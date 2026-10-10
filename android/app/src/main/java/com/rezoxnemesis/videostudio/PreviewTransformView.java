package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A selected clip's direct monitor transform. Gestures preview temporarily and commit once. */
final class PreviewTransformView extends View {
    interface Listener {
        void onPreview(float scaleRatio, float deltaX, float deltaY, float deltaRotation);
        void onCommit(float scaleRatio, float deltaX, float deltaY, float deltaRotation);
        void onCancel();
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final float aspect;
    private final float baseX,baseY,baseScaleX,baseScaleY,baseRotation;
    private float x,y,ratio=1,rotation,downX,downY,pinchDistance,pinchAngle,startRatio,startRotation;
    private boolean dragging,pinching,changed;
    PreviewTransformView(Context context,float aspect,MotionTimeline.Sample sample,Listener listener){
        super(context);this.listener=listener;this.aspect=aspect;baseX=x=sample.x;baseY=y=sample.y;baseScaleX=sample.scaleX;baseScaleY=sample.scaleY;baseRotation=rotation=sample.rotation;
        setContentDescription("Selected clip transform. Drag to move. Pinch to scale and rotate.");
    }
    private RectF bounds(){
        float width=viewportWidth(),height=viewportHeight();
        float cx=getWidth()/2f+x*width/2f,cy=getHeight()/2f-y*height/2f;
        return new RectF(cx-width*baseScaleX*ratio/2f,cy-height*baseScaleY*ratio/2f,cx+width*baseScaleX*ratio/2f,cy+height*baseScaleY*ratio/2f);
    }
    @Override protected void onDraw(Canvas canvas){
        RectF bounds=bounds();canvas.save();canvas.rotate(-rotation,bounds.centerX(),bounds.centerY());
        paint.setColor(Color.rgb(47,226,240));paint.setStrokeWidth(dp(1.5f));paint.setStyle(Paint.Style.STROKE);canvas.drawRect(bounds,paint);
        paint.setStyle(Paint.Style.FILL);for(float px:new float[]{bounds.left,bounds.right})for(float py:new float[]{bounds.top,bounds.bottom})canvas.drawCircle(px,py,dp(4),paint);canvas.restore();
        paint.setTextSize(dp(10));canvas.drawText("Drag to move · pinch to scale / rotate",dp(8),dp(16),paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                RectF hit=bounds();hit.inset(-dp(20),-dp(20));if(!hit.contains(event.getX(),event.getY()))return false;
                dragging=true;changed=false;downX=event.getX();downY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if(dragging&&event.getPointerCount()==2){pinching=true;pinchDistance=distance(event);pinchAngle=angle(event);startRatio=ratio;startRotation=rotation;}return true;
            case MotionEvent.ACTION_MOVE:
                if(!dragging)return false;
                if(pinching&&event.getPointerCount()>=2){ratio=Math.max(.05f,Math.min(10,startRatio*distance(event)/Math.max(1,pinchDistance)));rotation=startRotation-angle(event)+pinchAngle;}
                else{x+=(event.getX()-downX)*2f/Math.max(1,viewportWidth());y-=(event.getY()-downY)*2f/Math.max(1,viewportHeight());downX=event.getX();downY=event.getY();}
                changed=true;listener.onPreview(ratio,x-baseX,y-baseY,rotation-baseRotation);invalidate();return true;
            case MotionEvent.ACTION_POINTER_UP:
                pinching=false;int remaining=event.getActionIndex()==0?1:0;downX=event.getX(remaining);downY=event.getY(remaining);return true;
            case MotionEvent.ACTION_UP:
                if(changed)listener.onCommit(ratio,x-baseX,y-baseY,rotation-baseRotation);else listener.onCancel();dragging=false;pinching=false;performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                x=baseX;y=baseY;ratio=1;rotation=baseRotation;dragging=false;pinching=false;changed=false;invalidate();listener.onCancel();return true;
            default:return dragging;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    boolean isManipulating(){return dragging;}
    private float viewportWidth(){return Math.min(getWidth(),getHeight()*aspect);}
    private float viewportHeight(){return Math.min(getHeight(),getWidth()/aspect);}
    private float distance(MotionEvent e){return (float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));}
    private float angle(MotionEvent e){return (float)Math.toDegrees(Math.atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0)));}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
