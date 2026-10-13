package com.rezoxnemesis.videostudio;

/** Rational authored frame clock. Generated timestamps are not decoded evidence. */
public final class NativeFrameClock {
    private NativeFrameClock() {}
    public static int requireFps(int fps) {
        if(fps!=24 && fps!=30 && fps!=60)throw new IllegalArgumentException("Use 24, 30 or 60 FPS");
        return fps;
    }
    public static long presentationTimeUs(long index,int fps) {
        return scaled(index,1_000_000L,requireFps(fps),false);
    }
    public static long presentationTimeMs(long index,int fps) {
        return scaled(index,1_000L,requireFps(fps),true);
    }
    public static long frameDurationMs(long index,int fps) {
        if(index==Long.MAX_VALUE)throw new IllegalArgumentException("Frame index overflow");
        return presentationTimeMs(index+1,fps)-presentationTimeMs(index,fps);
    }
    public static long frameDurationUs(long index,int fps) {
        if(index==Long.MAX_VALUE)throw new IllegalArgumentException("Frame index overflow");
        return presentationTimeUs(index+1,fps)-presentationTimeUs(index,fps);
    }
    public static long durationMs(long frameCount,int fps) { return presentationTimeMs(frameCount,fps); }
    private static long scaled(long index,long scale,int fps,boolean round) {
        if(index<0)throw new IllegalArgumentException("Frame index must be nonnegative");
        try {
            long numerator=Math.multiplyExact(index,scale);
            long result=numerator/fps;
            return round && numerator%fps>=(fps+1)/2 ? Math.addExact(result,1) : result;
        }catch(ArithmeticException overflow){throw new IllegalArgumentException("Frame clock exceeds supported range",overflow);}
    }
}
