package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.view.Surface;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.Player;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.ui.PlayerView;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/**
 * UNIT ONLY: the real SDK wrapper leases/releases a public VideoGraph.Factory at
 * the earliest valid playback-thread initialization during PlayerView binding.
 * The graph is a quota-counted protocol fixture; no GL, codec, pixels or Android
 * playback/export fidelity is claimed. Only source loading in handlePrepare is
 * suppressed; setComposition, graph-wrapper initialization and release stay real.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows={
        LiveEditPlayerGraphQuotaTest.QuotaBuilderShadow.class,
        LiveEditPlayerGraphQuotaTest.NoSourceLoadingShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class LiveEditPlayerGraphQuotaTest {
    private static final Format SDR=new Format.Builder().setSampleMimeType("video/avc")
            .setWidth(2).setHeight(2).setFrameRate(24).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();
    private QuotaFactory quota;

    @Before public void resetQuota(){quota=new QuotaFactory();QuotaBuilderShadow.factory=quota;}

    @Test public void thirdCandidateRealizesItsSdkGraphWithoutAThirdLiveLease()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        EagerBindingView binding=bind(live);ProjectStore.Project project=ownedStill();
        try{
            Player a=admit(live,project,1000,false,null,null);
            Player b=admit(live,project,1250,true,null,null);
            assertEquals("A and B must really hold two graph leases before the race",2,quota.live());
            assertEquals(2,quota.attempts());assertFalse(quota.graph(0).released);assertFalse(quota.graph(1).released);
            project.updatedAt++;String before=SketchDocument.canonical(project.toJson());
            live.playEdit(project,new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                    "9:16","720p",1500,false,null,null);
            Player c=binding.getPlayer();
            assertNotSame(b,c);assertSame(c,field(live,"editPlayer"));
            assertSame(b,field(live,"fallbackPlayer"));
            assertEquals("Count actual SDK Factory.create calls, not builder calls",3,quota.attempts());
            assertEquals("Retire obsolete A before C can initialize",2,quota.maximumLive());
            assertTrue(quota.graph(0).released);assertEquals(1,quota.graph(0).releaseCalls);
            assertFalse(quota.graph(1).released);assertFalse(quota.graph(2).released);
            assertEquals(2,quota.live());assertEquals(1500,live.currentPositionMs());
            assertFalse(c.getPlayWhenReady());assertFalse(b.getPlayWhenReady());
            assertEquals(before,SketchDocument.canonical(project.toJson()));
            assertNotSame(a,c);
        }finally{live.release();}
        assertEquals("Normal SDK release must close every retained protocol graph",0,quota.live());
        for(QuotaGraph graph:quota.graphs())assertEquals(1,graph.releaseCalls);
    }

    @Test public void candidateBindingFailureReleasesItsRealLeaseAndPreservesActiveB()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        EagerBindingView binding=bind(live);ProjectStore.Project project=ownedStill();
        ArrayList<String> aErrors=new ArrayList<>(),bErrors=new ArrayList<>(),cErrors=new ArrayList<>();
        try{
            admit(live,project,1000,false,()->{},aErrors::add);
            Player b=admit(live,project,1250,true,()->{},bErrors::add);
            live.setContextIds("accepted-b","clip-b");
            Object watcher=field(live,"clipBoundaryWatcher"),callback=field(live,"editError");
            assertEquals(2,quota.live());binding.failAfterInitialization=true;
            project.updatedAt++;String before=SketchDocument.canonical(project.toJson());
            BindingFailure failure=assertThrows(BindingFailure.class,()->live.playEdit(project,
                    new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                    "9:16","720p",2000,false,()->{},cErrors::add));
            assertEquals("planned binding failure after actual graph initialization",failure.getMessage());
            assertTrue("Reach the intended fault, rather than failing the graph quota",binding.intendedFaultReached);
            assertEquals(3,quota.attempts());assertEquals(2,quota.maximumLive());
            assertTrue("Obsolete A has been retired",quota.graph(0).released);
            assertFalse("The previously active B remains usable",quota.graph(1).released);
            assertTrue("Failed candidate C closes the actual SDK-owned graph",quota.graph(2).released);
            assertEquals(1,quota.graph(2).releaseCalls);assertEquals(1,quota.live());
            assertSame(b,binding.getPlayer());assertSame(b,field(live,"editPlayer"));
            assertNull(field(live,"fallbackPlayer"));assertTrue(live.isEditedPreview());
            assertEquals(1250,live.currentPositionMs());assertTrue(b.getPlayWhenReady());
            assertEquals("accepted-b",live.snapshotState().snapshotId);assertEquals("clip-b",live.snapshotState().clipId);
            assertSame(watcher,field(live,"clipBoundaryWatcher"));assertSame(callback,field(live,"editError"));
            assertTrue(aErrors.isEmpty());assertTrue(bErrors.isEmpty());assertTrue(cErrors.isEmpty());
            assertEquals(before,SketchDocument.canonical(project.toJson()));
        }finally{live.release();}
        assertEquals(0,quota.live());for(QuotaGraph graph:quota.graphs())assertEquals(1,graph.releaseCalls);
    }

    private EagerBindingView bind(LiveEditPlayer live)throws Exception{
        EagerBindingView binding=new EagerBindingView(RuntimeEnvironment.getApplication());
        binding.setPlayer((Player)field(live,"player"));
        Field view=LiveEditPlayer.class.getDeclaredField("view");view.setAccessible(true);view.set(live,binding);
        return binding;
    }

    private static Player admit(LiveEditPlayer live,ProjectStore.Project project,long at,boolean ready,
            Runnable done,java.util.function.Consumer<String> error)throws Exception{
        project.updatedAt++;
        live.playEdit(project,new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                "9:16","720p",at,ready,done,error);
        return (Player)field(live,"editPlayer");
    }

    private static ProjectStore.Project ownedStill()throws Exception{
        Context context=RuntimeEnvironment.getApplication();File file=File.createTempFile("quota-still-",".png",context.getFilesDir());
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
        try(FileOutputStream output=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
        finally{bitmap.recycle();}
        ProjectStore.Project project=new ProjectStore.Project();project.id="graph-quota";
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="still";asset.mime="image/png";asset.uri=Uri.fromFile(file).toString();
        project.assets.add(asset);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="still-clip";clip.assetId=asset.id;clip.outMs=4000;project.clips.add(clip);
        return project;
    }

    /** Only observing private wrapper identity; all initialization/release calls are public SDK APIs. */
    private static Object field(Object object,String name)throws Exception{
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }

    private static final class BindingFailure extends RuntimeException {
        BindingFailure(){super("planned binding failure after actual graph initialization");}
    }

    private static final class EagerBindingView extends PlayerView {
        final Set<CompositionPlayer> initialized=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean failAfterInitialization,intendedFaultReached;
        EagerBindingView(Context context){super(context);}
        @Override public void setPlayer(Player player){
            super.setPlayer(player);
            if(!(player instanceof CompositionPlayer)||!initialized.add((CompositionPlayer)player))return;
            CompositionPlayer candidate=(CompositionPlayer)player;
            CountDownLatch completed=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Handler playback=new Handler(candidate.getPlaybackLooper());
            assertTrue("The public playback looper must accept the controlled initialization",playback.post(()->{
                try{
                    PlaybackVideoGraphWrapper wrapper=(PlaybackVideoGraphWrapper)field(candidate,"playbackVideoGraphWrapper");
                    assertNotNull(wrapper);wrapper.getSink(0).initialize(SDR);
                }catch(Throwable error){failure.set(error);}finally{completed.countDown();}
            }));
            // Drain only the current playback-thread turn, never main queued loads or delayed watchers.
            Shadows.shadowOf(candidate.getPlaybackLooper()).idle();
            try{assertTrue("Bounded playback initialization barrier",completed.await(5,TimeUnit.SECONDS));}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            if(failure.get()!=null)throw new IllegalStateException("SDK primary graph initialization failed",failure.get());
            if(failAfterInitialization){intendedFaultReached=true;throw new BindingFailure();}
        }
    }

    @Implements(value=CompositionPlayer.Builder.class,isInAndroidSdk=false)
    public static class QuotaBuilderShadow {
        @RealObject private CompositionPlayer.Builder builder;
        static QuotaFactory factory;
        @Implementation public CompositionPlayer build(){
            builder.setVideoGraphFactory(factory);
            return Shadow.directlyOn(builder,CompositionPlayer.Builder.class,"build");
        }
    }

    /** No source I/O: the actual setComposition graph/wrapper lifecycle is deliberately retained. */
    @Implements(value=CompositionPlayer.class,isInAndroidSdk=false)
    public static class NoSourceLoadingShadow {
        @Implementation protected ListenableFuture<?> handlePrepare(){return Futures.immediateVoidFuture();}
    }

    private static final class QuotaFactory implements VideoGraph.Factory {
        private final ArrayList<QuotaGraph> graphs=new ArrayList<>();
        private int attempts,live,maximumLive;
        @Override public synchronized VideoGraph create(Context context,ColorInfo color,DebugViewProvider debug,
                VideoGraph.Listener listener,Executor executor,long initialOffsetUs,boolean automatic){
            attempts++;
            if(live>=2)throw new IllegalStateException("controlled maximum of two live graph leases");
            QuotaGraph graph=new QuotaGraph(this);graphs.add(graph);live++;maximumLive=Math.max(maximumLive,live);return graph;
        }
        @Override public boolean supportsMultipleInputs(){return false;}
        synchronized int attempts(){return attempts;}synchronized int live(){return live;}
        synchronized int maximumLive(){return maximumLive;}synchronized QuotaGraph graph(int index){return graphs.get(index);}
        synchronized List<QuotaGraph> graphs(){return new ArrayList<>(graphs);}
        synchronized void released(QuotaGraph graph){if(!graph.released){graph.released=true;live--;}}
    }

    /** Protocol-only resource token. Initialization has no native GL/codec/image rendering. */
    private static final class QuotaGraph implements VideoGraph {
        final QuotaFactory owner;volatile boolean released;volatile int releaseCalls;
        SurfaceTexture texture;Surface surface;
        QuotaGraph(QuotaFactory owner){this.owner=owner;}
        @Override public void initialize(){}
        @Override public void registerInput(int input){}
        @Override public void setOutputSurfaceInfo(SurfaceInfo output){}
        @Override public void setOnInputSurfaceReadyListener(int input,Runnable ready){}
        @Override public Surface getInputSurface(int input){
            if(surface==null){texture=new SurfaceTexture(0);surface=new Surface(texture);}return surface;
        }
        @Override public void setOnInputFrameProcessedListener(int input,OnInputFrameProcessedListener listener){}
        @Override public void setCompositionEffects(List<Effect> effects){}
        @Override public void setCompositorSettings(VideoCompositorSettings settings){}
        @Override public void registerInputStream(int input,int type,Format format,List<Effect> effects,long offset){}
        @Override public int getPendingInputFrameCount(int input){return 0;}
        @Override public boolean registerInputFrame(int input){return !released;}
        @Override public boolean queueInputBitmap(int input,Bitmap bitmap,TimestampIterator times){return !released;}
        @Override public boolean queueInputTexture(int input,int texture,long time){return !released;}
        @Override public void renderOutputFrame(long timeNs){}
        @Override public void redraw(){}
        @Override public boolean hasProducedFrameWithTimestampZero(){return false;}
        @Override public void flush(){}
        @Override public void signalEndOfInput(int input){}
        @Override public void release(){releaseCalls++;owner.released(this);if(surface!=null)surface.release();if(texture!=null)texture.release();}
    }
}
