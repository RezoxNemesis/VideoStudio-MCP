package com.rezoxnemesis.videostudio;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Synthetic completed receipt fixture exercises placement, NOT neural inference. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingKeyframeTest {
 Context context; JSONObject placement,record; File output; String outputSha;
 @Before public void fixture()throws Exception {
  context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);
  ProjectStore.Project project=projects.create("Synthetic AI placement fixture");
  File root=new File(context.getFilesDir(),"local_drawing/studies/key_fixture");assertTrue(root.mkdirs()||root.isDirectory());
  Bitmap image=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);new Canvas(image).drawColor(Color.BLUE);
  File initial=new File(root,"initial.png");try(FileOutputStream out=new FileOutputStream(initial)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
  outputSha=SketchProjectStore.fileHash(initial);output=new File(root,outputSha+".png");assertTrue(initial.renameTo(output));
  ProjectStore.Asset asset=projects.registerGeneratedAsset(project,Uri.fromFile(output),"fixture.png","local_ai_draft",false);
  JSONObject request=new JSONObject().put("requestId","key_fixture").put("sketchId","source_sketch").put("expectedRevision",2).put("objectId","source_guide").put("sourceSha256","a".repeat(64)).put("settings",new JSONObject().put("prompt","Keep the same character, pencil key drawing").put("seed",42).put("steps",4).put("strength",.2).put("guidance",7));
  String model=new LocalDrawingModels(context).identity();String fingerprint=SketchDocument.sha256((model+"\n"+SketchDocument.canonical(request)).getBytes(StandardCharsets.UTF_8));
  record=new JSONObject().put("requestId","key_fixture").put("request",request).put("modelIdentity",model).put("requestSha256",fingerprint).put("state","completed").put("outputFile",output.getName()).put("outputSha256",outputSha).put("outputBytes",output.length()).put("outputProjectId",project.id).put("outputAssetId",asset.id);
  saveRecord();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId","animation").put("operationId","create").put("name","Key placement fixture").put("width",512).put("height",512).put("frameCount",24));
  placement=new JSONObject().put("sketchId","animation").put("operationId","key_placement").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","ai_key").put("frame",8).put("exposure",4).put("localDrawing",new JSONObject().put("requestId","key_fixture").put("outputSha256",outputSha));
 }
 void saveRecord()throws Exception {Files.write(new File(output.getParentFile(),"record.json").toPath(),record.toString().getBytes(StandardCharsets.UTF_8));}
 JSONObject document()throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.get("animation");}}
 @Test public void chosenDraftBecomesOnlyItsExplicitCelAndRetainsProvenanceThroughUndoAndArchive()throws Exception {
  JSONObject placed=SketchEditOps.execute(context,"sketch_import_reference",placement);assertEquals(2,placed.getLong("revision"));
  JSONObject doc=document(),object=SketchDocument.object(doc,"ai_key");assertEquals(8,object.getInt("frame"));assertEquals(4,object.getInt("exposure"));
  JSONObject provenance=object.getJSONObject("reference").getJSONObject("localDrawing");assertEquals("local_ai_raster",provenance.getString("kind"));assertEquals(outputSha,provenance.getString("outputSha256"));assertEquals(SketchDocument.canonical(record.getJSONObject("request")),SketchDocument.canonical(provenance.getJSONObject("request")));
  assertEquals(outputSha,SketchProjectStore.fileHash(output));
  for(int frame:new int[]{7,8,11,12}){JSONObject result=SketchEditOps.execute(context,"sketch_preview",new JSONObject().put("sketchId","animation").put("frameIndex",frame).put("maxDimension",512));byte[] png=android.util.Base64.decode(result.getJSONObject("sketchPreviewImage").getString("base64"),0);Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,png.length);try{assertEquals("Correct cel exposure at frame "+frame,frame>=8&&frame<=11,bitmap.getPixel(256,256)==Color.BLUE);}finally{bitmap.recycle();}}
  assertEquals(2,SketchEditOps.execute(context,"sketch_import_reference",placement).getLong("revision"));
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId","animation").put("operationId","undo_key").put("expectedRevision",2));assertThrows(Exception.class,()->SketchDocument.object(document(),"ai_key"));
  SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId","animation").put("operationId","redo_key").put("expectedRevision",3));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId","animation").put("operationId","archive").put("expectedRevision",4));
  JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restored_key","Restored AI key");try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(SketchDocument.canonical(provenance),SketchDocument.canonical(SketchDocument.object(sketches.get(restored.getString("sketchId")),"ai_key").getJSONObject("reference").getJSONObject("localDrawing")));}
 }
 @Test public void manualButtonPlacesSameBoundDraftAndRetryDoesNotDuplicateIt()throws Exception {
  context.getSharedPreferences("com.rezoxnemesis.videostudio.LocalDrawingActivity",Context.MODE_PRIVATE).edit().putString("lastRequest_animation","key_fixture").commit();
  try(org.robolectric.android.controller.ActivityController<LocalDrawingActivity> controller=Robolectric.buildActivity(LocalDrawingActivity.class,new Intent(context,LocalDrawingActivity.class).putExtra("sketchId","animation").putExtra("frameIndex",8)).create().start().resume().visible()){
   LocalDrawingActivity activity=controller.get();idle(activity);
   // getPreferences is Activity-local; set through the actual Activity for portability.
   activity.getPreferences(Context.MODE_PRIVATE).edit().putString("lastRequest_animation","key_fixture").commit();
   button(activity.getWindow().getDecorView(),"Refresh references").performClick();idle(activity);
   android.widget.Button place=button(activity.getWindow().getDecorView(),"Place draft as an animation key");assertNotNull("Manual AI key placement must be available",place);
   ((android.widget.EditText)field(activity,"keyExposure")).setText("4");place.performClick();idle(activity);
   JSONObject doc=document();assertEquals(2,doc.getLong("revision"));JSONObject object=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0);assertEquals(8,object.getInt("frame"));assertEquals(4,object.getInt("exposure"));assertEquals(outputSha,object.getJSONObject("reference").getJSONObject("localDrawing").getString("outputSha256"));
   button(activity.getWindow().getDecorView(),"Retry last key placement").performClick();idle(activity);assertEquals(2,document().getLong("revision"));assertEquals(1,document().getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
  }
 }
 private static void idle(Object target)throws Exception {((java.util.concurrent.ExecutorService)field(target,"worker")).submit(()->{}).get(30,java.util.concurrent.TimeUnit.SECONDS);org.robolectric.shadows.ShadowLooper.idleMainLooper();}
 private static Object field(Object target,String name)throws Exception {java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private static android.widget.Button button(android.view.View v,String text){if(v instanceof android.widget.Button&&text.contentEquals(((android.widget.Button)v).getText()))return(android.widget.Button)v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.widget.Button found=button(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
 @Test public void failedOrChangedDraftAndWrongAssetNeverMutateTheTarget()throws Exception {
  String before=SketchDocument.canonical(document());record.put("state","failed");saveRecord();assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference",placement));assertEquals(before,SketchDocument.canonical(document()));
  record.put("state","completed");saveRecord();JSONObject wrong=SketchDocument.copy(placement).put("assetId","wrong_asset");assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference",wrong));assertEquals(before,SketchDocument.canonical(document()));
  Files.write(output.toPath(),new byte[]{1,2,3});assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference",placement));assertEquals(before,SketchDocument.canonical(document()));
 }
 @Test public void characterLibraryReusesTheKeyWithProvenanceAndAnExplicitExposure()throws Exception {
  SketchEditOps.execute(context,"sketch_import_reference",placement);
  SketchEditOps.execute(context,"sketch_component_save",new JSONObject().put("componentId","young_man_key").put("sketchId","animation").put("operationId","save_pose").put("expectedRevision",2).put("name","Young man - AI key fixture").put("category","character").put("frameIndex",8).put("layerIds",new JSONArray().put("layer_1")));
  SketchEditOps.execute(context,"sketch_component_apply",new JSONObject().put("componentId","young_man_key").put("sketchId","animation").put("operationId","reuse_key").put("expectedRevision",2).put("frameIndex",16).put("exposure",2));
  JSONObject reused=document().getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0);assertEquals(16,reused.getInt("frame"));assertEquals(2,reused.getInt("exposure"));assertEquals(outputSha,reused.getJSONObject("reference").getJSONObject("localDrawing").getString("outputSha256"));
 }
 @Test public void archivedPromptCannotBeAlteredWithoutBreakingItsIdentity()throws Exception {
  SketchEditOps.execute(context,"sketch_import_reference",placement);JSONObject doc=document();
  SketchDocument.object(doc,"ai_key").getJSONObject("reference").getJSONObject("localDrawing").getJSONObject("request").getJSONObject("settings").put("prompt","An altered drawing instruction");
  assertThrows(Exception.class,()->SketchDocument.validate(doc));
 }
 @Test public void assetBytesMustMatchChosenOutputEvenIfProjectAndAssetIdsMatch()throws Exception {
  File replacement=new File(context.getFilesDir(),"replacement.png");Bitmap b=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);new Canvas(b).drawColor(Color.RED);try(FileOutputStream out=new FileOutputStream(replacement)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project p=projects.get(placement.getString("projectId"));p.asset(placement.getString("assetId")).uri=Uri.fromFile(replacement).toString();projects.save(p);placement.put("sourceProjectUpdatedAt",projects.get(p.id).updatedAt);
  assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference",placement));assertEquals(1,document().getLong("revision"));assertEquals(outputSha,SketchProjectStore.fileHash(output));
 }
 @Test public void committedImportReplaysAfterOptionalStudyFilesAreGone()throws Exception {
  SketchEditOps.execute(context,"sketch_import_reference",placement);assertTrue(output.delete());
  assertEquals(2,SketchEditOps.execute(context,"sketch_import_reference",placement).getLong("revision"));assertEquals(1,document().getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
 }
 @Test public void strictPlacementRequiresChosenClockAndRejectsUnknownOrCoercedValues()throws Exception {
  for(String field:new String[]{"frame","exposure"}){JSONObject missing=SketchDocument.copy(placement);missing.remove(field);assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference",missing));}
  for(Object bad:new Object[]{JSONObject.NULL,"true",true,new JSONObject().put("requestId","key_fixture").put("outputSha256",outputSha).put("accepted",true)}){JSONObject p=SketchDocument.copy(placement).put("localDrawing",bad);assertThrows(Exception.class,()->SketchEditOps.execute(context,"sketch_import_reference",p));}
  assertEquals(1,document().getLong("revision"));
 }
}
