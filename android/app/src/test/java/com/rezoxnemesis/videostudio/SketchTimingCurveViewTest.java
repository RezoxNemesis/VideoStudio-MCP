package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Pixel, drag, validation and exact sample parity tests for drawing curves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchTimingCurveViewTest {
    private SketchTimingCurveView view(){
        SketchTimingCurveView widget=new SketchTimingCurveView(RuntimeEnvironment.getApplication());
        widget.layout(0,0,360,240);return widget;
    }

    @Test public void curveUsesExactSketchAnimationInterpolation()throws Exception{
        SketchTimingCurveView widget=view();
        JSONArray curve=SketchTimingCurveView.parse(".42,0,1,1");
        widget.setCurve(curve);
        assertEquals(0,SketchEasing.sample("bezier",widget.curve(),0),0);
        assertEquals(1,SketchEasing.sample("bezier",widget.curve(),1),0);
        assertTrue(SketchEasing.sample("bezier",widget.curve(),.5)<.5);
        assertEquals(4,widget.curve().length());
    }

    @Test public void draggingCyanControlIsRealDraftAndCancelRestoresOriginal(){
        SketchTimingCurveView widget=view();
        widget.setCurve(SketchTimingCurveView.parse(".3,.2,.8,.9"));
        AtomicReference<JSONArray> last=new AtomicReference<>();
        widget.setOnDraftChanged(last::set);
        float[] p=widget.handleCenter(0);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,p[0],p[1],0);
        MotionEvent move=MotionEvent.obtain(0,20,MotionEvent.ACTION_MOVE,p[0]+40,p[1]-35,0);
        MotionEvent cancel=MotionEvent.obtain(0,40,MotionEvent.ACTION_CANCEL,p[0]+40,p[1]-35,0);
        try{
            assertTrue(widget.onTouchEvent(down));
            assertTrue(widget.onTouchEvent(move));
            assertNotNull(last.get());
            assertTrue(last.get().optDouble(0)>.3);
            assertTrue(widget.onTouchEvent(cancel));
            assertEquals(.3,widget.curve().optDouble(0),.00001);
            assertEquals(.2,widget.curve().optDouble(1),.00001);
        }finally{down.recycle();move.recycle();cancel.recycle();}
    }

    @Test public void graphRendersActualGridAndEasingPath(){
        SketchTimingCurveView widget=view();
        Bitmap b=Bitmap.createBitmap(360,240,Bitmap.Config.ARGB_8888);
        try{
            widget.draw(new Canvas(b));
            int varied=0;int old=0;
            for(int y=0;y<240;y+=6)for(int x=0;x<360;x+=6){
                int pixel=b.getPixel(x,y);
                if(old!=0&&pixel!=old)varied++;
                old=pixel;
            }
            assertTrue("A dynamic curve, grid and handles must actually render",varied>25);
        }finally{b.recycle();}
    }

    @Test public void finiteBoundedSketchControlPointsAreRequired(){
        assertThrows(IllegalArgumentException.class,()->
                SketchTimingCurveView.parse(".2,.3,.4"));
        assertThrows(IllegalArgumentException.class,()->
                SketchTimingCurveView.parse(".2,1.4,.4,1"));
        assertThrows(IllegalArgumentException.class,()->
                SketchTimingCurveView.parse("nan,0,.4,1"));
        assertThrows(IllegalArgumentException.class,()->
                SketchTimingCurveView.verified(new JSONArray().put("0").put(0).put(1).put(1)));
    }
}
