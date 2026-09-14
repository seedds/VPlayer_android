package com.seedds.vplayer.data.library

import com.seedds.vplayer.data.fs.LibraryErrors
import com.seedds.vplayer.data.fs.LibraryException
import com.seedds.vplayer.data.fs.LibraryItemComparator
import com.seedds.vplayer.data.fs.LibraryNames
import com.seedds.vplayer.data.fs.LibraryPaths
import com.seedds.vplayer.data.fs.NaturalOrderComparator
import com.seedds.vplayer.data.model.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The library as the rest of the app sees it: a tree of folders and files under
 * the app's private `videos/` directory.
 *
 * Every mutation refuses rather than guesses. A name collision is an error
 * instead of an automatic `(1)` suffix, and a rename may not turn a playable
 * file into an unplayable one, because silently renaming or reclassifying a
 * user's file is worse than telling them it did not work.
 */
class LibraryRepository(private val paths: LibraryPaths) {

    /** Entries directly inside [relativePath], folders first then natural order. */
    suspend fun list(relativePath: String?): List<LibraryItem> = withContext(Dispatchers.IO) {
        paths.ensureDirectories()
        val directory = paths.fileFor(LibraryNames.normalizeDirectoryPath(relativePath))
        val children = directory.listFiles().orEmpty()
        children
            .mapNotNull { child -> buildItem(child) }
            .sortedWith(LibraryItemComparator)
    }

