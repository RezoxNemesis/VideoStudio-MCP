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
import java.util.HashMap;
import java.util.Map;

/**
 * A touch-operable two-lane timeline. Touch the empty ruler to seek; drag a
 * colored music/title bar to move it, or grab its right handle to trim it.
 * The renderer only sees saved project timing after the gesture finishes.
 */
public final class StudioTracksView extends View {
    public interface SeekListener {void onSeek(long positionMs);}
    public interface ChangeListener {
        void onChange(String layerId,long startMs,long durationMs);
        void onSelect(String layerId);
    }
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<StudioLayer> layers=new ArrayList<>();
    private final Map<String,float[]> waveforms=new HashMap<>();
    private long durationMs=1,playheadMs=0;
    private SeekListener callback;
    private ChangeListener change;
    private String draggingId="";
    private boolean resize=false,dragChanged=false;
    private float downX=0;
    private long originalStart,originalDuration,previewStart,previewDuration;
    private static final int MIN_DURATION=100;

    public StudioTracksView(Context context){super(context);setMinimumHeight(dp(135));}
    public void setTimeline(long duration,long position,List<StudioLayer> content,
                            SeekListener callback){
        setTimeline(duration,position,content,callback,null);
    }
    public void setTimeline(long duration,long position,List<StudioLayer> content,
                            SeekListener callback,ChangeListener change){
        durationMs=Math.max(1,duration);
        playheadMs=Math.max(0,Math.min(durationMs,position));
        layers.clear();if(content!=null)layers.addAll(content);
        this.callback=callback;
        this.change=change;
        draggingId="";resize=false;
        invalidate();
    }
    public void setWaveform(String laneId,float[] amplitudes){
        if(laneId==null||amplitudes==null||amplitudes.length==0)return;
        waveforms.put(laneId,amplitudes.clone());invalidate();
    }
    private int dp(int value){return Math.round(getResources().getDisplayMetrics().density*value);}
    private float begin(){return dp(56);}
    private float available(){return Math.max(1,getWidth()-dp(66));}
    private long delta(float pixels){
        return Math.round(pixels/available()*durationMs);
    }
    private float x(long ms){
        return begin()+available()*Math.max(0,Math.min(1,ms/(float)durationMs));
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int height=getHeight();
        canvas.drawColor(0xff0b1222);
        float left=begin(),right=Math.max(left+4,getWidth()-dp(10));
        float audioY=dp(24),titleY=dp(76),barHeight=dp(33);
        brush.setStyle(Paint.Style.FILL);
        brush.setColor(0xffb4bacc);
        brush.setTextSize(dp(10));
        canvas.drawText("MUSIC",dp(5),audioY+dp(20),brush);
        canvas.drawText("TEXT",dp(8),titleY+dp(20),brush);
        brush.setColor(0xff1a2840);
        canvas.drawRoundRect(new RectF(left,audioY,right,audioY+barHeight),dp(6),dp(6),brush);
        canvas.drawRoundRect(new RectF(left,titleY,right,titleY+barHeight),dp(6),dp(6),brush);

        for(StudioLayer lane:layers){
            long start=lane.id.equals(draggingId)?previewStart:lane.startMs;
            long length=lane.id.equals(draggingId)?previewDuration:lane.durationMs;
            float x=x(start),x2=x(start+length);
            if(x2-x<dp(3))x2=Math.min(right,x+dp(3));
            boolean audio=StudioLayer.AUDIO.equals(lane.type);
            float top=audio?audioY:titleY;
            brush.setColor(audio?0xff188fcb:0xffa13bdf);
            canvas.drawRoundRect(new RectF(x,top,x2,top+barHeight),dp(5),dp(5),brush);
            brush.setColor(0xffffffff);
            brush.setTextSize(dp(10));
            String label=audio?"♪ Music":(lane.text==null?"Text":lane.text);
            int maxChars=Math.max(1,(int)((x2-x-dp(10))/dp(6)));
            if(label.length()>maxChars)label=label.substring(0,maxChars);
            canvas.save();
            canvas.clipRect(x,top,x2,top+barHeight);
            canvas.drawText(label,x+dp(5),top+dp(21),brush);
            canvas.restore();
            if(audio){
                float[] peaks=waveforms.get(lane.id);
                if(peaks!=null&&peaks.length>0){
                    brush.setColor(0xbbd6eeff);
                    brush.setStrokeWidth(Math.max(1,dp(1)));
                    float mid=top+barHeight*.60f;
                    for(int sample=0;sample<peaks.length;sample++){
                        float bx=x+dp(2)+(x2-x-dp(4))*(sample+.5f)/peaks.length;
                        float size=Math.min(barHeight*.26f,
                                Math.max(0f,peaks[sample])*barHeight*.26f);
                        canvas.drawLine(bx,mid-size,bx,mid+size,brush);
                    }
                }
            }
            brush.setColor(0x99ffffff);
            canvas.drawRect(x2-dp(5),top+dp(6),x2-dp(3),top+barHeight-dp(6),brush);
            if(lane.id.equals(draggingId)){
                brush.setColor(Color.WHITE);
                brush.setStyle(Paint.Style.STROKE);
                brush.setStrokeWidth(dp(2));
                canvas.drawRoundRect(new RectF(x,top,x2,top+barHeight),dp(5),dp(5),brush);
                brush.setStyle(Paint.Style.FILL);
            }
        }
        float play=x(playheadMs);
        brush.setColor(0xffffffff);brush.setStrokeWidth(dp(2));
        canvas.drawLine(play,dp(10),play,height-dp(7),brush);
        brush.setColor(0xff22d7ff);
        canvas.drawCircle(play,dp(7),dp(4),brush);
        brush.setTextSize(dp(9));
        brush.setColor(0xff8d9dba);
        canvas.drawText("Drag colored bars to move • right edge to trim",dp(8),
                height-dp(5),brush);
    }

