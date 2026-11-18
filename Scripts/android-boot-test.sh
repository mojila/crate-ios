#!/bin/bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

# Phase-1 proof: boot an arm64 Linux kernel + initramfs on the attached
# Android device through the crate engine, with no disk, network or display,
# and check the shell echo round-trip over the serial console.
#
#     ./Scripts/android-boot-test.sh [image-id]   # default debian-xfce
#
# Fetches the kernel and initrd from the arm64-desktop-images catalog
# (pinned release below, SHA-256 checked, like the iOS Install flow),
# builds the test binary, pushes everything to /data/local/tmp/crate-test/
# and runs it. Exit 0: PASS, 2: kernel up but no shell (see log), 1: failure.
set -euo pipefail

CATALOG_RELEASE="${CATALOG_RELEASE:-2026.11.14-7}"
IMAGE_ID="${1:-debian-xfce}"

REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMGDIR="$REPO/.deps-android/images/$IMAGE_ID"
OUT="$REPO/android/libs/arm64-v8a"
REMOTE=/data/local/tmp/crate-test

mkdir -p "$IMGDIR"
if [ ! -f "$IMGDIR/catalog.json" ]; then
    curl --fail --location --silent --show-error \
        --output "$IMGDIR/catalog.json" \
        "https://github.com/oneamendez/arm64-desktop-images/releases/download/$CATALOG_RELEASE/catalog.json"
fi

KURL="$(python3 -c "
import json
c = json.load(open('$IMGDIR/catalog.json'))
for i in c['images']:
    if i['id'] == '$IMAGE_ID':
        print(i['kernel']['url'], i['kernel']['file'], i['kernel']['sha256'])
        print(i['initrd']['url'], i['initrd']['file'], i['initrd']['sha256'])
        break
" | tr '\n' ' ')"
set -- $KURL
[ $# -eq 6 ] || { echo "image '$IMAGE_ID' not in catalog $CATALOG_RELEASE" >&2; exit 1; }

fetch() {  # url file sha256
    [ -f "$IMGDIR/$2" ] || curl --fail --location --silent --show-error \
        --output "$IMGDIR/$2" "$1"
    echo "$3  $IMGDIR/$2" | shasum -a 256 --check --quiet
    echo "checked $2"
}
fetch "$1" "$2" "$3"
fetch "$4" "$5" "$6"

./Scripts/android-build-engine.sh bin-only

adb wait-for-device
adb shell mkdir -p "$REMOTE"
adb push "$OUT/crate-boot-test" "$REMOTE/" >/dev/null
adb push "$OUT"/libslirp.so* "$REMOTE/" >/dev/null
adb push "$OUT"/libglib-2.0.so* "$REMOTE/" >/dev/null
adb push "$IMGDIR/$2" "$REMOTE/vmlinuz" >/dev/null
adb push "$IMGDIR/$5" "$REMOTE/initrd.img" >/dev/null

echo "==> booting $IMAGE_ID on device (up to 5 minutes)"
set +e
adb shell "cd $REMOTE && LD_LIBRARY_PATH=$REMOTE ./crate-boot-test \
    --kernel vmlinuz --initrd initrd.img --memory-mib 1024"
code=$?
set -e
[ "$code" -eq 0 ] && echo "BOOT TEST: PASS" || echo "BOOT TEST: exit $code (0 PASS, 2 no shell, 1 failure)"
exit "$code"
