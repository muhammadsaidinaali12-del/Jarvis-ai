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
import com.example.speech.JarvisBrain
import com.example.speech.SpeechManager
import com.example.speech.SpeechState
import com.example.speech.TtsManager
import com.example.speech.WakeWordDetector
import com.example.speech.WakeWordEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel

class JarvisVoiceService : Service() {

    companion object {

        private const val TAG =
            "JarvisVoiceService"

        const val CHANNEL_ID =
            "jarvis_voice_assistant_channel"

        const val NOTIFICATION_ID =
            1001

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


        /*
         * =====================================================
         * SERVICE STATUS
         * =====================================================
         */

        private val _serviceStatus =
            MutableStateFlow(
                ServiceState.STOPPED
            )

        val serviceStatus:
                StateFlow<ServiceState> =
            _serviceStatus.asStateFlow()


        /*
         * =====================================================
         * WAKE WORD EVENTS
         * =====================================================
         *
         * SharedFlow digunakan supaya:
         *
         * 1. UI aktif:
         *    ViewModel menerima event.
         *
         * 2. UI ditutup:
         *    tidak ada collector,
         *    sehingga Service menjalankan
         *    perintah sendiri.
         *
         * Ini adalah bagian penting untuk
         * memperbaiki background command.
         */

        private val _wakeWordEvents =
            MutableSharedFlow<WakeWordEvent>(
                extraBufferCapacity = 32,
                onBufferOverflow =
                    BufferOverflow.DROP_OLDEST
            )

        val wakeWordEvents:
                SharedFlow<WakeWordEvent> =
            _wakeWordEvents.asSharedFlow()


        /*
         * =====================================================
         * START SERVICE
         * =====================================================
         */

        fun startService(
            context: Context
        ) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {
                    action =
                        ACTION_START
                }

            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {

                    ContextCompat
                        .startForegroundService(
                            context,
                            intent
                        )

                } else {

                    context.startService(
                        intent
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to start voice service",
                    e
                )
            }
        }


        /*
         * =====================================================
         * SERVICE ACTIONS
         * =====================================================
         */

        fun pauseService(
            context: Context
        ) {

            sendAction(
                context,
                ACTION_PAUSE
            )
        }

        fun resumeService(
            context: Context
        ) {

            sendAction(
                context,
                ACTION_RESUME
            )
        }

        fun stopService(
            context: Context
        ) {

            sendAction(
                context,
                ACTION_STOP
            )
        }

        fun muteDetectorForTts(
            context: Context
        ) {

            sendAction(
                context,
                ACTION_MUTE_TTS
            )
        }

        fun unmuteDetectorAfterTts(
            context: Context
        ) {

            sendAction(
                context,
                ACTION_UNMUTE_TTS
            )
        }


