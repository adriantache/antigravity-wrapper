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
import com.adriantache.antigravitywrapper.util.NotificationHelper
import com.adriantache.antigravitywrapper.util.UrlPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages bidirectional communication between Kotlin/Compose and Antigravity web frames
 * (including the cross-origin https://antigravity.static.usercontent.goog iframe) using
 * AndroidX WebKit DocumentStartJavaScript + WebMessageListener.
 */
class AntigravityWebBridge(
    private val context: Context,
    private val configuredServerUrlProvider: () -> String,
    private val onComposerStateChanged: (ComposerState) -> Unit,
    private val onModelsLoaded: (List<ModelOption>) -> Unit,
    private val onModelSubOptionsLoaded: (parentName: String, parentFullText: String, options: List<ModelOption>) -> Unit = { _, _, _ -> },
    private val onModelSelectDone: () -> Unit = {},
    private val onPermissionsLoaded: (List<PermissionOption>) -> Unit,
    private val onScrollStateChanged: (Boolean) -> Unit,
    private val onSendResult: (ok: Boolean, error: String?) -> Unit,
    private val onAttachResult: (ok: Boolean, fileNames: List<String>, error: String?) -> Unit = { _, _, _ -> },
    private val onRequestNativeFocus: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameProxies = ConcurrentHashMap<String, JavaScriptReplyProxy>()
    @Volatile
    private var composerFrameKey: String? = null
    @Volatile
    private var currentConfig = BridgeConfig(
        nativeComposerEnabled = true,
        showVoiceButton = false,
        isTablet = false,
        enhancedWebComposer = true
    )

    fun attachTo(webView: WebView) {
        val allowedOrigins = setOf("*")

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
                postToMain { onModelsLoaded(event.models) }
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

            is BridgeEvent.WebNotification -> {
                NotificationHelper.showNotification(
                    context = context,
                    title = event.title,
                    body = event.body,
                    targetUrl = event.url
                )
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
