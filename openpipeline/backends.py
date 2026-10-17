"""Actual local Blender, Diffusers and configured interpolation execution."""
from __future__ import annotations
from fractions import Fraction
import bisect
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time
import math

from .archive import MAX_ARCHIVE, atomic_json, digest, owned, read_json
from .config import model_manifest


class Unavailable(RuntimeError):
    def __init__(self, code, state="waiting_model"):
        super().__init__(code); self.code, self.state = code, state


def _png_dimensions(path):
    with Path(path).open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError("Source is not a PNG raster")
    width, height = struct.unpack(">II", header[16:24])
    if min(width, height) < 16 or max(width, height) > 4096 or width % 2 or height % 2 or width * height > 4096 * 2160:
        raise ValueError("PNG dimensions exceed bounded even video dimensions")
    return width, height


def verify_video(path, *, fps, count, width, height):
    result = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
        "-show_entries", "stream=width,height,avg_frame_rate,nb_read_frames,duration,codec_name:format=duration", "-of", "json", str(path)],
        capture_output=True, check=True, timeout=120)
    data = json.loads(result.stdout)
    videos = data.get("streams", [])
    if len(videos) != 1:
        raise ValueError("Video must contain exactly one video stream")
    video = videos[0]
    duration = float(video.get("duration", data.get("format", {}).get("duration", 0)))
    if (int(video.get("nb_read_frames", 0)) != count or Fraction(video.get("avg_frame_rate", "0/1")) != fps or
        (video.get("width"), video.get("height")) != (width, height) or abs(duration - count / fps) > max(.001, 1 / fps / 100)):
        raise ValueError("Decoded output clock or dimensions differ")
    return {"frameCount": count, "fps": fps, "width": width, "height": height,
            "durationSeconds": duration, "decodedFrameCountVerified": True, "codec": video.get("codec_name"),
            "sha256": digest(path), "nativeRendered": False, "cinematicAcceptance": False}


def encode_video(frames, output, fps, count, width, height):
    output = Path(output)
    if output.exists():
        raise ValueError("Video output must be new")
    for index in range(count):
        if _png_dimensions(owned(frames, "frame_%06d.png" % index)) != (width, height):
            raise ValueError("Ordered frame dimensions differ")
    temporary = output.with_suffix(".partial.mp4")
    try:
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-framerate", str(fps), "-start_number", "0",
            "-i", str(Path(frames) / "frame_%06d.png"), "-frames:v", str(count), "-c:v", "libx264", "-threads", "2",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(temporary)], check=True, capture_output=True, timeout=1800)
        proof = verify_video(temporary, fps=fps, count=count, width=width, height=height)
        os.link(temporary, output)
        return proof
    finally:
        temporary.unlink(missing_ok=True)


def _ordered_sources(request, source_root, frame_root):
    frame_root.mkdir()
    dimensions = None
    for index, record in enumerate(request["sources"]):
        source = owned(source_root, record["path"])
        if source.stat().st_size != record["bytes"] or digest(source) != record["sha256"]:
            raise ValueError("Source changed after admission")
        shape = _png_dimensions(source)
        if dimensions is not None and shape != dimensions:
            raise ValueError("Source frame dimensions differ")
        dimensions = shape
        target = frame_root / ("frame_%06d.png" % index)
        shutil.copyfile(source, target)
        if digest(target) != record["sha256"]:
            raise ValueError("Source changed during copy")
    return dimensions


