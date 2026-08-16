package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JarvisAmberAccent
import com.example.ui.theme.JarvisBorderSubtle
import com.example.ui.theme.JarvisCyanLight
import com.example.ui.theme.JarvisCyanPrimary
import com.example.ui.theme.JarvisGreenSuccess
import com.example.ui.theme.JarvisRedAlert
import com.example.ui.theme.JarvisSurfaceNavy
import com.example.viewmodel.AssistantStatus

@Composable
fun JarvisHeader(
    status: AssistantStatus,
    isTtsMuted: Boolean,
    isPaused: Boolean,
    onTogglePauseResume: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenManualInput: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_radar")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    val statusDotColor = when (status) {
        AssistantStatus.WAKE_WORD_LISTENING -> JarvisGreenSuccess
        AssistantStatus.ACTIVE_LISTENING -> JarvisCyanLight
        AssistantStatus.PROCESSING -> JarvisAmberAccent
        AssistantStatus.SPEAKING -> JarvisCyanLight
        AssistantStatus.PAUSED -> Color(0xFF6A859E)
        AssistantStatus.ERROR -> JarvisRedAlert
    }

    val statusSubtitle = when (status) {
        AssistantStatus.WAKE_WORD_LISTENING -> "WAKE WORD: \"JARVIS\" (SIAGA)"
        AssistantStatus.ACTIVE_LISTENING -> "MENDENGARKAN PERINTAH..."
        AssistantStatus.PROCESSING -> "MEMPROSES DATA..."
        AssistantStatus.SPEAKING -> "MENJAWAB SUARA..."
        AssistantStatus.PAUSED -> "MIKROFON DIJEDA"
        AssistantStatus.ERROR -> "PERINGATAN SISTEM"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JarvisSurfaceNavy.copy(alpha = 0.7f))
            .border(1.dp, JarvisBorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated radar dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusDotColor.copy(alpha = if (isPaused) 0.5f else dotAlpha))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "JARVIS OS // V1",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.2.sp
                    ),
                    color = JarvisCyanPrimary
                )
                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp
                    ),
                    color = statusDotColor
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Pause / Resume listening toggle
            IconButton(
                onClick = onTogglePauseResume,
                modifier = Modifier.testTag("pause_resume_button")
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "Lanjutkan JARVIS" else "Jeda JARVIS",
                    tint = if (isPaused) JarvisAmberAccent else JarvisCyanPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Manual keyboard input fallback button
            IconButton(
                onClick = onOpenManualInput,
                modifier = Modifier.testTag("manual_input_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Ketik Perintah Manual",
                    tint = JarvisCyanPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Mute / Unmute TTS
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier.testTag("mute_tts_button")
            ) {
                Icon(
                    imageVector = if (isTtsMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isTtsMuted) "Suara Dimatikan" else "Suara Aktif",
                    tint = if (isTtsMuted) JarvisAmberAccent else JarvisCyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Clear history
            IconButton(
                onClick = onClearHistory,
                modifier = Modifier.testTag("clear_history_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Hapus Riwayat",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
