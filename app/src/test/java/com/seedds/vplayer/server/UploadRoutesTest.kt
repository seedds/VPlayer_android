package com.seedds.vplayer.server

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.library.LibraryArtifacts
import com.seedds.vplayer.data.library.LibraryRepository
import com.seedds.vplayer.data.media.ThumbnailCache
import com.seedds.vplayer.data.model.UploadStatus
import com.seedds.vplayer.data.store.PlaybackStateStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadRoutesTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var paths: LibraryPaths
    private lateinit var repository: LibraryRepository
    private lateinit var deps: ServerDependencies
    private val events = mutableListOf<Pair<UploadStatus, String>>()
    private var libraryChangedCount = 0

    @Before
    fun setUp() {
        paths = LibraryPaths(filesDir = temp.newFolder("files"), cacheDir = temp.newFolder("cache"))
        paths.ensureDirectories()
        repository = LibraryRepository(paths)
        val thumbnails = ThumbnailCache(paths)
        deps = ServerDependencies(
            repository = repository,
            sessions = UploadSessionManager(paths),
            artifacts = LibraryArtifacts(PlaybackStateStore(paths.playbackStateFile), thumbnails),
            page = UploadPage { PAGE_TEMPLATE },
            maxParallelUploads = { 4 },
            onActivity = { status, message -> events += status to message },
            onLibraryChanged = { libraryChangedCount++ },
        )
    }

    private fun seed(relativePath: String, content: String = "x") {
        val file = paths.fileFor(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun ApplicationTestBuilder.install() = application { uploadServerModule(deps) }

    private suspend fun HttpResponse.json(): JsonObject =
        ServerJson.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.message(): String = json()["message"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.postChunk(
        uploadId: String,
        chunkIndex: Int,
        totalChunks: Int,
        totalSize: Long,
        bytes: ByteArray,
    ): HttpResponse = client.post("/upload/chunk") {
        header("x-upload-id", uploadId)
        header("x-chunk-index", chunkIndex.toString())
        header("x-total-chunks", totalChunks.toString())
        header("x-total-size", totalSize.toString())
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        key = "file",
                        value = bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"chunk.bin\"")
                        },
                    )
                },
            ),
        )
    }

    private suspend fun ApplicationTestBuilder.upload(
        relativePath: String,
        payload: ByteArray,
        chunks: Int = 1,
    ): String {
        val init = postJson(
            "/upload/init",
            """{"relativePath":"$relativePath","totalSize":${payload.size}}""",
        ).json()
        val uploadId = init["uploadId"]!!.jsonPrimitive.content
        val chunkSize = (payload.size + chunks - 1) / chunks
        repeat(chunks) { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            postChunk(uploadId, index, chunks, payload.size.toLong(), payload.copyOfRange(start, end))
        }
        return uploadId
    }

    // ------------------------------------------------------------------ page

    @Test
    fun `the upload page is served with its settings substituted`() = testApplication {
        install()
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("const defaultChunkSize = 1048576;"))
        assertTrue(body.contains("const MAX_PARALLEL_UPLOADS = 4;"))
        assertTrue(body.contains("}, 60000);"))
        assertFalse(body.contains("__VPLAYER"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `an unknown route is a 404 with the shared error shape`() = testApplication {
        install()
        val response = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(LibraryErrors.ROUTE_NOT_FOUND, response.message())
    }

    // ---------------------------------------------------------------- upload

    @Test
    fun `a chunked upload lands in the library`() = testApplication {
        install()
        val payload = "hello world".toByteArray()
        val uploadId = upload("Shows/ep1.mp4", payload, chunks = 3)
        val response = postJson("/upload/complete", """{"uploadId":"$uploadId"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello world", paths.fileFor("Shows/ep1.mp4").readText())
        assertEquals(1, libraryChangedCount)
        assertTrue(events.contains(UploadStatus.Complete to "Saved Shows/ep1.mp4"))
    }

    @Test
    fun `init reports the sanitised destination and chunk size`() = testApplication {
        install()
        val body = postJson(
            "/upload/init",
            """{"relativePath":"Shows/My Movie.MP4","totalSize":10}""",
        ).json()
        assertEquals("Shows/My Movie.mp4", body["relativePath"]!!.jsonPrimitive.content)
        assertEquals(1048576, body["chunkSize"]!!.jsonPrimitive.int)
    }

    @Test
    fun `init falls back to fileName when no path is given`() = testApplication {
        install()
        val body = postJson("/upload/init", """{"fileName":"clip.mp4","totalSize":10}""").json()
        assertEquals("clip.mp4", body["relativePath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `init refuses a missing name or size`() = testApplication {
        install()
        assertEquals(
            LibraryErrors.missingField("fileName"),
            postJson("/upload/init", """{"totalSize":10}""").message(),
        )
        assertEquals(
            LibraryErrors.missingField("totalSize"),
            postJson("/upload/init", """{"fileName":"a.mp4"}""").message(),
        )
        assertEquals(
            LibraryErrors.UPLOAD_EMPTY,
            postJson("/upload/init", """{"fileName":"a.mp4","totalSize":0}""").message(),
        )
    }

    @Test
    fun `init refuses a size sent as a string`() = testApplication {
        install()
        val response = postJson("/upload/init", """{"fileName":"a.mp4","totalSize":"10"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(LibraryErrors.missingField("totalSize"), response.message())
    }

    @Test
    fun `a chunk reports progress`() = testApplication {
        install()
        val init = postJson("/upload/init", """{"fileName":"a.mp4","totalSize":10}""").json()
        val uploadId = init["uploadId"]!!.jsonPrimitive.content
        val body = postChunk(uploadId, 0, 2, 10, ByteArray(4)).json()
        assertEquals(4L, body["receivedBytes"]!!.jsonPrimitive.long)
        assertEquals(10L, body["totalBytes"]!!.jsonPrimitive.long)
    }

    @Test
    fun `chunks must arrive in order and match the declared size`() = testApplication {
        install()
        val init = postJson("/upload/init", """{"fileName":"a.mp4","totalSize":10}""").json()
        val uploadId = init["uploadId"]!!.jsonPrimitive.content

        assertEquals(
            LibraryErrors.unexpectedChunkOrder(0),
            postChunk(uploadId, 1, 2, 10, ByteArray(4)).message(),
        )
        assertEquals(
            LibraryErrors.UPLOAD_SIZE_MISMATCH,
            postChunk(uploadId, 0, 2, 11, ByteArray(4)).message(),
        )
        assertEquals(
            LibraryErrors.CHUNK_INDEX_OUT_OF_RANGE,
            postChunk(uploadId, 5, 2, 10, ByteArray(4)).message(),
        )
        assertEquals(
            LibraryErrors.UPLOAD_SESSION_NOT_FOUND,
            postChunk("nope", 0, 2, 10, ByteArray(4)).message(),
        )
    }

    @Test
    fun `a chunk missing its headers is refused`() = testApplication {
        install()
        val response = client.post("/upload/chunk") { setBody(ByteArray(0)) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(LibraryErrors.missingField("x-upload-id"), response.message())
    }

    @Test
    fun `a chunk larger than the declared upload is refused`() = testApplication {
        install()
        val init = postJson("/upload/init", """{"fileName":"a.mp4","totalSize":4}""").json()
        val uploadId = init["uploadId"]!!.jsonPrimitive.content
        assertEquals(
            LibraryErrors.CHUNK_EXCEEDS_SIZE,
            postChunk(uploadId, 0, 1, 4, ByteArray(9)).message(),
        )
    }

    @Test
    fun `completing early is refused`() = testApplication {
        install()
        val init = postJson("/upload/init", """{"fileName":"a.mp4","totalSize":10}""").json()
        val uploadId = init["uploadId"]!!.jsonPrimitive.content
        postChunk(uploadId, 0, 2, 10, ByteArray(4))
        assertEquals(
            LibraryErrors.UPLOAD_INCOMPLETE,
            postJson("/upload/complete", """{"uploadId":"$uploadId"}""").message(),
        )
    }

    @Test
    fun `uploading over an existing file replaces it`() = testApplication {
        install()
        seed("a.mp4", "old")
        val payload = "new".toByteArray()
        val uploadId = upload("a.mp4", payload)
        postJson("/upload/complete", """{"uploadId":"$uploadId"}""")
        assertEquals("new", paths.fileFor("a.mp4").readText())
    }

    @Test
    fun `uploading onto a folder name is refused`() = testApplication {
        install()
        paths.fileFor("busy").mkdirs()
        val uploadId = upload("busy", "x".toByteArray())
        assertEquals(
            LibraryErrors.FOLDER_NAME_TAKEN,
            postJson("/upload/complete", """{"uploadId":"$uploadId"}""").message(),
        )
    }

    @Test
    fun `cancelling is accepted even for an unknown upload`() = testApplication {
        install()
        assertEquals(HttpStatusCode.OK, postJson("/upload/cancel", """{"uploadId":"nope"}""").status)
        assertEquals(
            LibraryErrors.missingField("uploadId"),
            postJson("/upload/cancel", "{}").message(),
        )
    }

    // --------------------------------------------------------------- library

    @Test
    fun `listing returns sorted entries with their kinds`() = testApplication {
        install()
        paths.fileFor("Shows").mkdirs()
        seed("ep10.mp4")
        seed("ep2.mp4")
        seed("notes.txt")

        val body = client.get("/library/list").json()
        assertEquals("", body["path"]!!.jsonPrimitive.content)
        val items = body["items"]!!.jsonArray
        assertEquals(
            listOf("Shows", "ep2.mp4", "ep10.mp4", "notes.txt"),
            items.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("folder", "video", "video", "file"),
            items.map { it.jsonObject["kind"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `listing normalises a traversal path instead of escaping the library`() = testApplication {
        install()
        val body = client.get("/library/list?path=..").json()
        assertEquals("folder", body["path"]!!.jsonPrimitive.content)
        assertTrue((body["items"] as JsonArray).isEmpty())
    }

    @Test
    fun `creating a folder returns the parent listing`() = testApplication {
        install()
        val body = postJson("/library/folder", """{"name":"Shows"}""").json()
        assertEquals("", body["path"]!!.jsonPrimitive.content)
        assertEquals("Shows", body["items"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(events.contains(UploadStatus.Idle to "Created folder Shows"))
        assertEquals(1, libraryChangedCount)
    }

    @Test
    fun `creating a duplicate folder is refused`() = testApplication {
        install()
        postJson("/library/folder", """{"name":"Shows"}""")
        assertEquals(
            LibraryErrors.NAME_TAKEN,
            postJson("/library/folder", """{"name":"Shows"}""").message(),
        )
    }

    @Test
    fun `renaming returns the new item and the current listing`() = testApplication {
        install()
        seed("Shows/ep1.mp4")
        val body = postJson(
            "/library/rename",
            """{"relativePath":"Shows/ep1.mp4","name":"pilot.mp4","currentPath":"Shows"}""",
        ).json()
        assertEquals("pilot.mp4", body["item"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Shows", body["path"]!!.jsonPrimitive.content)
        assertTrue(events.contains(UploadStatus.Idle to "Renamed ep1.mp4 to pilot.mp4"))
    }

    @Test
    fun `renaming must keep a video playable`() = testApplication {
        install()
        seed("a.mp4")
        assertEquals(
            LibraryErrors.keepExtension(".mp4"),
            postJson("/library/rename", """{"relativePath":"a.mp4","name":"a.txt"}""").message(),
        )
    }

    @Test
    fun `renaming something that is gone is refused`() = testApplication {
        install()
        assertEquals(
            LibraryErrors.ITEM_NOT_FOUND,
            postJson("/library/rename", """{"relativePath":"gone.mp4","name":"x.mp4"}""").message(),
        )
    }

    @Test
    fun `deleting removes the item and returns the listing`() = testApplication {
        install()
        seed("Shows/ep1.mp4")
        val body = postJson("/library/delete", """{"relativePath":"Shows"}""").json()
        assertFalse(paths.fileFor("Shows").exists())
        assertTrue((body["items"] as JsonArray).isEmpty())
        assertTrue(events.contains(UploadStatus.Idle to "Deleted Shows"))
    }

    @Test
    fun `deleting a traversal path is refused`() = testApplication {
        install()
        seed("secret.mp4")
        assertEquals(
            LibraryErrors.ITEM_NOT_FOUND,
            postJson("/library/delete", """{"relativePath":"Shows/../secret.mp4"}""").message(),
        )
        assertTrue(paths.fileFor("secret.mp4").exists())
    }

    @Test
    fun `moving reports how many items moved`() = testApplication {
        install()
        seed("a.mp4")
        seed("b.mp4")
        paths.fileFor("Archive").mkdirs()

        val body = postJson(
            "/library/move",
            """{"destinationPath":"Archive","items":[{"relativePath":"a.mp4"},{"relativePath":"b.mp4"}]}""",
        ).json()
        assertEquals(2, body["movedCount"]!!.jsonPrimitive.int)
        assertTrue((body["failures"] as JsonArray).isEmpty())
        assertTrue(paths.fileFor("Archive/a.mp4").exists())
        assertTrue(events.contains(UploadStatus.Idle to "Moved 2 items"))
    }

    @Test
    fun `a move continues past an item it cannot place`() = testApplication {
        install()
        seed("a.mp4")
        seed("b.mp4")
        seed("Archive/a.mp4")

        val body = postJson(
            "/library/move",
            """{"destinationPath":"Archive","items":[{"relativePath":"a.mp4"},{"relativePath":"b.mp4"}]}""",
        ).json()
        assertEquals(1, body["movedCount"]!!.jsonPrimitive.int)
        assertEquals(
            listOf(LibraryErrors.NAME_TAKEN_IN_DESTINATION),
            body["failures"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(paths.fileFor("Archive/b.mp4").exists())
    }

    @Test
    fun `a move with no items is refused`() = testApplication {
        install()
        assertEquals(
            LibraryErrors.SELECT_AT_LEAST_ONE,
            postJson("/library/move", """{"items":[]}""").message(),
        )
    }

    @Test
    fun `a move entry without a path is reported per item`() = testApplication {
        install()
        val body = postJson("/library/move", """{"items":[{},"nope"]}""").json()
        assertEquals(
            listOf(LibraryErrors.missingField("relativePath"), LibraryErrors.INVALID_ITEM_TO_MOVE),
            body["failures"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private companion object {
        val PAGE_TEMPLATE = """
            <html><body><script>
            const defaultChunkSize = __VPLAYER_CHUNK_SIZE__;
            const MAX_PARALLEL_UPLOADS = __VPLAYER_MAX_PARALLEL_UPLOADS__;
            }, __VPLAYER_CHUNK_TIMEOUT_MS__);
            </script></body></html>
        """.trimIndent()
    }
}
