package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Evidence orchestration only: all figure authoring, options, correction and output use native product surfaces. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeCorrectionWorkflowStudyTest {
 @Test public void reviewOriginalHumanMotionAndApplyAppOwnedArmRecipeWithoutRetiming()throws Exception{
  String destination=System.getenv("VIDEOSTUDIO_CORRECTION_EVIDENCE");Assume.assumeTrue(destination!=null);File root=new File(destination);assertFalse("Fresh generation required",root.exists());assertTrue(root.mkdirs());
  ExecutorService executor=Executors.newSingleThreadExecutor();try{Future<?> work=executor.submit(()->{try{run(root);}catch(Exception e){throw new RuntimeException(e);}});try{work.get(10,TimeUnit.MINUTES);}catch(ExecutionException e){throw new AssertionError(e.getCause());}}finally{executor.shutdownNow();}
 }
 private static void run(File root)throws Exception{
  Context c=RuntimeEnvironment.getApplication();JSONObject create=new JSONObject().put("sketchId","motion_review").put("operationId","create").put("width",1280).put("height",720).put("fps",24).put("frameCount",96).put("background","#FFFAF8F3");SketchEditOps.execute(c,"sketch_create",create);json(root,"create-request.json",create);
  JSONObject settings=new JSONObject().put("view","front").put("figureStyle","anatomy_sketch").put("skinColor","#FFF1EEE8").put("hairColor","#FF555150").put("trouserColor","#FFD5D0C9").put("size",1.55).put("floorY",.91);
  // Original short listening motion remains editable; the recipe changes four arm channels.
  JSONObject operation=new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","person").put("settings",settings).put("preset","listen").put("blinkTrack",false);
  JSONObject edit=new JSONObject().put("sketchId","motion_review").put("operationId","draw").put("expectedRevision",1).put("operations",new JSONArray().put(operation));SketchEditOps.execute(c,"sketch_edit",edit);json(root,"author-request.json",edit);
  JSONObject source;try(SketchProjectStore store=new SketchProjectStore(c)){source=store.get("motion_review");}String sourceHash=SketchCorrectionOps.hash(source);json(root,"source-document.json",source);
  JSONObject start=id().put("expectedRevision",2).put("target","Review resting arm bends and preserve the original body motion, source artwork and clock").put("frameOffset",0).put("frameLimit",24).put("frameStep",4).put("motionRequired",true);json(root,"start-response.json",SketchCorrectionOps.execute(c,"sketch_correction_start",start));json(root,"recipe-options.json",SketchCorrectionOps.execute(c,"sketch_correction_options",id().put("objectId","person_rig")));
  JSONObject note=id().put("expectedReviewRevision",1).put("noteId","arms").put("outcome","NEEDS_REWORK").put("note","Resting arms are too angular. Preview the app-owned neutral arm option; inspect shoulder shape, hands and contact separately.").put("objectId","person_rig").put("frameStart",0).put("frameEnd",95);SketchCorrectionOps.execute(c,"sketch_correction_note",note);
  JSONObject propose=id().put("expectedReviewRevision",2).put("proposalId","neutral").put("summary","Try the native neutral arm recipe without replacing the character or changing the shot clock").put("noteIds",new JSONArray().put("arms")).put("recipeId","neutral_arms").put("objectId","person_rig");JSONObject proposed=SketchCorrectionOps.execute(c,"sketch_correction_propose",propose);json(root,"proposal-request.json",propose);json(root,"proposal-response.json",proposed);
  try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(sourceHash,SketchCorrectionOps.hash(store.get("motion_review")));}
  JSONObject applied=SketchCorrectionOps.execute(c,"sketch_correction_apply",id().put("expectedReviewRevision",3).put("proposalId","neutral").put("expectedRevision",2).put("operationId","apply_neutral"));json(root,"apply-response.json",applied);JSONObject candidate;try(SketchProjectStore store=new SketchProjectStore(c)){candidate=store.get("motion_review");}json(root,"candidate-document.json",candidate);
  JSONObject beforeActor=SketchDocument.object(source,"person_rig"),afterActor=SketchDocument.object(candidate,"person_rig");String[] unchanged={"rootX","rootY","rootZ","bodyLean","headTilt","gazeX","gazeY","hipX","hipY","hipZ","footLeftX","footLeftY","footLeftZ","footRightX","footRightY","footRightZ","nearHandX","nearHandY","nearHandZ","farHandX","farHandY","farHandZ"};
  JSONArray hashes=new JSONArray();int different=0;try(SketchFrameRenderer before=new SketchFrameRenderer(c,source);SketchFrameRenderer after=new SketchFrameRenderer(c,candidate)){
   for(int f=0;f<96;f++){JSONObject a=SketchHumanAnimation.pose(beforeActor,f),b=SketchHumanAnimation.pose(afterActor,f);for(String key:unchanged)assertEquals("Preserve motion "+key+" frame"+f,a.getDouble(key),b.getDouble(key),0);Bitmap x=before.render(f),y=after.render(f);try{boolean changed=!x.sameAs(y);if(changed)different++;File old=new File(root,String.format("before/frame-%04d.png",f)),next=new File(root,String.format("after/frame-%04d.png",f));png(old,x);png(next,y);hashes.put(new JSONObject().put("frame",f).put("before",SketchProjectStore.fileHash(old)).put("after",SketchProjectStore.fileHash(next)).put("changed",changed));}finally{x.recycle();y.recycle();}}
  }
  assertTrue(different>0);json(root,"Native-Frame-Hashes.json",new JSONObject().put("frames",hashes));SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId","motion_review").put("expectedRevision",3).put("operationId","undo_neutral"));try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(SketchDocument.canonical(source.getJSONArray("layers")),SketchDocument.canonical(store.get("motion_review").getJSONArray("layers")));}
  json(root,"Study-Receipt.json",new JSONObject().put("technical","PASS").put("visual","UNREVIEWED").put("framesPerGeneration",96).put("fps",24).put("width",1280).put("height",720).put("durationSeconds",4).put("nativePngCount",192).put("differentFramePairs",different).put("preservedMotionChannelCount",unchanged.length).put("preservedMotionFrameChecks",96).put("sourceUnchangedBeforeApply",true).put("nativeUndoArtworkEqual",true).put("renderer","Actual VideoStudio native SketchEditOps / SketchCorrectionOps / Android Canvas in workspace Robolectric").put("professionalQualityCertified",false).put("phoneExecution",false).put("liveMcpVerified",false).put("uniqueAnimationScenarios",1));
 }
 private static JSONObject id()throws Exception{return new JSONObject().put("sketchId","motion_review").put("reviewId","arm_review");}
 private static void json(File root,String name,JSONObject value)throws Exception{File file=new File(root,name);file.getParentFile().mkdirs();try(Writer out=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)){out.write(value.toString(2));}}
 private static void png(File file,Bitmap image)throws Exception{file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}
}
