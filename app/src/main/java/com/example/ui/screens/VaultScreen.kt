package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.DownloadedClipEntity
import com.example.data.local.PromptBlueprintEntity
import com.example.data.local.UserSettings
import com.example.data.local.VideoTranscriptEntity
import com.example.data.network.TranscriptSerializer
import com.example.data.model.TranscriptLanguageMode
import com.example.ui.components.StudioTopHeader
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.copyTextToClipboard
import com.example.ui.components.formatFileSize
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCoral
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioElevated
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultScreen(
    settings: UserSettings,
    downloadedClips: List<DownloadedClipEntity>,
    promptBlueprints: List<PromptBlueprintEntity>,
    videoTranscripts: List<VideoTranscriptEntity>,
    onTogglePreferHd: (Boolean) -> Unit,
    onOpenKeySheet: () -> Unit,
    onAnalyzeClip: (localPath: String, contentUri: String) -> Unit,
    onOpenBlueprint: (PromptBlueprintEntity) -> Unit,
    onOpenTranscript: (VideoTranscriptEntity) -> Unit,
    onDeleteClip: (DownloadedClipEntity) -> Unit,
    onDeleteBlueprint: (PromptBlueprintEntity) -> Unit,
    onDeleteTranscript: (VideoTranscriptEntity) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedSegment by remember { mutableIntStateOf(0) }
    var previewVideoData by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                    title = stringResource(R.string.vault_headline),
                    subtitle = "Manage saved videos, timestamped transcripts, extracted AI prompts, and your AI API key.",
                    hasApiKey = settings.hasApiKey,
                    providerLabel = settings.activeProvider.shortBadge,
                    onOpenKeySheet = onOpenKeySheet
                )
            }

            // Quick Settings Card
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenKeySheet() }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, tint = StudioCyan)
                                Column {
                                    Text(
                                        text = "${settings.activeProvider.shortBadge} API Key & Models",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StudioTextPrimary
                                    )
                                    Text(
                                        text = "${settings.maskedApiKey} · ${settings.activeDisplayModel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                            Text(
                                text = "Edit",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioCyan
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prefer HD No-Watermark Stream",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioTextPrimary
                                )
                                Text(
                                    text = "Automatically selects highest bitrate MP4",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.preferHdDownload,
                                onCheckedChange = onTogglePreferHd,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StudioObsidian,
                                    checkedTrackColor = StudioCyan
                                ),
                                modifier = Modifier.testTag("prefer_hd_switch")
                            )
                        }
                    }
                }
            }

            // Segmented Switcher
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedSegment == 0,
                        onClick = { selectedSegment = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = StudioCyan.copy(alpha = 0.2f),
                            activeContentColor = StudioCyan,
                            inactiveContainerColor = StudioSurface,
                            inactiveContentColor = StudioTextSecondary
                        ),
                        modifier = Modifier.testTag("vault_tab_clips")
                    ) {
                        Text("Clips (${downloadedClips.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SegmentedButton(
                        selected = selectedSegment == 1,
                        onClick = { selectedSegment = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = StudioCoral.copy(alpha = 0.2f),
                            activeContentColor = StudioCoral,
                            inactiveContainerColor = StudioSurface,
                            inactiveContentColor = StudioTextSecondary
                        ),
                        modifier = Modifier.testTag("vault_tab_prompts")
                    ) {
                        Text("Prompts (${promptBlueprints.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SegmentedButton(
                        selected = selectedSegment == 2,
                        onClick = { selectedSegment = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = StudioEmerald.copy(alpha = 0.2f),
                            activeContentColor = StudioEmerald,
                            inactiveContainerColor = StudioSurface,
                            inactiveContentColor = StudioTextSecondary
                        ),
                        modifier = Modifier.testTag("vault_tab_transcripts")
                    ) {
                        Text("Scripts (${videoTranscripts.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            when (selectedSegment) {
                0 -> {
                    if (downloadedClips.isEmpty()) {
                        item {
                            EmptyVaultPlaceholder(
                                title = stringResource(R.string.vault_empty_title),
                                description = stringResource(R.string.vault_empty_desc)
                            )
                        }
                    } else {
                        items(downloadedClips, key = { it.id }) { clip ->
                            VaultClipCard(
                                clip = clip,
                                onPlay = {
                                    val file = File(clip.localFilePath)
                                    val uriStr = if (file.exists()) {
                                        android.net.Uri.fromFile(file).toString()
                                    } else {
                                        clip.contentUriString
                                    }
                                    previewVideoData = uriStr to clip.title
                                },
                                onShare = { shareLocalVideo(context, clip.localFilePath) },
                                onAnalyze = { onAnalyzeClip(clip.localFilePath, clip.contentUriString) },
                                onDelete = { onDeleteClip(clip) }
                            )
                        }
                    }
                }
                1 -> {
                    if (promptBlueprints.isEmpty()) {
                        item {
                            EmptyVaultPlaceholder(
                                title = "No Saved Prompt Blueprints Yet",
                                description = "Analyze any video in Prompt AI and every Master Video Prompt + Ingredient Image Prompt will be saved here."
                            )
                        }
                    } else {
                        items(promptBlueprints, key = { it.id }) { bp ->
                            VaultBlueprintCard(
                                blueprint = bp,
                                onOpen = { onOpenBlueprint(bp) },
                                onCopyMaster = {
                                    copyTextToClipboard(context, bp.videoTitle, bp.masterVideoPrompt)
                                    onShowMessage("Copied master video prompt!")
                                },
                                onDelete = { onDeleteBlueprint(bp) }
                            )
                        }
                    }
                }
                else -> {
                    if (videoTranscripts.isEmpty()) {
                        item {
                            EmptyVaultPlaceholder(
                                title = "No Saved Transcripts Yet",
                                description = "Generate a complete timestamped transcript in the Transcript tab and it will be saved here."
                            )
                        }
                    } else {
                        items(videoTranscripts, key = { it.id }) { tr ->
                            VaultTranscriptCard(
                                entity = tr,
                                onOpen = { onOpenTranscript(tr) },
                                onCopyAll = {
                                    val mode = TranscriptLanguageMode.entries.firstOrNull {
                                        it.name == tr.languageModeName
                                    } ?: TranscriptLanguageMode.ORIGINAL_VERBATIM
                                    val parsed = TranscriptSerializer.fromGeminiOrStoredJson(
                                        jsonString = tr.payloadJson,
                                        id = tr.id,
                                        videoTitle = tr.videoTitle,
                                        sourceUriString = tr.sourceUriString,
                                        geminiModelUsed = tr.geminiModelUsed,
                                        languageMode = mode,
                                        createdAt = tr.createdAt
                                    )
                                    copyTextToClipboard(
                                        context,
                                        tr.videoTitle,
                                        TranscriptSerializer.formatTimestampedText(parsed)
                                    )
                                    onShowMessage("Copied timestamped transcript!")
                                },
                                onDelete = { onDeleteTranscript(tr) }
                            )
                        }
                    }
                }
            }
        }
    }

    previewVideoData?.let { (uri, title) ->
        VideoPlayerDialog(
            videoUriString = uri,
            title = title,
            onDismiss = { previewVideoData = null }
        )
    }
}

