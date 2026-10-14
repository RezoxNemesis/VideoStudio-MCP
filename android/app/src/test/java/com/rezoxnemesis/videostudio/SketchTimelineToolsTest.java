package com.rezoxnemesis.videostudio;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import java.lang.reflect.Field;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,qualifiers="mdpi")
public class SketchTimelineToolsTest {
    @Test public void exactNavigationReachesTheLastPageWithoutRoundingOrClampingBadInput() {
        assertEquals(9999,SketchTimelineTools.position(" 10000 ",10000));
        assertEquals(0,SketchTimelineTools.position("1",10000));
        for(String value:new String[]{"0","10001","1.5","NaN","999999999999"})
            assertThrows(value,IllegalArgumentException.class,()->SketchTimelineTools.position(value,10000));
    }

    @Test public void exposureSheetUsesInclusiveArtistPageNumbersAndNativeTransactionLimits() throws Exception {
        JSONArray edits=SketchTimelineTools.exposureEdits("9001","9128","2",10000);
        assertEquals(128,edits.length());
        assertEquals(9000,edits.getJSONObject(0).getInt("page"));
        assertEquals(9127,edits.getJSONObject(127).getInt("page"));
        assertEquals(2,edits.getJSONObject(127).getInt("frames"));
        assertThrows(IllegalArgumentException.class,()->SketchTimelineTools.exposureEdits("1","129","2",10000));
        assertThrows(IllegalArgumentException.class,()->SketchTimelineTools.exposureEdits("2","1","2",10000));
        for(String value:new String[]{"0","601","1.5"})
            assertThrows(IllegalArgumentException.class,()->SketchTimelineTools.exposureEdits("1","1",value,10000));
    }

    @Test public void sparseExposureDurationCountsOnlyCanonicalPagesInsideTheBook() throws Exception {
        JSONObject holds=new JSONObject().put("0",2).put("9999",600).put("10000",600).put("01",50).put("bad",7);
        assertEquals(10600,SketchTimelineTools.totalFrames(holds,10000));
        assertEquals(1,SketchTimelineTools.exposure(holds,1));
        assertEquals(600,SketchTimelineTools.exposure(holds,9999));
    }

    @Test public void statusRefreshPreservesBrowsingAndShrinkClampsTheStrip() throws Exception {
        SketchFilmstripView view=new SketchFilmstripView(RuntimeEnvironment.getApplication());view.layout(0,0,320,48);view.setFrames(10000,0);
        touch(view,MotionEvent.ACTION_DOWN,280,0);touch(view,MotionEvent.ACTION_MOVE,20,16);touch(view,MotionEvent.ACTION_CANCEL,20,17);
        float browsed=offset(view);assertTrue(browsed>200);view.setFrames(10000,0);
        assertEquals("Save/status updates must preserve an artist's browse position",browsed,offset(view),.01);
        view.setTimelineKey("another-book");view.setFrames(10000,0);assertEquals("A different book starts on its own selected page",0,offset(view),.01);
        view.setFrames(1,0);assertEquals(0,offset(view),.01);
        view.setFrames(10000,9999);view.layout(0,0,640,48);
        assertTrue(offset(view)<=10000*64-640);
        assertTrue(9999*64-offset(view)<640);
    }

    @Test public void accessibilityCanSelectAnExactDistantFrameAndReportsItsExposure() throws Exception {
        SketchFilmstripView view=new SketchFilmstripView(RuntimeEnvironment.getApplication());view.layout(0,0,320,48);view.setFrames(10000,0);
        int[] selected={-1};view.setListener(index->{selected[0]=index;view.setFrames(10000,index);});
        Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,10000);
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args));
        assertEquals(9999,selected[0]);view.setExposures(new JSONObject().put("9999",3));
        assertTrue(view.getContentDescription().toString().contains("10000 of 10000, exposure 3"));
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null));assertEquals(9998,selected[0]);
        view.setEnabled(false);assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null));
    }

    @Test public void releaseContinuesASwipeWithoutSelectingAnAccidentalFrame() throws Exception {
        SketchFilmstripView view=new SketchFilmstripView(RuntimeEnvironment.getApplication());view.layout(0,0,320,48);view.setFrames(10000,0);int[] selections={0};view.setListener(index->selections[0]++);
        touch(view,MotionEvent.ACTION_DOWN,280,0);touch(view,MotionEvent.ACTION_MOVE,180,16);touch(view,MotionEvent.ACTION_MOVE,80,32);touch(view,MotionEvent.ACTION_UP,20,48);
        float released=offset(view);ShadowSystemClock.advanceBy(Duration.ofMillis(100));view.computeScroll();
        assertTrue("A fast swipe should coast beyond its release point",offset(view)>released);assertEquals(0,selections[0]);
    }

    private static void touch(SketchFilmstripView view,int action,float x,long time){MotionEvent event=MotionEvent.obtain(0,time,action,x,20,0);try{view.onTouchEvent(event);}finally{event.recycle();}}
    private static float offset(SketchFilmstripView view)throws Exception{Field field=SketchFilmstripView.class.getDeclaredField("offset");field.setAccessible(true);return field.getFloat(view);}
}
