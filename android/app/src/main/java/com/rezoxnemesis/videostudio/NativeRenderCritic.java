package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Lightweight, fully local continuity/technical critic for rendered video.
 *
 * This is deliberately not presented as semantic vision intelligence. It
 * samples frames and measures black/overexposed frames, freeze-like spans,
 * abrupt visual jumps, luminance stability and decode health. More capable
 * visual critique providers can later replace or augment it through the same
 * render.critique capability.
 */
public final class NativeRenderCritic {
    private final Context context;

    public NativeRenderCritic(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject critique(ProjectStore.Asset asset, int requestedSamples) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Rendered video asset is required");
        if (asset.mime == null || !asset.mime.startsWith("video/")) {
            throw new IllegalArgumentException("Render critique requires a video asset");
        }

        int samples = Math.max(8, Math.min(24, requestedSamples));
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        ArrayList<FrameStats> stats = new ArrayList<>();
        try {
            retriever.setDataSource(context, Uri.parse(asset.uri));
            long durationMs = parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    asset.durationMs
            );
            int width = (int) parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int height = (int) parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);

            long span = Math.max(1, durationMs);
            for (int i = 0; i < samples; i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long timeMs = samples == 1 ? 0 : Math.round((i / (double) (samples - 1)) * Math.max(0, span - 1));
                Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) continue;
                Bitmap small = scale(frame, 48, 27);
                if (small != frame) frame.recycle();
                stats.add(measure(small, timeMs));
                small.recycle();
            }

            if (stats.size() < 3) throw new IllegalStateException("Not enough rendered frames could be decoded for critique");

            int black = 0;
            int over = 0;
            int freeze = 0;
            int abrupt = 0;
            double motionTotal = 0;
            double brightnessTotal = 0;
            double brightnessSq = 0;
            double minLuma = 1;
            double maxLuma = 0;

            JSONArray timeline = new JSONArray();
            FrameStats previous = null;
            for (FrameStats current : stats) {
                double diff = previous == null ? 0 : difference(previous.signature, current.signature);
                if (current.meanLuma < 0.045) black++;
                if (current.meanLuma > 0.955) over++;
                if (previous != null) {
                    if (diff < 0.006) freeze++;
                    if (diff > 0.23) abrupt++;
                    motionTotal += diff;
                }
                brightnessTotal += current.meanLuma;
                brightnessSq += current.meanLuma * current.meanLuma;
                minLuma = Math.min(minLuma, current.meanLuma);
                maxLuma = Math.max(maxLuma, current.meanLuma);

                JSONObject sample = new JSONObject();
                sample.put("timeMs", current.timeMs);
                sample.put("luma", round(current.meanLuma));
                sample.put("contrast", round(current.contrast));
                if (previous != null) sample.put("frameDiff", round(diff));
                timeline.put(sample);
                previous = current;
            }

            int transitions = Math.max(1, stats.size() - 1);
            double blackRatio = black / (double) stats.size();
            double overRatio = over / (double) stats.size();
            double freezeRatio = freeze / (double) transitions;
            double avgDiff = motionTotal / transitions;
            double meanBrightness = brightnessTotal / stats.size();
            double variance = Math.max(0, brightnessSq / stats.size() - meanBrightness * meanBrightness);
            double brightnessStdDev = Math.sqrt(variance);

            JSONArray issues = new JSONArray();
            if (blackRatio > 0.10) issues.put(issue("black_frames", "Several sampled frames are nearly black", blackRatio));
            if (overRatio > 0.10) issues.put(issue("overexposure", "Several sampled frames are heavily overexposed", overRatio));
            if (freezeRatio > 0.55) issues.put(issue("low_temporal_change", "Long portions may be visually static or frozen", freezeRatio));
            if (abrupt >= Math.max(2, stats.size() / 4)) issues.put(issue("abrupt_visual_jumps", "Frequent large frame-to-frame visual jumps detected", abrupt / (double) transitions));
            if (brightnessStdDev > 0.22) issues.put(issue("brightness_instability", "Large luminance swings may indicate inconsistent shots", brightnessStdDev));
            if (width <= 0 || height <= 0) issues.put(issue("metadata", "Video dimensions could not be read reliably", 1));

            double penalty = 0;
            penalty += Math.min(35, blackRatio * 100);
            penalty += Math.min(25, overRatio * 80);
            penalty += Math.min(18, Math.max(0, freezeRatio - 0.35) * 45);
            penalty += Math.min(15, abrupt * 3.5);
            penalty += Math.min(12, Math.max(0, brightnessStdDev - 0.12) * 60);
            int score = (int) Math.max(0, Math.min(100, Math.round(100 - penalty)));

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("engine", "native-continuity-heuristics-v1");
            result.put("semanticVision", false);
            result.put("assetId", asset.id);
            result.put("name", asset.name);
            result.put("durationMs", durationMs);
            result.put("width", width);
            result.put("height", height);
            result.put("sampleCount", stats.size());
            result.put("score", score);
            result.put("blackFrameRatio", round(blackRatio));
            result.put("overexposedFrameRatio", round(overRatio));
            result.put("freezeLikeRatio", round(freezeRatio));
            result.put("abruptJumpCount", abrupt);
            result.put("averageFrameDifference", round(avgDiff));
            result.put("meanLuminance", round(meanBrightness));
            result.put("luminanceStdDev", round(brightnessStdDev));
            result.put("minLuminance", round(minLuma));
            result.put("maxLuminance", round(maxLuma));
            result.put("issues", issues);
            result.put("samples", timeline);
            result.put("recommendation", issues.length() == 0
                    ? "Technical continuity checks passed"
                    : "Target only flagged stages/shots for repair; preserve unaffected cached nodes");
            return result;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static FrameStats measure(Bitmap bitmap, long timeMs) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        double[] signature = new double[width * height];
        double sum = 0;
        double sumSq = 0;
        int at = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = bitmap.getPixel(x, y);
                double luma = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255d;
                signature[at++] = luma;
                sum += luma;
                sumSq += luma * luma;
            }
        }
        double mean = signature.length == 0 ? 0 : sum / signature.length;
        double variance = signature.length == 0 ? 0 : Math.max(0, sumSq / signature.length - mean * mean);
        return new FrameStats(timeMs, mean, Math.sqrt(variance), signature);
    }

    private static double difference(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0;
        double total = 0;
        for (int i = 0; i < n; i++) total += Math.abs(a[i] - b[i]);
        return total / n;
    }

    private static Bitmap scale(Bitmap source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) return source;
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static JSONObject issue(String code, String message, double severity) {
        JSONObject issue = new JSONObject();
        try {
            issue.put("code", code);
            issue.put("message", message);
            issue.put("severity", round(Math.max(0, Math.min(1, severity))));
        } catch (Exception ignored) {}
        return issue;
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static final class FrameStats {
        final long timeMs;
        final double meanLuma;
        final double contrast;
        final double[] signature;

        FrameStats(long timeMs, double meanLuma, double contrast, double[] signature) {
            this.timeMs = timeMs;
            this.meanLuma = meanLuma;
            this.contrast = contrast;
            this.signature = signature;
        }
    }
}
