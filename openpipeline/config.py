"""Only trusted operator configuration selects executables, models and mounts."""
from __future__ import annotations
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import json
from .archive import checksum, fields, integer, read_json

SCHEMA = "videostudio.openpipeline.config.v1"
_RUNTIME_CACHE = {}


def load(path):
    path = Path(path).resolve(strict=True)
    value = read_json(path)
    fields(value, ("schema", "root"), ("pythonExecutable", "blenderExecutable", "cogvideox", "kokoro", "interpolationConfig",
           "jobTimeoutSeconds", "queueLimit", "host", "port", "tokenEnv", "driveInbox", "driveResults"))
    if value["schema"] != SCHEMA:
        raise ValueError("Unknown runner configuration schema")
    def absolute(name):
        item = value[name]
        if not isinstance(item, str) or not item or "\0" in item:
            raise ValueError("Invalid configured path")
        candidate = Path(item).expanduser()
        value[name] = str(candidate.resolve() if candidate.is_absolute() else (path.parent / candidate).resolve())
    for name in ("root", "interpolationConfig", "driveInbox", "driveResults"):
        if name in value:
            absolute(name)
    value.setdefault("pythonExecutable", sys.executable)
    value.setdefault("blenderExecutable", "blender")
    for name in ("pythonExecutable", "blenderExecutable"):
        if not isinstance(value[name], str) or not value[name] or "\0" in value[name]:
            raise ValueError("Invalid trusted executable")
        if "/" in value[name]:
            # A venv interpreter is normally a symlink. Resolving it changes
            # Python's venv discovery and silently selects the system packages.
            candidate = Path(value[name]).expanduser()
            value[name] = os.path.abspath(candidate if candidate.is_absolute() else path.parent / candidate)
    integer(value.setdefault("jobTimeoutSeconds", 7200), 10, 86400)
    integer(value.setdefault("queueLimit", 32), 1, 256)
    integer(value.setdefault("port", 8787), 1, 65535)
    value.setdefault("host", "127.0.0.1"); value.setdefault("tokenEnv", "VIDEOSTUDIO_RUNNER_TOKEN")
    if not isinstance(value["host"], str) or len(value["host"]) > 253 or not isinstance(value["tokenEnv"], str) or not value["tokenEnv"].isidentifier():
        raise ValueError("Invalid server binding/token environment name")
    if "cogvideox" in value:
        model = value["cogvideox"]
        fields(model, ("modelPath", "manifestSha256", "revision"), ("device", "dtype", "minAvailableRamGiB", "cpuThreads", "maxFrames", "maxWidth", "maxHeight", "maxInferenceSteps"))
        checksum(model["manifestSha256"])
        revision = model["revision"]
        if not isinstance(revision, str) or len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision):
            raise ValueError("Model revision must pin an exact 40-character commit")
        candidate = Path(model["modelPath"])
        model["modelPath"] = str((candidate if candidate.is_absolute() else path.parent / candidate).resolve())
        if model.setdefault("device", "cpu") not in ("cpu", "cuda") or model.setdefault("dtype", "float32") not in ("float32", "bfloat16", "float16"):
            raise ValueError("Unsupported model device/dtype")
        if model["device"] == "cpu" and model["dtype"] == "float16":
            raise ValueError("CPU CogVideoX requires float32 or bfloat16")
        for name, default, low, high in (("cpuThreads", 2, 1, 16), ("minAvailableRamGiB", 12, 1, 512),
            ("maxFrames", 49, 1, 241), ("maxWidth", 720, 16, 2048), ("maxHeight", 480, 16, 2048), ("maxInferenceSteps", 50, 1, 100)):
            integer(model.setdefault(name, default), low, high)
    if "kokoro" in value:
        speech = value["kokoro"]
        fields(speech, ("modelPath", "manifestSha256", "revision"), ("cpuThreads",))
        checksum(speech["manifestSha256"])
        if not isinstance(speech["revision"], str) or len(speech["revision"]) != 40 or any(c not in "0123456789abcdef" for c in speech["revision"]):
            raise ValueError("Speech model needs an exact revision")
        candidate = Path(speech["modelPath"])
        speech["modelPath"] = str((candidate if candidate.is_absolute() else path.parent / candidate).resolve())
        integer(speech.setdefault("cpuThreads", 4), 1, 16)
    value["_configPath"] = str(path)
    return value


