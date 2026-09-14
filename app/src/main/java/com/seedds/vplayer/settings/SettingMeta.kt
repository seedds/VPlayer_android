package com.seedds.vplayer.settings

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt

/** The three numeric settings the app exposes. */
enum class SettingKey {
    MaxParallelUploads,
    SubtitleFontSize,
    LongPressSpeedTenths,
    ;

    companion object {
        fun fromName(value: String): SettingKey? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Everything about one setting in a single place: its choices, its default and
 * all the copy the settings and picker screens render. Bounds are the first and
 * last option, so a setting's limits can never drift away from its choices.
 */
data class SettingMeta(
    val key: SettingKey,
    val default: Int,
    val options: List<Int>,
    /** Title of the picker screen this setting opens. */
    val navTitle: String,
    /** Section heading on the settings screen. */
    val title: String,
    val subtitle: String,
    /** Label on the row that opens the picker. */
    val rowLabel: String,
    val footnote: String? = null,
    private val format: ((Int) -> String)? = null,
) {
    val min: Int get() = options.first()
    val max: Int get() = options.last()

    fun label(value: Int): String = format?.invoke(value) ?: value.toString()

    /**
     * Coerces stored or user input into range. A value that is not a number at
     * all falls back to the default; anything else is rounded and clamped, so a
     * value saved by a future build with more choices still lands somewhere
     * sensible rather than being thrown away.
     */
    fun clamp(value: Int?): Int {
        if (value == null) return default
        return value.coerceIn(min, max)
    }

    fun clamp(value: Double?): Int {
        if (value == null || value.isNaN() || value.isInfinite()) return default
        return value.roundToInt().coerceIn(min, max)
    }
}

object SettingsCatalog {

    val MaxParallelUploads = SettingMeta(
        key = SettingKey.MaxParallelUploads,
        default = 3,
        options = listOf(1, 2, 3, 4, 5),
        navTitle = "Concurrent Uploads",
        title = "Concurrent uploads",
        subtitle = "Choose how many files the browser uploader can send in parallel.",
        rowLabel = "Select upload count",
        footnote = "Refresh the browser upload page to apply changes.",
    )

    val SubtitleFontSize = SettingMeta(
        key = SettingKey.SubtitleFontSize,
        default = 36,
        options = listOf(24, 28, 32, 36, 40, 44, 48),
        navTitle = "Subtitle Size",
        title = "Subtitle size",
        subtitle = "Choose how large subtitles appear during playback.",
        rowLabel = "Select subtitle size",
    )

    val LongPressSpeedTenths = SettingMeta(
        key = SettingKey.LongPressSpeedTenths,
        default = 30,
        options = listOf(10, 15, 20, 25, 30),
        navTitle = "Hold-to-Speed-Up",
        title = "Hold-to-speed-up rate",
        subtitle = "Press and hold the video to temporarily play at this speed.",
        rowLabel = "Select speed",
        // Stored in tenths so the persisted value stays a whole number.
        format = { "%.1f×".format(Locale.US, it / 10.0) },
    )

    val all = listOf(MaxParallelUploads, SubtitleFontSize, LongPressSpeedTenths)

    operator fun get(key: SettingKey): SettingMeta = when (key) {
        SettingKey.MaxParallelUploads -> MaxParallelUploads
        SettingKey.SubtitleFontSize -> SubtitleFontSize
        SettingKey.LongPressSpeedTenths -> LongPressSpeedTenths
    }

    /** How the settings screen groups the settings into panels. */
    data class Panel(val title: String, val subtitle: String, val keys: List<SettingKey>)

    val panels = listOf(
        Panel(
            title = "Upload settings",
            subtitle = "Control how many files the browser uploader sends at once.",
            keys = listOf(SettingKey.MaxParallelUploads),
        ),
        Panel(
            title = "Player settings",
            subtitle = "Tune subtitles and the hold-to-speed-up gesture.",
            keys = listOf(SettingKey.SubtitleFontSize, SettingKey.LongPressSpeedTenths),
        ),
    )
}

@Serializable
data class Settings(
    val maxParallelUploads: Int = SettingsCatalog.MaxParallelUploads.default,
    val subtitleFontSize: Int = SettingsCatalog.SubtitleFontSize.default,
    val longPressSpeedTenths: Int = SettingsCatalog.LongPressSpeedTenths.default,
) {
    /** Playback speed the hold gesture applies, e.g. 30 tenths reads as 3.0x. */
    val holdSpeed: Float get() = longPressSpeedTenths / 10f

    operator fun get(key: SettingKey): Int = when (key) {
        SettingKey.MaxParallelUploads -> maxParallelUploads
        SettingKey.SubtitleFontSize -> subtitleFontSize
        SettingKey.LongPressSpeedTenths -> longPressSpeedTenths
    }

    fun with(key: SettingKey, value: Int): Settings = when (key) {
        SettingKey.MaxParallelUploads -> copy(maxParallelUploads = value)
        SettingKey.SubtitleFontSize -> copy(subtitleFontSize = value)
        SettingKey.LongPressSpeedTenths -> copy(longPressSpeedTenths = value)
    }

    /** Brings every field back into its allowed range. */
    fun clamped(): Settings = Settings(
        maxParallelUploads = SettingsCatalog.MaxParallelUploads.clamp(maxParallelUploads),
        subtitleFontSize = SettingsCatalog.SubtitleFontSize.clamp(subtitleFontSize),
        longPressSpeedTenths = SettingsCatalog.LongPressSpeedTenths.clamp(longPressSpeedTenths),
    )
}
