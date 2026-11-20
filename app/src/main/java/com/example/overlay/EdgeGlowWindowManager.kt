package com.example.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.sin

private const val TAG = "EdgeGlowWindowManager"

enum class EdgeGlowMode {
    AMBIENT,
    SCREEN_SHARING,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Coordinates a single system-wide, touch-passthrough holographic AI Edge Glow window
 * shared cleanly by OverlayAssistantService and ScreenShareService.
 */
object EdgeGlowController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var glowView: NeonEdgeGlowView? = null

    private var overlayWantsGlow = false
    private var screenShareWantsGlow = false
    private var userEnabled = true
    private var currentMode = EdgeGlowMode.AMBIENT

    fun setUserPreference(context: Context, enabled: Boolean) {
        userEnabled = enabled
        syncWindow(context.applicationContext)
    }

    fun updateOverlayState(context: Context, active: Boolean, mode: EdgeGlowMode = currentMode) {
        overlayWantsGlow = active
        currentMode = mode
        syncWindow(context.applicationContext)
    }

    fun updateScreenShareState(context: Context, active: Boolean) {
        screenShareWantsGlow = active
        if (active && currentMode == EdgeGlowMode.AMBIENT) {
            currentMode = EdgeGlowMode.SCREEN_SHARING
        } else if (!active && currentMode == EdgeGlowMode.SCREEN_SHARING) {
            currentMode = EdgeGlowMode.AMBIENT
        }
        syncWindow(context.applicationContext)
    }

    fun updateMode(mode: EdgeGlowMode) {
        currentMode = mode
        mainHandler.post {
            glowView?.setGlowMode(mode)
        }
    }

    private fun syncWindow(appContext: Context) {
        mainHandler.post {
            val shouldShow = userEnabled &&
                (overlayWantsGlow || screenShareWantsGlow) &&
                Settings.canDrawOverlays(appContext)

            if (shouldShow) {
                attachIfNeeded(appContext)
                glowView?.setGlowMode(
                    if (screenShareWantsGlow && currentMode == EdgeGlowMode.AMBIENT) {
                        EdgeGlowMode.SCREEN_SHARING
                    } else {
                        currentMode
                    }
                )
            } else {
                detachInternal()
            }
        }
    }

    private fun attachIfNeeded(appContext: Context) {
        if (glowView != null) return
        val wm = (windowManager ?: appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .also { windowManager = it }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = NeonEdgeGlowView(appContext)
        try {
            wm.addView(view, params)
            glowView = view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach edge glow window", e)
        }
    }

    private fun detachInternal() {
        val view = glowView ?: return
        glowView = null
        view.stopAnimation()
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
    }
}

private class NeonEdgeGlowView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private var phase = 0f
    private var mode = EdgeGlowMode.AMBIENT

    private val shaderMatrix = Matrix()
    private var sweepShader: SweepGradient? = null

    private val colors = intArrayOf(
        Color.parseColor("#22D3EE"), // Electric Cyan
        Color.parseColor("#38BDF8"), // Sky Blue
        Color.parseColor("#A855F7"), // Luminous Violet
        Color.parseColor("#F472B6"), // Maya Pink
        Color.parseColor("#10B981"), // Emerald Pulse
        Color.parseColor("#22D3EE")  // Wrap smoothly
    )

    private val outerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f * density
        maskFilter = BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val midGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val coreRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
    }

    private val borderRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            phase = anim.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun setGlowMode(newMode: EdgeGlowMode) {
        mode = newMode
        animator.duration = when (newMode) {
            EdgeGlowMode.LISTENING -> 1900L
            EdgeGlowMode.SPEAKING -> 2200L
            EdgeGlowMode.THINKING -> 1600L
            EdgeGlowMode.SCREEN_SHARING -> 3000L
            EdgeGlowMode.AMBIENT -> 4200L
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    fun stopAnimation() {
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            sweepShader = SweepGradient(w / 2f, h / 2f, colors, null)
            outerHaloPaint.shader = sweepShader
            midGlowPaint.shader = sweepShader
            coreRimPaint.shader = sweepShader
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shader = sweepShader ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        shaderMatrix.setRotate(phase, w / 2f, h / 2f)
        shader.setLocalMatrix(shaderMatrix)

        val pulseWave = 0.85f + 0.15f * sin(Math.toRadians((phase * 2f).toDouble())).toFloat()
        val strength = when (mode) {
            EdgeGlowMode.LISTENING -> 1.0f
            EdgeGlowMode.THINKING -> 0.95f
            EdgeGlowMode.SPEAKING -> 0.92f
            EdgeGlowMode.SCREEN_SHARING -> 0.84f
            EdgeGlowMode.AMBIENT -> 0.56f
        } * pulseWave

        outerHaloPaint.alpha = (135 * strength).toInt().coerceIn(40, 220)
        midGlowPaint.alpha = (180 * strength).toInt().coerceIn(70, 240)
        coreRimPaint.alpha = (225 * strength).toInt().coerceIn(90, 255)

        val inset = 2f * density
        val radius = 34f * density
        borderRect.set(inset, inset, w - inset, h - inset)

        canvas.drawRoundRect(borderRect, radius, radius, outerHaloPaint)
        canvas.drawRoundRect(borderRect, radius, radius, midGlowPaint)
        canvas.drawRoundRect(borderRect, radius, radius, coreRimPaint)
    }
}
