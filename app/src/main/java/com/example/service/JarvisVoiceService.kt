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

            val intent = Intent(
                context,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(
                    context,
                    intent
                )
            } else {
                context.startService(intent)
            }
        }

        fun pauseService(context: Context) {

            val intent = Intent(
                context,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_PAUSE
            }

            context.startService(intent)
        }

        fun resumeService(context: Context) {

            val intent = Intent(
                context,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_RESUME
            }

            context.startService(intent)
        }

        fun stopService(context: Context) {

            val intent = Intent(
                context,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_STOP
            }

            context.startService(intent)
        }

        fun muteDetectorForTts(context: Context) {

            val intent = Intent(
                context,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_MUTE_TTS
            }

            context.startService(intent)
        }

        fun unmuteDetectorAfterTts(context: Context) {

            val intent = Intent(
                context,
                JarvisVoiceService::class.java
            ).apply {
                action = ACTION_UNMUTE_TTS
            }

            context.startService(intent)
        }
    }

    enum class ServiceState {
        RUNNING,
        PAUSED,
        STOPPED
    }

    inner class LocalBinder : Binder() {

        fun getService(): JarvisVoiceService =
            this@JarvisVoiceService
    }

    private val binder = LocalBinder()

    private var wakeWordDetector: WakeWordDetector? = null

    private var detectorRunning = false

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "JarvisVoiceService onCreate"
        )

        createNotificationChannel()

        wakeWordDetector =
            WakeWordDetector(this)
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

            ACTION_START -> {

                startForegroundWithNotification(
                    "JARVIS SIAGA",
                    "Mendengarkan wake word 'JARVIS'...",
                    false
                )

                startWakeWordEngine()

                _serviceStatus.value =
                    ServiceState.RUNNING
            }

            ACTION_PAUSE -> {

                pauseWakeWordEngine()

                updateNotification(
                    "JARVIS DIJEDA",
                    "Mikrofon dinonaktifkan sementara.",
                    true
                )

                _serviceStatus.value =
                    ServiceState.PAUSED
            }

            ACTION_RESUME -> {

                startForegroundWithNotification(
                    "JARVIS SIAGA",
                    "Mendengarkan wake word 'JARVIS'...",
                    false
                )

                resumeWakeWordEngine()

                updateNotification(
                    "JARVIS SIAGA",
                    "Mendengarkan wake word 'JARVIS'...",
                    false
                )

                _serviceStatus.value =
                    ServiceState.RUNNING
            }

            ACTION_MUTE_TTS -> {

                muteForTts()
            }

            ACTION_UNMUTE_TTS -> {

                unmuteAfterTts()
            }

            ACTION_STOP -> {

                stopWakeWordEngine()

                _serviceStatus.value =
                    ServiceState.STOPPED

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }

                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder {

        return binder
    }

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

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

            startForeground(
                NOTIFICATION_ID,
                notification
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

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

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

    private fun startWakeWordEngine() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.w(
                TAG,
                "RECORD_AUDIO permission not granted"
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

        try {

            wakeWordDetector?.start { event ->

                _wakeWordEvents.value =
                    event
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

    private fun pauseWakeWordEngine() {

        if (!detectorRunning) {
            return
        }

        try {

            wakeWordDetector?.pause()

            Log.d(
                TAG,
                "WakeWordDetector paused"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to pause detector",
                e
            )
        }
    }

    private fun resumeWakeWordEngine() {

        if (!detectorRunning) {

            startWakeWordEngine()
            return
        }

        try {

            wakeWordDetector?.resume()

            Log.d(
                TAG,
                "WakeWordDetector resumed"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to resume detector",
                e
            )
        }
    }

    private fun stopWakeWordEngine() {

        if (!detectorRunning) {
            return
        }

        try {

            wakeWordDetector?.stop()

            Log.d(
                TAG,
                "WakeWordDetector stopped"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to stop detector",
                e
            )
        }

        detectorRunning = false
    }

    fun muteForTts() {

        try {

            wakeWordDetector?.muteForTts()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to mute detector",
                e
            )
        }
    }

    fun unmuteAfterTts() {

        try {

            wakeWordDetector?.unmuteAfterTts()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to unmute detector",
                e
            )
        }
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "JarvisVoiceService onDestroy"
        )

        stopWakeWordEngine()

        wakeWordDetector = null

        _serviceStatus.value =
            ServiceState.STOPPED

        super.onDestroy()
    }
}