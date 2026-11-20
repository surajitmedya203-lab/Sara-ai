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
import com.example.accessibility.SaraAccessibilityService
import com.example.actions.DeviceActionExecutor
import com.example.actions.ParsedSaraIntent
import com.example.actions.SaraCommandParser
import com.example.data.ai.FirebaseAiRepository
import com.example.data.local.MemoryNoteEntity
import com.example.data.local.PersonalTaskEntity
import com.example.data.local.SaraDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.screenshare.ScreenShareConsentActivity
import com.example.screenshare.ScreenShareService
import com.example.voice.SaraSpeechManager
import com.example.voice.SaraTtsManager
import com.example.voice.SpeechUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "OverlayAssistantService"

private enum class OverlayListenMode {
    COMMAND,
    DICTATE_TO_APP
}

/**
 * Floating Display-Over-Other-Apps ("SYSTEM_ALERT_WINDOW") assistant bubble & expandable Maya card.
 * Supports:
 * - Dragging over any external app
 * - Voice Typing ("Dictate"): listens and types spoken words directly into the focused input field
 * - Look at Screen ("See Screen"): captures screen screenshot + visible text and uses Gemini 3.8 Flash
 * - Voice commands & live Gemini chat in Maya's female voice
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
    private var dictateButtonView: Button? = null
    private var screenShareButtonView: Button? = null
    private var accessibilityBannerBtn: Button? = null

    private var isExpanded = false
    private var listenMode = OverlayListenMode.COMMAND

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        actionExecutor = DeviceActionExecutor(this)
        prefsRepo = UserPreferencesRepository(this)
        ttsManager = SaraTtsManager(this)

        serviceScope.launch {
            prefsRepo.preferences.collect { prefs ->
                EdgeGlowController.setUserPreference(this@OverlayAssistantService, prefs.edgeGlowEnabled)
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
                        EdgeGlowController.updateMode(EdgeGlowMode.LISTENING)
                        val partial = st.partialText
                        statusTitleView?.text = when {
                            partial.isNotBlank() -> "\"$partial\""
                            listenMode == OverlayListenMode.DICTATE_TO_APP -> "বলুন, টাইপ করছি…"
                            else -> "শুনছি… বলুন"
                        }
                        if (listenMode == OverlayListenMode.DICTATE_TO_APP) {
                            dictateButtonView?.text = "⏹️ থামান"
                        } else {
                            micButtonView?.text = "⏹️ থামান"
                        }
                    }
                    is SpeechUiState.Error -> {
                        EdgeGlowController.updateMode(
                            if (ScreenShareService.isScreenSharing.value) EdgeGlowMode.SCREEN_SHARING else EdgeGlowMode.AMBIENT
                        )
                        statusTitleView?.text = st.message
                        resetButtonLabels()
                    }
                    SpeechUiState.Idle -> {
                        EdgeGlowController.updateMode(
                            if (ScreenShareService.isScreenSharing.value) EdgeGlowMode.SCREEN_SHARING else EdgeGlowMode.AMBIENT
                        )
                        resetButtonLabels()
                    }
                }
            }
        }

        serviceScope.launch {
            SaraAccessibilityService.isServiceConnected.collect { connected ->
                accessibilityBannerBtn?.visibility = if (connected) View.GONE else View.VISIBLE
            }
        }

        serviceScope.launch {
            ScreenShareService.isScreenSharing.collect { sharing ->
                screenShareButtonView?.text = if (sharing) "🛑 শেয়ার বন্ধ" else "📺 শেয়ার"
                if (speechManager?.state?.value !is SpeechUiState.Listening) {
                    EdgeGlowController.updateMode(
                        if (sharing) EdgeGlowMode.SCREEN_SHARING else EdgeGlowMode.AMBIENT
                    )
                }
            }
        }

        ensureNotificationChannel()
    }

    private fun resetButtonLabels() {
        micButtonView?.text = "🎤 বলুন"
        dictateButtonView?.text = "⌨️ টাইপ"
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
        EdgeGlowController.updateOverlayState(
            context = this,
            active = true,
            mode = if (ScreenShareService.isScreenSharing.value) EdgeGlowMode.SCREEN_SHARING else EdgeGlowMode.AMBIENT
        )
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

        val cardWidth = (316 * dp).toInt()
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

        // Header row: Avatar preview + Title + Minimize/Close
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.img_maya_wave)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        characterPreviewView = avatar
        header.addView(avatar, LinearLayout.LayoutParams((52 * dp).toInt(), (68 * dp).toInt()))

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        val title = TextView(this).apply {
            text = "Maya • Screen & Voice"
            setTextColor(AndroidColor.parseColor("#FFF472B6"))
            textSize = 15f
        }
        val statusTv = TextView(this).apply {
            text = "টাইপ, স্ক্রিন দেখা বা Gemini"
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

        // Optional Accessibility Setup Shortcut Banner if not yet enabled
        val isAccEnabled = SaraAccessibilityService.isAccessibilityEnabled(this)
        val accBtn = Button(this).apply {
            text = "⚠️ সব অ্যাপে টাইপ ও স্ক্রিন দেখার জন্য Accessibility চালু করুন"
            textSize = 12f
            setTextColor(AndroidColor.parseColor("#FDE68A"))
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(AndroidColor.parseColor("#33F59E0B"))
            }
            visibility = if (isAccEnabled) View.GONE else View.VISIBLE
            setOnClickListener {
                detachExpandedPanel()
                SaraAccessibilityService.openAccessibilitySettings(this@OverlayAssistantService)
            }
        }
        accessibilityBannerBtn = accBtn
        card.addView(
            accBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * dp).toInt()
            }
        )

        // Reply display box
        val replyTv = TextView(this).apply {
            text = "যেকোনো অ্যাপের লেখার ঘরে ট্যাপ করে '⌨️ টাইপ' চাপলে আপনার কথা সরাসরি লিখে দেবো, আর '👁️ স্ক্রিন' চাপলে আপনার স্ক্রিন দেখে বলে দেবো!"
            setTextColor(AndroidColor.WHITE)
            textSize = 13f
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

        // Primary Row: Voice Type into Focused App | Look at Screen
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dictateBtn = Button(this).apply {
            text = "⌨️ টাইপ"
            textSize = 12.5f
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(AndroidColor.parseColor("#059669"))
            }
            setOnClickListener {
                startDictateToFocusedApp()
            }
        }
        dictateButtonView = dictateBtn
        row1.addView(
            dictateBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * dp).toInt()
            }
        )

        val screenBtn = Button(this).apply {
            text = "👁️ দেখো"
            textSize = 12f
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(AndroidColor.parseColor("#7C3AED"))
            }
            setOnClickListener {
                inspectCurrentScreenWithGemini("আমার স্ক্রিনে কী আছে দেখে সংক্ষেপে বলো এবং কী করা যায় জানাও।")
            }
        }
        row1.addView(
            screenBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * dp).toInt()
            }
        )

        val shareBtn = Button(this).apply {
            text = if (ScreenShareService.isScreenSharing.value) "🛑 শেয়ার বন্ধ" else "📺 শেয়ার"
            textSize = 12f
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(AndroidColor.parseColor("#0284C7"))
            }
            setOnClickListener {
                if (ScreenShareService.isScreenSharing.value) {
                    ScreenShareService.stopScreenShare(this@OverlayAssistantService)
                    statusTitleView?.text = "স্ক্রিন শেয়ার বন্ধ"
                } else {
                    detachExpandedPanel()
                    ScreenShareConsentActivity.launch(this@OverlayAssistantService)
                }
            }
        }
        screenShareButtonView = shareBtn
        row1.addView(
            shareBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(row1)

        // Secondary Row: Voice Command/Chat | Open Main App
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micBtn = Button(this).apply {
            text = "🎤 বলুন"
            textSize = 12.5f
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(AndroidColor.parseColor("#DB2777"))
            }
            setOnClickListener {
                serviceScope.launch {
                    val prefs = prefsRepo.preferences.first()
                    if (speechManager?.state?.value is SpeechUiState.Listening) {
                        speechManager?.stopListening()
                    } else {
                        listenMode = OverlayListenMode.COMMAND
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
        row2.addView(
            micBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                marginEnd = (6 * dp).toInt()
            }
        )

        val openBtn = Button(this).apply {
            text = "অ্যাপ খুলুন"
            textSize = 12.5f
            setTextColor(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
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
        row2.addView(
            openBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
        )
        card.addView(
            row2,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * dp).toInt()
            }
        )

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

    private fun startDictateToFocusedApp() {
        if (!SaraAccessibilityService.isAccessibilityEnabled(this)) {
            accessibilityBannerBtn?.visibility = View.VISIBLE
            replyBodyView?.text = "সব অ্যাপে টাইপ করতে আগে Accessibility অপশন থেকে Sara (Maya) চালু করুন।"
            SaraAccessibilityService.openAccessibilitySettings(this)
            return
        }

        serviceScope.launch {
            val prefs = prefsRepo.preferences.first()
            if (speechManager?.state?.value is SpeechUiState.Listening) {
                speechManager?.stopListening()
                return@launch
            }
            listenMode = OverlayListenMode.DICTATE_TO_APP
            ttsManager?.stop()
            characterPreviewView?.setImageResource(R.drawable.img_maya_idle)
            statusTitleView?.text = "যেকোনো বক্সে কার্সর রেখে বলুন…"
            speechManager?.startListening(
                languageCode = prefs.languageCode,
                onFinalResult = { transcript ->
                    val res = SaraAccessibilityService.typeIntoFocusedField(
                        context = this@OverlayAssistantService,
                        dictatedText = transcript,
                        append = true
                    )
                    val isBn = prefs.languageCode.startsWith("bn") || transcript.any { it in '\u0980'..'\u09FF' }
                    val msg = if (isBn) res.messageBn else res.messageEn
                    statusTitleView?.text = if (res.success) "Typed!" else "Input needed"
                    replyBodyView?.text = msg
                    if (res.success) {
                        characterPreviewView?.setImageResource(R.drawable.img_maya_wave)
                    }
                },
                onError = { err ->
                    statusTitleView?.text = err
                }
            )
        }
    }

    private fun inspectCurrentScreenWithGemini(userPrompt: String) {
        val isSharingLive = ScreenShareService.isScreenSharing.value
        val isAccReady = SaraAccessibilityService.isAccessibilityEnabled(this)

        if (!isSharingLive && !isAccReady) {
            accessibilityBannerBtn?.visibility = View.VISIBLE
            replyBodyView?.text = "স্ক্রিন দেখার জন্য '📺 শেয়ার' চাপুন অথবা Accessibility থেকে Sara চালু করুন।"
            ScreenShareConsentActivity.launch(this)
            return
        }

        serviceScope.launch {
            val prefs = prefsRepo.preferences.first()
            statusTitleView?.text = "স্ক্রিন দেখছি…"
            EdgeGlowController.updateMode(EdgeGlowMode.THINKING)
            characterPreviewView?.setImageResource(R.drawable.img_maya_idle)

            val summary = if (isAccReady) {
                SaraAccessibilityService.readCurrentScreenContext()
            } else {
                com.example.accessibility.ScreenContextSummary("", "")
            }

            // Prefer live MediaProjection frame when Screen Share is active; otherwise use Accessibility screenshot
            var screenBitmap = ScreenShareService.getLatestFrameCopy()
            if (screenBitmap == null && isAccReady) {
                expandedView?.visibility = View.INVISIBLE
                bubbleView?.visibility = View.INVISIBLE
                delay(200L)
                screenBitmap = suspendCancellableCoroutine { cont ->
                    SaraAccessibilityService.captureCurrentScreenBitmap { bmp ->
                        if (cont.isActive) cont.resume(bmp)
                    }
                }
                bubbleView?.visibility = View.VISIBLE
                expandedView?.visibility = View.VISIBLE
            }

            statusTitleView?.text = "Gemini বিশ্লেষণ করছে…"

            try {
                val aiRes = aiRepo.analyzeScreenWithGemini(
                    userPrompt = userPrompt,
                    screenBitmap = screenBitmap,
                    screenTextSummary = summary.visibleText,
                    activePackageName = summary.activePackage,
                    userName = prefs.userName,
                    languageCode = prefs.languageCode,
                    onStreamingChunk = { partial ->
                        replyBodyView?.text = partial
                    }
                )
                replyBodyView?.text = aiRes.replyText
                statusTitleView?.text = if (aiRes.isError) "Error" else "Screen Ready"
                if (prefs.speakResponses) {
                    EdgeGlowController.updateMode(EdgeGlowMode.SPEAKING)
                    ttsManager?.speak(aiRes.replyText, prefs.languageCode)
                }
            } finally {
                screenBitmap?.recycle()
                EdgeGlowController.updateMode(
                    if (ScreenShareService.isScreenSharing.value) EdgeGlowMode.SCREEN_SHARING else EdgeGlowMode.AMBIENT
                )
            }
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

            when (parsed) {
                is ParsedSaraIntent.ToggleScreenShare -> {
                    if (parsed.enable) {
                        detachExpandedPanel()
                        ScreenShareConsentActivity.launch(this@OverlayAssistantService)
                    } else {
                        ScreenShareService.stopScreenShare(this@OverlayAssistantService)
                        replyBodyView?.text = if (isBn) "স্ক্রিন শেয়ার বন্ধ করা হয়েছে।" else "Screen share stopped."
                        statusTitleView?.text = "Share Stopped"
                    }
                    return@launch
                }
                is ParsedSaraIntent.InspectScreen -> {
                    inspectCurrentScreenWithGemini(text)
                    return@launch
                }
                is ParsedSaraIntent.TypeDictatedText -> {
                    val res = SaraAccessibilityService.typeIntoFocusedField(
                        context = this@OverlayAssistantService,
                        dictatedText = parsed.textToType,
                        append = true
                    )
                    val msg = if (isBn) res.messageBn else res.messageEn
                    replyBodyView?.text = msg
                    statusTitleView?.text = if (res.success) "Typed!" else "Notice"
                    if (prefs.speakResponses && !res.success) {
                        ttsManager?.speak(msg, prefs.languageCode)
                    }
                    return@launch
                }
                else -> Unit
            }

            EdgeGlowController.updateMode(EdgeGlowMode.THINKING)
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
                    dao.insertMemory(MemoryNoteEntity(keyTopic = parsed.topic, content = parsed.content))
                    if (isBn) "মনে রাখলাম: ${parsed.content}" else "Saved memory: ${parsed.content}"
                }
                is ParsedSaraIntent.AddTask -> {
                    dao.insertTask(PersonalTaskEntity(title = parsed.title))
                    if (isBn) "কাজ যোগ করেছি: ${parsed.title}" else "Added task: ${parsed.title}"
                }
                else -> {
                    val sharedFrame = if (ScreenShareService.isScreenSharing.value) {
                        ScreenShareService.getLatestFrameCopy()
                    } else {
                        null
                    }
                    if (sharedFrame != null) {
                        statusTitleView?.text = "স্ক্রিন + Gemini…"
                        try {
                            aiRepo.analyzeScreenWithGemini(
                                userPrompt = text,
                                screenBitmap = sharedFrame,
                                screenTextSummary = "",
                                activePackageName = "",
                                userName = prefs.userName,
                                languageCode = prefs.languageCode,
                                onStreamingChunk = { partial ->
                                    replyBodyView?.text = partial
                                }
                            ).replyText
                        } finally {
                            sharedFrame.recycle()
                        }
                    } else {
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
            }

            replyBodyView?.text = reply
            statusTitleView?.text = "Maya Ready"
            if (prefs.speakResponses) {
                EdgeGlowController.updateMode(EdgeGlowMode.SPEAKING)
                ttsManager?.speak(reply, prefs.languageCode)
            } else {
                EdgeGlowController.updateMode(
                    if (ScreenShareService.isScreenSharing.value) EdgeGlowMode.SCREEN_SHARING else EdgeGlowMode.AMBIENT
                )
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
        dictateButtonView = null
        screenShareButtonView = null
        accessibilityBannerBtn = null
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
        EdgeGlowController.updateOverlayState(this, active = false)
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
        EdgeGlowController.updateOverlayState(this, active = false)
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
