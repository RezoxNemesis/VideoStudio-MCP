package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioTimedTitleOverlayTest {
    @Test public void subtitleAppearsOnlyInsideItsAuthoredWindow(){
        StudioLayer title=new StudioLayer();
        title.type=StudioLayer.TITLE;
        title.text="REVENGE OF ASHES";
        title.position=2;
        title.startMs=1250;title.durationMs=950;
        StudioTimedTitleOverlay overlay=new StudioTimedTitleOverlay(title);
        assertEquals(0f,overlay.getOverlaySettings(1_249_000).getAlphaScale(),0);
        assertEquals(1f,overlay.getOverlaySettings(1_250_000).getAlphaScale(),0);
        assertEquals(1f,overlay.getOverlaySettings(2_199_999).getAlphaScale(),0);
        assertEquals(0f,overlay.getOverlaySettings(2_200_000).getAlphaScale(),0);
        assertEquals("REVENGE OF ASHES",overlay.getText(1_600_000).toString());
    }
    @Test public void adjacentCaptionsDoNotNeedCutsInTheirOriginalVideo(){
        assertTrue(StudioTimedTitleOverlay.activeAt(3_000_000,3_000_000,3_500_000));
        assertFalse(StudioTimedTitleOverlay.activeAt(3_500_000,3_000_000,3_500_000));
        assertTrue(StudioTimedTitleOverlay.activeAt(3_500_000,3_500_000,4_100_000));
    }
}
