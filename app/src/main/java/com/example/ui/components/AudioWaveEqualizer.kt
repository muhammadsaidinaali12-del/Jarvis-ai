package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JarvisAmberAccent
import com.example.ui.theme.JarvisCyanLight
import com.example.ui.theme.JarvisCyanPrimary
import com.example.ui.theme.JarvisRedAlert
import com.example.viewmodel.AssistantStatus

@Composable
fun AudioWaveEqualizer(
    status: AssistantStatus,
    audioLevel: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 18
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer_bars")

    val baseColor = when (status) {
        AssistantStatus.WAKE_WORD_LISTENING -> JarvisCyanPrimary.copy(alpha = 0.6f)
        AssistantStatus.ACTIVE_LISTENING -> JarvisCyanLight
        AssistantStatus.PROCESSING -> JarvisAmberAccent
        AssistantStatus.SPEAKING -> JarvisCyanLight
        AssistantStatus.PAUSED -> Color(0xFF6A859E).copy(alpha = 0.3f)
        AssistantStatus.ERROR -> JarvisRedAlert
    }

    Row(
        modifier = modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val phaseOffset = (i * 120) % 800
            val animatedFactor by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400 + phaseOffset, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )

            val heightMultiplier = when (status) {
                AssistantStatus.ACTIVE_LISTENING -> (0.3f + audioLevel * 0.7f) * animatedFactor
                AssistantStatus.WAKE_WORD_LISTENING -> (0.15f + audioLevel * 0.5f) * animatedFactor
                AssistantStatus.SPEAKING -> 0.4f + (animatedFactor * 0.6f)
                AssistantStatus.PROCESSING -> 0.3f + (animatedFactor * 0.4f)
                AssistantStatus.PAUSED -> 0.1f
                AssistantStatus.ERROR -> 0.15f
            }.coerceIn(0.1f, 1f)

            val currentBarHeight = (26.dp * heightMultiplier).coerceAtLeast(3.dp)

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(currentBarHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.9f),
                                baseColor,
                                baseColor.copy(alpha = 0.4f)
                            )
                        )
                    )
            )
        }
    }
}
