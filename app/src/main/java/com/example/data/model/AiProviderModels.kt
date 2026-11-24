package com.example.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

enum class AiProvider(
    val displayName: String,
    val shortBadge: String,
    val defaultBaseUrl: String,
    val keyPlaceholder: String,
    val keyHintLabel: String,
    val subtitle: String
) {
    GEMINI(
        displayName = "Google Gemini",
        shortBadge = "Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        keyPlaceholder = "AIza...",
        keyHintLabel = "Gemini API Key (AIza...)",
        subtitle = "Native full video + audio understanding (Google AI Studio)"
    ),
    GROQ(
        displayName = "Groq (Free Whisper + LLM)",
        shortBadge = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        keyPlaceholder = "gsk_...",
        keyHintLabel = "Groq API Key (gsk_...)",
        subtitle = "Ultra-fast Whisper STT + Llama/Qwen Filipino translation (free tier)"
    ),
    OPENAI(
        displayName = "OpenAI",
        shortBadge = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        keyPlaceholder = "sk-...",
        keyHintLabel = "OpenAI API Key (sk-...)",
        subtitle = "Whisper / GPT-4o Transcribe + GPT chat & vision"
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        shortBadge = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        keyPlaceholder = "sk-or-v1-...",
        keyHintLabel = "OpenRouter API Key (sk-or-v1-...)",
        subtitle = "Multi-model router including :free audio & translation models"
    ),
    CUSTOM_OPENAI(
        displayName = "Custom 3rd-Party API",
        shortBadge = "Custom API",
        defaultBaseUrl = "",
        keyPlaceholder = "sk-... or Bearer token",
        keyHintLabel = "3rd-Party API Key",
        subtitle = "Any OpenAI-compatible URL (Together, Fireworks, DeepSeek, proxy)"
    );

    val isThirdParty: Boolean
        get() = this != GEMINI
}

enum class ModelCapabilityTag(val badge: String) {
    AUDIO_STT("Audio STT"),
    MULTIMODAL_AUDIO("Audio + Chat"),
    VISION_CHAT("Vision + Chat"),
    CHAT_TRANSLATE("Chat / Translate")
}

data class DetectedAiModel(
    val id: String,
    val displayLabel: String,
    val capability: ModelCapabilityTag
)

data class ProviderVerificationResult(
    val isValid: Boolean,
    val message: String,
    val provider: AiProvider,
    val resolvedBaseUrl: String,
    val allModels: List<DetectedAiModel> = emptyList(),
    val recommendedSttModel: String? = null,
    val recommendedChatModel: String? = null,
    val recommendedVisionModel: String? = null
)

data class WhisperSegment(
    val startSec: Double,
    val endSec: Double,
    val text: String
)

data class WhisperTranscriptionChunk(
    val detectedLanguage: String,
    val text: String,
    val segments: List<WhisperSegment>
)

object ThirdPartyModelCatalog {

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val mapAdapter by lazy {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        moshi.adapter<Map<String, Any?>>(type)
    }

    val GROQ_DEFAULT_STT_MODELS = listOf(
        DetectedAiModel("whisper-large-v3-turbo", "whisper-large-v3-turbo (Fast STT)", ModelCapabilityTag.AUDIO_STT),
        DetectedAiModel("whisper-large-v3", "whisper-large-v3 (Accurate STT)", ModelCapabilityTag.AUDIO_STT)
    )

    val GROQ_DEFAULT_CHAT_MODELS = listOf(
        DetectedAiModel("llama-3.3-70b-versatile", "llama-3.3-70b-versatile", ModelCapabilityTag.CHAT_TRANSLATE),
        DetectedAiModel("meta-llama/llama-4-scout-17b-16e-instruct", "llama-4-scout (Vision)", ModelCapabilityTag.VISION_CHAT),
        DetectedAiModel("llama-3.1-8b-instant", "llama-3.1-8b-instant", ModelCapabilityTag.CHAT_TRANSLATE)
    )

    val OPENAI_DEFAULT_STT_MODELS = listOf(
        DetectedAiModel("gpt-4o-mini-transcribe", "gpt-4o-mini-transcribe", ModelCapabilityTag.AUDIO_STT),
        DetectedAiModel("whisper-1", "whisper-1", ModelCapabilityTag.AUDIO_STT)
    )

    val OPENAI_DEFAULT_CHAT_MODELS = listOf(
        DetectedAiModel("gpt-4o-mini", "gpt-4o-mini (Vision + Chat)", ModelCapabilityTag.VISION_CHAT),
        DetectedAiModel("gpt-4.1-mini", "gpt-4.1-mini (Vision + Chat)", ModelCapabilityTag.VISION_CHAT)
    )

