package com.rezoxnemesis.videostudio;

/**
 * Deterministic bidirectional pose-to-pose motion for ALREADY IMPORTED keyframes.
 *
 * Unlike camera-motion presets this changes individual pixels and can move the
 * subjects between two distinct drawn poses. This is a bounded, local optical
 * flow approximation, NOT neural reconstruction of unseen limbs or 3-D acting.
 * The first/last anchor are returned bit-for-bit to protect authored frames.
 *
 * Pure Java: usable in unit tests without Android or external native libraries.
 */
public final class PoseSequenceFlow {
    private static final int ANALYSIS_W = 96;
    private static final int ANALYSIS_H = 160;
    private static final int GRID_X = 12;
    private static final int GRID_Y = 20;
    private static final int SEARCH = 10;
    private static final int PATCH = 2;

    public static final class Motion {
        private final float[] dx = new float[GRID_X * GRID_Y];
        private final float[] dy = new float[GRID_X * GRID_Y];
        private final float[] confidence = new float[GRID_X * GRID_Y];
        public double meanConfidence() {
            double sum = 0;
            for (float c : confidence) sum += c;
            return sum / confidence.length;
        }
    }

    public static final class Pair {
        public final Motion forward;
        public final Motion backward;
        public final double confidence;
        private Pair(Motion a, Motion b) {
            forward = a;
            backward = b;
            confidence = (a.meanConfidence() + b.meanConfidence()) * .5;
        }
    }

    private PoseSequenceFlow() {}

    public static Pair analyse(int[] a, int[] b, int width, int height) {
        check(a, b, width, height);
        byte[] luminanceA = luminance(a, width, height);
        byte[] luminanceB = luminance(b, width, height);
        return new Pair(estimate(luminanceA, luminanceB),
                estimate(luminanceB, luminanceA));
    }

