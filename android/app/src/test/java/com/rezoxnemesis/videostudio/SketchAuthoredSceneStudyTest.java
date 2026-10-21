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
  String op=manifest.getString("studyId"),id;
  if(manifest.has("newSketch")){
   assertTrue("Fresh native studies explicitly exclude imported image pixels",manifest.optBoolean("plainSource",false));
   id=op;JSONObject create=SketchDocument.copy(manifest.getJSONObject("newSketch")).put("sketchId",id).put("operationId","new");
   calls.put(new JSONObject().put("action","sketch_create").put("request",create).put("receipt",SketchEditOps.execute(context,"sketch_create",create)));
  }else if(manifest.has("referenceSource")){
   JSONObject input=manifest.getJSONObject("referenceSource");File source=new File(input.getString("path"));assertEquals("Owner reference upload identity",input.getString("expectedUploadSha256"),SketchProjectStore.fileHash(source));ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Owner profile reference only");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,android.net.Uri.fromFile(source),source.getName(),"image/jpeg",0,source.length());id=op;
   JSONObject create=new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",1).put("background","#00000000");calls.put(new JSONObject().put("action","sketch_create").put("request",create).put("receipt",SketchEditOps.execute(context,"sketch_create",create)));
   JSONObject admit=new JSONObject().put("sketchId",id).put("operationId","reference").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false);calls.put(new JSONObject().put("action","sketch_import_reference").put("request",admit).put("receipt",SketchEditOps.execute(context,"sketch_import_reference",admit)));JSONObject reference=SketchDocument.object(load(context,id),"guide").getJSONObject("reference");assertEquals(input.getString("expectedUploadSha256"),reference.getString("sourceSha256"));
   JSONObject fit=new JSONObject().put("sketchId",id).put("operationId","fit_source").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","set_canvas").put("width",reference.getInt("width")).put("height",reference.getInt("height"))).put(new JSONObject().put("type","update_object").put("objectId","guide").put("patch",new JSONObject().put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)))));calls.put(new JSONObject().put("action","sketch_edit").put("request",fit).put("receipt",SketchEditOps.execute(context,"sketch_edit",fit)));
  }else{File source=new File(manifest.getString("sourceArchive"));JSONObject restored=SketchPaintArchive.restoreFile(context,source,SketchProjectStore.fileHash(source),op,"Authored manga scene study");id=restored.getString("sketchId");}
  JSONArray batches=manifest.getJSONArray("batches");
  for(int b=0;b<batches.length();b++){
   JSONObject batch=batches.getJSONObject(b),doc=load(context,id);JSONArray operations=batch.optJSONArray("operations");
   if(batch.has("contourFills")){if(operations!=null)throw new IllegalArgumentException("Choose direct edits or native source-bound fills");operations=new JSONArray();JSONArray fills=batch.getJSONArray("contourFills"),ids=new JSONArray();java.util.Set<String> distinct=new java.util.LinkedHashSet<>();for(int i=0;i<fills.length();i++)distinct.add(fills.getJSONObject(i).getString("contourObjectId"));for(String contour:distinct)ids.put(contour);JSONObject inspectionRequest=new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("contourObjectIds",ids);JSONObject inspection=SketchEditOps.execute(context,"sketch_inspect",inspectionRequest);calls.put(new JSONObject().put("action","sketch_inspect").put("request",inspectionRequest).put("receipt",inspection));java.util.Map<String,String> hashes=new java.util.HashMap<>();JSONArray sources=inspection.getJSONArray("contourPaintSources");for(int i=0;i<sources.length();i++)hashes.put(sources.getJSONObject(i).getString("contourObjectId"),sources.getJSONObject(i).getString("contourSha256"));for(int i=0;i<fills.length();i++){JSONObject fill=SketchDocument.copy(fills.getJSONObject(i)).put("type","paint_fill").put("expectedContourSha256",hashes.get(fills.getJSONObject(i).getString("contourObjectId")));operations.put(fill);}}
   if(operations==null)throw new IllegalArgumentException("Native edit operations required");JSONObject request=new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("operationId","authored_"+b).put("operations",operations);
   JSONObject receipt=SketchEditOps.execute(context,"sketch_edit",request);calls.put(new JSONObject().put("action","sketch_edit").put("request",request).put("receipt",receipt));doc=load(context,id);
   render(context,doc,new File(root,batch.getString("name")+"-artwork.png"));
   JSONObject comparison=preview(context,doc,calls,doc.has("layeredDrawing")?"comparison":"artwork",manifest.optBoolean("stageOnly",false),null);png(comparison,new File(root,batch.getString("name")+(doc.has("layeredDrawing")?"-comparison.png":"-native-preview.png")));
   write(new File(root,"native-calls.json"),calls.toString(2));write(new File(root,"document.json"),doc.toString(2));
  }
  JSONObject doc=load(context,id);JSONArray crops=manifest.optJSONArray("referenceCrops");if(crops!=null)for(int i=0;i<crops.length();i++){JSONObject crop=crops.getJSONObject(i);String view=crop.optString("view",doc.has("layeredDrawing")?"reference":"artwork");JSONObject preview=preview(context,doc,calls,view,!"reference".equals(view)&&manifest.optBoolean("stageOnly",false),crop.getJSONObject("bounds"));png(preview,new File(root,view+"-"+crop.getString("name")+".png"));}
  write(new File(root,"native-calls.json"),calls.toString(2));write(new File(root,"document.json"),doc.toString(2));
  render(context,doc,new File(root,"final-artwork.png"));JSONObject finalPreview=preview(context,doc,calls,doc.has("layeredDrawing")?"comparison":"artwork",manifest.optBoolean("stageOnly",false),null);png(finalPreview,new File(root,doc.has("layeredDrawing")?"final-comparison.png":"native-artwork-preview.png"));
  // The full-document render intentionally includes currently visible adjacent
  // passes. Save an explicitly isolated native stage preview as well, rather than
  // mistaking construction guides for marks in the refinement/clean layer.
  if(doc.has("layeredDrawing")){JSONObject isolated=preview(context,doc,calls,"artwork",true,null);png(isolated,new File(root,"native-isolated-stage-preview.png"));}
  else{assertTrue("Explicit owned plain-source mode",manifest.optBoolean("plainSource",false));JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++)assertNotEquals("Owned plain studies cannot import reference image pixels","image",objects.getJSONObject(j).getString("kind"));}}write(new File(root,"native-calls.json"),calls.toString(2));
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("operationId","archive_"+op));Files.copy(archive.file.toPath(),new File(root,"editable-study.vssketch").toPath());
  JSONObject report=new JSONObject().put("studyId",op).put("sourceProof",manifest.has("sourceProof")?manifest.getJSONObject("sourceProof"):new JSONObject().put("status","UNRECORDED")).put("appVersion",AppProtocol.APP_VERSION).put("renderer","VideoStudio SketchFrameRenderer / Android native Canvas under Robolectric").put("phoneExecution",false).put("sourceAssistedReferenceOnly",doc.has("layeredDrawing")).put("referencePixelsInArtwork",false).put("visualStatus",manifest.getString("visualStatus")).put("reviewNote",manifest.getString("reviewNote")).put("nativeBatches",batches.length()).put("animationScenarios",0).put("archive",archive.metadata).put("workflow",doc.has("layeredDrawing")?SketchLayeredDrawing.summary(doc):new JSONObject().put("enabled",false));write(new File(root,"study-evidence.json"),report.toString(2));
 }
 private JSONObject preview(Context context,JSONObject doc,JSONArray calls,String view,boolean stageOnly,JSONObject crop)throws Exception{
  JSONObject args=new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",doc.getLong("revision")).put("frameIndex",0).put("maxDimension",doc.has("layeredDrawing")?1024:720);if(crop!=null)args.put("crop",crop);String action;
  if(doc.has("layeredDrawing")){action="sketch_layered_drawing_preview";args.put("view",view).put("stageOnly",stageOnly);}else{if(!"artwork".equals(view)||stageOnly)throw new IllegalArgumentException("Owned plain studies expose artwork preview, not a reference comparison or isolated stage");action="sketch_preview";}
  JSONObject reply=SketchEditOps.execute(context,action,args);calls.put(new JSONObject().put("action",action).put("request",args).put("receipt",reply));return reply;
 }
 private void write(File f,String s)throws Exception{Files.write(f.toPath(),s.getBytes(StandardCharsets.UTF_8));}
 private JSONObject load(Context c,String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(c)){return store.get(id);}}
 private void png(JSONObject preview,File file)throws Exception{Files.write(file.toPath(),Base64.decode(preview.getJSONObject("sketchPreviewImage").getString("base64"),Base64.DEFAULT));}
 private void render(Context c,JSONObject doc,File file)throws Exception{try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){Bitmap frame=renderer.render(0);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(frame.compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame.recycle();}}}
}
