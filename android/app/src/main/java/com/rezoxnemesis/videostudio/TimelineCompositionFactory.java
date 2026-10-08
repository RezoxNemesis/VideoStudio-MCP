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
public final class TimelineCompositionFactory {
    private final Context context;
    private int frameRate = 30;
    public TimelineCompositionFactory(Context context) { this.context = context.getApplicationContext(); }

    public Composition build(ProjectStore.Project original, String aspect, String quality, boolean useProxies) {
        if (original == null || original.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
        ProjectStore.Project project = ProjectStore.Project.fromJson(original.toJson());
        for(ProjectStore.Clip c:project.clips)if(project.track(c.trackId)==null)throw new IllegalArgumentException("Clip refers to a missing track: "+c.id);
        frameRate = project.settings.optInt("fps", 30);
        if (useProxies) for (ProjectStore.Asset a : project.assets) a.uri = ProxyManager.previewUri(original, a);
        boolean solo = false;
        for (ProjectStore.Track t : project.tracks) solo |= t.solo;
        ArrayList<ProjectStore.Track> tracks = new ArrayList<>(project.tracks);
        tracks.sort((a, b) -> Integer.compare(b.order, a.order));
        ArrayList<EditedMediaItemSequence> sequences = new ArrayList<>();
        long durationMs = project.outputDurationMs();
        for (ProjectStore.Track track : tracks) {
            if (solo && !track.solo) continue;
            ArrayList<ProjectStore.Clip> clips = new ArrayList<>();
            boolean audio = false, video = false, animated = false;
            for (ProjectStore.Clip c : project.clips) if (track.id.equals(c.trackId)) {
                ProjectStore.Asset a = project.asset(c.assetId);
                if (a == null) throw new IllegalArgumentException("Missing source for clip " + c.id);
                if (a.mime == null) throw new IllegalArgumentException("Unknown source format");
                clips.add(c);
                audio |= !track.muted && (a.mime.startsWith("video/") || a.mime.startsWith("audio/"));
                video |= !track.audioOnly() && track.visible && !a.mime.startsWith("audio/");
                animated |= c.effects.optBoolean("animatedScene", false);
            }
            if (clips.isEmpty() || (!audio && !video)) continue;
            clips.sort(java.util.Comparator.comparingLong(c -> c.startMs));
            java.util.HashSet<Integer> types = new java.util.HashSet<>();
            if (audio) types.add(C.TRACK_TYPE_AUDIO);
            if (video) types.add(C.TRACK_TYPE_VIDEO);
            if (animated && video) {
                for (String role : new String[]{"head", "torso", "lower", "foreground"}) {
                    EditedMediaItemSequence.Builder layer = new EditedMediaItemSequence.Builder(java.util.Collections.singleton(C.TRACK_TYPE_VIDEO));
                    long cursor = 0; boolean used = false;
                    for (ProjectStore.Clip c : clips) {
                        if (c.startMs > cursor) layer.addGap(Math.multiplyExact(c.startMs - cursor, 1000L));
                        boolean articulated = !c.effects.optString("headUri").isEmpty();
                        String uri = "foreground".equals(role) && articulated ? "" : c.effects.optString(role + "Uri", "");
                        long length = c.outputDurationMs();
                        if (!uri.isEmpty()) {
                            layer.addItem(buildLayerItem(uri, c, aspect, quality, length, c.effects.optJSONObject("animationSpec"), role)); used = true;
                        } else layer.addGap(Math.multiplyExact(length, 1000L));
                        cursor = TimelineMath.add(c.startMs, length);
                    }
                    if (cursor < durationMs) layer.addGap(Math.multiplyExact(durationMs - cursor, 1000L));
                    if (used) sequences.add(layer.build());
                }
            }
            EditedMediaItemSequence.Builder sequence = new EditedMediaItemSequence.Builder(types);
            long cursor = 0;
            for (ProjectStore.Clip c : clips) {
                if (c.startMs < cursor) throw new IllegalArgumentException("Clips overlap on track " + track.name);
                if (c.startMs > cursor) sequence.addGap(Math.multiplyExact(c.startMs - cursor, 1000L));
                ProjectStore.Asset a = project.asset(c.assetId);
                String background = c.effects.optString("backgroundUri", "");
                if (video && c.effects.optBoolean("animatedScene", false) && !background.isEmpty())
                    sequence.addItem(buildLayerItem(background, c, aspect, quality, c.outputDurationMs(), c.effects.optJSONObject("animationSpec"), "background"));
                else sequence.addItem(buildItem(a, c, aspect, quality, a.mime.startsWith("image/"), !video, !audio));
                cursor = TimelineMath.add(c.startMs, c.outputDurationMs());
            }
            if (cursor < durationMs) sequence.addGap(Math.multiplyExact(durationMs - cursor, 1000L));
            sequences.add(sequence.build());
        }
        if (sequences.isEmpty()) throw new IllegalArgumentException("No visible or audible tracks");
        return new Composition.Builder(sequences).build();
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
                .setFrameRate(frameRate)
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
        if ("540p".equalsIgnoreCase(quality)) height = 540;
        if ("360p".equalsIgnoreCase(quality)) height = 360;
        effects.add(Presentation.createForHeight(height));

        // Keep a safety overscan so parallax never reveals the edge of a plate.
        float overscan = "background".equals(layerRole) ? 1.10f : 1.035f;
        effects.add(new ScaleAndRotateTransformation.Builder()
                .setScale(overscan, overscan)
                .build());

        applyColourEffects(effects, fx, clip);

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
        effects.add(new ClipTransformEffect(clip));
        effects.add(new ClipOpacityEffect(clip));
        return effects;
    }

    private void applyColourEffects(List<Effect> effects, JSONObject fx, ProjectStore.Clip clip) {
        boolean animated=false;
        for(int i=0;i<clip.keyframes.length();i++){
            JSONObject frame=clip.keyframes.optJSONObject(i);if(frame==null)continue;
            String property=frame.optString("property");
            animated |= "brightness".equals(property)||"contrast".equals(property)||"saturationAdjust".equals(property)||"lightnessAdjust".equals(property);
        }
        if(animated){effects.add(new ClipColourEffect(clip));return;}

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

    private EditedMediaItem buildItem(ProjectStore.Asset asset, ProjectStore.Clip clip, String aspect, String quality, boolean image, boolean audioOnly, boolean removeAudio) {
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
        if (image) edited.setFrameRate(frameRate);

        if (!image && Math.abs(clip.speed - 1f) > .01f) {
            final float speed = Math.max(.25f, Math.min(4f, clip.speed));
            edited.setSpeed(new SpeedProvider() {
                @Override public float getSpeed(long timeUs) { return speed; }
                @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
            });
        }

        ArrayList<AudioProcessor> audio = new ArrayList<>();
        if (!removeAudio && !image) audio.add(new ClipAudioProcessor(clip));
        List<Effect> video = audioOnly ? Collections.emptyList() : buildEffects(clip, aspect, quality, inputDurationMs);
        edited.setEffects(new Effects(audio, video));
        edited.setRemoveVideo(audioOnly);
        if (removeAudio || image) edited.setRemoveAudio(true);
        return edited.build();
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip, String aspect, String quality, long inputDurationMs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        float targetAspect = aspectRatio(aspect);
        effects.add(Presentation.createForAspectRatio(targetAspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = "720p".equalsIgnoreCase(quality) ? ("16:9".equals(aspect) ? 720 : 1280) : ("16:9".equals(aspect) ? 1080 : 1920);
        if ("540p".equalsIgnoreCase(quality)) height = 540;
        if ("360p".equalsIgnoreCase(quality)) height = 360;
        effects.add(Presentation.createForHeight(height));

        JSONObject proceduralGraph = fx.optJSONObject("proceduralScene");
        if (proceduralGraph != null) {
            try {
                effects.add(new OverlayEffect(Collections.singletonList(new ProceduralSceneOverlay(
                        proceduralGraph, Math.max(100_000, clip.outputDurationMs() * 1000L)))));
            } catch (Exception error) { throw new IllegalArgumentException("Invalid procedural scene", error); }
        }

        String preset = fx.optString("effectPreset", fx.optString("colorPreset", ""));
        applyColourEffects(effects, fx, clip);

        double blur = fx.optDouble("blur", 0);
        if ("gaussian_blur".equals(preset)) blur = Math.max(blur, 5);
        if ("soft_glow".equals(preset) || "dream".equals(preset)) blur = Math.max(blur, 1.6);
        if (blur > .1) effects.add(new GaussianBlur((float) Math.min(18, blur)));

        effects.add(new ClipTransformEffect(clip));
        effects.add(new ClipOpacityEffect(clip));
        float cropLeft=(float)fx.optDouble("cropLeft"), cropRight=(float)fx.optDouble("cropRight");
        float cropTop=(float)fx.optDouble("cropTop"), cropBottom=(float)fx.optDouble("cropBottom");
        if(cropLeft+cropRight+cropTop+cropBottom>0)
            effects.add(new androidx.media3.effect.Crop(-1+2*cropLeft,1-2*cropRight,-1+2*cropBottom,1-2*cropTop));
        if(!clip.title.isEmpty()) {
            android.text.SpannableString text=new android.text.SpannableString(clip.title);
            text.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),0,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.RelativeSizeSpan(.42f),0,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            effects.add(new OverlayEffect(Collections.singletonList(androidx.media3.effect.TextOverlay.createStaticTextOverlay(text))));
        }

        String motion = fx.optString("motionPreset", "none");
        String transition = clip.transition == null ? "none" : clip.transition;
        if (!"none".equals(motion) || (!"none".equals(transition) && !"cut".equals(transition))) {
            String matrixPreset = "none".equals(motion) ? transition : motion;
            effects.add(new MotionMatrixEffect(matrixPreset, Math.max(100_000, clip.outputDurationMs() * 1000L), 280_000));
        }

        return effects;
    }

    private static float aspectRatio(String aspect) {
        if ("16:9".equals(aspect)) return 16f/9f;
        if ("1:1".equals(aspect)) return 1f;
        if ("4:5".equals(aspect)) return 4f/5f;
        return 9f/16f;
    }
    private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
}
