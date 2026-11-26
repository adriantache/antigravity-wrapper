package com.adriantache.antigravitywrapper

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.adriantache.antigravitywrapper.bridge.AntigravityWebBridge
import com.adriantache.antigravitywrapper.bridge.AttachedFilePayload
import com.adriantache.antigravitywrapper.bridge.BridgeConfig
import com.adriantache.antigravitywrapper.bridge.ComposerState
import com.adriantache.antigravitywrapper.bridge.ModelOption
import com.adriantache.antigravitywrapper.bridge.PermissionOption
import com.adriantache.antigravitywrapper.bridge.UsageSection
import com.adriantache.antigravitywrapper.data.PreferencesManager
import com.adriantache.antigravitywrapper.ui.components.AntigravityWebView
import com.adriantache.antigravitywrapper.ui.components.ConnectionErrorOverlay
import com.adriantache.antigravitywrapper.ui.components.FloatingControlPill
import com.adriantache.antigravitywrapper.ui.components.ModelPickerBottomSheet
import com.adriantache.antigravitywrapper.ui.components.NativeComposerBar
import com.adriantache.antigravitywrapper.ui.components.PermissionsPickerBottomSheet
import com.adriantache.antigravitywrapper.ui.components.SettingsDialog
import com.adriantache.antigravitywrapper.ui.components.UsageOverlayCard
import com.adriantache.antigravitywrapper.ui.components.dispatchEscapeEvent
import com.adriantache.antigravitywrapper.ui.theme.AntigravityWrapperTheme
import com.adriantache.antigravitywrapper.util.DownloadHelper
import com.adriantache.antigravitywrapper.util.DownloadOutcome
import com.adriantache.antigravitywrapper.util.NotificationHelper
import com.adriantache.antigravitywrapper.util.UrlPolicy
import com.adriantache.antigravitywrapper.util.VoiceDictationController
import com.adriantache.antigravitywrapper.util.VoiceInputHelper
import com.adriantache.antigravitywrapper.util.VoiceStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager
    val incomingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(this)

        handleIncomingIntent(intent)
        NotificationHelper.createNotificationChannel(this)
        requestNotificationPermissionIfNecessary()

        // Enable edge-to-edge with matching #101010 dark theme bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#101010")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#101010"))
        )

        // Ensure system status and navigation bars are always visible
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Respect system cutouts by default
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }

        setContent {
            AntigravityWrapperTheme {
                AntigravityApp(
                    activity = this,
                    preferencesManager = preferencesManager
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val extracted = NotificationHelper.extractUrlFromIntent(intent)
        if (!extracted.isNullOrBlank()) {
            incomingUrl.value = extracted
            try {
                intent?.data = null
                intent?.replaceExtras(null)
            } catch (_: Exception) {
                // Ignore if intent is immutable
            }
        }
    }

    private fun requestNotificationPermissionIfNecessary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    fun applyKeepScreenOn(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AntigravityApp(
    activity: MainActivity,
    preferencesManager: PreferencesManager
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isTablet = configuration.screenWidthDp >= 600
    val hasHardwareKeyboard = configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val latestHasHardwareKeyboard by rememberUpdatedState(hasHardwareKeyboard)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pendingIncomingUrl by activity.incomingUrl
    var currentUrl by remember {
        mutableStateOf(activity.incomingUrl.value ?: preferencesManager.serverUrl)
    }
    val latestConfiguredUrl by rememberUpdatedState(currentUrl)

    var isDesktopMode by remember { mutableStateOf(preferencesManager.isDesktopMode) }
    var isKeepScreenOn by remember { mutableStateOf(preferencesManager.isKeepScreenOn) }
    var isPullToRefreshEnabled by remember { mutableStateOf(preferencesManager.isPullToRefreshEnabled) }
    var isNativeComposerEnabled by remember { mutableStateOf(preferencesManager.isNativeComposerEnabled) }
    var isShowVoiceButtonEnabled by remember { mutableStateOf(preferencesManager.isShowVoiceButtonEnabled) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var isPageLoading by remember { mutableStateOf(true) }
    var webViewRootCanScrollUp by remember { mutableStateOf(false) }
    var innerSpaCanScrollUp by remember { mutableStateOf(false) }

    // Connection error state
    var hasConnectionError by remember { mutableStateOf(false) }
    var connectionErrorMessage by remember { mutableStateOf("") }
    var failingUrl by remember { mutableStateOf("") }

    // Native composer & bridge state (per-conversation drafts)
    var composerState by remember { mutableStateOf(ComposerState()) }
    val draftsByConversation = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(preferencesManager.loadDrafts())
        }
    }
    var activeConversationKey by rememberSaveable { mutableStateOf("") }
    var draftText by rememberSaveable { mutableStateOf("") }
    var attachedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var isAttachingFiles by remember { mutableStateOf(false) }
    var isSendingMessage by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var lastSendWasHardwareKeyboard by remember { mutableStateOf(false) }
    var isComposerFocused by remember { mutableStateOf(false) }
    var pendingNativeFocus by remember { mutableStateOf(false) }
    val composerFocusRequester = remember { FocusRequester() }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var containerWidthPx by remember { mutableIntStateOf(0) }

    // Model & permissions bottom sheet state
    var showModelSheet by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var hasViewUsageOption by remember { mutableStateOf(false) }
    var modelSubParentName by remember { mutableStateOf<String?>(null) }
    var modelSubParentFullText by remember { mutableStateOf<String?>(null) }
    var modelSubOptions by remember { mutableStateOf<List<ModelOption>>(emptyList()) }

    var showPermissionsSheet by remember { mutableStateOf(false) }
    var isLoadingPermissions by remember { mutableStateOf(false) }
    var availablePermissions by remember { mutableStateOf<List<PermissionOption>>(emptyList()) }

    // Floating Usage card state (stays above composer without changing focus/keyboard state)
    var showUsageCard by remember { mutableStateOf(false) }
    var isLoadingUsage by remember { mutableStateOf(false) }
    var blockWebViewFocusForUsage by remember { mutableStateOf(false) }
    var suppressNativeFocusUntilMs by remember { mutableLongStateOf(0L) }
    var usageSections by remember { mutableStateOf<List<UsageSection>>(emptyList()) }
    var showAllUsageSections by remember { mutableStateOf(false) }

    val handleDownloadOutcome: (DownloadOutcome) -> Unit = { outcome ->
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            when (outcome) {
                is DownloadOutcome.SavedToDownloads -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Saved ${outcome.saved.fileName} to Downloads",
                        actionLabel = "Open",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        DownloadHelper.openDownloadedFile(
                            context = context,
                            uri = outcome.saved.uri,
                            mimeType = outcome.saved.mimeType
                        )
                    }
                }
                is DownloadOutcome.EnqueuedInManager -> {
                    snackbarHostState.showSnackbar(
                        message = "Downloading ${outcome.fileName}...",
                        duration = SnackbarDuration.Short
                    )
                }
                is DownloadOutcome.Failed -> {
                    snackbarHostState.showSnackbar(
                        message = outcome.message.ifBlank { "Download failed" },
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    // Bidirectional WebMessage bridge to the Antigravity iframe
    val webBridge = remember(context) {
        AntigravityWebBridge(
            context = context.applicationContext,
            configuredServerUrlProvider = { latestConfiguredUrl },
            onComposerStateChanged = { state ->
                val newKey = state.conversationKey
                if (state.hasComposer && newKey.isNotBlank() && newKey != activeConversationKey) {
                    if (activeConversationKey.isNotBlank()) {
                        if (draftText.isNotEmpty()) {
                            draftsByConversation[activeConversationKey] = draftText
                        } else {
                            draftsByConversation.remove(activeConversationKey)
                        }
                        preferencesManager.saveDraft(activeConversationKey, draftText)
                    }
                    activeConversationKey = newKey
                    draftText = draftsByConversation[newKey].orEmpty()
                    attachedFileNames = emptyList()
                    showUsageCard = false
                }
                composerState = state
            },
            onModelsLoaded = { models, hasViewUsage ->
                availableModels = models
                hasViewUsageOption = hasViewUsage
                modelSubParentName = null
                modelSubParentFullText = null
                modelSubOptions = emptyList()
                isLoadingModels = false
            },
            onModelSubOptionsLoaded = { parentName, parentFullText, options ->
                modelSubParentName = parentName
                modelSubParentFullText = parentFullText
                modelSubOptions = options
                isLoadingModels = false
            },
            onModelSelectDone = {
                isLoadingModels = false
                modelSubParentName = null
                modelSubParentFullText = null
                modelSubOptions = emptyList()
                showModelSheet = false
            },
            onPermissionsLoaded = { options ->
                availablePermissions = options
                isLoadingPermissions = false
            },
            onUsageLoaded = { sections ->
                usageSections = sections
                isLoadingUsage = false
                if (!blockWebViewFocusForUsage) {
                    suppressNativeFocusUntilMs = System.currentTimeMillis() + 600L
                }
                blockWebViewFocusForUsage = false
            },
            onScrollStateChanged = { canScroll ->
                innerSpaCanScrollUp = canScroll
            },
            onSendResult = { ok, error ->
                isSendingMessage = false
                if (ok) {
                    draftText = ""
                    if (activeConversationKey.isNotBlank()) {
                        draftsByConversation.remove(activeConversationKey)
                        preferencesManager.saveDraft(activeConversationKey, "")
                    }
                    attachedFileNames = emptyList()
                    // Dismiss on-screen soft keyboard after sending, but keep focus for hardware keyboards
                    if (!lastSendWasHardwareKeyboard && !latestHasHardwareKeyboard) {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                    lastSendWasHardwareKeyboard = false
                } else {
                    lastSendWasHardwareKeyboard = false
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = error?.ifBlank { null }
                                ?: "Couldn't send message — your draft was kept",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            },
            onAttachResult = { ok, names, error ->
                isAttachingFiles = false
                if (ok && names.isNotEmpty()) {
                    attachedFileNames = attachedFileNames + names
                } else if (!ok) {
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = error?.ifBlank { null } ?: "Couldn't attach file",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            },
            onRequestNativeFocus = {
                if (shouldAcceptNativeFocusRequest(isLoadingUsage, System.currentTimeMillis(), suppressNativeFocusUntilMs)) {
                    pendingNativeFocus = true
                }
            },
            onDownloadOutcome = handleDownloadOutcome
        )
    }

    // Native file picker launcher (avoids WebView's transient user-activation block on hidden file inputs)
    val nativeFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        isAttachingFiles = true
        coroutineScope.launch {
            val (payloads, skippedLarge) = withContext(Dispatchers.IO) {
                readSelectedFiles(context, uris)
            }
            if (skippedLarge > 0) {
                snackbarHostState.showSnackbar(
                    message = "Skipped $skippedLarge file(s) over 25 MB limit",
                    duration = SnackbarDuration.Short
                )
            }
            if (payloads.isEmpty()) {
                isAttachingFiles = false
                return@launch
            }
            val posted = webBridge.attachFiles(payloads)
            if (!posted) {
                isAttachingFiles = false
                snackbarHostState.showSnackbar("Session not ready to attach files")
            }
        }
    }

    fun updateDraftText(updated: String) {
        draftText = updated
        val key = activeConversationKey.ifBlank { composerState.conversationKey }
        if (key.isNotBlank()) {
            activeConversationKey = key
            if (updated.isNotEmpty()) {
                draftsByConversation[key] = updated
            } else {
                draftsByConversation.remove(key)
            }
        }
    }

    fun showMicrophonePermissionSnackbar(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Settings",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(settingsIntent)
                } catch (_: Exception) {
                    // Ignore if settings activity cannot be opened
                }
            }
        }
    }

    val latestUpdateDraftText by rememberUpdatedState(::updateDraftText)
    val latestShowVoiceError by rememberUpdatedState<(String, Boolean) -> Unit> { message, isPermissionError ->
        if (isPermissionError) {
            showMicrophonePermissionSnackbar(message)
        } else {
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    val voiceDictationController = remember(context) {
        VoiceDictationController(
            context = context.applicationContext,
            onListeningChanged = { listening -> isVoiceListening = listening },
            onTranscriptUpdate = { updated -> latestUpdateDraftText(updated) },
            onError = { message, isPermissionError -> latestShowVoiceError(message, isPermissionError) }
        )
    }

    DisposableEffect(voiceDictationController) {
        onDispose {
            voiceDictationController.cancel()
        }
    }

    val nativeVoiceIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                updateDraftText(VoiceInputHelper.mergeVoiceTranscript(draftText, spoken))
            }
        }
    }

    fun startNativeVoiceDictation() {
        when (voiceDictationController.start(draftText)) {
            VoiceStartResult.STARTED -> Unit
            VoiceStartResult.FALLBACK_TO_INTENT -> {
                try {
                    val intent = VoiceInputHelper.createRecognitionIntent(
                        packageName = context.packageName,
                        prompt = "Speak your message"
                    )
                    nativeVoiceIntentLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    latestShowVoiceError("Voice recognition is not available on this device", false)
                } catch (_: Exception) {
                    latestShowVoiceError("Could not start voice input", false)
                }
            }
        }
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startNativeVoiceDictation()
        } else {
            showMicrophonePermissionSnackbar("Microphone permission is required for voice input")
        }
    }

    val showNativeComposer = isNativeComposerEnabled &&
        composerState.hasComposer &&
        !hasConnectionError
    val composerHeightDp = with(density) { composerHeightPx.toDp() }
    val isSplitLayout = showNativeComposer &&
        isTablet &&
        (composerState.composerLeftFraction >= 0.06f || composerState.composerRightFraction <= 0.94f)
    val composerSpacerPx = if (isSplitLayout) {
        composerHeightDp.value.roundToInt().coerceAtLeast(0)
    } else {
        0
    }

    // Push live config updates into the iframe whenever toggles, screen width, or split-view spacer change
    LaunchedEffect(isNativeComposerEnabled, isShowVoiceButtonEnabled, isTablet, isDesktopMode, composerSpacerPx) {
        webBridge.updateConfig(
            BridgeConfig(
                nativeComposerEnabled = isNativeComposerEnabled,
                showVoiceButton = isShowVoiceButtonEnabled,
                isTablet = isTablet,
                isDesktopMode = isDesktopMode,
                enhancedWebComposer = true,
                composerSpacerPx = composerSpacerPx
            )
        )
    }

    // Debounce persisting the active conversation's draft to SharedPreferences
    LaunchedEffect(activeConversationKey, draftText) {
        if (activeConversationKey.isNotBlank()) {
            delay(400L)
            preferencesManager.saveDraft(activeConversationKey, draftText)
        }
    }

    // Hand off focus to native TextField when web UI auto-focuses its hidden editor (e.g. "+ New Conversation")
    LaunchedEffect(pendingNativeFocus, showNativeComposer) {
        if (pendingNativeFocus && showNativeComposer) {
            delay(50L)
            try {
                composerFocusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
                // FocusRequester not yet attached
            }
            pendingNativeFocus = false
        }
    }

    // Guard against stuck sending spinner if iframe navigates mid-send
    LaunchedEffect(isSendingMessage) {
        if (isSendingMessage) {
            delay(3000L)
            if (isSendingMessage) {
                isSendingMessage = false
                snackbarHostState.showSnackbar(
                    message = "Send timed out — your draft was kept",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Guard against stuck model/usage loading spinners
    LaunchedEffect(isLoadingModels) {
        if (isLoadingModels) {
            delay(3000L)
            if (isLoadingModels) {
                isLoadingModels = false
            }
        }
    }

    LaunchedEffect(showNativeComposer) {
        if (!showNativeComposer) {
            isComposerFocused = false
            voiceDictationController.cancel()
        }
    }

    LaunchedEffect(activeConversationKey) {
        if (isVoiceListening) {
            voiceDictationController.cancel()
        }
    }

    LaunchedEffect(isLoadingUsage) {
        if (isLoadingUsage) {
            delay(4500L)
            if (isLoadingUsage) {
                isLoadingUsage = false
                blockWebViewFocusForUsage = false
            }
        }
    }

    fun startUsageFetch(preserveComposerFocus: Boolean) {
        val shouldBlock = shouldBlockWebViewFocusDuringUsage(
            isLoadingUsage = true,
            isComposerFocused = preserveComposerFocus
        )
        isLoadingUsage = true
        blockWebViewFocusForUsage = shouldBlock
        if (!shouldBlock) {
            suppressNativeFocusUntilMs = System.currentTimeMillis() + 5000L
        }
        if (!webBridge.requestUsage()) {
            isLoadingUsage = false
            blockWebViewFocusForUsage = false
        }
    }

    // Only block WebView focusability while scraping Usage if the native composer currently owns focus.
    // Calling isFocusable = false while WebView itself holds focus triggers View.clearFocus() ->
    // rootViewRequestFocus(), which would unexpectedly focus BasicTextField and open the keyboard.
    DisposableEffect(blockWebViewFocusForUsage, webViewInstance) {
        val wv = webViewInstance
        if (blockWebViewFocusForUsage && wv != null && !wv.hasFocus()) {
            wv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            wv.isFocusable = false
            wv.isFocusableInTouchMode = false
        }
        onDispose {
            if (wv != null) {
                wv.isFocusable = true
                wv.isFocusableInTouchMode = true
                wv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }

    // Guard against stuck attaching spinner
    LaunchedEffect(isAttachingFiles) {
        if (isAttachingFiles) {
            delay(10_000L)
            if (isAttachingFiles) {
                isAttachingFiles = false
                snackbarHostState.showSnackbar(
                    message = "File attachment timed out",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    LaunchedEffect(pendingIncomingUrl) {
        pendingIncomingUrl?.let { url ->
            hasConnectionError = false
            currentUrl = url
            webViewInstance?.loadUrl(url)
            activity.incomingUrl.value = null
        }
    }

    // Settings dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Double-back & stuck-history trackers
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var lastGoBackIndex by remember { mutableIntStateOf(-1) }
    var lastGoBackUrl by remember { mutableStateOf("") }
    var lastGoBackTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isKeepScreenOn) {
        activity.applyKeepScreenOn(isKeepScreenOn)
    }

    // Network connectivity listener to automatically reconnect when network returns
    DisposableEffect(context) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (hasConnectionError) {
                    coroutineScope.launch {
                        delay(500)
                        hasConnectionError = false
                        webViewInstance?.reload()
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        onDispose {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // Ignore unregister exceptions
            }
        }
    }

    fun handleRootBack() {
        if (preferencesManager.isDoubleBackExitEnabled) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000L) {
                activity.finish()
            } else {
                lastBackPressTime = currentTime
                // Send Escape into both the cross-origin iframe and outer frame
                webBridge.dispatchEscape()
                webViewInstance?.dispatchEscapeEvent()
                coroutineScope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = "Press back again to exit",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        } else {
            activity.finish()
        }
    }

    // Back gesture interception
    BackHandler(enabled = true) {
        if (showUsageCard) {
            showUsageCard = false
            isLoadingUsage = false
            blockWebViewFocusForUsage = false
            return@BackHandler
        }
        if (showModelSheet) {
            if (modelSubOptions.isNotEmpty()) {
                modelSubParentName = null
                modelSubParentFullText = null
                modelSubOptions = emptyList()
            } else {
                showModelSheet = false
            }
            return@BackHandler
        }
        if (showPermissionsSheet) {
            showPermissionsSheet = false
            return@BackHandler
        }
        if (showSettingsDialog) {
            showSettingsDialog = false
            return@BackHandler
        }

        // First ask the Antigravity SPA frame to handle in-app back navigation
        // (dismiss open web overlays, or click the mobile conversation Back button to return to the list).
        webBridge.navigateBack { handled, onConversationList ->
            if (handled) {
                lastGoBackIndex = -1
                lastGoBackUrl = ""
                focusManager.clearFocus()
                return@navigateBack
            }

            // Already on the conversation list (or tablet split-view with sidebar visible):
            // avoid bouncing into stale WebView history or Google auth redirects.
            if (onConversationList) {
                handleRootBack()
                return@navigateBack
            }

            val wv = webViewInstance
            if (wv != null && wv.canGoBack()) {
                val history = wv.copyBackForwardList()
                val idx = history.currentIndex
                val currUrl = if (idx >= 0) history.getItemAtIndex(idx)?.url.orEmpty() else ""
                val prevUrl = if (idx > 0) history.getItemAtIndex(idx - 1)?.url.orEmpty() else ""
                val now = System.currentTimeMillis()
                val isStuckHistoryTrap = idx == lastGoBackIndex &&
                    currUrl == lastGoBackUrl &&
                    (now - lastGoBackTime) < 2000L

                if (idx > 0 && UrlPolicy.shouldAllowBackNavigation(currUrl, prevUrl) && !isStuckHistoryTrap) {
                    lastGoBackIndex = idx
                    lastGoBackUrl = currUrl
                    lastGoBackTime = now
                    wv.goBack()
                    return@navigateBack
                }
            }

            handleRootBack()
        }
    }

    // Compute IME insets separately so WebView snaps ONCE to target IME height (eliminating
    // 60-120Hz Chromium reflow jank) while the lightweight Compose composer animates smoothly.
    val systemBarBottomPx = WindowInsets.systemBars.getBottom(density)
    val imeTargetOverlapDp = with(density) {
        (WindowInsets.imeAnimationTarget.getBottom(density) - systemBarBottomPx)
            .coerceAtLeast(0)
            .toDp()
    }
    val imeCurrentOverlapDp = with(density) {
        (WindowInsets.ime.getBottom(density) - systemBarBottomPx)
            .coerceAtLeast(0)
            .toDp()
    }
    val isImeVisible = WindowInsets.isImeVisible
    val canScrollUp = webViewRootCanScrollUp || innerSpaCanScrollUp

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF101010),
        contentColor = Color.White,
        // Exclude animated IME from Scaffold so WebView isn't resized every animation frame
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = (if (showNativeComposer) composerHeightDp else 0.dp) + imeCurrentOverlapDp
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101010))
                .padding(innerPadding)
                .onGloballyPositioned { coords ->
                    containerWidthPx = coords.size.width
                }
        ) {
            val pullRefreshState = rememberPullToRefreshState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        // In tablet split-view, the chat column itself reserves composerSpacerPx so
                        // the left conversations sidebar stays full-height and interactive.
                        bottom = (if (showNativeComposer && !isSplitLayout) composerHeightDp else 0.dp) +
                            imeTargetOverlapDp
                    )
                    .pullToRefresh(
                        isRefreshing = isRefreshing,
                        state = pullRefreshState,
                        enabled = isPullToRefreshEnabled && !canScrollUp && !isImeVisible,
                        onRefresh = {
                            isRefreshing = true
                            hasConnectionError = false
                            webViewInstance?.reload()
                        }
                    )
            ) {
                AntigravityWebView(
                    modifier = Modifier.fillMaxSize(),
                    url = currentUrl,
                    isDesktopMode = isDesktopMode,
                    bridge = webBridge,
                    onWebViewCreated = { webView ->
                        webViewInstance = webView
                    },
                    onPageStarted = {
                        isPageLoading = true
                        hasConnectionError = false
                        attachedFileNames = emptyList()
                        isAttachingFiles = false
                        lastGoBackIndex = -1
                        lastGoBackUrl = ""
                    },
                    onPageFinished = {
                        isPageLoading = false
                        isRefreshing = false
                    },
                    onProgressChanged = { progress ->
                        pageProgress = progress
                        if (progress >= 100) {
                            isPageLoading = false
                            isRefreshing = false
                        }
                    },
                    onConnectionError = { _, description, errorUrl ->
                        hasConnectionError = true
                        connectionErrorMessage = description
                        failingUrl = errorUrl
                        isRefreshing = false
                        isPageLoading = false
                    },
                    onScrollChanged = { canScroll ->
                        webViewRootCanScrollUp = canScroll
                    },
                    onDownloadOutcome = handleDownloadOutcome,
                    onPermissionDenied = { message ->
                        showMicrophonePermissionSnackbar(message)
                    }
                )

                if (isPullToRefreshEnabled) {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }

            // Native Jetpack Compose Composer Bar (slides smoothly with keyboard at full refresh rate)
            if (showNativeComposer) {
                val containerWidthDp = with(density) { containerWidthPx.toDp() }
                val splitPlacement = if (isSplitLayout && containerWidthPx > 0) {
                    val rawStartDp = containerWidthDp * composerState.composerLeftFraction
                    val rawEndDp = containerWidthDp * composerState.composerRightFraction
                    val minBarWidthDp = 320.dp.coerceAtMost(containerWidthDp)
                    val barWidthDp = (rawEndDp - rawStartDp)
                        .coerceAtLeast(minBarWidthDp)
                        .coerceAtMost(containerWidthDp)
                    val barStartDp = rawStartDp.coerceIn(
                        0.dp,
                        (containerWidthDp - barWidthDp).coerceAtLeast(0.dp)
                    )
                    barStartDp to barWidthDp
                } else {
                    null
                }

                val composerModifier = if (splitPlacement != null) {
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = splitPlacement.first)
                        .width(splitPlacement.second)
                        .padding(bottom = imeCurrentOverlapDp)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = imeCurrentOverlapDp)
                }

                // Floating Usage card directly above the input bar (preserves focus & keyboard)
                if (showUsageCard) {
                    val usageModifier = if (splitPlacement != null) {
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = splitPlacement.first)
                            .width(splitPlacement.second)
                            .padding(bottom = composerHeightDp + imeCurrentOverlapDp + 4.dp)
                    } else {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = composerHeightDp + imeCurrentOverlapDp + 4.dp)
                    }

                    UsageOverlayCard(
                        modifier = usageModifier,
                        sections = usageSections,
                        currentModel = composerState.currentModel,
                        isLoading = isLoadingUsage,
                        showAllSections = showAllUsageSections,
                        onToggleShowAll = { showAllUsageSections = !showAllUsageSections },
                        onRefresh = {
                            startUsageFetch(preserveComposerFocus = isComposerFocused)
                        },
                        onOpenWebUsage = {
                            showUsageCard = false
                            webBridge.openUsage()
                        },
                        onDismiss = {
                            showUsageCard = false
                            isLoadingUsage = false
                            blockWebViewFocusForUsage = false
                        }
                    )
                }

                NativeComposerBar(
                    modifier = composerModifier.onGloballyPositioned { coords ->
                        composerHeightPx = coords.size.height
                    },
                    text = draftText,
                    onTextChange = ::updateDraftText,
                    composerState = composerState,
                    attachedFileNames = attachedFileNames,
                    isAttaching = isAttachingFiles,
                    isSending = isSendingMessage,
                    showVoiceButton = isShowVoiceButtonEnabled,
                    isVoiceListening = isVoiceListening,
                    isFloatingOverlay = isSplitLayout,
                    isUsageOpen = showUsageCard,
                    focusRequester = composerFocusRequester,
                    onFocusChanged = { focused ->
                        isComposerFocused = focused
                    },
                    onSend = { fromHardwareKeyboard ->
                        if (isVoiceListening) {
                            voiceDictationController.cancel()
                        }
                        val hasContent = draftText.trim().isNotEmpty() || attachedFileNames.isNotEmpty()
                        if (hasContent && !isSendingMessage) {
                            showUsageCard = false
                            lastSendWasHardwareKeyboard = fromHardwareKeyboard
                            isSendingMessage = true
                            val posted = webBridge.sendMessage(draftText)
                            if (!posted) {
                                isSendingMessage = false
                                lastSendWasHardwareKeyboard = false
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Session not ready yet")
                                }
                            }
                        }
                    },
                    onStop = {
                        webBridge.stopGeneration()
                    },
                    onOpenModelPicker = {
                        showUsageCard = false
                        showModelSheet = true
                        isLoadingModels = true
                        if (!webBridge.requestModels()) {
                            isLoadingModels = false
                        }
                    },
                    onOpenUsage = {
                        if (showUsageCard) {
                            showUsageCard = false
                            isLoadingUsage = false
                            blockWebViewFocusForUsage = false
                        } else {
                            showUsageCard = true
                            showAllUsageSections = false
                            startUsageFetch(preserveComposerFocus = isComposerFocused)
                        }
                    },
                    onOpenPermissionsPicker = {
                        showPermissionsSheet = true
                        isLoadingPermissions = true
                        if (!webBridge.requestPermissions()) {
                            isLoadingPermissions = false
                        }
                    },
                    onAttachClick = {
                        try {
                            nativeFilePickerLauncher.launch(arrayOf("*/*"))
                        } catch (_: Exception) {
                            webBridge.triggerAttach()
                        }
                    },
                    onClearAttachments = {
                        attachedFileNames = emptyList()
                        webBridge.clearAttachments()
                    },
                    onVoiceClick = {
                        if (isVoiceListening) {
                            voiceDictationController.stop()
                        } else {
                            val hasAudioPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasAudioPermission) {
                                startNativeVoiceDictation()
                            } else {
                                voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onSwitchToWebComposer = {
                        voiceDictationController.cancel()
                        isNativeComposerEnabled = false
                        preferencesManager.isNativeComposerEnabled = false
                        coroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = "Switched to web input (use ⋮ to switch back)",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
            }

            // Top progress bar during page load
            AnimatedVisibility(
                visible = isPageLoading && pageProgress < 100,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Connection Error Reconnect Overlay
            if (hasConnectionError) {
                ConnectionErrorOverlay(
                    failingUrl = failingUrl.ifBlank { currentUrl },
                    errorMessage = connectionErrorMessage,
                    onRetry = {
                        hasConnectionError = false
                        webViewInstance?.reload()
                    },
                    onOpenSettings = {
                        showSettingsDialog = true
                    },
                    onResetDefault = {
                        hasConnectionError = false
                        val defaultUrl = PreferencesManager.DEFAULT_URL
                        currentUrl = defaultUrl
                        preferencesManager.serverUrl = defaultUrl
                        webViewInstance?.loadUrl(defaultUrl)
                    }
                )
            }

            // Floating quick controls pill
            FloatingControlPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp),
                showComposerToggle = composerState.hasComposer && !hasConnectionError,
                isNativeComposerEnabled = isNativeComposerEnabled,
                onToggleNativeComposer = {
                    val next = !isNativeComposerEnabled
                    isNativeComposerEnabled = next
                    preferencesManager.isNativeComposerEnabled = next
                },
                onRefresh = {
                    hasConnectionError = false
                    webViewInstance?.reload()
                },
                onOpenSettings = {
                    showSettingsDialog = true
                }
            )

            // Native Model Picker Bottom Sheet
            if (showModelSheet) {
                ModelPickerBottomSheet(
                    models = availableModels,
                    isLoading = isLoadingModels,
                    hasViewUsage = hasViewUsageOption,
                    subOptionsTitle = modelSubParentName,
                    subOptions = modelSubOptions,
                    onBackFromSubOptions = {
                        modelSubParentName = null
                        modelSubParentFullText = null
                        modelSubOptions = emptyList()
                    },
                    onSelect = { option ->
                        isLoadingModels = true
                        webBridge.selectModel(option)
                    },
                    onSelectSubOption = { subOption ->
                        val pFull = modelSubParentFullText ?: return@ModelPickerBottomSheet
                        val pName = modelSubParentName ?: pFull
                        isLoadingModels = true
                        webBridge.selectModelSubOption(pFull, pName, subOption)
                    },
                    onOpenUsage = {
                        showModelSheet = false
                        isLoadingModels = false
                        modelSubParentName = null
                        modelSubParentFullText = null
                        modelSubOptions = emptyList()
                        showUsageCard = true
                        showAllUsageSections = false
                        startUsageFetch(preserveComposerFocus = false)
                    },
                    onDismiss = {
                        showModelSheet = false
                        isLoadingModels = false
                        modelSubParentName = null
                        modelSubParentFullText = null
                        modelSubOptions = emptyList()
                    }
                )
            }

            // Native Permissions Picker Bottom Sheet
            if (showPermissionsSheet) {
                PermissionsPickerBottomSheet(
                    options = availablePermissions,
                    isLoading = isLoadingPermissions,
                    onSelect = { option ->
                        showPermissionsSheet = false
                        webBridge.selectPermission(option)
                    },
                    onDismiss = { showPermissionsSheet = false }
                )
            }

            // Settings Dialog
            if (showSettingsDialog) {
                SettingsDialog(
                    preferencesManager = preferencesManager,
                    currentUrl = currentUrl,
                    onDismiss = { showSettingsDialog = false },
                    onSaveAndConnect = { newUrl ->
                        showSettingsDialog = false
                        hasConnectionError = false
                        val urlChanged = newUrl != currentUrl
                        val desktopChanged = preferencesManager.isDesktopMode != isDesktopMode
                        currentUrl = newUrl
                        isDesktopMode = preferencesManager.isDesktopMode
                        isKeepScreenOn = preferencesManager.isKeepScreenOn
                        isPullToRefreshEnabled = preferencesManager.isPullToRefreshEnabled
                        isNativeComposerEnabled = preferencesManager.isNativeComposerEnabled
                        isShowVoiceButtonEnabled = preferencesManager.isShowVoiceButtonEnabled
                        if (urlChanged) {
                            webViewInstance?.loadUrl(newUrl)
                        } else if (desktopChanged) {
                            webViewInstance?.reload()
                        }
                    },
                    onClearCache = {
                        webViewInstance?.clearCache(true)
                        CookieManager.getInstance().removeAllCookies(null)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cache and cookies cleared")
                        }
                    }
                )
            }
        }
    }
}

