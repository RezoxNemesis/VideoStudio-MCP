"""Offline, explicitly cast dialogue for the app-owned open production runner.

No voice cloning, implicit model downloads, substituted actors or truncated lines.
The model's duration predictions are a lip-sync starting point, not forced alignment.
"""
from __future__ import annotations
import math
from pathlib import Path
import time
import wave

from .archive import atomic_json, checksum, digest, fields, identifier, integer, owned, read_json

VOICES = {"en-US": ("af_heart", "am_adam"), "hi-IN": ("hf_alpha", "hm_omega")}
SAMPLE_RATE = 24000


def validate_controls(value):
    fields(value, ("language", "durationMs", "lines"))
    if not isinstance(value["language"], str) or value["language"] not in VOICES:
        raise ValueError("DIALOGUE_LANGUAGE_UNAVAILABLE")
    duration = integer(value["durationMs"], 100, 600000)
    lines = value["lines"]
    if not isinstance(lines, list) or not 1 <= len(lines) <= 32:
        raise ValueError("Dialogue needs 1..32 lines per request")
    ids, cast, text_count, previous = set(), {}, 0, -1
    for line in lines:
        fields(line, ("lineId", "characterId", "voice", "text", "startMs", "slotMs"), ("speed",))
        identifier(line["lineId"]); identifier(line["characterId"])
        if line["lineId"] in ids:
            raise ValueError("Duplicate dialogue line")
        ids.add(line["lineId"])
        if line["voice"] not in VOICES[value["language"]]:
            raise ValueError("DIALOGUE_VOICE_LANGUAGE_MISMATCH")
        if cast.setdefault(line["characterId"], line["voice"]) != line["voice"]:
            raise ValueError("CHARACTER_VOICE_CHANGED")
        text = line["text"]
        if not isinstance(text, str) or not text.strip() or len(text) > 240 or any(ord(c) < 32 and c not in "\n\t" for c in text):
            raise ValueError("Dialogue lines need 1..240 characters; split longer speech into lines")
        text_count += len(text)
        if text_count > 6000:
            raise ValueError("Dialogue text budget exceeded")
        start = integer(line["startMs"], 0, duration - 1)
        slot = integer(line["slotMs"], 100, 60000)
        if start < previous or start + slot > duration:
            raise ValueError("Dialogue slots must be ordered, nonoverlapping and inside the scene")
        previous = start + slot
        speed = line.get("speed", 1)
        if type(speed) not in (float, int) or not math.isfinite(speed) or not .75 <= speed <= 1.25:
            raise ValueError("Dialogue speed must be 0.75..1.25")
    return value


def model_manifest(config, *, verify_bytes=False):
    model = config.get("kokoro")
    if model is None:
        raise FileNotFoundError("KOKORO_NOT_CONFIGURED")
    root = Path(model["modelPath"])
    path = owned(root, "videostudio-speech-manifest.json")
    if digest(path) != checksum(model["manifestSha256"]):
        raise ValueError("SPEECH_MODEL_MANIFEST_CHANGED")
    manifest = read_json(path)
    fields(manifest, ("schema", "modelId", "revision", "license", "files"))
    if (manifest["schema"] != "videostudio.speech.model.v1" or manifest["modelId"] != "hexgrad/Kokoro-82M"
            or manifest["revision"] != model["revision"] or manifest["license"] != "apache-2.0"):
        raise ValueError("SPEECH_MODEL_IDENTITY_CHANGED")
    required = {"config.json", "kokoro-v1_0.pth"} | {"voices/" + voice + ".pt" for voices in VOICES.values() for voice in voices}
    records = manifest["files"]
    if not isinstance(records, list) or len(records) != len(required):
        raise ValueError("SPEECH_MODEL_FILES_DIFFER")
    seen = set()
    for record in records:
        fields(record, ("path", "bytes", "sha256"))
        name = record["path"]
        if name not in required or name in seen:
            raise ValueError("SPEECH_MODEL_FILES_DIFFER")
        seen.add(name); checksum(record["sha256"])
        artifact = owned(root, name)
        if artifact.stat().st_size != integer(record["bytes"], 1, 512 * 1024**2):
            raise ValueError("SPEECH_MODEL_SIZE_CHANGED")
        if verify_bytes and digest(artifact) != record["sha256"]:
            raise ValueError("SPEECH_MODEL_BYTES_CHANGED")
    return manifest


def _write_wave(path, samples):
    import numpy as np
    if not np.isfinite(samples).all() or not len(samples):
        raise ValueError("SPEECH_INVALID_SAMPLES")
    peak = float(np.max(np.abs(samples)))
    if peak < .001:
        raise ValueError("SPEECH_SILENT_OUTPUT")
    # Attenuation only. Retain a little headroom; never hard-clip a model waveform.
    gain = min(1., .95 / peak)
    pcm = np.rint(samples * gain * 32767).astype("<i2")
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(1); stream.setsampwidth(2); stream.setframerate(SAMPLE_RATE)
        stream.writeframes(pcm.tobytes())
    return {"samples": len(samples), "sampleRate": SAMPLE_RATE, "durationMs": len(samples) * 1000 / SAMPLE_RATE,
            "peakBeforeGain": peak, "appliedGain": gain, "sha256": digest(path)}


