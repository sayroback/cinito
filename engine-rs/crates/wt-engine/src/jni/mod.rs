//! JNI exports matching `engine-go/main.go` and `EngineHost.kt`.
//!
//! Nine `Java_webtor_app_EngineHost_*` symbols are exported for
//! `System.loadLibrary("engine")`. A single global [`EngineHostApi`] is
//! installed by `startEngine` (via a registered factory or the unwired stub).

pub mod host_api;
pub mod server_impl;

pub use host_api::EngineHostApi;
pub use server_impl::AndroidEngineHost;

/// App version stamped by `build-android.sh` (`WEBTOR_APP_VERSION`), else `"dev"`.
pub fn webtor_app_version() -> &'static str {
    option_env!("WEBTOR_APP_VERSION").unwrap_or("dev")
}

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, OnceLock};

use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;

/// Factory that builds the [`EngineHostApi`] implementation used on Android.
///
/// Registered once from `lib.rs` so `startEngine` can construct the
/// `EngineServer`-backed host. When unset, an unwired stub
/// is used and `start_engine` returns `1`.
pub type EngineHostFactory = fn() -> Arc<dyn EngineHostApi>;

static ENGINE_FACTORY: OnceLock<EngineHostFactory> = OnceLock::new();
static ENGINE: OnceLock<Arc<dyn EngineHostApi>> = OnceLock::new();

/// Register the host-api factory. First registration wins.
pub fn register_engine_factory(factory: EngineHostFactory) -> bool {
    ENGINE_FACTORY.set(factory).is_ok()
}

/// Install a concrete host API (tests / early wiring). First call wins.
pub fn install_engine(api: Arc<dyn EngineHostApi>) -> bool {
    ENGINE.set(api).is_ok()
}

/// Current global engine, if `startEngine` (or [`install_engine`]) has run.
pub fn current_engine() -> Option<&'static Arc<dyn EngineHostApi>> {
    ENGINE.get()
}

fn engine_or_init() -> &'static Arc<dyn EngineHostApi> {
    ENGINE.get_or_init(|| match ENGINE_FACTORY.get() {
        Some(factory) => factory(),
        None => Arc::new(AndroidEngineHost::new()) as Arc<dyn EngineHostApi>,
    })
}

pub(crate) fn log_info(msg: &str) {
    #[cfg(target_os = "android")]
    {
        log::info!(target: "webtor-engine", "{msg}");
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[webtor-engine] {msg}");
    }
}

pub(crate) fn log_error(msg: &str) {
    #[cfg(target_os = "android")]
    {
        log::error!(target: "webtor-engine", "{msg}");
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[webtor-engine] ERROR {msg}");
    }
}

pub(crate) fn log_warn(msg: &str) {
    #[cfg(target_os = "android")]
    {
        log::warn!(target: "webtor-engine", "{msg}");
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[webtor-engine] WARN {msg}");
    }
}

/// High-frequency diagnostics: `log` facade only, so host builds stay quiet
/// unless a logger is installed.
pub(crate) fn log_debug(msg: &str) {
    log::debug!(target: "webtor-engine", "{msg}");
}

/// Text of a caught panic payload, for the error log.
fn panic_text(payload: &(dyn std::any::Any + Send)) -> String {
    payload
        .downcast_ref::<&str>()
        .map(|s| (*s).to_owned())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "non-string panic payload".to_owned())
}

#[cfg(target_os = "android")]
fn init_android_logging() {
    static INIT: std::sync::Once = std::sync::Once::new();
    INIT.call_once(|| {
        let _ = android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("webtor-engine"),
        );
        // catch_unwind keeps the process alive but drops the message; log it.
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            log::error!("panic: {info}");
            previous(info);
        }));
    });
}

#[cfg(not(target_os = "android"))]
fn init_android_logging() {}

fn java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> String {
    if value.is_null() {
        return String::new();
    }
    match env.get_string(&value) {
        Ok(s) => s.into(),
        Err(err) => {
            log_error(&format!("java string read failed: {err}"));
            String::new()
        }
    }
}

fn java_bytes(env: &mut JNIEnv<'_>, value: &JByteArray<'_>) -> Vec<u8> {
    if value.is_null() {
        return Vec::new();
    }
    let len = match env.get_array_length(value) {
        Ok(n) => n as usize,
        Err(err) => {
            log_error(&format!("java byte array length failed: {err}"));
            return Vec::new();
        }
    };
    if len == 0 {
        return Vec::new();
    }
    let mut buf = vec![0i8; len];
    if let Err(err) = env.get_byte_array_region(value, 0, &mut buf) {
        log_error(&format!("java byte array read failed len={len}: {err}"));
        return Vec::new();
    }
    buf.into_iter().map(|b| b as u8).collect()
}

