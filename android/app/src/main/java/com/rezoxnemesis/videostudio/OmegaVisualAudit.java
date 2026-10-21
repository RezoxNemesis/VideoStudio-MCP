package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * OMEGA Phase 6: bounded, deterministic, Android-independent visual compositor
 * and technical evidence. This is an integration-ready engine, NOT a substitute
 * for a decoded native video, reference comparison or human artistic review.
 * Nothing in this class reads a file, inspects Gallery, changes a project or
 * assigns an artistic quality score.
 */
final class OmegaVisualAudit {
    private OmegaVisualAudit() {}
    static final int MAX_EDGE = 2048;
    static final int MAX_LAYERS = 8;
    static final int MAX_INPUT_PIXELS = 4_194_304;
    static final int MAX_REGISTRATION_PX = 8;

    static final class Rect {
        final int left, top, right, bottom, width, height;
        Rect(int left, int top, int right, int bottom, int width, int height) {
            validCanvas(width, height);
            if (left < 0 || top < 0 || left >= right || top >= bottom ||
                    right > width || bottom > height)
                throw new IllegalArgumentException("Invalid subject region");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.width = width; this.height = height;
        }
        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
        boolean intersects(Rect other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }

    static final class Evidence {
        final int subjectPixels, backgroundPixels, subjectChanged, backgroundChanged;
        Evidence(int subjectPixels, int backgroundPixels, int subjectChanged, int backgroundChanged) {
            this.subjectPixels = subjectPixels; this.backgroundPixels = backgroundPixels;
            this.subjectChanged = subjectChanged; this.backgroundChanged = backgroundChanged;
        }
        double subjectChangeRatio() { return subjectPixels == 0 ? 0 : subjectChanged / (double) subjectPixels; }
        double backgroundChangeRatio() { return backgroundPixels == 0 ? 0 : backgroundChanged / (double) backgroundPixels; }
        boolean backgroundDominated() {
            return backgroundPixels > 0 && backgroundChangeRatio() > .15 && subjectChangeRatio() < .02;
        }
        boolean staticSubject() { return subjectChanged == 0; }
    }

    // Backwards-compatible raw RGB change counter. Transparent source pixels are
    // NOT an alpha-aware visual proof; callers should compare composited opaque frames.
    static Evidence measure(int[] before, int[] after, int width, int height, Rect subject, int threshold) {
        validPair(before, after, width, height);
        validRect(subject, width, height);
        if (threshold < 0 || threshold > 765) throw new IllegalArgumentException("Invalid RGB threshold");
        int inside = 0, outside = 0, moveInside = 0, moveOutside = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int index = y * width + x;
            boolean in = subject.contains(x, y);
            if (in) { inside++; if (difference(before[index], after[index]) > threshold) moveInside++; }
            else { outside++; if (difference(before[index], after[index]) > threshold) moveOutside++; }
        }
        return new Evidence(inside, outside, moveInside, moveOutside);
    }

    static final class Translation {
        final int dx, dy, textureSamples;
        final double zeroError, alignedError;
        final boolean reliable;
        Translation(int dx, int dy, int textureSamples, double zeroError, double alignedError, boolean reliable) {
            this.dx = dx; this.dy = dy; this.textureSamples = textureSamples;
            this.zeroError = zeroError; this.alignedError = alignedError; this.reliable = reliable;
        }
    }

    static final class Motion {
        final Evidence raw, stabilized;
        final Translation camera;
        final boolean cameraOnlyCandidate;
        Motion(Evidence raw, Evidence stabilized, Translation camera) {
            this.raw = raw; this.stabilized = stabilized; this.camera = camera;
            // An explicit warning, never a claim of true/false anatomical motion.
            this.cameraOnlyCandidate = camera.reliable && (camera.dx != 0 || camera.dy != 0)
                    && raw.subjectChangeRatio() > .05 && stabilized.subjectChangeRatio() <= .02;
        }
    }

