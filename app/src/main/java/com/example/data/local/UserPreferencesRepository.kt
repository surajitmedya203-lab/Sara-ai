package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.saraDataStore: DataStore<Preferences> by preferencesDataStore(name = "sara_prefs")

data class SaraPreferences(
    val userName: String = "বস",
    val languageCode: String = "bn-BD", // "bn-BD", "bn-IN", "en-US"
    val voiceTonePreset: String = "MAYA_SWEET", // "MAYA_SWEET", "MAYA_SOFT", "MAYA_BRIGHT"
    val preferredTtsVoiceName: String = "",
    val speakResponses: Boolean = true,
    val continuousListening: Boolean = false,
    val voiceLockEnabled: Boolean = false,
    val isVoiceEnrolled: Boolean = false,
    val voicePrintJson: String = "",
    val lastMatchScore: Int = 0,
    val shaderStyleId: String = "HOLOGRAM_CYAN",
    val darkTheme: Boolean = true,
    val seededDefaults: Boolean = false
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val USER_NAME = stringPreferencesKey("user_name")
        val LANGUAGE_CODE = stringPreferencesKey("language_code")
        val VOICE_TONE_PRESET = stringPreferencesKey("voice_tone_preset")
        val PREFERRED_TTS_VOICE_NAME = stringPreferencesKey("preferred_tts_voice_name")
        val SPEAK_RESPONSES = booleanPreferencesKey("speak_responses")
        val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val VOICE_LOCK_ENABLED = booleanPreferencesKey("voice_lock_enabled")
        val IS_VOICE_ENROLLED = booleanPreferencesKey("is_voice_enrolled")
        val VOICE_PRINT_JSON = stringPreferencesKey("voice_print_json")
        val LAST_MATCH_SCORE = intPreferencesKey("last_match_score")
        val SHADER_STYLE_ID = stringPreferencesKey("shader_style_id")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SEEDED_DEFAULTS = booleanPreferencesKey("seeded_defaults")
    }

    val preferences: Flow<SaraPreferences> = context.saraDataStore.data.map { prefs ->
        SaraPreferences(
            userName = prefs[Keys.USER_NAME] ?: "বস",
            languageCode = prefs[Keys.LANGUAGE_CODE] ?: "bn-BD",
            voiceTonePreset = prefs[Keys.VOICE_TONE_PRESET] ?: "MAYA_SWEET",
            preferredTtsVoiceName = prefs[Keys.PREFERRED_TTS_VOICE_NAME] ?: "",
            speakResponses = prefs[Keys.SPEAK_RESPONSES] ?: true,
            continuousListening = prefs[Keys.CONTINUOUS_LISTENING] ?: false,
            voiceLockEnabled = prefs[Keys.VOICE_LOCK_ENABLED] ?: false,
            isVoiceEnrolled = prefs[Keys.IS_VOICE_ENROLLED] ?: false,
            voicePrintJson = prefs[Keys.VOICE_PRINT_JSON] ?: "",
            lastMatchScore = prefs[Keys.LAST_MATCH_SCORE] ?: 0,
            shaderStyleId = prefs[Keys.SHADER_STYLE_ID] ?: "HOLOGRAM_CYAN",
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            seededDefaults = prefs[Keys.SEEDED_DEFAULTS] ?: false
        )
    }

    suspend fun setUserName(name: String) {
        context.saraDataStore.edit { it[Keys.USER_NAME] = name.ifBlank { "বস" } }
    }

    suspend fun setLanguageCode(code: String) {
        context.saraDataStore.edit { it[Keys.LANGUAGE_CODE] = code }
    }

    suspend fun setVoiceTonePreset(presetId: String) {
        context.saraDataStore.edit { it[Keys.VOICE_TONE_PRESET] = presetId }
    }

    suspend fun setPreferredTtsVoiceName(voiceName: String) {
        context.saraDataStore.edit { it[Keys.PREFERRED_TTS_VOICE_NAME] = voiceName }
    }

    suspend fun setSpeakResponses(enabled: Boolean) {
        context.saraDataStore.edit { it[Keys.SPEAK_RESPONSES] = enabled }
    }

    suspend fun setContinuousListening(enabled: Boolean) {
        context.saraDataStore.edit { it[Keys.CONTINUOUS_LISTENING] = enabled }
    }

    suspend fun setVoiceLockEnabled(enabled: Boolean) {
        context.saraDataStore.edit { it[Keys.VOICE_LOCK_ENABLED] = enabled }
    }

    suspend fun saveVoiceProfile(voicePrintJson: String) {
        context.saraDataStore.edit {
            it[Keys.VOICE_PRINT_JSON] = voicePrintJson
            it[Keys.IS_VOICE_ENROLLED] = true
            it[Keys.LAST_MATCH_SCORE] = 98
        }
    }

    suspend fun clearVoiceProfile() {
        context.saraDataStore.edit {
            it[Keys.VOICE_PRINT_JSON] = ""
            it[Keys.IS_VOICE_ENROLLED] = false
            it[Keys.VOICE_LOCK_ENABLED] = false
            it[Keys.LAST_MATCH_SCORE] = 0
        }
    }

    suspend fun updateLastMatchScore(score: Int) {
        context.saraDataStore.edit { it[Keys.LAST_MATCH_SCORE] = score }
    }

    suspend fun setShaderStyle(styleId: String) {
        context.saraDataStore.edit { it[Keys.SHADER_STYLE_ID] = styleId }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.saraDataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun markDefaultsSeeded() {
        context.saraDataStore.edit { it[Keys.SEEDED_DEFAULTS] = true }
    }
}
