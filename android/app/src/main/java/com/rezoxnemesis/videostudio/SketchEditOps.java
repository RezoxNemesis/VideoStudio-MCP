package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** The only document operation facade, used unchanged by touch and MCP. */
public final class SketchEditOps {
    private SketchEditOps() {}

    public static JSONObject execute(Context context, String action, JSONObject args) throws Exception {
        if (args == null || args.toString().getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
            throw SketchDocument.bad("Request exceeds 512 KiB; split the edit into revision-bound batches");
        try (SketchProjectStore store = new SketchProjectStore(context)) {
            if ("sketch_list".equals(action)) { SketchDocument.fields(args, ""); return new JSONObject().put("sketches", store.list()); }
            String id = SketchDocument.id(args, "sketchId");
            if ("sketch_get".equals(action)) {
                SketchDocument.fields(args, "sketchId,expectedRevision,layerId,objectId,pointOffset,pointLimit,objectOffset,objectLimit"); JSONObject doc = store.get(id);
                if (args.has("expectedRevision")) SketchProjectStore.requireRevision(doc, SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 1));
                JSONObject result = new JSONObject().put("sketchId", id).put("revision", doc.getLong("revision"))
                        .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8)));
                if (args.has("objectId")) {
                    JSONObject object = SketchDocument.copy(SketchDocument.object(doc, SketchDocument.id(args, "objectId")));
                    if (args.has("layerId") && !SketchDocument.owner(doc, object.getString("id")).getString("id").equals(SketchDocument.id(args, "layerId")))
                        throw SketchDocument.bad("Object is not owned by the selected layer");
                    result.put("layerId", SketchDocument.owner(doc, object.getString("id")).getString("id"));
                    if (object.has("points")) {
                        JSONArray points = object.getJSONArray("points");
                        int offset = args.has("pointOffset") ? (int) SketchDocument.integer(args, "pointOffset", 0, points.length()) : 0;
                        int limit = args.has("pointLimit") ? (int) SketchDocument.integer(args, "pointLimit", 1, 512) : 512;
                        JSONArray page = new JSONArray(); for (int i = offset; i < Math.min(points.length(), offset + limit); i++) page.put(points.getJSONArray(i));
                        object.put("points", page); result.put("pointCount", points.length()).put("pointOffset", offset).put("morePoints", offset + page.length() < points.length());
                    }
                    result.put("object", object);
                } else if (args.has("layerId")) {
                    JSONObject layer = SketchDocument.copy(SketchDocument.layer(doc, SketchDocument.id(args, "layerId")));
                    JSONArray objects = layer.getJSONArray("objects");
                    int offset = args.has("objectOffset") ? (int) SketchDocument.integer(args, "objectOffset", 0, objects.length()) : 0;
                    int limit = args.has("objectLimit") ? (int) SketchDocument.integer(args, "objectLimit", 1, 128) : 128;
                    JSONArray page = new JSONArray();
                    for (int i = offset; i < Math.min(objects.length(), offset + limit); i++) {
                        JSONObject object = objects.getJSONObject(i); page.put(new JSONObject().put("id", object.getString("id")).put("kind", object.getString("kind"))
                                .put("frame", object.getInt("frame")).put("exposure", object.getInt("exposure"))
                                .put("pointCount", object.has("points") ? object.getJSONArray("points").length() : 0));
                    }
                    layer.put("objects", page); result.put("layer", layer).put("objectCount", objects.length()).put("objectOffset", offset).put("moreObjects", offset + page.length() < objects.length());
                } else {
                    JSONObject header = SketchDocument.copy(doc); JSONArray summaries = new JSONArray(); JSONArray layers = doc.getJSONArray("layers");
                    for (int i = 0; i < layers.length(); i++) {
                        JSONObject layer = SketchDocument.copy(layers.getJSONObject(i)); int count = layer.getJSONArray("objects").length(); layer.remove("objects");
                        JSONArray keyFrames = new JSONArray(); JSONArray keys = layer.getJSONArray("keys");
                        for (int k = 0; k < keys.length(); k++) keyFrames.put(keys.getJSONObject(k).getInt("frame")); layer.remove("keys");
                        summaries.put(layer.put("objectCount", count).put("keyFrames", keyFrames));
                    }
                    header.put("layers", summaries); result.put("document", header).put("geometryPaged", true);
                }
                if (result.toString().getBytes(StandardCharsets.UTF_8).length > 512 * 1024) throw new IllegalStateException("SKETCH_QUERY_BUDGET: request a smaller geometry page");
                return result;
            }
            if ("sketch_preview".equals(action)) {
                SketchDocument.fields(args, "sketchId,expectedRevision,frameIndex,maxDimension");
                JSONObject doc = store.get(id);
                if (args.has("expectedRevision")) SketchProjectStore.requireRevision(doc, SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 1));
                int frame = args.has("frameIndex") ? (int) SketchDocument.integer(args, "frameIndex", 0, doc.getInt("frameCount") - 1) : 0;
                int dimension = args.has("maxDimension") ? (int) SketchDocument.integer(args, "maxDimension", 128, 720) : 720;
                try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, doc)) {
                    android.graphics.Bitmap bitmap = renderer.render(frame);
                    try {
                        byte[] bytes;
                        while (true) {
                            double ratio = Math.min(1, dimension / (double) Math.max(bitmap.getWidth(), bitmap.getHeight()));
                            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap,
                                    Math.max(1, (int) Math.round(bitmap.getWidth() * ratio)), Math.max(1, (int) Math.round(bitmap.getHeight() * ratio)), true);
                            if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Sketch preview encoding failed");
                            bytes = out.toByteArray(); if (bytes.length <= 1024 * 1024) break;
                            dimension = Math.max(128, dimension * 3 / 4);
                            if (Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 128) throw new IllegalStateException("SKETCH_PREVIEW_BUDGET: PNG exceeds 1 MiB");
                        }
                        return new JSONObject().put("sketchId", id).put("revision", doc.getLong("revision")).put("frameIndex", frame)
                                .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8)))
                                .put("renderer", "android-bitmap-canvas").put("sketchPreviewImage", new JSONObject().put("mimeType", "image/png")
                                        .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                                        .put("width", bitmap.getWidth()).put("height", bitmap.getHeight()).put("sha256", SketchDocument.sha256(bytes)));
                    } finally { bitmap.recycle(); }
                }
            }
            String op = SketchDocument.id(args, "operationId");
            String fingerprint = SketchDocument.sha256((action + "\n" + SketchDocument.canonical(args)).getBytes(StandardCharsets.UTF_8));
            if ("sketch_create".equals(action)) {
                SketchDocument.fields(args, "sketchId,operationId,name,width,height,fps,frameCount,background"); return store.create(args, fingerprint);
            }
            long revision = SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 2);
            switch (action) {
                case "sketch_edit":
                    SketchDocument.fields(args, "sketchId,operationId,expectedRevision,operations");
                    JSONArray operations = args.getJSONArray("operations");
                    if (operations.length() < 1 || operations.length() > 64) throw SketchDocument.bad("Use 1..64 operations in an atomic batch");
                    return store.mutate(id, op, revision, fingerprint, doc -> {
                        for (int i = 0; i < operations.length(); i++) apply(doc, operations.getJSONObject(i));
                        return doc;
                    });
                case "sketch_undo": case "sketch_redo":
                    SketchDocument.fields(args, "sketchId,operationId,expectedRevision");
                    return store.historyMove(id, op, revision, fingerprint, "sketch_redo".equals(action));
                case "sketch_import_reference":
                    SketchDocument.fields(args, "sketchId,operationId,expectedRevision,projectId,sourceProjectUpdatedAt,assetId,layerId,objectId,frame,exposure,bounds");
                    ProjectStore projects = new ProjectStore(context);
                    return projects.guardedProject(SketchDocument.id(args, "projectId"), SketchDocument.integer(args, "sourceProjectUpdatedAt", 0, Long.MAX_VALUE), project ->
                        store.mutate(id, op, revision, fingerprint, doc -> {
                            JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(args, "layerId")); requireUnlocked(layer);
                            ProjectStore.Asset asset = project.asset(SketchDocument.id(args, "assetId"));
                            // Validate the future image geometry and document before copying any bytes.
                            JSONObject referencePlaceholder = new JSONObject().put("sha256", new String(new char[64]).replace('\0', '0'))
                                    .put("file", new String(new char[64]).replace('\0', '0') + ".png").put("width", 1).put("height", 1)
                                    .put("sourceProjectId", project.id).put("sourceAssetId", SketchDocument.id(args, "assetId"));
                            JSONObject object = completeObject(new JSONObject().put("id", SketchDocument.id(args, "objectId")).put("kind", "image")
                                    .put("frame", args.has("frame") ? args.get("frame") : 0)
                                    .put("exposure", args.has("exposure") ? args.get("exposure") : doc.getInt("frameCount"))
                                    .put("bounds", args.has("bounds") ? args.getJSONObject("bounds") : new JSONObject().put("x", 0).put("y", 0).put("width", 1).put("height", 1))
                                    .put("reference", referencePlaceholder));
                            layer.getJSONArray("objects").put(object); SketchDocument.validate(doc);
                            object.put("reference", store.importReference(id, project, asset)); return doc;
                        }));
                default: throw SketchDocument.bad("Unsupported sketch action: " + action);
            }
        }
    }

    static void apply(JSONObject doc, JSONObject op) throws Exception {
        String type = op.getString("type");
        switch (type) {
            case "add_layer": {
                SketchDocument.fields(op, "type,layer"); JSONObject layer = SketchDocument.copy(op.getJSONObject("layer"));
                SketchDocument.fields(layer, "id,name,visible,locked,opacity,transform,keys,objects");
                if (!layer.has("name")) layer.put("name", "Layer"); if (!layer.has("visible")) layer.put("visible", true);
                if (!layer.has("locked")) layer.put("locked", false); if (!layer.has("opacity")) layer.put("opacity", 1);
                if (!layer.has("transform")) layer.put("transform", SketchDocument.transform()); if (!layer.has("keys")) layer.put("keys", new JSONArray());
                if (!layer.has("objects")) layer.put("objects", new JSONArray());
                doc.getJSONArray("layers").put(layer); break;
            }
            case "update_layer": {
                SketchDocument.fields(op, "type,layerId,patch"); JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(op, "layerId"));
                JSONObject patch = op.getJSONObject("patch"); SketchDocument.fields(patch, "name,visible,locked,opacity"); merge(layer, patch); break;
            }
            case "delete_layer": {
                SketchDocument.fields(op, "type,layerId"); String id = SketchDocument.id(op, "layerId"); requireUnlocked(SketchDocument.layer(doc, id));
                remove(doc.getJSONArray("layers"), id); break;
            }
            case "reorder_layer": {
                SketchDocument.fields(op, "type,layerId,index"); JSONArray layers = doc.getJSONArray("layers");
                int index = (int) SketchDocument.integer(op, "index", 0, layers.length() - 1);
                JSONObject selected = SketchDocument.layer(doc, SketchDocument.id(op, "layerId")); requireUnlocked(selected);
                remove(layers, selected.getString("id")); JSONArray reordered = new JSONArray();
                for (int i = 0, j = 0; i <= layers.length(); i++) reordered.put(i == index ? selected : layers.getJSONObject(j++));
                doc.put("layers", reordered); break;
            }
            case "add_object": {
                SketchDocument.fields(op, "type,layerId,object"); JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(op, "layerId")); requireUnlocked(layer);
                layer.getJSONArray("objects").put(completeObject(op.getJSONObject("object"))); break;
            }
            case "update_object": {
                SketchDocument.fields(op, "type,objectId,patch"); String id = SketchDocument.id(op, "objectId"); requireUnlocked(SketchDocument.owner(doc, id));
                JSONObject patch = op.getJSONObject("patch"); SketchDocument.fields(patch, "color,width,fill,points,bounds,frame,exposure");
                merge(SketchDocument.object(doc, id), patch); break;
            }
            case "delete_object": {
                SketchDocument.fields(op, "type,objectId"); String id = SketchDocument.id(op, "objectId"); JSONObject layer = SketchDocument.owner(doc, id);
                requireUnlocked(layer); remove(layer.getJSONArray("objects"), id); break;
            }
            case "set_canvas": {
                SketchDocument.fields(op, "type,width,height,background,name");
                for (String key : new String[]{"width","height","background","name"}) if (op.has(key)) doc.put(key, op.get(key)); break;
            }
            case "set_timing": {
                SketchDocument.fields(op, "type,fps,frameCount");
                if (op.has("fps")) doc.put("fps", op.get("fps")); if (op.has("frameCount")) doc.put("frameCount", op.get("frameCount")); break;
            }
            case "set_exposure": {
                SketchDocument.fields(op, "type,objectId,frame,exposure"); String id = SketchDocument.id(op, "objectId"); requireUnlocked(SketchDocument.owner(doc, id));
                SketchDocument.object(doc, id).put("frame", op.get("frame")).put("exposure", op.get("exposure")); break;
            }
            case "set_transform": {
                SketchDocument.fields(op, "type,layerId,objectId,transform"); JSONObject item = target(doc, op);
                JSONObject transform = SketchDocument.copy(item.getJSONObject("transform"));
                SketchDocument.fields(op.getJSONObject("transform"), "x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity");
                merge(transform, op.getJSONObject("transform")); item.put("transform", transform); break;
            }
            case "set_keyframe": {
                SketchDocument.fields(op, "type,layerId,objectId,frame,transform,interpolation,remove"); JSONObject item = target(doc, op);
                int frame = (int) SketchDocument.integer(op, "frame", 0, doc.getInt("frameCount") - 1);
                boolean delete = op.has("remove") && op.getBoolean("remove"); JSONArray existing = item.getJSONArray("keys"), out = new JSONArray();
                JSONObject key = null;
                if (!delete) {
                    JSONObject transform = SketchDocument.transformAt(item, frame);
                    SketchDocument.fields(op.getJSONObject("transform"), "x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity");
                    merge(transform, op.getJSONObject("transform"));
                    key = new JSONObject().put("frame", frame).put("transform", transform).put("interpolation", op.optString("interpolation", "linear"));
                }
                boolean inserted = false;
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject previous = existing.getJSONObject(i); int priorFrame = previous.getInt("frame");
                    if (!inserted && priorFrame >= frame) { if (key != null) out.put(key); inserted = true; }
                    if (priorFrame != frame) out.put(previous);
                }
                if (!inserted && key != null) out.put(key); item.put("keys", out); break;
            }
            default: throw SketchDocument.bad("Unsupported sketch edit operation: " + type);
        }
        // Bound every intermediate state as well as the final batch, before persistence.
        SketchDocument.validate(doc);
    }

    public static JSONObject completeObject(JSONObject source) throws Exception {
        JSONObject object = SketchDocument.copy(source);
        if (!object.has("frame")) object.put("frame", 0); if (!object.has("exposure")) object.put("exposure", 1);
        if (!object.has("color")) object.put("color", "#FF161C2B"); if (!object.has("width")) object.put("width", .008);
        if (!object.has("fill")) object.put("fill", false); if (!object.has("transform")) object.put("transform", SketchDocument.transform());
        if (!object.has("keys")) object.put("keys", new JSONArray()); return object;
    }
    private static JSONObject target(JSONObject doc, JSONObject op) throws Exception {
        if (op.has("objectId") == op.has("layerId")) throw SketchDocument.bad("Choose exactly one objectId or layerId");
        if (op.has("objectId")) { String id = SketchDocument.id(op, "objectId"); requireUnlocked(SketchDocument.owner(doc, id)); return SketchDocument.object(doc, id); }
        JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(op, "layerId")); requireUnlocked(layer); return layer;
    }
    private static void merge(JSONObject target, JSONObject source) throws Exception { Iterator<String> keys = source.keys(); while (keys.hasNext()) { String key = keys.next(); target.put(key, source.get(key)); } }
    private static void remove(JSONArray array, String id) throws Exception {
        for (int i = 0; i < array.length(); i++) if (id.equals(array.getJSONObject(i).getString("id"))) { array.remove(i); return; }
        throw SketchDocument.bad("Unknown ID: " + id);
    }
    static void requireUnlocked(JSONObject layer) throws Exception { if (layer.getBoolean("locked")) throw new IllegalStateException("SKETCH_LAYER_LOCKED"); }
}
