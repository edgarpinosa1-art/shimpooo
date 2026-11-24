package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AiProvider
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.ThirdPartyModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reelforge_settings"
)

data class UserSettings(
    val activeProvider: AiProvider = AiProvider.GEMINI,
    val geminiApiKey: String = "",
    val selectedGeminiModel: String = DEFAULT_GEMINI_MODEL,
    val thirdPartyApiKey: String = "",
    val thirdPartyBaseUrl: String = "",
    val selectedSttModel: String = DEFAULT_STT_MODEL,
    val selectedChatModel: String = DEFAULT_CHAT_MODEL,
    val targetGenerator: TargetVideoGenerator = TargetVideoGenerator.VEO_3,
    val preferHdDownload: Boolean = true,
    val customCobaltUrl: String = ""
) {
    val activeApiKey: String
        get() = if (activeProvider == AiProvider.GEMINI) geminiApiKey.trim() else thirdPartyApiKey.trim()

    val hasApiKey: Boolean
        get() = activeApiKey.isNotEmpty()

    val effectiveThirdPartyBaseUrl: String
        get() = ThirdPartyModelCatalog.normalizeBaseUrl(activeProvider, thirdPartyBaseUrl)

    val activeDisplayModel: String
        get() = if (activeProvider == AiProvider.GEMINI) {
            selectedGeminiModel
        } else {
            "${selectedSttModel.substringAfterLast('/')} + ${selectedChatModel.substringAfterLast('/')}"
        }

    val maskedApiKey: String
        get() {
            val trimmed = activeApiKey
            if (trimmed.length <= 10) return if (trimmed.isEmpty()) "Not set" else "••••••"
            return "${trimmed.take(6)}••••${trimmed.takeLast(4)}"
        }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3-flash-preview"
        const val DEFAULT_STT_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_CHAT_MODEL = "llama-3.3-70b-versatile"

        val AVAILABLE_MODELS = listOf(
            "gemini-3-flash-preview" to "Gemini 3 Flash Preview (Recommended)",
            "gemini-3.1-pro-preview" to "Gemini 3.1 Pro Preview (Best Quality)",
            "gemini-2.5-flash" to "Gemini 2.5 Flash (Fast & Stable)",
            "gemini-2.5-flash-lite" to "Gemini 2.5 Flash-Lite (Ultra Fast)",
            "gemini-2.0-flash" to "Gemini 2.0 Flash"
        )
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PROVIDER = stringPreferencesKey("active_ai_provider")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val SELECTED_MODEL = stringPreferencesKey("selected_gemini_model")
        val THIRD_PARTY_API_KEY = stringPreferencesKey("third_party_api_key")
        val THIRD_PARTY_BASE_URL = stringPreferencesKey("third_party_base_url")
        val SELECTED_STT_MODEL = stringPreferencesKey("selected_stt_model")
        val SELECTED_CHAT_MODEL = stringPreferencesKey("selected_chat_model")
        val TARGET_GENERATOR = stringPreferencesKey("target_video_generator")
        val PREFER_HD = booleanPreferencesKey("prefer_hd_download")
        val CUSTOM_COBALT_URL = stringPreferencesKey("custom_cobalt_url")
    }

    val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val providerName = prefs[Keys.ACTIVE_PROVIDER] ?: AiProvider.GEMINI.name
        val provider = AiProvider.entries.firstOrNull { it.name == providerName }
            ?: AiProvider.GEMINI

        val genName = prefs[Keys.TARGET_GENERATOR] ?: TargetVideoGenerator.VEO_3.name
        val generator = TargetVideoGenerator.entries.firstOrNull { it.name == genName }
            ?: TargetVideoGenerator.VEO_3

        val savedGeminiModel = prefs[Keys.SELECTED_MODEL]?.trim().orEmpty()
        val resolvedGeminiModel = if (
            savedGeminiModel.isBlank() ||
            ThirdPartyModelCatalog.isDeprecatedGeminiModel(savedGeminiModel)
        ) {
            UserSettings.DEFAULT_GEMINI_MODEL
        } else {
            savedGeminiModel
        }

        UserSettings(
            activeProvider = provider,
            geminiApiKey = prefs[Keys.GEMINI_API_KEY].orEmpty(),
            selectedGeminiModel = resolvedGeminiModel,
            thirdPartyApiKey = prefs[Keys.THIRD_PARTY_API_KEY].orEmpty(),
            thirdPartyBaseUrl = prefs[Keys.THIRD_PARTY_BASE_URL].orEmpty(),
            selectedSttModel = prefs[Keys.SELECTED_STT_MODEL]
                ?.takeIf { it.isNotBlank() }
                ?: UserSettings.DEFAULT_STT_MODEL,
            selectedChatModel = prefs[Keys.SELECTED_CHAT_MODEL]
                ?.takeIf { it.isNotBlank() }
                ?: UserSettings.DEFAULT_CHAT_MODEL,
            targetGenerator = generator,
            preferHdDownload = prefs[Keys.PREFER_HD] ?: true,
            customCobaltUrl = prefs[Keys.CUSTOM_COBALT_URL].orEmpty()
        )
    }

    suspend fun saveActiveProvider(provider: AiProvider) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_PROVIDER] = provider.name
        }
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

    suspend fun saveThirdPartyConfig(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_PROVIDER] = provider.name
            prefs[Keys.THIRD_PARTY_API_KEY] = apiKey.trim()
            prefs[Keys.THIRD_PARTY_BASE_URL] = baseUrl.trim().trimEnd('/')
        }
    }

    suspend fun saveSelectedSttModel(modelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SELECTED_STT_MODEL] = modelId.trim().ifBlank { UserSettings.DEFAULT_STT_MODEL }
        }
    }

    suspend fun saveSelectedChatModel(modelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SELECTED_CHAT_MODEL] = modelId.trim().ifBlank { UserSettings.DEFAULT_CHAT_MODEL }
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
