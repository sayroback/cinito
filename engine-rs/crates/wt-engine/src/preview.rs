//! Preview reader for thumbnail generation.
//!
//! Port of `engine-go/preview.go`. Supplies seekable verified bytes for
//! thumbnails without mutating playback focus, resume history, or startup
//! ownership. Piece admission is budgeted so a decoder cannot exhaust RAM
//! before requesting MP4 tail metadata.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;

/// Soft byte budget for one preview session (Go: 8 MiB).
pub const PREVIEW_BUDGET_BYTES: i64 = 8 * 1024 * 1024;

/// Preview sessions auto-close after this (extended to 25s for multi-piece container header/tail probes).
pub const PREVIEW_SESSION_TIMEOUT: Duration = Duration::from_secs(25);

pub type PreviewReadFn = Box<dyn Fn(&mut [u8], i64) -> std::io::Result<usize> + Send + Sync>;

/// Directory for temporary preview draft storage under the download root.
pub fn preview_dir(base_dir: impl AsRef<Path>, info_hash_hex: &str) -> PathBuf {
    base_dir.as_ref().join(".preview").join(info_hash_hex)
}

/// Admit whole pieces covering `[offset, offset+length)` against a session
/// budget. Already-admitted or already-complete pieces reuse the budget.
///
/// `piece_length` / `file_torrent_offset` describe the torrent layout;
/// `piece_complete` reports verified pieces that need no network admission.
#[allow(clippy::too_many_arguments)]
pub fn admit_preview_range(
    admitted: &mut HashMap<u32, bool>,
    admitted_bytes: &mut i64,
    budget: i64,
    piece_length: i64,
    file_torrent_offset: i64,
    file_length: i64,
    offset: i64,
    length: i64,
    piece_complete: impl Fn(u32) -> bool,
    payload_allowed: bool,
) -> std::io::Result<Vec<u32>> {
    if length <= 0 || piece_length <= 0 {
        return Ok(Vec::new());
    }
    if offset < 0 || offset >= file_length {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "invalid preview offset",
        ));
    }
    let end_off = (offset + length).min(file_length);
    let abs_begin = file_torrent_offset + offset;
    let abs_end = file_torrent_offset + end_off;
    let begin = (abs_begin / piece_length) as u32;
    let end = ((abs_end + piece_length - 1) / piece_length) as u32;
    let mut need = 0i64;
    let mut new_pieces = Vec::new();
    for i in begin..end {
        if admitted.contains_key(&i) {
            continue;
        }
        if piece_complete(i) {
            continue;
        }
        if !payload_allowed {
            return Err(std::io::Error::other("payload transfers are paused"));
        }
        // Approximate piece length: full pieces except possibly the last.
        let n = piece_length;
        if *admitted_bytes + need + n > budget {
            return Err(std::io::Error::other("preview byte budget exceeded"));
        }
        need += n;
        new_pieces.push(i);
    }
    for i in &new_pieces {
        admitted.insert(*i, true);
        *admitted_bytes += piece_length;
    }
    Ok(new_pieces)
}

struct PreviewAdmission {
    piece_length: i64,
    file_offset: i64,
    storage: Arc<wt_storage::DocumentTorrentStorage>,
    server: std::sync::Weak<crate::server::EngineServer>,
    torrent_id: String,
    coordinator: Arc<crate::playback::PlaybackPriorityCoordinator>,
    cached_only: bool,
}

pub struct PreviewReader {
    pub file_index: usize,
    pub length: i64,
    budget: i64,
    budget_remaining: AtomicI64,
    closed: AtomicBool,
    inner_read: Mutex<Option<PreviewReadFn>>,
    /// Pieces admitted for network fetch this session.
    admitted: Mutex<HashMap<u32, bool>>,
    admitted_bytes: AtomicI64,
    close_hooks: Mutex<Vec<Box<dyn FnOnce() + Send + Sync>>>,
    admission: Mutex<Option<PreviewAdmission>>,
    /// Who closed the reader (`close_with_reason`); reported in cancelled reads.
    close_reason: Mutex<Option<&'static str>>,
}

impl PreviewReader {
    pub fn new(file_index: usize, length: i64, read_fn: PreviewReadFn) -> Self {
        Self::with_budget(file_index, length, PREVIEW_BUDGET_BYTES, read_fn)
    }

