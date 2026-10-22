package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.robolectric.RuntimeEnvironment;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Source-free test fixture. All artwork is produced by ordinary native head/path rendering. */
final class SketchHeadFitTestSupport {
 static final String BASE="32faad8a2ed955c72356b3729e5911984ab2158e";
 static final String SKIN="#FFF1EEE8",IRIS="#FF4B5150";
 static final double SCALE=5.5,CX=256,CY=205;
 static JSONObject settings(double yaw,double pitch)throws Exception{return new JSONObject().put("figureStyle","anatomy_sketch").put("hairStyle","long").put("fringeStyle","center_part").put("skinColor",SKIN).put("hairColor","#FF555150").put("viewYawDegrees",yaw).put("viewPitchDegrees",pitch);}
 static JSONObject profile(double jaw,double chin,double eye)throws Exception{return new JSONObject().put("jawWidth",jaw).put("chinLength",chin).put("eyeWidth",eye);}
 static JSONObject document(JSONObject input,JSONObject pose)throws Exception {
  JSONObject settings=SketchHumanFigure.settings(input),doc=SketchDocument.create(new JSONObject().put("sketchId","head_fit_fixture").put("width",512).put("height",512).put("frameCount",1).put("fps",24).put("background","#FFFFFFFF"));
  JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings,pose);
  SketchAnatomyHead.draw(layer,"actor",settings,p);double[] centre=p.camera.point(p.head);JSONArray objects=layer.getJSONArray("objects");
  for(int i=0;i<objects.length();i++){
   JSONObject object=objects.getJSONObject(i);JSONArray commands=object.getJSONArray("commands");
   for(int j=0;j<commands.length();j++){JSONArray c=commands.getJSONArray(j);for(int k=1;k<c.length();k+=2){double x=(c.getDouble(k)*1280-640)*16-centre[0],y=(c.getDouble(k+1)*720-360)*16-centre[1];c.put(k,(CX+x*SCALE)/512);c.put(k+1,(CY+y*SCALE)/512);}}
   object.put("width",object.getDouble("width")*720*SCALE/512);
  }
  SketchDocument.validate(doc);return doc;
 }
 static String ink(JSONObject doc)throws Exception{return SketchDocument.canonical(doc.getJSONArray("layers").getJSONObject(0));}
 static Bitmap render(JSONObject doc)throws Exception{try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){return renderer.render(0);}}
 static String sha(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte b:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
 static String textSha(String value)throws Exception{return sha(value.getBytes(StandardCharsets.UTF_8));}
 static String pixels(Bitmap bitmap)throws Exception{int[] p=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(p,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());ByteBuffer bytes=ByteBuffer.allocate(p.length*4).order(ByteOrder.BIG_ENDIAN);for(int pixel:p)bytes.putInt(pixel);return sha(bytes.array());}
 static byte[] png(Bitmap bitmap)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));return out.toByteArray();}
 static void write(File file,String value)throws Exception{Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8));}
 static JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
 static JSONArray cases()throws Exception{
  JSONArray cases=new JSONArray();for(int yaw:new int[]{0,45,90,135,180,225,270,315})cases.put(new JSONObject().put("name",String.format(java.util.Locale.ROOT,"yaw-%03d",yaw)).put("settings",settings(yaw,0)).put("pose",new JSONObject()));
  cases.put(new JSONObject().put("name","posed-yaw-030-pitch-minus020").put("settings",settings(30,-20)).put("pose",new JSONObject().put("bodyLean",13).put("headTilt",9).put("blink",.35).put("gazeX",.4).put("brow",.3).put("mouth",.2)));
  cases.put(new JSONObject().put("name","legacy-omitted-camera-and-fringe").put("settings",new JSONObject().put("figureStyle","anatomy_sketch").put("skinColor",SKIN)).put("pose",new JSONObject()));return cases;
 }
 static File baseline(){String path=System.getenv("VIDEOSTUDIO_HEAD_FIT_BASELINE");assertNotNull("Root must capture original32faad8 native baseline before product edits; set VIDEOSTUDIO_HEAD_FIT_BASELINE",path);return new File(path);}
 static JSONArray skinShell(JSONObject doc)throws Exception{JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"),best=null;for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(object.getBoolean("fill")&&SKIN.equals(object.getString("color"))&&(best==null||object.getJSONArray("commands").length()>best.length()))best=object.getJSONArray("commands");}assertNotNull("Actual filled cranial shell",best);return best;}
 static double shellLowerWidth(JSONObject doc)throws Exception{double width=0;JSONArray commands=skinShell(doc);for(int i=0;i<commands.length();i++){JSONArray c=commands.getJSONArray(i);for(int k=1;k<c.length();k+=2){double x=(c.getDouble(k)*512-CX)/SCALE,y=(c.getDouble(k+1)*512-CY)/SCALE;if(y>=19&&y<=24)width=Math.max(width,Math.abs(x));}}assertTrue("Measured lower mandibular control vertices",width>0);return width;}
 static double shellBottom(JSONObject doc)throws Exception{double bottom=-Double.MAX_VALUE;JSONArray commands=skinShell(doc);for(int i=0;i<commands.length();i++){JSONArray c=commands.getJSONArray(i);for(int k=2;k<c.length();k+=2)bottom=Math.max(bottom,(c.getDouble(k)*512-CY)/SCALE);}return bottom;}
 static double[] irisBounds(JSONObject doc)throws Exception{JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");double[] chosen=null;for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(!object.getBoolean("fill")||!IRIS.equals(object.getString("color")))continue;double[] b={Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};JSONArray commands=object.getJSONArray("commands");for(int j=0;j<commands.length();j++){JSONArray c=commands.getJSONArray(j);for(int k=1;k<c.length();k+=2){b[0]=Math.min(b[0],c.getDouble(k));b[2]=Math.max(b[2],c.getDouble(k));b[1]=Math.min(b[1],c.getDouble(k+1));b[3]=Math.max(b[3],c.getDouble(k+1));}}if(chosen==null||b[0]>chosen[0])chosen=b;}assertNotNull("Visible native iris",chosen);return chosen;}
 private SketchHeadFitTestSupport(){}
}
