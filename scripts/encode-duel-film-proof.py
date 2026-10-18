#!/usr/bin/env python3
"""Encode a real original two-actor Android Canvas sequence, without interpolating PNGs.

Separate authored vector camera views, two independently rigged characters, and
a verified wrist-to-wrist interaction are compiled into the native 100 PNG frames
first. FFmpeg only encodes the exact 24fps sequence; it doesn't invent frames.
"""
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path("android/app/build/sketch-duel-film-proof")
FRAMES = ROOT / "frames"
FILM = ROOT / "Rooftop-Standoff-two-characters-original-2d.mp4"
COUNT = 100
FPS = 24

def require(value, message):
    if not value:
        raise RuntimeError("DUEL_FILM_PROOF: " + message)

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda:f.read(65536),b""):
            digest.update(chunk)
    return digest.hexdigest()

def command(*args):
    subprocess.run(args, check=True, stdin=subprocess.DEVNULL)

require(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg and FFprobe are required")
native = json.loads((ROOT / "native-duel-proof.json").read_text())
metrics = json.loads((ROOT / "duel-stage-metrics.json").read_text())
require(native.get("schema") == "videostudio.native-duel-proof.v1", "Not an original native action proof")
require((native.get("width"),native.get("height"),native.get("fps"),native.get("frameCount")) == (1280,720,24,100),
        "The native film metadata does not match the requested 1280x720/24fps/100 frames")
require(native.get("changedAdjacentFrames",0) >= 65, "Insufficient frame-to-frame original motion")
require(metrics.get("actors") == ["Vale","Rook"], "Two independent humans not verified")
require(metrics.get("batonWristDistancePx",999) <= 6, "The simulated physical contact is not close enough")
require(metrics.get("preparedViews") and len(metrics["preparedViews"]) == 3, "Missing alternate-view artwork")
paths = sorted(FRAMES.glob("frame-*.png"))
require(len(paths) == COUNT and [p.name for p in paths] ==
        [f"frame-{i:04d}.png" for i in range(COUNT)], "Native PNGs are missing or out of order")
manifest = native["frames"]
for i,frame in enumerate(manifest):
    f=paths[i]
    require(frame["frame"] == i and f.stat().st_size > 1000, "Empty or misordered native drawing")
    require(sha256(f) == frame["sha256"], "Native frame changed after evidence capture")

command("ffmpeg","-nostdin","-y","-v","error","-framerate",str(FPS),"-start_number","0",
        "-i",str(FRAMES/"frame-%04d.png"),"-frames:v",str(COUNT),"-an",
        "-c:v","libx264","-preset","medium","-crf","19","-pix_fmt","yuv420p",
        "-movflags","+faststart",
        "-metadata","title=Rooftop Standoff - two actors, native 2D animation proof",
        "-metadata","comment=Exactly 100 Android Canvas frames encoded in CI; not physical phone execution",
        str(FILM))
probe = json.loads(subprocess.check_output(["ffprobe","-v","error",
        "-select_streams","v:0","-count_frames","-show_entries",
        "stream=width,height,r_frame_rate,nb_read_frames:format=duration,size",
        "-of","json",str(FILM)],text=True))
stream = probe["streams"][0]
require(int(stream.get("nb_read_frames",0)) == COUNT, "The playable movie lost native frames")
require((stream["width"],stream["height"])==(1280,720), "Original native raster dimensions changed")
require(stream["r_frame_rate"] == "24/1", "FPS changed during encoding")
require(abs(float(probe["format"]["duration"])-COUNT/FPS)<.02, "Movie duration is wrong")
require(FILM.stat().st_size <= 20*1024*1024, "Mobile example exceeds 20 MiB")
for frame in (0,12,16,20,36,48,59,60,72,84,99):
    shutil.copy2(FRAMES/f"frame-{frame:04d}.png",ROOT/f"contact-frame-{frame:04d}.png")
summary = dict(
    schema="videostudio.two-actor-visual-proof.v1",
    video=FILM.name, sha256=sha256(FILM), fileBytes=FILM.stat().st_size,
    nativeFrameCount=COUNT, fps=FPS, durationSeconds=COUNT/FPS,
    nativeFramesVerified=COUNT, changedAdjacentFrames=native["changedAdjacentFrames"],
    separateActors=metrics["actors"],preparedViews=metrics["preparedViews"],
    distanceBetweenActorWristsPx=metrics["batonWristDistancePx"],
    inputSourceSha256=sha256(ROOT/"editable-duel-source.json"),
    inputProgramSha256=sha256(ROOT/"editable-duel-program.json"),
    verification="Actual workspace Android Canvas PNGs encoded at 24 fps; not physical phone",
    spokenDialogueIncluded=False, audioPhonemesAligned=False,
    filmGradeVisualQualityCertified=False, playbackOnPhoneVerified=False,
    videoProbe=probe)
(ROOT/"mp4-verified.json").write_text(json.dumps(summary,indent=2)+"\n")
# OMEGA M1 audits native PNG evidence and the verified MP4 *before* cleanup.
# An audit failure preserves the original frames for diagnosis and fails CI closed.
subprocess.run(["node", "scripts/omega-native-proof.mjs",
                "--kind", "duel", "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

# OMEGA M2 measures pixel motion from the verified owned MP4 before frame cleanup.
# This is an advisory cinematic diagnostic; it never approves artwork or phone playback.
subprocess.run(["node", "scripts/omega-motion-dna.mjs",
                "--kind", "duel", "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

# M2.1 measures the exact 100-frame Android compiler's authored rig joints,
# foot locks and proximity, bound to original M1/M2 video+source receipts.
# A failed joint/receipt audit leaves all PNGs for diagnosis.
subprocess.run(["node", "scripts/omega-skeleton.mjs",
                "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

# OMEGA M3: independent real native wrist/foot quality gate before PNG cleanup.
subprocess.run(["node", "scripts/omega-physics-brain.mjs",
                "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

# OMEGA M3.1 independently measures grounded brace/recoil/settle from native
# authored joints, before PNG cleanup. Failed QA retains frame evidence.
subprocess.run(["node", "scripts/omega-impact-dynamics.mjs",
                "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

# M3.2: check actual editable shaft geometry, original native samples and
# rendered MP4 *before* deleting native pixels. Invalid evidence blocks release.
subprocess.run(["node", "scripts/omega-weapon-contact.mjs",
                "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

# M3.3: inspect real interior weapon crossing after native M1-M3.2 proofs.
subprocess.run(["node", "scripts/omega-combat-staging.mjs",
                "--root", str(ROOT)],
               check=True, stdin=subprocess.DEVNULL)

shutil.rmtree(FRAMES)
print(json.dumps({k:summary[k] for k in ("video","sha256","fileBytes",
    "nativeFrameCount","fps","changedAdjacentFrames","distanceBetweenActorWristsPx")},indent=2))
