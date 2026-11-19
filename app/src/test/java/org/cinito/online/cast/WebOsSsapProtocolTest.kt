package org.cinito.online.cast

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOsSsapProtocolTest {
    @Test
    fun buildsRegisterPayloadIncludingAndOmittingClientKey() {
        val initial = WebOsSsapProtocol.buildRegisterPayload(clientKey = null)
        assertTrue(initial.contains("\"type\":\"register\""))
        assertTrue(initial.contains("\"pairingType\":\"PROMPT\""))
        assertFalse(initial.contains("\"client-key\""))

        val paired = WebOsSsapProtocol.buildRegisterPayload(clientKey = "lg-key-123")
        assertTrue(paired.contains("\"client-key\":\"lg-key-123\""))
        assertTrue(paired.contains("LAUNCH"))
        assertTrue(paired.contains("CONTROL_AUDIO"))
    }

    @Test
    fun parsesPromptRegisteredResponseAndErrorEnvelopes() {
        val prompt = WebOsSsapProtocol.parseEnvelope(
            """{"type":"response","id":"reg_0","payload":{"pairingType":"PROMPT","returnValue":true}}""",
        )
        assertEquals(SsapMessageType.Response, prompt.type)
        assertTrue(prompt.isPairingPrompt)
        assertNull(prompt.clientKey)

        val registered = WebOsSsapProtocol.parseEnvelope(
            """{"type":"registered","id":"reg_0","payload":{"client-key":"abc-987"}}""",
        )
        assertEquals(SsapMessageType.Registered, registered.type)
        assertEquals("abc-987", registered.clientKey)

        val err = WebOsSsapProtocol.parseEnvelope(
            """{"type":"error","id":"req_2","error":"404 no such app"}""",
        )
        assertEquals(SsapMessageType.Error, err.type)
        assertEquals("404 no such app", err.error)
    }

    @Test
    fun parsesLaunchPointsForReceiverDetection() {
        val raw = """
            {
              "type":"response",
              "id":"req_3",
              "payload":{
                "returnValue":true,
                "launchPoints":[
                  {"id":"com.webos.app.browser","title":"Navegador web"},
                  {"id":"org.cinito.tv","title":"CiniTo TV"}
                ]
              }
            }
        """.trimIndent()
        val ids = WebOsSsapProtocol.extractLaunchPointIds(WebOsSsapProtocol.parseEnvelope(raw))
        assertTrue(ids.contains("com.webos.app.browser"))
        assertTrue(ids.contains("org.cinito.tv"))
    }

    @Test
    fun buildsSsapLaunchRequestPayload() {
        val json = WebOsSsapProtocol.buildRequestPayload(
            id = "req_1",
            uri = "ssap://system.launcher/launch",
            payload = buildJsonObject {
                put("id", JsonPrimitive("org.cinito.tv"))
            },
        )
        assertTrue(json.contains("\"id\":\"req_1\""))
        assertTrue(json.contains("\"uri\":\"ssap://system.launcher/launch\""))
        assertTrue(json.contains("\"org.cinito.tv\""))
    }

    @Test
    fun extractsPointerSocketPathAndBuildsRemoteKeyFrames() {
        val raw = """{"type":"response","id":"req_9","payload":{"returnValue":true,"socketPath":"ws://192.168.1.50:3000/resources/abc/netinput.pointer.sock"}}"""
        val path = WebOsSsapProtocol.extractPointerSocketPath(WebOsSsapProtocol.parseEnvelope(raw))
        assertEquals("ws://192.168.1.50:3000/resources/abc/netinput.pointer.sock", path)

        val frame = WebOsSsapProtocol.buildPointerButtonFrame(WebOsRemoteKey.DOWN)
        assertEquals("type:button\nname:DOWN\n\n", frame)
    }
}
