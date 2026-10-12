"""Actual Blender/glTF contract checks. Run with unittest; no mocked Blender.

The fixture deliberately samples evaluated mesh and bone matrices. Merely writing
files, JSON self-reports, or an unweighted mannequin cannot satisfy these checks.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTIONS = ("STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH",
           "KNOCKBACK", "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH")


def read_glb(path):
    data = path.read_bytes()
    magic, version, length = struct.unpack_from("<III", data)
    if magic != 0x46546c67 or version != 2 or length != len(data):
        raise AssertionError("Invalid GLB header")
    json_len, kind = struct.unpack_from("<II", data, 12)
    if kind != 0x4e4f534a:
        raise AssertionError("First GLB chunk is not JSON")
    return json.loads(data[20:20 + json_len]), data


class BlenderContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="c3d-blender-contract-")
        cls.directory = Path(cls.temp.name)
        timeline = {"schema": "videostudio.cinematic3d.timeline.v1", "name": "Blender contract",
                    "fps": 24, "motionProvider": "procedural_mannequin", "shots": [
            {"id": "impact", "action": "CLASH", "durationFrames": 32, "hitStopFrames": 3,
             "camera": {"mode": "wide", "fovDegrees": 48, "shakeIntensity": 0.5},
             "vfx": {"sparkBurst": True, "slashTrail": True, "rubble": True}}]}
        cls.timeline_file = cls.directory / "input.json"
        cls.timeline_file.write_text(json.dumps(timeline), encoding="utf-8")
        script = ROOT / "cinematic3d" / "blender_scene.py"
        if not script.is_file():
            raise AssertionError("Missing Blender production engine cinematic3d/blender_scene.py")
        blender = os.environ.get("C3D_BLENDER") or shutil.which("blender")
        if not blender:
            raise AssertionError("Actual Blender is required for the Blender contract fixture")
        run = subprocess.run([blender, "-b", "--factory-startup", "-t", "2", "--python-exit-code", "23", "--python", str(script),
                              "--", "--timeline", str(cls.timeline_file), "--output", str(cls.directory / "out"),
                              "--library-only"], cwd=ROOT, capture_output=True, text=True, timeout=300)
        cls.run_log = (run.stdout + run.stderr)[-131072:]
        (cls.directory / "blender-run.log").write_text(cls.run_log, encoding="utf-8")
        if os.environ.get("C3D_BLENDER_LOG"):
            Path(os.environ["C3D_BLENDER_LOG"]).write_text(cls.run_log, encoding="utf-8")
        if run.returncode:
            raise AssertionError("Blender production execution failed:\n" + (run.stdout + run.stderr)[-7000:])
        cls.output = cls.directory / "out"
        if not (cls.output / "manifest.json").is_file():
            raise AssertionError("Blender did not publish a bundle manifest:\n" + (run.stdout + run.stderr)[-7000:])
        cls.manifest = json.loads((cls.output / "manifest.json").read_text())
        cls.glb, cls.bytes = read_glb(cls.output / "scene.glb")
        # A second Blender process independently measures authoring scene state.
        probe = cls.directory / "probe.py"
        probe.write_text('''import bpy,json,sys,math
from pathlib import Path
scene=bpy.context.scene
import _cycles
rigs=[o for o in bpy.data.objects if o.type=='ARMATURE' and o.get('cinematic_actor')]
result={'actors':[],'cameras':[o.name for o in bpy.data.objects if o.type=='CAMERA'],'denoising':scene.cycles.use_denoising,'oidnAvailable':bool(_cycles.with_openimagedenoise)}
for rig in rigs:
 meshes=[o for o in bpy.data.objects if o.type=='MESH' and any(m.type=='ARMATURE' and m.object==rig for m in o.modifiers)]
 weighted=sum(sum(1 for v in m.data.vertices if any(g.weight>0 for g in v.groups)) for m in meshes)
 sockets=[o for o in bpy.data.objects if o.name==rig.get('katana_object')]
 result['actors'].append({'name':rig.name,'rigify':bool(rig.get('rigify_rig_id')),'primary':list(rig.get('primary_joints',[])),'weighted':weighted,'bones':len(rig.data.bones),'swordBone':sockets[0].parent_bone if sockets else None,'swordParent':sockets[0].parent.name if sockets and sockets[0].parent else None})
result['animated_objects']=sum(1 for o in bpy.data.objects if o.animation_data and (o.animation_data.action or o.animation_data.nla_tracks))
result['environment_meshes']=sum(1 for o in bpy.data.objects if o.type=='MESH' and o.get('environment'))
from mathutils import Vector,Matrix
socket_error=0.0
foot_error=0.0
for frame in range(scene.frame_start,scene.frame_end+1):
 scene.frame_set(frame)
 for rig in rigs:
  sword=bpy.data.objects[rig['katana_object']].evaluated_get(bpy.context.evaluated_depsgraph_get())
  hand=rig.matrix_world@rig.pose.bones['DEF-hand.R'].head
  socket_error=max(socket_error,(sword.matrix_world.to_translation()-hand).length)
  for side in ('L','R'):
   bone=rig.pose.bones['DEF-foot.'+side]
   ankle=rig.matrix_world@bone.head
   foot_error=max(foot_error,abs(ankle.z-bone.bone.head_local.z))
scene.frame_set((scene.frame_start+scene.frame_end)//2)
segments=[]
for rig in rigs:
 sword=bpy.data.objects[rig['katana_object']].evaluated_get(bpy.context.evaluated_depsgraph_get())
 segments.append((sword.matrix_world@Vector(sword['blade_start']),sword.matrix_world@Vector(sword['blade_end'])))
p,q=segments[0];r,s=segments[1]
u,v,w=q-p,s-r,p-r
a,b,c,d,e=u.dot(u),u.dot(v),v.dot(v),u.dot(w),v.dot(w)
den=a*c-b*b
t=max(0,min(1,(b*e-c*d)/den))
k=max(0,min(1,(a*e-b*d)/den))
result['independentBladeDistance']=(p+u*t-r-v*k).length
# Cast the real finite blade segment into the other evaluated katana mesh.
# Matrix conversion includes the local segment distance, so a hit beyond the
# source blade cannot pass. This does not invoke the production collision code.
other=bpy.data.objects[rigs[1]['katana_object']].evaluated_get(bpy.context.evaluated_depsgraph_get())
inverse=other.matrix_world.inverted()
local_start,local_end=inverse@p,inverse@q
local_delta=local_end-local_start
hit,location,normal,polygon=other.ray_cast(local_start,local_delta.normalized(),distance=local_delta.length)
result['independentBladeMeshRaycastHit']=bool(hit and polygon>=0)
result['independentBladeMeshRaycastError']=(other.matrix_world@location-(p+u*t+r+v*k)/2).length if hit else None
result['independentSocketError']=socket_error
result['independentFootError']=foot_error
# At output index18 the held32frame timeline maps to phase .5625. Cloud VFX
# must use the same two-second authored clip clock as native library replay.
scene.frame_set(19)
spark=bpy.data.objects['C3D_Spark_00']
cloud_spark=spark.matrix_world.to_translation().copy()
cloud_fade=spark.scale.x
for obj in bpy.data.objects:
 if obj.animation_data:
  obj.animation_data.action=None
  for track in obj.animation_data.nla_tracks:track.mute=track.name!='C3D_CLASH'
scene.frame_set(28)
result['retimedSparkPositionResidual']=(spark.matrix_world.to_translation()-cloud_spark).length
result['retimedSparkFadeResidual']=abs(spark.scale.x-cloud_fade)
# Restore the cloud timeline for independent evaluated skin deformation.
for obj in bpy.data.objects:
 if obj.animation_data:
  for track in obj.animation_data.nla_tracks:track.mute=True
  obj.animation_data.action=bpy.data.actions.get('C3D_OUTPUT_TIMELINE__'+obj.name)
deformations=[]
for rig in rigs:
 meshes=[o for o in bpy.data.objects if o.type=='MESH' and any(m.type=='ARMATURE' and m.object==rig for m in o.modifiers)]
 mesh=meshes[0]
 snapshots=[]
 for frame in (scene.frame_start,(scene.frame_start+scene.frame_end)//2):
  scene.frame_set(frame)
  evaluated=mesh.evaluated_get(bpy.context.evaluated_depsgraph_get())
  posed=evaluated.to_mesh()
  snapshots.append([v.co.copy() for v in posed.vertices])
  evaluated.to_mesh_clear()
 deformations.append(max((a-b).length for a,b in zip(*snapshots)))
result['evaluatedSkinDeformationMeters']=min(deformations)
# Independently replay the CHARGE library track: planted world feet must stay
# while roots advance. Height-only assertions would miss root-dragged feet.
for obj in bpy.data.objects:
 if obj.animation_data:
  obj.animation_data.action=None
  for track in obj.animation_data.nla_tracks:
   track.mute=track.name!='C3D_CHARGE'
charge=[]
for rig in rigs:
 feet=[];roots=[]
 for frame in range(1,11):
  scene.frame_set(frame)
  feet.append((rig.matrix_world@rig.pose.bones['DEF-foot.L'].head).copy())
  roots.append(rig.parent.matrix_world.to_translation().copy())
 charge.append({'stanceDrift':max((p-feet[0]).length for p in feet),'rootTravel':(roots[-1]-roots[0]).length})
result['chargeStance']=charge
Path(sys.argv[-1]).write_text(json.dumps(result))
''', encoding="utf-8")
        inspected = subprocess.run([blender, "-b", str(cls.output / "scene.blend"), "-t", "2", "--python-exit-code", "23", "--python", str(probe), "--", str(cls.directory / "probe.json")], capture_output=True, text=True, timeout=90)
        if inspected.returncode:
            raise AssertionError("Independent Blender probe failed:\n" + (inspected.stdout + inspected.stderr)[-3000:])
        cls.probe = json.loads((cls.directory / "probe.json").read_text())
        cls.qa = json.loads((cls.output / "qa.json").read_text())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_real_rigify_weighted_actors_and_rigid_wrist_katanas(self):
        self.assertEqual(len(self.probe["actors"]), 2)
        for actor in self.probe["actors"]:
            self.assertTrue(actor["rigify"])
            self.assertEqual(len(actor["primary"]), 16)
            self.assertGreater(actor["weighted"], 500)
            self.assertGreater(actor["bones"], 16)
            self.assertEqual(actor["swordBone"], "DEF-hand.R")
            self.assertEqual(actor["swordParent"], actor["name"])

    def test_glb_encodes_two_skins_exact_clips_camera_and_full_world_environment(self):
        self.assertLessEqual(len(self.bytes), 16 * 1024 * 1024)
        self.assertLessEqual(struct.unpack_from("<I", self.bytes, 12)[0], 512 * 1024)
        self.assertGreaterEqual(len(self.glb.get("skins", [])), 2)
        names = {a["name"] for a in self.glb.get("animations", [])}
        self.assertEqual(names, {"C3D_" + a for a in ACTIONS})
        self.assertEqual(self.probe["cameras"], ["CinematicCamera"])
        self.assertTrue(any(n.get("name") == "CinematicCamera" and "camera" in n for n in self.glb["nodes"]))
        self.assertAlmostEqual(self.glb["cameras"][0]["perspective"]["yfov"], 48 * 3.141592653589793 / 180, places=5)
        self.assertGreater(self.probe["environment_meshes"], 15)
        self.assertTrue(all("uri" not in b for b in self.glb.get("buffers", [])))
        for name in ("BlackKatana", "WhiteKatana"):
            node = next(n for n in self.glb["nodes"] if n.get("name") == name)
            self.assertEqual(node["extras"]["gltf_blade_start"], [0, 0, -0.16])
            self.assertEqual(node["extras"]["gltf_blade_end"], [0, 0, -1.06])
            positions = [self.glb["accessors"][p["attributes"]["POSITION"]] for p in self.glb["meshes"][node["mesh"]]["primitives"]]
            self.assertLessEqual(min(a["min"][2] for a in positions), -1.06)
            self.assertLessEqual(max(abs(a["max"][1]) for a in positions), 0.1)
        for animation in self.glb["animations"]:
            targets = {c["target"]["node"] for c in animation["channels"]}
            node_names = {self.glb["nodes"][n].get("name") for n in targets}
            self.assertIn("CinematicCamera", node_names)
            self.assertIn("BlackRoot", node_names)
            self.assertIn("WhiteRoot", node_names)

    def test_bundle_hashes_evidence_and_named_library_bindings(self):
        self.assertEqual(self.manifest["schema"], "videostudio.cinematic3d.bundle.v1")
        for entry in self.manifest["files"]:
            data = (self.output / entry["path"]).read_bytes()
            self.assertEqual(entry["bytes"], len(data))
            self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(self.manifest["timelineFile"], "timeline.json")
        for scene in self.manifest["scenes"]:
            self.assertEqual(scene["path"], "scene.glb")
            self.assertEqual(scene["animationName"], "C3D_" + scene["action"])
            self.assertEqual(scene["cameraName"], "CinematicCamera")
            self.assertGreater(scene["clipDurationSeconds"], 0)
        self.assertEqual(self.manifest["evidence"]["motionProvider"], "procedural_mannequin")
        self.assertFalse(self.manifest["evidence"]["originalImageReconstruction"])

    def test_measured_contact_foot_socket_and_six_output_frame_trails(self):
        self.assertTrue(self.qa["measured"])
        self.assertLessEqual(self.qa["impactBladeDistanceMeters"], 0.025)
        self.assertIn("actualBladeMeshRaycastHit", self.qa, "Actual evaluated blade-mesh raycasting is not implemented")
        self.assertTrue(self.qa["actualBladeMeshRaycastHit"])
        self.assertLessEqual(self.qa["actualBladeMeshRaycastErrorMeters"], 0.025)
        self.assertLessEqual(self.qa["groundedFootMaxErrorMeters"], 0.02)
        self.assertLessEqual(self.qa["wristSocketMaxErrorMeters"], 1e-5)
        self.assertEqual(self.qa["trailWindowOutputFrames"], 6)
        self.assertGreater(self.qa["trailGeometrySamples"], 6)
        self.assertGreater(self.qa["contactDebrisCount"], 4)
        self.assertGreater(self.qa["contactSparkCount"], 4)
        self.assertEqual({s["action"] for s in self.qa["shotMeasurements"]}, set(ACTIONS))
        self.assertTrue(all(s["replayMeasured"] for s in self.qa["shotMeasurements"]))
        self.assertEqual(self.probe["denoising"], self.probe["oidnAvailable"])
        self.assertLessEqual(self.probe["independentBladeDistance"], 0.025)
        self.assertTrue(self.probe["independentBladeMeshRaycastHit"])
        self.assertLessEqual(self.probe["independentBladeMeshRaycastError"], 0.025)
        self.assertLessEqual(self.probe["independentFootError"], 0.02)
        self.assertLessEqual(self.probe["independentSocketError"], 1e-5)
        self.assertLessEqual(self.probe["retimedSparkPositionResidual"], 1e-5)
        self.assertLessEqual(self.probe["retimedSparkFadeResidual"], 1e-5)
        self.assertGreater(self.probe["evaluatedSkinDeformationMeters"], 0.05)
        for gait in self.probe["chargeStance"]:
            self.assertGreater(gait["rootTravel"], 0.2)
            self.assertLessEqual(gait["stanceDrift"], 0.02)


class BlenderAuthoredContractTest(unittest.TestCase):
    """Nonlibrary authored roots/anchors and truthful provider failure gates."""

    def test_authored_scene_and_provider_admission(self):
        blender = os.environ.get("C3D_BLENDER") or shutil.which("blender")
        self.assertTrue(blender, "Actual Blender is required")
        with tempfile.TemporaryDirectory(prefix="c3d-authored-contract-") as name:
            directory = Path(name)
            identities = [[1, 0, 0, 0] for _ in range(16)]
            poses = []
            for phase in (0, 1):
                poses.append({"phase": phase, "actors": {"black": {"root": [-1.15 + 0.07 * phase, -0.055, 0], "rotations": identities},
                                                          "white": {"root": [1.15 - 0.07 * phase, -0.055, 0], "rotations": identities}}})
            shot_id = "authored_" + "x" * 80
            shot = {"id": shot_id, "action": "STANDOFF", "durationFrames": 9, "hitStopFrames": 0,
                    "camera": {"mode": "top_down", "fovDegrees": 48, "shakeIntensity": 0,
                               "keyframes": [{"phase": 0, "position": [0.2, 6.0, 0.3], "target": [0.0, 0.8, 0.0], "up": [0, 0, -1]},
                                             {"phase": 1, "position": [1.1, 5.2, -0.4], "target": [0.25, 0.9, 0.1], "up": [0, 0, -1]}]},
                    "vfx": {"sparkBurst": False, "slashTrail": False, "rubble": False}, "poseKeyframes": poses,
                    "footContacts": [{"actor": "black", "foot": "leading", "startPhase": 0, "endPhase": 1,
                                      "anchor": [-1.0618, 0.07668, -0.01458]}]}
            timeline = {"schema": "videostudio.cinematic3d.timeline.v1", "name": "Authored fixture", "fps": 24,
                        "motionProvider": "authored_3d", "shots": [shot]}
            (directory / "input.json").write_text(json.dumps(timeline), encoding="utf-8")
            probe = directory / "produce.py"
            probe.write_text('''import sys,json,copy
from pathlib import Path
sys.path.insert(0,sys.argv[-3])
import bpy
from cinematic3d.blender_scene import build_scene,export_bundle,shot_clip_name,pose_actors,default_shot,animated_objects
from cinematic3d.timeline import validate_timeline,sample_shot
timeline=validate_timeline(json.loads(Path(sys.argv[-2]).read_text()))
state=build_scene(timeline,False)
manifest=export_bundle(state,Path(sys.argv[-1]))
result={'rejected':[],'roots':[],'cameraQuaternion':list(state['camera'].rotation_quaternion),'cameraSamples':[]}
for frame in (1,9):
 bpy.context.scene.frame_set(frame)
 result['roots'].append([list(a['root'].matrix_world.to_translation()) for a in state['actors']])
 shared=sample_shot(timeline['shots'][0],frame-1,timeline['fps'])['camera']['position']
 result['cameraSamples'].append({'frame':frame,'actual':list(state['camera'].matrix_world.to_translation()),'expected':[shared[0],-shared[2],shared[1]]})
missing=copy.deepcopy(timeline)
del missing['shots'][0]['poseKeyframes']
inverse=copy.deepcopy(timeline)
inverse['motionProvider']='procedural_mannequin'
for label,data,library in [('missing-authored-poses',missing,False),('authored-library',timeline,True),('procedural-labelled-authored',inverse,False)]:
 try: build_scene(data,library)
 except ValueError: result['rejected'].append(label)
 else: raise AssertionError('Provider admission silently accepted '+label)
import hashlib
longid=timeline['shots'][0]['id']
result['clipNamespacesDistinct']=shot_clip_name({'id':longid})!=shot_clip_name({'id':hashlib.sha256(longid.encode()).hexdigest()[:32]})
for obj in animated_objects(state):
 obj.animation_data.action=None
 for track in obj.animation_data.nla_tracks:track.mute=True
charge=default_shot('CHARGE',24)
anchor=[-2.13,0.07668,-0.0882]
charge['footContacts']=[{'actor':'black','foot':'leading','startPhase':0,'endPhase':0.15,'anchor':anchor}]
pose_actors(state,charge,0.1)
black=state['actors'][0]['rig']
point=black.matrix_world@black.pose.bones['DEF-foot.L'].head
result['explicitChargeAnchorResidual']=((point.x-anchor[0])**2+(point.y+anchor[2])**2+(point.z-anchor[1])**2)**0.5
Path(sys.argv[-1],'probe.json').write_text(json.dumps(result))
''', encoding="utf-8")
            result = subprocess.run([blender, "-b", "--factory-startup", "-t", "2", "--python-exit-code", "23", "--python", str(probe),
                                     "--", str(ROOT), str(directory / "input.json"), str(directory / "out")], capture_output=True, text=True, timeout=120)
            self.assertEqual(result.returncode, 0, (result.stdout + result.stderr)[-7000:])
            output = directory / "out"
            manifest = json.loads((output / "manifest.json").read_text())
            qa = json.loads((output / "qa.json").read_text())
            measured = json.loads((output / "probe.json").read_text())
            glb, _ = read_glb(output / "scene.glb")
            clip_name = "C3D_SHOT_H_" + hashlib.sha256(shot_id.encode("ascii")).hexdigest()[:32]
            self.assertEqual({a["name"] for a in glb["animations"]}, {clip_name})
            self.assertEqual(manifest["scenes"][0]["animationName"], clip_name)
            self.assertFalse(manifest["generator"]["actionLibrary"])
            self.assertEqual(manifest["evidence"]["motionProvider"], "authored_3d")
            self.assertEqual(measured["rejected"], ["missing-authored-poses", "authored-library", "procedural-labelled-authored"])
            self.assertTrue(measured["clipNamespacesDistinct"])
            self.assertLessEqual(measured["explicitChargeAnchorResidual"], 1e-5)
            self.assertAlmostEqual(measured["roots"][0][0][0], -1.15, places=5)
            self.assertAlmostEqual(measured["roots"][1][0][0], -1.15 + 0.07 * 8 / 9, places=5)
            self.assertTrue(all(abs(x) <= 1 for x in measured["cameraQuaternion"]))
            self.assertEqual([sample["frame"] for sample in measured["cameraSamples"]], [1, 9])
            for sample in measured["cameraSamples"]:
                for actual, expected in zip(sample["actual"], sample["expected"]):
                    self.assertAlmostEqual(actual, expected, places=5)
            self.assertLessEqual(qa["groundedFootMaxErrorMeters"], 0.002)
            self.assertEqual(round(manifest["scenes"][0]["clipDurationSeconds"] * 24) % 2, 0)
            self.assertIsNone(qa["impactBladeDistanceMeters"])
            self.assertEqual(qa["contactDebrisCount"], 0)
            self.assertEqual(qa["contactSparkCount"], 0)
            self.assertLessEqual((output / "timeline.json").stat().st_size, 512 * 1024)


if __name__ == "__main__":
    unittest.main()
