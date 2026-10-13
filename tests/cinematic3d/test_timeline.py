"""Contract tests for strict timelines and the shared output-frame clock."""
import copy
import json
import math
from pathlib import Path
import random
import unittest

from cinematic3d.timeline import compile_timeline, sample_shot, validate_timeline


def shot(**changes):
    value = {
        "id": "shot_001", "action": "CLASH", "durationFrames": 30,
        "hitStopFrames": 3,
        "camera": {"mode": "wide", "fovDegrees": 55, "shakeIntensity": 0.5},
        "vfx": {"sparkBurst": True, "slashTrail": True, "rubble": False},
    }
    value.update(changes)
    return value


def timeline(shots=None, **changes):
    value = {"schema": "videostudio.cinematic3d.timeline.v1", "name": "Authored demo",
             "fps": 30, "motionProvider": "procedural_mannequin", "shots": shots or [shot()]}
    value.update(changes)
    return value


def pose_key(phase):
    actor = {"root": [0, 0, 0], "rotations": [[1, 0, 0, 0] for _ in range(16)]}
    return {"phase": phase, "actors": {"black": copy.deepcopy(actor), "white": copy.deepcopy(actor)}}


def camera_key(phase, position=None, target=None, up=None):
    key = {"phase": phase, "position": position or [2, 2, 6], "target": target or [0, 1, 0]}
    if up is not None:
        key["up"] = up
    return key


