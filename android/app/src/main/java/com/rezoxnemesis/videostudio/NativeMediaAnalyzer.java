package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Locale;

public final class NativeMediaAnalyzer {
    private static final int MAX_FRAME_ATTEMPTS = 3;
    private final Context context;

    public NativeMediaAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject analyse(ProjectStore.Asset asset, int frameCount, long startMs, long endMs) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Asset not found");
        if (asset.mime == null || !asset.mime.startsWith("video/")) throw new IllegalArgumentException("Visual analysis currently requires a video asset");
        cancelled();

        frameCount = Math.max(6, Math.min(16, frameCount));
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        ArrayList<Bitmap> frames = new ArrayList<>();
        Bitmap sheet = null;
        try {
            cancelled();
            retriever.setDataSource(context, Uri.parse(asset.uri));
            String durationMetadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = parseLong(durationMetadata, asset.durationMs);
            boolean durationMetadataAvailable = parseLong(durationMetadata, -1) > 0;
            int width = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int height = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            Integer rotation = rotationDegrees(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (endMs <= 0 || endMs > durationMs) endMs = durationMs;
            startMs = Math.max(0, Math.min(startMs, Math.max(0, endMs - 1)));
            long span = Math.max(1, endMs - startMs);

            ArrayList<Long> times = new ArrayList<>();
            JSONArray changes = new JSONArray();
            JSONArray sampledFrames = new JSONArray();
            JSONArray failures = new JSONArray();
            RuntimeException lastDecoderFailure = null;
            double[] previous = null;

            for (int i = 0; i < frameCount; i++) {
                long timeMs = startMs + (long) ((i / (double) Math.max(1, frameCount - 1)) * span);
                Bitmap frame = null, thumb = null;
                try {
                    FrameSample sample = decodeSample(retriever, timeMs);
                    frame = sample.frame;
                    cancelled();
                    if (frame == null) {
                        JSONObject failure = new JSONObject().put("requestedTimeMs", timeMs)
                                .put("attempts", sample.attempts)
                                .put("reason", sample.error == null ? "decoder_returned_no_frame" : "decoder_exception");
                        if (sample.error != null) {
                            lastDecoderFailure = sample.error;
                            String message = sample.error.getMessage();
                            failure.put("errorType", sample.error.getClass().getName());
                            if (message != null) failure.put("message", message.substring(0, Math.min(512, message.length())));
                        }
                        failures.put(failure);
                        continue;
                    }
                    // Returned decoder dimensions precede unchanged contact-sheet thumbnailing.
                    // Closest-sync retrieval still does not expose actual presentation timestamps.
                    sampledFrames.put(new JSONObject().put("requestedTimeMs", timeMs)
                            .put("width", frame.getWidth()).put("height", frame.getHeight())
                            .put("attempts", sample.attempts));
                    thumb = scaleInside(frame, 270, 480);
                    if (thumb != frame) recycle(frame);
                    frame = null;
                    frames.add(thumb);
                    thumb = null; // The outer finally now owns this thumbnail.
                    times.add(timeMs);

                    double[] signature = signature(frames.get(frames.size() - 1));
                    if (previous != null) {
                        double diff = difference(previous, signature);
                        if (diff > 0.115) {
                            JSONObject change = new JSONObject();
                            change.put("timeMs", timeMs);
                            change.put("timeSeconds", Math.round(timeMs / 100d) / 10d);
                            change.put("strength", Math.round(diff * 1000d) / 1000d);
                            changes.put(change);
                        }
                    }
                    previous = signature;
                } finally {
                    recycle(frame);
                    recycle(thumb);
                }
            }

            cancelled();
            if (frames.isEmpty()) throw new IllegalStateException(
                    "Could not decode analysis frames (0 of " + frameCount + " requested samples)", lastDecoderFailure);
            sheet = contactSheet(frames, times);
            int sheetWidth = sheet.getWidth(), sheetHeight = sheet.getHeight();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            cancelled();
            if (!sheet.compress(Bitmap.CompressFormat.JPEG, 76, bytes))
                throw new IllegalStateException("Could not encode analysis contact sheet");
            cancelled();

            JSONObject contact = new JSONObject();
            contact.put("mimeType", "image/jpeg");
            contact.put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
            contact.put("frameCount", times.size());
            contact.put("width", sheetWidth);
            contact.put("height", sheetHeight);

            JSONArray sampled = new JSONArray();
            for (Long t : times) sampled.put(Math.round(t / 100d) / 10d);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("assetId", asset.id);
            result.put("name", asset.name);
            result.put("durationMs", durationMs);
            result.put("durationMetadataAvailable", durationMetadataAvailable);
            result.put("width", width);
            result.put("height", height);
            result.put("codedWidth", width);
            result.put("codedHeight", height);
            result.put("rotationMetadataAvailable", rotation != null);
            result.put("rotationDegrees", rotation == null ? JSONObject.NULL : rotation);
            boolean displayKnown = rotation != null && width > 0 && height > 0;
            boolean swapped = rotation != null && (rotation == 90 || rotation == 270);
            result.put("displayWidth", displayKnown ? (swapped ? height : width) : JSONObject.NULL);
            result.put("displayHeight", displayKnown ? (swapped ? width : height) : JSONObject.NULL);
            result.put("samplingMode", "closest_sync");
            result.put("exactFrameTimes", false);
            result.put("sampledFrames", sampledFrames);
            result.put("sampleTimesSeconds", sampled);
            result.put("sceneChanges", changes);
            result.put("contactSheet", contact);
            result.put("coverage", new JSONObject().put("requestedFrames", frameCount)
                    .put("decodedFrames", times.size()).put("failedFrames", failures.length())
                    .put("complete", failures.length() == 0).put("failures", failures)
                    .put("maximumAttemptsPerSample", MAX_FRAME_ATTEMPTS));
            result.put("analysisEngine", "native-frame-sampler-v2");
            cancelled();
            return result;
        } finally {
            recycle(sheet);
            for (Bitmap frame : frames) recycle(frame);
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static final class FrameSample {
        Bitmap frame;
        int attempts;
        RuntimeException error;
    }

    private FrameSample decodeSample(MediaMetadataRetriever retriever, long timeMs) throws InterruptedIOException {
        FrameSample sample = new FrameSample();
        while (sample.attempts < MAX_FRAME_ATTEMPTS) {
            cancelled();
            sample.attempts++;
            try {
                sample.frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (sample.frame != null) return sample;
            } catch (SecurityException denied) {
                // Revoked access is not a transient codec miss and must not be retried.
                throw denied;
            } catch (RuntimeException decoderFailure) {
                cancelled();
                sample.error = decoderFailure;
            }
        }
        return sample;
    }

    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Native media analysis cancelled");
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private Bitmap contactSheet(ArrayList<Bitmap> frames, ArrayList<Long> times) throws InterruptedIOException {
        int cols = frames.size() <= 8 ? 2 : 3;
        int rows = (int) Math.ceil(frames.size() / (double) cols);
        int cellW = 300;
        int cellH = 220;
        Bitmap sheet = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888);
        boolean completed = false;
        try {
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(Color.rgb(8, 11, 20));
            Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelBg.setColor(Color.argb(190, 0, 0, 0));
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(Color.WHITE);
            label.setTextSize(22);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));

            for (int i = 0; i < frames.size(); i++) {
                cancelled();
                int col = i % cols;
                int row = i / cols;
                float left = col * cellW;
                float top = row * cellH;
                Bitmap b = frames.get(i);
                float scale = Math.min(cellW / (float) b.getWidth(), cellH / (float) b.getHeight());
                float dw = b.getWidth() * scale;
                float dh = b.getHeight() * scale;
                float x = left + (cellW - dw) / 2f;
                float y = top + (cellH - dh) / 2f;
                canvas.drawBitmap(b, null, new android.graphics.RectF(x, y, x + dw, y + dh), null);
                canvas.drawRect(left, top + cellH - 38, left + 112, top + cellH, labelBg);
                String ts = String.format(Locale.US, "%.1fs", times.get(i) / 1000d);
                canvas.drawText(ts, left + 10, top + cellH - 11, label);
            }
            cancelled();
            completed = true;
            return sheet;
        } finally {
            if (!completed) recycle(sheet);
        }
    }

    private Bitmap scaleInside(Bitmap source, int maxW, int maxH) {
        float scale = Math.min(maxW / (float) source.getWidth(), maxH / (float) source.getHeight());
        if (scale >= 1) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
    }

    private double[] signature(Bitmap bitmap) throws InterruptedIOException {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 14, true);
        try {
            double[] out = new double[24 * 14 * 3];
            int at = 0;
            for (int y = 0; y < 14; y++) {
                cancelled();
                for (int x = 0; x < 24; x++) {
                    int c = small.getPixel(x, y);
                    out[at++] = Color.red(c) / 255d;
                    out[at++] = Color.green(c) / 255d;
                    out[at++] = Color.blue(c) / 255d;
                }
            }
            return out;
        } finally {
            // Android may return its input when the requested size is already24×14.
            // That input is still needed by the contact sheet and belongs to frames.
            if (small != bitmap) recycle(small);
        }
    }

    private double difference(double[] a, double[] b) {
        double total = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) total += Math.abs(a[i] - b[i]);
        return n == 0 ? 0 : total / n;
    }

    private long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (Exception ignored) { return fallback; }
    }

    private Integer rotationDegrees(String value) {
        if (value == null) return null;
        try {
            int rotation = Math.floorMod(Integer.parseInt(value), 360);
            return rotation % 90 == 0 ? rotation : null;
        } catch (NumberFormatException invalid) { return null; }
    }
}
