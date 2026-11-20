package com.example.screenshare

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.overlay.EdgeGlowController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

private const val TAG = "ScreenShareService"

/**
 * Foreground service running Android's MediaProjection API to share live screen frames
 * with Maya / Gemini 3.8 Flash on demand while showing the compliant status-bar indicator.
 */
class ScreenShareService : Service() {

    companion object {
        private const val CHANNEL_ID = "sara_screen_share_channel"
        private const val NOTIF_ID = 4403
        const val ACTION_START = "com.example.screenshare.START"
        const val ACTION_STOP = "com.example.screenshare.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        private val _isScreenSharing = MutableStateFlow(false)
        val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

        private val _latestFrameTimestampMs = MutableStateFlow(0L)
        val latestFrameTimestampMs: StateFlow<Long> = _latestFrameTimestampMs.asStateFlow()

        private val frameLock = Any()
        private var latestBitmap: Bitmap? = null

        fun createCaptureIntent(context: Context): Intent {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mgr.createScreenCaptureIntent()
        }

        fun startScreenShare(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopScreenShare(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Returns a safe mutable copy of the latest shared screen bitmap (<= 1080px).
         * Caller is responsible for recycling the returned Bitmap when finished.
         */
        fun getLatestFrameCopy(): Bitmap? {
            synchronized(frameLock) {
                val src = latestBitmap ?: return null
                if (src.isRecycled) return null
                return src.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var lastFrameWriteMs = 0L
    private var captureWidth = 720
    private var captureHeight = 1280
    private var captureDpi = 320

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system/user")
            stopShareCleanly()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val (scaledW, scaledH) = scaleDimensions(width, height, maxDim = 1080)
            if (scaledW != captureWidth || scaledH != captureHeight) {
                captureWidth = scaledW
                captureHeight = scaledH
                recreateVirtualDisplaySurface()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopShareCleanly()
            return START_NOT_STICKY
        }

        // Foreground service MUST start before obtaining MediaProjection on Android 14+
        startForegroundMediaProjection()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            Log.w(TAG, "Invalid MediaProjection resultCode/data")
            stopShareCleanly()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, projectionData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopShareCleanly()
            return START_NOT_STICKY
        }

        tearDownProjectionResources()
        mediaProjection = projection

        val metricsPair = computeTargetDisplayMetrics()
        captureWidth = metricsPair.first
        captureHeight = metricsPair.second
        captureDpi = resources.displayMetrics.densityDpi

        val thread = HandlerThread("MayaScreenShare").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        projection.registerCallback(projectionCallback, handler)
        recreateVirtualDisplaySurface()

        _isScreenSharing.value = true
        EdgeGlowController.updateScreenShareState(this, active = true)
        return START_NOT_STICKY
    }

    private fun startForegroundMediaProjection() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 20, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 21, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.screen_share_notif_title))
            .setContentText(getString(R.string.screen_share_notif_text))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.screen_share_stop_action), stopPi)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
    }

    private fun computeTargetDisplayMetrics(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rawW: Int
        val rawH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            rawW = bounds.width().coerceAtLeast(360)
            rawH = bounds.height().coerceAtLeast(640)
        } else {
            val dm = resources.displayMetrics
            rawW = dm.widthPixels.coerceAtLeast(360)
            rawH = dm.heightPixels.coerceAtLeast(640)
        }
        return scaleDimensions(rawW, rawH, maxDim = 1080)
    }

    private fun scaleDimensions(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= maxDim) return w to h
        val ratio = maxDim.toFloat() / longest.toFloat()
        return (w * ratio).roundToInt().coerceAtLeast(240) to
            (h * ratio).roundToInt().coerceAtLeast(240)
    }

    private fun recreateVirtualDisplaySurface() {
        val projection = mediaProjection ?: return
        val handler = captureHandler ?: return

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()

        val reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val now = System.currentTimeMillis()
                // Throttle stored frame updates to ~1 frame every 950ms to stay ultra-lightweight
                if (now - lastFrameWriteMs < 950L && latestBitmap != null) {
                    return@setOnImageAvailableListener
                }
                lastFrameWriteMs = now

                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * captureWidth
                val paddedWidth = captureWidth + rowPadding / pixelStride

                val rawBitmap = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (paddedWidth != captureWidth) {
                    val c = Bitmap.createBitmap(rawBitmap, 0, 0, captureWidth, captureHeight)
                    rawBitmap.recycle()
                    c
                } else {
                    rawBitmap
                }

                synchronized(frameLock) {
                    latestBitmap?.recycle()
                    latestBitmap = cropped
                }
                _latestFrameTimestampMs.value = now
            } catch (e: Exception) {
                Log.w(TAG, "Frame capture failed: ${e.message}")
            } finally {
                image.close()
            }
        }, handler)

        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "MayaScreenShareDisplay",
            captureWidth,
            captureHeight,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun tearDownProjectionResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        synchronized(frameLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }

    private fun stopShareCleanly() {
        tearDownProjectionResources()
        _isScreenSharing.value = false
        EdgeGlowController.updateScreenShareState(this, active = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.screen_share_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.screen_share_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDownProjectionResources()
        _isScreenSharing.value = false
        EdgeGlowController.updateScreenShareState(this, active = false)
    }
}
