package com.example.actions

import android.media.AudioManager
import android.provider.Settings
import com.example.data.local.MemoryNoteEntity
import com.example.data.local.PersonalTaskEntity
import com.example.data.local.RoutineEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ParsedSaraIntent {
    data class ToggleFlashlight(val enable: Boolean) : ParsedSaraIntent
    data class SetVolumePercent(val percent: Int) : ParsedSaraIntent
    data class StepVolume(val increase: Boolean) : ParsedSaraIntent
    data class SetSoundMode(val ringerMode: Int) : ParsedSaraIntent
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : ParsedSaraIntent
    data class SetTimer(val seconds: Int) : ParsedSaraIntent
    data class CallTarget(val target: String) : ParsedSaraIntent
    data class SendSms(val target: String, val body: String) : ParsedSaraIntent
    data class OpenSettings(val action: String, val label: String) : ParsedSaraIntent
    data object OpenCamera : ParsedSaraIntent
    data class OpenCalendar(val title: String) : ParsedSaraIntent
    data class OpenApp(val appName: String) : ParsedSaraIntent
    data class WebSearch(val query: String) : ParsedSaraIntent
    data class MapsNavigate(val place: String) : ParsedSaraIntent
    data class AddTask(val title: String) : ParsedSaraIntent
    data object ListTasks : ParsedSaraIntent
    data class SaveMemory(val topic: String, val content: String) : ParsedSaraIntent
    data class RecallMemory(val query: String) : ParsedSaraIntent
    data class RunRoutine(val query: String) : ParsedSaraIntent
    data object ReadBattery : ParsedSaraIntent
    data object ReadTimeDate : ParsedSaraIntent
    data object ReadStorageNetwork : ParsedSaraIntent
    data class CalculateMath(val expression: String, val answer: Double) : ParsedSaraIntent
    data class ExpressEmotion(val emotion: String) : ParsedSaraIntent
    data object InspectScreen : ParsedSaraIntent
    data class TypeDictatedText(val textToType: String) : ParsedSaraIntent
    data class ToggleScreenShare(val enable: Boolean) : ParsedSaraIntent
    data class GenerativeAiQuery(val prompt: String) : ParsedSaraIntent
}

object SaraCommandParser {

