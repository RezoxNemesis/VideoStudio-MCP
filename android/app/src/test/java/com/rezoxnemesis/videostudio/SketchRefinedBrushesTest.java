package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchRefinedBrushesTest {
    private static JSONArray points(){
        return new JSONArray()
                .put(new JSONArray().put(.12).put(.42).put(.6))
                .put(new JSONArray().put(.32).put(.29).put(.9))
                .put(new JSONArray().put(.48).put(.33).put(.8))
                .put(new JSONArray().put(.66).put(.37).put(.5))
                .put(new JSONArray().put(.82).put(.25).put(.7));
    }
    private static int[] paint(String preset)throws Exception {
        int size=240;
        Bitmap output=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(output);
            Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
            pen.setColor(Color.WHITE);
            SketchBrushes.draw(canvas,points(),size,size,8,pen,
                    SketchBrushes.style(preset,1,1,true,0));
            int[] pixels=new int[size*size];
            output.getPixels(pixels,0,size,0,0,size,size);
            return pixels;
        }finally{output.recycle();}
    }
    @Test public void detailedHairAndEngravingPresetsAreExplicitAndValidated() throws Exception{
        assertEquals("hairline",SketchBrushes.style("hairline",1,1,true,0).getString("preset"));
        assertEquals("engraving",SketchBrushes.style("engraving",.8,.9,true,0).getString("preset"));
        try{SketchBrushes.style("uncertain_auto_anatomy",1,1,true,0);fail("unknown brush accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("brush"));}
    }
    @Test public void eachRefinedBrushRendersActualRepeatablePixels() throws Exception {
        for(String preset:new String[]{"hairline","engraving"}){
            int[] a=paint(preset),b=paint(preset);
            assertArrayEquals("Brush must be deterministic for saved replay",a,b);
            int nonzero=0;for(int color:a)if(Color.alpha(color)>0)nonzero++;
            assertTrue("Brush must put ink on the native Canvas",nonzero>80);
        }
    }
    @Test public void engravingHasMoreFineMaterialThanHairline() throws Exception {
        int[] hair=paint("hairline"),engrave=paint("engraving");
        int hairPixels=0,engravedPixels=0;
        for(int v:hair)if(Color.alpha(v)>0)hairPixels++;
        for(int v:engrave)if(Color.alpha(v)>0)engravedPixels++;
        assertTrue("Engraving should leave stronger strokes",engravedPixels>hairPixels);
    }
}
