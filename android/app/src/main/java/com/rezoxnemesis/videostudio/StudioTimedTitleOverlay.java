package com.rezoxnemesis.videostudio;

import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Pair;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;

/**
 * Composition-time caption layer: works even while Media3 composites a
 * separately articulated source scene. This does not require splitting or
 * repeating any source images; alpha is zero outside the authored time span.
 */
public final class StudioTimedTitleOverlay extends TextOverlay {
    private final long startUs,endUs;
    private final SpannableString content;
    private final OverlaySettings visible,hidden;
    private final StudioLayerMotion motion;
    private final StudioParentGroupTransform parent;
    private final float anchorY;
    public StudioTimedTitleOverlay(StudioLayer caption){this(caption,null);}
    public StudioTimedTitleOverlay(StudioLayer caption,StudioLayer motionGroup){
        if(caption==null||!StudioLayer.TITLE.equals(caption.type))
            throw new IllegalArgumentException("Timed title layer required");
        caption.validate();
        motion=StudioLayerMotion.fromLayer(caption);
        parent=motionGroup==null?null:new StudioParentGroupTransform(motionGroup);
        startUs=caption.startMs*1000L;
        endUs=(caption.startMs+caption.durationMs)*1000L;
        content=new SpannableString(caption.text);
        content.setSpan(new ForegroundColorSpan(caption.color),0,content.length(),0);
        int style="bold_italic".equals(caption.fontStyle)?Typeface.BOLD_ITALIC:
                "italic".equals(caption.fontStyle)?Typeface.ITALIC:
                "bold".equals(caption.fontStyle)?Typeface.BOLD:Typeface.NORMAL;
        Layout.Alignment align="left".equals(caption.textAlign)?Layout.Alignment.ALIGN_NORMAL:
                "right".equals(caption.textAlign)?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_CENTER;
        content.setSpan(new StyleSpan(style),0,content.length(),0);
        content.setSpan(new TypefaceSpan(caption.fontFamily),0,content.length(),0);
        content.setSpan(new AbsoluteSizeSpan(caption.fontSize,true),0,content.length(),0);
        content.setSpan(new AlignmentSpan.Standard(align),0,content.length(),0);
        if((caption.backgroundColor>>>24)>0)
            content.setSpan(new BackgroundColorSpan(caption.backgroundColor),0,content.length(),0);
        float y=caption.position==0?.72f:(caption.position==2?-.71f:0f);
        anchorY=y;
        visible=new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f,y)
                .setOverlayFrameAnchor(0f,0f)
                .setAlphaScale(1f).build();
        hidden=new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f,y)
                .setOverlayFrameAnchor(0f,0f)
                .setAlphaScale(0f).build();
    }
    public static boolean activeAt(long timestampUs,long startUs,long endUs){
        return timestampUs>=startUs&&timestampUs<endUs;
    }
    @Override public SpannableString getText(long presentationTimeUs){
        return content;
    }
    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs){
        boolean active=activeAt(presentationTimeUs,startUs,endUs);
        OverlaySettings own;
        if(!motion.hasKeyframes())own=active?visible:hidden;
        else{
            StudioClipKeyframes.Values value=motion.atUs(active?presentationTimeUs-startUs:0);
            own=new MotionSettings(0f+(float)(2*value.x),anchorY-(float)(2*value.y),
                    1f*(float)value.scaleX,1f*(float)value.scaleY,0f+(float)value.rotate,
                    active?1f*(float)value.opacity:0f);
        }
        return parent==null?own:parent.apply(own,presentationTimeUs);
    }

    /** Immutable per-frame settings support authored offscreen anchors without clamping. */
    private static final class MotionSettings implements OverlaySettings {
        private final Pair<Float,Float> background,scale;
        private final float rotation,alpha;
        MotionSettings(float x,float y,float sx,float sy,float rotation,float alpha){
            background=Pair.create(x,y);scale=Pair.create(sx,sy);this.rotation=rotation;this.alpha=alpha;
        }
        @Override public Pair<Float,Float> getBackgroundFrameAnchor(){return background;}
        @Override public Pair<Float,Float> getOverlayFrameAnchor(){return OverlaySettings.DEFAULT_OVERLAY_FRAME_ANCHOR;}
        @Override public Pair<Float,Float> getScale(){return scale;}
        @Override public float getRotationDegrees(){return rotation;}
        @Override public float getAlphaScale(){return alpha;}
    }
}
