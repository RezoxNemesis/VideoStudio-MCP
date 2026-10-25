package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

/**
 * Native, touch-editable Sketch frame-timing graph. Unlike an imitation of
 * the easing curve, every pixel of the path is sampled through SketchEasing,
 * the exact evaluator used for saved vector and shape animation keyframes.
 *
 * The graph only changes an unsaved draft; the original editor's revision-
 * checked set_keyframe/set_shape_key operation remains the commit boundary.
 */
public final class SketchTimingCurveView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] handles={.42f,0f,1f,1f};
    private Consumer<JSONArray> change;
    private int selected=-1;
    private float initialX,initialY;
    private float left,right,top,bottom;
    private static final int SAMPLES=52;

    public SketchTimingCurveView(Context context){
        super(context);
        setMinimumHeight(dp(218));
        setMinimumWidth(dp(180));
        setClickable(true);
        setContentDescription("Animation timing graph, drag either curve handle or use exact values");
    }

    private int dp(int n){return StudioUi.dp(getContext(),n);}
    private void viewport(){
        left=dp(30);right=Math.max(left+1,getWidth()-dp(18));
        top=dp(16);bottom=Math.max(top+1,getHeight()-dp(25));
    }
    public void setOnDraftChanged(Consumer<JSONArray> callback){change=callback;}

    public void setCurve(JSONArray coordinates){
        double[] valid=verified(coordinates);
        for(int i=0;i<4;i++)handles[i]=(float)valid[i];
        invalidate();
    }

    public JSONArray curve(){
        JSONArray array=new JSONArray();
        for(float h:handles)array.put((Object)Double.valueOf(h));
        return array;
    }

    public static double[] verified(JSONArray array){
        if(array==null||array.length()!=4)
            throw new IllegalArgumentException("Sketch timing curve requires four finite values");
        double[] d=new double[4];
        for(int i=0;i<4;i++){
            Object raw=array.opt(i);
            if(!(raw instanceof Number))
                throw new IllegalArgumentException("Timing handles must be numeric");
            d[i]=((Number)raw).doubleValue();
            if(!Double.isFinite(d[i])||d[i]<0||d[i]>1)
                throw new IllegalArgumentException("Sketch curve handles must be in [0,1]");
        }
        return d;
    }

    public static JSONArray parse(String commaSeparated){
        if(commaSeparated==null)throw new IllegalArgumentException("Curve values required");
        String[] tokens=commaSeparated.split(",");
        if(tokens.length!=4)throw new IllegalArgumentException("Enter exactly x1,y1,x2,y2");
        JSONArray result=new JSONArray();
        try{
            for(String token:tokens){
                double number=Double.parseDouble(token.trim());
                if(!Double.isFinite(number))throw new NumberFormatException("nonfinite");
                result.put((Object)Double.valueOf(number));
            }
        }catch(NumberFormatException invalid){
            throw new IllegalArgumentException("Curve controls require finite decimal numbers",invalid);
        }
        verified(result);
        return result;
    }

    public static String format(JSONArray array){
        double[] v=verified(array);
        return v[0]+", "+v[1]+", "+v[2]+", "+v[3];
    }

    private float px(double x){return (float)(left+x*(right-left));}
    private float py(double y){return (float)(bottom-y*(bottom-top));}
    private float nx(float px){return Math.max(0,Math.min(1,(px-left)/(right-left)));}
    private float ny(float py){return Math.max(0,Math.min(1,(bottom-py)/(bottom-top)));}

    public float[] handleCenter(int index){
        if(index<0||index>1)throw new IllegalArgumentException("Only two motion handles exist");
        viewport();
        return new float[]{px(handles[2*index]),py(handles[2*index+1])};
    }

    @Override protected void onMeasure(int w,int h){
        setMeasuredDimension(resolveSize(dp(280),w),resolveSize(dp(218),h));
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);viewport();
        paint.setColor(0xff1b2b35);paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(6),dp(6),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));
        paint.setColor(0xff405464);
        for(int i=0;i<=4;i++){
            float v=i/4f;
            canvas.drawLine(px(v),top,px(v),bottom,paint);
            canvas.drawLine(left,py(v),right,py(v),paint);
        }
        paint.setColor(0xff7797a3);paint.setStrokeWidth(dp(1));
        canvas.drawLine(px(0),py(0),px(handles[0]),py(handles[1]),paint);
        canvas.drawLine(px(1),py(1),px(handles[2]),py(handles[3]),paint);
        Path sampled=new Path();
        JSONArray source=curve();
        for(int i=0;i<=SAMPLES;i++){
            double t=i/(double)SAMPLES,value;
            try{value=SketchEasing.sample("bezier",source,t);}
            catch(Exception bad){value=t;}
            if(i==0)sampled.moveTo(px(t),py(value));
            else sampled.lineTo(px(t),py(value));
        }
        paint.setColor(0xffa3e4d6);paint.setStrokeWidth(dp(3));
        canvas.drawPath(sampled,paint);
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<2;i++){
            paint.setColor(i==selected?0xffffffff:0xff63c7ed);
            canvas.drawCircle(px(handles[2*i]),py(handles[2*i+1]),dp(10),paint);
        }
        paint.setColor(0xffb7c9d4);paint.setTextSize(dp(10));
        canvas.drawText("VALUE",dp(2),top+dp(10),paint);
        canvas.drawText("TIME →",right-dp(49),bottom+dp(18),paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        viewport();
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                float best=Float.MAX_VALUE;int hit=-1;
                for(int i=0;i<2;i++){
                    float dist=(float)Math.hypot(event.getX()-px(handles[2*i]),
                            event.getY()-py(handles[2*i+1]));
                    if(dist<best){best=dist;hit=i;}
                }
                if(best>dp(32))return false;
                selected=hit;
                initialX=handles[2*hit];initialY=handles[2*hit+1];
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(selected<0)return false;
                float x=nx(event.getX()),y=ny(event.getY());
                handles[2*selected]=x;handles[2*selected+1]=y;
                if(change!=null)change.accept(curve());
                invalidate();return true;
            }
            case MotionEvent.ACTION_UP:{
                if(selected<0)return false;
                selected=-1;invalidate();performClick();return true;
            }
            case MotionEvent.ACTION_CANCEL:{
                if(selected<0)return false;
                handles[2*selected]=initialX;handles[2*selected+1]=initialY;
                selected=-1;if(change!=null)change.accept(curve());
                invalidate();return true;
            }
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
