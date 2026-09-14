package com.seedds.vplayer.data.fs

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** The port the upload server listens on unless the user changes it. */
const val DEFAULT_SERVER_PORT = 8081

/** Ports below 1025 need privileges the app does not have. */
const val MIN_SERVER_PORT = 1025
const val MAX_SERVER_PORT = 65535

private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * Human byte size, binary scaled: `512 B`, `1.5 KB`, `10 MB`, `1.0 GB`.
 * One decimal below 10 in a unit, none at or above it, and none for bytes.
 */
fun formatBytes(value: Long?): String {
    if (value == null || value <= 0L) return "0 B"
    var scaled = value.toDouble()
    var index = 0
    while (scaled >= 1024.0 && index < BYTE_UNITS.lastIndex) {
        scaled /= 1024.0
        index++
    }
    val digits = if (scaled >= 10.0 || index == 0) 0 else 1
    return "%.${digits}f %s".format(Locale.US, scaled, BYTE_UNITS[index])
}

/**
 * Clock-style duration: `MM:SS`, or `HH:MM:SS` once past an hour. An unknown
 * or negative duration reads as `--:--`, which is what the library shows
 * before a video has been probed.
 */
fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return "--:--"
    val total = floor(seconds).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(Locale.US, hours, minutes, secs)
    } else {
        "%02d:%02d".format(Locale.US, minutes, secs)
    }
}

/** Wall-clock `HH:MM` in 24-hour form, shown in the player's top bar. */
fun formatClockTime(epochMillis: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = epochMillis
    return "%02d:%02d".format(
        Locale.US,
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
}

/** Date and time in the device's locale, used for upload activity timestamps. */
fun formatDate(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT).format(Date(epochMillis))

/**
 * Reads a port out of user input, falling back when it is not a usable one.
 * Leading digits win, matching the lenient parse the text field had before, so
 * `8081x` is still port 8081.
 */
fun normalizePort(value: String?, fallback: Int = DEFAULT_SERVER_PORT): Int {
    val text = value?.trim().orEmpty()
    val digits = text.takeWhile { it.isDigit() }
    val parsed = digits.toIntOrNull() ?: return fallback
    return if (parsed < MIN_SERVER_PORT || parsed > MAX_SERVER_PORT) fallback else parsed
}

/** Whether a port is inside the range the server will accept. */
fun isValidServerPort(port: Int): Boolean = port in MIN_SERVER_PORT..MAX_SERVER_PORT

/** Percentage string for upload rows, e.g. `42%`. */
fun formatPercent(fraction: Float): String = "${Math.round(fraction * 100)}%"

/** Playback speed as the player shows it: one decimal, no suffix. */
fun formatSpeed(rate: Float): String = "%.1f".format(Locale.US, abs(rate))
