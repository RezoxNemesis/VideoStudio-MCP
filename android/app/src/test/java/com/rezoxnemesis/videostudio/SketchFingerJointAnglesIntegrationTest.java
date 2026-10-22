package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Strict native publication, ordinary component/Undo and portable archive binding. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchFingerJointAnglesIntegrationTest {
 final Context context=RuntimeEnvironment.getApplication();
 @Test public void addPatchUndoComponentAndArchiveKeepAnglesAndNativeFramesWhileInvalidPatchIsAtomic()throws Exception{
  String id="finger_joint_"+UUID.randomUUID();create(id);JSONObject p=SketchFingerJointAnglesTest.pose(0).put("fingerJointAngles",SketchFingerJointAnglesTest.triple("index",90,80,35));JSONObject d=SketchFingerJointAnglesTest.document(new JSONArray().put(p)),object=SketchDocument.copy(SketchDocument.object(d,"hand_rig"));edit(id,1,"add",new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object));JSONObject initial=load(id);assertAngles(initial,90);
  JSONObject changed=SketchDocument.copy(object.getJSONObject("hand"));changed.getJSONArray("poses").getJSONObject(0).getJSONObject("fingerJointAngles").put("index",new JSONArray().put(105).put(100).put(45));edit(id,2,"patch",new JSONObject().put("type","update_object").put("objectId","hand_rig").put("patch",new JSONObject().put("hand",changed)));assertAngles(load(id),105);
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","undo"));assertAngles(load(id),90);SketchEditOps.execute(context,"sketch_redo",new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","redo"));JSONObject accepted=load(id);assertAngles(accepted,105);
  String frozen=SketchHandPartTestSupport.canonical(accepted);JSONObject invalid=SketchDocument.copy(SketchDocument.object(accepted,"hand_rig").getJSONObject("hand"));invalid.getJSONArray("poses").getJSONObject(0).put("fingerJointAngles",new JSONObject());assertThrows(Exception.class,()->edit(id,5,"reject_empty",new JSONObject().put("type","update_object").put("objectId","hand_rig").put("patch",new JSONObject().put("hand",invalid))));assertEquals("Invalid pose cannot publish revision or geometry",frozen,SketchHandPartTestSupport.canonical(load(id)));
  String component="finger_component_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",id).put("expectedRevision",5).put("operationId","save").put("name","Source-free explicit finger joints").put("category","pose").put("frameIndex",0));String target="finger_target_"+UUID.randomUUID();create(target);SketchComponentLibrary.execute(context,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","apply").put("frameIndex",0));JSONObject placed=load(target);JSONObject hand=placed.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0).getJSONObject("hand");assertEquals(105,hand.getJSONArray("poses").getJSONObject(0).getJSONObject("fingerJointAngles").getJSONArray("index").getDouble(0),0);
  try(SketchFrameRenderer original=new SketchFrameRenderer(context,accepted);SketchFrameRenderer imported=new SketchFrameRenderer(context,placed)){SketchFingerJointAnglesTest.same(original.render(0,false),imported.render(0,false),"Component retains actual explicit joint pixels");}
  ExecutorService worker=Executors.newSingleThreadExecutor();SketchPaintArchive.Prepared archive;try{archive=worker.submit(()->SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",5).put("operationId","archive"))).get(60,TimeUnit.SECONDS);}finally{worker.shutdownNow();}JSONObject receipt=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_"+UUID.randomUUID(),"Restored explicit finger joints"),restored=load(receipt.getString("sketchId"));assertAngles(restored,105);
  try(SketchFrameRenderer original=new SketchFrameRenderer(context,accepted);SketchFrameRenderer roundtrip=new SketchFrameRenderer(context,restored)){for(int frame:new int[]{0,36,95})SketchFingerJointAnglesTest.same(original.render(frame,false),roundtrip.render(frame,false),"Portable joint-angle frame"+frame);}assertEquals("Component/archive cannot mutate original",frozen,SketchHandPartTestSupport.canonical(load(id)));
 }
 private void create(String id)throws Exception{SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",512).put("frameCount",96).put("fps",24));}
 private JSONObject load(String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
 private void edit(String id,long revision,String op,JSONObject operation)throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",op).put("operations",new JSONArray().put(operation)));}
 private void assertAngles(JSONObject document,double mcp)throws Exception{assertEquals(mcp,SketchDocument.object(document,"hand_rig").getJSONObject("hand").getJSONArray("poses").getJSONObject(0).getJSONObject("fingerJointAngles").getJSONArray("index").getDouble(0),0);}
}
