"""Real numerical contracts for optional models, styles and reference guides."""
import copy
import json
import math
from pathlib import Path
import unittest

from cinematic3d import characters
from cinematic3d.motion import procedural_pose, sample_pose, NEW_ACTIONS, LEGACY_ACTIONS, map_reference_guide, support_root_drop, foot_lock_ik
from cinematic3d.timeline import validate_timeline


def timeline():
    return json.loads((Path(__file__).resolve().parents[2] / "cinematic3d/examples/action-library.json").read_text())


class WorkbenchMotionTest(unittest.TestCase):
    def test_generated_stance_drops_root_before_ik_instead_of_stretching_or_dragging_feet(self):
        # A straight resting leg cannot retain its floor contact after a body bob
        # or the old .6m/.14m dodge. These are actual annulus reach deficits.
        chains=[{"hip":[0,.97,0],"target":[0,.04,0],"upperLength":.43,"lowerLength":.43},
                {"hip":[0,.76,.6],"target":[0,.04,0],"upperLength":.43,"lowerLength":.43}]
        self.assertTrue(any(not foot_lock_ik(c["hip"],c["target"],c["upperLength"],c["lowerLength"],[0,0,1])["reachable"] for c in chains))
        drop=support_root_drop(chains)
        self.assertGreater(drop,.07)
        for chain in chains:
            hip=list(chain["hip"]);hip[1]-=drop
            solved=foot_lock_ik(hip,chain["target"],chain["upperLength"],chain["lowerLength"],[0,0,1])
            self.assertTrue(solved["reachable"])
            self.assertLess(math.dist(solved["foot"],chain["target"]),1e-9)
            self.assertAlmostEqual(math.dist(hip,solved["knee"]),chain["upperLength"])
            self.assertAlmostEqual(math.dist(solved["knee"],solved["foot"]),chain["lowerLength"])
        with self.assertRaises(ValueError):support_root_drop([dict(chains[0],hip=[1,.9,0])])

    def test_new_actions_are_finite_articulated_and_distinct(self):
        self.assertEqual(len(NEW_ACTIONS), 10)
        for action in NEW_ACTIONS:
            before, after = procedural_pose(action, .15), procedural_pose(action, .65)
            self.assertNotEqual(before, after, action)
            for pose in after.values():
                self.assertEqual(len(pose["rotations"]), 16)
                self.assertTrue(all(math.isfinite(x) for x in pose["root"]))
                for q in pose["rotations"]:
                    self.assertAlmostEqual(sum(x*x for x in q), 1)
        self.assertEqual(len(LEGACY_ACTIONS), 11)

    def test_styles_change_real_root_and_joint_sampling(self):
        samples = [sample_pose({"action": "RUN", "motionStyle": {"preset": style, "intensity": .8}}, .35)
                   for style in ("grounded", "anime", "martial", "cartoony")]
        self.assertEqual(len({json.dumps(p, sort_keys=True) for p in samples}), 4)
        neutral = {"action": "CLASH"}
        self.assertEqual(sample_pose(neutral, .4), procedural_pose("CLASH", .4))

    def test_styles_preserve_equivalent_authored_quaternion_signs(self):
        q=[math.cos(.4),math.sin(.4),0,0]
        style={"preset":"cartoony","intensity":.8}
        def shot(sign):
            actor={"root":[0,0,0],"rotations":[[sign*v for v in q] for _ in range(16)]}
            return {"action":"IDLE","motionStyle":style,"poseKeyframes":[{"phase":0,"actors":{"black":actor,"white":copy.deepcopy(actor)}}]}
        a,b=sample_pose(shot(1),.35),sample_pose(shot(-1),.35)
        for left,right in zip(a["black"]["rotations"],b["black"]["rotations"]):
            self.assertAlmostEqual(abs(sum(x*y for x,y in zip(left,right))),1)

    def test_optional_fields_preserve_absence_and_reject_unknown_and_unsafe(self):
        legacy = timeline()
        self.assertNotIn("actors", validate_timeline(legacy))
        edited = copy.deepcopy(legacy)
        edited["actors"] = {"black": {"preset": "chibi", "headScale": 1.1}, "white": {"preset": "robot"}}
        edited["shots"][0]["action"] = "WAVE"
        edited["shots"][0]["motionStyle"] = {"preset": "anime", "intensity": .8}
        self.assertEqual(validate_timeline(edited)["actors"], edited["actors"])
        for field, value in (("heightScale", 20), ("preset", "quadruped"), ("command", "python"), ("buildScale", True)):
            bad = copy.deepcopy(edited)
            bad["actors"]["black"][field] = value
            with self.assertRaises(ValueError):
                validate_timeline(bad)
        bad = copy.deepcopy(edited)
        bad["shots"][0]["motionStyle"]["intensity"] = float("nan")
        with self.assertRaises(ValueError): validate_timeline(bad)

    def test_five_presets_change_geometry_and_material_parameters(self):
        descriptors = [characters.morphology({"preset": preset}) for preset in characters.PRESETS]
        self.assertEqual(len(descriptors), 5)
        self.assertEqual(len({(p["height"], p["build"], p["head"]) for p in descriptors}), 5)
        for descriptor in descriptors:
            self.assertTrue(all(math.isfinite(descriptor[key]) and descriptor[key] > 0 for key in ("height", "build", "head")))
        self.assertEqual(characters.morphology(None)["height"], 1)

    def test_selected_action_pack_keeps_old_default_and_rejects_ambiguous_actions(self):
        self.assertEqual(characters.select_library_actions(), LEGACY_ACTIONS)
        self.assertEqual(characters.select_library_actions(["WAVE", "WALK"]), ("WAVE", "WALK"))
        for actions in ([], ["WAVE", "WAVE"], ["SHAPE_SHIFT"], ["WAVE", None]):
            with self.assertRaises(ValueError): characters.select_library_actions(actions)

    def test_reference_mapping_retains_binding_assumptions_and_uncertainty(self):
        points = [[.2 + i*.01, .2 + i*.02] for i in range(16)]
        request = {"schema": "videostudio.cinematic3d.reference-guide-request.v1", "sourceSha256": "a"*64,
                   "profileSha256": "b"*64, "joints": points, "imageWidth": 720, "imageHeight": 1280,
                   "heightMeters": 1.7, "depthMeters": [0]*16, "uncertaintyMeters": [.2]*16}
        result = map_reference_guide(request)
        self.assertEqual(result["sourceSha256"], request["sourceSha256"])
        self.assertEqual(result["profileSha256"], request["profileSha256"])
        self.assertEqual(len(result["joints3d"]), 16)
        self.assertFalse(result["evidence"]["originalImageReconstruction"])
        self.assertEqual(result["uncertaintyMeters"], [.2]*16)
        with self.assertRaises(ValueError): map_reference_guide(dict(request, depthMeters=[0]*15))
        collapsed=copy.deepcopy(request);collapsed["joints"][5]=collapsed["joints"][4]
        with self.assertRaises(ValueError): map_reference_guide(collapsed)


if __name__ == "__main__": unittest.main()
