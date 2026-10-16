package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Bounded Android-only H.264 encoder for authored Sketch Studio PNG sequences.
 *
 * A long sketch sequence is NOT a playlist of hundreds of 42-ms photo clips:
 * Media3's image composition path is expensive and its exact-source output
 * constraints reject 1200x1500 even though Sketch Studio accepts that canvas.
 *
 * Frames here are read from the already verified, app-private SketchNativeExport
 * snapshot, one at a time. Original documents and raster frames are not changed.
 */
final class SketchFrameStreamEncoder {
    interface Progress {
        void encoding(int frame, int total, String detail) throws Exception;
    }

    static final int SKETCH_SAFE_PIXELS = 1280 * 720;
    static final int SKETCH_SAFE_EDGE = 1280;
    private static final int ALIGN = 16;
    private static final long MAX_ENCODE_TIME_MS = 20L * 60_000L;
    private static final long MAX_IDLE_TIME_MS = 30_000L;

    static final class Size {
        final int width, height;
        Size(int width, int height) { this.width = width; this.height = height; }
    }

    private static final class Selection {
        final MediaCodecInfo codec;
        final Size size;
        Selection(MediaCodecInfo codec, Size size) { this.codec = codec; this.size = size; }
    }

    private SketchFrameStreamEncoder() {}

    static boolean recommended(int sourceWidth, int sourceHeight, int frameCount) {
        return frameCount >= 120 || sourceWidth > SKETCH_SAFE_EDGE ||
                sourceHeight > SKETCH_SAFE_EDGE ||
                (long) sourceWidth * sourceHeight > SKETCH_SAFE_PIXELS;
    }

    /** No distorted portrait: dimensions are both 16-aligned and within a 0.5% aspect error. */
    static Size boundedSize(int sourceWidth, int sourceHeight, String quality) {
        if (sourceWidth < 256 || sourceHeight < 256 || sourceWidth > 2048 ||
                sourceHeight > 2048 || ((sourceWidth | sourceHeight) & 1) != 0) {
            throw new IllegalArgumentException("Invalid native Sketch Studio canvas");
        }
        int maxEdge = "1080p".equals(quality) ? 1920 : SKETCH_SAFE_EDGE;
        long maxPixels = "1080p".equals(quality) ? 1920L * 1080 : SKETCH_SAFE_PIXELS;
        Size best = null;
        long area = -1;
        double bestError = Double.MAX_VALUE;
        for (int w = ALIGN; w <= Math.min(maxEdge, sourceWidth); w += ALIGN) {
            int h = ALIGN * (int) Math.round((double) w * sourceHeight / sourceWidth / ALIGN);
            if (h < ALIGN || h > Math.min(maxEdge, sourceHeight) ||
                    (long) w * h > maxPixels) continue;
            double error = Math.abs((double) w / h - (double) sourceWidth / sourceHeight)
                    / ((double) sourceWidth / sourceHeight);
            if (error > .005) continue;
            long pixels = (long) w * h;
            // Protect visual aspect ratio first; within a 0.1% band use more pixels.
            if (best == null || error < bestError - .001 ||
                    (Math.abs(error - bestError) <= .001 && pixels > area)) {
                best = new Size(w, h);
                bestError = error;
                area = pixels;
            }
        }
        if (best == null) throw new IllegalStateException("SKETCH_CODEC_SIZE: no safe H.264 frame geometry");
        return best;
    }

