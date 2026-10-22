package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

/** Source-free forward-kinematic and native-pixel contracts; no prop/contact certification. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchFingerJointAnglesTest {
 static final String[] FINGERS={"index","middle","ring","little"};
 static JSONObject triple(String finger,double m,double p,double d)throws Exception{return new JSONObject().put(finger,new JSONArray().put(m).put(p).put(d));}
 static JSONObject pose(int frame)throws Exception{return new JSONObject().put("frame",frame).put("indexCurl",.82).put("middleCurl",.88).put("ringCurl",.93).put("littleCurl",.96).put("thumbCurl",.64).put("thumbOpposition",.7).put("spread",.06).put("wristFlex",0).put("wristTurn",0).put("interpolation","linear");}
 static JSONObject document(JSONArray poses)throws Exception{
  JSONObject c=SketchDocument.copy(SketchHandPartTestSupport.cases().getJSONObject(0));c.put("poses",poses);return SketchHandPartTestSupport.document(c,null);
 }
 static SketchHandGeometry.V[][] joints(JSONObject object,int frame)throws Exception{return SketchHandGeometry.joints(SketchHandAnimation.poseAt(object.getJSONObject("hand").getJSONArray("poses"),frame));}
 static SketchHandGeometry.V[][] at(JSONArray poses,int frame)throws Exception{return SketchHandGeometry.joints(SketchHandAnimation.poseAt(poses,frame));}
 static void point(SketchHandGeometry.V a,SketchHandGeometry.V b){assertEquals(a.x,b.x,1e-12);assertEquals(a.y,b.y,1e-12);assertEquals(a.z,b.z,1e-12);}
 static void pointBits(SketchHandGeometry.V a,SketchHandGeometry.V b){assertEquals(Double.doubleToLongBits(a.x),Double.doubleToLongBits(b.x));assertEquals(Double.doubleToLongBits(a.y),Double.doubleToLongBits(b.y));assertEquals(Double.doubleToLongBits(a.z),Double.doubleToLongBits(b.z));}
 static void expectedChain(SketchHandGeometry.V[] actual,int finger,double spread,double...degrees){
  double[] lengths=SketchHandGeometry.LENGTHS[finger];double fan=Math.toRadians(new double[]{-14,-3,7,19}[finger]*spread),angle=0;SketchHandGeometry.V previous=actual[0];
  for(int j=0;j<3;j++){angle+=Math.toRadians(degrees[j]);SketchHandGeometry.V next=previous.add(new SketchHandGeometry.V(Math.sin(fan)*Math.cos(angle),-Math.cos(fan)*Math.cos(angle),Math.sin(angle)).mul(lengths[j]));point(next,actual[j+1]);assertEquals(lengths[j],actual[j+1].sub(actual[j]).length(),1e-12);previous=next;}
 }
 @Test public void omissionRetainsAllPinnedLegacyGeometryAndNativePixels()throws Exception{
  new SketchHandPartPassTest().omittedCompleteHandsRetainPinned93048e7InkAndEveryNativePixel();
  JSONArray legacy=new JSONArray().put(pose(0)).put(pose(95));assertEquals("Legacy programmes keep their exact original nine-channel representation",9,SketchHandAnimation.poseAt(legacy,36).length);
 }
 @Test public void explicitNinetyDegreeMcpIsPerpendicularToPalmAxisInsteadOfLegacySixtyCeiling()throws Exception{
  JSONObject p=pose(0).put("fingerJointAngles",triple("index",90,80,35));JSONArray poses=new JSONArray().put(p);String before=SketchHandPartTestSupport.canonical(poses);JSONObject d=document(poses),object=SketchDocument.object(d,"hand_rig");SketchHandGeometry.V[][] chain=joints(object,0);expectedChain(chain[0],0,.06,90,80,35);assertEquals("90deg MCP proximal segment is perpendicular to projected palm axis",chain[0][0].y,chain[0][1].y,1e-12);assertEquals(SketchHandGeometry.LENGTHS[0][0],chain[0][1].z-chain[0][0].z,1e-12);
  JSONObject legacyPose=pose(0).put("indexCurl",1);SketchHandGeometry.V[][] old=at(new JSONArray().put(legacyPose),0);assertTrue("Legacy maximum still has a long projected first segment",old[0][0].y-old[0][1].y>.33);assertEquals(before,SketchHandPartTestSupport.canonical(poses));assertEquals(before,SketchHandPartTestSupport.canonical(object.getJSONObject("hand").getJSONArray("poses")));
  try(SketchFrameRenderer fitted=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d);SketchFrameRenderer legacy=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document(new JSONArray().put(legacyPose)))){Bitmap a=fitted.render(0,false),b=legacy.render(0,false);try{assertFalse("The new local geometry must reach actual native pixels",a.sameAs(b));}finally{a.recycle();b.recycle();}}
 }
 @Test public void eachExplicitTripleOverridesOnlyItsOwnCurlAndKeepsThumbAndSegmentLengths()throws Exception{
  JSONObject ordinary=pose(0),explicit=pose(0);JSONObject map=new JSONObject();for(int i=0;i<4;i++)map.put(FINGERS[i],new JSONArray().put(90+i*5).put(80+i*10).put(30+i*10));explicit.put("fingerJointAngles",map);document(new JSONArray().put(explicit));SketchHandGeometry.V[][] before=at(new JSONArray().put(ordinary),0),after=at(new JSONArray().put(explicit),0);
  for(int d=0;d<4;d++){point(before[d][0],after[d][0]);expectedChain(after[d],d,.06,90+d*5,80+d*10,30+d*10);}for(int j=0;j<4;j++)point(before[4][j],after[4][j]);
  for(int d=0;d<4;d++){JSONObject one=pose(0).put("fingerJointAngles",triple(FINGERS[d],95,70,40));document(new JSONArray().put(one));SketchHandGeometry.V[][] selected=at(new JSONArray().put(one),0);for(int other=0;other<5;other++)if(other!=d)for(int j=0;j<4;j++)point(before[other][j],selected[other][j]);}
 }
 @Test public void interpolationResolvesBothEndpointsBeforeEasingIncludingMixedExplicitAndLegacyKeys()throws Exception{
  for(String interpolation:new String[]{"linear","hold","smooth","ease_in_out","bezier"}){
   JSONObject a=pose(0).put("indexCurl",.2).put("interpolation",interpolation),b=pose(60).put("indexCurl",.8).put("fingerJointAngles",triple("index",100,110,70));if(interpolation.equals("bezier"))a.put("curve",new JSONArray().put(.2).put(.1).put(.8).put(.9));JSONArray keys=new JSONArray().put(a).put(b);document(keys);
   double t=SketchEasing.sample(interpolation,a.optJSONArray("curve"),.25);expectedChain(at(keys,15)[0],0,.06,12+(100-12)*t,18.4+(110-18.4)*t,9.6+(70-9.6)*t);expectedChain(at(keys,60)[0],0,.06,100,110,70);
   a=pose(0).put("fingerJointAngles",triple("index",90,80,35));b=pose(60).put("indexCurl",.5);keys=new JSONArray().put(a).put(b);document(keys);expectedChain(at(keys,30)[0],0,.06,60,63,29.5);
  }
 }
 @Test public void fingersNeverExplicitAndThumbKeepLegacyDoubleBitsAcrossEveryEasing()throws Exception{
  for(String interpolation:new String[]{"linear","hold","smooth","ease_in","ease_out","ease_in_out","bezier"}){
   JSONObject a=pose(0).put("interpolation",interpolation),b=pose(60);for(int d=0;d<4;d++){a.put(FINGERS[d]+"Curl",.123+d*.127);b.put(FINGERS[d]+"Curl",.789-d*.087);}if(interpolation.equals("bezier"))a.put("curve",new JSONArray().put(.23).put(.07).put(.76).put(.93));JSONArray old=new JSONArray().put(a).put(b),explicit=new JSONArray(old.toString());explicit.getJSONObject(0).put("fingerJointAngles",triple("index",90,80,35));document(explicit);
   for(int frame:new int[]{0,1,15,29,59,60,95}){SketchHandGeometry.V[][] legacy=at(old,frame),changed=at(explicit,frame);for(int d=1;d<5;d++)for(int j=0;j<4;j++)pointBits(legacy[d][j],changed[d][j]);}
  }
 }
 @Test public void explicitLegacyEquivalentAnglesKeepGeometryAndNativePixelsAndEveryViewUsesActualJoints()throws Exception{
  for(String view:new String[]{"front","back","left","right","high","low"}){
   JSONObject p=pose(0),equivalent=pose(0),map=new JSONObject();for(int d=0;d<4;d++){double curl=p.getDouble(FINGERS[d]+"Curl");map.put(FINGERS[d],new JSONArray().put(60*curl).put(92*curl).put(48*curl));}equivalent.put("fingerJointAngles",map);JSONObject c=SketchDocument.copy(SketchHandPartTestSupport.cases().getJSONObject(0));c.getJSONObject("settings").put("view",view);c.put("poses",new JSONArray().put(p));JSONObject old=SketchHandPartTestSupport.document(c,null);c.put("poses",new JSONArray().put(equivalent));JSONObject explicit=SketchHandPartTestSupport.document(c,null);
   try(SketchFrameRenderer left=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),old);SketchFrameRenderer right=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),explicit)){Bitmap a=left.render(36,false),b=right.render(36,false);try{assertTrue("Equivalent explicit angles retain native geometry/shading at "+view,a.sameAs(b));}finally{a.recycle();b.recycle();}}
  }
 }
 @Test public void strictJointMapValidationRejectsMalformedConstructorAndRetainedPayloads()throws Exception{
  List<Object> invalid=new ArrayList<>(Arrays.asList(JSONObject.NULL,true,false,1,"90,80,35",new JSONArray(),new JSONObject(),triple("thumb",90,80,35),new JSONObject().put("Index",new JSONArray().put(90).put(80).put(35))));
  for(Object bad:new Object[]{JSONObject.NULL,true,"90",new JSONObject(),new JSONArray(),new JSONArray().put(90).put(80),new JSONArray().put(90).put(80).put(35).put(0),new JSONArray().put("90").put(80).put(35),new JSONArray().put(true).put(80).put(35),new JSONArray().put(90).put(JSONObject.NULL).put(35),new JSONArray().put(-.001).put(80).put(35),new JSONArray().put(110.001).put(80).put(35),new JSONArray().put(90).put(120.001).put(35),new JSONArray().put(90).put(80).put(90.001)})invalid.add(new JSONObject().put("index",bad));
  for(Object bad:invalid){JSONObject p=pose(0).put("fingerJointAngles",bad);assertThrows("Constructor must reject strict map "+bad,Exception.class,()->document(new JSONArray().put(p)));JSONObject retained=document(new JSONArray().put(pose(0)));SketchDocument.object(retained,"hand_rig").getJSONObject("hand").getJSONArray("poses").getJSONObject(0).put("fingerJointAngles",bad);assertThrows("Retained validation must reject the same input",Exception.class,()->SketchDocument.validate(retained));}
  document(new JSONArray().put(pose(0).put("fingerJointAngles",triple("index",0,0,0))));document(new JSONArray().put(pose(0).put("fingerJointAngles",triple("little",110,120,90))));
  for(double nonfinite:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){JSONArray bad=new JSONArray("[90,80,35]"){@Override public Object get(int i)throws JSONException{return i==1?nonfinite:super.get(i);}};JSONArray keys=new JSONArray().put(pose(0).put("fingerJointAngles",new JSONObject().put("index",bad)));assertThrows("Strict native validator must reject nonfinite numeric degree values",Exception.class,()->SketchHandAnimation.validatePoses(keys,96));}
 }
 @Test public void explicitAnglesKeepSampledNativeCelAndNoPropSplitPixelsExact()throws Exception{
  JSONObject first=pose(0).put("fingerJointAngles",triple("index",90,80,35)),last=pose(95).put("fingerJointAngles",triple("index",105,110,70));JSONObject d=document(new JSONArray().put(first).put(last)),split=SketchHandPartTestSupport.split(d),object=SketchDocument.object(d,"hand_rig");String source=SketchHandPartTestSupport.canonical(d);JSONObject sampled=SketchHandAnimation.sampled(object,36);assertTrue("Sampling cannot discard resolved joint controls",sampled.getJSONObject("hand").getJSONArray("poses").getJSONObject(0).has("fingerJointAngles"));JSONObject frozen=SketchDocument.copy(d);SketchDocument.layer(frozen,"layer_1").put("objects",new JSONArray().put(sampled));SketchDocument.validate(frozen);
  try(SketchFrameRenderer full=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d);SketchFrameRenderer parts=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),split);SketchFrameRenderer cel=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),frozen)){for(int f:new int[]{0,36,95})same(full.render(f,false),parts.render(f,false),"Bent local geometry must preserve full-visibility split recomposition at"+f);same(full.render(36,false),cel.render(36,false),"Resolved sampled pose native pixels");}assertEquals(source,SketchHandPartTestSupport.canonical(d));
 }
 static void same(Bitmap a,Bitmap b,String message){try{assertTrue(message,a.sameAs(b));}finally{a.recycle();b.recycle();}}
}
