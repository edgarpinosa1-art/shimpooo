package com.example.data.dubbing

import android.content.Context
import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubSourceLanguage
import com.example.data.model.DubVoiceEngine
import com.example.data.model.LocalVideoInspection
import com.example.data.model.MmsModelStatus
import com.example.data.model.VideoTranscript
import com.example.data.network.GeminiVideoAnalyzerRepository
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
    val androidTts = AndroidTtsSynthesizer(appContext)
    private val videoDubber = VideoDubberEngine(appContext, mmsOnnxEngine, androidTts)
    private val geminiAnalyzer = GeminiVideoAnalyzerRepository(appContext)

    private val _state = MutableStateFlow(DubberPanelState())
    val state: StateFlow<DubberPanelState> = _state.asStateFlow()

    init {
        refreshEnvironment()
    }

    fun isBackgroundWorkActive(): Boolean {
        val cur = _state.value
        return cur.mmsModelStatus == MmsModelStatus.DOWNLOADING ||
            cur.scriptState is DubScriptUiState.Generating ||
            cur.renderState is DubRenderUiState.Rendering
    }

    fun refreshEnvironment() {
        val ready = mmsOnnxEngine.isModelReady()
        val sizeMb = mmsOnnxEngine.getInstalledSizeMb()
        val curStatus = _state.value.mmsModelStatus
        _state.value = _state.value.copy(
            mmsModelStatus = if (curStatus == MmsModelStatus.DOWNLOADING) {
                MmsModelStatus.DOWNLOADING
            } else if (ready) {
                MmsModelStatus.READY
            } else {
                MmsModelStatus.NOT_INSTALLED
            },
            mmsModelSizeMb = sizeMb
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
        val rebuiltCast = DubTimelineMath.buildLockedCharacterCast(
            lines = cur.dubLines,
            existingCast = emptyMap(),
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = voiceId
        )
        _state.value = cur.copy(
            selectedAndroidVoiceId = voiceId,
            speakerProfiles = rebuiltCast
        )
    }

    fun updateVoicePitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.75f, 1.35f)
        val cur = _state.value
        val rebuiltCast = DubTimelineMath.buildLockedCharacterCast(
            lines = cur.dubLines,
            existingCast = emptyMap(),
            basePitch = clamped,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId
        )
        _state.value = cur.copy(
            voicePitch = clamped,
            speakerProfiles = rebuiltCast
        )
    }

    fun updateBaseSpeed(rate: Float) {
        _state.value = _state.value.copy(baseSpeechRate = rate.coerceIn(0.80f, 1.35f))
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

    fun generateFilipinoDubScript(
        inspection: LocalVideoInspection?,
        apiKey: String,
        modelId: String
    ) {
        if (_state.value.scriptState is DubScriptUiState.Generating) return
        if (inspection == null) {
            _state.value = _state.value.copy(
                scriptState = DubScriptUiState.Error("Pumili muna ng video mula sa device o downloader.")
            )
            return
        }
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                scriptState = DubScriptUiState.Error("Ilagay muna ang iyong Gemini API key (free tier) o i-import ang .SRT offline.")
            )
            return
        }
        scope.launch {
            _state.value = _state.value.copy(
                scriptState = DubScriptUiState.Generating("Preparing video for lip-timed translation...")
            )
            DubbingForegroundService.startOrUpdate(
                context = appContext,
                title = "Translating to Filipino",
                message = "Preparing ${inspection.displayName}...",
                progressPercent = null
            )

            try {
                val result = geminiAnalyzer.generateIsochronicFilipinoDubScript(
                    apiKey = apiKey,
                    modelId = modelId,
                    inspection = inspection,
                    sourceLanguage = _state.value.sourceLanguage,
                    onStageUpdate = { stage ->
                        _state.value = _state.value.copy(
                            scriptState = DubScriptUiState.Generating(stage)
                        )
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
                        val cur = _state.value
                        val cast = DubTimelineMath.buildLockedCharacterCast(
                            lines = lines,
                            existingCast = emptyMap(),
                            basePitch = cur.voicePitch,
                            availableVoices = cur.androidVoices,
                            selectedVoiceId = cur.selectedAndroidVoiceId
                        )
                        _state.value = cur.copy(
                            dubLines = lines,
                            speakerProfiles = cast,
                            scriptState = DubScriptUiState.Ready(lines.size),
                            renderState = DubRenderUiState.Idle
                        )
                        DubbingForegroundService.completeAndStop(
                            context = appContext,
                            title = "Tapos na ang Filipino Script (${lines.size} lines)",
                            message = "${cast.size} character(s) locked · Handa nang i-render."
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

    fun importOfflineScript(rawScript: String): Int {
        val parsed = DubTimelineMath.parseSrtOrTimedScript(rawScript)
        if (parsed.isEmpty()) return 0
        val normalized = parsed.map {
            it.copy(speaker = DubTimelineMath.canonicalizeSpeakerName(it.speaker))
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
            scriptState = DubScriptUiState.Ready(normalized.size),
            renderState = DubRenderUiState.Idle
        )
        return normalized.size
    }

    fun loadTranscriptIntoDubber(transcript: VideoTranscript) {
        val lines = DubTimelineMath.fromVideoTranscript(transcript).map {
            it.copy(speaker = DubTimelineMath.canonicalizeSpeakerName(it.speaker))
        }
        val cur = _state.value
        val cast = DubTimelineMath.buildLockedCharacterCast(
            lines = lines,
            existingCast = emptyMap(),
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId
        )
        _state.value = cur.copy(
            dubLines = lines,
            speakerProfiles = cast,
            scriptState = if (lines.isNotEmpty()) DubScriptUiState.Ready(lines.size) else DubScriptUiState.Idle,
            renderState = DubRenderUiState.Idle
        )
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
        // Keep existing character locks intact even if a line is removed
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
        if (cur.renderState is DubRenderUiState.Rendering) return
        if (inspection == null) {
            _state.value = cur.copy(
                renderState = DubRenderUiState.Error("Pumili muna ng video na idu-dub.")
            )
            return
        }
        if (cur.dubLines.isEmpty()) {
            _state.value = cur.copy(
                renderState = DubRenderUiState.Error("Gumawa o mag-import muna ng timestamped Filipino script.")
            )
            return
        }

        val lockedCast = DubTimelineMath.buildLockedCharacterCast(
            lines = cur.dubLines,
            existingCast = cur.speakerProfiles,
            basePitch = cur.voicePitch,
            availableVoices = cur.androidVoices,
            selectedVoiceId = cur.selectedAndroidVoiceId
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
