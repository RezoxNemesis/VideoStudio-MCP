package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.facemesh.FaceMesh;
import com.google.mlkit.vision.facemesh.FaceMeshDetection;
import com.google.mlkit.vision.facemesh.FaceMeshDetector;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bundled on-device portrait analysis for VideoStudio v3.
 *
 * No remote inference is used. The bundled ML Kit selfie model produces a
 * foreground confidence mask, and the bundled face-mesh model provides a
 * face-aware framing anchor. The generated foreground/background plates are
 * written to VideoStudio's app-private storage and can be animated as
 * independent layers by the native Media3 renderer.
 */
public final class NativePortraitMotionAnalyzer {
    public static final class Result {
        public final Uri foregroundUri;
        public final Uri backgroundUri;
        public final JSONObject analysis;
        public final int width;
        public final int height;

        Result(Uri foregroundUri, Uri backgroundUri, JSONObject analysis, int width, int height) {
            this.foregroundUri = foregroundUri;
            this.backgroundUri = backgroundUri;
            this.analysis = analysis;
            this.width = width;
            this.height = height;
        }
    }

    private static final int MAX_ANALYSIS_EDGE = 1440;

    private final Context context;

    public NativePortraitMotionAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result analyseAndBuildLayers(ProjectStore.Asset asset, String projectId) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Missing image asset");
        if (asset.mime == null || !asset.mime.startsWith("image/")) {
            throw new IllegalArgumentException("Portrait animation requires an image asset");
        }

        Bitmap original = decode(asset.uri);
        if (original == null) throw new IllegalStateException("Could not decode image");

        Bitmap working = scaleForAnalysis(original);
        if (working != original) original.recycle();

        Segmenter segmenter = Segmentation.getClient(
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build()
        );
        FaceMeshDetector faceDetector = FaceMeshDetection.getClient();

        SegmentationMask mask;
        List<FaceMesh> faces;
        try {
            InputImage image = InputImage.fromBitmap(working, 0);
            mask = Tasks.await(segmenter.process(image), 25, TimeUnit.SECONDS);
            try {
                faces = Tasks.await(faceDetector.process(image), 20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                faces = java.util.Collections.emptyList();
            }
        } finally {
            try { segmenter.close(); } catch (Exception ignored) {}
            try { faceDetector.close(); } catch (Exception ignored) {}
        }

        MaskStats stats = readMask(mask, working.getWidth(), working.getHeight());
        Bitmap foreground = buildForeground(working, stats);
        Bitmap background = buildSoftBackground(working);

        File dir = new File(context.getFilesDir(), "animation_layers/" + safe(projectId));
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create animation layer directory");

        String stem = safe(asset.id);
        File fgFile = new File(dir, stem + "_subject.png");
        File bgFile = new File(dir, stem + "_background.jpg");
        write(foreground, fgFile, Bitmap.CompressFormat.PNG, 100);
        write(background, bgFile, Bitmap.CompressFormat.JPEG, 95);

        JSONObject analysis = new JSONObject();
        analysis.put("engine", "bundled-on-device-portrait-ai");
        analysis.put("subjectDetected", stats.coverage > 0.015);
        analysis.put("subjectCoverage", stats.coverage);
        analysis.put("subjectCenterX", stats.centerX);
        analysis.put("subjectCenterY", stats.centerY);
        analysis.put("subjectLeft", stats.left);
        analysis.put("subjectTop", stats.top);
        analysis.put("subjectRight", stats.right);
        analysis.put("subjectBottom", stats.bottom);
        analysis.put("maskWidth", mask.getWidth());
        analysis.put("maskHeight", mask.getHeight());

        Rect face = faces.isEmpty() ? null : largestFace(faces);
        if (face != null) {
            double faceCx = clamp01((face.exactCenterX()) / working.getWidth());
            double faceCy = clamp01((face.exactCenterY()) / working.getHeight());
            double faceCoverage = clamp01((face.width() * (double) face.height())
                    / (working.getWidth() * (double) working.getHeight()));
            analysis.put("faceDetected", true);
            analysis.put("faceCenterX", faceCx);
            analysis.put("faceCenterY", faceCy);
            analysis.put("faceCoverage", faceCoverage);
        } else {
            analysis.put("faceDetected", false);
            analysis.put("faceCenterX", stats.centerX);
            analysis.put("faceCenterY", Math.max(0.2, stats.centerY - 0.18));
            analysis.put("faceCoverage", 0);
        }

        analysis.put("shotType", shotType(stats.coverage, analysis.optDouble("faceCoverage", 0)));
        analysis.put("foregroundUri", Uri.fromFile(fgFile).toString());
        analysis.put("backgroundUri", Uri.fromFile(bgFile).toString());
        analysis.put("width", working.getWidth());
        analysis.put("height", working.getHeight());

        foreground.recycle();
        background.recycle();
        working.recycle();

        return new Result(
                Uri.fromFile(fgFile),
                Uri.fromFile(bgFile),
                analysis,
                mask.getWidth(),
                mask.getHeight()
        );
    }

