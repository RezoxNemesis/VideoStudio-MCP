package com.rezoxnemesis.videostudio;

import android.net.Uri;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LiveEditPlayerRecoveryTest {
    @Test public void rejectedEditRetainsSourceViewPositionAndPlaybackIntent()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            ExoPlayer source=(ExoPlayer)field(live,"player");
            source.setMediaItem(MediaItem.fromUri(Uri.parse("file:///previous-source.mp4")),1250);
            source.setPlayWhenReady(true);live.setContextIds("snapshot","old-clip");
            FrameLayout host=new FrameLayout(RuntimeEnvironment.getApplication());live.attach(host);
            PlayerView view=(PlayerView)host.getChildAt(0);
            ProjectStore.Project unknown=NativePreviewAdmissionTest.project();unknown.assets.get(0).durationMs=0;
            Exception error=assertThrows(Exception.class,()->live.playEdit(unknown,new NativeRenderEngine(RuntimeEnvironment.getApplication()),"9:16","720p",0,true,null,null));
            assertSame("Failed candidate must not replace the working view",source,view.getPlayer());
            assertFalse(live.isEditedPreview());assertTrue(source.getPlayWhenReady());
            assertEquals(1250,live.currentPositionMs());
            assertEquals("snapshot",live.snapshotState().snapshotId);
            assertTrue("Unknown duration needs actionable feedback",String.valueOf(error.getMessage()).toLowerCase().contains("duration"));
        }finally{live.release();}
    }
    @Test public void asynchronousNewEditFailureCannotRevealAnUnrelatedSourceFrame()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            ExoPlayer source=(ExoPlayer)field(live,"player");
            source.setMediaItem(MediaItem.fromUri("file:///unrelated-previous-project.mp4"),1000);
            source.setPlayWhenReady(true);
            FrameLayout host=new FrameLayout(RuntimeEnvironment.getApplication());
            live.attach(host);
            PlayerView view=(PlayerView)host.getChildAt(0);
            ProjectStore.Project current=NativePreviewAdmissionTest.project();
            java.util.ArrayList<String> errors=new java.util.ArrayList<>();
            live.playEdit(current,new NativeRenderEngine(RuntimeEnvironment.getApplication()),
                    "9:16","720p",0,true,null,errors::add);
            Player candidate=(Player)field(live,"editPlayer");
            assertSame(candidate,view.getPlayer());
            failPlayback(candidate);
            assertNull("Never show stale prior-project footage as this project's failed edit",
                    view.getPlayer());
            assertFalse(live.isEditedPreview());
            assertFalse(source.getPlayWhenReady());
            assertNull(field(live,"editPlayer"));
            assertEquals(1,errors.size());
            assertTrue(errors.get(0).contains("Edited preview unavailable"));
        }finally{live.release();}
    }

    @Test public void asynchronousFailureRestoresPreviousEditedPlayerAndItsClock()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            ProjectStore.Project p=NativePreviewAdmissionTest.project();
            live.playEdit(p,new NativeRenderEngine(RuntimeEnvironment.getApplication()),"9:16","720p",1250,false,null,null);
            Player previous=(Player)field(live,"editPlayer");live.setContextIds("accepted","old-clip");
            p.updatedAt++;
            java.util.ArrayList<String> errors=new java.util.ArrayList<>();
            live.playEdit(p,new NativeRenderEngine(RuntimeEnvironment.getApplication()),"9:16","720p",2000,true,null,errors::add);
            Player failed=(Player)field(live,"editPlayer");assertNotSame(previous,failed);
            failPlayback(failed);
            assertSame(previous,field(live,"editPlayer"));assertTrue(live.isEditedPreview());
            assertEquals(1250,live.currentPositionMs());assertFalse(live.snapshotState().playWhenReady);
            assertEquals("accepted",live.snapshotState().snapshotId);
            assertEquals(1,errors.size());assertTrue(errors.get(0).contains("Retry"));
        }finally{live.release();}
    }

    @Test public void thirdCandidateReleasesOldestAndSourceSwitchReleasesRemainingEdits()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            ProjectStore.Project p=NativePreviewAdmissionTest.project();
            Player a=admit(live,p,1000,null,null),b=admit(live,p,1250,null,null);
            assertFalse(released(a));assertFalse(released(b));
            Player c=admit(live,p,1500,null,null);
            assertEquals(android.os.Looper.getMainLooper(),c.getApplicationLooper());
            assertTrue("Third candidate must release the oldest fallback",released(a));
            assertSame(b,field(live,"fallbackPlayer"));assertFalse(released(b));assertFalse(released(c));
            live.setPlaylist(java.util.Collections.emptyList());
            assertTrue(released(b));assertTrue(released(c));
            assertNull(field(live,"fallbackPlayer"));assertNull(field(live,"editPlayer"));
        }finally{live.release();}
    }

    @Test public void directPlayerViewPausePreventsRollbackFromResumingPreviousEdit()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            FrameLayout host=new FrameLayout(RuntimeEnvironment.getApplication());live.attach(host);
            PlayerView view=(PlayerView)host.getChildAt(0);
            ProjectStore.Project p=NativePreviewAdmissionTest.project();
            NativeRenderEngine engine=new NativeRenderEngine(RuntimeEnvironment.getApplication());
            live.playEdit(p,engine,"9:16","720p",1000,true,null,null);
            Player a=view.getPlayer();assertTrue(a.getPlayWhenReady());p.updatedAt++;
            live.playEdit(p,engine,"9:16","720p",1250,true,null,null);
            Player b=view.getPlayer();assertNotSame(a,b);assertTrue(b.getPlayWhenReady());
            // PlayerView's controller calls Player directly, bypassing LiveEditPlayer.pause().
            view.getPlayer().pause();assertFalse(b.getPlayWhenReady());failPlayback(b);
            assertSame(a,view.getPlayer());
            assertFalse("A user's direct native-control pause must survive rollback",a.getPlayWhenReady());
        }finally{live.release();}
    }

    @Test public void rollbackRestoresPreviousErrorAndCompletionCallbacks()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            ProjectStore.Project p=NativePreviewAdmissionTest.project();
            java.util.ArrayList<String> aErrors=new java.util.ArrayList<>(),bErrors=new java.util.ArrayList<>();
            Player a=admit(live,p,1000,()->{},aErrors::add);live.setContextIds("accepted-a","clip-a");
            Runnable aWatcher=(Runnable)field(live,"clipBoundaryWatcher");
            Player b=admit(live,p,1250,()->{},bErrors::add);live.setContextIds("pending-b","clip-b");
            failPlayback(b);
            assertSame(a,field(live,"editPlayer"));
            assertSame("Rollback must resume the previous completion watcher",aWatcher,field(live,"clipBoundaryWatcher"));
            assertEquals("accepted-a",live.snapshotState().snapshotId);assertEquals("clip-a",live.snapshotState().clipId);
            assertEquals(1,bErrors.size());assertTrue(aErrors.isEmpty());
            // A's earlier fallback was discarded to keep retention bounded. A subsequent
            // failure must report through A's callback, without fabricating an older snapshot.
            failPlayback(a);
            assertEquals(1,aErrors.size());assertEquals(1,bErrors.size());
            assertEquals("accepted-a",live.snapshotState().snapshotId);assertEquals("clip-a",live.snapshotState().clipId);
        }finally{live.release();}
    }

    @Test public void sdkErrorExposedDuringViewBindingRejectsCandidateBeforeAdoption()throws Exception{
        LiveEditPlayer live=new LiveEditPlayer(RuntimeEnvironment.getApplication());
        try{
            ExoPlayer source=(ExoPlayer)field(live,"player");source.setMediaItem(MediaItem.fromUri("file:///previous-source.mp4"),1250);source.setPlayWhenReady(true);
            PlayerView binding=new PlayerView(RuntimeEnvironment.getApplication()){
                @Override public void setPlayer(Player player){
                    super.setPlayer(player);
                    if(player instanceof androidx.media3.transformer.CompositionPlayer){
                        try{
                            java.lang.reflect.Method error=player.getClass().getDeclaredMethod("maybeUpdatePlaybackError",String.class,Exception.class,int.class);error.setAccessible(true);
                            error.invoke(player,"view binding rejected source",new java.io.IOException("injected SDK boundary error"),PlaybackException.ERROR_CODE_DECODING_FAILED);
                        }catch(Exception error){throw new AssertionError(error);}
                    }
                }
            };
            binding.setPlayer(source);
            Field view=LiveEditPlayer.class.getDeclaredField("view");view.setAccessible(true);view.set(live,binding);
            ProjectStore.Project p=NativePreviewAdmissionTest.project();
            Exception failure=assertThrows(Exception.class,()->live.playEdit(p,new NativeRenderEngine(RuntimeEnvironment.getApplication()),"9:16","720p",0,true,null,null));
            assertTrue(failure.getMessage().contains("view binding rejected"));assertSame(source,binding.getPlayer());
            assertFalse(live.isEditedPreview());assertTrue(source.getPlayWhenReady());assertEquals(1250,live.currentPositionMs());
        }finally{live.release();}
    }

    private static Player admit(LiveEditPlayer live,ProjectStore.Project p,long at,Runnable done,java.util.function.Consumer<String> error)throws Exception{
        p.updatedAt++;
        live.playEdit(p,new NativeRenderEngine(RuntimeEnvironment.getApplication()),"9:16","720p",at,false,done,error);
        return (Player)field(live,"editPlayer");
    }
    private static boolean released(Player player)throws Exception{
        Field f=androidx.media3.common.SimpleBasePlayer.class.getDeclaredField("released");f.setAccessible(true);return f.getBoolean(player);
    }

    /** Inject only the SDK error event; exercise the actual registered product listener. */
    @SuppressWarnings("unchecked")
    static void failPlayback(Player player)throws Exception{
        Class<?> type=player.getClass();Field listeners=null;
        while(type!=null){try{listeners=type.getDeclaredField("listeners");break;}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}
        assertNotNull(listeners);listeners.setAccessible(true);
        ((ListenerSet<Player.Listener>)listeners.get(player)).sendEvent(Player.EVENT_PLAYER_ERROR,
                listener->listener.onPlayerError(new PlaybackException("decoder rejected source",null,PlaybackException.ERROR_CODE_DECODING_FAILED)));
    }
    private static Object field(Object object,String name)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
}
