// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! JNI bridge between Kotlin (com.crate.engine.Engine) and the crate engine's
//! C API (crate-ffi). One Engine object owns one machine id; the registry
//! below owns the machines. crate allows one machine per process, enforced
//! here by failing a second create while one exists.
//!
//! Engine threads call back on their own threads: each callback re-attaches
//! to the JVM and invokes the Engine's onConsoleOutput / onStateChanged /
//! onFrame methods. Context pointers handed to C are registry ids cast to
//! pointers, so nothing dangles when a machine is destroyed: a late callback
//! simply finds no entry.

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_ushort, c_void};
use std::sync::{LazyLock, Mutex};
use std::sync::atomic::{AtomicI64, Ordering};

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};

// Links the engine in: calling crate_ffi's own symbol (rather than only
// declaring the C API in the extern block below) marks the crate used, so
// rustc passes its rlib to the linker and the crate_* objects land in this
// cdylib. Without a real reference the crate_* imports stay undefined and
// System.loadLibrary would fail on device; android-build-engine.sh guards it.
use crate_ffi::crate_api_version;

mod install;

// ------------------------------------------------------------------ crate.h
// Signatures mirror include/crate.h (checked against the no_mangle
// definitions in crate-ffi); the callback structs mirror the #[repr(C)]
// ConsoleCallbacks/DisplayCallbacks/CFrame there.

// Opaque engine types.
enum CrateConfig {}
enum CrateMachine {}

type CrateLogFn = Option<unsafe extern "C" fn(*mut c_void, c_int, *const c_char)>;
type ConsoleOutFn = Option<unsafe extern "C" fn(*mut c_void, u32, *const u8, usize)>;
type DisplayFrameFn = Option<unsafe extern "C" fn(*mut c_void, u32, *const CrateFrame)>;
type StateFn = Option<unsafe extern "C" fn(*mut c_void, c_int, c_int)>;

#[repr(C)]
struct ConsoleCallbacks {
    size: usize,
    context: *mut c_void,
    output: ConsoleOutFn,
    input_ready: Option<unsafe extern "C" fn(*mut c_void, u32)>,
}

#[repr(C)]
struct CrateDisplayMode {
    width: u32,
    height: u32,
}

const DISK_READ_ONLY: u32 = 1;

#[repr(C)]
struct CrateFrame {
    width: u32,
    height: u32,
    stride: u32,
    pixels: *const u8,
    dirty_x: u32,
    dirty_y: u32,
    dirty_width: u32,
    dirty_height: u32,
}

#[repr(C)]
struct DisplayCallbacks {
    size: usize,
    context: *mut c_void,
    frame: DisplayFrameFn,
}

// crate_status; CRATE_OK is 0.
const CPU_JIT: c_int = 1;
const CONSOLE_SERIAL: c_int = 0;
const GPU_2D: u32 = 0;
const INPUT_KEYBOARD: u32 = 1 << 0;
const INPUT_TABLET: u32 = 1 << 1;

