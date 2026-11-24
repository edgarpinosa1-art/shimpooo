package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.dubbing.DubbingJobCoordinator
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadedClipEntity
import com.example.data.local.PromptBlueprintEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.data.local.VaultRepository
import com.example.data.local.VideoTranscriptEntity
import com.example.data.model.AiProvider
import com.example.data.model.DetectedAiModel
import com.example.data.model.DownloadedVideoResult
import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubSourceLanguage
import com.example.data.model.DubVoiceEngine
import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.LocalVideoInspection
import com.example.data.model.MmsModelStatus
import com.example.data.model.ModelCapabilityTag
import com.example.data.model.PromptBlueprint
import com.example.data.model.PromptUpgradePreset
import com.example.data.model.SocialPlatform
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.TranscriptLanguageMode
import com.example.data.model.VideoAspectRatio
import com.example.data.model.VideoTranscript
import com.example.data.network.GeminiVideoAnalyzerRepository
import com.example.data.network.PromptBlueprintSerializer
import com.example.data.network.ThirdPartyAiRepository
import com.example.data.network.TranscriptSerializer
import com.example.data.network.VideoExtractorRepository
import com.example.data.network.VideoFrameExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface DownloaderUiState {
    data object Idle : DownloaderUiState
    data object Extracting : DownloaderUiState
    data class Extracted(val info: ExtractedVideoInfo) : DownloaderUiState
    data class Downloading(
        val info: ExtractedVideoInfo,
        val progress: Float?,
        val bytesCopied: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L,
        val activeConnections: Int = 1
    ) : DownloaderUiState
    data class Downloaded(
        val info: ExtractedVideoInfo,
        val result: DownloadedVideoResult
    ) : DownloaderUiState
    data class Error(val message: String) : DownloaderUiState
}

sealed interface StudioAnalysisUiState {
    data object Idle : StudioAnalysisUiState
    data object InspectingVideo : StudioAnalysisUiState
    data class Analyzing(val stage: String) : StudioAnalysisUiState
    data class Success(val blueprint: PromptBlueprint) : StudioAnalysisUiState
    data class Error(val message: String) : StudioAnalysisUiState
}

sealed interface TranscriptUiState {
    data object Idle : TranscriptUiState
    data object InspectingVideo : TranscriptUiState
    data class Transcribing(val stage: String) : TranscriptUiState
    data class Success(val transcript: VideoTranscript) : TranscriptUiState
    data class Error(val message: String) : TranscriptUiState
}

sealed interface ApiKeyCheckState {
    data object Idle : ApiKeyCheckState
    data object Verifying : ApiKeyCheckState
    data class Valid(
        val message: String,
        val provider: AiProvider = AiProvider.GEMINI,
        val liveModels: List<Pair<String, String>> = emptyList(),
        val detectedModels: List<DetectedAiModel> = emptyList(),
        val recommendedSttModel: String? = null,
        val recommendedChatModel: String? = null
    ) : ApiKeyCheckState
    data class Invalid(val message: String) : ApiKeyCheckState
}

class ReelForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsDataStore(application)
    private val vaultRepository = VaultRepository(AppDatabase.getInstance(application).vaultDao())
    private val videoExtractor = VideoExtractorRepository(application)
    private val frameExtractor = VideoFrameExtractor(application)
    private val geminiAnalyzer = GeminiVideoAnalyzerRepository(application)
    private val thirdPartyAi = ThirdPartyAiRepository(application)
    private val dubbingCoordinator = DubbingJobCoordinator.getInstance(application)

    val userSettings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserSettings()
        )

    val downloadedClips: StateFlow<List<DownloadedClipEntity>> = vaultRepository.downloadedClips
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val promptBlueprints: StateFlow<List<PromptBlueprintEntity>> = vaultRepository.promptBlueprints
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val videoTranscripts: StateFlow<List<VideoTranscriptEntity>> = vaultRepository.videoTranscripts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _detectedPlatform = MutableStateFlow(SocialPlatform.UNKNOWN)
    val detectedPlatform: StateFlow<SocialPlatform> = _detectedPlatform.asStateFlow()

    private val _downloaderState = MutableStateFlow<DownloaderUiState>(DownloaderUiState.Idle)
    val downloaderState: StateFlow<DownloaderUiState> = _downloaderState.asStateFlow()

    private val _selectedVideo = MutableStateFlow<LocalVideoInspection?>(null)
    val selectedVideo: StateFlow<LocalVideoInspection?> = _selectedVideo.asStateFlow()

    private val _selectedAspectRatio = MutableStateFlow(VideoAspectRatio.PORTRAIT_9_16)
    val selectedAspectRatio: StateFlow<VideoAspectRatio> = _selectedAspectRatio.asStateFlow()

    private val _selectedUpgradePreset = MutableStateFlow(PromptUpgradePreset.CINEMATIC_UPGRADE)
    val selectedUpgradePreset: StateFlow<PromptUpgradePreset> = _selectedUpgradePreset.asStateFlow()

    private val _extraCreatorNotes = MutableStateFlow("")
    val extraCreatorNotes: StateFlow<String> = _extraCreatorNotes.asStateFlow()

    private val _studioState = MutableStateFlow<StudioAnalysisUiState>(StudioAnalysisUiState.Idle)
    val studioState: StateFlow<StudioAnalysisUiState> = _studioState.asStateFlow()

    private val _transcriptLanguageMode = MutableStateFlow(TranscriptLanguageMode.ORIGINAL_VERBATIM)
    val transcriptLanguageMode: StateFlow<TranscriptLanguageMode> = _transcriptLanguageMode.asStateFlow()

    private val _transcriptState = MutableStateFlow<TranscriptUiState>(TranscriptUiState.Idle)
    val transcriptState: StateFlow<TranscriptUiState> = _transcriptState.asStateFlow()

    private val _apiKeyCheckState = MutableStateFlow<ApiKeyCheckState>(ApiKeyCheckState.Idle)
    val apiKeyCheckState: StateFlow<ApiKeyCheckState> = _apiKeyCheckState.asStateFlow()

    val dubberState: StateFlow<DubberPanelState> = dubbingCoordinator.state

    init {
        dubbingCoordinator.refreshEnvironment()
    }

    // =========================================================================
    // DOWNLOADER ACTIONS
    // =========================================================================
    fun updateUrlInput(text: String) {
        _urlInput.value = text
        val extracted = videoExtractor.extractFirstUrl(text) ?: text
        _detectedPlatform.value = SocialPlatform.detectFromUrl(extracted)
    }

    fun handleSharedTextUrl(sharedText: String) {
        updateUrlInput(sharedText)
        extractVideoFromUrl()
    }

    fun clearDownloader() {
        _urlInput.value = ""
        _detectedPlatform.value = SocialPlatform.UNKNOWN
        _downloaderState.value = DownloaderUiState.Idle
    }

    fun extractVideoFromUrl() {
        val raw = _urlInput.value.trim()
        if (raw.isEmpty()) {
            _downloaderState.value = DownloaderUiState.Error(
                "Mag-paste muna ng TikTok, Facebook, Instagram, YouTube, o Pinterest video URL."
            )
            return
        }

        viewModelScope.launch {
            _downloaderState.value = DownloaderUiState.Extracting
            try {
                val cobaltUrl = userSettings.value.customCobaltUrl
                val result = videoExtractor.extractVideoInfo(raw, cobaltUrl)
                result.fold(
                    onSuccess = { info ->
                        _detectedPlatform.value = info.platform
                        _downloaderState.value = DownloaderUiState.Extracted(info)
                    },
                    onFailure = { err ->
                        _downloaderState.value = DownloaderUiState.Error(
                            err.localizedMessage ?: "Hindi ma-extract ang video."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _downloaderState.value = DownloaderUiState.Error(
                    e.localizedMessage ?: "Extraction failed."
                )
            }
        }
    }

    fun downloadExtractedVideo(info: ExtractedVideoInfo) {
        viewModelScope.launch {
            _downloaderState.value = DownloaderUiState.Downloading(
                info = info,
                progress = null,
                bytesCopied = 0L,
                totalBytes = -1L,
                speedBytesPerSec = 0L,
                activeConnections = 1
            )
            try {
                val preferHd = userSettings.value.preferHdDownload
                var sampleStartMs = System.currentTimeMillis()
                var sampleStartBytes = 0L
                var currentSpeed = 0L

                val dlResult = videoExtractor.downloadVideoToDevice(info, preferHd) { copied, total, threads ->
                    val now = System.currentTimeMillis()
                    val elapsedMs = now - sampleStartMs
                    if (elapsedMs >= 450L) {
                        val deltaBytes = (copied - sampleStartBytes).coerceAtLeast(0L)
                        currentSpeed = (deltaBytes * 1000L) / elapsedMs
                        sampleStartMs = now
                        sampleStartBytes = copied
                    }
                    val fraction = if (total > 0L) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                    _downloaderState.value = DownloaderUiState.Downloading(
                        info = info,
                        progress = fraction,
                        bytesCopied = copied,
                        totalBytes = total,
                        speedBytesPerSec = currentSpeed,
                        activeConnections = threads
                    )
                }
                dlResult.fold(
                    onSuccess = { saved ->
                        vaultRepository.saveDownloadedClip(info, saved)
                        _downloaderState.value = DownloaderUiState.Downloaded(info, saved)
                    },
                    onFailure = { err ->
                        _downloaderState.value = DownloaderUiState.Error(
                            err.localizedMessage ?: "Download failed."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _downloaderState.value = DownloaderUiState.Error(
                    e.localizedMessage ?: "Download failed."
                )
            }
        }
    }

    fun downloadAudioTrack(info: ExtractedVideoInfo, onResultMessage: (String) -> Unit) {
        viewModelScope.launch {
            val res = videoExtractor.downloadAudioToDevice(info)
            res.fold(
                onSuccess = { msg -> onResultMessage(msg) },
                onFailure = { err -> onResultMessage(err.localizedMessage ?: "Audio download failed.") }
            )
        }
    }

    // =========================================================================
    // PROMPT STUDIO ACTIONS
    // =========================================================================
    fun onLocalVideoSelected(uri: Uri) {
        viewModelScope.launch {
            _studioState.value = StudioAnalysisUiState.InspectingVideo
            _transcriptState.value = TranscriptUiState.InspectingVideo
            try {
                val inspection = frameExtractor.inspectVideo(uri)
                _selectedVideo.value = inspection
                _studioState.value = StudioAnalysisUiState.Idle
                _transcriptState.value = TranscriptUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "Hindi mabasa ang video: ${e.localizedMessage ?: "Unsupported format"}"
                _studioState.value = StudioAnalysisUiState.Error(msg)
                _transcriptState.value = TranscriptUiState.Error(msg)
            }
        }
    }

    fun loadDownloadedClipIntoStudio(localFilePath: String, contentUriString: String) {
        val localFile = File(localFilePath)
        val uri = if (localFile.exists()) {
            Uri.fromFile(localFile)
        } else {
            Uri.parse(contentUriString)
        }
        onLocalVideoSelected(uri)
    }

    fun updateExtraCreatorNotes(notes: String) {
        _extraCreatorNotes.value = notes
    }

    fun selectAspectRatio(ratio: VideoAspectRatio) {
        _selectedAspectRatio.value = ratio
    }

    fun selectUpgradePreset(preset: PromptUpgradePreset) {
        _selectedUpgradePreset.value = preset
    }

    fun saveSelectedKeyframesToGallery(onComplete: (String) -> Unit) {
        val frames = _selectedVideo.value?.keyframePaths.orEmpty()
        if (frames.isEmpty()) {
            onComplete("No keyframes to save.")
            return
        }
        viewModelScope.launch {
            val count = frameExtractor.saveKeyframesToGallery(frames)
            onComplete(
                if (count > 0) "Saved $count keyframes to Pictures/GizmoohDownloader!"
                else "Could not save keyframes."
            )
        }
    }

    fun analyzeSelectedVideo() {
        val inspection = _selectedVideo.value
        if (inspection == null) {
            _studioState.value = StudioAnalysisUiState.Error("Pumili muna ng video mula sa device o downloader.")
            return
        }
        val settings = userSettings.value
        if (!settings.hasApiKey) {
            _studioState.value = StudioAnalysisUiState.Error(
                "Ilagay muna ang iyong ${settings.activeProvider.shortBadge} API key sa API Key settings."
            )
            return
        }

        viewModelScope.launch {
            _studioState.value = StudioAnalysisUiState.Analyzing("Preparing video via ${settings.activeProvider.shortBadge}...")
            try {
                val result = if (settings.activeProvider == AiProvider.GEMINI) {
                    geminiAnalyzer.analyzeVideoForPrompts(
                        apiKey = settings.geminiApiKey,
                        modelId = settings.selectedGeminiModel,
                        inspection = inspection,
                        targetGenerator = settings.targetGenerator,
                        aspectRatio = _selectedAspectRatio.value,
                        upgradePreset = _selectedUpgradePreset.value,
                        extraCreatorNotes = _extraCreatorNotes.value.trim(),
                        onStageUpdate = { stage ->
                            _studioState.value = StudioAnalysisUiState.Analyzing(stage)
                        },
                        onModelAutoFallback = { fallbackModel ->
                            viewModelScope.launch { settingsStore.saveGeminiModel(fallbackModel) }
                        }
                    )
                } else {
                    thirdPartyAi.analyzeVideoForPromptsWithThirdParty(
                        provider = settings.activeProvider,
                        apiKey = settings.thirdPartyApiKey,
                        baseUrl = settings.effectiveThirdPartyBaseUrl,
                        chatModelId = settings.selectedChatModel,
                        sttModelId = settings.selectedSttModel,
                        inspection = inspection,
                        targetGenerator = settings.targetGenerator,
                        aspectRatio = _selectedAspectRatio.value,
                        upgradePreset = _selectedUpgradePreset.value,
                        extraNotes = _extraCreatorNotes.value.trim(),
                        onStageUpdate = { stage ->
                            _studioState.value = StudioAnalysisUiState.Analyzing(stage)
                        },
                        onModelsAutoUpdated = { newStt, newChat ->
                            viewModelScope.launch {
                                if (!newStt.isNullOrBlank()) settingsStore.saveSelectedSttModel(newStt)
                                if (!newChat.isNullOrBlank()) settingsStore.saveSelectedChatModel(newChat)
                            }
                        }
                    )
                }

                result.fold(
                    onSuccess = { blueprint ->
                        val savedId = vaultRepository.savePromptBlueprint(blueprint)
                        _studioState.value = StudioAnalysisUiState.Success(
                            blueprint.copy(id = savedId)
                        )
                    },
                    onFailure = { err ->
                        _studioState.value = StudioAnalysisUiState.Error(
                            err.localizedMessage ?: "Hindi nagtagumpay ang video analysis."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _studioState.value = StudioAnalysisUiState.Error(
                    e.localizedMessage ?: "Analysis failed."
                )
            }
        }
    }

    fun openBlueprintFromVault(entity: PromptBlueprintEntity) {
        runCatching {
            val generator = TargetVideoGenerator.entries.firstOrNull {
                it.name == entity.targetGeneratorName
            } ?: TargetVideoGenerator.VEO_3

            val blueprint = PromptBlueprintSerializer.fromGeminiOrStoredJson(
                jsonString = entity.payloadJson,
                id = entity.id,
                videoTitle = entity.videoTitle,
                sourceUriString = entity.sourceUriString,
                targetGenerator = generator,
                geminiModelUsed = entity.geminiModelUsed,
                createdAt = entity.createdAt
            )
            _studioState.value = StudioAnalysisUiState.Success(blueprint)
        }.onFailure { err ->
            _studioState.value = StudioAnalysisUiState.Error(
                err.localizedMessage ?: "Could not load blueprint."
            )
        }
    }

    // =========================================================================
    // TRANSCRIPT STUDIO ACTIONS
    // =========================================================================
    fun selectTranscriptLanguageMode(mode: TranscriptLanguageMode) {
        _transcriptLanguageMode.value = mode
    }

    fun transcribeSelectedVideo() {
        val inspection = _selectedVideo.value
        if (inspection == null) {
            _transcriptState.value = TranscriptUiState.Error("Pumili muna ng video mula sa device o downloader.")
            return
        }
        val settings = userSettings.value
        if (!settings.hasApiKey) {
            _transcriptState.value = TranscriptUiState.Error(
                "Ilagay muna ang iyong ${settings.activeProvider.shortBadge} API key sa API Key settings."
            )
            return
        }

        viewModelScope.launch {
            _transcriptState.value = TranscriptUiState.Transcribing(
                "Preparing audio via ${settings.activeProvider.shortBadge}..."
            )
            try {
                val result = if (settings.activeProvider == AiProvider.GEMINI) {
                    geminiAnalyzer.transcribeVideoWithTimestamps(
                        apiKey = settings.geminiApiKey,
                        modelId = settings.selectedGeminiModel,
                        inspection = inspection,
                        languageMode = _transcriptLanguageMode.value,
                        onStageUpdate = { stage ->
                            _transcriptState.value = TranscriptUiState.Transcribing(stage)
                        },
                        onModelAutoFallback = { fallbackModel ->
                            viewModelScope.launch { settingsStore.saveGeminiModel(fallbackModel) }
                        }
                    )
                } else {
                    thirdPartyAi.transcribeVideoWithThirdParty(
                        provider = settings.activeProvider,
                        apiKey = settings.thirdPartyApiKey,
                        baseUrl = settings.effectiveThirdPartyBaseUrl,
                        sttModelId = settings.selectedSttModel,
                        chatModelId = settings.selectedChatModel,
                        inspection = inspection,
                        languageMode = _transcriptLanguageMode.value,
                        onStageUpdate = { stage ->
                            _transcriptState.value = TranscriptUiState.Transcribing(stage)
                        },
                        onModelsAutoUpdated = { newStt, newChat ->
                            viewModelScope.launch {
                                if (!newStt.isNullOrBlank()) settingsStore.saveSelectedSttModel(newStt)
                                if (!newChat.isNullOrBlank()) settingsStore.saveSelectedChatModel(newChat)
                            }
                        }
                    )
                }

                result.fold(
                    onSuccess = { transcript ->
                        val savedId = vaultRepository.saveVideoTranscript(transcript)
                        _transcriptState.value = TranscriptUiState.Success(
                            transcript.copy(id = savedId)
                        )
                    },
                    onFailure = { err ->
                        _transcriptState.value = TranscriptUiState.Error(
                            err.localizedMessage ?: "Hindi nagtagumpay ang video transcription."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _transcriptState.value = TranscriptUiState.Error(
                    e.localizedMessage ?: "Transcription failed."
                )
            }
        }
    }

    fun openTranscriptFromVault(entity: VideoTranscriptEntity) {
        runCatching {
            val mode = TranscriptLanguageMode.entries.firstOrNull {
                it.name == entity.languageModeName
            } ?: TranscriptLanguageMode.ORIGINAL_VERBATIM

            val transcript = TranscriptSerializer.fromGeminiOrStoredJson(
                jsonString = entity.payloadJson,
                id = entity.id,
                videoTitle = entity.videoTitle,
                sourceUriString = entity.sourceUriString,
                geminiModelUsed = entity.geminiModelUsed,
                languageMode = mode,
                createdAt = entity.createdAt
            )
            _transcriptState.value = TranscriptUiState.Success(transcript)
        }.onFailure { err ->
            _transcriptState.value = TranscriptUiState.Error(
                err.localizedMessage ?: "Could not load transcript."
            )
        }
    }

    // =========================================================================
    // SETTINGS & MULTI-PROVIDER AI KEY ACTIONS
    // =========================================================================
    fun saveActiveProvider(provider: AiProvider) {
        viewModelScope.launch {
            settingsStore.saveActiveProvider(provider)
            _apiKeyCheckState.value = ApiKeyCheckState.Idle
        }
    }

    fun saveAndVerifyProviderConfig(
        provider: AiProvider,
        rawKey: String,
        rawBaseUrl: String
    ) {
        val cleanedKey = rawKey.trim()
        val cleanedUrl = rawBaseUrl.trim()
        viewModelScope.launch {
            settingsStore.saveActiveProvider(provider)
            if (provider == AiProvider.GEMINI) {
                settingsStore.saveGeminiApiKey(cleanedKey)
                if (cleanedKey.isEmpty()) {
                    _apiKeyCheckState.value = ApiKeyCheckState.Idle
                    return@launch
                }
                _apiKeyCheckState.value = ApiKeyCheckState.Verifying
                val res = geminiAnalyzer.verifyApiKey(cleanedKey)
                if (res.isValid) {
                    val currentModel = userSettings.value.selectedGeminiModel
                    val isCurrentAvailable = res.availableModels.any { it.first == currentModel }
                    if (!isCurrentAvailable && !res.recommendedModelId.isNullOrBlank()) {
                        settingsStore.saveGeminiModel(res.recommendedModelId)
                    }
                    _apiKeyCheckState.value = ApiKeyCheckState.Valid(
                        message = res.message,
                        provider = AiProvider.GEMINI,
                        liveModels = res.availableModels,
                        detectedModels = res.availableModels.map { (id, label) ->
                            DetectedAiModel(id, label, ModelCapabilityTag.MULTIMODAL_AUDIO)
                        }
                    )
                } else {
                    _apiKeyCheckState.value = ApiKeyCheckState.Invalid(res.message)
                }
            } else {
                settingsStore.saveThirdPartyConfig(provider, cleanedKey, cleanedUrl)
                if (cleanedKey.isEmpty()) {
                    _apiKeyCheckState.value = ApiKeyCheckState.Idle
                    return@launch
                }
                _apiKeyCheckState.value = ApiKeyCheckState.Verifying
                val res = thirdPartyAi.verifyAndDetectModels(provider, cleanedKey, cleanedUrl)
                if (res.isValid) {
                    val curSettings = userSettings.value
                    val liveIds = res.allModels.map { it.id }.toSet()
                    if (!res.recommendedSttModel.isNullOrBlank() && curSettings.selectedSttModel !in liveIds) {
                        settingsStore.saveSelectedSttModel(res.recommendedSttModel)
                    }
                    if (!res.recommendedChatModel.isNullOrBlank() && curSettings.selectedChatModel !in liveIds) {
                        settingsStore.saveSelectedChatModel(res.recommendedChatModel)
                    }
                    _apiKeyCheckState.value = ApiKeyCheckState.Valid(
                        message = res.message,
                        provider = provider,
                        liveModels = res.allModels.map { it.id to it.displayLabel },
                        detectedModels = res.allModels,
                        recommendedSttModel = res.recommendedSttModel,
                        recommendedChatModel = res.recommendedChatModel
                    )
                } else {
                    _apiKeyCheckState.value = ApiKeyCheckState.Invalid(res.message)
                }
            }
        }
    }

    fun resetKeyCheckState() {
        _apiKeyCheckState.value = ApiKeyCheckState.Idle
    }

    fun saveGeminiModel(modelId: String) {
        viewModelScope.launch { settingsStore.saveGeminiModel(modelId) }
    }

    fun saveSelectedSttModel(modelId: String) {
        viewModelScope.launch { settingsStore.saveSelectedSttModel(modelId) }
    }

    fun saveSelectedChatModel(modelId: String) {
        viewModelScope.launch { settingsStore.saveSelectedChatModel(modelId) }
    }

    fun saveTargetGenerator(generator: TargetVideoGenerator) {
        viewModelScope.launch { settingsStore.saveTargetGenerator(generator) }
    }

    fun savePreferHd(preferHd: Boolean) {
        viewModelScope.launch { settingsStore.savePreferHdDownload(preferHd) }
    }

    fun saveCustomCobaltUrl(url: String) {
        viewModelScope.launch { settingsStore.saveCustomCobaltUrl(url) }
    }

    // =========================================================================
    // VAULT ACTIONS
    // =========================================================================
    fun deleteClip(clip: DownloadedClipEntity) {
        viewModelScope.launch {
            runCatching { File(clip.localFilePath).delete() }
            vaultRepository.deleteClip(clip.id)
        }
    }

    fun deleteBlueprint(blueprint: PromptBlueprintEntity) {
        viewModelScope.launch {
            vaultRepository.deleteBlueprint(blueprint.id)
        }
    }

    fun deleteTranscript(transcript: VideoTranscriptEntity) {
        viewModelScope.launch {
            vaultRepository.deleteTranscript(transcript.id)
        }
    }

    // =========================================================================
    // AI FILIPINO DUBBER ACTIONS (PERSISTENT ACROSS BACK / BACKGROUND)
    // =========================================================================
    fun isDubbingBackgroundWorkActive(): Boolean = dubbingCoordinator.isBackgroundWorkActive()

    fun refreshDubberEnvironment() = dubbingCoordinator.refreshEnvironment()

    fun selectDubSourceLanguage(lang: DubSourceLanguage) = dubbingCoordinator.selectSourceLanguage(lang)

    fun selectDubVoiceEngine(engine: DubVoiceEngine) = dubbingCoordinator.selectVoiceEngine(engine)

    fun selectDubDuckingMode(mode: DubAudioDuckingMode) = dubbingCoordinator.selectDuckingMode(mode)

    fun selectAndroidDubVoice(voiceId: String) = dubbingCoordinator.selectAndroidVoice(voiceId)

    fun updateDubVoicePitch(pitch: Float) = dubbingCoordinator.updateVoicePitch(pitch)

    fun updateDubBaseSpeed(rate: Float) = dubbingCoordinator.updateBaseSpeed(rate)

    fun toggleMultiSpeaker(enabled: Boolean) = dubbingCoordinator.toggleMultiSpeaker(enabled)

    fun cycleCharacterVoice(speakerKey: String) = dubbingCoordinator.cycleCharacterVoice(speakerKey)

    fun downloadMmsTagalogModel() = dubbingCoordinator.downloadMmsTagalogModel()

    fun deleteMmsTagalogModel() = dubbingCoordinator.deleteMmsTagalogModel()

    fun generateFilipinoDubScript() {
        dubbingCoordinator.generateFilipinoDubScript(
            inspection = _selectedVideo.value,
            settings = userSettings.value
        )
    }

    fun importOfflineDubScript(rawScript: String, onFeedback: (String) -> Unit) {
        val count = dubbingCoordinator.importOfflineScript(rawScript)
        if (count <= 0) {
            onFeedback("Walang valid timestamp format. Gumamit ng SRT o [00:01 - 00:04] Teksto.")
        } else {
            onFeedback("Na-import ang $count timed Filipino dub lines!")
        }
    }

    fun loadTranscriptIntoDubber(transcript: VideoTranscript) {
        dubbingCoordinator.loadTranscriptIntoDubber(transcript)
    }

    fun updateDubLineFilipinoText(lineId: Long, newText: String) {
        dubbingCoordinator.updateLineText(lineId, newText)
    }

    fun removeDubLine(lineId: Long) {
        dubbingCoordinator.removeLine(lineId)
    }

    fun renderFilipinoDubbedVideo() {
        dubbingCoordinator.renderDubbedVideo(_selectedVideo.value)
    }
}
