package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchHumanGroundIntegrationTest {
 @Test public void actualOperationFollowsBridgeFloorAcrossViewsAndPreservesEditableLift()throws Exception {
  for(String view:new String[]{"front","left","right","back","high","low"}){
   JSONObject d=SketchHandAnimationTest.doc(24),settings=new JSONObject().put("view",view).put("size",.7).put("x",.5);
   JSONObject op=SketchHumanAnimationTest.op("listen",view).put("settings",settings).put("ground","bridge").put("poseOverrides",new JSONObject().put("rootY",-4).put("footLeftY",-3));SketchEditOps.apply(d,op);
   JSONObject actor=SketchDocument.object(d,"actor_rig"),p=SketchHumanAnimation.pose(actor,0),s=actor.getJSONObject("human").getJSONObject("settings"),placement=SketchHumanGround.placement(d,settings,"bridge");
   assertEquals(placement.getDouble("floorY"),s.getDouble("floorY"),0);assertEquals(-4,p.getDouble("rootY"),1e-9);
   for(String side:new String[]{"Left","Right"}){double[] q=SketchHumanFigure.project(s,new double[]{p.getDouble("foot"+side+"X"),p.getDouble("foot"+side+"Y"),p.getDouble("foot"+side+"Z")});double[] lift=SketchHumanFigure.project(s,new double[]{0,side.equals("Left")?-3:0,0});assertEquals(-q[0]/12+lift[1],q[1],1e-9);}
  }
 }
 @Test public void nonWalkingPresetGroundPlaneIsAppliedAfterStrengthRatherThanFlattened()throws Exception {
  JSONArray original=SketchHumanPresets.create("explain",96,24,new JSONObject().put("strength",.25));
  JSONArray slope=SketchHumanPresets.create("explain",96,24,new JSONObject().put("strength",.25).put("groundSlopeX",-.08).put("groundSlope",.12));
  JSONObject a=original.getJSONObject(4),b=slope.getJSONObject(4);for(String side:new String[]{"Left","Right"})assertEquals(a.getDouble("foot"+side+"Y")-.08*a.getDouble("foot"+side+"X")+.12*a.getDouble("foot"+side+"Z"),b.getDouble("foot"+side+"Y"),1e-9);
 }
 @Test public void ambiguousGroundAndFramingInputsRejectWithoutEditingDocument()throws Exception {
  for(JSONObject extra:new JSONObject[]{new JSONObject().put("fit",true),new JSONObject().put("motion",new JSONObject().put("groundSlope",0)),new JSONObject().put("poses",SketchHumanPresets.create("listen",96,24))}){
   JSONObject d=SketchHandAnimationTest.doc(24),op=SketchHumanAnimationTest.op("listen","front").put("ground","bridge");java.util.Iterator<String> keys=extra.keys();while(keys.hasNext()){String key=keys.next();op.put(key,extra.get(key));}String before=SketchDocument.canonical(d);assertThrows(Exception.class,()->SketchEditOps.apply(d,op));assertEquals(before,SketchDocument.canonical(d));
  }
 }
}
