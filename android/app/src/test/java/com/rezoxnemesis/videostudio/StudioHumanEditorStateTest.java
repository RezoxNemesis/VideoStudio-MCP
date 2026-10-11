package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class StudioHumanEditorStateTest {
    @Test public void timelineScrubRespectsSpeedAndTrimWithoutSkippingFrames(){
        ProjectStore.Clip first=new ProjectStore.Clip();
        first.id="first";first.inMs=1000;first.outMs=5000;first.speed=2;
        ProjectStore.Clip second=new ProjectStore.Clip();
        second.id="second";second.inMs=200;second.outMs=3200;second.speed=1;
        assertEquals(2000,first.outputDurationMs());
        assertEquals(0,StudioTimelineClock.locate(Arrays.asList(first,second),0).clipIndex);
        assertEquals(3000,StudioTimelineClock.locate(Arrays.asList(first,second),1000).assetTimeMs);
        StudioTimelineClock.Position p=StudioTimelineClock.locate(
                Arrays.asList(first,second),2500);
        assertEquals(1,p.clipIndex);
        assertEquals(500,p.offsetInClipMs);
        assertEquals(700,p.assetTimeMs);
        assertEquals(3199,StudioTimelineClock.locate(Arrays.asList(first,second),999999).assetTimeMs);
    }

    @Test public void anEmptyTimelineHasASafeSeekPosition(){
        assertEquals(-1,StudioTimelineClock.locate(java.util.Collections.emptyList(),5).clipIndex);
    }

    @Test public void editingToolsAreCategorizedAndSearchable(){
        for(int i=0;i<StudioToolCatalog.GROUPS.length;i++)
            assertFalse(StudioToolCatalog.group(i).isEmpty());
        assertTrue(StudioToolCatalog.find("trim").stream().anyMatch(t->"Trim".equals(t.label)));
        assertTrue(StudioToolCatalog.find("3d").stream().anyMatch(t->"3D Model Viewer".equals(t.label)));
        assertTrue(StudioToolCatalog.find("mute").stream().anyMatch(t->"Volume".equals(t.label)||
                t.description.toLowerCase().contains("mute")));
        assertTrue(StudioToolCatalog.find("effects").size()>1);
    }

    @Test public void undoRedoRestoresTimelineStateWithoutDeletingSources(){
        ProjectStore.Project p=new ProjectStore.Project();
        p.id="sample_project_123";p.name="Fight";
        ProjectStore.Asset original=new ProjectStore.Asset();
        original.id="source_id";original.name="original.png";original.uri="file:///source.png";
        p.assets.add(original);
        ProjectStore.Clip first=new ProjectStore.Clip();
        first.id="clip1";first.assetId=original.id;
        first.inMs=0;first.outMs=3000;p.clips.add(first);
        StudioEditHistory h=new StudioEditHistory();
        h.checkpoint(p);
        first.speed=.5f;
        assertTrue(h.canUndo());
        ProjectStore.Project previous=h.undo(p);
        assertEquals(1.0,previous.clips.get(0).speed,.001);
        assertEquals("original.png",previous.assets.get(0).name);
        ProjectStore.Project current=h.redo(previous);
        assertEquals(.5,current.clips.get(0).speed,.001);
        assertEquals("original.png",current.assets.get(0).name);
        h.reset("another_project");
        assertFalse(h.canUndo());
    }
}
