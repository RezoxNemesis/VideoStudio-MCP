package com.rezoxnemesis.videostudio;

import org.json.*;
import android.graphics.Path;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanAnatomyTest {
    @Test public void optionalContractPreservesLegacyDefaultsAndStrictlyChecksCombinations()throws Exception{
        assertEquals(13,SketchHumanFigure.defaults().length());
        assertFalse(SketchHumanFigure.settings(new JSONObject()).has("figureStyle"));
        assertFalse(SketchHumanFigure.settings(new JSONObject()).has("constructionGuides"));
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("figureStyle",true),new JSONObject().put("constructionGuides",true),new JSONObject().put("figureStyle","photo"),new JSONObject().put("constructionGuides","false"),new JSONObject().put("view","three_quarter_left"),new JSONObject().put("figureStyle","anatomy_sketch").put("clothingStyle","tailored")})
            assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.settings(bad));
        for(String view:new String[]{"front","left","right","back","high","low","three_quarter_left","three_quarter_right"})assertEquals(view,SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put("view",view)).getString("view"));
    }
    @Test public void styleChangesNeckProportionWithoutMovingWristsSolesOrFixedLimbJoints()throws Exception{
        for(String view:new String[]{"front","left","right","back","high","low"}){
            JSONObject settings=new JSONObject().put("view",view),pose=new JSONObject().put("nearHandX",90).put("nearHandY",-225).put("bodyLean",8);
            SketchHumanFigure.Skeleton a=SketchHumanFigure.skeleton(settings,pose),b=SketchHumanFigure.skeleton(SketchDocument.copy(settings).put("figureStyle","anatomy_sketch"),pose);
            // High/low styles intentionally use a stronger camera elevation. Lean is around that
            // camera normal; zero lean is used for exact physical-anchor comparisons there.
            if(view.equals("high")||view.equals("low")){pose.put("bodyLean",0);a=SketchHumanFigure.skeleton(settings,pose);b=SketchHumanFigure.skeleton(SketchDocument.copy(settings).put("figureStyle","anatomy_sketch"),pose);}
            for(double[][] pair:new double[][][]{ {a.hip,b.hip},{a.nearHand,b.nearHand},{a.farHand,b.farHand},{a.nearElbow,b.nearElbow},{a.leftKnee,b.leftKnee},{a.rightKnee,b.rightKnee},{a.leftFoot,b.leftFoot},{a.rightFoot,b.rightFoot}})assertArrayEquals(pair[0],pair[1],1e-8);
            assertEquals(15,distance(a.neck,b.neck),1e-8);
            assertEquals(76,distance(b.nearShoulder,b.nearElbow),1e-8);assertEquals(70,distance(b.nearElbow,b.nearHand),1e-8);
        }
    }
    @Test public void explicitLegacyAndDisabledGuidesProduceIdenticalEditableNativePaths()throws Exception{
        for(String view:new String[]{"front","left","right","back","high","low"}){
            JSONObject a=document(),b=document(),settings=new JSONObject().put("view",view);
            SketchHumanFigure.draw(a,layer(a),"actor",settings,new JSONObject(),0);
            SketchHumanFigure.draw(b,layer(b),"actor",SketchDocument.copy(settings).put("figureStyle","legacy").put("constructionGuides",false),new JSONObject(),0);
            assertEquals(SketchDocument.canonical(a),SketchDocument.canonical(b));
        }
    }
    @Test public void nativeSketchKeepsEditableInkAndOptionalGuidesSeparate()throws Exception{
        for(String view:new String[]{"front","left","right","back","high","low","three_quarter_left","three_quarter_right"}){
            JSONObject a=document(),b=document(),s=new JSONObject().put("figureStyle","anatomy_sketch").put("view",view);
            SketchHumanFigure.draw(a,layer(a),"actor",s,new JSONObject(),0);SketchHumanFigure.draw(b,layer(b),"actor",SketchDocument.copy(s).put("constructionGuides",true),new JSONObject(),0);
            JSONArray ink=layer(a).getJSONArray("objects"),guided=layer(b).getJSONArray("objects");assertTrue(ink.length()>100);assertTrue(guided.length()>ink.length());
            java.util.HashSet<String> ids=new java.util.HashSet<>();
            for(int i=0;i<ink.length();i++){JSONObject o=ink.getJSONObject(i);assertEquals("path",o.getString("kind"));assertTrue(ids.add(o.getString("id")));assertTrue(o.getString("id").startsWith("actor_anatomy_"));assertEquals(SketchDocument.canonical(o),SketchDocument.canonical(guided.getJSONObject(i)));}
            SketchDocument.validate(a);SketchDocument.validate(b);
        }
    }
    @Test public void profileTorsoHasAnatomicalDepthRatherThanAFoldedFrontPlane()throws Exception{
        for(String view:new String[]{"left","right"}){
            JSONObject doc=document(),settings=new JSONObject().put("figureStyle","anatomy_sketch").put("view",view);
            SketchHumanFigure.draw(doc,layer(doc),"actor",settings,new JSONObject(),0);JSONArray objects=layer(doc).getJSONArray("objects");JSONObject torso=null;
            for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").equals("actor_anatomy_torso_0"))torso=objects.getJSONObject(i);
            assertNotNull(torso);RectF bounds=new RectF();SketchFrameRenderer.path(torso.getJSONArray("commands"),1280,720).computeBounds(bounds,true);assertTrue("Chest and back both contribute to the profile silhouette",bounds.width()>40);
        }
    }
    @Test public void highAndLowUseTheDocumentDownwardYConvention()throws Exception{
        for(String view:new String[]{"high","low"}){
            JSONObject settings=new JSONObject().put("figureStyle","anatomy_sketch").put("view",view);SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings,new JSONObject());
            assertTrue(view.equals("high")?p.camera.normal()[1]<0:p.camera.normal()[1]>0);
            double[]surface=SketchHumanFigure.project(settings,new double[]{0,0,20});assertTrue(view.equals("high")?surface[1]>0:surface[1]<0);
            SketchHumanFigure.Skeleton legacy=SketchHumanFigure.skeleton(new JSONObject().put("view",view),new JSONObject());assertEquals(view.equals("high")?.32:-.24,legacy.camera.pitch,0);
        }
    }
    private static double distance(double[]a,double[]b){return Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2));}
    private static JSONObject document()throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","anatomy_test").put("width",1280).put("height",720).put("frameCount",1).put("fps",30));}
    private static JSONObject layer(JSONObject d)throws Exception{return d.getJSONArray("layers").getJSONObject(0);}
}
