package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchColourTraceTest {
    private JSONObject source(Context c,Bitmap bitmap,String id) throws Exception {
        File file=new File(c.getFilesDir(),UUID.randomUUID()+".png");try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        ProjectStore projects=new ProjectStore(c);ProjectStore.Project project=projects.create("Trace fixture");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned";asset.name="Fixture";asset.mime="image/png";asset.uri=Uri.fromFile(file).toString();project.assets.add(asset);projects.save(project);
        SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));
        SketchEditOps.execute(c,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","ref").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId","owned").put("layerId","layer_1").put("objectId","ref"));
        return new JSONObject().put("file",file.getAbsolutePath()).put("sha",SketchProjectStore.fileHash(file));
    }
    @Test public void colouredClosedRegionsPreserveSilhouetteAndHoleWithoutVisibleSourceBitmap() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(bitmap);cv.drawColor(Color.BLUE);Paint p=new Paint();p.setColor(Color.RED);cv.drawRect(48,48,208,208,p);p.setColor(Color.BLUE);cv.drawRect(96,96,160,160,p);JSONObject proof=source(c,bitmap,id);bitmap.recycle();
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","update_layer").put("layerId","layer_1").put("patch",new JSONObject().put("visible",false))).put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","colours"))).put(new JSONObject().put("type","trace_reference").put("objectId","ref").put("layerId","colours").put("frame",0).put("idPrefix","region").put("mode","colour_regions").put("maxDimension",256).put("colourLevels",8));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","trace").put("expectedRevision",2).put("operations",ops));
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject doc=store.get(id);JSONArray objects=SketchDocument.layer(doc,"colours").getJSONArray("objects");assertTrue(objects.length()>=3);for(int n=0;n<objects.length();n++){assertEquals("path",objects.getJSONObject(n).getString("kind"));assertTrue(objects.getJSONObject(n).getBoolean("fill"));}try(SketchFrameRenderer renderer=new SketchFrameRenderer(c,doc)){Bitmap out=renderer.render(0);try{assertEquals(Color.BLUE,out.getPixel(20,20));assertEquals(Color.RED,out.getPixel(60,60));assertEquals("The hole must remain open",Color.BLUE,out.getPixel(128,128));}finally{out.recycle();}}}
        assertEquals(proof.getString("sha"),SketchProjectStore.fileHash(new File(proof.getString("file"))));
    }
    @Test public void colourTraceUsesReferenceCropAndMapsIntoTheDestinationLayer() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(bitmap);cv.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.BLUE);cv.drawRect(128,0,256,256,p);source(c,bitmap,id);bitmap.recycle();
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","ref").put("patch",new JSONObject().put("referenceCrop",new JSONObject().put("x",.5).put("y",0).put("width",.5).put("height",1)).put("bounds",new JSONObject().put("x",.2).put("y",.3).put("width",.4).put("height",.4)))).put(new JSONObject().put("type","update_layer").put("layerId","layer_1").put("patch",new JSONObject().put("visible",false))).put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","colours").put("transform",SketchDocument.transform().put("x",.1)))).put(new JSONObject().put("type","trace_reference").put("objectId","ref").put("layerId","colours").put("frame",0).put("idPrefix","region").put("mode","colour_regions"));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","trace").put("expectedRevision",2).put("operations",ops));
        try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer renderer=new SketchFrameRenderer(c,store.get(id))){Bitmap out=renderer.render(0);try{assertEquals(Color.BLUE,out.getPixel(100,120));assertEquals(Color.WHITE,out.getPixel(20,20));}finally{out.recycle();}}
    }
    @Test public void tinyEdgeCropShowsAndTracesTheSameLastSourcePixel() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(bitmap);cv.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.GREEN);cv.drawRect(255,0,256,256,p);source(c,bitmap,id);bitmap.recycle();
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","crop").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","ref").put("patch",new JSONObject().put("referenceCrop",new JSONObject().put("x",.999).put("y",0).put("width",.001).put("height",1))))));
        try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer renderer=new SketchFrameRenderer(c,store.get(id))){Bitmap out=renderer.render(0);try{assertEquals("Nonempty normalized crop must include its final pixel",Color.GREEN,out.getPixel(128,128));}finally{out.recycle();}}
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","update_layer").put("layerId","layer_1").put("patch",new JSONObject().put("visible",false))).put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","colours"))).put(new JSONObject().put("type","trace_reference").put("objectId","ref").put("layerId","colours").put("frame",0).put("idPrefix","region").put("mode","colour_regions"));SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","trace").put("expectedRevision",3).put("operations",ops));
        try(SketchProjectStore store=new SketchProjectStore(c);SketchFrameRenderer renderer=new SketchFrameRenderer(c,store.get(id))){Bitmap out=renderer.render(0);try{assertEquals(Color.GREEN,out.getPixel(128,128));}finally{out.recycle();}}
    }

}
