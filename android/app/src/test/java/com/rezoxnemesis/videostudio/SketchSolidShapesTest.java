package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Native geometry and authoring behavior; no reference assets or workspace geometry. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk=33,manifest=org.robolectric.annotation.Config.NONE)
public class SketchSolidShapesTest {
 @Test public void curvedSphereLightingUsesContinuousNativeMaterialGradients()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("shape","sphere").put("lighting",true);JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","smooth").put("width",1280).put("height",720).put("frameCount",61));SketchSolidOps.apply(d,SketchSolidOpsTest.request("smooth",p).getJSONArray("operations").getJSONObject(0));JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");int shaded=0;for(int n=0;n<objects.length();n++){JSONObject object=objects.getJSONObject(n);if(!object.getBoolean("fill")||object.getString("id").endsWith("_body"))continue;assertTrue("Curved lighting must not be stepped mesh patches",object.has("gradient"));assertFalse(object.has("styleId"));assertEquals(3,object.getJSONObject("gradient").getJSONArray("colors").length());shaded++;}assertTrue(shaded>0);SketchDocument.validate(d);
 }
 @Test public void rockingPyramidKeepsItsActiveFootAnchored()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("shape","pyramid").put("size",new JSONArray("[180,220,180]")).put("groundLock",true).put("rockingPivot",true);p.getJSONArray("keys").getJSONObject(0).put("roll",-20);p.getJSONArray("keys").getJSONObject(1).put("yaw",0).put("roll",20);
  SketchSolidOps.Model m=new SketchSolidOps.Model(new JSONObject().put("frameCount",61),p);assertEquals(5,m.mesh.vertices.length);
  for(int f:new int[]{0,15,45,60}){double[] low=null;for(double[] v:m.vertices(f))if(low==null||v[1]<low[1])low=v;assertEquals(0,low[1],1e-9);assertEquals("The active foot must stay at its neutral floor location",f<30?90:-90,low[0],1e-9);}
 }
 @Test public void nativeHeldCelsSpan1200FramesWithoutDuplicatingGeometry()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("holdLast",true).put("mergeHolds",true);p.getJSONArray("keys").getJSONObject(1).put("frame",1).put("yaw",0);JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","hold").put("width",1280).put("height",720).put("frameCount",1200));SketchSolidOps.apply(d,SketchSolidOpsTest.request("hold",p).getJSONArray("operations").getJSONObject(0));SketchDocument.validate(d);JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");assertTrue(objects.length()<8);for(int i=0;i<objects.length();i++)assertEquals(1200,objects.getJSONObject(i).getInt("exposure"));
 }
 @Test public void coneNarrowsToOneApex()throws Exception{
  SketchSolidOps.Model cone=new SketchSolidOps.Model(new JSONObject().put("frameCount",61),SketchSolidOpsTest.program().put("shape","cone"));int apex=0;for(double[] v:cone.mesh.vertices){if(v[1]==.5){apex++;assertEquals(0,v[0],0);assertEquals(0,v[2],0);}else assertEquals((.5-v[1])/2,Math.hypot(v[0],v[2]),1e-9);}assertEquals(1,apex);
 }
 @Test public void lightingStaysInWorldSpace()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("lighting",true);p.getJSONArray("keys").getJSONObject(1).put("yaw",180);JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","light").put("width",1280).put("height",720).put("frameCount",61));SketchSolidOps.apply(d,SketchSolidOpsTest.request("light",p).getJSONArray("operations").getJSONObject(0));assertEquals("#FFBD843A",SketchDocument.object(d,"crate_f0_shade1").getString("color"));assertEquals("#FF2C9087",SketchDocument.object(d,"crate_f60_shade4").getString("color"));SketchDocument.validate(d);
 }
 @Test public void softContactShadowUsesNativeRadialShadingAndAnEllipticalTransform()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("ground",true).put("softShadow",true);JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","shadow").put("width",1280).put("height",720).put("frameCount",61));SketchSolidOps.apply(d,SketchSolidOpsTest.request("shadow",p).getJSONArray("operations").getJSONObject(0));JSONObject shadow=SketchDocument.object(d,"crate_f0_shadow");assertFalse("Gradient shadows must not claim a flat linked palette style",shadow.has("styleId"));assertEquals("radial",shadow.getJSONObject("gradient").getString("type"));assertEquals(.22,shadow.getJSONObject("transform").getDouble("scaleY"),0);SketchDocument.validate(d);
 }
 @Test public void cylinderHasCircularCapsAndGroundContactAtEveryPose()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("shape","cylinder").put("size",new JSONArray("[140,220,140]")).put("groundLock",true).put("rollingRadius",70);for(int i=0;i<2;i++)p.getJSONArray("keys").getJSONObject(i).put("pitch",90).put("yaw",0);p.getJSONArray("keys").getJSONObject(1).put("x",260);
  SketchSolidOps.Model m=new SketchSolidOps.Model(new JSONObject().put("frameCount",61),p);assertTrue(m.mesh.vertices.length>=48);for(int f=0;f<=60;f++){double min=1e9,max=-min;for(double[] v:m.vertices(f)){min=Math.min(min,v[1]);max=Math.max(max,v[1]);}assertEquals("Rolling cylinder cannot float or penetrate",0,min,1e-9);assertTrue(max<=140.00001);}
 }
 @Test public void groundedConstraintRejectsConflictingFlightKeys()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("groundLock",true);p.getJSONArray("keys").getJSONObject(0).put("flightHeight",20);Exception error=assertThrows(Exception.class,()->new SketchSolidOps.Model(new JSONObject().put("frameCount",61),p));assertTrue(error.getMessage().contains("Ground lock"));
 }
 @Test public void sphereRollUsesTravelDistanceAndKeepsAnActualRoundSurface()throws Exception{
  JSONObject p=SketchSolidOpsTest.program().put("shape","sphere").put("size",new JSONArray("[160,160,160]")).put("rollingRadius",80);
  p.getJSONArray("keys").getJSONObject(1).put("x",Math.PI*80).put("yaw",0);
  SketchSolidOps.Model model=new SketchSolidOps.Model(new JSONObject().put("frameCount",61),p);assertEquals(-180,model.pose(60)[5],1e-9);assertEquals(-90,model.pose(30)[5],1e-9);
  double[][] v=model.vertices(0);assertTrue("Sphere needs a shared curved mesh, not a recoloured box",v.length>100);double min=1e9;for(double[] a:v)min=Math.min(min,a[1]);assertEquals(0,min,1e-9);
  for(double[] a:v)assertEquals(80,Math.sqrt(a[0]*a[0]+(a[1]-80)*(a[1]-80)+a[2]*a[2]),1e-8);
  JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","sphere").put("width",1280).put("height",720).put("frameCount",61));SketchSolidOps.apply(d,SketchSolidOpsTest.request("sphere",p).getJSONArray("operations").getJSONObject(0));SketchDocument.validate(d);
  assertTrue(d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()<900);
 }
}