    pub fn with_budget(
        file_index: usize,
        length: i64,
        budget: i64,
        read_fn: PreviewReadFn,
    ) -> Self {
        Self {
            file_index,
            length,
            budget,
            budget_remaining: AtomicI64::new(budget),
            closed: AtomicBool::new(false),
            inner_read: Mutex::new(Some(read_fn)),
            admitted: Mutex::new(HashMap::new()),
            admitted_bytes: AtomicI64::new(0),
            close_hooks: Mutex::new(Vec::new()),
            admission: Mutex::new(None),
            close_reason: Mutex::new(None),
        }
    }

    pub fn add_close_hook(&self, f: impl FnOnce() + Send + Sync + 'static) {
        let mut hooks = self.close_hooks.lock();
        if self.closed.load(Ordering::SeqCst) {
            drop(hooks);
            f();
        } else {
            hooks.push(Box::new(f));
        }
    }

    pub fn length(&self) -> i64 {
        self.length
    }

    pub fn budget(&self) -> i64 {
        self.budget
    }

    pub fn admitted_bytes(&self) -> i64 {
        self.admitted_bytes.load(Ordering::SeqCst)
    }

    /// Admit pieces for `[offset, offset+want)` then read. When
    /// `piece_length == 0`, skips admission and only enforces the soft
    /// remaining-budget counter used by the stub reader path.
    pub fn read_at(&self, buf: &mut [u8], offset: i64) -> std::io::Result<usize> {
        if self.closed.load(Ordering::SeqCst) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "preview reader closed",
            ));
        }
        if offset < 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "invalid preview offset",
            ));
        }
        if offset >= self.length || buf.is_empty() {
            return Ok(0);
        }

        let max_read = (self.length - offset) as usize;
        let want = buf.len().min(max_read);

        if let Some(admission) = self.admission.lock().as_ref() {
            if !admission.cached_only {
                let allowed = admission
                    .server
                    .upgrade()
                    .is_some_and(|s| s.payload_transfers_allowed.load(Ordering::SeqCst));
                self.admit_range(
                    admission.piece_length,
                    admission.file_offset,
                    offset,
                    want as i64,
                    |p| admission.storage.piece_complete(p as usize),
                    allowed,
                )?;
                let demand = self
                    .admitted
                    .lock()
                    .keys()
                    .filter(|&&p| !admission.storage.piece_complete(p as usize))
                    .map(|&p| (p, wt_types::PiecePriority::Now))
                    .collect();
                admission.coordinator.set_preview_demand(demand);
                if let Some(server) = admission.server.upgrade() {
                    let effective = admission.coordinator.effective();
                    crate::playback::PlaybackHost::set_playback_storage_demand(
                        server.as_ref(),
                        &admission.torrent_id,
                        &effective,
                    );
                    crate::playback::PlaybackHost::apply_piece_priorities(
                        server.as_ref(),
                        &admission.torrent_id,
                        &effective,
                    );
                    crate::playback::PlaybackHost::set_request_focus(
                        server.as_ref(),
                        &admission.torrent_id,
                        admission.file_offset + offset,
                    );
                    crate::playback::PlaybackHost::refocus_requests(
                        server.as_ref(),
                        &admission.torrent_id,
                    );
                    let _ = crate::playback::PlaybackHost::nudge_urgent(
                        server.as_ref(),
                        &admission.torrent_id,
                    );
                }
            }
            let lock = self.inner_read.lock();
            let read = lock
                .as_ref()
                .ok_or_else(|| std::io::Error::other("preview closed"))?(
                &mut buf[..want], offset
            );
            return read.map_err(|err| self.annotate_closed(err));
        }

        // Soft remaining-budget gate (matches Go errPreviewBudget on overshoot).
        let mut curr_budget = self.budget_remaining.load(Ordering::SeqCst);
        loop {
            if curr_budget <= 0 {
                return Err(std::io::Error::other("preview byte budget exceeded"));
            }
            let allowed = want.min(curr_budget as usize);
            let next_budget = curr_budget - allowed as i64;
            match self.budget_remaining.compare_exchange_weak(
                curr_budget,
                next_budget,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => {
                    let lock = self.inner_read.lock();
                    if let Some(ref reader) = *lock {
                        return reader(&mut buf[..allowed], offset);
                    }
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::BrokenPipe,
                        "preview closed",
                    ));
                }
                Err(b) => curr_budget = b,
            }
        }
    }

    /// Record piece admissions for a read range (engine wiring calls this
    /// before publishing High demand).
    pub fn admit_range(
        &self,
        piece_length: i64,
        file_torrent_offset: i64,
        offset: i64,
        length: i64,
        piece_complete: impl Fn(u32) -> bool,
        payload_allowed: bool,
    ) -> std::io::Result<Vec<u32>> {
        if self.closed.load(Ordering::SeqCst) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "preview closed",
            ));
        }
        let mut admitted = self.admitted.lock();
        let mut admitted_n = self.admitted_bytes.load(Ordering::SeqCst);
        let new_pieces = admit_preview_range(
            &mut admitted,
            &mut admitted_n,
            self.budget,
            piece_length,
            file_torrent_offset,
            self.length,
            offset,
            length,
            piece_complete,
            payload_allowed,
        )?;
        self.admitted_bytes.store(admitted_n, Ordering::SeqCst);
        Ok(new_pieces)
    }

    pub fn close(&self) {
        self.close_with_reason("closed by caller");
    }

    /// Close and record who asked, so a cancelled read can say why.
    pub fn close_with_reason(&self, reason: &'static str) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        *self.close_reason.lock() = Some(reason);
        let hooks = std::mem::take(&mut *self.close_hooks.lock());
        for hook in hooks {
            hook();
        }
        let mut lock = self.inner_read.lock();
        *lock = None;
        self.admitted.lock().clear();
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    pub fn close_reason(&self) -> Option<&'static str> {
        *self.close_reason.lock()
    }

    /// Reads interrupted by a close name the closer in the error text.
    fn annotate_closed(&self, err: std::io::Error) -> std::io::Error {
        if err.kind() == std::io::ErrorKind::Interrupted && self.closed.load(Ordering::SeqCst) {
            let reason = self.close_reason().unwrap_or("unspecified");
            return std::io::Error::new(
                std::io::ErrorKind::Interrupted,
                format!("preview cancelled: {reason}"),
            );
        }
        err
    }
}

