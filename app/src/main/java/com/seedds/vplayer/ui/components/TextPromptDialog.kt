package com.seedds.vplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.seedds.vplayer.ui.theme.VColors

/**
 * The single-field prompt used for creating a folder and renaming an item.
 *
 * [initialSelection] lets the rename flow preselect just the base name, so
 * typing replaces the name while leaving the extension visible and intact. It
 * is applied once when the dialog appears; reapplying it on recomposition would
 * re-select the text after every keystroke.
 */
@Composable
fun TextPromptDialog(
    title: String,
    confirmLabel: String,
    initialValue: String = "",
    placeholder: String = "",
    initialSelection: IntRange? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialValue,
                selection = initialSelection
                    ?.let { TextRange(it.first, it.last) }
                    ?: TextRange(initialValue.length),
            ),
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = value.text.trim()
    val canConfirm = trimmed.isNotEmpty()

    fun submit() {
        if (canConfirm) onConfirm(trimmed)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(VColors.Surface)
                .border(1.dp, VColors.Border, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                color = VColors.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
            )

            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(VColors.SurfaceRaised)
                    .border(1.dp, VColors.BorderAlt, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = VColors.Placeholder,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = VColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(VColors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                VButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    tone = ButtonTone.Secondary,
                    horizontalPadding = 16.dp,
                    verticalPadding = 12.dp,
                    fontSize = 14.sp,
                    cornerRadius = 16.dp,
                )
                VButton(
                    label = confirmLabel,
                    onClick = ::submit,
                    tone = ButtonTone.Accent,
                    enabled = canConfirm,
                    horizontalPadding = 16.dp,
                    verticalPadding = 12.dp,
                    fontSize = 14.sp,
                    cornerRadius = 16.dp,
                )
            }
        }
    }
}

/**
 * The selection a rename prompt opens with: the base name, leaving the
 * extension unselected so it survives typing.
 */
fun renameSelection(name: String): IntRange {
    val extension = com.seedds.vplayer.data.fs.LibraryNames.extensionOf(name)
    val end = (name.length - extension.length).coerceAtLeast(0)
    return 0..end
}
