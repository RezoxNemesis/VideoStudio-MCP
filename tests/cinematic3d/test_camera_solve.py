"""Actual calibrated OpenCV solves, including geometric failure gates."""
import importlib
import copy
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import numpy as np

try:
    camera_solve = importlib.import_module("cinematic3d.camera_solve")
except ModuleNotFoundError:
    camera_solve = None


class CameraSolveTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(camera_solve, "Actual calibrated camera solver is missing")
        import cv2
        self.cv2 = cv2
        self.points = np.array([[-1,-1,0],[1,-1,0],[-1,1,0],[1,1,0],[-.8,-.7,1],
                                [.8,-.7,1],[-.8,.7,1],[.8,.7,1]], dtype=np.float64)
        self.intrinsics = np.array([[800,0,320],[0,800,240],[0,0,1]], dtype=np.float64)
        self.rvec = np.array([.12,-.09,.04], dtype=np.float64)
        self.tvec = np.array([.1,-.2,6.0], dtype=np.float64)
        self.pixels = cv2.projectPoints(self.points, self.rvec, self.tvec, self.intrinsics, None)[0].reshape(-1,2)

    def test_recovers_real_extrinsics_and_measured_residual(self):
        result = camera_solve.solve_camera(self.points.tolist(), self.pixels.tolist(), self.intrinsics.tolist())
        self.assertEqual(result["status"], "completed")
        np.testing.assert_allclose(result["rvec"], self.rvec, atol=1e-5)
        np.testing.assert_allclose(result["tvec"], self.tvec, atol=1e-5)
        self.assertLess(result["maxReprojectionErrorPx"], 1e-5)
        self.assertEqual(len(result["residualsPx"]), len(self.points))
        rotation = self.cv2.Rodrigues(self.rvec)[0]
        np.testing.assert_allclose(result["cameraPosition"], -rotation.T @ self.tvec, atol=1e-5)

    def test_collinear_correspondences_are_rejected(self):
        points = [[float(i),0,0] for i in range(6)]
        with self.assertRaises(camera_solve.CameraSolveError):
            camera_solve.solve_camera(points, [[i*20,240] for i in range(6)], self.intrinsics.tolist())

    def test_inaccurate_correspondences_fail_residual_gate(self):
        pixels = self.pixels.copy()
        pixels[0] += [35,-25]
        with self.assertRaises(camera_solve.CameraSolveError):
            camera_solve.solve_camera(self.points.tolist(), pixels.tolist(), self.intrinsics.tolist(), max_reprojection_error_px=1)

    def test_nonfinite_and_invalid_intrinsics_are_rejected(self):
        bad = self.intrinsics.copy(); bad[0,0] = -1
        with self.assertRaises(camera_solve.CameraSolveError):
            camera_solve.solve_camera(self.points.tolist(), self.pixels.tolist(), bad.tolist())
        points = self.points.copy(); points[0,0] = float("nan")
        with self.assertRaises(camera_solve.CameraSolveError):
            camera_solve.solve_camera(points.tolist(), self.pixels.tolist(), self.intrinsics.tolist())

    def camera_adapter(self):
        adapter=getattr(camera_solve,"camera_keyframe",None)
        self.assertTrue(callable(adapter),"Measured PnP-to-timeline camera adapter is missing")
        return adapter

    def centered_solve(self):
        matrix=self.intrinsics.copy();matrix[0,0]=400;matrix[1,1]=400
        pixels=self.cv2.projectPoints(self.points,self.rvec,self.tvec,matrix,None)[0].reshape(-1,2)
        result=camera_solve.solve_camera(self.points.tolist(),pixels.tolist(),matrix.tolist())
        return result,pixels

    def test_actual_pnp_keyframe_rays_match_calibrated_image_pixels(self):
        adapter=self.camera_adapter()
        receipt,pixels=self.centered_solve()
        result=adapter(receipt,640,480,.35)
        camera=result["camera"];key=camera["keyframes"][0]
        self.assertAlmostEqual(camera["fovDegrees"],math.degrees(2*math.atan(480/(2*400))),places=8)
        self.assertEqual(key["phase"],.35)
        position=np.asarray(key["position"])
        forward=np.asarray(key["target"])-position;forward/=np.linalg.norm(forward)
        up=np.asarray(key["up"])
        right=np.cross(forward,up);right/=np.linalg.norm(right)
        corrected_up=np.cross(right,forward)
        relative=self.points-position
        depth=relative@forward
        focal=480/(2*math.tan(math.radians(camera["fovDegrees"])/2))
        projected=np.column_stack([320+(relative@right)*focal/depth,240-(relative@corrected_up)*focal/depth])
        np.testing.assert_allclose(projected,pixels,atol=1e-5)
        self.assertEqual(result["cameraProjection"]["model"],"centered_pinhole")
        self.assertEqual(result["cameraProjection"]["units"],"metres")
        self.assertEqual(result["cameraProjection"]["worldUp"],"Y")
        self.assertAlmostEqual(result["cameraProjection"]["viewportAspectRatio"],640/480)

    def test_lens_adapter_rejects_off_center_unequal_focals_and_out_of_range_fov(self):
        adapter=self.camera_adapter()
        receipt,_=self.centered_solve()
        for kind in ("off_center","unequal_focals","narrow_fov","skew"):
            with self.subTest(kind=kind):
                altered=copy.deepcopy(receipt)
                if kind=="off_center": altered["intrinsics"][0][2]+=1
                elif kind=="unequal_focals": altered["intrinsics"][0][0]+=1
                elif kind=="narrow_fov": altered["intrinsics"][0][0]=altered["intrinsics"][1][1]=800
                else: altered["intrinsics"][0][1]=.01
                with self.assertRaises(camera_solve.CameraSolveError): adapter(altered,640,480,0)

    def test_camera_adapter_rejects_unmeasured_receipts_bad_rotations_and_invalid_phase(self):
        adapter=self.camera_adapter()
        receipt,_=self.centered_solve()
        for kind in ("unmeasured","unavailable","reflection","scaled_rotation","nonfinite_position"):
            with self.subTest(kind=kind):
                altered=copy.deepcopy(receipt)
                if kind=="unmeasured": altered["measured"]=False
                elif kind=="unavailable": altered["status"]="unavailable"
                elif kind=="reflection":
                    altered["cameraRotationMatrixYUp"]=np.asarray(altered["cameraRotationMatrixYUp"])@np.diag([1,1,-1])
                    altered["cameraRotationMatrixYUp"]=altered["cameraRotationMatrixYUp"].tolist()
                elif kind=="scaled_rotation": altered["cameraRotationMatrixYUp"][0][0]*=2
                else: altered["cameraPosition"][0]=float("nan")
                with self.assertRaises(camera_solve.CameraSolveError): adapter(altered,640,480,0)
        for phase in (True,-.01,1.01,float("nan")):
            with self.subTest(phase=phase):
                with self.assertRaises(camera_solve.CameraSolveError): adapter(receipt,640,480,phase)
        with self.assertRaises(camera_solve.CameraSolveError): adapter(receipt,True,480,0)

    def test_real_camera_cli_emits_timeline_patch_only_with_complete_viewport(self):
        receipt,pixels=self.centered_solve()
        request={"points3d":self.points.tolist(),"points2d":pixels.tolist(),"intrinsics":receipt["intrinsics"],
                 "imageWidth":640,"imageHeight":480,"phase":.5}
        root=Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="c3d-camera-cli-") as temporary:
            source=Path(temporary)/"request.json";output=Path(temporary)/"receipt.json"
            source.write_text(json.dumps(request))
            run=subprocess.run([sys.executable,"-m","cinematic3d","camera","--request",str(source),"--output",str(output)],
                               cwd=root,capture_output=True,text=True,timeout=15)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
            result=json.loads(output.read_text())
            self.assertEqual(result["camera"]["keyframes"][0]["phase"],.5)
            self.assertTrue(result["measured"])
            self.assertFalse(result["nativeRendered"])
            for remove in ("imageHeight","imageWidth"):
                malformed=dict(request);malformed.pop(remove)
                source.write_text(json.dumps(malformed))
                run=subprocess.run([sys.executable,"-m","cinematic3d","camera","--request",str(source)],cwd=root,
                                   capture_output=True,text=True,timeout=15)
                self.assertEqual(run.returncode,2)
                self.assertEqual(json.loads(run.stdout)["code"],"INVALID_INPUT")


if __name__ == "__main__":
    unittest.main()
