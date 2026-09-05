package com.openminis.app.ui.chat.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.KEY_AUTO_CLOSE_READ_ALOUD_CAPSULE
import kotlin.math.roundToInt

/** Compact, draggable controller shared by manual and automatic read-aloud. */
@Composable
fun SpeechPlayerCapsule(
    modifier: Modifier = Modifier,
    bottomObstructionPx: Int = 0,
    additionalObstructionDp: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    val appearancePrefs = remember(context) { getAppearancePrefs(context) }
    var autoClose by remember(appearancePrefs) {
        mutableStateOf(appearancePrefs.getBoolean(KEY_AUTO_CLOSE_READ_ALOUD_CAPSULE, true))
    }
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == null || key == KEY_AUTO_CLOSE_READ_ALOUD_CAPSULE) {
                autoClose = prefs.getBoolean(KEY_AUTO_CLOSE_READ_ALOUD_CAPSULE, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled by VoiceOutputState.isEnabled.collectAsState()
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }
    val globalSpeaking by VoiceOutputState.isSpeaking.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()
    val savedDragDp by VoiceOutputState.dragOffsetDp.collectAsState()

    val activePlayer by VoiceOutputState.activePlayer.collectAsState()
    val idleBoolean = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val idleFloat = remember { kotlinx.coroutines.flow.MutableStateFlow(0f) }
    val playerSpeaking by (activePlayer?.isSpeaking ?: idleBoolean).collectAsState()
    val paused by (activePlayer?.isPaused ?: idleBoolean).collectAsState()
    val synthesizing by (activePlayer?.isSynthesizing ?: idleBoolean).collectAsState()
    val playbackProgress by (activePlayer?.playbackProgress ?: idleFloat).collectAsState()

    val hasPlayback = globalSpeaking || playerSpeaking || paused || synthesizing
    var wasVisible by remember { mutableStateOf(false) }
    val visible = speechCapsuleVisible(enabled, hasPlayback, wasVisible, autoClose)
    LaunchedEffect(visible) { wasVisible = visible }
    if (!visible) return

    var expanded by remember { mutableStateOf(false) }
    val capsuleWidth by animateDpAsState(
        targetValue = if (expanded) 220.dp else 118.dp,
        animationSpec = tween(durationMillis = 160),
        label = "speech-capsule-width",
    )
    var capsuleSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(savedDragDp, density) {
        dragOffsetPx = savedDragDp?.let { (x, y) ->
            with(density) { Offset(x.dp.toPx(), y.dp.toPx()) }
        } ?: Offset.Zero
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val parentWidthPx = constraints.maxWidth.toFloat()
        val parentHeightPx = constraints.maxHeight.toFloat()
        val obstruction = with(density) { bottomObstructionPx.toDp() }
        val lift = maxOf(
            if (bottomObstructionPx > 0) obstruction + 8.dp else 0.dp,
            additionalObstructionDp,
        )
        val endInsetPx = with(density) { 10.dp.toPx() }
        val bottomInsetPx = with(density) { (10.dp + lift).toPx() }

        fun clampOffset(value: Offset): Offset {
            val minX = -(
                parentWidthPx - capsuleSize.width - endInsetPx
            ).coerceAtLeast(0f)
            val minY = -(
                parentHeightPx - capsuleSize.height - bottomInsetPx
            ).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(minX, 0f), value.y.coerceIn(minY, 0f))
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 10.dp + lift)
                .offset {
                    val safe = clampOffset(dragOffsetPx)
                    IntOffset(safe.x.roundToInt(), safe.y.roundToInt())
                }
                .onGloballyPositioned {
                    capsuleSize = it.size
                    dragOffsetPx = clampOffset(dragOffsetPx)
                }
                .pointerInput(parentWidthPx, parentHeightPx, capsuleSize) {
                    detectDragGestures(
                        onDragEnd = {
                            val safe = clampOffset(dragOffsetPx)
                            dragOffsetPx = safe
                            with(density) {
                                VoiceOutputState.setCapsuleDragOffsetDp(
                                    safe.x.toDp().value,
                                    safe.y.toDp().value,
                                )
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        dragOffsetPx = clampOffset(dragOffsetPx + amount)
                    }
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            // The same progress fraction spans the animated compact/expanded
            // width, avoiding a stale fixed-width track after toggling state.
            Column(modifier = Modifier.width(capsuleWidth)) {
                Row(
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        modifier = Modifier.size(40.dp),
                        enabled = activePlayer != null && hasPlayback,
                        onClick = {
                            if (paused) activePlayer?.resume() else activePlayer?.pause()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Icon(
                            if (playerSpeaking && !paused) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = {
                            wasVisible = false
                            VoiceOutputState.setEnabled(false)
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(19.dp))
                    }

                    AnimatedVisibility(expanded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                modifier = Modifier.height(36.dp),
                                onClick = { VoiceOutputState.nextSpeed() },
                            ) {
                                Text(VoiceOutputState.speedLabel(speed))
                            }
                            IconButton(
                                modifier = Modifier.size(36.dp),
                                onClick = { activePlayer?.fastForward(5_000) },
                                enabled = activePlayer != null && hasPlayback,
                            ) {
                                Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(19.dp))
                            }
                        }
                    }

                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = { expanded = !expanded },
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                val safeProgress = if (!hasPlayback) 1f else if (playbackProgress.isFinite()) {
                    playbackProgress.coerceIn(0f, 1f)
                } else {
                    0f
                }
                // Exclude the rounded end caps from the playback range in both sizes.
                val progressModifier = Modifier
                    .padding(horizontal = 23.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                if (synthesizing && safeProgress == 0f) {
                    LinearProgressIndicator(
                        modifier = progressModifier,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { safeProgress },
                        modifier = progressModifier,
                    )
                }
            }
        }
    }
}
