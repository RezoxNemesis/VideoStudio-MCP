"""Optional providers execute real configured children, never timeline commands."""
import hashlib
import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

try:
    providers = importlib.import_module("cinematic3d.providers")
except ModuleNotFoundError:
    providers = None

JOINTS = ["pelvis", "chest", "neck", "head", "swordShoulder", "swordElbow", "swordHand",
          "freeShoulder", "freeElbow", "freeHand", "leadingHip", "leadingKnee", "leadingFoot",
          "trailingHip", "trailingKnee", "trailingFoot"]


class PoseProviderTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(providers, "Configured pose provider implementation is missing")
        self.temp = tempfile.TemporaryDirectory(prefix="c3d-pose-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.source = self.directory / "source.png"
        self.source.write_bytes(b"private-source-fixture")
        self.sha = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.weights = self.directory / "weights.bin"
        self.weights.write_bytes(b"checked-test-runner-weights")
        self.weight_sha = hashlib.sha256(self.weights.read_bytes()).hexdigest()

    def config(self, code, **kwargs):
        runner = self.directory / "runner.py"
        runner.write_text(code, encoding="utf-8")
        values = dict(family="4dhumans", command=(sys.executable, str(runner)),
                      weights=self.weights, weights_sha256=self.weight_sha, timeout_seconds=3)
        values.update(kwargs)
        return providers.PoseRunnerConfig(**values)

    def valid_runner(self, suffix=""):
        return '''import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--input');p.add_argument('--output');p.add_argument('--weights');a=p.parse_args()
r=json.loads(Path(a.input).read_text())
assert Path(r['sourcePath']).read_bytes()==b'private-source-fixture'
assert Path(a.weights).read_bytes()==b'checked-test-runner-weights'
result={'schema':'videostudio.cinematic3d.poses.v1','sourceSha256':r['sourceSha256'],'jointNames':r['jointNames'],
'frames':[{'frame':0,'root':[0.1,0.0,-0.2],'localQuaternions':[[1,0,0,0]]*16,'confidence':[0.75]*16,'ambiguity':['occluded trailing arm']}]}
''' + suffix + "\nPath(a.output).write_text(json.dumps(result))\n"

    def test_real_runner_preserves_source_confidence_and_estimated_label(self):
        receipt = providers.estimate_pose(self.config(self.valid_runner()), self.source, self.sha)
        self.assertEqual(receipt["status"], "completed")
        self.assertEqual(receipt["jointNames"], JOINTS)
        self.assertEqual(receipt["sourceSha256"], self.sha)
        self.assertEqual(receipt["frames"][0]["confidence"], [0.75] * 16)
        self.assertEqual(receipt["frames"][0]["ambiguity"], ["occluded trailing arm"])
        self.assertEqual(receipt["evidence"]["motionProvider"], "estimated_4dhumans")
        self.assertFalse(receipt["evidence"]["originalImageReconstruction"])
        self.assertIsNone(receipt["evidence"]["anatomyQa"])

    def test_missing_config_and_weights_are_explicitly_unavailable(self):
        self.assertEqual(providers.estimate_pose(None, self.source, self.sha)["status"], "unavailable")
        config = self.config(self.valid_runner())
        self.weights.unlink()
        result = providers.estimate_pose(config, self.source, self.sha)
        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["code"], "MODEL_MISSING")

    def test_changed_model_is_rejected_before_child_execution(self):
        marker = self.directory / "ran"
        config = self.config("from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()")
        self.weights.write_bytes(b"tampered")
        result = providers.estimate_pose(config, self.source, self.sha)
        self.assertEqual(result["code"], "MODEL_SHA256_MISMATCH")
        self.assertFalse(marker.exists())

    def test_mismatched_source_and_nonunit_quaternions_fail_closed(self):
        result = providers.estimate_pose(self.config(self.valid_runner("result['sourceSha256']='0'*64")), self.source, self.sha)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["code"], "INVALID_POSE_OUTPUT")
        result = providers.estimate_pose(self.config(self.valid_runner("result['frames'][0]['localQuaternions'][3]=[2,0,0,0]")), self.source, self.sha)
        self.assertEqual(result["code"], "INVALID_POSE_OUTPUT")

    def test_child_timeout_is_bounded_and_reported(self):
        result = providers.estimate_pose(self.config("import time\ntime.sleep(5)", timeout_seconds=0.1), self.source, self.sha)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["code"], "RUNNER_TIMEOUT")

    def test_source_hash_is_verified_before_execution(self):
        result = providers.estimate_pose(self.config(self.valid_runner()), self.source, "0" * 64)
        self.assertEqual(result["code"], "SOURCE_SHA256_MISMATCH")

    def test_missing_configured_python_wrapper_is_unavailable(self):
        config = self.config(self.valid_runner())
        (self.directory / "runner.py").unlink()
        result = providers.estimate_pose(config, self.source, self.sha)
        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["code"], "RUNNER_MISSING")

    def test_wrapper_can_report_missing_model_runtime_without_false_failure(self):
        result = providers.estimate_pose(self.config("raise SystemExit(69)"), self.source, self.sha)
        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["code"], "RUNNER_UNAVAILABLE")


if __name__ == "__main__":
    unittest.main()