def available_ram():
    try:
        values = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        ram = int(values["MemAvailable"].split()[0]) * 1024
        for base in (Path("/sys/fs/cgroup"),):
            maximum, current = base / "memory.max", base / "memory.current"
            if maximum.is_file() and maximum.read_text().strip() != "max":
                inactive_file = 0
                statistics = base / "memory.stat"
                if statistics.is_file():
                    counters = dict(line.split() for line in statistics.read_text().splitlines())
                    inactive_file = int(counters.get("inactive_file", "0"))
                # Local verified model files can occupy reclaimable page cache.
                # Excluding inactive_file would falsely deny otherwise safe CPU jobs.
                ram = min(ram, max(0, int(maximum.read_text()) - int(current.read_text()) + inactive_file))
        return ram
    except (OSError, KeyError, ValueError):
        return 0


def model_manifest(config, *, verify_bytes=False):
    from .archive import digest, owned, relative, SHA
    model = config.get("cogvideox")
    if model is None:
        raise FileNotFoundError("COGVIDEOX_NOT_CONFIGURED")
    root = Path(model["modelPath"])
    manifest_path = owned(root, "videostudio-model-manifest.json")
    if digest(manifest_path) != model["manifestSha256"]:
        raise ValueError("MODEL_MANIFEST_SHA256_MISMATCH")
    manifest = read_json(manifest_path)
    fields(manifest, ("schema", "modelId", "revision", "license", "files"))
    if manifest["schema"] != "videostudio.openpipeline.model.v1" or manifest["revision"] != model["revision"] or manifest["modelId"] != "zai-org/CogVideoX-2b":
        raise ValueError("MODEL_REVISION_OR_FAMILY_MISMATCH")
    files = manifest["files"]
    if not isinstance(files, list) or not 1 <= len(files) <= 512:
        raise ValueError("Invalid model manifest file set")
    seen = set()
    for record in files:
        fields(record, ("path", "bytes", "sha256"))
        name = relative(record["path"])
        if name in seen or name == "videostudio-model-manifest.json" or Path(name).suffix in (".py", ".bin", ".pkl", ".pickle", ".pt", ".pth"):
            raise ValueError("Unsafe or duplicate model artifact")
        seen.add(name); checksum(record["sha256"])
        path = owned(root, name)
        if path.stat().st_size != integer(record["bytes"], 1, 32 * 1024**3):
            raise ValueError("MODEL_FILE_SIZE_MISMATCH")
        if verify_bytes and digest(path) != record["sha256"]:
            raise ValueError("MODEL_FILE_SHA256_MISMATCH")
    if "model_index.json" not in seen or not any(name.endswith(".safetensors") for name in seen):
        raise ValueError("MODEL_COMPONENTS_MISSING")
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file() and path.name != "videostudio-model-manifest.json"}
    if actual != seen:
        raise ValueError("MODEL_UNBOUND_FILES")
    return manifest


