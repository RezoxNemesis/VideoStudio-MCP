package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OMEGA M3.1: a bounded, editable 2D impact acting pass over prepared native rig keys.
 * Changes anticipation, weight shift, torso/head response, rear-arm balance and scarf lag
 * on five EXISTING keys only. It never moves the frame-16 contact key, changes source
 * artwork, overwrites a project, or edits the camera. The native solver checks every
 * authored planted foot and the exact wrist trajectory before returning a copy.
 *
 * This is authored secondary acting, not a general rigid-body/cloth simulator.
 */
final class SketchAnimationImpactDynamics {
    private static final int WIDTH = 1280, HEIGHT = 720, IMPACT = 16;
    private static final int[] KEYS = {5, 11, 20, 25, 30};
    private static final String[] ACTORS = {"wide_rig", "rook_wide_rig"};
    private static final double[] ROOT_SINK = {.003, .006, .012, .006, .002};
    private static final String[] BONES = {"chest", "head", "scarf", "back_upper", "front_upper", "front_fore"};
    private static final double[][] HERO_DEGREES = {
        {-2, -2,  4, -2,  0,  0},
        { 3,  4,  6,  3,  6,  3},
        { 2, -6, -8,  6, -5, -2},
        {-2, -5, -6, -3,  0,  0},
        { 0, -2, -4, -1,  0,  0}
    };
    private SketchAnimationImpactDynamics() {}

    static JSONObject shape(JSONObject source, JSONObject original) throws Exception {
        if (source == null || original == null || source.getInt("width") != WIDTH ||
                source.getInt("height") != HEIGHT || original.getInt("frameCount") != 100 ||
                original.getInt("fps") != 24) throw bad("Only the reviewed 100-frame 720p native duel is supported");
        JSONArray cues = original.getJSONArray("interactionCues");
        if (cues.length() != 1) throw bad("Expected precisely one reviewed original impact cue");
        JSONObject cue = cues.getJSONObject(0);
        if (cue.getInt("frame") != IMPACT || !"wide_rig".equals(cue.getString("rigA")) ||
                !"rook_wide_rig".equals(cue.getString("rigB")) ||
                !"wrist".equals(cue.getString("jointA")) || !"wrist".equals(cue.getString("jointB")) ||
                cue.getDouble("maxGapPx") > 8.0) throw bad("Reviewed eight-pixel authored contact must exist");

        JSONArray old = SketchAnimationCompiler.sampleFrames(source, original, 5, 30);
        JSONObject copy = SketchDocument.copy(original);
        JSONArray changeLog = new JSONArray();
        for (int actor = 0; actor < ACTORS.length; actor++) {
            JSONObject rig = rig(copy, ACTORS[actor]);
            JSONArray poses = rig.getJSONArray("poses");
            int matched = 0;
            for (int k = 0; k < KEYS.length; k++) {
                JSONObject pose = pose(poses, KEYS[k]);
                JSONObject angles = pose.getJSONObject("rotations");
                double previousSink = pose.optDouble("rootY", 0);
                if (!Double.isFinite(previousSink) || Math.abs(previousSink) > .08)
                    throw bad("Root movement is already outside the reviewed bound");
                pose.put("rootY", previousSink + ROOT_SINK[k]);
                for (int b = 0; b < BONES.length; b++) {
                    // Both actors react symmetrically, with independently authored initial keys.
                    // RootX and the impact pose remain byte-for-byte unchanged.
                    double before = angles.optDouble(BONES[b], 0);
                    if (!Double.isFinite(before)) throw bad("Nonfinite bone rotation");
                    double delta = (actor == 0 ? 1 : -1) * HERO_DEGREES[k][b];
                    if (delta != 0) angles.put(BONES[b], before + delta);
                }
                changeLog.put(new JSONObject().put("rigId", ACTORS[actor]).put("frame", KEYS[k])
                        .put("rootSinkNormalized", ROOT_SINK[k]));
                matched++;
            }
            if (matched != KEYS.length) throw bad("Missing prepared pose key");
        }
        JSONArray result = SketchAnimationCompiler.sampleFrames(source, copy, 5, 30);
        if (result.length() != 26 || old.length() != 26) throw bad("Native sampler omitted acting frames");
        JSONObject beforeImpact = old.getJSONObject(IMPACT - 5);
        JSONObject afterImpact = result.getJSONObject(IMPACT - 5);
        double beforeGap = gap(beforeImpact), afterGap = gap(afterImpact);
        if (beforeGap > 6.001 || Math.abs(afterGap - beforeGap) > .001)
            throw bad("M3 impact alignment changed during the acting pass");
        double oldPeak = maxWristStep(old), newPeak = maxWristStep(result);
        if (newPeak > 55 || oldPeak - newPeak < 5)
            throw bad("Edited key trajectories did not remove the reviewed abrupt wrist movement");
        double maxError = 0; int footEvidence = 0;
        for (int index = 0; index < result.length(); index++) {
            JSONObject f = result.getJSONObject(index), prior = old.getJSONObject(index);
            int frame = index + 5;
            if (frame == IMPACT && !SketchDocument.canonical(f).equals(SketchDocument.canonical(prior)))
                throw bad("Impact-frame native skeleton changed");
            for (String actor : ACTORS) {
                double x = joint(f, actor, "root").getDouble("x");
                if (Math.abs(x - joint(prior, actor, "root").getDouble("x")) > 1e-9)
                    throw bad("Unapproved horizontal character translation");
            }
            JSONArray contacts = f.getJSONArray("contactErrors");
            int matched = 0;
            for (int c = 0; c < contacts.length(); c++) {
                JSONObject item = contacts.getJSONObject(c);
                if (!ACTORS[0].equals(item.optString("rigId")) &&
                        !ACTORS[1].equals(item.optString("rigId"))) continue;
                if (!"front_ankle".equals(item.optString("jointId")) &&
                        !"back_ankle".equals(item.optString("jointId"))) continue;
                double error = item.getDouble("errorPixels");
                if (!Double.isFinite(error) || error < 0 || error > .02)
                    throw bad("Planted foot slipped at frame " + frame);
                matched++; footEvidence++; maxError = Math.max(maxError, error);
            }
            if (matched != 4) throw bad("A planted sole disappeared at frame " + frame);
        }
        double heroSink = (joint(result.getJSONObject(20-5), ACTORS[0], "root").getDouble("y")-
                joint(result.getJSONObject(IMPACT-5), ACTORS[0], "root").getDouble("y")) * HEIGHT;
        double rivalSink = (joint(result.getJSONObject(20-5), ACTORS[1], "root").getDouble("y")-
                joint(result.getJSONObject(IMPACT-5), ACTORS[1], "root").getDouble("y")) * HEIGHT;
        if (heroSink < 5 || heroSink > 20 || rivalSink < 5 || rivalSink > 20)
            throw bad("Root weight response outside reviewed acting bounds");
        // The original pose program remains immutable. This report is observational and
        // must not be confused with a completed film or body dynamics simulation.
        JSONObject report = new JSONObject().put("schema", "videostudio.impact-dynamics.v1")
                .put("impactFrame", IMPACT).put("unchangedContactGapPx", afterGap)
                .put("originalMaxWristStepPx", oldPeak).put("verifiedMaxWristStepPx", newPeak)
                .put("heroPostImpactSinkPx", heroSink).put("rivalPostImpactSinkPx", rivalSink)
                .put("checkedPlantedFootSamples", footEvidence).put("maxHardFootErrorPx", maxError)
                .put("keyframeCorrections", changeLog).put("impactPoseUnchanged", true)
                .put("sourceArtChanged", false).put("cameraChanged", false)
                .put("motionKind", "bounded-authored-pose-response-not-general-physics")
                .put("humanReviewRequired", true);
        return new JSONObject().put("program", copy).put("report", report);
    }

