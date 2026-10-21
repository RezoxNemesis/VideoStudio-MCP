package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Editable rope/connector ink with exact endpoints at two native human grips. Not cloth physics. */
final class SketchGripLink {
 private SketchGripLink(){}
 static JSONObject defaults()throws Exception{return new JSONObject().put("sagPx",12).put("widthPx",2.8).put("color","#FF9D784F");}
 static JSONObject settings(JSONObject input)throws Exception {SketchDocument.fields(input,"sagPx,widthPx,color");JSONObject s=defaults();Iterator<String> keys=input.keys();while(keys.hasNext()){String key=keys.next();s.put(key,input.get(key));}SketchDocument.number(s,"sagPx",0,64);SketchDocument.number(s,"widthPx",.5,12);SketchDocument.color(s.getString("color"));return s;}
 static void apply(JSONObject doc,JSONObject op)throws Exception {
  SketchDocument.fields(op,"type,layerId,linkId,from,to,settings");String id=SketchDocument.id(op,"linkId"),layerId=SketchDocument.id(op,"layerId");SketchStoryStage.requireTarget(doc,layerId,id);
  int frames=doc.getInt("frameCount");if(frames>240)throw SketchDocument.bad("Grip links preserve exact output-frame curves; use a shot of at most240frames");JSONObject s=settings(op.getJSONObject("settings")),from=endpoint(doc,op.getJSONObject("from")),to=endpoint(doc,op.getJSONObject("to"));
  JSONArray layers=doc.getJSONArray("layers");int target=-1,firstActor=layers.length();for(int i=0;i<layers.length();i++){String layer=layers.getJSONObject(i).getString("id");if(layer.equals(layerId))target=i;if(layer.equals(SketchDocument.owner(doc,from.getString("id")).getString("id"))||layer.equals(SketchDocument.owner(doc,to.getString("id")).getString("id")))firstActor=Math.min(firstActor,i);}
  if(target>=firstActor)throw SketchDocument.bad("Place the grip-link layer behind its human hand layers");
  JSONArray keys=new JSONArray();int w=doc.getInt("width"),h=doc.getInt("height");double fit=Math.min(w/1280d,h/720d);JSONObject dest=SketchDocument.layer(doc,layerId);
  for(int f=0;f<frames;f++){
   if(Thread.currentThread().isInterrupted())throw new InterruptedException("Grip link construction cancelled");float[] a=worldGrip(doc,from,op.getJSONObject("from").getString("hand"),f),b=worldGrip(doc,to,op.getJSONObject("to").getString("hand"),f);
   if(Math.hypot(a[0]-b[0],a[1]-b[1])<2)throw SketchDocument.bad("Grip-link endpoints must be distinct");float[] control={(a[0]+b[0])/2,(float)((a[1]+b[1])/2+2*s.getDouble("sagPx")*fit)};
   Matrix world=SketchFrameRenderer.matrix(SketchDocument.transformAt(dest,f),w,h),inverse=new Matrix();if(!world.invert(inverse))throw SketchDocument.bad("Grip-link destination transform is not invertible");inverse.mapPoints(a);inverse.mapPoints(b);inverse.mapPoints(control);
   JSONArray commands=new JSONArray().put(new JSONArray().put("M").put(a[0]/w).put(a[1]/h)).put(new JSONArray().put("Q").put(control[0]/w).put(control[1]/h).put(b[0]/w).put(b[1]/h));SketchDocument.validateCommands(commands);
   Path geometry=SketchFrameRenderer.path(commands,w,h);geometry.transform(world);RectF bounds=new RectF();geometry.computeBounds(bounds,true);float pad=(float)(s.getDouble("widthPx")*fit*.65);bounds.inset(-pad,-pad);if(bounds.left<0||bounds.top<0||bounds.right>w||bounds.bottom>h)throw SketchDocument.bad("Grip link leaves canvas");
   keys.put(new JSONObject().put("frame",f).put("interpolation","hold").put("commands",commands));
  }
  JSONArray additions=new JSONArray();for(int part=0;part<2;part++){
   JSONObject path=SketchEditOps.completeObject(new JSONObject().put("id",id+(part==0?"_shadow":"_rope")).put("kind","path").put("frame",0).put("exposure",frames).put("fill",false).put("width",Math.max(.0005,s.getDouble("widthPx")*fit*(part==0?1.3:.65)/Math.min(w,h))).put("color",SketchCharacterArt.tone(s.getString("color"),part==0?.63:1.15)).put("commands",keys.getJSONObject(0).getJSONArray("commands")).put("shapeKeys",new JSONArray(keys.toString())));additions.put(path);
  }SketchStoryStage.append(doc,layerId,additions);
 }
 private static JSONObject endpoint(JSONObject doc,JSONObject input)throws Exception {SketchDocument.fields(input,"actorId,hand");String id=SketchDocument.id(input,"actorId")+"_rig";if(!Set.of("near","far").contains(input.getString("hand")))throw SketchDocument.bad("Choose near/far hand");JSONObject actor=SketchDocument.object(doc,id);if(!"human".equals(actor.getString("kind")))throw SketchDocument.bad("Grip links require native human actors");if(actor.getInt("frame")!=0||actor.getInt("exposure")<doc.getInt("frameCount")||actor.getJSONObject("human").getInt("fps")!=doc.getInt("fps"))throw SketchDocument.bad("Grip-link actors must cover the same complete frame clock");return actor;}
 static float[] worldGrip(JSONObject doc,JSONObject actor,String hand,int frame)throws Exception {
  JSONObject settings=actor.getJSONObject("human").getJSONObject("settings");double[] grip=SketchHumanFigure.grip(settings,SketchHumanAnimation.pose(actor,frame),hand.equals("near"));int w=doc.getInt("width"),h=doc.getInt("height");double fit=Math.min(w/1280d,h/720d)*settings.getDouble("size");float[] point={(float)(w*settings.getDouble("x")+grip[0]*fit),(float)(h*settings.getDouble("floorY")+grip[1]*fit)};
  Matrix world=SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.owner(doc,actor.getString("id")),frame),w,h);world.preConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(actor,frame),w,h));world.mapPoints(point);return point;
 }
}
