package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import java.util.function.Consumer;

/**
 * An actual editable custom cubic Bézier velocity graph for Android phones.
 *
 * The on-screen curve is sampled from StudioBezierEasing, the same math
 * invoked by the compositor during playback/export. Drag only alters a draft
 * editor value. The owning keyframe dialog still commits via guarded project
 * revision on Apply; Cancel discards draft handles.
 */
public final class StudioBezierCurveView extends View {
    private static final float MIN_Y=-.5f,MAX_Y=1.5f;
    private static final int GRID_DIVISIONS=4,SAMPLES=72;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] controls={.22f,1f,.36f,1f};
    private Consumer<JSONArray> callback;
    private int handle=-1;
    private float left,top,right,bottom;
    private float originalX,originalY;
    private boolean changed;

    public StudioBezierCurveView(Context context){
        super(context);
        setMinimumHeight(dp(210));
        setContentDescription("Bézier easing graph. Drag cyan control handles; use the exact numeric fields for precision.");
        setFocusable(true);setClickable(true);
    }

    public void setOnHandlesChanged(Consumer<JSONArray> listener){callback=listener;}
    public void setHandles(JSONArray values){
        double[] verified=StudioBezierEasing.validate(values);
        for(int i=0;i<4;i++)controls[i]=(float)verified[i];
        invalidate();
    }
    public JSONArray handles(){
        JSONArray array=new JSONArray();
        for(float control:controls)array.put((Object)Double.valueOf(control));
        return array;
    }
    public PointF handlePoint(int which){
        if(which!=0&&which!=1)throw new IllegalArgumentException("Use first or second cubic control handle");
        box();
        return new PointF(pixelX(controls[which*2]),pixelY(controls[which*2+1]));
    }
    private int dp(int amount){return StudioUi.dp(getContext(),amount);}

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=resolveSize(dp(220),widthSpec);
        int height=resolveSize(dp(210),heightSpec);
        setMeasuredDimension(width,height);
    }

    private void box(){
        left=dp(34);right=Math.max(left+1,getWidth()-dp(19));
        top=dp(20);bottom=Math.max(top+1,getHeight()-dp(27));
    }

    private float pixelX(double value){return (float)(left+(right-left)*value);}
    private float pixelY(double value){
        return (float)(bottom-(value-MIN_Y)/(MAX_Y-MIN_Y)*(bottom-top));
    }
    private float domainX(float pixel){return Math.max(0,Math.min(1,(pixel-left)/(right-left)));}
    private float domainY(float pixel){
        return Math.max(MIN_Y,Math.min(MAX_Y,MIN_Y+(bottom-pixel)/(bottom-top)*(MAX_Y-MIN_Y)));
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        box();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(StudioEditorWorkspace.PANEL);
        canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(7),dp(7),paint);
        paint.setStrokeWidth(Math.max(1,dp(1)));
        for(int i=0;i<=GRID_DIVISIONS;i++){
            float fraction=i/(float)GRID_DIVISIONS;
            paint.setColor(StudioEditorWorkspace.RULE);
            canvas.drawLine(pixelX(fraction),top,pixelX(fraction),bottom,paint);
        }
        for(double y:new double[]{-.5,0,.5,1,1.5}){
            paint.setColor(y==0||y==1?0xff64736c:StudioEditorWorkspace.RULE);
            canvas.drawLine(left,pixelY(y),right,pixelY(y),paint);
        }
        paint.setTextSize(dp(10));
        paint.setColor(StudioEditorWorkspace.MUTED);
        canvas.drawText("0",left-dp(12),pixelY(0)+dp(4),paint);
        canvas.drawText("1",left-dp(12),pixelY(1)+dp(4),paint);
        canvas.drawText("TIME  →",right-dp(49),bottom+dp(18),paint);

        paint.setStrokeWidth(dp(1));paint.setColor(0xff748b9d);
        canvas.drawLine(pixelX(0),pixelY(0),pixelX(controls[0]),pixelY(controls[1]),paint);
        canvas.drawLine(pixelX(1),pixelY(1),pixelX(controls[2]),pixelY(controls[3]),paint);

        double[] curve={controls[0],controls[1],controls[2],controls[3]};
        Path evaluated=new Path();
        for(int i=0;i<=SAMPLES;i++){
            float x=i/(float)SAMPLES;
            float y=(float)StudioBezierEasing.evaluate(x,curve);
            if(i==0)evaluated.moveTo(pixelX(x),pixelY(y));
            else evaluated.lineTo(pixelX(x),pixelY(y));
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(StudioEditorWorkspace.SAGE);
        canvas.drawPath(evaluated,paint);
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<2;i++){
            float x=pixelX(controls[2*i]),y=pixelY(controls[2*i+1]);
            paint.setColor(i==handle?0xffffffff:0xff65cce6);
            canvas.drawCircle(x,y,dp(11),paint);
            paint.setColor(StudioEditorWorkspace.BG);
            canvas.drawCircle(x,y,dp(5),paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        box();
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                int nearest=-1;float distance=Float.MAX_VALUE;
                for(int i=0;i<2;i++){
                    float dx=event.getX()-pixelX(controls[i*2]);
                    float dy=event.getY()-pixelY(controls[i*2+1]);
                    float d=(float)Math.hypot(dx,dy);
                    if(d<distance){distance=d;nearest=i;}
                }
                if(distance>dp(36))return false;
                handle=nearest;changed=false;
                originalX=controls[2*handle];originalY=controls[2*handle+1];
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(handle<0)return false;
                float x=domainX(event.getX()),y=domainY(event.getY());
                controls[2*handle]=x;controls[2*handle+1]=y;
                changed=changed||Math.abs(x-originalX)>.0001f||Math.abs(y-originalY)>.0001f;
                if(changed&&callback!=null)callback.accept(handles());
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:{
                if(handle<0)return false;
                handle=-1;invalidate();performClick();return true;
            }
            case MotionEvent.ACTION_CANCEL:{
                if(handle<0)return false;
                handle=-1;invalidate();return true;
            }
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
