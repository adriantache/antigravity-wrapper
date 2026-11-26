package com.adriantache.antigravitywrapper.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.adriantache.antigravitywrapper.bridge.AntigravityWebBridge
import com.adriantache.antigravitywrapper.util.DownloadHelper
import com.adriantache.antigravitywrapper.util.DownloadOutcome
import com.adriantache.antigravitywrapper.util.NotificationHelper
import com.adriantache.antigravitywrapper.util.UrlNavigationAction
import com.adriantache.antigravitywrapper.util.UrlPolicy
import com.adriantache.antigravitywrapper.util.WebPermissionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val NOTIFICATION_POLYFILL_JS = """
    (function() {
        if (!window.Notification || !window.Notification._isAntigravityBridge) {
            function AntigravityNotification(title, options) {
                options = options || {};
                var targetUrl = '';
                if (options.data) {
                    if (typeof options.data === 'string') {
                        targetUrl = options.data;
                    } else if (options.data.url) {
                        targetUrl = options.data.url;
                    } else if (options.data.link) {
                        targetUrl = options.data.link;
                    }
                }
                if (!targetUrl) {
                    targetUrl = window.location.href;
                }
                if (window._AntigravityNotifier && window._AntigravityNotifier.postNotification) {
                    window._AntigravityNotifier.postNotification(title || 'Antigravity', options.body || '', targetUrl);
                }
                this.title = title;
                this.body = options.body || '';
                this.data = options.data;
                this.close = function() {};
            }

            AntigravityNotification.permission = 'granted';
            AntigravityNotification._isAntigravityBridge = true;
            AntigravityNotification.requestPermission = function(callback) {
                if (callback) callback('granted');
                return Promise.resolve('granted');
            };

            window.Notification = AntigravityNotification;
        }

        if (window.ServiceWorkerRegistration && window.ServiceWorkerRegistration.prototype) {
            window.ServiceWorkerRegistration.prototype.showNotification = function(title, options) {
                options = options || {};
                var targetUrl = (options.data && (options.data.url || options.data.link)) || window.location.href;
                if (window._AntigravityNotifier && window._AntigravityNotifier.postNotification) {
                    window._AntigravityNotifier.postNotification(title || 'Antigravity', options.body || '', targetUrl);
                }
                return Promise.resolve();
            };
        }
    })();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AntigravityWebView(
    modifier: Modifier = Modifier,
    url: String,
    isDesktopMode: Boolean,
    bridge: AntigravityWebBridge? = null,
    onWebViewCreated: (WebView) -> Unit = {},
    onPageStarted: () -> Unit = {},
    onPageFinished: () -> Unit = {},
    onProgressChanged: (Int) -> Unit = {},
    onConnectionError: (errorCode: Int, description: String, failingUrl: String) -> Unit = { _, _, _ -> },
    onScrollChanged: (canScrollUp: Boolean) -> Unit = {},
    onDownloadOutcome: (DownloadOutcome) -> Unit = {},
    onPermissionDenied: (String) -> Unit = {}
) {
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    val currentConfiguredUrl by rememberUpdatedState(url)
    val latestIsDesktopMode by rememberUpdatedState(isDesktopMode)
    val latestOnDownloadOutcome by rememberUpdatedState(onDownloadOutcome)
    val latestOnPermissionDenied by rememberUpdatedState(onPermissionDenied)
    val coroutineScope = rememberCoroutineScope()

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            val grantable = WebPermissionPolicy.resolveGrantableResources(
                requestedResources = request.resources,
                hasRecordAudioPermission = granted
            )
            try {
                if (grantable.isNotEmpty()) {
                    request.grant(grantable)
                } else {
                    request.deny()
                }
            } catch (_: Exception) {
                // Ignore if request was already canceled or WebView navigated away
            }
        }
        if (!granted) {
            latestOnPermissionDenied("Microphone permission is required for voice input")
        }
    }

    // File chooser launcher for file uploads in Antigravity web interface
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data
            val uris = when {
                intentData?.clipData != null -> {
                    val count = intentData.clipData!!.itemCount
                    Array(count) { i -> intentData.clipData!!.getItemAt(i).uri }
                }
                intentData?.data != null -> arrayOf(intentData.data!!)
                else -> null
            }
            filePathCallback?.onReceiveValue(uris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    // React to desktop mode toggling dynamically
    LaunchedEffect(isDesktopMode, webViewInstance) {
        webViewInstance?.let { wv ->
            wv.settings.userAgentString = if (isDesktopMode) {
                DESKTOP_USER_AGENT
            } else {
                MOBILE_USER_AGENT
            }
            wv.settings.useWideViewPort = true
            wv.settings.loadWithOverviewMode = true
        }
    }

    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(url, webViewInstance) {
        if (url.isNotBlank() && url != lastLoadedUrl) {
            lastLoadedUrl = url
            webViewInstance?.let { wv ->
                if (wv.url != url) {
                    wv.loadUrl(url)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                pendingPermissionRequest?.deny()
            } catch (_: Exception) {
                // Ignore if already handled
            }
            pendingPermissionRequest = null
            webViewInstance?.destroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#101010"))
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    userAgentString = if (isDesktopMode) {
                        DESKTOP_USER_AGENT
                    } else {
                        MOBILE_USER_AGENT
                    }
                }

                val webView = this
                var baseScale = 0f
                var isZoomedIn = false
                addJavascriptInterface(WebNotificationBridge(context), "_AntigravityNotifier")
                bridge?.attachTo(webView)
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                        super.onScaleChanged(view, oldScale, newScale)
                        if (baseScale <= 0f || newScale < baseScale) {
                            baseScale = newScale
                        }
                        isZoomedIn = baseScale > 0f && newScale > baseScale * 1.03f
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val reqUrl = request?.url ?: return false
                        return when (
                            UrlPolicy.classifyNavigation(
                                scheme = reqUrl.scheme,
                                host = reqUrl.host,
                                port = reqUrl.port,
                                isForMainFrame = request.isForMainFrame,
                                configuredServerUrl = currentConfiguredUrl
                            )
                        ) {
                            UrlNavigationAction.ALLOW_IN_WEBVIEW -> false
                            UrlNavigationAction.CONVERT_ANTIGRAVITY_SCHEME -> {
                                val converted = NotificationHelper.normalizeUrl(reqUrl.toString())
                                view?.loadUrl(converted)
                                true
                            }
                            UrlNavigationAction.OPEN_EXTERNALLY -> {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, reqUrl).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    view?.context?.startActivity(intent)
                                } catch (_: Exception) {
                                    // Keep WebView on the current page if no external handler exists
                                }
                                true
                            }
                        }
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        baseScale = 0f
                        isZoomedIn = false
                        bridge?.resetForNavigation()
                        view?.evaluateJavascript(NOTIFICATION_POLYFILL_JS, null)
                        onPageStarted()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(NOTIFICATION_POLYFILL_JS, null)
                        onPageFinished()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.errorCode ?: -1
                            } else {
                                -1
                            }
                            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString() ?: "Connection error"
                            } else {
                                "Connection error"
                            }
                            onConnectionError(
                                errorCode,
                                description,
                                request.url.toString()
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            val statusCode = errorResponse?.statusCode ?: 200
                            if (statusCode >= 500) {
                                onConnectionError(
                                    statusCode,
                                    "HTTP $statusCode: ${errorResponse?.reasonPhrase ?: "Server error"}",
                                    request.url.toString()
                                )
                            }
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onProgressChanged(newProgress)
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request == null) return
                        val requested = request.resources.orEmpty()
                        val hasAudioPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (WebPermissionPolicy.requiresRecordAudioPrompt(requested, hasAudioPermission)) {
                            try {
                                pendingPermissionRequest?.deny()
                            } catch (_: Exception) {
                                // Ignore stale canceled request
                            }
                            pendingPermissionRequest = request
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return
                        }

                        val grantable = WebPermissionPolicy.resolveGrantableResources(
                            requestedResources = requested,
                            hasRecordAudioPermission = hasAudioPermission
                        )
                        try {
                            if (grantable.isNotEmpty()) {
                                request.grant(grantable)
                            } else {
                                request.deny()
                            }
                        } catch (_: Exception) {
                            // Ignore if request was canceled or WebView navigated away
                        }
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                        super.onPermissionRequestCanceled(request)
                        if (pendingPermissionRequest === request) {
                            pendingPermissionRequest = null
                        }
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        newFilePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = newFilePathCallback

                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }

                        try {
                            fileChooserLauncher.launch(intent)
                        } catch (e: Exception) {
                            filePathCallback = null
                            return false
                        }
                        return true
                    }
                }

                setOnScrollChangeListener { _, scrollX, scrollY, _, _ ->
                    // Prevent root horizontal drift in mobile/tablet mode when not zoomed in
                    if (!latestIsDesktopMode && !isZoomedIn && scrollX != 0) {
                        scrollTo(0, scrollY)
                    }
                    // canScrollUp is false when at the very top (scrollY == 0)
                    onScrollChanged(scrollY > 0)
                }

                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                    val trimmedUrl = downloadUrl.orEmpty().trim()
                    if (trimmedUrl.isEmpty()) return@setDownloadListener

                    if (bridge?.isRecentlyExportedUrl(trimmedUrl) == true) {
                        return@setDownloadListener
                    }

                    val lower = trimmedUrl.lowercase()
                    when {
                        lower.startsWith("http://") || lower.startsWith("https://") -> {
                            val outcome = DownloadHelper.enqueueHttpDownload(
                                context = context,
                                url = trimmedUrl,
                                userAgent = userAgent,
                                contentDisposition = contentDisposition,
                                mimeType = mimetype
                            )
                            latestOnDownloadOutcome(outcome)
                        }

                        lower.startsWith("data:") -> {
                            coroutineScope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    val parsed = DownloadHelper.parseDataUri(trimmedUrl)
                                        ?: return@withContext DownloadOutcome.Failed("Unsupported data download")
                                    val effectiveMime = mimetype?.takeIf { it.isNotBlank() } ?: parsed.mimeType
                                    val fileName = DownloadHelper.resolveDownloadFileName(
                                        url = null,
                                        contentDisposition = contentDisposition,
                                        mimeType = effectiveMime
                                    )
                                    try {
                                        val saved = DownloadHelper.saveBytesToDownloads(
                                            context = context,
                                            bytes = parsed.bytes,
                                            rawFileName = fileName,
                                            reportedMimeType = effectiveMime
                                        )
                                        DownloadOutcome.SavedToDownloads(saved)
                                    } catch (e: Exception) {
                                        DownloadOutcome.Failed(e.message ?: "Failed to save download")
                                    }
                                }
                                latestOnDownloadOutcome(outcome)
                            }
                        }

                        lower.startsWith("blob:") -> {
                            val fileName = DownloadHelper.resolveDownloadFileName(
                                url = null,
                                contentDisposition = contentDisposition,
                                mimeType = mimetype
                            )
                            val requested = bridge?.requestBlobExport(
                                url = trimmedUrl,
                                fileName = fileName,
                                mimeType = mimetype.orEmpty()
                            ) ?: false
                            if (!requested) {
                                latestOnDownloadOutcome(
                                    DownloadOutcome.Failed("Blob download is unavailable on this page")
                                )
                            }
                        }

                        else -> {
                            latestOnDownloadOutcome(DownloadOutcome.Failed("Unsupported download link"))
                        }
                    }
                }

                webViewInstance = this
                onWebViewCreated(this)
                lastLoadedUrl = url
                loadUrl(url)
            }
        },
        update = {
            // Recompositions do not re-trigger loadUrl; URL updates are controlled via LaunchedEffect
        }
    )
}

/**
 * Injects an Escape keyboard event into the active DOM element of the WebView.
 * This dismisses modals, command palettes, autocomplete menus, and search widgets
 * in web-based IDEs without closing the browser page.
 */
fun WebView.dispatchEscapeEvent() {
    val js = """
        (function() {
            var active = document.activeElement || document.body;
            var opts = {
                key: 'Escape',
                code: 'Escape',
                keyCode: 27,
                which: 27,
                bubbles: true,
                cancelable: true,
                view: window
            };
            var down = new KeyboardEvent('keydown', opts);
            active.dispatchEvent(down);
            var up = new KeyboardEvent('keyup', opts);
            active.dispatchEvent(up);
        })();
    """.trimIndent()
    evaluateJavascript(js, null)
}
