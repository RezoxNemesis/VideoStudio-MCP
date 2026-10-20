package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchPaintSelectionDepthTest {
 @Test public void ordinaryPaintPaddingDoesNotInterceptAnotherPartAndDepthChangesUndoNormally()throws Exception {
  java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();try{worker.submit(()->{try{
   android.content.Context c=RuntimeEnvironment.getApplication();String id="paint_depth_"+java.util.UUID.randomUUID();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));SketchPaintMeshTest.edit(c,id,1,"parts",new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","back")).put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","front")));
   JSONObject stroke=new JSONObject().put("type","paint_stroke").put("objectId","back").put("strokeId","red_part").put("points",new JSONArray("[[.2,.3,1],[.5,.3,1]]")).put("brush",new JSONObject().put("mode","paint").put("color","#FFFF0000").put("sizePx",40));SketchPaintMeshTest.edit(c,id,2,"red",new JSONArray().put(stroke));JSONObject d=SketchPaintMeshTest.load(c,id);
   java.util.Map<String,SketchFrameRenderer.ImageSelection> masks;try(SketchFrameRenderer r=new SketchFrameRenderer(c,d)){r.enableImageSelections();Bitmap b=r.render(0,false,128,null);b.recycle();masks=r.imageSelections();assertEquals("back",SketchFrameRenderer.hitTest(d,0,.3,.3,.001,masks));assertNull(SketchFrameRenderer.hitTest(d,0,.9,.9,.001,masks));}
   stroke.put("objectId","front").put("strokeId","blue_part").put("points",new JSONArray("[[.4,.3,1]]"));stroke.getJSONObject("brush").put("color","#FF0000FF");SketchPaintMeshTest.edit(c,id,3,"blue",new JSONArray().put(stroke));JSONObject before=SketchPaintMeshTest.load(c,id);String blueHash=SketchDocument.object(before,"front").getJSONObject("paint").getString("sha256");
   try(SketchFrameRenderer r=new SketchFrameRenderer(c,before)){r.reuseSelections(masks);r.enableImageSelections();Bitmap b=r.render(0,false);try{assertEquals(Color.BLUE,b.getPixel(102,77));}finally{b.recycle();}assertEquals("front",SketchFrameRenderer.hitTest(before,0,.4,.3,.001,r.imageSelections()));masks=r.imageSelections();}
   JSONObject reorder=new JSONObject().put("type","reorder_object").put("objectId","front").put("index",0);SketchPaintMeshTest.edit(c,id,4,"depth",new JSONArray().put(reorder));JSONObject after=SketchPaintMeshTest.load(c,id);assertEquals(blueHash,SketchDocument.object(after,"front").getJSONObject("paint").getString("sha256"));assertEquals("front",after.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0).getString("id"));try(SketchFrameRenderer r=new SketchFrameRenderer(c,after)){r.reuseSelections(masks);assertEquals(2,r.imageSelections().size());r.enableImageSelections();Bitmap b=r.render(0,false);try{assertEquals(Color.RED,b.getPixel(102,77));}finally{b.recycle();}assertEquals("back",SketchFrameRenderer.hitTest(after,0,.4,.3,.001,r.imageSelections()));}
   SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",5).put("operationId","undo"));assertEquals("back",SketchPaintMeshTest.load(c,id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0).getString("id"));assertEquals(blueHash,SketchDocument.object(SketchPaintMeshTest.load(c,id),"front").getJSONObject("paint").getString("sha256"));
   JSONObject invalid=SketchDocument.copy(after);assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(invalid,new JSONObject().put("type","reorder_object").put("objectId","front").put("index",2)));return null;
  }catch(Exception failure){throw new RuntimeException(failure);}}).get(90,java.util.concurrent.TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
}
