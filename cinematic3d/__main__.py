"""Operator CLI for verified Blender production and configured optional providers."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from . import camera_solve, pipeline, polish, providers, timeline, characters, motion

CONFIG_SCHEMA = "videostudio.cinematic3d.local-config.v1"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _fields(value, required=(), optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        raise ValueError("Missing or unknown local configuration/request fields")
    return value


def _text(value, maximum=4096):
    if not isinstance(value, str) or not value or len(value) > maximum or "\0" in value:
        raise ValueError("A bounded nonempty string is required")
    return value


def _hash(value):
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise ValueError("An exact lowercase SHA-256 is required")
    return value


def _path(value, parent):
    path = Path(_text(value)).expanduser()
    return path if path.is_absolute() else parent / path


def _strings(value, maximum=256):
    if not isinstance(value, list) or not 1 <= len(value) <= maximum:
        raise ValueError("A bounded nonempty string array is required")
    return tuple(_text(item) for item in value)


def _config(path):
    if path is None:
        return {}, Path.cwd()
    value = providers.read_json(path, limit=512 * 1024)
    _fields(value, ("schema",), ("blenderExecutable", "pose", "interpolation", "comfyui"))
    if value["schema"] != CONFIG_SCHEMA:
        raise ValueError("Unknown trusted local configuration schema")
    if "blenderExecutable" in value:
        _text(value["blenderExecutable"])
    for name in ("pose", "interpolation", "comfyui"):
        if name in value and not isinstance(value[name], dict):
            raise ValueError("Configured provider must be an object")
    return value, path.expanduser().absolute().parent


def _command(values, parent):
    command = list(_strings(values, 32))
    # Explicit path arguments are relative to the local config, never a timeline.
    for index, value in enumerate(command):
        if value.startswith(("./", "../")) or (not value.startswith("-") and (parent / value).is_file()):
            command[index] = str(_path(value, parent).absolute())
    return tuple(command)


def _runner_config(config, parent, key):
    if key not in config:
        return None
    value = _fields(config[key], ("family", "command", "weights", "weightsSha256"), ("timeoutSeconds",))
    family = _text(value["family"], 32)
    families = ("4dhumans", "vitpose", "smplx") if key == "pose" else ("rife", "film")
    if family not in families:
        raise ValueError("Unsupported configured provider family")
    cls = providers.PoseRunnerConfig if key == "pose" else polish.InterpolationConfig
    return cls(family, _command(value["command"], parent), _path(value["weights"], parent),
               _hash(value["weightsSha256"]), providers.check_timeout(value.get("timeoutSeconds", 120)))


def _comfy_config(config, parent):
    if "comfyui" not in config:
        return None
    value = _fields(config["comfyui"], ("endpoint", "workflowFile", "workflowSha256", "allowedNodeTypes", "models",
                    "imageInputBinding", "outputNodes"), ("timeoutSeconds", "pollIntervalSeconds"))
    models = value["models"]
    if not isinstance(models, list) or not 1 <= len(models) <= 64:
        raise ValueError("ComfyUI requires 1..64 explicit local model bindings")
    accepted = []
    for model in models:
        _fields(model, ("path", "sha256", "nodeId", "inputName"), ("modelName",))
        accepted.append(polish.ComfyModel(_path(model["path"], parent), _hash(model["sha256"]),
                        _text(model["nodeId"], 80), _text(model["inputName"], 128),
                        _text(model["modelName"], 256) if "modelName" in model else None))
    binding = _strings(value["imageInputBinding"], 2)
    if len(binding) != 2:
        raise ValueError("ComfyUI imageInputBinding requires node ID and input name")
    polling = value.get("pollIntervalSeconds", .25)
    if isinstance(polling, bool) or not isinstance(polling, (int, float)) or not 0 < polling <= 10:
        raise ValueError("ComfyUI polling interval must be in 0..10 seconds")
    return polish.ComfyUIConfig(_text(value["endpoint"]), _path(value["workflowFile"], parent),
              _hash(value["workflowSha256"]), _strings(value["allowedNodeTypes"]), tuple(accepted), binding,
              _strings(value["outputNodes"]), providers.check_timeout(value.get("timeoutSeconds", 120)), polling)


def _new_output(path):
    if path is not None and (path.exists() or path.is_symlink()):
        raise FileExistsError("Accepted output already exists; choose a new path")


def _write_json(path, value):
    _new_output(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode("utf-8")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".cinematic-receipt-", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        # Create-only atomic publication also rejects a concurrent output writer.
        os.link(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _timeline_path(args):
    if args.timeline is not None and args.timeline_positional is not None and args.timeline != args.timeline_positional:
        raise ValueError("Choose a positional timeline or --timeline, not two different inputs")
    value = args.timeline or args.timeline_positional
    if value is None:
        raise ValueError("A timeline file is required")
    return value


def _integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("An integer within the declared bounds is required")
    return value


def _source_records(entries, accepted):
    if not isinstance(entries, dict) or len(entries) > 256:
        raise ValueError("A bounded exported source record map is required")
    total = 0
    for asset_id, entry in entries.items():
        if not isinstance(asset_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}", asset_id):
            raise ValueError("Invalid exported source identity")
        _fields(entry, ("assetId", "mime", "metadataSha256", "sourceSha256", "bytes", "codedWidth", "codedHeight",
                        "exifOrientation", "orientedWidth", "orientedHeight"))
        if entry["assetId"] != asset_id or not re.fullmatch(r"image/[A-Za-z0-9.+_-]{1,94}", _text(entry["mime"], 100)):
            raise ValueError("Exported source identity/MIME differs")
        _hash(entry["metadataSha256"])
        _hash(entry["sourceSha256"])
        total += _integer(entry["bytes"], 1, 32 * 1024 * 1024)
        if total > 512 * 1024 * 1024:
            raise ValueError("Declared original source bytes exceed 512 MiB")
        width = _integer(entry["codedWidth"], 16, 8192)
        height = _integer(entry["codedHeight"], 16, 8192)
        orientation = _integer(entry["exifOrientation"], 1, 8)
        if (_integer(entry["orientedWidth"], 16, 8192), _integer(entry["orientedHeight"], 16, 8192)) != (
                (height, width) if orientation >= 5 else (width, height)):
            raise ValueError("Exported source orientation dimensions differ")
    selected = set()
    for shot in accepted["shots"]:
        for anchor in shot.get("sourceAnchors", []):
            asset_id = anchor["assetId"]
            selected.add(asset_id)
            if asset_id not in entries or entries[asset_id]["sourceSha256"] != anchor["sha256"]:
                raise ValueError("Ordered source anchor differs from its exported byte record")
    if selected != set(entries):
        raise ValueError("Exported source records contain foreign or missing source bindings")


def _input_timeline(path):
    # Parse and hash the same frozen bytes, including an inert phone export wrapper.
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 512 * 1024:
        raise ValueError("Timeline input must be a regular JSON file of at most 512 KiB")
    raw = path.read_bytes()
    if len(raw) > 512 * 1024:
        raise ValueError("Timeline input grew beyond 512 KiB")
    with tempfile.TemporaryDirectory(prefix="c3d-cli-input-") as temporary:
        frozen = Path(temporary) / "input.json"
        frozen.write_bytes(raw)
        value = pipeline.read_json(frozen)
    digest = hashlib.sha256(raw).hexdigest()
    proof = {"inputArtifactSha256": digest, "sourceBytesVerified": False,
             "sourceProjectRevisionVerified": False}
    if value.get("schema") != "videostudio.cinematic3d.export-request.v1":
        return timeline.validate_timeline(value), dict(proof, sourceTimelineSha256=digest), None
    _fields(value, ("schema", "timeline", "sourceProjectId", "sourceProjectUpdatedAt", "sourceEntries",
                    "bundleSha256", "programSha256"))
    project_id = _text(value["sourceProjectId"], 96)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}", project_id):
        raise ValueError("Invalid exported project identity")
    revision = _integer(value["sourceProjectUpdatedAt"], 0, (1 << 63) - 1)
    accepted = timeline.validate_timeline(value["timeline"])
    _source_records(value["sourceEntries"], accepted)
    proof.update(exportRequestSha256=digest, sourceProjectId=project_id, sourceProjectUpdatedAt=revision,
                 sourceEntries=value["sourceEntries"], sourceBundleSha256=_hash(value["bundleSha256"]),
                 sourceProgramSha256=_hash(value["programSha256"]))
    return accepted, proof, raw


def _blender(args, config, parent):
    value = args.blender or config.get("blenderExecutable", "blender")
    if "/" in value or "\\" in value:
        return str(_path(value, parent).absolute())
    return _text(value)


def _capabilities(config, parent, blender):
    executable = shutil.which(blender)
    engine = {"status": "unavailable", "executableFound": executable is not None,
              "runtimeVerified": False, "rigifyVerified": None, "gpuObserved": None}
    if executable:
        try:
            result = subprocess.run([executable, "--version"], capture_output=True, text=True, timeout=10, check=True)
            first = result.stdout.splitlines()[0] if result.stdout else ""
            if first.startswith("Blender "):
                engine.update(status="available", version=first[:128], runtimeVerified=True)
        except (OSError, subprocess.SubprocessError):
            pass
    optional = {}
    for key, adapter in (("pose", _runner_config), ("interpolation", _runner_config)):
        configured = adapter(config, parent, key)
        optional[key] = {"status": "configured" if configured else "unavailable",
                         "runtimeVerified": False, "modelReadinessVerified": False}
        if configured:
            optional[key]["family"] = configured.family
        else:
            optional[key]["code"] = "POSE_PROVIDER_NOT_CONFIGURED" if key == "pose" else "INTERPOLATION_NOT_CONFIGURED"
    comfy = _comfy_config(config, parent)
    optional["comfyui"] = {"status": "configured" if comfy else "unavailable", "runtimeVerified": False,
                           "endpointReadinessVerified": False, "runtimeWeightsSha256Verified": None}
    if not comfy:
        optional["comfyui"]["code"] = "COMFYUI_NOT_CONFIGURED"
    camera_found = all(importlib.util.find_spec(name) is not None for name in ("cv2", "numpy"))
    optional["camera"] = {"status": "installed" if camera_found else "unavailable", "runtimeVerified": False}
    return {"ok": True, "schema": "videostudio.cinematic3d.capabilities.v1", "blender": engine,
            "providers": optional, "timelineSchema": timeline.SCHEMA, "bundleSchema": "videostudio.cinematic3d.bundle.v1",
            "fps": [24, 30, 60], "maxShots": 80, "maxFrames": 12000, "nativeMinimumVersion": "3.8.0",
            "actions": list(motion.ALL_ACTIONS), "defaultLibraryActions": list(motion.LEGACY_ACTIONS),
            "motionStyles": list(characters.MOTION_STYLES), "actorPresets": list(characters.PRESETS),
            "conditioning": {"schema": "videostudio.cinematic3d.conditioning.v1", "maxFrames": 1200,
                "passes": ["pose", "beauty", "depth", "normals", "actors", "props"], "nativeRendered": False},
            "nativeRendered": False, "cinematicAcceptance": False}


def _request(path, required, optional=()):
    return _fields(providers.read_json(path, limit=512 * 1024), required, optional)


def _execute(args):
    config, parent = _config(getattr(args, "config", None))
    if args.command == "capabilities":
        return _capabilities(config, parent, _blender(args, config, parent))
    if args.command == "validate":
        path = _timeline_path(args)
        accepted, proof, _ = _input_timeline(path)
        compiled = timeline.compile_timeline(accepted)
        if args.output:
            _write_json(args.output, accepted)
        return {"ok": True, "schema": timeline.SCHEMA, "fps": accepted["fps"], "shotCount": len(accepted["shots"]),
                "totalFrames": compiled["totalFrames"], "durationSeconds": compiled["durationSeconds"],
                "motionProvider": accepted["motionProvider"], **proof,
                "nativeRendered": False, "cinematicAcceptance": False}
    if args.command == "build":
        if not 0 <= args.render_frames <= 12000:
            raise ValueError("--render-frames must be 0..12000")
        if not 0 <= args.conditioning_frames <= 1200:
            raise ValueError("--conditioning-frames must be 0..1200")
        selected = characters.select_library_actions(args.actions.split(",")) if args.actions is not None else None
        _new_output(args.output)
        accepted, proof, wrapper = _input_timeline(_timeline_path(args))
        with tempfile.TemporaryDirectory(prefix="c3d-cli-timeline-") as temporary:
            normalized = Path(temporary) / "timeline.json"
            normalized.write_text(json.dumps(accepted, ensure_ascii=False, allow_nan=False), encoding="utf-8")
            provenance = {"source_request_bytes": wrapper} if wrapper is not None else {}
            result = pipeline.build(normalized, args.output, blender=_blender(args, config, parent),
                                    library_only=args.library_only, render_frames=args.render_frames,
                                    conditioning_frames=args.conditioning_frames, selected_actions=selected, **provenance)
        return dict(result, **proof)
    if args.command == "bundle":
        _new_output(args.output)
        return pipeline.build_bundle(args.generation, args.output)
    if args.command == "pose":
        result = providers.estimate_pose(_runner_config(config, parent, "pose"), args.source, _hash(args.source_sha256))
    elif args.command == "guide":
        result = motion.map_reference_guide(providers.read_json(args.request, limit=512*1024))
    elif args.command == "camera":
        request = _request(args.request, ("points3d", "points2d", "intrinsics"),
                           ("maxReprojectionErrorPx", "imageWidth", "imageHeight", "phase"))
        viewport = "imageWidth" in request or "imageHeight" in request
        if viewport and not {"imageWidth", "imageHeight"} <= request.keys():
            raise ValueError("Camera projection requires both imageWidth and imageHeight")
        if "phase" in request and not viewport:
            raise ValueError("A timeline camera phase requires declared imageWidth and imageHeight")
        if "phase" in request and (isinstance(request["phase"], bool) or not isinstance(request["phase"], (int, float)) or not 0 <= request["phase"] <= 1):
            raise ValueError("Camera keyframe phase must be finite numeric data within 0..1")
        if viewport:
            _integer(request["imageWidth"], 1, 8192)
            _integer(request["imageHeight"], 1, 8192)
        result = camera_solve.solve_camera(request["points3d"], request["points2d"], request["intrinsics"],
                            max_reprojection_error_px=request.get("maxReprojectionErrorPx", 2.0))
        if viewport and result.get("status") == "completed":
            result.update(camera_solve.camera_keyframe(result, request["imageWidth"], request["imageHeight"],
                                                      request.get("phase", 0.0)))
    elif args.command == "interpolate":
        request = _request(args.request, ("frames", "inputFps", "outputFps"), ("cuts", "hitStopRanges"))
        frames = request["frames"]
        if not isinstance(frames, list) or not 1 <= len(frames) <= 12000:
            raise ValueError("An ordered array of 1..12000 input PNG paths is required")
        frames = [_path(value, args.request.expanduser().absolute().parent) for value in frames]
        result = polish.interpolate_frames(_runner_config(config, parent, "interpolation"), frames,
                    request["inputFps"], request["outputFps"], args.output, cuts=request.get("cuts", ()),
                    hit_stop_ranges=request.get("hitStopRanges", ()))
    elif args.command == "comfyui":
        result = polish.run_comfyui(_comfy_config(config, parent), args.source, _hash(args.source_sha256), args.output)
    else:
        raise ValueError("Unsupported cinematic command")
    result = dict(result)
    result.update(ok=result.get("status") == "completed", nativeRendered=False, cinematicAcceptance=False)
    return result


def _parser():
    parser = argparse.ArgumentParser(prog="python -m cinematic3d", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("validate", "build"):
        command = commands.add_parser(name)
        command.add_argument("timeline_positional", nargs="?", type=Path)
        command.add_argument("--timeline", type=Path)
        command.add_argument("--output", type=Path, required=name == "build")
        if name == "build":
            command.add_argument("--blender")
            command.add_argument("--config", type=Path)
            command.add_argument("--library-only", action="store_true")
            command.add_argument("--render-frames", type=int, default=0)
            command.add_argument("--conditioning-frames", type=int, default=0)
            command.add_argument("--actions", help="Comma-separated selected library actions")
    bundle = commands.add_parser("bundle")
    bundle.add_argument("--generation", type=Path, required=True)
    bundle.add_argument("--output", type=Path, required=True)
    capabilities = commands.add_parser("capabilities")
    capabilities.add_argument("--config", type=Path)
    capabilities.add_argument("--blender")
    for name in ("pose", "comfyui"):
        command = commands.add_parser(name)
        command.add_argument("--source", type=Path, required=True)
        command.add_argument("--source-sha256", required=True)
        command.add_argument("--config", type=Path)
        command.add_argument("--output", type=Path, required=name == "comfyui")
        if name == "comfyui":
            command.add_argument("--receipt", type=Path)
    camera = commands.add_parser("camera")
    camera.add_argument("--request", type=Path, required=True)
    camera.add_argument("--output", type=Path)
    guide = commands.add_parser("guide")
    guide.add_argument("--request", type=Path, required=True)
    guide.add_argument("--output", type=Path, required=True)
    interpolation = commands.add_parser("interpolate")
    interpolation.add_argument("--request", type=Path, required=True)
    interpolation.add_argument("--output", type=Path, required=True)
    interpolation.add_argument("--config", type=Path)
    interpolation.add_argument("--receipt", type=Path)
    return parser


def main(argv=None):
    args = _parser().parse_args(argv)
    receipt = getattr(args, "receipt", None)
    if args.command in ("pose", "camera", "guide"):
        receipt = args.output
    try:
        _new_output(receipt)
        if args.command == "validate":
            _new_output(args.output)
        result = _execute(args)
        if receipt:
            _write_json(receipt, result)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 69 if result.get("status") == "unavailable" else 1 if result.get("status") == "failed" else 0
    except providers.ProviderFailure as error:
        result = providers.failure_receipt(error, args.command)
        print(json.dumps(dict(result, ok=False, nativeRendered=False, cinematicAcceptance=False)))
        return 69 if error.status == "unavailable" else 1
    except subprocess.TimeoutExpired:
        print(json.dumps({"ok": False, "status": "failed", "code": "PIPELINE_TIMEOUT", "nativeRendered": False}))
        return 1
    except subprocess.CalledProcessError as error:
        print(json.dumps({"ok": False, "status": "failed", "code": "PIPELINE_EXECUTION_FAILED", "exitCode": error.returncode,
                          "nativeRendered": False}))
        return 1
    except (OSError, ValueError, TypeError, KeyError, RecursionError) as error:
        print(json.dumps({"ok": False, "status": "failed", "code": "INVALID_INPUT", "message": str(error),
                          "nativeRendered": False, "cinematicAcceptance": False}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
