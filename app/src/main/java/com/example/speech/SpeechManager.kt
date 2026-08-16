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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SpeechState {
    data object Idle : SpeechState
    data class Listening(val rmsDb: Float = 0f, val partialText: String = "") : SpeechState
    data object Processing : SpeechState
    data class Success(val spokenText: String) : SpeechState
    data class Error(val message: String, val isPermanent: Boolean = false, val isAudioHardwareIssue: Boolean = false) : SpeechState
}

class SpeechManager(private val context: Context) {

    private val tag = "JarvisSpeechManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    val isRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(tag, "onReadyForSpeech")
            _speechState.value = SpeechState.Listening(rmsDb = 0f, partialText = "")
            _rmsLevel.value = 0.1f
        }

        override fun onBeginningOfSpeech() {
            Log.d(tag, "onBeginningOfSpeech")
            _speechState.value = SpeechState.Listening(rmsDb = 0.3f, partialText = "")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Normalize dB value (usually between -2 and 10) to 0.0 .. 1.0 for visualizer
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _rmsLevel.value = normalized
            val current = _speechState.value
            if (current is SpeechState.Listening) {
                _speechState.value = current.copy(rmsDb = normalized)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(tag, "onEndOfSpeech")
            _speechState.value = SpeechState.Processing
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            _rmsLevel.value = 0f
            val isAudioIssue = error == SpeechRecognizer.ERROR_NO_MATCH || 
                               error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                               error == SpeechRecognizer.ERROR_AUDIO

            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    "Tidak ada suara terdeteksi. Pada emulator web preview, mikrofon fisik perangkat Anda mungkin tidak tersambung ke container. Anda dapat menggunakan tombol manual (keyboard) atau mengetuk chip contoh."
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    "Gangguan input audio mikrofon. Pastikan browser dan sistem mengizinkan akses mikrofon ke emulator."
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    "Layanan pengenal suara Google belum siap atau terputus sementara. Silakan coba kembali."
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    "Izin mikrofon (RECORD_AUDIO) belum diberikan. Mohon izinkan pada dialog izin."
                }
                SpeechRecognizer.ERROR_NETWORK -> {
                    "Koneksi internet bermasalah untuk Google Speech Recognition."
                }
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    "Waktu koneksi jaringan pengenal suara habis."
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    "Layanan suara sedang sibuk. Mohon tunggu beberapa detik."
                }
                SpeechRecognizer.ERROR_SERVER -> {
                    "Gangguan pada server pengenal suara Google."
                }
                else -> "Gagal mengenali suara (Kode: $error)."
            }
            Log.w(tag, "SpeechRecognizer error: $error -> $errorMessage")
            _speechState.value = SpeechState.Error(
                message = errorMessage,
                isPermanent = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                isAudioHardwareIssue = isAudioIssue
            )
            cleanupRecognizer()
        }

        override fun onResults(results: Bundle?) {
            _rmsLevel.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull()?.trim()

            if (!recognizedText.isNullOrBlank()) {
                Log.d(tag, "Recognized: $recognizedText")
                _speechState.value = SpeechState.Success(recognizedText)
            } else {
                _speechState.value = SpeechState.Error(
                    message = "Suara tidak terdengar jelas. Silakan ulangi dengan suara lebih dekat ke mikrofon.",
                    isAudioHardwareIssue = true
                )
            }
            cleanupRecognizer()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = partials?.firstOrNull()?.trim() ?: ""
            if (partialText.isNotBlank()) {
                val current = _speechState.value
                if (current is SpeechState.Listening) {
                    _speechState.value = current.copy(partialText = partialText)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening() {
        mainHandler.post {
            try {
                // Ensure previous instance is cleaned up
                cleanupRecognizer()

                // Check permission explicitly
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    _speechState.value = SpeechState.Error(
                        message = "Izin mikrofon (RECORD_AUDIO) belum diberikan.",
                        isPermanent = true
                    )
                    return@post
                }

                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _speechState.value = SpeechState.Error(
                        message = "Layanan pengenal suara Google (Speech Services) tidak aktif di lingkungan ini. Gunakan tombol keyboard manual.",
                        isPermanent = true,
                        isAudioHardwareIssue = true
                    )
                    return@post
                }

                val appContext = context.applicationContext ?: context
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(recognitionListener)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                }

                _speechState.value = SpeechState.Listening()
                speechRecognizer?.startListening(intent)
                Log.d(tag, "Started listening in Indonesian (id-ID)")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start listening", e)
                _speechState.value = SpeechState.Error(
                    message = "Gagal memulai mikrofon: ${e.localizedMessage}. Pada emulator web, gunakan input teks manual.",
                    isAudioHardwareIssue = true
                )
                cleanupRecognizer()
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping recognizer", e)
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            cleanupRecognizer()
            _rmsLevel.value = 0f
            _speechState.value = SpeechState.Idle
        }
    }

    fun resetState() {
        _speechState.value = SpeechState.Idle
        _rmsLevel.value = 0f
    }

    fun emitManualInput(text: String) {
        if (text.isNotBlank()) {
            _speechState.value = SpeechState.Success(text.trim())
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(tag, "Error destroying speech recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    fun destroy() {
        cleanupRecognizer()
    }
}