fn new_java_bytes(env: &mut JNIEnv<'_>, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(arr) => arr.into_raw(),
        Err(err) => {
            log_error(&format!("java byte array alloc failed len={}: {err}", data.len()));
            std::ptr::null_mut()
        }
    }
}

const MAX_READ_SIZE: jint = 1024 * 1024;

/// `Java_webtor_app_EngineHost_startEngine`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_startEngine(
    mut env: JNIEnv,
    _host: JObject,
    download_dir: JByteArray,
    max_peers: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        init_android_logging();
        log_info("initializing native Rust engine");
        let dir = java_bytes(&mut env, &download_dir);
        engine_or_init().start_engine(&dir, max_peers)
    }));
    match result {
        Ok(code) => code,
        Err(payload) => {
            log_error(&format!("startEngine panicked: {}", panic_text(&*payload)));
            1
        }
    }
}

/// `Java_webtor_app_EngineHost_requestEngine`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_requestEngine(
    mut env: JNIEnv,
    _host: JObject,
    method: JString,
    path: JString,
    body: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let method = java_string(&mut env, method);
        let path = java_string(&mut env, path);
        let body = java_bytes(&mut env, &body);
        log_debug(&format!("requestEngine enter {method} {path} bodyLen={}", body.len()));
        let payload = match current_engine() {
            Some(engine) => engine.request_engine(&method, &path, &body),
            None => {
                log_warn(&format!("requestEngine {method} {path}: engine not started, returning 503"));
                b"503\n{\"error\":\"engine is starting\"}".to_vec()
            }
        };
        let status = payload.split(|&b| b == b'\n').next().unwrap_or_default();
        log_debug(&format!("requestEngine exit {method} {path} status={}", String::from_utf8_lossy(status)));
        new_java_bytes(&mut env, &payload)
    }));
    match result {
        Ok(arr) => arr,
        Err(payload) => {
            log_error(&format!("requestEngine panicked: {}", panic_text(&*payload)));
            new_java_bytes(&mut env, b"500\n{\"error\":\"native panic\"}")
        }
    }
}

/// `Java_webtor_app_EngineHost_waitForEngineEvent`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_waitForEngineEvent(
    _env: JNIEnv,
    _host: JObject,
    after_version: jlong,
    timeout_millis: jlong,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| match current_engine() {
        Some(engine) => engine.wait_for_engine_event(after_version, timeout_millis),
        None => {
            std::thread::sleep(std::time::Duration::from_millis(250));
            after_version
        }
    }));
    match result {
        Ok(v) => v,
        Err(payload) => {
            log_error(&format!("waitForEngineEvent panicked: {}", panic_text(&*payload)));
            after_version
        }
    }
}

/// `Java_webtor_app_EngineHost_openPlayback`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_openPlayback(
    mut env: JNIEnv,
    _host: JObject,
    id: JString,
    index: jint,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let id = java_string(&mut env, id);
        log_debug(&format!("openPlayback enter id={id} index={index}"));
        let Some(engine) = current_engine() else {
            log_error(&format!("openPlayback id={id} index={index}: engine not started"));
            return 0;
        };
        engine.open_playback(&id, index)
    }));
    match result {
        Ok(handle) => {
            log_debug(&format!("openPlayback exit index={index} handle={handle}"));
            handle
        }
        Err(payload) => {
            log_error(&format!("openPlayback panicked: {}", panic_text(&*payload)));
            0
        }
    }
}

/// `Java_webtor_app_EngineHost_readPlayback`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_readPlayback(
    env: JNIEnv,
    _host: JObject,
    handle: jlong,
    offset: jlong,
    size: jint,
    target: JByteArray,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if target.is_null() {
            return -1;
        }
        let Ok(array_len) = env.get_array_length(&target) else {
            return -1;
        };
        if !(0..=MAX_READ_SIZE).contains(&size) || size > array_len {
            log_warn(&format!("readPlayback handle={handle} invalid size={size} arrayLen={array_len}"));
            return -1;
        }
        let Some(engine) = current_engine() else {
            log_warn(&format!("readPlayback handle={handle}: engine not started"));
            return -1;
        };
        let mut buf = vec![0u8; size as usize];
        let n = engine.read_playback(handle, offset, &mut buf);
        if n > 0 {
            // One buffer: reinterpret u8 → i8 for SetByteArrayRegion (same layout).
            let signed =
                unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<i8>(), n as usize) };
            if let Err(err) = env.set_byte_array_region(&target, 0, signed) {
                log_error(&format!("readPlayback handle={handle} copy-out failed n={n}: {err}"));
                return -1;
            }
        }
        n
    }));
    match result {
        Ok(n) => n,
        Err(payload) => {
            log_error(&format!("readPlayback panicked: {}", panic_text(&*payload)));
            -1
        }
    }
}

