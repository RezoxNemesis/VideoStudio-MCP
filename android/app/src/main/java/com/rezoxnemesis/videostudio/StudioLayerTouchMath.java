package com.rezoxnemesis.videostudio;

/** Pure timeline snapping/drag math for audio and caption tracks. */
public final class StudioLayerTouchMath {
    private StudioLayerTouchMath(){}

    public static final class Timing {
        public final long startMs,durationMs;
        Timing(long start,long duration){startMs=start;durationMs=duration;}
    }

    public static Timing move(long videoLength,long start,long duration,long delta){
        if(videoLength<100||start<0||duration<100||start+duration>videoLength)
            throw new IllegalArgumentException("Invalid source lane timing");
        long position=Math.max(0,Math.min(videoLength-duration,start+delta));
        return new Timing(position,duration);
    }
    public static Timing trimRight(long videoLength,long start,long duration,long delta){
        if(videoLength<100||start<0||duration<100||start+duration>videoLength)
            throw new IllegalArgumentException("Invalid source lane timing");
        long result=Math.max(100,Math.min(videoLength-start,duration+delta));
        return new Timing(start,result);
    }

    /** Snaps only when a nearby video-clip boundary actually exists. */
    public static long snap(long value,long[] boundaries,long toleranceMs){
        if(boundaries==null||toleranceMs<0)return value;
        long best=value,difference=toleranceMs+1;
        for(long boundary:boundaries){
            long dist=Math.abs(value-boundary);
            if(dist<difference){difference=dist;best=boundary;}
        }
        return difference<=toleranceMs?best:value;
    }
}
