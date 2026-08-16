package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JarvisAmberAccent
import com.example.ui.theme.JarvisBlack
import com.example.ui.theme.JarvisCyanLight
import com.example.ui.theme.JarvisCyanPrimary
import com.example.ui.theme.JarvisRedAlert
import com.example.viewmodel.AssistantStatus

@Composable
fun SpeakButton(
    status: AssistantStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "speak_button_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "btn_pulse"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "btn_glow"
    )

    val isListening = status == AssistantStatus.ACTIVE_LISTENING
    val isSpeaking = status == AssistantStatus.SPEAKING
    val isProcessing = status == AssistantStatus.PROCESSING
    val isPaused = status == AssistantStatus.PAUSED

    val buttonColor by animateColorAsState(
        targetValue = when {
            isListening -> JarvisRedAlert
            isSpeaking -> JarvisAmberAccent
            isProcessing -> JarvisAmberAccent
            isPaused -> Color(0xFF4A6572)
            else -> JarvisCyanPrimary
        },
        label = "btn_color"
    )

    val buttonText = when (status) {
        AssistantStatus.ACTIVE_LISTENING -> "HENTIKAN MENDENGARKAN"
        AssistantStatus.SPEAKING -> "HENTIKAN SUARA JARVIS"
        AssistantStatus.PROCESSING -> "MEMPROSES..."
        AssistantStatus.PAUSED -> "LANJUTKAN JARVIS"
        AssistantStatus.ERROR -> "BICARA / COBA LAGI"
        AssistantStatus.WAKE_WORD_LISTENING -> "BICARA MANUAL"
    }

    val subText = when (status) {
        AssistantStatus.ACTIVE_LISTENING -> "JARVIS sedang mendengarkan perintah Anda..."
        AssistantStatus.SPEAKING -> "Sentuh untuk menghentikan pembacaan suara"
        AssistantStatus.PROCESSING -> "Menganalisis perintah bahasa Indonesia..."
        AssistantStatus.PAUSED -> "Sentuh untuk mengaktifkan mikrofon dan wake word"
        AssistantStatus.ERROR -> "Sentuh untuk mencoba input suara kembali"
        AssistantStatus.WAKE_WORD_LISTENING -> "Panggil \"JARVIS\" atau tekan tombol ini"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring when active listening or speaking
        if (isListening || isSpeaking) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .scale(pulseScale)
                    .clip(RoundedCornerShape(32.dp))
                    .background(buttonColor.copy(alpha = glowAlpha * 0.3f))
            )
        }

        // Main tactile button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = when {
                            isListening -> listOf(Color(0xFFDC2626), Color(0xFFEF4444), Color(0xFFB91C1C))
                            isSpeaking -> listOf(Color(0xFFD97706), Color(0xFFF59E0B), Color(0xFFB45309))
                            isPaused -> listOf(Color(0xFF37474F), Color(0xFF455A64), Color(0xFF263238))
                            else -> listOf(Color(0xFF00B4D8), Color(0xFF00E5FF), Color(0xFF0096C7))
                        }
                    )
                )
                .border(
                    width = 2.dp,
                    color = if (isListening || isSpeaking) Color.White else JarvisCyanLight,
                    shape = RoundedCornerShape(30.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onClick
                )
                .testTag("speak_button"),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Mic / Stop / Play Icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(JarvisBlack.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isListening || isSpeaking -> Icons.Default.Stop
                            isPaused -> Icons.Default.PlayArrow
                            else -> Icons.Default.Mic
                        },
                        contentDescription = "Tombol Aksi Suara",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = buttonText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            letterSpacing = 1.2.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
