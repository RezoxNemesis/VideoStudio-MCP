package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class PromptVideoRenderer {
    public interface Progress {
        void onProgress(int percent, String detail) throws InterruptedException;
    }

    private static final String MIME = "video/avc";

    private static final class Scene {
        Bitmap bitmap;
        String caption;
        String motion;
        String font;
        long durationMs;
    }

    public File render(android.content.Context context,
                       ProjectStore.Project project,
                       JSONArray sceneSpecs,
                       File output,
                       int width,
                       int height,
                       int fps,
                       Progress progress) throws Exception {
        if (sceneSpecs == null || sceneSpecs.length() == 0) throw new IllegalArgumentException("At least one scene is required");
        width = clampEven(width, 360, 1280);
        height = clampEven(height, 360, 1920);
        fps = Math.max(12, Math.min(30, fps));

        List<Scene> scenes = new ArrayList<>();
        long totalMs = 0;
        try {
            for (int i = 0; i < sceneSpecs.length(); i++) {
                JSONObject spec = sceneSpecs.optJSONObject(i);
                if (spec == null) continue;
                ProjectStore.Asset asset = project.asset(spec.optString("assetId"));
                if (asset == null || !asset.mime.startsWith("image/")) {
                    throw new IllegalArgumentException("Scene " + i + " must reference an imported image asset");
                }
                Scene scene = new Scene();
                scene.bitmap = loadBitmap(context, Uri.parse(asset.uri), width, height);
                if (scene.bitmap == null) throw new IllegalArgumentException("Could not decode scene image " + asset.name);
                scene.caption = spec.optString("caption", "");
                scene.motion = spec.optString("motion", "push_in");
                scene.font = spec.optString("font", "sans");
                scene.durationMs = Math.max(800, Math.min(15000, spec.optLong("durationMs", 3000)));
                scenes.add(scene);
                totalMs += scene.durationMs;
            }
            if (scenes.isEmpty()) throw new IllegalArgumentException("No renderable scenes");

            if (output.getParentFile() != null && !output.getParentFile().exists() && !output.getParentFile().mkdirs()) {
                throw new IllegalStateException("Could not create render directory");
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_000_000, width * height * fps / 5));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            MediaCodec codec = MediaCodec.createEncoderByType(MIME);
            MediaMuxer muxer = null;
            boolean muxerStarted = false;
            int track = -1;
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                Bitmap frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(frameBitmap);
                byte[] yuv = new byte[width * height * 3 / 2];

                long renderedMs = 0;
                long ptsUs = 0;
                for (int si = 0; si < scenes.size(); si++) {
                    Scene scene = scenes.get(si);
                    int frames = Math.max(1, (int) Math.ceil(scene.durationMs * fps / 1000d));
                    for (int fi = 0; fi < frames; fi++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        float t = frames <= 1 ? 1f : fi / (float) (frames - 1);
                        drawScene(canvas, frameBitmap, scene, t);
                        argbToI420(frameBitmap, yuv, width, height);

                        boolean queued = false;
                        while (!queued) {
                            int input = codec.dequeueInputBuffer(10_000);
                            if (input >= 0) {
                                ByteBuffer in = codec.getInputBuffer(input);
                                if (in == null) throw new IllegalStateException("Encoder input buffer unavailable");
                                in.clear();
                                in.put(yuv);
                                codec.queueInputBuffer(input, 0, yuv.length, ptsUs, 0);
                                queued = true;
                            }
                            DrainResult d = drain(codec, muxer, track, muxerStarted, info, false);
                            track = d.track;
                            muxerStarted = d.muxerStarted;
                        }
                        ptsUs += 1_000_000L / fps;
                        renderedMs += 1000L / fps;
                        int percent = Math.min(96, 2 + (int) (94d * renderedMs / Math.max(1, totalMs)));
                        progress.onProgress(percent, "Rendering scene " + (si + 1) + " / " + scenes.size());
                    }
                }

                boolean eosQueued = false;
                while (!eosQueued) {
                    int input = codec.dequeueInputBuffer(10_000);
                    if (input >= 0) {
                        codec.queueInputBuffer(input, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                    DrainResult d = drain(codec, muxer, track, muxerStarted, info, false);
                    track = d.track;
                    muxerStarted = d.muxerStarted;
                }

                boolean eos = false;
                while (!eos) {
                    DrainResult d = drain(codec, muxer, track, muxerStarted, info, true);
                    track = d.track;
                    muxerStarted = d.muxerStarted;
                    eos = d.eos;
                }
                frameBitmap.recycle();
                progress.onProgress(100, "Prompt video ready");
            } finally {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                if (muxer != null) {
                    try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
                    try { muxer.release(); } catch (Exception ignored) {}
                }
            }
            return output;
        } finally {
            for (Scene scene : scenes) {
                if (scene.bitmap != null && !scene.bitmap.isRecycled()) scene.bitmap.recycle();
            }
        }
    }

    private static final class DrainResult {
        int track;
        boolean muxerStarted;
        boolean eos;
    }

    private DrainResult drain(MediaCodec codec, MediaMuxer muxer, int track, boolean muxerStarted,
                              MediaCodec.BufferInfo info, boolean waitForEos) {
        DrainResult result = new DrainResult();
        result.track = track;
        result.muxerStarted = muxerStarted;
        int spins = waitForEos ? 100 : 4;
        while (spins-- > 0) {
            int outIndex = codec.dequeueOutputBuffer(info, waitForEos ? 10_000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (result.muxerStarted) throw new IllegalStateException("Encoder format changed twice");
                result.track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                result.muxerStarted = true;
                continue;
            }
            if (outIndex >= 0) {
                ByteBuffer out = codec.getOutputBuffer(outIndex);
                if (out != null && info.size > 0 && result.muxerStarted) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    muxer.writeSampleData(result.track, out, info);
                }
                result.eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outIndex, false);
                if (result.eos) break;
            }
        }
        return result;
    }

    private void drawScene(Canvas canvas, Bitmap target, Scene scene, float t) {
        canvas.drawColor(Color.BLACK);
        float base = Math.max(target.getWidth() / (float) scene.bitmap.getWidth(), target.getHeight() / (float) scene.bitmap.getHeight());
        float zoom;
        float dx = 0f, dy = 0f;
        switch (scene.motion) {
            case "pull_out": zoom = 1.10f - .10f * t; break;
            case "pan_left": zoom = 1.08f; dx = target.getWidth() * .06f * (1f - 2f * t); break;
            case "pan_right": zoom = 1.08f; dx = target.getWidth() * .06f * (-1f + 2f * t); break;
            case "float": zoom = 1.04f + .025f * (float) Math.sin(t * Math.PI); dy = target.getHeight() * .025f * (float) Math.sin(t * Math.PI * 2); break;
            default: zoom = 1f + .10f * t; break;
        }
        float scale = base * zoom;
        float drawW = scene.bitmap.getWidth() * scale;
        float drawH = scene.bitmap.getHeight() * scale;
        RectF dst = new RectF((target.getWidth() - drawW) / 2f + dx,
                (target.getHeight() - drawH) / 2f + dy,
                (target.getWidth() + drawW) / 2f + dx,
                (target.getHeight() + drawH) / 2f + dy);
        Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(scene.bitmap, null, dst, imagePaint);

        if (scene.caption != null && !scene.caption.trim().isEmpty()) {
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setColor(Color.argb(145, 0, 0, 0));
            float barTop = target.getHeight() * .74f;
            canvas.drawRoundRect(new RectF(target.getWidth() * .06f, barTop,
                    target.getWidth() * .94f, target.getHeight() * .94f), 28, 28, shade);

            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Math.max(34, target.getWidth() * .055f));
            text.setTypeface(typeface(scene.font));
            drawWrappedText(canvas, scene.caption, text, target.getWidth() * .5f,
                    barTop + text.getTextSize() * 1.25f, target.getWidth() * .78f);
        }
    }

    private Typeface typeface(String preset) {
        if ("serif".equals(preset)) return Typeface.create("serif", Typeface.BOLD);
        if ("mono".equals(preset)) return Typeface.create("monospace", Typeface.BOLD);
        if ("rounded".equals(preset)) return Typeface.create("sans-serif-rounded", Typeface.BOLD);
        if ("condensed".equals(preset)) return Typeface.create("sans-serif-condensed", Typeface.BOLD);
        return Typeface.create("sans-serif", Typeface.BOLD);
    }

    private void drawWrappedText(Canvas canvas, String value, Paint paint, float centerX, float startY, float maxWidth) {
        String[] words = value.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        float y = startY;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), centerX, y, paint);
                y += paint.getTextSize() * 1.18f;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) canvas.drawText(line.toString(), centerX, y, paint);
    }

    private Bitmap loadBitmap(android.content.Context context, Uri uri, int width, int height) throws Exception {
        InputStream in = null;
        try {
            if ("file".equals(uri.getScheme())) in = new FileInputStream(new File(uri.getPath()));
            else in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap raw = BitmapFactory.decodeStream(in);
            if (raw == null) return null;
            int max = Math.max(width, height) * 2;
            if (Math.max(raw.getWidth(), raw.getHeight()) <= max) return raw;
            float scale = max / (float) Math.max(raw.getWidth(), raw.getHeight());
            Bitmap resized = Bitmap.createScaledBitmap(raw, Math.max(2, (int)(raw.getWidth()*scale)), Math.max(2, (int)(raw.getHeight()*scale)), true);
            raw.recycle();
            return resized;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private void argbToI420(Bitmap bitmap, byte[] out, int width, int height) {
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int yIndex = 0;
        int uIndex = width * height;
        int vIndex = uIndex + width * height / 4;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = pixels[j * width + i];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int y = ((66*r + 129*g + 25*b + 128) >> 8) + 16;
                int u = ((-38*r - 74*g + 112*b + 128) >> 8) + 128;
                int v = ((112*r - 94*g - 18*b + 128) >> 8) + 128;
                out[yIndex++] = (byte) clamp(y);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    out[uIndex++] = (byte) clamp(u);
                    out[vIndex++] = (byte) clamp(v);
                }
            }
        }
    }

    private int clampEven(int value, int min, int max) {
        int v = Math.max(min, Math.min(max, value));
        return (v & 1) == 0 ? v : v - 1;
    }

    private int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