    private StudioLayer hit(float px,float py){
        for(int i=layers.size()-1;i>=0;i--){
            StudioLayer lane=layers.get(i);
            boolean audio=StudioLayer.AUDIO.equals(lane.type);
            float top=audio?dp(24):dp(76),bottom=top+dp(33);
            if(py>=top&&py<=bottom&&px>=x(lane.startMs)-dp(5)&&
                    px<=x(lane.startMs+lane.durationMs)+dp(5))return lane;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{
                StudioLayer selected=hit(e.getX(),e.getY());
                downX=e.getX();dragChanged=false;
                if(selected!=null&&change!=null){
                    draggingId=selected.id;
                    originalStart=selected.startMs;
                    originalDuration=selected.durationMs;
                    previewStart=originalStart;
                    previewDuration=originalDuration;
                    resize=e.getX()>=x(originalStart+originalDuration)-dp(13);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                draggingId="";
                playheadMs=Math.round(Math.max(0,Math.min(1,
                        (e.getX()-begin())/available()))*durationMs);
                invalidate();
                if(callback!=null)callback.onSeek(playheadMs);
                return true;
            }
            case MotionEvent.ACTION_MOVE:{
                if(!draggingId.isEmpty()){
                    long shift=delta(e.getX()-downX);
                    StudioLayerTouchMath.Timing p=resize
                            ?StudioLayerTouchMath.trimRight(durationMs,
                                    originalStart,originalDuration,shift)
                            :StudioLayerTouchMath.move(durationMs,
                                    originalStart,originalDuration,shift);
                    previewStart=p.startMs;previewDuration=p.durationMs;
                    dragChanged=dragChanged||Math.abs(e.getX()-downX)>dp(5);
                    invalidate();
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:{
                if(!draggingId.isEmpty()){
                    String selected=draggingId;
                    long start=previewStart,duration=previewDuration;
                    draggingId="";
                    if(change!=null){
                        if(dragChanged)change.onChange(selected,start,duration);
                        else change.onSelect(selected);
                    }
                    invalidate();
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                draggingId="";invalidate();return true;
            default:return true;
        }
    }
}
