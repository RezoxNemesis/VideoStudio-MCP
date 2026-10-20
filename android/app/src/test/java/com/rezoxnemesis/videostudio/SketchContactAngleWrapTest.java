package com.rezoxnemesis.videostudio;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Recreates the arm spinning across the face in the preserved native banana contact study. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchContactAngleWrapTest {
 JSONObject source()throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","angle_source").put("width",576).put("height",1280).put("frameCount",96));JSONArray objects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(String id:new String[]{"upper","fore"})objects.put(SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","line").put("frame",0).put("exposure",96).put("points",new JSONArray(id.equals("upper")?"[[.386,.31,1],[.353,.426,1]]":"[[.353,.426,1],[.371,.594,1]]"))));return d;}
 JSONObject program(int bend,double authoredTurns,boolean lock)throws Exception{JSONObject rig=new JSONObject("{id:'actor',joints:[{id:'shoulder',x:.386,y:.31},{id:'elbow',parent:'shoulder',x:.353,y:.426},{id:'pad',parent:'elbow',x:.371,y:.594}],bindings:[{objectId:'upper',jointId:'shoulder'},{objectId:'fore',jointId:'elbow'}],poses:[{frame:0,interpolation:'hold',rotations:{}}],ik:[]}");rig.getJSONArray("poses").getJSONObject(0).getJSONObject("rotations").put("shoulder",authoredTurns);JSONObject contact=new JSONObject().put("hip","shoulder").put("knee","elbow").put("ankle","pad").put("start",42).put("end",58).put("x",.2907322290269653).put("y",.23341740219415902).put("bend",bend).put("blendInFrames",12).put("blendOutFrames",12);if(lock)contact.put("rotation",720);rig.getJSONArray("ik").put(contact);return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",96).put("fps",24).put("rigs",new JSONArray().put(rig));}
 JSONObject joint(JSONObject sample,int index)throws Exception{return sample.getJSONArray("joints").getJSONObject(index);}
 void check(int bend,double authored,boolean lock)throws Exception{JSONObject source=source(),program=program(bend,authored,lock);String sourceBefore=SketchDocument.canonical(source),programBefore=SketchDocument.canonical(program);JSONObject locked=SketchAnimationCompiler.sample(source,program,42);double hip=joint(locked,0).getDouble("rotation"),knee=joint(locked,1).getDouble("rotation")-hip;assertTrue("IK hip must choose the equivalent orientation nearest the authored pose: "+hip,Math.abs(hip-authored)<=180);assertTrue("IK bend must choose the equivalent local orientation nearest the authored pose: "+knee,Math.abs(knee)<=180);
  for(int f=30;f<=70;f++){JSONObject sample=SketchAnimationCompiler.sample(source,program,f);double h=joint(sample,0).getDouble("rotation"),k=joint(sample,1).getDouble("rotation")-h;assertTrue("Hip ease spins at frame "+f,Math.abs(h-authored)<=180);assertTrue("Bend ease spins at frame "+f,Math.abs(k)<=180);if(f>=42&&f<=58){assertEquals(.2907322290269653,joint(sample,2).getDouble("x"),1e-10);assertEquals(.23341740219415902,joint(sample,2).getDouble("y"),1e-10);if(lock)assertEquals(720,joint(sample,2).getDouble("rotation"),1e-10);}}
  JSONObject compiled=SketchAnimationCompiler.compile(source,program,"angle_output");JSONObject upper=SketchDocument.object(compiled.getJSONObject("document"),"upper");assertEquals(authored+(hip-authored)*.5,SketchDocument.transformAt(upper,36).getDouble("rotation"),1e-8);assertEquals(sourceBefore,SketchDocument.canonical(source));assertEquals(programBefore,SketchDocument.canonical(program));
 }
 @Test public void reachingHandDoesNotTakeAFullTurnDuringContactEase()throws Exception{check(-1,0,false);}
 @Test public void bothBendBranchesKeepTheirNearestEquivalentLocalAngles()throws Exception{check(1,0,false);}
 @Test public void authoredMultipleTurnsAreRetainedAsTheEaseBaseline()throws Exception{check(-1,720,false);}
 @Test public void explicitLockedWorldOrientationStillRetainsItsAuthoredTurns()throws Exception{check(-1,0,true);}
}
