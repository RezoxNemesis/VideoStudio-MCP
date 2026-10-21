package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRetainedInteractionTest {
 @Test public void actualHumanCoatAndHandPalmCanBeSelectedByInteriorTaps()throws Exception {
  JSONObject human=SketchHandAnimationTest.doc(30);SketchEditOps.apply(human,SketchHumanAnimationTest.op("listen","front"));assertEquals("actor_rig",SketchFrameRenderer.hitTest(human,0,.5,.55,.001));
  JSONObject hand=SketchHandAnimationTest.doc(30);SketchEditOps.apply(hand,SketchHandAnimationTest.op("front"));assertEquals("hand_rig",SketchFrameRenderer.hitTest(hand,0,.5,.55,.001));
 }
 @Test public void nativeHandConstructorRejectsAnInterruptedWorkerWithoutAppendingArtwork()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30);String before=SketchDocument.canonical(d);Thread.currentThread().interrupt();try{assertThrows(InterruptedException.class,()->SketchEditOps.apply(d,SketchHandAnimationTest.op("front")));}finally{Thread.interrupted();}assertEquals(before,SketchDocument.canonical(d));
 }
 @Test public void handStrokeCannotLeaveCanvasThroughWidthOrDestinationTransform()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30),op=SketchHandAnimationTest.op("front");op.getJSONObject("settings").put("centerY",.65);SketchEditOps.apply(d,op);
  JSONObject thick=SketchDocument.copy(d);SketchDocument.object(thick,"hand_rig").put("width",.2);assertThrows(IllegalArgumentException.class,()->SketchDocument.validate(thick));
  // Ordinary thin-outline projects remain readable with their original logical bounds.
  SketchDocument.validate(d);JSONObject transformed=SketchDocument.copy(d);SketchDocument.layer(transformed,"layer_1").getJSONObject("transform").put("y",.04);SketchDocument.object(transformed,"hand_rig").put("width",.15);assertThrows(IllegalArgumentException.class,()->SketchDocument.validate(transformed));
 }
}
