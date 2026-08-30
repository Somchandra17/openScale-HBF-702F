#!/bin/bash
# Builds the debug APK and reports where it landed.
#   ./build-apk.sh            -> all ABIs (~79 MB)
#   ./build-apk.sh arm64-v8a  -> single ABI, much smaller
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd "$(dirname "$0")/android_app" || exit 1
LOG=/tmp/apk-build.log
ABI_ARG=""
[ -n "$1" ] && ABI_ARG="-Pandroid.injected.build.abi=$1"
./gradlew :app:assembleDebug $ABI_ARG --console=plain > "$LOG" 2>&1
echo "exit=$?"
grep -E '^(BUILD|FAILURE)' "$LOG"
echo "--- errors ---"
grep -E '^e: |error:' "$LOG" | head -10

APK=app/build/outputs/apk/debug/openScale-debug.apk
ls -lh "$APK" 2>/dev/null

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
if [ -n "$BT" ] && [ -f "$APK" ]; then
  echo "--- abis ---"
  "$BT/aapt2" dump badging "$APK" 2>/dev/null | grep native-code
  echo "--- signature ---"
  "$BT/apksigner" verify "$APK" >/dev/null 2>&1 && echo "SIGNATURE VERIFIES" || echo "signature check FAILED"
  echo "--- sha256 ---"
  shasum -a 256 "$APK" | awk '{print $1}'
fi
