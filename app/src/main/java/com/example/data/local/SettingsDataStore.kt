package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.TargetVideoGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reelforge_settings"
)

data class UserSettings(
    val geminiApiKey: String = "",
    val selectedGeminiModel: String = DEFAULT_GEMINI_MODEL,
    val targetGenerator: TargetVideoGenerator = TargetVideoGenerator.VEO_3,
    val preferHdDownload: Boolean = true,
    val customCobaltUrl: String = ""
) {
    val hasApiKey: Boolean
        get() = geminiApiKey.trim().isNotEmpty()

    val maskedApiKey: String
        get() {
            val trimmed = geminiApiKey.trim()
            if (trimmed.length <= 10) return if (trimmed.isEmpty()) "Not set" else "••••••"
            return "${trimmed.take(6)}••••${trimmed.takeLast(4)}"
        }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        val AVAILABLE_MODELS = listOf(
            "gemini-3.8-flash" to "Gemini 3.8 Flash (Best Speed & Video)",
            "gemini-3.6-flash" to "Gemini 3.6 Flash (High Precision)",
            "gemini-4-hydrogen" to "Gemini 4 Hydrogen (Frontier)",
            "gemini-4-argon" to "Gemini 4 Argon (Deep Reasoning)",
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite (Ultra Fast)",
            "gemini-2.5-flash" to "Gemini 2.5 Flash (Legacy Fallback)"
        )
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val SELECTED_MODEL = stringPreferencesKey("selected_gemini_model")
        val TARGET_GENERATOR = stringPreferencesKey("target_video_generator")
        val PREFER_HD = booleanPreferencesKey("prefer_hd_download")
        val CUSTOM_COBALT_URL = stringPreferencesKey("custom_cobalt_url")
    }

    val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val genName = prefs[Keys.TARGET_GENERATOR] ?: TargetVideoGenerator.VEO_3.name
        val generator = TargetVideoGenerator.entries.firstOrNull { it.name == genName }
            ?: TargetVideoGenerator.VEO_3

        UserSettings(
            geminiApiKey = prefs[Keys.GEMINI_API_KEY].orEmpty(),
            selectedGeminiModel = prefs[Keys.SELECTED_MODEL]
                ?.takeIf { it.isNotBlank() }
                ?: UserSettings.DEFAULT_GEMINI_MODEL,
            targetGenerator = generator,
            preferHdDownload = prefs[Keys.PREFER_HD] ?: true,
            customCobaltUrl = prefs[Keys.CUSTOM_COBALT_URL].orEmpty()
        )
    }

    suspend fun saveGeminiApiKey(apiKey: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GEMINI_API_KEY] = apiKey.trim()
        }
    }

    suspend fun saveGeminiModel(modelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SELECTED_MODEL] = modelId.trim().ifBlank { UserSettings.DEFAULT_GEMINI_MODEL }
        }
    }

    suspend fun saveTargetGenerator(generator: TargetVideoGenerator) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TARGET_GENERATOR] = generator.name
        }
    }

    suspend fun savePreferHdDownload(preferHd: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PREFER_HD] = preferHd
        }
    }

    suspend fun saveCustomCobaltUrl(url: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_COBALT_URL] = url.trim().trimEnd('/')
        }
    }
}
