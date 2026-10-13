"""Real video preparation and atomic mounted-exchange primitive regressions."""
import errno
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
import threading
import unittest
from unittest.mock import patch

from openpipeline import archive
from openpipeline.backends import encode_video, prepare_video_source
from openpipeline.exchange import ExchangeUnavailable, copy_verified
from test_openpipeline import png


class ReferenceVideoTest(unittest.TestCase):
    def request(self, source=None, **controls):
        values = {"prompt": "Restyle the moving cube", "mode": "video_to_video", "strength": .4,
            "width": 32, "height": 32, "numFrames": 9, "fps": 8, "seed": 17,
            "numInferenceSteps": 5, "guidanceScale": 6}
        values.update(controls)
        return {"schema": archive.REQUEST_SCHEMA, "requestId": "video_reference", "projectId": "fixture",
            "sourceProjectUpdatedAt": 0, "backend": "cogvideox", "controls": values,
            "sources": [] if source is None else [source]}

    def test_mode_strength_source_identity_and_nonzero_denoising_are_required(self):
        source = {"assetId": "source", "path": "reference.mp4", "mime": "video/mp4", "bytes": 32, "sha256": "a" * 64}
        self.assertEqual(archive.validate_request(self.request(source))["controls"]["mode"], "video_to_video")
        for changes in ({"strength": .01}, {"strength": float("nan")}, {"strength": .05, "numInferenceSteps": 2},
                        {"mode": "text_to_video"}, {"mode": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                archive.validate_request(self.request(source, **changes))
        with self.assertRaisesRegex(ValueError, "VIDEO_SOURCE_REQUIRED"):
            archive.validate_request(self.request())
        source["mime"] = "image/png"
        with self.assertRaisesRegex(ValueError, "VIDEO_SOURCE_REQUIRED"):
            archive.validate_request(self.request(source))

    def test_pinned_vae_frame_clock_rejects_thirteen_before_inference(self):
        source = {"assetId": "source", "path": "reference.mp4", "mime": "video/mp4", "bytes": 32, "sha256": "a" * 64}
        for mode in ("video_to_video", "text_to_video"):
            for count in (9, 17, 49):
                request = self.request(source if mode == "video_to_video" else None, mode=mode, numFrames=count)
                if mode == "text_to_video": request["controls"].pop("strength")
                self.assertEqual(archive.validate_request(request)["controls"]["numFrames"], count)
            request["controls"]["numFrames"] = 13
            with self.assertRaisesRegex(ValueError, "8k\\+1"):
                archive.validate_request(request)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "Actual video decoder required")
    def test_real_video_preparation_retains_actual_ordered_source_clock_and_hashes(self):
        with tempfile.TemporaryDirectory(prefix="v2v-preparation-") as name:
            root = Path(name); frames = root / "frames"; frames.mkdir()
            for index in range(17): png(frames / ("frame_%06d.png" % index), (20 + index * 12, 50, 80))
            source = root / "reference.mp4"
            encode_video(frames, source, 16, 17, 32, 24)
            record = {"assetId": "reference", "path": source.name, "mime": "video/mp4",
                "bytes": source.stat().st_size, "sha256": archive.digest(source)}
            request = self.request(record)
            archive.validate_request(request)
            paths, receipt = prepare_video_source(record, root, request["controls"], root / "prepared")
            self.assertEqual(len(paths), 9)
            self.assertEqual(receipt["sourceClipSha256"], record["sha256"])
            self.assertEqual(receipt["sourceDecodedFrameCount"], 17)
            self.assertEqual([frame["sourceFrame"] for frame in receipt["frames"]], list(range(0, 17, 2)))
            self.assertEqual([frame["timeSeconds"] for frame in receipt["frames"]], [index / 8 for index in range(9)])
            self.assertFalse(receipt["extrapolated"])
            from PIL import Image
            last_red = -1
            for path, proof in zip(paths, receipt["frames"]):
                self.assertEqual(archive.digest(path), proof["sha256"])
                with Image.open(path) as image:
                    self.assertEqual(image.size, (32, 32))
                    red = image.getpixel((16, 16))[0]
                    self.assertGreater(red, last_red); last_red = red
            with self.assertRaisesRegex(ValueError, "SOURCE_VIDEO_TOO_SHORT"):
                prepare_video_source(record, root, self.request(record, numFrames=17, fps=8)["controls"], root / "too_long")
            record["sha256"] = "b" * 64
            with self.assertRaisesRegex(ValueError, "SOURCE_CLIP_BYTES_CHANGED"):
                prepare_video_source(record, root, request["controls"], root / "changed")


class MountedExchangeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mounted-exchange-")
        self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)
        self.source = self.root / "source.vsresult"; self.source.write_bytes(b"immutable result archive" * 1024)
        self.target = self.root / "outbox" / "request_one.vsresult"

    def test_verified_hardlink_copy_preserves_source_and_reuses_exact_existing_bytes(self):
        proof = copy_verified(self.source, self.target, expected_sha=archive.digest(self.source))
        self.assertTrue(proof["published"])
        self.assertFalse(proof["remoteDrivePersistenceVerified"])
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())
        self.assertEqual(copy_verified(self.source, self.target)["publication"], "existing_verified")
        self.assertEqual(list(self.target.parent.glob("*.partial")), [])
        self.source.write_bytes(b"different result")
        with self.assertRaisesRegex(ValueError, "MOUNT_OUTPUT_CONFLICT"):
            copy_verified(self.source, self.target)
        self.assertNotEqual(self.source.read_bytes(), self.target.read_bytes())

    def test_actual_noreplace_rename_supports_simulated_hardlinkless_fuse(self):
        with patch("openpipeline.exchange.os.link", side_effect=OSError(errno.EPERM, "FUSE hardlinks unavailable")):
            proof = copy_verified(self.source, self.target)
        self.assertEqual(proof["publication"], "rename_noreplace")
        self.assertEqual(archive.digest(self.target), proof["readbackSha256"])
        self.assertTrue(self.source.exists())

    def test_unsupported_mount_preserves_one_verified_generation_partial_without_claim(self):
        with patch("openpipeline.exchange.os.link", side_effect=OSError(errno.EPERM, "unsupported")), patch(
                "openpipeline.exchange._rename_noreplace", side_effect=OSError(errno.ENOSYS, "unsupported")):
            for _ in range(2):
                with self.assertRaises(ExchangeUnavailable) as raised:
                    copy_verified(self.source, self.target)
                receipt = raised.exception.receipt
                self.assertEqual(receipt["code"], "MOUNT_ATOMIC_PROMOTION_UNAVAILABLE")
                self.assertFalse(receipt["published"])
                self.assertEqual(archive.digest(receipt["partialPath"]), archive.digest(self.source))
        self.assertFalse(self.target.exists())
        self.assertEqual(len(list(self.target.parent.glob("*.partial"))), 1)
        proof = copy_verified(self.source, self.target)
        self.assertTrue(proof["published"])
        self.assertEqual(list(self.target.parent.glob("*.partial")), [])

    def test_source_target_and_ancestor_symlinks_are_rejected_without_foreign_changes(self):
        real = self.root / "foreign"; real.mkdir()
        link = self.root / "ancestor"; link.symlink_to(real, target_is_directory=True)
        with self.assertRaises(OSError): copy_verified(self.source, link / "result.vsresult")
        link_source = self.root / "linked-source"; link_source.symlink_to(self.source)
        with self.assertRaises(OSError): copy_verified(link_source, self.target)
        self.target.parent.mkdir(exist_ok=True)
        self.target.symlink_to(self.source)
        with self.assertRaises(OSError): copy_verified(self.source, self.target)
        self.assertEqual(list(real.iterdir()), [])
        self.assertEqual(self.source.read_bytes(), b"immutable result archive" * 1024)

    def test_competing_publications_cannot_overwrite_the_winning_generation(self):
        from openpipeline.exchange import _promote
        other = self.root / "other.vsresult"; other.write_bytes(b"competing output" * 1024)
        barrier = threading.Barrier(2)
        outcomes = []
        def promote(parent, temporary, target):
            barrier.wait(timeout=3)
            return _promote(parent, temporary, target)
        def publish(source):
            try: outcomes.append(copy_verified(source, self.target))
            except ValueError as error: outcomes.append(error)
        with patch("openpipeline.exchange._promote", side_effect=promote), patch(
                "openpipeline.exchange.os.link", side_effect=OSError(errno.EPERM, "FUSE hardlinks unavailable")):
            threads = [threading.Thread(target=publish, args=(source,)) for source in (self.source, other)]
            for thread in threads: thread.start()
            for thread in threads: thread.join(timeout=5)
        self.assertEqual(len(outcomes), 2)
        self.assertEqual(sum(isinstance(outcome, dict) for outcome in outcomes), 1)
        self.assertIn(archive.digest(self.target), (archive.digest(self.source), archive.digest(other)))
        self.assertEqual(sum(isinstance(outcome, ValueError) for outcome in outcomes), 1)

    def test_copy_budget_prevents_publication_before_streaming(self):
        with self.assertRaisesRegex(ValueError, "MOUNT_ARTIFACT_SIZE"):
            copy_verified(self.source, self.target, limit=8)
        self.assertFalse(self.target.exists())
