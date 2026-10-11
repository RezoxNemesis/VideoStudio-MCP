package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioTrackLayoutTest {
    @Test public void storesOverlappingVideoAndAudioWithoutChangingSources() throws Exception {
        StudioTrackLayout layout=new StudioTrackLayout();
        StudioTrackLayout.Track video=layout.addTrack("video-1",StudioTrackLayout.Kind.VIDEO);
        StudioTrackLayout.Track audio=layout.addTrack("audio-1",StudioTrackLayout.Kind.AUDIO);
        video.add(new StudioTrackLayout.Item("clip-v",0,5000));
        audio.add(new StudioTrackLayout.Item("clip-a",1000,3500));
        StudioTrackLayout restored=StudioTrackLayout.fromJson(layout.toJson());
        assertEquals(2,restored.tracks().size());
        assertEquals(1000,restored.tracks().get(1).items().get(0).startMs);
    }
    @Test(expected=IllegalArgumentException.class)
    public void refusesDuplicateClipInSameTrack(){
        StudioTrackLayout.Track t=new StudioTrackLayout.Track("t",StudioTrackLayout.Kind.VIDEO);
        t.add(new StudioTrackLayout.Item("c",0,100));
        t.add(new StudioTrackLayout.Item("c",500,100));
    }
    @Test(expected=IllegalArgumentException.class)
    public void refusesNegativeTimelinePositions(){
        new StudioTrackLayout.Item("clip",-1,100);
    }
}
