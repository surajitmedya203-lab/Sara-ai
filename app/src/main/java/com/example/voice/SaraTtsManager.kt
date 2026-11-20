package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.sin

enum class MayaVoiceTone(
    val id: String,
    val labelBn: String,
    val labelEn: String,
    val pitch: Float,
    val rate: Float
) {
    MAYA_SWEET("MAYA_SWEET", "মিষ্টি মায়া (Sweet Girl)", "Sweet Maya", 1.24f, 1.02f),
    MAYA_SOFT("MAYA_SOFT", "কোমল কণ্ঠ (Soft Girl)", "Soft & Warm", 1.16f, 0.95f),
    MAYA_BRIGHT("MAYA_BRIGHT", "উচ্ছ্বল কণ্ঠ (Bright Girl)", "Bright & Lively", 1.32f, 1.06f);

    companion object {
        fun fromId(id: String): MayaVoiceTone = entries.firstOrNull { it.id == id } ?: MAYA_SWEET
    }
}

data class FemaleVoiceOption(
    val voiceName: String,
    val displayLabel: String,
    val localeTag: String,
    val isNetwork: Boolean,
    val score: Int
)

object FemaleVoiceSelector {

    private val femaleTokens = listOf(
        "female", "woman", "girl", "feminine",
        "-sfg-", "-tpf-", "-tpc-", "-iob-", "-iog-",
        "-bnf-", "-bnd-", "-bne-", "-ban-",
        "-end-", "-ene-", "-enc-",
        "heera", "kalpana", "aditi", "raveena", "kajal", "priya", "neha",
        "maya", "sara", "samantha", "zira", "jenny", "aria", "ava"
    )

    private val maleTokens = listOf(
        "-bab-", "-bac-", "-bnm-", "-iol-", "-iom-", "-tpd-", "-sfm-",
        "_male", "-male", " male", "gender=male", "boy",
        "david", "mark", "ravi", "hemant", "prabhat", "pradeep", "madhur"
    )

    fun scoreVoice(
        voiceName: String,
        localeTag: String,
        features: Set<String>,
        isNetwork: Boolean,
        targetLocale: Locale
    ): Int {
        val lowerName = voiceName.lowercase(Locale.ROOT)
        val lowerFeatures = features.joinToString(" ").lowercase(Locale.ROOT)
        val combined = "$lowerName $lowerFeatures"

        var score = 0
        val voiceLang = localeTag.substringBefore("-").substringBefore("_").lowercase(Locale.ROOT)
        val targetLang = targetLocale.language.lowercase(Locale.ROOT)

        if (voiceLang == targetLang) {
            score += 60
            val targetCountry = targetLocale.country.lowercase(Locale.ROOT)
            if (targetCountry.isNotEmpty() && localeTag.lowercase(Locale.ROOT).contains(targetCountry)) {
                score += 25
            }
        } else {
            score -= 100
        }

        if (femaleTokens.any { combined.contains(it) }) {
            score += 90
        }
        if (maleTokens.any { combined.contains(it) }) {
            score -= 120
        }
        if (!isNetwork) {
            score += 12
        }
        return score
    }

