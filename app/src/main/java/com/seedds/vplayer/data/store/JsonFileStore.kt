package com.seedds.vplayer.data.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A small JSON document on disk, cached in memory and written one mutation at a
 * time.
 *
 * Two properties matter more than speed here. Writes are atomic, so a process
 * death mid-save cannot leave a truncated file that loses every saved playback
 * position. And a file that has become unreadable falls back to the default
 * value rather than throwing, because the library itself is the real data and
 * losing progress is annoying, not fatal.
 *
 * Callers are often on the main thread, and the playback map grows with the
 * library, so everything past the in-memory read (decoding, the caller's
 * transform, encoding and the write) runs on [Dispatchers.IO].
 *
 * @param normalize applied to whatever was loaded, so callers always see a
 *   valid value even if the file was hand-edited or written by an older build.
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val defaultValue: () -> T,
    private val normalize: (T) -> T = { it },
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: T? = null

    suspend fun read(): T {
        cached?.let { return it }
        return withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }
    }

    /**
     * Applies [transform] to the current value and persists the result.
     * Returning null from [transform] means "nothing changed", and skips the
     * write entirely.
     */
    suspend fun update(transform: (T) -> T?): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = loadLocked()
            val next = transform(current) ?: return@withLock current
            writeLocked(next)
            next
        }
    }

    /** Replaces the value unconditionally. */
    suspend fun write(value: T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeLocked(value)
            value
        }
    }

    /** Drops the in-memory copy; the next read goes back to disk. */
    fun invalidate() {
        cached = null
    }

    private fun loadLocked(): T {
        cached?.let { return it }
        val loaded = runCatching {
            if (!file.exists()) return@runCatching defaultValue()
            val text = file.readText()
            if (text.isBlank()) defaultValue() else json.decodeFromString(serializer, text)
        }.getOrElse { defaultValue() }
        val normalized = normalize(loaded)
        cached = normalized
        return normalized
    }

    private fun writeLocked(value: T) {
        cached = value
        val text = json.encodeToString(serializer, value)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!temp.renameTo(file)) {
            // renameTo refuses to clobber on some filesystems; fall back to
            // replacing the target explicitly.
            file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    companion object {
        val Format: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
            prettyPrint = false
        }
    }
}
