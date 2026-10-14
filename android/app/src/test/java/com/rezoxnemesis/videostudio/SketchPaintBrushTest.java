package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintBrushTest {
    JSONObject stroke(String mode,double size,double pressure)throws Exception {return new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","stroke").put("points",new JSONArray().put(new JSONArray().put(.15).put(.5).put(pressure)).put(new JSONArray().put(.85).put(.5).put(pressure))).put("brush",new JSONObject().put("mode",mode).put("color","#FFBB4433").put("sizePx",size));}
    Bitmap paint(JSONObject op)throws Exception {Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);SketchPaintBrush.apply(b,null,0,0,SketchPaintBrush.plan(op,256,256),null,1);return b;}
    int vertical(Bitmap b,int x){int count=0;for(int y=0;y<b.getHeight();y++)if(Color.alpha(b.getPixel(x,y))>0)count++;return count;}
    @Test public void pressureChangesActualRasterWidthAndPreservesUncoveredPixels()throws Exception {
        Bitmap thin=paint(stroke("paint",32,.15)),thick=paint(stroke("paint",32,1));try{assertTrue(vertical(thick,128)>vertical(thin,128)*2);assertEquals(Color.rgb(187,68,51),thick.getPixel(128,128));assertEquals(0,thick.getPixel(128,20));}finally{thin.recycle();thick.recycle();}
    }
    @Test public void softnessFlowAndAlphaLockChangePixelsWithoutEscapingExistingAlpha()throws Exception {
        JSONObject op=stroke("paint",40,1);op.getJSONObject("brush").put("hardness",.2).put("flow",.15);Bitmap b=paint(op);try{assertTrue(Color.alpha(b.getPixel(128,128))>Color.alpha(b.getPixel(128,145)));assertTrue(Color.alpha(b.getPixel(128,145))>0);}finally{b.recycle();}
        Bitmap base=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);base.eraseColor(0);for(int y=115;y<140;y++)for(int x=115;x<140;x++)base.setPixel(x,y,0x880000FF);Bitmap locked=base.copy(Bitmap.Config.ARGB_8888,true);op.put("alphaLock",true);SketchPaintBrush.apply(locked,base,0,0,SketchPaintBrush.plan(op,256,256),null,1);try{assertEquals(136,Color.alpha(locked.getPixel(128,128)));assertNotEquals(base.getPixel(128,128),locked.getPixel(128,128));assertEquals(0,locked.getPixel(110,128));}finally{base.recycle();locked.recycle();}
    }
    @Test public void erasingAndClipAffectOnlyCoveredPixels()throws Exception {
        Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);JSONObject op=stroke("erase",32,1).put("clip",new JSONObject().put("x",.45).put("y",.4).put("width",.1).put("height",.2));SketchPaintBrush.apply(b,null,0,0,SketchPaintBrush.plan(op,256,256),null,1);try{assertEquals(0,Color.alpha(b.getPixel(128,128)));assertEquals(Color.BLUE,b.getPixel(80,128));assertEquals(Color.BLUE,b.getPixel(128,40));}finally{b.recycle();}
    }
    @Test public void seededGrainIsRepeatableAndFlatTipAngleChangesItsFootprint()throws Exception {
        JSONObject op=stroke("paint",36,1);op.getJSONObject("brush").put("texture","grain").put("seed",42).put("flow",.4);Bitmap a=paint(op),b=paint(op);op.getJSONObject("brush").put("seed",43);Bitmap c=paint(op);try{assertTrue(a.sameAs(b));assertFalse(a.sameAs(c));}finally{a.recycle();b.recycle();c.recycle();}
        op=stroke("paint",40,1);op.put("points",new JSONArray().put(new JSONArray().put(.5).put(.5).put(1)));op.getJSONObject("brush").put("tip","flat").put("angle",0);Bitmap horizontal=paint(op);op.getJSONObject("brush").put("angle",90);Bitmap upright=paint(op);try{assertTrue(vertical(upright,128)>vertical(horizontal,128)*2);}finally{horizontal.recycle();upright.recycle();}
    }
    @Test public void planningRejectsPathologicalWorkAndUnrecognizedFieldsBeforeRasterization()throws Exception {
        JSONObject huge=stroke("paint",.5,1);huge.getJSONObject("brush").put("spacing",.05);assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(huge,2048,2048));
        JSONObject unknown=stroke("paint",8,1).put("hiddenPicture","paste");assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(unknown,256,256));
        JSONObject invalid=stroke("erase",8,1).put("alphaLock",true);assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(invalid,256,256));
    }
    @Test public void opacityCapsStrokeCoverageWhileFlowAccumulatesAcrossDabs()throws Exception {
        JSONObject opacity=stroke("paint",32,1),flow=stroke("paint",32,1);
        opacity.getJSONObject("brush").put("opacity",.2).put("flow",1);
        flow.getJSONObject("brush").put("opacity",1).put("flow",.2);
        Bitmap capped=paint(opacity),accumulated=paint(flow);
        try{
            assertEquals("Overlapping dabs must retain the selected 20% stroke opacity",51,Color.alpha(capped.getPixel(128,128)));
            assertTrue("Flow builds coverage within the stroke",Color.alpha(accumulated.getPixel(128,128))>51);
            assertFalse(capped.sameAs(accumulated));
        }finally{capped.recycle();accumulated.recycle();}
    }
    @Test public void frameIndexIsPreservedAndRequiresABoundedInteger()throws Exception {
        JSONObject selected=stroke("paint",16,1).put("frameIndex",5);
        SketchPaintBrush.Plan plan=null;
        try{plan=SketchPaintBrush.plan(selected,256,256);}
        catch(IllegalArgumentException rejected){fail("A valid selected frame must be accepted: "+rejected.getMessage());}
        assertEquals(5,plan.stroke.getInt("frameIndex"));
        selected.put("frameIndex",0);assertEquals(0,SketchPaintBrush.plan(selected,256,256).stroke.getInt("frameIndex"));
        selected.put("frameIndex",1199);assertEquals(1199,SketchPaintBrush.plan(selected,256,256).stroke.getInt("frameIndex"));
        for(Object invalid:new Object[]{-1,1200,5.5,"5",JSONObject.NULL}){
            selected.put("frameIndex",invalid);
            assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(selected,256,256));
        }
    }
    @Test public void cursorTracksVisiblePaintAndPhysicalPressure()throws Exception {
        JSONObject operation=stroke("paint",16,1);
        operation.put("points",new JSONArray().put(new JSONArray().put(.15).put(.5).put(.25))
                .put(new JSONArray().put(.85).put(.5).put(1)));
        SketchPaintBrush.Plan plan=SketchPaintBrush.plan(operation,256,256);
        JSONObject hover=plan.cursorAt(0),first=plan.cursorAt(.0001),middle=plan.cursorAt(.5),last=plan.cursorAt(1);
        assertEquals(.15,hover.getDouble("x"),.000001);assertEquals(.5,hover.getDouble("y"),.000001);assertEquals(0,hover.getDouble("pressure"),0);
        assertEquals(.15,first.getDouble("x"),.000001);assertEquals(.25,first.getDouble("pressure"),.000001);
        assertEquals(.5,middle.getDouble("x"),.004);assertEquals(.625,middle.getDouble("pressure"),.01);
        assertEquals(.85,last.getDouble("x"),.000001);assertEquals(1,last.getDouble("pressure"),0);
        Bitmap painted=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        try{SketchPaintBrush.apply(painted,null,0,0,plan,null,.5);
            assertTrue("Cursor must indicate the actual visible brush footprint",Color.alpha(painted.getPixel((int)(middle.getDouble("x")*256),(int)(middle.getDouble("y")*256)))>0);
            assertEquals(0,painted.getPixel(220,128));
        }finally{painted.recycle();}
        operation.getJSONObject("brush").put("pressure",false);
        assertEquals("Disabling width pressure must preserve physical cursor pressure",.625,SketchPaintBrush.plan(operation,256,256).cursorAt(.5).getDouble("pressure"),.01);
        assertThrows(IllegalArgumentException.class,()->plan.cursorAt(-.01));
        assertThrows(IllegalArgumentException.class,()->plan.cursorAt(1.01));
        assertThrows(IllegalArgumentException.class,()->plan.cursorAt(Double.NaN));
    }
}
