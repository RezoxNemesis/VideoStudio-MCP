package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchSymmetryTransactionTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private interface Checked { void run() throws Exception; }

    private void stateRejected(String marker, Checked action) throws Exception {
        try { action.run(); fail("Expected " + marker); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(marker));
        }
    }

    private String create() throws Exception {
        String id = UUID.randomUUID().toString();
        SketchEditOps.execute(context, "sketch_create", new JSONObject().put("sketchId", id)
                .put("operationId", "create").put("width", 512).put("height", 256).put("frameCount", 3));
        return id;
    }

    private JSONObject stroke(String id) throws Exception {
        return new JSONObject().put("id", id).put("kind", "stroke").put("frame", 0).put("exposure", 3)
                .put("color", "#FFCC5511").put("width", .012)
                .put("points", new JSONArray("[[0.2,0.3,0.25],[0.4,0.35,0.9]]"));
    }

    private JSONObject symmetry(String id, String mode) throws Exception {
        JSONObject configuration = new JSONObject().put("mode", mode);
        if ("radial".equals(mode)) configuration.put("segments", 4);
        return new JSONObject().put("type", "symmetry_stroke").put("layerId", "layer_1")
                .put("object", stroke(id)).put("symmetry", configuration);
    }

    private JSONObject request(String id, String operationId, long revision, JSONArray operations) throws Exception {
        return new JSONObject().put("sketchId", id).put("operationId", operationId)
                .put("expectedRevision", revision).put("operations", operations);
    }

    private JSONObject document(String id) throws Exception {
        try (SketchProjectStore store = new SketchProjectStore(context)) { return store.get(id); }
    }

    private JSONArray objects(JSONObject document) throws Exception {
        return SketchDocument.layer(document, "layer_1").getJSONArray("objects");
    }

    private JSONArray events(String id) throws Exception {
        try (SketchProjectStore store = new SketchProjectStore(context)) {
            return store.liveEvents(id, 0, 8).getJSONArray("events");
        }
    }

    private String beginOriginal(String id) throws Exception {
        String session = UUID.randomUUID().toString();
        SketchEditOps.execute(context, "sketch_live_begin", new JSONObject().put("sketchId", id)
                .put("operationId", session).put("expectedRevision", 1)
                .put("drawingMethod", "original_strokes").put("strokeDurationMs", 80));
        return session;
    }

    @Test public void canonicalRetryReturnsOneReceiptAndConflictingReuseCannotDuplicateTheGesture() throws Exception {
        String id = create();
        JSONObject args = request(id, "mirrored", 1, new JSONArray().put(symmetry("mark", "vertical")));
        JSONObject first = SketchEditOps.execute(context, "sketch_edit", args);
        JSONObject reordered = new JSONObject().put("operations", args.getJSONArray("operations"))
                .put("expectedRevision", 1).put("operationId", "mirrored").put("sketchId", id);
        assertEquals(SketchDocument.canonical(first),
                SketchDocument.canonical(SketchEditOps.execute(context, "sketch_edit", reordered)));
        JSONObject saved = document(id);
        assertEquals(2, saved.getLong("revision"));
        assertEquals(2, objects(saved).length());
        assertEquals(.8, SketchDocument.object(saved, "mark_sym1").getJSONArray("points")
                .getJSONArray(0).getDouble(0), 1e-10);
        assertEquals(.25, SketchDocument.object(saved, "mark_sym1").getJSONArray("points")
                .getJSONArray(0).getDouble(2), 0);
        assertEquals(1, events(id).length());

        JSONObject conflicting = symmetry("mark", "vertical");
        conflicting.getJSONObject("symmetry").put("centerX", .45);
        stateRejected("OPERATION_ID_CONFLICT", () -> SketchEditOps.execute(context, "sketch_edit",
                request(id, "mirrored", 1, new JSONArray().put(conflicting))));
        stateRejected("REVISION_CONFLICT", () -> SketchEditOps.execute(context, "sketch_edit",
                request(id, "stale", 1, new JSONArray().put(symmetry("stale", "vertical")))));
        assertEquals(SketchDocument.canonical(saved), SketchDocument.canonical(document(id)));
        assertEquals(1, events(id).length());
    }

    @Test public void bothAxesProduceOneUndoableGestureAndRedoRestoresEveryOrdinaryStroke() throws Exception {
        String id = create();
        SketchEditOps.execute(context, "sketch_edit",
                request(id, "both", 1, new JSONArray().put(symmetry("petal", "both"))));
        JSONObject saved = document(id);
        assertEquals(4, objects(saved).length());
        for (String objectId : Arrays.asList("petal", "petal_sym1", "petal_sym2", "petal_sym3")) {
            JSONObject object = SketchDocument.object(saved, objectId);
            assertEquals("stroke", object.getString("kind"));
            assertEquals(3, object.getInt("exposure"));
            assertFalse(object.has("symmetry"));
        }
        JSONObject undo = new JSONObject().put("sketchId", id).put("operationId", "undo")
                .put("expectedRevision", 2);
        assertEquals(3, SketchEditOps.execute(context, "sketch_undo", undo).getLong("revision"));
        assertEquals(0, objects(document(id)).length());
        assertEquals(3, SketchEditOps.execute(context, "sketch_undo", undo).getLong("revision"));
        SketchEditOps.execute(context, "sketch_redo", new JSONObject().put("sketchId", id)
                .put("operationId", "redo").put("expectedRevision", 3));
        JSONObject restored = document(id);
        assertEquals(4, restored.getLong("revision"));
        saved.put("revision", 4);
        assertEquals(SketchDocument.canonical(saved), SketchDocument.canonical(restored));
    }

    @Test public void outsideCopyRollsBackEarlierExpansionHistoryEventsAndFailedReceipt() throws Exception {
        String id = create();
        JSONObject outside = symmetry("outside", "vertical");
        outside.getJSONObject("symmetry").put("centerX", .1);
        try {
            SketchEditOps.execute(context, "sketch_edit", request(id, "atomic", 1,
                    new JSONArray().put(symmetry("first", "vertical")).put(outside)));
            fail("An off-canvas reflected stroke must reject the complete batch");
        } catch (IllegalArgumentException expected) { }
        assertEquals(1, document(id).getLong("revision"));
        assertEquals(0, objects(document(id)).length());
        assertEquals(0, events(id).length());
        stateRejected("UNDO_EMPTY", () -> SketchEditOps.execute(context, "sketch_undo",
                new JSONObject().put("sketchId", id).put("operationId", "empty_undo")
                        .put("expectedRevision", 1)));

        SketchEditOps.execute(context, "sketch_edit", request(id, "atomic", 1,
                new JSONArray().put(symmetry("first", "vertical")).put(symmetry("outside", "vertical"))));
        assertEquals(2, document(id).getLong("revision"));
        assertEquals(4, objects(document(id)).length());
        assertEquals(1, events(id).length());
    }

    @Test public void lockedLayerRejectsSymmetryWithoutCommittingEarlierOperations() throws Exception {
        String id = create();
        SketchEditOps.execute(context, "sketch_edit", request(id, "lock", 1,
                new JSONArray().put(new JSONObject().put("type", "update_layer").put("layerId", "layer_1")
                        .put("patch", new JSONObject().put("locked", true)))));
        JSONObject locked = document(id);
        stateRejected("LAYER_LOCKED", () -> SketchEditOps.execute(context, "sketch_edit",
                request(id, "blocked", 2, new JSONArray()
                        .put(new JSONObject().put("type", "set_canvas").put("name", "Must roll back"))
                        .put(symmetry("blocked", "both")))));
        assertEquals(SketchDocument.canonical(locked), SketchDocument.canonical(document(id)));
        assertEquals(1, events(id).length());
        SketchEditOps.execute(context, "sketch_undo", new JSONObject().put("sketchId", id)
                .put("operationId", "undo_lock").put("expectedRevision", 2));
        assertFalse(SketchDocument.layer(document(id), "layer_1").getBoolean("locked"));
        assertEquals(0, objects(document(id)).length());
        SketchEditOps.execute(context, "sketch_edit",
                request(id, "blocked", 3, new JSONArray().put(symmetry("blocked", "both"))));
        assertEquals(4, objects(document(id)).length());
    }

    @Test public void derivedIdCollisionCannotLeaveTheSourceOrOtherCopiesBehind() throws Exception {
        String id = create();
        JSONObject existing = new JSONObject().put("type", "add_object").put("layerId", "layer_1")
                .put("object", stroke("collision_sym1"));
        SketchEditOps.execute(context, "sketch_edit",
                request(id, "existing", 1, new JSONArray().put(existing)));
        JSONObject before = document(id);
        try {
            SketchEditOps.execute(context, "sketch_edit", request(id, "collision", 2,
                    new JSONArray().put(symmetry("collision", "both"))));
            fail("Generated IDs must not overwrite or duplicate an existing object");
        } catch (IllegalArgumentException expected) { }
        assertEquals(SketchDocument.canonical(before), SketchDocument.canonical(document(id)));
        assertEquals(1, events(id).length());
        SketchEditOps.execute(context, "sketch_edit", request(id, "collision", 2,
                new JSONArray().put(symmetry("fresh", "both"))));
        assertEquals(3, document(id).getLong("revision"));
        assertEquals(5, objects(document(id)).length());
    }

    @Test public void mixedPaintCelAndSymmetryPersistAsStandardAdditionsAndUndoTogether() throws Exception {
        String id = create();
        JSONObject cel = new JSONObject().put("type", "add_paint_cel").put("layerId", "layer_1")
                .put("objectId", "mixed_cel").put("frame", 0).put("exposure", 3);
        SketchEditOps.execute(context, "sketch_edit", request(id, "mixed", 1,
                new JSONArray().put(cel).put(symmetry("mixed_ink", "vertical"))));
        JSONObject saved = document(id);
        assertEquals(2, saved.getLong("revision"));
        assertEquals(3, objects(saved).length());
        JSONObject paint = SketchDocument.object(saved, "mixed_cel");
        assertEquals("paint", paint.getString("kind"));
        assertTrue(paint.has("paint"));
        SketchPaintStore.validateDescriptor(paint.getJSONObject("paint"));
        JSONArray recorded = events(id);
        assertEquals(1, recorded.length());
        JSONArray additions = recorded.getJSONObject(0).getJSONArray("operations");
        assertEquals(3, additions.length());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < additions.length(); i++) {
            JSONObject addition = additions.getJSONObject(i), object = addition.getJSONObject("object");
            assertEquals("add_object", addition.getString("type"));
            assertEquals("layer_1", addition.getString("layerId"));
            ids.add(object.getString("id"));
            assertEquals(SketchDocument.canonical(SketchDocument.object(saved, object.getString("id"))),
                    SketchDocument.canonical(SketchEditOps.completeObject(object)));
        }
        assertEquals(new HashSet<>(Arrays.asList("mixed_cel", "mixed_ink", "mixed_ink_sym1")), ids);
        SketchEditOps.execute(context, "sketch_undo", new JSONObject().put("sketchId", id)
                .put("operationId", "undo_mixed").put("expectedRevision", 2));
        assertEquals(3, document(id).getLong("revision"));
        assertEquals(0, objects(document(id)).length());
    }

    @Test public void outsideSymmetryInMixedPaintBatchLeavesNoObjectsHistoryEventsOrReceipt() throws Exception {
        String id = create();
        JSONObject before = document(id);
        JSONObject cel = new JSONObject().put("type", "add_paint_cel").put("layerId", "layer_1")
                .put("objectId", "rollback_cel");
        JSONObject outside = symmetry("rollback_ink", "vertical");
        outside.getJSONObject("symmetry").put("centerX", .1);
        try {
            SketchEditOps.execute(context, "sketch_edit", request(id, "mixed_rollback", 1,
                    new JSONArray().put(cel).put(outside)));
            fail("Paint preparation must not commit before symmetry validation");
        } catch (IllegalArgumentException expected) { }
        assertEquals(SketchDocument.canonical(before), SketchDocument.canonical(document(id)));
        assertEquals(0, events(id).length());
        stateRejected("UNDO_EMPTY", () -> SketchEditOps.execute(context, "sketch_undo",
                new JSONObject().put("sketchId", id).put("operationId", "undo_failed_mixed")
                        .put("expectedRevision", 1)));
        SketchEditOps.execute(context, "sketch_edit", request(id, "mixed_rollback", 1,
                new JSONArray().put(cel).put(symmetry("rollback_ink", "vertical"))));
        assertEquals(2, document(id).getLong("revision"));
        assertEquals(3, objects(document(id)).length());
        assertEquals(1, events(id).length());
    }

    @Test public void laterAppendDoesNotRewriteTheEarlierSymmetryAdditionInTheSameLiveEvent() throws Exception {
        String id = create();
        JSONObject replay = document(id);
        JSONObject append = new JSONObject().put("type", "append_points").put("objectId", "growing")
                .put("points", new JSONArray("[[0.45,0.4,0.7]]"));
        SketchEditOps.execute(context, "sketch_edit", request(id, "symmetry_then_append", 1,
                new JSONArray().put(symmetry("growing", "vertical")).put(append)));
        JSONObject saved = document(id);
        assertEquals(3, SketchDocument.object(saved, "growing").getJSONArray("points").length());
        assertEquals(2, SketchDocument.object(saved, "growing_sym1").getJSONArray("points").length());
        JSONArray recorded = events(id);
        assertEquals(1, recorded.length());
        JSONArray operations = recorded.getJSONObject(0).getJSONArray("operations");
        assertEquals(3, operations.length());
        assertEquals("add_object", operations.getJSONObject(0).getString("type"));
        assertEquals("growing", operations.getJSONObject(0).getJSONObject("object").getString("id"));
        assertEquals("The initial live addition must retain its authored two-point snapshot", 2,
                operations.getJSONObject(0).getJSONObject("object").getJSONArray("points").length());
        assertEquals("add_object", operations.getJSONObject(1).getString("type"));
        assertEquals("growing_sym1", operations.getJSONObject(1).getJSONObject("object").getString("id"));
        assertEquals(2, operations.getJSONObject(1).getJSONObject("object").getJSONArray("points").length());
        assertEquals("append_points", operations.getJSONObject(2).getString("type"));
        assertEquals(1, operations.getJSONObject(2).getJSONArray("points").length());
        for (int i = 0; i < operations.length(); i++) SketchEditOps.apply(replay, operations.getJSONObject(i));
        replay.put("revision", saved.getLong("revision"));
        assertEquals(SketchDocument.canonical(saved), SketchDocument.canonical(replay));
    }

    @Test public void originalDrawingKeepsAuthoredStrokeAndEraserCopiesAsSourceFreeAdditions() throws Exception {
        String id = create(), session = beginOriginal(id);
        JSONObject eraser = symmetry("eraser", "horizontal");
        eraser.getJSONObject("object").put("kind", "eraser");
        SketchEditOps.execute(context, "sketch_edit", request(id, "authored", 1,
                new JSONArray().put(symmetry("ink", "vertical")).put(eraser)));
        JSONObject saved = document(id);
        assertEquals(2, saved.getLong("revision"));
        assertEquals(4, objects(saved).length());
        assertEquals("stroke", SketchDocument.object(saved, "ink_sym1").getString("kind"));
        assertEquals("eraser", SketchDocument.object(saved, "eraser_sym1").getString("kind"));
        assertEquals("original_strokes", SketchLiveDrawing.read(context, id, session).getString("drawingMethod"));
        JSONArray operations = events(id).getJSONObject(0).getJSONArray("operations");
        assertEquals(4, operations.length());
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i), object = operation.getJSONObject("object");
            assertEquals("add_object", operation.getString("type"));
            assertFalse(object.has("reference"));
            assertFalse(object.has("paint"));
            assertFalse(object.has("cloneSource"));
            assertEquals(SketchDocument.canonical(SketchDocument.object(saved, object.getString("id"))),
                    SketchDocument.canonical(SketchEditOps.completeObject(object)));
        }
    }

    @Test public void originalDrawingChecksNestedImageReferencePaintAndCloneBeforeAnyMutation() throws Exception {
        String id = create();
        beginOriginal(id);
        JSONObject original = new JSONObject().put("drawingMethod", "original_strokes").put("ended", false);
        List<JSONObject> untrusted = Arrays.asList(
                stroke("image").put("kind", "image"),
                stroke("reference").put("reference", new JSONObject()),
                stroke("paint_kind").put("kind", "paint"),
                stroke("paint_pointer").put("paint", new JSONObject()),
                stroke("clone_source").put("cloneSource", new JSONObject()),
                stroke("clone_brush").put("brush", new JSONObject().put("mode", "clone")));
        for (JSONObject object : untrusted) {
            JSONObject operation = symmetry("unused", "vertical").put("object", object);
            JSONArray operations = new JSONArray().put(operation);
            stateRejected("SKETCH_ORIGINAL_DRAWING_REQUIRED:",
                    () -> SketchDrawingPolicy.requireOperations(original, operations));
            stateRejected("SKETCH_ORIGINAL_DRAWING_REQUIRED:", () -> SketchEditOps.execute(context, "sketch_edit",
                    request(id, "reject_" + object.getString("id"), 1,
                            new JSONArray().put(symmetry("earlier", "vertical")).put(operation))));
            assertEquals(1, document(id).getLong("revision"));
            assertEquals(0, objects(document(id)).length());
            assertEquals(0, events(id).length());
        }
    }

    @Test public void liveExpansionMatchesOptimisticAndSavedGeometryAndUsesProgressiveNativeReplay() throws Exception {
        String id = create(), session = beginOriginal(id);
        JSONObject baseline = document(id), optimistic = SketchDocument.copy(baseline);
        JSONObject operation = symmetry("radial", "radial");
        operation.getJSONObject("object").put("points", new JSONArray("[[0.55,0.5,0.25],[0.6,0.55,0.9]]"));
        SketchEditOps.apply(optimistic, operation);
        SketchEditOps.execute(context, "sketch_edit", request(id, "radial", 1, new JSONArray().put(operation)));
        JSONObject saved = document(id);
        optimistic.put("revision", saved.getLong("revision"));
        assertEquals(SketchDocument.canonical(saved), SketchDocument.canonical(optimistic));
        JSONArray recorded = events(id);
        assertEquals(1, recorded.length());
        JSONObject event = recorded.getJSONObject(0);
        assertEquals(1, event.getLong("baseRevision"));
        assertEquals(2, event.getLong("revision"));
        JSONArray additions = event.getJSONArray("operations");
        assertEquals(4, additions.length());
        List<String> expected = new ArrayList<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < additions.length(); i++) {
                JSONObject addition = additions.getJSONObject(i);
                assertEquals("add_object", addition.getString("type"));
                assertEquals("layer_1", addition.getString("layerId"));
                String objectId = addition.getJSONObject("object").getString("id");
                expected.add(objectId);
                SketchEditOps.apply(baseline, addition);
                SketchAgentHand.Track hand = worker.submit(() -> SketchAgentHand.prepare(saved, addition, 0))
                        .get(5, TimeUnit.SECONDS);
                SketchAgentHand.State halfway = hand.at(.5);
                assertTrue("Every copied stroke must prepare an actual drawing hand", halfway.visible && halfway.drawing);
                assertEquals(objectId, halfway.objectId);
                assertEquals("add_object", halfway.operationType);
                JSONArray points = SketchDocument.object(saved, objectId).getJSONArray("points");
                assertEquals(points.getJSONArray(0).getDouble(0), hand.at(0).x, 1e-6);
                assertEquals(points.getJSONArray(0).getDouble(1), hand.at(0).y, 1e-6);
                assertEquals(points.getJSONArray(1).getDouble(0), hand.at(1).x, 1e-6);
                assertEquals(points.getJSONArray(1).getDouble(1), hand.at(1).y, 1e-6);
                assertEquals(.9, hand.at(1).pressure, 1e-10);
            }
        } finally { worker.shutdownNow(); }
        assertEquals(new HashSet<>(Arrays.asList("radial", "radial_sym1", "radial_sym2", "radial_sym3")),
                new HashSet<>(expected));
        baseline.put("revision", saved.getLong("revision"));
        assertEquals(SketchDocument.canonical(saved), SketchDocument.canonical(baseline));
        SketchEditOps.execute(context, "sketch_live_end", new JSONObject().put("sketchId", id)
                .put("sessionId", session).put("expectedRevision", 2));

        List<String> presented = new ArrayList<>();
        Set<String> progressive = new HashSet<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        SketchLivePlayer player = new SketchLivePlayer(context, SketchLiveDrawing.read(context, id, session),
                new SketchLivePlayer.Listener() {
                    public void drawing(JSONObject doc, String objectId, float fraction, String caption) {
                        error.set("Unprepared geometry reached the drawing callback");
                    }
                    public void preparedDrawing(SketchLivePreparation.Prepared prepared, String objectId,
                                                float fraction, String caption) {
                        if (objectId == null) return;
                        if (fraction > 0 && fraction < 1) progressive.add(objectId);
                        if (fraction == 1) presented.add(objectId);
                    }
                    public void committed(JSONObject doc) { }
                    public void idle() { }
                    public void finished(long sequence) { finished.set(true); }
                    public void failed(String message) { error.set(message); }
                });
        try {
            player.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!finished.get() && error.get() == null && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
                Thread.sleep(2);
            }
            assertNull(error.get());
            assertTrue("Saved symmetry session must reach its recorded end", finished.get());
            assertEquals(expected, presented);
            assertEquals("Each persisted copy must appear through a real partial stroke",
                    new HashSet<>(expected), progressive);
        } finally { player.close(); }
    }
}
