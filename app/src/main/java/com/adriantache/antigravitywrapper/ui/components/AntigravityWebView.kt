package com.adriantache.antigravitywrapper.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.adriantache.antigravitywrapper.util.NotificationHelper

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
    onWebViewCreated: (WebView) -> Unit = {},
    onPageStarted: () -> Unit = {},
    onPageFinished: () -> Unit = {},
    onProgressChanged: (Int) -> Unit = {},
    onConnectionError: (errorCode: Int, description: String, failingUrl: String) -> Unit = { _, _, _ -> },
    onScrollChanged: (canScrollUp: Boolean) -> Unit = {}
) {
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

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
                addJavascriptInterface(WebNotificationBridge(context), "_AntigravityNotifier")
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val reqUrl = request?.url ?: return false
                        val targetUrl = reqUrl.toString()

                        if (targetUrl.startsWith("antigravity://")) {
                            val converted = NotificationHelper.normalizeUrl(targetUrl)
                            view?.loadUrl(converted)
                            return true
                        }

                        val host = reqUrl.host?.lowercase() ?: ""
                        // Allow Google authentication, Antigravity web dashboard, and local servers
                        if (host == "antigravity.google" ||
                            host.endsWith(".antigravity.google") ||
                            host.endsWith("google.com") ||
                            host.endsWith("gstatic.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host == "localhost" ||
                            host == "10.0.2.2" ||
                            host.startsWith("192.168.") ||
                            host.startsWith("10.") ||
                            host.startsWith("172.")
                        ) {
                            return false
                        }

                        // Try opening external link in external browser
                        return try {
                            val intent = Intent(Intent.ACTION_VIEW, reqUrl)
                            view?.context?.startActivity(intent)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
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
                        // Automatically grant permissions for remote dev sessions (microphone, audio, etc.)
                        request?.grant(request.resources)
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

                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    // canScrollUp is false when at the very top (scrollY == 0)
                    onScrollChanged(scrollY > 0)
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
