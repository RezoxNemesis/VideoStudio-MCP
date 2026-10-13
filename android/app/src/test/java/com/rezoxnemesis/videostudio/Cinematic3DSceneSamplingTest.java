package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class Cinematic3DSceneSamplingTest {
    private static Object call(String method,Class<?>[] types,Object... args) {
        try { return Class.forName("com.rezoxnemesis.videostudio.Cinematic3DSceneSampling").getMethod(method,types).invoke(null,args); }
        catch(java.lang.reflect.InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException) throw (RuntimeException)failure.getCause();
            throw new AssertionError(failure.getCause());
        }
        catch(ReflectiveOperationException absent) { throw new AssertionError("Shared cinematic scene sampler is absent",absent); }
    }
    private static int requireNamedIndex(String[] names,String exact) { return (Integer)call("requireNamedIndex",new Class<?>[]{String[].class,String.class},names,exact); }
    private static JSONObject sceneForShot(JSONObject bundle,JSONObject shot) { return (JSONObject)call("sceneForShot",new Class<?>[]{JSONObject.class,JSONObject.class},bundle,shot); }
    private static JSONObject frame(JSONObject shot,int frame,int fps,JSONObject scene) { return (JSONObject)call("frame",new Class<?>[]{JSONObject.class,int.class,int.class,JSONObject.class},shot,frame,fps,scene); }
    private static double[][] bladeEndpoints(double[] world,JSONObject extras) { return (double[][])call("bladeEndpoints",new Class<?>[]{double[].class,JSONObject.class},world,extras); }
    static JSONObject shot(int frames,int hold) throws Exception {
        return new JSONObject().put("id","shot_clash").put("action","CLASH")
                .put("durationFrames",frames).put("hitStopFrames",hold)
                .put("camera",new JSONObject().put("mode","orbit").put("fovDegrees",57).put("shakeIntensity",0.4))
                .put("vfx",new JSONObject().put("sparkBurst",true).put("slashTrail",true).put("rubble",true));
    }
    static JSONObject scene() throws Exception {
        return new JSONObject().put("shotId","shot_clash").put("action","CLASH").put("path","scene.glb")
                .put("animationName","C3D_CLASH").put("cameraName","CinematicCamera")
                .put("clipDurationSeconds",2).put("impactPhase",0.5);
    }
    @Test public void animationSelectionRequiresOneExactManifestName() {
        assertEquals(1,requireNamedIndex(new String[]{"clash","C3D_CLASH"},"C3D_CLASH"));
        for(String[] names:new String[][]{{"clash"},{"C3D_CLASH_preview"},{"C3D_CLASH","C3D_CLASH"}}) {
            try { requireNamedIndex(names,"C3D_CLASH"); fail("Missing or ambiguous clip accepted"); }
            catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("exact")); }
        }
    }
    @Test public void bindingRejectsAnUnlistedShotOrChangedAction() throws Exception {
        JSONObject bundle=new JSONObject().put("manifest",new JSONObject().put("scenes",new JSONArray().put(scene())));
        assertEquals("C3D_CLASH",sceneForShot(bundle,shot(48,2)).getString("animationName"));
        for(JSONObject bad:new JSONObject[]{shot(48,2).put("id","new_shot"),shot(48,2).put("action","STANDOFF")}) {
            try { sceneForShot(bundle,bad); fail("Unbound choreography accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void frameSamplingUsesExclusiveClockAndExactHoldWhileCameraMoves() throws Exception {
        for(int fps:new int[]{24,30,60}) for(int hold:new int[]{2,3,4,5}) {
            JSONObject shot=shot(fps*2,hold);
            int held=0;
            double heldClip=-1;
            JSONObject firstHeld=null,lastHeld=null;
            for(int frame=0;frame<fps*2;frame++) {
                JSONObject value=frame(shot,frame,fps,scene());
                assertEquals(frame/(double)fps,value.getDouble("seconds"),1e-12);
                assertEquals(frame/(double)(fps*2)*2,value.getDouble("cameraSeconds"),1e-12);
                assertEquals(value.getDouble("phase")*2,value.getDouble("clipSeconds"),1e-12);
                assertTrue(value.getDouble("phase")<1);
                if(value.getBoolean("hitStop")) {
                    if(held++==0) { heldClip=value.getDouble("clipSeconds"); firstHeld=value; }
                    assertEquals(heldClip,value.getDouble("clipSeconds"),0);
                    lastHeld=value;
                }
            }
            assertEquals(hold,held);
            assertTrue(lastHeld.getDouble("cameraSeconds")>firstHeld.getDouble("cameraSeconds"));
            assertNotEquals(firstHeld.getJSONObject("camera").toString(),lastHeld.getJSONObject("camera").toString());
        }
    }
    @Test public void sceneDurationDoesNotChangeTheOutputClock() throws Exception {
        JSONObject value=frame(shot(120,0),119,60,scene().put("clipDurationSeconds",10));
        assertEquals(119/60.0,value.getDouble("seconds"),1e-12);
        assertEquals(119/120.0*10,value.getDouble("clipSeconds"),1e-12);
    }
    @Test public void declaredImpactPhaseMapsHeldActorTimeToAuthoredContact() throws Exception {
        JSONObject shot=shot(48,3),scene=scene().put("impactPhase",0.3);
        JSONObject held=frame(shot,22,24,scene);
        assertTrue(held.getBoolean("hitStop"));
        assertEquals(0.5,held.getDouble("phase"),0);
        assertEquals(0.6,held.getDouble("clipSeconds"),1e-12);
        JSONObject last=frame(shot,47,24,scene);
        assertTrue(last.getDouble("clipSeconds")<2);
        assertTrue(last.getDouble("clipSeconds")>0.6);
    }
    @Test public void onlyExplicitActionLibraryMayBindANewShotId() throws Exception {
        JSONObject bundle=new JSONObject().put("manifest",new JSONObject()
                .put("generator",new JSONObject().put("actionLibrary",true))
                .put("scenes",new JSONArray().put(scene())));
        JSONObject selected=sceneForShot(bundle,shot(48,2).put("id","edited_clash"));
        assertEquals("edited_clash",selected.getString("shotId"));
        assertEquals("C3D_CLASH",selected.getString("animationName"));
    }
    @Test public void authoredClipDurationAdmissionRejectsWrongAndNonfiniteClocks() {
        call("requireClipDuration",new Class<?>[]{double.class,double.class},2.0,2.0);
        call("requireClipDuration",new Class<?>[]{double.class,double.class},2.0-1/24.0,2.0);
        for(double actual:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,1.0,3.0}) {
            try { call("requireClipDuration",new Class<?>[]{double.class,double.class},actual,2.0); fail("Wrong clip clock accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void explicitLibraryMayChangeActionWithoutChangingStableShotId() throws Exception {
        JSONObject macro=scene().put("shotId","macro").put("action","MACRO_CLASH").put("animationName","C3D_MACRO_CLASH");
        JSONObject bundle=new JSONObject().put("manifest",new JSONObject()
                .put("generator",new JSONObject().put("actionLibrary",true))
                .put("scenes",new JSONArray().put(scene()).put(macro)));
        JSONObject selected=sceneForShot(bundle,shot(48,2).put("action","MACRO_CLASH"));
        assertEquals("shot_clash",selected.getString("shotId"));
        assertEquals("C3D_MACRO_CLASH",selected.getString("animationName"));
    }
    @Test public void trailsUseExactlySixPriorOutputSamplesAfterClipRetiming() throws Exception {
        for(int fps:new int[]{24,30,60}) {
            int frames=fps*4,current=frames/2;
            JSONObject sample=frame(shot(frames,0),current,fps,scene());
            JSONArray times=sample.getJSONArray("trailClipSeconds");
            assertEquals(7,times.length());
            for(int age=0;age<7;age++)assertEquals((current-age)*2/(double)frames,times.getDouble(age),1e-12);
            assertEquals(6*2/(double)frames,times.getDouble(0)-times.getDouble(6),1e-12);
        }
    }
    @Test public void trailHistoryResetsAtCutsAndAdvancesDuringActorHold() throws Exception {
        JSONObject shot=shot(48,5);
        JSONArray atCut=frame(shot,0,24,scene()).getJSONArray("trailClipSeconds");
        assertEquals(0,atCut.getDouble(0),0);
        for(int age=1;age<7;age++)assertTrue(atCut.isNull(age));
        JSONArray held=frame(shot,25,24,scene()).getJSONArray("trailClipSeconds");
        for(int age=0;age<5;age++)assertEquals(1,held.getDouble(age),0);
        assertTrue(held.getDouble(5)<1);assertTrue(held.getDouble(6)<held.getDouble(5));
        // Scrubbing to another cut cannot retain the previous render's history.
        JSONArray scrubbed=frame(shot,0,24,scene()).getJSONArray("trailClipSeconds");
        assertEquals(atCut.toString(),scrubbed.toString());
    }
    @Test public void nativeBladeAnchorsUseConvertedGltfLocalAxisAndAuthoredWorldTransform() throws Exception {
        JSONObject extras=new JSONObject().put("gltf_blade_start",new JSONArray(new double[]{0,0,-.16}))
                .put("gltf_blade_end",new JSONArray(new double[]{0,0,-1.06}));
        double[] world={0,0,-1,0, 0,1,0,0, 1,0,0,0, 2,3,4,1};
        double[][] endpoints=bladeEndpoints(world,extras);
        assertArrayEquals(new double[]{1.84,3,4},endpoints[0],1e-12);
        assertArrayEquals(new double[]{.94,3,4},endpoints[1],1e-12);
        JSONObject unswizzled=new JSONObject(extras.toString()).put("gltf_blade_start",new JSONArray(new double[]{0,.16,0}));
        try { bladeEndpoints(world,unswizzled); fail("Blender local coordinates accepted as native glTF anchors"); }
        catch(IllegalArgumentException expected) { }
    }
}
