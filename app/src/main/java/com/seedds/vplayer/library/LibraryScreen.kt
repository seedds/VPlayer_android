package com.seedds.vplayer.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.ui.components.ButtonTone
import com.seedds.vplayer.ui.components.ConfirmDialog
import com.seedds.vplayer.ui.components.EmptyState
import com.seedds.vplayer.ui.components.LoadingCard
import com.seedds.vplayer.ui.components.MessageDialog
import com.seedds.vplayer.ui.components.TextPromptDialog
import com.seedds.vplayer.ui.components.VButton
import com.seedds.vplayer.ui.components.renameSelection
import com.seedds.vplayer.ui.theme.VColors

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPlayVideo: (LibraryItem.Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // At most one row shows its actions at a time, and scrolling puts it away:
    // an open row left behind while the user moves on is just a trap.
    var openRowPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState.isScrollInProgress, state.selectionMode) {
        if (listState.isScrollInProgress || state.selectionMode) openRowPath = null
    }

    // System back steps out the way the toolbar does: leave selection first,
    // then climb a folder. Only at the root does it leave the app.
    BackHandler(enabled = state.selectionMode || state.currentFolderPath != null) {
        if (state.selectionMode) viewModel.cancelSelection() else viewModel.navigateUp()
    }

    Column(modifier = modifier.fillMaxSize().background(VColors.Background)) {
        if (state.loading) {
            LoadingCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            return@Column
        }

        LibraryToolbar(
            selectedCount = state.selectedPaths.size,
            selectionMode = state.selectionMode,
            allSelected = state.allSelected,
            insideFolder = state.currentFolderPath != null,
            onNavigateUp = viewModel::navigateUp,
            onNewFolder = { viewModel.showDialog(LibraryDialog.NewFolder) },
            onClearAllHistory = viewModel::confirmClearAllHistory,
            onCancelSelection = viewModel::cancelSelection,
            onToggleSelectAll = viewModel::toggleSelectAll,
            onMove = { viewModel.showDialog(LibraryDialog.Move) },
            onClearSelectionHistory = viewModel::confirmClearSelectionHistory,
            onDeleteSelection = viewModel::confirmDeleteSelection,
        )

        if (state.items.isEmpty()) {
            val atRoot = state.currentFolderPath == null
            EmptyState(
                title = if (atRoot) "No media yet" else "This folder is empty",
                body = if (atRoot) {
                    "Use the Upload tab at the bottom, open the device URL on your computer, and send a file here."
                } else {
                    "Use the Upload tab to add files here, or go up to another folder."
                },
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        ) {
            itemsIndexed(items = state.items, key = { _, item -> item.relativePath }) { index, item ->
                SwipeRevealRow(
                    isOpen = openRowPath == item.relativePath,
                    onOpenChange = { open -> openRowPath = if (open) item.relativePath else null },
                    enabled = !state.selectionMode,
                    actions = {
                        SwipeAction(
                            label = "Rename",
                            background = VColors.Primary,
                            pressedBackground = VColors.PrimaryPressed,
                            textColor = VColors.OnSwipeRename,
                            modifier = Modifier.weight(1f),
                        ) {
                            openRowPath = null
                            viewModel.showDialog(LibraryDialog.Rename(item))
                        }
                        SwipeAction(
                            label = "Delete",
                            background = VColors.SwipeDelete,
                            pressedBackground = VColors.SwipeDeletePressed,
                            textColor = VColors.OnSwipeDelete,
                            modifier = Modifier.weight(1f),
                        ) {
                            openRowPath = null
                            viewModel.confirmDeleteItem(item)
                        }
                    },
                ) {
                    LibraryRow(
                        item = item,
                        playback = state.playbackState[item.relativePath],
                        thumbnailPath = state.thumbnails[item.relativePath],
                        selected = item.relativePath in state.selectedPaths,
                        selectionMode = state.selectionMode,
                        onClick = {
                            when {
                                state.selectionMode -> viewModel.toggleSelection(item)
                                item is LibraryItem.Folder -> viewModel.openFolder(item.relativePath)
                                item is LibraryItem.Video -> onPlayVideo(item)
                                else -> Unit
                            }
                        },
                        onLongClick = { viewModel.longPress(item, index) },
                    )
                }
            }
        }
    }

    LibraryDialogHost(state = state, viewModel = viewModel)
}

@Composable
private fun LibraryToolbar(
    selectedCount: Int,
    selectionMode: Boolean,
    allSelected: Boolean,
    insideFolder: Boolean,
    onNavigateUp: () -> Unit,
    onNewFolder: () -> Unit,
    onClearAllHistory: () -> Unit,
    onCancelSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onMove: () -> Unit,
    onClearSelectionHistory: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when {
            selectionMode -> Text(
                text = "$selectedCount selected",
                color = VColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            insideFolder -> Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VColors.ButtonSecondary)
                    .clickable(onClick = onNavigateUp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "←",
                    color = VColors.TextSecondary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            else -> Box(Modifier)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                VButton("Cancel", onCancelSelection, verticalPadding = 10.dp)
                VButton(if (allSelected) "Deselect All" else "Select All", onToggleSelectAll, verticalPadding = 10.dp)
                VButton("Move", onMove, verticalPadding = 10.dp)
                VButton("Clear History", onClearSelectionHistory, verticalPadding = 10.dp)
                VButton("Delete", onDeleteSelection, tone = ButtonTone.Danger, verticalPadding = 10.dp)
            } else {
                VButton("New Folder", onNewFolder, horizontalPadding = 12.dp, verticalPadding = 8.dp)
                VButton("Clear All History", onClearAllHistory, horizontalPadding = 12.dp, verticalPadding = 8.dp)
            }
        }
    }
}

@Composable
private fun SwipeAction(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    pressedBackground: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (pressed) pressedBackground else background)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun LibraryDialogHost(state: LibraryUiState, viewModel: LibraryViewModel) {
    when (val dialog = state.dialog) {
        null -> Unit

        LibraryDialog.NewFolder -> TextPromptDialog(
            title = "New folder",
            confirmLabel = "Create",
            placeholder = "Folder name",
            onDismiss = viewModel::dismissDialog,
            onConfirm = viewModel::createFolder,
        )

        is LibraryDialog.Rename -> TextPromptDialog(
            title = "Rename",
            confirmLabel = "Rename",
            initialValue = dialog.item.name,
            initialSelection = renameSelection(dialog.item.name),
            onDismiss = viewModel::dismissDialog,
            onConfirm = { name -> viewModel.rename(dialog.item, name) },
        )

        LibraryDialog.Move -> FolderPickerSheet(
            repository = viewModel.repositoryForPicker,
            movingItems = state.items.filter { it.relativePath in state.selectedPaths },
            onDismiss = viewModel::dismissDialog,
            onMoveHere = viewModel::moveSelection,
        )

        is LibraryDialog.Confirm -> ConfirmDialog(
            title = dialog.title,
            message = dialog.message,
            confirmLabel = dialog.confirmLabel,
            onDismiss = viewModel::dismissDialog,
            onConfirm = dialog.onConfirm,
        )

        is LibraryDialog.Message -> MessageDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = viewModel::dismissDialog,
        )
    }
}
