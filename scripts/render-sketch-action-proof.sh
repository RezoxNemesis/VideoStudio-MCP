#!/usr/bin/env bash
# Reproduce original synthetic artwork with VideoStudio's actual Android Canvas renderer.
# The MP4 encoder runs in the workspace. This is not a phone/device-render claim.
set -euo pipefail
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(dirname -- "$script_dir")"
export JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/videostudio/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/toolchains/videostudio/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/toolchains/videostudio/gradle-user-home}"
gradle_binary="${VIDEOSTUDIO_GRADLE_BINARY:-/workspace/toolchains/videostudio/gradle-8.9/bin/gradle}"
command -v python3 >/dev/null
command -v ffmpeg >/dev/null
command -v ffprobe >/dev/null
python3 "$repo_dir/scripts/generate-sketch-action-fixture.py"
(
  cd "$repo_dir/android"
  "$gradle_binary" :app:testDebugUnitTest \
    --tests 'com.rezoxnemesis.videostudio.SketchActionFilmTest' \
    --offline --max-workers=2
)
evidence_dir="$repo_dir/android/app/build/sketch-action-film"
ffmpeg -nostdin -y -v error -framerate 24 -start_number 0 \
  -i "$evidence_dir/frames/frame-%04d.png" -frames:v 100 \
  -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p \
  -movflags +faststart \
  -metadata title='Rooftop Relay — original 100-frame articulated 2D proof' \
  -metadata comment='Workspace encoding of VideoStudio SketchFrameRenderer native Android Canvas PNGs; not a phone render' \
  "$evidence_dir/Rooftop-Relay-100-frames-workspace.mp4"
python3 - "$evidence_dir" <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess
import sys
root=Path(sys.argv[1])
video=root/'Rooftop-Relay-100-frames-workspace.mp4'
probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-count_frames',
    '-show_entries','stream=width,height,r_frame_rate,avg_frame_rate,nb_read_frames,duration:format=duration,size',
    '-of','json',str(video)],text=True))
stream=probe['streams'][0]
assert int(stream['nb_read_frames'])==100,stream
assert stream['r_frame_rate']=='24/1',stream
assert (stream['width'],stream['height'])==(1280,720),stream
assert abs(float(stream['duration'])-100/24)<.001,stream
pngs=sorted((root/'frames').glob('frame-*.png'))
assert len(pngs)==100
assert [p.name for p in pngs]==[f'frame-{f:04d}.png' for f in range(100)]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
proof=dict(renderer='VideoStudio SketchFrameRenderer / Robolectric native Android Canvas',
    encoder='Workspace FFmpeg libx264; exact ordered PNG sequence; no invented frames',
    executionLocation='workspace; not Android phone execution',
    video=video.name,sha256=sha(video),bytes=video.stat().st_size,
    frameCount=100,fps=24,durationSeconds=100/24,sourcePngCount=len(pngs),
    audio='No audio; dialogue lettering and mouth-cel timing are authored cues.',probe=probe)
(root/'workspace-encode-verification.json').write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps(proof,indent=2))
PY
