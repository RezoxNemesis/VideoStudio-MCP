package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanGaitTest {
 @Test public void walkingHasReadableStrideAndPassingRiseRatherThanPersistentShuffling()throws Exception {
  JSONArray poses=SketchHumanPresets.create("walk",240,60);JSONObject contact=poses.getJSONObject(0),passing=poses.getJSONObject(23);
  assertTrue("Feet must separate by at least60reference pixels",Math.abs(contact.getDouble("footLeftZ")-contact.getDouble("footRightZ"))>=60);
  assertTrue("Pelvis rises into the passing pose",passing.getDouble("hipY")<contact.getDouble("hipY")-2.5);
  for(int f=1;f<poses.length();f++){JSONObject a=poses.getJSONObject(f-1),b=poses.getJSONObject(f);for(String side:new String[]{"Left","Right"})if(a.getDouble("foot"+side+"Y")==0&&b.getDouble("foot"+side+"Y")==0)assertEquals("Planted world foot must not slide",a.getDouble("foot"+side+"Z"),b.getDouble("foot"+side+"Z"),1e-8);SketchHumanFigure.skeleton(new JSONObject(),SketchHumanAnimation.poseAt(poses,f));}
 }
 @Test public void newWalkAndJogKeepEveryPoseReachableAcrossNativeSixViews()throws Exception {
  for(String action:new String[]{"walk","run"})for(String view:new String[]{"front","left","right","back","high","low"}){JSONObject d=SketchHandAnimationTest.doc(24);SketchEditOps.apply(d,SketchHumanAnimationTest.op(action,view).put("fit",true).put("settings",new JSONObject().put("view",view).put("size",1.25).put("floorY",.85)));SketchDocument.validate(d);}
 }
 @Test public void explicitMotionFramingMovesTheAnchorWithoutShrinkingOrRetimingTheActor()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(24),op=SketchHumanAnimationTest.op("walk","low").put("settings",new JSONObject().put("view","low").put("size",1.25).put("floorY",.85));
  assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(SketchDocument.copy(d),op));String before=SketchDocument.canonical(op);SketchEditOps.apply(d,new JSONObject(op.toString()).put("fit",true));JSONObject settings=SketchDocument.object(d,"actor_rig").getJSONObject("human").getJSONObject("settings");assertEquals(1.25,settings.getDouble("size"),0);assertTrue(settings.getDouble("floorY")<.85);assertEquals(1280,d.getInt("width"));assertEquals(720,d.getInt("height"));assertEquals(96,d.getInt("frameCount"));assertEquals(24,d.getInt("fps"));assertEquals(before,SketchDocument.canonical(op));
 }
 @Test public void joggingUsesBentArmsAndRelaxedHandsRatherThanHangingStraightArms()throws Exception {
  JSONArray poses=SketchHumanPresets.create("run",96,24);for(int f=0;f<96;f+=8){JSONObject p=SketchHumanAnimation.poseAt(poses,f);SketchHumanFigure.Skeleton b=SketchHumanFigure.skeleton(new JSONObject(),p);assertTrue("A jogging forearm must remain visibly bent",distance(b.nearShoulder,b.nearHand)<115);assertTrue("Opposite arm also pumps with a bent elbow",distance(b.farShoulder,b.farHand)<115);assertTrue("Running hands are relaxed, not rigid spread palms",p.getDouble("handOpen")<=.5);}
 }
 @Test public void walkingStanceExtendsTheLegRatherThanRemainingInACrouch()throws Exception {
  JSONArray poses=SketchHumanPresets.create("walk",96,24);JSONObject contact=SketchHumanAnimation.poseAt(poses,0);SketchHumanFigure.Skeleton b=SketchHumanFigure.skeleton(new JSONObject(),contact);
  assertTrue("Contact hip-to-ankle extension should exceed170 of176 fixed bone pixels",distance(b.leftHip,b.leftAnkle)>170);assertTrue(distance(b.rightHip,b.rightAnkle)>170);
  double lift=0;for(int f=0;f<96;f++)lift=Math.max(lift,-SketchHumanAnimation.poseAt(poses,f).getDouble("footLeftY"));assertTrue("Swing shoe needs a readable clearance above the deck",lift>=22);
 }
 @Test public void independentGaitPhaseChangesFootTimingWithoutChangingRootTravelOrClock()throws Exception {
  JSONArray a=SketchHumanPresets.create("walk",96,24),b=SketchHumanPresets.create("walk",96,24,new JSONObject().put("phase",.35));assertEquals(a.length(),b.length());
  for(int f=0;f<96;f++){JSONObject x=a.getJSONObject(f),y=b.getJSONObject(f);assertEquals(x.getDouble("rootZ"),y.getDouble("rootZ"),1e-9);assertEquals(x.getInt("frame"),y.getInt("frame"));SketchHumanFigure.skeleton(new JSONObject(),SketchHumanAnimation.poseAt(b,f));}
  assertNotEquals(a.getJSONObject(0).getDouble("footLeftZ"),b.getJSONObject(0).getDouble("footLeftZ"),.01);
 }
 @Test public void angerKeepsAFreeArmGestureWhenTheOtherHandMustHoldAProp()throws Exception {
  JSONArray poses=SketchHumanAnimation.presetPoses("anger",96,24,new JSONObject(),"front",new JSONObject().put("nearHandY",-210));double first=poses.getJSONObject(0).getDouble("farHandY"),raised=first;for(int f=0;f<96;f++)raised=Math.min(raised,SketchHumanAnimation.poseAt(poses,f).getDouble("farHandY"));assertTrue("Pinning a prop hand must not cancel the actor's only gesture",first-raised>=50);
 }
 private double distance(double[] a,double[] b){double n=0;for(int i=0;i<a.length;i++)n+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(n);}
 @Test public void profilePresetsLeanAndLookInTheCharactersFacingDirection()throws Exception {
  for(String action:new String[]{"walk","look_down"}){JSONObject a=SketchHandAnimationTest.doc(30),b=SketchHandAnimationTest.doc(30);SketchEditOps.apply(a,SketchHumanAnimationTest.op(action,"left").put("fit",true));SketchEditOps.apply(b,SketchHumanAnimationTest.op(action,"right").put("fit",true));JSONObject left=SketchHumanAnimation.pose(SketchDocument.object(a,"actor_rig"),60),right=SketchHumanAnimation.pose(SketchDocument.object(b,"actor_rig"),60);assertEquals(-right.getDouble("bodyLean"),left.getDouble("bodyLean"),1e-8);assertEquals(-right.getDouble("headTilt"),left.getDouble("headTilt"),1e-8);}
 }
}
