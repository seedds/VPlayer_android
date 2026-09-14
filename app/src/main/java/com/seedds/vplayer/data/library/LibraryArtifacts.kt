package com.seedds.vplayer.data.library

import com.seedds.vplayer.data.media.ThumbnailCache
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.store.PlaybackStateStore

/**
 * Keeps the per-video side data — saved progress and cached thumbnails — in
 * step with the files themselves.
 *
 * Videos have to be collected *before* a delete, while the files are still
 * there to enumerate, which is why callers pass the list in rather than the
 * item that is about to disappear.
 */
class LibraryArtifacts(
    private val playbackStateStore: PlaybackStateStore,
    private val thumbnailCache: ThumbnailCache,
) {

    /** Forgets everything remembered about videos that are being deleted. */
    suspend fun forget(videos: List<LibraryItem.Video>) {
        if (videos.isEmpty()) return
        playbackStateStore.remove(videos.map(LibraryItem.Video::relativePath))
        videos.forEach { thumbnailCache.delete(it) }
    }

    /**
     * Follows videos to their new paths after a rename or move of the video
     * itself or of a folder above it.
     *
     * @return the old-to-new path pairs that were applied, so an in-memory
     *   thumbnail map can be re-keyed too.
     */
    suspend fun relink(
        videos: List<LibraryItem.Video>,
        oldRootPath: String,
        newRootPath: String,
    ): List<Pair<String, String>> {
        if (videos.isEmpty() || oldRootPath == newRootPath) return emptyList()

        val pairs = videos.map { video ->
            video to video.copy(relativePath = newRootPath + video.relativePath.removePrefix(oldRootPath))
        }
        playbackStateStore.move(pairs.map { (from, to) -> from.relativePath to to.relativePath })
        pairs.forEach { (from, to) ->
            if (!thumbnailCache.move(from, to)) {
                // The move failed, so drop the stale entry and let the next
                // hydration pass regenerate it at the new path.
                thumbnailCache.delete(from)
            }
        }
        return pairs.map { (from, to) -> from.relativePath to to.relativePath }
    }
}