    /** Produce a new pose frame at a normalized position [0, 1]. */
    public static int[] between(int[] a, int[] b, int width, int height,
                                Pair flow, double time) {
        check(a, b, width, height);
        if (flow == null) throw new IllegalArgumentException("Motion analysis required");
        if (!Double.isFinite(time) || time < 0 || time > 1) {
            throw new IllegalArgumentException("time must be in [0,1]");
        }
        if (time == 0) return a.clone();
        if (time == 1) return b.clone();

        final float t = (float) (time * time * (3 - 2 * time));
        final float oneMinus = 1 - t;
        final float scaleX = width / (float) ANALYSIS_W;
        final float scaleY = height / (float) ANALYSIS_H;
        int[] out = new int[width * height];

        for (int y = 0; y < height; ++y) {
            final float gy = y / (float) height * GRID_Y - .5f;
            int y0 = (int) Math.floor(gy);
            final float fy = clamp(gy - y0, 0, 1);
            y0 = Math.max(0, Math.min(GRID_Y - 1, y0));
            int y1 = Math.min(GRID_Y - 1, y0 + 1);
            for (int x = 0; x < width; ++x) {
                float gx = x / (float) width * GRID_X - .5f;
                int x0 = (int) Math.floor(gx);
                float fx = clamp(gx - x0, 0, 1);
                x0 = Math.max(0, Math.min(GRID_X - 1, x0));
                int x1 = Math.min(GRID_X - 1, x0 + 1);
                int i00 = y0 * GRID_X + x0;
                int i10 = y0 * GRID_X + x1;
                int i01 = y1 * GRID_X + x0;
                int i11 = y1 * GRID_X + x1;

                float dxa = bilerp(flow.forward.dx, i00, i10, i01, i11, fx, fy);
                float dya = bilerp(flow.forward.dy, i00, i10, i01, i11, fx, fy);
                float dxb = bilerp(flow.backward.dx, i00, i10, i01, i11, fx, fy);
                float dyb = bilerp(flow.backward.dy, i00, i10, i01, i11, fx, fy);

                // Forward displacements are measured on source A.
                // Backward displacements are measured on target B.
                float xa = x - t * dxa * scaleX;
                float ya = y - t * dya * scaleY;
                float xb = x - oneMinus * dxb * scaleX;
                float yb = y - oneMinus * dyb * scaleY;

                int ca = pixel(a, width, height, xa, ya);
                int cb = pixel(b, width, height, xb, yb);
                int r = Math.round(((ca >>> 16) & 255) * oneMinus
                        + ((cb >>> 16) & 255) * t);
                int g = Math.round(((ca >>> 8) & 255) * oneMinus
                        + ((cb >>> 8) & 255) * t);
                int blue = Math.round((ca & 255) * oneMinus + (cb & 255) * t);
                out[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | blue;
            }
        }
        return out;
    }

    private static Motion estimate(byte[] from, byte[] to) {
        Motion out = new Motion();
        for (int cy = 0; cy < GRID_Y; ++cy) {
            for (int cx = 0; cx < GRID_X; ++cx) {
                int x = Math.round((cx + .5f) * ANALYSIS_W / GRID_X);
                int y = Math.round((cy + .5f) * ANALYSIS_H / GRID_Y);
                x = Math.max(PATCH + SEARCH, Math.min(ANALYSIS_W - PATCH - SEARCH - 1, x));
                y = Math.max(PATCH + SEARCH, Math.min(ANALYSIS_H - PATCH - SEARCH - 1, y));

                int texture = 0;
                int center = from[y * ANALYSIS_W + x] & 255;
                for (int py = -PATCH; py <= PATCH; ++py)
                    for (int px = -PATCH; px <= PATCH; ++px)
                        texture += Math.abs((from[(y + py) * ANALYSIS_W + x + px] & 255) - center);

                int winner = Integer.MAX_VALUE;
                int bestX = 0, bestY = 0;
                for (int oy = -SEARCH; oy <= SEARCH; ++oy) {
                    for (int ox = -SEARCH; ox <= SEARCH; ++ox) {
                        int diff = 0;
                        for (int py = -PATCH; py <= PATCH; ++py)
                            for (int px = -PATCH; px <= PATCH; ++px) {
                                int src = from[(y + py) * ANALYSIS_W + x + px] & 255;
                                int dst = to[(y + py + oy) * ANALYSIS_W + x + px + ox] & 255;
                                diff += Math.abs(src - dst);
                            }
                        // A tiny static bias protects the predominantly stationary city.
                        diff += (Math.abs(ox) + Math.abs(oy)) * 2;
                        if (diff < winner) {
                            winner = diff;
                            bestX = ox;
                            bestY = oy;
                        }
                    }
                }
                int i = cy * GRID_X + cx;
                float reliability = clamp(texture / 400f, 0f, 1f);
                reliability *= clamp(1f - winner / 4500f, 0f, 1f);
                out.confidence[i] = reliability;
                out.dx[i] = bestX * reliability;
                out.dy[i] = bestY * reliability;
            }
        }
        // Average isolated motion vectors while preserving coherent movements.
        smooth(out.dx);
        smooth(out.dy);
        return out;
    }

    private static void smooth(float[] f) {
        float[] initial = f.clone();
        for (int y = 1; y < GRID_Y - 1; ++y) {
            for (int x = 1; x < GRID_X - 1; ++x) {
                int i = y * GRID_X + x;
                f[i] = (initial[i] * 4f + initial[i - 1] + initial[i + 1]
                        + initial[i - GRID_X] + initial[i + GRID_X]) / 8f;
            }
        }
    }

    private static byte[] luminance(int[] pixels, int width, int height) {
        byte[] result = new byte[ANALYSIS_W * ANALYSIS_H];
        for (int y = 0; y < ANALYSIS_H; ++y) {
            int sy = Math.min(height - 1, (int) ((y + .5) * height / ANALYSIS_H));
            for (int x = 0; x < ANALYSIS_W; ++x) {
                int sx = Math.min(width - 1, (int) ((x + .5) * width / ANALYSIS_W));
                int p = pixels[sy * width + sx];
                result[y * ANALYSIS_W + x] = (byte) (((p >>> 16 & 255) * 77
                        + (p >>> 8 & 255) * 150 + (p & 255) * 29) >> 8);
            }
        }
        return result;
    }

    private static int pixel(int[] image, int width, int height, float fx, float fy) {
        float x = clamp(fx, 0, width - 1);
        float y = clamp(fy, 0, height - 1);
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(width - 1, x0 + 1), y1 = Math.min(height - 1, y0 + 1);
        float tx = x - x0, ty = y - y0;
        int p00 = image[y0 * width + x0], p10 = image[y0 * width + x1];
        int p01 = image[y1 * width + x0], p11 = image[y1 * width + x1];
        int r = Math.round((1 - ty) * ((1 - tx) * (p00 >>> 16 & 255) + tx * (p10 >>> 16 & 255))
                + ty * ((1 - tx) * (p01 >>> 16 & 255) + tx * (p11 >>> 16 & 255)));
        int g = Math.round((1 - ty) * ((1 - tx) * (p00 >>> 8 & 255) + tx * (p10 >>> 8 & 255))
                + ty * ((1 - tx) * (p01 >>> 8 & 255) + tx * (p11 >>> 8 & 255)));
        int b = Math.round((1 - ty) * ((1 - tx) * (p00 & 255) + tx * (p10 & 255))
                + ty * ((1 - tx) * (p01 & 255) + tx * (p11 & 255)));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static float bilerp(float[] f, int a, int b, int c, int d, float x, float y) {
        return (1 - y) * ((1 - x) * f[a] + x * f[b])
                + y * ((1 - x) * f[c] + x * f[d]);
    }

    private static float clamp(float v, float low, float high) {
        return Math.max(low, Math.min(high, v));
    }

    private static void check(int[] a, int[] b, int w, int h) {
        if (w < 32 || h < 32 || w > 1920 || h > 1920 ||
                (long) w * h > 4_000_000L ||
                a == null || b == null || a.length != w * h || b.length != w * h) {
            throw new IllegalArgumentException("Invalid bounded pose-image buffers");
        }
    }
}
