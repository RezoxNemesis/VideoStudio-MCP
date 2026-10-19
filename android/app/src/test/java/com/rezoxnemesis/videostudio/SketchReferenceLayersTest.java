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

/** Native authored source -> registered import -> actual source-assisted native parts. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchReferenceLayersTest {
 final Context context=RuntimeEnvironment.getApplication();
 final ExecutorService worker=Executors.newSingleThreadExecutor();
 @After public void stop(){worker.shutdownNow();}
 private <T>T run(Callable<T> action)throws Exception{try{return worker.submit(action).get(90,TimeUnit.SECONDS);}catch(ExecutionException e){Throwable t=e.getCause();if(t instanceof Exception)throw(Exception)t;if(t instanceof Error)throw(Error)t;throw new RuntimeException(t);}}
 private JSONObject call(JSONObject args)throws Exception{try{return SketchEditOps.execute(context,"sketch_import_reference_layers",args);}catch(IllegalArgumentException e){if(e.getMessage().contains("Unsupported sketch action"))throw new AssertionError("App-owned named reference layers are missing",e);throw e;}}
 private static JSONObject bounds(double x,double y,double w,double h)throws Exception{return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);}
 private static JSONArray ring(double l,double t,double r,double b)throws Exception{return new JSONArray().put(new JSONArray().put(l).put(t)).put(new JSONArray().put(r).put(t)).put(new JSONArray().put(r).put(b)).put(new JSONArray().put(l).put(b));}
 private static JSONObject mask(String id,JSONArray rings)throws Exception{return new JSONObject().put("maskId",id).put("layerId",id+"_layer").put("objectId",id+"_image").put("name",id+" part").put("rings",rings);}
 private static JSONObject drawing(String id,String color,JSONObject box)throws Exception{return new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id",id).put("kind","rect").put("fill",true).put("color",color).put("bounds",box));}
 private final class Fixture{
  final String id="parts_"+UUID.randomUUID();final JSONObject request;final JSONObject original;
  Fixture(boolean hidden)throws Exception{
   JSONObject art=SketchDocument.create(new JSONObject().put("sketchId","art_"+id).put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));
   SketchEditOps.apply(art,drawing("red","#FFFF0000",bounds(0,0,.5,1)));SketchEditOps.apply(art,drawing("blue","#800000FF",bounds(.5,0,.5,1)));SketchEditOps.apply(art,drawing("green","#FF00FF00",bounds(.1,.1,.1,.1)));
   File png=new File(context.getFilesDir(),id+"_native-source.png");try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,art)){Bitmap b=renderer.render(0);try(FileOutputStream out=new FileOutputStream(png)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}}
   ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Original native reference fixture");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(png),png.getName(),"image/png",0,png.length());
   SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("fps",24).put("frameCount",96).put("background","#00000000"));
   SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","import").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","source").put("guideVisible",!hidden));original=doc();JSONObject ref=SketchDocument.object(original,"source").getJSONObject("reference");
   request=new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","separate").put("sourceObjectId","source").put("expectedReferenceSha256",ref.getString("sha256")).put("expectedSourceSha256",ref.getString("sourceSha256")).put("frame",0).put("exposure",96).put("hideSource",true)
    .put("masks",new JSONArray().put(mask("left",new JSONArray().put(ring(0,0,.5,1)).put(ring(.1,.1,.2,.2)))).put(mask("right",new JSONArray().put(ring(.5,0,1,1)))))
    .put("background",new JSONObject().put("layerId","background_layer").put("objectId","background_image").put("name","Unselected source pixels"));
  }
  JSONObject doc()throws Exception{try(SketchProjectStore s=new SketchProjectStore(context)){return s.get(id);}}
  Bitmap pixels(JSONObject d,String object)throws Exception{try(SketchProjectStore s=new SketchProjectStore(context)){return BitmapFactory.decodeFile(s.referenceFile(d.getString("id"),SketchDocument.object(d,object).getJSONObject("reference")).getPath());}}
 }
 @Test public void masksKeepActualSourcePixelsAlphaHolesAndExactClock()throws Exception{run(()->{
  Fixture f=new Fixture(false);JSONObject result=call(f.request),d=f.doc();assertTrue(result.getBoolean("sourceAssisted"));assertEquals(3,d.getInt("revision"));assertEquals(4,d.getJSONArray("layers").length());
  for(String field:new String[]{"width","height","fps","frameCount"})assertEquals(field,f.original.get(field),d.get(field));assertEquals(0,SketchDocument.object(d,"source").getJSONObject("transform").getDouble("opacity"),0);
  Bitmap source=f.pixels(f.original,"source"),left=f.pixels(d,"left_image"),right=f.pixels(d,"right_image");try{assertEquals(source.getPixel(4,128),left.getPixel(4,128));assertEquals(0,left.getPixel(40,40));assertEquals(0,left.getPixel(192,128));assertEquals(source.getPixel(192,128),right.getPixel(192,128));assertEquals(128,Color.alpha(right.getPixel(192,128)));}finally{source.recycle();left.recycle();right.recycle();}
  try(SketchFrameRenderer a=new SketchFrameRenderer(context,f.original);SketchFrameRenderer b=new SketchFrameRenderer(context,d)){Bitmap before=a.render(0),after=b.render(0);try{assertTrue("Disjoint masks plus remainder reproduce the original visible image",before.sameAs(after));}finally{before.recycle();after.recycle();}}
  JSONObject reference=SketchDocument.object(d,"left_image").getJSONObject("reference"),provenance=reference.getJSONObject("sourceMask");assertEquals("source_assisted",provenance.getString("method"));assertEquals(f.request.getString("expectedReferenceSha256"),provenance.getString("sourceReferenceSha256"));assertEquals(f.request.getString("expectedSourceSha256"),provenance.getString("sourceSha256"));assertEquals(2,provenance.getJSONArray("rings").length());assertFalse(provenance.getBoolean("complement"));assertTrue(provenance.getString("maskSha256").matches("[a-f0-9]{64}"));return null;
 });}
 @Test public void oneHistoryStepHasExactReplayUndoRedoAndConflictSafeIdentity()throws Exception{run(()->{
  Fixture f=new Fixture(false);JSONObject first=call(f.request),after=f.doc();assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(call(f.request)));assertEquals(3,f.doc().getInt("revision"));
  assertThrows(IllegalStateException.class,()->call(SketchDocument.copy(f.request).put("hideSource",false)));assertThrows(IllegalStateException.class,()->call(SketchDocument.copy(f.request).put("operationId","stale")));
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",f.id).put("expectedRevision",3).put("operationId","undo"));assertEquals(SketchDocument.canonical(f.original.getJSONArray("layers")),SketchDocument.canonical(f.doc().getJSONArray("layers")));
  SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",f.id).put("expectedRevision",4).put("operationId","redo"));assertEquals(SketchDocument.canonical(after.getJSONArray("layers")),SketchDocument.canonical(f.doc().getJSONArray("layers")));return null;
 });}
 @Test public void invalidAndOverlappingMasksNeverPublishPartialLayers()throws Exception{run(()->{
  for(String variant:new String[]{"overlap","self_intersection","string_coordinate","duplicate_id","empty_visible","wrong_hash"}){
   Fixture f=new Fixture(false);JSONObject a=SketchDocument.copy(f.request);JSONArray masks=a.getJSONArray("masks");
   switch(variant){case "overlap":masks.getJSONObject(1).put("rings",new JSONArray().put(ring(.4,0,1,1)));break;case "self_intersection":masks.getJSONObject(0).put("rings",new JSONArray().put(new JSONArray("[[0,0],[0.5,1],[0.5,0],[0,1]]")));break;case "string_coordinate":masks.getJSONObject(0).getJSONArray("rings").getJSONArray(0).getJSONArray(0).put(0,"0");break;case "duplicate_id":masks.getJSONObject(1).put("objectId","left_image");break;case "empty_visible":masks.getJSONObject(0).put("rings",new JSONArray().put(ring(0,0,.001,.001)));break;default:a.put("expectedReferenceSha256","0".repeat(64));}
   String before=SketchDocument.canonical(f.doc());assertThrows(IllegalArgumentException.class,()->call(a));assertEquals(variant,before,SketchDocument.canonical(f.doc()));
  }return null;
 });}
 @Test public void unsupportedSourcePlacementIsRejectedWithoutChangingTheDrawing()throws Exception{run(()->{
  for(String variant:new String[]{"rotation","mixed_layer","crop","layer_opacity"}){
   Fixture f=new Fixture(false);JSONObject op;
   if(variant.equals("rotation"))op=new JSONObject().put("type","set_transform").put("objectId","source").put("transform",SketchDocument.transform().put("rotation",10));
   else if(variant.equals("mixed_layer"))op=drawing("other","#FF00FF00",bounds(.1,.1,.1,.1));
   else if(variant.equals("crop"))op=new JSONObject().put("type","update_object").put("objectId","source").put("patch",new JSONObject().put("referenceCrop",bounds(.1,.1,.8,.8)));
   else op=new JSONObject().put("type","update_layer").put("layerId","layer_1").put("patch",new JSONObject().put("opacity",.5));
   SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",f.id).put("expectedRevision",2).put("operationId","placement").put("operations",new JSONArray().put(op)));JSONObject a=SketchDocument.copy(f.request).put("expectedRevision",3);String before=SketchDocument.canonical(f.doc());assertThrows(IllegalArgumentException.class,()->call(a));assertEquals(variant,before,SketchDocument.canonical(f.doc()));
  }return null;
 });}
 @Test public void originalDrawingRejectsSourcePixelCopyBeforeGeneratingAnyFiles()throws Exception{run(()->{
  Fixture f=new Fixture(true);SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",f.id).put("operationId","original").put("expectedRevision",2).put("drawingMethod","original_strokes"));String before=SketchDocument.canonical(f.doc());File dir=new File(context.getFilesDir(),"sketch_studio/"+f.id+"/references");int files=Objects.requireNonNull(dir.list()).length;
  assertThrows(IllegalStateException.class,()->call(f.request));assertEquals(before,SketchDocument.canonical(f.doc()));assertEquals(files,Objects.requireNonNull(dir.list()).length);return null;
 });}
 @Test public void componentsAndArchivesRetainIndependentPixelsAndMaskProvenance()throws Exception{run(()->{
  Fixture f=new Fixture(false);call(f.request);String component="component_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",f.id).put("expectedRevision",3).put("operationId","save_parts").put("category","prop").put("name","Native named parts").put("frameIndex",0).put("layerIds",new JSONArray().put("background_layer").put("left_layer").put("right_layer")));
  String target="target_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",target).put("operationId","new").put("width",256).put("height",256).put("fps",24).put("frameCount",96).put("background","#00000000"));SketchComponentLibrary.execute(context,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","place_parts").put("frameIndex",0).put("exposure",96));
  JSONObject placed;try(SketchProjectStore s=new SketchProjectStore(context)){placed=s.get(target);s.verifyReferences(placed);}assertEquals(3,derived(placed));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",target).put("expectedRevision",2).put("operationId","archive_parts"));JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_parts","Restored parts");JSONObject d;try(SketchProjectStore s=new SketchProjectStore(context)){d=s.get(restored.getString("sketchId"));s.verifyReferences(d);}assertEquals(3,derived(d));assertEquals(SketchDocument.canonical(placed.getJSONArray("layers")),SketchDocument.canonical(d.getJSONArray("layers")));return null;
 });}
 @Test public void previewIsSourceBoundReadOnlyAndLeavesNoOwnedFiles()throws Exception{run(()->{
  Fixture f=new Fixture(false);String before=SketchDocument.canonical(f.doc());File dir=new File(context.getFilesDir(),"sketch_studio/"+f.id+"/references");int files=Objects.requireNonNull(dir.list()).length;
  long signal=context.getSharedPreferences("sketch_live_workspace",Context.MODE_PRIVATE).getLong("signal",0);JSONObject reply=SketchEditOps.execute(context,"sketch_reference_layers_preview",f.request);
  assertFalse(reply.getBoolean("sourceModified"));assertEquals(2,reply.getInt("sourceRevision"));assertEquals(f.request.getString("expectedReferenceSha256"),reply.getString("sourceReferenceSha256"));assertEquals(0,reply.getInt("edgeAdjustedPointCount"));assertEquals(SketchDocument.canonical(new JSONArray(f.request.getJSONArray("masks").toString())),SketchDocument.canonical(reply.getJSONArray("acceptedMasks")));
  byte[] png=android.util.Base64.decode(reply.getJSONObject("sketchPreviewImage").getString("base64"),android.util.Base64.DEFAULT);assertEquals(reply.getJSONObject("sketchPreviewImage").getString("sha256"),SketchDocument.sha256(png));Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,png.length);assertNotNull(bitmap);bitmap.recycle();
  assertEquals(before,SketchDocument.canonical(f.doc()));assertEquals(files,Objects.requireNonNull(dir.list()).length);assertEquals(signal,context.getSharedPreferences("sketch_live_workspace",Context.MODE_PRIVATE).getLong("signal",0));try(SketchProjectStore store=new SketchProjectStore(context)){String fingerprint=SketchDocument.sha256(("sketch_reference_layers_preview\n"+SketchDocument.canonical(f.request)).getBytes(java.nio.charset.StandardCharsets.UTF_8));assertNull(store.operationReceipt(f.id,"separate",fingerprint));}return null;
 });}
 @Test public void localEdgeSuggestionsAreDeterministicBoundedAndPersistAcceptedGeometry()throws Exception{run(()->{
  Fixture f=new Fixture(false);JSONObject a=SketchDocument.copy(f.request).put("edgeSnapRadius",4).put("masks",new JSONArray().put(mask("left",new JSONArray().put(ring(0,0,.49,1)))));
  JSONObject first=SketchEditOps.execute(context,"sketch_reference_layers_preview",a),second=SketchEditOps.execute(context,"sketch_reference_layers_preview",a);assertTrue(first.getInt("edgeAdjustedPointCount")>0);assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(second));
  JSONArray accepted=first.getJSONArray("acceptedMasks").getJSONObject(0).getJSONArray("rings").getJSONArray(0),input=a.getJSONArray("masks").getJSONObject(0).getJSONArray("rings").getJSONArray(0);
  for(int i=0;i<input.length();i++){double dx=(accepted.getJSONArray(i).getDouble(0)-input.getJSONArray(i).getDouble(0))*256,dy=(accepted.getJSONArray(i).getDouble(1)-input.getJSONArray(i).getDouble(1))*256;assertTrue(dx*dx+dy*dy<=16.000001);}
  call(a);assertEquals(SketchDocument.canonical(first.getJSONArray("acceptedMasks").getJSONObject(0).getJSONArray("rings")),SketchDocument.canonical(SketchDocument.object(f.doc(),"left_image").getJSONObject("reference").getJSONObject("sourceMask").getJSONArray("rings")));return null;
 });}
 @Test public void provenanceTamperingAndAmbiguousGeometryAreRejected()throws Exception{run(()->{
  Fixture f=new Fixture(false);call(f.request);JSONObject copy=f.doc();JSONObject metadata=SketchDocument.object(copy,"left_image").getJSONObject("reference").getJSONObject("sourceMask");metadata.put("maskSha256","0".repeat(64));assertThrows(IllegalArgumentException.class,()->SketchDocument.validate(copy));
  assertThrows(IllegalArgumentException.class,()->SketchSourceMask.rings(new JSONArray().put(new JSONArray("[[0,0],[1,0],[0.5,0],[0,1]]")),8));
  assertThrows(IllegalArgumentException.class,()->SketchSourceMask.rings(new JSONArray().put(ring(0,0,1,1)).put(ring(0,.2,.5,.8)),8));return null;
 });}
 @Test public void lateCancellationAfterFinalPartStagingCannotCommitOrPublish()throws Exception{run(()->{
  Fixture f=new Fixture(false);File dir=new File(context.getFilesDir(),"sketch_studio/"+f.id+"/references");String before=SketchDocument.canonical(f.doc());int files=Objects.requireNonNull(dir.list()).length;java.util.concurrent.atomic.AtomicBoolean interrupted=new java.util.concurrent.atomic.AtomicBoolean();
  JSONObject request=new JSONObject(f.request.toString()){@Override public String getString(String key)throws JSONException{String value=super.getString(key);if(key.equals("operationId")){File[] staged=dir.listFiles((folder,name)->name.endsWith(".tmp"));if(staged!=null&&staged.length==3&&interrupted.compareAndSet(false,true))Thread.currentThread().interrupt();}return value;}};
  try{assertThrows(InterruptedIOException.class,()->call(request));}finally{Thread.interrupted();}
  assertTrue("Interruption was injected after the final encoded part",interrupted.get());assertEquals(before,SketchDocument.canonical(f.doc()));assertEquals("No staged or published parts survive pre-publication cancellation",files,Objects.requireNonNull(dir.list()).length);return null;
 });}
 @Test public void previewRejectsAlreadyExhaustedOwnedReferenceBudgetWithoutWriting()throws Exception{run(()->{
  Fixture f=new Fixture(false);File dir=new File(context.getFilesDir(),"sketch_studio/"+f.id+"/references"),filler=new File(dir,"retained-budget-fixture.bin");try(RandomAccessFile out=new RandomAccessFile(filler,"rw")){out.setLength(128L*1024*1024);}String before=SketchDocument.canonical(f.doc());String[] files=dir.list();
  assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_reference_layers_preview",f.request));assertEquals(before,SketchDocument.canonical(f.doc()));assertArrayEquals(files,dir.list());return null;
 });}
 @Test public void committedReceiptReplaysAfterUndoWithoutCopyingIntoOriginalSession()throws Exception{run(()->{
  Fixture f=new Fixture(true);JSONObject receipt=call(f.request);SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",f.id).put("expectedRevision",3).put("operationId","undo_before_original"));
  SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",f.id).put("expectedRevision",4).put("operationId","original_after_undo").put("drawingMethod","original_strokes"));String before=SketchDocument.canonical(f.doc());
  assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(call(f.request)));assertEquals(before,SketchDocument.canonical(f.doc()));return null;
 });}
 @Test public void touchSelectionUsesNamedMasksAndHolesInsteadOfTransparentCanvasBounds()throws Exception{run(()->{
  Fixture f=new Fixture(false);call(f.request);JSONObject d=f.doc();assertEquals("left_image",hit(d,0,.02,.5,0));assertEquals("right_image",hit(d,0,.75,.5,0));assertEquals("background_image",hit(d,0,.15,.15,0));assertEquals("right_image",hit(d,0,.5,.5,0));return null;
 });}
 @Test public void namedPartSelectionFollowsAnimatedObjectAndLayerMatrices()throws Exception{run(()->{
  Fixture f=new Fixture(false);call(f.request);JSONObject d=f.doc();SketchDocument.layer(d,"right_layer").put("visible",false);SketchDocument.layer(d,"background_layer").put("visible",false);
  for(int frame:new int[]{0,24}){
   SketchEditOps.apply(d,new JSONObject().put("type","set_keyframe").put("objectId","left_image").put("frame",frame).put("transform",SketchDocument.transform().put("rotation",frame==0?15:90).put("x",frame==0?.1:-.1).put("scaleX",.7).put("scaleY",1.1).put("anchorX",.25)));
   SketchEditOps.apply(d,new JSONObject().put("type","set_keyframe").put("layerId","left_layer").put("frame",frame).put("transform",SketchDocument.transform().put("rotation",frame==0?-20:35).put("scaleY",.8)));
  }
  for(int frame:new int[]{0,12,24}){Matrix combined=new Matrix();combined.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(d,"left_layer"),frame),256,256),SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.object(d,"left_image"),frame),256,256));
   float[] inside={.03f*256,.5f*256},outside={.75f*256,.5f*256};combined.mapPoints(inside);combined.mapPoints(outside);assertEquals("left_image",hit(d,frame,inside[0]/256d,inside[1]/256d,0));assertNull(hit(d,frame,outside[0]/256d,outside[1]/256d,0));
  }return null;
 });}
 @Test public void namedPartSelectionUsesTheSameIntegerReferenceCropAsRendering()throws Exception{run(()->{
  Fixture f=new Fixture(false);call(f.request);JSONObject d=f.doc();SketchDocument.layer(d,"right_layer").put("visible",false);SketchDocument.layer(d,"background_layer").put("visible",false);
  SketchEditOps.apply(d,new JSONObject().put("type","update_object").put("objectId","left_image").put("patch",new JSONObject().put("bounds",bounds(.2,.2,.6,.6)).put("referenceCrop",bounds(.4979,.25,.003,.5))));
  assertEquals("left_image",hit(d,0,.2+.2*.6,.5,0));assertNull(hit(d,0,.2+.6*.6,.5,0));assertNull(hit(d,0,.1,.5,0));return null;
 });}
 @Test public void legacyImagesKeepTheirExistingExpandedRectangleSelection()throws Exception{run(()->{
  Fixture f=new Fixture(false);assertEquals("source",SketchFrameRenderer.hitTest(f.original,0,.75,.5,0));assertEquals("source",SketchFrameRenderer.hitTest(f.original,0,-.003,.5,.01));return null;
 });}
 @Test public void recuttingAReferenceKeepsInheritedTransparentHolesClickableThrough()throws Exception{run(()->{
  Fixture f=new Fixture(false);call(f.request);JSONObject first=f.doc(),ref=SketchDocument.object(first,"left_image").getJSONObject("reference");JSONObject request=SketchDocument.copy(f.request).put("operationId","recut").put("expectedRevision",3).put("sourceObjectId","left_image").put("expectedReferenceSha256",ref.getString("sha256")).put("masks",new JSONArray().put(mask("recut",new JSONArray().put(ring(0,0,.5,1)))));request.remove("background");call(request);JSONObject d=f.doc();
  assertEquals("background_image",hit(d,0,.15,.15,0));assertEquals("recut_image",hit(d,0,.02,.5,0));return null;
 });}
 private String hit(JSONObject d,int frame,double x,double y,double tolerance)throws Exception{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,d)){renderer.prepareRasterResources(128,frame);assertTrue(renderer.preparedSelectionBytes()<=8L*1024*1024);return SketchFrameRenderer.hitTest(d,frame,x,y,tolerance,renderer.imageSelections());}}
 @Test public void alphaSelectionKeepsFullResolutionAndSurvivesBitmapCacheRelease()throws Exception{run(()->{
  Fixture f=new Fixture(false);JSONObject d=f.doc(),reference=SketchDocument.copy(SketchDocument.object(d,"source").getJSONObject("reference"));JSONArray all=new JSONArray().put(ring(0,0,1,1));reference.put("sourceMask",SketchSourceMask.metadata(f.request,"whole",all,false,256,256));
  SketchEditOps.apply(d,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","whole_layer").put("objects",new JSONArray().put(SketchEditOps.completeObject(new JSONObject().put("id","whole_image").put("kind","image").put("frame",0).put("exposure",96).put("bounds",bounds(0,0,1,1)).put("reference",reference))))));
  java.util.Map<String,SketchFrameRenderer.ImageSelection> selections;try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,d)){renderer.prepareRasterResources(128,0);selections=renderer.imageSelections();assertEquals(256,selections.get(reference.getString("sha256")).width);assertTrue(renderer.preparedSelectionBytes()<=8192);}
  assertEquals("whole_image",SketchFrameRenderer.hitTest(d,0,.75,.5,0,selections));assertThrows(IllegalStateException.class,()->SketchFrameRenderer.hitTest(d,0,.75,.5,0));return null;
 });}
 @Test public void actualCanvasWaitsForWorkerAlphaAndSelectsVisiblePartsWithoutEditing()throws Exception{
  JSONObject d=run(()->{Fixture f=new Fixture(false);call(f.request);return f.doc();});String before=SketchDocument.canonical(d);
  android.widget.FrameLayout host=new android.widget.FrameLayout(context);SketchCanvasView canvas=new SketchCanvasView(context);host.addView(canvas,new android.widget.FrameLayout.LayoutParams(512,512));host.layout(0,0,512,512);canvas.layout(0,0,512,512);
  java.util.List<String> picked=new java.util.ArrayList<>(),messages=new java.util.ArrayList<>();java.util.List<JSONObject> edits=new java.util.ArrayList<>();canvas.setListener(new SketchCanvasView.Listener(){public void onSelection(String id){picked.add(id);}public void onMessage(String message){messages.add(message);}public void onOperation(JSONObject operation){edits.add(operation);}});canvas.setDocument(d);canvas.setFrame(0);canvas.setTool("select");
  java.lang.reflect.Field field=SketchCanvasView.class.getDeclaredField("rasterWorker");field.setAccessible(true);ExecutorService raster=(ExecutorService)field.get(canvas);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);raster.execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));Bitmap viewport=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
  try{
   canvas.draw(new Canvas(viewport));tap(canvas,.02,.5);assertTrue(picked.isEmpty());assertTrue(messages.stream().anyMatch(m->m.contains("selection is preparing")));
   release.countDown();raster.submit(()->{}).get(60,TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();canvas.draw(new Canvas(viewport));
   tap(canvas,.02,.5);assertEquals("left_image",canvas.getSelectedId());tap(canvas,.15,.15);assertEquals("background_image",canvas.getSelectedId());tap(canvas,.75,.5);assertEquals("right_image",canvas.getSelectedId());assertTrue(edits.isEmpty());assertEquals(before,SketchDocument.canonical(d));
   java.lang.reflect.Field cache=SketchCanvasView.class.getDeclaredField("imageSelections");cache.setAccessible(true);assertFalse(((java.util.Map<?,?>)cache.get(canvas)).isEmpty());canvas.setDocument(SketchDocument.copy(d).put("revision",4));assertTrue("A changed source must discard old alpha publications",((java.util.Map<?,?>)cache.get(canvas)).isEmpty());
  }finally{release.countDown();canvas.dispose();viewport.recycle();}
 }
 private static void tap(SketchCanvasView canvas,double x,double y){long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,(float)(x*512),(float)(y*512),0),up=android.view.MotionEvent.obtain(now,now+1,android.view.MotionEvent.ACTION_UP,(float)(x*512),(float)(y*512),0);try{canvas.onTouchEvent(down);canvas.onTouchEvent(up);}finally{down.recycle();up.recycle();}}
 private static int derived(JSONObject d)throws Exception{int count=0;JSONArray layers=d.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject o=objects.getJSONObject(j);if(o.has("reference")&&o.getJSONObject("reference").has("sourceMask")){assertEquals("source_assisted",o.getJSONObject("reference").getJSONObject("sourceMask").getString("method"));count++;}}}return count;}
}
