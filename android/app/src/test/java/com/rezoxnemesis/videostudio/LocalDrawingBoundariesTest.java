package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.widget.*;
import android.view.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class LocalDrawingBoundariesTest {
 private Context context;private JSONObject request;private String id="local_boundary_source";
 @Before public void source()throws Exception{
  context=RuntimeEnvironment.getApplication();Bitmap b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.BLUE);c.drawRect(8,0,16,16,p);File f=new File(context.getFilesDir(),"owned_reference.png");try(FileOutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Native source boundary fixture");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(f),f.getName(),"image/png",0,f.length());
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("name","Boundary fixture").put("width",256).put("height",256).put("frameCount",1));
  SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","import").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide"));
  try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject doc=store.get(id);request=new JSONObject().put("requestId","boundary_study").put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("objectId","guide").put("sourceSha256",SketchDocument.object(doc,"guide").getJSONObject("reference").getString("sha256")).put("sourceRect",new JSONObject().put("x",.5).put("y",0).put("width",.5).put("height",1)).put("settings",new JSONObject().put("prompt","A precise pencil study").put("seed",42).put("steps",20).put("strength",.6).put("guidance",7));}
 }
 @Test public void sourceCropDoesNotLeakExcludedPixelsAndKeepsOriginalDocument()throws Exception{
  try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject before=store.get(id);String hash=SketchDocument.canonical(before);Bitmap frozen=LocalDrawingSource.prepare(store,before,request);try{assertEquals(512,frozen.getWidth());assertEquals(Color.WHITE,frozen.getPixel(20,256));assertEquals("Excluded red pixels must not leak through cached/bilinear source preparation",Color.BLUE,frozen.getPixel(128,256));assertEquals(Color.BLUE,frozen.getPixel(256,256));}finally{frozen.recycle();}assertEquals(hash,SketchDocument.canonical(store.get(id)));}
 }
 @Test public void strictSourceAndSettingsRejectUnknownCoercedOrStaleInputs()throws Exception{
  JSONObject bad=SketchDocument.copy(request).put("sourceUrl","https://example.com");assertThrows(Exception.class,()->LocalDrawingSource.validate(bad));
  JSONObject string=SketchDocument.copy(request);string.getJSONObject("settings").put("steps","20");assertThrows(Exception.class,()->LocalDrawingSource.validate(string));
  JSONObject nil=SketchDocument.copy(request);nil.getJSONObject("settings").put("negativePrompt",JSONObject.NULL);assertThrows(Exception.class,()->LocalDrawingSource.validate(nil));
  JSONObject wrong=SketchDocument.copy(request).put("sourceSha256",new String(new char[64]).replace('\0','a'));
  try(SketchProjectStore store=new SketchProjectStore(context)){assertThrows(Exception.class,()->LocalDrawingSource.prepare(store,store.get(id),wrong));JSONObject stale=SketchDocument.copy(request).put("expectedRevision",1);assertThrows(Exception.class,()->LocalDrawingSource.prepare(store,store.get(id),stale));}
 }
 @Test public void absentModelFailsWithoutDrawingFallbackAndRetainsSourceBoundRetry()throws Exception{
  String before;try(SketchProjectStore store=new SketchProjectStore(context)){before=SketchDocument.canonical(store.get(id));}
  Exception failure=assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",request,null));assertTrue(failure.getMessage(),failure.getMessage().contains("MODEL_NOT_INSTALLED"));
  JSONObject receipt=LocalDrawingOperations.execute(context,"local_drawing_get",new JSONObject().put("requestId",request.getString("requestId")),null);assertEquals("failed",receipt.getString("state"));assertFalse(receipt.has("outputSha256"));assertFalse(receipt.getBoolean("hostedInference"));
  JSONObject altered=SketchDocument.copy(request);altered.getJSONObject("settings").put("seed",43);Exception conflict=assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",altered,null));assertTrue(conflict.getMessage().contains("REQUEST_ID_CONFLICT"));
  assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",request,null));
  try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(before,SketchDocument.canonical(store.get(id)));}
 }
 @Test public void humanUiLoadsOwnedReferenceAndReportsMissingModelWithoutNetworking()throws Exception{
  try(ActivityController<LocalDrawingActivity> controller=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(context,LocalDrawingActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
   LocalDrawingActivity activity=controller.get();idle(activity);View root=activity.getWindow().getDecorView();Button check=button(root,"Check local model");assertNotNull(check);check.performClick();idle(activity);assertTrue(((TextView)field(activity,"status")).getText().toString().contains("Model not installed"));
   Spinner image=(Spinner)field(activity,"source");assertEquals(1,image.getCount());assertNotNull(button(root,"Generate new draft"));
  }
 }
 @Test public void receiptCorruptionCannotChangeItsAcceptedSourceOrSettingsSilently()throws Exception{
  assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",request,null));
  File file=new File(context.getFilesDir(),"local_drawing/studies/boundary_study/record.json");JSONObject record=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));record.getJSONObject("request").getJSONObject("settings").put("seed",999);
  java.nio.file.Files.write(file.toPath(),record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Exception error=assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_get",new JSONObject().put("requestId","boundary_study"),null));assertTrue(error.getMessage().contains("RECORD_IDENTITY_DIFFERS"));
 }
 @Test public void cancellationBeforeAdmissionLeavesNoStudyOrArtworkChange()throws Exception{
  String before;try(SketchProjectStore sketches=new SketchProjectStore(context)){before=SketchDocument.canonical(sketches.get(id));}
  Thread.currentThread().interrupt();try{assertThrows(InterruptedException.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",request,null));}finally{Thread.interrupted();}
  assertFalse(new File(context.getFilesDir(),"local_drawing/studies/boundary_study/record.json").exists());try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(before,SketchDocument.canonical(sketches.get(id)));}
 }
 @Test public void invalidSourceAttemptsDoNotExhaustTheRetainedStudyQuota()throws Exception{
  for(int i=0;i<70;i++){JSONObject stale=SketchDocument.copy(request).put("requestId","stale_"+i).put("expectedRevision",1);assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",stale,null));}
  File parent=new File(context.getFilesDir(),"local_drawing/studies");assertTrue(!parent.exists()||parent.listFiles().length==0);
  Exception missing=assertThrows(Exception.class,()->LocalDrawingOperations.execute(context,"local_drawing_generate",request,null));assertTrue(missing.getMessage().contains("MODEL_NOT_INSTALLED"));
  assertEquals("failed",LocalDrawingOperations.execute(context,"local_drawing_get",new JSONObject().put("requestId","boundary_study"),null).getString("state"));
 }
 @Test public void modelManifestAloneDoesNotBecomeAnExecutableNeuralProvider()throws Exception{
  LocalDrawingModels models=new LocalDrawingModels(context);assertFalse(models.status().getBoolean("modelInstalled"));File root=models.directory();assertTrue(root.mkdirs()||root.isDirectory());
  JSONObject fake=new JSONObject().put("id",LocalDrawingModels.ID).put("version","1").put("runtimeAdapter",LocalDrawingModels.ID).put("pinnedCatalogueSha256",models.identity()).put("capabilities",new JSONArray().put("image.generate.neural"));java.nio.file.Files.write(new File(root,"manifest.json").toPath(),fake.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertFalse(models.status().getBoolean("runtimeAvailable"));assertFalse(new CapabilityRegistry(context).resolve("image.generate.neural","").getBoolean("resolved"));
  for(String action:new String[]{"local_drawing_models","local_drawing_install","local_drawing_generate","local_drawing_get"}){assertTrue(NativeAgentMailboxActions.supports(action));assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));}
 }
 private static void idle(Object target)throws Exception{((ExecutorService)field(target,"worker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
 private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private static Button button(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return(Button)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button found=button(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
