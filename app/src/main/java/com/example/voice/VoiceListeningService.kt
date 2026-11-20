package com.example.voice

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceServiceEvent {
    data class TranscriptCaptured(val text: String) : VoiceServiceEvent
    data class CaptureError(val message: String) : VoiceServiceEvent
}

/**
 * Process-local reactive bridge between [VoiceListeningService] and ViewModels/UI.
 */
object VoiceServiceBus {
    private val _speechState = MutableStateFlow<SpeechUiState>(SpeechUiState.Idle)
    val speechState: StateFlow<SpeechUiState> = _speechState.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isContinuousMode = MutableStateFlow(false)
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    private val _events = MutableSharedFlow<VoiceServiceEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<VoiceServiceEvent> = _events.asSharedFlow()

    internal fun updateSpeechState(state: SpeechUiState) {
        _speechState.value = state
    }

    internal fun updateRmsLevel(level: Float) {
        _rmsLevel.value = level
    }

    internal fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    internal fun setContinuousMode(continuous: Boolean) {
        _isContinuousMode.value = continuous
    }

    internal fun emitTranscript(text: String) {
        _events.tryEmit(VoiceServiceEvent.TranscriptCaptured(text))
    }

    internal fun emitError(message: String) {
        _events.tryEmit(VoiceServiceEvent.CaptureError(message))
    }

    fun clearError() {
        if (_speechState.value is SpeechUiState.Error) {
            _speechState.value = SpeechUiState.Idle
        }
    }
}

/**
 * Foreground microphone [Service] wrapping Android [SpeechRecognizer] via [SaraSpeechManager]
 * to capture user voice commands reliably.
 */
