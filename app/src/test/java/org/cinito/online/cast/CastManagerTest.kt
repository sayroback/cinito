package org.cinito.online.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.network.DohProvider
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.settings.AppPreferences
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
import webtor.app.PlaybackLeases

@OptIn(ExperimentalCoroutinesApi::class)
class CastManagerTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSessionClient(
        override val canReuseConnection: Boolean = true,
        private val connectIdleResult: Boolean = true,
        private val emitSsapOnConnect: Boolean = true,
    ) : CastSessionClient {
        var idleConnectCount = 0
        var loadCount = 0
        var selectedAudio: Int? = null
        var lastInitialSubtitleIndex: Int? = null
        var lastSubtitleStyle: SubtitleStyleConfig? = null
        var failSubtitleSelection = false
        var sentKeys = mutableListOf<WebOsRemoteKey>()
        var stoppedCount = 0
        var closedCount = 0
        var lastCallback: ((CastRemoteUpdate) -> Unit)? = null
        var nativeSubtitlesResult = true
        var throwOnNativeSubtitles = false
        var lastNativeSubtitlesEnabled: Boolean? = null

        override suspend fun connectIdle(
            device: CastDevice,
            mediaServerBaseUrl: String?,
            onUpdate: (CastRemoteUpdate) -> Unit,
        ): Boolean {
            idleConnectCount++
            lastCallback = onUpdate
            if (!connectIdleResult) return false
            if (emitSsapOnConnect) {
                onUpdate(
                    CastRemoteUpdate(
                        status = CastPlaybackStatus.Idle,
                        ssapConnected = true,
                        hasCiniToTvApp = true,
                    ),
                )
            }
            return true
        }

        override suspend fun connectAndLoad(
            device: CastDevice,
            mediaUrl: String,
            mimeType: String,
            title: String,
            positionMs: Long,
            subtitles: List<CastSubtitleTrack>,
            audioTracks: List<CastAudioTrack>,
            initialAudioIndex: Int?,
            initialSubtitleIndex: Int?,
            subtitleStyle: SubtitleStyleConfig,
            onUpdate: (CastRemoteUpdate) -> Unit,
        ) {
            loadCount++
            lastInitialSubtitleIndex = initialSubtitleIndex
            lastSubtitleStyle = subtitleStyle
            lastCallback = onUpdate
            onUpdate(CastRemoteUpdate(status = CastPlaybackStatus.Playing, positionMs = positionMs, durationMs = 120_000L))
        }

        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun setVolume(fraction: Float) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun selectSubtitleIndex(index: Int?) {
            if (failSubtitleSelection) error("Subtitle switch failed")
        }
        override suspend fun setNativeSubtitlesEnabled(enabled: Boolean): Boolean {
            lastNativeSubtitlesEnabled = enabled
            if (throwOnNativeSubtitles) error("Native subtitle toggle failed")
            return nativeSubtitlesResult
        }
        override suspend fun selectAudioTrack(index: Int) {
            selectedAudio = index
        }
        override suspend fun sendTvRemoteKey(key: WebOsRemoteKey): Boolean {
            sentKeys += key
            return true
        }
        override suspend fun stop() {
            stoppedCount++
        }
        override fun close() {
            closedCount++
        }
    }

    @Test
    fun connectDeviceEntersIdleReusesSessionAcrossVideosAndKeepsTvOnStopPlayback() = runTest(testDispatcher) {
        val fakeClient = FakeSessionClient()
        var createdCount = 0
        val lgTv = CastDevice(
            id = "webos:192.168.1.50",
            name = "LG OLED Sala",
            model = "OLED55C3",
            host = "192.168.1.50",
            port = 3000,
            protocol = CastProtocol.WebOs,
            vendor = CastVendor.LG,
            ssapPort = 3000,
        )
        val manager = CastManager(
            context = null,
            networkClient = HardenedNetworkClient(DohProvider.System),
            playbackLeases = PlaybackLeases {},
            onProgressBookmark = { _, _, _ -> },
            hostIpProvider = { "127.0.0.1" },
            ioDispatcher = testDispatcher,
            clientFactory = { _, _, _ ->
                createdCount++
                fakeClient
            },
        )

        manager.connectDevice(lgTv)
        advanceUntilIdle()
        assertTrue(manager.session.value.isConnected)
        assertTrue(manager.session.value.isIdleOnTv)
        assertTrue(manager.session.value.hasCiniToTvApp)
        assertEquals(1, fakeClient.idleConnectCount)

        manager.castMedia(
            device = lgTv,
            media = CastMediaSource(
                title = "Dune.2024.mkv",
                sourceUri = "http://127.0.0.1:9999/media.mkv",
                mimeType = "video/x-matroska",
                positionMs = 0L,
                durationMs = 120_000L,
                subtitles = emptyList(),
                audioTracks = listOf(
                    CastAudioTrack(0, "Español Latino", "es-419", "EAC3", AudioLanguageTag.Latino),
                    CastAudioTrack(1, "English", "en", "TRUEHD", AudioLanguageTag.Other),
                ),
                initialAudioIndex = 0,
                bookmarkUri = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
            ),
        )
        advanceUntilIdle()
        assertEquals(1, createdCount) // Reused connected client!
        assertEquals(1, fakeClient.loadCount)
        assertEquals(2, manager.session.value.audioTracks.size)
        assertEquals(0, manager.session.value.activeAudioIndex)

        manager.selectAudioIndex(1)
        advanceUntilIdle()
        assertEquals(1, fakeClient.selectedAudio)
        assertEquals(1, manager.session.value.activeAudioIndex)

        manager.sendTvRemoteKey(WebOsRemoteKey.ENTER)
        advanceUntilIdle()
        assertEquals(listOf(WebOsRemoteKey.ENTER), fakeClient.sentKeys)

        manager.castMedia(
            device = lgTv,
            media = CastMediaSource(
                title = "Dune.2024.Fuente2.mkv",
                sourceUri = "http://127.0.0.1:9999/media2.mkv",
                mimeType = "video/x-matroska",
                positionMs = 0L,
                durationMs = 100_000L,
                subtitles = emptyList(),
                bookmarkUri = "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
            ),
        )
        advanceUntilIdle()
        assertEquals(1, createdCount) // Still reusing without closing!
        assertEquals(2, fakeClient.loadCount)

        manager.stopPlaybackKeepConnection()
        advanceUntilIdle()
        assertTrue(manager.session.value.isConnected)
        assertTrue(manager.session.value.isIdleOnTv)
        assertNull(manager.session.value.activeSourceUri)
        assertEquals(1, fakeClient.stoppedCount)
        assertEquals(0, fakeClient.closedCount)

        manager.disconnectDevice()
        advanceUntilIdle()
        assertFalse(manager.session.value.isConnected)
        assertEquals(1, fakeClient.closedCount)
        manager.shutdown()
    }

    @Test
    fun detectsEndOfStreamForGoogleCastAndDlna() {
        assertTrue(GoogleCastClient.isPlaybackFinished("IDLE", "FINISHED"))
        assertFalse(GoogleCastClient.isPlaybackFinished("IDLE", "INTERRUPTED"))
        assertTrue(
            DlnaCastClient.didReachNaturalEnd(
                previousState = "PLAYING",
                currentState = "STOPPED",
                lastPosMs = 1_195_000L,
                durationMs = 1_200_000L,
            ),
        )
        assertFalse(
            DlnaCastClient.didReachNaturalEnd(
                previousState = "PLAYING",
                currentState = "STOPPED",
                lastPosMs = 300_000L,
                durationMs = 1_200_000L,
            ),
        )
    }

    @Test
    fun failedConnectDeviceClearsConnectedDeviceAndClosesClient() = runTest(testDispatcher) {
        val failingClient = FakeSessionClient(connectIdleResult = false, emitSsapOnConnect = false)
        val tv = CastDevice(
            id = "webos:192.168.1.99",
            name = "LG Sin Conexion",
            model = null,
            host = "192.168.1.99",
            port = 3000,
            protocol = CastProtocol.WebOs,
            vendor = CastVendor.LG,
        )
        val manager = CastManager(
            context = null,
            networkClient = HardenedNetworkClient(DohProvider.System),
            playbackLeases = PlaybackLeases {},
            onProgressBookmark = { _, _, _ -> },
            hostIpProvider = { "127.0.0.1" },
            ioDispatcher = testDispatcher,
            clientFactory = { _, _, _ -> failingClient },
        )

        manager.connectDevice(tv)
        advanceUntilIdle()
        assertFalse(manager.session.value.isConnected)
        assertNull(manager.session.value.connectedDevice)
        assertEquals(CastPlaybackStatus.Error, manager.session.value.status)
        assertNotNull(manager.session.value.errorMessage)
        assertEquals(1, failingClient.closedCount)
        manager.shutdown()
    }

    @Test
    fun standbyPreservesVolumeUpdatesAndDeviceSwitchResetsLgFlags() = runTest(testDispatcher) {
        val lgClient = FakeSessionClient(emitSsapOnConnect = true)
        val castClient = FakeSessionClient(emitSsapOnConnect = false)
        val lgTv = CastDevice(
            id = "webos:192.168.1.50",
            name = "LG Living",
            model = null,
            host = "192.168.1.50",
            port = 3000,
            protocol = CastProtocol.WebOs,
            vendor = CastVendor.LG,
            ssapPort = 3000,
            hasCiniToTvApp = true,
        )
        val chromecast = CastDevice(
            id = "cast:192.168.1.60",
            name = "Chromecast Cuarto",
            model = null,
            host = "192.168.1.60",
            port = 8009,
            protocol = CastProtocol.GoogleCast,
            vendor = CastVendor.Google,
        )
        val manager = CastManager(
            context = null,
            networkClient = HardenedNetworkClient(DohProvider.System),
            playbackLeases = PlaybackLeases {},
            onProgressBookmark = { _, _, _ -> },
            hostIpProvider = { "127.0.0.1" },
            ioDispatcher = testDispatcher,
            clientFactory = { dev, _, _ ->
                if (dev.protocol == CastProtocol.WebOs) lgClient else castClient
            },
        )

        manager.castMedia(
            device = lgTv,
            media = CastMediaSource(
                title = "Movie.mkv",
                sourceUri = "http://127.0.0.1:9999/movie.mkv",
                mimeType = "video/x-matroska",
                positionMs = 0L,
                durationMs = 100_000L,
                subtitles = emptyList(),
                bookmarkUri = "magnet:?xt=urn:btih:3333333333333333333333333333333333333333",
            ),
        )
        advanceUntilIdle()
        lgClient.lastCallback?.invoke(CastRemoteUpdate(status = null, ssapConnected = true, hasCiniToTvApp = true))
        assertTrue(manager.session.value.hasSsapControl)
        assertTrue(manager.session.value.hasCiniToTvApp)

        manager.stopPlaybackKeepConnection()
        advanceUntilIdle()
        assertTrue(manager.session.value.isIdleOnTv)

        // Device volume/mute updates still apply in standby, while stale playback status is ignored.
        lgClient.lastCallback?.invoke(
            CastRemoteUpdate(
                status = CastPlaybackStatus.Playing,
                positionMs = 45_000L,
                volume = 0.42f,
                isMuted = true,
            ),
        )
        assertEquals(0.42f, manager.session.value.volume)
        assertTrue(manager.session.value.isMuted)
        assertEquals(CastPlaybackStatus.Idle, manager.session.value.status)
        assertEquals(0L, manager.session.value.positionMs)

        // Switching to Chromecast clears LG SSAP / CiniTo TV app capability flags.
        manager.connectDevice(chromecast)
        advanceUntilIdle()
        assertTrue(manager.session.value.isConnected)
        assertFalse(manager.session.value.hasSsapControl)
        assertFalse(manager.session.value.hasCiniToTvApp)
        assertEquals(1, lgClient.closedCount)

        // Reconnect quickly after disconnect cancels teardown without leaking old client close.
        manager.disconnectDevice()
        manager.connectDevice(lgTv)
        advanceUntilIdle()
        assertEquals(1, castClient.closedCount)
        assertTrue(manager.session.value.isConnected)
        manager.shutdown()
    }

    @Test
    fun preservesMultiAudioTracksAppliesCapabilityOverrideAllowsZeroSeekAndRevertsFailedSubtitles() = runTest(testDispatcher) {
        val fakeClient = FakeSessionClient()
        val lgTv = CastDevice(
            id = "webos:192.168.1.50",
            name = "LG OLED Sala",
            model = "OLED55C3",
            host = "192.168.1.50",
            port = 3000,
            protocol = CastProtocol.WebOs,
            vendor = CastVendor.LG,
            ssapPort = 3000,
        )
        val manager = CastManager(
            context = null,
            networkClient = HardenedNetworkClient(DohProvider.System),
            playbackLeases = PlaybackLeases {},
            onProgressBookmark = { _, _, _ -> },
            hostIpProvider = { "127.0.0.1" },
            ioDispatcher = testDispatcher,
            clientFactory = { _, _, _ -> fakeClient },
            preferencesProvider = {
                AppPreferences(preferredSubtitleLanguage = "es", subtitlesEnabledByDefault = true)
            },
        )

        val style = SubtitleStyleConfig(fontSizeSp = 24f, backgroundOpacity = 0.7f, edgeStyle = SubtitleEdgeStyle.Outline)
        manager.castMedia(
            device = lgTv,
            media = CastMediaSource(
                title = "Dune.2024.mkv",
                sourceUri = "http://127.0.0.1:9999/media.mkv",
                mimeType = "video/x-matroska",
                positionMs = 30_000L,
                durationMs = 120_000L,
                subtitles = listOf(
                    PlaybackItem("http://127.0.0.1:9999/en.srt", "Dune.en.srt"),
                    PlaybackItem("http://127.0.0.1:9999/es.srt", "Dune.es.srt"),
                ),
                audioTracks = listOf(
                    CastAudioTrack(0, "Español Latino", "es-419", "EAC3", AudioLanguageTag.Latino),
                    CastAudioTrack(1, "English", "en", "TRUEHD", AudioLanguageTag.Other),
                ),
                initialAudioIndex = 0,
                subtitleStyle = style,
                embeddedSubtitleLabels = listOf("Español Latino · SRT"),
                bookmarkUri = "magnet:?xt=urn:btih:4444444444444444444444444444444444444444",
            ),
        )
        advanceUntilIdle()

        assertEquals(1, fakeClient.lastInitialSubtitleIndex)
        assertEquals(style, fakeClient.lastSubtitleStyle)
        assertEquals(listOf("Español Latino · SRT"), manager.session.value.embeddedSubtitleLabels)

        // Empty or single-track updates must NOT wipe out probed multi-track audio list
        fakeClient.lastCallback?.invoke(
            CastRemoteUpdate(
                status = CastPlaybackStatus.Playing,
                positionMs = 32_000L,
                audioTracksUpdate = emptyList(),
            ),
        )
        assertEquals(2, manager.session.value.audioTracks.size)

        fakeClient.lastCallback?.invoke(
            CastRemoteUpdate(
                status = CastPlaybackStatus.Playing,
                positionMs = 33_000L,
                audioTracksUpdate = listOf(CastAudioTrack(0, "Pista 1", "und")),
            ),
        )
        assertEquals(2, manager.session.value.audioTracks.size)

        // Capability override disables remote audio switching in UI & manager
        fakeClient.lastCallback?.invoke(
            CastRemoteUpdate(
                status = CastPlaybackStatus.Playing,
                positionMs = 34_000L,
                audioCapabilityOverride = CastAudioControlCapability.AssistedRemoteMenu,
            ),
        )
        assertEquals(CastAudioControlCapability.AssistedRemoteMenu, manager.session.value.audioControlCapability)
        manager.selectAudioIndex(1)
        advanceUntilIdle()
        assertEquals(0, manager.session.value.activeAudioIndex)
        assertNull(fakeClient.selectedAudio)

        // Optimistic seek to 0:00 and remote positionMs == 0L updates are accepted
        manager.seekTo(0L)
        assertEquals(0L, manager.session.value.positionMs)
        advanceUntilIdle()
        fakeClient.lastCallback?.invoke(CastRemoteUpdate(status = CastPlaybackStatus.Playing, positionMs = 0L, durationMs = 120_000L))
        assertEquals(0L, manager.session.value.positionMs)

        // Dynamic local subtitle addition & rollback on subtitle failure
        manager.addLocalSubtitle(PlaybackItem("http://127.0.0.1:9999/local.es.srt", "Local.es.srt"))
        advanceUntilIdle()
        assertEquals(3, manager.session.value.subtitles.size)
        assertEquals(2, manager.session.value.activeSubtitleIndex)

        fakeClient.failSubtitleSelection = true
        manager.selectSubtitleIndex(null)
        advanceUntilIdle()
        assertEquals(2, manager.session.value.activeSubtitleIndex)
        assertNotNull(manager.session.value.errorMessage)

        manager.shutdown()
    }

    @Test
    fun setTvSubtitlesEnabledUpdatesOnSuccessAndRevertsOnFalseOrException() = runTest(testDispatcher) {
        val fakeClient = FakeSessionClient(emitSsapOnConnect = false)
        val dlnaTv = CastDevice(
            id = "dlna:192.168.1.70",
            name = "Samsung QLED",
            model = "QN55Q80",
            host = "192.168.1.70",
            port = 9197,
            protocol = CastProtocol.Dlna,
            vendor = CastVendor.Samsung,
            avTransportControlUrl = "http://192.168.1.70:9197/upnp/control/AVTransport1",
            renderingControlUrl = "http://192.168.1.70:9197/upnp/control/RenderingControl1",
        )
        val manager = CastManager(
            context = null,
            networkClient = HardenedNetworkClient(DohProvider.System),
            playbackLeases = PlaybackLeases {},
            onProgressBookmark = { _, _, _ -> },
            hostIpProvider = { "127.0.0.1" },
            ioDispatcher = testDispatcher,
            clientFactory = { _, _, _ -> fakeClient },
        )

        manager.castMedia(
            device = dlnaTv,
            media = CastMediaSource(
                title = "Movie.mkv",
                sourceUri = "http://127.0.0.1:9999/movie.mkv",
                mimeType = "video/x-matroska",
                positionMs = 0L,
                durationMs = 120_000L,
                subtitles = emptyList(),
                bookmarkUri = "magnet:?xt=urn:btih:5555555555555555555555555555555555555555",
            ),
        )
        advanceUntilIdle()

        assertEquals(CastSubtitleControlCapability.DlnaNativeToggle, manager.session.value.subtitleControlCapability)
        assertNull(manager.session.value.subtitlesEnabledOnTv)

        // Success
        fakeClient.nativeSubtitlesResult = true
        manager.setTvSubtitlesEnabled(true)
        advanceUntilIdle()
        assertEquals(true, fakeClient.lastNativeSubtitlesEnabled)
        assertEquals(true, manager.session.value.subtitlesEnabledOnTv)
        assertNull(manager.session.value.errorMessage)

        // False return reverts to previous state and sets errorMessage
        fakeClient.nativeSubtitlesResult = false
        manager.setTvSubtitlesEnabled(false)
        advanceUntilIdle()
        assertEquals(false, fakeClient.lastNativeSubtitlesEnabled)
        assertEquals(true, manager.session.value.subtitlesEnabledOnTv)
        assertNotNull(manager.session.value.errorMessage)

        // Exception reverts to previous state and sets errorMessage
        manager.clearError()
        fakeClient.throwOnNativeSubtitles = true
        manager.setTvSubtitlesEnabled(false)
        advanceUntilIdle()
        assertEquals(true, manager.session.value.subtitlesEnabledOnTv)
        assertNotNull(manager.session.value.errorMessage)

        manager.shutdown()
    }

    @Test
    fun propagatesSubtitleCapabilityOverrideAndSubtitleEnabledOnTvAndUpdatesOnLocalSubtitle() = runTest(testDispatcher) {
        val fakeClient = FakeSessionClient(emitSsapOnConnect = false)
        val lgTv = CastDevice(
            id = "webos:192.168.1.50",
            name = "LG OLED Sala",
            model = "OLED55C3",
            host = "192.168.1.50",
            port = 3000,
            protocol = CastProtocol.WebOs,
            vendor = CastVendor.LG,
            avTransportControlUrl = "http://192.168.1.50:1900/upnp/control/AVTransport1",
            renderingControlUrl = "http://192.168.1.50:1900/upnp/control/RenderingControl1",
        )
        val chromecast = CastDevice(
            id = "cast:192.168.1.60",
            name = "Chromecast",
            model = "Chromecast",
            host = "192.168.1.60",
            port = 8009,
            protocol = CastProtocol.GoogleCast,
            vendor = CastVendor.Google,
        )
        val manager = CastManager(
            context = null,
            networkClient = HardenedNetworkClient(DohProvider.System),
            playbackLeases = PlaybackLeases {},
            onProgressBookmark = { _, _, _ -> },
            hostIpProvider = { "127.0.0.1" },
            ioDispatcher = testDispatcher,
            clientFactory = { _, _, _ -> fakeClient },
        )

        manager.castMedia(
            device = lgTv,
            media = CastMediaSource(
                title = "Series.mkv",
                sourceUri = "http://127.0.0.1:9999/series.mkv",
                mimeType = "video/x-matroska",
                positionMs = 0L,
                durationMs = 120_000L,
                subtitles = emptyList(),
                preferredWebOsMode = WebOsCastMode.Dlna,
                bookmarkUri = "magnet:?xt=urn:btih:6666666666666666666666666666666666666666",
            ),
        )
        advanceUntilIdle()
        assertEquals(CastSubtitleControlCapability.DlnaNativeToggle, manager.session.value.subtitleControlCapability)
        assertNull(manager.session.value.subtitlesEnabledOnTv)

        // Remote update propagates both subtitleEnabledOnTv and subtitleCapabilityOverride
        fakeClient.lastCallback?.invoke(
            CastRemoteUpdate(
                status = CastPlaybackStatus.Playing,
                positionMs = 10_000L,
                subtitleEnabledOnTv = false,
                subtitleCapabilityOverride = CastSubtitleControlCapability.MixedExternalAndNative,
            ),
        )
        assertEquals(false, manager.session.value.subtitlesEnabledOnTv)
        assertEquals(CastSubtitleControlCapability.MixedExternalAndNative, manager.session.value.subtitleControlCapability)

        // Adding local subtitle on DLNA upgrades/keeps MixedExternalAndNative
        manager.addLocalSubtitle(PlaybackItem("http://127.0.0.1:9999/sub.es.srt", "Sub.es.srt"))
        advanceUntilIdle()
        assertEquals(CastSubtitleControlCapability.MixedExternalAndNative, manager.session.value.subtitleControlCapability)

        // Switching to GoogleCast without subtitles resets subtitlesEnabledOnTv and sets Unsupported,
        // then addLocalSubtitle updates it to ExternalTrackSelect
        manager.castMedia(
            device = chromecast,
            media = CastMediaSource(
                title = "Movie2.mp4",
                sourceUri = "http://127.0.0.1:9999/movie2.mp4",
                mimeType = "video/mp4",
                positionMs = 0L,
                durationMs = 90_000L,
                subtitles = emptyList(),
                bookmarkUri = "magnet:?xt=urn:btih:7777777777777777777777777777777777777777",
            ),
        )
        advanceUntilIdle()
        assertNull(manager.session.value.subtitlesEnabledOnTv)
        assertEquals(CastSubtitleControlCapability.Unsupported, manager.session.value.subtitleControlCapability)

        manager.addLocalSubtitle(PlaybackItem("http://127.0.0.1:9999/cc.es.srt", "CC.es.srt"))
        advanceUntilIdle()
        assertEquals(CastSubtitleControlCapability.ExternalTrackSelect, manager.session.value.subtitleControlCapability)

        manager.shutdown()
    }
}
