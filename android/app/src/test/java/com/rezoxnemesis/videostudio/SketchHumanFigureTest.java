package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

/** Fixed physical joints and actual editable native figure output, independently of a film. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanFigureTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"};

    @Test public void defaultsAndStrictSettingsExposeTheReusableAuthoringContract()throws Exception {
        JSONObject s=SketchHumanFigure.defaults();assertEquals(13,s.length());assertEquals("graphic",s.getString("faceStyle"));assertEquals("cel",s.getString("shading"));assertEquals(135,s.getDouble("lightAngle"),0);assertEquals("short",s.getString("hairStyle"));
        assertEquals("front",s.getString("view"));assertEquals("slender",s.getString("build"));
        assertEquals(.5,s.getDouble("x"),0);assertEquals(.88,s.getDouble("floorY"),0);assertEquals(1,s.getDouble("size"),0);
        for(String key:new String[]{"skinColor","hairColor","coatColor","trouserColor"})assertTrue(s.getString(key).matches("#[A-Fa-f0-9]{8}"));
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("faceStyle","unknown"),new JSONObject().put("shading",true),new JSONObject().put("lightAngle",181),new JSONObject().put("view","quarter"),new JSONObject().put("build","child"),new JSONObject().put("hairStyle","unsupported"),new JSONObject().put("unknown",1),new JSONObject().put("size",0),new JSONObject().put("x",1.1),new JSONObject().put("floorY",-.1),new JSONObject().put("size","1"),new JSONObject().put("coatColor","#123456")})
            assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.settings(bad));
        JSONObject pose=SketchHumanFigure.poseDefaults();assertEquals(35,pose.length());assertEquals(35,SketchHumanFigure.POSE_CHANNELS.length);assertEquals(0,pose.getDouble("blink"),0);
        assertEquals(0,pose.getDouble("nearWrist"),0);assertEquals(0,pose.getDouble("farWrist"),0);assertEquals(1,pose.getDouble("handOpen"),0);
        assertEquals(0,pose.getDouble("nearElbowSwivel"),0);assertEquals(0,pose.getDouble("farElbowSwivel"),0);
        assertEquals(58,pose.getDouble("nearHandX"),0);assertEquals(-166,pose.getDouble("nearHandY"),0);
        for(String channel:SketchHumanFigure.POSE_CHANNELS)assertTrue(pose.has(channel));
        for(String channel:new String[]{"mouthWide","mouthRound","mouthTeeth","mouthTongue"})assertEquals(0,pose.getDouble(channel),0);
        assertEquals(16,pose.getDouble("nearHandZ"),0);assertEquals(-16,pose.getDouble("farHandZ"),0);assertEquals(-9,pose.getDouble("footLeftZ"),0);assertEquals(9,pose.getDouble("footRightZ"),0);
    }

    @Test public void elbowSwivelIsAContinuousFixedLengthOrbitWithUnchangedHandAnchors()throws Exception {
        for(String view:VIEWS){JSONObject s=SketchHumanFigure.settings(new JSONObject().put("view",view)),pose=new JSONObject().put("nearHandX",96).put("nearHandY",-255).put("farHandX",-80).put("farHandY",-235);SketchHumanFigure.Skeleton zero=SketchHumanFigure.skeleton(s,pose);double[] previous=null;
            for(int angle=-180;angle<=180;angle+=5){SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,SketchDocument.copy(pose).put("nearElbowSwivel",angle).put("farElbowSwivel",-angle));bones(p);assertArrayEquals(zero.nearHand,p.nearHand,1e-9);assertArrayEquals(zero.farHand,p.farHand,1e-9);if(previous!=null)assertTrue("No discontinuous bend flip",distance(previous,p.nearElbow)<8);previous=p.nearElbow;}
            SketchHumanFigure.Skeleton flipped=SketchHumanFigure.skeleton(s,SketchDocument.copy(pose).put("nearElbowSwivel",180));assertTrue(distance(zero.nearElbow,flipped.nearElbow)>50);
            if(view.equals("front"))assertTrue("An explaining elbow can move below its wrist",flipped.nearElbow[1]>flipped.nearHand[1]);
            assertArrayEquals(SketchHumanFigure.skeleton(s,SketchDocument.copy(pose).put("nearElbowSwivel",-180)).nearElbow,flipped.nearElbow,1e-8);
        }
        for(String key:new String[]{"nearElbowSwivel","farElbowSwivel"})assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.validatePose(new JSONObject().put(key,181)));
    }

    @Test public void longHairIsBehindTheCoatAndNativeFrontNeckRemainsVisible()throws Exception {
        for(String view:VIEWS){
            JSONObject s=SketchHumanFigure.settings(new JSONObject().put("view",view).put("hairStyle","long")),doc=document(1280,720),layer=layer(doc);SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,new JSONObject());
            SketchHumanFigure.draw(doc,layer,"actor",s,new JSONObject(),0);assertTrue("Rear hair must precede the actual neck",partIndex(layer,"actor_long_hair_0")<partIndex(layer,"actor_neck_0"));assertTrue(partIndex(layer,"actor_long_hair_0")<partIndex(layer,"actor_coat_0"));
            if(!view.equals("back"))try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
                double[] point={p.neck[0]*.75+p.shoulderCentre[0]*.25,p.neck[1]*.75+p.shoulderCentre[1]*.25,p.neck[2]*.75+p.shoulderCentre[2]*.25},q=SketchHumanFigure.project(s,point);Bitmap image=renderer.render(0);
                try{int pixel=image.getPixel((int)Math.round(1280*s.getDouble("x")+q[0]),(int)Math.round(720*s.getDouble("floorY")+q[1]));assertEquals("Long hair must not become a beard over the "+view+" neck",Color.parseColor(s.getString("skinColor")),pixel);}finally{image.recycle();}
            }
        }
    }

    @Test public void roundedSleevesCoverElbowShoulderAndWristDisksWithoutStretchingBones()throws Exception {
        for(String view:VIEWS)for(int lean:new int[]{-12,0,12}){
            JSONObject s=SketchHumanFigure.settings(new JSONObject().put("view",view)),pose=new JSONObject().put("bodyLean",lean).put("nearHandX",96).put("nearHandY",-255).put("farHandX",-70).put("farHandY",-190),doc=document(1280,720),layer=layer(doc);
            SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,pose);bones(p);SketchHumanFigure.draw(doc,layer,"actor",s,pose,0);
            for(Object[] joint:new Object[][]{{"near",p.nearElbow,"elbow",6d},{"far",p.farElbow,"elbow",6d},{"near",p.nearShoulder,"shoulder",8d},{"far",p.farShoulder,"shoulder",8d},{"near",p.nearHand,"wrist",3d},{"far",p.farHand,"wrist",3d}}){
                String side=(String)joint[0],name=(String)joint[2];double[] q=SketchHumanFigure.project(s,(double[])joint[1]);Path connected=new Path();
                for(String part:new String[]{"upper_arm","forearm","hand",name}){JSONObject object=part(layer,"actor_"+side+"_"+part+"_0");if(object!=null)assertTrue("Native contour union failed: view="+view+" lean="+lean+" side="+side+" joint="+name+" part="+part,connected.op(SketchFrameRenderer.path(object.getJSONArray("commands"),1280,720),Path.Op.UNION));}
                Path disk=new Path(),outside=new Path();disk.addCircle((float)(640+q[0]),(float)(720*.88+q[1]),((Double)joint[3]).floatValue(),Path.Direction.CW);assertTrue(outside.op(disk,connected,Path.Op.DIFFERENCE));assertTrue("Rounded native continuity at "+view+" "+side+" "+name,outside.isEmpty());
            }
        }
    }

    @Test public void sleeveCleanupHasAnExternalRimWithoutClosedMechanicalJointRings()throws Exception {
        for(String view:VIEWS){JSONObject s=SketchHumanFigure.settings(new JSONObject().put("view",view)),pose=new JSONObject().put("nearHandX",96).put("nearHandY",-255).put("nearElbowSwivel",165),doc=document(1280,720),layer=layer(doc);SketchHumanFigure.Skeleton skeleton=SketchHumanFigure.skeleton(s,pose);SketchHumanFigure.draw(doc,layer,"actor",s,pose,0);
            for(boolean near:new boolean[]{true,false}){String side=near?"near":"far";double[] elbow=SketchHumanFigure.project(s,near?skeleton.nearElbow:skeleton.farElbow);JSONObject rim=part(layer,"actor_"+side+"_sleeve_contour_0");assertNotNull("Sleeve needs one continuous external contour",rim);assertFalse(rim.getBoolean("fill"));
                Path contour=SketchFrameRenderer.path(rim.getJSONArray("commands"),1280,720),stroke=new Path(),centre=new Path(),inside=new Path();Paint paint=new Paint();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((float)(rim.getDouble("width")*720));paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);assertTrue(paint.getFillPath(contour,stroke));centre.addCircle((float)(640+elbow[0]),(float)(720*.88+elbow[1]),5.5f,Path.Direction.CW);assertTrue(inside.op(stroke,centre,Path.Op.INTERSECT));assertTrue("External sleeve rim must not cross the elbow interior: "+view,inside.isEmpty());
                JSONArray objects=layer.getJSONArray("objects");for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(object.getBoolean("fill")||!object.getString("id").startsWith("actor_"+side+"_"))continue;RectF bounds=bounds(object,1280,720);if(bounds.width()>30||bounds.height()>30)continue;JSONArray commands=object.getJSONArray("commands");boolean closed=false;for(int j=0;j<commands.length();j++)closed|="Z".equals(commands.getJSONArray(j).getString(0));
                    for(double[] joint:new double[][]{near?skeleton.nearElbow:skeleton.farElbow,near?skeleton.nearShoulder:skeleton.farShoulder}){double[] p=SketchHumanFigure.project(s,joint);if(Math.hypot(bounds.centerX()-640-p[0],bounds.centerY()-720*.88-p[1])<2)assertFalse("No isolated closed outline around a clothing joint",closed);}
                }
            }
        }
    }

    @Test public void adultCoatKeepsRelaxedWaistVolumeAndCurvedHem()throws Exception {
        for(String build:new String[]{"slender","broad"}){JSONObject doc=document(1280,720);SketchHumanFigure.draw(doc,layer(doc),"actor",new JSONObject().put("build",build),new JSONObject(),0);JSONObject coat=part(layer(doc),"actor_coat_0");Path shape=SketchFrameRenderer.path(coat.getJSONArray("commands"),1280,720);double hip=720*.88-180;RectF[] bands=new RectF[2];
            for(int i=0;i<2;i++){float y=(float)(hip+(i==0?-95:0));Path band=new Path(),section=new Path();band.addRect(0,y-.5f,1280,y+.5f,Path.Direction.CW);assertTrue(section.op(shape,band,Path.Op.INTERSECT));bands[i]=new RectF();section.computeBounds(bands[i],true);}
            double ratio=bands[1].width()/bands[0].width();assertTrue("Coat should hang with adult waist volume rather than a rigid pinched tube: "+ratio,ratio>.74&&ratio<.95);
        }
    }

    @Test public void projectedGripFollowsTheActualWristRotationAndFingerPinchInAllViews()throws Exception {
        for(String view:VIEWS)for(int angle:new int[]{-90,0,90}){
            JSONObject s=SketchHumanFigure.settings(new JSONObject().put("view",view)),pose=new JSONObject().put("nearWrist",angle).put("farWrist",-angle).put("handOpen",0),doc=document(1280,720),layer=layer(doc);SketchHumanFigure.draw(doc,layer,"actor",s,pose,0);SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(s,pose);bones(p);
            for(boolean near:new boolean[]{true,false}){String side=near?"near":"far";double[] contact=SketchHumanFigure.grip(s,pose,near),wrist=SketchHumanFigure.project(s,near?p.nearHand:p.farHand);assertEquals("Grip is a palm/finger contact, not the wrist",15,Math.hypot(contact[0]-wrist[0],contact[1]-wrist[1]),1e-8);
                Path pad=new Path();pad.addCircle((float)(640+contact[0]),(float)(720*.88+contact[1]),.2f,Path.Direction.CW);
                for(String digit:new String[]{"thumb","finger_index"}){Path skin=SketchFrameRenderer.path(part(layer,"actor_"+side+"_"+digit+"_0").getJSONArray("commands"),1280,720),outside=new Path();assertTrue(outside.op(pad,skin,Path.Op.DIFFERENCE));assertTrue("Reported contact must be inside the drawn "+view+" "+side+" "+digit,outside.isEmpty());}
            }
        }
        JSONObject s=SketchHumanFigure.settings(new JSONObject()),closed=new JSONObject().put("handOpen",0),open=new JSONObject().put("handOpen",1);double[] a=SketchHumanFigure.grip(s,closed,true),b=SketchHumanFigure.grip(s,open,true);assertTrue("Contact tracks the authored hand shape",Math.hypot(a[0]-b[0],a[1]-b[1])>1);
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("nearWrist",181),new JSONObject().put("farWrist",-181),new JSONObject().put("handOpen",1.1)})assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.validatePose(invalid));
    }

    @Test public void nativeHandleAtGripActuallyIntersectsAndIsOccludedByClosedFingers()throws Exception {
        JSONObject s=SketchHumanFigure.settings(new JSONObject()),pose=new JSONObject().put("nearWrist",90).put("handOpen",0),doc=document(1280,720);double[] g=SketchHumanFigure.grip(s,pose,true);double x=640+g[0],y=720*.88+g[1];
        JSONArray commands=new JSONArray().put(new JSONArray().put("M").put((x-1.8)/1280).put((y-23)/720)).put(new JSONArray().put("L").put((x+1.8)/1280).put((y-23)/720)).put(new JSONArray().put("L").put((x+1.8)/1280).put((y+23)/720)).put(new JSONArray().put("L").put((x-1.8)/1280).put((y+23)/720)).put(new JSONArray().put("Z"));
        JSONObject handle=SketchEditOps.completeObject(new JSONObject().put("id","contact_handle").put("kind","path").put("commands",commands).put("color","#FFFF2020").put("fill",true));layer(doc).getJSONArray("objects").put(handle);JSONObject handleOnly=SketchDocument.copy(doc);SketchHumanFigure.draw(doc,layer(doc),"actor",s,pose,0);SketchDocument.validate(doc);
        Path shape=SketchFrameRenderer.path(commands,1280,720),thumb=SketchFrameRenderer.path(part(layer(doc),"actor_near_thumb_0").getJSONArray("commands"),1280,720),contact=new Path();assertTrue(contact.op(shape,thumb,Path.Op.INTERSECT));assertFalse(contact.isEmpty());
        try(SketchFrameRenderer before=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),handleOnly);SketchFrameRenderer after=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
            Bitmap a=before.render(0),b=after.render(0);try{int px=(int)Math.round(x),py=(int)Math.round(y);assertEquals(0xFFFF2020,a.getPixel(px,py));assertNotEquals("Drawn fingers must occlude the actual handle at the reported grip",0xFFFF2020,b.getPixel(px,py));assertEquals("The handle must still extend beyond the fingers",0xFFFF2020,b.getPixel(px,py+21));}finally{a.recycle();b.recycle();}
        }
    }

    @Test public void depthAnchorsGiveProfileStrideWithoutChangingAnyBoneLength()throws Exception {
        JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("view","left")),pose=new JSONObject().put("rootZ",8).put("hipZ",4)
                .put("nearHandZ",30).put("farHandZ",-25).put("footLeftZ",40).put("footRightZ",-30);
        SketchHumanFigure.Skeleton rest=SketchHumanFigure.skeleton(settings,new JSONObject()),moved=SketchHumanFigure.skeleton(settings,pose);bones(moved);
        assertEquals(12,moved.hip[2],0);assertEquals(42,moved.nearHand[2],0);assertEquals(-13,moved.farHand[2],0);assertEquals(40,moved.leftFoot[2],0);assertEquals(-30,moved.rightFoot[2],0);
        double[] a=SketchHumanFigure.project(settings,rest.leftFoot),b=SketchHumanFigure.project(settings,moved.leftFoot);
        assertTrue("Profile stride must survive projection",Math.abs(a[0]-b[0])>40);
        JSONObject still=document(1280,720),walking=document(1280,720);SketchHumanFigure.draw(still,layer(still),"actor",settings,new JSONObject(),0);SketchHumanFigure.draw(walking,layer(walking),"actor",settings,pose,0);
        assertTrue(Math.abs(bounds(part(layer(still),"actor_left_shoe_0"),1280,720).centerX()-bounds(part(layer(walking),"actor_left_shoe_0"),1280,720).centerX())>40);
        try(SketchFrameRenderer first=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),still);SketchFrameRenderer second=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),walking)){
            Bitmap x=first.render(0),y=second.render(0);try{assertFalse("Actual side-view gait pixels must change",x.sameAs(y));}finally{x.recycle();y.recycle();}
        }
    }

    @Test public void articulationControlsShapeTheCavityAndKeepTeethAndTongueOccluded()throws Exception {
        for(String view:VIEWS){
            JSONObject s=new JSONObject().put("view",view),open=document(1280,720),closed=document(1280,720);
            JSONObject controls=new JSONObject().put("mouth",.8).put("mouthWide",.5).put("mouthRound",.4).put("mouthTeeth",1).put("mouthTongue",1);
            SketchHumanFigure.draw(open,layer(open),"actor",s,controls,0);SketchHumanFigure.draw(closed,layer(closed),"actor",s,SketchDocument.copy(controls).put("mouth",0),0);
            assertNull("Closed mouth hides teeth in "+view,part(layer(closed),"actor_teeth_0"));assertNull("Closed mouth hides tongue in "+view,part(layer(closed),"actor_tongue_0"));
            if(view.equals("back")){assertNull(part(layer(open),"actor_mouth_0"));assertNull(part(layer(open),"actor_teeth_0"));assertNull(part(layer(open),"actor_tongue_0"));continue;}
            Path cavity=SketchFrameRenderer.path(part(layer(open),"actor_mouth_0").getJSONArray("commands"),1280,720);
            for(String feature:new String[]{"teeth","tongue"}){JSONObject object=part(layer(open),"actor_"+feature+"_0");assertNotNull(object);Path path=SketchFrameRenderer.path(object.getJSONArray("commands"),1280,720),outside=new Path();assertTrue(outside.op(path,cavity,Path.Op.DIFFERENCE));assertTrue("No "+feature+" outside actual "+view+" cavity",outside.isEmpty());}
            JSONObject plain=document(1280,720),wide=document(1280,720),round=document(1280,720),unshown=document(1280,720);
            SketchHumanFigure.draw(plain,layer(plain),"actor",s,new JSONObject().put("mouth",.8),0);
            SketchHumanFigure.draw(wide,layer(wide),"actor",s,new JSONObject().put("mouth",.8).put("mouthWide",1),0);
            SketchHumanFigure.draw(round,layer(round),"actor",s,new JSONObject().put("mouth",.8).put("mouthRound",1),0);
            SketchHumanFigure.draw(unshown,layer(unshown),"actor",s,new JSONObject().put("mouth",.8).put("mouthTeeth",0).put("mouthTongue",0),0);
            RectF normal=bounds(part(layer(plain),"actor_mouth_0"),1280,720),wider=bounds(part(layer(wide),"actor_mouth_0"),1280,720),rounded=bounds(part(layer(round),"actor_mouth_0"),1280,720);
            assertTrue(wider.width()>normal.width()*1.3);assertTrue(rounded.width()<normal.width()*.7);assertTrue(rounded.height()>normal.height());assertNull(part(layer(unshown),"actor_teeth_0"));assertNull(part(layer(unshown),"actor_tongue_0"));
        }
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("mouthWide",-1.1),new JSONObject().put("mouthRound",1.1),new JSONObject().put("mouthTeeth",-.1),new JSONObject().put("mouthTongue",1.1)})assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.validatePose(invalid));
    }

    @Test public void pureVerticalGazeHasReadableTravelWithoutChangingTheEyeOpening()throws Exception {
        for(String view:new String[]{"front","left","right","high","low"}){JSONObject up=document(1280,720),down=document(1280,720),settings=new JSONObject().put("view",view);SketchHumanFigure.draw(up,layer(up),"actor",settings,new JSONObject().put("gazeY",-1),0);SketchHumanFigure.draw(down,layer(down),"actor",settings,new JSONObject().put("gazeY",1),0);RectF a=bounds(part(layer(up),"actor_eye_near_iris_0"),1280,720),b=bounds(part(layer(down),"actor_eye_near_iris_0"),1280,720);assertTrue("Pure vertical gaze must move visibly at native scale: "+view,b.centerY()-a.centerY()>=.75);assertEquals(SketchDocument.canonical(part(layer(up),"actor_eye_near_0")),SketchDocument.canonical(part(layer(down),"actor_eye_near_0")));}
    }
    @Test public void extremeGazeKeepsTheDrawnIrisInsideTheActualEyeOpening()throws Exception {
        for(String view:new String[]{"front","left","right","high","low"})for(int direction:new int[]{-1,1}){
            JSONObject doc=document(1280,720);SketchHumanFigure.draw(doc,layer(doc),"actor",new JSONObject().put("view",view),new JSONObject().put("gazeX",direction).put("gazeY",direction),0);
            for(String eye:new String[]{"eye_near","eye_far"}){JSONObject white=part(layer(doc),"actor_"+eye+"_0");if(white==null)continue;
                Path aperture=SketchFrameRenderer.path(white.getJSONArray("commands"),1280,720),iris=SketchFrameRenderer.path(part(layer(doc),"actor_"+eye+"_iris_0").getJSONArray("commands"),1280,720),outside=new Path();
                assertTrue(outside.op(iris,aperture,Path.Op.DIFFERENCE));assertTrue("Gaze must not leak through the lid: "+view,outside.isEmpty());
            }
        }
    }

    @Test public void blinkClosesNativeAperturesKeepsIrisOccludedAndHidesItAtFullClosure()throws Exception {
        for(String view:VIEWS){
            double previousHeight=Double.POSITIVE_INFINITY;
            for(double blink:new double[]{0,.5,.95,1}){
                JSONObject doc=document(1280,720);SketchHumanFigure.draw(doc,layer(doc),"actor",new JSONObject().put("view",view),new JSONObject().put("blink",blink).put("gazeX",1).put("gazeY",-1),0);SketchDocument.validate(doc);
                for(String eye:new String[]{"eye_near","eye_far"}){
                    JSONObject opening=part(layer(doc),"actor_"+eye+"_0"),iris=part(layer(doc),"actor_"+eye+"_iris_0"),lid=part(layer(doc),"actor_"+eye+"_closed_lid_0");
                    boolean hidden=view.equals("back")||eye.equals("eye_far")&&(view.equals("left")||view.equals("right"));
                    if(hidden){assertNull(opening);assertNull(iris);assertNull(lid);continue;}
                    if(blink==1){assertNull("Closed eye must omit white aperture",opening);assertNull("Closed lid must hide the iris",iris);assertNotNull(lid);assertFalse(lid.getBoolean("fill"));}
                    else {
                        assertNotNull(opening);assertNotNull(iris);assertNull(lid);Path aperture=SketchFrameRenderer.path(opening.getJSONArray("commands"),1280,720),pigment=SketchFrameRenderer.path(iris.getJSONArray("commands"),1280,720),outside=new Path();
                        assertTrue(outside.op(pigment,aperture,Path.Op.DIFFERENCE));assertTrue("Blink must occlude the actual iris: "+view+" "+blink,outside.isEmpty());
                        if(eye.equals("eye_near")){double height=bounds(opening,1280,720).height();assertTrue("Blink must narrow the actual aperture",height<previousHeight);previousHeight=height;}
                    }
                }
            }
        }
        JSONObject open=document(1280,720),closed=document(1280,720);SketchHumanFigure.draw(open,layer(open),"actor",new JSONObject(),new JSONObject(),0);SketchHumanFigure.draw(closed,layer(closed),"actor",new JSONObject(),new JSONObject().put("blink",1),0);
        try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),open);SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),closed)){
            Bitmap first=a.render(0),second=b.render(0);try{assertFalse("A full blink must change actual native pixels",first.sameAs(second));}finally{first.recycle();second.recycle();}
        }
        for(Object invalid:new Object[]{-.1,1.1,"1",true})assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.validatePose(new JSONObject().put("blink",invalid)));
    }

    @Test public void restCoordinatesHaveAdultProportionsAndPreciselyFixedBones()throws Exception {
        SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(new JSONObject(),new JSONObject());
        assertEquals(0,p.hip[0],0);assertEquals(-180,p.hip[1],0);assertEquals(-300,p.nearShoulder[1],0);
        assertEquals(-360,p.head[1],0);assertEquals(0,p.leftFoot[1],0);assertEquals(0,p.rightFoot[1],0);
        assertTrue("Crown-to-floor / full head height must be adult",390d/54>=7&&390d/54<=7.5);
        bones(p);assertEquals(14,p.leftFoot[1]-p.leftAnkle[1],0);assertEquals(14,p.rightFoot[1]-p.rightAnkle[1],0);
    }

    @Test public void hundredsOfGenericPosesKeepLengthsAndExplicitAnchorsAcrossViews()throws Exception {
        for(String view:VIEWS)for(int i=0;i<80;i++){
            double phase=i*.19;JSONObject settings=new JSONObject().put("view",view).put("build",i%2==0?"slender":"broad");
            JSONObject pose=new JSONObject().put("bodyLean",20*Math.sin(phase)).put("headTilt",18*Math.cos(phase)).put("hipY",-180+7*Math.sin(phase*.7));
            SketchHumanFigure.Skeleton rest=SketchHumanFigure.skeleton(settings,pose);
            double nearX=(i%2==0?40:47)+20*Math.sin(phase),nearY=-182;
            double farX=-(i%2==0?40:47)-18*Math.cos(phase),farY=-178;
            pose.put("nearHandX",nearX).put("nearHandY",nearY).put("farHandX",farX).put("farHandY",farY);
            pose.put("footLeftX",-28-8*Math.sin(phase)).put("footLeftY",-8*Math.max(0,Math.sin(phase)));
            pose.put("footRightX",28+8*Math.sin(phase)).put("footRightY",-8*Math.max(0,-Math.sin(phase)));
            SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings,pose);bones(p);
            SketchHumanFigure.Skeleton unleaned=SketchHumanFigure.skeleton(settings,SketchDocument.copy(pose).put("bodyLean",0));
            JSONObject normalized=SketchHumanFigure.settings(settings);double angle=Math.toRadians(pose.getDouble("bodyLean"));
            double[] origin=SketchHumanFigure.project(normalized,p.hip);
            for(double[][] pair:new double[][][]{new double[][]{unleaned.nearHand,p.nearHand},new double[][]{unleaned.farHand,p.farHand}}){
                double[] a=SketchHumanFigure.project(normalized,pair[0]),b=SketchHumanFigure.project(normalized,pair[1]);double x=a[0]-origin[0],y=a[1]-origin[1];
                assertEquals(origin[0]+x*Math.cos(angle)-y*Math.sin(angle),b[0],1e-8);assertEquals(origin[1]+x*Math.sin(angle)+y*Math.cos(angle),b[1],1e-8);
            }
            assertEquals(pose.getDouble("footLeftX"),p.leftFoot[0],0);assertEquals(pose.getDouble("footRightY"),p.rightFoot[1],0);
        }
    }

    @Test public void rootsMoveBodyWhileExplicitFeetStayInTheUnchangingFloorFrame()throws Exception {
        JSONObject s=new JSONObject(),pose=new JSONObject().put("rootX",14).put("rootY",-4).put("footLeftX",-25).put("footRightX",32);
        SketchHumanFigure.Skeleton a=SketchHumanFigure.skeleton(s,new JSONObject()),b=SketchHumanFigure.skeleton(s,pose);bones(b);
        assertEquals(a.hip[0]+14,b.hip[0],0);assertEquals(a.hip[1]-4,b.hip[1],0);assertEquals(a.nearHand[0]+14,b.nearHand[0],1e-9);
        assertEquals(-25,b.leftFoot[0],0);assertEquals(32,b.rightFoot[0],0);assertEquals(0,b.leftFoot[1],0);assertEquals(0,b.rightFoot[1],0);
    }

    @Test public void shortAndLongHairAreIndependentStyleControlsAcrossAllViews()throws Exception {
        for(String view:VIEWS){JSONObject shortDoc=document(1280,720),longDoc=document(1280,720);
            SketchHumanFigure.draw(shortDoc,layer(shortDoc),"actor",new JSONObject().put("view",view).put("hairStyle","short"),new JSONObject(),0);
            SketchHumanFigure.draw(longDoc,layer(longDoc),"actor",new JSONObject().put("view",view).put("hairStyle","long"),new JSONObject(),0);
            assertNull(part(layer(shortDoc),"actor_long_hair_0"));assertNotNull(part(layer(longDoc),"actor_long_hair_0"));
            assertNotEquals(SketchDocument.canonical(layer(shortDoc)),SketchDocument.canonical(layer(longDoc)));SketchDocument.validate(longDoc);
        }
    }

    @Test public void unreachableAndMalformedPosesRejectAtomicallyRatherThanStretchingBones()throws Exception {
        JSONObject doc=document(1280,720),layer=layer(doc);String before=SketchDocument.canonical(doc);
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("nearHandX",500),new JSONObject().put("nearHandY",-590),new JSONObject().put("footLeftX",230),new JSONObject().put("footRightY",70),new JSONObject().put("bodyLean",26),new JSONObject().put("headTilt",21),new JSONObject().put("mouth",1.1),new JSONObject().put("gazeY",-1.1),new JSONObject().put("brow",2),new JSONObject().put("smile",-2),new JSONObject().put("hipX",true),new JSONObject().put("oldFilmGesture",1)}){
            assertThrows("Must reject "+bad,IllegalArgumentException.class,()->SketchHumanFigure.draw(doc,layer,"actor",new JSONObject(),bad,0));
            assertEquals(before,SketchDocument.canonical(doc));
        }
        assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.draw(doc,layer,"actor",new JSONObject(),new JSONObject(),Double.NaN));
    }

    @Test public void allSixViewsHaveNativeBezierAnatomyAndCorrectHiddenFaceFeatures()throws Exception {
        Set<Integer> hashes=new HashSet<>();
        for(String view:VIEWS){
            JSONObject doc=document(1280,720),layer=layer(doc),settings=new JSONObject().put("view",view);
            SketchHumanFigure.draw(doc,layer,"adult",settings,new JSONObject().put("mouth",.6).put("smile",.4).put("brow",.3),1);SketchDocument.validate(doc);
            assertNotNull(part(layer,"adult_coat_0"));assertNotNull(part(layer,"adult_head_0"));assertNotNull(part(layer,"adult_near_hand_0"));
            assertNotNull(part(layer,"adult_near_finger_index_0"));assertNotNull(part(layer,"adult_near_finger_little_0"));assertNotNull(part(layer,"adult_near_thumb_0"));
            assertNotNull(part(layer,"adult_left_shoe_0"));assertNotNull(part(layer,"adult_right_shoe_0"));
            if(view.equals("back")){assertNull(part(layer,"adult_eye_near_0"));assertNull(part(layer,"adult_eye_far_0"));assertNull(part(layer,"adult_mouth_0"));assertNotNull(part(layer,"adult_back_seam_0"));}
            else {assertNotNull(part(layer,"adult_eye_near_0"));assertNotNull(part(layer,"adult_mouth_0"));if(view.equals("left")||view.equals("right"))assertNull(part(layer,"adult_eye_far_0"));else assertNotNull(part(layer,"adult_eye_far_0"));}
            boolean curved=false;JSONArray objects=layer.getJSONArray("objects");
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);assertEquals("path",o.getString("kind"));assertEquals(0,o.getInt("frame"));assertEquals(1,o.getInt("exposure"));assertEquals(0,o.getJSONArray("keys").length());JSONArray commands=o.getJSONArray("commands");for(int j=0;j<commands.length();j++)if("C".equals(commands.getJSONArray(j).getString(0)))curved=true;}
            assertTrue("Original Bezier surfaces must remain editable",curved);
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){Bitmap image=renderer.render(0);try{int[] pixels=new int[1280*720];image.getPixels(pixels,0,1280,0,0,1280,720);assertTrue("Views must create distinct actual native images",hashes.add(Arrays.hashCode(pixels)));}finally{image.recycle();}}
        }
    }

    @Test public void drawnWristPalmsAndSolesMeetTheActualProjectedAnchors()throws Exception {
        for(String view:VIEWS){
            JSONObject doc=document(1280,720),layer=layer(doc),settings=SketchHumanFigure.settings(new JSONObject().put("view",view));
            JSONObject pose=new JSONObject().put("bodyLean",9).put("headTilt",-12);SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings,pose);
            SketchHumanFigure.draw(doc,layer,"actor",settings,pose,0);
            for(Object[] pair:new Object[][]{{"near_hand",p.nearHand},{"far_hand",p.farHand},{"left_shoe",p.leftFoot},{"right_shoe",p.rightFoot}}){
                double[] q=SketchHumanFigure.project(settings,(double[])pair[1]);RectF b=bounds(part(layer,"actor_"+pair[0]+"_0"),1280,720);
                float x=(float)(1280*settings.getDouble("x")+q[0]*settings.getDouble("size")),y=(float)(720*settings.getDouble("floorY")+q[1]*settings.getDouble("size"));
                assertTrue("Attached "+view+" "+pair[0]+" "+b+" vs "+x+","+y,x>=b.left-.05&&x<=b.right+.05&&y>=b.top-.05&&y<=b.bottom+.05);
            }
            RectF neck=bounds(part(layer,"actor_neck_0"),1280,720),head=bounds(part(layer,"actor_head_0"),1280,720);
            assertTrue("The jaw must join the visible neck: "+view,RectF.intersects(neck,head));
        }
    }

    @Test public void buildColoursAndAllActingControlsChangeNativePaths()throws Exception {
        JSONObject base=document(1280,720);SketchHumanFigure.draw(base,layer(base),"actor",new JSONObject(),new JSONObject(),0);
        for(JSONObject pose:new JSONObject[]{new JSONObject().put("bodyLean",12),new JSONObject().put("headTilt",15),new JSONObject().put("gazeX",1),new JSONObject().put("gazeY",-1),new JSONObject().put("mouth",.8),new JSONObject().put("smile",-.7),new JSONObject().put("brow",.9),new JSONObject().put("nearHandX",35).put("nearHandY",-215),new JSONObject().put("farHandX",-52).put("farHandY",-205),new JSONObject().put("hipX",8),new JSONObject().put("footLeftX",-42).put("footLeftY",-10)}){
            JSONObject changed=document(1280,720);SketchHumanFigure.draw(changed,layer(changed),"actor",new JSONObject(),pose,0);assertNotEquals("Ignoring acting control "+pose,SketchDocument.canonical(layer(base)),SketchDocument.canonical(layer(changed)));
        }
        JSONObject broad=document(1280,720);SketchHumanFigure.draw(broad,layer(broad),"actor",new JSONObject().put("build","broad"),new JSONObject(),0);
        assertTrue(bounds(part(layer(broad),"actor_coat_0"),1280,720).width()>bounds(part(layer(base),"actor_coat_0"),1280,720).width()*1.1);
        JSONObject colors=new JSONObject().put("skinColor","#FF98674E").put("hairColor","#FF382A22").put("coatColor","#FF62553A").put("trouserColor","#FF414B52");
        JSONObject colored=document(1280,720);SketchHumanFigure.draw(colored,layer(colored),"actor",colors,new JSONObject(),0);
        assertEquals("#FF98674E",part(layer(colored),"actor_head_0").getString("color"));assertEquals("#FF62553A",part(layer(colored),"actor_coat_0").getString("color"));assertEquals("#FF414B52",part(layer(colored),"actor_left_thigh_0").getString("color"));
    }

    @Test public void uniformCanvasMappingAndBoundsNeverRelyOnInkClamping()throws Exception {
        double ratio=0;
        for(int[] size:new int[][]{{1280,720},{720,1280},{900,900}}){JSONObject doc=document(size[0],size[1]);SketchHumanFigure.draw(doc,layer(doc),"actor",new JSONObject(),new JSONObject(),0);SketchDocument.validate(doc);
            RectF head=bounds(part(layer(doc),"actor_head_0"),size[0],size[1]);if(ratio==0)ratio=head.width()/head.height();else assertEquals(ratio,head.width()/head.height(),.001);
            JSONArray objects=layer(doc).getJSONArray("objects");for(int i=0;i<objects.length();i++){RectF b=bounds(objects.getJSONObject(i),size[0],size[1]);assertTrue(b.left>0&&b.top>0&&b.right<size[0]&&b.bottom<size[1]);}
        }
        for(JSONObject settings:new JSONObject[]{new JSONObject().put("x",0),new JSONObject().put("x",1),new JSONObject().put("floorY",.2),new JSONObject().put("floorY",1)}){
            JSONObject doc=document(1280,720);String before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.draw(doc,layer(doc),"actor",settings,new JSONObject(),0));assertEquals(before,SketchDocument.canonical(doc));
        }
    }

    @Test public void outlineStrokeScalesUniformlyWithTheFigureOnPortraitSquareAndLandscape()throws Exception {
        double ratio=0;
        for(int[] size:new int[][]{{1280,720},{720,1280},{900,900}}){
            JSONObject doc=document(size[0],size[1]);SketchHumanFigure.draw(doc,layer(doc),"actor",new JSONObject(),new JSONObject(),0);
            JSONObject outline=part(layer(doc),"actor_head_1");assertFalse(outline.getBoolean("fill"));
            double actualStroke=outline.getDouble("width")*Math.min(size[0],size[1]),headHeight=bounds(outline,size[0],size[1]).height();
            assertEquals("Reference .8-pixel head ink must share uniform geometry scale",.8*Math.min(size[0]/1280d,size[1]/720d),actualStroke,1e-8);
            if(ratio==0)ratio=actualStroke/headHeight;else assertEquals("Portrait must not thin its outlines relative to the anatomy",ratio,actualStroke/headHeight,1e-7);
        }
    }

    @Test public void portraitEdgePlacementUsesTheActualRendererStrokeRadius()throws Exception {
        JSONObject baseline=document(720,1280);SketchHumanFigure.draw(baseline,layer(baseline),"actor",new JSONObject(),new JSONObject(),0);
        double right=0;JSONArray objects=layer(baseline).getJSONArray("objects");
        for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);double pad=object.getBoolean("fill")?0:object.getDouble("width")*720/2;right=Math.max(right,bounds(object,720,1280).right+pad);}
        double x=.5+(720-.02-right)/720;JSONObject edge=document(720,1280);
        SketchHumanFigure.draw(edge,layer(edge),"actor",new JSONObject().put("x",x),new JSONObject(),0);SketchDocument.validate(edge);
        JSONArray result=layer(edge).getJSONArray("objects");for(int i=0;i<result.length();i++){JSONObject object=result.getJSONObject(i);double pad=object.getBoolean("fill")?0:object.getDouble("width")*720/2;assertTrue("The actual native stroke must remain on the portrait canvas",bounds(object,720,1280).right+pad<=720);}
    }

    @Test public void independentActorsAndExistingArtworkSurviveWhileLockedDuplicateAndAnimatedTargetsReject()throws Exception {
        JSONObject doc=document(1280,720);SketchHumanFigure.draw(doc,layer(doc),"one",new JSONObject().put("x",.3).put("size",.7),new JSONObject(),0);String first=SketchDocument.canonical(layer(doc).getJSONArray("objects"));
        SketchHumanFigure.draw(doc,layer(doc),"two",new JSONObject().put("x",.7).put("size",.7).put("view","back"),new JSONObject(),0);SketchDocument.validate(doc);assertTrue(layer(doc).getJSONArray("objects").length()>100);
        JSONArray objects=layer(doc).getJSONArray("objects"),prefix=new JSONArray();for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").startsWith("one_"))prefix.put(objects.getJSONObject(i));assertEquals(first,SketchDocument.canonical(prefix));
        String before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.draw(doc,layer(doc),"one",new JSONObject(),new JSONObject(),0));assertEquals(before,SketchDocument.canonical(doc));
        layer(doc).put("locked",true);before=SketchDocument.canonical(doc);assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.draw(doc,layer(doc),"three",new JSONObject(),new JSONObject(),0));assertEquals(before,SketchDocument.canonical(doc));
        JSONObject animated=document(1280,720).put("frameCount",2);assertThrows(IllegalArgumentException.class,()->SketchHumanFigure.draw(animated,layer(animated),"one",new JSONObject(),new JSONObject(),0));
    }

    private static void bones(SketchHumanFigure.Skeleton p){
        assertEquals(76,distance(p.nearShoulder,p.nearElbow),1e-8);assertEquals(70,distance(p.nearElbow,p.nearHand),1e-8);
        assertEquals(76,distance(p.farShoulder,p.farElbow),1e-8);assertEquals(70,distance(p.farElbow,p.farHand),1e-8);
        assertEquals(90,distance(p.leftHip,p.leftKnee),1e-8);assertEquals(86,distance(p.leftKnee,p.leftAnkle),1e-8);
        assertEquals(90,distance(p.rightHip,p.rightKnee),1e-8);assertEquals(86,distance(p.rightKnee,p.rightAnkle),1e-8);
    }
    private static double distance(double[] a,double[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(sum);}
    private static JSONObject document(int width,int height)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","human_figure").put("width",width).put("height",height).put("frameCount",1).put("fps",30));}
    private static JSONObject layer(JSONObject doc)throws Exception{return doc.getJSONArray("layers").getJSONObject(0);}
    private static JSONObject part(JSONObject layer,String id)throws Exception{JSONArray a=layer.getJSONArray("objects");for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).getString("id")))return a.getJSONObject(i);return null;}
    private static int partIndex(JSONObject layer,String id)throws Exception{JSONArray a=layer.getJSONArray("objects");for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).getString("id")))return i;throw new AssertionError("Missing native part "+id);}
    private static RectF bounds(JSONObject object,int width,int height)throws Exception{assertNotNull("Missing drawn anatomy",object);RectF b=new RectF();SketchFrameRenderer.path(object.getJSONArray("commands"),width,height).computeBounds(b,true);return b;}
}
