package com.adriantache.antigravitywrapper.bridge

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.adriantache.antigravitywrapper.util.DownloadHelper
import com.adriantache.antigravitywrapper.util.DownloadOutcome
import com.adriantache.antigravitywrapper.util.NotificationHelper
import com.adriantache.antigravitywrapper.util.UrlPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages bidirectional communication between Kotlin/Compose and Antigravity web frames
 * (including the cross-origin https://antigravity.static.usercontent.goog iframe) using
 * AndroidX WebKit DocumentStartJavaScript + WebMessageListener.
 */
class AntigravityWebBridge(
    private val context: Context,
    private val configuredServerUrlProvider: () -> String,
    private val onComposerStateChanged: (ComposerState) -> Unit,
    private val onModelsLoaded: (models: List<ModelOption>, hasViewUsage: Boolean) -> Unit,
    private val onModelSubOptionsLoaded: (parentName: String, parentFullText: String, options: List<ModelOption>) -> Unit = { _, _, _ -> },
    private val onModelSelectDone: () -> Unit = {},
    private val onPermissionsLoaded: (List<PermissionOption>) -> Unit,
    private val onUsageLoaded: (List<UsageSection>) -> Unit = {},
    private val onScrollStateChanged: (Boolean) -> Unit,
    private val onSendResult: (ok: Boolean, error: String?) -> Unit,
    private val onAttachResult: (ok: Boolean, fileNames: List<String>, error: String?) -> Unit = { _, _, _ -> },
    private val onRequestNativeFocus: () -> Unit = {},
    private val onDownloadOutcome: (DownloadOutcome) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val frameProxies = ConcurrentHashMap<String, JavaScriptReplyProxy>()
    private val backRequestSeq = AtomicLong(0L)
    private val downloadRequestSeq = AtomicLong(0L)
    private val pendingBackRequests = LinkedHashMap<String, PendingBackRequest>()
    private val pendingDownloads = LinkedHashMap<String, PendingDownload>()
    private val pendingBlobTimeouts = LinkedHashMap<String, Runnable>()
    private val recentExportedUrls = LinkedHashMap<String, Long>()
    @Volatile
    private var composerFrameKey: String? = null

    private class PendingBackRequest(
        val remainingFrames: ArrayDeque<Pair<String, JavaScriptReplyProxy>>,
        val onResult: (handled: Boolean, onConversationList: Boolean) -> Unit,
        var timeoutRunnable: Runnable? = null,
        var completed: Boolean = false
    )

    private class PendingDownload(
        val fileName: String,
        val mimeType: String,
        val totalBytes: Long,
        val chunks: MutableList<String> = mutableListOf(),
        var totalBase64Chars: Long = 0L
    )
    @Volatile
    private var currentConfig = BridgeConfig(
        nativeComposerEnabled = true,
        showVoiceButton = false,
        isTablet = false,
        enhancedWebComposer = true
    )

    fun attachTo(webView: WebView) {
        val allowedOrigins = buildAllowedOriginRules(configuredServerUrlProvider())

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                AntigravityBridgeScript.BRIDGE_NAME,
                allowedOrigins
            ) { _: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy ->
                val originStr = sourceOrigin.toString()
                if (!UrlPolicy.isTrustedBridgeOrigin(originStr, configuredServerUrlProvider())) {
                    return@addWebMessageListener
                }

                val frameKey = "$originStr|$isMainFrame"
                frameProxies[frameKey] = replyProxy

                val event = ComposerBridgeProtocol.parseEvent(message.data) ?: return@addWebMessageListener
                handleEvent(frameKey, isMainFrame, replyProxy, event)
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AntigravityBridgeScript.SCRIPT,
                allowedOrigins
            )
        }
    }

    private fun buildAllowedOriginRules(configuredServerUrl: String): Set<String> {
        val rules = linkedSetOf(
            "https://antigravity.static.usercontent.goog",
            "https://*.antigravity.static.usercontent.goog",
            "https://antigravity.google.com",
            "https://*.antigravity.google.com",
            "https://antigravity.google",
            "https://*.antigravity.google"
        )
        UrlPolicy.extractEndpoint(configuredServerUrl)?.let { ep ->
            val portPart = if (ep.explicitPort != -1) ":${ep.explicitPort}" else ""
            val hostPart = if (ep.host.contains(":")) "[${ep.host}]" else ep.host
            rules.add("${ep.scheme}://$hostPart$portPart")
        }
        return rules
    }

    private fun handleEvent(
        frameKey: String,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
        event: BridgeEvent
    ) {
        when (event) {
            BridgeEvent.Ready -> {
                safePost(replyProxy, ComposerBridgeProtocol.encodeConfig(currentConfig))
            }

            is BridgeEvent.ComposerStateChanged -> {
                val activeKey = composerFrameKey
                if (event.state.hasComposer) {
                    composerFrameKey = frameKey
                    postToMain { onComposerStateChanged(event.state) }
                } else if (activeKey == null || activeKey == frameKey) {
                    if (activeKey == frameKey) {
                        composerFrameKey = null
                    }
                    postToMain { onComposerStateChanged(event.state) }
                }
            }

            is BridgeEvent.ModelsLoaded -> {
                postToMain { onModelsLoaded(event.models, event.hasViewUsage) }
            }

            is BridgeEvent.ModelSubOptions -> {
                postToMain {
                    onModelSubOptionsLoaded(event.parentName, event.parentFullText, event.options)
                }
            }

            is BridgeEvent.ModelSelectResult -> {
                postToMain { onModelSelectDone() }
            }

            is BridgeEvent.PermissionsLoaded -> {
                postToMain { onPermissionsLoaded(event.options) }
            }

            is BridgeEvent.UsageLoaded -> {
                postToMain { onUsageLoaded(event.sections) }
            }

            is BridgeEvent.ScrollStateChanged -> {
                if (composerFrameKey == null || composerFrameKey == frameKey || !isMainFrame) {
                    postToMain { onScrollStateChanged(event.canScrollUp) }
                }
            }

            is BridgeEvent.SendResult -> {
                postToMain { onSendResult(event.ok, event.error) }
            }

            is BridgeEvent.AttachResult -> {
                postToMain { onAttachResult(event.ok, event.fileNames, event.error) }
            }

            BridgeEvent.RequestNativeFocus -> {
                postToMain { onRequestNativeFocus() }
            }

            is BridgeEvent.BackNavigationResult -> {
                postToMain { handleBackNavigationResult(event) }
            }

            is BridgeEvent.WebNotification -> {
                NotificationHelper.showNotification(
                    context = context,
                    title = event.title,
                    body = event.body,
                    targetUrl = event.url
                )
            }

            is BridgeEvent.DownloadStart -> {
                postToMain { handleDownloadStart(event) }
            }

            is BridgeEvent.DownloadChunk -> {
                postToMain { handleDownloadChunk(event) }
            }

            is BridgeEvent.DownloadCommit -> {
                postToMain { handleDownloadCommit(event.downloadId) }
            }

            is BridgeEvent.DownloadError -> {
                postToMain { handleDownloadError(event.downloadId, event.error) }
            }
        }
    }

    fun updateConfig(config: BridgeConfig) {
        currentConfig = config
        val payload = ComposerBridgeProtocol.encodeConfig(config)
        broadcast(payload)
    }

    fun resetForNavigation() {
        composerFrameKey = null
        frameProxies.clear()
        postToMain {
            pendingBackRequests.values.forEach { req ->
                req.completed = true
                req.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            }
            pendingBackRequests.clear()
            onComposerStateChanged(ComposerState())
            onScrollStateChanged(false)
        }
    }

    fun sendMessage(text: String): Boolean {
        return postToComposerFrame(ComposerBridgeProtocol.encodeSend(text))
    }

    fun stopGeneration() {
        postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("stop"))
    }

    fun requestModels(): Boolean {
        return postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("getModels"))
    }

    fun selectModel(option: ModelOption) {
        postToComposerFrame(ComposerBridgeProtocol.encodeSelectModel(option.fullText, option.name))
    }

    fun requestUsage(): Boolean {
        return postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("getUsage"))
    }

    fun openUsage() {
        postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("openUsage"))
    }

    fun selectModelSubOption(
        parentFullText: String,
        parentName: String,
        subOption: ModelOption
    ) {
        postToComposerFrame(
            ComposerBridgeProtocol.encodeSelectModel(
                fullText = parentFullText,
                name = parentName,
                subFullText = subOption.fullText,
                subName = subOption.name
            )
        )
    }

    fun requestPermissions(): Boolean {
        return postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("getPermissions"))
    }

    fun selectPermission(option: PermissionOption) {
        postToComposerFrame(ComposerBridgeProtocol.encodeSelectPermission(option.label))
    }

    fun attachFiles(files: List<AttachedFilePayload>): Boolean {
        if (files.isEmpty()) return false
        val batchId = "b_${System.nanoTime()}"
        if (!postToComposerFrame(ComposerBridgeProtocol.encodeAttachBatchStart(batchId, files.size))) {
            return false
        }
        val chunkSize = 200_000
        files.forEachIndexed { index, file ->
            if (!postToComposerFrame(
                    ComposerBridgeProtocol.encodeAttachFileStart(
                        batchId = batchId,
                        index = index,
                        name = file.name,
                        mimeType = file.mimeType
                    )
                )
            ) {
                return false
            }
            var offset = 0
            val data = file.base64Data
            while (offset < data.length) {
                val end = (offset + chunkSize).coerceAtMost(data.length)
                if (!postToComposerFrame(
                        ComposerBridgeProtocol.encodeAttachFileChunk(
                            batchId = batchId,
                            index = index,
                            data = data.substring(offset, end)
                        )
                    )
                ) {
                    return false
                }
                offset = end
            }
        }
        return postToComposerFrame(ComposerBridgeProtocol.encodeAttachBatchCommit(batchId))
    }

    fun clearAttachments() {
        postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("clearAttachments"))
    }

    fun triggerAttach() {
        postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("triggerAttach"))
    }

    fun triggerVoice() {
        postToComposerFrame(ComposerBridgeProtocol.encodeSimpleCommand("triggerVoice"))
    }

    fun dispatchEscape() {
        broadcast(ComposerBridgeProtocol.encodeSimpleCommand("dispatchEscape"))
    }

    fun isRecentlyExportedUrl(url: String): Boolean {
        val now = System.currentTimeMillis()
        recentExportedUrls.entries.removeAll { now - it.value > 2500L }
        return recentExportedUrls.containsKey(url)
    }

    fun requestBlobExport(url: String, fileName: String, mimeType: String): Boolean {
        if (frameProxies.isEmpty()) return false
        val requestId = "exp_${downloadRequestSeq.incrementAndGet()}"
        val timeout = Runnable {
            pendingBlobTimeouts.remove(requestId)
            pendingDownloads.remove(requestId)
            onDownloadOutcome(DownloadOutcome.Failed("Could not read exported blob"))
        }
        pendingBlobTimeouts[requestId] = timeout
        mainHandler.postDelayed(timeout, 8000L)
        broadcast(ComposerBridgeProtocol.encodeExportBlob(requestId, url, fileName, mimeType))
        return true
    }

    private fun handleDownloadStart(event: BridgeEvent.DownloadStart) {
        pendingBlobTimeouts.remove(event.downloadId)?.let { mainHandler.removeCallbacks(it) }
        if (event.sourceUrl.isNotBlank()) {
            val now = System.currentTimeMillis()
            recentExportedUrls.entries.removeAll { now - it.value > 2500L }
            recentExportedUrls[event.sourceUrl] = now
        }
        if (event.totalBytes > MAX_EXPORT_BYTES) {
            pendingDownloads.remove(event.downloadId)
            onDownloadOutcome(DownloadOutcome.Failed("Exported file exceeds 50 MB limit"))
            return
        }
        pendingDownloads[event.downloadId] = PendingDownload(
            fileName = event.fileName,
            mimeType = event.mimeType,
            totalBytes = event.totalBytes
        )
    }

    private fun handleDownloadChunk(event: BridgeEvent.DownloadChunk) {
        val pending = pendingDownloads[event.downloadId] ?: return
        val nextChars = pending.totalBase64Chars + event.data.length
        if (nextChars > MAX_EXPORT_BASE64_CHARS) {
            pendingDownloads.remove(event.downloadId)
            onDownloadOutcome(DownloadOutcome.Failed("Exported file exceeds 50 MB limit"))
            return
        }
        pending.totalBase64Chars = nextChars
        pending.chunks.add(event.data)
    }

    private fun handleDownloadCommit(downloadId: String) {
        val pending = pendingDownloads.remove(downloadId) ?: return
        val joined = pending.chunks.joinToString(separator = "")
        downloadExecutor.execute {
            val outcome = try {
                val bytes = DownloadHelper.decodeBase64(joined)
                val saved = DownloadHelper.saveBytesToDownloads(
                    context = context,
                    bytes = bytes,
                    rawFileName = pending.fileName,
                    reportedMimeType = pending.mimeType
                )
                DownloadOutcome.SavedToDownloads(saved)
            } catch (e: Exception) {
                DownloadOutcome.Failed(e.message ?: "Failed to save exported file")
            }
            postToMain { onDownloadOutcome(outcome) }
        }
    }

    private fun handleDownloadError(downloadId: String, error: String) {
        pendingBlobTimeouts.remove(downloadId)?.let { mainHandler.removeCallbacks(it) }
        pendingDownloads.remove(downloadId)
        onDownloadOutcome(DownloadOutcome.Failed(error.ifBlank { "Export failed" }))
    }

    companion object {
        private const val MAX_EXPORT_BYTES = 50L * 1024L * 1024L
        private const val MAX_EXPORT_BASE64_CHARS = 70L * 1024L * 1024L
    }

    /**
     * Asks the active Antigravity SPA frame (and any other attached frames in priority order)
     * to handle in-app back navigation: dismiss open web dialogs/menus, or click the mobile
     * conversation Back button to return to the conversation list.
     */
    fun navigateBack(onResult: (handled: Boolean, onConversationList: Boolean) -> Unit) {
        postToMain {
            val orderedFrames = ArrayDeque<Pair<String, JavaScriptReplyProxy>>()
            val primaryKey = composerFrameKey
            if (primaryKey != null) {
                frameProxies[primaryKey]?.let { orderedFrames.addLast(primaryKey to it) }
            }
            frameProxies.entries
                .filter { it.key != primaryKey && it.key.endsWith("|false") }
                .forEach { orderedFrames.addLast(it.key to it.value) }
            frameProxies.entries
                .filter { it.key != primaryKey && !it.key.endsWith("|false") }
                .forEach { orderedFrames.addLast(it.key to it.value) }

            if (orderedFrames.isEmpty()) {
                onResult(false, false)
                return@postToMain
            }

            val requestId = "back_${backRequestSeq.incrementAndGet()}"
            val request = PendingBackRequest(
                remainingFrames = orderedFrames,
                onResult = onResult
            )
            val timeout = Runnable {
                completeBackRequest(requestId, handled = false, onConversationList = false)
            }
            request.timeoutRunnable = timeout
            pendingBackRequests[requestId] = request
            mainHandler.postDelayed(timeout, 300L)

            dispatchNextBackFrame(requestId, request)
        }
    }

    private fun dispatchNextBackFrame(requestId: String, request: PendingBackRequest) {
        if (request.completed) return
        val payload = ComposerBridgeProtocol.encodeNavigateBack(requestId)
        while (request.remainingFrames.isNotEmpty()) {
            val (key, proxy) = request.remainingFrames.removeFirst()
            if (safePost(proxy, payload)) {
                return
            }
            frameProxies.remove(key, proxy)
        }
        completeBackRequest(requestId, handled = false, onConversationList = false)
    }

    private fun handleBackNavigationResult(event: BridgeEvent.BackNavigationResult) {
        val request = pendingBackRequests[event.requestId] ?: return
        if (request.completed) return

        when {
            event.handled -> completeBackRequest(event.requestId, handled = true, onConversationList = false)
            event.onConversationList -> completeBackRequest(event.requestId, handled = false, onConversationList = true)
            else -> dispatchNextBackFrame(event.requestId, request)
        }
    }

    private fun completeBackRequest(
        requestId: String,
        handled: Boolean,
        onConversationList: Boolean
    ) {
        val request = pendingBackRequests.remove(requestId) ?: return
        if (request.completed) return
        request.completed = true
        request.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        request.onResult(handled, onConversationList)
    }

    private fun postToComposerFrame(payload: String): Boolean {
        val primary = composerFrameKey?.let { frameProxies[it] }
        if (primary != null && safePost(primary, payload)) {
            return true
        }
        // Fallback: prefer any iframe proxy, then any proxy
        val fallback = frameProxies.entries.firstOrNull { it.key.endsWith("|false") }?.value
            ?: frameProxies.values.firstOrNull()
        return fallback != null && safePost(fallback, payload)
    }

    private fun broadcast(payload: String) {
        frameProxies.entries.toList().forEach { (key, proxy) ->
            if (!safePost(proxy, payload)) {
                frameProxies.remove(key, proxy)
            }
        }
    }

    private fun safePost(proxy: JavaScriptReplyProxy, payload: String): Boolean {
        return try {
            proxy.postMessage(payload)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
