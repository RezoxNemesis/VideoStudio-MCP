package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/**
 * Verifies that crowded visual layers can be edited independently by touch.
 * This is the *actual* Android view used by the human editor, not an HTML mock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioProfessionalTracksViewTest {
    private static int dp(Context context,int value){
        return Math.round(context.getResources().getDisplayMetrics().density*value);
    }
    private static StudioLayer title(String id,int start){
        StudioLayer l=new StudioLayer();
        l.id=id;l.type=StudioLayer.TITLE;l.text="Scene "+id;
        l.startMs=start;l.durationMs=2500;
        return l;
    }
    private static StudioTracksView view(Context ctx,List<StudioLayer> lanes,
                                         StudioTracksView.ChangeListener change){
        StudioTracksView tracks=new StudioTracksView(ctx);
        tracks.setProfessionalRows(true);
        tracks.setTimeline(10000,0,lanes,at->{},change);
        tracks.setSnapping(null,false);
        tracks.layout(0,0,620,tracks.desiredHeightPx());
        return tracks;
    }

    @Test public void multitrackModeDoesNotChangeCompactDefaults(){
        Context ctx=RuntimeEnvironment.getApplication();
        StudioTracksView tracks=new StudioTracksView(ctx);
        assertFalse(tracks.hasProfessionalRows());
        assertEquals(dp(ctx,135),tracks.desiredHeightPx());
        tracks.setTimeline(10000,0,Arrays.asList(title("A",0),title("B",1200),title("C",2200)),at->{});
        tracks.setProfessionalRows(true);
        assertTrue(tracks.hasProfessionalRows());
        assertTrue("Three independent rows need more vertical space",tracks.desiredHeightPx()>dp(ctx,135));
        tracks.setProfessionalRows(false);
        assertEquals(dp(ctx,135),tracks.desiredHeightPx());
    }

    @Test public void overlappingTitlesCanBeSelectedOnDifferentRows(){
        Context ctx=RuntimeEnvironment.getApplication();
        AtomicReference<String> selected=new AtomicReference<>("");
        StudioTracksView tracks=view(ctx,Arrays.asList(title("A",0),title("B",1000)),
            new StudioTracksView.ChangeListener(){
                public void onChange(String id,long start,long dur){}
                public void onSelect(String id){selected.set(id);}
            });
        int px=dp(ctx,56)+Math.round((620-dp(ctx,66))*.20f);
        touchTap(tracks,px,dp(ctx,28+15));
        assertEquals("A",selected.get());
        touchTap(tracks,px,dp(ctx,28+48+15));
        assertEquals("B",selected.get());
    }

    @Test public void draggingSecondRowDoesNotMoveFirstOrModifySavedSource(){
        Context ctx=RuntimeEnvironment.getApplication();
        StudioLayer first=title("primary",0),second=title("overlay",1500);
        AtomicReference<String> changed=new AtomicReference<>();
        AtomicInteger recordedStart=new AtomicInteger(-1);
        StudioTracksView tracks=view(ctx,Arrays.asList(first,second),
                new StudioTracksView.ChangeListener(){
                    public void onSelect(String id){}
                    public void onChange(String id,long startMs,long durationMs){
                        changed.set(id);recordedStart.set((int)startMs);
                    }
                });
        float px=dp(ctx,56)+(620-dp(ctx,66))*.25f;
        float py=dp(ctx,28+48+15);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,px,py,0);
        MotionEvent move=MotionEvent.obtain(0,30,MotionEvent.ACTION_MOVE,px+65,py,0);
        MotionEvent up=MotionEvent.obtain(0,45,MotionEvent.ACTION_UP,px+65,py,0);
        try{
            assertTrue(tracks.onTouchEvent(down));
            assertTrue(tracks.onTouchEvent(move));
            assertTrue(tracks.onTouchEvent(up));
            assertEquals("overlay",changed.get());
            assertTrue(recordedStart.get()>1500);
            assertEquals("Input remains unchanged until guarded Activity commit",1500,second.startMs);
            assertEquals(0,first.startMs);
        }finally{down.recycle();move.recycle();up.recycle();}
    }

    @Test public void twoFingerZoomNeverAccidentallyMovesAClip(){
        Context ctx=RuntimeEnvironment.getApplication();
        AtomicInteger moves=new AtomicInteger(),zooms=new AtomicInteger();
        AtomicReference<Float> factor=new AtomicReference<>(0f);
        StudioTracksView tracks=view(ctx,Arrays.asList(title("A",0),title("B",1000)),
                new StudioTracksView.ChangeListener(){
                    public void onSelect(String id){}
                    public void onChange(String id,long s,long d){moves.incrementAndGet();}
                });
        tracks.setOnPinchZoom(f->{zooms.incrementAndGet();factor.set(f);});
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,160,dp(ctx,10),0);
        MotionEvent begin=pointerEvent(10,MotionEvent.ACTION_POINTER_DOWN|
                        (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),160,200);
        MotionEvent change=pointerEvent(20,MotionEvent.ACTION_MOVE,120,260);
        MotionEvent endPointer=pointerEvent(30,MotionEvent.ACTION_POINTER_UP|
                        (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),120,260);
        MotionEvent up=MotionEvent.obtain(0,35,MotionEvent.ACTION_UP,120,dp(ctx,10),0);
        try {
            assertTrue(tracks.onTouchEvent(down));
            assertTrue(tracks.onTouchEvent(begin));
            assertTrue(tracks.onTouchEvent(change));
            assertTrue(tracks.onTouchEvent(endPointer));
            assertTrue(tracks.onTouchEvent(up));
            assertEquals(1,zooms.get());
            assertTrue(factor.get()>1.18f);
            assertEquals("Gesture is only a timeline zoom, never a save",0,moves.get());
        }finally{down.recycle();begin.recycle();change.recycle();endPointer.recycle();up.recycle();}
    }

    private static void touchTap(StudioTracksView view,float x,float y){
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(0,10,MotionEvent.ACTION_UP,x,y,0);
        try {
            assertTrue(view.onTouchEvent(down));
            assertTrue(view.onTouchEvent(up));
        }finally{down.recycle();up.recycle();}
    }

    private static MotionEvent pointerEvent(long at,int action,float x1,float x2){
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] points=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){
            props[i]=new MotionEvent.PointerProperties();
            props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            points[i]=new MotionEvent.PointerCoords();
            points[i].pressure=1f;points[i].size=1f;points[i].y=10f;
        }
        points[0].x=x1;points[1].x=x2;
        return MotionEvent.obtain(0,at,action,2,props,points,0,0,1,1,0,0,0,0);
    }
}
