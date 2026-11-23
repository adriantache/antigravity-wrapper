package com.adriantache.antigravitywrapper

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.adriantache.antigravitywrapper.bridge.AntigravityWebBridge
import com.adriantache.antigravitywrapper.bridge.AttachedFilePayload
import com.adriantache.antigravitywrapper.bridge.BridgeConfig
import com.adriantache.antigravitywrapper.bridge.ComposerState
import com.adriantache.antigravitywrapper.bridge.ModelOption
import com.adriantache.antigravitywrapper.bridge.PermissionOption
import com.adriantache.antigravitywrapper.data.PreferencesManager
import com.adriantache.antigravitywrapper.ui.components.AntigravityWebView
import com.adriantache.antigravitywrapper.ui.components.ConnectionErrorOverlay
import com.adriantache.antigravitywrapper.ui.components.FloatingControlPill
import com.adriantache.antigravitywrapper.ui.components.ModelPickerBottomSheet
import com.adriantache.antigravitywrapper.ui.components.NativeComposerBar
import com.adriantache.antigravitywrapper.ui.components.PermissionsPickerBottomSheet
import com.adriantache.antigravitywrapper.ui.components.SettingsDialog
import com.adriantache.antigravitywrapper.ui.components.dispatchEscapeEvent
import com.adriantache.antigravitywrapper.ui.theme.AntigravityWrapperTheme
import com.adriantache.antigravitywrapper.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val isTablet = configuration.screenWidthDp >= 600
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

    // Native composer & bridge state
    var composerState by remember { mutableStateOf(ComposerState()) }
    var draftText by rememberSaveable { mutableStateOf("") }
    var attachedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var isAttachingFiles by remember { mutableStateOf(false) }
    var isSendingMessage by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }

    // Model & permissions bottom sheet state
    var showModelSheet by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modelSubParentName by remember { mutableStateOf<String?>(null) }
    var modelSubParentFullText by remember { mutableStateOf<String?>(null) }
    var modelSubOptions by remember { mutableStateOf<List<ModelOption>>(emptyList()) }

    var showPermissionsSheet by remember { mutableStateOf(false) }
    var isLoadingPermissions by remember { mutableStateOf(false) }
    var availablePermissions by remember { mutableStateOf<List<PermissionOption>>(emptyList()) }

    // Bidirectional WebMessage bridge to the Antigravity iframe
    val webBridge = remember(context) {
        AntigravityWebBridge(
            context = context.applicationContext,
            configuredServerUrlProvider = { latestConfiguredUrl },
            onComposerStateChanged = { state ->
                composerState = state
            },
            onModelsLoaded = { models ->
                availableModels = models
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
            onScrollStateChanged = { canScroll ->
                innerSpaCanScrollUp = canScroll
            },
            onSendResult = { ok, error ->
                isSendingMessage = false
                if (ok) {
                    draftText = ""
                    attachedFileNames = emptyList()
                } else {
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
            }
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

    // Push live config updates into the iframe whenever toggles or screen width change
    LaunchedEffect(isNativeComposerEnabled, isShowVoiceButtonEnabled, isTablet) {
        webBridge.updateConfig(
            BridgeConfig(
                nativeComposerEnabled = isNativeComposerEnabled,
                showVoiceButton = isShowVoiceButtonEnabled,
                isTablet = isTablet,
                enhancedWebComposer = true
            )
        )
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

    // Guard against stuck model loading spinner
    LaunchedEffect(isLoadingModels) {
        if (isLoadingModels) {
            delay(3000L)
            if (isLoadingModels) {
                isLoadingModels = false
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

    // Double-back tracker
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

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

    // Back gesture interception
    BackHandler(enabled = true) {
        if (showModelSheet) {
            showModelSheet = false
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

        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
            return@BackHandler
        }

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

    val showNativeComposer = isNativeComposerEnabled &&
        composerState.hasComposer &&
        !hasConnectionError
    val composerHeightDp = with(density) { composerHeightPx.toDp() }
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
        ) {
            val pullRefreshState = rememberPullToRefreshState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = (if (showNativeComposer) composerHeightDp else 0.dp) + imeTargetOverlapDp
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
                NativeComposerBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = imeCurrentOverlapDp)
                        .onGloballyPositioned { coords ->
                            composerHeightPx = coords.size.height
                        },
                    text = draftText,
                    onTextChange = { draftText = it },
                    composerState = composerState,
                    attachedFileNames = attachedFileNames,
                    isAttaching = isAttachingFiles,
                    isSending = isSendingMessage,
                    showVoiceButton = isShowVoiceButtonEnabled,
                    onSend = {
                        val hasContent = draftText.trim().isNotEmpty() || attachedFileNames.isNotEmpty()
                        if (hasContent && !isSendingMessage) {
                            isSendingMessage = true
                            val posted = webBridge.sendMessage(draftText)
                            if (!posted) {
                                isSendingMessage = false
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
                        showModelSheet = true
                        isLoadingModels = true
                        if (!webBridge.requestModels()) {
                            isLoadingModels = false
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
                        webBridge.triggerVoice()
                    },
                    onSwitchToWebComposer = {
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
