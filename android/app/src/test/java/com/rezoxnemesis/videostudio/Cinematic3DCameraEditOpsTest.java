package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Camera authoring and output-clock checks, without GPU execution claims. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DCameraEditOpsTest {
    static JSONObject apply(JSONObject timeline,JSONArray operations) throws Exception {
        try{return (JSONObject)Class.forName("com.rezoxnemesis.videostudio.Cinematic3DCameraEditOps").getMethod("apply",JSONObject.class,JSONArray.class).invoke(null,timeline,operations);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
    static JSONObject key(String shot,double phase,double x) throws Exception {
        return new JSONObject().put("type","upsert_camera_key").put("shotId",shot).put("phase",phase)
                .put("position",new JSONArray(new double[]{x,2,6})).put("target",new JSONArray(new double[]{0,1,0})).put("up",new JSONArray(new double[]{0,1,0}));
    }
    @Test public void sortedInsertAndReplacementPreserveTheSourceTimelineAndEveryBakedField() throws Exception {
        JSONObject source=Cinematic3DStudioActivityTest.timeline();JSONObject baked=source.getJSONArray("shots").getJSONObject(1);JSONArray rotations=new JSONArray();for(int i=0;i<16;i++)rotations.put(new JSONArray(new double[]{1,0,0,0}));JSONObject pose=new JSONObject().put("root",new JSONArray(new double[]{.2,0,-.3})).put("rotations",rotations);
        baked.put("poseKeyframes",new JSONArray().put(new JSONObject().put("phase",.5).put("actors",new JSONObject().put("black",pose).put("white",new JSONObject(pose.toString()))))).put("footContacts",new JSONArray().put(new JSONObject().put("actor","black").put("foot","leading").put("startPhase",0).put("endPhase",.5).put("anchor",new JSONArray(new double[]{.2,0,-.3})))).put("motionStyle",new JSONObject().put("preset","grounded").put("intensity",.4));String before=Cinematic3DTimeline.canonical(source);
        JSONObject result=apply(source,new JSONArray().put(key("impact",1,5)).put(key("impact",0,1)).put(key("impact",.5,3)).put(key("impact",.5,4)));
        JSONArray keys=result.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").getJSONArray("keyframes");assertEquals(3,keys.length());
        assertEquals(0,keys.getJSONObject(0).getDouble("phase"),0);assertEquals(.5,keys.getJSONObject(1).getDouble("phase"),0);assertEquals(4,keys.getJSONObject(1).getJSONArray("position").getDouble(0),0);assertEquals(1,keys.getJSONObject(2).getDouble("phase"),0);
        JSONObject reverted=new JSONObject(result.toString());reverted.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").remove("keyframes");
        assertEquals(before,Cinematic3DTimeline.canonical(reverted));assertEquals(before,Cinematic3DTimeline.canonical(source));
        JSONObject sampled=Cinematic3DTimeline.sample(result,36).getJSONObject("sample").getJSONObject("camera");assertEquals(4,sampled.getJSONArray("position").getDouble(0),1e-12);
    }
    @Test public void removeAndClearRestorePresetsWithoutLeavingAnEmptyAuthoredTrack() throws Exception {
        JSONObject authored=apply(Cinematic3DStudioActivityTest.timeline(),new JSONArray().put(key("impact",0,1)).put(key("impact",1,5)));
        JSONObject removed=apply(authored,new JSONArray().put(new JSONObject().put("type","remove_camera_key").put("shotId","impact").put("phase",0)));
        assertEquals(1,removed.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").getJSONArray("keyframes").length());
        JSONObject last=apply(removed,new JSONArray().put(new JSONObject().put("type","remove_camera_key").put("shotId","impact").put("phase",1)));assertFalse(last.getJSONArray("shots").getJSONObject(1).getJSONObject("camera").has("keyframes"));
        JSONObject clear=apply(authored,new JSONArray().put(new JSONObject().put("type","clear_camera_keys").put("shotId","impact")));assertEquals(Cinematic3DTimeline.canonical(last),Cinematic3DTimeline.canonical(clear));
    }
    @Test public void malformedOrPartiallyInvalidBatchesLeaveTheirInputUntouched() throws Exception {
        JSONObject source=Cinematic3DStudioActivityTest.timeline();String before=source.toString();
        for(JSONArray operations:new JSONArray[]{new JSONArray(),new JSONArray().put(key("missing",0,1)),new JSONArray().put(key("impact",0,1).put("execute","forbidden")),new JSONArray().put(key("impact",0,1).put("phase","0")),new JSONArray().put(key("impact",0,1)).put(new JSONObject().put("type","remove_camera_key").put("shotId","impact").put("phase",.5)),new JSONArray().put(new JSONObject().put("type","clear_camera_keys").put("shotId","impact").put("phase",0))}) {
            try{apply(source,operations);fail("Invalid camera operation admitted");}catch(IllegalArgumentException expected){}assertEquals(before,source.toString());
        }
        JSONArray excess=new JSONArray();for(int i=0;i<129;i++)excess.put(key("impact",i/128d,1));try{apply(source,excess);fail("Unbounded operation batch admitted");}catch(IllegalArgumentException expected){}
    }
    @Test public void interpolationAndImpactShakeAreCheckedAtActualOutputFrames() throws Exception {
        JSONObject source=Cinematic3DStudioActivityTest.timeline();JSONObject shot=source.getJSONArray("shots").getJSONObject(1);shot.put("hitStopFrames",4);String before=source.toString();
        JSONObject left=key("impact",0,0).put("position",new JSONArray(new double[]{0,1,2})).put("target",new JSONArray(new double[]{0,1,0}));
        JSONObject right=key("impact",1,0).put("position",new JSONArray(new double[]{0,1,-2})).put("target",new JSONArray(new double[]{0,1,0}));
        try{apply(source,new JSONArray().put(left).put(right));fail("Valid endpoint keys with a degenerate middle output frame admitted");}catch(IllegalArgumentException expected){}assertEquals(before,source.toString());
        // At impact (frame 10), shake is [A*sin(.7), .6*A*sin(1.3), .4*A].
        shot.getJSONObject("camera").put("shakeIntensity",1);double[] position={0,1,2},target={.04*Math.sin(.7),1+.024*Math.sin(1.3),2+.016};
        JSONObject near=key("impact",0,0).put("position",new JSONArray(position)).put("target",new JSONArray(target));
        try{apply(source,new JSONArray().put(near));fail("Impact shake collapsed the output camera basis");}catch(IllegalArgumentException expected){}
    }
    @Test public void optionalUpUsesTheSelectedModeAndCameraKeepsMovingDuringActorHold() throws Exception {
        JSONObject source=Cinematic3DStudioActivityTest.timeline();source.getJSONArray("shots").getJSONObject(1).put("hitStopFrames",4);
        JSONObject left=key("impact",0,1),right=key("impact",1,5);left.remove("up");right.remove("up");JSONObject timeline=apply(source,new JSONArray().put(left).put(right));JSONObject shot=timeline.getJSONArray("shots").getJSONObject(1);
        JSONObject a=Cinematic3DTimeline.sampleShot(shot,10,24),b=Cinematic3DTimeline.sampleShot(shot,11,24);assertTrue(a.getBoolean("hitStop"));assertTrue(b.getBoolean("hitStop"));assertEquals(a.getDouble("phase"),b.getDouble("phase"),0);assertTrue(b.getJSONObject("camera").getJSONArray("position").getDouble(0)>a.getJSONObject("camera").getJSONArray("position").getDouble(0));
    }
    @Test public void keyBudgetAndCancellationRejectWithoutChangingTheInput() throws Exception {
        JSONArray operations=new JSONArray();for(int i=0;i<128;i++)operations.put(key("impact",i/127d,1));JSONObject full=apply(Cinematic3DStudioActivityTest.timeline(),operations);String before=full.toString();
        try{apply(full,new JSONArray().put(key("impact",.1234567,2)));fail("A 129th camera key was admitted");}catch(IllegalArgumentException expected){}assertEquals(before,full.toString());
        Thread.currentThread().interrupt();try{apply(full,new JSONArray().put(key("impact",0,3)));fail("Cancelled camera edit admitted");}catch(InterruptedException expected){}finally{Thread.interrupted();}assertEquals(before,full.toString());
    }
    @Test public void cooperativeCancellationStopsInsidePreflightWithoutMutatingTheInput() throws Exception {
        JSONObject source=Cinematic3DStudioActivityTest.timeline();String before=source.toString();AtomicInteger checks=new AtomicInteger();BooleanSupplier cancelled=()->checks.incrementAndGet()>8;
        try{Class.forName("com.rezoxnemesis.videostudio.Cinematic3DCameraEditOps").getMethod("apply",JSONObject.class,JSONArray.class,BooleanSupplier.class).invoke(null,source,new JSONArray().put(key("impact",0,3)),cancelled);fail("Cancelled output-camera preflight completed");}
        catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof InterruptedException);}
        assertTrue("Cancellation must be checked during frame sampling",checks.get()>8);assertEquals(before,source.toString());assertFalse("Predicate cancellation must not interrupt the shared executor thread",Thread.currentThread().isInterrupted());
    }
}
