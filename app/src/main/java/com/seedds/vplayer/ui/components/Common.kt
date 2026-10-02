package com.seedds.vplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.ui.theme.VColors

/** The rounded card that groups a section of the Upload and Settings screens. */
@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(VColors.Surface)
            .border(1.dp, VColors.Border, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Text(
            text = title,
            color = VColors.TextPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = VColors.TextMutedAlt,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

/** Visual weight of a [VButton]. */
enum class ButtonTone { Primary, Secondary, Danger, Accent }

/**
 * The app's button. Pressed and disabled both dim the button rather than
 * recoloring it, which is how the original React Native styles behaved. The
 * spec gives some screens their own amounts: the Upload tab dims both to 0.76,
 * and the move sheet's "Move Here" disables to 0.4.
 */
@Composable
fun VButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Secondary,
    enabled: Boolean = true,
    minWidth: androidx.compose.ui.unit.Dp = 0.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 10.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    cornerRadius: androidx.compose.ui.unit.Dp = 14.dp,
    pressedAlpha: Float = 0.78f,
    disabledAlpha: Float = 0.5f,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val background = when (tone) {
        ButtonTone.Primary -> VColors.Primary
        ButtonTone.Secondary -> VColors.ButtonSecondary
        ButtonTone.Danger -> VColors.Danger
        ButtonTone.Accent -> VColors.Cta
    }
    val foreground = when (tone) {
        ButtonTone.Secondary -> VColors.TextSecondary
        else -> VColors.OnCta
    }

    Box(
        modifier = modifier
            .widthIn(min = minWidth)
            .alpha(
                when {
                    !enabled -> disabledAlpha
                    pressed -> pressedAlpha
                    else -> 1f
                },
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Shown on every tab until the first library listing and the server are ready. */
@Composable
fun LoadingCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(VColors.Surface)
            .border(BorderStroke(1.dp, VColors.Border), RoundedCornerShape(24.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = VColors.Primary)
            Text(
                text = "Preparing storage, network, and local upload server...",
                color = VColors.TextMutedLoading,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The card shown when a folder, or the whole library, has nothing in it. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(VColors.Surface)
            .border(BorderStroke(1.dp, VColors.Border), RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = VColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                color = VColors.TextMutedBody,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A thin progress bar, used for both per-file and aggregate upload progress. */
@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 12.dp,
    trackColor: Color = VColors.ProgressTrack,
    fillColor: Color = VColors.Primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(fillColor),
        )
    }
}
