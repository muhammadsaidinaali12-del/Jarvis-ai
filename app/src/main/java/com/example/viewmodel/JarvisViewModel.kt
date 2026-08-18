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

    private val tag = "JarvisViewModel"

    private val app =
        application.applicationContext

    private val speechManager =
        SpeechManager(app)

    private val ttsManager =
        TtsManager(app)

    private val jarvisBrain =
        JarvisBrain(app)

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
        MutableStateFlow<List<DialogueItem>>(
            emptyList()
        )

    private val _errorMessage =
        MutableStateFlow<String?>(null)

    private val _passiveAudioLevel =
        MutableStateFlow(0f)

    private var activeListeningTimeoutJob: Job? = null

    private var isProcessingCommand = false

    private var isDestroying = false

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
                            "JARVIS AKTIF // MENDENGARKAN PERINTAH..."
                        }

                    AssistantStatus.PROCESSING ->
                        "JARVIS MEMPROSES PERINTAH..."

                    AssistantStatus.SPEAKING ->
                        "JARVIS SEDANG BERBICARA..."

                    AssistantStatus.PAUSED ->
                        "JARVIS DIJEDA // MIKROFON NONAKTIF"

                    AssistantStatus.ERROR ->
                        "STATUS ERROR: ${error ?: "Kesalahan tidak diketahui"}"
                }

            val audioLevel =
                when (status) {

                    AssistantStatus.WAKE_WORD_LISTENING ->
                        passiveRms

                    AssistantStatus.ACTIVE_LISTENING ->
                        activeRms

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

        startBackgroundVoiceServiceIfPermitted()
    }

    /*
     * =========================================================
     * SPEECH MANAGER OBSERVER
     * =========================================================
     */

    private fun observeSpeechManager() {

        viewModelScope.launch {

            speechManager.speechState.collect { state ->

                if (isDestroying) {
                    return@collect
                }

                when (state) {

                    SpeechState.Idle -> {
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

                    SpeechState.Processing -> {

                        _status.value =
                            AssistantStatus.PROCESSING
                    }

                    is SpeechState.Success -> {

                        activeListeningTimeoutJob?.cancel()

                        val text =
                            state.spokenText.trim()

                        if (text.isBlank()) {
                            return@collect
                        }

                        _userSpokenText.value = text

                        processUserInput(text)
                    }

                    is SpeechState.Error -> {

                        if (
                            _status.value ==
                            AssistantStatus.PAUSED
                        ) {
                            return@collect
                        }

                        if (isProcessingCommand) {
                            return@collect
                        }

                        _errorMessage.value =
                            state.message

                        _status.value =
                            AssistantStatus.ERROR

                        speakWithWakeWordControl(
                            if (state.isAudioHardwareIssue) {
                                "Maaf Tuan, suara tidak terdengar jelas. Silakan coba lagi."
                            } else {
                                "Maaf Tuan, ${state.message}"
                            }
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

                    if (isDestroying) {
                        return@collect
                    }

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

                if (
                    _status.value ==
                    AssistantStatus.PAUSED
                ) {
                    return
                }

                if (isProcessingCommand) {
                    return
                }

                _errorMessage.value = null

                _passiveAudioLevel.value = 0f

                vibratePhone()

                /*
                 * -------------------------------------------------
                 * INLINE COMMAND
                 *
                 * "JARVIS, buka kamera"
                 * -------------------------------------------------
                 */

                val inlineCommand =
                    event.inlineCommand
                        ?.trim()

                if (!inlineCommand.isNullOrBlank()) {

                    _userSpokenText.value =
                        inlineCommand

                    _status.value =
                        AssistantStatus.PROCESSING

                    muteWakeWordDetector()

                    processUserInput(
                        inlineCommand
                    )

                    return
                }

                /*
                 * -------------------------------------------------
                 * STANDALONE WAKE WORD
                 *
                 * "JARVIS"
                 *
                 * -> "Ya, Tuan."
                 * -> mulai mendengarkan
                 * -------------------------------------------------
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
                // Status detector tidak menjadi status utama UI.
            }
        }
    }

    /*
     * =========================================================
     * ACTIVE COMMAND LISTENING
     * =========================================================
     */

    private fun startActiveCommandListening() {

        if (isDestroying) {
            return
        }

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        /*
         * WakeWordDetector dan SpeechManager tidak boleh
         * menggunakan mikrofon secara bersamaan.
         */

        muteWakeWordDetector()

        _errorMessage.value = null

        _status.value =
            AssistantStatus.ACTIVE_LISTENING

        _userSpokenText.value =
            "Mendengarkan..."

        speechManager.startListening()

        activeListeningTimeoutJob?.cancel()

        activeListeningTimeoutJob =
            viewModelScope.launch {

                delay(10000L)

                if (
                    _status.value ==
                    AssistantStatus.ACTIVE_LISTENING
                ) {

                    speechManager.stopListening()

                    delay(400L)

                    if (
                        _status.value ==
                        AssistantStatus.ACTIVE_LISTENING
                    ) {

                        speakWithWakeWordControl(
                            "Tidak ada perintah yang terdengar, Tuan."
                        ) {

                            returnToWakeWordListening()
                        }
                    }
                }
            }
    }

    /*
     * =========================================================
     * PROCESS USER COMMAND
     * =========================================================
     */

    private fun processUserInput(
        input: String
    ) {

        if (isProcessingCommand) {
            return
        }

        if (input.isBlank()) {
            returnToWakeWordListening()
            return
        }

        isProcessingCommand = true

        activeListeningTimeoutJob?.cancel()

        _status.value =
            AssistantStatus.PROCESSING

        _errorMessage.value = null

        muteWakeWordDetector()

        viewModelScope.launch {

            try {

                val response =
                    jarvisBrain.processCommand(
                        input
                    )

                if (isDestroying) {
                    return@launch
                }

                _jarvisResponseText.value =
                    response.displayText

                /*
                 * Simpan percakapan.
                 */

                val timestamp =
                    SimpleDateFormat(
                        "HH:mm",
                        Locale.forLanguageTag("id-ID")
                    ).format(Date())

                val dialogue =
                    DialogueItem(
                        userPrompt = input,
                        jarvisReply = response.displayText,
                        timestamp = timestamp,
                        executedActionTitle =
                            response.executedActionTitle
                    )

                _history.value =
                    _history.value + dialogue

                /*
                 * JARVIS menjawab melalui TTS.
                 */

                speakWithWakeWordControl(
                    response.spokenText
                ) {

                    isProcessingCommand = false

                    returnToWakeWordListening()
                }

            } catch (e: Exception) {

                isProcessingCommand = false

                _errorMessage.value =
                    e.localizedMessage
                        ?: "Terjadi kesalahan."

                _status.value =
                    AssistantStatus.ERROR

                speakWithWakeWordControl(
                    "Maaf Tuan, terjadi kesalahan saat memproses perintah."
                ) {

                    returnToWakeWordListening()
                }
            }
        }
    }

    /*
     * =========================================================
     * TTS
     * =========================================================
     */

    private fun speakWithWakeWordControl(
        text: String,
        onFinished: () -> Unit
    ) {

        if (isDestroying) {
            return
        }

        /*
         * PENTING:
         *
         * Matikan detector SEBELUM TTS.
         *
         * Ini mencegah JARVIS mendengar suara TTS-nya sendiri.
         */

        muteWakeWordDetector()

        _status.value =
            AssistantStatus.SPEAKING

        ttsManager.speak(text) {

            viewModelScope.launch {

                /*
                 * Beri sedikit waktu agar audio benar-benar
                 * selesai sebelum mikrofon diaktifkan lagi.
                 */

                delay(300L)

                if (isDestroying) {
                    return@launch
                }

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
     * RETURN TO WAKE WORD LISTENING
     * =========================================================
     */

    private fun returnToWakeWordListening() {

        if (isDestroying) {
            return
        }

        activeListeningTimeoutJob?.cancel()

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        viewModelScope.launch {

            delay(500L)

            if (isDestroying) {
                return@launch
            }

            if (
                _status.value ==
                AssistantStatus.PAUSED
            ) {
                return@launch
            }

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING

            _errorMessage.value = null

            _userSpokenText.value = ""

            _passiveAudioLevel.value = 0f

            /*
             * Hidupkan kembali wake-word detector.
             */

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
                .muteDetectorForTts(app)

        } catch (e: Exception) {
            android.util.Log.e(
                tag,
                "Failed to mute wake word detector",
                e
            )
        }
    }

    private fun unmuteWakeWordDetector() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        try {

            JarvisVoiceService
                .unmuteDetectorAfterTts(app)

        } catch (e: Exception) {
            android.util.Log.e(
                tag,
                "Failed to unmute wake word detector",
                e
            )
        }
    }

    /*
     * =========================================================
     * FOREGROUND VOICE SERVICE
     * =========================================================
     */

    private fun startBackgroundVoiceServiceIfPermitted() {

        val microphoneGranted =
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (!microphoneGranted) {

            _errorMessage.value =
                "Izin mikrofon diperlukan agar JARVIS dapat mendengarkan wake word."

            _status.value =
                AssistantStatus.ERROR

            return
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val notificationGranted =
                ContextCompat.checkSelfPermission(
                    app,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            if (!notificationGranted) {

                /*
                 * Jangan memaksa service foreground microphone
                 * berjalan sebelum izin notifikasi tersedia pada
                 * perangkat yang memerlukannya.
                 */

                _errorMessage.value =
                    "Izin notifikasi diperlukan untuk menjalankan JARVIS di latar belakang."

                _status.value =
                    AssistantStatus.ERROR

                return
            }
        }

        try {

            JarvisVoiceService
                .startService(app)

            _status.value =
                AssistantStatus.WAKE_WORD_LISTENING

            _errorMessage.value = null

        } catch (e: Exception) {

            android.util.Log.e(
                tag,
                "Failed to start JarvisVoiceService",
                e
            )

            _errorMessage.value =
                "Gagal menjalankan layanan suara JARVIS."

            _status.value =
                AssistantStatus.ERROR
        }
    }

    /*
     * =========================================================
     * PAUSE / RESUME
     * =========================================================
     */

    fun pauseJarvis() {

        activeListeningTimeoutJob?.cancel()

        speechManager.cancel()

        muteWakeWordDetector()

        ttsManager.stop()

        _status.value =
            AssistantStatus.PAUSED

        _userSpokenText.value = ""

        _passiveAudioLevel.value = 0f

        try {

            JarvisVoiceService
                .pauseService(app)

        } catch (e: Exception) {
            android.util.Log.e(
                tag,
                "Failed to pause service",
                e
            )
        }
    }

    fun resumeJarvis() {

        if (isDestroying) {
            return
        }

        _errorMessage.value = null

        _status.value =
            AssistantStatus.WAKE_WORD_LISTENING

        try {

            JarvisVoiceService
                .resumeService(app)

        } catch (e: Exception) {
            android.util.Log.e(
                tag,
                "Failed to resume service",
                e
            )
        }
    }

    /*
     * =========================================================
     * MANUAL TEXT INPUT
     * =========================================================
     */

    fun submitManualInput(
        text: String
    ) {

        val cleanText =
            text.trim()

        if (cleanText.isBlank()) {
            return
        }

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        _userSpokenText.value =
            cleanText

        processUserInput(
            cleanText
        )
    }

    /*
     * =========================================================
     * MANUAL MICROPHONE
     * =========================================================
     */

    fun startManualListening() {

        if (
            _status.value ==
            AssistantStatus.PAUSED
        ) {
            return
        }

        startActiveCommandListening()
    }

    fun stopManualListening() {

        activeListeningTimeoutJob?.cancel()

        speechManager.stopListening()
    }

    /*
     * =========================================================
     * TTS MUTE
     * =========================================================
     */

    fun toggleTtsMute(): Boolean {

        return ttsManager.toggleMute()
    }

    /*
     * =========================================================
     * VIBRATION
     * =========================================================
     */

    private fun vibratePhone() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    app.getSystemService(
                        VibratorManager::class.java
                    )

                vibratorManager
                    ?.defaultVibrator
                    ?.vibrate(
                        VibrationEffect.createOneShot(
                            80L,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )

            } else {

                @Suppress("DEPRECATION")
                val vibrator =
                    app.getSystemService(
                        Vibrator::class.java
                    )

                @Suppress("DEPRECATION")
                vibrator?.vibrate(80L)
            }

        } catch (e: Exception) {
            android.util.Log.e(
                tag,
                "Vibration failed",
                e
            )
        }
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */

    override fun onCleared() {

        isDestroying = true

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