    private static Selection selectCodec(int sourceWidth, int sourceHeight, String quality, int fps) {
        Size preferred = boundedSize(sourceWidth, sourceHeight, quality);
        MediaCodecInfo[] installed = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        Selection best = null;
        long bestPixels = -1;
        boolean bestHardware = false;
        for (MediaCodecInfo info : installed) {
            if (!info.isEncoder()) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            } catch (IllegalArgumentException unavailable) { continue; }
            boolean flexible = false;
            for (int color : caps.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    flexible = true; break;
                }
            }
            if (!flexible || caps.getVideoCapabilities() == null) continue;
            boolean hardware = info.isHardwareAccelerated();
            for (int w = preferred.width; w >= ALIGN * 16; w -= ALIGN) {
                int h = ALIGN * (int) Math.round((double) w * sourceHeight / sourceWidth / ALIGN);
                if (h > preferred.height || h < ALIGN * 16 ||
                        (long) w * h > (long) preferred.width * preferred.height) continue;
                double error = Math.abs((double) w / h - (double) sourceWidth / sourceHeight)
                        / ((double) sourceWidth / sourceHeight);
                if (error > .005) continue;
                if (!caps.getVideoCapabilities().areSizeAndRateSupported(w, h, fps)) continue;
                long pixels = (long) w * h;
                if (best == null || hardware && !bestHardware ||
                        hardware == bestHardware && pixels > bestPixels) {
                    best = new Selection(info, new Size(w, h));
                    bestHardware = hardware;
                    bestPixels = pixels;
                }
                break;
            }
        }
        if (best == null) throw new IllegalStateException(
                "SKETCH_CODEC_UNAVAILABLE: no Android H.264 encoder supports flexible YUV input");
        return best;
    }

    static JSONObject encode(SketchNativeExport.Prepared prepared, Progress progress) throws Exception {
        JSONObject meta = prepared.metadata;
        int count = meta.getInt("frameCount");
        int fps = NativeFrameClock.requireFps(meta.getInt("fps"));
        int sourceWidth = meta.getInt("width"), sourceHeight = meta.getInt("height");
        if (count < 1 || count > SketchDocument.MAX_FRAMES)
            throw new IllegalArgumentException("Invalid original sketch frame count");
        Selection chosen = selectCodec(sourceWidth, sourceHeight, prepared.quality, fps);
        int width = chosen.size.width, height = chosen.size.height;
        File output = prepared.encodingFile;
        File directory = output.getCanonicalFile().getParentFile();
        if (!directory.isDirectory() || output.exists() && !output.delete())
            throw new IllegalStateException("SKETCH_CODEC_OUTPUT: private encoding directory unavailable");

        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean started = false, muxerStarted = false, completed = false;
        int track = -1, written = 0;
        long startedAt = SystemClock.elapsedRealtime(), lastActivity = startedAt;
        try {
            codec = MediaCodec.createByCodecName(chosen.codec.getName());
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_BIT_RATE,
                    Math.max(1_200_000, Math.min(8_000_000, width * height * fps / 6)));
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            started = true;
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int queued = 0;
            boolean eosSubmitted = false, eosSeen = false;
            int[] first = new int[width], second = new int[width];
            progress.encoding(0, count, "H.264 initialized: " + width + "x" + height);
            while (!eosSeen) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("SKETCH_CODEC_CANCELLED");
                long now = SystemClock.elapsedRealtime();
                if (now - startedAt > MAX_ENCODE_TIME_MS)
                    throw new IllegalStateException("SKETCH_CODEC_TIMEOUT: encode exceeded 20 minutes");
                if (now - lastActivity > MAX_IDLE_TIME_MS)
                    throw new IllegalStateException("SKETCH_CODEC_STALLED: encoder made no progress for 30 seconds");

                if (!eosSubmitted) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        if (queued == count) {
                            codec.queueInputBuffer(index, 0, 0,
                                    NativeFrameClock.presentationTimeUs(count, fps),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosSubmitted = true;
                        } else {
                            File frame = new File(directory,
                                    String.format(Locale.ROOT, "frame_%04d.png", queued));
                            // Paths are restricted to the existing app-private export directory.
                            if (!frame.getCanonicalFile().getParentFile().equals(directory)
                                    || !frame.isFile())
                                throw new IllegalStateException(
                                        "SKETCH_CODEC_FRAME_MISSING: frame " + queued);
                            Bitmap bitmap = decodeFrame(frame, width, height);
                            try {
                                Image image = codec.getInputImage(index);
                                if (image == null) throw new IllegalStateException(
                                        "SKETCH_CODEC_INPUT: flexible YUV input image unavailable");
                                try {
                                    fillYuv420(bitmap, image, first, second);
                                } finally { image.close(); }
                            } finally { bitmap.recycle(); }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2,
                                    NativeFrameClock.presentationTimeUs(queued, fps), 0);
                            queued++;
                            if (queued % 4 == 0 || queued == count)
                                progress.encoding(queued, count,
                                        "H.264 frames queued " + queued + "/" + count);
                        }
                        lastActivity = SystemClock.elapsedRealtime();
                    }
                }
                for (int i = 0; i < 32; i++) {
                    int outputIndex = codec.dequeueOutputBuffer(info, eosSubmitted ? 10_000 : 0);
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IllegalStateException(
                                "SKETCH_CODEC_FORMAT: encoder changed format twice");
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                        lastActivity = SystemClock.elapsedRealtime();
                        continue;
                    }
                    if (outputIndex < 0) continue;
                    try {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            if (!muxerStarted || track < 0)
                                throw new IllegalStateException("SKETCH_CODEC_MUXER: video track unavailable");
                            ByteBuffer encoded = codec.getOutputBuffer(outputIndex);
                            if (encoded == null) throw new IllegalStateException(
                                    "SKETCH_CODEC_OUTPUT: missing encoded buffer");
                            encoded.position(info.offset);
                            encoded.limit(info.offset + info.size);
                            muxer.writeSampleData(track, encoded, info);
                            written++;
                            if (written % 12 == 0 || written == count)
                                progress.encoding(Math.min(written, count), count,
                                        "H.264 samples written " + written + "/" + count);
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            eosSeen = true;
                        lastActivity = SystemClock.elapsedRealtime();
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                    if (eosSeen) break;
                }
            }
            if (written != count) throw new IllegalStateException(
                    "SKETCH_CODEC_FRAME_COUNT: muxed " + written + " of " + count + " frames");
            if (!muxerStarted || output.length() == 0)
                throw new IllegalStateException("SKETCH_CODEC_EMPTY: no completed H.264 samples");
            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;
            codec.stop();
            started = false;
            codec.release();
            codec = null;

            MediaMetadataRetriever verifier = new MediaMetadataRetriever();
            try {
                verifier.setDataSource(output.getAbsolutePath());
                int actualWidth = Integer.parseInt(verifier.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int actualHeight = Integer.parseInt(verifier.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                if (width != actualWidth || height != actualHeight)
                    throw new IllegalStateException("SKETCH_CODEC_SIZE_MISMATCH: MP4 geometry differs");
            } finally { verifier.release(); }
            progress.encoding(count, count, "MP4 samples and dimensions verified");
            completed = true;
            return new JSONObject()
                    .put("ok", true).put("engine", "VideoStudio native MediaCodec + MediaMuxer")
                    .put("encoder", chosen.codec.getName())
                    .put("width", width).put("height", height).put("fps", fps)
                    .put("sourceCanvasWidth", sourceWidth).put("sourceCanvasHeight", sourceHeight)
                    .put("frameCount", count).put("muxedVideoSamples", written)
                    .put("encodedClockVerified", true).put("decodedFrameCountVerified", false)
                    .put("sourceRasterVerified", true).put("nativeSketchStreaming", true)
                    .put("outputBytes", output.length());
        } finally {
            if (started && codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
            }
            if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (!completed && output.exists()) output.delete();
        }
    }

    private static Bitmap decodeFrame(File png, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(png.getAbsolutePath(), options);
        if (options.outWidth < 1 || options.outHeight < 1)
            throw new IllegalStateException("SKETCH_CODEC_PNG: invalid frame header");
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (options.inSampleSize * 2 <=
                Math.min(options.outWidth / width, options.outHeight / height))
            options.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeFile(png.getAbsolutePath(), options);
        if (decoded == null) throw new IllegalStateException(
                "SKETCH_CODEC_PNG: cannot decode frame");
        if (decoded.getWidth() == width && decoded.getHeight() == height) return decoded;
        try {
            return Bitmap.createScaledBitmap(decoded, width, height, true);
        } finally { decoded.recycle(); }
    }

    private static void fillYuv420(Bitmap bitmap, Image image, int[] first, int[] second) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (image.getWidth() != width || image.getHeight() != height ||
                image.getFormat() != android.graphics.ImageFormat.YUV_420_888)
            throw new IllegalStateException("SKETCH_CODEC_YUV: unsupported encoder layout");
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) throw new IllegalStateException(
                "SKETCH_CODEC_YUV: expected three Android planes");
        ByteBuffer Y = planes[0].getBuffer(), U = planes[1].getBuffer(), V = planes[2].getBuffer();
        int yStride = planes[0].getRowStride(), yPixel = planes[0].getPixelStride();
        int uStride = planes[1].getRowStride(), vStride = planes[2].getRowStride();
        int uPixel = planes[1].getPixelStride(), vPixel = planes[2].getPixelStride();
        for (int y = 0; y < height; y += 2) {
            bitmap.getPixels(first, 0, width, 0, y, width, 1);
            bitmap.getPixels(second, 0, width, 0, y + 1, width, 1);
            for (int x = 0; x < width; x++) {
                int c0 = first[x], c1 = second[x];
                Y.put(y * yStride + x * yPixel, (byte) luma(c0));
                Y.put((y + 1) * yStride + x * yPixel, (byte) luma(c1));
            }
            for (int x = 0; x < width; x += 2) {
                int avgR = (red(first[x]) + red(first[x + 1]) +
                        red(second[x]) + red(second[x + 1]) + 2) / 4;
                int avgG = (green(first[x]) + green(first[x + 1]) +
                        green(second[x]) + green(second[x + 1]) + 2) / 4;
                int avgB = (blue(first[x]) + blue(first[x + 1]) +
                        blue(second[x]) + blue(second[x + 1]) + 2) / 4;
                int u = clamp((-38 * avgR - 74 * avgG + 112 * avgB + 128) / 256 + 128);
                int v = clamp((112 * avgR - 94 * avgG - 18 * avgB + 128) / 256 + 128);
                U.put((y / 2) * uStride + (x / 2) * uPixel, (byte) u);
                V.put((y / 2) * vStride + (x / 2) * vPixel, (byte) v);
            }
        }
    }

    private static int red(int c) { return (c >>> 16) & 255; }
    private static int green(int c) { return (c >>> 8) & 255; }
    private static int blue(int c) { return c & 255; }
    private static int luma(int c) {
        return clamp(((66 * red(c) + 129 * green(c) + 25 * blue(c) + 128) >> 8) + 16);
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
