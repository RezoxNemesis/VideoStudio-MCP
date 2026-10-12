package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

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
public final class NativeSkinnedSpritePortraitGeometryTest {
    @Test public void anotherActorsSourceSwordCannotStayBakedIntoTheBodyTexture()throws Exception{
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false,
                SourceArtworkRasterFixture.BLACK_MATERIAL,new float[]{.15f,.60f});
            NativeSkinnedSprite2D sprite=new NativeSkinnedSprite2D(fixture.context,fixture.from,fixture.blackFrom,
                    SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H)){
            Bitmap before=sprite.getCutoutForTesting(),after=null;
            try{
                assertTrue("Fixture must contain the other original weapon across this body",count(before,SourceArtworkRasterFixture.WHITE_SWORD)>10);
                sprite.excludeSourceProps(java.util.Arrays.asList(fixture.blackFrom.swordOutline,fixture.whiteFrom.swordOutline));
                after=sprite.getCutoutForTesting();
                SourceArtworkRasterFixture.writeEvidence("other_weapon_excluded_from_actor",before,after);
                assertEquals("The body must not carry another fighter's moving weapon material",0,count(after,SourceArtworkRasterFixture.WHITE_SWORD));
                assertTrue(sprite.hasSourceProp());
            }finally{before.recycle();if(after!=null)after.recycle();}
        }
    }
    private static int count(Bitmap image,int sought){
        int count=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if(image.getPixel(x,y)==sought)count++;return count;
    }
    @Test public void meshOpacityActuallyModulatesTheRenderedSourceAlpha() throws Exception {
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false);
            NativeSkinnedSprite2D sprite=new NativeSkinnedSprite2D(fixture.context,fixture.from,fixture.blackFrom,SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H)){
            Bitmap full=Bitmap.createBitmap(SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H,Bitmap.Config.ARGB_8888);
            Bitmap half=Bitmap.createBitmap(SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H,Bitmap.Config.ARGB_8888);
            try {
                sprite.render(new Canvas(full),fixture.blackFrom.joints,255);
                sprite.render(new Canvas(half),fixture.blackFrom.joints,128);
                SourceArtworkRasterFixture.writeEvidence("mesh_alpha_characterization",full,half);
                assertEquals(255,Color.alpha(full.getPixel(64,284)));
                assertEquals("Mesh opacity must affect actual output alpha, independent of identical texture colors",128,Color.alpha(half.getPixel(64,284)),1);
            }finally{full.recycle();half.recycle();}
        }
    }
    @Test public void rigidNinetyDegreePosePreservesPhysicalPortraitMaterialGeometry() throws Exception {
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false);
            NativeSkinnedSprite2D sprite=new NativeSkinnedSprite2D(fixture.context,fixture.from,fixture.blackFrom,SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H)){
            float[][] target=SourceArtworkRasterFixture.copy(fixture.blackFrom.joints);
            int width=SourceArtworkRasterFixture.W,height=SourceArtworkRasterFixture.H;
            for(float[] point:target){
                float dx=(point[0]-.5f)*width,dy=(point[1]-.5f)*height;
                point[0]=.5f-dy/width;point[1]=.5f+dx/height;
            }
            Bitmap still=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Bitmap turned=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {
                sprite.render(new Canvas(still),fixture.blackFrom.joints,255);
                sprite.render(new Canvas(turned),target,255);
                SourceArtworkRasterFixture.writeEvidence("portrait_physical_rotation",still,turned);
                double[] before=SourceArtworkRasterFixture.centroid(still,SourceArtworkRasterFixture.MARKER,false);
                double[] after=SourceArtworkRasterFixture.centroid(turned,SourceArtworkRasterFixture.MARKER,false);
                double expectedX=width*.5-(before[1]-height*.5),expectedY=height*.5+(before[0]-width*.5);
                assertEquals("Physical rotation must not use square normalized-UV angles on portrait artwork",expectedX,after[0],3);
                assertEquals("Original material must follow the same physical transform as its posed bones",expectedY,after[1],3);
            }finally{still.recycle();turned.recycle();}
        }
    }
}
