package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** The Android touch graph manipulates the actual compositor Bézier values. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioBezierCurveViewTest {
    private static StudioBezierCurveView graph(){
        StudioBezierCurveView view=new StudioBezierCurveView(RuntimeEnvironment.getApplication());
        view.layout(0,0,440,290);
        return view;
    }

    @Test public void authoredHandlesRoundTripThroughNumericInterface(){
        StudioBezierCurveView curve=graph();
        curve.setHandles(StudioBezierEasing.fromText("0.22,1.0,0.36,1.0"));
        JSONArray handles=curve.handles();
        assertEquals(4,handles.length());
        assertEquals(.22,handles.optDouble(0),.001);
        assertEquals(1.0,handles.optDouble(1),.001);
        assertEquals("4 inputs remain finite",4,StudioBezierEasing.validate(handles).length);
        assertNotNull(StudioBezierCurveView.format(handles));
    }

    @Test public void dragHandleUpdatesOnlyDraftAndKeepsXBounds() {
        StudioBezierCurveView curve=graph();
        curve.setHandles(StudioBezierEasing.fromText("0.22,0,0.78,1"));
        AtomicInteger updates=new AtomicInteger();
        AtomicReference<JSONArray> last=new AtomicReference<>();
        curve.setOnHandlesChanged(v->{updates.incrementAndGet();last.set(v);});
        PointF first=curve.handlePoint(0);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,first.x,first.y,0);
        MotionEvent move=MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,first.x+55,first.y-40,0);
        MotionEvent up=MotionEvent.obtain(0,20,MotionEvent.ACTION_UP,first.x+55,first.y-40,0);
        try{
            assertTrue(curve.onTouchEvent(down));
            assertTrue(curve.onTouchEvent(move));
            assertTrue(curve.onTouchEvent(up));
            assertEquals(1,updates.get());
            assertNotNull(last.get());
            double[] controls=StudioBezierEasing.validate(last.get());
            assertTrue(controls[0]>.22);
            assertTrue(controls[0]<=1);
            assertTrue(controls[1]>0);
            assertTrue(controls[1]<=1.5);
        }finally{down.recycle();move.recycle();up.recycle();}
    }

    @Test public void cancelledGestureRestoresHandleInsteadOfWritingUnintendedMotion(){
        StudioBezierCurveView curve=graph();
        JSONArray original=StudioBezierEasing.fromText("0.22,0.5,0.78,1.0");
        curve.setHandles(original);
        AtomicReference<JSONArray> callback=new AtomicReference<>();
        curve.setOnHandlesChanged(callback::set);
        PointF second=curve.handlePoint(1);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,second.x,second.y,0);
        MotionEvent drag=MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,second.x-40,second.y+20,0);
        MotionEvent cancel=MotionEvent.obtain(0,20,MotionEvent.ACTION_CANCEL,second.x-40,second.y+20,0);
        try{
            curve.onTouchEvent(down);curve.onTouchEvent(drag);curve.onTouchEvent(cancel);
            double[] last=StudioBezierEasing.validate(curve.handles());
            assertEquals(.78,last[2],.001);
            assertEquals(1.0,last[3],.001);
            assertNotNull(callback.get());
        }finally{down.recycle();drag.recycle();cancel.recycle();}
    }

    @Test public void graphRendersOriginalPixelsAndUsesRealEasingSampler(){
        StudioBezierCurveView curve=graph();
        curve.setHandles(StudioBezierEasing.fromText("0.42,0.0,0.58,1.0"));
        Bitmap bitmap=Bitmap.createBitmap(440,290,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(bitmap);curve.draw(canvas);
            int diverse=0,last=-1;
            for(int y=5;y<285;y+=8)for(int x=5;x<435;x+=8){
                int color=bitmap.getPixel(x,y);
                if(last!=-1&&color!=last)diverse++;
                last=color;
            }
            assertTrue("The graph draws actual axes, motion path, and handles",diverse>40);
            double[] handles=StudioBezierEasing.validate(curve.handles());
            assertEquals(.5,StudioBezierEasing.evaluate(.5,handles),.001);
        }finally{bitmap.recycle();}
    }
}
