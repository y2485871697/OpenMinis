package com.openminis.app.ui.chat.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.speech.VoiceOutputState

/**
 * Shared RikkaHub-style floating controller for manual read-aloud and the
 * voice panel's Read Replies channel. Both entry points register their player
 * in [VoiceOutputState], so this always controls the sound the user hears.
 */
@Composable
fun SpeechPlayerCapsule(
    modifier: Modifier = Modifier,
    bottomObstructionPx: Int = 0,
    additionalObstructionDp: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }
    val globalSpeaking by VoiceOutputState.isSpeaking.collectAsState()
    val synthesizing by VoiceOutputState.isSynthesizing.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()

    val activePlayer = VoiceOutputState.activePlayer
    val idleSpeaking = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val idlePaused = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val idleIndex = remember { kotlinx.coroutines.flow.MutableStateFlow(0) }
    val playerSpeaking by (activePlayer?.isSpeaking
        ?: idleSpeaking).collectAsState()
    val paused by (activePlayer?.isPaused
        ?: idlePaused).collectAsState()
    val currentChunk by (activePlayer?.currentChunkIndex
        ?: idleIndex).collectAsState()
    val totalChunks by (activePlayer?.totalChunks
        ?: idleIndex).collectAsState()

    if (!globalSpeaking && !playerSpeaking && !paused && !synthesizing) return

    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(playerSpeaking) {
        if (playerSpeaking) expanded = true
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val obstruction = with(density) { bottomObstructionPx.toDp() }
        val lift = maxOf(
            if (bottomObstructionPx > 0) obstruction + 8.dp else 0.dp,
            additionalObstructionDp,
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp + lift),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        if (paused) activePlayer?.resume() else activePlayer?.pause()
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (playerSpeaking && !paused) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        if (totalChunks > 0) {
                            CircularProgressIndicator(
                                progress = { currentChunk.toFloat() / totalChunks.toFloat() },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = Color.Transparent,
                            )
                        }
                    }
                }

                IconButton(onClick = {
                    activePlayer?.stop()
                    VoiceOutputState.setEnabled(false)
                }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }

                AnimatedVisibility(expanded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { VoiceOutputState.nextSpeed() }) {
                            Text("x${"%.2g".format(speed)}")
                        }
                        IconButton(onClick = { activePlayer?.fastForward(5_000) }) {
                            Icon(Icons.Default.FastForward, contentDescription = null)
                        }
                    }
                }

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
