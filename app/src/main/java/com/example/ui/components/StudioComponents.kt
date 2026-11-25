package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.local.UserSettings
import com.example.data.model.AiProvider
import com.example.data.model.ModelCapabilityTag
import com.example.data.model.SocialPlatform
import com.example.data.model.ThirdPartyModelCatalog
import com.example.ui.ApiKeyCheckState
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

fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f KB", kb)
    }
}

fun formatDownloadSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return ""
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB/s", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f KB/s", kb)
    }
}

fun formatDurationSeconds(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", m, s)
}

@Composable
fun StudioTopHeader(
    title: String,
    subtitle: String,
    hasApiKey: Boolean,
    onOpenKeySheet: () -> Unit,
    modifier: Modifier = Modifier,
    providerLabel: String = "Gemini",
    offlineOpenSourceLabel: String? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, StudioBorder, RoundedCornerShape(22.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_studio_banner),
            contentDescription = "Gizmooh Downloader creator studio backdrop",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            StudioObsidian.copy(alpha = 0.55f),
                            StudioObsidian.copy(alpha = 0.92f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(StudioCyan)
                    )
                    Text(
                        text = "GIZMOOH STUDIO",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioCyan
                    )
                }

                if (offlineOpenSourceLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioEmerald.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("header_open_source_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Open-source offline ready",
                                tint = StudioEmerald,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = offlineOpenSourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioEmerald
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (hasApiKey) StudioEmerald.copy(alpha = 0.16f) else StudioCoral.copy(alpha = 0.18f),
                        border = BorderStroke(
                            1.dp,
                            if (hasApiKey) StudioEmerald.copy(alpha = 0.6f) else StudioCoral.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier
                            .testTag("header_api_key_chip")
                            .clip(RoundedCornerShape(50))
                            .clickable { onOpenKeySheet() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (hasApiKey) Icons.Filled.CheckCircle else Icons.Filled.Key,
                                contentDescription = "Manage AI API key",
                                tint = if (hasApiKey) StudioEmerald else StudioCoral,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (hasApiKey) "$providerLabel Ready" else "Set AI Key",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasApiKey) StudioEmerald else StudioCoral
                            )
                        }
                    }
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = StudioTextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary
            )
        }
    }
}

