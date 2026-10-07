package com.rezoxnemesis.videostudio;

/** Deterministic geometry shared by preview, still generation and video export. */
public final class SceneMath {
    private SceneMath() {}
    public static double ease(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }
    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public static double[] rotate(double x, double y, double z, double rx, double ry, double rz) {
        double cy = Math.cos(rx), sy = Math.sin(rx), yy = y * cy - z * sy, zz = y * sy + z * cy;
        y = yy; z = zz; cy = Math.cos(ry); sy = Math.sin(ry);
        double xx = x * cy + z * sy; zz = -x * sy + z * cy;
        x = xx; z = zz; cy = Math.cos(rz); sy = Math.sin(rz);
        return new double[]{x * cy - y * sy, x * sy + y * cy, z};
    }
    /** Pinhole camera. Near-plane clipping is explicit; no exploding polygons. */
    public static double[] project(double x, double y, double z, double fovDegrees, double aspect) {
        if (z <= .05 || !Double.isFinite(x + y + z)) return null;
        double focal = 1 / Math.tan(Math.toRadians(fovDegrees) / 2);
        return new double[]{.5 + x * focal / (2 * z * aspect), .5 - y * focal / (2 * z)};
    }
    public static double diffuse(double[] a, double[] b, double[] c) {
        double ux = b[0]-a[0], uy = b[1]-a[1], uz = b[2]-a[2];
        double vx = c[0]-a[0], vy = c[1]-a[1], vz = c[2]-a[2];
        double nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
        double length = Math.sqrt(nx*nx+ny*ny+nz*nz);
        return length < 1e-9 ? .25 : .25 + .75 * Math.max(0, (nx * -.4 + ny * .7 + nz * -.59) / length);
    }
}
