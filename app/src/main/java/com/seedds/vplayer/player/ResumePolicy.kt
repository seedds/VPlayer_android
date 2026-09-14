package com.seedds.vplayer.player

/**
 * Where playback should start when a video is reopened.
 *
 * A video left within [NEAR_END_THRESHOLD_SECONDS] of the end resumes slightly
 * before that point instead of at the very end, so reopening something you
 * finished replays the ending rather than immediately firing the end-of-video
 * handling and skipping to the next file.
 */
object ResumePolicy {

    const val NEAR_END_THRESHOLD_SECONDS = 10.0

    fun resumePosition(savedPositionSeconds: Double, durationSeconds: Double): Double {
        if (!savedPositionSeconds.isFinite() || savedPositionSeconds <= 0.0) return 0.0
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return savedPositionSeconds

        val clamped = savedPositionSeconds.coerceIn(0.0, durationSeconds)
        if (durationSeconds - clamped >= NEAR_END_THRESHOLD_SECONDS) return clamped
        return (durationSeconds - NEAR_END_THRESHOLD_SECONDS).coerceAtLeast(0.0)
    }
}
