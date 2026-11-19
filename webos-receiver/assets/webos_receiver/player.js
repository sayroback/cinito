(function () {
  "use strict";

  var video = document.getElementById("video");
  var idleScreen = document.getElementById("idle-screen");
  var idleTitle = document.getElementById("idle-title");
  var idleHint = document.getElementById("idle-hint");
  var bufferingPill = document.getElementById("buffering-pill");
  var bufferingLabel = document.getElementById("buffering-label");
  var errorBanner = document.getElementById("error-banner");
  var errorDetail = document.getElementById("error-detail");
  var tvHud = document.getElementById("tv-hud");
  var hudTitle = document.getElementById("hud-title");
  var hudStateBadge = document.getElementById("hud-state-badge");
  var timeCurrent = document.getElementById("time-current");
  var timeDuration = document.getElementById("time-duration");
  var timelineBuffered = document.getElementById("timeline-buffered");
  var timelineProgress = document.getElementById("timeline-progress");
  var subtitleText = document.getElementById("subtitle-text");
  var subStatusLabel = document.getElementById("sub-status-label");
  var reconnectBanner = document.getElementById("reconnect-banner");
  var autoplayOverlay = document.getElementById("autoplay-overlay");
  var autoplayBtn = document.getElementById("autoplay-btn");
  var fullscreenBtn = document.getElementById("fullscreen-btn");

  var serverBase = "";
  var currentMediaId = null;
  var currentSubtitles = [];
  var managedTrackElements = [];
  var activeSubIndex = null;
  var lastServerSubIndex = undefined;
  var localSubOverrideUntil = 0;
  var activeAudioIndex = null;
  var lastServerAudioIndex = undefined;
  var pendingSeekMs = null;
  var lastCmdSeq = 0;
  var pollTimer = null;
  var reportTimer = null;
  var hudHideTimer = null;
  var stallTimer = null;
  var activeCueTrack = null;
  var audioReportSentForMedia = null;
  var audioSwitchingSupported = null;
  var connectionFailCount = 0;
  var pollInFlight = false;
  var pollStartedAt = 0;

  function formatTime(sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    var s = Math.floor(sec);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var r = s % 60;
    var pad = function (n) { return n < 10 ? "0" + n : String(n); };
    return h > 0 ? h + ":" + pad(m) + ":" + pad(r) : m + ":" + pad(r);
  }

  function showHud(autoHide) {
    tvHud.classList.remove("hidden");
    clearTimeout(hudHideTimer);
    if (autoHide && !video.paused && !video.ended) {
      hudHideTimer = setTimeout(function () {
        tvHud.classList.add("hidden");
      }, 4200);
    }
  }

  function isAllowedSessionUrl(rawUrl) {
    if (!rawUrl || typeof rawUrl !== "string") return false;
    try {
      var u = new URL(rawUrl, window.location.href);
      if (u.protocol !== "http:" && u.protocol !== "https:") return false;
      var h = (u.hostname || "").toLowerCase();
      if (window.location.hostname && h === window.location.hostname.toLowerCase()) return true;
      if (h === "localhost" || h === "127.0.0.1" || h.endsWith(".local")) return true;
      var parts = h.split(".");
      if (parts.length !== 4) return false;
      var b0 = parseInt(parts[0], 10);
      var b1 = parseInt(parts[1], 10);
      return b0 === 10 || (b0 === 192 && b1 === 168) || (b0 === 172 && b1 >= 16 && b1 <= 31) || (b0 === 169 && b1 === 254);
    } catch (_) {
      return false;
    }
  }

  function resolveLaunchParams(raw) {
    var data = raw;
    if (typeof raw === "string" && raw.trim()) {
      try { data = JSON.parse(raw); } catch (_) { data = null; }
    }
    if (data && data.serverBase && isAllowedSessionUrl(data.serverBase)) {
      attachSession(data.serverBase);
      return true;
    }
    return false;
  }

  function bootFromEnvironment() {
    if (window.location.pathname.indexOf("/watch") !== -1) {
      var prefix = window.location.pathname.replace(/\/watch.*$/, "");
      attachSession(window.location.origin + prefix);
      return;
    }
    var params = new URLSearchParams(window.location.search);
    var base = params.get("base");
    if (base && isAllowedSessionUrl(base)) {
      attachSession(base);
      return;
    }
    if (window.PalmSystem && window.PalmSystem.launchParams) {
      resolveLaunchParams(window.PalmSystem.launchParams);
    }
  }

  function attachSession(nextBase) {
    var clean = String(nextBase || "").replace(/\/+$/, "");
    if (!clean || clean === serverBase || !isAllowedSessionUrl(clean)) return;
    serverBase = clean;
    currentMediaId = null;
    currentSubtitles = [];
    managedTrackElements = [];
    activeSubIndex = null;
    lastServerSubIndex = undefined;
    localSubOverrideUntil = 0;
    activeAudioIndex = null;
    lastServerAudioIndex = undefined;
    pendingSeekMs = null;
    lastCmdSeq = 0;
    connectionFailCount = 0;
    pollInFlight = false;
    hideReconnectBanner();
    clearInterval(pollTimer);
    clearInterval(reportTimer);
    pollSession();
    pollTimer = setInterval(pollSession, 500);
    reportTimer = setInterval(function () { postReport(false); }, 900);
  }

  function showReconnectBanner() {
    if (reconnectBanner) reconnectBanner.classList.remove("hidden");
  }

  function hideReconnectBanner() {
    if (reconnectBanner) reconnectBanner.classList.add("hidden");
  }

  function handleConnectionSuccess() {
    connectionFailCount = 0;
    hideReconnectBanner();
  }

  function handleConnectionFailure() {
    connectionFailCount++;
    if (connectionFailCount >= 3) {
      showReconnectBanner();
    }
  }

  function pollSession() {
    if (!serverBase) return;
    var now = Date.now();
    if (pollInFlight && (now - pollStartedAt) < 4000) return;
    pollInFlight = true;
    pollStartedAt = now;
    fetch(serverBase + "/tv/session.json", { cache: "no-store" })
      .then(function (r) {
        if (!r.ok) {
          handleConnectionFailure();
          return null;
        }
        handleConnectionSuccess();
        return r.json();
      })
      .then(function (session) {
        pollInFlight = false;
        if (!session) return;
        applySessionState(session);
      })
      .catch(function () {
        pollInFlight = false;
        handleConnectionFailure();
      });
  }

  function showAutoplayPrompt() {
    if (autoplayOverlay) autoplayOverlay.classList.remove("hidden");
    if (autoplayBtn && typeof autoplayBtn.focus === "function") {
      try { autoplayBtn.focus(); } catch (_) {}
    }
  }

  function hideAutoplayPrompt() {
    if (autoplayOverlay) autoplayOverlay.classList.add("hidden");
  }

  function tryPlayVideo() {
    var playPromise = video.play();
    if (playPromise && typeof playPromise.then === "function") {
      playPromise.then(function () {
        hideAutoplayPrompt();
      }).catch(function (err) {
        var name = err && err.name ? String(err.name) : "";
        var msg = err && err.message ? String(err.message) : String(err || "");
        if (name === "NotAllowedError" || msg.indexOf("NotAllowedError") !== -1 || msg.toLowerCase().indexOf("not allowed") !== -1) {
          showAutoplayPrompt();
        }
        showHud(false);
      });
    }
  }

  function startStallWatch(label) {
    bufferingPill.classList.remove("hidden");
    bufferingLabel.textContent = label || "Cargando búfer…";
    clearTimeout(stallTimer);
    stallTimer = setTimeout(function () {
      if (!video.paused && !video.ended && !video.error) {
        bufferingLabel.textContent = "Red lenta · Esperando datos del teléfono…";
        postReport(true);
      }
    }, 12000);
  }

  function clearStallWatch() {
    clearTimeout(stallTimer);
    stallTimer = null;
  }

  function updateFullscreenButton() {
    if (!fullscreenBtn) return;
    var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
    fullscreenBtn.textContent = fsEl ? "⛶ Salir pantalla completa" : "⛶ Pantalla completa";
  }

  function toggleFullscreen() {
    var doc = document;
    var fsEl = doc.fullscreenElement || doc.webkitFullscreenElement;
    if (!fsEl) {
      var root = doc.documentElement;
      var req = root.requestFullscreen || root.webkitRequestFullscreen;
      if (typeof req === "function") {
        try {
          var p = req.call(root);
          if (p && typeof p.catch === "function") p.catch(function () {});
        } catch (_) {}
      }
    } else {
      var exit = doc.exitFullscreen || doc.webkitExitFullscreen;
      if (typeof exit === "function") {
        try {
          var ep = exit.call(doc);
          if (ep && typeof ep.catch === "function") ep.catch(function () {});
        } catch (_) {}
      }
    }
  }

  function enterIdleScreen(deviceName) {
    clearStallWatch();
    hideAutoplayPrompt();
    video.pause();
    video.removeAttribute("src");
    while (video.firstChild) video.removeChild(video.firstChild);
    video.load();
    currentMediaId = null;
    managedTrackElements = [];
    activeSubIndex = null;
    activeCueTrack = null;
    audioReportSentForMedia = null;
    audioSwitchingSupported = null;
    subtitleText.textContent = "";
    subtitleText.classList.add("hidden");
    bufferingPill.classList.add("hidden");
    errorBanner.classList.add("hidden");
    tvHud.classList.add("hidden");
    idleScreen.classList.remove("hidden");
    if (idleTitle && deviceName) {
      idleTitle.textContent = "Conectado en " + deviceName;
    }
    if (idleHint) {
      idleHint.textContent = "Tu teléfono es el control remoto. Elige cualquier película, episodio o fuente para reproducir aquí.";
    }
  }

  function applySessionState(session) {
    if (session.idle || !session.mediaUrl) {
      if (currentMediaId !== null) {
        enterIdleScreen(session.deviceName);
      } else if (session.deviceName && idleTitle) {
        idleTitle.textContent = "Conectado en " + session.deviceName;
      }
      return;
    }
    idleScreen.classList.add("hidden");

    if (session.subtitleStyle) {
      var sp = Math.max(14, Math.min(34, session.subtitleStyle.fontSizeSp || 19));
      var px = Math.round(sp * 1.85);
      subtitleText.style.fontSize = px + "px";
      var alpha = session.subtitleStyle.backgroundOpacity != null ? session.subtitleStyle.backgroundOpacity : 0.45;
      subtitleText.style.backgroundColor = "rgba(8, 10, 14, " + alpha + ")";
    }

    if (session.mediaId !== currentMediaId) {
      loadNewMedia(session);
    }

    if (session.activeSubtitleIndex !== lastServerSubIndex && Date.now() >= localSubOverrideUntil) {
      lastServerSubIndex = session.activeSubtitleIndex;
      applySubtitleSelection(session.activeSubtitleIndex);
    }

    if (session.activeAudioIndex !== lastServerAudioIndex) {
      lastServerAudioIndex = session.activeAudioIndex;
      if (session.activeAudioIndex != null) {
        applyAudioSelection(session.activeAudioIndex);
      }
    }

    var cmds = [];
    if (Array.isArray(session.commands)) {
      cmds = session.commands;
    } else if (session.command) {
      cmds = [session.command];
    }
    var executedAny = false;
    for (var c = 0; c < cmds.length; c++) {
      var cmd = cmds[c];
      if (cmd && typeof cmd.seq === "number" && cmd.seq > lastCmdSeq) {
        lastCmdSeq = cmd.seq;
        executeCommand(cmd);
        executedAny = true;
      }
    }
    if (executedAny && currentMediaId !== null) {
      postReport(true);
    }
  }

  function applyAudioSelection(index) {
    if (index == null || !video.audioTracks || video.audioTracks.length === 0) return false;
    var target = Number(index);
    if (target < 0 || target >= video.audioTracks.length) return false;
    activeAudioIndex = target;
    for (var i = 0; i < video.audioTracks.length; i++) {
      video.audioTracks[i].enabled = (i === target);
    }
    if (isFinite(video.currentTime) && video.currentTime > 0.2) {
      video.currentTime = video.currentTime;
    }
    showHud(true);
    return true;
  }

  function collectHtmlAudioTracks() {
    if (!video.audioTracks || video.audioTracks.length === 0) return null;
    var out = [];
    for (var i = 0; i < video.audioTracks.length; i++) {
      var t = video.audioTracks[i];
      out.push({
        index: i,
        name: t.label || t.language || ("Pista " + (i + 1)),
        language: t.language || ""
      });
    }
    return out.length > 0 ? out : null;
  }

  function loadNewMedia(session) {
    clearStallWatch();
    hideAutoplayPrompt();
    currentMediaId = session.mediaId;
    currentSubtitles = session.subtitles || [];
    managedTrackElements = [];
    activeSubIndex = null;
    localSubOverrideUntil = 0;
    lastServerSubIndex = session.activeSubtitleIndex;
    activeAudioIndex = session.activeAudioIndex != null ? session.activeAudioIndex : null;
    lastServerAudioIndex = session.activeAudioIndex;
    audioReportSentForMedia = null;
    audioSwitchingSupported = null;
    pendingSeekMs = session.startPositionMs > 2000 ? session.startPositionMs : null;
    errorBanner.classList.add("hidden");
    bufferingPill.classList.remove("hidden");
    bufferingLabel.textContent = "Cargando video…";
    hudTitle.textContent = session.title || "CiniTo";

    // Remove existing track elements cleanly before loading new media
    while (video.firstChild) video.removeChild(video.firstChild);
    subtitleText.classList.add("hidden");
    subtitleText.textContent = "";

    var initialSubIdx = session.activeSubtitleIndex != null ? Number(session.activeSubtitleIndex) : null;
    currentSubtitles.forEach(function (sub, i) {
      if (!sub || !isAllowedSessionUrl(sub.vttUrl)) {
        managedTrackElements[i] = null;
        return;
      }
      var trackEl = document.createElement("track");
      trackEl.kind = "subtitles";
      trackEl.label = sub.name || ("Subtítulo " + (i + 1));
      trackEl.srclang = sub.language || "und";
      trackEl.src = sub.vttUrl;
      trackEl.default = (initialSubIdx !== null && i === initialSubIdx);
      trackEl.addEventListener("load", function () {
        if (activeSubIndex === i) {
          applySubtitleSelection(activeSubIndex);
        }
      });
      trackEl.addEventListener("error", function () {
        if (activeSubIndex === i) {
          subStatusLabel.textContent = "Subtítulos: Error al cargar";
        }
      });
      managedTrackElements[i] = trackEl;
      video.appendChild(trackEl);
    });

    if (!isAllowedSessionUrl(session.mediaUrl)) return;
    video.src = session.mediaUrl;
    video.load();
    applySubtitleSelection(initialSubIdx);
    showHud(true);
    tryPlayVideo();
  }

  function applySubtitleSelection(index) {
    activeSubIndex = index == null ? null : Number(index);
    if (activeCueTrack) {
      activeCueTrack.oncuechange = null;
      activeCueTrack.mode = "disabled";
      activeCueTrack = null;
    }
    subtitleText.textContent = "";
    subtitleText.classList.add("hidden");

    var tracks = video.textTracks;
    if (tracks) {
      for (var i = 0; i < tracks.length; i++) {
        tracks[i].mode = "disabled";
      }
    }
    for (var k = 0; k < managedTrackElements.length; k++) {
      if (managedTrackElements[k]) {
        managedTrackElements[k].default = (activeSubIndex !== null && k === activeSubIndex);
      }
    }

    var trackEl = activeSubIndex != null ? managedTrackElements[activeSubIndex] : null;
    var target = trackEl && trackEl.track ? trackEl.track : (activeSubIndex != null && tracks ? tracks[activeSubIndex] : null);
    if (activeSubIndex != null && target) {
      // Keep native rendering hidden and draw cues in custom styled overlay box
      target.mode = "hidden";
      activeCueTrack = target;
      target.oncuechange = renderActiveCues;
      renderActiveCues();
      var label = currentSubtitles[activeSubIndex] ? currentSubtitles[activeSubIndex].name : ("#" + (activeSubIndex + 1));
      subStatusLabel.textContent = "Subtítulos: " + label;
    } else {
      subStatusLabel.textContent = "Subtítulos: Apagados";
    }
  }

  function renderActiveCues() {
    if (!activeCueTrack || !activeCueTrack.activeCues || activeCueTrack.activeCues.length === 0) {
      subtitleText.textContent = "";
      subtitleText.classList.add("hidden");
      return;
    }
    var lines = [];
    for (var i = 0; i < activeCueTrack.activeCues.length; i++) {
      var raw = String(activeCueTrack.activeCues[i].text || "").replace(/<[^>]+>/g, "").trim();
      if (raw) lines.push(raw);
    }
    if (lines.length > 0) {
      subtitleText.textContent = lines.join("\n");
      subtitleText.classList.remove("hidden");
    } else {
      subtitleText.textContent = "";
      subtitleText.classList.add("hidden");
    }
  }

  function executeCommand(cmd) {
    switch (cmd.type) {
      case "PLAY":
        tryPlayVideo();
        showHud(true);
        break;
      case "PAUSE":
        video.pause();
        showHud(false);
        break;
      case "SEEK":
        if (isFinite(video.duration) && video.duration > 0) {
          var maxTarget = Math.max(0, video.duration - 0.25);
          video.currentTime = Math.max(0, Math.min(maxTarget, (cmd.positionMs || 0) / 1000));
          pendingSeekMs = null;
          updateProgressUi();
          postReport(true);
        } else {
          pendingSeekMs = Math.max(0, cmd.positionMs || 0);
        }
        showHud(true);
        break;
      case "SELECT_AUDIO":
        if (applyAudioSelection(cmd.audioIndex)) {
          postReport(true, undefined, Number(cmd.audioIndex));
        }
        break;
      case "STOP":
        enterIdleScreen();
        break;
    }
  }

  function postReport(immediate, tvSubtitleOverride, tvAudioOverride, detectedAudioTracks) {
    if (!serverBase || !currentMediaId) return;
    var errMsg = null;
    var status = "BUFFERING";
    if (video.error) {
      status = "ERROR";
      errMsg = describeMediaError(video.error.code);
    } else if (video.ended) {
      status = "ENDED";
    } else if (!video.paused && video.readyState >= 3) {
      status = "PLAYING";
    } else if (video.paused && video.readyState >= 1) {
      status = "PAUSED";
    }

    var payload = {
      mediaId: currentMediaId,
      status: status,
      positionMs: Math.round((video.currentTime || 0) * 1000),
      durationMs: isFinite(video.duration) && video.duration > 0 ? Math.round(video.duration * 1000) : 0,
      errorMessage: errMsg
    };
    if (typeof tvSubtitleOverride !== "undefined") {
      payload.subtitleSelectionChanged = true;
      payload.activeSubtitleIndex = tvSubtitleOverride;
    }
    if (typeof tvAudioOverride !== "undefined") {
      payload.audioSelectionChanged = true;
      payload.activeAudioIndex = tvAudioOverride;
    }
    if (detectedAudioTracks && detectedAudioTracks.length > 0) {
      payload.audioTracks = detectedAudioTracks;
    }
    if (audioSwitchingSupported !== null) {
      payload.audioSwitchingSupported = audioSwitchingSupported;
    }
    if (lastCmdSeq > 0) {
      payload.ackCmdSeq = lastCmdSeq;
    }

    fetch(serverBase + "/tv/report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    }).then(function (r) {
      if (r.ok) {
        handleConnectionSuccess();
      } else {
        handleConnectionFailure();
      }
    }).catch(function () {
      handleConnectionFailure();
    });
  }

  function describeMediaError(code) {
    switch (code) {
      case 1: return "Reproducción cancelada.";
      case 2: return "Error de red leyendo el flujo desde tu teléfono.";
      case 3: return "Error decodificando el video en el navegador web.";
      case 4: return "Formato o audio no compatible con navegador webOS; cambia al modo DLNA en la app.";
      default: return "Error de reproducción en el televisor.";
    }
  }

  function updateProgressUi() {
    var pos = video.currentTime || 0;
    var dur = isFinite(video.duration) && video.duration > 0 ? video.duration : 0;
    timeCurrent.textContent = formatTime(pos);
    timeDuration.textContent = formatTime(dur);
    timelineProgress.style.width = dur > 0 ? Math.min(100, (pos / dur) * 100) + "%" : "0%";
    if (dur > 0 && video.buffered && video.buffered.length > 0) {
      var bufEnd = video.buffered.end(video.buffered.length - 1);
      timelineBuffered.style.width = Math.min(100, (bufEnd / dur) * 100) + "%";
    }
  }

  video.addEventListener("loadedmetadata", function () {
    if (pendingSeekMs != null && isFinite(video.duration) && video.duration > 0) {
      var maxSec = Math.max(0, video.duration - 0.25);
      video.currentTime = Math.max(0, Math.min(maxSec, pendingSeekMs / 1000));
      pendingSeekMs = null;
    }
    if (activeAudioIndex != null) {
      applyAudioSelection(activeAudioIndex);
    }
    if (audioReportSentForMedia !== currentMediaId) {
      audioReportSentForMedia = currentMediaId;
      var htmlTracks = collectHtmlAudioTracks();
      if (htmlTracks && htmlTracks.length > 0) {
        audioSwitchingSupported = htmlTracks.length > 1;
        postReport(true, undefined, undefined, htmlTracks);
      } else {
        audioSwitchingSupported = false;
        postReport(true, undefined, undefined, null);
      }
    }
    updateProgressUi();
  });

  video.addEventListener("waiting", function () {
    startStallWatch("Cargando búfer…");
    hudStateBadge.textContent = "Cargando…";
    postReport(true);
  });

  video.addEventListener("stalled", function () {
    startStallWatch("Esperando datos de red…");
    hudStateBadge.textContent = "Cargando…";
    postReport(true);
  });

  video.addEventListener("canplay", function () {
    clearStallWatch();
  });

  video.addEventListener("playing", function () {
    clearStallWatch();
    hideAutoplayPrompt();
    bufferingPill.classList.add("hidden");
    errorBanner.classList.add("hidden");
    hudStateBadge.textContent = "Reproduciendo";
    showHud(true);
    postReport(true);
  });

  video.addEventListener("pause", function () {
    clearStallWatch();
    hudStateBadge.textContent = "En pausa";
    showHud(false);
    postReport(true);
  });

  video.addEventListener("ended", function () {
    clearStallWatch();
    bufferingPill.classList.add("hidden");
    hudStateBadge.textContent = "Finalizado";
    showHud(false);
    postReport(true);
  });

  video.addEventListener("error", function () {
    clearStallWatch();
    hideAutoplayPrompt();
    bufferingPill.classList.add("hidden");
    var msg = describeMediaError(video.error ? video.error.code : 0);
    errorDetail.textContent = msg;
    errorBanner.classList.remove("hidden");
    postReport(true);
  });

  video.addEventListener("timeupdate", function () {
    updateProgressUi();
    if (activeCueTrack && activeCueTrack.cues && activeCueTrack.cues.length > 0) {
      renderActiveCues();
    }
  });
  video.addEventListener("seeked", function () {
    updateProgressUi();
    postReport(true);
  });

  if (video.textTracks && typeof video.textTracks.addEventListener === "function") {
    video.textTracks.addEventListener("addtrack", function () {
      if (activeSubIndex != null) {
        applySubtitleSelection(activeSubIndex);
      }
    });
  }

  if (autoplayBtn) {
    autoplayBtn.addEventListener("click", tryPlayVideo);
  }
  if (fullscreenBtn) {
    fullscreenBtn.addEventListener("click", toggleFullscreen);
  }
  document.addEventListener("fullscreenchange", updateFullscreenButton);
  document.addEventListener("webkitfullscreenchange", updateFullscreenButton);

  document.addEventListener("webOSLaunch", function (e) {
    resolveLaunchParams(e && e.detail ? e.detail : null);
  });
  document.addEventListener("webOSRelaunch", function (e) {
    resolveLaunchParams(e && e.detail ? e.detail : null);
  });

  document.addEventListener("keydown", function (e) {
    var code = e.keyCode || 0;
    var key = e.key || "";
    if (!currentMediaId) return;

    if (autoplayOverlay && !autoplayOverlay.classList.contains("hidden") &&
        (key === "Enter" || key === " " || code === 13 || code === 415)) {
      tryPlayVideo();
      e.preventDefault();
      return;
    }

    if (key === "Enter" || key === " " || code === 13 || code === 415 || code === 19) {
      if (video.paused) tryPlayVideo(); else video.pause();
      e.preventDefault();
    } else if (key === "ArrowLeft" || code === 37 || code === 412) {
      video.currentTime = Math.max(0, (video.currentTime || 0) - 10);
      showHud(true);
      e.preventDefault();
    } else if (key === "ArrowRight" || code === 39 || code === 417) {
      var dur = isFinite(video.duration) ? video.duration : ((video.currentTime || 0) + 10);
      video.currentTime = Math.min(dur, (video.currentTime || 0) + 10);
      showHud(true);
      e.preventDefault();
    } else if (key === "ArrowUp" || key === "ArrowDown" || code === 38 || code === 40) {
      if (currentSubtitles.length > 0) {
        var next = activeSubIndex == null ? 0 : (activeSubIndex + 1);
        if (next >= currentSubtitles.length) next = null;
        lastServerSubIndex = next;
        localSubOverrideUntil = Date.now() + 1800;
        applySubtitleSelection(next);
        postReport(true, next);
      }
      showHud(true);
      e.preventDefault();
    } else if (key === "f" || key === "F" || code === 70) {
      toggleFullscreen();
      e.preventDefault();
    } else if (code === 461 || key === "Escape") {
      if (tvHud.classList.contains("hidden")) {
        showHud(true);
      } else if (window.PalmSystem && typeof window.PalmSystem.platformBack === "function") {
        window.PalmSystem.platformBack();
      }
      e.preventDefault();
    }
  });

  bootFromEnvironment();
})();
