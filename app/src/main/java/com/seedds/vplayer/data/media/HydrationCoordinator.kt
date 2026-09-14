package com.seedds.vplayer.data.media

import com.seedds.vplayer.data.model.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

/**
 * Fills in the durations and thumbnails the library shows, without making the
 * list feel slow.
 *
 * Three things keep it out of the way. Probes run one at a time, because the
 * cost is dominated by the platform decoder and running several only makes them
 * contend. Results are flushed in batches, so a hundred arriving thumbnails
 * cost a handful of recompositions rather than a hundred. And every file is
 * probed at most once per process, including files that failed, so a broken
 * video cannot be retried in a tight loop for as long as the app is open.
 */
class HydrationCoordinator(
    private val scope: CoroutineScope,
    private val probe: MediaProbe,
    private val thumbnailCache: ThumbnailCache,
    private val onResults: (List<ProbeResult>) -> Unit,
) {
    /**
     * Identity of everything already probed this session: path, size and
     * modified time, so a file replaced in place is probed again.
     */
    private val probedKeys = Collections.synchronizedSet(mutableSetOf<String>())

    private var job: Job? = null

    private fun keyOf(video: LibraryItem.Video) =
        "${video.relativePath}|${video.size}|${video.modified}"

    /** Cached thumbnails for videos already on screen, read without any decoding. */
    suspend fun cachedThumbnails(videos: List<LibraryItem.Video>): Map<String, File> =
        thumbnailCache.cachedAll(videos)

    /**
     * Starts hydrating [videos], replacing any pass already running. The newest
     * request wins because it describes what the user is looking at now.
     */
    fun hydrate(videos: List<LibraryItem.Video>, knownDurations: Map<String, Double?>) {
        job?.cancel()
        val pending = videos.filter { probedKeys.add(keyOf(it)) }
        if (pending.isEmpty()) return

        job = scope.launch {
            val batch = mutableListOf<ProbeResult>()
            var lastFlush = System.currentTimeMillis()

            for (video in pending) {
                if (!currentCoroutineContext().isActive) {
                    // Release the key so a later pass can pick this file up;
                    // otherwise a cancelled probe would leave it unhydrated for
                    // the rest of the session.
                    probedKeys.remove(keyOf(video))
                    break
                }

                val result = runCatching { probe.probe(video, knownDurations[video.relativePath]) }
                    .getOrElse { ProbeResult(video.relativePath, knownDurations[video.relativePath], null) }
                batch += result

                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    onResults(batch.toList())
                    batch.clear()
                    lastFlush = now
                }
            }

            if (batch.isNotEmpty() && currentCoroutineContext().isActive) onResults(batch.toList())
        }
    }

    /**
     * Deletes cache files no video claims any more. Only safe once a full
     * library walk has finished; running it against a partial list would delete
     * thumbnails that are still in use.
     */
    fun pruneAfter(allVideos: List<LibraryItem.Video>) {
        scope.launch {
            job?.join()
            if (currentCoroutineContext().isActive) thumbnailCache.prune(allVideos)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /**
         * Long enough to coalesce a burst of fast cache hits, short enough that
         * thumbnails visibly stream in rather than landing in one jump.
         */
        const val FLUSH_INTERVAL_MS = 250L
    }
}
