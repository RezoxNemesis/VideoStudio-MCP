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
public class SketchShapeAnimationTest {
    static JSONArray shape(double x)throws Exception {
        return new JSONArray().put(new JSONArray().put("M").put(x).put(.3))
                .put(new JSONArray().put("C").put(x+.05).put(.2).put(x+.15).put(.2).put(x+.2).put(.3))
                .put(new JSONArray().put("L").put(x+.2).put(.7)).put(new JSONArray().put("L").put(x).put(.7)).put(new JSONArray().put("Z"));
    }
    static JSONObject key(int frame,double x,String mode)throws Exception {
        JSONObject k=new JSONObject().put("frame",frame).put("commands",shape(x)).put("interpolation",mode);
        if("bezier".equals(mode))k.put("curve",new JSONArray().put(.42).put(0).put(1).put(1));return k;
    }
    static JSONObject document()throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","shape_qa").put("width",256).put("height",256).put("frameCount",61));
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject()
                .put("id","subject").put("kind","path").put("commands",shape(.1)).put("fill",true).put("color","#FFFF0000").put("exposure",61)));
        return d;
    }
    static JSONObject animated()throws Exception {
        JSONObject d=document();SketchDocument.object(d,"subject").put("shapeKeys",new JSONArray().put(key(0,.1,"bezier")).put(key(60,.7,"hold")));return d;
    }
    @Test public void actualNativeFramesFollowTheBezierShapeWithoutChangingSource()throws Exception {
        JSONObject d=animated();String original=SketchDocument.canonical(d);SketchDocument.validate(d);
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){
            Bitmap first=renderer.render(0),middle=renderer.render(30),last=renderer.render(60);
            try{
                assertEquals(Color.RED,first.getPixel(50,128));assertEquals(Color.WHITE,middle.getPixel(50,128));
                assertEquals(Color.RED,middle.getPixel(100,128));assertEquals(Color.RED,last.getPixel(210,128));
            }finally{first.recycle();middle.recycle();last.recycle();}
        }
        assertEquals(original,SketchDocument.canonical(d));
    }
    @Test public void revisionBoundShapeEditsPersistDetachedCommandsAndRejectInvalidTopology()throws Exception {
        JSONObject d=document();String id="shape_store_"+java.util.UUID.randomUUID();d.put("id",id);
        try(SketchProjectStore store=new SketchProjectStore(RuntimeEnvironment.getApplication())){
            store.importOwnedDocument(d,"create");
            JSONObject operation=new JSONObject().put("type","set_shape_key").put("objectId","subject").put("frame",0)
                    .put("commands",shape(.1)).put("interpolation","bezier").put("curve",new JSONArray().put(.42).put(0).put(1).put(1));
            SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","shape_key_1").put("operations",new JSONArray().put(operation)));
            operation.getJSONArray("commands").getJSONArray(0).put(1,.8);
            JSONObject saved=store.get(id);assertEquals(.1,SketchDocument.object(saved,"subject").getJSONArray("shapeKeys").getJSONObject(0).getJSONArray("commands").getJSONArray(0).getDouble(1),0);
            String before=SketchDocument.canonical(saved);
            JSONObject bad=new JSONObject(operation.toString()).put("commands",new JSONArray().put(new JSONArray().put("M").put(.1).put(.2)).put(new JSONArray().put("L").put(.4).put(.5)));
            try{SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","bad_shape").put("operations",new JSONArray().put(bad)));fail("Incompatible topology persisted");}catch(IllegalArgumentException expected){}
            assertEquals(before,SketchDocument.canonical(store.get(id)));
        }
    }
    @Test public void duplicateCelFreezesTheSampledCurveAndFrameEditsShiftItsKeys()throws Exception {
        JSONObject d=animated();
        SketchEditOps.apply(d,new JSONObject().put("type","duplicate_cel").put("layerId","layer_1").put("fromFrame",30).put("toFrame",61).put("idPrefix","frozen"));
        JSONObject frozen=SketchDocument.object(d,"frozen_0");
        assertEquals(.1+.6*.3153568125,frozen.getJSONArray("commands").getJSONArray(0).getDouble(1),.000001);
        assertFalse(frozen.has("shapeKeys"));
        SketchEditOps.apply(d,new JSONObject().put("type","insert_frame").put("frame",20));
        assertEquals(61,SketchDocument.object(d,"subject").getJSONArray("shapeKeys").getJSONObject(1).getInt("frame"));
        SketchEditOps.apply(d,new JSONObject().put("type","delete_frame").put("frame",20));
        assertEquals(60,SketchDocument.object(d,"subject").getJSONArray("shapeKeys").getJSONObject(1).getInt("frame"));
    }
    @Test public void preparedPreviewSamplesShapeGeometryBeforeFlatteningItsFrameClock()throws Exception {
        JSONObject d=animated(),program=new JSONObject().put("schema","videostudio.sketch-animation.v1").put("frameCount",61).put("fps",24).put("rigs",new JSONArray());
        JSONObject preview=SketchAnimationCompiler.preview(d,program,30,"preview_shape").getJSONObject("document");
        JSONObject o=SketchDocument.object(preview,"subject");
        assertEquals(.1+.6*.3153568125,o.getJSONArray("commands").getJSONArray(0).getDouble(1),.000001);assertFalse(o.has("shapeKeys"));
        SketchDocument.validate(preview);
    }
    @Test public void malformedTopologyFramesAndCurveControlsAreRejected()throws Exception {
        for(int i=0;i<5;i++){
            JSONObject d=animated(),o=SketchDocument.object(d,"subject"),k=o.getJSONArray("shapeKeys").getJSONObject(1);
            if(i==0)k.getJSONArray("commands").getJSONArray(1).put(0,"Q");
            if(i==1)k.put("frame",0);
            if(i==2)k.put("frame",61);
            if(i==3)k.put("interpolation","bezier");
            if(i==4)k.getJSONArray("commands").getJSONArray(0).put(1,2);
            try{SketchDocument.validate(d);fail("Malformed shape key accepted: "+i);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void shapeGeometryIsPagedAfterSamplingWithoutEmbeddingOtherKeysGeometry()throws Exception {
        JSONObject d=animated();String id="shape_page_"+java.util.UUID.randomUUID();d.put("id",id);
        try(SketchProjectStore store=new SketchProjectStore(RuntimeEnvironment.getApplication())){store.importOwnedDocument(d,"create");}
        JSONObject page=SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_get",new JSONObject().put("sketchId",id)
                .put("expectedRevision",1).put("objectId","subject").put("shapeFrame",30).put("commandLimit",1));
        JSONObject o=page.getJSONObject("object");assertEquals(1,o.getJSONArray("commands").length());assertTrue(page.getBoolean("moreCommands"));
        assertEquals(.1+.6*.3153568125,o.getJSONArray("commands").getJSONArray(0).getDouble(1),.000001);
        for(int i=0;i<o.getJSONArray("shapeKeys").length();i++)assertFalse(o.getJSONArray("shapeKeys").getJSONObject(i).has("commands"));
    }
    @Test public void weightedRigDeformationConsumesTheSampledCurvedShape()throws Exception {
        JSONObject d=animated();JSONArray weights=new JSONArray();for(int i=0;i<6;i++)weights.put(new JSONObject().put("root",1));
        JSONObject rig=new JSONObject().put("id","actor").put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.5).put("y",.5)))
                .put("bindings",new JSONArray().put(new JSONObject().put("objectId","subject").put("jointId","root").put("weights",weights)))
                .put("poses",new JSONArray().put(new JSONObject().put("frame",0)));
        JSONObject p=new JSONObject().put("schema","videostudio.sketch-animation.v1").put("frameCount",61).put("fps",24).put("rigs",new JSONArray().put(rig));
        JSONObject baked=SketchAnimationCompiler.compile(d,p,"weighted_shape").getJSONObject("document");
        JSONObject cel=baked.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(30);
        assertFalse(cel.has("shapeKeys"));assertEquals(.1+.6*.3153568125,cel.getJSONArray("commands").getJSONArray(0).getDouble(1),.000001);
        assertEquals("C",cel.getJSONArray("commands").getJSONArray(1).getString(0));
    }
    @Test public void shorterBakeRefusesToDiscardRetainedShapeTiming()throws Exception {
        JSONObject p=new JSONObject().put("schema","videostudio.sketch-animation.v1").put("frameCount",30).put("fps",24).put("rigs",new JSONArray());
        try{SketchAnimationCompiler.compile(animated(),p,"truncated_shape");fail("Shape keys were silently truncated");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("shape keys"));}
    }
    @Test public void shapeKeysCanBeClearedAndCannotExceedTheirBound()throws Exception {
        JSONObject d=animated();SketchEditOps.apply(d,new JSONObject().put("type","update_object").put("objectId","subject")
                .put("patch",new JSONObject().put("shapeKeys",new JSONArray())));
        assertFalse(SketchShapeKeys.animated(SketchDocument.object(d,"subject")));
        d.put("frameCount",300);JSONArray keys=new JSONArray();for(int i=0;i<241;i++)keys.put(key(i,.1,"linear"));
        SketchDocument.object(d,"subject").put("shapeKeys",keys);
        try{SketchDocument.validate(d);fail("More than 240 shape keys accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("240"));}
    }
    @Test public void selectionUsesTheRenderedShapeRatherThanItsRestPosition()throws Exception {
        JSONObject d=animated();SketchDocument.validate(d);
        double left=.1+.6*.3153568125;
        assertEquals("subject",SketchFrameRenderer.hitTest(d,30,(float)left,.5f,.003));
        assertNull(SketchFrameRenderer.hitTest(d,30,.1f,.5f,.003));
    }
    @Test public void shapeTimingHasExactKeysAndUsesTheBaseBeforeItsFirstKey()throws Exception {
        JSONObject o=SketchDocument.object(document(),"subject");
        o.put("shapeKeys",new JSONArray().put(key(10,.4,"hold")).put(key(30,.7,"linear")));
        assertEquals(.1,SketchShapeKeys.commandsAt(o,9).getJSONArray(0).getDouble(1),0);
        assertEquals(.4,SketchShapeKeys.commandsAt(o,10).getJSONArray(0).getDouble(1),0);
        assertEquals(.4,SketchShapeKeys.commandsAt(o,29).getJSONArray(0).getDouble(1),0);
        assertEquals(.7,SketchShapeKeys.commandsAt(o,30).getJSONArray(0).getDouble(1),0);
        assertEquals(.7,SketchShapeKeys.commandsAt(o,60).getJSONArray(0).getDouble(1),0);
    }
    @Test public void consolidationPreservesIndependentlyAnimatedPaths()throws Exception {
        JSONObject d=animated(),o=SketchDocument.object(d,"subject");
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchDocument.copy(o).put("id","subject_other"));
        try{SketchEditOps.apply(d,new JSONObject().put("type","consolidate_path_runs").put("layerId","layer_1").put("idPrefix","subject"));fail("Animated paths were treated as static consolidation candidates");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("No safe identical-style"));}
        assertEquals(2,d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());
        assertTrue(SketchShapeKeys.animated(SketchDocument.object(d,"subject_other")));
    }
    @Test public void inspectionCountsShapeGeometryAndRecognisesItsActualSampledChanges()throws Exception {
        JSONObject result=SketchInspection.inspect(animated(),new JSONObject().put("frameLimit",3));
        assertEquals(2,result.getJSONObject("usage").getInt("keys"));
        assertEquals(18,result.getJSONObject("usage").getInt("points"));
        assertTrue(result.getJSONArray("frames").getJSONObject(1).getBoolean("contentChanged"));
        JSONObject sheet=SketchXsheetProjection.project(animated(),0,2);
        assertTrue(sheet.getJSONArray("columns").getJSONObject(0).getJSONArray("cells").getJSONObject(0).getBoolean("shapeKey"));
        assertFalse(sheet.getJSONArray("columns").getJSONObject(0).getJSONArray("cells").getJSONObject(1).optBoolean("shapeKey"));
    }
    @Test public void replacementShapeKeysDetachFromTheCallersMutablePatch()throws Exception {
        JSONObject d=document();JSONArray keys=new JSONArray().put(key(0,.1,"linear")).put(key(60,.7,"hold"));
        SketchEditOps.apply(d,new JSONObject().put("type","update_object").put("objectId","subject").put("patch",new JSONObject().put("shapeKeys",keys)));
        keys.getJSONObject(0).getJSONArray("commands").getJSONArray(0).put(1,.9);
        assertEquals(.1,SketchDocument.object(d,"subject").getJSONArray("shapeKeys").getJSONObject(0).getJSONArray("commands").getJSONArray(0).getDouble(1),0);
    }
    @Test public void bakedUnboundPathsRetainEditableKeysAndTheSameNativePixels()throws Exception {
        JSONObject source=animated(),program=new JSONObject().put("schema","videostudio.sketch-animation.v1").put("frameCount",61).put("fps",24).put("rigs",new JSONArray());
        JSONObject baked=SketchAnimationCompiler.compile(source,program,"shape_baked").getJSONObject("document");
        assertEquals(SketchDocument.object(source,"subject").getJSONArray("shapeKeys").toString(),SketchDocument.object(baked,"subject").getJSONArray("shapeKeys").toString());
        try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),source);SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),baked)){
            for(int frame:new int[]{0,15,30,60}){Bitmap x=a.render(frame),y=b.render(frame);try{assertTrue("Baked shape pixels differ at "+frame,x.sameAs(y));}finally{x.recycle();y.recycle();}}
        }
    }
}
