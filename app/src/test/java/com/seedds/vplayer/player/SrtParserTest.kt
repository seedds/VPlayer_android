package com.seedds.vplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {

    @Test
    fun `parses a plain file`() {
        val cues = SrtParser.parse(
            """
            1
            00:00:01,000 --> 00:00:03,500
            Hello there

            2
            00:00:04,000 --> 00:00:05,000
            Second line
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Hello there", cues[0].text)
        assertEquals("Second line", cues[1].text)
    }

    @Test
    fun `handles CRLF, a leading BOM and a dot before milliseconds`() {
        val cues = SrtParser.parse("﻿1\r\n00:00:01.250 --> 00:00:02.750\r\nText\r\n")
        assertEquals(1, cues.size)
        assertEquals(1250L, cues[0].startMs)
        assertEquals(2750L, cues[0].endMs)
    }

    @Test
    fun `pads a short fraction on the right`() {
        val cues = SrtParser.parse("00:00:01,5 --> 00:00:02,05\nText")
        assertEquals(1500L, cues[0].startMs)
        assertEquals(2050L, cues[0].endMs)
    }

    @Test
    fun `accepts a single digit hour and a missing index line`() {
        val cues = SrtParser.parse("0:00:01,000 --> 0:00:02,000\nText")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
    }

    @Test
    fun `keeps multi-line text and strips styling tags`() {
        val cues = SrtParser.parse(
            "1\n00:00:01,000 --> 00:00:02,000\n<i>First</i>\n<b>Second</b> <FONT color=\"#fff\">x</font>",
        )
        assertEquals("First\nSecond x", cues[0].text)
    }

    @Test
    fun `leaves non-styling braces alone`() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}Top")
        assertEquals("{\\an8}Top", cues[0].text)
    }

    @Test
    fun `drops cues that cannot be understood`() {
        val cues = SrtParser.parse(
            """
            1
            garbage line
            still garbage

            2
            00:00:05,000 --> 00:00:04,000
            Backwards

            3
            00:00:06,000 --> 00:00:07,000

            4
            00:00:08,000 --> 00:00:09,000
            Good
            """.trimIndent(),
        )
        assertEquals(1, cues.size)
        assertEquals("Good", cues[0].text)
    }

    @Test
    fun `drops a cue whose timing carries trailing position data`() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000 X1:100 Y1:100\nText")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `empty input yields no cues`() {
        assertTrue(SrtParser.parse("").isEmpty())
        assertTrue(SrtParser.parse("   \n\n ").isEmpty())
    }

    @Test
    fun `sorts cues by start time`() {
        val cues = SrtParser.parse(
            "1\n00:00:09,000 --> 00:00:10,000\nLate\n\n2\n00:00:01,000 --> 00:00:02,000\nEarly",
        )
        assertEquals(listOf("Early", "Late"), cues.map { it.text })
    }

    @Test
    fun `active cue lookup is inclusive at both bounds`() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\nShown")
        assertNull(cues.activeCueAt(0.999))
        assertEquals("Shown", cues.activeCueAt(1.0)?.text)
        assertEquals("Shown", cues.activeCueAt(1.5)?.text)
        assertEquals("Shown", cues.activeCueAt(2.0)?.text)
        assertNull(cues.activeCueAt(2.001))
        assertNull(cues.activeCueAt(-5.0))
        assertNull(emptyList<SubtitleCue>().activeCueAt(1.0))
    }

    @Test
    fun `overlapping cues resolve to the earliest start`() {
        val cues = SrtParser.parse(
            "1\n00:00:01,000 --> 00:00:09,000\nFirst\n\n2\n00:00:02,000 --> 00:00:03,000\nSecond",
        )
        assertEquals("First", cues.activeCueAt(2.5)?.text)
    }
}