unsafe extern "C" {
    fn crate_last_error() -> *const c_char;
    fn crate_set_log_callback(f: CrateLogFn, context: *mut c_void, max: c_int);
    fn crate_config_new(machine: *const c_char) -> *mut CrateConfig;
    fn crate_config_free(config: *mut CrateConfig);
    fn crate_config_set_cpu_backend(config: *mut CrateConfig, backend: c_int) -> c_int;
    fn crate_config_set_cpus(config: *mut CrateConfig, count: u32) -> c_int;
    fn crate_config_set_memory(config: *mut CrateConfig, bytes: u64) -> c_int;
    fn crate_config_set_kernel(config: *mut CrateConfig, path: *const c_char) -> c_int;
    fn crate_config_set_initrd(config: *mut CrateConfig, path: *const c_char) -> c_int;
    fn crate_config_set_cmdline(config: *mut CrateConfig, cmdline: *const c_char) -> c_int;
    fn crate_config_add_console(
        config: *mut CrateConfig,
        kind: c_int,
        cbs: *const ConsoleCallbacks,
        index: *mut u32,
    ) -> c_int;
    fn crate_config_add_disk(config: *mut CrateConfig, path: *const c_char, flags: u32) -> c_int;
    fn crate_config_add_network(config: *mut CrateConfig, index: *mut u32) -> c_int;
    fn crate_config_add_display(
        config: *mut CrateConfig,
        gpu: u32,
        cbs: *const DisplayCallbacks,
        index: *mut u32,
    ) -> c_int;
    fn crate_config_set_display_modes(
        config: *mut CrateConfig,
        display: u32,
        modes: *const CrateDisplayMode,
        count: usize,
    ) -> c_int;
    fn crate_config_add_input(config: *mut CrateConfig, devices: u32) -> c_int;
    fn crate_config_set_state_callback(
        config: *mut CrateConfig,
        f: StateFn,
        context: *mut c_void,
    ) -> c_int;
    fn crate_machine_create(config: *const CrateConfig, machine: *mut *mut CrateMachine)
        -> c_int;
    fn crate_machine_start(machine: *mut CrateMachine) -> c_int;
    fn crate_machine_kill(machine: *mut CrateMachine) -> c_int;
    fn crate_machine_free(machine: *mut CrateMachine);
    fn crate_console_write(
        machine: *mut CrateMachine,
        console: u32,
        bytes: *const u8,
        length: usize,
    ) -> usize;
    fn crate_display_set_active(machine: *mut CrateMachine, display: u32, active: bool);
    fn crate_input_key(machine: *mut CrateMachine, code: c_ushort, down: bool);
    fn crate_input_pointer(machine: *mut CrateMachine, x: u32, y: u32, buttons: u32);
    fn crate_input_scroll(machine: *mut CrateMachine, dx: i32, dy: i32);
}

// ------------------------------------------------------------- android log

const ANDROID_LOG_ERROR: c_int = 6;
const ANDROID_LOG_WARN: c_int = 5;
const ANDROID_LOG_INFO: c_int = 4;
const ANDROID_LOG_DEBUG: c_int = 3;

unsafe extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

unsafe extern "C" fn android_log(_context: *mut c_void, level: c_int, message: *const c_char) {
    let prio = match level {
        0 => ANDROID_LOG_ERROR,
        1 => ANDROID_LOG_WARN,
        2 => ANDROID_LOG_INFO,
        _ => ANDROID_LOG_DEBUG,
    };
    unsafe {
        __android_log_write(
            prio,
            c"crate".as_ptr(),
            if message.is_null() {
                c"(null)".as_ptr()
            } else {
                message
            },
        );
    }
}

// ---------------------------------------------------------------- registry

struct Callbacks {
    // Raw JavaVM pointer (JavaVM::get_java_vm_pointer). JavaVM is a plain
    // pointer wrapper with no Drop; reconstruct with from_raw per callback.
    // usize so the struct stays Send+Sync.
    jvm_ptr: usize,
    target: GlobalRef,
}

struct Machine {
    ptr: usize, // *mut CrateMachine
    cbs: Callbacks,
}

