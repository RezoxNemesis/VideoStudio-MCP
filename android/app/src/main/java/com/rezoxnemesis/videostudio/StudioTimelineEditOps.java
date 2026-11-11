package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.List;

/** Pure, testable human-editor clip manipulation; imported source bytes remain untouched. */
public final class StudioTimelineEditOps {
    private StudioTimelineEditOps(){}

    /**
     * Move the clip identified by a stable ID to a new zero-based index.
     * This is non-destructive; changes only the ordering of the project edit.
     */
    public static boolean move(List<ProjectStore.Clip> clips,String clipId,int targetIndex){
        if(clips==null||clipId==null||targetIndex<0||targetIndex>=clips.size())
            return false;
        int source=-1;
        for(int i=0;i<clips.size();i++)
            if(clipId.equals(clips.get(i).id)){source=i;break;}
        if(source<0||source==targetIndex)return false;
        ProjectStore.Clip moved=clips.remove(source);
        clips.add(targetIndex,moved);
        return true;
    }

    /** Timeline position of a clip after a move, respecting its speed/trim. */
    public static long startOf(List<ProjectStore.Clip> clips,String clipId) {
        if(clips==null||clipId==null)return 0;
        long start=0;
        for(ProjectStore.Clip clip:clips){
            if(clipId.equals(clip.id))return start;
            start+=Math.max(0,clip.outputDurationMs());
        }
        return 0;
    }

    public static List<String> order(List<ProjectStore.Clip> clips){
        ArrayList<String> ids=new ArrayList<>();
        if(clips!=null)for(ProjectStore.Clip clip:clips)ids.add(clip.id);
        return ids;
    }
}
