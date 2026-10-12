"""Pure quaternion, authored mannequin motion and world-anchor two-bone IK.

The procedural library is explicit estimated mannequin choreography. It makes no
source-image reconstruction claim and is independent of Blender or neural models.
"""
import math

PRIMARY_JOINTS = ("pelvis", "chest", "neck", "head", "swordShoulder", "swordElbow", "swordHand",
                  "freeShoulder", "freeElbow", "freeHand", "leadingHip", "leadingKnee", "leadingFoot",
                  "trailingHip", "trailingKnee", "trailingFoot")


def _finite(value, label):
    if type(value) not in (int, float):
        raise ValueError(label + " must be a finite number")
    try:
        finite = math.isfinite(value)
    except OverflowError:
        finite = False
    if not finite:
        raise ValueError(label + " must be a finite number")
    return value


def _vector(value, dimensions, label):
    if not isinstance(value, (list, tuple)) or len(value) != dimensions:
        raise ValueError(label + " has the wrong coordinate count")
    return [_finite(coordinate, label) for coordinate in value]


def _phase(value):
    _finite(value, "phase")
    if not 0 <= value <= 1:
        raise ValueError("phase must be between 0 and 1")
    return value


def normalize_quaternion(value):
    """Normalize a nonzero wxyz rotation without discarding its authored sign."""
    quaternion = _vector(value, 4, "quaternion")
    # Scale first so large/small finite inputs cannot overflow/underflow the norm.
    scale = max(abs(x) for x in quaternion)
    if scale == 0:
        raise ValueError("Quaternion must not be zero")
    scaled = [x / scale for x in quaternion]
    length = math.hypot(*scaled)
    return [x / length for x in scaled]


def slerp(q1, q2, t):
    """Normalized shortest-path SLERP; a >180° turn requires intermediate keys."""
    _phase(t)
    left, right = normalize_quaternion(q1), normalize_quaternion(q2)
    dot = sum(a * b for a, b in zip(left, right))
    if dot < 0:
        right = [-x for x in right]
        dot = -dot
    dot = min(1.0, max(-1.0, dot))
    if dot > 0.9995:
        return normalize_quaternion([(1 - t) * a + t * b for a, b in zip(left, right)])
    angle = math.acos(dot)
    denominator = math.sin(angle)
    left_weight = math.sin((1 - t) * angle) / denominator
    right_weight = math.sin(t * angle) / denominator
    return normalize_quaternion([left_weight * a + right_weight * b for a, b in zip(left, right)])


def to_blender_vector(value):
    """Convert right-handed Y-up to Blender Z-up: (x,y,z)->(x,-z,y)."""
    x, y, z = _vector(value, 3, "vector")
    return [x, -z, y]


def to_blender_quaternion(value):
    """Conjugate the rotation basis; preserve wxyz quaternion serialization."""
    w, x, y, z = normalize_quaternion(value)
    return [w, x, -z, y]


def to_gltf_quaternion(value):
    """Serialize the shared Y-up rotation as glTF xyzw (no axis conversion)."""
    w, x, y, z = normalize_quaternion(value)
    return [x, y, z, w]


def _multiply(left, right):
    w, x, y, z = left
    a, b, c, d = right
    return normalize_quaternion([w * a - x * b - y * c - z * d,
                                 w * b + x * a + y * d - z * c,
                                 w * c - x * d + y * a + z * b,
                                 w * d + x * c - y * b + z * a])


def _axis(axis, radians):
    result = [math.cos(radians / 2), 0.0, 0.0, 0.0]
    result[axis + 1] = math.sin(radians / 2)
    return result


