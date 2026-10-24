package com.rezoxnemesis.videostudio;

import android.util.Pair;
import androidx.media3.common.OverlaySettings;

/**
 * One-level motion parenting, evaluated at absolute composition timestamps.
 * The non-rendering group transforms each child about the output-frame center.
 * Shared by image, vector and title compositors in preview/export.
 */
public final class StudioParentGroupTransform {
    private final StudioLayer parent;
    private final StudioLayerMotion motion;

    public StudioParentGroupTransform(StudioLayer group) {
        if(group==null||!StudioLayer.GROUP.equals(group.type))
            throw new IllegalArgumentException("Source-free motion group required");
        group.validate();
        parent=group.copy();
        motion=StudioLayerMotion.fromLayer(parent);
    }

    public OverlaySettings apply(OverlaySettings child,long presentationTimeUs){
        if(child==null||presentationTimeUs<0)
            throw new IllegalArgumentException("Child settings and nonnegative video clock required");
        long origin=parent.startMs*1000L;
        long elapsed=Math.max(0,Math.min(parent.durationMs*1000L,presentationTimeUs-origin));
        StudioClipKeyframes.Values values=motion.atUs(elapsed);
        Pair<Float,Float> base=child.getBackgroundFrameAnchor();
        Pair<Float,Float> baseScale=child.getScale();
        double radians=Math.toRadians(values.rotate);
        double c=Math.cos(radians),s=Math.sin(radians);
        double x=base.first*values.scaleX,y=base.second*values.scaleY;
        float transformedX=(float)(x*c-y*s+values.x*2.0);
        float transformedY=(float)(x*s+y*c-values.y*2.0);
        float scaleX=(float)(baseScale.first*values.scaleX);
        float scaleY=(float)(baseScale.second*values.scaleY);
        float rotate=(float)(child.getRotationDegrees()+values.rotate);
        float alpha=(float)Math.max(0,Math.min(1,child.getAlphaScale()*values.opacity));
        return new ParentedSettings(transformedX,transformedY,scaleX,scaleY,
                rotate,alpha,child.getOverlayFrameAnchor());
    }

    private static final class ParentedSettings implements OverlaySettings {
        private final Pair<Float,Float> background,scale,overlay;
        private final float rotation,alpha;
        ParentedSettings(float x,float y,float sx,float sy,float rotate,float alpha,
                         Pair<Float,Float> overlay){
            background=Pair.create(x,y);scale=Pair.create(sx,sy);
            rotation=rotate;this.alpha=alpha;this.overlay=overlay;
        }
        @Override public Pair<Float,Float> getBackgroundFrameAnchor(){return background;}
        @Override public Pair<Float,Float> getOverlayFrameAnchor(){return overlay;}
        @Override public Pair<Float,Float> getScale(){return scale;}
        @Override public float getRotationDegrees(){return rotation;}
        @Override public float getAlphaScale(){return alpha;}
    }
}
