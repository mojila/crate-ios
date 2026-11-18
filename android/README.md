# crate for Android — phase 2: app scaffold + JNI machine control

Linux VMs on Android phones, beside the iOS app. iOS is untouched: everything
new lives in `android/`, `Scripts/android-*.sh`, and `Scripts/patches/`.

## Status

- [x] Phase 1: native libs (glib + slirp), pinned engine source with Android
      patches, `crate-boot-test` binary + `libcrate_jni.so`, headless boot of
      Debian on a Samsung S22 (`Scripts/android-boot-test.sh` → PASS).
- [x] Phase 2 (this): Gradle app (`com.crate.app`), full JNI machine-control
      surface (`com.crate.engine.Engine`), serial-console UI. Installs and
      boots Debian in-app.
- [ ] Phase 3: display/input (GPU frame callbacks → Compose canvas, touch →
      `sendPointer`, keyboard → `sendKey`), install flow, catalog images.

## Layout

- `android/rust-bridge/` — Rust crate: `cdylib` JNI bridge (`src/lib.rs`)
  plus the headless `crate-boot-test` binary (`src/bin/boot_test.rs`).
- `android/libs/arm64-v8a/` — build outputs: `libcrate_jni.so`,
  `crate-boot-test`, glib + slirp. Staged by `Scripts/android-build-engine.sh`
  into `android/app/src/main/{jniLibs,assets}` — never edit those by hand.
- `android/app/` — Kotlin + Compose app. `com.crate.engine` is the JNI
  wrapper (no Android dependencies except Compose state); `com.crate.app`
  is the UI.
- `Scripts/android-{fetch-engine,build-native,build-engine,boot-test}.sh`
  — fetch, build, and headless-verify pipeline.
- `Scripts/patches/crate-android.patch` — engine changes for Android:
  memfd/JIT `cfg` gates widened to Android, enter/epilogue trampolines via
  `sym` operands (portable to ELF and Mach-O).

## Build and run

Prerequisites: Android SDK with platform `android-37`, NDK `30.0.16248370`,
Rust `aarch64-linux-android` target, `cargo-ndk`, meson, ninja, an arm64
(Snapdragon) device with USB debugging. macOS paths assume the SDK under
`~/Library/Android/sdk` (`NDK_DIR` overrides).

```sh
./Scripts/android-build-native.sh     # glib + slirp → android/libs
./Scripts/android-build-engine.sh     # bridge .so + boot-test, stages app libs
./Scripts/android-boot-test.sh debian-xfce   # headless boot proof on device
```

Then the app (first build downloads AGP/Kotlin — a few minutes):

```sh
cd android && ./gradlew :app:assembleDebug
```

Push the guest kernel/initrd where the app can read them (its external
files dir — no permissions needed for the app's own dir):

```sh
adb shell mkdir -p /sdcard/Android/data/com.crate.app/files/crate
adb push .deps-android/images/debian-xfce/debian-xfce-vmlinuz \
    /sdcard/Android/data/com.crate.app/files/crate/vmlinuz
adb push .deps-android/images/debian-xfce/debian-xfce-initrd.img \
    /sdcard/Android/data/com.crate.app/files/crate/initrd.img
# Pushed files land shell-owned without other-read; the app needs that:
adb shell chmod 755 /sdcard/Android/data/com.crate.app/files/crate
adb shell chmod 644 /sdcard/Android/data/com.crate.app/files/crate/*
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app, tap **Start**: the serial log streams, ending at a `#` shell
prompt (verified: `uname` → `Linux`). Type into the field + **Send**
(Done dismisses the keyboard), or **^C** to interrupt. **Kill** stops the
VM and frees it, returning to `idle` so **Start** can boot again.

Check **Display (GPU) at boot** to register the virtio GPU + tablet/keyboard
inputs (`setDisplayActive`, mirroring iOS `DisplayBridge`). The serial guest
has no GUI so no frames arrive (`frames: 0`), but the display panel shows
the key row (Esc/Tab/arrows) and touch reaches the guest tablet — both JNI
paths verified crash-free. Real pixels need a desktop disk image (Install
screen); frames render as Fit-scaled ARGB with a frame counter.

## Install screen (Home | Install menu)

Fetches the catalog, downloads a Debian Xfce image (kernel + initrd + xz
disk parts, each SHA-256-checked), unpacks the disk sparsely (Rust
`rust-bridge/src/install.rs`: streaming xz decode, zero blocks become holes,
whole stream hashed), verifies the disk digest, grows it to 12 GiB, and
writes the cloud-init NoCloud `seed.img` (FAT12 `CIDATA`: user-data with a
SHA-512-crypt password hash, meta-data, vendor-data). Mirrors iOS
`Installer`/`DiskUnpacker`/`SeedDisk` 1:1.

VMs live in internal storage (`filesDir/VMs/<uuid>/`: `vmlinuz`,
`initrd.img`, `disk.img`, `seed.img`, `machine.json`) — internal storage is
sparse-capable, emulated external storage may not be. **Boot** on a machine
boots its desktop (`root=/dev/vda` from the catalog cmdline) with display
forced on; **Kill** frees it. Native methods live on
`com.crate.install.Installer` (same `libcrate_jni.so`).

Verified on the S22 2026-11-18: Debian Xfce installed from the catalog over
the phone's own network (668 MiB, SHA-256-checked per file and for the
unpacked disk), grown to 12 GiB, seeded `owner`/`crate`, then booted —
Xfce desktop renders in the display panel (frames flow) while serial shows
`Debian GNU/Linux 13 crate ttyAMA0`, `crate login:`, and a working
`owner@crate:~$` shell login with the seeded password. Install needs the
`INTERNET` permission (declared) and holds a wake lock for the download;
leaving the screen returns to the same state (VM files persist, install
re-runs cleanly from the panel).

