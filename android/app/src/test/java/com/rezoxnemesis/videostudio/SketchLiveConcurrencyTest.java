package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Protect the durable baseline and human takeover at the concurrent native admission boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLiveConcurrencyTest {
    @Test public void competingBeginsKeepOneImmutableMethodAndDuration() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String id = create(context);
        JSONObject original = beginArgs(id, "same", SketchDrawingPolicy.ORIGINAL, 100);
        JSONObject assisted = beginArgs(id, "same", SketchDrawingPolicy.ASSISTED, 5000);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> one = workers.submit(() -> beginWhenReady(context, original, ready, start));
            Future<Object> two = workers.submit(() -> beginWhenReady(context, assisted, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Object a = one.get(15, TimeUnit.SECONDS), b = two.get(15, TimeUnit.SECONDS);
            assertTrue("Exactly one competing begin must succeed", (a instanceof JSONObject) != (b instanceof JSONObject));
            Throwable rejected = (Throwable) (a instanceof JSONObject ? b : a);
            assertTrue(rejected.toString(), rejected instanceof IllegalStateException);
            assertEquals("SKETCH_LIVE_SESSION_CONFLICT", rejected.getMessage());
            JSONObject winnerArgs = a instanceof JSONObject ? original : assisted;
            JSONObject baseline = SketchLiveDrawing.read(context, id, "same");
            assertEquals(winnerArgs.getString("drawingMethod"), baseline.getString("drawingMethod"));
            assertEquals(winnerArgs.getInt("strokeDurationMs"), baseline.getInt("strokeDurationMs"));
            String immutable = baseline.toString();
            try (SketchProjectStore store = new SketchProjectStore(context)) {
                JSONObject metadata = store.liveSession(id, "same");
                assertEquals(baseline.getString("drawingMethod"), metadata.getString("drawingMethod"));
                assertEquals(baseline.getInt("strokeDurationMs"), metadata.getInt("strokeDurationMs"));
            }
            SketchLiveDrawing.begin(context, winnerArgs);
            assertEquals("An idempotent retry must retain the exact durable baseline", immutable,
                    SketchLiveDrawing.read(context, id, "same").toString());
            JSONObject loserArgs = a instanceof JSONObject ? assisted : original;
            try { SketchLiveDrawing.begin(context, loserArgs); fail("A changed intent must conflict"); }
            catch (IllegalStateException expected) { assertEquals("SKETCH_LIVE_SESSION_CONFLICT", expected.getMessage()); }
            assertEquals(immutable, SketchLiveDrawing.read(context, id, "same").toString());
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test public void humanTakeoverAdoptsAndEndsTheRevisionCommittedByAContendingWriter() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String id = create(context);
        SketchLiveDrawing.begin(context, beginArgs(id, "original", SketchDrawingPolicy.ORIGINAL, 100));
        JSONObject operation = new JSONObject().put("type", "add_object").put("layerId", "layer_1")
                .put("object", new JSONObject().put("id", "last_agent_stroke").put("kind", "line")
                        .put("points", new JSONArray("[[0.2,0.2,1],[0.8,0.8,1]]")));
        JSONArray operations = new JSONArray().put(operation);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), takingOver = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<JSONObject> writer = workers.submit(() -> {
                try (SketchProjectStore store = new SketchProjectStore(context)) {
                    return store.mutate(id, "last_agent_stroke", 1, "last_agent_stroke", current -> {
                        entered.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writer was not released");
                        SketchEditOps.apply(current, operation);
                        return current;
                    }, operations);
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<JSONObject> takeover = workers.submit(() -> {
                takingOver.countDown();
                try (SketchProjectStore store = new SketchProjectStore(context)) { return store.takeOverDrawing(id); }
            });
            assertTrue(takingOver.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(2, writer.get(15, TimeUnit.SECONDS).getLong("revision"));
            JSONObject adopted = takeover.get(15, TimeUnit.SECONDS);
            assertEquals(2, adopted.getLong("revision"));
            assertEquals("last_agent_stroke", SketchDocument.object(adopted, "last_agent_stroke").getString("id"));
            try (SketchProjectStore store = new SketchProjectStore(context)) {
                JSONObject ended = store.liveSession(id, "original");
                assertTrue(ended.getBoolean("ended"));
                assertEquals(store.latestSequence(id), ended.getLong("endSequence"));
                assertFalse(SketchDrawingPolicy.requiresOriginal(store.activeDrawingSession(id)));
            }
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void notificationDuringFlipbookPlaybackCannotBeOvertakenByTheOldBookPage() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String bookId = "book_" + UUID.randomUUID();
        SketchEditOps.execute(context, "sketch_book_create", new JSONObject().put("bookId", bookId)
                .put("operationId", "new").put("pageCount", 2).put("width", 256).put("height", 256));
        String pageId = SketchEditOps.execute(context, "sketch_book_open_page", new JSONObject()
                .put("bookId", bookId).put("page", 0)).getString("sketchId");
        String target = create(context);
        SketchLiveDrawing.begin(context, beginArgs(target, "notification", SketchDrawingPolicy.ORIGINAL, 5000));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ActivityController<SketchStudioActivity> controller = Robolectric.buildActivity(SketchStudioActivity.class,
                new Intent(context, SketchStudioActivity.class).putExtra("sketchId", pageId))) {
            SketchStudioActivity studio = controller.get();
            studio.getPreferences(0).edit().putBoolean("watchLiveDrawing", false)
                    .putString("lastBookId", bookId).putInt("lastBookPage", 0).commit();
            controller.create().start().resume().visible(); settle(studio);
            assertEquals(bookId, field(studio, "bookId"));
            setField(studio, "playing", true);
            ((ExecutorService) field(studio, "io")).execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            controller.newIntent(new Intent(context, SketchStudioActivity.class)
                    .setAction(SketchLiveNotification.ACTION_WATCH)
                    .putExtra(SketchLiveNotification.EXTRA_SKETCH_ID, target)
                    .putExtra(SketchLiveNotification.EXTRA_SESSION_ID, "notification"));
            // Queue the target load before allowing any stale book work to finish.
            ShadowLooper.idleMainLooper();
            release.countDown(); settle(studio);
            ((Runnable) field(studio, "watchTick")).run(); settle(studio);
            assertFalse((boolean) field(studio, "playing"));
            assertEquals(target, field(studio, "sketchId"));
            assertNull(field(studio, "bookId"));
            assertEquals("notification", field(studio, "notifiedSessionId"));
            SketchLivePlayer player = (SketchLivePlayer) field(studio, "livePlayer");
            assertNotNull(player);
            assertEquals("notification", field(player, "sessionId"));
        } finally { release.countDown(); }
    }

    private static Object beginWhenReady(Context context, JSONObject args, CountDownLatch ready,
                                         CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent begin was not released");
        try { return SketchLiveDrawing.begin(context, args); }
        catch (Exception failure) { return failure; }
    }

    private static String create(Context context) throws Exception {
        String id = "s_" + UUID.randomUUID();
        SketchEditOps.execute(context, "sketch_create", new JSONObject().put("sketchId", id).put("operationId", "new"));
        return id;
    }

    private static JSONObject beginArgs(String id, String session, String method, int duration) throws Exception {
        return new JSONObject().put("sketchId", id).put("operationId", session).put("expectedRevision", 1)
                .put("drawingMethod", method).put("strokeDurationMs", duration);
    }

    private static void settle(SketchStudioActivity studio) throws Exception {
        for (int i = 0; i < 6; i++) {
            ((ExecutorService) field(studio, "io")).submit(() -> {}).get(15, TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
