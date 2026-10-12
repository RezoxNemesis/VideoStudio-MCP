"""Independent actual-Blender checks for new skins/actions and selective packs."""
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("C3D_WORKBENCH_BLENDER_TEST") == "1", "Opt-in actual Blender model/action probe")
class WorkbenchBlenderTest(unittest.TestCase):
    def test_rest_skeleton_models_new_actions_and_selected_pack_are_real(self):
        blender = os.environ.get("C3D_BLENDER") or shutil.which("blender")
        self.assertTrue(blender, "Actual Blender is required")
        project = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="c3d-workbench-") as temporary:
            root = Path(temporary)
            probe = root / "probe.py"
            probe.write_text('''import bpy,json,sys,math
from pathlib import Path
from mathutils import Vector
sys.path.insert(0,sys.argv[-2])
from cinematic3d.blender_scene import build_scene,export_bundle,pose_actors,foot_targets
actions=['WAVE','WALK','PUNCH']
shots=[{'id':a.lower(),'action':a,'durationFrames':16,'hitStopFrames':0,'camera':{'mode':'wide','fovDegrees':48,'shakeIntensity':0},'vfx':{'sparkBurst':False,'slashTrail':False,'rubble':False},'motionStyle':{'preset':'anime','intensity':.8}} for a in actions]
timeline={'schema':'videostudio.cinematic3d.timeline.v1','name':'Workbench models','fps':24,'motionProvider':'procedural_mannequin','actors':{'black':{'preset':'stocky'},'white':{'preset':'chibi','material':{'color':[.2,.6,.8],'metallic':.2,'roughness':.4}}},'shots':shots}
state=build_scene(timeline,True,actions)
assert set(state['clips'])=={'C3D_'+a for a in actions}
heights=[a['rig'].data.bones['DEF-spine.006'].tail_local.z for a in state['actors']]
assert abs(heights[0]-heights[1])>.02
for actor in state['actors']:
 assert any(m.type=='ARMATURE' and m.object==actor['rig'] for m in actor['mesh'].modifiers)
 assert all(v.groups and abs(sum(g.weight for g in v.groups)-1)<1e-5 for v in actor['mesh'].data.vertices)
 for obj in (actor['root'],actor['rig'],actor['sword']):
  if obj.animation_data:
   obj.animation_data.action=None
   for track in obj.animation_data.nla_tracks:track.mute=True
scene=bpy.context.scene
points=[]
for phase in (.12,.5):
 pose_actors(state,shots[2],phase)
 rig=state['actors'][0]['rig'].evaluated_get(bpy.context.evaluated_depsgraph_get())
 points.append(rig.matrix_world@rig.pose.bones['DEF-hand.R'].head)
assert (points[1]-points[0]).length>.025, 'Blender must retain actual punch articulation'
roots=[]
for phase in (.1,.8):
 pose_actors(state,shots[1],phase)
 roots.append(state['actors'][0]['root'].location.copy())
assert (roots[1]-roots[0]).length>.2
style_matrices=[]
for style in ('grounded','anime','martial','cartoony'):
 shot=dict(shots[2]);shot['motionStyle']={'preset':style,'intensity':.8}
 pose_actors(state,shot,.35)
 rig=state['actors'][0]['rig']
 style_matrices.append(tuple(round(v,5) for row in rig.pose.bones['DEF-spine.003'].matrix for v in row)+tuple(round(v,5) for row in rig.pose.bones['DEF-upper_arm.L'].matrix for v in row))
assert len(set(style_matrices))==4, 'Style must alter evaluated bone transforms'
for action in ('RUN','DODGE','DANCE'):
 shot=dict(shots[1]);shot['action']=action;shot.pop('motionStyle',None)
 for index in range(25):
  phase=index/24
  pose_actors(state,shot,phase)
  for actor in state['actors']:
   rig=actor['rig']
   for side,target in foot_targets(actor,shot,phase).items():
    if target['stance']:
     ankle=rig.matrix_world@rig.pose.bones['DEF-foot.'+side].head
     assert (ankle-target['goal']).length<1e-5, action+' must retain its real planted ankle'
    hip=rig.pose.bones['DEF-thigh.'+side].head
    knee=rig.pose.bones['DEF-shin.'+side].head
    ankle=rig.pose.bones['DEF-foot.'+side].head
    assert abs((hip-knee).length-rig.data.bones['DEF-thigh.'+side].length)<1e-5
    assert abs((knee-ankle).length-rig.data.bones['DEF-shin.'+side].length)<1e-5
manifest=export_bundle(state,Path(sys.argv[-1]))
assert len(manifest['scenes'])==3 and manifest['generator']['actionLibrary']
print('C3D_WORKBENCH_PROBE '+json.dumps({'restHeights':heights,'punchTravelMeters':(points[1]-points[0]).length,'styles':len(set(style_matrices))}))
''')
            run = subprocess.run([blender, "-b", "--factory-startup", "-t", "2", "--python-exit-code", "23",
                                  "--python", str(probe), "--", str(project), str(root / "out")],
                                 cwd=project, capture_output=True, text=True, timeout=300)
            self.assertEqual(run.returncode, 0, (run.stdout + run.stderr)[-7000:])
            data = (root / "out" / "scene.glb").read_bytes()
            size = struct.unpack_from("<I", data, 12)[0]
            glb = json.loads(data[20:20+size])
            self.assertEqual({a["name"] for a in glb["animations"]}, {"C3D_WAVE", "C3D_WALK", "C3D_PUNCH"})
            self.assertGreaterEqual(len(glb["skins"]), 2)
            self.assertEqual({s["action"] for s in json.loads((root / "out" / "manifest.json").read_text())["scenes"]},
                             {"WAVE", "WALK", "PUNCH"})


if __name__ == "__main__": unittest.main()
