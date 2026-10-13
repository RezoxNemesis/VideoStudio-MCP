package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

/**
 * Reproduction of an actual visual QA failure: the software previously
 * returned a "passed" animation where one black fighter disappeared
 * between storyboard endpoints and an overlapping white fighter ghosted
 * into the unanimated background plate.
 */
public final class StoryboardSourceMatteContinuityTest {
    private static final int W=96,H=128;
    private static int[] background(){
        int[] out=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)
            out[y*W+x]=0xff000000|
                    ((125+x/5)<<16)|
                    ((64+y/7)<<8)|
                    (37+x/8);
        return out;
    }
    private static void paint(int[] pixels,int left,int top,int width,
                              int height,int color){
        for(int y=top;y<top+height;y++)
            for(int x=left;x<left+width;x++)
                pixels[y*W+x]=color;
    }
    private static int[] first(){
        int[] image=background();
        paint(image,6,48,10,40,0xff141217);
        paint(image,72,52,14,35,0xfff4f2eb);
        return image;
    }
    private static int[] last(){
        int[] image=background();
        paint(image,37,48,10,40,0xff141217);
        paint(image,65,52,14,35,0xfff4f2eb);
        return image;
    }
    @Test public void completeSilhouettesRecoverUnchangedWhiteOverlapAndBothActors(){
        StoryboardLayerIsolation.Result layers=
                StoryboardLayerIsolation.analyse(first(),last(),W,H);
        assertEquals(layers.reason,StoryboardLayerIsolation.Status.READY,layers.status);
        assertTrue("Two originally illustrated actors must be isolated",
                layers.tracks.size()>=2);
        int center=69+66*W;
        assertNotEquals("The overlap must NOT leave a ghost white figure in world",
                0xfff4f2eb,layers.cleanPlate[center]);
        int blackTracks=0,whiteTracks=0;
        for(StoryboardLayerIsolation.Track t:layers.tracks){
            if(t.from.meanR<75 && t.to.meanR<75)blackTracks++;
            if(t.from.meanR>180 && t.to.meanR>180)whiteTracks++;
        }
        assertEquals("Dark subject independently tracked",1,blackTracks);
        assertEquals("Bright subject independently tracked",1,whiteTracks);
    }

    @Test public void fullSceneMotionScoreCannotHideMissingBlackSubject(){
        StoryboardLayerIsolation.Result layers=
                StoryboardLayerIsolation.analyse(first(),last(),W,H);
        int[] incomplete=background();
        paint(incomplete,68,52,14,35,0xfff4f2eb);
        StoryboardSubjectRetentionGate.Report verdict=
                StoryboardSubjectRetentionGate.inspect(
                        layers,first(),last(),incomplete,W,H);
        assertFalse("An invisible fighter must fail visual QA",verdict.accepted);
        assertTrue(verdict.reason.contains("vanished"));
    }

    @Test public void bothNewOriginalColoredSubjectsPassWhenRendered(){
        StoryboardLayerIsolation.Result layers=
                StoryboardLayerIsolation.analyse(first(),last(),W,H);
        int[] next=background();
        paint(next,22,48,10,40,0xff141217);
        paint(next,68,52,14,35,0xfff4f2eb);
        StoryboardSubjectRetentionGate.Report verdict=
                StoryboardSubjectRetentionGate.inspect(
                        layers,first(),last(),next,W,H);
        assertTrue(verdict.reason,verdict.accepted);
        assertTrue(verdict.verifiedObjects>=2);
    }
}
