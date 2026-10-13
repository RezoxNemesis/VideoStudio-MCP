package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Spinner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Authoring/persistence evidence. No fixture claims a GPU pose preview or a rebuilt scene. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class Cinematic3DDraftFormsTest {
    @Test public void boundedActorMaterialsAndStylesPreserveOriginalDraftAndOtherActor() throws Exception {
        JSONObject original=Cinematic3DTimelineTest.timeline();String before=original.toString();
        JSONObject black=new JSONObject().put("preset","chibi").put("heightScale",.9).put("buildScale",1.1).put("headScale",1.2)
                .put("material",new JSONObject().put("color",Cinematic3DDraftForms.vector(.2,.4,.6)).put("metallic",.8).put("roughness",.2));
        JSONObject edited=Cinematic3DDraftForms.editActor(original,"black",black);
        assertEquals("chibi",edited.getJSONObject("actors").getJSONObject("black").getString("preset"));assertEquals("athletic",edited.getJSONObject("actors").getJSONObject("white").getString("preset"));
        JSONObject styled=Cinematic3DDraftForms.editStyle(edited,0,"anime",.75,false);assertEquals(.75,styled.getJSONArray("shots").getJSONObject(0).getJSONObject("motionStyle").getDouble("intensity"),0);
        assertEquals(before,original.toString());assertFalse(edited.getJSONArray("shots").getJSONObject(0).has("motionStyle"));
        try{Cinematic3DDraftForms.editActor(original,"black",new JSONObject(black.toString()).put("heightScale",1.3));fail("Unbounded actor admitted");}catch(IllegalArgumentException expected){}
        try{Cinematic3DDraftForms.editStyle(edited,0,"anime",1.1,false);fail("Unbounded style admitted");}catch(IllegalArgumentException expected){}
        assertFalse(Cinematic3DDraftForms.editStyle(styled,0,"grounded",0,true).getJSONArray("shots").getJSONObject(0).has("motionStyle"));
    }
    @Test public void eulerConversionNormalizesSafelyAndPoseEditsKeepAllSixteenLocalJointsAndBothRoots() throws Exception {
        JSONArray q=Cinematic3DDraftForms.eulerXYZ(90,0,0);assertEquals(Math.sqrt(.5),q.getDouble(0),1e-12);assertEquals(Math.sqrt(.5),q.getDouble(1),1e-12);assertEquals(0,q.getDouble(2),0);
        JSONArray huge=Cinematic3DDraftForms.quaternion(1e300,1e300,0,0);assertEquals(Math.sqrt(.5),huge.getDouble(0),1e-12);
        try{Cinematic3DDraftForms.quaternion(0,0,0,0);fail("Zero quaternion admitted");}catch(IllegalArgumentException expected){}
        JSONObject original=Cinematic3DTimelineTest.timeline(),first=Cinematic3DDraftForms.editPose(original,0,"black",6,0,q,Cinematic3DDraftForms.vector(-.5,0,.2));
        JSONObject second=Cinematic3DDraftForms.editPose(first,0,"white",12,.5,Cinematic3DDraftForms.eulerXYZ(0,20,0),Cinematic3DDraftForms.vector(.5,.1,0));
        JSONArray keys=second.getJSONArray("shots").getJSONObject(0).getJSONArray("poseKeyframes");assertEquals(2,keys.length());
        JSONObject actors=keys.getJSONObject(1).getJSONObject("actors");assertEquals(16,actors.getJSONObject("black").getJSONArray("rotations").length());assertEquals(16,actors.getJSONObject("white").getJSONArray("rotations").length());
        assertEquals(first.getJSONArray("shots").getJSONObject(0).getJSONArray("poseKeyframes").getJSONObject(0).getJSONObject("actors").getJSONObject("black").toString(),actors.getJSONObject("black").toString());
        assertEquals(Cinematic3DDraftForms.vector(1,0,0,0).toString(),actors.getJSONObject("white").getJSONArray("rotations").getJSONArray(11).toString());
        assertFalse(original.getJSONArray("shots").getJSONObject(0).has("poseKeyframes"));
        JSONObject replaced=Cinematic3DDraftForms.editPose(second,0,"white",13,.5,Cinematic3DDraftForms.eulerXYZ(0,0,15),Cinematic3DDraftForms.vector(.6,.1,0));assertEquals(2,replaced.getJSONArray("shots").getJSONObject(0).getJSONArray("poseKeyframes").length());
        assertEquals(1,Cinematic3DDraftForms.removePose(replaced,0,.5).getJSONArray("shots").getJSONObject(0).getJSONArray("poseKeyframes").length());
    }
    @Test public void plantedIntervalsAreSortedAndOverlapsRejectWithoutChangingAcceptedKeys() throws Exception {
        JSONObject original=Cinematic3DTimelineTest.timeline();JSONObject later=Cinematic3DDraftForms.editContact(original,0,-1,"black","leading",.6,1,Cinematic3DDraftForms.vector(-.4,.05,.1),false);
        JSONObject both=Cinematic3DDraftForms.editContact(later,0,-1,"black","leading",0,.4,Cinematic3DDraftForms.vector(-.6,.05,.1),false);JSONArray contacts=both.getJSONArray("shots").getJSONObject(0).getJSONArray("footContacts");
        assertEquals(0,contacts.getJSONObject(0).getDouble("startPhase"),0);assertEquals(.6,contacts.getJSONObject(1).getDouble("startPhase"),0);String before=both.toString();
        try{Cinematic3DDraftForms.editContact(both,0,-1,"black","leading",.2,.7,Cinematic3DDraftForms.vector(0,0,0),false);fail("Overlapping contact admitted");}catch(IllegalArgumentException expected){}
        assertEquals(before,both.toString());assertFalse(original.getJSONArray("shots").getJSONObject(0).has("footContacts"));
        assertEquals(1,Cinematic3DDraftForms.editContact(both,0,0,"black","leading",0,1,Cinematic3DDraftForms.vector(0,0,0),true).getJSONArray("shots").getJSONObject(0).getJSONArray("footContacts").length());
    }
    @Test public void rebuildTemplateBindsRevisionAndOrderedUniqueOwnedAnchorsWithoutClaimingOriginalReconstruction() throws Exception {
        ProjectStore.Project project=new ProjectStore.Project();project.id="source_project";project.updatedAt=17;JSONObject draft=Cinematic3DTimelineTest.timeline();JSONArray anchors=new JSONArray();
        for(String id:new String[]{"source_b","source_a","source_b"})anchors.put(new JSONObject().put("assetId",id).put("sha256","a".repeat(64)));
        for(String id:new String[]{"source_a","source_b"}){ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=id;asset.mime="image/png";project.assets.add(asset);}draft.getJSONArray("shots").getJSONObject(0).put("sourceAnchors",anchors);
        JSONObject template=Cinematic3DDraftForms.rebuildTemplate(project,17,draft);assertEquals("blender",template.getString("backend"));assertEquals("source_b",template.getJSONArray("assetIds").getString(0));assertEquals("source_a",template.getJSONArray("assetIds").getString(1));
        assertEquals(0,template.getJSONObject("controls").getInt("renderFrames"));assertEquals(0,template.getJSONObject("controls").getJSONArray("conditioningFrames").length());assertFalse(template.has("programId"));assertFalse(template.has("requestId"));assertFalse(template.has("originalImageReconstruction"));assertEquals(3,template.getJSONObject("controls").getJSONObject("timeline").getJSONArray("shots").getJSONObject(0).getJSONArray("sourceAnchors").length());
        try{Cinematic3DDraftForms.rebuildTemplate(project,16,draft);fail("Stale project admitted");}catch(IllegalStateException expected){}
        project.assets.remove(0);try{Cinematic3DDraftForms.rebuildTemplate(project,17,draft);fail("Foreign source anchor admitted");}catch(IllegalArgumentException expected){}
    }
    @Test public void humanActorFormBlocksOldScenePlaybackAndRetainsDraftThroughPrivateStateRecreation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Human draft safety");JSONObject original=Cinematic3DTimelineTest.timeline();Bundle state=new Bundle();state.putLong("sourceRevision",project.updatedAt);state.putString("draft",original.toString());state.putString("acceptedTimeline",original.toString());state.putString("bundleId","original_bundle");state.putString("programId","original_program");state.putString("programSha","a".repeat(64));state.putBoolean("bindingReady",true);
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(state).start().resume().visible();Bundle retained=new Bundle();
        try{
            Cinematic3DStudioActivity activity=controller.get();Method open=activity.getClass().getDeclaredMethod("editActor",String.class);open.setAccessible(true);open.invoke(activity,"black");AlertDialog form=ShadowAlertDialog.getLatestAlertDialog();Spinner preset=find(form.getWindow().getDecorView(),Spinner.class);assertNotNull(preset);preset.setSelection(3);form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue((Boolean)field(activity,"sceneRebuildRequired"));assertFalse((Boolean)field(activity,"bindingReady"));assertTrue((Boolean)field(activity,"dirty"));assertEquals("chibi",((JSONObject)field(activity,"timeline")).getJSONObject("actors").getJSONObject("black").getString("preset"));assertFalse(((JSONObject)field(activity,"acceptedTimeline")).has("actors"));
            button(activity.getWindow().getDecorView(),"Live 3D preview").performClick();assertNull(shadowOf(activity).getNextStartedActivity());button(activity.getWindow().getDecorView(),"Save timeline").performClick();assertEquals(project.updatedAt,projects.get(project.id).updatedAt);
            button(activity.getWindow().getDecorView(),"Build updated scene").performClick();Intent handoff=shadowOf(activity).getNextStartedActivity();assertNotNull(handoff);JSONObject template=new JSONObject(handoff.getStringExtra("requestTemplate"));assertEquals("chibi",template.getJSONObject("controls").getJSONObject("timeline").getJSONObject("actors").getJSONObject("black").getString("preset"));
            controller.saveInstanceState(retained);assertTrue(retained.containsKey("draftStateId"));assertFalse(retained.containsKey("draft"));
        }finally{controller.pause().stop().destroy();}
        ActivityController<Cinematic3DStudioActivity> reopened=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(retained).start().resume().visible();
        try{assertTrue((Boolean)field(reopened.get(),"sceneRebuildRequired"));assertFalse((Boolean)field(reopened.get(),"bindingReady"));assertEquals("original_program",field(reopened.get(),"programId"));assertEquals("chibi",((JSONObject)field(reopened.get(),"timeline")).getJSONObject("actors").getJSONObject("black").getString("preset"));}finally{reopened.pause().stop().destroy();}
    }
    @Test public void largeValidatedPoseDraftUsesAnImmutableHashBoundFileHandoffInsteadOfBinderPayload() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Large pose rebuild");JSONObject timeline=Cinematic3DTimelineTest.timeline();JSONArray keys=new JSONArray();
        // Stay within the real 65,536-node limit while exercising a genuinely large, precise pose payload.
        for(int i=0;i<64;i++){
            JSONObject key=Cinematic3DDraftForms.poseBase(timeline,0,i/63d);
            for(String actor:Cinematic3DDraftForms.ACTORS)for(int joint=0;joint<6;joint++)
                key.getJSONObject("actors").getJSONObject(actor).getJSONArray("rotations").put(joint,Cinematic3DDraftForms.eulerXYZ((i+joint)*.33,12.4+joint*.2,5.6+i*.1));
            keys.put(key);
        }
        JSONArray shots=new JSONArray();
        for(int i=0;i<4;i++)shots.put(new JSONObject(timeline.getJSONArray("shots").getJSONObject(0).toString()).put("id","pose_shot_"+i).put("poseKeyframes",new JSONArray(keys.toString())));
        timeline.put("shots",shots);timeline=Cinematic3DTimeline.validate(timeline);assertTrue(Cinematic3DDraftForms.rebuildTemplate(project,project.updatedAt,timeline).toString().length()>200_000);
        Bundle state=new Bundle();state.putLong("sourceRevision",project.updatedAt);state.putString("draft",timeline.toString());state.putString("acceptedTimeline",Cinematic3DTimelineTest.timeline().toString());state.putString("bundleId","bundle");state.putBoolean("dirty",true);state.putBoolean("sceneRebuildRequired",true);
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(state).start().resume().visible();
        try{button(controller.get().getWindow().getDecorView(),"Build updated scene").performClick();Intent next=shadowOf(controller.get()).getNextStartedActivity();assertNotNull(next);assertNull(next.getStringExtra("requestTemplate"));String name=next.getStringExtra("requestTemplateFileName");assertTrue(name.matches("[0-9a-f-]{36}\\.json"));java.io.File file=new java.io.File(context.getFilesDir(),"open_pipeline_templates/"+name);byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());assertEquals(Cinematic3DTimeline.hash(bytes),next.getStringExtra("requestTemplateSha256"));assertEquals(4,new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("controls").getJSONObject("timeline").getJSONArray("shots").length());assertEquals(project.updatedAt,projects.get(project.id).updatedAt);}finally{controller.pause().stop().destroy();}
    }
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Button button(View root,String title){if(root instanceof Button&&title.contentEquals(((Button)root).getText()))return(Button)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button found=button(((ViewGroup)root).getChildAt(i),title);if(found!=null)return found;}return null;}
    private static <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
}
