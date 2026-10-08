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
            if (project.clips.stream().anyMatch(c -> c.track != 0)) {
                composition = buildMultitrack(project,aspect,quality);
            } else if (layeredAnimation) {
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

            AtomicBoolean finished = new AtomicBoolean(false);
            Transformer transformer = new Transformer.Builder(context)
                    .setVideoMimeType("hevc".equals(project.clips.get(0).effects.optString("_exportCodec"))?MimeTypes.VIDEO_H265:MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                            .setEnableFallback(true)
                            .setEnableFormatFallback(true)
                            .setRequestedVideoEncoderSettings(new androidx.media3.transformer.VideoEncoderSettings.Builder()
                                    .setBitrate(project.clips.get(0).effects.optInt("_exportBitrate",8000000)).build())
                            .setRequestedAudioEncoderSettings(new androidx.media3.transformer.AudioEncoderSettings.Builder()
                                    .setBitrate(128000).build())
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            finished.set(true);
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
                                        ? (hasArticulatedAnimation(project)
                                                ? "VideoStudio v3.2 Media3 articulated portrait"
                                                : "VideoStudio v3.1 Media3 layered parallax")
                                        : "Media3 Transformer 1.11.1");
                                info.put("layeredAnimation", layeredAnimation);
                                info.put("animationMode", layeredAnimation
                                        ? (hasArticulatedAnimation(project) ? "articulated-subject-2.5d" : "subject-aware-2.5d")
                                        : "standard");
                            } catch (Exception ignored) {}
                            listener.onProgress(100, "Export complete");
                            listener.onCompleted(outputFile, info);
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException exception) {
                            finished.set(true);
                            listener.onError(exception.getMessage() == null ? "Native export failed" : exception.getMessage());
                        }
                    })
                    .build();

            Handle handle = new Handle(transformer, main);
            main.post(() -> {
                if (handle.isCancelled()) return;
                try {
                    transformer.start(composition, outputFile.getAbsolutePath());
                    startProgressPolling(handle, listener, finished);
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

    static int outputHeight(String aspect, String quality) {
        int shortEdge = quality.equals("320p") ? 320 : quality.equals("512p") ? 512 : quality.equals("480p") ? 480 : quality.equals("720p") ? 720 : 1080;
        return Math.round(shortEdge / Math.min(1f, aspectRatio(aspect)) / 2f) * 2;
    }

    public Composition previewComposition(ProjectStore.Project project,String aspect) {
        ProjectStore.Project preview=ProjectStore.Project.fromJson(project.toJson());
        for(ProjectStore.Asset asset:preview.assets){ProjectStore.Asset original=project.asset(asset.id);asset.uri=ProxyManager.previewUri(project,original);}
        for(ProjectStore.Clip clip:preview.clips){clip.effects.remove("_exportFps");clip.effects.remove("_exportBitrate");}
        if(hasLayeredAnimation(preview))return buildLayeredAnimationComposition(preview,aspect,"512p");
        return buildMultitrack(preview,aspect,"512p");
    }

    private Composition buildMultitrack(ProjectStore.Project p,String aspect,String quality) {
        ArrayList<EditedMediaItemSequence> sequences=new ArrayList<>();
        for(int track=3;track>=0;track--) {
            final int selected=track;
            List<ProjectStore.Clip> clips=new ArrayList<>();for(ProjectStore.Clip c:p.clips)if(c.track==selected)clips.add(c);
            if(clips.isEmpty())continue;
            boolean audioOnly=clips.stream().allMatch(c->p.asset(c.assetId).mime.startsWith("audio/"));
            if(clips.stream().anyMatch(c->p.asset(c.assetId).mime.startsWith("audio/"))&&!audioOnly)throw new IllegalArgumentException("Keep audio and visual media on separate tracks");
            EditedMediaItemSequence.Builder builder=new EditedMediaItemSequence.Builder(audioOnly?Collections.singleton(C.TRACK_TYPE_AUDIO):new java.util.HashSet<>(java.util.Arrays.asList(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_VIDEO)));
            if(track!=0)clips.sort(java.util.Comparator.comparingLong(c->c.timelineStartMs));
            long cursor=0;
            for(ProjectStore.Clip c:clips){
                long start=track==0?cursor:c.timelineStartMs;
                if(start<cursor)throw new IllegalArgumentException("Clips overlap on the same track; move one to another track");
                if(start>cursor)builder.addGap((start-cursor)*1000);
                ProjectStore.Asset a=p.asset(c.assetId);builder.addItem(buildItem(a,c,aspect,quality,a.mime.startsWith("image/")));cursor=start+c.outputDurationMs();
            }
            if(cursor<p.outputDurationMs())builder.addGap((p.outputDurationMs()-cursor)*1000);
            sequences.add(builder.build());
        }
        return new Composition.Builder(sequences).build();
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
        boolean articulated = hasArticulatedAnimation(project);
        if (articulated) {
            List<EditedMediaItem> head = new ArrayList<>();
            List<EditedMediaItem> torso = new ArrayList<>();
            List<EditedMediaItem> lower = new ArrayList<>();
            List<EditedMediaItem> background = new ArrayList<>();

            for (ProjectStore.Clip clip : project.clips) {
                JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
                JSONObject spec = fx.optJSONObject("animationSpec");
                long durationMs = Math.max(700, clip.outputDurationMs());

                head.add(buildLayerItem(fx.optString("headUri"), clip, aspect, quality, durationMs, spec, "head"));
                torso.add(buildLayerItem(fx.optString("torsoUri"), clip, aspect, quality, durationMs, spec, "torso"));
                lower.add(buildLayerItem(fx.optString("lowerUri"), clip, aspect, quality, durationMs, spec, "lower"));
                background.add(buildLayerItem(fx.optString("backgroundUri"), clip, aspect, quality, durationMs, spec, "background"));
            }

            // Earlier sequences are composited above later sequences. The
            // feathered bands add back up to the original subject alpha while
            // their independent transforms create articulated motion.
            return new Composition.Builder(
                    EditedMediaItemSequence.withVideoFrom(head),
                    EditedMediaItemSequence.withVideoFrom(torso),
                    EditedMediaItemSequence.withVideoFrom(lower),
                    EditedMediaItemSequence.withVideoFrom(background)
            ).build();
        }

        List<EditedMediaItem> foreground = new ArrayList<>();
        List<EditedMediaItem> background = new ArrayList<>();
        for (ProjectStore.Clip clip : project.clips) {
            JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
            JSONObject spec = fx.optJSONObject("animationSpec");
            long durationMs = Math.max(700, clip.outputDurationMs());
            foreground.add(buildLayerItem(fx.optString("foregroundUri"), clip, aspect, quality, durationMs, spec, "foreground"));
            background.add(buildLayerItem(fx.optString("backgroundUri"), clip, aspect, quality, durationMs, spec, "background"));
        }
        return new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(foreground),
                EditedMediaItemSequence.withVideoFrom(background)
        ).build();
    }

    private boolean hasArticulatedAnimation(ProjectStore.Project project) {
        if (project == null || project.clips.isEmpty()) return false;
        for (ProjectStore.Clip clip : project.clips) {
            JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
            if (fx.optString("headUri", "").isEmpty()
                    || fx.optString("torsoUri", "").isEmpty()
                    || fx.optString("lowerUri", "").isEmpty()
                    || fx.optString("backgroundUri", "").isEmpty()) return false;
        }
        return true;
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
                .setFrameRate(clip.effects.optInt("_exportFps",30))
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
        int height = outputHeight(aspect, quality);
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
                Math.min(320_000L, Math.max(180_000L, durationUs / 12)),
                animationSpec,
                layerRole
        ));

        // Atmosphere is drawn only once on the topmost subject sequence.
        // Articulated renders use the head layer; older layered projects use
        // the single foreground layer.
        if (("head".equals(layerRole) || "foreground".equals(layerRole)) && animationSpec != null) {
            String environment = animationSpec.optString("environmentMotion", "ambient_drift");
            double atmosphere = animationSpec.optDouble("atmosphereIntensity", .42);
            effects.add(new OverlayEffect(Collections.singletonList(
                    new AtmosphereOverlay(environment, atmosphere, durationUs)
            )));
        }
        appendEditorGeometry(effects,clip,fx);
        if(fx.has("mask")&&!fx.optString("mask").equals("none"))effects.add(new EditorMaskEffect(fx.optString("mask"),(float)fx.optDouble("maskFeather",.03)));
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
        if (image) edited.setFrameRate(clip.effects != null && clip.effects.optJSONObject("nativeScene") != null
                ? clip.effects.optJSONObject("nativeScene").optInt("fps",30) : clip.effects.optInt("_exportFps",30));

        if (!image && Math.abs(clip.speed - 1f) > .01f) {
            final float speed = Math.max(.25f, Math.min(4f, clip.speed));
            edited.setSpeed(new SpeedProvider() {
                @Override public float getSpeed(long timeUs) { return speed; }
                @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
            });
        }

        List<AudioProcessor> audio = new ArrayList<>();
        if (asset.mime != null && (asset.mime.startsWith("video/") || asset.mime.startsWith("audio/"))) {
            androidx.media3.common.audio.ChannelMixingAudioProcessor volume = new androidx.media3.common.audio.ChannelMixingAudioProcessor();
            for(int channels=1;channels<=8;channels++) volume.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(channels,channels).scaleBy(clip.volume));
            audio.add(volume);
            androidx.media3.common.audio.SonicAudioProcessor resampler = new androidx.media3.common.audio.SonicAudioProcessor();
            resampler.setOutputSampleRateHz(48000);
            audio.add(resampler);
        }
        List<Effect> video = buildEffects(clip, aspect, quality, inputDurationMs);
        edited.setEffects(new Effects(audio, video));
        if (asset.mime == null || (!asset.mime.startsWith("video/") && !asset.mime.startsWith("audio/"))) edited.setRemoveAudio(true);
        if(asset.mime != null && asset.mime.startsWith("audio/")) edited.setRemoveVideo(true);
        return edited.build();
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip, String aspect, String quality, long inputDurationMs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
        if(fx.has("_exportFps"))effects.add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(fx.optInt("_exportFps",30)));

        float targetAspect = aspectRatio(aspect);
        effects.add(Presentation.createForAspectRatio(targetAspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = outputHeight(aspect, quality);
        effects.add(Presentation.createForHeight(height));

        JSONObject temporalFlow = fx.optJSONObject("temporalFlow");
        if (temporalFlow != null) {
            try { effects.add(new OverlayEffect(Collections.singletonList(new TemporalFlowOverlay(context, temporalFlow)))); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid temporal flow artifacts", error); }
        }

        JSONObject nativeScene = fx.optJSONObject("nativeScene");
        if (nativeScene != null) {
            try {
                effects.add(new OverlayEffect(Collections.singletonList(new NativeSceneOverlay(context, nativeScene))));
            } catch (Exception error) { throw new IllegalArgumentException("Invalid native VSL scene", error); }
        }

        JSONObject proceduralGraph = fx.optJSONObject("proceduralScene");
        if (proceduralGraph != null) {
            try {
                effects.add(new OverlayEffect(Collections.singletonList(new ProceduralSceneOverlay(
                        proceduralGraph, Math.max(100_000, clip.outputDurationMs() * 1000L)))));
            } catch (Exception error) { throw new IllegalArgumentException("Invalid procedural scene", error); }
        }

        JSONObject generatedFrames=fx.optJSONObject("generatedFrames");
        if(generatedFrames!=null)effects.add(new OverlayEffect(Collections.singletonList(new GeneratedFrameOverlay(new File(generatedFrames.optString("folder")),generatedFrames.optInt("count"),generatedFrames.optInt("fps")))));
        appendEditorGeometry(effects,clip,fx);
        String preset = fx.optString("effectPreset", fx.optString("colorPreset", ""));
        applyColourEffects(effects, fx);

        if(fx.has("mask")&&!fx.optString("mask").equals("none"))effects.add(new EditorMaskEffect(fx.optString("mask"),(float)fx.optDouble("maskFeather",.03)));
        if(fx.optBoolean("chromaKey"))effects.add(new EditorMaskEffect("green_screen",(float)fx.optDouble("chromaTolerance",.12)));
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

    private void appendEditorGeometry(List<Effect> effects,ProjectStore.Clip clip,JSONObject fx) {
        if(!clip.title.isEmpty())effects.add(new OverlayEffect(Collections.singletonList(new EditorTitleOverlay(clip.title))));
        org.json.JSONArray crop=fx.optJSONArray("cropBounds");
        if(crop!=null){float left=(float)crop.optDouble(0),top=(float)crop.optDouble(1),right=(float)crop.optDouble(2),bottom=(float)crop.optDouble(3);if(left<0||top<0||right>1||bottom>1||right<=left||bottom<=top)throw new IllegalArgumentException("Invalid crop bounds");effects.add(new androidx.media3.effect.Crop(left*2-1,right*2-1,1-bottom*2,1-top*2));}
        org.json.JSONArray keys=fx.optJSONArray("editorKeyframes");
        if(keys!=null)effects.add(new MotionMatrixEffect("none",Math.max(1,clip.outputDurationMs()*1000),0,keyframeSpec(keys),"flat"));
    }

    private static JSONObject keyframeSpec(org.json.JSONArray keys) {JSONObject spec=new JSONObject();try{spec.put("keyframes",keys);}catch(Exception ignored){}return spec;}

    private void startProgressPolling(Handle handle, Listener listener, AtomicBoolean finished) {
        ProgressHolder holder = new ProgressHolder();
        handle.progressTask = new Runnable() {
            @Override public void run() {
                if (handle.isCancelled() || finished.get()) return;
                try {
                    int state = handle.transformer.getProgress(holder);
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        listener.onProgress(holder.progress, "Native export " + holder.progress + "%");
                    }
                    if (!finished.get()) {
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
        if ("4:3".equals(aspect)) return 4f / 3f;
        if ("4:5".equals(aspect)) return 4f / 5f;
        return 9f / 16f;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

