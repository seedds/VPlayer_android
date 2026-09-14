package com.seedds.vplayer.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the player does in response to a gesture on the video surface. */
interface PlayerGestureCallbacks {
    /** One finger, one tap: show the controls, or put them away. */
    fun onToggleControls()

    /** One finger, twice: play or pause without disturbing the overlay. */
    fun onTogglePlayback()

    /** Two fingers, twice: lock or unlock the controls. */
    fun onToggleLock()

    /** One finger held down: run at the hold speed until it lifts. */
    fun onHoldStart()

    fun onHoldEnd()

    /** Holding only makes sense while something is actually playing. */
    fun canHold(): Boolean
}

/**
 * Recognises the player's touch vocabulary on the video surface.
 *
 * Taps are held for [DOUBLE_TAP_WINDOW_MS] before being acted on, so a double
 * tap never flashes the controls on its way to play/pause. Finger count is
 * remembered across that window, which is what keeps a two-finger lock gesture
 * from being read as a one-finger pause. A hold is strictly longer than the
 * double-tap window, so holding can never swallow a double tap.
 *
 * Gestures keep working while the controls are locked; locking hides chrome, it
 * does not deafen the screen.
 */
@Composable
fun Modifier.playerGestures(
    enabled: Boolean = true,
    scrubbing: Boolean = false,
    callbacks: PlayerGestureCallbacks,
): Modifier {
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentScrubbing by rememberUpdatedState(scrubbing)
    val scope = rememberCoroutineScope()
    val tapTracker = remember { TapTracker() }

    return this.pointerInput(enabled) {
        if (!enabled) return@pointerInput

        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            var maxPointers = 1
            var moved = false
            var holding = false
            var elapsed = 0L

            val slop = viewConfiguration.touchSlop

            while (true) {
                val remaining = HOLD_DELAY_MS - elapsed
                val event: PointerEvent? = if (!holding && remaining > 0) {
                    val started = System.currentTimeMillis()
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        .also { elapsed += System.currentTimeMillis() - started }
                } else {
                    awaitPointerEvent()
                }

                if (event == null) {
                    // The finger stayed put long enough to mean "hold".
                    if (maxPointers == 1 && !moved && !currentScrubbing && currentCallbacks.canHold()) {
                        holding = true
                        currentCallbacks.onHoldStart()
                    } else {
                        elapsed = HOLD_DELAY_MS
                    }
                    continue
                }

                val pressed = event.changes.count { it.pressed }
                if (pressed > maxPointers) maxPointers = pressed
                if (event.changes.any { it.positionChange().getDistance() > slop }) moved = true

                // A second finger means this was never a hold.
                if (pressed > 1 && !holding) elapsed = HOLD_DELAY_MS

                if (event.changes.all { !it.pressed }) break
            }

            if (holding) {
                currentCallbacks.onHoldEnd()
                // The lift that ends a hold is not also a tap.
                tapTracker.reset()
                return@awaitEachGesture
            }

            if (moved || currentScrubbing) {
                tapTracker.reset()
                return@awaitEachGesture
            }

            tapTracker.register(
                scope = scope,
                fingerCount = if (maxPointers >= 2) 2 else 1,
                callbacks = currentCallbacks,
            )
        }
    }
}

/**
 * Decides whether a tap is the first of a pair or a lone one, remembering how
 * many fingers made the previous tap.
 */
private class TapTracker {
    private var pendingSingleTap: Job? = null
    private var lastTapAt = 0L
    private var lastFingerCount = 0

    fun register(scope: CoroutineScope, fingerCount: Int, callbacks: PlayerGestureCallbacks) {
        val now = System.currentTimeMillis()
        val isDouble = pendingSingleTap != null &&
            now - lastTapAt <= DOUBLE_TAP_WINDOW_MS &&
            lastFingerCount == fingerCount

        if (isDouble) {
            reset()
            when (fingerCount) {
                1 -> callbacks.onTogglePlayback()
                else -> callbacks.onToggleLock()
            }
            return
        }

        lastTapAt = now
        lastFingerCount = fingerCount
        pendingSingleTap?.cancel()
        pendingSingleTap = scope.launch {
            delay(DOUBLE_TAP_WINDOW_MS)
            pendingSingleTap = null
            // A lone two-finger tap means nothing; only a pair of them does.
            if (fingerCount == 1) callbacks.onToggleControls()
        }
    }

    fun reset() {
        pendingSingleTap?.cancel()
        pendingSingleTap = null
        lastTapAt = 0L
        lastFingerCount = 0
    }
}

/** How long a second tap may take to arrive, and how long a lone tap waits. */
const val DOUBLE_TAP_WINDOW_MS = 250L

/** Strictly longer than the double-tap window, so the two never collide. */
const val HOLD_DELAY_MS = 500L
