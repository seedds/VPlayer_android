package com.seedds.vplayer.ui.nav

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seedds.vplayer.app.AppContainer
import com.seedds.vplayer.library.LibraryViewModel
import com.seedds.vplayer.settings.SettingsViewModel

/** Builds the view models with the app's hand-wired dependencies. */
fun appViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { LibraryViewModel(container) }
    initializer { SettingsViewModel(container) }
}
