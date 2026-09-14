package com.seedds.vplayer.data.model

/**
 * What a library entry is. Only [Video] can be played; [Subtitle] pairs with a
 * video by base name, and [File] is anything else the user uploaded, which is
 * listed and manageable but never playable.
 */
enum class LibraryKind(val wireName: String) {
    Folder("folder"),
    Video("video"),
    Subtitle("subtitle"),
    File("file"),
}

/**
 * One entry in the library.
 *
 * [relativePath] is relative to the library root (`videos/`) and is the entry's
 * identity: playback progress and thumbnails are keyed by it, and the HTTP API
 * addresses items by it.
 *
 * [modified] is epoch milliseconds, or 0 when the filesystem does not report
 * it. It must never fall back to "now": it feeds the thumbnail and probe cache
 * keys, so a fabricated timestamp would change on every listing and the file
 * could never hit its cache.
 */
sealed interface LibraryItem {
    val kind: LibraryKind
    val name: String
    val relativePath: String
    val parentPath: String?
    val modified: Long

    data class Folder(
        override val name: String,
        override val relativePath: String,
        override val parentPath: String?,
        override val modified: Long,
    ) : LibraryItem {
        override val kind: LibraryKind get() = LibraryKind.Folder
    }

    data class Video(
        override val name: String,
        override val relativePath: String,
        override val parentPath: String?,
        override val modified: Long,
        val size: Long,
        val extension: String,
    ) : LibraryItem {
        override val kind: LibraryKind get() = LibraryKind.Video
    }

    data class Subtitle(
        override val name: String,
        override val relativePath: String,
        override val parentPath: String?,
        override val modified: Long,
        val size: Long,
        val extension: String,
    ) : LibraryItem {
        override val kind: LibraryKind get() = LibraryKind.Subtitle
    }

    data class File(
        override val name: String,
        override val relativePath: String,
        override val parentPath: String?,
        override val modified: Long,
        val size: Long,
        val extension: String,
    ) : LibraryItem {
        override val kind: LibraryKind get() = LibraryKind.File
    }
}

/** Byte size for the entries that have one; folders report null. */
val LibraryItem.sizeOrNull: Long?
    get() = when (this) {
        is LibraryItem.Folder -> null
        is LibraryItem.Video -> size
        is LibraryItem.Subtitle -> size
        is LibraryItem.File -> size
    }

/** Lowercased extension including the leading dot, or null for folders. */
val LibraryItem.extensionOrNull: String?
    get() = when (this) {
        is LibraryItem.Folder -> null
        is LibraryItem.Video -> extension
        is LibraryItem.Subtitle -> extension
        is LibraryItem.File -> extension
    }
