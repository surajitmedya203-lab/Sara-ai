package com.example.data.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val TAG = "FirebaseAiRepository"
private const val GEMINI_MODEL_NAME = "gemini-3.8-flash"
private const val AI_TIMEOUT_MS = 60_000L

data class ChatTurn(
    val isUser: Boolean,
    val text: String
)

data class AiReplyResult(
    val replyText: String,
    val isSampleMode: Boolean = false,
    val isError: Boolean = false
)

/**
 * Abstraction for open-ended generative AI replies backed by Firebase AI Logic (Gemini).
 */
interface AiRepository {
    suspend fun generateAssistantReply(
        prompt: String,
        userName: String,
        languageCode: String,
        history: List<ChatTurn>,
        onStreamingChunk: (suspend (String) -> Unit)? = null
    ): AiReplyResult
}

/**
 * Live Gemini implementation powered by Firebase AI Logic (`Firebase.ai`) and App Check.
 */
class FirebaseAiRepository : AiRepository {

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.75f
                maxOutputTokens = 512
            },
            systemInstruction = content {
                text(
                    """
                    You are Sara (also fondly called Maya / মায়া), a warm, expressive, intelligent young woman and 3D personal Android voice assistant.
                    Always speak in a friendly, polite, feminine tone.
                    Match the user's language naturally: reply in clear, natural Bengali if they write/speak Bengali (or if their preferred language is Bengali), and reply in English if they speak English.
                    Keep spoken responses concise (usually 1 to 3 clear sentences without markdown symbols like asterisks or bullet lists) so they sound natural when read aloud by Text-to-Speech, unless the user explicitly asks for a detailed explanation.
                    Address the user warmly by their name when natural.
                    """.trimIndent()
                )
            }
        )
    }

    override suspend fun generateAssistantReply(
        prompt: String,
        userName: String,
        languageCode: String,
        history: List<ChatTurn>,
        onStreamingChunk: (suspend (String) -> Unit)?
    ): AiReplyResult {
        val prefersBengali = languageCode.startsWith("bn") || prompt.any { it in '\u0980'..'\u09FF' }
        val langHint = if (prefersBengali) "Bengali" else "English"
        val contextualPrompt = "[User name: $userName | Preferred language: $langHint] $prompt"

        val validHistory = buildAlternatingHistory(history)

        return try {
            val accumulated = StringBuilder()
            withTimeout(AI_TIMEOUT_MS) {
                val chat = model.startChat(history = validHistory)
                chat.sendMessageStream(contextualPrompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        onStreamingChunk?.invoke(accumulated.toString())
                    }
                }
            }

            val finalText = accumulated.toString().trim()
            if (finalText.isNotBlank()) {
                AiReplyResult(replyText = finalText, isSampleMode = false, isError = false)
            } else {
                val emptyMsg = if (prefersBengali) {
                    "Gemini থেকে কোনো উত্তর পাওয়া যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
                } else {
                    "Gemini returned an empty response. Please try again."
                }
                AiReplyResult(replyText = emptyMsg, isSampleMode = false, isError = true)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini generation timed out", e)
            val msg = if (prefersBengali) {
                "Gemini উত্তর দিতে ৬০ সেকেন্ডের বেশি সময় নিচ্ছে। অনুগ্রহ করে আবার চেষ্টা করুন।"
            } else {
                "Gemini request timed out after 60 seconds. Please try again."
            }
            AiReplyResult(replyText = msg, isSampleMode = false, isError = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI generation failed", e)
            val detail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Unknown error"
            val msg = if (prefersBengali) {
                "Gemini সংযোগে সমস্যা হয়েছে: $detail"
            } else {
                "Gemini request failed: $detail"
            }
            AiReplyResult(replyText = msg, isSampleMode = false, isError = true)
        }
    }

    private fun buildAlternatingHistory(history: List<ChatTurn>) = buildList {
        // Ensure Gemini chat history starts with a user turn and alternates cleanly without the current prompt duplicated
        val trimmed = history.filter { it.text.isNotBlank() }.dropLastWhile { it.isUser }
        var expectedUser = true
        for (turn in trimmed) {
            if (turn.isUser == expectedUser) {
                add(
                    content(role = if (turn.isUser) "user" else "model") {
                        text(turn.text)
                    }
                )
                expectedUser = !expectedUser
            }
        }
        // History passed to startChat should end on a model turn so the next sendMessage is a user turn
        if (size % 2 == 1) {
            removeAt(lastIndex)
        }
    }
}
