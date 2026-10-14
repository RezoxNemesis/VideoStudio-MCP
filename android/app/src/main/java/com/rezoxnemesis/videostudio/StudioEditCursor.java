package com.rezoxnemesis.videostudio;

/** Exact non-destructive source/timeline mapping for playhead editing. */
public final class StudioEditCursor {
    private StudioEditCursor(){}

    /** The closed out boundary is useful for trimming; split validates separately. */
    public static long sourceAt(long inMs,long outMs,double speed,long clipStartMs,long playheadMs){
        if(inMs<0||outMs<=inMs||!Double.isFinite(speed)||speed<=0)
            throw new IllegalArgumentException("Invalid clip timing");
        long duration=Math.max(1,(long)((outMs-inMs)/speed));
        if(playheadMs<clipStartMs||playheadMs-clipStartMs>duration)
            throw new IllegalArgumentException("Move the playhead inside the selected clip");
        if(playheadMs-clipStartMs==duration)return outMs;
        return Math.max(inMs,Math.min(outMs,inMs+Math.round((playheadMs-clipStartMs)*speed)));
    }

    public static boolean canSplit(long inMs,long outMs,double speed,long clipStartMs,long playheadMs){
        try{
            long at=sourceAt(inMs,outMs,speed,clipStartMs,playheadMs);
            // Match the transaction facade's source minimum and rendered video minimum.
            long minimum=Math.max(100,(long)Math.ceil(100*speed));
            return at-inMs>=minimum&&outMs-at>=minimum;
        }catch(IllegalArgumentException invalid){return false;}
    }

    public static long adjacentBoundary(long[] boundaries,long playheadMs,int direction){
        if(boundaries==null||boundaries.length==0)return Math.max(0,playheadMs);
        long chosen=direction<0?boundaries[0]:boundaries[boundaries.length-1];
        for(long cut:boundaries){
            if(direction<0&&cut<playheadMs)chosen=Math.max(chosen,cut);
            else if(direction>0&&cut>playheadMs)chosen=Math.min(chosen,cut);
        }
        return chosen;
    }

    public static String timecode(long positionMs){
        long at=Math.max(0,positionMs);
        return String.format(java.util.Locale.US,"%d:%02d.%03d",at/60000,(at/1000)%60,at%1000);
    }
}
