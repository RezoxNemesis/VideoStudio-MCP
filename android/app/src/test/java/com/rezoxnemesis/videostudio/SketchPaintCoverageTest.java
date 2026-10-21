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

/** Native raster evidence: one soft sweep must not become a flat accumulating band. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintCoverageTest {
    private JSONObject stroke(String coverage, boolean repeat) throws Exception {
        JSONArray points=new JSONArray("[[0.25,0.5,1],[0.5,0.5,1],[0.75,0.5,1]]");
        if(repeat)points.put(new JSONArray("[0.5,0.5,1]")).put(new JSONArray("[0.25,0.5,1]"));
        JSONObject brush=new JSONObject().put("mode","paint").put("color","#FF000000")
                .put("sizePx",32).put("opacity",.58).put("flow",1).put("hardness",0).put("spacing",.1);
        if(coverage!=null)brush.put("coverage",coverage);
        return new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","soft_sweep")
                .put("points",points).put("brush",brush);
    }
    private Bitmap paint(JSONObject request) throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        SketchPaintBrush.apply(bitmap,null,0,0,SketchPaintBrush.plan(request,256,256),null,1);
        return bitmap;
    }
    @Test public void maximumCoveragePreservesSoftProfileAcrossRepeatedSweeps() throws Exception {
        Bitmap single=paint(stroke("maximum",false)),repeat=paint(stroke("maximum",true));
        try {
            assertTrue("The same swept support cannot build a second flat band",single.sameAs(repeat));
            int center=Color.alpha(single.getPixel(128,128)),middle=Color.alpha(single.getPixel(128,136)),edge=Color.alpha(single.getPixel(128,142));
            assertTrue("Native hardness-zero profile must retain meaningful center-to-half-radius falloff",center-middle>=45);
            assertTrue(middle>edge && edge>0);
            assertEquals(0,single.getPixel(128,145));
        } finally {single.recycle();repeat.recycle();}
    }
    @Test public void maximumCoverageEqualsOneNearestSoftStampRatherThanManyOverlaps() throws Exception {
        JSONObject dot=stroke("maximum",false);dot.put("points",new JSONArray("[[0.5,0.5,1]]"));
        Bitmap stamp=paint(dot),sweep=paint(stroke("maximum",false));
        try {for(int y=128;y<=143;y++)assertEquals("Dense dabs must not over-darken the native feather at row"+y,stamp.getPixel(128,y),sweep.getPixel(128,y));}
        finally {stamp.recycle();sweep.recycle();}
    }
    @Test public void explicitAccumulateRetainsLegacyPixelsAndOmittedNormalizedRequest() throws Exception {
        JSONObject old=stroke(null,false),explicit=stroke("accumulate",false);
        SketchPaintBrush.Plan legacy=SketchPaintBrush.plan(old,256,256),modern=SketchPaintBrush.plan(explicit,256,256);
        assertFalse("Omitted option must not be injected into historical journal/fingerprint input",legacy.stroke.getJSONObject("brush").has("coverage"));
        JSONObject stripped=modern.stroke;stripped.getJSONObject("brush").remove("coverage");
        assertEquals(SketchDocument.canonical(legacy.stroke),SketchDocument.canonical(stripped));
        Bitmap a=paint(old),b=paint(explicit);
        try {assertTrue(a.sameAs(b));assertTrue("Legacy soft flow actually accumulates at half-radius",Color.alpha(a.getPixel(128,136))>130);}
        finally {a.recycle();b.recycle();}
    }
    @Test public void maximumStillHonorsFlowOpacityAndAlphaLock() throws Exception {
        JSONObject request=stroke("maximum",true);request.getJSONObject("brush").put("flow",.4);
        Bitmap image=paint(request);try {assertTrue(Color.alpha(image.getPixel(128,128))<=60);assertTrue(Color.alpha(image.getPixel(128,136))>0);}finally {image.recycle();}
        Bitmap before=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);before.eraseColor(0);
        for(int y=120;y<138;y++)for(int x=100;x<150;x++)before.setPixel(x,y,0x88FFFFFF);
        Bitmap locked=before.copy(Bitmap.Config.ARGB_8888,true);request.put("alphaLock",true);
        try {SketchPaintBrush.apply(locked,before,0,0,SketchPaintBrush.plan(request,256,256),null,1);assertEquals(136,Color.alpha(locked.getPixel(128,132)));assertEquals(0,locked.getPixel(128,139));assertNotEquals(before.getPixel(128,132),locked.getPixel(128,132));}
        finally {locked.recycle();before.recycle();}
    }
    @Test public void cloneSamplesThePinnedSourceAndEraseKeepsTheSameSoftMaximum()throws Exception {
        JSONObject clone=stroke("maximum",false);clone.getJSONObject("brush").put("mode","clone");clone.put("cloneSource",new JSONObject().put("objectId","source").put("sha256","a".repeat(64)));
        Bitmap cloned=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        SketchPaintBrush.Plan clonePlan=SketchPaintBrush.plan(clone,256,256);assertEquals(clone.getJSONObject("cloneSource").getString("objectId"),clonePlan.stroke.getJSONObject("cloneSource").getString("objectId"));assertEquals(clone.getJSONObject("cloneSource").getString("sha256"),clonePlan.stroke.getJSONObject("cloneSource").getString("sha256"));int[] sampled={0};
        try {SketchPaintBrush.apply(cloned,null,0,0,clonePlan,(x,y)->{sampled[0]++;return 0xFF00FF00;},1);int value=cloned.getPixel(128,136);assertTrue("Owned clone sampler must actually be used",sampled[0]>0);assertEquals(0x00FF00,value&0xFFFFFF);assertTrue(Color.alpha(value)>0&&Color.alpha(value)<90);}
        finally {cloned.recycle();}
        JSONObject erase=stroke("maximum",true);erase.getJSONObject("brush").put("mode","erase");
        Bitmap erased=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);erased.eraseColor(Color.RED);
        try {SketchPaintBrush.apply(erased,null,0,0,SketchPaintBrush.plan(erase,256,256),null,1);assertTrue(Color.alpha(erased.getPixel(128,136))>170);assertEquals(Color.RED,erased.getPixel(128,145));}
        finally {erased.recycle();}
    }
    @Test public void coverageIsStrictAndVectorFittedBrushesDoNotClaimRasterSemantics() throws Exception {
        for(Object bad:new Object[]{"Maximum","heal",1,true,JSONObject.NULL}) {
            JSONObject request=stroke(null,false);request.getJSONObject("brush").put("coverage",bad);
            assertThrows(IllegalArgumentException.class,()->SketchPaintBrush.plan(request,256,256));
        }
        JSONObject vector=SketchBrushes.style("graphite",1,.9,true,0).put("coverage","maximum");
        assertThrows(IllegalArgumentException.class,()->SketchBrushes.validate(vector));
    }
}
