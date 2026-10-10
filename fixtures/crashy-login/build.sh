#!/usr/bin/env bash
# Builds the fixture without Gradle, from the tools of the Android pack of the Linux environment (the user installs it
# in the app; `umbra-pack list` shows it): javac, aapt2, d8, zipalign, apksigner and the SDK platform's android.jar.
# Output: build/crashy-login.apk, signed with a throwaway debug key. Usage: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)}"
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || { echo "build.sh: no android.jar found (the Android app builds pack is not installed; ask the user to install it, or set ANDROID_JAR)" >&2; exit 1; }
for tool in javac aapt2 d8 zipalign apksigner keytool; do
  command -v "$tool" >/dev/null || { echo "build.sh: '$tool' is missing (the Android app builds pack is not installed; ask the user to install it)" >&2; exit 1; }
done

OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

# 1. Resources and the manifest.
aapt2 link --manifest AndroidManifest.xml -I "$ANDROID_JAR" --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 -o "$OUT/base.apk"

# 2. Java sources to class files, then to dex.
find src -name '*.java' ! -name '._*' > "$OUT/sources.txt"   # ._* are macOS resource forks that tar adds
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
d8 --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# 3. Add the dex to the apk, align, sign.
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT/dex" && zip -q -j ../unaligned.apk classes.dex )
zipalign -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$OUT/crashy-login.apk" "$OUT/aligned.apk"
echo "built $OUT/crashy-login.apk"
