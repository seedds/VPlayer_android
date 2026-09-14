package com.seedds.vplayer.app

import android.app.Application

class VPlayerApp : Application() {

    /** Manually wired singletons; the app is small enough not to need DI. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
