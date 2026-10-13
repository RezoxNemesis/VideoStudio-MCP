"""Pure sidecar admission tests; the opt-in probe exercises real Blender passes."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from cinematic3d.conditioning import (FILENAMES, PRIMARY_JOINTS, ROLES, SCHEMA,
                                     frame_directory, to_yup, verify_conditioning, world_matrix_yup)


class ConditioningTest(unittest.TestCase):
    def fixture(self, root):
        directory = root / frame_directory(0)
        directory.mkdir(parents=True)
        pose = {"outputFrame": 0, "blenderFrame": 1, "jointOrder": list(PRIMARY_JOINTS),
                "actors": {name: {"pose3d": [[0, 0, 0]] * 16, "pose2d": [{"xy": [.5, .5]}] * 16}
                           for name in ("black", "white")}}
        files = []
        for role in ROLES:
            path = directory / FILENAMES[role]
            if role == "pose":
                path.write_text(json.dumps(pose))
            elif role == "depth":
                path.write_bytes(b"\x76\x2f\x31\x01fixture")
            else:
                path.write_bytes(b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 32, 24))
            data = path.read_bytes()
            files.append({"role": role, "path": path.relative_to(root).as_posix(), "bytes": len(data),
                          "sha256": hashlib.sha256(data).hexdigest()})
        manifest = {"schema": SCHEMA, "execution": "cloud_blender", "nativeRendered": False,
                    "originalImageReconstruction": False, "frameCount": 1, "width": 32, "height": 24,
                    "fps": 30, "jointOrder": list(PRIMARY_JOINTS),
                    "frames": [{"outputFrame": 0, "blenderFrame": 1, "files": files}]}
        (root / "manifest.json").write_text(json.dumps(manifest))
        return manifest

    def test_basis_conversion_preserves_camera_local_axes(self):
        matrix = [[1, 0, 0, 2], [0, 1, 0, 3], [0, 0, 1, 4], [0, 0, 0, 1]]
        self.assertEqual(to_yup([2, 3, 4]), [2, 4, -3])
        self.assertEqual(world_matrix_yup(matrix), [[1, 0, 0, 2], [0, 1, 0, 4], [0, 0, 1, -3], [0, 0, 0, 1]])
        self.assertEqual(world_matrix_yup(matrix, camera=True)[1], [0, 0, 1, 4])
        for index in (-1, 1200, True):
            with self.assertRaises(ValueError):
                frame_directory(index)

    def test_ordered_sidecar_receipt_and_changed_bytes(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root)
            receipt = verify_conditioning(root)
            self.assertFalse(receipt["nativeRendered"])
            self.assertEqual(receipt["frameCount"], 1)
            self.assertEqual(receipt["sha256"], hashlib.sha256((root / "manifest.json").read_bytes()).hexdigest())
            (root / frame_directory(0) / "depth.exr").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                verify_conditioning(root)

    def test_rejects_unbound_files_traversal_symlink_and_clock_changes(self):
        for mutation in ("unbound", "traversal", "symlink", "clock", "dimensions", "labels", "duplicate"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                manifest = self.fixture(root)
                if mutation == "unbound":
                    (root / "unbound.json").write_text("{}")
                elif mutation == "traversal":
                    manifest["frames"][0]["files"][0]["path"] = "../pose.json"
                elif mutation == "symlink":
                    path = root / frame_directory(0) / "depth.exr"
                    external = root / "outside.exr"
                    path.rename(external)
                    path.symlink_to(external)
                elif mutation == "clock":
                    manifest["frames"][0]["blenderFrame"] = 2
                elif mutation == "dimensions":
                    manifest["width"] = 33
                elif mutation == "labels":
                    manifest["nativeRendered"] = True
                else:
                    manifest["frames"][0]["files"].append(manifest["frames"][0]["files"][0])
                (root / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    verify_conditioning(root)


@unittest.skipUnless(os.environ.get("C3D_CONDITIONING_BLENDER_TEST") == "1", "Opt-in actual Blender render probe")
class ConditioningBlenderTest(unittest.TestCase):
    def test_actual_evaluated_poses_camera_and_rendered_passes(self):
        blender = os.environ.get("C3D_BLENDER") or shutil.which("blender")
        self.assertTrue(blender, "Actual Blender is required")
        project = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="c3d-conditioning-") as name:
            root = Path(name)
            script = root / "probe.py"
            script.write_text('''import bpy,json,sys,math
from pathlib import Path
sys.path.insert(0,sys.argv[-2])
from cinematic3d.blender_scene import build_scene,export_bundle
from cinematic3d.conditioning import export_conditioning
from cinematic3d.timeline import validate_timeline
from bpy_extras.object_utils import world_to_camera_view
timeline=validate_timeline({'schema':'videostudio.cinematic3d.timeline.v1','name':'Conditioning probe','fps':24,'motionProvider':'procedural_mannequin','shots':[{'id':'clash','action':'CLASH','durationFrames':8,'hitStopFrames':2,'camera':{'mode':'orbit','fovDegrees':55,'shakeIntensity':0},'vfx':{'sparkBurst':False,'slashTrail':False,'rubble':False}}]})
state=build_scene(timeline)
output=Path(sys.argv[-1])
export_bundle(state,output)
scene=bpy.context.scene
scene.render.resolution_x=96;scene.render.resolution_y=64;scene.cycles.samples=1
original_dither_intensity=scene.render.dither_intensity
receipt=export_conditioning(state,output,8)
assert scene.render.dither_intensity==original_dither_intensity, 'Conditioning changed original scene dither'
poses=[json.loads((output/'conditioning'/('frames/frame_%06d/pose.json'%i)).read_text()) for i in range(8)]
assert poses[3]['clock']['hitStop'] and poses[4]['clock']['hitStop']
for actor in ('black','white'):
 assert poses[3]['actors'][actor]['pose3d']==poses[4]['actors'][actor]['pose3d']
 assert poses[3]['actors'][actor]['rootWorldMatrix']==poses[4]['actors'][actor]['rootWorldMatrix']
assert poses[3]['camera']['worldFromCamera']!=poses[4]['camera']['worldFromCamera']
scene.frame_set(8)
rig=state['actors'][0]['rig'].evaluated_get(bpy.context.evaluated_depsgraph_get())
point=rig.matrix_world@rig.pose.bones[rig['primary_bones'][0]].head
projected=world_to_camera_view(scene,state['camera'],point)
assert max(abs(a-b) for a,b in zip(poses[7]['actors']['black']['pose2d'][0]['xy'],[projected.x,1-projected.y]))<1e-6
for role in ('actors','props','normals'):
 image=bpy.data.images.load(str(output/'conditioning'/('frames/frame_000000/'+role+'.png')),check_existing=False)
 pixels=list(image.pixels)
 if role in ('actors','props'):
  unique=sorted({round(v,9) for v in pixels})
  residual=max(min(abs(v),abs(v-1)) for v in pixels)
  assert all(min(abs(v),abs(v-1))<1e-5 for v in pixels), {'role':role,'uniquePixels':unique,'maxBinaryResidual':residual,'loadedColorSpace':image.colorspace_settings.name,'originalDitherIntensity':scene.render.dither_intensity}
  assert any(pixels[i]>.5 for i in range(0,len(pixels),4))
  assert any(pixels[i]>.5 for i in range(1,len(pixels),4))
 else: assert any(.01<v<.99 for v in pixels)
 bpy.data.images.remove(image)
depth=bpy.data.images.load(str(output/'conditioning/frames/frame_000000/depth.exr'),check_existing=False)
distances=[v for v in depth.pixels if math.isfinite(v) and .05<v<250]
assert distances and max(distances)-min(distances)>.1, 'Depth must contain real nonconstant metre distances'
bpy.data.images.remove(depth)
print('C3D_CONDITIONING_PROBE '+json.dumps(receipt))
''')
            run = subprocess.run([blender, "-b", "--factory-startup", "-t", "2", "--python-exit-code", "23",
                                  "--python", str(script), "--", str(project), str(root / "out")],
                                 cwd=project, capture_output=True, text=True, timeout=300)
            self.assertEqual(run.returncode, 0, (run.stdout + run.stderr)[-7000:])
            self.assertEqual(verify_conditioning(root / "out" / "conditioning")["frameCount"], 8)
