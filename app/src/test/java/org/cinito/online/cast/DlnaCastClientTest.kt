package org.cinito.online.cast

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DlnaCastClientTest {
    private data class RecordedSoap(
        val path: String,
        val soapActionHeader: String,
        val action: String,
        val body: String,
    )

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun testDevice(includeRenderingControl: Boolean = true): CastDevice = CastDevice(
        id = "dlna:test-lg",
        name = "LG OLED Sala",
        model = "OLED55C3",
        host = "127.0.0.1",
        port = server.port,
        protocol = CastProtocol.Dlna,
        vendor = CastVendor.LG,
        avTransportControlUrl = server.url("/upnp/control/AVTransport1").toString(),
        renderingControlUrl = if (includeRenderingControl) {
            server.url("/upnp/control/RenderingControl1").toString()
        } else {
            null
        },
    )

    private suspend fun awaitCondition(timeoutMs: Long = 8_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(50L)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    private fun soapOk(action: String, service: String, innerXml: String = ""): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                  <s:Body>
                    <u:${action}Response xmlns:u="$service">
                      $innerXml
                    </u:${action}Response>
                  </s:Body>
                </s:Envelope>
                """.trimIndent(),
            )

    private fun upnpFault(errorCode: Int, errorDescription: String): MockResponse =
        MockResponse()
            .setResponseCode(500)
            .setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                  <s:Body>
                    <s:Fault>
                      <faultcode>s:Client</faultcode>
                      <faultstring>UPnPError</faultstring>
                      <detail>
                        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                          <errorCode>$errorCode</errorCode>
                          <errorDescription>$errorDescription</errorDescription>
                        </UPnPError>
                      </detail>
                    </s:Fault>
                  </s:Body>
                </s:Envelope>
                """.trimIndent(),
            )

    private fun defaultPollResponse(action: String, currentSubtitle: String = "OFF"): MockResponse? =
        when (action) {
            "GetPositionInfo" -> soapOk(
                action = "GetPositionInfo",
                service = NS_AV_TRANSPORT,
                innerXml = "<RelTime>00:01:30</RelTime><TrackDuration>01:00:00</TrackDuration>",
            )
            "GetTransportInfo" -> soapOk(
                action = "GetTransportInfo",
                service = NS_AV_TRANSPORT,
                innerXml = "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>",
            )
            "GetVolume" -> soapOk(
                action = "GetVolume",
                service = NS_RENDERING_CONTROL,
                innerXml = "<CurrentVolume>40</CurrentVolume>",
            )
            "X_GetSubtitle" -> soapOk(
                action = "X_GetSubtitle",
                service = NS_RENDERING_CONTROL,
                innerXml = "<CurrentSubtitle>$currentSubtitle</CurrentSubtitle>",
            )
            else -> null
        }

    @Test
    fun connectAndLoadSendsStopSetAvTransportUriAndPlayWith701Retries() = runBlocking {
        val recorded = CopyOnWriteArrayList<RecordedSoap>()
        val setUriAttempts = AtomicInteger(0)
        val playAttempts = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val soapHeader = request.getHeader("SOAPACTION").orEmpty()
                val action = soapHeader.substringAfter('#').trim('"')
                val body = request.body.readUtf8()
                recorded += RecordedSoap(request.path.orEmpty(), soapHeader, action, body)
                return when (action) {
                    "Stop" -> soapOk("Stop", NS_AV_TRANSPORT)
                    "SetAVTransportURI" -> {
                        if (setUriAttempts.incrementAndGet() == 1) {
                            upnpFault(701, "Transition not available")
                        } else {
                            soapOk("SetAVTransportURI", NS_AV_TRANSPORT)
                        }
                    }
                    "Play" -> {
                        if (playAttempts.incrementAndGet() == 1) {
                            upnpFault(701, "Transition not available")
                        } else {
                            soapOk("Play", NS_AV_TRANSPORT)
                        }
                    }
                    else -> defaultPollResponse(action) ?: soapOk(action, NS_AV_TRANSPORT)
                }
            }
        }

        val updates = CopyOnWriteArrayList<CastRemoteUpdate>()
        val client = DlnaCastClient(allowLoopbackForTest = true)
        try {
            client.connectAndLoad(
                device = testDevice(includeRenderingControl = true),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = emptyList(),
                onUpdate = { updates += it },
            )

            val setupActions = recorded
                .map { it.action }
                .filter { it in setOf("Stop", "SetAVTransportURI", "Play") }
            assertEquals(
                listOf("Stop", "SetAVTransportURI", "Stop", "SetAVTransportURI", "Play", "Play"),
                setupActions,
            )
            val setUriReq = recorded.first { it.action == "SetAVTransportURI" }
            assertTrue(setUriReq.body.contains("<CurrentURI>http://192.168.1.50:8080/movie.mkv</CurrentURI>"))
            assertTrue(updates.any { it.subtitleCapabilityOverride == CastSubtitleControlCapability.DlnaNativeToggle })
        } finally {
            client.close()
        }
    }

    @Test
    fun setNativeSubtitlesEnabledSendsXSetSubtitleAndEmitsUpdate() = runBlocking {
        val recorded = CopyOnWriteArrayList<RecordedSoap>()
        val rejectXSetWith401 = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val soapHeader = request.getHeader("SOAPACTION").orEmpty()
                val action = soapHeader.substringAfter('#').trim('"')
                val body = request.body.readUtf8()
                recorded += RecordedSoap(request.path.orEmpty(), soapHeader, action, body)
                return when (action) {
                    "Stop", "SetAVTransportURI", "Play" -> soapOk(action, NS_AV_TRANSPORT)
                    "X_SetSubtitle" -> {
                        if (rejectXSetWith401.get() == 1) {
                            upnpFault(401, "Invalid Action")
                        } else {
                            soapOk("X_SetSubtitle", NS_RENDERING_CONTROL)
                        }
                    }
                    else -> defaultPollResponse(action) ?: soapOk(action, NS_RENDERING_CONTROL)
                }
            }
        }

        val updates = CopyOnWriteArrayList<CastRemoteUpdate>()
        val client = DlnaCastClient(allowLoopbackForTest = true)
        try {
            client.connectAndLoad(
                device = testDevice(includeRenderingControl = true),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = emptyList(),
                onUpdate = { updates += it },
            )

            assertTrue(client.setNativeSubtitlesEnabled(true))
            val onReq = recorded.last { it.action == "X_SetSubtitle" }
            assertEquals("\"$NS_RENDERING_CONTROL#X_SetSubtitle\"", onReq.soapActionHeader)
            assertTrue(onReq.body.contains("<InstanceID>0</InstanceID><DesiredSubtitle>ON</DesiredSubtitle>"))
            assertTrue(updates.any { it.subtitleEnabledOnTv == true })

            assertTrue(client.setNativeSubtitlesEnabled(false))
            val offReq = recorded.last { it.action == "X_SetSubtitle" }
            assertEquals("\"$NS_RENDERING_CONTROL#X_SetSubtitle\"", offReq.soapActionHeader)
            assertTrue(offReq.body.contains("<InstanceID>0</InstanceID><DesiredSubtitle>OFF</DesiredSubtitle>"))
            assertTrue(updates.any { it.subtitleEnabledOnTv == false })

            // SOAP 401/501 disables supportsNativeSubtitleToggle and avoids further requests
            rejectXSetWith401.set(1)
            val countBefore401 = recorded.count { it.action == "X_SetSubtitle" }
            assertFalse(client.setNativeSubtitlesEnabled(true))
            assertEquals(countBefore401 + 1, recorded.count { it.action == "X_SetSubtitle" })
            assertTrue(updates.any { it.subtitleCapabilityOverride == CastSubtitleControlCapability.Unsupported })

            assertFalse(client.setNativeSubtitlesEnabled(false))
            assertEquals(countBefore401 + 1, recorded.count { it.action == "X_SetSubtitle" })
        } finally {
            client.close()
        }

        // Without renderingControlUrl, setNativeSubtitlesEnabled immediately returns false
        val clientNoRc = DlnaCastClient(allowLoopbackForTest = true)
        try {
            val noRcUpdates = CopyOnWriteArrayList<CastRemoteUpdate>()
            clientNoRc.connectAndLoad(
                device = testDevice(includeRenderingControl = false),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = emptyList(),
                onUpdate = { noRcUpdates += it },
            )
            assertFalse(clientNoRc.setNativeSubtitlesEnabled(true))
            assertTrue(noRcUpdates.any { it.subtitleCapabilityOverride == CastSubtitleControlCapability.Unsupported })
        } finally {
            clientNoRc.close()
        }
    }

    @Test
    fun startPollingQueriesXGetSubtitleParsesOnOffAndDisablesOn401() = runBlocking {
        // Part 1: X_GetSubtitle returns ON on first poll cycle, then OFF on next 2-cycle poll
        val getSubCalls = AtomicInteger(0)
        val recordedOnOff = CopyOnWriteArrayList<RecordedSoap>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val soapHeader = request.getHeader("SOAPACTION").orEmpty()
                val action = soapHeader.substringAfter('#').trim('"')
                val body = request.body.readUtf8()
                recordedOnOff += RecordedSoap(request.path.orEmpty(), soapHeader, action, body)
                return when (action) {
                    "Stop", "SetAVTransportURI", "Play" -> soapOk(action, NS_AV_TRANSPORT)
                    "X_GetSubtitle" -> {
                        val state = if (getSubCalls.incrementAndGet() == 1) "ON" else "OFF"
                        defaultPollResponse(action, currentSubtitle = state)!!
                    }
                    else -> defaultPollResponse(action) ?: soapOk(action, NS_AV_TRANSPORT)
                }
            }
        }

        val updatesOnOff = CopyOnWriteArrayList<CastRemoteUpdate>()
        val clientOnOff = DlnaCastClient(allowLoopbackForTest = true)
        try {
            clientOnOff.connectAndLoad(
                device = testDevice(includeRenderingControl = true),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = emptyList(),
                onUpdate = { updatesOnOff += it },
            )
            awaitCondition(timeoutMs = 8_000L) {
                val subStates = updatesOnOff.mapNotNull { it.subtitleEnabledOnTv }
                true in subStates && false in subStates
            }
            val subReq = recordedOnOff.first { it.action == "X_GetSubtitle" }
            assertEquals("\"$NS_RENDERING_CONTROL#X_GetSubtitle\"", subReq.soapActionHeader)
            assertTrue(subReq.body.contains("<InstanceID>0</InstanceID>"))
        } finally {
            clientOnOff.close()
        }

        // Part 2: X_GetSubtitle returns 401 Invalid Action -> disables polling X_GetSubtitle on subsequent cycles
        val getVolumeCalls = AtomicInteger(0)
        val getSub401Calls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.getHeader("SOAPACTION").orEmpty().substringAfter('#').trim('"')
                request.body.readUtf8()
                return when (action) {
                    "Stop", "SetAVTransportURI", "Play" -> soapOk(action, NS_AV_TRANSPORT)
                    "GetVolume" -> {
                        getVolumeCalls.incrementAndGet()
                        defaultPollResponse(action)!!
                    }
                    "X_GetSubtitle" -> {
                        getSub401Calls.incrementAndGet()
                        upnpFault(401, "Invalid Action")
                    }
                    else -> defaultPollResponse(action) ?: soapOk(action, NS_AV_TRANSPORT)
                }
            }
        }

        val updates401 = CopyOnWriteArrayList<CastRemoteUpdate>()
        val client401 = DlnaCastClient(allowLoopbackForTest = true)
        try {
            client401.connectAndLoad(
                device = testDevice(includeRenderingControl = true),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = emptyList(),
                onUpdate = { updates401 += it },
            )
            awaitCondition(timeoutMs = 8_000L) { getVolumeCalls.get() >= 2 }
            assertEquals(1, getSub401Calls.get())
            assertTrue(updates401.any { it.subtitleCapabilityOverride == CastSubtitleControlCapability.Unsupported })
        } finally {
            client401.close()
        }
    }

    @Test
    fun selectSubtitleIndexNullInDlnaNativeToggleUsesXSetSubtitleOffWithoutRestartingVideo() = runBlocking {
        val recorded = CopyOnWriteArrayList<RecordedSoap>()
        val setUriCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val soapHeader = request.getHeader("SOAPACTION").orEmpty()
                val action = soapHeader.substringAfter('#').trim('"')
                val body = request.body.readUtf8()
                recorded += RecordedSoap(request.path.orEmpty(), soapHeader, action, body)
                if (action == "SetAVTransportURI") {
                    setUriCount.incrementAndGet()
                }
                return when (action) {
                    "Stop", "SetAVTransportURI", "Play" -> soapOk(action, NS_AV_TRANSPORT)
                    "X_SetSubtitle" -> soapOk("X_SetSubtitle", NS_RENDERING_CONTROL)
                    else -> defaultPollResponse(action, currentSubtitle = "ON") ?: soapOk(action, NS_AV_TRANSPORT)
                }
            }
        }

        val updates = CopyOnWriteArrayList<CastRemoteUpdate>()
        val client = DlnaCastClient(allowLoopbackForTest = true)
        try {
            client.connectAndLoad(
                device = testDevice(includeRenderingControl = true),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = emptyList(),
                initialSubtitleIndex = 0,
                onUpdate = { updates += it },
            )

            // Wait until first PLAYING poll applies pendingInitialNativeSub -> X_SetSubtitle(ON)
            awaitCondition(timeoutMs = 5_000L) {
                recorded.any { it.action == "X_SetSubtitle" && it.body.contains("<DesiredSubtitle>ON</DesiredSubtitle>") }
            }
            assertEquals(1, setUriCount.get())

            client.selectSubtitleIndex(null)

            assertEquals(1, setUriCount.get())
            val lastSetSub = recorded.last { it.action == "X_SetSubtitle" }
            assertTrue(lastSetSub.body.contains("<DesiredSubtitle>OFF</DesiredSubtitle>"))
            assertEquals(false, updates.lastOrNull { it.subtitleEnabledOnTv != null }?.subtitleEnabledOnTv)
        } finally {
            client.close()
        }
    }

    @Test
    fun selectSubtitleIndexInMixedModeUsesXSetSubtitleOffWithoutReloadAndReloadsOnTrackSelect() = runBlocking {
        val recorded = CopyOnWriteArrayList<RecordedSoap>()
        val setUriCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val soapHeader = request.getHeader("SOAPACTION").orEmpty()
                val action = soapHeader.substringAfter('#').trim('"')
                val body = request.body.readUtf8()
                recorded += RecordedSoap(request.path.orEmpty(), soapHeader, action, body)
                if (action == "SetAVTransportURI") {
                    setUriCount.incrementAndGet()
                }
                return when (action) {
                    "Stop", "SetAVTransportURI", "Play" -> soapOk(action, NS_AV_TRANSPORT)
                    "X_SetSubtitle" -> soapOk("X_SetSubtitle", NS_RENDERING_CONTROL)
                    else -> defaultPollResponse(action) ?: soapOk(action, NS_AV_TRANSPORT)
                }
            }
        }

        val subs = listOf(
            CastSubtitleTrack(
                index = 0,
                name = "Español",
                language = "es",
                vttUrl = "http://192.168.1.50:8080/sub/0.vtt",
                srtUrl = "http://192.168.1.50:8080/sub/0.srt",
            ),
        )
        val updates = CopyOnWriteArrayList<CastRemoteUpdate>()
        val client = DlnaCastClient(allowLoopbackForTest = true)
        try {
            client.connectAndLoad(
                device = testDevice(includeRenderingControl = true),
                mediaUrl = "http://192.168.1.50:8080/movie.mkv",
                mimeType = "video/x-matroska",
                title = "Movie",
                positionMs = 0L,
                subtitles = subs,
                initialSubtitleIndex = 0,
                onUpdate = { updates += it },
            )
            assertTrue(updates.any { it.subtitleCapabilityOverride == CastSubtitleControlCapability.MixedExternalAndNative })
            assertEquals(1, setUriCount.get())

            // Disabling subtitles with X_SetSubtitle supported avoids SetAVTransportURI reload
            client.selectSubtitleIndex(null)
            assertEquals(1, setUriCount.get())
            assertTrue(recorded.last { it.action == "X_SetSubtitle" }.body.contains("<DesiredSubtitle>OFF</DesiredSubtitle>"))

            // Selecting an external subtitle track reloads SetAVTransportURI and calls X_SetSubtitle(ON)
            client.selectSubtitleIndex(0)
            assertEquals(2, setUriCount.get())
            assertTrue(recorded.last { it.action == "SetAVTransportURI" }.body.contains("0.srt"))
            assertTrue(recorded.last { it.action == "X_SetSubtitle" }.body.contains("<DesiredSubtitle>ON</DesiredSubtitle>"))
        } finally {
            client.close()
        }
    }

    companion object {
        private const val NS_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val NS_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    }
}
