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
/** Actual emitted head ink against the owner's actual native torso fill; not AI/likeness evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHeadNeckOcclusionTest {
 private static final String SKIN="#FFF1EEE8",HEAD_INK="#FF383B40";
 private static final class Fixture implements AutoCloseable {
  final JSONObject settings,pose,document,standalone,integrated;final SketchHumanFigure.Skeleton skeleton;final Bitmap oldInk,newInk,body;
  Fixture(String view,double tilt,double lean)throws Exception {
   settings=SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put("view",view).put("skinColor",SKIN).put("hairStyle","short"));pose=new JSONObject().put("headTilt",tilt).put("bodyLean",lean);skeleton=SketchHumanFigure.skeleton(settings,pose);
   document=SketchDocument.create(new JSONObject().put("sketchId","neck_fixture").put("width",512).put("height",512).put("frameCount",1));JSONObject layer=document.getJSONArray("layers").getJSONObject(0);SketchHumanAnatomy.draw(layer,"actor",settings,skeleton);JSONArray objects=layer.getJSONArray("objects");JSONArray head=new JSONArray(),bodyFill=new JSONArray();
   for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(object.getString("id").startsWith("actor_anatomy_head_")&&HEAD_INK.equals(object.getString("color")))head.put(SketchDocument.copy(object));if(object.getString("id").startsWith("actor_anatomy_torso_")&&object.getBoolean("fill")&&SKIN.equals(object.getString("color")))bodyFill.put(SketchDocument.copy(object));}
   assertTrue("Use the actual emitted continuous neck/torso surface",bodyFill.length()>0);integrated=with(head);JSONObject isolated=SketchDocument.copy(layer).put("objects",new JSONArray());SketchAnatomyHead.draw(isolated,"actor",settings,skeleton);JSONArray original=new JSONArray();objects=isolated.getJSONArray("objects");for(int i=0;i<objects.length();i++)if(HEAD_INK.equals(objects.getJSONObject(i).getString("color")))original.put(SketchDocument.copy(objects.getJSONObject(i)));standalone=with(original);oldInk=render(standalone);newInk=render(integrated);body=bodyMask(bodyFill);
  }
  private Bitmap bodyMask(JSONArray objects)throws Exception {Bitmap bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);fill.setColor(Color.BLACK);double[] centre=skeleton.camera.point(skeleton.head);Matrix mapping=new Matrix();mapping.setTranslate((float)(-10240-centre[0]),(float)(-5760-centre[1]));mapping.postScale(6.5f,6.5f);mapping.postTranslate(256,232);for(int i=0;i<objects.length();i++){Path path=SketchFrameRenderer.path(objects.getJSONObject(i).getJSONArray("commands"),20480,11520);path.transform(mapping);canvas.drawPath(path,fill);}return bitmap;}
  private JSONObject with(JSONArray objects)throws Exception {JSONObject d=SketchDocument.copy(document);JSONArray chosen=new JSONArray();double[] centre=skeleton.camera.point(skeleton.head);for(int i=0;i<objects.length();i++){JSONObject object=SketchDocument.copy(objects.getJSONObject(i));JSONArray commands=object.getJSONArray("commands");for(int j=0;j<commands.length();j++){JSONArray command=commands.getJSONArray(j);for(int k=1;k<command.length();k+=2){double x=(command.getDouble(k)*1280-640)*16-centre[0],y=(command.getDouble(k+1)*720-360)*16-centre[1];command.put(k,(256+x*6.5)/512);command.put(k+1,(232+y*6.5)/512);}}if(object.has("clipOut")){JSONArray rings=object.getJSONArray("clipOut");for(int r=0;r<rings.length();r++){JSONArray ring=rings.getJSONArray(r);for(int q=0;q<ring.length();q++){JSONArray point=ring.getJSONArray(q);double x=(point.getDouble(0)*1280-640)*16-centre[0],y=(point.getDouble(1)*720-360)*16-centre[1];point.put(0,(256+x*6.5)/512).put(1,(232+y*6.5)/512);}}}object.put("width",object.getDouble("width")*720*6.5/512);chosen.put(object);}d.getJSONArray("layers").getJSONObject(0).put("objects",chosen);return d;}
  public void close(){oldInk.recycle();newInk.recycle();body.recycle();}
 }
 private static Bitmap render(JSONObject document)throws Exception {try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),document)){return renderer.render(0,false);}}
 private static void evidence(String name,Bitmap bitmap)throws Exception {String path=System.getenv("VIDEOSTUDIO_HEAD_NECK_EVIDENCE");if(path==null)return;File directory=new File(path);assertTrue(directory.mkdirs()||directory.isDirectory());File target=new File(directory,name);assertFalse("Keep failed native screenshots",target.exists());try(FileOutputStream out=new FileOutputStream(target)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
 @Test public void nativeProfilesDoNotInkCranialCapAcrossTheActualNeckInterior()throws Exception {
  List<String> failures=new ArrayList<>();for(String view:new String[]{"left","right"})try(Fixture f=new Fixture(view,0,0)){
   int previous=0,remaining=0;for(int y=382;y<=399;y++)for(int x=233;x<=306;x++){if((f.body.getPixel(x,y)>>>24)<250)continue;if((f.oldInk.getPixel(x,y)>>>24)>128)previous++;if((f.newInk.getPixel(x,y)>>>24)>128)remaining++;}
   assertTrue("Fixture exposes the reproduced native chin-cap seam in "+view,previous>15);evidence(view+"-old-head-ink.png",f.oldInk);evidence(view+"-integrated-head-ink.png",f.newInk);evidence(view+"-actual-neck-fill.png",f.body);if(remaining>0)failures.add(view+": "+remaining+" internal cap pixels remain (previous "+previous+")");
  }assertTrue("Cranium outline inside the already-filled neck is a construction seam: "+failures,failures.isEmpty());
 }
 @Test public void frontalChinInFrontOfTheNeckRemainsVisibleDespiteProjectedOverlap()throws Exception {
  try(Fixture f=new Fixture("front",0,0)){int retained=0;for(int y=390;y<=400;y++)for(int x=251;x<=261;x++){assertEquals("Projected torso overlap must not erase a genuinely nearer frontal chin at "+x+","+y,f.oldInk.getPixel(x,y),f.newInk.getPixel(x,y));if((f.oldInk.getPixel(x,y)>>>24)>128)retained++;}assertTrue("The fixture must include actual visible frontal chin ink",retained>5);}
 }
 @Test public void exposedProfileJawAndEveryOtherInkPixelOutsideActualTorsoStayExact()throws Exception {
  for(String view:new String[]{"front","left","right","back","high","low","three_quarter_left","three_quarter_right"})for(double tilt:new double[]{0,18})try(Fixture f=new Fixture(view,tilt,tilt==0?0:9)){
   int visible=0;for(int y=2;y<510;y++)for(int x=2;x<510;x++){boolean nearBody=false;for(int dy=-2;dy<=2&&!nearBody;dy++)for(int dx=-2;dx<=2;dx++)if((f.body.getPixel(x+dx,y+dy)>>>24)>0){nearBody=true;break;}if(nearBody)continue;int expected=f.oldInk.getPixel(x,y),actual=f.newInk.getPixel(x,y);assertEquals("Only neck-overlapped ink may change: "+view+" tilt"+tilt+" pixel"+x+","+y,expected,actual);if((expected>>>24)>128)visible++;}assertTrue("Preserve genuine exposed facial/jaw/hair ink",visible>100);
  }
 }
}
