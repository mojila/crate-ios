//! Install-side helpers for the Android app: unpacking an xz-compressed disk
//! image into a sparse file (zero blocks become holes), SHA-256 file checks,
//! and writing the cloud-init NoCloud seed disk.
//!
//! The flow mirrors iOS `DiskUnpacker` + `SeedDisk`: each compressed part is
//! streamed through one reusable buffer, every decompressed byte (holes
//! included) goes through SHA-256, and the finished digest must equal the
//! catalog's before the disk is grown and the seed written.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use sha2::{Digest, Sha512, Sha256};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

const BLOCK: usize = 1 << 20;
const READ_CHUNK: usize = 4 << 20;

struct Session {
    file: File,
    hasher: Sha256,
    offset: u64,
    buf: Box<[u8; BLOCK]>,
    zeros: Box<[u8; BLOCK]>,
    filled: usize,
}

struct State {
    sessions: Mutex<HashMap<u64, Session>>,
    next: AtomicU64,
    error: Mutex<String>,
}

fn state() -> &'static State {
    static S: OnceLock<State> = OnceLock::new();
    S.get_or_init(|| State {
        sessions: Mutex::new(HashMap::new()),
        next: AtomicU64::new(1),
        error: Mutex::new(String::new()),
    })
}

fn set_error(msg: String) {
    *state().error.lock().unwrap() = msg;
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|j| j.into())
        .map_err(|e| e.to_string())
}

fn flush(s: &mut Session) -> std::io::Result<()> {
    if s.filled == 0 {
        return Ok(());
    }
    // A full block of zeros becomes a hole: seek past it instead of writing.
    let all_zero = s.filled == BLOCK && s.buf[..] == s.zeros[..];
    if !all_zero {
        s.file.write_all(&s.buf[..s.filled])?;
    } else {
        s.file.seek(SeekFrom::Current(s.filled as i64))?;
    }
    s.offset += s.filled as u64;
    s.filled = 0;
    Ok(())
}

/// `Write` sink that hashes every byte and packs the stream into 1 MiB
/// blocks, flushing full ones (zero blocks seeked past as holes).
struct Feed<'a> {
    s: &'a mut Session,
}

