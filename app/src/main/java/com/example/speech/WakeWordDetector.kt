package com.example.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface WakeWordEvent {

    data class Detected(
        val inlineCommand: String?
    ) : WakeWordEvent

    data class AudioLevel(
        val level: Float
    ) : WakeWordEvent

    data class StatusChanged(
        val isListening: Boolean
    ) : WakeWordEvent

    data class Error(
        val message: String,
        val isRecoverable: Boolean = true
    ) : WakeWordEvent
}

/**
 * Passive JARVIS wake-word detector.
 *
 * Behaviour:
 *
 * "JARVIS"
 *      -> Detected(null)
 *
 * "JARVIS, buka YouTube"
 *      -> Detected("buka YouTube")
 *
 * "Besok saya ada sekolah"
 *      -> ignored
 *
 * Catatan:
 * Android tidak memberikan akses aplikasi pihak ketiga
 * ke private system hotword engine seperti "Hey Google".
 *
 * Karena itu class ini menggunakan SpeechRecognizer
 * sebagai backend passive listening dan melakukan
 * restart session secara otomatis.
 */
class WakeWordDetector(
    private val context: Context,
    var sensitivity: Float = DEFAULT_SENSITIVITY
) {

    companion object {

        private const val TAG = "JarvisWakeWord"

        const val WAKE_WORD = "JARVIS"

        const val DEFAULT_SENSITIVITY = 0.75f

        private const val RESTART_DELAY_NORMAL = 250L
        private const val RESTART_DELAY_BUSY = 1200L
        private const val RESTART_DELAY_ERROR = 900L

        /**
         * Variasi yang masih dianggap sebagai JARVIS.
         */
        private val WAKE_WORD_REGEX = Regex(
            "\\b(" +
                    "jarvis|" +
                    "jar\\s*vis|" +
                    "djarvis|" +
                    "carvis|" +
                    "jarves|" +
                    "yarvis|" +
                    "jarviz|" +
                    "jar\\s+visual" +
                    ")\\b",
            RegexOption.IGNORE_CASE
        )

        /**
         * Contoh:
         *
         * "hai jarvis"
         * "halo jarvis"
         * "oke jarvis"
         */
        private val PREFIX_WAKE_WORD_REGEX = Regex(
            "\\b(" +
                    "hai|" +
                    "hei|" +
                    "halo|" +
                    "ok|" +
                    "oke" +
                    ")\\s+" +
                    "(" +
                    "jarvis|" +
                    "jar\\s*vis|" +
                    "djarvis|" +
                    "carvis|" +
                    "jarves|" +
                    "yarvis|" +
                    "jarviz" +
                    ")\\b",
            RegexOption.IGNORE_CASE
        )
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val scope =
        CoroutineScope(
            Dispatchers.Default + Job()
        )

    private var speechRecognizer:
            SpeechRecognizer? = null

    private var listener:
            ((WakeWordEvent) -> Unit)? = null

    @Volatile
    private var isPassiveRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var isMutedDuringTts = false

    /**
     * Mencegah satu wake word menghasilkan
     * event berkali-kali.
     */
    @Volatile
    private var detectionAlreadySent = false

    /**
     * Setiap session mempunyai generation.
     *
     * Ini mencegah coroutine restart lama
     * menyalakan recognizer setelah detector
     * sebenarnya sudah dihentikan.
     */
    @Volatile
    private var sessionGeneration = 0L

    private val _isListening =
        MutableStateFlow(false)

    val isListening:
            StateFlow<Boolean> =
        _isListening.asStateFlow()

    /**
     * RecognitionListener
     */
    private val recognitionListener =
        object : RecognitionListener {

            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                if (!isSessionValid()) {
                    return
                }

                _isListening.value = true

                listener?.invoke(
                    WakeWordEvent.StatusChanged(true)
                )

                Log.d(
                    TAG,
                    "Passive recognizer ready"
                )
            }

            override fun onBeginningOfSpeech() {

                if (!isSessionValid()) {
                    return
                }

                Log.d(
                    TAG,
                    "Speech detected while waiting for JARVIS"
                )
            }

            override fun onRmsChanged(
                rmsdB: Float
            ) {

                if (!isSessionValid()) {
                    return
                }

                val normalized =
                    ((rmsdB + 2f) / 12f)
                        .coerceIn(0f, 1f)

                listener?.invoke(
                    WakeWordEvent.AudioLevel(
                        normalized
                    )
                )
            }

            override fun onBufferReceived(
                buffer: ByteArray?
            ) {
                /*
                 * Audio buffer tidak disimpan.
                 */
            }

            override fun onEndOfSpeech() {

                Log.d(
                    TAG,
                    "Passive utterance ended"
                )
            }

            override fun onError(
                error: Int
            ) {

                if (
                    !isPassiveRunning ||
                    detectionAlreadySent
                ) {
                    return
                }

                cleanupRecognizer()

                if (
                    isPaused ||
                    isMutedDuringTts
                ) {
                    return
                }

                Log.d(
                    TAG,
                    "Passive recognizer error: $error"
                )

                when (error) {

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {

                        listener?.invoke(
                            WakeWordEvent.Error(
                                message =
                                    "Izin mikrofon diperlukan untuk mendeteksi JARVIS.",
                                isRecoverable = false
                            )
                        )
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {

                        restartPassiveListening(
                            RESTART_DELAY_BUSY
                        )
                    }

                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {

                        /*
                         * Ini normal pada passive listening.
                         *
                         * Tidak perlu menampilkan error
                         * kepada pengguna.
                         */
                        restartPassiveListening(
                            RESTART_DELAY_NORMAL
                        )
                    }

                    else -> {

                        restartPassiveListening(
                            RESTART_DELAY_ERROR
                        )
                    }
                }
            }

            override fun onResults(
                results: Bundle?
            ) {

                if (
                    !isSessionValid() ||
                    detectionAlreadySent
                ) {
                    return
                }

                val text =
                    results
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                Log.d(
                    TAG,
                    "Passive recognition: \"$text\""
                )

                val detection =
                    evaluateWakeWord(text)

                cleanupRecognizer()

                if (detection != null) {

                    detectionAlreadySent = true

                    Log.i(
                        TAG,
                        "JARVIS DETECTED: inline=${detection.inlineCommand}"
                    )

                    listener?.invoke(
                        WakeWordEvent.Detected(
                            detection.inlineCommand
                        )
                    )

                    return
                }

                /*
                 * Bukan JARVIS.
                 *
                 * Abaikan sepenuhnya.
                 */
                Log.d(
                    TAG,
                    "No wake word detected"
                )

                if (
                    isPassiveRunning &&
                    !isPaused &&
                    !isMutedDuringTts
                ) {

                    restartPassiveListening(
                        RESTART_DELAY_NORMAL
                    )
                }
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {

                if (
                    !isSessionValid() ||
                    detectionAlreadySent
                ) {
                    return
                }

                val text =
                    partialResults
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (text.isBlank()) {
                    return
                }

                Log.d(
                    TAG,
                    "Partial passive result: \"$text\""
                )

                val detection =
                    evaluateWakeWord(text)

                if (detection == null) {
                    return
                }

                /*
                 * PENTING:
                 *
                 * Jangan langsung memicu response
                 * kalau partial result hanya:
                 *
                 * "JARVIS"
                 *
                 * Karena user mungkin sebenarnya mengatakan:
                 *
                 * "JARVIS, buka YouTube"
                 *
                 * dan recognizer belum selesai mendengar.
                 *
                 * Inline command boleh langsung diproses.
                 */
                if (
                    detection.inlineCommand.isNullOrBlank()
                ) {
                    return
                }

                detectionAlreadySent = true

                cleanupRecognizer()

                Log.i(
                    TAG,
                    "JARVIS + INLINE COMMAND detected"
                )

                listener?.invoke(
                    WakeWordEvent.Detected(
                        detection.inlineCommand
                    )
                )
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
                // Tidak digunakan.
            }
        }

    data class WakeWordMatch(
        val inlineCommand: String?
    )

    /**
     * Mengecek apakah text mengandung wake word.
     */
    fun evaluateWakeWord(
        text: String
    ): WakeWordMatch? {

        val normalized =
            text
                .lowercase(
                    Locale.forLanguageTag("id-ID")
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        if (normalized.isBlank()) {
            return null
        }

        /*
         * Contoh:
         *
         * "jarvis"
         * "jarvis buka youtube"
         */
        val directMatch =
            WAKE_WORD_REGEX.find(
                normalized
            )

        if (directMatch != null) {

            return createWakeWordMatch(
                normalized,
                directMatch
            )
        }

        /*
         * Contoh:
         *
         * "halo jarvis"
         * "oke jarvis buka youtube"
         */
        val prefixMatch =
            PREFIX_WAKE_WORD_REGEX.find(
                normalized
            )

        if (prefixMatch != null) {

            val command =
                cleanInlineCommand(
                    normalized.substring(
                        prefixMatch.range.last + 1
                    )
                )

            return WakeWordMatch(
                inlineCommand = command
            )
        }

        /*
         * Tidak ada JARVIS.
         */
        return null
    }

    private fun createWakeWordMatch(
        text: String,
        match: MatchResult
    ): WakeWordMatch {

        val afterWakeWord =
            text.substring(
                match.range.last + 1
            )

        return WakeWordMatch(
            inlineCommand =
                cleanInlineCommand(
                    afterWakeWord
                )
        )
    }

    /**
     * Membersihkan command setelah wake word.
     */
    private fun cleanInlineCommand(
        text: String
    ): String? {

        val cleaned =
            text
                .trim()
                .removePrefix(",")
                .removePrefix(":")
                .removePrefix("-")
                .trim()

        return cleaned.takeIf {
            it.length >= 3
        }
    }

    /**
     * Mulai passive listening.
     */
    fun start(
        onEvent: (WakeWordEvent) -> Unit
    ) {

        mainHandler.post {

            listener = onEvent

            isPassiveRunning = true
            isPaused = false
            isMutedDuringTts = false

            detectionAlreadySent = false

            sessionGeneration++

            scheduleStart(
                delayMs = 0L,
                generation = sessionGeneration
            )
        }
    }

    private fun scheduleStart(
        delayMs: Long,
        generation: Long
    ) {

        scope.launch {

            if (delayMs > 0L) {
                delay(delayMs)
            }

            mainHandler.post {

                if (
                    generation !=
                    sessionGeneration
                ) {
                    return@post
                }

                if (
                    !isPassiveRunning ||
                    isPaused ||
                    isMutedDuringTts
                ) {
                    return@post
                }

                startInternal(
                    generation
                )
            }
        }
    }

    /**
     * Membuat satu sesi SpeechRecognizer.
     */
    private fun startInternal(
        generation: Long
    ) {

        if (
            generation !=
            sessionGeneration
        ) {
            return
        }

        if (
            !isPassiveRunning ||
            isPaused ||
            isMutedDuringTts
        ) {
            return
        }

        try {

            cleanupRecognizer()

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                listener?.invoke(
                    WakeWordEvent.Error(
                        "Izin mikrofon belum diberikan.",
                        false
                    )
                )

                return
            }

            if (
                !SpeechRecognizer
                    .isRecognitionAvailable(context)
            ) {

                listener?.invoke(
                    WakeWordEvent.Error(
                        "Layanan Speech Recognition tidak tersedia.",
                        false
                    )
                )

                return
            }

            detectionAlreadySent = false

            speechRecognizer =
                SpeechRecognizer
                    .createSpeechRecognizer(
                        context.applicationContext
                    )
                    .apply {

                        setRecognitionListener(
                            recognitionListener
                        )
                    }

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        "id-ID"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        "id-ID"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        context.packageName
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        3
                    )

                    /*
                     * Memberikan waktu yang cukup untuk:
                     *
                     * "JARVIS, buka YouTube"
                     */
                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        1200L
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1800L
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1500L
                    )
                }

            speechRecognizer?.startListening(
                intent
            )

            _isListening.value = true

            Log.d(
                TAG,
                "Passive JARVIS listening started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start passive recognition",
                e
            )

            cleanupRecognizer()

            restartPassiveListening(
                RESTART_DELAY_ERROR
            )
        }
    }

    private fun restartPassiveListening(
        delayMs: Long
    ) {

        if (
            !isPassiveRunning ||
            isPaused ||
            isMutedDuringTts
        ) {
            return
        }

        scheduleStart(
            delayMs,
            sessionGeneration
        )
    }

    /**
     * Dipanggil ketika JARVIS akan berbicara
     * atau ketika microphone harus diberikan
     * kepada SpeechManager.
     */
    fun muteForTts() {

        mainHandler.post {

            isMutedDuringTts = true

            detectionAlreadySent = true

            sessionGeneration++

            cleanupRecognizer()

            _isListening.value = false

            listener?.invoke(
                WakeWordEvent.StatusChanged(false)
            )

            Log.d(
                TAG,
                "Wake word detector muted"
            )
        }
    }

    /**
     * Mengaktifkan kembali passive listening.
     */
    fun unmuteAfterTts() {

        mainHandler.post {

            isMutedDuringTts = false

            detectionAlreadySent = false

            if (
                !isPassiveRunning ||
                isPaused
            ) {
                return@post
            }

            sessionGeneration++

            scheduleStart(
                delayMs = 350L,
                generation = sessionGeneration
            )

            Log.d(
                TAG,
                "Wake word detector resumed"
            )
        }
    }

    fun pause() {

        mainHandler.post {

            isPaused = true

            sessionGeneration++

            cleanupRecognizer()

            _isListening.value = false

            listener?.invoke(
                WakeWordEvent.StatusChanged(false)
            )
        }
    }

    fun resume() {

        mainHandler.post {

            isPaused = false

            if (
                !isPassiveRunning ||
                isMutedDuringTts
            ) {
                return@post
            }

            sessionGeneration++

            detectionAlreadySent = false

            scheduleStart(
                delayMs = 100L,
                generation = sessionGeneration
            )
        }
    }

    /**
     * Hentikan detector sepenuhnya.
     */
    fun stop() {

        mainHandler.post {

            isPassiveRunning = false
            isPaused = false
            isMutedDuringTts = false

            detectionAlreadySent = true

            sessionGeneration++

            cleanupRecognizer()

            _isListening.value = false

            listener?.invoke(
                WakeWordEvent.StatusChanged(false)
            )

            Log.d(
                TAG,
                "Wake word detector stopped"
            )
        }
    }

    private fun isSessionValid(): Boolean {

        return isPassiveRunning &&
                !isPaused &&
                !isMutedDuringTts
    }

    /**
     * Membersihkan SpeechRecognizer.
     */
    private fun cleanupRecognizer() {

        try {

            speechRecognizer
                ?.setRecognitionListener(null)

            speechRecognizer?.cancel()

            speechRecognizer?.destroy()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error cleaning up recognizer",
                e
            )

        } finally {

            speechRecognizer = null

            _isListening.value = false
        }
    }
}