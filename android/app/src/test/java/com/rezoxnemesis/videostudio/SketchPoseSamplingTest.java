package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchPoseSamplingTest {
 interface Work{void run()throws Exception;}
 void worker(Work action)throws Exception{ExecutorService pool=Executors.newSingleThreadExecutor();try{pool.submit(()->{try{action.run();return null;}catch(Exception e){throw new RuntimeException(e);}}).get(60,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
 final Context context=RuntimeEnvironment.getApplication();
 JSONObject source()throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","pose_source_"+java.util.UUID.randomUUID()).put("width",320).put("height",480).put("frameCount",100));d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","arm").put("kind","line").put("frame",0).put("exposure",100).put("points",new JSONArray("[[.4,.4,1],[.6,.4,1]]"))));try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(d,"create");}return d;}
 JSONObject program()throws Exception{return new JSONObject("{schema:'videostudio.sketch-animation.v1',frameCount:100,fps:24,rigs:[{id:'actor',joints:[{id:'root',x:.4,y:.4},{id:'tip',parent:'root',x:.6,y:.4}],bindings:[{objectId:'arm',jointId:'root'}],poses:[{frame:0,interpolation:'linear',rotations:{root:0}},{frame:99,interpolation:'linear',rotations:{root:90}}]}],camera:[{frame:0,x:.1,y:.05,zoom:1.2,rotation:0,interpolation:'hold'}],cameraLayerIds:['layer_1']}");}
 JSONObject save(String id,JSONObject source,JSONObject p,long revision,String op)throws Exception{return SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId",id).put("sketchId",source.getString("id")).put("sourceRevision",source.getLong("revision")).put("expectedRevision",revision).put("operationId",op).put("program",p));}
 JSONObject get(JSONObject args)throws Exception{return SketchAnimationOps.execute(context,"sketch_animation_get",args);}
 JSONObject joint(JSONObject result,String name)throws Exception{JSONArray joints=result.getJSONObject("poseSample").getJSONArray("joints");for(int i=0;i<joints.length();i++)if(name.equals(joints.getJSONObject(i).getString("jointId")))return joints.getJSONObject(i);throw new AssertionError("Missing sampled joint "+name);}
 @Test public void sampledSavedPoseUsesFrozenSourceAndPreCameraCoordinatesWithoutMutatingEither()throws Exception{worker(()->{
  JSONObject source=source(),p=program();String id="pose_"+java.util.UUID.randomUUID();JSONObject saved=save(id,source,p,0,"save"),legacy=get(new JSONObject().put("animationId",id));String sourceBefore=SketchDocument.canonical(source),programBefore=SketchDocument.canonical(p),legacyBefore=SketchDocument.canonical(legacy);
  JSONObject result=get(new JSONObject().put("animationId",id).put("expectedRevision",1).put("sampleFrame",99)),tip=joint(result,"tip");assertEquals(99,result.getJSONObject("poseSample").getInt("frame"));assertEquals(.4,tip.getDouble("x"),1e-12);assertEquals(256d/480,tip.getDouble("y"),1e-12);assertEquals(.48,tip.getDouble("displayX"),1e-12);assertEquals(.59,tip.getDouble("displayY"),1e-12);assertEquals(90,tip.getDouble("rotation"),1e-12);assertFalse(result.has("sourceDocument"));assertFalse(result.has("document"));assertEquals(saved.getString("sourceSha256"),result.getString("sourceSha256"));assertEquals(saved.getString("programSha256"),result.getString("programSha256"));
  SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",source.getString("id")).put("expectedRevision",1).put("operationId","later_source_edit").put("operations",new JSONArray().put(new JSONObject().put("type","set_canvas").put("name","Later human source edit"))));assertEquals(SketchDocument.canonical(result),SketchDocument.canonical(get(new JSONObject().put("animationId",id).put("expectedRevision",1).put("sampleFrame",99))));
  assertEquals(sourceBefore,SketchDocument.canonical(SketchAnimationOps.load(context,id).getJSONObject("sourceDocument")));assertEquals(programBefore,SketchDocument.canonical(p));assertEquals(legacyBefore,SketchDocument.canonical(get(new JSONObject().put("animationId",id))));
 });}
 @Test public void samplingRequiresWorkerAndPreservesCancellation()throws Exception{
  assertThrows(IllegalStateException.class,()->SketchPoseCoordinates.sample(new JSONObject(),new JSONObject(),0));
  worker(()->{Thread.currentThread().interrupt();try{assertThrows(java.io.InterruptedIOException.class,()->SketchPoseCoordinates.sample(new JSONObject(),new JSONObject(),0));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}});
 }
 @Test public void savedPoseRejectsStaleRevisionAndStrictInvalidFrames()throws Exception{worker(()->{
  JSONObject source=source(),p=program();String id="pose_"+java.util.UUID.randomUUID();save(id,source,p,0,"save");JSONObject args=new JSONObject().put("animationId",id).put("expectedRevision",1).put("sampleFrame",99);JSONObject updated=SketchDocument.copy(p);updated.getJSONArray("rigs").getJSONObject(0).getJSONArray("poses").getJSONObject(1).getJSONObject("rotations").put("root",45);save(id,source,updated,1,"update");assertThrows(IllegalStateException.class,()->get(args));JSONObject latest=get(SketchDocument.copy(args).put("expectedRevision",2));assertEquals(45,joint(latest,"tip").getDouble("rotation"),1e-12);
  for(Object bad:new Object[]{-1,100,1.5,"1",false,JSONObject.NULL})assertThrows(Exception.class,()->get(SketchDocument.copy(args).put("expectedRevision",2).put("sampleFrame",bad)));assertThrows(Exception.class,()->get(new JSONObject().put("animationId",id).put("sampleFrame",0)));assertThrows(Exception.class,()->get(SketchDocument.copy(args).put("expectedRevision",2).put("unknown",true)));assertEquals(2,get(new JSONObject().put("animationId",id)).getLong("revision"));assertEquals(SketchDocument.canonical(get(new JSONObject().put("animationId",id))),SketchDocument.canonical(get(new JSONObject().put("animationId",id).put("expectedRevision",2))));
 });}
}
