package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Private native execution/consistency evidence, never a likeness acceptance test. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TempleNativeFrameConsistencyStudyTest {
 @Test public void nativeEyeCelsPreserveEveryPixelOutsideTheExplicitEyeRegionAndReopenExactly()throws Exception {
  String input=System.getenv("VIDEOSTUDIO_TEMPLE_FRAME_STUDY");Assume.assumeTrue("Explicit owner Temple study",input!=null);
  java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
  try{worker.submit(()->{try{runNativeStudy(input);return null;}catch(Exception error){throw new RuntimeException(error);}}).get(300,java.util.concurrent.TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
 private void runNativeStudy(String input)throws Exception {
  JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(input).toPath()),StandardCharsets.UTF_8));
  Context context=RuntimeEnvironment.getApplication();File output=new File(manifest.getString("output")),frames=new File(output,"frames");assertTrue(frames.mkdirs()||frames.isDirectory());
  File original=new File(manifest.getString("originalUpload"));String originalHash=manifest.getString("originalUploadSha256");assertEquals(originalHash,SketchProjectStore.fileHash(original));
  // Keep the original on its own locked native reference surface. It is never
  // placed into the independently authored figure or the frame-consistency render.
  ProjectStore projects=new ProjectStore(context);ProjectStore.Project owner=projects.create("Temple owner reference");
  ProjectStore.Asset inputAsset=projects.registerImportedAsset(owner.id,android.net.Uri.fromFile(original),original.getName(),"image/jpeg",0,original.length());
  String referenceId="temple_original_reference";
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",referenceId).put("operationId","create").put("width",1024).put("height",1280).put("frameCount",1));
  SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",referenceId).put("expectedRevision",1).put("operationId","import").put("projectId",owner.id).put("sourceProjectUpdatedAt",projects.get(owner.id).updatedAt).put("assetId",inputAsset.id).put("layerId","layer_1").put("objectId","guide"));
  JSONObject referenceBounds=new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",referenceId).put("expectedRevision",2).put("operationId","fit_original").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","guide").put("patch",new JSONObject().put("bounds",referenceBounds)))));
  JSONArray namedParts=new JSONArray().put(new JSONObject().put("id","figure").put("name","Figure")).put(new JSONObject().put("id","background").put("name","Background"));
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",referenceId).put("expectedRevision",3).put("operationId","lock_original").put("operations",new JSONArray().put(new JSONObject().put("type","layered_drawing_begin").put("referenceObjectId","guide").put("prefix","temple").put("parts",namedParts))));
  JSONObject referencePreview=SketchEditOps.execute(context,"sketch_layered_drawing_preview",new JSONObject().put("sketchId",referenceId).put("expectedRevision",4).put("view","reference").put("frameIndex",0).put("maxDimension",720).put("crop",new JSONObject().put("x",.4).put("y",.17).put("width",.17).put("height",.15)));
  Files.write(new File(output,"original-reference-face-native-preview.png").toPath(),android.util.Base64.decode(referencePreview.getJSONObject("sketchPreviewImage").getString("base64"),android.util.Base64.DEFAULT));
  File archive=new File(manifest.getString("sourceArchive"));String archiveHash=SketchProjectStore.fileHash(archive);
  JSONObject restored=SketchPaintArchive.restoreFile(context,archive,archiveHash,"temple_native_frame_check","Temple native frame consistency study");
  try(SketchProjectStore store=new SketchProjectStore(context)){
   JSONObject doc=store.get(restored.getString("sketchId"));String before=SketchDocument.canonical(doc);assertEquals(640,doc.getInt("width"));assertEquals(1280,doc.getInt("height"));assertEquals(48,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));
   JSONArray layers=doc.getJSONArray("layers");int paint=0,paths=0;
   for(int i=0;i<layers.length();i++){JSONObject layer=layers.getJSONObject(i);assertEquals(0,layer.getJSONArray("keys").length());JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);String kind=object.getString("kind");assertNotEquals("No imported/generated raster guide in authored artwork","image",kind);if("paint".equals(kind))paint++;if("path".equals(kind))paths++;assertEquals("No rig/transform animation",0,object.getJSONArray("keys").length());}}
   JSONArray roi=manifest.getJSONArray("allowedChangePixels"),results=new JSONArray();int[] baseline=null;int w=doc.getInt("width"),h=doc.getInt("height");int changedFrames=0;
   try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){
    for(int f=0;f<48;f++){
     Bitmap image=renderer.render(f);int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);File png=new File(frames,String.format(java.util.Locale.ROOT,"%04d.png",f));try(FileOutputStream stream=new FileOutputStream(png)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{image.recycle();}
     if(f==0)baseline=pixels;int changed=0,outside=0;
     for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(pixels[y*w+x]!=baseline[y*w+x]){changed++;if(x<roi.getInt(0)||x>=roi.getInt(2)||y<roi.getInt(1)||y>=roi.getInt(3))outside++;}
     if(changed>0)changedFrames++;results.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(png)).put("changedPixels",changed).put("outsideEyeRegionChangedPixels",outside));
     Files.write(new File(output,"frame-evidence.json").toPath(),results.toString(2).getBytes(StandardCharsets.UTF_8));
     assertEquals("Body/costume/silhouette changed at native frame"+f,0,outside);
     if(f>=18&&f<=23)assertTrue("Eye cel must actually change at frame"+f,changed>0);else assertEquals("Open eye return must be pixel exact at frame"+f,0,changed);
    }
   }
   assertEquals(6,changedFrames);assertEquals(before,SketchDocument.canonical(store.get(doc.getString("id"))));assertEquals(originalHash,SketchProjectStore.fileHash(original));assertEquals(archiveHash,SketchProjectStore.fileHash(archive));
   SketchPaintArchive.Prepared exported=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",doc.getLong("revision")).put("operationId","temple_frame_archive"));Files.copy(exported.file.toPath(),new File(output,"editable-temple-blink.vssketch").toPath());
   JSONObject evidence=new JSONObject().put("technicalStatus","PASS").put("visualStatus","UNREVIEWED").put("appVersion",AppProtocol.APP_VERSION).put("sourceProof",manifest.getJSONObject("sourceProof")).put("renderer","VideoStudio SketchFrameRenderer / Android native Canvas under Robolectric").put("phoneExecution",false).put("androidCodecExecution",false).put("audioGenerated",false).put("originalUploadUnchanged",true).put("authoredSourceUnchanged",true).put("rasterGuideObjects",0).put("paintObjects",paint).put("penObjects",paths).put("layers",layers.length()).put("frameCount",48).put("fps",24).put("durationSeconds",2).put("animationScenarios",1).put("allowedChangePixels",roi).put("outsideEyeRegionChangedPixels",0).put("reopenedEyesPixelExact",true).put("rigsUsed",false).put("cameraOrbit",false).put("assetLikenessCertified",false).put("fullAssetSceneRecreated",false).put("archive",exported.metadata);
   Files.write(new File(output,"native-frame-study-evidence.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));
  }
 }
}
