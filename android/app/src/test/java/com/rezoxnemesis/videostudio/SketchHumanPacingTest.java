package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Readable native pen replay cannot fabricate points or slow authoritative saves. */
public class SketchHumanPacingTest {
    private static JSONObject stroke(int count)throws Exception{
        JSONArray pts=new JSONArray();
        for(int i=0;i<count;i++)pts.put(new JSONArray()
                .put((Object)Double.valueOf(i/(double)Math.max(1,count)))
                .put((Object)Double.valueOf(.4)).put((Object)Double.valueOf(.7)));
        return new JSONObject().put("type","add_object")
                .put("object",new JSONObject().put("kind","stroke")
                        .put("id","original-ink").put("points",pts));
    }

    @Test public void acceptedDrawingRendersVisiblyWithoutChangingSourceOperation()throws Exception{
        JSONObject op=stroke(60),before=new JSONObject(op.toString());
        int fast=SketchHumanPacing.durationMs(op,80,SketchHumanPacing.FAST);
        int readable=SketchHumanPacing.durationMs(op,80,SketchHumanPacing.READABLE);
        int cinematic=SketchHumanPacing.durationMs(op,80,SketchHumanPacing.CINEMATIC);
        assertEquals(80,fast);
        assertTrue(readable>fast);
        assertTrue(cinematic>readable);
        assertTrue(cinematic<=SketchHumanPacing.MAX_VISIBLE_MS);
        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(op));
    }

    @Test public void exactKeyframeAndNavigationNeverPretendToDrawInk()throws Exception{
        for(String type:new String[]{"set_keyframe","set_canvas","set_timing","insert_frame","delete_object","reorder_layer"}){
            JSONObject op=new JSONObject().put("type",type).put("frame",12);
            assertEquals(80,SketchHumanPacing.durationMs(op,80,SketchHumanPacing.CINEMATIC));
        }
    }

    @Test public void paintAndBezierPenUseTheirRealAcceptedPointCounts()throws Exception{
        JSONObject paint=new JSONObject().put("type","paint_stroke")
                .put("stroke",new JSONObject().put("request",
                        new JSONObject().put("points",stroke(80).getJSONObject("object").getJSONArray("points"))));
        assertTrue(SketchHumanPacing.durationMs(paint,80,SketchHumanPacing.READABLE)>80);
        JSONObject path=new JSONObject().put("type","add_object")
                .put("object",new JSONObject().put("kind","path")
                        .put("commands",new JSONArray().put(new JSONArray().put("M").put(.1).put(.2))
                                .put(new JSONArray().put("Q").put(.5).put(.4).put(.7).put(.8))));
        assertTrue(SketchHumanPacing.durationMs(path,80,SketchHumanPacing.READABLE)>80);
    }

    @Test public void invalidPacingModeAndUnboundedInputAreRejected()throws Exception{
        JSONObject op=stroke(1);
        assertThrows(IllegalArgumentException.class,()->
                SketchHumanPacing.durationMs(op,79,SketchHumanPacing.READABLE));
        assertThrows(IllegalArgumentException.class,()->
                SketchHumanPacing.durationMs(op,80,"unlimited-background-stream"));
        assertThrows(IllegalArgumentException.class,()->
                SketchHumanPacing.durationMs(op,6000,SketchHumanPacing.CINEMATIC));
    }

    @Test public void superLongStrokesHaveConstantBoundedPresentationCost()throws Exception{
        JSONObject op=stroke(12000);
        int ms=SketchHumanPacing.durationMs(op,80,SketchHumanPacing.CINEMATIC);
        assertEquals(SketchHumanPacing.MAX_VISIBLE_MS,ms);
    }
}
