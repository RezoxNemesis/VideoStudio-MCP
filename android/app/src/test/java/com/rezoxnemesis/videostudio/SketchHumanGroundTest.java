package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Projection/IK regressions for the shared native ground helper; no renderer or film fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public final class SketchHumanGroundTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] LOCATIONS={"forest_path","bridge","shelter","village"};

    @Test public void placementUsesTheActualDeckAndUniformLetterboxOffsets()throws Exception {
        JSONObject settings=SketchHumanFigure.defaults();
        JSONObject landscape=SketchHumanGround.placement(document(1280,720,24),settings,"bridge");
        assertEquals(539d/720,landscape.getDouble("floorY"),1e-12);
        assertEquals(-1d/12,landscape.getDouble("groundSlopeX"),1e-12);
        assertEquals(0,landscape.getDouble("groundSlope"),1e-12);
        JSONObject portrait=SketchHumanGround.placement(document(720,1280,60),settings,"bridge");
        assertEquals((437.5+539*.5625)/1280,portrait.getDouble("floorY"),1e-12);
        assertEquals(landscape.getDouble("groundSlopeX"),portrait.getDouble("groundSlopeX"),0);
        assertEquals(620d/720,SketchHumanGround.placement(document(1280,720,30),settings,"forest_path").getDouble("floorY"),1e-12);
        assertEquals(625d/720,SketchHumanGround.placement(document(1280,720,30),settings,"shelter").getDouble("floorY"),1e-12);
    }

    @Test public void projectedFeetAndRootMatchAllFourStageFloorsInAllSixViewsAndCanvasFits()throws Exception {
        for(int[] canvas:new int[][]{{1280,720},{720,1280},{1600,600}})for(int fps:new int[]{24,30,60}){
            JSONObject doc=document(canvas[0],canvas[1],fps);String beforeDoc=SketchDocument.canonical(doc);
            for(String location:LOCATIONS)for(String view:VIEWS)for(double size:new double[]{.65,1.2})for(double x:new double[]{.37,.63}){
                JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view",view).put("x",x).put("size",size));
                String beforeSettings=SketchDocument.canonical(settings);
                JSONObject placement=SketchHumanGround.placement(doc,settings,location);
                assertEquals(3,placement.length());
                JSONObject source=new JSONObject().put("rootX",13).put("rootZ",25)
                        .put("footLeftX",-30).put("footLeftZ",-36).put("footRightX",28).put("footRightZ",62);
                String beforePose=SketchDocument.canonical(source);
                JSONObject pose=SketchHumanGround.applyPlane(source,placement.getDouble("groundSlopeX"),placement.getDouble("groundSlope"),false);
                for(String prefix:new String[]{"footLeft","footRight","root"}){
                    double[] projected=SketchHumanFigure.project(settings,new double[]{pose.getDouble(prefix+"X"),pose.getDouble(prefix+"Y"),pose.getDouble(prefix+"Z")});
                    double fit=Math.min(canvas[0]/1280d,canvas[1]/720d),offsetX=(canvas[0]-1280*fit)/2,offsetY=(canvas[1]-720*fit)/2;
                    double screenX=canvas[0]*x+projected[0]*size*fit;
                    double screenY=canvas[1]*placement.getDouble("floorY")+projected[1]*size*fit;
                    double refPixelX=(screenX-offsetX)/fit;
                    double floor=location.equals("bridge")?592.3333333333334-refPixelX/12:location.equals("shelter")?625:620;
                    assertEquals(location+" / "+view+" / "+prefix,offsetY+floor*fit,screenY,1e-9);
                }
                assertEquals(beforeSettings,SketchDocument.canonical(settings));
                assertEquals(beforePose,SketchDocument.canonical(source));
            }
            assertEquals(beforeDoc,SketchDocument.canonical(doc));
            assertEquals(fps,doc.getInt("fps"));assertEquals(fps*4,doc.getInt("frameCount"));
        }
    }

    @Test public void planeAddsToAuthoredHeightsAndPreservesAllOtherChannelsWithoutMutatingInput()throws Exception {
        JSONObject source=new JSONObject().put("rootX",20).put("rootY",-31).put("rootZ",40).put("hipY",-167)
                .put("footLeftX",-35).put("footLeftY",-17).put("footLeftZ",-24)
                .put("footRightX",32).put("footRightY",-9).put("footRightZ",60)
                .put("nearWrist",35).put("gazeX",.6).put("mouthTongue",.7);
        String before=SketchDocument.canonical(source);JSONObject full=SketchHumanFigure.validatePose(source);
        JSONObject result=SketchHumanGround.applyPlane(source,-.08,.12,false);
        assertEquals(SketchHumanFigure.POSE_CHANNELS.length,result.length());assertNotSame(source,result);
        for(String channel:SketchHumanFigure.POSE_CHANNELS){
            double expected=full.getDouble(channel);
            if(channel.equals("rootY"))expected+=-.08*20+.12*40;
            if(channel.equals("footLeftY"))expected+=-.08*-35+.12*-24;
            if(channel.equals("footRightY"))expected+=-.08*32+.12*60;
            assertEquals(channel,expected,result.getDouble(channel),1e-12);
        }
        assertEquals(before,SketchDocument.canonical(source));
    }

    @Test public void zeroSlopesAreAnIdenticalFullPoseEvenWhenStabilizationIsRequested()throws Exception {
        JSONObject source=new JSONObject().put("rootY",-60).put("hipY",-190).put("footLeftY",-11).put("blink",.4);
        String before=SketchDocument.canonical(source),expected=SketchDocument.canonical(SketchHumanFigure.validatePose(source));
        for(boolean stabilize:new boolean[]{false,true}){
            JSONObject result=SketchHumanGround.applyPlane(source,0,0,stabilize);
            assertEquals(expected,SketchDocument.canonical(result));assertNotSame(source,result);
        }
        assertEquals(before,SketchDocument.canonical(source));
    }

    @Test public void gaitReachStabilizationLowersOnlyTheHipAndKeepsFixedBones()throws Exception {
        JSONObject source=new JSONObject().put("rootZ",20).put("hipY",-177).put("footLeftZ",-35).put("footRightZ",55);
        String before=SketchDocument.canonical(source);
        JSONObject plane=SketchHumanGround.applyPlane(source,0,-1d/12,false);
        JSONObject stabilized=SketchHumanGround.applyPlane(source,0,-1d/12,true);
        double lowering=stabilized.getDouble("hipY")-plane.getDouble("hipY");assertTrue(lowering>0&&lowering<=12);
        for(String channel:SketchHumanFigure.POSE_CHANNELS)if(!channel.equals("hipY"))assertEquals(channel,plane.getDouble(channel),stabilized.getDouble(channel),0);
        for(String view:VIEWS)for(String build:new String[]{"slender","broad"}){
            SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(new JSONObject().put("view",view).put("build",build),stabilized);
            assertTrue(distance(p.leftHip,p.leftAnkle)<=175.5+1e-9);assertTrue(distance(p.rightHip,p.rightAnkle)<=175.5+1e-9);
            assertEquals(90,distance(p.leftHip,p.leftKnee),1e-9);assertEquals(86,distance(p.leftKnee,p.leftAnkle),1e-9);
            assertEquals(90,distance(p.rightHip,p.rightKnee),1e-9);assertEquals(86,distance(p.rightKnee,p.rightAnkle),1e-9);
        }
        assertEquals(before,SketchDocument.canonical(source));
    }

    @Test public void crossedFeetStayWithinReachForBothActualPelvisWidths()throws Exception {
        JSONObject source=new JSONObject().put("hipY",-184).put("footLeftX",34).put("footRightX",-34);
        String before=SketchDocument.canonical(source);
        JSONObject result=SketchHumanGround.applyPlane(source,.01,0,true);
        assertTrue(result.getDouble("hipY")-source.getDouble("hipY")<=12);
        for(String build:new String[]{"slender","broad"}){
            SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(new JSONObject().put("build",build),result);
            assertTrue(build+" left",distance(p.leftHip,p.leftAnkle)<=175.5+1e-9);
            assertTrue(build+" right",distance(p.rightHip,p.rightAnkle)<=175.5+1e-9);
        }
        assertEquals(before,SketchDocument.canonical(source));
    }

    @Test public void actualDefaultWalkAndRunFitEverySceneAndViewWithoutFalseHipCompressionFailures()throws Exception {
        for(int fps:new int[]{24,30,60})for(String action:new String[]{"walk","run"})for(double phase:new double[]{0,.35})for(String view:VIEWS){
            JSONArray poses=SketchHumanPresets.createForView(action,240,fps,new JSONObject().put("phase",phase),view);
            String original=SketchDocument.canonical(poses);
            for(String location:LOCATIONS){
                JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view",view));
                JSONObject placement=SketchHumanGround.placement(document(1280,720,fps),settings,location);
                double sx=placement.getDouble("groundSlopeX"),sz=placement.getDouble("groundSlope");
                for(int frame=0;frame<poses.length();frame++){
                    JSONObject plain=new JSONObject();for(String channel:SketchHumanFigure.POSE_CHANNELS)plain.put(channel,poses.getJSONObject(frame).get(channel));
                    String before=SketchDocument.canonical(plain);
                    JSONObject result=SketchHumanGround.applyPlane(plain,sx,sz,true);
                    double lowering=result.getDouble("hipY")-plain.getDouble("hipY");
                    String label=action+" / "+view+" / "+location+" / "+fps+"fps / phase "+phase+" / "+frame;
                    assertTrue(label,lowering>=0&&lowering<=16);
                    assertEquals(label,plain.getDouble("rootY")+sx*plain.getDouble("rootX")+sz*plain.getDouble("rootZ"),result.getDouble("rootY"),1e-10);
                    for(String side:new String[]{"Left","Right"}){
                        assertEquals(label,plain.getDouble("foot"+side+"Y")+sx*plain.getDouble("foot"+side+"X")+sz*plain.getDouble("foot"+side+"Z"),result.getDouble("foot"+side+"Y"),1e-10);
                        double[] point=SketchHumanFigure.project(settings,new double[]{result.getDouble("foot"+side+"X"),result.getDouble("foot"+side+"Y"),result.getDouble("foot"+side+"Z")});
                        double slope=location.equals("bridge")?-1d/12:0;
                        double[] lift=SketchHumanFigure.project(settings,new double[]{0,plain.getDouble("foot"+side+"Y"),0});
                        assertEquals(label,slope*point[0]+lift[1],point[1],1e-9);
                    }
                    for(String build:new String[]{"slender","broad"}){
                        SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(SketchDocument.copy(settings).put("build",build),result);
                        assertTrue(label+" / "+build,distance(p.leftHip,p.leftAnkle)<=175.5+1e-9);
                        assertTrue(label+" / "+build,distance(p.rightHip,p.rightAnkle)<=175.5+1e-9);
                        assertEquals(90,distance(p.leftHip,p.leftKnee),1e-8);assertEquals(86,distance(p.leftKnee,p.leftAnkle),1e-8);
                        assertEquals(90,distance(p.rightHip,p.rightKnee),1e-8);assertEquals(86,distance(p.rightKnee,p.rightAnkle),1e-8);
                    }
                    assertEquals(before,SketchDocument.canonical(plain));
                }
            }
            assertEquals(original,SketchDocument.canonical(poses));
        }
    }

    @Test public void uprightGaitMaximumRequiredLoweringAcrossAllGroundsViewsClocksAndPhasesIsBounded()throws Exception {
        double maximum=0;String worst="";
        for(int fps:new int[]{24,30,60})for(String action:new String[]{"walk","run"})for(double phase:new double[]{0,.35})for(String view:VIEWS){
            JSONArray poses=SketchHumanPresets.createForView(action,240,fps,new JSONObject().put("phase",phase),view);
            for(String location:LOCATIONS){
                JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view",view));
                JSONObject placement=SketchHumanGround.placement(document(1280,720,fps),settings,location);
                for(int frame=0;frame<poses.length();frame++){
                    JSONObject plain=new JSONObject();for(String channel:SketchHumanFigure.POSE_CHANNELS)plain.put(channel,poses.getJSONObject(frame).get(channel));
                    JSONObject plane=SketchHumanGround.applyPlane(plain,placement.getDouble("groundSlopeX"),placement.getDouble("groundSlope"),false);
                    double needed=requiredLowering(plane);
                    if(needed>maximum){maximum=needed;worst=action+" / "+view+" / "+location+" / "+fps+"fps / phase "+phase+" / "+frame;}
                }
            }
        }
        assertEquals(worst,15.077849828485228,maximum,1e-9);
        assertEquals("walk / low / bridge / 30fps / phase 0.0 / 29",worst);
        assertTrue("Only the necessary whole-pixel bound is admitted",maximum>15&&maximum<16);
    }

    @Test public void sixteenPixelLimitAcceptsItsBoundaryAndRejectsExcessWithoutMovingFeet()throws Exception {
        JSONObject source=SketchHumanFigure.poseDefaults().put("hipY",-220);double sx=.01;
        JSONObject plane=SketchHumanGround.applyPlane(source,sx,0,false);
        double initialNeed=requiredLowering(plane);source.put("hipY",source.getDouble("hipY")+initialNeed-16);
        String before=SketchDocument.canonical(source);
        JSONObject result=SketchHumanGround.applyPlane(source,sx,0,true);
        assertEquals(16,result.getDouble("hipY")-source.getDouble("hipY"),1e-10);
        JSONObject expectedPlane=SketchHumanGround.applyPlane(source,sx,0,false);
        for(String channel:SketchHumanFigure.POSE_CHANNELS)if(!channel.equals("hipY"))assertEquals(channel,expectedPlane.getDouble(channel),result.getDouble(channel),0);
        JSONObject excessive=SketchDocument.copy(source).put("hipY",source.getDouble("hipY")-.001);
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(excessive,sx,0,true));
        assertEquals(before,SketchDocument.canonical(source));
    }

    @Test public void excessiveCompressionAndUnreachableHorizontalReachRejectWithoutFlatteningLift()throws Exception {
        JSONObject jump=new JSONObject().put("rootY",-80).put("footLeftY",-7).put("footRightY",-5);
        String before=SketchDocument.canonical(jump);
        JSONObject unchangedLift=SketchHumanGround.applyPlane(jump,.01,0,false);
        assertEquals(-80,unchangedLift.getDouble("rootY"),0);
        assertEquals(-7-.28,unchangedLift.getDouble("footLeftY"),1e-12);
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(jump,.01,0,true));
        assertEquals(before,SketchDocument.canonical(jump));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(new JSONObject().put("footLeftZ",200),.01,0,true));
    }

    @Test public void malformedPlacementAndPoseInputsRejectWithoutChangingTheirSources()throws Exception {
        JSONObject doc=document(1280,720,24),settings=SketchHumanFigure.defaults();String before=SketchDocument.canonical(doc);
        for(String location:new String[]{"none","castle",null})assertThrows(IllegalArgumentException.class,()->SketchHumanGround.placement(doc,settings,location));
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("view","quarter"),new JSONObject().put("size",0),new JSONObject().put("x",1.1),new JSONObject().put("floorY",".88")})
            assertThrows(Exception.class,()->SketchHumanGround.placement(doc,bad,"bridge"));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.placement(SketchDocument.copy(doc).put("width",1279),settings,"bridge"));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.placement(SketchDocument.copy(doc).put("fps",25),settings,"bridge"));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.placement(document(2048,256,24),new JSONObject().put("x",0),"bridge"));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.placement(null,settings,"bridge"));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.placement(doc,null,"bridge"));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(null,0,0,false));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(new JSONObject().put("gazeX",2),0,0,false));
        assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(new JSONObject().put("unknown",0),0,0,false));
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(new JSONObject(),invalid,0,false));
            assertThrows(IllegalArgumentException.class,()->SketchHumanGround.applyPlane(new JSONObject(),0,invalid,false));
        }
        assertEquals(before,SketchDocument.canonical(doc));
    }

    private static JSONObject document(int width,int height,int fps)throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","ground_test").put("width",width).put("height",height).put("fps",fps).put("frameCount",fps*4));
    }
    private static double requiredLowering(JSONObject plane)throws Exception {
        double required=0,hipX=plane.getDouble("rootX")+plane.getDouble("hipX"),hipY=plane.getDouble("rootY")+plane.getDouble("hipY"),hipZ=plane.getDouble("rootZ")+plane.getDouble("hipZ");
        for(int pelvis:new int[]{18,23})for(String side:new String[]{"Left","Right"}){
            double sign=side.equals("Left")?-1:1,dx=plane.getDouble("foot"+side+"X")-(hipX+sign*pelvis),dz=plane.getDouble("foot"+side+"Z")-hipZ;
            double allowableVertical=Math.sqrt(175.5*175.5-dx*dx-dz*dz);
            required=Math.max(required,plane.getDouble("foot"+side+"Y")-14-hipY-allowableVertical);
        }
        return required;
    }
    private static double distance(double[] a,double[] b){return Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2));}
}
