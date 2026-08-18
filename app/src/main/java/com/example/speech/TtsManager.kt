package com.example.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * TTS Manager untuk JARVIS.
 *
 * Tanggung jawab:
 * - Menghasilkan suara JARVIS.
 * - Menggunakan konfigurasi dari JarvisVoiceConfig.
 * - Menangani status speaking.
 * - Memberikan callback ketika ucapan selesai.
 * - Mencegah wake-word detector menangkap suara JARVIS
 *   melalui koordinasi dengan ViewModel/VoiceService.
 */
class TtsManager(
    private val context: Context
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JarvisTtsManager"
    }

    private var tts: TextToSpeech? = null

    private val completionCallbacks =
        ConcurrentHashMap<String, () -> Unit>()

    private val _isReady =
        MutableStateFlow(false)

    val isReady: StateFlow<Boolean> =
        _isReady.asStateFlow()

    private val _isSpeaking =
        MutableStateFlow(false)

    val isSpeaking: StateFlow<Boolean> =
        _isSpeaking.asStateFlow()

    private val _isMuted =
        MutableStateFlow(false)

    val isMuted: StateFlow<Boolean> =
        _isMuted.asStateFlow()

    /**
     * Mencegah dua proses speak berjalan bersamaan.
     */
    private var currentUtteranceId: String? = null

    init {
        try {
            tts = TextToSpeech(
                context.applicationContext,
                this
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to initialize TextToSpeech",
                e
            )
        }
    }

    /**
     * TTS initialization callback.
     */
    override fun onInit(status: Int) {

        if (status != TextToSpeech.SUCCESS) {

            Log.e(
                TAG,
                "TTS initialization failed. Status=$status"
            )

            _isReady.value = false
            return
        }

        val engine = tts

        if (engine == null) {

            Log.e(
                TAG,
                "TTS engine is null after initialization"
            )

            _isReady.value = false
            return
        }

        try {

            /**
             * Terapkan seluruh konfigurasi suara
             * dari JarvisVoiceConfig.
             */
            JarvisVoiceConfig.configureVoice(
                engine
            )

            /**
             * Pastikan listener progress terpasang.
             */
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(
                        utteranceId: String?
                    ) {

                        Log.d(
                            TAG,
                            "TTS started: $utteranceId"
                        )

                        _isSpeaking.value = true

                        currentUtteranceId =
                            utteranceId
                    }

                    override fun onDone(
                        utteranceId: String?
                    ) {

                        Log.d(
                            TAG,
                            "TTS finished: $utteranceId"
                        )

                        _isSpeaking.value = false

                        if (
                            utteranceId ==
                            currentUtteranceId
                        ) {
                            currentUtteranceId = null
                        }

                        utteranceId?.let { id ->
                            completionCallbacks
                                .remove(id)
                                ?.invoke()
                        }
                    }

                    @Deprecated(
                        "Deprecated in Java"
                    )
                    override fun onError(
                        utteranceId: String?
                    ) {

                        Log.e(
                            TAG,
                            "TTS error: $utteranceId"
                        )

                        _isSpeaking.value = false

                        if (
                            utteranceId ==
                            currentUtteranceId
                        ) {
                            currentUtteranceId = null
                        }

                        utteranceId?.let { id ->
                            completionCallbacks
                                .remove(id)
                                ?.invoke()
                        }
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int
                    ) {

                        Log.e(
                            TAG,
                            "TTS error code=$errorCode id=$utteranceId"
                        )

                        _isSpeaking.value = false

                        if (
                            utteranceId ==
                            currentUtteranceId
                        ) {
                            currentUtteranceId = null
                        }

                        utteranceId?.let { id ->
                            completionCallbacks
                                .remove(id)
                                ?.invoke()
                        }
                    }
                }
            )

            _isReady.value = true

            Log.i(
                TAG,
                "JARVIS TTS initialized successfully"
            )

            Log.i(
                TAG,
                "Voice=${JarvisVoiceConfig.JARVIS_VOICE_NAME}"
            )

            Log.i(
                TAG,
                "Language=${JarvisVoiceConfig.JARVIS_LANGUAGE}"
            )

            Log.i(
                TAG,
                "Rate=${JarvisVoiceConfig.JARVIS_SPEECH_RATE}"
            )

            Log.i(
                TAG,
                "Pitch=${JarvisVoiceConfig.JARVIS_PITCH}"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to configure TTS",
                e
            )

            _isReady.value = false
        }
    }

    /**
     * Membuat JARVIS berbicara.
     *
     * QUEUE_FLUSH digunakan agar respons baru
     * menggantikan ucapan lama.
     */
    fun speak(
        text: String,
        onCompleted: (() -> Unit)? = null
    ) {

        if (_isMuted.value) {

            Log.d(
                TAG,
                "TTS muted; speech skipped"
            )

            onCompleted?.invoke()
            return
        }

        if (!_isReady.value) {

            Log.w(
                TAG,
                "TTS not ready"
            )

            onCompleted?.invoke()
            return
        }

        val cleanedText =
            cleanTextForSpeech(text)

        if (cleanedText.isBlank()) {

            onCompleted?.invoke()
            return
        }

        /**
         * Hentikan ucapan sebelumnya agar JARVIS
         * tidak berbicara bertumpuk.
         */
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Unable to stop previous TTS",
                e
            )
        }

        val utteranceId =
            "JARVIS_${System.currentTimeMillis()}"

        currentUtteranceId =
            utteranceId

        if (onCompleted != null) {

            completionCallbacks[
                utteranceId
            ] = onCompleted
        }

        /**
         * Volume dikontrol oleh konfigurasi pusat.
         */
        val params =
            Bundle().apply {

                putFloat(
                    TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    JarvisVoiceConfig.JARVIS_VOLUME
                )
            }

        try {

            _isSpeaking.value = true

            val result =
                tts?.speak(
                    cleanedText,
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    utteranceId
                )

            if (
                result !=
                TextToSpeech.SUCCESS
            ) {

                Log.e(
                    TAG,
                    "TTS speak failed: result=$result"
                )

                _isSpeaking.value = false

                currentUtteranceId = null

                completionCallbacks
                    .remove(utteranceId)
                    ?.invoke()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Exception while speaking",
                e
            )

            _isSpeaking.value = false

            currentUtteranceId = null

            completionCallbacks
                .remove(utteranceId)
                ?.invoke()
        }
    }

    /**
     * Menghentikan JARVIS berbicara.
     */
    fun stop() {

        try {

            tts?.stop()

            _isSpeaking.value = false

            currentUtteranceId = null

            completionCallbacks.clear()

            Log.d(
                TAG,
                "TTS stopped"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping TTS",
                e
            )
        }
    }

    /**
     * Mengaktifkan/nonaktifkan suara JARVIS.
     *
     * @return true jika sekarang muted.
     */
    fun toggleMute(): Boolean {

        val newMuted =
            !_isMuted.value

        _isMuted.value =
            newMuted

        if (newMuted) {
            stop()
        }

        Log.d(
            TAG,
            "TTS muted=$newMuted"
        )

        return newMuted
    }

    /**
     * Set mute secara langsung.
     */
    fun setMuted(
        muted: Boolean
    ) {

        _isMuted.value =
            muted

        if (muted) {
            stop()
        }
    }

    /**
     * Membersihkan teks sebelum dibacakan.
     *
     * JARVIS tidak perlu membaca:
     * - Markdown
     * - code fence
     * - simbol UI
     * - emoji
     */
    private fun cleanTextForSpeech(
        raw: String
    ): String {

        return raw
            .replace(
                Regex("```[a-zA-Z0-9_-]*"),
                " "
            )
            .replace(
                "```",
                " "
            )
            .replace(
                Regex("[#*_~`\\[\\](){}>]"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    /**
     * Memaksa konfigurasi suara diterapkan kembali.
     *
     * Berguna jika pengguna mengganti engine/voice
     * dari pengaturan perangkat.
     */
    fun reconfigureVoice() {

        val engine = tts

        if (engine == null) {

            Log.w(
                TAG,
                "Cannot reconfigure: TTS engine is null"
            )

            return
        }

        try {

            JarvisVoiceConfig.configureVoice(
                engine
            )

            Log.i(
                TAG,
                "JARVIS voice configuration reapplied"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to reconfigure JARVIS voice",
                e
            )
        }
    }

    /**
     * Membersihkan seluruh resource TTS.
     */
    fun shutdown() {

        Log.d(
            TAG,
            "Shutting down TTS"
        )

        try {

            completionCallbacks.clear()

            tts?.stop()

            tts?.shutdown()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error shutting down TTS",
                e
            )

        } finally {

            tts = null

            currentUtteranceId = null

            _isSpeaking.value = false
            _isReady.value = false
        }
    }
}