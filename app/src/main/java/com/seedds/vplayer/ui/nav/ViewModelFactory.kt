package com.seedds.vplayer.ui.nav

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seedds.vplayer.app.AppContainer
import com.seedds.vplayer.library.LibraryViewModel
import com.seedds.vplayer.player.PlayerViewModel
import com.seedds.vplayer.settings.SettingsViewModel
import com.seedds.vplayer.upload.UploadViewModel

/** Builds the view models with the app's hand-wired dependencies. */
fun appViewModelFactory(application: Application, container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { LibraryViewModel(container) }
        initializer { SettingsViewModel(container) }
        initializer { UploadViewModel(application, container) }
        initializer { PlayerViewModel(application, container) }
    }
