package com.example.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val tag = "JarvisTtsManager"
    private var tts: TextToSpeech? = null
    private val completionCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize TTS engine", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsEngine = tts
            if (ttsEngine != null) {
                // Apply centralized JARVIS Voice configuration (male voice, calm pitch, rate, volume)
                JarvisVoiceConfig.configureVoice(ttsEngine)

                ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(tag, "TTS started speaking: $utteranceId")
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(tag, "TTS finished speaking: $utteranceId")
                        _isSpeaking.value = false
                        utteranceId?.let { id ->
                            completionCallbacks.remove(id)?.invoke()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(tag, "TTS error on utterance: $utteranceId")
                        _isSpeaking.value = false
                        utteranceId?.let { id ->
                            completionCallbacks.remove(id)?.invoke()
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(tag, "TTS error ($errorCode) on utterance: $utteranceId")
                        _isSpeaking.value = false
                        utteranceId?.let { id ->
                            completionCallbacks.remove(id)?.invoke()
                        }
                    }
                })

                _isReady.value = true
                Log.d(tag, "TTS initialized successfully with JARVIS voice profile")
            }
        } else {
            Log.e(tag, "TTS initialization failed with status: $status")
            _isReady.value = false
        }
    }

    fun speak(text: String, onCompleted: (() -> Unit)? = null) {
        if (_isMuted.value) {
            Log.d(tag, "TTS is muted, skipping speech output")
            onCompleted?.invoke()
            return
        }

        val cleanedText = cleanTextForSpeech(text)
        if (cleanedText.isBlank()) {
            onCompleted?.invoke()
            return
        }

        val utteranceId = "JARVIS_UTT_${System.currentTimeMillis()}"
        if (onCompleted != null) {
            completionCallbacks[utteranceId] = onCompleted
        }

        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, JarvisVoiceConfig.JARVIS_VOLUME)
        }

        try {
            _isSpeaking.value = true
            val result = tts?.speak(
                cleanedText,
                TextToSpeech.QUEUE_FLUSH,
                params,
                utteranceId
            )
            if (result != TextToSpeech.SUCCESS) {
                Log.w(tag, "TTS speak returned code: $result")
                _isSpeaking.value = false
                completionCallbacks.remove(utteranceId)?.invoke()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error invoking TTS speak", e)
            _isSpeaking.value = false
            completionCallbacks.remove(utteranceId)?.invoke()
        }
    }

    fun stop() {
        try {
            tts?.stop()
            _isSpeaking.value = false
        } catch (e: Exception) {
            Log.e(tag, "Error stopping TTS", e)
        }
    }

    fun toggleMute(): Boolean {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        if (newMuted) {
            stop()
        }
        return newMuted
    }

    private fun cleanTextForSpeech(raw: String): String {
        // Remove markdown symbols, code fences, emoji characters that sound strange when spoken
        return raw
            .replace(Regex("```[a-zA-Z]*"), "")
            .replace("```", "")
            .replace(Regex("[#*_~`\\[\\](){}>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(tag, "Error shutting down TTS", e)
        }
    }
}
