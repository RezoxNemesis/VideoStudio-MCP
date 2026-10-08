package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Time-scaled tracks with playhead selection and edge trim gestures. */
final class EditorTimelineView extends View {
    interface Events {void select(ProjectStore.Clip clip,long localMs);void trim(ProjectStore.Clip clip,long in,long out);}
    private final ProjectStore.Project project;private final Events events;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<ProjectStore.Clip,RectF> rectangles=new LinkedHashMap<>();
    private final Map<String,float[]> waveforms=new HashMap<>();
    private final Map<String,Bitmap> thumbnails=new HashMap<>();
    void waveform(String clip,float[] data){if(data!=null)waveforms.put(clip,data);invalidate();}
    void thumbnail(String asset,Bitmap image){if(image!=null)thumbnails.put(asset,image);invalidate();}
    float pixelsPerSecond=64;long playheadMs;String selected="";private ProjectStore.Clip touching;private float down;private int edge;
    EditorTimelineView(Context c,ProjectStore.Project project,Events events){super(c);this.project=project;this.events=events;setMinimumHeight(230);}
    void zoom(float value){pixelsPerSecond=value;requestLayout();invalidate();}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(Math.max(600,(int)(project.outputDurationMs()/1000f*pixelsPerSecond+90)),260);}
    @Override protected void onDraw(Canvas c){
        c.drawColor(Color.rgb(9,15,28));rectangles.clear();p.setTextSize(17);
        for(int t=0;t<4;t++){p.setColor(Color.rgb(34,43,63));c.drawLine(0,40+t*52,getWidth(),40+t*52,p);p.setColor(Color.LTGRAY);c.drawText(new String[]{"V1","V2","A1","V3"}[t],4,70+t*52,p);}
        for(int seconds=0;seconds*1000<=project.outputDurationMs();seconds++){float x=60+seconds*pixelsPerSecond;p.setColor(Color.GRAY);c.drawLine(x,22,x,35,p);if(seconds%Math.max(1,(int)(80/pixelsPerSecond))==0)c.drawText(seconds+"s",x,18,p);}
        long start=0;
        for(ProjectStore.Clip clip:project.clips){long at=clip.track==0?start:clip.timelineStartMs;if(clip.track==0)start+=clip.outputDurationMs();
            RectF r=new RectF(60+at/1000f*pixelsPerSecond,44+clip.track*52,60+(at+clip.outputDurationMs())/1000f*pixelsPerSecond,88+clip.track*52);rectangles.put(clip,r);
            p.setColor(clip.id.equals(selected)?Color.rgb(19,124,146):clip.track==2?Color.rgb(55,88,66):Color.rgb(60,49,101));c.drawRoundRect(r,7,7,p);
            Bitmap thumb=thumbnails.get(clip.assetId);if(thumb!=null&&!thumb.isRecycled()){c.save();c.clipRect(r);p.setAlpha(60);c.drawBitmap(thumb,null,new RectF(r.left,r.top,Math.min(r.right,r.left+64),r.bottom),p);p.setAlpha(255);c.restore();}
            float[] wave=waveforms.get(clip.id);if(wave!=null){float width=r.width()*Math.min(1,120000f/Math.max(1,clip.outMs-clip.inMs));p.setColor(Color.rgb(100,240,195));for(int j=0;j<wave.length;j++){float x=r.left+j*width/wave.length,amplitude=Math.min(18,wave[j]*clip.volume*50);c.drawLine(x,r.centerY()-amplitude,x,r.centerY()+amplitude,p);}}
            p.setColor(Color.WHITE);ProjectStore.Asset asset=project.asset(clip.assetId);String name=asset==null?"Missing":asset.name;
            c.save();c.clipRect(r);c.drawText(name,r.left+8,r.top+19,p);p.setTextSize(13);c.drawText(String.format(java.util.Locale.US,"%.1fs · %.2f×",clip.outputDurationMs()/1000f,clip.speed),r.left+8,r.bottom-5,p);p.setTextSize(17);c.restore();
            if(clip.id.equals(selected)){p.setColor(Color.CYAN);c.drawRect(r.left,r.top,r.left+5,r.bottom,p);c.drawRect(r.right-5,r.top,r.right,r.bottom,p);}
        }
        p.setColor(Color.rgb(255,105,111));p.setStrokeWidth(2);float x=60+playheadMs/1000f*pixelsPerSecond;c.drawLine(x,24,x,252,p);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN){for(Map.Entry<ProjectStore.Clip,RectF> item:rectangles.entrySet())if(item.getValue().contains(e.getX(),e.getY())){touching=item.getKey();down=e.getX();RectF r=item.getValue();edge=Math.abs(e.getX()-r.left)<14?1:Math.abs(e.getX()-r.right)<14?2:0;getParent().requestDisallowInterceptTouchEvent(edge!=0);return true;}}
        if(e.getAction()==MotionEvent.ACTION_UP&&touching!=null){ProjectStore.Clip clip=touching;RectF r=rectangles.get(clip);touching=null;
            long delta=(long)((e.getX()-down)/pixelsPerSecond*1000*clip.speed);
            if(edge!=0&&Math.abs(delta)>30)events.trim(clip,edge==1?Math.max(0,clip.inMs+delta):clip.inMs,edge==2?clip.outMs+delta:clip.outMs);
            else events.select(clip,Math.max(0,Math.min(clip.outputDurationMs()-1,(long)((e.getX()-r.left)/pixelsPerSecond*1000))));return true;}
        return touching!=null||super.onTouchEvent(e);
    }
}
