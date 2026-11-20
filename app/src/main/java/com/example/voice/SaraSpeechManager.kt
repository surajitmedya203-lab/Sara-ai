package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SpeechUiState {
    data object Idle : SpeechUiState
    data class Listening(val partialText: String = "") : SpeechUiState
    data class Error(val message: String) : SpeechUiState
}

class SaraSpeechManager(
    private val context: Context,
    private val onStateUpdate: ((SpeechUiState) -> Unit)? = null,
    private val onRmsUpdate: ((Float) -> Unit)? = null
) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<SpeechUiState>(SpeechUiState.Idle)
    val state: StateFlow<SpeechUiState> = _state.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun publishState(newState: SpeechUiState) {
        _state.value = newState
        onStateUpdate?.invoke(newState)
    }

    private fun publishRms(level: Float) {
        _rmsLevel.value = level
        onRmsUpdate?.invoke(level)
    }

    fun startListening(
        languageCode: String,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isAvailable) {
            val msg = "Speech recognition service is unavailable on this device. You can type your command below."
            publishState(SpeechUiState.Error(msg))
            onError(msg)
            return
        }

        stopListening()

        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                speechRecognizer = it
            }
        } catch (_: Exception) {
            val msg = "Could not initialize speech recognizer on this device."
            publishState(SpeechUiState.Error(msg))
            onError(msg)
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                publishState(SpeechUiState.Listening(""))
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB typically ranges from -2 to 10+ dB
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                publishRms(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                publishRms(0f)
            }

            override fun onError(error: Int) {
                publishRms(0f)
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Network needed for speech recognition, or type your command."
                    SpeechRecognizer.ERROR_NO_MATCH -> "ভয়েস বুঝতে পারিনি — আবার বলুন বা টাইপ করুন।"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কোনো কথা শোনা যায়নি।"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
                    else -> "Speech recognition unavailable ($error)."
                }
                publishState(SpeechUiState.Error(message))
                onError(message)
            }

            override fun onResults(results: Bundle?) {
                publishRms(0f)
                publishState(SpeechUiState.Idle)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                if (best.isNotEmpty()) {
                    onFinalResult(best)
                } else {
                    onError("ভয়েস বুঝতে পারিনি, আবার চেষ্টা করুন।")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    publishState(SpeechUiState.Listening(partial))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        publishState(SpeechUiState.Listening(""))
        try {
            recognizer.startListening(intent)
        } catch (_: SecurityException) {
            val msg = "Microphone permission needed."
            publishState(SpeechUiState.Error(msg))
            onError(msg)
        } catch (_: Exception) {
            val msg = "Could not start speech recognition."
            publishState(SpeechUiState.Error(msg))
            onError(msg)
        }
    }

    fun stopListening() {
        publishRms(0f)
        publishState(SpeechUiState.Idle)
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    fun clearError() {
        if (_state.value is SpeechUiState.Error) {
            publishState(SpeechUiState.Idle)
        }
    }
}
