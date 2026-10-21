package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchShadingTimingTest {
    private JSONObject document()throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","quality_study").put("width",256).put("height",256).put("frameCount",61));
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject()
                .put("id","subject").put("kind","rect").put("color","#FFFFFFFF").put("fill",true).put("exposure",61)
                .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
        return d;
    }
    private JSONObject radial()throws Exception {
        return new JSONObject().put("type","radial").put("cx",.5).put("cy",.5).put("radius",.45)
                .put("colors",new JSONArray().put("#FFFFFFFF").put("#FF20A0DF").put("#FF050A20"))
                .put("stops",new JSONArray().put(0).put(.5).put(1));
    }
    private JSONObject keyed(String mode,JSONArray curve)throws Exception {
        JSONObject d=document(),o=SketchDocument.object(d,"subject");
        JSONObject first=new JSONObject().put("frame",0).put("transform",SketchDocument.transform()).put("interpolation",mode);
        if(curve!=null)first.put("curve",curve);
        o.put("keys",new JSONArray().put(first).put(new JSONObject().put("frame",60)
                .put("transform",SketchDocument.transform().put("x",1)).put("interpolation","hold")));
        return d;
    }
    @Test public void actualNativeRadialShaderHasLightCenterColoredMidtoneAndDarkEdge()throws Exception {
        JSONObject d=document();SketchDocument.object(d,"subject").put("gradient",radial());SketchDocument.validate(d);
        try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){
            Bitmap b=r.render(0);try{
                int center=b.getPixel(128,128),middle=b.getPixel(185,128),edge=b.getPixel(250,128);
                assertTrue(Color.red(center)>245);assertTrue(Color.blue(middle)>Color.red(middle)+100);
                assertTrue(Color.red(edge)<15&&Color.green(edge)<20);
                assertEquals(Color.blue(b.getPixel(185,128)),Color.blue(b.getPixel(128,185)),3);
            }finally{b.recycle();}
        }
    }
    @Test public void invalidRadialStopsAndRadiusRejectBeforeApplyingAnEdit()throws Exception {
        for(JSONObject g:new JSONObject[]{radial().put("radius",0),radial().put("colors",new JSONArray().put("#FFFFFFFF")),
                radial().put("stops",new JSONArray().put(0).put(.5)),radial().put("stops",new JSONArray().put(0).put(0).put(1)),
                radial().put("stops",new JSONArray().put(.1).put(.5).put(1)),radial().put("cx",2)}){
            try{SketchDocument.validateGradient(g);fail("Invalid radial shading accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void existingLinearShadingIsStillValid()throws Exception {
        SketchDocument.validateGradient(new JSONObject().put("startColor","#FFFFFFFF").put("endColor","#FF000000")
                .put("x0",0).put("y0",0).put("x1",1).put("y1",1));
    }
    @Test public void materialShadingCanBeRemovedWithoutReplacingTheShape()throws Exception {
        JSONObject d=document();SketchDocument.object(d,"subject").put("gradient",radial());
        String geometry=SketchDocument.object(d,"subject").getJSONObject("bounds").toString();
        SketchEditOps.apply(d,new JSONObject().put("type","update_object").put("objectId","subject")
                .put("patch",new JSONObject().put("gradient",JSONObject.NULL)));
        assertFalse(SketchDocument.object(d,"subject").has("gradient"));
        assertEquals(geometry,SketchDocument.object(d,"subject").getJSONObject("bounds").toString());
    }
    @Test public void bezierUsesTheTimeCoordinateAndPreservesAuthoredGeometry()throws Exception {
        JSONObject d=keyed("bezier",new JSONArray().put(.42).put(0).put(1).put(1));SketchDocument.validate(d);
        JSONObject o=SketchDocument.object(d,"subject");String before=SketchDocument.canonical(o);
        assertEquals(.3153568125,SketchDocument.transformAt(o,30).getDouble("x"),.000001);
        assertEquals(0,SketchDocument.transformAt(o,0).getDouble("x"),0);
        assertEquals(1,SketchDocument.transformAt(o,60).getDouble("x"),0);
        assertEquals(before,SketchDocument.canonical(o));
    }
    @Test public void namedEasesGiveDifferentIntentionalTiming()throws Exception {
        double[] expected={.25,.0625,.4375,.15625,.125};
        String[] modes={"linear","ease_in","ease_out","smooth","ease_in_out"};
        for(int i=0;i<modes.length;i++){
            JSONObject d=keyed(modes[i],null);SketchDocument.validate(d);
            assertEquals(modes[i],expected[i],SketchDocument.transformAt(SketchDocument.object(d,"subject"),15).getDouble("x"),.000001);
        }
        assertEquals(0,SketchDocument.transformAt(SketchDocument.object(keyed("hold",null),"subject"),59).getDouble("x"),0);
    }
    @Test public void malformedOrOrphanedCurvesCannotDisappearSilently()throws Exception {
        for(JSONObject d:new JSONObject[]{keyed("bezier",null),keyed("bezier",new JSONArray().put(0).put(1)),
                keyed("bezier",new JSONArray().put(-.1).put(0).put(1).put(1)),keyed("linear",new JSONArray().put(0).put(0).put(1).put(1))}){
            try{SketchDocument.validate(d);fail("Invalid curve accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void nativeEditingOperationRetainsExplicitCurveControls()throws Exception {
        JSONObject d=document();JSONArray curve=new JSONArray().put(.42).put(0).put(1).put(1);
        SketchEditOps.apply(d,new JSONObject().put("type","set_keyframe").put("objectId","subject").put("frame",0)
                .put("transform",new JSONObject().put("x",0)).put("interpolation","bezier").put("curve",curve));
        SketchEditOps.apply(d,new JSONObject().put("type","set_keyframe").put("objectId","subject").put("frame",60)
                .put("transform",new JSONObject().put("x",1)).put("interpolation","hold"));
        assertEquals(curve.toString(),SketchDocument.object(d,"subject").getJSONArray("keys").getJSONObject(0).getJSONArray("curve").toString());
        assertEquals(.3153568125,SketchDocument.transformAt(SketchDocument.object(d,"subject"),30).getDouble("x"),.000001);
    }
    @Test public void preparedRigSamplingConsumesTheSameAuthoredTimeCurve()throws Exception {
        JSONObject source=document();JSONArray curve=new JSONArray().put(.42).put(0).put(1).put(1);
        JSONObject rig=new JSONObject().put("id","actor")
                .put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.5).put("y",.5)))
                .put("bindings",new JSONArray().put(new JSONObject().put("objectId","subject").put("jointId","root")))
                .put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("rootX",0).put("interpolation","bezier").put("curve",curve))
                        .put(new JSONObject().put("frame",60).put("rootX",.2)));
        JSONObject program=new JSONObject().put("schema","videostudio.sketch-animation.v1").put("frameCount",61).put("fps",24).put("rigs",new JSONArray().put(rig));
        JSONObject sample=SketchAnimationCompiler.sample(source,program,30);
        assertEquals(.5+.2*.3153568125,sample.getJSONArray("joints").getJSONObject(0).getDouble("x"),.000001);
    }
    @Test public void manualPoseSamplingAndJointEditsPreserveAnExistingBezierCurve()throws Exception {
        JSONObject rig=new JSONObject().put("id","actor").put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.5).put("y",.5)))
                .put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("rootX",0).put("interpolation","bezier")
                        .put("curve",new JSONArray().put(.42).put(0).put(1).put(1)))
                        .put(new JSONObject().put("frame",60).put("rootX",1)));
        JSONObject program=new JSONObject().put("rigs",new JSONArray().put(rig));
        assertEquals(.3153568125,SketchAnimationUiModel.previousPose(rig,30).getDouble("rootX"),.000001);
        SketchAnimationUiModel.setRotation(program,"actor",0,"root",10,"smooth");
        JSONObject changed=rig.getJSONArray("poses").getJSONObject(0);
        assertEquals("bezier",changed.getString("interpolation"));assertTrue(changed.has("curve"));
        SketchEasing.validate(changed);
        SketchAnimationUiModel.pose(program,"actor",0,"linear");assertFalse(changed.has("curve"));
    }
    @Test public void inverseTouchCameraUsesTheAuthoredBezierTimeCoordinate()throws Exception {
        JSONObject source=document(),program=new JSONObject().put("camera",new JSONArray()
                .put(new JSONObject().put("frame",0).put("x",0).put("interpolation","bezier")
                        .put("curve",new JSONArray().put(.42).put(0).put(1).put(1)))
                .put(new JSONObject().put("frame",60).put("x",1)));
        double[] world=SketchAnimationUiModel.canvasToWorld(source,program,30,.5,.5);
        assertEquals(.5-.3153568125,world[0],.000001);assertEquals(.5,world[1],0);
    }
}
