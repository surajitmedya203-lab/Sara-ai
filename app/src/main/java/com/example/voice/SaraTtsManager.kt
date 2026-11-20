package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

class SaraTtsManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lipSyncJob: Job? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _mouthAmplitude = MutableStateFlow(0f)
    val mouthAmplitude: StateFlow<Float> = _mouthAmplitude.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                tts?.setPitch(1.08f)
                tts?.setSpeechRate(1.0f)
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

    fun speak(text: String, preferredLanguageCode: String) {
        if (!isReady || text.isBlank()) return
        val hasBengali = text.any { it in '\u0980'..'\u09FF' }
        val targetLocale = when {
            hasBengali -> Locale("bn", "BD")
            preferredLanguageCode.startsWith("bn") -> Locale("bn", "IN")
            else -> Locale.US
        }

        val langStatus = tts?.setLanguage(targetLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (hasBengali) {
                // Fallback to Indian Bengali or English if bn-BD voice pack is absent
                val bnIn = tts?.setLanguage(Locale("bn", "IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (bnIn == TextToSpeech.LANG_MISSING_DATA || bnIn == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
            } else {
                tts?.setLanguage(Locale.US)
            }
        }

        val cleanText = text.replace("[স্যাম্পল AI মোড]", "")
            .replace("[Sample AI Mode]", "")
            .trim()
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
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
