package com.example.viewmodel

import android.Manifest
import android.app.Application
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    val status: AssistantStatus =
        AssistantStatus.WAKE_WORD_LISTENING,

    val statusText: String =
        "JARVIS SIAGA // MENUNGGU WAKE WORD 'JARVIS'",

    val userSpokenText: String = "",

    val jarvisResponseText: String =
        "Halo Tuan, saya JARVIS V1. Panggil saya dengan mengucapkan \"JARVIS\" atau tekan tombol di bawah untuk memberikan perintah.",

    val history: List<DialogueItem> =
        emptyList(),

    val audioLevel: Float = 0f,

    val isTtsMuted: Boolean = false,

    val isPaused: Boolean = false,

    val errorMessage: String? = null,

    val isRecognitionAvailable: Boolean = true
)

class JarvisViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val tag = "JarvisViewModel"

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
            "Halo Tuan, saya JARVIS V1. Panggil saya dengan mengucapkan \"JARVIS\" atau tekan tombol di bawah untuk memberikan perintah."
        )

    private val _history =
        MutableStateFlow<List<DialogueItem>>(
            emptyList()
        )

    private val _errorMessage =
        MutableStateFlow<String?>(null)

    private val _passiveAudioLevel =
        MutableStateFlow(0f)

    private var activeListeningTimeoutJob: Job? =
        null

    private var isProcessingCommand = false

    val isRecognitionAvailable: Boolean =
        speechManager.isRecognitionAvailable

    /*
     * =========================================================
     * UI STATE
     * =========================================================
     */

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
        ) { values ->

            val status =
                values[0] as AssistantStatus

            val activeRms =
                values[1] as Float

            val passiveRms =
                values[2] as Float

            val muted =
                values[3] as Boolean

            val spokenText =
                values[4] as String

            val responseText =
                values[5] as String

            @Suppress("UNCHECKED_CAST")
            val history =
                values[6] as List<DialogueItem>

            val error =
                values[7] as String?

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
                        "STATUS: ${error ?: "ERROR"}"
                }

            val audioLevel =
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
                history = history,
                audioLevel = audioLevel,
                isTtsMuted = muted,
                isPaused =
                    status == AssistantStatus.PAUSED,
                errorMessage = error,
                isRecognitionAvailable =
                    isRecognitionAvailable
            )

        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            JarvisUiState()
        )

    /*
     * =========================================================
     * INITIALIZATION
     * =========================================================
     */

    init {

        observeSpeechManager()

        observeWakeWordService()

        /*
         * Tidak memaksa service berjalan apabila permission
         * RECORD_AUDIO belum diberikan.
         */
        startBackgroundVoiceServiceIfPermitted()
    }

    /*
     * =========================================================
     * BACKGROUND VOICE SERVICE
     * =========================================================
     */

    fun startBackgroundVoiceServiceIfPermitted() {

        val context =
            getApplication<Application>()

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {

            JarvisVoiceService.startService(
                context
            )

        } catch (e: Exception) {

            _errorMessage.value =
                "Gagal menjalankan layanan suara: ${e.localizedMessage}"

            _status.value =
                AssistantStatus.ERROR
        }
    }

    /*
     * =========================================================
     * SPEECH MANAGER
     * =========================================================
     */

    private fun observeSpeechManager() {

        viewModelScope.launch {

            speechManager.speechState.collect { state ->

                when (state) {

                    is SpeechState.Idle -> {
                        // Tidak melakukan apa-apa.
                    }

                    is SpeechState.Listening -> {

                        _errorMessage.value = null

                        _status.value =
                            AssistantStatus.ACTIVE_LISTENING

                        if (
                            state.partialText.isNotBlank()
                        ) {

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

                        if (
                            _status.value ==
                            AssistantStatus.PAUSED
                        ) {
                            return@collect
                        }

                        activeListeningTimeoutJob?.cancel()

                        _errorMessage.value =
                            state.message

                        _status.value =
                            AssistantStatus.ERROR

                        val shortMessage =
                            if (
                                state.isAudioHardwareIssue
                            ) {
                                "Maaf Tuan, suara tidak terdengar jelas. Silakan coba lagi."
                            } else {
                                "Maaf Tuan, ${state.message}"
                            }

                        speakWithWakeWordControl(
                            shortMessage
                        ) {
                            returnToWakeWordListening()
                        }
                    }
                }
            }
        }
    }

    /*
     * =========================================================
     * WAKE WORD SERVICE
     * =========================================================
     */

    private fun observeWakeWordService() {

        viewModelScope.launch {

            JarvisVoiceService
                .wakeWordEvents
                .collect { event ->

                    if (event != null) {
                        handleWakeWordEvent(event)
                    }
                }
        }
    }

    private fun handleWakeWordEvent(
        event: WakeWordEvent
    ) {

        when (event) {

            is WakeWordEvent.Detected -> {

                _errorMessage.value = null

                vibratePhone()

                /*
                 * INLINE COMMAND
                 *
                 * Contoh:
                 * "JARVIS buka kamera"
                 */

                if (
                    !event.inlineCommand
                        .isNullOrBlank()
                ) {

                    val command =
                        event.inlineCommand.trim()

                    _userSpokenText.value =
                        command

                    _status.value =
                        AssistantStatus.PROCESSING

                    processUserInput(command)

                    return
                }

                /*
                 * Hanya wake word:
                 *
                 * "JARVIS"
                 *
                 * JARVIS menjawab:
                 * "Ya, Tuan."
                 */

                _userSpokenText.value =
                    "JARVIS terdeteksi"

                speakWithWakeWordControl(
                    "Ya, Tuan."
                ) {

                    if (
                        _status.value !=
                        AssistantStatus.PAUSED
                    ) {
                        startActiveCommandListening()
                    }
                }
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
                // Status detector tidak digunakan sebagai
                // status utama UI.
            }
        }
    }

    /*
     * =========================================================
     * ACTIVE COMMAND LISTENING
     * =========================================================
     */

    private fun startActiveCommandListening() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        muteWakeWordDetector()

        _status.value =
            AssistantStatus.ACTIVE_LISTENING

        _userSpokenText.value =
            "Mendengarkan..."

        _errorMessage.value = null

        activeListeningTimeoutJob?.cancel()

        speechManager.startListening()

        activeListeningTimeoutJob =
            viewModelScope.launch {

                delay(10000)

                if (
                    _status.value ==
                    AssistantStatus.ACTIVE_LISTENING
                ) {

                    speechManager.stopListening()

                    delay(300)

                    if (
                        _status.value ==
                        AssistantStatus.ACTIVE_LISTENING
                    ) {
                        returnToWakeWordListening()
                    }
                }
            }
    }

    /*
     * =========================================================
     * PROCESS COMMAND
     * =========================================================
     */

    fun processUserInput(
        input: String
    ) {

        val command =
            input.trim()

        if (command.isBlank()) {
            return
        }

        if (isProcessingCommand) {
            return
        }

        isProcessingCommand = true

        activeListeningTimeoutJob?.cancel()

        _status.value =
            AssistantStatus.PROCESSING

        _errorMessage.value = null

        viewModelScope.launch {

            try {

                val response =
                    jarvisBrain.processCommand(
                        command
                    )

                _jarvisResponseText.value =
                    response.displayText

                val timestamp =
                    SimpleDateFormat(
                        "HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date())

                val item =
                    DialogueItem(
                        userPrompt = command,
                        jarvisReply = response.displayText,
                        timestamp = timestamp,
                        executedActionTitle =
                            response.executedActionTitle
                    )

                _history.value =
                    _history.value + item

                speakWithWakeWordControl(
                    response.spokenText
                ) {

                    isProcessingCommand = false

                    if (
                        _status.value !=
                        AssistantStatus.PAUSED
                    ) {
                        returnToWakeWordListening()
                    }
                }

            } catch (e: Exception) {

                isProcessingCommand = false

                val message =
                    "Maaf Tuan, terjadi kesalahan saat memproses perintah."

                _errorMessage.value =
                    e.localizedMessage ?: message

                _jarvisResponseText.value =
                    message

                speakWithWakeWordControl(
                    message
                ) {
                    returnToWakeWordListening()
                }
            }
        }
    }

    /*
     * =========================================================
     * TTS + WAKE WORD CONTROL
     * =========================================================
     */

    private fun speakWithWakeWordControl(
        text: String,
        onFinished: () -> Unit
    ) {

        muteWakeWordDetector()

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            viewModelScope.launch {

                delay(250)

                if (
                    _status.value ==
                    AssistantStatus.PAUSED
                ) {
                    return@launch
                }

                onFinished()
            }
        }
    }

    /*
     * =========================================================
     * RETURN TO WAKE WORD
     * =========================================================
     */

    private fun returnToWakeWordListening() {

        activeListeningTimeoutJob?.cancel()

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        viewModelScope.launch {

            delay(500)

            if (
                _status.value ==
                AssistantStatus.PAUSED
            ) {
                return@launch
            }

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING

            _passiveAudioLevel.value =
                0f

            _userSpokenText.value =
                ""

            isProcessingCommand = false

            unmuteWakeWordDetector()
        }
    }

    /*
     * =========================================================
     * WAKE WORD DETECTOR CONTROL
     * =========================================================
     */

    private fun muteWakeWordDetector() {

        try {

            JarvisVoiceService
                .muteDetectorForTts(
                    getApplication()
                )

        } catch (e: Exception) {
            // Service mungkin belum aktif.
        }
    }

    private fun unmuteWakeWordDetector() {

        try {

            JarvisVoiceService
                .unmuteDetectorAfterTts(
                    getApplication()
                )

        } catch (e: Exception) {
            // Service mungkin belum aktif.
        }
    }

    /*
     * =========================================================
     * BUTTON ACTIONS
     * =========================================================
     */

    fun onSpeakButtonPressed() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        if (
            _status.value ==
            AssistantStatus.SPEAKING
        ) {
            return
        }

        startActiveCommandListening()
    }

    fun togglePauseResume() {

        val context =
            getApplication<Application>()

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {

            _errorMessage.value = null

            JarvisVoiceService.resumeService(
                context
            )

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING

            _userSpokenText.value = ""

        } else {

            activeListeningTimeoutJob?.cancel()

            speechManager.cancel()

            ttsManager.stop()

            JarvisVoiceService.pauseService(
                context
            )

            _passiveAudioLevel.value =
                0f

            _status.value =
                AssistantStatus.PAUSED

            _userSpokenText.value = ""
        }
    }

    fun toggleMute(): Boolean {

        return ttsManager.toggleMute()
    }

    fun clearHistory() {

        _history.value =
            emptyList()
    }

    fun replayAudio(
        text: String
    ) {

        if (text.isBlank()) {
            return
        }

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        muteWakeWordDetector()

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            viewModelScope.launch {

                delay(250)

                if (
                    _status.value !=
                    AssistantStatus.PAUSED
                ) {
                    returnToWakeWordListening()
                }
            }
        }
    }

    /*
     * =========================================================
     * VIBRATION
     * =========================================================
     */

    private fun vibratePhone() {

        try {

            val context =
                getApplication<Application>()

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.VIBRATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    context.getSystemService(
                        VibratorManager::class.java
                    )

                vibratorManager
                    ?.defaultVibrator
                    ?.vibrate(
                        VibrationEffect.createOneShot(
                            80,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )

            } else {

                @Suppress("DEPRECATION")
                val vibrator =
                    context.getSystemService(
                        Vibrator::class.java
                    )

                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }

        } catch (_: Exception) {
            // Vibration tidak boleh membuat JARVIS crash.
        }
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */

    override fun onCleared() {

        activeListeningTimeoutJob?.cancel()

        try {
            speechManager.destroy()
        } catch (_: Exception) {
        }

        try {
            ttsManager.shutdown()
        } catch (_: Exception) {
        }

        super.onCleared()
    }
}