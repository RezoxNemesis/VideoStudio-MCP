package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded, non-destructive contact pose refinement for an explicitly authored
 * two-rig wrist cue. Adjusts only the two arm joint angles at an EXISTING pose
 * key; never moves character roots, feet, media or camera. The exact native
 * SketchAnimationCompiler is the admission and final verification authority.
 *
 * This is a 2D joint-proximity correction, NOT blade/silhouette collision,
 * IK for arbitrary limbs, cloth dynamics or a professional animation grade.
 */
final class SketchAnimationContactDirector {
    private static final double MAX_ANGLE_DELTA_DEG = 22.0;
    private static final double MAX_STEP_DEG = 6.0;
    private static final double DAMPING = .6;
    private static final int MAX_ITERATIONS = 12;
    private static final double RAD_PER_DEG = Math.PI / 180.0;

    private SketchAnimationContactDirector() {}

    static JSONObject refinePairedWristCue(JSONObject source, JSONObject original, int frame,
                                          String rigA, String rigB, String jointId,
                                          double targetGapPx) throws Exception {
        if (source == null || original == null || rigA == null || rigB == null || jointId == null ||
                rigA.equals(rigB) || !Double.isFinite(targetGapPx) || targetGapPx < 1 || targetGapPx > 20)
            throw bad("Two distinct rigs, joint and 1..20-pixel target are required");
        // This single authoritative native sample validates the source, rig model,
        // timeline, contacts and original user-authored pose without mutation.
        JSONObject baseline = SketchAnimationCompiler.sample(source, original, frame);
        JSONObject cue = findCue(original.getJSONArray("interactionCues"), frame, rigA, rigB, jointId);
        double authoredLimit = cue.getDouble("maxGapPx");
        if (!Double.isFinite(authoredLimit) || authoredLimit <= targetGapPx)
            throw bad("Contact refinement requires an authored tolerance above the requested target");
        int width = source.getInt("width"), height = source.getInt("height");
        Limb first = limb(baseline, original, rigA, jointId, frame, width, height);
        Limb second = limb(baseline, original, rigB, jointId, frame, width, height);
        double before = distance(first.tip, second.tip);
        if (before > authoredLimit + 1e-6)
            throw bad("Native source contact violates the existing authored contact cue");

        double[] angles = new double[4];
        int iterations = 0;
        for (; iterations < MAX_ITERATIONS; iterations++) {
            Point[] positions = targets(first, second, angles);
            double gap = distance(positions[0], positions[1]);
            if (gap <= targetGapPx) break;
            // Exact 2D Jacobian for the distal wrist under two serial local rotations.
            // The native solver does not apply the leg IK to these arm joints.
            Point pivot0 = first.upper;
            Point pivot1 = rotateAbout(first.fore, first.upper, angles[0]);
            Point pivot2 = second.upper;
            Point pivot3 = rotateAbout(second.fore, second.upper, angles[2]);
            Point[] columns = {
                tangent(positions[0], pivot0, 1), tangent(positions[0], pivot1, 1),
                tangent(positions[1], pivot2, -1), tangent(positions[1], pivot3, -1),
            };
            double xx = DAMPING, yy = DAMPING, xy = 0;
            for (Point col : columns) {
                xx += col.x * col.x;
                xy += col.x * col.y;
                yy += col.y * col.y;
            }
            double determinant = xx * yy - xy * xy;
            if (!Double.isFinite(determinant) || determinant <= 1e-12)
                throw bad("Cannot solve degenerate wrist geometry");
            double dx = positions[0].x - positions[1].x;
            double dy = positions[0].y - positions[1].y;
            double vx = (yy * dx - xy * dy) / determinant;
            double vy = (-xy * dx + xx * dy) / determinant;
            double maxChange = 0;
            for (int j = 0; j < angles.length; j++) {
                double change = clamp(-(columns[j].x * vx + columns[j].y * vy), -MAX_STEP_DEG, MAX_STEP_DEG);
                double updated = clamp(angles[j] + change, -MAX_ANGLE_DELTA_DEG, MAX_ANGLE_DELTA_DEG);
                maxChange = Math.max(maxChange, Math.abs(updated - angles[j]));
                angles[j] = updated;
            }
            if (maxChange < 1e-7) break;
        }
        double predicted = distance(targets(first, second, angles)[0], targets(first, second, angles)[1]);
        if (!Double.isFinite(predicted) || predicted > targetGapPx)
            throw bad("Arm-angle bounds cannot achieve target contact; redraw/re-author this action");
        JSONObject next = SketchDocument.copy(original);
        apply(next, rigA, first.upperId, first.foreId, frame, angles[0], angles[1]);
        apply(next, rigB, second.upperId, second.foreId, frame, angles[2], angles[3]);
        // Never assert success from our analytic approximation alone. Check the
        // rebuilt joint positions through the same native solver used to bake.
        JSONObject checked = SketchAnimationCompiler.sample(source, next, frame);
        Point a = point(joint(checked, rigA, jointId), width, height);
        Point b = point(joint(checked, rigB, jointId), width, height);
        double verified = distance(a, b);
        if (!Double.isFinite(verified) || verified > targetGapPx + 1e-6 ||
                Math.abs(verified - predicted) > .01)
            throw bad("Native solver disagrees with proposed wrist-contact refinement");
        JSONArray beforeContacts = baseline.getJSONArray("contactErrors");
        JSONArray afterContacts = checked.getJSONArray("contactErrors");
        if (beforeContacts.length() != afterContacts.length())
            throw bad("Refinement unexpectedly changes planted-foot contacts");
        for (int i = 0; i < beforeContacts.length(); i++) {
            JSONObject prior = beforeContacts.getJSONObject(i), after = afterContacts.getJSONObject(i);
            if (!prior.getString("rigId").equals(after.getString("rigId")) ||
                    !prior.getString("jointId").equals(after.getString("jointId")) ||
                    Math.abs(prior.getDouble("errorPixels") - after.getDouble("errorPixels")) > .01)
                throw bad("Existing planted-foot constraint changed");
        }
        JSONArray corrections = new JSONArray();
        corrections.put(new JSONObject().put("rig", rigA).put("upperJoint", first.upperId)
                .put("foreJoint", first.foreId).put("upperDeltaDeg", angles[0]).put("foreDeltaDeg", angles[1]));
        corrections.put(new JSONObject().put("rig", rigB).put("upperJoint", second.upperId)
                .put("foreJoint", second.foreId).put("upperDeltaDeg", angles[2]).put("foreDeltaDeg", angles[3]));
        JSONObject report = new JSONObject()
                .put("schema", "videostudio.contact-director.v1")
                .put("frame", frame).put("joint", jointId)
                .put("rigA", rigA).put("rigB", rigB)
                .put("authoredTolerancePx", authoredLimit)
                .put("requestedGapPx", targetGapPx)
                .put("baselineWristGapPx", before)
                .put("verifiedWristGapPx", verified)
                .put("solverIterations", iterations)
                .put("maximumAngleDeltaDeg", MAX_ANGLE_DELTA_DEG)
                .put("rootOrCameraEdited", false)
                .put("hardFootContactsPreserved", true)
                .put("corrections", corrections)
                .put("evidence", "Compiled native world-space wrist centers; not weapon mesh or silhouette contact");
        return new JSONObject().put("program", next).put("report", report);
    }

