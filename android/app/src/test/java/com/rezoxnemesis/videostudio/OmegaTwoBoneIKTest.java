package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Deterministic OMEGA 4 core plus actual SketchDocument/native vector preview regressions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class OmegaTwoBoneIKTest {
    private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static OmegaTwoBoneIK.Joint p(double x,double y){return new OmegaTwoBoneIK.Joint(x,y);}
    private static OmegaTwoBoneIK.Bone b(String id,String parent,double x,double y,double tx,double ty){
        return new OmegaTwoBoneIK.Bone(id,parent,p(x,y),p(tx,ty));
    }
    private static OmegaTwoBoneIK.Influence w(String id,double weight){return new OmegaTwoBoneIK.Influence(id,weight);}
    private static OmegaTwoBoneIK.SkinVertex v(double x,double y,OmegaTwoBoneIK.Influence...weights){
        return new OmegaTwoBoneIK.SkinVertex(p(x,y),weights);
    }
    private static OmegaTwoBoneIK.View view(String name,OmegaTwoBoneIK.Skin...skins){
        return new OmegaTwoBoneIK.View(name,new OmegaTwoBoneIK.Bone[]{
                b("torso",null,.2,.3,.2,.55),
                b("upper",null,.4,.4,.525,.4),
                b("lower","upper",.525,.4,.65,.4),
                b("finger","lower",.65,.4,.69,.4)
        },skins,320,480);
    }
    private static OmegaTwoBoneIK.Rig rig(OmegaTwoBoneIK.View... views){
        return new OmegaTwoBoneIK.Rig("sketch_4",7,HASH,320,480,24,100,views);
    }
    private static OmegaTwoBoneIK.Target aim(double x,double y){
        return new OmegaTwoBoneIK.Target("upper","lower",OmegaTwoBoneIK.Target.AIM,"handle",p(x,y),1);
    }
    private static OmegaTwoBoneIK.FrameRequest pose(int frame,double rx,double ry,OmegaTwoBoneIK.Target...targets){
        return new OmegaTwoBoneIK.FrameRequest("front",frame,7,p(rx,ry),Collections.emptyMap(),targets);
    }
    private static OmegaTwoBoneIK.Skin mesh(){
        return new OmegaTwoBoneIK.Skin("layer_1","sleeve",false,new OmegaTwoBoneIK.SkinVertex[]{
                v(.45,.4,w("upper",1)),v(.6,.4,w("lower",1)),v(.2,.4,w("torso",1))
        },null);
    }
    private static void invalid(Runnable r,String expected){
        try{r.run();fail("Expected: "+expected);}catch(IllegalArgumentException e){
            assertTrue(e.getMessage(),e.getMessage().contains(expected));
        }
    }
    @Test public void originalSolvePreservesExactLengthsAndMirroredBend(){
        OmegaTwoBoneIK.Solution s=OmegaTwoBoneIK.solve(p(0,0),p(1,1),1,1,1);
        assertTrue(s.reachable);assertEquals(1,s.root.distanceTo(s.elbow),1e-10);
        assertEquals(1,s.elbow.distanceTo(s.tip),1e-10);
        assertEquals(1,s.tip.x,1e-10);assertEquals(1,s.tip.y,1e-10);
        OmegaTwoBoneIK.Solution other=OmegaTwoBoneIK.solve(p(0,0),p(1,1),1,1,-1);
        assertTrue(s.elbow.distanceTo(other.elbow)>.2);
    }
    @Test public void outerAndInnerUnreachableTargetsClampWithoutStretch(){
        OmegaTwoBoneIK.Solution far=OmegaTwoBoneIK.solve(p(0,0),p(10,0),1,1,1);
        assertFalse(far.reachable);assertEquals(2,far.tip.x,1e-10);
        OmegaTwoBoneIK.Solution near=OmegaTwoBoneIK.solve(p(0,0),p(.1,0),1,.5,1);
        assertFalse(near.reachable);assertEquals(.5,near.tip.x,1e-10);
        assertEquals(1,near.root.distanceTo(near.elbow),1e-9);
        assertEquals(.5,near.elbow.distanceTo(near.tip),1e-9);
    }
    @Test public void coincidentPointsStayFiniteAndRejectUnsafeLengths(){
        OmegaTwoBoneIK.Solution s=OmegaTwoBoneIK.solve(p(.2,.2),p(.2,.2),.4,.4,1);
        assertEquals(.4,s.root.distanceTo(s.elbow),1e-7);
        assertEquals(.4,s.elbow.distanceTo(s.tip),1e-7);
        invalid(()->OmegaTwoBoneIK.solve(p(0,0),p(1,1),0,1,1),"Unsafe");
        invalid(()->OmegaTwoBoneIK.solve(p(0,0),p(1,1),1,1,0),"Unsafe");
        invalid(()->p(Double.NaN,0),"Nonfinite");
        invalid(()->p(Double.POSITIVE_INFINITY,0),"Nonfinite");
    }
    @Test public void framePinnedPixelLengthsAndNativeFrameClock(){
        OmegaTwoBoneIK.Rig r=rig(view("front"));
        OmegaTwoBoneIK.FrameResult first=OmegaTwoBoneIK.sample(r,pose(0,0,0));
        OmegaTwoBoneIK.FrameResult later=OmegaTwoBoneIK.sample(r,pose(25,0,0));
        assertEquals(0,first.presentationTimeUs);assertEquals(NativeFrameClock.presentationTimeUs(25,24),later.presentationTimeUs);
        assertEquals(40,later.bone("upper").lengthPixels,1e-7);
        assertEquals(0,first.bone("upper").tip.distanceTo(later.bone("upper").tip),0);
        invalid(()->OmegaTwoBoneIK.sample(r,pose(100,0,0)),"frame/revision");
        invalid(()->OmegaTwoBoneIK.sample(r,new OmegaTwoBoneIK.FrameRequest("front",25,6,p(0,0),null)),"frame/revision");
    }
    @Test public void localRotationArticulatesDistalBonesNotUnrelatedTorso(){
        Map<String,Double> angle=new LinkedHashMap<>();angle.put("upper",90d);
        OmegaTwoBoneIK.FrameResult f=OmegaTwoBoneIK.sample(rig(view("front")),
                new OmegaTwoBoneIK.FrameRequest("front",12,7,p(0,0),angle));
        assertEquals(.4,f.bone("upper").tip.x,1e-10);
        assertEquals(.4+40d/480,f.bone("upper").tip.y,1e-10);
        assertEquals(f.bone("upper").tip.x,f.bone("lower").start.x,1e-10);
        assertEquals(.2,f.bone("torso").tip.x,1e-12);
    }
    @Test public void twoHardPlantedFeetRemainAtFixedSourceContactsAcrossRootMotion(){
        OmegaTwoBoneIK.Bone[] legs=new OmegaTwoBoneIK.Bone[]{
                b("left_thigh",null,.3,.35,.32,.55),b("left_shin","left_thigh",.32,.55,.3,.75),
                b("right_thigh",null,.7,.35,.68,.55),b("right_shin","right_thigh",.68,.55,.7,.75)};
        OmegaTwoBoneIK.Rig r=rig(new OmegaTwoBoneIK.View("front",legs,null,320,480));
        OmegaTwoBoneIK.Target left=new OmegaTwoBoneIK.Target("left_thigh","left_shin",OmegaTwoBoneIK.Target.FOOT_PLANT,"floor_L",p(.3,.72),1);
        OmegaTwoBoneIK.Target right=new OmegaTwoBoneIK.Target("right_thigh","right_shin",OmegaTwoBoneIK.Target.FOOT_PLANT,"floor_R",p(.7,.72),-1);
        for(int frame:new int[]{0,25,50,75,99}){
            double travel=(frame/99d)*.035;
            OmegaTwoBoneIK.FrameResult result=OmegaTwoBoneIK.sample(r,new OmegaTwoBoneIK.FrameRequest("front",frame,7,p(travel,0),null,left,right));
            assertEquals(.3,result.bone("left_shin").tip.x,1e-9);
            assertEquals(.72,result.bone("left_shin").tip.y,1e-9);
            assertEquals(.7,result.bone("right_shin").tip.x,1e-9);
            assertEquals(.72,result.bone("right_shin").tip.y,1e-9);
            assertEquals(2,result.contacts().length);
            for(OmegaTwoBoneIK.ContactReport report:result.contacts())assertTrue(report.residualPixels<.05);
        }
    }
    @Test public void gripConstraintMovesForearmAndFingerButNotTorso(){
        OmegaTwoBoneIK.Target grip=new OmegaTwoBoneIK.Target("upper","lower",OmegaTwoBoneIK.Target.PROP_GRIP,"prop_77",p(.6,.5),1);
        OmegaTwoBoneIK.FrameResult result=OmegaTwoBoneIK.sample(rig(view("front")),pose(40,0,0,grip));
        assertEquals(.6,result.bone("lower").tip.x,1e-9);
        assertEquals(.5,result.bone("lower").tip.y,1e-9);
        assertEquals(result.bone("lower").tip.x,result.bone("finger").start.x,1e-9);
        assertEquals(.2,result.bone("torso").tip.x,1e-12);
        assertEquals("prop_77",result.contacts()[0].anchorId);
    }
    @Test public void unreachableHardFootAndGripFailClosedButAimReportsClamping(){
        OmegaTwoBoneIK.Rig r=rig(view("front"));
        invalid(()->OmegaTwoBoneIK.sample(r,pose(2,0,0,
                new OmegaTwoBoneIK.Target("upper","lower",OmegaTwoBoneIK.Target.FOOT_PLANT,"floor",p(.99,.99),1))),"UNREACHABLE_FOOT_PLANT");
        invalid(()->OmegaTwoBoneIK.sample(r,pose(2,0,0,
                new OmegaTwoBoneIK.Target("upper","lower",OmegaTwoBoneIK.Target.PROP_GRIP,"prop",p(.99,.99),1))),"UNREACHABLE_PROP_GRIP");
        OmegaTwoBoneIK.ContactReport report=OmegaTwoBoneIK.sample(r,pose(2,0,0,aim(.99,.99))).contacts()[0];
        assertFalse(report.reachable);assertTrue(report.residualPixels>100);
    }
    @Test public void contactConflictsAndTopologyAreRejected(){
        OmegaTwoBoneIK.Rig r=rig(view("front"));
        OmegaTwoBoneIK.Target c=aim(.6,.45);
        invalid(()->OmegaTwoBoneIK.sample(r,pose(0,0,0,c,c)),"Overlapping");
        invalid(()->OmegaTwoBoneIK.sample(r,pose(0,0,0,
                new OmegaTwoBoneIK.Target("lower","torso",OmegaTwoBoneIK.Target.FOOT_PLANT,"x",p(.5,.5),1))),"direct upper/lower");
    }
    @Test public void weightedMeshSkinDeformsLocallyWithStableObjectAndLayerIds(){
        OmegaTwoBoneIK.Rig r=rig(view("front",mesh()));
        OmegaTwoBoneIK.FrameResult rest=OmegaTwoBoneIK.sample(r,pose(1,0,0));
        Map<String,Double> rotation=Collections.singletonMap("upper",45d);
        OmegaTwoBoneIK.FrameResult moved=OmegaTwoBoneIK.sample(r,new OmegaTwoBoneIK.FrameRequest("front",2,7,p(0,0),rotation));
        OmegaTwoBoneIK.DeformedSkin skin=moved.skins()[0];
        assertEquals("layer_1",skin.layerId);assertEquals("sleeve",skin.objectId);
        assertTrue(rest.skins()[0].vertices()[1].distanceTo(skin.vertices()[1])>.01);
        assertEquals(.2,skin.vertices()[2].x,1e-12);
        assertEquals(.4,skin.vertices()[2].y,1e-12);
        OmegaTwoBoneIK.Joint[] stolen=skin.vertices();stolen[0]=p(.9,.9);
        assertEquals(skin.vertices()[0].x,OmegaTwoBoneIK.sample(r,new OmegaTwoBoneIK.FrameRequest("front",2,7,p(0,0),rotation)).skins()[0].vertices()[0].x,0);
    }
    @Test public void blendedWeightsAndTrianglesPreserveSourceWinding(){
        OmegaTwoBoneIK.Skin skin=new OmegaTwoBoneIK.Skin("layer_1","cloth",false,new OmegaTwoBoneIK.SkinVertex[]{
                v(.43,.43,w("upper",.75),w("torso",.25)),
                v(.47,.43,w("upper",.75),w("torso",.25)),
                v(.43,.48,w("upper",.75),w("torso",.25))
        },new int[]{0,1,2});
        OmegaTwoBoneIK.Rig r=rig(view("front",skin));
        assertEquals(3,OmegaTwoBoneIK.sample(r,pose(4,0,0)).skins()[0].vertices().length);
        invalid(()->new OmegaTwoBoneIK.SkinVertex(p(.4,.5),w("upper",.6),w("torso",.3)),"sum to one");
        invalid(()->new OmegaTwoBoneIK.SkinVertex(p(.4,.5),w("upper",.5),w("upper",.5)),"Duplicate");
    }
    @Test public void lockedLayerOrInvalidGeometryRejectBeforeAnyNativeEdit(){
        OmegaTwoBoneIK.Skin locked=new OmegaTwoBoneIK.Skin("layer_1","sleeve",true,mesh().vertices(),null);
        invalid(()->OmegaTwoBoneIK.sample(rig(view("front",locked)),pose(0,0,0)),"LOCKED_LAYER");
        invalid(()->new OmegaTwoBoneIK.View("front",new OmegaTwoBoneIK.Bone[]{
                b("upper",null,.4,.4,.5,.4),b("lower","upper",.6,.4,.7,.4)
        },null,320,480),"disconnected");
        invalid(()->new OmegaTwoBoneIK.View("front",new OmegaTwoBoneIK.Bone[]{
                b("upper","upper",.4,.4,.5,.4)
        },null,320,480),"parents must precede");
        invalid(()->rig(view("front"),view("front")),"Duplicate");
    }
    @Test public void threeAnglesRequireThreeSeparatelyAuthoredViews(){
        OmegaTwoBoneIK.View front=view("front");
        OmegaTwoBoneIK.View quarter=new OmegaTwoBoneIK.View("three_quarter",new OmegaTwoBoneIK.Bone[]{
                b("arm",null,.5,.42,.61,.47)
        },null,320,480);
        OmegaTwoBoneIK.View profile=new OmegaTwoBoneIK.View("profile",new OmegaTwoBoneIK.Bone[]{
                b("arm",null,.55,.4,.66,.48)
        },null,320,480);
        OmegaTwoBoneIK.Rig r=rig(front,quarter,profile);
        assertArrayEquals(new String[]{"front","three_quarter","profile"},r.viewIds());
        for(String angle:r.viewIds()){
            OmegaTwoBoneIK.FrameResult f=OmegaTwoBoneIK.sample(r,new OmegaTwoBoneIK.FrameRequest(angle,30,7,p(0,0),null));
            assertEquals(angle,f.viewId);assertEquals(NativeFrameClock.presentationTimeUs(30,24),f.presentationTimeUs);
        }
        invalid(()->OmegaTwoBoneIK.sample(r,new OmegaTwoBoneIK.FrameRequest("rear",0,7,p(0,0),null)),"Missing explicitly authored view");
    }
    @Test public void hundredBindingsRemainDeterministicAndWithinNativeObjectBudgets(){
        OmegaTwoBoneIK.Skin[] skins=new OmegaTwoBoneIK.Skin[100];
        for(int i=0;i<100;i++)skins[i]=new OmegaTwoBoneIK.Skin("layer_1","asset_"+i,false,new OmegaTwoBoneIK.SkinVertex[]{
                v(.45,.4,w("upper",1)),v(.6,.4,w("lower",1))
        },null);
        OmegaTwoBoneIK.Rig r=rig(view("front",skins));
        for(int frame:new int[]{0,25,50,75,99}){
            OmegaTwoBoneIK.FrameResult f=OmegaTwoBoneIK.sample(r,pose(frame,0,0));
            assertEquals(100,f.skins().length);assertEquals(NativeFrameClock.presentationTimeUs(frame,24),f.presentationTimeUs);
        }
    }
    private static JSONObject nativeSketch() throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","sketch_4").put("width",320).put("height",480)
                .put("frameCount",100).put("fps",24));
        source.put("revision",7);
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(new JSONObject()
                .put("id","sleeve").put("kind","line").put("frame",0).put("exposure",100)
                .put("points",new JSONArray().put(new JSONArray().put(.45).put(.4).put(1))
                        .put(new JSONArray().put(.6).put(.4).put(.7)))
                .put("color","#FF000000").put("fill",false).put("width",.003)
                .put("transform",SketchDocument.transform()).put("keys",new JSONArray()));
        SketchDocument.validate(source);return source;
    }
    private static OmegaTwoBoneIK.Rig nativeRig(JSONObject source,boolean locked)throws Exception {
        String hash=SketchDocument.sha256(SketchDocument.canonical(source).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OmegaTwoBoneIK.Skin link=new OmegaTwoBoneIK.Skin("layer_1","sleeve",locked,new OmegaTwoBoneIK.SkinVertex[]{
                v(.45,.4,w("upper",1)),v(.6,.4,w("lower",1))
        },null);
        return new OmegaTwoBoneIK.Rig("sketch_4",7,hash,320,480,24,100,view("front",link));
    }
    @Test public void actualNativeDocumentRevisionShaAndVectorTopologyArePreserved()throws Exception{
        JSONObject source=nativeSketch();String before=SketchDocument.canonical(source);
        OmegaTwoBoneIK.Rig rig=nativeRig(source,false);
        Map<String,Double> turns=Collections.singletonMap("upper",30d);
        OmegaTwoBoneIK.FrameRequest request=new OmegaTwoBoneIK.FrameRequest("front",20,7,p(0,0),turns);
        JSONObject preview=OmegaTwoBoneIK.previewNativeChanges(source,rig,request);
        assertFalse(preview.getBoolean("committed"));assertFalse(preview.getBoolean("artisticQualityVerified"));
        assertEquals(7,preview.getLong("expectedRevision"));assertEquals("sketch_4",preview.getString("sourceSketchId"));
        assertEquals(before,SketchDocument.canonical(source));
        JSONObject patch=preview.getJSONArray("patches").getJSONObject(0);
        assertEquals("sleeve",patch.getString("objectId"));assertEquals("line",patch.getString("kind"));
        assertEquals(.7,patch.getJSONArray("points").getJSONArray(1).getDouble(2),0);
        JSONObject candidate=SketchDocument.copy(source);
        SketchDocument.object(candidate,"sleeve").put("points",patch.getJSONArray("points"));
        SketchDocument.validate(candidate);
    }
    @Test public void nativePreviewRejectsStaleHashLayerLocksOrChangedRestGeometry()throws Exception{
        JSONObject source=nativeSketch();OmegaTwoBoneIK.Rig rig=nativeRig(source,false);
        OmegaTwoBoneIK.FrameRequest request=pose(0,0,0);
        source.getJSONArray("layers").getJSONObject(0).put("locked",true);
        try{OmegaTwoBoneIK.previewNativeChanges(source,rig,request);fail();}
        catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("mismatch"));}
        source=nativeSketch();rig=nativeRig(source,false);
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0)
                .getJSONArray("points").getJSONArray(0).put(0,.455);
        try{OmegaTwoBoneIK.previewNativeChanges(source,rig,request);fail();}
        catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("mismatch"));}
    }
    @Test public void editableSourceWithTransformsRequiresReviewNotDestructiveRebake()throws Exception{
        JSONObject source=nativeSketch();source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0)
                .getJSONObject("transform").put("rotation",20);
        OmegaTwoBoneIK.Rig rig=nativeRig(source,false);
        try{OmegaTwoBoneIK.previewNativeChanges(source,rig,pose(0,0,0));fail();}
        catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("NATIVE_TRANSFORM"));}
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void realNativeCanvasRasterizesLocalDeformationWithoutRepaintingBackground()throws Exception{
        JSONObject source=nativeSketch();OmegaTwoBoneIK.Rig rig=nativeRig(source,false);
        OmegaTwoBoneIK.FrameRequest request=new OmegaTwoBoneIK.FrameRequest("front",20,7,p(0,0),
                Collections.singletonMap("upper",30d));
        JSONObject patch=OmegaTwoBoneIK.previewNativeChanges(source,rig,request)
                .getJSONArray("patches").getJSONObject(0);
        JSONObject candidate=SketchDocument.copy(source);
        SketchDocument.object(candidate,"sleeve").put("points",patch.getJSONArray("points"));
        android.graphics.Bitmap before=null,after=null;
        try(SketchFrameRenderer original=new SketchFrameRenderer(org.robolectric.RuntimeEnvironment.getApplication(),source);
            SketchFrameRenderer deformed=new SketchFrameRenderer(org.robolectric.RuntimeEnvironment.getApplication(),candidate)){
            before=original.render(20);after=deformed.render(20);
            int differences=0;
            for(int y=0;y<before.getHeight();y++)for(int x=0;x<before.getWidth();x++)
                if(before.getPixel(x,y)!=after.getPixel(x,y))differences++;
            assertTrue("A valid native cel must change actual Canvas pixels",differences>5);
            assertTrue("Local arm bend must not repaint the entire frame",differences<before.getWidth()*before.getHeight()/8);
            assertEquals("Untouched corner remains visually identical",before.getPixel(4,4),after.getPixel(4,4));
            assertEquals("Native frame size",320,after.getWidth());
            assertEquals("Native frame size",480,after.getHeight());
        }finally{
            if(before!=null)before.recycle();
            if(after!=null)after.recycle();
        }
    }


}
