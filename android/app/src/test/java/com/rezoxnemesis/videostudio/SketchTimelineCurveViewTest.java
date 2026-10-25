package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android curve view uses exactly the saved SketchEasing interpolation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchTimelineCurveViewTest {
    private SketchTimelineCurveView view(){
        SketchTimelineCurveView graph=new SketchTimelineCurveView(RuntimeEnvironment.getApplication());
        graph.layout(0,0,420,260);
        return graph;
    }

    @Test public void exactSketchTimingCurveRendersAgainstTheSavedNativeSampler()throws Exception{
        SketchTimelineCurveView graph=view();
        JSONArray authored=new JSONArray().put(.42).put(0).put(.58).put(1);
        graph.setCurve(authored);
        assertEquals(.5,SketchEasing.sample("bezier",graph.curve(),.5),.00001);
        Bitmap pixels=Bitmap.createBitmap(420,260,Bitmap.Config.ARGB_8888);
        try{
            graph.draw(new Canvas(pixels));
            int changes=0,previous=0;
            for(int y=8;y<250;y+=8)for(int x=8;x<410;x+=8){
                int color=pixels.getPixel(x,y);
                if(previous!=0&&color!=previous)changes++;
                previous=color;
            }
            assertTrue("Actual grid, native curve and handles must be visible",changes>25);
        }finally{pixels.recycle();}
    }

    @Test public void humanTouchDragsTheSameNumericHandlesChatGptCanAuthor()throws Exception{
        SketchTimelineCurveView graph=view();
        graph.setCurve(new JSONArray().put(.2).put(.2).put(.8).put(.8));
        AtomicReference<JSONArray> value=new AtomicReference<>();
        graph.setListener(value::set);
        PointF handle=graph.handlePoint(0);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,handle.x,handle.y,0);
        MotionEvent drag=MotionEvent.obtain(0,16,MotionEvent.ACTION_MOVE,handle.x+40,handle.y-35,0);
        MotionEvent up=MotionEvent.obtain(0,25,MotionEvent.ACTION_UP,handle.x+40,handle.y-35,0);
        try{
            assertTrue(graph.onTouchEvent(down));
            assertTrue(graph.onTouchEvent(drag));
            assertTrue(graph.onTouchEvent(up));
            assertNotNull(value.get());
            assertTrue(value.get().getDouble(0)>.2);
            assertTrue(value.get().getDouble(1)>.2);
            JSONObject key=new JSONObject().put("interpolation","bezier").put("curve",value.get());
            SketchEasing.validate(key);
            assertTrue(SketchEasing.sample("bezier",value.get(),.5)>=0);
        }finally{down.recycle();drag.recycle();up.recycle();}
    }

    @Test public void cancelledGestureRevertsToOriginalDraftAndNeverCommitsByItself()throws Exception {
        SketchTimelineCurveView graph=view();
        JSONArray original=new JSONArray().put(.3).put(.4).put(.7).put(.6);
        graph.setCurve(original);
        AtomicReference<JSONArray> last=new AtomicReference<>();
        graph.setListener(last::set);
        PointF handle=graph.handlePoint(1);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,handle.x,handle.y,0);
        MotionEvent drag=MotionEvent.obtain(0,14,MotionEvent.ACTION_MOVE,handle.x-35,handle.y+38,0);
        MotionEvent cancel=MotionEvent.obtain(0,19,MotionEvent.ACTION_CANCEL,handle.x-35,handle.y+38,0);
        try{
            graph.onTouchEvent(down);graph.onTouchEvent(drag);graph.onTouchEvent(cancel);
            assertEquals(.7,graph.curve().getDouble(2),.001);
            assertEquals(.6,graph.curve().getDouble(3),.001);
            assertNotNull(last.get());
            assertEquals(.7,last.get().getDouble(2),.001);
        }finally{down.recycle();drag.recycle();cancel.recycle();}
    }

    @Test public void rejectOutOfRangeAndNonnumericControls(){
        SketchTimelineCurveView graph=view();
        assertThrows(IllegalArgumentException.class,
                ()->graph.setCurve(new JSONArray().put(.2).put(1.2).put(.8).put(1)));
        assertThrows(IllegalArgumentException.class,
                ()->graph.setCurve(new JSONArray().put(.2).put(.5).put("evil").put(1)));
        assertThrows(IllegalArgumentException.class,
                ()->graph.setCurve(new JSONArray().put(.2).put(.5).put(.8)));
        assertThrows(IllegalArgumentException.class,()->graph.handlePoint(3));
    }
}
