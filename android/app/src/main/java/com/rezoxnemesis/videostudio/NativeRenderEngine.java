package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.AbsoluteSizeSpan;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.common.audio.DefaultGainProvider;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EncoderUtil;
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
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.common.collect.ImmutableList;

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
        return export(project,outputFile,aspect,quality,null,listener);
    }

    public Handle export(ProjectStore.Project project,File outputFile,String aspect,String quality,
                         Long maxOutputSampleDelayMs,Listener listener) {
        if (project == null || project.clips.isEmpty()) {
            listener.onError("Timeline is empty");
            return null;
        }
        try {
            final Long outputSampleDelayMs=NativeExportOptions.validate(maxOutputSampleDelayMs);
            if (outputFile.exists() && !outputFile.delete()) {
                listener.onError("Could not replace previous export");
                return null;
            }
            final boolean layeredAnimation = hasLayeredAnimation(project);
            final boolean hasStudioTracks = !project.studioLayers.isEmpty();
            final int[] requestedRaster = outputSize(aspect, quality);
            Composition composition = buildPreviewComposition(project, aspect, quality);

            Transformer.Builder transformerBuilder = new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                            // Media3's default selector discards software whenever any
                            // hardware codec exists, even if only software fits the raster.
                            .setVideoEncoderSelector(mime -> ImmutableList.sortedCopyOf(
                                    (a,b) -> Boolean.compare(EncoderUtil.isHardwareAccelerated(b,mime),
                                            EncoderUtil.isHardwareAccelerated(a,mime)),
                                    EncoderUtil.getSupportedEncoders(mime)))
                            .setEnableFormatFallback(false)
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            JSONObject info = new JSONObject();
                            try {
                                info.put("ok", true);
                                info.put("path", outputFile.getAbsolutePath());
                                info.put("sizeBytes", outputFile.length());
                                info.put("encoderReportedFrameCount",result.videoFrameCount);
                                info.put("encoderReportedDurationMs",result.durationMs);
                                info.put("encoderReportedWidth",result.width);
                                info.put("encoderReportedHeight",result.height);
                                info.put("encodedFrameCountVerified",false);
                                info.put("encodedClockVerified",false);
                                info.put("decodedFrameCountVerified",false);
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
                                info.put("nativeStudioTracks", hasStudioTracks);
                                info.put("studioTrackCount", project.studioLayers.size());
                                if(outputSampleDelayMs!=null)
                                    info.put(NativeExportOptions.FIELD,outputSampleDelayMs);
                                info.put("animationMode", layeredAnimation
                                        ? (hasArticulatedAnimation(project) ? "articulated-subject-2.5d" : "subject-aware-2.5d")
                                        : "standard");
                            } catch (Exception ignored) {}
                            
                            // A successful encoder callback is not sufficient. Verify displayed
                            // dimensions; these metadata checks cannot prove that content is upright.
                            android.media.MediaMetadataRetriever verifier =
                                    new android.media.MediaMetadataRetriever();
                            try {
                                verifier.setDataSource(outputFile.getAbsolutePath());
                                int actualWidth = Integer.parseInt(verifier.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                                int actualHeight = Integer.parseInt(verifier.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                                String rotation = verifier.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                                int rotationDegrees = rotation == null ? 0
                                        : Math.floorMod(Integer.parseInt(rotation), 360);
                                if (rotationDegrees == 90 || rotationDegrees == 270) {
                                    int temp = actualWidth; actualWidth = actualHeight; actualHeight = temp;
                                }
                                if (!outputGeometryMatches(aspect, quality, actualWidth, actualHeight)) {
                                    listener.onError("Export raster mismatch: requested " + requestedRaster[0] + "x" + requestedRaster[1]
                                            + " but encoded " + actualWidth + "x" + actualHeight);
                                    return;
                                }
                                info.put("encodedWidth", actualWidth);
                                info.put("encodedHeight", actualHeight);
                                info.put("displayDimensionsVerified", true);
                                info.put("orientationVerified", false);
                                info.put("physicalContentOrientationVerified", false);
                                info.put("sourceRasterVerified", sourceRaster(aspect) != null);
                            } catch (Exception verificationFailed) {
                                listener.onError("Could not verify encoded dimensions of exported video");
                                return;
                            } finally {
                                try { verifier.release(); }
                                catch (java.io.IOException ignored) { /* closing the metadata probe is best effort */ }
                            }
                            listener.onProgress(100, "Export complete");
                            listener.onCompleted(outputFile, info);
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException exception) {
                            if(exception.errorCode==ExportException.ERROR_CODE_MUXING_TIMEOUT){
                                long limit=outputSampleDelayMs==null
                                        ?Transformer.DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS:outputSampleDelayMs;
                                listener.onError("Native export stopped (7002): no output sample was written for "
                                        +java.math.BigDecimal.valueOf(limit,3).stripTrailingZeros().toPlainString()+" seconds.");
                                return;
                            }
                            if (exception.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
                                    && exception.codecInfo != null && exception.codecInfo.isVideo) {
                                listener.onError("NATIVE_EXPORT_RASTER_UNAVAILABLE: cannot encode exact "
                                        + requestedRaster[0] + "x" + requestedRaster[1] + "; " + exception.getMessage());
                                return;
                            }
                            listener.onError(exception.getMessage() == null ? "Native export failed" : exception.getMessage());
                        }
                    });
            if(outputSampleDelayMs!=null)
                transformerBuilder.setMaxDelayBetweenMuxerSamplesMs(outputSampleDelayMs);
            Transformer transformer=transformerBuilder.build();

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

    /** Builds the identical edited composition for interactive preview and native export. */
    public Composition buildPreviewComposition(ProjectStore.Project project, String aspect, String quality) throws Exception {
        if (project == null || project.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
        project = ProjectStore.Project.fromJson(project.toJson());
        final boolean layeredAnimation = hasLayeredAnimation(project);
        final boolean hasStudioTracks = !project.studioLayers.isEmpty();
        if(hasStudioTracks) StudioLayerContract.fromJson(
                project,StudioLayerContract.toJson(project.studioLayers));
        if(layeredAnimation)for(StudioLayer layer:project.studioLayers)
            if(StudioLayer.VIDEO.equals(layer.type))throw new IllegalArgumentException(
                    "VIDEO overlays are not yet supported with layered/articulated image animation");
        Composition composition;
        if(layeredAnimation && hasStudioTracks) {
            // Preserve the articulated foreground/background compositor,
            // then mix audio on independent audio-only sequences and
            // apply captions to the final composition at authored times.
            Composition animated=buildLayeredAnimationComposition(project,aspect,quality);
            ArrayList<EditedMediaItemSequence> sequences=
                    new ArrayList<>(animated.sequences);
            sequences.addAll(buildStudioAudioSequences(project));
            Composition.Builder assembled=new Composition.Builder(sequences);
            attachTimedTitles(assembled,project);
            composition=assembled.build();
        } else if (layeredAnimation) {
            composition = buildLayeredAnimationComposition(project, aspect, quality);
        } else if(hasStudioTracks) {
            composition = buildStudioTrackComposition(project,aspect,quality);
        } else {
            List<EditedMediaItem> edited = new ArrayList<>();
            boolean hasSourceAudio = false;
            long itemStartUs = 0;
            EditedMediaItem previousItem=null;
            for (ProjectStore.Clip clip : project.clips) {
                ProjectStore.Asset asset = project.asset(clip.assetId);
                if (asset == null) continue;
                boolean isImage = asset.mime != null && asset.mime.startsWith("image/");
                boolean isVideo = asset.mime != null && asset.mime.startsWith("video/");
                if (isVideo) hasSourceAudio = true;
                if(previousItem!=null)itemStartUs=Math.addExact(itemStartUs,StudioEffectClock.durationUs(previousItem));
                EditedMediaItem item=buildItem(asset,clip,aspect,quality,isImage,Collections.emptyList(),itemStartUs);
                edited.add(item);
                previousItem=item;
            }
            if (edited.isEmpty()) {
                throw new IllegalArgumentException("No renderable clips");
            }

            EditedMediaItemSequence sequence = hasSourceAudio
                    ? EditedMediaItemSequence.withAudioAndVideoFrom(edited)
                    : EditedMediaItemSequence.withVideoFrom(edited);
            composition = new Composition.Builder(sequence).build();
        }
        return composition;
    }

    /** Shared actual GPU effect list; CompositionPlayer also reuses buildItem's trim and clock. */
    public List<Effect> buildPreviewEffects(ProjectStore.Clip clip, String aspect, String quality) {
        return buildEffects(clip, aspect, quality, Math.max(1, clip.outMs - clip.inMs));
    }

    /**
     * Continuous video timeline with synchronized captions and real audio
     * tracks. Video clips are NOT cut at caption boundaries; the caption
     * overlay renders only at its actual requested presentation timestamp.
     */
    private Composition buildStudioTrackComposition(ProjectStore.Project project,
                                                     String aspect,String quality) {
        ArrayList<EditedMediaItem> clips=new ArrayList<>();
        boolean hasSourceAudio=false;
        long itemStartUs=0;
        EditedMediaItem previousItem=null;
        for(ProjectStore.Clip clip:project.clips){
            ProjectStore.Asset asset=project.asset(clip.assetId);
            if(asset==null||asset.mime==null)continue;
            boolean image=asset.mime.startsWith("image/");
            boolean video=asset.mime.startsWith("video/");
            if(!image&&!video)continue;
            if(video)hasSourceAudio=true;
            if(clip.outputDurationMs()<=0)continue;
            if(previousItem!=null)itemStartUs=Math.addExact(itemStartUs,StudioEffectClock.durationUs(previousItem));
            EditedMediaItem item=buildItem(asset,clip,aspect,quality,image,Collections.emptyList(),itemStartUs);
            clips.add(item);
            previousItem=item;
        }
        if(clips.isEmpty())throw new IllegalArgumentException("No playable base video clips");
        ArrayList<EditedMediaItemSequence> sequences=new ArrayList<>();
        sequences.add(hasSourceAudio
                ?EditedMediaItemSequence.withAudioAndVideoFrom(clips)
                :EditedMediaItemSequence.withVideoFrom(clips));
        ArrayList<StudioLayer> videos=new ArrayList<>();
        for(StudioLayer layer:project.studioLayers)if(StudioLayer.VIDEO.equals(layer.type))videos.add(layer);
        if(!videos.isEmpty()){
            // Match the actual base item clock, including submillisecond speed/rational
            // durations. The saved project duration deliberately remains unchanged.
            long baseUs=Math.addExact(itemStartUs,StudioEffectClock.durationUs(clips.get(clips.size()-1)));
            sequences.addAll(buildStudioVideoSequences(project,videos,baseUs));
        }
        sequences.addAll(buildStudioAudioSequences(project));
        Composition.Builder assembled=new Composition.Builder(sequences);
        if(!videos.isEmpty()){
            int[] canvas=outputSize(aspect,quality);
            assembled.setVideoCompositorSettings(new StudioVideoCompositorSettings(canvas[0],canvas[1],videos));
        }
        attachTimedTitles(assembled,project);
        return assembled.build();
    }

    /** Real nonlooping VIDEO inputs; no imageDuration, forced FPS or source audio. */
    private List<EditedMediaItemSequence> buildStudioVideoSequences(ProjectStore.Project project,
            List<StudioLayer> videos,long baseDurationUs){
        ArrayList<EditedMediaItemSequence> result=new ArrayList<>();
        for(StudioLayer layer:videos){
            StudioLayerContract.validate(project,layer);
            ProjectStore.Asset asset=project.asset(layer.assetId);
            long startUs=Math.multiplyExact(layer.startMs,1000L);
            long endUs=Math.addExact(startUs,Math.multiplyExact(layer.durationMs,1000L));
            if(endUs>baseDurationUs)throw new IllegalArgumentException("VIDEO overlay exceeds the native base duration");
            MediaItem media=new MediaItem.Builder().setUri(Uri.parse(asset.uri))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(layer.sourceInMs)
                            .setEndPositionMs(Math.addExact(layer.sourceInMs,layer.durationMs)).build()).build();
            EditedMediaItem item=new EditedMediaItem.Builder(media)
                    .setDurationUs(Math.multiplyExact(asset.durationMs,1000L)).setRemoveAudio(true).build();
            EditedMediaItemSequence.Builder sequence=new EditedMediaItemSequence.Builder(
                    java.util.Collections.singleton(C.TRACK_TYPE_VIDEO));
            if(startUs>0)sequence.addGap(startUs);
            sequence.addItem(item);
            if(endUs<baseDurationUs)sequence.addGap(baseDurationUs-endUs);
            result.add(sequence.build());
        }
        return result;
    }

    private void attachTimedTitles(Composition.Builder assembled,
                                          ProjectStore.Project project){
        StudioCompositionOverlayEffect overlays=new StudioCompositionOverlayEffect(context,project);
        ArrayList<Effect> finalStage=new ArrayList<>();
        if(!overlays.isEmpty())finalStage.add(overlays);
        // The camera is a composition-global matrix AFTER image/title/vector
        // overlay compositing, not an item-local zoom of a source screenshot.
        // This effect order is shared by interactive CompositionPlayer and
        // the native H.264 export builder. Audio is not transformed.
        for(StudioLayer layer:project.studioLayers)if(StudioLayer.CAMERA.equals(layer.type)){
            if(layer.startMs!=0||layer.durationMs!=project.outputDurationMs())
                throw new IllegalArgumentException("Composition camera must cover the original project clock");
            finalStage.add(StudioClipKeyframes.parse(layer.motionKeyframes,layer.durationMs));
        }
        if(!finalStage.isEmpty()){
            assembled.setEffects(new Effects(Collections.emptyList(),finalStage));
        }
    }

    /** Each imported audio lane is an independent, actually mixed Media3 sequence. */
    private List<EditedMediaItemSequence> buildStudioAudioSequences(
            ProjectStore.Project project) {
        ArrayList<EditedMediaItemSequence> sequences=new ArrayList<>();
        List<StudioLayer> music=StudioTrackLayout.audioLayers(project);
        long baseDuration=project.outputDurationMs();
        for(StudioLayer lane:music){
            ProjectStore.Asset asset=project.asset(lane.assetId);
            long end=Math.min(asset.durationMs>0?asset.durationMs:
                    lane.sourceInMs+lane.durationMs,lane.sourceInMs+lane.durationMs);
            MediaItem media=new MediaItem.Builder()
                    .setUri(Uri.parse(asset.uri))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(lane.sourceInMs)
                            .setEndPositionMs(end)
                            .build())
                    .build();
            EditedMediaItem.Builder track=new EditedMediaItem.Builder(media)
                    .setRemoveVideo(true);
            // Media3 applies source clipping after durationUs, including sourceInMs.
            if(asset.durationMs>0)track.setDurationUs(Math.multiplyExact(asset.durationMs,1000L));
            if(lane.gain<.999f){
                List<AudioProcessor> processors=Collections.singletonList(
                        new GainProcessor(new DefaultGainProvider.Builder(lane.gain).build()));
                track.setEffects(new Effects(processors,Collections.emptyList()));
            }
            EditedMediaItemSequence.Builder audio=new EditedMediaItemSequence.Builder(
                    Collections.singleton(C.TRACK_TYPE_AUDIO));
            if(lane.startMs>0)audio.addGap(lane.startMs*1000L);
            audio.addItem(track.build());
            long tail=baseDuration-lane.startMs-lane.durationMs;
            if(tail>0)audio.addGap(tail*1000L);
            sequences.add(audio.build());
        }
        return sequences;
    }

    private static List<Effect> titleEffects(List<StudioLayer> titles){
        if(titles==null||titles.isEmpty())return Collections.emptyList();
        ArrayList<TextureOverlay> overlays=new ArrayList<>();
        int count=0;
        for(StudioLayer title:titles){
            if(count++>=5)break;
            SpannableString line=new SpannableString(title.text);
            line.setSpan(new ForegroundColorSpan(title.color),0,line.length(),0);
            line.setSpan(new StyleSpan(Typeface.BOLD),0,line.length(),0);
            line.setSpan(new AbsoluteSizeSpan(32,true),0,line.length(),0);
            float y=title.position==0?.72f:(title.position==2?-.71f:0f);
            StaticOverlaySettings settings=new StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f,y)
                    .setOverlayFrameAnchor(0f,0f)
                    .setAlphaScale(1f)
                    .build();
            overlays.add(TextOverlay.createStaticTextOverlay(line,settings));
        }
        return Collections.singletonList(new OverlayEffect(overlays));
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
            long itemStartUs=0;
            EditedMediaItem previousItem=null;

            for (ProjectStore.Clip clip : project.clips) {
                JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
                JSONObject spec = fx.optJSONObject("animationSpec");
                long durationMs = Math.max(700, clip.outputDurationMs());

                if(previousItem!=null)itemStartUs=Math.addExact(itemStartUs,StudioEffectClock.durationUs(previousItem));
                EditedMediaItem headItem=buildLayerItem(fx.optString("headUri"),clip,aspect,quality,durationMs,spec,"head",itemStartUs);
                head.add(headItem);
                torso.add(buildLayerItem(fx.optString("torsoUri"),clip,aspect,quality,durationMs,spec,"torso",itemStartUs));
                lower.add(buildLayerItem(fx.optString("lowerUri"),clip,aspect,quality,durationMs,spec,"lower",itemStartUs));
                background.add(buildLayerItem(fx.optString("backgroundUri"),clip,aspect,quality,durationMs,spec,"background",itemStartUs));
                previousItem=headItem;
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
        long itemStartUs=0;
        EditedMediaItem previousItem=null;
        for (ProjectStore.Clip clip : project.clips) {
            JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
            JSONObject spec = fx.optJSONObject("animationSpec");
            long durationMs = Math.max(700, clip.outputDurationMs());
            if(previousItem!=null)itemStartUs=Math.addExact(itemStartUs,StudioEffectClock.durationUs(previousItem));
            EditedMediaItem foregroundItem=buildLayerItem(fx.optString("foregroundUri"),clip,aspect,quality,durationMs,spec,"foreground",itemStartUs);
            foreground.add(foregroundItem);
            background.add(buildLayerItem(fx.optString("backgroundUri"),clip,aspect,quality,durationMs,spec,"background",itemStartUs));
            previousItem=foregroundItem;
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
        return buildLayerItem(uri,clip,aspect,quality,durationMs,animationSpec,layerRole,0);
    }

    private EditedMediaItem buildLayerItem(String uri,ProjectStore.Clip clip,String aspect,String quality,
                                           long durationMs,JSONObject animationSpec,String layerRole,long itemStartUs) {
        MediaItem media = new MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setImageDurationMs(durationMs)
                .build();

        EditedMediaItem.Builder item = new EditedMediaItem.Builder(media)
                .setFrameRate(30)
                .setRemoveAudio(true);

        List<Effect> video = buildLayerEffects(clip,aspect,quality,durationMs,animationSpec,layerRole,itemStartUs);
        item.setEffects(new Effects(Collections.emptyList(), video));
        return item.build();
    }

    private List<Effect> buildLayerEffects(ProjectStore.Clip clip,
                                           String aspect,
                                           String quality,
                                           long durationMs,
                                           JSONObject animationSpec,
                                           String layerRole) {
        return buildLayerEffects(clip,aspect,quality,durationMs,animationSpec,layerRole,0);
    }

    private List<Effect> buildLayerEffects(ProjectStore.Clip clip,String aspect,String quality,long durationMs,
                                          JSONObject animationSpec,String layerRole,long itemStartUs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        int[] raster=outputSize(aspect,quality);
        if(fx.has("crop"))effects.add(new StudioCropEffect(fx.optJSONObject("crop")));
        int width=raster[0],height=raster[1];
        effects.add(Presentation.createForWidthAndHeight(
                width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));

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
        ).withPresentationStartUs(itemStartUs));

        // Atmosphere is drawn only once on the topmost subject sequence.
        // Articulated renders use the head layer; older layered projects use
        // the single foreground layer.
        if (("head".equals(layerRole) || "foreground".equals(layerRole)) && animationSpec != null) {
            String environment = animationSpec.optString("environmentMotion", "ambient_drift");
            double atmosphere = animationSpec.optDouble("atmosphereIntensity", .42);
            effects.add(new OverlayEffect(Collections.singletonList(
                    new AtmosphereOverlay(environment,atmosphere,durationUs,itemStartUs)
            )));
        }
        StudioPixelEffect pixels=new StudioPixelEffect(fx,Math.max(1,clip.outputDurationMs()),itemStartUs);
        if(pixels.hasPixelChanges())effects.add(pixels);
        return effects;
    }

    private void applyColourEffects(List<Effect> effects, JSONObject fx) {
        double brightness = fx.optDouble("brightness", 0);
        double contrast = fx.optDouble("contrast", 0);
        double saturation = fx.optDouble("saturationAdjust", fx.optDouble("saturation", 0));
        double lightness = fx.optDouble("lightnessAdjust", 0);
        String preset = fx.has("colorPreset")?fx.optString("colorPreset",""):fx.optString("effectPreset","");
        if (StudioPrecisionEdits.COLORS.contains(preset) && !"none".equals(preset)) {
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

    private EditedMediaItem buildItem(ProjectStore.Asset asset, ProjectStore.Clip clip,
                                      String aspect,String quality,boolean image) {
        return buildItem(asset,clip,aspect,quality,image,Collections.emptyList());
    }

    private EditedMediaItem buildItem(ProjectStore.Asset asset, ProjectStore.Clip clip,
                                      String aspect,String quality,boolean image,
                                      List<StudioLayer> titles) {
        return buildItem(asset,clip,aspect,quality,image,titles,0);
    }

    private EditedMediaItem buildItem(ProjectStore.Asset asset,ProjectStore.Clip clip,String aspect,String quality,
                                      boolean image,List<StudioLayer> titles,long itemStartUs) {
        long inputDurationMs = Math.max(100, clip.outMs - clip.inMs);
        MediaItem.Builder media = new MediaItem.Builder().setUri(Uri.parse(asset.uri));

        if (image) {
            // Retain the imported format when an owned image has an opaque filename.
            // Export's image admission cannot rely on preview's header sniffing.
            if(androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType(asset.mime))
                media.setMimeType(asset.mime);
            // Pose-interpolated frames are already real video samples; the usual
            // 250ms photo minimum would destroy 24fps action timing.
            boolean poseFrame = clip.effects != null && (clip.effects.optBoolean("poseInbetween", false)
                    || clip.effects.optBoolean("directorSequenceFrame", false));
            if(poseFrame)poseFrameRate(clip);
            media.setImageDurationMs(Math.max(poseFrame ? 1 : 250, clip.outputDurationMs()));
        } else {
            media.setClippingConfiguration(
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(Math.max(0, clip.inMs))
                            .setEndPositionMs(Math.max(clip.inMs + 100, clip.outMs))
                            .build());
        }

        EditedMediaItem.Builder edited = new EditedMediaItem.Builder(media.build());
        // CompositionPlayer needs the full source clock before applying trim/speed.
        // Unknown metadata stays unknown; the interactive player reports it explicitly.
        if(!image&&asset.durationMs>0)
            edited.setDurationUs(Math.multiplyExact(asset.durationMs,1000L));
        if (image) edited.setFrameRate(clip.effects != null && (clip.effects.optBoolean("poseInbetween", false)
                    || clip.effects.optBoolean("directorSequenceFrame", false))
                ? poseFrameRate(clip) : 30);
        if(image)applyAuthoredFrameClock(edited,asset,clip);

        if (!image && Math.abs(clip.speed - 1f) > .01f) {
            final float speed = Math.max(.25f, Math.min(4f, clip.speed));
            edited.setSpeed(new SpeedProvider() {
                @Override public float getSpeed(long timeUs) { return speed; }
                @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
            });
        }

        List<AudioProcessor> audio=Collections.emptyList();
        if(asset.mime!=null&&asset.mime.startsWith("video/")){
            StudioClipAudioEnvelope envelope=StudioClipAudioEnvelope.fromEffects(
                    clip.effects,clip.outputDurationMs(),clip.speed,clip.volume);
            if(envelope.changesAudio())audio=Collections.singletonList(new StudioClipAudioProcessor(envelope));
        }
        List<Effect> video = buildEffects(clip,aspect,quality,inputDurationMs,itemStartUs);
        video.addAll(titleEffects(titles));
        edited.setEffects(new Effects(audio, video));
        if (asset.mime == null || !asset.mime.startsWith("video/")) edited.setRemoveAudio(true);
        return edited.build();
    }

    private static int poseFrameRate(ProjectStore.Clip clip) {
        int fps=clip.effects.optInt("poseFps",24);
        // Existing pose-sequence tools support 12..30. The cinematic request
        // path applies its own stricter 24/30/60 contract before composition.
        if(fps<12 || fps>60)throw new IllegalArgumentException("Pose frame FPS must be in 12..60");
        return fps;
    }

    private static void applyAuthoredFrameClock(EditedMediaItem.Builder item,
                                              ProjectStore.Asset asset,ProjectStore.Clip clip) {
        if(!asset.generated || clip.effects==null || asset.generationMetadata==null ||
                !("multigenre_storyboard_motion".equals(asset.role) || "storyboard_artwork_frame".equals(asset.role)
                        || "generated_skinned_2d".equals(asset.role) || "generated_cinematic_3d".equals(asset.role)
                        || "generated_sketch_frame".equals(asset.role)) ||
                !"rational-authored-v1".equals(clip.effects.optString("frameClock")) ||
                !"rational-authored-v1".equals(asset.generationMetadata.optString("frameClock")))return;
        Object index=clip.effects.opt("frameIndex"), original=asset.generationMetadata.opt("frameIndex");
        if(!(index instanceof Number) || !(original instanceof Number) ||
                !Double.isFinite(((Number)index).doubleValue()) || ((Number)index).doubleValue()<0 ||
                ((Number)index).doubleValue()>2_000_000 || ((Number)index).doubleValue()!=((Number)index).longValue() ||
                ((Number)index).doubleValue()!=((Number)original).doubleValue())
            throw new IllegalArgumentException("Generated frame clock identity differs from its asset");
        int fps=NativeFrameClock.requireFps(poseFrameRate(clip));
        if(asset.generationMetadata.optInt("fps",0)!=fps)
            throw new IllegalArgumentException("Generated frame FPS differs from its asset");
        long frame=((Number)index).longValue();
        // A person may extend, trim or retime a generated image. Such clips
        // keep their edited duration instead of inheriting a one-frame clock.
        if(clip.inMs==0 && clip.speed==1f && clip.outMs==NativeFrameClock.frameDurationMs(frame,fps))
            item.setDurationUs(NativeFrameClock.frameDurationUs(frame,fps));
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip, String aspect, String quality, long inputDurationMs) {
        return buildEffects(clip,aspect,quality,inputDurationMs,0);
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip,String aspect,String quality,long inputDurationMs,long itemStartUs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        if(fx.has("crop"))effects.add(new StudioCropEffect(fx.optJSONObject("crop")));
        // Enforce correct raster geometry instead of a 9:16 scene letterboxed
        // in a 16:9 output. Encoding must preserve this computed raster.
        int[] raster=outputSize(aspect,quality);
        int width=raster[0],height=raster[1];
        effects.add(Presentation.createForWidthAndHeight(
                width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));

        JSONObject proceduralGraph = fx.optJSONObject("proceduralScene");
        if (proceduralGraph != null) {
            try {
                effects.add(new OverlayEffect(Collections.singletonList(new ProceduralSceneOverlay(
                        proceduralGraph,Math.max(100_000,clip.outputDurationMs()*1000L),itemStartUs))));
            } catch (Exception error) { throw new IllegalArgumentException("Invalid procedural scene", error); }
        }

        String preset = fx.optString("effectPreset", fx.optString("colorPreset", ""));
        applyColourEffects(effects, fx);

        double blur = fx.optDouble("blur", 0);
        if ("gaussian_blur".equals(preset)) blur = Math.max(blur, 5);
        if ("soft_glow".equals(preset) || "dream".equals(preset)) blur = Math.max(blur, 1.6);
        if (blur > .1) effects.add(new GaussianBlur((float) Math.min(18, blur)));

        effects.add(StudioClipKeyframes.fromEffects(fx,Math.max(1,clip.outputDurationMs())).withPresentationStartUs(itemStartUs));
        if (clip.title != null && !clip.title.trim().isEmpty()) {
            StudioLayer title = new StudioLayer();
            title.text = clip.title;
            title.durationMs = Math.max(100, clip.outputDurationMs());
            title.position = 2;
            effects.addAll(titleEffects(Collections.singletonList(title)));
        }

        String motion = fx.optString("motionPreset", "none");
        String transition = clip.transition == null ? "none" : clip.transition;
        boolean exactKeys = fx.optJSONArray("keyframes") != null && fx.optJSONArray("keyframes").length() > 0;
        if (!exactKeys && (!"none".equals(motion) || (!"none".equals(transition) && !"cut".equals(transition)))) {
            String matrixPreset = "none".equals(motion) ? transition : motion;
            effects.add(new MotionMatrixEffect(matrixPreset,Math.max(100_000,clip.outputDurationMs()*1000L),280_000).withPresentationStartUs(itemStartUs));
        }

        StudioPixelEffect pixels=new StudioPixelEffect(fx,Math.max(1,clip.outputDurationMs()),itemStartUs);
        if(pixels.hasPixelChanges())effects.add(pixels);

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
        int[] exact=sourceRaster(aspect);
        if(exact!=null)return exact[0]/(float)exact[1];
        if ("16:9".equals(aspect)) return 16f / 9f;
        if ("1:1".equals(aspect)) return 1f;
        if ("4:5".equals(aspect)) return 4f / 5f;
        return 9f / 16f;
    }

    private static int[] sourceRaster(String aspect) {
        if(aspect==null || !aspect.startsWith("source:"))return null;
        java.util.regex.Matcher dimensions=java.util.regex.Pattern.compile("source:([0-9]{3,4})x([0-9]{3,4})").matcher(aspect);
        if(!dimensions.matches())throw new IllegalArgumentException("Exact storyboard raster is invalid");
        int width=Integer.parseInt(dimensions.group(1)),height=Integer.parseInt(dimensions.group(2));
        if(width<256 || height<256 || width>1280 || height>1280 || (width&1)!=0 || (height&1)!=0 ||
                (long)width*height>720L*1280L)
            throw new IllegalArgumentException("Exact storyboard raster exceeds its bounded frame geometry");
        return new int[]{width,height};
    }
    private static int[] outputSize(String aspect,String quality) {
        int[] exact=sourceRaster(aspect);if(exact!=null)return exact;
        int height="720p".equalsIgnoreCase(quality)?("16:9".equals(aspect)?720:1280):("16:9".equals(aspect)?1080:1920);
        return new int[]{Math.round(height*aspectRatio(aspect)),height};
    }

    static boolean outputGeometryMatches(String aspect, int width, int height) {
        return outputGeometryMatches(aspect, "720p", width, height);
    }

    static boolean outputGeometryMatches(String aspect, String quality, int width, int height) {
        if (width <= 0 || height <= 0) return false;
        int[] exact = outputSize(aspect, quality);
        return width == exact[0] && height == exact[1];
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

