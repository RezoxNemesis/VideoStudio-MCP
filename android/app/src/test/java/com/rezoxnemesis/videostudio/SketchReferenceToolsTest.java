package com.rezoxnemesis.videostudio;
import android.graphics.*;
import android.net.Uri;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchReferenceToolsTest {
    @Test public void cropAndTraceOperateOnOwnedReferenceWithoutChangingOriginal() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",1));
        File file=new File(context.getFilesDir(),UUID.randomUUID()+".png");Bitmap original=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(original);c.drawColor(Color.WHITE);Paint p=new Paint();p.setColor(Color.RED);c.drawRect(0,0,64,64,p);p.setColor(Color.BLACK);c.drawRect(80,16,110,48,p);try(FileOutputStream out=new FileOutputStream(file)){original.compress(Bitmap.CompressFormat.PNG,100,out);}original.recycle();String sourceHash=SketchProjectStore.fileHash(file);
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Reference fixture");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.mime="image/png";asset.uri=Uri.fromFile(file).toString();asset.name="Reference";project.assets.add(asset);projects.save(project);
        SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","ref").put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId","source").put("layerId","layer_1").put("objectId","image"));
        JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);}
        JSONObject image=SketchDocument.object(doc,"image");assertEquals("Reference must be fitted without stretching",.5,image.getJSONObject("bounds").getDouble("height"),.001);
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","image").put("patch",new JSONObject().put("referenceCrop",new JSONObject().put("x",.5).put("y",0).put("width",.5).put("height",1)).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))))
            .put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","trace")))
            .put(new JSONObject().put("type","trace_reference").put("objectId","image").put("layerId","trace").put("frame",0).put("threshold",.1).put("idPrefix","edge"));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","trace").put("operations",ops));
        try(SketchProjectStore store=new SketchProjectStore(context)){doc=store.get(id);}
        JSONArray traced=SketchDocument.layer(doc,"trace").getJSONArray("objects");assertTrue("Tracing must create editable geometry",traced.length()>0);assertEquals("stroke",traced.getJSONObject(0).getString("kind"));assertEquals(sourceHash,SketchProjectStore.fileHash(file));
        try(SketchFrameRenderer r=new SketchFrameRenderer(context,doc)){Bitmap b=r.render(0);try{assertEquals(Color.WHITE,b.getPixel(10,128));assertEquals(Color.BLACK,b.getPixel(128,128));}finally{b.recycle();}}
    }
}