struct Registry {
    machines: HashMap<i64, Machine>,
    last_error: String,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(|| {
    Mutex::new(Registry {
        machines: HashMap::new(),
        last_error: String::new(),
    })
});
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn machine_ptr(id: i64) -> *mut CrateMachine {
    REGISTRY
        .lock()
        .unwrap()
        .machines
        .get(&id)
        .map(|m| m.ptr as *mut CrateMachine)
        .unwrap_or(std::ptr::null_mut())
}

fn callbacks_for(id: i64) -> Option<Callbacks> {
    REGISTRY.lock().unwrap().machines.get(&id).map(|m| Callbacks {
        jvm_ptr: m.cbs.jvm_ptr,
        target: m.cbs.target.clone(),
    })
}

/// Rebuild a JavaVM handle from a stored pointer. The pointer comes from
/// JavaVM::get_java_vm_pointer and stays valid for the process lifetime.
fn with_vm<T>(ptr: usize, f: impl FnOnce(&JavaVM) -> T) -> Option<T> {
    if ptr == 0 {
        return None;
    }
    // SAFETY: pointer was a valid JavaVM when stored; the VM outlives us.
    let vm = unsafe { JavaVM::from_raw(ptr as *mut _) }.ok()?;
    Some(f(&vm))
}

fn fail(status: c_int) -> jint {
    let msg = unsafe {
        let p = crate_last_error();
        if p.is_null() {
            format!("crate error {status}")
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    };
    REGISTRY.lock().unwrap().last_error = msg;
    status as jint
}

fn call_void(cbs: &Callbacks, name: &str, sig: &str, args: &[JValue]) {
    with_vm(cbs.jvm_ptr, |vm| {
        let Ok(mut env) = vm.attach_current_thread() else {
            return;
        };
        if env
            .call_method(cbs.target.as_obj(), name, sig, args)
            .is_err()
        {
            return;
        }
        let _ = (|| -> jni::errors::Result<()> {
            if env.exception_check()? {
                env.exception_describe()?;
                env.exception_clear()?;
            }
            Ok(())
        })();
    });
}

/// jbyte is i8; reinterpret without copying.
fn as_i8(bytes: &[u8]) -> &[i8] {
    unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) }
}

unsafe extern "C" fn console_out(
    context: *mut c_void,
    _console: u32,
    bytes: *const u8,
    length: usize,
) {
    let Some(cbs) = callbacks_for(context as i64) else {
        return;
    };
    let data = unsafe { std::slice::from_raw_parts(bytes, length).to_vec() };
    with_vm(cbs.jvm_ptr, |vm| {
        let Ok(env) = vm.attach_current_thread() else {
            return;
        };
        let Ok(arr) = env.new_byte_array(data.len() as i32) else {
            return;
        };
        if env.set_byte_array_region(&arr, 0, as_i8(&data)).is_err() {
            return;
        }
        call_void(
            &cbs,
            "onConsoleOutput",
            "([B)V",
            &[JValue::Object(&JObject::from(arr))],
        );
    });
}

unsafe extern "C" fn display_frame(
    context: *mut c_void,
    _display: u32,
    frame: *const CrateFrame,
) {
    let Some(cbs) = callbacks_for(context as i64) else {
        return;
    };
    let f = unsafe { &*frame };
    let n = (f.stride as usize) * (f.height as usize);
    let pixels = unsafe { std::slice::from_raw_parts(f.pixels, n).to_vec() };
    let dims = [
        JValue::Int(f.width as jint),
        JValue::Int(f.height as jint),
        JValue::Int(f.stride as jint),
    ];
    let tail = [
        JValue::Int(f.dirty_x as jint),
        JValue::Int(f.dirty_y as jint),
        JValue::Int(f.dirty_width as jint),
        JValue::Int(f.dirty_height as jint),
    ];
    with_vm(cbs.jvm_ptr, |vm| {
        let Ok(env) = vm.attach_current_thread() else {
            return;
        };
        let Ok(arr) = env.new_byte_array(pixels.len() as i32) else {
            return;
        };
        if env.set_byte_array_region(&arr, 0, as_i8(&pixels)).is_err() {
            return;
        }
        let frame_obj = JObject::from(arr);
        let mut args: Vec<JValue> = dims.to_vec();
        args.push(JValue::Object(&frame_obj));
        args.extend_from_slice(&tail);
        call_void(&cbs, "onFrame", "(III[BIIII)V", &args);
    });
}

unsafe extern "C" fn state_changed(context: *mut c_void, state: c_int, reason: c_int) {
    let Some(cbs) = callbacks_for(context as i64) else {
        return;
    };
    call_void(
        &cbs,
        "onStateChanged",
        "(II)V",
        &[JValue::Int(state as jint), JValue::Int(reason as jint)],
    );
}

