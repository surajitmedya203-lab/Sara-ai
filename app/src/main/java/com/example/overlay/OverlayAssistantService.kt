package com.example.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.actions.DeviceActionExecutor
import com.example.actions.ParsedSaraIntent
import com.example.actions.SaraCommandParser
import com.example.data.ai.FirebaseAiRepository
import com.example.data.local.SaraDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.voice.SaraSpeechManager
import com.example.voice.SaraTtsManager
import com.example.voice.SpeechUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "OverlayAssistantService"

/**
 * Floating Display-Over-Other-Apps ("SYSTEM_ALERT_WINDOW") assistant bubble & expandable Maya card.
 * Allows users to drag Maya over any app, tap to expand, speak commands or ask Gemini, and hear
 * Maya respond in her female voice without leaving the current app.
 */
class OverlayAssistantService : Service() {

    companion object {
        private const val CHANNEL_ID = "sara_overlay_channel"
        private const val NOTIF_ID = 4402
        const val ACTION_START = "com.example.overlay.START"
        const val ACTION_STOP = "com.example.overlay.STOP"

        private val _isOverlayRunning = MutableStateFlow(false)
        val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun startOverlay(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayAssistantService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopOverlay(context: Context) {
            val intent = Intent(context, OverlayAssistantService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var expandedView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var expandedParams: WindowManager.LayoutParams? = null

    private var speechManager: SaraSpeechManager? = null
    private var ttsManager: SaraTtsManager? = null
    private lateinit var actionExecutor: DeviceActionExecutor
    private val aiRepo = FirebaseAiRepository()
    private lateinit var prefsRepo: UserPreferencesRepository

    private var statusTitleView: TextView? = null
    private var replyBodyView: TextView? = null
    private var characterPreviewView: ImageView? = null
    private var micButtonView: Button? = null

    private var isExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        actionExecutor = DeviceActionExecutor(this)
        prefsRepo = UserPreferencesRepository(this)
        ttsManager = SaraTtsManager(this)

        serviceScope.launch {
            prefsRepo.preferences.collect { prefs ->
                ttsManager?.applyVoiceConfig(
                    toneId = prefs.voiceTonePreset,
                    preferredVoice = prefs.preferredTtsVoiceName,
                    languageCode = prefs.languageCode
                )
            }
        }

        speechManager = SaraSpeechManager(context = this)

        serviceScope.launch {
            speechManager?.state?.collect { st ->
                when (st) {
                    is SpeechUiState.Listening -> {
                        val partial = st.partialText
                        statusTitleView?.text = if (partial.isNotBlank()) "\"$partial\"" else "শুনছি… বলুন"
                        micButtonView?.text = "থামান (Stop)"
                    }
                    is SpeechUiState.Error -> {
                        statusTitleView?.text = st.message
                        micButtonView?.text = "🎤 বলুন (Speak)"
                    }
                    SpeechUiState.Idle -> {
                        micButtonView?.text = "🎤 বলুন (Speak)"
                    }
                }
            }
        }

        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted.")
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        startForegroundSafely()
        if (bubbleView == null) {
            attachFloatingBubble()
        }
        _isOverlayRunning.value = true
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 10, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, OverlayAssistantService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 11, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.overlay_close_action), stopPi)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
    }

    private fun attachFloatingBubble() {
        val dp = resources.displayMetrics.density
        val sizePx = (64 * dp).toInt()
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (220 * dp).toInt()
        }

        val bubbleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor("#E614192E"))
                setStroke((2.5f * dp).toInt(), AndroidColor.parseColor("#FFF472B6"))
            }
            elevation = 10 * dp
            val avatar = ImageView(this@OverlayAssistantService).apply {
                setImageResource(R.drawable.img_maya_idle)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (6 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                contentDescription = "Floating Maya bubble"
            }
            addView(avatar, LinearLayout.LayoutParams(sizePx, sizePx))
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false

        bubbleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(bubbleContainer, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleExpandedPanel()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleParams = params
        bubbleView = bubbleContainer
        try {
            windowManager.addView(bubbleContainer, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble", e)
            stopSelfCleanly()
        }
    }

    private fun toggleExpandedPanel() {
        if (isExpanded) {
            detachExpandedPanel()
        } else {
            attachExpandedPanel()
        }
    }

    private fun attachExpandedPanel() {
        if (expandedView != null) return
        val dp = resources.displayMetrics.density
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val cardWidth = (300 * dp).toInt()
        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * dp
                setColor(AndroidColor.parseColor("#F2111629"))
                setStroke((1.5f * dp).toInt(), AndroidColor.parseColor("#FFF472B6"))
            }
            elevation = 14 * dp
        }

        // Header row: Avatar preview + Title + Minimize/Close buttons
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.img_maya_wave)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        characterPreviewView = avatar
        header.addView(avatar, LinearLayout.LayoutParams((54 * dp).toInt(), (72 * dp).toInt()))

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        val title = TextView(this).apply {
            text = "Maya • Gemini Live"
            setTextColor(AndroidColor.parseColor("#FFF472B6"))
            textSize = 15f
        }
        val statusTv = TextView(this).apply {
            text = "ট্যাপ করে কথা বলুন"
            setTextColor(AndroidColor.parseColor("#CBD5E1"))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusTitleView = statusTv
        titleCol.addView(title)
        titleCol.addView(statusTv)
        header.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val minBtn = Button(this).apply {
            text = "—"
            setTextColor(AndroidColor.WHITE)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setOnClickListener { detachExpandedPanel() }
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            setTextColor(AndroidColor.parseColor("#FFFCA5A5"))
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setOnClickListener { stopSelfCleanly() }
        }
        header.addView(minBtn, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()))
        header.addView(closeBtn, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()))
        card.addView(header)

        // Reply display box
        val replyTv = TextView(this).apply {
            text = "হাই! আমি যেকোনো অ্যাপের উপর থেকেও আপনার কমান্ড শুনতে ও Gemini দিয়ে উত্তর দিতে পারি।"
            setTextColor(AndroidColor.WHITE)
            textSize = 13.5f
            val p = (10 * dp).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(AndroidColor.parseColor("#26FFFFFF"))
            }
        }
        replyBodyView = replyTv
        val replyLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (8 * dp).toInt()
            bottomMargin = (10 * dp).toInt()
        }
        card.addView(replyTv, replyLp)

        // Action row: Speak Mic Button + Quick Flash + Open App
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micBtn = Button(this).apply {
            text = "🎤 বলুন (Speak)"
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(AndroidColor.parseColor("#DB2777"))
            }
            setOnClickListener {
                serviceScope.launch {
                    val prefs = prefsRepo.preferences.first()
                    if (speechManager?.state?.value is SpeechUiState.Listening) {
                        speechManager?.stopListening()
                    } else {
                        ttsManager?.stop()
                        characterPreviewView?.setImageResource(R.drawable.img_maya_idle)
                        speechManager?.startListening(
                            languageCode = prefs.languageCode,
                            onFinalResult = { transcript -> handleOverlayCommand(transcript) },
                            onError = { err -> statusTitleView?.text = err }
                        )
                    }
                }
            }
        }
        micButtonView = micBtn
        controls.addView(
            micBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                marginEnd = (6 * dp).toInt()
            }
        )

        val openBtn = Button(this).apply {
            text = "অ্যাপ"
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(AndroidColor.parseColor("#2563EB"))
            }
            setOnClickListener {
                detachExpandedPanel()
                val launch = Intent(this@OverlayAssistantService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(launch)
            }
        }
        controls.addView(
            openBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
        )
        card.addView(controls)

        expandedParams = params
        expandedView = card
        isExpanded = true
        try {
            windowManager.addView(card, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach expanded overlay card", e)
            isExpanded = false
        }
    }

    private fun handleOverlayCommand(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        statusTitleView?.text = "আপনি: $text"

        serviceScope.launch {
            val prefs = prefsRepo.preferences.first()
            val isBn = prefs.languageCode.startsWith("bn") || text.any { it in '\u0980'..'\u09FF' }
            val parsed = SaraCommandParser.parse(text)
            val dao = SaraDatabase.getInstance(this@OverlayAssistantService).saraDao()

            val reply: String = when (parsed) {
                is ParsedSaraIntent.ToggleFlashlight -> {
                    val res = actionExecutor.setFlashlight(parsed.enable)
                    if (isBn) res.saraReplyBn else res.saraReplyEn
                }
                is ParsedSaraIntent.OpenApp -> {
                    val res = actionExecutor.launchAppByName(parsed.appName)
                    if (isBn) res.saraReplyBn else res.saraReplyEn
                }
                is ParsedSaraIntent.SetAlarm -> {
                    val res = actionExecutor.setAlarm(parsed.hour, parsed.minute, parsed.label)
                    if (isBn) res.saraReplyBn else res.saraReplyEn
                }
                is ParsedSaraIntent.CallTarget -> {
                    val res = actionExecutor.dialNumberOrContact(parsed.target)
                    if (isBn) res.saraReplyBn else res.saraReplyEn
                }
                is ParsedSaraIntent.WebSearch -> {
                    val res = actionExecutor.webSearch(parsed.query)
                    if (isBn) res.saraReplyBn else res.saraReplyEn
                }
                is ParsedSaraIntent.ReadTimeDate -> SaraCommandParser.formatDateTimeReply(isBn)
                is ParsedSaraIntent.ExpressEmotion -> {
                    when (parsed.emotion) {
                        "LOVE" -> {
                            characterPreviewView?.setImageResource(R.drawable.img_maya_love)
                            if (isBn) "আমিও আপনাকে অনেক ভালোবাসি ${prefs.userName}! 💖" else "Love you too, ${prefs.userName}!"
                        }
                        "ANGRY" -> {
                            characterPreviewView?.setImageResource(R.drawable.img_maya_angry)
                            if (isBn) "হুম! রাগ করে দিয়েছি কিন্তু! 😠" else "Hmph! Now I'm mad!"
                        }
                        else -> {
                            characterPreviewView?.setImageResource(R.drawable.img_maya_wave)
                            if (isBn) "হাই ${prefs.userName}! বলুন কী করতে হবে?" else "Hi ${prefs.userName}!"
                        }
                    }
                }
                is ParsedSaraIntent.SaveMemory -> {
                    dao.insertMemory(com.example.data.local.MemoryNoteEntity(keyTopic = parsed.topic, content = parsed.content))
                    if (isBn) "মনে রাখলাম: ${parsed.content}" else "Saved memory: ${parsed.content}"
                }
                is ParsedSaraIntent.AddTask -> {
                    dao.insertTask(com.example.data.local.PersonalTaskEntity(title = parsed.title))
                    if (isBn) "কাজ যোগ করেছি: ${parsed.title}" else "Added task: ${parsed.title}"
                }
                else -> {
                    statusTitleView?.text = "Gemini চিন্তা করছে…"
                    val aiRes = aiRepo.generateAssistantReply(
                        prompt = text,
                        userName = prefs.userName,
                        languageCode = prefs.languageCode,
                        history = emptyList(),
                        onStreamingChunk = { partial ->
                            replyBodyView?.text = partial
                        }
                    )
                    aiRes.replyText
                }
            }

            replyBodyView?.text = reply
            statusTitleView?.text = "Maya Ready"
            if (prefs.speakResponses) {
                ttsManager?.speak(reply, prefs.languageCode)
            }
        }
    }

    private fun detachExpandedPanel() {
        expandedView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        expandedView = null
        expandedParams = null
        statusTitleView = null
        replyBodyView = null
        characterPreviewView = null
        micButtonView = null
        isExpanded = false
    }

    private fun stopSelfCleanly() {
        detachExpandedPanel()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        _isOverlayRunning.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.overlay_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isOverlayRunning.value = false
        detachExpandedPanel()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        speechManager?.stopListening()
        ttsManager?.shutdown()
        serviceScope.cancel()
    }
}
