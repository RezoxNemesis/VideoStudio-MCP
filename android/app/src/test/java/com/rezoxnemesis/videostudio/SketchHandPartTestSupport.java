package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Source-free fixtures only; all geometry/shading/rasterization comes from native VideoStudio. */
final class SketchHandPartTestSupport {
 static final String BASE="93048e717a25fafdc2fe92f663b9fa2089469876";
 static final int SIZE=512;
 static JSONArray cases()throws Exception{
  JSONArray a=new JSONArray();String[][] choices={{"cel","right","front","grasp"},{"cel","left","front","open"},{"cel","right","back","grasp"},{"cel","left","left","grasp"},{"cel","right","right","open"},{"cel","left","high","grasp"},{"cel","right","low","grasp"},{"legacy","right","front","grasp"},{"smooth","left","back","open"}};
  for(String[] c:choices){JSONObject settings=new JSONObject().put("view",c[2]).put("handedness",c[1]).put("detail","anatomical").put("centerX",.5).put("centerY",.55).put("size",.55).put("skinColor","#FFE2DED3").put("nailColor","#FFF0EADF").put("motion",0).put("wristMotion",0);if(!c[0].equals("legacy"))settings.put("renderStyle",c[0]);
   boolean grasp=c[3].equals("grasp");JSONObject pose=new JSONObject().put("frame",0).put("interpolation","hold").put("indexCurl",grasp?.82:.04).put("middleCurl",grasp?.88:.06).put("ringCurl",grasp?.93:.08).put("littleCurl",grasp?.96:.1).put("thumbCurl",grasp?.68:.12).put("thumbOpposition",grasp?.7:.15).put("spread",grasp?.06:.4).put("wristFlex",grasp?6:0).put("wristTurn",grasp?-8:0);
   a.put(new JSONObject().put("id",String.join("_",c)).put("settings",settings).put("poses",new JSONArray().put(pose)));
  }return a;
 }
 static JSONObject document(JSONObject c,Object parts)throws Exception{
  JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","hand_part_fixture").put("width",SIZE).put("height",SIZE).put("frameCount",96).put("fps",24).put("background","#FFF1F1EB"));
  JSONObject op=new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand").put("settings",SketchDocument.copy(c.getJSONObject("settings"))).put("poses",new JSONArray(c.getJSONArray("poses").toString()));if(parts!=null)op.put("parts",parts);SketchHandAnimation.apply(d,op);
  JSONObject object=SketchDocument.object(d,"hand_rig");JSONArray objectKeys=new JSONArray(),layerKeys=new JSONArray();
  for(int frame:new int[]{0,36,95}){JSONObject t=SketchDocument.transform();if(frame==36)t.put("rotation",13).put("scaleX",1.04).put("scaleY",.96).put("x",.011).put("y",-.006);objectKeys.put(new JSONObject().put("frame",frame).put("transform",t).put("interpolation","linear"));JSONObject l=SketchDocument.transform();if(frame==36)l.put("rotation",-7).put("x",-.008).put("y",.004);layerKeys.put(new JSONObject().put("frame",frame).put("transform",l).put("interpolation","linear"));}
  object.put("keys",objectKeys);d.getJSONArray("layers").getJSONObject(0).put("keys",layerKeys);SketchDocument.validate(d);return d;
 }
 static JSONObject split(JSONObject all)throws Exception{
  JSONObject d=SketchDocument.copy(all);JSONObject source=all.getJSONArray("layers").getJSONObject(0);JSONArray layers=new JSONArray();
  for(String parts:new String[]{"palm_forearm","digits"}){JSONObject layer=SketchDocument.copy(source);layer.put("id",parts);JSONObject hand=layer.getJSONArray("objects").getJSONObject(0);hand.put("id",parts+"_hand");hand.getJSONObject("hand").put("parts",parts);layers.put(layer);}d.put("layers",layers);SketchDocument.validate(d);return d;
 }
 static String inkHash(JSONObject d,int frame)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  for(SketchHandGeometry.Ink mark:SketchHandAnimation.ink(SketchDocument.object(d,"hand_rig"),SIZE,SIZE,frame)){out.writeUTF(mark.color);out.writeBoolean(mark.fill);out.writeUTF(mark.gradient==null?"":SketchDocument.canonical(mark.gradient));float[] p=mark.path.approximate(.01f);out.writeInt(p.length);for(float f:p)out.writeFloat(f);}return SketchDocument.sha256(bytes.toByteArray());
 }
 static String pixels(Bitmap image)throws Exception{int[] p=new int[image.getWidth()*image.getHeight()];image.getPixels(p,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());ByteBuffer bytes=ByteBuffer.allocate(p.length*4);for(int color:p)bytes.putInt(color);return SketchDocument.sha256(bytes.array());}
 static JSONObject readBaseline()throws Exception{
  String path=System.getenv("VIDEOSTUDIO_HAND_PART_BASELINE");if(path!=null){File file=new File(path);if(file.isDirectory())file=new File(file,"Baseline.json");return new JSONObject(Files.readString(file.toPath()));}
  try(InputStream stream=SketchHandPartTestSupport.class.getResourceAsStream("/native-hand-parts-baseline-93048e7.json")){assertNotNull("Root must capture original93048e7 before production changes and retain its immutable source-free baseline",stream);return new JSONObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8));}
 }
 static String canonical(Object value)throws Exception{return SketchDocument.canonical(new JSONObject(new JSONObject().put("v",value).toString()).get("v"));}
}
