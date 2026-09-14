package com.seedds.vplayer.upload

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seedds.vplayer.app.AppContainer
import com.seedds.vplayer.data.fs.DEFAULT_SERVER_PORT
import com.seedds.vplayer.data.fs.normalizePort
import com.seedds.vplayer.data.model.UploadActivity
import com.seedds.vplayer.server.ServerState
import com.seedds.vplayer.server.UploadServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UploadViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    val serverState: StateFlow<ServerState> = container.serverController.state

    val activity: StateFlow<UploadActivity> = container.serverController.activity

    val activeUploadCount: StateFlow<Int> = container.serverController.activeUploadCount

    val lanAddress: StateFlow<String?> = container.lanAddressMonitor.addresses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.lanAddressMonitor.currentAddress())

    private val _portInput = MutableStateFlow(DEFAULT_SERVER_PORT.toString())
    val portInput: StateFlow<String> = _portInput.asStateFlow()

    init {
        // Keep the activity line fresh while uploads run: progress is stored in
        // the session manager and only becomes visible when it is folded in.
        viewModelScope.launch { container.serverController.refreshActivity() }
    }

    fun setPortInput(value: String) {
        _portInput.value = value.filter(Char::isDigit).take(5)
    }

    /** Starts, or restarts on a new port, from the Upload tab. */
    fun startServer() {
        val port = normalizePort(_portInput.value)
        _portInput.value = port.toString()
        UploadServerService.start(getApplication(), port)
    }

    fun stopServer() {
        UploadServerService.stop(getApplication())
    }

    /** Poll the aggregate while a transfer is running so bytes keep ticking. */
    fun refreshActivity() {
        viewModelScope.launch { container.serverController.refreshActivity() }
    }
}
