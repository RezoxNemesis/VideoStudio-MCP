package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchGripLinkTest {
 @Test public void nativeRopeBetweenTwoHandsRetainsExactEditableShapeKeys()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30);SketchEditOps.apply(d,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","actor_layer").put("name","Actor")));
  JSONObject actor=SketchHumanAnimationTest.op("tie","front").put("layerId","actor_layer");SketchEditOps.apply(d,actor);
  JSONObject op=new JSONObject().put("type","add_grip_link").put("layerId","layer_1").put("linkId","rope").put("from",new JSONObject().put("actorId","actor").put("hand","near")).put("to",new JSONObject().put("actorId","actor").put("hand","far")).put("settings",new JSONObject().put("sagPx",12));
  SketchEditOps.apply(d,op);SketchDocument.validate(d);JSONObject rope=SketchDocument.object(d,"rope_rope");assertTrue(rope.getJSONArray("shapeKeys").length()>2);assertEquals(120,rope.getInt("exposure"));
 }
 private JSONObject link()throws Exception{return new JSONObject().put("type","add_grip_link").put("layerId","layer_1").put("linkId","rope").put("from",new JSONObject().put("actorId","actor").put("hand","near")).put("to",new JSONObject().put("actorId","actor").put("hand","far")).put("settings",new JSONObject());}
 private JSONObject scene(int fps)throws Exception{JSONObject d=SketchHandAnimationTest.doc(fps);SketchEditOps.apply(d,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id","actors")));
  SketchEditOps.apply(d,SketchHumanAnimationTest.op("tie","front").put("layerId","actors"));return d;}
 @Test public void endpointsStayOnActualPinchesAtThirtyAndSixtyFpsUnderLayerTransforms()throws Exception {
  for(int fps:new int[]{30,60}){JSONObject d=scene(fps);SketchEditOps.apply(d,new JSONObject().put("type","set_transform").put("layerId","actors").put("transform",new JSONObject().put("rotation",3).put("x",.01)));
   SketchEditOps.apply(d,new JSONObject().put("type","set_transform").put("layerId","layer_1").put("transform",new JSONObject().put("scaleX",.9).put("scaleY",.9).put("rotation",-2)));
   SketchEditOps.apply(d,link());JSONObject rope=SketchDocument.object(d,"rope_rope"),actor=SketchDocument.object(d,"actor_rig");JSONArray keys=rope.getJSONArray("shapeKeys");assertEquals(d.getInt("frameCount"),keys.length());
   for(int f=0;f<keys.length();f++){JSONArray path=keys.getJSONObject(f).getJSONArray("commands");float[] ends={(float)(path.getJSONArray(0).getDouble(1)*1280),(float)(path.getJSONArray(0).getDouble(2)*720),(float)(path.getJSONArray(1).getDouble(3)*1280),(float)(path.getJSONArray(1).getDouble(4)*720)};
    SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(d,"layer_1"),f),1280,720).mapPoints(ends);float[] a=SketchGripLink.worldGrip(d,actor,"near",f),b=SketchGripLink.worldGrip(d,actor,"far",f);assertEquals(a[0],ends[0],.002);assertEquals(a[1],ends[1],.002);assertEquals(b[0],ends[2],.002);assertEquals(b[1],ends[3],.002);}
  }
 }
 @Test public void frontLayerAndMismatchedClocksRejectAtomically()throws Exception {
  for(String defect:new String[]{"front","clock"}){JSONObject d=scene(30),op=link();if(defect.equals("front"))op.put("layerId","actors");else SketchDocument.object(d,"actor_rig").getJSONObject("human").put("fps",60);String before=SketchDocument.canonical(d);assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(d,op));assertEquals(before,SketchDocument.canonical(d));}
 }
}
