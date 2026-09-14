package com.seedds.vplayer.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.library.LibraryArtifacts
import com.seedds.vplayer.data.library.LibraryRepository
import com.seedds.vplayer.data.media.ThumbnailCache
import com.seedds.vplayer.data.store.PlaybackStateStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Drives the real CIO engine on the device with a real HTTP client, which is
 * the only way to catch problems the JVM route tests cannot see: the engine
 * failing to bind, connection reuse breaking between chunks, or R8 stripping
 * something the server needs at runtime.
 */
@RunWith(AndroidJUnit4::class)
class UploadServerOnDeviceTest {

    private lateinit var root: File
    private lateinit var paths: LibraryPaths
    private lateinit var controller: UploadServerController
    private var port: Int = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun base() = "http://127.0.0.1:$port"

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "server-test-${System.nanoTime()}").apply { mkdirs() }
        paths = LibraryPaths(filesDir = File(root, "files"), cacheDir = File(root, "cache"))
        paths.ensureDirectories()

        val repository = LibraryRepository(paths)
        val thumbnails = ThumbnailCache(paths)
        port = freePort()

        controller = UploadServerController(
            paths = paths,
            dependenciesFactory = { sessions ->
                ServerDependencies(
                    repository = repository,
                    sessions = sessions,
                    artifacts = LibraryArtifacts(PlaybackStateStore(paths.playbackStateFile), thumbnails),
                    page = UploadPage {
                        context.assets.open(UploadPage.ASSET_NAME).use { it.readBytes().decodeToString() }
                    },
                    maxParallelUploads = { 3 },
                )
            },
        )
        controller.start(port)
    }

    @After
    fun tearDown() = runBlocking {
        controller.stop()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun servesTheUploadPageWithItsSettingsSubstituted() {
        val response = client.newCall(Request.Builder().url(base() + "/").build()).execute()
        response.use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue(body.contains("const defaultChunkSize = 1048576;"))
            assertTrue(body.contains("const MAX_PARALLEL_UPLOADS = 3;"))
            assertTrue("placeholders should all be replaced", !body.contains("__VPLAYER"))
        }
    }

    @Test
    fun uploadsAcrossManyChunksOnOneConnection() {
        val payload = ByteArray(5 * 64 * 1024) { (it % 251).toByte() }
        val init = postJson("/upload/init", """{"relativePath":"Clips/on-device.mp4","totalSize":${payload.size}}""")
        val uploadId = init.getString("uploadId")

        // Deliberately far smaller than the server's chunk size, to push many
        // requests down the same keep-alive connection.
        val chunkSize = 64 * 1024
        val totalChunks = (payload.size + chunkSize - 1) / chunkSize
        for (index in 0 until totalChunks) {
            val start = index * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val progress = postChunk(uploadId, index, totalChunks, payload.size, payload.copyOfRange(start, end))
            assertEquals(end.toLong(), progress.getLong("receivedBytes"))
        }

        postJson("/upload/complete", """{"uploadId":"$uploadId"}""")

        val saved = paths.fileFor("Clips/on-device.mp4")
        assertTrue(saved.exists())
        assertEquals(payload.size.toLong(), saved.length())
        assertTrue(saved.readBytes().contentEquals(payload))
    }

    @Test
    fun reportsRefusalsAsJsonMessages() {
        val request = Request.Builder()
            .url(base() + "/upload/complete")
            .post("""{"uploadId":"missing"}""".toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals(400, response.code)
            assertEquals("Upload session not found.", JSONObject(response.body!!.string()).getString("message"))
        }

        client.newCall(Request.Builder().url(base() + "/nope").build()).execute().use { response ->
            assertEquals(404, response.code)
            assertEquals("Route not found.", JSONObject(response.body!!.string()).getString("message"))
        }
    }

    @Test
    fun listsTheLibraryItCreated() {
        postJson("/library/folder", """{"name":"Archive"}""")
        val listing = getJson("/library/list")
        val items = listing.getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals("Archive", items.getJSONObject(0).getString("name"))
        assertEquals("folder", items.getJSONObject(0).getString("kind"))
    }

    private fun postJson(path: String, body: String): JSONObject {
        val request = Request.Builder().url(base() + path).post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body!!.string()
            assertTrue("$path failed: $text", response.isSuccessful)
            return JSONObject(text)
        }
    }

    private fun getJson(path: String): JSONObject {
        client.newCall(Request.Builder().url(base() + path).build()).execute().use { response ->
            return JSONObject(response.body!!.string())
        }
    }

    private fun postChunk(
        uploadId: String,
        index: Int,
        totalChunks: Int,
        totalSize: Int,
        bytes: ByteArray,
    ): JSONObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "chunk.bin", bytes.toRequestBody(OCTET_STREAM))
            .build()
        val request = Request.Builder()
            .url(base() + "/upload/chunk")
            .header("x-upload-id", uploadId)
            .header("x-chunk-index", index.toString())
            .header("x-total-chunks", totalChunks.toString())
            .header("x-total-size", totalSize.toString())
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body!!.string()
            assertTrue("chunk $index failed: $text", response.isSuccessful)
            return JSONObject(text)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
