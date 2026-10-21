"""Phrase validation and PCM assembly; fake inference isolates the sample clock."""
import copy
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch
import wave

import numpy as np
from openpipeline.speech import render, validate_controls


def controls():
    return {"language": "hi-IN", "durationMs": 4000, "lines": [{
        "lineId": "line", "characterId": "actor", "voice": "hm_omega",
        "text": "रुको। सुनो।", "startMs": 500, "slotMs": 3000, "speed": 1.1,
        "phrases": [{"text": "रुको।", "speed": .75, "pauseAfterMs": 125},
                    {"text": "सुनो।", "pauseAfterMs": 250}]}]}


class PhraseSpeechTest(unittest.TestCase):
    def test_preserves_exact_words_optional_fields_and_existing_line_limits(self):
        c = controls(); before = copy.deepcopy(c)
        self.assertEqual(validate_controls(c), before)
        self.assertEqual(c, before)
        for phrases in ([{"text": c["lines"][0]["text"]}],
                        [{"text": "a", "speed": 1.25, "pauseAfterMs": 1500}] * 8):
            c = controls(); c["lines"][0]["phrases"] = phrases
            c["lines"][0]["text"] = " ".join(p["text"] for p in phrases)
            self.assertEqual(validate_controls(c), c)

    def test_rejects_malformed_phrases_and_lost_or_changed_text(self):
        variants = [None, {}, [], [{"text": "a"}] * 9, [{"text": ""}],
                    [{"text": " "}], [{"text": "a" * 241}], [{"text": "x\0"}],
                    [{"text": "changed"}], [{"text": 7}], [{"text": "रुको। सुनो।", "voice": "hf_alpha"}]]
        for key, values in (("speed", [True, "1", None, .749, 1.251, float("nan"), float("inf")]),
                            ("pauseAfterMs", [True, "1", None, -1, 1501, 1.5, 1.0])):
            variants.extend([[{"text": "रुको। सुनो।", key: v}] for v in values])
        for phrases in variants:
            c = controls(); c["lines"][0]["phrases"] = phrases
            with self.subTest(phrases=phrases), self.assertRaises(ValueError):
                validate_controls(c)
        c = controls(); c["lines"][0]["text"] = "रुको।  सुनो।"
        with self.assertRaises(ValueError): validate_controls(c)
        c = controls(); c["lines"][0]["text"] = "a" * 120 + " " + "b" * 120
        c["lines"][0]["phrases"] = [{"text": "a" * 120}, {"text": "b" * 120}]
        with self.assertRaises(ValueError): validate_controls(c)

    def run_render(self, c, segment_count=1):
        calls = []
        class Model:
            def __init__(self, **kwargs): pass
            def eval(self): return self
            def to(self, device): return self
        class Pipeline:
            def __init__(self, **kwargs): pass
            def __call__(self, text, split_pattern):
                return [types.SimpleNamespace(phonemes=text)] * segment_count
            @staticmethod
            def infer(model, phones, pack, speed):
                calls.append((phones, speed))
                samples = np.arange(2400, dtype=np.float32) / 4800
                return types.SimpleNamespace(audio=types.SimpleNamespace(numpy=lambda: samples),
                    pred_dur=types.SimpleNamespace(tolist=lambda: [1, 2, 1]))
        torch = types.SimpleNamespace(set_num_threads=lambda n: None, load=lambda *a, **k: object(),
                                      inference_mode=__import__('contextlib').nullcontext)
        kokoro = types.SimpleNamespace(KModel=Model, KPipeline=Pipeline, __version__="test")
        with tempfile.TemporaryDirectory() as tmp, patch.dict("sys.modules", {"torch": torch, "kokoro": kokoro}), \
                patch("openpipeline.speech.model_manifest", return_value={"modelId": "hexgrad/Kokoro-82M", "revision": "test", "license": "apache-2.0"}):
            outputs, proof = render({"kokoro": {"modelPath": tmp, "manifestSha256": "a" * 64}}, c, Path(tmp), lambda s: None)
            manifest = json.loads((Path(tmp) / "dialogue.json").read_text())
            with wave.open(str(Path(tmp) / "line_00.wav")) as stream:
                pcm = np.frombuffer(stream.readframes(stream.getnframes()), dtype="<i2").copy()
            with wave.open(str(Path(tmp) / "dialogue.wav")) as stream:
                master = np.frombuffer(stream.readframes(stream.getnframes()), dtype="<i2").copy()
            return calls, manifest, pcm, master, proof

    def test_infers_each_phrase_at_its_pace_and_inserts_exact_silence_without_rescaling(self):
        c = controls(); calls, timing, pcm, master, proof = self.run_render(c)
        self.assertEqual(calls, [("रुको।", .75), ("सुनो।", 1.1)])
        line = timing["lines"][0]
        self.assertEqual(line["phrases"], c["lines"][0]["phrases"])
        self.assertEqual([s["startSample"] for s in line["timing"]], [0, 5400])
        self.assertEqual([s["sampleCount"] for s in line["timing"]], [2400, 2400])
        self.assertEqual(len(pcm), 13800)
        self.assertTrue(np.all(pcm[2400:5400] == 0)); self.assertTrue(np.all(pcm[7800:] == 0))
        expected = np.rint(np.arange(2400, dtype=np.float32) / 4800 * 32767).astype("<i2")
        np.testing.assert_array_equal(pcm[:2400], expected)
        np.testing.assert_array_equal(pcm[5400:7800], expected)
        np.testing.assert_array_equal(master[12000:25800], pcm)
        self.assertEqual(line["timing"][1]["modelDurationUnits"], [1, 2, 1])
        self.assertFalse(proof["voiceActingAccepted"])

    def test_bounds_total_generated_segments_not_only_phrase_count(self):
        c = controls(); c["lines"][0]["slotMs"] = 3000
        self.assertEqual(len(self.run_render(c, 4)[1]["lines"][0]["timing"]), 8)
        with self.assertRaisesRegex(ValueError, "SPEECH_TOO_MANY_SEGMENTS"):
            self.run_render(c, 5)
        del c["lines"][0]["phrases"]
        with self.assertRaisesRegex(ValueError, "SPEECH_TOO_MANY_SEGMENTS"):
            self.run_render(c, 9)

    def test_pause_overrun_rejects_instead_of_truncating_and_legacy_pcm_is_unchanged(self):
        c = controls(); c["lines"][0]["slotMs"] = 500
        with self.assertRaisesRegex(ValueError, "DIALOGUE_EXCEEDS_SLOT"): self.run_render(c)
        c = controls(); del c["lines"][0]["phrases"]
        calls, timing, pcm, master, proof = self.run_render(c)
        self.assertEqual(calls, [(c["lines"][0]["text"], 1.1)])
        self.assertNotIn("phrases", timing["lines"][0])
        expected = np.rint(np.arange(2400, dtype=np.float32) / 4800 * 32767).astype("<i2")
        np.testing.assert_array_equal(pcm, expected)
        np.testing.assert_array_equal(master[12000:14400], expected)
