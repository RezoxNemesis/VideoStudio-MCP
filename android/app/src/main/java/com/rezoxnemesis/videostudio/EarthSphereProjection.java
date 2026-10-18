package com.rezoxnemesis.videostudio;

/**
 * Deterministic, source-texture-based orthographic Earth inverse projection.
 *
 * No Android dependencies: test the actual geographic mapping independently
 * from the eventual GLES3 and bounded CPU renderers. Screen +Y points down;
 * sphere +Y / geographic latitude points north. Longitude is east positive.
 */
public final class EarthSphereProjection {
    private EarthSphereProjection() {}

    public static final class Point {
        public final boolean visible;
        public final double viewX, viewY, viewZ;
        public final double latitudeDeg, longitudeDeg, texU, texV;
        private Point(boolean visible, double x, double y, double z,
                      double latitude, double longitude, double u, double v) {
            this.visible = visible;
            this.viewX = x; this.viewY = y; this.viewZ = z;
            this.latitudeDeg = latitude; this.longitudeDeg = longitude;
            this.texU = u; this.texV = v;
        }
    }

    /** centerLongitude already includes the camera-yaw subtraction in the film CSV. */
    public static Point project(double px, double py, double cx, double cy,
                                double radius, double axisTiltDeg,
                                double centerLongitudeDeg) {
        if (!(radius > 0) || !Double.isFinite(radius)
                || !Double.isFinite(px) || !Double.isFinite(py)
                || !Double.isFinite(centerLongitudeDeg)
                || !Double.isFinite(axisTiltDeg)) {
            throw new IllegalArgumentException("Finite Earth projection parameters required");
        }
        final double vx = (px - cx) / radius;
        final double vy = (cy - py) / radius;
        final double q = vx * vx + vy * vy;
        if (q > 1.0) return new Point(false, vx, vy, 0, 0, 0, 0, 0);
        final double vz = Math.sqrt(Math.max(0, 1.0 - q));
        final double tilt = Math.toRadians(axisTiltDeg);
        final double c = Math.cos(tilt), s = Math.sin(tilt);
        // Screen-space axial tilt inverted to recover Earth geographic axes.
        final double ex = vx * c + vy * s;
        final double ey = -vx * s + vy * c;
        final double latitude = Math.toDegrees(Math.asin(clamp(ey, -1, 1)));
        final double longitude = wrapLongitude(centerLongitudeDeg
                + Math.toDegrees(Math.atan2(ex, vz)));
        final double u = wrap01((longitude + 180.0) / 360.0);
        final double v = clamp((90.0 - latitude) / 180.0, 0.0, 1.0);
        return new Point(true, vx, vy, vz, latitude, longitude, u, v);
    }

    /** Wrap east-positive degrees to [-180,180) without pole/mirrored seam artifacts. */
    public static double wrapLongitude(double degrees) {
        double v = (degrees + 180.0) % 360.0;
        if (v < 0) v += 360.0;
        return v - 180.0;
    }

    public static double wrap01(double v) {
        double out = v % 1.0;
        return out < 0 ? out + 1.0 : out;
    }

    public static double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }

    public static double smoothstep(double low, double high, double x) {
        if (!(high > low)) throw new IllegalArgumentException("Invalid smoothstep limits");
        double t = clamp((x - low) / (high - low), 0, 1);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Sun vector is camera/view-space (from canonical frame CSV). */
    public static double sunCosine(Point sample, double sunX, double sunY, double sunZ) {
        if (!sample.visible) return -1;
        double d = Math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ);
        if (!(d > 0)) throw new IllegalArgumentException("Sun vector must be nonzero");
        return (sample.viewX * sunX + sample.viewY * sunY + sample.viewZ * sunZ) / d;
    }

    public static double daylight(double cosine) {
        return smoothstep(-0.10, 0.10, cosine);
    }

    /** Seam-wrapping bilinear RGB sampler; horizontal wraps, latitude clamps. */
    public static int bilinear(int[] pixels, int width, int height, double u, double v) {
        if (pixels == null || width < 2 || height < 2 || pixels.length != width * height)
            throw new IllegalArgumentException("Invalid equirectangular texture array");
        final double sx = wrap01(u) * width - 0.5;
        final double sy = clamp(v, 0, 1) * (height - 1);
        final int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
        final int x1 = x0 + 1, y1 = Math.min(height - 1, y0 + 1);
        final double tx = sx - x0, ty = sy - y0;
        final int ax = Math.floorMod(x0, width), bx = Math.floorMod(x1, width);
        final int a = pixels[y0 * width + ax], b = pixels[y0 * width + bx];
        final int c = pixels[y1 * width + ax], d = pixels[y1 * width + bx];
        final int R = lerpChannel(a >> 16, b >> 16, c >> 16, d >> 16, tx, ty);
        final int G = lerpChannel(a >> 8, b >> 8, c >> 8, d >> 8, tx, ty);
        final int B = lerpChannel(a, b, c, d, tx, ty);
        return 0xff000000 | (R << 16) | (G << 8) | B;
    }

    private static int lerpChannel(int a, int b, int c, int d, double tx, double ty) {
        double top = (a & 255) * (1 - tx) + (b & 255) * tx;
        double bottom = (c & 255) * (1 - tx) + (d & 255) * tx;
        return (int) clamp(Math.round(top * (1 - ty) + bottom * ty), 0, 255);
    }
}
