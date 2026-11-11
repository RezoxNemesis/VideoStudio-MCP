package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Source-independent authoring math for multiple timed caption/audio lanes.
 * Returns deterministic output-relative title segments for Media3 exports;
 * imported video source clips and original files are never modified.
 */
public final class StudioTrackLayout {
    private StudioTrackLayout(){}

    public static final class Segment {
        public final long startMs,durationMs;
        public final ArrayList<StudioLayer> activeTitles;
        Segment(long startMs,long durationMs,List<StudioLayer> active){
            this.startMs=startMs;this.durationMs=durationMs;
            activeTitles=new ArrayList<>(active);
        }
    }

    public static List<Segment> titleSegments(long clipStartMs,long clipDurationMs,
                                               List<StudioLayer> layers){
        if(clipStartMs<0||clipDurationMs<1)
            throw new IllegalArgumentException("Clip segment clock invalid");
        TreeSet<Long> knots=new TreeSet<>();
        knots.add(0L);knots.add(clipDurationMs);
        long end=clipStartMs+clipDurationMs;
        if(layers!=null)for(StudioLayer layer:layers){
            if(layer==null||!StudioLayer.TITLE.equals(layer.type))continue;
            layer.validate();
            long a=Math.max(clipStartMs,layer.startMs);
            long b=Math.min(end,layer.startMs+layer.durationMs);
            if(b>a){
                if(a>clipStartMs && a<end)knots.add(a-clipStartMs);
                if(b>clipStartMs && b<end)knots.add(b-clipStartMs);
            }
        }
        ArrayList<Long> boundaries=new ArrayList<>(knots);
        ArrayList<Segment> result=new ArrayList<>();
        for(int i=0;i<boundaries.size()-1;i++){
            long from=boundaries.get(i),until=boundaries.get(i+1);
            if(until<=from)continue;
            long midpoint=clipStartMs+from+(until-from)/2;
            ArrayList<StudioLayer> titles=new ArrayList<>();
            if(layers!=null)for(StudioLayer l:layers)
                if(l!=null&&StudioLayer.TITLE.equals(l.type)&&
                        midpoint>=l.startMs&&midpoint<l.startMs+l.durationMs)
                    titles.add(l);
            titles.sort(Comparator.comparingLong(l->l.startMs));
            result.add(new Segment(from,until-from,titles));
        }
        return result;
    }

    public static List<StudioLayer> activeAt(List<StudioLayer> all,String type,long ms){
        if(all==null)return Collections.emptyList();
        ArrayList<StudioLayer> active=new ArrayList<>();
        for(StudioLayer layer:all)if(layer!=null&&type.equals(layer.type)
                &&ms>=layer.startMs&&ms<layer.startMs+layer.durationMs)
            active.add(layer);
        return active;
    }

    /** Media3 output uses one independent audio sequence per overlapping music lane. */
    public static List<StudioLayer> audioLayers(ProjectStore.Project project){
        if(project==null)return Collections.emptyList();
        ArrayList<StudioLayer> music=new ArrayList<>();
        for(StudioLayer l:project.studioLayers){
            if(l==null||!StudioLayer.AUDIO.equals(l.type))continue;
            l.validate();
            if(l.startMs+l.durationMs>project.outputDurationMs())
                throw new IllegalArgumentException("Audio track extends beyond the video timeline");
            ProjectStore.Asset asset=project.asset(l.assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("audio/"))
                throw new IllegalArgumentException("Music track must reference a VideoStudio-imported audio asset");
            if(asset.durationMs>0 && l.sourceInMs+l.durationMs>asset.durationMs+20)
                throw new IllegalArgumentException("Audio trim exceeds imported file duration");
            music.add(l);
        }
        if(music.size()>4)
            throw new IllegalArgumentException("Up to 4 simultaneous music lanes are supported");
        music.sort(Comparator.comparingLong(l->l.startMs));
        return music;
    }
}
