"""Configured, source-bound pose runners. Models are optional and never downloaded.

This module consumes trusted local configuration, not executable fields from a
timeline. A runner wrapper around 4DHumans, ViTPose or SMPL-X receives ``--input
REQUEST --output PREDICTIONS --weights CHECKED_FILE``. The request declares the
canonical sixteen joint names and Y-up metre/wxyz convention. The wrapper must
convert its model's own joint layout and coordinate system to ``poses.v1``;
predictions remain estimates even when the child exits successfully. Wrappers
report a missing model runtime/dependency using exit 69 (EX_UNAVAILABLE); other
nonzero exits report failed execution. No child logs enter public receipts.
"""
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile

JOINT_NAMES = ("pelvis", "chest", "neck", "head", "swordShoulder", "swordElbow", "swordHand",
               "freeShoulder", "freeElbow", "freeHand", "leadingHip", "leadingKnee", "leadingFoot",
               "trailingHip", "trailingKnee", "trailingFoot")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class PoseRunnerConfig:
    family: str
    command: tuple[str, ...]
    weights: Path
    weights_sha256: str
    timeout_seconds: float = 120.0


class ProviderFailure(Exception):
    """A concise receipt-safe failure; never includes child logs or credentials."""
    def __init__(self, code, *, status="failed", **details):
        self.code, self.status, self.details = code, status, details
        super().__init__(code)


def failure_receipt(error, provider):
    return {"status": error.status, "code": error.code, "provider": provider, **error.details}


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_file(path, expected_sha256, *, missing_code="MODEL_MISSING", mismatch_code="MODEL_SHA256_MISMATCH"):
    if not isinstance(expected_sha256, str) or not _SHA256.fullmatch(expected_sha256):
        raise ProviderFailure("INVALID_CONFIG")
    try:
        result = Path(path).expanduser().resolve(strict=True)
        if not result.is_file():
            raise OSError("Not a regular file")
        actual = sha256_file(result)
    except (OSError, TypeError, ValueError):
        raise ProviderFailure(missing_code, status="unavailable") from None
    if actual != expected_sha256:
        raise ProviderFailure(mismatch_code)
    return result


def check_source(path, expected_sha256):
    return check_file(path, expected_sha256, missing_code="SOURCE_MISSING", mismatch_code="SOURCE_SHA256_MISMATCH")


def check_timeout(seconds):
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or not 0 < seconds <= 3600:
        raise ProviderFailure("INVALID_CONFIG")
    return float(seconds)


def check_runner(command, weights, expected_sha256):
    checked_weights = check_file(weights, expected_sha256)
    if not isinstance(command, (tuple, list)) or not 1 <= len(command) <= 32 or any(
            not isinstance(value, str) or not value or "\0" in value or len(value) > 4096 for value in command):
        raise ProviderFailure("INVALID_CONFIG")
    executable = shutil.which(command[0])
    if executable is None:
        raise ProviderFailure("RUNNER_MISSING", status="unavailable")
    if Path(executable).name.startswith(("python", "pypy")):
        # Python can exist while its configured wrapper does not. Recognize the
        # interpreter's option arguments without mistaking them for script paths.
        arguments = iter(command[1:])
        for argument in arguments:
            if argument in ("-c", "-m"):
                break
            if argument in ("-W", "-X"):
                next(arguments, None)
                continue
            if argument.startswith("-") and argument != "--":
                continue
            script = next(arguments, None) if argument == "--" else argument
            if script is not None and not Path(script).is_file():
                raise ProviderFailure("RUNNER_MISSING", status="unavailable")
            break
    return (executable, *command[1:]), checked_weights


def execute_runner(command, weights, request, output, timeout_seconds):
    """Bound the child and its descendants, while keeping arbitrary logs private."""
    timeout = check_timeout(timeout_seconds)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    request_path = output.parent / (output.name + ".request.json")
    request_path.write_text(json.dumps(request, allow_nan=False), encoding="utf-8")
    arguments = [*command, "--input", str(request_path), "--output", str(output), "--weights", str(weights)]
    # Temporary log files avoid unbounded captured stdout/stderr in process RAM.
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        try:
            child = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                     start_new_session=True)
        except OSError:
            raise ProviderFailure("RUNNER_UNAVAILABLE", status="unavailable") from None
        try:
            exit_code = child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            raise ProviderFailure("RUNNER_TIMEOUT") from None
    if exit_code == 69:  # sysexits.EX_UNAVAILABLE: missing configured model runtime.
        raise ProviderFailure("RUNNER_UNAVAILABLE", status="unavailable")
    if exit_code != 0:
        raise ProviderFailure("RUNNER_FAILED", exitCode=exit_code)


