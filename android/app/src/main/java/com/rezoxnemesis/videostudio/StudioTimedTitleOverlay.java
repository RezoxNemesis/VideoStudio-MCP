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
    public StudioTimedTitleOverlay(StudioLayer caption){
        if(caption==null||!StudioLayer.TITLE.equals(caption.type))
            throw new IllegalArgumentException("Timed title layer required");
        caption.validate();
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
        return activeAt(presentationTimeUs,startUs,endUs)?visible:hidden;
    }
}
