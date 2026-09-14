package com.seedds.vplayer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seedds.vplayer.app.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<Settings> = container.settingsStore.settings

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch { container.settingsStore.load() }
    }

    /**
     * Saves one setting. On failure the picker stays open with the reason
     * shown, so the user is not returned to a screen that silently kept the
     * old value.
     */
    fun update(key: SettingKey, value: Int, onSaved: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.settingsStore.update(key, value) }
                .onSuccess { onSaved() }
                .onFailure { _error.value = it.message?.takeIf(String::isNotBlank) ?: "Could not save settings." }
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
