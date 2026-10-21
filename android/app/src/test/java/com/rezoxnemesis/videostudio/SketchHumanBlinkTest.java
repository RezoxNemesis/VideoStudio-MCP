package com.rezoxnemesis.videostudio;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanBlinkTest {
 @Test public void naturalBlinkClosesAtPhysicalTimeWithoutAddingBodyKeysOrChangingActing()throws Exception {
  for(int fps:new int[]{24,30,60}){JSONObject d=SketchHandAnimationTest.doc(fps);SketchEditOps.apply(d,SketchHumanAnimationTest.op("listen","front"));JSONObject a=SketchDocument.object(d,"actor_rig");assertEquals(1,SketchHumanAnimation.pose(a,(int)Math.round(1.9*fps)).getDouble("blink"),1e-8);assertEquals(0,SketchHumanAnimation.pose(a,(int)Math.round(2.2*fps)).getDouble("blink"),1e-8);assertEquals(9,a.getJSONObject("human").getJSONArray("poses").length());}
 }
 @Test public void blinkCanBeDisabledOrTimedExplicitlyAndFrozenCelsKeepItsPixels()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30);SketchEditOps.apply(d,SketchHumanAnimationTest.op("explain","front").put("blinkTrack",new JSONObject().put("phaseMs",900)));JSONObject actor=SketchDocument.object(d,"actor_rig");assertEquals(1,SketchHumanAnimation.pose(actor,30).getDouble("blink"),0);JSONObject frozen=SketchHumanAnimation.sampled(actor,30);assertFalse(frozen.getJSONObject("human").has("blinkTrack"));frozen.put("frame",0).put("exposure",1);JSONObject cel=SketchDocument.copy(d).put("frameCount",1);cel.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray().put(frozen));try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d);SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),cel)){Bitmap x=a.render(30),y=b.render(0);try{assertTrue(x.sameAs(y));}finally{x.recycle();y.recycle();}}
  JSONObject off=SketchHandAnimationTest.doc(30);SketchEditOps.apply(off,SketchHumanAnimationTest.op("explain","front").put("blinkTrack",false));assertEquals(0,SketchHumanAnimation.pose(SketchDocument.object(off,"actor_rig"),57).getDouble("blink"),0);
 }
 @Test public void invalidBlinkControlsRejectAtomically()throws Exception {
  for(JSONObject bad:new JSONObject[]{new JSONObject().put("intervalMs",100),new JSONObject().put("closeMs",0),new JSONObject().put("phaseMs",-1),new JSONObject().put("extra",1)}){JSONObject d=SketchHandAnimationTest.doc(30);String before=SketchDocument.canonical(d);assertThrows(IllegalArgumentException.class,()->SketchEditOps.apply(d,SketchHumanAnimationTest.op("listen","front").put("blinkTrack",bad)));assertEquals(before,SketchDocument.canonical(d));}
 }
}
