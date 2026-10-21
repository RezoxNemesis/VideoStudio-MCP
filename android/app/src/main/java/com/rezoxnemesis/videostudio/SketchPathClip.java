package com.rezoxnemesis.videostudio;
import android.graphics.Path;
import org.json.*;
/** Object-local vector exclusion. A clip affects its path only, never lower-layer artwork. */
final class SketchPathClip {
 static final int MAX_RINGS=128,MAX_RING_POINTS=32,MAX_POINTS=4096;
 private SketchPathClip(){}
 static int validate(JSONObject object)throws Exception {
  if(!object.has("clipOut"))return 0;
  if(!"path".equals(object.optString("kind")))throw SketchDocument.bad("Path clipping requires an editable path");
  if(object.has("shapeKeys")&&object.getJSONArray("shapeKeys").length()>0||object.has("masterLink"))throw SketchDocument.bad("Path clipping does not support shape keys or linked masters; detach first");
  return validateRings(object.get("clipOut"));
 }
 static int validateRings(Object value)throws Exception {
  if(!(value instanceof JSONArray))throw SketchDocument.bad("clipOut requires polygon rings");JSONArray rings=(JSONArray)value;
  if(rings.length()<1||rings.length()>MAX_RINGS)throw SketchDocument.bad("Use1..128 clipping rings");int count=0;
  for(int i=0;i<rings.length();i++){
   if(!(rings.get(i) instanceof JSONArray))throw SketchDocument.bad("A clipping ring requires point pairs");JSONArray ring=rings.getJSONArray(i);int n=ring.length();if(n<3||n>MAX_RING_POINTS||(count+=n)>MAX_POINTS)throw SketchDocument.bad("Clip rings require3..32 points and at most4096 total vertices");double[][] p=new double[n][2];
   for(int j=0;j<n;j++){if(!(ring.get(j) instanceof JSONArray)||ring.getJSONArray(j).length()!=2)throw SketchDocument.bad("Clip vertices require[x,y]");JSONArray q=ring.getJSONArray(j);p[j][0]=SketchDocument.scalar(q.get(0),0,1,"Clip x");p[j][1]=SketchDocument.scalar(q.get(1),0,1,"Clip y");}
   double sign=0,area=0;for(int j=0;j<n;j++){double[] a=p[j],b=p[(j+1)%n],c=p[(j+2)%n];double cross=cross(b[0]-a[0],b[1]-a[1],c[0]-b[0],c[1]-b[1]);if(Math.hypot(b[0]-a[0],b[1]-a[1])<=1e-12)throw SketchDocument.bad("Clip vertices must be distinct");if(Math.abs(cross)>1e-15){if(sign!=0&&sign*cross<0)throw SketchDocument.bad("Clip rings must be simple and convex");sign=cross;}area+=a[0]*b[1]-a[1]*b[0];}
   if(Math.abs(area)<=1e-14||sign==0)throw SketchDocument.bad("Clip ring must have positive area");
   for(int a=0;a<n;a++)for(int b=a+2;b<n;b++){if(a==0&&b==n-1)continue;if(intersects(p[a],p[(a+1)%n],p[b],p[(b+1)%n]))throw SketchDocument.bad("Clip ring edges must not intersect");}
  }return count;
 }
 private static double cross(double x,double y,double a,double b){return x*b-y*a;}
 private static boolean intersects(double[] a,double[] b,double[] c,double[] d){double x=cross(b[0]-a[0],b[1]-a[1],c[0]-a[0],c[1]-a[1]),y=cross(b[0]-a[0],b[1]-a[1],d[0]-a[0],d[1]-a[1]),u=cross(d[0]-c[0],d[1]-c[1],a[0]-c[0],a[1]-c[1]),v=cross(d[0]-c[0],d[1]-c[1],b[0]-c[0],b[1]-c[1]);return x*y<=0&&u*v<=0&&Math.max(Math.min(a[0],b[0]),Math.min(c[0],d[0]))<=Math.min(Math.max(a[0],b[0]),Math.max(c[0],d[0]))&&Math.max(Math.min(a[1],b[1]),Math.min(c[1],d[1]))<=Math.min(Math.max(a[1],b[1]),Math.max(c[1],d[1]));}
 static Path path(JSONArray rings,int width,int height)throws Exception {
  Path p=new Path();for(int i=0;i<rings.length();i++){JSONArray ring=rings.getJSONArray(i);double area=0;for(int j=0;j<ring.length();j++){JSONArray a=ring.getJSONArray(j),b=ring.getJSONArray((j+1)%ring.length());area+=a.getDouble(0)*b.getDouble(1)-a.getDouble(1)*b.getDouble(0);}for(int j=0;j<ring.length();j++){JSONArray q=ring.getJSONArray(area<0?ring.length()-1-j:j);float x=(float)q.getDouble(0)*width,y=(float)q.getDouble(1)*height;if(j==0)p.moveTo(x,y);else p.lineTo(x,y);}p.close();}return p;
 }
 static boolean excludes(JSONArray rings,double x,double y)throws Exception {for(int i=0;i<rings.length();i++){JSONArray ring=rings.getJSONArray(i);double sign=0;boolean inside=true;for(int j=0;j<ring.length();j++){JSONArray a=ring.getJSONArray(j),b=ring.getJSONArray((j+1)%ring.length());double c=cross(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1),x-a.getDouble(0),y-a.getDouble(1));if(Math.abs(c)<1e-14)continue;if(sign!=0&&sign*c<0){inside=false;break;}sign=c;}if(inside)return true;}return false;}
}
