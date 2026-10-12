"""Evaluated Blender conditioning sidecars, separate from the phone archive.

Blender is imported only by the exporter. Pure helpers and byte verification are
usable by the publisher without Blender. This is cloud render evidence, never
source-image reconstruction or Android evidence.
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct

from .motion import PRIMARY_JOINTS
from .timeline import sample_shot, validate_timeline

SCHEMA = "videostudio.cinematic3d.conditioning.v1"
MAX_FRAMES = 1200
MAX_JSON = 8 * 1024 * 1024
MAX_FILE = 128 * 1024 * 1024
MAX_TOTAL = 8 * 1024 * 1024 * 1024
ROLES = ("pose", "beauty", "depth", "normals", "actors", "props")
FILENAMES = {"pose": "pose.json", "beauty": "beauty.png", "depth": "depth.exr",
             "normals": "normals.png", "actors": "actors.png", "props": "props.png"}
_SHA = re.compile(r"[a-f0-9]{64}\Z")
_BASIS = ((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1))


def frame_directory(index: int) -> str:
    if type(index) is not int or not 0 <= index < MAX_FRAMES:
        raise ValueError("Conditioning frame index must be 0..1199")
    return "frames/frame_%06d" % index


def to_yup(point) -> list[float]:
    values = [float(value) for value in point]
    if len(values) != 3 or not all(math.isfinite(value) for value in values):
        raise ValueError("World point must have three finite coordinates")
    return [values[0], values[2], -values[1]]


def matrix_rows(matrix) -> list[list[float]]:
    rows = [[float(value) for value in row] for row in matrix]
    if len(rows) != 4 or any(len(row) != 4 for row in rows) or not all(
            math.isfinite(value) for row in rows for value in row):
        raise ValueError("Expected a finite 4x4 matrix")
    return rows


def _multiply(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def world_matrix_yup(matrix, *, camera: bool = False) -> list[list[float]]:
    """Camera local axes stay X-right/Y-up/Z-back; object local axes become Y-up."""
    result = _multiply(_BASIS, matrix_rows(matrix))
    if not camera:
        inverse = [list(row) for row in zip(*_BASIS)]
        result = _multiply(result, inverse)
    return result


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def _json(path: Path, value) -> None:
    encoded = (json.dumps(value, ensure_ascii=False, separators=(",", ":"),
                          sort_keys=True, allow_nan=False) + "\n").encode("utf-8")
    if len(encoded) > MAX_JSON:
        raise ValueError("Conditioning JSON exceeds its budget")
    with path.open("xb") as stream:
        stream.write(encoded)


def _read_json(path: Path):
    if path.stat().st_size > MAX_JSON:
        raise ValueError("Conditioning JSON exceeds its budget")
    def pairs(entries):
        value = {}
        for key, child in entries:
            if key in value:
                raise ValueError("Duplicate conditioning JSON key")
            value[key] = child
        return value
    try:
        result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs,
                            parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ValueError("Malformed conditioning JSON") from error
    stack = [(result, 0)]
    count = 0
    while stack:
        value, depth = stack.pop()
        count += 1
        if depth > 24 or count > 500000:
            raise ValueError("Conditioning JSON exceeds structural budget")
        if isinstance(value, dict):
            stack.extend((child, depth + 1) for child in value.values())
        elif isinstance(value, list):
            stack.extend((child, depth + 1) for child in value)
        elif isinstance(value, float) and not math.isfinite(value):
            raise ValueError("Nonfinite conditioning JSON")
    return result


def _owned(root: Path, name: str) -> Path:
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_./-]{1,160}", name):
        raise ValueError("Invalid conditioning path")
    if name.startswith("/") or any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("Conditioning path traversal")
    path = root
    for part in name.split("/"):
        path = path / part
        if path.is_symlink():
            raise ValueError("Conditioning symlinks are unavailable")
    if not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Missing owned conditioning file")
    return path


def _receipt(root: Path, count: int) -> dict:
    path = root / "manifest.json"
    return {"path": str(path.resolve()), "sha256": digest(path), "bytes": path.stat().st_size,
            "frameCount": count, "execution": "cloud_blender", "nativeRendered": False,
            "originalImageReconstruction": False}


def verify_conditioning(folder: Path) -> dict:
    """Verify an ordered, bounded sidecar generation without trusting its hashes."""
    root = Path(folder)
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Conditioning must be an owned directory")
    manifest = _read_json(_owned(root, "manifest.json"))
    if not isinstance(manifest, dict) or manifest.get("schema") != SCHEMA:
        raise ValueError("Unknown conditioning schema")
    count = manifest.get("frameCount")
    if type(count) is not int or not 0 <= count <= MAX_FRAMES:
        raise ValueError("Invalid conditioning frame count")
    if manifest.get("execution") != "cloud_blender" or manifest.get("nativeRendered") is not False or manifest.get("originalImageReconstruction") is not False:
        raise ValueError("Conditioning evidence labels must identify cloud-only output")
    width, height = manifest.get("width"), manifest.get("height")
    if any(type(value) is not int or not 1 <= value <= 4096 for value in (width, height)):
        raise ValueError("Invalid conditioning dimensions")
    if manifest.get("fps") not in (24, 30, 60) or manifest.get("jointOrder") != list(PRIMARY_JOINTS):
        raise ValueError("Invalid conditioning clock or semantic joint order")
    frames = manifest.get("frames")
    if not isinstance(frames, list) or len(frames) != count:
        raise ValueError("Incomplete conditioning frame sequence")
    expected = {"manifest.json"}
    total = (root / "manifest.json").stat().st_size
    for index, frame in enumerate(frames):
        if not isinstance(frame, dict) or frame.get("outputFrame") != index or frame.get("blenderFrame") != index + 1:
            raise ValueError("Conditioning frame order differs from the output clock")
        files = frame.get("files")
        if not isinstance(files, list) or [item.get("role") for item in files if isinstance(item, dict)] != list(ROLES):
            raise ValueError("Incomplete conditioning pass set")
        for record in files:
            if set(record) != {"role", "path", "bytes", "sha256"}:
                raise ValueError("Invalid conditioning artifact descriptor")
            role = record["role"]
            name = frame_directory(index) + "/" + FILENAMES[role]
            if record["path"] != name or name in expected:
                raise ValueError("Duplicate or incorrectly bound conditioning file")
            expected.add(name)
            path = _owned(root, name)
            size = record["bytes"]
            if type(size) is not int or not 1 <= size <= MAX_FILE or path.stat().st_size != size:
                raise ValueError("Conditioning artifact size changed")
            total += size
            if total > MAX_TOTAL:
                raise ValueError("Conditioning exceeds its total byte budget")
            if not isinstance(record["sha256"], str) or not _SHA.fullmatch(record["sha256"]) or digest(path) != record["sha256"]:
                raise ValueError("Conditioning artifact bytes changed")
            if role == "pose":
                pose = _read_json(path)
                if pose.get("outputFrame") != index or pose.get("blenderFrame") != index + 1:
                    raise ValueError("Pose clock differs from its frame binding")
                if pose.get("jointOrder") != list(PRIMARY_JOINTS) or set(pose.get("actors", {})) != {"black", "white"}:
                    raise ValueError("Pose semantic joints or actors differ")
                for actor in pose["actors"].values():
                    if len(actor.get("pose3d", [])) != 16 or len(actor.get("pose2d", [])) != 16:
                        raise ValueError("Pose must contain sixteen evaluated joints")
            elif path.suffix == ".png":
                with path.open("rb") as stream:
                    header = stream.read(24)
                if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR" or struct.unpack(">II", header[16:24]) != (width, height):
                    raise ValueError("Rendered PNG dimensions or signature differ")
            else:
                with path.open("rb") as stream:
                    if stream.read(4) != b"\x76\x2f\x31\x01":
                        raise ValueError("Depth must be a genuine OpenEXR artifact")
    actual = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("Conditioning symlinks are unavailable")
        if path.is_file():
            actual.add(path.relative_to(root).as_posix())
            if len(actual) > 1 + MAX_FRAMES * len(ROLES):
                raise ValueError("Conditioning file count exceeds its budget")
    if actual != expected:
        raise ValueError("Unbound conditioning artifacts are unavailable")
    return _receipt(root, count)


def _compositor(scene):
    """One render produces beauty and raw data passes with explicit colour policy."""
    scene.use_nodes = True
    tree = scene.node_tree
    tree.nodes.clear()
    layer = scene.view_layers[0]
    layer.use_pass_z = layer.use_pass_normal = layer.use_pass_object_index = True
    # The copied scene is not bpy.context.scene. Register Cycles passes on its
    # own layer rather than relying on context-bound property update callbacks.
    layer.update_render_passes()
    render = tree.nodes.new("CompositorNodeRLayers")
    render.scene = scene
    render.layer = layer.name
    layer.update_render_passes()
    required = {"Image", "Depth", "Normal", "IndexOB"}
    missing = required - {socket.name for socket in render.outputs}
    if missing:
        raise RuntimeError("Copied conditioning render layer did not register required passes: " +
                           ",".join(sorted(missing)) + "; available=" +
                           ",".join(socket.name for socket in render.outputs))
    outputs = {}
    def output(role, source, raw=True):
        node = tree.nodes.new("CompositorNodeOutputFile")
        node.file_slots[0].path = role + "_"
        node.format.file_format = "OPEN_EXR" if role == "depth" else "PNG"
        node.format.color_mode = ("BW" if role == "depth" else
                                  scene.render.image_settings.color_mode if role == "beauty" else "RGB")
        node.format.color_depth = "32" if role == "depth" else "8"
        if role == "depth":
            node.format.exr_codec = "ZIP"
        if raw:
            node.format.color_management = "OVERRIDE"
            node.format.view_settings.view_transform = "Raw"
            node.format.view_settings.look = "None"
            node.format.view_settings.exposure = 0
            node.format.view_settings.gamma = 1
            if hasattr(node.file_slots[0], "save_as_render"):
                node.file_slots[0].save_as_render = False
        tree.links.new(source, node.inputs[0])
        outputs[role] = node
    output("beauty", render.outputs["Image"], raw=False)
    output("depth", render.outputs["Depth"])
    separate = tree.nodes.new("CompositorNodeSepRGBA")
    tree.links.new(render.outputs["Normal"], separate.inputs[0])
    combine = tree.nodes.new("CompositorNodeCombRGBA")
    for channel, axis, scale in ((0, "R", .5), (1, "B", .5), (2, "G", -.5)):
        multiply = tree.nodes.new("CompositorNodeMath")
        multiply.operation = "MULTIPLY_ADD"
        multiply.inputs[1].default_value, multiply.inputs[2].default_value = scale, .5
        tree.links.new(separate.outputs[axis], multiply.inputs[0])
        tree.links.new(multiply.outputs[0], combine.inputs[channel])
    combine.inputs[3].default_value = 1
    output("normals", combine.outputs[0])
    for role, indices in (("actors", (1, 2)), ("props", (3, 4))):
        channels = tree.nodes.new("CompositorNodeCombRGBA")
        channels.inputs[2].default_value = 0
        channels.inputs[3].default_value = 1
        for channel, pass_index in enumerate(indices):
            mask = tree.nodes.new("CompositorNodeIDMask")
            mask.index = pass_index
            mask.use_antialiasing = False
            tree.links.new(render.outputs["IndexOB"], mask.inputs[0])
            tree.links.new(mask.outputs[0], channels.inputs[channel])
        output(role, channels.outputs[0])
    return outputs


def _camera_metadata(scene, camera, depsgraph, width, height):
    from mathutils import Matrix
    evaluated = camera.evaluated_get(depsgraph)
    projection = matrix_rows(evaluated.calc_matrix_camera(
        depsgraph, x=width, y=height, scale_x=scene.render.pixel_aspect_x,
        scale_y=scene.render.pixel_aspect_y))
    world = world_matrix_yup(evaluated.matrix_world, camera=True)
    data = evaluated.data
    intrinsics = None
    if data.type == "PERSP":
        intrinsics = [[projection[0][0] * width / 2, 0, (1 - projection[0][2]) * width / 2],
                      [0, projection[1][1] * height / 2, (1 + projection[1][2]) * height / 2],
                      [0, 0, 1]]
    return {"name": camera.name, "type": data.type, "worldFromCamera": world,
            "cameraFromWorld": matrix_rows(Matrix(world).inverted()), "projectionMatrix": projection,
            "intrinsicsPixels": intrinsics, "intrinsicsAxes": "X right, Y down, Z forward",
            "cameraLocalAxes": "X right, Y up, Z backward", "clipConvention": "OpenGL NDC [-1,1]",
            "lensMm": data.lens, "sensorWidthMm": data.sensor_width, "sensorHeightMm": data.sensor_height,
            "sensorFit": data.sensor_fit, "shift": [data.shift_x, data.shift_y],
            "clipStartMeters": data.clip_start, "clipEndMeters": data.clip_end,
            "pixelAspect": [scene.render.pixel_aspect_x, scene.render.pixel_aspect_y]}


def _pose(state, scene, index, clock, width, height):
    from bpy_extras.object_utils import world_to_camera_view
    depsgraph = scene.view_layers[0].depsgraph
    camera = state["camera"].evaluated_get(depsgraph)
    actors = {}
    for actor in state["actors"]:
        rig = actor["rig"].evaluated_get(depsgraph)
        names = list(rig.get("primary_bones", []))
        if len(names) != 16 or list(rig.get("primary_joints", [])) != list(PRIMARY_JOINTS):
            raise ValueError("Conditioning requires the explicit sixteen-primary rig mapping")
        pose3d, pose2d = [], []
        for name in names:
            bone = rig.pose.bones.get(name)
            if bone is None:
                raise ValueError("Mapped conditioning bone is unavailable")
            point = rig.matrix_world @ bone.head
            projected = world_to_camera_view(scene, camera, point)
            pose3d.append(to_yup(point))
            pose2d.append({"xy": [float(projected.x), float(1 - projected.y)],
                           "cameraDepthMeters": float(projected.z),
                           "inCanvasAndClip": bool(0 <= projected.x <= 1 and 0 <= projected.y <= 1 and
                                                   camera.data.clip_start <= projected.z <= camera.data.clip_end)})
        root = actor["root"].evaluated_get(depsgraph)
        sword = actor["sword"].evaluated_get(depsgraph)
        actors[actor["label"].lower()] = {"pose3d": pose3d, "pose2d": pose2d,
            "rootWorldMatrix": world_matrix_yup(root.matrix_world),
            "rootPosition": to_yup(root.matrix_world.to_translation()),
            "propWorldMatrix": world_matrix_yup(sword.matrix_world), "jointLocation": "evaluated deform bone head"}
    return {"outputFrame": index, "blenderFrame": index + 1, "timeSeconds": index / state["timeline"]["fps"],
            "clock": clock, "jointOrder": list(PRIMARY_JOINTS), "worldCoordinates": "right-handed Y-up metres",
            "imageCoordinates": "normalized, origin top-left; pixel xy = normalized xy * width,height",
            "projectionVisibility": "canvas and clip only; no joint occlusion or identity certainty",
            "camera": _camera_metadata(scene, state["camera"], depsgraph, width, height), "actors": actors}


def export_conditioning(state, output: Path, frame_count: int) -> dict:
    """Render the already baked C3D_OUTPUT_TIMELINE into an immutable sidecar."""
    import bpy
    timeline = validate_timeline(state["timeline"])
    total = sum(shot["durationFrames"] for shot in timeline["shots"])
    if type(frame_count) is not int or not 0 <= frame_count <= min(MAX_FRAMES, total):
        raise ValueError("Conditioning frame count must be 0..1200 and within the output timeline")
    scene = bpy.context.scene
    if scene.frame_start != 1 or scene.frame_end != total or scene.camera != state["camera"]:
        raise ValueError("Conditioning requires the baked output scene and its active camera")
    if (scene.render.use_border or scene.render.use_multiview or
            getattr(scene.render, "use_motion_blur", False) or getattr(scene.cycles, "use_motion_blur", False)):
        raise ValueError("Conditioning requires full-frame single-view samples without motion blur")
    for obj in [state["camera"]] + [actor[key] for actor in state["actors"] for key in ("rig", "root", "sword")]:
        action = obj.animation_data.action if obj.animation_data else None
        if action is None or not action.name.startswith("C3D_OUTPUT_TIMELINE"):
            raise ValueError("Conditioning requires baked C3D_OUTPUT_TIMELINE actions")
    if len(state["actors"]) != 2 or {actor["label"].lower() for actor in state["actors"]} != {"black", "white"}:
        raise ValueError("Conditioning requires explicit black and white actor ownership")
    width = int(scene.render.resolution_x * scene.render.resolution_percentage / 100)
    height = int(scene.render.resolution_y * scene.render.resolution_percentage / 100)
    if any(not 1 <= value <= 4096 for value in (width, height)) or width * height > 4096 * 2160:
        raise ValueError("Conditioning render dimensions exceed budget")
    root = Path(output) / "conditioning"
    if root.exists() or root.is_symlink():
        raise ValueError("Conditioning generations are immutable; use a new output directory")
    root.mkdir(parents=True)
    original_frame, original_subframe = scene.frame_current, scene.frame_subframe
    indices = {obj: obj.pass_index for obj in scene.objects}
    render_scene = None
    complete = False
    try:
        for obj in scene.objects:
            obj.pass_index = 0
        for actor in state["actors"]:
            black = actor["label"].lower() == "black"
            actor["mesh"].pass_index = 1 if black else 2
            actor["sword"].pass_index = 3 if black else 4
        render_scene = scene.copy()
        render_scene.name = "C3D_CONDITIONING_RENDER"
        # PNG dithering perturbs binary object masks by a quantization step.
        # Disable it only on this private render copy; the authoring/beauty scene
        # retains the user's original output settings.
        render_scene.render.dither_intensity = 0
        if scene.node_tree is not None and render_scene.node_tree == scene.node_tree:
            raise RuntimeError("Blender scene copy must have an independent compositor")
        nodes = _compositor(render_scene)
        frames = []
        rendered_bytes = 0
        offset, shot_index = 0, 0
        for index in range(frame_count):
            while index >= offset + timeline["shots"][shot_index]["durationFrames"]:
                offset += timeline["shots"][shot_index]["durationFrames"]
                shot_index += 1
            shot = timeline["shots"][shot_index]
            local = index - offset
            sampled = sample_shot(shot, local, timeline["fps"])
            clock = {"shotId": shot["id"], "shotFrame": local, "phase": sampled["phase"],
                     "motionSeconds": sampled["motionSeconds"], "hitStop": sampled["hitStop"]}
            scene.frame_set(index + 1)
            bpy.context.view_layer.update()
            directory = root / frame_directory(index)
            directory.mkdir(parents=True)
            _json(directory / "pose.json", _pose(state, scene, index, clock, width, height))
            render_scene.frame_set(index + 1)
            for node in nodes.values():
                node.base_path = str(directory.resolve())
            bpy.ops.render.render(scene=render_scene.name)
            for role in ROLES[1:]:
                extension = ".exr" if role == "depth" else ".png"
                generated = list(directory.glob(role + "_*" + extension))
                if len(generated) != 1:
                    raise RuntimeError("Blender did not emit exactly one " + role + " pass")
                generated[0].rename(directory / FILENAMES[role])
            files = []
            for role in ROLES:
                path = directory / FILENAMES[role]
                size = path.stat().st_size
                rendered_bytes += size
                if not 1 <= size <= MAX_FILE or rendered_bytes > MAX_TOTAL - MAX_JSON:
                    raise ValueError("Rendered conditioning exceeds its byte budget")
                files.append({"role": role, "path": path.relative_to(root).as_posix(),
                              "bytes": size, "sha256": digest(path)})
            frames.append({"outputFrame": index, "blenderFrame": index + 1, "files": files})
        manifest = {"schema": SCHEMA, "execution": "cloud_blender", "nativeRendered": False,
            "originalImageReconstruction": False, "cinematicAcceptance": False,
            "blenderVersion": bpy.app.version_string, "frameCount": frame_count, "totalOutputFrames": total,
            "fps": timeline["fps"], "width": width, "height": height, "jointOrder": list(PRIMARY_JOINTS),
            "timelineSha256": hashlib.sha256(json.dumps(timeline, sort_keys=True, separators=(",", ":"),
                                                        ensure_ascii=False, allow_nan=False).encode()).hexdigest(),
            "passes": {"beauty": {"format": "PNG", "viewTransform": scene.view_settings.view_transform},
                "depth": {"format": "OpenEXR float32", "units": "metres", "semantics": "Cycles Z pass: camera ray distance to first visible surface; background uses renderer no-hit sentinel; not camera-axis Z"},
                "normals": {"format": "PNG RGB8 raw", "space": "world right-handed Y-up",
                    "decode": "normal = RGB/255 * 2 - 1; no-surface normal maps to [0.5,0.5,0.5]"},
                "actors": {"format": "PNG RGB8 raw", "palette": {"black": [255, 0, 0], "white": [0, 255, 0], "other": [0, 0, 0]},
                    "semantics": "visible surfaces of weighted actor meshes; object-index pass, no antialiasing; props excluded"},
                "props": {"format": "PNG RGB8 raw", "palette": {"blackKatana": [255, 0, 0], "whiteKatana": [0, 255, 0], "other": [0, 0, 0]},
                    "semantics": "visible katana surfaces; object-index pass, no antialiasing"}}, "frames": frames}
        _json(root / "manifest.json", manifest)
        result = verify_conditioning(root)
        complete = True
        return result
    finally:
        for obj, pass_index in indices.items():
            obj.pass_index = pass_index
        if render_scene is not None:
            bpy.data.scenes.remove(render_scene)
        scene.frame_set(original_frame, subframe=original_subframe)
        if not complete:
            shutil.rmtree(root)
