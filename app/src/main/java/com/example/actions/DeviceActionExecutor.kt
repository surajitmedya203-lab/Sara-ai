package com.example.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

data class DeviceTelemetry(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkLabel: String,
    val freeStorageGb: String,
    val mediaVolumePercent: Int,
    val ringModeLabel: String,
    val flashlightOn: Boolean,
    val currentTimeLabel: String
)

data class ActionExecutionResult(
    val success: Boolean,
    val category: String,
    val actionSummary: String,
    val saraReplyBn: String,
    val saraReplyEn: String,
    val requiresContactPermission: Boolean = false
)

class DeviceActionExecutor(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var torchCameraId: String? = null

    private val _flashlightOn = MutableStateFlow(false)
    val flashlightOn: StateFlow<Boolean> = _flashlightOn.asStateFlow()

    init {
        discoverTorchCamera()
        registerTorchCallback()
    }

    private fun discoverTorchCamera() {
        try {
            val ids = cameraManager?.cameraIdList ?: return
            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchCameraId = id
                    return
                }
            }
            for (id in ids) {
                val hasFlash = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    torchCameraId = id
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun registerTorchCallback() {
        try {
            cameraManager?.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId) {
                        _flashlightOn.value = enabled
                    }
                }
            }, null)
        } catch (_: Exception) {
        }
    }

    fun vibrateConfirm() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35L)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun setFlashlight(enable: Boolean): ActionExecutionResult {
        val camId = torchCameraId
        if (cameraManager == null || camId == null) {
            return ActionExecutionResult(
                success = false,
                category = "FLASHLIGHT",
                actionSummary = "Flashlight unavailable",
                saraReplyBn = "দুঃখিত, এই ডিভাইসে ফ্ল্যাশলাইট হার্ডওয়্যার পাওয়া যায়নি।",
                saraReplyEn = "Sorry, no camera flash hardware was found on this device."
            )
        }
        return try {
            cameraManager.setTorchMode(camId, enable)
            _flashlightOn.value = enable
            vibrateConfirm()
            ActionExecutionResult(
                success = true,
                category = "FLASHLIGHT",
                actionSummary = if (enable) "Flashlight turned ON" else "Flashlight turned OFF",
                saraReplyBn = if (enable) "ফ্ল্যাশলাইট জ্বালিয়ে দিয়েছি।" else "ফ্ল্যাশলাইট বন্ধ করে দিয়েছি।",
                saraReplyEn = if (enable) "Flashlight turned on." else "Flashlight turned off."
            )
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                category = "FLASHLIGHT",
                actionSummary = "Flashlight failed",
                saraReplyBn = "ফ্ল্যাশলাইট চালু করতে সমস্যা হয়েছে: ${e.localizedMessage ?: "error"}",
                saraReplyEn = "Unable to toggle flashlight: ${e.localizedMessage ?: "error"}"
            )
        }
    }

    fun adjustMediaVolumePercent(percent: Int): ActionExecutionResult {
        val am = audioManager ?: return ActionExecutionResult(
            false, "VOLUME", "Audio service unavailable",
            "অডিও কন্ট্রোল পাওয়া যায়নি।", "Audio control unavailable."
        )
        val clamped = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((clamped / 100f) * max).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        vibrateConfirm()
        return ActionExecutionResult(
            success = true,
            category = "VOLUME",
            actionSummary = "Media volume set to $clamped%",
            saraReplyBn = "মিডিয়া ভলিউম $clamped শতাংশে সেট করেছি।",
            saraReplyEn = "Media volume set to $clamped%."
        )
    }

    fun stepVolume(increase: Boolean): ActionExecutionResult {
        val am = audioManager ?: return ActionExecutionResult(
            false, "VOLUME", "Audio unavailable", "অডিও কন্ট্রোল নেই।", "Audio unavailable."
        )
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val current = getMediaVolumePercent()
        vibrateConfirm()
        return ActionExecutionResult(
            success = true,
            category = "VOLUME",
            actionSummary = if (increase) "Volume raised ($current%)" else "Volume lowered ($current%)",
            saraReplyBn = if (increase) "ভলিউম বাড়িয়ে $current শতাংশ করেছি।" else "ভলিউম কমিয়ে $current শতাংশ করেছি।",
            saraReplyEn = if (increase) "Raised media volume to $current%." else "Lowered media volume to $current%."
        )
    }

    fun setRingerMode(mode: Int): ActionExecutionResult {
        val am = audioManager ?: return ActionExecutionResult(
            false, "SOUND_MODE", "Audio unavailable", "সাউন্ড মোড পরিবর্তন হয়নি।", "Audio unavailable."
        )
        return try {
            am.ringerMode = mode
            vibrateConfirm()
            val (bn, en, sum) = when (mode) {
                AudioManager.RINGER_MODE_SILENT -> Triple("ফোন সাইলেন্ট মোডে দিয়েছি।", "Phone set to silent mode.", "Ringer: Silent")
                AudioManager.RINGER_MODE_VIBRATE -> Triple("ফোন ভাইব্রেট মোডে দিয়েছি।", "Phone set to vibrate mode.", "Ringer: Vibrate")
                else -> Triple("ফোন নরমাল রিং মোডে দিয়েছি।", "Phone set to normal ring mode.", "Ringer: Normal")
            }
            ActionExecutionResult(true, "SOUND_MODE", sum, bn, en)
        } catch (_: SecurityException) {
            openSettingsScreen(Settings.ACTION_SOUND_SETTINGS, "Sound Settings")
        }
    }

    fun getMediaVolumePercent(): Int {
        val am = audioManager ?: return 50
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((cur * 100f) / max).toInt().coerceIn(0, 100)
    }

    fun openSettingsScreen(action: String, label: String): ActionExecutionResult {
        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            vibrateConfirm()
            ActionExecutionResult(
                success = true,
                category = "SETTINGS",
                actionSummary = "Opened $label",
                saraReplyBn = "$label খুলে দিয়েছি।",
                saraReplyEn = "Opened $label."
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ActionExecutionResult(
                    true, "SETTINGS", "Opened System Settings",
                    "সিস্টেম সেটিংস খুলে দিয়েছি।", "Opened System Settings."
                )
            } catch (e: Exception) {
                ActionExecutionResult(
                    false, "SETTINGS", "Could not open settings",
                    "সেটিংস খুলতে পারিনি।", "Unable to open settings."
                )
            }
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String = "Sara Alarm"): ActionExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(
            intent = intent,
            category = "ALARM",
            summary = "Set alarm for %02d:%02d".format(hour, minute),
            replyBn = "সকাল/সময় %02d:%02d-এ অ্যালার্ম সেট করার স্ক্রিন খুলে দিয়েছি।".format(hour, minute),
            replyEn = "Opening alarm setup for %02d:%02d.".format(hour, minute)
        )
    }

    fun setTimer(seconds: Int, label: String = "Sara Timer"): ActionExecutionResult {
        val safeSec = seconds.coerceAtLeast(5)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSec)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val mins = safeSec / 60
        val remSec = safeSec % 60
        val durationLabel = if (mins > 0) "${mins}m ${remSec}s" else "${remSec}s"
        return launchIntent(
            intent = intent,
            category = "TIMER",
            summary = "Timer: $durationLabel",
            replyBn = "$durationLabel এর টাইমার সেট করছি।",
            replyEn = "Setting a timer for $durationLabel."
        )
    }

    fun dialNumberOrContact(query: String): ActionExecutionResult {
        val digits = query.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3 && query.none { it.isLetter() && it !in listOf('c', 'a', 'l') }) {
            return openDialer(digits, digits)
        }
        val cleanedName = query.trim()
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            return ActionExecutionResult(
                success = false,
                category = "CALL",
                actionSummary = "Contacts permission needed for \"$cleanedName\"",
                saraReplyBn = "$cleanedName-কে কল করার জন্য কন্টাক্টস অ্যাক্সেস অনুমতি দিন।",
                saraReplyEn = "Please grant Contacts permission so I can look up $cleanedName.",
                requiresContactPermission = true
            )
        }

        val contact = findContactPhone(cleanedName)
        return if (contact != null) {
            openDialer(contact.second, contact.first)
        } else if (digits.length >= 3) {
            openDialer(digits, digits)
        } else {
            ActionExecutionResult(
                success = false,
                category = "CALL",
                actionSummary = "Contact \"$cleanedName\" not found",
                saraReplyBn = "আপনার কন্টাক্টসে \"$cleanedName\" নামটি খুঁজে পাইনি।",
                saraReplyEn = "I couldn't find \"$cleanedName\" in your contacts."
            )
        }
    }

    private fun openDialer(phoneNumber: String, displayName: String): ActionExecutionResult {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(
            intent = intent,
            category = "CALL",
            summary = "Dial $displayName ($phoneNumber)",
            replyBn = "$displayName-কে কল করার জন্য ডায়ালার খুলে দিয়েছি।",
            replyEn = "Opening dialer for $displayName ($phoneNumber)."
        )
    }

    fun composeSms(targetQuery: String, messageBody: String): ActionExecutionResult {
        val digits = targetQuery.filter { it.isDigit() || it == '+' }
        var resolvedNumber = digits
        var displayName = targetQuery
        if (digits.length < 3 && targetQuery.isNotBlank()) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                return ActionExecutionResult(
                    success = false,
                    category = "SMS",
                    actionSummary = "Contacts permission needed",
                    saraReplyBn = "$targetQuery-কে মেসেজ পাঠাতে কন্টাক্টস অ্যাক্সেস দিন।",
                    saraReplyEn = "Please allow Contacts permission to look up $targetQuery.",
                    requiresContactPermission = true
                )
            }
            val found = findContactPhone(targetQuery)
            if (found != null) {
                displayName = found.first
                resolvedNumber = found.second
            }
        }

        val uri = if (resolvedNumber.isNotBlank()) {
            Uri.parse("smsto:${Uri.encode(resolvedNumber)}")
        } else {
            Uri.parse("smsto:")
        }
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (messageBody.isNotBlank()) putExtra("sms_body", messageBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(
            intent = intent,
            category = "SMS",
            summary = "SMS to ${displayName.ifBlank { "recipient" }}",
            replyBn = "${displayName.ifBlank { "মেসেজ" }}-এর জন্য SMS কম্পোজার খুলে দিয়েছি।",
            replyEn = "Opening SMS composer${if (displayName.isNotBlank()) " for $displayName" else ""}."
        )
    }

    private fun findContactPhone(nameQuery: String): Pair<String, String>? {
        val target = nameQuery.lowercase().trim()
        if (target.isEmpty()) return null
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$target%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: nameQuery
                    val num = cursor.getString(1) ?: return null
                    name to num
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun openCamera(): ActionExecutionResult {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(
            intent = intent,
            category = "CAMERA",
            summary = "Opened Camera",
            replyBn = "ক্যামেরা খুলে দিয়েছি।",
            replyEn = "Opening camera."
        )
    }

    fun openCalendarEvent(title: String): ActionExecutionResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Sara Event" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(
            intent = intent,
            category = "CALENDAR",
            summary = "Calendar event: $title",
            replyBn = "ক্যালেন্ডারে ইভেন্ট যোগ করার স্ক্রিন খুলে দিয়েছি।",
            replyEn = "Opening calendar to create your event."
        )
    }

    fun webSearch(query: String): ActionExecutionResult {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(
            intent = intent,
            category = "WEB_SEARCH",
            summary = "Search: $query",
            replyBn = "\"$query\" সম্পর্কে গুগলে সার্চ করছি।",
            replyEn = "Searching Google for \"$query\"."
        )
    }

    fun navigateToPlace(destination: String): ActionExecutionResult {
        val encoded = URLEncoder.encode(destination, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(
            intent = intent,
            category = "MAPS",
            summary = "Navigate: $destination",
            replyBn = "\"$destination\"-এর ম্যাপ ও দিকনির্দেশনা খুলছি।",
            replyEn = "Opening Maps for \"$destination\"."
        )
    }

    fun getInstalledLauncherApps(): List<InstalledAppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolveInfos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim() ?: pkg
                InstalledAppInfo(label = label, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launchAppByName(spokenApp: String): ActionExecutionResult {
        val normalized = normalizeAppAlias(spokenApp)
        val apps = getInstalledLauncherApps()
        val match = apps.firstOrNull { it.label.equals(normalized, ignoreCase = true) }
            ?: apps.firstOrNull {
                it.label.contains(normalized, ignoreCase = true) ||
                    it.packageName.contains(normalized, ignoreCase = true)
            }

        if (match == null) {
            return ActionExecutionResult(
                success = false,
                category = "OPEN_APP",
                actionSummary = "App \"$spokenApp\" not found",
                saraReplyBn = "ফোনে \"$spokenApp\" অ্যাপটি খুঁজে পাওয়া যায়নি। Phone Hub থেকে ইনস্টল করা অ্যাপগুলো দেখতে পারেন।",
                saraReplyEn = "Couldn't find \"$spokenApp\" installed on this device."
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ActionExecutionResult(
                false, "OPEN_APP", "Cannot launch ${match.label}",
                "${match.label} চালু করা যাচ্ছে না।", "Unable to launch ${match.label}."
            )

        return launchIntent(
            intent = launchIntent,
            category = "OPEN_APP",
            summary = "Launched ${match.label}",
            replyBn = "${match.label} খুলে দিয়েছি।",
            replyEn = "Opening ${match.label}."
        )
    }

    fun launchPackage(app: InstalledAppInfo): ActionExecutionResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ActionExecutionResult(
                false, "OPEN_APP", "Cannot launch ${app.label}",
                "${app.label} চালু করা যাচ্ছে না।", "Unable to launch ${app.label}."
            )
        return launchIntent(
            intent = launchIntent,
            category = "OPEN_APP",
            summary = "Launched ${app.label}",
            replyBn = "${app.label} খুলে দিয়েছি।",
            replyEn = "Opening ${app.label}."
        )
    }

    private fun normalizeAppAlias(raw: String): String {
        val lower = raw.lowercase().trim()
        return when {
            lower.contains("ইউটিউব") || lower.contains("youtube") -> "youtube"
            lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("whatsapp") -> "whatsapp"
            lower.contains("ক্রোম") || lower.contains("chrome") -> "chrome"
            lower.contains("ফেসবুক") || lower.contains("facebook") -> "facebook"
            lower.contains("ম্যাপ") || lower.contains("maps") -> "maps"
            lower.contains("ক্যালকুলেটর") || lower.contains("calculator") -> "calculator"
            lower.contains("গ্যালারি") || lower.contains("ফটো") || lower.contains("photos") -> "photos"
            lower.contains("জিমেইল") || lower.contains("gmail") -> "gmail"
            lower.contains("ক্যামেরা") || lower.contains("camera") -> "camera"
            lower.contains("সেটিংস") || lower.contains("settings") -> "settings"
            lower.contains("ক্যালেন্ডার") || lower.contains("calendar") -> "calendar"
            lower.contains("ঘড়ি") || lower.contains("clock") -> "clock"
            else -> lower
        }
    }

    fun readTelemetry(): DeviceTelemetry {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 85
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val netLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online"
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeBytes = stat.availableBytes
        val freeGb = "%.1f GB".format(freeBytes / (1024.0 * 1024.0 * 1024.0))

        val ringerMode = when (audioManager?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Ring"
        }

        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

        return DeviceTelemetry(
            batteryPercent = pct,
            isCharging = isCharging,
            networkLabel = netLabel,
            freeStorageGb = freeGb,
            mediaVolumePercent = getMediaVolumePercent(),
            ringModeLabel = ringerMode,
            flashlightOn = _flashlightOn.value,
            currentTimeLabel = timeFmt.format(Date())
        )
    }

    private fun launchIntent(
        intent: Intent,
        category: String,
        summary: String,
        replyBn: String,
        replyEn: String
    ): ActionExecutionResult {
        return try {
            context.startActivity(intent)
            vibrateConfirm()
            ActionExecutionResult(true, category, summary, replyBn, replyEn)
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                category = category,
                actionSummary = "Failed: $summary",
                saraReplyBn = "এই কাজটি করার জন্য উপযুক্ত অ্যাপ ফোনে পাওয়া যায়নি।",
                saraReplyEn = "No compatible app found on device for: $summary"
            )
        }
    }
}
