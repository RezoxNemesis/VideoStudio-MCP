package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

/** Timestamp-driven titles, including Unicode-safe character/word reveals. */
@UnstableApi
public final class ClipTitleOverlay extends CanvasOverlay {
    private final String text, animation;
    private final long sequenceStartUs, durationUs;
    private final TextPaint paint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private StaticLayout layout;
    private String layoutText = "";
    private int layoutWidth;
    private float layoutSize, layoutSpacing;

    public ClipTitleOverlay(ProjectStore.Clip clip, long sequenceStartUs) {
        super(true);
        text = clip.title;
        animation = clip.effects.optString("textAnimation", "none");
        this.sequenceStartUs = sequenceStartUs;
        durationUs = Math.multiplyExact(clip.outputDurationMs(), 1000L);
        String font = clip.effects.optString("fontFamily", "sans-serif-medium");
        if ("elegant".equals(font) || "editorial".equals(font)) font = "serif";
        else if ("poster".equals(font)) font = "sans-serif-black";
        else if ("tech".equals(font)) font = "monospace";
        paint.setTypeface(Typeface.create(font, Typeface.NORMAL));
        paint.setColor(Color.WHITE);
    }

    @Override public void onDraw(Canvas canvas, long presentationTimeUs) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (text.isEmpty() || canvas.getWidth() < 1 || canvas.getHeight() < 1) return;
        long local = Math.max(0, presentationTimeUs - sequenceStartUs);
        long entrance = Math.min(600000L, Math.max(100000L, durationUs / 3));
        float t = Math.min(1f, local / (float) entrance), e = t*t*(3-2*t);
        float alpha = 1, scale = 1, dx = 0, dy = 0, rotation = 0, spacing = 0;
        String shown = text;
        switch (animation) {
            case "fade": alpha=e; break;
            case "fade_up": alpha=e; dy=(1-e)*canvas.getHeight()*.08f; break;
            case "fade_down": alpha=e; dy=-(1-e)*canvas.getHeight()*.08f; break;
            case "slide_left": alpha=e; dx=-(1-e)*canvas.getWidth()*.22f; break;
            case "slide_right": alpha=e; dx=(1-e)*canvas.getWidth()*.22f; break;
            case "scale_in": alpha=e; scale=.55f+.45f*e; break;
            case "pop": case "caption_pop": alpha=e; scale=.65f+.35f*e+.16f*(float)Math.sin(Math.PI*t); break;
            case "bounce": alpha=e; dy=-(1-t)*(float)Math.abs(Math.sin(t*Math.PI*3))*canvas.getHeight()*.12f; break;
            case "typewriter": {
                int count=text.codePointCount(0,text.length());
                shown=text.substring(0,text.offsetByCodePoints(0,Math.min(count,(int)Math.floor(t*count)))); break;
            }
            case "word_reveal": {
                java.util.regex.Matcher words=java.util.regex.Pattern.compile("\\S+\\s*").matcher(text);
                int count=0;while(words.find())count++;
                int visible=(int)Math.floor(t*count),end=0;words.reset();
                while(visible-->0&&words.find())end=words.end();shown=text.substring(0,end);break;
            }
            case "line_reveal": case "mask_reveal": alpha=t==0?0:1; break;
            case "blur_in": alpha=e; scale=1.12f-.12f*e; break;
            case "tracking_in": alpha=e; spacing=(1-e)*.18f; break;
            case "tracking_out": alpha=e; spacing=-(1-e)*.07f; break;
            case "glitch": alpha=e; dx=(1-t)*(float)Math.sin(local/17000d)*canvas.getWidth()*.025f; break;
            case "neon_flicker": alpha=e*(.65f+.35f*(float)Math.abs(Math.sin(local/51000d))); break;
            case "kinetic": alpha=e; rotation=(1-e)*-7; scale=.8f+.2f*e; break;
            case "cinematic_title": alpha=e; spacing=.08f*e; scale=.96f+.04f*e; break;
            default: break;
        }
        if (shown.isEmpty() || alpha<=0) return;
        int width=Math.max(1,Math.round(canvas.getWidth()*.86f));
        float size=Math.max(14,Math.min(96,canvas.getHeight()*.075f));
        paint.setTextSize(size);paint.setLetterSpacing(spacing);
        paint.setMaskFilter("blur_in".equals(animation)&&e<1
                ?new android.graphics.BlurMaskFilter(Math.max(.01f,(1-e)*size*.14f),android.graphics.BlurMaskFilter.Blur.NORMAL):null);
        paint.setShadowLayer(Math.max(1,size*.08f),0,1,"neon_flicker".equals(animation)?Color.CYAN:Color.BLACK);
        if(layout==null||width!=layoutWidth||size!=layoutSize||spacing!=layoutSpacing||!shown.equals(layoutText)){
            layout=StaticLayout.Builder.obtain(shown,0,shown.length(),paint,width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                    .setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END).build();
            layoutText=shown;layoutWidth=width;layoutSize=size;layoutSpacing=spacing;
        }
        int saved=canvas.saveLayerAlpha(0,0,canvas.getWidth(),canvas.getHeight(),Math.round(255*alpha));
        canvas.translate(canvas.getWidth()*.5f+dx,canvas.getHeight()*.80f+dy);
        canvas.rotate(rotation);canvas.scale(scale,scale);canvas.translate(-width*.5f,-layout.getHeight()*.5f);
        if("line_reveal".equals(animation))canvas.clipRect(0,0,width,layout.getHeight()*e);
        if("mask_reveal".equals(animation))canvas.clipRect(0,0,width*e,layout.getHeight());
        layout.draw(canvas);canvas.restoreToCount(saved);
    }
}
