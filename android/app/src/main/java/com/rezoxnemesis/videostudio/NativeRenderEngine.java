package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.SpeedParameters;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.DefaultMuxer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.EncoderSelector;
import androidx.media3.transformer.EncoderUtil;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic SDR Media3 export of the persisted track graph. */
@UnstableApi
public final class NativeRenderEngine {
    // Each video input owns GPU frame pools. Keep the articulated four-layer
    // baseline while bounding decoder/GPU use instead of opening every track.
    public static final int MAX_VIDEO_LAYERS = 4;

    public interface Listener {
        void onProgress(int progress, String detail);
        void onCompleted(File file, JSONObject result);
        void onError(String message);
    }

    public static final class Handle {
        private final Handler main;
        private final File output;
        private final Listener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private Transformer transformer;
        private Runnable progressTask;
        private boolean softwareRetry;
        private int attempt;
        private String firstFailure = "";

        Handle(Handler main, File output, Listener listener) {
            this.main=main; this.output=output; this.listener=listener;
        }

        public void cancel() {
            cancelled.set(true);
            main.post(() -> {
                if (terminal.get()) return;
                if (transformer!=null) try { transformer.cancel(); } catch (Exception ignored) {}
                fail("Export cancelled");
            });
        }

        public boolean isCancelled() { return cancelled.get(); }
        private void stopPolling() { if(progressTask!=null)main.removeCallbacks(progressTask); }
        private void fail(String message) {
            if(!terminal.compareAndSet(false,true))return;
            stopPolling();
            if(output.exists()) output.delete();
            listener.onError(message);
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NativeRenderEngine(Context context) { this.context=context.getApplicationContext(); }

    public static int frameRate(ProjectStore.Project project) { return NativeAnimationCadence.frameRate(project); }
    public static JSONObject cadenceProfile(ProjectStore.Project project) { return NativeAnimationCadence.profile(project); }

    /** Preview receives source timestamps; final item effects receive program timestamps. */
    public static List<Effect> previewEffects(ProjectStore.Clip clip,String aspect,String quality) {
        return NativeVideoEffects.build(clip,aspect,quality,Math.max(0,clip.inMs)*1000L,clip.effectiveSpeed(),"flat");
    }

    /** Source-only capability report, also used by UI/MCP before a render is accepted. */
    public static JSONObject diagnostics(ProjectStore.Project project) {
        JSONObject report=new JSONObject();
        JSONArray unsupported=new JSONArray();
        JSONArray warnings=new JSONArray();
        int layers=0,visualTracks=0,audioTracks=0,audibleClips=0;
        try {
            if(project==null||project.clips.isEmpty()) unsupported.put("Timeline is empty");
            else {
                ProjectStore.Project snapshot=ProjectStore.Project.fromJson(project.toJson());
                ProjectTimeline.validate(snapshot);
                int cadence=frameRate(snapshot);
                report.put("animationFrameRate",cadence);report.put("cadence",cadenceProfile(snapshot));
                if(snapshot.outputDurationMs()>Long.MAX_VALUE/1000L)unsupported.put("Timeline exceeds the native microsecond clock range");
                for(ProjectStore.Clip clip:snapshot.clips)if(clip.outMs>Long.MAX_VALUE/1000L)unsupported.put(clip.id+": source time exceeds the native microsecond clock range");
                for(ProjectStore.Track track:snapshot.renderTracks()) {
                    if(!snapshot.isTrackEnabled(track)||snapshot.clipsOnTrack(track.id).isEmpty())continue;
                    if(snapshot.isTrackAudible(track))for(ProjectStore.Clip clip:snapshot.clipsOnTrack(track.id)) {
                        ProjectStore.Asset asset=snapshot.asset(clip.assetId);
                        if(asset!=null&&asset.hasAudio&&!audioDetached(clip))audibleClips++;
                    }
                    if(track.isAudio()) {
                        if(!snapshot.isTrackAudible(track))continue;
                        audioTracks++;
                        for(ProjectStore.Clip clip:snapshot.clipsOnTrack(track.id))
                            for(String error:NativeVideoEffects.unsupportedAudio(clip))unsupported.put(clip.id+": "+error);
                        continue;
                    }
                    visualTracks++;
                    layers+=roles(snapshot.clipsOnTrack(track.id)).size();
                    for(ProjectStore.Clip clip:snapshot.clipsOnTrack(track.id)) {
                        for(String error:NativeVideoEffects.unsupported(clip)) unsupported.put(clip.id+": "+error);
                        JSONObject spec=clip.effects==null?null:clip.effects.optJSONObject("animationSpec");
                        if(clip.effects!=null&&clip.effects.has("celExposure")) {
                            if(clip.effects.optJSONObject("celExposure")==null)unsupported.put(clip.id+": celExposure must be an authored exposure object");
                            else if(!AnimationCelEdits.matchesFrameGrid(clip))warnings.put(clip.id+": cel timing was edited outside its authored frame grid; export samples its millisecond timeline timing");
                            else if(AnimationCelEdits.exposureFrameRate(clip)!=cadence)warnings.put(clip.id+": authored exposure frame count is resampled to the explicitly chosen "+cadence+" fps output cadence");
                            else if(clip.effects.getJSONObject("celExposure").optLong("originMs",0)!=0L)
                                warnings.put(clip.id+": exposure origin shifts its authored grid relative to the output frame clock; frame boundaries are sampled at the chosen output cadence");
                        }
                        if(spec!=null)for(String hint:java.util.Arrays.asList("motionBlur","focusPulse"))
                            if(spec.optDouble(hint,0)>0)warnings.put(clip.id+": animationSpec."+hint+" is an unused authoring hint; no optical effect is applied");
                        if(isLayered(clip))for(String role:roles(Collections.singletonList(clip)))
                            if(clip.effects.optString(role+"Uri", "").isEmpty())unsupported.put(clip.id+": generated "+role+" portrait layer is missing");
                    }
                }
                if(layers>MAX_VIDEO_LAYERS)unsupported.put("Native compositor supports "+MAX_VIDEO_LAYERS+" video layers; enabled tracks require "+layers);
                if(visualTracks==0&&audioTracks==0)unsupported.put("No enabled media tracks");
                renderTimings(snapshot,cadence,Long.MAX_VALUE);
            }
            report.put("supported",unsupported.length()==0);
            report.put("unsupported",unsupported);
            report.put("warnings",warnings);
            report.put("videoLayers",layers);report.put("videoLayerLimit",MAX_VIDEO_LAYERS);
            report.put("visualTracks",visualTracks);report.put("audioTracks",audioTracks);report.put("audibleClips",audibleClips);
            report.put("colorPipeline","SDR BT.709; HDR inputs tone-mapped by OpenGL");
            report.put("motionMode","deterministic procedural transforms and existing image layers");
            report.put("transitions","clip-edge alpha fade, color dip and geometric motion; overlapping tracks composite source-over");
            report.put("audioMix","pitch-preserving speed, output-clock gain automation, optional EQ/pan/gate/de-esser/compression/algorithmic room/delay/ceiling, timeline silence and additive track mix; effect tails stay inside each clip with a 16MiB PCM state budget");
            report.put("codecRecovery","one software-video retry if a device software AVC encoder is available");
        } catch(Exception error) {
            try{report.put("supported",false);report.put("unsupported",new JSONArray().put(message(error)));}catch(Exception ignored){}
        }
        return report;
    }

    public static JSONObject diagnostics(ProjectRangeExport.Prepared range) {
        if(range==null)return diagnostics((ProjectStore.Project)null);
        return rangeDiagnostics(range.project(),range);
    }

    private static JSONObject rangeDiagnostics(ProjectStore.Project project,ProjectRangeExport.Prepared range) {
        JSONObject report=diagnostics(project);
        try {
            JSONArray unsupported=report.optJSONArray("unsupported");
            if(unsupported==null){unsupported=new JSONArray();report.put("unsupported",unsupported);}
            if(range.durationMs<=0L||range.durationMs>Long.MAX_VALUE/1000L)
                unsupported.put("Requested range exceeds the native duration clock");
            if(project.outputDurationMs()>range.durationMs)
                unsupported.put("Cropped media extends beyond the requested range");
            if(report.optInt("visualTracks",0)==0&&report.optInt("audibleClips",0)==0)
                unsupported.put("Requested range contains no enabled renderable media");
            report.put("supported",unsupported.length()==0);
            JSONObject rangeMetadata=range.metadata();
            rangeMetadata.put("terminalGapMs",Math.max(0L,range.durationMs-project.outputDurationMs()));
            report.put("exportRange",rangeMetadata);
            JSONArray warnings=report.optJSONArray("warnings");
            if(warnings==null){warnings=new JSONArray();report.put("warnings",warnings);}
            JSONArray rangeWarnings=range.warnings();
            for(int index=0;index<rangeWarnings.length();index++)warnings.put(rangeWarnings.get(index));
            report.put("rangeDurationPolicy","exact requested composition span; encoded frame/AAC packet end checked within 100ms");
            warnings.put("Explicit range milliseconds determine its endpoints; a partial final frame can change authored cel exposure counts");
        }catch(Exception error){try{report.put("supported",false);report.put("unsupported",new JSONArray().put(message(error)));}catch(Exception ignored){}}
        return report;
    }

    public Handle export(ProjectStore.Project project,File outputFile,String aspect,String quality,Listener listener) {
        return exportInternal(project,outputFile,aspect,quality,listener,null);
    }

    public Handle export(ProjectRangeExport.Prepared range,File outputFile,String aspect,String quality,Listener listener) {
        if(listener==null)throw new IllegalArgumentException("Export listener is required");
        try {
            if(range==null)throw new IllegalArgumentException("Requested export range is missing");
            return exportInternal(range.project(),outputFile,aspect,quality,listener,range);
        }catch(Exception error){listener.onError(message(error));return null;}
    }

    private Handle exportInternal(ProjectStore.Project project,File outputFile,String aspect,String quality,Listener listener,
                                  ProjectRangeExport.Prepared range) {
        if(listener==null)throw new IllegalArgumentException("Export listener is required");
        try {
            if(project==null||project.clips.isEmpty())throw new IllegalArgumentException("Timeline is empty");
            if(outputFile==null)throw new IllegalArgumentException("Export output is required");
            ProjectStore.Project snapshot=ProjectStore.Project.fromJson(project.toJson());
            JSONObject report=range==null?diagnostics(snapshot):rangeDiagnostics(snapshot,range);
            if(!report.optBoolean("supported",false))throw new IllegalArgumentException("Unsupported native render: "+report.optJSONArray("unsupported"));
            int[] size=NativeVideoEffects.outputSize(aspect,quality);
            long durationMs=range==null?snapshot.outputDurationMs():range.durationMs;
            if(durationMs<=0||durationMs>Long.MAX_VALUE/1000)throw new IllegalArgumentException("Invalid render duration");
            Map<String,Interval> renderTimings=renderTimings(snapshot,frameRate(snapshot),range==null?Long.MAX_VALUE:durationMs*1000L);
            long compositionDurationUs=range==null?renderTimings.values().stream().mapToLong(interval->interval.endUs).max().orElse(0L):durationMs*1000L;
            if(compositionDurationUs<=0L)throw new IllegalArgumentException("Native render timing is empty");
            report.put("compositionDurationUs",compositionDurationUs);
            report.put("expectedVideoFrameCount",NativeAnimationCadence.frameCount(compositionDurationUs,frameRate(snapshot)));
            JSONArray clockWarnings=report.optJSONArray("warnings");
            for(ProjectStore.Clip clip:snapshot.clips){Interval timing=renderTimings.get(clip.id);
                if(timing.startUs!=clip.startMs*1000L||timing.endUs!=clip.endMs()*1000L)
                    clockWarnings.put(clip.id+": native shared cel boundaries adjust its output window by at most one millisecond; authored millisecond timing remains stored");}
            validateSourceReferences(snapshot,outputFile);
            File parent=outputFile.getParentFile();
            if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("Could not create export workspace");
            if(outputFile.exists()&&!outputFile.delete())throw new IllegalStateException("Could not replace previous export");
            Handle handle=new Handle(main,outputFile,listener);
            main.post(() -> {
                if(handle.isCancelled()){handle.fail("Export cancelled");return;}
                startAttempt(handle,snapshot,aspect,quality,size,durationMs,report,renderTimings);
            });
            return handle;
        }catch(Exception error){listener.onError(message(error));return null;}
    }

    private void startAttempt(Handle handle,ProjectStore.Project project,String aspect,String quality,
                              int[] size,long durationMs,JSONObject report,Map<String,Interval> renderTimings) {
        if(handle.isCancelled()||handle.terminal.get())return;
        final int attempt=++handle.attempt;
        try {
            Composition composition=buildComposition(project,aspect,quality,size,report.getLong("compositionDurationUs"),renderTimings);
            DefaultEncoderFactory.Builder factory=new DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true).setEnableFormatFallback(false)
                    .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setMaxBFrames(0).build());
            if(handle.softwareRetry)factory.setVideoEncoderSelector(SOFTWARE_VIDEO);
            StreamingBitmapLoader bitmaps=new StreamingBitmapLoader(context,Math.max(size[0],size[1])*2);
            Transformer transformer=new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setVideoFrameProcessorFactory(new DefaultVideoFrameProcessor.Factory.Builder()
                            .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT).build())
                    .setEncoderFactory(NativeAnimationCadence.encoderFactory(factory.build(),frameRate(project)))
                    .setMuxerFactory(new DefaultMuxer.Factory().setVideoDurationUs(report.getLong("compositionDurationUs")))
                    .setAssetLoaderFactory(NativeAnimationCadence.assetLoaderFactory(new DefaultAssetLoaderFactory(context,
                            new DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
                            Clock.DEFAULT,null,bitmaps),bitmaps))
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition composition,ExportResult result) {
                            if(attempt!=handle.attempt)return;
                            handle.stopPolling();
                            if(handle.isCancelled()||handle.terminal.get())return;
                            handle.listener.onProgress(99,"Checking encoded output");
                            new Thread(() -> {
                                try {
                                    JSONObject verified=verifyOutput(handle.output,result,durationMs,size,report.optInt("visualTracks",0)>0,report.optInt("audibleClips",0)>0,report.has("exportRange"),
                                            frameRate(project),report.getLong("compositionDurationUs"),handle.cancelled);
                                    main.post(() -> {
                                        if(attempt!=handle.attempt)return;
                                        if(handle.isCancelled()||handle.terminal.get())return;
                                        if(!handle.terminal.compareAndSet(false,true))return;
                                        JSONObject info=new JSONObject();
                                        try {
                                            info.put("ok",true);info.put("path",handle.output.getAbsolutePath());info.put("sizeBytes",handle.output.length());
                                            info.put("projectId",project.id);info.put("projectRevision",project.revision);
                                            info.put("durationMs",durationMs);info.put("clipCount",project.clips.size());
                                            if(report.optJSONObject("exportRange")!=null)info.put("exportRange",new JSONObject(report.getJSONObject("exportRange").toString()));
                                            info.put("aspect",aspect);info.put("quality",quality);info.put("engine","Media3 Transformer 1.11.1 track compositor");
                                            info.put("animationFrameRate",frameRate(project));info.put("cadence",cadenceProfile(project));
                                            info.put("compositionDurationUs",report.getLong("compositionDurationUs"));
                                            info.put("layeredAnimation",hasLayered(project));
                                            info.put("animationMode",hasLayered(project)?"procedural image-layer 2.5D":"deterministic clip motion");
                                            info.put("videoEncoder",result.videoEncoderName);info.put("audioEncoder",result.audioEncoderName);
                                            info.put("softwareVideoRetry",handle.softwareRetry);info.put("initialCodecFailure",handle.firstFailure);
                                            info.put("capabilities",report);info.put("outputValidation",verified);
                                        }catch(Exception ignored){}
                                        handle.listener.onProgress(100,"Export complete");handle.listener.onCompleted(handle.output,info);
                                    });
                                }catch(Exception error){main.post(() -> handle.fail("Encoded output validation failed: "+message(error)));}
                            },"VideoStudio-export-validation").start();
                        }
                        @Override public void onError(Composition composition,ExportResult result,ExportException error) {
                            if(attempt!=handle.attempt)return;
                            handle.stopPolling();
                            if(handle.isCancelled()||handle.terminal.get())return;
                            String detail=codecMessage(error);
                            boolean encodingFailure=error.errorCode==ExportException.ERROR_CODE_ENCODER_INIT_FAILED
                                    ||error.errorCode==ExportException.ERROR_CODE_ENCODING_FAILED
                                    ||error.errorCode==ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED;
                            boolean videoFailure=error.codecInfo==null||(error.codecInfo.isVideo&&!error.codecInfo.isDecoder);
                            if(!handle.softwareRetry&&encodingFailure&&videoFailure&&report.optInt("visualTracks",0)>0&&hasHardwareAvc()&&hasSoftwareAvc()) {
                                handle.firstFailure=detail;handle.softwareRetry=true;
                                try{handle.transformer.cancel();}catch(Exception ignored){}
                                if(handle.output.exists()&&!handle.output.delete()){handle.fail(detail+"; could not remove partial output for retry");return;}
                                handle.listener.onProgress(0,"Video encoder failed; retrying once with an available device software AVC encoder");
                                startAttempt(handle,project,aspect,quality,size,durationMs,report,renderTimings);
                            }else handle.fail(detail+(handle.softwareRetry?"; software video retry also failed":""));
                        }
                    }).build();
            handle.transformer=transformer;
            transformer.start(composition,handle.output.getAbsolutePath());
            startProgressPolling(handle);
        }catch(Exception error){handle.fail("Could not start native export: "+message(error));}
    }

    private Composition buildComposition(ProjectStore.Project project,String aspect,String quality,int[] size,long durationUs,Map<String,Interval> renderTimings) {
        ArrayList<EditedMediaItemSequence> sequences=new ArrayList<>();
        Map<Integer,List<Interval>> intervals=new HashMap<>();
        List<ProjectStore.Track> tracks=project.renderTracks();
        int cadence=frameRate(project);
        boolean video=tracks.stream().anyMatch(track->!track.isAudio()&&project.isTrackEnabled(track)&&!project.clipsOnTrack(track.id).isEmpty());
        if(video) {
            // A tiny invisible bitmap supplies the explicitly selected primary
            // clock even while all real tracks are empty, and ensures the
            // multiple-input graph required for custom compositor alpha is used.
            sequences.add(new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_VIDEO))
                    .addItem(NativeAnimationCadence.clockItem(durationUs,cadence)).build());
            intervals.put(0,Collections.emptyList());
            for(int index=tracks.size()-1;index>=0;index--) {
                ProjectStore.Track track=tracks.get(index);
                if(track.isAudio()||!project.isTrackEnabled(track))continue;
                List<ProjectStore.Clip> clips=project.clipsOnTrack(track.id);
                if(clips.isEmpty())continue;
                for(String role:roles(clips)) {
                    EditedMediaItemSequence.Builder sequence=new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_VIDEO));
                    ArrayList<Interval> active=new ArrayList<>();long cursorUs=0;
                    for(ProjectStore.Clip clip:clips) {
                        ProjectStore.Asset asset=project.asset(clip.assetId);
                        String uri=visualUri(asset,clip,role);
                        if(uri.isEmpty())continue;
                        Interval timing=renderTimings.get(clip.id);
                        if(timing.startUs<cursorUs)throw new IllegalArgumentException("Overlapping render intervals on track "+track.id);
                        if(timing.startUs>cursorUs)sequence.addGap(timing.startUs-cursorUs);
                        String effectiveRole=isLayered(clip)?("head".equals(role)&&!isArticulated(clip)?"foreground":role):"flat";
                        sequence.addItem(buildVisualItem(asset,uri,clip,aspect,quality,effectiveRole,cadence,timing));
                        active.add(timing);cursorUs=timing.endUs;
                    }
                    if(cursorUs<durationUs)sequence.addGap(durationUs-cursorUs);
                    intervals.put(sequences.size(),active);sequences.add(sequence.build());
                }
            }
        }
        for(ProjectStore.Track track:tracks) {
            if(!project.isTrackAudible(track))continue;
            List<ProjectStore.Clip> clips=project.clipsOnTrack(track.id);
            boolean hasAudio=clips.stream().anyMatch(clip->{ProjectStore.Asset asset=project.asset(clip.assetId);return asset!=null&&asset.hasAudio&&!audioDetached(clip);});
            if(!hasAudio)continue;
            EditedMediaItemSequence.Builder sequence=new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_AUDIO));
            long cursorUs=0;
            for(ProjectStore.Clip clip:clips) {
                ProjectStore.Asset asset=project.asset(clip.assetId);
                if(asset==null||!asset.hasAudio||audioDetached(clip))continue;
                Interval timing=renderTimings.get(clip.id);
                if(timing.startUs<cursorUs)throw new IllegalArgumentException("Overlapping audio on track "+track.id);
                if(timing.startUs>cursorUs)sequence.addGap(timing.startUs-cursorUs);
                sequence.addItem(buildAudioItem(asset,clip,timing));cursorUs=timing.endUs;
            }
            if(cursorUs<durationUs)sequence.addGap(durationUs-cursorUs);
            sequences.add(sequence.build());
        }
        if(sequences.isEmpty())throw new IllegalArgumentException("No enabled renderable tracks");
        Composition.Builder composition=new Composition.Builder(sequences)
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
        if(video)composition.setVideoCompositorSettings(new VideoCompositorSettings() {
            @Override public Size getOutputSize(List<Size> inputSizes){return new Size(size[0],size[1]);}
            @Override public OverlaySettings getOverlaySettings(int inputId,long presentationTimeUs) {
                List<Interval> active=intervals.get(inputId);boolean visible=false;
                if(active!=null)for(Interval interval:active)if(presentationTimeUs>=interval.startUs&&presentationTimeUs<interval.endUs){visible=true;break;}
                return new StaticOverlaySettings.Builder().setAlphaScale(visible?1:0).build();
            }
        });
        return composition.build();
    }

    private EditedMediaItem buildVisualItem(ProjectStore.Asset asset,String uri,ProjectStore.Clip clip,String aspect,String quality,String role,int cadence,Interval timing) {
        boolean image=!"flat".equals(role)||(asset.mime!=null&&asset.mime.startsWith("image/"));
        MediaItem.Builder media=new MediaItem.Builder().setUri(Uri.parse(uri));
        if(image)media.setImageDurationMs(clip.outputDurationMs()).setTag(new NativeAnimationCadence.ImageTiming(timing.startUs));else media.setClippingConfiguration(clipping(clip));
        EditedMediaItem.Builder item=new EditedMediaItem.Builder(media.build()).setRemoveAudio(true);
        if(image)item.setFrameRate(cadence).setDurationUs(timing.endUs-timing.startUs);else applySpeed(item,renderSpeed(clip,timing));
        ArrayList<Effect> effects=new ArrayList<>();
        if(image)effects.add(new BitmapInputAlphaEffect());
        effects.addAll(NativeVideoEffects.build(clip,aspect,quality,timing.startUs,1,role));
        item.setEffects(new Effects(Collections.emptyList(),effects));
        return item.build();
    }

    private static Interval visualTiming(ProjectStore.Asset asset,ProjectStore.Clip clip,int cadence) {
        try {
            if(preciseCel(asset,clip,cadence))
                return new Interval(AnimationCelEdits.exposureStartUs(clip),AnimationCelEdits.exposureEndUs(clip));
            return new Interval(Math.multiplyExact(clip.startMs,1000L),Math.multiplyExact(clip.endMs(),1000L));
        }catch(Exception error){throw new IllegalArgumentException("Invalid native exposure clock for "+clip.id,error);}
    }

    private static boolean preciseCel(ProjectStore.Asset asset,ProjectStore.Clip clip,int cadence) throws Exception {
        return AnimationCelEdits.matchesFrameGrid(asset,clip)&&AnimationCelEdits.exposureFrameRate(clip)==cadence;
    }

    /** Cel precision belongs to both sides of a shared model boundary. Retiming
     * only one neighbor would either overlap it or insert an artificial gap. */
    private static Map<String,Interval> renderTimings(ProjectStore.Project project,int cadence,long terminalLimitUs) {
        try {
            HashMap<String,Interval> result=new HashMap<>();HashSet<String> anchors=new HashSet<>();
            for(ProjectStore.Clip clip:project.clips){ProjectStore.Asset asset=project.asset(clip.assetId);
                result.put(clip.id,visualTiming(asset,clip,cadence));if(preciseCel(asset,clip,cadence))anchors.add(clip.id);}
            shareBoundaries(project,result,anchors);
            HashMap<String,Interval> linked=new HashMap<>();
            for(ProjectStore.Clip clip:project.clips){ProjectStore.Track track=project.track(clip.trackId);
                if(clip.linkGroupId!=null&&!clip.linkGroupId.isEmpty()&&track!=null&&!track.isAudio())linked.put(clip.linkGroupId,result.get(clip.id));}
            for(ProjectStore.Clip clip:project.clips)if(linked.containsKey(clip.linkGroupId)){
                result.put(clip.id,linked.get(clip.linkGroupId));anchors.add(clip.id);}
            shareBoundaries(project,result,anchors);
            for(ProjectStore.Track track:project.tracks){long previousEnd=0L;
                for(ProjectStore.Clip clip:project.clipsOnTrack(track.id)){Interval timing=result.get(clip.id);
                    long end=clip.endMs()*1000L==terminalLimitUs?terminalLimitUs:Math.min(terminalLimitUs,timing.endUs);
                    timing=new Interval(timing.startUs,end);
                    if(timing.startUs<previousEnd||timing.endUs<=timing.startUs)
                        throw new IllegalArgumentException("Cel boundary precision leaves an invalid native window on track "+track.id+"; align its neighboring exposure frames");
                    if(Math.abs(timing.startUs-clip.startMs*1000L)>1000L||Math.abs(timing.endUs-clip.endMs()*1000L)>1000L)
                        throw new IllegalArgumentException("Cel boundary adjustment exceeds the one-millisecond native quantization bound");
                    result.put(clip.id,timing);previousEnd=timing.endUs;
                    ProjectStore.Asset asset=project.asset(clip.assetId);
                    if(asset!=null&&(asset.hasAudio||asset.mime!=null&&asset.mime.startsWith("video/")))renderSpeed(clip,timing);
                }}
            return result;
        }catch(RuntimeException error){throw error;}
        catch(Exception error){throw new IllegalArgumentException("Could not resolve native cel boundaries",error);}
    }

    private static void shareBoundaries(ProjectStore.Project project,Map<String,Interval> timings,HashSet<String> anchors) {
        for(ProjectStore.Track track:project.tracks){ProjectStore.Clip previous=null;
            for(ProjectStore.Clip clip:project.clipsOnTrack(track.id)){
                if(previous!=null&&previous.endMs()==clip.startMs&&(anchors.contains(previous.id)||anchors.contains(clip.id))){
                    Interval left=timings.get(previous.id),right=timings.get(clip.id);
                    long shared=anchors.contains(previous.id)?left.endUs:right.startUs;
                    if(Math.abs(left.endUs-right.startUs)>1000L)throw new IllegalArgumentException("Adjacent cel frame grids disagree beyond native quantization");
                    timings.put(previous.id,new Interval(left.startUs,shared));timings.put(clip.id,new Interval(shared,right.endUs));
                }
                previous=clip;
            }}
    }

    private static float renderSpeed(ProjectStore.Clip clip,Interval timing) {
        long span=timing.endUs-timing.startUs;
        float rate=(float)(clip.effectiveSpeed()*(clip.outputDurationMs()*1000.0/span));
        boolean adjusted=span!=clip.outputDurationMs()*1000L;
        if(span<=0L||!Float.isFinite(rate)||rate<=0f||adjusted&&(rate<.1f||rate>16f))
            throw new IllegalArgumentException("Native cel boundary adjustment leaves an unsupported source speed for "+clip.id);
        return rate;
    }

    private EditedMediaItem buildAudioItem(ProjectStore.Asset asset,ProjectStore.Clip clip,Interval timing) {
        if(audioDetached(clip))throw new IllegalArgumentException("Detached embedded audio cannot be included twice");
        MediaItem media=new MediaItem.Builder().setUri(Uri.parse(asset.uri)).setClippingConfiguration(clipping(clip)).build();
        EditedMediaItem.Builder item=new EditedMediaItem.Builder(media).setRemoveVideo(true);
        applySpeed(item,renderSpeed(clip,timing));
        // Media3 applies setSpeed in its preprocessing pipeline, before these
        // processors. Combined floating-point gain and DSP use that same clip
        // output clock and clamp only once when writing the final PCM frame.
        List<AudioProcessor> audio=Collections.singletonList(new AudioDspProcessor(clip));
        return item.setEffects(new Effects(audio,Collections.emptyList())).build();
    }
    private static MediaItem.ClippingConfiguration clipping(ProjectStore.Clip clip) {
        return new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.inMs).setEndPositionMs(clip.outMs).build();
    }
    private static boolean audioDetached(ProjectStore.Clip clip){return clip.effects!=null&&clip.effects.optBoolean("audioDetached",false);}
    @androidx.annotation.OptIn(markerClass=androidx.media3.common.util.ExperimentalApi.class)
    private static void applySpeed(EditedMediaItem.Builder item,float rate) {
        if(!Float.isFinite(rate)||rate<=0)throw new IllegalArgumentException("Effective source speed must be positive");
        if(rate!=1)item.setSpeed(new SpeedParameters(new SpeedProvider(){@Override public float getSpeed(long timeUs){return rate;}@Override public long getNextSpeedChangeTimeUs(long timeUs){return C.TIME_UNSET;}},true));
    }

    private static List<String> roles(List<ProjectStore.Clip> clips) {
        boolean layered=false,articulated=false;
        for(ProjectStore.Clip clip:clips){layered|=isLayered(clip);articulated|=isArticulated(clip);}
        return articulated?java.util.Arrays.asList("head","torso","lower","background")
                :layered?java.util.Arrays.asList("foreground","background"):Collections.singletonList("flat");
    }
    private static boolean isLayered(ProjectStore.Clip clip){return clip.effects!=null&&clip.effects.optBoolean("animatedScene",false);}
    private static boolean isArticulated(ProjectStore.Clip clip){return isLayered(clip)&&!clip.effects.optString("headUri","").isEmpty()&&!clip.effects.optString("torsoUri","").isEmpty()&&!clip.effects.optString("lowerUri","").isEmpty();}
    private static boolean hasLayered(ProjectStore.Project project){for(ProjectStore.Clip clip:project.clips)if(isLayered(clip))return true;return false;}
    private static String visualUri(ProjectStore.Asset asset,ProjectStore.Clip clip,String role) {
        if(!isLayered(clip))return "flat".equals(role)||"background".equals(role)?asset.uri:"";
        if("head".equals(role)&&!isArticulated(clip))return clip.effects.optString("foregroundUri","");
        return clip.effects.optString(role+"Uri","");
    }

    private void validateSourceReferences(ProjectStore.Project project,File output) throws Exception {
        String target=output.getCanonicalPath();
        for(ProjectStore.Track track:project.tracks) {
            if(!project.isTrackEnabled(track))continue;
            for(ProjectStore.Clip clip:project.clipsOnTrack(track.id)) {
                ProjectStore.Asset asset=project.asset(clip.assetId);
                if(asset==null||asset.uri==null||asset.uri.isEmpty())throw new IllegalArgumentException("Missing source for clip "+clip.id);
                boolean audio=asset.mime!=null&&asset.mime.startsWith("audio/");
                boolean visual=asset.mime!=null&&(asset.mime.startsWith("image/")||asset.mime.startsWith("video/"));
                if(track.isAudio()?(!audio&&!asset.hasAudio):!visual)throw new IllegalArgumentException("Media type does not match track for clip "+clip.id);
                assertReadable(asset.uri,target);
                if(isLayered(clip))for(String role:roles(Collections.singletonList(clip)))assertReadable(visualUri(asset,clip,role),target);
            }
        }
    }
    private void assertReadable(String raw,String target) throws Exception {
        if(raw==null||raw.isEmpty())throw new IllegalArgumentException("Generated layer source is missing");
        Uri uri=Uri.parse(raw);
        if("file".equalsIgnoreCase(uri.getScheme())&&uri.getPath()!=null&&target.equals(new File(uri.getPath()).getCanonicalPath()))throw new IllegalArgumentException("Export must not overwrite imported source media");
        try(AssetFileDescriptor fd=context.getContentResolver().openAssetFileDescriptor(uri,"r")){if(fd==null)throw new IllegalArgumentException("Source is no longer readable: "+uri.getLastPathSegment());}
    }

    private static final EncoderSelector SOFTWARE_VIDEO=mime->{
        ImmutableList.Builder<MediaCodecInfo> selected=ImmutableList.builder();
        for(MediaCodecInfo codec:EncoderUtil.getSupportedEncoders(mime))if(codec.isSoftwareOnly())selected.add(codec);
        return selected.build();
    };
    private static boolean hasSoftwareAvc(){return !SOFTWARE_VIDEO.selectEncoderInfos(MimeTypes.VIDEO_H264).isEmpty();}
    private static boolean hasHardwareAvc(){for(MediaCodecInfo codec:EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H264))if(codec.isHardwareAccelerated()&&!codec.isSoftwareOnly())return true;return false;}
    private static String codecMessage(ExportException error){return error.getErrorCodeName()+": "+message(error)+(error.codecInfo==null?"":" ["+error.codecInfo.name+"]");}

    private void startProgressPolling(Handle handle) {
        ProgressHolder holder=new ProgressHolder();
        handle.progressTask=new Runnable(){@Override public void run(){
            if(handle.isCancelled()||handle.terminal.get())return;
            try{if(handle.transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE)handle.listener.onProgress(Math.min(98,holder.progress),(handle.softwareRetry?"Software video retry ":"Native export ")+holder.progress+"%");}
            catch(Exception ignored){}
            // UNAVAILABLE is temporary (e.g. source metadata is still loading).
            main.postDelayed(this,450);
        }};
        main.post(handle.progressTask);
    }

    private static JSONObject verifyOutput(File output,ExportResult result,long durationMs,int[] size,boolean requireVideo,boolean requireAudio,boolean rangeExport,
                                           int cadence,long compositionDurationUs,AtomicBoolean cancelled) throws Exception {
        if(!output.isFile()||output.length()==0)throw new IllegalStateException("Encoded file is empty");
        MediaExtractor extractor=new MediaExtractor();
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        Bitmap frame=null;
        try {
            extractor.setDataSource(output.getAbsolutePath());boolean video=false,audio=false;long actualDurationUs=0;int videoTrack=-1;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(format.containsKey(MediaFormat.KEY_DURATION))actualDurationUs=Math.max(actualDurationUs,format.getLong(MediaFormat.KEY_DURATION));
                if(mime!=null&&mime.startsWith("video/")) {
                    video=true;videoTrack=i;
                    if(!format.containsKey(MediaFormat.KEY_WIDTH)||!format.containsKey(MediaFormat.KEY_HEIGHT))throw new IllegalStateException("Encoder output has no dimensions");
                    int displayWidth=format.getInteger(MediaFormat.KEY_WIDTH),displayHeight=format.getInteger(MediaFormat.KEY_HEIGHT);
                    // Media3 may encode portrait frames sideways and write a 90°
                    // container rotation. Validate the displayed format, not the
                    // underlying AVC raster orientation.
                    retriever.setDataSource(output.getAbsolutePath());
                    String rotationValue=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    int rotation=rotationValue==null?0:Integer.parseInt(rotationValue);
                    if(Math.floorMod(rotation,180)==90){int swap=displayWidth;displayWidth=displayHeight;displayHeight=swap;}
                    if(displayWidth!=size[0]||displayHeight!=size[1])throw new IllegalStateException("Encoder output resolution differs from requested format");
                }else if(mime!=null&&mime.startsWith("audio/"))audio=true;
            }
            if(requireVideo&&!video)throw new IllegalStateException("Encoded output contains no video track");
            if(requireAudio&&!audio)throw new IllegalStateException("Encoded output lost the audible source audio");
            if(!video&&!audio)throw new IllegalStateException("Encoded output contains no playable tracks");
            if(actualDurationUs<=0)throw new IllegalStateException("Encoded output has no valid duration");
            long toleranceUs=rangeExport?100000L:Math.max(150000, durationMs*1000L/100);
            if(Math.abs(actualDurationUs-durationMs*1000L)>toleranceUs)throw new IllegalStateException("Encoded duration differs from timeline: "+actualDurationUs/1000+"ms vs "+durationMs+"ms");
            if(video) {
                retriever.setDataSource(output.getAbsolutePath());
                frame=retriever.getScaledFrameAtTime(Math.min(100000,actualDurationUs/2),MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320);
                if(frame==null||frame.getWidth()==0||frame.getHeight()==0)throw new IllegalStateException("Encoded video does not decode a frame");
            }
            if(audio) {
                for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){extractor.selectTrack(i);break;}
                if(extractor.readSampleData(ByteBuffer.allocateDirect(65536),0)<=0)throw new IllegalStateException("Encoded audio contains no sample");
            }
            long videoFrameCount=0L;
            if(video) {
                // Codec metadata is only a hint. Scan actual encoded sample PTS
                // using constant memory; maxBFrames=0 keeps presentation order.
                for(int index=0;index<extractor.getTrackCount();index++)extractor.unselectTrack(index);
                extractor.selectTrack(videoTrack);extractor.seekTo(0L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long expectedFrames=NativeAnimationCadence.frameCount(compositionDurationUs,cadence);
                while(extractor.getSampleTime()>=0L) {
                    if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Export cancelled during cadence validation");
                    long pts=extractor.getSampleTime();
                    long expectedPts=NativeAnimationCadence.timestampUs(videoFrameCount,cadence);
                    if(Math.abs(pts-expectedPts)>NativeAnimationCadence.TIMESTAMP_TOLERANCE_US)
                        throw new IllegalStateException("Encoded sample cadence differs from requested "+cadence+" fps at frame "+videoFrameCount+": "+pts+"us vs "+expectedPts+"us");
                    videoFrameCount++;
                    if(videoFrameCount>expectedFrames)throw new IllegalStateException("Encoder produced extra frames outside the requested cadence span");
                    if(!extractor.advance())break;
                }
                if(videoFrameCount!=expectedFrames)throw new IllegalStateException("Encoded frame count differs from requested cadence: "+videoFrameCount+" vs "+expectedFrames);
            }
            JSONObject info=new JSONObject();info.put("containerReadable",true);info.put("videoFrameDecoded",video);info.put("audioTrack",audio);
            info.put("durationMs",actualDurationUs/1000);info.put("width",video?size[0]:0);info.put("height",video?size[1]:0);
            info.put("requestedDurationMs",durationMs);info.put("durationToleranceMs",toleranceUs/1000L);
            info.put("encodedWidth",result.width);info.put("encodedHeight",result.height);
            info.put("videoTimestampCadenceVerified",video);info.put("videoFrameCount",videoFrameCount);
            info.put("animationFrameRate",cadence);info.put("compositionDurationUs",compositionDurationUs);
            info.put("timestampToleranceUs",NativeAnimationCadence.TIMESTAMP_TOLERANCE_US);
            info.put("scope","container, duration, requested dimensions, representative decoded frame and every encoded video sample timestamp; full decoded playback is not scanned");return info;
        }finally{if(frame!=null)frame.recycle();extractor.release();retriever.release();}
    }

    private static final class Interval { final long startUs,endUs; Interval(long startUs,long endUs){this.startUs=startUs;this.endUs=endUs;} }
    private static String message(Throwable error){String detail=error.getMessage();return detail==null||detail.trim().isEmpty()?error.getClass().getSimpleName():detail;}
}
