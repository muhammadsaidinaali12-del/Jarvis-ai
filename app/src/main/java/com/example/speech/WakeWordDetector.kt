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
 * JARVIS passive wake-word detector.
 *
 * Behaviour:
 *
 * "JARVIS"
 *      -> Detected(null)
 *
 * "JARVIS, buka YouTube"
 *      -> Detected("buka YouTube")
 *
 * "Besok saya sekolah"
 *      -> ignored
 *
 * Important:
 * SpeechRecognizer is used only as the recognition backend.
 * It is NOT allowed to overlap with SpeechManager.
 */
class WakeWordDetector(
    private val context: Context,
    var sensitivity: Float = DEFAULT_SENSITIVITY
) {

    companion object {

        private const val TAG = "JarvisWakeWord"

        const val WAKE_WORD = "JARVIS"

        const val DEFAULT_SENSITIVITY = 0.75f

        private const val RESTART_DELAY_NORMAL = 350L
        private const val RESTART_DELAY_BUSY = 1200L
        private const val RESTART_DELAY_ERROR = 900L

        private val WAKE_WORD_REGEX = Regex(
            pattern = """
                \b(
                    jarvis|
                    jar\s*vis|
                    djarvis|
                    carvis|
                    jarves|
                    yarvis|
                    jarviz|
                    jar\s+visual
                )\b
            """.trimIndent(),
            option = RegexOption.IGNORE_CASE
        )

        private val PREFIX_WAKE_WORD_REGEX = Regex(
            pattern = """
                \b(
                    hai|
                    hei|
                    halo|
                    ok|
                    oke
                )\s+
                (
                    jarvis|
                    jar\s*vis|
                    djarvis|
                    carvis|
                    jarves|
                    yarvis|
                    jarviz
                )\b
            """.trimIndent(),
            option = RegexOption.IGNORE_CASE
        )
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val scope =
        CoroutineScope(
            Dispatchers.Default + Job()
        )

    private var speechRecognizer: SpeechRecognizer? =
        null

    private var listener:
        ((WakeWordEvent) -> Unit)? = null

    @Volatile
    private var isPassiveRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var isMutedDuringTts = false

    /**
     * Prevents multiple callbacks from the same
     * recognition session.
     */
    @Volatile
    private var detectionAlreadySent = false

    /**
     * Used to invalidate old scheduled restart jobs.
     */
    @Volatile
    private var sessionGeneration = 0L

    private val _isListening =
        MutableStateFlow(false)

    val isListening: StateFlow<Boolean> =
        _isListening.asStateFlow()

    private val recognitionListener =
        object : RecognitionListener {

            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                if (!isSessionValid()) {
                    return
                }

                Log.d(
                    TAG,
                    "Passive recognizer ready"
                )

                _isListening.value = true

                listener?.invoke(
                    WakeWordEvent.StatusChanged(true)
                )
            }

            override fun onBeginningOfSpeech() {

                if (!isSessionValid()) {
                    return
                }

                Log.d(
                    TAG,
                    "Voice detected while waiting for JARVIS"
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
                // Audio is never persisted.
            }

            override fun onEndOfSpeech() {

                Log.d(
                    TAG,
                    "Passive recognition utterance ended"
                )
            }

            override fun onError(
                error: Int
            ) {

                if (!isPassiveRunning) {
                    return
                }

                if (detectionAlreadySent) {
                    return
                }

                Log.d(
                    TAG,
                    "Passive recognizer error: $error"
                )

                cleanupRecognizer()

                if (
                    isPaused ||
                    isMutedDuringTts
                ) {
                    return
                }

                when (error) {

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {

                        listener?.invoke(
                            WakeWordEvent.Error(
                                message =
                                    "Izin mikrofon diperlukan untuk mendeteksi JARVIS.",
                                isRecoverable = false
                            )
                        )

                        return
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {

                        restartPassiveListening(
                            RESTART_DELAY_BUSY
                        )
                    }

                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {

                        restartPassiveListening(
                            RESTART_DELAY_NORMAL
                        )
                    }

                    SpeechRecognizer.ERROR_CLIENT -> {

                        restartPassiveListening(
                            RESTART_DELAY_ERROR
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

                if (!isSessionValid()) {
                    return
                }

                if (detectionAlreadySent) {
                    return
                }

                val matches =
                    results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                val text =
                    matches
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
                        "JARVIS DETECTED. command=${detection.inlineCommand}"
                    )

                    listener?.invoke(
                        WakeWordEvent.Detected(
                            detection.inlineCommand
                        )
                    )

                    return
                }

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

                if (!isSessionValid()) {
                    return
                }

                if (detectionAlreadySent) {
                    return
                }

                val partials =
                    partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                val text =
                    partials
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (text.isBlank()) {
                    return
                }

                val detection =
                    evaluateWakeWord(text)

                if (detection != null) {

                    detectionAlreadySent = true

                    Log.i(
                        TAG,
                        "JARVIS detected from partial result: \"$text\""
                    )

                    cleanupRecognizer()

                    listener?.invoke(
                        WakeWordEvent.Detected(
                            detection.inlineCommand
                        )
                    )
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
                // Not required.
            }
        }

    data class WakeWordMatch(
        val inlineCommand: String?
    )

    /**
     * Determines whether the supplied text contains
     * the JARVIS wake word.
     *
     * Examples:
     *
     * JARVIS
     * -> WakeWordMatch(null)
     *
     * JARVIS buka YouTube
     * -> WakeWordMatch("buka YouTube")
     *
     * Hai JARVIS buka kamera
     * -> WakeWordMatch("buka kamera")
     *
     * Besok saya sekolah
     * -> null
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

        val directMatch =
            WAKE_WORD_REGEX.find(normalized)

        if (directMatch != null) {

            return createWakeWordMatch(
                normalized,
                directMatch
            )
        }

        val prefixMatch =
            PREFIX_WAKE_WORD_REGEX.find(normalized)

        if (prefixMatch != null) {

            val afterPrefix =
                normalized
                    .substring(
                        prefixMatch.range.last + 1
                    )
                    .trim()

            val command =
                cleanInlineCommand(
                    afterPrefix
                )

            return WakeWordMatch(
                inlineCommand =
                    command
            )
        }

        return null
    }

    private fun createWakeWordMatch(
        text: String,
        match: MatchResult
    ): WakeWordMatch {

        val afterWakeWord =
            text
                .substring(
                    match.range.last + 1
                )
                .trim()

        val command =
            cleanInlineCommand(
                afterWakeWord
            )

        return WakeWordMatch(
            inlineCommand = command
        )
    }

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

        if (
            cleaned.isBlank() ||
            cleaned.length < 3
        ) {
            return null
        }

        return cleaned
    }

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

            scheduleStartInternal(
                delayMs = 0L,
                generation = sessionGeneration
            )
        }
    }

    private fun scheduleStartInternal(
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

            val appContext =
                context.applicationContext

            speechRecognizer =
                SpeechRecognizer
                    .createSpeechRecognizer(
                        appContext
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
                     * Short recognition windows are intentional.
                     *
                     * We restart after the utterance ends
                     * instead of keeping one SpeechRecognizer
                     * alive indefinitely.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        1000L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1200L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        900L
                    )
                }

            speechRecognizer?.startListening(
                intent
            )

            _isListening.value = true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start passive recognition",
                e
            )

            cleanupRecognizer()

            if (
                isPassiveRunning &&
                !isPaused &&
                !isMutedDuringTts
            ) {

                restartPassiveListening(
                    RESTART_DELAY_ERROR
                )
            }
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

        val generation =
            sessionGeneration

        scheduleStartInternal(
            delayMs,
            generation
        )
    }

    fun muteForTts() {

        mainHandler.post {

            isMutedDuringTts = true

            sessionGeneration++

            detectionAlreadySent = true

            cleanupRecognizer()

            _isListening.value = false

            Log.d(
                TAG,
                "Wake-word detector muted"
            )
        }
    }

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

            val generation =
                sessionGeneration

            scheduleStartInternal(
                450L,
                generation
            )

            Log.d(
                TAG,
                "Wake-word detector resumed"
            )
        }
    }

    fun pause() {

        mainHandler.post {

            isPaused = true

            sessionGeneration++

            detectionAlreadySent = true

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

            if (!isPassiveRunning) {
                return@post
            }

            sessionGeneration++

            detectionAlreadySent = false

            val generation =
                sessionGeneration

            scheduleStartInternal(
                150L,
                generation
            )
        }
    }

    fun stop() {

        mainHandler.post {

            isPassiveRunning = false
            isPaused = false
            isMutedDuringTts = false

            sessionGeneration++

            detectionAlreadySent = true

            cleanupRecognizer()

            _isListening.value = false

            listener?.invoke(
                WakeWordEvent.StatusChanged(false)
            )

            listener = null

            Log.d(
                TAG,
                "Wake-word detector stopped"
            )
        }
    }

    private fun isSessionValid(): Boolean {

        return isPassiveRunning &&
            !isPaused &&
            !isMutedDuringTts
    }

    private fun cleanupRecognizer() {

        try {

            speechRecognizer
                ?.setRecognitionListener(null)

        } catch (_: Exception) {
        }

        try {

            speechRecognizer
                ?.cancel()

        } catch (_: Exception) {
        }

        try {

            speechRecognizer
                ?.destroy()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error destroying recognizer",
                e
            )
        }

        speechRecognizer = null

        _isListening.value = false
    }
}