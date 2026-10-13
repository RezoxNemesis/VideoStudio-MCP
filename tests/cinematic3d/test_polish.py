"""Real HTTP and subprocess contracts; no actual model/GPU readiness is invented."""
import hashlib
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
import zlib

try:
    polish = importlib.import_module("cinematic3d.polish")
except ModuleNotFoundError:
    polish = None


def png(rgb):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1,1,8,2,0,0,0)) + chunk(b"IDAT", zlib.compress(bytes([0,*rgb]))) + chunk(b"IEND", b"")


class PolishTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(polish, "Actual optional polishing adapters are missing")
        self.temp = tempfile.TemporaryDirectory(prefix="c3d-polish-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.frames = []
        for i, color in enumerate(((255,0,0),(0,255,0),(0,0,255),(255,255,0))):
            path = self.directory / (str(i) + ".png")
            path.write_bytes(png(color)); self.frames.append(path)
        self.weights = self.directory / "weights.bin"; self.weights.write_bytes(b"model-fixture")
        self.sha = hashlib.sha256(self.weights.read_bytes()).hexdigest()

    def runner(self, suffix="", **kwargs):
        path = self.directory / "interpolate.py"
        path.write_text('''import argparse,json,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--input');p.add_argument('--output');p.add_argument('--weights');a=p.parse_args()
r=json.loads(Path(a.input).read_text());o=Path(a.output);o.mkdir(parents=True,exist_ok=True)
with (o.parent.parent/'calls.jsonl').open('a') as log:log.write(json.dumps(r)+'\\n')
files=[]
for i,t in enumerate(r['outputSampleTimesSeconds']):
 target=o/(str(i)+'.png');shutil.copyfile(r['frames'][min(len(r['frames'])-1,int(t*r['inputFps']))],target);files.append(target.name)
result={'schema':'videostudio.cinematic3d.interpolation.v1','fps':r['outputFps'],'frameCount':len(files),'durationSeconds':len(files)/r['outputFps'],'frames':files}
''' + suffix + "\n(o/'manifest.json').write_text(json.dumps(result))\n", encoding="utf-8")
        values = dict(family="rife", command=(sys.executable, str(path)), weights=self.weights,
                      weights_sha256=self.sha, timeout_seconds=3)
        values.update(kwargs)
        return polish.InterpolationConfig(**values)

    def test_interpolation_executes_child_and_checks_exact_clock(self):
        result = polish.interpolate_frames(self.runner(), self.frames[:3], 24, 48, self.directory / "out")
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["frameCount"], 6)
        self.assertEqual(result["fps"], 48)
        self.assertAlmostEqual(result["durationSeconds"], 3/24)
        self.assertEqual(len(result["frames"]), 6)
        self.assertTrue(all(Path(f["path"]).is_file() for f in result["frames"]))

    def test_cuts_split_runner_and_hit_stop_frames_are_copied(self):
        output = self.directory / "out"
        result = polish.interpolate_frames(self.runner(), self.frames, 24, 48, output, cuts=(2,), hit_stop_ranges=((2,4),))
        self.assertEqual(result["status"], "completed")
        calls = [json.loads(line) for line in (output / "calls.jsonl").read_text().splitlines()]
        self.assertEqual([len(c["frames"]) for c in calls], [2])
        self.assertEqual(Path(result["frames"][3]["path"]).read_bytes(), self.frames[1].read_bytes())
        for index, source in ((4,2),(5,2),(6,3),(7,3)):
            self.assertEqual(Path(result["frames"][index]["path"]).read_bytes(), self.frames[source].read_bytes())
        self.assertEqual(result["protectedOutputFrames"], [4,5,6,7])

    def test_rational_24_to_60_clock_is_endpoint_exclusive(self):
        result = polish.interpolate_frames(self.runner(), self.frames, 24, 60, self.directory / "out", cuts=(1,))
        self.assertEqual(result["frameCount"], 10)
        self.assertAlmostEqual(result["durationSeconds"], 4/24)
        self.assertEqual(Path(result["frames"][2]["path"]).read_bytes(), self.frames[0].read_bytes())

    def test_malformed_runner_count_and_timeout_fail(self):
        result = polish.interpolate_frames(self.runner("result['frameCount']-=1"), self.frames[:3], 24, 48, self.directory / "bad")
        self.assertEqual(result["code"], "INVALID_INTERPOLATION_OUTPUT")
        result = polish.interpolate_frames(self.runner("import time;time.sleep(5)", timeout_seconds=.1), self.frames[:3], 24, 48, self.directory / "timeout")
        self.assertEqual(result["code"], "RUNNER_TIMEOUT")

    def test_missing_interpolation_provider_is_unavailable(self):
        self.assertEqual(polish.interpolate_frames(None, self.frames, 24, 48, self.directory / "out")["status"], "unavailable")

    def test_all_hold_frames_do_not_claim_a_model_runner_executed(self):
        result = polish.interpolate_frames(self.runner(), self.frames, 24, 48, self.directory / "held", hit_stop_ranges=((0,4),))
        self.assertEqual(result["status"], "completed")
        self.assertFalse(result["evidence"]["runnerExecuted"])
        self.assertEqual(result["evidence"]["runnerInvocationCount"], 0)

    def comfy_config(self, mode="complete", timeout=3):
        state = {"requests":[], "mode":mode, "image":self.frames[0].read_bytes()}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def send_json(self, obj, status=200):
                body=json.dumps(obj).encode(); self.send_response(status);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(body)));self.end_headers();self.wfile.write(body)
            def do_GET(self):
                state["requests"].append(("GET",self.path))
                if self.path=="/system_stats" and state["mode"] in ("trickle_body","trickle_headers"):
                    body=json.dumps({"system":{"comfyui_version":"test"},"devices":[]}).encode()
                    try:
                        if state["mode"]=="trickle_headers":
                            payload=("HTTP/1.0 200 OK\r\nContent-Length: "+str(len(body))+"\r\n\r\n").encode()+body
                        else:
                            self.send_response(200);self.send_header("Content-Length",str(len(body)));self.end_headers();payload=body
                        for value in payload:
                            self.wfile.write(bytes([value]));self.wfile.flush();time.sleep(.02)
                    except (BrokenPipeError,ConnectionResetError): pass
                    return
                if self.path=="/system_stats": return self.send_json({"system":{"comfyui_version":"test"},"devices":[]})
                if self.path=="/object_info": return self.send_json({"LoadImage":{},"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["weights.bin"]]}}},"SaveImage":{},"VAEEncode":{},"VAEDecode":{}})
                if self.path.startswith("/history/"):
                    if state["mode"]=="pending": return self.send_json({})
                    if state["mode"]=="error": return self.send_json({"prompt-fixture":{"status":{"completed":False,"status_str":"error","messages":[["execution_error",{}]]}}})
                    return self.send_json({"prompt-fixture":{"status":{"completed":True,"status_str":"success"},"outputs":{"3":{"images":[{"filename":"result.png","subfolder":"","type":"output"}]}}}})
                if self.path.startswith("/view?"):
                    self.send_response(200);self.send_header("Content-Length",str(len(state["image"])));self.end_headers();self.wfile.write(state["image"]);return
                return self.send_json({},404)
            def do_POST(self):
                length=int(self.headers["Content-Length"]);body=self.rfile.read(length);state["requests"].append(("POST",self.path))
                if self.path=="/upload/image":
                    state["uploaded"]=body;return self.send_json({"name":"uploaded.png","subfolder":"","type":"input"})
                if self.path=="/prompt":
                    state["prompt"]=json.loads(body);return self.send_json({"prompt_id":"prompt-fixture"})
                return self.send_json({},404)
        server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        self.addCleanup(server.server_close);self.addCleanup(server.shutdown)
        graph={"1":{"class_type":"LoadImage","inputs":{"image":"unused.png"}},
               "2":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"weights.bin"}},
               "3":{"class_type":"SaveImage","inputs":{"images":["5",0],"filename_prefix":"Cinematic"}},
               "4":{"class_type":"VAEEncode","inputs":{"pixels":["1",0],"vae":["2",2]}},
               "5":{"class_type":"VAEDecode","inputs":{"samples":["4",0],"vae":["2",2]}}}
        workflow=self.directory / "workflow.json";workflow.write_text(json.dumps(graph))
        model=polish.ComfyModel(path=self.weights,sha256=self.sha,node_id="2",input_name="ckpt_name")
        config=polish.ComfyUIConfig(endpoint="http://127.0.0.1:"+str(server.server_port),workflow_file=workflow,
            workflow_sha256=hashlib.sha256(workflow.read_bytes()).hexdigest(),allowed_node_types=("LoadImage","CheckpointLoaderSimple","SaveImage","VAEEncode","VAEDecode"),
            models=(model,),image_input_binding=("1","image"),output_nodes=("3",),timeout_seconds=timeout,poll_interval_seconds=.01)
        return config,state

    def test_comfyui_uploads_vetted_graph_waits_history_and_fetches_actual_output(self):
        config,state=self.comfy_config()
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "comfy")
        self.assertEqual(result["status"],"completed")
        self.assertIn(("GET","/system_stats"),state["requests"])
        self.assertIn(("GET","/object_info"),state["requests"])
        self.assertEqual(state["prompt"]["prompt"]["1"]["inputs"]["image"],"uploaded.png")
        self.assertIn(self.frames[0].read_bytes(),state["uploaded"])
        self.assertEqual(Path(result["outputs"][0]["path"]).read_bytes(),self.frames[0].read_bytes())
        self.assertFalse(result["evidence"]["originalImageReconstruction"])

    def test_comfy_history_timeout_and_execution_error_are_explicit(self):
        config,state=self.comfy_config("pending",timeout=.15)
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "pending")
        self.assertEqual(result["code"],"COMFYUI_TIMEOUT")
        state["mode"]="error"
        # This phase tests an actual history error, not a 150 ms worker-startup
        # race across readiness/upload/submission/history. Keep the short timeout
        # above, and give this independently bounded error request its own budget.
        config=replace(config,timeout_seconds=3)
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "error")
        self.assertEqual(result["code"],"COMFYUI_EXECUTION_FAILED")

    def test_changed_workflow_and_missing_model_never_submit(self):
        config,state=self.comfy_config()
        config.workflow_file.write_text("{}")
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "changed")
        self.assertEqual(result["code"],"WORKFLOW_SHA256_MISMATCH")
        self.assertFalse(state["requests"])
        config,state=self.comfy_config();self.weights.unlink()
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "missing")
        self.assertEqual(result["status"],"unavailable")
        self.assertEqual(result["code"],"MODEL_MISSING")
        self.assertFalse(state["requests"])

    def test_disconnected_model_is_rejected_before_submission(self):
        config,state=self.comfy_config()
        graph=json.loads(config.workflow_file.read_text())
        graph["3"]["inputs"]["images"]=["1",0]
        config.workflow_file.write_text(json.dumps(graph))
        config=replace(config,workflow_sha256=hashlib.sha256(config.workflow_file.read_bytes()).hexdigest())
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "disconnected")
        self.assertEqual(result["code"],"DISCONNECTED_MODEL_BINDING")
        self.assertFalse(state["requests"])

    def test_uploaded_source_must_reach_configured_outputs(self):
        config,state=self.comfy_config()
        graph=json.loads(config.workflow_file.read_text())
        graph["4"]["inputs"]["pixels"]="unbound pixels"
        config.workflow_file.write_text(json.dumps(graph))
        config=replace(config,workflow_sha256=hashlib.sha256(config.workflow_file.read_bytes()).hexdigest())
        result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / "unbound_source")
        self.assertEqual(result["code"],"DISCONNECTED_IMAGE_BINDING")
        self.assertFalse(state["requests"])

    def test_absolute_deadline_bounds_trickled_body_and_headers(self):
        for mode in ("trickle_body","trickle_headers"):
            with self.subTest(mode=mode):
                config,_=self.comfy_config(mode,timeout=.12)
                started=time.monotonic()
                result=polish.run_comfyui(config,self.frames[0],hashlib.sha256(self.frames[0].read_bytes()).hexdigest(),self.directory / mode)
                elapsed=time.monotonic()-started
                self.assertEqual(result["code"],"COMFYUI_TIMEOUT")
                self.assertLess(elapsed,.4,"A socket idle timeout does not bound a trickling peer")


if __name__ == "__main__":
    unittest.main()
