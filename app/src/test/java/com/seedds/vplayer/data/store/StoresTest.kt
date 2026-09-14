package com.seedds.vplayer.data.store

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonFileStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store(file: File) = JsonFileStore(
        file = file,
        serializer = MapSerializer(String.serializer(), String.serializer()),
        json = JsonFileStore.Format,
        defaultValue = { emptyMap() },
    )

    @Test
    fun `a missing file reads as the default`() = runTest {
        assertEquals(emptyMap<String, String>(), store(File(temp.root, "missing.json")).read())
    }

    @Test
    fun `a corrupt file recovers instead of throwing`() = runTest {
        val file = temp.newFile("corrupt.json")
        file.writeText("{ not json at all")
        assertEquals(emptyMap<String, String>(), store(file).read())
    }

    @Test
    fun `writes survive a reload`() = runTest {
        val file = File(temp.root, "values.json")
        store(file).update { it + ("a" to "1") }
        assertEquals(mapOf("a" to "1"), store(file).read())
    }

    @Test
    fun `returning null from an update writes nothing`() = runTest {
        val file = File(temp.root, "values.json")
        val store = store(file)
        store.update { it + ("a" to "1") }
        val before = file.lastModified()
        val result = store.update { null }
        assertEquals(mapOf("a" to "1"), result)
        assertEquals(before, file.lastModified())
    }

    @Test
    fun `no temp file is left behind`() = runTest {
        val file = File(temp.root, "values.json")
        store(file).update { it + ("a" to "1") }
        assertFalse(File(temp.root, "values.json.tmp").exists())
    }
}

class PlaybackStateStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private var clock = 1_000L
    private fun newStore() = PlaybackStateStore(File(temp.root, "playback-state.json")) { clock }

    @Test
    fun `saving a position marks the video as started`() = runTest {
        val store = newStore()
        store.savePosition("Shows/ep1.mp4", 12.5, 100.0)
        val entry = store.entry("Shows/ep1.mp4")!!
        assertEquals(12.5, entry.positionSeconds, 0.0)
        assertEquals(100.0, entry.durationSeconds!!, 0.0)
        assertEquals(true, entry.hasStartedPlayback)
        assertEquals(1_000L, entry.updatedAt)
    }

    @Test
    fun `probing a duration leaves the video looking new`() = runTest {
        val store = newStore()
        store.saveDuration("Shows/ep1.mp4", 100.0)
        val entry = store.entry("Shows/ep1.mp4")!!
        assertEquals(false, entry.hasStartedPlayback)
        assertEquals(0.0, entry.positionSeconds, 0.0)
    }

    @Test
    fun `a save without a usable duration keeps the probed one`() = runTest {
        val store = newStore()
        store.saveDuration("a.mp4", 100.0)
        store.savePosition("a.mp4", 10.0, Double.NaN)
        assertEquals(100.0, store.entry("a.mp4")!!.durationSeconds!!, 0.0)
    }

    @Test
    fun `invalid positions are rejected`() = runTest {
        val store = newStore()
        store.savePosition("a.mp4", -1.0)
        store.savePosition("a.mp4", Double.NaN)
        assertNull(store.entry("a.mp4"))
        assertEquals(0.0, store.position("a.mp4"), 0.0)
    }

    @Test
    fun `clearing all keeps durations so nothing needs re-probing`() = runTest {
        val store = newStore()
        store.savePosition("a.mp4", 50.0, 100.0)
        store.savePosition("b.mp4", 20.0, 60.0)
        clock = 2_000L
        store.clearAll()
        listOf("a.mp4", "b.mp4").forEach { path ->
            val entry = store.entry(path)!!
            assertEquals(0.0, entry.positionSeconds, 0.0)
            assertEquals(false, entry.hasStartedPlayback)
            assertEquals(2_000L, entry.updatedAt)
        }
        assertEquals(100.0, store.entry("a.mp4")!!.durationSeconds!!, 0.0)
    }

    @Test
    fun `clearing a selection leaves the rest alone`() = runTest {
        val store = newStore()
        store.savePosition("a.mp4", 50.0, 100.0)
        store.savePosition("b.mp4", 20.0, 60.0)
        store.clearFor(listOf("a.mp4", "missing.mp4"))
        assertEquals(0.0, store.entry("a.mp4")!!.positionSeconds, 0.0)
        assertEquals(20.0, store.entry("b.mp4")!!.positionSeconds, 0.0)
    }

    @Test
    fun `removing drops entries entirely`() = runTest {
        val store = newStore()
        store.savePosition("a.mp4", 50.0, 100.0)
        store.remove(listOf("a.mp4"))
        assertNull(store.entry("a.mp4"))
    }

    @Test
    fun `moving re-keys progress and forgets the old path`() = runTest {
        val store = newStore()
        store.savePosition("Shows/ep1.mp4", 50.0, 100.0)
        store.move(listOf("Shows/ep1.mp4" to "Archive/ep1.mp4"))
        assertNull(store.entry("Shows/ep1.mp4"))
        assertEquals(50.0, store.entry("Archive/ep1.mp4")!!.positionSeconds, 0.0)
    }

    @Test
    fun `a move to the same path changes nothing`() = runTest {
        val store = newStore()
        store.savePosition("a.mp4", 50.0, 100.0)
        store.move(listOf("a.mp4" to "a.mp4"))
        assertEquals(50.0, store.entry("a.mp4")!!.positionSeconds, 0.0)
    }

    @Test
    fun `state survives being reopened`() = runTest {
        val file = File(temp.root, "playback-state.json")
        PlaybackStateStore(file) { clock }.savePosition("a.mp4", 33.0, 100.0)
        val reopened = PlaybackStateStore(file) { clock }
        assertEquals(33.0, reopened.position("a.mp4"), 0.0)
        assertTrue(file.readText().contains("\"positionSeconds\""))
    }
}

class SettingsStoreTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `defaults apply on first launch`() = runTest {
        val settings = SettingsStore(File(temp.root, "app-settings.json")).load()
        assertEquals(com.seedds.vplayer.settings.Settings(), settings)
    }

    @Test
    fun `an out of range stored value is clamped on read`() = runTest {
        val file = temp.newFile("app-settings.json")
        file.writeText("""{"maxParallelUploads":99,"subtitleFontSize":2,"longPressSpeedTenths":0}""")
        val settings = SettingsStore(file).load()
        assertEquals(5, settings.maxParallelUploads)
        assertEquals(24, settings.subtitleFontSize)
        assertEquals(10, settings.longPressSpeedTenths)
    }

    @Test
    fun `updates persist and publish`() = runTest {
        val file = File(temp.root, "app-settings.json")
        val store = SettingsStore(file)
        store.load()
        store.update(com.seedds.vplayer.settings.SettingKey.SubtitleFontSize, 48)
        assertEquals(48, store.settings.value.subtitleFontSize)
        assertEquals(48, SettingsStore(file).load().subtitleFontSize)
    }
}
