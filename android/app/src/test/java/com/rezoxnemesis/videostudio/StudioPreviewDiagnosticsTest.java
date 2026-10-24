package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Reads the actual saved native receipt, with no source path leakage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioPreviewDiagnosticsTest {
    @Test public void capturesFailureChainWithoutPrivateMediaOrRenderedFrameClaims() {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Project project=new ProjectStore.Project();
        project.id="project-kinetic-test";
        project.name="Sensitive Private Title";
        project.updatedAt=12345;
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="clip1";project.clips.add(clip);
        StudioLayer audio=new StudioLayer();audio.type=StudioLayer.AUDIO;audio.assetId="audio";
        project.studioLayers.add(audio);

        RuntimeException cause=new RuntimeException("file:///private/secret.mp4");
        RuntimeException frames=new RuntimeException(
                "Error processing video frames: content://private/source",cause);
        StudioPreviewDiagnostics.failed(context,project,frames,"asynchronous_playback");
        JSONObject saved=StudioPreviewDiagnostics.last(context);
        assertEquals("failed",saved.optString("status"));
        assertEquals("video_frame_processing",saved.optString("failureCategory"));
        assertEquals(1,saved.optInt("clipCount"));
        assertEquals(1,saved.optInt("audioLayers"));
        assertFalse(saved.toString().contains("file:///"));
        assertFalse(saved.toString().contains("content://"));
        assertFalse(saved.toString().contains("Sensitive Private"));
        assertFalse(saved.toString().contains("secret.mp4"));
        assertTrue(saved.optBoolean("needsOnDeviceReproduction"));
    }

    @Test public void onlyFirstRenderedFrameCanMarkTheFailedPreviewRecovered() {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Project p=new ProjectStore.Project();
        p.id="project-other";p.updatedAt=7;
        StudioPreviewDiagnostics.failed(context,p,new IllegalStateException("Renderer failed"),"prepare");
        StudioPreviewDiagnostics.renderedFirstFrame(context,"wrong-project");
        assertEquals("failed",StudioPreviewDiagnostics.last(context).optString("status"));
        StudioPreviewDiagnostics.renderedFirstFrame(context,p.id);
        assertEquals("first_frame_recovered",StudioPreviewDiagnostics.last(context).optString("status"));
    }
}
