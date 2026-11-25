package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.dubbing.DubTimelineMath
import com.example.data.model.DubLineItem
import com.example.data.model.DubVoiceEngine
import com.example.data.model.DubVoiceOption
import com.example.data.model.DubbedVideoResult
import com.example.data.model.MmsModelStatus
import com.example.data.model.SpeakerVoiceProfile
import com.example.data.model.WhisperBrainTier
import com.example.ui.DubberPanelState
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCoral
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioElevated
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import java.util.Locale

@Composable
fun MmsOnnxModelCard(
    state: DubberPanelState,
    onDownloadModel: () -> Unit,
    onDeleteModel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioObsidian,
        border = BorderStroke(
            1.dp,
            when (state.mmsModelStatus) {
                MmsModelStatus.READY -> StudioEmerald.copy(alpha = 0.6f)
                MmsModelStatus.ERROR -> StudioError.copy(alpha = 0.6f)
                else -> StudioBorder
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Meta facebook/mms-tts-tgl (ONNX)",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = when (state.mmsModelStatus) {
                            MmsModelStatus.READY -> String.format(
                                Locale.US,
                                "Installed Offline · %.1f MB",
                                state.mmsModelSizeMb
                            )
                            MmsModelStatus.DOWNLOADING -> state.mmsDownloadStage
                            MmsModelStatus.ERROR -> state.mmsDownloadStage.ifBlank { "Download error" }
                            MmsModelStatus.NOT_INSTALLED -> "1-time download (~62 MB) for 100% open-source offline Tagalog voice."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.mmsModelStatus) {
                            MmsModelStatus.READY -> StudioEmerald
                            MmsModelStatus.ERROR -> StudioError
                            MmsModelStatus.DOWNLOADING -> StudioCyan
                            MmsModelStatus.NOT_INSTALLED -> StudioTextSecondary
                        }
                    )
                }

                if (state.mmsModelStatus == MmsModelStatus.READY) {
                    IconButton(
                        onClick = onDeleteModel,
                        modifier = Modifier.testTag("delete_mms_model_button")
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete downloaded MMS model",
                            tint = StudioTextSecondary
                        )
                    }
                }
            }

            if (state.mmsModelStatus == MmsModelStatus.DOWNLOADING) {
                if (state.mmsDownloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { state.mmsDownloadProgress },
                        color = StudioCyan,
                        trackColor = StudioElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                    )
                } else {
                    LinearProgressIndicator(
                        color = StudioCyan,
                        trackColor = StudioElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                    )
                }
            } else if (state.mmsModelStatus != MmsModelStatus.READY) {
                Button(
                    onClick = onDownloadModel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCyan,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("download_mms_model_button")
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Open-Source Tagalog Model", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun AndroidVoiceTuningCard(
    voices: List<DubVoiceOption>,
    selectedVoiceId: String,
    pitch: Float,
    baseSpeed: Float,
    engine: DubVoiceEngine,
    onSelectVoice: (String) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onOpenSystemTtsInstall: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (engine == DubVoiceEngine.ANDROID_FIL_PH) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Filipino Voices (${voices.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextPrimary
                )
                OutlinedButton(
                    onClick = onOpenSystemTtsInstall,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("TTS Voice Pack", style = MaterialTheme.typography.labelSmall, color = StudioCyan)
                }
            }

            voices.take(4).forEach { voice ->
                val selected = voice.id == selectedVoiceId
                Surface(
                    onClick = { onSelectVoice(voice.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) StudioCyan.copy(alpha = 0.16f) else StudioObsidian,
                    border = BorderStroke(1.dp, if (selected) StudioCyan else StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = voice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) StudioCyan else StudioTextPrimary
                        )
                        Text(
                            text = voice.localeTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                }
            }

            Column {
                Text(
                    text = String.format(Locale.US, "Voice Pitch: %.2fx", pitch),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
                Slider(
                    value = pitch.coerceIn(0.84f, 1.22f),
                    onValueChange = onPitchChanged,
                    valueRange = 0.84f..1.22f,
                    colors = SliderDefaults.colors(
                        thumbColor = StudioCyan,
                        activeTrackColor = StudioCyan
                    )
                )
            }
        }

        Column {
            Text(
                text = String.format(Locale.US, "Base Speaking Speed: %.2fx (auto-synced per line)", baseSpeed),
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextSecondary
            )
            Slider(
                value = baseSpeed.coerceIn(0.84f, 1.30f),
                onValueChange = onSpeedChanged,
                valueRange = 0.84f..1.30f,
                colors = SliderDefaults.colors(
                    thumbColor = StudioCoral,
                    activeTrackColor = StudioCoral
                )
            )
        }
    }
}

@Composable
fun DubLineEditorCard(
    line: DubLineItem,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioCyan.copy(alpha = 0.16f))
                            .border(1.dp, StudioCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${DubTimelineMath.formatMsToTimestamp(line.startMs)} → ${DubTimelineMath.formatMsToTimestamp(line.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                    }
                    Text(
                        text = line.speaker,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCoral
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_dub_line_${line.id}")
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Remove dubbing line",
                        tint = StudioTextSecondary
                    )
                }
            }

            if (line.originalText.isNotBlank()) {
                Text(
                    text = "Orig: ${line.originalText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
            }

            OutlinedTextField(
                value = line.filipinoText,
                onValueChange = onTextChange,
                label = { Text("Filipino Dub Line") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioEmerald,
                    unfocusedBorderColor = StudioBorder,
                    focusedLabelColor = StudioEmerald,
                    cursorColor = StudioEmerald
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun DubbedResultCard(
    result: DubbedVideoResult,
    onPreview: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioEmerald.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StudioEmerald)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Filipino Dubbed MP4 Ready!",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioEmerald
                    )
                    Text(
                        text = "${result.engineUsed} · ${result.segmentCount} lip-timed lines · ${formatFileSize(result.fileSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = if (result.gallerySaved) {
                            "Saved to Movies/GizmoohDownloader/${result.fileName}"
                        } else {
                            "Saved locally (${result.fileName})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPreview,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioEmerald,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("preview_dubbed_video_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Preview Dub", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("share_dubbed_video_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = StudioTextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share MP4", color = StudioTextPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun MultiSpeakerToggleCard(
    enabled: Boolean,
    detectedSpeakerCount: Int,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioObsidian,
        border = BorderStroke(
            1.dp,
            if (enabled) StudioCyan.copy(alpha = 0.55f) else StudioBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.RecordVoiceOver,
                contentDescription = null,
                tint = if (enabled) StudioCyan else StudioTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Multi-Speaker Voice Switching (Optional)",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioTextPrimary
                )
                val statusNote = when {
                    detectedSpeakerCount > 1 && enabled ->
                        "$detectedSpeakerCount speakers detected · Auto pitch/voice switch ON"
                    enabled ->
                        "Auto-assigns distinct pitch & voice when dialogue has >1 speaker"
                    else ->
                        "Single consistent narrator voice across all lines"
                }
                Text(
                    text = statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detectedSpeakerCount > 1 && enabled) StudioCyan else StudioTextSecondary
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = StudioObsidian,
                    checkedTrackColor = StudioCyan,
                    uncheckedThumbColor = StudioTextSecondary,
                    uncheckedTrackColor = StudioElevated
                ),
                modifier = Modifier.testTag("multi_speaker_toggle")
            )
        }
    }
}

