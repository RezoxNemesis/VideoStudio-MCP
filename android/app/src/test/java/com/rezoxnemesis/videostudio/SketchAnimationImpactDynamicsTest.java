package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exact original native rig solver regression; not phone or artistic QA. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationImpactDynamicsTest {
    private static JSONObject joint(JSONObject frame,String rig,String part)throws Exception{
        JSONArray joints=frame.getJSONArray("joints");
        for(int i=0;i<joints.length();i++){
            JSONObject j=joints.getJSONObject(i);
            if(rig.equals(j.getString("rigId"))&&part.equals(j.getString("jointId")))return j;
        }
        throw new AssertionError("Missing real native joint: "+rig+":"+part);
    }
    @Test public void actualRooftopCharactersBraceRecoilAndSettleWithoutLosingContact()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject built=SketchDuelAnimationExample.build(app);
        JSONObject source=built.getJSONObject("source"),program=built.getJSONObject("program");
        JSONObject report=built.getJSONObject("report").getJSONObject("impactDynamics");
        assertEquals("videostudio.impact-dynamics.v1",report.getString("schema"));
        assertEquals(16,report.getInt("impactFrame"));
        assertEquals(10,report.getJSONArray("keyframeCorrections").length());
        assertTrue(report.getBoolean("impactPoseUnchanged"));
        assertFalse(report.getBoolean("sourceArtChanged"));
        assertFalse(report.getBoolean("cameraChanged"));
        assertTrue(report.getBoolean("humanReviewRequired"));
        assertTrue("M3 baseline impact approach contains >60px per-frame motion",
                report.getDouble("originalMaxWristStepPx")>60);
        assertTrue("Native wrist trajectory must be measurably smoother",
                report.getDouble("verifiedMaxWristStepPx")<=55);
        assertTrue(report.getDouble("originalMaxWristStepPx")-
                report.getDouble("verifiedMaxWristStepPx")>=5);
        assertEquals(104,report.getInt("checkedPlantedFootSamples"));
        assertEquals(0,report.getDouble("maxHardFootErrorPx"),.02);
        assertTrue(report.getDouble("unchangedContactGapPx")<=6);
        assertEquals(8,program.getJSONArray("interactionCues").getJSONObject(0).getDouble("maxGapPx"),.00001);
        assertEquals(1,SketchAnimationCompiler.verifyInteractionCues(source,program)
                .getJSONArray("contacts").length());
        JSONArray frames=SketchAnimationCompiler.sampleFrames(source,program,0,35);
        for(String rig:new String[]{"wide_rig","rook_wide_rig"}){
            JSONObject pre=joint(frames.getJSONObject(16),rig,"root");
            JSONObject reaction=joint(frames.getJSONObject(20),rig,"root");
            assertEquals("No horizontal teleport in body response",pre.getDouble("x"),reaction.getDouble("x"),.00001);
            assertTrue("Real downward sink after impact",(reaction.getDouble("y")-pre.getDouble("y"))*720>5);
            for(int f=5;f<=30;f++){
                int n=0;JSONArray locked=frames.getJSONObject(f).getJSONArray("contactErrors");
                for(int i=0;i<locked.length();i++){
                    JSONObject c=locked.getJSONObject(i);
                    if(rig.equals(c.getString("rigId"))){
                        assertTrue("Hard planted foot must remain locked",c.getDouble("errorPixels")<=.02);
                        n++;
                    }
                }
                assertEquals("Both actor feet must be planted every impact frame",2,n);
            }
        }
    }
    @Test public void rejectedUnsupportedScenePreservesEditablePoseProgram()throws Exception{
        JSONObject built=SketchDuelAnimationExample.build(RuntimeEnvironment.getApplication());
        JSONObject source=built.getJSONObject("source"),p=SketchDocument.copy(built.getJSONObject("program"));
        p.getJSONArray("interactionCues").getJSONObject(0).put("maxGapPx",80);
        String original=SketchDocument.canonical(p);
        try{
            SketchAnimationImpactDynamics.shape(source,p);
            fail("M3.1 must not loosen an authorized tight impact cue");
        }catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("impact"));}
        assertEquals(original,SketchDocument.canonical(p));
    }
    @Test public void missingExistingActingKeyCannotBeInvented()throws Exception{
        JSONObject built=SketchDuelAnimationExample.build(RuntimeEnvironment.getApplication());
        JSONObject source=built.getJSONObject("source"),p=SketchDocument.copy(built.getJSONObject("program"));
        JSONArray rigs=p.getJSONArray("rigs");
        for(int i=0;i<rigs.length();i++){
            JSONObject r=rigs.getJSONObject(i);
            if(!"wide_rig".equals(r.getString("id")))continue;
            JSONArray updated=new JSONArray(),old=r.getJSONArray("poses");
            for(int j=0;j<old.length();j++){
                JSONObject pose=old.getJSONObject(j);
                if(pose.getInt("frame")!=11)updated.put(pose);
            }
            r.put("poses",updated);
        }
        String unchanged=SketchDocument.canonical(p);
        try{
            SketchAnimationImpactDynamics.shape(source,p);
            fail("No silent pose insertion when source is missing the impact anticipation key");
        }catch(Exception expected){assertNotNull(expected.getMessage());}
        assertEquals(unchanged,SketchDocument.canonical(p));
    }
}