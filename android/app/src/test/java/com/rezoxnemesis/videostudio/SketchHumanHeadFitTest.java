package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;

/** Identity geometry in one local construction volume, not independent 2D silhouette edits. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanHeadFitTest {
 @Test public void omittedProfilePreservesCapturedOriginalGeometryAndActualNativePixels()throws Exception{
  JSONObject baseline=SketchHeadFitTestSupport.read(new File(SketchHeadFitTestSupport.baseline(),"Baseline.json"));assertEquals(SketchHeadFitTestSupport.BASE,baseline.getString("sourceCommit"));JSONArray cases=baseline.getJSONArray("cases");assertEquals(10,cases.length());
  for(int i=0;i<cases.length();i++){JSONObject entry=cases.getJSONObject(i);assertFalse(entry.getJSONObject("settings").has("headProportions"));JSONObject doc=SketchHeadFitTestSupport.document(entry.getJSONObject("settings"),entry.getJSONObject("pose"));assertEquals(entry.getString("name"),entry.getString("layerSha256"),SketchHeadFitTestSupport.textSha(SketchHeadFitTestSupport.ink(doc)));Bitmap image=SketchHeadFitTestSupport.render(doc);try{assertEquals(entry.getString("name"),entry.getString("rawPixelsSha256"),SketchHeadFitTestSupport.pixels(image));}finally{image.recycle();}}
  assertFalse(SketchHumanFigure.settings(new JSONObject()).has("headProportions"));assertEquals(13,SketchHumanFigure.defaults().length());
 }
 @Test public void explicitIdentityProfileIsPixelExactAcrossYawAndPoseWithoutInjectingOtherFields()throws Exception{
  JSONArray cases=SketchHeadFitTestSupport.cases();for(int i=0;i<cases.length();i++){JSONObject entry=cases.getJSONObject(i),settings=entry.getJSONObject("settings"),pose=entry.getJSONObject("pose");JSONObject original=SketchHeadFitTestSupport.document(settings,pose),identity=SketchHeadFitTestSupport.document(SketchDocument.copy(settings).put("headProportions",SketchHeadFitTestSupport.profile(1,1,1)),pose);assertEquals(entry.getString("name"),SketchHeadFitTestSupport.ink(original),SketchHeadFitTestSupport.ink(identity));Bitmap a=SketchHeadFitTestSupport.render(original),b=SketchHeadFitTestSupport.render(identity);try{assertTrue(entry.getString("name"),a.sameAs(b));}finally{a.recycle();b.recycle();}}
  JSONObject partial=SketchHumanFigure.settings(SketchHeadFitTestSupport.settings(0,0).put("headProportions",new JSONObject().put("jawWidth",.85)));assertEquals(1,partial.getJSONObject("headProportions").length());assertEquals(.85,partial.getJSONObject("headProportions").getDouble("jawWidth"),0);
 }
 @Test public void profileIsStrictAnatomyOnlyAndEveryNumericScaleHasBoundedNoncoercingValidation()throws Exception{
  // First establish acceptance: rejection of every unknown key on old source is not sufficient.
  assertEquals(3,SketchHumanFigure.settings(SketchHeadFitTestSupport.settings(0,0).put("headProportions",SketchHeadFitTestSupport.profile(.75,1.25,1))).getJSONObject("headProportions").length());
  for(Object bad:new Object[]{JSONObject.NULL,true,"1",new JSONArray(),new JSONObject(),new JSONObject().put("unknown",1)})assertThrows(Exception.class,()->SketchHumanFigure.settings(SketchHeadFitTestSupport.settings(0,0).put("headProportions",bad)));
  for(String key:new String[]{"jawWidth","chinLength","eyeWidth"})for(Object bad:new Object[]{JSONObject.NULL,true,"1",.749,1.251,new JSONArray(),new JSONObject(),Double.NaN,Double.POSITIVE_INFINITY})assertThrows(Exception.class,()->SketchHumanFigure.settings(SketchHeadFitTestSupport.settings(0,0).put("headProportions",new JSONObject().put(key,bad))));
  for(String style:new String[]{"legacy","unknown"})assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("figureStyle",style).put("headProportions",SketchHeadFitTestSupport.profile(1,1,1))));assertThrows(Exception.class,()->SketchHumanFigure.settings(new JSONObject().put("headProportions",SketchHeadFitTestSupport.profile(1,1,1))));
 }
 @Test public void jawWidthChangesTheLowerMandibleAndChinLengthUsesTheMouthLandmark()throws Exception{
  JSONObject base=SketchHeadFitTestSupport.document(SketchHeadFitTestSupport.settings(0,0),new JSONObject());JSONObject narrow=SketchHeadFitTestSupport.document(SketchHeadFitTestSupport.settings(0,0).put("headProportions",new JSONObject().put("jawWidth",.8)),new JSONObject());assertTrue("Lower mandibular width must change geometrically",SketchHeadFitTestSupport.shellLowerWidth(narrow)<SketchHeadFitTestSupport.shellLowerWidth(base)*.9);
  JSONObject longer=SketchHeadFitTestSupport.document(SketchHeadFitTestSupport.settings(0,0).put("headProportions",new JSONObject().put("chinLength",1.2)),new JSONObject());double original=SketchHeadFitTestSupport.shellBottom(base),actual=SketchHeadFitTestSupport.shellBottom(longer);assertEquals("Length is local below resting mouthY14.3, not a whole-head or canvas scale",14.3+(original-14.3)*1.2,actual,1e-8);
  assertArrayEquals("Lower-face fit must not displace eye anchors",SketchHeadFitTestSupport.irisBounds(base),SketchHeadFitTestSupport.irisBounds(longer),0);
 }
 @Test public void eyeWidthIsARealSocketPlaneScaleInFrontThreeQuarterAndExactProfile()throws Exception{
  for(int yaw:new int[]{0,45,90})for(int pitch:new int[]{-20,0,20}){JSONObject original=SketchHeadFitTestSupport.document(SketchHeadFitTestSupport.settings(yaw,pitch),new JSONObject()),fitted=SketchHeadFitTestSupport.document(SketchHeadFitTestSupport.settings(yaw,pitch).put("headProportions",new JSONObject().put("eyeWidth",1.2)),new JSONObject());double[] a=SketchHeadFitTestSupport.irisBounds(original),b=SketchHeadFitTestSupport.irisBounds(fitted);assertEquals("The iris socket scales in both localx and localz; an x-only canvas fit fails at profile",1.2,(b[2]-b[0])/(a[2]-a[0]),1e-8);assertEquals("Eye anchor stays fixed at yaw"+yaw,(a[0]+a[2])/2,(b[0]+b[2])/2,1e-10);}
 }
 @Test public void onePersistentFitSurvivesContinuousYawWithoutChangingWorldSkeletonOrExpression()throws Exception{
  JSONObject pose=new JSONObject().put("bodyLean",13).put("headTilt",9).put("blink",.35).put("gazeX",.4).put("mouth",.2),profile=SketchHeadFitTestSupport.profile(.82,.94,.9);String initial=null;
  for(int yaw:new int[]{0,1,30,44,45,46,89,90,91,135,179,180,181,225,269,270,271,315,359,360}){
   JSONObject plain=SketchHeadFitTestSupport.settings(yaw,-15),fit=SketchDocument.copy(plain).put("headProportions",profile);SketchHumanFigure.Skeleton a=SketchHumanFigure.skeleton(plain,pose),b=SketchHumanFigure.skeleton(fit,pose);double[][] aa={a.head,a.neck,a.hip,a.nearShoulder,a.farShoulder,a.nearHand,a.farHand,a.leftFoot,a.rightFoot},bb={b.head,b.neck,b.hip,b.nearShoulder,b.farShoulder,b.nearHand,b.farHand,b.leftFoot,b.rightFoot};for(int j=0;j<aa.length;j++)assertArrayEquals("Head fitting must not move world joints",aa[j],bb[j],0);assertEquals(SketchDocument.canonical(a.controls),SketchDocument.canonical(b.controls));JSONObject doc=SketchHeadFitTestSupport.document(fit,pose);SketchDocument.validate(doc);String ink=SketchHeadFitTestSupport.ink(doc);if(yaw==0)initial=ink;if(yaw==360)assertEquals("One local volume must close its full revolution exactly",initial,ink);assertTrue(doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()>20);
  }
 }
}
