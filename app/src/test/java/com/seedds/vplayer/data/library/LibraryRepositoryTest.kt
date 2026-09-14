package com.seedds.vplayer.data.library

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryException
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.model.LibraryKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var paths: LibraryPaths
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        paths = LibraryPaths(filesDir = temp.newFolder("files"), cacheDir = temp.newFolder("cache"))
        paths.ensureDirectories()
        repository = LibraryRepository(paths)
    }

    private fun seed(relativePath: String, content: String = "x") {
        val file = paths.fileFor(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun seedFolder(relativePath: String) {
        paths.fileFor(relativePath).mkdirs()
    }

    private fun failureOf(block: suspend () -> Unit): String = runCatching {
        runBlocking { block() }
    }.exceptionOrNull().let { error ->
        assertTrue("expected a LibraryException, got $error", error is LibraryException)
        error!!.message!!
    }

    @Test
    fun `listing classifies entries and sorts folders first`() = runTest {
        seedFolder("Shows")
        seed("ep10.mp4")
        seed("ep2.mp4")
        seed("ep2.srt")
        seed("notes.txt")

        val items = repository.list(null)
        assertEquals(
            listOf("Shows", "ep2.mp4", "ep2.srt", "ep10.mp4", "notes.txt"),
            items.map(LibraryItem::name),
        )
        assertEquals(
            listOf(LibraryKind.Folder, LibraryKind.Video, LibraryKind.Subtitle, LibraryKind.Video, LibraryKind.File),
            items.map(LibraryItem::kind),
        )
    }

    @Test
    fun `listing a missing folder is empty rather than an error`() = runTest {
        assertTrue(repository.list("nope").isEmpty())
    }

    @Test
    fun `items carry their path, parent and size`() = runTest {
        seed("Shows/S1/ep1.mp4", "12345")
        val item = repository.getItem("Shows/S1/ep1.mp4") as LibraryItem.Video
        assertEquals("ep1.mp4", item.name)
        assertEquals("Shows/S1", item.parentPath)
        assertEquals(".mp4", item.extension)
        assertEquals(5L, item.size)
    }

    @Test
    fun `a traversal path never resolves`() = runTest {
        seed("secret.mp4")
        assertNull(repository.getItem("Shows/../secret.mp4"))
        assertNull(repository.getItem(".."))
        assertNull(repository.getItem(""))
    }

    @Test
    fun `recursive video listing skips other kinds`() = runTest {
        seed("Shows/S1/ep1.mp4")
        seed("Shows/S1/ep1.srt")
        seed("Shows/S2/ep2.mkv")
        seed("readme.txt")
        assertEquals(
            listOf("Shows/S1/ep1.mp4", "Shows/S2/ep2.mkv"),
            repository.listAllVideos().map(LibraryItem.Video::relativePath),
        )
    }

    @Test
    fun `creating a folder sanitises the name`() = runTest {
        val folder = repository.createFolder(null, "My: Shows  ")
        assertEquals("My_ Shows", folder.name)
        assertTrue(paths.fileFor(folder.relativePath).isDirectory)
    }

    @Test
    fun `creating a folder that already exists is refused`() = runTest {
        repository.createFolder(null, "Shows")
        assertEquals(LibraryErrors.NAME_TAKEN, failureOf { repository.createFolder(null, "Shows") })
    }

    @Test
    fun `renaming moves the file and keeps the folder`() = runTest {
        seed("Shows/ep1.mp4")
        val renamed = repository.rename("Shows/ep1.mp4", "pilot.mp4")
        assertEquals("Shows/pilot.mp4", renamed.relativePath)
        assertFalse(paths.fileFor("Shows/ep1.mp4").exists())
    }

    @Test
    fun `renaming to the same name is a no-op`() = runTest {
        seed("ep1.mp4")
        val renamed = repository.rename("ep1.mp4", "ep1.mp4")
        assertEquals("ep1.mp4", renamed.relativePath)
    }

    @Test
    fun `renaming onto an existing name is refused`() = runTest {
        seed("a.mp4")
        seed("b.mp4")
        assertEquals(LibraryErrors.NAME_TAKEN, failureOf { repository.rename("a.mp4", "b.mp4") })
    }

    @Test
    fun `renaming may change a video extension but not its kind`() = runTest {
        seed("a.mp4")
        assertEquals("a.mkv", repository.rename("a.mp4", "a.mkv").name)
        assertEquals(
            LibraryErrors.keepExtension(".mkv"),
            failureOf { repository.rename("a.mkv", "a.txt") },
        )
    }

    @Test
    fun `renaming a subtitle must keep it a subtitle`() = runTest {
        seed("a.srt")
        assertEquals(
            LibraryErrors.keepExtension(".srt"),
            failureOf { repository.rename("a.srt", "a.mp4") },
        )
    }

    @Test
    fun `renaming an unrelated file is unrestricted`() = runTest {
        seed("notes.txt")
        assertEquals("notes.md", repository.rename("notes.txt", "notes.md").name)
    }

    @Test
    fun `renaming something that is gone is refused`() {
        assertEquals(LibraryErrors.ITEM_NOT_FOUND, failureOf { repository.rename("gone.mp4", "x.mp4") })
    }

    @Test
    fun `moving relocates an item`() = runTest {
        seed("ep1.mp4")
        seedFolder("Archive")
        val moved = repository.move("ep1.mp4", "Archive")
        assertEquals("Archive/ep1.mp4", moved.relativePath)
        assertTrue(paths.fileFor("Archive/ep1.mp4").exists())
    }

    @Test
    fun `moving to the current folder is a no-op`() = runTest {
        seed("Shows/ep1.mp4")
        assertEquals("Shows/ep1.mp4", repository.move("Shows/ep1.mp4", "Shows").relativePath)
    }

    @Test
    fun `moving to a missing destination is refused`() = runTest {
        seed("ep1.mp4")
        assertEquals(
            LibraryErrors.DESTINATION_NOT_FOUND,
            failureOf { repository.move("ep1.mp4", "Nope") },
        )
    }

    @Test
    fun `moving onto an existing name is refused`() = runTest {
        seed("ep1.mp4")
        seed("Archive/ep1.mp4")
        assertEquals(
            LibraryErrors.NAME_TAKEN_IN_DESTINATION,
            failureOf { repository.move("ep1.mp4", "Archive") },
        )
    }

    @Test
    fun `a folder cannot be moved inside itself`() = runTest {
        seedFolder("Shows/S1")
        assertEquals(
            LibraryErrors.FOLDER_INTO_ITSELF,
            failureOf { repository.move("Shows", "Shows/S1") },
        )
        assertEquals(
            LibraryErrors.FOLDER_INTO_ITSELF,
            failureOf { repository.move("Shows", "Shows") },
        )
    }

    @Test
    fun `deleting a folder removes everything under it`() = runTest {
        seed("Shows/S1/ep1.mp4")
        repository.delete("Shows")
        assertFalse(paths.fileFor("Shows").exists())
    }

    @Test
    fun `deleting something that is gone is refused`() {
        assertEquals(LibraryErrors.ITEM_NOT_FOUND, failureOf { repository.delete("gone.mp4") })
    }

    @Test
    fun `collecting videos walks a folder and ignores other kinds`() = runTest {
        seed("Shows/S1/ep1.mp4")
        seed("Shows/S1/ep1.srt")
        seed("Shows/notes.txt")
        val folder = repository.getItem("Shows")!!
        assertEquals(listOf("Shows/S1/ep1.mp4"), repository.collectVideos(folder).map { it.relativePath })

        val subtitle = repository.getItem("Shows/S1/ep1.srt")!!
        assertTrue(repository.collectVideos(subtitle).isEmpty())

        val video = repository.getItem("Shows/S1/ep1.mp4")!!
        assertEquals(1, repository.collectVideos(video).size)
    }

    @Test
    fun `a subtitle pairs with a sibling video by base name`() = runTest {
        seed("Shows/Ep 1.mkv")
        seed("Shows/ep 1.srt")
        val video = repository.getItem("Shows/Ep 1.mkv") as LibraryItem.Video
        assertEquals("ep 1.srt", repository.findMatchingSubtitle(video)?.name)
    }

    @Test
    fun `a subtitle in another folder does not pair`() = runTest {
        seed("Shows/ep1.mkv")
        seed("Subs/ep1.srt")
        val video = repository.getItem("Shows/ep1.mkv") as LibraryItem.Video
        assertNull(repository.findMatchingSubtitle(video))
    }

    @Test
    fun `a language suffixed subtitle does not pair`() = runTest {
        seed("ep1.mkv")
        seed("ep1.en.srt")
        val video = repository.getItem("ep1.mkv") as LibraryItem.Video
        assertNull(repository.findMatchingSubtitle(video))
    }

    @Test
    fun `walking up finds the nearest folder that still exists`() = runTest {
        seedFolder("Shows")
        assertEquals("Shows", repository.nearestExistingFolder("Shows/S1/S2"))
        assertNull(repository.nearestExistingFolder("Gone/Deeper"))
        assertNull(repository.nearestExistingFolder(null))
    }

    @Test
    fun `unicode names round-trip through create and rename`() = runTest {
        val folder = repository.createFolder(null, "中文电影")
        seed("${folder.relativePath}/剧集 1.mp4")
        val renamed = repository.rename("${folder.relativePath}/剧集 1.mp4", "剧集 2.mp4")
        assertEquals("剧集 2.mp4", renamed.name)
        assertEquals("中文电影/剧集 2.mp4", renamed.relativePath)
    }
}
