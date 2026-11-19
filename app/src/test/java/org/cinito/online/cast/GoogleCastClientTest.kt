package org.cinito.online.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.cinito.online.settings.SubtitleEdgeStyle
import org.cinito.online.settings.SubtitleStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GoogleCastClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeFramedMessages(bytes: ByteArray): List<CastWireMessage> {
        val out = mutableListOf<CastWireMessage>()
        var idx = 0
        while (idx + 4 <= bytes.size) {
            val len = ((bytes[idx].toInt() and 0xFF) shl 24) or
                ((bytes[idx + 1].toInt() and 0xFF) shl 16) or
                ((bytes[idx + 2].toInt() and 0xFF) shl 8) or
                (bytes[idx + 3].toInt() and 0xFF)
            idx += 4
            if (len <= 0 || idx + len > bytes.size) break
            out += CastProtocolUtils.decodeCastMessage(bytes.copyOfRange(idx, idx + len))
            idx += len
        }
        return out
    }

    @Test
    fun preservesStatusCallbackAcrossCloseTransportAndComputesCanReuseDynamically() {
        val client = GoogleCastClient()
        assertFalse(client.canReuseConnection)

        client.setStatusCallbackForUnitTest { }
        assertTrue(client.hasStatusCallbackForUnitTest())

        client.closeTransportForUnitTest(clearCallback = false)
        assertTrue(client.hasStatusCallbackForUnitTest())

        client.close()
        assertFalse(client.hasStatusCallbackForUnitTest())
        assertFalse(client.canReuseConnection)
    }

    @Test
    fun filtersReceiverStatusStrictlyForDefaultMediaReceiverAndHandlesLaunchError() = runBlocking {
        val client = GoogleCastClient()
        val out = ByteArrayOutputStream()
        val ready = CompletableDeferred<String>()
        client.installTestTransportForUnitTest(out, ready)

        // Backdrop / other app must NOT complete transportReady
        client.handleIncomingMessage(
            CastWireMessage(
                sourceId = "receiver-0",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.receiver",
                payloadUtf8 = """
                    {
                      "type": "RECEIVER_STATUS",
                      "status": {
                        "applications": [
                          {"appId": "E8C28D3C", "transportId": "backdrop-tid-1"}
                        ]
                      }
                    }
                """.trimIndent(),
            ),
        )
        assertFalse(ready.isCompleted)
        assertNull(client.currentTransportIdForUnitTest())

        // Default Media Receiver (CC1AD845) connects with origin:{} BEFORE completing transportReady
        client.handleIncomingMessage(
            CastWireMessage(
                sourceId = "receiver-0",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.receiver",
                payloadUtf8 = """
                    {
                      "type": "RECEIVER_STATUS",
                      "status": {
                        "applications": [
                          {"appId": "CC1AD845", "transportId": "media-tid-99"}
                        ]
                      }
                    }
                """.trimIndent(),
            ),
        )
        assertTrue(ready.isCompleted)
        assertEquals("media-tid-99", ready.await())
        assertEquals("media-tid-99", client.currentTransportIdForUnitTest())

        val sent = decodeFramedMessages(out.toByteArray())
        assertEquals(1, sent.size)
        assertEquals("media-tid-99", sent.first().destinationId)
        assertEquals("urn:x-cast:com.google.cast.tp.connection", sent.first().namespace)
        val connectObj = json.parseToJsonElement(sent.first().payloadUtf8!!) as JsonObject
        assertEquals("CONNECT", connectObj["type"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(connectObj["origin"] as? JsonObject)

        // LAUNCH_ERROR completes transportReady exceptionally
        val clientErr = GoogleCastClient()
        val readyErr = CompletableDeferred<String>()
        clientErr.installTestTransportForUnitTest(ByteArrayOutputStream(), readyErr)
        clientErr.handleIncomingMessage(
            CastWireMessage(
                sourceId = "receiver-0",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.receiver",
                payloadUtf8 = """{"type":"LAUNCH_ERROR","reason":"NOT_ALLOWED"}""",
            ),
        )
        assertTrue(readyErr.isCompleted)
        assertTrue(readyErr.isCancelled || readyErr.getCompletionExceptionOrNull() != null)
        assertTrue(readyErr.getCompletionExceptionOrNull()?.message?.contains("NOT_ALLOWED") == true)
    }

    @Test
    fun repliesPongToPingSourceId() = runBlocking {
        val client = GoogleCastClient()
        val out = ByteArrayOutputStream()
        client.installTestTransportForUnitTest(out)

        client.handleIncomingMessage(
            CastWireMessage(
                sourceId = "transport-xyz",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.tp.heartbeat",
                payloadUtf8 = """{"type":"PING"}""",
            ),
        )

        val sent = decodeFramedMessages(out.toByteArray())
        assertEquals(1, sent.size)
        assertEquals("transport-xyz", sent.first().destinationId)
        assertEquals("urn:x-cast:com.google.cast.tp.heartbeat", sent.first().namespace)
        val obj = json.parseToJsonElement(sent.first().payloadUtf8!!) as JsonObject
        assertEquals("PONG", obj["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun buildLoadPayloadUses1000OffsetRespectsInitialSubtitleAndIncludesTextTrackStyle() {
        val client = GoogleCastClient()
        val subs = listOf(
            CastSubtitleTrack(0, "Español", "es", "http://192.168.1.5:8080/0.vtt", "http://192.168.1.5:8080/0.srt"),
            CastSubtitleTrack(2, "English", "en", "http://192.168.1.5:8080/2.vtt", "http://192.168.1.5:8080/2.srt"),
        )
        val style = SubtitleStyleConfig(
            fontSizeSp = 28.5f,
            backgroundOpacity = 0.5f,
            edgeStyle = SubtitleEdgeStyle.Outline,
        )

        val withInitial = client.buildLoadPayload(
            mediaUrl = "http://192.168.1.5:8080/video.mp4",
            mimeType = "video/mp4",
            title = "Movie",
            positionMs = 15_000L,
            subtitles = subs,
            initialSubtitleIndex = 2,
            subtitleStyle = style,
        )
        val activeIds = (withInitial["activeTrackIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
        assertEquals(listOf(1002), activeIds)

        val mediaObj = withInitial["media"] as JsonObject
        val tracks = (mediaObj["tracks"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf(1000, 1002), tracks.map { it["trackId"]?.jsonPrimitive?.intOrNull })

        val styleObj = mediaObj["textTrackStyle"] as JsonObject
        assertEquals(1.5, styleObj["fontScale"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.01)
        assertEquals("#FFFFFFFF", styleObj["foregroundColor"]?.jsonPrimitive?.contentOrNull)
        assertEquals("#00000080", styleObj["backgroundColor"]?.jsonPrimitive?.contentOrNull)
        assertEquals("OUTLINE", styleObj["edgeType"]?.jsonPrimitive?.contentOrNull)

        // Null or missing initialSubtitleIndex omits activeTrackIds
        val withoutInitial = client.buildLoadPayload(
            mediaUrl = "http://192.168.1.5:8080/video.mp4",
            mimeType = "video/mp4",
            title = "Movie",
            positionMs = 0L,
            subtitles = subs,
            initialSubtitleIndex = 99,
            subtitleStyle = SubtitleStyleConfig(edgeStyle = SubtitleEdgeStyle.None),
        )
        assertNull(withoutInitial["activeTrackIds"])
        val noneStyleObj = (withoutInitial["media"] as JsonObject)["textTrackStyle"] as JsonObject
        assertEquals("NONE", noneStyleObj["edgeType"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun handlesMediaStatusWithoutMediaWithJsonNullAndFlushesPendingTrackUpdates() = runBlocking {
        val client = GoogleCastClient()
        val out = ByteArrayOutputStream()
        val ready = CompletableDeferred<String>()
        val updates = mutableListOf<CastRemoteUpdate>()
        client.installTestTransportForUnitTest(out, ready) { updates += it }

        // Establish transportId
        client.handleIncomingMessage(
            CastWireMessage(
                sourceId = "receiver-0",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.receiver",
                payloadUtf8 = """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC1AD845","transportId":"app-1"}]}}""",
            ),
        )

        // User selects subtitle and updates style BEFORE mediaSessionId arrives
        client.selectSubtitleIndex(2)
        client.updateSubtitleStyle(
            SubtitleStyleConfig(fontSizeSp = 19f, backgroundOpacity = 0.0f, edgeStyle = SubtitleEdgeStyle.DropShadow),
        )
        // Only CONNECT should have been written so far
        assertEquals(1, decodeFramedMessages(out.toByteArray()).size)

        // MEDIA_STATUS arrives with JsonNull fields and missing media object
        client.handleIncomingMessage(
            CastWireMessage(
                sourceId = "app-1",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.media",
                payloadUtf8 = """
                    {
                      "type": "MEDIA_STATUS",
                      "status": [
                        {
                          "mediaSessionId": 77,
                          "playerState": "BUFFERING",
                          "idleReason": null,
                          "currentTime": 12.5,
                          "media": null,
                          "activeTrackIds": []
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(77, client.currentMediaSessionIdForUnitTest())
        val messages = decodeFramedMessages(out.toByteArray())
        assertEquals(2, messages.size)
        val editObj = json.parseToJsonElement(messages[1].payloadUtf8!!) as JsonObject
        assertEquals("EDIT_TRACKS_INFO", editObj["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(77, editObj["mediaSessionId"]?.jsonPrimitive?.intOrNull)
        val sentActiveIds = (editObj["activeTrackIds"] as JsonArray).map { it.jsonPrimitive.intOrNull }
        assertEquals(listOf(1002), sentActiveIds)
        val editStyle = editObj["textTrackStyle"] as JsonObject
        assertEquals("DROP_SHADOW", editStyle["edgeType"]?.jsonPrimitive?.contentOrNull)
        assertEquals("#00000000", editStyle["backgroundColor"]?.jsonPrimitive?.contentOrNull)

        // Subsequent MEDIA_STATUS with media.tracks (TEXT + AUDIO) and activeTrackIds
        client.handleIncomingMessage(
            CastWireMessage(
                sourceId = "app-1",
                destinationId = "sender-0",
                namespace = "urn:x-cast:com.google.cast.media",
                payloadUtf8 = """
                    {
                      "type": "MEDIA_STATUS",
                      "status": [
                        {
                          "mediaSessionId": 77,
                          "playerState": "PLAYING",
                          "currentTime": 20.0,
                          "activeTrackIds": [2, 1002],
                          "media": {
                            "duration": 120.0,
                            "tracks": [
                              {"trackId": 1, "type": "AUDIO", "name": "English", "language": "en"},
                              {"trackId": 2, "type": "AUDIO", "name": "Español", "language": "es"},
                              {"trackId": 1002, "type": "TEXT", "name": "English", "language": "en"}
                            ]
                          }
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val last = updates.last()
        assertEquals(CastPlaybackStatus.Playing, last.status)
        assertEquals(20_000L, last.positionMs)
        assertEquals(120_000L, last.durationMs)
        assertTrue(last.hasSubtitleOverride)
        assertEquals(2, last.subtitleOverrideIndex)
        assertTrue(last.hasAudioOverride)
        assertEquals(1, last.audioOverrideIndex)
        assertEquals(2, last.audioTracksUpdate?.size)
        assertEquals("Español", last.audioTracksUpdate?.get(1)?.name)
    }
}
