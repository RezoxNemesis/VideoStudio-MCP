package com.rezoxnemesis.videostudio;

/** Overflow-safe authored clock used by split, head-extended trim and retime. */
public final class AnimationClock {
    private AnimationClock() {}
    public static long microseconds(long milliseconds){
        if(milliseconds>Long.MAX_VALUE/1000L)return Long.MAX_VALUE;
        if(milliseconds<Long.MIN_VALUE/1000L)return Long.MIN_VALUE;
        return milliseconds*1000L;
    }
    /** Signed offsets keep authored state anchored to the old source head.
     * Newly revealed output before that head holds authored state at zero. */
    public static long offsetTimeUs(long outputLocalUs,long authoredOffsetUs){
        long local=Math.max(0,outputLocalUs);
        if(authoredOffsetUs>0&&local>Long.MAX_VALUE-authoredOffsetUs)return Long.MAX_VALUE;
        return Math.max(0,local+authoredOffsetUs);
    }
    public static long authoredTimeUs(long outputLocalUs,long authoredOffsetUs,long authoredDurationUs){
        return Math.min(Math.max(1,authoredDurationUs),offsetTimeUs(outputLocalUs,authoredOffsetUs));
    }
}
