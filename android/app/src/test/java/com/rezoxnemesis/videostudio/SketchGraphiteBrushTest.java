package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchGraphiteBrushTest {
    private static Bitmap render(boolean erase,boolean pressure,double opacity)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888);
        if(erase)bitmap.eraseColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(70,50,90));
        if(erase)paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        SketchBrushes.draw(new Canvas(bitmap),new JSONArray("[[0.125,0.501953125,0.3],[0.875,0.501953125,0.9]]"),
                512,256,1,paint,SketchBrushes.style("graphite",opacity,.6,pressure,0));
        return bitmap;
    }
    @Test public void subpixelShadingHasNoBeadGapsAndRetainsPressureAndOpacity()throws Exception{
        Bitmap a=render(false,true,1),b=render(false,true,.4),uniform=render(false,false,1);
        try{
            for(int x=70;x<440;x++)assertTrue("Continuous pressure core missing at "+x,Color.alpha(a.getPixel(x,128))>0);
            long start=0,end=0,light=0;
            for(int x=75;x<150;x++)start+=Color.alpha(a.getPixel(x,128));
            for(int x=350;x<425;x++){end+=Color.alpha(a.getPixel(x,128));light+=Color.alpha(b.getPixel(x,128));}
            assertTrue("Pressure must change coverage",end>start*1.3);
            assertTrue("Layer opacity must remain effective",end>light*1.7);
            assertTrue(Color.alpha(uniform.getPixel(90,128))>Color.alpha(a.getPixel(90,128)));
            assertEquals(0,a.getPixel(256,123));assertEquals(0,a.getPixel(20,128));
        }finally{a.recycle();b.recycle();uniform.recycle();}
    }
    @Test public void nativeReplayIsPixelExactAndGraphiteEraserActuallyErases()throws Exception{
        Bitmap a=render(false,true,.8),b=render(false,true,.8),erase=render(true,true,.8);
        try{
            int[] first=new int[512*256],second=new int[first.length];
            a.getPixels(first,0,512,0,0,512,256);b.getPixels(second,0,512,0,0,512,256);
            assertArrayEquals(first,second);
            assertTrue(Color.alpha(erase.getPixel(350,128))<250);
            assertEquals(Color.WHITE,erase.getPixel(350,123));
            assertArrayEquals(new String[]{"ink","pencil","marker","airbrush","charcoal","calligraphy","hairline","engraving"},
                    java.util.Arrays.copyOf(SketchBrushes.PRESETS,8));
        }finally{a.recycle();b.recycle();erase.recycle();}
    }
    @Test public void redundantCollinearControlPointCannotDoubleCoreDensityOrErasing()throws Exception{
        // At this source alpha the fine grain rounds to zero. The native pressure
        // core remains visible, exposing double application at segment/cap joins.
        JSONArray points=new JSONArray("[[.125,.5,1],[.5,.5,1],[.875,.5,1]]");
        for(boolean erase:new boolean[]{false,true}){
            Bitmap bitmap=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888);
            try{
                if(erase)bitmap.eraseColor(Color.WHITE);
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0x0246325A);
                if(erase)paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                SketchBrushes.draw(new Canvas(bitmap),points,512,256,32,paint,
                        SketchBrushes.style("graphite",1,0,false,0));
                for(int x=80;x<432;x++)assertEquals("Graphite coverage changed near a control point at "+x,
                        erase?254:1,Color.alpha(bitmap.getPixel(x,128)));
            }finally{bitmap.recycle();}
        }
    }
}
