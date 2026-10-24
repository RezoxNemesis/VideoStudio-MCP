package com.rezoxnemesis.videostudio;

import android.graphics.RectF;
import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Preview, direct touch and export geometry use one explicit canvas ratio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioPreviewCanvasTest {
    @Test public void allFourNativeExportCanvasRatiosCanBePreviewedExactly(){
        String[] labels={"9:16","16:9","1:1","4:5"};
        float[] ratios={9f/16f,16f/9f,1f,4f/5f};
        for(int i=0;i<labels.length;i++){
            assertEquals(labels[i],StudioPreviewCanvas.coerce(labels[i]));
            assertEquals(ratios[i],StudioPreviewCanvas.ratio(labels[i]),.0001f);
            RectF fitted=StudioTouchTransformOverlay.fittedCanvas(1200,650,ratios[i]);
            assertEquals(ratios[i],fitted.width()/fitted.height(),.001f);
            assertTrue(fitted.left>=0);assertTrue(fitted.top>=0);
            assertTrue(fitted.right<=1200.01f&&fitted.bottom<=650.01f);
        }
        assertEquals("9:16",StudioPreviewCanvas.coerce("unknown"));
        assertEquals("9:16",StudioPreviewCanvas.coerce(null));
    }

    private static StudioLayer moved(float aspect)throws Exception{
        StudioLayer shape=StudioShapePresets.create("rect","hold",0,2000);
        shape.x=.5f;shape.y=.5f;shape.width=.3f;shape.height=.3f;
        final StudioLayer[] output=new StudioLayer[1];
        StudioTouchTransformOverlay overlay=new StudioTouchTransformOverlay(
                RuntimeEnvironment.getApplication(),shape,aspect,
                new StudioTouchTransformOverlay.Listener(){
                    public void onCommit(StudioLayer draft){output[0]=draft;}
                    public void onCancel(){}
                });
        overlay.layout(0,0,600,300);
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,300,150,0);
        MotionEvent drag=MotionEvent.obtain(0,15,MotionEvent.ACTION_MOVE,350,150,0);
        MotionEvent up=MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,350,150,0);
        try{
            assertTrue(overlay.onTouchEvent(down));
            assertTrue(overlay.onTouchEvent(drag));
            assertTrue(overlay.onTouchEvent(up));
            assertNotNull(output[0]);
            return output[0];
        }finally{down.recycle();drag.recycle();up.recycle();}
    }

    @Test public void userDragUsesFittedOutputPixelsNotPhysicalPhoneOrientation()throws Exception{
        StudioLayer landscape=moved(16f/9f);
        StudioLayer portrait=moved(9f/16f);
        assertTrue(landscape.x>.5f);
        assertTrue(portrait.x>landscape.x);
        assertEquals(.5f,landscape.y,.0001f);
        assertEquals(.5f,portrait.y,.0001f);
        // Horizontal canvas pixels occupy more of this viewport; the exact
        // same finger delta must move a smaller normalized fraction.
        assertEquals(.5f+50f/(300f*16f/9f),landscape.x,.02f);
    }

    @Test public void unsupportedOrNaNPreviewAspectCannotReachTransform() {
        StudioLayer shape=new StudioLayer();shape.type=StudioLayer.SHAPE;
        assertThrows(IllegalArgumentException.class,
                ()->new StudioTouchTransformOverlay(RuntimeEnvironment.getApplication(),
                        shape,Float.NaN,new StudioTouchTransformOverlay.Listener(){
                            public void onCommit(StudioLayer s){}
                            public void onCancel(){}
                        }));
    }
}
