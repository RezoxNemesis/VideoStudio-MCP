package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Actual source-alpha oracle for the padded-cel editor failure, not a larger budget. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSelectionPaddingBudgetTest {
    @Test public void sparseCelAtBottomOfCanvasStoresOnlyItsInkSupportAndKeepsSourceCoordinates() {
        Bitmap source=Bitmap.createBitmap(640,1280,Bitmap.Config.ARGB_8888);
        try {
            for(int y=1210;y<1226;y++)for(int x=610;x<626;x++)source.setPixel(x,y,0x01223344);
            for(int y=1213;y<1218;y++)for(int x=613;x<618;x++)source.setPixel(x,y,0);
            SketchFrameRenderer.ImageSelection support=new SketchFrameRenderer.ImageSelection(source);
            assertEquals(640,support.width);assertEquals(1280,support.height);
            assertTrue("Transparent top/left padding must not consume the8MiB support budget",support.bytes()<=32);
            int[] row=new int[640];
            for(int y=0;y<1280;y++) {
                source.getPixels(row,0,640,0,y,640,1);
                for(int x=0;x<640;x++)assertEquals("Exact full-source alpha at"+x+","+y,(row[x]>>>24)!=0,support.contains(x,y));
            }
            assertFalse(support.contains(-1,1210));assertFalse(support.contains(640,1210));
            assertFalse(support.contains(610,-1));assertFalse(support.contains(610,1280));
        } finally {source.recycle();}
    }

    @Test public void emptyAndFullyOpaqueSourcesRetainTheirExactAlphaMeaning() {
        Bitmap source=Bitmap.createBitmap(64,128,Bitmap.Config.ARGB_8888);
        try {
            SketchFrameRenderer.ImageSelection empty=new SketchFrameRenderer.ImageSelection(source);
            assertEquals(0,empty.bytes());assertFalse(empty.contains(0,0));assertFalse(empty.contains(63,127));
            source.eraseColor(0xFF102030);
            SketchFrameRenderer.ImageSelection full=new SketchFrameRenderer.ImageSelection(source);
            assertEquals(64*128/8,full.bytes());
            for(int y=0;y<128;y++)for(int x=0;x<64;x++)assertTrue(full.contains(x,y));
        } finally {source.recycle();}
    }
}