    /**
     * Estimate background camera shift from authored non-subject pixels, then
     * measure motion residual in subject coordinates. Search is capped at 8 px
     * and only reports registration when there are enough textured samples.
     */
    static Motion measureMotion(int[] before, int[] after, int width, int height,
                                Rect subject, int threshold, int maxShift) {
        validPair(before, after, width, height);
        validRect(subject, width, height);
        if (threshold < 0 || threshold > 765) throw new IllegalArgumentException("Invalid threshold");
        Translation estimate = estimate(before, after, width, height, subject, true, maxShift);
        Evidence raw = measure(before, after, width, height, subject, threshold);
        int dx = estimate.reliable ? estimate.dx : 0, dy = estimate.reliable ? estimate.dy : 0;
        Evidence stable = translatedDifference(before, after, width, height, subject, threshold, dx, dy);
        return new Motion(raw, stable, estimate);
    }

    static final class Parallax {
        final Translation near, far;
        final boolean depthDifferentialCandidate;
        Parallax(Translation near, Translation far) {
            this.near = near; this.far = far;
            this.depthDifferentialCandidate = near.reliable && far.reliable &&
                    (near.dx != far.dx || near.dy != far.dy);
        }
    }

    /** Two *user-declared, disjoint*, texture-rich depth regions are mandatory. */
    static Parallax measureParallax(int[] before, int[] after, int width, int height,
                                    Rect near, Rect far, int maxShift) {
        validPair(before, after, width, height);
        validRect(near, width, height); validRect(far, width, height);
        if (near.intersects(far)) throw new IllegalArgumentException("Depth regions overlap");
        return new Parallax(estimate(before, after, width, height, near, false, maxShift),
                estimate(before, after, width, height, far, false, maxShift));
    }

    private static Translation estimate(int[] before, int[] after, int width, int height,
                                         Rect region, boolean exclude, int maxShift) {
        if (maxShift < 0 || maxShift > MAX_REGISTRATION_PX)
            throw new IllegalArgumentException("Registration offset exceeds budget");
        // Always compare the SAME eligible source points for each candidate:
        // no improved score from opportunistically discarding border samples.
        int margin = maxShift + 1, stride = 2;
        int[] indices = new int[Math.min(MAX_INPUT_PIXELS, width * height / 4 + 1)];
        int count = 0;
        for (int y = margin; y < height - margin; y += stride)
            for (int x = margin; x < width - margin; x += stride) {
                if (region.contains(x, y) == exclude) continue;
                int p = y * width + x;
                int gradient = Math.abs(luma(before[p]) - luma(before[p + 1]))
                        + Math.abs(luma(before[p]) - luma(before[p + width]));
                if (gradient >= 24) indices[count++] = p;
            }
        if (count < 12) return new Translation(0, 0, count, 0, 0, false);
        // Cap sampled registration work on desktop-size frames, and preserve
        // deterministic coverage across the full ROI (not just its beginning).
        int step = Math.max(1, (count + 2047) / 2048);
        double zero = score(before, after, indices, count, step, 0);
        double best = zero;
        int bx = 0, by = 0;
        for (int dy = -maxShift; dy <= maxShift; dy++) for (int dx = -maxShift; dx <= maxShift; dx++) {
            if (dx == 0 && dy == 0) continue;
            double error = score(before, after, indices, count, step, dx + dy * width);
            int oldDist = Math.abs(bx) + Math.abs(by), newDist = Math.abs(dx) + Math.abs(dy);
            if (error < best - 1e-9 || Math.abs(error - best) < 1e-9 && newDist < oldDist) {
                best = error; bx = dx; by = dy;
            }
        }
        // A textureless backdrop or tiny photographic coincidence must not be
        // counted as reliable subject stabilization or depth parallax.
        boolean reliable = count >= 12 && (bx != 0 || by != 0) && zero - best >= 12.0
                && best <= zero * .72;
        return new Translation(reliable ? bx : 0, reliable ? by : 0, count, zero, best, reliable);
    }

    private static double score(int[] a, int[] b, int[] indices, int count, int stride,
                                int offset) {
        long cost = 0;
        int samples = 0;
        for (int i = 0; i < count; i += stride) {
            cost += difference(a[indices[i]], b[indices[i] + offset]); samples++;
        }
        return cost / (double) samples;
    }

