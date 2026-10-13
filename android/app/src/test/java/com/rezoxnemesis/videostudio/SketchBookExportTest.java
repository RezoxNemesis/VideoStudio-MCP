package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchBookExportTest {
    @Test public void thousandFrameBookWritesEveryFrameWithBoundedPageLoading() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",id).put("operationId","new").put("pageCount",1000).put("width",256).put("height",256).put("fps",30));
        final int[] count={0};SketchNativeExport.Prepared result=SketchBookExport.prepare(c,new JSONObject().put("bookId",id).put("expectedRevision",1).put("operationId","thousand"),(frame,total)->{assertEquals(1000,total);assertEquals(count[0]++,frame);});
        assertEquals(1000,count[0]);assertEquals(1000,result.project.assets.size());assertEquals(1000,result.project.clips.size());assertEquals(33333333,result.metadata.getLong("durationUs"));assertEquals(999,result.project.clips.get(999).effects.getInt("bookPage"));assertTrue(new java.io.File(Uri.parse(result.project.assets.get(999).uri).getPath()).isFile());assertEquals(0,SketchEditOps.execute(c,"sketch_book_get",new JSONObject().put("bookId",id)).getJSONObject("book").getInt("createdPages"));
    }
    @Test public void nativeBookFramesAreSequentialFrozenAndPreserveBlankPages() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String book=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","new").put("pageCount",4).put("width",256).put("height",256));String id=SketchEditOps.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",book).put("page",1)).getString("sketchId");
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","red").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","red").put("kind","rect").put("fill",true).put("color","#FFFF0000").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))))));
        JSONObject args=new JSONObject().put("bookId",book).put("expectedRevision",1).put("operationId","export");SketchNativeExport.Prepared output=SketchBookExport.prepare(c,args,(frame,count)->assertEquals(4,count));assertEquals(4,output.project.clips.size());assertEquals(166666,output.metadata.getLong("durationUs"));
        Bitmap blank=BitmapFactory.decodeFile(Uri.parse(output.project.assets.get(0).uri).getPath()),red=BitmapFactory.decodeFile(Uri.parse(output.project.assets.get(1).uri).getPath());try{assertEquals(Color.WHITE,blank.getPixel(128,128));assertEquals(Color.RED,red.getPixel(128,128));}finally{blank.recycle();red.recycle();}
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","blue").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","red").put("patch",new JSONObject().put("color","#FF0000FF")))));
        SketchNativeExport.Prepared retry=SketchBookExport.prepare(c,args,(frame,count)->{});assertEquals(output.project.id,retry.project.id);assertEquals(output.metadata.getString("documentSha256"),retry.metadata.getString("documentSha256"));
        Bitmap frozen=BitmapFactory.decodeFile(Uri.parse(retry.project.assets.get(1).uri).getPath());try{assertEquals(Color.RED,frozen.getPixel(128,128));}finally{frozen.recycle();}
        assertEquals(1,SketchEditOps.execute(c,"sketch_book_get",new JSONObject().put("bookId",book)).getJSONObject("book").getInt("createdPages"));
    }
}
