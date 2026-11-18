#!/bin/bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

# Fetch the crate engine for the Android port: a checkout of
# github.com/oneamendez/crate at the pinned tag and commit below, with the
# Android compatibility patches in Scripts/patches/ applied. Mirrors
# Scripts/fetch-crate.sh (iOS), but as source (the bridge builds it with
# cargo-ndk) rather than an xcframework. Nothing is committed: the checkout
# lives in .deps-android/engine-src. The bridge (android/rust-bridge) depends
# on it through relative path dependencies.
#
#     ./Scripts/android-fetch-engine.sh
set -euo pipefail

ENGINE_TAG=v0.1.1
ENGINE_SHA=bd2efb4ae9d8567d581b1e6c2313ac21a112f1f2

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO/.deps-android/engine-src"
STAMP="$ENGINE_TAG $ENGINE_SHA applied: $(cd "$REPO/Scripts/patches" && sha256sum crate-android.patch | cut -d' ' -f1)"

if [ "$(cat "$DEST/.android-stamp" 2>/dev/null)" = "$STAMP" ]; then
    echo "crate engine $ENGINE_TAG already in .deps-android/"
    exit 0
fi

rm -rf "$DEST"
git clone --quiet --branch "$ENGINE_TAG" \
    https://github.com/oneamendez/crate "$DEST"
actual="$(git -C "$DEST" rev-parse HEAD)"
[ "$actual" = "$ENGINE_SHA" ] || {
    echo "crate $ENGINE_TAG moved: got $actual, want $ENGINE_SHA" >&2
    echo "update ENGINE_SHA in Scripts/android-fetch-engine.sh" >&2
    exit 1
}
git -C "$DEST" apply "$REPO/Scripts/patches/crate-android.patch"
echo "$STAMP" > "$DEST/.android-stamp"
echo "crate engine $ENGINE_TAG ($ENGINE_SHA) with Android patches in .deps-android/"
