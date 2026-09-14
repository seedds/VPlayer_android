package com.seedds.vplayer.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seedds.vplayer.app.AppContainer
import com.seedds.vplayer.library.LibraryScreen
import com.seedds.vplayer.library.LibraryViewModel
import com.seedds.vplayer.settings.SettingKey
import com.seedds.vplayer.settings.SettingPickerScreen
import com.seedds.vplayer.settings.SettingsScreen
import com.seedds.vplayer.settings.SettingsViewModel
import com.seedds.vplayer.upload.UploadScreen
import com.seedds.vplayer.upload.UploadViewModel
import com.seedds.vplayer.ui.theme.VColors

/** Which full-screen destination is showing over the tabs, if any. */
private sealed interface Overlay {
    data class Picker(val key: SettingKey) : Overlay
}

/**
 * Root shell: three bottom tabs with no icons, and the tab bar painted to match
 * the app background rather than a raised Material surface.
 */
@Composable
fun AppScaffold(container: AppContainer) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val factory = remember(container) { appViewModelFactory(application, container) }
    val libraryViewModel: LibraryViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    val uploadViewModel: UploadViewModel = viewModel(factory = factory)

    // The browser can add, rename or delete files at any time; refresh the
    // library whenever the server says something changed.
    DisposableEffect(container) {
        container.serverController.setLibraryChangedListener {
            libraryViewModel.refresh()
            libraryViewModel.hydrateWholeLibrary()
        }
        onDispose { container.serverController.setLibraryChangedListener(null) }
    }

    var selectedTab by remember { mutableStateOf(TabDestination.Library) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }

    when (val current = overlay) {
        is Overlay.Picker -> {
            SettingPickerScreen(
                viewModel = settingsViewModel,
                settingKey = current.key,
                onBack = { overlay = null },
                modifier = Modifier.fillMaxSize(),
            )
            return
        }
        null -> Unit
    }

    Scaffold(
        containerColor = VColors.Background,
        bottomBar = {
            Column {
                HorizontalDivider(thickness = Dp.Hairline, color = VColors.DividerTabBar)
                NavigationBar(containerColor = VColors.Background, tonalElevation = 0.dp) {
                    TabDestination.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                                when (tab) {
                                    TabDestination.Library -> libraryViewModel.refresh()
                                    else -> libraryViewModel.cancelSelection()
                                }
                            },
                            icon = {},
                            label = { Text(text = tab.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = VColors.Primary,
                                unselectedTextColor = VColors.TextSecondary,
                                indicatorColor = VColors.Background,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VColors.Background)
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                TabDestination.Library -> LibraryScreen(
                    viewModel = libraryViewModel,
                    onPlayVideo = { /* wired up with the player */ },
                )

                TabDestination.Upload -> UploadScreen(viewModel = uploadViewModel)

                TabDestination.Settings -> SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenPicker = { key -> overlay = Overlay.Picker(key) },
                )
            }
        }
    }
}
