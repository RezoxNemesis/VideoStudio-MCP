package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exercises the actual Activity entry, saved baseline and local-save boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchNotificationEntryTest {
    @Test public void coldNotificationOpensItsExactBaselineDespiteManualModeBookAndHigherGlobalCursor() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        createLive(context, "other", false);
        String target = createLive(context, "target", false);
        try (ActivityController<SketchStudioActivity> controller = Robolectric.buildActivity(
                SketchStudioActivity.class, invitation(context, target, "target"))) {
            SketchStudioActivity studio = controller.get();
            studio.getPreferences(0).edit().putBoolean("watchLiveDrawing", false)
                    .putString("lastBookId", "unrelated_book").putLong("lastLiveSequence", 100000).commit();
            controller.create().start().resume().visible();
            settle(studio); watch(studio);
            assertEquals(target, field(studio, "sketchId"));
            assertNull(field(studio, "bookId"));
            assertTrue((boolean) field(studio, "watching"));
            SketchLivePlayer player = (SketchLivePlayer) field(studio, "livePlayer");
            assertNotNull(player);
            assertEquals("target", field(player, "sessionId"));
            JSONObject baseline = (JSONObject) field(player, "document");
            assertEquals("The first accepted stroke must still be ahead of presentation", 1, baseline.getLong("revision"));
            assertEquals(0, SketchDocument.layer(baseline, "layer_1").getJSONArray("objects").length());
            assertEquals(100000, studio.getPreferences(0).getLong("lastLiveSequence", 0));
            try (SketchProjectStore store = new SketchProjectStore(context)) {
                assertEquals("first_stroke", store.liveEvents(target, 0, 1).getJSONArray("events")
                        .getJSONObject(0).getJSONArray("operations").getJSONObject(0)
                        .getJSONObject("object").getString("id"));
            }
        }
    }

    @Test public void warmNotificationSwitchesFromHumanDrawingToItsExactSession() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String human = createDocument(context);
        String target = createLive(context, "target", false);
        try (ActivityController<SketchStudioActivity> controller = Robolectric.buildActivity(
                SketchStudioActivity.class, new Intent(context, SketchStudioActivity.class).putExtra("sketchId", human))) {
            SketchStudioActivity studio = controller.get();
            studio.getPreferences(0).edit().putBoolean("watchLiveDrawing", false).commit();
            controller.create().start().resume().visible(); settle(studio);
            assertEquals(human, field(studio, "sketchId"));
            controller.newIntent(invitation(context, target, "target"));
            settle(studio); watch(studio);
            assertEquals(target, field(studio, "sketchId"));
            assertTrue((boolean) field(studio, "watching"));
            assertEquals("target", field(field(studio, "livePlayer"), "sessionId"));
        }
    }

    @Test public void endedConsumedSessionReplaysFromItsFirstStrokeEvenAfterTheGlobalCursorPassedIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String target = createLive(context, "finished", true);
        try (SketchProjectStore store = new SketchProjectStore(context)) {
            store.consumeLiveSession(store.liveSession(target, "finished").getLong("sessionSequence"));
        }
        try (ActivityController<SketchStudioActivity> controller = Robolectric.buildActivity(
                SketchStudioActivity.class, invitation(context, target, "finished"))) {
            SketchStudioActivity studio = controller.get();
            studio.getPreferences(0).edit().putLong("lastLiveSequence", 100000).commit();
            controller.create().start().resume().visible(); settle(studio); watch(studio);
            SketchLivePlayer player = (SketchLivePlayer) field(studio, "livePlayer");
            assertNotNull(player);
            assertEquals("finished", field(player, "sessionId"));
            assertEquals(1, ((JSONObject) field(player, "document")).getLong("revision"));
            assertEquals(100000, studio.getPreferences(0).getLong("lastLiveSequence", 0));
        }
    }

    @Test public void completedNotificationViewStaysPinnedWhenAnotherDrawingSignalsTheWorkspace() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String target = createDocument(context);
        SketchEditOps.execute(context, "sketch_live_begin", new JSONObject().put("sketchId", target)
                .put("operationId", "empty").put("expectedRevision", 1).put("drawingMethod", "original_strokes"));
        SketchEditOps.execute(context, "sketch_live_end", new JSONObject().put("sketchId", target)
                .put("sessionId", "empty").put("expectedRevision", 1));
        try (ActivityController<SketchStudioActivity> controller = Robolectric.buildActivity(
                SketchStudioActivity.class, invitation(context, target, "empty"))) {
            SketchStudioActivity studio = controller.get();
            controller.create().start().resume().visible(); settle(studio); watch(studio);
            SketchLivePlayer player = (SketchLivePlayer) field(studio, "livePlayer");
            if (player != null) {
                ((ExecutorService) field(player, "reader")).submit(() -> {}).get(10, TimeUnit.SECONDS);
                ShadowLooper.idleMainLooper();
            }
            settle(studio);
            createLive(context, "unrelated", false);
            watch(studio);
            assertEquals("An unrelated live signal must not redirect the tapped session", target, field(studio, "sketchId"));
            assertEquals("empty", field(studio, "notifiedSessionId"));
            assertFalse((boolean) field(studio, "notificationReplayPending"));
        }
    }

    @Test public void rapidWarmInvitationsKeepTheLatestTargetAndFinishThePendingHumanStroke() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String human = createDocument(context);
        String first = createLive(context, "first", false), latest = createLive(context, "latest", false);
        CountDownLatch writerEntered = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        try (ActivityController<SketchStudioActivity> controller = Robolectric.buildActivity(
                SketchStudioActivity.class, new Intent(context, SketchStudioActivity.class).putExtra("sketchId", human))) {
            SketchStudioActivity studio = controller.get();
            studio.getPreferences(0).edit().putBoolean("watchLiveDrawing", false).commit();
            controller.create().start().resume().visible(); settle(studio);
            SketchEditorSession local = (SketchEditorSession) field(studio, "session");
            ((ExecutorService) field(local, "writer")).execute(() -> {
                writerEntered.countDown();
                try { releaseWriter.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
            local.submit(stroke("human_ink"));
            assertEquals(1, local.pending());
            controller.newIntent(invitation(context, first, "first"));
            controller.newIntent(invitation(context, latest, "latest"));
            releaseWriter.countDown();
            local.awaitIdle(10000); settle(studio); watch(studio);
            assertEquals(latest, field(studio, "sketchId"));
            assertEquals("latest", field(field(studio, "livePlayer"), "sessionId"));
            try (SketchProjectStore store = new SketchProjectStore(context)) {
                assertEquals("human_ink", SketchDocument.object(store.get(human), "human_ink").getString("id"));
            }
            assertNull(local.error());
        } finally { releaseWriter.countDown(); }
    }

    private static String createDocument(Context context) throws Exception {
        String id = "s_" + UUID.randomUUID().toString();
        SketchEditOps.execute(context, "sketch_create", new JSONObject().put("sketchId", id)
                .put("operationId", "new").put("width", 256).put("height", 256));
        return id;
    }

    private static String createLive(Context context, String session, boolean ended) throws Exception {
        String id = createDocument(context);
        SketchEditOps.execute(context, "sketch_live_begin", new JSONObject().put("sketchId", id)
                .put("operationId", session).put("expectedRevision", 1).put("strokeDurationMs", 5000)
                .put("drawingMethod", "original_strokes"));
        SketchEditOps.execute(context, "sketch_edit", new JSONObject().put("sketchId", id)
                .put("operationId", "first").put("expectedRevision", 1).put("operations", stroke("first_stroke")));
        if (ended) SketchEditOps.execute(context, "sketch_live_end", new JSONObject().put("sketchId", id)
                .put("sessionId", session).put("expectedRevision", 2));
        return id;
    }

    private static JSONArray stroke(String id) throws Exception {
        return new JSONArray().put(new JSONObject().put("type", "add_object").put("layerId", "layer_1")
                .put("object", new JSONObject().put("id", id).put("kind", "stroke")
                        .put("points", new JSONArray("[[0.2,0.2,1],[0.8,0.8,1]]"))));
    }

    private static Intent invitation(Context context, String sketchId, String sessionId) {
        return new Intent(context, SketchStudioActivity.class).setAction(SketchLiveNotification.ACTION_WATCH)
                .setData(new Uri.Builder().scheme("videostudio-live").authority("session").appendPath(sketchId).appendPath(sessionId).build())
                .putExtra(SketchLiveNotification.EXTRA_SKETCH_ID, sketchId).putExtra(SketchLiveNotification.EXTRA_SESSION_ID, sessionId);
    }

    private static void watch(SketchStudioActivity studio) throws Exception {
        ((Runnable) field(studio, "watchTick")).run();
        settle(studio);
    }

    private static void settle(SketchStudioActivity studio) throws Exception {
        for (int i = 0; i < 3; i++) {
            ((ExecutorService) field(studio, "io")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
