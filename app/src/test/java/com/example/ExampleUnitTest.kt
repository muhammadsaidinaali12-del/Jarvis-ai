package com.example

import com.example.speech.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {

    @Test
    fun testWakeWordDetection_IgnoredConversations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = WakeWordDetector(context)

        // General speech without "JARVIS" must be completely ignored
        assertNull(detector.evaluateWakeWord("Besok saya ada sekolah."))
        assertNull(detector.evaluateWakeWord("Hari ini cuaca cukup panas ya."))
        assertNull(detector.evaluateWakeWord("Halo semuanya apa kabar"))
        assertNull(detector.evaluateWakeWord("Saya ingin makan siang"))
    }

    @Test
    fun testWakeWordDetection_StandaloneTrigger() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = WakeWordDetector(context)

        val result1 = detector.evaluateWakeWord("JARVIS")
        assertNotNull(result1)
        assertNull(result1?.inlineCommand)

        val result2 = detector.evaluateWakeWord("jarvis")
        assertNotNull(result2)
        assertNull(result2?.inlineCommand)

        val result3 = detector.evaluateWakeWord("Hai JARVIS")
        assertNotNull(result3)
        assertNull(result3?.inlineCommand)
    }

    @Test
    fun testWakeWordDetection_InlineCommand() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = WakeWordDetector(context)

        val result1 = detector.evaluateWakeWord("JARVIS, jam berapa sekarang?")
        assertNotNull(result1)
        assertEquals("jam berapa sekarang?", result1?.inlineCommand)

        val result2 = detector.evaluateWakeWord("JARVIS buka YouTube")
        assertNotNull(result2)
        assertEquals("buka youtube", result2?.inlineCommand?.lowercase())

        val result3 = detector.evaluateWakeWord("Hai JARVIS, siapa namamu?")
        assertNotNull(result3)
        assertEquals("siapa namamu?", result3?.inlineCommand)
    }

    @Test
    fun testJarvisVoiceConfig_Parameters() {
        assertEquals("id-ID", com.example.speech.JarvisVoiceConfig.JARVIS_LANGUAGE)
        assertEquals(0.96f, com.example.speech.JarvisVoiceConfig.JARVIS_SPEECH_RATE, 0.01f)
        assertEquals(0.88f, com.example.speech.JarvisVoiceConfig.JARVIS_PITCH, 0.01f)
        assertEquals(1.0f, com.example.speech.JarvisVoiceConfig.JARVIS_VOLUME, 0.01f)
    }
}
