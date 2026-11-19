package org.cinito.online.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.cinito.online.settings.SubtitleEdgeStyle
import org.cinito.online.settings.SubtitleStyleConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import webtor.app.PlaybackItem
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList

class CastMediaServerTest {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var upstream: MockWebServer
    private lateinit var server: CastMediaServer

    @Before
    fun setUp() {
        upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/video.bin" -> {
                        val range = request.getHeader("Range")
                        when {
                            request.method == "HEAD" -> MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "video/x-matroska")
                                .setHeader("Content-Length", "10")
                            range == "bytes=0-4" -> MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Type", "video/x-matroska")
                                .setHeader("Content-Range", "bytes 0-4/10")
                                .setBody("01234")
                            else -> MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "video/x-matroska")
                                .setBody("0123456789")
                        }
                    }
                    "/es.srt" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("1\n00:00:01,000 --> 00:00:03,000\nHola LG TV\n")
                    "/win1252.ass" -> {
                        val assText = """
                            [Script Info]
                            ScriptType: v4.00+

                            [Events]
                            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,¡Hola niño, acción!
                        """.trimIndent()
                        val bytes = assText.toByteArray(Charset.forName("windows-1252"))
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(okio.Buffer().write(bytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()

        server = CastMediaServer(
            context = null,
            assetReader = { path ->
                when (path) {
                    "webos_receiver/index.html" -> "<html><body>CiniTo TV</body></html>".toByteArray()
                    "webos_receiver/styles.css" -> "body{color:#fff}".toByteArray()
                    "webos_receiver/player.js" -> "console.log('ready');".toByteArray()
                    else -> null
                }
            },
            hostIpProvider = { "127.0.0.1" },
        )
    }

    @After
    fun tearDown() {
        server.stop()
        upstream.shutdown()
    }

    @Test
    fun servesSessionCommandsAssetsSubtitlesAndRejectsInvalidTokens() {
        val published = server.publish(
            sourceUri = upstream.url("/video.bin").toString(),
            mimeType = "video/x-matroska",
            title = "Coco.2017.1080p.mkv",
            positionMs = 45_000L,
            subtitles = listOf(PlaybackItem(upstream.url("/es.srt").toString(), "Coco.es.srt")),
            activeSubtitleIndex = 0,
            subtitleStyle = SubtitleStyleConfig(fontSizeSp = 22f, backgroundOpacity = 0.6f),
        )
        assertNotNull(published)
        published!!
        assertTrue(published.mediaUrl.endsWith("/media.mkv"))

        // Wrong token must be denied
        val badTokenUrl = published.sessionUrl.replace(
            published.baseUrl,
            published.baseUrl.substringBeforeLast('/') + "/deadbeef",
        )
        http.newCall(Request.Builder().url(badTokenUrl).build()).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        // OPTIONS preflight must include CORS expose headers
        val preflightReq = Request.Builder().url(published.mediaUrl).method("OPTIONS", null).build()
        http.newCall(preflightReq).execute().use { resp ->
            assertEquals(204, resp.code)
            assertEquals("*", resp.header("Access-Control-Allow-Origin"))
            assertTrue(resp.header("Access-Control-Expose-Headers").orEmpty().contains("Content-Range"))
        }

        // Session JSON should expose active subtitle & style, then explicit JSON null after disable
        server.enqueueTvCommand("SEEK", 120_000L)
        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("*", resp.header("Access-Control-Allow-Origin"))
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals(published.mediaUrl, obj["mediaUrl"]?.jsonPrimitive?.content)
            assertEquals(0, obj["activeSubtitleIndex"]?.jsonPrimitive?.intOrNull)
            assertEquals("SEEK", obj["command"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals(120_000L, obj["command"]?.jsonObject?.get("positionMs")?.jsonPrimitive?.longOrNull)
            assertEquals(1, obj["subtitles"]?.jsonArray?.size)
        }

        server.updateSubtitleSelection(null)
        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertTrue(obj["activeSubtitleIndex"] is JsonNull)
        }

        // Shared HTML5 receiver routes serve cleanly
        http.newCall(Request.Builder().url(published.watchUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("CiniTo TV"))
        }
        http.newCall(Request.Builder().url("${published.baseUrl}/player.js").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("ready"))
        }

        // Re-select subtitle 0, then verify media range relay + LG/Samsung subtitle headers + VTT conversion
        server.updateSubtitleSelection(0)
        val rangeReq = Request.Builder().url(published.mediaUrl).header("Range", "bytes=0-4").build()
        http.newCall(rangeReq).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals("01234", resp.body!!.string())
            assertEquals(published.subtitles.first().srtUrl, resp.header("CaptionInfo.sec"))
            assertEquals(published.subtitles.first().srtUrl, resp.header("PV-SubtitleLocation"))
        }
        http.newCall(Request.Builder().url(published.subtitles.first().vttUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val vtt = resp.body!!.string()
            assertTrue(vtt.startsWith("WEBVTT"))
            assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.000"))
        }
    }

    @Test
    fun receivesTvReportCallbacksIncludingPlaybackEnded() {
        val reports = CopyOnWriteArrayList<TvBridgeReport>()
        server.setTvReportListener { reports.add(it) }

        val published = server.publish(
            sourceUri = upstream.url("/video.bin").toString(),
            mimeType = "video/mp4",
            title = "S01E01.mp4",
            subtitles = emptyList(),
        )!!

        val reportUrl = "${published.baseUrl}/tv/report"
        val endedBody = """{"mediaId":1,"status":"ENDED","positionMs":2400000,"durationMs":2400000}"""
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(reportUrl).post(endedBody).build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        assertEquals(1, reports.size)
        val report = reports.first()
        assertEquals(CastPlaybackStatus.Idle, report.status)
        assertTrue(report.ended)
        assertEquals(2_400_000L, report.positionMs)
    }

    @Test
    fun ignoresStaleMediaIdReportsUpdatesDlnaSubtitleHeadersAndHonorsTokenScopedStop() {
        val reports = CopyOnWriteArrayList<TvBridgeReport>()
        val published = server.publish(
            sourceUri = upstream.url("/video.bin").toString(),
            mimeType = "video/x-matroska",
            title = "Series.S01E02.mkv",
            subtitles = listOf(
                PlaybackItem(upstream.url("/es.srt").toString(), "es.srt"),
                PlaybackItem(upstream.url("/es.srt").toString(), "en.srt"),
            ),
            activeSubtitleIndex = 1,
        )!!
        server.setTvReportListener(published.mediaId) { reports.add(it) }

        // Subtitle header tracks activeSubtitleIndex = 1, then vanishes when null
        http.newCall(Request.Builder().url(published.mediaUrl).head().build()).execute().use { resp ->
            assertEquals(published.subtitles[1].srtUrl, resp.header("CaptionInfo.sec"))
            assertEquals(published.subtitles[1].srtUrl, resp.header("PV-SubtitleLocation"))
        }
        server.updateSubtitleSelection(null)
        http.newCall(Request.Builder().url(published.mediaUrl).head().build()).execute().use { resp ->
            assertEquals(null, resp.header("CaptionInfo.sec"))
            assertEquals(null, resp.header("PV-SubtitleLocation"))
        }

        // Stale mediaId report must be ignored
        val reportUrl = "${published.baseUrl}/tv/report"
        val staleBody = """{"mediaId":${published.mediaId - 1},"status":"ENDED","positionMs":999,"durationMs":999}"""
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(reportUrl).post(staleBody).build()).execute().close()
        assertEquals(0, reports.size)

        // Matching mediaId with TV-side subtitle selection change updates snapshot & listener
        val tvSubBody = """{"mediaId":${published.mediaId},"status":"PLAYING","positionMs":5000,"durationMs":10000,"subtitleSelectionChanged":true,"activeSubtitleIndex":1}"""
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(reportUrl).post(tvSubBody).build()).execute().close()
        assertEquals(1, reports.size)
        assertTrue(reports.first().hasSubtitleOverride)
        assertEquals(1, reports.first().subtitleOverrideIndex)

        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals(1, obj["activeSubtitleIndex"]?.jsonPrimitive?.intOrNull)
        }

        // Stop with wrong token leaves server alive; matching token stops cleanly
        val currentTok = server.currentSessionToken()
        server.stop(expectedTokenHex = "wrong-token")
        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        server.stop(expectedTokenHex = currentTok)
    }

    @Test
    fun preparesIdleSessionAndPublishesAudioTracksAndSelectAudioCommand() {
        val reports = CopyOnWriteArrayList<TvBridgeReport>()
        val idle = server.prepareIdle(deviceName = "LG OLED C3")
        assertNotNull(idle)
        idle!!

        http.newCall(Request.Builder().url(idle.sessionUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals("", obj["mediaUrl"]?.jsonPrimitive?.content)
            assertEquals(true, obj["idle"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("LG OLED C3", obj["deviceName"]?.jsonPrimitive?.content)
        }

        val published = server.publish(
            sourceUri = upstream.url("/video.bin").toString(),
            mimeType = "video/x-matroska",
            title = "Dune.2024.Dual.mkv",
            subtitles = emptyList(),
            audioTracks = listOf(
                CastAudioTrack(0, "Español Latino · EAC3", "es-419", "EAC3"),
                CastAudioTrack(1, "English · TRUEHD", "en", "TRUEHD"),
            ),
            activeAudioIndex = 0,
        )!!
        server.setTvReportListener(published.mediaId) { reports.add(it) }
        server.updateAudioSelection(1)
        server.enqueueTvCommand("SELECT_AUDIO", audioIndex = 1)

        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals(1, obj["activeAudioIndex"]?.jsonPrimitive?.intOrNull)
            assertEquals(2, obj["audioTracks"]?.jsonArray?.size)
            val cmd = obj["command"]?.jsonObject
            assertEquals("SELECT_AUDIO", cmd?.get("type")?.jsonPrimitive?.content)
            assertEquals(1, cmd?.get("audioIndex")?.jsonPrimitive?.intOrNull)
        }

        val audioReport = """{"mediaId":${published.mediaId},"status":"PLAYING","positionMs":9000,"durationMs":120000,"audioSelectionChanged":true,"activeAudioIndex":1}"""
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${published.baseUrl}/tv/report").post(audioReport).build()).execute().close()
        assertEquals(1, reports.size)
        assertTrue(reports.first().hasAudioOverride)
        assertEquals(1, reports.first().audioOverrideIndex)

        server.clearMediaKeepSession("LG OLED C3")
        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals("", obj["mediaUrl"]?.jsonPrimitive?.content)
            assertEquals(true, obj["idle"]?.jsonPrimitive?.content?.toBoolean())
        }
    }

    @Test
    fun enqueuesFifoCommandsBoundedTo16AndClearsConfirmedByAfterSeqOrAckCmdSeq() {
        val reports = CopyOnWriteArrayList<TvBridgeReport>()
        val published = server.publish(
            sourceUri = upstream.url("/video.bin").toString(),
            mimeType = "video/x-matroska",
            title = "Commands.mkv",
            subtitles = emptyList(),
            audioTracks = listOf(
                CastAudioTrack(0, "Español", "es"),
                CastAudioTrack(1, "English", "en"),
            ),
        )!!
        server.setTvReportListener(published.mediaId) { reports.add(it) }

        repeat(20) { idx ->
            server.enqueueTvCommand("SEEK", positionMs = (idx + 1) * 1_000L)
        }

        val seqs: List<Long>
        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val cmds = obj["commands"]?.jsonArray!!
            assertEquals(16, cmds.size)
            seqs = cmds.map { it.jsonObject["seq"]?.jsonPrimitive?.longOrNull!! }
            assertEquals(seqs.sorted(), seqs)
            assertEquals(5_000L, cmds.first().jsonObject["positionMs"]?.jsonPrimitive?.longOrNull)
            assertEquals(20_000L, cmds.last().jsonObject["positionMs"]?.jsonPrimitive?.longOrNull)
            assertEquals(seqs.last(), obj["command"]?.jsonObject?.get("seq")?.jsonPrimitive?.longOrNull)
        }

        // ?afterSeq clears all commands <= N atomically
        val cutoffSeq = seqs[13]
        http.newCall(Request.Builder().url("${published.sessionUrl}?afterSeq=$cutoffSeq").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val cmds = obj["commands"]?.jsonArray!!
            assertEquals(2, cmds.size)
            assertEquals(seqs[14], cmds[0].jsonObject["seq"]?.jsonPrimitive?.longOrNull)
            assertEquals(seqs[15], cmds[1].jsonObject["seq"]?.jsonPrimitive?.longOrNull)
        }

        // ackCmdSeq in /tv/report clears remaining commands, and audioSwitchingSupported=false sets capability without empty tracks
        val reportBody = """
            {
              "mediaId": ${published.mediaId},
              "status": "PLAYING",
              "positionMs": 5000,
              "durationMs": 60000,
              "ackCmdSeq": ${seqs.last()},
              "audioSwitchingSupported": false,
              "audioTracks": []
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${published.baseUrl}/tv/report").post(reportBody).build()).execute().close()

        assertEquals(1, reports.size)
        assertNull(reports.first().reportedAudioTracks)
        assertEquals(CastAudioControlCapability.AssistedRemoteMenu, reports.first().audioCapabilityOverride)

        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals(0, obj["commands"]?.jsonArray?.size)
            assertFalse("command" in obj)
        }
    }

    @Test
    fun addsLocalSubtitleDynamicallyConvertsWindows1252AssAndReturnsCors404OnMissing() {
        val published = server.publish(
            sourceUri = upstream.url("/video.bin").toString(),
            mimeType = "video/x-matroska",
            title = "Anime.mkv",
            subtitles = emptyList(),
            subtitleStyle = SubtitleStyleConfig(
                fontSizeSp = 22f,
                backgroundOpacity = 0.5f,
                edgeStyle = SubtitleEdgeStyle.Outline,
            ),
        )!!
        val originalToken = server.currentSessionToken()

        http.newCall(Request.Builder().url(published.sessionUrl).build()).execute().use { resp ->
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals(
                "Outline",
                obj["subtitleStyle"]?.jsonObject?.get("edgeStyle")?.jsonPrimitive?.content,
            )
        }

        val addedAss = server.addLocalSubtitle(
            PlaybackItem(upstream.url("/win1252.ass").toString(), "Anime.es.ass"),
        )
        assertNotNull(addedAss)
        addedAss!!
        assertEquals(0, addedAss.index)
        assertEquals("es", addedAss.language)
        assertEquals(originalToken, server.currentSessionToken())

        http.newCall(Request.Builder().url(addedAss.vttUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("*", resp.header("Access-Control-Allow-Origin"))
            val vtt = resp.body!!.string()
            assertTrue(vtt.startsWith("WEBVTT"))
            assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.500"))
            assertTrue(vtt.contains("¡Hola niño, acción!"))
        }

        http.newCall(Request.Builder().url(addedAss.srtUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("*", resp.header("Access-Control-Allow-Origin"))
            val srt = resp.body!!.string()
            assertTrue(srt.contains("00:00:01,000 --> 00:00:03,500"))
            assertTrue(srt.contains("¡Hola niño, acción!"))
        }

        val missingSub = server.addLocalSubtitle(
            PlaybackItem(upstream.url("/does-not-exist.srt").toString(), "Missing.srt"),
        )!!
        http.newCall(Request.Builder().url(missingSub.vttUrl).build()).execute().use { resp ->
            assertEquals(404, resp.code)
            assertEquals("*", resp.header("Access-Control-Allow-Origin"))
        }
    }
}