@Composable
fun PlatformBadge(
    platform: SocialPlatform,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isActive) platform.accentColor else StudioBorder
    val bgColor = if (isActive) platform.accentColor.copy(alpha = 0.15f) else StudioElevated
    val textColor = if (isActive) platform.accentColor else StudioTextSecondary

    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Text(
            text = platform.badgeTag,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun CopyablePromptBlock(
    title: String,
    badge: String?,
    promptText: String,
    negativePrompt: String? = null,
    accentColor: Color = StudioCyan,
    onCopy: (String) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
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
                    if (!badge.isNullOrBlank()) {
                        Text(
                            text = badge.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                }

                OutlinedButton(
                    onClick = { onCopy(promptText) },
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
                    modifier = Modifier.testTag("${testTagPrefix}_copy_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy $title",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", style = MaterialTheme.typography.labelLarge)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioObsidian)
                    .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = promptText,
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextPrimary
                )
            }

            if (!negativePrompt.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Negative: $negativePrompt",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onCopy(negativePrompt) },
                        modifier = Modifier.testTag("${testTagPrefix}_copy_negative_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy negative prompt",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyConfigBottomSheet(
    settings: UserSettings,
    checkState: ApiKeyCheckState,
    onSelectProvider: (AiProvider) -> Unit,
    onSaveAndVerifyConfig: (provider: AiProvider, key: String, baseUrl: String) -> Unit,
    onSelectGeminiModel: (String) -> Unit,
    onSelectSttModel: (String) -> Unit,
    onSelectChatModel: (String) -> Unit,
    onSaveCustomCobaltUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var providerDraft by remember(settings.activeProvider) { mutableStateOf(settings.activeProvider) }
    var geminiKeyDraft by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var thirdPartyKeyDraft by remember(settings.thirdPartyApiKey) { mutableStateOf(settings.thirdPartyApiKey) }
    var baseUrlDraft by remember(settings.activeProvider, settings.thirdPartyBaseUrl) {
        mutableStateOf(
            if (settings.activeProvider == AiProvider.CUSTOM_OPENAI) {
                settings.thirdPartyBaseUrl
            } else {
                settings.activeProvider.defaultBaseUrl
            }
        )
    }

    var geminiModelDraft by remember(settings.selectedGeminiModel) {
        mutableStateOf(settings.selectedGeminiModel)
    }
    var sttModelDraft by remember(settings.selectedSttModel) {
        mutableStateOf(settings.selectedSttModel)
    }
    var chatModelDraft by remember(settings.selectedChatModel) {
        mutableStateOf(settings.selectedChatModel)
    }
    var cobaltDraft by remember(settings.customCobaltUrl) {
        mutableStateOf(settings.customCobaltUrl)
    }
    var showKey by remember { mutableStateOf(false) }

    val activeKeyDraft = if (providerDraft == AiProvider.GEMINI) geminiKeyDraft else thirdPartyKeyDraft

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface,
        contentColor = StudioTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = StudioCyan
                    )
                    Text(
                        text = "AI Provider, Key & Models",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioTextPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = StudioTextSecondary)
                }
            }

            Text(
                text = "Pwedeng Gemini o 3rd-party API (Groq free tier, OpenAI, OpenRouter, o Custom URL). Automatic na ide-detect ng app ang lahat ng available Audio STT at Translator models ng key mo.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )

            Text(
                text = "1. Select AI Provider",
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiProvider.entries.forEach { provider ->
                    val selected = providerDraft == provider
                    FilterChip(
                        selected = selected,
                        onClick = {
                            providerDraft = provider
                            if (provider != AiProvider.GEMINI) {
                                baseUrlDraft = if (provider == AiProvider.CUSTOM_OPENAI) {
                                    settings.thirdPartyBaseUrl
                                } else {
                                    provider.defaultBaseUrl
                                }
                                // Set sensible defaults for that provider if draft still points to another provider
                                if (provider == AiProvider.GROQ && !sttModelDraft.contains("whisper")) {
                                    sttModelDraft = "whisper-large-v3-turbo"
                                    onSelectSttModel("whisper-large-v3-turbo")
                                }
                                if (provider == AiProvider.OPENROUTER) {
                                    sttModelDraft = "google/gemini-2.5-flash"
                                    chatModelDraft = "google/gemini-2.5-flash"
                                    onSelectSttModel("google/gemini-2.5-flash")
                                    onSelectChatModel("google/gemini-2.5-flash")
                                }
                            }
                            onSelectProvider(provider)
                        },
                        label = { Text(provider.displayName, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioCyan.copy(alpha = 0.22f),
                            selectedLabelColor = StudioCyan
                        ),
                        modifier = Modifier.testTag("provider_chip_${provider.name}")
                    )
                }
            }

            Text(
                text = providerDraft.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = StudioCyan
            )

            if (providerDraft.isThirdParty) {
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it },
                    enabled = providerDraft == AiProvider.CUSTOM_OPENAI,
                    label = { Text("API Base URL") },
                    placeholder = { Text("https://api.example.com/openai/v1") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioCyan,
                        unfocusedBorderColor = StudioBorder,
                        focusedLabelColor = StudioCyan,
                        cursorColor = StudioCyan
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("third_party_base_url_input")
                )
            }

            OutlinedTextField(
                value = activeKeyDraft,
                onValueChange = { newVal ->
                    if (providerDraft == AiProvider.GEMINI) {
                        geminiKeyDraft = newVal
                    } else {
                        thirdPartyKeyDraft = newVal
                    }
                },
                label = { Text(providerDraft.keyHintLabel) },
                placeholder = { Text(providerDraft.keyPlaceholder) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    Row {
                        IconButton(
                            onClick = {
                                readClipboardText(context)?.trim()?.let { pasted ->
                                    if (providerDraft == AiProvider.GEMINI) {
                                        geminiKeyDraft = pasted
                                    } else {
                                        thirdPartyKeyDraft = pasted
                                    }
                                }
                            },
                            modifier = Modifier.testTag("paste_api_key_button")
                        ) {
                            Icon(
                                Icons.Filled.ContentPaste,
                                contentDescription = "Paste API Key",
                                tint = StudioCyan
                            )
                        }
                        IconButton(
                            onClick = { showKey = !showKey },
                            modifier = Modifier.testTag("toggle_api_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle key visibility",
                                tint = StudioTextSecondary
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioCyan,
                    unfocusedBorderColor = StudioBorder,
                    focusedLabelColor = StudioCyan,
                    cursorColor = StudioCyan
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gemini_api_key_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onSaveAndVerifyConfig(providerDraft, activeKeyDraft, baseUrlDraft)
                    },
                    enabled = checkState !is ApiKeyCheckState.Verifying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCyan,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("verify_save_api_key_button")
                ) {
                    if (checkState is ApiKeyCheckState.Verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = StudioObsidian
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Detecting Models...")
                    } else {
                        Text("Verify & Detect Models", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (activeKeyDraft.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            if (providerDraft == AiProvider.GEMINI) {
                                geminiKeyDraft = ""
                            } else {
                                thirdPartyKeyDraft = ""
                            }
                            onSaveAndVerifyConfig(providerDraft, "", baseUrlDraft)
                        },
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("clear_api_key_button")
                    ) {
                        Text("Clear", color = StudioTextSecondary)
                    }
                }
            }

            when (checkState) {
                is ApiKeyCheckState.Valid -> {
                    Surface(
                        color = StudioEmerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioEmerald.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = checkState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioEmerald,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                is ApiKeyCheckState.Invalid -> {
                    Surface(
                        color = StudioError.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioError.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = checkState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioError,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                else -> Unit
            }

            if (providerDraft == AiProvider.GEMINI) {
                Text(
                    text = "2. Active Gemini Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioTextPrimary
                )

                val geminiOptions = (checkState as? ApiKeyCheckState.Valid)
                    ?.takeIf { it.provider == AiProvider.GEMINI && it.liveModels.isNotEmpty() }
                    ?.liveModels
                    ?: UserSettings.AVAILABLE_MODELS

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    geminiOptions.forEach { (modelId, label) ->
                        val selected = geminiModelDraft == modelId
                        FilterChip(
                            selected = selected,
                            onClick = {
                                geminiModelDraft = modelId
                                onSelectGeminiModel(modelId)
                            },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioCyan.copy(alpha = 0.2f),
                                selectedLabelColor = StudioCyan
                            ),
                            modifier = Modifier.testTag("model_chip_$modelId")
                        )
                    }
                }

                OutlinedTextField(
                    value = geminiModelDraft,
                    onValueChange = {
                        geminiModelDraft = it
                        onSelectGeminiModel(it)
                    },
                    label = { Text("Active Gemini Model ID") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioCyan,
                        unfocusedBorderColor = StudioBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_model_id_input")
                )
            } else {
                val detected = (checkState as? ApiKeyCheckState.Valid)
                    ?.takeIf { it.provider == providerDraft }
                    ?.detectedModels
                    .orEmpty()

                val sttOptions = detected.filter {
                    it.capability == ModelCapabilityTag.AUDIO_STT ||
                        it.capability == ModelCapabilityTag.MULTIMODAL_AUDIO
                }.ifEmpty {
                    when (providerDraft) {
                        AiProvider.GROQ -> ThirdPartyModelCatalog.GROQ_DEFAULT_STT_MODELS
                        AiProvider.OPENAI -> ThirdPartyModelCatalog.OPENAI_DEFAULT_STT_MODELS
                        AiProvider.OPENROUTER -> ThirdPartyModelCatalog.OPENROUTER_DEFAULT_CHAT_MODELS
                        else -> ThirdPartyModelCatalog.GROQ_DEFAULT_STT_MODELS
                    }
                }

                val chatOptions = detected.filter {
                    it.capability != ModelCapabilityTag.AUDIO_STT
                }.ifEmpty {
                    when (providerDraft) {
                        AiProvider.GROQ -> ThirdPartyModelCatalog.GROQ_DEFAULT_CHAT_MODELS
                        AiProvider.OPENAI -> ThirdPartyModelCatalog.OPENAI_DEFAULT_CHAT_MODELS
                        AiProvider.OPENROUTER -> ThirdPartyModelCatalog.OPENROUTER_DEFAULT_CHAT_MODELS
                        else -> ThirdPartyModelCatalog.OPENAI_DEFAULT_CHAT_MODELS
                    }
                }

                Text(
                    text = "2. Audio Transcript Model (STT)",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioTextPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sttOptions.forEach { m ->
                        val selected = sttModelDraft == m.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                sttModelDraft = m.id
                                onSelectSttModel(m.id)
                            },
                            label = {
                                Text(
                                    text = "${m.id} · ${m.capability.badge}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioEmerald.copy(alpha = 0.22f),
                                selectedLabelColor = StudioEmerald
                            ),
                            modifier = Modifier.testTag("stt_model_chip_${m.id}")
                        )
                    }
                }

                OutlinedTextField(
                    value = sttModelDraft,
                    onValueChange = {
                        sttModelDraft = it
                        onSelectSttModel(it)
                    },
                    label = { Text("Active STT / Audio Model ID") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioEmerald,
                        unfocusedBorderColor = StudioBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_stt_model_id_input")
                )

                Text(
                    text = "3. Filipino Translator & Chat Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioTextPrimary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chatOptions.take(25).forEach { m ->
                        val selected = chatModelDraft == m.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                chatModelDraft = m.id
                                onSelectChatModel(m.id)
                            },
                            label = {
                                Text(
                                    text = "${m.id} · ${m.capability.badge}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioCyan.copy(alpha = 0.22f),
                                selectedLabelColor = StudioCyan
                            ),
                            modifier = Modifier.testTag("chat_model_chip_${m.id}")
                        )
                    }
                }

                OutlinedTextField(
                    value = chatModelDraft,
                    onValueChange = {
                        chatModelDraft = it
                        onSelectChatModel(it)
                    },
                    label = { Text("Active Translator / Chat Model ID") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioCyan,
                        unfocusedBorderColor = StudioBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_chat_model_id_input")
                )
            }

            Text(
                text = "Optional: Self-Hosted Cobalt Instance URL",
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary
            )
            OutlinedTextField(
                value = cobaltDraft,
                onValueChange = {
                    cobaltDraft = it
                    onSaveCustomCobaltUrl(it)
                },
                label = { Text("Cobalt API URL (optional fallback)") },
                placeholder = { Text("https://your-cobalt-instance.example") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioCyan,
                    unfocusedBorderColor = StudioBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_cobalt_url_input")
            )
        }
    }
}

@Composable
fun VideoPlayerDialog(
    videoUriString: String,
    title: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = StudioObsidian,
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_video_player_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close player", tint = StudioTextPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 14f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoURI(Uri.parse(videoUriString))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
