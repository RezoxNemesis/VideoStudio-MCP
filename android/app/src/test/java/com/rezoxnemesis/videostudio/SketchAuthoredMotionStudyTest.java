package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Ordinary native archive/edit/animation/render calls; no outside drawing or motion engine. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAuthoredMotionStudyTest {
 @Test public void executeNativeAuthoredMotionStudy()throws Exception{
  String input=System.getenv("VIDEOSTUDIO_AUTHORED_MOTION_MANIFEST");Assume.assumeTrue("Explicit private native motion requests required",input!=null);ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{run(new JSONObject(new String(Files.readAllBytes(new File(input).toPath()),StandardCharsets.UTF_8)));return null;}catch(Exception e){throw new RuntimeException(e);}}).get(900,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
 private final Context context=RuntimeEnvironment.getApplication();private final JSONArray calls=new JSONArray();
 private JSONObject load(String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
 private JSONObject call(String action,JSONObject request)throws Exception{JSONObject result=SketchEditOps.execute(context,action,request);calls.put(new JSONObject().put("action",action).put("request",request).put("receipt",result));return result;}
 private JSONObject animation(String action,JSONObject request)throws Exception{JSONObject result=SketchAnimationOps.execute(context,action,request);calls.put(new JSONObject().put("action",action).put("request",request).put("receipt",result));return result;}
 private void edits(String id,JSONArray batches,String prefix)throws Exception{for(int i=0;i<batches.length();i++)call("sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",load(id).getLong("revision")).put("operationId",prefix+i).put("operations",batches.getJSONObject(i).getJSONArray("operations")));}
 private void write(File root,String name,String value)throws Exception{Files.write(new File(root,name).toPath(),value.getBytes(StandardCharsets.UTF_8));}
 private void run(JSONObject manifest)throws Exception{
  String study=manifest.getString("studyId");File root=new File(manifest.getString("output")),frames=new File(root,"frames");assertTrue(frames.mkdirs()||frames.isDirectory());File source=new File(manifest.getString("sourceArchive"));JSONObject restored=SketchPaintArchive.restoreFile(context,source,SketchProjectStore.fileHash(source),study,"Authored native motion working study");String id=restored.getString("sketchId");
  edits(id,manifest.getJSONArray("checkpointBatches"),"checkpoint_");JSONObject checked=load(id);assertEquals("REVIEWED",SketchLayeredDrawing.summary(checked).getString("status"));
  int count=manifest.getInt("frameCount"),fps=manifest.getInt("fps");String layoutAnimation=study+"_layout",layoutId=study+"_poses",finalAnimation=study+"_acting",outputId=study+"_baked";
  JSONObject layoutProgram=new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",count).put("fps",fps).put("rigs",new JSONArray());
  animation("sketch_animation_save",new JSONObject().put("animationId",layoutAnimation).put("sketchId",id).put("sourceRevision",checked.getLong("revision")).put("expectedRevision",0).put("operationId","save_layout").put("program",layoutProgram));
  animation("sketch_animation_bake",new JSONObject().put("animationId",layoutAnimation).put("expectedRevision",1).put("operationId","bake_layout").put("outputSketchId",layoutId));
  edits(layoutId,manifest.getJSONArray("poseBatches"),"pose_");JSONObject prepared=load(layoutId),program=manifest.getJSONObject("program");assertEquals(count,program.getInt("frameCount"));assertEquals(fps,program.getInt("fps"));
  JSONObject saved=animation("sketch_animation_save",new JSONObject().put("animationId",finalAnimation).put("sketchId",layoutId).put("sourceRevision",prepared.getLong("revision")).put("expectedRevision",0).put("operationId","save_acting").put("program",program));
  animation("sketch_animation_bake",new JSONObject().put("animationId",finalAnimation).put("expectedRevision",1).put("operationId","bake_acting").put("outputSketchId",outputId));JSONObject baked=load(outputId);assertEquals(count,baked.getInt("frameCount"));assertFalse(baked.has("layeredDrawing"));
  int images=0;JSONArray layers=baked.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++)if("image".equals(objects.getJSONObject(j).getString("kind")))images++;}assertEquals("Imported reference pixels cannot appear in the authored film output",0,images);
  JSONArray hashes=new JSONArray();try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,baked)){for(int frame=0;frame<count;frame++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Native study cancelled");File png=new File(frames,String.format(java.util.Locale.ROOT,"%04d.png",frame));Bitmap image=renderer.render(frame);try(FileOutputStream out=new FileOutputStream(png)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}hashes.put(new JSONObject().put("frame",frame).put("presentationTimeUs",NativeFrameClock.presentationTimeUs(frame,fps)).put("sha256",SketchProjectStore.fileHash(png)));}}
  SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",outputId).put("expectedRevision",baked.getLong("revision")).put("operationId","motion_archive"));Files.copy(archive.file.toPath(),new File(root,"editable-motion.vssketch").toPath());
  write(root,"native-calls.json",calls.toString(2));write(root,"document.json",baked.toString(2));write(root,"frame-hashes.json",hashes.toString(2));write(root,"motion-evidence.json",new JSONObject().put("studyId",study).put("sourceProof",manifest.getJSONObject("sourceProof")).put("appVersion",AppProtocol.APP_VERSION).put("renderer","VideoStudio SketchAnimationCompiler / SketchFrameRenderer, Android native Canvas under Robolectric").put("phoneExecution",false).put("androidCodecExecution",false).put("audioGenerated",false).put("audioAuditioned",false).put("visualStatus","UNREVIEWED").put("scenarioCount",1).put("logicalFrames",count).put("fps",fps).put("durationUs",NativeFrameClock.presentationTimeUs(count,fps)).put("referencePixelsInArtwork",false).put("cameraOrbit",false).put("limitations",manifest.getString("limitations")).put("animationReceipt",saved).put("archive",archive.metadata).toString(2));
 }
}
