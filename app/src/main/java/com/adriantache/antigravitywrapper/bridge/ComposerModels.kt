package com.adriantache.antigravitywrapper.bridge

import org.json.JSONArray
import org.json.JSONObject

data class ComposerState(
    val hasComposer: Boolean = false,
    val currentModel: String = "",
    val currentPermissions: String = "",
    val isGenerating: Boolean = false,
    val hasVoiceButton: Boolean = false,
    val hasAttach: Boolean = false
)

data class ModelOption(
    val name: String,
    val effort: String = "",
    val badge: String = "",
    val fullText: String,
    val isDisabled: Boolean = false,
    val isSelected: Boolean = false
)

data class PermissionOption(
    val label: String,
    val description: String = "",
    val isSelected: Boolean = false
)

data class BridgeConfig(
    val nativeComposerEnabled: Boolean,
    val showVoiceButton: Boolean,
    val isTablet: Boolean,
    val enhancedWebComposer: Boolean = true
)

data class AttachedFilePayload(
    val name: String,
    val mimeType: String,
    val base64Data: String
)

sealed class BridgeEvent {
    data object Ready : BridgeEvent()
    data class ComposerStateChanged(val state: ComposerState) : BridgeEvent()
    data class ModelsLoaded(val models: List<ModelOption>) : BridgeEvent()
    data class PermissionsLoaded(val options: List<PermissionOption>) : BridgeEvent()
    data class ScrollStateChanged(val canScrollUp: Boolean) : BridgeEvent()
    data class SendResult(val ok: Boolean, val error: String? = null) : BridgeEvent()
    data class AttachResult(
        val ok: Boolean,
        val fileNames: List<String> = emptyList(),
        val error: String? = null
    ) : BridgeEvent()
    data class WebNotification(val title: String, val body: String, val url: String) : BridgeEvent()
}

object ComposerBridgeProtocol {

    fun parseEvent(rawJson: String?): BridgeEvent? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val json = JSONObject(rawJson)
            when (json.optString("type")) {
                "ready" -> BridgeEvent.Ready

                "composerState" -> BridgeEvent.ComposerStateChanged(
                    ComposerState(
                        hasComposer = json.optBoolean("hasComposer", false),
                        currentModel = json.optString("currentModel", ""),
                        currentPermissions = json.optString("currentPermissions", ""),
                        isGenerating = json.optBoolean("isGenerating", false),
                        hasVoiceButton = json.optBoolean("hasVoiceButton", false),
                        hasAttach = json.optBoolean("hasAttach", false)
                    )
                )

                "modelsList" -> {
                    val arr = json.optJSONArray("models") ?: JSONArray()
                    val models = buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val name = item.optString("name", "").trim()
                            val fullText = item.optString("fullText", name).trim()
                            if (name.isNotEmpty() || fullText.isNotEmpty()) {
                                add(
                                    ModelOption(
                                        name = name.ifEmpty { fullText },
                                        effort = item.optString("effort", "").trim(),
                                        badge = item.optString("badge", "").trim(),
                                        fullText = fullText.ifEmpty { name },
                                        isDisabled = item.optBoolean("disabled", false),
                                        isSelected = item.optBoolean("isSelected", false)
                                    )
                                )
                            }
                        }
                    }
                    BridgeEvent.ModelsLoaded(models)
                }

                "permissionsList" -> {
                    val arr = json.optJSONArray("items") ?: JSONArray()
                    val options = buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val label = item.optString("label", "").trim()
                            if (label.isNotEmpty()) {
                                add(
                                    PermissionOption(
                                        label = label,
                                        description = item.optString("description", "").trim(),
                                        isSelected = item.optBoolean("isSelected", false)
                                    )
                                )
                            }
                        }
                    }
                    BridgeEvent.PermissionsLoaded(options)
                }

                "scrollState" -> BridgeEvent.ScrollStateChanged(
                    canScrollUp = json.optBoolean("canScrollUp", false)
                )

                "sendResult" -> BridgeEvent.SendResult(
                    ok = json.optBoolean("ok", false),
                    error = json.optString("error", "").ifBlank { null }
                )

                "attachResult" -> {
                    val arr = json.optJSONArray("names") ?: JSONArray()
                    val names = buildList {
                        for (i in 0 until arr.length()) {
                            val name = arr.optString(i, "").trim()
                            if (name.isNotEmpty()) add(name)
                        }
                    }
                    BridgeEvent.AttachResult(
                        ok = json.optBoolean("ok", false),
                        fileNames = names,
                        error = json.optString("error", "").ifBlank { null }
                    )
                }

                "notification" -> BridgeEvent.WebNotification(
                    title = json.optString("title", "Antigravity"),
                    body = json.optString("body", ""),
                    url = json.optString("url", "")
                )

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun encodeConfig(config: BridgeConfig): String = JSONObject().apply {
        put("type", "config")
        put("nativeComposerEnabled", config.nativeComposerEnabled)
        put("showVoiceButton", config.showVoiceButton)
        put("isTablet", config.isTablet)
        put("enhancedWebComposer", config.enhancedWebComposer)
    }.toString()

    fun encodeSend(text: String): String = JSONObject().apply {
        put("type", "send")
        put("text", text)
    }.toString()

    fun encodeSelectModel(fullText: String, name: String): String = JSONObject().apply {
        put("type", "selectModel")
        put("fullText", fullText)
        put("name", name)
    }.toString()

    fun encodeSelectPermission(label: String): String = JSONObject().apply {
        put("type", "selectPermission")
        put("label", label)
    }.toString()

    fun encodeSimpleCommand(type: String): String = JSONObject().apply {
        put("type", type)
    }.toString()

    fun encodeAttachBatchStart(batchId: String, fileCount: Int): String = JSONObject().apply {
        put("type", "attachBatchStart")
        put("batchId", batchId)
        put("fileCount", fileCount)
    }.toString()

    fun encodeAttachFileStart(
        batchId: String,
        index: Int,
        name: String,
        mimeType: String
    ): String = JSONObject().apply {
        put("type", "attachFileStart")
        put("batchId", batchId)
        put("index", index)
        put("name", name)
        put("mimeType", mimeType)
    }.toString()

    fun encodeAttachFileChunk(
        batchId: String,
        index: Int,
        data: String
    ): String = JSONObject().apply {
        put("type", "attachFileChunk")
        put("batchId", batchId)
        put("index", index)
        put("data", data)
    }.toString()

    fun encodeAttachBatchCommit(batchId: String): String = JSONObject().apply {
        put("type", "attachBatchCommit")
        put("batchId", batchId)
    }.toString()
}
