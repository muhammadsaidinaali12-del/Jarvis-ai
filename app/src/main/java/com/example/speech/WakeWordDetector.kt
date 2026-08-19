package com.example.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WakeWordEvent {

    data class Detected(
        val inlineCommand: String? = null
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

class WakeWordDetector(
    private val context: Context,
    sensitivity: Float = DEFAULT_SENSITIVITY
) {

    companion object {

        private const val TAG =
            "JarvisWakeWord"

        const val WAKE_WORD =
            "JARVIS"

        /*
         * =====================================================
         * SENSITIVITY
         * =====================================================
         *
         * Nilai lebih kecil = lebih sensitif.
         *
         * 0.45 dipilih sebagai titik awal yang lebih responsif
         * dibanding 0.75.
         *
         * Jika terlalu banyak false trigger:
         *   naikkan ke 0.50 - 0.55
         *
         * Jika masih sulit mendeteksi:
         *   coba 0.40
         */
        const val DEFAULT_SENSITIVITY =
            0.45f

        private const val MODEL_FILE =
            "hey_jarvis_v0.1.onnx"

        private const val MODEL_NAME =
            "JARVIS"

        /*
         * Jangan terlalu lama mengunci detector setelah
         * sebuah deteksi.
         */
        private const val DETECTION_COOLDOWN_MS =
            900L
    }

    var sensitivity: Float =
        sensitivity.coerceIn(
            0.10f,
            0.90f
        )

    private val appContext =
        context.applicationContext

    private val scope =
        CoroutineScope(
            Dispatchers.Default +
                SupervisorJob()
        )

    private var engine:
        WakeWordEngine? = null

    private var detectionJob:
        Job? = null

    private var eventListener:
        ((WakeWordEvent) -> Unit)? = null

    @Volatile
    private var running =
        false

    @Volatile
    private var paused =
        false

    @Volatile
    private var muted =
        false

    @Volatile
    private var detectionInProgress =
        false

    private val _isListening =
        MutableStateFlow(false)

    val isListening:
        StateFlow<Boolean> =
        _isListening.asStateFlow()

    /*
     * =====================================================
     * START
     * =====================================================
     */

    fun start(
        onEvent: (WakeWordEvent) -> Unit
    ) {

        eventListener =
            onEvent

        running = true
        paused = false
        muted = false
        detectionInProgress = false

        startInternal()
    }

    /*
     * =====================================================
     * CREATE ENGINE
     * =====================================================
     */

    private fun createEngine():
        WakeWordEngine {

        val model =
            WakeWordModel(
                name = MODEL_NAME,
                modelPath = MODEL_FILE,
                threshold = sensitivity
            )

        return WakeWordEngine(
            context = appContext,
            models = listOf(model),
            detectionMode =
                DetectionMode.SINGLE_BEST,
            detectionCooldownMs =
                DETECTION_COOLDOWN_MS,
            scope = scope
        )
    }

    /*
     * =====================================================
     * START INTERNAL
     * =====================================================
     */

    private fun startInternal() {

        if (!running) {
            return
        }

        if (paused) {
            return
        }

        if (muted) {
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            _isListening.value =
                false

            eventListener?.invoke(
                WakeWordEvent.Error(
                    message =
                        "Izin mikrofon diperlukan untuk mendeteksi JARVIS.",
                    isRecoverable = false
                )
            )

            return
        }

        try {

            stopEngineOnly()

            detectionInProgress =
                false

            val newEngine =
                createEngine()

            engine =
                newEngine

            /*
             * Collector dibuat sebelum engine dimulai.
             */
            detectionJob =
                scope.launch {

                    newEngine.detections
                        .collect { detection ->

                            if (!running) {
                                return@collect
                            }

                            if (paused) {
                                return@collect
                            }

                            if (muted) {
                                return@collect
                            }

                            if (detectionInProgress) {
                                return@collect
                            }

                            detectionInProgress =
                                true

                            val score =
                                detection.score
                                    .coerceIn(
                                        0f,
                                        1f
                                    )

                            Log.i(
                                TAG,
                                "Wake word detected: " +
                                    "model=${detection.model.name}, " +
                                    "score=$score, " +
                                    "threshold=$sensitivity"
                            )

                            /*
                             * Berikan feedback level kepada UI.
                             */
                            eventListener?.invoke(
                                WakeWordEvent.AudioLevel(
                                    score
                                )
                            )

                            /*
                             * Hentikan wake-word engine agar
                             * microphone bisa dipakai SpeechManager.
                             */
                            stopEngineOnly()

                            /*
                             * Wake word terdeteksi.
                             *
                             * Tidak membutuhkan inline command.
                             *
                             * JarvisViewModel akan:
                             *
                             * JARVIS
                             *   ↓
                             * "Ya, Tuan."
                             *   ↓
                             * SpeechManager
                             *   ↓
                             * perintah pengguna
                             */
                            eventListener?.invoke(
                                WakeWordEvent.Detected(
                                    inlineCommand = null
                                )
                            )
                        }
                }

            newEngine.start()

            _isListening.value =
                true

            eventListener?.invoke(
                WakeWordEvent.StatusChanged(
                    isListening = true
                )
            )

            Log.i(
                TAG,
                "Wake-word detector started. " +
                    "threshold=$sensitivity"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start wake-word detector",
                e
            )

            stopEngineOnly()

            _isListening.value =
                false

            eventListener?.invoke(
                WakeWordEvent.Error(
                    message =
                        "Gagal menjalankan wake-word detector: " +
                            (e.localizedMessage
                                ?: "unknown error"),
                    isRecoverable = true
                )
            )
        }
    }

    /*
     * =====================================================
     * MUTE FOR TTS
     * =====================================================
     */

    fun muteForTts() {

        muted = true
        detectionInProgress = true

        stopEngineOnly()

        _isListening.value =
            false

        eventListener?.invoke(
            WakeWordEvent.StatusChanged(
                isListening = false
            )
        )

        Log.d(
            TAG,
            "Wake-word detector muted for TTS"
        )
    }

    /*
     * =====================================================
     * UNMUTE AFTER TTS
     * =====================================================
     */

    fun unmuteAfterTts() {

        muted = false
        detectionInProgress = false

        if (!running) {
            return
        }

        if (paused) {
            return
        }

        startInternal()

        Log.d(
            TAG,
            "Wake-word detector resumed"
        )
    }

    /*
     * =====================================================
     * PAUSE
     * =====================================================
     */

    fun pause() {

        paused = true
        detectionInProgress = true

        stopEngineOnly()

        _isListening.value =
            false

        eventListener?.invoke(
            WakeWordEvent.StatusChanged(
                isListening = false
            )
        )

        Log.d(
            TAG,
            "Wake-word detector paused"
        )
    }

    /*
     * =====================================================
     * RESUME
     * =====================================================
     */

    fun resume() {

        paused = false
        detectionInProgress = false

        if (!running) {
            return
        }

        if (muted) {
            return
        }

        startInternal()

        Log.d(
            TAG,
            "Wake-word detector resumed after pause"
        )
    }

    /*
     * =====================================================
     * STOP
     * =====================================================
     */

    fun stop() {

        running = false
        paused = false
        muted = false
        detectionInProgress = true

        stopEngineOnly()

        eventListener = null

        _isListening.value =
            false

        Log.d(
            TAG,
            "Wake-word detector stopped"
        )
    }

    /*
     * =====================================================
     * STOP ENGINE ONLY
     * =====================================================
     *
     * Digunakan ketika microphone perlu diberikan kepada
     * SpeechManager atau ketika TTS sedang berbicara.
     */

    private fun stopEngineOnly() {

        detectionJob?.cancel()
        detectionJob = null

        try {

            engine?.stop()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping wake-word engine",
                e
            )
        }

        try {

            engine?.release()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error releasing wake-word engine",
                e
            )
        }

        engine = null

        _isListening.value =
            false
    }
}