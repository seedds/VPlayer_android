package com.seedds.vplayer.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.data.fs.formatDuration
import com.seedds.vplayer.ui.theme.VColors

/**
 * The scrub bar: a full-width strip that is entirely touchable.
 *
 * There is no thumb to hit. Touching anywhere jumps the playhead to that point,
 * which is far easier to aim at on a phone held in one hand than a small handle
 * would be, and the whole strip doubles as the progress fill.
 */
@Composable
fun SeekStrip(
    progress: Float,
    elapsedSeconds: Double,
    remainingSeconds: Double,
    fileName: String,
    durationSeconds: Double,
    onScrubStart: (Double) -> Unit,
    onScrubUpdate: (Double) -> Unit,
    onScrubCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val currentDuration by rememberUpdatedState(durationSeconds)

    fun timeAt(x: Float): Double {
        val fraction = (x / widthPx.toFloat()).coerceIn(0f, 1f)
        return fraction * currentDuration.coerceAtLeast(0.0)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            // The strip runs edge to edge, so with gesture navigation a scrub
            // starting at the left end would otherwise be taken as Back.
            .systemGestureExclusion()
            .background(VColors.Player.SeekTrack)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                // As spec B4 has it: the playhead jumps to the finger on
                // touch-down, follows it, and the seek commits on release. A
                // tap is a scrub that never moved.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var x = down.position.x
                    onScrubStart(timeAt(x))
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            x = change.position.x
                            onScrubUpdate(timeAt(x))
                        }
                        change.consume()
                    }
                    onScrubCommit(timeAt(x))
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(VColors.Player.ProgressFill),
        )

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                text = formatDuration(elapsedSeconds),
                modifier = Modifier.align(Alignment.CenterStart),
                color = VColors.Player.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = fileName,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 78.dp),
                color = VColors.Player.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "-${formatDuration(remainingSeconds)}",
                modifier = Modifier.align(Alignment.CenterEnd),
                color = VColors.Player.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The frame preview that follows the finger while scrubbing. */
@Composable
fun FramePreview(
    frame: android.graphics.Bitmap?,
    progress: Float,
    stripWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    if (frame == null) return
    val density = LocalDensity.current
    val widthPx = with(density) { PREVIEW_WIDTH.toPx() }
    // Centred on the playhead, but never hanging off either end of the strip.
    val left = (progress * stripWidthPx - widthPx / 2f)
        .coerceIn(0f, (stripWidthPx - widthPx).coerceAtLeast(0f))

    Box(
        modifier = modifier
            .offset { IntOffset(left.toInt(), 0) }
            .size(width = PREVIEW_WIDTH, height = PREVIEW_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(VColors.Player.Popup)
            .border(1.dp, VColors.Player.PopupBorder, RoundedCornerShape(12.dp)),
    ) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

val STRIP_HEIGHT = 44.dp
val PREVIEW_WIDTH = 160.dp
val PREVIEW_HEIGHT = 90.dp
