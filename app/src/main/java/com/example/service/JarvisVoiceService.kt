package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.speech.WakeWordDetector
import com.example.speech.WakeWordEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JarvisVoiceService : Service() {

    companion object {

        private const val TAG = "JarvisVoiceService"

        const val CHANNEL_ID =
            "jarvis_voice_assistant_channel"

        const val NOTIFICATION_ID = 1001

        const val ACTION_START =
            "com.example.jarvis.ACTION_START"

        const val ACTION_STOP =
            "com.example.jarvis.ACTION_STOP"

        const val ACTION_PAUSE =
            "com.example.jarvis.ACTION_PAUSE"

        const val ACTION_RESUME =
            "com.example.jarvis.ACTION_RESUME"

        const val ACTION_MUTE_TTS =
            "com.example.jarvis.ACTION_MUTE_TTS"

        const val ACTION_UNMUTE_TTS =
            "com.example.jarvis.ACTION_UNMUTE_TTS"

        private val _serviceStatus =
            MutableStateFlow(ServiceState.STOPPED)

        val serviceStatus: StateFlow<ServiceState> =
            _serviceStatus.asStateFlow()

        private val _wakeWordEvents =
            MutableStateFlow<WakeWordEvent?>(null)

        val wakeWordEvents: StateFlow<WakeWordEvent?> =
            _wakeWordEvents.asStateFlow()

        fun startService(context: Context) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action = ACTION_START
                }

            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

                    ContextCompat.startForegroundService(
                        context,
                        intent
                    )

                } else {

                    context.startService(intent)
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to start service",
                    e
                )
            }
        }

        fun pauseService(context: Context) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action = ACTION_PAUSE
                }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to pause service", e)
            }
        }

        fun resumeService(context: Context) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action = ACTION_RESUME
                }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to resume service", e)
            }
        }

        fun stopService(context: Context) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action = ACTION_STOP
                }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to stop service", e)
            }
        }

        fun muteDetectorForTts(context: Context) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action = ACTION_MUTE_TTS
                }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to mute detector", e)
            }
        }

        fun unmuteDetectorAfterTts(context: Context) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action = ACTION_UNMUTE_TTS
                }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to unmute detector", e)
            }
        }
    }

    enum class ServiceState {
        RUNNING,
        PAUSED,
        STOPPED
    }

    inner class LocalBinder : Binder() {

        fun getService():
                JarvisVoiceService =
            this@JarvisVoiceService
    }

    private val binder =
        LocalBinder()

    private var wakeWordDetector:
            WakeWordDetector? = null

    /*
     * Menunjukkan apakah detector sedang
     * benar-benar diminta untuk berjalan.
     */
    private var detectorRunning = false

    /*
     * Menunjukkan apakah detector sedang
     * dimatikan sementara karena TTS.
     */
    private var detectorMutedForTts = false

    /*
     * =========================================================
     * SERVICE LIFECYCLE
     * =========================================================
     */

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "JarvisVoiceService created"
        )

        createNotificationChannel()

        createWakeWordDetector()
    }

    private fun createWakeWordDetector() {

        try {

            wakeWordDetector =
                WakeWordDetector(
                    applicationContext
                )

            Log.d(
                TAG,
                "WakeWordDetector created"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to create WakeWordDetector",
                e
            )

            wakeWordDetector = null
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action =
            intent?.action ?: ACTION_START

        Log.d(
            TAG,
            "onStartCommand: $action"
        )

        when (action) {

            ACTION_START ->
                handleStart()

            ACTION_PAUSE ->
                handlePause()

            ACTION_RESUME ->
                handleResume()

            ACTION_MUTE_TTS ->
                muteForTts()

            ACTION_UNMUTE_TTS ->
                unmuteAfterTts()

            ACTION_STOP ->
                handleStop()

            else ->
                Log.w(
                    TAG,
                    "Unknown action: $action"
                )
        }

        /*
         * Jangan biarkan Android menghentikan
         * service secara permanen setelah proses
         * background selesai.
         */
        return START_STICKY
    }

    /*
     * =========================================================
    