def prepare_video_source(source_record, source_root, controls, destination):
    """Measure presentation timestamps, then decode exact selected frame indices.

    No frame is invented to extend the clip. Upsampling may repeat a measured
    source frame, with that repeated source identity retained in the receipt.
    """
    source = owned(source_root, source_record["path"])
    if source_record["mime"] != "video/mp4" or source.stat().st_size != source_record["bytes"] or not 1 <= source.stat().st_size <= MAX_ARCHIVE or digest(source) != source_record["sha256"]:
        raise ValueError("SOURCE_CLIP_BYTES_CHANGED")
    with source.open("rb") as stream:
        header = stream.read(12)
    if len(header) != 12 or header[4:8] != b"ftyp":
        raise ValueError("SOURCE_IS_NOT_MP4_CONTAINER")
    probe = subprocess.run(["ffprobe", "-v", "error", "-threads", "2", "-protocol_whitelist", "file,pipe",
        "-show_entries", "format=format_name,duration:stream=index,codec_type,width,height,avg_frame_rate,duration,start_time",
        "-of", "json", str(source)], capture_output=True, check=True, timeout=30)
    metadata = json.loads(probe.stdout)
    videos = [stream for stream in metadata.get("streams", []) if stream.get("codec_type") == "video"]
    if len(videos) != 1 or "mp4" not in metadata.get("format", {}).get("format_name", "").split(","):
        raise ValueError("SOURCE_REQUIRES_ONE_MP4_VIDEO_STREAM")
    video = videos[0]
    width, height = video.get("width", 0), video.get("height", 0)
    duration = float(video.get("duration", metadata.get("format", {}).get("duration", 0)))
    input_fps = Fraction(video.get("avg_frame_rate", "0/1"))
    if (not math.isfinite(duration) or not 0 < duration <= 600 or
        min(width, height) < 16 or max(width, height) > 4096 or width * height > 4096 * 2160 or
        not 0 < input_fps <= 120):
        raise ValueError("SOURCE_VIDEO_RESOURCE_LIMIT")
    requested = [Fraction(index, controls["fps"]) for index in range(controls["numFrames"])]
    if float(requested[-1]) >= duration:
        raise ValueError("SOURCE_VIDEO_TOO_SHORT")
    # Decode/probe is bounded by the measured duration/FPS and a wall-clock cap.
    timing = subprocess.run(["ffprobe", "-v", "error", "-threads", "2", "-protocol_whitelist", "file,pipe",
        "-select_streams", "v:0", "-show_frames", "-show_entries", "frame=best_effort_timestamp_time",
        "-of", "csv=p=0", str(source)], capture_output=True, check=True, timeout=120)
    if len(timing.stdout) > 4 * 1024**2:
        raise ValueError("SOURCE_TIMESTAMP_BUDGET_EXCEEDED")
    timestamps = []
    for line in timing.stdout.decode("utf-8").splitlines():
        first = line.split(",", 1)[0].strip()
        if not first:
            continue
        try:
            timestamp = Fraction(first)
        except (ValueError, ZeroDivisionError) as error:
            raise ValueError("SOURCE_PRESENTATION_TIMESTAMP_UNAVAILABLE") from error
        if timestamps and timestamp < timestamps[-1]:
            raise ValueError("SOURCE_PRESENTATION_TIMESTAMPS_NOT_ORDERED")
        timestamps.append(timestamp)
        if len(timestamps) > 72000:
            raise ValueError("SOURCE_FRAME_BUDGET_EXCEEDED")
    if len(timestamps) < controls["numFrames"]:
        raise ValueError("SOURCE_DECODED_FRAME_COUNT_INSUFFICIENT")
    origin = timestamps[0]
    normalized = [timestamp - origin for timestamp in timestamps]
    selected = []
    for timestamp in requested:
        right = bisect.bisect_left(normalized, timestamp)
        candidates = [index for index in (right - 1, right) if 0 <= index < len(normalized)]
        selected.append(min(candidates, key=lambda index: (abs(normalized[index] - timestamp), index)))
    destination = Path(destination)
    destination.mkdir()
    unique = sorted(set(selected))
    decoded = destination / "decoded"; decoded.mkdir()
    selection = "+".join("eq(n\\,%d)" % index for index in unique)
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-threads", "2", "-protocol_whitelist", "file,pipe",
        "-i", str(source), "-map", "0:%d" % video["index"], "-an", "-sn", "-vf",
        "select=" + selection + ",scale=%d:%d:flags=lanczos" % (controls["width"], controls["height"]),
        "-vsync", "0", "-frames:v", str(len(unique)), "-start_number", "0", "-threads", "2",
        str(decoded / "frame_%06d.png")], capture_output=True, check=True, timeout=120)
    generated = sorted(decoded.glob("frame_*.png"))
    if len(generated) != len(unique):
        raise ValueError("SOURCE_SELECTED_FRAME_DECODE_INCOMPLETE")
    selected_files = dict(zip(unique, generated))
    frames, records = [], []
    for index, source_index in enumerate(selected):
        path = destination / ("frame_%06d.png" % index)
        shutil.copyfile(selected_files[source_index], path)
        if _png_dimensions(path) != (controls["width"], controls["height"]):
            raise ValueError("SOURCE_RESIZED_DIMENSIONS_DIFFER")
        frames.append(path)
        records.append({"outputFrame": index, "timeSeconds": float(requested[index]), "sourceFrame": source_index,
            "sourceTimeSeconds": float(normalized[source_index]), "preparedFrame": path.name,
            "bytes": path.stat().st_size, "sha256": digest(path)})
    if source.stat().st_size != source_record["bytes"] or digest(source) != source_record["sha256"]:
        raise ValueError("SOURCE_CLIP_CHANGED_DURING_PREPARATION")
    shutil.rmtree(decoded)
    return frames, {"sourceAssetId": source_record["assetId"], "sourceClipSha256": source_record["sha256"],
        "sourceBytes": source_record["bytes"], "sourceDurationSeconds": duration, "sourceWidth": width,
        "sourceHeight": height, "sourceFrameRate": str(input_fps), "sourceDecodedFrameCount": len(timestamps),
        "sourceTimestampOriginSeconds": float(origin), "requestedFps": controls["fps"], "frameCount": len(frames),
        "selectionPolicy": "nearest decoded presentation timestamp; ties select earlier source frame; upsampling may repeat a measured source frame",
        "resizePolicy": "full decoded raster stretched to requested dimensions with Lanczos; FFmpeg applies display-matrix orientation",
        "requestedWidth": controls["width"], "requestedHeight": controls["height"],
        "extrapolated": False, "frames": records}


