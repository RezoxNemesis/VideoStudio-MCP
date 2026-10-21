package com.rezoxnemesis.videostudio;
import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowVMRuntime;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Private real-weight study through the same operation used by the Activity and MCP. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDrawingEndToEndStudyTest {
 @Test public void realNativeOperationPublishesOneCandidateReplaysAndKeepsSourceAndArchive()throws Exception{
  String path=System.getenv("VIDEOSTUDIO_LOCAL_DRAWING_STUDY"),model=System.getenv("VIDEOSTUDIO_LOCAL_DRAWING_MODEL");Assume.assumeTrue("Explicit private model/reference study",path!=null&&model!=null);
  JSONObject study=new JSONObject(new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8));File root=new File(study.getString("output"));assertTrue(root.mkdirs()||root.isDirectory());Context context=RuntimeEnvironment.getApplication();
  // These are admission fixtures, not evidence of Android phone hardware.
  ShadowVMRuntime.setIs64Bit(true);ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();memory.availMem=10L*1024*1024*1024;memory.totalMem=16L*1024*1024*1024;Shadows.shadowOf((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
  LocalDrawingModels models=new LocalDrawingModels(context);JSONObject catalogue=models.catalogue();File cached=new File(context.getFilesDir(),"creative_workspace/models/cloud_restore/"+LocalDrawingModels.ID+"_native_download");assertTrue(cached.mkdirs()||cached.isDirectory());JSONArray files=catalogue.getJSONArray("files");
  for(int i=0;i<files.length();i++){JSONObject item=files.getJSONObject(i);String name=item.getString("path");File target=new File(cached,name);assertTrue(target.getParentFile().mkdirs()||target.getParentFile().isDirectory());Files.createLink(target.toPath(),new File(model,name).toPath());}
  // Exercise real checksum validation, license retention and transactional activation
  // from cached public-weight files. This does not claim a network download test.
  models.install((n,message)->{});assertTrue(new File(models.directory(),"MODEL_LICENSE.txt").isFile());
  assertTrue(models.status().getBoolean("modelInstalled"));assertTrue(models.status().getBoolean("runtimeAvailable"));
  File original=new File(study.getString("sourcePath"));assertEquals(study.getString("sourceSha256"),SketchProjectStore.fileHash(original));ProjectStore projects=new ProjectStore(context);ProjectStore.Project owner=projects.create("Original reference preserved");ProjectStore.Asset input=projects.registerImportedAsset(owner.id,Uri.fromFile(original),original.getName(),"image/jpeg",0,original.length());
  ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{
   String id="local_neural_original";SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("name","Original manga guide").put("width",512).put("height",512).put("frameCount",1));
   SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","reference").put("expectedRevision",1).put("projectId",owner.id).put("sourceProjectUpdatedAt",projects.get(owner.id).updatedAt).put("assetId",input.id).put("layerId","layer_1").put("objectId","guide"));
   try(SketchProjectStore sketches=new SketchProjectStore(context)){
    JSONObject doc=sketches.get(id);String before=SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8));
    JSONObject request=new JSONObject().put("requestId",study.getString("requestId")).put("sketchId",id).put("expectedRevision",doc.getLong("revision")).put("objectId","guide").put("sourceSha256",SketchDocument.object(doc,"guide").getJSONObject("reference").getString("sha256")).put("sourceRect",study.getJSONObject("sourceRect")).put("settings",study.getJSONObject("settings"));
    long start=System.currentTimeMillis();JSONObject result=LocalDrawingOperations.execute(context,"local_drawing_generate",request,(n,s)->Files.write(new File(root,"native-operation-progress.json").toPath(),new JSONObject().put("percent",n).put("stage",s).toString().getBytes(StandardCharsets.UTF_8)));long elapsed=System.currentTimeMillis()-start;
    assertEquals("completed",result.getString("state"));assertEquals("1.23.2",result.getString("runtimeVersion"));assertFalse(result.getBoolean("hostedInference"));AtomicInteger additional=new AtomicInteger();JSONObject replay=LocalDrawingOperations.execute(context,"local_drawing_generate",request,(n,s)->additional.incrementAndGet());assertTrue(replay.getBoolean("replayed"));assertEquals(0,additional.get());assertEquals(result.getString("outputAssetId"),replay.getString("outputAssetId"));
    ProjectStore.Project output=projects.get(result.getString("outputProjectId"));assertNotNull(output);assertEquals(1,output.assets.size());ProjectStore.Asset asset=output.asset(result.getString("outputAssetId"));File image=new File(Uri.parse(asset.uri).getPath());assertEquals(result.getString("outputSha256"),SketchProjectStore.fileHash(image));Files.copy(image.toPath(),new File(root,"native-neural-draft.png").toPath());
    JSONObject preview=LocalDrawingOperations.execute(context,"local_drawing_get",new JSONObject().put("requestId",request.getString("requestId")).put("includePreview",true),null);assertEquals(result.getString("outputSha256"),preview.getJSONObject("localDrawingPreviewImage").getString("sha256"));assertEquals("image/png",preview.getJSONObject("localDrawingPreviewImage").getString("mimeType"));
    assertEquals(before,SketchDocument.sha256(SketchDocument.canonical(sketches.get(id)).getBytes(StandardCharsets.UTF_8)));assertEquals(study.getString("sourceSha256"),SketchProjectStore.fileHash(original));
    String guide="native_ai_raster_guide";SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",guide).put("operationId","create_ai_guide").put("name","AI-generated raster draft · reference, not artist layers").put("width",512).put("height",512).put("frameCount",1));
    SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",guide).put("operationId","import_ai_guide").put("expectedRevision",1).put("projectId",output.id).put("sourceProjectUpdatedAt",output.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","ai_draft_reference").put("guideVisible",true));
    JSONObject guideDocument=sketches.get(guide);SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",guide).put("expectedRevision",guideDocument.getLong("revision")).put("operationId","archive_ai_guide"));Files.copy(archive.file.toPath(),new File(root,"ai-raster-guide.vssketch").toPath());
    JSONObject restored=SketchPaintArchive.restoreFile(context,new File(root,"ai-raster-guide.vssketch"),SketchProjectStore.fileHash(new File(root,"ai-raster-guide.vssketch")),"restored_ai_guide","Restored native AI reference draft");assertNotNull(restored.getString("sketchId"));
    JSONObject evidence=new JSONObject().put("technicalStatus","PASS").put("visualStatus","UNREVIEWED").put("appVersion",AppProtocol.APP_VERSION).put("receipt",result).put("elapsedMs",elapsed).put("nativeArchive",archive.metadata).put("sourceDocumentUnchanged",true).put("originalUploadUnchanged",true).put("idempotentReplay",true).put("sourceProof",study.optJSONObject("sourceProof")==null?new JSONObject().put("status","UNRECORDED"):study.getJSONObject("sourceProof")).put("renderer","VideoStudio LocalDrawingOperations and ONNX Runtime CPU/JNI on host; native Android source preparation/PNG under Robolectric").put("hardwareAdmission","Simulated Android MemoryInfo/64-bit flag; this does not measure phone hardware").put("modelInstallation","Pinned real model files cached by hardlink; actual checksum/license/transactional model installation exercised; network download not exercised").put("phoneExecution",false).put("artistLayersRecovered",false).put("animationScenarios",0);
    Files.write(new File(root,"native-neural-operation-evidence.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    // Removing the optional model must not remove a published candidate or its replay receipt.
    new ModelPackManager(context).uninstall(LocalDrawingModels.ID);assertFalse(models.status().getBoolean("modelInstalled"));assertTrue(new File(model,"unet/weights.pb").isFile());assertEquals("completed",LocalDrawingOperations.execute(context,"local_drawing_generate",request,null).getString("state"));
   }return null;
  }catch(Exception error){
   File failed=new File(context.getFilesDir(),"local_drawing/studies/"+study.getString("requestId"));
   if(failed.isDirectory())try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(failed.toPath())){for(java.nio.file.Path item:(Iterable<java.nio.file.Path>)paths::iterator){java.nio.file.Path target=new File(root,"failed-native-study").toPath().resolve(failed.toPath().relativize(item));if(Files.isDirectory(item))Files.createDirectories(target);else Files.copy(item,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
   throw new RuntimeException(error);}}).get(2400,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
}
