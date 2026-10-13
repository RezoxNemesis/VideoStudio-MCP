"""Numerical motion tests exercise real interpolation and root-relative IK."""
import math
import unittest

from cinematic3d.motion import (PRIMARY_JOINTS, foot_lock_ik, procedural_pose, sample_foot_contacts,
                                sample_pose, slerp, to_blender_quaternion, to_blender_vector,
                                to_gltf_quaternion, two_bone_ik)


def axis_quaternion(degrees):
    angle = math.radians(degrees) / 2
    return [math.cos(angle), 0, math.sin(angle), 0]


def actor(root=None, angle=0):
    return {"root": root or [0, 0, 0], "rotations": [axis_quaternion(angle) for _ in range(16)]}


class QuaternionTests(unittest.TestCase):
    def test_joint_order_matches_original_rig_profile(self):
        self.assertEqual(tuple(PRIMARY_JOINTS), ("pelvis", "chest", "neck", "head", "swordShoulder",
                         "swordElbow", "swordHand", "freeShoulder", "freeElbow", "freeHand",
                         "leadingHip", "leadingKnee", "leadingFoot", "trailingHip", "trailingKnee", "trailingFoot"))

    def test_shortest_path_handles_antipodes_without_collapse(self):
        for t in (0, 0.25, 0.5, 0.75, 1):
            result = slerp([1, 0, 0, 0], [-1, 0, 0, 0], t)
            self.assertAlmostEqual(abs(result[0]), 1)
            self.assertAlmostEqual(sum(x * x for x in result), 1)

    def test_slerp_interpolates_rotation_angle_and_normalizes_inputs(self):
        result = slerp([2, 0, 0, 0], [0, 0, 3, 0], 0.5)
        self.assertAlmostEqual(result[0], math.sqrt(0.5))
        self.assertAlmostEqual(result[2], math.sqrt(0.5))
        self.assertAlmostEqual(sum(x * x for x in result), 1)

    def test_nearly_parallel_quaternions_remain_unit_length(self):
        result = slerp(axis_quaternion(0), axis_quaternion(0.001), 0.3)
        self.assertAlmostEqual(sum(x * x for x in result), 1)
        self.assertGreater(result[2], 0)

    def test_invalid_quaternions_and_phases_fail(self):
        for value in ([0, 0, 0, 0], [1, 0, 0], [math.nan, 0, 0, 1], [True, 0, 0, 0]):
            with self.subTest(q=value), self.assertRaises(ValueError):
                slerp(value, [1, 0, 0, 0], 0.5)
        for phase in (-0.1, 1.1, math.inf, True):
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                slerp([1, 0, 0, 0], [1, 0, 0, 0], phase)

    def test_coordinate_conversion_preserves_handedness_and_order(self):
        self.assertEqual(to_blender_vector([1, 2, 3]), [1, -3, 2])
        self.assertEqual(to_blender_quaternion([0, 0, 1, 0]), [0, 0, 0, 1])
        self.assertEqual(to_gltf_quaternion([0.5, 0.5, 0.5, 0.5]), [0.5, 0.5, 0.5, 0.5])
        self.assertEqual(to_gltf_quaternion([1, 0, 0, 0]), [0, 0, 0, 1])


