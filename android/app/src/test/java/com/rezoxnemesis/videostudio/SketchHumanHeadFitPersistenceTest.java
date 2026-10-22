package com.rezoxnemesis.videostudio;

import android.content.*;
import android.graphics.Bitmap;
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

/** Existing ordinary human operations must carry one editable proportional identity recipe. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanHeadFitPersistenceTest {
 final Context context=RuntimeEnvironment.getApplication();
 JSONObject load(String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
 void edit(String id,long revision,String op,JSONObject operation)throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",op).put("operations",new JSONArray().put(operation)));}
 JSONObject profile(JSONObject doc,String id)throws Exception{return SketchDocument.object(doc,id).getJSONObject("human").getJSONObject("settings").getJSONObject("headProportions");}
 @Test public void retainedFitSurvivesUndoComponentAndPortableArchiveWithExactNativeFrames()throws Exception{
  String id="head_fit_"+UUID.randomUUID();JSONObject fitting=SketchHeadFitTestSupport.profile(.82,.94,.9);
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",512).put("height",640).put("frameCount",48));
  edit(id,1,"actor",new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","actor").put("fit",true).put("preset","listen").put("settings",SketchHeadFitTestSupport.settings(45,-10).put("headProportions",fitting)));
  JSONObject original=load(id);assertEquals(SketchDocument.canonical(fitting),SketchDocument.canonical(profile(original,"actor_rig")));JSONObject human=SketchDocument.copy(SketchDocument.object(original,"actor_rig").getJSONObject("human"));human.getJSONObject("settings").remove("headProportions");edit(id,2,"clear_fit",new JSONObject().put("type","update_object").put("objectId","actor_rig").put("patch",new JSONObject().put("human",human)));assertFalse(SketchDocument.object(load(id),"actor_rig").getJSONObject("human").getJSONObject("settings").has("headProportions"));
  SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("operationId","undo"));assertEquals(SketchDocument.canonical(fitting),SketchDocument.canonical(profile(load(id),"actor_rig")));
  String component="head_component_"+UUID.randomUUID();SketchComponentLibrary.execute(context,"sketch_component_save",new JSONObject().put("componentId",component).put("sketchId",id).put("expectedRevision",4).put("operationId","save").put("name","Source-free fitted anatomy").put("category","pose").put("frameIndex",12));String target="head_target_"+UUID.randomUUID();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",target).put("operationId","create").put("width",512).put("height",640).put("frameCount",48));SketchComponentLibrary.execute(context,"sketch_component_apply",new JSONObject().put("componentId",component).put("sketchId",target).put("expectedRevision",1).put("operationId","apply").put("frameIndex",0));JSONObject placed=load(target).getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0);assertEquals(SketchDocument.canonical(fitting),SketchDocument.canonical(placed.getJSONObject("human").getJSONObject("settings").getJSONObject("headProportions")));
  ExecutorService worker=Executors.newSingleThreadExecutor();SketchPaintArchive.Prepared archive;try{archive=worker.submit(()->SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",4).put("operationId","archive"))).get(60,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
  Uri uri=Uri.parse("content://owned.documents/head_fit_"+UUID.randomUUID()+".vssketch");Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,new FileInputStream(archive.file));JSONObject restored=SketchLocalArchive.restore(context,uri,"restore_"+UUID.randomUUID(),null);JSONObject reopened=load(restored.getString("sketchId"));assertEquals(SketchDocument.canonical(fitting),SketchDocument.canonical(profile(reopened,"actor_rig")));
  try(SketchFrameRenderer a=new SketchFrameRenderer(context,original);SketchFrameRenderer b=new SketchFrameRenderer(context,reopened)){for(int frame:new int[]{0,12,24,47}){Bitmap x=a.render(frame,false),y=b.render(frame,false);try{assertTrue("Actual native frame must survive archive at"+frame,x.sameAs(y));}finally{x.recycle();y.recycle();}}}
 }
 @Test public void manualControlsOptInToTheSameProfileAndLegacySubmissionsOmitIt()throws Exception{
  try(org.robolectric.android.controller.ActivityController<android.app.Activity> a=Robolectric.buildActivity(android.app.Activity.class).setup()){
   JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","manual_head_fit").put("width",1280).put("height",720).put("frameCount",48).put("fps",24));java.util.List<JSONObject> requests=new ArrayList<>();
   SketchHumanDialog.show(a.get(),doc,(request,callback)->{requests.add(request);callback.accept(new IllegalStateException("Keep controls for test"));});android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();android.view.View root=dialog.getWindow().getDecorView();
   android.widget.CheckBox enabled=(android.widget.CheckBox)find(root,"Character head proportions");assertNotNull("Optional fitting is available manually",enabled);assertFalse(enabled.isChecked());assertFalse("Anatomy-only fitting is disabled for legacy",enabled.isEnabled());dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());assertFalse(humanSettings(requests.get(0)).has("headProportions"));requests.clear();
   ((android.widget.Spinner)find(root,"Figure style")).setSelection(1);org.robolectric.shadows.ShadowLooper.idleMainLooper();assertTrue(enabled.isEnabled());enabled.setChecked(true);
   ((android.widget.EditText)find(root,"Jaw width scale")).setText("0.82");((android.widget.EditText)find(root,"Chin length scale")).setText("0.94");((android.widget.EditText)find(root,"Eye width scale")).setText("0.9");dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());assertEquals(SketchDocument.canonical(SketchHeadFitTestSupport.profile(.82,.94,.9)),SketchDocument.canonical(humanSettings(requests.get(0)).getJSONObject("headProportions")));requests.clear();
   ((android.widget.Spinner)find(root,"Figure style")).setSelection(0);org.robolectric.shadows.ShadowLooper.idleMainLooper();dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(1,requests.size());assertFalse("Switching back to legacy must omit the anatomy profile",humanSettings(requests.get(0)).has("headProportions"));dialog.cancel();
  }
 }
 static JSONObject humanSettings(JSONObject request)throws Exception{JSONArray operations=request.getJSONArray("operations");for(int i=0;i<operations.length();i++)if("add_human_animation".equals(operations.getJSONObject(i).getString("type")))return operations.getJSONObject(i).getJSONObject("settings");throw new AssertionError("Ordinary human operation missing");}
 static android.view.View find(android.view.View view,String description){if(view.getContentDescription()!=null&&description.contentEquals(view.getContentDescription()))return view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View found=find(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
}
