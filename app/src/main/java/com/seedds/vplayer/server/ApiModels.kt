package com.seedds.vplayer.server

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryException
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.model.extensionOrNull
import com.seedds.vplayer.data.model.sizeOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A library entry as the browser page sees it. */
@Serializable
data class LibraryItemDto(
    val kind: String,
    val name: String,
    val modified: Long,
    val parentPath: String? = null,
    val relativePath: String,
    val extension: String? = null,
    val size: Long? = null,
)

fun LibraryItem.toDto() = LibraryItemDto(
    kind = kind.wireName,
    name = name,
    modified = modified,
    parentPath = parentPath,
    relativePath = relativePath,
    extension = extensionOrNull,
    size = sizeOrNull,
)

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class ListingResponse(val path: String, val items: List<LibraryItemDto>)

@Serializable
data class MutationResponse(
    val ok: Boolean = true,
    val path: String,
    val items: List<LibraryItemDto>,
    val item: LibraryItemDto? = null,
    val movedCount: Int? = null,
    val failures: List<String>? = null,
)

@Serializable
data class UploadInitResponse(val uploadId: String, val relativePath: String, val chunkSize: Int)

@Serializable
data class UploadChunkResponse(val ok: Boolean = true, val receivedBytes: Long, val totalBytes: Long)

@Serializable
data class OkResponse(val ok: Boolean = true)

/** JSON wire format: absent rather than null, and tolerant of extra fields. */
val ServerJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** One entry of a move request: either a path to move, or why it is unusable. */
sealed interface MoveEntry {
    data class Path(val relativePath: String) : MoveEntry
    data class Invalid(val message: String) : MoveEntry
}

/**
 * Request bodies are read field by field rather than deserialised into a class,
 * so a missing or mistyped field produces the precise message the browser page
 * expects instead of a serializer's internal complaint.
 */
object RequestFields {

    fun requireString(body: JsonObject, field: String): String {
        val value = (body[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (value.isNullOrEmpty()) throw LibraryException(LibraryErrors.missingField(field))
        return value
    }

    fun optionalString(body: JsonObject, field: String): String? =
        (body[field] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    /** Requires an actual JSON number; a numeric string is not accepted. */
    fun requireNumber(body: JsonObject, field: String): Long {
        val primitive = body[field] as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            throw LibraryException(LibraryErrors.missingField(field))
        }
        val value = primitive.doubleOrNull?.takeIf { it.isFinite() }
            ?: throw LibraryException(LibraryErrors.missingField(field))
        return value.toLong()
    }

    fun requireHeader(value: String?, name: String): String {
        if (value.isNullOrBlank()) throw LibraryException(LibraryErrors.missingField(name))
        return value.trim()
    }

    fun requireHeaderInt(value: String?, name: String): Int =
        requireHeader(value, name).toIntOrNull()
            ?: throw LibraryException(LibraryErrors.invalidField(name))

    fun requireHeaderLong(value: String?, name: String): Long =
        requireHeader(value, name).toLongOrNull()
            ?: throw LibraryException(LibraryErrors.invalidField(name))

    /**
     * Reads the items of a move request. Unusable entries are kept in place
     * rather than dropped, so the response can report one failure per item the
     * caller asked about.
     */
    fun moveItems(body: JsonObject): List<MoveEntry> {
        val array = body["items"] as? JsonArray
        if (array.isNullOrEmpty()) throw LibraryException(LibraryErrors.SELECT_AT_LEAST_ONE)
        return array.map { element ->
            val entry = element as? JsonObject
                ?: return@map MoveEntry.Invalid(LibraryErrors.INVALID_ITEM_TO_MOVE)
            val path = (entry["relativePath"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            if (path == null) {
                MoveEntry.Invalid(LibraryErrors.missingField("relativePath"))
            } else {
                MoveEntry.Path(path)
            }
        }
    }
}
