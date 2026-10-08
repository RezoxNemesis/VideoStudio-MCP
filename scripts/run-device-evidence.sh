#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts/device-evidence
collect_evidence() {
  adb pull /sdcard/Android/data/com.rezoxnemesis.videostudio/files/evidence artifacts/device-evidence || true
  adb logcat -d > artifacts/device-evidence/logcat.txt || true
}
trap collect_evidence EXIT
adb logcat -c
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.rezoxnemesis.videostudio.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/device-evidence/instrumentation.txt
rg -q 'OK \([1-9][0-9]* tests?\)' artifacts/device-evidence/instrumentation.txt
