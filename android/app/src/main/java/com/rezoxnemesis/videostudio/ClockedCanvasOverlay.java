package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

/** Maps Media3's frame clock to the authored clip window without copying a bitmap. */
@UnstableApi
final class ClockedCanvasOverlay extends CanvasOverlay {
    private final CanvasOverlay delegate;
    private final long offsetUs,animationOffsetUs;
    private final float clockSpeed;
    ClockedCanvasOverlay(CanvasOverlay delegate,long offsetUs,float clockSpeed,long animationOffsetUs) {
        super(true);this.delegate=delegate;this.offsetUs=offsetUs;
        this.clockSpeed=Math.max(.0001f,clockSpeed);this.animationOffsetUs=animationOffsetUs;
    }
    @Override public void onDraw(Canvas canvas,long presentationTimeUs) {
        delegate.onDraw(canvas,AnimationClock.offsetTimeUs((long)((presentationTimeUs-offsetUs)/(double)clockSpeed),animationOffsetUs));
    }
}