## Theme (dark mode)

The app follows the system theme by default and lets you pin Light or Dark
from the **Settings** menu (Home | Install | Settings, Home selected by
default): Appearance offers System / Light / Dark radio rows, persisted
across restarts. Both schemes are baseline Material3, so the familiar
purple buttons work in each; the status/navigation bars and launch
background (`values-night/themes.xml`) match the active scheme. System theme
changes are handled in-place (`uiMode` config change, verified: a running
VM keeps running), never by recreating the activity.

## Icon buttons

Text buttons are `TipIconButton` (`com.crate.app.ui`, icon + long-press
`PlainTooltip` + TalkBack description). Active tab / primary row action is
filled: Console `Terminal`, Install `Download`, theme cycler reflects the
mode (`BrightnessAuto`/`LightMode`/`DarkMode`), Send `Send`, Start/Boot
`PlayArrow`, Ctrl+C `Keyboard`, Kill `Stop`, catalog pick
`RadioButtonUnchecked/Checked`, install confirm `Download`, Cancel `Close`,
dismiss `Check`. The display key row stays text — Esc/Tab/arrow glyphs are
already minimal and clearer as labels. Needs both
`material-icons-core` (Play/Send/Close/Check live there) and
`material-icons-extended`; the pair balloons the debug APK (~79 MB vs ~34
MB, no shrinking) — release should enable minify if size matters.

## Launcher icon
`mipmap-anydpi-v26/ic_launcher.xml` (+ `-round`) is a pure adaptive icon —
no legacy PNGs needed since minSdk 33 guarantees adaptive support. The
foreground (`drawable-nodpi/ic_launcher_foreground.png`, 512 px, downscaled
from the provided artwork) is full-bleed dark navy, so the background
(`values/colors.xml`, `#1A2230`) only peeks through the mask crop and blends
with the artwork edges. Manifest also pins `android:label="Crate"` (there
was none). Verified in the Samsung Finder: gear emblem + "Crate" label.
Note: the artwork's bottom wordmark reads "CRETE" and sits near the mask
edge — round masks crop it, squircle shows it. If that bothers you, say the
word and I'll re-crop the foreground to the gear emblem (mask-safe).

## JNI contract (rust-bridge/src/lib.rs)

Kotlin `Engine` owns one machine id; Rust keeps a registry id → machine map
(one machine per process — crate's own limit). Context pointers handed to C
are registry ids cast to pointers, so a late callback after destroy finds no
entry instead of dangling. Engine threads attach to the JVM per callback and
invoke `onConsoleOutput` / `onStateChanged` / `onFrame`, marshalled to the
main thread in `Engine`.

The bridge links the engine statically through a real `use crate_ffi::...`
reference — a bare `extern "C"` block alone lets rustc drop the rlib and
leaves every `crate_*` symbol undefined. `android-build-engine.sh` fails the
build if any undefined `crate_*` remains in `libcrate_jni.so`.

Native dependency chain: `libcrate_jni.so → libslirp.so.0 →
libglib-2.0.so.0`. The versioned glib/slirp binaries ship under
`assets/native` and are `System.load`ed by absolute path (glib first) in
`NativeLibs.ensureLoaded` before `loadLibrary("crate_jni")`.

## Versions (pinned 2026-11-18, latest stable off Maven)

AGP 9.5.1 (bundles Kotlin — no `org.jetbrains.kotlin.android` plugin),
Kotlin/Compose-compiler 2.2.21, Compose BOM 2026.09.00, Gradle 9.9.1,
compileSdk 37 / targetSdk 36 / minSdk 33, arm64-v8a only.
