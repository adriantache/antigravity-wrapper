package com.adriantache.antigravitywrapper

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.adriantache.antigravitywrapper.data.PreferencesManager
import com.adriantache.antigravitywrapper.ui.components.AntigravityWebView
import com.adriantache.antigravitywrapper.ui.components.ConnectionErrorOverlay
import com.adriantache.antigravitywrapper.ui.components.FloatingControlPill
import com.adriantache.antigravitywrapper.ui.components.SettingsDialog
import com.adriantache.antigravitywrapper.ui.components.dispatchEscapeEvent
import com.adriantache.antigravitywrapper.ui.theme.AntigravityWrapperTheme
import com.adriantache.antigravitywrapper.util.NotificationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            } catch (e: Exception) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntigravityApp(
    activity: MainActivity,
    preferencesManager: PreferencesManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pendingIncomingUrl by activity.incomingUrl
    var currentUrl by remember {
        mutableStateOf(activity.incomingUrl.value ?: preferencesManager.serverUrl)
    }
    var isDesktopMode by remember { mutableStateOf(preferencesManager.isDesktopMode) }
    var isKeepScreenOn by remember { mutableStateOf(preferencesManager.isKeepScreenOn) }
    var isPullToRefreshEnabled by remember { mutableStateOf(preferencesManager.isPullToRefreshEnabled) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var isPageLoading by remember { mutableStateOf(true) }
    var canScrollUp by remember { mutableStateOf(false) }

    // Connection error state
    var hasConnectionError by remember { mutableStateOf(false) }
    var connectionErrorMessage by remember { mutableStateOf("") }
    var failingUrl by remember { mutableStateOf("") }

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
            } catch (e: Exception) {
                // Ignore unregister exceptions
            }
        }
    }

    // Back gesture interception
    BackHandler(enabled = true) {
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
                // Send Escape key into web interface DOM to dismiss dropdowns/popups/palettes
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF101010),
        contentColor = Color.White,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    .pullToRefresh(
                        isRefreshing = isRefreshing,
                        state = pullRefreshState,
                        enabled = isPullToRefreshEnabled && !canScrollUp,
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
                    onWebViewCreated = { webView ->
                        webViewInstance = webView
                    },
                    onPageStarted = {
                        isPageLoading = true
                        hasConnectionError = false
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
                        canScrollUp = canScroll
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
                onRefresh = {
                    hasConnectionError = false
                    webViewInstance?.reload()
                },
                onOpenSettings = {
                    showSettingsDialog = true
                }
            )

            // Settings Dialog
            if (showSettingsDialog) {
                SettingsDialog(
                    preferencesManager = preferencesManager,
                    currentUrl = currentUrl,
                    onDismiss = { showSettingsDialog = false },
                    onSaveAndConnect = { newUrl ->
                        showSettingsDialog = false
                        hasConnectionError = false
                        currentUrl = newUrl
                        isDesktopMode = preferencesManager.isDesktopMode
                        isKeepScreenOn = preferencesManager.isKeepScreenOn
                        isPullToRefreshEnabled = preferencesManager.isPullToRefreshEnabled
                        webViewInstance?.loadUrl(newUrl)
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
