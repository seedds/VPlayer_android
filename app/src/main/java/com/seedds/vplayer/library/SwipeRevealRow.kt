package com.seedds.vplayer.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A row that slides left to reveal actions underneath it.
 *
 * Openness is hoisted rather than kept here so the list can guarantee only one
 * row is ever open, and can close it when the user starts scrolling. Horizontal
 * drags are claimed only after the pointer has clearly moved sideways, which
 * leaves vertical scrolling and long press on the row content untouched.
 */
@Composable
fun SwipeRevealRow(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    actionsWidth: Dp = 176.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionsWidthPx = with(density) { actionsWidth.toPx() }
    val openThresholdPx = with(density) { OPEN_THRESHOLD.toPx() }

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    var rowHeight by remember { mutableIntStateOf(0) }

    // The parent decides what is open; follow it whenever that changes, and
    // snap shut the moment swiping is disabled (for instance when a selection
    // starts) so a half-open row cannot be left stranded.
    LaunchedEffect(isOpen, enabled, actionsWidthPx) {
        val target = if (isOpen && enabled) -actionsWidthPx else 0f
        if (offsetX.value != target) {
            offsetX.animateTo(target, tween(durationMillis = 180))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (offsetX.value < 0f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(actionsWidth)
                    .then(
                        if (rowHeight > 0) {
                            Modifier.height(with(density) { rowHeight.toDp() })
                        } else {
                            Modifier.fillMaxHeight()
                        },
                    ),
                content = actions,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeight = it.height }
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(enabled, actionsWidthPx) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val next = (offsetX.value + dragAmount).coerceIn(-actionsWidthPx, 0f)
                                offsetX.snapTo(next)
                            }
                        },
                        onDragEnd = {
                            val shouldOpen = offsetX.value <= -openThresholdPx
                            currentOnOpenChange(shouldOpen)
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = if (shouldOpen) -actionsWidthPx else 0f,
                                    animationSpec = tween(durationMillis = 180),
                                )
                            }
                        },
                        onDragCancel = {
                            currentOnOpenChange(false)
                            scope.launch { offsetX.animateTo(0f, tween(durationMillis = 180)) }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

/** How far the row must travel before releasing leaves it open. */
private val OPEN_THRESHOLD = 56.dp
