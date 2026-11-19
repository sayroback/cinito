# CiniTo — AI Agent Context & Engineering Guide

This repository (`cinito`) is an Android torrent streaming + Cast media player built with Kotlin/Jetpack Compose (`app/`), shared Kotlin engine client (`core/`), a Rust BitTorrent streaming engine (`engine-rs/`), and an LG webOS HTML5 receiver (`webos-receiver/`).

Any AI coding agent starting a new session here should read this file and [docs/AI_DEBUGGING.md](docs/AI_DEBUGGING.md) before modifying Cast, streaming, or playback code.

---

## 1. Repository Architecture

| Module / Path | Responsibility | Key Entry Points |
|---|---|---|
| `app/` (`org.cinito.online.*`) | Compose UI, metadata/container probing, Cast orchestration, Media3/ExoPlayer | `MainActivity.kt`, `cast/`, `metadata/`, `player/`, `ui/player/` |
| `app/` (`webtor.app.*`) | Torrent library session, foreground service, network monitor, HTTP/content playback | `WebtorApp.kt`, `LibrarySession.kt`, `PlayService.kt`, `PlaybackProvider.kt`, `NetworkMonitor.kt` |
| `core/` (`webtor.core.*`) | Engine HTTP/control client contract | `EngineClient.kt` |
| `engine-rs/` | Rust BitTorrent engine compiled via NDK (`libengine.so`), HTTP `/torrent/{id}/file/{index}` server | `crates/wt-engine/src/server.rs`, `stream_http.rs`, `seek_index.rs` |
| `webos-receiver/` | Shared web receiver served by `CastMediaServer` (`/watch`) and deployable as LG Dev App (`org.cinito.tv`) | `assets/webos_receiver/index.html`, `player.js`, `styles.css`, `deploy-tv.sh` |
| `tools/` | Live TV & Android Emulator debugging utilities | `emulator_cast_debug.sh`, `cast_relay.py`, `probe_tv.py` |
| `docs/` | Operational guides and debugging runbooks | `docs/AI_DEBUGGING.md` |

> Note: `app/build.gradle.kts` maps `assets.srcDir(file("../webos-receiver/assets"))`, so `webos-receiver/assets/webos_receiver/*` is bundled directly into the APK under `webos_receiver/`.

---

## 2. Build, Test & Verification Commands

Run from the repository root:

```bash
# Android JVM unit tests (Cast, Metadata, Network, Player, Webtor)
./gradlew testDebugUnitTest

# Build and install debug APK to attached device/emulator
./gradlew installDebug

# If building native Rust engine specifically for x86_64 emulator ABI
./gradlew installDebug -Pcinito.emulator=true

# Rust engine unit tests (from engine-rs/)
cd engine-rs && cargo test -p wt-engine --lib

# JS syntax check for webOS receiver
node --check webos-receiver/assets/webos_receiver/player.js
```

---

## 3. Critical Cast & Streaming Invariants (Do Not Regress)

1. **Google Cast V2 (`GoogleCastClient.kt`)**
   - `ensureConnectedTransport()` calls `closeTransport(clearCallback = false)` so `statusCallback` assigned by `connectIdle`/`connectAndLoad` is never wiped out.
   - `RECEIVER_STATUS` must match `appId == "CC1AD845"` (`DEFAULT_MEDIA_RECEIVER_APP_ID`) strictly — never match any app that merely has a `transportId` (otherwise Backdrop `E8C28D3C` hijacks the session). Always send `NS_CONNECTION` `CONNECT` with `"origin": {}` when `transportId` changes, and handle `LAUNCH_ERROR`.
   - Use `as? JsonObject`, `as? JsonArray`, and `?.jsonPrimitive?.contentOrNull` everywhere in incoming Cast JSON parsing (`MEDIA_STATUS` often sends `"media": null`).
   - External subtitle tracks use `trackId = 1000 + sub.index` to avoid colliding with receiver internal track IDs. Separate `lastSubCommandAtMs` and `lastAudioCommandAtMs` grace timers.
   - Keep `soTimeout = 6_000` before TLS handshake and `10_000` after, with `lastRxAtMs` watchdog in `startReaderLoop`.

2. **Discovery (`CastDiscoveryManager.kt`, `CastProtocolUtils.kt`, `HardenedNetworkClient.kt`)**
   - `startSsdpScan()` binds `MulticastSocket` to the Wi-Fi interface (`findLocalLanIpv4Address()`).
   - `probeHost(host)` queries both `:8008/ssdp/device-desc.xml` and `:8008/setup/eureka_info` (modern LG webOS TVs expose built-in Google Cast on `:8009` + `:8008/setup/eureka_info` while returning 404 on `/ssdp/device-desc.xml`), and runs a targeted SSDP multicast burst if SSAP (`3000`/`3001`) responds without `avTransportControlUrl`.
   - `NetworkSafety.isPrivateLanHost()` allows IPv4 private/link-local, `.local`, and IPv6 link-local (`fe80::/10`) / ULA (`fc00::/7`) without DNS blocking.
   - `dlnaDidlMetadataXml()` must **not** emit a second `<res protocolInfo="http-get:*:text/srt:*">` element (causes UPnP 714/402 on strict TVs); keep `<pv:subtitleFileUri>` and `<sec:CaptionInfoEx>` only.

