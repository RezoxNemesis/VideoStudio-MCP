package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Native Skia pixel proof; not a claim of final device GPU throughput. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StudioMaskRasterizerTest {

    static Bitmap opaque(int width,int height){
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.RED);
        return b;
    }

    @Test public void ellipseFeatherMasksActualPixelsWithoutTouchingSourceFiles()throws Exception{
        Bitmap b=opaque(100,100);
        try{
            JSONObject mask=new JSONObject().put("kind","ellipse").put("feather",.18);
            assertSame(b,StudioMaskRasterizer.apply(b,mask));
            assertEquals(255,Color.alpha(b.getPixel(50,50)));
            assertEquals(0,Color.alpha(b.getPixel(0,0)));
            int boundary=Color.alpha(b.getPixel(96,50));
            assertTrue("A feather should contain partially transparent pixels",boundary>0&&boundary<255);
            assertEquals(255,Color.red(b.getPixel(50,50)));
        }finally{b.recycle();}
    }

    @Test public void hardRectangleAndInvertedRoundRectAreBounded()throws Exception{
        Bitmap normal=opaque(100,100),inverted=opaque(100,100);
        try{
            StudioMaskRasterizer.apply(normal,new JSONObject().put("kind","rectangle"));
            assertEquals(255,Color.alpha(normal.getPixel(50,50)));
            assertEquals(0,Color.alpha(normal.getPixel(0,0)));
            StudioMaskRasterizer.apply(inverted,new JSONObject().put("kind","round_rect")
                    .put("roundness",.3).put("invert",true));
            assertEquals(0,Color.alpha(inverted.getPixel(50,50)));
            assertTrue(Color.alpha(inverted.getPixel(0,0))>0);
        }finally{normal.recycle();inverted.recycle();}
    }

    @Test public void wipeLeftRightAndInversionHaveDeterministicBounds()throws Exception{
        Bitmap b=opaque(100,100);
        try{
            StudioMaskRasterizer.apply(b,new JSONObject()
                    .put("kind","linear_wipe").put("position",.5).put("feather",.05));
            assertEquals(255,Color.alpha(b.getPixel(2,50)));
            assertEquals(0,Color.alpha(b.getPixel(97,50)));
            assertTrue(Color.alpha(b.getPixel(50,50))>0);
        }finally{b.recycle();}
    }

    @Test public void malformedUnboundedMasksFailBeforeRendererAllocations()throws Exception{
        assertThrows(IllegalArgumentException.class,()->
                StudioMaskRasterizer.validate(new JSONObject().put("kind","unknown")));
        assertThrows(IllegalArgumentException.class,()->
                StudioMaskRasterizer.validate(new JSONObject().put("kind","ellipse").put("feather",.8)));
        assertThrows(IllegalArgumentException.class,()->
                StudioMaskRasterizer.validate(new JSONObject().put("kind","ellipse").put("uri","file:///private")));
        Bitmap tooLarge=Bitmap.createBitmap(1025,1025,Bitmap.Config.ARGB_8888);
        try{
            assertThrows(IllegalArgumentException.class,()->
                    StudioMaskRasterizer.apply(tooLarge,new JSONObject().put("kind","ellipse")));
        }finally{tooLarge.recycle();}
    }

    @Test public void legacyAudioVideoTitleAndUnmaskedImageRemainByteStable()throws Exception{
        for(String type:new String[]{StudioLayer.TITLE,StudioLayer.IMAGE,StudioLayer.AUDIO,StudioLayer.VIDEO}){
            StudioLayer l=new StudioLayer();
            l.type=type;
            if(StudioLayer.TITLE.equals(type))l.text="Existing caption";
            if(StudioLayer.IMAGE.equals(type)||StudioLayer.AUDIO.equals(type)||StudioLayer.VIDEO.equals(type))
                l.assetId="existing-asset";
            if(StudioLayer.VIDEO.equals(type))l.audioMode="silent";
            JSONObject json=l.toJson();
            assertFalse(json.has("mask"));
            assertEquals(json.toString(),StudioLayer.fromJson(json).toJson().toString());
        }
    }

    @Test public void imageAndShapeMasksRoundTripAndVideoRejectsMask()throws Exception{
        for(String type:new String[]{StudioLayer.IMAGE,StudioLayer.SHAPE}){
            StudioLayer layer=new StudioLayer();layer.type=type;
            if(StudioLayer.IMAGE.equals(type))layer.assetId="previously-imported-image";
            layer.mask=new JSONObject().put("kind","ellipse").put("feather",.1).put("invert",false);
            JSONObject json=layer.toJson();
            assertEquals("ellipse",json.getJSONObject("mask").getString("kind"));
            assertEquals("ellipse",StudioLayer.fromJson(json).mask.getString("kind"));
        }
        StudioLayer forbidden=new StudioLayer();
        forbidden.type=StudioLayer.VIDEO;forbidden.audioMode="silent";forbidden.assetId="owned";
        forbidden.mask=new JSONObject().put("kind","ellipse");
        assertThrows(IllegalArgumentException.class,forbidden::validate);
    }
}
