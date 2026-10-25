package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/**
 * Real saved drawing operations, not UI-drawn animation substitutes.
 * Playback controls must preserve operation order and durable revisions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLiveReplayDeckTest {
    @Test public void pauseThenStepOneSavedStrokeThenResumeRemainingWork()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();
        String id="live_"+UUID.randomUUID(),session="s_"+UUID.randomUUID();
        SketchEditOps.execute(ctx,"sketch_create",new JSONObject().put("sketchId",id)
                .put("operationId","create").put("width",256).put("height",256).put("frameCount",1));
        SketchLiveDrawing.begin(ctx,new JSONObject().put("sketchId",id)
                .put("operationId",session).put("expectedRevision",1).put("strokeDurationMs",80));
        JSONArray operations=new JSONArray();
        for(int i=0;i<2;i++){
            operations.put(new JSONObject().put("type","add_object").put("layerId","layer_1")
                .put("object",new JSONObject().put("id","real_line_"+i)
                    .put("kind","line")
                    .put("points",new JSONArray()
                        .put(new JSONArray().put(.1).put(.2+i*.2).put(1))
                        .put(new JSONArray().put(.8).put(.2+i*.2).put(1)))));
        }
        SketchEditOps.execute(ctx,"sketch_edit",new JSONObject().put("sketchId",id)
                .put("operationId","two_strokes").put("expectedRevision",1)
                .put("operations",operations));
        SketchEditOps.execute(ctx,"sketch_live_end",new JSONObject().put("sketchId",id)
                .put("sessionId",session).put("expectedRevision",2));
        final long authoritative;
        try(SketchProjectStore store=new SketchProjectStore(ctx)) {
            authoritative=store.revision(id);
        }
        assertEquals(2,authoritative);

        AtomicInteger completedStrokes=new AtomicInteger();
        AtomicInteger pauses=new AtomicInteger();
        AtomicReference<String> failed=new AtomicReference<>();
        AtomicBoolean done=new AtomicBoolean();
        SketchLivePlayer replay=new SketchLivePlayer(ctx,
                SketchLiveDrawing.read(ctx,id,session),new SketchLivePlayer.Listener(){
            public void drawing(JSONObject document,String objectId,float fraction,String caption){}
            public void preparedDrawing(SketchLivePreparation.Prepared prepared,
                                        String objectId,float fraction,String caption) {
                if(objectId!=null&&fraction>=1)completedStrokes.incrementAndGet();
            }
            public void replayState(boolean paused,float speed,boolean step){
                if(paused)pauses.incrementAndGet();
            }
            public void committed(JSONObject document){}
            public void idle(){}
            public void finished(long sequence){done.set(true);}
            public void failed(String reason){failed.set(reason);}
        });
        try{
            replay.setPlaybackSpeed(.25f);
            replay.start();
            replay.pausePlayback();
            assertTrue(replay.isPaused());
            ShadowLooper.idleMainLooper(180,TimeUnit.MILLISECONDS);
            assertEquals("A paused watch cannot reveal future strokes",0,completedStrokes.get());
            replay.nextOperation();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(!replay.isPaused()&&failed.get()==null&&System.nanoTime()<deadline){
                ShadowLooper.idleMainLooper(40,TimeUnit.MILLISECONDS);
                Thread.sleep(3);
            }
            assertNull(failed.get());
            assertTrue("Step should return to paused after one accepted real operation",replay.isPaused());
            assertEquals(1,completedStrokes.get());
            assertFalse(done.get());
            replay.resumePlayback();
            while(!done.get()&&failed.get()==null&&System.nanoTime()<deadline){
                ShadowLooper.idleMainLooper(40,TimeUnit.MILLISECONDS);
                Thread.sleep(3);
            }
            assertNull(failed.get());
            assertTrue("Resuming should reach the original complete live journal",done.get());
            assertEquals(2,completedStrokes.get());
            assertTrue(pauses.get()>0);
            try(SketchProjectStore store=new SketchProjectStore(ctx)) {
                assertEquals("Viewing controls must never write additional cels",authoritative,store.revision(id));
            }
        }finally{replay.close();}
    }
}
