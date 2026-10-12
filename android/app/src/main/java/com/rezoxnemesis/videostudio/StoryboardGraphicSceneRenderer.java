package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import java.util.Random;

/**
 * Non-character storyboard animation for explainers, kinetic text, product
 * presentations, landscapes, ambient illustrated scenes and abstract art.
 *
 * Unlike character scenes, a handwriting/diagram scene genuinely benefits
 * from progressive mask reveal; landscapes may use measured 2.5D depth
 * bands and subtle particle atmosphere. This reports CAMERA_ONLY or
 * GRAPHIC_REVEAL, never "new character body poses".
 */
public final class StoryboardGraphicSceneRenderer implements AutoCloseable {
    public enum Mode { DRAW_REVEAL, ENVIRONMENT, ABSTRACT_FLOW }
    private final int w,h;
    private final Bitmap a,b;
    private final int[] start,end;
    private final Mode mode;
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG|
            Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private final boolean[] changed;
    private final float changedFraction;

    public StoryboardGraphicSceneRenderer(int[] first,int[] last,int width,
                                          int height,Mode mode) {
        if(first==null||last==null||mode==null||width<64||height<64
                ||(long)width*height>720L*1280L||
                first.length!=width*height||last.length!=first.length)
            throw new IllegalArgumentException("Two imported graphic source frames required");
        w=width;h=height;this.mode=mode;
        start=first.clone();end=last.clone();
        a=Bitmap.createBitmap(first,width,height,Bitmap.Config.ARGB_8888);
        b=Bitmap.createBitmap(last,width,height,Bitmap.Config.ARGB_8888);
        changed=new boolean[first.length];
        int change=0;
        long delta=0;
        for(int i=0;i<first.length;i++){
            int c1=first[i],c2=last[i];
            int diff=Math.abs((c1>>>16&255)-(c2>>>16&255))
                    +Math.abs((c1>>>8&255)-(c2>>>8&255))
                    +Math.abs((c1&255)-(c2&255));
            delta+=diff;
            if(diff>72){changed[i]=true;change++;}
        }
        changedFraction=change/(float)first.length;
        if(mode==Mode.DRAW_REVEAL && (changedFraction<.001f||changedFraction>.55f))
            throw new IllegalArgumentException(
                    "Not a progressive illustration; source panels change too much or not at all");
        if(mode==Mode.ENVIRONMENT && delta/(first.length*765d)>.12)
            throw new IllegalArgumentException(
                    "Different camera or landscape composition; split into separate establishing shots");
    }
    public float change(){return changedFraction;}
    public String backend(){
        return mode==Mode.DRAW_REVEAL?"real-pixel-graphic-stroke-reveal"
             :mode==Mode.ENVIRONMENT?"source-environment-parallax-and-atmosphere"
             :"source-abstract-wave-distortion";
    }

    public Bitmap render(float t) {
        if(t<0||t>1||!Float.isFinite(t))
            throw new IllegalArgumentException("Graphic timestamp outside 0..1");
        if(t==0)return a.copy(Bitmap.Config.ARGB_8888,false);
        if(t==1)return b.copy(Bitmap.Config.ARGB_8888,false);
        if(mode==Mode.DRAW_REVEAL)return reveal(t);
        return ambient(t);
    }

    private Bitmap reveal(float t){
        // Sequentially expose source B's real *changed* strokes in an
        // advancing spatial band. Unchanged paper/background stays exact
        // from the original. No fade of the whole page or fake handwriting.
        int[] out=start.clone();
        float boundary=(float)(t*w*1.12-w*.06);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x;
            if(!changed[i])continue;
            float jitter=(float)Math.sin(y*.075)*w*.006f;
            float spatial=x-boundary+jitter;
            if(spatial<0)out[i]=end[i];
            else if(spatial<3){
                float a=Math.max(0,Math.min(1,1-spatial/3f));
                out[i]=mix(start[i],end[i],a);
            }
        }
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888);
    }
    private Bitmap ambient(float t) {
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);
        c.drawBitmap(a,0,0,brush);
        // Three source-image depth bands move at different velocities.
        // It is intentionally named camera/environment animation, not
        // human animation; no speculative backside of buildings is drawn.
        int[] cuts={0,(int)(h*.33f),(int)(h*.68f),h};
        float[] speeds={.011f,.006f,.0025f};
        c.save();
        for(int band=0;band<3;band++){
            int top=cuts[band],bottom=cuts[band+1];
            float phase=(float)Math.sin(Math.PI*t);
            float motion=(float)Math.sin(2*Math.PI*t)*w*speeds[band]*phase;
            if(mode==Mode.ABSTRACT_FLOW)
                motion=(float)Math.sin(2*Math.PI*t+band*.9f)*w*.014f*phase;
            c.save();c.clipRect(0,top,w,bottom);
            Rect source=new Rect(0,top,w,bottom);
            RectF dest=new RectF(motion,top,motion+w,bottom);
            c.drawBitmap(a,source,dest,brush);
            c.restore();
        }
        atmosphere(c,t);
        c.restore();
        return out;
    }
    private void atmosphere(Canvas canvas,float t){
        // Deterministic humidity, dust and subtle atmospheric motion.
        // Stationary source silhouettes never receive fake limbs.
        Random noise=new Random(81927);
        for(int i=0;i<55;i++){
            float originX=noise.nextFloat()*w,originY=noise.nextFloat()*h;
            float vx=(noise.nextFloat()-.5f)*w*.03f,
                  vy=(noise.nextFloat()-.5f)*h*.05f;
            float x=originX+vx*t,y=originY+vy*t;
            float life=(float)Math.sin(Math.PI*t);
            int alpha=Math.round((13+noise.nextInt(26))*life);
            float radius=.5f+noise.nextFloat()*3.5f;
            brush.setColor(Color.argb(alpha,248,215,170));
            canvas.drawCircle(x,y,radius,brush);
        }
        brush.setColor(Color.WHITE);
    }
    private static int mix(int a,int b,float t){
        float o=1-t;
        int r=Math.round((a>>>16&255)*o+(b>>>16&255)*t);
        int g=Math.round((a>>>8&255)*o+(b>>>8&255)*t);
        int blue=Math.round((a&255)*o+(b&255)*t);
        return 0xff000000|(r<<16)|(g<<8)|blue;
    }
    @Override public void close(){a.recycle();b.recycle();}
}
