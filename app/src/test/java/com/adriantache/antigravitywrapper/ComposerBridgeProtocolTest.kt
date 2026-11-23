package com.adriantache.antigravitywrapper

import com.adriantache.antigravitywrapper.bridge.BridgeConfig
import com.adriantache.antigravitywrapper.bridge.BridgeEvent
import com.adriantache.antigravitywrapper.bridge.ComposerBridgeProtocol
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
              "hasAttach": true
            }
        """.trimIndent()

        val event = ComposerBridgeProtocol.parseEvent(raw) as BridgeEvent.ComposerStateChanged
        assertTrue(event.state.hasComposer)
        assertEquals("Gemini 4 Neon High", event.state.currentModel)
        assertEquals("Turbo", event.state.currentPermissions)
        assertTrue(event.state.isGenerating)
        assertTrue(event.state.hasVoiceButton)
        assertTrue(event.state.hasAttach)
    }

    @Test
    fun parseEvent_modelsList_parsesModelsAndFlags() {
        val raw = """
            {
              "type": "modelsList",
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
                    enhancedWebComposer = true
                )
            )
        )
        assertEquals("config", cfgJson.getString("type"))
        assertTrue(cfgJson.getBoolean("nativeComposerEnabled"))
        assertFalse(cfgJson.getBoolean("showVoiceButton"))
        assertTrue(cfgJson.getBoolean("isTablet"))
        assertTrue(cfgJson.getBoolean("enhancedWebComposer"))

        val sendJson = JSONObject(ComposerBridgeProtocol.encodeSend("Hello\nWorld"))
        assertEquals("send", sendJson.getString("type"))
        assertEquals("Hello\nWorld", sendJson.getString("text"))

        val modelJson = JSONObject(
            ComposerBridgeProtocol.encodeSelectModel("Gemini 4 Neon High New", "Gemini 4 Neon")
        )
        assertEquals("selectModel", modelJson.getString("type"))
        assertEquals("Gemini 4 Neon High New", modelJson.getString("fullText"))
        assertEquals("Gemini 4 Neon", modelJson.getString("name"))
    }
}