def read_json(path, *, limit=8 * 1024 * 1024):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise ValueError("Missing, linked or oversized JSON")
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    def no_constant(_):
        raise ValueError("Nonfinite JSON")
    result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs, parse_constant=no_constant)
    def depth(value, level=0):
        if level > 24:
            raise ValueError("Excessive JSON depth")
        if isinstance(value, dict):
            for item in value.values(): depth(item, level + 1)
        elif isinstance(value, list):
            for item in value: depth(item, level + 1)
    depth(result)
    return result


def _number(value):
    return not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value)


def _validate_predictions(result, source_sha256):
    if not isinstance(result, dict) or set(result) != {"schema", "sourceSha256", "jointNames", "frames"}:
        raise ValueError("Unexpected pose fields")
    if result["schema"] != "videostudio.cinematic3d.poses.v1" or result["sourceSha256"] != source_sha256 or result["jointNames"] != list(JOINT_NAMES):
        raise ValueError("Wrong pose convention or source binding")
    frames = result["frames"]
    if not isinstance(frames, list) or not 1 <= len(frames) <= 12000:
        raise ValueError("Invalid frame count")
    previous = -1
    for frame in frames:
        if not isinstance(frame, dict) or set(frame) != {"frame", "root", "localQuaternions", "confidence", "ambiguity"}:
            raise ValueError("Unexpected frame fields")
        index = frame["frame"]
        if isinstance(index, bool) or not isinstance(index, int) or not previous < index < 12000:
            raise ValueError("Unordered frame indices")
        previous = index
        root = frame["root"]
        if not isinstance(root, list) or len(root) != 3 or not all(_number(v) and abs(v) <= 10000 for v in root):
            raise ValueError("Invalid metre root")
        quaternions = frame["localQuaternions"]
        if not isinstance(quaternions, list) or len(quaternions) != 16:
            raise ValueError("Sixteen local quaternions required")
        for quaternion in quaternions:
            if not isinstance(quaternion, list) or len(quaternion) != 4 or not all(_number(v) for v in quaternion) or abs(sum(v*v for v in quaternion) - 1) > 1e-4:
                raise ValueError("Invalid normalized wxyz quaternion")
        confidence = frame["confidence"]
        if not isinstance(confidence, list) or len(confidence) != 16 or not all(_number(v) and 0 <= v <= 1 for v in confidence):
            raise ValueError("Per-joint confidence required")
        ambiguity = frame["ambiguity"]
        if not isinstance(ambiguity, list) or len(ambiguity) > 32 or not all(isinstance(v, str) and len(v) <= 2000 for v in ambiguity):
            raise ValueError("Invalid ambiguity annotations")
    return result


def estimate_pose(config, source_path, source_sha256):
    """Return completed estimated poses, an explicit unavailable, or failed receipt."""
    if config is None:
        return failure_receipt(ProviderFailure("POSE_PROVIDER_NOT_CONFIGURED", status="unavailable"), "pose")
    try:
        if not isinstance(config, PoseRunnerConfig) or config.family not in ("4dhumans", "vitpose", "smplx"):
            raise ProviderFailure("INVALID_CONFIG")
        check_timeout(config.timeout_seconds)
        source = check_source(source_path, source_sha256)
        command, weights = check_runner(config.command, config.weights, config.weights_sha256)
        with tempfile.TemporaryDirectory(prefix="c3d-pose-run-") as temporary:
            output = Path(temporary) / "predictions.json"
            request = {"schema": "videostudio.cinematic3d.pose-request.v1", "sourcePath": str(source),
                       "sourceSha256": source_sha256, "jointNames": list(JOINT_NAMES),
                       "coordinates": "right_handed_y_up_metres", "quaternionOrder": "wxyz", "family": config.family}
            execute_runner(command, weights, request, output, config.timeout_seconds)
            try:
                predictions = _validate_predictions(read_json(output), source_sha256)
            except (OSError, ValueError, TypeError, RecursionError):
                raise ProviderFailure("INVALID_POSE_OUTPUT") from None
        # Recheck the files: a changed source or checkpoint cannot acquire a valid receipt.
        check_source(source, source_sha256)
        check_file(weights, config.weights_sha256)
        return {"status": "completed", "provider": config.family, **predictions,
                "evidence": {"motionProvider": "estimated_" + config.family, "originalImageReconstruction": False,
                             "anatomyQa": None, "runnerExecuted": True, "sourceVerified": True,
                             "weightsSha256": config.weights_sha256}}
    except ProviderFailure as error:
        return failure_receipt(error, getattr(config, "family", "pose"))
