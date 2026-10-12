"""Pure quaternion, authored mannequin motion and world-anchor two-bone IK.

The procedural library is explicit estimated mannequin choreography. It makes no
source-image reconstruction claim and is independent of Blender or neural models.
"""
import math
import re

from .characters import validate_motion_style

LEGACY_ACTIONS = ("STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH", "KNOCKBACK",
                  "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH")
NEW_ACTIONS = ("IDLE", "WALK", "RUN", "JUMP", "DODGE", "DUCK", "PUNCH", "KICK", "DANCE", "WAVE")
ALL_ACTIONS = LEGACY_ACTIONS + NEW_ACTIONS

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


def _new_pose(action, phase):
    """Authored humanoid movement curves, independent of weapons or inference."""
    wave = math.sin(4 * math.pi * phase)
    pulse = math.sin(math.pi * phase)
    result = {}
    for name, side in (("black", -1), ("white", 1)):
        root = [side * 1.15, 0.0, 0.0]
        q = [[1., 0., 0., 0.] for _ in PRIMARY_JOINTS]
        q[1] = _axis(0, math.radians(2) * math.sin(2 * math.pi * phase))
        q[4], q[7] = _axis(0, -.25), _axis(0, -.25)
        if action in ("WALK", "RUN"):
            running = action == "RUN"
            stride, knee, arm = (42, 75, 48) if running else (24, 38, 22)
            root[0] = side * (1.65 - (1.0 if running else .6) * phase)
            root[1] = (.07 if running else .025) * abs(wave)
            q[1] = _axis(0, math.radians(16 if running else 4))
            q[10], q[13] = _axis(0, math.radians(stride) * wave), _axis(0, -math.radians(stride) * wave)
            q[11], q[14] = _axis(0, math.radians(knee) * max(0, -wave)), _axis(0, math.radians(knee) * max(0, wave))
            q[4], q[7] = _axis(0, -math.radians(arm) * wave), _axis(0, math.radians(arm) * wave)
            q[5], q[8] = _axis(0, math.radians(-75 if running else -18)), _axis(0, math.radians(-75 if running else -18))
        elif action == "JUMP":
            root[1] = .9 * pulse
            q[4], q[7] = _axis(0, -1.3 * pulse), _axis(0, -1.3 * pulse)
            q[10] = q[13] = _axis(0, -.6 * pulse)
            q[11] = q[14] = _axis(0, 1.4 * pulse)
        elif action == "DODGE":
            root[2] = .6 * pulse
            root[1] = -.14 * pulse
            q[1] = _axis(2, side * .4 * pulse)
            q[10], q[13] = _axis(2, .3 * pulse), _axis(2, -.3 * pulse)
            q[11] = q[14] = _axis(0, .5 * pulse)
        elif action == "DUCK":
            root[1] = -.32 * pulse
            q[1] = _axis(0, .35 * pulse)
            q[10] = q[13] = _axis(0, -.65 * pulse)
            q[11] = q[14] = _axis(0, 1.1 * pulse)
        elif action == "PUNCH":
            strike = math.sin(math.pi * min(1., phase * 1.2)) ** 2
            q[1] = _axis(1, side * .35 * strike)
            q[4] = _axis(0, -1.35 * strike)
            q[5] = _axis(0, -1.4 * (1 - strike))
            q[7], q[8] = _axis(0, -.6), _axis(0, -1.3)
            root[0] += -side * .15 * strike
        elif action == "KICK":
            q[10], q[11], q[12] = _axis(0, -1.35 * pulse), _axis(0, .8 * (1-pulse)), _axis(0, -.3 * pulse)
            q[1] = _axis(0, -.22 * pulse)
            q[4], q[7] = _axis(0, -.65 * pulse), _axis(0, .5 * pulse)
        elif action == "DANCE":
            root[2] = .2 * math.sin(2 * math.pi * phase)
            root[1] = .055 * abs(wave)
            q[0] = _axis(1, .25 * wave)
            q[1] = _axis(2, .2 * wave)
            q[4], q[7] = _axis(0, -1.2 + .5 * wave), _axis(0, -1.2 - .5 * wave)
            q[5], q[8] = _axis(0, -.5 - .4 * wave), _axis(0, -.5 + .4 * wave)
            q[10], q[13] = _axis(0, .15 * wave), _axis(0, -.15 * wave)
        elif action == "WAVE":
            q[4] = _axis(2, -1.7)
            q[5] = _axis(0, -1.0)
            q[6] = _axis(2, .5 * math.sin(6 * math.pi * phase))
            q[3] = _axis(1, .1 * math.sin(2 * math.pi * phase))
        result[name] = {"root": root, "rotations": q}
    return result


