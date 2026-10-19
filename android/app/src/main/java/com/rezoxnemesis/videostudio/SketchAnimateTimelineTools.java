package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Adobe Animate-style authoring commands, expressed only as native SketchEditOps.
 *
 * This is original VideoStudio code informed by public Flash/Animate workflows;
 * it contains no Adobe runtime, binaries, artwork, or copied third-party source.
 * Keys remain editable in SketchDocument, and SketchFrameRenderer is the single
 * source of truth for drawing exposure and motion playback.
 */
final class SketchAnimateTimelineTools {
    private static final String TRANSFORM_FIELDS =
            "x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity";

    private SketchAnimateTimelineTools() {}

    static JSONObject insertKey(JSONObject source, String layerId, String objectId,
                                int frame, String easing) throws Exception {
        JSONObject selected = target(source, layerId, objectId, frame);
        JSONObject operation = selector(layerId, objectId).put("type", "set_keyframe")
                .put("frame", frame).put("transform", SketchDocument.transformAt(selected, frame))
                .put("interpolation", easing);
        addCurve(operation, easing);
        return operation;
    }

    static JSONObject removeKey(JSONObject source, String layerId, String objectId,
                                int frame) throws Exception {
        JSONObject selected = target(source, layerId, objectId, frame);
        JSONArray keys = selected.getJSONArray("keys");
        boolean found = false;
        for (int i = 0; i < keys.length(); i++) {
            if (keys.getJSONObject(i).getInt("frame") == frame) { found = true; break; }
        }
        if (!found) throw SketchDocument.bad("No motion key exists on this frame");
        return selector(layerId, objectId).put("type", "set_keyframe")
                .put("frame", frame).put("remove", true);
    }

    /**
     * Build a bounded, non-destructive two-key transform tween.
     *
     * The first key freezes the actual evaluated transform at the first frame.
     * The second key holds the supplied absolute destination fields. Existing
     * middle keys are refused, rather than silently overwriting an artist's work.
     * If requested, a selected object's held drawing can be extended explicitly.
     */
    static JSONArray motionTween(JSONObject source, String layerId, String objectId,
                                 int first, int last, JSONObject destination,
                                 String easing, boolean extendExposure) throws Exception {
        if (source == null || destination == null) throw SketchDocument.bad("A drawing and tween destination are required");
        checkFrame(source, first);
        checkFrame(source, last);
        if (last <= first) throw SketchDocument.bad("Tween destination must follow the start frame");
        if (last - first > SketchDocument.MAX_FRAMES - 1)
            throw SketchDocument.bad("Tween exceeds the drawing frame budget");
        SketchDocument.fields(destination, TRANSFORM_FIELDS);
        if (destination.length() == 0) throw SketchDocument.bad("Choose at least one destination transform field");
        addCurve(new JSONObject(), easing); // Validate timing before preparing mutations.
        JSONObject selected = target(source, layerId, objectId, first);
        JSONArray existing = selected.getJSONArray("keys");
        for (int i = 0; i < existing.length(); i++) {
            int time = existing.getJSONObject(i).getInt("frame");
            if (first < time && time < last)
                throw SketchDocument.bad("Tween crosses an existing key at frame " + (time + 1)
                        + "; split the tween or edit that key first");
        }

        JSONArray operations = new JSONArray();
        if (objectId != null && !SketchFrameRenderer.exposed(selected, last)) {
            if (!extendExposure)
                throw SketchDocument.bad("The selected drawing is not exposed at the tween destination; extend its hold first");
            operations.put(extendHold(source, objectId, last));
        }

        JSONObject current = SketchDocument.transformAt(selected, first);
        JSONObject destinationTransform = SketchDocument.copy(current);
        java.util.Iterator<String> fields = destination.keys();
        while (fields.hasNext()) {
            String name = fields.next();
            destinationTransform.put(name, destination.get(name));
        }
        SketchDocument.validateTransform(destinationTransform);
        JSONObject firstKey = selector(layerId, objectId).put("type", "set_keyframe")
                .put("frame", first).put("transform", current)
                .put("interpolation", easing);
        addCurve(firstKey, easing);
        JSONObject lastKey = selector(layerId, objectId).put("type", "set_keyframe")
                .put("frame", last).put("transform", destinationTransform)
                .put("interpolation", "hold");
        operations.put(firstKey).put(lastKey);
        return operations;
    }

