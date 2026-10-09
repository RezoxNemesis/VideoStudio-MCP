package com.rezoxnemesis.videostudio;

/** Source/time mapping shared by editing, keyframes, preview and final render. */
public final class TimelineMath {
    private TimelineMath() {}
    public static long effectLocalMs(long timestampUs,long sequenceStartUs){
        // A clip that began before a render window has a negative origin.
        if(timestampUs<=sequenceStartUs)return 0;
        return Math.subtractExact(timestampUs,sequenceStartUs)/1000;
    }
    public static long audioLocalMs(long positionOffsetUs,long frames,int sampleRate){
        if(frames<0||sampleRate<=0)throw new IllegalArgumentException("Invalid PCM clock");
        return add(Math.max(0,positionOffsetUs)/1000,Math.round(frames*1000d/sampleRate));
    }

    public static long duration(long inMs, long outMs, double speed) {
        if (inMs < 0 || outMs < inMs) throw new IllegalArgumentException("Invalid source range");
        requireSpeed(speed);
        double value = (outMs - inMs) / speed;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value);
    }

    public static long add(long left, long right) {
        if (left < 0 || right < 0) throw new IllegalArgumentException("Timeline times must be nonnegative");
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    public static long sourceAt(long inMs, long outMs, double speed, long localMs) {
        duration(inMs, outMs, speed);
        double delta = Math.max(0L, localMs) * speed;
        if (delta >= outMs - inMs) return outMs;
        return add(inMs, Math.round(delta));
    }

    public static long localAt(long sourceMs, long inMs, double speed) {
        requireSpeed(speed);
        if (inMs < 0) throw new IllegalArgumentException("Negative source in point");
        if (sourceMs <= inMs) return 0L;
        double value = (sourceMs - inMs) / speed;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value);
    }

    public static long snap(long candidate, long[] targets, long toleranceMs) {
        long time = Math.max(0L, candidate), nearest = time, best = Math.max(0L, toleranceMs);
        if (targets == null) return time;
        for (long target : targets) {
            if (target < 0) continue;
            long distance = target >= time ? target - time : time - target;
            if (distance <= best) { nearest = target; best = distance; }
        }
        return nearest;
    }

    public static long frameTime(long frame, int fps) {
        requireFps(fps);
        if (frame < 0) throw new IllegalArgumentException("Negative frame number");
        long seconds = frame / fps;
        if (seconds > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        return add(seconds * 1000L, (frame % fps) * 1000L / fps);
    }

    public static long frameIndex(long timeMs, int fps) {
        requireFps(fps);
        if (timeMs < 0) throw new IllegalArgumentException("Negative timeline time");
        long seconds = timeMs / 1000L;
        if (seconds > Long.MAX_VALUE / fps) return Long.MAX_VALUE;
        return add(seconds * fps, (timeMs % 1000L) * fps / 1000L);
    }

    public static double interpolate(long[] times, double[] values, long time, String easing) {
        if (times == null || values == null || times.length == 0 || times.length != values.length)
            throw new IllegalArgumentException("Keyframe times and values must have equal nonzero length");
        for (int i = 0; i < times.length; i++) {
            if (times[i] < 0 || !Double.isFinite(values[i]) || (i > 0 && times[i] <= times[i - 1]))
                throw new IllegalArgumentException("Keyframes must be finite and strictly ordered");
        }
        if (time <= times[0]) return values[0];
        if (time >= times[times.length - 1]) return values[values.length - 1];
        int low = 0, high = times.length - 1;
        while (high - low > 1) {
            int mid = (low + high) >>> 1;
            if (times[mid] <= time) low = mid; else high = mid;
        }
        double t = (time - times[low]) / (double) (times[high] - times[low]);
        if ("hold".equals(easing)) t = 0;
        else if ("ease_in".equals(easing)) t *= t;
        else if ("ease_out".equals(easing)) t = 1 - (1 - t) * (1 - t);
        else if ("ease_in_out".equals(easing) || "easeInOut".equals(easing)) t = t * t * (3 - 2 * t);
        return values[low] + (values[high] - values[low]) * t;
    }

    private static void requireSpeed(double speed) {
        if (!Double.isFinite(speed) || speed <= 0) throw new IllegalArgumentException("Speed must be finite and positive");
    }
    private static void requireFps(int fps) {
        if (fps < 1 || fps > 240) throw new IllegalArgumentException("Frame rate must be between 1 and 240");
    }
}
