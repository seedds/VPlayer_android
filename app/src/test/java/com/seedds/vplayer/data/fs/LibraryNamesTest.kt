package com.seedds.vplayer.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryNamesTest {

    @Test
    fun `keeps unicode letters and marks`() {
        assertEquals("中文电影.mp4", LibraryNames.sanitizeFileName("中文电影.mp4"))
        assertEquals("Ünïcødé", LibraryNames.sanitizeFolderName("Ünïcødé"))
        assertEquals("日本語フォルダ", LibraryNames.sanitizeFolderName("日本語フォルダ"))
    }

    @Test
    fun `replaces disallowed characters with underscore`() {
        assertEquals("a_b_c.mp4", LibraryNames.sanitizeFileName("a:b?c.mp4"))
        assertEquals("no_pipes", LibraryNames.sanitizeFolderName("no|pipes"))
    }

    @Test
    fun `collapses runs of spaces`() {
        assertEquals("two words", LibraryNames.sanitizeFolderName("two    words"))
        assertEquals("a b.mp4", LibraryNames.sanitizeFileName("a  b.mp4"))
    }

    @Test
    fun `other whitespace becomes an underscore rather than a space`() {
        // Tabs and newlines are not in the allowed set, so they are replaced
        // before the space-collapsing step ever sees them.
        assertEquals("a__b.mp4", LibraryNames.sanitizeFileName("a\t\tb.mp4"))
        assertEquals("a_b", LibraryNames.sanitizeFolderName("a\nb"))
    }

    @Test
    fun `drops directory parts from a file name`() {
        assertEquals("ep1.mp4", LibraryNames.sanitizeFileName("Shows/Season 1/ep1.mp4"))
        assertEquals("ep1.mp4", LibraryNames.sanitizeFileName("C:\\videos\\ep1.mp4"))
    }

    @Test
    fun `lowercases the extension but keeps the base case`() {
        assertEquals("MyMovie.mp4", LibraryNames.sanitizeFileName("MyMovie.MP4"))
    }

    @Test
    fun `falls back when nothing usable remains`() {
        assertEquals("folder", LibraryNames.sanitizeFolderName("..."))
        assertEquals("folder", LibraryNames.sanitizeFolderName("   "))
        assertEquals("upload", LibraryNames.sanitizeFileName(""))
        assertEquals("upload.mp4", LibraryNames.sanitizeFileName("...mp4").let {
            // "...mp4" has extension ".mp4" and an all-dots base, so the base
            // falls back while the extension survives.
            it
        })
    }

    @Test
    fun `strips trailing dots`() {
        assertEquals("name", LibraryNames.sanitizeFolderName("name..."))
    }

    @Test
    fun `extension detection is case insensitive and returns lowercase`() {
        assertEquals(".mkv", LibraryNames.extensionOf("Film.MKV"))
        assertEquals("", LibraryNames.extensionOf("README"))
        assertTrue(LibraryNames.isVideoFileName("a.WEBM"))
        assertTrue(LibraryNames.isSubtitleFileName("a.SRT"))
        assertFalse(LibraryNames.isVideoFileName("a.txt"))
    }

    @Test
    fun `base name drops only the last extension`() {
        assertEquals("Movie.part1", LibraryNames.baseNameOf("Movie.part1.mp4"))
        assertEquals("README", LibraryNames.baseNameOf("README"))
    }

    @Test
    fun `directory normalisation neutralises traversal`() {
        assertEquals("folder/folder", LibraryNames.normalizeDirectoryPath("../.."))
        assertEquals("Shows/Season 1", LibraryNames.normalizeDirectoryPath("Shows//Season 1/"))
        assertEquals("", LibraryNames.normalizeDirectoryPath(null))
    }

    @Test
    fun `file path normalisation applies folder rules to parents`() {
        assertEquals("Shows/ep1.mp4", LibraryNames.normalizeFilePath("Shows/ep1.MP4"))
        assertEquals("upload", LibraryNames.normalizeFilePath(""))
    }

    @Test
    fun `lookup rejects traversal instead of rewriting it`() {
        assertNull(LibraryNames.toLookupPath(".."))
        assertNull(LibraryNames.toLookupPath("Shows/../secret"))
        assertNull(LibraryNames.toLookupPath(""))
        assertEquals(listOf("Shows", "ep1.mp4"), LibraryNames.toLookupPath("Shows/ep1.mp4"))
    }

    @Test
    fun `parent path stops at the library root`() {
        assertNull(LibraryNames.parentPathOf("ep1.mp4"))
        assertNull(LibraryNames.parentPathOf(null))
        assertEquals("Shows", LibraryNames.parentPathOf("Shows/ep1.mp4"))
        assertEquals("Shows/Season 1", LibraryNames.parentPathOf("Shows/Season 1/ep1.mp4"))
    }

    @Test
    fun `join path builds under the current folder`() {
        assertEquals("Shows/ep1.mp4", LibraryNames.joinPath("Shows", "ep1.mp4"))
        assertEquals("ep1.mp4", LibraryNames.joinPath(null, "ep1.mp4"))
        assertEquals("Shows/S1/ep1.mp4", LibraryNames.joinPath("Shows", "S1/ep1.mp4"))
    }

    @Test
    fun `rename must not change a playable file into an unplayable one`() {
        assertTrue(LibraryNames.renamePreservesKind("a.mp4", "b.mkv"))
        assertFalse(LibraryNames.renamePreservesKind("a.mp4", "b.txt"))
        assertTrue(LibraryNames.renamePreservesKind("a.srt", "b.srt"))
        assertFalse(LibraryNames.renamePreservesKind("a.srt", "b.mp4"))
        assertTrue(LibraryNames.renamePreservesKind("notes.txt", "notes.md"))
    }
}