3. **LG webOS & DLNA (`DlnaCastClient.kt`, `WebOsCastClient.kt`, `WebOsSsapClient.kt`)**
   - LG UPnP DMR (`LG WebOSTV DMRplus`) blocks on `SetAVTransportURI` / `Play` while opening MKV streams over torrent (`> 8s`): keep `longSoapHttp` (`25s` readTimeout) for `SetAVTransportURI`/`Play`/`Stop`/`Seek` and `8s` for polling.
   - Always send `Stop` before `SetAVTransportURI` and retry on UPnP `701` (`Transition not available`, common when TV is in `LG_TRANSITIONING`); close `com.webos.app.browser` via SSAP before switching to DLNA.
   - LG `RenderingControl:1` exposes `X_GetSubtitle` and `X_SetSubtitle` (`ON`/`OFF`). Use it for live subtitle toggling in DLNA without restarting playback, and gracefully disable on SOAP `401`/`501` for non-LG renderers.
   - `WebOsSsapClient` invokes `onDisconnected` on unexpected `onFailure`/`onClosed` and aborts `ws://3000` fallback if `wss://3001` explicitly rejects pairing.

4. **Subtitles & HTTP Bridge (`CastMediaServer.kt`, `MkvSubtitleExtractor.kt`, `CastManager.kt`, `player.js`)**
   - `CastSubtitleControlCapability` (`ExternalTrackSelect`, `DlnaNativeToggle`, `MixedExternalAndNative`, `Unsupported`) models what the active TV protocol can actually control.
   - `MkvSubtitleExtractor` extracts `S_TEXT/UTF8`, `S_TEXT/ASS`, `S_TEXT/SSA`, `S_TEXT/WEBVTT` in one sequential EBML pass (skipping video/audio payloads) **only when `isSourceFileComplete == true`**. Never block on incomplete streaming torrents.
   - `CastMediaServer` uses a bounded FIFO `commands` queue (with `?afterSeq=` / `ackCmdSeq`), survives transient `accept()` errors, rebinds on LAN IP change, converts `.ass`/`.ssa` & `Windows-1252` SRT to WebVTT, adds CORS headers on `404`, and supports `CastDebugOverrides` (`BuildConfig.DEBUG` only) for emulator debugging.
   - `player.js` tracks `managedTrackElements` by direct reference, binds `load`/`error`/`addtrack`, displays an explicit autoplay resume overlay if `play()` is rejected, and reports `audioSwitchingSupported: false` instead of wiping phone-probed audio tracks when `video.audioTracks` is unavailable.

5. **Lifecycle & Stability (`PlayService.kt`, `LibrarySession.kt`, `NetworkMonitor.kt`, `engine-rs`)**
   - `PlayService` (`dataSync|mediaPlayback`) stays active with `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF` while `castManager.session.value.isConnected` is true (including standby and completed/local files), and `LibrarySession` protects active Cast entries from `idleDropMinutes`.
   - `NetworkMonitor` (`1500ms`) + `LibrarySession` (`3000ms`) debounce brief `NET_CAPABILITY_VALIDATED` drops so `engine-rs` does not kill peers or abort `stream_http.rs`.
   - `CineViewModel.onCleared()` must not call `finishPlayback` when Cast is active.

6. **UI Rules**
   - Top bars (`GalleryLibraryScreen`, `DiscoverScreen`, `SearchScreen`) must pass `compact = true` to `CastTopBarActionButton` and enforce `maxLines = 1` + `softWrap = false` on titles.
   - `CastRemoteOverlay` uses an opaque background (`0xFF0B0D12`), consumes pointer events at its root, guards `scrubbingVol` against scroll-cancel, disables timeline scrubbing when `durationMs <= 0L`, and disables fake audio switches unless `audioControlCapability == NativeSwitch`.

---

## 4. Multi-Agent Development Workflow

When delegating work to parallel AI agents in this repo:
1. **Freeze shared contracts first** (`CastModels.kt`, `CastSessionClient.kt`, `MediaContainerProbe.kt`) before fanning out.
2. **Assign strictly disjoint file lists** per agent (e.g., Protocol/Discovery vs. DLNA/WebOS/Receiver vs. Server/Manager/Extractor vs. Lifecycle/Engine vs. UI/`MainActivity`).
3. **Do not run `./gradlew` concurrently** across multiple agents in the same workspace (Gradle lock contention). Run `./gradlew testDebugUnitTest` and `cargo test -p wt-engine --lib` once after merging all agent edits.
4. **Never commit secrets** (`local.properties`, `.keystore`, `.env`, API tokens).
