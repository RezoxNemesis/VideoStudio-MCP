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

/** Exercise the actual legacy service entry point against the durable project store. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class LegacyToolParityTest {
    @Test public void gaussianPresetAlsoReachesGeneratedAnimationLayers()throws Exception{
        apply(request("effect",new JSONObject().put("preset","gaussian_blur")));
        ProjectStore.Clip clip=store.get(project.id).clips.get(0);
        java.lang.reflect.Method build=TimelineCompositionFactory.class.getDeclaredMethod("buildLayerEffects",ProjectStore.Clip.class,String.class,String.class,long.class,JSONObject.class,String.class,long.class);
        build.setAccessible(true);
        TimelineCompositionFactory factory=new TimelineCompositionFactory(RuntimeEnvironment.getApplication());
        for(String role:new String[]{"background","head","torso","lower_drape","foreground"}){
            java.util.List<?> effects=(java.util.List<?>)build.invoke(factory,clip,"16:9","720p",1000L,new JSONObject(),role,0L);
            assertTrue("Gaussian effect must execute on "+role,effects.stream().anyMatch(e->e instanceof androidx.media3.effect.GaussianBlur));
        }
    }
    private ProjectStore store;
    private ProjectStore.Project project;
    private ControlService service;

    @Before public void setUp() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_v3.db");
        store=new ProjectStore(context);project=store.create("Legacy control parity");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.mime="video/mp4";asset.durationMs=5000;project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original";clip.assetId=asset.id;clip.outMs=1000;clip.title="Original title";project.clips.add(clip);
        store.save(project);
        service=Robolectric.buildService(ControlService.class).get();
        java.lang.reflect.Field field=ControlService.class.getDeclaredField("store");field.setAccessible(true);field.set(service,store);
    }
    private JSONObject request(String tool,JSONObject settings)throws Exception {
        return new JSONObject().put("projectId",project.id).put("clipIndex",0).put("tool",tool).put("settings",settings).put("_mcpCommandId","legacy-command-001");
    }
    private JSONObject apply(JSONObject request)throws Exception {
        java.lang.reflect.Method method=ControlService.class.getDeclaredMethod("applyTool",JSONObject.class);method.setAccessible(true);
        try{return (JSONObject)method.invoke(service,request);}
        catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}
    }
    private void rejected(JSONObject request)throws Exception {
        long revision=store.get(project.id).revision;
        try{apply(request);fail("The command must be rejected before any project edit");}catch(IllegalArgumentException expected){}
        assertEquals(revision,store.get(project.id).revision);
    }
    @Test public void legacyVolumeCannotBypassTrackLock()throws Exception {
        project.tracks.get(0).locked=true;store.save(project);
        rejected(request("volume",new JSONObject().put("volume",.4)));
        assertEquals(1,store.get(project.id).clips.get(0).volume,0);
    }
    @Test public void legacyTrimCannotExtendBeyondTheOwnedSource()throws Exception {
        rejected(request("trim",new JSONObject().put("outMs",6000)));
        assertEquals(1000,store.get(project.id).clips.get(0).outMs);
    }
    @Test public void unknownToolCannotClaimSuccessOrCreateInertMetadata()throws Exception {
        rejected(request("imaginary_super_resolution",new JSONObject()));
        assertFalse(store.get(project.id).clips.get(0).effects.has("imaginary_super_resolution"));
    }
    @Test public void titleAndItsStylesFormOneUndoableEdit()throws Exception {
        apply(request("title",new JSONObject().put("text","Edited title").put("font","poster").put("animation","typewriter")));
        ProjectStore.Project edited=store.get(project.id);assertEquals("Edited title",edited.clips.get(0).title);
        assertEquals("poster",edited.clips.get(0).effects.getString("fontFamily"));
        assertEquals(project.revision+1,edited.revision);
        ProjectStore.Project undo=store.undo(project.id,edited.revision);assertEquals("Original title",undo.clips.get(0).title);
        assertFalse(undo.clips.get(0).effects.has("fontFamily"));
    }
    @Test public void invalidTitleStyleRollsBackTheEntireEdit()throws Exception {
        rejected(request("title",new JSONObject().put("text","Must not persist").put("font","nonexistent-font")));
        assertEquals("Original title",store.get(project.id).clips.get(0).title);
    }
    @Test public void speedChangesRippleFollowingClipsInsteadOfOverlapping()throws Exception {
        ProjectStore.Clip second=ProjectStore.Clip.fromJson(project.clips.get(0).toJson());second.id="second";second.startMs=1000;project.clips.add(second);store.save(project);
        apply(request("speed",new JSONObject().put("speed",.5)));
        ProjectStore.Project edited=store.get(project.id);assertEquals(2000,edited.clips.get(1).startMs);assertEquals(3000,edited.outputDurationMs());
        assertEquals(1000,store.undo(project.id,edited.revision).clips.get(1).startMs);
    }
    @Test public void legacyKeyframesReachTheRenderedCurveAndCanBeUndone()throws Exception {
        JSONArray frames=new JSONArray().put(new JSONObject().put("property","opacity").put("timeMs",0).put("value",0).put("easing","linear"))
                .put(new JSONObject().put("property","opacity").put("timeMs",1000).put("value",1).put("easing","linear"));
        apply(request("keyframes",new JSONObject().put("keyframes",frames)));
        ProjectStore.Project edited=store.get(project.id);assertEquals(.5,EditorEngine.valueAt(edited.clips.get(0),"opacity",500,1),.0001);
        assertEquals(0,store.undo(project.id,edited.revision).clips.get(0).keyframes.length());
    }
    @Test public void replayAfterAnOwnerEditCannotReapplyOrOverwriteIt()throws Exception {
        JSONObject request=request("title",new JSONObject().put("text","Agent title"));JSONObject first=apply(request);
        ProjectStore.Project edited=store.get(project.id);
        new EditorEngine(store).execute(project.id,edited.revision,"owner","","set_title",new JSONObject().put("clipId","original").put("text","Owner title"));
        long currentRevision=store.get(project.id).revision;
        JSONObject replay=apply(new JSONObject(request.toString()));
        assertEquals("Owner title",store.get(project.id).clips.get(0).title);assertEquals(currentRevision,store.get(project.id).revision);
        assertEquals(first.getLong("revision"),replay.getLong("revision"));
    }
    @Test public void aReusedCommandIdCannotExecuteDifferentSettings()throws Exception {
        apply(request("volume",new JSONObject().put("volume",.4)));
        rejected(request("volume",new JSONObject().put("volume",.7)));
        assertEquals(.4,store.get(project.id).clips.get(0).volume,.0001);
    }
    @Test public void explicitRevisionProtectsNewerOwnerWork()throws Exception {
        JSONObject request=request("volume",new JSONObject().put("volume",.4)).put("expectedRevision",project.revision);
        new EditorEngine(store).execute(project.id,project.revision,"owner","","set_title",new JSONObject().put("clipId","original").put("text","New owner title"));
        long revision=store.get(project.id).revision;
        try{apply(request);fail("Stale revision must conflict");}catch(ProjectStore.RevisionConflict expected){}
        assertEquals(revision,store.get(project.id).revision);assertEquals("New owner title",store.get(project.id).clips.get(0).title);
    }
    @Test public void numericStringsAndUnknownSettingsCannotBeSilentlyCoerced()throws Exception {
        rejected(request("volume",new JSONObject().put("volume","0.4")));
        rejected(request("blur",new JSONObject().put("sigma",4).put("unimplemented",true)));
    }
    @Test public void chromaKeyPatchPreservesAnExistingMaskAndUndoRestoresIt()throws Exception {
        project.clips.get(0).effects.put("mask","ellipse").put("maskWidth",.6);store.save(project);
        apply(request("green_screen",new JSONObject().put("color","#00FF00")));
        ProjectStore.Project edited=store.get(project.id);JSONObject effects=edited.clips.get(0).effects;
        assertEquals("ellipse",effects.getString("mask"));assertEquals(.6,effects.getDouble("maskWidth"),0);assertTrue(effects.getBoolean("chromaKey"));
        JSONObject undo=store.undo(project.id,edited.revision).clips.get(0).effects;assertEquals("ellipse",undo.getString("mask"));assertFalse(undo.has("chromaKey"));
    }
    private boolean hasGaussianBlur(ProjectStore.Clip clip)throws Exception{
        java.lang.reflect.Method method=TimelineCompositionFactory.class.getDeclaredMethod("buildEffects",ProjectStore.Clip.class,String.class,String.class,long.class,long.class);method.setAccessible(true);
        java.util.List<?> effects=(java.util.List<?>)method.invoke(new TimelineCompositionFactory(RuntimeEnvironment.getApplication()),clip,"16:9","720p",5000L,0L);
        return effects.stream().anyMatch(androidx.media3.effect.GaussianBlur.class::isInstance);
    }
    @Test public void clearingAMigratedEffectRemovesItsActualBlurProvider()throws Exception{
        project.clips.get(0).effects.put("effectPreset","gaussian_blur");store.save(project);assertTrue(hasGaussianBlur(project.clips.get(0)));
        apply(request("effect",new JSONObject().put("preset","none")));ProjectStore.Project edited=store.get(project.id);
        assertFalse("Legacy effect replacement must stop the rendered provider",hasGaussianBlur(edited.clips.get(0)));
        assertTrue(hasGaussianBlur(store.undo(project.id,edited.revision).clips.get(0)));
    }
    @Test public void anExistingGaussianPresetStillSelectsItsExecutingProvider()throws Exception{
        apply(request("effect",new JSONObject().put("preset","gaussian_blur")));ProjectStore.Project edited=store.get(project.id);
        assertTrue(hasGaussianBlur(edited.clips.get(0)));assertFalse(hasGaussianBlur(store.undo(project.id,edited.revision).clips.get(0)));
    }
}
