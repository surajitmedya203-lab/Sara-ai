package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

private const val TAG = "SaraAccessibility"

data class TypeIntoAppResult(
    val success: Boolean,
    val messageBn: String,
    val messageEn: String
)

data class ScreenContextSummary(
    val activePackage: String,
    val visibleText: String
)

/**
 * Accessibility service enabling Maya to:
 * 1) Type user-spoken dictation into the focused input field of any app.
 * 2) Read non-password visible text or capture an on-demand screenshot (API 30+) so Gemini can
 *    understand what is currently on the user's screen.
 */
class SaraAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: SaraAccessibilityService? = null

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _activePackageName = MutableStateFlow("")
        val activePackageName: StateFlow<String> = _activePackageName.asStateFlow()

        fun isAccessibilityEnabled(context: Context): Boolean {
            if (_isServiceConnected.value && instance != null) return true
            val expected = "${context.packageName}/${SaraAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val component = splitter.next()
                if (component.equals(expected, ignoreCase = true) ||
                    component.endsWith("/.accessibility.SaraAccessibilityService", ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun typeIntoFocusedField(
            context: Context,
            dictatedText: String,
            append: Boolean = true
        ): TypeIntoAppResult {
            val service = instance
                ?: return TypeIntoAppResult(
                    success = false,
                    messageBn = "সব অ্যাপে টাইপ করতে আগে Accessibility সার্ভিস চালু করুন।",
                    messageEn = "Please enable Maya Accessibility service to type across apps."
                )
            return service.performTypeIntoFocusedInput(context, dictatedText, append)
        }

        fun readCurrentScreenContext(): ScreenContextSummary {
            val service = instance ?: return ScreenContextSummary("", "")
            return service.extractScreenSummary()
        }

        fun captureCurrentScreenBitmap(onComplete: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null) {
                onComplete(null)
                return
            }
            service.captureScreenshotIfSupported(onComplete)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.d(TAG, "SaraAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != packageName) {
            _activePackageName.value = pkg
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "SaraAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _isServiceConnected.value = false
    }

    private fun performTypeIntoFocusedInput(
        context: Context,
        dictatedText: String,
        append: Boolean
    ): TypeIntoAppResult {
        val cleanText = dictatedText.trim()
        if (cleanText.isEmpty()) {
            return TypeIntoAppResult(
                success = false,
                messageBn = "টাইপ করার মতো কোনো কথা পাওয়া যায়নি।",
                messageEn = "No speech text to type."
            )
        }

        val root = activeNonOwnRoot()
            ?: return TypeIntoAppResult(
                success = false,
                messageBn = "কোনো টেক্সট বক্স খোলা পাওয়া যায়নি — আগে যেকোনো লেখার ঘরে ট্যাপ করুন।",
                messageEn = "No active app window found. Tap an input box first."
            )

        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?: findFirstEditableNode(root)

        if (targetNode == null) {
            return TypeIntoAppResult(
                success = false,
                messageBn = "আগে যেকোনো অ্যাপের লেখার ঘরে (Text box) কার্সর বসান, তারপর বলুন।",
                messageEn = "Tap inside any text input box first, then dictate."
            )
        }

        val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetNode.isShowingHintText
        } else {
            false
        }
        val existing = if (append && !isShowingHint) {
            targetNode.text?.toString().orEmpty()
        } else {
            ""
        }
        val mergedText = if (existing.isBlank()) {
            cleanText
        } else if (existing.endsWith(" ") || existing.endsWith("\n")) {
            "$existing$cleanText"
        } else {
            "$existing $cleanText"
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mergedText)
        }
        val setTextOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (setTextOk) {
            return TypeIntoAppResult(
                success = true,
                messageBn = "টাইপ করা হয়েছে: \"$cleanText\"",
                messageEn = "Typed: \"$cleanText\""
            )
        }

        // Fallback: copy to clipboard and paste into editors that disallow ACTION_SET_TEXT
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Maya Voice Dictation", cleanText))
        val pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return if (pasted) {
            TypeIntoAppResult(
                success = true,
                messageBn = "পেস্ট করে টাইপ করা হয়েছে: \"$cleanText\"",
                messageEn = "Typed via paste: \"$cleanText\""
            )
        } else {
            TypeIntoAppResult(
                success = false,
                messageBn = "এই অ্যাপের বক্সে অটো-টাইপ ব্লক করা আছে; লেখাটি ক্লিপবোর্ডে কপি করা হয়েছে।",
                messageEn = "Auto-type blocked by this field; copied text to clipboard."
            )
        }
    }

    private fun extractScreenSummary(): ScreenContextSummary {
        val root = activeNonOwnRoot()
            ?: return ScreenContextSummary(_activePackageName.value, "")
        val pkg = root.packageName?.toString().orEmpty().ifBlank { _activePackageName.value }
        val lines = LinkedHashSet<String>()
        collectReadableText(root, lines, maxChars = 3500)
        return ScreenContextSummary(
            activePackage = pkg,
            visibleText = lines.joinToString("\n")
        )
    }

    private fun captureScreenshotIfSupported(onComplete: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onComplete(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            val scaled = software?.let { scaleDownIfLarge(it, maxDim = 1080) }
                            if (scaled !== software) software?.recycle()
                            onComplete(scaled)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed converting screenshot buffer", e)
                            onComplete(null)
                        } finally {
                            hwBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed with errorCode=$errorCode")
                        onComplete(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception", e)
            onComplete(null)
        }
    }

    private fun scaleDownIfLarge(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            (w * ratio).roundToInt().coerceAtLeast(1),
            (h * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun activeNonOwnRoot(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() != packageName) {
            return activeRoot
        }
        val nonOwnWindowRoot = windows
            .orEmpty()
            .firstOrNull { win ->
                val r = win.root
                r != null && r.packageName?.toString() != packageName
            }
            ?.root
        return nonOwnWindowRoot ?: activeRoot
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findFirstEditableNode(child)
            if (match != null) return match
        }
        return null
    }

    private fun collectReadableText(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        maxChars: Int,
        depth: Int = 0
    ) {
        if (depth > 28) return
        val currentTotal = out.sumOf { it.length + 1 }
        if (currentTotal >= maxChars) return

        // Never read password fields for user privacy & Play policy compliance
        if (!node.isPassword && node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && text.length <= 400) {
                out.add(text)
            } else if (desc.isNotBlank() && desc.length <= 250) {
                out.add(desc)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectReadableText(child, out, maxChars, depth + 1)
        }
    }
}
