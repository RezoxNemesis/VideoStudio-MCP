#!/usr/bin/env python3
"""A video of a person → a motion clip the CodeScape rig can play (body.js clipOf + playClip).

    python video2clip.py take.mp4 clip.json [--from 12.5] [--to 20] [--model pose_landmarker_heavy.task] [--name "my take"]
    python video2clip.py --npz pose.npz clip.json [--from 53] [--to 61]      (an earlier capture saved as world landmarks)

Needs: pip install mediapipe numpy   (and ffmpeg on the PATH, to read the video).  The pose model is downloaded once, next to this file.

The clip: { fps, frames: [[x, y, z] × 33], vis: [[left wrist, right wrist, left ankle, right ankle]], seen: { legs }, source }
Axes are the film's: metres, y up, the hips at the origin, the performer's left on +x when they face +z.
(MediaPipe's world landmarks have y down and z away from the camera: turning them half a turn about x gives exactly that.)
Film the whole body if the legs are wanted: a leg the camera did not see is not guessed, the rig stands instead.
"""
import argparse, json, os, re, subprocess, sys, urllib.request

MODEL_URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task"


def to_clip(world, fps, name=""):
    """world: frames × 33 × 4 (x, y, z, visibility) in MediaPipe's world axes."""
    frames, vis = [], []
    for f in world:
        frames.append([[round(float(p[0]), 3), round(float(-p[1]), 3), round(float(-p[2]), 3)] for p in f])
        vis.append([round(float(f[j][3]), 2) for j in (15, 16, 27, 28)])
    n = max(1, len(vis))
    legs = sum(1 for v in vis if v[2] > 0.5 and v[3] > 0.5) / n > 0.6
    return {"fps": round(float(fps), 3), "frames": frames, "vis": vis, "seen": {"legs": bool(legs)}, "source": name,
            "note": "on the spot: the hips are the origin of every frame"}


def from_video(path, model, t0, t1, ffmpeg="ffmpeg"):
    import numpy as np, mediapipe as mp
    from mediapipe.tasks.python import vision, BaseOptions
    if not os.path.exists(model):
        print("getting the pose model (once)…", file=sys.stderr); urllib.request.urlretrieve(MODEL_URL, model)
    said = subprocess.run([ffmpeg, "-hide_banner", "-i", path], capture_output=True, text=True, errors="replace").stderr      # ffmpeg alone says the size and the rate: no ffprobe needed
    line = next((l for l in said.splitlines() if "Video:" in l), "")
    m, r = re.search(r"(\d{2,5})x(\d{2,5})", line), re.search(r"([\d.]+) fps", line)
    if not m: sys.exit("ffmpeg cannot read a picture from " + path)
    w, h, fps = int(m.group(1)), int(m.group(2)), float(r.group(1)) if r else 30.0
    if re.search(r"rotate\s*:\s*-?(90|270)|rotation of -?(90|270)", said): w, h = h, w        # a phone video filmed upright
    k = min(1.0, 960 / max(w, h)); W, H = int(w * k) // 2 * 2, int(h * k) // 2 * 2      # the model does not need more than this
    cmd = [ffmpeg, "-v", "error"] + (["-ss", str(t0)] if t0 else []) + (["-t", str(t1 - (t0 or 0))] if t1 else []) + ["-i", path, "-vf", f"scale={W}:{H}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    rd = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    lm = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=model), running_mode=vision.RunningMode.VIDEO, num_poses=1))
    out, last, i = [], None, 0
    while True:
        buf = rd.stdout.read(W * H * 3)
        if len(buf) < W * H * 3: break
        r = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.frombuffer(buf, np.uint8).reshape(H, W, 3)), int(i * 1000 / fps)); i += 1
        if r.pose_world_landmarks: last = [[p.x, p.y, p.z, p.visibility] for p in r.pose_world_landmarks[0]]
        if last is not None: out.append(last)        # a frame with nobody found repeats the last pose rather than leaving a hole
        if i % 150 == 0: print(f"{i} frames…", file=sys.stderr)
    lm.close()
    return out, fps


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video", nargs="?"); ap.add_argument("out"); ap.add_argument("--npz"); ap.add_argument("--from", dest="t0", type=float, default=0.0); ap.add_argument("--to", dest="t1", type=float, default=0.0)
    ap.add_argument("--model", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "pose_landmarker_heavy.task")); ap.add_argument("--name", default=""); ap.add_argument("--ffmpeg", default="ffmpeg")
    a = ap.parse_args()
    if a.npz:
        import numpy as np
        d = np.load(a.npz); fps = float(d["fps"]); world = d["world"]; i0 = int(a.t0 * fps); i1 = int(a.t1 * fps) if a.t1 else len(world); world = world[i0:i1]
    else:
        if not a.video: ap.error("give a video, or --npz")
        world, fps = from_video(a.video, a.model, a.t0, a.t1, a.ffmpeg)
    if not len(world): sys.exit("nobody was found in the video")
    clip = to_clip(world, fps, a.name or os.path.basename(a.npz or a.video))
    with open(a.out, "w") as f: json.dump(clip, f, separators=(",", ":"))
    print(json.dumps({"file": a.out, "frames": len(clip["frames"]), "fps": clip["fps"], "seconds": round(len(clip["frames"]) / clip["fps"], 2), "legs": clip["seen"]["legs"]}))


if __name__ == "__main__":
    main()
