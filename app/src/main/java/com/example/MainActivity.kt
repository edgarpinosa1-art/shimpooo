package com.example

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.dubbing.DubbingForegroundService
import com.example.ui.DubRenderUiState
import com.example.ui.DubScriptUiState
import com.example.ui.ReelForgeViewModel
import com.example.ui.components.ApiKeyConfigBottomSheet
import com.example.ui.screens.DownloaderScreen
import com.example.ui.screens.DubberScreen
import com.example.ui.screens.PromptStudioScreen
import com.example.ui.screens.TranscriptScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.ReelForgeTheme
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioElevated
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioTextSecondary
import kotlinx.coroutines.launch

const val ROUTE_DOWNLOADER = "downloader"
const val ROUTE_TRANSCRIPT = "transcript"
const val ROUTE_DUBBER = "dubber"
const val ROUTE_PROMPT_STUDIO = "prompt_studio"
const val ROUTE_VAULT = "vault"

sealed interface SharedLaunchPayload {
    data class TextUrl(val text: String, val nonce: Long) : SharedLaunchPayload
    data class VideoUri(val uri: Uri, val nonce: Long) : SharedLaunchPayload
    data class OpenDubber(val nonce: Long) : SharedLaunchPayload
}

private data class NavDestinationItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    private val pendingSharedPayload = mutableStateOf<SharedLaunchPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        handleIncomingShareIntent(intent)
        setContent {
            ReelForgeTheme {
                ReelForgeApp(
                    sharedPayload = pendingSharedPayload.value,
                    onSharedPayloadConsumed = { pendingSharedPayload.value = null },
                    onBackgroundTaskRequested = { moveTaskToBack(true) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val nonce = System.currentTimeMillis()
        if (intent.getBooleanExtra(DubbingForegroundService.EXTRA_OPEN_DUBBER, false)) {
            pendingSharedPayload.value = SharedLaunchPayload.OpenDubber(nonce)
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        when {
            type.startsWith("text/") -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    pendingSharedPayload.value = SharedLaunchPayload.TextUrl(sharedText, nonce)
                }
            }
            type.startsWith("video/") -> {
                val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (videoUri != null) {
                    pendingSharedPayload.value = SharedLaunchPayload.VideoUri(videoUri, nonce)
                }
            }
        }
    }
}

@Composable
fun ReelForgeApp(
    sharedPayload: SharedLaunchPayload? = null,
    onSharedPayloadConsumed: () -> Unit = {},
    onBackgroundTaskRequested: () -> Unit = {},
    viewModel: ReelForgeViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_DOWNLOADER

    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val detectedPlatform by viewModel.detectedPlatform.collectAsStateWithLifecycle()
    val downloaderState by viewModel.downloaderState.collectAsStateWithLifecycle()
    val selectedVideo by viewModel.selectedVideo.collectAsStateWithLifecycle()
    val selectedAspectRatio by viewModel.selectedAspectRatio.collectAsStateWithLifecycle()
    val selectedUpgradePreset by viewModel.selectedUpgradePreset.collectAsStateWithLifecycle()
    val extraNotes by viewModel.extraCreatorNotes.collectAsStateWithLifecycle()
    val studioState by viewModel.studioState.collectAsStateWithLifecycle()
    val transcriptLanguageMode by viewModel.transcriptLanguageMode.collectAsStateWithLifecycle()
    val transcriptState by viewModel.transcriptState.collectAsStateWithLifecycle()
    val apiKeyCheckState by viewModel.apiKeyCheckState.collectAsStateWithLifecycle()
    val downloadedClips by viewModel.downloadedClips.collectAsStateWithLifecycle()
    val promptBlueprints by viewModel.promptBlueprints.collectAsStateWithLifecycle()
    val videoTranscripts by viewModel.videoTranscripts.collectAsStateWithLifecycle()
    val dubberState by viewModel.dubberState.collectAsStateWithLifecycle()

    var showApiKeySheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    val destinations = remember {
        listOf(
            NavDestinationItem(
                route = ROUTE_DOWNLOADER,
                labelRes = R.string.nav_downloader,
                selectedIcon = Icons.Filled.Download,
                unselectedIcon = Icons.Outlined.Download,
                testTag = "nav_tab_downloader"
            ),
            NavDestinationItem(
                route = ROUTE_TRANSCRIPT,
                labelRes = R.string.nav_transcript,
                selectedIcon = Icons.Filled.Subtitles,
                unselectedIcon = Icons.Outlined.Subtitles,
                testTag = "nav_tab_transcript"
            ),
            NavDestinationItem(
                route = ROUTE_DUBBER,
                labelRes = R.string.nav_dubber,
                selectedIcon = Icons.Filled.RecordVoiceOver,
                unselectedIcon = Icons.Outlined.RecordVoiceOver,
                testTag = "nav_tab_dubber"
            ),
            NavDestinationItem(
                route = ROUTE_PROMPT_STUDIO,
                labelRes = R.string.nav_prompt_studio,
                selectedIcon = Icons.Filled.AutoAwesome,
                unselectedIcon = Icons.Outlined.AutoAwesome,
                testTag = "nav_tab_prompt_studio"
            ),
            NavDestinationItem(
                route = ROUTE_VAULT,
                labelRes = R.string.nav_vault,
                selectedIcon = Icons.Filled.VideoLibrary,
                unselectedIcon = Icons.Outlined.VideoLibrary,
                testTag = "nav_tab_vault"
            )
        )
    }

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(sharedPayload) {
        when (sharedPayload) {
            is SharedLaunchPayload.TextUrl -> {
                navigateToTab(ROUTE_DOWNLOADER)
                viewModel.handleSharedTextUrl(sharedPayload.text)
                onSharedPayloadConsumed()
            }
            is SharedLaunchPayload.VideoUri -> {
                navigateToTab(ROUTE_PROMPT_STUDIO)
                viewModel.onLocalVideoSelected(sharedPayload.uri)
                onSharedPayloadConsumed()
            }
            is SharedLaunchPayload.OpenDubber -> {
                navigateToTab(ROUTE_DUBBER)
                onSharedPayloadConsumed()
            }
            null -> Unit
        }
    }

    val isDubbingBusy = dubberState.scriptState is DubScriptUiState.Generating ||
        dubberState.renderState is DubRenderUiState.Rendering ||
        dubberState.mmsModelStatus == com.example.data.model.MmsModelStatus.DOWNLOADING

    BackHandler(enabled = currentRoute == ROUTE_DOWNLOADER && isDubbingBusy) {
        showMessage("Tuloy-tuloy sa background ang dubbing — makikita ang progress sa notification bar.")
        onBackgroundTaskRequested()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = StudioObsidian,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = StudioSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        destinations.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = StudioCyan,
                                    selectedTextColor = StudioCyan,
                                    indicatorColor = StudioElevated,
                                    unselectedIconColor = StudioTextSecondary,
                                    unselectedTextColor = StudioTextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = StudioSurface,
                        modifier = Modifier.statusBarsPadding()
                    ) {
                        destinations.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = StudioCyan,
                                    selectedTextColor = StudioCyan,
                                    indicatorColor = StudioElevated,
                                    unselectedIconColor = StudioTextSecondary,
                                    unselectedTextColor = StudioTextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_DOWNLOADER,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_DOWNLOADER) {
                        DownloaderScreen(
                            urlInput = urlInput,
                            detectedPlatform = detectedPlatform,
                            downloaderState = downloaderState,
                            recentClips = downloadedClips,
                            hasApiKey = settings.hasApiKey,
                            onUrlChanged = viewModel::updateUrlInput,
                            onClearUrl = viewModel::clearDownloader,
                            onExtractClick = viewModel::extractVideoFromUrl,
                            onDownloadClick = viewModel::downloadExtractedVideo,
                            onDownloadAudioClick = { info ->
                                viewModel.downloadAudioTrack(info, showMessage)
                            },
                            onSendClipToPromptStudio = { localPath, contentUri ->
                                viewModel.loadDownloadedClipIntoStudio(localPath, contentUri)
                                navigateToTab(ROUTE_PROMPT_STUDIO)
                            },
                            onSendClipToTranscript = { localPath, contentUri ->
                                viewModel.loadDownloadedClipIntoStudio(localPath, contentUri)
                                navigateToTab(ROUTE_TRANSCRIPT)
                            },
                            onSendClipToDubber = { localPath, contentUri ->
                                viewModel.loadDownloadedClipIntoStudio(localPath, contentUri)
                                navigateToTab(ROUTE_DUBBER)
                            },
                            onOpenKeySheet = {
                                viewModel.resetKeyCheckState()
                                showApiKeySheet = true
                            },
                            onShowMessage = showMessage
                        )
                    }

                    composable(ROUTE_TRANSCRIPT) {
                        TranscriptScreen(
                            settings = settings,
                            selectedVideo = selectedVideo,
                            languageMode = transcriptLanguageMode,
                            transcriptState = transcriptState,
                            downloadedClips = downloadedClips,
                            onVideoPicked = viewModel::onLocalVideoSelected,
                            onSelectDownloadedClip = viewModel::loadDownloadedClipIntoStudio,
                            onSelectLanguageMode = viewModel::selectTranscriptLanguageMode,
                            onGenerateTranscriptClick = {
                                if (!settings.hasApiKey) {
                                    viewModel.resetKeyCheckState()
                                    showApiKeySheet = true
                                } else {
                                    viewModel.transcribeSelectedVideo()
                                }
                            },
                            onSendTranscriptToDubber = { transcript ->
                                viewModel.loadTranscriptIntoDubber(transcript)
                                navigateToTab(ROUTE_DUBBER)
                            },
                            onOpenKeySheet = {
                                viewModel.resetKeyCheckState()
                                showApiKeySheet = true
                            },
                            onShowMessage = showMessage
                        )
                    }

                    composable(ROUTE_DUBBER) {
                        DubberScreen(
                            settings = settings,
                            selectedVideo = selectedVideo,
                            dubberState = dubberState,
                            downloadedClips = downloadedClips,
                            onVideoPicked = viewModel::onLocalVideoSelected,
                            onSelectDownloadedClip = viewModel::loadDownloadedClipIntoStudio,
                            onSelectSourceLanguage = viewModel::selectDubSourceLanguage,
                            onSelectVoiceEngine = viewModel::selectDubVoiceEngine,
                            onSelectDuckingMode = viewModel::selectDubDuckingMode,
                            onSelectAndroidVoice = viewModel::selectAndroidDubVoice,
                            onPitchChanged = viewModel::updateDubVoicePitch,
                            onSpeedChanged = viewModel::updateDubBaseSpeed,
                            onToggleMultiSpeaker = viewModel::toggleMultiSpeaker,
                            onCycleCharacterVoice = viewModel::cycleCharacterVoice,
                            onDownloadMmsModel = viewModel::downloadMmsTagalogModel,
                            onDeleteMmsModel = viewModel::deleteMmsTagalogModel,
                            onGenerateDubScript = viewModel::generateFilipinoDubScript,
                            onImportOfflineScript = { raw ->
                                viewModel.importOfflineDubScript(raw, showMessage)
                            },
                            onUpdateDubLineText = viewModel::updateDubLineFilipinoText,
                            onRemoveDubLine = viewModel::removeDubLine,
                            onRenderDubbedVideo = viewModel::renderFilipinoDubbedVideo,
                            onOpenKeySheet = {
                                viewModel.resetKeyCheckState()
                                showApiKeySheet = true
                            }
                        )
                    }

                    composable(ROUTE_PROMPT_STUDIO) {
                        PromptStudioScreen(
                            settings = settings,
                            selectedVideo = selectedVideo,
                            selectedAspectRatio = selectedAspectRatio,
                            selectedUpgradePreset = selectedUpgradePreset,
                            extraNotes = extraNotes,
                            studioState = studioState,
                            downloadedClips = downloadedClips,
                            onVideoPicked = viewModel::onLocalVideoSelected,
                            onSelectDownloadedClip = viewModel::loadDownloadedClipIntoStudio,
                            onSelectTargetGenerator = viewModel::saveTargetGenerator,
                            onSelectAspectRatio = viewModel::selectAspectRatio,
                            onSelectUpgradePreset = viewModel::selectUpgradePreset,
                            onSaveKeyframesClick = {
                                viewModel.saveSelectedKeyframesToGallery(showMessage)
                            },
                            onExtraNotesChanged = viewModel::updateExtraCreatorNotes,
                            onAnalyzeClick = {
                                if (!settings.hasApiKey) {
                                    viewModel.resetKeyCheckState()
                                    showApiKeySheet = true
                                } else {
                                    viewModel.analyzeSelectedVideo()
                                }
                            },
                            onOpenKeySheet = {
                                viewModel.resetKeyCheckState()
                                showApiKeySheet = true
                            },
                            onShowMessage = showMessage
                        )
                    }

                    composable(ROUTE_VAULT) {
                        VaultScreen(
                            settings = settings,
                            downloadedClips = downloadedClips,
                            promptBlueprints = promptBlueprints,
                            videoTranscripts = videoTranscripts,
                            onTogglePreferHd = viewModel::savePreferHd,
                            onOpenKeySheet = {
                                viewModel.resetKeyCheckState()
                                showApiKeySheet = true
                            },
                            onAnalyzeClip = { localPath, contentUri ->
                                viewModel.loadDownloadedClipIntoStudio(localPath, contentUri)
                                navigateToTab(ROUTE_PROMPT_STUDIO)
                            },
                            onOpenBlueprint = { entity ->
                                viewModel.openBlueprintFromVault(entity)
                                navigateToTab(ROUTE_PROMPT_STUDIO)
                            },
                            onOpenTranscript = { entity ->
                                viewModel.openTranscriptFromVault(entity)
                                navigateToTab(ROUTE_TRANSCRIPT)
                            },
                            onDeleteClip = viewModel::deleteClip,
                            onDeleteBlueprint = viewModel::deleteBlueprint,
                            onDeleteTranscript = viewModel::deleteTranscript,
                            onShowMessage = showMessage
                        )
                    }
                }
            }
        }
    }

    if (showApiKeySheet) {
        ApiKeyConfigBottomSheet(
            settings = settings,
            checkState = apiKeyCheckState,
            onSaveAndVerifyKey = viewModel::saveAndVerifyApiKey,
            onSelectModel = viewModel::saveGeminiModel,
            onSaveCustomCobaltUrl = viewModel::saveCustomCobaltUrl,
            onDismiss = { showApiKeySheet = false }
        )
    }
}
