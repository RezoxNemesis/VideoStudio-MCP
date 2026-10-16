package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public final class SketchFrameStreamEncoderTest {
    @Test public void crimsonRoseCanvasRoutesToStreamingRatherThanMedia3PhotoSequence() {
        assertTrue(SketchFrameStreamEncoder.recommended(1200, 1500, 246));
        assertTrue("Even one large sketch used to fail exact source raster",
                SketchFrameStreamEncoder.recommended(1200, 1500, 1));
        assertTrue(SketchFrameStreamEncoder.recommended(720, 1280, 246));
        assertFalse("Existing short 720x1280 films retain the known Media3 path",
                SketchFrameStreamEncoder.recommended(720, 1280, 24));
        assertFalse(SketchFrameStreamEncoder.recommended(1280, 720, 100));
    }

    @Test public void fourByFiveSourceKeepsRatioWithinHalfPercentAndSafeCodecBudget() {
        SketchFrameStreamEncoder.Size size =
                SketchFrameStreamEncoder.boundedSize(1200, 1500, "720p");
        assertEquals(0, size.width % 16);
        assertEquals(0, size.height % 16);
        assertTrue(size.width <= 1200 && size.height <= 1500);
        assertTrue(size.width <= SketchFrameStreamEncoder.SKETCH_SAFE_EDGE);
        assertTrue(size.height <= SketchFrameStreamEncoder.SKETCH_SAFE_EDGE);
        assertTrue((long) size.width * size.height <= SketchFrameStreamEncoder.SKETCH_SAFE_PIXELS);
        assertEquals("Preserve 4:5 without stretching the rose or face",
                .8, size.width / (double) size.height, .004);
    }

    @Test public void squareAndLandscapeDrawingsStayInSafeAspect() {
        for (int[] canvas : new int[][]{{2048, 2048}, {1280, 720}, {720, 1280}}) {
            SketchFrameStreamEncoder.Size size =
                    SketchFrameStreamEncoder.boundedSize(canvas[0], canvas[1], "720p");
            assertTrue(size.width > 0 && size.height > 0);
            assertEquals(0, size.width % 16);
            assertEquals(0, size.height % 16);
            assertTrue((long) size.width * size.height <= SketchFrameStreamEncoder.SKETCH_SAFE_PIXELS);
            assertEquals(canvas[0] / (double) canvas[1], size.width / (double) size.height,
                    canvas[0] / (double) canvas[1] * .005);
        }
    }

    @Test public void higherQualityMayIncreaseBudgetButNeverUpscalesSketch() {
        SketchFrameStreamEncoder.Size small =
                SketchFrameStreamEncoder.boundedSize(1200, 1500, "720p");
        SketchFrameStreamEncoder.Size large =
                SketchFrameStreamEncoder.boundedSize(1200, 1500, "1080p");
        assertTrue((long) large.width * large.height >= (long) small.width * small.height);
        assertTrue(large.width <= 1200 && large.height <= 1500);
        assertEquals(.8, large.width / (double) large.height, .004);
    }

    @Test public void rejectsInvalidCanvasesBeforeCodecAllocation() {
        for (int[] invalid : new int[][]{{0, 1500}, {1200, 1501}, {4096, 4096}, {254, 254}}) {
            try {
                SketchFrameStreamEncoder.boundedSize(invalid[0], invalid[1], "720p");
                fail("Expected invalid dimensions to be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("canvas"));
            }
        }
    }

    @Test public void twentyFourFpsAuthoredClockHasExactTenAndQuarterSecondDuration() {
        assertEquals(10_250_000L, NativeFrameClock.presentationTimeUs(246, 24));
        assertEquals(10_208_333L, NativeFrameClock.presentationTimeUs(245, 24));
    }
}