    private static Evidence translatedDifference(int[] a, int[] b, int w, int h,
                                                   Rect region, int threshold, int dx, int dy) {
        int inside = 0, outside = 0, movedInside = 0, movedOutside = 0;
        for (int y = 0; y < h; y++) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) continue;
            for (int x = 0; x < w; x++) {
                int xx = x + dx;
                if (xx < 0 || xx >= w) continue;
                boolean in = region.contains(x, y);
                if (in) { inside++; if (difference(a[y*w+x], b[yy*w+xx]) > threshold) movedInside++; }
                else { outside++; if (difference(a[y*w+x], b[yy*w+xx]) > threshold) movedOutside++; }
            }
        }
        return new Evidence(inside, outside, movedInside, movedOutside);
    }

    static final class Appearance {
        final double meanLuma, contrast, edgeFraction, blackFraction, whiteFraction;
        Appearance(double meanLuma, double contrast, double edgeFraction,
                   double blackFraction, double whiteFraction) {
            this.meanLuma = meanLuma; this.contrast = contrast; this.edgeFraction = edgeFraction;
            this.blackFraction = blackFraction; this.whiteFraction = whiteFraction;
        }
    }

    /** Technical luminance/edge evidence only, no inference about art or anatomy. */
    static Appearance appearance(int[] pixels, int width, int height, Rect region) {
        validFrame(pixels, width, height); validRect(region, width, height);
        int n = 0, black = 0, white = 0, edges = 0, considered = 0;
        double mean = 0, m2 = 0;
        for (int y = region.top; y < region.bottom; y++)
            for (int x = region.left; x < region.right; x++) {
                int lum = luma(pixels[y*width+x]);
                n++; double delta = lum - mean;
                mean += delta / n; m2 += delta * (lum - mean);
                if (lum <= 5) black++;
                if (lum >= 250) white++;
                if (x+1 < region.right && y+1 < region.bottom) {
                    considered++;
                    if (Math.abs(lum - luma(pixels[y*width+x+1])) +
                            Math.abs(lum - luma(pixels[(y+1)*width+x])) >= 40) edges++;
                }
            }
        return new Appearance(mean / 255.0, Math.sqrt(m2 / n) / 255.0,
                considered == 0 ? 0 : edges / (double) considered,
                black / (double) n, white / (double) n);
    }

    static final class Layer {
        final String id;
        final int width, height;
        final int[] pixels;
        final double depth, x, y, opacity, grain, keyResponse;
        final int seed;
        final boolean castsShadow;
        Layer(String id, int width, int height, int[] pixels, double depth,
              double x, double y, double opacity, double grain, double keyResponse,
              int seed, boolean castsShadow) {
            validFrame(pixels, width, height);
            if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,64}"))
                throw new IllegalArgumentException("Invalid layer ID");
            bounded(depth, 0, 1, "depth"); bounded(x, -1, 1, "layer x");
            bounded(y, -1, 1, "layer y"); bounded(opacity, 0, 1, "opacity");
            bounded(grain, 0, .12, "grain"); bounded(keyResponse, 0, 2, "key response");
            this.id = id; this.width = width; this.height = height;
            this.pixels = pixels.clone(); // isolate input mutations while rendering
            this.depth = depth; this.x = x; this.y = y; this.opacity = opacity;
            this.grain = grain; this.keyResponse = keyResponse; this.seed = seed;
            this.castsShadow = castsShadow;
        }
    }

    /** User-authored per-frame look, never inferred from a reference photo. */
    static final class Look {
        final double cameraX, cameraY, ambient, keyStrength, keyX, keyY, keyRadius;
        final double shadowStrength;
        final int shadowDx, shadowDy;
        Look(double cameraX, double cameraY, double ambient, double keyStrength,
             double keyX, double keyY, double keyRadius, double shadowStrength,
             int shadowDx, int shadowDy) {
            bounded(cameraX, -.5, .5, "camera x"); bounded(cameraY, -.5, .5, "camera y");
            bounded(ambient, 0, 1.5, "ambient"); bounded(keyStrength, 0, 1.5, "key strength");
            bounded(keyX, 0, 1, "key x"); bounded(keyY, 0, 1, "key y");
            bounded(keyRadius, .05, 2, "key radius");
            bounded(shadowStrength, 0, 1, "shadow strength");
            if (shadowDx < -16 || shadowDx > 16 || shadowDy < -16 || shadowDy > 16)
                throw new IllegalArgumentException("Shadow offset exceeds pixel budget");
            this.cameraX = cameraX; this.cameraY = cameraY; this.ambient = ambient;
            this.keyStrength = keyStrength; this.keyX = keyX; this.keyY = keyY;
            this.keyRadius = keyRadius; this.shadowStrength = shadowStrength;
            this.shadowDx = shadowDx; this.shadowDy = shadowDy;
        }
        static Look neutral() { return new Look(0,0,1,0,.5,.5,.75,0,0,0); }
    }

    /**
     * Compose authored alpha layers back-to-front over an opaque background.
     * Depth changes camera displacement, not user-authored subject transforms.
     * Lighting is a 2D radial grade, grain is stable in *source* coordinates,
     * shadows are simple soft offset mattes. No 3D illumination is claimed.
     * All inputs and output are private runtime pixels, never serialized.
     */
    static int[] compose(int width, int height, int opaqueBackground, Layer[] layers, Look look) {
        validCanvas(width, height);
        if ((opaqueBackground >>> 24) != 255 || layers == null || look == null ||
                layers.length == 0 || layers.length > MAX_LAYERS)
            throw new IllegalArgumentException("Invalid compositing request");
        Set<String> ids = new HashSet<>();
        long inputPixels = 0;
        for (Layer layer : layers) {
            if (layer == null || layer.width != width || layer.height != height || !ids.add(layer.id))
                throw new IllegalArgumentException("Invalid or duplicate layer");
            inputPixels += layer.pixels.length;
            if (inputPixels > MAX_INPUT_PIXELS)
                throw new IllegalArgumentException("Compositing pixel budget exceeded");
        }
        int[] out = new int[width * height];
        Arrays.fill(out, opaqueBackground);
        for (Layer layer : layers) {
            if (layer.opacity == 0) continue;
            double offsetX = (layer.x - look.cameraX * layer.depth) * width;
            double offsetY = (layer.y - look.cameraY * layer.depth) * height;
            if (layer.castsShadow && look.shadowStrength > 0) {
                // 3x3 fixed blur: simple, small soft separation on the plate;
                // no shadow changes to opaque background or original layers.
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    double sx = x - offsetX - look.shadowDx;
                    double sy = y - offsetY - look.shadowDy;
                    double alpha = 0;
                    for (int yy = -1; yy <= 1; yy++)
                        for (int xx = -1; xx <= 1; xx++)
                            alpha += (sample(layer, sx+xx, sy+yy) >>> 24) / 255.0 / 9.0;
                    int srcAlpha = clamp255(alpha * layer.opacity * look.shadowStrength * 255);
                    int i = y * width + x;
                    out[i] = over(out[i], srcAlpha << 24);
                }
            }
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                double sx = x - offsetX, sy = y - offsetY;
                int pixel = sample(layer, sx, sy);
                int a = clamp255((pixel >>> 24) * layer.opacity);
                if (a == 0) continue;
                double lightDx = (x + .5) / width - look.keyX;
                double lightDy = (y + .5) / height - look.keyY;
                double falloff = Math.max(0, 1 - Math.sqrt(lightDx*lightDx + lightDy*lightDy) / look.keyRadius);
                double gain = look.ambient + look.keyStrength * layer.keyResponse * falloff;
                // Integer hash, deterministic and anchored to local artwork,
                // so camera pans do not create random per-frame film-grain flicker.
                double noise = layer.grain * (2.0 * hash((int)Math.floor(sx), (int)Math.floor(sy), layer.seed)
                        / Integer.MAX_VALUE - 1.0);
                int r = clamp255(((pixel >>> 16) & 255) * gain + 255 * noise);
                int g = clamp255(((pixel >>> 8) & 255) * gain + 255 * noise);
                int b = clamp255((pixel & 255) * gain + 255 * noise);
                int i = y * width + x;
                out[i] = over(out[i], (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    /** ARGB bilinear sample with premultiplied alpha: avoids dark fringes. */
    private static int sample(Layer layer, double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy, a = 0, r = 0, g = 0, b = 0;
        for (int j = 0; j <= 1; j++) for (int i = 0; i <= 1; i++) {
            int xx = ix + i, yy = iy + j;
            if (xx < 0 || yy < 0 || xx >= layer.width || yy >= layer.height) continue;
            double weight = (i == 0 ? 1-fx : fx) * (j == 0 ? 1-fy : fy);
            int pixel = layer.pixels[yy*layer.width+xx];
            double alpha = (pixel >>> 24) / 255.0 * weight;
            a += alpha; r += ((pixel >>> 16) & 255) * alpha;
            g += ((pixel >>> 8) & 255) * alpha; b += (pixel & 255) * alpha;
        }
        if (a <= 0) return 0;
        return (clamp255(a*255) << 24) | (clamp255(r/a) << 16) |
                (clamp255(g/a) << 8) | clamp255(b/a);
    }

    /** Source-over into an opaque ARGB destination with round-to-nearest. */
    private static int over(int dst, int src) {
        int a = src >>> 24;
        if (a == 0) return dst;
        if (a == 255) return src;
        int r = ((((src>>>16)&255)*a + ((dst>>>16)&255)*(255-a) + 127)/255);
        int g = ((((src>>>8)&255)*a + ((dst>>>8)&255)*(255-a) + 127)/255);
        int b = (((src&255)*a + (dst&255)*(255-a) + 127)/255);
        return 0xff000000 | (r<<16) | (g<<8) | b;
    }

    private static int hash(int x, int y, int seed) {
        int v = seed ^ (x * 0x9e3779b9) ^ (y * 0x85ebca6b);
        v ^= v >>> 16; v *= 0x7feb352d; v ^= v >>> 15;
        v *= 0x846ca68b; v ^= v >>> 16;
        return v & 0x7fffffff;
    }

    static int[] anchorFrames(int frameCount) {
        if (frameCount < 5 || frameCount > 1200)
            throw new IllegalArgumentException("Five distinct frame positions are required");
        int last = frameCount - 1;
        return new int[]{0, last/4, last/2, 3*last/4, last};
    }
    static long[] anchorTimesUs(int frameCount, int fps) {
        int[] frames = anchorFrames(frameCount);
        long[] times = new long[frames.length];
        for (int i = 0; i < frames.length; i++)
            times[i] = NativeFrameClock.presentationTimeUs(frames[i], fps);
        return times;
    }

    private static int difference(int a, int b) {
        return Math.abs(((a>>>16)&255)-((b>>>16)&255)) +
                Math.abs(((a>>>8)&255)-((b>>>8)&255)) + Math.abs((a&255)-(b&255));
    }
    private static int luma(int color) {
        return (((color>>>16)&255)*77 + ((color>>>8)&255)*150 + (color&255)*29) >>> 8;
    }
    private static int clamp255(double value) { return (int) Math.max(0, Math.min(255, Math.round(value))); }
    private static void bounded(double value, double low, double high, String what) {
        if (!Double.isFinite(value) || value < low || value > high)
            throw new IllegalArgumentException("Invalid " + what);
    }
    private static void validCanvas(int width, int height) {
        if (width < 2 || height < 2 || width > MAX_EDGE || height > MAX_EDGE)
            throw new IllegalArgumentException("Unsupported visual canvas");
    }
    private static void validFrame(int[] pixels, int width, int height) {
        validCanvas(width, height);
        if (pixels == null || pixels.length != width * height)
            throw new IllegalArgumentException("Visual frame size mismatch");
    }
    private static void validPair(int[] a, int[] b, int width, int height) {
        validFrame(a, width, height); validFrame(b, width, height);
    }
    private static void validRect(Rect region, int width, int height) {
        if (region == null || region.width != width || region.height != height)
            throw new IllegalArgumentException("Visual region/canvas mismatch");
    }
}
