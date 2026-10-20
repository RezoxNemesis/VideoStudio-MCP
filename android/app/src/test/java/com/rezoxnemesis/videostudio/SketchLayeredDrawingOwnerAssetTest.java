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
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in real owner reference admission. This never claims a completed redraw. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLayeredDrawingOwnerAssetTest {
 @Test public void ownerScreenshotRemainsReadOnlyReferenceAndCannotPassAsAuthoredDrawing()throws Exception{
  String input=System.getenv("VIDEOSTUDIO_OWNER_DRAWING_ASSET"),output=System.getenv("VIDEOSTUDIO_OWNER_DRAWING_EVIDENCE");
  Assume.assumeTrue("Explicit private owner fixture required",input!=null&&output!=null);
  ExecutorService worker=Executors.newSingleThreadExecutor();
  try{worker.submit(()->{try{execute(new File(input),new File(output));return null;}catch(Exception e){throw new RuntimeException(e);}}).get(120,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
 private static void execute(File source,File output)throws Exception{
  Context context=RuntimeEnvironment.getApplication();assertTrue(source.isFile());assertTrue(output.mkdirs()||output.isDirectory());String id="owner_layered_admission";
  String uploadSha=SketchProjectStore.fileHash(source);ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Owner layer drawing reference only");
  ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(source),"owner-test-reference.jpg","image/jpeg",0,source.length());
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",416).put("height",864).put("frameCount",1).put("background","#00000000"));
  SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","reference").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide"));
  JSONObject crop=new JSONObject().put("x",80.0/576).put("y",211.0/1280).put("width",416.0/576).put("height",864.0/1280);
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","focus_reference").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","guide").put("patch",new JSONObject().put("referenceCrop",crop).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))))));
  JSONArray parts=new JSONArray();for(String part:new String[]{"rear_character","sword","front_character","front_hair","front_clothing","rear_clothing","background","lettering"})parts.put(new JSONObject().put("id",part).put("name",part.replace('_',' ')));
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","begin").put("expectedRevision",3).put("operations",new JSONArray().put(new JSONObject().put("type","layered_drawing_begin").put("referenceObjectId","guide").put("prefix","manga").put("parts",parts))));
  JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);store.verifyReferences(doc);}
  assertEquals(uploadSha,SketchDocument.object(doc,"guide").getJSONObject("reference").getString("sourceSha256"));
  assertThrows(IllegalArgumentException.class,()->SketchEditOps.execute(context,"sketch_layered_drawing_preview",new JSONObject().put("sketchId",id).put("expectedRevision",4).put("view",JSONObject.NULL)));
  JSONObject preview=SketchEditOps.execute(context,"sketch_layered_drawing_preview",new JSONObject().put("sketchId",id).put("expectedRevision",4).put("view","reference").put("maxDimension",1024));
  assertTrue(NativeCommandResultPolicy.successful("sketch_layered_drawing_preview",preview));
  Files.write(new File(output,"owner-reference-native-preview.png").toPath(),Base64.decode(preview.getJSONObject("sketchPreviewImage").getString("base64"),Base64.DEFAULT));
  try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){Bitmap artwork=renderer.render(0,false);try{int[] row=new int[artwork.getWidth()];for(int y=0;y<artwork.getHeight();y++){artwork.getPixels(row,0,row.length,0,y,row.length,1);for(int pixel:row)assertEquals("Locked reference never appears in authored export",0,pixel>>>24);}}finally{artwork.recycle();}}
  assertThrows(IllegalStateException.class,()->SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","empty_review").put("expectedRevision",4).put("operations",new JSONArray().put(new JSONObject().put("type","layered_drawing_review").put("stage","construction").put("reviewedRevision",4).put("status","ACCEPTABLE").put("note","This must reject: reference pixels are not authored marks.")))));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","editable_checkpoint"));Files.copy(archive.file.toPath(),new File(output,"owner-reference-prepared-NOT-DRAWN.vssketch").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,archive.metadata.getString("archiveSha256"),"restore_owner","Restored owner drawing reference");assertNotNull(restored);
  JSONObject evidence=new JSONObject().put("appVersion",AppProtocol.APP_VERSION).put("renderer","VideoStudio Android Canvas under Robolectric native graphics").put("phoneExecution",false).put("uploadSha256",uploadSha).put("nativeReferenceCrop",crop).put("sourceRevision",4).put("technicalStatus","PASS").put("visualStatus","UNREVIEWED").put("authoredDrawingCreated",false).put("referencePixelsInArtwork",false).put("emptyArtworkAcceptanceRejected",true).put("archive",archive.metadata).put("workflow",SketchLayeredDrawing.summary(doc));
  Files.write(new File(output,"owner-asset-evidence.json").toPath(),evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
}