    private Bitmap decode(String uriString) throws Exception {
        Uri uri = Uri.parse(uriString);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) return BitmapFactory.decodeStream(in);
        }
        if ("file".equalsIgnoreCase(uri.getScheme()) || uri.getScheme() == null) {
            String path = uri.getScheme() == null ? uriString : uri.getPath();
            return BitmapFactory.decodeFile(path);
        }
        return null;
    }

    private Bitmap scaleForAnalysis(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int max = Math.max(w, h);
        if (max <= MAX_ANALYSIS_EDGE) return source;
        float scale = MAX_ANALYSIS_EDGE / (float) max;
        return Bitmap.createScaledBitmap(source, Math.max(2, Math.round(w * scale)), Math.max(2, Math.round(h * scale)), true);
    }

    private static final class MaskStats {
        final float[] values;
        final int maskWidth;
        final int maskHeight;
        final double coverage;
        final double centerX;
        final double centerY;
        final double left;
        final double top;
        final double right;
        final double bottom;

        MaskStats(float[] values, int maskWidth, int maskHeight,
                  double coverage, double centerX, double centerY,
                  double left, double top, double right, double bottom) {
            this.values = values;
            this.maskWidth = maskWidth;
            this.maskHeight = maskHeight;
            this.coverage = coverage;
            this.centerX = centerX;
            this.centerY = centerY;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private MaskStats readMask(SegmentationMask mask, int imageW, int imageH) {
        int mw = Math.max(1, mask.getWidth());
        int mh = Math.max(1, mask.getHeight());
        float[] values = new float[mw * mh];
        ByteBuffer buffer = mask.getBuffer().duplicate().order(ByteOrder.nativeOrder());
        buffer.rewind();

        int minX = mw, minY = mh, maxX = -1, maxY = -1;
        double sx = 0, sy = 0, weight = 0;
        int strong = 0;
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                int i = y * mw + x;
                float confidence = buffer.remaining() >= 4 ? buffer.getFloat() : 0f;
                confidence = Math.max(0f, Math.min(1f, confidence));
                values[i] = confidence;
                if (confidence > 0.42f) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    strong++;
                }
                if (confidence > 0.2f) {
                    double w = confidence * confidence;
                    sx += x * w;
                    sy += y * w;
                    weight += w;
                }
            }
        }

        boolean found = maxX >= minX && maxY >= minY;
        double cx = weight > 0 ? sx / weight / Math.max(1, mw - 1) : 0.5;
        double cy = weight > 0 ? sy / weight / Math.max(1, mh - 1) : 0.5;
        double coverage = strong / (double) Math.max(1, mw * mh);
        double l = found ? minX / (double) Math.max(1, mw - 1) : 0.15;
        double t = found ? minY / (double) Math.max(1, mh - 1) : 0.10;
        double r = found ? maxX / (double) Math.max(1, mw - 1) : 0.85;
        double b = found ? maxY / (double) Math.max(1, mh - 1) : 0.95;

        return new MaskStats(values, mw, mh, coverage, clamp01(cx), clamp01(cy),
                clamp01(l), clamp01(t), clamp01(r), clamp01(b));
    }

    private Bitmap buildForeground(Bitmap source, MaskStats stats) {
        int w = source.getWidth(), h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            int my = Math.min(stats.maskHeight - 1,
                    Math.max(0, Math.round(y * (stats.maskHeight - 1f) / Math.max(1, h - 1))));
            for (int x = 0; x < w; x++) {
                int mx = Math.min(stats.maskWidth - 1,
                        Math.max(0, Math.round(x * (stats.maskWidth - 1f) / Math.max(1, w - 1))));
                float confidence = stats.values[my * stats.maskWidth + mx];
                float alpha = smoothstep(0.18f, 0.82f, confidence);
                int color = pixels[y * w + x];
                int originalAlpha = (color >>> 24) & 0xff;
                int a = Math.round(originalAlpha * alpha);
                pixels[y * w + x] = (color & 0x00ffffff) | (a << 24);
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    private Bitmap buildSoftBackground(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        int sw = Math.max(12, w / 18);
        int sh = Math.max(12, h / 18);
        Bitmap tiny = Bitmap.createScaledBitmap(source, sw, sh, true);
        Bitmap blurred = Bitmap.createScaledBitmap(tiny, w, h, true);
        tiny.recycle();

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0.92f);
        ColorMatrix exposure = new ColorMatrix(new float[]{
                0.92f,0,0,0,-5,
                0,0.92f,0,0,-5,
                0,0,0.92f,0,-5,
                0,0,0,1,0
        });
        matrix.postConcat(exposure);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(blurred, 0, 0, paint);
        blurred.recycle();
        return out;
    }

    private static Rect largestFace(List<FaceMesh> faces) {
        Rect best = null;
        long bestArea = -1;
        for (FaceMesh mesh : faces) {
            Rect r = mesh.getBoundingBox();
            long area = r.width() * (long) r.height();
            if (area > bestArea) {
                bestArea = area;
                best = r;
            }
        }
        return best;
    }

    private static String shotType(double subjectCoverage, double faceCoverage) {
        if (faceCoverage > 0.10 || subjectCoverage > 0.58) return "close";
        if (faceCoverage > 0.035 || subjectCoverage > 0.30) return "medium";
        return "wide";
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / Math.max(0.0001f, edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    private void write(Bitmap bitmap, File file, Bitmap.CompressFormat format, int quality) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(format, quality, out)) throw new IllegalStateException("Could not write animation layer");
            out.flush();
        }
    }

    private static String safe(String value) {
        String v = value == null ? "asset" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return v.isEmpty() ? "asset" : v;
    }

    private static double clamp01(double v) {
        return Math.max(0d, Math.min(1d, v));
    }
}
