package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;

/** Strict native visible-owner partition; no invented artwork, tolerance masks or hidden-face redraw. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchHandPartPassTest {
 @Test public void omittedCompleteHandsRetainPinned93048e7InkAndEveryNativePixel()throws Exception{
  JSONObject baseline=SketchHandPartTestSupport.readBaseline();assertEquals(SketchHandPartTestSupport.BASE,baseline.getString("sourceCommit"));JSONArray expected=baseline.getJSONArray("cases"),cases=SketchHandPartTestSupport.cases();assertEquals(9,expected.length());assertEquals(9,cases.length());
  for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i),old=expected.getJSONObject(i),d=SketchHandPartTestSupport.document(c,null);assertEquals(c.getString("id"),old.getString("id"));assertEquals(SketchHandPartTestSupport.canonical(c),SketchHandPartTestSupport.canonical(old.getJSONObject("input")));assertFalse(SketchDocument.object(d,"hand_rig").getJSONObject("hand").has("parts"));
   try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){JSONArray frames=old.getJSONArray("frames");assertEquals(2,frames.length());for(int j=0;j<frames.length();j++){JSONObject frame=frames.getJSONObject(j);int f=frame.getInt("frame");assertEquals(frame.getString("inkSha256"),SketchHandPartTestSupport.inkHash(d,f));for(boolean paper:new boolean[]{false,true}){Bitmap image=renderer.render(f,paper);try{assertEquals(c.getString("id")+" frame"+f+" paper"+paper,frame.getString(paper?"paperPixelSha256":"transparentPixelSha256"),SketchHandPartTestSupport.pixels(image));}finally{image.recycle();}}}}
  }
 }
 @Test public void explicitAllRetainsCompleteGeometryBoundsPoseAndNativePixels()throws Exception{
  JSONArray cases=SketchHandPartTestSupport.cases();for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i),old=SketchHandPartTestSupport.document(c,null),explicit=SketchHandPartTestSupport.document(c,"all");JSONObject a=SketchDocument.object(old,"hand_rig"),b=SketchDocument.object(explicit,"hand_rig");assertEquals("all",b.getJSONObject("hand").getString("parts"));assertEquals(SketchHandPartTestSupport.canonical(a.getJSONObject("bounds")),SketchHandPartTestSupport.canonical(b.getJSONObject("bounds")));assertEquals(SketchHandPartTestSupport.canonical(a.getJSONObject("hand").getJSONArray("poses")),SketchHandPartTestSupport.canonical(b.getJSONObject("hand").getJSONArray("poses")));
   try(SketchFrameRenderer left=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),old);SketchFrameRenderer right=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),explicit)){for(int f:new int[]{0,36}){assertEquals(SketchHandPartTestSupport.inkHash(old,f),SketchHandPartTestSupport.inkHash(explicit,f));Bitmap x=left.render(f,false),y=right.render(f,false);try{assertTrue("Explicit all must keep native complete pixels",x.sameAs(y));}finally{x.recycle();y.recycle();}}}
  }
 }
 @Test public void strictConstructorAndRetainedPartsRejectUnknownTypesAndPartialSmooth()throws Exception{
  JSONObject c=SketchHandPartTestSupport.cases().getJSONObject(0);for(Object part:new Object[]{"all","palm_forearm","digits"}){JSONObject d=SketchHandPartTestSupport.document(c,part);assertEquals(part,SketchDocument.object(d,"hand_rig").getJSONObject("hand").get("parts"));}
  for(Object part:new Object[]{JSONObject.NULL,true,false,1,0,new JSONObject(),new JSONArray(),"","All","palm","fingers"}){assertThrows(Exception.class,()->SketchHandPartTestSupport.document(c,part));JSONObject retained=SketchHandPartTestSupport.document(c,null);SketchDocument.object(retained,"hand_rig").getJSONObject("hand").put("parts",part);assertThrows(Exception.class,()->SketchDocument.validate(retained));}
  JSONObject smooth=SketchHandPartTestSupport.cases().getJSONObject(7);for(String part:new String[]{"palm_forearm","digits"}){assertThrows(Exception.class,()->SketchHandPartTestSupport.document(smooth,part));JSONObject retained=SketchHandPartTestSupport.document(smooth,null);SketchDocument.object(retained,"hand_rig").getJSONObject("hand").put("parts",part);assertThrows(Exception.class,()->SketchDocument.validate(retained));}
 }
 @Test public void noPropSplitRecompositionIsPixelExactIncludingAntialiasingAndTransformedFrames()throws Exception{
  JSONArray cases=SketchHandPartTestSupport.cases();for(int i=0;i<7;i++){JSONObject all=SketchHandPartTestSupport.document(cases.getJSONObject(i),null),split=SketchHandPartTestSupport.split(all);for(String id:new String[]{"palm_forearm_hand","digits_hand"})assertEquals(SketchHandPartTestSupport.canonical(SketchDocument.object(all,"hand_rig").getJSONObject("bounds")),SketchHandPartTestSupport.canonical(SketchDocument.object(split,id).getJSONObject("bounds")));
   try(SketchFrameRenderer complete=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),all);SketchFrameRenderer parts=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),split)){for(int frame:new int[]{0,36})for(boolean paper:new boolean[]{false,true}){Bitmap x=complete.render(frame,paper),y=parts.render(frame,paper);try{assertTrue("Normal source-over rear+digits must exactly reconstruct complete hand, without AA cracks: "+cases.getJSONObject(i).getString("id")+" frame"+frame+" paper"+paper,x.sameAs(y));}finally{x.recycle();y.recycle();}}}
  }
 }
 @Test public void selectedPassesKeepOnlyTheirAlreadyVisibleOwnersAndOriginalShading()throws Exception{
  JSONArray cases=SketchHandPartTestSupport.cases();for(int c:new int[]{0,2,5}){JSONObject input=cases.getJSONObject(c),all=SketchHandPartTestSupport.document(input,null),rear=SketchHandPartTestSupport.document(input,"palm_forearm"),front=SketchHandPartTestSupport.document(input,"digits");JSONObject hand=SketchDocument.object(all,"hand_rig").getJSONObject("hand"),settings=hand.getJSONObject("settings");double[] pose=SketchHandAnimation.poseAt(hand.getJSONArray("poses"),0);Method method=SketchHandGeometry.class.getDeclaredMethod("celSurfaces",JSONObject.class,double[].class);method.setAccessible(true);@SuppressWarnings("unchecked") List<SketchHandGeometry.Face> faces=(List<SketchHandGeometry.Face>)method.invoke(null,settings,pose);faces.sort((a,b)->Double.compare(depth(b),depth(a)));
   try(SketchFrameRenderer ar=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),all);SketchFrameRenderer rr=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),rear);SketchFrameRenderer fr=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),front)){Bitmap a=ar.render(0,false),r=rr.render(0,false),f=fr.render(0,false);try{assertFalse("Rear cannot masquerade as complete",a.sameAs(r));assertFalse("Digits cannot masquerade as complete",a.sameAs(f));int rearSamples=0,digitSamples=0;
    for(int y=0;y<512;y++)for(int x=0;x<512;x++){if((a.getPixel(x,y)>>>24)==0){assertEquals("No revealed hidden skin outside complete output",0,r.getPixel(x,y)>>>24);assertEquals(0,f.getPixel(x,y)>>>24);}if(x%3!=0||y%3!=0||(a.getPixel(x,y)>>>24)!=255)continue;int owner=visibleOwner(faces,settings,x+.5,y+.5);if(owner<0||owner!=visibleOwner(faces,settings,x+.01,y+.01)||owner!=visibleOwner(faces,settings,x+.99,y+.99)||owner!=visibleOwner(faces,settings,x+.01,y+.99)||owner!=visibleOwner(faces,settings,x+.99,y+.01))continue;
     if(owner==0||owner==6){rearSamples++;assertEquals("Digit pass cannot reveal occluded digits through actual rear-owned skin",0,f.getPixel(x,y)>>>24);assertEquals("Rear retains original full-hand lighting/detail",a.getPixel(x,y),r.getPixel(x,y));}else{digitSamples++;assertEquals("Rear cannot reveal hidden palm through actual digit-owned skin",0,r.getPixel(x,y)>>>24);assertEquals("Digits retain original full-hand lighting/detail",a.getPixel(x,y),f.getPixel(x,y));}
    }assertTrue("Exercise visible rear owners",rearSamples>10);assertTrue("Exercise visible finger/thumb owners",digitSamples>10);
   }finally{a.recycle();r.recycle();f.recycle();}}
  }
 }
 private static double depth(SketchHandGeometry.Face face){double n=0;for(SketchHandGeometry.V v:face.p)n+=v.z;return n/face.p.length;}
 private static int visibleOwner(List<SketchHandGeometry.Face> faces,JSONObject settings,double px,double py)throws Exception{double unit=512/720d*112*settings.getDouble("size"),x=(px-512*settings.getDouble("centerX"))/unit,y=(py-512*settings.getDouble("centerY"))/unit;for(SketchHandGeometry.Face face:faces){if(face.material!=0||face.p[1].sub(face.p[0]).cross(face.p[2].sub(face.p[0])).unit().z<=.00001)continue;for(int i=1;i<face.p.length-1;i++){SketchHandGeometry.V a=face.p[0],b=face.p[i],c=face.p[i+1];double det=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);if(det==0)continue;double u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/det,v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/det;if(u>=0&&v>=0&&u+v<=1)return face.owner;}}return -1;}
}
