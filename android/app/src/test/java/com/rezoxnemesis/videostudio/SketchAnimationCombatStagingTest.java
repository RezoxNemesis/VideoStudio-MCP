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

/** Real Robolectric native solver and compiled editable vector contact tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationCombatStagingTest {
    private final Context app=RuntimeEnvironment.getApplication();
    @Test public void batsFormVisibleInteriorCrossInsteadOfNearlyParallelTips()throws Exception{
        JSONObject built=SketchDuelAnimationExample.build(app),report=built.getJSONObject("report");
        JSONObject stage=report.getJSONObject("combatStaging");
        assertEquals("videostudio.crossed-baton-staging.v1",stage.getString("schema"));
        assertEquals(16,stage.getInt("frame"));
        assertTrue("Actual visible hands must be spaced",stage.getDouble("gripSeparationPx")>40);
        assertTrue("Weapons must be visibly crossed at impact",stage.getDouble("shaftCrossAngleDeg")>35);
        assertTrue("No fake tip-to-tip staging",stage.getDouble("tipSeparationPx")>25);
        for(String key:new String[]{"intersectionFractionA","intersectionFractionB"}){
            assertTrue(stage.getDouble(key)>.35);
            assertTrue(stage.getDouble(key)<.75);
        }
        JSONObject source=built.getJSONObject("source"),program=built.getJSONObject("program");
        assertTrue("Previous M3.2 tip-to-tip stage must be retained as history",
                report.getJSONObject("weaponContact").getDouble("afterTipGapPx")<6);
        assertEquals("Preserve original close wrist joint pose",3.4119,
                report.getDouble("batonWristDistancePx"),.2);
        JSONObject nativeCross=SketchAnimationCombatStaging.bakedCross(
                SketchAnimationCompiler.compile(source,program,"duel_staged_cross").getJSONObject("document"),16);
        assertEquals(stage.getDouble("shaftCrossAngleDeg"),nativeCross.getDouble("shaftCrossAngleDeg"),.3);
        assertEquals(stage.getDouble("impactX"),nativeCross.getDouble("impactX"),.003);
        assertEquals(stage.getDouble("impactY"),nativeCross.getDouble("impactY"),.003);
        assertTrue(nativeCross.getDouble("gripSeparationPx")>40);
        assertTrue(nativeCross.getDouble("tipSeparationPx")>25);
    }
    @Test public void nativeFeetRemainPlantedAndBodyArtworkIsNotFlattened()throws Exception{
        JSONObject b=SketchDuelAnimationExample.build(app);
        JSONObject src=b.getJSONObject("source"),prg=b.getJSONObject("program");
        JSONArray frame16=SketchAnimationCompiler.sample(src,prg,16).getJSONArray("contactErrors");
        assertEquals(4,frame16.length());
        for(int i=0;i<frame16.length();i++)assertTrue(frame16.getJSONObject(i).getDouble("errorPixels")<.001);
        for(String rig:new String[]{"wide_rig","rook_wide_rig"}){
            JSONObject d=SketchAnimationUiModel.rig(prg,rig);
            assertTrue(d.getJSONArray("poses").length()>=6);
            assertTrue(d.getJSONArray("ik").length()>=2);
        }
        assertFalse(b.getJSONObject("report").getBoolean("professionalArtQualityVerified"));
    }
    @Test public void secondStagingAttemptIsRejectedWithoutSourceMutation()throws Exception{
        JSONObject b=SketchDuelAnimationExample.build(app);
        JSONObject src=b.getJSONObject("source"),prg=b.getJSONObject("program");
        String frozen=SketchDocument.canonical(src);
        try{SketchAnimationCombatStaging.cross(src,prg);fail("Double staging overwrote edited baton");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("OMEGA_COMBAT_STAGING")||expected.getMessage().contains("OMEGA_WEAPON"));}
        assertEquals(frozen,SketchDocument.canonical(src));
    }
}