impl std::io::Write for Feed<'_> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.s.hasher.update(buf);
        let mut src = buf;
        while !src.is_empty() {
            let room = BLOCK - self.s.filled;
            let take = room.min(src.len());
            self.s.buf[self.s.filled..self.s.filled + take].copy_from_slice(&src[..take]);
            self.s.filled += take;
            src = &src[take..];
            if self.s.filled == BLOCK {
                flush(self.s)?;
            }
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeLastError(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let msg = state().error.lock().unwrap().clone();
    env.new_string(msg)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeBegin(
    mut env: JNIEnv,
    _cls: JClass,
    disk: JString,
) -> jlong {
    let mut run = || -> Result<u64, String> {
        let path = jstring_to_string(&mut env, &disk)?;
        let file = File::options()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| format!("open {path}: {e}"))?;
        let id = state().next.fetch_add(1, Ordering::SeqCst);
        state().sessions.lock().unwrap().insert(
            id,
            Session {
                file,
                hasher: Sha256::new(),
                offset: 0,
                buf: Box::new([0u8; BLOCK]),
                zeros: Box::new([0u8; BLOCK]),
                filled: 0,
            },
        );
        Ok(id)
    };
    match run() {
        Ok(id) => id as jlong,
        Err(e) => {
            set_error(e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativePart(
    mut env: JNIEnv,
    _cls: JClass,
    session: jlong,
    part: JString,
) -> jlong {
    let mut run = || -> Result<u64, String> {
        let path = jstring_to_string(&mut env, &part)?;
        let input = File::open(&path).map_err(|e| format!("open {path}: {e}"))?;
        let mut reader = std::io::BufReader::with_capacity(READ_CHUNK, input);
        let mut sessions = state().sessions.lock().unwrap();
        let s = sessions
            .get_mut(&(session as u64))
            .ok_or_else(|| "no install session".to_string())?;
        lzma_rs::xz_decompress(&mut reader, &mut Feed { s })
            .map_err(|e| format!("xz decode: {e}"))?;
        let s = sessions
            .get(&(session as u64))
            .ok_or_else(|| "no install session".to_string())?;
        // Partial block stays buffered (like iOS DiskUnpacker.flush on finish).
        Ok(s.offset + s.filled as u64)
    };
    match run() {
        Ok(n) => n as jlong,
        Err(e) => {
            set_error(e);
            -1
        }
    }
}

/// Bytes written so far (buffered + flushed), for progress polling.
#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeBytes(
    _env: JNIEnv,
    _cls: JClass,
    session: jlong,
) -> jlong {
    match state().sessions.lock().unwrap().get(&(session as u64)) {
        Some(s) => (s.offset + s.filled as u64) as jlong,
        None => -1,
    }
}

/// Finishes the stream: flushes, truncates to the written size, drops the
/// session. Returns "bytes hexdigest" (split on the space in Kotlin).
#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeFinish(
    mut env: JNIEnv,
    _cls: JClass,
    session: jlong,
) -> jstring {
    let mut run = || -> Result<String, String> {
        let mut sessions = state().sessions.lock().unwrap();
        let s = sessions
            .remove(&(session as u64))
            .ok_or_else(|| "no install session".to_string())?;
        let mut s = s;
        flush(&mut s).map_err(|e| format!("write disk: {e}"))?;
        s.file
            .set_len(s.offset)
            .map_err(|e| format!("truncate disk: {e}"))?;
        s.file.flush().map_err(|e| format!("flush disk: {e}"))?;
        let digest = s.hasher.finalize();
        Ok(format!(
            "{} {}",
            s.offset,
            digest.iter().map(|b| format!("{b:02x}")).collect::<String>()
        ))
    };
    match run() {
        Ok(out) => env
            .new_string(out)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            set_error(e);
            std::ptr::null_mut()
        }
    }
}

/// Grows the file to `size` bytes (sparse — new space is a hole).
#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeExtend(
    mut env: JNIEnv,
    _cls: JClass,
    disk: JString,
    size: jlong,
) -> jint {
    let mut run = || -> Result<(), String> {
        let path = jstring_to_string(&mut env, &disk)?;
        let f = File::options()
            .write(true)
            .open(&path)
            .map_err(|e| format!("open {path}: {e}"))?;
        let cur = f.metadata().map_err(|e| e.to_string())?.len();
        f.set_len(cur.max(size as u64))
            .map_err(|e| format!("extend: {e}"))?;
        Ok(())
    };
    match run() {
        Ok(()) => 0,
        Err(e) => {
            set_error(e);
            -1
        }
    }
}

/// SHA-256 of a file, streamed in 4 MiB chunks. Returns hex or null.
#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeSha256(
    mut env: JNIEnv,
    _cls: JClass,
    path: JString,
) -> jstring {
    let mut run = || -> Result<String, String> {
        let path = jstring_to_string(&mut env, &path)?;
        let mut f = File::open(&path).map_err(|e| format!("open {path}: {e}"))?;
        let mut hasher = Sha256::new();
        let mut chunk = vec![0u8; READ_CHUNK];
        loop {
            let n = f.read(&mut chunk).map_err(|e| format!("read: {e}"))?;
            if n == 0 {
                break;
            }
            hasher.update(&chunk[..n]);
        }
        Ok(hasher
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect())
    };
    match run() {
        Ok(hex) => env
            .new_string(hex)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            set_error(e);
            std::ptr::null_mut()
        }
    }
}

// ------------------------------------------------------------- seed disk

fn put16(img: &mut [u8], off: usize, v: u16) {
    img[off] = (v & 0xFF) as u8;
    img[off + 1] = (v >> 8) as u8;
}

fn put32(img: &mut [u8], off: usize, v: u32) {
    for i in 0..4 {
        img[off + i] = ((v >> (8 * i)) & 0xFF) as u8;
    }
}

fn set_fat(fat: &mut [u8], n: usize, value: usize) {
    let off = n * 3 / 2;
    if n % 2 == 0 {
        fat[off] = (value & 0xFF) as u8;
        fat[off + 1] = (fat[off + 1] & 0xF0) | (((value >> 8) & 0x0F) as u8);
    } else {
        fat[off] = (fat[off] & 0x0F) | (((value << 4) & 0xF0) as u8);
        fat[off + 1] = ((value >> 4) & 0xFF) as u8;
    }
}

fn long_name_entries(name: &str, short: &[u8; 11]) -> Vec<[u8; 32]> {
    let mut checksum: u8 = 0;
    for &b in short.iter() {
        checksum = ((checksum & 1) << 7).wrapping_add(checksum >> 1).wrapping_add(b);
    }
    let mut units: Vec<u16> = name.encode_utf16().collect();
    units.push(0);
    let count = units.len().div_ceil(13);
    units.resize(count * 13, 0xFFFF);
    let mut entries = Vec::new();
    for part in 0..count {
        let mut e = [0u8; 32];
        e[0] = (part as u8 + 1) | (if part == count - 1 { 0x40 } else { 0 });
        e[11] = 0x0F;
        e[13] = checksum;
        let slots = [1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30];
        for (ch, slot) in units[part * 13..(part + 1) * 13].iter().zip(slots) {
            e[slot] = (ch & 0xFF) as u8;
            e[slot + 1] = (ch >> 8) as u8;
        }
        entries.push(e);
    }
    entries.reverse();
    entries
}

