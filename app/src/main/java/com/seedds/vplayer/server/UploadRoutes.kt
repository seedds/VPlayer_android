package com.seedds.vplayer.server

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryException
import com.seedds.vplayer.data.fs.LibraryNames
import com.seedds.vplayer.data.library.LibraryArtifacts
import com.seedds.vplayer.data.library.LibraryRepository
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.model.UploadStatus
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Everything the HTTP layer needs, kept as plain collaborators so the routes can be tested without Android. */
class ServerDependencies(
    val repository: LibraryRepository,
    val sessions: UploadSessionManager,
    val artifacts: LibraryArtifacts,
    val page: UploadPage,
    val maxParallelUploads: () -> Int,
    val onActivity: suspend (UploadStatus, String) -> Unit = { _, _ -> },
    val onLibraryChanged: suspend () -> Unit = {},
)

/**
 * The LAN upload API.
 *
 * Every endpoint answers with the same `{"message": ...}` shape on refusal and
 * the message is written for a person, because it is shown verbatim in the
 * browser. Handled failures are 400s: the request was understood and declined.
 * A 500 means the server itself broke.
 *
 * There is no authentication, TLS or rate limiting here. The server is meant
 * for a trusted home network and only runs while the user has it switched on.
 */
fun Application.uploadServerModule(deps: ServerDependencies) {
    install(ContentNegotiation) { json(ServerJson) }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message?.takeIf(String::isNotBlank) ?: LibraryErrors.UNKNOWN_SERVER_ERROR),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(LibraryErrors.ROUTE_NOT_FOUND))
        }
    }

    routing {
        get("/") {
            call.guarded(deps) {
                // No caching: the page embeds the concurrency setting, and a
                // stale copy would silently ignore a change the user just made.
                call.response.cacheControl(CacheControl.NoStore(null))
                call.respondText(
                    text = deps.page.render(
                        chunkSize = UploadSessionManager.CHUNK_SIZE,
                        maxParallelUploads = deps.maxParallelUploads(),
                    ),
                    contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
                )
            }
        }

        get("/library/list") {
            call.guarded(deps) {
                val path = call.request.queryParameters["path"]
                call.respond(deps.listing(path))
            }
        }

        post("/upload/init") {
            call.guarded(deps) {
                val body = call.jsonBody()
                val requestedPath = RequestFields.optionalString(body, "relativePath")
                    ?: RequestFields.requireString(body, "fileName")
                val totalSize = RequestFields.requireNumber(body, "totalSize")

                val (session, result) = deps.sessions.init(requestedPath, totalSize)
                deps.onActivity(UploadStatus.Receiving, "Preparing ${session.fileName}")
                call.respond(
                    UploadInitResponse(
                        uploadId = result.uploadId,
                        relativePath = result.relativePath,
                        chunkSize = result.chunkSize,
                    ),
                )
            }
        }

        post("/upload/chunk") {
            call.guarded(deps) {
                val uploadId = RequestFields.requireHeader(call.request.headers["x-upload-id"], "x-upload-id")
                val chunkIndex = RequestFields.requireHeaderInt(call.request.headers["x-chunk-index"], "x-chunk-index")
                val totalChunks =
                    RequestFields.requireHeaderInt(call.request.headers["x-total-chunks"], "x-total-chunks")
                val totalSize = RequestFields.requireHeaderLong(call.request.headers["x-total-size"], "x-total-size")

                val progress = call.consumeChunk { source ->
                    deps.sessions.appendChunk(
                        uploadId = uploadId,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        totalSize = totalSize,
                        source = source,
                    )
                }

                val session = deps.sessions.activeSessions().firstOrNull { it.uploadId == uploadId }
                deps.onActivity(UploadStatus.Receiving, "Uploading ${session?.fileName ?: uploadId}")
                call.respond(
                    UploadChunkResponse(receivedBytes = progress.receivedBytes, totalBytes = progress.totalBytes),
                )
            }
        }

        post("/upload/complete") {
            call.guarded(deps) {
                val uploadId = RequestFields.requireString(call.jsonBody(), "uploadId")
                val session = try {
                    deps.sessions.complete(uploadId)
                } catch (error: Throwable) {
                    val name = error.message ?: uploadId
                    deps.onActivity(UploadStatus.Error, "Failed to save $name")
                    throw error
                }
                deps.onActivity(UploadStatus.Complete, "Saved ${session.relativePath}")
                deps.onLibraryChanged()
                call.respond(OkResponse())
            }
        }

        post("/upload/cancel") {
            call.guarded(deps) {
                val uploadId = RequestFields.requireString(call.jsonBody(), "uploadId")
                deps.sessions.cancel(uploadId)?.let { session ->
                    deps.onActivity(UploadStatus.Error, "Cancelled ${session.fileName}")
                }
                call.respond(OkResponse())
            }
        }

        post("/library/folder") {
            call.guarded(deps) {
                val body = call.jsonBody()
                val parentPath = RequestFields.optionalString(body, "parentPath")
                val name = RequestFields.requireString(body, "name")

                val folder = deps.repository.createFolder(parentPath, name)
                deps.onActivity(UploadStatus.Idle, "Created folder ${folder.name}")
                deps.onLibraryChanged()
                call.respond(deps.mutationResponse(parentPath))
            }
        }

        post("/library/delete") {
            call.guarded(deps) {
                val body = call.jsonBody()
                val relativePath = RequestFields.requireString(body, "relativePath")
                val currentPath = RequestFields.optionalString(body, "currentPath")

                val target = deps.repository.getItem(relativePath)
                    ?: throw LibraryException(LibraryErrors.ITEM_NOT_FOUND)
                // Enumerate first: once the files are gone there is nothing
                // left to look up, and their progress and thumbnails would leak.
                deps.artifacts.forget(deps.repository.collectVideos(target))
                deps.repository.delete(target.relativePath)

                deps.onActivity(UploadStatus.Idle, "Deleted ${target.name}")
                deps.onLibraryChanged()
                call.respond(deps.mutationResponse(currentPath))
            }
        }

        post("/library/rename") {
            call.guarded(deps) {
                val body = call.jsonBody()
                val relativePath = RequestFields.requireString(body, "relativePath")
                val name = RequestFields.requireString(body, "name")
                val currentPath = RequestFields.optionalString(body, "currentPath")

                val target = deps.repository.getItem(relativePath)
                    ?: throw LibraryException(LibraryErrors.ITEM_NOT_FOUND)
                val videos = deps.repository.collectVideos(target)
                val renamed = deps.repository.rename(target.relativePath, name)
                deps.artifacts.relink(videos, target.relativePath, renamed.relativePath)

                deps.onActivity(UploadStatus.Idle, "Renamed ${target.name} to ${renamed.name}")
                deps.onLibraryChanged()
                call.respond(deps.mutationResponse(currentPath, item = renamed))
            }
        }

        post("/library/move") {
            call.guarded(deps) {
                val body = call.jsonBody()
                val currentPath = RequestFields.optionalString(body, "currentPath")
                val destinationPath = RequestFields.optionalString(body, "destinationPath")
                val entries = RequestFields.moveItems(body)

                var movedCount = 0
                val failures = mutableListOf<String>()
                // Each item is attempted on its own: one refusal should not
                // strand the rest of a bulk move.
                entries.forEach { entry ->
                    when (entry) {
                        is MoveEntry.Invalid -> failures += entry.message
                        is MoveEntry.Path -> runCatching {
                            val target = deps.repository.getItem(entry.relativePath)
                                ?: throw LibraryException(LibraryErrors.ITEM_NOT_FOUND)
                            val videos = deps.repository.collectVideos(target)
                            val moved = deps.repository.move(target.relativePath, destinationPath)
                            if (moved.relativePath != target.relativePath) {
                                deps.artifacts.relink(videos, target.relativePath, moved.relativePath)
                                movedCount++
                            }
                        }.onFailure { error ->
                            failures += error.message?.takeIf(String::isNotBlank)
                                ?: LibraryErrors.COULD_NOT_MOVE
                        }
                    }
                }

                deps.onActivity(
                    UploadStatus.Idle,
                    "Moved $movedCount item${if (movedCount == 1) "" else "s"}",
                )
                deps.onLibraryChanged()
                call.respond(
                    deps.mutationResponse(currentPath, movedCount = movedCount, failures = failures),
                )
            }
        }
    }
}

