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
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
/** Existing editable programmes, Undo and archives carry the explicit hair recipe; no new media route. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanFringePersistenceTest {
 final Context context=RuntimeEnvironment.getApplication();
 JSONObject load(String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
 void edit(String id,long revision,String op,JSONObject operation)throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",op).put("operations",new JSONArray().put(operation)));}
 @Test public void retainedFringeSurvivesOrdinaryHistoryComponentsAndEditableArchiveWithIdenticalNativeFrames()throws Exception{
  String id="fringe_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",640).put("frameCount",48));
  edit(id,1,"actor",new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","actor").put("fit",true).put("settings",new JSONObject().put("figureStyle","anatomy_sketch").put("hairStyle","long").put("fringeStyle","center_part")).put("preset","listen"));JSONObject original=load(id),human=SketchDocument.copy(SketchDocument.object(original,"actor_rig").getJSONObject("human"));assertEquals("center_part",human.getJSONObject("settings").getString("fringeStyle"));
  JSONObject swept=SketchDocument.copy(human);swept.getJSONObject("settings").remove("fringeStyle");edit(id,2,"swept",new JSONObject().put("type","update_object").put("objectId","actor_rig").put("patch",new JSONObject().put("human",swept)));SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","undo"));assertEquals("center_part",SketchDocument.object(load(id),"actor_rig").getJSONObject("human").getJSONObject("settings").getString("fringeStyle"));
  String component="fringe_component_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",id).put("expectedRevision",4).put("operationId","save").put("name","Native parted fringe").put("category","pose").put("frameIndex",12));String target="fringe_target_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",target).put("operationId","create").put("width",512).put("height",640).put("frameCount",48));SketchComponentLibrary.execute(context,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","apply").put("frameIndex",0));JSONObject placed=load(target).getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0);assertEquals("center_part",placed.getJSONObject("human").getJSONObject("settings").getString("fringeStyle"));
  ExecutorService worker=Executors.newSingleThreadExecutor();SketchPaintArchive.Prepared archive;try{archive=worker.submit(()->SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","archive"))).get(60,TimeUnit.SECONDS);}finally{worker.shutdownNow();}Uri uri=Uri.parse("content://owned.documents/fringe_"+UUID.randomUUID()+".vssketch");Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,new FileInputStream(archive.file));JSONObject restored=SketchLocalArchive.restore(context,uri,"restore_"+UUID.randomUUID(),null);JSONObject d=load(restored.getString("sketchId"));assertEquals("center_part",SketchDocument.object(d,"actor_rig").getJSONObject("human").getJSONObject("settings").getString("fringeStyle"));
  try(SketchFrameRenderer a=new SketchFrameRenderer(context,original);SketchFrameRenderer b=new SketchFrameRenderer(context,d)){for(int frame:new int[]{0,12,24,47}){Bitmap x=a.render(frame,false),y=b.render(frame,false);try{assertTrue("Actual native fringe key geometry must survive save/reopen atframe"+frame,x.sameAs(y));}finally{x.recycle();y.recycle();}}}
 }
}
