#!/bin/bash
# Runs the full JVM unit-test suite and prints only the verdict.
# Usage: ./run-suite.sh [extra gradle args]
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd "$(dirname "$0")/android_app" || exit 1
LOG=/tmp/openscale-suite.log
./gradlew :app:testDebugUnitTest --console=plain "$@" > "$LOG" 2>&1
echo "exit=$?"
grep -E '^(BUILD|FAILURE)' "$LOG"
echo "--- compile errors (first 10) ---"
grep -E '^e: |error:' "$LOG" | head -10
echo "--- failed tests ---"
grep -E 'FAILED' "$LOG" | head -10
echo "--- counts: tests / failures / ignored ---"
R=app/build/reports/tests/testDebugUnitTest/index.html
[ -f "$R" ] && grep -oE '<div class="counter">[0-9]+</div>' "$R" | grep -oE '[0-9]+' | head -3
