package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Authored native frame conditioning; no model weights or generated-art claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingFrameSourceTest {
 Context context;JSONObject request;String id="authored_frame_source";
 @Before public void authoredLayers()throws Exception {
  context=RuntimeEnvironment.getApplication();
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("name","Authored frame input").put("width",256).put("height",256).put("frameCount",24));
  JSONObject blue=new JSONObject().put("id","blue_key").put("kind","rect").put("frame",4).put("exposure",4).put("color","#FF0000FF").put("fill",true).put("bounds",new JSONObject().put("x",.2).put("y",.3).put("width",.2).put("height",.2));
  JSONObject red=new JSONObject().put("id","red_background").put("kind","rect").put("color","#FFFF0000").put("fill",true).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1));
  JSONArray operations=new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",blue))
   .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","excluded").put("name","Excluded red layer")))
   .put(new JSONObject().put("type","add_object").put("layerId","excluded").put("object",red));
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","authored").put("expectedRevision",1).put("operations",operations));
  JSONObject doc=document();String hash=SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8));
  request=new JSONObject().put("requestId","frame_study").put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("frameSource",new JSONObject().put("frameIndex",4).put("documentSha256",hash).put("layerIds",new JSONArray().put("layer_1")).put("crop",new JSONObject().put("x",.1).put("y",.2).put("width",.4).put("height",.4))).put("settings",new JSONObject().put("prompt","Refine this posed drawing in graphite").put("seed",42).put("steps",4).put("strength",.4).put("guidance",6));
 }
 JSONObject document()throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
 JSONObject execute(JSONObject p)throws Exception {ExecutorService worker=Executors.newSingleThreadExecutor();try{return worker.submit(()->LocalDrawingOperations.execute(context,"local_drawing_generate",p,null)).get(30,TimeUnit.SECONDS);}catch(ExecutionException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw e;}finally{worker.shutdownNow();}}
 @Test public void selectedFrameAndLayersBecomeFrozenModelInputWithoutEditingSource()throws Exception {
  String before=SketchDocument.canonical(document());Exception missing=assertThrows(Exception.class,()->execute(request));assertTrue(missing.getMessage(),missing.getMessage().contains("MODEL_NOT_INSTALLED"));
  JSONObject receipt=LocalDrawingOperations.execute(context,"local_drawing_get",new JSONObject().put("requestId","frame_study"),null);assertEquals("failed",receipt.getString("state"));assertEquals("sketch_frame",receipt.getString("sourceKind"));assertEquals(request.getJSONObject("frameSource").getString("documentSha256"),receipt.getString("sourceDocumentSha256"));
  Bitmap frozen=BitmapFactory.decodeFile(new File(context.getFilesDir(),"local_drawing/studies/frame_study/source.png").getPath());assertNotNull(frozen);try{assertEquals(512,frozen.getWidth());assertEquals(512,frozen.getHeight());assertEquals(Color.BLUE,frozen.getPixel(256,256));assertEquals("Unselected layer and document background must be absent",Color.WHITE,frozen.getPixel(10,10));}finally{frozen.recycle();}
  assertEquals(before,SketchDocument.canonical(document()));
 }
 @Test public void sourcePreviewShowsTheActualFrameWithoutAStudyOrInstalledModel()throws Exception {
  JSONObject p=SketchDocument.copy(request).put("previewOnly",true);ExecutorService worker=Executors.newSingleThreadExecutor();JSONObject preview;try{preview=worker.submit(()->LocalDrawingOperations.execute(context,"local_drawing_generate",p,null)).get(30,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
  assertTrue(preview.getBoolean("sourcePreviewOnly"));assertFalse(preview.getBoolean("inferenceExecuted"));assertFalse(preview.getBoolean("sourceArtworkModified"));JSONObject png=preview.getJSONObject("localDrawingSourcePreviewImage");byte[] bytes=android.util.Base64.decode(png.getString("base64"),0);assertEquals(SketchDocument.sha256(bytes),png.getString("sha256"));Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);try{assertEquals(Color.BLUE,image.getPixel(256,256));assertEquals(Color.WHITE,image.getPixel(10,10));}finally{image.recycle();}assertFalse(new File(context.getFilesDir(),"local_drawing/studies/frame_study/record.json").exists());
 }
 @Test public void staleHashRevisionUnknownLayerOrEmptyExposureNeverAdmitAStudy()throws Exception {
  for(String kind:new String[]{"hash","revision","layer","frame","crop"}){JSONObject p=SketchDocument.copy(request).put("requestId","bad_"+kind);JSONObject source=p.getJSONObject("frameSource");
   if(kind.equals("hash"))source.put("documentSha256","a".repeat(64));if(kind.equals("revision"))p.put("expectedRevision",1);if(kind.equals("layer"))source.put("layerIds",new JSONArray().put("missing"));if(kind.equals("frame"))source.put("frameIndex",0);if(kind.equals("crop"))source.put("crop",new JSONObject().put("x",.8).put("y",.8).put("width",.1).put("height",.1));
   assertThrows(Exception.class,()->execute(p));assertFalse(new File(context.getFilesDir(),"local_drawing/studies/"+p.getString("requestId")+"/record.json").exists());
  }
 }
 @Test public void sourceModesAndFrameFieldsRejectCoercionNullAndAmbiguity()throws Exception {
  LocalDrawingSource.validate(request);
  for(String kind:new String[]{"both","null","string_frame","duplicate","empty","unknown","tiny_crop"}){JSONObject p=SketchDocument.copy(request);JSONObject source=p.getJSONObject("frameSource");if(kind.equals("both"))p.put("objectId","guide").put("sourceSha256","a".repeat(64));if(kind.equals("null"))p.put("frameSource",JSONObject.NULL);if(kind.equals("string_frame"))source.put("frameIndex","4");if(kind.equals("duplicate"))source.put("layerIds",new JSONArray().put("layer_1").put("layer_1"));if(kind.equals("empty"))source.put("layerIds",new JSONArray());if(kind.equals("unknown"))source.put("pose","right");if(kind.equals("tiny_crop"))source.put("crop",new JSONObject().put("x",0).put("y",0).put("width",0).put("height",1));assertThrows(Exception.class,()->LocalDrawingSource.validate(p));}
 }
 @Test public void retryUsesTheFrozenFrameAndChangedClockConflicts()throws Exception {
  Exception first=assertThrows(Exception.class,()->execute(request));assertTrue(first.getMessage(),first.getMessage().contains("MODEL_NOT_INSTALLED"));String frozen=new File(context.getFilesDir(),"local_drawing/studies/frame_study/source.png").getPath();String hash=SketchProjectStore.fileHash(new File(frozen));
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","later_edit").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","delete_object").put("objectId","blue_key"))));
  Exception again=assertThrows(Exception.class,()->execute(request));assertTrue(again.getMessage(),again.getMessage().contains("MODEL_NOT_INSTALLED"));assertEquals(hash,SketchProjectStore.fileHash(new File(frozen)));
  JSONObject changed=SketchDocument.copy(request);changed.getJSONObject("frameSource").put("frameIndex",5);Exception conflict=assertThrows(Exception.class,()->execute(changed));assertTrue(conflict.getMessage(),conflict.getMessage().contains("REQUEST_ID_CONFLICT"));
 }
 @Test public void manualSourcePreviewUsesTheSameExplicitFrameAndLayerSelection()throws Exception {
  String before=SketchDocument.canonical(document());
  try(org.robolectric.android.controller.ActivityController<LocalDrawingActivity> controller=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(context,LocalDrawingActivity.class).putExtra("sketchId",id).putExtra("frameIndex",4)).create().start().resume().visible()){
   LocalDrawingActivity activity=controller.get();idle(activity);android.view.View root=activity.getWindow().getDecorView();android.widget.Button preview=button(root,"Preview source");assertNotNull("Manual source preview is available",preview);
   ((android.widget.Spinner)field(activity,"inputMode")).setSelection(1);org.robolectric.shadows.ShadowLooper.idleMainLooper();
   button(root,"Choose frame layers").performClick();android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);android.widget.ListView layers=dialog.getListView();layers.setItemChecked(1,false);layers.performItemClick(null,1,1);dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
   for(String[] entry:new String[][]{{"x","0.1"},{"y","0.2"},{"w","0.4"},{"h","0.4"}})((android.widget.EditText)field(activity,entry[0])).setText(entry[1]);preview.performClick();idle(activity);
   Bitmap image=(Bitmap)field(activity,"shown");assertNotNull(image);assertEquals(Color.BLUE,image.getPixel(256,256));assertEquals(Color.WHITE,image.getPixel(10,10));assertTrue(((android.widget.TextView)field(activity,"status")).getText().toString().contains("Source preview"));
   assertFalse(new File(context.getFilesDir(),"local_drawing/studies").exists());
  }
  assertEquals(before,SketchDocument.canonical(document()));
 }
 private static void idle(Object target)throws Exception {((ExecutorService)field(target,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();}
 private static Object field(Object target,String name)throws Exception {java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private static android.widget.Button button(android.view.View v,String text){if(v instanceof android.widget.Button&&text.contentEquals(((android.widget.Button)v).getText()))return(android.widget.Button)v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.widget.Button found=button(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
 @Test public void cancellationBeforePreparationKeepsAllAuthoredLayers()throws Exception {
  String before=SketchDocument.canonical(document());Thread.currentThread().interrupt();try{assertThrows(InterruptedException.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",request,null));}finally{Thread.interrupted();}assertEquals(before,SketchDocument.canonical(document()));assertFalse(new File(context.getFilesDir(),"local_drawing/studies/frame_study/record.json").exists());
 }
}
