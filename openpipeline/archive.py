"""Inert, bounded and hash-bound request/result ZIP contracts."""
from __future__ import annotations
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import zipfile

REQUEST_SCHEMA = "videostudio.openpipeline.request.v1"
RESULT_SCHEMA = "videostudio.openpipeline.result.v1"
MAX_ARCHIVE = 256 * 1024 * 1024
MAX_EXPANDED = 512 * 1024 * 1024
MAX_JSON = 512 * 1024
MAX_FILES = 256
ID = re.compile(r"[A-Za-z0-9_-]{1,96}\Z")
SHA = re.compile(r"[a-f0-9]{64}\Z")


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def fields(value, required, optional=()):
    if not isinstance(value, dict) or set(required) - value.keys() or value.keys() - set(required) - set(optional):
        raise ValueError("Missing or unknown fields")


def integer(value, minimum, maximum, label="integer"):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("Invalid " + label)
    return value


def identifier(value):
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise ValueError("Invalid identity")
    return value


def checksum(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError("Invalid lowercase SHA-256")
    return value


def relative(value):
    if (not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_./-]{1,180}", value) or
            value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/"))):
        raise ValueError("Invalid owned artifact path")
    return value


def owned(root, name):
    root = Path(root)
    if root.is_symlink():
        raise ValueError("Artifact root is a symlink")
    path = root
    for part in relative(name).split("/"):
        path = path / part
        if path.is_symlink():
            raise ValueError("Artifact symlinks are unavailable")
    if not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Missing owned artifact")
    return path


def json_bytes(raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_JSON:
        raise ValueError("JSON exceeds metadata budget")
    def pairs(entries):
        value = {}
        for key, child in entries:
            if key in value:
                raise ValueError("Duplicate JSON key")
            value[key] = child
        return value
    try:
        result = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs,
            parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ValueError("Malformed JSON") from error
    stack, count = [(result, 0)], 0
    while stack:
        value, depth = stack.pop()
        count += 1
        if depth > 24 or count > 65536:
            raise ValueError("JSON exceeds structural budget")
        if isinstance(value, dict):
            stack.extend((child, depth + 1) for child in value.values())
        elif isinstance(value, list):
            stack.extend((child, depth + 1) for child in value)
        elif isinstance(value, float) and not math.isfinite(value):
            raise ValueError("Nonfinite JSON")
    if not isinstance(result, dict):
        raise ValueError("JSON object required")
    return result


def read_json(path):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > MAX_JSON:
        raise ValueError("Invalid metadata file")
    return json_bytes(path.read_bytes())


def encode(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    json_bytes(raw)
    return raw


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".metadata-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(encode(value)); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def fsync_directory(path):
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def validate_request(value):
    fields(value, ("schema", "requestId", "projectId", "sourceProjectUpdatedAt", "backend", "controls", "sources"))
    if value["schema"] != REQUEST_SCHEMA:
        raise ValueError("Unknown request schema")
    identifier(value["requestId"]); identifier(value["projectId"])
    integer(value["sourceProjectUpdatedAt"], 0, (1 << 63) - 1)
    backend, controls, sources = value["backend"], value["controls"], value["sources"]
    if backend not in ("blender", "cogvideox", "frame_sequence", "interpolation"):
        raise ValueError("Unsupported open-source backend")
    if not isinstance(sources, list) or len(sources) > 64:
        raise ValueError("At most 64 ordered sources are admitted")
    paths, ids, total = {"request.json"}, set(), 0
    for source in sources:
        fields(source, ("assetId", "path", "bytes", "sha256", "mime"))
        identifier(source["assetId"]); relative(source["path"]); checksum(source["sha256"])
        if source["assetId"] in ids or source["path"] in paths:
            raise ValueError("Duplicate source identity/path")
        paths.add(source["path"]); ids.add(source["assetId"])
        total += integer(source["bytes"], 1, MAX_ARCHIVE)
        if total > MAX_EXPANDED - MAX_JSON:
            raise ValueError("Sources exceed expansion budget")
        if not isinstance(source["mime"], str) or not re.fullmatch(r"[a-z0-9.+_-]+/[a-zA-Z0-9.+_-]{1,100}", source["mime"]):
            raise ValueError("Invalid source MIME")
        if Path(source["path"]).suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp", ".mp4", ".json", ".glb", ".wav"):
            raise ValueError("Executable or unsupported source artifact")
    if backend == "cogvideox":
        fields(controls, ("prompt", "width", "height", "numFrames", "fps", "seed", "numInferenceSteps", "guidanceScale"), ("negativePrompt", "mode", "strength"))
        for key in ("prompt", "negativePrompt"):
            if key in controls and (not isinstance(controls[key], str) or len(controls[key]) > 10000 or "\0" in controls[key] or (key == "prompt" and not controls[key].strip())):
                raise ValueError("Invalid generation prompt")
        for key in ("width", "height"):
            integer(controls[key], 16, 2048, key)
            if controls[key] % 16:
                raise ValueError("CogVideoX dimensions must be divisible by sixteen")
        integer(controls["numFrames"], 1, 241)
        if (controls["numFrames"] - 1) % 8:
            raise ValueError("CogVideoX-2B frame count must be 8k+1")
        integer(controls["fps"], 1, 60); integer(controls["seed"], 0, 2147483647)
        integer(controls["numInferenceSteps"], 1, 100)
        guidance = controls["guidanceScale"]
        if type(guidance) not in (int, float) or not math.isfinite(guidance) or not 0 <= guidance <= 30:
            raise ValueError("Invalid guidance scale")
        mode = controls.get("mode", "text_to_video")
        if mode not in ("text_to_video", "video_to_video"):
            raise ValueError("Unsupported CogVideoX mode")
        if mode == "text_to_video":
            if "strength" in controls:
                raise ValueError("Strength applies only to video_to_video")
            if sources:
                raise ValueError("REFERENCE_CONDITIONING_UNAVAILABLE")
        else:
            strength = controls.get("strength")
            if type(strength) not in (int, float) or not math.isfinite(strength) or not .05 <= strength <= 1:
                raise ValueError("video_to_video requires strength from 0.05 to 1")
            if int(strength * controls["numInferenceSteps"]) < 1:
                raise ValueError("V2V_ZERO_DENOISING_STEPS")
            if len(sources) != 1 or sources[0]["mime"] != "video/mp4" or not sources[0]["path"].lower().endswith(".mp4"):
                raise ValueError("VIDEO_SOURCE_REQUIRED")
    elif backend == "blender":
        from cinematic3d.timeline import validate_timeline
        fields(controls, ("timeline",), ("conditioningFrames", "renderFrames"))
        timeline = validate_timeline(controls["timeline"])
        total_frames = sum(shot["durationFrames"] for shot in timeline["shots"])
        integer(controls.get("renderFrames", 0), 0, min(total_frames, 12000))
        selected = controls.get("conditioningFrames", [])
        if not isinstance(selected, list) or len(selected) > min(total_frames, 1200) or selected != list(range(len(selected))):
            raise ValueError("Conditioning frames must be the ordered prefix [0..N-1], at most 1200")
        anchors = {(anchor["assetId"], anchor["sha256"]) for shot in timeline["shots"] for anchor in shot.get("sourceAnchors", [])}
        if anchors - {(source["assetId"], source["sha256"]) for source in sources}:
            raise ValueError("Blender timeline source anchors lack their exact source bytes")
    elif backend == "frame_sequence":
        fields(controls, ("fps", "width", "height"))
        integer(controls["fps"], 1, 60)
        for key in ("width", "height"):
            integer(controls[key], 16, 4096)
            if controls[key] % 2:
                raise ValueError("Video dimensions must be even")
    else:
        fields(controls, ("inputFps", "outputFps"), ("cuts", "hitStopRanges"))
        integer(controls["inputFps"], 1, 60); integer(controls["outputFps"], controls["inputFps"] + 1, 120)
        cuts, holds = controls.get("cuts", []), controls.get("hitStopRanges", [])
        if not isinstance(cuts, list) or len(cuts) > 80 or any(type(item) is not int or not 0 < item < len(sources) for item in cuts) or cuts != sorted(set(cuts)):
            raise ValueError("Cuts must be ordered interior source frame boundaries")
        if not isinstance(holds, list) or len(holds) > 80:
            raise ValueError("Invalid hold ranges")
        previous = -1
        for hold in holds:
            if not isinstance(hold, list) or len(hold) != 2 or any(type(item) is not int for item in hold) or not 0 <= hold[0] < hold[1] <= len(sources) or hold[0] < previous:
                raise ValueError("Hold ranges must be ordered, nonoverlapping, end-exclusive")
            previous = hold[1]
    if backend in ("frame_sequence", "interpolation") and (len(sources) < (2 if backend == "interpolation" else 1) or any(source["mime"] != "image/png" or not source["path"].endswith(".png") for source in sources)):
        raise ValueError("This backend requires an ordered PNG source sequence")
    return value


def _entries(archive):
    entries, total = {}, 0
    for info in archive.infolist():
        relative(info.filename)
        kind = stat.S_IFMT(info.external_attr >> 16)
        if info.is_dir() or kind not in (0, stat.S_IFREG) or info.flag_bits & 1 or info.filename in entries:
            raise ValueError("ZIP directories, links, duplicates and encryption are unavailable")
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise ValueError("Unsupported ZIP compression")
        total += info.file_size
        if len(entries) >= MAX_FILES or info.file_size < 1 or total > MAX_EXPANDED:
            raise ValueError("ZIP expansion exceeds its budget")
        entries[info.filename] = info
    return entries


def unpack_job(archive_path, destination):
    """Extract only an exact declared file set; remove partial extraction on error."""
    archive_path, destination = Path(archive_path), Path(destination)
    if archive_path.is_symlink() or not 1 <= archive_path.stat().st_size <= MAX_ARCHIVE or destination.exists():
        raise ValueError("Invalid archive or existing extraction destination")
    destination.mkdir(parents=True)
    try:
        with zipfile.ZipFile(archive_path) as archive:
            entries = _entries(archive)
            if "request.json" not in entries or entries["request.json"].file_size > MAX_JSON:
                raise ValueError("Missing bounded request.json")
            raw = archive.read(entries["request.json"])
            request = validate_request(json_bytes(raw))
            expected = {"request.json", *(source["path"] for source in request["sources"])}
            if set(entries) != expected:
                raise ValueError("Archive contains undeclared or missing sources")
            (destination / "request.json").write_bytes(raw)
            for source in request["sources"]:
                info = entries[source["path"]]
                if info.file_size != source["bytes"]:
                    raise ValueError("Source declared size differs")
                target = destination / source["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                value, count = hashlib.sha256(), 0
                with archive.open(info) as stream, target.open("xb") as output:
                    while block := stream.read(128 * 1024):
                        count += len(block)
                        if count > source["bytes"]:
                            raise ValueError("Source grew beyond its bound")
                        value.update(block); output.write(block)
                    output.flush(); os.fsync(output.fileno())
                if count != source["bytes"] or value.hexdigest() != source["sha256"]:
                    raise ValueError("Source SHA-256 differs")
            fsync_directory(destination)
            return request, hashlib.sha256(raw).hexdigest()
    except zipfile.BadZipFile as error:
        shutil.rmtree(destination, ignore_errors=True)
        raise ValueError("INVALID_ZIP_ARCHIVE") from error
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise


def package(root, names, target):
    """Hash exact streamed bytes and publish create-only, closing copy races."""
    root, target = Path(root), Path(target)
    if target.exists() or target.is_symlink() or len(names) > MAX_FILES or len(set(names)) != len(names):
        raise ValueError("Immutable archive exists or invalid file set")
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".archive-", dir=target.parent)
    os.close(fd); temporary = Path(name)
    try:
        total = 0
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name in names:
                path = owned(root, name)
                before, size = digest(path), path.stat().st_size
                total += size
                if not 1 <= size <= MAX_EXPANDED or total > MAX_EXPANDED:
                    raise ValueError("Archive expansion exceeds budget")
                info = zipfile.ZipInfo(relative(name), (1980, 1, 1, 0, 0, 0))
                info.compress_type, info.external_attr = zipfile.ZIP_DEFLATED, 0o100600 << 16
                value, count = hashlib.sha256(), 0
                with path.open("rb") as stream, archive.open(info, "w") as output:
                    while block := stream.read(128 * 1024):
                        count += len(block)
                        if count > size:
                            raise ValueError("Artifact changed during packaging")
                        output.write(block); value.update(block)
                if count != size or value.hexdigest() != before:
                    raise ValueError("Artifact changed during packaging")
        if temporary.stat().st_size > MAX_ARCHIVE:
            raise ValueError("Compressed archive exceeds budget")
        with temporary.open("rb") as stream:
            os.fsync(stream.fileno())
        os.link(temporary, target); fsync_directory(target.parent)
        return {"path": str(target.resolve()), "bytes": target.stat().st_size, "sha256": digest(target)}
    finally:
        temporary.unlink(missing_ok=True)


def pack_job(request_path, sources, output):
    request_path = Path(request_path)
    if request_path.is_symlink() or not request_path.is_file() or request_path.stat().st_size > MAX_JSON:
        raise ValueError("Invalid bounded request metadata")
    raw = request_path.read_bytes()
    request = validate_request(json_bytes(raw))
    with tempfile.TemporaryDirectory(prefix="openpipeline-pack-") as name:
        root = Path(name)
        (root / "request.json").write_bytes(raw)
        for record in request["sources"]:
            source = owned(sources, record["path"])
            if source.stat().st_size != record["bytes"] or digest(source) != record["sha256"]:
                raise ValueError("Source differs from its request binding")
            target = root / record["path"]; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            if digest(target) != record["sha256"]:
                raise ValueError("Source changed while copying")
        return dict(package(root, ["request.json"] + [source["path"] for source in request["sources"]], output),
                    requestId=request["requestId"], requestSha256=hashlib.sha256(raw).hexdigest())
