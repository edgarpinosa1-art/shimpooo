package com.example.data.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.dubbing.DubTimelineMath
import com.example.data.model.AiProvider
import com.example.data.model.DubLineItem
import com.example.data.model.DubSourceLanguage
import com.example.data.model.LocalVideoInspection
import com.example.data.model.ModelCapabilityTag
import com.example.data.model.PromptBlueprint
import com.example.data.model.PromptUpgradePreset
import com.example.data.model.ProviderVerificationResult
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.ThirdPartyModelCatalog
import com.example.data.model.TranscriptLanguageMode
import com.example.data.model.TranscriptSegment
import com.example.data.model.VideoAspectRatio
import com.example.data.model.VideoTranscript
import com.example.data.model.WhisperTranscriptionChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

private const val TAG = "ThirdPartyAiRepo"

data class RawTimedSpeechSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    val text: String
)

object ThirdPartyTranscriptHelper {

    fun flattenChunkToGlobalSegments(
        chunk: WhisperTranscriptionChunk,
        startOffsetMs: Long,
        chunkDurationMs: Long,
        startingIndex: Int
    ): List<RawTimedSpeechSegment> {
        if (chunk.segments.isNotEmpty()) {
            return chunk.segments.mapIndexed { idx, seg ->
                val sMs = startOffsetMs + (seg.startSec * 1000.0).roundToLong().coerceAtLeast(0L)
                val eMs = startOffsetMs + (seg.endSec * 1000.0).roundToLong().coerceAtLeast((seg.startSec * 1000.0).roundToLong() + 400L)
                RawTimedSpeechSegment(
                    index = startingIndex + idx,
                    startMs = sMs,
                    endMs = eMs.coerceAtLeast(sMs + 400L),
                    speaker = "Speaker 1",
                    text = seg.text.trim()
                )
            }
        }
        val cleanText = chunk.text.trim()
        if (cleanText.isBlank()) return emptyList()

        // Split plain transcription text proportionally across chunk duration
        val sentences = cleanText
            .split(Regex("""(?<=[.!?。！？])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(cleanText) }

        val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)
        val effectiveDur = chunkDurationMs.coerceAtLeast(sentences.size * 1500L)
        var cursorMs = startOffsetMs

        return sentences.mapIndexed { idx, sentence ->
            val sliceMs = ((sentence.length.toDouble() / totalChars.toDouble()) * effectiveDur)
                .roundToLong()
                .coerceAtLeast(1000L)
            val endMs = if (idx == sentences.lastIndex) {
                startOffsetMs + effectiveDur
            } else {
                cursorMs + sliceMs
            }
            val item = RawTimedSpeechSegment(
                index = startingIndex + idx,
                startMs = cursorMs,
                endMs = endMs.coerceAtLeast(cursorMs + 500L),
                speaker = "Speaker 1",
                text = sentence
            )
            cursorMs = endMs
            item
        }
    }
}

class ThirdPartyAiRepository(private val context: Context) {

    private val client = NetworkClient.httpClient
    private val audioExtractor = AudioChunkExtractor(context)

    suspend fun verifyAndDetectModels(
        provider: AiProvider,
        apiKey: String,
        customBaseUrl: String
    ): ProviderVerificationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val resolvedBase = ThirdPartyModelCatalog.normalizeBaseUrl(provider, customBaseUrl)
        if (resolvedBase.isBlank()) {
            return@withContext ProviderVerificationResult(
                isValid = false,
                message = "Ilagay ang Base URL ng 3rd-party API (hal. https://api.groq.com/openai/v1).",
                provider = provider,
                resolvedBaseUrl = resolvedBase
            )
        }
        if (cleanKey.isBlank()) {
            return@withContext ProviderVerificationResult(
                isValid = false,
                message = "Ilagay muna ang iyong ${provider.shortBadge} API key.",
                provider = provider,
                resolvedBaseUrl = resolvedBase
            )
        }

        try {
            val reqBuilder = Request.Builder()
                .url("$resolvedBase/models")
                .header("Authorization", "Bearer $cleanKey")
                .header("Accept", "application/json")
                .get()
            applyProviderHeaders(provider, reqBuilder)

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = parseThirdPartyErrorMessage(provider, rawBody, resp.code)
                    return@withContext ProviderVerificationResult(
                        isValid = false,
                        message = errMsg,
                        provider = provider,
                        resolvedBaseUrl = resolvedBase
                    )
                }
                ThirdPartyModelCatalog.parseOpenAiModelsJson(
                    jsonBody = rawBody,
                    provider = provider,
                    resolvedBaseUrl = resolvedBase
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "3rd-party key verification failed", e)
            ProviderVerificationResult(
                isValid = false,
                message = e.localizedMessage ?: "Network error connecting to ${provider.shortBadge}.",
                provider = provider,
                resolvedBaseUrl = resolvedBase
            )
        }
    }

    suspend fun transcribeVideoWithThirdParty(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        sttModelId: String,
        chatModelId: String,
        inspection: LocalVideoInspection,
        languageMode: TranscriptLanguageMode,
        onStageUpdate: (String) -> Unit,
        onModelsAutoUpdated: (sttModel: String?, chatModel: String?) -> Unit = { _, _ -> }
    ): Result<VideoTranscript> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val resolvedBase = ThirdPartyModelCatalog.normalizeBaseUrl(provider, baseUrl)
        if (cleanKey.isBlank() || resolvedBase.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Kumpletuhin muna ang ${provider.shortBadge} API key at Base URL.")
            )
        }

        val workDir = File(context.cacheDir, "tp_transcript_${System.currentTimeMillis()}")
        try {
            val useChatInputAudio = shouldUseChatInputAudio(provider, sttModelId)
            val audioChunks = audioExtractor.extractAudioChunks(
                uriString = inspection.uriString,
                durationHintMs = inspection.durationMs,
                workDir = workDir,
                forceWavForChatAudio = useChatInputAudio,
                onStageUpdate = onStageUpdate
            )

            var activeStt = sttModelId.trim().ifBlank { defaultSttForProvider(provider) }
            var activeChat = chatModelId.trim().ifBlank { defaultChatForProvider(provider) }

            val rawSegments = mutableListOf<RawTimedSpeechSegment>()
            var detectedLanguage = "Auto-detected"

            for ((idx, chunk) in audioChunks.withIndex()) {
                val partLabel = if (audioChunks.size > 1) {
                    "part ${idx + 1}/${audioChunks.size}"
                } else {
                    "full audio"
                }
                onStageUpdate("Transcribing $partLabel using $activeStt...")

                val transcribedChunk = if (useChatInputAudio) {
                    transcribeChunkViaChatInputAudio(
                        provider = provider,
                        apiKey = cleanKey,
                        baseUrl = resolvedBase,
                        modelId = activeStt,
                        chunk = chunk
                    )
                } else {
                    transcribeChunkViaAudioEndpoint(
                        provider = provider,
                        apiKey = cleanKey,
                        baseUrl = resolvedBase,
                        initialModelId = activeStt,
                        chunk = chunk,
                        onModelFallback = { fallback ->
                            activeStt = fallback
                            onModelsAutoUpdated(fallback, null)
                        }
                    )
                }

                if (transcribedChunk.detectedLanguage.isNotBlank() &&
                    transcribedChunk.detectedLanguage != "Auto-detected"
                ) {
                    detectedLanguage = transcribedChunk.detectedLanguage
                }

                val mapped = ThirdPartyTranscriptHelper.flattenChunkToGlobalSegments(
                    chunk = transcribedChunk,
                    startOffsetMs = chunk.startOffsetMs,
                    chunkDurationMs = chunk.durationMs,
                    startingIndex = rawSegments.size + 1
                )
                rawSegments.addAll(mapped)
            }

            if (rawSegments.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Walang spoken audio na na-detect sa video.")
                )
            }

            // Now use chat model to apply exact target language mode (Tagalog / Lip-Sync / Bilingual / Speaker cleanup) + summary
            val finalSegments = mutableListOf<TranscriptSegment>()
            var summaryAcc = ""
            val batches = rawSegments.chunked(32)

            for ((bIdx, batch) in batches.withIndex()) {
                val actionLabel = if (languageMode == TranscriptLanguageMode.ORIGINAL_VERBATIM) {
                    "Formatting transcript"
                } else {
                    "Translating to ${languageMode.label}"
                }
                onStageUpdate("$actionLabel (${bIdx + 1}/${batches.size}) via $activeChat...")

                val (translatedBatch, batchSummary, usedChat) = transformTranscriptBatchWithChatModel(
                    provider = provider,
                    apiKey = cleanKey,
                    baseUrl = resolvedBase,
                    initialModelId = activeChat,
                    batch = batch,
                    languageMode = languageMode,
                    detectedLanguage = detectedLanguage,
                    onModelFallback = { fallback ->
                        activeChat = fallback
                        onModelsAutoUpdated(null, fallback)
                    }
                )
                activeChat = usedChat
                finalSegments.addAll(translatedBatch)
                if (summaryAcc.isBlank() && batchSummary.isNotBlank()) {
                    summaryAcc = batchSummary
                }
            }

            val fullPlain = finalSegments.joinToString(" ") { it.text }
            val modelLabel = if (activeStt == activeChat) {
                "${provider.shortBadge}: $activeChat"
            } else {
                "${provider.shortBadge}: $activeStt + $activeChat"
            }

            Result.success(
                VideoTranscript(
                    videoTitle = inspection.displayName,
                    sourceUriString = inspection.uriString,
                    geminiModelUsed = modelLabel,
                    languageMode = languageMode,
                    detectedLanguage = detectedLanguage,
                    overallSummary = summaryAcc.ifBlank {
                        "Transcribed via ${provider.shortBadge} ($activeStt) and formatted with $activeChat."
                    },
                    segments = finalSegments,
                    fullPlainTranscript = fullPlain
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "3rd-party transcription failed", e)
            Result.failure(e)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    suspend fun generateIsochronicFilipinoDubScriptWithThirdParty(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        sttModelId: String,
        chatModelId: String,
        inspection: LocalVideoInspection,
        sourceLanguage: DubSourceLanguage,
        onStageUpdate: (String) -> Unit,
        onModelsAutoUpdated: (sttModel: String?, chatModel: String?) -> Unit = { _, _ -> }
    ): Result<List<DubLineItem>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val resolvedBase = ThirdPartyModelCatalog.normalizeBaseUrl(provider, baseUrl)
        if (cleanKey.isBlank() || resolvedBase.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Kumpletuhin muna ang ${provider.shortBadge} API key at Base URL.")
            )
        }

        val workDir = File(context.cacheDir, "tp_dub_${System.currentTimeMillis()}")
        try {
            val useChatInputAudio = shouldUseChatInputAudio(provider, sttModelId)
            val audioChunks = audioExtractor.extractAudioChunks(
                uriString = inspection.uriString,
                durationHintMs = inspection.durationMs,
                workDir = workDir,
                forceWavForChatAudio = useChatInputAudio,
                onStageUpdate = onStageUpdate
            )

            var activeStt = sttModelId.trim().ifBlank { defaultSttForProvider(provider) }
            var activeChat = chatModelId.trim().ifBlank { defaultChatForProvider(provider) }

            val rawSegments = mutableListOf<RawTimedSpeechSegment>()
            for ((idx, chunk) in audioChunks.withIndex()) {
                val partLabel = if (audioChunks.size > 1) "part ${idx + 1}/${audioChunks.size}" else "audio"
                onStageUpdate("Extracting timed speech ($partLabel) with $activeStt...")

                val transcribedChunk = if (useChatInputAudio) {
                    transcribeChunkViaChatInputAudio(
                        provider = provider,
                        apiKey = cleanKey,
                        baseUrl = resolvedBase,
                        modelId = activeStt,
                        chunk = chunk
                    )
                } else {
                    transcribeChunkViaAudioEndpoint(
                        provider = provider,
                        apiKey = cleanKey,
                        baseUrl = resolvedBase,
                        initialModelId = activeStt,
                        chunk = chunk,
                        onModelFallback = { fallback ->
                            activeStt = fallback
                            onModelsAutoUpdated(fallback, null)
                        }
                    )
                }

                rawSegments.addAll(
                    ThirdPartyTranscriptHelper.flattenChunkToGlobalSegments(
                        chunk = transcribedChunk,
                        startOffsetMs = chunk.startOffsetMs,
                        chunkDurationMs = chunk.durationMs,
                        startingIndex = rawSegments.size + 1
                    )
                )
            }

            if (rawSegments.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Walang spoken dialogue na nakita sa video para i-dub.")
                )
            }

            val batches = rawSegments.chunked(28)
            val discoveredRoster = linkedSetOf<String>()
            val allDubLines = mutableListOf<DubLineItem>()

            for ((bIdx, batch) in batches.withIndex()) {
                onStageUpdate("Adapting Filipino lip-sync lines (${bIdx + 1}/${batches.size}) via $activeChat...")
                val (dubBatch, usedChat) = translateDubBatchWithChatModel(
                    provider = provider,
                    apiKey = cleanKey,
                    baseUrl = resolvedBase,
                    initialModelId = activeChat,
                    batch = batch,
                    sourceLanguage = sourceLanguage,
                    discoveredRoster = discoveredRoster,
                    onModelFallback = { fallback ->
                        activeChat = fallback
                        onModelsAutoUpdated(null, fallback)
                    }
                )
                activeChat = usedChat
                dubBatch.forEach { item ->
                    discoveredRoster.add(item.speaker)
                    allDubLines.add(item)
                }
            }

            val normalized = allDubLines.mapIndexed { idx, line ->
                line.copy(id = (idx + 1).toLong())
            }
            if (normalized.isEmpty()) {
                Result.failure(IllegalStateException("Walang Filipino dub lines na na-generate."))
            } else {
                Result.success(normalized)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "3rd-party dub script failed", e)
            Result.failure(e)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    suspend fun analyzeVideoForPromptsWithThirdParty(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        chatModelId: String,
        sttModelId: String,
        inspection: LocalVideoInspection,
        targetGenerator: TargetVideoGenerator,
        aspectRatio: VideoAspectRatio,
        upgradePreset: PromptUpgradePreset,
        extraNotes: String,
        onStageUpdate: (String) -> Unit,
        onModelsAutoUpdated: (sttModel: String?, chatModel: String?) -> Unit = { _, _ -> }
    ): Result<PromptBlueprint> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val resolvedBase = ThirdPartyModelCatalog.normalizeBaseUrl(provider, baseUrl)
        if (cleanKey.isBlank() || resolvedBase.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Kumpletuhin muna ang ${provider.shortBadge} API key at Base URL.")
            )
        }

        val workDir = File(context.cacheDir, "tp_prompt_${System.currentTimeMillis()}")
        try {
            var activeChat = chatModelId.trim().ifBlank { defaultChatForProvider(provider) }

            // Optional brief audio transcript context from the first chunk so the video prompt is richer
            val audioSnippet = runCatching {
                if (!shouldUseChatInputAudio(provider, sttModelId)) {
                    onStageUpdate("Sampling audio dialogue for prompt context...")
                    val chunks = audioExtractor.extractAudioChunks(
                        uriString = inspection.uriString,
                        durationHintMs = minOf(inspection.durationMs, 180_000L),
                        workDir = workDir,
                        forceWavForChatAudio = false,
                        onStageUpdate = {}
                    )
                    chunks.firstOrNull()?.let { firstChunk ->
                        transcribeChunkViaAudioEndpoint(
                            provider = provider,
                            apiKey = cleanKey,
                            baseUrl = resolvedBase,
                            initialModelId = sttModelId.trim().ifBlank { defaultSttForProvider(provider) },
                            chunk = firstChunk,
                            onModelFallback = { fallback -> onModelsAutoUpdated(fallback, null) }
                        ).text.take(1500)
                    }.orEmpty()
                } else ""
            }.getOrDefault("")

            val durationSec = inspection.durationMs / 1000.0
            val aspectHint = if (inspection.width > 0 && inspection.height > 0) {
                "${inspection.width}x${inspection.height}"
            } else {
                "vertical/social"
            }

            val instruction = """
                You are an elite AI Cinematographer, Prompt Reverse-Engineer, and VFX Director.
                Analyze the attached keyframes (chronological frames from start to end of the video) plus metadata.
                Specs: ${String.format(java.util.Locale.US, "%.1f", durationSec)}s, resolution $aspectHint.
                Target AI Video Generator: ${targetGenerator.displayName} (${targetGenerator.promptGuidance}).
                Target Output Framing: ${aspectRatio.label} — ${aspectRatio.directive}.
                Creative Mode: ${upgradePreset.label} — ${upgradePreset.directive}.
                ${if (extraNotes.isNotBlank()) "Additional Creator Notes: $extraNotes" else ""}
                ${if (audioSnippet.isNotBlank()) "Spoken Audio Context: $audioSnippet" else ""}

                Return ONLY a valid JSON object with exact keys:
                {
                  "whatHappenedSummary": "...",
                  "originalPromptGuess": "...",
                  "visualStyle": "...",
                  "cameraAndLens": "...",
                  "lightingAndPalette": "...",
                  "audioAndPacing": "...",
                  "ingredients": [
                    { "name": "...", "role": "Character|Environment|Prop|Outfit", "visualDetails": "...", "imagePrompt": "...", "negativePrompt": "..." }
                  ],
                  "masterVideoPrompt": "...",
                  "negativeVideoPrompt": "...",
                  "scenes": [
                    { "timeRange": "00:00 - 00:03", "shotType": "...", "cameraMovement": "...", "actionDescription": "...", "scenePrompt": "..." }
                  ],
                  "recreationTips": "..."
                }
            """.trimIndent()

            onStageUpdate("Analyzing keyframes & building prompt blueprint via $activeChat...")

            val contentArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", instruction)
                })
                inspection.keyframePaths.take(4).forEach { framePath ->
                    val f = File(framePath)
                    if (f.exists() && f.length() > 0L) {
                        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$b64")
                            })
                        })
                    }
                }
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArr)
                })
            }

            val (jsonText, usedModel) = try {
                executeChatCompletionsWithFallback(
                    provider = provider,
                    apiKey = cleanKey,
                    baseUrl = resolvedBase,
                    initialModelId = activeChat,
                    messages = messages,
                    temperature = 0.45,
                    requireJsonObject = true,
                    timeoutMs = 115_000L,
                    onModelFallback = { fallback ->
                        activeChat = fallback
                        onModelsAutoUpdated(null, fallback)
                    }
                )
            } catch (visionErr: Exception) {
                // If selected chat model doesn't support image_url, try auto-switching to a vision model or text-only fallback
                val verifyRes = verifyAndDetectModels(provider, cleanKey, resolvedBase)
                val visionCandidate = verifyRes.recommendedVisionModel
                if (!visionCandidate.isNullOrBlank() && visionCandidate != activeChat) {
                    onStageUpdate("Switching to vision model $visionCandidate...")
                    onModelsAutoUpdated(null, visionCandidate)
                    executeChatCompletionsWithFallback(
                        provider = provider,
                        apiKey = cleanKey,
                        baseUrl = resolvedBase,
                        initialModelId = visionCandidate,
                        messages = messages,
                        temperature = 0.45,
                        requireJsonObject = true,
                        timeoutMs = 115_000L,
                        onModelFallback = {}
                    )
                } else {
                    throw visionErr
                }
            }

            val blueprint = PromptBlueprintSerializer.fromGeminiOrStoredJson(
                jsonString = jsonText,
                videoTitle = inspection.displayName,
                sourceUriString = inspection.uriString,
                targetGenerator = targetGenerator,
                geminiModelUsed = "${provider.shortBadge}: $usedModel"
            )
            Result.success(blueprint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "3rd-party video prompt analysis failed", e)
            Result.failure(e)
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private suspend fun transcribeChunkViaAudioEndpoint(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        initialModelId: String,
        chunk: ExtractedAudioChunk,
        onModelFallback: (String) -> Unit
    ): WhisperTranscriptionChunk {
        var currentModel = initialModelId
        val tried = linkedSetOf<String>()

        for (attempt in 0..2) {
            tried.add(currentModel)
            try {
                return callWhisperMultipart(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    modelId = currentModel,
                    chunk = chunk,
                    useVerboseJson = true
                )
            } catch (e: ThirdPartyHttpException) {
                val lower = e.message.lowercase()
                if (e.statusCode == 400 && (lower.contains("response_format") || lower.contains("verbose_json") || lower.contains("granularities"))) {
                    // Server supports /audio/transcriptions with response_format=json only
                    return callWhisperMultipart(
                        provider = provider,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        modelId = currentModel,
                        chunk = chunk,
                        useVerboseJson = false
                    )
                }
                val isModelMissing = e.statusCode == 404 ||
                    lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist") || lower.contains("decommissioned") || lower.contains("deprecated"))
                if (isModelMissing && attempt < 2) {
                    val verify = verifyAndDetectModels(provider, apiKey, baseUrl)
                    val candidateIds = verify.allModels
                        .filter { it.capability == ModelCapabilityTag.AUDIO_STT }
                        .map { it.id }
                        .filterNot { it in tried }
                    val fallback = ThirdPartyModelCatalog.pickBestSttModel(candidateIds)
                    if (!fallback.isNullOrBlank()) {
                        currentModel = fallback
                        onModelFallback(fallback)
                        continue
                    }
                }
                throw e
            }
        }
        throw IllegalStateException("Audio transcription failed for $currentModel.")
    }

    private suspend fun callWhisperMultipart(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String,
        chunk: ExtractedAudioChunk,
        useVerboseJson: Boolean
    ): WhisperTranscriptionChunk {
        var lastErr: Exception? = null
        repeat(2) { retry ->
            try {
                return withTimeout(180_000L) {
                    val bodyBuilder = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "file",
                            chunk.file.name,
                            chunk.file.asRequestBody(chunk.mimeType.toMediaType())
                        )
                        .addFormDataPart("model", modelId)

                    if (useVerboseJson) {
                        bodyBuilder.addFormDataPart("response_format", "verbose_json")
                        bodyBuilder.addFormDataPart("timestamp_granularities[]", "segment")
                    } else {
                        bodyBuilder.addFormDataPart("response_format", "json")
                    }

                    val reqBuilder = Request.Builder()
                        .url("$baseUrl/audio/transcriptions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(bodyBuilder.build())
                    applyProviderHeaders(provider, reqBuilder)

                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw ThirdPartyHttpException(
                                statusCode = resp.code,
                                message = parseThirdPartyErrorMessage(provider, raw, resp.code)
                            )
                        }
                        ThirdPartyModelCatalog.parseWhisperVerboseJson(raw)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ThirdPartyHttpException) {
                if (e.statusCode in 400..499 && e.statusCode != 429) throw e
                lastErr = e
                if (retry == 0) delay(1500L)
            } catch (e: Exception) {
                lastErr = e
                if (retry == 0) delay(1500L)
            }
        }
        throw lastErr ?: IllegalStateException("Transcription network error.")
    }

    private suspend fun transcribeChunkViaChatInputAudio(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String,
        chunk: ExtractedAudioChunk
    ): WhisperTranscriptionChunk {
        val audioB64 = Base64.encodeToString(chunk.file.readBytes(), Base64.NO_WRAP)
        val prompt = """
            Listen carefully to this entire audio clip and transcribe EVERY spoken line chronologically.
            Return ONLY valid JSON:
            {
              "language": "detected language name",
              "text": "full transcript text",
              "segments": [
                { "start": 0.0, "end": 3.4, "text": "exact spoken sentence" }
              ]
            }
            Use accurate seconds relative to the start of this audio clip (0.0 to ${chunk.durationMs / 1000.0}).
        """.trimIndent()

        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "input_audio")
                put("input_audio", JSONObject().apply {
                    put("data", audioB64)
                    put("format", chunk.formatExt)
                })
            })
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }

        val (jsonText, _) = executeChatCompletionsWithFallback(
            provider = provider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            initialModelId = modelId,
            messages = messages,
            temperature = 0.15,
            requireJsonObject = true,
            timeoutMs = 180_000L,
            onModelFallback = {}
        )
        return ThirdPartyModelCatalog.parseWhisperVerboseJson(jsonText)
    }

    private suspend fun transformTranscriptBatchWithChatModel(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        initialModelId: String,
        batch: List<RawTimedSpeechSegment>,
        languageMode: TranscriptLanguageMode,
        detectedLanguage: String,
        onModelFallback: (String) -> Unit
    ): Triple<List<TranscriptSegment>, String, String> {
        val inputLinesJson = JSONArray().apply {
            batch.forEach { seg ->
                put(JSONObject().apply {
                    put("index", seg.index)
                    put("startTime", TranscriptSerializer.formatClockTimestamp(seg.startMs))
                    put("endTime", TranscriptSerializer.formatClockTimestamp(seg.endMs))
                    put("text", seg.text)
                })
            }
        }

        val prompt = """
            You are a precision broadcast transcript editor and translator.
            Original detected language: $detectedLanguage.
            Target Output Mode: ${languageMode.label}.
            Directive: ${languageMode.directive}

            Process every item in INPUT_SEGMENTS below without skipping any index.
            Keep the exact "startTime" and "endTime" values unchanged, assign clean consistent "speaker" labels (e.g. Speaker 1, Speaker 2), and output "text" according to the Target Output Mode.
            Also write a brief 1-2 sentence "overallSummary".

            Return ONLY valid JSON:
            {
              "overallSummary": "...",
              "segments": [
                { "index": 1, "startTime": "00:00", "endTime": "00:04", "speaker": "Speaker 1", "text": "..." }
              ]
            }

            INPUT_SEGMENTS:
            $inputLinesJson
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val (jsonText, usedModel) = executeChatCompletionsWithFallback(
            provider = provider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            initialModelId = initialModelId,
            messages = messages,
            temperature = 0.25,
            requireJsonObject = true,
            timeoutMs = 120_000L,
            onModelFallback = onModelFallback
        )

        val root = runCatching { JSONObject(jsonText) }.getOrNull()
        val summary = root?.optString("overallSummary", "").orEmpty().trim()
        val arr = root?.optJSONArray("segments") ?: JSONArray()

        val mappedByIndex = mutableMapOf<Int, TranscriptSegment>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val idx = obj.optInt("index", -1)
            val txt = obj.optString("text", "").trim()
            if (txt.isNotBlank()) {
                val orig = batch.firstOrNull { it.index == idx } ?: batch.getOrNull(i)
                val seg = TranscriptSegment(
                    startTime = obj.optString("startTime")
                        .takeIf { it.isNotBlank() }
                        ?: TranscriptSerializer.formatClockTimestamp(orig?.startMs ?: 0L),
                    endTime = obj.optString("endTime")
                        .takeIf { it.isNotBlank() }
                        ?: TranscriptSerializer.formatClockTimestamp(orig?.endMs ?: 2500L),
                    speaker = obj.optString("speaker", "Speaker 1").ifBlank { "Speaker 1" },
                    text = txt
                )
                mappedByIndex[orig?.index ?: i] = seg
            }
        }

        val finalList = batch.map { orig ->
            mappedByIndex[orig.index] ?: TranscriptSegment(
                startTime = TranscriptSerializer.formatClockTimestamp(orig.startMs),
                endTime = TranscriptSerializer.formatClockTimestamp(orig.endMs),
                speaker = orig.speaker,
                text = orig.text
            )
        }
        return Triple(finalList, summary, usedModel)
    }

    private suspend fun translateDubBatchWithChatModel(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        initialModelId: String,
        batch: List<RawTimedSpeechSegment>,
        sourceLanguage: DubSourceLanguage,
        discoveredRoster: Set<String>,
        onModelFallback: (String) -> Unit
    ): Pair<List<DubLineItem>, String> {
        val inputJson = JSONArray().apply {
            batch.forEach { seg ->
                val durSec = ((seg.endMs - seg.startMs).coerceAtLeast(400L)) / 1000.0
                put(JSONObject().apply {
                    put("index", seg.index)
                    put("durationSec", String.format(java.util.Locale.US, "%.2f", durSec))
                    put("originalText", seg.text)
                })
            }
        }

        val rosterNote = if (discoveredRoster.isNotEmpty()) {
            "\nLOCKED CHARACTER ROSTER FROM EARLIER LINES (REUSE EXACTLY WHEN SAME PERSON SPEAKS):\n" +
                discoveredRoster.joinToString("\n") { "- \"$it\"" }
        } else ""

        val prompt = """
            You are an expert Filipino (Tagalog) Anime & Film Dubbing Script Adapter (Isochronic Lip-Sync Specialist).
            Source Language Hint: ${sourceLanguage.promptHint}.$rosterNote

            Translate every item in INPUT_LINES into natural, expressive conversational Filipino / Tagalog.
            CRITICAL RULES:
            1. MATCH SPEAKING DURATION ("durationSec"): Make "filipinoText" concise and natural so it takes roughly the same speaking time as the original line!
            2. Omit sound-effect tags like [Music] from "filipinoText".
            3. Assign a stable canonical "speaker" label per character (e.g. "Character 1 (Male)", "Character 2 (Female)", "Narrator") without temporary moods.
            4. Keep every "index" intact.

            Return ONLY valid JSON:
            {
              "lines": [
                { "index": 1, "speaker": "Character 1 (Male)", "filipinoText": "..." }
              ]
            }

            INPUT_LINES:
            $inputJson
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val (jsonText, usedModel) = executeChatCompletionsWithFallback(
            provider = provider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            initialModelId = initialModelId,
            messages = messages,
            temperature = 0.25,
            requireJsonObject = true,
            timeoutMs = 120_000L,
            onModelFallback = onModelFallback
        )

        val root = runCatching { JSONObject(jsonText) }.getOrNull()
        val arr = root?.optJSONArray("lines") ?: JSONArray()
        val outByIndex = mutableMapOf<Int, Pair<String, String>>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val idx = obj.optInt("index", -1)
            val fil = obj.optString("filipinoText", "").trim()
            val spk = DubTimelineMath.canonicalizeSpeakerName(obj.optString("speaker", "Speaker 1"))
            if (fil.isNotBlank()) {
                val resolvedIdx = if (idx > 0) idx else (batch.getOrNull(i)?.index ?: i)
                outByIndex[resolvedIdx] = spk to fil
            }
        }

        val mapped = batch.mapNotNull { orig ->
            val translated = outByIndex[orig.index]
            val filText = translated?.second?.takeIf { it.isNotBlank() } ?: orig.text
            val speaker = translated?.first ?: "Speaker 1"
            if (filText.isBlank()) null else {
                DubLineItem(
                    id = orig.index.toLong(),
                    startMs = orig.startMs,
                    endMs = orig.endMs,
                    speaker = speaker,
                    originalText = orig.text,
                    filipinoText = filText
                )
            }
        }
        return mapped to usedModel
    }

    private suspend fun executeChatCompletionsWithFallback(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        initialModelId: String,
        messages: JSONArray,
        temperature: Double,
        requireJsonObject: Boolean,
        timeoutMs: Long,
        onModelFallback: (String) -> Unit
    ): Pair<String, String> {
        var currentModel = initialModelId
        val tried = linkedSetOf<String>()
        var includeResponseFormat = requireJsonObject

        for (attempt in 0..3) {
            tried.add(currentModel)
            try {
                val payload = JSONObject().apply {
                    put("model", currentModel)
                    put("messages", messages)
                    put("temperature", temperature)
                    if (includeResponseFormat) {
                        put("response_format", JSONObject().apply { put("type", "json_object") })
                    }
                }
                val content = callChatCompletionsOnce(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    payload = payload,
                    timeoutMs = timeoutMs
                )
                return content to currentModel
            } catch (e: ThirdPartyHttpException) {
                val lower = e.message.lowercase()
                if (includeResponseFormat && e.statusCode == 400 &&
                    (lower.contains("response_format") || lower.contains("json_object"))
                ) {
                    includeResponseFormat = false
                    continue
                }
                val isModelMissing = e.statusCode == 404 ||
                    (lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist") || lower.contains("decommissioned") || lower.contains("deprecated") || lower.contains("no endpoints")))
                if (isModelMissing && attempt < 3) {
                    val verify = verifyAndDetectModels(provider, apiKey, baseUrl)
                    val chatIds = verify.allModels
                        .filter { it.capability != ModelCapabilityTag.AUDIO_STT }
                        .map { it.id }
                        .filterNot { it in tried }
                    val fallback = ThirdPartyModelCatalog.pickBestChatModel(chatIds) ?: throw e
                    currentModel = fallback
                    onModelFallback(fallback)
                    continue
                }
                throw e
            }
        }
        throw IllegalStateException("${provider.shortBadge} chat request failed on all attempted models.")
    }

    private suspend fun callChatCompletionsOnce(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        payload: JSONObject,
        timeoutMs: Long
    ): String {
        var lastError: Exception? = null
        repeat(2) { retry ->
            try {
                return withTimeout(timeoutMs) {
                    val reqBuilder = Request.Builder()
                        .url("$baseUrl/chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    applyProviderHeaders(provider, reqBuilder)

                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw ThirdPartyHttpException(
                                statusCode = resp.code,
                                message = parseThirdPartyErrorMessage(provider, raw, resp.code)
                            )
                        }
                        ThirdPartyModelCatalog.extractOpenAiChatContent(raw)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ThirdPartyHttpException) {
                if (e.statusCode in 400..499 && e.statusCode != 429) throw e
                lastError = e
                if (retry == 0) delay(1200L)
            } catch (e: Exception) {
                lastError = e
                if (retry == 0) delay(1200L)
            }
        }
        throw lastError ?: IllegalStateException("${provider.shortBadge} request failed.")
    }

    private fun shouldUseChatInputAudio(provider: AiProvider, sttModelId: String): Boolean {
        val lower = sttModelId.lowercase().trim()
        if (lower.isBlank()) {
            return provider == AiProvider.OPENROUTER
        }
        val isDedicatedWhisper = lower.contains("whisper") || lower.contains("transcribe") || lower.endsWith("-stt")
        return if (isDedicatedWhisper) {
            false
        } else {
            provider == AiProvider.OPENROUTER || lower.contains("audio") || lower.contains("gemini") || lower.contains("voxtral")
        }
    }

    private fun defaultSttForProvider(provider: AiProvider): String = when (provider) {
        AiProvider.GROQ -> "whisper-large-v3-turbo"
        AiProvider.OPENAI -> "gpt-4o-mini-transcribe"
        AiProvider.OPENROUTER -> "google/gemini-2.5-flash"
        else -> "whisper-1"
    }

    private fun defaultChatForProvider(provider: AiProvider): String = when (provider) {
        AiProvider.GROQ -> "llama-3.3-70b-versatile"
        AiProvider.OPENAI -> "gpt-4o-mini"
        AiProvider.OPENROUTER -> "google/gemini-2.5-flash"
        else -> "gpt-4o-mini"
    }

    private fun applyProviderHeaders(provider: AiProvider, builder: Request.Builder) {
        if (provider == AiProvider.OPENROUTER) {
            builder.header("HTTP-Referer", "https://gizmooh.studio.app")
            builder.header("X-Title", "Gizmooh Downloader")
        }
    }

    private fun parseThirdPartyErrorMessage(provider: AiProvider, rawBody: String, code: Int): String {
        val parsed = runCatching {
            val obj = JSONObject(rawBody)
            obj.optJSONObject("error")?.optString("message")
                ?: obj.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        return if (!parsed.isNullOrBlank()) {
            "${provider.shortBadge} API ($code): $parsed"
        } else {
            "${provider.shortBadge} API request failed (HTTP $code)."
        }
    }

    private class ThirdPartyHttpException(
        val statusCode: Int,
        override val message: String
    ) : IllegalStateException(message)
}
