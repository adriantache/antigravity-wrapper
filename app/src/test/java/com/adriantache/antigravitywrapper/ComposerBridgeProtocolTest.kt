package com.adriantache.antigravitywrapper

import com.adriantache.antigravitywrapper.bridge.BridgeConfig
import com.adriantache.antigravitywrapper.bridge.BridgeEvent
import com.adriantache.antigravitywrapper.bridge.ComposerBridgeProtocol
import com.adriantache.antigravitywrapper.bridge.UsageQuotaItem
import com.adriantache.antigravitywrapper.bridge.UsageSection
import com.adriantache.antigravitywrapper.ui.components.EnterKeyAction
import com.adriantache.antigravitywrapper.ui.components.resolveExternalKeyboardEnterAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerBridgeProtocolTest {

    @Test
    fun parseEvent_composerState_populatesAllFields() {
        val raw = """
            {
              "type": "composerState",
              "hasComposer": true,
              "currentModel": "Gemini 4 Neon High",
              "currentPermissions": "Turbo",
              "isGenerating": true,
              "hasVoiceButton": true,
              "hasAttach": true,
              "composerLeftFraction": 0.25,
              "composerRightFraction": 0.92
            }
        """.trimIndent()

        val event = ComposerBridgeProtocol.parseEvent(raw) as BridgeEvent.ComposerStateChanged
        assertTrue(event.state.hasComposer)
        assertEquals("Gemini 4 Neon High", event.state.currentModel)
        assertEquals("Turbo", event.state.currentPermissions)
        assertTrue(event.state.isGenerating)
        assertTrue(event.state.hasVoiceButton)
        assertTrue(event.state.hasAttach)
        assertEquals(0.25f, event.state.composerLeftFraction, 0.001f)
        assertEquals(0.92f, event.state.composerRightFraction, 0.001f)
    }

    @Test
    fun parseEvent_modelsList_parsesModelsAndFlags() {
        val raw = """
            {
              "type": "modelsList",
              "hasViewUsage": true,
              "models": [
                {
                  "name": "Gemini 4 Neon",
                  "effort": "High",
                  "badge": "New",
                  "fullText": "Gemini 4 Neon High New",
                  "disabled": false,
                  "isSelected": true
                },
                {
                  "name": "Gemini 4 Carbon",
                  "effort": "",
                  "badge": "Unavailable",
                  "fullText": "Gemini 4 Carbon Unavailable",
                  "disabled": true,
                  "isSelected": false
                }
              ]
            }
        """.trimIndent()

        val event = ComposerBridgeProtocol.parseEvent(raw) as BridgeEvent.ModelsLoaded
        assertTrue(event.hasViewUsage)
        assertEquals(2, event.models.size)

        val first = event.models[0]
        assertEquals("Gemini 4 Neon", first.name)
        assertEquals("High", first.effort)
        assertEquals("New", first.badge)
        assertFalse(first.isDisabled)
        assertTrue(first.isSelected)

        val second = event.models[1]
        assertEquals("Gemini 4 Carbon", second.name)
        assertEquals("Unavailable", second.badge)
        assertTrue(second.isDisabled)
        assertFalse(second.isSelected)
    }

    @Test
    fun parseEvent_modelsList_normalizesMultilineNames() {
        val raw = """
            {
              "type": "modelsList",
              "hasViewUsage": true,
              "models": [
                {
                  "name": "Gemini 4 Neon\nHigh",
                  "effort": "High\n",
                  "badge": " New ",
                  "fullText": "Gemini 4 Neon\nHigh\nNew",
                  "disabled": false,
                  "isSelected": true
                }
              ]
            }
        """.trimIndent()

        val event = ComposerBridgeProtocol.parseEvent(raw) as BridgeEvent.ModelsLoaded
        assertEquals("Gemini 4 Neon High", event.models[0].name)
        assertEquals("High", event.models[0].effort)
        assertEquals("New", event.models[0].badge)
    }

    @Test
    fun parseEvent_sendResultAndScrollStateAndNotifications() {
        val sendOk = ComposerBridgeProtocol.parseEvent("""{"type":"sendResult","ok":true,"error":""}""")
            as BridgeEvent.SendResult
        assertTrue(sendOk.ok)
        assertNull(sendOk.error)

        val sendErr = ComposerBridgeProtocol.parseEvent("""{"type":"sendResult","ok":false,"error":"Editor missing"}""")
            as BridgeEvent.SendResult
        assertFalse(sendErr.ok)
        assertEquals("Editor missing", sendErr.error)

        val scroll = ComposerBridgeProtocol.parseEvent("""{"type":"scrollState","canScrollUp":true}""")
            as BridgeEvent.ScrollStateChanged
        assertTrue(scroll.canScrollUp)

        val notif = ComposerBridgeProtocol.parseEvent(
            """{"type":"notification","title":"Done","body":"Task finished","url":"https://antigravity.google.com/r/123"}"""
        ) as BridgeEvent.WebNotification
        assertEquals("Done", notif.title)
        assertEquals("Task finished", notif.body)
        assertEquals("https://antigravity.google.com/r/123", notif.url)

        val attachOk = ComposerBridgeProtocol.parseEvent(
            """{"type":"attachResult","ok":true,"names":["photo.jpg","notes.txt"],"error":""}"""
        ) as BridgeEvent.AttachResult
        assertTrue(attachOk.ok)
        assertEquals(listOf("photo.jpg", "notes.txt"), attachOk.fileNames)
        assertNull(attachOk.error)

        val subOpts = ComposerBridgeProtocol.parseEvent(
            """
            {
              "type": "modelSubOptions",
              "parentName": "Gemini 4 Neon",
              "parentFullText": "Gemini 4 Neon High New",
              "options": [
                {"name": "High", "effort": "", "badge": "", "fullText": "High", "disabled": false, "isSelected": true},
                {"name": "Medium", "effort": "", "badge": "", "fullText": "Medium", "disabled": false, "isSelected": false}
              ]
            }
            """.trimIndent()
        ) as BridgeEvent.ModelSubOptions
        assertEquals("Gemini 4 Neon", subOpts.parentName)
        assertEquals(2, subOpts.options.size)
        assertEquals("High", subOpts.options[0].name)
        assertTrue(subOpts.options[0].isSelected)

        val selectDone = ComposerBridgeProtocol.parseEvent("""{"type":"modelSelectResult","done":true}""")
            as BridgeEvent.ModelSelectResult
        assertTrue(selectDone.done)

        assertEquals(
            BridgeEvent.RequestNativeFocus,
            ComposerBridgeProtocol.parseEvent("""{"type":"requestNativeFocus"}""")
        )

        val usage = ComposerBridgeProtocol.parseEvent(
            """
            {
              "type": "usageLoaded",
              "sections": [
                {
                  "title": "Gemini Models",
                  "items": [
                    {"label": "Weekly limit", "percentLeft": 91, "percentText": "91% left", "resetText": "Resets in 6d 21h"},
                    {"label": "5h limit", "percentLeft": 67, "percentText": "67% left", "resetText": "Resets in 2h 24m"}
                  ]
                },
                {
                  "title": "Claude and GPT models",
                  "items": [
                    {"label": "Weekly limit", "percentLeft": 100, "percentText": "100% left", "resetText": ""}
                  ]
                }
              ]
            }
            """.trimIndent()
        ) as BridgeEvent.UsageLoaded
        assertEquals(2, usage.sections.size)
        assertEquals("Gemini Models", usage.sections[0].title)
        assertEquals(91, usage.sections[0].items[0].percentLeft)
        assertEquals("Resets in 6d 21h", usage.sections[0].items[0].resetText)
    }

    @Test
    fun filterRelevantUsageSections_matchesActiveModelFamily() {
        val sections = listOf(
            UsageSection("Gemini Models", listOf(UsageQuotaItem("5h limit", 67, "67% left"))),
            UsageSection("Claude and GPT models", listOf(UsageQuotaItem("5h limit", 100, "100% left")))
        )

        val geminiOnly = ComposerBridgeProtocol.filterRelevantUsageSections(sections, "Gemini 4 Neon High")
        assertEquals(1, geminiOnly.size)
        assertEquals("Gemini Models", geminiOnly[0].title)

        val orchestratorOnly = ComposerBridgeProtocol.filterRelevantUsageSections(sections, "Orchestrator Gemini 4 Neon Extra High")
        assertEquals(1, orchestratorOnly.size)
        assertEquals("Gemini Models", orchestratorOnly[0].title)

        val claudeOnly = ComposerBridgeProtocol.filterRelevantUsageSections(sections, "Claude Sonnet 5.5 Medium")
        assertEquals(1, claudeOnly.size)
        assertEquals("Claude and GPT models", claudeOnly[0].title)

        val gptOnly = ComposerBridgeProtocol.filterRelevantUsageSections(sections, "GPT-OSS 120B (Medium)")
        assertEquals(1, gptOnly.size)
        assertEquals("Claude and GPT models", gptOnly[0].title)

        val unknownFallback = ComposerBridgeProtocol.filterRelevantUsageSections(sections, "Some Future Model")
        assertEquals(2, unknownFallback.size)
    }

    @Test
    fun parseEvent_invalidOrUnknownJson_returnsNull() {
        assertNull(ComposerBridgeProtocol.parseEvent(null))
        assertNull(ComposerBridgeProtocol.parseEvent(""))
        assertNull(ComposerBridgeProtocol.parseEvent("not json"))
        assertNull(ComposerBridgeProtocol.parseEvent("""{"type":"unknown"}"""))
    }

    @Test
    fun encodeConfigAndCommands_produceValidJson() {
        val cfgJson = JSONObject(
            ComposerBridgeProtocol.encodeConfig(
                BridgeConfig(
                    nativeComposerEnabled = true,
                    showVoiceButton = false,
                    isTablet = true,
                    enhancedWebComposer = true,
                    composerSpacerPx = 112
                )
            )
        )
        assertEquals("config", cfgJson.getString("type"))
        assertTrue(cfgJson.getBoolean("nativeComposerEnabled"))
        assertFalse(cfgJson.getBoolean("showVoiceButton"))
        assertTrue(cfgJson.getBoolean("isTablet"))
        assertTrue(cfgJson.getBoolean("enhancedWebComposer"))
        assertEquals(112, cfgJson.getInt("composerSpacerPx"))

        val sendJson = JSONObject(ComposerBridgeProtocol.encodeSend("Hello\nWorld"))
        assertEquals("send", sendJson.getString("type"))
        assertEquals("Hello\nWorld", sendJson.getString("text"))

        val modelJson = JSONObject(
            ComposerBridgeProtocol.encodeSelectModel(
                fullText = "Gemini 4 Neon High New",
                name = "Gemini 4 Neon",
                subFullText = "Medium",
                subName = "Medium"
            )
        )
        assertEquals("selectModel", modelJson.getString("type"))
        assertEquals("Gemini 4 Neon High New", modelJson.getString("fullText"))
        assertEquals("Gemini 4 Neon", modelJson.getString("name"))
        assertEquals("Medium", modelJson.getString("subFullText"))
        assertEquals("Medium", modelJson.getString("subName"))

        val batchStart = JSONObject(ComposerBridgeProtocol.encodeAttachBatchStart("b_1", 2))
        assertEquals("attachBatchStart", batchStart.getString("type"))
        assertEquals("b_1", batchStart.getString("batchId"))
        assertEquals(2, batchStart.getInt("fileCount"))

        val fileStart = JSONObject(ComposerBridgeProtocol.encodeAttachFileStart("b_1", 0, "img.png", "image/png"))
        assertEquals("attachFileStart", fileStart.getString("type"))
        assertEquals("img.png", fileStart.getString("name"))
        assertEquals("image/png", fileStart.getString("mimeType"))

        val fileChunk = JSONObject(ComposerBridgeProtocol.encodeAttachFileChunk("b_1", 0, "YWJj"))
        assertEquals("attachFileChunk", fileChunk.getString("type"))
        assertEquals("YWJj", fileChunk.getString("data"))

        val batchCommit = JSONObject(ComposerBridgeProtocol.encodeAttachBatchCommit("b_1"))
        assertEquals("attachBatchCommit", batchCommit.getString("type"))
        assertEquals("b_1", batchCommit.getString("batchId"))
    }

    @Test
    fun externalKeyboardEnter_sendsOnReturnAndNewlinesOnShiftReturn() {
        assertEquals(
            EnterKeyAction.SEND,
            resolveExternalKeyboardEnterAction(
                isEnterKey = true,
                isHardwareKeyboard = true,
                isShiftPressed = false,
                isKeyDown = true,
                repeatCount = 0,
                canSend = true
            )
        )
        assertEquals(
            EnterKeyAction.PASS_THROUGH,
            resolveExternalKeyboardEnterAction(
                isEnterKey = true,
                isHardwareKeyboard = true,
                isShiftPressed = true,
                isKeyDown = true,
                repeatCount = 0,
                canSend = true
            )
        )
        assertEquals(
            EnterKeyAction.PASS_THROUGH,
            resolveExternalKeyboardEnterAction(
                isEnterKey = true,
                isHardwareKeyboard = false,
                isShiftPressed = false,
                isKeyDown = true,
                repeatCount = 0,
                canSend = true
            )
        )
        assertEquals(
            EnterKeyAction.CONSUME_ONLY,
            resolveExternalKeyboardEnterAction(
                isEnterKey = true,
                isHardwareKeyboard = true,
                isShiftPressed = false,
                isKeyDown = true,
                repeatCount = 1,
                canSend = true
            )
        )
        assertEquals(
            EnterKeyAction.CONSUME_ONLY,
            resolveExternalKeyboardEnterAction(
                isEnterKey = true,
                isHardwareKeyboard = true,
                isShiftPressed = false,
                isKeyDown = true,
                repeatCount = 0,
                canSend = false
            )
        )
        assertEquals(
            EnterKeyAction.CONSUME_ONLY,
            resolveExternalKeyboardEnterAction(
                isEnterKey = true,
                isHardwareKeyboard = true,
                isShiftPressed = false,
                isKeyDown = false,
                repeatCount = 0,
                canSend = true
            )
        )
    }
}
