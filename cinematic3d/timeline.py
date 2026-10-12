"""Strict portable cinematic timelines and their shared rational frame clock.

Coordinates are right-handed Y-up metres. Output indices are always zero-based;
shot endFrame is exclusive. No input data is mutated by validation or compilation.
"""
import json
import math
import re

from .motion import normalize_quaternion

SCHEMA = "videostudio.cinematic3d.timeline.v1"
ACTIONS = frozenset({"STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH",
                     "KNOCKBACK", "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH"})
IMPACT_ACTIONS = frozenset({"CLASH", "BLADE_LOCK", "MACRO_CLASH"})
MAX_METADATA_BYTES = 512 * 1024
MAX_JSON_DEPTH = 16
MAX_JSON_NODES = 65536
MAX_CAMERA_COORDINATE = 10000
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}\Z", re.ASCII)
_SHA = re.compile(r"[a-f0-9]{64}\Z", re.ASCII)


def _bounded_json(value):
    """Reject cycles/depth/non-JSON data before serialization can recurse."""
    nodes = 0
    active = set()

    def visit(node, depth):
        nonlocal nodes
        nodes += 1
        if depth > MAX_JSON_DEPTH or nodes > MAX_JSON_NODES:
            raise ValueError("Timeline JSON exceeds depth or node limits")
        if isinstance(node, (dict, list)):
            identity = id(node)
            if identity in active:
                raise ValueError("Timeline JSON must not contain cycles")
            active.add(identity)
            if isinstance(node, dict):
                for key, child in node.items():
                    if not isinstance(key, str):
                        raise ValueError("JSON object keys must be strings")
                    visit(child, depth + 1)
            else:
                for child in node:
                    visit(child, depth + 1)
            active.remove(identity)
        elif node is None or isinstance(node, (str, bool, int)):
            return
        elif isinstance(node, float) and math.isfinite(node):
            return
        else:
            raise ValueError("Timeline must contain finite JSON values")

    visit(value, 0)
    try:
        encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, OverflowError, UnicodeError) as error:
        raise ValueError("Timeline is not valid UTF-8 JSON") from error
    if len(encoded) > MAX_METADATA_BYTES:
        raise ValueError("Timeline metadata exceeds 512 KiB")


def _fields(value, required, optional=(), label="object"):
    if not isinstance(value, dict):
        raise ValueError(label + " must be an object")
    missing = set(required) - value.keys()
    unknown = value.keys() - set(required) - set(optional)
    if missing or unknown:
        raise ValueError(label + " has missing or unknown fields")


def _integer(value, minimum, maximum, label):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(label + " must be a bounded integer")
    return value


def _number(value, minimum, maximum, label):
    if type(value) not in (int, float):
        raise ValueError(label + " must be a finite number")
    try:
        valid = math.isfinite(value) and minimum <= value <= maximum
    except OverflowError:
        valid = False
    if not valid:
        raise ValueError(label + " is outside its finite range")
    return value


def _vector(value, label):
    if not isinstance(value, list) or len(value) != 3:
        raise ValueError(label + " must contain three coordinates")
    return [_number(x, -math.inf, math.inf, label) for x in value]


def _identifier(value, label):
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise ValueError(label + " must be a safe identifier of at most 96 characters")
    return value


def _collection(value, minimum, maximum, label):
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise ValueError(label + " exceeds collection bounds")
    return value


def _pose_keys(value):
    keys = []
    previous = -1
    for key in _collection(value, 1, 128, "poseKeyframes"):
        _fields(key, ("phase", "actors"), label="pose key")
        phase = _number(key["phase"], 0, 1, "pose phase")
        if phase <= previous:
            raise ValueError("Pose key phases must be strictly increasing")
        previous = phase
        _fields(key["actors"], ("black", "white"), label="pose actors")
        actors = {}
        for actor_name in ("black", "white"):
            actor = key["actors"][actor_name]
            _fields(actor, ("root", "rotations"), label="actor pose")
            rotations = _collection(actor["rotations"], 16, 16, "joint rotations")
            actors[actor_name] = {"root": _vector(actor["root"], "actor root"),
                                  "rotations": [_authored_quaternion(q) for q in rotations]}
        keys.append({"phase": phase, "actors": actors})
    return keys


def _authored_quaternion(value):
    normalized = normalize_quaternion(value)
    maximum = max(abs(coordinate) for coordinate in value)
    norm = maximum * math.hypot(*(coordinate / maximum for coordinate in value))
    # Unrelated camera/FPS edits must preserve already accepted baked pose values.
    return list(value) if abs(norm - 1) <= 1e-12 else normalized


def _camera_vector(value, label):
    result = _vector(value, label)
    if any(abs(coordinate) > MAX_CAMERA_COORDINATE for coordinate in result):
        raise ValueError(label + " exceeds the 10000 metre/component range")
    return result


def _camera_up(mode):
    return [0.0, 0.0, -1.0] if mode == "top_down" else [0.0, 1.0, 0.0]


