package com.seedds.vplayer.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.seedds.vplayer.data.fs.formatDuration
import com.seedds.vplayer.data.model.LibraryItem
import com.seedds.vplayer.data.store.PlaybackEntry
import com.seedds.vplayer.ui.theme.VColors

/**
 * One library entry. The row is deliberately flat: a thumbnail, the name, one
 * line of context and a progress badge, so a long list stays scannable.
 */
@Composable
fun LibraryRow(
    item: LibraryItem,
    playback: PlaybackEntry?,
    thumbnailPath: String?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val background = when {
        selected -> VColors.RowSelected
        pressed -> VColors.RowPressed
        else -> VColors.Surface
    }

    Column(modifier = modifier.fillMaxWidth().background(background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThumbnailSlot(item = item, thumbnailPath = thumbnailPath)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.name,
                        color = VColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaLine(item, playback),
                        color = VColors.TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(modifier = Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                if (item is LibraryItem.Video) {
                    PlaybackBadge(
                        hasStarted = playback?.hasStartedPlayback == true,
                        progress = playbackProgress(playback),
                    )
                }
            }

            if (selectionMode) {
                Box(modifier = Modifier.width(78.dp), contentAlignment = Alignment.CenterEnd) {
                    SelectionIndicator(selected = selected)
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = VColors.DividerRow)
    }
}

/** The context line under a name: progress for a video, a label otherwise. */
private fun metaLine(item: LibraryItem, playback: PlaybackEntry?): String = when (item) {
    is LibraryItem.Folder -> "Folder"
    is LibraryItem.Subtitle -> "Subtitle file"
    is LibraryItem.File -> "File cannot be played"
    is LibraryItem.Video -> {
        val position = playback?.positionSeconds
        val duration = playback?.durationSeconds
        "${formatDuration(position)} / ${formatDuration(duration)}"
    }
}

private fun playbackProgress(playback: PlaybackEntry?): Float {
    val duration = playback?.durationSeconds ?: return 0f
    if (duration <= 0.0) return 0f
    return (playback.positionSeconds / duration).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun ThumbnailSlot(item: LibraryItem, thumbnailPath: String?) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 46.dp)
            .clip(shape)
            .background(
                when {
                    item is LibraryItem.Folder -> VColors.FolderThumbBackground
                    thumbnailPath != null -> VColors.ThumbBackground
                    else -> VColors.Primary
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            item is LibraryItem.Folder -> FolderGlyph()
            thumbnailPath != null -> AsyncImage(
                model = thumbnailPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text(
                text = when (item) {
                    is LibraryItem.Video -> "Video"
                    is LibraryItem.Subtitle -> "SRT"
                    else -> "File"
                },
                color = VColors.OnPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FolderGlyph() {
    Canvas(modifier = Modifier.size(width = 52.dp, height = 36.dp)) {
        val strokeWidth = 2.2f * density
        val width = size.width
        val height = size.height
        val inset = strokeWidth
        val bodyTop = height * 0.3f
        val tabWidth = width * 0.42f
        val radius = 4f * density

        val folder = Path().apply {
            moveTo(inset, bodyTop)
            lineTo(inset, height - inset - radius)
            quadraticTo(inset, height - inset, inset + radius, height - inset)
            lineTo(width - inset - radius, height - inset)
            quadraticTo(width - inset, height - inset, width - inset, height - inset - radius)
            lineTo(width - inset, bodyTop)
            close()
        }
        val tab = Path().apply {
            moveTo(inset, bodyTop)
            lineTo(inset, inset)
            lineTo(inset + tabWidth, inset)
            lineTo(inset + tabWidth + height * 0.18f, bodyTop)
            close()
        }

        drawPath(tab, color = VColors.FolderIconFill)
        drawPath(tab, color = VColors.FolderIconStroke, style = Stroke(width = strokeWidth))
        drawPath(folder, color = VColors.FolderIconFill)
        drawPath(folder, color = VColors.FolderIconStroke, style = Stroke(width = strokeWidth))
    }
}
