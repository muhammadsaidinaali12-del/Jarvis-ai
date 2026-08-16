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

/**
 * Event callbacks emitted by WakeWordDetector
 */
sealed interface WakeWordEvent {
    data class Detected(val inlineCommand: String?) : WakeWordEvent
    data class AudioLevel(val level: Float) : WakeWordEvent
    data class StatusChanged(val isListening: Boolean) : WakeWordEvent
    data class Error(val message: String, val isRecoverable: Boolean = true) : WakeWordEvent
}

/**
 * On-Device Wake Word Detector for "JARVIS".
 *
 * Requirements:
 * - Low latency continuous passive listening for the keyword "JARVIS".
 * - Ignores all non-wake-word conversations (e.g. "Besok saya ada sekolah" -> No action).
 * - Detects standalone wake word ("JARVIS" -> prompt "Ya, Tuan." -> active listening).
 * - Detects inline command ("JARVIS, buka YouTube" -> extracts "buka YouTube" directly).
 * - Zero disk persistence of audio for privacy.
 * - Pauses during TTS playback to avoid audio loopback.
 */
class WakeWordDetector(
    private val context: Context,
    var sensitivity: Float = DEFAULT_SENSITIVITY
) {
    companion object {
        private const val TAG = "JarvisWakeWord"
        const val WAKE_WORD = "JARVIS"
        const val DEFAULT_SENSITIVITY = 0.75f

        // Variations and phonetic alignments for "JARVIS"
        private val WAKE_WORD_PATTERNS = listOf(
            Regex("\\b(jarvis|jar vis|djarvis|carvis|jarves|yarvis|jarviz|jar visual)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(hai|hei|halo|ok|oke)\\s+(jarvis|jar vis|djarvis)\\b", RegexOption.IGNORE_CASE)
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isPassiveRunning = false
    private var isPaused = false
    private var listener: ((WakeWordEvent) -> Unit)? = null
    private var restartAttempts = 0
    private var isMutedDuringTts = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Wake word recognizer ready")
            _isListening.value = true
            listener?.invoke(WakeWordEvent.StatusChanged(true))
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech energy detected in passive mode")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            listener?.invoke(WakeWordEvent.AudioLevel(normalized))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Passive speech utterance ended")
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Passive recognizer error code: $error")
            cleanupRecognizer()

            // In passive wake-word mode, silence/timeouts/no-match are normal.
            if (isPassiveRunning && !isPaused && !isMutedDuringTts) {
                // Schedule restart with gentle backoff to prevent tight loops
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        listener?.invoke(WakeWordEvent.Error("Izin mikrofon diperlukan untuk mendeteksi wake word.", false))
                        return
                    }
                    else -> 600L
                }
                restartPassiveListening(delayMs)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val fullText = matches?.firstOrNull()?.trim() ?: ""
            Log.d(TAG, "Passive recognized snippet: \"$fullText\"")

            if (fullText.isNotBlank()) {
                val detection = evaluateWakeWord(fullText)
                if (detection != null) {
                    Log.i(TAG, ">>> WAKE WORD TRIGGERED! Inline command: \"${detection.inlineCommand}\"")
                    cleanupRecognizer()
                    listener?.invoke(WakeWordEvent.Detected(detection.inlineCommand))
                    return
                } else {
                    Log.d(TAG, "No wake word in snippet (\"$fullText\"). Ignored completely.")
                }
            }

            cleanupRecognizer()
            if (isPassiveRunning && !isPaused && !isMutedDuringTts) {
                restartPassiveListening(200L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = partials?.firstOrNull()?.trim() ?: ""
            if (partialText.isNotBlank()) {
                val detection = evaluateWakeWord(partialText)
                if (detection != null) {
                    Log.i(TAG, ">>> WAKE WORD TRIGGERED in partial! Inline command: \"${detection.inlineCommand}\"")
                    cleanupRecognizer()
                    listener?.invoke(WakeWordEvent.Detected(detection.inlineCommand))
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    data class WakeWordMatch(val inlineCommand: String?)

    /**
     * Inspects text for the wake word "JARVIS".
     * If found, extracts any inline command that followed the wake word.
     * Returns null if wake word is NOT present.
     */
    fun evaluateWakeWord(text: String): WakeWordMatch? {
        val lower = text.lowercase(Locale.forLanguageTag("id-ID")).trim()

        for (pattern in WAKE_WORD_PATTERNS) {
            val match = pattern.find(lower)
            if (match != null) {
                val matchedEnd = match.range.last + 1
                val afterWakeWord = lower.substring(matchedEnd).trim()
                    .removePrefix(",")
                    .removePrefix(":")
                    .removePrefix("-")
                    .trim()

                val inlineCommand = if (afterWakeWord.isNotBlank() && afterWakeWord.length > 2) {
                    afterWakeWord
                } else {
                    null
                }

                return WakeWordMatch(inlineCommand = inlineCommand)
            }
        }
        return null
    }

    fun start(onEvent: (WakeWordEvent) -> Unit) {
        this.listener = onEvent
        this.isPassiveRunning = true
        this.isPaused = false
        restartAttempts = 0
        scheduleStart(0L)
    }

    private fun scheduleStart(delayMs: Long) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            mainHandler.post {
                if (isPassiveRunning && !isPaused && !isMutedDuringTts) {
                    startInternal()
                }
            }
        }
    }

    private fun startInternal() {
        try {
            cleanupRecognizer()

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                listener?.invoke(WakeWordEvent.Error("Izin mikrofon belum diberikan.", false))
                return
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                listener?.invoke(WakeWordEvent.Error("Layanan Speech Recognition tidak tersedia.", false))
                return
            }

            val appContext = context.applicationContext ?: context
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(recognitionListener)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }

            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting passive wake-word listening", e)
            cleanupRecognizer()
            restartPassiveListening(1500L)
        }
    }

    private fun restartPassiveListening(delayMs: Long) {
        if (!isPassiveRunning || isPaused || isMutedDuringTts) return
        scheduleStart(delayMs)
    }

    fun muteForTts() {
        isMutedDuringTts = true
        mainHandler.post {
            cleanupRecognizer()
            _isListening.value = false
        }
    }

    fun unmuteAfterTts() {
        isMutedDuringTts = false
        if (isPassiveRunning && !isPaused) {
            restartPassiveListening(500L)
        }
    }

    fun pause() {
        isPaused = true
        mainHandler.post {
            cleanupRecognizer()
            _isListening.value = false
            listener?.invoke(WakeWordEvent.StatusChanged(false))
        }
    }

    fun resume() {
        isPaused = false
        if (isPassiveRunning) {
            scheduleStart(100L)
        }
    }

    fun stop() {
        isPassiveRunning = false
        isPaused = false
        mainHandler.post {
            cleanupRecognizer()
            _isListening.value = false
            listener?.invoke(WakeWordEvent.StatusChanged(false))
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up passive recognizer", e)
        } finally {
            speechRecognizer = null
            _isListening.value = false
        }
    }
}
