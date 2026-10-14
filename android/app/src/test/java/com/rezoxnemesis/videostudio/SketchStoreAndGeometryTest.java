package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchStoreAndGeometryTest {
    final Context context = RuntimeEnvironment.getApplication();
    static JSONObject object(String id) throws Exception {
        return new JSONObject().put("id", id).put("kind", "stroke").put("frame", 0).put("exposure", 3)
                .put("points", new JSONArray().put(new JSONArray().put(.1).put(.2).put(.25)).put(new JSONArray().put(.7).put(.8).put(.9)));
    }
    String create() throws Exception {
        String id = UUID.randomUUID().toString(); SketchEditOps.execute(context, "sketch_create", new JSONObject().put("sketchId", id).put("operationId", "create").put("width", 256).put("height", 256).put("frameCount", 3)); return id;
    }
    JSONObject request(String id, String op, long revision, JSONArray operations) throws Exception {
        return new JSONObject().put("sketchId", id).put("operationId", op).put("expectedRevision", revision).put("operations", operations);
    }
    JSONObject add(String objectId) throws Exception { return new JSONObject().put("type", "add_object").put("layerId", "layer_1").put("object", object(objectId)); }

    @Test public void persistedCASCanonicalRetryAndConflictingIdUseDoNotDuplicateEdits() throws Exception {
        String id = create(); JSONObject args = request(id, "stroke1", 1, new JSONArray().put(add("stroke1")));
        JSONObject first = SketchEditOps.execute(context, "sketch_edit", args);
        JSONObject reordered = new JSONObject().put("operations", args.get("operations")).put("expectedRevision", 1).put("operationId", "stroke1").put("sketchId", id);
        assertEquals(first.toString(), SketchEditOps.execute(context, "sketch_edit", reordered).toString());
        try (SketchProjectStore reopened = new SketchProjectStore(context)) {
            JSONObject doc = reopened.get(id); assertEquals(2, doc.getLong("revision")); assertEquals(1, SketchDocument.layer(doc, "layer_1").getJSONArray("objects").length());
        }
        try { SketchEditOps.execute(context, "sketch_edit", request(id, "stroke1", 1, new JSONArray().put(add("different")))); fail("Conflicting reuse admitted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("OPERATION_ID_CONFLICT")); }
        try { SketchEditOps.execute(context, "sketch_edit", request(id, "stale", 1, new JSONArray().put(add("stale")))); fail("Stale author admitted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("REVISION_CONFLICT")); }
    }
    @Test public void batchFailureRollsBackSnapshotHistoryAndReceiptAndUndoRedoSurviveReopen() throws Exception {
        String id = create();
        JSONObject invalid = object("bad"); invalid.getJSONArray("points").getJSONArray(0).put(0, 2);
        JSONObject args = request(id, "atomic", 1, new JSONArray().put(add("good")).put(new JSONObject().put("type", "add_object").put("layerId", "layer_1").put("object", invalid)));
        try { SketchEditOps.execute(context, "sketch_edit", args); fail("Out-of-range geometry admitted"); } catch (IllegalArgumentException expected) {}
        try (SketchProjectStore store = new SketchProjectStore(context)) { assertEquals(1, store.get(id).getLong("revision")); assertEquals(0, SketchDocument.layer(store.get(id), "layer_1").getJSONArray("objects").length()); }
        SketchEditOps.execute(context, "sketch_edit", request(id, "atomic", 1, new JSONArray().put(add("good"))));
        JSONObject undo = new JSONObject().put("sketchId", id).put("operationId", "undo").put("expectedRevision", 2);
        assertEquals(3, SketchEditOps.execute(context, "sketch_undo", undo).getLong("revision"));
        try (SketchProjectStore store = new SketchProjectStore(context)) { assertEquals(0, SketchDocument.layer(store.get(id), "layer_1").getJSONArray("objects").length()); }
        assertEquals(3, SketchEditOps.execute(context, "sketch_undo", undo).getLong("revision"));
        SketchEditOps.execute(context, "sketch_redo", new JSONObject().put("sketchId", id).put("operationId", "redo").put("expectedRevision", 3));
        try (SketchProjectStore store = new SketchProjectStore(context)) { assertEquals(4, store.get(id).getLong("revision")); assertEquals(.25, SketchDocument.object(store.get(id), "good").getJSONArray("points").getJSONArray(0).getDouble(2), 0); }
    }
    @Test public void editingAfterUndoDiscardsRedoAndHistoryIsBounded() throws Exception {
        String id = create(); long revision = 1;
        for (int i = 0; i < 40; i++) revision = SketchEditOps.execute(context, "sketch_edit", request(id, "rename" + i, revision,
                new JSONArray().put(new JSONObject().put("type", "set_canvas").put("name", "Name " + i)))).getLong("revision");
        int undos = 0;
        while (true) {
            try { revision = SketchEditOps.execute(context, "sketch_undo", new JSONObject().put("sketchId", id).put("operationId", "undo_" + undos).put("expectedRevision", revision)).getLong("revision"); undos++; }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("UNDO_EMPTY")); break; }
        }
        assertEquals(31, undos);
        revision = SketchEditOps.execute(context, "sketch_edit", request(id, "branch", revision, new JSONArray().put(add("branch")))).getLong("revision");
        try { SketchEditOps.execute(context, "sketch_redo", new JSONObject().put("sketchId", id).put("operationId", "old_redo").put("expectedRevision", revision)); fail("Discarded history replayed"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("REDO_EMPTY")); }
    }
    @Test public void transformInterpolationExposureAndLockedLayersUseExactSharedGeometry() throws Exception {
        String id = create();
        JSONArray ops = new JSONArray().put(add("keyed"))
                .put(new JSONObject().put("type", "set_keyframe").put("objectId", "keyed").put("frame", 0).put("transform", new JSONObject().put("x", 0)).put("interpolation", "linear"))
                .put(new JSONObject().put("type", "set_keyframe").put("objectId", "keyed").put("frame", 2).put("transform", new JSONObject().put("x", .4)).put("interpolation", "hold"));
        SketchEditOps.execute(context, "sketch_edit", request(id, "keys", 1, ops));
        try (SketchProjectStore store = new SketchProjectStore(context)) {
            JSONObject object = SketchDocument.object(store.get(id), "keyed"); assertEquals(.2, SketchDocument.transformAt(object, 1).getDouble("x"), .00001);
            assertTrue(SketchFrameRenderer.exposed(object, 2)); assertFalse(SketchFrameRenderer.exposed(object, 3));
        }
        SketchEditOps.execute(context, "sketch_edit", request(id, "lock", 2, new JSONArray().put(new JSONObject().put("type", "update_layer").put("layerId", "layer_1").put("patch", new JSONObject().put("locked", true)))));
        try { SketchEditOps.execute(context, "sketch_edit", request(id, "blocked", 3, new JSONArray().put(add("blocked")))); fail("Locked layer edited"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("LAYER_LOCKED")); }
    }
    @Test public void pagedPointQueriesNeverReturnTheWholeDocumentOrChangeTheRevision() throws Exception {
        String id = create(); JSONObject stroke = object("long"); JSONArray points = new JSONArray(); for (int i = 0; i < 1400; i++) points.put(new JSONArray().put(i / 1400d).put(.5).put(.7)); stroke.put("points", points);
        SketchEditOps.execute(context, "sketch_edit", request(id, "long", 1, new JSONArray().put(new JSONObject().put("type", "add_object").put("layerId", "layer_1").put("object", stroke))));
        JSONObject header = SketchEditOps.execute(context, "sketch_get", new JSONObject().put("sketchId", id)); assertTrue(header.getBoolean("geometryPaged")); assertFalse(header.getJSONObject("document").getJSONArray("layers").getJSONObject(0).has("objects"));
        JSONObject page = SketchEditOps.execute(context, "sketch_get", new JSONObject().put("sketchId", id).put("expectedRevision", 2).put("objectId", "long").put("pointOffset", 512).put("pointLimit", 512));
        assertEquals(1400, page.getInt("pointCount")); assertEquals(512, page.getJSONObject("object").getJSONArray("points").length()); assertTrue(page.getBoolean("morePoints")); assertEquals(2, page.getLong("revision"));
    }
    @Test public void geometryAndCanvasBudgetsRejectInvalidKindsNonIntegralFpsAndUnsafeIds() throws Exception {
        for (JSONObject args : new JSONObject[]{new JSONObject().put("sketchId", "../escape"),new JSONObject().put("sketchId", "odd").put("width", 257),new JSONObject().put("sketchId", "fps").put("fps", 24.5),new JSONObject().put("sketchId", "size").put("width", 2050).put("height", 2048)}) {
            try { SketchDocument.create(args); fail("Invalid document admitted"); } catch (IllegalArgumentException expected) {}
        }
        JSONObject doc = SketchDocument.create(new JSONObject().put("sketchId", "valid").put("frameCount", 3));
        JSONObject wrong = SketchEditOps.completeObject(object("invalid").put("kind", "stro")); SketchDocument.layer(doc, "layer_1").getJSONArray("objects").put(wrong);
        try { SketchDocument.validate(doc); fail("Substring kind admitted"); } catch (IllegalArgumentException expected) {}
    }
}
