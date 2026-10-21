package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Compare emitted anatomy ink with Android's actual stroked silhouette, not object counts. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnatomyContourRenderTest {
 @Test public void evenOddExportPreservesHolesNestedIslandsAndSeparatedParts()throws Exception {
  Path path=new Path();path.setFillType(Path.FillType.EVEN_ODD);
  path.addRect(5,5,95,95,Path.Direction.CW);path.addRect(15,15,85,85,Path.Direction.CW);path.addRect(30,30,70,70,Path.Direction.CCW);path.addRect(40,40,60,60,Path.Direction.CW);path.addRect(105,5,120,95,Path.Direction.CCW);
  assertExportMatchesNative(path);
 }
 @Test public void windingExportKeepsAnAlreadyCorrectNativeHole()throws Exception {
  Path path=new Path();path.addRect(5,5,95,95,Path.Direction.CCW);path.addRect(15,15,85,85,Path.Direction.CW);assertExportMatchesNative(path);
 }
 private void assertExportMatchesNative(Path source)throws Exception {
  Path exported=new Path();for(float[] ring:SketchHumanAnatomy.windingContours(source)){exported.moveTo(ring[0],ring[1]);for(int i=2;i<ring.length;i+=2)exported.lineTo(ring[i],ring[i+1]);exported.close();}
  Bitmap expected=Bitmap.createBitmap(128,100,Bitmap.Config.ARGB_8888),actual=Bitmap.createBitmap(128,100,Bitmap.Config.ARGB_8888);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);new Canvas(expected).drawPath(source,paint);new Canvas(actual).drawPath(exported,paint);
  try{int[] a=new int[12800],b=new int[12800];expected.getPixels(a,0,128,0,0,128,100);actual.getPixels(b,0,128,0,0,128,100);assertArrayEquals("Native filled regions and holes must survive editable contour export",a,b);}finally{expected.recycle();actual.recycle();}
 }
 private JSONObject document(String view,boolean longHair)throws Exception {
  JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","contour_study").put("width",1280).put("height",720).put("frameCount",1));
  SketchHumanFigure.draw(d,d.getJSONArray("layers").getJSONObject(0),"actor",new JSONObject().put("figureStyle","anatomy_sketch").put("view",view).put("hairStyle",longHair?"long":"short").put("skinColor","#FFF1EEE8").put("size",1.4),new JSONObject(),0);return d;
 }
 private Bitmap rendered(JSONObject d)throws Exception {try(SketchFrameRenderer r=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),d)){return r.render(0,false);}}
 private int ink(Bitmap b){int[] row=new int[b.getWidth()];int n=0;for(int y=0;y<b.getHeight();y++){b.getPixels(row,0,row.length,0,y,row.length,1);for(int p:row)if((p>>>24)>32)n++;}return n;}
 private void evidence(String name,Bitmap b)throws Exception {String location=System.getenv("VIDEOSTUDIO_ANATOMY_CONTOUR_EVIDENCE");if(location==null)return;File root=new File(location);assertTrue(root.mkdirs()||root.isDirectory());File file=new File(root,name);assertFalse("Preserve failed native generations",file.exists());try(FileOutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
 @Test public void exportedSkinContourDoesNotFillTheInteriorOfTheNativeStroke()throws Exception {
  List<String> errors=new ArrayList<>();
  for(String view:new String[]{"front","left","right","back","high","low","three_quarter_left","three_quarter_right"}){
   JSONObject d=document(view,false);JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put("view",view).put("skinColor","#FFF1EEE8").put("size",1.4));JSONObject stage=SketchDocument.copy(d.getJSONArray("layers").getJSONObject(0)).put("objects",new JSONArray());SketchHumanAnatomy.draw(stage,"reference",settings,SketchHumanFigure.skeleton(settings,new JSONObject()));JSONArray objects=stage.getJSONArray("objects");Path skin=new Path(),shorts=new Path();boolean found=false;JSONObject rim=null;
   for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.getString("id").contains("_anatomy_contour_")){break;}if(!o.getBoolean("fill"))continue;Path shape=SketchFrameRenderer.path(o.getJSONArray("commands"),20480,11520);Matrix model=new Matrix();model.setTranslate(-10240,-5760);shape.transform(model);if(o.getString("color").equals("#FFF1EEE8")){if(!found){skin.set(shape);found=true;}else assertTrue(skin.op(shape,Path.Op.UNION));}if(o.getString("id").contains("_anatomy_shorts_"))shorts.addPath(shape);}
   JSONArray actualObjects=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<actualObjects.length();i++)if(actualObjects.getJSONObject(i).getString("id").contains("_anatomy_contour_"))rim=actualObjects.getJSONObject(i);assertTrue(found);assertNotNull(rim);Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(.65f);stroke.setStrokeJoin(Paint.Join.ROUND);stroke.setStrokeCap(Paint.Cap.ROUND);Path expectedPath=new Path();assertTrue(stroke.getFillPath(skin,expectedPath));assertTrue(expectedPath.op(shorts,Path.Op.DIFFERENCE));Matrix placement=new Matrix();placement.setScale(1.4f,1.4f);placement.postTranslate(640,720*.88f);expectedPath.transform(placement);
   Bitmap expected=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);fill.setColor(Color.BLACK);new Canvas(expected).drawPath(expectedPath,fill);
   JSONObject isolated=SketchDocument.copy(d);isolated.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray().put(rim));Bitmap actual=rendered(isolated);
   try{int wanted=ink(expected),got=ink(actual);assertTrue(wanted>100);if(got>wanted*2)errors.add(view+": contour filled "+got+" pixels; native stroke uses "+wanted);evidence(view+"-native-stroke.png",expected);evidence(view+"-emitted-contour.png",actual);}finally{actual.recycle();expected.recycle();}
  }
  assertTrue("An outline must leave body interiors visible: "+errors,errors.isEmpty());
 }
 @Test public void longHairHasVisibleMassBehindTheNeckInBothProfileViews()throws Exception {
  List<String> errors=new ArrayList<>();
  for(String view:new String[]{"left","right"}){
   Bitmap shortHair=rendered(document(view,false)),longHair=rendered(document(view,true));JSONObject settings=SketchHumanFigure.settings(new JSONObject().put("figureStyle","anatomy_sketch").put("view",view));SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings,new JSONObject());double[] h=p.camera.point(p.head);int cx=(int)Math.round(640+h[0]*1.4),cy=(int)Math.round(720*.88+h[1]*1.4),changed=0;
   for(int yy=cy+7;yy<cy+60;yy++)for(int offset=21;offset<=30;offset++){int xx=cx+(view.equals("right")?-offset:offset)*14/10;int a=shortHair.getPixel(xx,yy),b=longHair.getPixel(xx,yy);if(Color.red(b)<160&&(b>>>24)>128&&((a>>>24)<128||Color.red(a)>190))changed++;}
   try{if(changed<70)errors.add(view+": only "+changed+" visible long-hair pixels behind neck");evidence(view+"-short-hair.png",shortHair);evidence(view+"-long-hair.png",longHair);}finally{shortHair.recycle();longHair.recycle();}
  }
  assertTrue("A rear hair curtain must retain depth in profile: "+errors,errors.isEmpty());
 }
}
