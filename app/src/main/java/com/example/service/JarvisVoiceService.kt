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
     * START
     * =========================================================
     */

    private fun handleStart() {

        if (!hasMicrophonePermission()) {

            Log.w(
                TAG,
                "RECORD_AUDIO permission missing"
            )

            _serviceStatus.value =
                ServiceState.STOPPED

            return
        }

        detectorMutedForTts = false

        startForegroundWithNotification(
            "JARVIS SIAGA",
            "Mendengarkan wake word 'JARVIS'...",
            false
        )

        _serviceStatus.value =
            ServiceState.RUNNING

        startWakeWordEngine()
    }

    /*
     * =========================================================
     * PAUSE
     * =========================================================
     */

    private fun handlePause() {

        detectorMutedForTts = false

        stopWakeWordEngine()

        _serviceStatus.value =
            ServiceState.PAUSED

        updateNotification(
            "JARVIS DIJEDA",
            "Mikrofon dinonaktifkan sementara.",
            true
        )

        Log.d(
            TAG,
            "JARVIS paused"
        )
    }

    /*
     * =========================================================
     * RESUME
     * =========================================================
     */

    private fun handleResume() {

        if (!hasMicrophonePermission()) {

            Log.w(
                TAG,
                "Cannot resume: microphone permission missing"
            )

            return
        }

        detectorMutedForTts = false

        startForegroundWithNotification(
            "JARVIS SIAGA",
            "Mendengarkan wake word 'JARVIS'...",
            false
        )

        _serviceStatus.value =
            ServiceState.RUNNING

        startWakeWordEngine()

        Log.d(
            TAG,
            "JARVIS resumed"
        )
    }

    /*
     * =========================================================
     * STOP
     * =========================================================
     */

    private fun handleStop() {

        detectorMutedForTts = false

        stopWakeWordEngine()

        _serviceStatus.value =
            ServiceState.STOPPED

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
            ) {

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

            } else {

                @Suppress("DEPRECATION")
                stopForeground(true)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping foreground",
                e
            )
        }

        stopSelf()

        Log.d(
            TAG,
            "JARVIS service stopped"
        )
    }

    /*
     * =========================================================
     * MICROPHONE PERMISSION
     * =========================================================
     */

    private fun hasMicrophonePermission():
            Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /*
     * =========================================================
     * WAKE WORD ENGINE
     * =========================================================
     */

    private fun startWakeWordEngine() {

        if (!hasMicrophonePermission()) {

            Log.w(
                TAG,
                "Microphone permission not granted"
            )

            return
        }

        if (detectorMutedForTts) {

            Log.d(
                TAG,
                "Detector is muted for TTS"
            )

            return
        }

        if (detectorRunning) {

            Log.d(
                TAG,
                "WakeWordDetector already running"
            )

            return
        }

        if (wakeWordDetector == null) {

            createWakeWordDetector()
        }

        val detector =
            wakeWordDetector

        if (detector == null) {

            Log.e(
                TAG,
                "WakeWordDetector unavailable"
            )

            return
        }

        try {

            detector.start { event ->

                /*
                 * Jangan teruskan event detector
                 * jika detector sedang dimute.
                 */

                if (detectorMutedForTts) {
                    return@start
                }

                when (event) {

                    is WakeWordEvent.Detected -> {

                        Log.i(
                            TAG,
                            "WAKE WORD DETECTED: ${event.inlineCommand}"
                        )

                        /*
                         * Matikan detector segera.
                         *
                         * SpeechManager nantinya akan
                         * mengambil alih microphone.
                         */
                        detectorMutedForTts = true

                        try {
                            detector.muteForTts()
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Failed to mute detector after detection",
                                e
                            )
                        }

                        _wakeWordEvents.value =
                            event
                    }

                    else -> {

                        _wakeWordEvents.value =
                            event
                    }
                }
            }

            detectorRunning = true

            Log.d(
                TAG,
                "WakeWordDetector started"
            )

        } catch (e: Exception) {

            detectorRunning = false

            Log.e(
                TAG,
                "Failed to start WakeWordDetector",
                e
            )
        }
    }

    /*
     * =========================================================
     * STOP WAKE WORD
     * =========================================================
     */

    private fun stopWakeWordEngine() {

        val detector =
            wakeWordDetector

        if (detector == null) {

            detectorRunning = false
            return
        }

        try {

            detector.stop()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to stop WakeWordDetector",
                e
            )

        } finally {

            detectorRunning = false

            Log.d(
                TAG,
                "WakeWordDetector stopped"
            )
        }
    }

    /*
     * =========================================================
     * MUTE FOR TTS / ACTIVE LISTENING
     * =========================================================
     */

    fun muteForTts() {

        detectorMutedForTts = true

        val detector =
            wakeWordDetector

        try {

            detector?.muteForTts()

            Log.d(
                TAG,
                "WakeWordDetector muted"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to mute WakeWordDetector",
                e
            )
        }
    }

    /*
     * =========================================================
     * UNMUTE AFTER TTS
     * =========================================================
     */

    fun unmuteAfterTts() {

        if (
            _serviceStatus.value ==
            ServiceState.PAUSED
        ) {
            return
        }

        if (
            _serviceStatus.value ==
            ServiceState.STOPPED
        ) {
            return
        }

        detectorMutedForTts = false

        val detector =
            wakeWordDetector

        try {

            if (detector == null) {

                createWakeWordDetector()
            }

            val currentDetector =
                wakeWordDetector

            if (currentDetector != null) {

                /*
                 * Detector sendiri akan melakukan
                 * restart passive listening.
                 */
                currentDetector.unmuteAfterTts()

                detectorRunning = true
            }

            Log.d(
                TAG,
                "WakeWordDetector unmuted"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to unmute WakeWordDetector",
                e
            )
        }
    }

    /*
     * =========================================================
     * FOREGROUND NOTIFICATION
     * =========================================================
     */

    private fun startForegroundWithNotification(
        title: String,
        content: String,
        isPaused: Boolean
    ) {

        val notification =
            buildNotification(
                title,
                content,
                isPaused
            )

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            } else {

                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start foreground service",
                e
            )
        }
    }

    private fun updateNotification(
        title: String,
        content: String,
        isPaused: Boolean
    ) {

        val notification =
            buildNotification(
                title,
                content,
                isPaused
            )

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    /*
     * =========================================================
     * NOTIFICATION
     * =========================================================
     */

    private fun buildNotification(
        title: String,
        content: String,
        isPaused: Boolean
    ): Notification {

        val openAppIntent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val openAppPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val pauseIntent =
            Intent(
                this,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_PAUSE
            }

        val pausePendingIntent =
            PendingIntent.getService(
                this,
                1,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val resumeIntent =
            Intent(
                this,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_RESUME
            }

        val resumePendingIntent =
            PendingIntent.getService(
                this,
                2,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val stopIntent =
            Intent(
                this,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                3,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setContentIntent(
                    openAppPendingIntent
                )
                .setOngoing(true)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                )

        if (isPaused) {

            builder.addAction(
                android.R.drawable.ic_media_play,
                "Lanjut",
                resumePendingIntent
            )

        } else {

            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Jeda",
                pausePendingIntent
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Hentikan",
            stopPendingIntent
        )

        return builder.build()
    }

    /*
     * =========================================================
     * NOTIFICATION CHANNEL
     * =========================================================
     */

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS Voice Assistant Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Layanan wake word dan mikrofon JARVIS"

                    setShowBadge(false)
                }

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(
                channel
            )
        }
    }

    /*
     * =========================================================
     * BINDER
     * =========================================================
     */

    override fun onBind(
        intent: Intent?
    ): IBinder {

        return binder
    }

    /*
     * =========================================================
     * DESTROY
     * =========================================================
     */

    override fun onDestroy() {

        Log.d(
            TAG,
            "JarvisVoiceService destroying"
        )

        detectorMutedForTts = true

        stopWakeWordEngine()

        try {

            wakeWordDetector?.stop()

        } catch (_: Exception) {
        }

        wakeWordDetector = null

        detectorRunning = false

        _serviceStatus.value =
            ServiceState.STOPPED

        super.onDestroy()
    }
}