class VoiceListeningService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VoiceListeningService = this@VoiceListeningService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var speechManager: SaraSpeechManager

    private var currentLanguageCode: String = "bn-BD"
    private var continuousMode: Boolean = false
    private var isPausedForTts: Boolean = false
    private var pendingRestartRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        speechManager = SaraSpeechManager(
            context = applicationContext,
            onStateUpdate = { state -> VoiceServiceBus.updateSpeechState(state) },
            onRmsUpdate = { level -> VoiceServiceBus.updateRmsLevel(level) }
        )
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                stopServiceAndRecognizer()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_FOR_TTS -> {
                isPausedForTts = true
                cancelPendingRestart()
                mainHandler.post { speechManager.stopListening() }
                return START_NOT_STICKY
            }
            ACTION_RESUME_AFTER_TTS -> {
                isPausedForTts = false
                currentLanguageCode = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: currentLanguageCode
                if (continuousMode && VoiceServiceBus.isServiceRunning.value) {
                    scheduleRecognitionRestart(delayMs = 300L)
                }
                return START_NOT_STICKY
            }
            ACTION_START_LISTENING, null -> {
                currentLanguageCode = intent?.getStringExtra(EXTRA_LANGUAGE_CODE) ?: currentLanguageCode
                continuousMode = intent?.getBooleanExtra(EXTRA_CONTINUOUS, false) ?: false
                isPausedForTts = false
                VoiceServiceBus.setContinuousMode(continuousMode)

                if (!hasRecordAudioPermission()) {
                    VoiceServiceBus.emitError("Microphone permission needed to start voice listening.")
                    stopServiceAndRecognizer()
                    return START_NOT_STICKY
                }

                promoteToForegroundSafely()
                VoiceServiceBus.setServiceRunning(true)
                beginRecognitionOnMainThread()
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun promoteToForegroundSafely() {
        val notification = buildListeningNotification(continuousMode)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                // Keep running as a standard bound/started service while app is interactive.
            }
        }
    }

    private fun beginRecognitionOnMainThread() {
        cancelPendingRestart()
        mainHandler.post {
            if (isPausedForTts) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
                val msg = "Speech recognition service is unavailable on this device. You can type your command below."
                VoiceServiceBus.updateSpeechState(SpeechUiState.Error(msg))
                VoiceServiceBus.emitError(msg)
                stopServiceAndRecognizer()
                return@post
            }

            speechManager.startListening(
                languageCode = currentLanguageCode,
                onFinalResult = { transcript ->
                    VoiceServiceBus.emitTranscript(transcript)
                    if (continuousMode && !isPausedForTts) {
                        scheduleRecognitionRestart(delayMs = 550L)
                    } else if (!continuousMode) {
                        stopServiceAndRecognizer()
                    }
                },
                onError = { errMsg ->
                    if (continuousMode && !isPausedForTts && isRecoverableContinuousError(errMsg)) {
                        scheduleRecognitionRestart(delayMs = 650L)
                    } else {
                        VoiceServiceBus.emitError(errMsg)
                        stopServiceAndRecognizer()
                    }
                }
            )
        }
    }

    private fun isRecoverableContinuousError(message: String): Boolean {
        return message.contains("কোনো কথা শোনা যায়নি") ||
            message.contains("ভয়েস বুঝতে পারিনি") ||
            message.contains("busy", ignoreCase = true)
    }

    private fun scheduleRecognitionRestart(delayMs: Long) {
        cancelPendingRestart()
        val runnable = Runnable {
            if (VoiceServiceBus.isServiceRunning.value && continuousMode && !isPausedForTts) {
                beginRecognitionOnMainThread()
            }
        }
        pendingRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRestart() {
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
    }

    private fun stopServiceAndRecognizer() {
        cancelPendingRestart()
        isPausedForTts = false
        continuousMode = false
        VoiceServiceBus.setContinuousMode(false)
        VoiceServiceBus.setServiceRunning(false)
        VoiceServiceBus.updateRmsLevel(0f)
        VoiceServiceBus.updateSpeechState(SpeechUiState.Idle)
        mainHandler.post {
            speechManager.stopListening()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.voice_service_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildListeningNotification(continuous: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceListeningService::class.java).apply {
            action = ACTION_STOP_LISTENING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (continuous) {
            getString(R.string.voice_service_notif_continuous)
        } else {
            getString(R.string.voice_service_notif_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_service_notif_title))
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.voice_service_stop_action), stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        cancelPendingRestart()
        speechManager.stopListening()
        VoiceServiceBus.setServiceRunning(false)
        VoiceServiceBus.setContinuousMode(false)
        VoiceServiceBus.updateRmsLevel(0f)
        VoiceServiceBus.updateSpeechState(SpeechUiState.Idle)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sara_voice_listening"
        private const val NOTIFICATION_ID = 4041

        const val ACTION_START_LISTENING = "com.example.voice.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.example.voice.ACTION_STOP_LISTENING"
        const val ACTION_PAUSE_FOR_TTS = "com.example.voice.ACTION_PAUSE_FOR_TTS"
        const val ACTION_RESUME_AFTER_TTS = "com.example.voice.ACTION_RESUME_AFTER_TTS"

        const val EXTRA_LANGUAGE_CODE = "extra_language_code"
        const val EXTRA_CONTINUOUS = "extra_continuous"

        fun startListening(
            context: Context,
            languageCode: String,
            continuous: Boolean = false
        ) {
            val intent = Intent(context, VoiceListeningService::class.java).apply {
                action = ACTION_START_LISTENING
                putExtra(EXTRA_LANGUAGE_CODE, languageCode)
                putExtra(EXTRA_CONTINUOUS, continuous)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun stopListening(context: Context) {
            val intent = Intent(context, VoiceListeningService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                VoiceServiceBus.setServiceRunning(false)
                VoiceServiceBus.updateSpeechState(SpeechUiState.Idle)
            }
        }

        fun pauseForTts(context: Context) {
            if (!VoiceServiceBus.isServiceRunning.value) return
            val intent = Intent(context, VoiceListeningService::class.java).apply {
                action = ACTION_PAUSE_FOR_TTS
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun resumeAfterTts(context: Context, languageCode: String) {
            if (!VoiceServiceBus.isServiceRunning.value) return
            val intent = Intent(context, VoiceListeningService::class.java).apply {
                action = ACTION_RESUME_AFTER_TTS
                putExtra(EXTRA_LANGUAGE_CODE, languageCode)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
