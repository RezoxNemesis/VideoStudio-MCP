package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real fixed-bone IK, six native projections and reusable retained-cel sampling. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchHumanKneelTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] BUILDS={"slender","broad"};
    private static final double KNEE_RADIUS=9;

    @Test public void supportingKneeMakesExactSustainedContactWithFixedBonesAcrossAllViewsAndBuilds()throws Exception {
        for(int fps:new int[]{24,30,60})for(String view:VIEWS){
            int frames=fps*4;JSONArray poses=SketchHumanPresets.createForView("kneel",frames,fps,new JSONObject(),view);
            for(String build:BUILDS){
                JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view",view).put("build",build));
                for(int frame=0;frame<frames;frame++){
                    SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(settings,SketchHumanAnimation.poseAt(poses,frame));
                    String label=view+" / "+build+" / "+fps+"fps / "+frame;bones(body);
                    assertEquals(label,0,body.leftFoot[1],1e-9);assertEquals(label,0,body.rightFoot[1],1e-9);
                    assertTrue(label+" supporting knee must not penetrate the model floor",body.rightKnee[1]+KNEE_RADIUS<=1e-8);
                    if(frame>=frames/2){
                        assertEquals(label+" supporting knee bottom",0,body.rightKnee[1]+KNEE_RADIUS,1e-8);
                        assertTrue(label+" opposite knee stays raised",body.leftKnee[1]<-60);
                        // High/low cameras project depth into screen Y: compare the actual
                        // support point with its own ground footprint, not a shared screen Y.
                        double[] contact=SketchHumanFigure.project(settings,new double[]{body.rightKnee[0],body.rightKnee[1]+KNEE_RADIUS,body.rightKnee[2]});
                        double[] floor=SketchHumanFigure.project(settings,new double[]{body.rightKnee[0],0,body.rightKnee[2]});
                        assertArrayEquals(label+" projected support",floor,contact,1e-8);
                    }
                }
            }
        }
    }

    @Test public void lowOpenHandsAndDownwardAttentionStayHeldThroughTheLastFrame()throws Exception {
        for(String view:VIEWS){
            JSONArray poses=SketchHumanPresets.createForView("kneel",120,30,new JSONObject(),view);
            JSONObject held=SketchHumanAnimation.poseAt(poses,60);
            for(int frame=61;frame<120;frame++)assertEquals(view+" must not stand back up or resume a gesture",SketchDocument.canonical(held),SketchDocument.canonical(SketchHumanAnimation.poseAt(poses,frame)));
            for(String build:BUILDS){
                SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(new JSONObject().put("view",view).put("build",build),held);
                for(double[] hand:new double[][]{body.nearHand,body.farHand}){
                    assertTrue(view+" hands should reach below the chest toward a low object",hand[1]>body.shoulderCentre[1]+90);
                    assertTrue(view+" hands should reach in front of the supporting knee",hand[2]>body.rightKnee[2]);
                }
                assertTrue("Palms stay open for shielding rather than fists",held.getDouble("handOpen")>=.5);
                assertTrue("Attention follows the low target",held.getDouble("gazeY")>=.5);
            }
        }
    }

    @Test public void nativeKneeAndShoeInkMeetTheirActualProjectedSupportPoints()throws Exception {
        for(String view:VIEWS)for(String build:BUILDS){
            JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view",view).put("build",build).put("size",1.1).put("floorY",.76));
            JSONArray poses=SketchHumanPresets.createForView("kneel",120,30,new JSONObject(),view);
            JSONObject pose=SketchHumanAnimation.poseAt(poses,90),doc=document(1,30);JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);
            SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(settings,pose);
            SketchHumanFigure.draw(doc,layer,"kneeling",settings,pose,3);
            JSONArray selected=new JSONArray(),all=layer.getJSONArray("objects");
            for(int i=0;i<all.length();i++){
                JSONObject object=all.getJSONObject(i);String id=object.getString("id");
                if(id.startsWith("kneeling_right_thigh_")||id.startsWith("kneeling_right_shin_")||id.startsWith("kneeling_right_shoe_")||id.startsWith("kneeling_left_shoe_"))selected.put(object);
            }
            assertTrue(selected.length()>3);layer.put("objects",selected);SketchDocument.validate(doc);
            double[] knee=SketchHumanFigure.project(settings,new double[]{body.rightKnee[0],0,body.rightKnee[2]});
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
                Bitmap bitmap=renderer.render(0);try{
                    assertInkNear(bitmap,settings,knee,view+" / "+build+" knee support");
                    for(Object[] foot:new Object[][]{{"left",body.leftFoot},{"right",body.rightFoot}}){
                        double[] projected=SketchHumanFigure.project(settings,(double[])foot[1]);
                        RectF bounds=bounds(part(selected,"kneeling_"+foot[0]+"_shoe_0"));
                        float x=(float)(1280*settings.getDouble("x")+projected[0]*settings.getDouble("size"));
                        float y=(float)(720*settings.getDouble("floorY")+projected[1]*settings.getDouble("size"));
                        assertTrue(view+" native sole must include its actual floor anchor",x>=bounds.left-.05&&x<=bounds.right+.05&&y>=bounds.top-.05&&y<=bounds.bottom+.05);
                        assertInkNear(bitmap,settings,projected,view+" / "+build+" "+foot[0]+" sole");
                    }
                }finally{bitmap.recycle();}
            }
        }
    }

    @Test public void retainedOperationKeepsEditableSparseClockAndFrozenNativeHeldCels()throws Exception {
        for(String view:VIEWS){
            JSONObject source=document(96,24),op=new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","kneeling")
                    .put("preset","kneel").put("blinkTrack",false).put("settings",new JSONObject().put("view",view).put("size",.9).put("floorY",.78));
            SketchEditOps.apply(source,op);JSONObject actor=SketchDocument.object(source,"kneeling_rig");
            assertEquals(9,actor.getJSONObject("human").getJSONArray("poses").length());
            String before=SketchDocument.canonical(source);JSONObject frozen=SketchHumanAnimation.sampled(actor,72);
            assertEquals(1,frozen.getJSONObject("human").getJSONArray("poses").length());assertEquals(72,frozen.getJSONObject("human").getInt("renderFrame"));
            JSONObject frozenDoc=document(1,24);frozen.put("frame",0).put("exposure",1);frozenDoc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(frozen);SketchDocument.validate(frozenDoc);
            try(SketchFrameRenderer live=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),source);SketchFrameRenderer cel=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),frozenDoc)){
                Bitmap middle=live.render(72),last=live.render(95),sample=cel.render(0);try{
                    assertTrue(view+" held knee/hand geometry must remain identical through the tail",middle.sameAs(last));
                    assertTrue(view+" frozen editable cel must match live native pixels",middle.sameAs(sample));
                }finally{middle.recycle();last.recycle();sample.recycle();}
            }
            frozen.getJSONObject("human").getJSONArray("poses").getJSONObject(0).put("headTilt",-12);
            assertEquals(before,SketchDocument.canonical(source));assertEquals(96,source.getInt("frameCount"));assertEquals(24,source.getInt("fps"));
        }
    }

    @Test public void strengthStillBlendsFromRestWithoutMutatingMotionOrChangingPoseBudget()throws Exception {
        JSONObject input=new JSONObject().put("strength",.5),fullMotion=new JSONObject().put("strength",1);String before=SketchDocument.canonical(input);
        JSONArray half=SketchHumanPresets.create("kneel",240,60,input),full=SketchHumanPresets.create("kneel",240,60,fullMotion),zero=SketchHumanPresets.create("kneel",240,60,new JSONObject().put("strength",0));
        JSONObject rest=SketchHumanFigure.poseDefaults();assertEquals(9,half.length());
        for(int i=0;i<half.length();i++)for(String channel:SketchHumanFigure.POSE_CHANNELS){
            assertEquals(channel,rest.getDouble(channel),zero.getJSONObject(i).getDouble(channel),1e-10);
            assertEquals(channel,rest.getDouble(channel)+(full.getJSONObject(i).getDouble(channel)-rest.getDouble(channel))*.5,half.getJSONObject(i).getDouble(channel),1e-10);
        }
        for(int frame=0;frame<240;frame++)for(String view:VIEWS)for(String build:BUILDS)bones(SketchHumanFigure.skeleton(new JSONObject().put("view",view).put("build",build),SketchHumanAnimation.poseAt(half,frame)));
        assertEquals(before,SketchDocument.canonical(input));
    }

    @Test public void editableShieldingOverridesRemainReachableWithoutMovingTheSupportingKnee()throws Exception {
        JSONObject overrides=new JSONObject().put("nearHandX",76).put("nearHandY",-162).put("nearHandZ",20)
                .put("nearWrist",-15).put("nearElbowSwivel",120).put("handOpen",.8);
        String before=SketchDocument.canonical(overrides);
        for(String view:VIEWS){
            JSONArray poses=SketchHumanAnimation.presetPoses("kneel",120,30,new JSONObject(),view,overrides);
            for(String build:BUILDS)for(int frame=0;frame<120;frame++){
                JSONObject pose=SketchHumanAnimation.poseAt(poses,frame);
                for(String key:new String[]{"nearHandX","nearHandY","nearHandZ","nearWrist","nearElbowSwivel","handOpen"})assertEquals(key,overrides.getDouble(key),pose.getDouble(key),1e-10);
                SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(new JSONObject().put("view",view).put("build",build),pose);bones(body);
                if(frame>=60)assertEquals(view+" / "+build,0,body.rightKnee[1]+KNEE_RADIUS,1e-8);
            }
        }
        assertEquals(before,SketchDocument.canonical(overrides));
    }

    @Test public void actualGroundOperationKeepsHeldKneeAndNativeFeetOnEveryStageAcrossViewsAndBuilds()throws Exception {
        for(String location:new String[]{"forest_path","bridge","shelter","village"})for(String view:VIEWS)for(String build:BUILDS){
            JSONObject doc=document(96,24),op=new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","grounded")
                    .put("preset","kneel").put("ground",location).put("blinkTrack",false)
                    .put("settings",new JSONObject().put("view",view).put("build",build).put("x",.43).put("size",1.1));
            SketchEditOps.apply(doc,op);JSONObject actor=SketchDocument.object(doc,"grounded_rig"),settings=actor.getJSONObject("human").getJSONObject("settings");
            String original=SketchDocument.canonical(doc),label=location+" / "+view+" / "+build;
            for(int frame=48;frame<96;frame++){
                SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(settings,SketchHumanAnimation.pose(actor,frame));bones(body);
                double[] knee=SketchHumanFigure.project(settings,body.rightKnee);
                double kneeX=1280*settings.getDouble("x")+knee[0]*settings.getDouble("size");
                double kneeBottom=720*settings.getDouble("floorY")+(knee[1]+KNEE_RADIUS)*settings.getDouble("size");
                assertEquals(label+" / "+frame+" projected knee contour must touch the actual stage floor",stageFloor(location,kneeX),kneeBottom,3);
                for(double[] foot:new double[][]{body.leftFoot,body.rightFoot}){
                    double[] q=SketchHumanFigure.project(settings,foot);double x=1280*settings.getDouble("x")+q[0]*settings.getDouble("size"),y=720*settings.getDouble("floorY")+q[1]*settings.getDouble("size");
                    assertEquals(label+" actual foot anchor stays on the same stage floor",stageFloor(location,x),y,1e-7);
                }
            }
            // Inspect actual editable leg ink from the admitted retained operation,
            // independently of the opaque scene floor that could hide missing ink.
            JSONObject pose=SketchHumanAnimation.pose(actor,72);SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(settings,pose);
            JSONObject nativeDoc=SketchHumanAnimation.frameDocument(actor,1280,720,72);JSONArray objects=nativeDoc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"),legs=new JSONArray();
            for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);String id=object.getString("id");if(id.startsWith("grounded_rig_right_thigh_")||id.startsWith("grounded_rig_right_shin_")||id.startsWith("grounded_rig_right_shoe_")||id.startsWith("grounded_rig_left_shoe_"))legs.put(object);}
            assertTrue(label,legs.length()>3);nativeDoc.put("background","#FFFFFFFF");nativeDoc.getJSONArray("layers").getJSONObject(0).put("objects",legs);
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),nativeDoc)){
                Bitmap bitmap=renderer.render(0);try{
                    double[] knee=SketchHumanFigure.project(settings,body.rightKnee);double x=1280*settings.getDouble("x")+knee[0]*settings.getDouble("size"),y=stageFloor(location,x);
                    assertInkNear(bitmap,settings,new double[]{knee[0],(y-720*settings.getDouble("floorY"))/settings.getDouble("size")},label+" native knee at the stage floor");
                    for(Object[] foot:new Object[][]{{"left",body.leftFoot},{"right",body.rightFoot}}){
                        double[] q=SketchHumanFigure.project(settings,(double[])foot[1]);assertInkNear(bitmap,settings,q,label+" native "+foot[0]+" sole");
                        RectF bounds=bounds(part(legs,"grounded_rig_"+foot[0]+"_shoe_0"));double sole=720*settings.getDouble("floorY")+q[1]*settings.getDouble("size");
                        assertEquals(label+" native sole bottom",sole,bounds.bottom,.1);
                    }
                }finally{bitmap.recycle();}
            }
            assertEquals(original,SketchDocument.canonical(doc));assertEquals(9,actor.getJSONObject("human").getJSONArray("poses").length());assertEquals(96,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));
        }
    }

    @Test public void postPlaneSolverChangesOnlyHipAndBothPresetPathsShareTheSameSolve()throws Exception {
        JSONObject motion=new JSONObject().put("groundSlopeX",-.15).put("groundSlope",.31);
        JSONArray raw=SketchHumanPresets.create("kneel",120,30),direct=SketchHumanPresets.create("kneel",120,30,motion);
        JSONArray operation=SketchHumanAnimation.presetPoses("kneel",120,30,motion,"front",new JSONObject());
        for(int frame:new int[]{60,90,119}){
            JSONObject plane=SketchHumanGround.applyPlane(SketchHumanAnimation.poseAt(raw,frame),-.15,.31,false);String before=SketchDocument.canonical(plane);
            JSONObject solved=SketchHumanKneelContact.apply(plane,-.15,.31);
            assertNotSame(plane,solved);assertNotEquals(plane.getDouble("hipY"),solved.getDouble("hipY"),.01);
            for(String key:SketchHumanFigure.POSE_CHANNELS)if(!key.equals("hipY"))assertEquals(key,plane.getDouble(key),solved.getDouble(key),0);
            assertEquals(before,SketchDocument.canonical(plane));
            assertPoseEquals(solved,SketchHumanAnimation.poseAt(direct,frame));
            assertPoseEquals(solved,SketchHumanAnimation.poseAt(operation,frame));
            double average=0;
            for(String build:BUILDS){SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(new JSONObject().put("build",build),solved);bones(body);double support=body.rightKnee[1]+.15*body.rightKnee[0]-.31*body.rightKnee[2];average+=support;assertEquals("Both pelvis widths stay within half a model pixel",-9,support,.5);}
            assertEquals("The shared solve targets the two-build average exactly",-9,average/2,1e-8);
        }
        JSONObject plane=SketchHumanAnimation.poseAt(raw,90);
        assertPoseEquals(plane,SketchHumanKneelContact.apply(plane,0,0));
    }

    @Test public void explicitHipRootAndPartialStrengthRemainAuthoredInsteadOfForcingContact()throws Exception {
        JSONObject motion=new JSONObject().put("groundSlopeX",-.15).put("groundSlope",.31);
        for(JSONObject overrides:new JSONObject[]{new JSONObject().put("hipY",-96),new JSONObject().put("rootY",-4)}){
            JSONArray poses=SketchHumanAnimation.presetPoses("kneel",120,30,motion,"front",overrides);
            JSONObject actual=SketchHumanAnimation.poseAt(poses,90),raw=SketchHumanAnimation.poseAt(SketchHumanPresets.create("kneel",120,30),90);
            java.util.Iterator<String> keys=overrides.keys();while(keys.hasNext()){String key=keys.next();raw.put(key,overrides.get(key));}
            assertPoseEquals(SketchHumanGround.applyPlane(raw,-.15,.31,false),actual);
        }
        JSONObject half=new JSONObject(motion.toString()).put("strength",.5);
        JSONObject expected=SketchHumanGround.applyPlane(SketchHumanAnimation.poseAt(SketchHumanPresets.create("kneel",120,30,new JSONObject().put("strength",.5)),90),-.15,.31,false);
        assertPoseEquals(expected,SketchHumanAnimation.poseAt(SketchHumanAnimation.presetPoses("kneel",120,30,half,"front",new JSONObject()),90));
    }

    @Test public void impossibleContactAndCancellationRejectWithoutMutatingTheAuthoredPose()throws Exception {
        JSONObject pose=SketchHumanAnimation.poseAt(SketchHumanPresets.create("kneel",120,30),90).put("rootY",400);String before=SketchDocument.canonical(pose);
        assertThrows(IllegalArgumentException.class,()->SketchHumanKneelContact.apply(pose,-.15,.31));assertEquals(before,SketchDocument.canonical(pose));
        assertThrows(IllegalArgumentException.class,()->SketchHumanKneelContact.apply(pose,Double.NaN,0));assertEquals(before,SketchDocument.canonical(pose));
        JSONObject valid=SketchHumanAnimation.poseAt(SketchHumanPresets.create("kneel",120,30),90);
        Thread.currentThread().interrupt();try{assertThrows(InterruptedException.class,()->SketchHumanKneelContact.apply(valid,-.15,.31));}finally{Thread.interrupted();}
        bones(SketchHumanFigure.skeleton(new JSONObject(),SketchHumanKneelContact.apply(valid,-.15,.31)));
    }

    private static double stageFloor(String location,double x){return location.equals("bridge")?592.3333333333334-x/12:location.equals("shelter")?625:620;}
    private static void assertPoseEquals(JSONObject expected,JSONObject actual)throws Exception{assertEquals(expected.length(),actual.length());for(String channel:SketchHumanFigure.POSE_CHANNELS)assertEquals(channel,expected.getDouble(channel),actual.getDouble(channel),1e-9);}

    private static JSONObject document(int frames,int fps)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","kneel_test").put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps).put("background","#FFFFFFFF"));}
    private static JSONObject part(JSONArray objects,String id)throws Exception{for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").equals(id))return objects.getJSONObject(i);throw new AssertionError("Missing native ink "+id);}
    private static RectF bounds(JSONObject object)throws Exception{RectF result=new RectF();SketchFrameRenderer.path(object.getJSONArray("commands"),1280,720).computeBounds(result,true);return result;}
    private static void assertInkNear(Bitmap bitmap,JSONObject settings,double[] point,String label)throws Exception{
        int x=(int)Math.round(1280*settings.getDouble("x")+point[0]*settings.getDouble("size")),y=(int)Math.round(720*settings.getDouble("floorY")+point[1]*settings.getDouble("size"));boolean ink=false;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(bitmap.getPixel(x+dx,y+dy)!=Color.WHITE)ink=true;
        assertTrue(label+" needs actual native ink at "+x+","+y,ink);
    }
    private static void bones(SketchHumanFigure.Skeleton body){
        assertEquals(90,distance(body.leftHip,body.leftKnee),1e-8);assertEquals(86,distance(body.leftKnee,body.leftAnkle),1e-8);
        assertEquals(90,distance(body.rightHip,body.rightKnee),1e-8);assertEquals(86,distance(body.rightKnee,body.rightAnkle),1e-8);
        assertEquals(76,distance(body.nearShoulder,body.nearElbow),1e-8);assertEquals(70,distance(body.nearElbow,body.nearHand),1e-8);
        assertEquals(76,distance(body.farShoulder,body.farElbow),1e-8);assertEquals(70,distance(body.farElbow,body.farHand),1e-8);
    }
    private static double distance(double[] a,double[] b){double sum=0;for(int i=0;i<3;i++)sum+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(sum);}
}
