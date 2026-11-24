package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.DownloadedClipEntity
import com.example.data.local.UserSettings
import com.example.data.model.LocalVideoInspection
import com.example.data.model.PromptBlueprint
import com.example.data.model.PromptUpgradePreset
import com.example.data.model.TargetVideoGenerator
import com.example.data.model.VideoAspectRatio
import com.example.data.network.PromptBlueprintSerializer
import com.example.ui.StudioAnalysisUiState
import com.example.ui.components.CopyablePromptBlock
import com.example.ui.components.StudioTopHeader
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.copyTextToClipboard
import com.example.ui.components.formatDurationSeconds
import com.example.ui.components.formatFileSize
import com.example.ui.theme.StudioAmber
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
import com.example.ui.theme.StudioViolet
import java.io.File

@Composable
fun PromptStudioScreen(
    settings: UserSettings,
    selectedVideo: LocalVideoInspection?,
    selectedAspectRatio: VideoAspectRatio,
    selectedUpgradePreset: PromptUpgradePreset,
    extraNotes: String,
    studioState: StudioAnalysisUiState,
    downloadedClips: List<DownloadedClipEntity>,
    onVideoPicked: (android.net.Uri) -> Unit,
    onSelectDownloadedClip: (localPath: String, contentUri: String) -> Unit,
    onSelectTargetGenerator: (TargetVideoGenerator) -> Unit,
    onSelectAspectRatio: (VideoAspectRatio) -> Unit,
    onSelectUpgradePreset: (PromptUpgradePreset) -> Unit,
    onSaveKeyframesClick: () -> Unit,
    onExtraNotesChanged: (String) -> Unit,
    onAnalyzeClick: () -> Unit,
    onOpenKeySheet: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var previewPlayerUri by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Zero-permission Android Photo Picker for videos (Play Policy compliant)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                onVideoPicked(uri)
            }
        }
    )

    val documentVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                onVideoPicked(uri)
            }
        }
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
                    title = stringResource(R.string.studio_headline),
                    subtitle = stringResource(R.string.studio_subtitle),
                    hasApiKey = settings.hasApiKey,
                    onOpenKeySheet = onOpenKeySheet
                )
            }

            // Prominent Gemini API Key Banner if not yet entered
            if (!settings.hasApiKey) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = StudioCoral.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioCoral.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onOpenKeySheet() }
                            .testTag("missing_api_key_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = StudioCoral,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.api_key_missing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioCoral
                                )
                                Text(
                                    text = stringResource(R.string.api_key_missing_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextPrimary
                                )
                            }
                            Text(
                                text = "Setup →",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioCoral
                            )
                        }
                    }
                }
            } else {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioSurface,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpenKeySheet() }
                            .testTag("active_key_summary_bar")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Key: ${settings.maskedApiKey}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioEmerald
                            )
                            Text(
                                text = "Model: ${settings.selectedGeminiModel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioCyan
                            )
                        }
                    }
                }
            }

            // Video Selection & Storyboard Card
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
                            text = "1. Load Video to Reverse-Engineer",
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
                                    .testTag("pick_video_gallery_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VideoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Gallery Video", style = MaterialTheme.typography.labelLarge)
                            }

                            OutlinedButton(
                                onClick = {
                                    documentVideoLauncher.launch(arrayOf("video/*"))
                                },
                                border = BorderStroke(1.dp, StudioBorder),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .height(50.dp)
                                    .testTag("pick_video_files_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = "Pick video from files",
                                    tint = StudioTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Files", color = StudioTextPrimary)
                            }
                        }

                        if (downloadedClips.isNotEmpty()) {
                            Text(
                                text = "Or tap a downloaded clip:",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(downloadedClips.take(8), key = { it.id }) { clip ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = StudioElevated,
                                        border = BorderStroke(1.dp, StudioBorder),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                onSelectDownloadedClip(
                                                    clip.localFilePath,
                                                    clip.contentUriString
                                                )
                                            }
                                            .testTag("quick_clip_${clip.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Movie,
                                                contentDescription = null,
                                                tint = StudioCyan,
                                                modifier = Modifier.size(15.dp)
                                            )
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

                        if (studioState is StudioAnalysisUiState.InspectingVideo) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = StudioCyan
                                )
                                Text(
                                    text = "Extracting storyboard keyframes...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioCyan
                                )
                            }
                        }

                        if (selectedVideo != null) {
                            VideoStoryboardPreview(
                                inspection = selectedVideo,
                                onPreviewClick = {
                                    previewPlayerUri = selectedVideo.uriString to selectedVideo.displayName
                                },
                                onSaveKeyframesClick = onSaveKeyframesClick
                            )
                        }
                    }
                }
            }

            // Target AI Video Generator + Notes + Analyze CTA
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
                            text = "2. Target AI Video Generator",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TargetVideoGenerator.entries.forEach { generator ->
                                val selected = settings.targetGenerator == generator
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectTargetGenerator(generator) },
                                    label = { Text(generator.shortLabel) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioCoral.copy(alpha = 0.22f),
                                        selectedLabelColor = StudioCoral
                                    ),
                                    modifier = Modifier.testTag("generator_chip_${generator.name}")
                                )
                            }
                        }

                        Text(
                            text = settings.targetGenerator.promptGuidance,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )

                        Text(
                            text = "Output Framing & Upgrade Style",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioTextPrimary
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VideoAspectRatio.entries.forEach { ratio ->
                                val selected = selectedAspectRatio == ratio
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectAspectRatio(ratio) },
                                    label = { Text(ratio.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = StudioCyan
                                    ),
                                    modifier = Modifier.testTag("aspect_chip_${ratio.name}")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PromptUpgradePreset.entries.forEach { preset ->
                                val selected = selectedUpgradePreset == preset
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectUpgradePreset(preset) },
                                    label = { Text(preset.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioViolet.copy(alpha = 0.24f),
                                        selectedLabelColor = StudioViolet
                                    ),
                                    modifier = Modifier.testTag("preset_chip_${preset.name}")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = extraNotes,
                            onValueChange = onExtraNotesChanged,
                            label = { Text("Optional Upgrade Direction (Tagalog / English)") },
                            placeholder = { Text("e.g., Make lighting more dramatic, 35mm film, keep product logo exact") },
                            minLines = 2,
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StudioCyan,
                                unfocusedBorderColor = StudioBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("extra_creator_notes_input")
                        )

                        val isAnalyzing = studioState is StudioAnalysisUiState.Analyzing
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isAnalyzing) {
                                        Brush.horizontalGradient(listOf(StudioElevated, StudioElevated))
                                    } else {
                                        Brush.horizontalGradient(listOf(StudioCyan, StudioCoral))
                                    }
                                )
                                .clickable(enabled = !isAnalyzing) { onAnalyzeClick() }
                                .testTag("analyze_video_button")
                                .padding(vertical = 15.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isAnalyzing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.5.dp,
                                        color = StudioCyan
                                    )
                                    Text(
                                        text = studioState.stage,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StudioCyan
                                    )
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = StudioObsidian,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.btn_analyze_video),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StudioObsidian
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Error display
            if (studioState is StudioAnalysisUiState.Error) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = StudioError.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioError.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Gemini Analysis Error",
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioError
                            )
                            Text(
                                text = studioState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextPrimary
                            )
                        }
                    }
                }
            }

            // Success Result: Full Structured Blueprint
            if (studioState is StudioAnalysisUiState.Success) {
                val blueprint = studioState.blueprint
                item {
                    BlueprintResultsHeader(
                        blueprint = blueprint,
                        onCopyAll = {
                            val fullPack = PromptBlueprintSerializer.formatFullCopyBundle(blueprint)
                            copyTextToClipboard(context, "Gizmooh Prompt Pack", fullPack)
                            onShowMessage("Copied complete Ingredient + Video Prompt Pack!")
                        }
                    )
                }

                // 1. 100% Upgraded Master Video Prompt
                item {
                    CopyablePromptBlock(
                        title = "100% Enhanced Master Video Prompt",
                        badge = "${blueprint.targetGenerator.shortLabel} · READY TO GENERATE",
                        promptText = blueprint.masterVideoPrompt,
                        negativePrompt = blueprint.negativeVideoPrompt,
                        accentColor = StudioCyan,
                        onCopy = { txt ->
                            copyTextToClipboard(context, "Master Video Prompt", txt)
                            onShowMessage("Master Video Prompt copied!")
                        },
                        testTagPrefix = "master_video_prompt"
                    )
                }

                // 2. Ingredient Image Prompts
                if (blueprint.ingredients.isNotEmpty()) {
                    item {
                        Text(
                            text = "Ingredient Image Prompts (${blueprint.ingredients.size})",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Generate these reference images first, then attach them as Ingredients/Elements with the Master Video Prompt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }

                    items(blueprint.ingredients) { ing ->
                        CopyablePromptBlock(
                            title = ing.name,
                            badge = "INGREDIENT · ${ing.role}",
                            promptText = ing.imagePrompt,
                            negativePrompt = ing.negativePrompt,
                            accentColor = StudioCoral,
                            onCopy = { txt ->
                                copyTextToClipboard(context, ing.name, txt)
                                onShowMessage("Copied ${ing.name} image prompt!")
                            },
                            testTagPrefix = "ingredient_${ing.name.take(12)}"
                        )
                    }
                }

                // 3. Reverse-Engineered Original Prompt & Cinematography Breakdown
                item {
                    CopyablePromptBlock(
                        title = "Detected Original Prompt / Video Formula",
                        badge = "REVERSE-ENGINEERED FROM VIDEO",
                        promptText = blueprint.originalPromptGuess,
                        accentColor = StudioViolet,
                        onCopy = { txt ->
                            copyTextToClipboard(context, "Original Prompt", txt)
                            onShowMessage("Original prompt formula copied!")
                        },
                        testTagPrefix = "original_prompt_guess"
                    )
                }

                // 4. Scene-by-Scene Breakdown
                if (blueprint.scenes.isNotEmpty()) {
                    item {
                        Text(
                            text = "Scene-by-Scene Shot Prompts (${blueprint.scenes.size})",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )
                    }

                    items(blueprint.scenes) { scene ->
                        CopyablePromptBlock(
                            title = "${scene.shotType} · ${scene.cameraMovement}",
                            badge = "TIME ${scene.timeRange}",
                            promptText = scene.scenePrompt,
                            negativePrompt = scene.actionDescription,
                            accentColor = StudioAmber,
                            onCopy = { txt ->
                                copyTextToClipboard(context, "Scene ${scene.timeRange}", txt)
                                onShowMessage("Scene ${scene.timeRange} prompt copied!")
                            },
                            testTagPrefix = "scene_${scene.timeRange}"
                        )
                    }
                }

                // 5. Cinematography Specs & Recreation Tips
                item {
                    CinematographyBreakdownCard(blueprint = blueprint)
                }
            }
        }
    }

    previewPlayerUri?.let { (uri, title) ->
        VideoPlayerDialog(
            videoUriString = uri,
            title = title,
            onDismiss = { previewPlayerUri = null }
        )
    }
}

