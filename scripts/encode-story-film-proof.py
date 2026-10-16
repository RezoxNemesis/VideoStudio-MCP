#!/usr/bin/env python3
"""Encode and independently inspect 240 original Android Canvas PNG frames.

Source PNGs were produced by SketchStoryFilmNativeProofTest under native
Robolectric Canvas. FFmpeg packages their exact order; no source image,
neural animation or invented optical-flow frames enter the proof.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(sys.argv[1]) if len(sys.argv)>1 else Path("android/app/build/sketch-story-film-proof")
FRAMES=ROOT/"frames"
FPS=24
EXPECTED=240
OUT=ROOT/"Struggle-of-a-Man-VideoStudio-4.5-original-2d.mp4"

def require(condition,message):
    if not condition:
        raise RuntimeError("STORY_FILM_PROOF: "+message)

def digest(path):
    h=hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda:file.read(65536),b""):
            h.update(chunk)
    return h.hexdigest()

def run(*args):
    subprocess.run(args,check=True,stdin=subprocess.DEVNULL)

require(shutil.which("ffmpeg"),"FFmpeg must be installed on CI")
require(shutil.which("ffprobe"),"FFprobe must be installed on CI")
native=json.loads((ROOT/"native-frame-proof.json").read_text())
require(native.get("frameCount")==EXPECTED and native.get("fps")==FPS and native.get("width")==1280 and native.get("height")==720,
        "Native frame proof disagrees with the requested 10s/24fps/720p film")
require(native.get("changedAdjacentFrames",0)>=180,"Not enough real changed drawing frames")
frames=sorted(FRAMES.glob("frame-*.png"))
require(len(frames)==EXPECTED,"Missing or unexpected native story frames")
require([f.name for f in frames]==[f"frame-{i:04d}.png" for i in range(EXPECTED)],
        "The native PNG sequence is incomplete or out of order")
require(all(f.stat().st_size>1000 for f in frames),"Native frame PNG is empty or truncated")
ROOT.mkdir(parents=True,exist_ok=True)

run("ffmpeg","-nostdin","-y","-v","error",
    "-framerate",str(FPS),"-start_number","0","-i",str(FRAMES/"frame-%04d.png"),
    "-frames:v",str(EXPECTED),"-an",
    "-c:v","libx264","-preset","medium","-crf","19","-pix_fmt","yuv420p",
    "-movflags","+faststart",
    "-metadata","title=The Struggle of a Man | VideoStudio 4.5 original 2D story film",
    "-metadata","comment=CI encoding of exact native Android Canvas frame sequence; not an installed-phone export",
    str(OUT))
probe=json.loads(subprocess.check_output([
    "ffprobe","-v","error","-select_streams","v:0","-count_frames",
    "-show_entries","stream=width,height,r_frame_rate,nb_read_frames,duration:format=duration,size",
    "-of","json",str(OUT)],text=True))
stream=probe["streams"][0]
require(int(stream.get("nb_read_frames",0))==EXPECTED,"MP4 lost or duplicated drawn frames")
require((stream["width"],stream["height"])==(1280,720),"MP4 frame dimensions changed")
require(stream["r_frame_rate"]=="24/1","MP4 has wrong frame rate")
require(abs(float(probe["format"]["duration"])-10)<.02,"MP4 duration is not 10 seconds")
require(OUT.stat().st_size<=20*1024*1024,"10-second MP4 exceeds 20 MiB mobile delivery target")

run("ffmpeg","-nostdin","-y","-v","error","-i",str(OUT),
    "-vf","fps=2,scale=320:180,tile=5x4","-frames:v","1",str(ROOT/"contact-sheet.png"))
require((ROOT/"contact-sheet.png").stat().st_size>1000,"Review contact sheet unavailable")
samples=[0,24,48,60,72,84,108,132,156,180,204,228,239]
for n in samples:
    shutil.copy2(FRAMES/f"frame-{n:04d}.png",ROOT/f"sample-frame-{n:04d}.png")
proof=dict(schema="videostudio.story-proof.v1",
           execution="Native Robolectric Android Canvas frames in GitHub CI; FFmpeg encoded; no phone",
           sourceDocument="editable-original-source.json",
           sourceDocumentSha256=digest(ROOT/"editable-original-source.json"),
           video=OUT.name,sha256=digest(OUT),bytes=OUT.stat().st_size,
           width=1280,height=720,fps=FPS,frameCount=EXPECTED,
           durationSeconds=10,renderer=native.get("renderer"),
           changedAdjacentFrames=native["changedAdjacentFrames"],
           videoProbe=probe,
           sourcePngsVerified=EXPECTED,
           generatedImages=False,audio=False,physicalPhoneVerified=False)
(ROOT/"mp4-verified.json").write_text(json.dumps(proof,indent=2)+"\n")
shutil.rmtree(FRAMES)
print(json.dumps({k:proof[k] for k in (
    "video","bytes","sha256","frameCount","fps","durationSeconds",
    "changedAdjacentFrames","sourcePngsVerified")},indent=2))
