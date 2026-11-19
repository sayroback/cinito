package org.cinito.online.cast

import org.cinito.online.settings.SubtitleStyleConfig

data class CastRemoteUpdate(
    val status: CastPlaybackStatus? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float? = null,
    val isMuted: Boolean? = null,
    val errorMessage: String? = null,
    val ended: Boolean = false,
    val subtitleOverrideIndex: Int? = null,
    val hasSubtitleOverride: Boolean = false,
    val audioOverrideIndex: Int? = null,
    val hasAudioOverride: Boolean = false,
    val audioTracksUpdate: List<CastAudioTrack>? = null,
    val audioCapabilityOverride: CastAudioControlCapability? = null,
    val subtitleEnabledOnTv: Boolean? = null,
    val subtitleCapabilityOverride: CastSubtitleControlCapability? = null,
    val ssapConnected: Boolean? = null,
    val hasCiniToTvApp: Boolean? = null,
)

interface CastSessionClient {
    val canReuseConnection: Boolean get() = false

    suspend fun connectIdle(
        device: CastDevice,
        mediaServerBaseUrl: String?,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Boolean = true

    suspend fun connectAndLoad(
        device: CastDevice,
        mediaUrl: String,
        mimeType: String,
        title: String,
        positionMs: Long,
        subtitles: List<CastSubtitleTrack>,
        audioTracks: List<CastAudioTrack> = emptyList(),
        initialAudioIndex: Int? = null,
        initialSubtitleIndex: Int? = null,
        subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
        onUpdate: (CastRemoteUpdate) -> Unit,
    )

    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    suspend fun setVolume(fraction: Float)
    suspend fun setMuted(muted: Boolean)
    suspend fun selectSubtitleIndex(index: Int?)
    suspend fun setNativeSubtitlesEnabled(enabled: Boolean): Boolean = false
    suspend fun updateSubtitleStyle(style: SubtitleStyleConfig) = Unit
    suspend fun selectAudioTrack(index: Int) = Unit
    suspend fun sendTvRemoteKey(key: WebOsRemoteKey): Boolean = false
    suspend fun stop()
    fun close()
}
