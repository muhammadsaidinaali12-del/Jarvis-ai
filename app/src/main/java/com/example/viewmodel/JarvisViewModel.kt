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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    val statusText: String =
        "JARVIS SIAGA // MENUNGGU WAKE WORD 'JARVIS'",
    val userSpokenText: String = "",
    val jarvisResponseText: String =
        "Halo Tuan, saya JARVIS V1. Panggil saya dengan mengucapkan \"JARVIS\".",
    val history: List<DialogueItem> = emptyList(),
    val audioLevel: Float = 0f,
    val isTtsMuted: Boolean = false,
    val isPaused: Boolean = false,
    val errorMessage: String? = null,
    val isRecognitionAvailable: Boolean = true
)

class JarvisViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val speechManager =
        SpeechManager(application)

    private val ttsManager =
        TtsManager(application)

    private val jarvisBrain =
        JarvisBrain(application)

    private val _status =
        MutableStateFlow(
            AssistantStatus.WAKE_WORD_LISTENING
        )

    private val _userSpokenText =
        MutableStateFlow("")

    private val _jarvisResponseText =
        MutableStateFlow(
            "Halo Tuan, saya JARVIS V1. Panggil saya dengan mengucapkan \"JARVIS\"."
        )

    private val _history =
        MutableStateFlow<List<DialogueItem>>(emptyList())

    private val _errorMessage =
        MutableStateFlow<String?>(null)

    private val _passiveAudioLevel =
        MutableStateFlow(0f)

    private var activeListeningTimeoutJob: Job? = null

    private var isProcessingCommand = false

    private var isSpeaking = false

    val isRecognitionAvailable: Boolean =
        speechManager.isRecognitionAvailable

    val uiState: StateFlow<JarvisUiState> =
        combine(
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

            val statusText =
                when (status) {

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

            val effectiveAudioLevel =
                when (status) {

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
                isPaused =
                    status == AssistantStatus.PAUSED,
                errorMessage = errorMsg,
                isRecognitionAvailable =
                    isRecognitionAvailable
            )

        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            JarvisUiState()
        )

    init {

        observeSpeechManager()

        observeWakeWordEvents()

        startBackgroundVoiceServiceIfPermitted()
    }

    // ------------------------------------------------------------
    // SPEECH MANAGER
    // ------------------------------------------------------------

    private fun observeSpeechManager() {

        speechManager.speechState
            .onEach { state ->

                when (state) {

                    SpeechState.Idle -> {
                        // Tidak melakukan apa-apa.
                    }

                    is SpeechState.Listening -> {

                        if (
                            _status.value !=
                            AssistantStatus.ACTIVE_LISTENING
                        ) {
                            return@onEach
                        }

                        _errorMessage.value =
                            null

                        if (
                            state.partialText.isNotBlank()
                        ) {

                            _userSpokenText.value =
                                state.partialText
                        }
                    }

                    SpeechState.Processing -> {

                        _status.value =
                            AssistantStatus.PROCESSING
                    }

                    is SpeechState.Success -> {

                        if (
                            _status.value !=
                            AssistantStatus.ACTIVE_LISTENING
                        ) {
                            return@onEach
                        }

                        _userSpokenText.value =
                            state.spokenText

                        activeListeningTimeoutJob?.cancel()

                        processUserInput(
                            state.spokenText
                        )
                    }

                    is SpeechState.Error -> {

                        if (
                            _status.value !=
                            AssistantStatus.ACTIVE_LISTENING
                        ) {
                            return@onEach
                        }

                        activeListeningTimeoutJob?.cancel()

                        _errorMessage.value =
                            state.message

                        _status.value =
                            AssistantStatus.ERROR

                        val message =
                            if (
                                state.isAudioHardwareIssue
                            ) {
                                "Maaf Tuan, saya tidak mendengar perintah Anda."
                            } else {
                                "Maaf Tuan, ${state.message}"
                            }

                        speakAndReturnToWakeWord(
                            message
                        )
                    }
                }

            }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------
    // WAKE WORD
    // ------------------------------------------------------------

    private fun observeWakeWordEvents() {

        JarvisVoiceService.wakeWordEvents
            .onEach { event ->

                if (event != null) {
                    handleWakeWordEvent(event)
                }

            }
            .launchIn(viewModelScope)
    }

    private fun handleWakeWordEvent(
        event: WakeWordEvent
    ) {

        when (event) {

            is WakeWordEvent.Detected -> {

                // Jangan menerima wake word baru ketika JARVIS
                // sedang memproses atau berbicara.
                if (
                    _status.value ==
                    AssistantStatus.PROCESSING ||
                    _status.value ==
                    AssistantStatus.SPEAKING ||
                    _status.value ==
                    AssistantStatus.PAUSED
                ) {
                    return
                }

                vibratePhone()

                _errorMessage.value =
                    null

                // ------------------------------------------------
                // INLINE COMMAND
                //
                // "JARVIS, buka kamera"
                // ------------------------------------------------

                val inlineCommand =
                    event.inlineCommand
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }

                if (inlineCommand != null) {

                    _userSpokenText.value =
                        inlineCommand

                    processUserInput(
                        inlineCommand
                    )

                    return
                }

                // ------------------------------------------------
                // WAKE WORD ONLY
                //
                // "JARVIS"
                // ↓
                // "Ya, Tuan."
                // ↓
                // listen command
                // ------------------------------------------------

                _status.value =
                    AssistantStatus.ACTIVE_LISTENING

                _userSpokenText.value =
                    "JARVIS terdeteksi"

                speakAndThenListen(
                    "Ya, Tuan."
                )
            }

            is WakeWordEvent.AudioLevel -> {

                if (
                    _status.value ==
                    AssistantStatus.WAKE_WORD_LISTENING
                ) {
                    _passiveAudioLevel.value =
                        event.level
                }
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

                // Detector status ditangani oleh service.
            }
        }
    }

    // ------------------------------------------------------------
    // TTS
    // ------------------------------------------------------------

    private fun speakAndThenListen(
        text: String
    ) {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        isSpeaking = true

        // Matikan wake-word detector sementara agar suara
        // JARVIS tidak dianggap sebagai input pengguna.
        JarvisVoiceService.muteForTts(
            getApplication()
        )

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            isSpeaking = false

            if (
                _status.value ==
                AssistantStatus.PAUSED
            ) {
                return@speak
            }

            // Hidupkan kembali wake-word detector setelah
            // JARVIS selesai berbicara.
            JarvisVoiceService.unmuteAfterTts(
                getApplication()
            )

            startActiveCommandListening()
        }
    }

    private fun speakAndReturnToWakeWord(
        text: String
    ) {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        isSpeaking = true

        JarvisVoiceService.muteForTts(
            getApplication()
        )

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            isSpeaking = false

            if (
                _status.value ==
                AssistantStatus.PAUSED
            ) {
                return@speak
            }

            JarvisVoiceService.unmuteAfterTts(
                getApplication()
            )

            returnToWakeWordListening()
        }
    }

    // ------------------------------------------------------------
    // ACTIVE COMMAND LISTENING
    // ------------------------------------------------------------

    private fun startActiveCommandListening() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        isProcessingCommand = false

        _status.value =
            AssistantStatus.ACTIVE_LISTENING

        _userSpokenText.value =
            "Mendengarkan..."

        activeListeningTimeoutJob?.cancel()

        speechManager.startListening()

        activeListeningTimeoutJob =
            viewModelScope.launch {

                delay(8000L)

                if (
                    _status.value ==
                    AssistantStatus.ACTIVE_LISTENING
                ) {

                    speechManager.cancel()

                    JarvisVoiceService.unmuteAfterTts(
                        getApplication()
                    )

                    returnToWakeWordListening()
                }
            }
    }

    // ------------------------------------------------------------
    // PROCESS COMMAND
    // ------------------------------------------------------------

    fun processUserInput(
        input: String
    ) {

        val cleanInput =
            input.trim()

        if (
            cleanInput.isBlank() ||
            isProcessingCommand
        ) {
            return
        }

        isProcessingCommand = true

        activeListeningTimeoutJob?.cancel()

        speechManager.cancel()

        _userSpokenText.value =
            cleanInput

        _errorMessage.value =
            null

        _status.value =
            AssistantStatus.PROCESSING

        viewModelScope.launch {

            try {

                val response =
                    jarvisBrain.processCommand(
                        cleanInput
                    )

                _jarvisResponseText.value =
                    response.displayText

                val timeFormat =
                    SimpleDateFormat(
                        "HH:mm",
                        Locale.forLanguageTag("id-ID")
                    )

                val newEntry =
                    DialogueItem(
                        userPrompt =
                            cleanInput,

                        jarvisReply =
                            response.displayText,

                        timestamp =
                            timeFormat.format(
                                Date()
                            ),

                        executedActionTitle =
                            response.executedActionTitle
                    )

                _history.value =
                    listOf(newEntry) +
                    _history.value

                // Pastikan wake word detector tidak mendengar
                // jawaban JARVIS.
                JarvisVoiceService.muteForTts(
                    getApplication()
                )

                _status.value =
                    AssistantStatus.SPEAKING

                isSpeaking = true

                ttsManager.speak(
                    response.spokenText
                ) {

                    isSpeaking = false
                    isProcessingCommand = false

                    if (
                        _status.value ==
                        AssistantStatus.PAUSED
                    ) {
                        return@speak
                    }

                    JarvisVoiceService.unmuteAfterTts(
                        getApplication()
                    )

                    returnToWakeWordListening()
                }

            } catch (e: Exception) {

                isProcessingCommand = false

                _errorMessage.value =
                    e.localizedMessage
                        ?: "Terjadi kesalahan saat memproses perintah."

                speakAndReturnToWakeWord(
                    "Maaf Tuan, terjadi kesalahan saat memproses perintah."
                )
            }
        }
    }

    // ------------------------------------------------------------
    // RETURN TO WAKE WORD MODE
    // ------------------------------------------------------------

    private fun returnToWakeWordListening() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        viewModelScope.launch {

            delay(350L)

            if (
                _status.value ==
                AssistantStatus.PAUSED
            ) {
                return@launch
            }

            isProcessingCommand = false
            isSpeaking = false

            _passiveAudioLevel.value =
                0f

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING
        }
    }

    // ------------------------------------------------------------
    // BACKGROUND SERVICE
    // ------------------------------------------------------------

    fun startBackgroundVoiceServiceIfPermitted() {

        val app =
            getApplication<Application>()

        if (
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {

            JarvisVoiceService.startService(
                app
            )

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING

            _errorMessage.value =
                null

        } catch (e: Exception) {

            _errorMessage.value =
                "Gagal memulai service suara: " +
                        "${e.localizedMessage}"

            _status.value =
                AssistantStatus.ERROR
        }
    }

    // ------------------------------------------------------------
    // PAUSE / RESUME
    // ------------------------------------------------------------

    fun pauseJarvis() {

        vibratePhone()

        activeListeningTimeoutJob?.cancel()

        speechManager.cancel()

        ttsManager.stop()

        JarvisVoiceService.pauseService(
            getApplication()
        )

        _passiveAudioLevel.value =
            0f

        _status.value =
            AssistantStatus.PAUSED
    }

    fun resumeJarvis() {

        vibratePhone()

        activeListeningTimeoutJob?.cancel()

        _errorMessage.value =
            null

        isProcessingCommand = false
        isSpeaking = false

        JarvisVoiceService.resumeService(
            getApplication()
        )

        _status.value =
            AssistantStatus.WAKE_WORD_LISTENING
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

    // ------------------------------------------------------------
    // MANUAL SPEAK BUTTON
    // ------------------------------------------------------------

    fun onSpeakButtonPressed() {

        vibratePhone()

        when (_status.value) {

            AssistantStatus.ACTIVE_LISTENING -> {

                speechManager.stopListening()
            }

            AssistantStatus.SPEAKING -> {

                ttsManager.stop()

                JarvisVoiceService.unmuteAfterTts(
                    getApplication()
                )

                returnToWakeWordListening()
            }

            AssistantStatus.PAUSED -> {

                resumeJarvis()
            }

            else -> {

                _errorMessage.value =
                    null

                JarvisVoiceService.muteForTts(
                    getApplication()
                )

                startActiveCommandListening()
            }
        }
    }

    // ------------------------------------------------------------
    // CANCEL
    // ------------------------------------------------------------

    fun cancelListening() {

        activeListeningTimeoutJob?.cancel()

        speechManager.cancel()

        JarvisVoiceService.unmuteAfterTts(
            getApplication()
        )

        returnToWakeWordListening()
    }

    // ------------------------------------------------------------
    // TTS CONTROLS
    // ------------------------------------------------------------

    fun replayAudio(
        text: String
    ) {

        vibratePhone()

        JarvisVoiceService.muteForTts(
            getApplication()
        )

        _status.value =
            AssistantStatus.SPEAKING

        isSpeaking = true

        ttsManager.speak(text) {

            isSpeaking = false

            JarvisVoiceService.unmuteAfterTts(
                getApplication()
            )

            returnToWakeWordListening()
        }
    }

    fun toggleMute() {

        vibratePhone()

        val muted =
            ttsManager.toggleMute()

        if (muted) {

            JarvisVoiceService.muteForTts(
                getApplication()
            )

        } else {

            JarvisVoiceService.unmuteAfterTts(
                getApplication()
            )
        }
    }

    fun stopSpeaking() {

        ttsManager.stop()

        isSpeaking = false

        JarvisVoiceService.unmuteAfterTts(
            getApplication()
        )

        returnToWakeWordListening()
    }

    // ------------------------------------------------------------
    // HISTORY
    // ------------------------------------------------------------

    fun clearHistory() {

        vibratePhone()

        _history.value =
            emptyList()
    }

    // ------------------------------------------------------------
    // VIBRATION
    // ------------------------------------------------------------

    private fun vibratePhone() {

        try {

            val context =
                getApplication<Application>()

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    context.getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as? VibratorManager

                vibratorManager
                    ?.defaultVibrator
                    ?.vibrate(
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

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

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

    // ------------------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------------------

    override fun onCleared() {

        activeListeningTimeoutJob?.cancel()

        speechManager.destroy()

        ttsManager.shutdown()

        /*
         * Jangan menghentikan JarvisVoiceService di sini.
         *
         * ViewModel bisa dihancurkan ketika Activity dibuat ulang,
         * misalnya karena konfigurasi atau lifecycle.
         *
         * Service harus tetap hidup sebagai background voice service.
         */

        super.onCleared()
    }
}