    fun parse(rawInput: String): ParsedSaraIntent {
        val text = normalizeBengaliDigits(rawInput.trim())
        val lower = text.lowercase()

        // 1. Flashlight
        if (lower.containsAny("ফ্ল্যাশ", "টর্চ", "flashlight", "torch", "light")) {
            val turnOff = lower.containsAny("বন্ধ", "অফ", "নিভাও", "off", "stop", "disable")
            val turnOn = lower.containsAny("অন", "জ্বালাও", "চালু", "খোলো", "on", "start", "enable")
            if (turnOff) return ParsedSaraIntent.ToggleFlashlight(false)
            if (turnOn) return ParsedSaraIntent.ToggleFlashlight(true)
        }

        // 2. Sound Mode (Silent / Vibrate / Normal)
        if (lower.containsAny("সাইলেন্ট", "নিঃশব্দ", "silent", "mute")) {
            return ParsedSaraIntent.SetSoundMode(AudioManager.RINGER_MODE_SILENT)
        }
        if (lower.containsAny("ভাইব্রেট", "vibrate")) {
            return ParsedSaraIntent.SetSoundMode(AudioManager.RINGER_MODE_VIBRATE)
        }
        if (lower.containsAny("নরমাল মোড", "রিং মোড", "unmute", "normal mode", "ring mode")) {
            return ParsedSaraIntent.SetSoundMode(AudioManager.RINGER_MODE_NORMAL)
        }

        // 3. Volume
        if (lower.containsAny("ভলিউম", "আওয়াজ", "সাউন্ড", "volume", "sound")) {
            val num = Regex("\\b(\\d{1,3})\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num in 0..100) {
                return ParsedSaraIntent.SetVolumePercent(num)
            }
            if (lower.containsAny("বাড়া", "বেশি", "up", "increase", "raise", "ফুল", "full", "max")) {
                return if (lower.containsAny("ফুল", "full", "max")) {
                    ParsedSaraIntent.SetVolumePercent(100)
                } else {
                    ParsedSaraIntent.StepVolume(true)
                }
            }
            if (lower.containsAny("কমা", "কম", "down", "decrease", "lower")) {
                return ParsedSaraIntent.StepVolume(false)
            }
        }

        // 4. Timer
        if (lower.containsAny("টাইমার", "timer")) {
            val mins = Regex("(\\d+)\\s*(মিনিট|min|minute|minutes)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val secs = Regex("(\\d+)\\s*(সেকেন্ড|sec|second|seconds)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val totalSeconds = (mins ?: 0) * 60 + (secs ?: 0)
            return ParsedSaraIntent.SetTimer(if (totalSeconds > 0) totalSeconds else 60)
        }

        // 5. Alarm
        if (lower.containsAny("অ্যালার্ম", "এলার্ম", "alarm")) {
            val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|সকাল|বিকাল|সন্ধ্যা|রাত)?").find(lower)
            var hour = timeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 7
            val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val period = timeMatch?.groupValues?.get(3).orEmpty()
            if (period.containsAny("pm", "বিকাল", "সন্ধ্যা", "রাত") && hour < 12) {
                hour += 12
            } else if (lower.containsAny("রাত", "বিকাল", "সন্ধ্যা", "pm") && hour < 12) {
                hour += 12
            }
            return ParsedSaraIntent.SetAlarm(hour.coerceIn(0, 23), minute.coerceIn(0, 59), "Sara Alarm")
        }

        // 6. Call
        if (lower.containsAny("কল করো", "কল দাও", "ফোন করো", "ডায়াল", "ডায়াল", "call ", "dial ")) {
            val target = text
                .replace(Regex("(?i)(সারা|sara|দয়া করে|please|কে|call|dial|কল করো|কল দাও|ফোন করো|ডায়াল করো|ডায়াল করো|করো|দাও)"), "")
                .trim()
                .removeSuffix("কে")
                .trim()
            return ParsedSaraIntent.CallTarget(target.ifBlank { text })
        }

        // 7. SMS / Message
        if (lower.containsAny("মেসেজ", "এসএমএস", "sms", "text ", "message ")) {
            val cleaned = text
                .replace(Regex("(?i)(সারা|sara|send|sms|message|text|মেসেজ দাও|মেসেজ পাঠাও|এসএমএস করো|পাঠাও|দাও)"), "")
                .trim()
            return ParsedSaraIntent.SendSms(target = cleaned.take(30), body = cleaned)
        }

        // 8. System Settings Panels
        if (lower.containsAny("ওয়াইফাই", "ওয়াইফাই", "wifi", "wi-fi")) {
            return ParsedSaraIntent.OpenSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings")
        }
        if (lower.containsAny("ব্লুটুথ", "bluetooth")) {
            return ParsedSaraIntent.OpenSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")
        }
        if (lower.containsAny("ব্রাইটনেস", "ডিসপ্লে", "brightness", "display")) {
            return ParsedSaraIntent.OpenSettings(Settings.ACTION_DISPLAY_SETTINGS, "Display & Brightness")
        }
        if (lower.containsAny("লোকেশন", "জিপিএস", "location", "gps")) {
            return ParsedSaraIntent.OpenSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location Settings")
        }

        // 9. Camera & Calendar
        if (lower.containsAny("ছবি তোলো", "সেলফি", "ক্যামেরা খোলো", "ক্যামেরা চালু", "take photo", "open camera", "selfie")) {
            return ParsedSaraIntent.OpenCamera
        }
        if (lower.containsAny("ক্যালেন্ডার ইভেন্ট", "schedule event", "add event", "meeting")) {
            val title = text.replace(Regex("(?i)(ক্যালেন্ডার ইভেন্ট|schedule event|add event|meeting)"), "").trim()
            return ParsedSaraIntent.OpenCalendar(title)
        }

        // 10. Personal Memory Save & Recall
        if (lower.containsAny("মনে রাখো", "মনে রেখো", "সেভ করো", "remember that", "remember my", "save note")) {
            val content = text
                .replace(Regex("(?i)(সারা|sara|মনে রাখো যে|মনে রাখো|মনে রেখো|সেভ করো|remember that|remember|save note)"), "")
                .trim()
            val topic = content.split(" ").take(4).joinToString(" ").ifBlank { "Personal Note" }
            return ParsedSaraIntent.SaveMemory(topic, content.ifBlank { text })
        }
        if (lower.containsAny("কোথায়", "কোথায়", "কী মনে রেখেছ", "কি মনে রেখেছ", "মেমোরি দেখাও", "what did i ask", "recall", "my notes")) {
            return ParsedSaraIntent.RecallMemory(text)
        }

        // 11. Personal Tasks
        if (lower.containsAny("কাজে যোগ", "টাস্ক যোগ", "রিমাইন্ডার", "add task", "todo", "to-do", "remind me")) {
            val title = text
                .replace(Regex("(?i)(সারা|sara|আমার কাজে যোগ করো|কাজে যোগ করো|টাস্ক যোগ করো|রিমাইন্ডার দাও|add task|add to-do|remind me to|remind me)"), "")
                .trim()
            return ParsedSaraIntent.AddTask(title.ifBlank { text })
        }
        if (lower.containsAny("টাস্ক দেখাও", "আমার কাজ", "কাজের তালিকা", "show tasks", "my tasks", "pending tasks")) {
            return ParsedSaraIntent.ListTasks
        }

        // 12. Routines
        if (lower.containsAny("রুটিন", "routine", "সকালের মোড", "রাতের মোড", "focus mode", "morning brief", "sleep mode")) {
            return ParsedSaraIntent.RunRoutine(text)
        }

        // 13. Maps / Navigation
        if (lower.containsAny("রাস্তা দেখাও", "ডিরেকশন", "ম্যাপে দেখাও", "navigate to", "directions to", "route to")) {
            val place = text
                .replace(Regex("(?i)(যাওয়ার রাস্তা দেখাও|যাওয়ার রাস্তা দেখাও|রাস্তা দেখাও|ডিরেকশন|ম্যাপে দেখাও|navigate to|directions to|route to)"), "")
                .trim()
            return ParsedSaraIntent.MapsNavigate(place.ifBlank { "Dhaka" })
        }

        // 14. Web Search
        if (lower.containsAny("সার্চ করো", "গুগল করো", "খুঁজে দাও", "search for", "google ", "search ")) {
            val query = text
                .replace(Regex("(?i)(সারা|sara|গুগলে সার্চ করো|সার্চ করো|গুগল করো|খুঁজে দাও|search for|google|search)"), "")
                .trim()
            return ParsedSaraIntent.WebSearch(query.ifBlank { text })
        }

        // 14b. Live Screen Share, Screen Inspect & Cross-App Dictation (checked before OpenApp)
        if (lower.containsAny("স্ক্রিন শেয়ার", "স্ক্রিন শেয়ার", "screen share", "share screen", "share my screen")) {
            val stop = lower.containsAny("বন্ধ", "অফ", "থামা", "stop", "off", "disable", "end")
            return ParsedSaraIntent.ToggleScreenShare(!stop)
        }
        if (lower.containsAny("স্ক্রিন দেখো", "স্ক্রিনে কী", "স্ক্রিনে কি", "পর্দায় কী", "পর্দায় কী", "look at screen", "what's on my screen", "read screen", "see my screen")) {
            return ParsedSaraIntent.InspectScreen
        }
        if (lower.startsWith("টাইপ করো") || lower.startsWith("লিখে দাও") || lower.startsWith("লেখো ") ||
            lower.startsWith("type ") || lower.startsWith("dictate ")
        ) {
            val contentToType = text
                .replace(Regex("(?i)^(টাইপ করো|লিখে দাও|লেখো|type|dictate)\\s*"), "")
                .trim()
            if (contentToType.isNotBlank()) {
                return ParsedSaraIntent.TypeDictatedText(contentToType)
            }
        }

        // 15. Open App
        if (lower.containsAny("খোলো", "চালু করো", "ওপেন করো", "open ", "launch ", "start ")) {
            val appName = text
                .replace(Regex("(?i)(সারা|sara|অ্যাপটি|অ্যাপ|খোলো|চালু করো|ওপেন করো|open|launch|start)"), "")
                .trim()
            if (appName.isNotBlank()) {
                return ParsedSaraIntent.OpenApp(appName)
            }
        }

        // 16. Device Status (Battery / Time / Date / Storage)
        if (lower.containsAny("ব্যাটারি", "চার্জ", "battery", "charge")) {
            return ParsedSaraIntent.ReadBattery
        }
        if (lower.containsAny("কয়টা বাজে", "কয়টা বাজে", "সময় কত", "সময় কত", "তারিখ", "what time", "today's date", "current time")) {
            return ParsedSaraIntent.ReadTimeDate
        }
        if (lower.containsAny("স্টোরেজ", "নেটওয়ার্ক", "নেটওয়ার্ক", "storage", "device status", "phone status")) {
            return ParsedSaraIntent.ReadStorageNetwork
        }

        // 17. Expressive 3D Character Emotions & Gestures (Hi, Love, Angry, Happy/Dance)
        if (lower.containsAny("ভালোবাসি", "ভালবাসি", "ভালোবাসা", "লাভ ইউ", "love you", "i love", "kiss", "তুমি খুব সুন্দর", "কিউট")) {
            return ParsedSaraIntent.ExpressEmotion("LOVE")
        }
        if (lower.containsAny("রাগ করো", "রাগ দেখাও", "রাগ করে", "রাগ", "পঁচা", "পচা", "angry", "be angry", "get mad")) {
            return ParsedSaraIntent.ExpressEmotion("ANGRY")
        }
        if (lower.containsAny("হাই", "হ্যালো", "হেলো", "হাত নাড়", "হাত নাড়", "wave", "say hi", "hello", "hey sara") || lower == "hi") {
            return ParsedSaraIntent.ExpressEmotion("WAVE_HI")
        }
        if (lower.containsAny("নাচো", "ড্যান্স", "খুশি হও", "dance", "celebrate", "happy")) {
            return ParsedSaraIntent.ExpressEmotion("HAPPY")
        }

        // 18. Simple Math
        tryEvaluateMath(text)?.let { (expr, result) ->
            return ParsedSaraIntent.CalculateMath(expr, result)
        }

        // 19. Fallback -> AiRepository
        return ParsedSaraIntent.GenerativeAiQuery(text)
    }

