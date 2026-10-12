package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeSourceProp2DTest {
    @Test public void sourceSwordMaterialFollowsPhysicalPortraitRotation()throws Exception{
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false)){
            Bitmap original=NativeStoryboardSourceImage.read(fixture.context,fixture.from,SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H);
            try(NativeSourceProp2D prop=new NativeSourceProp2D(original,fixture.blackFrom)){
                Bitmap rest=Bitmap.createBitmap(original.getWidth(),original.getHeight(),Bitmap.Config.ARGB_8888);
                Bitmap turned=Bitmap.createBitmap(original.getWidth(),original.getHeight(),Bitmap.Config.ARGB_8888);
                try{
                    float[] hand=fixture.blackFrom.joints[Rig2DProfile.SWORD_HAND],tip=fixture.blackFrom.swordTip;
                    prop.render(new Canvas(rest),hand,tip,255);
                    prop.render(new Canvas(turned),turn(hand),turn(tip),255);
                    SourceArtworkRasterFixture.writeEvidence("original_sword_physical_rotation",rest,turned);
                    double[] before=SourceArtworkRasterFixture.centroid(rest,SourceArtworkRasterFixture.BLACK_SWORD,false);
                    double[] after=SourceArtworkRasterFixture.centroid(turned,SourceArtworkRasterFixture.BLACK_SWORD,false);
                    assertEquals(SourceArtworkRasterFixture.W*.5-(before[1]-SourceArtworkRasterFixture.H*.5),after[0],1.5);
                    assertEquals(SourceArtworkRasterFixture.H*.5+(before[0]-SourceArtworkRasterFixture.W*.5),after[1],1.5);
                }finally{rest.recycle();turned.recycle();}
            }finally{original.recycle();}
        }
    }
    @Test public void annotatedHiltOffsetIsNotInventedAtTheSwordHand()throws Exception{
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false)){
            float[] hand=fixture.blackFrom.joints[Rig2DProfile.SWORD_HAND],tip=fixture.blackFrom.swordTip;
            float[] hilt={hand[0]+(tip[0]-hand[0])*.15f,hand[1]+(tip[1]-hand[1])*.15f};
            JSONObject json=fixture.blackFrom.toJson().put("swordHilt",new JSONArray().put(hilt[0]).put(hilt[1]));
            Rig2DProfile profile=Rig2DProfile.fromJson(json);
            Bitmap original=NativeStoryboardSourceImage.read(fixture.context,fixture.from,SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H);
            try(NativeSourceProp2D prop=new NativeSourceProp2D(original,profile)){
                float[] actual=prop.predictedHilt(turn(hand),turn(tip)),expected=turn(hilt);
                assertEquals(expected[0],actual[0],.000001);assertEquals(expected[1],actual[1],.000001);
                assertTrue(Math.hypot(actual[0]-turn(hand)[0],actual[1]-turn(hand)[1])>.01);
            }finally{original.recycle();}
        }
    }
    private static float[] turn(float[] point){
        float dx=(point[0]-.5f)*SourceArtworkRasterFixture.W,dy=(point[1]-.5f)*SourceArtworkRasterFixture.H;
        return new float[]{.5f-dy/SourceArtworkRasterFixture.W,.5f+dx/SourceArtworkRasterFixture.H};
    }
}
