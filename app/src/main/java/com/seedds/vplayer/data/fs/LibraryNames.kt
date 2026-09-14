package com.seedds.vplayer.data.fs

import java.text.Normalizer

/**
 * Every rule about what a library file or folder may be called, and how a
 * caller-supplied path is turned into one that is safe to touch on disk.
 *
 * The character policy is deliberately permissive about scripts and strict
 * about structure: any Unicode letter, mark or number survives (so Chinese,
 * Japanese and accented names round-trip), while separators, control
 * characters and anything else collapse to `_`.
 */
object LibraryNames {

    val ALLOWED_VIDEO_EXTENSIONS = listOf(".mp4", ".mov", ".m4v", ".webm", ".mkv")
    val ALLOWED_SUBTITLE_EXTENSIONS = listOf(".srt")

    private val DISALLOWED = Regex("""[^\p{L}\p{M}\p{N}._ -]""")
    private val WHITESPACE_RUN = Regex("""\s+""")
    private val ALL_DOTS = Regex("""^\.+$""")
    private val TRAILING_DOTS = Regex("""\.+$""")
    private val EXTENSION = Regex("""\.[^.]+$""")
    private val SEPARATORS = Regex("""[\\/]+""")

    const val FALLBACK_FOLDER_NAME = "folder"
    const val FALLBACK_FILE_NAME = "upload"

    /** The trailing `.ext` of a name, lowercased, or `""` when there is none. */
    fun extensionOf(name: String): String =
        EXTENSION.find(name.lowercase())?.value.orEmpty()

    fun isVideoFileName(name: String): Boolean = extensionOf(name) in ALLOWED_VIDEO_EXTENSIONS

    fun isSubtitleFileName(name: String): Boolean = extensionOf(name) in ALLOWED_SUBTITLE_EXTENSIONS

    /** The name without its extension, used to pair a video with its subtitle. */
    fun baseNameOf(name: String): String {
        val extension = extensionOf(name)
        return if (extension.isEmpty()) name else name.dropLast(extension.length)
    }

    fun sanitizeFolderName(input: String): String {
        val cleaned = Normalizer.normalize(input, Normalizer.Form.NFC)
            .replace(DISALLOWED, "_")
            .replace(WHITESPACE_RUN, " ")
            .trim()
        val normalized = cleaned
            .replace(ALL_DOTS, "")
            .replace(TRAILING_DOTS, "")
            .trim()
        return normalized.ifEmpty { FALLBACK_FOLDER_NAME }
    }

    /**
     * Reduces an arbitrary caller-supplied name to a single safe leaf name.
     * Any directory part is dropped, so this can be fed a full browser path.
     * The extension is lowercased; the base name keeps its case.
     */
    fun sanitizeFileName(input: String): String {
        val leaf = input.split(SEPARATORS).lastOrNull()?.trim().orEmpty()
            .ifEmpty { FALLBACK_FILE_NAME }
        val cleaned = Normalizer.normalize(leaf, Normalizer.Form.NFC)
            .replace(DISALLOWED, "_")
            .replace(WHITESPACE_RUN, " ")
        val extension = extensionOf(cleaned)
        val rawBase = if (extension.isEmpty()) cleaned else cleaned.dropLast(extension.length)
        val base = rawBase
            .replace(ALL_DOTS, "")
            .replace(TRAILING_DOTS, "")
            .trim()
            .ifEmpty { FALLBACK_FILE_NAME }
        return base + extension.lowercase()
    }

    /** Splits on either separator, trimming segments and dropping empty ones. */
    fun splitRelativePath(input: String?): List<String> =
        input.orEmpty().split(SEPARATORS).map(String::trim).filter(String::isNotEmpty)

    /**
     * Normalises a folder path for listing. Every segment is sanitised, so a
     * `..` segment becomes a literal folder named `folder` rather than escaping
     * the library root. Use [toLookupPath] instead when resolving an item that
     * must already exist.
     */
    fun normalizeDirectoryPath(input: String?): String =
        splitRelativePath(input).joinToString("/") { sanitizeFolderName(it) }

    /** Normalises a file path: folder rules for the parents, file rules for the leaf. */
    fun normalizeFilePath(input: String?): String {
        val segments = splitRelativePath(input)
        if (segments.isEmpty()) return sanitizeFileName(FALLBACK_FILE_NAME)
        val parents = segments.dropLast(1).map { sanitizeFolderName(it) }
        return (parents + sanitizeFileName(segments.last())).joinToString("/")
    }

    /**
     * Validates a path that is supposed to name an existing item. Unlike
     * [normalizeDirectoryPath] this does not rewrite segments, because an
     * item's stored path already matches what is on disk; it only refuses
     * empty paths and traversal segments.
     */
    fun toLookupPath(relativePath: String?): List<String>? {
        val segments = splitRelativePath(relativePath)
        if (segments.isEmpty()) return null
        if (segments.any { it == "." || it == ".." }) return null
        return segments
    }

    /** The containing folder of a relative path, or null at the library root. */
    fun parentPathOf(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        val segments = path.split("/").filter(String::isNotEmpty)
        if (segments.size <= 1) return null
        return segments.dropLast(1).joinToString("/")
    }

    fun joinPath(base: String?, child: String): String =
        splitRelativePath(listOfNotNull(base?.takeIf(String::isNotEmpty), child).joinToString("/"))
            .joinToString("/")

    /**
     * Whether renaming [originalName] to [nextName] keeps the file the same
     * kind. A video must stay a video and a subtitle must stay a subtitle, so a
     * rename cannot silently make a file unplayable; other files are free.
     */
    fun renamePreservesKind(originalName: String, nextName: String): Boolean = when {
        isVideoFileName(originalName) -> isVideoFileName(nextName)
        isSubtitleFileName(originalName) -> isSubtitleFileName(nextName)
        else -> true
    }
}
