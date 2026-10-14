package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioVolumeEditsTest {
    @Test public void volumeOnlyKeepsBothFadeFieldsOutOfTheOperation()throws Exception{
        JSONObject set=StudioVolumeEdits.settings(70,5000,2000,2000,2000,2000);
        assertEquals(.7,set.getDouble("volume"),0);assertFalse(set.has("audioFadeInMs"));assertFalse(set.has("audioFadeOutMs"));
    }
    @Test public void unchangedAuthoredFadesCanExceedTheSplitSegmentLength()throws Exception{
        JSONObject set=StudioVolumeEdits.settings(70,500,2000,2000,2000,2000);
        assertEquals(.7,set.getDouble("volume"),0);assertEquals(1,set.length());
    }
    @Test public void editingOneFadeOnlySendsThatField()throws Exception{
        JSONObject set=StudioVolumeEdits.settings(70,5000,2000,2000,500,2000);
        assertEquals(500,set.getLong("audioFadeInMs"));assertFalse(set.has("audioFadeOutMs"));
    }
    @Test(expected=IllegalArgumentException.class) public void explicitLocalFadeReauthoringStillRequiresBothFadesToFit()throws Exception{
        StudioVolumeEdits.settings(70,500,2000,2000,300,2000);
    }
    @Test public void stereoDialogSettingsKeepUnchangedSplitFadesOutOfTheOperation()throws Exception{
        JSONObject set=StudioVolumeEdits.settings(70,500,2000,2000,2000,2000,-35,true);
        assertEquals(.7,set.getDouble("volume"),0);assertEquals(-.35,set.getDouble("audioPan"),0);assertTrue(set.getBoolean("audioMono"));
        assertEquals(3,set.length());assertFalse(set.has("audioFadeInMs"));assertFalse(set.has("audioFadeOutMs"));
        JSONObject reset=StudioVolumeEdits.settings(100,500,0,0,0,0,0,false);
        assertEquals(0,reset.getDouble("audioPan"),0);assertFalse(reset.getBoolean("audioMono"));
    }
    @Test public void stereoDialogSettingsRejectPanOutsideTheSliderContract()throws Exception{
        for(int pan:new int[]{-101,101,Integer.MIN_VALUE,Integer.MAX_VALUE}){
            try{StudioVolumeEdits.settings(70,500,0,0,0,0,pan,false);fail("Accepted pan "+pan);}catch(IllegalArgumentException expected){}
        }
    }
}
