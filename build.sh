#!/bin/bash
# Build DiLink 4.0 ADB Tool without Gradle.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/30.0.2}"
AAPT2="${AAPT2:-$SDK/build-tools/34.0.0/aapt2}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-30/android.jar}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
DIST="$ROOT/dist"
KS="${KEYSTORE:-}"
KS_PASS="${KS_PASS:-}"
ALIAS="${KEY_ALIAS:-dilink}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 29 \
  --version-code 2 \
  --version-name "1.1"

echo "[3/6] javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ -n "$KS" ]; then
  : "${KS_PASS:?Set KS_PASS when KEYSTORE is provided}"
  OUT_APK="$DIST/ADB跳码助手-v1.1.apk"
  "$BT/apksigner" sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$ALIAS" \
    --out "$OUT_APK" "$OUT/aligned.apk"
  "$BT/apksigner" verify --print-certs "$OUT_APK" | head -5
else
  OUT_APK="$DIST/ADB跳码助手-v1.1-unsigned.apk"
  cp "$OUT/aligned.apk" "$OUT_APK"
  echo "KEYSTORE not set; wrote unsigned APK: $OUT_APK"
fi

ls -lh "$OUT_APK"
echo "done"