def _camera_basis(position, target, up):
    position = _camera_vector(position, "camera position")
    target = _camera_vector(target, "camera target")
    up = _camera_vector(up, "camera up")
    direction = [aim - eye for eye, aim in zip(position, target)]
    length, up_length = math.hypot(*direction), math.hypot(*up)
    separation = 1e-6 * max(1, *(abs(coordinate) for coordinate in position + target))
    if length <= separation or up_length <= 1e-9:
        raise ValueError("Camera look direction/up must be nondegenerate at float32 precision")
    direction = [coordinate / length for coordinate in direction]
    unit_up = [coordinate / up_length for coordinate in up]
    cross = [direction[1] * unit_up[2] - direction[2] * unit_up[1],
             direction[2] * unit_up[0] - direction[0] * unit_up[2],
             direction[0] * unit_up[1] - direction[1] * unit_up[0]]
    if math.hypot(*cross) <= 1e-6:
        raise ValueError("Camera up must not be parallel to the look direction")


def _camera_keys(value, mode):
    keys = []
    previous = -1
    for key in _collection(value, 1, 128, "camera keyframes"):
        _fields(key, ("phase", "position", "target"), ("up",), "camera key")
        phase = _number(key["phase"], 0, 1, "camera key phase")
        if phase <= previous:
            raise ValueError("Camera key phases must be strictly increasing")
        previous = phase
        accepted = {"phase": phase, "position": _camera_vector(key["position"], "camera position"),
                    "target": _camera_vector(key["target"], "camera target")}
        if "up" in key:
            accepted["up"] = _camera_vector(key["up"], "camera up")
        _camera_basis(accepted["position"], accepted["target"], accepted.get("up", _camera_up(mode)))
        keys.append(accepted)
    return keys


def _sample_camera_keys(keys, phase, mode):
    left = right = keys[0]
    if phase >= keys[-1]["phase"]:
        left = right = keys[-1]
    elif phase > keys[0]["phase"]:
        for right in keys[1:]:
            if phase <= right["phase"]:
                break
            left = right
    interval = right["phase"] - left["phase"]
    alpha = (phase - left["phase"]) / interval if interval else 0
    result = {}
    for field in ("position", "target", "up"):
        a, b = left.get(field, _camera_up(mode)), right.get(field, _camera_up(mode))
        result[field] = [(1 - alpha) * x + alpha * y for x, y in zip(a, b)]
    return result


def _foot_contacts(value):
    contacts = []
    previous = {}
    for contact in _collection(value, 0, 64, "footContacts"):
        _fields(contact, ("actor", "foot", "startPhase", "endPhase", "anchor"), label="foot contact")
        if contact["actor"] not in ("black", "white") or contact["foot"] not in ("leading", "trailing"):
            raise ValueError("Foot contact actor or foot is invalid")
        start = _number(contact["startPhase"], 0, 1, "contact startPhase")
        end = _number(contact["endPhase"], 0, 1, "contact endPhase")
        identity = (contact["actor"], contact["foot"])
        if end <= start or start < previous.get(identity, -1):
            raise ValueError("Foot contact intervals must be ordered and nonoverlapping")
        previous[identity] = end
        contacts.append({"actor": contact["actor"], "foot": contact["foot"], "startPhase": start,
                         "endPhase": end, "anchor": _vector(contact["anchor"], "foot anchor")})
    return contacts


def _shot(value, compiled=False):
    optional = {"sourceAnchors", "poseKeyframes", "footContacts"}
    if compiled:
        optional.update({"startFrame", "endFrame"})
    _fields(value, ("id", "action", "durationFrames", "hitStopFrames", "camera", "vfx"), optional, "shot")
    identity = _identifier(value["id"], "shot id")
    action = value["action"]
    if not isinstance(action, str) or action not in ACTIONS:
        raise ValueError("Unknown cinematic action")
    duration = _integer(value["durationFrames"], 8, 600, "durationFrames")
    hold = _integer(value["hitStopFrames"], 0, 5, "hitStopFrames")
    if hold == 1 or (hold and action not in IMPACT_ACTIONS):
        raise ValueError("Impact actions alone permit a 2–5 frame hit-stop")
    camera = value["camera"]
    _fields(camera, ("mode", "fovDegrees", "shakeIntensity"), ("keyframes",), label="camera")
    if camera["mode"] not in ("wide", "orbit", "macro", "top_down"):
        raise ValueError("Unknown camera mode")
    vfx = value["vfx"]
    _fields(vfx, ("sparkBurst", "slashTrail", "rubble"), label="vfx")
    if any(type(vfx[field]) is not bool for field in vfx):
        raise ValueError("VFX flags must be booleans")
    result = {"id": identity, "action": action, "durationFrames": duration, "hitStopFrames": hold,
              "camera": {"mode": camera["mode"], "fovDegrees": _number(camera["fovDegrees"], 35, 100, "fovDegrees"),
                         "shakeIntensity": _number(camera["shakeIntensity"], 0, 1, "shakeIntensity")},
              "vfx": dict(vfx)}
    if "keyframes" in camera:
        result["camera"]["keyframes"] = _camera_keys(camera["keyframes"], camera["mode"])
    if "sourceAnchors" in value:
        anchors = []
        for anchor in _collection(value["sourceAnchors"], 0, 160, "sourceAnchors"):
            _fields(anchor, ("assetId", "sha256"), label="source anchor")
            digest = anchor["sha256"]
            if not isinstance(digest, str) or not _SHA.fullmatch(digest):
                raise ValueError("Source sha256 must be lowercase hexadecimal")
            anchors.append({"assetId": _identifier(anchor["assetId"], "source asset id"), "sha256": digest})
        result["sourceAnchors"] = anchors
    if "poseKeyframes" in value:
        result["poseKeyframes"] = _pose_keys(value["poseKeyframes"])
    if "footContacts" in value:
        result["footContacts"] = _foot_contacts(value["footContacts"])
    return result


