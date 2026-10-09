package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Bounded original-media stages, one continuous audio pass and a verified lossless join. */
final class SegmentedRenderEngine {
    interface Capture {RenderSourceIdentity.Snapshot capture(ProjectStore.Project graph,String aspect,String quality,BooleanSupplier cancelled)throws Exception;}
    interface Journals {RenderSessionStore open();}
    interface Progress {void report(int percent,String detail);}
    interface Renderer {JSONObject render(ProjectStore.Project graph,RenderSessionStore.Kind kind,long startUs,long endUs,File output,String aspect,String quality,RenderRetryController.Route route,BooleanSupplier cancelled,Progress progress)throws Exception;}
    interface Mixer {JSONObject mux(ProjectStore.Project originals,List<RenderSessionStore.Checkpoint> video,RenderSessionStore.Checkpoint audio,File output,BooleanSupplier cancelled)throws Exception;}
    interface Timing {long durationUs(ProjectStore.Project graph)throws Exception;boolean hasAudio(ProjectStore.Project graph)throws Exception;}
    interface CheckpointGate {void await(String stage)throws Exception;}
    private static final long WINDOW_US=5_000_000;
    private static final ExecutorService WORKERS=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"studio-segmented-render");thread.setDaemon(true);return thread;});
    private final Capture capture;private final Journals journals;private final Renderer renderer;private final Mixer mixer;private final Timing timing;
    private final Executor workers,callbacks;

    SegmentedRenderEngine(Context context){this(new RenderSourceIdentity(context)::capture,()->new RenderSessionStore(context),nativeRenderer(context),
        (graph,video,audio,file,cancelled)->SegmentMediaMuxer.mux(context,graph,video,audio,file,cancelled),
        new Timing(){public long durationUs(ProjectStore.Project graph){return TimelineCompositionFactory.programDurationUs(graph);}public boolean hasAudio(ProjectStore.Project graph){return new TimelineCompositionFactory(context).buildAudio(graph)!=null;}},WORKERS,new Handler(Looper.getMainLooper())::post);}
    /** callbacks must serialize notifications; injection keeps lifecycle tests independent of codecs. */
    SegmentedRenderEngine(Capture capture,Journals journals,Renderer renderer,Mixer mixer,Timing timing,Executor workers,Executor callbacks){
        this.capture=capture;this.journals=journals;this.renderer=renderer;this.mixer=mixer;this.timing=timing;this.workers=workers;this.callbacks=callbacks;
    }
    static int windowCount(long durationUs){if(durationUs<=0)throw new IllegalArgumentException("Empty render programme");long count=(durationUs-1)/WINDOW_US+1;if(count>1_000_000)throw new IllegalArgumentException("Render window plan exceeds the journal bound");return (int)count;}
    static TimelineWindow window(long durationUs,int ordinal){int count=windowCount(durationUs);if(ordinal<0||ordinal>=count)throw new IllegalArgumentException("Window ordinal is outside the programme");long start=Math.multiplyExact((long)ordinal,WINDOW_US);return new TimelineWindow(start,Math.min(durationUs,Math.addExact(start,WINDOW_US)));}

    NativeRenderEngine.Handle export(ProjectStore.Project original,File output,String aspect,String quality,NativeRenderEngine.Listener listener){return export(original,output,aspect,quality,listener,stage->{});}
    NativeRenderEngine.Handle export(ProjectStore.Project original,File output,String aspect,String quality,NativeRenderEngine.Listener listener,CheckpointGate gate){
        Objects.requireNonNull(listener,"Render listener");Task task;
        try{
            if(original==null||original.clips.isEmpty())throw new IllegalArgumentException("Timeline is empty");
            ProjectStore.Project frozen=ProjectStore.Project.fromJson(original.snapshotJson());NativeRenderEngine.protectOriginals(frozen,output);
            if(Files.exists(output.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Render output already exists; use a fresh workspace");
            task=new Task(frozen,output,aspect,quality,listener,gate);workers.execute(task.future);
        }catch(Exception error){try{listener.onError(message(error));}catch(RuntimeException unavailable){android.util.Log.e("VideoStudioRender","Export error listener failed",unavailable);}return new NativeRenderEngine.Handle(()->{});}
        return new NativeRenderEngine.Handle(task::cancel);
    }

    private final class Task {
        final ProjectStore.Project original;final File output;final String aspect,quality;final NativeRenderEngine.Listener listener;final CheckpointGate gate;
        final AtomicBoolean cancelled=new AtomicBoolean(),aborted=new AtomicBoolean(),terminal=new AtomicBoolean(),errorNotified=new AtomicBoolean();
        final AtomicBoolean delivered=new AtomicBoolean(),completing=new AtomicBoolean();final Object notificationLock=new Object();
        boolean progressQueued;int progressValue;String progressDetail;
        final FutureTask<Void> future;int rendered,reused;boolean reusedAudio,repaired;
        Task(ProjectStore.Project graph,File output,String aspect,String quality,NativeRenderEngine.Listener listener,CheckpointGate gate){this.original=graph;this.output=output;this.aspect=aspect;this.quality=quality;this.listener=listener;this.gate=Objects.requireNonNull(gate);future=new FutureTask<>(()->{run();return null;});}
        boolean stopped(){return cancelled.get()||aborted.get();}
        void check()throws IOException{SegmentMediaMuxer.check(this::stopped);}
        void cancel(){cancelled.set(true);future.cancel(true);}
        void progress(int value,String detail){
            synchronized(notificationLock){if(stopped()||terminal.get()||delivered.get()||completing.get())return;progressValue=Math.max(0,Math.min(99,value));progressDetail=detail;if(progressQueued)return;progressQueued=true;}
            callbacks.execute(this::dispatchProgress);
        }
        void dispatchProgress(){
            int value;String detail;synchronized(notificationLock){value=progressValue;detail=progressDetail;progressDetail=null;}
            try{if(!stopped()&&!delivered.get()&&!completing.get())listener.onProgress(value,detail);}catch(CancellationException stop){cancel();}catch(RuntimeException failure){fail(failure);}
            finally{boolean again;synchronized(notificationLock){again=progressDetail!=null&&!stopped()&&!terminal.get()&&!delivered.get();if(!again)progressQueued=false;}if(again)callbacks.execute(this::dispatchProgress);}
        }
        JSONObject render(ProjectStore.Project graph,RenderSessionStore.Kind kind,long start,long end,File stage,RenderRetryController.Route route,java.util.function.IntUnaryOperator scale)throws Exception{
            AtomicBoolean active=new AtomicBoolean(true);try{return renderer.render(graph,kind,start,end,stage,aspect,quality,route,this::stopped,(value,detail)->{if(active.get())progress(scale.applyAsInt(value),detail);});}finally{active.set(false);}
        }
        void boundary(String stage)throws Exception{check();gate.await(stage);check();}
        void fail(Exception failure){if(cancelled.get()||delivered.get())return;aborted.set(true);terminal.set(true);future.cancel(true);if(errorNotified.compareAndSet(false,true))callbacks.execute(()->{if(cancelled.get()||!delivered.compareAndSet(false,true))return;try{listener.onError(message(failure));}catch(CancellationException stop){cancel();}catch(RuntimeException unavailable){android.util.Log.e("VideoStudioRender","Segmented error listener failed",unavailable);}});}
        void run(){
            try{
                boundary("native_source_snapshot");progress(0,"Capturing original media");RenderSourceIdentity.Snapshot snapshot=capture.capture(original,aspect,quality,this::stopped);check();
                JSONObject proof;
                try(RenderSessionStore store=journals.open()){
                    RenderSessionStore.Writer writer=store.acquire(snapshot);
                    try{
                        JSONObject saved=store.session(writer);ProjectStore.Project graph=ProjectStore.Project.fromJson(saved.getJSONObject("project"));long duration=timing.durationUs(graph);int count=windowCount(duration);boolean audioPresent=timing.hasAudio(graph);
                        RenderRetryController.Route route=RenderRetryController.Route.valueOf(saved.getString("codecRoute").toUpperCase(java.util.Locale.ROOT));repaired=route!=RenderRetryController.Route.CONSERVATIVE;
                        while(true){
                            reused=0;RenderRetryController.Route highest=route;
                            for(int i=0;i<count;i++){
                                boundary("native_video_window_"+i);TimelineWindow window=window(duration,i);RenderSessionStore.Checkpoint checkpoint=store.reusable(writer,RenderSessionStore.Kind.VIDEO,i,window.startUs,window.endUs);
                                if(checkpoint==null){File stage=store.begin(writer,RenderSessionStore.Kind.VIDEO,i,window.startUs,window.endUs,seek(snapshot,window.startUs,window.endUs));final int ordinal=i;
                                    JSONObject encoded=render(graph,RenderSessionStore.Kind.VIDEO,window.startUs,window.endUs,stage,route,percent->(int)((ordinal+(percent/100d))*80/count));check();checkpoint=store.complete(writer,RenderSessionStore.Kind.VIDEO,i,encoded);rendered++;
                                }else reused++;
                                highest=highest(highest,checkpoint.proof.optString("codecRoute"));progress((i+1)*80/count,"Verified video window "+(i+1)+" of "+count);
                            }
                            RenderSessionStore.Checkpoint audio=null;reusedAudio=false;
                            if(audioPresent){boundary("native_continuous_audio");audio=store.reusable(writer,RenderSessionStore.Kind.AUDIO,0,0,duration);reusedAudio=audio!=null;
                                if(audio==null){File stage=store.begin(writer,RenderSessionStore.Kind.AUDIO,0,0,duration,seek(snapshot,0,duration).put("wholeProgramAudio",true));JSONObject encoded=render(graph,RenderSessionStore.Kind.AUDIO,0,duration,stage,route,percent->80+percent*10/100);check();audio=store.complete(writer,RenderSessionStore.Kind.AUDIO,0,encoded);}
                            }
                            boundary("native_verified_join");progress(92,"Joining verified windows");
                            try{List<RenderSessionStore.Checkpoint> video=records(store,writer,duration,count);proof=mixer.mux(original,video,audio,output,this::stopped);check();
                                JSONObject first=video.get(0).proof;for(String field:new String[]{"videoEncoder","codecProfile","codecWidth","codecHeight","codecFps","configuredBitrate","requestedBitrate","encodedWidth","encodedHeight"})if(first.has(field))proof.put(field,first.get(field));
                                proof.put("audioEncoder",audio==null?"":audio.proof.optString("audioEncoder")).put("firstVideoCodecRoute",first.optString("codecRoute")).put("codecRoute",highest.name().toLowerCase(java.util.Locale.ROOT));
                                proof.put("renderSessionId",snapshot.sessionId).put("reusedVideoWindows",reused).put("renderedVideoWindows",rendered).put("reusedContinuousAudio",reusedAudio).put("codecConfigurationRepaired",repaired);break;
                            }catch(SegmentMediaMuxer.IncompatibleConfigurationException mismatch){
                                int next=Math.max(highest.ordinal(),route.ordinal()+1);if(next>=RenderRetryController.Route.values().length)throw mismatch;
                                route=RenderRetryController.Route.values()[next];store.codecRoute(writer,route);repaired=true;progress(0,"Rebuilding video with a consistent codec configuration");
                                for(int i=0;i<count;i++){check();store.invalidate(writer,RenderSessionStore.Kind.VIDEO,i);}writer.close();writer=store.acquire(snapshot);
                            }
                        }
                    }finally{writer.close();}
                }
                check();final JSONObject ready=new JSONObject(proof.toString());if(terminal.compareAndSet(false,true))callbacks.execute(()->{if(stopped()||delivered.get()||!completing.compareAndSet(false,true))return;try{listener.onCompleted(output,ready);delivered.set(true);}catch(CancellationException stop){cancel();}catch(RuntimeException failure){fail(failure);}finally{completing.set(false);}});
            }catch(Exception error){if(!stopped())fail(error);}
        }
    }
    private static JSONObject seek(RenderSourceIdentity.Snapshot snapshot,long start,long end)throws Exception{return new JSONObject().put("windowStartUs",start).put("windowEndUs",end).put("originalGraphHash",snapshot.manifest.getString("graphHash"));}
    private static RenderRetryController.Route highest(RenderRetryController.Route current,String name){try{var parsed=RenderRetryController.Route.valueOf(name.toUpperCase(java.util.Locale.ROOT));return parsed.ordinal()>current.ordinal()?parsed:current;}catch(IllegalArgumentException unavailable){return current;}}
    /** Materialize at most one checkpoint's metadata. The muxer freshly verifies every input. */
    private static List<RenderSessionStore.Checkpoint> records(RenderSessionStore store,RenderSessionStore.Writer writer,long duration,int count){return new AbstractList<>(){
        public int size(){return count;}
        public RenderSessionStore.Checkpoint get(int index){TimelineWindow window=window(duration,index);try{var checkpoint=store.recorded(writer,RenderSessionStore.Kind.VIDEO,index,window.startUs,window.endUs);if(checkpoint==null)throw new IllegalStateException("Verified video checkpoint disappeared");return checkpoint;}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Could not read video checkpoint metadata",failure);}}
    };}
    private static Renderer nativeRenderer(Context context){return (graph,kind,start,end,file,aspect,quality,route,cancelled,progress)->{
        SegmentMediaMuxer.check(cancelled);var factory=new TimelineCompositionFactory(context);var composition=kind==RenderSessionStore.Kind.VIDEO?factory.buildVideoWindow(graph,aspect,quality,new TimelineWindow(start,end)):factory.buildAudio(graph);
        CompletableFuture<JSONObject> result=new CompletableFuture<>();NativeRenderEngine.Handle handle=new NativeRenderEngine(context).exportComposition(graph,composition,file,aspect,quality,kind==RenderSessionStore.Kind.VIDEO,new NativeRenderEngine.Listener(){
            public void onProgress(int percent,String detail){if(!cancelled.getAsBoolean())progress.report(percent,detail);}
            public void onCompleted(File output,JSONObject proof){result.complete(proof);}
            public void onError(String detail){result.completeExceptionally(new IOException(detail));}
        },route);
        if(handle==null)throw new IOException("Could not start bounded native stage");
        try{while(true){SegmentMediaMuxer.check(cancelled);try{return result.get(500,TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){/* Observe STOP even while codec progress is stalled. */}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("Native stage failed",cause);}}}
        finally{handle.cancel();}
    };}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
