package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.GlMatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.RgbMatrix;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Live still/title monitor using the native export effect graph.
 * One decoded bitmap is uploaded once, retained in Media3's replay cache, and
 * redrawn at the requested clip-output clock. No export file is produced.
 * The caller owns view placement; this helper owns its SurfaceTexture lifecycle.
 */
@UnstableApi
public final class StillGpuRenderer implements AutoCloseable {
    public interface Listener {
        void onReady();
        void onError(String detail);
    }
    public static final int MAX_BITMAP_EDGE=1536;
    public static final int MAX_RENDERERS=4;
    private static final long GLOBAL_TEXTURE_BUDGET=256L*1024L*1024L;
    private static final long FIRST_FRAME_TIMEOUT_MS=15_000,REDRAW_TIMEOUT_MS=8_000;
    private static final Object BUDGET_LOCK=new Object();
    private static long reservedBytes;private static int reservedRenderers;
    private final Context context;
    private final TextureView view;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService actor=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"still-monitor-control");thread.setDaemon(true);return thread;});
    private final AtomicLong requestedUs=new AtomicLong(),frameClockUs=new AtomicLong();
    private final AtomicBoolean requestPending=new AtomicBoolean(),closed=new AtomicBoolean();
    private final List<Effect> effects;
    private final int[] outputSize;
    private final int bitmapMaxEdge;
    private final long durationUs;
    private volatile boolean ready,failed,releaseIncomplete;
    private volatile long firstFrameSinceMs=android.os.SystemClock.elapsedRealtime();
    private volatile long lastTextureUpdateMs,lastDrawRequestedMs,pendingPresentationSinceMs;
    private long hiddenSinceMs;
    private final Runnable watchdog=new Runnable(){@Override public void run(){
        if(closed.get()||failed)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(!view.isAttachedToWindow()||!view.isShown()||view.getWindowVisibility()!=android.view.View.VISIBLE){
            if(hiddenSinceMs==0)hiddenSinceMs=now;main.postDelayed(this,1000);return;
        }
        if(hiddenSinceMs!=0){long paused=now-hiddenSinceMs;firstFrameSinceMs+=paused;
            if(pendingPresentationSinceMs!=0)pendingPresentationSinceMs+=paused;hiddenSinceMs=0;}
        if(!ready&&now-firstFrameSinceMs>FIRST_FRAME_TIMEOUT_MS)fail("Still GPU monitor did not produce its first frame within 15 seconds");
        else if(ready&&pendingPresentationSinceMs!=0&&now-pendingPresentationSinceMs>REDRAW_TIMEOUT_MS)
            fail("Still GPU monitor stopped presenting requested frames for 8 seconds");
        if(!closed.get()&&!failed)main.postDelayed(this,1000);
    }};
    private volatile ListenableFuture<Bitmap> decode;
    private DefaultVideoFrameProcessor processor;
    private Bitmap pendingBitmap,queuedBitmap;
    private Surface surface;
    private volatile SurfaceTexture texture;
    private boolean streamRegistered,frameBusy;
    private long lastRequestedUs=-1,reservation;

    /** Construct on the UI thread. Effect/clip changes require a new instance. */
    public StillGpuRenderer(Context context,ProjectStore.Clip clip,String uri,String aspect,Listener listener) {
        this(context,clip,uri,aspect,"flat",listener);
    }
    public StillGpuRenderer(Context context,ProjectStore.Clip clip,String uri,String aspect,String layerRole,Listener listener) {
        this(context,clip,uri,aspect,layerRole,MAX_BITMAP_EDGE,listener);
    }
    StillGpuRenderer(Context context,ProjectStore.Clip clip,String uri,String aspect,String layerRole,int bitmapMaxEdge,Listener listener) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Still monitor view must be created on the UI thread");
        this.context=context.getApplicationContext();this.listener=listener;
        this.bitmapMaxEdge=Math.max(64,Math.min(MAX_BITMAP_EDGE,bitmapMaxEdge));
        ProjectStore.Clip snapshot=ProjectStore.Clip.fromJson(clip.toJson());
        if(snapshot.outputDurationMs()>Long.MAX_VALUE/1000L)throw new IllegalArgumentException("Still duration exceeds the native clock range");
        durationUs=Math.max(1,snapshot.outputDurationMs())*1000L;outputSize=NativeVideoEffects.outputSize(aspect,"720p");
        effects=new ArrayList<>();effects.add(new StillFrameClockEffect(frameClockUs,false));
        effects.add(new BitmapInputAlphaEffect());
        effects.addAll(NativeVideoEffects.build(snapshot,aspect,"720p",0,1,layerRole));
        effects.add(new StillFrameClockEffect(frameClockUs,true));
        view=new TextureView(context);view.setOpaque(false);
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            @Override public void onSurfaceTextureAvailable(SurfaceTexture available,int width,int height) {
                if(closed.get()||failed){available.release();return;}
                firstFrameSinceMs=android.os.SystemClock.elapsedRealtime();
                if(hiddenSinceMs!=0)hiddenSinceMs=firstFrameSinceMs;
                available.setDefaultBufferSize(outputSize[0],outputSize[1]);
                if(!submit(()->{if(closed.get()||failed){available.release();return;}
                    try{detachSurface();texture=available;surface=new Surface(available);initialize();}
                    catch(Exception error){fail("Still GPU surface failed: "+message(error));}}))available.release();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture available,int width,int height) {
                // Buffer dimensions stay bounded; TextureView scales them to its view bounds.
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture destroyed) {
                if(closed.get()&&actor.isShutdown()){destroyed.release();return false;}
                // Detach EGL's producer before releasing the consumer, off the UI thread.
                if(!submit(()->{if(texture==destroyed)detachSurface();else destroyed.release();}))destroyed.release();return false;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture updated) {
                if(updated==texture){lastTextureUpdateMs=android.os.SystemClock.elapsedRealtime();pendingPresentationSinceMs=0;}
                if(updated==texture&&!ready&&!closed.get()&&!failed){ready=true;if(listener!=null)listener.onReady();}
            }
        });
        decode=new StreamingBitmapLoader(this.context,this.bitmapMaxEdge).loadBitmap(Uri.parse(uri));
        decode.addListener(()->{
            Bitmap result=null;
            try {
                result=decode.get();
                if(closed.get()||failed){result.recycle();return;}
                final Bitmap bitmap=result;
                try{actor.execute(()->{if(closed.get()||failed){bitmap.recycle();return;}pendingBitmap=bitmap;initialize();});}
                catch(RejectedExecutionException released){bitmap.recycle();}
            }catch(Exception error){if(!closed.get())fail("Still image decode failed: "+message(error));}
        },Runnable::run);
        main.postDelayed(watchdog,1000);
    }
    public TextureView view(){return view;}
    public boolean isReady(){return ready&&!failed&&!closed.get();}
    public boolean isFailed(){return failed;}
    /** Time is relative to this clip's output window, not the source trim clock. */
    public void renderAtUs(long outputLocalUs) {
        requestedUs.set(Math.max(0,Math.min(durationUs,outputLocalUs)));
        if(closed.get()||failed)return;
        if(requestPending.compareAndSet(false,true))submit(()->{requestPending.set(false);pump();});
    }
    private void initialize() {
        if(closed.get()||failed||surface==null)return;
        if(processor!=null){processor.setOutputSurfaceInfo(new SurfaceInfo(surface,outputSize[0],outputSize[1]));pump();return;}
        if(pendingBitmap==null)return;
        try {
            reserve(pendingBitmap);
            processor=new DefaultVideoFrameProcessor.Factory.Builder().setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT)
                    .setEnableReplayableCache(true).build().create(
                    context,DebugViewProvider.NONE,ColorInfo.SDR_BT709_LIMITED,false,actor,new VideoFrameProcessor.Listener(){
                        @Override public void onInputStreamRegistered(int type,Format format,List<Effect> registeredEffects) {
                            submit(()->{
                                if(closed.get()||failed||processor==null||pendingBitmap==null)return;
                                streamRegistered=true;long initial=requestedUs.get();frameClockUs.set(initial);lastRequestedUs=initial;frameBusy=true;
                                markDrawRequested();
                                try {
                                    if(!processor.queueInputBitmap(pendingBitmap,new SingleTimestamp())){fail("Still monitor refused its registered bitmap input");return;}
                                    queuedBitmap=pendingBitmap;pendingBitmap=null;
                                    // Media3 normally recycles on upload. Retain only a cleanup
                                    // reference because release may discard a queued upload.
                                }catch(Exception error){fail("Still bitmap upload failed: "+message(error));}
                            });
                        }
                        @Override public void onOutputFrameAvailableForRendering(long timeUs,boolean redraw) {
                            submit(()->{
                                if(closed.get()||failed||processor==null)return;
                                if(!redraw)processor.renderOutputFrame(android.os.SystemClock.elapsedRealtimeNanos());
                                frameBusy=false;pump();
                            });
                        }
                        @Override public void onError(VideoFrameProcessingException error){submit(()->fail("Still GPU pipeline failed: "+message(error)));}
                    });
            processor.setOutputSurfaceInfo(new SurfaceInfo(surface,outputSize[0],outputSize[1]));
            Format format=new Format.Builder().setWidth(pendingBitmap.getWidth()).setHeight(pendingBitmap.getHeight())
                    .setPixelWidthHeightRatio(1).setColorInfo(ColorInfo.SRGB_BT709_FULL).build();
            processor.registerInputStream(VideoFrameProcessor.INPUT_TYPE_BITMAP,format,effects,0);
        }catch(Exception error){fail("Still GPU initialization failed: "+message(error));}
    }
    private void pump() {
        if(closed.get()||failed||processor==null||!streamRegistered||frameBusy||surface==null)return;
        long wanted=requestedUs.get();if(wanted==lastRequestedUs)return;
        try{lastRequestedUs=wanted;frameClockUs.set(wanted);frameBusy=true;markDrawRequested();processor.redraw();}
        catch(Exception error){fail("Still GPU redraw failed: "+message(error));}
    }
    private void reserve(Bitmap bitmap) {
        // Conservative reservation: bitmap upload/sampler/cache/clock textures,
        // effect output pools, overlays and SurfaceTexture's display buffers.
        // Rig deformation and alpha normalization precede layout and retain
        // source dimensions; reserving all passes at the larger frame size
        // avoids undercounting those FBOs when a large source shrinks to 720p.
        long inputBytes=(long)bitmap.getWidth()*bitmap.getHeight()*4;
        long outputBytes=(long)outputSize[0]*outputSize[1]*4;
        long estimate=inputBytes*6+Math.max(inputBytes,outputBytes)*drawPasses(effects)+outputBytes*8;
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        long budget=manager!=null&&manager.isLowRamDevice()?GLOBAL_TEXTURE_BUDGET/2:GLOBAL_TEXTURE_BUDGET;
        if(manager!=null){ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);
            if(memory.lowMemory||memory.availMem<estimate*2+128L*1024*1024)throw new IllegalStateException("Not enough available memory for the still GPU monitor");}
        synchronized(BUDGET_LOCK){
            if(reservedRenderers>=MAX_RENDERERS||estimate>budget-reservedBytes)throw new IllegalStateException("Still GPU monitor exceeds its "+budget/1024/1024+"MB texture reservation or four-layer budget");
            reservation=estimate;reservedBytes+=estimate;reservedRenderers++;
        }
    }
    private static int drawPasses(List<Effect> effects) {
        int count=0;boolean matrixGroup=false;
        for(Effect effect:effects){boolean matrix=effect instanceof GlMatrixTransformation||effect instanceof RgbMatrix;
            if(matrix){if(!matrixGroup)count++;matrixGroup=true;}else{matrixGroup=false;count+=effect instanceof GaussianBlur?3:effect instanceof OverlayEffect?4:1;}}
        return count;
    }
    public JSONObject diagnostics() {
        JSONObject result=new JSONObject();try{result.put("pipeline","Media3 replayable bitmap + native export effects");result.put("ready",isReady());result.put("failed",failed);
            result.put("maxBitmapEdge",bitmapMaxEdge);result.put("width",outputSize[0]);result.put("height",outputSize[1]);result.put("reservedTextureBytes",reservation);
            result.put("releaseIncomplete",releaseIncomplete);
            result.put("globalTextureBudgetBytes",GLOBAL_TEXTURE_BUDGET);result.put("verification","source API review only; device verification deferred");}catch(Exception ignored){}return result;
    }
    private void fail(String detail) {
        synchronized(this){if(failed||closed.get())return;failed=true;ready=false;}
        main.removeCallbacks(watchdog);
        if(decode!=null)decode.cancel(true);
        main.post(()->{if(!closed.get()&&listener!=null)listener.onError(detail);});
        submit(()->{releasePipeline();detachSurface();});
    }
    private void detachSurface() {
        ready=false;lastRequestedUs=-1;pendingPresentationSinceMs=0;
        if(processor!=null)processor.setOutputSurfaceInfo(null);
        if(surface!=null){surface.release();surface=null;}
        if(texture!=null){texture.release();texture=null;}
    }
    private void releasePipeline() {
        boolean released=processor==null;
        if(processor!=null){try{processor.setOutputSurfaceInfo(null);processor.release();released=true;}catch(Exception ignored){releaseIncomplete=true;}processor=null;}
        streamRegistered=false;frameBusy=false;
        if(pendingBitmap!=null){pendingBitmap.recycle();pendingBitmap=null;}
        if(queuedBitmap!=null&&released&&!queuedBitmap.isRecycled())queuedBitmap.recycle();queuedBitmap=null;
        // A timed-out GL release still consumes a reservation; admitting more
        // renderers could otherwise make a failed driver leak memory repeatedly.
        synchronized(BUDGET_LOCK){if(reservation>0&&released&&!releaseIncomplete){reservedBytes-=reservation;reservedRenderers--;reservation=0;}}
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;ready=false;
        main.removeCallbacks(watchdog);
        // StreamingBitmapLoader publishes ownership through SettableFuture and
        // recycles cancelled allocations. Completion still handles a successful
        // result racing close, including rejected actor submissions.
        if(decode!=null)decode.cancel(true);
        submit(()->{releasePipeline();detachSurface();actor.shutdown();});
    }
    private void markDrawRequested(){lastDrawRequestedMs=android.os.SystemClock.elapsedRealtime();if(pendingPresentationSinceMs==0)pendingPresentationSinceMs=lastDrawRequestedMs;}
    private boolean submit(Runnable task){try{actor.execute(task);return true;}catch(RejectedExecutionException ignored){return false;}}
    private static String message(Throwable error){while(error.getCause()!=null)error=error.getCause();return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private static final class SingleTimestamp implements TimestampIterator {
        boolean pending=true;
        @Override public boolean hasNext(){return pending;}
        @Override public long next(){if(!pending)throw new NoSuchElementException();pending=false;return 0;}
        @Override public TimestampIterator copyOf(){return new SingleTimestamp();}
        @Override public long getLastTimestampUs(){return 0;}
    }
}
