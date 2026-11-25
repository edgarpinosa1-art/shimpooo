package com.example.ui

import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubLineItem
import com.example.data.model.DubSourceLanguage
import com.example.data.model.DubVoiceEngine
import com.example.data.model.DubVoiceOption
import com.example.data.model.DubbedVideoResult
import com.example.data.model.MmsModelStatus
import com.example.data.model.SpeakerVoiceProfile
import com.example.data.model.WhisperBrainTier

sealed interface DubScriptUiState {
    data object Idle : DubScriptUiState
    data class Generating(val stage: String) : DubScriptUiState
    data class Ready(val lineCount: Int) : DubScriptUiState
    data class Error(val message: String) : DubScriptUiState
}

sealed interface DubRenderUiState {
    data object Idle : DubRenderUiState
    data class Rendering(val stage: String, val progress: Float?) : DubRenderUiState
    data class Success(val result: DubbedVideoResult) : DubRenderUiState
    data class Error(val message: String) : DubRenderUiState
}

data class DubberPanelState(
    val sourceLanguage: DubSourceLanguage = DubSourceLanguage.AUTO_DETECT,
    val voiceEngine: DubVoiceEngine = DubVoiceEngine.ANDROID_FIL_PH,
    val duckingMode: DubAudioDuckingMode = DubAudioDuckingMode.STRIP_VOCALS_KEEP_BGM,
    val androidVoices: List<DubVoiceOption> = emptyList(),
    val selectedAndroidVoiceId: String = "",
    val voicePitch: Float = 1.0f,
    val baseSpeechRate: Float = 1.0f,
    val multiSpeakerEnabled: Boolean = true,
    val speakerProfiles: Map<String, SpeakerVoiceProfile> = emptyMap(),
    val detectedCastSummary: String = "",
    val isAutoPipelineRunning: Boolean = false,
    val autoStartOnVideoDrop: Boolean = true,
    val mmsModelStatus: MmsModelStatus = MmsModelStatus.NOT_INSTALLED,
    val mmsModelSizeMb: Double = 0.0,
    val mmsDownloadStage: String = "",
    val mmsDownloadProgress: Float? = null,
    val whisperBrainTier: WhisperBrainTier = WhisperBrainTier.LARGE_V3_TURBO,
    val whisperModelStatus: MmsModelStatus = MmsModelStatus.NOT_INSTALLED,
    val whisperModelSizeMb: Double = 0.0,
    val whisperDownloadStage: String = "",
    val whisperDownloadProgress: Float? = null,
    val preferOfflineWhisperBrain: Boolean = true,
    val dubLines: List<DubLineItem> = emptyList(),
    val scriptState: DubScriptUiState = DubScriptUiState.Idle,
    val renderState: DubRenderUiState = DubRenderUiState.Idle
)
