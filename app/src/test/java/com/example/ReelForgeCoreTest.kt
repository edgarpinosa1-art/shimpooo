package com.example

import com.example.data.model.DubLineItem
import com.example.data.model.DubVoiceOption
import com.example.data.model.IngredientPrompt
import com.example.data.model.PromptBlueprint
import com.example.data.model.ScenePrompt
import com.example.data.model.SocialPlatform
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.TranscriptLanguageMode
import com.example.data.model.TranscriptSegment
import com.example.data.model.VideoTranscript
import com.example.data.network.PinterestExtractorHelper
import com.example.data.network.PromptBlueprintSerializer
import com.example.data.network.TranscriptSerializer
import com.example.data.network.VideoExtractorRepository
import com.example.data.dubbing.DubTimelineMath
import com.example.data.dubbing.MmsTagalogOnnxEngine
import com.example.data.dubbing.TimedPcmClip
import com.example.data.dubbing.WavPcmTools
import com.example.data.model.AiProvider
import com.example.data.model.ModelCapabilityTag
import com.example.data.model.ThirdPartyModelCatalog
import com.example.data.network.FastDownloadPlanner
import com.example.data.network.ThirdPartyTranscriptHelper
import com.example.data.network.YouTubeExtractorHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

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
            SocialPlatform.YOUTUBE,
            SocialPlatform.detectFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            SocialPlatform.YOUTUBE,
            SocialPlatform.detectFromUrl("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            SocialPlatform.YOUTUBE,
            SocialPlatform.detectFromUrl("https://youtube.com/shorts/abcDEF12345")
        )
        assertEquals(
            SocialPlatform.PINTEREST,
            SocialPlatform.detectFromUrl("https://www.pinterest.com/pin/123456789012345/")
        )
        assertEquals(
            SocialPlatform.PINTEREST,
            SocialPlatform.detectFromUrl("https://ph.pinterest.com/pin/987654321/")
        )
        assertEquals(
            SocialPlatform.PINTEREST,
            SocialPlatform.detectFromUrl("https://pin.it/4abcXYZ")
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
        assertTrue(ratios.any { it.label.contains("9:16") && it.directive.isNotBlank() })
        assertTrue(presets.all { it.label.isNotBlank() && it.directive.isNotBlank() })
    }

    @Test
    fun youtubeVideoIdExtractionHandlesWatchShortsAndShortLinks() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeExtractorHelper.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12s")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeExtractorHelper.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=xyz")
        )
        assertEquals(
            "abcDEF12345",
            YouTubeExtractorHelper.extractYouTubeVideoId("https://youtube.com/shorts/abcDEF12345")
        )
        assertEquals(
            "live9876543",
            YouTubeExtractorHelper.extractYouTubeVideoId("https://www.youtube.com/live/live9876543")
        )
        assertNull(YouTubeExtractorHelper.extractYouTubeVideoId("https://youtube.com/@creator"))
    }

    @Test
    fun transcriptFormatsTimestampedTextAndValidSrtSubtitles() {
        val transcript = VideoTranscript(
            videoTitle = "Taglish Tech Review",
            sourceUriString = "content://media/video/9",
            geminiModelUsed = "gemini-3.8-flash",
            languageMode = TranscriptLanguageMode.ORIGINAL_VERBATIM,
            detectedLanguage = "Taglish",
            overallSummary = "Quick smartphone camera test.",
            segments = listOf(
                TranscriptSegment("00:00", "00:04", "Host", "Guys, tingnan natin yung night mode nito."),
                TranscriptSegment("00:04", "01:05", "Host", "Sobrang linaw kahit madilim yung paligid.")
            ),
            fullPlainTranscript = "Guys, tingnan natin yung night mode nito. Sobrang linaw kahit madilim yung paligid."
        )

        val formatted = TranscriptSerializer.formatTimestampedText(transcript)
        assertTrue(formatted.contains("[00:00 - 00:04] Host: Guys, tingnan natin yung night mode nito."))
        assertTrue(formatted.contains("[00:04 - 01:05] Host:"))

        val srt = TranscriptSerializer.formatSrtSubtitles(transcript.segments)
        assertTrue(srt.contains("00:00:00,000 --> 00:00:04,000"))
        assertTrue(srt.contains("00:00:04,000 --> 00:01:05,000"))
    }

    @Test
    fun transcriptTimeWindowsAndClockFormattingHandleShortAndLongVideos() {
        assertEquals("00:00", TranscriptSerializer.formatClockTimestamp(0L))
        assertEquals("06:00", TranscriptSerializer.formatClockTimestamp(6L * 60L * 1000L))
        assertEquals("18:00", TranscriptSerializer.formatClockTimestamp(18L * 60L * 1000L))
        assertEquals("01:05:00", TranscriptSerializer.formatClockTimestamp((65L * 60L) * 1000L))

        // Short/medium video <= 8 minutes runs in a single pass
        val sevenMinMs = 7L * 60L * 1000L
        assertEquals(listOf(0L to sevenMinMs), TranscriptSerializer.buildTimeWindows(sevenMinMs))

        // 20-minute video splits into 4 windows: 0-6, 6-12, 12-18, 18-20
        val twentyMinMs = 20L * 60L * 1000L
        val windows = TranscriptSerializer.buildTimeWindows(twentyMinMs)
        assertEquals(4, windows.size)
        assertEquals(0L to 6L * 60L * 1000L, windows[0])
        assertEquals(6L * 60L * 1000L to 12L * 60L * 1000L, windows[1])
        assertEquals(12L * 60L * 1000L to 18L * 60L * 1000L, windows[2])
        assertEquals(18L * 60L * 1000L to twentyMinMs, windows[3])
    }

    @Test
    fun transcriptMergeChunksDeduplicatesBoundaryOverlapsAndJoinsText() {
        val part1 = VideoTranscript(
            videoTitle = "Long Podcast",
            sourceUriString = "content://media/video/10",
            geminiModelUsed = "gemini-2.5-flash",
            languageMode = TranscriptLanguageMode.TAGALOG_TAGLISH,
            detectedLanguage = "Taglish",
            overallSummary = "Opening discussion.",
            segments = listOf(
                TranscriptSegment("00:00", "00:05", "Host", "Welcome back sa episode."),
                TranscriptSegment("05:55", "06:00", "Host", "Pag-usapan natin ang AI.")
            ),
            fullPlainTranscript = "Welcome back sa episode. Pag-usapan natin ang AI."
        )
        val part2 = VideoTranscript(
            videoTitle = "Long Podcast",
            sourceUriString = "content://media/video/10",
            geminiModelUsed = "gemini-2.5-flash",
            languageMode = TranscriptLanguageMode.TAGALOG_TAGLISH,
            detectedLanguage = "Taglish",
            overallSummary = "Deep dive on workflow.",
            segments = listOf(
                TranscriptSegment("05:55", "06:00", "Host", "Pag-usapan natin ang AI."),
                TranscriptSegment("06:00", "06:10", "Guest", "Una, piliin ang tamang model.")
            ),
            fullPlainTranscript = "Pag-usapan natin ang AI. Una, piliin ang tamang model."
        )

        val merged = TranscriptSerializer.mergeChunks(
            chunks = listOf(part1, part2),
            videoTitle = "Long Podcast",
            sourceUriString = "content://media/video/10",
            geminiModelUsed = "gemini-2.5-flash",
            languageMode = TranscriptLanguageMode.TAGALOG_TAGLISH
        )

        assertEquals(3, merged.segments.size)
        assertEquals("00:00", merged.segments[0].startTime)
        assertEquals("05:55", merged.segments[1].startTime)
        assertEquals("06:00", merged.segments[2].startTime)
        assertEquals(
            "Welcome back sa episode. Pag-usapan natin ang AI. Una, piliin ang tamang model.",
            merged.fullPlainTranscript
        )
        assertTrue(merged.overallSummary.contains("Opening discussion."))
        assertTrue(merged.overallSummary.contains("Deep dive on workflow."))
    }

    @Test
    fun pinterestPinIdAndJsonExtractionHandleStandardAndIdeaPins() {
        assertEquals(
            "123456789012345",
            PinterestExtractorHelper.extractPinId("https://www.pinterest.com/pin/123456789012345/")
        )
        assertEquals(
            "9876543210",
            PinterestExtractorHelper.extractPinId("https://ph.pinterest.com/pin/aesthetic-desk-setup--9876543210/")
        )
        assertNull(PinterestExtractorHelper.extractPinId("https://pin.it/abc123"))

        val standardPinJson = """
            {
              "resource_response": {
                "data": {
                  "grid_title": "Cozy Minimalist Desk Tour",
                  "pinner": { "full_name": "Studio Setup", "username": "studiosetup" },
                  "images": { "orig": { "url": "https://i.pinimg.com/originals/aa/bb/cc.jpg" } },
                  "videos": {
                    "video_list": {
                      "V_360P": { "url": "https://v1.pinimg.com/videos/mc/360p/sample.mp4", "width": 360, "height": 640, "duration": 14500 },
                      "V_720P": { "url": "https://v1.pinimg.com/videos/mc/720p/sample.mp4", "width": 720, "height": 1280, "duration": 14500 }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val extracted = PinterestExtractorHelper.parsePinResourceJson(
            jsonString = standardPinJson,
            originalUrl = "https://pin.it/xyz",
            resolvedUrl = "https://www.pinterest.com/pin/123456789012345/"
        )
        assertNotNull(extracted)
        assertEquals(SocialPlatform.PINTEREST, extracted?.platform)
        assertEquals("https://v1.pinimg.com/videos/mc/720p/sample.mp4", extracted?.hdDownloadUrl)
        assertEquals("https://v1.pinimg.com/videos/mc/360p/sample.mp4", extracted?.sdDownloadUrl)
        assertEquals("Cozy Minimalist Desk Tour", extracted?.title)
        assertEquals("Studio Setup", extracted?.author)
        assertEquals(14, extracted?.durationSec)

        val ideaPinJson = """
            {
              "resource_response": {
                "data": {
                  "title": "Coffee Pour Over Tutorial",
                  "native_creator": { "username": "baristajoy" },
                  "story_pin_data": {
                    "pages": [
                      {
                        "blocks": [
                          {
                            "video": {
                              "video_list": {
                                "V_EXP7": { "url": "https://v1.pinimg.com/videos/mc/exp7/coffee.mp4", "width": 720, "height": 1280, "duration": 9000 }
                              }
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val ideaExtracted = PinterestExtractorHelper.parsePinResourceJson(
            jsonString = ideaPinJson,
            originalUrl = "https://www.pinterest.com/pin/555666777/",
            resolvedUrl = "https://www.pinterest.com/pin/555666777/"
        )
        assertNotNull(ideaExtracted)
        assertEquals("https://v1.pinimg.com/videos/mc/exp7/coffee.mp4", ideaExtracted?.hdDownloadUrl)
        assertEquals("@baristajoy", ideaExtracted?.author)
        assertEquals("Pinterest Idea Pin HD", ideaExtracted?.extractionMethod)
    }

    @Test
    fun pinterestHtmlFallbackExtractsDirectPinimgMp4() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Sunset Timelapse | Pinterest" />
                <meta property="og:image" content="https://i.pinimg.com/736x/11/22/33.jpg" />
              </head>
              <body>
                <script>
                  var data = {"url":"https:\/\/v1.pinimg.com\/videos\/mc\/720p\/sunset.mp4"};
                </script>
              </body>
            </html>
        """.trimIndent()

        val info = PinterestExtractorHelper.parsePinterestHtml(
            html = html,
            originalUrl = "https://www.pinterest.com/pin/111222333/",
            resolvedUrl = "https://www.pinterest.com/pin/111222333/"
        )
        assertNotNull(info)
        assertEquals("https://v1.pinimg.com/videos/mc/720p/sunset.mp4", info?.hdDownloadUrl)
        assertEquals("Sunset Timelapse", info?.title)
        assertEquals("https://i.pinimg.com/736x/11/22/33.jpg", info?.thumbnailUrl)
    }

    @Test
    fun dubTimelineMathParsesTimestampsSrtAndBracketedScripts() {
        assertEquals(0L, DubTimelineMath.parseTimestampToMs("00:00"))
        assertEquals(65_500L, DubTimelineMath.parseTimestampToMs("01:05.500"))
        assertEquals(3_661_250L, DubTimelineMath.parseTimestampToMs("01:01:01,250"))
        assertEquals("01:05.50", DubTimelineMath.formatMsToTimestamp(65_500L))

        val srt = """
            1
            00:00:00,500 --> 00:00:03,200
            Kamusta mga kaibigan!

            2
            00:00:03,500 --> 00:00:06,000
            Panoorin natin ang tutorial na ito.
        """.trimIndent()
        val srtLines = DubTimelineMath.parseSrtOrTimedScript(srt)
        assertEquals(2, srtLines.size)
        assertEquals(500L, srtLines[0].startMs)
        assertEquals(3200L, srtLines[0].endMs)
        assertEquals("Kamusta mga kaibigan!", srtLines[0].filipinoText)
        assertEquals(3500L, srtLines[1].startMs)
        assertEquals(6000L, srtLines[1].endMs)

        val bracketed = """
            [00:01 - 00:04] Host: Tara, subukan natin ito ngayon.
            [00:04.50 - 00:08.00] Guest: Ang galing ng resulta!
        """.trimIndent()
        val scriptLines = DubTimelineMath.parseSrtOrTimedScript(bracketed)
        assertEquals(2, scriptLines.size)
        assertEquals("Host", scriptLines[0].speaker)
        assertEquals(1000L, scriptLines[0].startMs)
        assertEquals(4000L, scriptLines[0].endMs)
        assertEquals("Guest", scriptLines[1].speaker)
        assertEquals(4500L, scriptLines[1].startMs)
        assertEquals(8000L, scriptLines[1].endMs)

        // Tempo clamping & deadband checks
        assertEquals(1.0f, DubTimelineMath.computeTempoRatioForWindow(2000L, 2000L), 0.001f)
        assertEquals(1.45f, DubTimelineMath.computeTempoRatioForWindow(5000L, 2000L), 0.001f)
        assertEquals(0.85f, DubTimelineMath.computeTempoRatioForWindow(1000L, 4000L), 0.001f)
    }

    @Test
    fun wavPcmToolsRoundTripsWsolaAndDuckingMix() {
        val sampleRate = 16000
        val numSamples = sampleRate // 1.0 second sine tone at 220 Hz
        val sine = ShortArray(numSamples) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 14000.0).toInt().toShort()
        }

        val tempWav = File.createTempFile("reelforge_test_", ".wav")
        try {
            WavPcmTools.writeMonoWavFile(tempWav, sine, sampleRate)
            val decoded = WavPcmTools.readWavFile(tempWav)
            assertEquals(sampleRate, decoded.sampleRate)
            assertEquals(1, decoded.channels)
            assertEquals(numSamples, decoded.samples.size)
            assertEquals(sine[100], decoded.samples[100])
        } finally {
            tempWav.delete()
        }

        // WSOLA speed-up (1.25x) produces ~0.8x sample length while staying non-empty
        val faster = WavPcmTools.wsolaTimeScale(sine, sampleRate, 1.25f)
        assertTrue(faster.size in (numSamples * 0.65).toInt()..(numSamples * 0.90).toInt())

        // WSOLA slow-down (0.85x) produces >1.05x sample length
        val slower = WavPcmTools.wsolaTimeScale(sine, sampleRate, 0.85f)
        assertTrue(slower.size > numSamples)

        // Ducking mixer lowers original audio amplitude during speech window
        val totalMonoSamples = sampleRate * 2 // 2 seconds stereo
        val origStereo = ShortArray(totalMonoSamples * 2) { 10000 }
        val speechMono = ShortArray(sampleRate / 2) { 0 } // silent speech clip to isolate duck envelope
        val mixed = WavPcmTools.mixDubClipsIntoStereoTimeline(
            originalStereoPcm = origStereo,
            totalDurationSamplesPerChannel = totalMonoSamples,
            sampleRate = sampleRate,
            dubClips = listOf(TimedPcmClip(startSample = sampleRate / 2, endSample = sampleRate, monoSamples = speechMono)),
            backgroundGainDuringSpeech = 0.25f
        )
        assertEquals(totalMonoSamples * 2, mixed.size)
        // Before speech starts (sample 100), gain ~ 1.0 (~10000)
        assertTrue(abs(mixed[100 * 2].toInt()) > 8500)
        // Deep inside speech window (sample 14000), background is ducked (~2500)
        assertTrue(abs(mixed[14000 * 2].toInt()) < 4500)
    }

    @Test
    fun mmsTagalogTokenizerNormalizesAndInterleavesBlanks() {
        val normalized = MmsTagalogOnnxEngine.normalizeTagalogForTts("Panalo & ₱500 [Music] sa 100%!")
        assertEquals("panalo at piso 500 sa 100 porsyento !", normalized)

        val tokenMap = mapOf(
            "_" to 0L,
            " " to 1L,
            "a" to 2L,
            "b" to 3L,
            "k" to 4L
        )
        val noBlank = MmsTagalogOnnxEngine.encodeTextToTokenIds("ka ba", tokenMap, addBlank = false)
        assertEquals(listOf(4L, 2L, 1L, 3L, 2L), noBlank.toList())

        val withBlank = MmsTagalogOnnxEngine.encodeTextToTokenIds("ka", tokenMap, addBlank = true)
        assertEquals(listOf(0L, 4L, 0L, 2L, 0L), withBlank.toList())
    }

    @Test
    fun multiSpeakerResolverAndLongFormChunkMergerWorkAccurately() {
        val voices = listOf(
            DubVoiceOption("v-ph-1", "Voice 1", "fil-PH"),
            DubVoiceOption("v-ph-2", "Voice 2", "fil-PH")
        )
        val speakers = listOf("Speaker 1 (Male)", "Speaker 2 (Female)", "Narrator")
        val p1 = DubTimelineMath.resolveSpeakerVoiceAssignment("Speaker 1 (Male)", speakers, 1.0f, voices, "v-ph-1")
        val p2 = DubTimelineMath.resolveSpeakerVoiceAssignment("Speaker 2 (Female)", speakers, 1.0f, voices, "v-ph-1")
        val p3 = DubTimelineMath.resolveSpeakerVoiceAssignment("Narrator", speakers, 1.0f, voices, "v-ph-1")

        assertEquals("v-ph-1", p1.androidVoiceId)
        assertEquals("v-ph-2", p2.androidVoiceId)
        assertEquals("v-ph-1", p3.androidVoiceId)
        assertTrue(p2.pitchMultiplier > p1.pitchMultiplier)

        val windows = listOf(
            0L to 360_000L,
            360_000L to 720_000L
        )
        val chunk1 = listOf(
            DubLineItem(1L, 1_000L, 3_500L, "Speaker 1 (Male)", "Hi there.", "Kamusta ka."),
            DubLineItem(2L, 358_000L, 360_500L, "Speaker 2 (Female)", "Look at this.", "Tingnan mo ito.")
        )
        val chunk2 = listOf(
            // Boundary jitter duplicate + out-of-window line
            DubLineItem(1L, 358_300L, 360_700L, "Speaker 2 (Female)", "Look at this.", "Tingnan mo ito."),
            DubLineItem(2L, 361_000L, 364_000L, "Speaker 1 (Male)", "Awesome!", "Ang galing!"),
            DubLineItem(3L, 10_000L, 12_000L, "Narrator", "Out of range", "Dapat mawala")
        )
        val merged = DubTimelineMath.mergeDubScriptChunks(listOf(chunk1, chunk2), windows)
        assertEquals(3, merged.size)
        assertEquals(1L, merged[0].id)
        assertEquals(2L, merged[1].id)
        assertEquals(3L, merged[2].id)
        assertEquals("Ang galing!", merged[2].filipinoText)
        // Ensure chunk boundary item doesn't overlap chunk2 item at 361_000
        assertTrue(merged[1].endMs <= merged[2].startMs)
    }

    @Test
    fun wavPcmPitchShiftAndStreamingDuckingBlockPreserveDurationAndContinuity() {
        val sampleRate = 16000
        val sine = ShortArray(sampleRate) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 14000.0).toInt().toShort()
        }

        val shiftedUp = WavPcmTools.pitchShiftMono(sine, sampleRate, 1.16f)
        assertTrue(shiftedUp.size in (sampleRate * 0.70).toInt()..(sampleRate * 1.15).toInt())

        val blockLen = sampleRate // 1s blocks
        val origBlock = ShortArray(blockLen * 2) { 10000 }
        val speechClip = TimedPcmClip(
            startSample = sampleRate / 2,
            endSample = sampleRate + sampleRate / 2,
            monoSamples = ShortArray(sampleRate) { 0 }
        )
        val duckState = WavPcmTools.DuckingFilterState(1.0f)

        val block0 = WavPcmTools.mixDubBlockIntoStereo(
            originalStereoBlock = origBlock,
            blockStartSample = 0,
            blockLengthSamples = blockLen,
            sampleRate = sampleRate,
            overlappingClips = listOf(speechClip),
            backgroundGainDuringSpeech = 0.20f,
            duckingState = duckState
        )
        val block1 = WavPcmTools.mixDubBlockIntoStereo(
            originalStereoBlock = origBlock,
            blockStartSample = blockLen,
            blockLengthSamples = blockLen,
            sampleRate = sampleRate,
            overlappingClips = listOf(speechClip),
            backgroundGainDuringSpeech = 0.20f,
            duckingState = duckState
        )

        assertEquals(blockLen * 2, block0.size)
        assertEquals(blockLen * 2, block1.size)
        // Ducking carries cleanly across block boundary (start of block1 is still ducked)
        assertTrue(abs(block1[100 * 2].toInt()) < 4500)
        // Tail of block1 recovers smoothly back toward original gain after speech ends
        assertTrue(abs(block1[(blockLen - 10) * 2].toInt()) > 7500)
    }

    @Test
    fun characterVoiceLocksPersistAcrossEditsAndMoodStripping() {
        assertEquals("Kenji", DubTimelineMath.canonicalizeSpeakerName("Kenji (angry)"))
        assertEquals("Character 2 (Female)", DubTimelineMath.canonicalizeSpeakerName("Character 2 (Female) (whispering)"))

        val voices = listOf(
            DubVoiceOption("v-ph-1", "Voice 1", "fil-PH"),
            DubVoiceOption("v-ph-2", "Voice 2", "fil-PH")
        )
        val initialLines = listOf(
            DubLineItem(1L, 0L, 2000L, "Hero (Male)", "Hello", "Kamusta"),
            DubLineItem(2L, 2500L, 5000L, "Rival (Female) (shouting)", "Watch out!", "Mag-ingat ka!")
        )
        val initialCast = DubTimelineMath.buildLockedCharacterCast(
            lines = initialLines,
            existingCast = emptyMap(),
            basePitch = 1.0f,
            availableVoices = voices,
            selectedVoiceId = "v-ph-1"
        )
        assertEquals(2, initialCast.size)
        val heroLock = initialCast["Hero (Male)"]
        val rivalLock = initialCast["Rival (Female)"]
        assertNotNull(heroLock)
        assertNotNull(rivalLock)
        assertTrue(heroLock!!.pitchMultiplier != rivalLock!!.pitchMultiplier)
        assertEquals("v-ph-1", heroLock.androidVoiceId)
        assertEquals("v-ph-2", rivalLock.androidVoiceId)

        // Customize hero pitch and verify lock survives even if first line is deleted later
        val customizedHero = DubTimelineMath.cycleCharacterVoiceProfile(heroLock, voices)
        val editedCast = initialCast.toMutableMap().apply { put("Hero (Male)", customizedHero) }
        val remainingLines = listOf(
            DubLineItem(1L, 2500L, 5000L, "Rival (Female)", "Watch out!", "Mag-ingat ka!"),
            DubLineItem(2L, 6000L, 8500L, "Hero (Male)", "Let's go!", "Tara na!")
        )
        val rebuiltCast = DubTimelineMath.buildLockedCharacterCast(
            lines = remainingLines,
            existingCast = editedCast,
            basePitch = 1.0f,
            availableVoices = voices,
            selectedVoiceId = "v-ph-1"
        )
        assertEquals(customizedHero.pitchMultiplier, rebuiltCast["Hero (Male)"]?.pitchMultiplier)
        assertEquals(rivalLock.pitchMultiplier, rebuiltCast["Rival (Female)"]?.pitchMultiplier)
        assertEquals(rivalLock.androidVoiceId, rebuiltCast["Rival (Female)"]?.androidVoiceId)
    }

    @Test
    fun wavPcmSinglePassTimeAndPitchScaleMatchesTargetDuration() {
        val sampleRate = 16000
        val sine = ShortArray(sampleRate) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 14000.0).toInt().toShort()
        }
        // Speed up 1.25x while shifting pitch up 1.15x in ONE pass -> ~0.8x length
        val combined = WavPcmTools.timeAndPitchScaleMono(
            samples = sine,
            sampleRate = sampleRate,
            tempoSpeedFactor = 1.25f,
            pitchFactor = 1.15f
        )
        assertTrue(combined.size in (sampleRate * 0.65).toInt()..(sampleRate * 0.92).toInt())
    }

    @Test
    fun thirdPartyModelsCatalogDetectsSttChatVisionAndFiltersUnsafeOrTtsModels() {
        val groqModelsJson = """
            {
              "object": "list",
              "data": [
                { "id": "whisper-large-v3-turbo", "object": "model" },
                { "id": "whisper-large-v3", "object": "model" },
                { "id": "llama-3.3-70b-versatile", "object": "model" },
                { "id": "meta-llama/llama-4-scout-17b-16e-instruct", "object": "model" },
                { "id": "llama-guard-3-8b", "object": "model" },
                { "id": "playai-tts", "object": "model" }
              ]
            }
        """.trimIndent()

        val res = ThirdPartyModelCatalog.parseOpenAiModelsJson(
            jsonBody = groqModelsJson,
            provider = AiProvider.GROQ,
            resolvedBaseUrl = "https://api.groq.com/openai/v1"
        )
        assertTrue(res.isValid)
        assertEquals(4, res.allModels.size)
        assertFalse(res.allModels.any { it.id.contains("guard") || it.id.contains("tts") })
        assertEquals("whisper-large-v3-turbo", res.recommendedSttModel)
        assertEquals("llama-3.3-70b-versatile", res.recommendedChatModel)
        assertEquals("meta-llama/llama-4-scout-17b-16e-instruct", res.recommendedVisionModel)
        assertEquals(
            ModelCapabilityTag.AUDIO_STT,
            res.allModels.first { it.id == "whisper-large-v3-turbo" }.capability
        )
        assertEquals(
            ModelCapabilityTag.VISION_CHAT,
            res.allModels.first { it.id == "meta-llama/llama-4-scout-17b-16e-instruct" }.capability
        )
    }

    @Test
    fun geminiModelSelectionSkipsRetired25ProAndPicksActiveModels() {
        assertTrue(ThirdPartyModelCatalog.isDeprecatedGeminiModel("gemini-2.5-pro"))
        assertTrue(ThirdPartyModelCatalog.isDeprecatedGeminiModel("gemini-1.5-pro"))
        assertFalse(ThirdPartyModelCatalog.isDeprecatedGeminiModel("gemini-3-flash-preview"))
        assertFalse(ThirdPartyModelCatalog.isDeprecatedGeminiModel("gemini-3.1-pro-preview"))

        val picked = ThirdPartyModelCatalog.pickPreferredGeminiModel(
            availableIds = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.1-pro-preview")
        )
        assertEquals("gemini-2.5-flash", picked)

        val fallback = ThirdPartyModelCatalog.pickPreferredGeminiModel(
            availableIds = listOf("gemini-2.5-pro", "gemini-3.1-pro-preview"),
            excluding = setOf("gemini-2.5-pro")
        )
        assertEquals("gemini-3.1-pro-preview", fallback)
    }

    @Test
    fun whisperVerboseJsonAndChunkOffsetPreserveGlobalVideoTimestamps() {
        val verboseJson = """
            {
              "language": "english",
              "text": "Welcome back everyone. Let's test this audio.",
              "segments": [
                { "id": 0, "start": 1.25, "end": 3.50, "text": " Welcome back everyone." },
                { "id": 1, "start": 3.80, "end": 6.10, "text": " Let's test this audio." }
              ]
            }
        """.trimIndent()

        val parsed = ThirdPartyModelCatalog.parseWhisperVerboseJson(verboseJson)
        assertEquals("english", parsed.detectedLanguage)
        assertEquals(2, parsed.segments.size)

        // Chunk 2 starts at 6 minutes (360_000 ms)
        val global = ThirdPartyTranscriptHelper.flattenChunkToGlobalSegments(
            chunk = parsed,
            startOffsetMs = 360_000L,
            chunkDurationMs = 120_000L,
            startingIndex = 45
        )
        assertEquals(2, global.size)
        assertEquals(45, global[0].index)
        assertEquals(361_250L, global[0].startMs)
        assertEquals(363_500L, global[0].endMs)
        assertEquals("Welcome back everyone.", global[0].text)
        assertEquals(363_800L, global[1].startMs)
        assertEquals(366_100L, global[1].endMs)
    }

    @Test
    fun openAiChatExtractorStripsMarkdownFencesCleanly() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "```json\n{\"lines\":[{\"index\":1,\"speaker\":\"Host\",\"filipinoText\":\"Kamusta kayo!\"}]}\n```"
                  }
                }
              ]
            }
        """.trimIndent()

        val extracted = ThirdPartyModelCatalog.extractOpenAiChatContent(response)
        assertTrue(extracted.startsWith("{"))
        assertTrue(extracted.endsWith("}"))
        assertTrue(extracted.contains("Kamusta kayo!"))
    }

    @Test
    fun fastDownloadPlannerSplitsExactNonOverlappingRanges() {
        assertEquals(104_857_600L, FastDownloadPlanner.parseTotalBytesFromContentRange("bytes 0-0/104857600"))
        assertNull(FastDownloadPlanner.parseTotalBytesFromContentRange("bytes 0-0/*"))

        val totalSize = 60L * 1024L * 1024L // 60 MB
        val chunks = FastDownloadPlanner.planChunks(totalSize, maxThreads = 6)
        assertEquals(6, chunks.size)
        assertEquals(0L, chunks.first().startByte)
        assertEquals(totalSize - 1L, chunks.last().endByte)
        assertEquals(totalSize, chunks.sumOf { it.expectedLength })
        for (i in 1 until chunks.size) {
            assertEquals(chunks[i - 1].endByte + 1L, chunks[i].startByte)
        }

        // Tiny file (<1 MB) stays 1 chunk
        val smallChunks = FastDownloadPlanner.planChunks(400L * 1024L, maxThreads = 6)
        assertEquals(1, smallChunks.size)
    }
}