class TimelineValidationTests(unittest.TestCase):
    def test_validation_returns_independent_canonical_data(self):
        original = timeline()
        accepted = validate_timeline(original)
        accepted["shots"][0]["camera"]["fovDegrees"] = 70
        self.assertEqual(original["shots"][0]["camera"]["fovDegrees"], 55)

    def test_preserves_source_occurrences_and_order(self):
        anchors = [{"assetId": "z_first", "sha256": "a" * 64},
                   {"assetId": "a_second", "sha256": "b" * 64},
                   {"assetId": "z_first", "sha256": "a" * 64}]
        self.assertEqual(validate_timeline(timeline([shot(sourceAnchors=anchors)]))["shots"][0]["sourceAnchors"], anchors)

    def test_supports_all_declared_actions(self):
        actions = ["STANDOFF", "CHARGE", "CLASH", "BLADE_LOCK", "FOLLOW_THROUGH", "KNOCKBACK",
                   "LANDING", "RECOVERY", "AERIAL_FLIP", "TUMBLE", "MACRO_CLASH"]
        for action in actions:
            with self.subTest(action=action):
                validate_timeline(timeline([shot(action=action, hitStopFrames=0)]))

    def test_rejects_unknown_fields_at_every_contract_level(self):
        for scope in ("root", "shot", "camera", "vfx", "anchor", "pose", "contact"):
            value = timeline()
            node = value
            if scope != "root":
                node = value["shots"][0]
            if scope in ("camera", "vfx"):
                node = node[scope]
            if scope == "anchor":
                node["sourceAnchors"] = [{"assetId": "asset", "sha256": "a" * 64}]
                node = node["sourceAnchors"][0]
            if scope == "pose":
                node["poseKeyframes"] = [pose_key(0), pose_key(1)]
                node = node["poseKeyframes"][0]
            if scope == "contact":
                node["footContacts"] = [{"actor": "black", "foot": "leading", "startPhase": 0,
                                         "endPhase": 1, "anchor": [0, 0, 0]}]
                node = node["footContacts"][0]
            node["executable"] = "forbidden"
            with self.subTest(scope=scope), self.assertRaises(ValueError):
                validate_timeline(value)

    def test_rejects_missing_fields_wrong_schema_and_provider(self):
        for field in ("schema", "name", "fps", "motionProvider", "shots"):
            value = timeline()
            del value[field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_timeline(value)
        for changes in ({"schema": "wrong"}, {"motionProvider": "exact_image_reconstruction"}):
            with self.assertRaises(ValueError):
                validate_timeline(timeline(**changes))

    def test_fps_is_24_30_or_60_and_integers_exclude_booleans(self):
        for fps in (24, 30, 60):
            self.assertEqual(validate_timeline(timeline(fps=fps))["fps"], fps)
        for fps in (True, 30.0, 0, 25, 120):
            with self.subTest(fps=fps), self.assertRaises(ValueError):
                validate_timeline(timeline(fps=fps))
        for field in ("durationFrames", "hitStopFrames"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_timeline(timeline([shot(**{field: True})]))

    def test_shot_and_total_frame_bounds(self):
        maximum = [shot(id="shot_%02d" % i, durationFrames=150, hitStopFrames=0) for i in range(80)]
        self.assertEqual(compile_timeline(timeline(maximum))["totalFrames"], 12000)
        for shots in ([], maximum + [shot(id="shot_80")], [shot(durationFrames=7)],
                      [shot(durationFrames=601)],
                      [shot(id="shot_%02d" % i, durationFrames=151) for i in range(80)]):
            value = timeline()
            value["shots"] = shots
            with self.subTest(count=len(shots)), self.assertRaises(ValueError):
                validate_timeline(value)

    def test_identifiers_and_duplicate_shots_are_rejected(self):
        for unsafe in ("../shot", "shot/1", "", ".hidden", "a" * 97):
            with self.subTest(id=unsafe), self.assertRaises(ValueError):
                validate_timeline(timeline([shot(id=unsafe)]))
        with self.assertRaises(ValueError):
            validate_timeline(timeline([shot(), shot()]))

    def test_impact_hold_is_0_or_two_to_five_and_only_for_impact(self):
        for duration in (0, 2, 3, 4, 5):
            validate_timeline(timeline([shot(hitStopFrames=duration)]))
        for value in (-1, 1, 6):
            with self.subTest(hold=value), self.assertRaises(ValueError):
                validate_timeline(timeline([shot(hitStopFrames=value)]))
        with self.assertRaises(ValueError):
            validate_timeline(timeline([shot(action="CHARGE", hitStopFrames=2)]))
        validate_timeline(timeline([shot(durationFrames=8, hitStopFrames=5)]))

    def test_camera_and_vfx_are_bounded_finite_typed_values(self):
        for field, values in {"fovDegrees": (34, 101, float("nan"), True),
                              "shakeIntensity": (-0.1, 1.1, float("inf"), False),
                              "mode": ("handheld",)}.items():
            for bad in values:
                camera = dict(shot()["camera"], **{field: bad})
                with self.subTest(field=field, value=bad), self.assertRaises(ValueError):
                    validate_timeline(timeline([shot(camera=camera)]))
        with self.assertRaises(ValueError):
            validate_timeline(timeline([shot(vfx={"sparkBurst": 1, "slashTrail": False, "rubble": False})]))

    def test_metadata_and_deep_json_are_rejected_before_recursive_work(self):
        with self.assertRaises(ValueError):
            validate_timeline(timeline(name="x" * (512 * 1024)))
        nested = []
        for _ in range(40):
            nested = [nested]
        with self.assertRaises(ValueError):
            validate_timeline(timeline(extra=nested))
        circular = timeline()
        circular["extra"] = circular
        with self.assertRaises(ValueError):
            validate_timeline(circular)

    def test_authored_quaternion_keys_are_normalized_and_ordered(self):
        keys = [pose_key(0), pose_key(0.5), pose_key(1)]
        keys[1]["actors"]["black"]["rotations"][0] = [0, 2, 0, 0]
        accepted = validate_timeline(timeline([shot(poseKeyframes=keys)]))
        self.assertEqual(accepted["shots"][0]["poseKeyframes"][1]["actors"]["black"]["rotations"][0], [0, 1, 0, 0])
        for invalid in ([pose_key(0), pose_key(0)], [pose_key(0.7), pose_key(0.3)], [pose_key(-0.1)], [pose_key(1.1)]):
            with self.subTest(keys=invalid), self.assertRaises(ValueError):
                validate_timeline(timeline([shot(poseKeyframes=invalid)]))
        bad = [pose_key(0)]
        bad[0]["actors"]["black"]["rotations"][0] = [0, 0, 0, 0]
        with self.assertRaises(ValueError):
            validate_timeline(timeline([shot(poseKeyframes=bad)]))

    def test_repeated_validation_keeps_authored_quaternion_values_stable(self):
        values = random.Random(173)
        for _ in range(100):
            key = pose_key(0)
            key["actors"]["black"]["rotations"] = [[values.random() for _ in range(4)] for _ in range(16)]
            accepted = validate_timeline(timeline([shot(poseKeyframes=[key])]))
            self.assertEqual(accepted, validate_timeline(accepted))

    def test_camera_track_validation_preserves_literal_key_order_and_optional_up(self):
        keys = [camera_key(0.2), camera_key(0.7, [4, 3, 6], [1, 1, 0], [0, 1, 1])]
        camera = dict(shot()["camera"], keyframes=keys)
        accepted = validate_timeline(timeline([shot(camera=camera)]))
        self.assertEqual(accepted["shots"][0]["camera"]["keyframes"], keys)
        accepted["shots"][0]["camera"]["keyframes"][0]["position"][0] = 99
        self.assertEqual(keys[0]["position"][0], 2)

    def test_camera_track_rejects_empty_oversized_unknown_unordered_and_invalid_basis(self):
        invalid = [[], [camera_key(i / 128) for i in range(129)],
                   [camera_key(0.7), camera_key(0.2)], [camera_key(0.2), camera_key(0.2)],
                   [camera_key(-0.1)], [camera_key(1.1)],
                   [dict(camera_key(0), execution="forbidden")],
                   [camera_key(0, [0, 1, 0], [0, 1, 0])],
                   [camera_key(0, up=[0, 0, 0])],
                   [camera_key(0, [0, 2, 0], [0, 1, 0], [0, 1, 0])],
                   [camera_key(0, [0, math.inf, 0])],
                   [camera_key(0, [10000.1, 2, 6])],
                   [camera_key(0, up=[0, 10000.1, 0])],
                   [camera_key(0, [10000, 1, 0], [10000, 1, 0.001])],
                   [camera_key(0, [1, 2])]]
        for keys in invalid:
            with self.subTest(keys=keys), self.assertRaises(ValueError):
                validate_timeline(timeline([shot(camera=dict(shot()["camera"], keyframes=keys))]))

    def test_foot_intervals_are_ordered_nonoverlapping_and_world_anchored(self):
        contact = {"actor": "black", "foot": "leading", "startPhase": 0.1,
                   "endPhase": 0.5, "anchor": [1, 0, -2]}
        accepted = validate_timeline(timeline([shot(footContacts=[contact])]))
        self.assertEqual(accepted["shots"][0]["footContacts"][0]["anchor"], [1, 0, -2])
        for changes in ({"endPhase": 0.1}, {"startPhase": -1}, {"foot": "hand"},
                        {"actor": "unknown"}, {"anchor": [0, math.nan, 0]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_timeline(timeline([shot(footContacts=[dict(contact, **changes)])]))
        with self.assertRaises(ValueError):
            validate_timeline(timeline([shot(footContacts=[contact, dict(contact, startPhase=0.4, endPhase=0.8)])]))


class TimelineClockTests(unittest.TestCase):
    def test_compile_uses_ordered_exclusive_frame_boundaries(self):
        result = compile_timeline(timeline([shot(id="later", durationFrames=30),
                                           shot(id="earlier", durationFrames=24)], fps=60))
        self.assertEqual(result["fps"], 60)
        self.assertEqual(result["totalFrames"], 54)
        self.assertEqual(result["durationSeconds"], 0.9)
        self.assertEqual([(s["id"], s["startFrame"], s["endFrame"]) for s in result["shots"]],
                         [("later", 0, 30), ("earlier", 30, 54)])

    def test_output_sample_n_uses_n_over_fps_without_inclusive_endpoint(self):
        value = shot(action="CHARGE", hitStopFrames=0)
        self.assertEqual(sample_shot(value, 0, 30)["motionSeconds"], 0)
        last = sample_shot(value, 29, 30)
        self.assertAlmostEqual(last["motionSeconds"], 29 / 30)
        self.assertAlmostEqual(last["phase"], 29 / 30)
        self.assertLess(last["phase"], 1)

    def test_exact_hold_sample_count_actor_clock_pauses_camera_continues(self):
        for hold in range(2, 6):
            value = shot(durationFrames=60, hitStopFrames=hold)
            samples = [sample_shot(value, n, 30) for n in range(60)]
            impact = samples[0]["impactFrame"]
            frozen = [n for n, item in enumerate(samples) if item["hitStop"]]
            self.assertEqual(frozen, list(range(impact, impact + hold)))
            self.assertEqual(len({samples[n]["motionSeconds"] for n in frozen}), 1)
            self.assertEqual(len({samples[n]["phase"] for n in frozen}), 1)
            self.assertGreater(samples[impact + hold]["motionSeconds"], samples[impact]["motionSeconds"])
            self.assertNotEqual(samples[impact]["camera"], samples[impact + 1]["camera"])

    def test_impact_pose_is_phase_half_and_sampling_matches_native_golden(self):
        fixture = json.loads((Path(__file__).parent / "fixtures" / "timeline_clock_golden.json").read_text())
        for case in fixture["cases"]:
            for expected in case["samples"]:
                with self.subTest(fps=case["fps"], mode=case["shot"]["camera"]["mode"], frame=expected["frame"]):
                    actual = sample_shot(case["shot"], expected["frame"], case["fps"])
                    self.assertEqual(actual["hitStop"], expected["hitStop"])
                    self.assertEqual(actual["impactFrame"], expected["impactFrame"])
                    self.assertAlmostEqual(actual["phase"], expected["phase"], places=12)
                    self.assertAlmostEqual(actual["motionSeconds"], expected["motionSeconds"], places=12)
                    for field in ("position", "target"):
                        for coordinate, wanted in zip(actual["camera"][field], expected["camera"][field]):
                            self.assertAlmostEqual(coordinate, wanted, places=12)
                    self.assertEqual(actual["camera"]["fovDegrees"], expected["camera"]["fovDegrees"])

    def test_camera_is_deterministic_y_up_bounded_and_fov_preserved(self):
        for mode in ("wide", "orbit", "macro", "top_down"):
            value = shot(camera={"mode": mode, "fovDegrees": 68, "shakeIntensity": 0})
            sample = sample_shot(value, 17, 30)
            self.assertEqual(sample, sample_shot(copy.deepcopy(value), 17, 30))
            self.assertEqual(set(sample), {"phase", "motionSeconds", "hitStop", "camera", "impactFrame"})
            self.assertEqual(sample["camera"]["fovDegrees"], 68)
            self.assertEqual(len(sample["camera"]["position"]), 3)
            self.assertEqual(len(sample["camera"]["target"]), 3)
            self.assertTrue(all(math.isfinite(x) for x in sample["camera"]["position"]))
            self.assertGreater(sample["camera"]["position"][1], 0)

    def test_authored_camera_interpolates_wall_clock_during_actor_hit_stop(self):
        camera = {"mode": "orbit", "fovDegrees": 68, "shakeIntensity": 0,
                  "keyframes": [camera_key(0, [2, 2, 6], [0, 1, 0], [0, 1, 0]),
                                camera_key(1, [8, 2, 6], [3, 1, 0], [0, 1, 1])]}
        value = shot(camera=camera)
        for frame in (0, 13, 14, 15, 29):
            sample = sample_shot(value, frame, 30)
            p = frame / 30
            for field, expected in (("position", [2 + 6 * p, 2, 6]), ("target", [3 * p, 1, 0]), ("up", [0, 1, p])):
                for actual, wanted in zip(sample["camera"][field], expected):
                    self.assertAlmostEqual(actual, wanted, places=12)
            self.assertEqual(sample["camera"]["fovDegrees"], 68)
            if frame in (13, 14, 15):
                self.assertTrue(sample["hitStop"])
                self.assertEqual(sample["phase"], 0.5)
        self.assertNotEqual(sample_shot(value, 13, 30)["camera"], sample_shot(value, 14, 30)["camera"])

    def test_authored_camera_clamps_to_key_range_and_has_safe_default_up(self):
        keys = [camera_key(0.25), camera_key(0.75, [8, 2, 6])]
        value = shot(camera=dict(shot()["camera"], shakeIntensity=0, keyframes=keys))
        first, last = sample_shot(value, 0, 30)["camera"], sample_shot(value, 29, 30)["camera"]
        self.assertEqual(first["position"], [2, 2, 6])
        self.assertEqual(last["position"], [8, 2, 6])
        self.assertEqual(first["up"], [0, 1, 0])

    def test_camera_track_rejects_degenerate_interpolated_look_direction_and_up(self):
        pairs = [
            [camera_key(0, [0, 1, 0], [0, 1, -1]), camera_key(1, [0, 1, 0], [0, 1, 1])],
            [camera_key(0, up=[0, 1, 0]), camera_key(1, up=[0, -1, 0])],
            [camera_key(0, [0, 0, 0], [1, 0, -1], [0, 1, -1]),
             camera_key(1, [0, 0, 0], [-1, 0, -1], [0, -1, -1])],
        ]
        for keys in pairs:
            value = shot(action="CHARGE", hitStopFrames=0,
                         camera=dict(shot()["camera"], shakeIntensity=0, keyframes=keys))
            validate_timeline(timeline([value]))
            with self.subTest(keys=keys), self.assertRaises(ValueError):
                sample_shot(value, 15, 30)

    def test_shake_decays_after_impact_on_the_wall_clock(self):
        value = shot(durationFrames=120, hitStopFrames=3)
        plain = copy.deepcopy(value)
        plain["camera"]["shakeIntensity"] = 0
        impact = sample_shot(value, 0, 30)["impactFrame"]
        def amplitude(frame):
            shaken = sample_shot(value, frame, 30)["camera"]["position"]
            base = sample_shot(plain, frame, 30)["camera"]["position"]
            return math.dist(shaken, base)
        self.assertGreater(amplitude(impact + 1), amplitude(impact + 40))
        self.assertEqual(amplitude(impact - 1), 0)

    def test_sample_rejects_noninteger_or_outside_frame_indices(self):
        for frame in (-1, 30, 1.5, True):
            with self.subTest(frame=frame), self.assertRaises(ValueError):
                sample_shot(shot(), frame, 30)
        with self.assertRaises(ValueError):
            sample_shot(shot(), 0, 25)


if __name__ == "__main__":
    unittest.main()
