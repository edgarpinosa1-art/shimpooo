package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.DownloadedClipEntity
import com.example.data.local.UserSettings
import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubSourceLanguage
import com.example.data.model.DubVoiceEngine
import com.example.data.model.LocalVideoInspection
import com.example.data.network.TranscriptSerializer
import com.example.ui.DubRenderUiState
import com.example.ui.DubScriptUiState
import com.example.ui.DubberPanelState
import com.example.ui.components.AndroidVoiceTuningCard
import com.example.ui.components.CharacterVoiceCastCard
import com.example.ui.components.DubLineEditorCard
import com.example.ui.components.DubbedResultCard
import com.example.ui.components.MmsOnnxModelCard
import com.example.ui.components.MultiSpeakerToggleCard
import com.example.ui.components.StudioTopHeader
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.formatDurationSeconds
import com.example.ui.components.formatFileSize
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

@Composable
fun DubberScreen(
    settings: UserSettings,
    selectedVideo: LocalVideoInspection?,
    dubberState: DubberPanelState,
    downloadedClips: List<DownloadedClipEntity>,
    onVideoPicked: (android.net.Uri) -> Unit,
    onSelectDownloadedClip: (localPath: String, contentUri: String) -> Unit,
    onSelectSourceLanguage: (DubSourceLanguage) -> Unit,
    onSelectVoiceEngine: (DubVoiceEngine) -> Unit,
    onSelectDuckingMode: (DubAudioDuckingMode) -> Unit,
    onSelectAndroidVoice: (String) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onToggleMultiSpeaker: (Boolean) -> Unit,
    onCycleCharacterVoice: (String) -> Unit,
    onDownloadMmsModel: () -> Unit,
    onDeleteMmsModel: () -> Unit,
    onGenerateDubScript: () -> Unit,
    onImportOfflineScript: (String) -> Unit,
    onUpdateDubLineText: (Long, String) -> Unit,
    onRemoveDubLine: (Long) -> Unit,
    onRenderDubbedVideo: () -> Unit,
    onOpenKeySheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showOfflineSrtBox by remember { mutableStateOf(false) }
    var rawSrtInput by remember { mutableStateOf("") }
    var previewVideoData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            pendingNotificationAction?.invoke()
            pendingNotificationAction = null
        }
    )

    val runWithNotificationPermission: (() -> Unit) -> Unit = { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) onVideoPicked(uri) }
    )

    val documentVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) onVideoPicked(uri) }
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StudioTopHeader(
                    title = stringResource(R.string.dubber_headline),
                    subtitle = stringResource(R.string.dubber_subtitle),
                    hasApiKey = settings.hasApiKey,
                    providerLabel = settings.activeProvider.shortBadge,
                    onOpenKeySheet = onOpenKeySheet
                )
            }

            // 1. Video Picker Card
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "1. Pick Video to Dub (EN / JA → Filipino)",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioCyan,
                                    contentColor = StudioObsidian
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("dubber_pick_video_button")
                            ) {
                                Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Gallery Video", style = MaterialTheme.typography.labelLarge)
                            }

                            OutlinedButton(
                                onClick = { documentVideoLauncher.launch(arrayOf("video/*")) },
                                border = BorderStroke(1.dp, StudioBorder),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .height(50.dp)
                                    .testTag("dubber_pick_files_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = "Pick video from files", tint = StudioTextPrimary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Files", color = StudioTextPrimary)
                            }
                        }

                        if (downloadedClips.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(downloadedClips.take(8), key = { it.id }) { clip ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = StudioElevated,
                                        border = BorderStroke(1.dp, StudioBorder),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                onSelectDownloadedClip(clip.localFilePath, clip.contentUriString)
                                            }
                                            .testTag("dubber_quick_clip_${clip.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Filled.Movie, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(15.dp))
                                            Text(
                                                text = clip.title.take(22),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = StudioTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedVideo != null) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioObsidian,
                                border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedVideo.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = StudioTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val durSec = (selectedVideo.durationMs / 1000L).toInt()
                                        Text(
                                            text = "${formatDurationSeconds(durSec)} · ${formatFileSize(selectedVideo.fileSizeBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioCyan
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { previewVideoData = selectedVideo.uriString to selectedVideo.displayName },
                                        border = BorderStroke(1.dp, StudioBorder),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play", color = StudioTextPrimary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            val chunkWindows = TranscriptSerializer.buildTimeWindows(selectedVideo.durationMs)
                            if (chunkWindows.size > 1) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = StudioEmerald.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.45f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Long-Form Chunked Dubbing Active: Auto-splits into ${chunkWindows.size} × 6-min lip-sync chunks + low-RAM streaming audio render.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioEmerald,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Source Language & Lip-Timed Translation Card
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "2. Lip-Timed Filipino Script",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DubSourceLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = dubberState.sourceLanguage == lang,
                                    onClick = { onSelectSourceLanguage(lang) },
                                    label = { Text(lang.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = StudioCyan
                                    )
                                )
                            }
                        }

                        val isGeneratingScript = dubberState.scriptState is DubScriptUiState.Generating
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isGeneratingScript) {
                                        Brush.horizontalGradient(listOf(StudioElevated, StudioElevated))
                                    } else {
                                        Brush.horizontalGradient(listOf(StudioCyan, StudioEmerald))
                                    }
                                )
                                .clickable(enabled = !isGeneratingScript) {
                                    if (!settings.hasApiKey) {
                                        onOpenKeySheet()
                                    } else {
                                        runWithNotificationPermission(onGenerateDubScript)
                                    }
                                }
                                .testTag("generate_dub_script_button")
                                .padding(vertical = 15.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isGeneratingScript) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = StudioCyan)
                                        Text(
                                            text = (dubberState.scriptState as DubScriptUiState.Generating).stage,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = StudioCyan
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.dub_background_safe_note),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = StudioObsidian, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = stringResource(R.string.btn_generate_dub_script),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StudioObsidian
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showOfflineSrtBox = !showOfflineSrtBox },
                            border = BorderStroke(1.dp, StudioBorder),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("toggle_offline_srt_button")
                        ) {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (showOfflineSrtBox) "Hide Offline .SRT Importer" else "Or Paste .SRT / Timed Lines (100% Offline, ₱0)",
                                color = StudioTextPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        AnimatedVisibility(visible = showOfflineSrtBox) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = rawSrtInput,
                                    onValueChange = { rawSrtInput = it },
                                    label = { Text("Paste .SRT or [00:01 - 00:04] Tagalog line") },
                                    minLines = 4,
                                    maxLines = 8,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = StudioCyan,
                                        unfocusedBorderColor = StudioBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        onImportOfflineScript(rawSrtInput)
                                        showOfflineSrtBox = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = StudioElevated,
                                        contentColor = StudioCyan
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("apply_offline_srt_button")
                                ) {
                                    Text("Load Timed Script", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        if (dubberState.scriptState is DubScriptUiState.Error) {
                            Text(
                                text = (dubberState.scriptState as DubScriptUiState.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioError
                            )
                        }
                    }
                }
            }

            // 3. Open-Source Voice Engine, Ducking & Render Card
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "3. Filipino Voice Engine & Timing Sync",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )

                        DubVoiceEngine.entries.forEach { engine ->
                            val selected = dubberState.voiceEngine == engine
                            Surface(
                                onClick = { onSelectVoiceEngine(engine) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) StudioCyan.copy(alpha = 0.14f) else StudioObsidian,
                                border = BorderStroke(1.dp, if (selected) StudioCyan else StudioBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dub_engine_${engine.name}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = engine.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) StudioCyan else StudioCoral
                                    )
                                    Text(
                                        text = engine.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StudioTextPrimary
                                    )
                                    Text(
                                        text = engine.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                        }

                        if (dubberState.voiceEngine == DubVoiceEngine.MMS_ONNX_TAGALOG) {
                            MmsOnnxModelCard(
                                state = dubberState,
                                onDownloadModel = onDownloadMmsModel,
                                onDeleteModel = onDeleteMmsModel
                            )
                        }

                        AndroidVoiceTuningCard(
                            voices = dubberState.androidVoices,
                            selectedVoiceId = dubberState.selectedAndroidVoiceId,
                            pitch = dubberState.voicePitch,
                            baseSpeed = dubberState.baseSpeechRate,
                            engine = dubberState.voiceEngine,
                            onSelectVoice = onSelectAndroidVoice,
                            onPitchChanged = onPitchChanged,
                            onSpeedChanged = onSpeedChanged,
                            onOpenSystemTtsInstall = {
                                runCatching {
                                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                                }
                            }
                        )

                        val detectedSpeakerCount = remember(dubberState.dubLines) {
                            dubberState.dubLines
                                .map { it.speaker.trim().ifBlank { "Speaker 1" } }
                                .distinct()
                                .size
                        }
                        MultiSpeakerToggleCard(
                            enabled = dubberState.multiSpeakerEnabled,
                            detectedSpeakerCount = detectedSpeakerCount,
                            onToggle = onToggleMultiSpeaker
                        )

                        if (dubberState.multiSpeakerEnabled && dubberState.speakerProfiles.isNotEmpty()) {
                            CharacterVoiceCastCard(
                                speakerProfiles = dubberState.speakerProfiles,
                                onCycleCharacterVoice = onCycleCharacterVoice
                            )
                        }

                        Text(
                            text = "Background Audio Ducking",
                            style = MaterialTheme.typography.labelLarge,
                            color = StudioTextPrimary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DubAudioDuckingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = dubberState.duckingMode == mode,
                                    onClick = { onSelectDuckingMode(mode) },
                                    label = { Text(mode.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioEmerald.copy(alpha = 0.2f),
                                        selectedLabelColor = StudioEmerald
                                    )
                                )
                            }
                        }

                        val isRendering = dubberState.renderState is DubRenderUiState.Rendering
                        if (isRendering) {
                            val renderInfo = dubberState.renderState as DubRenderUiState.Rendering
                            val pct = renderInfo.progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
                            val statusHeader = when {
                                pct != null && pct >= 75 -> "Patapos na! ($pct%) — Malapit nang mabuo ang MP4"
                                pct != null -> "Dubbing Progress: $pct%"
                                else -> "Dubbing in progress..."
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = statusHeader,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (pct != null && pct >= 75) StudioCyan else StudioEmerald
                                    )
                                }
                                if (renderInfo.progress != null) {
                                    LinearProgressIndicator(
                                        progress = { renderInfo.progress },
                                        color = StudioEmerald,
                                        trackColor = StudioElevated,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(50))
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        color = StudioEmerald,
                                        trackColor = StudioElevated,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(50))
                                    )
                                }
                                Text(
                                    text = renderInfo.stage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioEmerald
                                )
                                Text(
                                    text = stringResource(R.string.dub_background_safe_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary
                                )
                            }
                        } else {
                            Button(
                                onClick = { runWithNotificationPermission(onRenderDubbedVideo) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioEmerald,
                                    contentColor = StudioObsidian
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("render_dubbed_video_button")
                            ) {
                                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.btn_render_dubbed_video), style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        if (dubberState.renderState is DubRenderUiState.Error) {
                            Text(
                                text = (dubberState.renderState as DubRenderUiState.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioError
                            )
                        }
                    }
                }
            }

            if (dubberState.renderState is DubRenderUiState.Success) {
                val res = (dubberState.renderState as DubRenderUiState.Success).result
                item {
                    DubbedResultCard(
                        result = res,
                        onPreview = { previewVideoData = res.localFilePath to res.fileName },
                        onShare = { shareLocalVideo(context, res.localFilePath) }
                    )
                }
            }

            if (dubberState.dubLines.isNotEmpty()) {
                item {
                    Text(
                        text = "Editable Lip-Timed Script (${dubberState.dubLines.size} lines)",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary
                    )
                }

                items(dubberState.dubLines, key = { it.id }) { line ->
                    DubLineEditorCard(
                        line = line,
                        onTextChange = { updated -> onUpdateDubLineText(line.id, updated) },
                        onDelete = { onRemoveDubLine(line.id) }
                    )
                }
            }
        }
    }

    previewVideoData?.let { (uri, title) ->
        val finalUri = if (uri.startsWith("/")) {
            android.net.Uri.fromFile(java.io.File(uri)).toString()
        } else uri
        VideoPlayerDialog(
            videoUriString = finalUri,
            title = title,
            onDismiss = { previewVideoData = null }
        )
    }
}
