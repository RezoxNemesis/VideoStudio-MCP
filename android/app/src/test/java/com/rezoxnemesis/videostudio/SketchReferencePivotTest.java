package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Source-bound cutout pivots and actual retained-pixel preview, not artwork approval. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchReferencePivotTest {
 final Context context=RuntimeEnvironment.getApplication();final ExecutorService worker=Executors.newSingleThreadExecutor();JSONObject request;String sourceHash;
 @After public void stop(){worker.shutdownNow();}
 private <T>T run(Callable<T> action)throws Exception{try{return worker.submit(action).get(90,TimeUnit.SECONDS);}catch(ExecutionException e){Throwable t=e.getCause();if(t instanceof Exception)throw(Exception)t;if(t instanceof Error)throw(Error)t;throw new RuntimeException(t);}}
 @Before public void fixture()throws Exception{run(()->{
  Bitmap b=Bitmap.createBitmap(256,128,Bitmap.Config.ARGB_8888);b.eraseColor(0xff234567);File png=new File(context.getFilesDir(),"pivot-source.png");try(FileOutputStream out=new FileOutputStream(png)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Cutout pivot fixture");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(png),png.getName(),"image/png",0,png.length());
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId","pivot_sketch").put("operationId","create").put("width",256).put("height",256).put("frameCount",24).put("background","#00000000"));
  SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId","pivot_sketch").put("operationId","import").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","source"));
  JSONObject ref=SketchDocument.object(doc(),"source").getJSONObject("reference");sourceHash=ref.getString("sha256");
  request=new JSONObject().put("sketchId","pivot_sketch").put("expectedRevision",2).put("operationId","parts").put("sourceObjectId","source").put("expectedReferenceSha256",sourceHash).put("expectedSourceSha256",ref.getString("sourceSha256")).put("frame",0).put("exposure",24).put("hideSource",true).put("masks",new JSONArray().put(new JSONObject().put("maskId","head").put("layerId","head_layer").put("objectId","head_image").put("name","Head fixture").put("rings",new JSONArray().put(new JSONArray("[[0,0],[0.5,0],[0.5,1],[0,1]]"))).put("pivot",new JSONArray("[0.25,0.5]"))));return null;
 });}
 JSONObject doc()throws Exception{try(SketchProjectStore s=new SketchProjectStore(context)){return s.get("pivot_sketch");}}
 @Test public void sourcePivotMapsThroughAspectFitAndRemainsFixedDuringRotation()throws Exception{run(()->{
  SketchEditOps.execute(context,"sketch_import_reference_layers",request);JSONObject after=doc(),part=SketchDocument.object(after,"head_image"),t=part.getJSONObject("transform");
  assertEquals(.25,t.getDouble("anchorX"),0);assertEquals(.5,t.getDouble("anchorY"),0);assertEquals(.25,part.getJSONObject("bounds").getDouble("y"),0);
  float[] pivot={64,128};SketchFrameRenderer.matrix(SketchDocument.copy(t).put("rotation",90),256,256).mapPoints(pivot);assertArrayEquals(new float[]{64,128},pivot,.0001f);
  assertEquals(sourceHash,SketchDocument.object(after,"source").getJSONObject("reference").getString("sha256"));
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId","pivot_sketch").put("operationId","undo").put("expectedRevision",3));assertThrows(Exception.class,()->SketchDocument.object(doc(),"head_image"));
  SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId","pivot_sketch").put("operationId","redo").put("expectedRevision",4));assertEquals(.25,SketchDocument.object(doc(),"head_image").getJSONObject("transform").getDouble("anchorX"),0);return null;
 });}
 @Test public void previewIncludesTransparentRetainedPixelsAndTheSameAcceptedPivot()throws Exception{run(()->{
  JSONObject ordinary=SketchDocument.copy(request);ordinary.getJSONArray("masks").getJSONObject(0).remove("pivot");JSONObject preview=SketchEditOps.execute(context,"sketch_reference_layers_preview",ordinary);JSONObject image=preview.getJSONObject("retainedPreviewImage");assertEquals("image/png",image.getString("mimeType"));assertTrue(image.getInt("width")<=360&&image.getInt("height")<=360);byte[] png=Base64.decode(image.getString("base64"),0);assertEquals(SketchDocument.sha256(png),image.getString("sha256"));Bitmap b=BitmapFactory.decodeByteArray(png,0,png.length);try{assertEquals(0xff234567,b.getPixel(32,64));assertEquals(0,b.getPixel(192,64));}finally{b.recycle();}
  assertEquals(2,doc().getInt("revision"));return null;
 });}
 @Test public void malformedPivotsNeverPublishParts()throws Exception{run(()->{
  for(Object pivot:new Object[]{JSONObject.NULL,new JSONArray("[0.5]"),new JSONArray("[0.5,\"0.5\"]"),new JSONArray("[-0.01,0.5]"),new JSONArray("[1.01,0.5]"),new JSONArray("[0.5,0.5,0]")}){JSONObject bad=SketchDocument.copy(request);bad.getJSONArray("masks").getJSONObject(0).put("pivot",pivot);assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference_layers",bad));assertEquals(2,doc().getInt("revision"));}return null;
 });}
 @Test public void jointOverlapRequiresAnExplicitBooleanAndReportsDurableSharedPixels()throws Exception{run(()->{
  JSONObject second=new JSONObject().put("maskId","neck").put("layerId","neck_layer").put("objectId","neck_image").put("name","Neck joint fixture").put("rings",new JSONArray().put(new JSONArray("[[0.25,0],[0.75,0],[0.75,1],[0.25,1]]")));
  request.getJSONArray("masks").put(second);assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference_layers",request));
  for(Object value:new Object[]{false,JSONObject.NULL,"true",1}){JSONObject bad=SketchDocument.copy(request).put("allowPartOverlap",value);assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference_layers",bad));assertEquals(2,doc().getInt("revision"));}
  JSONObject invalidBackground=SketchDocument.copy(request).put("allowPartOverlap",true).put("background",new JSONObject().put("layerId","bg_layer").put("objectId","bg_image").put("name","Background"));assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference_layers",invalidBackground));assertEquals(2,doc().getInt("revision"));
  request.put("allowPartOverlap",true);JSONObject result=SketchEditOps.execute(context,"sketch_import_reference_layers",request);assertEquals(8192,result.getInt("sharedSourcePixelCount"));assertEquals(SketchDocument.canonical(result),SketchDocument.canonical(SketchEditOps.execute(context,"sketch_import_reference_layers",request)));
  JSONObject reference=SketchDocument.object(doc(),"head_image").getJSONObject("reference");assertTrue(reference.getJSONObject("sourceMask").getBoolean("allowPartOverlap"));SketchSourceMask.validate(reference);reference.getJSONObject("sourceMask").put("allowPartOverlap",false);assertThrows(Exception.class,()->SketchSourceMask.validate(reference));return null;
 });}
 @Test public void jointOverlapExplicitFalseBindsTheChoiceWithoutChangingRetainedPixels()throws Exception{run(()->{
  request.put("allowPartOverlap",false);JSONObject result=SketchEditOps.execute(context,"sketch_import_reference_layers",request);assertEquals(0,result.getInt("sharedSourcePixelCount"));JSONObject reference=SketchDocument.object(doc(),"head_image").getJSONObject("reference");assertFalse(reference.getJSONObject("sourceMask").getBoolean("allowPartOverlap"));SketchSourceMask.validate(reference);String explicit=reference.getJSONObject("sourceMask").getString("maskSha256");reference.getJSONObject("sourceMask").remove("allowPartOverlap");assertNotEquals(explicit,SketchSourceMask.hash(reference.getInt("width"),reference.getInt("height"),reference.getJSONObject("sourceMask").getJSONArray("rings"),false));assertThrows(Exception.class,()->SketchSourceMask.validate(reference));return null;
 });}
 @Test public void sourcePivotsAndSharedPixelProvenanceSurviveArchiveRestoration()throws Exception{run(()->{
  request.put("allowPartOverlap",true);SketchEditOps.execute(context,"sketch_import_reference_layers",request);JSONObject before=doc();SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId","pivot_sketch").put("expectedRevision",3).put("operationId","archive_pivots"));JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_pivots","Restored pivot parts");try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject after=store.get(restored.getString("sketchId"));store.verifyReferences(after);assertEquals(SketchDocument.canonical(before.getJSONArray("layers")),SketchDocument.canonical(after.getJSONArray("layers")));}return null;
 });}
 @Test public void overlappingTranslucentPreviewMatchesTheActualImportedLayerStack()throws Exception{run(()->{
  Bitmap input=Bitmap.createBitmap(256,128,Bitmap.Config.ARGB_8888);input.eraseColor(0x80ff0000);File png=new File(context.getFilesDir(),"alpha-stack-source.png");try(FileOutputStream out=new FileOutputStream(png)){assertTrue(input.compress(Bitmap.CompressFormat.PNG,100,out));}finally{input.recycle();}
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Translucent cutout overlap fixture");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(png),png.getName(),"image/png",0,png.length());String id="alpha_stack_sketch";
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",24).put("background","#00000000"));SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","import").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","source"));JSONObject source;try(SketchProjectStore store=new SketchProjectStore(context)){source=store.get(id);}JSONObject ref=SketchDocument.object(source,"source").getJSONObject("reference");
  JSONObject args=SketchDocument.copy(request).put("sketchId",id).put("expectedReferenceSha256",ref.getString("sha256")).put("expectedSourceSha256",ref.getString("sourceSha256")).put("allowPartOverlap",true);JSONObject first=args.getJSONArray("masks").getJSONObject(0);first.put("rings",new JSONArray().put(new JSONArray("[[0,0],[0.75,0],[0.75,1],[0,1]]")));args.getJSONArray("masks").put(new JSONObject().put("maskId","right").put("layerId","right_layer").put("objectId","right_image").put("name","Right translucent part").put("rings",new JSONArray().put(new JSONArray("[[0.25,0],[1,0],[1,1],[0.25,1]]"))));
  JSONObject preview=SketchEditOps.execute(context,"sketch_reference_layers_preview",args);byte[] bytes=Base64.decode(preview.getJSONObject("retainedPreviewImage").getString("base64"),Base64.DEFAULT);Bitmap retained=BitmapFactory.decodeByteArray(bytes,0,bytes.length);try{SketchEditOps.execute(context,"sketch_import_reference_layers",args);JSONObject imported;try(SketchProjectStore store=new SketchProjectStore(context)){imported=store.get(id);}try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,imported)){Bitmap actual=renderer.render(0);try{assertEquals("Native source-over stack must really accumulate two half-alpha parts",192,Color.alpha(actual.getPixel(128,128)));assertEquals("Retained preview must show actual import compositing at shared pixels",actual.getPixel(128,128),retained.getPixel(128,64));assertEquals("Single-part pixels retain their source alpha",actual.getPixel(32,128),retained.getPixel(32,64));assertEquals(128,Color.alpha(retained.getPixel(32,64)));}finally{actual.recycle();}}}finally{retained.recycle();}return null;
 });}
 @Test public void omittedPivotKeepsTheOriginalCanvasCenter()throws Exception{run(()->{
  request.getJSONArray("masks").getJSONObject(0).remove("pivot");SketchEditOps.execute(context,"sketch_import_reference_layers",request);JSONObject t=SketchDocument.object(doc(),"head_image").getJSONObject("transform");assertEquals(.5,t.getDouble("anchorX"),0);assertEquals(.5,t.getDouble("anchorY"),0);return null;
 });}
}
