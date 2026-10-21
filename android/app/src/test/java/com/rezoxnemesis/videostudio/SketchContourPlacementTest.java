package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Reproduces the overlapping hair/face paint failure from the actual redraw. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchContourPlacementTest {
 interface Work {void run()throws Exception;}
 private void worker(Work work)throws Exception{ExecutorService e=Executors.newSingleThreadExecutor();try{e.submit(()->{try{work.run();return null;}catch(Exception x){throw new RuntimeException(x);}}).get(90,TimeUnit.SECONDS);}finally{e.shutdownNow();}}
 private JSONObject load(Context c,String id)throws Exception{try(SketchProjectStore s=new SketchProjectStore(c)){return s.get(id);}}
 private JSONObject edit(Context c,String id,String op,JSONArray ops)throws Exception{return SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId",op).put("expectedRevision",load(c,id).getLong("revision")).put("operations",ops));}
 private JSONObject path(String id,double inset)throws Exception{return new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id",id).put("kind","path").put("color","#FF111111").put("width",.002).put("fill",false).put("commands",new JSONArray().put(new JSONArray().put("M").put(inset).put(inset)).put(new JSONArray().put("L").put(1-inset).put(inset)).put(new JSONArray().put("L").put(1-inset).put(1-inset)).put(new JSONArray().put("L").put(inset).put(1-inset)).put(new JSONArray().put("Z"))));}
 private JSONObject fill(JSONObject d,String target,String contour,String color,String placement)throws Exception{JSONObject f=new JSONObject().put("type","paint_fill").put("objectId",target).put("strokeId","fill_"+target).put("contourObjectId",contour).put("expectedContourSha256",SketchContourPaint.pin(d,contour).getString("sha256")).put("color",color);if(placement!=null)f.put("placement",placement);return f;}
 private String fixture(Context c,String placement)throws Exception{
  String id="placement_"+java.util.UUID.randomUUID();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",1));
  edit(c,id,"hair",new JSONArray().put(path("hair",.1)).put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","hair_paint")));
  edit(c,id,"hair_fill",new JSONArray().put(fill(load(c,id),"hair_paint","hair","#FF706060",null)));
  edit(c,id,"face",new JSONArray().put(path("face",.3)).put(new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","face_paint")));
  edit(c,id,"face_fill",new JSONArray().put(fill(load(c,id),"face_paint","face","#FFFFE8CC",placement)));return id;
 }
 private int pixel(Context c,JSONObject d,int x,int y)throws Exception{try(SketchFrameRenderer r=new SketchFrameRenderer(c,d)){Bitmap b=r.render(0);try{return b.getPixel(x,y);}finally{b.recycle();}}}
 private JSONArray order(JSONObject d)throws Exception{JSONArray a=new JSONArray(),o=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<o.length();i++)a.put(o.getJSONObject(i).getString("id"));return a;}
 @Test public void faceFillStaysAboveEarlierHairAndBelowItsOwnContour()throws Exception{worker(()->{Context c=RuntimeEnvironment.getApplication();String id=fixture(c,"under_contour");JSONObject d=load(c,id);assertEquals("[\"hair_paint\",\"hair\",\"face_paint\",\"face\"]",order(d).toString());assertEquals(0xffffe8cc,pixel(c,d,128,128));assertEquals(0xff706060,pixel(c,d,40,40));});}
 @Test public void omittedAndExplicitLayerBackKeepLegacyComposition()throws Exception{worker(()->{Context c=RuntimeEnvironment.getApplication();for(String placement:new String[]{null,"layer_back"}){JSONObject d=load(c,fixture(c,placement));assertEquals("face_paint",order(d).getString(0));assertEquals(0xff706060,pixel(c,d,128,128));}});}
 @Test public void placementSurvivesReceiptRetryUndoRedoAndEditableArchive()throws Exception{worker(()->{
  Context c=RuntimeEnvironment.getApplication();String id=fixture(c,"under_contour");JSONObject filled=load(c,id);String expected=order(filled).toString(),paint=SketchDocument.object(filled,"face_paint").getJSONObject("paint").getString("sha256");
  JSONObject retry=new JSONObject().put("sketchId",id).put("operationId","face_fill").put("expectedRevision",4).put("operations",new JSONArray().put(fill(filled,"face_paint","face","#FFFFE8CC","under_contour")));JSONObject receipt=SketchEditOps.execute(c,"sketch_edit",retry);assertEquals(5,receipt.getLong("revision"));assertEquals(5,load(c,id).getLong("revision"));
  SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",5));assertEquals("face_paint",order(load(c,id)).getString(3));assertEquals(0xff706060,pixel(c,load(c,id),128,128));
  SketchEditOps.execute(c,"sketch_redo",new JSONObject().put("sketchId",id).put("operationId","redo").put("expectedRevision",6));assertEquals(expected,order(load(c,id)).toString());assertEquals(0xffffe8cc,pixel(c,load(c,id),128,128));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(c,new JSONObject().put("sketchId",id).put("expectedRevision",7).put("operationId","archive"));JSONObject restored=SketchPaintArchive.restoreFile(c,archive.file,SketchProjectStore.fileHash(archive.file),"restore","Overlap study");JSONObject d=load(c,restored.getString("sketchId"));assertEquals(expected,order(d).toString());assertEquals(paint,SketchDocument.object(d,"face_paint").getJSONObject("paint").getString("sha256"));assertEquals(0xffffe8cc,pixel(c,d,128,128));
  try(SketchPaintStore store=new SketchPaintStore(c,d.getString("id"))){JSONObject descriptor=SketchDocument.object(d,"face_paint").getJSONObject("paint");store.verify(descriptor);assertEquals("under_contour",store.journal(descriptor,0,8).getJSONObject(0).getJSONObject("request").getString("placement"));}
 });}
 @Test public void repaintPreservesExistingPartOrderWithoutMovingItsPaint()throws Exception{worker(()->{
  Context c=RuntimeEnvironment.getApplication();String id=fixture(c,"under_contour");JSONObject before=load(c,id);String order=order(before).toString();JSONObject f=fill(before,"face_paint","face","#FFCCEEFF","preserve").put("strokeId","repaint");edit(c,id,"repaint",new JSONArray().put(f));JSONObject after=load(c,id);assertEquals(order,order(after).toString());assertEquals(0xffcceeff,pixel(c,after,128,128));
 });}
 @Test public void paintedCelsCannotBeAutomaticallyRelocatedAndFailureKeepsSource()throws Exception{worker(()->{
  Context c=RuntimeEnvironment.getApplication();String id=fixture(c,"under_contour");edit(c,id,"other_contour",new JSONArray().put(path("other",.2)));JSONObject d=load(c,id);String before=SketchDocument.canonical(d);JSONObject f=fill(d,"face_paint","other","#FFFFFFFF","under_contour").put("strokeId","wrong_part");assertThrows(IllegalArgumentException.class,()->edit(c,id,"reject_move",new JSONArray().put(f)));assertEquals(before,SketchDocument.canonical(load(c,id)));
 });}
 @Test public void strictPlacementRejectsCoercionAndUnknownChoices()throws Exception{JSONObject f=new JSONObject().put("type","paint_fill").put("objectId","p").put("strokeId","s").put("contourObjectId","c").put("expectedContourSha256","a".repeat(64)).put("color","#FFFFFFFF");for(Object invalid:new Object[]{JSONObject.NULL,1,true,"front","",new JSONObject()}){f.put("placement",invalid);assertThrows(IllegalArgumentException.class,()->SketchContourPaint.request(f));}for(String valid:new String[]{"under_contour","layer_back","preserve"}){f.put("placement",valid);SketchContourPaint.request(f);}}
}
