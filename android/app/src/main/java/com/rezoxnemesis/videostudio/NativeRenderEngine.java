package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class NativeRenderEngine {
    public interface Listener {
        void onProgress(int progress, String detail);
        void onCompleted(File file, JSONObject result);
        void onError(String message);
    }

    public static final class Handle {
        private final Transformer transformer;
        private final Handler main;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private Runnable progressTask;

        Handle(Transformer transformer, Handler main) {
            this.transformer = transformer;
            this.main = main;
        }

        public void cancel() {
            cancelled.set(true);
            main.post(() -> {
                try { transformer.cancel(); } catch (Exception ignored) {}
                if (progressTask != null) main.removeCallbacks(progressTask);
            });
        }

        boolean isCancelled() { return cancelled.get(); }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NativeRenderEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public Handle export(ProjectStore.Project project, File outputFile, String aspect, String quality, Listener listener) {
        if (project == null || project.clips.isEmpty()) {
            listener.onError("Timeline is empty");
            return null;
        }
        try {
            if (outputFile.exists() && !outputFile.delete()) {
                listener.onError("Could not replace previous export");
                return null;
            }
            List<EditedMediaItem> edited = new ArrayList<>();
            boolean allVideoWithAudio = true;
            for (ProjectStore.Clip clip : project.clips) {
                ProjectStore.Asset asset = project.asset(clip.assetId);
                if (asset == null) continue;
                boolean isImage = asset.mime != null && asset.mime.startsWith("image/");
                boolean isVideo = asset.mime != null && asset.mime.startsWith("video/");
                if (!isVideo) allVideoWithAudio = false;
                edited.add(buildItem(asset, clip, aspect, quality, isImage));
            }
            if (edited.isEmpty()) {
                listener.onError("No renderable clips");
                return null;
            }

            EditedMediaItemSequence sequence = allVideoWithAudio
                    ? EditedMediaItemSequence.withAudioAndVideoFrom(edited)
                    : EditedMediaItemSequence.withVideoFrom(edited);
            Composition composition = new Composition.Builder(sequence).build();

            Transformer transformer = new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            JSONObject info = new JSONObject();
                            try {
                                info.put("ok", true);
                                info.put("path", outputFile.getAbsolutePath());
                                info.put("sizeBytes", outputFile.length());
                                info.put("durationMs", project.outputDurationMs());
                                info.put("clipCount", project.clips.size());
                                info.put("aspect", aspect);
                                info.put("quality", quality);
                                info.put("engine", "Media3 Transformer 1.11.1");
                            } catch (Exception ignored) {}
                            listener.onProgress(100, "Export complete");
                            listener.onCompleted(outputFile, info);
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException exception) {
                            listener.onError(exception.getMessage() == null ? "Native export failed" : exception.getMessage());
                        }
                    })
                    .build();

            Handle handle = new Handle(transformer, main);
            main.post(() -> {
                if (handle.isCancelled()) return;
                try {
                    transformer.start(composition, outputFile.getAbsolutePath());
                    startProgressPolling(handle, listener);
                } catch (Exception e) {
                    listener.onError(e.getMessage() == null ? "Could not start native export" : e.getMessage());
                }
            });
            return handle;
        } catch (Exception error) {
            listener.onError(error.getMessage() == null ? "Could not prepare native export" : error.getMessage());
            return null;
        }
    }

    private EditedMediaItem buildItem(ProjectStore.Asset asset, ProjectStore.Clip clip, String aspect, String quality, boolean image) {
        long inputDurationMs = Math.max(100, clip.outMs - clip.inMs);
        MediaItem.Builder media = new MediaItem.Builder().setUri(Uri.parse(asset.uri));

        if (image) {
            media.setImageDurationMs(Math.max(250, clip.outputDurationMs()));
        } else {
            media.setClippingConfiguration(
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(Math.max(0, clip.inMs))
                            .setEndPositionMs(Math.max(clip.inMs + 100, clip.outMs))
                            .build());
        }

        EditedMediaItem.Builder edited = new EditedMediaItem.Builder(media.build());
        if (image) edited.setFrameRate(30);

        if (!image && Math.abs(clip.speed - 1f) > .01f) {
            final float speed = Math.max(.25f, Math.min(4f, clip.speed));
            edited.setSpeed(new SpeedProvider() {
                @Override public float getSpeed(long timeUs) { return speed; }
                @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
            });
        }

        List<AudioProcessor> audio = Collections.emptyList();
        List<Effect> video = buildEffects(clip, aspect, quality, inputDurationMs);
        edited.setEffects(new Effects(audio, video));
        if (!asset.mime.startsWith("video/")) edited.setRemoveAudio(true);
        return edited.build();
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip, String aspect, String quality, long inputDurationMs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        float targetAspect = aspectRatio(aspect);
        effects.add(Presentation.createForAspectRatio(targetAspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = "720p".equalsIgnoreCase(quality) ? ("16:9".equals(aspect) ? 720 : 1280) : ("16:9".equals(aspect) ? 1080 : 1920);
        effects.add(Presentation.createForHeight(height));

        double brightness = fx.optDouble("brightness", 0);
        double contrast = fx.optDouble("contrast", 0);
        double saturation = fx.optDouble("saturationAdjust", fx.optDouble("saturation", 0));
        double lightness = fx.optDouble("lightnessAdjust", 0);
        String preset = fx.optString("effectPreset", fx.optString("colorPreset", ""));
        if (!preset.isEmpty() && !"none".equals(preset)) {
            JSONObject p = CreatorCatalog.effectPreset(preset);
            if (!fx.has("brightness")) brightness = p.optDouble("brightness", brightness);
            if (!fx.has("contrast")) contrast = p.optDouble("contrast", contrast);
            if (!fx.has("saturationAdjust") && !fx.has("saturation")) saturation = p.optDouble("saturationAdjust", saturation);
            if (!fx.has("lightnessAdjust")) lightness = p.optDouble("lightnessAdjust", lightness);
        }

        brightness = clamp(brightness, -1, 1);
        contrast = clamp(contrast, -1, 1);
        saturation = clamp(saturation, -100, 100);
        lightness = clamp(lightness, -100, 100);
        if (Math.abs(brightness) > .001) effects.add(new Brightness((float) brightness));
        if (Math.abs(contrast) > .001) effects.add(new Contrast((float) contrast));
        if (Math.abs(saturation) > .001 || Math.abs(lightness) > .001) {
            effects.add(new HslAdjustment.Builder()
                    .adjustSaturation((float) saturation)
                    .adjustLightness((float) lightness)
                    .build());
        }

        double blur = fx.optDouble("blur", 0);
        if ("gaussian_blur".equals(preset)) blur = Math.max(blur, 5);
        if ("soft_glow".equals(preset) || "dream".equals(preset)) blur = Math.max(blur, 1.6);
        if (blur > .1) effects.add(new GaussianBlur((float) Math.min(18, blur)));

        double rotation = clamp(fx.optDouble("rotate", 0), -45, 45);
        double scale = clamp(fx.optDouble("scale", fx.optDouble("zoom", 1)), .5, 2.5);
        if (Math.abs(rotation) > .01 || Math.abs(scale - 1) > .01) {
            effects.add(new ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees((float) rotation)
                    .setScale((float) scale, (float) scale)
                    .build());
        }

        String motion = fx.optString("motionPreset", "none");
        String transition = clip.transition == null ? "none" : clip.transition;
        if (!"none".equals(motion) || (!"none".equals(transition) && !"cut".equals(transition))) {
            String matrixPreset = "none".equals(motion) ? transition : motion;
            effects.add(new MotionMatrixEffect(matrixPreset, Math.max(100_000, clip.outputDurationMs() * 1000L), 280_000));
        }

        return effects;
    }

    private void startProgressPolling(Handle handle, Listener listener) {
        ProgressHolder holder = new ProgressHolder();
        handle.progressTask = new Runnable() {
            @Override public void run() {
                if (handle.isCancelled()) return;
                try {
                    int state = handle.transformer.getProgress(holder);
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        listener.onProgress(holder.progress, "Native export " + holder.progress + "%");
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED && state != Transformer.PROGRESS_STATE_UNAVAILABLE) {
                        main.postDelayed(this, 450);
                    }
                } catch (Exception ignored) {}
            }
        };
        main.post(handle.progressTask);
    }

    private static float aspectRatio(String aspect) {
        if ("16:9".equals(aspect)) return 16f / 9f;
        if ("1:1".equals(aspect)) return 1f;
        if ("4:5".equals(aspect)) return 4f / 5f;
        return 9f / 16f;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
