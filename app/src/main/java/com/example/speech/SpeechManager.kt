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

/**
 * SpeechManager
 *
 * Bertugas mengambil alih microphone setelah WakeWordDetector
 * mendeteksi kata "JARVIS".
 *
 * Alur:
 *
 * WakeWordDetector
 *       ↓
 * "JARVIS"
 *       ↓
 * JarvisViewModel
 *       ↓
 * SpeechManager.startListening()
 *       ↓
 * pengguna memberikan perintah
 *       ↓
 * SpeechState.Success
 *       ↓
 * JarvisBrain
 *
 * SpeechManager TIDAK melakukan wake-word detection.
 * Wake-word detection sepenuhnya ditangani oleh WakeWordDetector.
 */
sealed interface SpeechState {

    /**
     * Tidak sedang mendengarkan.
     */
    data object Idle : SpeechState

    /**
     * Sedang mendengarkan perintah pengguna.
     */
    data class Listening(
        val rmsDb: Float = 0f,
        val partialText: String = ""
    ) : SpeechState

    /**
     * SpeechRecognizer sudah selesai menangkap suara
     * dan hasil sedang diproses.
     */
    data object Processing : SpeechState

    /**
     * Berhasil mengenali perintah.
     */
    data class Success(
        val spokenText: String
    ) : SpeechState

    /**
     * Terjadi kesalahan.
     */
    data class Error(
        val message: String,
        val isPermanent: Boolean = false,
        val isAudioHardwareIssue: Boolean = false
    ) : SpeechState
}

