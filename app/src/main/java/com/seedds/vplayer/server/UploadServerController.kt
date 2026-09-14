package com.seedds.vplayer.server

import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.fs.isValidServerPort
import com.seedds.vplayer.data.model.ActiveUploadRow
import com.seedds.vplayer.data.model.UploadActivity
import com.seedds.vplayer.data.model.UploadStatus
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the upload server is doing, as the Upload tab renders it. */
sealed interface ServerState {
    data object Stopped : ServerState
    data class Starting(val port: Int) : ServerState
    data class Running(val port: Int) : ServerState
    data class Error(val message: String) : ServerState

    val runningPort: Int? get() = (this as? Running)?.port
    val isRunning: Boolean get() = this is Running
}

/**
 * Owns the HTTP server for the life of the process.
 *
 * Starting on the port that is already bound adopts the running server instead
 * of restarting it, so repeated starts (app launch, tab revisit, a service
 * restart) are harmless and never interrupt an upload in flight.
 */
class UploadServerController(
    private val paths: LibraryPaths,
    private val dependenciesFactory: (UploadSessionManager) -> ServerDependencies,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _activity = MutableStateFlow(
        UploadActivity.of(UploadStatus.Idle, "Starting local server...", now()),
    )
    val activity: StateFlow<UploadActivity> = _activity.asStateFlow()

    val sessions = UploadSessionManager(paths, now)

    /** Set of active upload ids, so the service knows when to hold its locks. */
    private val _activeUploadCount = MutableStateFlow(0)
    val activeUploadCount: StateFlow<Int> = _activeUploadCount.asStateFlow()

    private var engine: EmbeddedServer<*, *>? = null
    private var boundPort: Int? = null

    /** What the activity reads as once no upload is in flight. */
    private var idleStatus: UploadStatus = UploadStatus.Idle
    private var idleMessage: String = "Starting local server..."

    private var libraryChangedListener: (suspend () -> Unit)? = null

    fun setLibraryChangedListener(listener: (suspend () -> Unit)?) {
        libraryChangedListener = listener
    }

    suspend fun start(port: Int) {
        mutex.withLock {
            if (engine != null && boundPort == port) {
                // Already serving this port. Adopting keeps in-flight uploads
                // alive; restarting would drop them for no reason.
                publish(UploadStatus.Idle, "Server ready on port $port.")
                _state.value = ServerState.Running(port)
                return
            }

            stopLocked()
            _state.value = ServerState.Starting(port)
            publish(UploadStatus.Idle, "Starting server on port $port...")

            val resolvedPort = if (isValidServerPort(port)) port else DEFAULT_PORT
            try {
                withContext(Dispatchers.IO) {
                    paths.ensureDirectories()
                    clearStagingDirectories()
                }
                val deps = dependenciesFactory(sessions).withLibraryChanged { libraryChangedListener?.invoke() }
                val server = embeddedServer(CIO, port = resolvedPort, host = BIND_ADDRESS) {
                    uploadServerModule(deps)
                }
                // The blocking start() waits on the engine from inside
                // runBlocking, which deadlocks when it is called from a
                // coroutine. The suspending pair is the one to use here.
                server.startSuspend(wait = false)
                engine = server
                boundPort = resolvedPort
            } catch (error: Throwable) {
                engine = null
                boundPort = null
                val message = error.message?.takeIf(String::isNotBlank) ?: "Unable to start the upload server."
                _state.value = ServerState.Error(message)
                publish(UploadStatus.Error, message)
                return
            }

            _state.value = ServerState.Running(resolvedPort)
            publish(UploadStatus.Idle, "Server ready on port $resolvedPort.")
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    suspend fun restart(port: Int) {
        stop()
        start(port)
    }

    private suspend fun stopLocked() {
        val wasRunning = engine != null
        engine?.let { server ->
            runCatching { server.stopSuspend(STOP_GRACE_MS, STOP_TIMEOUT_MS) }
        }
        engine = null
        boundPort = null
        sessions.clear()
        refreshActiveCount()
        _state.value = ServerState.Stopped
        // Only announce a stop that actually happened, so a plain restart does
        // not flash "Server stopped." at the user on its way back up.
        if (wasRunning) publish(UploadStatus.Stopped, "Server stopped.")
    }

    /** Publishes an event, folding in whatever uploads are currently running. */
    suspend fun publish(status: UploadStatus, message: String) {
        idleStatus = status
        idleMessage = message
        refreshActivity()
    }

    suspend fun refreshActivity() {
        val rows = buildRows(sessions.activeSessions(), now())
        _activeUploadCount.value = rows.size
        _activity.value = buildActivity(idleStatus, idleMessage, rows, now())
    }

    private suspend fun refreshActiveCount() {
        _activeUploadCount.value = sessions.activeSessions().size
    }

    private fun clearStagingDirectories() {
        paths.tempUploadsDir.listFiles()?.forEach { it.deleteRecursively() }
        paths.chunkTempDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val BIND_ADDRESS = "0.0.0.0"
        const val DEFAULT_PORT = 8081
        private const val STOP_GRACE_MS = 500L
        private const val STOP_TIMEOUT_MS = 2_000L

        /**
         * Folds the running uploads into one line. While anything is in flight
         * the aggregate wins over whatever event was last emitted, because
         * "uploading 3 files" is more useful than the name of one chunk.
         */
        fun buildActivity(
            idleStatus: UploadStatus,
            idleMessage: String,
            rows: List<ActiveUploadRow>,
            updatedAt: Long,
        ): UploadActivity {
            if (rows.isEmpty()) {
                return UploadActivity(
                    status = idleStatus,
                    message = idleMessage,
                    updatedAt = updatedAt,
                    activeUploads = emptyList(),
                )
            }
            val preparingOnly = rows.all { it.receivedBytes == 0L }
            val verb = if (preparingOnly) "Preparing" else "Uploading"
            val plural = if (rows.size == 1) "" else "s"
            return UploadActivity(
                status = UploadStatus.Receiving,
                message = "$verb ${rows.size} file$plural",
                updatedAt = updatedAt,
                activeUploads = rows,
                receivedBytes = rows.sumOf(ActiveUploadRow::receivedBytes),
                totalBytes = rows.sumOf(ActiveUploadRow::totalBytes),
            )
        }

        fun buildRows(sessions: List<UploadSession>, updatedAt: Long): List<ActiveUploadRow> =
            sessions.map { session ->
                ActiveUploadRow(
                    uploadId = session.uploadId,
                    // The destination path, so two uploads of the same file
                    // name into different folders are told apart.
                    fileName = session.relativePath,
                    message = if (session.receivedBytes > 0L) {
                        "Uploading ${session.fileName}"
                    } else {
                        "Preparing ${session.fileName}"
                    },
                    updatedAt = updatedAt,
                    receivedBytes = session.receivedBytes.coerceAtMost(session.totalSize),
                    totalBytes = session.totalSize,
                )
            }
    }
}

private fun ServerDependencies.withLibraryChanged(listener: suspend () -> Unit) = ServerDependencies(
    repository = repository,
    sessions = sessions,
    artifacts = artifacts,
    page = page,
    maxParallelUploads = maxParallelUploads,
    onActivity = onActivity,
    onLibraryChanged = {
        onLibraryChanged()
        listener()
    },
)