internal fun shouldBlockWebViewFocusDuringUsage(
    isLoadingUsage: Boolean,
    isComposerFocused: Boolean
): Boolean = isLoadingUsage && isComposerFocused

internal fun shouldAcceptNativeFocusRequest(
    isLoadingUsage: Boolean,
    nowMs: Long,
    suppressUntilMs: Long
): Boolean = !isLoadingUsage && nowMs >= suppressUntilMs

private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024

private fun readSelectedFiles(
    context: Context,
    uris: List<Uri>
): Pair<List<AttachedFilePayload>, Int> {
    val resolver = context.contentResolver
    val payloads = mutableListOf<AttachedFilePayload>()
    var skippedLarge = 0

    for (uri in uris) {
        try {
            var displayName = "attachment"
            var reportedSize = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            reportedSize = cursor.getLong(sizeIdx)
                        }
                    }
                }

            if (reportedSize > MAX_ATTACHMENT_BYTES) {
                skippedLarge++
                continue
            }

            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_ATTACHMENT_BYTES) {
                        skippedLarge++
                        return@use null
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            } ?: continue

            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            payloads.add(
                AttachedFilePayload(
                    name = displayName,
                    mimeType = mimeType,
                    base64Data = encoded
                )
            )
        } catch (_: Exception) {
            // Skip unreadable URI
        }
    }

    return payloads to skippedLarge
}
