package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAgentHandPlaybackTest {
    @Test public void agentHandOnlyFollowsAlreadyPublishedNativeStrokePixelsAndHidesAtCompletion() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();
        ExecutorService author=Executors.newSingleThreadExecutor();JSONObject baseline;
        try{baseline=author.submit(()->{
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","cel").put("expectedRevision",1)
                    .put("operations",new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("objectId","paint_cel").put("layerId","layer_1"))));
            SketchLiveDrawing.begin(context,new JSONObject().put("sketchId",id).put("operationId",session).put("expectedRevision",2).put("strokeDurationMs",80));
            JSONObject stroke=new JSONObject().put("type","paint_stroke").put("objectId","paint_cel").put("strokeId","visible_stroke")
                    .put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1)).put(new JSONArray().put(.9).put(.5).put(1)))
                    .put("brush",new JSONObject().put("mode","paint").put("color","#FFFF0000").put("sizePx",20).put("spacing",.2));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","stroke").put("expectedRevision",2).put("operations",new JSONArray().put(stroke)));
            SketchEditOps.execute(context,"sketch_live_end",new JSONObject().put("sketchId",id).put("sessionId",session).put("expectedRevision",3));
            return SketchLiveDrawing.read(context,id,session);
        }).get(15,TimeUnit.SECONDS);}finally{author.shutdownNow();}
        AtomicReference<Bitmap> shown=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();AtomicReference<SketchAgentHand.State> last=new AtomicReference<>();
        AtomicInteger handFrames=new AtomicInteger(),published=new AtomicInteger();AtomicBoolean finished=new AtomicBoolean();
        SketchLivePlayer player=new SketchLivePlayer(context,baseline,new SketchLivePlayer.Listener(){
            public void drawing(JSONObject document,String target,float fraction,String caption){error.set("Paint requires its actual prepared pixels");}
            public SketchPaintReplay.Viewport paintViewport(String objectId){return new SketchPaintReplay.Viewport(256,null);}
            public void preparedPaintDrawing(SketchLivePreparation.Prepared prepared,SketchPaintReplay.Frame frame,String objectId,float fraction,String caption){
                Bitmap old=shown.getAndSet(frame.bitmap.copy(Bitmap.Config.ARGB_8888,false));if(old!=null)old.recycle();published.incrementAndGet();frame.close();
            }
            public void agentHand(SketchAgentHand.State state){
                last.set(state);if(!state.visible||!state.drawing)return;
                assertTrue("Hand callback must follow the matching displayed prefix",published.get()>0);Bitmap bitmap=shown.get();assertNotNull(bitmap);
                int x=Math.max(0,Math.min(255,(int)Math.floor(state.x*256))),y=Math.max(0,Math.min(255,(int)Math.floor(state.y*256)));
                assertTrue("The actual cursor must touch pixels already present in this prefix",(bitmap.getPixel(x,y)>>>24)>0);
                assertEquals("paint_cel",state.objectId);assertEquals("Brush",state.toolLabel);handFrames.incrementAndGet();
            }
            public void committed(JSONObject document){}public void idle(){}public void finished(long sequence){finished.set(true);}public void failed(String message){error.set(message);}
        });
        try{player.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!finished.get()&&error.get()==null&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper(20,TimeUnit.MILLISECONDS);Thread.sleep(2);}
            assertNull(error.get());assertTrue(finished.get());assertTrue("Visible hand must follow multiple real brush prefixes",handFrames.get()>=3);
            assertNotNull(last.get());assertFalse("Finishing the saved session hides the active hand",last.get().visible);
            int count=handFrames.get();player.close();ShadowLooper.idleMainLooper(200,TimeUnit.MILLISECONDS);assertEquals("Closed playback cannot publish late drawing positions",count,handFrames.get());
        }finally{player.close();Bitmap bitmap=shown.get();if(bitmap!=null)bitmap.recycle();}
    }
}