    val OPENROUTER_DEFAULT_CHAT_MODELS = listOf(
        DetectedAiModel("google/gemini-2.5-flash", "gemini-2.5-flash (Audio + Vision)", ModelCapabilityTag.MULTIMODAL_AUDIO),
        DetectedAiModel("openai/gpt-4o-mini", "openai/gpt-4o-mini", ModelCapabilityTag.VISION_CHAT),
        DetectedAiModel("meta-llama/llama-3.3-70b-instruct:free", "llama-3.3-70b-instruct:free", ModelCapabilityTag.CHAT_TRANSLATE)
    )

    fun normalizeBaseUrl(provider: AiProvider, customBaseUrl: String): String {
        if (provider != AiProvider.CUSTOM_OPENAI) {
            return provider.defaultBaseUrl.trimEnd('/')
        }
        var cleaned = customBaseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "https://$cleaned"
        }
        if (cleaned.endsWith("/models")) {
            cleaned = cleaned.removeSuffix("/models").trimEnd('/')
        }
        return cleaned
    }

    fun isDeprecatedGeminiModel(modelId: String): Boolean {
        val lower = modelId.lowercase().trim()
        return lower.startsWith("gemini-1.0") ||
            lower.startsWith("gemini-1.5") ||
            lower == "gemini-pro" ||
            lower == "gemini-pro-vision" ||
            lower.contains("2.0-pro-exp") ||
            lower.contains("2.0-flash-thinking-exp") ||
            lower == "gemini-2.5-pro" ||
            lower.startsWith("gemini-2.5-pro-preview") ||
            lower.startsWith("gemini-2.5-pro-exp")
    }

    fun pickPreferredGeminiModel(
        availableIds: List<String>,
        excluding: Set<String> = emptySet()
    ): String? {
        val candidates = availableIds.filterNot { it in excluding }
        if (candidates.isEmpty()) return null

        val preferredOrder = listOf(
            "gemini-3-flash-preview",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-3-pro-preview",
            "gemini-2.0-flash"
        )
        preferredOrder.firstOrNull { pref -> candidates.any { it == pref } }?.let { return it }

        val activeFlash = candidates.firstOrNull {
            it.lowercase().contains("flash") && !isDeprecatedGeminiModel(it)
        }
        if (activeFlash != null) return activeFlash

        val nonDeprecated = candidates.firstOrNull { !isDeprecatedGeminiModel(it) }
        return nonDeprecated ?: candidates.firstOrNull()
    }

    fun parseGeminiModelsJson(jsonBody: String): List<DetectedAiModel> {
        val root = runCatching { mapAdapter.fromJson(jsonBody) }.getOrNull() ?: return emptyList()
        val rawModels = root["models"] as? List<*> ?: return emptyList()

        val result = mutableListOf<DetectedAiModel>()
        for (item in rawModels) {
            val map = item as? Map<*, *> ?: continue
            val rawName = (map["name"] as? String).orEmpty().removePrefix("models/").trim()
            if (rawName.isBlank()) continue
            val methods = (map["supportedGenerationMethods"] as? List<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()
            val supportsGenerate = methods.contains("generateContent")
            val lower = rawName.lowercase()
            val isVideoCapable = supportsGenerate &&
                lower.startsWith("gemini-") &&
                !lower.contains("embedding") &&
                !lower.contains("tts") &&
                !lower.contains("-image") &&
                !lower.contains("imagen") &&
                !lower.contains("robotics") &&
                !lower.contains("computer-use") &&
                !isDeprecatedGeminiModel(rawName)
            if (isVideoCapable) {
                val display = (map["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: rawName
                result.add(
                    DetectedAiModel(
                        id = rawName,
                        displayLabel = display,
                        capability = ModelCapabilityTag.MULTIMODAL_AUDIO
                    )
                )
            }
        }
        return result.distinctBy { it.id }
    }

    fun classifyOpenAiModel(rawId: String, rawDisplayName: String? = null): DetectedAiModel? {
        val id = rawId.trim()
        if (id.isBlank()) return null
        val lower = id.lowercase()

        val excludedTokens = listOf(
            "embedding", "moderation", "llama-guard", "prompt-guard", "safeguard",
            "tts", "dall-e", "gpt-image", "chatgpt-image", "sora", "babbage",
            "davinci", "realtime", "computer-use", "deep-research", "orpheus",
            "kokoro", "playai"
        )
        if (excludedTokens.any { lower.contains(it) }) {
            return null
        }

        val capability = when {
            lower.contains("whisper") ||
                lower.contains("transcribe") ||
                lower.endsWith("-stt") ||
                lower.contains("/stt") -> ModelCapabilityTag.AUDIO_STT

            lower.contains("audio") ||
                lower.contains("voxtral") ||
                (lower.contains("gemini-") && !lower.contains("gemma")) -> ModelCapabilityTag.MULTIMODAL_AUDIO

            lower.contains("llama-4") ||
                lower.contains("gpt-4o") ||
                lower.contains("gpt-4.1") ||
                lower.contains("gpt-5") ||
                lower.contains("claude-3") ||
                lower.contains("claude-sonnet-4") ||
                lower.contains("claude-opus-4") ||
                lower.contains("pixtral") ||
                lower.contains("vision") ||
                (lower.contains("qwen") && lower.contains("vl")) -> ModelCapabilityTag.VISION_CHAT

            else -> ModelCapabilityTag.CHAT_TRANSLATE
        }

        val label = rawDisplayName?.takeIf { it.isNotBlank() && it != id } ?: id
        return DetectedAiModel(
            id = id,
            displayLabel = label,
            capability = capability
        )
    }

    fun parseOpenAiModelsJson(
        jsonBody: String,
        provider: AiProvider,
        resolvedBaseUrl: String
    ): ProviderVerificationResult {
        val root = runCatching { mapAdapter.fromJson(jsonBody) }.getOrNull()
            ?: return ProviderVerificationResult(
                isValid = false,
                message = "Invalid JSON response from $resolvedBaseUrl/models.",
                provider = provider,
                resolvedBaseUrl = resolvedBaseUrl
            )

        val rawList = (root["data"] as? List<*>)
            ?: (root["models"] as? List<*>)
            ?: emptyList<Any>()

        val detected = rawList.mapNotNull { entry ->
            when (entry) {
                is Map<*, *> -> {
                    val id = (entry["id"] as? String)
                        ?: (entry["name"] as? String)
                        ?: return@mapNotNull null
                    val name = entry["name"] as? String
                    classifyOpenAiModel(id, name)
                }
                is String -> classifyOpenAiModel(entry)
                else -> null
            }
        }.distinctBy { it.id }

        val sttModels = detected.filter {
            it.capability == ModelCapabilityTag.AUDIO_STT ||
                it.capability == ModelCapabilityTag.MULTIMODAL_AUDIO
        }
        val chatModels = detected.filter {
            it.capability != ModelCapabilityTag.AUDIO_STT
        }
        val visionModels = detected.filter {
            it.capability == ModelCapabilityTag.VISION_CHAT ||
                it.capability == ModelCapabilityTag.MULTIMODAL_AUDIO
        }

        val recommendedStt = pickBestSttModel(sttModels.map { it.id })
        val recommendedChat = pickBestChatModel(chatModels.map { it.id })
        val recommendedVision = pickBestVisionModel(visionModels.map { it.id }) ?: recommendedChat

        val sttCount = detected.count { it.capability == ModelCapabilityTag.AUDIO_STT }
        val audioChatCount = detected.count { it.capability == ModelCapabilityTag.MULTIMODAL_AUDIO }
        val chatCount = detected.count {
            it.capability == ModelCapabilityTag.CHAT_TRANSLATE ||
                it.capability == ModelCapabilityTag.VISION_CHAT
        }

        val summary = buildString {
            append("Connected to ${provider.shortBadge}! Detected ${detected.size} models")
            val parts = mutableListOf<String>()
            if (sttCount > 0) parts.add("$sttCount Audio STT")
            if (audioChatCount > 0) parts.add("$audioChatCount Multimodal Audio")
            if (chatCount > 0) parts.add("$chatCount Chat/Translate")
            if (parts.isNotEmpty()) {
                append(" (${parts.joinToString(" · ")})")
            }
            append(".")
        }

        return ProviderVerificationResult(
            isValid = true,
            message = summary,
            provider = provider,
            resolvedBaseUrl = resolvedBaseUrl,
            allModels = detected.sortedWith(
                compareBy<DetectedAiModel> { it.capability.ordinal }.thenBy { it.id.lowercase() }
            ),
            recommendedSttModel = recommendedStt,
            recommendedChatModel = recommendedChat,
            recommendedVisionModel = recommendedVision
        )
    }

    fun pickBestSttModel(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val priority = listOf(
            "whisper-large-v3-turbo",
            "whisper-large-v3",
            "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe",
            "whisper-1",
            "distil-whisper-large-v3-en",
            "google/gemini-2.5-flash",
            "google/gemini-2.5-flash:free",
            "openai/gpt-4o-audio-preview"
        )
        priority.firstOrNull { pref -> ids.any { it.equals(pref, ignoreCase = true) } }?.let { pref ->
            return ids.first { it.equals(pref, ignoreCase = true) }
        }
        return ids.firstOrNull { it.lowercase().contains("whisper") && !it.lowercase().contains("-en") }
            ?: ids.firstOrNull { it.lowercase().contains("transcribe") }
            ?: ids.firstOrNull { it.lowercase().contains("whisper") }
            ?: ids.firstOrNull()
    }

    fun pickBestChatModel(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val priority = listOf(
            "llama-3.3-70b-versatile",
            "qwen/qwen3-32b",
            "qwen-qwq-32b",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "gpt-4o-mini",
            "gpt-4.1-mini",
            "gpt-4o",
            "google/gemini-2.5-flash",
            "openai/gpt-4o-mini",
            "meta-llama/llama-3.3-70b-instruct:free",
            "deepseek/deepseek-chat",
            "deepseek-chat",
            "llama-3.1-8b-instant",
            "gemma2-9b-it"
        )
        priority.firstOrNull { pref -> ids.any { it.equals(pref, ignoreCase = true) } }?.let { pref ->
            return ids.first { it.equals(pref, ignoreCase = true) }
        }
        return ids.firstOrNull { it.lowercase().contains("70b") }
            ?: ids.firstOrNull { it.lowercase().contains("mini") }
            ?: ids.firstOrNull { it.lowercase().contains("flash") }
            ?: ids.firstOrNull()
    }

    fun pickBestVisionModel(ids: List<String>): String? {
        if (ids.isEmpty()) return null
        val priority = listOf(
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "gpt-4o-mini",
            "gpt-4.1-mini",
            "gpt-4o",
            "google/gemini-2.5-flash",
            "openai/gpt-4o-mini"
        )
        priority.firstOrNull { pref -> ids.any { it.equals(pref, ignoreCase = true) } }?.let { pref ->
            return ids.first { it.equals(pref, ignoreCase = true) }
        }
        return ids.firstOrNull()
    }

    fun parseWhisperVerboseJson(jsonBody: String): WhisperTranscriptionChunk {
        val root = runCatching { mapAdapter.fromJson(jsonBody) }.getOrNull()
            ?: throw IllegalStateException("Unexpected transcription response format.")

        val detectedLanguage = (root["language"] as? String)?.takeIf { it.isNotBlank() } ?: "Auto-detected"
        val fullText = (root["text"] as? String).orEmpty().trim()
        val rawSegments = root["segments"] as? List<*> ?: emptyList<Any>()

        val segments = rawSegments.mapNotNull { entry ->
            val seg = entry as? Map<*, *> ?: return@mapNotNull null
            val start = (seg["start"] as? Number)?.toDouble() ?: 0.0
            val end = (seg["end"] as? Number)?.toDouble() ?: (start + 2.5)
            val text = (seg["text"] as? String).orEmpty().trim()
            if (text.isBlank()) null else WhisperSegment(startSec = start, endSec = end.coerceAtLeast(start + 0.4), text = text)
        }

        return WhisperTranscriptionChunk(
            detectedLanguage = detectedLanguage,
            text = fullText.ifBlank { segments.joinToString(" ") { it.text } },
            segments = segments
        )
    }

    fun extractOpenAiChatContent(rawBody: String): String {
        val root = runCatching { mapAdapter.fromJson(rawBody) }.getOrNull()
            ?: throw IllegalStateException("Invalid chat completion response JSON.")
        val choices = root["choices"] as? List<*>
            ?: throw IllegalStateException("Model returned no choices.")
        val first = choices.firstOrNull() as? Map<*, *>
            ?: throw IllegalStateException("Empty chat completion choice.")
        val message = first["message"] as? Map<*, *>
            ?: throw IllegalStateException("Missing message object in completion.")

        val content = when (val raw = message["content"]) {
            is String -> raw
            is List<*> -> raw.mapNotNull { part ->
                (part as? Map<*, *>)?.get("text") as? String
            }.joinToString("\n")
            else -> ""
        }.trim()

        if (content.isBlank()) {
            throw IllegalStateException("Model returned blank text.")
        }
        return stripMarkdownJsonFence(content)
    }

    fun stripMarkdownJsonFence(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter('\n', cleaned)
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substringBeforeLast("```")
            }
            cleaned = cleaned.trim()
        }
        val firstBrace = cleaned.indexOf('{')
        val firstBracket = cleaned.indexOf('[')
        val startIdx = when {
            firstBrace >= 0 && firstBracket >= 0 -> minOf(firstBrace, firstBracket)
            firstBrace >= 0 -> firstBrace
            else -> firstBracket
        }
        if (startIdx > 0) {
            cleaned = cleaned.substring(startIdx)
        }
        val lastBrace = cleaned.lastIndexOf('}')
        val lastBracket = cleaned.lastIndexOf(']')
        val endIdx = maxOf(lastBrace, lastBracket)
        if (endIdx >= 0 && endIdx < cleaned.lastIndex) {
            cleaned = cleaned.substring(0, endIdx + 1)
        }
        return cleaned.trim()
    }
}
