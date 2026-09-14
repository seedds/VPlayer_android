package com.seedds.vplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * VPlayer renders one fixed light scheme regardless of the system setting; the
 * whole design is hand-tuned for it and there is no dark variant to fall back
 * to. [isSystemInDarkTheme] is deliberately ignored.
 */
private val VPlayerColorScheme = lightColorScheme(
    primary = VColors.Primary,
    onPrimary = VColors.OnPrimary,
    secondary = VColors.Cta,
    onSecondary = VColors.OnCta,
    error = VColors.Danger,
    onError = VColors.OnDanger,
    background = VColors.Background,
    onBackground = VColors.TextPrimary,
    surface = VColors.Surface,
    onSurface = VColors.TextPrimary,
    surfaceVariant = VColors.SurfaceRaised,
    onSurfaceVariant = VColors.TextSecondary,
    outline = VColors.Border,
    outlineVariant = VColors.BorderAlt,
    scrim = VColors.Scrim,
)

@Composable
fun VPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VPlayerColorScheme,
        typography = VPlayerTypography,
        content = content,
    )
}
