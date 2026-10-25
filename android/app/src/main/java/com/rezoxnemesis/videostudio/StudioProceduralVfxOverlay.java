package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.Pair;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.effect.CanvasOverlay;

/**
 * A live source-free Media3 CanvasOverlay, not a still sticker. Evaluates deterministic
 * geometry from the exact video presentation timestamp for every exported frame.
 * GPU texture upload is handled by Media3 CanvasOverlay; never allocate one Bitmap per frame.
 */
public final class StudioProceduralVfxOverlay extends CanvasOverlay {
    public static final int RASTER_SIZE=512;
    private final StudioLayer lane;
    private final StudioLayerMotion motion;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int outputWidth=1, outputHeight=1;

    public StudioProceduralVfxOverlay(StudioLayer source){
        super(false);
        if(source==null||!StudioLayer.VFX.equals(source.type))
            throw new IllegalArgumentException("Native procedural VFX lane required");
        source.validate();
        lane=source.copy();
        motion=StudioLayerMotion.fromLayer(lane);
        setCanvasSize(RASTER_SIZE,RASTER_SIZE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void configure(Size videoSize){
        super.configure(videoSize);
        outputWidth=Math.max(1,videoSize.getWidth());
        outputHeight=Math.max(1,videoSize.getHeight());
    }

    public boolean activeAt(long timeUs){
        return timeUs>=lane.startMs*1000L
                && timeUs<(lane.startMs+lane.durationMs)*1000L;
    }

    @Override public void onDraw(Canvas canvas,long presentationTimeUs){
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
        if(!activeAt(presentationTimeUs))return;
        long localUs=presentationTimeUs-lane.startMs*1000L;
        renderParticles(canvas,lane,localUs,paint);
    }

    /** Independent from the Media3 GPU context to enable honest CPU pixel-timing tests. */
    public static void renderParticles(Canvas canvas,StudioLayer source,long localUs,Paint brush){
        if(canvas==null||source==null||brush==null||localUs<0)
            throw new IllegalArgumentException("Canvas, VFX and nonnegative local time required");
        // The source is validated and snapshotted at overlay creation, not per output frame.
        int w=canvas.getWidth(),h=canvas.getHeight();
        if(w<=0||h<=0)return;
        double time=localUs/1_000_000d;
        int count=source.particleCount;
        double size=source.particleSize*Math.min(w,h);
        double velocity=source.particleSpeed;
        for(int i=0;i<count;i++){
            double hx=hash(source.particleSeed,i,11),hy=hash(source.particleSeed,i,23),
                    hb=hash(source.particleSeed,i,37);
            double phase,px,py,alpha;
            float radius;
            brush.setColor(source.color);
            brush.setStyle(Paint.Style.FILL);
            switch(source.vfxKind){
                case "rain":{
                    phase=fract(hy+time*velocity*(.20+hb*.12));
                    px=fract(hx+time*.015*velocity);
                    py=phase;
                    alpha=.25+.55*(1-hb);
                    brush.setStyle(Paint.Style.STROKE);
                    brush.setStrokeWidth(Math.max(1f,(float)(size*.23)));
                    int c=source.color;
                    brush.setColor(Color.argb((int)Math.round(Color.alpha(c)*alpha),
                            Color.red(c),Color.green(c),Color.blue(c)));
                    float x=(float)(px*w),y=(float)(py*h);
                    canvas.drawLine(x,y,x-(float)(size*1.3),y+(float)(size*(3+hb*3)),brush);
                    break;
                }
                case "sparks":{
                    phase=fract(hb+time*velocity*.42);
                    double theta=6.28318530718*hx;
                    double r=Math.pow(phase,.8)*.49;
                    px=.5+Math.cos(theta)*r;
                    py=.52+Math.sin(theta)*r*.7+phase*phase*.11;
                    alpha=(1-phase)*(1-phase);
                    radius=(float)(size*(.45+.55*(1-phase)));
                    brush.setAlpha((int)Math.round(Color.alpha(source.color)*alpha));
                    canvas.drawCircle((float)(px*w),(float)(py*h),Math.max(1,radius),brush);
                    break;
                }
                case "energy":{
                    double theta=6.28318530718*(hx+time*velocity*(.12+hb*.05));
                    double r=.13+.34*hy;
                    px=.5+Math.cos(theta)*r;
                    py=.5+Math.sin(theta)*r*.68;
                    alpha=.45+.45*Math.sin(theta*2+time*velocity)*Math.sin(theta*2+time*velocity);
                    radius=(float)(size*(.55+hb*.65));
                    brush.setAlpha((int)Math.round(Color.alpha(source.color)*alpha));
                    canvas.drawCircle((float)(px*w),(float)(py*h),Math.max(1,radius),brush);
                    break;
                }
                case "embers":{
                    phase=fract(hy+time*velocity*(.07+hb*.08));
                    px=fract(hx+.045*Math.sin(time*.9+i*2.4));
                    py=1-phase;
                    alpha=.15+.8*Math.sin(Math.PI*phase);
                    radius=(float)(size*(.55+hb*.7));
                    brush.setAlpha((int)Math.round(Color.alpha(source.color)*alpha));
                    canvas.drawCircle((float)(px*w),(float)(py*h),Math.max(1,radius),brush);
                    break;
                }
                case "snow":{
                    phase=fract(hy+time*velocity*(.025+hb*.035));
                    px=fract(hx+.038*Math.sin(time*.8+hb*6));
                    py=phase;
                    alpha=.4+.5*hy;
                    radius=(float)(size*(.35+hb*.7));
                    brush.setAlpha((int)Math.round(Color.alpha(source.color)*alpha));
                    canvas.drawCircle((float)(px*w),(float)(py*h),Math.max(1,radius),brush);
                    break;
                }
                case "confetti":{
                    phase=fract(hy+time*velocity*(.09+hb*.08));
                    px=fract(hx+.075*Math.sin(time*.5+hb*6));
                    py=phase;
                    alpha=.45+.5*Math.sin(Math.PI*phase);
                    brush.setAlpha((int)Math.round(Color.alpha(source.color)*alpha));
                    canvas.save();
                    canvas.rotate((float)(time*velocity*95+hx*360),(float)(px*w),(float)(py*h));
                    canvas.drawRect((float)(px*w-size*.9),(float)(py*h-size*.18),
                            (float)(px*w+size*.9),(float)(py*h+size*.18),brush);
                    canvas.restore();
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported VFX particle type");
            }
        }
        // Deterministic and bounded: no random generator, heap-spawned sprites or frame buffers.
    }

    static double hash(int seed,int index,int salt){
        int x=seed ^ (index*0x9e3779b9) ^ (salt*0x7f4a7c15);
        x^=x>>>16;x*=0x7feb352d;x^=x>>>15;x*=0x846ca68b;x^=x>>>16;
        return (x&0x7fffffff)/(double)0x80000000L;
    }
    static double fract(double x){return x-Math.floor(x);}

    @Override public OverlaySettings getOverlaySettings(long us){
        boolean active=activeAt(us);
        long clock=active?us-lane.startMs*1000L:0;
        StudioClipKeyframes.Values v=motion.atUs(clock);
        float x=2f*lane.x-1f+(float)(2*v.x),y=1f-2f*lane.y-(float)(2*v.y);
        float sx=outputWidth*lane.width/RASTER_SIZE*(float)v.scaleX;
        float sy=outputHeight*lane.height/RASTER_SIZE*(float)v.scaleY;
        float angle=lane.rotation+(float)v.rotate;
        float alpha=active?lane.opacity*(float)v.opacity:0f;
        return new Settings(x,y,sx,sy,angle,alpha);
    }

    private static final class Settings implements OverlaySettings{
        final Pair<Float,Float> background,scale;final float rotation,alpha;
        Settings(float x,float y,float sx,float sy,float r,float a){
            background=Pair.create(x,y);scale=Pair.create(sx,sy);rotation=r;alpha=a;
        }
        @Override public Pair<Float,Float> getBackgroundFrameAnchor(){return background;}
        @Override public Pair<Float,Float> getOverlayFrameAnchor(){return OverlaySettings.DEFAULT_OVERLAY_FRAME_ANCHOR;}
        @Override public Pair<Float,Float> getScale(){return scale;}
        @Override public float getRotationDegrees(){return rotation;}
        @Override public float getAlphaScale(){return alpha;}
    }
}
