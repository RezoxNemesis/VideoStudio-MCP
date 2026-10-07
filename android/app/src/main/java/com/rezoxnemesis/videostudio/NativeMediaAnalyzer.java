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
import java.util.ArrayList;
import java.util.Locale;

public final class NativeMediaAnalyzer {
    private final Context context;

    public NativeMediaAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject analyse(ProjectStore.Asset asset, int frameCount, long startMs, long endMs) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Asset not found");
        if (asset.mime == null || !asset.mime.startsWith("video/")) throw new IllegalArgumentException("Visual analysis currently requires a video asset");

        frameCount = Math.max(6, Math.min(16, frameCount));
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(asset.uri));
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), asset.durationMs);
            int width = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int height = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            if (endMs <= 0 || endMs > durationMs) endMs = durationMs;
            startMs = Math.max(0, Math.min(startMs, Math.max(0, endMs - 1)));
            long span = Math.max(1, endMs - startMs);

            ArrayList<Bitmap> frames = new ArrayList<>();
            ArrayList<Long> times = new ArrayList<>();
            JSONArray changes = new JSONArray();
            double[] previous = null;

            for (int i = 0; i < frameCount; i++) {
                long timeMs = startMs + (long) ((i / (double) Math.max(1, frameCount - 1)) * span);
                Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) continue;
                Bitmap thumb = scaleInside(frame, 270, 480);
                if (thumb != frame) frame.recycle();
                frames.add(thumb);
                times.add(timeMs);

                double[] signature = signature(thumb);
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
            }

            if (frames.isEmpty()) throw new IllegalStateException("Could not decode analysis frames");
            Bitmap sheet = contactSheet(frames, times);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            sheet.compress(Bitmap.CompressFormat.JPEG, 76, bytes);
            sheet.recycle();
            for (Bitmap b : frames) b.recycle();

            JSONObject contact = new JSONObject();
            contact.put("mimeType", "image/jpeg");
            contact.put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
            contact.put("frameCount", times.size());

            JSONArray sampled = new JSONArray();
            for (Long t : times) sampled.put(Math.round(t / 100d) / 10d);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("assetId", asset.id);
            result.put("name", asset.name);
            result.put("durationMs", durationMs);
            result.put("width", width);
            result.put("height", height);
            result.put("sampleTimesSeconds", sampled);
            result.put("sceneChanges", changes);
            result.put("contactSheet", contact);
            result.put("analysisEngine", "native-frame-sampler-v1");
            return result;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private Bitmap contactSheet(ArrayList<Bitmap> frames, ArrayList<Long> times) {
        int cols = frames.size() <= 8 ? 2 : 3;
        int rows = (int) Math.ceil(frames.size() / (double) cols);
        int cellW = 300;
        int cellH = 220;
        Bitmap sheet = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(Color.rgb(8, 11, 20));
        Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelBg.setColor(Color.argb(190, 0, 0, 0));
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.WHITE);
        label.setTextSize(22);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));

        for (int i = 0; i < frames.size(); i++) {
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
        return sheet;
    }

    private Bitmap scaleInside(Bitmap source, int maxW, int maxH) {
        float scale = Math.min(maxW / (float) source.getWidth(), maxH / (float) source.getHeight());
        if (scale >= 1) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
    }

    private double[] signature(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 14, true);
        double[] out = new double[24 * 14 * 3];
        int at = 0;
        for (int y = 0; y < 14; y++) {
            for (int x = 0; x < 24; x++) {
                int c = small.getPixel(x, y);
                out[at++] = Color.red(c) / 255d;
                out[at++] = Color.green(c) / 255d;
                out[at++] = Color.blue(c) / 255d;
            }
        }
        small.recycle();
        return out;
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
}
