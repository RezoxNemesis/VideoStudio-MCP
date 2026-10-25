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
 * Cross-instance durable MCP -> Sketch Studio stage lifecycle tests.
 * Tests are NOT on-device visual certification: successful status requires
 * an actual foreground finish() call, which only SketchStudioActivity makes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentStageCommandsTest {
    private static JSONObject source(Context ctx)throws Exception{
        String sketchId="sketch-"+UUID.randomUUID(),operation="create-"+UUID.randomUUID();
        SketchEditOps.execute(ctx,"sketch_create",new JSONObject()
                .put("sketchId",sketchId).put("operationId",operation)
                .put("name","Private animator workspace").put("frameCount",48)
                .put("width",720).put("height",1280).put("fps",24));
        try(SketchProjectStore projects=new SketchProjectStore(ctx)){
            return projects.get(sketchId);
        }
    }
    private static JSONObject request(JSONObject doc,JSONArray steps)throws Exception{
        return new JSONObject().put("sketchId",doc.getString("id"))
                .put("operationId","stage-"+UUID.randomUUID())
                .put("expectedRevision",doc.getLong("revision"))
                .put("steps",steps);
    }

    @Test public void queuedWorkNeverClaimsTheDrawingWasAlreadyPerformed()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject doc=source(app);
        JSONObject request=request(doc,new JSONArray()
                .put(new JSONObject().put("action","tool").put("tool","curve"))
                .put(new JSONObject().put("action","frame").put("frameIndex",21))
                .put(new JSONObject().put("action","camera").put("zoom",3)
                    .put("centerX",.7).put("centerY",.4).put("animationMs",250))
                .put(new JSONObject().put("action","wait").put("durationMs",200))
                .put(new JSONObject().put("action","transform_curve")));
        String before=SketchDocument.canonical(doc);
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            JSONObject queued=stage.enqueue(app,request);
            assertEquals("queued",queued.getString("state"));
            assertEquals(0,queued.getInt("completedSteps"));
            assertFalse(queued.getBoolean("applied"));
            assertFalse(queued.getBoolean("documentModified"));
            assertFalse(queued.getBoolean("galleryAccess"));
            try(SketchProjectStore projects=new SketchProjectStore(app)){
                assertEquals(before,SketchDocument.canonical(projects.get(doc.getString("id"))));
            }
        }
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            JSONObject same=stage.enqueue(app,request);
            assertTrue(same.getBoolean("reusedOperationId"));
            for(int i=0;i<5;i++){
                JSONObject claimed=stage.claim(doc.getString("id"));
                assertNotNull(claimed);
                assertEquals(i,claimed.getInt("stepIndex"));
                assertEquals(5,claimed.getInt("stepCount"));
                assertEquals("running",stage.status(new JSONObject().put("sketchId",doc.getString("id"))
                    .put("operationId",request.getString("operationId")))).getString("state"));
                JSONObject result=stage.finish(claimed,true,new JSONObject()
                        .put("appliedOnScreen",true).put("actualFrameIndex",21)
                        .put("atMs",System.currentTimeMillis()));
                assertEquals(i+1,result.getInt("completedSteps"));
                assertEquals(i==4?"applied":"queued",result.getString("state"));
            }
            assertNull(stage.claim(doc.getString("id")));
            assertTrue(stage.status(new JSONObject().put("sketchId",doc.getString("id")).put("operationId",request.getString("operationId"))).getBoolean("applied"));
        }
        try(SketchProjectStore projects=new SketchProjectStore(app)){
            assertEquals(before,SketchDocument.canonical(projects.get(doc.getString("id"))));
        }
    }

    @Test public void leaseReleaseDoesNotPretendItExecuted()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject doc=source(app),program=request(doc,new JSONArray()
                .put(new JSONObject().put("action","fit")));
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            stage.enqueue(app,program);
            JSONObject claimed=stage.claim(doc.getString("id"));
            assertNotNull(claimed);
            assertEquals("running",stage.status(new JSONObject().put("sketchId",doc.getString("id"))
                    .put("operationId",program.getString("operationId")))).getString("state"));
            stage.release(claimed);
            assertEquals("queued",stage.status(new JSONObject().put("sketchId",doc.getString("id")).put("operationId",program.getString("operationId"))).getString("state"));
            assertEquals(0,stage.status(new JSONObject().put("sketchId",doc.getString("id")).put("operationId",program.getString("operationId"))).getInt("completedSteps"));
            assertNotNull(stage.claim(doc.getString("id")));
        }
    }

    @Test public void duplicateIdWithDifferentProgramFailsWithoutOverwrite()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject doc=source(app),program=request(doc,new JSONArray()
                .put(new JSONObject().put("action","frame").put("frameIndex",2)));
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            stage.enqueue(app,program);
            JSONObject conflicting=new JSONObject(program.toString());
            conflicting.put("steps",new JSONArray()
                    .put(new JSONObject().put("action","frame").put("frameIndex",8)));
            IllegalStateException error=assertThrows(IllegalStateException.class,
                    ()->stage.enqueue(app,conflicting));
            assertTrue(error.getMessage().contains("OPERATION_CONFLICT"));
            assertEquals("queued",stage.status(new JSONObject().put("sketchId",doc.getString("id")).put("operationId",program.getString("operationId"))).getString("state"));
        }
    }

    @Test public void neverAllowShellGalleryMediaImportOrInvalidCoordinates()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject doc=source(app);
        JSONArray[] untrusted={
            new JSONArray().put(new JSONObject().put("action","gallery_browse")),
            new JSONArray().put(new JSONObject().put("action","shell").put("command","rm -rf")),
            new JSONArray().put(new JSONObject().put("action","camera")
                .put("zoom",99).put("centerX",.5).put("centerY",.5)),
            new JSONArray().put(new JSONObject().put("action","camera")
                .put("zoom",3).put("centerX","infinity").put("centerY",.5)),
            new JSONArray().put(new JSONObject().put("action","tool").put("tool","paint")),
            new JSONArray().put(new JSONObject().put("action","frame").put("frameIndex",4000)),
            new JSONArray().put(new JSONObject().put("action","shape_curve").put("objectId","not-owned")),
            new JSONArray().put(new JSONObject().put("action","wait").put("durationMs",5000)),
            new JSONArray().put(new JSONObject().put("action","transform_curve"))
                .put(new JSONObject().put("action","frame").put("frameIndex",3))
        };
        for(JSONArray steps:untrusted){
            JSONObject request=request(doc,steps);
            assertThrows("Should reject "+steps,Exception.class,
                    ()->SketchAgentStageCommands.normalize(request,doc));
        }
    }

    @Test public void exactRevisionPinPreventsStaleStageScripts()throws Exception{
        Context app=RuntimeEnvironment.getApplication();JSONObject doc=source(app);
        JSONObject request=request(doc,new JSONArray().put(new JSONObject().put("action","fit")));
        JSONObject stale=new JSONObject(request.toString()).put("expectedRevision",doc.getLong("revision")+1);
        assertThrows(IllegalStateException.class,()->
                SketchAgentStageCommands.normalize(stale,doc));
    }

    @Test public void failedForegroundStepNeverIncrementsProgress()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject doc=source(app),program=request(doc,new JSONArray()
                .put(new JSONObject().put("action","grid").put("enabled",true)));
        try(SketchAgentStageCommands stage=new SketchAgentStageCommands(app)){
            stage.enqueue(app,program);
            JSONObject step=stage.claim(doc.getString("id"));
            JSONObject rejected=stage.finish(step,false,
                    new JSONObject().put("appliedOnScreen",false).put("reason","Foreground canvas changed"));
            assertEquals("rejected",rejected.getString("state"));
            assertEquals(0,rejected.getInt("completedSteps"));
            assertFalse(rejected.getBoolean("applied"));
        }
    }
}
