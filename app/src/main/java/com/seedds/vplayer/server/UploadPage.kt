package com.seedds.vplayer.server

/**
 * The browser upload page.
 *
 * The page is one self-contained asset with no external requests, so it works
 * on a phone serving a laptop over Wi-Fi with no internet involved. Only three
 * values are injected, and the concurrency one is why changing that setting
 * asks the user to refresh the page.
 */
class UploadPage(private val readAsset: () -> String) {

    private val template: String by lazy(readAsset)

    fun render(
        chunkSize: Int,
        maxParallelUploads: Int,
        chunkTimeoutMs: Int = CHUNK_TIMEOUT_MS,
    ): String = template
        .replace(CHUNK_SIZE_TOKEN, chunkSize.toString())
        .replace(MAX_PARALLEL_TOKEN, maxParallelUploads.toString())
        .replace(CHUNK_TIMEOUT_TOKEN, chunkTimeoutMs.toString())

    companion object {
        const val ASSET_NAME = "upload.html"

        private const val CHUNK_SIZE_TOKEN = "__VPLAYER_CHUNK_SIZE__"
        private const val MAX_PARALLEL_TOKEN = "__VPLAYER_MAX_PARALLEL_UPLOADS__"
        private const val CHUNK_TIMEOUT_TOKEN = "__VPLAYER_CHUNK_TIMEOUT_MS__"

        /**
         * A 1 MiB chunk on slow Wi-Fi can take far longer than a control call,
         * so chunk posts get a generous window while JSON calls stay snappy.
         */
        const val CHUNK_TIMEOUT_MS = 60_000
    }
}