/// 1 MiB FAT12 image, exactly like iOS `FATImage.make` (label CIDATA).
fn fat_image(label: &str, files: &[(&str, &[u8])], serial: u32) -> Vec<u8> {
    const SECTOR: usize = 512;
    const SECTORS: usize = 2048;
    const FAT_SECTORS: usize = 6;
    const ROOT_ENTRIES: usize = 224;
    const ROOT_SECTORS: usize = ROOT_ENTRIES * 32 / SECTOR;
    const DATA_START: usize = 1 + 2 * FAT_SECTORS + ROOT_SECTORS;

    let mut image = vec![0u8; SECTORS * SECTOR];
    let mut padded = [0x20u8; 11];
    for (i, &b) in label.to_uppercase().as_bytes().iter().take(11).enumerate() {
        padded[i] = b;
    }
    image[0..3].copy_from_slice(&[0xEB, 0x3C, 0x90]);
    image[3..11].copy_from_slice(b"MSWIN4.1");
    put16(&mut image, 11, SECTOR as u16);
    image[13] = 1;
    put16(&mut image, 14, 1);
    image[16] = 2;
    put16(&mut image, 17, ROOT_ENTRIES as u16);
    put16(&mut image, 19, SECTORS as u16);
    image[21] = 0xF8;
    put16(&mut image, 22, FAT_SECTORS as u16);
    put16(&mut image, 24, 32);
    put16(&mut image, 26, 2);
    image[36] = 0x80;
    image[38] = 0x29;
    put32(&mut image, 39, serial);
    image[43..54].copy_from_slice(&padded);
    image[54..62].copy_from_slice(b"FAT12   ");
    image[510] = 0x55;
    image[511] = 0xAA;

    let mut fat = vec![0u8; FAT_SECTORS * SECTOR];
    set_fat(&mut fat, 0, 0xFF8);
    set_fat(&mut fat, 1, 0xFFF);
    let mut root = vec![0u8; ROOT_SECTORS * SECTOR];
    let mut entry = 0usize;
    let mut add = |bytes: &[u8; 32]| {
        root[entry * 32..(entry + 1) * 32].copy_from_slice(bytes);
        entry += 1;
    };
    let mut label_entry = [0u8; 32];
    label_entry[0..11].copy_from_slice(&padded);
    label_entry[11] = 0x08;
    add(&label_entry);

    let mut cluster = 2usize;
    for (index, (name, contents)) in files.iter().enumerate() {
        let mut short = [0x20u8; 11];
        let tag = format!("FILE{index:04}");
        short[..tag.len()].copy_from_slice(tag.as_bytes());
        for e in long_name_entries(name, &short) {
            add(&e);
        }
        let clusters = contents.len().div_ceil(SECTOR);
        let mut dirent = [0u8; 32];
        dirent[0..11].copy_from_slice(&short);
        dirent[11] = 0x20;
        if clusters > 0 {
            dirent[26] = (cluster & 0xFF) as u8;
            dirent[27] = (cluster >> 8) as u8;
        }
        put32(&mut dirent, 28, contents.len() as u32);
        add(&dirent);
        for i in 0..clusters {
            set_fat(
                &mut fat,
                cluster + i,
                if i == clusters - 1 { 0xFFF } else { cluster + i + 1 },
            );
        }
        let off = (DATA_START + cluster - 2) * SECTOR;
        image[off..off + contents.len()].copy_from_slice(contents);
        cluster += clusters;
    }
    for copy in 0..2 {
        let off = (1 + copy * FAT_SECTORS) * SECTOR;
        image[off..off + fat.len()].copy_from_slice(&fat);
    }
    let root_off = (1 + 2 * FAT_SECTORS) * SECTOR;
    image[root_off..root_off + root.len()].copy_from_slice(&root);
    image
}

