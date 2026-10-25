package com.rezoxnemesis.videostudio;

import androidx.media3.common.Effect;
import androidx.media3.effect.SingleColorLut;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

/** Tests real graded LUT data, native renderer admission, and exact source safety. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioResolveColorGraphTest {
    private static JSONArray nodes(JSONObject... values){
        JSONArray j=new JSONArray();for(JSONObject o:values)j.put(o);return j;
    }

    @Test public void identityLutPreservesEveryRgbCubeCornerAndTransparencyContract()throws Exception {
        JSONArray graph=nodes(new JSONObject().put("type","primaries"));
        int[][][] lut=StudioResolveColorGraph.buildCube(graph);
        assertEquals(StudioResolveColorGraph.LUT_SIZE,lut.length);
        assertEquals(0xff000000,lut[0][0][0]);
        assertEquals(0xffffffff,lut[16][16][16]);
        assertEquals(0xffFF0000,lut[16][0][0]);
        assertEquals(0xff00FF00,lut[0][16][0]);
        assertEquals(0xff0000FF,lut[0][0][16]);
        assertEquals(0xff817F81 & 0xff000000,
                StudioResolveColorGraph.colorAt(graph,129,127,129)&0xff000000);
    }

    @Test public void primaryColorMathProducesVisibleWarmthContrastAndSaturation()throws Exception{
        int original=0xff637E9A;
        JSONArray warm=nodes(new JSONObject().put("type","primaries")
                .put("temperature",.45).put("contrast",1.2).put("saturation",1.15));
        int graded=StudioResolveColorGraph.colorAt(warm,0x63,0x7E,0x9A);
        assertNotEquals(original,graded);
        int red=(graded>>16)&255,blue=graded&255;
        int noWarm=StudioResolveColorGraph.colorAt(nodes(new JSONObject()
                .put("type","primaries").put("contrast",1.2).put("saturation",1.15)),
                0x63,0x7E,0x9A);
        assertTrue(red>((noWarm>>16)&255));
        assertTrue(blue<(noWarm&255));
    }

    @Test public void rgbWheelsAndChannelCurveAreActuallyApplied()throws Exception{
        JSONArray curve=nodes(new JSONObject().put("type","curve").put("channel","red")
                .put("points",new JSONArray()
                        .put(new JSONArray().put(0).put(0))
                        .put(new JSONArray().put(.5).put(.8))
                        .put(new JSONArray().put(1).put(1))));
        int redOnly=StudioResolveColorGraph.colorAt(curve,127,127,127);
        assertTrue(((redOnly>>16)&255)>175);
        assertEquals(127,(redOnly>>8)&255);
        assertEquals(127,redOnly&255);
        JSONArray wheels=nodes(new JSONObject().put("type","wheels")
                .put("lift",new JSONArray().put(.1).put(0).put(0))
                .put("gamma",new JSONArray().put(1).put(1).put(1))
                .put("gain",new JSONArray().put(1).put(1).put(1)));
        int brightRed=StudioResolveColorGraph.colorAt(wheels,100,100,100);
        assertTrue(((brightRed>>16)&255)>((brightRed>>8)&255));
    }

    @Test public void hueQualifierDistinguishesTheChosenHue()throws Exception{
        JSONArray qualified=nodes(new JSONObject().put("type","hue_qualifier")
                .put("centerHue",0).put("width",20).put("softness",5)
                .put("saturation",0).put("gain",1));
        int red=StudioResolveColorGraph.colorAt(qualified,255,0,0);
        int blue=StudioResolveColorGraph.colorAt(qualified,0,0,255);
        assertTrue("Red-only hue qualifier turns red into gray",
                Math.abs(((red>>16)&255)-(red&255))<=2);
        assertEquals(0xff0000ff,blue);
    }

    @Test public void nativeMedia3PreviewUsesTheSameSerialLutAsFinalExport()throws Exception{
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="test-clip";
        clip.inMs=0;clip.outMs=4000;
        clip.effects=new JSONObject().put("resolveColorNodes",StudioResolveColorEdits.preset("cinematic"));
        NativeRenderEngine engine=new NativeRenderEngine(
                org.robolectric.RuntimeEnvironment.getApplication());
        List<Effect> effects=engine.buildPreviewEffects(clip,"9:16","720p");
        assertTrue("Real 3D LUT must be admitted to NativeRenderEngine effect graph",
                effects.stream().anyMatch(effect->effect instanceof SingleColorLut));
    }

    @Test public void gradeNodesFailClosedInsteadOfSilentlySkippingUnknownFields()throws Exception{
        assertThrows(IllegalArgumentException.class,()->
                StudioResolveColorGraph.validate(nodes(new JSONObject().put("type","primaries")
                        .put("exposure","super bright"))));
        assertThrows(IllegalArgumentException.class,()->
                StudioResolveColorGraph.validate(nodes(new JSONObject().put("type","unknown"))));
        assertThrows(IllegalArgumentException.class,()->
                StudioResolveColorGraph.validate(nodes(new JSONObject().put("type","primaries")
                        .put("externalFile","file:///private/grade.cube"))));
        assertThrows(IllegalArgumentException.class,()->
                StudioResolveColorGraph.validate(nodes(new JSONObject().put("type","curve")
                        .put("channel","red").put("points",new JSONArray()
                                .put(new JSONArray().put(.2).put(.4))
                                .put(new JSONArray().put(1).put(1)))));
        JSONArray tooMany=new JSONArray();
        for(int i=0;i<9;i++)tooMany.put(new JSONObject().put("type","filmic"));
        assertThrows(IllegalArgumentException.class,()->
                StudioResolveColorGraph.validate(tooMany));
    }
}
