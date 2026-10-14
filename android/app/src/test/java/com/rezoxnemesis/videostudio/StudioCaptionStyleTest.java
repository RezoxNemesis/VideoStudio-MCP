package com.rezoxnemesis.videostudio;

import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioCaptionStyleTest {
    private JSONObject title()throws Exception{return new JSONObject().put("id","caption").put("type","title").put("text","Hello\nWorld").put("durationMs",1500);}
    @Test public void oldCaptionsRetainTheirExistingAppearance()throws Exception{
        StudioLayer legacy=StudioLayer.fromJson(title());JSONObject restored=legacy.copy().toJson();
        assertEquals(32,restored.getInt("fontSize"));assertEquals("sans-serif",restored.getString("fontFamily"));
        assertEquals("bold",restored.getString("fontStyle"));assertEquals("left",restored.getString("textAlign"));
        assertEquals(0,restored.getInt("backgroundColor"));
        SpannableString text=new StudioTimedTitleOverlay(StudioLayer.fromJson(restored)).getText(0);
        assertEquals(Layout.Alignment.ALIGN_NORMAL,text.getSpans(0,text.length(),AlignmentSpan.class)[0].getAlignment());
    }
    @Test public void savedStyleProducesActualTimedOverlaySpans()throws Exception{
        StudioLayer layer=StudioLayer.fromJson(title().put("fontSize",48).put("fontFamily","serif")
                .put("fontStyle","bold_italic").put("textAlign","right").put("backgroundColor",0x88334455));
        JSONObject copy=layer.copy().toJson();assertEquals(48,copy.getInt("fontSize"));assertEquals("serif",copy.getString("fontFamily"));
        StudioTimedTitleOverlay overlay=new StudioTimedTitleOverlay(layer);SpannableString text=overlay.getText(0);
        assertEquals("Hello\nWorld",text.toString());
        assertEquals(48,text.getSpans(0,text.length(),AbsoluteSizeSpan.class)[0].getSize());
        assertEquals("serif",text.getSpans(0,text.length(),TypefaceSpan.class)[0].getFamily());
        assertEquals(Typeface.BOLD_ITALIC,text.getSpans(0,text.length(),StyleSpan.class)[0].getStyle());
        assertEquals(Layout.Alignment.ALIGN_OPPOSITE,text.getSpans(0,text.length(),AlignmentSpan.class)[0].getAlignment());
        assertEquals(0x88334455,text.getSpans(0,text.length(),BackgroundColorSpan.class)[0].getBackgroundColor());
        assertEquals(1,overlay.getOverlaySettings(1499999).getAlphaScale(),0);
        assertEquals(0,overlay.getOverlaySettings(1500000).getAlphaScale(),0);
    }
    @Test public void malformedStylesCannotBecomeDefaultsOrCrossIntoOtherLayerTypes()throws Exception{
        for(Object size:new Object[]{11,97,12.5,"48",JSONObject.NULL})
            assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("fontSize",size)));
        for(String key:new String[]{"fontFamily","fontStyle","textAlign"})
            for(Object value:new Object[]{"invalid",1,JSONObject.NULL})
                assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put(key,value)));
        for(Object color:new Object[]{"#ffffff",.5,4294967296L,JSONObject.NULL})
            assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(title().put("backgroundColor",color)));
        assertThrows(IllegalArgumentException.class,()->StudioLayer.fromJson(new JSONObject().put("type","audio").put("assetId","sound").put("fontSize",32)));
    }
    @Test public void transparentBackgroundAndNormalWeightHaveNoHiddenStyle()throws Exception{
        StudioLayer layer=StudioLayer.fromJson(title().put("fontStyle","normal").put("textAlign","left"));
        SpannableString text=new StudioTimedTitleOverlay(layer).getText(0);
        assertEquals(Typeface.NORMAL,text.getSpans(0,text.length(),StyleSpan.class)[0].getStyle());
        assertEquals(0,text.getSpans(0,text.length(),BackgroundColorSpan.class).length);
        assertEquals(Layout.Alignment.ALIGN_NORMAL,text.getSpans(0,text.length(),AlignmentSpan.class)[0].getAlignment());
    }
    @Test public void explicitCenterSurvivesRoundTripAndCentersMultilineText()throws Exception{
        StudioLayer layer=StudioLayer.fromJson(title().put("textAlign","center")).copy();
        assertEquals("center",layer.toJson().getString("textAlign"));
        SpannableString text=new StudioTimedTitleOverlay(layer).getText(0);
        assertEquals(Layout.Alignment.ALIGN_CENTER,text.getSpans(0,text.length(),AlignmentSpan.class)[0].getAlignment());
    }
}
