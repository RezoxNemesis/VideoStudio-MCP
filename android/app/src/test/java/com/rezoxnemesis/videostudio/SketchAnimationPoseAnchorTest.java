package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Authored keys are exact anchors; quarter-pixel reduction applies between them. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnimationPoseAnchorTest {
 private JSONObject source()throws Exception {
  JSONObject s=SketchDocument.create(new JSONObject().put("sketchId","pose_anchor_source").put("width",720).put("height",1280).put("fps",24).put("frameCount",96));
  JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id","head").put("kind","rect").put("frame",0).put("exposure",96).put("color","#FF353C4B").put("fill",true).put("bounds",new JSONObject().put("x",.21).put("y",.23).put("width",.23).put("height",.19)));
  s.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(object);SketchDocument.validate(s);return s;
 }
 private JSONObject program()throws Exception {
  JSONArray poses=new JSONArray();for(double[] row:new double[][]{{0,0},{16,0},{36,2.2},{60,2.2},{84,0},{95,0}})poses.put(new JSONObject().put("frame",(int)row[0]).put("interpolation","ease_in_out").put("rotations",new JSONObject().put("neck",row[1])));
  JSONObject rig=new JSONObject().put("id","neck_rig").put("joints",new JSONArray().put(new JSONObject().put("id","neck").put("x",235.5/720).put("y",501d/1280))).put("bindings",new JSONArray().put(new JSONObject().put("objectId","head").put("jointId","neck"))).put("poses",poses);
  return new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("fps",24).put("frameCount",96).put("rigs",new JSONArray().put(rig));
 }
 private JSONObject exact(JSONObject s,JSONObject p,int frame)throws Exception {return SketchDocument.object(SketchAnimationCompiler.preview(s,p,frame,"preview_anchor").getJSONObject("document"),"head").getJSONObject("transform");}
 private void exactTransform(JSONObject a,JSONObject b)throws Exception {for(String k:new String[]{"x","y","scaleX","scaleY","rotation","anchorX","anchorY","opacity"})assertEquals("Authored "+k+" must be exact",a.getDouble(k),b.getDouble(k),0);}
 @Test public void authoredRigPosesIncludingReturnToNeutralAreExactAfterReduction()throws Exception {
  JSONObject s=source(),p=program();String original=SketchDocument.canonical(s);JSONObject result=SketchAnimationCompiler.compile(s,p,"pose_anchor_output"),d=result.getJSONObject("document"),head=SketchDocument.object(d,"head");
  for(int f:new int[]{0,16,36,60,84,95})exactTransform(exact(s,p,f),SketchDocument.transformAt(head,f));assertEquals(original,SketchDocument.canonical(s));assertTrue(head.getJSONArray("keys").length()<96);assertTrue(result.getJSONObject("report").getDouble("maxBakedTransformApproximationPixels")<=.2500001);
 }
 @Test public void explicitNeutralFramesRenderExactlyLikeNativeSingleFramePreview()throws Exception {
  JSONObject s=source(),p=program(),d=SketchAnimationCompiler.compile(s,p,"pose_anchor_output").getJSONObject("document");
  try(SketchFrameRenderer full=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){for(int f:new int[]{16,36,60,84,85,95}){JSONObject preview=SketchAnimationCompiler.preview(s,p,f,"pose_anchor_preview").getJSONObject("document");try(SketchFrameRenderer single=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),preview)){Bitmap a=full.render(f,false),b=single.render(0,false);try{assertTrue("Native authored/resting pixels exact at"+f,a.sameAs(b));}finally{a.recycle();b.recycle();}}}}
 }
 @Test public void boundSourceKeysRetainExactSmallOpacityAndRotationValues()throws Exception {
  JSONObject s=source(),p=program(),head=SketchDocument.object(s,"head");JSONArray keys=new JSONArray();for(int f:new int[]{0,43,51,95}){JSONObject transform=SketchDocument.transform().put("opacity",f==43?.43015:.43).put("rotation",f==43?.0003:0);keys.put(new JSONObject().put("frame",f).put("interpolation","hold").put("transform",transform));}head.put("keys",keys);SketchDocument.validate(s);
  JSONObject baked=SketchDocument.object(SketchAnimationCompiler.compile(s,p,"pose_anchor_output").getJSONObject("document"),"head");for(int f:new int[]{0,43,51,95})exactTransform(exact(s,p,f),SketchDocument.transformAt(baked,f));
 }
 @Test public void cameraSelectedLayerAuthoredOpacityKeysAreNotRoundedAway()throws Exception {
  JSONObject s=source(),p=program(),layer=s.getJSONArray("layers").getJSONObject(0);p.put("rigs",new JSONArray()).put("cameraLayerIds",new JSONArray().put("layer_1")).put("camera",new JSONArray().put(new JSONObject().put("frame",0).put("zoom",1.2)).put(new JSONObject().put("frame",95).put("zoom",1.2)));
  JSONArray keys=new JSONArray();for(int f:new int[]{0,43,51,95})keys.put(new JSONObject().put("frame",f).put("interpolation","hold").put("transform",SketchDocument.transform().put("opacity",f==43?.43015:.43)));layer.put("keys",keys);SketchDocument.validate(s);
  JSONObject baked=SketchAnimationCompiler.compile(s,p,"pose_anchor_output").getJSONObject("document").getJSONArray("layers").getJSONObject(0);for(int f:new int[]{0,43,51,95}){JSONObject exact=SketchAnimationCompiler.preview(s,p,f,"pose_anchor_preview").getJSONObject("document").getJSONArray("layers").getJSONObject(0);exactTransform(exact.getJSONObject("transform"),SketchDocument.transformAt(baked,f));}
 }
 @Test public void contactBlendAndLockBoundariesRetainTheExactNativeConstraintSamples()throws Exception {
  JSONObject s=source(),p=program(),rig=p.getJSONArray("rigs").getJSONObject(0);
  rig.put("joints",new JSONArray().put(new JSONObject().put("id","hip").put("x",.4).put("y",.3)).put(new JSONObject().put("id","knee").put("parent","hip").put("x",.4).put("y",.5)).put(new JSONObject().put("id","foot").put("parent","knee").put("x",.4).put("y",.7)));
  rig.put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("interpolation","ease_in_out")).put(new JSONObject().put("frame",95).put("rootX",.015)));
  rig.getJSONArray("bindings").getJSONObject(0).put("jointId","foot");rig.put("ik",new JSONArray().put(new JSONObject().put("hip","hip").put("knee","knee").put("ankle","foot").put("start",36).put("end",60).put("blendInFrames",8).put("blendOutFrames",6).put("x",.43).put("y",.6).put("bend",1).put("rotation",15)));
  JSONObject baked=SketchDocument.object(SketchAnimationCompiler.compile(s,p,"pose_anchor_output").getJSONObject("document"),"head");for(int f:new int[]{27,28,35,36,59,60,65,66})exactTransform(exact(s,p,f),SketchDocument.transformAt(baked,f));
 }
 @Test public void zeroBlendContactReleasesOnTheFirstFreeFrameEvenBelowPixelTolerance()throws Exception {
  JSONObject s=source(),p=program(),rig=p.getJSONArray("rigs").getJSONObject(0);
  rig.put("joints",new JSONArray().put(new JSONObject().put("id","hip").put("x",.4).put("y",.3)).put(new JSONObject().put("id","knee").put("parent","hip").put("x",.4).put("y",.5)).put(new JSONObject().put("id","foot").put("parent","knee").put("x",.4).put("y",.7)));
  rig.put("poses",new JSONArray().put(new JSONObject().put("frame",0)).put(new JSONObject().put("frame",95)));
  rig.getJSONArray("bindings").getJSONObject(0).put("jointId","foot");rig.put("ik",new JSONArray().put(new JSONObject().put("hip","hip").put("knee","knee").put("ankle","foot").put("start",36).put("end",60).put("x",.40001).put("y",.69999).put("bend",1).put("rotation",0)));
  JSONObject baked=SketchDocument.object(SketchAnimationCompiler.compile(s,p,"pose_anchor_output").getJSONObject("document"),"head");for(int f:new int[]{60,61,62})exactTransform(exact(s,p,f),SketchDocument.transformAt(baked,f));
 }
}
