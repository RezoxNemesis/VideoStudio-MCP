package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Tests motion of actual object pixels, not just camera motion. */
public class PoseSequenceFlowTest {
    private static final int W = 96;
    private static final int H = 160;

    private static int[] image(int left) {
        int[] pixels = new int[W * H];
        Arrays.fill(pixels, 0xff0b1015);
        for (int y = 56; y < 76; y++)
            for (int x = left; x < left + 16; x++)
                pixels[y * W + x] = 0xfffff7ef;
        return pixels;
    }

    @Test public void keepsAuthoredEndpointsBitExact() {
        int[] a = image(24), b = image(34);
        PoseSequenceFlow.Pair motion = PoseSequenceFlow.analyse(a, b, W, H);
        assertArrayEquals(a, PoseSequenceFlow.between(a, b, W, H, motion, 0));
        assertArrayEquals(b, PoseSequenceFlow.between(a, b, W, H, motion, 1));
    }

    @Test public void generatesNewTemporalFramesNotAStillCameraZoom() {
        int[] a = image(24), b = image(34);
        PoseSequenceFlow.Pair motion = PoseSequenceFlow.analyse(a, b, W, H);
        int[] quarter = PoseSequenceFlow.between(a, b, W, H, motion, .25);
        int[] half = PoseSequenceFlow.between(a, b, W, H, motion, .50);
        int[] threeQuarters = PoseSequenceFlow.between(a, b, W, H, motion, .75);
        assertFalse(Arrays.equals(a, quarter));
        assertFalse(Arrays.equals(a, half));
        assertFalse(Arrays.equals(b, half));
        assertFalse(Arrays.equals(b, threeQuarters));
        assertFalse(Arrays.equals(quarter, half));
        assertFalse(Arrays.equals(half, threeQuarters));
        // Authored drawings move to the right; intermediate visible shapes
        // must occupy a position between both keyframes.
        double ca = center(a), cb = center(b), cm = center(half);
        assertTrue("expected rightward motion", cb > ca + 5);
        assertTrue("in-between not between endpoints", cm > ca && cm < cb);
    }

    @Test public void identicalBackgroundDoesNotDrift() {
        int[] a = new int[W * H];
        Arrays.fill(a, 0xff344455);
        PoseSequenceFlow.Pair motion = PoseSequenceFlow.analyse(a, a, W, H);
        assertArrayEquals(a, PoseSequenceFlow.between(a, a, W, H, motion, .5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTimeOutsideSequence() {
        int[] a = image(24), b = image(34);
        PoseSequenceFlow.between(a, b, W, H, PoseSequenceFlow.analyse(a, b, W, H), 1.25);
    }

    private static double center(int[] img) {
        double sum = 0, n = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int brightness = img[y * W + x] >>> 16 & 255;
                if (brightness >= 128) {
                    double strength = (brightness - 127) / 128.0;
                    sum += x * strength;
                    n += strength;
                }
            }
        return sum / Math.max(1, n);
    }
}
