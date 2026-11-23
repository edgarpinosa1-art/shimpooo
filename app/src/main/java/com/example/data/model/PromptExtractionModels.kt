package com.example.data.model

enum class TargetVideoGenerator(
    val displayName: String,
    val shortLabel: String,
    val promptGuidance: String
) {
    UNIVERSAL(
        displayName = "Universal Cinematic (Best All-Around)",
        shortLabel = "Universal",
        promptGuidance = "Balanced cinematic prompt compatible with Veo, Sora, Kling, and Runway with rich subject, motion, lens, lighting, and atmosphere directives."
    ),
    VEO_3(
        displayName = "Google Veo 3 / Ingredients-to-Video",
        shortLabel = "Veo 3",
        promptGuidance = "Tailored for Google Veo 3 with clear reference ingredient anchors, realistic physics, ambient sound cues, camera focal length, and continuous shot pacing."
    ),
    SORA_2(
        displayName = "OpenAI Sora 2",
        shortLabel = "Sora 2",
        promptGuidance = "Tailored for Sora 2 with rich narrative spatial choreography, material textures, temporal continuity, and dynamic lighting transitions."
    ),
    KLING_2(
        displayName = "Kling AI (Elements & Motion)",
        shortLabel = "Kling AI",
        promptGuidance = "Tailored for Kling AI Elements/Ingredients workflow with explicit subject consistency tags, clear motion vectors, and smooth camera orbit/dolly instructions."
    ),
    RUNWAY_GEN4(
        displayName = "Runway Gen-4",
        shortLabel = "Runway",
        promptGuidance = "Tailored for Runway Gen-4 with concise subject-action-camera phrasing, lens blur, shutter speed feel, and color grading style."
    ),
    HAILUO_MINIMAX(
        displayName = "Hailuo / MiniMax",
        shortLabel = "Hailuo",
        promptGuidance = "Tailored for Hailuo AI with vivid expressive character action, dynamic camera motion, and crisp micro-detail descriptions."
    )
}

enum class VideoAspectRatio(
    val label: String,
    val directive: String
) {
    PORTRAIT_9_16("9:16 Reels", "Vertical 9:16 portrait framing optimized for TikTok, Reels, and Shorts."),
    LANDSCAPE_16_9("16:9 Cinema", "Widescreen 16:9 cinematic horizontal framing."),
    SQUARE_1_1("1:1 Square", "Centered 1:1 square social framing.")
}

enum class PromptUpgradePreset(
    val label: String,
    val directive: String
) {
    CINEMATIC_UPGRADE(
        "100% Cinematic Upgrade",
        "Preserve the exact viral hook and subject, but elevate camera motion, volumetric lighting, lens bokeh, textures, and motion physics to high-budget cinema quality."
    ),
    EXACT_RECREATION(
        "Exact Match",
        "Stay 100% faithful to the original clip's composition, wardrobe, camera angles, lighting, and timing for an accurate recreation."
    ),
    HYPER_COMMERCIAL(
        "Ad Commercial",
        "Transform into a glossy, high-converting luxury studio commercial with crisp macro product shots, rim lighting, and sleek transitions."
    ),
    STYLIZED_ANIME_3D(
        "3D / Stylized",
        "Reimagine the exact action and choreography in vibrant, richly textured stylized 3D animation / anime cinema aesthetics."
    )
}

data class IngredientPrompt(
    val name: String,
    val role: String,
    val visualDetails: String,
    val imagePrompt: String,
    val negativePrompt: String
)

data class ScenePrompt(
    val timeRange: String,
    val shotType: String,
    val cameraMovement: String,
    val actionDescription: String,
    val scenePrompt: String
)

data class PromptBlueprint(
    val id: Long = 0L,
    val videoTitle: String,
    val sourceUriString: String,
    val targetGenerator: TargetVideoGenerator,
    val geminiModelUsed: String,
    val whatHappenedSummary: String,
    val originalPromptGuess: String,
    val visualStyle: String,
    val cameraAndLens: String,
    val lightingAndPalette: String,
    val audioAndPacing: String,
    val ingredients: List<IngredientPrompt>,
    val masterVideoPrompt: String,
    val negativeVideoPrompt: String,
    val scenes: List<ScenePrompt>,
    val recreationTips: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class LocalVideoInspection(
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val mimeType: String,
    val keyframePaths: List<String>
)
