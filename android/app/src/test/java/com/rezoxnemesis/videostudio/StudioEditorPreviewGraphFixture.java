package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.transformer.CompositionPlayer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;

/**
 * UNIT ONLY: real CompositionPlayer admission/seek/control state, without a GL pipeline.
 * Robolectric's GLES20 shadow does not enumerate shader uniforms. It cannot execute the
 * native DefaultVideoFrameProcessor used on Android. This fixture never renders pixels,
 * proves effect fidelity, or establishes GPU/codec/export execution. Production is untouched.
 */
public final class StudioEditorPreviewGraphFixture {
    private StudioEditorPreviewGraphFixture(){}

    @Implements(value=CompositionPlayer.Builder.class,isInAndroidSdk=false)
    public static class BuilderShadow {
        @RealObject private CompositionPlayer.Builder builder;
        @Implementation public CompositionPlayer build(){
            builder.setVideoGraphFactory(new GraphFactory());
            return Shadow.directlyOn(builder,CompositionPlayer.Builder.class,"build");
        }
    }

    private static final class GraphFactory implements VideoGraph.Factory {
        @Override public VideoGraph create(Context context,ColorInfo color,DebugViewProvider debug,
                VideoGraph.Listener listener,Executor executor,long initialOffsetUs,boolean automatic){
            return new Graph(listener,executor,automatic);
        }
        @Override public boolean supportsMultipleInputs(){return false;}
    }

    /** Emits only the graph's protocol notifications; there is no visual output surface. */
    private static final class Graph implements VideoGraph {
        private final Listener listener;
        private final Executor executor;
        private final boolean automatic;
        private final ArrayDeque<Long> pending=new ArrayDeque<>();
        private final Map<Integer,Long> offsets=new HashMap<>();
        private final Map<Integer,SurfaceTexture> textures=new HashMap<>();
        private final Map<Integer,Surface> surfaces=new HashMap<>();
        private final Map<Integer,OnInputFrameProcessedListener> processed=new HashMap<>();
        private boolean ended,released,zero;
        private long lastTimestamp,generation;
        Graph(Listener listener,Executor executor,boolean automatic){this.listener=listener;this.executor=executor;this.automatic=automatic;}
        @Override public void initialize(){}
        @Override public void registerInput(int input){}
        @Override public void setOutputSurfaceInfo(SurfaceInfo surface){}
        @Override public void setOnInputSurfaceReadyListener(int input,Runnable ready){post(ready);}
        @Override public synchronized Surface getInputSurface(int input){
            Surface surface=surfaces.get(input);
            if(surface==null){SurfaceTexture texture=new SurfaceTexture(0);textures.put(input,texture);surface=new Surface(texture);surfaces.put(input,surface);}
            return surface;
        }
        @Override public synchronized void setOnInputFrameProcessedListener(int input,OnInputFrameProcessedListener value){processed.put(input,value);}
        @Override public void setCompositionEffects(List<Effect> effects){}
        @Override public void setCompositorSettings(VideoCompositorSettings settings){}
        @Override public synchronized void registerInputStream(int input,int type,Format format,List<Effect> effects,long offset){
            offsets.put(input,offset);ended=false;
            post(()->listener.onOutputSizeChanged(Math.max(1,format.width),Math.max(1,format.height)));
        }
        @Override public int getPendingInputFrameCount(int input){return 0;}
        @Override public boolean registerInputFrame(int input){return !released;}
        @Override public synchronized boolean queueInputBitmap(int input,Bitmap bitmap,TimestampIterator times){
            if(released)return false;
            while(times.hasNext())queue(times.next()+offsets.getOrDefault(input,0L));
            return true;
        }
        @Override public synchronized boolean queueInputTexture(int input,int texture,long time){
            if(released)return false;queue(time+offsets.getOrDefault(input,0L));
            OnInputFrameProcessedListener complete=processed.get(input);
            if(complete!=null)try{complete.onInputFrameProcessed(texture,0);}catch(VideoFrameProcessingException error){post(()->listener.onError(error));}
            return true;
        }
        private void queue(long time){
            lastTimestamp=time;zero|=time==0;
            if(!automatic)pending.addLast(time);
            post(()->listener.onOutputFrameAvailableForRendering(time,false));
        }
        @Override public synchronized void renderOutputFrame(long renderTimeNs){if(!pending.isEmpty())pending.removeFirst();finishIfDrained();}
        @Override public void redraw(){}
        @Override public synchronized boolean hasProducedFrameWithTimestampZero(){return zero;}
        @Override public synchronized void flush(){generation++;pending.clear();ended=false;zero=false;lastTimestamp=0;}
        @Override public synchronized void signalEndOfInput(int input){ended=true;finishIfDrained();}
        private void finishIfDrained(){if(ended&&pending.isEmpty()){ended=false;long last=lastTimestamp;post(()->listener.onEnded(last));}}
        @Override public synchronized void release(){
            generation++;released=true;pending.clear();
            for(Surface surface:surfaces.values())surface.release();for(SurfaceTexture texture:textures.values())texture.release();
            surfaces.clear();textures.clear();processed.clear();
        }
        private void post(Runnable callback){
            long expected=generation;
            executor.execute(()->{synchronized(Graph.this){if(released||expected!=generation)return;}callback.run();});
        }
    }
}