pub fn open_preview(
    server: &crate::server::EngineServer,
    id: &str,
    index: usize,
) -> std::io::Result<Arc<PreviewReader>> {
    let mut active = server.preview_active.lock();
    if let Some(prev) = active.take() {
        prev.close_with_reason("superseded by open_preview");
    }

    let records = server.records.read();
    let rec_arc = records
        .get(id)
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "torrent not found"))?
        .clone();
    drop(records);
    let mut rec = rec_arc.write();
    rec.refresh();

    if rec.error.is_some() {
        return Err(std::io::Error::other("torrent unavailable for preview"));
    }
    if rec.torrent.info().is_none() {
        return Err(std::io::Error::other("metadata not ready"));
    }
    if index >= rec.files.len() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "invalid file index",
        ));
    }

    let file = rec
        .torrent
        .files()
        .get(index)
        .cloned()
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "file not found"))?;
    let length = file.length();
    let file_verified = file.bytes_completed() >= length;
    if rec.paused && !file_verified {
        return Err(std::io::Error::other("torrent is paused"));
    }
    let storage = server
        .storage
        .get_storage(&rec.info_hash)
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "storage unavailable"))?;

    let is_temp;
    if !rec.configured {
        storage
            .attach_preview_temp(index)
            .map_err(|e| std::io::Error::other(e.to_string()))?;
        is_temp = true;
        if server.payload_transfers_allowed.load(Ordering::SeqCst) {
            rec.torrent.allow_data_download();
        }
    } else {
        is_temp = false;
        let selected = rec.selected.contains(&index);
        if !selected && !file_verified {
            return Err(std::io::Error::other("file is not selected for preview"));
        }
    }
    crate::jni::log_info(&format!(
        "open_preview id={id} index={index} len={length} temp={is_temp} configured={} peers={}",
        rec.configured,
        rec.torrent.stats().total_peers,
    ));

    let mut reader_inner = file.new_reader();
    reader_inner.set_readahead(0);
    reader_inner.set_responsive();
    let cancel_token = wt_core::CancelToken(Arc::new(std::sync::atomic::AtomicBool::new(false)));
    reader_inner.set_cancel(cancel_token.clone());
    let backend = Arc::new(Mutex::new(reader_inner));
    let storage_clone = storage.clone();
    let server_weak = server.self_ref.get().cloned().unwrap_or_default();
    let file_offset = file.offset();
    let backend_read = backend.clone();

    let cached_only = rec.direct_play && server.playback_readers.lock().values()
        .any(|reader| reader.id == id && !reader.is_closed());
    let torrent_read = rec.torrent.clone();
    let cancel_read = cancel_token.clone();
    let read_fn: PreviewReadFn = Box::new(move |buf, offset| {
        let abs = file_offset + offset;
        let want = buf.len();
        // Check if verified storage tail / cached pieces can fulfill the read without network:
        let (got, ok) = storage_clone.read_verified_preview(buf, abs);
        if ok && got == want || cached_only && got > 0 {
            return Ok(got);
        }
        if cached_only {
            return Err(std::io::Error::other("preview range is not cached yet"));
        }
        // Unverified bytes require payload transfers to be allowed:
        if let Some(srv) = server_weak.upgrade() {
            if !srv.payload_transfers_allowed.load(Ordering::SeqCst) {
                return Err(std::io::Error::other("payload transfers are paused"));
            }
        }
        if storage_clone.stream_mode() {
            let piece_len = torrent_read.info().map_or(0, |i| i.piece_length);
            if piece_len > 0 {
                let mut n = 0usize;
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(18);
                let mut last_nudge = std::time::Instant::now();
                while n < buf.len() {
                    if cancel_read.is_cancelled() || torrent_read.closed() {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Interrupted,
                            "preview cancelled",
                        ));
                    }
                    let cur_abs = abs + n as i64;
                    let piece = (cur_abs / piece_len) as u32;
                    let max_chunk = (buf.len() - n).min((piece_len - (cur_abs % piece_len)) as usize);
                    let (got_s, ready, res) =
                        storage_clone.read_verified_stream(&mut buf[n..n + max_chunk], cur_abs);
                    res.map_err(|e| std::io::Error::other(e.to_string()))?;
                    if got_s > 0 {
                        n += got_s;
                        continue;
                    }
                    if std::time::Instant::now() >= deadline {
                        return if n > 0 {
                            Ok(n)
                        } else {
                            Err(std::io::Error::new(
                                std::io::ErrorKind::TimedOut,
                                "preview stream read timed out",
                            ))
                        };
                    }
                    if last_nudge.elapsed() >= std::time::Duration::from_millis(200) {
                        torrent_read.update_piece_completion(piece);
                        torrent_read.nudge_urgent_requests();
                        torrent_read.nudge_urgent_peers();
                        last_nudge = std::time::Instant::now();
                    }
                    if let Some(ready) = ready {
                        let gen = ready.generation();
                        ready.wait_timeout(gen, std::time::Duration::from_millis(25));
                    } else {
                        std::thread::sleep(std::time::Duration::from_millis(25));
                    }
                }
                return Ok(n);
            }
        }
        let mut be = backend_read.lock();
        use std::io::{Read, Seek, SeekFrom};
        be.seek(SeekFrom::Start(offset.max(0) as u64))?;
        let mut n = 0;
        while n < buf.len() {
            let got = be.read(&mut buf[n..])?;
            if got == 0 {
                break;
            }
            n += got;
        }
        Ok(n)
    });

    let piece_length = rec
        .torrent
        .info()
        .map(|info| info.piece_length)
        .ok_or_else(|| std::io::Error::other("metadata not ready"))?;
    let effective_budget = PREVIEW_BUDGET_BYTES
        .max(piece_length.saturating_mul(6))
        .max(32 * 1024 * 1024);
    let reader = Arc::new(PreviewReader::with_budget(
        index,
        length,
        effective_budget,
        read_fn,
    ));
    *reader.admission.lock() = Some(PreviewAdmission {
        piece_length,
        file_offset,
        storage: storage.clone(),
        server: server.self_ref.get().cloned().unwrap_or_default(),
        torrent_id: id.to_owned(),
        coordinator: rec.playback_coordinator.clone(),
        cached_only,
    });
    let coordinator_hook = rec.playback_coordinator.clone();
    let id_hook = id.to_owned();
    let reader_weak = Arc::downgrade(&reader);
    let server_weak_hook = server.self_ref.get().cloned().unwrap_or_default();
    let rec_weak = Arc::downgrade(&rec_arc);
    let storage_hook = storage.clone();
    let torrent_hook = rec.torrent.clone();
    let handle = server.next_reader_id.fetch_add(1, Ordering::SeqCst);

    let cancel_hook = cancel_token;

    reader.add_close_hook(move || {
        let reason = reader_weak.upgrade().and_then(|r| r.close_reason()).unwrap_or("unspecified");
        crate::jni::log_info(&format!(
            "preview closed id={id_hook} index={index} temp={is_temp} reason={reason}"
        ));
        cancel_hook.cancel();
        torrent_hook.bump_change();
        coordinator_hook.set_preview_demand(HashMap::new());
        if let Some(srv) = server_weak_hook.upgrade() {
            let effective = coordinator_hook.effective();
            crate::playback::PlaybackHost::set_playback_storage_demand(
                srv.as_ref(),
                &id_hook,
                &effective,
            );
            crate::playback::PlaybackHost::apply_piece_priorities(
                srv.as_ref(),
                &id_hook,
                &effective,
            );
            let mut act = srv.preview_active.lock();
            if let Some(r) = reader_weak.upgrade() {
                if act.as_ref().is_some_and(|curr| Arc::ptr_eq(curr, &r)) {
                    *act = None;
                }
            } else {
                *act = None;
            }
            srv.preview_readers.lock().remove(&handle);
        }
        if is_temp {
            storage_hook.release_preview_temp();
            for piece in 0..torrent_hook.num_pieces() {
                torrent_hook.update_piece_completion(piece);
            }
            if let Some(rec_arc) = rec_weak.upgrade() {
                let rec_r = rec_arc.read();
                if !rec_r.configured && !rec_r.direct_play {
                    rec_r.torrent.disallow_data_download();
                }
            }
        }
    });

    *active = Some(reader.clone());
    server.preview_readers.lock().insert(handle, reader.clone());

    let weak_exp = Arc::downgrade(&reader);
    std::thread::spawn(move || {
        std::thread::sleep(PREVIEW_SESSION_TIMEOUT);
        if let Some(r) = weak_exp.upgrade() {
            r.close_with_reason("session timeout");
        }
    });

    Ok(reader)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn preview_dir_nests_under_dot_preview() {
        let p = preview_dir("/data", "abc");
        assert_eq!(p, PathBuf::from("/data/.preview/abc"));
    }

    #[test]
    fn budget_enforced_across_seeks() {
        let data = vec![0u8; 1024];
        let reader = PreviewReader::with_budget(
            0,
            data.len() as i64,
            100,
            Box::new(move |buf, off| {
                let off = off as usize;
                let n = buf.len().min(data.len().saturating_sub(off));
                buf[..n].copy_from_slice(&data[off..off + n]);
                Ok(n)
            }),
        );
        let mut buf = [0u8; 60];
        assert_eq!(reader.read_at(&mut buf, 0).unwrap(), 60);
        let mut buf = [0u8; 60];
        assert_eq!(reader.read_at(&mut buf, 60).unwrap(), 40);
        let mut buf = [0u8; 1];
        let err = reader.read_at(&mut buf, 100).unwrap_err();
        assert!(err.to_string().contains("budget"));
    }

    #[test]
    fn close_is_idempotent() {
        let reader = PreviewReader::new(0, 10, Box::new(|buf, _| Ok(buf.len())));
        reader.close();
        reader.close();
        assert!(reader.is_closed());
        assert!(reader.read_at(&mut [0u8; 4], 0).is_err());
    }

    #[test]
    fn admit_range_skips_complete_and_caps_budget() {
        let mut admitted = HashMap::new();
        let mut admitted_n = 0i64;
        let complete = |i: u32| i == 1;
        // piece 0 needs admission (1 MiB), piece 1 complete, piece 2 would exceed.
        let budget = 1024 * 1024;
        let new_pieces = admit_preview_range(
            &mut admitted,
            &mut admitted_n,
            budget,
            1 << 20,
            0,
            4 << 20,
            0,
            2 << 20,
            complete,
            true,
        )
        .unwrap();
        assert_eq!(new_pieces, vec![0]);
        let err = admit_preview_range(
            &mut admitted,
            &mut admitted_n,
            budget,
            1 << 20,
            0,
            4 << 20,
            2 << 20,
            1 << 20,
            complete,
            true,
        )
        .unwrap_err();
        assert!(err.to_string().contains("budget"));
    }

    #[test]
    fn admit_range_respects_payload_gate() {
        let mut admitted = HashMap::new();
        let mut admitted_n = 0;
        let err = admit_preview_range(
            &mut admitted,
            &mut admitted_n,
            PREVIEW_BUDGET_BYTES,
            1 << 20,
            0,
            4 << 20,
            0,
            1 << 20,
            |_| false,
            false,
        )
        .unwrap_err();
        assert!(err.to_string().contains("paused"));
    }

    #[test]
    fn arc_close_shared() {
        let reader = Arc::new(PreviewReader::new(
            0,
            8,
            Box::new(|buf, _| {
                buf.fill(7);
                Ok(buf.len())
            }),
        ));
        let mut buf = [0u8; 4];
        assert_eq!(reader.read_at(&mut buf, 0).unwrap(), 4);
        reader.close();
        assert!(reader.read_at(&mut buf, 0).is_err());
    }
}
