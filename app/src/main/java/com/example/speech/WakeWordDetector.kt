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
 * Required preprocessing assets:
 * assets/melspectrogram.onnx
 * assets/embedding_model.onnx
 *
 * Alur:
 *
 * Microphone
 *      ↓
 * OpenWakeWord
 *      ↓
 * hey_jarvis_v0.1.onnx
 *      ↓
 * threshold
 *      ↓
 * WakeWordEvent.Detected
 *      ↓
 * SpeechManager mengambil alih microphone
 *
 * Catatan:
 *
 * Model yang kita gunakan adalah model "Hey JARVIS".
 * Karena itu wake word yang dideteksi secara akustik
 * adalah "Hey JARVIS", bukan sekadar kata "JARVIS".
 *
 * Tidak menggunakan SpeechRecognizer untuk wake word.
 * SpeechRecognizer hanya boleh digunakan setelah
 * wake word terdeteksi untuk mengambil command pengguna.
 */
class WakeWordDetector(
    private val context: Context,
    sensitivity: Float = DEFAULT_SENSITIVITY
) {

    companion object {

        private const val TAG = "JarvisWakeWord"

        const val WAKE_WORD = "JARVIS"

        /**
         * Threshold awal berdasarkan pengujian model
         * hey_jarvis_v0.1.onnx di Google Colab.
         *
         * Pengujian:
         *
         * "Hey JARVIS"
         * -> score maksimum sekitar 0.995
         *
         * Threshold 0.75 memberikan margin yang cukup.
         */
        const val DEFAULT_SENSITIVITY = 0.75f

        private const val MODEL_FILE =
            "hey_jarvis_v0.1.onnx"

        private const val MODEL_NAME =
            "JARVIS"

        /**
         * Mencegah deteksi berulang terlalu cepat.
         */
        private const val DETECTION_COOLDOWN_MS =
            2000L
    }

    /**
     * Threshold wake word.
     *
     * Semakin kecil:
     * semakin sensitif.
     *
     * Semakin besar:
     * semakin ketat.
     */
    var sensitivity: Float =
        sensitivity.coerceIn(
            0.05f,
            0.99f
        )

    private val applicationContext =
        context.applicationContext

    /**
     * Scope khusus untuk OpenWakeWord.
     */
    private val scope =
        CoroutineScope(
            Dispatchers.Default +
                    SupervisorJob()
        )

    private var engine:
            WakeWordEngine? = null

    private var detectionJob:
            Job? = null

    private var listener:
            ((WakeWordEvent) -> Unit)? = null

    @Volatile
    private var isPassiveRunning =
        false

    @Volatile
    private var isPaused =
        false

    @Volatile
    private var isMutedDuringTts =
        false

    /**
     * Mencegah satu wake word menghasilkan
     * beberapa event.
     */
    @Volatile
    private var detectionAlreadySent =
        false

    private val _isListening =
        MutableStateFlow(false)

    val isListening:
            StateFlow<Boolean> =
        _isListening.asStateFlow()

    /**
     * Membuat OpenWakeWord engine.
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
     * Memulai passive wake-word detection.
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

    /**
     * Memulai engine.
     */
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

        /**
         * Pastikan permission microphone tersedia.
         */
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

            /**
             * Bersihkan engine lama terlebih dahulu.
             */
            stopEngineOnly()

            detectionAlreadySent = false

            /**
             * Buat engine baru.
             */
            val newEngine =
                createEngine()

            engine = newEngine

            /**
             * Collector HARUS dibuat sebelum
             * engine.start().
             */
            detectionJob =
                scope.launch {

                    newEngine.detections
                        .collect { detection ->

                            /**
                             * Abaikan event jika detector
                             * sedang tidak aktif.
                             */
                            if (
                                !isPassiveRunning ||
                                isPaused ||
                                isMutedDuringTts ||
                                detectionAlreadySent
                            ) {
                                return@collect
                            }

                            /**
                             * Pastikan hanya satu event
                             * dikirim untuk satu deteksi.
                             */
                            detectionAlreadySent = true

                            val score =
                                detection.score
                                    .coerceIn(
                                        0f,
                                        1f
                                    )

                            Log.i(
                                TAG,
                                "JARVIS DETECTED: " +
                                        "model=${detection.model.name}, " +
                                        "score=$score"
                            )

                            /**
                             * Score model dapat digunakan
                             * sebagai indikator level aktivitas
                             * pada UI.
                             *
                             * Ini BUKAN scores flow.
                             * Kita mengambil score langsung
                             * dari WakeWordDetection.
                             */
                            listener?.invoke(
                                WakeWordEvent.AudioLevel(
                                    score
                                )
                            )

                            /**
                             * Hentikan engine segera setelah
                             * wake word berhasil dideteksi.
                             *
                             * Ini penting agar microphone dapat
                             * diberikan kepada SpeechManager.
                             */
                            stopEngineOnly()

                            /**
                             * OpenWakeWord hanya mendeteksi
                             * wake word.
                             *
                             * Command akan ditangani oleh
                             * SpeechManager setelah event ini.
                             *
                             * inlineCommand sengaja null.
                             */
                            listener?.invoke(
                                WakeWordEvent.Detected(
                                    inlineCommand = null
                                )
                            )
                        }
                }

            /**
             * Mulai microphone + inference.
             */
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
                        "model=$MODEL_FILE, " +
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
                            e.message
                                ?: "unknown error"
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
     * setelah TTS selesai.
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
            "OpenWakeWord resumed after TTS"
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
     * Menghentikan detector sepenuhnya.
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
     * Hanya menghentikan engine.
     *
     * Tidak mengubah status passive detector.
     */
    private fun stopEngineOnly() {

        detectionJob?.cancel()
        detectionJob = null

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