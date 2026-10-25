package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Presentation-only normalized drawing camera with no source coordinate mutation. */
public class SketchViewportMathTest {
    @Test public void exactFocusCentersAnArbitraryCanvasPointOnPortraitAndLandscapeScreens(){
        for(int[] screen:new int[][]{{360,780},{780,360},{1080,1920}}){
            int w=screen[0],h=screen[1],docW=1200,docH=1500;
            SketchViewportMath.Camera camera=SketchViewportMath.focus(w,h,docW,docH,.72,.3,3);
            double scale=Math.min(w/(double)docW,h/(double)docH)*camera.zoom;
            double screenX=(w-docW*scale)*.5+camera.panX+.72*docW*scale;
            double screenY=(h-docH*scale)*.5+camera.panY+.3*docH*scale;
            assertEquals(w/2.0,screenX,.01);
            assertEquals(h/2.0,screenY,.01);
        }
    }

    @Test public void penInsideSafeViewDoesNotTriggerCameraMovement(){
        float[] pan=SketchViewportMath.follow(600,900,600,900,2,0,0,.5,.5);
        assertEquals(0,pan[0],0);assertEquals(0,pan[1],0);
        float[] outside=SketchViewportMath.follow(600,900,600,900,2,0,0,.95,.9);
        assertTrue(outside[0]<0);assertTrue(outside[1]<0);
        assertTrue(Math.abs(outside[0])<600);
    }

    @Test public void invalidCameraDoesNotProjectOutsideOrPoisonMath(){
        assertThrows(IllegalArgumentException.class,()->
                SketchViewportMath.focus(0,600,600,900,.5,.5,3));
        assertThrows(IllegalArgumentException.class,()->
                SketchViewportMath.focus(600,900,600,900,Double.NaN,.5,2));
        assertThrows(IllegalArgumentException.class,()->
                SketchViewportMath.focus(600,900,600,900,.5,.5,13));
        assertThrows(IllegalArgumentException.class,()->
                SketchViewportMath.focus(600,900,600,900,1.1,.5,2));
    }
}
