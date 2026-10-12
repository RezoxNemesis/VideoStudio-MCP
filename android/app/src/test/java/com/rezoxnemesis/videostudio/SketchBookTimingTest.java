package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchBookTimingTest {
    @Test public void celHoldsUseAnExplicitFrameClockAndBookCas() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",4).put("width",256).put("height",256));
        JSONObject updated=SketchEditOps.execute(c,"sketch_book_edit",new JSONObject().put("bookId",id).put("expectedRevision",1).put("operationId","hold").put("fps",30).put("exposures",new JSONArray().put(new JSONObject().put("page",1).put("frames",3))));assertEquals(2,updated.getJSONObject("book").getInt("revision"));
        SketchNativeExport.Prepared export=SketchBookExport.prepare(c,new JSONObject().put("bookId",id).put("expectedRevision",2).put("operationId","render"),(f,count)->assertEquals(6,count));assertEquals(6,export.project.clips.size());assertEquals(200000,export.metadata.getLong("durationUs"));assertEquals(1,export.project.clips.get(1).effects.getInt("bookPage"));assertEquals(1,export.project.clips.get(3).effects.getInt("bookPage"));assertEquals(2,export.project.clips.get(4).effects.getInt("bookPage"));
        try{SketchEditOps.execute(c,"sketch_book_edit",new JSONObject().put("bookId",id).put("expectedRevision",1).put("operationId","stale").put("fps",60));fail("Stale book edit accepted");}catch(IllegalStateException expected){}
    }
}
