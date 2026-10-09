#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts/device-evidence
collect_evidence() {
  adb pull /sdcard/Android/data/com.rezoxnemesis.videostudio/files/evidence artifacts/device-evidence || true
  adb logcat -d > artifacts/device-evidence/logcat.txt || true
  python3 - <<'PYUI'
import pathlib,json,xml.etree.ElementTree as ET
for trace in pathlib.Path('artifacts/device-evidence').rglob('failure-*.xml'):
    labels=[]
    for node in ET.parse(trace).getroot().iter('node'):
        text=node.get('text','') or node.get('content-desc','')
        if text and '://' not in text: labels.append({'package':node.get('package',''),'text':text[:160]})
    print('DEVICE_FAILURE_UI '+json.dumps({'file':trace.name,'labels':labels[:50]}))
for trace in pathlib.Path('artifacts/device-evidence').rglob('codec-reliability-proof.json'):
    proof=json.loads(trace.read_text())
    fields=['codecRoute','videoEncoder','audioEncoder','decoderNames','encodedWidth','encodedHeight','codecWidth','codecHeight','codecFps','requestedBitrate','configuredBitrate','hasAudio','durationMs','sha256','codecReliabilityRecorded']
    print('DEVICE_CODEC_EVIDENCE '+json.dumps({'originalRetained':proof['originalRetained'],'attempts':[{key:attempt.get(key) for key in fields} for attempt in proof['attempts']]}))
PYUI
}
trap collect_evidence EXIT
adb logcat -c
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.rezoxnemesis.videostudio.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/device-evidence/instrumentation.txt
if command -v rg >/dev/null 2>&1; then
  rg -q 'OK \([1-9][0-9]* tests?\)' artifacts/device-evidence/instrumentation.txt
else
  grep -Eq 'OK \([1-9][0-9]* tests?\)' artifacts/device-evidence/instrumentation.txt
fi