class SpeechManager(
    private val context: Context
) {

    companion object {

        private const val TAG = "JarvisSpeechManager"

        /**
         * Bahasa utama JARVIS.
         */
        private const val LANGUAGE = "id-ID"

        /**
         * Delay kecil sebelum memulai recognition.
         *
         * Ini memberi waktu bagi WakeWordDetector untuk
         * benar-benar melepas microphone.
         */
        private const val START_DELAY_MS = 250L

        /**
         * Berapa lama SpeechRecognizer menunggu suara mulai.
         */
        private const val MINIMUM_LENGTH_MS = 3000L

        /**
         * Jeda hening setelah pengguna selesai bicara.
         */
        private const val COMPLETE_SILENCE_MS = 1800L

        /**
         * Jeda kemungkinan selesai bicara.
         */
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 1400L
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * Token sesi.
     *
     * Digunakan untuk mencegah callback dari sesi lama
     * memengaruhi sesi baru.
     */
    private var listeningSessionId = 0L

    /**
     * Apakah manager sedang diminta untuk mendengarkan.
     */
    private var isListeningRequested = false

    private val _speechState =
        MutableStateFlow<SpeechState>(
            SpeechState.Idle
        )

    val speechState: StateFlow<SpeechState> =
        _speechState.asStateFlow()

    private val _rmsLevel =
        MutableStateFlow(0f)

    val rmsLevel: StateFlow<Float> =
        _rmsLevel.asStateFlow()

    /**
     * Apakah SpeechRecognizer tersedia pada perangkat.
     */
    val isRecognitionAvailable: Boolean
        get() = try {
            SpeechRecognizer.isRecognitionAvailable(
                context
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to check SpeechRecognizer availability",
                e
            )
            false
        }

    /**
     * RecognitionListener untuk mode ACTIVE LISTENING.
     *
     * WakeWordDetector mempunyai listener sendiri.
     * Jangan menggunakan listener ini untuk wake word.
     */
    private val recognitionListener =
        object : RecognitionListener {

            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                Log.d(
                    TAG,
                    "Speech recognizer ready"
                )

                if (!isListeningRequested) {
                    return
                }

                _rmsLevel.value = 0f

                _speechState.value =
                    SpeechState.Listening(
                        rmsDb = 0f,
                        partialText = ""
                    )
            }

            override fun onBeginningOfSpeech() {

                Log.d(
                    TAG,
                    "User started speaking"
                )

                if (!isListeningRequested) {
                    return
                }

                _speechState.value =
                    SpeechState.Listening(
                        rmsDb = 0.25f,
                        partialText =
                            currentPartialText()
                    )
            }

            override fun onRmsChanged(
                rmsdB: Float
            ) {

                if (!isListeningRequested) {
                    return
                }

                /**
                 * SpeechRecognizer biasanya memberikan
                 * nilai sekitar -2 sampai 10 dB.
                 *
                 * Kita normalisasi menjadi 0..1
                 * untuk visualizer UI.
                 */
                val normalized =
                    ((rmsdB + 2f) / 12f)
                        .coerceIn(0f, 1f)

                _rmsLevel.value =
                    normalized

                val current =
                    _speechState.value

                if (current is SpeechState.Listening) {

                    _speechState.value =
                        current.copy(
                            rmsDb = normalized
                        )
                }
            }

            override fun onBufferReceived(
                buffer: ByteArray?
            ) {
                // Tidak menyimpan audio.
            }

            override fun onEndOfSpeech() {

                Log.d(
                    TAG,
                    "User finished speaking"
                )

                if (!isListeningRequested) {
                    return
                }

                _rmsLevel.value = 0f

                _speechState.value =
                    SpeechState.Processing
            }

            override fun onError(
                error: Int
            ) {

                Log.w(
                    TAG,
                    "SpeechRecognizer error: $error"
                )

                _rmsLevel.value = 0f

                /**
                 * Jika kita sendiri yang menghentikan
                 * recognizer, jangan tampilkan error palsu.
                 */
                if (!isListeningRequested) {

                    cleanupRecognizer(
                        changeState = false
                    )

                    return
                }

                val errorInfo =
                    createErrorInfo(error)

                Log.w(
                    TAG,
                    "Speech error message: ${errorInfo.message}"
                )

                cleanupRecognizer(
                    changeState = false
                )

                _speechState.value =
                    SpeechState.Error(
                        message =
                            errorInfo.message,
                        isPermanent =
                            errorInfo.isPermanent,
                        isAudioHardwareIssue =
                            errorInfo.isAudioHardwareIssue
                    )
            }

            override fun onResults(
                results: Bundle?
            ) {

                _rmsLevel.value = 0f

                if (!isListeningRequested) {

                    cleanupRecognizer(
                        changeState = false
                    )

                    return
                }

                val matches =
                    results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                /**
                 * Ambil hasil terbaik.
                 */
                val recognizedText =
                    matches
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                Log.d(
                    TAG,
                    "Final recognized text: \"$recognizedText\""
                )

                cleanupRecognizer(
                    changeState = false
                )

                isListeningRequested = false

                if (recognizedText.isNotBlank()) {

                    _speechState.value =
                        SpeechState.Success(
                            spokenText =
                                recognizedText
                        )

                } else {

                    _speechState.value =
                        SpeechState.Error(
                            message =
                                "Saya tidak mendengar perintah Anda. Silakan ulangi.",
                            isPermanent = false,
                            isAudioHardwareIssue = false
                        )
                }
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {

                if (!isListeningRequested) {
                    return
                }

                val partials =
                    partialResults
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                val partialText =
                    partials
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (partialText.isBlank()) {
                    return
                }

                Log.d(
                    TAG,
                    "Partial speech: \"$partialText\""
                )

                val current =
                    _speechState.value

                if (current is SpeechState.Listening) {

                    _speechState.value =
                        current.copy(
                            partialText =
                                partialText
                        )
                } else {

                    _speechState.value =
                        SpeechState.Listening(
                            rmsDb =
                                _rmsLevel.value,
                            partialText =
                                partialText
                        )
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
                // Tidak diperlukan.
            }
        }

    /**
     * Menyimpan partial text terakhir.
     */
    private fun currentPartialText(): String {

        val current =
            _speechState.value

        return if (
            current is SpeechState.Listening
        ) {
            current.partialText
        } else {
            ""
        }
    }

    /**
     * Mulai ACTIVE LISTENING.
     *
     * Dipanggil setelah WakeWordDetector mendeteksi
     * "JARVIS".
     */
    fun startListening() {

        mainHandler.post {

            /**
             * Batalkan sesi sebelumnya jika masih ada.
             */
            cancelInternal(
                resetState = false
            )

            listeningSessionId++

            isListeningRequested = true

            val currentSession =
                listeningSessionId

            Log.i(
                TAG,
                "Starting active command listening. Session=$currentSession"
            )

            /**
             * Pastikan permission microphone tersedia.
             */
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                isListeningRequested = false

                _speechState.value =
                    SpeechState.Error(
                        message =
                            "Izin mikrofon belum diberikan.",
                        isPermanent = true,
                        isAudioHardwareIssue = false
                    )

                return@post
            }

            /**
             * Pastikan speech recognition tersedia.
             */
            if (!isRecognitionAvailable) {

                isListeningRequested = false

                _speechState.value =
                    SpeechState.Error(
                        message =
                            "Layanan pengenal suara tidak tersedia pada perangkat ini.",
                        isPermanent = true,
                        isAudioHardwareIssue = false
                    )

                return@post
            }

            /**
             * Beri sedikit waktu agar WakeWordDetector
             * benar-benar melepaskan microphone.
             */
            mainHandler.postDelayed({

                /**
                 * Pastikan sesi ini masih valid.
                 */
                if (
                    !isListeningRequested ||
                    currentSession != listeningSessionId
                ) {
                    return@postDelayed
                }

                startRecognizerInternal(
                    currentSession
                )

            }, START_DELAY_MS)
        }
    }

    /**
     * Membuat dan menjalankan SpeechRecognizer.
     */
    private fun startRecognizerInternal(
        sessionId: Long
    ) {

        if (
            !isListeningRequested ||
            sessionId != listeningSessionId
        ) {
            return
        }

        try {

            cleanupRecognizer(
                changeState = false
            )

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
                        LANGUAGE
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        LANGUAGE
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        context.packageName
                    )

                    /**
                     * Partial result diperlukan agar UI
                     * dapat menampilkan teks sementara.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    /**
                     * Kita hanya membutuhkan hasil terbaik.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        3
                    )

                    /**
                     * Pengguna mempunyai waktu untuk
                     * mulai memberikan perintah.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        MINIMUM_LENGTH_MS
                    )

                    /**
                     * Jangan terlalu cepat menganggap
                     * pengguna selesai berbicara.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        COMPLETE_SILENCE_MS
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        POSSIBLY_COMPLETE_SILENCE_MS
                    )
                }

            _rmsLevel.value = 0f

            _speechState.value =
                SpeechState.Listening(
                    rmsDb = 0f,
                    partialText = ""
                )

            speechRecognizer?.startListening(
                intent
            )

            Log.i(
                TAG,
                "Active command listening started"
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Microphone security exception",
                e
            )

            isListeningRequested = false

            cleanupRecognizer(
                changeState = false
            )

            _speechState.value =
                SpeechState.Error(
                    message =
                        "Akses mikrofon ditolak oleh sistem.",
                    isPermanent = true,
                    isAudioHardwareIssue = false
                )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start active speech recognition",
                e
            )

            isListeningRequested = false

            cleanupRecognizer(
                changeState = false
            )

            _speechState.value =
                SpeechState.Error(
                    message =
                        "Gagal memulai pendengaran perintah.",
                    isPermanent = false,
                    isAudioHardwareIssue = true
                )
        }
    }

    /**
     * Hentikan pendengaran secara normal.
     *
     * Dipakai jika UI/ViewModel ingin mengakhiri
     * active listening.
     */
    fun stopListening() {

        mainHandler.post {

            Log.d(
                TAG,
                "stopListening()"
            )

            isListeningRequested = false

            listeningSessionId++

            try {

                speechRecognizer?.stopListening()

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Error stopping SpeechRecognizer",
                    e
                )
            }

            cleanupRecognizer(
                changeState = false
            )

            _rmsLevel.value = 0f

            _speechState.value =
                SpeechState.Idle
        }
    }

    /**
     * Batalkan active listening.
     *
     * Tidak dianggap sebagai error.
     */
    fun cancel() {

        mainHandler.post {

            Log.d(
                TAG,
                "cancel()"
            )

            cancelInternal(
                resetState = true
            )
        }
    }

    private fun cancelInternal(
        resetState: Boolean
    ) {

        isListeningRequested = false

        listeningSessionId++

        cleanupRecognizer(
            changeState = false
        )

        _rmsLevel.value = 0f

        if (resetState) {

            _speechState.value =
                SpeechState.Idle
        }
    }

    /**
     * Mengembalikan state ke Idle.
     */
    fun resetState() {

        mainHandler.post {

            isListeningRequested = false

            listeningSessionId++

            cleanupRecognizer(
                changeState = false
            )

            _rmsLevel.value = 0f

            _speechState.value =
                SpeechState.Idle
        }
    }

    /**
     * Digunakan oleh UI jika pengguna memasukkan
     * perintah secara manual.
     */
    fun emitManualInput(
        text: String
    ) {

        val cleaned =
            text.trim()

        if (cleaned.isBlank()) {
            return
        }

        mainHandler.post {

            isListeningRequested = false

            listeningSessionId++

            cleanupRecognizer(
                changeState = false
            )

            _rmsLevel.value = 0f

            _speechState.value =
                SpeechState.Success(
                    spokenText = cleaned
                )
        }
    }

    /**
     * Membersihkan SpeechRecognizer.
     */
    private fun cleanupRecognizer(
        changeState: Boolean
    ) {

        try {

            speechRecognizer
                ?.setRecognitionListener(
                    null
                )

            speechRecognizer
                ?.cancel()

            speechRecognizer
                ?.destroy()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error cleaning SpeechRecognizer",
                e
            )

        } finally {

            speechRecognizer = null

            if (changeState) {

                _rmsLevel.value = 0f

                _speechState.value =
                    SpeechState.Idle
            }
        }
    }

    /**
     * Mengubah kode error SpeechRecognizer
     * menjadi pesan yang mudah dipahami.
     */
    private fun createErrorInfo(
        error: Int
    ): ErrorInfo {

        return when (error) {

            SpeechRecognizer.ERROR_NO_MATCH -> {

                ErrorInfo(
                    message =
                        "Saya tidak dapat memahami perintah Anda. Silakan ulangi.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {

                ErrorInfo(
                    message =
                        "Saya tidak mendengar perintah Anda.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_AUDIO -> {

                ErrorInfo(
                    message =
                        "Terjadi masalah pada input mikrofon.",
                    isPermanent = false,
                    isAudioHardwareIssue = true
                )
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {

                ErrorInfo(
                    message =
                        "Izin mikrofon diperlukan agar JARVIS dapat mendengar perintah.",
                    isPermanent = true,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {

                ErrorInfo(
                    message =
                        "Layanan pengenal suara sedang sibuk. Silakan coba lagi.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_NETWORK -> {

                ErrorInfo(
                    message =
                        "Koneksi jaringan diperlukan untuk pengenalan suara pada perangkat ini.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {

                ErrorInfo(
                    message =
                        "Waktu koneksi pengenal suara habis.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_SERVER -> {

                ErrorInfo(
                    message =
                        "Layanan pengenal suara sedang mengalami gangguan.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            SpeechRecognizer.ERROR_CLIENT -> {

                ErrorInfo(
                    message =
                        "Layanan pengenal suara terputus. Silakan coba lagi.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }

            else -> {

                ErrorInfo(
                    message =
                        "Gagal mengenali suara. Kode error: $error.",
                    isPermanent = false,
                    isAudioHardwareIssue = false
                )
            }
        }
    }

    private data class ErrorInfo(
        val message: String,
        val isPermanent: Boolean,
        val isAudioHardwareIssue: Boolean
    )

    /**
     * Dipanggil ketika ViewModel/Activity dihancurkan.
     */
    fun destroy() {

        mainHandler.post {

            Log.d(
                TAG,
                "Destroying SpeechManager"
            )

            isListeningRequested = false

            listeningSessionId++

            cleanupRecognizer(
                changeState = false
            )

            _rmsLevel.value = 0f

            _speechState.value =
                SpeechState.Idle
        }
    }
}