package com.rezoxnemesis.videostudio;

import java.util.List;

/**
 * Pure timeline clock mapping. The editor can scrub mixed image/video clips,
 * while a rendered MP4 remains the fidelity-approved way to preview effects.
 */
public final class StudioTimelineClock {
    private StudioTimelineClock(){}
    public static final class Position {
        public final int clipIndex;
        public final long offsetInClipMs,assetTimeMs,globalTimeMs;
        Position(int i,long elapsed,long at,long global){
            clipIndex=i;offsetInClipMs=elapsed;assetTimeMs=at;globalTimeMs=global;
        }
    }
    public static Position locate(List<ProjectStore.Clip> clips,long positionMs){
        if(clips==null||clips.isEmpty())return new Position(-1,0,0,0);
        long total=0;
        for(ProjectStore.Clip clip:clips)total+=Math.max(0,clip.outputDurationMs());
        long target=Math.max(0,Math.min(Math.max(0,total-1),positionMs));
        long cursor=0;
        for(int i=0;i<clips.size();i++){
            ProjectStore.Clip clip=clips.get(i);
            long duration=Math.max(0,clip.outputDurationMs());
            if(target<cursor+duration||i==clips.size()-1){
                long offset=Math.max(0,target-cursor);
                long original=clip.inMs+(long)(offset*Math.max(.1f,clip.speed));
                original=Math.max(clip.inMs,Math.min(original,Math.max(clip.inMs,clip.outMs-1)));
                return new Position(i,offset,original,target);
            }
            cursor+=duration;
        }
        return new Position(0,0,clips.get(0).inMs,0);
    }
}
