package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JarvisAmberAccent
import com.example.ui.theme.JarvisCyanGlow
import com.example.ui.theme.JarvisCyanLight
import com.example.ui.theme.JarvisCyanPrimary
import com.example.ui.theme.JarvisRedAlert
import com.example.viewmodel.AssistantStatus
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArcReactorVisualizer(
    status: AssistantStatus,
    audioLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor")

    // Continuous rotation for outer tech ring
    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_ring_rot"
    )

    // Reverse rotation for inner segmented ring
    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_ring_rot"
    )

    // Pulse for core glow
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    // Determine theme accent depending on status
    val baseColor = when (status) {
        AssistantStatus.WAKE_WORD_LISTENING -> JarvisCyanPrimary
        AssistantStatus.ACTIVE_LISTENING -> JarvisCyanLight
        AssistantStatus.PROCESSING -> JarvisAmberAccent
        AssistantStatus.SPEAKING -> JarvisCyanLight
        AssistantStatus.PAUSED -> Color(0xFF6A859E)
        AssistantStatus.ERROR -> JarvisRedAlert
    }

    Box(
        modifier = modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f - 8f

            // Dynamic expansion based on real-time audio volume (RMS)
            val dynamicScale = 1f + (audioLevel * 0.35f)
            val currentRadius = maxRadius * dynamicScale.coerceIn(0.9f, 1.35f)

            // 1. Ambient Background Glow Gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.35f * (0.8f + audioLevel * 0.6f)),
                        baseColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = currentRadius * 1.1f
                ),
                radius = currentRadius * 1.1f,
                center = center
            )

            // 2. Outermost Thin Tech Orbit Ring
            drawCircle(
                color = baseColor.copy(alpha = 0.35f),
                radius = currentRadius * 0.95f,
                center = center,
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            )

            // 3. Rotating Segmented Outer Arc Ring
            rotate(outerRotation, pivot = center) {
                val segments = 8
                val sweep = 360f / segments
                for (i in 0 until segments) {
                    val startAngle = i * sweep + 4f
                    drawArc(
                        color = baseColor.copy(alpha = if (i % 2 == 0) 0.85f else 0.4f),
                        startAngle = startAngle,
                        sweepAngle = sweep - 12f,
                        useCenter = false,
                        topLeft = Offset(center.x - currentRadius * 0.82f, center.y - currentRadius * 0.82f),
                        size = androidx.compose.ui.geometry.Size(currentRadius * 1.64f, currentRadius * 1.64f),
                        style = Stroke(width = 3f, cap = StrokeCap.Round)
                    )
                }
            }

            // 4. Inner Reverse Rotating Tech Nodes
            rotate(innerRotation, pivot = center) {
                val nodeCount = 12
                val nodeRadius = currentRadius * 0.65f
                for (i in 0 until nodeCount) {
                    val angleRad = Math.toRadians((i * (360f / nodeCount)).toDouble())
                    val nodeX = center.x + (nodeRadius * cos(angleRad)).toFloat()
                    val nodeY = center.y + (nodeRadius * sin(angleRad)).toFloat()

                    drawCircle(
                        color = if (i % 3 == 0) baseColor else baseColor.copy(alpha = 0.45f),
                        radius = if (i % 3 == 0) 3.5f else 2f,
                        center = Offset(nodeX, nodeY)
                    )
                }
            }

            // 5. Middle Solid Precision Ring
            drawCircle(
                color = baseColor.copy(alpha = 0.6f),
                radius = currentRadius * 0.48f,
                center = center,
                style = Stroke(width = 2f)
            )

            // 6. Central Arc Reactor Core
            val coreRadius = currentRadius * 0.32f * corePulse * (1f + audioLevel * 0.25f)

            // Core Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        baseColor,
                        baseColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = coreRadius * 1.4f
                ),
                radius = coreRadius * 1.4f,
                center = center
            )

            // Core Solid Center
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = coreRadius * 0.5f,
                center = center
            )

            // Dynamic Audio Wave Radii when active
            if (audioLevel > 0.05f || status == AssistantStatus.ACTIVE_LISTENING || status == AssistantStatus.SPEAKING || status == AssistantStatus.WAKE_WORD_LISTENING) {
                val rippleCount = 3
                for (r in 1..rippleCount) {
                    val waveRadius = currentRadius * (0.95f + (r * 0.12f * (audioLevel + 0.2f)))
                    drawCircle(
                        color = baseColor.copy(alpha = (0.4f / r) * (audioLevel + 0.3f).coerceIn(0f, 1f)),
                        radius = waveRadius,
                        center = center,
                        style = Stroke(width = 1.5f)
                    )
                }
            }
        }
    }
}
