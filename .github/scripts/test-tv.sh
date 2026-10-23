#!/usr/bin/env bash
set -uo pipefail
adb logcat -c
adb logcat -v brief SmartIPTVTest:I TestRunner:I AndroidRuntime:E '*:S' &
logger=$!
collect() {
  kill "$logger" 2>/dev/null || true
  mkdir -p artifacts/screenshots
  adb logcat -d > artifacts/logcat.txt || true
  adb exec-out screencap -p > artifacts/screenshots/final-screen.png || true
  adb pull /sdcard/Download/SmartIPTV-TV-testshots artifacts/screenshots || true
}
trap collect EXIT
timeout -k 20s 6m bash ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=180000 --console=plain
