package com.seedds.vplayer.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.ui.theme.VColors

/**
 * How far through a video the user is, drawn as a pie.
 *
 * A video that has never been opened shows `[new]` instead, so "untouched" and
 * "barely started" are not both a nearly empty circle.
 */
@Composable
fun PlaybackBadge(hasStarted: Boolean, progress: Float, modifier: Modifier = Modifier) {
    if (!hasStarted) {
        Text(
            text = "[new]",
            color = VColors.Primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.size(18.dp)) {
        Canvas(Modifier.size(18.dp)) {
            val diameter = size.minDimension
            val radius = diameter / 2f
            val center = Offset(radius, radius)
            val fraction = progress.coerceIn(0f, 1f)

            drawCircle(color = VColors.BadgeBase, radius = radius, center = center)

            when {
                fraction >= 1f -> drawCircle(color = VColors.Primary, radius = radius, center = center)
                fraction > 0f -> {
                    // Sweep clockwise from twelve o'clock, which is how a
                    // person reads a progress dial.
                    val wedge = Path().apply {
                        moveTo(center.x, center.y)
                        arcTo(
                            rect = androidx.compose.ui.geometry.Rect(Offset.Zero, Size(diameter, diameter)),
                            startAngleDegrees = -90f,
                            sweepAngleDegrees = 360f * fraction,
                            forceMoveTo = false,
                        )
                        close()
                    }
                    drawPath(wedge, color = VColors.Primary)
                }
            }

            drawCircle(
                color = VColors.BadgeOutline,
                radius = radius - 0.625f,
                center = center,
                style = Stroke(width = 1.25f),
            )
        }
    }
}

/** Checkbox-style indicator shown on every row while a selection is active. */
@Composable
fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 30.dp)
            .clip(shape)
            .background(if (selected) VColors.Primary else VColors.Surface)
            .border(2.dp, if (selected) VColors.Primary else VColors.SelectionBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(text = "\u2713", color = VColors.OnPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
