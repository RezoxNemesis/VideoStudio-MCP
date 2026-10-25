package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/** Native queued ChatGPT UI commands must never mutate a saved drawing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SketchAgentStageControlTest {
    private Context context;

    @Before public void clearStage(){
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("videostudio_sketch_agent_stage_v1",Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private JSONObject source()throws Exception{
        String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","stage-create-001")
                .put("name","Human direct control test")
                .put("width",600).put("height",900)
                .put("frameCount",16).put("fps",24));
        try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}
    }

    private JSONObject request(JSONObject doc)throws Exception{
        return new JSONObject()
                .put("sketchId",doc.getString("id"))
                .put("operationId","agent-stage-001")
                .put("expectedRevision",doc.getLong("revision"))
                .put("steps",new JSONArray()
                        .put(new JSONObject().put("type","tool").put("tool","curve"))
                        .put(new JSONObject().put("type","viewport")
                                .put("x",.7).put("y",.32).put("zoom",2.75))
                        .put(new JSONObject().put("type","frame").put("frame",12))
                        .put(new JSONObject().put("type","layer").put("layerId","layer_1"))
                        .put(new JSONObject().put("type","onion")
                                .put("enabled",true).put("range",2).put("alpha",60)));
    }

    @Test public void validAgentActionsAreStagedNotPretendedExecuted()throws Exception{
        JSONObject doc=source();
        String before=SketchDocument.canonical(doc);
        JSONObject req=request(doc);
        JSONObject staged=SketchAgentStageControl.stage(context,req);
        assertTrue(staged.getBoolean("ok"));
        assertEquals("queued_for_visible_editor",staged.getString("status"));
        assertEquals(0,staged.getInt("executedSteps"));
        assertEquals(5,staged.getInt("stepCount"));
        assertFalse(staged.getBoolean("documentModified"));
        assertFalse(staged.getBoolean("rendered"));
        assertEquals(staged.getLong("sequence"),
                SketchAgentStageControl.stage(context,req).getLong("sequence"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            assertEquals(before,SketchDocument.canonical(store.get(doc.getString("id"))));
        }
    }

    @Test public void stageAdvancesOnlyAfterUiAcknowledgesEachRealStep()throws Exception{
        JSONObject doc=source(),req=request(doc);
        JSONObject queued=SketchAgentStageControl.stage(context,req);
        long seq=queued.getLong("sequence");
        String id=doc.getString("id");
        for(int i=0;i<5;i++){
            JSONObject next=SketchAgentStageControl.nextVisibleStep(context,id,doc.getLong("revision"));
            assertEquals(i,next.getInt("index"));
            assertEquals(i,SketchAgentStageControl.status(context,new JSONObject()
                    .put("sketchId",id)).getInt("executedSteps"));
            SketchAgentStageControl.acknowledge(context,id,doc.getLong("revision"),seq,i);
        }
        assertNull(SketchAgentStageControl.nextVisibleStep(context,id,doc.getLong("revision")));
        JSONObject status=SketchAgentStageControl.status(context,new JSONObject().put("sketchId",id));
        assertEquals("applied_by_visible_editor",status.getString("status"));
        assertEquals(5,status.getInt("executedSteps"));
        assertThrows(IllegalStateException.class,()->
                SketchAgentStageControl.acknowledge(context,id,doc.getLong("revision"),seq,0));
    }

    @Test public void busyStageAndConflictingOperationNeverOverwritePendingUserWorkspace()throws Exception{
        JSONObject doc=source(),req=request(doc);
        SketchAgentStageControl.stage(context,req);
        JSONObject conflict=request(doc);
        conflict.put("steps",new JSONArray().put(new JSONObject().put("type","frame").put("frame",1)));
        assertThrows(IllegalStateException.class,()->SketchAgentStageControl.stage(context,conflict));
        JSONObject separate=request(doc);
        separate.put("operationId","second-stage-001");
        assertThrows(IllegalStateException.class,()->SketchAgentStageControl.stage(context,separate));
        assertEquals(0,SketchAgentStageControl.status(context,new JSONObject()
                .put("sketchId",doc.getString("id"))).getInt("executedSteps"));
    }

    @Test public void strictSourceAndToolsRejectUntrustedInputs()throws Exception{
        JSONObject doc=source(),request=request(doc);
        JSONArray untrusted=new JSONArray().put(new JSONObject()
                .put("type","gallery").put("mode","scan_all_media"));
        request.put("steps",untrusted);
        assertThrows(IllegalArgumentException.class,()->
                SketchAgentStageControl.stage(context,request));
        request.put("steps",new JSONArray().put(new JSONObject()
                .put("type","tool").put("tool","install_apk")));
        assertThrows(IllegalArgumentException.class,()->
                SketchAgentStageControl.stage(context,request));
        request.put("steps",new JSONArray().put(new JSONObject()
                .put("type","viewport").put("x",Double.NaN).put("y",.3).put("zoom",2)));
        assertThrows(Exception.class,()->SketchAgentStageControl.stage(context,request));
        request.put("steps",new JSONArray().put(new JSONObject()
                .put("type","frame").put("frame",99999)));
        assertThrows(IllegalArgumentException.class,()->
                SketchAgentStageControl.stage(context,request));
        request.put("steps",new JSONArray().put(new JSONObject()
                .put("type","object").put("objectId","another-user-private-object")));
        assertThrows(Exception.class,()->SketchAgentStageControl.stage(context,request));
        request=request(doc);
        request.put("expectedRevision",doc.getLong("revision")+1);
        assertThrows(IllegalStateException.class,()->
                SketchAgentStageControl.stage(context,request));
    }

    @Test public void ownershipAndRevisionGuardRejectWrongVisibleCanvas()throws Exception{
        JSONObject doc=source(),r=request(doc);
        SketchAgentStageControl.stage(context,r);
        assertNull(SketchAgentStageControl.nextVisibleStep(context,"another-sketch",
                doc.getLong("revision")));
        assertNull(SketchAgentStageControl.nextVisibleStep(context,doc.getString("id"),
                doc.getLong("revision")+1));
    }
}
