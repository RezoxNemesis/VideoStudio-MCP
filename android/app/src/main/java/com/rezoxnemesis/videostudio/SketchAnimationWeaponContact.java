package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OMEGA M3.2: aim ACTUAL prepared, wrist-bound vector baton drawings instead of
 * substituting wrist-to-wrist proximity for weapon contact. Only source-document
 * weapon transforms are edited; authored pose keys, native IK, artwork coordinates,
 * camera, signing identity and other documents are untouched.
 *
 * This module verifies the projected 2D drawn centre-lines. It is NOT a
 * rigid-body/3D swept collision system or a film-quality certification.
 */
final class SketchAnimationWeaponContact {
    static final int CONTACT_FRAME = 16;
    private static final int[] FRAMES = {0, 8, 10, 12, 14, 16, 18, 20, 24, 30, 35};
    private static final double[] ENVELOPE = {0, 0, 0, .10, .79, 1, 1, 1, 0, 0, 0};
    private static final String[] WIDE = {"wide_baton", "wide_baton_edge", "wide_baton_core", "wide_baton_grip"};
    private static final String[] ROOK = {"rook_wide_baton", "rook_wide_baton_edge", "rook_wide_baton_core", "rook_wide_baton_grip"};
    private SketchAnimationWeaponContact() {}

    /** The return value is an independent editable source document and measured report. */
    static JSONObject aim(JSONObject originalSource, JSONObject program) throws Exception {
        SketchDocument.validate(originalSource);
        int w = originalSource.getInt("width"), h = originalSource.getInt("height");
        if (w != 1280 || h != 720 || program.getInt("frameCount") != 100)
            throw invalid("M3.2 weapon stage currently accepts only the reviewed 1280x720/100-frame duel");
        JSONObject baseline = SketchAnimationCompiler.sample(originalSource, program, CONTACT_FRAME);
        JSONObject next = SketchDocument.copy(originalSource);
        verifyRig(next, program, "wide_rig", WIDE);
        verifyRig(next, program, "rook_wide_rig", ROOK);
        Pair before = pair(next, program, baseline, CONTACT_FRAME);
        // Use same source-canvas frame as the native IK, not a moving screen/camera.
        double referenceReach = Math.min(before.a.length, before.b.length);
        double targetX = (before.a.wrist.x + before.b.wrist.x) * .5;
        double targetY = (before.a.wrist.y + before.b.wrist.y) * .5 - referenceReach + 1;
        Point target = new Point(targetX, targetY);
        double firstOffset = aimRotation(before.a, target);
        double secondOffset = aimRotation(before.b, target);
        if (Math.abs(firstOffset) > 85 || Math.abs(secondOffset) > 85 ||
                firstOffset > -25 || secondOffset < 25)
            throw invalid("The prepared baton artwork cannot be safely turned toward the shared impact");
        changeGroup(next, program, "wide_rig", WIDE, firstOffset);
        changeGroup(next, program, "rook_wide_rig", ROOK, secondOffset);
        SketchDocument.validate(next);
        // Same native rig solver as Canvas/bake, with all foot contacts still active.
        JSONObject afterSample = SketchAnimationCompiler.sample(next, program, CONTACT_FRAME);
        Pair after = pair(next, program, afterSample, CONTACT_FRAME);
        double centerlineDistance = segmentDistance(after.a, after.b);
        double tipDistance = distance(after.a.tip, after.b.tip);
        if (!Double.isFinite(tipDistance) || tipDistance > 6 || centerlineDistance > 6 ||
                beforeTipDistance(before) < 50)
            throw invalid("Projected baton tips/centrelines do not meet at the intended impact frame");
        JSONArray oldFeet = baseline.getJSONArray("contactErrors"), newFeet = afterSample.getJSONArray("contactErrors");
        if (oldFeet.length() != newFeet.length() || oldFeet.length() < 4)
            throw invalid("The four native planted-foot constraints are missing");
        for (int i = 0; i < oldFeet.length(); i++) {
            JSONObject a = oldFeet.getJSONObject(i), b = newFeet.getJSONObject(i);
            if (!a.getString("rigId").equals(b.getString("rigId")) ||
                    !a.getString("jointId").equals(b.getString("jointId")) ||
                    Math.abs(a.getDouble("errorPixels") - b.getDouble("errorPixels")) > .001)
                throw invalid("The weapon correction unexpectedly changed planted feet");
        }
        for (int frame : new int[]{0, 8, 12, 14, 16, 18, 20, 24, 30, 35}) {
            JSONObject sampled = SketchAnimationCompiler.sample(next, program, frame);
            Pair inspected = pair(next, program, sampled, frame);
            if (!finite(inspected.a.tip) || !finite(inspected.b.tip) ||
                    !within(inspected.a.tip, w, h) || !within(inspected.b.tip, w, h))
                throw invalid("Weapon tip left the canvas at frame " + frame);
        }
        Point contactPoint = new Point((after.a.tip.x + after.b.tip.x) / 2,
                (after.a.tip.y + after.b.tip.y) / 2);
        JSONObject report = new JSONObject()
                .put("schema", "videostudio.weapon-silhouette-contact.v1")
                .put("frame", CONTACT_FRAME).put("rigA", "wide_rig").put("rigB", "rook_wide_rig")
                .put("beforeTipGapPx", beforeTipDistance(before))
                .put("beforeCenterlineGapPx", segmentDistance(before.a, before.b))
                .put("afterTipGapPx", tipDistance).put("afterCenterlineGapPx", centerlineDistance)
                .put("wideRotationOffsetDegrees", firstOffset)
                .put("rookRotationOffsetDegrees", secondOffset)
                .put("impactX", contactPoint.x / w).put("impactY", contactPoint.y / h)
                .put("hardFootContactsPreserved", true)
                .put("sourceOnlyWeaponTransforms", true)
                .put("releaseDecision", "MANUAL_REVIEW_REQUIRED")
                .put("evidence", "Native joint-transformed editable vector baton CORE centre-lines; not full baton silhouette/3D mesh collision");
        return new JSONObject().put("source", next).put("report", report);
    }

