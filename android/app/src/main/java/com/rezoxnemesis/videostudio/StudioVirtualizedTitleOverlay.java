package com.rezoxnemesis.videostudio;

import android.text.SpannableString;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One real GPU title texture for a NON-OVERLAPPING ordered run of native TITLE lanes.
 *
 * The existing StudioTimedTitleOverlay owns the precise text style, local time,
 * parenting, keyframe transforms and alpha. This class only chooses which title
 * belongs on the shared GPU texture at a composition timestamp.
 *
 * This reduces the number of simultaneous TextureOverlay shader samplers.
 * It does not flatten the authored timeline, change source frames or coalesce
 * titles that overlap. Preview and export use the same built Composition.
 */
public final class StudioVirtualizedTitleOverlay extends TextOverlay {
    static final int MAX_TITLES_PER_TEXTURE=64;
    private static final SpannableString EMPTY=new SpannableString(" ");
    private static final OverlaySettings INVISIBLE=new StaticOverlaySettings.Builder()
            .setAlphaScale(0f).build();

    private final List<StudioTimedTitleOverlay> titles;
    private final long[] startsUs,endsUs;

    public StudioVirtualizedTitleOverlay(List<StudioLayer> layers,List<StudioLayer> parents) {
        if(layers==null||parents==null||layers.size()!=parents.size()
                ||layers.size()<2||layers.size()>MAX_TITLES_PER_TEXTURE)
            throw new IllegalArgumentException("Provide 2..64 ordered title lanes and corresponding parents");
        ArrayList<StudioTimedTitleOverlay> snapshots=new ArrayList<>(layers.size());
        startsUs=new long[layers.size()];
        endsUs=new long[layers.size()];
        long lastEnd=-1;
        for(int i=0;i<layers.size();i++){
            StudioLayer source=layers.get(i);
            if(source==null||!StudioLayer.TITLE.equals(source.type)
                    ||source.glyphAnimation!=null)
                throw new IllegalArgumentException("Only ordinary TITLE lanes can share this shader texture");
            source.validate();
            long start=Math.multiplyExact(source.startMs,1000L);
            long end=Math.multiplyExact(source.startMs+source.durationMs,1000L);
            if(start<lastEnd)throw new IllegalArgumentException("Only strictly nonoverlapping title windows can share a texture");
            StudioLayer parent=parents.get(i);
            snapshots.add(new StudioTimedTitleOverlay(source.copy(),parent==null?null:parent.copy()));
            startsUs[i]=start;endsUs[i]=end;lastEnd=end;
        }
        titles=Collections.unmodifiableList(snapshots);
    }

    public int logicalTitleCount(){return titles.size();}

    /** Bounds are composition-time, half-open [start,end), matching the old renderer. */
    private int activeIndex(long presentationTimeUs){
        int low=0,high=startsUs.length-1;
        while(low<=high){
            int middle=(low+high)>>>1;
            if(presentationTimeUs<startsUs[middle])high=middle-1;
            else if(presentationTimeUs>=endsUs[middle])low=middle+1;
            else return middle;
        }
        return -1;
    }

    @Override public SpannableString getText(long presentationTimeUs){
        int active=activeIndex(presentationTimeUs);
        return active<0?EMPTY:titles.get(active).getText(presentationTimeUs);
    }

    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs){
        int active=activeIndex(presentationTimeUs);
        return active<0?INVISIBLE:titles.get(active).getOverlaySettings(presentationTimeUs);
    }
}
