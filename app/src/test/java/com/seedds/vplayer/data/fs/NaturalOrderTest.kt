package com.seedds.vplayer.data.fs

import com.seedds.vplayer.data.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun `numbers sort by value, not by digit`() {
        val sorted = listOf("ep10.mp4", "ep2.mp4", "ep1.mp4").sortedWith(NaturalOrderComparator)
        assertEquals(listOf("ep1.mp4", "ep2.mp4", "ep10.mp4"), sorted)
    }

    @Test
    fun `case and accents are ignored`() {
        assertEquals(0, NaturalOrderComparator.compare("Movie", "movie"))
        assertEquals(0, NaturalOrderComparator.compare("resume", "résumé"))
    }

    @Test
    fun `leading zeros only break ties`() {
        assertTrue(NaturalOrderComparator.compare("ep01", "ep1") != 0)
        assertEquals(0, NaturalOrderComparator.compare("ep01", "ep01"))
    }

    @Test
    fun `long digit runs do not overflow`() {
        val huge = "file99999999999999999999999.mp4"
        val bigger = "file999999999999999999999999.mp4"
        assertTrue(NaturalOrderComparator.compare(huge, bigger) < 0)
    }

    @Test
    fun `a prefix sorts before a longer name`() {
        assertTrue(NaturalOrderComparator.compare("ep", "ep1") < 0)
    }

    @Test
    fun `folders come before files`() {
        val folder = LibraryItem.Folder("zzz", "zzz", null, 0)
        val video = LibraryItem.Video("aaa.mp4", "aaa.mp4", null, 0, 1, ".mp4")
        val sorted = listOf(video, folder).sortedWith(LibraryItemComparator)
        assertEquals(listOf<LibraryItem>(folder, video), sorted)
    }
}
