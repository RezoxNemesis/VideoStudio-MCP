package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Additional guards; the original executed14-case RED source stays byte-identical. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchLayerGroupMotionBoundaryTest {
    final SketchLayerGroupMotionTest s=new SketchLayerGroupMotionTest();
    @Test public void aFullUnwrappedSpinMovesInBetweenAndReturnsEveryNativePixelExactly()throws Exception{s.worker(()->{
        String id=s.fixture();JSONObject before=s.doc(id);JSONObject op=new JSONObject().put("type","set_layer_group_motion").put("layerIds",new JSONArray().put("layer_1").put("fruit")).put("pivot",new JSONArray().put(.5).put(.5)).put("poses",new JSONArray().put(s.pose(0,0,0,0,"linear")).put(s.pose(95,0,0,360,"hold")));
        s.edit(id,"spin",op);JSONObject after=s.doc(id);assertArrayEquals("360-degree returned native pixels are exactly neutral",s.pixels(before,0),s.pixels(after,95));assertFalse("An authored full spin must not be normalized into a stationary track",java.util.Arrays.equals(s.pixels(before,0),s.pixels(after,48)));
        for(int f=0;f<96;f++)for(String layer:new String[]{"layer_1","fruit"}){
            Matrix group=new Matrix();group.setTranslate(SketchLayerGroupMotionTest.W*.5f,SketchLayerGroupMotionTest.H*.5f);group.preRotate(360*f/95f);group.preTranslate(-SketchLayerGroupMotionTest.W*.5f,-SketchLayerGroupMotionTest.H*.5f);Matrix expected=new Matrix();expected.setConcat(group,SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(before,layer),f),SketchLayerGroupMotionTest.W,SketchLayerGroupMotionTest.H));Matrix actual=SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(after,layer),f),SketchLayerGroupMotionTest.W,SketchLayerGroupMotionTest.H);float[] a={0,0,512,0,0,384,512,384},b=a.clone();expected.mapPoints(a);actual.mapPoints(b);for(int n=0;n<a.length;n+=2)assertTrue("Actual native full-spin matrix frame "+f,Math.hypot(a[n]-b[n],a[n+1]-b[n+1])<=SketchLayerGroupMotionTest.GRID);
        }
    });}
    @Test public void oneFrameShotAcceptsOnePoseAndKeepsOneLocalFrame()throws Exception{s.worker(()->{
        String id=s.blank(1);s.edit(id,"fruit",new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","fruit")));JSONObject op=new JSONObject().put("type","set_layer_group_motion").put("layerIds",new JSONArray().put("layer_1").put("fruit")).put("pivot",new JSONArray().put(.5).put(.5)).put("poses",new JSONArray().put(s.pose(0,.02,-.01,9,"hold")));s.edit(id,"one",op);JSONObject after=s.doc(id);assertEquals(1,after.getInt("frameCount"));for(String layer:new String[]{"layer_1","fruit"}){assertEquals(1,SketchDocument.layer(after,layer).getJSONArray("keys").length());JSONObject key=s.keyAt(SketchDocument.layer(after,layer),0).getJSONObject("transform");assertEquals(.02,key.getDouble("x"),1e-12);assertEquals(-.01,key.getDouble("y"),1e-12);assertEquals(9,key.getDouble("rotation"),0);}
        JSONObject duplicated=SketchDocument.copy(op);duplicated.getJSONArray("poses").put(s.pose(0,0,0,0,"hold"));s.rejected(id,duplicated);
    });}
    @Test public void composedTransformOverflowAndCancelledPlanningLeaveNoPartialTrack()throws Exception{s.worker(()->{
        String id=s.fixture();s.edit(id,"near_limit",new JSONObject().put("type","set_transform").put("layerId","fruit").put("transform",new JSONObject().put("x",3.99)));JSONObject op=s.motion();for(int n=0;n<op.getJSONArray("poses").length();n++)op.getJSONArray("poses").getJSONObject(n).put("x",.2);JSONObject before=s.doc(id),detached=SketchDocument.copy(before);assertThrows(Exception.class,()->SketchEditOps.apply(detached,op));assertEquals(s.canonical(before),s.canonical(detached));s.rejected(id,op);
        JSONObject cancelled=SketchDocument.copy(before);Thread.currentThread().interrupt();try{assertThrows(java.io.InterruptedIOException.class,()->SketchEditOps.apply(cancelled,s.zero()));assertEquals(s.canonical(before),s.canonical(cancelled));}finally{Thread.interrupted();}
    });}
}
