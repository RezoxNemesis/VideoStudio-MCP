package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
/** Local clipping must preserve the original native stroke and the filled artwork below it. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchPathClipTest {
 final Context context=RuntimeEnvironment.getApplication();
 static JSONArray rings()throws Exception{return new JSONArray("[[[0.45,0.3],[0.65,0.3],[0.65,0.7],[0.45,0.7]]]");}
 static JSONObject stroke()throws Exception{return new JSONObject().put("id","ink").put("kind","path").put("frame",0).put("exposure",1).put("color","#FF383B40").put("width",.025).put("fill",false).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("commands",new JSONArray("[[\"M\",0.1,0.5],[\"C\",0.3,0.15,0.7,0.85,0.9,0.5]]"));}
 JSONObject document()throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","clip_"+UUID.randomUUID()).put("width",256).put("height",256).put("background","#00000000"));d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(stroke());return d;}
 Bitmap render(JSONObject d)throws Exception{try(SketchFrameRenderer r=new SketchFrameRenderer(context,d)){return r.render(0,false);}}
 JSONObject load(String id)throws Exception{try(SketchProjectStore s=new SketchProjectStore(context)){return s.get(id);}}
 void edit(String id,long revision,String op,JSONObject operation)throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",op).put("operations",new JSONArray().put(operation)));}
 @Test public void clippingKeepsEveryOriginalStrokePixelOutsideTheMaskExactAndDoesNotEraseTheTorso()throws Exception {
  JSONObject original=document(),clipped=SketchDocument.copy(original);SketchDocument.object(clipped,"ink").put("clipOut",rings());Bitmap a=render(original),b=render(clipped);
  try{int removed=0;for(int y=0;y<256;y++)for(int x=0;x<256;x++){if(x<114||x>167||y<75||y>181)assertEquals("Native full-path rasterization must survive outside the local clip at "+x+","+y,a.getPixel(x,y),b.getPixel(x,y));if(x>=117&&x<=164&&y>=78&&y<=177){assertEquals(0,b.getPixel(x,y));if((a.getPixel(x,y)>>>24)>128)removed++;}}assertTrue(removed>50);}finally{a.recycle();b.recycle();}
  JSONObject torso=stroke().put("id","torso").put("fill",true).put("color","#FFFF0000").put("commands",new JSONArray("[[\"M\",0,0],[\"L\",1,0],[\"L\",1,1],[\"L\",0,1],[\"Z\"]]"));JSONArray objects=clipped.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");objects.put(0,torso);objects.put(SketchDocument.object(original,"ink").put("clipOut",rings()));Bitmap composed=render(clipped);try{assertEquals("Clipping one outline must retain the filled layer below",Color.RED,composed.getPixel(128,128));}finally{composed.recycle();}
 }
 @Test public void objectAndLayerTransformsMoveTheClipAndHiddenPixelsCannotBePicked()throws Exception {
  JSONObject d=document(),ink=SketchDocument.object(d,"ink");ink.put("clipOut",rings());ink.getJSONObject("transform").put("rotation",31);JSONObject layer=d.getJSONArray("layers").getJSONObject(0);layer.getJSONObject("transform").put("x",.06).put("y",.04);
  Matrix combined=new Matrix();combined.setConcat(SketchFrameRenderer.matrix(layer.getJSONObject("transform"),256,256),SketchFrameRenderer.matrix(ink.getJSONObject("transform"),256,256));float[] hidden={128,128},visible={25.6f,128};combined.mapPoints(hidden);combined.mapPoints(visible);
  assertNull("A clipped-away centerline must not intercept a tap",SketchFrameRenderer.hitTest(d,0,hidden[0]/256,hidden[1]/256,0));assertEquals("ink",SketchFrameRenderer.hitTest(d,0,visible[0]/256,visible[1]/256,.005));
  Bitmap bitmap=render(d);try{assertEquals(0,bitmap.getPixel(Math.round(hidden[0]),Math.round(hidden[1])));}finally{bitmap.recycle();}
 }
 @Test public void invalidOrAmbiguousClipRecipesRejectBeforeMutation()throws Exception {
  JSONObject valid=document();SketchDocument.object(valid,"ink").put("clipOut",rings());SketchDocument.validate(valid);
  Object[] invalid={JSONObject.NULL,"[]",new JSONArray(),new JSONArray("[[[0,0],[1,1]]]") ,new JSONArray("[[[0,0],[0.5,0.5],[1,1]]]") ,new JSONArray("[[[0,0],[1,1],[1,0],[0,1]]]") ,new JSONArray("[[[-0.01,0],[1,0],[1,1]]]") ,new JSONArray("[[[0,0],[\"1\",0],[1,1]]]")};
  for(Object bad:invalid){JSONObject d=document();SketchDocument.object(d,"ink").put("clipOut",bad);assertThrows("Reject malformed or degenerate clipping",Exception.class,()->SketchDocument.validate(d));}
  JSONObject nonPath=document();JSONObject o=SketchDocument.object(nonPath,"ink");o.put("kind","polygon").remove("commands");o.put("points",new JSONArray("[[0,0,1],[1,0,1],[1,1,1]]")).put("clipOut",rings());assertThrows(Exception.class,()->SketchDocument.validate(nonPath));
  JSONObject animated=document();o=SketchDocument.object(animated,"ink");o.put("clipOut",rings()).put("shapeKeys",new JSONArray().put(new JSONObject().put("frame",0).put("commands",o.getJSONArray("commands")).put("interpolation","linear")));assertThrows("A static clip must not silently drift from animated geometry",Exception.class,()->SketchDocument.validate(animated));
 }
 @Test public void ordinaryEditsUndoReplayReopenComponentsAndLocalArchivesRetainTheClip()throws Exception {
  JSONObject d=document();String id=d.getString("id");try(SketchProjectStore s=new SketchProjectStore(context)){s.importOwnedDocument(d,"original");}
  JSONObject patch=new JSONObject().put("type","update_object").put("objectId","ink").put("patch",new JSONObject().put("clipOut",rings()));edit(id,1,"mask",patch);edit(id,1,"mask",patch);assertEquals(rings().toString(),SketchDocument.object(load(id),"ink").getJSONArray("clipOut").toString());
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","undo"));assertFalse(SketchDocument.object(load(id),"ink").has("clipOut"));SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","redo"));
  String component="component_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",id).put("expectedRevision",4).put("operationId","save").put("name","Clipped native ink").put("category","effect").put("frameIndex",0));
  JSONObject target=document();target.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray());try(SketchProjectStore s=new SketchProjectStore(context)){s.importOwnedDocument(target,"target");}SketchComponentLibrary.execute(context,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target.getString("id")).put("expectedRevision",1).put("operationId","apply").put("frameIndex",0));assertEquals(rings().toString(),load(target.getString("id")).getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0).getJSONArray("clipOut").toString());
  java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();SketchPaintArchive.Prepared archive;try{archive=worker.submit(()->SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","archive"))).get();}finally{worker.shutdownNow();}
  Uri uri=Uri.parse("content://owned.documents/clipped_"+UUID.randomUUID()+".vssketch");Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,new FileInputStream(archive.file));JSONObject restored=SketchLocalArchive.restore(context,uri,"restore_"+UUID.randomUUID(),null);JSONObject restoredDoc=load(restored.getString("sketchId"));assertEquals(rings().toString(),SketchDocument.object(restoredDoc,"ink").getJSONArray("clipOut").toString());Bitmap a=render(load(id)),b=render(restoredDoc);try{assertTrue(a.sameAs(b));}finally{a.recycle();b.recycle();}
  edit(id,4,"clear",new JSONObject().put("type","update_object").put("objectId","ink").put("patch",new JSONObject().put("clipOut",JSONObject.NULL)));assertFalse(SketchDocument.object(load(id),"ink").has("clipOut"));
 }
}
