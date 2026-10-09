package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class StudioEditorUiTest {
    @Test public void imagePreviewPlanDoesNotRequireFinalRender() {
        ProjectStore.Asset image = new ProjectStore.Asset(); image.id="image"; image.uri="file:///picked.png"; image.mime="image/png";
        assertEquals("image", StudioPreviewMonitor.sourceKind(image));
        image.mime="video/mp4"; assertEquals("video", StudioPreviewMonitor.sourceKind(image));
        image.mime="audio/wav"; assertEquals("audio", StudioPreviewMonitor.sourceKind(image));
    }

    @Test public void boundedImageDecodeReturnsVisiblePixels() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(context.getCacheDir(), "preview-source.png");
        Bitmap original = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888);
        original.eraseColor(android.graphics.Color.CYAN);
        try (FileOutputStream out = new FileOutputStream(source)) { original.compress(Bitmap.CompressFormat.PNG, 100, out); }
        original.recycle();
        Bitmap decoded = StudioPreviewMonitor.decodeImage(context, Uri.fromFile(source), 640);
        assertNotNull(decoded); assertTrue(decoded.getWidth() <= 640); assertTrue(decoded.getHeight() <= 640);
        assertEquals(android.graphics.Color.CYAN, decoded.getPixel(decoded.getWidth()/2, decoded.getHeight()/2));
        decoded.recycle();
    }

    @Test public void rulerCoordinatesMapToTimelineMilliseconds() {
        Context context = RuntimeEnvironment.getApplication();
        StudioTimelineView view = new StudioTimelineView(context);
        view.setPixelsPerSecond(100);
        assertEquals(2500, view.timeAt(view.headerWidth() + 250));
        assertEquals(0, view.timeAt(0));
    }

    @Test public void timelineHasSeparateRowsAndRetainsSelection() {
        Context context = RuntimeEnvironment.getApplication();
        ProjectStore.Project p = new ProjectStore.Project(); p.id = "p";
        p.ensureTimelineDefaults(); ProjectStore.Track overlay = new ProjectStore.Track(); overlay.id="overlay"; p.tracks.add(overlay);
        StudioTimelineView view = new StudioTimelineView(context); view.setProject(p, "clip-a");
        assertEquals(2, view.trackCount()); assertEquals("clip-a", view.selectedClipId());
        view.setPlayhead(4_294_967_500L); assertEquals(4_294_967_500L, view.playhead());
    }
}
