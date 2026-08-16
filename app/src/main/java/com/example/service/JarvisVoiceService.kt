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
import com.example.R
import com.example.speech.WakeWordDetector
import com.example.speech.WakeWordEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android Foreground Service for JARVIS continuous background listening & wake-word detection.
 *
 * Adheres strictly to modern Android requirements:
 * - Proper Service lifecycle
 * - Foreground Service with type FOREGROUND_SERVICE_TYPE_MICROPHONE
 * - Persistent notification with transparency on microphone usage
 * - User actions to PAUSE, RESUME, and STOP
 */
class JarvisVoiceService : Service() {

    companion object {
        private const val TAG = "JarvisVoiceService"
        const val CHANNEL_ID = "jarvis_voice_assistant_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.jarvis.ACTION_START"
        const val ACTION_STOP = "com.example.jarvis.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.jarvis.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.jarvis.ACTION_RESUME"

        private val _serviceStatus = MutableStateFlow(ServiceState.STOPPED)
        val serviceStatus: StateFlow<ServiceState> = _serviceStatus.asStateFlow()

        private val _wakeWordEvents = MutableStateFlow<WakeWordEvent?>(null)
        val wakeWordEvents: StateFlow<WakeWordEvent?> = _wakeWordEvents.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseService(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeService(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
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
        fun getService(): JarvisVoiceService = this@JarvisVoiceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeWordDetector: WakeWordDetector? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "JarvisVoiceService onCreate")
        createNotificationChannel()
        wakeWordDetector = WakeWordDetector(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "JarvisVoiceService onStartCommand action: $action")

        when (action) {
            ACTION_START -> {
                startForegroundWithNotification("JARVIS SIAGA", "Mendengarkan wake word 'JARVIS'...", isPaused = false)
                startWakeWordEngine()
                _serviceStatus.value = ServiceState.RUNNING
            }
            ACTION_PAUSE -> {
                pauseWakeWordEngine()
                updateNotification("JARVIS DIJEDA", "Mikrofon dinonaktifkan sementara oleh pengguna.", isPaused = true)
                _serviceStatus.value = ServiceState.PAUSED
            }
            ACTION_RESUME -> {
                resumeWakeWordEngine()
                updateNotification("JARVIS SIAGA", "Mendengarkan wake word 'JARVIS'...", isPaused = false)
                _serviceStatus.value = ServiceState.RUNNING
            }
            ACTION_STOP -> {
                stopWakeWordEngine()
                _serviceStatus.value = ServiceState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundWithNotification(title: String, content: String, isPaused: Boolean) {
        val notification = buildNotification(title, content, isPaused)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service with microphone type", e)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, content: String, isPaused: Boolean) {
        val notification = buildNotification(title, content, isPaused)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String, isPaused: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, JarvisVoiceService::class.java).apply { action = ACTION_PAUSE }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, JarvisVoiceService::class.java).apply { action = ACTION_RESUME }
        val resumePendingIntent = PendingIntent.getService(
            this,
            2,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisVoiceService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(!isPaused)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Voice Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan pemantau wake word dan mikrofon latar belakang JARVIS"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startWakeWordEngine() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start WakeWordEngine: RECORD_AUDIO permission not granted")
            return
        }

        wakeWordDetector?.start { event ->
            _wakeWordEvents.value = event
        }
    }

    private fun pauseWakeWordEngine() {
        wakeWordDetector?.pause()
    }

    private fun resumeWakeWordEngine() {
        wakeWordDetector?.resume()
    }

    private fun stopWakeWordEngine() {
        wakeWordDetector?.stop()
    }

    fun muteForTts() {
        wakeWordDetector?.muteForTts()
    }

    fun unmuteAfterTts() {
        wakeWordDetector?.unmuteAfterTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "JarvisVoiceService onDestroy")
        stopWakeWordEngine()
        serviceScope.cancel()
        _serviceStatus.value = ServiceState.STOPPED
    }
}
