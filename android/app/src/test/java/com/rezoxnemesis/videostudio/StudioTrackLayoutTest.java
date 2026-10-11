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
    @Test public void audioClipsCanOverlapAcrossSeparateLanesButNotSameLane() throws Exception {
        StudioTrackLayout layout=new StudioTrackLayout();
        StudioTrackLayout.Track a=layout.addTrack("audio-1",StudioTrackLayout.Kind.AUDIO);
        StudioTrackLayout.Track b=layout.addTrack("audio-2",StudioTrackLayout.Kind.AUDIO);
        a.add(new StudioTrackLayout.Item("occ-a","music-1",1000,0,2500,1f));
        b.add(new StudioTrackLayout.Item("occ-b","sound-2",2000,400,2200,1f));
        assertEquals(4200,layout.durationMs());
        StudioTrackLayout restored=StudioTrackLayout.fromJson(layout.toJson());
        assertEquals("sound-2",restored.track("audio-2").items().get(0).assetId);
        assertEquals(400,restored.track("audio-2").items().get(0).sourceInMs);
        assertEquals(1f,restored.track("audio-2").items().get(0).gain,0.001f);
        try{
            restored.track("audio-1").add(new StudioTrackLayout.Item(
                    "occ-3","music-3",1200,0,300,1f));
            fail("Overlapping sounds on one track should be rejected");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("overlap"));
        }
    }

    @Test public void movingAudioClipRollsBackIfNewPositionCollides() throws Exception {
        StudioTrackLayout.Track t=new StudioTrackLayout.Track(
                "sound",StudioTrackLayout.Kind.AUDIO);
        t.add(new StudioTrackLayout.Item("one","sound-1",0,0,1000,1f));
        t.add(new StudioTrackLayout.Item("two","sound-2",1500,0,1000,1f));
        try{
            t.replace("two",new StudioTrackLayout.Item(
                    "two","sound-2",500,0,1000,1f));
            fail("Should not silently overwrite earlier sound");
        }catch(IllegalArgumentException expected){
            assertEquals(1500,t.find("two").startMs);
        }
    }

    @Test(expected=IllegalArgumentException.class)
    public void refusesNegativeTimelinePositions(){
        new StudioTrackLayout.Item("clip",-1,100);
    }
}
