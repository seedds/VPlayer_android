package com.seedds.vplayer.data.media

import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCacheTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `cache keys stay the same across builds so existing thumbnails are reused`() {
        val paths = LibraryPaths(filesDir = temp.newFolder("files"), cacheDir = temp.newFolder("cache"))
        val cache = ThumbnailCache(paths)
        val video = LibraryItem.Video(
            name = "ep1.mp4",
            relativePath = "Shows/ep1.mp4",
            parentPath = "Shows",
            modified = 1_700_000_000_000L,
            size = 1_048_576L,
            extension = ".mp4",
        )

        // SHA-1 of "Shows/ep1.mp4|1048576|1700000000000|10|240|240".
        assertEquals("e7e483c625f462d63d8b4008d0397f71abb10a57", cache.cacheKey(video))
    }
}
