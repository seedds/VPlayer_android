package com.seedds.vplayer.server

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryException
import com.seedds.vplayer.data.fs.LibraryNames
import com.seedds.vplayer.data.fs.LibraryPaths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import kotlin.random.Random

/** One upload in progress, from `init` until `complete` or `cancel`. */
data class UploadSession(
    val uploadId: String,
    /** Leaf file name after sanitising, e.g. `ep1.mp4`. */
    val fileName: String,
    /** Destination inside the library, e.g. `Shows/ep1.mp4`. */
    val relativePath: String,
    val tempFile: File,
    val totalSize: Long,
    val receivedBytes: Long = 0L,
    val expectedChunkIndex: Int = 0,
    val lastActivityAt: Long,
)

data class UploadInitResult(val uploadId: String, val relativePath: String, val chunkSize: Int)

data class ChunkProgress(val receivedBytes: Long, val totalBytes: Long)

/**
 * Tracks browser uploads and assembles them on disk.
 *
 * Chunks must arrive in order, which is what lets an upload be a plain append
 * to one temp file instead of a sparse file plus a received-ranges map. The
 * browser uploads a single file serially, so this costs nothing and removes a
 * whole class of partial-write bugs.
 *
 * There is no resume protocol: a failed chunk fails the file, and the browser
 * cancels and starts it again. Sessions abandoned by a closed tab are swept
 * after [SESSION_TTL_MS].
 */
