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
public class SketchFeatureFilmTest {
    @Test public void largeExposureMapsArePagedAtTheMcpBoundary()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",216000));
        for(int batch=0;batch<2;batch++){
            JSONArray edits=new JSONArray();for(int p=batch*128;p<(batch+1)*128;p++)edits.put(new JSONObject().put("page",p).put("frames",2));
            SketchEditOps.execute(c,"sketch_book_edit",new JSONObject().put("bookId",id).put("expectedRevision",batch+1).put("operationId","batch_"+batch).put("exposures",edits));
        }
        JSONObject book=SketchEditOps.execute(c,"sketch_book_get",new JSONObject().put("bookId",id).put("pageOffset",200).put("pageLimit",4)).getJSONObject("book");
        assertTrue(book.getBoolean("exposuresPaginated"));assertEquals(4,book.getJSONObject("exposures").length());assertEquals(216256,book.getInt("totalFrames"));
        assertEquals(2,book.getJSONObject("exposures").getInt("200"));
    }
    @Test public void oneHourAtEverySupportedRateHasExactEndpointsAndNoAllocatedCels()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(int fps:new int[]{24,30,60}){
            String id=UUID.randomUUID().toString();int frames=3600*fps;
            JSONObject book=SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",frames).put("fps",fps)).getJSONObject("book");
            SketchSequenceTimeline timeline=new SketchSequenceTimeline(book,0,frames);
            assertEquals(frames,timeline.frames());assertEquals(frames-1,timeline.page(frames-1));assertEquals(3_600_000_000L,NativeFrameClock.presentationTimeUs(frames,fps));assertEquals(0,book.getInt("createdPages"));
            SketchNativeExport.Prepared p=SketchBookExport.prepare(c,new JSONObject().put("bookId",id).put("operationId","hour").put("expectedRevision",1),(f,n)->{});
            assertEquals(frames,p.metadata.getInt("frameCount"));assertEquals(3_600_000_000L,p.metadata.getLong("durationUs"));assertEquals(0,p.project.assets.size());
        }
    }
    @Test public void directionCuesUseGlobalFramesAndRevisionBoundEdits()throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",86400));
        JSONObject request=new JSONObject().put("bookId",id).put("operationId","direct").put("expectedRevision",1).put("exposures",new JSONArray().put(new JSONObject().put("page",0).put("frames",3))).put("cues",new JSONArray().put(new JSONObject().put("id","clash").put("type","contact").put("startFrame",50000).put("endFrame",50002).put("label","Blade contact; verify both edges touch")));
        JSONObject result=SketchEditOps.execute(c,"sketch_book_edit",request);assertEquals(2,result.getJSONObject("book").getInt("revision"));
        assertEquals(result.toString(),SketchEditOps.execute(c,"sketch_book_edit",request).toString());
        JSONObject sheet=SketchEditOps.execute(c,"sketch_book_timeline",new JSONObject().put("bookId",id).put("expectedRevision",2).put("frameOffset",50000).put("frameLimit",4));
        assertEquals(49998,sheet.getJSONArray("frames").getJSONObject(0).getInt("page"));assertEquals(1,sheet.getJSONArray("cues").length());assertFalse(sheet.getBoolean("cuesExecuteDrawing"));assertEquals(86402,sheet.getInt("totalFrames"));
        try{SketchEditOps.execute(c,"sketch_book_timeline",new JSONObject().put("bookId",id).put("expectedRevision",1));fail();}catch(IllegalStateException expected){}
        try{SketchEditOps.execute(c,"sketch_book_edit",new JSONObject().put("bookId",id).put("expectedRevision",2).put("operationId","invalid").put("cues",new JSONArray().put(new JSONObject().put("id","bad").put("type","contact").put("startFrame",86402).put("endFrame",86403).put("label","outside"))));fail();}catch(IllegalArgumentException expected){}
        assertEquals(2,SketchEditOps.execute(c,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book").getInt("revision"));
    }
}