    fun formatDateTimeReply(isBengali: Boolean): String {
        val now = Date()
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(now)
        return if (isBengali) {
            "এখন সময় $timeStr, এবং আজ $dateStr।"
        } else {
            "It is currently $timeStr on $dateStr."
        }
    }

    fun formatTasksSummary(tasks: List<PersonalTaskEntity>, isBengali: Boolean): String {
        if (tasks.isEmpty()) {
            return if (isBengali) "আপনার কোনো পেন্ডিং কাজ নেই, সব কাজ সম্পন্ন!" else "You have no pending personal tasks."
        }
        val listText = tasks.take(5).mapIndexed { i, t -> "${i + 1}. ${t.title}" }.joinToString("\n")
        return if (isBengali) {
            "আপনার ${tasks.size}টি পেন্ডিং কাজ আছে:\n$listText"
        } else {
            "You have ${tasks.size} pending tasks:\n$listText"
        }
    }

    fun formatMemoryRecall(query: String, memories: List<MemoryNoteEntity>, isBengali: Boolean): String {
        if (memories.isEmpty()) {
            return if (isBengali) {
                "আমি এখনো কোনো ব্যক্তিগত তথ্য সেভ করিনি। বলুন 'মনে রাখো...' তাহলে মনে রাখবো।"
            } else {
                "No personal memories saved yet. Say 'Remember that...' to store something."
            }
        }
        val keywords = query.lowercase().split(" ").filter { it.length >= 3 }
        val matched = memories.filter { mem ->
            val hay = "${mem.keyTopic} ${mem.content}".lowercase()
            keywords.any { hay.contains(it) }
        }
        val targetList = matched.ifEmpty { memories.take(3) }
        val formatted = targetList.joinToString("\n") { "• ${it.content}" }
        return if (isBengali) {
            "আমার মনে আছে:\n$formatted"
        } else {
            "Here is what I remember:\n$formatted"
        }
    }