    private static JSONObject findCue(JSONArray cues, int frame, String a, String b, String jointId) throws Exception {
        for (int i = 0; i < cues.length(); i++) {
            JSONObject cue = cues.getJSONObject(i);
            boolean direct = a.equals(cue.optString("rigA")) && b.equals(cue.optString("rigB")) &&
                    jointId.equals(cue.optString("jointA")) && jointId.equals(cue.optString("jointB"));
            boolean reversed = b.equals(cue.optString("rigA")) && a.equals(cue.optString("rigB")) &&
                    jointId.equals(cue.optString("jointA")) && jointId.equals(cue.optString("jointB"));
            if (cue.optInt("frame", -1) == frame && (direct || reversed)) return cue;
        }
        throw bad("No authored wrist-to-wrist contact cue at requested keyframe");
    }

    private static Limb limb(JSONObject snapshot, JSONObject program, String rigId, String distalId,
                             int frame, int width, int height) throws Exception {
        JSONObject distal = joint(snapshot, rigId, distalId);
        if (distal.isNull("parent")) throw bad("Wrist needs two upper-arm ancestors");
        String foreId = distal.getString("parent");
        JSONObject fore = joint(snapshot, rigId, foreId);
        if (fore.isNull("parent")) throw bad("Wrist must have an articulated upper arm");
        String upperId = fore.getString("parent");
        JSONObject upper = joint(snapshot, rigId, upperId);
        // The scene must have an EXACT authored contact pose on both actors.
        JSONObject rig = findRig(program, rigId);
        JSONObject key = findPose(rig, frame);
        JSONObject rotations = key.getJSONObject("rotations");
        for (String id : new String[]{upperId, foreId}) {
            if (!rotations.has(id) || !Double.isFinite(rotations.getDouble(id)))
                throw bad("The contact pose must author both arm joint angles: " + rigId + ":" + id);
        }
        return new Limb(upperId, foreId, point(upper, width, height),
                point(fore, width, height), point(distal, width, height));
    }

