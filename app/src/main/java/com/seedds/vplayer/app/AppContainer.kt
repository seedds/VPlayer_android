package com.seedds.vplayer.app

import android.content.Context
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.library.LibraryArtifacts
import com.seedds.vplayer.data.library.LibraryRepository
import com.seedds.vplayer.data.media.MediaProbe
import com.seedds.vplayer.data.media.ThumbnailCache
import com.seedds.vplayer.data.store.PlaybackStateStore
import com.seedds.vplayer.data.store.SettingsStore
import com.seedds.vplayer.server.LanAddressMonitor
import com.seedds.vplayer.server.ServerDependencies
import com.seedds.vplayer.server.UploadPage
import com.seedds.vplayer.server.UploadServerController
import com.seedds.vplayer.settings.SettingsCatalog

/**
 * Owns the process-wide singletons: the file layout, the JSON stores, the
 * library repository and the upload server. Built once in [VPlayerApp] and read
 * by view models and the server service. The app is small enough that hand
 * wiring beats a DI framework.
 */
class AppContainer(private val appContext: Context) {

    val paths = LibraryPaths(
        filesDir = appContext.filesDir,
        cacheDir = appContext.cacheDir,
    )

    val settingsStore = SettingsStore(paths.settingsFile)

    val playbackStateStore = PlaybackStateStore(paths.playbackStateFile)

    val libraryRepository = LibraryRepository(paths)

    val thumbnailCache = ThumbnailCache(paths)

    val libraryArtifacts = LibraryArtifacts(playbackStateStore, thumbnailCache)

    val mediaProbe = MediaProbe(paths, thumbnailCache, playbackStateStore)

    val lanAddressMonitor = LanAddressMonitor(appContext)

    private val uploadPage = UploadPage {
        appContext.assets.open(UploadPage.ASSET_NAME).use { it.readBytes().decodeToString() }
    }

    val serverController: UploadServerController by lazy {
        lateinit var controller: UploadServerController
        controller = UploadServerController(
            paths = paths,
            dependenciesFactory = { sessions ->
                ServerDependencies(
                    repository = libraryRepository,
                    sessions = sessions,
                    artifacts = libraryArtifacts,
                    page = uploadPage,
                    maxParallelUploads = {
                        SettingsCatalog.MaxParallelUploads.clamp(settingsStore.settings.value.maxParallelUploads)
                    },
                    onActivity = { status, message -> controller.publish(status, message) },
                )
            },
        )
        controller
    }

    init {
        paths.ensureDirectories()
    }
}
