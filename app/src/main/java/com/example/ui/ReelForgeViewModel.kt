package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadedClipEntity
import com.example.data.local.PromptBlueprintEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.data.local.VaultRepository
import com.example.data.model.DownloadedVideoResult
import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.LocalVideoInspection
import com.example.data.model.PromptBlueprint
import com.example.data.model.PromptUpgradePreset
import com.example.data.model.SocialPlatform
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.VideoAspectRatio
import com.example.data.network.GeminiVideoAnalyzerRepository
import com.example.data.network.PromptBlueprintSerializer
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
        val totalBytes: Long
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

sealed interface ApiKeyCheckState {
    data object Idle : ApiKeyCheckState
    data object Verifying : ApiKeyCheckState
    data class Valid(
        val message: String,
        val liveModels: List<Pair<String, String>> = emptyList()
    ) : ApiKeyCheckState
    data class Invalid(val message: String) : ApiKeyCheckState
}

class ReelForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsDataStore(application)
    private val vaultRepository = VaultRepository(AppDatabase.getInstance(application).vaultDao())
    private val videoExtractor = VideoExtractorRepository(application)
    private val frameExtractor = VideoFrameExtractor(application)
    private val geminiAnalyzer = GeminiVideoAnalyzerRepository(application)

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

    private val _apiKeyCheckState = MutableStateFlow<ApiKeyCheckState>(ApiKeyCheckState.Idle)
    val apiKeyCheckState: StateFlow<ApiKeyCheckState> = _apiKeyCheckState.asStateFlow()

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
                "Mag-paste muna ng TikTok, Facebook, o Instagram video URL."
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
                totalBytes = -1L
            )
            try {
                val preferHd = userSettings.value.preferHdDownload
                val dlResult = videoExtractor.downloadVideoToDevice(info, preferHd) { copied, total ->
                    val fraction = if (total > 0L) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                    _downloaderState.value = DownloaderUiState.Downloading(
                        info = info,
                        progress = fraction,
                        bytesCopied = copied,
                        totalBytes = total
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
            try {
                val inspection = frameExtractor.inspectVideo(uri)
                _selectedVideo.value = inspection
                _studioState.value = StudioAnalysisUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _studioState.value = StudioAnalysisUiState.Error(
                    "Hindi mabasa ang video: ${e.localizedMessage ?: "Unsupported format"}"
                )
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
                if (count > 0) "Saved $count keyframes to Pictures/ReelForge!"
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
                "Ilagay muna ang iyong Gemini API key sa API Key settings."
            )
            return
        }

        viewModelScope.launch {
            _studioState.value = StudioAnalysisUiState.Analyzing("Preparing video...")
            try {
                val result = geminiAnalyzer.analyzeVideoForPrompts(
                    apiKey = settings.geminiApiKey,
                    modelId = settings.selectedGeminiModel,
                    inspection = inspection,
                    targetGenerator = settings.targetGenerator,
                    aspectRatio = _selectedAspectRatio.value,
                    upgradePreset = _selectedUpgradePreset.value,
                    extraCreatorNotes = _extraCreatorNotes.value.trim(),
                    onStageUpdate = { stage ->
                        _studioState.value = StudioAnalysisUiState.Analyzing(stage)
                    }
                )

                result.fold(
                    onSuccess = { blueprint ->
                        val savedId = vaultRepository.savePromptBlueprint(blueprint)
                        _studioState.value = StudioAnalysisUiState.Success(
                            blueprint.copy(id = savedId)
                        )
                    },
                    onFailure = { err ->
                        _studioState.value = StudioAnalysisUiState.Error(
                            err.localizedMessage ?: "Hindi nagtagumpay ang Gemini analysis."
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
    // SETTINGS & GEMINI API KEY ACTIONS
    // =========================================================================
    fun saveAndVerifyApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        viewModelScope.launch {
            settingsStore.saveGeminiApiKey(cleaned)
            if (cleaned.isEmpty()) {
                _apiKeyCheckState.value = ApiKeyCheckState.Idle
                return@launch
            }
            _apiKeyCheckState.value = ApiKeyCheckState.Verifying
            val res = geminiAnalyzer.verifyApiKey(cleaned)
            if (res.isValid) {
                val currentModel = userSettings.value.selectedGeminiModel
                val isCurrentAvailable = res.availableModels.any { it.first == currentModel }
                if (!isCurrentAvailable && !res.recommendedModelId.isNullOrBlank()) {
                    settingsStore.saveGeminiModel(res.recommendedModelId)
                }
                _apiKeyCheckState.value = ApiKeyCheckState.Valid(
                    message = res.message,
                    liveModels = res.availableModels
                )
            } else {
                _apiKeyCheckState.value = ApiKeyCheckState.Invalid(res.message)
            }
        }
    }

    fun resetKeyCheckState() {
        _apiKeyCheckState.value = ApiKeyCheckState.Idle
    }

    fun saveGeminiModel(modelId: String) {
        viewModelScope.launch { settingsStore.saveGeminiModel(modelId) }
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
}
