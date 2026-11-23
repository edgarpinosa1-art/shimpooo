package com.example

import com.example.data.model.IngredientPrompt
import com.example.data.model.PromptBlueprint
import com.example.data.model.ScenePrompt
import com.example.data.model.SocialPlatform
import com.example.data.model.TargetVideoGenerator
import com.example.data.network.PromptBlueprintSerializer
import com.example.data.network.VideoExtractorRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelForgeCoreTest {

    @Test
    fun platformDetectionIdentifiesTikTokFacebookAndInstagramLinks() {
        assertEquals(
            SocialPlatform.TIKTOK,
            SocialPlatform.detectFromUrl("https://vt.tiktok.com/ZSYabc123/")
        )
        assertEquals(
            SocialPlatform.FACEBOOK,
            SocialPlatform.detectFromUrl("https://www.facebook.com/share/r/12345/")
        )
        assertEquals(
            SocialPlatform.FACEBOOK,
            SocialPlatform.detectFromUrl("https://fb.watch/xyz987/")
        )
        assertEquals(
            SocialPlatform.INSTAGRAM,
            SocialPlatform.detectFromUrl("https://www.instagram.com/reel/C8abcXYZ/")
        )
        assertEquals(
            SocialPlatform.UNKNOWN,
            SocialPlatform.detectFromUrl("https://example.com/video")
        )
    }

    @Test
    fun extractFirstUrlCleansSurroundingShareSheetText() {
        val shared = "Check out this viral clip! https://vt.tiktok.com/ZSY12345/ #fyp"
        assertEquals("https://vt.tiktok.com/ZSY12345/", VideoExtractorRepository.extractFirstUrl(shared))
        assertNull(VideoExtractorRepository.extractFirstUrl("no link here"))
    }

    @Test
    fun promptBlueprintFormatsFullCopyBundleCleanly() {
        val original = PromptBlueprint(
            id = 42L,
            videoTitle = "Neon Street Cyber Cat",
            sourceUriString = "content://media/video/1",
            targetGenerator = TargetVideoGenerator.VEO_3,
            geminiModelUsed = "gemini-3.8-flash",
            whatHappenedSummary = "A cybernetic cat leaps across rain-slicked neon rooftops.",
            originalPromptGuess = "Cyberpunk cat jumping on rooftop at night",
            visualStyle = "35mm anamorphic neo-noir",
            cameraAndLens = "Low-angle tracking shot, 35mm f/1.8",
            lightingAndPalette = "Electric cyan and coral neon rim lights",
            audioAndPacing = "Fast 120bpm synth pulse with rain ambience",
            ingredients = listOf(
                IngredientPrompt(
                    name = "Cyber Cat Hero",
                    role = "Character",
                    visualDetails = "Sleek black fur with glowing cyan visor",
                    imagePrompt = "Studio turnaround sheet of a sleek black cat wearing a glowing cyan visor",
                    negativePrompt = "blurry, deformed paws"
                )
            ),
            masterVideoPrompt = "Low-angle 35mm tracking shot of a cybernetic black cat leaping across wet neon rooftops...",
            negativeVideoPrompt = "watermark, flicker, extra limbs",
            scenes = listOf(
                ScenePrompt(
                    timeRange = "00:00 - 00:03",
                    shotType = "Low-Angle Tracking",
                    cameraMovement = "Fast lateral dolly",
                    actionDescription = "Cat sprints and leaps over puddle",
                    scenePrompt = "Fast lateral dolly following cyber cat..."
                )
            ),
            recreationTips = "1. Generate Cyber Cat Hero image first. 2. Attach as Ingredient in Veo 3."
        )

        val bundle = PromptBlueprintSerializer.formatFullCopyBundle(original)
        org.junit.Assert.assertTrue(bundle.contains("MASTER AI VIDEO PROMPT"))
        org.junit.Assert.assertTrue(bundle.contains("Cyber Cat Hero"))
        org.junit.Assert.assertTrue(bundle.contains("00:00 - 00:03"))
        org.junit.Assert.assertTrue(bundle.contains(original.masterVideoPrompt))
    }

    @Test
    fun promptStudioAspectRatioAndUpgradePresetsAreWellFormed() {
        val ratios = com.example.data.model.VideoAspectRatio.entries
        val presets = com.example.data.model.PromptUpgradePreset.entries
        org.junit.Assert.assertTrue(ratios.any { it.label.contains("9:16") && it.directive.isNotBlank() })
        org.junit.Assert.assertTrue(presets.all { it.label.isNotBlank() && it.directive.isNotBlank() })
    }
}
