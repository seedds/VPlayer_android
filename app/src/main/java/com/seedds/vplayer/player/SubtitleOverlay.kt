package com.seedds.vplayer.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.ui.theme.VColors

/**
 * Subtitles over the video.
 *
 * The text is drawn twice, a black stroke under a near-white fill, because a
 * subtitle has to stay readable over both a snowfield and a night scene and a
 * plain shadow does not survive either.
 */
@Composable
fun SubtitleOverlay(
    text: String,
    fontSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { OUTLINE_WIDTH_DP.dp.toPx() }
    // The React Native build paired a 36sp face with 44sp of leading; keep that
    // ratio so a larger subtitle size stays proportionally spaced.
    val lineHeight = fontSizeSp * BASE_LINE_HEIGHT / BASE_FONT_SIZE

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val shared = TextStyle(
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeight.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = text,
            style = shared.copy(
                color = VColors.Player.SubtitleOutline,
                drawStyle = Stroke(width = strokeWidthPx),
            ),
        )
        Text(
            text = text,
            style = shared.copy(color = VColors.Player.SubtitleFill),
        )
    }
}

private const val BASE_FONT_SIZE = 36
private const val BASE_LINE_HEIGHT = 44

/** Thick enough to read over a bright scene without swallowing thin glyphs. */
private const val OUTLINE_WIDTH_DP = 2
