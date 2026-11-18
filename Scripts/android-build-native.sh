#!/bin/bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

# Build libslirp (BSD-3-Clause) and the GLib it needs (LGPL-2.1-or-later)
# from their releases' sources for Android arm64, as shared libraries under
# android/libs/arm64-v8a/: the VM's user-mode networking. Same versions and
# SHA-256 pins as Scripts/build-slirp.sh (iOS); nothing iOS is touched.
#
#     ./Scripts/android-build-native.sh
#
# Needs the NDK (SDK Manager), meson, ninja and pkg-config. Override the NDK
# with NDK_DIR. The device and the app load these from the APK's jniLibs in
# later phases; the phase-1 boot test pushes them next to its binary and uses
# LD_LIBRARY_PATH.
set -euo pipefail

GLIB_VERSION=2.90.1
GLIB_SHA256=93c941aa17d5eb1d53fe838365f29a8b4e539c222a256d974ec8f30fc413e396
SLIRP_VERSION=4.9.7
SLIRP_SHA256=d2567fa06a6357c7755d61c67293b2c0bf069b3f2173bde6b76cf768529a1938
MIN_ANDROID=33

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/android/libs/arm64-v8a"
DEPS="$REPO/.deps-android"
SRC="$DEPS/src"
WORK="$DEPS/native-build"
STAMP="glib $GLIB_VERSION, libslirp $SLIRP_VERSION, android arm64 API $MIN_ANDROID, patches: $(cd "$REPO/Scripts/patches" && sha256sum libslirp-android.patch | cut -d' ' -f1)"

if [ "$(cat "$REPO/android/libs/Native.version" 2>/dev/null)" = "$STAMP" ] \
    && [ -f "$OUT/libslirp.so" ]; then
    echo "$STAMP already in android/libs/"
    exit 0
fi

command -v meson >/dev/null || { echo "need meson (brew install meson)" >&2; exit 1; }
command -v ninja >/dev/null || { echo "need ninja (brew install ninja)" >&2; exit 1; }
command -v pkg-config >/dev/null || { echo "need pkg-config (brew install pkg-config)" >&2; exit 1; }

NDK_DIR="${NDK_DIR:-$HOME/Library/Android/sdk/ndk/30.0.16248370}"
[ -d "$NDK_DIR" ] || { echo "no NDK at $NDK_DIR (set NDK_DIR)" >&2; exit 1; }
TC="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TC/aarch64-linux-android$MIN_ANDROID-clang"
CXX="$TC/aarch64-linux-android$MIN_ANDROID-clang++"
[ -x "$CC" ] || { echo "no $CC in NDK (wrong MIN_ANDROID?)" >&2; exit 1; }

fetch() {  # url file sha256
    [ -f "$SRC/$2" ] || curl --fail --location --silent --show-error --output "$SRC/$2" "$1"
    echo "$3  $SRC/$2" | shasum -a 256 --check --quiet
}
mkdir -p "$SRC"
fetch "https://download.gnome.org/sources/glib/${GLIB_VERSION%.*}/glib-$GLIB_VERSION.tar.xz" \
      "glib-$GLIB_VERSION.tar.xz" "$GLIB_SHA256"
fetch "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v$SLIRP_VERSION/libslirp-v$SLIRP_VERSION.tar.gz" \
      "libslirp-v$SLIRP_VERSION.tar.gz" "$SLIRP_SHA256"
rm -rf "$WORK"
mkdir -p "$WORK"
tar -xf "$SRC/glib-$GLIB_VERSION.tar.xz" -C "$WORK"
tar -xf "$SRC/libslirp-v$SLIRP_VERSION.tar.gz" -C "$WORK"
# Android has no /etc/resolv.conf for slirp's upstream DNS: honor
# SLIRP_RESOLV_CONF (the app's own resolv.conf) instead.
patch -p1 -d "$WORK/libslirp-v$SLIRP_VERSION" < "$REPO/Scripts/patches/libslirp-android.patch"

PREFIX="$WORK/prefix"
mkdir -p "$PREFIX/lib/pkgconfig"
cat > "$WORK/android.ini" <<CROSS
[binaries]
c = ['$CC']
cpp = ['$CXX']
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
# The versioned clang wrappers (aarch64-linux-android33-clang) already set
# the API level; repeating -D__ANDROID_API__ here only redefines it.
c_args = ['-fPIC']
cpp_args = ['-fPIC']
c_link_args = []
cpp_link_args = []

[properties]
# What GLib would run a program to find out, which it can't on Android.
growing_stack = false
have_c99_vsnprintf = true
have_c99_snprintf = true
have_unix98_printf = true

[host_machine]
system = 'linux'
subsystem = 'android'
kernel = 'linux'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
CROSS

# Only what this build installs: never Homebrew's macOS GLib or pcre2.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=""

