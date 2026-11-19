# CiniTo Privacy Policy

CiniTo contains **zero** advertisements, **zero** analytics SDKs, **zero** crash reporters, and **zero** telemetry.

## Network connections the app can make

1. **BitTorrent swarm & trackers (user-initiated):**
   When you add or stream a torrent, the Rust `engine-rs` BitTorrent core connects to peers and trackers listed in that torrent (plus built-in public UDP/HTTPS trackers and DHT/PEX if enabled in engine settings). Automatic external tracker-list fetching on startup, UPnP/NAT-PMP router port mapping, LAN multicast Local Service Discovery (LSD), and WebTorrent/STUN connections are disabled by default. Only `127.0.0.1` loopback is used by the local streaming server.

2. **Public HTTPS torrent search providers & UDP swarm diagnostics (user-initiated):**
   When you run a search, queries are sent over HTTPS (using your selected DNS-over-HTTPS provider or system DNS) only to the search providers you have enabled (Torrentio `torrentio.strem.fun`, Knaben, ThePirateBay `apibay.org`, SolidTorrents `solidtorrents.to`, LimeTorrents `limetorrents.lol`, BitSearch `bitsearch.to`, YTS, TorrentsCSV, Nyaa, Internet Archive, SubsPlease, or your own configured Torznab servers). Cleartext HTTP (including via redirects) to public internet hosts is rejected. Optional UDP tracker scraping resolves tracker domains through your configured DoH resolver and never contacts untrusted tracker hosts injected inside magnet links.

3. **Keyless public catalog metadata (`Metadata Lookup`, toggleable):**
   When **Metadata lookup** is enabled in Settings (enabled by default, can be disabled at any time):
   - Discover tab and library poster/synopsis matching query JustWatch MX (`apis.justwatch.com`, when México/Latino region is active), Cinemeta (`v3-cinemeta.strem.io`), TVmaze (`api.tvmaze.com`), and optionally TMDB (`api.themoviedb.org`, `es-MX`, only if you configure your own TMDB API key) over HTTPS.
   - Local `.torrent` files opened from device storage and non-media filenames are excluded from automatic library metadata lookup, and negative lookups are cached on disk to avoid repeated queries.
   - No user account, device ID, or viewing history is ever sent; only normalized media titles or public catalog queries are requested.
   - When disabled, zero metadata or artwork requests are made and the gallery displays offline local video frame previews.

4. **Local Wi-Fi TV Casting (user-initiated):**
   When you open the Cast picker inside the player, CiniTo discovers Google Cast (`_googlecast._tcp.` mDNS), LG webOS SSAP (`wss://:3001` / `ws://:3000`), and DLNA/UPnP (`239.255.255.250:1900` SSDP) televisions strictly inside your local Wi-Fi network. While casting, an ephemeral local HTTP streaming server bound to your local LAN IP and protected by a random 128-bit per-session token serves only the active video/subtitle to your selected TV and shuts down immediately when casting stops. Zero Google Play Services Cast SDK or telemetry is included.

## Local storage

All library state, playback positions, preferences, cached metadata/posters, and Torznab, TMDB, and LG webOS SSAP pairing keys (encrypted via Android Keystore AES-256-GCM) stay exclusively on your device and are excluded from cloud backups.
