package com.rezoxnemesis.videostudio;

/** Device-side media resource and timeout decisions using 64-bit arithmetic. */
final class NativeMediaWorkBudget {
    static final long GIB=1024L*1024L*1024L;
    static final long MAX_LOCAL_MEDIA_BYTES=10L*GIB;
    static final long RESERVED_FREE_BYTES=512L*1024L*1024L;
    private static final long MIN_RENDER_MS=30L*60L*1000L;
    private static final long MAX_RENDER_MS=24L*60L*60L*1000L;
    private static final long RENDER_BYTES_PER_MINUTE=32L*1024L*1024L;
    private NativeMediaWorkBudget(){}

    /**
     * Large source files and long movies must not hit the old 30-minute
     * unconditional wall. Retain a 24-hour deadline, cancellation, checkpointing
     * and owner-controlled thermal/storage governors.
     */
    static long renderDeadlineMs(long durationMs,long inputBytes){
        if(durationMs<0||inputBytes<0)
            throw new IllegalArgumentException("Invalid source duration or size");
        long byTimeline=durationMs>=MAX_RENDER_MS/8?MAX_RENDER_MS:durationMs*8;
        long sourceMinutes=(inputBytes/RENDER_BYTES_PER_MINUTE)
                +(inputBytes%RENDER_BYTES_PER_MINUTE==0?0:1);
        long bySource=sourceMinutes>=MAX_RENDER_MS/60000L?MAX_RENDER_MS:sourceMinutes*60000L;
        return Math.min(MAX_RENDER_MS,Math.max(MIN_RENDER_MS,Math.max(byTimeline,bySource)));
    }

    static StorageBudget.Check outputPreflight(long freeBytes,long expectedOutputBytes) {
        if(expectedOutputBytes<0||expectedOutputBytes>MAX_LOCAL_MEDIA_BYTES)
            throw new IllegalArgumentException("Expected native export outside 10 GiB budget");
        return StorageBudget.check(freeBytes,expectedOutputBytes,RESERVED_FREE_BYTES);
    }
}
