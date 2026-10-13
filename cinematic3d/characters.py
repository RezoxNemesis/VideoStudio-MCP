"""Bounded humanoid morphology data. Geometry is authored from its rest skeleton.

These presets are procedural actors, never recovered identities or neural models.
Absent configuration preserves the original mannequin exactly.
"""
import math

PRESETS = ("athletic", "slender", "stocky", "chibi", "robot")
_SHAPES = {
    "athletic": (1.02, 1.18, .98),
    "slender": (1.08, .82, .94),
    "stocky": (.94, 1.32, 1.08),
    "chibi": (.88, 1.05, 1.55),
    "robot": (1.0, 1.10, 1.0),
}
MOTION_STYLES = ("grounded", "anime", "martial", "cartoony")


def _number(value, low, high, label):
    try:
        valid = type(value) in (int, float) and math.isfinite(value) and low <= value <= high
    except OverflowError:
        valid = False
    if not valid:
        raise ValueError(label + " must be finite and within its bounds")
    return value


def validate_actor(value):
    allowed = {"preset", "heightScale", "buildScale", "headScale", "material"}
    if not isinstance(value, dict) or "preset" not in value or value.keys() - allowed:
        raise ValueError("Actor requires a preset and only bounded morphology/material fields")
    if value["preset"] not in PRESETS:
        raise ValueError("Unknown humanoid morphology preset")
    result = {"preset": value["preset"]}
    for key in ("heightScale", "buildScale", "headScale"):
        if key in value:
            result[key] = _number(value[key], .8, 1.2, key)
    if "material" in value:
        material = value["material"]
        if not isinstance(material, dict) or set(material) != {"color", "metallic", "roughness"}:
            raise ValueError("Actor material requires color, metallic and roughness")
        color = material["color"]
        if not isinstance(color, list) or len(color) != 3:
            raise ValueError("Material color requires three linear RGB values")
        result["material"] = {"color": [_number(v, 0, 1, "material color") for v in color],
                              "metallic": _number(material["metallic"], 0, 1, "metallic"),
                              "roughness": _number(material["roughness"], .1, 1, "roughness")}
    return result


def validate_actors(value):
    if not isinstance(value, dict) or set(value) != {"black", "white"}:
        raise ValueError("Both named humanoid actors are required")
    return {name: validate_actor(value[name]) for name in ("black", "white")}


def validate_motion_style(value):
    if not isinstance(value, dict) or "preset" not in value or value.keys() - {"preset", "intensity"}:
        raise ValueError("Motion style requires a preset and optional intensity")
    if value["preset"] not in MOTION_STYLES:
        raise ValueError("Unknown motion style")
    result = {"preset": value["preset"]}
    if "intensity" in value:
        result["intensity"] = _number(value["intensity"], 0, 1, "motion intensity")
    return result


def morphology(value):
    if value is None:
        return {"preset": "legacy_mannequin", "height": 1.0, "build": 1.0, "head": 1.0,
                "configured": False}
    accepted = validate_actor(value)
    height, build, head = _SHAPES[accepted["preset"]]
    return {"preset": accepted["preset"], "height": height * accepted.get("heightScale", 1),
            "build": build * accepted.get("buildScale", 1),
            "head": head * accepted.get("headScale", 1), "configured": True,
            **({"material": accepted["material"]} if "material" in accepted else {})}


def select_library_actions(value=None):
    """Retain eleven old default clips; opt into bounded selected action packs."""
    from .motion import LEGACY_ACTIONS, ALL_ACTIONS
    if value is None:
        return LEGACY_ACTIONS
    if not isinstance(value, (list, tuple)) or not 1 <= len(value) <= len(ALL_ACTIONS):
        raise ValueError("Selected actions require a bounded nonempty list")
    if any(not isinstance(action, str) or action not in ALL_ACTIONS for action in value) or len(set(value)) != len(value):
        raise ValueError("Selected actions must be known and unique")
    return tuple(value)
