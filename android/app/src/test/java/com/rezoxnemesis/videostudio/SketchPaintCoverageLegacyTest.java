package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Confirms the actual pre-feature coverage cause without any new API field. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintCoverageLegacyTest {
    private JSONObject request(JSONArray points)throws Exception {return new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","legacy_soft").put("points",points).put("brush",new JSONObject().put("mode","paint").put("color","#FF000000").put("sizePx",32).put("opacity",.58).put("flow",1).put("hardness",0).put("spacing",.1));}
    @Test public void existingSoftDabsReallyBuildUpTheInnerProfileAndRemainOmitted()throws Exception {
        JSONObject sweep=request(new JSONArray("[[.25,.5,1],[.5,.5,1],[.75,.5,1]]")),dot=request(new JSONArray("[[.5,.5,1]]"));
        SketchPaintBrush.Plan plan=SketchPaintBrush.plan(sweep,256,256);assertFalse(plan.stroke.getJSONObject("brush").has("coverage"));
        Bitmap a=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        try {
            SketchPaintBrush.apply(a,null,0,0,plan,null,1);SketchPaintBrush.apply(b,null,0,0,SketchPaintBrush.plan(dot,256,256),null,1);
            assertTrue("Native legacy dense flow creates the observed nearly-flat band",Color.alpha(a.getPixel(128,136))>130);
            assertTrue("Native single soft stamp has substantial feather at the identical radius",Color.alpha(b.getPixel(128,136))<80);
            assertTrue(Color.alpha(a.getPixel(128,136))-Color.alpha(b.getPixel(128,136))>=50);
            assertEquals(0,a.getPixel(128,145));
        }finally {a.recycle();b.recycle();}
    }
}
