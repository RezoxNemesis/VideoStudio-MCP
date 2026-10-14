package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

/** Independent review: native rendered coordinates must agree with the reported solver. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationCompilerReviewTest {
    private JSONObject source()throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","review_source").put("width",320).put("height",480).put("frameCount",1));
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","arm").put("kind","line").put("points",new JSONArray().put(new JSONArray().put(.4).put(.4).put(1)).put(new JSONArray().put(.6).put(.4).put(1)))));
        return d;
    }
    private JSONObject joint(String id,String parent,double x,double y)throws Exception {
        JSONObject j=new JSONObject().put("id",id).put("x",x).put("y",y);if(parent!=null)j.put("parent",parent);return j;
    }
    private JSONObject program()throws Exception {
        return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",5).put("fps",24).put("rigs",new JSONArray().put(new JSONObject().put("id","hero").put("joints",new JSONArray().put(joint("root",null,.4,.4)).put(joint("hand","root",.6,.4))).put("bindings",new JSONArray().put(new JSONObject().put("objectId","arm").put("jointId","root"))).put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("rotations",new JSONObject().put("root",0))).put(new JSONObject().put("frame",4).put("rotations",new JSONObject().put("root",70)))).put("ik",new JSONArray())));
    }
    @Test public void portraitNativeObjectMatrixAgreesWithPixelSpaceRigAfterPreparedObjectTransform()throws Exception {
        JSONObject s=source(),p=program(),object=SketchDocument.object(s,"arm");
        JSONObject base=object.getJSONObject("transform");base.put("anchorX",.4).put("anchorY",.4).put("scaleX",1.1).put("scaleY",.8).put("rotation",15).put("x",.03).put("y",-.01);
        float[] rest={128,192,192,192};SketchFrameRenderer.matrix(base,320,480).mapPoints(rest);
        JSONArray joints=p.getJSONArray("rigs").getJSONObject(0).getJSONArray("joints");joints.getJSONObject(0).put("x",rest[0]/320d).put("y",rest[1]/480d);joints.getJSONObject(1).put("x",rest[2]/320d).put("y",rest[3]/480d);
        JSONObject out=SketchAnimationCompiler.compile(s,p,"review_output").getJSONObject("document");
        for(int frame=0;frame<5;frame++){
            float[] actual={128,192,192,192};Matrix matrix=SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.object(out,"arm"),frame),320,480);matrix.mapPoints(actual);
            JSONArray sampled=SketchAnimationCompiler.sample(s,p,frame).getJSONArray("joints");
            assertEquals(sampled.getJSONObject(0).getDouble("x")*320,actual[0],.01);
            assertEquals(sampled.getJSONObject(0).getDouble("y")*480,actual[1],.01);
            assertEquals(sampled.getJSONObject(1).getDouble("x")*320,actual[2],.26);
            assertEquals(sampled.getJSONObject(1).getDouble("y")*480,actual[3],.26);
        }
    }
    @Test public void preparedLayerSpatialTransformsCannotSilentlyInvalidateWorldContacts()throws Exception {
        JSONObject s=source(),p=program();s.getJSONArray("layers").getJSONObject(0).getJSONObject("transform").put("x",.1);
        assertInvalid(s,p,"layer");
        s=source();JSONObject shifted=SketchDocument.transform().put("rotation",20);s.getJSONArray("layers").getJSONObject(0).getJSONArray("keys").put(new JSONObject().put("frame",0).put("transform",shifted).put("interpolation","hold"));
        assertInvalid(s,p,"layer");
    }
    @Test public void cameraDoesNotMoveOverlayForRigOnUnselectedLayer()throws Exception {
        JSONObject s=source(),p=program();JSONArray layers=s.getJSONArray("layers");JSONObject extra=SketchDocument.copy(layers.getJSONObject(0)).put("id","background").put("name","Background").put("objects",new JSONArray());layers.put(extra);
        p.put("cameraLayerIds",new JSONArray().put("background")).put("camera",new JSONArray().put(new JSONObject().put("frame",0).put("x",.1).put("rotation",35).put("zoom",1.3)));
        JSONObject sample=SketchAnimationCompiler.sample(s,p,2);JSONArray joints=sample.getJSONArray("joints");
        for(int i=0;i<joints.length();i++){JSONObject j=joints.getJSONObject(i);assertEquals(j.getDouble("x"),j.getDouble("displayX"),1e-9);assertEquals(j.getDouble("y"),j.getDouble("displayY"),1e-9);}
    }
    @Test public void contactDiagnosticsMustDescribeFinalPoseWhenAnotherIkChainControlsAnAncestor()throws Exception {
        JSONObject s=source(),p=program(),rig=p.getJSONArray("rigs").getJSONObject(0);
        rig.put("joints",new JSONArray().put(joint("root",null,.4,.3)).put(joint("chest","root",.4,.2)).put(joint("head","chest",.4,.1)).put(joint("hip","root",.4,.45)).put(joint("knee","hip",.4,.6)).put(joint("foot","knee",.4,.75)));
        rig.put("poses",new JSONArray()).put("ik",new JSONArray()
                .put(new JSONObject().put("hip","hip").put("knee","knee").put("ankle","foot").put("start",0).put("end",4).put("x",.46).put("y",.7))
                .put(new JSONObject().put("hip","root").put("knee","chest").put("ankle","head").put("start",0).put("end",4).put("x",.47).put("y",.14)));
        try{
            JSONObject sample=SketchAnimationCompiler.sample(s,p,0);JSONObject foot=sample.getJSONArray("joints").getJSONObject(5),reported=sample.getJSONArray("contactErrors").getJSONObject(0);
            double actualError=Math.hypot((foot.getDouble("x")-.46)*320,(foot.getDouble("y")-.7)*480);
            assertEquals("A later ancestor solve must not leave an obsolete zero error",actualError,reported.getDouble("errorPixels"),1e-7);
        }catch(IllegalArgumentException rejected){assertTrue(rejected.getMessage(),rejected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("ik"));}
    }
    @Test public void weightedArtworkHasIdenticalNativePainterOrderInPreviewAndBakedAnimation()throws Exception {
        JSONObject source=source(),program=program();
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","foreground").put("kind","rect").put("fill",true).put("color","#FFFF2233").put("bounds",new JSONObject().put("x",.3).put("y",.3).put("width",.4).put("height",.4))));
        JSONObject binding=program.getJSONArray("rigs").getJSONObject(0).getJSONArray("bindings").getJSONObject(0);
        binding.put("weights",new JSONArray().put(new JSONObject().put("root",1)).put(new JSONObject().put("root",1)));
        JSONObject baked=SketchAnimationCompiler.compile(source,program,"review_output").getJSONObject("document");
        JSONObject preview=SketchAnimationCompiler.preview(source,program,0,source.getString("id")).getJSONObject("document");
        try(SketchFrameRenderer full=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),baked);SketchFrameRenderer current=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),preview)){
            Bitmap a=full.render(0),b=current.render(0);
            try{int[] aa=new int[320*480],bb=new int[320*480];a.getPixels(aa,0,320,0,0,320,480);b.getPixels(bb,0,320,0,0,320,480);assertArrayEquals("Weighted parts retain their prepared z slot in native preview and baked output",bb,aa);}finally{a.recycle();b.recycle();}
        }
    }
    private void assertInvalid(JSONObject source,JSONObject program,String detail)throws Exception {
        String before=SketchDocument.canonical(source);
        try{SketchAnimationCompiler.compile(source,program,"review_output");fail("Unsupported transform admitted");}
        catch(IllegalArgumentException rejected){assertTrue(rejected.getMessage(),rejected.getMessage().toLowerCase(java.util.Locale.ROOT).contains(detail));}
        assertEquals(before,SketchDocument.canonical(source));
    }
}
