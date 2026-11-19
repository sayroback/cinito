# Live AI Debugging Runbook (TV + Android Emulator / Physical Phone)

Use this runbook whenever a new AI session needs to reproduce, capture logs for, or verify Cast / DLNA / Chromecast / WebOS / subtitle issues on real LAN hardware.

---

## 1. Quick Environment Check

Verify attached Android devices, host LAN IPv4, and available tools:

```bash
adb devices -l
ip -brief addr
```

Check TV reachability and advertise services on the host's Wi-Fi subnet:

```bash
python3 tools/probe_tv.py --host <TV_IP>
# Or discover all SSDP services on the local subnet:
python3 tools/probe_tv.py
```

### Important Emulator Networking Constraint
The Android Studio emulator (`emulator-5554`) runs behind QEMU SLIRP virtual NAT (`10.0.2.15` / `10.0.2.16`):
1. **mDNS (`_googlecast._tcp`)** is usually bridged by the emulator, so Chromecast devices appear automatically.
2. **UDP SSDP multicast (`239.255.255.250:1900`)** is **blocked** by QEMU NAT, so LG webOS (`webos-second-screen:1`) and DLNA (`MediaRenderer:1`) will **not** auto-appear in `emulator-5554`. Use the red **IP** button in `CastDeviceSheet` (`probeHost`) to add the TV by IP.
3. **Inbound HTTP from TV to Emulator fails without a relay:** Normally `CastMediaServer` advertises `http://10.0.2.16:<port>/...`, which the TV on the LAN cannot reach (causing DLNA `716 Resource not found` or browser timeouts).

---

## 2. One-Command Emulator-to-TV Bridge (`tools/emulator_cast_debug.sh`)

`CastMediaServer` includes a `BuildConfig.DEBUG`-only override (`CastDebugOverrides` in `CastProtocolUtils.kt`) reading two Android system properties:
- `debug.cinito.cast_host` -> host PC's LAN IPv4 advertised to the TV in URLs
- `debug.cinito.cast_port` -> fixed TCP port (default `8765`) bound on `0.0.0.0` inside the emulator

Run the all-in-one helper from repo root:

```bash
./tools/emulator_cast_debug.sh
```

What it does automatically:
1. Detects the running `emulator-*` and the host's active LAN IPv4 (`ip route get 1.1.1.1`).
2. Sets `debug.cinito.cast_host` and `debug.cinito.cast_port` on the emulator via `adb shell setprop`.
3. Configures `adb forward tcp:8765 tcp:8765`.
4. Starts `tools/cast_relay.py` listening on `<HOST_LAN_IP>:8765 -> 127.0.0.1:8765`.
5. Clears `logcat`, launches `org.cinito.online/.MainActivity`, and streams filtered logs (`CineCast:* AudioProbe:* webtor-engine:* AndroidRuntime:E`).

> **Note:** If the host process restarts or the terminal stops, `tools/cast_relay.py` stops too — the TV will immediately fail with `DLNA HTTP 500 (SetAVTransportURI): Resource not found (716)` until you restart `./tools/emulator_cast_debug.sh` (or `tools/cast_relay.py`).
> Properties set via `setprop` reset when the emulator cold-boots.

To disable the override on the emulator without restarting:
```bash
adb -s emulator-5554 shell setprop debug.cinito.cast_host ""
adb -s emulator-5554 shell setprop debug.cinito.cast_port ""
```

---

## 3. Debugging with a Physical Android Phone (True L2 SSDP + mDNS)

To test real SSDP/mDNS auto-discovery or background Doze/Wi-Fi roaming without emulator NAT:
1. Connect via **USB** (`adb devices -l`) or **Wireless Debugging** (Android 11+):
   ```bash
   adb pair <PHONE_IP>:<PAIR_PORT> <6_DIGIT_CODE>
   adb connect <PHONE_IP>:<ADB_PORT>
   ```
2. Do **not** set `debug.cinito.cast_host` on a physical phone (it uses its real Wi-Fi IP automatically).
3. Stream live logs:
   ```bash
   adb -s <SERIAL> logcat -c
   adb -s <SERIAL> logcat -s CineCast:* AudioProbe:* webtor-engine:* AndroidRuntime:E
   ```

---

## 4. Cast Log Tag (`CineCast`) Reference

All Cast subsystems log under tag `CineCast` (`CastLog` in `CastProtocolUtils.kt`):
- `[Discovery]` — `probeHost(...)` results (`WebOs`, `GoogleCast`, `hasDlna`)
- `[Server]` — `ensureStarted bound=...`, `prepareIdle`, `publish` (`subs=`, `audio=`, `lanIp=`, `port=`)
- `[HTTP]` — TV requests (`/watch`, `/styles.css`, `/player.js`, `/media.*`, `/sub/*.vtt|.srt`)
- `[TVReport]` — `player.js` `/tv/report` state changes, errors, track overrides
- `[Manager]` — `castMedia`, `selectSubtitleIndex`, `selectAudioIndex`, `setTvSubtitlesEnabled`, remote errors
- `[GCast]` — TLS connect, `RECEIVER_STATUS` / `MEDIA_STATUS` payloads, `readerLoop` exit reason
- `[DLNA]` — `SetAVTransportURI`, `701`/`714`/`716` retries, `Play` timeout fallback, `X_SetSubtitle`
- `[WebOS]` / `[SSAP]` — launch `org.cinito.tv` vs `Dlna` vs `Browser`, WebSocket `onFailure`/`onClosed`

---

## 5. Direct TV Diagnostics (LG webOS MCP + UPnP SOAP)

### Using `webos-tv` MCP (when configured in your AI harness)
- `tv_get_device_info` — model, `sdkVersion`, firmware
- `tv_get_status` — active app (`com.webos.app.browser`, `com.webos.app.mediadiscovery`, etc.), volume, mute
- `tv_list_apps` / `tv_dev_list_apps` — check whether `org.cinito.tv` is installed in Dev Mode
- `tv_dev_deploy_app` / `tv_dev_launch` / `tv_dev_inspect_start` — deploy and open Web Inspector on `webos-receiver/`

### Direct DLNA / Cast Probes via `tools/probe_tv.py` or `curl`
```bash
# Probe TV ports, Google Cast eureka_info, SSDP MediaRenderer, and live DLNA transport/subtitle state
python3 tools/probe_tv.py --host <TV_IP>

# Query DLNA GetTransportInfo directly (replace <AV_CONTROL_URL> from MediaRenderer XML)
curl -sS -X POST "http://<TV_IP>:<PORT><AV_CONTROL_URL>" \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo"' \
  -d '<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>'
```

Common LG DLNA fault codes seen during debugging:
- `701 Transition not available` — DMR is in `LG_TRANSITIONING` or active playback; `Stop` + delay is required before `SetAVTransportURI` / `Play`.
- `714 Illegal MIME-Type` / `402 Invalid Args` — strict DIDL-Lite rejection; retry `SetAVTransportURI` with empty `CurrentURIMetaData`.
- `716 Resource not found` — TV cannot reach `CastMediaServer` URL (check Wi-Fi subnet, firewall, or whether `cast_relay.py` / `adb forward` stopped).
- `401 Invalid Action` on `X_GetSubtitle` / `X_SetSubtitle` — renderer does not support LG `RenderingControl` subtitle toggling.
