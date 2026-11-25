package com.example.data.dubbing

import android.content.Context
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.data.model.AiProvider
import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubLineItem
import com.example.data.model.DubSourceLanguage
import com.example.data.model.DubVoiceEngine
import com.example.data.model.LocalVideoInspection
import com.example.data.model.MmsModelStatus
import com.example.data.model.VideoTranscript
import com.example.data.model.WhisperBrainTier
import com.example.data.network.GeminiVideoAnalyzerRepository
import com.example.data.network.ThirdPartyAiRepository
import com.example.ui.DubRenderUiState
import com.example.ui.DubScriptUiState
import com.example.ui.DubberPanelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped coordinator so AI dubbing, translation, and ONNX model downloads
 * keep running reliably in the background even when the user presses Back or switches apps.
 */
class DubbingJobCoordinator private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val mmsOnnxEngine = MmsTagalogOnnxEngine(appContext)
    val whisperOnnxEngine = WhisperOnnxEngine(appContext)
    val androidTts = AndroidTtsSynthesizer(appContext)
    private val videoDubber = VideoDubberEngine(appContext, mmsOnnxEngine, androidTts)
    private val geminiAnalyzer = GeminiVideoAnalyzerRepository(appContext)
    private val thirdPartyAi = ThirdPartyAiRepository(appContext)
    private val mp4SpeakerAnalyzer = Mp4SpeakerAnalyzer(appContext)
    private val embeddedSubExtractor = EmbeddedSubtitleExtractor(appContext)
    private val offlineTranslator = OfflineTagalogTranslator()
    private val settingsStore = SettingsDataStore(appContext)

    private var loadedVideoUri: String = ""

    private val _state = MutableStateFlow(DubberPanelState())
    val state: StateFlow<DubberPanelState> = _state.asStateFlow()

    init {
        refreshEnvironment()
    }

    fun isBackgroundWorkActive(): Boolean {
        val cur = _state.value
        return cur.isAutoPipelineRunning ||
            cur.mmsModelStatus == MmsModelStatus.DOWNLOADING ||
            cur.whisperModelStatus == MmsModelStatus.DOWNLOADING ||
            cur.scriptState is DubScriptUiState.Generating ||
            cur.renderState is DubRenderUiState.Rendering
    }

    fun onSelectedVideoChanged(newUriString: String) {
        if (isBackgroundWorkActive()) return
        if (loadedVideoUri.isNotBlank() && loadedVideoUri != newUriString) {
            _state.value = _state.value.copy(
                dubLines = emptyList(),
                speakerProfiles = emptyMap(),
                detectedCastSummary = "",
                scriptState = DubScriptUiState.Idle,
                renderState = DubRenderUiState.Idle
            )
        }
        loadedVideoUri = newUriString
    }

    fun refreshEnvironment() {
        val ready = mmsOnnxEngine.isModelReady()
        val sizeMb = mmsOnnxEngine.getInstalledSizeMb()
        val curStatus = _state.value.mmsModelStatus

        val activeTier = _state.value.whisperBrainTier
        val whisperReady = whisperOnnxEngine.isModelReady(activeTier)
        val whisperSizeMb = whisperOnnxEngine.getInstalledSizeMb(activeTier)
        val curWhisperStatus = _state.value.whisperModelStatus

        _state.value = _state.value.copy(
            mmsModelStatus = if (curStatus == MmsModelStatus.DOWNLOADING) {
                MmsModelStatus.DOWNLOADING
            } else if (ready) {
                MmsModelStatus.READY
            } else {
                MmsModelStatus.NOT_INSTALLED
            },
            mmsModelSizeMb = sizeMb,
            whisperModelStatus = if (curWhisperStatus == MmsModelStatus.DOWNLOADING) {
                MmsModelStatus.DOWNLOADING
            } else if (whisperReady) {
                MmsModelStatus.READY
            } else {
                MmsModelStatus.NOT_INSTALLED
            },
            whisperModelSizeMb = whisperSizeMb
        )
        scope.launch {
            val voices = androidTts.getAvailableFilipinoVoices()
            val cur = _state.value
            val resolvedId = if (voices.any { it.id == cur.selectedAndroidVoiceId }) {
                cur.selectedAndroidVoiceId
            } else {
                voices.firstOrNull()?.id.orEmpty()
            }
            val updatedCast = DubTimelineMath.buildLockedCharacterCast(
                lines = cur.dubLines,
                existingCast = cur.speakerProfiles,
                basePitch = cur.voicePitch,
                availableVoices = voices,
                selectedVoiceId = resolvedId
            )
            _state.value = _state.value.copy(
                androidVoices = voices,
                selectedAndroidVoiceId = resolvedId,
                speakerProfiles = updatedCast
            )
        }
    }

    fun selectSourceLanguage(lang: DubSourceLanguage) {
        _state.value = _state.value.copy(sourceLanguage = lang)
    }

    fun selectVoiceEngine(engine: DubVoiceEngine) {
        _state.value = _state.value.copy(voiceEngine = engine)
    }

    fun selectDuckingMode(mode: DubAudioDuckingMode) {
        _state.value = _state.value.copy(duckingMode = mode)
    }

    fun selectAndroidVoice(voiceId: String) {
        val cur = _state.value
        val pitchHints = cur.speakerProfiles.mapValues { it.value.detectedPitchHz }
        val rebuiltCast = DubTimelineMath.buildLockedCharacterCast(
            lines = cur.dubLines,
            existingCast = emptyMap(),
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = voiceId,
            detectedPitchHzMap = pitchHints
        )
        _state.value = cur.copy(
            selectedAndroidVoiceId = voiceId,
            speakerProfiles = rebuiltCast
        )
    }

    fun updateVoicePitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.84f, 1.22f)
        val cur = _state.value
        val pitchHints = cur.speakerProfiles.mapValues { it.value.detectedPitchHz }
        val rebuiltCast = DubTimelineMath.buildLockedCharacterCast(
            lines = cur.dubLines,
            existingCast = emptyMap(),
            basePitch = clamped,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId,
            detectedPitchHzMap = pitchHints
        )
        _state.value = cur.copy(
            voicePitch = clamped,
            speakerProfiles = rebuiltCast
        )
    }

    fun updateBaseSpeed(rate: Float) {
        _state.value = _state.value.copy(baseSpeechRate = rate.coerceIn(0.84f, 1.30f))
    }

    fun toggleMultiSpeaker(enabled: Boolean) {
        _state.value = _state.value.copy(multiSpeakerEnabled = enabled)
    }

    fun cycleCharacterVoice(speakerKey: String) {
        val cur = _state.value
        val currentProfile = cur.speakerProfiles[speakerKey] ?: return
        val updatedProfile = DubTimelineMath.cycleCharacterVoiceProfile(currentProfile, cur.androidVoices)
        val newMap = cur.speakerProfiles.toMutableMap().apply {
            put(speakerKey, updatedProfile)
        }
        _state.value = cur.copy(speakerProfiles = newMap)
    }

    fun downloadMmsTagalogModel() {
        if (_state.value.mmsModelStatus == MmsModelStatus.DOWNLOADING) return
        scope.launch {
            _state.value = _state.value.copy(
                mmsModelStatus = MmsModelStatus.DOWNLOADING,
                mmsDownloadStage = "Starting Hugging Face download...",
                mmsDownloadProgress = null
            )
            DubbingForegroundService.startOrUpdate(
                context = appContext,
                title = "Downloading Tagalog Voice Model",
                message = "Open-Source MMS-TTS ONNX (~62 MB)...",
                progressPercent = null
            )

            var lastNotifPct = -1
            val res = mmsOnnxEngine.downloadModel { stage, progress ->
                _state.value = _state.value.copy(
                    mmsDownloadStage = stage,
                    mmsDownloadProgress = progress
                )
                val pct = progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
                if (pct == null || pct >= lastNotifPct + 5 || pct >= 95) {
                    if (pct != null) lastNotifPct = pct
                    DubbingForegroundService.startOrUpdate(
                        context = appContext,
                        title = "Downloading Tagalog Voice Model",
                        message = stage,
                        progressPercent = pct
                    )
                }
            }
            res.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        mmsModelStatus = MmsModelStatus.READY,
                        mmsModelSizeMb = mmsOnnxEngine.getInstalledSizeMb(),
                        mmsDownloadStage = "Open-Source MMS Tagalog ONNX Ready!",
                        mmsDownloadProgress = 1f
                    )
                    DubbingForegroundService.completeAndStop(
                        context = appContext,
                        title = "Tapos na! MMS Tagalog Model Ready",
                        message = "100% offline Tagalog ONNX voice ay ready na gamitin."
                    )
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Model download failed."
                    _state.value = _state.value.copy(
                        mmsModelStatus = MmsModelStatus.ERROR,
                        mmsDownloadStage = msg,
                        mmsDownloadProgress = null
                    )
                    DubbingForegroundService.failAndStop(
                        context = appContext,
                        title = "Hindi natapos ang model download",
                        message = msg
                    )
                }
            )
        }
    }

    fun deleteMmsTagalogModel() {
        mmsOnnxEngine.deleteModel()
        _state.value = _state.value.copy(
            mmsModelStatus = MmsModelStatus.NOT_INSTALLED,
            mmsModelSizeMb = 0.0,
            mmsDownloadStage = "",
            mmsDownloadProgress = null
        )
    }

    fun togglePreferOfflineWhisperBrain(prefer: Boolean) {
        _state.value = _state.value.copy(preferOfflineWhisperBrain = prefer)
    }

    fun toggleAutoStartOnVideoDrop(enabled: Boolean) {
        _state.value = _state.value.copy(autoStartOnVideoDrop = enabled)
    }

    fun selectWhisperBrainTier(tier: WhisperBrainTier) {
        if (_state.value.whisperModelStatus == MmsModelStatus.DOWNLOADING || isBackgroundWorkActive()) return
        val ready = whisperOnnxEngine.isModelReady(tier)
        val sizeMb = whisperOnnxEngine.getInstalledSizeMb(tier)
        _state.value = _state.value.copy(
            whisperBrainTier = tier,
            whisperModelStatus = if (ready) MmsModelStatus.READY else MmsModelStatus.NOT_INSTALLED,
            whisperModelSizeMb = sizeMb,
            whisperDownloadStage = "",
            whisperDownloadProgress = null
        )
    }

    fun downloadWhisperBaseModel() {
        if (_state.value.whisperModelStatus == MmsModelStatus.DOWNLOADING) return
        val tier = _state.value.whisperBrainTier
        scope.launch {
            _state.value = _state.value.copy(
                whisperModelStatus = MmsModelStatus.DOWNLOADING,
                whisperDownloadStage = "Starting ${tier.label} ONNX download (${tier.approxSizeLabel})...",
                whisperDownloadProgress = null
            )
            DubbingForegroundService.startOrUpdate(
                context = appContext,
                title = "Downloading ${tier.label}",
                message = "Open-Source Offline AI (${tier.approxSizeLabel})...",
                progressPercent = null
            )

            var lastNotifPct = -1
            val res = whisperOnnxEngine.downloadModel(tier) { stage, progress ->
                _state.value = _state.value.copy(
                    whisperDownloadStage = stage,
                    whisperDownloadProgress = progress
                )
                val pct = progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
                if (pct == null || pct >= lastNotifPct + 5 || pct >= 95) {
                    if (pct != null) lastNotifPct = pct
                    DubbingForegroundService.startOrUpdate(
                        context = appContext,
                        title = "Downloading ${tier.label}",
                        message = stage,
                        progressPercent = pct
                    )
                }
            }
            res.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        whisperModelStatus = MmsModelStatus.READY,
                        whisperModelSizeMb = whisperOnnxEngine.getInstalledSizeMb(tier),
                        whisperDownloadStage = "${tier.label} Ready (Offline)!",
                        whisperDownloadProgress = 1f
                    )
                    DubbingForegroundService.completeAndStop(
                        context = appContext,
                        title = "Tapos na! ${tier.label} Ready",
                        message = "100% open-source offline MP4 speech AI ay ready na."
                    )
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Whisper download failed."
                    _state.value = _state.value.copy(
                        whisperModelStatus = MmsModelStatus.ERROR,
                        whisperDownloadStage = msg,
                        whisperDownloadProgress = null
                    )
                    DubbingForegroundService.failAndStop(
                        context = appContext,
                        title = "Hindi natapos ang Whisper download",
                        message = msg
                    )
                }
            )
        }
    }

    fun deleteWhisperBaseModel() {
        val tier = _state.value.whisperBrainTier
        whisperOnnxEngine.deleteModel(tier)
        _state.value = _state.value.copy(
            whisperModelStatus = MmsModelStatus.NOT_INSTALLED,
            whisperModelSizeMb = 0.0,
            whisperDownloadStage = "",
            whisperDownloadProgress = null
        )
    }

    /**
     * Full 1-Tap Automated Pipeline:
     * 1) Extract & translate dialogue to natural Filipino (Embedded MP4 subs + ML Kit Free, OR Vault transcript + ML Kit Free, OR Cloud AI with auto-fallback)
     * 2) Detect exact speaker count & Male/Female pitch registers directly from MP4 audio
     * 3) Strip original vocals (keeping BGM/SFX), synthesize matched Filipino voices, & render finished MP4 to Gallery.
     */
    fun autoDubAndRenderVideo(
        inspection: LocalVideoInspection?,
        settings: UserSettings,
        cachedTranscriptForVideo: VideoTranscript? = null,
        onNeedApiKey: () -> Unit = {}
    ) {
        if (isBackgroundWorkActive()) return
        if (inspection == null) {
            _state.value = _state.value.copy(
                renderState = DubRenderUiState.Error("Pumili muna ng video na awtomatikong idu-dub.")
            )
            return
        }

        loadedVideoUri = inspection.uriString
        scope.launch {
            val cur = _state.value
            val effectiveEngine = resolveEffectiveEngine(cur)

            _state.value = cur.copy(
                isAutoPipelineRunning = true,
                voiceEngine = effectiveEngine,
                renderState = DubRenderUiState.Rendering("Step 1/3: Scanning MP4 dialogue & subtitles...", 0.04f),
                scriptState = DubScriptUiState.Generating("Auto-analyzing video speech...")
            )
            DubbingForegroundService.startOrUpdate(
                context = appContext,
                title = "Auto-Dubbing MP4 to Filipino",
                message = "Step 1/3: Translating dialogue to Filipino...",
                progressPercent = 4
            )

            try {
                // Step 1: Obtain timed Filipino lines automatically
                val baseLines = if (cur.dubLines.isNotEmpty() && loadedVideoUri == inspection.uriString) {
                    cur.dubLines
                } else {
                    resolveTimedFilipinoLines(
                        inspection = inspection,
                        settings = settings,
                        cachedTranscriptForVideo = cachedTranscriptForVideo,
                        onStage = { msg, frac ->
                            _state.value = _state.value.copy(
                                scriptState = DubScriptUiState.Generating(msg),
                                renderState = DubRenderUiState.Rendering(msg, frac)
                            )
                            DubbingForegroundService.startOrUpdate(
                                context = appContext,
                                title = "Auto-Dubbing MP4 to Filipino",
                                message = msg,
                                progressPercent = (frac * 100f).toInt().coerceIn(2, 99)
                            )
                        }
                    ).getOrElse { err ->
                        val msg = err.localizedMessage ?: "Hindi ma-process ang MP4 audio gamit ang Open-Source Whisper + ML Kit. Pumili ng Small/Base tier kung kulang sa RAM."
                        _state.value = _state.value.copy(
                            isAutoPipelineRunning = false,
                            scriptState = DubScriptUiState.Error(msg),
                            renderState = DubRenderUiState.Error(msg)
                        )
                        DubbingForegroundService.failAndStop(appContext, "Auto-Dub stopped", msg)
                        return@launch
                    }
                }

                // Step 2: Detect speakers & Male/Female voice registers directly from MP4 audio
                _state.value = _state.value.copy(
                    renderState = DubRenderUiState.Rendering("Step 2/3: Detecting speakers & pitch inside MP4 audio...", 0.34f)
                )
                DubbingForegroundService.startOrUpdate(
                    context = appContext,
                    title = "Auto-Dubbing MP4 to Filipino",
                    message = "Detecting characters inside MP4 audio...",
                    progressPercent = 34
                )

                val speakerAnalysis = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    mp4SpeakerAnalyzer.analyzeMp4Speakers(inspection.uriString, baseLines)
                }

                val latest = _state.value
                val lockedCast = DubTimelineMath.buildLockedCharacterCast(
                    lines = speakerAnalysis.refinedLines,
                    existingCast = emptyMap(),
                    basePitch = latest.voicePitch,
                    availableVoices = latest.androidVoices,
                    selectedVoiceId = latest.selectedAndroidVoiceId,
                    detectedPitchHzMap = speakerAnalysis.speakerPitchHz
                )

                _state.value = latest.copy(
                    dubLines = speakerAnalysis.refinedLines,
                    speakerProfiles = lockedCast,
                    detectedCastSummary = speakerAnalysis.summary,
                    scriptState = DubScriptUiState.Ready(speakerAnalysis.refinedLines.size),
                    renderState = DubRenderUiState.Rendering(
                        speakerAnalysis.summary.ifBlank { "Step 3/3: Stripping original voice & synthesizing Filipino cast..." },
                        0.40f
                    )
                )

                // Step 3: Automatically render dubbed MP4 with vocal stripping + broadcast mastering
                var lastNotifPct = 38
                val renderResult = videoDubber.renderDubbedVideo(
                    sourceUriString = inspection.uriString,
                    sourceDurationMs = inspection.durationMs,
                    lines = speakerAnalysis.refinedLines,
                    voiceEngine = effectiveEngine,
                    androidVoiceId = latest.selectedAndroidVoiceId,
                    availableAndroidVoices = latest.androidVoices,
                    lockedSpeakerProfiles = lockedCast,
                    voicePitch = latest.voicePitch,
                    baseSpeechRate = latest.baseSpeechRate,
                    duckingMode = latest.duckingMode,
                    multiSpeakerEnabled = latest.multiSpeakerEnabled,
                    onStageUpdate = { stage, frac ->
                        val unifiedFrac = 0.40f + ((frac ?: 0f) * 0.60f)
                        _state.value = _state.value.copy(
                            renderState = DubRenderUiState.Rendering(stage, unifiedFrac.coerceIn(0.40f, 1.0f))
                        )
                        val pct = (unifiedFrac * 100f).toInt().coerceIn(40, 100)
                        if (pct >= lastNotifPct + 3 || pct >= 85) {
                            lastNotifPct = pct
                            DubbingForegroundService.startOrUpdate(
                                context = appContext,
                                title = "Dubbing Video to Filipino",
                                message = stage,
                                progressPercent = pct
                            )
                        }
                    }
                )

                renderResult.fold(
                    onSuccess = { dubbed ->
                        _state.value = _state.value.copy(
                            isAutoPipelineRunning = false,
                            renderState = DubRenderUiState.Success(dubbed)
                        )
                        DubbingForegroundService.completeAndStop(
                            context = appContext,
                            title = "Tapos na! Filipino Dubbed MP4 Ready",
                            message = "${dubbed.fileName} (${lockedCast.size} character voice(s)) saved to Gallery."
                        )
                    },
                    onFailure = { err ->
                        val msg = err.localizedMessage ?: "Hindi natapos ang video dubbing render."
                        _state.value = _state.value.copy(
                            isAutoPipelineRunning = false,
                            renderState = DubRenderUiState.Error(msg)
                        )
                        DubbingForegroundService.failAndStop(
                            context = appContext,
                            title = "Hindi natapos ang auto-dub",
                            message = msg
                        )
                    }
                )
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isAutoPipelineRunning = false)
                throw e
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Auto-dub failed."
                _state.value = _state.value.copy(
                    isAutoPipelineRunning = false,
                    renderState = DubRenderUiState.Error(msg)
                )
                DubbingForegroundService.failAndStop(appContext, "Auto-Dub failed", msg)
            }
        }
    }

    private suspend fun resolveEffectiveEngine(cur: DubberPanelState): DubVoiceEngine {
        return when {
            cur.voiceEngine == DubVoiceEngine.MMS_ONNX_TAGALOG && mmsOnnxEngine.isModelReady() -> {
                DubVoiceEngine.MMS_ONNX_TAGALOG
            }
            cur.voiceEngine == DubVoiceEngine.MMS_ONNX_TAGALOG -> {
                // Smoothly fall back to built-in Neural Filipino if ONNX isn't downloaded yet
                DubVoiceEngine.ANDROID_FIL_PH
            }
            else -> DubVoiceEngine.ANDROID_FIL_PH
        }
    }

    private suspend fun resolveTimedFilipinoLines(
        inspection: LocalVideoInspection,
        settings: UserSettings,
        cachedTranscriptForVideo: VideoTranscript?,
        onStage: (String, Float) -> Unit
    ): Result<List<DubLineItem>> {
        // 1) Check embedded subtitle track inside MP4 (100% Free, Offline, Zero Key!)
        val embedded = kotlinx.coroutines.withContext(Dispatchers.IO) {
            embeddedSubExtractor.extractEmbeddedLines(inspection.uriString)
        }
        if (embedded.isNotEmpty()) {
            onStage("Found ${embedded.size} embedded subtitles! Translating offline (₱0)...", 0.14f)
            val translated = offlineTranslator.translateLinesToTagalog(
                lines = embedded,
                sourceHint = _state.value.sourceLanguage,
                onStageUpdate = { msg -> onStage(msg, 0.24f) }
            )
            if (translated.isSuccess && translated.getOrNull()?.isNotEmpty() == true) {
                return translated
            }
        }

        // 2) Check cached transcript from Script tab (if user already extracted one for this video)
        if (cachedTranscriptForVideo != null && cachedTranscriptForVideo.segments.isNotEmpty()) {
            val fromTranscript = DubTimelineMath.fromVideoTranscript(cachedTranscriptForVideo).map {
                it.copy(originalText = it.filipinoText)
            }
            if (fromTranscript.isNotEmpty()) {
                onStage("Translating saved transcript offline (Free ML Kit Tagalog)...", 0.18f)
                val translated = offlineTranslator.translateLinesToTagalog(
                    lines = fromTranscript,
                    sourceHint = _state.value.sourceLanguage,
                    onStageUpdate = { msg -> onStage(msg, 0.26f) }
                )
                if (translated.isSuccess && translated.getOrNull()?.isNotEmpty() == true) {
                    return translated
                }
            }
        }

        // 3) Strongest 100% Open-Source On-Device AI Brain (Whisper Large-v3-Turbo / Small / Base ONNX + ML Kit Tagalog, ₱0, Zero API Keys!)
        val selectedTier = _state.value.whisperBrainTier
        val sttRes = runWhisperWithRamFallback(
            preferredTier = selectedTier,
            inspection = inspection,
            sourceHint = _state.value.sourceLanguage,
            onStage = onStage
        )
        val rawLines = sttRes.getOrElse { return Result.failure(it) }
        onStage("Translating ${rawLines.size} spoken lines to Filipino (ML Kit Offline, ₱0)...", 0.29f)
        val translated = offlineTranslator.translateLinesToTagalog(
            lines = rawLines,
            sourceHint = DubSourceLanguage.AUTO_DETECT,
            onStageUpdate = { msg -> onStage(msg, 0.31f) }
        )
        return if (translated.isSuccess && translated.getOrNull()?.isNotEmpty() == true) {
            translated
        } else {
            Result.success(rawLines)
        }
    }

    private suspend fun runWhisperWithRamFallback(
        preferredTier: WhisperBrainTier,
        inspection: LocalVideoInspection,
        sourceHint: DubSourceLanguage,
        onStage: (String, Float) -> Unit
    ): Result<List<DubLineItem>> {
        val candidateTiers = when (preferredTier) {
            WhisperBrainTier.LARGE_V3_TURBO -> listOf(
                WhisperBrainTier.LARGE_V3_TURBO,
                WhisperBrainTier.SMALL,
                WhisperBrainTier.BASE
            )
            WhisperBrainTier.SMALL -> listOf(WhisperBrainTier.SMALL, WhisperBrainTier.BASE)
            WhisperBrainTier.BASE -> listOf(WhisperBrainTier.BASE)
        }

        var lastError: Throwable? = null
        for ((index, tier) in candidateTiers.withIndex()) {
            _state.value = _state.value.copy(whisperBrainTier = tier)

            if (!whisperOnnxEngine.isModelReady(tier)) {
                _state.value = _state.value.copy(
                    whisperModelStatus = MmsModelStatus.DOWNLOADING,
                    whisperDownloadStage = "Downloading ${tier.label} (${tier.approxSizeLabel})..."
                )
                val dl = whisperOnnxEngine.downloadModel(tier) { dlStage, dlFrac ->
                    _state.value = _state.value.copy(
                        whisperDownloadStage = dlStage,
                        whisperDownloadProgress = dlFrac
                    )
                    val mappedFrac = 0.05f + ((dlFrac ?: 0.5f) * 0.14f)
                    onStage(dlStage, mappedFrac)
                }
                if (dl.isFailure) {
                    val err = dl.exceptionOrNull()
                    lastError = err
                    val errMsg = err?.localizedMessage.orEmpty()
                    val isRamOrModelFailure = errMsg.contains("RAM", ignoreCase = true) ||
                        errMsg.contains("memory", ignoreCase = true) ||
                        errMsg.contains("OutOfMemory", ignoreCase = true) ||
                        errMsg.contains("alloc", ignoreCase = true) ||
                        errMsg.contains("HTTP 404", ignoreCase = true)
                    val nextTier = candidateTiers.getOrNull(index + 1)
                    if (nextTier != null && (isRamOrModelFailure || whisperOnnxEngine.isModelReady(nextTier))) {
                        whisperOnnxEngine.closeSessions()
                        onStage("${tier.label} unavailable/RAM full — switching to ${nextTier.label}...", 0.08f)
                        continue
                    }
                    _state.value = _state.value.copy(
                        whisperModelStatus = MmsModelStatus.ERROR,
                        whisperDownloadStage = errMsg.ifBlank { "Whisper download failed." }
                    )
                    return Result.failure(err ?: IllegalStateException("Whisper download failed."))
                }
                _state.value = _state.value.copy(
                    whisperModelStatus = MmsModelStatus.READY,
                    whisperModelSizeMb = whisperOnnxEngine.getInstalledSizeMb(tier),
                    whisperDownloadStage = "${tier.label} Ready!",
                    whisperDownloadProgress = 1f
                )
            } else {
                _state.value = _state.value.copy(
                    whisperModelStatus = MmsModelStatus.READY,
                    whisperModelSizeMb = whisperOnnxEngine.getInstalledSizeMb(tier)
                )
            }

            val sttRes = whisperOnnxEngine.transcribeMp4ToLines(
                sourceUriString = inspection.uriString,
                sourceHint = sourceHint,
                tier = tier,
                onProgress = { stage, frac ->
                    onStage(stage, frac ?: 0.22f)
                }
            )
            if (sttRes.isSuccess) {
                return sttRes
            }
            val err = sttRes.exceptionOrNull()
            lastError = err
            val errMsg = err?.localizedMessage.orEmpty()
            val isRecoverableTierFailure = errMsg.contains("RAM", ignoreCase = true) ||
                errMsg.contains("memory", ignoreCase = true) ||
                errMsg.contains("OutOfMemory", ignoreCase = true) ||
                errMsg.contains("alloc", ignoreCase = true) ||
                errMsg.contains("runtime", ignoreCase = true) ||
                errMsg.contains("OrtException", ignoreCase = true)
            val nextTier = candidateTiers.getOrNull(index + 1)
            if (nextTier != null && isRecoverableTierFailure) {
                whisperOnnxEngine.closeSessions()
                onStage("${tier.label} hit device RAM limit — switching cleanly to ${nextTier.label}...", 0.12f)
                continue
            }
            return Result.failure(err ?: IllegalStateException("Whisper AI transcription failed."))
        }
        return Result.failure(lastError ?: IllegalStateException("Whisper AI failed across all tiers."))
    }

    fun generateFilipinoDubScript(
        inspection: LocalVideoInspection?,
        settings: UserSettings
    ) {
        if (_state.value.scriptState is DubScriptUiState.Generating || _state.value.isAutoPipelineRunning) return
        if (inspection == null) {
            _state.value = _state.value.copy(
                scriptState = DubScriptUiState.Error("Pumili muna ng video mula sa device o downloader.")
            )
            return
        }
        loadedVideoUri = inspection.uriString
        scope.launch {
            _state.value = _state.value.copy(
                scriptState = DubScriptUiState.Generating("Preparing video & checking subtitles...")
            )
            DubbingForegroundService.startOrUpdate(
                context = appContext,
                title = "Translating to Filipino",
                message = "Preparing ${inspection.displayName}...",
                progressPercent = null
            )

            try {
                val result = resolveTimedFilipinoLines(
                    inspection = inspection,
                    settings = settings,
                    cachedTranscriptForVideo = null,
                    onStage = { stage, _ ->
                        _state.value = _state.value.copy(scriptState = DubScriptUiState.Generating(stage))
                        DubbingForegroundService.startOrUpdate(
                            context = appContext,
                            title = "Translating to Filipino",
                            message = stage,
                            progressPercent = null
                        )
                    }
                )
                result.fold(
                    onSuccess = { lines ->
                        _state.value = _state.value.copy(
                            scriptState = DubScriptUiState.Generating("Detecting speakers & voice pitch from MP4...")
                        )
                        val speakerAnalysis = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            mp4SpeakerAnalyzer.analyzeMp4Speakers(inspection.uriString, lines)
                        }
                        val cur = _state.value
                        val cast = DubTimelineMath.buildLockedCharacterCast(
                            lines = speakerAnalysis.refinedLines,
                            existingCast = emptyMap(),
                            basePitch = cur.voicePitch,
                            availableVoices = cur.androidVoices,
                            selectedVoiceId = cur.selectedAndroidVoiceId,
                            detectedPitchHzMap = speakerAnalysis.speakerPitchHz
                        )
                        _state.value = cur.copy(
                            dubLines = speakerAnalysis.refinedLines,
                            speakerProfiles = cast,
                            detectedCastSummary = speakerAnalysis.summary,
                            scriptState = DubScriptUiState.Ready(speakerAnalysis.refinedLines.size),
                            renderState = DubRenderUiState.Idle
                        )
                        DubbingForegroundService.completeAndStop(
                            context = appContext,
                            title = "Tapos na ang Filipino Script (${speakerAnalysis.refinedLines.size} lines)",
                            message = speakerAnalysis.summary.ifBlank { "${cast.size} character(s) locked · Handa nang i-render." }
                        )
                    },
                    onFailure = { err ->
                        val msg = err.localizedMessage ?: "Hindi ma-generate ang Filipino dubbing script."
                        _state.value = _state.value.copy(
                            scriptState = DubScriptUiState.Error(msg)
                        )
                        DubbingForegroundService.failAndStop(
                            context = appContext,
                            title = "Translation failed",
                            message = msg
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Script generation failed."
                _state.value = _state.value.copy(scriptState = DubScriptUiState.Error(msg))
                DubbingForegroundService.failAndStop(appContext, "Translation failed", msg)
            }
        }
    }

    fun importOfflineScript(rawScript: String, inspection: LocalVideoInspection? = null, translateToTagalog: Boolean = true): Int {
        val parsed = DubTimelineMath.parseSrtOrTimedScript(rawScript)
        if (parsed.isEmpty()) return 0
        val normalized = parsed.map {
            it.copy(
                speaker = DubTimelineMath.canonicalizeSpeakerName(it.speaker),
                originalText = it.originalText.ifBlank { it.filipinoText }
            )
        }
        val cur = _state.value
        val cast = DubTimelineMath.buildLockedCharacterCast(
            lines = normalized,
            existingCast = emptyMap(),
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId
        )
        _state.value = cur.copy(
            dubLines = normalized,
            speakerProfiles = cast,
            detectedCastSummary = "",
            scriptState = DubScriptUiState.Ready(normalized.size),
            renderState = DubRenderUiState.Idle
        )

        // Optionally auto-translate foreign .SRT offline using free ML Kit Tagalog + analyze MP4 speakers!
        scope.launch {
            val maybeTranslated = if (translateToTagalog) {
                offlineTranslator.translateLinesToTagalog(normalized, _state.value.sourceLanguage)
                    .getOrDefault(normalized)
            } else normalized

            val analyzed = if (inspection != null) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    mp4SpeakerAnalyzer.analyzeMp4Speakers(inspection.uriString, maybeTranslated)
                }
            } else {
                Mp4SpeakerAnalysisResult(maybeTranslated, emptyMap(), emptyMap(), "")
            }

            val latest = _state.value
            val updatedCast = DubTimelineMath.buildLockedCharacterCast(
                lines = analyzed.refinedLines,
                existingCast = emptyMap(),
                basePitch = latest.voicePitch,
                availableVoices = latest.androidVoices,
                selectedVoiceId = latest.selectedAndroidVoiceId,
                detectedPitchHzMap = analyzed.speakerPitchHz
            )
            _state.value = latest.copy(
                dubLines = analyzed.refinedLines,
                speakerProfiles = updatedCast,
                detectedCastSummary = analyzed.summary,
                scriptState = DubScriptUiState.Ready(analyzed.refinedLines.size)
            )
        }
        return normalized.size
    }

    fun loadTranscriptIntoDubber(transcript: VideoTranscript) {
        val rawLines = DubTimelineMath.fromVideoTranscript(transcript).map {
            it.copy(
                speaker = DubTimelineMath.canonicalizeSpeakerName(it.speaker),
                originalText = it.filipinoText
            )
        }
        val cur = _state.value
        val cast = DubTimelineMath.buildLockedCharacterCast(
            lines = rawLines,
            existingCast = emptyMap(),
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId
        )
        _state.value = cur.copy(
            dubLines = rawLines,
            speakerProfiles = cast,
            scriptState = if (rawLines.isNotEmpty()) DubScriptUiState.Ready(rawLines.size) else DubScriptUiState.Idle,
            renderState = DubRenderUiState.Idle
        )

        if (rawLines.isNotEmpty() && transcript.sourceUriString.isNotBlank()) {
            scope.launch {
                val analyzed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    mp4SpeakerAnalyzer.analyzeMp4Speakers(transcript.sourceUriString, rawLines)
                }
                val latest = _state.value
                val updatedCast = DubTimelineMath.buildLockedCharacterCast(
                    lines = analyzed.refinedLines,
                    existingCast = emptyMap(),
                    basePitch = latest.voicePitch,
                    availableVoices = latest.androidVoices,
                    selectedVoiceId = latest.selectedAndroidVoiceId,
                    detectedPitchHzMap = analyzed.speakerPitchHz
                )
                _state.value = latest.copy(
                    dubLines = analyzed.refinedLines,
                    speakerProfiles = updatedCast,
                    detectedCastSummary = analyzed.summary
                )
            }
        }
    }

    fun updateLineText(lineId: Long, newText: String) {
        val updated = _state.value.dubLines.map { line ->
            if (line.id == lineId) line.copy(filipinoText = newText) else line
        }
        _state.value = _state.value.copy(dubLines = updated)
    }

    fun removeLine(lineId: Long) {
        val cur = _state.value
        val updated = cur.dubLines.filterNot { it.id == lineId }
        val updatedCast = DubTimelineMath.buildLockedCharacterCast(
            lines = updated,
            existingCast = cur.speakerProfiles,
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId
        )
        _state.value = cur.copy(
            dubLines = updated,
            speakerProfiles = updatedCast,
            scriptState = if (updated.isNotEmpty()) DubScriptUiState.Ready(updated.size) else DubScriptUiState.Idle
        )
    }

    fun renderDubbedVideo(inspection: LocalVideoInspection?) {
        val cur = _state.value
        if (cur.renderState is DubRenderUiState.Rendering || cur.isAutoPipelineRunning) return
        if (inspection == null) {
            _state.value = cur.copy(
                renderState = DubRenderUiState.Error("Pumili muna ng video na idu-dub.")
            )
            return
        }
        if (cur.dubLines.isEmpty()) {
            _state.value = cur.copy(
                renderState = DubRenderUiState.Error("Gumawa o mag-import muna ng timestamped Filipino script, o gamitin ang 1-Tap Auto-Dub.")
            )
            return
        }

        val pitchHints = cur.speakerProfiles.mapValues { it.value.detectedPitchHz }
        val lockedCast = DubTimelineMath.buildLockedCharacterCast(
            lines = cur.dubLines,
            existingCast = cur.speakerProfiles,
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId,
            detectedPitchHzMap = pitchHints
        )

        scope.launch {
            _state.value = _state.value.copy(
                speakerProfiles = lockedCast,
                renderState = DubRenderUiState.Rendering("Initializing Filipino voice engine...", 0.02f)
            )
            DubbingForegroundService.startOrUpdate(
                context = appContext,
                title = "Dubbing Video to Filipino",
                message = "Initializing Filipino voice engine...",
                progressPercent = 2
            )

            var lastNotifPct = -1
            val result = videoDubber.renderDubbedVideo(
                sourceUriString = inspection.uriString,
                sourceDurationMs = inspection.durationMs,
                lines = cur.dubLines,
                voiceEngine = cur.voiceEngine,
                androidVoiceId = cur.selectedAndroidVoiceId,
                availableAndroidVoices = cur.androidVoices,
                lockedSpeakerProfiles = lockedCast,
                voicePitch = cur.voicePitch,
                baseSpeechRate = cur.baseSpeechRate,
                duckingMode = cur.duckingMode,
                multiSpeakerEnabled = cur.multiSpeakerEnabled,
                onStageUpdate = { stage, frac ->
                    _state.value = _state.value.copy(
                        renderState = DubRenderUiState.Rendering(stage, frac)
                    )
                    val pct = frac?.let { (it * 100f).toInt().coerceIn(0, 100) }
                    if (pct == null || pct >= lastNotifPct + 3 || pct >= 75) {
                        if (pct != null) lastNotifPct = pct
                        DubbingForegroundService.startOrUpdate(
                            context = appContext,
                            title = "Dubbing Video to Filipino",
                            message = stage,
                            progressPercent = pct
                        )
                    }
                }
            )
            result.fold(
                onSuccess = { dubbed ->
                    _state.value = _state.value.copy(
                        renderState = DubRenderUiState.Success(dubbed)
                    )
                    DubbingForegroundService.completeAndStop(
                        context = appContext,
                        title = "Tapos na! Filipino Dubbed MP4 Ready",
                        message = "${dubbed.fileName} (${dubbed.segmentCount} lines) saved to Gallery."
                    )
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Hindi natapos ang video dubbing render."
                    _state.value = _state.value.copy(
                        renderState = DubRenderUiState.Error(msg)
                    )
                    DubbingForegroundService.failAndStop(
                        context = appContext,
                        title = "Hindi natapos ang dubbing",
                        message = msg
                    )
                }
            )
        }
    }

    companion object {
        @Volatile
        private var instance: DubbingJobCoordinator? = null

        fun getInstance(context: Context): DubbingJobCoordinator {
            return instance ?: synchronized(this) {
                instance ?: DubbingJobCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
