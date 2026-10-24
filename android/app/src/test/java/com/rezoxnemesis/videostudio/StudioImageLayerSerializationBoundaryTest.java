package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** A legacy static image at the public minimum must survive its own saved JSON. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioImageLayerSerializationBoundaryTest {
    @Test public void exactMinimumWidthAndHeightRoundTripWithoutMotionOrRelaxedInputBounds() throws Exception {
        StudioLayer layer=StudioLayer.fromJson(image().put("width",.01).put("height",.01));
        assertFalse(layer.toJson().has("motionKeyframes"));
        assertEquals(.01,layer.toJson().getDouble("width"),0);
        assertEquals(.01,layer.toJson().getDouble("height"),0);
        StudioLayer copied=layer.copy();
        assertEquals(Float.floatToIntBits(.01f),Float.floatToIntBits(copied.width));
        assertEquals(Float.floatToIntBits(.01f),Float.floatToIntBits(copied.height));
    }

    @Test public void valuesBelowPublicMinimumStillRejectIncludingOldFloatSerializationValue() throws Exception {
        for(String field:new String[]{"width","height"})
            for(Object value:new Object[]{Math.nextDown(.01),(double).01f,.009,"0.01",JSONObject.NULL})
                assertThrows(field+"="+value,IllegalArgumentException.class,()->StudioLayer.fromJson(image().put(field,value)));
    }

    @Test public void unrelatedGeometrySerializationIsUnchanged() throws Exception {
        StudioLayer layer=StudioLayer.fromJson(image().put("x",.3).put("y",.4).put("width",.1).put("height",.7).put("rotation",.3).put("opacity",.2));
        JSONObject json=layer.toJson();
        assertEquals((double)layer.x,json.getDouble("x"),0);assertEquals((double)layer.y,json.getDouble("y"),0);
        assertEquals((double)layer.width,json.getDouble("width"),0);assertEquals((double)layer.height,json.getDouble("height"),0);
        assertEquals((double)layer.rotation,json.getDouble("rotation"),0);assertEquals((double)layer.opacity,json.getDouble("opacity"),0);
        assertFalse(json.has("motionKeyframes"));
    }

    private static JSONObject image()throws Exception{return new JSONObject().put("id","legacy-minimum").put("type","image")
            .put("assetId","owned-image").put("durationMs",1000);}
}
