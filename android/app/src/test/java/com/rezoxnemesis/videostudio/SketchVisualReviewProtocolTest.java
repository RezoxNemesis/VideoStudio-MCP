package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real native Android dispatch and relay image-block compatibility. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchVisualReviewProtocolTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void nativeCommandAdmissionIsReadOnlyBackgroundAndAliasCompatible()throws Exception{
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("sketch_review"));
        assertTrue(NativeAgentMailboxActions.supports("sketch_review"));
        assertTrue(NativeAgentMailboxActions.supports("studio_sketch_review"));
        JSONObject capabilities=NativeWorkbenchBridge.capabilities();
        String names=capabilities.getJSONArray("canonicalActions").toString();
        assertTrue(names.contains("sketch_review"));
        assertTrue(capabilities.getJSONArray("aliases").toString().contains("studio_sketch_review"));
        JSONObject sketch=SketchCapabilities.get().getJSONObject("visualReview");
        assertEquals("sketch_review",sketch.getString("action"));
        assertFalse(sketch.getBoolean("automaticPhotographicLikenessScoring"));
    }

    @Test public void persistedRevisionPinnedReviewRemainsAnOrdinaryNativeMcpImage()throws Exception{
        String id="review_native_"+UUID.randomUUID().toString().replace("-","");
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id)
                .put("operationId","create").put("width",320).put("height",480)
                .put("frameCount",1));
        JSONArray edit=new JSONArray().put(new JSONObject().put("type","add_object")
                .put("layerId","layer_1").put("object",new JSONObject().put("id","vector_color")
                        .put("kind","rect").put("color","#FF2363CA").put("fill",true)
                        .put("bounds",new JSONObject().put("x",.1).put("y",.2)
                                .put("width",.8).put("height",.5))));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id)
                .put("operationId","create_shape").put("expectedRevision",1)
                .put("operations",edit));
        String frozen;
        try(SketchProjectStore store=new SketchProjectStore(context)){
            frozen=SketchDocument.canonical(store.get(id));
        }
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            JSONObject rendered=worker.submit(()->SketchEditOps.execute(context,"sketch_review",
                    new JSONObject().put("sketchId",id).put("expectedRevision",2)
                            .put("focus","silhouette"))).get(30,TimeUnit.SECONDS);
            assertEquals(id,rendered.getString("sketchId"));
            assertEquals(2,rendered.getLong("revision"));
            assertTrue(rendered.has("sketchPreviewImage"));
            assertEquals("image/jpeg",rendered.getJSONObject("sketchPreviewImage").getString("mimeType"));
            assertFalse(RelayTransportPolicy.result(UUID.randomUUID().toString(),rendered.toString())
                    .optBoolean("resultExternalized",false));
            try(SketchProjectStore store=new SketchProjectStore(context)){
                assertEquals("A visual MCP review may never rewrite the original sketch",
                        frozen,SketchDocument.canonical(store.get(id)));
            }
            try{
                worker.submit(()->SketchEditOps.execute(context,"sketch_review",
                        new JSONObject().put("sketchId",id).put("expectedRevision",1)))
                        .get(10,TimeUnit.SECONDS);
                fail("Stale visual analysis must not silently attach to newer drawing");
            }catch(java.util.concurrent.ExecutionException expected){
                assertTrue(expected.getCause().getMessage(),
                        expected.getCause().getMessage().contains("REVISION"));
            }
            try{
                worker.submit(()->SketchEditOps.execute(context,"sketch_review",
                        new JSONObject().put("sketchId",id)
                                .put("referenceBase64","photo-content-must-not-upload")))
                        .get(10,TimeUnit.SECONDS);
                fail("The review channel must not accept uploading the target photo");
            }catch(java.util.concurrent.ExecutionException expected){
                assertTrue(expected.getCause().getMessage().contains("field")
                        || expected.getCause().getMessage().contains("Unknown"));
            }
        }finally{worker.shutdownNow();}
    }
}