    private static JSONObject rig(JSONObject program, String id) throws Exception {
        JSONArray rigs = program.getJSONArray("rigs");
        for (int i = 0; i < rigs.length(); i++) {
            JSONObject r = rigs.getJSONObject(i);
            if (id.equals(r.getString("id"))) return r;
        }
        throw bad("Missing prepared actor rig: " + id);
    }
    private static JSONObject pose(JSONArray poses, int frame) throws Exception {
        for (int i = 0; i < poses.length(); i++) {
            JSONObject p = poses.getJSONObject(i);
            if (p.getInt("frame") == frame) return p;
        }
        throw bad("Missing required acting key at frame " + frame);
    }
    private static JSONObject joint(JSONObject snapshot, String actor, String part) throws Exception {
        JSONArray array = snapshot.getJSONArray("joints");
        for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.getJSONObject(i);
            if (actor.equals(j.getString("rigId")) && part.equals(j.getString("jointId"))) return j;
        }
        throw bad("Missing solver joint: " + actor + ":" + part);
    }
    private static double gap(JSONObject frame) throws Exception {
        JSONObject a = joint(frame, ACTORS[0], "wrist"), b = joint(frame, ACTORS[1], "wrist");
        return Math.hypot((a.getDouble("x")-b.getDouble("x"))*WIDTH,
                (a.getDouble("y")-b.getDouble("y"))*HEIGHT);
    }
    private static double maxWristStep(JSONArray samples) throws Exception {
        double maximum = 0;
        for (int i = 12-5; i <= 20-5; i++) {
            JSONObject now = samples.getJSONObject(i), before = samples.getJSONObject(i-1);
            for (String actor : ACTORS) {
                JSONObject a = joint(now, actor, "wrist"), b = joint(before, actor, "wrist");
                maximum = Math.max(maximum, Math.hypot((a.getDouble("x")-b.getDouble("x"))*WIDTH,
                        (a.getDouble("y")-b.getDouble("y"))*HEIGHT));
            }
        }
        return maximum;
    }
    private static IllegalArgumentException bad(String why) {
        return new IllegalArgumentException("OMEGA_IMPACT: " + why);
    }
}