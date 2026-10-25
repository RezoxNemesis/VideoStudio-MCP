package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/**
 * Exact source-session isolation: a queued MCP action is never an on-screen
 * success until the matching SketchStudioActivity settles its real UI receipt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchLiveViewerCommandsTest {
    private String[] live(Context ctx)throws Exception{
        String id="sketch_"+UUID.randomUUID(),session="session_"+UUID.randomUUID();
        SketchEditOps.execute(ctx,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","create")
                .put("width",256).put("height",256).put("frameCount",1));
        SketchLiveDrawing.begin(ctx,new JSONObject()
                .put("sketchId",id).put("expectedRevision",1)
                .put("operationId",session).put("strokeDurationMs",80));
        return new String[]{id,session};
    }

    private JSONObject cmd(String[] pins,String op,String action)throws Exception{
        return new JSONObject().put("sketchId",pins[0])
                .put("sessionId",pins[1]).put("operationId",op)
                .put("command",action);
    }

    /** Read receipts by identity only; command parameters are not status query fields. */
    private JSONObject receiptQuery(String[] pins,String operationId)throws Exception{
        return new JSONObject().put("sketchId",pins[0])
                .put("sessionId",pins[1]).put("operationId",operationId);
    }

    @Test public void queuedIsNotCompletedUntilRealForegroundAck()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();
        String[] pair=live(ctx);
        try(SketchLiveViewerCommands queue=new SketchLiveViewerCommands(ctx)){
            JSONObject request=cmd(pair,"speed_once","speed").put("speed",.25);
            JSONObject queued=queue.enqueue(ctx,request);
            assertEquals("queued",queued.getString("state"));
            assertFalse(queued.getBoolean("foregroundApplied"));
            assertFalse(queued.getBoolean("reused"));
            JSONObject repeat=queue.enqueue(ctx,request);
            assertTrue(repeat.getBoolean("reused"));
            assertEquals("queued",repeat.getString("state"));
            assertNull(queue.claim("someone_else",pair[1]));
            assertNull(queue.claim(pair[0],"different_session"));

            JSONObject claim=queue.claim(pair[0],pair[1]);
            assertNotNull(claim);
            assertEquals("running",claim.getString("state"));
            assertEquals("running",queue.status(receiptQuery(pair,"speed_once")).getString("state"));
            JSONObject result=queue.finish(claim,true,new JSONObject()
                    .put("note","Native view speed applied")
                    .put("viewPaused",false).put("viewRate",.25));
            assertEquals("applied",result.getString("state"));
            assertTrue(result.getBoolean("foregroundApplied"));
            assertEquals(.25,result.getJSONObject("evidence").getDouble("viewRate"),.00001);
            assertNull(queue.claim(pair[0],pair[1]));
            assertThrows(IllegalStateException.class,()->queue.finish(claim,true,new JSONObject()));
        }
    }

    @Test public void interruptedBeforeOnScreenExecutionCanBeReleasedWithoutPretendingSuccess()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();
        String[] pair=live(ctx);
        try(SketchLiveViewerCommands queue=new SketchLiveViewerCommands(ctx)){
            queue.enqueue(ctx,cmd(pair,"pause_once","pause"));
            JSONObject claim=queue.claim(pair[0],pair[1]);
            assertNotNull(claim);
            queue.release(claim);
            assertEquals("queued",queue.status(receiptQuery(pair,"pause_once")).getString("state"));
            assertFalse(queue.status(receiptQuery(pair,"pause_once")).getBoolean("foregroundApplied"));
            JSONObject retried=queue.claim(pair[0],pair[1]);
            assertNotNull(retried);
            JSONObject rejected=queue.finish(retried,false,new JSONObject().put("note","Sketch player closed"));
            assertEquals("rejected",rejected.getString("state"));
            assertFalse(rejected.getBoolean("foregroundApplied"));
        }
    }

    @Test public void rejectsChangedOperationIDsUnpinnedSessionsAndIllegalRate()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();String[] pair=live(ctx);
        try(SketchLiveViewerCommands queue=new SketchLiveViewerCommands(ctx)){
            queue.enqueue(ctx,cmd(pair,"same_op","pause"));
            IllegalStateException mismatch=assertThrows(IllegalStateException.class,
                    ()->queue.enqueue(ctx,cmd(pair,"same_op","resume")));
            assertEquals("SKETCH_REPLAY_OPERATION_CONFLICT",mismatch.getMessage());
            assertThrows(IllegalArgumentException.class,
                    ()->queue.enqueue(ctx,cmd(pair,"rate_one","speed").put("speed",.2)));
            assertThrows(IllegalArgumentException.class,
                    ()->queue.enqueue(ctx,cmd(pair,"rate_two","pause").put("speed",1)));
            assertThrows(IllegalArgumentException.class,
                    ()->queue.enqueue(ctx,cmd(pair,"rate_three","speed").put("speed","0.25")));
            assertThrows(IllegalArgumentException.class,
                    ()->queue.enqueue(ctx,cmd(new String[]{pair[0],"not_a_real_session"},"ghost","pause")));
            assertEquals("queued",queue.status(receiptQuery(pair,"same_op")).getString("state"));
            assertThrows(IllegalArgumentException.class,
                    ()->queue.status(cmd(pair,"same_op","pause")));
            assertThrows(IllegalArgumentException.class,
                    ()->queue.status(receiptQuery(pair,"same_op").put("gallery",true)));
        }
    }

    @Test public void capabilitiesDescribeViewerControlsRatherThanFakeArtOrGalleryOperations()throws Exception{
        JSONObject c=SketchLiveViewerCommands.capabilities();
        assertEquals("4.10.40",c.getString("minimumNativeVersion"));
        assertTrue(c.getBoolean("appliedOnlyByVisibleSketchStudio"));
        assertTrue(c.getBoolean("sourceStrokeJournalRequired"));
        assertTrue(c.getBoolean("noSketchRevisionWrites"));
        assertTrue(c.getBoolean("noGalleryAccess"));
        assertFalse(c.has("gallery"));
        assertEquals(4,c.getJSONArray("commands").length());
    }
}