def admission(config, request):
    backend = request["backend"]
    if not shutil.which(config["pythonExecutable"]):
        return "waiting_compute", "PYTHON_RUNTIME_MISSING"
    if backend == "kokoro":
        from .speech import model_manifest as speech_manifest
        try:
            speech_manifest(config)
        except (FileNotFoundError, ValueError, OSError) as error:
            return "waiting_model", str(error)[:160]
        try:
            subprocess.run([config["pythonExecutable"], "-c", "import kokoro,torch,numpy,spacy.util; assert spacy.util.is_package('en_core_web_sm')"],
                           capture_output=True, check=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            return "waiting_compute", "SPEECH_RUNTIME_OR_ENGLISH_G2P_MISSING"
        return "queued", None
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        return "waiting_compute", "FFMPEG_RUNTIME_MISSING"
    if backend == "blender" and not shutil.which(config["blenderExecutable"]):
        return "waiting_compute", "BLENDER_RUNTIME_MISSING"
    if backend == "interpolation":
        if not config.get("interpolationConfig") or not Path(config["interpolationConfig"]).is_file():
            return "waiting_model", "INTERPOLATION_NOT_CONFIGURED"
        try:
            from cinematic3d.__main__ import _config, _runner_config
            from cinematic3d.providers import check_runner, ProviderFailure
            trusted, parent = _config(Path(config["interpolationConfig"]))
            runner = _runner_config(trusted, parent, "interpolation")
            if runner is None:
                return "waiting_model", "INTERPOLATION_NOT_CONFIGURED"
            check_runner(runner.command, runner.weights, runner.weights_sha256)
        except (OSError, ValueError, ProviderFailure) as error:
            return "waiting_model", getattr(error, "code", "INTERPOLATION_MODEL_UNAVAILABLE")
    if backend == "cogvideox":
        try:
            model_manifest(config)
        except (FileNotFoundError, ValueError, OSError) as error:
            return "waiting_model", str(error)[:160]
        model, controls = config["cogvideox"], request["controls"]
        executable = config["pythonExecutable"]
        cached = _RUNTIME_CACHE.get(executable)
        if cached is None or time.monotonic() - cached[0] > 60:
            try:
                probe = subprocess.run([executable, "-c",
                    "import json,torch;from diffusers import CogVideoXPipeline;print(json.dumps({'cuda':torch.cuda.is_available()}))"],
                    capture_output=True, check=True, timeout=30)
                observed = json.loads(probe.stdout)
                cached = (time.monotonic(), observed, None)
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                cached = (time.monotonic(), {}, "COGVIDEOX_RUNTIME_MISSING")
                diagnostic = getattr(error, "stderr", b"")
                if isinstance(diagnostic, bytes):
                    diagnostic = diagnostic.decode("utf-8", errors="replace")
                config["_runtimeDiagnostic"] = {"exceptionType": type(error).__name__,
                    "importDiagnostic": diagnostic[-512:] if diagnostic else str(error)[:200]}
            _RUNTIME_CACHE[executable] = cached
        if cached[2]:
            return "waiting_compute", cached[2]
        if model["device"] == "cuda" and not cached[1].get("cuda"):
            return "waiting_compute", "CUDA_NOT_AVAILABLE"
        if any(controls[key] > model[limit] for key, limit in (("width", "maxWidth"), ("height", "maxHeight"), ("numFrames", "maxFrames"), ("numInferenceSteps", "maxInferenceSteps"))):
            return "waiting_compute", "MODEL_RESOURCE_LIMIT"
        if available_ram() < model["minAvailableRamGiB"] * 1024**3:
            return "waiting_compute", "INSUFFICIENT_AVAILABLE_RAM"
    return "queued", None


def capabilities(config):
    backends = {}
    for name in ("blender", "cogvideox", "frame_sequence", "interpolation", "kokoro"):
        request = {"backend": name, "controls": {"width": 16, "height": 16, "numFrames": 1, "numInferenceSteps": 1}}
        state, code = admission(config, request)
        backends[name] = {"state": state, "available": state == "queued", "code": code,
                          "runtimeExecutionVerified": False, "modelBytesVerified": False}
    backends["cogvideox"]["configuredDevice"] = config.get("cogvideox", {}).get("device")
    from .speech import VOICES
    backends["kokoro"]["voices"] = VOICES
    backends["kokoro"]["voiceActingAccepted"] = False
    return {"schema": "videostudio.openpipeline.capabilities.v1", "backends": backends,
            "maxArchiveBytes": 256 * 1024**2, "maxExpandedBytes": 512 * 1024**2,
            "maxSources": 64, "execution": "self_hosted_open_source", "nativeRendered": False,
            "gpuObserved": None, "commercialProviders": [], "availableRamBytes": available_ram()}
