package com.example.data.network

import com.example.data.model.IngredientPrompt
import com.example.data.model.PromptBlueprint
import com.example.data.model.ScenePrompt
import com.example.data.model.TargetVideoGenerator
import org.json.JSONArray
import org.json.JSONObject

object PromptBlueprintSerializer {

    fun toJson(blueprint: PromptBlueprint): String {
        return JSONObject().apply {
            put("videoTitle", blueprint.videoTitle)
            put("sourceUriString", blueprint.sourceUriString)
            put("targetGenerator", blueprint.targetGenerator.name)
            put("geminiModelUsed", blueprint.geminiModelUsed)
            put("whatHappenedSummary", blueprint.whatHappenedSummary)
            put("originalPromptGuess", blueprint.originalPromptGuess)
            put("visualStyle", blueprint.visualStyle)
            put("cameraAndLens", blueprint.cameraAndLens)
            put("lightingAndPalette", blueprint.lightingAndPalette)
            put("audioAndPacing", blueprint.audioAndPacing)
            put("masterVideoPrompt", blueprint.masterVideoPrompt)
            put("negativeVideoPrompt", blueprint.negativeVideoPrompt)
            put("recreationTips", blueprint.recreationTips)
            put("createdAt", blueprint.createdAt)
            put("ingredients", JSONArray().apply {
                blueprint.ingredients.forEach { ing ->
                    put(JSONObject().apply {
                        put("name", ing.name)
                        put("role", ing.role)
                        put("visualDetails", ing.visualDetails)
                        put("imagePrompt", ing.imagePrompt)
                        put("negativePrompt", ing.negativePrompt)
                    })
                }
            })
            put("scenes", JSONArray().apply {
                blueprint.scenes.forEach { scene ->
                    put(JSONObject().apply {
                        put("timeRange", scene.timeRange)
                        put("shotType", scene.shotType)
                        put("cameraMovement", scene.cameraMovement)
                        put("actionDescription", scene.actionDescription)
                        put("scenePrompt", scene.scenePrompt)
                    })
                }
            })
        }.toString()
    }

    fun fromGeminiOrStoredJson(
        jsonString: String,
        id: Long = 0L,
        videoTitle: String,
        sourceUriString: String,
        targetGenerator: TargetVideoGenerator,
        geminiModelUsed: String,
        createdAt: Long = System.currentTimeMillis()
    ): PromptBlueprint {
        val json = JSONObject(jsonString)

        val ingredientsArray = json.optJSONArray("ingredients") ?: JSONArray()
        val ingredients = buildList {
            for (i in 0 until ingredientsArray.length()) {
                val obj = ingredientsArray.optJSONObject(i) ?: continue
                add(
                    IngredientPrompt(
                        name = obj.optString("name", "Ingredient ${i + 1}"),
                        role = obj.optString("role", "Visual Element"),
                        visualDetails = obj.optString("visualDetails", ""),
                        imagePrompt = obj.optString("imagePrompt", ""),
                        negativePrompt = obj.optString("negativePrompt", "blurry, low quality, watermark, text, distorted anatomy")
                    )
                )
            }
        }

        val scenesArray = json.optJSONArray("scenes") ?: JSONArray()
        val scenes = buildList {
            for (i in 0 until scenesArray.length()) {
                val obj = scenesArray.optJSONObject(i) ?: continue
                add(
                    ScenePrompt(
                        timeRange = obj.optString("timeRange", "00:00 - 00:05"),
                        shotType = obj.optString("shotType", "Medium Shot"),
                        cameraMovement = obj.optString("cameraMovement", "Smooth tracking"),
                        actionDescription = obj.optString("actionDescription", ""),
                        scenePrompt = obj.optString("scenePrompt", "")
                    )
                )
            }
        }

        val resolvedGen = TargetVideoGenerator.entries.firstOrNull {
            it.name == json.optString("targetGenerator", targetGenerator.name)
        } ?: targetGenerator

        return PromptBlueprint(
            id = id,
            videoTitle = json.optString("videoTitle", videoTitle).ifBlank { videoTitle },
            sourceUriString = json.optString("sourceUriString", sourceUriString).ifBlank { sourceUriString },
            targetGenerator = resolvedGen,
            geminiModelUsed = json.optString("geminiModelUsed", geminiModelUsed).ifBlank { geminiModelUsed },
            whatHappenedSummary = json.optString("whatHappenedSummary", ""),
            originalPromptGuess = json.optString("originalPromptGuess", ""),
            visualStyle = json.optString("visualStyle", ""),
            cameraAndLens = json.optString("cameraAndLens", ""),
            lightingAndPalette = json.optString("lightingAndPalette", ""),
            audioAndPacing = json.optString("audioAndPacing", ""),
            ingredients = ingredients,
            masterVideoPrompt = json.optString("masterVideoPrompt", ""),
            negativeVideoPrompt = json.optString("negativeVideoPrompt", ""),
            scenes = scenes,
            recreationTips = json.optString("recreationTips", ""),
            createdAt = json.optLong("createdAt", createdAt)
        )
    }

    fun formatFullCopyBundle(blueprint: PromptBlueprint): String {
        return buildString {
            appendLine("=== MASTER AI VIDEO PROMPT (${blueprint.targetGenerator.shortLabel}) ===")
            appendLine(blueprint.masterVideoPrompt)
            if (blueprint.negativeVideoPrompt.isNotBlank()) {
                appendLine()
                appendLine("Negative Prompt: ${blueprint.negativeVideoPrompt}")
            }
            if (blueprint.ingredients.isNotEmpty()) {
                appendLine()
                appendLine("=== INGREDIENT IMAGE PROMPTS ===")
                blueprint.ingredients.forEachIndexed { idx, ing ->
                    appendLine("${idx + 1}. [${ing.role}] ${ing.name}")
                    appendLine("Image Prompt: ${ing.imagePrompt}")
                    if (ing.negativePrompt.isNotBlank()) {
                        appendLine("Negative: ${ing.negativePrompt}")
                    }
                    appendLine()
                }
            }
            if (blueprint.scenes.isNotEmpty()) {
                appendLine("=== SCENE-BY-SCENE PROMPTS ===")
                blueprint.scenes.forEach { scene ->
                    appendLine("[${scene.timeRange}] ${scene.shotType} · ${scene.cameraMovement}")
                    appendLine(scene.scenePrompt)
                    appendLine()
                }
            }
            appendLine("=== REVERSE-ENGINEERED ORIGINAL PROMPT ===")
            appendLine(blueprint.originalPromptGuess)
        }.trim()
    }
}
