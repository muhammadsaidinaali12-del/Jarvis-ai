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

    private val tag =
        "JarvisViewModel"

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

    private var isProcessingCommand =
        false

    val isRecognitionAvailable: Boolean =
        speechManager.isRecognitionAvailable

    /*
     * ---------------------------------------------------------
     * UI STATE
     * ---------------------------------------------------------
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

            val isMuted =
                values[3] as Boolean

            val spokenText =
                values[4] as String

            val responseText =
                values[5] as String

            @Suppress("UNCHECKED_CAST")
            val historyList =
                values[6] as List<DialogueItem>

            val errorMsg =
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
            SharingStarted.WhileSubscribed(5000),
            JarvisUiState()
        )

    /*
     * ---------------------------------------------------------
     * INITIALIZATION
     * ---------------------------------------------------------
     */

    init {

        observeSpeechManager()

        observeWakeWordService()

        startBackgroundVoiceServiceIfPermitted()
    }

    /*
     * ---------------------------------------------------------
     * SPEECH MANAGER
     * ---------------------------------------------------------
     */

    private fun observeSpeechManager() {

        viewModelScope.launch {

            speechManager.speechState.collect { state ->

                when (state) {

                    is SpeechState.Idle -> {
                        // Tidak melakukan apa-apa.
                    }

                    is SpeechState.Listening -> {

                        _errorMessage.value =
                            null

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
     * ---------------------------------------------------------
     * WAKE WORD SERVICE
     * ---------------------------------------------------------
     */

    private fun observeWakeWordService() {

        viewModelScope.launch {

            JarvisVoiceService
                .wakeWordEvents
                .collect { event ->

                    event?.let {
                        handleWakeWordEvent(it)
                    }
                }
        }
    }

    private fun handleWakeWordEvent(
        event: WakeWordEvent
    ) {

        when (event) {

            is WakeWordEvent.Detected -> {

                /*
                 * Wake word detector sudah mendeteksi JARVIS.
                 *
                 * Kita jangan langsung membiarkan detector
                 * terus mendengarkan ketika SpeechManager
                 * mengambil alih mikrofon.
                 */

                _errorMessage.value =
                    null

                vibratePhone()

                /*
                 * INLINE COMMAND
                 *
                 * Contoh:
                 *
                 * "JARVIS, buka kamera"
                 *
                 * Perintah langsung diproses tanpa
                 * menunggu "Ya, Tuan."
                 */

                if (
                    !event.inlineCommand
                        .isNullOrBlank()
                ) {

                    val command =
                        event.inlineCommand
                            .trim()

                    _userSpokenText.value =
                        command

                    _status.value =
                        AssistantStatus.PROCESSING

                    processUserInput(
                        command
                    )

                    return
                }

                /*
                 * STANDALONE WAKE WORD
                 *
                 * Contoh:
                 *
                 * "JARVIS"
                 *
                 * JARVIS menjawab:
                 *
                 * "Ya, Tuan."
                 *
                 * lalu mulai mendengarkan perintah.
                 */

                _status.value =
                    AssistantStatus.ACTIVE_LISTENING

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

                /*
                 * Status internal detector tidak
                 * digunakan sebagai status utama UI.
                 */
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * ACTIVE COMMAND LISTENING
     * ---------------------------------------------------------
     */

    private fun startActiveCommandListening() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        /*
         * Pastikan wake word detector tidak sedang
         * memakai mikrofon ketika SpeechManager aktif.
         *
         * Detector akan otomatis aktif kembali setelah
         * kita selesai berbicara.
         */

        muteWakeWordDetector()

        _status.value =
            AssistantStatus.ACTIVE_LISTENING

        _userSpokenText.value =
            "Mendengarkan..."

        _errorMessage.value =
            null

        speechManager.startListening()

        activeListeningTimeoutJob?.cancel()

        activeListeningTimeoutJob =
            viewModelScope.launch {

                delay(10000)

                if (
                    _status.value ==
                    AssistantStatus.ACTIVE_LISTENING
                ) {

                    speechManager.stopListening()

                    delay(300)

                    returnToWakeWordListening()
                }
            }
    }

    /*
     * ---------------------------------------------------------
     * RETURN TO WAKE WORD
     * ---------------------------------------------------------
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

            /*
             * Aktifkan kembali wake word detector.
             */

            unmuteWakeWordDetector()
        }
    }

    /*
     * ---------------------------------------------------------
     * TTS + WAKE WORD CONTROL
     * ---------------------------------------------------------
     */

    private fun speakWithWakeWordControl(
        text: String,
        onFinished: () -> Unit
    ) {

        /*
         * Matikan wake word detector sebelum TTS
         * agar JARVIS tidak mendengar suaranya sendiri.
         */

        muteWakeWordDetector()

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            /*
             * TTS selesai.
             *
             * Jangan langsung mengaktifkan detector
             * sebelum callback selesai diproses.
             */

            viewModelScope.launch {

                delay(250)

                if (
                    _status