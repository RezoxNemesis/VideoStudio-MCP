package com.rezoxnemesis.videostudio;

/** Time-safe touch trimming on phones, independent from media file mutation. */
public final class StudioTrimMath {
    private StudioTrimMath(){}
    public static long sourceLength(long assetDurationMs,ProjectStore.Clip clip){
        return Math.max(100,Math.max(assetDurationMs,clip==null?100:clip.outMs));
    }
    public static int toSeek(long sourceMs,long sourceLengthMs){
        if(sourceLengthMs<=0)return 0;
        return Math.max(0,Math.min(1000,
                (int)Math.round(sourceMs*1000.0/sourceLengthMs)));
    }
    public static long fromSeek(int progress,long sourceLengthMs){
        return Math.max(0,Math.min(sourceLengthMs,
                Math.round(Math.max(0,Math.min(1000,progress))*
                        sourceLengthMs/1000.0)));
    }
    public static boolean valid(long start,long end,long sourceDuration){
        return sourceDuration>=100&&start>=0&&end>start+99&&
                end<=sourceDuration;
    }
}
