package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationCompilerTest {
    private JSONObject source(int frames) throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","prepared").put("width",320).put("height",480).put("frameCount",frames));
        JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        objects.put(item("arm","line",new JSONArray().put(point(.4,.4)).put(point(.6,.4)),0,frames));
        objects.put(item("leg","line",new JSONArray().put(point(.4,.4)).put(point(.4,.6)),0,frames));
        return d;
    }
    private JSONArray point(double x,double y)throws Exception{return new JSONArray().put(x).put(y).put(1);}
    private JSONObject item(String id,String kind,JSONArray points,int frame,int exposure)throws Exception {
        return new JSONObject().put("id",id).put("kind",kind).put("frame",frame).put("exposure",exposure).put("points",points).put("color","#FF214D63").put("fill",false).put("width",.008).put("transform",SketchDocument.transform()).put("keys",new JSONArray());
    }
    private JSONObject joint(String id,String parent,double x,double y)throws Exception {
        JSONObject j=new JSONObject().put("id",id).put("x",x).put("y",y);if(parent!=null)j.put("parent",parent);return j;
    }
    private JSONObject pose(int frame,String interpolation,double rootX,double rotation)throws Exception {
        return new JSONObject().put("frame",frame).put("interpolation",interpolation).put("rootX",rootX).put("rootY",0).put("rotations",new JSONObject().put("root",0).put("shoulder",rotation));
    }
    private JSONObject rig()throws Exception {
        return new JSONObject().put("id","hero").put("joints",new JSONArray().put(joint("root",null,.4,.4)).put(joint("shoulder","root",.4,.4)).put(joint("hand","shoulder",.6,.4)))
                .put("bindings",new JSONArray().put(new JSONObject().put("objectId","arm").put("jointId","shoulder")).put(new JSONObject().put("objectId","leg").put("jointId","root")))
                .put("poses",new JSONArray().put(pose(0,"linear",0,0)).put(pose(99,"linear",0,90))).put("ik",new JSONArray());
    }
    private JSONObject program(int frames)throws Exception{return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",frames).put("fps",24).put("rigs",new JSONArray().put(rig()));}
    private JSONObject compile(JSONObject source,JSONObject p)throws Exception{return SketchAnimationCompiler.compile(source,p,"animated");}
    @Test public void oneHundredEditableFramesHaveJointRelativeMotionAndPreservePreparedArtwork()throws Exception {
        JSONObject s=source(1);String original=SketchDocument.canonical(s);JSONObject compiled=compile(s,program(100));JSONObject d=compiled.getJSONObject("document");
        assertEquals(original,SketchDocument.canonical(s));assertEquals(100,d.getInt("frameCount"));assertEquals("animated",d.getString("id"));
        JSONObject arm=SketchDocument.object(d,"arm");assertEquals(100,arm.getInt("exposure"));assertEquals(90,SketchDocument.transformAt(arm,99).getDouble("rotation"),1e-8);
        JSONObject first=SketchAnimationCompiler.sample(s,program(100),0),last=SketchAnimationCompiler.sample(s,program(100),99);
        JSONObject hand=last.getJSONArray("joints").getJSONObject(2);assertEquals(.4,hand.getDouble("x"),1e-8);assertEquals(.4+64d/480,hand.getDouble("y"),1e-8);
        assertTrue(compiled.getJSONObject("report").getInt("articulatedMotionFrames")>0);assertFalse(compiled.getJSONObject("report").getBoolean("artisticQualityVerified"));
        assertEquals(first.getJSONArray("joints").getJSONObject(0).getDouble("x"),last.getJSONArray("joints").getJSONObject(0).getDouble("x"),0);
        SketchDocument.validate(d);
    }
    @Test public void smoothAndHoldTimingChangeTheActualRenderedObjectTransforms()throws Exception {
        JSONObject p=program(5),r=p.getJSONArray("rigs").getJSONObject(0);r.put("poses",new JSONArray().put(pose(0,"smooth",0,0)).put(pose(4,"linear",0,80)));
        JSONObject a=SketchDocument.object(compile(source(1),p).getJSONObject("document"),"arm");assertEquals(12.5,SketchDocument.transformAt(a,1).getDouble("rotation"),1e-8);
        r.getJSONArray("poses").getJSONObject(0).put("interpolation","hold");a=SketchDocument.object(compile(source(1),p).getJSONObject("document"),"arm");assertEquals(0,SketchDocument.transformAt(a,3).getDouble("rotation"),0);assertEquals(80,SketchDocument.transformAt(a,4).getDouble("rotation"),0);
    }
    @Test public void twoBoneContactsStayAtWorldTargetWhileTheHipMoves()throws Exception {
        JSONObject p=program(10),r=p.getJSONArray("rigs").getJSONObject(0);r.put("joints",new JSONArray().put(joint("root",null,.4,.3)).put(joint("knee","root",.4,.5)).put(joint("foot","knee",.4,.7)));
        r.getJSONArray("bindings").getJSONObject(0).put("jointId","root");r.put("poses",new JSONArray().put(new JSONObject().put("frame",0)).put(new JSONObject().put("frame",9).put("rootX",.06)));
        r.put("ik",new JSONArray().put(new JSONObject().put("hip","root").put("knee","knee").put("ankle","foot").put("start",0).put("end",9).put("x",.45).put("y",.65).put("bend",1)));
        JSONObject c=compile(source(1),p);assertTrue(c.getJSONObject("report").getDouble("maxFootContactErrorPixels")<1e-6);assertEquals(10,c.getJSONObject("report").getInt("contactSampleCount"));
        for(int frame=0;frame<10;frame++){JSONObject foot=SketchAnimationCompiler.sample(source(1),p,frame).getJSONArray("joints").getJSONObject(2);assertEquals(.45,foot.getDouble("x"),1e-8);assertEquals(.65,foot.getDouble("y"),1e-8);}
    }
    @Test public void unreachableFootContactFailsWithoutModifyingSource()throws Exception {
        JSONObject p=program(10),r=p.getJSONArray("rigs").getJSONObject(0);r.put("joints",new JSONArray().put(joint("root",null,.4,.3)).put(joint("knee","root",.4,.4)).put(joint("foot","knee",.4,.5)));
        r.getJSONArray("bindings").getJSONObject(0).put("jointId","root");r.put("poses",new JSONArray().put(new JSONObject().put("frame",0)));r.put("ik",new JSONArray().put(new JSONObject().put("hip","root").put("knee","knee").put("ankle","foot").put("start",0).put("end",9).put("x",.95).put("y",.95).put("bend",1)));
        assertRejected(source(1),p,"UNREACHABLE_IK_TARGET");
    }
    @Test public void authoredSkinWeightsProduceEditableDeformingCelsRatherThanGlobalMotion()throws Exception {
        JSONObject s=source(1);JSONObject p=program(3),r=p.getJSONArray("rigs").getJSONObject(0);r.put("poses",new JSONArray().put(pose(0,"linear",0,0)).put(pose(2,"linear",0,90)));
        r.getJSONArray("bindings").getJSONObject(0).put("weights",new JSONArray().put(new JSONObject().put("root",1)).put(new JSONObject().put("hand",1)));
        JSONObject d=compile(s,p).getJSONObject("document");JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");int cels=0;boolean endpointMoved=false;
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.getString("id").startsWith("deform_")){cels++;assertEquals(1,o.getInt("exposure"));if(o.getInt("frame")==2)endpointMoved=Math.abs(o.getJSONArray("points").getJSONArray(1).getDouble(1)-(.4+64d/480))<1e-8;}}
        assertEquals(3,cels);assertTrue(endpointMoved);SketchDocument.validate(d);
    }
    @Test public void sparsePoseValuesPersistAndCameraComposesAfterObjectArticulation()throws Exception {
        JSONObject p=program(3),r=p.getJSONArray("rigs").getJSONObject(0);r.put("poses",new JSONArray().put(pose(0,"linear",.1,30)).put(new JSONObject().put("frame",2).put("interpolation","linear").put("rotations",new JSONObject())));
        p.put("cameraLayerIds",new JSONArray().put("layer_1")).put("camera",new JSONArray().put(new JSONObject().put("frame",0).put("x",.05).put("y",0).put("zoom",1).put("rotation",0).put("interpolation","hold")));
        JSONObject d=compile(source(1),p).getJSONObject("document");assertEquals(30,SketchDocument.transformAt(SketchDocument.object(d,"arm"),2).getDouble("rotation"),0);assertEquals(.05,SketchDocument.transformAt(d.getJSONArray("layers").getJSONObject(0),2).getDouble("x"),1e-8);
    }
    @Test public void dialogueAndMouthCelsHaveExplicitFrameTimingAndRemainEditable()throws Exception {
        JSONObject s=source(1),p=program(5);p.getJSONArray("rigs").getJSONObject(0).put("poses",new JSONArray().put(pose(0,"hold",0,0)));
        p.put("dialogues",new JSONArray().put(new JSONObject().put("start",1).put("end",3).put("text","Catch it!").put("layerId","layer_1").put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.8).put("height",.2)).put("color","#FFFFFFFF").put("fontSize",.04)));
        p.put("mouthCels",new JSONArray().put(new JSONObject().put("objectId","leg").put("start",2).put("end",4)));
        JSONObject c=compile(s,p),d=c.getJSONObject("document");assertEquals(2,SketchDocument.object(d,"leg").getInt("frame"));assertEquals(3,SketchDocument.object(d,"leg").getInt("exposure"));
        JSONObject text=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(2);assertEquals("text",text.getString("kind"));assertEquals("Catch it!",text.getString("text"));assertEquals(1,text.getInt("frame"));assertEquals(3,text.getInt("exposure"));
        assertEquals(1,c.getJSONObject("report").getInt("dialogueCount"));assertEquals(1,c.getJSONObject("report").getInt("mouthCelCount"));
    }
    @Test public void invalidRigTopologyAndUnknownMappingsAreRejectedAtomically()throws Exception {
        JSONObject p=program(100),r=p.getJSONArray("rigs").getJSONObject(0);r.getJSONArray("joints").getJSONObject(0).put("parent","hand");assertRejected(source(1),p,"cycle");
        p=program(100);p.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings").getJSONObject(0).put("objectId","unknown");assertRejected(source(1),p,"Unknown sketch object");
        p=program(100);p.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings").put(new JSONObject().put("objectId","arm").put("jointId","hand"));assertRejected(source(1),p,"bound more than once");
    }
    @Test public void invalidSkinWeightsDoNotLeavePartialCels()throws Exception {
        JSONObject p=program(100);p.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings").getJSONObject(0).put("weights",new JSONArray().put(new JSONObject().put("root",.3)).put(new JSONObject().put("root",1)));assertRejected(source(1),p,"sum to one");
    }
    @Test public void outputBudgetIsCheckedBeforeAnOversizedFrameExpansion()throws Exception {
        JSONObject p=program(1200),s=source(1);JSONArray sourceObjects=s.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"),bindings=p.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings");
        for(int i=0;i<4;i++){String id="extra_"+i;sourceObjects.put(item(id,"line",new JSONArray().put(point(.3,.3)).put(point(.4,.4)),0,1));bindings.put(new JSONObject().put("objectId",id).put("jointId","shoulder"));}
        JSONArray alternating=new JSONArray();for(int frame=0;frame<1200;frame++)alternating.put(pose(frame,"hold",0,frame%2*90));p.getJSONArray("rigs").getJSONObject(0).put("poses",alternating);
        assertRejected(s,p,"budget");
    }
    @Test public void sourceAndOutputIdsMustDifferAndFrameArgumentsMustBeIntegers()throws Exception {
        try{SketchAnimationCompiler.compile(source(1),program(100),"prepared");fail("Overwriting source admitted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("source"));}
        JSONObject p=program(100).put("frameCount",100.5);assertRejected(source(1),p,"integer");
    }
    @Test public void previewSamplesOneFrameAndMatchesFullTimelineAtThatFrame()throws Exception {
        JSONObject source=source(1),p=program(100);JSONObject full=compile(source,p).getJSONObject("document"),preview=SketchAnimationCompiler.preview(source,p,51,"prepared").getJSONObject("document");
        assertEquals(1,preview.getInt("frameCount"));assertEquals("prepared",preview.getString("id"));
        assertEquals(SketchDocument.transformAt(SketchDocument.object(full,"arm"),51).getDouble("rotation"),SketchDocument.object(preview,"arm").getJSONObject("transform").getDouble("rotation"),.001);
    }
    @Test public void adaptiveKeysRespectEverySampleWithinQuarterPixel()throws Exception {
        JSONObject source=source(1),p=program(100);p.getJSONArray("rigs").getJSONObject(0).getJSONArray("poses").getJSONObject(0).put("interpolation","smooth");
        JSONObject result=compile(source,p),object=SketchDocument.object(result.getJSONObject("document"),"arm");assertTrue(object.getJSONArray("keys").length()<100);
        for(int frame=0;frame<100;frame++){
            JSONObject actual=SketchDocument.transformAt(object,frame),exact=SketchDocument.object(SketchAnimationCompiler.preview(source,p,frame,"prepared").getJSONObject("document"),"arm").getJSONObject("transform");
            double[] a=map(actual,.6*320,.4*480),b=map(exact,.6*320,.4*480);assertTrue(Math.hypot(a[0]-b[0],a[1]-b[1])<=.2500001);
        }
        assertTrue(result.getJSONObject("report").getDouble("maxBakedTransformApproximationPixels")<=.2500001);
    }
    @Test public void repeatedMouthCelIntervalsProduceDisjointEditableCopies()throws Exception {
        JSONObject p=program(10);p.getJSONArray("rigs").getJSONObject(0).put("poses",new JSONArray());p.put("mouthCels",new JSONArray().put(new JSONObject().put("objectId","arm").put("start",0).put("end",2)).put(new JSONObject().put("objectId","arm").put("start",6).put("end",9)));
        JSONObject result=compile(source(1),p);JSONArray objects=result.getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertEquals(3,objects.length());assertEquals(2,result.getJSONObject("report").getInt("mouthCelCount"));
        assertEquals(2,SketchAnimationCompiler.preview(source(1),p,1,"prepared").getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
        assertEquals(1,SketchAnimationCompiler.preview(source(1),p,4,"prepared").getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
        assertEquals(2,SketchAnimationCompiler.preview(source(1),p,8,"prepared").getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
    }
    @Test public void preparedLayerTransformsAreExplicitlyRejectedForRigBindings()throws Exception {
        JSONObject source=source(1);source.getJSONArray("layers").getJSONObject(0).getJSONObject("transform").put("x",.1);assertRejected(source,program(100),"identity prepared layer");
    }
    private double[] map(JSONObject t,double x,double y)throws Exception {double ax=t.getDouble("anchorX")*320,ay=t.getDouble("anchorY")*480,dx=(x-ax)*t.getDouble("scaleX"),dy=(y-ay)*t.getDouble("scaleY"),r=Math.toRadians(t.getDouble("rotation"));return new double[]{ax+t.getDouble("x")*320+Math.cos(r)*dx-Math.sin(r)*dy,ay+t.getDouble("y")*480+Math.sin(r)*dx+Math.cos(r)*dy};}
    @Test public void footPlantCanLockTheAuthoredSoleOrientation()throws Exception {
        JSONObject p=program(10),r=p.getJSONArray("rigs").getJSONObject(0);r.put("joints",new JSONArray().put(joint("root",null,.4,.3)).put(joint("knee","root",.4,.5)).put(joint("foot","knee",.4,.7)));
        r.getJSONArray("bindings").getJSONObject(0).put("jointId","foot");r.put("poses",new JSONArray().put(new JSONObject().put("frame",0)).put(new JSONObject().put("frame",9).put("rootX",.06)));
        r.put("ik",new JSONArray().put(new JSONObject().put("hip","root").put("knee","knee").put("ankle","foot").put("start",0).put("end",9).put("x",.45).put("y",.65).put("bend",1).put("rotation",0)));
        JSONObject compiled=compile(source(1),p);for(int frame=0;frame<10;frame++){JSONObject foot=SketchAnimationCompiler.sample(source(1),p,frame).getJSONArray("joints").getJSONObject(2);assertEquals(0,foot.getDouble("rotation"),1e-8);}
        assertEquals(1,compiled.getJSONObject("report").getInt("orientationContactCount"));assertTrue(compiled.getJSONObject("report").getDouble("maxFootContactRotationErrorDegrees")<1e-8);
    }
    @Test public void weightedCelsPreservePreparedLayerStackOrder()throws Exception {
        JSONObject p=program(3),r=p.getJSONArray("rigs").getJSONObject(0);r.put("poses",new JSONArray().put(pose(0,"linear",0,0)).put(pose(2,"linear",0,90)));
        r.getJSONArray("bindings").getJSONObject(0).put("weights",new JSONArray().put(new JSONObject().put("root",1)).put(new JSONObject().put("hand",1)));
        JSONArray objects=compile(source(1),p).getJSONObject("document").getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        assertTrue(objects.getJSONObject(0).getString("id").startsWith("deform_"));assertEquals("leg",objects.getJSONObject(3).getString("id"));
    }
    @Test public void malformedOptionalArraysAreRejectedRatherThanSilentlyIgnoringAnimation()throws Exception {
        assertRejected(source(1),program(100).put("camera","not-an-array"),"array");
        JSONObject p=program(100);p.getJSONArray("rigs").getJSONObject(0).put("poses","not-an-array");assertRejected(source(1),p,"array");
    }
    @Test public void unusedJointKeysMustNotClaimIndependentArticulation()throws Exception {
        JSONObject p=program(100),rig=p.getJSONArray("rigs").getJSONObject(0);rig.getJSONArray("bindings").getJSONObject(0).put("jointId","root");
        JSONObject report=compile(source(1),p).getJSONObject("report");assertFalse(report.getBoolean("independentArticulation"));assertEquals(0,report.getInt("articulatedMotionFrames"));
    }
    @Test public void repeatedCelIntervalsKeepTheOriginalCompositingSlot()throws Exception {
        JSONObject p=program(10);p.getJSONArray("rigs").getJSONObject(0).put("poses",new JSONArray());p.put("celRanges",new JSONArray().put(new JSONObject().put("objectId","arm").put("start",0).put("end",2)).put(new JSONObject().put("objectId","arm").put("start",6).put("end",9)));
        JSONObject doc=SketchAnimationCompiler.preview(source(1),p,8,"prepared").getJSONObject("document");JSONArray visible=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertTrue(visible.getJSONObject(0).getString("id").startsWith("cel_"));assertEquals("leg",visible.getJSONObject(1).getString("id"));
    }
    @Test public void hiddenLayersAndZeroOpacityPartsDoNotClaimIndependentArticulation()throws Exception {
        JSONObject source=source(1),p=program(100);source.getJSONArray("layers").getJSONObject(0).put("visible",false);
        assertFalse(compile(source,p).getJSONObject("report").getBoolean("independentArticulation"));
        source=source(1);SketchDocument.object(source,"arm").getJSONObject("transform").put("opacity",0);
        assertFalse(compile(source,p).getJSONObject("report").getBoolean("independentArticulation"));
        source=source(1);source.getJSONArray("layers").getJSONObject(0).put("opacity",0);
        assertFalse(compile(source,p).getJSONObject("report").getBoolean("independentArticulation"));
        source=source(1);source.getJSONArray("layers").getJSONObject(0).getJSONObject("transform").put("opacity",0);
        assertFalse(compile(source,p).getJSONObject("report").getBoolean("independentArticulation"));
    }
    @Test public void weightedGradientArtworkCannotSilentlySlideItsColours()throws Exception {
        JSONObject source=source(1),p=program(3);SketchDocument.object(source,"arm").put("gradient",new JSONObject().put("startColor","#FF0000FF").put("endColor","#FFFFFFFF").put("x0",.4).put("y0",.4).put("x1",.6).put("y1",.4));
        p.getJSONArray("rigs").getJSONObject(0).put("poses",new JSONArray().put(pose(0,"linear",0,0)).put(pose(2,"linear",0,90)));
        p.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings").getJSONObject(0).put("weights",new JSONArray().put(new JSONObject().put("root",1)).put(new JSONObject().put("hand",1)));
        assertRejected(source,p,"gradient");
    }
    private void assertRejected(JSONObject source,JSONObject p,String message)throws Exception {
        String before=SketchDocument.canonical(source);try{compile(source,p);fail("Invalid animation admitted: "+message);}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(message));}assertEquals(before,SketchDocument.canonical(source));
    }
}
