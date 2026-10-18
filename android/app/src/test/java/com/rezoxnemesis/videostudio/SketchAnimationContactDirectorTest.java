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

/** Real native rig solver regression, with no APK, device media or Gallery I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public final class SketchAnimationContactDirectorTest {
    private static JSONObject rebuiltUncorrected(JSONObject built) throws Exception {
        JSONObject original = SketchDocument.copy(built.getJSONObject("program"));
        JSONObject changes = built.getJSONObject("report").getJSONObject("contactDirector");
        JSONArray patches = changes.getJSONArray("corrections");
        for (int i = 0; i < patches.length(); i++) {
            JSONObject patch = patches.getJSONObject(i);
            JSONArray rigs = original.getJSONArray("rigs");
            for (int j = 0; j < rigs.length(); j++) {
                JSONObject rig = rigs.getJSONObject(j);
                if (!patch.getString("rig").equals(rig.getString("id"))) continue;
                JSONArray poses = rig.getJSONArray("poses");
                for (int p = 0; p < poses.length(); p++) {
                    JSONObject pose = poses.getJSONObject(p);
                    if (pose.getInt("frame") != 16) continue;
                    JSONObject angles = pose.getJSONObject("rotations");
                    String upper = patch.getString("upperJoint"), fore = patch.getString("foreJoint");
                    angles.put(upper, angles.getDouble(upper) - patch.getDouble("upperDeltaDeg"));
                    angles.put(fore, angles.getDouble(fore) - patch.getDouble("foreDeltaDeg"));
                }
            }
        }
        original.getJSONArray("interactionCues").getJSONObject(0).put("maxGapPx",80);
        return original;
    }

    private static JSONObject joint(JSONObject sample, String rig, String id) throws Exception {
        JSONArray all = sample.getJSONArray("joints");
        for (int i = 0; i < all.length(); i++) {
            JSONObject candidate = all.getJSONObject(i);
            if (rig.equals(candidate.getString("rigId")) && id.equals(candidate.getString("jointId")))
                return candidate;
        }
        throw new AssertionError("Missing tested joint " + rig + ":" + id);
    }

    private static double gap(JSONObject frame, int width, int height) throws Exception {
        JSONObject a = joint(frame,"wide_rig","wrist"), b = joint(frame,"rook_wide_rig","wrist");
        return Math.hypot((a.getDouble("x")-b.getDouble("x"))*width,
                (a.getDouble("y")-b.getDouble("y"))*height);
    }

    @Test public void authoredDuelContactIsGenuinelyTighterWithoutChangingOriginalArtOrFootPlants() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        JSONObject built = SketchDuelAnimationExample.build(app);
        JSONObject source = built.getJSONObject("source"), corrected = built.getJSONObject("program");
        JSONObject old = rebuiltUncorrected(built);
        String sourceBefore = SketchDocument.canonical(source), programBefore = SketchDocument.canonical(old);
        JSONObject before = SketchAnimationCompiler.sample(source,old,16);
        JSONObject answer = SketchAnimationContactDirector.refinePairedWristCue(
                source,old,16,"wide_rig","rook_wide_rig","wrist",6);
        JSONObject changed = answer.getJSONObject("program");
        JSONObject report = answer.getJSONObject("report");
        JSONObject after = SketchAnimationCompiler.sample(source,changed,16);
        assertTrue("This must fix the known 32px separation",gap(before,1280,720)>20);
        assertTrue("Native, independently sampled wrists must end within 6px",gap(after,1280,720)<=6);
        assertEquals(gap(before,1280,720),report.getDouble("baselineWristGapPx"),.01);
        assertEquals(gap(after,1280,720),report.getDouble("verifiedWristGapPx"),.01);
        assertEquals(2,report.getJSONArray("corrections").length());
        assertFalse(report.getBoolean("rootOrCameraEdited"));
        assertTrue(report.getBoolean("hardFootContactsPreserved"));
        assertEquals("Source vector art must be untouched",sourceBefore,SketchDocument.canonical(source));
        assertEquals("Original authored pose keys must be untouched",programBefore,SketchDocument.canonical(old));
        for (String actor : new String[]{"wide_rig","rook_wide_rig"}) {
            for (String foot : new String[]{"front_ankle","back_ankle"}) {
                JSONObject a = joint(before,actor,foot), b = joint(after,actor,foot);
                assertEquals("Foot X must not drift",a.getDouble("x"),b.getDouble("x"),1e-8);
                assertEquals("Foot Y must not drift",a.getDouble("y"),b.getDouble("y"),1e-8);
            }
        }
        assertEquals(4,before.getJSONArray("contactErrors").length());
        assertEquals(4,after.getJSONArray("contactErrors").length());
        assertEquals(1,SketchAnimationCompiler.verifyInteractionCues(source,changed)
                .getJSONArray("contacts").length());
    }

    @Test public void preparedNativeDuelBuildEnforcesTightCueInsteadOfPermissiveLegacyTolerance() throws Exception {
        JSONObject built=SketchDuelAnimationExample.build(RuntimeEnvironment.getApplication());
        JSONObject source=built.getJSONObject("source"),program=built.getJSONObject("program");
        JSONObject cue=program.getJSONArray("interactionCues").getJSONObject(0);
        assertEquals(8,cue.getDouble("maxGapPx"),0.0001);
        assertTrue(gap(SketchAnimationCompiler.sample(source,program,16),1280,720)<=6);
        JSONObject evidence=built.getJSONObject("report").getJSONObject("contactDirector");
        assertTrue(evidence.getDouble("baselineWristGapPx")-evidence.getDouble("verifiedWristGapPx")>15);
    }

    @Test public void missingAuthorizedCueOrImpactPoseIsRejectedWithoutMutation() throws Exception {
        JSONObject built=SketchDuelAnimationExample.build(RuntimeEnvironment.getApplication());
        JSONObject source=built.getJSONObject("source"),altered=rebuiltUncorrected(built);
        altered.put("interactionCues",new JSONArray());
        String before=SketchDocument.canonical(altered);
        try {
            SketchAnimationContactDirector.refinePairedWristCue(source,altered,16,"wide_rig","rook_wide_rig","wrist",6);
            fail("A missing authored cue may not be invented by the agent");
        } catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("contact cue"));}
        assertEquals(before,SketchDocument.canonical(altered));
    }

    @Test public void cannotSilentlyUseNonexistentRigOrMoveFeetByChangingRoots() throws Exception {
        JSONObject built=SketchDuelAnimationExample.build(RuntimeEnvironment.getApplication());
        JSONObject source=built.getJSONObject("source"),program=rebuiltUncorrected(built);
        try {
            SketchAnimationContactDirector.refinePairedWristCue(source,program,16,"wide_rig","unknown_rig","wrist",6);
            fail("Cannot refine nonexistent actor");
        } catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("contact cue"));}
        try {
            SketchAnimationContactDirector.refinePairedWristCue(source,program,16,"wide_rig","rook_wide_rig","wrist",.2);
            fail("Must reject out-of-contract subpixel target");
        } catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("target"));}
    }
}
