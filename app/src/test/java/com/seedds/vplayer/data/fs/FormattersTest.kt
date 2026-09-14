package com.seedds.vplayer.data.fs

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `byte sizes match the browser page formatting`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(null))
        assertEquals("0 B", formatBytes(-5))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("10 MB", formatBytes(10L * 1024 * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", formatBytes(1024L * 1024 * 1024 * 1024))
        // Caps at TB rather than inventing a unit.
        assertEquals("1024 TB", formatBytes(1024L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun `durations switch to hours only when needed`() {
        assertEquals("--:--", formatDuration(null))
        assertEquals("--:--", formatDuration(-1.0))
        assertEquals("--:--", formatDuration(Double.NaN))
        assertEquals("00:00", formatDuration(0.0))
        assertEquals("03:12", formatDuration(192.7))
        assertEquals("59:59", formatDuration(3599.0))
        assertEquals("01:00:00", formatDuration(3600.0))
        assertEquals("12:34:56", formatDuration(45296.0))
    }

    @Test
    fun `port parsing falls back outside the usable range`() {
        assertEquals(8081, normalizePort("8081"))
        assertEquals(8081, normalizePort("8081x"))
        assertEquals(8081, normalizePort("80"))
        assertEquals(8081, normalizePort("70000"))
        assertEquals(8081, normalizePort(""))
        assertEquals(8081, normalizePort(null))
        assertEquals(1025, normalizePort("1025"))
        assertEquals(65535, normalizePort("65535"))
        assertEquals(9000, normalizePort("abc", fallback = 9000))
    }

    @Test
    fun `speed and percent read the way the controls show them`() {
        assertEquals("1.0", formatSpeed(1f))
        assertEquals("2.5", formatSpeed(2.5f))
        assertEquals("42%", formatPercent(0.4157f))
        assertEquals("100%", formatPercent(1f))
    }
}
