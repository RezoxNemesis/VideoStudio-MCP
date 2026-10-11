package com.rezoxnemesis.videostudio;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import androidx.media3.effect.OverlaySettings;
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
        content.setSpan(new StyleSpan(Typeface.BOLD),0,content.length(),0);
        content.setSpan(new AbsoluteSizeSpan(32,true),0,content.length(),0);
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