def _styled_pose(result, style, phase):
    """Styles modify real rotations, timing curves and body translation."""
    if style is None:
        return result
    accepted = validate_motion_style(style)
    preset, intensity = accepted["preset"], accepted.get("intensity", .65)
    amplitude = {"grounded": .85, "anime": 1.2, "martial": .95, "cartoony": 1.35}[preset]
    gain = 1 + (amplitude - 1) * intensity
    for actor in result.values():
        for index in range(1, 16):
            q = actor["rotations"][index]
            # Angular amplification extrapolates on the authored axis, not SLERP past bounds.
            sign = -1 if q[0] < 0 else 1
            axis = [sign*v for v in q[1:]]
            angle = 2 * math.atan2(math.hypot(*axis), sign*q[0])
            norm = math.hypot(*q[1:])
            if norm > 1e-12:
                actor["rotations"][index] = [sign*math.cos(angle*gain/2),
                    *(sign*v/norm*math.sin(angle*gain/2) for v in axis)]
        if preset == "grounded":
            actor["root"][1] *= 1 - .2 * intensity
        elif preset == "anime":
            actor["rotations"][1] = _multiply(actor["rotations"][1], _axis(1, .14*intensity*math.sin(math.pi*phase)))
        elif preset == "martial":
            actor["rotations"][7] = _multiply(actor["rotations"][7], _axis(0, -.3*intensity))
            actor["rotations"][8] = _multiply(actor["rotations"][8], _axis(0, -.4*intensity))
        elif preset == "cartoony":
            actor["root"][1] += .09*intensity*abs(math.sin(4*math.pi*phase))
            actor["rotations"][3] = _multiply(actor["rotations"][3], _axis(2, .22*intensity*math.sin(2*math.pi*phase)))
    return result


def procedural_pose(action, phase, style=None):
    """Return both mannequin roots and 16 local wxyz rotations for an action."""
    _phase(phase)
    if not isinstance(action, str) or action not in ALL_ACTIONS:
        raise ValueError("Unknown procedural mannequin action")
    if action in NEW_ACTIONS:
        return _styled_pose(_new_pose(action, phase), style, phase)
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
    return _styled_pose(result, style, phase)


def sample_pose(shot, phase):
    """Interpolate authored poses; otherwise explicitly use the mannequin library."""
    _phase(phase)
    keys = shot.get("poseKeyframes")
    if keys is None:
        return procedural_pose(shot["action"], phase, shot.get("motionStyle"))
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
    return _styled_pose(result, shot.get("motionStyle"), phase)


def sample_foot_contacts(shot, phase):
    """Return immutable world anchors for active half-open contact intervals."""
    _phase(phase)
    result = {"black": {}, "white": {}}
    for contact in shot.get("footContacts", []):
        if contact["startPhase"] <= phase < contact["endPhase"]:
            result[contact["actor"]][contact["foot"]] = _vector(contact["anchor"], 3, "foot anchor")
    return result


def support_root_drop(chains, margin=.005):
    """Plan a downward Y-up root adjustment from actual two-bone reach.

    Generated floor anchors stay fixed and segment lengths stay fixed. Raising
    a fully extended hip or moving it sideways otherwise projects the requested
    ankle onto the reachable annulus. Authored roots/contacts are not adjusted
    by the Blender caller. Impossible horizontal spans fail explicitly.
    """
    if not isinstance(chains, (list, tuple)) or len(chains) > 16:
        raise ValueError("A bounded list of support chains is required")
    margin = _finite(margin, "support reach margin")
    if not 0 <= margin <= .05:
        raise ValueError("Support reach margin must be 0..0.05 metres")
    minimum, maximum = 0., math.inf
    for chain in chains:
        if not isinstance(chain, dict) or set(chain) != {"hip", "target", "upperLength", "lowerLength"}:
            raise ValueError("Support chains require actual hip, target and segment lengths")
        hip, target = _vector(chain["hip"], 3, "support hip"), _vector(chain["target"], 3, "support target")
        upper, lower = _finite(chain["upperLength"], "upper length"), _finite(chain["lowerLength"], "lower length")
        if upper <= 0 or lower <= 0:
            raise ValueError("Support bone lengths must remain positive")
        reach = _finite(upper + lower - margin, "support reach")
        if reach <= 0:
            raise ValueError("Support margin exceeds the actual leg reach")
        delta = [_finite(a-b, "support displacement") for a,b in zip(hip, target)]
        horizontal = _finite(math.hypot(delta[0], delta[2]), "horizontal support displacement")
        if horizontal >= reach:
            raise ValueError("Procedural foot anchor exceeds horizontal leg reach; author a shorter step")
        vertical = reach * math.sqrt(max(0., 1-(horizontal/reach)**2))
        minimum = max(minimum, delta[1]-vertical)
        maximum = min(maximum, delta[1]+vertical)
    if minimum > maximum:
        raise ValueError("Support chains have no common downward root position")
    return minimum


