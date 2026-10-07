package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class LiveEditPlayerTest {
    @Test public void playbackStateRoundTripsPositionSelectionAndSnapshot() throws Exception {
        LivePlaybackState state=new LivePlaybackState(
                "content://video/source-1",
                4_294_967_500L,
                true,
                "snapshot-17",
                "clip-9"
        );
        LivePlaybackState restored=LivePlaybackState.fromJson(state.toJson());
        assertEquals("content://video/source-1",restored.mediaUri);
        assertEquals(4_294_967_500L,restored.positionMs);
        assertTrue(restored.playWhenReady);
        assertEquals("snapshot-17",restored.snapshotId);
        assertEquals("clip-9",restored.clipId);
    }

    @Test public void emptyPlaybackStateIsSafeAndDoesNotInventMedia() throws Exception {
        LivePlaybackState restored=LivePlaybackState.fromJson(new JSONObject());
        assertEquals("",restored.mediaUri);
        assertEquals(0L,restored.positionMs);
        assertFalse(restored.playWhenReady);
        assertEquals("",restored.snapshotId);
        assertEquals("",restored.clipId);
    }

    @Test public void stateCopyCanPreservePlaybackWhileSwitchingEditorHosts() {
        LivePlaybackState original=new LivePlaybackState(
                "file:///source.mp4",12_345L,true,"checkpoint-a","clip-a"
        );
        LivePlaybackState copy=original.copy();
        assertNotSame(original,copy);
        assertEquals(original.mediaUri,copy.mediaUri);
        assertEquals(original.positionMs,copy.positionMs);
        assertEquals(original.playWhenReady,copy.playWhenReady);
        assertEquals(original.snapshotId,copy.snapshotId);
        assertEquals(original.clipId,copy.clipId);
    }
}
