package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanAnimationTest {
 static JSONObject op(String preset,String view)throws Exception{return new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","actor").put("preset",preset).put("settings",new JSONObject().put("view",view));}
 @Test public void fullBodyActingDispatchRetainsEditablePosesAndMovesActualNativePixels()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30);SketchEditOps.apply(d,op("explain","front"));SketchDocument.validate(d);JSONArray a=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertEquals(1,a.length());assertEquals("human",a.getJSONObject(0).getString("kind"));
  try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap x=r.render(0),y=r.render(60);try{assertFalse(x.sameAs(y));}finally{x.recycle();y.recycle();}}
 }
 @Test public void presetPoseOverridesRetainGaitContactsWhileHoldingAndLooking()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30),o=op("walk","right");o.put("poseOverrides",new JSONObject().put("nearHandY",-215).put("nearHandX",64).put("handOpen",.25).put("gazeX",-.6));
  SketchEditOps.apply(d,o);JSONObject h=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0).getJSONObject("human");JSONArray base=SketchHumanPresets.create("walk",d.getInt("frameCount"),30);
  for(int f=0;f<base.length();f++){JSONObject actual=h.getJSONArray("poses").getJSONObject(f);assertEquals(-215,actual.getDouble("nearHandY"),0);assertEquals(.25,actual.getDouble("handOpen"),0);assertEquals(-.6,actual.getDouble("gazeX"),0);for(String key:new String[]{"rootZ","footLeftZ","footLeftY","footRightZ","footRightY"})assertEquals(base.getJSONObject(f).getDouble(key),actual.getDouble(key),0);}
 }
 @Test public void invalidOrAmbiguousOverridesLeaveDocumentUntouched()throws Exception {
  for(JSONObject invalid:new JSONObject[]{new JSONObject().put("gazeX",2),new JSONObject().put("frame",0),new JSONObject().put("unknown",1)}){JSONObject d=SketchHandAnimationTest.doc(30);String before=SketchDocument.canonical(d);assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(d,op("listen","front").put("poseOverrides",invalid)));assertEquals(before,SketchDocument.canonical(d));}
  JSONObject d=SketchHandAnimationTest.doc(30);assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(d,op("listen","front").put("poseOverrides",new JSONObject()).put("poses",new JSONArray().put(new JSONObject().put("frame",0)))));
 }
 @Test public void kneelingReachesActualKneeFloorContactWithFixedLegLengths()throws Exception {
  JSONArray poses=SketchHumanPresets.create("kneel",120,30);JSONObject pose=SketchHumanAnimation.poseAt(poses,60);
  for(String build:new String[]{"slender","broad"}){SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(new JSONObject().put("build",build),pose);assertEquals(0,body.leftFoot[1],0);assertEquals(0,body.rightFoot[1],0);assertTrue("Right knee should meet the floor within its10px contour, actualY="+body.rightKnee[1],body.rightKnee[1]>=-14&&body.rightKnee[1]<=-6);assertTrue("Other knee stays raised",body.leftKnee[1]<-60);assertEquals(90,length(body.rightHip,body.rightKnee),1e-7);assertEquals(86,length(body.rightKnee,body.rightAnkle),1e-7);}
 }
 private double length(double[] a,double[] b){double n=0;for(int i=0;i<a.length;i++)n+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(n);}
}
