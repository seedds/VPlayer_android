package com.seedds.vplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.data.fs.LibraryNames
import com.seedds.vplayer.data.library.LibraryRepository
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.ui.components.ButtonTone
import com.seedds.vplayer.ui.components.VButton
import com.seedds.vplayer.ui.theme.VColors

/**
 * Folder browser for choosing a move destination.
 *
 * Destinations that cannot work are disabled with the reason spelled out
 * underneath, rather than allowed and then refused: the user finds out before
 * committing instead of after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    repository: LibraryRepository,
    movingItems: List<LibraryItem>,
    onDismiss: () -> Unit,
    onMoveHere: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var browsePath by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<LibraryItem.Folder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(browsePath) {
        loading = true
        folders = repository.list(browsePath).filterIsInstance<LibraryItem.Folder>()
        loading = false
    }

    val sourceParentPath = movingItems.firstOrNull()?.parentPath
    val movedFolderPaths = movingItems.filterIsInstance<LibraryItem.Folder>().map(LibraryItem.Folder::relativePath)

    fun insideMovedSubtree(path: String?): Boolean {
        val candidate = path ?: return false
        return movedFolderPaths.any { moved -> candidate == moved || candidate.startsWith("$moved/") }
    }

    val currentIsSource = browsePath == sourceParentPath
    val currentIsInsideMoved = insideMovedSubtree(browsePath)
    val canMoveHere = !currentIsSource && !currentIsInsideMoved
    val browseFolderName = browsePath?.substringAfterLast('/')

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VColors.Background,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VButton("Cancel", onDismiss, cornerRadius = 12.dp, fontSize = 14.sp)
                Text(
                    text = browseFolderName ?: "Library",
                    modifier = Modifier.weight(1f),
                    color = VColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VButton(
                    label = "Move Here",
                    onClick = { onMoveHere(browsePath) },
                    tone = ButtonTone.Primary,
                    enabled = canMoveHere,
                    cornerRadius = 12.dp,
                    fontSize = 14.sp,
                    modifier = Modifier.alpha(if (canMoveHere) 1f else 0.4f),
                )
            }
            HorizontalDivider(thickness = 1.dp, color = VColors.DividerTabBar)

            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (browsePath != null) {
                    PickerRow(
                        leading = "←",
                        label = "..",
                        enabled = true,
                        onClick = { browsePath = LibraryNames.parentPathOf(browsePath) },
                    )
                }

                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = VColors.Primary)
                    }

                    folders.isEmpty() -> Text(
                        text = "No folders here.",
                        color = VColors.TextMutedAlt,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )

                    else -> folders.forEach { folder ->
                        PickerRow(
                            leading = "📁",
                            label = folder.name,
                            trailing = "›",
                            enabled = !insideMovedSubtree(folder.relativePath),
                            onClick = { browsePath = folder.relativePath },
                        )
                    }
                }
            }

            Text(
                text = when {
                    currentIsSource -> "Items are already in this folder."
                    currentIsInsideMoved -> "A folder cannot be moved into itself."
                    else -> "Move here into \"${browseFolderName ?: "Library"}\"."
                },
                color = VColors.TextMutedAlt,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PickerRow(
    leading: String,
    label: String,
    enabled: Boolean,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = leading, fontSize = 16.sp, color = VColors.TextSecondary)
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = VColors.TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Text(text = trailing, color = VColors.Placeholder, fontSize = 20.sp)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = VColors.DividerPicker)
    }
}
