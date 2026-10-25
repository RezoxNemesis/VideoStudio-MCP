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

/**
 * The MCP cannot claim that something was clicked or drawn until the real
 * Sketch Studio activity has acknowledged its UI state change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SketchAgentWorkspaceTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private JSONObject create()throws Exception{
        String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",
                new JSONObject().put("sketchId",id)
                        .put("operationId",UUID.randomUUID().toString())
                        .put("name","Native agent hand test")
                        .put("width",640).put("height",480)
                        .put("fps",24).put("frameCount",48));
        try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}
    }

    private JSONObject request(JSONObject doc,JSONArray actions)throws Exception{
        return new JSONObject().put("sketchId",doc.getString("id"))
                .put("expectedRevision",doc.getLong("revision"))
                .put("operationId",UUID.randomUUID().toString())
                .put("actions",actions);
    }

    @Test public void planIsReadOnlyAndQueueNeverClaimsToHaveDrawnOrMovedUi()throws Exception{
        JSONObject doc=create(),req=request(doc,new JSONArray()
                .put(new JSONObject().put("type","seek_frame").put("frameIndex",11))
                .put(new JSONObject().put("type","select_tool").put("tool","curve"))
                .put(new JSONObject().put("type","toggle_onion")
                        .put("enabled",true).put("range",2).put("alpha",50)));
        String before=SketchDocument.canonical(doc);
        JSONObject plan=SketchAgentWorkspace.plan(context,req);
        assertTrue(plan.getBoolean("dryRun"));
        assertEquals(3,plan.getInt("stepCount"));
        assertEquals(before,SketchDocument.canonical(sketch(doc.getString("id"))));
        JSONObject queued=SketchAgentWorkspace.enqueue(context,req);
        assertTrue(queued.getBoolean("ok"));
        assertEquals("pending",queued.getString("status"));
        assertFalse(queued.getBoolean("applied"));
        assertFalse(queued.getBoolean("visibleSketchStudio"));
        assertEquals(0,queued.getInt("uiAppliedSteps"));
        assertFalse(queued.getBoolean("previewVerified"));
        assertTrue(queued.getBoolean("sourceDrawingUnchanged"));
        assertEquals(before,SketchDocument.canonical(sketch(doc.getString("id"))));
    }

    @Test public void oneRealVisibleAckPerStepAndIdempotentOperationId()throws Exception {
        JSONObject doc=create(),req=request(doc,new JSONArray()
                .put(new JSONObject().put("type","seek_frame").put("frameIndex",8))
                .put(new JSONObject().put("type","select_tool").put("tool","nodes")));
        String id=doc.getString("id"),operation=req.getString("operationId");
        SketchAgentWorkspace.enqueue(context,req);
        assertNotNull(SketchAgentWorkspace.nextForUi(context,id,doc.getLong("revision")));
        assertEquals("seek_frame",SketchAgentWorkspace.nextForUi(context,id,doc.getLong("revision"))
                .getJSONObject("step").getString("type"));
        JSONObject first=SketchAgentWorkspace.acknowledged(context,id,operation,0,
                new JSONObject().put("action","seek_frame").put("frameIndex",8));
        assertEquals("pending",first.getString("status"));
        assertEquals(1,first.getInt("uiAppliedSteps"));
        assertThrows(IllegalStateException.class,
                ()->SketchAgentWorkspace.acknowledged(context,id,operation,0,new JSONObject()));
        SketchAgentWorkspace.visible(context,id,true);
        assertTrue(SketchAgentWorkspace.status(context,new JSONObject().put("sketchId",id))
                .getBoolean("visibleSketchStudio"));
        JSONObject done=SketchAgentWorkspace.acknowledged(context,id,operation,1,
                new JSONObject().put("action","select_tool").put("tool","nodes"));
        assertTrue(done.getBoolean("applied"));
        assertEquals(2,done.getInt("uiAppliedSteps"));
        assertNull(SketchAgentWorkspace.nextForUi(context,id,doc.getLong("revision")));
        JSONObject again=SketchAgentWorkspace.enqueue(context,req);
        assertTrue(again.getBoolean("operationReplayed"));
        assertTrue(again.getBoolean("applied"));
        JSONObject different=new JSONObject(req.toString());
        different.put("actions",new JSONArray().put(new JSONObject().put("type","reset_view")));
        assertThrows(IllegalStateException.class,
                ()->SketchAgentWorkspace.enqueue(context,different));
        SketchAgentWorkspace.visible(context,id,false);
    }

    @Test public void humanPauseAndCancellationAreHonoredBeforeUiCanAct()throws Exception {
        JSONObject doc=create(),req=request(doc,new JSONArray()
                .put(new JSONObject().put("type","select_tool").put("tool","stroke")));
        String id=doc.getString("id");
        SketchAgentWorkspace.enqueue(context,req);
        SketchAgentWorkspace.ownerPause(context,id,true);
        assertTrue(SketchAgentWorkspace.ownerPaused(context,id));
        assertNull(SketchAgentWorkspace.nextForUi(context,id,doc.getLong("revision")));
        JSONObject paused=SketchAgentWorkspace.status(context,new JSONObject().put("sketchId",id));
        assertTrue(paused.getBoolean("pausedByOwner"));
        assertEquals(0,paused.getInt("uiAppliedSteps"));
        SketchAgentWorkspace.ownerPause(context,id,false);
        assertNotNull(SketchAgentWorkspace.nextForUi(context,id,doc.getLong("revision")));
        JSONObject cancelled=SketchAgentWorkspace.cancel(context,
                new JSONObject().put("sketchId",id).put("operationId",req.getString("operationId")));
        assertEquals("cancelled",cancelled.getString("status"));
        assertFalse(cancelled.getBoolean("applied"));
        assertNull(SketchAgentWorkspace.nextForUi(context,id,doc.getLong("revision")));
    }

    @Test public void staleRevisionBlocksUiWithoutSilentlyReplacingNewerArtwork()throws Exception {
        JSONObject doc=create(),req=request(doc,new JSONArray()
                .put(new JSONObject().put("type","seek_frame").put("frameIndex",3)));
        String id=doc.getString("id");
        SketchAgentWorkspace.enqueue(context,req);
        // Another source edit advances revision independently.
        SketchEditOps.execute(context,"sketch_edit",new JSONObject()
                .put("sketchId",id).put("operationId",UUID.randomUUID().toString())
                .put("expectedRevision",doc.getLong("revision"))
                .put("operations",new JSONArray().put(new JSONObject().put("type","set_timing")
                        .put("fps",30))));
        JSONObject newer=sketch(id);
        assertTrue(newer.getLong("revision")>doc.getLong("revision"));
        assertNull(SketchAgentWorkspace.nextForUi(context,id,newer.getLong("revision")));
        JSONObject blocked=SketchAgentWorkspace.status(context,new JSONObject().put("sketchId",id));
        assertEquals("blocked_revision",blocked.getString("status"));
        assertEquals(0,blocked.getInt("uiAppliedSteps"));
        assertEquals(30,newer.getInt("fps"));
    }

    @Test public void strictNativeControlsCannotReachGalleryOrForeignObject()throws Exception {
        JSONObject doc=create();
        String[] illegal={"open_gallery","press_random_button","tap_screen","screenshot_gallery",
                "inject_touch","draw_fake_pointer"};
        for(String command:illegal){
            JSONObject req=request(doc,new JSONArray().put(new JSONObject().put("type",command)));
            assertThrows(IllegalArgumentException.class,
                    ()->SketchAgentWorkspace.plan(context,req));
        }
        JSONObject invalidLayer=request(doc,new JSONArray().put(new JSONObject()
                .put("type","select_layer").put("layerId","foreign-layer")));
        assertThrows(IllegalArgumentException.class,
                ()->SketchAgentWorkspace.plan(context,invalidLayer));
        JSONObject invalidFrame=request(doc,new JSONArray().put(new JSONObject()
                .put("type","seek_frame").put("frameIndex",60)));
        assertThrows(IllegalArgumentException.class,
                ()->SketchAgentWorkspace.plan(context,invalidFrame));
        JSONObject invalidBrush=request(doc,new JSONArray().put(new JSONObject()
                .put("type","set_brush").put("color","#FFFFFFFF")
                .put("width",.98)));
        assertThrows(IllegalArgumentException.class,
                ()->SketchAgentWorkspace.plan(context,invalidBrush));
        JSONObject noSilentTouch=request(doc,new JSONArray().put(new JSONObject()
                .put("type","show_curve")).put(new JSONObject().put("type","seek_frame")
                .put("frameIndex",0)));
        assertThrows(IllegalArgumentException.class,
                ()->SketchAgentWorkspace.plan(context,noSilentTouch));
        JSONObject cap=SketchAgentWorkspace.capabilities();
        assertFalse(cap.getBoolean("physicalTouchInjection"));
        assertFalse(cap.getBoolean("galleryAccess"));
        assertFalse(cap.getBoolean("previewVerifiedByUiAction"));
    }

    private JSONObject sketch(String id)throws Exception {
        try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}
    }
}
