"""Bounded, offline Blender/Rigify production of portable cinematic scenes.

Run with Blender, never execute this script from an imported phone bundle::
  blender -b --factory-startup --python cinematic3d/blender_scene.py -- \
      --timeline request.json --output output --library-only

The mannequins are a labelled procedural demonstration. They are not an
original-image reconstruction, a neural prediction, or Android render evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import struct
import sys

if str(Path(__file__).resolve().parents[1]) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import bpy
from mathutils import Matrix, Quaternion, Vector

from cinematic3d.timeline import validate_timeline, sample_shot
from cinematic3d.motion import PRIMARY_JOINTS, sample_pose, sample_foot_contacts, foot_lock_ik
from cinematic3d.portable_glb import compact_glb

ACTIONS = ("STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH",
           "KNOCKBACK", "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH")
IMPACTS = {"CLASH", "BLADE_LOCK", "MACRO_CLASH"}
PRIMARY_BONES = ("DEF-spine", "DEF-spine.003", "DEF-spine.004", "DEF-spine.006",
                 "DEF-upper_arm.R", "DEF-forearm.R", "DEF-hand.R",
                 "DEF-upper_arm.L", "DEF-forearm.L", "DEF-hand.L",
                 "DEF-thigh.L", "DEF-shin.L", "DEF-foot.L",
                 "DEF-thigh.R", "DEF-shin.R", "DEF-foot.R")
BASIS = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))
MAX_GLB = 16 * 1024 * 1024
EPS = 1e-6


def yup(point):
    return BASIS @ Vector(point)


def local_quaternion(q):
    """Change Y-up wxyz local rotations to Blender's Z-up basis."""
    rotation = Quaternion(q).normalized().to_matrix()
    return (BASIS @ rotation @ BASIS.transposed()).to_quaternion()


def material(name, colour, metallic=0.0, roughness=0.6, emission=0.0):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*colour, 1)
    mat.use_nodes = True
    node = mat.node_tree.nodes.get("Principled BSDF")
    node.inputs["Base Color"].default_value = (*colour, 1)
    node.inputs["Metallic"].default_value = metallic
    node.inputs["Roughness"].default_value = roughness
    node.inputs["Emission Color"].default_value = (*colour, 1)
    node.inputs["Emission Strength"].default_value = emission
    return mat


def activate(obj):
    if bpy.context.object and bpy.context.object.mode != "OBJECT":
        bpy.ops.object.mode_set(mode="OBJECT")
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def apply(obj):
    activate(obj)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)


def ellipsoid(name, center, radii, mat, segments=16, rings=8):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=rings, radius=1, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.scale = radii
    apply(obj)
    obj.data.materials.append(mat)
    for polygon in obj.data.polygons:
        polygon.use_smooth = True
    return obj


def capsule(name, start, end, radius, mat):
    start, end = Vector(start), Vector(end)
    direction = end - start
    bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=radius, depth=direction.length,
                                       location=(start + end) / 2)
    obj = bpy.context.object
    obj.name = name
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = direction.to_track_quat("Z", "Y")
    apply(obj)
    obj.data.materials.append(mat)
    bevel = obj.modifiers.new("Machined rounded edges", "BEVEL")
    bevel.width = min(radius * 0.3, 0.025)
    bevel.segments = 2
    activate(obj)
    bpy.ops.object.modifier_apply(modifier=bevel.name)
    for polygon in obj.data.polygons:
        polygon.use_smooth = True
    return obj


