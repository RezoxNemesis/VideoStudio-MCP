package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
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
        private final Runnable cancellation;
        Handle(RenderRetryController controller){this(controller::cancel);}
        Handle(Runnable cancellation){this.cancellation=cancellation;}
        public void cancel(){cancellation.run();}
    }

    private static final java.util.concurrent.ExecutorService VERIFICATION=java.util.concurrent.Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"studio-render-verification");t.setDaemon(true);return t;});
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    public NativeRenderEngine(Context context){this.context=context.getApplicationContext();}

    public Handle export(ProjectStore.Project project,File outputFile,String aspect,String quality,Listener listener){
        return export(project,outputFile,aspect,quality,listener,RenderRetryController.Route.DEFAULT);
    }
    /** Package-private route entry also supports real codec-path device diagnostics. */
    Handle export(ProjectStore.Project original,File outputFile,String aspect,String quality,Listener listener,RenderRetryController.Route initialRoute){
        if(original==null||original.clips.isEmpty()){listener.onError("Timeline is empty");return null;}
        try{
            ProjectStore.Project project=ProjectStore.Project.fromJson(original.snapshotJson());
            protectOriginals(project,outputFile);
            Composition composition=new TimelineCompositionFactory(context).build(project,aspect,quality,false);
            return exportComposition(project,composition,outputFile,aspect,quality,true,listener,initialRoute);
        }catch(Exception error){listener.onError(error.getMessage()==null?"Could not prepare native export":error.getMessage());return null;}
    }

    /** Bounded worker entry for original-clock windows and the separate continuous audio pass. */
    Handle exportComposition(ProjectStore.Project original,Composition composition,File outputFile,String aspect,String quality,boolean requireVideo,Listener listener,RenderRetryController.Route initialRoute){
        try{
            if(composition==null)throw new IllegalArgumentException("Native composition is absent");
            ProjectStore.Project project=ProjectStore.Project.fromJson(original.snapshotJson());protectOriginals(project,outputFile);
            RenderRetryController controller=new RenderRetryController(main::post,VERIFICATION,
                    (route,callback)->startAttempt(project,composition,outputFile,aspect,quality,route,callback),
                    ()->PlayableMediaVerifier.verify(context,Uri.fromFile(outputFile),requireVideo),
                    new RenderRetryController.Observer(){
                        public void progress(int percent,String detail){listener.onProgress(percent,detail);}
                        public void failed(String detail){listener.onError(detail);}
                        public void completed(JSONObject result){
                            if(requireVideo)
                            try(CodecReliabilityStore history=new CodecReliabilityStore(context)){
                                history.verified(result.optString("videoEncoder"),result.optInt("codecWidth"),result.optInt("codecHeight"),result.optString("codecProfile"),result.optInt("codecFps",project.settings.optInt("fps",30)),result.getString("sha256"));
                                result.put("codecReliabilityRecorded",true);
                            }catch(Exception unavailable){android.util.Log.w("VideoStudioRender","Could not record codec success",unavailable);}
                            listener.onCompleted(outputFile,result);
                        }
                    });
            controller.start(initialRoute);return new Handle(controller);
        }catch(Exception error){listener.onError(error.getMessage()==null?"Could not prepare native export":error.getMessage());return null;}
    }

    private RenderRetryController.Attempt startAttempt(ProjectStore.Project project,Composition composition,File outputFile,String aspect,String quality,RenderRetryController.Route route,RenderRetryController.Callback callback)throws Exception{
        // outputFile is the caller's dedicated render workspace, never an input asset.
        protectOriginals(project,outputFile);
        if(outputFile.exists()&&!outputFile.delete())throw new java.io.IOException("Could not replace failed export workspace");
        int[] dimensions=NativeCodecPolicy.dimensions(aspect,quality);int fps=project.settings.optInt("fps",30);
        String profile=route==RenderRetryController.Route.DEFAULT?"default":"baseline";
        ReliableDecoderFactory decoder=new ReliableDecoderFactory(context,route,fps);
        java.util.Set<String> recordedErrors=java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicReference<androidx.media3.common.Format> encoderFormat=new java.util.concurrent.atomic.AtomicReference<>();
        androidx.media3.transformer.Codec.EncoderFactory encoder=new androidx.media3.transformer.Codec.EncoderFactory(){
            private androidx.media3.transformer.DefaultEncoderFactory configured(androidx.media3.common.Format format){
                int rate=format.frameRate>0?Math.max(1,Math.round(format.frameRate)):fps;
                int width=format.width>0?format.width:dimensions[0],height=format.height>0?format.height:dimensions[1];
                androidx.media3.transformer.VideoEncoderSettings settings=NativeCodecPolicy.settings(route,project.settings.optInt("exportBitrate",8_000_000),rate,width,height);
                androidx.media3.transformer.EncoderSelector selector=mime->{
                    List<android.media.MediaCodecInfo> codecs=new ArrayList<>(androidx.media3.transformer.EncoderSelector.DEFAULT.selectEncoderInfos(mime));
                    if(route==RenderRetryController.Route.SOFTWARE_CODECS)codecs.removeIf(codec->!codec.isSoftwareOnly());
                    try(CodecReliabilityStore history=new CodecReliabilityStore(context)){
                        return com.google.common.collect.ImmutableList.copyOf(NativeCodecPolicy.selectEncoders(codecs,codec->{android.media.MediaCodecInfo.VideoCapabilities video=codec.getCapabilitiesForType(mime).getVideoCapabilities();return video==null?null:video.getBitrateRange();},codec->history.penalty(codec.getName(),width,height,profile,rate),settings.bitrate));
                    }
                };
                return new androidx.media3.transformer.DefaultEncoderFactory.Builder(context).setEnableFallback(true).setEnableFormatFallback(false).setVideoEncoderSelector(selector)
                        .setRequestedVideoEncoderSettings(settings).setRequestedAudioEncoderSettings(new androidx.media3.transformer.AudioEncoderSettings.Builder().setBitrate(192_000).build()).build();
            }
            public androidx.media3.transformer.Codec createForAudioEncoding(androidx.media3.common.Format format,android.media.metrics.LogSessionId session)throws ExportException{return configured(format).createForAudioEncoding(format,session);}
            public androidx.media3.transformer.Codec createForVideoEncoding(androidx.media3.common.Format format,android.media.metrics.LogSessionId session)throws ExportException{
                format=NativeCodecPolicy.encoderRequest(format);
                encoderFormat.set(format);
                androidx.media3.transformer.Codec codec=configured(format).createForVideoEncoding(format,session);
                androidx.media3.common.Format actual=codec.getConfigurationFormat();
                int requested=Math.max(500_000,Math.min(50_000_000,project.settings.optInt("exportBitrate",8_000_000)));
                if(!NativeCodecPolicy.matchesConfiguredFormat(format,actual,requested)){
                    String name=codec.getName();codec.release();
                    throw ExportException.createForCodec(new IllegalStateException("Encoder changed requested final resolution, frame rate or target bitrate: requested "+format+", resolved "+actual+", target "+requested),ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,new ExportException.CodecInfo(actual.toString(),true,false,name));
                }
                encoderFormat.set(actual);return codec;
            }
            public boolean isVideoFormatSupported(androidx.media3.common.Format format){format=NativeCodecPolicy.encoderRequest(format);return configured(format).isVideoFormatSupported(format);}
            public boolean audioNeedsEncoding(){return true;}
            public boolean videoNeedsEncoding(){return true;}
        };
        AtomicBoolean stopped=new AtomicBoolean();ProgressHolder progress=new ProgressHolder();Transformer[] active=new Transformer[1];Runnable[] poll=new Runnable[1];
        Transformer transformer=new Transformer.Builder(context)
                .setAssetLoaderFactory(new androidx.media3.transformer.DefaultAssetLoaderFactory(context,decoder,androidx.media3.common.util.Clock.DEFAULT,
                        new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context),new androidx.media3.datasource.DataSourceBitmapLoader(context)))
                .setEncoderFactory(encoder)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition completed,ExportResult result){
                        if(stopped.get())return;
                        try{
                            boolean layered=hasLayeredAnimation(project);
                            JSONObject info=new JSONObject().put("path",outputFile.getAbsolutePath()).put("clipCount",project.clips.size()).put("aspect",aspect).put("quality",quality)
                                    .put("engine","Media3 Transformer 1.11.1").put("layeredAnimation",layered)
                                    .put("animationMode",layered?(hasArticulatedAnimation(project)?"articulated-subject-2.5d":"subject-aware-2.5d"):"standard")
                                    .put("codecRoute",route.name().toLowerCase(java.util.Locale.ROOT)).put("codecProfile",profile)
                                    .put("videoEncoder",result.videoEncoderName==null?"":result.videoEncoderName).put("audioEncoder",result.audioEncoderName==null?"":result.audioEncoderName)
                                    .put("decoderNames",decoder.names()).put("encodedWidth",result.width).put("encodedHeight",result.height).put("requestedBitrate",NativeCodecPolicy.settings(route,project.settings.optInt("exportBitrate",8_000_000),fps).bitrate);
                            androidx.media3.common.Format configuration=encoderFormat.get();
                            if(configuration!=null)info.put("codecWidth",configuration.width).put("codecHeight",configuration.height).put("codecFps",Math.round(configuration.frameRate)).put("configuredBitrate",configuration.bitrate);
                            callback.encoded(info);
                        }catch(Exception error){callback.failed(new RenderRetryController.Failure(ExportException.ERROR_CODE_UNSPECIFIED,error.getMessage()));}
                    }
                    @Override public void onError(Composition failed,ExportResult result,ExportException error){
                        if(stopped.get())return;
                        android.util.Log.e("VideoStudioRender","Codec route "+route+" failed",error);
                        if(error.codecInfo!=null&&error.codecInfo.isDecoder)decoder.record(error);
                        else{
                            androidx.media3.common.Format configuration=encoderFormat.get();
                            recordCodecFailure(error,configuration==null?dimensions[0]:configuration.width,configuration==null?dimensions[1]:configuration.height,profile,configuration==null?fps:Math.max(1,Math.round(configuration.frameRate)),recordedErrors);
                        }
                        String detail=error.getMessage()==null?"Native export failed":error.getMessage();Throwable cause=error.getCause();int depth=0;
                        while(cause!=null&&depth++<3){detail+=" · "+cause.getClass().getSimpleName()+": "+cause.getMessage();cause=cause.getCause();}
                        callback.failed(new RenderRetryController.Failure(error.errorCode,detail));
                    }
                }).build();
        active[0]=transformer;
        poll[0]=()->{
            if(stopped.get())return;
            try{if(transformer.getProgress(progress)==Transformer.PROGRESS_STATE_AVAILABLE)callback.progress(progress.progress,"Native export "+progress.progress+"%");}
            catch(Exception ignored){}
            if(!stopped.get())main.postDelayed(poll[0],450);
        };
        try{transformer.start(composition,outputFile.getAbsolutePath());main.post(poll[0]);}
        catch(Exception failure){stopped.set(true);try{transformer.cancel();}catch(Exception ignored){}throw failure;}
        return ()->{if(stopped.compareAndSet(false,true)){main.removeCallbacks(poll[0]);try{active[0].cancel();}catch(Exception ignored){}}};
    }

    private void recordCodecFailure(ExportException error,int width,int height,String profile,int fps,java.util.Set<String> recorded){
        if(error.codecInfo==null||!RenderRetryController.codecFailure(error.errorCode)||!recorded.add(error.timestampMs+":"+error.codecInfo.name+":"+error.errorCode))return;
        try(CodecReliabilityStore history=new CodecReliabilityStore(context)){history.failure(error.codecInfo.name,width,height,profile,fps,error.getErrorCodeName());}
        catch(Exception unavailable){android.util.Log.w("VideoStudioRender","Could not record codec failure",unavailable);}
    }
    static void protectOriginals(ProjectStore.Project project,File output)throws java.io.IOException{
        File target=output.getCanonicalFile();
        for(ProjectStore.Asset asset:project.assets)protectSource(target,asset.uri);
        for(ProjectStore.Clip clip:project.clips)for(String layer:new String[]{"headUri","torsoUri","lowerUri","foregroundUri","backgroundUri"})protectSource(target,clip.effects.optString(layer));
    }
    private static void protectSource(File target,String source)throws java.io.IOException{
        Uri uri=Uri.parse(source);
        if("file".equals(uri.getScheme())&&target.equals(new File(uri.getPath()).getCanonicalFile()))throw new IllegalArgumentException("Export workspace refers to an original source");
    }

    private boolean hasLayeredAnimation(ProjectStore.Project project) {
        if (project == null || project.clips.isEmpty()) return false;
        boolean found = false;
        for (ProjectStore.Clip clip : project.clips) {
            ProjectStore.Asset asset = project.asset(clip.assetId);
            if (asset == null || asset.mime == null || !asset.mime.startsWith("image/")) continue;
            JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
            if (!fx.optBoolean("animatedScene", false)) continue;
            if (fx.optString("foregroundUri", "").isEmpty() || fx.optString("backgroundUri", "").isEmpty()) continue;
            if (fx.optJSONObject("animationSpec") == null) continue;
            found = true;
        }
        return found;
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

}
