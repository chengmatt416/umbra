#!/usr/bin/env bash
# Stages Qualcomm's QNN runtime for Laya on Snapdragon NPUs into build/qnn/jniLibs/arm64-v8a, where app/build.gradle.kts
# picks it up. The libraries come from the Qualcomm AI Runtime (QAIRT) SDK, the version LiteRT 2.3.0's Qualcomm dispatch
# library is built against (LiteRT's ci/tools/python/vendor_sdk/qualcomm/setup.py).
#
# Licence: QTI "AI Stack License" -- the libraries may be distributed only in object code and as incorporated in an
# application, never on their own. So they go into the APK and nowhere else: not into git, not onto R2.
#
# Only the HTP backend for Hexagon v73-v81 (Snapdragon 8 Gen 2 and later) is staged: Laya's NPU graph is fp16, which
# older HTPs do not run. libQnnHtpPrepare.so (85 MB) lets LiteRT compile Laya for the NPU on the phone itself when no
# file compiled ahead of time exists for its SoC; the result is cached, so a phone compiles once.
set -euo pipefail

VERSION=2.50.0.260828
URL="https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$VERSION/v$VERSION.zip"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ZIP="${QAIRT_ZIP:-$ROOT/build/laya-convert/qairt-$VERSION.zip}"
OUT="$ROOT/build/qnn/jniLibs/arm64-v8a"

if [ ! -f "$ZIP" ]; then
  mkdir -p "$(dirname "$ZIP")"
  echo "Downloading QAIRT $VERSION (2.6 GB)..."
  # The server refuses HEAD requests but serves GET.
  curl -fL -A "Python-urllib/3.13" --retry 3 -C - -o "$ZIP.part" "$URL"
  mv "$ZIP.part" "$ZIP"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
P="qairt/$VERSION"
unzip -q -o "$ZIP" \
  "$P/LICENSE.pdf" "$P/QNN_NOTICE.txt" \
  "$P/lib/aarch64-android/libQnnHtp.so" "$P/lib/aarch64-android/libQnnSystem.so" \
  "$P/lib/aarch64-android/libQnnHtpPrepare.so" \
  "$P/lib/aarch64-android/libQnnHtpV73Stub.so" "$P/lib/aarch64-android/libQnnHtpV75Stub.so" \
  "$P/lib/aarch64-android/libQnnHtpV79Stub.so" "$P/lib/aarch64-android/libQnnHtpV81Stub.so" \
  "$P/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so" "$P/lib/hexagon-v75/unsigned/libQnnHtpV75Skel.so" \
  "$P/lib/hexagon-v79/unsigned/libQnnHtpV79Skel.so" "$P/lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so" \
  -d "$TMP"

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$TMP/$P"/lib/aarch64-android/*.so "$OUT/"
# The DSP loads the skeletons from the same folder (LiteRT points ADSP_LIBRARY_PATH at the dispatch library's folder).
cp "$TMP/$P"/lib/hexagon-v*/unsigned/*.so "$OUT/"
mkdir -p "$ROOT/build/qnn/legal"
cp "$TMP/$P/LICENSE.pdf" "$ROOT/build/qnn/legal/QAIRT-LICENSE.pdf"
cp "$TMP/$P/QNN_NOTICE.txt" "$ROOT/build/qnn/legal/QNN_NOTICE.txt"
echo "$VERSION" > "$ROOT/build/qnn/VERSION"
ls -l "$OUT"
du -sh "$OUT"