@Composable
private fun VideoStoryboardPreview(
    inspection: LocalVideoInspection,
    onPreviewClick: () -> Unit,
    onSaveKeyframesClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioObsidian,
        border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = inspection.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val durSec = (inspection.durationMs / 1000L).toInt()
                    val resText = if (inspection.width > 0 && inspection.height > 0) {
                        "${inspection.width}×${inspection.height}"
                    } else {
                        "Video"
                    }
                    Text(
                        text = "${formatDurationSeconds(durSec)} · $resText · ${formatFileSize(inspection.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan
                    )
                }

                OutlinedButton(
                    onClick = onPreviewClick,
                    border = BorderStroke(1.dp, StudioBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("preview_selected_video_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", color = StudioTextPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (inspection.keyframePaths.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(inspection.keyframePaths) { framePath ->
                        AsyncImage(
                            model = File(framePath),
                            contentDescription = "Extracted video keyframe",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 76.dp, height = 104.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                        )
                    }
                }

                OutlinedButton(
                    onClick = onSaveKeyframesClick,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("save_keyframes_gallery_button")
                ) {
                    Text(
                        text = "Save ${inspection.keyframePaths.size} Keyframes to Gallery (Pictures/GizmoohDownloader)",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun BlueprintResultsHeader(
    blueprint: PromptBlueprint,
    onCopyAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioEmerald.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PROMPT BLUEPRINT COMPLETE",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioEmerald
                    )
                    Text(
                        text = blueprint.videoTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onCopyAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioEmerald,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier.testTag("copy_all_prompts_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy All", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (blueprint.whatHappenedSummary.isNotBlank()) {
                Text(
                    text = blueprint.whatHappenedSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextPrimary
                )
            }
        }
    }
}

@Composable
private fun CinematographyBreakdownCard(blueprint: PromptBlueprint) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Cinematography & 100% Recreation Guide",
                style = MaterialTheme.typography.titleMedium,
                color = StudioCyan
            )
            SpecRow("Visual Style", blueprint.visualStyle)
            SpecRow("Camera & Lens", blueprint.cameraAndLens)
            SpecRow("Lighting & Palette", blueprint.lightingAndPalette)
            SpecRow("Audio & Pacing", blueprint.audioAndPacing)
            if (blueprint.recreationTips.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioElevated)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "PRO WORKFLOW STEPS",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCoral
                        )
                        Text(
                            text = blueprint.recreationTips,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioTextPrimary
        )
    }
}
