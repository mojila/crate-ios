// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Phase-1 on-device proof that the crate engine runs on Android: boots an
//! arm64 Linux kernel + initramfs with no disks, no network and no display,
//! on the JIT backend, and talks to it over the serial console.
//!
//! Pass looks like: kernel boots through initramfs, runs `/bin/sh` (rdinit),
//! and echoes back a marker we send:
//!
//!     crate-boot-test --kernel vmlinuz --initrd initrd.img
//!
//! Exit 0 on the echo round-trip, 2 when the kernel came up but the shell did
//! not (e.g. a panic: still engine-proof, printed for inspection), 1 on
//! timeout or engine failure. The whole serial log goes to stdout.

use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate_virt::{ConsoleConfig, ConsoleKind, InputConfig, VirtConfig, VirtMachine};

const MAX_LOG: usize = 4 << 20;

fn usage() -> ! {
    eprintln!(
        "usage: crate-boot-test --kernel IMAGE --initrd IMAGE [--cmdline STRING] \
         [--memory-mib N] [--timeout-secs N]"
    );
    std::process::exit(3);
}

fn main() {
    let mut kernel: Option<PathBuf> = None;
    let mut initrd: Option<PathBuf> = None;
    let mut cmdline = "console=ttyAMA0 rdinit=/bin/sh".to_string();
    let mut memory_mib: u64 = 1024;
    let mut timeout_secs: u64 = 300;
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--kernel" => kernel = it.next().map(PathBuf::from),
            "--initrd" => initrd = it.next().map(PathBuf::from),
            "--cmdline" => cmdline = it.next().unwrap_or_else(|| usage()),
            "--memory-mib" => {
                memory_mib = it.next().and_then(|v| v.parse().ok()).unwrap_or_else(|| usage())
            }
            "--timeout-secs" => {
                timeout_secs = it.next().and_then(|v| v.parse().ok()).unwrap_or_else(|| usage())
            }
            _ => usage(),
        }
    }
    let (kernel, initrd) = match (kernel, initrd) {
        (Some(k), Some(i)) => (k, i),
        _ => usage(),
    };

    let log: Arc<Mutex<Vec<u8>>> = Arc::default();
    let stdout_log = log.clone();
    let machine = Arc::new(
        VirtMachine::new(VirtConfig {
            cpus: 1,
            memory: memory_mib << 20,
            kernel,
            raw_kernel: false,
            initrd: Some(initrd),
            cmdline,
            disks: Vec::new(),
            networks: Vec::new(),
            consoles: vec![ConsoleConfig {
                kind: ConsoleKind::Serial,
                output: Arc::new(move |bytes: &[u8]| {
                    let _ = std::io::stdout().write_all(bytes);
                    let _ = std::io::stdout().flush();
                    let mut log = stdout_log.lock().unwrap();
                    if log.len() < MAX_LOG {
                        let room = MAX_LOG - log.len();
                        log.extend_from_slice(&bytes[..bytes.len().min(room)]);
                    }
                }),
                input_ready: None,
            }],
            displays: Vec::new(),
            input: InputConfig {
                keyboard: false,
                tablet: false,
            },
        })
        .unwrap_or_else(|e| {
            eprintln!("crate-boot-test: cannot build machine: {e:?}");
            std::process::exit(1);
        }),
    );

    let runner = machine.clone();
    let stop: Arc<std::sync::atomic::AtomicBool> = Arc::default();
    let stopper = stop.clone();
    let handle = std::thread::spawn(move || {
        let backend = crate_jit::backend::Jit::new();
        let result = runner.run(&backend);
        stopper.store(true, std::sync::atomic::Ordering::SeqCst);
        result
    });

    let deadline = Instant::now() + Duration::from_secs(timeout_secs);
    let seen = |needle: &[u8]| {
        log.lock()
            .unwrap()
            .windows(needle.len())
            .any(|w| w == needle)
    };

    // 1. The kernel must get all the way into userspace.
    let booted = loop {
        if seen(b"as init process") {
            break true;
        }
        if seen(b"Kernel panic") {
            break false;
        }
        if Instant::now() > deadline || stop.load(std::sync::atomic::Ordering::SeqCst) {
            eprintln!("crate-boot-test: TIMEOUT waiting for userspace");
            machine.kill();
            let _ = handle.join();
            std::process::exit(1);
        }
        std::thread::sleep(Duration::from_millis(200));
    };

    // 2. Talk to the shell: echo a marker and wait for it back.
    let mut pass = false;
    if booted {
        let marker = b"echo SMOKE_OK_2419\n";
        let mut rest = &marker[..];
        let feed_deadline = Instant::now() + Duration::from_secs(60);
        while !rest.is_empty() && Instant::now() < feed_deadline {
            let n = machine.console_write(0, rest);
            rest = &rest[n..];
            if n == 0 {
                std::thread::sleep(Duration::from_millis(5));
            }
        }
        let echo_deadline = Instant::now() + Duration::from_secs(60);
        while Instant::now() < echo_deadline {
            if seen(b"SMOKE_OK_2419") {
                pass = true;
                break;
            }
            if stop.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            std::thread::sleep(Duration::from_millis(200));
        }
    }

    machine.kill();
    let outcome = match handle.join() {
        Ok(r) => format!("{r:?}"),
        Err(_) => "runner thread panicked".to_string(),
    };
    eprintln!("crate-boot-test: machine outcome: {outcome}");
    std::process::exit(if pass {
        eprintln!("crate-boot-test: PASS (shell echo round-trip)");
        0
    } else if booted {
        eprintln!("crate-boot-test: PARTIAL (userspace reached, no echo)");
        2
    } else {
        eprintln!("crate-boot-test: NO BOOT (see serial log above)");
        1
    });
}
