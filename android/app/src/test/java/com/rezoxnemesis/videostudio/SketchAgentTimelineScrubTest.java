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
 * Timeline planning and durable stage receipts, not phone GPU frame proof.
 * Only SketchStudioActivity's actual foreground frame-slider callbacks can
 * truthfully call finish(), and no test here claims rendered pixel validation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentTimelineScrubTest {
    private static JSONObject request(int frame,int ms)throws Exception{
        return new JSONObject().put("action","scrub")
                .put("frameIndex",frame).put("durationMs",ms);
    }

    @Test public void boundedSmoothTimelineMovementIsMonotonicInBothDirections()throws Exception{
        JSONObject doc=SketchAgentGestureOpsTest.drawing();
        JSONObject before=SketchDocument.copy(doc);
        SketchAgentTimelineScrub.Plan right=SketchAgentTimelineScrub.plan(doc,request(10,800),1);
        assertEquals(1,right.sample(0));
        assertEquals(10,right.sample(1));
        int previous=1;
        for(int i=0;i<=SketchAgentTimelineScrub.MAX_VISUAL_SAMPLES;i++){
            int at=right.sample(i/(float)SketchAgentTimelineScrub.MAX_VISUAL_SAMPLES);
            assertTrue(at>=previous);
            assertTrue(at<=10);previous=at;
        }
        SketchAgentTimelineScrub.Plan left=SketchAgentTimelineScrub.plan(doc,request(0,1000),11);
        assertEquals(11,left.sample(0));
        assertEquals(0,left.sample(1));
        previous=11;
        for(int i=0;i<=SketchAgentTimelineScrub.MAX_VISUAL_SAMPLES;i++){
            int at=left.sample(i/(float)SketchAgentTimelineScrub.MAX_VISUAL_SAMPLES);
            assertTrue(at<=previous);
            previous=at;
        }
        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(doc));
    }

    @Test public void foregroundClaimCannotBeForgedByAnOutOfRangeOrNoOpGesture()throws Exception{
        JSONObject doc=SketchAgentGestureOpsTest.drawing();
        for(JSONObject invalid:new JSONObject[]{
                request(100,500),request(2,50),request(2,10000),
                request(0,400).put("gallery","content://private"),
                request(2,400).put("frameIndex","NaN")
        })assertThrows(Exception.class,()->
                SketchAgentTimelineScrub.plan(doc,invalid,0));
        assertThrows(Exception.class,()->SketchAgentTimelineScrub.plan(doc,request(4,600),4));
        assertThrows(Exception.class,()->SketchAgentTimelineScrub.plan(doc,
                request(4,600),30));
    }

    @Test public void receiptOnlyClaimsActualNativeRulerNotPixelRenderedFrames()throws Exception{
        JSONObject doc=SketchAgentGestureOpsTest.drawing();
        SketchAgentTimelineScrub.Plan plan=SketchAgentTimelineScrub.plan(doc,request(8,700),1);
        assertThrows(IllegalStateException.class,()->plan.evidence(7,8));
        assertThrows(IllegalStateException.class,()->plan.evidence(8,0));
        assertThrows(IllegalStateException.class,()->plan.evidence(8,200));
        JSONObject proof=plan.evidence(8,6);
        assertTrue(proof.getBoolean("sourceRevisionUnchanged"));
        assertFalse(proof.getBoolean("renderedPixelsVerified"));
        assertEquals(8,proof.getInt("actualScrubEndFrame"));
        assertEquals(6,proof.getInt("visibleFramePublications"));
    }

    @Test public void sketchMcpAdmitsScrubWithoutPretendingQueuedFramesWereShown()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        String sketch="sketch-ruler-"+UUID.randomUUID();
        SketchEditOps.execute(app,"sketch_create",new JSONObject()
                .put("sketchId",sketch).put("operationId","create-"+UUID.randomUUID())
                .put("name","Ruler gesture regression")
                .put("width",512).put("height",768).put("frameCount",36).put("fps",24));
        JSONObject doc;
        try(SketchProjectStore store=new SketchProjectStore(app)){doc=store.get(sketch);}
        String source=SketchDocument.canonical(doc);
        JSONArray steps=new JSONArray()
                .put(new JSONObject().put("action","frame").put("frameIndex",2))
                .put(request(25,600))
                .put(new JSONObject().put("action","onion").put("enabled",true));
        JSONObject program=new JSONObject()
                .put("sketchId",sketch).put("operationId","stage-"+UUID.randomUUID())
                .put("expectedRevision",doc.getLong("revision")).put("steps",steps);
        assertEquals(3,SketchAgentStageCommands.normalize(program,doc)
                .getJSONArray("steps").length());
        try(SketchAgentStageCommands queue=new SketchAgentStageCommands(app)){
            JSONObject accepted=queue.enqueue(app,program);
            assertEquals("queued",accepted.getString("state"));
            assertEquals(0,accepted.getInt("completedSteps"));
            assertFalse(accepted.getBoolean("documentModified"));
            JSONObject first=queue.claim(sketch);
            assertEquals("frame",first.getJSONObject("step").getString("action"));
            queue.finish(first,true,new JSONObject().put("appliedOnScreen",true)
                    .put("actualFrameIndex",2));
            JSONObject claimed=queue.claim(sketch);
            assertEquals("scrub",claimed.getJSONObject("step").getString("action"));
            JSONObject pending=queue.status(new JSONObject()
                    .put("sketchId",sketch).put("operationId",program.getString("operationId")));
            assertEquals("running",pending.getString("state"));
            assertEquals(1,pending.getInt("completedSteps"));
            assertFalse(pending.getBoolean("documentModified"));
            SketchAgentTimelineScrub.Plan plan=SketchAgentTimelineScrub.plan(
                    doc,claimed.getJSONObject("step"),2);
            JSONObject actual=plan.evidence(25,8)
                    .put("appliedOnScreen",true)
                    .put("actualFrameIndex",25);
            JSONObject after=queue.finish(claimed,true,actual);
            assertEquals(2,after.getInt("completedSteps"));
            assertEquals("queued",after.getString("state"));
            assertFalse(after.getBoolean("documentModified"));
        }
        try(SketchProjectStore store=new SketchProjectStore(app)){
            assertEquals(source,SketchDocument.canonical(store.get(sketch)));
        }
    }

    @Test public void explicitCapabilitiesNeverClaimFullPhoneRenderOrSourceChanges()throws Exception{
        JSONObject cap=SketchAgentTimelineScrub.capabilities();
        assertEquals("4.10.44",cap.getString("minimumNativeVersion"));
        assertEquals(12,SketchAgentTimelineScrub.MAX_VISUAL_SAMPLES);
        assertEquals(13,cap.getInt("maximumFramePublications"));
        assertFalse(cap.getBoolean("sourceChanged"));
        assertFalse(cap.getBoolean("pixelOutputVerification"));
        assertFalse(cap.getBoolean("galleryAccess"));
        JSONObject stage=SketchAgentStageCommands.capabilities();
        assertEquals("4.10.44",stage.getString("minimumNativeVersion"));
        assertEquals("scrub",stage.getJSONObject("visibleTimelineScrub").getString("stageAction"));
        assertTrue(stage.getJSONArray("availableSteps").toString().contains("scrub"));
    }
}
