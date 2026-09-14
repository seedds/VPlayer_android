package com.seedds.vplayer.player

import kotlin.math.floor

/** One subtitle line with the time range it is shown for, in milliseconds. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * A deliberately forgiving SubRip parser. Real `.srt` files in the wild have
 * missing index numbers, `.` instead of `,` before the milliseconds, stray
 * blank lines and inline styling tags, so anything that cannot be understood
 * is dropped rather than failing the whole file.
 */
object SrtParser {

    private val BLOCK_SEPARATOR = Regex("""\n\s*\n""")
    private val TIMESTAMP = Regex("""^(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})$""")
    private val STYLE_TAG = Regex("""</?(?:i|b|u|font)(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private const val ARROW = "-->"
    private const val BOM = '﻿'

    fun parse(content: String): List<SubtitleCue> {
        val normalized = content
            .removePrefix(BOM.toString())
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        for (block in normalized.split(BLOCK_SEPARATOR)) {
            val lines = block.split("\n").map(String::trim).filter(String::isNotEmpty)
            if (lines.isEmpty()) continue

            // The index number, when present, sits above the timing line; both
            // it and anything else before the arrow are ignored.
            val timingIndex = lines.indexOfFirst { it.contains(ARROW) }
            if (timingIndex == -1) continue

            val halves = lines[timingIndex].split(ARROW)
            if (halves.size < 2) continue
            val startMs = parseTimestamp(halves[0].trim()) ?: continue
            val endMs = parseTimestamp(halves[1].trim()) ?: continue
            if (endMs <= startMs) continue

            val text = lines.drop(timingIndex + 1)
                .joinToString("\n")
                .replace(STYLE_TAG, "")
                .trim()
            if (text.isEmpty()) continue

            cues += SubtitleCue(startMs = startMs, endMs = endMs, text = text)
        }

        return cues.sortedBy(SubtitleCue::startMs)
    }

    /**
     * `HH:MM:SS,mmm` where the hour may be one digit, the separator may be a
     * dot, and the milliseconds may be short. A short fraction is padded on the
     * right, so `,5` is half a second rather than five milliseconds.
     */
    private fun parseTimestamp(value: String): Long? {
        val match = TIMESTAMP.find(value) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured
        val millis = fraction.padEnd(3, '0').toLong()
        return ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 + millis
    }
}

/**
 * The cue to show at [positionSeconds], or null when there is none. Both bounds
 * are inclusive, and where cues overlap the earliest-starting one wins.
 */
fun List<SubtitleCue>.activeCueAt(positionSeconds: Double): SubtitleCue? {
    if (isEmpty()) return null
    val millis = floor(positionSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
    return firstOrNull { millis >= it.startMs && millis <= it.endMs }
}