echo "==> GLib $GLIB_VERSION for android-arm64 (libglib only)"
meson setup "$WORK/glib" "$WORK/glib-$GLIB_VERSION" --cross-file "$WORK/android.ini" \
    --prefix "$PREFIX" --buildtype release --default-library shared --wrap-mode default \
    -Dtests=false -Dinstalled_tests=false -Dintrospection=disabled -Dnls=disabled \
    -Dlibmount=disabled -Dselinux=disabled -Dxattr=false -Dman-pages=disabled \
    -Ddocumentation=false -Dsysprof=disabled -Ddtrace=disabled -Dsystemtap=disabled \
    -Dglib_debug=disabled -Dlibelf=disabled \
    -Dpcre2:default_library=static -Dlibffi:default_library=static \
    -Dproxy-libintl:default_library=static
# Only libglib: libslirp's sole GLib dependency. The rest of GLib (notably
# gobject) does not link on Android: libffi's exec-trampoline object
# references a hidden symbol no DSO here may satisfy. libglib itself links
# cleanly, which is all that follows needs.
glib_so="$(meson introspect "$WORK/glib" --targets | python3 -c "
import json, sys
for t in json.load(sys.stdin):
    if t.get('name') == 'glib-2.0' and t.get('type') == 'shared library':
        print(t['filename'][0])
        break
")"
[ -n "$glib_so" ] || { echo "libglib target not found" >&2; exit 1; }
# Introspection prints an absolute path; ninja wants it relative to the
# build directory.
glib_rel="${glib_so#"$WORK/glib/"}"
ninja -C "$WORK/glib" "$glib_rel"

# Hand-install what libslirp's build needs: the library by SONAME, the
# public headers with the generated glibconfig.h, and a .pc file.
mkdir -p "$PREFIX/lib/pkgconfig" \
    "$PREFIX/include/glib-2.0/glib" "$PREFIX/lib/glib-2.0/include"
so="$glib_so"
cp -f "$so" "$PREFIX/lib/"
base="$(basename "$so")"
ln -sf "$base" "$PREFIX/lib/libglib-2.0.so.0"
ln -sf libglib-2.0.so.0 "$PREFIX/lib/libglib-2.0.so"
cd "$WORK/glib-$GLIB_VERSION/glib" && find . -name '*.h' | tar -cf - -T - | \
    tar -xf - -C "$PREFIX/include/glib-2.0/glib/"; cd "$REPO"
cp -f "$WORK/glib-$GLIB_VERSION/glib/glib.h" "$PREFIX/include/glib-2.0/"
# Generated headers win over the source copies (gversionmacros.h,
# glib-visibility.h; glibconfig.h lives under lib/ for the .pc Cflags).
cp -f "$WORK/glib/glib/"*.h "$PREFIX/include/glib-2.0/glib/" 2>/dev/null || true
cp -f "$WORK/glib/glib/glibconfig.h" "$PREFIX/lib/glib-2.0/include/"
cat > "$PREFIX/lib/pkgconfig/glib-2.0.pc" <<PC
prefix=$PREFIX
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: GLib
Description: C Utility Library
Version: $GLIB_VERSION
Libs: -L\${libdir} -lglib-2.0
Cflags: -I\${includedir}/glib-2.0 -I\${libdir}/glib-2.0/include
PC

echo "==> libslirp $SLIRP_VERSION for android-arm64"
meson setup "$WORK/slirp" "$WORK/libslirp-v$SLIRP_VERSION" --cross-file "$WORK/android.ini" \
    --prefix "$PREFIX" --buildtype release --default-library shared
meson compile -C "$WORK/slirp"
meson install -C "$WORK/slirp" --destdir "$WORK/root"

# The .so files the loader wants, named by SONAME, plus the dev symlinks the
# Rust link step wants. glib was installed straight to $PREFIX above; slirp
# went through --destdir. Whatever libslirp marks NEEDED gets copied too.
mkdir -p "$OUT"
cp -f "$WORK"/root"$PREFIX"/lib/libslirp.so* "$OUT/" 2>/dev/null || true
cp -f "$PREFIX"/lib/libglib-2.0.so* "$OUT/" 2>/dev/null || true
for need in $("$TC/llvm-readelf" -d "$OUT"/libslirp.so* 2>/dev/null \
    | grep -o 'lib[a-z0-9._-]*\.so[.0-9]*' | sort -u); do
    [ -f "$OUT/$need" ] && continue
    cp -f "$WORK"/root"$PREFIX"/lib/"$need" "$OUT/" 2>/dev/null || \
    cp -f "$PREFIX"/lib/"$need" "$OUT/" 2>/dev/null || true
done
ls "$OUT"
echo "$STAMP" > "$REPO/android/libs/Native.version"
echo "$STAMP in android/libs/"
