package org.cinito.online.cast

import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.settings.SubtitleStyleConfig
import webtor.app.PlaybackItem

enum class CastProtocol(val label: String) {
    GoogleCast("Chromecast / Google TV"),
    WebOs("LG webOS TV"),
    Dlna("Smart TV (DLNA)"),
}

enum class CastVendor(val label: String) {
    LG("LG"),
    Google("Google"),
    Samsung("Samsung"),
    Sony("Sony"),
    Roku("Roku"),
    Generic("Smart TV"),
}

enum class WebOsCastMode(val label: String) {
    Auto("Automático"),
    ReceiverApp("App CiniTo TV"),
    Dlna("DLNA Hardware"),
    Browser("Navegador webOS"),
}

enum class WebOsRemoteKey(val buttonName: String) {
    UP("UP"),
    DOWN("DOWN"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    ENTER("ENTER"),
    BACK("BACK"),
    HOME("HOME"),
    INFO("INFO"),
    CC("CC"),
    MENU("MENU"),
}

enum class CastAudioControlCapability {
    NativeSwitch,
    AssistedRemoteMenu,
    SingleTrack,
    None,
}

enum class CastSubtitleControlCapability {
    ExternalTrackSelect,
    DlnaNativeToggle,
    MixedExternalAndNative,
    Unsupported,
}

enum class CastSubtitleKind {
    ExternalSidecar,
    LocalFile,
    ExtractedEmbedded,
    NativeEmbedded,
}

data class CastDevice(
    val id: String,
    val name: String,
    val model: String?,
    val host: String,
    val port: Int,
    val protocol: CastProtocol,
    val vendor: CastVendor = CastVendor.Generic,
    val avTransportControlUrl: String? = null,
    val renderingControlUrl: String? = null,
    val ssapPort: Int? = null,
    val hasCiniToTvApp: Boolean = false,
) {
    val hasDlna: Boolean get() = avTransportControlUrl != null
    val hasSsap: Boolean get() = ssapPort != null || protocol == CastProtocol.WebOs
}

enum class CastPlaybackStatus {
    Idle,
    Connecting,
    Buffering,
    Playing,
    Paused,
    Error,
}

data class CastSubtitleTrack(
    val index: Int,
    val name: String,
    val language: String?,
    val vttUrl: String,
    val srtUrl: String,
    val kind: CastSubtitleKind = CastSubtitleKind.ExternalSidecar,
    val isTextBased: Boolean = true,
)

data class EmbeddedSubtitleSpec(
    val trackNumber: Int,
    val label: String,
    val language: String?,
    val rawCodecId: String?,
    val isTextBased: Boolean,
)

data class CastAudioTrack(
    val index: Int,
    val name: String,
    val language: String?,
    val codec: String? = null,
    val regionTag: AudioLanguageTag = AudioLanguageTag.Other,
)

data class CastMediaSource(
    val title: String,
    val sourceUri: String,
    val mimeType: String,
    val positionMs: Long,
    val durationMs: Long,
    val subtitles: List<PlaybackItem>,
    val audioTracks: List<CastAudioTrack> = emptyList(),
    val initialAudioIndex: Int? = null,
    val leaseKey: String? = null,
    val bookmarkUri: String,
    val playlist: List<PlaybackItem> = emptyList(),
    val currentPlaylistIndex: Int = 0,
    val preferredWebOsMode: WebOsCastMode = WebOsCastMode.Auto,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val initialSubtitleIndex: Int? = null,
    val preserveInitialSubtitle: Boolean = false,
    val embeddedSubtitleLabels: List<String> = emptyList(),
    val embeddedSubtitles: List<EmbeddedSubtitleSpec> = emptyList(),
    val isSourceFileComplete: Boolean = false,
)

data class CastSessionState(
    val isDiscovering: Boolean = false,
    val isConnectingDevice: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    val connectedDevice: CastDevice? = null,
    val status: CastPlaybackStatus = CastPlaybackStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val mediaTitle: String = "",
    val activeSourceUri: String? = null,
    val subtitles: List<CastSubtitleTrack> = emptyList(),
    val embeddedSubtitleLabels: List<String> = emptyList(),
    val activeSubtitleIndex: Int? = null,
    val subtitleControlCapability: CastSubtitleControlCapability = CastSubtitleControlCapability.Unsupported,
    val subtitlesEnabledOnTv: Boolean? = null,
    val audioTracks: List<CastAudioTrack> = emptyList(),
    val activeAudioIndex: Int? = null,
    val audioControlCapability: CastAudioControlCapability = CastAudioControlCapability.None,
    val lanUrl: String? = null,
    val activeModeLabel: String? = null,
    val pairingPromptDeviceName: String? = null,
    val activeWebOsMode: WebOsCastMode = WebOsCastMode.Auto,
    val canSwitchWebOsMode: Boolean = false,
    val hasCiniToTvApp: Boolean = false,
    val hasSsapControl: Boolean = false,
    val playlist: List<PlaybackItem> = emptyList(),
    val currentPlaylistIndex: Int = 0,
    val errorMessage: String? = null,
) {
    val isConnected: Boolean get() = connectedDevice != null
    val hasActiveMedia: Boolean get() = activeSourceUri != null
    val isIdleOnTv: Boolean get() = isConnected && !hasActiveMedia && status == CastPlaybackStatus.Idle
    val hasNextEpisode: Boolean get() = playlist.size > 1 && currentPlaylistIndex in 0 until (playlist.size - 1)
    val hasPreviousEpisode: Boolean get() = playlist.size > 1 && currentPlaylistIndex > 0
}