    /** Used by Robolectric to export exact Native Canvas/rig-based weapon traces. */
    static JSONObject trace(JSONObject source, JSONObject program, int start, int end) throws Exception {
        if (start != 0 || end != 35) throw invalid("Reviewed M3.2 wide shot uses exactly frames 0..35");
        verifyRig(source, program, "wide_rig", WIDE);
        verifyRig(source, program, "rook_wide_rig", ROOK);
        JSONArray sampled = SketchAnimationCompiler.sampleFrames(source, program, start, end);
        JSONArray frames = new JSONArray();
        for (int frame = start; frame <= end; frame++) {
            Pair p = pair(source, program, sampled.getJSONObject(frame - start), frame);
            frames.put(new JSONObject().put("frame", frame)
                    .put("wideCore", points(p.a)).put("rookCore", points(p.b))
                    .put("centerlineGapPx", segmentDistance(p.a, p.b))
                    .put("tipGapPx", distance(p.a.tip, p.b.tip)));
        }
        return new JSONObject().put("schema", "videostudio.native-baton-core-trace.v1")
                .put("frameCount", frames.length()).put("firstFrame", start).put("lastFrame", end)
                .put("width", source.getInt("width")).put("height", source.getInt("height"))
                .put("sourceSpace", "native-canvas-pixels-before-camera")
                .put("fullSilhouetteVerified", false)
                .put("frames", frames);
    }

    /** Inspect actual compiled vector-object transform, after key reduction, not only the rig solver. */
    static double bakedTipGap(JSONObject compiledDocument, int frame) throws Exception {
        int w = compiledDocument.getInt("width"), h = compiledDocument.getInt("height");
        Point a = renderedTip(compiledDocument, "wide_baton_core", frame, w, h);
        Point b = renderedTip(compiledDocument, "rook_wide_baton_core", frame, w, h);
        return distance(a, b);
    }

    private static Point renderedTip(JSONObject document, String id, int frame, int w, int h) throws Exception {
        JSONObject shape = SketchDocument.object(document, id);
        JSONArray commands = shape.getJSONArray("commands");
        if (commands.length() != 2) throw invalid("Compiled weapon lost its editable two-point core");
        JSONArray cmd = commands.getJSONArray(1);
        Point raw = point(cmd, w, h);
        JSONObject t = SketchDocument.transformAt(shape, frame);
        double ax = t.getDouble("anchorX") * w, ay = t.getDouble("anchorY") * h;
        double dx = (raw.x - ax) * t.getDouble("scaleX");
        double dy = (raw.y - ay) * t.getDouble("scaleY");
        double rotation = Math.toRadians(t.getDouble("rotation"));
        return new Point(ax + t.getDouble("x") * w + Math.cos(rotation) * dx - Math.sin(rotation) * dy,
                ay + t.getDouble("y") * h + Math.sin(rotation) * dx + Math.cos(rotation) * dy);
    }

