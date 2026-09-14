package com.seedds.vplayer.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.seedds.vplayer.data.fs.formatClockTime
import com.seedds.vplayer.data.fs.formatSpeed
import com.seedds.vplayer.ui.theme.VColors
import kotlinx.coroutines.delay

/**
 * Full-screen playback.
 *
 * The screen is deliberately quiet: chrome fades out while something is
 * playing, and the whole surface is one big gesture target. Locking hides the
 * controls without disabling the gestures, so a phone in a pocket or a hand
 * resting on the screen cannot skip the video, while a tap still works.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var stripWidthPx by remember { mutableIntStateOf(1) }

    ImmersiveLandscape(activity)
    PauseWhenAway(viewModel)

    BackHandler {
        viewModel.onLeaving()
        onClose()
    }

    Box(modifier = modifier.fillMaxSize().background(VColors.Player.Background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    player = viewModel.player
                }
            },
            onRelease = { view -> view.player = null },
        )

        // The gesture layer sits under the controls so buttons win their taps,
        // and covers everything else so the video itself is always touchable.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .playerGestures(
                    scrubbing = state.scrubbing,
                    callbacks = rememberGestureCallbacks(viewModel),
                ),
        )

        state.subtitleText?.let { text ->
            SubtitleOverlay(
                text = text,
                fontSizeSp = state.subtitleFontSize,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 18.dp)
                    // Lift clear of the seek bar while it is showing.
                    .padding(bottom = if (state.controlsVisible && !state.locked) 54.dp else 14.dp),
            )
        }

        state.boostSpeed?.let { boost ->
            BoostBadge(
                speed = boost,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp),
            )
        }

        state.errorMessage?.let { message ->
            ErrorOverlay(message = message, modifier = Modifier.align(Alignment.Center))
        }

        if (state.controlsVisible) {
            PlayerControls(
                state = state,
                stripWidthPx = stripWidthPx,
                onStripWidth = { stripWidthPx = it },
                viewModel = viewModel,
                onClose = {
                    viewModel.onLeaving()
                    onClose()
                },
            )
        }
    }
}

@Composable
private fun rememberGestureCallbacks(viewModel: PlayerViewModel): PlayerGestureCallbacks =
    remember(viewModel) {
        object : PlayerGestureCallbacks {
            override fun onToggleControls() = viewModel.toggleControls()
            override fun onTogglePlayback() = viewModel.togglePlayback()
            override fun onToggleLock() = viewModel.toggleLock()
            override fun onHoldStart() = viewModel.startHoldBoost()
            override fun onHoldEnd() = viewModel.endHoldBoost()
            override fun canHold(): Boolean = viewModel.canHold()
        }
    }

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    stripWidthPx: Int,
    onStripWidth: (Int) -> Unit,
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (!state.locked) {
            TopBar(
                title = state.video?.name.orEmpty(),
                hasNext = state.hasNext,
                onBack = onClose,
                onNext = viewModel::next,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        CenterControls(
            locked = state.locked,
            isPlaying = state.isPlaying,
            onToggleLock = viewModel::toggleLock,
            onSeekBack = { viewModel.seekBy(-PlayerViewModel.SEEK_STEP_SECONDS) },
            onSeekForward = { viewModel.seekBy(PlayerViewModel.SEEK_STEP_SECONDS) },
            onTogglePlayback = viewModel::togglePlayback,
            modifier = Modifier.align(Alignment.Center),
        )

        if (!state.locked) {
            SpeedControl(
                speed = state.playbackSpeed,
                onFaster = { viewModel.changeSpeed(PlayerViewModel.SPEED_STEP) },
                onSlower = { viewModel.changeSpeed(-PlayerViewModel.SPEED_STEP) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 12.dp),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 12.dp),
            ) {
                if (state.scrubbing) {
                    FramePreview(
                        frame = state.previewFrame,
                        progress = state.progress,
                        stripWidthPx = stripWidthPx,
                        modifier = Modifier.align(Alignment.TopStart).padding(bottom = 12.dp),
                    )
                }
                SeekStrip(
                    progress = state.progress,
                    elapsedSeconds = state.displayedSeconds,
                    remainingSeconds = state.remainingSeconds,
                    fileName = state.video?.name.orEmpty(),
                    durationSeconds = state.durationSeconds,
                    onScrubStart = viewModel::beginScrub,
                    onScrubUpdate = viewModel::updateScrub,
                    onScrubCommit = viewModel::commitScrub,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .onSizeChanged { onStripWidth(it.width.coerceAtLeast(1)) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    hasNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp)
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.width(78.dp), contentAlignment = Alignment.CenterStart) {
            PlayerPill(label = "Back", onClick = onBack)
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The wall clock, because the system bar is hidden in here and
            // people want to know how late it is getting.
            Text(
                text = formatClockTime(clock),
                color = VColors.Player.OnAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = title,
                color = VColors.Player.OnAccent.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Box(modifier = Modifier.width(78.dp), contentAlignment = Alignment.CenterEnd) {
            if (hasNext) PlayerPill(label = "Next", onClick = onNext)
        }
    }
}

@Composable
private fun CenterControls(
    locked: Boolean,
    isPlaying: Boolean,
    onToggleLock: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(VColors.Player.Chip)
                .border(1.dp, VColors.Player.Hairline, RoundedCornerShape(999.dp))
                .clickable(onClick = onToggleLock),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (locked) "🔓" else "🔒",
                fontSize = 22.sp,
            )
        }

        // Kept in the layout while locked so the lock button does not jump.
        Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (!locked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PlayerPill(label = "-10", onClick = onSeekBack, minWidth = 64.dp)
                    PlayerPill(
                        label = if (isPlaying) "❚❚" else "▶",
                        onClick = onTogglePlayback,
                        minWidth = 92.dp,
                        verticalPadding = 12.dp,
                        horizontalPadding = 22.dp,
                    )
                    PlayerPill(label = "+10", onClick = onSeekForward, minWidth = 64.dp)
                }
            }
        }
    }
}

@Composable
private fun SpeedControl(
    speed: Float,
    onFaster: () -> Unit,
    onSlower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpeedButton(glyph = "+", onClick = onFaster)
        // The rate sits on a chip rather than floating: bare white text
        // disappears the moment the video behind it is bright.
        Text(
            text = formatSpeed(speed),
            modifier = Modifier
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VColors.Player.Chip)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = VColors.Player.OnAccent,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        SpeedButton(glyph = "−", onClick = onSlower)
    }
}

@Composable
private fun SpeedButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.Player.AccentFill)
            .border(1.dp, VColors.Player.Hairline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = VColors.Player.OnAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlayerPill(
    label: String,
    onClick: () -> Unit,
    minWidth: androidx.compose.ui.unit.Dp = 0.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 10.dp,
) {
    Box(
        modifier = Modifier
            .widthIn(min = minWidth)
            .clip(RoundedCornerShape(999.dp))
            .background(VColors.Player.AccentFill)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = VColors.Player.OnAccent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BoostBadge(speed: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(VColors.Player.Chip)
            .border(1.dp, VColors.Player.Hairline, RoundedCornerShape(999.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "${formatSpeed(speed)}×",
            color = VColors.Player.OnAccent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = "▶", color = VColors.Player.OnAccent, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorOverlay(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "⚠", color = VColors.Player.OnAccent, fontSize = 40.sp)
        Text(
            text = message,
            color = VColors.Player.OnAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Landscape and full bleed for as long as the player is on screen, restored on
 * the way out so the library comes back the way the user left it.
 */
@Composable
private fun ImmersiveLandscape(activity: Activity?) {
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }

        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val previousOrientation = activity.requestedOrientation

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
        }
    }
}

/**
 * Stops playback whenever the player stops being the thing the user is looking
 * at, including losing window focus to a notification shade or a split-screen
 * neighbour, which a lifecycle event alone would miss.
 */
@Composable
private fun PauseWhenAway(viewModel: PlayerViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onInterrupted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (!focused) viewModel.onInterrupted()
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
