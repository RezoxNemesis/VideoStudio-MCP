package com.rezoxnemesis.videostudio;

import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioTracksViewGestureTest {
    private static final class Edits implements StudioTracksView.ChangeListener {
        int commits,selections,leftTrims;long start,duration;
        @Override public void onChange(String id,long start,long duration){commits++;this.start=start;this.duration=duration;}
        @Override public void onTrimLeft(String id,long start,long duration){leftTrims++;onChange(id,start,duration);}
        @Override public void onSelect(String id){selections++;}
    }
    private static StudioLayer lane(){
        StudioLayer layer=new StudioLayer();layer.id="caption";layer.text="Title";layer.startMs=2000;layer.durationMs=3000;return layer;
    }
    private static StudioTracksView view(StudioLayer lane,Edits edits){
        StudioTracksView view=new StudioTracksView(RuntimeEnvironment.getApplication());
        view.setTimeline(10000,0,List.of(lane),at->{},edits);view.setSnapping(new long[]{0,10000},false);
        view.layout(0,0,1066,150);return view;
    }
    private static void touch(StudioTracksView view,int action,float x,float y){
        float density=view.getResources().getDisplayMetrics().density;
        MotionEvent event=MotionEvent.obtain(0,100,action,x*density,y*density,0);
        view.onTouchEvent(event);event.recycle();
    }
    @Test public void leftHandleSavesOneEditOnlyAfterRelease(){
        StudioLayer original=lane();Edits edits=new Edits();StudioTracksView view=view(original,edits);
        touch(view,MotionEvent.ACTION_DOWN,258,90);touch(view,MotionEvent.ACTION_MOVE,358,90);
        assertEquals(0,edits.commits);assertEquals(2000,original.startMs);
        touch(view,MotionEvent.ACTION_UP,358,90);
        assertEquals(1,edits.commits);assertEquals(1,edits.leftTrims);
        assertEquals(3000,edits.start);assertEquals(2000,edits.duration);
    }
    @Test public void cancellingARealDragCannotSaveOrMutateTheProject(){
        StudioLayer original=lane();Edits edits=new Edits();StudioTracksView view=view(original,edits);
        touch(view,MotionEvent.ACTION_DOWN,350,90);touch(view,MotionEvent.ACTION_MOVE,500,90);
        touch(view,MotionEvent.ACTION_CANCEL,500,90);
        assertEquals(0,edits.commits);assertEquals(0,edits.selections);assertEquals(2000,original.startMs);
    }
    @Test public void scrubbingSeeksOnReleaseSoPreviewCannotDetachTheActiveGesture(){
        StudioTracksView view=new StudioTracksView(RuntimeEnvironment.getApplication());final long[] seek={-1};
        view.setTimeline(10000,0,List.of(),at->seek[0]=at);view.layout(0,0,1066,150);
        touch(view,MotionEvent.ACTION_DOWN,56,7);touch(view,MotionEvent.ACTION_MOVE,556,7);
        assertEquals(-1,seek[0]);touch(view,MotionEvent.ACTION_UP,556,7);assertEquals(5000,seek[0]);
    }
    private static HorizontalScrollView viewport(StudioTracksView tracks){
        android.app.Activity activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        HorizontalScrollView parent=new HorizontalScrollView(activity);activity.setContentView(parent);
        parent.addView(tracks,new FrameLayout.LayoutParams(1066,150));
        parent.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(150,View.MeasureSpec.EXACTLY));
        parent.layout(0,0,400,150);return parent;
    }
    private static void dispatch(HorizontalScrollView parent,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(0,100,action,x,y,0);parent.dispatchTouchEvent(event);event.recycle();
    }
    @Test public void emptyRulerDragPansZoomedViewportWithoutCommittingASeek(){
        StudioTracksView tracks=new StudioTracksView(RuntimeEnvironment.getApplication());final long[] seek={-1};
        tracks.setTimeline(10000,0,List.of(),at->seek[0]=at);HorizontalScrollView parent=viewport(tracks);
        dispatch(parent,MotionEvent.ACTION_DOWN,350,7);dispatch(parent,MotionEvent.ACTION_MOVE,200,7);dispatch(parent,MotionEvent.ACTION_MOVE,50,7);dispatch(parent,MotionEvent.ACTION_UP,50,7);
        assertTrue("Zoomed timeline must permit horizontal panning",parent.getScrollX()>0);assertEquals(-1,seek[0]);
    }
    @Test public void clipHandleDragKeepsParentStationaryAndCommitsTrim(){
        Edits edits=new Edits();StudioTracksView tracks=view(lane(),edits);HorizontalScrollView parent=viewport(tracks);
        dispatch(parent,MotionEvent.ACTION_DOWN,258,90);dispatch(parent,MotionEvent.ACTION_MOVE,358,90);dispatch(parent,MotionEvent.ACTION_UP,358,90);
        assertEquals(0,parent.getScrollX());assertEquals(1,edits.leftTrims);assertEquals(3000,edits.start);
    }
}
