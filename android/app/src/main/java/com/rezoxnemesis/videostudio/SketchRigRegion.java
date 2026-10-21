package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.Set;

/** Retained local influence recipe shared by the native rig preview and compiler. */
final class SketchRigRegion {
 private SketchRigRegion(){}
 static void validate(JSONObject region,String moving,Set<String> joints)throws Exception {
  SketchDocument.fields(region,"fixedJointId,movingJointId,start,end,radiusPx");String fixed=SketchDocument.id(region,"fixedJointId"),motion=SketchDocument.id(region,"movingJointId");if(fixed.equals(motion)||!motion.equals(moving)||!joints.contains(fixed)||!joints.contains(motion))throw SketchDocument.bad("Region requires distinct existing joints and the binding's moving joint");
  for(String name:new String[]{"start","end"}){JSONArray point=region.getJSONArray(name);if(point.length()!=2)throw SketchDocument.bad("Region endpoints require normalized prepared-world [x,y]");for(int i=0;i<2;i++)SketchDocument.scalar(point.get(i),-4,4,"Region coordinate");}
  JSONArray a=region.getJSONArray("start"),b=region.getJSONArray("end");if(Math.hypot(a.getDouble(0)-b.getDouble(0),a.getDouble(1)-b.getDouble(1))<1e-9)throw SketchDocument.bad("Region endpoints must not coincide");SketchDocument.number(region,"radiusPx",.5,2048);
 }
 static JSONArray localVertices(JSONObject object,int frame)throws Exception {
  JSONArray result=new JSONArray();String kind=object.getString("kind");
  if("paint".equals(kind)&&object.has("paintMesh"))return SketchPaintMesh.pointsAt(object,frame);
  if("path".equals(kind)){JSONArray commands=SketchShapeKeys.commandsAt(object,frame);for(int i=0;i<commands.length();i++){JSONArray command=commands.getJSONArray(i);for(int j=1;j<command.length();j+=2)result.put(new JSONArray().put(command.getDouble(j)).put(command.getDouble(j+1)));}return result;}
  if(java.util.Arrays.asList("stroke","eraser","line","polygon").contains(kind)){JSONArray points=object.getJSONArray("points");for(int i=0;i<points.length();i++)result.put(new JSONArray().put(points.getJSONArray(i).getDouble(0)).put(points.getJSONArray(i).getDouble(1)));return result;}
  throw SketchDocument.bad("Prepare a paint grid or bind authored point/path geometry before regional deformation");
 }
 static JSONArray weights(JSONObject object,JSONObject region,String moving,Set<String> joints,int width,int height)throws Exception {
  validate(region,moving,joints);JSONArray local=localVertices(object,0),a=region.getJSONArray("start"),b=region.getJSONArray("end"),weights=new JSONArray();double ax=a.getDouble(0)*width,ay=a.getDouble(1)*height,dx=(b.getDouble(0)-a.getDouble(0))*width,dy=(b.getDouble(1)-a.getDouble(1))*height,length2=dx*dx+dy*dy,radius=region.getDouble("radiusPx");String fixed=region.getString("fixedJointId");JSONObject transform=SketchDocument.transformAt(object,0);double anchorX=transform.getDouble("anchorX")*width,anchorY=transform.getDouble("anchorY")*height,angle=Math.toRadians(transform.getDouble("rotation")),cos=Math.cos(angle),sin=Math.sin(angle);int affected=0;
  for(int i=0;i<local.length();i++){if((i&127)==0&&Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Rig region preparation cancelled");JSONArray p=local.getJSONArray(i);double xx=(p.getDouble(0)*width-anchorX)*transform.getDouble("scaleX"),yy=(p.getDouble(1)*height-anchorY)*transform.getDouble("scaleY"),x=anchorX+transform.getDouble("x")*width+cos*xx-sin*yy,y=anchorY+transform.getDouble("y")*height+sin*xx+cos*yy,t=clamp(((x-ax)*dx+(y-ay)*dy)/length2),distance=Math.hypot(x-ax-t*dx,y-ay-t*dy),influence=smooth(t)*(1-smooth(clamp(distance/radius)));JSONObject value=new JSONObject();if(influence<1)value.put(fixed,1-influence);if(influence>0){value.put(moving,influence);affected++;}weights.put(value);}
  if(affected==0)throw SketchDocument.bad("Region must positively affect at least one source vertex");return weights;
 }
 private static double clamp(double value){return Math.max(0,Math.min(1,value));}
 private static double smooth(double value){return value*value*(3-2*value);}
}
