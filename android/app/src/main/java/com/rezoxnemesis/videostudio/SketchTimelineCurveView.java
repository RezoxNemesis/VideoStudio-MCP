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
 * Real timeline Bézier editor for Sketch Studio transform/shape keys.
 * Both handle axes are [0,1] because SketchEasing and the retained-document
 * contract use that same range. Draft positions are never written to a
 * sketch until the human/agent selects the owning dialog's Apply action.
 */
public final class SketchTimelineCurveView extends View {
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double[] handles={.42,0,1,1};
    private Consumer<JSONArray> listener;
    private int moving=-1;
    private float x0,y0,x1,y1,oldX,oldY;
    private boolean altered;

    public SketchTimelineCurveView(Context context){
        super(context);
        setContentDescription("Native sketch timing curve. Drag two control handles or enter exact coordinates.");
        setMinimumHeight(dp(205));
        setFocusable(true);setClickable(true);
    }
    public void setListener(Consumer<JSONArray> listener){this.listener=listener;}
    public void setCurve(JSONArray curve){
        if(curve==null||curve.length()!=4)
            throw SketchDocument.bad("Bezier timing requires exactly four handles");
        for(int i=0;i<4;i++)handles[i]=SketchDocument.scalar(curve.opt(i),0,1,"Bezier control");
        invalidate();
    }
    public JSONArray curve(){
        JSONArray out=new JSONArray();
        for(double v:handles)out.put((Object)Double.valueOf(v));
        return out;
    }
    public PointF handlePoint(int index){
        if(index<0||index>1)throw SketchDocument.bad("Curve handle is outside native view");
        box();
        return new PointF(toX(handles[index*2]),toY(handles[index*2+1]));
    }
    private int dp(int n){return StudioUi.dp(getContext(),n);}
    private void box(){
        x0=dp(34);x1=Math.max(x0+1,getWidth()-dp(20));
        y0=dp(18);y1=Math.max(y0+1,getHeight()-dp(28));
    }
    private float toX(double t){return (float)(x0+(x1-x0)*t);}
    private float toY(double t){return (float)(y1-(y1-y0)*t);}
    private double fromX(float x){return Math.max(0,Math.min(1,(x-x0)/(x1-x0)));}
    private double fromY(float y){return Math.max(0,Math.min(1,(y1-y)/(y1-y0)));}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        setMeasuredDimension(resolveSize(dp(240),widthSpec),resolveSize(dp(205),heightSpec));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);box();
        brush.setStyle(Paint.Style.FILL);brush.setColor(0xff14231e);
        canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(6),dp(6),brush);
        brush.setStrokeWidth(Math.max(1,dp(1)));
        for(int i=0;i<=4;i++){
            float f=i/4f;brush.setColor(0xff344b40);
            canvas.drawLine(toX(f),y0,toX(f),y1,brush);
            canvas.drawLine(x0,toY(f),x1,toY(f),brush);
        }
        brush.setColor(0xff7e9991);brush.setTextSize(dp(10));
        canvas.drawText("0",x0-dp(16),y1+dp(4),brush);
        canvas.drawText("1",x0-dp(16),y0+dp(4),brush);
        canvas.drawText("TIME →",x1-dp(48),y1+dp(19),brush);
        brush.setColor(0xff6799a5);brush.setStrokeWidth(dp(1));
        canvas.drawLine(toX(0),toY(0),toX(handles[0]),toY(handles[1]),brush);
        canvas.drawLine(toX(1),toY(1),toX(handles[2]),toY(handles[3]),brush);
        Path path=new Path();
        JSONArray key=curve();
        for(int i=0;i<=64;i++){
            double t=i/64.0;
            double sample;
            try{sample=SketchEasing.sample("bezier",key,t);}
            catch(Exception invalid){return;}
            float x=toX(t),y=toY(sample);
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        brush.setStyle(Paint.Style.STROKE);brush.setStrokeWidth(dp(3));
        brush.setStrokeCap(Paint.Cap.ROUND);brush.setColor(0xff6aedd0);
        canvas.drawPath(path,brush);brush.setStyle(Paint.Style.FILL);
        for(int i=0;i<2;i++){
            float x=toX(handles[i*2]),y=toY(handles[i*2+1]);
            brush.setColor(i==moving?0xffffffff:0xff74ccea);
            canvas.drawCircle(x,y,dp(10),brush);
            brush.setColor(0xff122521);canvas.drawCircle(x,y,dp(4),brush);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        box();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                float closest=Float.MAX_VALUE;int index=-1;
                for(int i=0;i<2;i++){
                    float d=(float)Math.hypot(e.getX()-toX(handles[2*i]),
                            e.getY()-toY(handles[2*i+1]));
                    if(d<closest){closest=d;index=i;}
                }
                if(closest>dp(34))return false;
                moving=index;altered=false;
                oldX=(float)handles[index*2];oldY=(float)handles[index*2+1];
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();return true;
            }
            case MotionEvent.ACTION_MOVE:
                if(moving<0)return false;
                double nx=fromX(e.getX()),ny=fromY(e.getY());
                handles[2*moving]=nx;handles[2*moving+1]=ny;
                altered=altered||Math.abs(nx-oldX)>.0001||Math.abs(ny-oldY)>.0001;
                if(altered&&listener!=null)listener.accept(curve());
                invalidate();return true;
            case MotionEvent.ACTION_UP:
                if(moving<0)return false;
                moving=-1;invalidate();performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                if(moving<0)return false;
                handles[2*moving]=oldX;handles[2*moving+1]=oldY;
                moving=-1;if(listener!=null)listener.accept(curve());
                invalidate();return true;
            default:return super.onTouchEvent(e);
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