    private static JSONArray points(Weapon weapon) {
        return new JSONArray().put(new JSONArray().put(weapon.grip.x).put(weapon.grip.y))
                .put(new JSONArray().put(weapon.tip.x).put(weapon.tip.y));
    }

    private static void changeGroup(JSONObject source, JSONObject program, String rigName,
                                    String[] objects, double amplitude) throws Exception {
        JSONObject rig = rig(program, rigName);
        JSONObject rest = restJoint(rig);
        for (String id : objects) {
            JSONObject obj = SketchDocument.object(source, id);
            if (obj.getJSONArray("keys").length() != 0 ||
                    obj.getJSONObject("transform").getDouble("rotation") != 0)
                throw invalid("Cannot overwrite artist-authored weapon animation: " + id);
            JSONObject base = obj.getJSONObject("transform");
            base.put("anchorX", rest.getDouble("x")).put("anchorY", rest.getDouble("y"));
            JSONArray keys = new JSONArray();
            for (int i = 0; i < FRAMES.length; i++) {
                JSONObject pose = SketchDocument.copy(base).put("rotation", amplitude * ENVELOPE[i]);
                keys.put(new JSONObject().put("frame", FRAMES[i])
                        .put("transform", pose).put("interpolation", "linear"));
            }
            obj.put("keys", keys);
        }
    }

    private static void verifyRig(JSONObject source, JSONObject program, String id,
                                  String[] ids) throws Exception {
        JSONObject rig = rig(program, id);
        for (String name : ids) {
            JSONObject obj = SketchDocument.object(source, name);
            if (!"path".equals(obj.optString("kind")) || obj.getInt("frame") != 0 ||
                    obj.getInt("exposure") < 36 ||
                    !"wrist".equals(bindingJoint(rig, name)))
                throw invalid("Prepared baton artwork is not a visible wrist-bound vector path: " + name);
        }
        JSONObject core = SketchDocument.object(source, ids[2]);
        JSONArray commands = core.getJSONArray("commands");
        if (commands.length() != 2 || !"M".equals(commands.getJSONArray(0).getString(0)) ||
                !"L".equals(commands.getJSONArray(1).getString(0)))
            throw invalid("Weapon core must be a 2-point editable M/L vector path");
        if (!SketchDocument.owner(source, ids[2]).getBoolean("visible"))
            throw invalid("Weapon source layer is hidden");
    }

