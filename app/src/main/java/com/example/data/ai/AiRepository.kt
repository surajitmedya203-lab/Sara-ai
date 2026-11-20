package com.example.data.ai

data class ChatTurn(
    val isUser: Boolean,
    val text: String
)

data class AiReplyResult(
    val replyText: String,
    val isSampleMode: Boolean
)

/**
 * Abstraction for open-ended generative AI replies.
 * When Firebase AI Logic is enabled, this interface is backed by Firebase.ai.
 */
interface AiRepository {
    suspend fun generateAssistantReply(
        prompt: String,
        userName: String,
        languageCode: String,
        history: List<ChatTurn>
    ): AiReplyResult
}

/**
 * Local sample-mode implementation used when Firebase setup was declined.
 * Clearly labels generative AI responses as sample mode without calling any external REST API.
 */
class PlaceholderAiRepository : AiRepository {
    override suspend fun generateAssistantReply(
        prompt: String,
        userName: String,
        languageCode: String,
        history: List<ChatTurn>
    ): AiReplyResult {
        val isBengali = languageCode.startsWith("bn") || prompt.any { it in '\u0980'..'\u09FF' }
        val lower = prompt.lowercase()

        val sampleBody = when {
            lower.contains("কেমন আছ") || lower.contains("how are you") -> {
                if (isBengali) {
                    "আমি দারুণ আছি, $userName! আপনার ফোনের সমস্ত কমান্ড—কল, অ্যালার্ম, ফ্ল্যাশলাইট, অ্যাপ খোলা, টাস্ক ও মেমোরি সামলাতে আমি সম্পূর্ণ প্রস্তুত।"
                } else {
                    "I am doing great, $userName! All on-device phone controls, tasks, memories, and voice verification tools are ready for your command."
                }
            }
            lower.contains("তুমি কে") || lower.contains("তোমার নাম") || lower.contains("who are you") || lower.contains("your name") -> {
                if (isBengali) {
                    "আমি সারা (Sara), আপনার ব্যক্তিগত থ্রিডি ভয়েস অ্যাসিস্ট্যান্ট। আমি আপনার ভয়েস চিনে রাখতে পারি এবং আপনার ফোনের জরুরি কাজগুলো করে দিতে পারি।"
                } else {
                    "I am Sara, your personal 3D voice assistant. I can learn your voiceprint, manage your personal tasks and memories, and execute Android phone actions."
                }
            }
            lower.contains("কি কি করতে পারো") || lower.contains("কী কী করতে পারো") || lower.contains("what can you do") -> {
                if (isBengali) {
                    "আমি আপনার হয়ে কল ডায়াল, মেসেজ তৈরি, অ্যালার্ম ও টাইমার সেট, ফ্ল্যাশলাইট অন/অফ, ভলিউম কন্ট্রোল, অ্যাপ ওপেন, ওয়েব ও ম্যাপ সার্চ, এবং আপনার ব্যক্তিগত কাজ ও মেমোরি সংরক্ষণ করতে পারি।"
                } else {
                    "I can dial contacts, draft messages, set alarms and timers, toggle your flashlight, adjust volume, launch apps, search maps or web, and manage your personal tasks, memories, and routines."
                }
            }
            else -> {
                if (isBengali) {
                    "[স্যাম্পল AI মোড] \"$prompt\" বিষয়ে বিস্তারিত উত্তর দেওয়ার জন্য Firebase AI Logic (Gemini) সংযোগ প্রয়োজন। তবে আপনার ফোনের যেকোনো কমান্ড (যেমন: 'ফ্ল্যাশলাইট অন করো', 'সকাল ৭টায় অ্যালার্ম দাও', 'মনে রাখো...', 'YouTube খোলো') আমি সরাসরি এখনই করে দিতে পারি!"
                } else {
                    "[Sample AI Mode] Open-ended generative answers for \"$prompt\" require Firebase AI Logic (Gemini). Meanwhile, all direct phone commands (e.g., 'Turn on flashlight', 'Set alarm for 7 AM', 'Remember that...', 'Open YouTube') execute live on your device right now."
                }
            }
        }

        return AiReplyResult(
            replyText = sampleBody,
            isSampleMode = true
        )
    }
}