def _cogvideox(config, controls, output, checkpoint, sources=(), source_root=None):
    mode = controls.get("mode", "text_to_video")
    prepared, sampling = None, None
    if mode == "video_to_video":
        checkpoint("preparing_reference_video")
        prepared, sampling = prepare_video_source(sources[0], source_root, controls, output / "reference_frames")
    checkpoint("verifying_model")
    manifest = model_manifest(config, verify_bytes=True)
    try:
        import torch
        import diffusers
        from diffusers import CogVideoXPipeline, CogVideoXVideoToVideoPipeline
    except ImportError as error:
        raise Unavailable("COGVIDEOX_RUNTIME_MISSING", "waiting_compute") from error
    model = config["cogvideox"]
    if model["device"] == "cuda" and not torch.cuda.is_available():
        raise Unavailable("CUDA_NOT_AVAILABLE", "waiting_compute")
    torch.set_num_threads(model["cpuThreads"])
    checkpoint("loading_model")
    dtype = getattr(torch, model["dtype"])
    pipeline_type = CogVideoXVideoToVideoPipeline if mode == "video_to_video" else CogVideoXPipeline
    pipe = pipeline_type.from_pretrained(model["modelPath"], torch_dtype=dtype,
        local_files_only=True, use_safetensors=True, low_cpu_mem_usage=True)
    pipe.to(model["device"])
    pipe.vae.enable_tiling(); pipe.vae.enable_slicing()
    generator = torch.Generator(device=model["device"]).manual_seed(controls["seed"])
    frames_root = output / "frames"; frames_root.mkdir()
    started = time.monotonic()
    checkpoint("inference")
    def step_callback(pipeline, step, timestep, tensors):
        steps.append(int(step))
        checkpoint("inference", {"step": int(step), "requestedSteps": controls["numInferenceSteps"]})
        return tensors
    steps = []
    arguments = dict(prompt=controls["prompt"], negative_prompt=controls.get("negativePrompt"),
        width=controls["width"], height=controls["height"], num_inference_steps=controls["numInferenceSteps"],
        guidance_scale=controls["guidanceScale"], generator=generator, output_type="pil", callback_on_step_end=step_callback)
    if mode == "video_to_video":
        from PIL import Image
        reference_images = []
        for path in prepared:
            with Image.open(path) as frame:
                reference_images.append(frame.convert("RGB"))
        arguments.update(video=reference_images, strength=controls["strength"])
    else:
        arguments["num_frames"] = controls["numFrames"]
    with torch.inference_mode():
        generated = pipe(**arguments).frames[0]
    inference_seconds = time.monotonic() - started
    if not steps:
        raise ValueError("V2V_ZERO_DENOISING_STEPS" if mode == "video_to_video" else "MODEL_DENOISING_NOT_EXECUTED")
    if len(generated) != controls["numFrames"]:
        raise ValueError("MODEL_FRAME_COUNT_MISMATCH: requested=%d observed=%d" % (controls["numFrames"], len(generated)))
    for index, frame in enumerate(generated):
        if frame.size != (controls["width"], controls["height"]):
            raise ValueError("Model output dimensions differ")
        frame.convert("RGB").save(frames_root / ("frame_%06d.png" % index))
    checkpoint("encoding")
    video = output / "master.mp4"
    proof = encode_video(frames_root, video, controls["fps"], len(generated), controls["width"], controls["height"])
    model_manifest(config, verify_bytes=True)
    execution = {"backend": "cogvideox", "mode": mode, "adapter": "diffusers." + pipeline_type.__name__,
        "modelId": manifest["modelId"], "revision": manifest["revision"], "license": manifest["license"],
        "modelManifestSha256": model["manifestSha256"], "modelBytesVerified": True,
        "device": model["device"], "dtype": model["dtype"], "gpuObserved": model["device"] == "cuda",
        "torchVersion": torch.__version__, "diffusersVersion": diffusers.__version__, "seed": controls["seed"],
        "numInferenceSteps": controls["numInferenceSteps"], "actualDenoisingSteps": len(steps), "inferenceSeconds": inference_seconds,
        "realModelInference": True, "nativeRendered": False, "originalImageReconstruction": False,
        "cinematicAcceptance": False, "video": proof}
    if mode == "video_to_video":
        execution.update(strength=controls["strength"], sourceClipSha256=sampling["sourceClipSha256"], inputSampling=sampling)
    return [(video, "video", "video/mp4")], execution


