package com.seedds.vplayer.data.fs

/**
 * A failure with a message meant for a person: it is shown verbatim in an app
 * alert and returned verbatim as the HTTP body, so the phone and the browser
 * always explain a refusal the same way.
 */
class LibraryException(message: String) : Exception(message)

/** Every rejection message the library and upload endpoints can produce. */
object LibraryErrors {
    const val ITEM_NOT_FOUND = "Library item not found."
    const val NAME_TAKEN = "A file or folder with that name already exists."
    const val NAME_TAKEN_IN_DESTINATION = "A file or folder with that name already exists in the destination."
    const val FOLDER_INTO_ITSELF = "A folder cannot be moved into itself."
    const val DESTINATION_NOT_FOUND = "Destination folder not found."
    const val COULD_NOT_CREATE_FOLDER = "Could not create the folder."
    const val COULD_NOT_RENAME = "Could not rename the item."
    const val COULD_NOT_MOVE = "Could not move the item."
    const val COULD_NOT_DELETE = "Could not delete the file."
    const val SELECT_AT_LEAST_ONE = "Select at least one item to move."
    const val INVALID_ITEM_TO_MOVE = "Invalid item to move."

    const val UPLOAD_SESSION_NOT_FOUND = "Upload session not found."
    const val UPLOAD_SIZE_MISMATCH = "Upload size mismatch."
    const val CHUNK_INDEX_OUT_OF_RANGE = "Chunk index out of range."
    const val UPLOAD_CHUNK_MISSING = "Uploaded chunk file missing."
    const val CHUNK_EXCEEDS_SIZE = "Chunk exceeds declared upload size."
    const val UPLOAD_INCOMPLETE = "Upload is incomplete."
    const val UPLOAD_EMPTY = "Upload must contain at least one byte."
    const val FOLDER_NAME_TAKEN = "A folder with that name already exists."
    const val ROUTE_NOT_FOUND = "Route not found."
    const val UNKNOWN_SERVER_ERROR = "Unknown server error."

    fun keepExtension(extension: String) = "Keep the $extension extension so the file stays playable."

    fun missingField(field: String) = "Missing $field."

    fun invalidField(field: String) = "Invalid $field."

    fun unexpectedChunkOrder(expected: Int) = "Unexpected chunk order. Expected chunk $expected."
}
