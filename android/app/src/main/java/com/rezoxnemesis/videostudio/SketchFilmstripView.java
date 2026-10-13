package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Virtualized exposure strip: constant memory for 1..10000 pages. */
public final class SketchFilmstripView extends View {
    public interface Listener{void select(int index);}
    private int count=1,current;private float offset,downX,lastX;private boolean dragging;private Listener listener;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public SketchFilmstripView(Context c){super(c);setBackgroundColor(StudioUi.BG);}
    public void setListener(Listener value){listener=value;}
    public void setFrames(int count,int current){this.count=count;this.current=current;float size=cell();if(current*size<offset||current*size+size>offset+getWidth())offset=Math.max(0,current*size-getWidth()/2f);invalidate();}
    private float cell(){return 64*getResources().getDisplayMetrics().density;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float size=cell();int start=Math.max(0,(int)(offset/size)),end=Math.min(count,start+(int)(getWidth()/size)+2);paint.setTextSize(12*getResources().getDisplayMetrics().density);
        for(int i=start;i<end;i++){float x=i*size-offset;paint.setColor(i==current?StudioUi.ACCENT:StudioUi.SURFACE_ALT);c.drawRoundRect(x+3,4,x+size-3,getHeight()-4,8,8,paint);paint.setColor(i==current?StudioUi.BG:StudioUi.TEXT);c.drawText(String.valueOf(i+1),x+12,getHeight()/2f+5,paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:downX=lastX=e.getX();dragging=false;getParent().requestDisallowInterceptTouchEvent(true);return true;case MotionEvent.ACTION_MOVE:if(Math.abs(e.getX()-downX)>8)dragging=true;offset=Math.max(0,Math.min(Math.max(0,count*cell()-getWidth()),offset+lastX-e.getX()));lastX=e.getX();invalidate();return true;case MotionEvent.ACTION_UP:if(!dragging&&listener!=null){int index=(int)((e.getX()+offset)/cell());if(index>=0&&index<count)listener.select(index);}performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
