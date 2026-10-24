package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Controls are actual time-domain handles, not merely an animation label. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioBezierEasingTest {
    @Test public void endpointsAndSymmetryAreExact() throws Exception {
        double[] h=StudioBezierEasing.validate(new JSONArray()
                .put(.42).put(0).put(.58).put(1));
        assertEquals(0,StudioBezierEasing.evaluate(0,h),0);
        assertEquals(.5,StudioBezierEasing.evaluate(.5,h),.00001);
        assertEquals(1,StudioBezierEasing.evaluate(1,h),0);
        assertTrue(StudioBezierEasing.evaluate(.2,h)<.2);
        assertTrue(StudioBezierEasing.evaluate(.8,h)>.8);
    }

    @Test public void videoInterpolationActuallyUsesCustomCurve()throws Exception{
        JSONArray curve=StudioBezierEasing.fromText("0.22,1.0,0.36,1.0");
        JSONObject first=new JSONObject().put("timeMs",0).put("x",0)
                .put("easing","cubic_bezier").put("bezier",curve);
        JSONObject last=new JSONObject().put("timeMs",1000).put("x",1)
                .put("easing","linear");
        JSONArray frames=new JSONArray().put(first).put(last);
        StudioClipKeyframes bezier=StudioClipKeyframes.parse(frames,1000);
        assertEquals(0,bezier.at(0).x,0);
        assertEquals(1,bezier.at(1000).x,0);
        assertTrue("Cinematic curve is not merely a linear alias",bezier.at(250).x>.40);
        assertTrue(bezier.at(750).x>.75);
    }

    @Test public void nativeTitleAndImageLayersAcceptSameBezierRecipe()throws Exception{
        for(String type:new String[]{StudioLayer.TITLE,StudioLayer.IMAGE,StudioLayer.SHAPE,StudioLayer.VIDEO}){
            StudioLayer item=new StudioLayer();item.type=type;item.durationMs=1500;
            if(StudioLayer.TITLE.equals(type))item.text="Cinema";
            if(StudioLayer.IMAGE.equals(type)||StudioLayer.VIDEO.equals(type))item.assetId="owned-visual";
            if(StudioLayer.VIDEO.equals(type))item.audioMode="silent";
            item.motionKeyframes=new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("scale",.5)
                    .put("easing","cubic_bezier")
                    .put("bezier",StudioBezierEasing.fromText(StudioBezierEasing.PRESET_FILM)))
                .put(new JSONObject().put("timeMs",1500).put("scale",1));
            StudioLayer reloaded=StudioLayer.fromJson(item.toJson());
            double size=StudioLayerMotion.fromLayer(reloaded).atUs(400_000).scaleX;
            assertTrue(size>.5);
            assertTrue(size<1.00001);
            assertEquals("cubic_bezier",reloaded.motionKeyframes.getJSONObject(0).getString("easing"));
        }
    }

    @Test public void noUntrustedBezierObjectsOrIllFormedHandles() {
        assertThrows(IllegalArgumentException.class,()->
            StudioBezierEasing.fromText("0.2,0.3,not_a_number,1"));
        assertThrows(IllegalArgumentException.class,()->
            StudioBezierEasing.validate(new JSONArray().put(.5).put(0).put(.5)));
        assertThrows(IllegalArgumentException.class,()->
            StudioBezierEasing.validate(new JSONArray().put(1.1).put(0).put(.5).put(1)));
        assertThrows(IllegalArgumentException.class,()->
            StudioClipKeyframes.parse(new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("easing","cubic_bezier")),
                1000));
        assertThrows(IllegalArgumentException.class,()->
            StudioClipKeyframes.parse(new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("easing","linear")
                    .put("bezier",new JSONArray().put(.2).put(.4).put(.8).put(.9))),
                1000));
    }

    @Test public void legacyMotionWithoutBezierRemainsUnchanged()throws Exception{
        JSONArray keys=new JSONArray()
            .put(new JSONObject().put("timeMs",0).put("x",0).put("easing","ease_out"))
            .put(new JSONObject().put("timeMs",1000).put("x",1));
        StudioClipKeyframes parsed=StudioClipKeyframes.parse(keys,1000);
        assertEquals(.4375,parsed.at(250).x,.00001);
        assertEquals("ease_out",keys.getJSONObject(0).getString("easing"));
        assertFalse(keys.getJSONObject(0).has("bezier"));
    }
}
