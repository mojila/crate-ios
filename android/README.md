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

Then the app (first build downloads AGP/Kotlin — a few minutes), install it,
open it, and install an OS from the Install tab — no manual file pushes
(the headless script above covers the no-app boot proof):

```sh
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Home lists installed operating systems — tap **Boot** on one to start it
immediately (one machine per process: Boot is gated while a VM lives). A
display boot goes fullscreen: status and buttons hide, the guest owns the
screen. The floating button (top-right) brings controls back; its
fullscreen button hides them again.
Serial output hides behind its toggle; the serial field + **Send** (Done
dismisses the keyboard) and **^C** reach the guest console. **Kill** stops
the VM and frees it, returning to `idle` so another OS can boot.
coordinates scale guest pixels to crate's 0..32767 tablet range (mirroring
iOS `DisplayBridge.pointer`); sending raw pixels lands in the top-left ~3%.
Touchpad mode (default; mouse icon toggles to direct touch) keeps a virtual
cursor in guest pixels, gain 2: drag moves (hover, no button), tap
left-clicks, long-press then drag holds left (window dragging), 2-finger
tap right-clicks. Verified on the S22 Xfce desktop: right/left swipes move
the guest cursor across the screen directionally.
A connected physical mouse (Bluetooth/USB/OTG) takes over automatically:
its absolute position drives the guest tablet directly — hover moves,
left/right/middle carry the real button bits, the wheel scrolls — while
the touchpad toggle shows "Physical mouse connected" and stays disabled
until the mouse disconnects. (The phone's built-in `sec_touchpad` device
is not a mouse and is ignored.)
A paired Bluetooth keyboard types straight into the guest once the first
frame lands (the display grabs key focus; tapping it refocuses): letters,
digits, punctuation, navigation, numpad and both modifier sides map to
Linux evdev codes, Shift-chords included. Volume/power/Back stay with the
phone.

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
sparse-capable, emulated external storage may not be. When an install
finishes, OK jumps back to Home where the new OS is listed with **Boot**.
Native methods live on `com.crate.install.Installer` (same
`libcrate_jni.so`).

Verified on the S22 2026-11-18: Debian Xfce installed from the catalog over
the phone's own network (668 MiB, SHA-256-checked per file and for the
unpacked disk), grown to 12 GiB, seeded `owner`/`crate`, then booted —
Xfce desktop renders in the display panel (frames flow) while serial shows
`Debian GNU/Linux 13 crate ttyAMA0`, `crate login:`, and a working
`owner@crate:~$` shell login with the seeded password. Install needs the
`INTERNET` permission (declared) and holds a wake lock for the download;
leaving the screen returns to the same state (VM files persist, install
re-runs cleanly from the panel).

## Settings (theme + VM)

The app follows the system theme by default and lets you pin Light or Dark
from the **Settings** menu (Home | Install | Settings, Home selected by
default): Appearance offers System / Light / Dark radio rows, persisted
across restarts. A Virtual machine section holds **Display (GPU) at boot**
(on by default, persisted the same way): off is the lean serial-console
path — boots skip the virtio GPU, show no display panel, and stay in
portrait. Both schemes are baseline Material3, so the familiar
purple buttons work in each; the status/navigation bars and launch
background (`values-night/themes.xml`) match the active scheme. System theme
changes are handled in-place (`uiMode` config change, verified: a running
VM keeps running), never by recreating the activity.

## Icon buttons

Text buttons are `TipIconButton` (`com.crate.app.ui`, icon + long-press
`PlainTooltip` + TalkBack description). Active tab / primary row action is
filled: Home `Home`, Install `Download`, Settings `Settings`, per-OS Boot
`PlayArrow`, Ctrl+C `Keyboard`, Kill `Stop`, catalog pick
`RadioButtonUnchecked/Checked`, install confirm `Download`, Cancel `Close`,
dismiss `Check`, serial-input toggle `Terminal` (field + Send `Send` hide
behind it, default hidden), console-output toggle `Subject` (log hides
behind it, default hidden — state line and display stay visible). The
display key row
stays text — Esc/Tab/arrow glyphs are
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
