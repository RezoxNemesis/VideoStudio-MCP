package com.rezoxnemesis.videostudio;

import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.StaticOverlaySettings;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exact time and blend arithmetic on the real Media3 OverlaySettings adapter. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StudioParentGroupTransformTest {
    @Test public void groupTranslationAndScaleAreCombinedAtExactCompositionTime()throws Exception{
        StudioLayer group=new StudioLayer();
        group.id="test-parent-1";group.type=StudioLayer.GROUP;
        group.startMs=1000;group.durationMs=2000;
        group.motionKeyframes=new JSONArray()
            .put(new JSONObject().put("timeMs",0).put("x",0).put("scale",1).put("opacity",1))
            .put(new JSONObject().put("timeMs",2000).put("x",.4).put("scale",1.5).put("opacity",.5));
        group.validate();
        OverlaySettings base=new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(.3f,-.25f)
                .setScale(1.2f,.9f).setAlphaScale(.8f).build();
        StudioParentGroupTransform parent=new StudioParentGroupTransform(group);
        OverlaySettings before=parent.apply(base,1_000_000L);
        assertEquals(.3,before.getBackgroundFrameAnchor().first,.001);
        assertEquals(-.25,before.getBackgroundFrameAnchor().second,.001);
        assertEquals(.8,before.getAlphaScale(),.001);
        OverlaySettings mid=parent.apply(base,2_000_000L);
        assertEquals(.3*1.25+.4,mid.getBackgroundFrameAnchor().first,.002);
        assertEquals(-.25*1.25,mid.getBackgroundFrameAnchor().second,.002);
        assertEquals(1.2*1.25,mid.getScale().first,.002);
        assertEquals(.8*.75,mid.getAlphaScale(),.002);
    }

    @Test public void groupSpinChangesCenterRelativeChildAndPreservesAnchor()throws Exception{
        StudioLayer parent=new StudioLayer();parent.type=StudioLayer.GROUP;
        parent.durationMs=1000;
        parent.motionKeyframes=new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("rotate",0))
                .put(new JSONObject().put("timeMs",1000).put("rotate",90));
        OverlaySettings child=new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(.4f,0)
                .setOverlayFrameAnchor(.2f,-.1f).setAlphaScale(.7f).build();
        OverlaySettings sample=new StudioParentGroupTransform(parent).apply(child,1_000_000);
        assertEquals(0,sample.getBackgroundFrameAnchor().first,.002);
        assertEquals(.4,sample.getBackgroundFrameAnchor().second,.002);
        assertEquals(.2,sample.getOverlayFrameAnchor().first,.002);
        assertEquals(-.1,sample.getOverlayFrameAnchor().second,.002);
        assertEquals(90,sample.getRotationDegrees(),.002);
        assertEquals(.7,sample.getAlphaScale(),.002);
    }

    @Test public void emptyParentKeysDoNotAlterExistingVisualSettings(){
        StudioLayer group=new StudioLayer();group.type=StudioLayer.GROUP;
        group.durationMs=4000;
        OverlaySettings child=new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(-.2f,.8f).setScale(.8f,.8f)
                .setRotationDegrees(-23).setAlphaScale(.3f).build();
        OverlaySettings result=new StudioParentGroupTransform(group).apply(child,2_000_000L);
        assertEquals(-.2,result.getBackgroundFrameAnchor().first,.001);
        assertEquals(.8,result.getBackgroundFrameAnchor().second,.001);
        assertEquals(.3,result.getAlphaScale(),.001);
        assertEquals(-23,result.getRotationDegrees(),.001);
    }
}