/// `Java_webtor_app_EngineHost_closePlayback`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_closePlayback(
    _env: JNIEnv,
    _host: JObject,
    handle: jlong,
) {
    log_debug(&format!("closePlayback handle={handle}"));
    if let Err(payload) = catch_unwind(AssertUnwindSafe(|| {
        if let Some(engine) = current_engine() {
            engine.close_playback(handle);
        }
    })) {
        log_error(&format!("closePlayback handle={handle} panicked: {}", panic_text(&*payload)));
    }
}

/// `Java_webtor_app_EngineHost_openPreview`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_openPreview(
    mut env: JNIEnv,
    _host: JObject,
    id: JString,
    index: jint,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let id = java_string(&mut env, id);
        log_debug(&format!("openPreview enter id={id} index={index}"));
        let Some(engine) = current_engine() else {
            log_error(&format!("openPreview id={id} index={index}: engine not started"));
            return 0;
        };
        engine.open_preview(&id, index)
    }));
    match result {
        Ok(handle) => {
            log_debug(&format!("openPreview exit index={index} handle={handle}"));
            handle
        }
        Err(payload) => {
            log_error(&format!("openPreview panicked: {}", panic_text(&*payload)));
            0
        }
    }
}

/// `Java_webtor_app_EngineHost_readPreview`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_readPreview(
    env: JNIEnv,
    _host: JObject,
    handle: jlong,
    offset: jlong,
    size: jint,
    target: JByteArray,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if target.is_null() {
            return -1;
        }
        let Ok(array_len) = env.get_array_length(&target) else {
            return -1;
        };
        if !(0..=MAX_READ_SIZE).contains(&size) || size > array_len {
            log_warn(&format!("readPreview handle={handle} invalid size={size} arrayLen={array_len}"));
            return -1;
        }
        let Some(engine) = current_engine() else {
            log_warn(&format!("readPreview handle={handle}: engine not started"));
            return -1;
        };
        let mut buf = vec![0u8; size as usize];
        let n = engine.read_preview(handle, offset, &mut buf);
        if n < 0 {
            // server_impl logs the reader error text ("preview read: ...").
            return -1;
        }
        if n > 0 {
            let signed =
                unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<i8>(), n as usize) };
            if let Err(err) = env.set_byte_array_region(&target, 0, signed) {
                log_error(&format!("readPreview handle={handle} copy-out failed n={n}: {err}"));
                return -1;
            }
        }
        n
    }));
    match result {
        Ok(n) => n,
        Err(payload) => {
            log_error(&format!("readPreview panicked: {}", panic_text(&*payload)));
            -1
        }
    }
}

/// `Java_webtor_app_EngineHost_closePreview`
#[no_mangle]
pub extern "system" fn Java_webtor_app_EngineHost_closePreview(
    _env: JNIEnv,
    _host: JObject,
    handle: jlong,
) {
    log_debug(&format!("closePreview handle={handle}"));
    if let Err(payload) = catch_unwind(AssertUnwindSafe(|| {
        if let Some(engine) = current_engine() {
            engine.close_preview(handle);
        }
    })) {
        log_error(&format!("closePreview handle={handle} panicked: {}", panic_text(&*payload)));
    }
}

/// Exported symbol names expected in `libengine.so` (for tests).
pub const JNI_EXPORT_SYMBOLS: &[&str] = &[
    "Java_webtor_app_EngineHost_startEngine",
    "Java_webtor_app_EngineHost_requestEngine",
    "Java_webtor_app_EngineHost_waitForEngineEvent",
    "Java_webtor_app_EngineHost_openPlayback",
    "Java_webtor_app_EngineHost_readPlayback",
    "Java_webtor_app_EngineHost_closePlayback",
    "Java_webtor_app_EngineHost_openPreview",
    "Java_webtor_app_EngineHost_readPreview",
    "Java_webtor_app_EngineHost_closePreview",
];
