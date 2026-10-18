package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchSolidCollisionTest {
 @Test public void nativeEditBuildsBothActorsWithoutSourcesAndKeepsEarlierArtwork()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id="impact_native";
  SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","blank_impact").put("width",1280).put("height",720).put("fps",30).put("frameCount",120));
  JSONObject request=new JSONObject().put("sketchId",id).put("operationId","construct_impact").put("expectedRevision",1).put("operations",new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","scene").put("name","Impact"))).put(op(new JSONObject()).put("layerId","scene")));
  SketchEditOps.execute(c,"sketch_edit",request);
  try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject d=s.get(id);assertEquals(2,d.getInt("revision"));assertEquals(0,d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());JSONArray objects=d.getJSONArray("layers").getJSONObject(1).getJSONArray("objects");boolean ball=false,box=false;for(int n=0;n<objects.length();n++){JSONObject o=objects.getJSONObject(n);assertEquals("path",o.getString("kind"));assertFalse(o.has("reference"));ball|=o.getString("id").startsWith("test_ball_");box|=o.getString("id").startsWith("test_box_");}assertTrue(ball&&box);SketchDocument.validate(d);}
 }
 @Test public void equalMassElasticImpactTransfersVelocityAndStopsUnderFriction()throws Exception{
  JSONObject d=doc(120,30);SketchSolidCollision.Motion m=new SketchSolidCollision.Motion(d,new JSONObject().put("ballMass",1).put("boxMass",1).put("restitution",1));
  assertEquals(0,m.ballVelocity,1e-9);assertEquals(m.approach,m.boxVelocity,1e-9);
  assertEquals(m.boxStart-m.halfWidth-m.radius,m.x(true,m.contact),1e-9);assertEquals(m.boxStart,m.x(false,m.contact),1e-9);
  assertEquals(m.boxVelocity*m.boxVelocity/(2*m.friction),m.displacement(m.boxVelocity,100),1e-9);assertEquals(m.displacement(m.boxVelocity,100),m.displacement(m.boxVelocity,1000),0);
 }
 @Test public void unequalMassImpactConservesMomentumAndMatchesRestitution()throws Exception{
  SketchSolidCollision.Motion m=new SketchSolidCollision.Motion(doc(120,30),new JSONObject());
  assertTrue(m.ballVelocity<0);assertTrue(m.boxVelocity>0);assertEquals(m.approach,m.ballVelocity+3*m.boxVelocity,1e-9);assertEquals(.7*m.approach,m.boxVelocity-m.ballVelocity,1e-9);
 }
 @Test public void actualCompiledPosesRemainGroundedAndSeparatedAcrossMassRatios()throws Exception{
  for(double mass:new double[]{.1,1,3,100}){
   JSONObject d=doc(120,30);JSONArray p=SketchSolidCollision.programs(d,new JSONObject().put("boxMass",mass));SketchSolidOps.Model ball=new SketchSolidOps.Model(d,p.getJSONObject(0)),box=new SketchSolidOps.Model(d,p.getJSONObject(1));
   for(int f=0;f<120;f++){double right=-1e9,left=1e9,low=1e9;for(double[] v:ball.vertices(f)){right=Math.max(right,v[0]);low=Math.min(low,v[1]);}for(double[] v:box.vertices(f)){left=Math.min(left,v[0]);low=Math.min(low,v[1]);}assertTrue("Interpenetration at mass "+mass+" frame "+f,right<=left+1e-6);assertEquals(0,low,1e-8);}
  }
 }
 @Test public void allSupportedClocksKeepExplicitContactAndBoundedSampling()throws Exception{
  for(int fps:new int[]{24,30,60}){JSONObject d=doc(240,fps);SketchSolidCollision.Motion motion=new SketchSolidCollision.Motion(d,new JSONObject());assertEquals(motion.boxStart-motion.halfWidth-motion.radius,motion.x(true,motion.contact),1e-9);JSONArray p=SketchSolidCollision.programs(d,new JSONObject());for(int n=0;n<2;n++)assertTrue(p.getJSONObject(n).getJSONArray("keys").length()<=64);assertEquals(fps,d.getInt("fps"));assertEquals(240,d.getInt("frameCount"));}
 }
 @Test public void invalidSettingsAndSecondActorFailureLeaveTheCallerUnchanged()throws Exception{
  JSONObject d=doc(120,30);String before=d.toString();for(JSONObject settings:new JSONObject[]{new JSONObject().put("url","reference.mp4"),new JSONObject().put("boxMass",0),new JSONObject().put("startX",100),new JSONObject().put("speed",20),new JSONObject().put("boxSize",new JSONArray("[170,400,150]")).put("camera",new JSONObject("{\"focal\":2200,\"distance\":1100,\"height\":310,\"horizon\":0.2}"))}){
   assertThrows(Exception.class,()->SketchSolidCollision.apply(d,op(settings)));assertEquals(before,d.toString());
  }
 }
 @Test public void secondActorFailureDoesNotLeakFirstActorOrPalette()throws Exception{
  JSONObject d=doc(120,30);d.put("paletteStyles",new JSONArray().put(new JSONObject().put("id","test_box_face0").put("name","Existing").put("color","#FF000000")));String before=d.toString();Exception error=assertThrows(Exception.class,()->SketchSolidCollision.apply(d,op(new JSONObject())));assertTrue(error.getMessage().contains("style already exists"));assertEquals(before,d.toString());
 }
 @Test public void replayAndUndoKeepOneAtomicSceneHistoryEntry()throws Exception{
  Context c=RuntimeEnvironment.getApplication();String id="impact_history";SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","blank_history").put("width",1280).put("height",720).put("fps",30).put("frameCount",120));JSONObject request=new JSONObject().put("sketchId",id).put("operationId","same_scene").put("expectedRevision",1).put("operations",new JSONArray().put(op(new JSONObject())));SketchEditOps.execute(c,"sketch_edit",request);SketchEditOps.execute(c,"sketch_edit",request);try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(2,store.get(id).getInt("revision"));}SketchEditOps.execute(c,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo_scene").put("expectedRevision",2));try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals(0,store.get(id).getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length());}
 }
 @Test public void fullDefaultSceneAt60FpsFitsExistingDrawingBudgets()throws Exception{
  JSONObject d=doc(240,60);SketchSolidCollision.apply(d,op(new JSONObject()));SketchDocument.validate(d);assertEquals(240,d.getInt("frameCount"));assertEquals(60,d.getInt("fps"));assertTrue(d.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<SketchDocument.MAX_BYTES);
 }
 @Test public void bakedMotionKeepsAnalyticDecelerationBetweenSparseKeys()throws Exception{
  JSONObject d=doc(240,60);SketchSolidCollision.Motion motion=new SketchSolidCollision.Motion(d,new JSONObject());JSONArray programs=SketchSolidCollision.programs(d,new JSONObject());for(int actor=0;actor<2;actor++){SketchSolidOps.Model model=new SketchSolidOps.Model(d,programs.getJSONObject(actor));for(int f=motion.contact+16;f<240;f++)assertEquals("Position must follow the actual friction curve, rather than jump velocity at sparse keys: actor "+actor+" frame "+f,motion.x(actor==0,f),model.pose(f)[0],1e-6);}
 }
 static JSONObject doc(int frames,int fps)throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","model").put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps));}
 static JSONObject op(JSONObject settings)throws Exception{return new JSONObject().put("type","add_solid_collision").put("layerId","layer_1").put("sceneId","test").put("settings",settings);}
}
