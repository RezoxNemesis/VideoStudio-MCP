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

/** A new automatic replay may pace strokes without changing explicit sessions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SketchHumanPacingLiveSessionTest {
    private JSONObject create(Context context)throws Exception{
        String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","create-automatic-pacing")
                .put("width",640).put("height",720).put("frameCount",24).put("fps",24));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            return store.get(id);
        }
    }

    @Test public void automaticNativeSessionRetainsFlagButDoesNotModifySketch()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        JSONObject doc=create(context);
        String id=doc.getString("id"),before=SketchDocument.canonical(doc);
        JSONObject begin=new JSONObject().put("sketchId",id)
                .put("operationId","agent-pacing-001").put("expectedRevision",doc.getLong("revision"))
                .put("strokeDurationMs",80).put("adaptiveNativePacing",true)
                .put("drawingMethod",SketchDrawingPolicy.ASSISTED);
        JSONObject staged=SketchLiveDrawing.beginQuietly(context,begin);
        assertFalse(staged.getJSONObject("notification").optBoolean("posted"));
        JSONObject baseline=SketchLiveDrawing.read(context,id,begin.getString("operationId"));
        assertTrue(baseline.getBoolean("adaptiveNativePacing"));
        assertEquals(80,baseline.getInt("strokeDurationMs"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            assertEquals(before,SketchDocument.canonical(store.get(id)));
        }
        JSONObject identical=SketchLiveDrawing.beginQuietly(context,begin);
        assertEquals(staged.getString("sessionId"),identical.getString("sessionId"));
    }

    @Test public void explicitOwnedLiveSessionCannotSilentlyOptIntoAdaptiveTiming()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        JSONObject doc=create(context);
        JSONObject forged=new JSONObject().put("sketchId",doc.getString("id"))
                .put("expectedRevision",doc.getLong("revision"))
                .put("operationId","explicit-user-001")
                .put("adaptiveNativePacing",true);
        assertThrows(Exception.class,()->SketchLiveDrawing.begin(context,forged));
    }

    @Test public void duplicateNativePacingIntentCannotChangePlaybackRules()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        JSONObject doc=create(context);
        JSONObject original=new JSONObject().put("sketchId",doc.getString("id"))
                .put("expectedRevision",doc.getLong("revision"))
                .put("operationId","native-pacing-idemp")
                .put("drawingMethod",SketchDrawingPolicy.ASSISTED)
                .put("strokeDurationMs",80)
                .put("adaptiveNativePacing",true);
        SketchLiveDrawing.beginQuietly(context,original);
        JSONObject changed=new JSONObject(original.toString()).put("adaptiveNativePacing",false);
        assertThrows(IllegalStateException.class,()->
                SketchLiveDrawing.beginQuietly(context,changed));
    }
}
