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
 * Source-pinned stage -> exact SQLite sketch_edit -> honest terminal receipt.
 * Foreground animation is tested separately; these tests never counterfeit
 * native stage success without calling finish() explicitly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentGestureStageTest {
    private JSONObject source(Context context)throws Exception{
        String id="gesture-stage-"+UUID.randomUUID();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","new-"+UUID.randomUUID())
                .put("name","Stage gesture regression").put("width",512)
                .put("height",768).put("fps",24).put("frameCount",12));
        JSONObject box=SketchEditOps.completeObject(new JSONObject()
                .put("id","stage_box").put("kind","rect").put("frame",0).put("exposure",12)
                .put("bounds",new JSONObject().put("x",.25).put("y",.3)
                        .put("width",.2).put("height",.3)));
        JSONObject curve=SketchEditOps.completeObject(new JSONObject()
                .put("id","stage_curve").put("kind","path")
                .put("frame",0).put("exposure",12)
                .put("commands",new JSONArray()
                        .put(new JSONArray().put("M").put(.2).put(.3))
                        .put(new JSONArray().put("Q").put(.4).put(.8).put(.75).put(.4))));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            long revision=store.revision(id);
            SketchEditOps.execute(context,"sketch_edit",new JSONObject()
                    .put("sketchId",id).put("operationId","setup-"+UUID.randomUUID())
                    .put("expectedRevision",revision)
                    .put("operations",new JSONArray()
                            .put(new JSONObject().put("type","add_object")
                                    .put("layerId","layer_1").put("object",box))
                            .put(new JSONObject().put("type","add_object")
                                    .put("layerId","layer_1").put("object",curve))));
            return store.get(id);
        }
    }
    private JSONObject request(JSONObject doc,JSONArray steps)throws Exception{
        return new JSONObject().put("sketchId",doc.getString("id"))
                .put("operationId","stage-move-"+UUID.randomUUID())
                .put("expectedRevision",doc.getLong("revision")).put("steps",steps);
    }

    @Test public void oneVisibleGestureIsAlwaysTheTerminalStepAndHasSourceBounds()throws Exception{
        JSONObject d=source(RuntimeEnvironment.getApplication());
        JSONObject gesture=new JSONObject().put("action","object_drag")
                .put("objectId","stage_box").put("frameIndex",7)
                .put("x",.2).put("y",.1);
        JSONObject program=request(d,new JSONArray()
                .put(new JSONObject().put("action","frame").put("frameIndex",7))
                .put(new JSONObject().put("action","object").put("objectId","stage_box"))
                .put(gesture));
        JSONObject allowed=SketchAgentStageCommands.normalize(program,d);
        assertEquals(3,allowed.getJSONArray("steps").length());
        assertEquals("object_drag",allowed.getJSONArray("steps").getJSONObject(2).getString("action"));
        assertThrows(Exception.class,()->SketchAgentStageCommands.normalize(request(d,
                new JSONArray().put(gesture).put(new JSONObject().put("action","onion")
                        .put("enabled",true))),d));
        assertThrows(Exception.class,()->SketchAgentStageCommands.normalize(request(d,
                new JSONArray().put(new JSONObject().put("action","node_drag")
                        .put("objectId","stage_curve").put("frameIndex",3)
                        .put("commandIndex",1).put("pairIndex",0).put("x",3).put("y",.3))),d));
    }

    @Test public void queuedProgramRequiresActualNativeSavedEditBeforeModifiedReceipt()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject d=source(app);
        JSONObject request=request(d,new JSONArray()
                .put(new JSONObject().put("action","frame").put("frameIndex",4))
                .put(new JSONObject().put("action","object_drag")
                        .put("objectId","stage_box").put("frameIndex",4)
                        .put("x",.18).put("y",-.05)));
        String before=SketchDocument.canonical(d);
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            JSONObject queued=stage.enqueue(app,request);
            assertFalse(queued.getBoolean("documentModified"));
            JSONObject first=stage.claim(d.getString("id"));
            assertEquals("frame",first.getJSONObject("step").getString("action"));
            stage.finish(first,true,new JSONObject().put("appliedOnScreen",true)
                    .put("actualFrameIndex",4));
            JSONObject claimed=stage.claim(d.getString("id"));
            assertEquals("object_drag",claimed.getJSONObject("step").getString("action"));
            assertFalse(stage.status(new JSONObject().put("sketchId",d.getString("id"))
                    .put("operationId",request.getString("operationId"))).getBoolean("documentModified"));
            try(SketchProjectStore store=new SketchProjectStore(app)){
                assertEquals(before,SketchDocument.canonical(store.get(d.getString("id"))));
            }
            SketchAgentGestureOps.Plan plan=SketchAgentGestureOps.plan(d,claimed.getJSONObject("step"));
            String editId=SketchAgentGestureOps.deterministicEditId(claimed);
            JSONObject nativeEdit=new JSONObject()
                    .put("sketchId",d.getString("id"))
                    .put("operationId",editId)
                    .put("expectedRevision",d.getLong("revision"))
                    .put("operations",new JSONArray().put(plan.editOperation));
            JSONObject edited=SketchEditOps.execute(app,"sketch_edit",nativeEdit);
            assertTrue(edited.getBoolean("persisted"));
            JSONObject receipt=stage.finish(claimed,true,new JSONObject()
                    .put("appliedOnScreen",true)
                    .put("documentModified",true)
                    .put("nativeEditId",editId)
                    .put("savedRevision",edited.getLong("revision")));
            assertTrue(receipt.getBoolean("applied"));
            assertTrue(receipt.getBoolean("documentModified"));
            assertEquals(2,receipt.getInt("completedSteps"));
            assertEquals(editId,receipt.getJSONObject("lastStepResult").getString("nativeEditId"));
            try(SketchProjectStore store=new SketchProjectStore(app)){
                JSONObject after=store.get(d.getString("id"));
                assertEquals(d.getLong("revision")+1,after.getLong("revision"));
                assertEquals(.18,SketchDocument.transformAt(
                        SketchDocument.object(after,"stage_box"),4).getDouble("x"),.000001);
            }
            JSONObject duplicate=SketchEditOps.execute(app,"sketch_edit",nativeEdit);
            assertEquals(edited.getLong("revision"),duplicate.getLong("revision"));
            assertTrue(stage.enqueue(app,request).getBoolean("reusedOperationId"));
        }
    }

    @Test public void refusedGestureNeverChangesTheOriginalOrItsStageCursor()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject d=source(app);
        JSONObject request=request(d,new JSONArray()
                .put(new JSONObject().put("action","node_drag")
                        .put("objectId","stage_curve").put("frameIndex",0)
                        .put("commandIndex",1).put("pairIndex",0)
                        .put("x",.55).put("y",.6)));
        String before=SketchDocument.canonical(d);
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            stage.enqueue(app,request);
            JSONObject claimed=stage.claim(d.getString("id"));
            JSONObject result=stage.finish(claimed,false,new JSONObject()
                    .put("appliedOnScreen",false)
                    .put("reason","Gesture interrupted before source change"));
            assertEquals("rejected",result.getString("state"));
            assertFalse(result.getBoolean("documentModified"));
            assertEquals(0,result.getInt("completedSteps"));
        }
        try(SketchProjectStore store=new SketchProjectStore(app)){
            assertEquals(before,SketchDocument.canonical(store.get(d.getString("id"))));
        }
    }

    @Test public void sourceRevisionMismatchAndUnknownGalleryActionNeverEnterQueue()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject d=source(app);
        JSONObject program=request(d,new JSONArray().put(new JSONObject()
                .put("action","node_drag")
                .put("objectId","stage_curve").put("frameIndex",0)
                .put("commandIndex",1).put("pairIndex",0).put("x",.5).put("y",.6)));
        assertThrows(IllegalStateException.class,()->
                SketchAgentStageCommands.normalize(
                        new JSONObject(program.toString())
                                .put("expectedRevision",d.getLong("revision")+1),d));
        assertThrows(Exception.class,()->
                SketchAgentStageCommands.normalize(request(d,new JSONArray()
                        .put(new JSONObject().put("action","gallery_open"))),d));
        assertFalse(SketchAgentGestureOps.capabilities().getBoolean("galleryAccess"));
    }
}
