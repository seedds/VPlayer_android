package com.seedds.vplayer.app

import android.content.Context
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.library.LibraryRepository
import com.seedds.vplayer.data.media.ThumbnailCache
import com.seedds.vplayer.data.store.PlaybackStateStore
import com.seedds.vplayer.data.store.SettingsStore

/**
 * Owns the process-wide singletons: the file layout, the two JSON stores and
 * the library repository. Built once in [VPlayerApp] and read by view models.
 * The app is small enough that hand wiring beats a DI framework.
 */
class AppContainer(appContext: Context) {

    val paths = LibraryPaths(
        filesDir = appContext.filesDir,
        cacheDir = appContext.cacheDir,
    )

    val settingsStore = SettingsStore(paths.settingsFile)

    val playbackStateStore = PlaybackStateStore(paths.playbackStateFile)

    val libraryRepository = LibraryRepository(paths)

    val thumbnailCache = ThumbnailCache(paths)

    init {
        paths.ensureDirectories()
    }
}
