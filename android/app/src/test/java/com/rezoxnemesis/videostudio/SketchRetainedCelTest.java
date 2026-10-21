package com.rezoxnemesis.videostudio;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
/** A saved cel must not duplicate an entire multi-second joint/speech programme. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRetainedCelTest {
 @Test public void humanAndHandCelsKeepOneEditablePoseWithIdenticalNativePixels()throws Exception {
  for(boolean human:new boolean[]{true,false}){
   JSONObject d=SketchHandAnimationTest.doc(30),op=human?SketchHumanAnimationTest.op("explain","front"):SketchHandAnimationTest.op("front");
   if(human)op.put("speech",new JSONObject().put("transitionMs",60).put("cues",new JSONArray().put(new JSONObject().put("startMs",0).put("endMs",4000).put("shape","round"))));
   SketchEditOps.apply(d,op);JSONObject original=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0);String before=SketchDocument.canonical(original),kind=human?"human":"hand";
   for(int frame:new int[]{0,35,60,119}){
    JSONObject sampled=human?SketchHumanAnimation.sampled(original,frame):SketchHandAnimation.sampled(original,frame);
    assertEquals(1,sampled.getJSONObject(kind).getJSONArray("poses").length());assertFalse(sampled.getJSONObject(kind).has("speech"));assertEquals(frame,sampled.getJSONObject(kind).getInt("renderFrame"));
    JSONObject cel=SketchDocument.copy(d).put("frameCount",1);sampled.put("frame",0).put("exposure",1);cel.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray().put(sampled));SketchDocument.validate(cel);
    try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d);SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),cel)){Bitmap x=a.render(frame),y=b.render(0);try{assertTrue("Pixel-identical compact native cel at "+frame,x.sameAs(y));}finally{x.recycle();y.recycle();}}
   }assertEquals(before,SketchDocument.canonical(original));
  }
 }
}