def execute(config, request, source_root, work, checkpoint):
    """Runs inside a managed worker process group; all child processes inherit it."""
    backend, controls = request["backend"], request["controls"]
    work = Path(work)
    checkpoint_path = work / "execution.json"
    if checkpoint_path.is_file():
        saved = read_json(checkpoint_path)
        outputs = []
        for item in saved["outputs"]:
            path = owned(work, item["path"])
            if path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
                raise ValueError("Completed backend checkpoint bytes changed")
            outputs.append((path, item["role"], item["mime"]))
        checkpoint("resumed_verified_output")
        return outputs, saved["actualExecution"]
    output = work / "output"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir()
    if backend == "cogvideox":
        outputs, proof = _cogvideox(config, controls, output, checkpoint, request["sources"], source_root)
    elif backend == "kokoro":
        from .speech import render
        outputs, proof = render(config, controls, output, checkpoint)
    elif backend == "blender":
        from cinematic3d.pipeline import build
        timeline_path = work / "timeline.json"
        atomic_json(timeline_path, controls["timeline"])
        checkpoint("blender_generation")
        result = build(timeline_path, output / "generation", blender=config["blenderExecutable"],
            render_frames=controls.get("renderFrames", 0), conditioning_frames=len(controls.get("conditioningFrames", [])))
        outputs = [(output / "generation" / "cinematic.vscinematic", "cinematic_bundle", "application/vnd.videostudio.cinematic3d")]
        if result.get("video"):
            outputs.append((output / "generation" / "master.mp4", "video", "video/mp4"))
        proof = {"backend": "blender", "execution": "cloud_blender", "realBlenderExecution": True,
                 "nativeRendered": False, "originalImageReconstruction": False, "cinematicAcceptance": False,
                 "bundleSha256": result["bundleSha256"], "video": result.get("video"), "conditioning": result.get("conditioning")}
    else:
        frames = output / "frames"
        width, height = _ordered_sources(request, source_root, frames)
        count = len(request["sources"])
        fps = controls.get("fps", controls.get("outputFps"))
        proof = {"backend": backend, "nativeRendered": False, "originalImageReconstruction": False, "cinematicAcceptance": False}
        if backend == "frame_sequence":
            if (width, height) != (controls["width"], controls["height"]):
                raise ValueError("Declared frame_sequence dimensions differ from source pixels")
            proof.update(realFrameEncoding=True, neuralInference=False)
        else:
            from cinematic3d.__main__ import _config, _runner_config
            from cinematic3d.polish import interpolate_frames
            trusted, parent = _config(Path(config["interpolationConfig"]))
            runner = _runner_config(trusted, parent, "interpolation")
            checkpoint("interpolation")
            interpolated = output / "interpolated"
            receipt = interpolate_frames(runner, [frames / ("frame_%06d.png" % n) for n in range(count)],
                controls["inputFps"], fps, interpolated, cuts=controls.get("cuts", ()), hit_stop_ranges=controls.get("hitStopRanges", ()))
            if receipt.get("status") != "completed":
                if receipt.get("status") == "unavailable":
                    raise Unavailable(receipt.get("code", "INTERPOLATION_UNAVAILABLE"))
                raise RuntimeError(receipt.get("code", "INTERPOLATION_FAILED"))
            frames, count = interpolated, receipt["frameCount"]
            proof.update(provider=receipt["provider"], interpolationEvidence=receipt["evidence"],
                         cuts=receipt["cuts"], protectedOutputFrames=receipt["protectedOutputFrames"])
        checkpoint("encoding")
        video = output / "master.mp4"
        proof["video"] = encode_video(frames, video, fps, count, width, height)
        outputs = [(video, "video", "video/mp4")]
    atomic_json(checkpoint_path, {"outputs": [{"path": path.relative_to(work).as_posix(), "role": role,
        "mime": mime, "bytes": path.stat().st_size, "sha256": digest(path)} for path, role, mime in outputs],
        "actualExecution": proof})
    checkpoint("backend_output_verified")
    return outputs, proof
