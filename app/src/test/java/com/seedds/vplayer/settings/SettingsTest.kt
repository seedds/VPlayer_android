package com.seedds.vplayer.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun `defaults match the shipped values`() {
        val settings = Settings()
        assertEquals(3, settings.maxParallelUploads)
        assertEquals(36, settings.subtitleFontSize)
        assertEquals(30, settings.longPressSpeedTenths)
        assertEquals(3.0f, settings.holdSpeed, 0.0f)
    }

    @Test
    fun `clamping uses the first and last option as bounds`() {
        assertEquals(1, SettingsCatalog.MaxParallelUploads.clamp(0))
        assertEquals(5, SettingsCatalog.MaxParallelUploads.clamp(99))
        assertEquals(24, SettingsCatalog.SubtitleFontSize.clamp(1))
        assertEquals(48, SettingsCatalog.SubtitleFontSize.clamp(100))
    }

    @Test
    fun `a value between options is kept rather than snapped`() {
        assertEquals(37, SettingsCatalog.SubtitleFontSize.clamp(37))
    }

    @Test
    fun `a missing value falls back to the default`() {
        assertEquals(3, SettingsCatalog.MaxParallelUploads.clamp(null as Int?))
        assertEquals(30, SettingsCatalog.LongPressSpeedTenths.clamp(Double.NaN))
    }

    @Test
    fun `hold speed is shown in tenths with a multiplication sign`() {
        assertEquals("3.0×", SettingsCatalog.LongPressSpeedTenths.label(30))
        assertEquals("1.5×", SettingsCatalog.LongPressSpeedTenths.label(15))
        assertEquals("4", SettingsCatalog.MaxParallelUploads.label(4))
    }

    @Test
    fun `clamping a whole settings object fixes every field`() {
        val clamped = Settings(maxParallelUploads = 99, subtitleFontSize = 2, longPressSpeedTenths = 0).clamped()
        assertEquals(5, clamped.maxParallelUploads)
        assertEquals(24, clamped.subtitleFontSize)
        assertEquals(10, clamped.longPressSpeedTenths)
    }

    @Test
    fun `keys round-trip through get and with`() {
        var settings = Settings()
        SettingKey.entries.forEach { key ->
            settings = settings.with(key, SettingsCatalog[key].options.last())
            assertEquals(SettingsCatalog[key].options.last(), settings[key])
        }
    }
}
