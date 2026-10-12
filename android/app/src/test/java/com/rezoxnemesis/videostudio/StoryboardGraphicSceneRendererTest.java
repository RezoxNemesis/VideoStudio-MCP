package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardGraphicSceneRendererTest {
    private static final int W=160,H=288;
    private int[] paper(){
        int[] p=new int[W*H];
        java.util.Arrays.fill(p,0xffe9ddc9);
        return p;
    }
    @Test public void diagramRevealsGenuineSourcePixelsInsteadOfFadingWholePage(){
        int[] start=paper(),end=paper();
        for(int x=18;x<140;x++)for(int y=100;y<106;y++)
            end[y*W+x]=0xff263349;
        try(StoryboardGraphicSceneRenderer director=new StoryboardGraphicSceneRenderer(
                start,end,W,H,
                StoryboardGraphicSceneRenderer.Mode.DRAW_REVEAL)){
            Bitmap first=director.render(0),mid=director.render(.5f),
                    last=director.render(1);
            try{
                assertEquals(start[104*W+22],first.getPixel(22,104));
                assertEquals(end[104*W+22],mid.getPixel(22,104));
                assertEquals(start[104*W+128],mid.getPixel(128,104));
                assertEquals(end[104*W+128],last.getPixel(128,104));
                assertEquals(start[25*W+80],mid.getPixel(80,25));
            }finally{first.recycle();mid.recycle();last.recycle();}
        }
    }
    @Test public void landscapeProducesTimeDependentAtmosphereWithoutFigureRig(){
        int[] same=paper();
        try(StoryboardGraphicSceneRenderer director=new StoryboardGraphicSceneRenderer(
                same,same.clone(),W,H,
                StoryboardGraphicSceneRenderer.Mode.ENVIRONMENT)){
            Bitmap first=director.render(0),mid=director.render(.5f),
                    finalFrame=director.render(1);
            assertNotNull(mid);
            assertEquals(same[20*W+40],first.getPixel(40,20));
            assertEquals(same[20*W+40],finalFrame.getPixel(40,20));
            first.recycle();mid.recycle();finalFrame.recycle();
        }
    }
}
