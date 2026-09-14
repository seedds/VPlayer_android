package com.seedds.vplayer.server

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryException
import com.seedds.vplayer.data.fs.LibraryPaths
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class UploadSessionManagerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var paths: LibraryPaths
    private var clock = 10_000L
    private var idCounter = 0

    @Before
    fun setUp() {
        paths = LibraryPaths(filesDir = temp.newFolder("files"), cacheDir = temp.newFolder("cache"))
        paths.ensureDirectories()
    }

    private fun manager() = UploadSessionManager(
        paths = paths,
        now = { clock },
        idSuffix = { "id${idCounter++}" },
    )

    private suspend fun UploadSessionManager.sendAll(uploadId: String, bytes: ByteArray, chunks: Int = 1) {
        val size = bytes.size
        val chunkSize = (size + chunks - 1) / chunks
        repeat(chunks) { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, size)
            appendChunk(
                uploadId = uploadId,
                chunkIndex = index,
                totalChunks = chunks,
                totalSize = size.toLong(),
                source = ByteArrayInputStream(bytes.copyOfRange(start, end)),
            )
        }
    }

    private fun expectFailure(block: suspend () -> Unit): String = runCatching {
        kotlinx.coroutines.runBlocking { block() }
    }.exceptionOrNull().let { error ->
        assertTrue("expected a LibraryException, got $error", error is LibraryException)
        error!!.message!!
    }

    @Test
    fun `a complete upload lands in the library`() = runTest {
        val manager = manager()
        val payload = "hello world".toByteArray()
        val (_, init) = manager.init("Shows/ep1.mp4", payload.size.toLong())
        manager.sendAll(init.uploadId, payload, chunks = 3)
        val session = manager.complete(init.uploadId)

        val saved = paths.fileFor(session.relativePath)
        assertEquals("Shows/ep1.mp4", session.relativePath)
        assertEquals("hello world", saved.readText())
        assertTrue(manager.isEmpty())
        assertFalse(session.tempFile.exists())
    }

    @Test
    fun `the destination path is sanitised at init`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("../Shows/My Movie.MP4", 4)
        assertEquals("folder/Shows/My Movie.mp4", init.relativePath)
        assertEquals(UploadSessionManager.CHUNK_SIZE, init.chunkSize)
    }

    @Test
    fun `an empty upload is refused`() {
        val message = expectFailure { manager().init("a.mp4", 0) }
        assertEquals(LibraryErrors.UPLOAD_EMPTY, message)
    }

    @Test
    fun `an unknown session is refused`() {
        val message = expectFailure {
            manager().appendChunk("nope", 0, 1, 4, ByteArrayInputStream(ByteArray(4)))
        }
        assertEquals(LibraryErrors.UPLOAD_SESSION_NOT_FOUND, message)
    }

    @Test
    fun `a declared size that disagrees with the session is refused`() {
        val manager = manager()
        val message = expectFailure {
            val (_, init) = manager.init("a.mp4", 10)
            manager.appendChunk(init.uploadId, 0, 1, 11, ByteArrayInputStream(ByteArray(10)))
        }
        assertEquals(LibraryErrors.UPLOAD_SIZE_MISMATCH, message)
    }

    @Test
    fun `a chunk index outside the declared count is refused`() {
        val manager = manager()
        val message = expectFailure {
            val (_, init) = manager.init("a.mp4", 10)
            manager.appendChunk(init.uploadId, 5, 2, 10, ByteArrayInputStream(ByteArray(10)))
        }
        assertEquals(LibraryErrors.CHUNK_INDEX_OUT_OF_RANGE, message)
    }

    @Test
    fun `chunks must arrive in order`() {
        val manager = manager()
        val message = expectFailure {
            val (_, init) = manager.init("a.mp4", 10)
            manager.appendChunk(init.uploadId, 1, 2, 10, ByteArrayInputStream(ByteArray(5)))
        }
        assertEquals(LibraryErrors.unexpectedChunkOrder(0), message)
    }

    @Test
    fun `a missing chunk body is refused`() {
        val manager = manager()
        val message = expectFailure {
            val (_, init) = manager.init("a.mp4", 10)
            manager.appendChunk(init.uploadId, 0, 1, 10, null)
        }
        assertEquals(LibraryErrors.UPLOAD_CHUNK_MISSING, message)
    }

    @Test
    fun `a chunk longer than the declared upload drops the session`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("a.mp4", 4)
        val message = expectFailure {
            manager.appendChunk(init.uploadId, 0, 1, 4, ByteArrayInputStream(ByteArray(9)))
        }
        assertEquals(LibraryErrors.CHUNK_EXCEEDS_SIZE, message)
        assertTrue(manager.isEmpty())
        assertEquals(0, paths.tempUploadsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `completing early is refused and abandons the session`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("a.mp4", 10)
        manager.appendChunk(init.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))
        val message = expectFailure { manager.complete(init.uploadId) }
        assertEquals(LibraryErrors.UPLOAD_INCOMPLETE, message)
        assertTrue(manager.isEmpty())
    }

    @Test
    fun `uploading over an existing file replaces it`() = runTest {
        val manager = manager()
        paths.fileFor("a.mp4").writeText("old content")

        val payload = "new".toByteArray()
        val (_, init) = manager.init("a.mp4", payload.size.toLong())
        manager.sendAll(init.uploadId, payload)
        manager.complete(init.uploadId)

        assertEquals("new", paths.fileFor("a.mp4").readText())
    }

    @Test
    fun `uploading onto a folder name is refused`() = runTest {
        val manager = manager()
        paths.fileFor("busy").mkdirs()
        val payload = "x".toByteArray()
        val (_, init) = manager.init("busy", payload.size.toLong())
        manager.sendAll(init.uploadId, payload)
        val message = expectFailure { manager.complete(init.uploadId) }
        assertEquals(LibraryErrors.FOLDER_NAME_TAKEN, message)
    }

    @Test
    fun `cancelling removes the session and its temp file`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("a.mp4", 10)
        manager.appendChunk(init.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))
        val cancelled = manager.cancel(init.uploadId)!!
        assertFalse(cancelled.tempFile.exists())
        assertTrue(manager.isEmpty())
    }

    @Test
    fun `cancelling an unknown session is not an error`() = runTest {
        assertEquals(null, manager().cancel("nope"))
    }

    @Test
    fun `an abandoned session is swept once it goes quiet`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("a.mp4", 10)
        manager.appendChunk(init.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))

        clock += UploadSessionManager.SESSION_TTL_MS - 1
        assertTrue(manager.sweepStale().isEmpty())

        clock += 2
        val swept = manager.sweepStale()
        assertEquals(1, swept.size)
        assertTrue(manager.isEmpty())
        assertFalse(swept.first().tempFile.exists())
    }

    @Test
    fun `chunk activity keeps a session alive`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("a.mp4", 10)
        clock += UploadSessionManager.SESSION_TTL_MS - 1
        manager.appendChunk(init.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))
        clock += 2
        assertTrue(manager.sweepStale().isEmpty())
    }

    @Test
    fun `clearing drops every session and temp file`() = runTest {
        val manager = manager()
        val (_, first) = manager.init("a.mp4", 10)
        val (_, second) = manager.init("b.mp4", 10)
        manager.appendChunk(first.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))
        manager.appendChunk(second.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))

        val dropped = manager.clear()
        assertEquals(2, dropped.size)
        assertTrue(manager.isEmpty())
        assertTrue(dropped.none { it.tempFile.exists() })
    }

    @Test
    fun `progress is reported as bytes accumulate`() = runTest {
        val manager = manager()
        val (_, init) = manager.init("a.mp4", 10)
        val first = manager.appendChunk(init.uploadId, 0, 2, 10, ByteArrayInputStream(ByteArray(4)))
        assertEquals(4L, first.receivedBytes)
        assertEquals(10L, first.totalBytes)
        val second = manager.appendChunk(init.uploadId, 1, 2, 10, ByteArrayInputStream(ByteArray(6)))
        assertEquals(10L, second.receivedBytes)
    }

    @Test
    fun `concurrent sessions stay independent`() = runTest {
        val manager = manager()
        val (_, a) = manager.init("a.mp4", 4)
        val (_, b) = manager.init("b.mp4", 4)
        manager.appendChunk(a.uploadId, 0, 1, 4, ByteArrayInputStream("aaaa".toByteArray()))
        manager.appendChunk(b.uploadId, 0, 1, 4, ByteArrayInputStream("bbbb".toByteArray()))
        manager.complete(a.uploadId)
        manager.complete(b.uploadId)
        assertEquals("aaaa", paths.fileFor("a.mp4").readText())
        assertEquals("bbbb", paths.fileFor("b.mp4").readText())
    }

    @Test
    fun `library paths resolve relative to the videos directory`() {
        assertEquals(File(paths.videosDir, "Shows/ep1.mp4"), paths.fileFor("Shows/ep1.mp4"))
        assertEquals(paths.videosDir, paths.fileFor(""))
        assertEquals("Shows/ep1.mp4", paths.relativePathOf(File(paths.videosDir, "Shows/ep1.mp4")))
        assertEquals(null, paths.relativePathOf(File(paths.filesDir, "elsewhere.mp4")))
    }
}
