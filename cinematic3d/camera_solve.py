"""Calibrated 3D/2D camera solving with actual measured reprojection gates."""
import math


class CameraSolveError(ValueError):
    pass


def _finite_number(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise CameraSolveError(label + " must be finite numeric data")
    return float(value)


def _vector(value, size, label):
    if not isinstance(value, (list, tuple)) or len(value) != size:
        raise CameraSolveError(label + " has the wrong dimensions")
    return [_finite_number(component, label) for component in value]


def _matrix(value, label):
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise CameraSolveError(label + " must be a 3x3 matrix")
    return [_vector(row, 3, label) for row in value]


def camera_keyframe(receipt, imageWidth, imageHeight, phase=0.0):
    """Convert a measured PnP receipt to a fixed-FOV timeline camera patch.

    Supplied world points/roots must use right-handed Y-up metres. The timeline
    perspective lens supports a centered principal point at ``(width/2,height/2)``,
    zero skew, square pixels and no distortion; off-center or unequal-focal lenses
    cannot be expressed exactly and are rejected. Replay must use the declared
    viewport aspect ratio: changing it changes the horizontal projection. This
    patch preserves measured camera orientation, including roll, via ``up``. It
    does not establish original-image reconstruction or native rendering evidence.
    """
    if not isinstance(receipt, dict) or receipt.get("status") != "completed" or receipt.get("measured") is not True or receipt.get("provider") != "opencv_solvepnp":
        raise CameraSolveError("A completed measured OpenCV PnP receipt is required")
    if any(type(value) is not int or not 1 <= value <= 8192 for value in (imageWidth, imageHeight)):
        raise CameraSolveError("Image dimensions must be integers within 1..8192")
    phase = _finite_number(phase, "Keyframe phase")
    if not 0 <= phase <= 1:
        raise CameraSolveError("Keyframe phase must be within 0..1")
    position = _vector(receipt.get("cameraPosition"), 3, "Camera position")
    if any(abs(component) > 10000 for component in position):
        raise CameraSolveError("Camera position exceeds the metre world bounds")
    rotation = _matrix(receipt.get("cameraRotationMatrixYUp"), "Y-up camera rotation")
    for first in range(3):
        for second in range(3):
            dot = sum(rotation[row][first] * rotation[row][second] for row in range(3))
            if abs(dot - (1 if first == second else 0)) > 1e-6:
                raise CameraSolveError("Camera rotation must be orthonormal")
    determinant = (
        rotation[0][0] * (rotation[1][1]*rotation[2][2] - rotation[1][2]*rotation[2][1])
        - rotation[0][1] * (rotation[1][0]*rotation[2][2] - rotation[1][2]*rotation[2][0])
        + rotation[0][2] * (rotation[1][0]*rotation[2][1] - rotation[1][1]*rotation[2][0]))
    if abs(determinant - 1) > 1e-6:
        raise CameraSolveError("Camera rotation must preserve right-handed orientation")
    matrix = _matrix(receipt.get("intrinsics"), "Camera intrinsics")
    fx, fy = matrix[0][0], matrix[1][1]
    if (fx <= 0 or fy <= 0 or abs(matrix[0][1]) > 1e-12 or abs(matrix[1][0]) > 1e-12 or
            any(abs(value-expected) > 1e-12 for value, expected in zip(matrix[2], (0,0,1)))):
        raise CameraSolveError("Unsupported perspective intrinsics")
    if (not math.isclose(fx, fy, rel_tol=1e-9, abs_tol=1e-9) or
            abs(matrix[0][2] - imageWidth/2) > 1e-6 or abs(matrix[1][2] - imageHeight/2) > 1e-6):
        raise CameraSolveError("Timeline cameras require centered principal points and square pixels")
    fov = math.degrees(2*math.atan(imageHeight/(2*fy)))
    if not 35 <= fov <= 100:
        raise CameraSolveError("Measured vertical FOV is outside the timeline's 35..100 degree range")
    forward = [-rotation[row][2] for row in range(3)]
    up = [rotation[row][1] for row in range(3)]
    forward_norm = math.sqrt(sum(value*value for value in forward))
    up_norm = math.sqrt(sum(value*value for value in up))
    forward = [value/forward_norm for value in forward]
    up = [value/up_norm for value in up]
    target = [value+direction for value,direction in zip(position,forward)]
    maximum = _finite_number(receipt.get("maxReprojectionErrorPx"), "Measured reprojection error")
    if maximum < 0:
        raise CameraSolveError("Measured reprojection error cannot be negative")
    return {"camera": {"fovDegrees": fov, "keyframes": [{"phase": phase, "position": position,
                    "target": target, "up": up}]},
            "cameraProjection": {"model": "centered_pinhole", "imageWidth": imageWidth, "imageHeight": imageHeight,
                    "viewportAspectRatio": imageWidth/imageHeight, "requiredViewportAspectRatio": imageWidth/imageHeight,
                    "viewportAspectMustMatch": True, "fovAxis": "vertical", "units": "metres", "worldUp": "Y",
                    "handedness": "right", "forwardAxis": "-Z", "distortion": "assumed_none",
                    "maxReprojectionErrorPx": maximum, "originalImageReconstruction": False}}


def solve_camera(points3d, points2d, intrinsics, *, max_reprojection_error_px=2.0):
    """Solve world-to-OpenCV extrinsics; world coordinates retain the supplied units.

    ``cameraRotationMatrix`` maps OpenCV camera coordinates (+Z forward, +Y down)
    to the supplied world. ``cameraRotationMatrixYUp`` maps a glTF/Blender camera
    (+Y up, -Z forward) to that same world. No pixel measurement means no solve.
    """
    try:
        import numpy as np
        import cv2
    except ImportError:
        return {"status": "unavailable", "code": "OPENCV_UNAVAILABLE", "provider": "opencv_solvepnp"}
    try:
        world = np.asarray(points3d, dtype=np.float64)
        pixels = np.asarray(points2d, dtype=np.float64)
        matrix = np.asarray(intrinsics, dtype=np.float64)
        if world.ndim != 2 or world.shape[1:] != (3,) or not 6 <= len(world) <= 10000 or pixels.shape != (len(world), 2) or matrix.shape != (3,3):
            raise CameraSolveError("At least six corresponding 3D/2D points and a 3x3 intrinsic matrix are required")
        if not all(np.isfinite(item).all() for item in (world,pixels,matrix)):
            raise CameraSolveError("Camera inputs must be finite")
        if (matrix[0,0] <= 0 or matrix[1,1] <= 0 or not np.allclose(matrix[2], [0,0,1], atol=1e-12) or
                abs(matrix[0,1]) > 1e-12 or abs(matrix[1,0]) > 1e-12):
            raise CameraSolveError("Intrinsics require positive focal lengths and zero skew")
        if isinstance(max_reprojection_error_px, bool) or not isinstance(max_reprojection_error_px, (int,float)) or not math.isfinite(max_reprojection_error_px) or max_reprojection_error_px <= 0:
            raise CameraSolveError("A finite positive residual threshold is required")
        for points in (world,pixels):
            singular = np.linalg.svd(points - points.mean(axis=0), compute_uv=False)
            if singular[0] <= 1e-8 or singular[1] <= max(1e-8,singular[0]*1e-6):
                raise CameraSolveError("Degenerate or nearly collinear camera correspondences")
        if len(np.unique(world, axis=0)) < 6 or len(np.unique(pixels, axis=0)) < 6:
            raise CameraSolveError("Camera correspondences must be distinct")
        ok,rvec,tvec = cv2.solvePnP(world,pixels,matrix,None,flags=cv2.SOLVEPNP_ITERATIVE)
        if not ok:
            raise CameraSolveError("OpenCV could not solve the camera")
        rvec,tvec = cv2.solvePnPRefineLM(world,pixels,matrix,None,rvec,tvec)
        rotation = cv2.Rodrigues(rvec)[0]
        transformed = (rotation @ world.T + tvec).T
        if not np.isfinite(transformed).all() or (transformed[:,2] <= 1e-6).any():
            raise CameraSolveError("Camera solution places supplied points behind the camera")
        projected = cv2.projectPoints(world,rvec,tvec,matrix,None)[0].reshape(-1,2)
        residuals = np.linalg.norm(projected-pixels,axis=1)
        maximum = float(residuals.max())
        if not math.isfinite(maximum) or maximum > max_reprojection_error_px:
            raise CameraSolveError("Camera reprojection residual exceeds the configured threshold")
        return {"status": "completed", "provider": "opencv_solvepnp", "rvec":rvec.reshape(3).tolist(),
                "tvec":tvec.reshape(3).tolist(), "cameraPosition":(-rotation.T @ tvec).reshape(3).tolist(),
                "cameraRotationMatrix":rotation.T.tolist(), "cameraRotationMatrixYUp":(rotation.T @ np.diag([1,-1,-1])).tolist(),
                "intrinsics":matrix.tolist(), "residualsPx":residuals.tolist(), "maxReprojectionErrorPx":maximum,
                "rmsReprojectionErrorPx":float(np.sqrt(np.mean(residuals**2))), "measured":True,
                "pointCount":len(world), "originalImageReconstruction":False}
    except CameraSolveError:
        raise
    except (ValueError, TypeError, OverflowError, cv2.error, np.linalg.LinAlgError) as error:
        raise CameraSolveError("Invalid camera inputs or unsolved geometry") from error
