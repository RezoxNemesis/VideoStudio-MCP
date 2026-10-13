package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public final class SketchPaintManifestBoundaryTest {
    @Test public void vectorManifestRejectsPaintSnapshotPointersBeforeCreatingSketchOrImportIdentity()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString(),hash="a".repeat(64),paintHash="0".repeat(64);
        JSONObject header=new JSONObject().put("schema","videostudio.sketch.frame-ops.v1").put("sourceSha256",hash).put("targetSketchId",id).put("targetWidth",256).put("targetHeight",256).put("fps",24).put("frameCount",1).put("layers",new JSONArray());
        JSONObject object=new JSONObject().put("id","forged_paint").put("kind","paint").put("frame",0).put("exposure",1).put("paint",new JSONObject().put("file",paintHash+".json").put("sha256",paintHash).put("width",256).put("height",256).put("tileSize",256)).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1));
        String data=new JSONObject().put("header",header).put("backgroundOperations",new JSONArray())+"\n"+new JSONObject().put("frame",0).put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object)))+"\n";
        Exception rejection=assertThrows(Exception.class,()->SketchFrameManifestImporter.applyBytes(context,data.getBytes(StandardCharsets.UTF_8),hash,id,1,null));assertTrue(rejection.getMessage(),rejection.getMessage().toLowerCase(java.util.Locale.ROOT).contains("owned archive"));
        try(SketchProjectStore store=new SketchProjectStore(context)){assertThrows(Exception.class,()->store.get(id));}assertFalse(context.getSharedPreferences("sketch_manifest_import_v1",Context.MODE_PRIVATE).contains(id));
    }
}
