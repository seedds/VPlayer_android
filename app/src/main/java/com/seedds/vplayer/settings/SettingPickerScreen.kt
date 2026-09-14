package com.seedds.vplayer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seedds.vplayer.ui.components.MessageDialog
import com.seedds.vplayer.ui.components.Panel
import com.seedds.vplayer.ui.theme.VColors

@Composable
fun SettingPickerScreen(
    viewModel: SettingsViewModel,
    settingKey: SettingKey,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val meta = SettingsCatalog[settingKey]
    val current = settings[settingKey]

    Column(modifier = modifier.fillMaxSize().background(VColors.Background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "‹ Settings",
                    color = VColors.Primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = meta.navTitle,
                color = VColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Panel(title = meta.title, subtitle = meta.subtitle) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    meta.options.forEach { option ->
                        OptionChip(
                            label = meta.label(option),
                            selected = option == current,
                            onClick = { viewModel.update(settingKey, option, onBack) },
                        )
                    }
                }
            }
        }
    }

    error?.let { message ->
        MessageDialog(title = "Save failed", message = message, onDismiss = viewModel::dismissError)
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) VColors.PrimaryTint else VColors.SurfaceRaised)
            .border(1.dp, if (selected) VColors.Primary else VColors.BorderAlt, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) VColors.Primary else VColors.TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