@Composable
fun CharacterVoiceCastCard(
    speakerProfiles: Map<String, SpeakerVoiceProfile>,
    detectedCastSummary: String = "",
    onCycleCharacterVoice: (String) -> Unit
) {
    if (speakerProfiles.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioObsidian,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Locked Character Voices (Auto-Detected from MP4)",
                style = MaterialTheme.typography.labelLarge,
                color = StudioTextPrimary
            )
            if (detectedCastSummary.isNotBlank()) {
                Text(
                    text = detectedCastSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioEmerald
                )
            }
            Text(
                text = "Tap any character to change their locked pitch/voice before rendering.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )
            speakerProfiles.values.forEach { profile ->
                Surface(
                    onClick = { onCycleCharacterVoice(profile.speakerKey) },
                    shape = RoundedCornerShape(12.dp),
                    color = StudioElevated,
                    border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("character_voice_${profile.lockedIndex}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = profile.speakerKey,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Locked · ${profile.presetLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WhisperBrainModelCard(
    state: DubberPanelState,
    onSelectTier: (WhisperBrainTier) -> Unit,
    onDownloadWhisper: () -> Unit,
    onDeleteWhisper: () -> Unit,
    onTogglePreferOffline: (Boolean) -> Unit,
    onToggleAutoStartOnDrop: (Boolean) -> Unit
) {
    val activeTier = state.whisperBrainTier
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioObsidian,
        border = BorderStroke(
            1.dp,
            when (state.whisperModelStatus) {
                MmsModelStatus.READY -> StudioEmerald.copy(alpha = 0.6f)
                MmsModelStatus.ERROR -> StudioError.copy(alpha = 0.6f)
                else -> StudioBorder
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OpenAI ${activeTier.label} (ONNX · ₱0)",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = when (state.whisperModelStatus) {
                            MmsModelStatus.READY -> String.format(
                                Locale.US,
                                "Offline AI Brain Ready · %.1f MB",
                                state.whisperModelSizeMb
                            )
                            MmsModelStatus.DOWNLOADING -> state.whisperDownloadStage
                            MmsModelStatus.ERROR -> state.whisperDownloadStage.ifBlank { "Whisper download error" }
                            MmsModelStatus.NOT_INSTALLED -> "1-time (${activeTier.approxSizeLabel}) strongest open-source speech AI. Listens & translates any MP4 offline (auto-falls back if RAM is tight)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.whisperModelStatus) {
                            MmsModelStatus.READY -> StudioEmerald
                            MmsModelStatus.ERROR -> StudioError
                            MmsModelStatus.DOWNLOADING -> StudioCyan
                            MmsModelStatus.NOT_INSTALLED -> StudioTextSecondary
                        }
                    )
                }

                if (state.whisperModelStatus == MmsModelStatus.READY) {
                    IconButton(
                        onClick = onDeleteWhisper,
                        modifier = Modifier.testTag("delete_whisper_model_button")
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete downloaded Whisper model",
                            tint = StudioTextSecondary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WhisperBrainTier.entries.forEach { tierOption ->
                    val selected = activeTier == tierOption
                    FilterChip(
                        selected = selected,
                        enabled = state.whisperModelStatus != MmsModelStatus.DOWNLOADING && !state.isAutoPipelineRunning,
                        onClick = { onSelectTier(tierOption) },
                        label = { Text("${tierOption.name.replace("_", "-")} (${tierOption.shortBadge})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                            selectedLabelColor = StudioCyan
                        ),
                        modifier = Modifier.testTag("whisper_tier_${tierOption.name}")
                    )
                }
            }

            if (state.whisperModelStatus == MmsModelStatus.DOWNLOADING) {
                if (state.whisperDownloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { state.whisperDownloadProgress },
                        color = StudioCyan,
                        trackColor = StudioElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                    )
                } else {
                    LinearProgressIndicator(
                        color = StudioCyan,
                        trackColor = StudioElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                    )
                }
            } else if (state.whisperModelStatus != MmsModelStatus.READY) {
                OutlinedButton(
                    onClick = onDownloadWhisper,
                    border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("download_whisper_model_button")
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pre-Download ${activeTier.label} (${activeTier.approxSizeLabel})", style = MaterialTheme.typography.labelLarge, color = StudioCyan)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Dub Agad Pagpili ng MP4",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = "Ilagay lang ang MP4 at kusa nang magda-dub hanggang Gallery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
                Switch(
                    checked = state.autoStartOnVideoDrop,
                    onCheckedChange = onToggleAutoStartOnDrop,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StudioObsidian,
                        checkedTrackColor = StudioCyan
                    ),
                    modifier = Modifier.testTag("auto_start_on_drop_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = StudioEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "100% Open-Source Only · Zero API Keys (${activeTier.label} + ML Kit)",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioEmerald
                )
            }
        }
    }
}

