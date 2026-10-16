package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchVisualReviewTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private static JSONObject rectangle(String id,String color,double x,double y,double w,double h)throws Exception{
        return SketchEditOps.completeObject(new JSONObject()
                .put("id",id).put("kind","rect").put("frame",0)
                .put("exposure",1).put("color",color).put("fill",true)
                .put("bounds",new JSONObject().put("x",x).put("y",y)
                        .put("width",w).put("height",h)));
    }
    private JSONObject document()throws Exception{
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","review_source")
                .put("width",320).put("height",480).put("frameCount",1)
                .put("background","#FFB28C6A"));
        JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        objects.put(rectangle("blue_dress","#FF214BC5",.13,.44,.76,.53))
                .put(rectangle("face","#FFE5A779",.29,.13,.40,.36))
                .put(rectangle("hair","#FF21151B",.25,.07,.54,.12))
                .put(rectangle("gold","#FFFFC870",.22,.53,.56,.07));
        SketchDocument.validate(doc);return doc;
    }
    private <T> T background(Callable<T> work)throws Exception{
        ExecutorService service=Executors.newSingleThreadExecutor();
        try {
            Future<T> result=service.submit(work);
            return result.get(30,TimeUnit.SECONDS);
        }finally{service.shutdownNow();}
    }
    @Test public void realNativeCanvasThumbnailFitsRelayWithoutExternalizationAndIsVisible()throws Exception{
        JSONObject doc=document();
        String frozen=SketchDocument.canonical(doc);
        JSONObject result=background(()->SketchVisualReview.inspect(context,doc,
                new JSONObject().put("sketchId","review_source")
                        .put("expectedRevision",1).put("frameIndex",0).put("focus","overview")));
        assertTrue(result.getBoolean("ok"));
        assertEquals("overview",result.getString("focus"));
        assertFalse(result.getBoolean("referencePhotoReceived"));
        assertFalse(result.getBoolean("referenceLikenessVerified"));
        assertTrue(result.getBoolean("requiresChatVisualComparison"));
        assertEquals(4,result.getInt("visibleObjects"));
        assertTrue(result.getInt("sampledColors")>=3);
        assertTrue("Native direct result must remain inside original 2KiB transport limit",
                result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <=RelayTransportPolicy.RESULT_BYTES);
        JSONObject relayed=RelayTransportPolicy.result("visual_review_test_command",result.toString());
        assertFalse("The image is a first-class MCP image, not a hidden 768-byte page trail",
                relayed.optBoolean("resultExternalized",false));
        JSONObject img=relayed.getJSONObject("sketchPreviewImage");
        assertEquals("image/jpeg",img.getString("mimeType"));
        byte[] encoded=Base64.decode(img.getString("base64"),Base64.NO_WRAP);
        assertEquals(SketchDocument.sha256(encoded),img.getString("sha256"));
        Bitmap bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.length);
        assertNotNull("MCP thumbnail must be an actual decoded Canvas image",bitmap);
        try{
            assertEquals(img.getInt("width"),bitmap.getWidth());
            assertEquals(img.getInt("height"),bitmap.getHeight());
            assertTrue(bitmap.getHeight()>12);
        }finally{bitmap.recycle();}
        assertEquals("Visual checkpoints are read-only",frozen,SketchDocument.canonical(doc));
    }

    @Test public void croppedFaceAndFullFrameRemainDistinctAndPinnedToSameRevision()throws Exception {
        JSONObject doc=document(),crop=new JSONObject()
                .put("x",.28).put("y",.12).put("width",.43).put("height",.38);
        JSONObject overview=background(()->SketchVisualReview.inspect(context,doc,
                new JSONObject().put("sketchId","review_source")
                        .put("frameIndex",0).put("focus","overview")));
        JSONObject face=background(()->SketchVisualReview.inspect(context,doc,
                new JSONObject().put("sketchId","review_source")
                        .put("expectedRevision",1).put("frameIndex",0)
                        .put("focus","face anatomy").put("crop",crop)));
        assertEquals(overview.getLong("revision"),face.getLong("revision"));
        assertEquals(crop.toString(),face.getJSONObject("crop").toString());
        assertNotEquals("Face crop must be an actual separately rendered region",
                overview.getJSONObject("sketchPreviewImage").getString("sha256"),
                face.getJSONObject("sketchPreviewImage").getString("sha256"));
        assertTrue(face.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2048);
    }

    @Test public void checkpointDoesNotClaimThatAWellRenderedShapeIsAGoodLikeness()throws Exception{
        JSONObject doc=SketchDocument.create(new JSONObject()
                .put("sketchId","blank_review").put("width",320).put("height",480)
                .put("frameCount",1));
        JSONObject review=background(()->SketchVisualReview.inspect(context,doc,
                new JSONObject().put("sketchId","blank_review")));
        assertEquals(0,review.getInt("visibleObjects"));
        assertFalse(review.getBoolean("referenceLikenessVerified"));
        assertTrue(review.getBoolean("requiresChatVisualComparison"));
        assertTrue(review.has("sketchPreviewImage"));
        assertFalse(RelayTransportPolicy.result("blank_review_cmd",review.toString())
                .optBoolean("resultExternalized",false));
    }

    @Test public void wrongOwnerWrongRevisionAndPhotoImportInputsAreRejected()throws Exception{
        JSONObject doc=document();String frozen=SketchDocument.canonical(doc);
        for(JSONObject arg:new JSONObject[]{
                new JSONObject().put("sketchId","different_sketch"),
                new JSONObject().put("sketchId","review_source").put("expectedRevision",5),
                new JSONObject().put("sketchId","review_source").put("frameIndex",1),
                new JSONObject().put("sketchId","review_source").put("crop",
                        new JSONObject().put("x",.95).put("y",.5).put("width",.2).put("height",.25))
        }){
            try{background(()->SketchVisualReview.inspect(context,doc,arg));fail("Unsafe visual checkpoint succeeded");}
            catch(java.util.concurrent.ExecutionException expected){assertNotNull(expected.getCause());}
        }
        assertEquals(frozen,SketchDocument.canonical(doc));
    }
}