def box(name, center, size, mat, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.scale = size
    apply(obj)
    obj.data.materials.append(mat)
    if bevel:
        mod = obj.modifiers.new("Rounded edges", "BEVEL")
        mod.width, mod.segments = bevel, 2
        activate(obj)
        bpy.ops.object.modifier_apply(modifier=mod.name)
    return obj


def empty(name):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    return obj


def distance_segment(point, a, b):
    direction = b - a
    factor = max(0.0, min(1.0, (point - a).dot(direction) / max(EPS, direction.length_squared)))
    return (point - (a + direction * factor)).length


def generate_actor(label, armour, joints, trim):
    """Generate real Rigify rig, then bind a lightweight weighted mannequin.

    Rigify's controls remain in the authoring file. Constraints on the deform
    skeleton are baked away before portable export; phone playback needs none.
    """
    bpy.ops.preferences.addon_enable(module="rigify")
    from rigify.metarigs.Basic import basic_human
    bpy.ops.object.armature_add()
    meta = bpy.context.object
    meta.name = label + "MetaRig"
    activate(meta)
    bpy.ops.object.mode_set(mode="EDIT")
    for bone in list(meta.data.edit_bones):
        meta.data.edit_bones.remove(bone)
    bpy.ops.object.mode_set(mode="OBJECT")
    basic_human.create(meta)
    bpy.ops.object.mode_set(mode="EDIT")
    for bone in meta.data.edit_bones:
        bone.head *= 0.9
        bone.tail *= 0.9
    bpy.ops.object.mode_set(mode="OBJECT")
    # Single-segment deform limbs retain straightforward sixteen-primary map.
    for bone in meta.pose.bones:
        if bone.rigify_type in {"limbs.arm", "limbs.leg"}:
            bone.rigify_parameters.segments = 1
    activate(meta)
    bpy.ops.pose.rigify_generate()
    rig = bpy.context.object
    rig.name = label + "Rigify"
    rig["cinematic_actor"] = label.lower()
    rig["rigify_rig_id"] = rig.data.get("rig_id", "")
    rig["primary_joints"] = list(PRIMARY_JOINTS)
    rig["primary_bones"] = list(PRIMARY_BONES)
    if not rig["rigify_rig_id"] or any(b not in rig.data.bones for b in PRIMARY_BONES):
        raise RuntimeError("Rigify did not generate the required sixteen-primary deform mapping")
    for bone in rig.pose.bones:
        bone.rotation_mode = "QUATERNION"
        # The direct primary-joint/analytic IK bake replaces control constraints.
        for constraint in list(bone.constraints):
            bone.constraints.remove(constraint)
    rig.hide_render = True  # Armature is non-renderable; child meshes stay visible.
    root = empty(label + "Root")
    rig.parent = root
    root.rotation_mode = "QUATERNION"
    meta.hide_render = True
    meta.hide_set(True)
    meta["authoring_only"] = True
    parts = []
    deform = [b for b in rig.data.bones if b.use_deform]
    for side in ("L", "R"):
        for limb, radius in (("upper_arm", 0.072), ("forearm", 0.061), ("thigh", 0.098), ("shin", 0.074)):
            bone = rig.data.bones["DEF-" + limb + "." + side]
            parts.append(capsule(label + "_" + limb + "_" + side, bone.head_local, bone.tail_local, radius, armour))
        for limb in ("upper_arm", "forearm", "thigh", "shin", "hand"):
            bone = rig.data.bones["DEF-" + limb + "." + side]
            radius = 0.083 if limb == "thigh" else 0.057
            parts.append(ellipsoid(label + "_Joint_" + limb + "_" + side, bone.head_local, (radius, radius, radius), joints))
        hand = rig.data.bones["DEF-hand." + side]
        parts.append(ellipsoid(label + "_Hand_" + side, (hand.head_local + hand.tail_local) / 2, (0.053, 0.055, 0.057), armour))
        foot = rig.data.bones["DEF-foot." + side]
        parts.append(ellipsoid(label + "_Foot_" + side, (foot.head_local.x, -0.065, 0.061), (0.084, 0.143, 0.061), armour))
    parts.append(ellipsoid(label + "_Pelvis", (0, 0.025, 0.965), (0.18, 0.12, 0.14), armour))
    parts.append(ellipsoid(label + "_Torso", (0, 0.015, 1.245), (0.215, 0.12, 0.25), armour))
    parts.append(ellipsoid(label + "_Neck", (0, -0.014, 1.53), (0.056, 0.054, 0.065), joints))
    parts.append(ellipsoid(label + "_Head", (0, -0.017, 1.665), (0.104, 0.094, 0.135), armour, 24, 12))
    parts.append(box(label + "_Visor", (0, -0.102, 1.69), (0.133, 0.012, 0.017), trim, 0.004))
    bpy.ops.object.select_all(action="DESELECT")
    for part in parts:
        part.select_set(True)
    bpy.context.view_layer.objects.active = parts[0]
    bpy.ops.object.join()
    mesh = bpy.context.object
    mesh.name = label + "WeightedMannequin"
    # Deterministic automatic closest-bone blending binds every real vertex.
    # This avoids platform-sensitive heat-weight failures on intersecting armour.
    groups = {b.name: mesh.vertex_groups.new(name=b.name) for b in deform}
    for vertex in mesh.data.vertices:
        position = mesh.matrix_world @ vertex.co
        closest = sorted((distance_segment(position, b.head_local, b.tail_local), b.name) for b in deform)[:2]
        inv = [1.0 / max(0.012, d) ** 3 for d, _ in closest]
        total = sum(inv)
        for (_, name), weight in zip(closest, inv):
            groups[name].add([vertex.index], weight / total, "REPLACE")
    mesh.parent = rig
    modifier = mesh.modifiers.new("Rigify weighted skin", "ARMATURE")
    modifier.object = rig
    modifier.use_deform_preserve_volume = False  # Match portable glTF linear skinning.
    sword = generate_katana(label, trim, joints)
    sword.parent = rig
    sword.parent_type = "BONE"
    sword.parent_bone = "DEF-hand.R"
    # Blender bone-parent origin is the bone tail; fixed wrist socket is rigid.
    wrist = rig.data.bones["DEF-hand.R"].head_local.copy()
    hand = rig.data.bones["DEF-hand.R"]
    parent_rest = hand.matrix_local @ Matrix.Translation((0, hand.length, 0))
    # Sword local +Y runs along blade; fixed socket at the wrist/hand head.
    sword.matrix_parent_inverse = Matrix.Identity(4)
    sword.matrix_basis = parent_rest.inverted() @ Matrix.Translation(wrist) @ hand.matrix_local.to_3x3().to_4x4()
    rig["katana_object"] = sword.name
    rig["katana_socket_local"] = list(sword.matrix_basis.to_translation())
    return {"label": label, "root": root, "rig": rig, "mesh": mesh, "sword": sword,
            "deform": deform, "socket": sword.matrix_basis.copy(), "rest": {b.name: b.matrix_local.copy() for b in deform}}


def generate_katana(label, metal, grip):
    blade_mat = material(label + " Katana steel", (0.57, 0.65, 0.72), 0.85, 0.23)
    parts = [box(label + "_Blade", (0, 0.61, 0), (0.033, 0.91, 0.012), blade_mat, 0.004),
             box(label + "_Edge", (0.018, 0.61, 0), (0.006, 0.88, 0.014), metal, 0.002),
             box(label + "_Hilt", (0, 0, 0), (0.042, 0.28, 0.035), grip, 0.006),
             ellipsoid(label + "_Guard", (0, 0.15, 0), (0.085, 0.013, 0.056), metal)]
    bpy.ops.object.select_all(action="DESELECT")
    for part in parts:
        part.select_set(True)
    bpy.context.view_layer.objects.active = parts[0]
    bpy.ops.object.join()
    sword = bpy.context.object
    sword.name = label + "Katana"
    # Joining preserves first-part origin; reset mesh into authored socket origin.
    transform = sword.matrix_world.copy()
    for vertex in sword.data.vertices:
        vertex.co = transform @ vertex.co
    sword.matrix_world = Matrix.Identity(4)
    sword["blade_start"] = [0, 0.16, 0]
    sword["blade_end"] = [0, 1.06, 0]
    # Generic glTF extras are not axis-converted by Blender's exporter.
    # Native endpoint history consumes these explicit glTF mesh-local anchors.
    sword["gltf_blade_start"] = [0, 0, -0.16]
    sword["gltf_blade_end"] = [0, 0, -1.06]
    sword["socket_role"] = "rigid_wrist"
    return sword


def environment():
    rng = random.Random(731)
    road = material("Cracked basalt road", (0.12, 0.135, 0.145), roughness=0.92)
    concrete = material("Ruined warm concrete", (0.28, 0.25, 0.225), roughness=0.95)
    dark = material("Ruined openings", (0.035, 0.045, 0.055), roughness=0.95)
    amber = material("Sunset haze geometry", (0.65, 0.245, 0.095), emission=0.35)
    ground = box("RuinRoad", (0, 0, -0.11), (38, 38, 0.2), road)
    ground["environment"] = True
    for side in (-1, 1):
        for i in range(7):
            x, y = side * (4.8 + rng.random() * 3.6), -4 + i * 2.7
            width, height = 1.25 + rng.random(), 2.1 + rng.random() * 5
            building = box("RuinTower_%s_%s" % (side, i), (x, y, height / 2), (width, 1.7, height), concrete, 0.03)
            building["environment"] = True
            for floor in range(int(height / 0.7)):
                window = box("RuinWindow_%s_%s_%s" % (side, i, floor), (x - side * width / 2.0, y - 0.3, floor * 0.7 + 0.5), (0.025, 0.45, 0.3), dark)
                window["environment"] = True
            pillar = box("BrokenColumn_%s_%s" % (side, i), (x - side, y + 1, 0.8), (0.3, 0.3, 1.6), concrete)
            pillar.rotation_euler = (rng.random() * 0.3, side * 0.35, rng.random())
            pillar["environment"] = True
    for i in range(34):
        x, y = rng.uniform(-7, 7), rng.uniform(-5, 12)
        if abs(x) < 2 and abs(y) < 2:
            x += 3.5
        rubble = box("StaticRubble_%02d" % i, (x, y, 0.08), (rng.uniform(0.1, 0.45), rng.uniform(0.1, 0.45), rng.uniform(0.08, 0.25)), concrete)
        rubble.rotation_euler = (rng.random(), rng.random(), rng.random() * 3)
        rubble["environment"] = True
    sun = ellipsoid("SunsetDisc", (0, 17, 4.2), (1.4, 0.06, 1.4), amber, 24, 12)
    sun["environment"] = True
    world = bpy.data.worlds.new("Sunset world")
    bpy.context.scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.12, 0.08, 0.065, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.5
    for name, location, colour, power, size in (("Sunset key", (-4, 6, 7), (1, 0.4, 0.16), 1700, 6),
                                               ("Cool fill", (0, -4, 5), (0.45, 0.65, 1), 1350, 5),
                                               ("Rim", (4, 4, 4), (1, 0.65, 0.25), 1100, 4)):
        data = bpy.data.lights.new(name, "AREA")
        data.energy, data.color, data.shape, data.size = power, colour, "DISK", size
        obj = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(obj)
        obj.location = location
        obj.rotation_euler = (Vector((0, 0, 1)) - obj.location).to_track_quat("-Z", "Y").to_euler()


def camera():
    data = bpy.data.cameras.new("CinematicCamera")
    data.sensor_fit = "VERTICAL"
    obj = bpy.data.objects.new("CinematicCamera", data)
    bpy.context.collection.objects.link(obj)
    obj.rotation_mode = "QUATERNION"
    bpy.context.scene.camera = obj
    data.clip_start, data.clip_end = 0.05, 160
    return obj


def vfx_geometry():
    spark = material("Impact orange sparks", (1, 0.38, 0.035), metallic=0.2, roughness=0.3, emission=5)
    trail = material("Blade ribbon amber", (1, 0.45, 0.07), metallic=0.1, roughness=0.3, emission=1.3)
    stone = material("Contact flying debris", (0.25, 0.21, 0.18), roughness=0.95)
    result = {"sparks": [], "rubble": [], "trails": []}
    for i in range(12):
        obj = capsule("C3D_Spark_%02d" % i, (0, 0, 0), (0, 0, 0.1), 0.007, spark)
        obj.location = (0, 0, 0)
        result["sparks"].append(obj)
    for i in range(8):
        obj = box("C3D_Rubble_%02d" % i, (0, 0, 0), (0.06, 0.06, 0.055), stone)
        result["rubble"].append(obj)
    for label in ("Black", "White"):
        # Six independent rigid ribbon segments preserve vertex portability.
        for age in range(6):
            obj = box("C3D_Trail_" + label + "_%d" % age, (0, 0, 0), (1, 1, 1), trail)
            obj.rotation_mode = "QUATERNION"
            obj["cinematic_trail_template"] = "unit_cube_v1"
            result["trails"].append(obj)
    for group in result.values():
        for obj in group:
            obj.scale = (EPS, EPS, EPS)
            obj.rotation_mode = "QUATERNION"
    return result


def bone_matrix(head, tail, rest):
    """Bone local +Y axis along anatomical segment, preserve rest roll."""
    head, tail = Vector(head), Vector(tail)
    direction = tail - head
    original = rest.to_3x3().col[1]
    rotation = original.rotation_difference(direction.normalized()).to_matrix() @ rest.to_3x3()
    return Matrix.Translation(head) @ rotation.to_4x4()


def solve_two_bone(hip, ankle, upper, lower, pole):
    delta = ankle - hip
    distance = min(upper + lower - 1e-5, max(abs(upper - lower) + 1e-5, delta.length))
    axis = delta.normalized()
    pole_axis = pole - axis * pole.dot(axis)
    if pole_axis.length < EPS:
        pole_axis = Vector((0, -1, 0))
    pole_axis.normalize()
    along = (upper * upper - lower * lower + distance * distance) / (2 * distance)
    height = math.sqrt(max(0.0, upper * upper - along * along))
    knee = hip + axis * along + pole_axis * height
    return knee, hip + axis * distance


def set_chain(actor, upper_name, lower_name, tip_name, origin, target, pole, tip_orientation=None, require_reachable=False):
    rig = actor["rig"]
    upper = rig.data.bones[upper_name]
    lower = rig.data.bones[lower_name]
    solution = foot_lock_ik(list(origin), list(target), upper.length, lower.length, list(origin + pole))
    if require_reachable and not solution["reachable"]:
        raise ValueError("Authored foot contact is unreachable after root movement")
    knee, end = Vector(solution["knee"]), Vector(solution["foot"])
    rig.pose.bones[upper_name].matrix = bone_matrix(origin, knee, actor["rest"][upper_name])
    bpy.context.view_layer.update()
    rig.pose.bones[lower_name].matrix = bone_matrix(knee, end, actor["rest"][lower_name])
    bpy.context.view_layer.update()
    tip = rig.pose.bones[tip_name]
    if tip_orientation:
        tip.matrix = Matrix.Translation(end) @ tip_orientation.to_4x4()
    else:
        tip.matrix = Matrix.Translation(end) @ actor["rest"][tip_name].to_3x3().to_4x4()
    return end


def foot_targets(actor, shot, phase):
    """Expected ankle positions and stance flags, independent of bone replay."""
    root, rig = actor["root"], actor["rig"]
    direction = 1 if actor["label"] == "Black" else -1
    contacts = sample_foot_contacts(shot, phase)["black" if direction == 1 else "white"]
    authored = bool(shot.get("poseKeyframes"))
    grounded = not authored and shot["action"] not in {"AERIAL_FLIP", "TUMBLE"} and root.location.z <= 0.005
    targets = {}
    for side, key in (("L", "leading"), ("R", "trailing")):
        explicit = contacts.get(key)
        if explicit is None and not grounded:
            continue
        rest = rig.data.bones["DEF-foot." + side].head_local
        goal = yup(explicit) if explicit is not None else rig.matrix_world @ rest
        stance = True
        if explicit is None:
            goal.z = rest.z
            if shot["action"] in IMPACTS:
                goal.x = -direction * 0.92 + direction * (0.12 if side == "L" else -0.12)
                goal.y = 0.088 if side == "L" else -0.088
            elif shot["action"] == "CHARGE":
                offset = 0.0 if side == "L" else 0.5
                cycle = phase * 3 + offset
                step, progress = math.floor(cycle), cycle % 1
                start_phase = max(0, (step - offset) / 3)
                next_phase = min(1, (step + 1 - offset) / 3)
                anchor_x = -direction * (2.2 - 1.4 * start_phase) + direction * 0.18
                next_x = -direction * (2.2 - 1.4 * next_phase) + direction * 0.18
                goal.x, goal.y = anchor_x, 0.088 if side == "L" else -0.088
                stance = progress < 0.6
                if not stance:
                    swing = (progress - 0.6) / 0.4
                    goal.x = anchor_x * (1 - swing) + next_x * swing
                    goal.z += 0.13 * math.sin(math.pi * swing)
        targets[side] = {"goal": goal, "stance": stance, "explicit": explicit is not None}
    return targets


def pose_actors(state, shot, phase):
    estimates = sample_pose(shot, phase)
    authored = bool(shot.get("poseKeyframes"))
    for actor in state["actors"]:
        black = actor["label"] == "Black"
        direction = 1 if black else -1
        entry = estimates["black" if black else "white"]
        root = actor["root"]
        root.location = yup(entry["root"])
        # Rest human faces Blender -Y. Authored quaternions preserve local axes.
        root.rotation_quaternion = Quaternion() if authored else Quaternion((0, 0, 1), direction * math.pi / 2)
        if not authored:
            # Hand reach/contact is solved in metres from actual Rigify lengths.
            action = shot["action"]
            if action in IMPACTS:
                approach = math.sin(min(1, phase * 2) * math.pi / 2)
                root.location.x = -direction * (1.15 - 0.35 * approach)
                root.location.z = -0.08  # Crouch gives real planted-leg reach.
            elif action == "CHARGE":
                root.location.x = -direction * (2.2 - 1.4 * phase)
                root.location.z = -0.055
            elif action == "LANDING":
                root.location.z = 0.8 * (1 - phase) ** 2 - 0.075 * math.sin(math.pi * phase)
            elif action in {"AERIAL_FLIP", "TUMBLE"}:
                root.location.z = max(0, math.sin(math.pi * phase)) * (0.8 if action == "AERIAL_FLIP" else 0.25)
                root.rotation_quaternion = root.rotation_quaternion @ Quaternion((1, 0, 0), phase * 2 * math.pi)
        rig = actor["rig"]
        for bone in rig.pose.bones:
            bone.matrix_basis = Matrix.Identity(4)
        for name, rotation in zip(PRIMARY_BONES, entry["rotations"]):
            rig.pose.bones[name].rotation_quaternion = local_quaternion(rotation)
        if not authored:
            # Heading belongs to the keyed world root, once, rather than being
            # duplicated in the procedural pelvis pose.
            rig.pose.bones["DEF-spine"].rotation_quaternion = Quaternion()
        bpy.context.view_layer.update()
        inverse = rig.matrix_world.inverted()
        actor["foot_goals"] = {}
        for side, target in foot_targets(actor, shot, phase).items():
            thigh_name, shin_name, foot_name = ("DEF-" + name + "." + side for name in ("thigh", "shin", "foot"))
            hip = rig.pose.bones[thigh_name].head.copy()
            goal = target["goal"]
            set_chain(actor, thigh_name, shin_name, foot_name, hip, inverse @ goal, Vector((0, -1, 0)), require_reachable=target["explicit"])
            if target["stance"]:
                actor["foot_goals"][side] = goal.copy()
        if authored:
            continue
        # Procedural battle wrists: curve into contact at phase .5, then follow through.
        wrist_x = -direction * (0.4 + 0.32 * (1 - math.sin(math.pi * phase)))
        wrist_z = 1.28 + 0.18 * math.cos(math.pi * phase)
        if shot["action"] in IMPACTS:
            wrist_z = 1.28
        wrist_world = Vector((wrist_x, 0, wrist_z))
        shoulder = rig.pose.bones["DEF-upper_arm.R"].head.copy()
        wrist = inverse @ wrist_world
        end = set_chain(actor, "DEF-upper_arm.R", "DEF-forearm.R", "DEF-hand.R", shoulder, wrist, Vector((0, -1, -0.3)))
        # Orient the forearm's rigid socket with exact crossed steel directions.
        blade_direction = Vector((direction * 0.866025403784, 0, 0.5))
        if shot["action"] not in IMPACTS:
            angle = -0.8 + phase * 1.6
            blade_direction = Vector((direction * math.cos(angle), 0.15 * math.sin(math.pi * phase), math.sin(angle)))
        local_direction = rig.matrix_world.to_3x3().inverted() @ blade_direction
        # Articulate the wrist bone; katana keeps one immutable local socket.
        desired = Matrix.Translation(end) @ local_direction.to_track_quat("Y", "Z").to_matrix().to_4x4()
        actor["sword"].matrix_basis = actor["socket"]
        rig.pose.bones["DEF-hand.R"].matrix = desired
    bpy.context.view_layer.update()


def blade_segment(actor):
    obj = actor["sword"].evaluated_get(bpy.context.evaluated_depsgraph_get())
    return obj.matrix_world @ Vector(obj["blade_start"]), obj.matrix_world @ Vector(obj["blade_end"])


def segment_contact(first, second):
    """Closest points on finite actual blade axes (Ericson segment distance)."""
    p1, q1 = first
    p2, q2 = second
    d1, d2, r = q1 - p1, q2 - p2, p1 - p2
    a, e, f = d1.dot(d1), d2.dot(d2), d2.dot(r)
    c, b = d1.dot(r), d1.dot(d2)
    denominator = a * e - b * b
    s = max(0, min(1, (b * f - c * e) / denominator)) if abs(denominator) > EPS else 0
    t = (b * s + f) / e
    if t < 0:
        t, s = 0, max(0, min(1, -c / a))
    elif t > 1:
        t, s = 1, max(0, min(1, (b - c) / a))
    x, y = p1 + d1 * s, p2 + d2 * t
    return (x - y).length, (x + y) / 2


def blade_mesh_raycast(source, target):
    """Cast a finite world-space blade into the other's evaluated mesh.

    Transform both endpoints into target-local space, including scale, before
    setting the finite ray distance. A guard/hilt hit outside the target's blade
    interval does not qualify. Misses and degenerate transforms have no invented
    error measurement; callers must reject required impact contacts.
    """
    graph = bpy.context.evaluated_depsgraph_get()
    start, end = blade_segment(source)
    other = target["sword"].evaluated_get(graph)
    miss = {"hit": False, "point": None, "normal": None, "polygonIndex": None,
            "errorMeters": None, "target": other.name, "method": "evaluated_mesh_Object.ray_cast"}
    world_length = (end - start).length
    determinant = other.matrix_world.to_3x3().determinant()
    if (other.type != "MESH" or not all(math.isfinite(c) for p in (start, end) for c in p)
            or not math.isfinite(world_length) or not EPS < world_length <= 10
            or not math.isfinite(determinant) or abs(determinant) < 1e-10):
        return dict(miss, reason="degenerate_blade_or_target_transform")
    inverse = other.matrix_world.inverted()
    local_start, local_end = inverse @ start, inverse @ end
    local_delta = local_end - local_start
    distance = local_delta.length
    if not math.isfinite(distance) or not EPS < distance <= 1e6:
        return dict(miss, reason="degenerate_local_ray")
    hit, point, normal, polygon = other.ray_cast(local_start, local_delta / distance,
                                                distance=distance, depsgraph=graph)
    if not hit or polygon < 0:
        return dict(miss, reason="no_evaluated_mesh_hit")
    target_start, target_end = Vector(other["blade_start"]), Vector(other["blade_end"])
    target_axis = target_end - target_start
    if target_axis.length_squared <= EPS * EPS:
        return dict(miss, reason="degenerate_target_blade")
    along = (point - target_start).dot(target_axis) / target_axis.length_squared
    if not -1e-6 <= along <= 1 + 1e-6:
        return dict(miss, reason="mesh_hit_outside_blade_interval")
    world_point = other.matrix_world @ point
    world_normal = other.matrix_world.to_3x3().inverted().transposed() @ normal
    _, axis_contact = segment_contact((start, end), blade_segment(target))
    error = (world_point - axis_contact).length
    if (not all(math.isfinite(c) for c in (*world_point, *world_normal)) or not math.isfinite(error)
            or world_normal.length <= EPS):
        return dict(miss, reason="nonfinite_mesh_hit")
    world_normal.normalize()
    return {"hit": True, "point": list(world_point), "normal": list(world_normal),
            "polygonIndex": int(polygon), "errorMeters": error, "target": other.name,
            "rayDistanceMeters": world_length, "method": "evaluated_mesh_Object.ray_cast"}


def key_transform(obj, frame):
    for field in ("location", "rotation_quaternion", "scale"):
        obj.keyframe_insert(field, frame=frame)


def set_camera(obj, sample):
    control = sample["camera"]
    obj.location = yup(control["position"])
    target = yup(control["target"])
    forward = (target - obj.location).normalized()
    up = yup(control.get("up", [0, 1, 0]))
    right = forward.cross(up)
    if right.length < EPS:
        up = Vector((0, 1, 0))
        right = forward.cross(up)
    right.normalize()
    image_up = right.cross(forward).normalized()
    obj.rotation_quaternion = Matrix((right, image_up, -forward)).transposed().to_quaternion()
    obj.data.angle = math.radians(control["fovDegrees"])


def pose_vfx(state, shot, phase, output_frame, fps, history, contact):
    active = shot["action"] in IMPACTS and shot["vfx"]["sparkBurst"]
    if phase >= 0.5 and state.get("vfx_anchor") is None:
        state["vfx_anchor"] = contact.copy()
    origin = state.get("vfx_anchor") or contact
    elapsed = (phase - 0.5) * (shot["durationFrames"] - shot["hitStopFrames"]) / fps
    rng = random.Random(811)
    for i, obj in enumerate(state["vfx"]["sparks"]):
        direction = Vector((rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(0.1, 1))).normalized()
        visible = active and 0 <= elapsed < 0.45
        obj.location = origin + direction * elapsed * (1.2 + i * 0.07) + Vector((0, 0, -2.5 * elapsed * elapsed))
        obj.rotation_quaternion = direction.to_track_quat("Z", "Y")
        size = max(EPS, 1 - elapsed / 0.45) if visible else EPS
        obj.scale = (size, size, size * 1.6)
    for i, obj in enumerate(state["vfx"]["rubble"]):
        angle = i * math.tau / 8
        visible = shot["vfx"]["rubble"] and shot["action"] in IMPACTS and 0 <= elapsed < 0.65
        obj.location = origin + Vector((math.cos(angle), math.sin(angle), 0.7)) * elapsed * 1.6 + Vector((0, 0, -3.7 * elapsed * elapsed))
        obj.rotation_quaternion = Quaternion(Vector((0.3, 0.7, 0.2)).normalized(), elapsed * (3 + i))
        obj.scale = (1, 1, 1) if visible else (EPS, EPS, EPS)
    for actor_index, actor in enumerate(state["actors"]):
        current = blade_segment(actor)
        history[actor_index].append(current)
        history[actor_index] = history[actor_index][-7:]
        for age in range(6):
            obj = state["vfx"]["trails"][actor_index * 6 + age]
            available = len(history[actor_index]) > age + 1 and shot["vfx"]["slashTrail"]
            if available:
                newer, older = history[actor_index][-age - 1], history[actor_index][-age - 2]
                center_new = (newer[0] + newer[1]) / 2
                center_old = (older[0] + older[1]) / 2
                blade = (newer[1] - newer[0]).normalized()
                sweep = center_new - center_old
                sweep -= blade * sweep.dot(blade)
                separation = sweep.length
                obj.location = (center_new + center_old) / 2
                if separation > 1e-5:
                    across = sweep.normalized()
                    normal = across.cross(blade).normalized()
                    obj.rotation_quaternion = Matrix((across, blade, normal)).transposed().to_quaternion()
                    obj.scale = (separation, (newer[1] - newer[0]).length, 0.006 * (1 - age / 7))
                else:
                    obj.scale = (EPS, EPS, EPS)
                state["trail_samples"] += int(separation > 1e-5)
            else:
                obj.scale = (EPS, EPS, EPS)


def animated_objects(state):
    return ([actor["root"] for actor in state["actors"]] + [actor["rig"] for actor in state["actors"]]
            + [actor["sword"] for actor in state["actors"]] + [state["camera"]]
            + [obj for values in state["vfx"].values() for obj in values])


def begin_actions(state, name):
    for obj in animated_objects(state):
        obj.animation_data_create()
        action = bpy.data.actions.new(name + "__" + obj.name)
        obj.animation_data.action = action


def finish_actions(state, name, end):
    for obj in animated_objects(state):
        action = obj.animation_data.action
        for curve in action.fcurves:
            for key in curve.keyframe_points:
                key.interpolation = "LINEAR"
        track = obj.animation_data.nla_tracks.new()
        track.name = name
        strip = track.strips.new(name, 1, action)
        strip.name = name
        strip.action_frame_start, strip.action_frame_end = 1, end
        strip.frame_start, strip.frame_end = 1, end
        strip.extrapolation = "NOTHING"
        track.mute = True
        obj.animation_data.action = None


def bake_clip(state, shot, name, fps, frames):
    begin_actions(state, name)
    history = [[], []]
    prepare_impact_origin(state, shot)
    clip_shot = dict(shot, hitStopFrames=0, durationFrames=frames)
    # One interpolation endpoint is authored after the last output sample.
    # Native export/render samples n/fps and never includes this endpoint.
    for frame in range(frames + 1):
        phase = frame / frames
        bpy.context.scene.frame_set(frame + 1)
        pose_actors(state, shot, phase)
        sample = sample_shot(clip_shot, min(frame, frames - 1), fps)
        set_camera(state["camera"], sample)
        distance, contact = segment_contact(blade_segment(state["actors"][0]), blade_segment(state["actors"][1]))
        pose_vfx(state, shot, phase, frame, fps, history, contact)
        for actor in state["actors"]:
            key_transform(actor["root"], frame + 1)
            key_transform(actor["sword"], frame + 1)
            for bone in actor["deform"]:
                pose = actor["rig"].pose.bones[bone.name]
                for field in ("location", "rotation_quaternion", "scale"):
                    pose.keyframe_insert(field, frame=frame + 1)
        key_transform(state["camera"], frame + 1)
        for group in state["vfx"].values():
            for obj in group:
                key_transform(obj, frame + 1)
        if shot["action"] == "CLASH" and frame == frames // 2:
            state["impact_distance"] = distance
            state["contact"] = list(contact)
    finish_actions(state, name, frames + 1)


def default_shot(action, fps):
    return {"id": action.lower(), "action": action, "durationFrames": fps * 2, "hitStopFrames": 0,
            "camera": {"mode": "macro" if action == "MACRO_CLASH" else "wide", "fovDegrees": 48, "shakeIntensity": 0.35 if action in IMPACTS else 0},
            "vfx": {"sparkBurst": True, "slashTrail": True, "rubble": True}}


def shot_clip_name(shot):
    # Blender NLA names are bounded strings; timeline IDs may contain 96 chars.
    identity = shot["id"]
    return ("C3D_SHOT_I_" + identity) if len(identity) <= 45 else ("C3D_SHOT_H_" + hashlib.sha256(identity.encode("ascii")).hexdigest()[:32])


def prepare_impact_origin(state, shot):
    state["vfx_anchor"] = None
    if shot["action"] in IMPACTS:
        pose_actors(state, shot, 0.5)
        _, origin = segment_contact(blade_segment(state["actors"][0]), blade_segment(state["actors"][1]))
        state["vfx_anchor"] = origin.copy()
        # Cache the actual contact query at the exact .5 impact pose. Keep the
        # existing VFX centre unchanged; adding measurement cannot move assets.
        state.setdefault("impact_mesh_raycasts", {})[shot["id"]] = [
            blade_mesh_raycast(state["actors"][0], state["actors"][1]),
            blade_mesh_raycast(state["actors"][1], state["actors"][0])]


def build_scene(timeline, library_only=False):
    if library_only and (timeline["motionProvider"] != "procedural_mannequin" or any(s.get("poseKeyframes") or s.get("footContacts") for s in timeline["shots"])):
        raise ValueError("The offline library is procedural_mannequin only; authored poses/contacts require a regular bundle rebuild")
    if timeline["motionProvider"] == "authored_3d" and any(not s.get("poseKeyframes") for s in timeline["shots"]):
        raise ValueError("authored_3d requires actual authored pose keyframes for every shot")
    if timeline["motionProvider"] == "procedural_mannequin" and any(s.get("poseKeyframes") for s in timeline["shots"]):
        raise ValueError("Authored pose keyframes require motionProvider authored_3d")
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    bpy.context.scene.render.fps = timeline["fps"]
    black = material("Matte carbon armour", (0.028, 0.036, 0.044), 0.3, 0.62)
    white = material("White ceramic armour", (0.78, 0.8, 0.77), 0.06, 0.48)
    joint = material("Graphite mechanical joints", (0.07, 0.08, 0.09), 0.5, 0.4)
    trim = material("Amber identification trim", (0.72, 0.3, 0.04), 0.35, 0.4, 0.12)
    actors = [generate_actor("Black", black, joint, trim), generate_actor("White", white, joint, trim)]
    environment()
    state = {"actors": actors, "camera": camera(), "vfx": vfx_geometry(), "trail_samples": 0,
             "impact_distance": None, "contact": None, "timeline": timeline, "library_only": library_only}
    fps = timeline["fps"]
    clips = [(default_shot(action, fps), "C3D_" + action, fps * 2) for action in ACTIONS] if library_only else [
        (shot, shot_clip_name(shot), (max(8, shot["durationFrames"] - shot["hitStopFrames"]) + 1) // 2 * 2) for shot in timeline["shots"]]
    if len({name for _, name, _ in clips}) != len(clips):
        raise ValueError("Portable clip names must be unique")
    for shot, name, frames in clips:
        bake_clip(state, shot, name, fps, frames)
    state["clips"] = {name: frames / fps for _, name, frames in clips}
    state["clip_shots"] = clips
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device, scene.cycles.samples = "CPU", 12
    import _cycles
    # OIDN is a compiled feature, not implied by the presence of Cycles.
    # Debian's installed 4.3 build renders on CPU but has no OIDN support.
    state["denoiser"] = {"name": "OpenImageDenoise", "available": bool(_cycles.with_openimagedenoise)}
    scene.cycles.use_denoising = state["denoiser"]["available"]
    if scene.cycles.use_denoising:
        scene.cycles.denoiser = "OPENIMAGEDENOISE"
    scene.render.resolution_x, scene.render.resolution_y, scene.render.resolution_percentage = 640, 360, 100
    scene.render.image_settings.file_format = "PNG"
    scene.view_settings.view_transform = "AgX"
    return state


def choose_clip(state, name):
    for obj in animated_objects(state):
        obj.animation_data.action = None
        for track in obj.animation_data.nla_tracks:
            track.mute = track.name != name


def measure_scene(state):
    """Measure baked NLA replay at every actual output clock sample."""
    fps = state["timeline"]["fps"]
    foot_errors, socket_errors = [], []
    impact_distances = []
    impact_mesh_raycasts = []
    sparks_visible = rubble_visible = 0
    trail_samples = 0
    measurements = []
    for shot, name, frames in state["clip_shots"]:
        choose_clip(state, name)
        count = shot["durationFrames"]
        local_foot = []
        local_sparks = local_rubble = 0
        local_raycasts = None
        phases = sorted(set([sample_shot(shot, n, fps)["phase"] for n in range(count)] + [0.5]))
        for phase in phases:
            time = 1 + phase * frames
            bpy.context.scene.frame_set(math.floor(time), subframe=time % 1)
            for actor in state["actors"]:
                rig = actor["rig"]
                for side, target in foot_targets(actor, shot, phase).items():
                    if not target["stance"]:
                        continue
                    goal = target["goal"]
                    world_ankle = rig.matrix_world @ rig.pose.bones["DEF-foot." + side].head
                    error = (world_ankle - goal).length
                    foot_errors.append(error)
                    local_foot.append(error)
                sword = actor["sword"].evaluated_get(bpy.context.evaluated_depsgraph_get())
                hand = rig.matrix_world @ rig.pose.bones["DEF-hand.R"].head
                socket_errors.append((sword.matrix_world.to_translation() - hand).length)
            distance, contact = segment_contact(blade_segment(state["actors"][0]), blade_segment(state["actors"][1]))
            if phase == 0.5 and shot["action"] in IMPACTS:
                impact_distances.append(distance)
                local_raycasts = [blade_mesh_raycast(state["actors"][0], state["actors"][1]),
                                  blade_mesh_raycast(state["actors"][1], state["actors"][0])]
                state.setdefault("impact_mesh_raycasts", {})[shot["id"]] = local_raycasts
                impact_mesh_raycasts.extend(local_raycasts)
            local_sparks = max(local_sparks, sum(o.scale.x > 1e-4 for o in state["vfx"]["sparks"]))
            local_rubble = max(local_rubble, sum(o.scale.x > 1e-4 for o in state["vfx"]["rubble"]))
            trail_samples += sum(o.scale.x > 1e-4 for o in state["vfx"]["trails"])
        sparks_visible = max(sparks_visible, local_sparks)
        rubble_visible = max(rubble_visible, local_rubble)
        measurements.append({"shotId": shot["id"], "action": shot["action"], "animationName": name, "replayMeasured": True,
                             "footContactMaxResidualMeters": max(local_foot) if local_foot else None,
                             "actualBladeMeshRaycasts": local_raycasts,
                             "visibleSparkCount": local_sparks, "visibleDebrisCount": local_rubble})
    return {"schema": "videostudio.cinematic3d.qa.v1", "measured": True,
            "measurementMethod": "Baked NLA replay sampled at shared output phases; evaluated finite blade segments and three-dimensional Rigify wrist/foot world matrices",
            "impactBladeDistanceMeters": max(impact_distances) if impact_distances else None,
            "actualBladeMeshRaycastHit": all(c["hit"] for c in impact_mesh_raycasts) if impact_mesh_raycasts else None,
            "actualBladeMeshRaycastErrorMeters": max(c["errorMeters"] for c in impact_mesh_raycasts) if impact_mesh_raycasts and all(c["hit"] for c in impact_mesh_raycasts) else None,
            "bladeMeshRaycastMethod": "Both finite world blade rays into opposite evaluated katana mesh; measured hit-to-finite-axis-contact residual in metres",
            "groundedFootMaxErrorMeters": max(foot_errors) if foot_errors else None, "wristSocketMaxErrorMeters": max(socket_errors),
            "footMeasurement": "Full three-dimensional world-anchor residual; authored foot anchors are ankle joint positions with no hidden offset",
            "trailWindowOutputFrames": 6, "trailGeometrySamples": trail_samples,
            "contactDebrisCount": rubble_visible, "contactSparkCount": sparks_visible, "shotMeasurements": measurements,
            "portableTrailClock": "Six authored clip frames; native edited replay requires six-output-frame endpoint history",
            "pixelEvidence": "unavailable until independently rendered and inspected",
            "nativeDeviceEvidence": "unavailable", "originalImageReconstruction": False}


def author_timeline_for_render(state):
    """Bake the exact output clock, including actor holds and continuous camera."""
    for obj in animated_objects(state):
        for track in obj.animation_data.nla_tracks:
            track.mute = True
    begin_actions(state, "C3D_OUTPUT_TIMELINE")
    global_frame = 1
    fps = state["timeline"]["fps"]
    for shot in state["timeline"]["shots"]:
        history = [[], []]
        prepare_impact_origin(state, shot)
        # The offline library has fixed two-second VFX choreography. Duration
        # edits retime that choreography through the shared phase, just as the
        # phone does. Trail history still advances in actual output frames.
        vfx_shot = dict(shot, durationFrames=fps * 2, hitStopFrames=0) if state["library_only"] else shot
        for local_frame in range(shot["durationFrames"]):
            sample = sample_shot(shot, local_frame, fps)
            bpy.context.scene.frame_set(global_frame)
            pose_actors(state, shot, sample["phase"])
            set_camera(state["camera"], sample)
            _, contact = segment_contact(blade_segment(state["actors"][0]), blade_segment(state["actors"][1]))
            pose_vfx(state, vfx_shot, sample["phase"], local_frame, fps, history, contact)
            for actor in state["actors"]:
                key_transform(actor["root"], global_frame)
                key_transform(actor["sword"], global_frame)
                for bone in actor["deform"]:
                    pose = actor["rig"].pose.bones[bone.name]
                    for field in ("location", "rotation_quaternion", "scale"):
                        pose.keyframe_insert(field, frame=global_frame)
            key_transform(state["camera"], global_frame)
            state["camera"].data.keyframe_insert("lens", frame=global_frame)
            for group in state["vfx"].values():
                for obj in group:
                    key_transform(obj, global_frame)
            global_frame += 1
    for obj in animated_objects(state):
        for curve in obj.animation_data.action.fcurves:
            for key in curve.keyframe_points:
                key.interpolation = "CONSTANT"  # Cuts/holds have exact frame semantics.
    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = 1, global_frame - 1
    scene.frame_set(1)


def write_json(path, data):
    encoded = (json.dumps(data, separators=(",", ":"), sort_keys=True, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")
    if len(encoded) > 512 * 1024:
        raise ValueError("Emitted JSON exceeds the phone's 512 KiB metadata limit: " + str(path.name))
    path.write_bytes(encoded)


def export_bundle(state, output):
    output = Path(output)
    if output.exists() and any(output.iterdir()):
        raise ValueError("Bundle output directory must be new or empty; existing generations are immutable")
    output.mkdir(parents=True, exist_ok=True)
    timeline = state["timeline"]
    qa = measure_scene(state)
    if ((qa["impactBladeDistanceMeters"] is not None and qa["impactBladeDistanceMeters"] > 0.025)
            or qa["actualBladeMeshRaycastHit"] is False
            or (qa["actualBladeMeshRaycastErrorMeters"] is not None and qa["actualBladeMeshRaycastErrorMeters"] > 0.025)
            or (qa["groundedFootMaxErrorMeters"] is not None and qa["groundedFootMaxErrorMeters"] > 0.02)
            or qa["wristSocketMaxErrorMeters"] > 1e-5):
        raise RuntimeError("Measured Blender contact/ground/socket acceptance failed: " + json.dumps(qa))
    # Only scene objects are selected; inert metarigs/widgets never enter GLB.
    bpy.ops.object.select_all(action="DESELECT")
    for obj in bpy.context.scene.objects:
        if not obj.get("authoring_only") and not obj.name.startswith("WGT-") and obj.type != "LIGHT":
            obj.select_set(True)
    for obj in animated_objects(state):
        obj.animation_data.action = None
        for track in obj.animation_data.nla_tracks:
            track.mute = False
    bpy.ops.export_scene.gltf(filepath=str(output / "scene.glb"), export_format="GLB", use_selection=True,
                              export_yup=True, export_animations=True, export_animation_mode="NLA_TRACKS",
                              export_force_sampling=True, export_def_bones=True, export_cameras=True,
                              export_apply=False, export_extras=True, export_anim_slide_to_zero=True,
                              export_optimize_animation_size=True, export_optimize_animation_keep_anim_object=True,
                              export_optimize_disable_viewport=True, export_materials="EXPORT")
    path = output / "scene.glb"
    compact, compaction = compact_glb(path.read_bytes())
    path.write_bytes(compact)
    qa["glbCompaction"] = compaction
    print("CINEMATIC3D_GLB_METADATA " + json.dumps(compaction))
    if (output / "scene.glb").stat().st_size > MAX_GLB:
        (output / "scene.glb").unlink()
        raise ValueError("Full-scene GLB exceeds the phone's 16 MiB individual-asset limit")
    glb = (output / "scene.glb").read_bytes()
    if struct.unpack_from("<I", glb, 12)[0] > 512 * 1024:
        raise ValueError("Full-scene GLB JSON chunk exceeds the phone's 512 KiB metadata limit")
    author_timeline_for_render(state)
    bpy.ops.wm.save_as_mainfile(filepath=str(output / "scene.blend"))
    write_json(output / "timeline.json", timeline)
    write_json(output / "qa.json", qa)
    provenance = {"schema": "videostudio.cinematic3d.provenance.v1", "engine": "Blender " + bpy.app.version_string,
                  "rigging": "genuine Rigify basic_human generation; sixteen primary deform bones; deterministic skin weights; analytic world-space foot IK; baked portable channels",
                  "renderer": "Cycles CPU", "gpuObserved": False, "motionProvider": timeline["motionProvider"],
                  "denoiser": state["denoiser"],
                  "bladeCollision": "Finite world blade segments ray_cast into opposite evaluated mesh at exact impact pose; measured QA in qa.json",
                  "description": "Procedural carbon/ceramic mannequin demonstration in authored ruined-city geometry",
                  "originalImageReconstruction": False, "nativeRenderEvidence": False,
                  "sourceAnchors": [a for shot in timeline["shots"] for a in shot.get("sourceAnchors", [])]}
    write_json(output / "provenance.json", provenance)
    files = []
    for name, role in (("scene.glb", "scene"), ("scene.blend", "authoring"), ("timeline.json", "timeline"), ("qa.json", "qa"), ("provenance.json", "provenance")):
        data = (output / name).read_bytes()
        files.append({"path": name, "role": role, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    scenes = []
    for shot in timeline["shots"]:
        name = "C3D_" + shot["action"] if state["library_only"] else shot_clip_name(shot)
        scenes.append({"shotId": shot["id"], "action": shot["action"], "path": "scene.glb", "animationName": name,
                       "cameraName": "CinematicCamera", "clipDurationSeconds": state["clips"][name], "impactPhase": 0.5})
    manifest = {"schema": "videostudio.cinematic3d.bundle.v1", "timelineFile": "timeline.json", "files": files,
                "scenes": scenes, "generator": {"name": "VideoStudio Blender Cinematic3D", "version": "1", "blenderVersion": bpy.app.version_string,
                "actionLibrary": state["library_only"] and len({s["action"] for s in timeline["shots"]}) == len(timeline["shots"]),
                "trailGeometry": "unit_cube_v1", "trailWindowOutputFrames": 6, "bladeSocketGeometry": "katana_negative_z_0.16_1.06_m_v1"},
                "evidence": {"execution": "cloud_blender", "motionProvider": timeline["motionProvider"],
                             "originalImageReconstruction": False, "nativeDeviceRender": False, "geometryQA": "qa.json"}}
    write_json(output / "manifest.json", manifest)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeline", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--library-only", action="store_true")
    parser.add_argument("--render-frames", type=int, default=0)
    args = parser.parse_args(argv)
    raw = args.timeline.read_bytes()
    if len(raw) > 512 * 1024:
        raise ValueError("Timeline exceeds 512 KiB")
    timeline = validate_timeline(json.loads(raw))
    if args.render_frames < 0 or args.render_frames > 12000:
        raise ValueError("--render-frames must be 0..12000")
    state = build_scene(timeline, args.library_only)
    manifest = export_bundle(state, args.output)
    if args.render_frames:
        frames = args.output / "frames"
        frames.mkdir(exist_ok=True)
        scene = bpy.context.scene
        for frame in range(1, min(scene.frame_end, args.render_frames) + 1):
            scene.frame_set(frame)
            scene.render.filepath = str(frames / ("frame_%06d.png" % (frame - 1)))
            bpy.ops.render.render(write_still=True)
    print("CINEMATIC3D_BUNDLE " + json.dumps({"output": str(args.output), "sceneCount": len(manifest["scenes"]), "execution": "cloud_blender", "originalImageReconstruction": False}))


if __name__ == "__main__":
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    main(args)
