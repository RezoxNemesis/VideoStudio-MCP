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
public class SketchPreviewCacheTest {
    @Test public void repeatPreviewReusesExactPixelsAndAChangedRevisionCannotReturnOldArtwork() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1));
        JSONArray objects=new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","fill").put("kind","rect").put("color","#FFFF0000").put("fill",true).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","fill").put("expectedRevision",1).put("operations",objects));
        JSONObject args=new JSONObject().put("sketchId",id).put("expectedRevision",2);JSONObject first=SketchEditOps.execute(c,"sketch_preview",args),second=SketchEditOps.execute(c,"sketch_preview",args);assertTrue("Repeated unchanged preview should avoid re-encoding",second.optBoolean("previewCached"));assertEquals(first.getJSONObject("sketchPreviewImage").getString("base64"),second.getJSONObject("sketchPreviewImage").getString("base64"));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","blue").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","fill").put("patch",new JSONObject().put("color","#FF0000FF")))));
        JSONObject changed=SketchEditOps.execute(c,"sketch_preview",new JSONObject().put("sketchId",id).put("expectedRevision",3));assertFalse(changed.optBoolean("previewCached"));assertNotEquals(first.getJSONObject("sketchPreviewImage").getString("base64"),changed.getJSONObject("sketchPreviewImage").getString("base64"));assertNotEquals(first.getString("documentSha256"),changed.getString("documentSha256"));
    }
    @Test public void cachedPixelsDoNotHideChangedOwnedReferenceFiles() throws Exception {
        Context c=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888);new android.graphics.Canvas(image).drawColor(android.graphics.Color.RED);java.io.File original=new java.io.File(c.getCacheDir(),"cache-guide.png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(original)){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        ProjectStore projects=new ProjectStore(c);ProjectStore.Project project=projects.create("Cache integrity");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="guide";asset.mime="image/png";asset.name="Guide";asset.uri=android.net.Uri.fromFile(original).toString();project.assets.add(asset);projects.save(project);SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("frameCount",1));SketchEditOps.execute(c,"sketch_import_reference",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","guide").put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId","guide").put("layerId","layer_1").put("objectId","guide"));JSONObject args=new JSONObject().put("sketchId",id).put("expectedRevision",2);SketchEditOps.execute(c,"sketch_preview",args);assertTrue(SketchEditOps.execute(c,"sketch_preview",args).optBoolean("previewCached"));
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject ref=SketchDocument.object(store.get(id),"guide").getJSONObject("reference");java.io.File file=store.referenceFile(id,ref);try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(new byte[]{1,2,3});}}
        try{SketchEditOps.execute(c,"sketch_preview",args);fail("Cached source pixels cannot hide changed artwork");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SKETCH_REFERENCE_CHANGED"));}
    }

}
