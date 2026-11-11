package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryboardCleanPlateTest {
    private static final int W=48,H=64;
    private static float[][] box(float l,float top,float right,float bottom){
        return new float[][]{
            {l,top},{right,top},{right,bottom},{l,bottom}
        };
    }
    private static int[] city(){
        int[] values=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int r=70+x/3,g=42+y/5,b=29+x/5;
            values[y*W+x]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return values;
    }
    private static void paint(int[] image,float[][] poly,int color){
        boolean[] mask=StoryboardCleanPlate.mask(W,H,poly);
        for(int i=0;i<image.length;i++)if(mask[i])image[i]=color;
    }
    @Test public void leavesOriginalCityPixelsExactlyUnchanged(){
        int[] from=city(),to=city(),base=city();
        float[][] ba=box(.05f,.4f,.30f,.88f),
                wa=box(.62f,.5f,.83f,.87f),
                bb=box(.27f,.43f,.49f,.86f),
                wb=box(.53f,.48f,.69f,.85f);
        paint(from,ba,0xff000000);
        paint(from,wa,0xffffffff);
        paint(to,bb,0xff000000);
        paint(to,wb,0xffffffff);
        StoryboardCleanPlate.Result result=StoryboardCleanPlate.restore(
                from,to,W,H,ba,wa,bb,wb);
        boolean[] covered=StoryboardCleanPlate.mask(W,H,ba);
        boolean[] white=StoryboardCleanPlate.mask(W,H,wa);
        for(int i=0;i<covered.length;i++)covered[i]|=white[i];
        for(int i=0;i<from.length;i++){
            if(!covered[i])assertEquals("Sky/city outside actors is stable",
                    from[i],result.pixels[i]);
        }
        assertEquals(base[H/2*W+W/8],
                result.pixels[H/2*W+W/8]);
        assertTrue(result.recoveredSourcePixels>0);
        assertTrue(result.backgroundDifference<.02);
    }

    @Test public void overlappingCharacterOcclusionReconstructsHoleFromRealNeighbors(){
        int[] a=city(),b=city();
        float[][] same=box(.25f,.25f,.50f,.7f);
        float[][] white=box(.7f,.7f,.82f,.9f);
        paint(a,same,0xff000000);paint(b,same,0xff000000);
        paint(a,white,0xffffffff);paint(b,white,0xffffffff);
        StoryboardCleanPlate.Result result=StoryboardCleanPlate.restore(a,b,W,H,
                same,white,same,white);
        assertTrue(result.filledPixels>0);
        assertNotEquals(0xff000000,result.pixels[H/2*W+W/3]);
        assertNotEquals(0xffffffff,result.pixels[H/2*W+W/3]);
    }

    @Test public void rejectLargeCameraOrColorJumpInsteadOfMorphingBuildings(){
        int[] a=new int[W*H],b=new int[W*H];
        java.util.Arrays.fill(a,0xff0d0d0d);
        java.util.Arrays.fill(b,0xfffafafa);
        float[][] black=box(.1f,.4f,.3f,.8f),white=box(.6f,.4f,.8f,.8f);
        try{
            StoryboardCleanPlate.restore(a,b,W,H,black,white,black,white);
            fail("Large scene cuts must remain separate cinematic shots");
        }catch(IllegalArgumentException expected){}
    }

    @Test public void noUnsafeMasksOrUnboundedImages() {
        try{
            StoryboardCleanPlate.mask(W,H,new float[0][2]);fail();
        }catch(IllegalArgumentException expected){}
        try{
            StoryboardCleanPlate.restore(new int[3],new int[3],W,H,
                    box(0,.1f,.3f,.3f),box(.6f,.1f,.8f,.3f),
                    box(0,.1f,.3f,.3f),box(.6f,.1f,.8f,.3f));
            fail();
        }catch(IllegalArgumentException expected){}
    }
}
