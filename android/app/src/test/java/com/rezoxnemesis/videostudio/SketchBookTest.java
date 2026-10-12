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
public class SketchBookTest {
    @Test public void thousandPageBookAllocatesOnlyOpenedPagesAndReopensSameDrawing() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();JSONObject book=SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","create").put("name","Portrait and landscape film").put("pageCount",1000).put("fps",24));
        assertEquals(1000,book.getJSONObject("book").getInt("pageCount"));assertEquals(0,book.getJSONObject("book").getInt("createdPages"));
        JSONObject page=SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",999));String sketch=page.getString("sketchId");
        assertEquals(sketch,SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",999)).getString("sketchId"));
        JSONObject fresh=SketchEditOps.execute(c,"sketch_book_get",new JSONObject().put("bookId",id));assertEquals(1,fresh.getJSONObject("book").getInt("createdPages"));
        try(SketchProjectStore s=new SketchProjectStore(c)){assertEquals(1,s.get(sketch).getInt("frameCount"));}
        try{SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",1000));fail("Out-of-book page allocated");}catch(IllegalArgumentException expected){}
    }
}
