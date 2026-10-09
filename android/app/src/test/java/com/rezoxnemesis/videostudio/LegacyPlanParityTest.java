package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.InvocationTargetException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class LegacyPlanParityTest {
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void explicitCutClearsTheExecutingGeneratedSceneTransition()throws Exception{
        JSONObject spec=new JSONObject().put("cameraPreset","none").put("transitionPreset","zoom_in").put("keyframes",new JSONArray()
                .put(new JSONObject().put("t",0).put("scale",1)).put(new JSONObject().put("t",1).put("scale",1)));
        java.lang.reflect.Method method=TimelineCompositionFactory.class.getDeclaredMethod("buildLayerEffects",ProjectStore.Clip.class,String.class,String.class,long.class,JSONObject.class,String.class,long.class);method.setAccessible(true);
        TimelineCompositionFactory factory=new TimelineCompositionFactory(RuntimeEnvironment.getApplication());
        java.util.List<?> before=(java.util.List<?>)method.invoke(factory,store.get(project.id).clips.get(0),"16:9","720p",1000L,spec,"foreground",0L);
        call("applyCreatorPreset",base().put("preset","none").put("motion","none").put("transition","none"));
        java.util.List<?> after=(java.util.List<?>)method.invoke(factory,store.get(project.id).clips.get(0),"16:9","720p",1000L,spec,"foreground",0L);
        float[] a=new float[9],b=new float[9];
        ((MotionMatrixEffect)before.stream().filter(MotionMatrixEffect.class::isInstance).findFirst().get()).getMatrix(999000).getValues(a);
        ((MotionMatrixEffect)after.stream().filter(MotionMatrixEffect.class::isInstance).findFirst().get()).getMatrix(999000).getValues(b);
        assertTrue("Clear must affect the rendered transition",Math.abs(a[android.graphics.Matrix.MSCALE_X]-b[android.graphics.Matrix.MSCALE_X])>.01);
        assertEquals("zoom_in",spec.getString("transitionPreset"));
    }
    @Test public void renderOnlyRejectsAStaleExplicitRevisionBeforeQueuing()throws Exception{
        try{call("autonomousEdit",base().put("render",true).put("expectedRevision",project.revision-1));fail("Stale render revision must fail");}
        catch(ProjectStore.RevisionConflict expected){assertEquals(project.revision,store.get(project.id).revision);}
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void creatorMotionChangesTheExecutingGeneratedLayerMatrix()throws Exception{
        JSONObject spec=new JSONObject().put("cameraPreset","push_in").put("keyframes",new JSONArray()
                .put(new JSONObject().put("t",0).put("scale",1)).put(new JSONObject().put("t",1).put("scale",1)));
        java.lang.reflect.Method method=TimelineCompositionFactory.class.getDeclaredMethod("buildLayerEffects",ProjectStore.Clip.class,String.class,String.class,long.class,JSONObject.class,String.class,long.class);method.setAccessible(true);
        TimelineCompositionFactory factory=new TimelineCompositionFactory(RuntimeEnvironment.getApplication());
        java.util.List<?> before=(java.util.List<?>)method.invoke(factory,store.get(project.id).clips.get(0),"16:9","720p",1000L,spec,"foreground",0L);
        call("applyCreatorPreset",base().put("preset","none").put("motion","pan_right"));
        java.util.List<?> after=(java.util.List<?>)method.invoke(factory,store.get(project.id).clips.get(0),"16:9","720p",1000L,spec,"foreground",0L);
        float[] a=new float[9],b=new float[9];
        ((MotionMatrixEffect)before.stream().filter(MotionMatrixEffect.class::isInstance).findFirst().get()).getMatrix(800000).getValues(a);
        ((MotionMatrixEffect)after.stream().filter(MotionMatrixEffect.class::isInstance).findFirst().get()).getMatrix(800000).getValues(b);
        assertTrue("A committed creator motion must change generated layer rendering",Math.abs(a[android.graphics.Matrix.MTRANS_X]-b[android.graphics.Matrix.MTRANS_X])>.00001);
        assertEquals("Original generation keyframes remain immutable",2,spec.getJSONArray("keyframes").length());
    }
    @Test public void ambiguousOrUnsupportedPlanInputsNeverBecomeSuccessfulEdits()throws Exception{
        for(JSONObject invalid:new JSONObject[]{scene().put("speed","0.5"),scene().put("transition","camera_shutter"),scene().put("inMs",0).put("start",1)}){
            rejected("applyEditPlan",base().put("clips",new JSONArray().put(invalid)));
        }
        rejected("applyCreatorPreset",base().put("preset","noir").put("clips",new JSONArray().put(scene())));
    }
    private ProjectStore store;private ProjectStore.Project project;private ControlService service;
    @Before public void setup()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_v3.db");
        context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        store=new ProjectStore(context);project=store.create("Bulk parity");
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="source";a.mime="video/mp4";a.durationMs=5000;project.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="original";c.assetId=a.id;c.outMs=1000;c.title="Original";project.clips.add(c);store.save(project);
        service=Robolectric.buildService(ControlService.class).get();java.lang.reflect.Field f=ControlService.class.getDeclaredField("store");f.setAccessible(true);f.set(service,store);
        java.lang.reflect.Field recovery=ControlService.class.getDeclaredField("recoveryPlans");recovery.setAccessible(true);recovery.set(service,new RecoveryPlanStore(context));
    }
    private JSONObject base()throws Exception{return new JSONObject().put("projectId",project.id).put("_mcpCommandId","bulk-command-001");}
    private JSONObject scene()throws Exception{return new JSONObject().put("assetId","source").put("inMs",0).put("outMs",1000);}
    private JSONObject call(String method,JSONObject args)throws Exception{
        java.lang.reflect.Method m=ControlService.class.getDeclaredMethod(method,JSONObject.class);m.setAccessible(true);
        try{return (JSONObject)m.invoke(service,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    private void rejected(String method,JSONObject args)throws Exception{
        long revision=store.get(project.id).revision;
        try{call(method,args);fail("Invalid bulk edit must fail atomically");}catch(IllegalArgumentException expected){}
        assertEquals(revision,store.get(project.id).revision);assertEquals("original",store.get(project.id).clips.get(0).id);
    }
    @Test public void planCannotReplaceLockedOwnerClips()throws Exception{
        project.tracks.get(0).locked=true;store.save(project);
        rejected("applyEditPlan",base().put("clips",new JSONArray().put(scene())));
    }
    @Test public void planRejectsSourceOverflowRatherThanSilentlyClamping()throws Exception{
        rejected("applyEditPlan",base().put("clips",new JSONArray().put(scene().put("outMs",6000))));
    }
    @Test public void planRejectsUnknownEffectProvidersAndRollsBackEveryScene()throws Exception{
        rejected("applyEditPlan",base().put("clips",new JSONArray().put(scene()).put(scene().put("effects",new JSONObject().put("imaginaryBloom",true)))));
    }
    @Test public void planIsOneUndoableRevisionWithExplicitSpeedAdjustedTiming()throws Exception{
        call("applyEditPlan",base().put("clips",new JSONArray().put(scene().put("speed",.5)).put(scene().put("title","Second"))));
        ProjectStore.Project edited=store.get(project.id);assertEquals(project.revision+1,edited.revision);
        assertEquals(2000,edited.clips.get(1).startMs);assertEquals(3000,edited.outputDurationMs());
        ProjectStore.Project undo=store.undo(project.id,edited.revision);assertEquals(1,undo.clips.size());assertEquals("Original",undo.clips.get(0).title);
    }
    @Test public void planReplayCannotReplaceNewOwnerChanges()throws Exception{
        JSONObject args=base().put("clips",new JSONArray().put(scene()));call("applyEditPlan",args);
        ProjectStore.Project edited=store.get(project.id);new EditorEngine(store).execute(project.id,edited.revision,"owner","","set_title",new JSONObject().put("clipId",edited.clips.get(0).id).put("text","Owner latest"));
        long revision=store.get(project.id).revision;call("applyEditPlan",args);
        assertEquals(revision,store.get(project.id).revision);assertEquals("Owner latest",store.get(project.id).clips.get(0).title);
    }
    @Test public void presetCannotBypassTrackLock()throws Exception{
        project.tracks.get(0).locked=true;store.save(project);rejected("applyCreatorPreset",base().put("preset","cinematic"));
    }
    @Test public void presetValidatesEveryProviderBeforeCommitting()throws Exception{
        rejected("applyCreatorPreset",base().put("preset","cinematic").put("font","unknown-font"));
    }
    @Test public void compoundAutonomousEditRollsBackPlanWhenPresetFails()throws Exception{
        rejected("autonomousEdit",base().put("clips",new JSONArray().put(scene())).put("preset","unknown-provider"));
    }
    @Test public void presetReplayPreservesLaterOwnerStyles()throws Exception{
        JSONObject args=base().put("preset","warm_film");call("applyCreatorPreset",args);
        ProjectStore.Project edited=store.get(project.id);new EditorEngine(store).execute(project.id,edited.revision,"owner","","set_effect_preset",new JSONObject().put("clipId","original").put("preset","noir"));
        long revision=store.get(project.id).revision;call("applyCreatorPreset",args);
        assertEquals(revision,store.get(project.id).revision);assertEquals("noir",store.get(project.id).clips.get(0).effects.getString("effectPreset"));
    }
}
