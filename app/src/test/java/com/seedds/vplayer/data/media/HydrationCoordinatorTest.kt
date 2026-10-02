package com.seedds.vplayer.data.media

import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.model.LibraryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HydrationCoordinatorTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var thumbnailCache: ThumbnailCache

    /** Paths in the order their probes started. */
    private val probed = mutableListOf<String>()

    /** Everything handed to the screen. */
    private val reported = mutableListOf<ProbeResult>()

    /** Each batch of durations written to the playback store. */
    private val savedBatches = mutableListOf<Map<String, Double>>()

    /** Probes of these paths wait here until the test lets them finish. */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Probes of these paths fail. */
    private val broken = mutableSetOf<String>()

    @Before
    fun setUp() {
        val paths = LibraryPaths(filesDir = temp.newFolder("files"), cacheDir = temp.newFolder("cache"))
        paths.ensureDirectories()
        thumbnailCache = ThumbnailCache(paths)
    }

    private fun video(name: String) = LibraryItem.Video(
        name = name,
        relativePath = name,
        parentPath = null,
        modified = 1_000L,
        size = 100L,
        extension = ".mp4",
    )

    private fun CoroutineScope.coordinator() = HydrationCoordinator(
        scope = this,
        probe = { video, knownDuration ->
            probed += video.relativePath
            gates[video.relativePath]?.await()
            if (video.relativePath in broken) error("cannot open ${video.relativePath}")
            ProbeResult(video.relativePath, knownDuration ?: 60.0, null)
        },
        thumbnailCache = thumbnailCache,
        saveDurations = { savedBatches += it },
        onResults = { reported += it },
    )

    @Test
    fun `a pass cancelled part-way leaves its unprobed videos for the next pass`() = runTest {
        val hydration = coordinator()
        val gate = CompletableDeferred<Unit>().also { gates["a.mp4"] = it }

        hydration.hydrateFolder(listOf(video("a.mp4"), video("b.mp4"), video("c.mp4")), emptyMap())
        runCurrent()
        // Opening another folder replaces the first pass while "a" is mid-probe.
        hydration.hydrateFolder(listOf(video("d.mp4")), emptyMap())
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        hydration.hydrateFolder(listOf(video("a.mp4"), video("b.mp4"), video("c.mp4")), emptyMap())
        advanceUntilIdle()

        assertEquals(listOf("a.mp4", "d.mp4", "b.mp4", "c.mp4"), probed)
        assertEquals(
            listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4"),
            reported.map(ProbeResult::relativePath).sorted(),
        )
    }

    @Test
    fun `opening a folder does not cancel the library sweep`() = runTest {
        val hydration = coordinator()

        hydration.hydrateLibrary(listOf(video("a.mp4"), video("b.mp4")), emptyMap())
        hydration.hydrateFolder(listOf(video("b.mp4"), video("c.mp4")), emptyMap())
        advanceUntilIdle()

        // "b" is listed by both passes and probed by whichever reached it first.
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), probed.sorted())
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), reported.map(ProbeResult::relativePath).sorted())
    }

    @Test
    fun `a batch's durations are saved in one write`() = runTest {
        val hydration = coordinator()

        hydration.hydrateFolder(listOf(video("a.mp4"), video("b.mp4"), video("c.mp4")), mapOf("b.mp4" to 42.0))
        advanceUntilIdle()

        // The fake probes finish well inside one flush interval.
        assertEquals(listOf(mapOf("a.mp4" to 60.0, "b.mp4" to 42.0, "c.mp4" to 60.0)), savedBatches)
        assertEquals(3, reported.size)
    }

    @Test
    fun `a file that fails to probe is reported without a thumbnail and not retried`() = runTest {
        val hydration = coordinator()
        broken += "a.mp4"

        hydration.hydrateFolder(listOf(video("a.mp4")), mapOf("a.mp4" to 42.0))
        advanceUntilIdle()
        hydration.hydrateLibrary(listOf(video("a.mp4")), emptyMap())
        advanceUntilIdle()

        assertEquals(listOf("a.mp4"), probed)
        val result = reported.single()
        assertEquals(42.0, result.durationSeconds!!, 0.0)
        assertNull(result.thumbnail)
    }
}