    fun matchRoutine(query: String, routines: List<RoutineEntity>): RoutineEntity? {
        val lower = query.lowercase()
        return routines.firstOrNull { r ->
            lower.contains(r.name.lowercase()) ||
                r.triggerPhrase.split(",").any { phrase ->
                    val p = phrase.trim().lowercase()
                    p.isNotEmpty() && lower.contains(p)
                }
        } ?: routines.firstOrNull()
    }

    private fun normalizeBengaliDigits(input: String): String {
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val idx = bnDigits.indexOf(ch)
            if (idx >= 0) sb.append(('0'.code + idx).toChar()) else sb.append(ch)
        }
        return sb.toString()
    }

    private fun tryEvaluateMath(text: String): Pair<String, Double>? {
        val normalized = text
            .replace("যোগ", "+")
            .replace("প্লাস", "+")
            .replace("বিয়োগ", "-")
            .replace("বিয়োগ", "-")
            .replace("মাইনাস", "-")
            .replace("গুণ", "*")
            .replace("ইনটু", "*")
            .replace("x", "*", ignoreCase = true)
            .replace("ভাগ", "/")

        val match = Regex("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)").find(normalized)
            ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDoubleOrNull() ?: return null
        val ans = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0.0) a / b else return null
            else -> return null
        }
        return "${match.groupValues[1]} $op ${match.groupValues[3]}" to ans
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { this.contains(it) }
    }
}
