package com.seedds.vplayer.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seedds.vplayer.app.AppContainer
import com.seedds.vplayer.data.fs.LibraryNames
import com.seedds.vplayer.data.library.LibraryArtifacts
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.store.PlaybackEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A modal the library screen is currently showing. */
sealed interface LibraryDialog {
    data object NewFolder : LibraryDialog
    data class Rename(val item: LibraryItem) : LibraryDialog
    data object Move : LibraryDialog
    data class Confirm(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val onConfirm: () -> Unit,
    ) : LibraryDialog
    data class Message(val title: String, val message: String) : LibraryDialog
}

data class LibraryUiState(
    val loading: Boolean = true,
    val currentFolderPath: String? = null,
    val items: List<LibraryItem> = emptyList(),
    val playbackState: Map<String, PlaybackEntry> = emptyMap(),
    /** Library-relative video path to the absolute path of its cached thumbnail. */
    val thumbnails: Map<String, String> = emptyMap(),
    val selectedPaths: Set<String> = emptySet(),
    val dialog: LibraryDialog? = null,
) {
    val selectionMode: Boolean get() = selectedPaths.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedPaths.size == items.size
    val videos: List<LibraryItem.Video> get() = items.filterIsInstance<LibraryItem.Video>()
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.libraryRepository

    /** The folder picker browses the library directly while it is open. */
    val repositoryForPicker get() = repository
    private val playbackStateStore = container.playbackStateStore
    private val artifacts = LibraryArtifacts(playbackStateStore, container.thumbnailCache)

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /**
     * Where a range selection started. A range extends from here rather than
     * from the previously touched row, so repeated long presses keep growing
     * one block instead of chaining into a new one each time.
     */
    private var anchorIndex: Int? = null

    init {
        refresh()
    }

    fun refresh(path: String? = _state.value.currentFolderPath) {
        viewModelScope.launch {
            // A folder can disappear while it is on screen, from a delete in
            // the browser or on another tab. Walk up to the nearest folder that
            // still exists rather than showing an empty screen for a dead path.
            val target = repository.nearestExistingFolder(path)
            val items = repository.list(target)
            val playback = playbackStateStore.all()
            val thumbnails = container.thumbnailCache
                .cachedAll(items.filterIsInstance<LibraryItem.Video>())
                .mapValues { (_, file) -> file.absolutePath }

            _state.update { current ->
                val present = items.mapTo(mutableSetOf(), LibraryItem::relativePath)
                val selection = current.selectedPaths.intersect(present)
                if (selection.isEmpty()) anchorIndex = null
                current.copy(
                    loading = false,
                    currentFolderPath = target,
                    items = items,
                    playbackState = playback,
                    thumbnails = current.thumbnails + thumbnails,
                    selectedPaths = selection,
                )
            }
        }
    }

    fun openFolder(relativePath: String) {
        cancelSelection()
        refresh(relativePath)
    }

    fun navigateUp() {
        cancelSelection()
        refresh(LibraryNames.parentPathOf(_state.value.currentFolderPath))
    }

    // ---------------------------------------------------------------- selection

    fun toggleSelection(item: LibraryItem) {
        _state.update { current ->
            val selection = current.selectedPaths.toMutableSet()
            if (!selection.remove(item.relativePath)) selection.add(item.relativePath)
            if (selection.isEmpty()) anchorIndex = null
            current.copy(selectedPaths = selection)
        }
    }

    /**
     * Long press either starts a selection or extends one. Extending only ever
     * adds, so dragging back over an already selected row cannot silently
     * deselect what the user just picked.
     */
    fun longPress(item: LibraryItem, index: Int) {
        val current = _state.value
        if (!current.selectionMode) {
            anchorIndex = index
            _state.update { it.copy(selectedPaths = setOf(item.relativePath)) }
            return
        }

        val anchor = anchorIndex
        if (anchor == null || anchor >= current.items.size) {
            anchorIndex = index
            toggleSelection(item)
            return
        }

        val range = minOf(anchor, index)..maxOf(anchor, index)
        val added = current.items.slice(range).map(LibraryItem::relativePath)
        _state.update { it.copy(selectedPaths = it.selectedPaths + added) }
    }

    fun selectAll() {
        _state.update { it.copy(selectedPaths = it.items.mapTo(mutableSetOf(), LibraryItem::relativePath)) }
    }

    fun cancelSelection() {
        anchorIndex = null
        _state.update { it.copy(selectedPaths = emptySet()) }
    }

    fun toggleSelectAll() {
        if (_state.value.allSelected) cancelSelection() else selectAll()
    }

    private fun selectedItems(): List<LibraryItem> {
        val current = _state.value
        return current.items.filter { it.relativePath in current.selectedPaths }
    }

    // ----------------------------------------------------------------- dialogs

    fun showDialog(dialog: LibraryDialog) = _state.update { it.copy(dialog = dialog) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    private fun fail(title: String, error: Throwable, fallback: String) {
        showDialog(LibraryDialog.Message(title, error.message?.takeIf(String::isNotBlank) ?: fallback))
    }

    // ---------------------------------------------------------------- mutations

    fun createFolder(name: String) {
        dismissDialog()
        viewModelScope.launch {
            runCatching { repository.createFolder(_state.value.currentFolderPath, name) }
                .onSuccess { refresh() }
                .onFailure { fail("New folder failed", it, "Could not create the folder.") }
        }
    }

    fun rename(item: LibraryItem, name: String) {
        dismissDialog()
        viewModelScope.launch {
            runCatching {
                val videos = repository.collectVideos(item)
                val renamed = repository.rename(item.relativePath, name)
                val moves = artifacts.relink(videos, item.relativePath, renamed.relativePath)
                applyThumbnailMoves(moves)
            }
                .onSuccess { refresh() }
                .onFailure { fail("Rename failed", it, "Could not rename the item.") }
        }
    }

    fun moveSelection(destinationPath: String?) {
        dismissDialog()
        val targets = selectedItems()
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            // Each item is attempted independently: one refusal, such as a name
            // already taken in the destination, should not strand the rest.
            targets.forEach { item ->
                runCatching {
                    val videos = repository.collectVideos(item)
                    val moved = repository.move(item.relativePath, destinationPath)
                    val moves = artifacts.relink(videos, item.relativePath, moved.relativePath)
                    applyThumbnailMoves(moves)
                }.onFailure { error ->
                    failures += "${item.name}: ${error.message?.takeIf(String::isNotBlank) ?: "move failed"}"
                }
            }
            cancelSelection()
            refresh()
            if (failures.isNotEmpty()) {
                showDialog(LibraryDialog.Message("Some items could not be moved", failures.joinToString("\n")))
            }
        }
    }

    fun confirmDeleteItem(item: LibraryItem) {
        showDialog(
            LibraryDialog.Confirm(
                title = if (item is LibraryItem.Folder) "Delete folder?" else "Delete file?",
                message = item.name,
                confirmLabel = "Delete",
            ) { deleteItems(listOf(item), "Could not delete the file.") },
        )
    }

    fun confirmDeleteSelection() {
        val targets = selectedItems()
        val count = targets.size
        showDialog(
            LibraryDialog.Confirm(
                title = "Delete selected items?",
                message = "$count item${if (count == 1) "" else "s"} will be removed.",
                confirmLabel = "Delete",
            ) { deleteItems(targets, "Could not delete the selected files.") },
        )
    }

    private fun deleteItems(items: List<LibraryItem>, failureMessage: String) {
        dismissDialog()
        viewModelScope.launch {
            runCatching {
                // Collect first: once the files are gone there is nothing left
                // to enumerate, and their progress and thumbnails would leak.
                val videos = items.flatMap { repository.collectVideos(it) }
                artifacts.forget(videos)
                forgetThumbnails(videos.map(LibraryItem.Video::relativePath))
                items.forEach { repository.delete(it.relativePath) }
            }
                .onSuccess {
                    cancelSelection()
                    refresh()
                }
                .onFailure { fail("Delete failed", it, failureMessage) }
        }
    }

    fun confirmClearSelectionHistory() {
        val targets = selectedItems()
        showDialog(
            LibraryDialog.Confirm(
                title = "Clear playback history?",
                message = "Saved playback positions will be reset for videos inside the selected items.",
                confirmLabel = "Clear",
            ) {
                dismissDialog()
                viewModelScope.launch {
                    runCatching {
                        val videos = targets.flatMap { repository.collectVideos(it) }
                        playbackStateStore.clearFor(videos.map(LibraryItem.Video::relativePath))
                    }
                        .onSuccess {
                            cancelSelection()
                            refresh()
                        }
                        .onFailure {
                            fail("Clear failed", it, "Could not clear playback history for the selected files.")
                        }
                }
            },
        )
    }

    fun confirmClearAllHistory() {
        showDialog(
            LibraryDialog.Confirm(
                title = "Clear playback history?",
                message = "This resets all saved playback positions and marks every video as new.",
                confirmLabel = "Clear",
            ) {
                dismissDialog()
                viewModelScope.launch {
                    runCatching { playbackStateStore.clearAll() }
                        .onSuccess { refresh() }
                        .onFailure { fail("Clear failed", it, "Could not clear playback history.") }
                }
            },
        )
    }

    private fun applyThumbnailMoves(moves: List<Pair<String, String>>) {
        if (moves.isEmpty()) return
        _state.update { current ->
            val thumbnails = current.thumbnails.toMutableMap()
            moves.forEach { (from, to) -> thumbnails.remove(from)?.let { thumbnails[to] = it } }
            current.copy(thumbnails = thumbnails)
        }
    }

    private fun forgetThumbnails(paths: List<String>) {
        if (paths.isEmpty()) return
        _state.update { it.copy(thumbnails = it.thumbnails - paths.toSet()) }
    }
}
