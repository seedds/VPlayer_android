package com.seedds.vplayer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette, ported verbatim from the React Native build. VPlayer is
 * light-only by design: there is no dark scheme, so these are absolute colors
 * rather than roles that flip with the system theme.
 */
object VColors {
    val Background = Color(0xFFEFE7DB)
    val Surface = Color(0xFFFFF8F1)
    val SurfaceRaised = Color(0xFFFFFDF9)

    val Border = Color(0xFFEAD8C4)
    val BorderAlt = Color(0xFFDFCFBD)
    val DividerTabBar = Color(0xFFDED1C2)
    val DividerPicker = Color(0xFFE0D3C4)
    val DividerRow = Color(0xFFDDCFBF)

    val Primary = Color(0xFF1F6F68)
    val PrimaryPressed = Color(0xFF175551)
    val PrimaryTint = Color(0xFFDCEEEA)
    val RowSelected = Color(0xFFEEF7F5)

    val Cta = Color(0xFFC6673D)
    val Danger = Color(0xFF9E3E28)
    val SwipeDelete = Color(0xFFC84630)
    val SwipeDeletePressed = Color(0xFFA93523)

    val ButtonSecondary = Color(0xFFE3D7CA)
    val RowPressed = Color(0xFFE6DDD2)

    val TextPrimary = Color(0xFF1D1917)
    val TextSecondary = Color(0xFF4F463F)
    val TextMuted = Color(0xFF6B6158)
    val TextMutedAlt = Color(0xFF70665D)
    val TextMutedPanel = Color(0xFF6F655C)
    val TextMutedBody = Color(0xFF645A51)
    val TextMutedLoading = Color(0xFF62574E)
    val TextMutedDate = Color(0xFF756A61)
    val Placeholder = Color(0xFF8F857B)

    val ServerUrl = Color(0xFFB35A36)

    val ThumbBackground = Color(0xFFD7CCC1)
    val FolderThumbBackground = Color(0xFFFFF5EB)
    val FolderIconFill = Color(0xFFF8F1E8)
    val FolderIconStroke = Color(0xFFC97846)

    val OnPrimary = Color(0xFFF6F1EB)
    val OnCta = Color(0xFFFFF7F2)
    val OnDanger = Color(0xFFFFF7F2)
    val OnSwipeRename = Color(0xFFF2FBF9)
    val OnSwipeDelete = Color(0xFFFFF3EF)

    val BadgeBase = Color(0xFFF4E7DA)
    val BadgeOutline = Color(0xFFC7B4A5)
    val ProgressTrack = Color(0xFFE7D8C9)
    val SelectionBorder = Color(0xFFD8C7B6)

    val Scrim = Color(0x73140C0C)

    /** Player surface colors. The player is a separate, near-black world. */
    object Player {
        val Background = Color(0xFF050505)
        val AccentFill = Color(0xE61F6F68)
        val ProgressFill = Color(0xFF1F6F68)
        val Chip = Color(0xC7080C10)
        val Popup = Color(0xF5080C10)
        val SeekTrack = Color(0xB80A121A)
        val Hairline = Color(0x1FFFFFFF)
        val PopupBorder = Color(0x14FFFFFF)
        val OnAccent = Color(0xFFFFFFFF)
        val SubtitleFill = Color(0xFFF2F2F2)
        val SubtitleOutline = Color(0xFF000000)
    }
}
