package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Real foreground Xsheet exposure ruler. The authoring source is immutable
 * during gesture playback. The active held range is rendered from the admitted
 * source-cel start and exact exposure, not guessed source-video pixels.
 */
public final class SketchAgentExposureCanvas extends View {
    private final SketchAgentExposureDrag.Plan plan;
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int displayedExposure,realDraws;
    private float fraction;
    private boolean closed;
    public SketchAgentExposureCanvas(Context context,SketchAgentExposureDrag.Plan plan){
        super(context);
        if(plan==null)throw new IllegalArgumentException("Native Xsheet source plan required");
        this.plan=plan;
        displayedExposure=plan.fromExposure;
        setBackgroundColor(0xee14272d);
        setClickable(false);
        setFocusable(false);
        setContentDescription("ChatGPT is dragging real Xsheet exposure from "+
                plan.fromExposure+" to "+plan.toExposure+" frames at source frame "+
                (plan.frameIndex+1));
    }
    private int dp(int px){return StudioUi.dp(getContext(),px);}
    public void present(float position){
        if(closed)throw new IllegalStateException("Xsheet ruler no longer visible");
        displayedExposure=plan.sample(position);
        fraction=Math.max(0,Math.min(1,position));
        invalidate();
    }
    public int exposure(){return displayedExposure;}
    public int actualDraws(){return realDraws;}
    public void close(){closed=true;setVisibility(GONE);}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(closed)return;
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(0xff253a41);
        canvas.drawRoundRect(new RectF(dp(6),dp(6),getWidth()-dp(6),getHeight()-dp(6)),
                dp(7),dp(7),pen);
        pen.setTextSize(dp(11));pen.setColor(0xffeef9f3);
        canvas.drawText("ChatGPT  ·  Xsheet hold",dp(14),dp(23),pen);
        pen.setTextSize(dp(9));pen.setColor(0xffadcfc7);
        canvas.drawText("Source frame "+(plan.frameIndex+1)+"  •  "+
                plan.authoredObjectIds.length()+" original marks",dp(14),dp(40),pen);
        canvas.drawText("Held "+displayedExposure+"/"+plan.maxSafeExposure+
                " frames  •  "+Math.round(fraction*100)+"%",dp(14),dp(57),pen);
        float left=dp(17),right=Math.max(left+1,getWidth()-dp(17)),top=dp(83),bottom=dp(125);
        float span=right-left;
        float end=left+span*displayedExposure/(float)Math.max(1,plan.maxSafeExposure);
        pen.setColor(0xff334b53);
        canvas.drawRoundRect(new RectF(left,top,right,bottom),dp(5),dp(5),pen);
        pen.setColor(0xff63cec8);
        canvas.drawRoundRect(new RectF(left,top,end,bottom),dp(5),dp(5),pen);
        pen.setColor(0xff93aeb3);pen.setStrokeWidth(dp(1));
        for(int tick=0;tick<=12;tick++){
            float x=left+span*tick/12f;
            canvas.drawLine(x,top-dp(3),x,bottom+dp(3),pen);
        }
        pen.setColor(0xfff8fcf7);
        canvas.drawLine(end,top-dp(8),end,bottom+dp(8),pen);
        SketchAgentHand.State hand=SketchAgentHand.State.manipulation(
                displayedExposure/(double)Math.max(1,plan.maxSafeExposure),.5,
                plan.layerId,"Xsheet exposure","Moving held-cel endpoint");
        SketchAgentHand.draw(canvas,hand,end,bottom+dp(13),dp(25));
        pen.setColor(0xffc3e2dd);pen.setTextSize(dp(9));
        canvas.drawText("Drawings are held, not invented",dp(14),
                Math.min(getHeight()-dp(9),bottom+dp(52)),pen);
        realDraws++;
    }
}
