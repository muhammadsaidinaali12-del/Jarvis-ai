package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ArcReactorVisualizer
import com.example.ui.components.AudioWaveEqualizer
import com.example.ui.components.DialogueHistoryView
import com.example.ui.components.JarvisHeader
import com.example.ui.components.LiveTranscriptionCard
import com.example.ui.components.ManualInputDialog
import com.example.ui.components.QuickPromptChips
import com.example.ui.components.SpeakButton
import com.example.ui.theme.JarvisAmberAccent
import com.example.ui.theme.JarvisBlack
import com.example.ui.theme.JarvisBorderSubtle
import com.example.ui.theme.JarvisCyanLight
import com.example.ui.theme.JarvisCyanPrimary
import com.example.ui.theme.JarvisDarkNavy
import com.example.ui.theme.JarvisGreenSuccess
import com.example.ui.theme.JarvisRedAlert
import com.example.ui.theme.JarvisSurfaceNavy
import com.example.viewmodel.AssistantStatus
import com.example.viewmodel.JarvisViewModel
import kotlinx.coroutines.launch

@Composable
fun JarvisMainScreen(
    viewModel: JarvisViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showManualInputDialog by remember { mutableStateOf(false) }

    // Multi-Permission launcher for RECORD_AUDIO and POST_NOTIFICATIONS
    val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val isAudioGranted = permissionsMap[Manifest.permission.RECORD_AUDIO] == true
        if (isAudioGranted) {
            viewModel.startBackgroundVoiceServiceIfPermitted()
            viewModel.onSpeakButtonPressed()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Izin mikrofon diperlukan untuk mendengarkan wake word 'JARVIS'.")
            }
        }
    }

    val handleSpeakClick: () -> Unit = {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            viewModel.onSpeakButtonPressed()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisBlack),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = JarvisBlack
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            JarvisDarkNavy.copy(alpha = 0.95f),
                            Color(0xFF030A18),
                            JarvisBlack
                        ),
                        radius = 1200f
                    )
                )
                .padding(paddingValues)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top HUD Bar with Pause/Resume, Mute, Keyboard, Clear History
                JarvisHeader(
                    status = uiState.status,
                    isTtsMuted = uiState.isTtsMuted,
                    isPaused = uiState.isPaused,
                    onTogglePauseResume = { viewModel.togglePauseResume() },
                    onToggleMute = { viewModel.toggleMute() },
                    onOpenManualInput = { showManualInputDialog = true },
                    onClearHistory = { viewModel.clearHistory() }
                )

                // Arc Reactor Core Visualizer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ArcReactorVisualizer(
                        status = uiState.status,
                        audioLevel = uiState.audioLevel
                    )
                }

                // Status Badge & Audio Waveform Equalizer
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Futuristic Status Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(JarvisSurfaceNavy.copy(alpha = 0.9f))
                            .border(1.dp, JarvisBorderSubtle, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = uiState.statusText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.1.sp
                            ),
                            color = when (uiState.status) {
                                AssistantStatus.WAKE_WORD_LISTENING -> JarvisGreenSuccess
                                AssistantStatus.ACTIVE_LISTENING -> JarvisCyanLight
                                AssistantStatus.PROCESSING -> JarvisAmberAccent
                                AssistantStatus.SPEAKING -> JarvisCyanLight
                                AssistantStatus.PAUSED -> Color(0xFF90A4AE)
                                AssistantStatus.ERROR -> JarvisRedAlert
                            },
                            textAlign = TextAlign.Center
                        )
                    }

                    // Dynamic Audio Equalizer
                    AudioWaveEqualizer(
                        status = uiState.status,
                        audioLevel = uiState.audioLevel
                    )
                }

                // Prominent Action Button (BICARA / PAUSE / RESUME)
                SpeakButton(
                    status = uiState.status,
                    onClick = handleSpeakClick
                )

                // Wake Word Passive Guide Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(JarvisSurfaceNavy.copy(alpha = 0.4f))
                        .border(1.dp, JarvisBorderSubtle.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "Deteksi Wake Word",
                            tint = JarvisCyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Panggil \"JARVIS, jam berapa?\" atau panggil \"JARVIS\" saja lalu tunggu \"Ya, Tuan.\"",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.SansSerif
                            ),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                // Diagnostic Info Banner when Audio/Speech issue occurs
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.errorMessage?.let { error ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(JarvisRedAlert.copy(alpha = 0.15f))
                                .border(1.dp, JarvisRedAlert.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Diagnostik Suara",
                                        tint = JarvisAmberAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "DIAGNOSTIK AUDIO / MIKROFON:",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            letterSpacing = 0.8.sp
                                        ),
                                        color = JarvisAmberAccent
                                    )
                                }
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }

                // Live Transcription & Response Card
                LiveTranscriptionCard(
                    status = uiState.status,
                    userSpokenText = uiState.userSpokenText,
                    jarvisResponseText = uiState.jarvisResponseText,
                    onReplayAudio = { text -> viewModel.replayAudio(text) }
                )

                // Quick Prompt Preset Chips
                QuickPromptChips(
                    onPromptSelected = { prompt ->
                        viewModel.processUserInput(prompt)
                    }
                )

                // Collapsible Conversation History
                DialogueHistoryView(
                    history = uiState.history,
                    onReplay = { text -> viewModel.replayAudio(text) }
                )

                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // Manual Input Dialog
        if (showManualInputDialog) {
            ManualInputDialog(
                onDismiss = { showManualInputDialog = false },
                onSubmit = { text ->
                    viewModel.processUserInput(text)
                }
            )
        }
    }
}