    private static String bindingJoint(JSONObject rig, String objectId) throws Exception {
        JSONArray items = rig.getJSONArray("bindings");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (objectId.equals(item.getString("objectId"))) return item.optString("jointId", "");
        }
        return "";
    }

    private static JSONObject rig(JSONObject program, String id) throws Exception {
        JSONArray rigs = program.getJSONArray("rigs");
        for (int i = 0; i < rigs.length(); i++)
            if (id.equals(rigs.getJSONObject(i).getString("id"))) return rigs.getJSONObject(i);
        throw invalid("Unknown prepared weapon rig: " + id);
    }
    private static JSONObject restJoint(JSONObject rig) throws Exception {
        JSONArray joints = rig.getJSONArray("joints");
        for (int i = 0; i < joints.length(); i++)
            if ("wrist".equals(joints.getJSONObject(i).getString("id"))) return joints.getJSONObject(i);
        throw invalid("Weapon has no prepared wrist joint");
    }
    private static JSONObject currentJoint(JSONObject snapshot, String rig, String joint) throws Exception {
        JSONArray joints = snapshot.getJSONArray("joints");
        for (int i = 0; i < joints.length(); i++) {
            JSONObject at = joints.getJSONObject(i);
            if (rig.equals(at.getString("rigId")) && joint.equals(at.getString("jointId"))) return at;
        }
        throw invalid("Native joint not found: " + rig + "/" + joint);
    }

    private static Pair pair(JSONObject source, JSONObject program, JSONObject sampled, int frame) throws Exception {
        Weapon a = weapon(source, program, sampled, "wide_rig", "wide_baton_core", frame);
        Weapon b = weapon(source, program, sampled, "rook_wide_rig", "rook_wide_baton_core", frame);
        return new Pair(a, b);
    }

    private static Weapon weapon(JSONObject source, JSONObject program, JSONObject sampled,
                                 String rigName, String coreId, int frame) throws Exception {
        int w = source.getInt("width"), h = source.getInt("height");
        JSONObject original = SketchDocument.object(source, coreId);
        JSONObject rest = restJoint(rig(program, rigName));
        JSONObject curr = currentJoint(sampled, rigName, "wrist");
        Point now = new Point(curr.getDouble("x") * w, curr.getDouble("y") * h);
        Point old = new Point(rest.getDouble("x") * w, rest.getDouble("y") * h);
        JSONArray commands = original.getJSONArray("commands");
        JSONObject offset = SketchDocument.transformAt(original, frame);
        double theta = Math.toRadians(curr.getDouble("rotation") + offset.getDouble("rotation"));
        double cos = Math.cos(theta), sin = Math.sin(theta);
        double x = offset.getDouble("x") * w, y = offset.getDouble("y") * h;
        Point first = point(commands.getJSONArray(0), w, h), last = point(commands.getJSONArray(1), w, h);
        Point a = new Point(now.x + cos * (first.x - old.x) - sin * (first.y - old.y) + x,
                now.y + sin * (first.x - old.x) + cos * (first.y - old.y) + y);
        Point b = new Point(now.x + cos * (last.x - old.x) - sin * (last.y - old.y) + x,
                now.y + sin * (last.x - old.x) + cos * (last.y - old.y) + y);
        return new Weapon(now, a, b, distance(old, last), curr.getDouble("rotation"), old, last);
    }

    private static Point point(JSONArray command, int w, int h) throws Exception {
        return new Point(command.getDouble(1) * w, command.getDouble(2) * h);
    }
    private static double aimRotation(Weapon weapon, Point target) {
        double angle = Math.toDegrees(Math.atan2(target.y - weapon.wrist.y, target.x - weapon.wrist.x));
        double baseline = Math.toDegrees(Math.atan2(weapon.originalTip.y - weapon.rest.y,
                weapon.originalTip.x - weapon.rest.x)) + weapon.nativeRotation;
        return ((angle - baseline + 540) % 360) - 180;
    }
    private static double beforeTipDistance(Pair p) {return distance(p.a.tip, p.b.tip);}
    private static double distance(Point a, Point b) {return Math.hypot(a.x - b.x, a.y - b.y);}
    private static boolean finite(Point p) {return Double.isFinite(p.x) && Double.isFinite(p.y);}
    private static boolean within(Point p, int w, int h) {return p.x >= 0 && p.x <= w && p.y >= 0 && p.y <= h;}
    private static double segmentDistance(Weapon x, Weapon y) {
        Point a = x.grip, b = x.tip, c = y.grip, d = y.tip;
        double o1 = cross(a, b, c), o2 = cross(a, b, d);
        double o3 = cross(c, d, a), o4 = cross(c, d, b);
        if ((o1 * o2 < 0 && o3 * o4 < 0)) return 0;
        return Math.min(Math.min(pointToSegment(a, c, d), pointToSegment(b, c, d)),
                Math.min(pointToSegment(c, a, b), pointToSegment(d, a, b)));
    }
    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }
    private static double pointToSegment(Point p, Point a, Point b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double square = dx * dx + dy * dy;
        if (square <= 1e-9) return distance(p, a);
        double t = Math.max(0, Math.min(1, ((p.x - a.x)*dx + (p.y - a.y)*dy) / square));
        return Math.hypot(p.x - (a.x + t*dx), p.y - (a.y + t*dy));
    }
    private static IllegalArgumentException invalid(String why) {
        return new IllegalArgumentException("OMEGA_WEAPON_CONTACT: " + why);
    }
    private static final class Point {
        final double x, y;
        Point(double x, double y) {this.x = x; this.y = y;}
    }
    private static final class Weapon {
        final Point wrist, grip, tip, rest, originalTip;
        final double length, nativeRotation;
        Weapon(Point wrist, Point grip, Point tip, double length,
               double nativeRotation, Point rest, Point originalTip) {
            this.wrist = wrist;this.grip = grip;this.tip = tip;this.length = length;
            this.nativeRotation = nativeRotation;this.rest = rest;this.originalTip = originalTip;
        }
    }
    private static final class Pair {
        final Weapon a,b;
        Pair(Weapon a, Weapon b) {this.a=a;this.b=b;}
    }
}