class PoseTests(unittest.TestCase):
    def test_authored_root_and_all_16_local_rotations_interpolate(self):
        shot = {"action": "AERIAL_FLIP", "poseKeyframes": [
            {"phase": 0, "actors": {"black": actor(), "white": actor([1, 0, 0])}},
            {"phase": 1, "actors": {"black": actor([2, 1, 0], 180), "white": actor([3, 0, 0], 90)}}]}
        result = sample_pose(shot, 0.5)
        self.assertEqual(result["black"]["root"], [1, 0.5, 0])
        self.assertEqual(result["white"]["root"], [2, 0, 0])
        self.assertEqual(len(result["black"]["rotations"]), 16)
        self.assertAlmostEqual(result["black"]["rotations"][6][2], math.sqrt(0.5))

    def test_flip_above_180_degrees_follows_authored_intermediate_keys(self):
        keys = [{"phase": phase, "actors": {"black": actor(angle=angle), "white": actor()}}
                for phase, angle in ((0, 0), (0.5, 135), (1, 270))]
        result = sample_pose({"action": "AERIAL_FLIP", "poseKeyframes": keys}, 0.75)
        rotation = result["black"]["rotations"][0]
        self.assertAlmostEqual(rotation[0], math.cos(math.radians(202.5) / 2))
        self.assertAlmostEqual(rotation[2], math.sin(math.radians(202.5) / 2))

    def test_pose_clamps_to_authored_key_range(self):
        keys = [{"phase": 0.25, "actors": {"black": actor([1, 0, 0]), "white": actor()}},
                {"phase": 0.75, "actors": {"black": actor([2, 0, 0]), "white": actor()}}]
        self.assertEqual(sample_pose({"action": "STANDOFF", "poseKeyframes": keys}, 0)["black"]["root"], [1, 0, 0])
        self.assertEqual(sample_pose({"action": "STANDOFF", "poseKeyframes": keys}, 1)["black"]["root"], [2, 0, 0])

    def test_procedural_actions_are_labelled_finite_and_distinct(self):
        actions = ("STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH", "KNOCKBACK",
                   "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH")
        for action in actions:
            with self.subTest(action=action):
                result = procedural_pose(action, 0.4)
                self.assertEqual(set(result), {"black", "white"})
                for pose in result.values():
                    self.assertEqual(len(pose["rotations"]), 16)
                    self.assertTrue(all(math.isfinite(x) for x in pose["root"]))
                    for quaternion in pose["rotations"]:
                        self.assertAlmostEqual(sum(x * x for x in quaternion), 1)
        self.assertNotEqual(procedural_pose("CHARGE", 0.1), procedural_pose("CHARGE", 0.8))
        self.assertNotEqual(procedural_pose("AERIAL_FLIP", 0.3), procedural_pose("STANDOFF", 0.3))

    def test_contact_anchors_do_not_move_with_root_and_end_is_exclusive(self):
        shot = {"footContacts": [{"actor": "black", "foot": "leading", "startPhase": 0.1,
                                  "endPhase": 0.8, "anchor": [1, 0, 2]}]}
        self.assertEqual(sample_foot_contacts(shot, 0.1), {"black": {"leading": [1, 0, 2]}, "white": {}})
        self.assertEqual(sample_foot_contacts(shot, 0.7), sample_foot_contacts(shot, 0.1))
        self.assertEqual(sample_foot_contacts(shot, 0.8), {"black": {}, "white": {}})


class FootIkTests(unittest.TestCase):
    def test_reachable_leg_hits_world_target_and_preserves_bone_lengths(self):
        root, target = [0, 1.4, 0], [0.5, 0, 0]
        result = two_bone_ik(root, target, 0.8, 0.8, [0, 0, 1])
        self.assertTrue(result["reachable"])
        for actual, expected in zip(result["foot"], target):
            self.assertAlmostEqual(actual, expected)
        self.assertAlmostEqual(math.dist(root, result["knee"]), 0.8)
        self.assertAlmostEqual(math.dist(result["knee"], result["foot"]), 0.8)
        self.assertGreater(result["knee"][2], 0)

    def test_foot_lock_survives_root_translation(self):
        anchor = [0, 0, 0]
        before = foot_lock_ik([0, 1.4, 0], anchor, 0.8, 0.8, [0, 0, 1])
        after = foot_lock_ik([0.4, 1.4, 0], anchor, 0.8, 0.8, [0, 0, 1])
        self.assertEqual(before["foot"], anchor)
        self.assertEqual(after["foot"], anchor)
        self.assertNotEqual(before["knee"], after["knee"])

    def test_unreachable_targets_are_flagged_and_preserve_lengths(self):
        result = two_bone_ik([0, 0, 0], [4, 0, 0], 1, 1, [0, 1, 0])
        self.assertFalse(result["reachable"])
        self.assertAlmostEqual(math.dist([0, 0, 0], result["knee"]), 1)
        self.assertAlmostEqual(math.dist(result["knee"], result["foot"]), 1)
        self.assertAlmostEqual(result["foot"][0], 2)

    def test_folded_leg_and_collinear_pole_have_finite_solutions(self):
        for target in ([0, 0, 0], [0, -1, 0]):
            result = two_bone_ik([0, 0, 0], target, 1, 1, [0, -2, 0])
            self.assertTrue(all(math.isfinite(x) for x in result["knee"] + result["foot"]))
            self.assertAlmostEqual(math.dist([0, 0, 0], result["knee"]), 1)
            self.assertAlmostEqual(math.dist(result["knee"], result["foot"]), 1)

    def test_invalid_lengths_and_nonfinite_targets_fail(self):
        for length in (0, -1, math.nan, True):
            with self.subTest(length=length), self.assertRaises(ValueError):
                two_bone_ik([0, 1, 0], [0, 0, 0], length, 1, [0, 0, 1])
        with self.assertRaises(ValueError):
            two_bone_ik([0, 1, 0], [math.inf, 0, 0], 1, 1, [0, 0, 1])

    def test_finite_inputs_that_overflow_world_geometry_fail(self):
        with self.assertRaises(ValueError):
            two_bone_ik([1e308, 0, 0], [1e308, 0, 0], 1, 1, [-1e308, 0, 0])


if __name__ == "__main__":
    unittest.main()
