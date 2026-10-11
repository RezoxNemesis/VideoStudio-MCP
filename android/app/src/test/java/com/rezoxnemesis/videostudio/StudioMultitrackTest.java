package com.rezoxnemesis.videostudio;

import android.content.Context;
import androidx.media3.transformer.Composition;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioMultitrackTest {
    private static StudioLayer title(long start,long duration,String text){
        StudioLayer t=new StudioLayer();
        t.type=StudioLayer.TITLE;t.startMs=start;t.durationMs=duration;t.text=text;
        t.validate();return t;
    }
    private static StudioLayer song(String id,long start,long duration){
        StudioLayer t=new StudioLayer();
        t.type=StudioLayer.AUDIO;t.assetId=id;t.startMs=start;t.durationMs=duration;
        t.gain=.6f;t.validate();return t;
    }

    @Test public void captionsSplitOneVideoIntoActualTimedExportSegments() {
        List<StudioTrackLayout.Segment> cuts=StudioTrackLayout.titleSegments(
                0,4000,List.of(title(900,1200,"Sword clash")));
        assertEquals(3,cuts.size());
        assertEquals(900,cuts.get(0).durationMs);
        assertEquals(1200,cuts.get(1).durationMs);
        assertEquals(1900,cuts.get(2).durationMs);
        assertTrue(cuts.get(0).activeTitles.isEmpty());
        assertEquals("Sword clash",cuts.get(1).activeTitles.get(0).text);
        assertTrue(cuts.get(2).activeTitles.isEmpty());
    }

    @Test public void layersPersistAndRemainUndoableAsProjectData() throws Exception {
        ProjectStore.Project project=new ProjectStore.Project();
        project.id=UUID.randomUUID().toString();
        project.name="Human timeline";
        project.studioLayers.add(title(350,1900,"The End"));
        String snapshot=project.toJson().toString();
        ProjectStore.Project back=ProjectStore.Project.fromJson(
                new org.json.JSONObject(snapshot));
        assertEquals(1,back.studioLayers.size());
        assertEquals("The End",back.studioLayers.get(0).text);
        StudioEditHistory history=new StudioEditHistory();
        history.checkpoint(back);
        back.studioLayers.clear();
        assertEquals(1,history.undo(back).studioLayers.size());
    }

    @Test public void createsRealMultiSequenceMedia3CompositionForAudioAndText() throws Exception {
        Context ctx=RuntimeEnvironment.getApplication();
        ProjectStore.Project p=new ProjectStore.Project();
        p.id=UUID.randomUUID().toString();
        ProjectStore.Asset video=new ProjectStore.Asset();
        video.id=UUID.randomUUID().toString();
        video.uri="file:///data/local/tmp/video.mp4";
        video.mime="video/mp4";video.durationMs=4000;p.assets.add(video);
        ProjectStore.Asset audio=new ProjectStore.Asset();
        audio.id=UUID.randomUUID().toString();
        audio.uri="file:///data/local/tmp/music.m4a";
        audio.mime="audio/mp4";audio.durationMs=3000;p.assets.add(audio);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id=UUID.randomUUID().toString();clip.assetId=video.id;
        clip.inMs=0;clip.outMs=4000;p.clips.add(clip);
        p.studioLayers.add(title(900,1100,"Actual overlay"));
        p.studioLayers.add(song(audio.id,500,2000));
        NativeRenderEngine renderer=new NativeRenderEngine(ctx);
        Method method=NativeRenderEngine.class.getDeclaredMethod(
                "buildStudioTrackComposition",ProjectStore.Project.class,
                String.class,String.class);
        method.setAccessible(true);
        Composition result=(Composition)method.invoke(renderer,p,"9:16","720p");
        assertEquals("Base video and independently mixed music",2,result.sequences.size());
        assertEquals("Caption creates three Media3 video segments",3,
                result.sequences.get(0).editedMediaItems.size());
        assertEquals("Audio gap + actual music + trailing silence",3,
                result.sequences.get(1).editedMediaItems.size());
    }

    @Test(expected=IllegalArgumentException.class)
    public void cannotAddAudioOutsideVideoDuration() {
        ProjectStore.Project p=new ProjectStore.Project();
        p.id=UUID.randomUUID().toString();
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.inMs=0;clip.outMs=1000;p.clips.add(clip);
        StudioLayer l=song("audiotrack-id",800,1000);
        p.studioLayers.add(l);
        StudioTrackLayout.audioLayers(p);
    }

    @Test public void findsCaptionsOnOverlappingLayersWithIndependentTimestamps() {
        List<StudioLayer> items=List.of(title(100,2500,"A"),title(650,1500,"B"));
        List<StudioLayer> active=StudioTrackLayout.activeAt(items,StudioLayer.TITLE,1100);
        assertEquals(2,active.size());
        assertEquals(0,StudioTrackLayout.activeAt(items,StudioLayer.TITLE,2800).size());
    }
}
