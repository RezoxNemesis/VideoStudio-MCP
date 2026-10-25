package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import java.util.function.Consumer;

/** Editable non-destructive four-corner projective geometry with touch handles. */
public final class StudioCornerPinTouchView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] xy={0,0,1,0,1,1,0,1};
    private Consumer<JSONArray> changed;
    private int selected=-1;
    private float originalX,originalY;
    private float left,top,right,bottom;

    public StudioCornerPinTouchView(Context context){
        super(context);
        setMinimumHeight(dp(270));
        setClickable(true);setFocusable(true);
        setContentDescription("Drag the four corners to reshape a source image into a 2D perspective quad");
    }
    private int dp(int value){return StudioUi.dp(getContext(),value);}
    private void bounds(){
        left=dp(24);top=dp(24);
        right=Math.max(left+1,getWidth()-dp(24));
        bottom=Math.max(top+1,getHeight()-dp(24));
    }
    private float px(float x){return left+(right-left)*x;}
    private float py(float y){return top+(bottom-top)*y;}
    private float fractionX(float x){return Math.max(0,Math.min(1,(x-left)/(right-left)));}
    private float fractionY(float y){return Math.max(0,Math.min(1,(y-top)/(bottom-top)));}
    public void onChange(Consumer<JSONArray> callback){changed=callback;}
    public void setCorners(JSONArray corners){
        JSONArray validated=StudioCornerPinRasterizer.validate(corners);
        for(int i=0;i<8;i++)xy[i]=(float)validated.optDouble(i);
        invalidate();
    }
    public JSONArray corners(){
        JSONArray out=new JSONArray();
        for(float value:xy)out.put((Object)Double.valueOf(value));
        return out;
    }
    public String numericCorners(){
        StringBuilder s=new StringBuilder();
        for(int i=0;i<xy.length;i++){
            if(i>0)s.append(',');
            s.append(xy[i]);
        }
        return s.toString();
    }
    @Override protected void onMeasure(int wSpec,int hSpec){
        setMeasuredDimension(resolveSize(dp(300),wSpec),resolveSize(dp(270),hSpec));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        bounds();
        p.setColor(StudioEditorWorkspace.PANEL);p.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(8),dp(8),p);
        p.setStrokeWidth(dp(1));
        for(int i=0;i<=8;i++){
            float t=i/8f;
            p.setColor(i%2==0?0xff53635c:StudioEditorWorkspace.RULE);
            canvas.drawLine(px(t),top,px(t),bottom,p);
            canvas.drawLine(left,py(t),right,py(t),p);
        }
        Path path=new Path();
        path.moveTo(px(xy[0]),py(xy[1]));
        for(int i=1;i<4;i++)path.lineTo(px(xy[i*2]),py(xy[i*2+1]));
        path.close();
        p.setColor(0x2949cce7);p.setStyle(Paint.Style.FILL);
        canvas.drawPath(path,p);
        p.setColor(StudioEditorWorkspace.SAGE);p.setStrokeWidth(dp(3));
        p.setStyle(Paint.Style.STROKE);canvas.drawPath(path,p);p.setStyle(Paint.Style.FILL);
        for(int i=0;i<4;i++){
            float x=px(xy[2*i]),y=py(xy[2*i+1]);
            p.setColor(i==selected?0xffffffff:0xff48d9f4);
            canvas.drawCircle(x,y,dp(11),p);
            p.setColor(StudioEditorWorkspace.BG);
            canvas.drawCircle(x,y,dp(5),p);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        bounds();
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                int nearest=-1;float minimum=Float.MAX_VALUE;
                for(int i=0;i<4;i++){
                    float x=px(xy[i*2]),y=py(xy[i*2+1]);
                    float distance=(float)Math.hypot(event.getX()-x,event.getY()-y);
                    if(distance<minimum){minimum=distance;nearest=i;}
                }
                if(minimum>dp(36))return false;
                selected=nearest;originalX=xy[2*selected];originalY=xy[2*selected+1];
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(selected<0)return false;
                float x=fractionX(event.getX()),y=fractionY(event.getY());
                JSONArray proposal=corners();
                try{
                    proposal.put(selected*2,(double)x);
                    proposal.put(selected*2+1,(double)y);
                    StudioCornerPinRasterizer.validate(proposal);
                }catch(Exception unacceptable){return true;}
                xy[selected*2]=x;xy[selected*2+1]=y;
                if(changed!=null)changed.accept(corners());
                invalidate();return true;
            }
            case MotionEvent.ACTION_UP:{
                if(selected<0)return false;
                selected=-1;invalidate();performClick();return true;
            }
            case MotionEvent.ACTION_CANCEL:{
                if(selected<0)return false;
                xy[selected*2]=originalX;xy[selected*2+1]=originalY;
                selected=-1;if(changed!=null)changed.accept(corners());
                invalidate();return true;
            }
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
