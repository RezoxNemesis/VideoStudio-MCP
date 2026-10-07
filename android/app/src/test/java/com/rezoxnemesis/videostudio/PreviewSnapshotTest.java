package com.rezoxnemesis.videostudio;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class PreviewSnapshotTest {
    @Test public void publishingNewCheckpointKeepsOlderSnapshotAddressable() {
        Context context=RuntimeEnvironment.getApplication();
        PreviewSnapshotStore store=new PreviewSnapshotStore(context);
        store.clearAll();

        PreviewSnapshotStore.Snapshot first=new PreviewSnapshotStore.Snapshot(
                "snap-1","project-a",7L,11L,"file:///preview-1.mp4",
                "720p","checkpoint","job-1",100L
        );
        PreviewSnapshotStore.Snapshot second=new PreviewSnapshotStore.Snapshot(
                "snap-2","project-a",8L,12L,"file:///preview-2.mp4",
                "1080p","final","job-2",200L
        );
        store.publish(first);
        store.publish(second);

        assertEquals("snap-2",store.latest("project-a").id);
        assertEquals("file:///preview-1.mp4",store.get("snap-1").uri);
        List<PreviewSnapshotStore.Snapshot> all=store.list("project-a");
        assertEquals(2,all.size());
        assertEquals("snap-2",all.get(0).id);
        store.clearAll();
    }

    @Test public void activePlaybackIsNotForceSwitchedByDefault() {
        assertFalse(PreviewSnapshotStore.shouldAutoSwitch(false,true));
        assertFalse(PreviewSnapshotStore.shouldAutoSwitch(true,true));
        assertTrue(PreviewSnapshotStore.shouldAutoSwitch(true,false));
    }

    @Test public void partialFilesCannotBePublishedAsPlayableSnapshots() {
        Context context=RuntimeEnvironment.getApplication();
        PreviewSnapshotStore store=new PreviewSnapshotStore(context);
        store.clearAll();
        try {
            store.publish(new PreviewSnapshotStore.Snapshot(
                    "bad","project-a",1L,1L,"file:///cache/render.mp4.partial",
                    "720p","checkpoint","job-x",1L
            ));
            fail("Partial preview was accepted");
        } catch(IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("partial"));
        } finally {
            store.clearAll();
        }
    }
}
