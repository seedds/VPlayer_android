package com.seedds.vplayer.data.media

import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.model.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for library thumbnails.
 *
 * A cache entry is keyed by the video's identity *and* the capture settings, so
 * a file replaced in place (same path, new bytes) misses the cache and is
 * regenerated rather than showing the old frame.
 */
class ThumbnailCache(private val paths: LibraryPaths) {

    /** Seconds into the video the library thumbnail is taken from. */
    val captureSeconds: Int get() = CAPTURE_SECONDS

    val maxWidth: Int get() = MAX_WIDTH

    val maxHeight: Int get() = MAX_HEIGHT

    fun cacheKey(video: LibraryItem.Video): String =
        sha1("${video.fingerprint}|$CAPTURE_SECONDS|$MAX_WIDTH|$MAX_HEIGHT")

    fun fileFor(video: LibraryItem.Video): File = File(paths.thumbnailsDir, "${cacheKey(video)}.jpg")

    /** The cached thumbnail for a video, or null when it has not been made yet. */
    suspend fun cached(video: LibraryItem.Video): File? = withContext(Dispatchers.IO) {
        fileFor(video).takeIf { it.exists() && it.length() > 0L }
    }

    /** Cached thumbnails for many videos in one pass, without touching media. */
    suspend fun cachedAll(videos: List<LibraryItem.Video>): Map<String, File> =
        withContext(Dispatchers.IO) {
            videos.mapNotNull { video ->
                val file = fileFor(video)
                if (file.exists() && file.length() > 0L) video.relativePath to file else null
            }.toMap()
        }

    /**
     * Follows a thumbnail to a renamed or moved video. A failure is not worth
     * reporting: the worst case is that the thumbnail is generated again.
     */
    suspend fun move(from: LibraryItem.Video, to: LibraryItem.Video): Boolean =
        withContext(Dispatchers.IO) {
            val source = fileFor(from)
            val destination = fileFor(to)
            if (source.absolutePath == destination.absolutePath) return@withContext true
            if (!source.exists()) return@withContext false
            destination.delete()
            source.renameTo(destination)
        }

    suspend fun delete(video: LibraryItem.Video) = withContext(Dispatchers.IO) {
        fileFor(video).delete()
        Unit
    }

    /**
     * Deletes cache files that no longer belong to any video. Run after a full
     * library sweep, so a mid-walk cancellation cannot delete live entries.
     */
    suspend fun prune(videos: List<LibraryItem.Video>) = withContext(Dispatchers.IO) {
        val live = videos.mapTo(mutableSetOf()) { fileFor(it).name }
        paths.thumbnailsDir.listFiles().orEmpty().forEach { file ->
            if (file.name !in live) file.delete()
        }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * Ten seconds in is far enough past titles and fades to be a
         * recognisable frame, and short clips fall back to just before the end.
         */
        const val CAPTURE_SECONDS = 10
        const val MAX_WIDTH = 240
        const val MAX_HEIGHT = 240
        const val JPEG_QUALITY = 90
    }
}