def procedural_pose(action, phase):
    """Return both mannequin roots and 16 local wxyz rotations for an action."""
    _phase(phase)
    actions = {"STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH", "KNOCKBACK",
               "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH"}
    if not isinstance(action, str) or action not in actions:
        raise ValueError("Unknown procedural mannequin action")
    result = {}
    wave = math.sin(2 * math.pi * phase)
    for name, side in (("black", -1), ("white", 1)):
        root = [side * 1.15, 0.0, 0.0]
        rotations = [[1.0, 0.0, 0.0, 0.0] for _ in PRIMARY_JOINTS]
        rotations[0] = _axis(1, side * math.pi / 2)
        rotations[1] = _axis(0, math.radians(3) * wave)
        rotations[4] = _axis(0, math.radians(-35))
        rotations[5] = _axis(0, math.radians(-70))
        rotations[7] = _axis(0, math.radians(-15))
        rotations[8] = _axis(0, math.radians(-25))
        if action == "CHARGE":
            root[0] = side * (2.2 - 1.05 * phase)
            rotations[1] = _axis(0, math.radians(14))
            rotations[10] = _axis(0, math.radians(30) * wave)
            rotations[13] = _axis(0, -math.radians(30) * wave)
            rotations[11] = _axis(0, math.radians(35) * max(0, -wave))
            rotations[14] = _axis(0, math.radians(35) * max(0, wave))
        elif action in ("CLASH", "MACRO_CLASH", "BLADE_LOCK"):
            swing = min(1.0, phase * 2) if action != "BLADE_LOCK" else 1 + 0.06 * wave
            rotations[4] = _axis(0, math.radians(-35 + 75 * swing))
            rotations[5] = _axis(0, math.radians(-70 + 30 * swing))
            rotations[7] = _axis(0, math.radians(15 + 20 * swing))
        elif action == "FOLLOW_THROUGH":
            rotations[4] = _axis(0, math.radians(40 + 85 * phase))
            rotations[5] = _axis(0, math.radians(-40 + 25 * phase))
            rotations[1] = _axis(1, side * math.radians(35) * phase)
        elif action == "KNOCKBACK":
            root[0] = side * (1.15 + 0.8 * phase)
            root[1] = 0.35 * math.sin(math.pi * phase)
            rotations[1] = _axis(0, math.radians(-25) * math.sin(math.pi * phase))
            rotations[4] = _axis(0, math.radians(-60))
        elif action == "AERIAL_FLIP":
            root[1] = 1.25 * math.sin(math.pi * phase)
            rotations[0] = _multiply(rotations[0], _axis(0, 2 * math.pi * phase))
            rotations[10] = _axis(0, math.radians(-40) * math.sin(math.pi * phase))
            rotations[11] = _axis(0, math.radians(90) * math.sin(math.pi * phase))
            rotations[13] = list(rotations[10])
            rotations[14] = list(rotations[11])
        elif action == "TUMBLE":
            root[0] = side * (1.15 + 0.6 * phase)
            root[1] = 0.3 * math.sin(math.pi * phase)
            rotations[0] = _multiply(rotations[0], _axis(2, side * 2 * math.pi * phase))
        elif action == "LANDING":
            root[1] = 0.8 * (1 - phase) ** 2
            crouch = math.sin(math.pi * phase)
            rotations[10] = _axis(0, -math.radians(30) * crouch)
            rotations[13] = list(rotations[10])
            rotations[11] = _axis(0, math.radians(60) * crouch)
            rotations[14] = list(rotations[11])
        elif action == "RECOVERY":
            rotations[1] = _axis(0, math.radians(15) * (1 - phase))
            rotations[4] = _axis(0, math.radians(40 - 75 * phase))
        result[name] = {"root": root, "rotations": rotations}
    return result