    /** Every video anywhere in the library, ordered by path. */
    suspend fun listAllVideos(): List<LibraryItem.Video> = withContext(Dispatchers.IO) {
        paths.ensureDirectories()
        val videos = mutableListOf<LibraryItem.Video>()
        val pending = ArrayDeque<File>()
        pending.addLast(paths.videosDir)
        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            for (child in directory.listFiles().orEmpty()) {
                if (child.isDirectory) {
                    pending.addLast(child)
                } else {
                    (buildItem(child) as? LibraryItem.Video)?.let(videos::add)
                }
            }
        }
        videos.sortedWith(compareBy(NaturalOrderComparator) { it.relativePath })
    }

    /**
     * Resolves an existing item. Unlike listing, the path is validated rather
     * than rewritten: it must already match what is on disk, and a traversal
     * segment is refused outright.
     */
    suspend fun getItem(relativePath: String?): LibraryItem? = withContext(Dispatchers.IO) {
        val segments = LibraryNames.toLookupPath(relativePath) ?: return@withContext null
        buildItem(paths.fileFor(segments.joinToString("/")))
    }

    private suspend fun requireItem(relativePath: String?): LibraryItem =
        getItem(relativePath) ?: throw LibraryException(LibraryErrors.ITEM_NOT_FOUND)

    suspend fun createFolder(parentPath: String?, name: String): LibraryItem.Folder =
        withContext(Dispatchers.IO) {
            paths.ensureDirectories()
            val parent = LibraryNames.normalizeDirectoryPath(parentPath)
            val folderName = LibraryNames.sanitizeFolderName(name)
            val relativePath = LibraryNames.joinPath(parent.ifEmpty { null }, folderName)
            val target = paths.fileFor(relativePath)

            if (target.exists()) throw LibraryException(LibraryErrors.NAME_TAKEN)
            target.mkdirs()

            buildItem(target) as? LibraryItem.Folder
                ?: throw LibraryException(LibraryErrors.COULD_NOT_CREATE_FOLDER)
        }

    /**
     * Renames one item in place. Returns the item unchanged when the sanitised
     * name resolves to where it already is, so re-confirming the same name is
     * not an error.
     */
    suspend fun rename(relativePath: String, name: String): LibraryItem {
        val target = requireItem(relativePath)
        return withContext(Dispatchers.IO) {
            val isFolder = target is LibraryItem.Folder
            val nextName = if (isFolder) {
                LibraryNames.sanitizeFolderName(name)
            } else {
                LibraryNames.sanitizeFileName(name)
            }

            if (!isFolder && !LibraryNames.renamePreservesKind(target.name, nextName)) {
                throw LibraryException(LibraryErrors.keepExtension(LibraryNames.extensionOf(target.name)))
            }

            val nextRelativePath = LibraryNames.joinPath(target.parentPath, nextName)
            if (nextRelativePath == target.relativePath) return@withContext target

            val destination = paths.fileFor(nextRelativePath)
            if (destination.exists()) throw LibraryException(LibraryErrors.NAME_TAKEN)
            if (!paths.fileFor(target.relativePath).renameTo(destination)) {
                throw LibraryException(LibraryErrors.COULD_NOT_RENAME)
            }

            buildItem(destination) ?: throw LibraryException(LibraryErrors.COULD_NOT_RENAME)
        }
    }

    /**
     * Moves one item into [destinationPath]. Moving into the current folder is
     * a no-op rather than an error, which keeps bulk moves forgiving when the
     * selection spans items that are already in place.
     */
    suspend fun move(relativePath: String, destinationPath: String?): LibraryItem {
        val target = requireItem(relativePath)
        return withContext(Dispatchers.IO) {
            val destination = LibraryNames.normalizeDirectoryPath(destinationPath)

            if (target is LibraryItem.Folder &&
                (destination == target.relativePath || destination.startsWith("${target.relativePath}/"))
            ) {
                throw LibraryException(LibraryErrors.FOLDER_INTO_ITSELF)
            }

            val destinationDir = paths.fileFor(destination)
            if (!destinationDir.isDirectory) throw LibraryException(LibraryErrors.DESTINATION_NOT_FOUND)

            if ((target.parentPath ?: "") == destination) return@withContext target

            val nextRelativePath = LibraryNames.joinPath(destination.ifEmpty { null }, target.name)
            val nextFile = paths.fileFor(nextRelativePath)
            if (nextFile.exists()) throw LibraryException(LibraryErrors.NAME_TAKEN_IN_DESTINATION)
            if (!paths.fileFor(target.relativePath).renameTo(nextFile)) {
                throw LibraryException(LibraryErrors.COULD_NOT_MOVE)
            }

            buildItem(nextFile) ?: throw LibraryException(LibraryErrors.COULD_NOT_MOVE)
        }
    }

    /** Deletes an item, recursively for a folder. Already-gone is success. */
    suspend fun delete(relativePath: String) {
        val target = requireItem(relativePath)
        withContext(Dispatchers.IO) {
            paths.fileFor(target.relativePath).deleteRecursively()
        }
    }

    /**
     * Every video an item covers: the video itself, all videos beneath a
     * folder, or nothing for a subtitle or other file. Used to clean up
     * playback progress and thumbnails before the files disappear.
     */
    suspend fun collectVideos(item: LibraryItem): List<LibraryItem.Video> = when (item) {
        is LibraryItem.Video -> listOf(item)
        is LibraryItem.Folder -> withContext(Dispatchers.IO) {
            val videos = mutableListOf<LibraryItem.Video>()
            val pending = ArrayDeque<File>()
            pending.addLast(paths.fileFor(item.relativePath))
            while (pending.isNotEmpty()) {
                val directory = pending.removeLast()
                for (child in directory.listFiles().orEmpty()) {
                    if (child.isDirectory) {
                        pending.addLast(child)
                    } else {
                        (buildItem(child) as? LibraryItem.Video)?.let(videos::add)
                    }
                }
            }
            videos
        }
        else -> emptyList()
    }

    /**
     * The subtitle that goes with [video]: a sibling `.srt` whose name before
     * the extension matches, compared case-insensitively. Only siblings are
     * considered, so unrelated files with the same name elsewhere never pair up.
     */
    suspend fun findMatchingSubtitle(video: LibraryItem.Video): LibraryItem.Subtitle? =
        withContext(Dispatchers.IO) {
            val baseName = LibraryNames.baseNameOf(video.name).lowercase()
            list(video.parentPath)
                .filterIsInstance<LibraryItem.Subtitle>()
                .firstOrNull { LibraryNames.baseNameOf(it.name).lowercase() == baseName }
        }

    /**
     * The nearest folder at or above [relativePath] that still exists. Deleting
     * the folder you are standing in walks you up rather than showing an empty
     * screen for a path that is gone.
     */
    suspend fun nearestExistingFolder(relativePath: String?): String? = withContext(Dispatchers.IO) {
        var candidate: String? = relativePath?.takeIf(String::isNotEmpty)
        while (candidate != null) {
            if (paths.fileFor(candidate).isDirectory) return@withContext candidate
            candidate = LibraryNames.parentPathOf(candidate)
        }
        null
    }

    private fun buildItem(file: File): LibraryItem? {
        if (!file.exists()) return null
        val relativePath = paths.relativePathOf(file)?.takeIf(String::isNotEmpty) ?: return null
        val name = file.name
        val parentPath = LibraryNames.parentPathOf(relativePath)
        // 0 rather than "now" when the filesystem has no timestamp: this value
        // is part of the thumbnail and probe cache keys, so inventing one would
        // invalidate the cache on every listing.
        val modified = file.lastModified().takeIf { it > 0L } ?: 0L

        if (file.isDirectory) {
            return LibraryItem.Folder(
                name = name,
                relativePath = relativePath,
                parentPath = parentPath,
                modified = modified,
            )
        }

        val size = file.length()
        val extension = LibraryNames.extensionOf(name)
        return when {
            LibraryNames.isSubtitleFileName(name) ->
                LibraryItem.Subtitle(name, relativePath, parentPath, modified, size, extension)
            LibraryNames.isVideoFileName(name) ->
                LibraryItem.Video(name, relativePath, parentPath, modified, size, extension)
            else ->
                LibraryItem.File(name, relativePath, parentPath, modified, size, extension)
        }
    }
}
