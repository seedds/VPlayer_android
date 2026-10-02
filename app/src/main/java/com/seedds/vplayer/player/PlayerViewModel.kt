package com.seedds.vplayer.player

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seedds.vplayer.app.AppContainer
import com.seedds.vplayer.data.model.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class PlayerUiState(
    val video: LibraryItem.Video? = null,
    val hasNext: Boolean = false,
    val isPlaying: Boolean = false,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val scrubbing: Boolean = false,
    val scrubSeconds: Double = 0.0,
    val playbackSpeed: Float = 1f,
    val boostSpeed: Float? = null,
    val subtitleText: String? = null,
    val subtitleFontSize: Int = 36,
    val errorMessage: String? = null,
    val previewFrame: Bitmap? = null,
    /** The open video was deleted or renamed from the browser, so the screen closes. */
    val videoRemoved: Boolean = false,
) {
    /** What the time labels and the filled bar read from. */
    val displayedSeconds: Double get() = if (scrubbing) scrubSeconds else positionSeconds

    val remainingSeconds: Double get() = (durationSeconds - displayedSeconds).coerceAtLeast(0.0)

    val progress: Float
        get() = if (durationSeconds > 0.0) {
            (displayedSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val boosting: Boolean get() = boostSpeed != null
}

/**
 * Drives playback for one folder's worth of videos.
 *
 * The interesting behaviour here is about not losing the user's place: progress
 * is written on every transition that could be the last one (leaving, switching,
 * backgrounding, finishing), and only sparsely during steady playback.
 */
class PlayerViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        // Pitch correction on, so a sped-up voice stays a voice.
        playbackParameters = PlaybackParameters(1f, 1f)
    }

    private var queue: List<LibraryItem.Video> = emptyList()
    private var currentIndex: Int = 0
    private var cues: List<SubtitleCue> = emptyList()

    private var ticker: Job? = null
    private var autoHideJob: Job? = null
    private var previewJob: Job? = null

    /** The newest position asked for while a frame is being made. */
    private var pendingPreviewSeconds: Double? = null
    private var lastPreviewedSeconds: Double? = null

    /** The base rate the speed control shows, kept apart from a live hold boost. */
    private var baseSpeed: Float = 1f

    private var lastPersistedSeconds: Double = 0.0

    /**
     * Set when playback was stopped by something other than the user, so
     * returning to the app does not start it again behind their back.
     */
    private var interrupted: Boolean = false

    private var pendingResumeFor: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            restartAutoHide()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update { it.copy(errorMessage = null) }
                    applyPendingResume()
                }
                // Emptying the playlist, which leaving the player does, also
                // reports ENDED. Only a video that played to its end should
                // save its end position and move on to the next one.
                Player.STATE_ENDED -> if (player.mediaItemCount > 0) onPlaybackEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    errorMessage = error.message?.takeIf(String::isNotBlank)
                        ?: "This video could not be played.",
                    controlsVisible = true,
                )
            }
        }
    }

    init {
        player.addListener(listener)
        viewModelScope.launch {
            container.settingsStore.load()
            // Followed rather than read once: this view model lives as long as
            // the activity, so a size changed in Settings must still reach it.
            container.settingsStore.settings.collect { settings ->
                _state.update { it.copy(subtitleFontSize = settings.subtitleFontSize) }
            }
        }
        startTicker()
    }

    // --------------------------------------------------------------- playback

    fun open(videos: List<LibraryItem.Video>, index: Int) {
        queue = videos
        // Every opening starts at 1.0x and unlocked. Neither is a setting, and
        // this view model outlives the screen, so both would otherwise carry
        // over. Next goes through play() instead and keeps the speed.
        baseSpeed = 1f
        _state.update { it.copy(playbackSpeed = 1f, locked = false, videoRemoved = false) }
        play(index)
    }

    private fun play(index: Int) {
        val video = queue.getOrNull(index) ?: return
        currentIndex = index
        lastPersistedSeconds = 0.0
        interrupted = false
        cues = emptyList()
        clearPreview()

        _state.update {
            it.copy(
                video = video,
                hasNext = index < queue.lastIndex,
                subtitleText = null,
                errorMessage = null,
                scrubbing = false,
                previewFrame = null,
                positionSeconds = 0.0,
                durationSeconds = 0.0,
                // The rate is reset to the base below, so a hold boost still
                // shown from the last video would claim a speed nothing plays.
                boostSpeed = null,
            )
        }

        pendingResumeFor = video.relativePath
        player.setMediaItem(MediaItem.fromUri(container.paths.fileFor(video.relativePath).toURI().toString()))
        player.prepare()
        // Rate does not always survive a source swap, so reassert it.
        player.playbackParameters = PlaybackParameters(baseSpeed, 1f)

        viewModelScope.launch { loadSubtitles(video) }
    }

    /**
     * Seeks to where the user left off, once the duration is known. Doing this
     * on STATE_READY rather than immediately avoids seeking into a source that
     * has not reported its length yet.
     */
    private fun applyPendingResume() {
        val video = _state.value.video ?: return
        val duration = player.duration.takeIf { it > 0L }?.let { it / 1000.0 } ?: 0.0
        _state.update { it.copy(durationSeconds = duration) }

        if (pendingResumeFor != video.relativePath) return
        pendingResumeFor = null

        viewModelScope.launch {
            val saved = container.playbackStateStore.position(video.relativePath)
            val resumeAt = ResumePolicy.resumePosition(saved, duration)
            if (resumeAt > 0.0) player.seekTo((resumeAt * 1000).toLong())
            syncPosition()
            if (!interrupted) player.play()
        }
    }

    private suspend fun loadSubtitles(video: LibraryItem.Video) {
        val subtitle = container.libraryRepository.findMatchingSubtitle(video) ?: return
        val file = container.paths.fileFor(subtitle.relativePath)
        // A film's worth of cues takes long enough to read and parse that doing
        // it on the main thread stutters the video as it starts.
        val parsed = withContext(Dispatchers.IO) {
            runCatching { file.readText() }.mapCatching(SrtParser::parse).getOrElse { emptyList() }
        }

        if (_state.value.video?.relativePath != video.relativePath) return
        cues = parsed
        refreshSubtitle(_state.value.positionSeconds)
    }

    // ----------------------------------------------------------------- ticker

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                if (player.isPlaying || _state.value.durationSeconds == 0.0) {
                    syncPosition()
                    if (!_state.value.scrubbing) persistPosition(force = false)
                }
            }
        }
    }

    private fun syncPosition() {
        val position = player.currentPosition.coerceAtLeast(0L) / 1000.0
        val duration = player.duration.takeIf { it > 0L }?.let { it / 1000.0 }
            ?: _state.value.durationSeconds
        _state.update { it.copy(positionSeconds = position, durationSeconds = duration) }
        if (!_state.value.scrubbing) refreshSubtitle(position)
    }

    private fun refreshSubtitle(seconds: Double) {
        val text = cues.activeCueAt(seconds)?.text
        if (text != _state.value.subtitleText) _state.update { it.copy(subtitleText = text) }
    }

    // ------------------------------------------------------------ persistence

    private fun persistPosition(force: Boolean) {
        val video = _state.value.video ?: return
        val position = player.currentPosition.coerceAtLeast(0L) / 1000.0
        // During steady playback a save every couple of seconds is plenty; the
        // moments that really matter are forced.
        if (!force && abs(position - lastPersistedSeconds) < PERSIST_THRESHOLD_SECONDS) return
        lastPersistedSeconds = position
        val duration = _state.value.durationSeconds.takeIf { it > 0.0 }
        viewModelScope.launch {
            container.playbackStateStore.savePosition(video.relativePath, position, duration)
        }
    }

    // ------------------------------------------------------------- transport

    fun togglePlayback() {
        if (player.isPlaying) {
            player.pause()
        } else {
            interrupted = false
            player.play()
        }
    }

    fun seekBy(seconds: Int) {
        val target = (player.currentPosition + seconds * 1000L).coerceAtLeast(0L)
        player.seekTo(target)
        syncPosition()
        showControls()
    }

    fun next() {
        if (currentIndex >= queue.lastIndex) return
        persistPosition(force = true)
        _state.update { it.copy(controlsVisible = false) }
        play(currentIndex + 1)
    }

    private fun onPlaybackEnded() {
        persistPosition(force = true)
        _state.update { it.copy(controlsVisible = false) }
        if (currentIndex < queue.lastIndex) play(currentIndex + 1)
    }

    // ---------------------------------------------------------------- speed

    fun changeSpeed(delta: Float) {
        val next = clampSpeed(baseSpeed + delta)
        baseSpeed = next
        if (!_state.value.boosting) player.playbackParameters = PlaybackParameters(next, 1f)
        _state.update { it.copy(playbackSpeed = next) }
        showControls()
    }

    /**
     * Hold to skim. The hold rate deliberately ignores the manual speed ceiling:
     * it is a transient gesture, not a setting, and skimming wants to be fast.
     */
    fun startHoldBoost() {
        val boost = container.settingsStore.settings.value.holdSpeed
        player.playbackParameters = PlaybackParameters(boost, 1f)
        _state.update { it.copy(boostSpeed = boost, controlsVisible = false) }
    }

    fun endHoldBoost() {
        if (!_state.value.boosting) return
        player.playbackParameters = PlaybackParameters(baseSpeed, 1f)
        _state.update { it.copy(boostSpeed = null) }
    }

    fun canHold(): Boolean = player.isPlaying && !_state.value.scrubbing

    // -------------------------------------------------------------- controls

    fun toggleControls() {
        val visible = !_state.value.controlsVisible
        _state.update { it.copy(controlsVisible = visible) }
        if (visible) restartAutoHide() else autoHideJob?.cancel()
    }

    fun showControls() {
        _state.update { it.copy(controlsVisible = true) }
        restartAutoHide()
    }

    fun toggleLock() {
        _state.update { it.copy(locked = !it.locked, controlsVisible = true) }
        clearPreview()
        restartAutoHide()
    }

    /**
     * Controls only fade while something is playing: leaving them up on a
     * paused video is what the user almost always wants.
     */
    private fun restartAutoHide() {
        autoHideJob?.cancel()
        val current = _state.value
        if (!current.controlsVisible || !player.isPlaying || current.scrubbing) return
        autoHideJob = viewModelScope.launch {
            delay(AUTO_HIDE_DELAY_MS)
            _state.update { it.copy(controlsVisible = false) }
        }
    }

    // -------------------------------------------------------------- scrubbing

    fun beginScrub(seconds: Double) {
        clearPreview()
        autoHideJob?.cancel()
        _state.update { it.copy(scrubbing = true, scrubSeconds = seconds, controlsVisible = true) }
        requestPreview(seconds)
    }

    fun updateScrub(seconds: Double) {
        _state.update { it.copy(scrubSeconds = seconds) }
        requestPreview(seconds)
    }

    fun commitScrub(seconds: Double) {
        clearPreview()
        player.seekTo((seconds * 1000).toLong())
        _state.update { it.copy(scrubbing = false, positionSeconds = seconds, scrubSeconds = seconds) }
        refreshSubtitle(seconds)
        persistPosition(force = true)
        showControls()
    }

    /**
     * One frame is made at a time, and only the newest position waits behind
     * it; anything the finger passed over meanwhile is skipped. Cancelling the
     * frame in flight on every movement instead meant a steady drag never let
     * one finish.
     */
    private fun requestPreview(seconds: Double) {
        val video = _state.value.video ?: return
        val last = lastPreviewedSeconds
        if (last != null && abs(seconds - last) <= PREVIEW_DEDUPE_SECONDS) return
        pendingPreviewSeconds = seconds
        if (previewJob?.isActive == true) return

        previewJob = viewModelScope.launch {
            val file = container.paths.fileFor(video.relativePath)
            while (true) {
                val target = pendingPreviewSeconds ?: break
                pendingPreviewSeconds = null
                lastPreviewedSeconds = target
                val frame = container.mediaProbe.previewFrame(file, target, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                // A frame that failed hides the popup rather than leaving an
                // older one up.
                if (_state.value.scrubbing) _state.update { it.copy(previewFrame = frame) }
            }
        }
    }

    private fun clearPreview() {
        previewJob?.cancel()
        previewJob = null
        pendingPreviewSeconds = null
        lastPreviewedSeconds = null
        _state.update { it.copy(previewFrame = null) }
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Anything that takes the player off screen stops it and writes the
     * position down. Coming back leaves it paused: resuming audio into a room
     * the user has walked away from is worse than making them tap play.
     */
    fun onInterrupted() {
        interrupted = true
        endHoldBoost()
        clearPreview()
        persistPosition(force = true)
        player.pause()
        _state.update { it.copy(scrubbing = false, locked = false, controlsVisible = true) }
    }

    /**
     * Leaving the player frees the decoder rather than just pausing it. The
     * view model outlives the screen, and holding a hardware decoder open while
     * the user browses the library can starve other apps of one.
     */
    fun onLeaving() {
        persistPosition(force = true)
        releaseVideo()
    }

    /**
     * The library changed from the browser. A video deleted or renamed while
     * it is open closes the player, as spec B1 asks. Its position is not
     * saved: a delete has just cleared its history, and a save would bring an
     * entry for a file that no longer exists back.
     */
    fun onLibraryChanged() {
        viewModelScope.launch {
            val video = _state.value.video ?: return@launch
            val present = withContext(Dispatchers.IO) { container.paths.fileFor(video.relativePath).isFile }
            if (present || _state.value.video?.relativePath != video.relativePath) return@launch
            releaseVideo()
            _state.update { it.copy(videoRemoved = true) }
        }
    }

    private fun releaseVideo() {
        clearPreview()
        container.mediaProbe.closePreview()
        player.pause()
        player.clearMediaItems()
        _state.update { it.copy(video = null, subtitleText = null, previewFrame = null) }
        cues = emptyList()
    }

    override fun onCleared() {
        persistPosition(force = true)
        ticker?.cancel()
        autoHideJob?.cancel()
        previewJob?.cancel()
        container.mediaProbe.closePreview()
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }

    companion object {
        const val AUTO_HIDE_DELAY_MS = 2_500L
        const val TICK_INTERVAL_MS = 250L
        const val PERSIST_THRESHOLD_SECONDS = 2.0
        const val SEEK_STEP_SECONDS = 10
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val SPEED_STEP = 0.1f
        const val PREVIEW_WIDTH = 320
        const val PREVIEW_HEIGHT = 180

        /** Closer than this to the last frame and the popup would show the same picture. */
        const val PREVIEW_DEDUPE_SECONDS = 0.05

        fun clampSpeed(value: Float): Float =
            ((value * 10).roundToInt() / 10f).coerceIn(MIN_SPEED, MAX_SPEED)
    }
}
