package com.seedds.vplayer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seedds.vplayer.ui.components.ButtonTone
import com.seedds.vplayer.ui.components.Panel
import com.seedds.vplayer.ui.components.VButton
import com.seedds.vplayer.ui.theme.VColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenPicker: (SettingKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(VColors.Background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BatteryPanel() }

        items(SettingsCatalog.panels.size) { index ->
            val panel = SettingsCatalog.panels[index]
            Panel(title = panel.title, subtitle = panel.subtitle) {
                panel.keys.forEach { key ->
                    val meta = SettingsCatalog[key]
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = meta.title,
                            color = VColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = meta.subtitle,
                            color = VColors.TextMutedPanel,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        SettingRow(
                            label = meta.rowLabel,
                            value = meta.label(settings[key]),
                            onClick = { onOpenPicker(key) },
                        )
                        meta.footnote?.let { footnote ->
                            Text(
                                text = footnote,
                                color = VColors.TextMutedPanel,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Aggressive battery management is the usual reason a long upload dies halfway
 * on a phone that is otherwise working fine, and it is not something the app
 * can fix from the inside. Offer the exemption and explain why.
 */
@Composable
private fun BatteryPanel() {
    val context = LocalContext.current
    Panel(
        title = "Background uploads",
        subtitle = "Some phones stop background transfers to save battery.",
    ) {
        Text(
            text = "If large uploads stop when you switch apps or the screen turns off, " +
                "allow VPlayer to keep running in the background.",
            color = VColors.TextMutedPanel,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        VButton(
            label = "Battery settings",
            onClick = { openBatterySettings(context) },
            tone = ButtonTone.Secondary,
            horizontalPadding = 16.dp,
            verticalPadding = 12.dp,
            fontSize = 14.sp,
            cornerRadius = 16.dp,
        )
    }
}

private fun openBatterySettings(context: android.content.Context) {
    // The per-app request dialog is the direct route, but it is not present on
    // every build, so fall back to the system list rather than doing nothing.
    val direct = android.content.Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        android.net.Uri.parse("package:" + context.packageName),
    )
    val fallback = android.content.Intent(
        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    )
    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(fallback) }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(VColors.SurfaceRaised)
            .border(1.dp, VColors.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = VColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = value, color = VColors.Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = "›", color = VColors.Placeholder, fontSize = 20.sp)
        }
    }
}
