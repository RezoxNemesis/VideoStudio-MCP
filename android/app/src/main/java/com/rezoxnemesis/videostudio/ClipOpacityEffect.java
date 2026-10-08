package com.rezoxnemesis.videostudio;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.RgbMatrix;

/** Frame-evaluated alpha used by the program monitor and exported layers. */
@UnstableApi
public final class ClipOpacityEffect implements RgbMatrix {
    private final ProjectStore.Clip clip;
    public ClipOpacityEffect(ProjectStore.Clip clip) { this.clip = ProjectStore.Clip.fromJson(clip.toJson()); }
    @Override public float[] getMatrix(long timeUs, boolean useHdr) {
        float opacity=(float)EditorEngine.valueAt(clip,"opacity",Math.max(0,timeUs/1000),1);
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,opacity};
    }
}
