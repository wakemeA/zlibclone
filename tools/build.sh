#!/usr/bin/env bash
# ZLibClone 构建脚本：aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner
# 用法: bash tools/build.sh [debug]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/33.0.2"
AAPT2="${AAPT2_BIN:-/usr/bin/aapt2}"
ZIPALIGN="${ZIPALIGN_BIN:-/usr/bin/zipalign}"
APKSIGNER="${APKSIGNER_BIN:-/usr/bin/apksigner}"
PLATFORM="$SDK/platforms/android-33/android.jar"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
APK_NAME="ZLibClone.apk"
KS="$ROOT/tools/zlibclone.jks"
KS_PASS="zlibclone"

for f in "$AAPT2" "$BT/d8" "$ZIPALIGN" "$APKSIGNER" "$PLATFORM"; do
  [ -e "$f" ] || { echo "缺少构建工具: $f"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 33 \
  --version-code 8 --version-name "1.2.1" \
  --auto-add-overlay

echo "[3/6] javac"
find "$APP/java" "$OUT/gen" -name '*.java' | sort > "$OUT/sources.txt"
if ! javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  echo "javac 失败："; tail -60 "$OUT/javac.log"; exit 1
fi
grep -v "bootstrap class path" "$OUT/javac.log" || true

echo "[4/6] d8 dex"
find "$OUT/classes" -name '*.class' | sort > "$OUT/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api 21 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex )
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias zlibclone \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=ZLibClone, OU=Clone, O=ZLibClone, L=CN, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
"$APKSIGNER" verify --verbose "$OUT/$APK_NAME" | head -8

echo "OK -> $OUT/$APK_NAME  ($(stat -c%s "$OUT/$APK_NAME") bytes)"
