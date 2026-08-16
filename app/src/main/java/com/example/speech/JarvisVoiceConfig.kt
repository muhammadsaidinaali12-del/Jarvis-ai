package com.example.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Centralized Voice Configuration for JARVIS AI Assistant.
 *
 * Designed to evoke the persona of an advanced AI assistant inspired by JARVIS:
 * - Adult male voice
 * - Calm, authoritative, and elegant cadence
 * - Deep, natural timbre (slightly lowered pitch)
 * - Clear articulation at a measured, steady tempo
 * - Human-like yet precise intonation
 * - Speaking fluent Indonesian while prioritizing best male acoustic models
 */
object JarvisVoiceConfig {
    private const val TAG = "JarvisVoiceConfig"

    // Primary Voice Configuration Parameters
    var JARVIS_LANGUAGE: String = "id-ID"
    var JARVIS_SPEECH_RATE: Float = 0.96f   // Calm, measured, authoritative tempo
    var JARVIS_PITCH: Float = 0.88f         // Slightly lower, deeper masculine resonance
    var JARVIS_VOLUME: Float = 1.0f         // Full volume clarity
    var JARVIS_VOICE_NAME: String? = null   // Selected Voice name cache

    /**
     * Selects the best available male voice from the TTS engine.
     * Prioritizes:
     * 1. Indonesian male voices (e.g. "id-id-x-dfz", "id_id_male", "male")
     * 2. British/UK English male voices if multilocale/accent supported
     * 3. High quality / neural voice profiles
     */
    fun configureVoice(tts: TextToSpeech) {
        try {
            // Set language first
            val primaryLocale = Locale.forLanguageTag(JARVIS_LANGUAGE)
            val fallbackLocale = Locale.Builder().setLanguage("id").setRegion("ID").build()

            val langResult = tts.setLanguage(primaryLocale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(fallbackLocale)
            }

            // Apply Pitch and Speech Rate
            tts.setPitch(JARVIS_PITCH)
            tts.setSpeechRate(JARVIS_SPEECH_RATE)

            // Attempt to select the optimal male voice
            val availableVoices = tts.voices
            if (!availableVoices.isNullOrEmpty()) {
                val selectedVoice = findBestMaleVoice(availableVoices)
                if (selectedVoice != null) {
                    val result = tts.setVoice(selectedVoice)
                    if (result == TextToSpeech.SUCCESS) {
                        JARVIS_VOICE_NAME = selectedVoice.name
                        Log.i(TAG, "Selected JARVIS Voice: ${selectedVoice.name} (Locale: ${selectedVoice.locale})")
                    } else {
                        Log.w(TAG, "Failed to set voice ${selectedVoice.name}, result code: $result")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring JARVIS voice settings", e)
        }
    }

    private fun findBestMaleVoice(voices: Set<Voice>): Voice? {
        val voiceList = voices.toList()

        // 1. Look for Indonesian male voices
        val indonesianMale = voiceList.firstOrNull { voice ->
            val isIndonesian = voice.locale.language.equals("id", ignoreCase = true) ||
                    voice.locale.language.equals("in", ignoreCase = true)
            val isMale = voice.name.contains("male", ignoreCase = true) ||
                    voice.name.contains("-dfz-", ignoreCase = true) || // Google TTS male voice ID for id-ID
                    voice.name.contains("b-local", ignoreCase = true) ||
                    voice.name.contains("d-local", ignoreCase = true)
            isIndonesian && isMale
        }
        if (indonesianMale != null) return indonesianMale

        // 2. Look for any Indonesian voice with high quality
        val indonesianAny = voiceList.firstOrNull { voice ->
            val isIndonesian = voice.locale.language.equals("id", ignoreCase = true) ||
                    voice.locale.language.equals("in", ignoreCase = true)
            val isNotExplicitlyFemale = !voice.name.contains("female", ignoreCase = true) &&
                    !voice.name.contains("-c-", ignoreCase = true)
            isIndonesian && isNotExplicitlyFemale
        }
        if (indonesianAny != null) return indonesianAny

        // 3. Fallback to British English male voice if available on system
        val britishMale = voiceList.firstOrNull { voice ->
            val isBritish = voice.locale.country.equals("GB", ignoreCase = true) ||
                    voice.locale.country.equals("UK", ignoreCase = true) ||
                    voice.locale.toLanguageTag().contains("en-GB", ignoreCase = true)
            val isMale = voice.name.contains("male", ignoreCase = true) ||
                    voice.name.contains("rjs", ignoreCase = true) ||
                    voice.name.contains("gba", ignoreCase = true)
            isBritish && isMale
        }
        if (britishMale != null) return britishMale

        return voiceList.firstOrNull {
            it.locale.language.equals("id", ignoreCase = true) ||
                    it.locale.language.equals("in", ignoreCase = true)
        }
    }
}
