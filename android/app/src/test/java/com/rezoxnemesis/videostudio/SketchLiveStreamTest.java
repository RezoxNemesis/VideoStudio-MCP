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
public class SketchLiveStreamTest {
    @Test public void fastAgentBatchesRetainEveryStrokeInOrder() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));
        JSONObject begin=SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","begin").put("strokeDurationMs",400));assertTrue(begin.getBoolean("ok"));long after=begin.getLong("afterSequence");
        for(int batch=0;batch<3;batch++){JSONArray ops=new JSONArray();for(int n=0;n<4;n++)ops.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","stroke_"+batch+"_"+n).put("kind","stroke").put("points",new JSONArray("[[0.1,0.2,1],[0.7,0.8,1]]"))));SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","batch_"+batch).put("expectedRevision",batch+1).put("operations",ops));}
        JSONObject stream=SketchEditOps.execute(c,"sketch_live_events",new JSONObject().put("sketchId",id).put("afterSequence",after).put("limit",8));JSONArray events=stream.getJSONArray("events");assertEquals(3,events.length());for(int i=0;i<3;i++){JSONObject event=events.getJSONObject(i);assertEquals(4,event.getJSONArray("operations").length());assertEquals("stroke_"+i+"_0",event.getJSONArray("operations").getJSONObject(0).getJSONObject("object").getString("id"));assertTrue(event.getLong("sequence")>after);after=event.getLong("sequence");}
        assertEquals(0,SketchEditOps.execute(c,"sketch_live_events",new JSONObject().put("sketchId",id).put("afterSequence",after)).getJSONArray("events").length());
    }
    @Test public void completedPagesRemainInAnOrderedSessionLedgerEvenWhenAuthoredQuickly() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String book=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","new").put("pageCount",1000));long cursor=0;
        for(int page=0;page<3;page++){String id=SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",page)).getString("sketchId");SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","page_"+page).put("expectedRevision",1));SketchEditOps.execute(c,"sketch_live_end",new JSONObject().put("sketchId",id).put("sessionId","page_"+page).put("expectedRevision",1));}
        try(SketchProjectStore store=new SketchProjectStore(c)){for(int page=0;page<3;page++){JSONObject next=store.nextLiveSession(cursor);assertEquals(page,next.getInt("page"));assertTrue(next.getBoolean("ended"));assertEquals(book,next.getString("bookId"));assertTrue(next.getLong("sessionSequence")>cursor);cursor=next.getLong("sessionSequence");}assertNull(store.nextLiveSession(cursor));}
    }
    @Test public void pausedPhoneDoesNotLoseAcceptedStrokesWhenTheLiveJournalGrows() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","watch"));
        JSONArray points=new JSONArray();for(int i=0;i<6000;i++)points.put(new JSONArray().put((i+.123456789)/6001d).put(.456789123).put(.876543219));
        long revision=1;for(int i=0;i<64;i++){JSONObject op=i==0?new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","ink").put("kind","stroke").put("points",points)):new JSONObject().put("type","update_object").put("objectId","ink").put("patch",new JSONObject().put("points",points));SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",revision++).put("operationId","step_"+i).put("operations",new JSONArray().put(op)));}
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject events=store.liveEvents(id,0,1);assertEquals("Unplayed events must survive the 12MiB maintenance threshold",1,events.getLong("oldestSequence"));assertEquals(64,events.getLong("latestSequence"));}
    }
    @Test public void rejectedBatchNeverAppearsAsLiveDrawing() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));
        JSONObject begin=SketchEditOps.execute(c,"sketch_live_begin",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","begin"));
        try{SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","bad").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","delete_object").put("objectId","missing"))));fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,SketchEditOps.execute(c,"sketch_live_events",new JSONObject().put("sketchId",id).put("afterSequence",begin.getLong("afterSequence"))).getJSONArray("events").length());
    }
}
