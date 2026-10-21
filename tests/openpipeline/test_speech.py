import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import wave
from openpipeline import archive
from openpipeline.config import admission
from openpipeline.speech import validate_controls, model_manifest, _write_wave


def controls():
    return {"language": "hi-IN", "durationMs": 12000, "lines": [
        {"lineId": "one", "characterId": "kael", "voice": "hm_omega", "text": "मैं लड़ना नहीं चाहता।", "startMs": 0, "slotMs": 5000},
        {"lineId": "two", "characterId": "sera", "voice": "hf_alpha", "text": "तो अपनी तलवार नीचे रखो।", "startMs": 6000, "slotMs": 6000}]}


class SpeechTest(unittest.TestCase):
    def test_timed_cast_preserves_hindi_and_rejects_changed_actors_or_overrun(self):
        self.assertEqual(validate_controls(controls()), controls())
        for change in ("language", "malformed_language", "cast", "slot", "overlap", "duplicate", "speed", "long", "field"):
            c = controls()
            if change == "language": c["lines"][0]["voice"] = "am_adam"
            if change == "malformed_language": c["language"] = {}
            if change == "cast": c["lines"][1]["characterId"] = "kael"
            if change == "slot": c["lines"][1]["slotMs"] = 6001
            if change == "overlap": c["lines"][1]["startMs"] = 4999
            if change == "duplicate": c["lines"][1]["lineId"] = "one"
            if change == "speed": c["lines"][0]["speed"] = float("nan")
            if change == "long": c["lines"][0]["text"] = "a" * 241
            if change == "field": c["download"] = "https://example.invalid"
            with self.subTest(change=change), self.assertRaises(ValueError): validate_controls(c)

    def test_unconfigured_model_waits_and_audio_requests_do_not_accept_arbitrary_sources(self):
        r = {"schema": archive.REQUEST_SCHEMA, "requestId": "voices", "projectId": "film",
             "sourceProjectUpdatedAt": 1, "backend": "kokoro", "controls": controls(), "sources": []}
        archive.validate_request(r)
        import sys
        self.assertEqual(admission({"pythonExecutable": sys.executable}, r), ("waiting_model", "KOKORO_NOT_CONFIGURED"))
        r["sources"] = [{"assetId": "voice", "path": "voice.wav", "mime": "audio/wav", "bytes": 50, "sha256": "a" * 64}]
        with self.assertRaisesRegex(ValueError, "source assets"): archive.validate_request(r)

    def test_model_manifest_binds_every_cast_voice_and_actual_weight_bytes(self):
        from openpipeline.speech import VOICES
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            names = ["config.json", "kokoro-v1_0.pth"] + ["voices/" + v + ".pt" for voices in VOICES.values() for v in voices]
            records = []
            for name in names:
                p = root / name; p.parent.mkdir(exist_ok=True); p.write_bytes(name.encode())
                records.append({"path": name, "bytes": p.stat().st_size, "sha256": archive.digest(p)})
            m = {"schema": "videostudio.speech.model.v1", "modelId": "hexgrad/Kokoro-82M", "revision": "1" * 40, "license": "apache-2.0", "files": records}
            p = root / "videostudio-speech-manifest.json"; p.write_text(json.dumps(m))
            c = {"kokoro": {"modelPath": str(root), "manifestSha256": archive.digest(p), "revision": m["revision"]}}
            self.assertEqual(model_manifest(c, verify_bytes=True), m)
            voice = root / "voices/hf_alpha.pt"; voice.write_bytes(b"x" * voice.stat().st_size)
            with self.assertRaisesRegex(ValueError, "BYTES_CHANGED"): model_manifest(c, verify_bytes=True)

    def test_pcm_output_is_real_finite_unclipped_audio(self):
        try: import numpy as np
        except ImportError: self.skipTest("numpy runtime unavailable")
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "test.wav"
            samples = np.sin(np.arange(24000, dtype=np.float32) * .05) * 1.2
            receipt = _write_wave(p, samples)
            with wave.open(str(p)) as stream:
                self.assertEqual((stream.getframerate(), stream.getnframes(), stream.getsampwidth(), stream.getnchannels()), (24000, 24000, 2, 1))
                actual = np.frombuffer(stream.readframes(24000), dtype="<i2")
            self.assertLess(np.max(np.abs(actual)), 32767)
            self.assertLess(receipt["appliedGain"], 1)
            with self.assertRaises(ValueError): _write_wave(p, np.zeros(10))
            with self.assertRaises(ValueError): _write_wave(p, np.array([float("nan")]))
