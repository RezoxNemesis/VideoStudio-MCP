package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Small responsive timeline lane surface with independently positioned audio
 * and caption bars. Touching any point seeks the actual editing playhead.
 */
public final class StudioTracksView extends View {
    public interface SeekListener {void onSeek(long positionMs);}
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<StudioLayer> layers=new ArrayList<>();
    private long durationMs=1,playheadMs=0;
    private SeekListener callback;
    public StudioTracksView(Context context){super(context);setMinimumHeight(dp(110));}
    public void setTimeline(long duration,long position,List<StudioLayer> content,
                            SeekListener callback){
        durationMs=Math.max(1,duration);
        playheadMs=Math.max(0,Math.min(durationMs,position));
        layers.clear();if(content!=null)layers.addAll(content);
        this.callback=callback;
        invalidate();
    }
    private int dp(int value){return Math.round(getResources().getDisplayMetrics().density*value);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int width=getWidth(),height=getHeight();
        canvas.drawColor(0xff0b1222);
        float left=dp(53),right=Math.max(left+4,width-dp(9));
        float w=right-left;
        float audioY=dp(20),titleY=dp(68),barHeight=dp(29);
        brush.setStyle(Paint.Style.FILL);
        brush.setColor(0xffb4bacc);
        brush.setTextSize(dp(10));
        canvas.drawText("AUDIO",dp(5),audioY+dp(19),brush);
        canvas.drawText("TITLES",dp(5),titleY+dp(19),brush);
        brush.setColor(0xff1a2840);
        canvas.drawRoundRect(new RectF(left,audioY,right,audioY+barHeight),dp(6),dp(6),brush);
        canvas.drawRoundRect(new RectF(left,titleY,right,titleY+barHeight),dp(6),dp(6),brush);
        for(StudioLayer lane:layers){
            float x=left+w*Math.max(0,Math.min(1,lane.startMs/(float)durationMs));
            float x2=left+w*Math.max(0,Math.min(1,(lane.startMs+lane.durationMs)/(float)durationMs));
            if(x2-x<dp(3))x2=Math.min(right,x+dp(3));
            boolean audio=StudioLayer.AUDIO.equals(lane.type);
            float top=audio?audioY:titleY;
            brush.setColor(audio?0xff188fcb:0xffa13bdf);
            canvas.drawRoundRect(new RectF(x,top,x2,top+barHeight),dp(5),dp(5),brush);
            brush.setColor(0xffffffff);
            brush.setTextSize(dp(10));
            String label=audio?"♪":(lane.text==null?"T":lane.text);
            int maxChars=Math.max(1,(int)((x2-x)/dp(7)));
            if(label.length()>maxChars)label=label.substring(0,maxChars);
            canvas.save();canvas.clipRect(x,top,x2,top+barHeight);
            canvas.drawText(label,x+dp(4),top+dp(20),brush);
            canvas.restore();
        }
        float play=left+w*playheadMs/(float)durationMs;
        brush.setColor(0xffffffff);brush.setStrokeWidth(dp(2));
        canvas.drawLine(play,dp(7),play,height-dp(5),brush);
        brush.setColor(0xff22d7ff);
        canvas.drawCircle(play,dp(6),dp(4),brush);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_DOWN||
           event.getAction()==MotionEvent.ACTION_MOVE){
            float x=(event.getX()-dp(53))/
                    (float)Math.max(1,getWidth()-dp(62));
            long p=Math.round(Math.max(0,Math.min(1,x))*durationMs);
            playheadMs=p;invalidate();
            if(event.getAction()==MotionEvent.ACTION_DOWN&&callback!=null)
                callback.onSeek(p);
            return true;
        }
        return true;
    }
}
