package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import org.json.JSONArray;

/**
 * Foreground-only proof surface: renders and visibly drags the exact authored
 * timing curve. It is not an animated screenshot or a fake Android MotionEvent.
 *
 * The native drawing is saved only by Sketch Studio's normal sketch_edit flow
 * after this view has reached the requested handle target on screen.
 */
public final class SketchAgentCurveCanvas extends View {
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SketchAgentCurveDrag.Plan plan;
    private JSONArray displayed;
    private int visibleDraws;
    private float fraction;
    private float l,t,r,b;
    private boolean released;

    public SketchAgentCurveCanvas(Context context,SketchAgentCurveDrag.Plan source)
            throws Exception {
        super(context);
        if(source==null)throw new IllegalArgumentException("An admitted native curve plan is required");
        plan=source;
        displayed=plan.sample(0);
        setContentDescription("ChatGPT is visibly dragging the real "+
                plan.mode+" Bézier timing handle at frame "+(plan.frameIndex+1));
        setBackgroundColor(0xee172629);
        setClickable(false);setFocusable(false);
        setMinimumWidth(dp(228));setMinimumHeight(dp(226));
    }
    private int dp(int amount){return StudioUi.dp(getContext(),amount);}

    public void present(float progress)throws Exception {
        if(released)throw new IllegalStateException("Timing curve view is closed");
        if(!Float.isFinite(progress))throw new IllegalArgumentException("Finite progress required");
        fraction=Math.max(0,Math.min(1,progress));
        displayed=plan.sample(fraction);
        invalidate();
    }
    public JSONArray displayedCurve()throws Exception {
        return new JSONArray(displayed.toString());
    }
    public boolean atTarget()throws Exception {
        return plan.matchesTarget(displayed);
    }
    public int actualDraws(){return visibleDraws;}
    public void close(){released=true;setVisibility(GONE);}

    private float px(double x){return l+(float)x*(r-l);}
    private float py(double y){return b-(float)y*(b-t);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        // Offscreen Canvas unit tests may render this view without an Activity.
        // Real stage success is independently gated on view.isShown() in SketchStudioActivity.
        if(released)return;
        l=dp(28);t=dp(43);r=Math.max(l+1,getWidth()-dp(18));
        b=Math.max(t+1,getHeight()-dp(27));
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(0xff223c40);
        canvas.drawRoundRect(new RectF(dp(7),dp(7),getWidth()-dp(7),getHeight()-dp(7)),
                dp(8),dp(8),pen);
        pen.setColor(0xffe3f6ec);pen.setTextSize(dp(11));
        canvas.drawText("ChatGPT  ·  Bézier timing",dp(16),dp(24),pen);
        pen.setTextSize(dp(9));
        pen.setColor(0xffa3bbb4);
        canvas.drawText(plan.mode+" · key "+(plan.frameIndex+1)+" · "+
                Math.round(fraction*100)+"%",dp(16),dp(37),pen);

        pen.setStrokeWidth(dp(1));
        for(int i=0;i<=4;i++){
            float x=px(i/4.0);
            pen.setColor(i==0||i==4?0xff81988c:0xff47605b);
            canvas.drawLine(x,t,x,b,pen);
            canvas.drawLine(l,py(i/4.0),r,py(i/4.0),pen);
        }
        try{
            double x1=displayed.getDouble(0),y1=displayed.getDouble(1),
                    x2=displayed.getDouble(2),y2=displayed.getDouble(3);
            pen.setColor(0xff4a8c93);pen.setStrokeWidth(dp(1));
            canvas.drawLine(px(0),py(0),px(x1),py(y1),pen);
            canvas.drawLine(px(1),py(1),px(x2),py(y2),pen);

            Path curve=new Path();
            for(int i=0;i<=64;i++){
                double time=i/64.0;
                double eased=SketchEasing.sample("bezier",displayed,time);
                if(i==0)curve.moveTo(px(time),py(eased));
                else curve.lineTo(px(time),py(eased));
            }
            pen.setColor(0xff7ae5d9);pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(dp(2));pen.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(curve,pen);
            pen.setStyle(Paint.Style.FILL);
            for(int i=0;i<2;i++){
                pen.setColor(i==plan.handleIndex?0xfff6fcf8:0xff89aabd);
                canvas.drawCircle(px(displayed.getDouble(i*2)),
                        py(displayed.getDouble(i*2+1)),dp(i==plan.handleIndex?7:5),pen);
            }
            double cursorX=displayed.getDouble(plan.handleIndex*2);
            double cursorY=displayed.getDouble(plan.handleIndex*2+1);
            SketchAgentHand.State hand=SketchAgentHand.State.manipulation(
                    cursorX,cursorY,plan.targetId,"Timing Bézier",
                    "Moving real animation handle");
            SketchAgentHand.draw(canvas,hand,px(cursorX),py(cursorY),dp(24));
            pen.setTextSize(dp(9));pen.setColor(Color.WHITE);
            canvas.drawText("Source unchanged until Save",dp(13),
                    Math.max(dp(12),getHeight()-dp(7)),pen);
            visibleDraws++;
        }catch(Exception invalid){
            pen.setColor(0xffffa1a1);pen.setTextSize(dp(10));
            canvas.drawText("Timing curve unavailable",dp(12),b-dp(8),pen);
        }
    }
}