/// glibc SHA-512 crypt ("$6$"), mirroring iOS `SHA512Crypt.hash`.
fn sha512_crypt(password: &[u8], salt: &[u8]) -> String {
    const ALPHABET: &[u8] =
        b"./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    let digest = |parts: &[&[u8]]| -> Vec<u8> {
        let mut h = Sha512::new();
        for p in parts {
            h.update(p);
        }
        h.finalize().to_vec()
    };
    let b = digest(&[password, salt, password]);
    let mut a = Sha512::new();
    a.update(password);
    a.update(salt);
    let mut n = password.len();
    while n > 64 {
        a.update(&b);
        n -= 64;
    }
    a.update(&b[..n]);
    n = password.len();
    while n > 0 {
        if n & 1 == 1 {
            a.update(&b);
        } else {
            a.update(password);
        }
        n >>= 1;
    }
    let mut result = a.finalize().to_vec();
    let dp_input: Vec<&[u8]> = vec![password; password.len()];
    let dp = digest(&dp_input);
    let p_seq: Vec<u8> = (0..password.len()).map(|i| dp[i % 64]).collect();
    let ds_input: Vec<&[u8]> = vec![salt; 16 + result[0] as usize];
    let ds = digest(&ds_input);
    let s_seq: Vec<u8> = (0..salt.len()).map(|i| ds[i % 64]).collect();
    for round in 0..5000 {
        let mut c = Sha512::new();
        if round & 1 == 1 {
            c.update(&p_seq);
        } else {
            c.update(&result);
        }
        if round % 3 != 0 {
            c.update(&s_seq);
        }
        if round % 7 != 0 {
            c.update(&p_seq);
        }
        if round & 1 == 1 {
            c.update(&result);
        } else {
            c.update(&p_seq);
        }
        result = c.finalize().to_vec();
    }
    const ORDER: [(usize, usize, usize); 21] = [
        (0, 21, 42), (22, 43, 1), (44, 2, 23), (3, 24, 45), (25, 46, 4), (47, 5, 26),
        (6, 27, 48), (28, 49, 7), (50, 8, 29), (9, 30, 51), (31, 52, 10), (53, 11, 32),
        (12, 33, 54), (34, 55, 13), (56, 14, 35), (15, 36, 57), (37, 58, 16), (59, 17, 38),
        (18, 39, 60), (40, 61, 19), (62, 20, 41),
    ];
    let mut encoded: Vec<u8> = Vec::with_capacity(86);
    let mut emit = |b2: u8, b1: u8, b0: u8, count: usize| {
        let mut w = ((b2 as u32) << 16) | ((b1 as u32) << 8) | b0 as u32;
        for _ in 0..count {
            encoded.push(ALPHABET[(w & 0x3F) as usize]);
            w >>= 6;
        }
    };
    for (i, j, k) in ORDER {
        emit(result[i], result[j], result[k], 4);
    }
    emit(0, 0, result[63], 2);
    format!(
        "$6${}${}",
        String::from_utf8_lossy(salt),
        String::from_utf8_lossy(&encoded)
    )
}

/// Writes the NoCloud seed disk. `vendor_template` may be empty (no vendor-data).
#[no_mangle]
pub extern "system" fn Java_com_crate_install_Installer_nativeSeed(
    mut env: JNIEnv,
    _cls: JClass,
    seed: JString,
    user: JString,
    password: JString,
    hostname: JString,
    timezone: JString,
    vendor_template: JString,
    instance: JString,
) -> jint {
    let mut run = || -> Result<(), String> {
        let path = jstring_to_string(&mut env, &seed)?;
        let user = jstring_to_string(&mut env, &user)?;
        let password = jstring_to_string(&mut env, &password)?;
        let hostname = jstring_to_string(&mut env, &hostname)?;
        let timezone = jstring_to_string(&mut env, &timezone)?;
        let vendor = jstring_to_string(&mut env, &vendor_template)?;
        let instance = jstring_to_string(&mut env, &instance)?;
        // Random 16-char salt from the crypt alphabet.
        let mut rng = std::collections::hash_map::DefaultHasher::new();
        use std::hash::{Hash, Hasher};
        std::time::SystemTime::now().hash(&mut rng);
        std::process::id().hash(&mut rng);
        let mut salt = String::new();
        let alpha = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        let mut x = rng.finish();
        for _ in 0..16 {
            x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            salt.push(alpha.as_bytes()[(x >> 33) as usize % 64] as char);
        }
        let hash = sha512_crypt(password.as_bytes(), salt.as_bytes());
        let user_data = format!(
            "#cloud-config\nhostname: {hostname}\nusers:\n  - name: {user}\n    gecos: {user}\n    shell: /bin/bash\n    sudo: ALL=(ALL) ALL\n    lock_passwd: false\n    passwd: \"{hash}\"\nssh_pwauth: true\ntimezone: {timezone}\n"
        );
        let meta_data = format!(
            "instance-id: {}\nlocal-hostname: {hostname}\n",
            instance.to_lowercase()
        );
        let mut files: Vec<(&str, &[u8])> = vec![
            ("user-data", user_data.as_bytes()),
            ("meta-data", meta_data.as_bytes()),
        ];
        let vendor_filled;
        if !vendor.is_empty() {
            vendor_filled = vendor.replace("{{user}}", &user);
            files.push(("vendor-data", vendor_filled.as_bytes()));
        }
        let serial = (rng.finish() & 0xFFFF_FFFF) as u32 | 1;
        let image = fat_image("CIDATA", &files, serial);
        std::fs::write(&path, &image).map_err(|e| format!("write {path}: {e}"))?;
        Ok(())
    };
    match run() {
        Ok(()) => 0,
        Err(e) => {
            set_error(e);
            -1
        }
    }
}