private suspend fun ServerDependencies.listing(path: String?): ListingResponse {
    val normalized = LibraryNames.normalizeDirectoryPath(path)
    return ListingResponse(
        path = normalized,
        items = repository.list(normalized).map(LibraryItem::toDto),
    )
}

private suspend fun ServerDependencies.mutationResponse(
    path: String?,
    item: LibraryItem? = null,
    movedCount: Int? = null,
    failures: List<String>? = null,
): MutationResponse {
    val listing = listing(path)
    return MutationResponse(
        path = listing.path,
        items = listing.items,
        item = item?.toDto(),
        movedCount = movedCount,
        failures = failures,
    )
}

/**
 * Runs a handler, turning any refusal into the shared 400 shape. A stale
 * session sweep runs first on every request, which is cheap and means a browser
 * tab that went away cannot hold an upload open indefinitely.
 */
private suspend fun ApplicationCall.guarded(deps: ServerDependencies, block: suspend () -> Unit) {
    try {
        val swept = deps.sessions.sweepStale()
        if (swept.isNotEmpty()) deps.onActivity(UploadStatus.Idle, "Cleaned up an inactive upload.")
        block()
    } catch (error: Throwable) {
        respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(error.message?.takeIf(String::isNotBlank) ?: LibraryErrors.UNKNOWN_SERVER_ERROR),
        )
    }
}

private suspend fun ApplicationCall.jsonBody(): JsonObject {
    val text = receiveText()
    if (text.isBlank()) return JsonObject(emptyMap())
    return ServerJson.parseToJsonElement(text).jsonObject
}

/**
 * Streams the `file` part of a chunk upload into [consume].
 *
 * Every part is drained and disposed even when the handler refuses, because a
 * connection left with an unread body cannot be reused and the browser's next
 * chunk would fail on a reset instead of the real reason.
 */
private suspend fun ApplicationCall.consumeChunk(
    consume: suspend (java.io.InputStream?) -> ChunkProgress,
): ChunkProgress {
    val multipart = receiveMultipart()
    var progress: ChunkProgress? = null
    var failure: Throwable? = null
    var sawFilePart = false

    while (true) {
        val part = multipart.readPart() ?: break
        try {
            if (!sawFilePart && part is PartData.FileItem && part.name == "file") {
                sawFilePart = true
                try {
                    progress = part.provider().toInputStream().use { consume(it) }
                } catch (error: Throwable) {
                    failure = error
                }
            }
        } finally {
            part.dispose()
        }
    }

    failure?.let { throw it }
    if (!sawFilePart) return consume(null)
    return progress ?: throw LibraryException(LibraryErrors.UPLOAD_CHUNK_MISSING)
}
