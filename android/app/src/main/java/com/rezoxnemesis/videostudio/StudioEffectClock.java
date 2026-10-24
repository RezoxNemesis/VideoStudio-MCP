package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.SpeedProviderUtil;
import androidx.media3.transformer.EditedMediaItem;

/** Renderer-only clock translation. Does not change saved keys or media timestamps. */
final class StudioEffectClock {
    private StudioEffectClock() {}

    static long localUs(long presentationUs,long itemStartUs) {
        requireOrigin(itemStartUs);
        return presentationUs<=itemStartUs?0:presentationUs-itemStartUs;
    }

    static long requireOrigin(long itemStartUs) {
        if(itemStartUs<0)throw new IllegalArgumentException("Item presentation origin must be nonnegative");
        return itemStartUs;
    }

    /** Match the constructed Media3 item, including its real minimum/rational clock and speed. */
    static long durationUs(EditedMediaItem item) {
        MediaItem.ClippingConfiguration clipping=item.mediaItem.clippingConfiguration;
        if(clipping.relativeToDefaultPosition)
            throw new IllegalArgumentException("Relative item clipping has no resolved presentation clock");
        long start=clipping.startPositionUs,end=clipping.endPositionUs;
        long span;
        if(end!=C.TIME_END_OF_SOURCE) {
            if(item.durationUs!=C.TIME_UNSET&&end>item.durationUs)
                throw new IllegalArgumentException("Item trim exceeds its known source duration");
            span=Math.subtractExact(end,start);
        } else {
            if(item.durationUs==C.TIME_UNSET)
                throw new IllegalArgumentException("Item presentation duration is unresolved; use an explicit source trim");
            span=Math.subtractExact(item.durationUs,start);
        }
        if(span<=0)throw new IllegalArgumentException("Item presentation duration must be positive");
        long duration=SpeedProviderUtil.getDurationAfterSpeedProviderApplied(item.speedProvider,span);
        if(duration<=0)throw new IllegalArgumentException("Speed-adjusted item duration must be positive");
        return duration;
    }
}
