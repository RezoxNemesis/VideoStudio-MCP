package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLivePreparedDrawingTest {
 @Test public void realSavedEventsAdoptWarmRendererSnapshotsAndPresentEveryStrokeInOrder() throws Exception {replaySavedStrokes(false);}
 @Test public void precisionPenSavedEventsPresentEveryFittedStrokeProgressivelyWithActualHand()throws Exception{replaySavedStrokes(true);}
 private void replaySavedStrokes(boolean precise)throws Exception{
  Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));
  SketchLiveDrawing.begin(context,new JSONObject().put("sketchId",id).put("operationId",session).put("expectedRevision",1).put("strokeDurationMs",80));
  JSONArray operations=new JSONArray();List<String> expected=new ArrayList<>();for(int i=0;i<16;i++){
   String objectId="curve_"+i;expected.add(objectId);JSONArray path=new JSONArray().put(new JSONArray().put("M").put(.1).put(.1));for(int n=0;n<80;n++)path.put(new JSONArray().put("C").put(.2).put(.3).put(.4).put(.5).put(.7).put(.8));
   if(precise)operations.put(new JSONObject().put("type","precision_contour").put("layerId","layer_1").put("objectId",objectId).put("points",new JSONArray("[[0.1,0.1],[0.2,0.3],[0.4,0.5],[0.7,0.8]]")).put("width",.005));
   else operations.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id",objectId).put("kind","path").put("commands",path)));
  }
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","curves").put("expectedRevision",1).put("operations",operations));
  SketchEditOps.execute(context,"sketch_live_end",new JSONObject().put("sketchId",id).put("sessionId",session).put("expectedRevision",2));
  AtomicInteger prefixes=new AtomicInteger(),hands=new AtomicInteger();List<String> presented=new ArrayList<>();AtomicReference<String> error=new AtomicReference<>();AtomicBoolean finished=new AtomicBoolean();SketchCanvasView canvas=new SketchCanvasView(context);
  SketchLivePlayer player=new SketchLivePlayer(context,SketchLiveDrawing.read(context,id,session),new SketchLivePlayer.Listener(){
   SketchLivePreparation.Prepared shown;
   public void drawing(JSONObject doc,String objectId,float fraction,String caption){error.set("Unprepared UI geometry path used");}
   public void preparedDrawing(SketchLivePreparation.Prepared prepared,String objectId,float fraction,String caption){try{
    if(shown!=prepared){Field rendererField=SketchLivePreparation.Prepared.class.getDeclaredField("renderer");rendererField.setAccessible(true);SketchFrameRenderer renderer=(SketchFrameRenderer)rendererField.get(prepared);Field paths=SketchFrameRenderer.class.getDeclaredField("geometryCache");paths.setAccessible(true);assertEquals(prepared.document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length(),((Map<?,?>)paths.get(renderer)).size());canvas.setPreparedDocument(prepared);shown=prepared;}
    canvas.setPresentation(objectId,fraction);if(objectId!=null&&fraction>0&&fraction<1)prefixes.incrementAndGet();if(fraction==1&&objectId!=null)presented.add(objectId);
   }catch(Exception failure){error.set(failure.toString());}}
   public void agentHand(SketchAgentHand.State state){if(state.visible&&state.drawing)hands.incrementAndGet();}
   public void committed(JSONObject doc){}public void idle(){}public void finished(long sequence){finished.set(true);}public void failed(String message){error.set(message);}
  });
  try{player.start();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(!finished.get()&&error.get()==null&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper(80,java.util.concurrent.TimeUnit.MILLISECONDS);Thread.sleep(2);}assertNull(error.get());assertTrue("Saved session must reach its actual end",finished.get());assertEquals(expected,presented);if(precise){assertTrue("Precision pen must publish real partial contours",prefixes.get()>0);assertTrue("Hand must follow actually drawn fitted paths",hands.get()>0);}}
  finally{player.close();canvas.dispose();}
 }
 @Test public void playbackHidesSelectionGizmosWithoutDiscardingSelectedPath() throws Exception {
  JSONObject document=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()).put("width",256).put("height",256).put("frameCount",1));JSONObject path=SketchEditOps.completeObject(new JSONObject().put("id","selected_path").put("kind","path").put("commands",new JSONArray().put(new JSONArray().put("M").put(.2).put(.2)).put(new JSONArray().put("L").put(.8).put(.8))));document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(path);
  SketchCanvasView view=new SketchCanvasView(RuntimeEnvironment.getApplication());view.setDocument(document);view.layout(0,0,256,256);view.selectObject("selected_path");android.graphics.Bitmap before=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888),during=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888);
  try{view.onDraw(new android.graphics.Canvas(before));view.setEnabled(false);view.onDraw(new android.graphics.Canvas(during));int shown=0,hidden=0;for(int y=0;y<256;y++)for(int x=0;x<256;x++){if(before.getPixel(x,y)==0xff2596ff)shown++;if(during.getPixel(x,y)==0xff2596ff)hidden++;}assertTrue(shown>0);assertEquals(0,hidden);assertEquals("selected_path",view.getSelectedId());}finally{before.recycle();during.recycle();view.dispose();}
 }
}
