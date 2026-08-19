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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * JARVIS Wake Word Detector
 *
 * Backend:
 * OpenWakeWord Android / ONNX Runtime
 *
 * Model:
 * assets/hey_jarvis_v0.1.onnx
 *
 * Preprocessor:
 * assets/melspectrogram.onnx
 * assets/embedding_model.onnx
 *
 * Alur:
 *
 * Microphone
 *      ↓
 * OpenWakeWord
 *      ↓
 * hey_jarvis_v0.1
 *      ↓
 * threshold
 *      ↓
 * WakeWordEvent.Detected
 *      ↓
 * SpeechManager mengambil alih microphone
 */
class WakeWordDetector(
    private val context: Context,
    sensitivity: Float = DEFAULT_SENSITIVITY
) {

    companion object {

        private const val TAG = "JarvisWakeWord"

        const val WAKE_WORD = "JARVIS"

        /**
         * Threshold awal berdasarkan pengujian
         * model hey_jarvis_v0.1.onnx di Colab.
         */
        const val DEFAULT_SENSITIVITY = 0.75f

        private const val MODEL_FILE =
            "hey_jarvis_v0.1.onnx"

        private const val MODEL_NAME =
            "JARVIS"

        private const val DETECTION_COOLDOWN_MS =
            2000L
    }

    /**
     * Threshold yang dapat diubah.
     *
     * Nilai lebih kecil:
     * lebih sensitif tetapi berpotensi lebih banyak
     * false positive.
     *
     * Nilai lebih besar:
     * lebih ketat tetapi membutuhkan ucapan
     * yang lebih jelas.
     */
    var sensitivity: Float =
        sensitivity.coerceIn(0.05f, 0.99f)

    private val applicationContext =
        context.applicationContext

    private val scope =
        CoroutineScope(
            Dispatchers.Default + SupervisorJob()
        )

    private var engine:
            WakeWordEngine? = null

    private var detectionJob:
            Job? = null

    private var scoreJob:
            Job? = null

    private var listener:
            ((WakeWordEvent) -> Unit)? = null

    @Volatile
    private var isPassiveRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var isMutedDuringTts = false

    @Volatile
    private var detectionAlreadySent = false

    private val _isListening =
        MutableStateFlow(false)

    val isListening:
            StateFlow<Boolean> =
        _isListening.asStateFlow()

    /**
     * Membuat OpenWakeWord engine.
     */
    private fun createEngine(): WakeWordEngine {

        val model =
            WakeWordModel(
                name = MODEL_NAME,
                modelPath = MODEL_FILE,
                threshold = sensitivity
            )

        return WakeWordEngine(
            context = applicationContext,
            models = listOf(model),
            detectionMode =
                DetectionMode.SINGLE_BEST,
            detectionCooldownMs =
                DETECTION_COOLDOWN_MS,
            scope = scope
        )
    }

    /**
     * Mulai passive wake-word detection.
     */
    fun start(
        onEvent: (WakeWordEvent) -> Unit
    ) {

        listener = onEvent

        isPassiveRunning = true
        isPaused = false
        isMutedDuringTts = false
        detectionAlreadySent = false

        startInternal()
    }

    private fun startInternal() {

        if (!isPassiveRunning) {
            return
        }

        if (isPaused) {
            return
        }

        if (isMutedDuringTts) {
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            _isListening.value = false

            listener?.invoke(
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

            detectionAlreadySent = false

            val newEngine =
                createEngine()

            engine = newEngine

            /*
             * Collector detection harus dibuat
             * SEBELUM engine.start().
             */
            detectionJob =
                scope.launch {

                    newEngine.detections
                        .catch { error ->

                            Log.e(
                                TAG,
                                "Wake-word detection flow error",
                                error
                            )

                            if (
                                isPassiveRunning &&
                                !isPaused &&
                                !isMutedDuringTts
                            ) {

                                listener?.invoke(
                                    WakeWordEvent.Error(
                                        message =
                                            "Wake-word engine error: ${
                                                error.message
                                                    ?: "unknown error"
                                            }",
                                        isRecoverable = true
                                    )
                                )
                            }

                        }
                        .collect { detection ->

                            if (
                                !isPassiveRunning ||
                                isPaused ||
                                isMutedDuringTts ||
                                detectionAlreadySent
                            ) {
                                return@collect
                            }

                            detectionAlreadySent = true

                            Log.i(
                                TAG,
                                "JARVIS DETECTED: " +
                                        "score=${detection.score}"
                            )

                            /*
                             * Jangan kirim inline command.
                             *
                             * OpenWakeWord hanya bertugas
                             * mendeteksi wake word.
                             *
                             * Setelah ini SpeechManager
                             * mengambil alih microphone
                             * untuk mendengarkan command.
                             */
                            listener?.invoke(
                                WakeWordEvent.Detected(
                                    inlineCommand = null
                                )
                            )
                        }
                }

            /*
             * Score digunakan untuk visualisasi
             * audio level/status pada UI.
             */
            scoreJob =
                scope.launch {

                    newEngine.scores
                        .catch { error ->

                            Log.e(
                                TAG,
                                "Wake-word score flow error",
                                error
                            )

                        }
                        .collect { score ->

                            if (
                                !isPassiveRunning ||
                                isPaused ||
                                isMutedDuringTts
                            ) {
                                return@collect
                            }

                            /*
                             * Score bukan RMS audio sebenarnya,
                             * tetapi sangat berguna sebagai
                             * indikator aktivitas/confidence
                             * model.
                             */
                            val level =
                                score.score
                                    .coerceIn(
                                        0f,
                                        1f
                                    )

                            listener?.invoke(
                                WakeWordEvent.AudioLevel(
                                    level
                                )
                            )
                        }
                }

            newEngine.start()

            _isListening.value = true

            listener?.invoke(
                WakeWordEvent.StatusChanged(
                    true
                )
            )

            Log.i(
                TAG,
                "OpenWakeWord started. " +
                        "Model=$MODEL_FILE " +
                        "threshold=$sensitivity"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start OpenWakeWord",
                e
            )

            _isListening.value = false

            stopEngineOnly()

            listener?.invoke(
                WakeWordEvent.Error(
                    message =
                        "Gagal memulai wake-word engine: ${
                            e.message ?: "unknown error"
                        }",
                    isRecoverable = true
                )
            )
        }
    }

    /**
     * Dipanggil ketika JARVIS akan berbicara
     * atau SpeechManager mengambil microphone.
     */
    fun muteForTts() {

        isMutedDuringTts = true
        detectionAlreadySent = true

        stopEngineOnly()

        _isListening.value = false

        listener?.invoke(
            WakeWordEvent.StatusChanged(
                false
            )
        )

        Log.d(
            TAG,
            "OpenWakeWord muted"
        )
    }

    /**
     * Aktifkan kembali wake-word detection
     * setelah TTS / active listening selesai.
     */
    fun unmuteAfterTts() {

        isMutedDuringTts = false
        detectionAlreadySent = false

        if (
            !isPassiveRunning ||
            isPaused
        ) {
            return
        }

        startInternal()

        Log.d(
            TAG,
            "OpenWakeWord resumed"
        )
    }

    /**
     * Pause detector.
     */
    fun pause() {

        isPaused = true

        stopEngineOnly()

        _isListening.value = false

        listener?.invoke(
            WakeWordEvent.StatusChanged(
                false
            )
        )

        Log.d(
            TAG,
            "OpenWakeWord paused"
        )
    }

    /**
     * Resume detector.
     */
    fun resume() {

        isPaused = false

        if (
            !isPassiveRunning ||
            isMutedDuringTts
        ) {
            return
        }

        detectionAlreadySent = false

        startInternal()

        Log.d(
            TAG,
            "OpenWakeWord resumed after pause"
        )
    }

    /**
     * Hentikan detector sepenuhnya.
     */
    fun stop() {

        isPassiveRunning = false
        isPaused = false
        isMutedDuringTts = false

        detectionAlreadySent = true

        stopEngineOnly()

        listener = null

        _isListening.value = false

        Log.d(
            TAG,
            "OpenWakeWord stopped"
        )
    }

    /**
     * Hanya menghentikan engine tanpa
     * mengubah status passive detector.
     */
    private fun stopEngineOnly() {

        detectionJob?.cancel()
        detectionJob = null

        scoreJob?.cancel()
        scoreJob = null

        try {

            engine?.stop()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping OpenWakeWord engine",
                e
            )
        }

        try {

            engine?.release()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error releasing OpenWakeWord engine",
                e
            )
        }

        engine = null

        _isListening.value = false
    }
}