package com.seedds.vplayer.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation graph. */
sealed interface Route {
    @Serializable data object Library : Route
    @Serializable data object Upload : Route
    @Serializable data object Settings : Route

    /** Option picker for one numeric setting, addressed by its key name. */
    @Serializable data class SettingPicker(val key: String) : Route

    @Serializable data object Player : Route
}

enum class TabDestination(val label: String) {
    Library("Library"),
    Upload("Upload"),
    Settings("Settings"),
}
