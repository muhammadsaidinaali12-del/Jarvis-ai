package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.JarvisVoiceService
import com.example.speech.JarvisBrain
import com.example.speech.SpeechManager
import com.example.speech.SpeechState
import com.example.speech.TtsManager
import com.example.speech.WakeWordEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class AssistantStatus {
    WAKE_WORD_LISTENING,
    ACTIVE_LISTENING,
    PROCESSING,
    SPEAKING,
    PAUSED,
    ERROR
}

data class DialogueItem(
    val id: String = UUID.randomUUID().toString(),
    val userPrompt: String,
    val jarvisReply: String,
    val timestamp: String,
    val executedActionTitle: String? = null
)

data class JarvisUiState(
    val status: AssistantStatus = AssistantStatus.WAKE_WORD_LISTENING,
    val statusText: String = "JARVIS SIAGA // MENUNGGU WAKE WORD 'JARVIS'",
    val userSpokenText: String = "",
    val jarvisResponseText: String =
        "Halo Tuan, saya JARVIS V1. Panggil saya dengan mengucapkan \"JARVIS\" atau tekan tombol di bawah untuk memberikan perintah.",
    val history: List<DialogueItem> = emptyList(),
    val audioLevel: Float = 0f,
    val isTtsMuted: Boolean = false,
    val isPaused: Boolean = false,
    val errorMessage: String? = null,
    val isRecognitionAvailable: Boolean = true
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val speechManager = SpeechManager(application)
    private val ttsManager = TtsManager(application)
    private val jarvisBrain = JarvisBrain(application)

    private val _status =
        MutableStateFlow(AssistantStatus.WAKE_WORD_LISTENING)

    private val _userSpokenText =
        MutableStateFlow("")

    private val _jarvisResponseText =
        MutableStateFlow(
            "Halo Tuan, saya JARVIS V1. Panggil saya dengan mengucapkan \"JARVIS\" atau tekan tombol di bawah untuk memberikan perintah."
        )

    private val _history =
        MutableStateFlow<List<DialogueItem>>(emptyList())

    private val _errorMessage =
        MutableStateFlow<String?>(null)

    private val _passiveAudioLevel =
        MutableStateFlow(0f)

    private var activeListeningTimeoutJob: Job? = null

    val isRecognitionAvailable: Boolean =
        speechManager.isRecognitionAvailable

    val uiState: StateFlow<JarvisUiState> = combine(
        _status,
        speechManager.rmsLevel,
        _passiveAudioLevel,
        ttsManager.isMuted,
        _userSpokenText,
        _jarvisResponseText,
        _history,
        _errorMessage
    ) { params ->

        val status =
            params[0] as AssistantStatus

        val activeRms =
            params[1] as Float

        val passiveRms =
            params[2] as Float

        val isMuted =
            params[3] as Boolean

        val spokenText =
            params[4] as String

        val responseText =
            params[5] as String

        @Suppress("UNCHECKED_CAST")
        val historyList =
            params[6] as List<DialogueItem>

        val errorMsg =
            params[7] as String?

        val statusText = when (status) {

            AssistantStatus.WAKE_WORD_LISTENING ->
                "JARVIS SIAGA // MENUNGGU WAKE WORD 'JARVIS'"

            AssistantStatus.ACTIVE_LISTENING ->
                if (
                    spokenText.isNotBlank() &&
                    spokenText != "Mendengarkan..."
                ) {
                    "JARVIS AKTIF: \"$spokenText\""
                } else {
                    "JARVIS AKTIF // MENDENGARKAN PERINTAH ANDA..."
                }

            AssistantStatus.PROCESSING ->
                "JARVIS MEMPROSES PERINTAH SUARA..."

            AssistantStatus.SPEAKING ->
                "JARVIS SEDANG MENJAWAB..."

            AssistantStatus.PAUSED ->
                "JARVIS DIJEDA // MIKROFON NONAKTIF"

            AssistantStatus.ERROR ->
                "STATUS: ${errorMsg ?: "ERROR"}"
        }

        val effectiveAudioLevel = when (status) {

            AssistantStatus.ACTIVE_LISTENING ->
                activeRms

            AssistantStatus.WAKE_WORD_LISTENING ->
                passiveRms

            AssistantStatus.SPEAKING ->
                0.45f

            else ->
                0f
        }

        JarvisUiState(
            status = status,
            statusText = statusText,
            userSpokenText = spokenText,
            jarvisResponseText = responseText,
            history = historyList,
            audioLevel = effectiveAudioLevel,
            isTtsMuted = isMuted,
            isPaused = status == AssistantStatus.PAUSED,
            errorMessage = errorMsg,
            isRecognitionAvailable = isRecognitionAvailable
        )

    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        JarvisUiState()
    )

    init {

        viewModelScope.launch {

            speechManager.speechState.collect { state ->

                when (state) {

                    is SpeechState.Idle -> {
                        // Tidak melakukan apa-apa.
                    }

                    is SpeechState.Listening -> {

                        _errorMessage.value = null

                        if (state.partialText.isNotBlank()) {
                            _userSpokenText.value =
                                state.partialText
                        }
                    }

                    is SpeechState.Processing -> {

                        _status.value =
                            AssistantStatus.PROCESSING
                    }

                    is SpeechState.Success -> {

                        _userSpokenText.value =
                            state.spokenText

                        processUserInput(
                            state.spokenText
                        )
                    }

                    is SpeechState.Error -> {

                        _errorMessage.value =
                            state.message

                        _status.value =
                            AssistantStatus.ERROR

                        val shortTtsMessage =
                            if (state.isAudioHardwareIssue) {
                                "Maaf Tuan, tidak ada suara terdeteksi. Silakan coba lagi atau gunakan input manual."
                            } else {
                                "Maaf Tuan, ${state.message}"
                            }

                        ttsManager.speak(
                            shortTtsMessage
                        ) {
                            returnToWakeWordListening()
                        }
                    }
                }
            }
        }

        viewModelScope.launch {

            JarvisVoiceService.wakeWordEvents.collect { event ->

                event?.let {
                    handleWakeWordEvent(it)
                }
            }
        }

        startBackgroundVoiceServiceIfPermitted()
    }

    fun startBackgroundVoiceServiceIfPermitted() {

        val app =
            getApplication<Application>()

        if (
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            try {

                JarvisVoiceService.startService(app)

                _status.value =
                    AssistantStatus.WAKE_WORD_LISTENING

            } catch (e: Exception) {

                _errorMessage.value =
                    "Gagal memulai service suara: ${e.localizedMessage}"
            }
        }
    }

    private fun handleWakeWordEvent(
        event: WakeWordEvent
    ) {

        when (event) {

            is WakeWordEvent.Detected -> {

                vibratePhone()

                _errorMessage.value = null

                if (
                    event.inlineCommand != null &&
                    event.inlineCommand.isNotBlank()
                ) {

                    _userSpokenText.value =
                        event.inlineCommand

                    _status.value =
                        AssistantStatus.PROCESSING

                    processUserInput(
                        event.inlineCommand
                    )

                } else {

                    _status.value =
                        AssistantStatus.ACTIVE_LISTENING

                    _userSpokenText.value =
                        "JARVIS terdeteksi"

                    ttsManager.speak(
                        "Ya, Tuan."
                    ) {

                        startActiveCommandListening()
                    }
                }
            }

            is WakeWordEvent.AudioLevel -> {

                _passiveAudioLevel.value =
                    event.level
            }

            is WakeWordEvent.Error -> {

                if (!event.isRecoverable) {

                    _errorMessage.value =
                        event.message

                    _status.value =
                        AssistantStatus.ERROR
                }
            }

            is WakeWordEvent.StatusChanged -> {
                // Status detector ditangani oleh service.
            }
        }
    }

    private fun startActiveCommandListening() {

        _status.value =
            AssistantStatus.ACTIVE_LISTENING

        _userSpokenText.value =
            "Mendengarkan..."

        speechManager.startListening()

        activeListeningTimeoutJob?.cancel()

        activeListeningTimeoutJob =
            viewModelScope.launch {

                delay(8000)

                if (
                    _status.value ==
                    AssistantStatus.ACTIVE_LISTENING
                ) {

                    speechManager.stopListening()

                    returnToWakeWordListening()
                }
            }
    }

    private fun returnToWakeWordListening() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        viewModelScope.launch {

            delay(500)

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING

            _passiveAudioLevel.value =
                0f
        }
    }

    fun onSpeakButtonPressed() {

        vibratePhone()

        when (_status.value) {

            AssistantStatus.ACTIVE_LISTENING -> {

                speechManager.stopListening()
            }

            AssistantStatus.SPEAKING -> {

                ttsManager.stop()

                returnToWakeWordListening()
            }

            AssistantStatus.PAUSED -> {

                resumeJarvis()
            }

            else -> {

                _errorMessage.value = null

                startActiveCommandListening()
            }
        }
    }

    fun pauseJarvis() {

        vibratePhone()

        _status.value =
            AssistantStatus.PAUSED

        speechManager.cancel()

        ttsManager.stop()

        _passiveAudioLevel.value =
            0f

        JarvisVoiceService.pauseService(
            getApplication()
        )
    }

    fun resumeJarvis() {

        vibratePhone()

        _status.value =
            AssistantStatus.WAKE_WORD_LISTENING

        _errorMessage.value = null

        JarvisVoiceService.resumeService(
            getApplication()
        )
    }

    fun togglePauseResume() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            resumeJarvis()
        } else {
            pauseJarvis()
        }
    }

    fun cancelListening() {

        speechManager.cancel()

        returnToWakeWordListening()
    }

    fun processUserInput(input: String) {

        if (input.isBlank()) {
            return
        }

        _userSpokenText.value =
            input

        _errorMessage.value =
            null

        _status.value =
            AssistantStatus.PROCESSING

        viewModelScope.launch {

            val response =
                jarvisBrain.processCommand(input)

            _jarvisResponseText.value =
                response.displayText

            val timeFormat =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.forLanguageTag("id-ID")
                )

            val newEntry =
                DialogueItem(
                    userPrompt = input,
                    jarvisReply = response.displayText,
                    timestamp = timeFormat.format(Date()),
                    executedActionTitle =
                        response.executedActionTitle
                )

            _history.value =
                listOf(newEntry) + _history.value

            _status.value =
                AssistantStatus.SPEAKING

            ttsManager.speak(
                response.spokenText
            ) {

                returnToWakeWordListening()
            }
        }
    }

    fun replayAudio(text: String) {

        vibratePhone()

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            returnToWakeWordListening()
        }
    }

    fun toggleMute() {

        vibratePhone()

        ttsManager.toggleMute()
    }

    fun clearHistory() {

        vibratePhone()

        _history.value =
            emptyList()
    }

    fun stopSpeaking() {

        ttsManager.stop()

        returnToWakeWordListening()
    }

    private fun vibratePhone() {

        try {

            val context =
                getApplication<Application>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                val vibratorManager =
                    context.getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as? VibratorManager

                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(
                        VibrationEffect.EFFECT_CLICK
                    )
                )

            } else {

                @Suppress("DEPRECATION")
                val vibrator =
                    context.getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as? Vibrator

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(
                            35,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )

                } else {

                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(35)
                }
            }

        } catch (_: Exception) {
        }
    }

    override fun onCleared() {

        super.onCleared()

        activeListeningTimeoutJob?.cancel()

        speechManager.destroy()

        ttsManager.shutdown()

        JarvisVoiceService.stopService(
            getApplication()
        )
    }
}