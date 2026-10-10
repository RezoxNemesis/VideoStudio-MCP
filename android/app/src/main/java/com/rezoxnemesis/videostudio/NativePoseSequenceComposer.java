package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns explicitly supplied, sequential VideoStudio-owned keyframes into real
 * intermediate image poses, stored app-privately and exported by Media3.
 *
 * No Gallery lookup, network call, online AI inference, or remote image upload.
 * An image-to-image flow warp cannot invent unseen limbs or guarantee cinematic
 * coherence between unrelated drawings; expose that limitation honestly.
 */
public final class NativePoseSequenceComposer {
    public interface Progress {
        void onProgress(int generated, int total, double confidence) throws Exception;
    }

    public static final int DEFAULT_WIDTH = 432;
    public static final int DEFAULT_HEIGHT = 768;
    private static final int MAX_FRAMES = 240;

    private NativePoseSequenceComposer() {}

    public static ProjectStore.Project compose(Context context,
                                               ProjectStore store,
                                               ProjectStore.Project original,
                                               ProjectStore.Project output,
                                               List<String> orderedAssetIds,
                                               int width,
                                               int height,
                                               int framesPerPair,
                                               int fps,
                                               Progress progress) throws Exception {
        if (original == null || output == null || original.id.equals(output.id)
                || orderedAssetIds == null
                || orderedAssetIds.size() < 2 || orderedAssetIds.size() > 40) {
            throw new IllegalArgumentException("Pose interpolation requires 2-40 ordered image anchors");
        }
        if (width < 256 || height < 256 || width > 1280 || height > 1920
                || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Pose frames require even, bounded dimensions");
        }
        if (framesPerPair < 2 || framesPerPair > 16 || fps < 12 || fps > 30) {
            throw new IllegalArgumentException("framesPerPair must be 2-16, FPS 12-30");
        }
        final int totalFrames = (orderedAssetIds.size() - 1) * framesPerPair + 1;
        if (totalFrames > MAX_FRAMES) {
            throw new IllegalArgumentException("Requested pose sequence exceeds 240 frames");
        }

        ArrayList<ProjectStore.Asset> anchors = new ArrayList<>();
        for (String id : orderedAssetIds) {
            ProjectStore.Asset asset = original.asset(id);
            if (asset == null || asset.mime == null || !asset.mime.startsWith("image/")) {
                throw new IllegalArgumentException("Unrecognized project image anchor: " + id);
            }
            anchors.add(asset);
        }
        output.assets.clear();
        output.clips.clear();
        output.sourcePrompt = "pose-sequence: ordered native image keyframes, optical-flow inbetweens";
        store.save(output);
        File images = new File(context.getFilesDir(),
                "creative_workspace/" + output.id + "/generated/pose_sequence");
        if (!images.exists() && !images.mkdirs()) {
            throw new IllegalStateException("Cannot create private pose-frame workspace");
        }

        // Delete only stale generated pose frames for this destination; leave
        // user media and other projects untouched after a recoverable retry.
        File[] stale = images.listFiles();
        if (stale != null) for (File f : stale)
            if (f.isFile() && f.getName().matches("pose_[0-9]{4}\\.png") && !f.delete())
                throw new IllegalStateException("Cannot clear partial pose export");
        final long frameDurationMs = Math.max(34, Math.round(1000.0 / fps));
        int[] previous = readPixels(context, anchors.get(0), width, height);
        int generated = 0;
        double accumulatedConfidence = 0;

        for (int pairIndex = 0; pairIndex < anchors.size() - 1; pairIndex++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int[] next = readPixels(context, anchors.get(pairIndex + 1), width, height);
            PoseSequenceFlow.Pair motion = PoseSequenceFlow.analyse(previous, next, width, height);
            accumulatedConfidence += motion.confidence;

            // Frame 0 is the exact first anchor. Every subsequent pair includes
            // only interpolated frames and its exact endpoint, with no duplicate
            // frame at transition boundaries.
            if (pairIndex == 0) {
                saveFrame(output, images, previous, width, height, generated++,
                        frameDurationMs, fps, "anchor");
                if (progress != null) progress.onProgress(generated, totalFrames, motion.confidence);
            }
            for (int step = 1; step <= framesPerPair; step++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                double t = step / (double) framesPerPair;
                int[] pixels = PoseSequenceFlow.between(previous, next, width, height, motion, t);
                String role = step == framesPerPair ? "anchor" : "intermediate";
                saveFrame(output, images, pixels, width, height, generated++,
                        frameDurationMs, fps, role);
                if (progress != null) progress.onProgress(generated, totalFrames, motion.confidence);
            }
            previous = next;
        }
        if (generated != totalFrames) throw new IllegalStateException("Pose frame-count mismatch");
        // One transactional project save rather than a DB write for every
        // frame; source project and anchors were never edited.
        store.save(output);
        return output;
    }

    private static int[] readPixels(Context context,
                                    ProjectStore.Asset asset,
                                    int width,
                                    int height) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream input = context.getContentResolver()
                .openInputStream(Uri.parse(asset.uri))) {
            if (input == null) throw new IllegalArgumentException("Missing VideoStudio-owned image");
            Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
            if (decoded == null) throw new IllegalArgumentException("Unreadable image anchor");
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
            if (scaled != decoded) decoded.recycle();
            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
            scaled.recycle();
            return pixels;
        }
    }

    private static void saveFrame(ProjectStore.Project destination,
                                  File directory,
                                  int[] pixels,
                                  int width,
                                  int height,
                                  int frame,
                                  long durationMs,
                                  int fps,
                                  String frameRole) throws Exception {
        String name = String.format(java.util.Locale.US, "pose_%04d.png", frame);
        File output = new File(directory, name);
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        boolean success;
        try (FileOutputStream file = new FileOutputStream(output)) {
            success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            file.flush();
        } finally {
            bitmap.recycle();
        }
        if (!success || output.length() == 0) {
            output.delete();
            throw new IllegalStateException("Could not encode interpolated frame " + frame);
        }

        ProjectStore.Asset asset = new ProjectStore.Asset();
        asset.id = UUID.randomUUID().toString();
        asset.uri = Uri.fromFile(output).toString();
        asset.name = name;
        asset.mime = "image/png";
        asset.role = "pose_sequence_frame";
        asset.generated = true;
        asset.sizeBytes = output.length();
        asset.seekable = true;
        asset.persistedReadAccess = true;
        asset.generationMetadata = new JSONObject();
        asset.generationMetadata.put("provider", "builtin.videostudio.pose-optical-flow");
        asset.generationMetadata.put("frame", frame);
        asset.generationMetadata.put("poseFrameRole", frameRole);
        destination.assets.add(asset);

        ProjectStore.Clip clip = new ProjectStore.Clip();
        clip.id = UUID.randomUUID().toString();
        clip.assetId = asset.id;
        clip.inMs = 0;
        clip.outMs = durationMs;
        clip.speed = 1;
        clip.transition = "none";
        clip.effects.put("poseInbetween", true);
        clip.effects.put("poseFps", fps);
        destination.clips.add(clip);
    }
}
