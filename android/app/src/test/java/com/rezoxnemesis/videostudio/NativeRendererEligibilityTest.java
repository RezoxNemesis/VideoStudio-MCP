package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Pure admission parity with the runtime geometry kernel; no rendered-pixel quality claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class NativeRendererEligibilityTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void closingRendererDoesNotRecycleItsSameSizedCallerOwnedCleanPlate()throws Exception{
        Bitmap plate=Bitmap.createBitmap(320,560,Bitmap.Config.ARGB_8888);
        plate.eraseColor(0xff426789);plate.setPixel(17,190,0xfff49c32);
        NativeSkinnedSprite2D black=null,white=null;NativeRiggedCombatRenderer renderer=null;
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),true)){
            black=new NativeSkinnedSprite2D(fixture.context,fixture.from,fixture.blackFrom,320,560);
            white=new NativeSkinnedSprite2D(fixture.context,fixture.from,fixture.whiteFrom,320,560);
            renderer=new NativeRiggedCombatRenderer(320,560,plate,black,white);
            Bitmap copiedWorld=renderer.staticWorldCopy();
            try{assertEquals(0xfff49c32,copiedWorld.getPixel(17,190));}finally{copiedWorld.recycle();}
            renderer.close();renderer=null;
            assertFalse("Closing a renderer must leave the caller's same-sized plate alive",plate.isRecycled());
            assertEquals("The caller's original material must remain readable",0xfff49c32,plate.getPixel(17,190));
        }finally{
            if(renderer!=null)renderer.close();else{if(black!=null)black.close();if(white!=null)white.close();}
            if(!plate.isRecycled())plate.recycle();
        }
    }
    @Test public void sourceAndPosedBladeThresholdUsesOutputPixels(){
        NativeSourceProp2D.requirePosedGeometry(new float[]{.5f,.5f},new float[]{.5078125f,.5f},256,448);
        try{NativeSourceProp2D.requirePosedGeometry(new float[]{.5f,.5f},new float[]{.50390625f,.5f},256,448);fail("A one-pixel blade must reject before allocation");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("2 output pixels"));}
    }
    @Test public void exactSharedBodyKernelRejectsScaleBelowPointTwelveAndAboveThreePointFive(){
        Rig2DProfile source=profile("from","a",true,1);
        NativeSkinnedSprite2D.requireRenderablePose(source,scaled(source.joints,.125f),256,448);
        for(float scale:new float[]{.1f,4f}){
            try{NativeSkinnedSprite2D.requireRenderablePose(source,scaled(source.joints,scale),256,448);fail("Unsupported bone scale must reject");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("[0.12,3.5]"));}
        }
    }
    @Test public void inactiveAppearanceIsNotRejectedForAPoseItNeverDraws(){
        Rig2DProfile b0=profile("b0","a",true,1),w0=profile("w0","a",false,1);
        Rig2DProfile b1=profile("b1","b",true,.25f),w1=profile("w1","b",false,.25f);
        StoryboardPoseMotion.Frame frame=new StoryboardPoseMotion.Frame(b0.joints,w0.joints,new float[]{.7f,.5f},new float[]{.7f,.5f},0,0);
        NativeStoryboardArtworkRenderer.requireRenderableFrame(b0,w0,b1,w1,frame,0,0,256,448,false);
        try{NativeStoryboardArtworkRenderer.requireRenderableFrame(b0,w0,b1,w1,frame,.5,.5,256,448,false);fail("Both appearances are actually drawn during handoff, so the incompatible small texture must reject");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("[0.12,3.5]"));}
    }
    private static Rig2DProfile profile(String id,String asset,boolean black,float scale){
        return new Rig2DProfile("fixture-"+id,"fixture-source","fixture-"+asset,black,false,scaled(Rig2DProfileTest.joints(),scale),Rig2DProfileTest.outline());
    }
    private static float[][] scaled(float[][] points,float scale){
        float[][] result=SourceArtworkRasterFixture.copy(points);
        for(float[] point:result){point[0]=.42f+(point[0]-.42f)*scale;point[1]=.57f+(point[1]-.57f)*scale;}
        return result;
    }
}
