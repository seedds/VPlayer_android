package com.seedds.vplayer.data.store

import com.seedds.vplayer.settings.SettingKey
import com.seedds.vplayer.settings.Settings
import com.seedds.vplayer.settings.SettingsCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The three user settings, persisted as one small JSON document and exposed as
 * state so screens re-render as soon as a value changes.
 */
class SettingsStore(file: File) {

    private val store = JsonFileStore(
        file = file,
        serializer = Settings.serializer(),
        json = JsonFileStore.Format,
        defaultValue = { Settings() },
        normalize = Settings::clamped,
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    suspend fun load(): Settings = store.read().also { _settings.value = it }

    suspend fun update(key: SettingKey, value: Int): Settings {
        val clamped = SettingsCatalog[key].clamp(value)
        return store.update { current ->
            if (current[key] == clamped) null else current.with(key, clamped)
        }.also { _settings.value = it }
    }
}
