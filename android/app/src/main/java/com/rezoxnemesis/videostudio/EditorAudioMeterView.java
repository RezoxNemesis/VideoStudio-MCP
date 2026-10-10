package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual per-binding decoded PCM peaks. This is not a master bus, LUFS or
 * oversampled true-peak measurement. No waveform/cache data drives these bars. */
public final class EditorAudioMeterView extends View {
    public static final class Level {
        public final String label;
        public final float left, right;
        public final boolean limited;
        public Level(String label,float left,float right,boolean limited) {
            this.label=label;this.left=Math.max(0,Math.min(1,left));this.right=Math.max(0,Math.min(1,right));this.limited=limited;
        }
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Level> levels=Collections.emptyList();
    public EditorAudioMeterView(Context context) { super(context);setContentDescription("Decoded audio peak meters in dBFS"); }
    public void setLevels(List<Level> next) {
        levels=next==null?Collections.emptyList():new ArrayList<>(next.subList(0,Math.min(6,next.size())));
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        if (levels.isEmpty()) {
            paint.setColor(Color.rgb(153,164,181));paint.setTextSize(11*density);
            canvas.drawText("Audio peaks · play to monitor",8*density,17*density,paint);return;
        }
        float row=getHeight()/(float)levels.size(),labelWidth=Math.min(getWidth()*.38f,150*density);
        float start=labelWidth+8*density,end=getWidth()-40*density,width=Math.max(0,end-start);
        for (int i=0;i<levels.size();i++) {
            Level level=levels.get(i);float top=i*row,barHeight=Math.min(6*density,row*.18f);
            paint.setTextSize(Math.min(11*density,row*.35f));paint.setColor(Color.rgb(211,219,230));
            String label=level.label==null?"Audio":level.label;
            while (label.length()>1&&paint.measureText(label)>labelWidth-8*density) label=label.substring(0,label.length()-1);
            canvas.drawText(label,8*density,top+row*.58f,paint);
            bar(canvas,start,top+row*.22f,width,barHeight,level.left);
            bar(canvas,start,top+row*.55f,width,barHeight,level.right);
            paint.setColor(level.limited?Color.rgb(255,92,82):Color.rgb(153,164,181));
            float peak=Math.max(level.left,level.right);
            String db=peak<=.001f?"−∞":String.format(java.util.Locale.US,"%.0f",20*Math.log10(peak));
            canvas.drawText(db,end+5*density,top+row*.58f,paint);
        }
    }
    private void bar(Canvas canvas,float left,float top,float width,float height,float peak) {
        paint.setColor(Color.rgb(38,47,61));canvas.drawRect(left,top,left+width,top+height,paint);
        float fraction=peak<=.001f?0:(float)Math.max(0,Math.min(1,(20*Math.log10(peak)+60)/60));
        paint.setColor(peak>.98f?Color.rgb(255,92,82):peak>.7f?Color.rgb(248,192,72):Color.rgb(77,218,155));
        canvas.drawRect(left,top,left+width*fraction,top+height,paint);
    }
}
