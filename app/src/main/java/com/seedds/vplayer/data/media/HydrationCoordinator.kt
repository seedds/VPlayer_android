package com.seedds.vplayer.data.media

import com.seedds.vplayer.data.model.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *
 * Two passes share that budget. The folder pass covers what is on screen and
 * the library pass sweeps everything else; each replaces only an earlier pass
 * of its own kind, so opening a folder never cancels the background sweep.
 */
class HydrationCoordinator(
    private val scope: CoroutineScope,
    private val probe: suspend (video: LibraryItem.Video, knownDuration: Double?) -> ProbeResult,
    private val thumbnailCache: ThumbnailCache,
    private val onResults: (List<ProbeResult>) -> Unit,
) {
    /**
     * Fingerprints of everything already probed this session, so a file
     * replaced in place is probed again.
     */
    private val probedKeys = Collections.synchronizedSet(mutableSetOf<String>())

    private var folderJob: Job? = null
    private var libraryJob: Job? = null

    /** Cached thumbnails for videos already on screen, read without any decoding. */
    suspend fun cachedThumbnails(videos: List<LibraryItem.Video>): Map<String, File> =
        thumbnailCache.cachedAll(videos)

    /**
     * Hydrates the folder on screen, replacing any folder pass already running.
     * The newest folder wins because it is what the user is looking at now.
     */
    fun hydrateFolder(videos: List<LibraryItem.Video>, knownDurations: Map<String, Double?>) {
        folderJob?.cancel()
        folderJob = scope.launch { probeAll(videos, knownDurations) }
    }

    /**
     * Hydrates the whole library and then deletes cache files no video claims
     * any more, replacing any library pass already running.
     */
    fun hydrateLibrary(allVideos: List<LibraryItem.Video>, knownDurations: Map<String, Double?>) {
        libraryJob?.cancel()
        libraryJob = scope.launch {
            probeAll(allVideos, knownDurations)
            // Only a sweep that ran to the end prunes. A cancelled one has been
            // replaced by a sweep with a fresher listing, and pruning against
            // the stale one could delete the thumbnail of a file added since.
            currentCoroutineContext().ensureActive()
            thumbnailCache.prune(allVideos)
        }
    }

    fun cancel() {
        folderJob?.cancel()
        libraryJob?.cancel()
        folderJob = null
        libraryJob = null
    }

    private suspend fun probeAll(videos: List<LibraryItem.Video>, knownDurations: Map<String, Double?>) {
        val batch = mutableListOf<ProbeResult>()
        var lastFlush = System.currentTimeMillis()

        try {
            for (video in videos) {
                currentCoroutineContext().ensureActive()
                // Claimed when it comes up, not when the pass starts, so a pass
                // cancelled part-way leaves the rest of its list for the next.
                if (!probedKeys.add(video.fingerprint)) continue

                // Once claimed, the probe runs to the end even if the pass is
                // cancelled meanwhile. The decoder cannot be interrupted anyway,
                // and abandoning it would leave the file claimed but never probed.
                val knownDuration = knownDurations[video.relativePath]
                val result = try {
                    withContext(NonCancellable) { probe(video, knownDuration) }
                } catch (e: Exception) {
                    ProbeResult(video.relativePath, knownDuration, null)
                }
                batch += result

                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    onResults(batch.toList())
                    batch.clear()
                    lastFlush = now
                }
            }
        } finally {
            // These files are claimed, so no later pass will report them; what
            // was learned belongs on screen even when the pass was cut short.
            if (batch.isNotEmpty()) onResults(batch.toList())
        }
    }

    private companion object {
        /**
         * Long enough to coalesce a burst of fast cache hits, short enough that
         * thumbnails visibly stream in rather than landing in one jump.
         */
        const val FLUSH_INTERVAL_MS = 250L
    }
}
