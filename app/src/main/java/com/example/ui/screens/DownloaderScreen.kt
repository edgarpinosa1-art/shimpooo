package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.DownloadedClipEntity
import com.example.data.model.DownloadedVideoResult
import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.SocialPlatform
import com.example.ui.DownloaderUiState
import com.example.ui.components.PlatformBadge
import com.example.ui.components.StudioTopHeader
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.components.formatDownloadSpeed
import com.example.ui.components.formatDurationSeconds
import com.example.ui.components.formatFileSize
import com.example.ui.components.readClipboardText
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCoral
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioElevated
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioError
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloaderScreen(
    urlInput: String,
    detectedPlatform: SocialPlatform,
    downloaderState: DownloaderUiState,
    recentClips: List<DownloadedClipEntity>,
    hasApiKey: Boolean,
    providerLabel: String = "Gemini",
    onUrlChanged: (String) -> Unit,
    onClearUrl: () -> Unit,
    onExtractClick: () -> Unit,
    onDownloadClick: (ExtractedVideoInfo) -> Unit,
    onDownloadAudioClick: (ExtractedVideoInfo) -> Unit,
    onSendClipToPromptStudio: (localPath: String, contentUri: String) -> Unit,
    onSendClipToTranscript: (localPath: String, contentUri: String) -> Unit,
    onSendClipToDubber: (localPath: String, contentUri: String) -> Unit,
    onOpenKeySheet: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
                    title = stringResource(R.string.downloader_headline),
                    subtitle = stringResource(R.string.downloader_subtitle),
                    hasApiKey = hasApiKey,
                    providerLabel = providerLabel,
                    onOpenKeySheet = onOpenKeySheet
                )
            }

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
                        // Platform detection pills
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                SocialPlatform.TIKTOK,
                                SocialPlatform.FACEBOOK,
                                SocialPlatform.INSTAGRAM,
                                SocialPlatform.YOUTUBE,
                                SocialPlatform.PINTEREST
                            ).forEach { platform ->
                                val active = detectedPlatform == platform ||
                                    detectedPlatform == SocialPlatform.UNKNOWN
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (detectedPlatform == platform) {
                                        platform.accentColor.copy(alpha = 0.2f)
                                    } else {
                                        StudioElevated
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (detectedPlatform == platform) platform.accentColor else StudioBorder
                                    )
                                ) {
                                    Text(
                                        text = platform.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) platform.accentColor else StudioTextMuted,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = onUrlChanged,
                            label = { Text(stringResource(R.string.url_input_label)) },
                            placeholder = {
                                Text(
                                    stringResource(R.string.url_input_placeholder),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = null,
                                    tint = if (detectedPlatform != SocialPlatform.UNKNOWN) {
                                        detectedPlatform.accentColor
                                    } else {
                                        StudioCyan
                                    }
                                )
                            },
                            trailingIcon = {
                                Row {
                                    if (urlInput.isNotEmpty()) {
                                        IconButton(
                                            onClick = onClearUrl,
                                            modifier = Modifier.testTag("clear_url_button")
                                        ) {
                                            Icon(
                                                Icons.Filled.Clear,
                                                contentDescription = "Clear URL",
                                                tint = StudioTextSecondary
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val clipText = readClipboardText(context)
                                            if (!clipText.isNullOrBlank()) {
                                                onUrlChanged(clipText)
                                            } else {
                                                onShowMessage("Clipboard is empty")
                                            }
                                        },
                                        modifier = Modifier.testTag("paste_url_button")
                                    ) {
                                        Icon(
                                            Icons.Filled.ContentPaste,
                                            contentDescription = "Paste URL from clipboard",
                                            tint = StudioCyan
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onExtractClick() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StudioCyan,
                                unfocusedBorderColor = StudioBorder,
                                focusedLabelColor = StudioCyan,
                                cursorColor = StudioCyan
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("video_url_input")
                        )

                        Button(
                            onClick = onExtractClick,
                            enabled = downloaderState !is DownloaderUiState.Extracting &&
                                downloaderState !is DownloaderUiState.Downloading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioCyan,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("extract_video_button")
                        ) {
                            if (downloaderState is DownloaderUiState.Extracting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = StudioObsidian
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Resolving No-Watermark Stream...", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.btn_fetch_video),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            // Result / Error / Progress section
            item {
                AnimatedVisibility(visible = downloaderState !is DownloaderUiState.Idle) {
                    when (downloaderState) {
                        is DownloaderUiState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = StudioError.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, StudioError.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Extraction Notice",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StudioError
                                    )
                                    Text(
                                        text = downloaderState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioTextPrimary
                                    )
                                }
                            }
                        }

                        is DownloaderUiState.Extracted -> {
                            ExtractedVideoCard(
                                info = downloaderState.info,
                                isDownloading = false,
                                progress = null,
                                bytesCopied = 0L,
                                totalBytes = -1L,
                                downloadedResult = null,
                                onDownloadClick = { onDownloadClick(downloaderState.info) },
                                onDownloadAudioClick = { onDownloadAudioClick(downloaderState.info) },
                                onPreviewClick = { uri ->
                                    previewVideoData = uri to downloaderState.info.title
                                },
                                onShareClick = { },
                                onSendToPromptStudio = { },
                                onSendToTranscript = { },
                                onSendToDubber = { }
                            )
                        }

                        is DownloaderUiState.Downloading -> {
                            ExtractedVideoCard(
                                info = downloaderState.info,
                                isDownloading = true,
                                progress = downloaderState.progress,
                                bytesCopied = downloaderState.bytesCopied,
                                totalBytes = downloaderState.totalBytes,
                                speedBytesPerSec = downloaderState.speedBytesPerSec,
                                activeConnections = downloaderState.activeConnections,
                                downloadedResult = null,
                                onDownloadClick = { },
                                onDownloadAudioClick = { },
                                onPreviewClick = { },
                                onShareClick = { },
                                onSendToPromptStudio = { },
                                onSendToTranscript = { },
                                onSendToDubber = { }
                            )
                        }

                        is DownloaderUiState.Downloaded -> {
                            ExtractedVideoCard(
                                info = downloaderState.info,
                                isDownloading = false,
                                progress = 1f,
                                bytesCopied = downloaderState.result.fileSizeBytes,
                                totalBytes = downloaderState.result.fileSizeBytes,
                                downloadedResult = downloaderState.result,
                                onDownloadClick = { },
                                onDownloadAudioClick = { onDownloadAudioClick(downloaderState.info) },
                                onPreviewClick = { uri ->
                                    previewVideoData = uri to downloaderState.info.title
                                },
                                onShareClick = {
                                    shareLocalVideo(context, downloaderState.result.localFilePath)
                                },
                                onSendToPromptStudio = {
                                    onSendClipToPromptStudio(
                                        downloaderState.result.localFilePath,
                                        downloaderState.result.contentUriString
                                    )
                                },
                                onSendToTranscript = {
                                    onSendClipToTranscript(
                                        downloaderState.result.localFilePath,
                                        downloaderState.result.contentUriString
                                    )
                                },
                                onSendToDubber = {
                                    onSendClipToDubber(
                                        downloaderState.result.localFilePath,
                                        downloaderState.result.contentUriString
                                    )
                                }
                            )
                        }

                        else -> Unit
                    }
                }
            }

            // Recent Downloads quick strip
            if (recentClips.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent Watermark-Free Clips",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary
                    )
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentClips.take(10), key = { it.id }) { clip ->
                            RecentClipCard(
                                clip = clip,
                                onPreview = {
                                    val file = File(clip.localFilePath)
                                    val uriStr = if (file.exists()) {
                                        android.net.Uri.fromFile(file).toString()
                                    } else {
                                        clip.contentUriString
                                    }
                                    previewVideoData = uriStr to clip.title
                                },
                                onAnalyze = {
                                    onSendClipToPromptStudio(clip.localFilePath, clip.contentUriString)
                                }
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
private fun ExtractedVideoCard(
    info: ExtractedVideoInfo,
    isDownloading: Boolean,
    progress: Float?,
    bytesCopied: Long,
    totalBytes: Long,
    downloadedResult: DownloadedVideoResult?,
    onDownloadClick: () -> Unit,
    onDownloadAudioClick: () -> Unit,
    onPreviewClick: (String) -> Unit,
    onShareClick: () -> Unit,
    onSendToPromptStudio: () -> Unit,
    onSendToTranscript: () -> Unit,
    onSendToDubber: () -> Unit,
    speedBytesPerSec: Long = 0L,
    activeConnections: Int = 1
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, info.platform.accentColor.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 92.dp, height = 124.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(StudioElevated)
                        .border(1.dp, StudioBorder, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!info.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = info.thumbnailUrl,
                            contentDescription = "Video thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = StudioCyan,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    if (info.durationSec > 0) {
                        Surface(
                            color = StudioObsidian.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = formatDurationSeconds(info.durationSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PlatformBadge(platform = info.platform)

                    Text(
                        text = info.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = info.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Surface(
                        color = StudioElevated,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${info.extractionMethod} · ${if (info.hasHdQuality) "HD Ready" else "SD"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (isDownloading) {
                val speedText = formatDownloadSpeed(speedBytesPerSec)
                val threadBadge = if (activeConnections > 1) "⚡ ${activeConnections}x Fast Threads" else "Fast Buffered Stream"
                val metaSuffix = listOfNotNull(
                    speedText.takeIf { it.isNotBlank() },
                    threadBadge
                ).joinToString(" · ")

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            color = StudioCyan,
                            trackColor = StudioElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50))
                        )
                        val pct = (progress * 100).toInt()
                        Text(
                            text = "Fast Download ($pct%) · ${formatFileSize(bytesCopied)} / ${formatFileSize(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    } else {
                        LinearProgressIndicator(
                            color = StudioCyan,
                            trackColor = StudioElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50))
                        )
                        Text(
                            text = "Fast Download · ${formatFileSize(bytesCopied)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                    if (metaSuffix.isNotBlank()) {
                        Text(
                            text = metaSuffix,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioCyan
                        )
                    }
                }
            } else if (downloadedResult == null) {
                Button(
                    onClick = onDownloadClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioEmerald,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("save_no_watermark_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_download_mp4), style = MaterialTheme.typography.labelLarge)
                }

                if (!info.audioUrl.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = onDownloadAudioClick,
                        border = BorderStroke(1.dp, StudioBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("download_mp3_audio_button")
                    ) {
                        Text("Save Audio Track", color = StudioCyan, style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Surface(
                    color = StudioEmerald.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StudioEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (downloadedResult.gallerySaved) {
                                "Saved to Movies/GizmoohDownloader (${formatFileSize(downloadedResult.fileSizeBytes)})"
                            } else {
                                "Saved locally (${formatFileSize(downloadedResult.fileSizeBytes)})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioEmerald
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val file = File(downloadedResult.localFilePath)
                            val uriStr = if (file.exists()) {
                                android.net.Uri.fromFile(file).toString()
                            } else {
                                downloadedResult.contentUriString
                            }
                            onPreviewClick(uriStr)
                        },
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("preview_downloaded_button")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = StudioTextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_open_player), color = StudioTextPrimary)
                    }

                    OutlinedButton(
                        onClick = onShareClick,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_downloaded_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = StudioTextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_share_video), color = StudioTextPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(listOf(StudioCyan, StudioCoral))
                        )
                        .clickable { onSendToPromptStudio() }
                        .testTag("send_to_prompt_studio_button")
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
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
                            text = "Extract AI Ingredients & Video Prompt →",
                            style = MaterialTheme.typography.labelLarge,
                            color = StudioObsidian
                        )
                    }
                }

                OutlinedButton(
                    onClick = onSendToTranscript,
                    border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("send_to_transcript_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = null,
                        tint = StudioCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Generate Timestamped Transcript →",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioCyan
                    )
                }

                OutlinedButton(
                    onClick = onSendToDubber,
                    border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("send_to_dubber_button")
                ) {
                    Text(
                        text = "Dub Video to Filipino →",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioEmerald
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentClipCard(
    clip: DownloadedClipEntity,
    onPreview: () -> Unit,
    onAnalyze: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.width(210.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = clip.platformName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioCyan
                )
                Text(
                    text = formatFileSize(clip.fileSizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
            }

            Text(
                text = clip.title,
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(42.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    border = BorderStroke(1.dp, StudioBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play clip", tint = StudioTextPrimary, modifier = Modifier.size(16.dp))
                }
                Button(
                    onClick = onAnalyze,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCyan.copy(alpha = 0.2f),
                        contentColor = StudioCyan
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(2f)
                        .height(40.dp)
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Prompt", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

fun shareLocalVideo(context: android.content.Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
}
