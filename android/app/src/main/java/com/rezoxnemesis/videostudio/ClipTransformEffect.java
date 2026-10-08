package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

/** Evaluates the inspector's transform curves for every preview/export frame. */
@UnstableApi
public final class ClipTransformEffect implements MatrixTransformation {
    private final ProjectStore.Clip clip;
    public ClipTransformEffect(ProjectStore.Clip clip) { this.clip = ProjectStore.Clip.fromJson(clip.toJson()); }
    @Override public Size configure(int width, int height) { return new Size(width, height); }
    @Override public Matrix getMatrix(long presentationTimeUs) {
        long local = Math.max(0, presentationTimeUs / 1000);
        float scale = (float)EditorEngine.valueAt(clip,"scale",local,clip.effects.optDouble("zoom",1));
        float sx = scale*(float)EditorEngine.valueAt(clip,"scaleX",local,1);
        float sy = scale*(float)EditorEngine.valueAt(clip,"scaleY",local,1);
        float rotation=(float)EditorEngine.valueAt(clip,"rotate",local,0);
        float x=(float)EditorEngine.valueAt(clip,"x",local,0),y=(float)EditorEngine.valueAt(clip,"y",local,0);
        float anchorX=(float)EditorEngine.valueAt(clip,"anchorX",local,0),anchorY=(float)EditorEngine.valueAt(clip,"anchorY",local,0);
        Matrix matrix=new Matrix();matrix.postTranslate(-anchorX,-anchorY);matrix.postScale(sx,sy);
        matrix.postRotate(rotation);matrix.postTranslate(x+anchorX,y+anchorY);return matrix;
    }
}
