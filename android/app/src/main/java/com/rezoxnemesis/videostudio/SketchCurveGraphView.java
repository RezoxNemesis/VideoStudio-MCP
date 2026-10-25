package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

/**
 * Touch-operated Sketch Studio easing editor. The graph samples SketchEasing,
 * exactly the function used by rendered native drawing/keyframes, rather than
 * approximating Adobe's animation curves with a decorative custom view.
 *
 * Coordinates remain in [0,1] because SketchDocument only admits normalized
 * cubic handles. No source frames or layers are saved until the enclosing
 * transform/shape-key dialog's Apply operation.
 */
public final class SketchCurveGraphView extends View {
    public static final int SAMPLES=72;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] controls={.42f,0,1,1};
    private Consumer<JSONArray> listener;
    private int grabbed=-1;
    private float oldX,oldY,left,top,right,bottom;
    private boolean pressed;

    public SketchCurveGraphView(Context context){
        super(context);
        setMinimumHeight(dp(216));
        setContentDescription("Native Sketch Studio timing graph: drag the two curve points for animated drawing easing");
        setClickable(true);setFocusable(true);
    }
    private int dp(int unit){return StudioUi.dp(getContext(),unit);}

    public void setCurve(JSONArray input)throws Exception{
        JSONObject checking=new JSONObject().put("interpolation","bezier")
                .put("curve",input);
        SketchEasing.validate(checking);
        for(int i=0;i<4;i++)controls[i]=(float)input.getDouble(i);
        invalidate();
    }
    public JSONArray curve(){
        JSONArray values=new JSONArray();
        for(float item:controls)values.put((Object)Double.valueOf(item));
        return values;
    }
    public void setOnCurveChanged(Consumer<JSONArray> callback){listener=callback;}
    public PointF handlePoint(int index){
        if(index<0||index>1)throw new IllegalArgumentException("Choose the first or second curve handle");
        bounds();
        return new PointF(x(controls[index*2]),y(controls[index*2+1]));
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        setMeasuredDimension(resolveSize(dp(220),widthSpec),resolveSize(dp(216),heightSpec));
    }
    private void bounds(){
        left=dp(30);top=dp(15);
        right=Math.max(left+1,getWidth()-dp(18));
        bottom=Math.max(top+1,getHeight()-dp(28));
    }
    private float x(double fraction){return (float)(left+fraction*(right-left));}
    private float y(double fraction){return (float)(bottom-fraction*(bottom-top));}
    private float fx(float screen){return Math.max(0,Math.min(1,(screen-left)/(right-left)));}
    private float fy(float screen){return Math.max(0,Math.min(1,(bottom-screen)/(bottom-top)));}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);bounds();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff172522);
        canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(6),dp(6),paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1,dp(1)));
        for(int i=0;i<=4;i++){
            float p=i/4f;
            paint.setColor(i==0||i==4?0xff738278:0xff334640);
            canvas.drawLine(x(p),top,x(p),bottom,paint);
            canvas.drawLine(left,y(p),right,y(p),paint);
        }
        paint.setColor(0xff7aa4ad);
        canvas.drawLine(x(0),y(0),x(controls[0]),y(controls[1]),paint);
        canvas.drawLine(x(1),y(1),x(controls[2]),y(controls[3]),paint);
        Path curvePath=new Path();
        JSONArray sampledHandles=curve();
        for(int i=0;i<=SAMPLES;i++){
            double time=i/(double)SAMPLES,progress=time;
            try{progress=SketchEasing.sample("bezier",sampledHandles,time);}
            catch(Exception invalid){progress=time;}
            if(i==0)curvePath.moveTo(x(time),y(progress));
            else curvePath.lineTo(x(time),y(progress));
        }
        paint.setColor(0xffc8e6d9);paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(curvePath,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10));paint.setColor(0xffa5b7ab);
        canvas.drawText("0",left-dp(14),bottom+dp(6),paint);
        canvas.drawText("1",left-dp(14),top+dp(4),paint);
        canvas.drawText("TIME  →",Math.max(left,right-dp(49)),bottom+dp(19),paint);
        for(int i=0;i<2;i++){
            paint.setColor(grabbed==i?0xffffffff:0xff71d2db);
            canvas.drawCircle(x(controls[2*i]),y(controls[2*i+1]),dp(11),paint);
            paint.setColor(0xff122423);
            canvas.drawCircle(x(controls[2*i]),y(controls[2*i+1]),dp(4),paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        bounds();
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                int chosen=-1;float closest=Float.MAX_VALUE;
                for(int i=0;i<2;i++){
                    float dx=x(controls[2*i])-event.getX(),
                            dy=y(controls[2*i+1])-event.getY();
                    float distance=(float)Math.hypot(dx,dy);
                    if(distance<closest){closest=distance;chosen=i;}
                }
                if(closest>dp(38))return false;
                grabbed=chosen;pressed=true;
                oldX=controls[chosen*2];oldY=controls[chosen*2+1];
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(!pressed||grabbed<0)return false;
                controls[grabbed*2]=fx(event.getX());
                controls[grabbed*2+1]=fy(event.getY());
                if(listener!=null)listener.accept(curve());
                invalidate();return true;
            }
            case MotionEvent.ACTION_UP:
                if(!pressed)return false;
                pressed=false;grabbed=-1;invalidate();performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                if(!pressed)return false;
                controls[grabbed*2]=oldX;controls[grabbed*2+1]=oldY;
                pressed=false;grabbed=-1;
                if(listener!=null)listener.accept(curve());
                invalidate();return true;
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
