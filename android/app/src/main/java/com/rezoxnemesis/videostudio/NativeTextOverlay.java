package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded, deterministic title rasterization for the preview/export effect chain. */
@UnstableApi
public final class NativeTextOverlay extends CanvasOverlay {
    private final String text,animation;
    private final Typeface font;
    private final long offsetUs,durationUs;
    private final float speed,textSize,y;
    private final int color;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

    public NativeTextOverlay(String text,JSONObject fx,long offsetUs,float speed,long durationUs) {
        super(true);this.text=text;this.animation=fx.optString("textAnimation","none");
        String family=fx.optString("fontFamily","sans-serif-medium");
        if(!supportsFont(family))throw new IllegalArgumentException("Font is not a supported system family: "+family);
        int style=Typeface.NORMAL;
        switch(family){
            case "poster":family="sans-serif-black";break;
            case "tech":family="monospace";break;
            case "editorial":family="serif";break;
            case "elegant":family="serif";style=Typeface.ITALIC;break;
            default:break;
        }
        font=Typeface.create(family,style);
        this.offsetUs=offsetUs;this.speed=Math.max(.1f,speed);this.durationUs=durationUs;
        textSize=(float)Math.max(.02,Math.min(.15,fx.optDouble("textSize",.06)));
        y=(float)Math.max(.05,Math.min(.95,fx.optDouble("textY",.80)));
        try{color=Color.parseColor(fx.optString("textColor","#FFFFFF"));}catch(Exception error){throw new IllegalArgumentException("Invalid title color",error);}
        paint.setTypeface(font);paint.setTextAlign(Paint.Align.CENTER);
    }

    public static boolean supportsAnimation(String value) {
        return Arrays.asList("none","fade","fade_up","fade_down","slide_left","slide_right",
                "scale_in","pop","bounce","typewriter","word_reveal","line_reveal","cinematic_title","caption_pop").contains(value);
    }
    public static boolean supportsFont(String value){return CreatorCatalog.FONTS.contains(value);}

    @Override public void onDraw(Canvas canvas,long presentationTimeUs) {
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
        if(canvas.getWidth()<=0||canvas.getHeight()<=0||text==null||text.isEmpty())return;
        long timeUs=Math.max(0,(long)((presentationTimeUs-offsetUs)/(double)speed));
        float p=Math.max(0,Math.min(1,timeUs/(float)Math.min(700000,Math.max(1,durationUs/3))));
        float e=MotionTimeline.ease(p,"ease_out"),alpha=1,scale=1,dx=0,dy=0;
        switch(animation) {
            case "fade": case "cinematic_title":alpha=e;break;
            case "fade_up":alpha=e;dy=(1-e)*canvas.getHeight()*.08f;break;
            case "fade_down":alpha=e;dy=-(1-e)*canvas.getHeight()*.08f;break;
            case "slide_left":dx=-(1-e)*canvas.getWidth();break;
            case "slide_right":dx=(1-e)*canvas.getWidth();break;
            case "scale_in":scale=Math.max(.01f,e);break;
            case "pop":case "caption_pop":scale=.8f+.2f*e+(float)Math.sin(p*Math.PI)*.12f;alpha=e;break;
            case "bounce":dy=-(float)Math.abs(Math.sin(p*Math.PI*3))*canvas.getHeight()*.06f*(1-p);break;
            default:break;
        }
        String visible=text;
        if("typewriter".equals(animation))visible=text.substring(0,Math.min(text.length(),Math.round(text.length()*p)));
        if("word_reveal".equals(animation)) {
            String[] words=text.split("\\s+");StringBuilder builder=new StringBuilder();
            for(int i=0;i<Math.ceil(words.length*p)&&i<words.length;i++){if(i>0)builder.append(' ');builder.append(words[i]);}visible=builder.toString();
        }
        paint.setTextSize(canvas.getWidth()*textSize);paint.setColor(color);paint.setAlpha(Math.round(Color.alpha(color)*alpha));
        paint.setShadowLayer(paint.getTextSize()*.12f,0,paint.getTextSize()*.025f,Color.argb(Math.round(190*alpha),0,0,0));
        List<String> lines=wrap(visible,canvas.getWidth()*.88f);
        int lineCount="line_reveal".equals(animation)?Math.min(lines.size(),(int)Math.ceil(lines.size()*p)):lines.size();
        canvas.save();canvas.translate(canvas.getWidth()/2f+dx,canvas.getHeight()*y+dy);canvas.scale(scale,scale);
        float height=paint.getFontSpacing();
        for(int i=0;i<lineCount;i++)canvas.drawText(lines.get(i),0,(i-(lines.size()-1)/2f)*height,paint);
        canvas.restore();
    }

    private List<String> wrap(String content,float maxWidth) {
        ArrayList<String> lines=new ArrayList<>();
        for(String paragraph:content.split("\\n",-1)) {
            String line="";
            for(String word:paragraph.split("\\s+")) {
                String candidate=line.isEmpty()?word:line+" "+word;
                if(!line.isEmpty()&&paint.measureText(candidate)>maxWidth){lines.add(line);line=word;}else line=candidate;
                if(lines.size()>=12)return lines;
            }
            lines.add(line);if(lines.size()>=12)break;
        }
        return lines;
    }
}
