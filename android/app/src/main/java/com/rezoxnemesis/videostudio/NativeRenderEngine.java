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
import androidx.media3.effect.OverlayEffect;
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
            final boolean layeredAnimation = hasLayeredAnimation(project);
            Composition composition;
            if (layeredAnimation) {
                composition = buildLayeredAnimationComposition(project, aspect, quality);
            } else {
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
                composition = new Composition.Builder(sequence).build();
            }

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
                                info.put("engine", layeredAnimation
                                        ? "VideoStudio v3.1 Media3 layered parallax"
                                        : "Media3 Transformer 1.11.1");
                                info.put("layeredAnimation", layeredAnimation);
                                info.put("animationMode", layeredAnimation ? "subject-aware-2.5d" : "standard");
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

    private boolean hasLayeredAnimation(ProjectStore.Project project) {
        if (project == null || project.clips.isEmpty()) return false;
        boolean found = false;
        for (ProjectStore.Clip clip : project.clips) {
            ProjectStore.Asset asset = project.asset(clip.assetId);
            if (asset == null || asset.mime == null || !asset.mime.startsWith("image/")) return false;
            JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
            if (!fx.optBoolean("animatedScene", false)) return false;
            if (fx.optString("foregroundUri", "").isEmpty() || fx.optString("backgroundUri", "").isEmpty()) return false;
            if (fx.optJSONObject("animationSpec") == null) return false;
            found = true;
        }
        return found;
    }

    private Composition buildLayeredAnimationComposition(ProjectStore.Project project, String aspect, String quality) {
        List<EditedMediaItem> foreground = new ArrayList<>();
        List<EditedMediaItem> background = new ArrayList<>();

        for (ProjectStore.Clip clip : project.clips) {
            JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
            String fgUri = fx.optString("foregroundUri", "");
            String bgUri = fx.optString("backgroundUri", "");
            JSONObject spec = fx.optJSONObject("animationSpec");
            long durationMs = Math.max(700, clip.outputDurationMs());

            // Media3's compositor treats the first sequence as the overlay in
            // its moving-overlay examples, so keep the alpha foreground first.
            foreground.add(buildLayerItem(fgUri, clip, aspect, quality, durationMs, spec, "foreground"));
            background.add(buildLayerItem(bgUri, clip, aspect, quality, durationMs, spec, "background"));
        }

        EditedMediaItemSequence foregroundSequence = EditedMediaItemSequence.withVideoFrom(foreground);
        EditedMediaItemSequence backgroundSequence = EditedMediaItemSequence.withVideoFrom(background);
        return new Composition.Builder(foregroundSequence, backgroundSequence).build();
    }

    private EditedMediaItem buildLayerItem(String uri,
                                           ProjectStore.Clip clip,
                                           String aspect,
                                           String quality,
                                           long durationMs,
                                           JSONObject animationSpec,
                                           String layerRole) {
        MediaItem media = new MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setImageDurationMs(durationMs)
                .build();

        EditedMediaItem.Builder item = new EditedMediaItem.Builder(media)
                .setFrameRate(30)
                .setRemoveAudio(true);

        List<Effect> video = buildLayerEffects(clip, aspect, quality, durationMs, animationSpec, layerRole);
        item.setEffects(new Effects(Collections.emptyList(), video));
        return item.build();
    }

    private List<Effect> buildLayerEffects(ProjectStore.Clip clip,
                                           String aspect,
                                           String quality,
                                           long durationMs,
                                           JSONObject animationSpec,
                                           String layerRole) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        effects.add(Presentation.createForAspectRatio(aspectRatio(aspect), Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = "720p".equalsIgnoreCase(quality)
                ? ("16:9".equals(aspect) ? 720 : 1280)
                : ("16:9".equals(aspect) ? 1080 : 1920);
        effects.add(Presentation.createForHeight(height));

        // Keep a safety overscan so parallax never reveals the edge of a plate.
        float overscan = "background".equals(layerRole) ? 1.10f : 1.035f;
        effects.add(new ScaleAndRotateTransformation.Builder()
                .setScale(overscan, overscan)
                .build());

        applyColourEffects(effects, fx);

        String preset = animationSpec == null
                ? fx.optString("motionPreset", "push_in")
                : animationSpec.optString("cameraPreset", fx.optString("motionPreset", "push_in"));
        long durationUs = Math.max(100_000L, durationMs * 1000L);
        effects.add(new MotionMatrixEffect(
                preset,
                durationUs,
                0,
                animationSpec,
                layerRole
        ));

        // Atmosphere is drawn only once, on the alpha foreground sequence,
        // after its spatial transform. That keeps mist/rain/light in screen
        // space while the subject and background move independently.
        if ("foreground".equals(layerRole) && animationSpec != null) {
            String environment = animationSpec.optString("environmentMotion", "ambient_drift");
            double atmosphere = animationSpec.optDouble("atmosphereIntensity", .42);
            effects.add(new OverlayEffect(Collections.singletonList(
                    new AtmosphereOverlay(environment, atmosphere, durationUs)
            )));
        }
        return effects;
    }

    private void applyColourEffects(List<Effect> effects, JSONObject fx) {
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
        if (asset.mime == null || !asset.mime.startsWith("video/")) edited.setRemoveAudio(true);
        return edited.build();
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip, String aspect, String quality, long inputDurationMs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        float targetAspect = aspectRatio(aspect);
        effects.add(Presentation.createForAspectRatio(targetAspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = "720p".equalsIgnoreCase(quality) ? ("16:9".equals(aspect) ? 720 : 1280) : ("16:9".equals(aspect) ? 1080 : 1920);
        effects.add(Presentation.createForHeight(height));

        String preset = fx.optString("effectPreset", fx.optString("colorPreset", ""));
        applyColourEffects(effects, fx);

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
