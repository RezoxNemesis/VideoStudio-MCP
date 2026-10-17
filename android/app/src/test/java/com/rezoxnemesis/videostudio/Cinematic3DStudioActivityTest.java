package com.rezoxnemesis.videostudio;

import android.os.Bundle;
import android.os.Parcel;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowAlertDialog;
import android.app.AlertDialog;
import android.widget.ListView;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Phone editor data fixtures; GPU and original-artwork acceptance are separate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DStudioActivityTest {
    @Test public void importedAuthoredCameraExplainsWhyItsKeysRequireARebuild() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Project project=new ProjectStore(context).create("Authored camera portrait");
        Bundle initial=state(project.updatedAt);
        JSONObject bundle=library();
        bundle.getJSONObject("manifest").getJSONArray("scenes").getJSONObject(0).put("cameraReplay","authored");
        initial.putString("editorBundle",bundle.toString());
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,
                new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(initial).start().resume().visible();
        try {
            Cinematic3DStudioActivity activity=controller.get();
            String before=Cinematic3DTimeline.canonical(field(activity,"timeline"));
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            findButton(activity.getWindow().getDecorView(),"Camera keys").performClick();
            assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(((android.widget.TextView)field(activity,"status")).getText().toString().contains("authored camera"));
            assertEquals(before,Cinematic3DTimeline.canonical(field(activity,"timeline")));
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void cameraWorkerCannotOverwriteANewerDraftOrApplyAfterPause() throws Exception {
        for(boolean pause:new boolean[]{false,true}){
            Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Pending camera draft");Bundle initial=state(project.updatedAt);initial.putInt("selectedShot",1);ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(initial).start().resume().visible();CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
            try{Cinematic3DStudioActivity activity=controller.get();ExecutorService worker=(ExecutorService)field(activity,"ownerWorker");worker.submit(()->{blocked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(blocked.await(5,TimeUnit.SECONDS));String before=Cinematic3DTimeline.canonical(field(activity,"timeline"));findButton(activity.getWindow().getDecorView(),"Camera keys").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals("Camera sampling must wait for the worker, leaving the UI draft untouched",before,Cinematic3DTimeline.canonical(field(activity,"timeline")));assertTrue((Boolean)field(activity,"busy"));
                String expected=before;if(pause)controller.pause();else{JSONObject newer=new JSONObject(((JSONObject)field(activity,"timeline")).toString()).put("name","Newer draft accepted elsewhere");setField(activity,"timeline",newer);expected=Cinematic3DTimeline.canonical(newer);}release.countDown();awaitWorker(activity);assertEquals(expected,Cinematic3DTimeline.canonical(field(activity,"timeline")));assertFalse((Boolean)field(activity,"dirty"));assertFalse((Boolean)field(activity,"busy"));assertTrue("A discarded worker must not close or reopen the form",dialog.isShowing());if(pause)controller.resume();
            }finally{release.countDown();controller.pause().stop().destroy();}
        }
    }

    @Test public void workerCameraValidationFailureKeepsTheFormOpenAndShowsTheError() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Camera worker validation");Bundle initial=state(project.updatedAt);initial.putInt("selectedShot",1);JSONObject draft=timeline();draft.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").put("keyframes",new JSONArray().put(new JSONObject().put("phase",0).put("position",new JSONArray(new double[]{0,1,2})).put("target",new JSONArray(new double[]{0,1,0}))));initial.putString("draft",draft.toString());ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(initial).start().resume().visible();
        try{Cinematic3DStudioActivity activity=controller.get();String before=Cinematic3DTimeline.canonical(field(activity,"timeline"));findButton(activity.getWindow().getDecorView(),"Camera keys").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();((android.widget.EditText)dialog.getWindow().getDecorView().findViewWithTag("camera_phase")).setText("1");((android.widget.EditText)dialog.getWindow().getDecorView().findViewWithTag("camera_position_2")).setText("-2");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitWorker(activity);assertTrue(dialog.isShowing());assertEquals(before,Cinematic3DTimeline.canonical(field(activity,"timeline")));android.widget.TextView error=dialog.getWindow().getDecorView().findViewWithTag("camera_error");assertNotNull("Camera validation errors must be visible in the open form",error);assertTrue(error.getText().toString().contains("Invalid camera"));assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());}finally{controller.pause().stop().destroy();}
    }

    @Test public void phoneAuthorsCameraKeysThroughTheSharedOperationsAndRetainsThemOnRecreation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Camera path authoring");Bundle initial=state(project.updatedAt);initial.putInt("selectedShot",1);Intent intent=new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id);Bundle saved=new Bundle();
        ActivityController<Cinematic3DStudioActivity> first=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(initial).start().resume().visible();
        try{Cinematic3DStudioActivity activity=first.get();Button camera=findButton(activity.getWindow().getDecorView(),"Camera keys");assertNotNull("Human camera-path authoring must be reachable",camera);camera.performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();android.widget.EditText phase=dialog.findViewById(android.R.id.content).findViewWithTag("camera_phase"),x=dialog.findViewById(android.R.id.content).findViewWithTag("camera_position_0");phase.setText("0.5");x.setText("3");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitWorker(activity);
            JSONObject expected=Cinematic3DCameraEditOpsTest.apply(timeline(),new JSONArray().put(Cinematic3DCameraEditOpsTest.key("impact",.5,3).put("position",new JSONArray(new double[]{3,1.65,5.5})).put("target",new JSONArray(new double[]{0,1.1,0}))));
            assertEquals(Cinematic3DTimeline.canonical(expected),Cinematic3DTimeline.canonical(field(activity,"timeline")));assertTrue((Boolean)field(activity,"dirty"));assertFalse((Boolean)field(activity,"sceneRebuildRequired"));first.saveInstanceState(saved);
        }finally{first.pause().stop().destroy();}
        ActivityController<Cinematic3DStudioActivity> recreated=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(saved).start().resume().visible();
        try{JSONObject restored=(JSONObject)field(recreated.get(),"timeline");assertEquals(.5,restored.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").getJSONArray("keyframes").getJSONObject(0).getDouble("phase"),0);assertTrue((Boolean)field(recreated.get(),"dirty"));findButton(recreated.get().getWindow().getDecorView(),"Camera keys").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();findButton(dialog.getWindow().getDecorView(),"Remove selected key").performClick();awaitWorker(recreated.get());assertFalse(((JSONObject)field(recreated.get(),"timeline")).getJSONArray("shots").getJSONObject(1).getJSONObject("camera").has("keyframes"));}finally{recreated.pause().stop().destroy();}
    }

    @Test public void invalidCameraFormKeepsTheCurrentDraftAndTheDialogOpen() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Invalid camera draft");Bundle initial=state(project.updatedAt);initial.putInt("selectedShot",1);ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(initial).start().resume().visible();
        try{Cinematic3DStudioActivity activity=controller.get();String before=Cinematic3DTimeline.canonical(field(activity,"timeline"));Button camera=findButton(activity.getWindow().getDecorView(),"Camera keys");assertNotNull(camera);camera.performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();((android.widget.EditText)dialog.findViewById(android.R.id.content).findViewWithTag("camera_phase")).setText("NaN");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());assertEquals(before,Cinematic3DTimeline.canonical(field(activity,"timeline")));assertFalse((Boolean)field(activity,"dirty"));}finally{controller.pause().stop().destroy();}
    }

    @Test public void editingAndReorderingPreserveShotIdentityAndBakedInputs() throws Exception {
        JSONObject original=timeline();
        JSONObject edited=(JSONObject)call("editShot",new Class[]{JSONObject.class,int.class,int.class,int.class,String.class,double.class,double.class,boolean.class,boolean.class,boolean.class},
                original,1,48,4,"macro",61d,.7d,false,true,false);
        JSONObject moved=(JSONObject)call("moveShot",new Class[]{JSONObject.class,int.class,int.class},edited,1,0);
        JSONObject shot=moved.getJSONArray("shots").getJSONObject(0);
        assertEquals("impact",shot.getString("id"));assertEquals("CLASH",shot.getString("action"));
        assertEquals(48,shot.getInt("durationFrames"));assertEquals(4,shot.getInt("hitStopFrames"));
        assertEquals("macro",shot.getJSONObject("camera").getString("mode"));
        assertEquals(61,shot.getJSONObject("camera").getDouble("fovDegrees"),0);
        assertEquals(.7,shot.getJSONObject("camera").getDouble("shakeIntensity"),0);
        assertFalse(shot.getJSONObject("vfx").getBoolean("sparkBurst"));assertTrue(shot.getJSONObject("vfx").getBoolean("slashTrail"));assertFalse(shot.getJSONObject("vfx").getBoolean("rubble"));
        assertEquals("approach",moved.getJSONArray("shots").getJSONObject(1).getString("id"));
        assertEquals(original.getJSONArray("shots").getJSONObject(1).getJSONArray("sourceAnchors").toString(),shot.getJSONArray("sourceAnchors").toString());
        assertEquals("Original draft must remain recoverable until save succeeds",24,original.getJSONArray("shots").getJSONObject(1).getInt("durationFrames"));
    }

    @Test public void impossibleHitStopRejectsWithoutMutatingTheDraft() throws Exception {
        JSONObject original=timeline();String before=original.toString();
        try{call("editShot",new Class[]{JSONObject.class,int.class,int.class,int.class,String.class,double.class,double.class,boolean.class,boolean.class,boolean.class},original,0,24,4,"wide",55d,0d,true,true,true);fail("Non-impact motion must reject an impact hold");}
        catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
        assertEquals(before,original.toString());
    }

    @Test public void cameraControlEditsPreserveAuthoredKeysUntilAnExplicitPresetChoice() throws Exception {
        JSONObject original=timeline();JSONArray keys=new JSONArray().put(new JSONObject().put("phase",0).put("position",new JSONArray(new double[]{0,2,5})).put("target",new JSONArray(new double[]{0,1,0}))).put(new JSONObject().put("phase",1).put("position",new JSONArray(new double[]{1,2,5})).put("target",new JSONArray(new double[]{0,1,0})));original.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").put("keyframes",keys);
        JSONObject edited=(JSONObject)call("editShot",new Class[]{JSONObject.class,int.class,int.class,int.class,String.class,double.class,double.class,boolean.class,boolean.class,boolean.class},original,1,48,4,"orbit",61d,.7d,false,true,false);JSONObject shot=edited.getJSONArray("shots").getJSONObject(1);
        assertTrue("Editing duration, hold and effects must preserve the authored camera path",shot.getJSONObject("camera").has("keyframes"));assertEquals(Cinematic3DTimeline.canonical(keys),Cinematic3DTimeline.canonical(shot.getJSONObject("camera").getJSONArray("keyframes")));
        JSONObject presets=(JSONObject)call("chooseCameraTrack",new Class[]{JSONObject.class,int.class,boolean.class},edited,1,false);assertFalse(presets.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").has("keyframes"));assertTrue("Switching to presets must keep the recoverable draft untouched",edited.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").has("keyframes"));assertEquals(Cinematic3DTimeline.canonical(original.getJSONArray("shots").getJSONObject(1).getJSONArray("sourceAnchors")),Cinematic3DTimeline.canonical(presets.getJSONArray("shots").getJSONObject(1).getJSONArray("sourceAnchors")));
    }

    @Test public void saveRequestUsesAcceptedSourceRevisionAndProgramCas() throws Exception {
        JSONObject request=(JSONObject)call("saveRequest",new Class[]{String.class,long.class,String.class,String.class,String.class,JSONObject.class},"source",321L,"bundle","program","a".repeat(64),timeline());
        assertEquals("source",request.getString("projectId"));assertEquals(321,request.getLong("sourceProjectUpdatedAt"));assertEquals("bundle",request.getString("bundleId"));assertEquals("program",request.getString("programId"));assertEquals("a".repeat(64),request.getString("expectedProgramSha256"));
        assertEquals("impact",request.getJSONObject("timeline").getJSONArray("shots").getJSONObject(1).getString("id"));
    }

    @Test public void sourceChangeRestoresAcceptedTimelineInsteadOfAdoptingStaleDraft() throws Exception {
        JSONObject saved=timeline();JSONObject dirty=new JSONObject(saved.toString());dirty.put("name","unsaved");
        Bundle state=new Bundle();state.putString("draft",dirty.toString());state.putLong("sourceRevision",100);state.putString("acceptedTimeline",saved.toString());
        JSONObject restored=(JSONObject)call("restoreTimeline",new Class[]{Bundle.class,long.class},state,101L);
        assertEquals("Phone timeline",restored.getString("name"));
        JSONObject retained=(JSONObject)call("restoreTimeline",new Class[]{Bundle.class,long.class},state,100L);
        assertEquals("unsaved",retained.getString("name"));
    }

    @Test public void phoneOrderControlAndRecreationKeepTheUnsavedShotDraft() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Synthetic phone controls");
        Bundle state=state(project.updatedAt);state.putInt("selectedShot",1);
        Intent intent=new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id);
        ActivityController<Cinematic3DStudioActivity> first=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(state).start().resume().visible();Bundle saved=new Bundle();
        try{Cinematic3DStudioActivity activity=first.get();Button move=findButton(activity.getWindow().getDecorView(),"Move earlier");assertNotNull(move);move.performClick();assertEquals("impact",((JSONObject)field(activity,"timeline")).getJSONArray("shots").getJSONObject(0).getString("id"));assertTrue((Boolean)field(activity,"dirty"));first.saveInstanceState(saved);}finally{first.pause().stop().destroy();}
        ActivityController<Cinematic3DStudioActivity> recreated=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(saved).start().resume().visible();
        try{assertEquals("impact",((JSONObject)field(recreated.get(),"timeline")).getJSONArray("shots").getJSONObject(0).getString("id"));assertTrue((Boolean)field(recreated.get(),"dirty"));assertNotNull(findButton(recreated.get().getWindow().getDecorView(),"Edit shot"));assertNotNull(findButton(recreated.get().getWindow().getDecorView(),"Restore saved"));}finally{recreated.pause().stop().destroy();}
    }

    @Test public void phoneSaveAfterSourceChangeRollsBackAndRequiresExplicitRebinding() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Synthetic stale source");Bundle state=state(project.updatedAt);JSONObject draft=timeline().put("name","Unsaved stale draft");state.putString("draft",draft.toString());state.putBoolean("dirty",true);
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(state).start().resume().visible();
        try{project.name="Source edited elsewhere";store.save(project);Cinematic3DStudioActivity activity=controller.get();findButton(activity.getWindow().getDecorView(),"Save timeline").performClick();assertEquals("Phone timeline",((JSONObject)field(activity,"timeline")).getString("name"));assertFalse((Boolean)field(activity,"dirty"));assertFalse((Boolean)field(activity,"bindingReady"));assertEquals(project.updatedAt,((Long)field(activity,"expectedSourceUpdatedAt")).longValue());findButton(activity.getWindow().getDecorView(),"Live 3D preview").performClick();assertNull("A stale saved binding must never start GPU preview",shadowOf(activity).getNextStartedActivity());}finally{controller.pause().stop().destroy();}
    }

    @Test public void recreationReconcilesAnAlreadyPublishedSaveWithoutMakingAnotherProgram() throws Exception {
        Cinematic3DStoreTest.Fixture fixture=new Cinematic3DStoreTest.Fixture();JSONObject bundle=fixture.install();JSONObject initial=Cinematic3DProgramStore.save(fixture.c,fixture.projects,fixture.saveRequest(bundle).put("programId",UUID.randomUUID().toString()));
        JSONObject edited=new JSONObject(fixture.timeline.toString()).put("name","Save completed during recreation");JSONObject pending=Cinematic3DStudioActivity.saveRequest(fixture.project.id,fixture.project.updatedAt,bundle.getString("bundleId"),initial.getString("programId"),initial.getString("sha256"),edited);
        Bundle state=new Bundle();state.putLong("sourceRevision",fixture.project.updatedAt);state.putString("draft",edited.toString());state.putString("acceptedTimeline",fixture.timeline.toString());state.putString("bundleId",bundle.getString("bundleId"));state.putString("programId",initial.getString("programId"));state.putString("programSha",initial.getString("sha256"));state.putBoolean("dirty",true);state.putBoolean("savePending",true);state.putString("pendingSaveRequest",pending.toString());
        JSONObject published=Cinematic3DProgramStore.save(fixture.c,fixture.projects,pending);
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(fixture.c,Cinematic3DStudioActivity.class).putExtra("projectId",fixture.project.id)).create(state).start().resume().visible();
        try{awaitWorker(controller.get());assertEquals(published.getString("sha256"),field(controller.get(),"expectedProgramSha256"));assertFalse((Boolean)field(controller.get(),"dirty"));assertTrue((Boolean)field(controller.get(),"bindingReady"));assertEquals(1,Cinematic3DProgramStore.list(fixture.c,fixture.projects,new JSONObject().put("projectId",fixture.project.id)).getJSONArray("programs").length());}finally{controller.pause().stop().destroy();}
    }

    @Test public void recreationBeforeSavedProgramReadRetriesTheRequestedProgram() throws Exception {
        for(boolean fromIntent:new boolean[]{true,false}){
            Cinematic3DStoreTest.Fixture fixture=new Cinematic3DStoreTest.Fixture();JSONObject bundle=fixture.install(),program=Cinematic3DProgramStore.save(fixture.c,fixture.projects,fixture.saveRequest(bundle));Intent intent=new Intent(fixture.c,Cinematic3DStudioActivity.class).putExtra("projectId",fixture.project.id);if(fromIntent)intent.putExtra("programId",program.getString("programId"));Bundle state=new Bundle();
            synchronized(NativeCinematicBundleStore.FILE_LOCK){ActivityController<Cinematic3DStudioActivity> first=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create();if(!fromIntent){Method load=Cinematic3DStudioActivity.class.getDeclaredMethod("loadProgram",String.class);load.setAccessible(true);load.invoke(first.get(),program.getString("programId"));}first.saveInstanceState(state);assertFalse("An unaccepted asynchronous read cannot save an empty draft over the requested program",state.containsKey("draft"));first.destroy();}
            ActivityController<Cinematic3DStudioActivity> recreated=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(state).start().resume().visible();
            try{awaitWorker(recreated.get());assertNotNull("The requested saved timeline must stay reachable after an interrupted initial or picker read",field(recreated.get(),"timeline"));assertEquals(program.getString("programId"),field(recreated.get(),"programId"));assertTrue((Boolean)field(recreated.get(),"bindingReady"));}finally{recreated.pause().stop().destroy();}
        }
    }

    @Test public void denseTimelineAndPendingSaveUseBoundedRecreationState() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Dense synthetic phone draft");JSONObject dense=timeline();JSONArray entries=new JSONArray();
        for(int i=0;i<80;i++){JSONObject shot=shot("shot_"+i,"CLASH");JSONArray references=new JSONArray();for(int j=0;j<32;j++)references.put(new JSONObject().put("assetId","original_"+"a".repeat(72)).put("sha256","b".repeat(64)));shot.put("sourceAnchors",references);entries.put(shot);}
        dense.put("shots",entries);dense=Cinematic3DTimeline.validate(dense);assertTrue("Fixture must exercise realistic near-limit metadata",dense.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>350*1024);
        Bundle initial=state(project.updatedAt);initial.putString("draft",dense.toString());initial.putString("acceptedTimeline",dense.toString());initial.putBoolean("dirty",true);initial.putString("programId",UUID.randomUUID().toString());Intent intent=new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id);Bundle saved=new Bundle();
        ActivityController<Cinematic3DStudioActivity> first=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(initial).start().resume().visible();
        try{setField(first.get(),"pendingSaveRequest",Cinematic3DStudioActivity.saveRequest(project.id,project.updatedAt,"synthetic-bundle",initial.getString("programId"),"",dense));setField(first.get(),"savePending",true);first.saveInstanceState(saved);Parcel parcel=Parcel.obtain();try{parcel.writeBundle(saved);assertTrue("Large timeline snapshots must not cross Android's Binder limit",parcel.dataSize()<64*1024);}finally{parcel.recycle();}}finally{first.pause().stop().destroy();}
        ActivityController<Cinematic3DStudioActivity> recreated=Robolectric.buildActivity(Cinematic3DStudioActivity.class,intent).create(saved).start().resume().visible();
        try{awaitWorker(recreated.get());assertEquals(Cinematic3DTimeline.canonical(dense),Cinematic3DTimeline.canonical(field(recreated.get(),"timeline")));assertEquals(32,((JSONObject)field(recreated.get(),"timeline")).getJSONArray("shots").getJSONObject(79).getJSONArray("sourceAnchors").length());}finally{recreated.pause().stop().destroy();}
    }

    @Test public void phoneAuthoringExportWritesThePortableRequestSchema() throws Exception {
        Cinematic3DStoreTest.Fixture fixture=new Cinematic3DStoreTest.Fixture();JSONObject bundle=fixture.install(),program=Cinematic3DProgramStore.save(fixture.c,fixture.projects,fixture.saveRequest(bundle));
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(fixture.c,Cinematic3DStudioActivity.class).putExtra("projectId",fixture.project.id).putExtra("programId",program.getString("programId"))).create().start().resume().visible();
        try{awaitWorker(controller.get());findButton(controller.get().getWindow().getDecorView(),"Export authoring request").performClick();awaitWorker(controller.get());JSONObject request=new JSONObject((String)field(controller.get(),"exportedRequest"));assertEquals("videostudio.cinematic3d.export-request.v1",request.getString("schema"));assertEquals(program.getString("sha256"),request.getString("programSha256"));assertEquals(Cinematic3DTimeline.canonical(fixture.timeline.getJSONArray("shots")),Cinematic3DTimeline.canonical(request.getJSONObject("timeline").getJSONArray("shots")));assertEquals(Intent.ACTION_CREATE_DOCUMENT,shadowOf(controller.get()).getNextStartedActivity().getAction());}finally{controller.pause().stop().destroy();}
    }

    @Test public void savedTimelineReopensAfterProjectRenameAsEditOnlyThenRebinds() throws Exception {
        Cinematic3DStoreTest.Fixture fixture=new Cinematic3DStoreTest.Fixture();JSONObject bundle=fixture.install(),program=Cinematic3DProgramStore.save(fixture.c,fixture.projects,fixture.saveRequest(bundle));fixture.project.name="Source renamed while studio was closed";fixture.projects.save(fixture.project);
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(fixture.c,Cinematic3DStudioActivity.class).putExtra("projectId",fixture.project.id).putExtra("programId",program.getString("programId"))).create().start().resume().visible();
        try{Cinematic3DStudioActivity activity=controller.get();awaitWorker(activity);assertNotNull("A source rename must not make the stored timeline unreachable",field(activity,"timeline"));assertEquals(program.getString("sha256"),field(activity,"expectedProgramSha256"));assertEquals(fixture.project.updatedAt,((Long)field(activity,"expectedSourceUpdatedAt")).longValue());assertFalse((Boolean)field(activity,"bindingReady"));findButton(activity.getWindow().getDecorView(),"Live 3D preview").performClick();assertNull(shadowOf(activity).getNextStartedActivity());findButton(activity.getWindow().getDecorView(),"Save timeline").performClick();awaitWorker(activity);assertTrue((Boolean)field(activity,"bindingReady"));assertNotEquals(program.getString("sha256"),field(activity,"expectedProgramSha256"));}finally{controller.pause().stop().destroy();}
    }

    @Test public void systemBackPreservesUnsavedShotsUntilTheOwnerChoosesToLeave() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Back gesture draft");Bundle state=state(project.updatedAt);state.putBoolean("dirty",true);
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(state).start().resume().visible();
        try{Cinematic3DStudioActivity activity=controller.get();activity.onBackPressed();assertFalse("System Back must use the same draft guard as Return",activity.isFinishing());AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(activity.isFinishing());assertTrue((Boolean)field(activity,"dirty"));}finally{controller.pause().stop().destroy();}
    }

    @Test public void actionLibrarySupportsOrderedAndRepeatedHumanShots() throws Exception {
        JSONObject library=library(),original=new JSONObject(library.getJSONObject("timeline").toString());original.put("shots",new JSONArray().put(original.getJSONArray("shots").getJSONObject(0)));String before=original.toString();
        JSONObject added=(JSONObject)call("addLibraryShot",new Class[]{JSONObject.class,JSONObject.class,String.class,String.class},original,library,"CLASH","new_clash");
        added=(JSONObject)call("addLibraryShot",new Class[]{JSONObject.class,JSONObject.class,String.class,String.class},added,library,"RECOVERY","new_recovery");
        JSONObject repeated=(JSONObject)call("duplicateLibraryShot",new Class[]{JSONObject.class,JSONObject.class,int.class,String.class},added,library,1,"repeat_clash");JSONArray shots=repeated.getJSONArray("shots");
        assertEquals(4,shots.length());assertEquals("CHARGE",shots.getJSONObject(0).getString("action"));assertEquals("CLASH",shots.getJSONObject(1).getString("action"));assertEquals("RECOVERY",shots.getJSONObject(2).getString("action"));assertEquals("CLASH",shots.getJSONObject(3).getString("action"));assertEquals("repeat_clash",shots.getJSONObject(3).getString("id"));
        assertEquals(Cinematic3DTimeline.canonical(library.getJSONObject("timeline").getJSONArray("shots").getJSONObject(1).getJSONArray("sourceAnchors")),Cinematic3DTimeline.canonical(shots.getJSONObject(1).getJSONArray("sourceAnchors")));assertEquals(before,original.toString());
    }

    @Test public void libraryActionChangeRetainsControlsAndUsesTheSelectedBakedTemplate() throws Exception {
        JSONObject library=library(),original=new JSONObject(library.getJSONObject("timeline").toString());JSONObject impact=original.getJSONArray("shots").getJSONObject(1);impact.put("durationFrames",48).put("hitStopFrames",4);impact.getJSONObject("camera").put("fovDegrees",61);original.put("shots",new JSONArray().put(impact));
        JSONObject changed=(JSONObject)call("changeLibraryAction",new Class[]{JSONObject.class,JSONObject.class,int.class,String.class},original,library,0,"RECOVERY");JSONObject shot=changed.getJSONArray("shots").getJSONObject(0);
        assertEquals("impact",shot.getString("id"));assertEquals("RECOVERY",shot.getString("action"));assertEquals(48,shot.getInt("durationFrames"));assertEquals(0,shot.getInt("hitStopFrames"));assertEquals(61,shot.getJSONObject("camera").getDouble("fovDegrees"),0);assertFalse("The new library template has no original-source anchors",shot.has("sourceAnchors"));assertEquals("CLASH",original.getJSONArray("shots").getJSONObject(0).getString("action"));
    }

    @Test public void libraryAuthoringRejectsCustomBundlesUnsupportedActionsAndShotFrameLimits() throws Exception {
        JSONObject library=library(),single=new JSONObject(library.getJSONObject("timeline").toString());single.put("shots",new JSONArray().put(single.getJSONArray("shots").getJSONObject(0)));JSONObject custom=new JSONObject(library.toString());custom.getJSONObject("manifest").getJSONObject("generator").put("actionLibrary",false);
        rejectLibrary("addLibraryShot",new Class[]{JSONObject.class,JSONObject.class,String.class,String.class},single,custom,"CLASH","new_clash");rejectLibrary("duplicateLibraryShot",new Class[]{JSONObject.class,JSONObject.class,int.class,String.class},single,custom,0,"new_clash");rejectLibrary("deleteLibraryShot",new Class[]{JSONObject.class,JSONObject.class,int.class},single,custom,0);rejectLibrary("changeLibraryAction",new Class[]{JSONObject.class,JSONObject.class,int.class,String.class},single,custom,0,"CLASH");
        rejectLibrary("addLibraryShot",new Class[]{JSONObject.class,JSONObject.class,String.class,String.class},single,library,"AERIAL_FLIP","unsupported");rejectLibrary("deleteLibraryShot",new Class[]{JSONObject.class,JSONObject.class,int.class},single,library,0);
        JSONObject full=new JSONObject(single.toString());JSONArray shots=new JSONArray();for(int i=0;i<80;i++)shots.put(shot("limit_"+i,"CHARGE"));full.put("shots",shots);rejectLibrary("addLibraryShot",new Class[]{JSONObject.class,JSONObject.class,String.class,String.class},full,library,"CLASH","too_many");
        shots=new JSONArray();for(int i=0;i<20;i++)shots.put(shot("frames_"+i,"CHARGE").put("durationFrames",600));full.put("shots",shots);rejectLibrary("addLibraryShot",new Class[]{JSONObject.class,JSONObject.class,String.class,String.class},full,library,"CLASH","too_long");
    }

    @Test public void phoneAddDuplicateAndDeleteControlsComposeDeclaredLibraryActions() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Human action composer");JSONObject library=library(),timeline=new JSONObject(library.getJSONObject("timeline").toString());timeline.put("shots",new JSONArray().put(timeline.getJSONArray("shots").getJSONObject(0)));Bundle state=state(project.updatedAt);state.putString("draft",timeline.toString());state.putString("acceptedTimeline",timeline.toString());state.putString("editorBundle",library.toString());
        ActivityController<Cinematic3DStudioActivity> controller=Robolectric.buildActivity(Cinematic3DStudioActivity.class,new Intent(context,Cinematic3DStudioActivity.class).putExtra("projectId",project.id)).create(state).start().resume().visible();
        try{Cinematic3DStudioActivity activity=controller.get();Button add=findButton(activity.getWindow().getDecorView(),"Add shot"),duplicate=findButton(activity.getWindow().getDecorView(),"Duplicate"),delete=findButton(activity.getWindow().getDecorView(),"Delete shot");assertNotNull("Human action selection must be reachable",add);assertNotNull(duplicate);assertNotNull(delete);add.performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);ListView choices=dialog.getListView();choices.performItemClick(choices.getChildAt(1),1,choices.getAdapter().getItemId(1));assertEquals("CLASH",((JSONObject)field(activity,"timeline")).getJSONArray("shots").getJSONObject(1).getString("action"));duplicate.performClick();assertEquals(3,((JSONObject)field(activity,"timeline")).getJSONArray("shots").length());delete.performClick();assertEquals(2,((JSONObject)field(activity,"timeline")).getJSONArray("shots").length());}finally{controller.pause().stop().destroy();}
    }

    static JSONObject timeline() throws Exception {
        JSONArray shots=new JSONArray();shots.put(shot("approach","CHARGE")).put(shot("impact","CLASH").put("sourceAnchors",new JSONArray().put(new JSONObject().put("assetId","original").put("sha256","b".repeat(64))).put(new JSONObject().put("assetId","original").put("sha256","b".repeat(64)))));
        return new JSONObject().put("schema","videostudio.cinematic3d.timeline.v1").put("name","Phone timeline").put("fps",24).put("motionProvider","procedural_mannequin").put("shots",shots);
    }
    private static JSONObject shot(String id,String action) throws Exception {return new JSONObject().put("id",id).put("action",action).put("durationFrames",24).put("hitStopFrames",0).put("camera",new JSONObject().put("mode","wide").put("fovDegrees",55).put("shakeIntensity",0)).put("vfx",new JSONObject().put("sparkBurst",true).put("slashTrail",true).put("rubble",true));}
    private static JSONObject library() throws Exception {JSONObject timeline=timeline();timeline.getJSONArray("shots").getJSONObject(1).put("hitStopFrames",3);timeline.getJSONArray("shots").put(shot("recover","RECOVERY"));JSONArray scenes=new JSONArray();for(int i=0;i<timeline.getJSONArray("shots").length();i++){JSONObject shot=timeline.getJSONArray("shots").getJSONObject(i);scenes.put(new JSONObject().put("shotId",shot.getString("id")).put("action",shot.getString("action")).put("path","scene.glb").put("animationName","clip_"+shot.getString("action")).put("cameraName","Camera").put("clipDurationSeconds",1).put("impactPhase",.5));}return new JSONObject().put("timeline",timeline).put("manifest",new JSONObject().put("generator",new JSONObject().put("actionLibrary",true)).put("scenes",scenes));}
    private static void rejectLibrary(String name,Class<?>[] signature,Object...arguments) throws Exception {try{call(name,signature,arguments);fail("Unsupported library mutation admitted: "+name);}catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}}
    private static Object call(String name,Class<?>[] signature,Object...args) throws Exception {
        Class<?> activity;
        try{activity=Class.forName("com.rezoxnemesis.videostudio.Cinematic3DStudioActivity");}catch(ClassNotFoundException missing){throw new AssertionError("Phone cinematic studio implementation missing",missing);}
        Method method;try{method=activity.getDeclaredMethod(name,signature);}catch(NoSuchMethodException missing){throw new AssertionError("Phone cinematic editing action missing: "+name,missing);}method.setAccessible(true);return method.invoke(null,args);
    }
    private static Bundle state(long revision) throws Exception {Bundle state=new Bundle();state.putLong("sourceRevision",revision);state.putString("draft",timeline().toString());state.putString("acceptedTimeline",timeline().toString());state.putString("bundleId","synthetic-bundle");return state;}
    private static Object field(Object object,String name) throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void setField(Object object,String name,Object value) throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static void awaitWorker(Cinematic3DStudioActivity activity) throws Exception {((ExecutorService)field(activity,"ownerWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static Button findButton(View view,String name){if(view instanceof Button&&name.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),name);if(found!=null)return found;}}return null;}
}
