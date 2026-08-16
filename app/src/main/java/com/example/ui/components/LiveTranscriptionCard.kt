package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.ui.theme.JarvisBorderCyan
import com.example.ui.theme.JarvisBorderSubtle
import com.example.ui.theme.JarvisCardBg
import com.example.ui.theme.JarvisCyanLight
import com.example.ui.theme.JarvisCyanPrimary
import com.example.ui.theme.JarvisGreenSuccess
import com.example.ui.theme.JarvisTextMuted
import com.example.ui.theme.JarvisTextPrimary
import com.example.ui.theme.JarvisTextSecondary
import com.example.viewmodel.AssistantStatus

@Composable
fun LiveTranscriptionCard(
    status: AssistantStatus,
    userSpokenText: String,
    jarvisResponseText: String,
    onReplayAudio: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when (status) {
        AssistantStatus.WAKE_WORD_LISTENING -> JarvisBorderSubtle
        AssistantStatus.ACTIVE_LISTENING -> JarvisCyanPrimary
        AssistantStatus.PROCESSING -> JarvisAmberAccent
        AssistantStatus.SPEAKING -> JarvisCyanLight
        AssistantStatus.PAUSED -> JarvisBorderSubtle.copy(alpha = 0.3f)
        AssistantStatus.ERROR -> JarvisAmberAccent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(JarvisCardBg.copy(alpha = 0.85f))
            .border(1.5.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // User Spoken Section
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "User Voice",
                        tint = JarvisCyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "PERINTAH SUARA ANDA:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 11.sp
                        ),
                        color = JarvisCyanLight
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (userSpokenText.isNotBlank()) "\"$userSpokenText\"" else "(Menunggu perintah suara...)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (userSpokenText.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.SansSerif
                    ),
                    color = if (userSpokenText.isNotBlank()) JarvisTextPrimary else JarvisTextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp)
                        .testTag("user_spoken_text")
                )
            }

            HorizontalDivider(
                color = JarvisBorderSubtle.copy(alpha = 0.4f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // JARVIS Response Section
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Jarvis Audio",
                            tint = JarvisCyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "RESPONS JARVIS:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontSize = 11.sp
                            ),
                            color = JarvisCyanPrimary
                        )
                    }

                    if (jarvisResponseText.isNotBlank()) {
                        IconButton(
                            onClick = { onReplayAudio(jarvisResponseText) },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("replay_response_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Ulangi Suara",
                                tint = JarvisCyanPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = jarvisResponseText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = JarvisTextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp)
                        .testTag("jarvis_response_text")
                )
            }
        }
    }
}
