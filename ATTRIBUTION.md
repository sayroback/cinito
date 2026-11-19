# Attribution & Licenses

CiniTo is licensed under the **GNU General Public License v3.0 or later (GPL-3.0-or-later)**. See `LICENSE` for the full text.

## Open-source foundations & libraries

1. **Torrent Engine & Streaming Core (`engine-rs`, `:core`, background transfer architecture)**
   - Derived from `karmugilen/torrent-player` (MIT / MPL-2.0)
   - Rust workspace `engine-rs` with `wt-engine`, `wt-meta`, `wt-policy`, `wt-peer`, `wt-wire`, `wt-trackers`, `wt-store`
   - Audited modifications in `cinito`: removed permissive wildcard CORS headers from `stream_http.rs`, adapted `build-android.sh` to build `libengine.so` into `org.cinito.online`.

2. **Multi-Provider Search & DNS-over-HTTPS Concepts**
   - Inspired by `prajwalch/TorrentSearch` (MIT)
   - Re-implemented cleanly in Kotlin (`org.cinito.online.network`, `org.cinito.online.search`) with strict hardening: removed all Cloudflare `WebView` JavaScript execution, removed `itorrents.net`, removed JitPack dependencies, and blocked public cleartext HTTP requests.

3. **Integrated Video & Subtitle Playback**
   - `androidx.media3` (`media3-exoplayer`, `media3-ui`, `media3-session`) — Apache-2.0
   - `io.github.anilbeesetti:nextlib-media3ext` (`NextRenderersFactory` + FFmpeg + `libass`) — GPL-3.0

4. **Networking, Serialization & UI**
   - `OkHttp`, `Coil 3`, `Ktor Client`, `Jsoup`, `Kotlinx Serialization`, `Jetpack Compose / Material 3` — Apache-2.0 / MIT
