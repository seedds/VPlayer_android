package com.seedds.vplayer.data.fs

import java.io.File

/**
 * Where everything lives inside the app sandbox. Nothing the app stores leaves
 * these directories, which is why the app needs no storage permissions.
 */
class LibraryPaths(
    val filesDir: File,
    val cacheDir: File,
) {
    /** The user-visible library. Folder depth is unbounded. */
    val videosDir: File = File(filesDir, "videos")

    /** Uploads being assembled; a file only moves into the library once complete. */
    val tempUploadsDir: File = File(filesDir, "uploads-tmp")

    val thumbnailsDir: File = File(filesDir, "thumbnails")

    val settingsFile: File = File(filesDir, "app-settings.json")

    val playbackStateFile: File = File(filesDir, "playback-state.json")

    /** Staging for in-flight HTTP chunks; swept whenever the server starts. */
    val chunkTempDir: File = File(cacheDir, "upload-chunk-temp")

    fun ensureDirectories() {
        videosDir.mkdirs()
        tempUploadsDir.mkdirs()
        thumbnailsDir.mkdirs()
    }

    /** Absolute file for a library-relative path; `""` is the library root. */
    fun fileFor(relativePath: String?): File =
        if (relativePath.isNullOrEmpty()) videosDir else File(videosDir, relativePath)

    /** The library-relative path of a file inside the library, or null if outside. */
    fun relativePathOf(file: File): String? {
        val root = videosDir.absolutePath
        val path = file.absolutePath
        if (path == root) return ""
        if (!path.startsWith("$root/")) return null
        return path.removePrefix("$root/")
    }
}
