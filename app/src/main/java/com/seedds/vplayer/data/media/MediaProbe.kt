package com.seedds.vplayer.data.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.store.PlaybackStateStore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/** What a probe learned about a video. */
data class ProbeResult(
    val relativePath: String,
    val durationSeconds: Double?,
    val thumbnail: File?,
)

/**
 * Reads durations and cover frames out of video files.
 *
 * Every call runs on a single dedicated thread. [MediaMetadataRetriever] is not
 * thread-safe and its calls cannot be interrupted, so serialising them keeps a
 * slow or broken file from corrupting another probe, and keeps decoding off the
 * threads that draw the library.
 */
class MediaProbe(
    private val paths: LibraryPaths,
    private val thumbnailCache: ThumbnailCache,
    private val playbackStateStore: PlaybackStateStore,
) {
    private val probeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vplayer-media-probe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Probes one video, skipping the work entirely when the answer is already
     * known. Reopening an already-hydrated library should cost nothing.
     */
    suspend fun probe(video: LibraryItem.Video, knownDuration: Double?): ProbeResult {
        val cached = thumbnailCache.cached(video)
        if (cached != null && knownDuration != null && knownDuration >= 0.0) {
            return ProbeResult(video.relativePath, knownDuration, cached)
        }

        return withContext(probeDispatcher) {
            val file = paths.fileFor(video.relativePath)
            if (!file.exists()) return@withContext ProbeResult(video.relativePath, knownDuration, cached)

            val retriever = MediaMetadataRetriever()
            try {
                // A file the framework cannot open at all is not worth retrying
                // for either value; report what we already had and move on.
                runCatching { retriever.setDataSource(file.absolutePath) }
                    .onFailure { return@withContext ProbeResult(video.relativePath, knownDuration, cached) }

                val duration = withTimeoutOrNull(SOURCE_LOAD_TIMEOUT_MS) {
                    readDurationSeconds(retriever)
                } ?: knownDuration

                if (duration != null && duration >= 0.0) {
                    playbackStateStore.saveDuration(video.relativePath, duration)
                }

                val thumbnail = cached ?: withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                    generateThumbnail(retriever, video, duration)
                }

                ProbeResult(video.relativePath, duration, thumbnail)
            } finally {
                releaseQuietly(retriever)
            }
        }
    }

    /** A single frame for the scrub preview, taken from an already-open file. */
    suspend fun previewFrame(file: File, positionSeconds: Double, width: Int, height: Int): Bitmap? =
        withContext(probeDispatcher) {
            val retriever = MediaMetadataRetriever()
            try {
                runCatching { retriever.setDataSource(file.absolutePath) }.getOrElse { return@withContext null }
                frameAt(retriever, positionSeconds, width, height)
            } finally {
                releaseQuietly(retriever)
            }
        }

    private fun readDurationSeconds(retriever: MediaMetadataRetriever): Double? =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { it / 1000.0 }

    /**
     * Ten seconds in usually clears titles and fades. A clip shorter than that
     * falls back to just before its end, and anything that fails falls back to
     * the first frame rather than showing nothing.
     */
    private fun generateThumbnail(
        retriever: MediaMetadataRetriever,
        video: LibraryItem.Video,
        duration: Double?,
    ): File? {
        val preferred = if (duration != null && duration > 0.0) {
            minOf(ThumbnailCache.CAPTURE_SECONDS.toDouble(), maxOf(0.0, duration - 1.0))
        } else {
            ThumbnailCache.CAPTURE_SECONDS.toDouble()
        }
        val candidates = if (preferred > 0.0) listOf(preferred, 0.0) else listOf(0.0)

        for (seconds in candidates) {
            val bitmap = frameAt(retriever, seconds, ThumbnailCache.MAX_WIDTH, ThumbnailCache.MAX_HEIGHT)
                ?: continue
            val target = thumbnailCache.fileFor(video)
            val written = runCatching {
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, ThumbnailCache.JPEG_QUALITY, out)
                }
            }.isSuccess
            bitmap.recycle()
            if (written && target.length() > 0L) return target
        }
        return null
    }

    private fun frameAt(
        retriever: MediaMetadataRetriever,
        positionSeconds: Double,
        width: Int,
        height: Int,
    ): Bitmap? = runCatching {
        val micros = (positionSeconds.coerceAtLeast(0.0) * 1_000_000L).toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                micros,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                width,
                height,
            )
        } else {
            retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { frame -> scaleWithin(frame, width, height) }
        }
    }.getOrNull()

    private fun scaleWithin(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(
            maxWidth.toFloat() / source.width,
            maxHeight.toFloat() / source.height,
            1f,
        )
        if (scale >= 1f) return source
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Released off the probe thread: release() can block on a decoder that has
     * wedged, and the queue behind it should not wait for that.
     */
    private fun releaseQuietly(retriever: MediaMetadataRetriever) {
        Thread { runCatching { retriever.release() } }
            .apply { isDaemon = true }
            .start()
    }

    companion object {
        /** Long enough for a slow file, short enough not to stall the queue. */
        const val SOURCE_LOAD_TIMEOUT_MS = 4_000L
        const val THUMBNAIL_TIMEOUT_MS = 6_000L
    }
}