    static JSONObject extendHold(JSONObject source, String objectId, int last) throws Exception {
        if (objectId == null) throw SketchDocument.bad("Select an object to extend its drawing");
        checkFrame(source, last);
        String safeId = SketchDocument.id(new JSONObject().put("id", objectId), "id");
        JSONObject object = SketchDocument.object(source, safeId);
        SketchEditOps.requireUnlocked(SketchDocument.owner(source, safeId));
        int start = object.getInt("frame"), exposure = object.getInt("exposure");
        if (last < start + exposure - 1)
            throw SketchDocument.bad("The selected drawing already extends beyond this frame");
        if (last < start)
            throw SketchDocument.bad("The hold cannot end before its drawing begins");
        return new JSONObject().put("type", "set_exposure")
                .put("objectId", safeId).put("frame", start)
                .put("exposure", last - start + 1);
    }

    /** Authored frame information for an Android timeline cell, not a new renderer. */
    static JSONObject inspect(JSONObject source, String layerId, String objectId,
                              int frame) throws Exception {
        JSONObject item = target(source, layerId, objectId, frame);
        boolean visible = objectId == null || SketchFrameRenderer.exposed(item, frame);
        JSONArray keys = item.getJSONArray("keys");
        JSONObject result = new JSONObject().put("frame", frame)
                .put("drawingExposed", visible).put("motionKey", false)
                .put("transform", SketchDocument.transformAt(item, frame))
                .put("targetType", objectId == null ? "layer" : "object");
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (key.getInt("frame") == frame) {
                result.put("motionKey", true);
                result.put("interpolation", key.getString("interpolation"));
                break;
            }
        }
        return result;
    }

    private static JSONObject target(JSONObject source, String layerId, String objectId,
                                     int frame) throws Exception {
        if (source == null) throw SketchDocument.bad("Open a sketch first");
        checkFrame(source, frame);
        if ((layerId == null) == (objectId == null))
            throw SketchDocument.bad("Select exactly one layer or object");
        if (objectId != null) {
            String id = SketchDocument.id(new JSONObject().put("id", objectId), "id");
            JSONObject owner = SketchDocument.owner(source, id);
            SketchEditOps.requireUnlocked(owner);
            JSONObject item = SketchDocument.object(source, id);
            if (!SketchFrameRenderer.exposed(item, frame))
                throw SketchDocument.bad("The selected drawing is not exposed on frame " + (frame + 1));
            return item;
        }
        String id = SketchDocument.id(new JSONObject().put("id", layerId), "id");
        JSONObject layer = SketchDocument.layer(source, id);
        SketchEditOps.requireUnlocked(layer);
        return layer;
    }

    private static void checkFrame(JSONObject source, int frame) throws Exception {
        if (frame < 0 || frame >= source.getInt("frameCount"))
            throw SketchDocument.bad("Timeline frame must be inside the existing drawing");
    }

    private static JSONObject selector(String layerId, String objectId) throws Exception {
        if ((layerId == null) == (objectId == null))
            throw SketchDocument.bad("Select one layer or one object");
        String chosen = objectId == null ? layerId : objectId;
        SketchDocument.id(new JSONObject().put("id", chosen), "id");
        return new JSONObject().put(objectId == null ? "layerId" : "objectId", chosen);
    }

    private static void addCurve(JSONObject key, String easing) throws Exception {
        JSONObject valid = new JSONObject().put("interpolation", easing);
        if ("bezier".equals(easing))
            valid.put("curve", new JSONArray().put(.42).put(0).put(.58).put(1));
        SketchEasing.validate(valid);
        if (key.has("type")) {
            key.put("interpolation", easing);
            if (valid.has("curve")) key.put("curve", valid.getJSONArray("curve"));
        }
    }
}
