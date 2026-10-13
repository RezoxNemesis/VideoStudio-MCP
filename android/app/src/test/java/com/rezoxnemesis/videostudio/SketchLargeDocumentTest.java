package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchLargeDocumentTest {
    @Test public void largeProfessionalSketchCanReopenAndUndoAcrossTheCursorWindowLimit() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));
        JSONArray points=new JSONArray();for(int n=0;n<7000;n++)points.put(new JSONArray().put((n+.123456789)/7001d).put(.456789123456789).put(.876543219876543));
        for(int stroke=0;stroke<8;stroke++)SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","stroke_"+stroke).put("expectedRevision",stroke+1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","ink_"+stroke).put("kind","stroke").put("points",points)))));
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject full=store.get(id);assertTrue(full.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024);assertEquals(8,SketchDocument.layer(full,"layer_1").getJSONArray("objects").length());}
        SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",9));
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(7,SketchDocument.layer(store.get(id),"layer_1").getJSONArray("objects").length());}
        SketchEditOps.execute(c,"sketch_redo",new JSONObject().put("sketchId",id).put("operationId","redo").put("expectedRevision",10));
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(points.toString(),SketchDocument.object(store.get(id),"ink_7").getJSONArray("points").toString());}
    }
}