        private fun sendAction(
            context: Context,
            action: String
        ) {

            val intent =
                Intent(
                    context,
                    JarvisVoiceService::class.java
                ).apply {

                    this.action =
                        action
                }

            try {

                context.startService(
                    intent
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Unable to send service action: $action",
                    e
                )
            }
        }
    }


    /*
     * =========================================================
     * SERVICE STATE
     * =========================================================
     */

    enum class ServiceState {

        RUNNING,

        PAUSED,

        STOPPED
    }


    /*
     * =========================================================
     * BINDER
     * =========================================================
     */

    inner class LocalBinder :
        Binder() {

        fun getService():
                JarvisVoiceService =
            this@JarvisVoiceService
    }

    private val binder =
        LocalBinder()


    /*
     * =========================================================
     * COROUTINE
     * =========================================================
     */

    private val serviceJob =
        SupervisorJob()

    private val serviceScope =
        CoroutineScope(
            Dispatchers.Main.immediate +
                    serviceJob
        )


    /*
     * =========================================================
     * JARVIS COMPONENTS
     * =========================================================
     */

    private lateinit var speechManager:
            SpeechManager

    private lateinit var ttsManager:
            TtsManager

    private lateinit var jarvisBrain:
            JarvisBrain


    /*
     * =========================================================
     * WAKE WORD
     * =========================================================
     */

    private var wakeWordDetector:
            WakeWordDetector? = null

    private var detectorRunning =
        false

    private var detectorMuted =
        false


    /*
     * =========================================================
     * BACKGROUND COMMAND STATE
     * =========================================================
     */

    private var processingBackgroundCommand =
        false

    private var speechCollectionJob:
            Job? = null

    private var commandTimeoutJob:
            Job? = null


    /*
     * =========================================================
     * CREATE
     * =========================================================
     */

    override fun onCreate() {

        super.onCreate()

        Log.i(
            TAG,
            "JARVIS Voice Service created"
        )

        createNotificationChannel()


        /*
         * Semua komponen menggunakan
         * applicationContext agar aman
         * ketika UI sudah ditutup.
         */

        speechManager =
            SpeechManager(
                applicationContext
            )

        ttsManager =
            TtsManager(
                applicationContext
            )

        jarvisBrain =
            JarvisBrain(
                applicationContext
            )


        createWakeWordDetector()

        observeSpeechManager()
    }


    /*
     * =========================================================
     * BIND
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

        Log.i(
            TAG,
            "JARVIS Voice Service destroying"
        )

        detectorMuted =
            true

        commandTimeoutJob?.cancel()

        speechCollectionJob?.cancel()

        stopWakeWordEngine()

        try {

            speechManager.cancel()

        } catch (_: Exception) {
        }

        try {

            ttsManager.shutdown()

        } catch (_: Exception) {
        }

        wakeWordDetector =
            null

        _serviceStatus.value =
            ServiceState.STOPPED

        serviceScope.cancel()

        super.onDestroy()
    }


    /*
     * =========================================================
     * START COMMAND
     * =========================================================
     */

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action =
            intent?.action
                ?: ACTION_START

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

        return START_STICKY
    }


    /*
     * =========================================================
     * START
     * =========================================================
     */

    private fun handleStart() {

        if (
            !hasMicrophonePermission()
        ) {

            Log.w(
                TAG,
                "RECORD_AUDIO permission missing"
            )

            _serviceStatus.value =
                ServiceState.STOPPED

            return
        }

        detectorMuted =
            false

        startForegroundWithNotification(
            title =
                "JARVIS SIAGA",
            content =
                "Mendengarkan wake word \"JARVIS\"...",
            isPaused =
                false
        )

        _serviceStatus.value =
            ServiceState.RUNNING

        startWakeWordEngine()

        Log.i(
            TAG,
            "JARVIS background voice mode started"
        )
    }


    /*
     * =========================================================
     * PAUSE
     * =========================================================
     */

    private fun handlePause() {

        detectorMuted =
            true

        commandTimeoutJob?.cancel()

        try {

            speechManager.cancel()

        } catch (_: Exception) {
        }

        try {

            ttsManager.stop()

        } catch (_: Exception) {
        }

        stopWakeWordEngine()

        processingBackgroundCommand =
            false

        _serviceStatus.value =
            ServiceState.PAUSED

        updateNotification(
            title =
                "JARVIS DIJEDA",
            content =
                "Mikrofon dinonaktifkan sementara.",
            isPaused =
                true
        )

        Log.i(
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

        if (
            !hasMicrophonePermission()
        ) {

            Log.w(
                TAG,
                "Cannot resume: microphone permission missing"
            )

            return
        }

        detectorMuted =
            false

        startForegroundWithNotification(
            title =
                "JARVIS SIAGA",
            content =
                "Mendengarkan wake word \"JARVIS\"...",
            isPaused =
                false
        )

        _serviceStatus.value =
            ServiceState.RUNNING

        startWakeWordEngine()

        Log.i(
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

        detectorMuted =
            true

        commandTimeoutJob?.cancel()

        try {

            speechManager.cancel()

        } catch (_: Exception) {
        }

        try {

            ttsManager.stop()

        } catch (_: Exception) {
        }

        processingBackgroundCommand =
            false

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
                stopForeground(
                    true
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to stop foreground service",
                e
            )
        }

        stopSelf()

        Log.i(
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

        return ContextCompat
            .checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) ==
                PackageManager.PERMISSION_GRANTED
    }


    /*
     * =========================================================
     * CREATE WAKE WORD DETECTOR
     * =========================================================
     */

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

            wakeWordDetector =
                null

            Log.e(
                TAG,
                "Failed to create WakeWordDetector",
                e
            )
        }
    }


    /*
     * =========================================================
     * START WAKE WORD ENGINE
     * =========================================================
     */

    private fun startWakeWordEngine() {

        if (
            !hasMicrophonePermission()
        ) {
            return
        }

        if (
            _serviceStatus.value !=
            ServiceState.RUNNING
        ) {
            return
        }

        if (detectorMuted) {
            return
        }

        if (detectorRunning) {
            return
        }

        if (
            wakeWordDetector == null
        ) {

            createWakeWordDetector()
        }

        val detector =
            wakeWordDetector
                ?: return

        try {

            detector.start { event ->

                /*
                 * Abaikan callback lama.
                 */

                if (
                    detectorMuted ||
                    _serviceStatus.value !=
                    ServiceState.RUNNING
                ) {

                    return@start
                }

                when (event) {

                    /*
                     * =========================================
                     * WAKE WORD DETECTED
                     * =========================================
                     */

                    is WakeWordEvent.Detected -> {

                        Log.i(
                            TAG,
                            "JARVIS wake word detected. " +
                                    "inlineCommand=${event.inlineCommand}"
                        )


                        /*
                         * Segera hentikan detector.
                         *
                         * Ini sangat penting karena
                         * SpeechRecognizer akan mengambil
                         * microphone.
                         */

                        stopWakeWordEngine()


                        /*
                         * Jika UI sedang aktif:
                         *
                         * ViewModel tetap menangani alur
                         * seperti sebelumnya.
                         *
                         * Jika UI ditutup:
                         *
                         * Service mengambil alih.
                         */

                        if (
                            _wakeWordEvents
                                .subscriptionCount
                                .value > 0
                        ) {

                            _wakeWordEvents
                                .tryEmit(
                                    event
                                )

                        } else {

                            handleBackgroundWakeWord(
                                event
                            )
                        }
                    }


                    /*
                     * =========================================
                     * AUDIO LEVEL
                     * =========================================
                     */

                    is WakeWordEvent.AudioLevel -> {

                        _wakeWordEvents
                            .tryEmit(
                                event
                            )
                    }


                    /*
                     * =========================================
                     * STATUS
                     * =========================================
                     */

                    is WakeWordEvent.StatusChanged -> {

                        _wakeWordEvents
                            .tryEmit(
                                event
                            )
                    }


                    /*
                     * =========================================
                     * ERROR
                     * =========================================
                     */

                    is WakeWordEvent.Error -> {

                        _wakeWordEvents
                            .tryEmit(
                                event
                            )

                        Log.w(
                            TAG,
                            "Wake word error: " +
                                    event.message
                        )
                    }
                }
            }

            detectorRunning =
                true

            Log.i(
                TAG,
                "WakeWordDetector started"
            )

        } catch (e: Exception) {

            detectorRunning =
                false

            Log.e(
                TAG,
                "Failed to start WakeWordDetector",
                e
            )
        }
    }


    /*
     * =========================================================
     * STOP WAKE WORD ENGINE
     * =========================================================
     */

    private fun stopWakeWordEngine() {

        detectorRunning =
            false

        try {

            wakeWordDetector?.stop()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "WakeWordDetector stop failed",
                e
            )
        }
    }


    /*
     * =========================================================
     * MUTE
     * =========================================================
     */

    fun muteForTts() {

        detectorMuted =
            true

        detectorRunning =
            false

        try {

            wakeWordDetector
                ?.muteForTts()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to mute detector",
                e
            )
        }

        Log.d(
            TAG,
            "WakeWordDetector muted"
        )
    }


    /*
     * =========================================================
     * UNMUTE
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

        if (
            !hasMicrophonePermission()
        ) {
            return
        }

        detectorMuted =
            false

        if (
            wakeWordDetector == null
        ) {

            createWakeWordDetector()
        }

        try {

            wakeWordDetector
                ?.unmuteAfterTts()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Detector unmute failed",
                e
            )
        }

        startWakeWordEngine()
    }


    /*
     * =========================================================
     * BACKGROUND WAKE WORD
     * =========================================================
     */

    private fun handleBackgroundWakeWord(
        event: WakeWordEvent.Detected
    ) {

        if (
            processingBackgroundCommand
        ) {
            return
        }

        if (
            _serviceStatus.value !=
            ServiceState.RUNNING
        ) {
            return
        }

        processingBackgroundCommand =
            true

        detectorMuted =
            true


        /*
         * =====================================================
         * INLINE COMMAND
         *
         * Contoh:
         *
         * "JARVIS buka YouTube"
         *
         * langsung diproses tanpa menunggu
         * perintah kedua.
         * =====================================================
         */

        val inlineCommand =
            event.inlineCommand
                ?.trim()
                .orEmpty()

        if (
            inlineCommand.isNotBlank()
        ) {

            processBackgroundCommand(
                inlineCommand
            )

            return
        }


        /*
         * =====================================================
         * WAKE WORD SAJA
         *
         * "JARVIS"
         *
         * JARVIS menjawab:
         *
         * "Ya, Tuan."
         *
         * kemudian mulai mendengarkan.
         * =====================================================
         */

        speakBackground(
            "Ya, Tuan."
        ) {

            startBackgroundCommandListening()
        }
    }


    /*
     * =========================================================
     * BACKGROUND COMMAND LISTENING
     * =========================================================
     */

    private fun startBackgroundCommandListening() {

        if (
            _serviceStatus.value !=
            ServiceState.RUNNING
        ) {

            processingBackgroundCommand =
                false

            return
        }

        detectorMuted =
            true

        commandTimeoutJob?.cancel()

        speechManager.startListening()


        /*
         * Maksimum 12 detik untuk perintah.
         */

        commandTimeoutJob =
            serviceScope.launch {

                delay(12000)

                if (
                    processingBackgroundCommand
                ) {

                    try {

                        speechManager
                            .stopListening()

                    } catch (_: Exception) {
                    }

                    speakBackground(
                        "Maaf Tuan, saya tidak mendengar perintah Anda."
                    ) {

                        finishBackgroundCommand()
                    }
                }
            }
    }


    /*
     * =========================================================
     * OBSERVE SPEECH MANAGER
     * =========================================================
     */

    private fun observeSpeechManager() {

        speechCollectionJob =
            serviceScope.launch {

                speechManager
                    .speechState
                    .collect { state ->

                        /*
                         * Jangan mengambil hasil
                         * SpeechManager jika service
                         * tidak sedang menjalankan
                         * background command.
                         */

                        if (
                            !processingBackgroundCommand
                        ) {
                            return@collect
                        }

                        when (state) {

                            is SpeechState.Listening -> {

                                /*
                                 * Masih mendengarkan.
                                 */
                            }


                            is SpeechState.Processing -> {

                                /*
                                 * SpeechRecognizer sedang
                                 * memproses hasil.
                                 */
                            }


                            is SpeechState.Success -> {

                                processBackgroundCommand(
                                    state.spokenText
                                )
                            }


                            is SpeechState.Error -> {

                                Log.w(
                                    TAG,
                                    "Background speech error: " +
                                            state.message
                                )

                                speakBackground(
                                    "Maaf Tuan, ${state.message}"
                                ) {

                                    finishBackgroundCommand()
                                }
                            }


                            SpeechState.Idle -> {
                                /*
                                 * Tidak melakukan apa-apa.
                                 */
                            }
                        }
                    }
            }
    }


    /*
     * =========================================================
     * PROCESS BACKGROUND COMMAND
     * =========================================================
     */

    private fun processBackgroundCommand(
        command: String
    ) {

        if (
            !processingBackgroundCommand
        ) {
            return
        }

        val cleanCommand =
            command.trim()

        if (
            cleanCommand.isBlank()
        ) {
            return
        }

        commandTimeoutJob?.cancel()


        serviceScope.launch {

            try {

                Log.i(
                    TAG,
                    "Processing background command: " +
                            cleanCommand
                )


                /*
                 * JarvisBrain menggunakan
                 * JarvisActionExecutor di dalam
                 * alur command yang sekarang.
                 *
                 * Karena context berasal dari Service,
                 * Intent akan memiliki FLAG_ACTIVITY_NEW_TASK
                 * pada executor sehingga aplikasi dapat
                 * dibuka walaupun UI JARVIS ditutup.
                 */

                val response =
                    jarvisBrain
                        .processCommand(
                            cleanCommand
                        )


                Log.i(
                    TAG,
                    "Background command completed: " +
                            response.displayText
                )


                speakBackground(
                    response.spokenText
                ) {

                    finishBackgroundCommand()
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Background command failed",
                    e
                )

                speakBackground(
                    "Maaf Tuan, terjadi kesalahan saat menjalankan perintah."
                ) {

                    finishBackgroundCommand()
                }
            }
        }
    }


    /*
     * =========================================================
     * BACKGROUND TTS
     * =========================================================
     */

    private fun speakBackground(
        text: String,
        onDone: () -> Unit
    ) {

        if (
            _serviceStatus.value !=
            ServiceState.RUNNING
        ) {

            processingBackgroundCommand =
                false

            return
        }

        detectorMuted =
            true

        stopWakeWordEngine()


        ttsManager.speak(
            text
        ) {

            serviceScope.launch {

                delay(250)

                if (
                    _serviceStatus.value ==
                    ServiceState.RUNNING
                ) {

                    onDone()

                } else {

                    processingBackgroundCommand =
                        false
                }
            }
        }
    }


    /*
     * =========================================================
     * FINISH BACKGROUND COMMAND
     * =========================================================
     */

    private fun finishBackgroundCommand() {

        commandTimeoutJob?.cancel()

        try {

            speechManager.cancel()

        } catch (_: Exception) {
        }

        processingBackgroundCommand =
            false


        if (
            _serviceStatus.value !=
            ServiceState.RUNNING
        ) {
            return
        }


        /*
         * Beri waktu sebentar agar microphone
         * benar-benar dilepas sebelum detector
         * dimulai kembali.
         */

        serviceScope.launch {

            delay(400)

            if (
                _serviceStatus.value ==
                ServiceState.RUNNING
            ) {

                detectorMuted =
                    false

                startWakeWordEngine()
            }
        }
    }


    /*
     * =========================================================
     * NOTIFICATION CHANNEL
     * =========================================================
     */

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "JARVIS Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {

                description =
                    "Layanan suara JARVIS"
            }

        manager.createNotificationChannel(
            channel
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

        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.M
                    ) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }

        val openIntent =
    PendingIntent.getActivity(
        this,
        0,
        Intent(
            this,
            MainActivity::class.java
        ).apply {
            this.flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
    )


        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle(
                title
            )
            .setContentText(
                content
            )
            .setContentIntent(
                openIntent
            )
            .setOngoing(
                !isPaused
            )
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }


    /*
     * =========================================================
     * START FOREGROUND
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


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MICROPHONE
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }


    /*
     * =========================================================
     * UPDATE NOTIFICATION
     * =========================================================
     */

    private fun updateNotification(
        title: String,
        content: String,
        isPaused: Boolean
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                title,
                content,
                isPaused
            )
        )
    }
}