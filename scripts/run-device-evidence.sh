#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts/device-evidence
collect_evidence() {
  adb logcat -d > artifacts/device-evidence/logcat.txt || true
  adb logcat -d -s StudioSegmentTiming:I '*:S' || true
  # Mux workspaces are private0600 files. Collect as the app UID without broadening file access.
  if adb exec-out run-as com.rezoxnemesis.videostudio tar -cf - -C files evidence > artifacts/device-evidence/evidence.tar; then
    python3 - <<'PYARCHIVE'
import pathlib,tarfile
root=pathlib.Path('artifacts/device-evidence').resolve()
with tarfile.open(root/'evidence.tar') as archive:
    for entry in archive.getmembers():
        destination=(root/entry.name).resolve()
        if not destination.is_relative_to(root) or entry.issym() or entry.islnk():
            raise ValueError('Unsafe device evidence archive member')
    archive.extractall(root,filter='data')
(root/'evidence.tar').unlink()
PYARCHIVE
  else
    adb pull /sdcard/Android/data/com.rezoxnemesis.videostudio/files/evidence artifacts/device-evidence || true
  fi
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
for trace in pathlib.Path('artifacts/device-evidence').rglob('segment-window-proof.json'):
    proof=json.loads(trace.read_text())
    print('DEVICE_SEGMENT_EVIDENCE '+json.dumps(proof))
for trace in pathlib.Path('artifacts/device-evidence').rglob('segment-input-timing.json'):
    print('DEVICE_SEGMENT_TIMING_EVIDENCE '+json.dumps(json.loads(trace.read_text())))
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