def sample_pose(shot, phase):
    """Interpolate authored poses; otherwise explicitly use the mannequin library."""
    _phase(phase)
    keys = shot.get("poseKeyframes")
    if keys is None:
        return procedural_pose(shot["action"], phase)
    if not keys:
        raise ValueError("Authored pose keys must not be empty")
    for index, key in enumerate(keys):
        _phase(key["phase"])
        if index and key["phase"] <= keys[index - 1]["phase"]:
            raise ValueError("Pose key phases must be strictly increasing")
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
    for name in ("black", "white"):
        a, b = left["actors"][name], right["actors"][name]
        a_root, b_root = _vector(a["root"], 3, "root"), _vector(b["root"], 3, "root")
        if len(a["rotations"]) != 16 or len(b["rotations"]) != 16:
            raise ValueError("Actor poses must contain all 16 rotations")
        result[name] = {"root": [(1 - alpha) * x + alpha * y for x, y in zip(a_root, b_root)],
                        "rotations": [slerp(x, y, alpha) for x, y in zip(a["rotations"], b["rotations"])]}
    return result


def sample_foot_contacts(shot, phase):
    """Return immutable world anchors for active half-open contact intervals."""
    _phase(phase)
    result = {"black": {}, "white": {}}
    for contact in shot.get("footContacts", []):
        if contact["startPhase"] <= phase < contact["endPhase"]:
            result[contact["actor"]][contact["foot"]] = _vector(contact["anchor"], 3, "foot anchor")
    return result


def two_bone_ik(root, target, upper_length, lower_length, pole):
    """Solve a two-segment leg against a world-space target and pole position.

    Unreachable targets are projected onto the reachable annulus and explicitly
    flagged. Segment lengths remain unchanged; no false foot-lock is claimed.
    """
    root = _vector(root, 3, "root")
    target = _vector(target, 3, "target")
    pole = _vector(pole, 3, "pole")
    upper_length = _finite(upper_length, "upper length")
    lower_length = _finite(lower_length, "lower length")
    if upper_length <= 0 or lower_length <= 0:
        raise ValueError("IK segment lengths must be positive")
    delta = [b - a for a, b in zip(root, target)]
    distance = math.hypot(*delta)
    if not math.isfinite(distance) or not math.isfinite(upper_length + lower_length):
        raise ValueError("IK geometry exceeds finite world range")
    minimum, maximum = abs(upper_length - lower_length), upper_length + lower_length
    reachable = minimum <= distance <= maximum
    solved_distance = min(maximum, max(minimum, distance))
    direction = [x / distance for x in delta] if distance else [0.0, -1.0, 0.0]
    offset = [b - a for a, b in zip(root, pole)]
    if not all(math.isfinite(x) for x in offset):
        raise ValueError("IK pole exceeds finite world range")
    projected = sum(x * y for x, y in zip(offset, direction))
    bend = [x - projected * y for x, y in zip(offset, direction)]
    bend_length = math.hypot(*bend)
    if bend_length < 1e-12:
        axis = min(range(3), key=lambda index: abs(direction[index]))
        fallback = [1.0 if index == axis else 0.0 for index in range(3)]
        projected = sum(x * y for x, y in zip(fallback, direction))
        bend = [x - projected * y for x, y in zip(fallback, direction)]
        bend_length = math.hypot(*bend)
    bend = [x / bend_length for x in bend]
    if solved_distance == 0:
        along, height = 0.0, upper_length
    else:
        # Law of cosines, scaled to avoid squaring large metre values.
        scale = max(upper_length, lower_length, solved_distance)
        a, b, d = upper_length / scale, lower_length / scale, solved_distance / scale
        along = scale * (a * a - b * b + d * d) / (2 * d)
        height = scale * math.sqrt(max(0.0, a * a - (along / scale) ** 2))
    knee = [r + along * axis + height * side for r, axis, side in zip(root, direction, bend)]
    foot = list(target) if reachable else [r + solved_distance * axis for r, axis in zip(root, direction)]
    if not all(math.isfinite(x) for x in knee + foot):
        raise ValueError("IK result exceeds finite world range")
    return {"knee": knee, "foot": foot, "reachable": reachable}


def foot_lock_ik(root, contact_anchor, upper_length, lower_length, pole):
    """Re-solve after root movement using the unchanged authored world anchor."""
    return two_bone_ik(root, contact_anchor, upper_length, lower_length, pole)
