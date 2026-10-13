package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchPreviewCropTest {
    @Test public void smallCropRerasterizesVectorDetailsAtTheRequestedInspectionResolution() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256));
        JSONObject reply=SketchEditOps.execute(c,"sketch_preview",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("maxDimension",256).put("crop",new JSONObject().put("x",.5).put("y",.5).put("width",.125).put("height",.125)));
        assertEquals(32,reply.getJSONObject("sourcePixelBounds").getInt("width"));assertEquals(256,reply.getJSONObject("sketchPreviewImage").getInt("width"));assertEquals(256,reply.getJSONObject("sketchPreviewImage").getInt("height"));
    }
    @Test public void croppedPreviewKeepsExactCanvasCoordinatesAndShowsOnlyTheChosenRegion() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256));
        JSONArray ops=new JSONArray();for(int n=0;n<2;n++)ops.put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","half_"+n).put("kind","rect").put("color",n==0?"#FFFF0000":"#FF0000FF").put("fill",true).put("bounds",new JSONObject().put("x",n*.5).put("y",0).put("width",.5).put("height",1))));SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","colour").put("expectedRevision",1).put("operations",ops));
        JSONObject reply=SketchEditOps.execute(c,"sketch_preview",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("maxDimension",256).put("crop",new JSONObject().put("x",.5).put("y",0).put("width",.5).put("height",1)));
        JSONObject image=reply.getJSONObject("sketchPreviewImage"),region=reply.getJSONObject("sourcePixelBounds");assertEquals(128,region.getInt("x"));assertEquals(128,region.getInt("width"));assertEquals(256,reply.getInt("canvasWidth"));assertEquals(128,image.getInt("width"));assertEquals(256,image.getInt("height"));
        byte[] bytes=android.util.Base64.decode(image.getString("base64"),android.util.Base64.DEFAULT);Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);try{assertEquals(Color.BLUE,bitmap.getPixel(20,120));assertEquals(Color.BLUE,bitmap.getPixel(100,120));}finally{bitmap.recycle();}
        try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals("Preview is read-only",2,store.revision(id));}
    }
    @Test public void previewRejectsAnOutOfCanvasCropBeforeRendering() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));
        try{SketchEditOps.execute(c,"sketch_preview",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("crop",new JSONObject().put("x",.9).put("y",0).put("width",.2).put("height",1)));fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("fit"));}
    }
}
