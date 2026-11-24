package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.data.model.LocalVideoInspection
import com.example.data.model.TranscriptLanguageMode
import com.example.data.model.TranscriptSegment
import com.example.data.model.VideoTranscript
import com.example.data.network.TranscriptSerializer
import com.example.ui.TranscriptUiState
import com.example.ui.components.CopyablePromptBlock
import com.example.ui.components.StudioTopHeader
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.copyTextToClipboard
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
import com.example.ui.theme.StudioViolet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptScreen(
    settings: UserSettings,
    selectedVideo: LocalVideoInspection?,
    languageMode: TranscriptLanguageMode,
    transcriptState: TranscriptUiState,
    downloadedClips: List<DownloadedClipEntity>,
    onVideoPicked: (android.net.Uri) -> Unit,
    onSelectDownloadedClip: (localPath: String, contentUri: String) -> Unit,
    onSelectLanguageMode: (TranscriptLanguageMode) -> Unit,
    onGenerateTranscriptClick: () -> Unit,
    onSendTranscriptToDubber: (VideoTranscript) -> Unit,
    onOpenKeySheet: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var previewPlayerUri by remember { mutableStateOf<Pair<String, String>?>(null) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) onVideoPicked(uri)
        }
    )

    val documentVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) onVideoPicked(uri)
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
                    title = stringResource(R.string.transcript_headline),
                    subtitle = stringResource(R.string.transcript_subtitle),
                    hasApiKey = settings.hasApiKey,
                    onOpenKeySheet = onOpenKeySheet
                )
            }

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
                            .testTag("transcript_missing_key_banner")
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
            }

            // 1. Input Video Card
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
                            text = "1. Input Video for Transcription",
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
                                    .testTag("transcript_pick_video_button")
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
                                    .testTag("transcript_pick_files_button")
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
                                                onSelectDownloadedClip(clip.localFilePath, clip.contentUriString)
                                            }
                                            .testTag("transcript_quick_clip_${clip.id}")
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
                                                text = clip.title.take(24),
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

                        if (transcriptState is TranscriptUiState.InspectingVideo) {
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
                                    text = "Reading video metadata...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioCyan
                                )
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
                                        onClick = {
                                            previewPlayerUri = selectedVideo.uriString to selectedVideo.displayName
                                        },
                                        border = BorderStroke(1.dp, StudioBorder),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play", color = StudioTextPrimary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Language Mode & Generate CTA
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
                            text = "2. Transcript Language & Output",
                            style = MaterialTheme.typography.titleLarge,
                            color = StudioTextPrimary
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TranscriptLanguageMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = languageMode == mode,
                                    onClick = { onSelectLanguageMode(mode) },
                                    label = { Text(mode.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = StudioCyan
                                    ),
                                    modifier = Modifier.testTag("transcript_mode_${mode.name}")
                                )
                            }
                        }

                        Text(
                            text = languageMode.directive,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )

                        val isTranscribing = transcriptState is TranscriptUiState.Transcribing
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isTranscribing) {
                                        Brush.horizontalGradient(listOf(StudioElevated, StudioElevated))
                                    } else {
                                        Brush.horizontalGradient(listOf(StudioCyan, StudioEmerald))
                                    }
                                )
                                .clickable(enabled = !isTranscribing) { onGenerateTranscriptClick() }
                                .testTag("generate_transcript_button")
                                .padding(vertical = 15.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTranscribing) {
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
                                        text = transcriptState.stage,
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
                                        imageVector = Icons.Filled.Subtitles,
                                        contentDescription = null,
                                        tint = StudioObsidian,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.btn_generate_transcript),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StudioObsidian
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (transcriptState is TranscriptUiState.Error) {
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
                                text = "Transcription Error",
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioError
                            )
                            Text(
                                text = transcriptState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextPrimary
                            )
                        }
                    }
                }
            }

            if (transcriptState is TranscriptUiState.Success) {
                val transcript = transcriptState.transcript
                val srtText = TranscriptSerializer.formatSrtSubtitles(transcript.segments)

                item {
                    TranscriptSummaryHeader(
                        transcript = transcript,
                        onCopyTimestamped = {
                            val formatted = TranscriptSerializer.formatTimestampedText(transcript)
                            copyTextToClipboard(context, "Timestamped Transcript", formatted)
                            onShowMessage("Complete timestamped transcript copied!")
                        },
                        onCopySrt = {
                            copyTextToClipboard(context, "SRT Subtitles", srtText)
                            onShowMessage("Copied .SRT subtitle format!")
                        },
                        onCopyPlain = {
                            copyTextToClipboard(context, "Plain Transcript", transcript.fullPlainTranscript)
                            onShowMessage("Copied plain text transcript!")
                        },
                        onSendToDubber = { onSendTranscriptToDubber(transcript) }
                    )
                }

                item {
                    Text(
                        text = "Timestamped Timeline (${transcript.segments.size} segments)",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary
                    )
                }

                items(transcript.segments) { segment ->
                    TranscriptSegmentCard(
                        segment = segment,
                        onCopyLine = {
                            val line = "[${segment.startTime} - ${segment.endTime}] ${segment.speaker}: ${segment.text}"
                            copyTextToClipboard(context, "Transcript Line", line)
                            onShowMessage("Copied ${segment.startTime} line!")
                        }
                    )
                }

                if (transcript.fullPlainTranscript.isNotBlank()) {
                    item {
                        CopyablePromptBlock(
                            title = "Complete Plain Transcript",
                            badge = "${transcript.detectedLanguage} · NO TIMESTAMPS",
                            promptText = transcript.fullPlainTranscript,
                            accentColor = StudioViolet,
                            onCopy = { txt ->
                                copyTextToClipboard(context, "Full Transcript", txt)
                                onShowMessage("Copied full plain transcript!")
                            },
                            testTagPrefix = "full_plain_transcript"
                        )
                    }
                }

                if (srtText.isNotBlank()) {
                    item {
                        CopyablePromptBlock(
                            title = "SRT Subtitle File Format",
                            badge = "CAPCUT / PREMIERE / YOUTUBE READY",
                            promptText = srtText,
                            accentColor = StudioEmerald,
                            onCopy = { txt ->
                                copyTextToClipboard(context, "SRT Subtitles", txt)
                                onShowMessage("Copied .SRT subtitles!")
                            },
                            testTagPrefix = "srt_subtitles"
                        )
                    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptSummaryHeader(
    transcript: VideoTranscript,
    onCopyTimestamped: () -> Unit,
    onCopySrt: () -> Unit,
    onCopyPlain: () -> Unit,
    onSendToDubber: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioEmerald.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "COMPLETE TRANSCRIPT · ${transcript.detectedLanguage.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = StudioEmerald
            )
            Text(
                text = transcript.videoTitle,
                style = MaterialTheme.typography.titleLarge,
                color = StudioTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (transcript.overallSummary.isNotBlank()) {
                Text(
                    text = transcript.overallSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextPrimary
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopyTimestamped,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioEmerald,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier.testTag("copy_timestamped_transcript_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Timestamped", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onCopySrt,
                    border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("copy_srt_transcript_button")
                ) {
                    Text("Copy .SRT", color = StudioCyan, style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onCopyPlain,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.testTag("copy_plain_transcript_button")
                ) {
                    Text("Plain Text", color = StudioTextPrimary, style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onSendToDubber,
                    border = BorderStroke(1.dp, StudioCoral.copy(alpha = 0.7f)),
                    modifier = Modifier.testTag("transcript_to_dubber_button")
                ) {
                    Text("Dub in Filipino →", color = StudioCoral, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun TranscriptSegmentCard(
    segment: TranscriptSegment,
    onCopyLine: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            text = "${segment.startTime} → ${segment.endTime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                    }
                    Text(
                        text = segment.speaker,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCoral
                    )
                }

                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextPrimary
                )
            }

            IconButton(onClick = onCopyLine) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy transcript segment",
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
