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

/** Film-proof acceptance of the real native editable baton drawings, not just wrists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationWeaponContactTest {
    private final Context app = RuntimeEnvironment.getApplication();

    @Test public void visibleNativeWeaponTipContactIsNotConfusedWithWristContact() throws Exception {
        JSONObject generated = SketchDuelAnimationExample.build(app);
        JSONObject source = generated.getJSONObject("source"), program = generated.getJSONObject("program");
        JSONObject reported = generated.getJSONObject("report").getJSONObject("weaponContact");
        assertTrue("Original M3.1 wrist-only pose visibly separated the batons",
                reported.getDouble("beforeTipGapPx") > 200);
        assertTrue("Editable shafts now converge at the drawn contact", reported.getDouble("afterTipGapPx") < 6);
        assertTrue(reported.getDouble("afterCenterlineGapPx") < 6);
        assertEquals("The previous wrist contact still must stay tight", 3.4119,
                generated.getJSONObject("report").getDouble("batonWristDistancePx"), .2);
        assertEquals("No automatic film acceptance", "MANUAL_REVIEW_REQUIRED", reported.getString("releaseDecision"));
        assertTrue("Clash must be drawn above actors' wrists", reported.getDouble("impactY") < .53);
        assertTrue("Shared frame-16 impact is in the photographed source canvas",
                reported.getDouble("impactX") > .35 && reported.getDouble("impactX") < .7);
        JSONArray frames = SketchAnimationWeaponContact.trace(source, program, 0, 35).getJSONArray("frames");
        assertEquals(36, frames.length());
        assertTrue("Native weapon tip separation must meet at contact", frames.getJSONObject(16).getDouble("tipGapPx") < 6);
        for (int i = 0; i < 36; i++) {
            JSONObject row = frames.getJSONObject(i);
            assertEquals(i, row.getInt("frame"));
            JSONArray a = row.getJSONArray("wideCore"), b = row.getJSONArray("rookCore");
            assertEquals(2, a.length());assertEquals(2, b.length());
            for (JSONArray seg : new JSONArray[]{a, b})
                for (int p = 0; p < 2; p++) {
                    JSONArray xy = seg.getJSONArray(p);
                    assertTrue(xy.getDouble(0) >= 0 && xy.getDouble(0) <= 1280);
                    assertTrue(xy.getDouble(1) >= 0 && xy.getDouble(1) <= 720);
                }
        }
    }

    @Test public void compiledEditableVectorKeysActuallyConvergeVisually() throws Exception {
        JSONObject generated = SketchDuelAnimationExample.build(app);
        JSONObject compiled = SketchAnimationCompiler.compile(generated.getJSONObject("source"),
                generated.getJSONObject("program"), "weapon_contact_visual_native").getJSONObject("document");
        double actual = SketchAnimationWeaponContact.bakedTipGap(compiled, 16);
        assertTrue("The Canvas-drawn baton cores must meet after native key reduction, measured gap=" + actual,
                actual < 8);
        assertFalse(generated.getJSONObject("report").getBoolean("professionalArtQualityVerified"));
    }

    @Test public void forgedBindingsAndMissingWeaponCoresAreRejectedWithoutMutation() throws Exception {
        JSONObject generated = SketchDuelAnimationExample.build(app);
        JSONObject source = generated.getJSONObject("source"), program = generated.getJSONObject("program");
        String snapshot = SketchDocument.canonical(source);
        JSONObject tamperedProgram = SketchDocument.copy(program);
        JSONArray bindings = SketchAnimationUiModel.rig(tamperedProgram, "wide_rig").getJSONArray("bindings");
        for (int i = 0; i < bindings.length(); i++){
            JSONObject b = bindings.getJSONObject(i);
            if ("wide_baton_core".equals(b.getString("objectId"))){b.put("jointId", "head");break;}
        }
        try{SketchAnimationWeaponContact.trace(source, tamperedProgram,0,35); fail("Forged baton rig admitted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("wrist-bound"));}
        assertEquals("Rejected geometry changes may not alter the approved source", snapshot, SketchDocument.canonical(source));
    }

    @Test public void approvedDrawingsAreNotSilentlyReaimedTwice() throws Exception {
        JSONObject generated = SketchDuelAnimationExample.build(app);
        JSONObject source = generated.getJSONObject("source"), program = generated.getJSONObject("program");
        String frozen = SketchDocument.canonical(source);
        try{ SketchAnimationWeaponContact.aim(source, program); fail("Repeated weapon edit overwrote manual keys"); }
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("artist-authored"));}
        assertEquals(frozen, SketchDocument.canonical(source));
    }
}