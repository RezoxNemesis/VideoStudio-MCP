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
@Config(sdk=33, manifest=Config.NONE)
public final class SketchCurrentLiveFollowTest {
    private static String created(Context context) throws Exception {
        String id="follow_"+UUID.randomUUID();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","create"));
        return id;
    }
    private static JSONObject edit(String id, String op, long rev) throws Exception {
        JSONArray operations=new JSONArray().put(new JSONObject()
                .put("type","add_object").put("layerId","layer_1")
                .put("object",new JSONObject().put("id",op).put("kind","line")
                        .put("points",new JSONArray("[[0.14,0.28,1],[0.68,0.72,1]]"))));
        return new JSONObject().put("sketchId",id).put("operationId",op)
                .put("expectedRevision",rev).put("operations",operations);
    }
    @Test public void latestNativeAgentStrokeSessionReplacesOldCompletedReplay() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=created(c);
        JSONObject first=SketchAgentLiveBridge.edit(c,edit(id,"face_stroke",1));
        assertEquals(2,first.getLong("revision"));
        assertEquals(1,first.getInt("watchableNativeOperations"));
        assertEquals(SketchDrawingPolicy.ASSISTED,first.getString("autoLiveMethod"));
        String firstSession=first.getString("watchSessionId");
        JSONObject second=SketchAgentLiveBridge.edit(c,edit(id,"rose_stroke",2));
        assertEquals(3,second.getLong("revision"));
        try(SketchProjectStore store=new SketchProjectStore(c)){
            JSONObject freshest=store.newestFollowSession(0,System.currentTimeMillis());
            assertNotNull(freshest);
            assertEquals(second.getString("watchSessionId"),freshest.getString("sessionId"));
            assertEquals(id,freshest.getString("sketchId"));
            assertTrue(freshest.getBoolean("ended"));
            assertTrue(freshest.getLong("endedAtMs")>0);
            assertTrue(freshest.getLong("sessionSequence")>
                    store.liveSession(id,firstSession).getLong("sessionSequence"));
            assertEquals("No stale session should be automatically selected after recent work has expired",
                    null,store.newestFollowSession(0,System.currentTimeMillis()+180_000L));
            assertEquals("History remains available explicitly",
                    second.getString("watchSessionId"),
                    store.latestCompletedLiveSession().getString("sessionId"));
            JSONObject source=store.get(id);
            assertEquals(3,source.getInt("revision"));
            assertNotNull(SketchDocument.object(source,"face_stroke"));
            assertNotNull(SketchDocument.object(source,"rose_stroke"));
        }
    }
    @Test public void nativeAutoJournalContainsEditableOperationsRatherThanOnlyFinishedPicture() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        String id=created(c);
        JSONObject response=SketchAgentLiveBridge.edit(c,edit(id,"new_neck_ink",1));
        JSONObject baseline=SketchLiveDrawing.read(c,id,response.getString("watchSessionId"));
        assertEquals(1,baseline.getJSONObject("document").getLong("revision"));
        assertEquals(SketchDrawingPolicy.ASSISTED,baseline.getString("drawingMethod"));
        try(SketchProjectStore store=new SketchProjectStore(c)){
            JSONObject stream=store.liveSession(id,response.getString("watchSessionId"));
            JSONArray events=store.liveEvents(id,stream.getLong("afterSequence"),4).getJSONArray("events");
            assertEquals(1,events.length());
            assertEquals("new_neck_ink",events.getJSONObject(0).getJSONArray("operations")
                    .getJSONObject(0).getJSONObject("object").getString("id"));
            assertEquals(2,events.getJSONObject(0).getLong("revision"));
            assertTrue(stream.getLong("endSequence")>stream.getLong("afterSequence"));
        }
    }
    @Test public void duplicateNativeCommandCannotInventAnotherReplayOrOverwriteArt() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=created(c);
        JSONObject request=edit(id,"only_one_rose",1);
        JSONObject before=SketchAgentLiveBridge.edit(c,request);
        JSONObject retry=SketchAgentLiveBridge.edit(c,request);
        assertEquals(before.getLong("revision"),retry.getLong("revision"));
        try(SketchProjectStore store=new SketchProjectStore(c)){
            assertEquals(1,store.get(id).getJSONArray("layers").getJSONObject(0)
                    .getJSONArray("objects").length());
            JSONObject recent=store.newestFollowSession(0,System.currentTimeMillis());
            assertEquals(before.getString("watchSessionId"),recent.getString("sessionId"));
        }
    }
    @Test public void currentExplicitLiveSessionRemainsOwnedAndAutoBridgeDoesNotReplaceIt() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=created(c);
        SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id)
                .put("expectedRevision",1).put("operationId","explicit_user_session")
                .put("drawingMethod",SketchDrawingPolicy.ORIGINAL));
        JSONObject result=SketchAgentLiveBridge.edit(c,edit(id,"within_artist_session",1));
        assertFalse("An explicit artist-controlled session must not be replaced",
                result.has("autoLiveMethod"));
        try(SketchProjectStore store=new SketchProjectStore(c)){
            JSONObject session=store.activeDrawingSession(id);
            assertEquals("explicit_user_session",session.getString("sessionId"));
            assertEquals(SketchDrawingPolicy.ORIGINAL,SketchDrawingPolicy.method(session));
            JSONObject current=store.get(id);
            assertEquals(2,current.getLong("revision"));
        }
    }
}