def validate_timeline(value):
    """Return independent normalized JSON or raise ValueError for any bad field."""
    _bounded_json(value)
    _fields(value, ("schema", "name", "fps", "motionProvider", "shots"), label="timeline")
    if value["schema"] != SCHEMA:
        raise ValueError("Unsupported timeline schema")
    name = value["name"]
    if not isinstance(name, str) or not name.strip() or len(name) > 256:
        raise ValueError("Timeline name must contain 1–256 characters")
    fps = _integer(value["fps"], 24, 60, "fps")
    if fps not in (24, 30, 60):
        raise ValueError("fps must be 24, 30 or 60")
    provider = value["motionProvider"]
    if provider not in ("procedural_mannequin", "authored_3d"):
        raise ValueError("Motion provider must declare procedural_mannequin or authored_3d")
    shots = [_shot(shot) for shot in _collection(value["shots"], 1, 80, "shots")]
    if len({shot["id"] for shot in shots}) != len(shots):
        raise ValueError("Shot identifiers must be unique")
    if sum(shot["durationFrames"] for shot in shots) > 12000:
        raise ValueError("Timeline exceeds 12000 output frames")
    return {"schema": SCHEMA, "name": name.strip(), "fps": fps, "motionProvider": provider, "shots": shots}


def compile_timeline(value):
    """Attach exclusive offsets without blending or reordering adjacent shots."""
    compiled = validate_timeline(value)
    offset = 0
    for shot in compiled["shots"]:
        shot["startFrame"] = offset
        offset += shot["durationFrames"]
        shot["endFrame"] = offset
    compiled["totalFrames"] = offset
    compiled["durationSeconds"] = offset / compiled["fps"]
    return compiled


def sample_shot(value, frame, fps):
    """Sample the actor clock and independently advancing camera at output n/fps.

    An H-frame hold consists of exactly H identical phase=.5 output samples. The
    first post-hold sample advances again; the terminal sample remains below1.
    """
    shot = _shot(value, compiled=True)
    _integer(fps, 24, 60, "fps")
    if fps not in (24, 30, 60):
        raise ValueError("fps must be 24, 30 or 60")
    count, hold = shot["durationFrames"], shot["hitStopFrames"]
    _integer(frame, 0, count - 1, "frame")
    impact = (count - hold) // 2 if hold else count // 2
    frozen = bool(hold and impact <= frame < impact + hold)
    if not hold:
        phase = frame / count
    elif frame < impact:
        phase = 0.5 * frame / impact
    elif frozen:
        phase = 0.5
    else:
        boundary = impact + hold - 1
        phase = 0.5 + 0.5 * (frame - boundary) / (count - boundary)

    mode = shot["camera"]["mode"]
    keys = shot["camera"].get("keyframes")
    up = _camera_up(mode)
    target = [0.0, 1.1, 0.0]
    if keys is not None:
        authored = _sample_camera_keys(keys, frame / count, mode)
        position, target, up = authored["position"], authored["target"], authored["up"]
    elif mode == "wide":
        position = [0.0, 1.65, 5.5]
    elif mode == "orbit":
        angle = -0.35 + 0.7 * frame / count
        position = [5.2 * math.sin(angle), 1.9, 5.2 * math.cos(angle)]
    elif mode == "macro":
        position = [0.15, 1.45, 2.2]
    else:
        position, target = [0.0, 6.0, 0.001], [0.0, 0.8, 0.0]
    if shot["action"] in IMPACT_ACTIONS and frame >= impact:
        elapsed = (frame - impact) / fps
        amplitude = 0.04 * shot["camera"]["shakeIntensity"] * math.exp(-7 * elapsed)
        position[0] += amplitude * math.sin(53 * elapsed + 0.7)
        position[1] += 0.6 * amplitude * math.sin(71 * elapsed + 1.3)
        position[2] += 0.4 * amplitude * math.cos(47 * elapsed)
    _camera_basis(position, target, up)
    camera = {"position": position, "target": target, "fovDegrees": shot["camera"]["fovDegrees"]}
    if keys is not None or mode == "top_down":
        camera["up"] = up
    return {"phase": phase, "motionSeconds": phase * (count - hold) / fps,
            "hitStop": frozen, "camera": camera, "impactFrame": impact}
