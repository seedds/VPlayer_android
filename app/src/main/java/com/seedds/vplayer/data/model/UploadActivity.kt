package com.seedds.vplayer.data.model

enum class UploadStatus { Idle, Receiving, Complete, Error, Stopped }

/** One upload currently in flight, as shown in the Upload tab. */
data class ActiveUploadRow(
    val uploadId: String,
    /** The destination path inside the library, e.g. `Shows/ep1.mp4`. */
    val fileName: String,
    val message: String,
    val updatedAt: Long,
    val receivedBytes: Long,
    val totalBytes: Long,
)

/**
 * The single "what is the server doing" value the Upload tab renders. When any
 * upload is in flight the aggregate status and message are derived from the
 * rows rather than from whatever event was emitted.
 */
data class UploadActivity(
    val status: UploadStatus,
    val message: String,
    val updatedAt: Long,
    val activeUploads: List<ActiveUploadRow> = emptyList(),
    val receivedBytes: Long? = null,
    val totalBytes: Long? = null,
) {
    companion object {
        fun of(status: UploadStatus, message: String, updatedAt: Long = System.currentTimeMillis()) =
            UploadActivity(status = status, message = message, updatedAt = updatedAt)
    }
}

/**
 * Fraction complete in `0..1`. A missing or zero total reads as 0%, except for
 * a finished upload which reads as 100%.
 */
fun uploadProgress(status: UploadStatus, receivedBytes: Long?, totalBytes: Long?): Float {
    if (totalBytes == null || totalBytes == 0L || receivedBytes == null || receivedBytes == 0L) {
        return if (status == UploadStatus.Complete) 1f else 0f
    }
    return (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

fun UploadActivity.progress(): Float = uploadProgress(status, receivedBytes, totalBytes)

fun ActiveUploadRow.progress(): Float =
    uploadProgress(UploadStatus.Receiving, receivedBytes, totalBytes)
