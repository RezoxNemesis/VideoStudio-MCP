package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioAudioPreviewEngineTest {
    @Test public void eligibleAudioSourcesHaveIndependentAccurateWindows(){
        ProjectStore.Project p=new ProjectStore.Project();
        ProjectStore.Asset imported=new ProjectStore.Asset();
        imported.id="audio_a";imported.mime="audio/mp4";
        p.assets.add(imported);
        StudioLayer music=new StudioLayer();
        music.type=StudioLayer.AUDIO;music.assetId="audio_a";
        music.startMs=1000;music.durationMs=3000;
        p.studioLayers.add(music);
        StudioLayer caption=new StudioLayer();
        caption.type=StudioLayer.TITLE;caption.text="Swords clash";
        caption.startMs=1500;caption.durationMs=1000;
        p.studioLayers.add(caption);
        assertEquals(0,StudioAudioPreviewEngine.active(p,999).size());
        assertEquals(1,StudioAudioPreviewEngine.active(p,1000).size());
        assertEquals(1,StudioAudioPreviewEngine.active(p,3999).size());
        assertEquals(0,StudioAudioPreviewEngine.active(p,4000).size());
        assertEquals(3,StudioAudioPreviewEngine.previewCapacity());
    }
    @Test public void anotherProjectAudioAssetIsNotAuditioned(){
        ProjectStore.Project p=new ProjectStore.Project();
        StudioLayer lane=new StudioLayer();
        lane.type=StudioLayer.AUDIO;lane.assetId="external";
        lane.durationMs=3000;p.studioLayers.add(lane);
        assertTrue(StudioAudioPreviewEngine.active(p,100).isEmpty());
    }
}
