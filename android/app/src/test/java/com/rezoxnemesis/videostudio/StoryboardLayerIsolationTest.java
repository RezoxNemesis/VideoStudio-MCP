package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;

public final class StoryboardLayerIsolationTest {
    private static final int W=96,H=128;
    private static int[] city(){
        int[] base=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)
            base[y*W+x]=0xff000000|((120+x/4)<<16)
                    |((70+y/6)<<8)|(40+x/8);
        return base;
    }
    private static void rectangle(int[] pixels,int left,int top,int width,int height,int c){
        for(int y=top;y<top+height;y++)
            for(int x=left;x<left+width;x++)
                pixels[y*W+x]=c;
    }
    @Test public void isolatesActualMovingBlackAndWhiteForegroundNotWholeCity(){
        int[] a=city(),b=city(),original=city();
        rectangle(a,10,50,12,32,0xff121212);
        rectangle(b,38,50,12,32,0xff121212);
        rectangle(a,71,50,12,29,0xfff4f2ef);
        rectangle(b,65,50,12,29,0xfff4f2ef);
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.analyse(a,b,W,H);
        assertEquals(StoryboardLayerIsolation.Status.READY,result.status);
        assertTrue(result.autoRenderable());
        assertTrue(result.tracks.size()>=1);
        assertTrue(result.changedFraction>.04);
        assertTrue(result.worldDifference<.10);
        assertEquals(original[12*W+40],result.cleanPlate[12*W+40]);
        for(StoryboardLayerIsolation.Track track:result.tracks){
            assertNotNull(track.from.alpha);
            assertTrue(track.from.width()>2);
            assertTrue(track.to.width()>2);
            assertTrue(track.confidence>=.42);
        }
    }
    @Test public void supportsManuallyTracedProductsAnimalsAndText(){
        int[] a=city(),b=city();
        rectangle(a,12,30,18,30,0xff223355);
        rectangle(b,48,30,18,30,0xff223355);
        float[][] first={{.12f,.22f},{.32f,.22f},{.32f,.49f},{.12f,.49f}};
        float[][] last={{.50f,.22f},{.70f,.22f},{.70f,.49f},{.50f,.49f}};
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.annotated(
                a,b,W,H,Collections.singletonList(first),Collections.singletonList(last));
        assertEquals(StoryboardLayerIsolation.Status.READY,result.status);
        assertTrue(result.autoRenderable());
        assertEquals(1,result.tracks.size());
        assertTrue(result.tracks.get(0).to.cx>result.tracks.get(0).from.cx);
    }
    @Test public void rejectsFlatImagesAndGlobalSceneReplacement(){
        int[] unchanged=city();
        StoryboardLayerIsolation.Result still=StoryboardLayerIsolation.analyse(
                unchanged,unchanged.clone(),W,H);
        assertEquals(StoryboardLayerIsolation.Status.NO_VISIBLE_ACTION,still.status);
        int[] other=new int[W*H];Arrays.fill(other,0xffeeeeee);
        StoryboardLayerIsolation.Result cut=StoryboardLayerIsolation.analyse(
                unchanged,other,W,H);
        assertEquals(StoryboardLayerIsolation.Status.CAMERA_CUT,cut.status);
    }
}
