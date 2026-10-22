package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native admission rules for a live drawing's declared method, not an image-generation claim. */
public final class SketchDrawingPolicy {
    public static final String ORIGINAL = "original_strokes", ASSISTED = "source_assisted";
    private SketchDrawingPolicy() {}

    /** Historical sessions did not declare a method; never upgrade their provenance. */
    public static String method(JSONObject session) {
        return session == null ? ASSISTED : session.optString("drawingMethod", ASSISTED);
    }
    public static boolean requiresOriginal(JSONObject session) {
        return session != null && !session.optBoolean("ended", false) && ORIGINAL.equals(method(session));
    }
    public static void requireMethod(String method) {
        if (!ORIGINAL.equals(method) && !ASSISTED.equals(method))
            throw new IllegalArgumentException("SKETCH_DRAWING_METHOD: choose original_strokes or source_assisted");
    }
    public static void requireBegin(JSONObject document, String method) throws Exception {
        requireMethod(method);
        if (!ORIGINAL.equals(method)) return;
        if(document.has("layeredDrawing")){SketchLayeredDrawing.validate(document);return;}
        JSONArray layers = document.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONArray objects = layers.getJSONObject(i).getJSONArray("objects");
            for (int j = 0; j < objects.length(); j++) {
                JSONObject object = objects.getJSONObject(j);
                if (!"image".equals(object.getString("kind")))
                    throw rejected("Start original drawing on a blank document; existing artwork remains available separately");
                requireHiddenGuide(object);
            }
        }
    }
    public static void requireAction(JSONObject session, String action, JSONObject args) throws Exception {
        if (!requiresOriginal(session)) return;
        if ("sketch_import_reference_layers".equals(action))
            throw rejected("Named cutouts copy source pixels; use source_assisted outside original drawing");
        if ("sketch_undo".equals(action) || "sketch_redo".equals(action))
            throw rejected("End the original drawing session before using history; history replacement is not a recorded drawing step");
        if ("sketch_import_reference".equals(action) && args.optBoolean("guideVisible", true))
            throw rejected("A reference may be imported only as a hidden guide during original drawing");
        if ("sketch_import_frame_manifest".equals(action) || "sketch_import_drawing_manifest".equals(action))
            throw rejected("Bulk imported artwork cannot replace an original drawing session");
        if ("sketch_edit".equals(action)) requireOperations(session, args.getJSONArray("operations"));
    }
    /** Call before expensive paint/trace preparation, and again at transactional admission. */
    public static void requireOperations(JSONObject session,JSONObject document,JSONArray operations)throws Exception {
        if(requiresOriginal(session)&&document!=null&&document.has("layeredDrawing")&&SketchCelOps.singleDuplicate(operations)){
            // The persistent workflow protects the hidden guide and rejects imported
            // artwork. The worker separately verifies all immutable paint ancestors.
            SketchLayeredDrawing.authoredCelSources(document,operations.getJSONObject(0));return;
        }
        requireOperations(session,operations);
    }
    public static void requireOperations(JSONObject session, JSONArray operations) throws Exception {
        if (!requiresOriginal(session) || operations == null) return;
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            String type = operation.optString("type");
            if ("trace_reference".equals(type) || "vectorize_reference".equals(type) || "duplicate_cel".equals(type) || "master_instance".equals(type))
                throw rejected("Use authored strokes and geometry instead of tracing or copying existing cel contents");
            if ("paint_stroke".equals(type)) {
                JSONObject record = operation.optJSONObject("stroke");
                JSONObject request = record == null ? operation : record.optJSONObject("request");
                if (request == null || request.has("cloneSource") || record != null && record.has("source") ||
                        request.optJSONObject("brush") != null && "clone".equals(request.getJSONObject("brush").optString("mode")))
                    throw rejected("The clone brush copies reference pixels; use paint or erase strokes for original drawing");
            }
            if ("add_object".equals(type)) requireAuthoredObject(operation.getJSONObject("object"));
            if ("symmetry_stroke".equals(type)) {
                JSONObject source=operation.getJSONObject("object");requireAuthoredObject(source);
                try { SketchSymmetry.requireSource(source);SketchSymmetry.configuration(operation.getJSONObject("symmetry")); }
                catch (Exception invalid) { throw rejected("Symmetry requires a source-free authored stroke or eraser: "+invalid.getMessage()); }
            }
            if ("add_layer".equals(type)) {
                JSONArray objects = operation.getJSONObject("layer").optJSONArray("objects");
                if (objects != null) for (int j = 0; j < objects.length(); j++) requireAuthoredObject(objects.getJSONObject(j));
            }
            if ("set_reference_visibility".equals(type) && operation.optBoolean("visible", true))
                throw rejected("Keep reference guides hidden in original drawing");
        }
    }
    private static void requireAuthoredObject(JSONObject object) {
        if ("image".equals(object.optString("kind")) || object.has("reference"))
            throw rejected("Import reference images with guideVisible:false; image insertion is not original drawing");
        // Native add_paint_cel creates the empty snapshot; public vector objects may not supply one.
        if ("paint".equals(object.optString("kind")) || object.has("paint"))
            throw rejected("Create an empty paint cel and author its pixels with paint strokes");
    }
    /** Final-state guard catches image revelation through transforms, keys, timing or hidden layers. */
    public static void requireDocument(JSONObject session, JSONObject current, JSONObject next) throws Exception {
        SketchLayeredDrawing.validate(next);
        if (!requiresOriginal(session)) return;
        JSONArray layers = next.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONArray objects = layers.getJSONObject(i).getJSONArray("objects");
            for (int j = 0; j < objects.length(); j++) {
                JSONObject object = objects.getJSONObject(j);
                if ("image".equals(object.getString("kind"))) requireHiddenGuide(object);
            }
        }
    }
    private static void requireHiddenGuide(JSONObject object) throws Exception {
        if (object.getJSONObject("transform").getDouble("opacity") != 0)
            throw rejected("Reference images must remain hidden in every frame of original drawing");
        JSONArray keys = object.getJSONArray("keys");
        for (int i = 0; i < keys.length(); i++)
            if (keys.getJSONObject(i).getJSONObject("transform").getDouble("opacity") != 0)
                throw rejected("Reference images must remain hidden in every keyed frame of original drawing");
    }
    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("SKETCH_ORIGINAL_DRAWING_REQUIRED: " + reason);
    }
}