    fun cleanSpeechText(raw: String): String {
        return raw
            .replace("[স্যাম্পল AI মোড]", "")
            .replace("[Sample AI Mode]", "")
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")
            .replace("#", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

class SaraTtsManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lipSyncJob: Job? = null

    private var currentTone: MayaVoiceTone = MayaVoiceTone.MAYA_SWEET
    private var preferredVoiceName: String = ""
    private var currentLanguageCode: String = "bn-BD"

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _mouthAmplitude = MutableStateFlow(0f)
    val mouthAmplitude: StateFlow<Float> = _mouthAmplitude.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<FemaleVoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<FemaleVoiceOption>> = _availableVoices.asStateFlow()

    private val _activeVoiceLabel = MutableStateFlow("Maya Female Voice (Auto)")
    val activeVoiceLabel: StateFlow<String> = _activeVoiceLabel.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                applyToneParameters()
                refreshVoiceCatalog(resolveLocale(currentLanguageCode))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        startLipSyncPulse()
                    }

                    override fun onDone(utteranceId: String?) {
                        stopLipSyncPulse()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        stopLipSyncPulse()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        stopLipSyncPulse()
                    }
                })
            }
        }
    }

    fun applyVoiceConfig(toneId: String, preferredVoice: String, languageCode: String) {
        currentTone = MayaVoiceTone.fromId(toneId)
        preferredVoiceName = preferredVoice
        currentLanguageCode = languageCode
        if (isReady) {
            applyToneParameters()
            refreshVoiceCatalog(resolveLocale(languageCode))
        }
    }

    private fun applyToneParameters() {
        tts?.setPitch(currentTone.pitch)
        tts?.setSpeechRate(currentTone.rate)
    }

    private fun resolveLocale(languageCode: String): Locale {
        return when {
            languageCode.equals("bn-IN", ignoreCase = true) -> Locale("bn", "IN")
            languageCode.startsWith("bn", ignoreCase = true) -> Locale("bn", "BD")
            languageCode.equals("en-IN", ignoreCase = true) -> Locale("en", "IN")
            else -> Locale.US
        }
    }

    private fun refreshVoiceCatalog(targetLocale: Locale): Voice? {
        val engineVoices = try {
            tts?.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (engineVoices.isEmpty()) return null

        val targetLang = targetLocale.language.lowercase(Locale.ROOT)
        val sameLangVoices = engineVoices.filter {
            it.locale?.language?.lowercase(Locale.ROOT) == targetLang
        }
        val candidates = sameLangVoices.ifEmpty { engineVoices }

        val ranked = candidates.map { voice ->
            val score = FemaleVoiceSelector.scoreVoice(
                voiceName = voice.name,
                localeTag = voice.locale?.toLanguageTag().orEmpty(),
                features = voice.features.orEmpty(),
                isNetwork = voice.isNetworkConnectionRequired,
                targetLocale = targetLocale
            )
            voice to score
        }.sortedByDescending { it.second }

        _availableVoices.value = ranked.take(6).mapIndexed { index, (voice, score) ->
            val tag = voice.locale?.toLanguageTag().orEmpty()
            val netLabel = if (voice.isNetworkConnectionRequired) "HD" else "Local"
            FemaleVoiceOption(
                voiceName = voice.name,
                displayLabel = "Voice ${index + 1} ($tag • $netLabel)",
                localeTag = tag,
                isNetwork = voice.isNetworkConnectionRequired,
                score = score
            )
        }

        val chosen = ranked.firstOrNull { it.first.name == preferredVoiceName }?.first
            ?: ranked.firstOrNull()?.first

        if (chosen != null) {
            try {
                tts?.voice = chosen
                val tag = chosen.locale?.toLanguageTag().orEmpty()
                _activeVoiceLabel.value = "${currentTone.labelEn} • $tag"
            } catch (_: Exception) {
            }
        }
        return chosen
    }

    fun speak(text: String, preferredLanguageCode: String) {
        if (!isReady || text.isBlank()) return
        currentLanguageCode = preferredLanguageCode

        val hasBengali = text.any { it in '\u0980'..'\u09FF' }
        val targetLocale = when {
            hasBengali && preferredLanguageCode.equals("bn-IN", ignoreCase = true) -> Locale("bn", "IN")
            hasBengali -> Locale("bn", "BD")
            preferredLanguageCode.startsWith("bn") -> Locale("bn", "IN")
            else -> Locale.US
        }

        val langStatus = tts?.setLanguage(targetLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        val effectiveLocale = if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (hasBengali) {
                val bnIn = tts?.setLanguage(Locale("bn", "IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (bnIn == TextToSpeech.LANG_MISSING_DATA || bnIn == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                    Locale.US
                } else {
                    Locale("bn", "IN")
                }
            } else {
                tts?.setLanguage(Locale.US)
                Locale.US
            }
        } else {
            targetLocale
        }

        applyToneParameters()
        refreshVoiceCatalog(effectiveLocale)

        val cleanText = FemaleVoiceSelector.cleanSpeechText(text)
        if (cleanText.isNotEmpty()) {
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    fun testMayaVoice(languageCode: String) {
        val sample = if (languageCode.startsWith("bn")) {
            "হাই! আমি মায়া, আপনার ব্যক্তিগত এআই অ্যাসিস্ট্যান্ট। এখন আমার মেয়ে কণ্ঠ কেমন লাগছে বলুন?"
        } else {
            "Hi there! I am Maya, your personal AI assistant. How do you like my female voice?"
        }
        speak(sample, languageCode)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        stopLipSyncPulse()
    }

    private fun startLipSyncPulse() {
        lipSyncJob?.cancel()
        lipSyncJob = scope.launch {
            var tick = 0f
            while (_isSpeaking.value) {
                tick += 0.35f
                val wave = ((sin(tick.toDouble()) * 0.45 + sin((tick * 2.3).toDouble()) * 0.35 + 0.5))
                    .toFloat()
                    .coerceIn(0.12f, 0.95f)
                _mouthAmplitude.value = wave
                delay(45L)
            }
        }
    }

    private fun stopLipSyncPulse() {
        _isSpeaking.value = false
        lipSyncJob?.cancel()
        _mouthAmplitude.value = 0f
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}
