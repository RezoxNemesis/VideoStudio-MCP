package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioDirectEditingTest {
    @Test public void directColorAndTransformSlidersMapToActualRendererParameters(){
        JSONObject effects=new JSONObject();
        StudioClipAdjustments.apply(effects,"brightness",135);
        StudioClipAdjustments.apply(effects,"contrast",75);
        StudioClipAdjustments.apply(effects,"saturationAdjust",145);
        StudioClipAdjustments.apply(effects,"lightnessAdjust",89);
        StudioClipAdjustments.apply(effects,"scale",133);
        StudioClipAdjustments.apply(effects,"rotate",65);
        assertEquals(.35,effects.optDouble("brightness"),.0001);
        assertEquals(-.25,effects.optDouble("contrast"),.0001);
        assertEquals(45,effects.optInt("saturationAdjust"));
        assertEquals(-11,effects.optInt("lightnessAdjust"));
        assertEquals(1.33,effects.optDouble("scale"),.0001);
        assertEquals(20,effects.optInt("rotate"));
        assertEquals(135,StudioClipAdjustments.sliderFor(effects,"brightness"));
        assertEquals(133,StudioClipAdjustments.sliderFor(effects,"scale"));
        assertEquals("20°",StudioClipAdjustments.labelFor("rotate",65));
        StudioClipAdjustments.reset(effects,false);
        assertFalse(effects.has("brightness"));
        assertTrue(effects.has("scale"));
        StudioClipAdjustments.reset(effects,true);
        assertFalse(effects.has("scale"));
    }

    @Test public void sliderBoundsCannotWriteOutOfRangeParameters(){
        JSONObject effects=new JSONObject();
        StudioClipAdjustments.apply(effects,"scale",999);
        StudioClipAdjustments.apply(effects,"rotate",-1);
        assertEquals(2.5,effects.optDouble("scale"),.0001);
        assertEquals(-45,effects.optDouble("rotate"),.0001);
    }

    @Test public void touchTrimConvertsToSourceMillisecondsAndRejectsInvalidRange(){
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.inMs=500;clip.outMs=5500;
        assertEquals(5500,StudioTrimMath.sourceLength(0,clip));
        assertEquals(500,StudioTrimMath.fromSeek(
                StudioTrimMath.toSeek(500,5000),5000),4);
        assertEquals(4750,StudioTrimMath.fromSeek(950,5000));
        assertTrue(StudioTrimMath.valid(400,3000,5000));
        assertFalse(StudioTrimMath.valid(400,6000,5000));
        assertFalse(StudioTrimMath.valid(1200,1290,5000));
    }

    @Test public void savedCaptionAndScrubClockSelectCorrectOnScreenText(){
        StudioLayer subtitle=new StudioLayer();
        subtitle.type=StudioLayer.TITLE;
        subtitle.text="Defend!";
        subtitle.startMs=950;
        subtitle.durationMs=1300;
        subtitle.position=2;subtitle.validate();
        assertEquals(1,StudioTrackLayout.activeAt(
                java.util.List.of(subtitle),StudioLayer.TITLE,1800).size());
        assertEquals(0,StudioTrackLayout.activeAt(
                java.util.List.of(subtitle),StudioLayer.TITLE,2300).size());
    }
}
