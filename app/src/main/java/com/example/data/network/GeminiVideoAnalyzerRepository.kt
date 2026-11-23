package com.example.data.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.model.LocalVideoInspection
import com.example.data.model.PromptBlueprint
import com.example.data.model.PromptUpgradePreset
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.VideoAspectRatio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val TAG = "GeminiVideoAnalyzer"
private const val GEMINI_BASE = "https://generativelanguage.googleapis.com"

data class KeyVerificationResult(
    val isValid: Boolean,
    val message: String,
    val availableModels: List<Pair<String, String>> = emptyList(),
    val recommendedModelId: String? = null
)

class GeminiVideoAnalyzerRepository(private val context: Context) {

    private val client = NetworkClient.httpClient

    suspend fun verifyApiKey(apiKey: String): KeyVerificationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext KeyVerificationResult(false, "API key cannot be empty.")
        }
        try {
            val req = Request.Builder()
                .url("$GEMINI_BASE/v1beta/models?key=$cleanKey")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val modelsArray = runCatching {
                        JSONObject(body).optJSONArray("models") ?: JSONArray()
                    }.getOrDefault(JSONArray())

                    val usableModels = mutableListOf<Pair<String, String>>()
                    for (i in 0 until modelsArray.length()) {
                        val obj = modelsArray.optJSONObject(i) ?: continue
                        val rawName = obj.optString("name").removePrefix("models/")
                        val displayName = obj.optString("displayName", rawName)
                        val methods = obj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        val supportsGenerate = (0 until methods.length()).any {
                            methods.optString(it) == "generateContent"
                        }
                        val lower = rawName.lowercase()
                        val isVideoCapableGemini = supportsGenerate &&
                            lower.startsWith("gemini-") &&
                            !lower.contains("embedding") &&
                            !lower.contains("tts") &&
                            !lower.contains("image") &&
                            !lower.contains("robotics") &&
                            !lower.contains("computer-use")
                        if (isVideoCapableGemini) {
                            usableModels.add(rawName to displayName)
                        }
                    }

                    val preferredOrder = listOf(
                        "gemini-3.8-flash",
                        "gemini-3.6-flash",
                        "gemini-3-flash",
                        "gemini-4-hydrogen",
                        "gemini-2.5-flash",
                        "gemini-3.5-flash-lite",
                        "gemini-2.5-pro"
                    )
                    val recommended = preferredOrder.firstOrNull { pref ->
                        usableModels.any { it.first == pref }
                    } ?: usableModels.firstOrNull { it.first.contains("flash") }?.first
                        ?: usableModels.firstOrNull()?.first

                    KeyVerificationResult(
                        isValid = true,
                        message = "Connected! ${usableModels.size} Gemini models available on your key.",
                        availableModels = usableModels,
                        recommendedModelId = recommended
                    )
                } else {
                    val errMsg = parseGeminiErrorMessage(body, resp.code)
                    KeyVerificationResult(false, errMsg)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "API key check failed", e)
            KeyVerificationResult(false, e.localizedMessage ?: "Network error verifying key.")
        }
    }

    suspend fun analyzeVideoForPrompts(
        apiKey: String,
        modelId: String,
        inspection: LocalVideoInspection,
        targetGenerator: TargetVideoGenerator,
        aspectRatio: VideoAspectRatio,
        upgradePreset: PromptUpgradePreset,
        extraCreatorNotes: String,
        onStageUpdate: (String) -> Unit
    ): Result<PromptBlueprint> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Ilagay muna ang iyong Gemini API key bago mag-analyze.")
            )
        }

        val tempVideoFile = File(context.cacheDir, "gemini_upload_${System.currentTimeMillis()}.mp4")
        var uploadedFileName: String? = null

        try {
            onStageUpdate("Preparing video file...")
            copyUriToFile(Uri.parse(inspection.uriString), tempVideoFile)

            val fileSizeBytes = tempVideoFile.length()
            val mimeType = inspection.mimeType.ifBlank { "video/mp4" }

            // 1. Try Gemini Files API resumable upload for full video + audio analysis
            onStageUpdate("Uploading full video to Gemini Files API...")
            val uploaded = uploadViaFilesApi(cleanKey, tempVideoFile, mimeType, inspection.displayName)

            val contentParts = JSONArray()
            var attachedVideoStream = false
            if (uploaded != null) {
                uploadedFileName = uploaded.first
                onStageUpdate("Processing video timeline on Gemini...")
                waitUntilFileActive(cleanKey, uploaded.first)
                contentParts.put(JSONObject().apply {
                    put("file_data", JSONObject().apply {
                        put("file_uri", uploaded.second)
                        put("mime_type", mimeType)
                    })
                })
                attachedVideoStream = true
            } else if (fileSizeBytes in 1L..(14L * 1024L * 1024L)) {
                // Fallback inline video bytes if Files API failed on a small clip
                onStageUpdate("Encoding video inline for Gemini...")
                val base64Video = Base64.encodeToString(tempVideoFile.readBytes(), Base64.NO_WRAP)
                contentParts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", base64Video)
                    })
                })
                attachedVideoStream = true
            }

            // Attach 4 keyframes alongside full video, or all 6 if video was too large for inline fallback
            val frameLimit = if (attachedVideoStream) 4 else 6
            inspection.keyframePaths.take(frameLimit).forEach { framePath ->
                val frameFile = File(framePath)
                if (frameFile.exists() && frameFile.length() > 0) {
                    val frameB64 = Base64.encodeToString(frameFile.readBytes(), Base64.NO_WRAP)
                    contentParts.put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", frameB64)
                        })
                    })
                }
            }

            if (contentParts.length() == 0) {
                return@withContext Result.failure(
                    IllegalStateException("Video upload failed and no frames could be attached.")
                )
            }

            val instructionPrompt = buildPromptEngineeringInstruction(
                targetGenerator = targetGenerator,
                aspectRatio = aspectRatio,
                upgradePreset = upgradePreset,
                durationMs = inspection.durationMs,
                width = inspection.width,
                height = inspection.height,
                extraNotes = extraCreatorNotes
            )
            contentParts.put(JSONObject().apply { put("text", instructionPrompt) })

            onStageUpdate("Reverse-engineering ingredients & crafting 100% enhanced video prompt...")

            val requestPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", contentParts)
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.45)
                    put("responseMimeType", "application/json")
                    put("responseSchema", buildResponseSchema())
                })
            }

            val responseJsonText = withTimeout(115_000L) {
                val url = "$GEMINI_BASE/v1beta/models/${modelId.trim()}:generateContent?key=$cleanKey"
                val req = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .post(requestPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseGeminiErrorMessage(rawBody, resp.code))
                    }
                    extractTextFromGeminiResponse(rawBody)
                }
            }

            val blueprint = PromptBlueprintSerializer.fromGeminiOrStoredJson(
                jsonString = responseJsonText,
                videoTitle = inspection.displayName,
                sourceUriString = inspection.uriString,
                targetGenerator = targetGenerator,
                geminiModelUsed = modelId
            )

            Result.success(blueprint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini analysis failed", e)
            Result.failure(e)
        } finally {
            runCatching { tempVideoFile.delete() }
            if (!uploadedFileName.isNullOrBlank()) {
                runCatching { deleteGeminiFile(cleanKey, uploadedFileName) }
            }
        }
    }

    private fun copyUriToFile(uri: Uri, destFile: File) {
        if (uri.scheme == "file") {
            val src = File(uri.path ?: "")
            if (src.exists()) {
                src.inputStream().use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                return
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot read selected video file.")
    }

    private fun uploadViaFilesApi(
        apiKey: String,
        file: File,
        mimeType: String,
        displayName: String
    ): Pair<String, String>? {
        return runCatching {
            val numBytes = file.length()
            val startBody = JSONObject().apply {
                put("file", JSONObject().apply {
                    put("display_name", displayName.take(64))
                })
            }.toString()

            val startReq = Request.Builder()
                .url("$GEMINI_BASE/upload/v1beta/files?key=$apiKey")
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", numBytes.toString())
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .header("Content-Type", "application/json")
                .post(startBody.toRequestBody("application/json".toMediaType()))
                .build()

            val uploadUrl = client.newCall(startReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Files API start failed HTTP ${resp.code}")
                    return null
                }
                resp.header("X-Goog-Upload-URL") ?: resp.header("x-goog-upload-url")
            } ?: return null

            val fileBody: RequestBody = file.asRequestBody(mimeType.toMediaType())
            val finalizeReq = Request.Builder()
                .url(uploadUrl)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .post(fileBody)
                .build()

            client.newCall(finalizeReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Files API upload failed HTTP ${resp.code}")
                    return null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val fileObj = json.optJSONObject("file") ?: return null
                val name = fileObj.optString("name")
                val uri = fileObj.optString("uri")
                if (name.isNotBlank() && uri.isNotBlank()) name to uri else null
            }
        }.getOrNull()
    }

    private suspend fun waitUntilFileActive(apiKey: String, fileName: String) {
        repeat(24) {
            val req = Request.Builder()
                .url("$GEMINI_BASE/v1beta/$fileName?key=$apiKey")
                .get()
                .build()
            val state = client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optString("state", "ACTIVE")
            }
            if (state == "ACTIVE") return
            if (state == "FAILED") {
                throw IllegalStateException("Gemini video processing failed on server.")
            }
            delay(1500L)
        }
    }

    private fun deleteGeminiFile(apiKey: String, fileName: String) {
        val req = Request.Builder()
            .url("$GEMINI_BASE/v1beta/$fileName?key=$apiKey")
            .delete()
            .build()
        client.newCall(req).execute().close()
    }

    private fun extractTextFromGeminiResponse(rawBody: String): String {
        val root = JSONObject(rawBody)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini returned no candidates.")
        val first = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Empty Gemini candidate.")
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("No content parts returned.")

        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text")
            if (!text.isNullOrBlank()) return text
        }
        throw IllegalStateException("Model returned empty text.")
    }

    private fun parseGeminiErrorMessage(rawBody: String, statusCode: Int): String {
        val parsed = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return if (!parsed.isNullOrBlank()) {
            "Gemini API ($statusCode): $parsed"
        } else {
            "Gemini API request failed (HTTP $statusCode)."
        }
    }

    private fun buildPromptEngineeringInstruction(
        targetGenerator: TargetVideoGenerator,
        aspectRatio: VideoAspectRatio,
        upgradePreset: PromptUpgradePreset,
        durationMs: Long,
        width: Int,
        height: Int,
        extraNotes: String
    ): String {
        val durationSec = (durationMs / 1000.0)
        val aspectHint = if (width > 0 && height > 0) "${width}x${height}" else "vertical/social"
        val userNoteSection = if (extraNotes.isNotBlank()) {
            "\nAdditional user goal/context: $extraNotes"
        } else ""

        return """
            You are an elite AI Cinematographer, Prompt Reverse-Engineer, and VFX Director.
            Analyze the attached video (and its keyframes) thoroughly from start to finish.
            Source video specs: ${String.format(java.util.Locale.US, "%.1f", durationSec)}s, resolution $aspectHint.
            Target AI Video Generator: ${targetGenerator.displayName}.
            Generator guidance: ${targetGenerator.promptGuidance}
            Target Output Framing: ${aspectRatio.label} — ${aspectRatio.directive}
            Creative Mode: ${upgradePreset.label} — ${upgradePreset.directive}$userNoteSection

            Your mission:
            1. Reverse-engineer EVERYTHING happening in the video: subjects, exact appearance/clothing, props, environment, lighting, lens/camera motion, transitions, physics, pacing, audio mood, and likely original prompt used if it was AI-generated (or exact recreation breakdown if filmed/edited).
            2. Produce "ingredients": modular, ultra-detailed IMAGE GENERATION PROMPTS (ready for Nano Banana / Imagen / Flux / Midjourney) for every visual ingredient (e.g., Hero Character/Subject Sheet, Environment Background Plate, Key Product/Prop, Outfit/Texture) so the user can first generate clean consistent reference images ("Ingredients to Video").
            3. Produce "masterVideoPrompt": an upgraded, 100% better cinematic AI VIDEO PROMPT tailored for ${targetGenerator.shortLabel}. Make it dramatically sharper, better lit, smoother in camera choreography, physically grounded, and production-ready—fixing any flaws, blur, or generic framing from the original clip while keeping 100% of the viral concept.
            4. Produce "scenes": chronological scene-by-scene breakdown with timestamps, shot type, camera move, and individual scene prompts.
            5. Produce "recreationTips": clear step-by-step advice (concise, easy to follow in English + friendly Tagalog-English clarity) explaining how to combine the Ingredient Image Prompts + Master Video Prompt for 100% peak quality output.

            Write all imagePrompt, masterVideoPrompt, negativePrompt, and scenePrompt values in clean, descriptive English (best compatibility for AI generators). Keep prompts vivid, concrete, and free of buzzword fluff like "8k masterpiece" unless paired with real lens/lighting/material physics details.
        """.trimIndent()
    }

    private fun buildResponseSchema(): JSONObject {
        fun str(desc: String) = JSONObject().apply {
            put("type", "STRING")
            put("description", desc)
        }

        val ingredientSchema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("name", str("Short label of this visual ingredient, e.g. 'Hero Subject Portrait' or 'Cyberpunk Street Plate'"))
                put("role", str("Category role: Character, Environment, Prop, Outfit, or Lighting Style"))
                put("visualDetails", str("Key observed traits from the video"))
                put("imagePrompt", str("Complete ready-to-paste AI image generator prompt on clean neutral or contextual framing to create this ingredient reference asset"))
                put("negativePrompt", str("Comma-separated negative prompt terms to avoid artifacts"))
            })
            put("required", JSONArray(listOf("name", "role", "visualDetails", "imagePrompt", "negativePrompt")))
        }

        val sceneSchema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("timeRange", str("Timestamp span e.g. 00:00 - 00:03"))
                put("shotType", str("Shot framing e.g. Extreme Close-Up, Medium Tracking Shot, Low-Angle Wide"))
                put("cameraMovement", str("Exact camera motion e.g. Slow dolly-in with subtle handheld micro-shake"))
                put("actionDescription", str("What happens visually in this beat"))
                put("scenePrompt", str("Dedicated AI video prompt for regenerating this specific shot"))
            })
            put("required", JSONArray(listOf("timeRange", "shotType", "cameraMovement", "actionDescription", "scenePrompt")))
        }

        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("whatHappenedSummary", str("Concise breakdown of the video concept, hook, and visual execution"))
                put("originalPromptGuess", str("Reverse-engineered prompt representing what was likely used or how the original video was constructed"))
                put("visualStyle", str("Overall aesthetic, film stock / render engine look, textures"))
                put("cameraAndLens", str("Focal length, depth of field, shutter feel, camera choreography"))
                put("lightingAndPalette", str("Key light, rim light, shadows, color grading palette"))
                put("audioAndPacing", str("Rhythm, cut speed, sound design / ambient audio cues"))
                put("ingredients", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", ingredientSchema)
                    put("description", "2 to 5 essential ingredient image prompts to recreate consistent subjects, props, and environments")
                })
                put("masterVideoPrompt", str("The 100% enhanced, production-grade AI video prompt tailored for the selected generator"))
                put("negativeVideoPrompt", str("Negative prompt to prevent flicker, morphing, watermarks, or artifacts"))
                put("scenes", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", sceneSchema)
                    put("description", "Chronological shot-by-shot breakdown")
                })
                put("recreationTips", str("Actionable step-by-step workflow instructions to recreate and elevate this video 100%"))
            })
            put(
                "required",
                JSONArray(
                    listOf(
                        "whatHappenedSummary",
                        "originalPromptGuess",
                        "visualStyle",
                        "cameraAndLens",
                        "lightingAndPalette",
                        "audioAndPacing",
                        "ingredients",
                        "masterVideoPrompt",
                        "negativeVideoPrompt",
                        "scenes",
                        "recreationTips"
                    )
                )
            )
        }
    }
}
