package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchLiveLargeEventTest {
    @Test public void denseGeneratedGeometryRemainsReadableAndToolPagesPreserveEveryOperation() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));JSONArray operations=new JSONArray();
        for(int n=0;n<22;n++){JSONArray commands=new JSONArray().put(new JSONArray().put("M").put(.1).put(.2));for(int k=1;k<4000;k++)commands.put(new JSONArray().put("L").put(.12345678901).put(.12345678902));JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id","region_"+n).put("kind","path").put("commands",commands));operations.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object));}
        assertTrue(operations.toString().length()>2*1024*1024);
        try(SketchProjectStore store=new SketchProjectStore(c)){
            store.mutate(id,"native_generated",1,"generated_regions",doc->{JSONArray objects=SketchDocument.layer(doc,"layer_1").getJSONArray("objects");for(int n=0;n<operations.length();n++)objects.put(operations.getJSONObject(n).getJSONObject("object"));return doc;},operations);
            assertEquals(22,store.liveEvents(id,0,4).getJSONArray("events").getJSONObject(0).getJSONArray("operations").length());
        }
        JSONObject cursor=new JSONObject().put("afterSequence",0).put("operationOffset",0);Set<String> seen=new HashSet<>();boolean more;
        do{JSONObject page=SketchEditOps.execute(c,"sketch_live_events",new JSONObject(cursor.toString()).put("sketchId",id));assertTrue("Native tool result stays inside its relay result budget",page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512*1024);JSONObject event=page.getJSONArray("events").getJSONObject(0);JSONArray objects=event.getJSONArray("operations");for(int n=0;n<objects.length();n++)assertTrue(seen.add(objects.getJSONObject(n).getJSONObject("object").getString("id")));cursor=page.getJSONObject("nextCursor");more=event.getBoolean("moreOperations");}while(more);
        assertEquals(22,seen.size());
    }
    @Test public void continuationRequiresTheSameRetainedEventAndNeverSkipsReplacementStrokes() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256));
        JSONArray ops=new JSONArray();for(int n=0;n<2;n++)ops.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","item_"+n).put("kind","ellipse").put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.2).put("height",.2))));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","marks").put("expectedRevision",1).put("operations",ops));
        JSONObject page=SketchEditOps.execute(c,"sketch_live_events",new JSONObject().put("sketchId",id).put("afterSequence",0).put("operationLimit",1));JSONObject cursor=page.getJSONObject("nextCursor");assertEquals(page.getJSONArray("events").getJSONObject(0).getLong("sequence"),cursor.getLong("eventSequence"));
        JSONObject request=new JSONObject(cursor.toString()).put("sketchId",id).put("operationLimit",1);JSONObject next=SketchEditOps.execute(c,"sketch_live_events",request);assertEquals("item_1",next.getJSONArray("events").getJSONObject(0).getJSONArray("operations").getJSONObject(0).getJSONObject("object").getString("id"));
        request.put("eventSequence",cursor.getLong("eventSequence")+1);try{SketchEditOps.execute(c,"sketch_live_events",request);fail("A replaced or trimmed event must never reuse an operation offset");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_EVENT_CURSOR_EXPIRED"));}
        request.remove("eventSequence");try{SketchEditOps.execute(c,"sketch_live_events",request);fail("An offset needs its pinned event identity");}catch(IllegalArgumentException expected){}
    }

}
