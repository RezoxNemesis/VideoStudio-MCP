package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
/** Actual public full-portrait composition. A rear curtain must not become foreground neck paint. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchRearHairOwnerOrderTest {
 static final String SKIN="#FFF1EEE8";
 static JSONObject settings(String view,String hair)throws Exception{return SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put("view",view).put("hairStyle",hair).put("hairColor","#FF202027").put("skinColor",SKIN).put("size",2).put("floorY",.9));}
 static JSONObject document(String view,String hair)throws Exception{JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","portrait").put("width",1280).put("height",1600).put("frameCount",1).put("background","#00000000"));SketchHumanFigure.draw(d,d.getJSONArray("layers").getJSONObject(0),"actor",settings(view,hair),new JSONObject(),0);return d;}
 static Bitmap render(JSONObject d)throws Exception{try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){return r.render(0,false);}}
 static JSONObject torso(JSONObject source)throws Exception{JSONObject d=SketchDocument.copy(source);JSONArray before=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"),after=new JSONArray();for(int i=0;i<before.length();i++){JSONObject o=before.getJSONObject(i);if(o.getString("id").startsWith("actor_anatomy_torso_")&&o.getBoolean("fill")&&SKIN.equals(o.getString("color")))after.put(o);}assertTrue("Use the owner's actual emitted neck/torso surface",after.length()>0);d.getJSONArray("layers").getJSONObject(0).put("objects",after);return d;}
 static double[] centre(String view)throws Exception{JSONObject s=settings(view,"long");double[] p=SketchHumanFigure.skeleton(s,new JSONObject()).camera.point(SketchHumanFigure.skeleton(s,new JSONObject()).head);return new double[]{640+p[0]*2,1440+p[1]*2};}
 static void evidence(String name,Bitmap b)throws Exception{String root=System.getenv("VIDEOSTUDIO_REAR_HAIR_EVIDENCE");if(root==null)return;File d=new File(root);assertTrue(d.mkdirs()||d.isDirectory());File f=new File(d,name);assertFalse("Preserve every native portrait trial",f.exists());try(FileOutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
 @Test public void rearHairBehindTheFrontOwnerMustPreserveTheActualNeckAndClaviclePixels()throws Exception{
  JSONObject shortDoc=document("front","short"),longDoc=document("front","long");Bitmap a=render(shortDoc),b=render(longDoc),mask=render(torso(longDoc));double[] c=centre("front");try{int samples=0,wrong=0;for(int dy=30;dy<=40;dy++)for(int dx=-5;dx<=5;dx++){int x=(int)Math.round(c[0]+dx*2),y=(int)Math.round(c[1]+dy*2);if(Color.alpha(mask.getPixel(x,y))<250)continue;samples++;if(a.getPixel(x,y)!=b.getPixel(x,y))wrong++;}evidence("front-short.png",a);evidence("front-long.png",b);evidence("front-actual-torso.png",mask);assertTrue(samples>60);assertEquals("Rear hair must not paint a beard over the owner's nearer neck/shoulder skin",0,wrong);}finally{a.recycle();b.recycle();mask.recycle();}
 }
 @Test public void rearHairInFrontOfTheBackOwnerMustRetainItsVisibleMass()throws Exception{
  JSONObject shortDoc=document("back","short"),longDoc=document("back","long");Bitmap a=render(shortDoc),b=render(longDoc),mask=render(torso(longDoc));double[] c=centre("back");try{int visible=0;for(int dy=30;dy<=40;dy++)for(int dx=-5;dx<=5;dx++){int x=(int)Math.round(c[0]+dx*2),y=(int)Math.round(c[1]+dy*2);if(Color.alpha(mask.getPixel(x,y))>=250&&Color.red(a.getPixel(x,y))>180&&Color.red(b.getPixel(x,y))<125&&Color.alpha(b.getPixel(x,y))>220)visible++;}evidence("back-short.png",a);evidence("back-long.png",b);assertTrue("A blanket move behind every torso would erase correctly nearer rear-view hair",visible>40);}finally{a.recycle();b.recycle();mask.recycle();}
 }
 @Test public void aWhiteHairPaletteCannotTurnTheRearCurtainIntoTheOwnersSkinRim()throws Exception{JSONObject[] d=new JSONObject[2];for(int j=0;j<2;j++){d[j]=SketchDocument.create(new JSONObject().put("sketchId","palette").put("width",1280).put("height",1600).put("frameCount",1));JSONObject s=settings("front",j==0?"short":"long").put("hairColor",SKIN);SketchHumanFigure.draw(d[j],d[j].getJSONArray("layers").getJSONObject(0),"actor",s,new JSONObject(),0);}assertEquals("Rear hair is a separate material even when its selected colour matches skin",SketchDocument.object(d[0],"actor_anatomy_contour_0").getJSONArray("commands").toString(),SketchDocument.object(d[1],"actor_anatomy_contour_0").getJSONArray("commands").toString());}
 @Test public void sideShoulderSkinNearerThanTheRearCurtainStaysVisibleBelowTheJaw()throws Exception{
  List<String> failures=new ArrayList<>();for(String view:new String[]{"left","right"}){JSONObject shortDoc=document(view,"short"),longDoc=document(view,"long");Bitmap a=render(shortDoc),b=render(longDoc),mask=render(torso(longDoc));double[] c=centre(view);try{int samples=0,wrong=0;for(int dy=38;dy<=41;dy++)for(int offset=4;offset<=6;offset++){int dx=view.equals("right")?-offset:offset,x=(int)Math.round(c[0]+dx*2),y=(int)Math.round(c[1]+dy*2);if(Color.alpha(mask.getPixel(x,y))<250)continue;samples++;if(a.getPixel(x,y)!=b.getPixel(x,y))wrong++;}assertTrue("The profile fixture must include actual solid owner shoulder",samples>4);evidence(view+"-short.png",a);evidence(view+"-long.png",b);if(wrong!=0)failures.add(view+":"+wrong);}finally{a.recycle();b.recycle();mask.recycle();}}assertTrue("The narrower rear curtain must not cover the nearer broad shoulder plane: "+failures,failures.isEmpty());
 }
}