class UploadSessionManager(
    private val paths: LibraryPaths,
    private val now: () -> Long = System::currentTimeMillis,
    private val idSuffix: () -> String = { randomSuffix() },
) {
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, UploadSession>()

    suspend fun activeSessions(): List<UploadSession> = mutex.withLock { sessions.values.toList() }

    suspend fun isEmpty(): Boolean = mutex.withLock { sessions.isEmpty() }

    /**
     * Reserves a destination and a temp file for a new upload. Parent folders
     * are created now so a later chunk cannot fail on a missing directory.
     */
    suspend fun init(requestedPath: String, totalSize: Long): Pair<UploadSession, UploadInitResult> {
        if (totalSize <= 0L) throw LibraryException(LibraryErrors.UPLOAD_EMPTY)

        val relativePath = LibraryNames.normalizeFilePath(requestedPath)
        val destination = paths.fileFor(relativePath)
        destination.parentFile?.mkdirs()
        paths.tempUploadsDir.mkdirs()

        val timestamp = now()
        val uploadId = "$timestamp-${idSuffix()}"
        val tempFile = File(paths.tempUploadsDir, "$timestamp-${idSuffix()}.upload")

        val session = UploadSession(
            uploadId = uploadId,
            fileName = relativePath.substringAfterLast('/'),
            relativePath = relativePath,
            tempFile = tempFile,
            totalSize = totalSize,
            lastActivityAt = timestamp,
        )
        mutex.withLock { sessions[uploadId] = session }

        return session to UploadInitResult(
            uploadId = uploadId,
            relativePath = relativePath,
            chunkSize = CHUNK_SIZE,
        )
    }

    /**
     * Validates and appends one chunk. [source] is consumed only after every
     * check passes, so a rejected chunk never touches the temp file.
     */
    suspend fun appendChunk(
        uploadId: String,
        chunkIndex: Int,
        totalChunks: Int,
        totalSize: Long,
        source: InputStream?,
    ): ChunkProgress {
        val session = mutex.withLock {
            val existing = sessions[uploadId] ?: throw LibraryException(LibraryErrors.UPLOAD_SESSION_NOT_FOUND)
            if (existing.totalSize != totalSize) throw LibraryException(LibraryErrors.UPLOAD_SIZE_MISMATCH)
            if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                throw LibraryException(LibraryErrors.CHUNK_INDEX_OUT_OF_RANGE)
            }
            if (chunkIndex != existing.expectedChunkIndex) {
                throw LibraryException(LibraryErrors.unexpectedChunkOrder(existing.expectedChunkIndex))
            }
            existing
        }
        if (source == null) throw LibraryException(LibraryErrors.UPLOAD_CHUNK_MISSING)

        val remaining = session.totalSize - session.receivedBytes
        val written = try {
            appendToTemp(session, source, remaining)
        } catch (overflow: ChunkTooLargeException) {
            // The chunk claimed more bytes than the upload declared. Drop the
            // session rather than leaving a half-written file behind.
            discard(uploadId)
            throw LibraryException(LibraryErrors.CHUNK_EXCEEDS_SIZE)
        }

        return mutex.withLock {
            val current = sessions[uploadId] ?: throw LibraryException(LibraryErrors.UPLOAD_SESSION_NOT_FOUND)
            val updated = current.copy(
                receivedBytes = current.receivedBytes + written,
                expectedChunkIndex = current.expectedChunkIndex + 1,
                lastActivityAt = now(),
            )
            sessions[uploadId] = updated
            ChunkProgress(receivedBytes = updated.receivedBytes, totalBytes = updated.totalSize)
        }
    }

    /**
     * Moves a finished upload into the library, replacing any file already at
     * that path. Replacing rather than auto-renaming is deliberate: re-sending
     * a file is how you correct a bad upload.
     */
    suspend fun complete(uploadId: String): UploadSession {
        val session = mutex.withLock {
            sessions[uploadId] ?: throw LibraryException(LibraryErrors.UPLOAD_SESSION_NOT_FOUND)
        }
        try {
            if (session.receivedBytes != session.totalSize) {
                throw LibraryException(LibraryErrors.UPLOAD_INCOMPLETE)
            }
            val destination = paths.fileFor(session.relativePath)
            if (destination.isDirectory) throw LibraryException(LibraryErrors.FOLDER_NAME_TAKEN)
            destination.parentFile?.mkdirs()
            if (destination.exists() && !destination.delete()) {
                throw LibraryException(LibraryErrors.COULD_NOT_MOVE)
            }
            // Flushed once here rather than after every chunk: a partial upload
            // is thrown away anyway, so only the finished file needs to survive
            // a power cut, and a flush per chunk held up every next chunk.
            java.io.FileOutputStream(session.tempFile, true).use { it.fd.sync() }
            if (!session.tempFile.renameTo(destination)) {
                session.tempFile.copyTo(destination, overwrite = true)
                session.tempFile.delete()
            }
        } catch (error: Throwable) {
            discard(uploadId)
            throw error
        }
        mutex.withLock { sessions.remove(uploadId) }
        return session
    }

    /** Drops a session and its temp file. Unknown ids are not an error. */
    suspend fun cancel(uploadId: String): UploadSession? = discard(uploadId)

    /**
     * Removes sessions whose browser has gone away. Without this a closed tab
     * would hold the keep-awake and the "uploading" banner until restart.
     */
    suspend fun sweepStale(): List<UploadSession> {
        val deadline = now() - SESSION_TTL_MS
        val stale = mutex.withLock {
            val expired = sessions.values.filter { it.lastActivityAt <= deadline }
            expired.forEach { sessions.remove(it.uploadId) }
            expired
        }
        stale.forEach { it.tempFile.delete() }
        return stale
    }

    /** Drops every session; used when the server stops. */
    suspend fun clear(): List<UploadSession> {
        val dropped = mutex.withLock {
            val all = sessions.values.toList()
            sessions.clear()
            all
        }
        dropped.forEach { it.tempFile.delete() }
        return dropped
    }

    private suspend fun discard(uploadId: String): UploadSession? {
        val session = mutex.withLock { sessions.remove(uploadId) }
        session?.tempFile?.delete()
        return session
    }

    private fun appendToTemp(session: UploadSession, source: InputStream, remaining: Long): Long {
        var written = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        java.io.FileOutputStream(session.tempFile, session.receivedBytes > 0L).use { output ->
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                written += read
                if (written > remaining) throw ChunkTooLargeException()
                output.write(buffer, 0, read)
            }
        }
        return written
    }

    private class ChunkTooLargeException : Exception()

    companion object {
        /** Matches the browser uploader's slice size. */
        const val CHUNK_SIZE = 1024 * 1024

        const val SESSION_TTL_MS = 5 * 60 * 1000L

        private const val COPY_BUFFER_BYTES = 64 * 1024

        private const val ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

        private fun randomSuffix(): String =
            (1..6).map { ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)] }.joinToString("")
    }
}