    private static JSONObject findRig(JSONObject program, String id) throws Exception {
        JSONArray rigs = program.getJSONArray("rigs");
        for (int i = 0; i < rigs.length(); i++) {
            JSONObject rig = rigs.getJSONObject(i);
            if (id.equals(rig.getString("id"))) return rig;
        }
        throw bad("Missing native rig: " + id);
    }

    private static JSONObject findPose(JSONObject rig, int frame) throws Exception {
        JSONArray poses = rig.getJSONArray("poses");
        for (int i = 0; i < poses.length(); i++) {
            JSONObject pose = poses.getJSONObject(i);
            if (pose.getInt("frame") == frame) return pose;
        }
        throw bad("Contact refinement requires an existing exact pose key at frame " + frame);
    }

    private static void apply(JSONObject next, String rigId, String upperId, String foreId,
                              int frame, double upperDelta, double foreDelta) throws Exception {
        JSONObject rotations = findPose(findRig(next, rigId), frame).getJSONObject("rotations");
        rotations.put(upperId, rotations.getDouble(upperId) + upperDelta);
        rotations.put(foreId, rotations.getDouble(foreId) + foreDelta);
    }

    private static JSONObject joint(JSONObject frame, String rigId, String jointId) throws Exception {
        JSONArray joints = frame.getJSONArray("joints");
        for (int i = 0; i < joints.length(); i++) {
            JSONObject j = joints.getJSONObject(i);
            if (rigId.equals(j.getString("rigId")) && jointId.equals(j.getString("jointId"))) return j;
        }
        throw bad("Missing authored joint " + rigId + ":" + jointId);
    }

    private static Point point(JSONObject j, int width, int height) throws Exception {
        return new Point(j.getDouble("x") * width, j.getDouble("y") * height);
    }
    private static Point rotateAbout(Point p, Point pivot, double degrees) {
        double r = degrees * RAD_PER_DEG, c = Math.cos(r), s = Math.sin(r);
        double dx = p.x - pivot.x, dy = p.y - pivot.y;
        return new Point(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c);
    }
    private static Point tangent(Point wrist, Point pivot, int sign) {
        return new Point(-sign * (wrist.y - pivot.y) * RAD_PER_DEG,
                sign * (wrist.x - pivot.x) * RAD_PER_DEG);
    }
    private static Point[] targets(Limb first, Limb second, double[] deltas) {
        return new Point[]{ target(first, deltas[0], deltas[1]), target(second, deltas[2], deltas[3]) };
    }
    private static Point target(Limb limb, double upper, double fore) {
        Point adjustedFore = rotateAbout(limb.fore, limb.upper, upper);
        Point wristDelta = new Point(limb.tip.x - limb.fore.x, limb.tip.y - limb.fore.y);
        double r = (upper + fore) * RAD_PER_DEG, c = Math.cos(r), s = Math.sin(r);
        return new Point(adjustedFore.x + wristDelta.x * c - wristDelta.y * s,
                adjustedFore.y + wristDelta.x * s + wristDelta.y * c);
    }
    private static double distance(Point a, Point b) {return Math.hypot(a.x - b.x, a.y - b.y);}
    private static double clamp(double value, double min, double max) {return Math.max(min, Math.min(max, value));}
    private static IllegalArgumentException bad(String message) {return new IllegalArgumentException("OMEGA_CONTACT: " + message);}
    private static final class Limb {
        final String upperId, foreId;
        final Point upper, fore, tip;
        Limb(String upperId, String foreId, Point upper, Point fore, Point tip) {
            this.upperId=upperId;this.foreId=foreId;this.upper=upper;this.fore=fore;this.tip=tip;
        }
    }
    private static final class Point {final double x,y;Point(double x,double y){this.x=x;this.y=y;}}
}
