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
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLayeredDrawingPersistenceTest {
 final Context context=RuntimeEnvironment.getApplication();final ExecutorService worker=Executors.newSingleThreadExecutor();
 @After public void stop(){worker.shutdownNow();}
 private <T>T run(Callable<T> action)throws Exception{try{return worker.submit(action).get(120,TimeUnit.SECONDS);}catch(ExecutionException e){Throwable t=e.getCause();if(t instanceof Exception)throw(Exception)t;if(t instanceof Error)throw(Error)t;throw new RuntimeException(t);}}
 private final class Fixture {
  final String id="layered_"+UUID.randomUUID();int serial;JSONObject baseline;
  Fixture()throws Exception{File png=new File(context.getCacheDir(),id+".png");Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);try{bitmap.eraseColor(Color.RED);try(FileOutputStream out=new FileOutputStream(png)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{bitmap.recycle();}
   ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Read-only source guide");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(png),png.getName(),"image/png",0,png.length());
   call("sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",2).put("background","#00000000"));
   call("sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","source").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide"));baseline=doc();
  }
  JSONObject call(String action,JSONObject args)throws Exception{return SketchEditOps.execute(context,action,args);}
  JSONObject doc()throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
  JSONObject request(JSONObject op)throws Exception{return new JSONObject().put("sketchId",id).put("expectedRevision",doc().getLong("revision")).put("operationId","step_"+(serial++)).put("operations",new JSONArray().put(op));}
  JSONObject edit(JSONObject op)throws Exception{return call("sketch_edit",request(op));}
  void begin()throws Exception{edit(SketchLayeredDrawingTest.begin());}
  void review(String stage)throws Exception{edit(new JSONObject().put("type","layered_drawing_review").put("stage",stage).put("reviewedRevision",doc().getLong("revision")).put("status","ACCEPTABLE").put("note","Explicit review of this native stage preview."));}
  void history(boolean redo)throws Exception{call(redo?"sketch_redo":"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",doc().getLong("revision")).put("operationId","history_"+(serial++)));}
 }
 @Test public void watcherEndAndTakeoverCannotDropPolicyAndUndoBeginRedoUseGenuineHistory()throws Exception{run(()->{
  Fixture f=new Fixture();f.begin();JSONObject begun=f.doc();f.history(false);assertFalse(f.doc().has("layeredDrawing"));assertEquals(SketchDocument.canonical(f.baseline.getJSONArray("layers")),SketchDocument.canonical(f.doc().getJSONArray("layers")));f.history(true);assertEquals(SketchDocument.canonical(begun.getJSONObject("layeredDrawing")),SketchDocument.canonical(f.doc().getJSONObject("layeredDrawing")));
  f.call("sketch_live_begin",new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("operationId","watch").put("drawingMethod","original_strokes"));f.call("sketch_live_end",new JSONObject().put("sketchId",f.id).put("expectedRevision",f.doc().getLong("revision")).put("sessionId","watch"));try(SketchProjectStore store=new SketchProjectStore(context)){store.takeOverDrawing(f.id);}
  assertThrows(IllegalStateException.class,()->f.edit(new JSONObject().put("type","add_human_animation")));assertThrows(IllegalStateException.class,()->f.edit(new JSONObject().put("type","update_layer").put("layerId","layer_1").put("patch",new JSONObject().put("locked",false))));
  String before=SketchDocument.canonical(f.doc());try(SketchProjectStore store=new SketchProjectStore(context)){assertThrows(IllegalStateException.class,()->store.mutate(f.id,"strip",f.doc().getLong("revision"),"strip",d->{d.remove("layeredDrawing");return d;}));}assertEquals(before,SketchDocument.canonical(f.doc()));
  f.edit(SketchLayeredDrawingTest.stroke("actor_construction","authored"));f.history(false);assertEquals(0,SketchDocument.layer(f.doc(),"actor_construction").getJSONArray("objects").length());f.history(true);assertEquals(1,SketchDocument.layer(f.doc(),"actor_construction").getJSONArray("objects").length());return null;
 });}
 @Test public void explicitReviewHasDurableReplayAndNormalizedHashAcrossReopen()throws Exception{run(()->{
  Fixture f=new Fixture();f.begin();f.edit(SketchLayeredDrawingTest.stroke("actor_construction","authored"));JSONObject args=f.request(new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",f.doc().getLong("revision")).put("status","ACCEPTABLE").put("note","Reviewed actual native authored construction."));JSONObject result=f.call("sketch_edit",args);assertEquals(SketchDocument.canonical(result),SketchDocument.canonical(f.call("sketch_edit",args)));assertTrue(SketchLayeredDrawing.summary(f.doc()).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));
  f.edit(new JSONObject().put("type","layered_drawing_stage").put("stage","refinement"));f.edit(new JSONObject().put("type","layered_drawing_stage").put("stage","construction"));f.edit(new JSONObject().put("type","update_object").put("objectId","authored").put("patch",new JSONObject().put("width",.02)));assertFalse(SketchLayeredDrawing.summary(f.doc()).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));assertThrows(IllegalStateException.class,()->f.edit(new JSONObject().put("type","layered_drawing_stage").put("stage","refinement")));return null;
 });}
 @Test public void emptyPaintAndFullyErasedSnapshotsCannotAcquireAcceptance()throws Exception{run(()->{
  Fixture f=new Fixture();f.begin();f.edit(new JSONObject().put("type","add_paint_cel").put("layerId","actor_construction").put("objectId","paint").put("exposure",2));assertThrows(IllegalStateException.class,()->f.review("construction"));
  JSONObject brush=new JSONObject().put("mode","paint").put("color","#FF000000").put("sizePx",32).put("hardness",1).put("pressure",false);JSONArray points=new JSONArray("[[0.3,0.5,1],[0.7,0.5,1]]");f.edit(new JSONObject().put("type","paint_stroke").put("objectId","paint").put("strokeId","painted").put("points",points).put("brush",brush));f.review("construction");assertTrue(SketchLayeredDrawing.summary(f.doc()).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));
  brush.put("mode","erase").put("sizePx",128);f.edit(new JSONObject().put("type","paint_stroke").put("objectId","paint").put("strokeId","erased").put("points",points).put("brush",brush));assertFalse(SketchLayeredDrawing.summary(f.doc()).getJSONArray("stageReviews").getJSONObject(0).getBoolean("acceptable"));assertThrows(IllegalStateException.class,()->f.review("construction"));return null;
 });}
 @Test public void committedPaintReceiptReplaysAfterStageAdvancesWithoutPreparingAgain()throws Exception{run(()->{
  Fixture f=new Fixture();f.begin();f.edit(new JSONObject().put("type","add_paint_cel").put("layerId","actor_construction").put("objectId","paint").put("exposure",2));JSONObject brush=new JSONObject().put("mode","paint").put("color","#FF000000").put("sizePx",24).put("hardness",1).put("pressure",false);JSONObject args=f.request(new JSONObject().put("type","paint_stroke").put("objectId","paint").put("strokeId","durable").put("points",new JSONArray("[[0.3,0.5,1],[0.7,0.5,1]]")).put("brush",brush));JSONObject receipt=f.call("sketch_edit",args);f.review("construction");f.edit(new JSONObject().put("type","layered_drawing_stage").put("stage","refinement"));String before=SketchDocument.canonical(f.doc());assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(f.call("sketch_edit",args)));assertEquals(before,SketchDocument.canonical(f.doc()));return null;
 });}
 @Test public void reviewAtFrameOneAndSingleOperationControlTransactionsCannotAcceptUnpublishedMarks()throws Exception{run(()->{
  Fixture f=new Fixture();f.begin();JSONObject mark=SketchLayeredDrawingTest.stroke("actor_construction","frame_one");mark.getJSONObject("object").put("frame",1);f.edit(mark);assertThrows(IllegalStateException.class,()->f.review("construction"));f.edit(new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",f.doc().getLong("revision")).put("frameIndex",1).put("status","ACCEPTABLE").put("note","Explicitly reviewed frame two."));assertEquals(1,f.doc().getJSONObject("layeredDrawing").getJSONArray("reviews").getJSONObject(0).getInt("frameIndex"));
  JSONObject args=f.request(SketchLayeredDrawingTest.stroke("actor_construction","unpublished"));args.getJSONArray("operations").put(new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",f.doc().getLong("revision")).put("status","ACCEPTABLE").put("note","Cannot review unpublished changes."));String before=SketchDocument.canonical(f.doc());assertThrows(IllegalStateException.class,()->f.call("sketch_edit",args));assertEquals(before,SketchDocument.canonical(f.doc()));return null;
 });}
 @Test public void reviewedCleanPartsPersistThroughNativeArchiveAndStayEditable()throws Exception{run(()->{
  Fixture f=new Fixture();f.begin();int n=0;for(String stage:SketchLayeredDrawing.STAGES){if(!"construction".equals(stage))f.edit(new JSONObject().put("type","layered_drawing_stage").put("stage",stage));JSONArray ids=SketchLayeredDrawing.activeLayerIds(f.doc());if(!"separation".equals(stage))for(int i=0;i<ids.length();i++)f.edit(SketchLayeredDrawingTest.stroke(ids.getString(i),"mark_"+(n++)));f.review(stage);}f.edit(new JSONObject().put("type","layered_drawing_finish"));JSONObject finished=f.doc();assertEquals("REVIEWED",finished.getJSONObject("layeredDrawing").getString("status"));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",f.id).put("expectedRevision",finished.getLong("revision")).put("operationId","archive"));JSONObject result=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore","Restored authored stages");try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject restored=store.get(result.getString("sketchId"));assertEquals(SketchDocument.canonical(finished.getJSONObject("layeredDrawing")),SketchDocument.canonical(restored.getJSONObject("layeredDrawing")));assertEquals("REVIEWED",SketchLayeredDrawing.summary(restored).getString("status"));}
  f.edit(SketchLayeredDrawingTest.stroke("actor_head","later_authored_edit"));assertEquals("IN_PROGRESS",f.doc().getJSONObject("layeredDrawing").getString("status"));return null;
 });}
}
