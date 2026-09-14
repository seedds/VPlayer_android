package com.seedds.vplayer.app

import android.content.Context

/**
 * Owns the process-wide singletons: the file-backed stores, the library
 * repository, the media probe queue and the upload server controller. Built
 * once in [VPlayerApp] and handed to view models.
 */
class AppContainer(private val appContext: Context) {
    // Populated as the phases land: stores, repository, media probe, server.
}
