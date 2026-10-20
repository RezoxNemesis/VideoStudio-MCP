package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.util.Base64;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;
/** Executes ordinary native authoring requests; every artwork pixel comes from the product renderer. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAuthoredSceneStudyTest {
 @Test public void executeActualAuthoredSceneRequests()throws Exception{
  String file=System.getenv("VIDEOSTUDIO_AUTHORED_SCENE_MANIFEST");Assume.assumeTrue("Explicit private study manifest",file!=null);
  ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{run(new JSONObject(new String(Files.readAllBytes(new File(file).toPath()),StandardCharsets.UTF_8)));return null;}catch(Exception e){throw new RuntimeException(e);}}).get(600,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
 private void run(JSONObject manifest)throws Exception{
  Context context=RuntimeEnvironment.getApplication();File root=new File(manifest.getString("output"));assertTrue(root.mkdirs()||root.isDirectory());JSONArray calls=new JSONArray();
  File source=new File(manifest.getString("sourceArchive"));String op=manifest.getString("studyId");JSONObject restored=SketchPaintArchive.restoreFile(context,source,SketchProjectStore.fileHash(source),op,"Authored manga scene study");String id=restored.getString("sketchId");JSONArray batches=manifest.getJSONArray("batches");
  for(int b=0;b<batches.length();b++){
   JSONObject batch=batches.getJSONObject(b),doc=load(context,id);JSONObject request=new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("operationId","authored_"+b).put("operations",batch.getJSONArray("operations"));
   JSONObject receipt=SketchEditOps.execute(context,"sketch_edit",request);calls.put(new JSONObject().put("action","sketch_edit").put("request",request).put("receipt",receipt));doc=load(context,id);
   render(context,doc,new File(root,batch.getString("name")+"-artwork.png"));
   JSONObject comparison=SketchEditOps.execute(context,"sketch_layered_drawing_preview",new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("view","comparison").put("stageOnly",manifest.optBoolean("stageOnly",false)).put("maxDimension",1024));png(comparison,new File(root,batch.getString("name")+"-comparison.png"));
   write(new File(root,"native-calls.json"),calls.toString(2));write(new File(root,"document.json"),doc.toString(2));
  }
  JSONObject doc=load(context,id);JSONArray crops=manifest.optJSONArray("referenceCrops");if(crops!=null)for(int i=0;i<crops.length();i++){JSONObject crop=crops.getJSONObject(i);JSONObject preview=SketchEditOps.execute(context,"sketch_layered_drawing_preview",new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("view",crop.optString("view","reference")).put("stageOnly",!"reference".equals(crop.optString("view","reference"))&&manifest.optBoolean("stageOnly",false)).put("maxDimension",1024).put("crop",crop.getJSONObject("bounds")));png(preview,new File(root,crop.optString("view","reference")+"-"+crop.getString("name")+".png"));}
  write(new File(root,"native-calls.json"),calls.toString(2));write(new File(root,"document.json"),doc.toString(2));
  render(context,doc,new File(root,"final-artwork.png"));JSONObject finalPreview=SketchEditOps.execute(context,"sketch_layered_drawing_preview",new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("view","comparison").put("stageOnly",manifest.optBoolean("stageOnly",false)).put("maxDimension",1024));png(finalPreview,new File(root,"final-comparison.png"));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("operationId","archive_"+op));Files.copy(archive.file.toPath(),new File(root,"editable-study.vssketch").toPath());
  JSONObject report=new JSONObject().put("studyId",op).put("sourceProof",manifest.has("sourceProof")?manifest.getJSONObject("sourceProof"):new JSONObject().put("status","UNRECORDED")).put("appVersion",AppProtocol.APP_VERSION).put("renderer","VideoStudio SketchFrameRenderer / Android native Canvas under Robolectric").put("phoneExecution",false).put("sourceAssistedReferenceOnly",true).put("referencePixelsInArtwork",false).put("visualStatus",manifest.getString("visualStatus")).put("reviewNote",manifest.getString("reviewNote")).put("nativeBatches",batches.length()).put("animationScenarios",0).put("archive",archive.metadata).put("workflow",SketchLayeredDrawing.summary(doc));write(new File(root,"study-evidence.json"),report.toString(2));
 }
 private void write(File f,String s)throws Exception{Files.write(f.toPath(),s.getBytes(StandardCharsets.UTF_8));}
 private JSONObject load(Context c,String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(c)){return store.get(id);}}
 private void png(JSONObject preview,File file)throws Exception{Files.write(file.toPath(),Base64.decode(preview.getJSONObject("sketchPreviewImage").getString("base64"),Base64.DEFAULT));}
 private void render(Context c,JSONObject doc,File file)throws Exception{try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){Bitmap frame=renderer.render(0);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(frame.compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame.recycle();}}}
}
