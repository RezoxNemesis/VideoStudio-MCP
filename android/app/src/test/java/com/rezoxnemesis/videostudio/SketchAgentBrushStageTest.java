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
 * Native MCP-to-foreground brush control admission.
 * Test proves real source-pinned UI intent, not generated painting or phone QA.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentBrushStageTest {
    private static JSONObject drawing(Context context)throws Exception {
        String id="test-brush-"+UUID.randomUUID();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","create")
                .put("frameCount",48).put("width",720).put("height",1280).put("fps",24));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            return store.get(id);
        }
    }

    private static JSONObject request(JSONObject source,JSONObject brush)throws Exception {
        return new JSONObject().put("sketchId",source.getString("id"))
                .put("operationId","brush-stage-"+UUID.randomUUID())
                .put("expectedRevision",source.getLong("revision"))
                .put("steps",new JSONArray().put(brush));
    }

    @Test public void realBrushStylingProgramIsAVisibleSourcePinnedStageAction()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        JSONObject doc=drawing(app);
        String before=SketchDocument.canonical(doc);
        JSONObject brush=new JSONObject().put("action","brush")
                .put("color","#FF77AA44").put("widthPx",6)
                .put("fill",true).put("preset","graphite")
                .put("opacity",.8).put("hardness",.4)
                .put("angle",15).put("stabilization",.35);
        JSONObject normalized=SketchAgentStageCommands.normalize(request(doc,brush),doc);
        assertEquals("brush",normalized.getJSONArray("steps").getJSONObject(0).getString("action"));
        assertEquals("#FF77AA44",normalized.getJSONArray("steps").getJSONObject(0).getString("color"));
        assertTrue(SketchAgentStageCommands.capabilities()
                .getJSONArray("availableSteps").toString().contains("brush"));
        assertTrue(SketchAgentStageCommands.capabilities().getBoolean("brushSettingRequiresVisibleWatch"));
        assertTrue(SketchAgentStageCommands.capabilities().getBoolean("brushSettingsOnly"));
        try(SketchAgentStageCommands queue=new SketchAgentStageCommands(app)){
            JSONObject accepted=queue.enqueue(app,normalized);
            assertEquals("queued",accepted.getString("state"));
            assertFalse("Queue admission is not on-screen control",accepted.getBoolean("applied"));
            assertFalse("Brush settings do not generate artwork",accepted.getBoolean("documentModified"));
        }
        try(SketchProjectStore store=new SketchProjectStore(app)){
            assertEquals(before,SketchDocument.canonical(store.get(doc.getString("id"))));
        }
    }

    @Test public void unknownColorsUnboundedBrushesAndGalleryInputsAreRejected()throws Exception{
        JSONObject doc=drawing(RuntimeEnvironment.getApplication());
        JSONArray invalid=new JSONArray()
                .put(new JSONObject().put("action","brush"))
                .put(new JSONObject().put("action","brush").put("color","#FF00GEEE"))
                .put(new JSONObject().put("action","brush").put("color",786))
                .put(new JSONObject().put("action","brush").put("widthPx",0.01))
                .put(new JSONObject().put("action","brush").put("widthPx",400))
                .put(new JSONObject().put("action","brush").put("widthPx","4"))
                .put(new JSONObject().put("action","brush").put("fill","yes"))
                .put(new JSONObject().put("action","brush").put("preset","fake_neural_brush"))
                .put(new JSONObject().put("action","brush").put("opacity",3))
                .put(new JSONObject().put("action","brush").put("hardness",-1))
                .put(new JSONObject().put("action","brush").put("angle",361))
                .put(new JSONObject().put("action","brush").put("stabilization",.99))
                .put(new JSONObject().put("action","brush").put("gallery",true))
                .put(new JSONObject().put("action","brush").put("url","content://external/gallery"));
        for(int i=0;i<invalid.length();i++){
            JSONObject suspect=invalid.getJSONObject(i);
            assertThrows("Should reject "+suspect,Exception.class,
                    ()->SketchAgentStageCommands.normalize(request(doc,suspect),doc));
        }
    }

    @Test public void multiStepHumanLikeToolBrushFrameSequenceRemainsWithinOneNativeProgram()throws Exception{
        JSONObject doc=drawing(RuntimeEnvironment.getApplication());
        JSONArray steps=new JSONArray()
                .put(new JSONObject().put("action","tool").put("tool","stroke"))
                .put(new JSONObject().put("action","brush")
                        .put("preset","ink").put("color","#FF161C2B")
                        .put("widthPx",2.0))
                .put(new JSONObject().put("action","frame").put("frameIndex",13))
                .put(new JSONObject().put("action","onion").put("enabled",true)
                        .put("range",2).put("alpha",60))
                .put(new JSONObject().put("action","camera")
                        .put("zoom",2.0).put("centerX",.5)
                        .put("centerY",.5).put("animationMs",250));
        JSONObject normalized=SketchAgentStageCommands.normalize(request(doc,steps.getJSONObject(1)),doc);
        assertEquals("brush",normalized.getJSONArray("steps").getJSONObject(0).getString("action"));
        JSONObject batch=new JSONObject().put("sketchId",doc.getString("id"))
                .put("operationId","brush-workflow-"+UUID.randomUUID())
                .put("expectedRevision",doc.getLong("revision"))
                .put("steps",steps);
        JSONObject prepared=SketchAgentStageCommands.normalize(batch,doc);
        assertEquals(5,prepared.getJSONArray("steps").length());
        assertEquals("camera",prepared.getJSONArray("steps").getJSONObject(4).getString("action"));
    }
}
