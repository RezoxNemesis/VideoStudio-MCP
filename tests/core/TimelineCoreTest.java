package com.rezoxnemesis.videostudio;

/** Executable JVM checks also used by the Android JUnit suite. */
public final class TimelineCoreTest {
    private static int checks;
    private static void eq(long expected, long actual) {
        checks++;
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void near(double expected, double actual) {
        checks++;
        if (Math.abs(expected - actual) > .000001) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void invalid(Runnable work) {
        checks++;
        try { work.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid timeline input was accepted");
    }
    public static void main(String[] args) {
        eq(3000, TimelineMath.duration(1000, 7000, 2));
        eq(8000, TimelineMath.duration(0, 4000, .5));
        eq(0, TimelineMath.duration(10, 10, 1));
        invalid(() -> TimelineMath.duration(-1, 10, 1));
        invalid(() -> TimelineMath.duration(100, 10, 1));
        invalid(() -> TimelineMath.duration(0, 10, 0));
        invalid(() -> TimelineMath.duration(0, 10, Double.NaN));
        eq(Long.MAX_VALUE, TimelineMath.add(Long.MAX_VALUE - 5, 10));
        eq(5_000_000_000L, TimelineMath.add(4_000_000_000L, 1_000_000_000L));
        eq(5000, TimelineMath.sourceAt(1000, 9000, 2, 2000));
        eq(9000, TimelineMath.sourceAt(1000, 9000, 2, Long.MAX_VALUE));
        eq(1000, TimelineMath.sourceAt(1000, 9000, 2, -100));
        eq(2000, TimelineMath.localAt(5000, 1000, 2));
        eq(0, TimelineMath.localAt(500, 1000, 2));
        eq(1000, TimelineMath.snap(994, new long[]{0, 1000, 3000}, 10));
        eq(994, TimelineMath.snap(994, new long[]{0, 1000}, 5));
        eq(Long.MAX_VALUE - 1, TimelineMath.snap(Long.MAX_VALUE - 1, new long[]{0}, 10));
        eq(1000, TimelineMath.frameTime(30, 30));
        eq(333, TimelineMath.frameTime(10, 30));
        eq(30, TimelineMath.frameIndex(1000, 30));
        eq(24, TimelineMath.frameIndex(1000, 24));
        eq(4_294_967_300L, TimelineMath.frameTime(128_849_019L, 30));
        long[] times = {0, 1000, 2000};
        double[] values = {0, 10, 0};
        near(5, TimelineMath.interpolate(times, values, 500, "linear"));
        near(10, TimelineMath.interpolate(times, values, 1000, "linear"));
        near(0, TimelineMath.interpolate(times, values, 9999, "linear"));
        near(0, TimelineMath.interpolate(times, values, -100, "linear"));
        near(0, TimelineMath.interpolate(times, values, 500, "hold"));
        near(2.5, TimelineMath.interpolate(times, values, 500, "ease_in"));
        near(7.5, TimelineMath.interpolate(times, values, 500, "ease_out"));
        near(5, TimelineMath.interpolate(times, values, 500, "ease_in_out"));
        invalid(() -> TimelineMath.interpolate(new long[]{100, 0}, new double[]{1, 2}, 50, "linear"));
        invalid(() -> TimelineMath.interpolate(new long[]{0, 0}, new double[]{1, 2}, 50, "linear"));
        invalid(() -> TimelineMath.interpolate(new long[]{0}, new double[]{Double.NaN}, 0, "linear"));
        System.out.println("PASS " + checks + " timeline core behavior checks");
    }
}
