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

/** Actual Canvas-rendered graph follows SketchEasing, including touch cancellation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchCurveGraphViewTest {
    private static SketchCurveGraphView graph(){
        SketchCurveGraphView view=new SketchCurveGraphView(RuntimeEnvironment.getApplication());
        view.layout(0,0,440,290);
        return view;
    }

    @Test public void graphAcceptsExactlyTheSameNativeSketchEasingBounds()throws Exception{
        SketchCurveGraphView graph=graph();
        JSONArray handles=new JSONArray().put(.42).put(0).put(1).put(1);
        graph.setCurve(handles);
        assertEquals(4,graph.curve().length());
        assertEquals(.42,graph.curve().optDouble(0),.00001);
        assertEquals(1,graph.curve().optDouble(3),.00001);
        assertThrows(IllegalArgumentException.class,()->graph.setCurve(
                new JSONArray().put(1.3).put(0).put(.5).put(1)));
    }

    @Test public void realTouchDraggingEditsDraftValuesWithoutSavingProject()throws Exception{
        SketchCurveGraphView graph=graph();
        graph.setCurve(new JSONArray().put(.2).put(.3).put(.8).put(1));
        AtomicInteger callbacks=new AtomicInteger();
        AtomicReference<JSONArray> latest=new AtomicReference<>();
        graph.setOnCurveChanged(values->{callbacks.incrementAndGet();latest.set(values);});
        PointF point=graph.handlePoint(0);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,point.x,point.y,0);
        MotionEvent move=MotionEvent.obtain(0,15,MotionEvent.ACTION_MOVE,point.x+40,point.y-25,0);
        MotionEvent up=MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,point.x+40,point.y-25,0);
        try{
            assertTrue(graph.onTouchEvent(down));
            assertTrue(graph.onTouchEvent(move));
            assertTrue(graph.onTouchEvent(up));
            assertEquals(1,callbacks.get());
            JSONArray updated=latest.get();
            assertTrue(updated.optDouble(0)>.2);
            assertTrue(updated.optDouble(1)>.3);
            assertTrue(updated.optDouble(0)<=1);
            assertTrue(updated.optDouble(1)<=1);
            assertTrue(Double.isFinite(SketchEasing.sample("bezier",updated,.5)));
        }finally{down.recycle();move.recycle();up.recycle();}
    }

    @Test public void cancellingTouchRestoresThePreviousGraph()throws Exception{
        SketchCurveGraphView graph=graph();
        graph.setCurve(new JSONArray().put(.2).put(.25).put(.8).put(.9));
        PointF p=graph.handlePoint(1);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,p.x,p.y,0);
        MotionEvent move=MotionEvent.obtain(0,20,MotionEvent.ACTION_MOVE,p.x-40,p.y+20,0);
        MotionEvent cancel=MotionEvent.obtain(0,30,MotionEvent.ACTION_CANCEL,p.x-40,p.y+20,0);
        try{
            graph.onTouchEvent(down);graph.onTouchEvent(move);graph.onTouchEvent(cancel);
            assertEquals(.8,graph.curve().optDouble(2),.0001);
            assertEquals(.9,graph.curve().optDouble(3),.0001);
        }finally{down.recycle();move.recycle();cancel.recycle();}
    }

    @Test public void graphRendersDifferentPixelsAndUsesExactNativeCurveSample()throws Exception{
        SketchCurveGraphView graph=graph();
        graph.setCurve(new JSONArray().put(.42).put(0).put(.58).put(1));
        Bitmap image=Bitmap.createBitmap(440,290,Bitmap.Config.ARGB_8888);
        try{
            graph.draw(new Canvas(image));
            int changes=0,last=-1;
            for(int y=5;y<285;y+=8)for(int x=5;x<435;x+=8){
                int color=image.getPixel(x,y);
                if(last!=-1&&last!=color)changes++;
                last=color;
            }
            assertTrue("Guide lines, curve and touch handles must be visible",changes>35);
            assertEquals(.5,SketchEasing.sample("bezier",graph.curve(),.5),.00001);
        }finally{image.recycle();}
    }
}
