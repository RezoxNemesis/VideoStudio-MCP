package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardSourceArticulationTest {
    static final int W=96,H=128;
    private static StoryboardLayerIsolation.Region figure(boolean next){
        int cx=next?48:39,left=next?30:22,right=next?76:68;
        int top=12,bottom=117;byte[] mask=new byte[W*H];
        long sx=0,sy=0;int count=0;
        for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++){
            boolean crown=Math.hypot(x-cx,y-25)<10;
            boolean chest=y>=33&&y<=74&&Math.abs(x-cx)<7;
            boolean arms=y>=42&&y<=67&&
                    Math.abs(y-(next?53:45)-(x-cx)*.34f)<3;
            boolean legA=y>=74&&y<=bottom&&
                    Math.abs(x-(cx-13)-(y-74)*.03f)<5;
            boolean legB=y>=74&&y<=bottom&&
                    Math.abs(x-(cx+11)-(y-74)*.10f)<5;
            if(crown||chest||arms||legA||legB){
                mask[y*W+x]=(byte)255;sx+=x;sy+=y;count++;
            }
        }
        assertTrue(count>150);
        return new StoryboardLayerIsolation.Region(mask,left,top,right,bottom,
                count,sx/(float)count,sy/(float)count,30,30,30,.95f);
    }
    private static Bitmap colorize(StoryboardLayerIsolation.Region r){
        int w=r.right-r.left+1,h=r.bottom-r.top+1;
        int[] pixels=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)
            if(r.alpha[(r.top+y)*W+r.left+x]!=0)
                pixels[y*w+x]=0xff121923;
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    @Test public void movingSourceExtremitiesDoNotUseRigidTranslation(){
        StoryboardSourceArticulation a=new StoryboardSourceArticulation(
                figure(false),figure(true),W,H,true);
        float[] hand=new float[2],foot=new float[2],crown=new float[2];
        a.shift(.90f,.40f,.45f,false,
                StoryboardMotionGrammar.Action.STRIKE,hand);
        a.shift(.10f,.93f,.45f,false,
                StoryboardMotionGrammar.Action.STRIKE,foot);
        a.shift(.5f,.10f,.45f,false,
                StoryboardMotionGrammar.Action.STRIKE,crown);
        assertTrue(Math.abs(hand[0])+Math.abs(hand[1])>.001);
        assertTrue(Math.abs(hand[0]-foot[0])+
                Math.abs(hand[1]-foot[1])>.001);
        float[] exact=new float[2];
        a.shift(.9f,.4f,0,false,StoryboardMotionGrammar.Action.STRIKE,exact);
        assertEquals(0,exact[0],.000001f);
        a.shift(.9f,.4f,1,true,StoryboardMotionGrammar.Action.STRIKE,exact);
        assertEquals(0,exact[1],.000001f);
    }
    @Test public void gaitIndependentlyMovesLegsRatherThanWholeImage(){
        StoryboardSourceArticulation a=new StoryboardSourceArticulation(
                figure(false),figure(true),W,H,true);
        float[] foot=new float[2],face=new float[2];
        a.shift(.2f,.93f,.25f,false,StoryboardMotionGrammar.Action.RUN,foot);
        a.shift(.2f,.09f,.25f,false,StoryboardMotionGrammar.Action.RUN,face);
        assertTrue(Math.abs(foot[0]-face[0])>.002);
    }
    @Test public void nativeWarpOnlyUsesRealArtistPixelColors(){
        StoryboardLayerIsolation.Region first=figure(false),last=figure(true);
        StoryboardDeformableSprite model=new StoryboardDeformableSprite(
                first,last,W,H,true,StoryboardMotionGrammar.Action.RUN,true);
        Bitmap source=colorize(first);
        assertSame(source,model.deformBitmap(source,0,false,1f));
        Bitmap mid=model.deformBitmap(source,.5f,false,1f);
        assertNotSame(source,mid);
        int moved=0,fake=0;
        for(int y=0;y<source.getHeight();y++)
            for(int x=0;x<source.getWidth();x++){
                int src=source.getPixel(x,y),out=mid.getPixel(x,y);
                if(src!=out)moved++;
                if((out>>>24)>0 && out!=0xff121923)fake++;
            }
        assertTrue("Source pixel positions change within shot",moved>30);
        assertEquals("No generated replacement colors",0,fake);
        mid.recycle();source.recycle();
    }
}
