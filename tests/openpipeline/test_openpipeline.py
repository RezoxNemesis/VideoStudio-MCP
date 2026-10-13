"""Focused archive, persistence, cancellation, HTTP and actual encoder regressions."""
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import warnings
import zipfile
import zlib

from openpipeline import archive
from openpipeline.backends import execute
from openpipeline.config import admission, load
from openpipeline.jobs import Runner, Store, _connect, _start_ticks, update
from openpipeline.server import BoundedServer, Handler


def png(path, colour):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    raster = b"".join(b"\0" + bytes(colour) * 32 for _ in range(24))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 24, 8, 2, 0, 0, 0)) +
                     chunk(b"IDAT", zlib.compress(raster)) + chunk(b"IEND", b""))


class OpenPipelineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="openpipeline-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config_path = self.root / "runner.json"
        self.config_path.write_text(json.dumps({"schema": "videostudio.openpipeline.config.v1",
            "root": str(self.root / "store"), "pythonExecutable": sys.executable, "jobTimeoutSeconds": 30}))
        self.config = load(self.config_path)

    def job(self, *, request_id="request_one", changed=False):
        inputs = self.root / ("inputs_" + str(time.time_ns())); inputs.mkdir()
        sources = []
        for index, colour in enumerate(((240, 10, 10), (10, 230, 10), (10, 10, 240))):
            path = inputs / ("source_%d.png" % index); png(path, colour)
            sources.append({"assetId": "asset_%d" % index, "path": path.name,
                "bytes": path.stat().st_size, "sha256": archive.digest(path), "mime": "image/png"})
        request = {"schema": archive.REQUEST_SCHEMA, "requestId": request_id, "projectId": "project_one",
            "sourceProjectUpdatedAt": 7, "backend": "frame_sequence", "controls": {"fps": 9 if changed else 8, "width": 32, "height": 24}, "sources": sources}
        request_path = inputs / "request.json"
        request_path.write_text(json.dumps(request, indent=2))
        output = self.root / ("job_" + str(time.time_ns()) + ".vsjob")
        receipt = archive.pack_job(request_path, inputs, output)
        return output, request, receipt

    def test_exact_request_bytes_and_ordered_sources_survive_archive(self):
        output, request, receipt = self.job()
        accepted, sha = archive.unpack_job(output, self.root / "unpacked")
        self.assertEqual(accepted, request)
        self.assertEqual(sha, receipt["requestSha256"])
        with zipfile.ZipFile(output) as package:
            self.assertEqual(sha, hashlib.sha256(package.read("request.json")).hexdigest())
        self.assertNotEqual(sha, hashlib.sha256(archive.encode(request)).hexdigest())

    def test_duplicate_traversal_undeclared_and_changed_sources_reject(self):
        source, _, _ = self.job()
        for kind in ("duplicate", "traversal", "undeclared", "changed"):
            with self.subTest(kind=kind):
                target = self.root / (kind + ".vsjob")
                with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, "w") as modified, warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    for name in original.namelist():
                        raw = original.read(name)
                        if kind == "changed" and name == "source_0.png":
                            raw = raw[:-1] + bytes([raw[-1] ^ 1])
                        modified.writestr(name, raw)
                    if kind == "duplicate": modified.writestr("request.json", original.read("request.json"))
                    if kind == "traversal": modified.writestr("../outside.png", b"owned")
                    if kind == "undeclared": modified.writestr("unbound.json", b"{}")
                destination = self.root / (kind + "_unpacked")
                with self.assertRaises(ValueError): archive.unpack_job(target, destination)
                self.assertFalse(destination.exists())

    def test_malformed_zip_is_a_bounded_admission_error(self):
        source = self.root / "broken.vsjob"; source.write_bytes(b"not a ZIP")
        with self.assertRaisesRegex(ValueError, "INVALID_ZIP_ARCHIVE"):
            archive.unpack_job(source, self.root / "partial")
        self.assertFalse((self.root / "partial").exists())

    def test_symlink_sources_and_overwrite_never_package(self):
        source, _, _ = self.job()
        root = self.root / "unpacked"
        request, _ = archive.unpack_job(source, root)
        path = root / request["sources"][0]["path"]
        path.rename(self.root / "external.png"); path.symlink_to(self.root / "external.png")
        output = self.root / "result.vsjob"; output.write_bytes(b"accepted")
        with self.assertRaises(ValueError): archive.pack_job(root / "request.json", root, output)
        self.assertEqual(output.read_bytes(), b"accepted")

    def test_idempotency_is_durable_and_conflicting_request_cannot_replace_it(self):
        first, _, receipt = self.job()
        store = Store(self.config)
        initial = store.submit(first, archive_sha=receipt["sha256"], idempotency_key="request_one")
        self.assertEqual(Store(self.config).submit(first)["jobId"], initial["jobId"])
        second, _, _ = self.job(changed=True)
        with self.assertRaisesRegex(ValueError, "IDEMPOTENCY_CONFLICT"): store.submit(second)
        self.assertEqual(store.status(initial["jobId"])["state"], "queued")
        self.assertEqual(list((store.root / "incoming").iterdir()), [])

    def test_venv_interpreter_symlink_keeps_lexical_invocation_path(self):
        executable = self.root / "fakevenv" / "bin" / "python"
        executable.parent.mkdir(parents=True); executable.symlink_to(sys.executable)
        raw = archive.read_json(self.config_path); raw["pythonExecutable"] = str(executable)
        self.config_path.write_text(json.dumps(raw))
        self.assertEqual(load(self.config_path)["pythonExecutable"], str(executable))
        self.assertNotEqual(str(executable.resolve()), str(executable))

    def test_missing_model_reports_waiting_and_sources_do_not_become_neural_conditioning(self):
        request = {"schema": archive.REQUEST_SCHEMA, "requestId": "neural", "projectId": "project_one",
            "sourceProjectUpdatedAt": 0, "backend": "cogvideox", "sources": [], "controls": {"prompt": "A cube rotates",
            "width": 64, "height": 64, "numFrames": 9, "fps": 8, "seed": 17, "numInferenceSteps": 2, "guidanceScale": 6}}
        archive.validate_request(request)
        self.assertEqual(admission(self.config, request), ("waiting_model", "COGVIDEOX_NOT_CONFIGURED"))
        _, normal, _ = self.job(); request["sources"] = normal["sources"]
        with self.assertRaisesRegex(ValueError, "REFERENCE_CONDITIONING_UNAVAILABLE"): archive.validate_request(request)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "Actual FFmpeg is required")
    def test_real_worker_encoder_publishes_bound_immutable_result(self):
        source, request, receipt = self.job()
        runner = Runner(self.config)
        try:
            submitted = runner.store.submit(source)
            completed = runner.run_job(submitted["jobId"])
            self.assertEqual(completed["state"], "completed", completed)
            path = Path(completed["resultPath"])
            self.assertEqual(archive.digest(path), completed["resultSha256"])
            with zipfile.ZipFile(path) as result:
                manifest = archive.json_bytes(result.read("manifest.json"))
                self.assertEqual(manifest["requestSha256"], receipt["requestSha256"])
                self.assertEqual(manifest["sourceProjectUpdatedAt"], request["sourceProjectUpdatedAt"])
                self.assertTrue(manifest["actualExecution"]["video"]["decodedFrameCountVerified"])
                self.assertEqual(manifest["actualExecution"]["video"]["frameCount"], 3)
                self.assertFalse(manifest["actualExecution"]["neuralInference"])
                self.assertEqual(manifest["files"][0]["mime"], "video/mp4")
                self.assertEqual(hashlib.sha256(result.read(manifest["files"][0]["path"])).hexdigest(), manifest["files"][0]["sha256"])
            self.assertEqual(runner.run_job(submitted["jobId"])["resultSha256"], completed["resultSha256"])
        finally:
            runner.close()
        # Simulate process death after immutable publication but before its DB
        # completion receipt. Recovery must preserve the exact accepted bytes.
        with _connect(self.config["root"]) as connection:
            connection.execute("UPDATE jobs SET state='running',pid=NULL,start_ticks=NULL,result_sha=NULL,result_bytes=NULL WHERE job_id=?", (submitted["jobId"],))
        recovered = Runner(self.config)
        try:
            self.assertEqual(recovered.store.status(submitted["jobId"])["state"], "checkpointed")
            resumed = recovered.run_job(submitted["jobId"])
            self.assertEqual(resumed["state"], "completed", resumed)
            self.assertEqual(resumed["resultSha256"], completed["resultSha256"])
        finally:
            recovered.close()

    def test_completed_backend_checkpoint_requires_unchanged_bytes(self):
        work = self.root / "work"; work.mkdir()
        output = work / "master.mp4"; output.write_bytes(b"verified backend checkpoint")
        archive.atomic_json(work / "execution.json", {"outputs": [{"path": output.name, "role": "video",
            "mime": "video/mp4", "bytes": output.stat().st_size, "sha256": archive.digest(output)}], "actualExecution": {"backend": "fixture"}})
        request = {"backend": "frame_sequence", "controls": {}}
        outputs, proof = execute(self.config, request, self.root, work, lambda *args: None)
        self.assertEqual(outputs[0][0], output)
        output.write_bytes(b"changed")
        with self.assertRaises(ValueError): execute(self.config, request, self.root, work, lambda *args: None)

    def test_deadline_expires_before_any_child_execution(self):
        source, _, _ = self.job(); runner = Runner(self.config)
        try:
            submitted = runner.store.submit(source)
            with _connect(runner.store.root) as connection:
                connection.execute("UPDATE jobs SET deadline=? WHERE job_id=?", (time.time() - 1, submitted["jobId"]))
            runner.tick()
            self.assertEqual(runner.store.status(submitted["jobId"])["state"], "expired")
            self.assertFalse(runner.children)
        finally: runner.close()

    def test_cancel_terminates_real_worker_group_including_grandchild(self):
        source, _, _ = self.job(); store = Store(self.config)
        submitted = store.submit(source)
        marker = self.root / "grandchild.pid"
        code = "import signal,subprocess,sys,time;from pathlib import Path;signal.signal(signal.SIGTERM,signal.SIG_IGN);p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)']);Path(sys.argv[1]).write_text(str(p.pid));time.sleep(60)"
        process = subprocess.Popen([sys.executable, "-c", code, str(marker)], start_new_session=True)
        try:
            for _ in range(100):
                if marker.exists(): break
                time.sleep(.01)
            self.assertTrue(marker.exists())
            update(store.root, submitted["jobId"], state="running", pid=process.pid, start_ticks=_start_ticks(process.pid))
            cancelled = store.cancel(submitted["jobId"])
            self.assertEqual(cancelled["state"], "cancelled")
            process.wait(timeout=3)
            child = Path("/proc/%s/stat" % marker.read_text())
            if child.exists(): self.assertEqual(child.read_text().rsplit(")", 1)[1].split()[0], "Z")
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL); process.wait()

    def test_authenticated_http_submission_status_and_bodyless_cancel(self):
        source, _, receipt = self.job(); runner = Runner(self.config)
        token = "test-only-token-of-sufficient-length"
        server = BoundedServer(("127.0.0.1", 0), Handler, runner, token)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        def connection(): return http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        try:
            client = connection(); client.request("GET", "/v1/capabilities")
            response = client.getresponse(); self.assertEqual(response.status, 401); response.read(); client.close()
            client = connection()
            client.request("POST", "/v1/jobs", body=source.read_bytes(), headers={"Authorization": "Bearer " + token,
                "Content-Type": "application/vnd.videostudio.vsjob", "Idempotency-Key": "request_one", "X-VideoStudio-SHA256": receipt["sha256"]})
            response = client.getresponse(); self.assertEqual(response.status, 202)
            submitted = json.loads(response.read()); client.close()
            self.assertEqual(submitted["requestSha256"], receipt["requestSha256"])
            client = connection(); client.request("GET", "/v1/jobs/" + submitted["jobId"] + "/cancel", headers={"Authorization": "Bearer " + token})
            response = client.getresponse(); self.assertEqual(response.status, 405); response.read(); client.close()
            self.assertEqual(runner.store.status(submitted["jobId"])["state"], "queued")
            client = connection(); client.putrequest("POST", "/v1/jobs/" + submitted["jobId"] + "/cancel")
            client.putheader("Authorization", "Bearer " + token); client.endheaders()
            response = client.getresponse(); raw = response.read(); client.close()
            self.assertEqual(response.status, 200, raw)
            self.assertEqual(json.loads(raw)["state"], "cancelled")
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=3); runner.close()


if __name__ == "__main__":
    unittest.main()
