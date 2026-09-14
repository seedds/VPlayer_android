package com.seedds.vplayer.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.ui.theme.VColors

/** Confirmation for an action that destroys data or resets progress. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VColors.Surface,
        titleContentColor = VColors.TextPrimary,
        textContentColor = VColors.TextMutedPanel,
        title = { Text(text = title, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp) },
        text = { Text(text = message, fontSize = 15.sp, lineHeight = 21.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = VColors.Danger, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = VColors.TextSecondary, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** Reports a failure that the user needs to see but cannot act on. */
@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VColors.Surface,
        titleContentColor = VColors.TextPrimary,
        textContentColor = VColors.TextMutedPanel,
        title = { Text(text = title, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp) },
        text = { Text(text = message, fontSize = 15.sp, lineHeight = 21.sp) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "OK", color = VColors.Primary, fontWeight = FontWeight.Bold)
            }
        },
    )
}
