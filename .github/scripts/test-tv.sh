#!/usr/bin/env bash
set -uo pipefail
collect() {
  mkdir -p artifacts/screenshots
  adb logcat -d > artifacts/logcat.txt || true
  adb pull /sdcard/Android/data/com.smartiptv.tv/files/screenshots artifacts/screenshots || true
}
trap collect EXIT
timeout 8m bash ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=180000 --console=plain