def map_reference_guide(request):
    """Lift marked joints with explicit scale/depth; no hidden anatomy is inferred.

    Hashes bind the declared marks. Original encoded source/profile bytes are not
    observed by this numerical helper, and the receipt keeps that scope explicit.
    """
    required = {"schema", "sourceSha256", "profileSha256", "joints", "imageWidth", "imageHeight",
                "heightMeters", "depthMeters", "uncertaintyMeters"}
    if not isinstance(request, dict) or set(request) != required:
        raise ValueError("Reference guide requires exact hashes, marks, scale, depth and uncertainty")
    if request["schema"] != "videostudio.cinematic3d.reference-guide-request.v1":
        raise ValueError("Unknown reference guide request schema")
    for key in ("sourceSha256", "profileSha256"):
        if not isinstance(request[key], str) or not re.fullmatch(r"[a-f0-9]{64}", request[key]):
            raise ValueError("Exact source/profile SHA-256 required")
    for key in ("imageWidth", "imageHeight"):
        if type(request[key]) is not int or not 16 <= request[key] <= 8192:
            raise ValueError("Reference image dimensions must be 16..8192")
    height = _finite(request["heightMeters"], "heightMeters")
    if not .3 <= height <= 3:
        raise ValueError("Guide height must be .3..3 metres")
    if not isinstance(request["joints"], list) or len(request["joints"]) != 16:
        raise ValueError("Exactly sixteen source-image joints required")
    points = [_vector(point, 2, "source joint") for point in request["joints"]]
    if any(not 0 <= coordinate <= 1 for point in points for coordinate in point):
        raise ValueError("Source joints use normalized image coordinates")
    for a, b in ((0,1),(1,2),(2,3),(1,4),(4,5),(5,6),(1,7),(7,8),(8,9),
                 (0,10),(10,11),(11,12),(0,13),(13,14),(14,15)):
        if points[a] == points[b]:
            raise ValueError("Source guide has a zero-length marked bone")
    depths = _vector(request["depthMeters"], 16, "declared depths")
    uncertainty = _vector(request["uncertaintyMeters"], 16, "declared uncertainty")
    if any(abs(value) > 10 for value in depths) or any(not 0 <= value <= 10 for value in uncertainty):
        raise ValueError("Guide depths/uncertainty exceed ten metres")
    span = max(point[1] for point in points) - min(point[1] for point in points)
    if span < .01:
        raise ValueError("Source marks do not establish usable projected body height")
    scale, floor = height/span, max(points[12][1], points[15][1])
    aspect = request["imageWidth"]/request["imageHeight"]
    world = [[(point[0]-points[0][0])*aspect*scale, (floor-point[1])*scale, depth]
             for point, depth in zip(points, depths)]
    return {"schema": "videostudio.cinematic3d.reference-guide.v1", "status": "completed",
            "sourceSha256": request["sourceSha256"], "profileSha256": request["profileSha256"],
            "jointNames": list(PRIMARY_JOINTS), "sourceJoints": points, "joints3d": world,
            "imageWidth": request["imageWidth"], "imageHeight": request["imageHeight"],
            "uncertaintyMeters": uncertainty,
            "uncertaintyScope": "declared authoring bounds; not a measured model confidence",
            "assumptions": {"projection": "scaled_orthographic", "coordinates": "right_handed_y_up_metres",
                "sourceCoordinates": "oriented_image_normalized_xy_top_left", "heightMeters": height,
                "depthProvider": "explicit_user_authored_depth", "depthMeters": depths,
                "origin": "pelvis_x_at_ground_reference", "floor": "lower_marked_foot"},
            "evidence": {"motionProvider": "estimated_reference_guide", "originalImageReconstruction": False,
                "sourceBytesVerified": False, "profileBytesVerified": False, "nativeRendered": False,
                "identityReconstruction": False}}


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