// ------------------------------------------------------------------- JNI

fn jstring_to_cstring(env: &mut JNIEnv, s: &JString) -> Result<CString, String> {
    let text: String = env
        .get_string(s)
        .map_err(|e| e.to_string())?
        .into();
    Ok(CString::new(text).unwrap_or_default())
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeApiVersion(
    _env: JNIEnv,
    _cls: JClass,
) -> jint {
    crate_api_version() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeCreate(
    mut env: JNIEnv,
    this: JObject,
    kernel: JString,
    initrd: JString,
    cmdline: JString,
    cpus: jint,
    memory_mib: jlong,
    with_display: jboolean,
    disk: JString,
    seed: JString,
) -> jlong {
    let mut setup = || -> Result<i64, String> {
        {
            let lock = REGISTRY.lock().unwrap();
            if !lock.machines.is_empty() {
                return Err("one machine per process: destroy the old one first".into());
            }
        }
        let kernel = jstring_to_cstring(&mut env, &kernel)?;
        let initrd = jstring_to_cstring(&mut env, &initrd)?;
        let cmdline = jstring_to_cstring(&mut env, &cmdline)?;
        let disk = jstring_to_cstring(&mut env, &disk)?;
        let seed = jstring_to_cstring(&mut env, &seed)?;
        // Empty string = absent (JNI has no nullables here).
        let disk: Option<&CString> = (!disk.is_empty()).then_some(&disk);
        let seed: Option<&CString> = (!seed.is_empty()).then_some(&seed);
        let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);

        unsafe {
            crate_set_log_callback(Some(android_log), std::ptr::null_mut(), 3);
            let config = crate_config_new(c"virt".as_ptr());
            if config.is_null() {
                return Err("crate_config_new(virt) failed".into());
            }
            let mut status: c_int = 0;
            status |= crate_config_set_cpu_backend(config, CPU_JIT);
            status |= crate_config_set_cpus(config, cpus as u32);
            status |= crate_config_set_memory(config, (memory_mib as u64) << 20);
            status |= crate_config_set_kernel(config, kernel.as_ptr());
            status |= crate_config_set_initrd(config, initrd.as_ptr());
            status |= crate_config_set_cmdline(config, cmdline.as_ptr());
            if let Some(disk) = disk {
                status |= crate_config_add_disk(config, disk.as_ptr(), 0);
            }
            if let Some(seed) = seed {
                status |= crate_config_add_disk(config, seed.as_ptr(), DISK_READ_ONLY);
            }
            {
                let mut _net_index = 0u32;
                status |= crate_config_add_network(config, &mut _net_index);
            }
            let console = ConsoleCallbacks {
                size: std::mem::size_of::<ConsoleCallbacks>(),
                context: id as *mut c_void,
                output: Some(console_out),
                input_ready: None,
            };
            let mut _console_index = 0u32;
            status |=
                crate_config_add_console(config, CONSOLE_SERIAL, &console, &mut _console_index);
            if with_display != 0 {
                let display = DisplayCallbacks {
                    size: std::mem::size_of::<DisplayCallbacks>(),
                    context: id as *mut c_void,
                    frame: Some(display_frame),
                };
                let mut _display_index = 0u32;
                status |= crate_config_add_display(config, GPU_2D, &display, &mut _display_index);
                // Phone screen first, then the usual fallbacks.
                let modes = [
                    CrateDisplayMode { width: 1280, height: 800 },
                    CrateDisplayMode { width: 1024, height: 768 },
                    CrateDisplayMode { width: 1920, height: 1200 },
                ];
                status |= crate_config_set_display_modes(
                    config,
                    _display_index,
                    modes.as_ptr(),
                    modes.len(),
                );
                status |= crate_config_add_input(config, INPUT_KEYBOARD | INPUT_TABLET);
            }
            status |=
                crate_config_set_state_callback(config, Some(state_changed), id as *mut c_void);
            let mut machine: *mut CrateMachine = std::ptr::null_mut();
            if status == 0 {
                status = crate_machine_create(config, &mut machine);
            }
            crate_config_free(config);
            if status != 0 {
                let p = crate_last_error();
                let msg = if p.is_null() {
                    format!("machine setup failed: {status}")
                } else {
                    CStr::from_ptr(p).to_string_lossy().into_owned()
                };
                return Err(msg);
            }
            let jvm = env.get_java_vm().map_err(|e| e.to_string())?;
            let target = env.new_global_ref(&this).map_err(|e| e.to_string())?;
            let jvm_ptr = jvm.get_java_vm_pointer() as usize;
            REGISTRY.lock().unwrap().machines.insert(
                id,
                Machine {
                    ptr: machine as usize,
                    cbs: Callbacks { jvm_ptr, target },
                },
            );
            Ok(id)
        }
    };
    match setup() {
        Ok(id) => id as jlong,
        Err(msg) => {
            REGISTRY.lock().unwrap().last_error = msg;
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeStart(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
) -> jint {
    let ptr = machine_ptr(id as i64);
    if ptr.is_null() {
        REGISTRY.lock().unwrap().last_error = format!("start: no machine {id}");
        return -1;
    }
    let status = unsafe { crate_machine_start(ptr) };
    if status != 0 {
        return fail(status);
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeConsoleWrite(
    env: JNIEnv,
    _this: JObject,
    id: jlong,
    data: JByteArray,
) -> jint {
    let ptr = machine_ptr(id as i64);
    if ptr.is_null() {
        REGISTRY.lock().unwrap().last_error = format!("write: no machine {id}");
        return -1;
    }
    let buf: Vec<u8> = env.convert_byte_array(&data).unwrap_or_default();
    if buf.is_empty() {
        return 0;
    }
    // Offer until taken, like the engine's own runner; bounded.
    let mut rest = &buf[..];
    for _ in 0..10_000 {
        if rest.is_empty() {
            break;
        }
        let n = unsafe { crate_console_write(ptr, 0, rest.as_ptr(), rest.len()) };
        rest = &rest[n..];
        if n == 0 {
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
    }
    (buf.len() - rest.len()) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeSetDisplayActive(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
    active: jboolean,
) {
    let ptr = machine_ptr(id as i64);
    if !ptr.is_null() {
        unsafe { crate_display_set_active(ptr, 0, active != 0) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeSendKey(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
    code: jint,
    down: jboolean,
) {
    let ptr = machine_ptr(id as i64);
    if !ptr.is_null() {
        unsafe { crate_input_key(ptr, code as c_ushort, down != 0) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeSendPointer(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
    x: jint,
    y: jint,
    buttons: jint,
) {
    let ptr = machine_ptr(id as i64);
    if !ptr.is_null() {
        unsafe { crate_input_pointer(ptr, x as u32, y as u32, buttons as u32) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeSendScroll(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
    dx: jint,
    dy: jint,
) {
    let ptr = machine_ptr(id as i64);
    if !ptr.is_null() {
        unsafe { crate_input_scroll(ptr, dx, dy) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeKill(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
) {
    let ptr = machine_ptr(id as i64);
    if !ptr.is_null() {
        unsafe { crate_machine_kill(ptr) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeDestroy(
    _env: JNIEnv,
    _this: JObject,
    id: jlong,
) {
    let removed = REGISTRY.lock().unwrap().machines.remove(&(id as i64));
    if let Some(m) = removed {
        unsafe { crate_machine_free(m.ptr as *mut CrateMachine) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_engine_Engine_nativeLastError<'local>(
    env: JNIEnv<'local>,
    _cls: JClass,
) -> JString<'local> {
    let msg = REGISTRY.lock().unwrap().last_error.clone();
    env.new_string(msg)
        .unwrap_or_else(|_| env.new_string("").expect("empty JNI string must work"))
}
