package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Moving subjects must not appear in the fixed source-world median. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeScenePlateBuilderTest {
    @Test public void movingDarkAndBrightCharactersDisappearFromLockedBackground() {
        int w=52,h=88,background=0xff6a5550;
        int[][] images=new int[9][w*h];
        for(int f=0;f<images.length;f++){
            Arrays.fill(images[f],background);
            // Both fighters move across distinct positions, while the city
            // keeps the exact same global coordinates in all source frames.
            for(int y=20;y<61;y++)
                for(int x=2+f*4;x<Math.min(w,6+f*4);x++)
                    images[f][y*w+x]=0xff020609;
            for(int y=28;y<70;y++)
                for(int x=Math.max(0,49-f*4);x<Math.min(w,51-f*4);x++)
                    images[f][y*w+x]=0xfff7f6ef;
        }
        Bitmap clean=NativeScenePlateBuilder.perChannelMedian(images,w,h);
        try{
            assertEquals(w,clean.getWidth());assertEquals(h,clean.getHeight());
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)
                assertEquals("Moving fighter leaked into fixed world",background,clean.getPixel(x,y));
        }finally{clean.recycle();}
    }

    @Test public void medianSuppressesSingleFrameLightingAndTextNoise() {
        int[][] frames={{0xff223344,0xff223344,0xff223344},
                {0xffff0000,0xff223344,0xff223344},
                {0xff223344,0xffffffff,0xff223344},
                {0xff223344,0xff223344,0xff223344},
                {0xff223344,0xff223344,0xffaabbcc}};
        Bitmap median=NativeScenePlateBuilder.perChannelMedian(frames,3,1);
        try{for(int i=0;i<3;i++)assertEquals(0xff223344,median.getPixel(i,0));}
        finally{median.recycle();}
    }

    @Test(expected=IllegalArgumentException.class)
    public void shortSourcesCannotBeTreatedAsReliableCleanPlate(){
        NativeScenePlateBuilder.perChannelMedian(new int[][]{{0xff000000},{0xffffffff}},1,1);
    }
}
