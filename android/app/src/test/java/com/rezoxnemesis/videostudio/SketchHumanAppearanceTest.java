package com.rezoxnemesis.videostudio;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHumanAppearanceTest {
 @Test public void illustratedFacesAreAvailableThroughTheActualNativeAuthoringOperation()throws Exception {
  JSONObject plain=SketchHandAnimationTest.doc(30);SketchEditOps.apply(plain,SketchHumanAnimationTest.op("explain","front"));for(String style:new String[]{"angular","soft"}){JSONObject d=SketchHandAnimationTest.doc(30);SketchEditOps.apply(d,SketchHumanAnimationTest.op("explain","front").put("settings",new JSONObject().put("faceStyle",style)));assertEquals(style,SketchDocument.object(d,"actor_rig").getJSONObject("human").getJSONObject("settings").getString("faceStyle"));try(SketchFrameRenderer a=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),plain);SketchFrameRenderer b=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){Bitmap x=a.render(60),y=b.render(60);try{assertFalse(x.sameAs(y));}finally{x.recycle();y.recycle();}}}
 }
 @Test public void softMaterialsChangeNativePixelsAndLightDirectionWithoutChangingAnyGeometry()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(30);SketchEditOps.apply(d,SketchHumanAnimationTest.op("explain","front").put("settings",new JSONObject().put("shading","soft").put("lightAngle",135)));JSONObject actor=SketchDocument.object(d,"actor_rig"),soft=SketchHumanAnimation.frameDocument(actor,1280,720,60);JSONObject other=SketchDocument.copy(actor);other.getJSONObject("human").getJSONObject("settings").put("lightAngle",-45);JSONObject reverse=SketchHumanAnimation.frameDocument(other,1280,720,60);JSONArray a=soft.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"),b=reverse.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");int materials=0;for(int i=0;i<a.length();i++){assertEquals(a.getJSONObject(i).getJSONArray("commands").toString(),b.getJSONObject(i).getJSONArray("commands").toString());if(a.getJSONObject(i).has("gradient"))materials++;}assertTrue("Native body materials need actual gradients",materials>=8);SketchDocument.validate(soft);SketchDocument.validate(reverse);
  try(SketchFrameRenderer x=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),soft);SketchFrameRenderer y=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),reverse)){Bitmap first=x.render(0),second=y.render(0);try{assertFalse("Light direction must alter actual native shading",first.sameAs(second));}finally{first.recycle();second.recycle();}}
 }
 @Test public void connectedCoatAndSleeveCapsShareOneLightingFieldRatherThanSeparateRoundHighlights()throws Exception {
  JSONObject d=SketchHandAnimationTest.doc(24);SketchEditOps.apply(d,SketchHumanAnimationTest.op("explain","front").put("settings",new JSONObject().put("shading","soft")));JSONObject drawn=SketchHumanAnimation.frameDocument(SketchDocument.object(d,"actor_rig"),1280,720,48);JSONArray objects=drawn.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");String first=null;int pieces=0;
  for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.has("gradient")&&o.getString("color").equals("#FF526B78")){String field=SketchDocument.canonical(o.getJSONObject("gradient"));if(first==null)first=field;else assertEquals("Connected coat, sleeves and joint coverage need one continuous material light field",first,field);pieces++;}}assertTrue(pieces>=6);
 }
 @Test public void integratedNativeFaceAndMaterialsProduceSixViewReviewEvidence()throws Exception {
  String root=System.getenv("VIDEOSTUDIO_APPEARANCE_EVIDENCE");org.junit.Assume.assumeTrue(root!=null);JSONArray records=new JSONArray();
  for(String style:new String[]{"angular","soft"})for(String view:new String[]{"front","left","right","back","high","low"}){
   JSONObject d=SketchHandAnimationTest.doc(24);SketchEditOps.apply(d,SketchHumanAnimationTest.op("explain",view).put("fit",true).put("settings",new JSONObject().put("view",view).put("faceStyle",style).put("shading","soft").put("size",1.3).put("hairStyle",style.equals("soft")?"long":"short")));
   java.io.File base=new java.io.File(root,style+"-"+view);base.mkdirs();java.nio.file.Files.write(new java.io.File(base,"source-document.json").toPath(),d.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){for(int f:new int[]{0,48,72}){Bitmap b=r.render(f);java.io.File out=new java.io.File(base,"frame-"+f+".png");try{SketchActionFilmTest.png(out,b);}finally{b.recycle();}records.put(new JSONObject().put("style",style).put("view",view).put("frame",f).put("sha256",SketchProjectStore.fileHash(out)).put("technical","PASS").put("visual","UNREVIEWED"));}}
  }
  java.nio.file.Files.write(new java.io.File(root,"index.json").toPath(),new JSONObject().put("renderer","VideoStudio native Android Canvas, workspace Robolectric").put("frames",records).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }

}
