package com.example.ui

import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubLineItem
import com.example.data.model.DubSourceLanguage
import com.example.data.model.DubVoiceEngine
import com.example.data.model.DubVoiceOption
import com.example.data.model.DubbedVideoResult
import com.example.data.model.MmsModelStatus
import com.example.data.model.SpeakerVoiceProfile

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
    val voiceEngine: DubVoiceEngine = DubVoiceEngine.MMS_ONNX_TAGALOG,
    val duckingMode: DubAudioDuckingMode = DubAudioDuckingMode.SMART_DUCK_20,
    val androidVoices: List<DubVoiceOption> = emptyList(),
    val selectedAndroidVoiceId: String = "",
    val voicePitch: Float = 1.0f,
    val baseSpeechRate: Float = 1.0f,
    val multiSpeakerEnabled: Boolean = true,
    val speakerProfiles: Map<String, SpeakerVoiceProfile> = emptyMap(),
    val mmsModelStatus: MmsModelStatus = MmsModelStatus.NOT_INSTALLED,
    val mmsModelSizeMb: Double = 0.0,
    val mmsDownloadStage: String = "",
    val mmsDownloadProgress: Float? = null,
    val dubLines: List<DubLineItem> = emptyList(),
    val scriptState: DubScriptUiState = DubScriptUiState.Idle,
    val renderState: DubRenderUiState = DubRenderUiState.Idle
)