def render(config, controls, output, checkpoint):
    validate_controls(controls)
    checkpoint("verifying_speech_model")
    manifest = model_manifest(config, verify_bytes=True)
    import numpy as np
    import torch
    import kokoro
    from kokoro import KModel, KPipeline
    if controls["language"] == "en-US":
        import spacy.util
        if not spacy.util.is_package("en_core_web_sm"):
            raise FileNotFoundError("ENGLISH_G2P_MODEL_NOT_INSTALLED")
    torch.set_num_threads(config["kokoro"].get("cpuThreads", 4))
    started = time.monotonic(); root = Path(config["kokoro"]["modelPath"])
    model = KModel(repo_id=manifest["modelId"], config=str(root / "config.json"), model=str(root / "kokoro-v1_0.pth")).eval().to("cpu")
    pipeline = KPipeline(lang_code="a" if controls["language"] == "en-US" else "h", repo_id=manifest["modelId"], model=False)
    mixed = np.zeros(controls["durationMs"] * SAMPLE_RATE // 1000, dtype=np.float32)
    records, outputs, cast = [], [], {}
    for index, line in enumerate(controls["lines"]):
        checkpoint("synthesizing_dialogue_%d" % (index + 1))
        # Tensor-only, hash-verified local voice packs; KPipeline cannot download one.
        pack = torch.load(str(root / ("voices/" + line["voice"] + ".pt")), map_location="cpu", weights_only=True)
        parts, predicted, offset = [], [], 0
        with torch.inference_mode():
            segments = list(pipeline(line["text"], split_pattern=None))
            if not segments:
                raise ValueError("SPEECH_PHONEMES_UNAVAILABLE")
            for segment in segments:
                phones = segment.phonemes
                # Upstream may truncate at 510. Reject that boundary, never accept it.
                if not phones or len(phones) >= 500:
                    raise ValueError("SPEECH_LINE_TOO_LONG_FOR_MODEL")
                result = KPipeline.infer(model, phones, pack, line.get("speed", 1))
                samples = result.audio.numpy().astype(np.float32)
                if not len(samples) or not np.isfinite(samples).all():
                    raise ValueError("SPEECH_INVALID_SAMPLES")
                durations = result.pred_dur.tolist() if result.pred_dur is not None else None
                predicted.append({"phonemes": phones, "startSample": offset, "sampleCount": len(samples),
                                  "modelDurationUnits": durations, "unitsPerSecond": 40,
                                  "forcedAlignmentVerified": False})
                parts.append(samples); offset += len(samples)
        samples = np.concatenate(parts)
        if len(samples) > line["slotMs"] * SAMPLE_RATE // 1000:
            raise ValueError("DIALOGUE_EXCEEDS_SLOT: " + line["lineId"])
        path = output / ("line_%02d.wav" % index)
        proof = _write_wave(path, samples)
        # Use the same gain as the individual stem, so the timeline matches it.
        first = line["startMs"] * SAMPLE_RATE // 1000
        mixed[first:first + len(samples)] += samples * proof["appliedGain"]
        records.append(dict(line, path=path.name, audio=proof, timing=predicted))
        outputs.append((path, "dialogue_stem", "audio/wav"))
        cast[line["characterId"]] = line["voice"]
    master = output / "dialogue.wav"; audio = _write_wave(master, mixed)
    timing = output / "dialogue.json"
    atomic_json(timing, {"schema": "videostudio.dialogue.v1", "language": controls["language"],
        "durationMs": controls["durationMs"], "cast": cast, "lines": records,
        "timingMethod": "model duration predictions; not forced alignment", "voiceActingAccepted": False})
    checkpoint("rechecking_speech_model")
    model_manifest(config, verify_bytes=True)
    outputs.extend(((master, "audio", "audio/wav"), (timing, "dialogue_timing", "application/json")))
    return outputs, {"backend": "kokoro", "execution": "self_hosted_open_source_speech", "device": "cpu",
        "nativeRendered": False, "realModelInference": True, "modelBytesVerified": True,
        "modelId": manifest["modelId"], "revision": manifest["revision"], "license": manifest["license"],
        "modelManifestSha256": config["kokoro"]["manifestSha256"], "kokoroVersion": getattr(kokoro, "__version__", "unknown"),
        "cast": cast, "language": controls["language"], "lineCount": len(records), "audio": audio,
        "inferenceSeconds": time.monotonic() - started, "voiceActingAccepted": False,
        "lipSyncAccepted": False, "cinematicAcceptance": False}
