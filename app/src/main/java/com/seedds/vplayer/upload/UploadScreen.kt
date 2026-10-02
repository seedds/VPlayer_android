package com.seedds.vplayer.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seedds.vplayer.data.fs.formatBytes
import com.seedds.vplayer.data.fs.formatDate
import com.seedds.vplayer.data.fs.formatPercent
import com.seedds.vplayer.data.model.ActiveUploadRow
import com.seedds.vplayer.data.model.UploadStatus
import com.seedds.vplayer.data.model.progress
import com.seedds.vplayer.ui.components.ButtonTone
import com.seedds.vplayer.ui.components.Panel
import com.seedds.vplayer.ui.components.ProgressBar
import com.seedds.vplayer.ui.components.VButton
import com.seedds.vplayer.ui.theme.VColors
import kotlinx.coroutines.delay

@Composable
fun UploadScreen(viewModel: UploadViewModel, modifier: Modifier = Modifier) {
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val address by viewModel.lanAddress.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val activeUploads by viewModel.activeUploadCount.collectAsStateWithLifecycle()

    // Byte counts live in the session manager, which has no reason to publish
    // on its own; poll while something is actually transferring.
    LaunchedEffect(activeUploads > 0) {
        while (activeUploads > 0) {
            viewModel.refreshActivity()
            delay(400)
        }
    }

    val running = serverState.isRunning
    val serverUrl = serverState.runningPort?.let { port -> address?.let { "http://$it:$port" } }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(VColors.Background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Panel(
                title = "HTTP upload server",
                subtitle = "Keep this tab open while sending files from your computer.",
            ) {
                Text(
                    text = if (running) "Server is running" else "Server is stopped",
                    color = VColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = serverUrl
                        ?: if (running) "Server is running. Discovering device IP..." else "Server is stopped",
                    color = VColors.ServerUrl,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                (serverState as? com.seedds.vplayer.server.ServerState.Error)?.let { error ->
                    Text(text = error.message, color = VColors.Danger, fontSize = 14.sp)
                }

                Text(
                    text = "PORT",
                    color = VColors.TextMutedPanel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(VColors.SurfaceRaised)
                        .border(1.dp, VColors.BorderAlt, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (portInput.isEmpty()) {
                        Text(text = "8081", color = VColors.Placeholder, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = portInput,
                        onValueChange = viewModel::setPortInput,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(color = VColors.TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(VColors.Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VButton(
                        label = if (running) "Restart server" else "Start server",
                        onClick = viewModel::startServer,
                        tone = ButtonTone.Accent,
                        minWidth = 120.dp,
                        horizontalPadding = 16.dp,
                        verticalPadding = 13.dp,
                        fontSize = 14.sp,
                        cornerRadius = 16.dp,
                        pressedAlpha = 0.76f,
                        disabledAlpha = 0.76f,
                    )
                    VButton(
                        label = "Stop",
                        onClick = viewModel::stopServer,
                        tone = ButtonTone.Danger,
                        enabled = running,
                        minWidth = 120.dp,
                        horizontalPadding = 16.dp,
                        verticalPadding = 13.dp,
                        fontSize = 14.sp,
                        cornerRadius = 16.dp,
                        pressedAlpha = 0.76f,
                        disabledAlpha = 0.76f,
                    )
                }
            }
        }

        item {
            Panel(
                title = "Upload activity",
                subtitle = "Each finished upload appears automatically in Library.",
            ) {
                Text(
                    text = activity.message,
                    color = VColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = formatDate(activity.updatedAt),
                    color = VColors.TextMutedDate,
                    fontSize = 13.sp,
                )

                ProgressBar(progress = activity.progress())

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val count = activity.activeUploads.size
                    Text(
                        text = if (count == 0) {
                            "No active uploads"
                        } else {
                            "$count active upload${if (count == 1) "" else "s"}"
                        },
                        color = VColors.TextMutedPanel,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = when {
                            activity.totalBytes != null && activity.totalBytes != 0L ->
                                "${formatBytes(activity.receivedBytes ?: 0L)} / ${formatBytes(activity.totalBytes)}"
                            activity.status == UploadStatus.Complete -> "Upload finished"
                            else -> "Waiting for browser upload"
                        },
                        color = VColors.TextMutedPanel,
                        fontSize = 14.sp,
                    )
                }

                if (activity.activeUploads.isEmpty()) {
                    EmptyUploadsCard()
                } else {
                    activity.activeUploads.forEach { row -> ActiveUploadCard(row) }
                }
            }
        }
    }
}

@Composable
private fun ActiveUploadCard(row: ActiveUploadRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(VColors.SurfaceRaised)
            .border(1.dp, VColors.Border, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.fileName,
                modifier = Modifier.weight(1f),
                color = VColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = formatDate(row.updatedAt), color = VColors.TextMutedDate, fontSize = 12.sp)
        }
        Text(text = row.message, color = VColors.TextMutedPanel, fontSize = 14.sp)
        ProgressBar(progress = row.progress())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatBytes(row.receivedBytes)} / ${formatBytes(row.totalBytes)}",
                color = VColors.TextMutedPanel,
                fontSize = 14.sp,
            )
            Text(text = formatPercent(row.progress()), color = VColors.TextMutedPanel, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyUploadsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(VColors.SurfaceRaised)
            .border(1.dp, VColors.Border, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(text = "No active uploads", color = VColors.TextMutedPanel, fontSize = 14.sp)
    }
}
