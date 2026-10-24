package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Direct editing handles drawn over the existing Media3 preview.
 * Video pixels are neither read nor re-encoded during a gesture.
 * A single guarded project edit is committed on finger release.
 */
public final class StudioTouchTransformOverlay extends View {
    public interface Listener {
        void onCommit(StudioLayer candidate);
        void onCancel();
    }
    private static final float MIN_W=.01f,MAX_W=2f,MIN_H=.01f,MAX_H=2f;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final StudioLayer initial;
    private StudioLayer draft,start;
    private boolean active,dirty,pinching,complete;
    private float startX,startY,startSpan,startAngle,startMidX,startMidY;
    private static final float FRAME_ASPECT=9f/16f;

    public StudioTouchTransformOverlay(Context context, StudioLayer layer, Listener listener) {
        super(context);
        if(layer==null||listener==null)throw new IllegalArgumentException("Touch editor needs a selected layer");
        if(!StudioLayer.IMAGE.equals(layer.type)&&!StudioLayer.SHAPE.equals(layer.type)
                &&!StudioLayer.VIDEO.equals(layer.type))
            throw new IllegalArgumentException("Direct manipulation works for image, video and native shape overlays");
        layer.validate();
        initial=layer.copy();
        draft=layer.copy();start=layer.copy();
        this.listener=listener;
        setContentDescription("Drag to move selected layer. Two fingers resize and rotate. Release to save.");
        setFocusable(true);setClickable(true);
    }

    /** Display-space fit matches portrait composition, independent of phone rotation. */
    public static RectF fittedCanvas(int width,int height,float aspect){
        if(width<=0||height<=0||!Float.isFinite(aspect)||aspect<=0)
            throw new IllegalArgumentException("A measurable preview and aspect are required");
        float actual=width/(float)height,contentW,contentH;
        if(actual>aspect){contentH=height;contentW=height*aspect;}
        else{contentW=width;contentH=width/aspect;}
        return new RectF((width-contentW)/2f,(height-contentH)/2f,
                (width+contentW)/2f,(height+contentH)/2f);
    }

    public StudioLayer candidate(){return draft.copy();}

    private RectF content(){return fittedCanvas(getWidth(),getHeight(),FRAME_ASPECT);}
    private RectF bounds(StudioLayer layer,RectF content){
        float centerX=content.left+layer.x*content.width();
        float centerY=content.top+layer.y*content.height();
        float halfW=layer.width*content.width()*.5f;
        float halfH=layer.height*content.height()*.5f;
        return new RectF(centerX-halfW,centerY-halfH,centerX+halfW,centerY+halfH);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(getWidth()<=0||getHeight()<=0)return;
        RectF b=bounds(draft,content());
        paint.setColor(0xff55dfff);paint.setStrokeWidth(Math.max(2,getResources().getDisplayMetrics().density*2));
        paint.setStyle(Paint.Style.STROKE);
        paint.setPathEffect(new DashPathEffect(new float[]{12,8},0));
        canvas.save();canvas.rotate(draft.rotation,b.centerX(),b.centerY());
        canvas.drawRoundRect(b,6,6,paint);
        paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);
        for(float x:new float[]{b.left,b.right})for(float y:new float[]{b.top,b.bottom})
            canvas.drawCircle(x,y,Math.max(4,getResources().getDisplayMetrics().density*5),paint);
        canvas.restore();
        paint.setColor(Color.WHITE);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
        paint.setShadowLayer(3,1,1,Color.BLACK);
        String guide="Drag to move   •   pinch to scale and rotate";
        canvas.drawText(guide,Math.max(6,content().left+8),Math.max(24,content().top+22),paint);
        paint.clearShadowLayer();
    }

    private static float clamp(float n,float low,float high){
        return Math.max(low,Math.min(high,n));
    }
    private static float angle(MotionEvent event){
        return (float)Math.toDegrees(Math.atan2(event.getY(1)-event.getY(0),
                event.getX(1)-event.getX(0)));
    }
    private static float span(MotionEvent event){
        return (float)Math.hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0));
    }
    private static float midX(MotionEvent e){return (e.getX(0)+e.getX(1))/2f;}
    private static float midY(MotionEvent e){return (e.getY(0)+e.getY(1))/2f;}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(complete)return true;
        RectF frame;
        try{frame=content();}catch(IllegalArgumentException error){return false;}
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN: {
                RectF hit=bounds(draft,frame);
                hit.inset(-24,-24);
                if(!hit.contains(e.getX(),e.getY()))return false;
                active=true;pinching=false;dirty=false;start=draft.copy();
                startX=e.getX();startY=e.getY();
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if(!active||e.getPointerCount()<2)return true;
                start=draft.copy();pinching=true;
                startMidX=midX(e);startMidY=midY(e);
                startSpan=Math.max(1,span(e));startAngle=angle(e);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if(!active)return false;
                if(e.getPointerCount()>=2&&pinching){
                    float ratio=clamp(span(e)/startSpan,.05f,20f);
                    draft.width=clamp(start.width*ratio,MIN_W,MAX_W);
                    draft.height=clamp(start.height*ratio,MIN_H,MAX_H);
                    draft.x=clamp(start.x+(midX(e)-startMidX)/frame.width(),0,1);
                    draft.y=clamp(start.y+(midY(e)-startMidY)/frame.height(),0,1);
                    draft.rotation=clamp(start.rotation+angle(e)-startAngle,-360,360);
                }else if(!pinching){
                    draft.x=clamp(start.x+(e.getX()-startX)/frame.width(),0,1);
                    draft.y=clamp(start.y+(e.getY()-startY)/frame.height(),0,1);
                }
                dirty=true;invalidate();return true;
            }
            case MotionEvent.ACTION_POINTER_UP:{
                if(!active)return false;
                // Rebase at remaining finger on the next gesture; no jump on UP.
                if(e.getPointerCount()==2){
                    int other=e.getActionIndex()==0?1:0;
                    start=draft.copy();startX=e.getX(other);startY=e.getY(other);pinching=false;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:{
                if(!active)return false;
                active=false;complete=true;
                if(dirty){
                    draft.validate();
                    listener.onCommit(draft.copy());
                }else listener.onCancel();
                performClick();return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if(active){active=false;complete=true;listener.onCancel();}
                return true;
            }
            default:return super.onTouchEvent(e);
        }
    }

    @Override public boolean performClick(){super.performClick();return true;}
}