@Composable
private fun EmptyVaultPlaceholder(
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_vault),
                contentDescription = "Empty vault illustration",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = StudioTextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary
            )
        }
    }
}

@Composable
private fun VaultClipCard(
    clip: DownloadedClipEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onAnalyze: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
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
                Text(
                    text = "${clip.platformName.uppercase()} · ${formatFileSize(clip.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioCyan
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_clip_${clip.id}")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete clip", tint = StudioTextSecondary)
                }
            }

            Text(
                text = clip.title,
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "${clip.author} · ${formatTimestamp(clip.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPlay,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = StudioTextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", color = StudioTextPrimary, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = StudioTextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", color = StudioTextPrimary, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = onAnalyze,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCyan,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Prompt", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun VaultBlueprintCard(
    blueprint: PromptBlueprintEntity,
    onOpen: () -> Unit,
    onCopyMaster: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioCoral.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen() }
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
                Text(
                    text = "${blueprint.targetGeneratorName} · ${blueprint.geminiModelUsed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioCoral
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_blueprint_${blueprint.id}")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete blueprint", tint = StudioTextSecondary)
                }
            }

            Text(
                text = blueprint.videoTitle,
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = blueprint.masterVideoPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyMaster,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Prompt", color = StudioEmerald, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioElevated,
                        contentColor = StudioCyan
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Open Full Pack", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun VaultTranscriptCard(
    entity: VideoTranscriptEntity,
    onOpen: () -> Unit,
    onCopyAll: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen() }
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
                Text(
                    text = "${entity.detectedLanguage} · ${entity.segmentCount} segments",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioEmerald
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_transcript_${entity.id}")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete transcript", tint = StudioTextSecondary)
                }
            }

            Text(
                text = entity.videoTitle,
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = entity.previewSnippet,
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyAll,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = StudioEmerald, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Script", color = StudioEmerald, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioElevated,
                        contentColor = StudioCyan
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Open Full", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
}
