package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/** Editable row-major texture grid. Coordinates are local to the paint cel's unchanged full-canvas source. */
final class SketchPaintMesh {
    static final int MAX_AXIS=16,MAX_VERTICES=289,MAX_KEYS=1200;
    private static final double MIN_AREA=1e-10;
    private SketchPaintMesh(){}

    static JSONObject regular(int columns,int rows)throws Exception {
        if(columns<1||columns>MAX_AXIS||rows<1||rows>MAX_AXIS)throw SketchDocument.bad("Paint mesh axes require 1..16 cells");
        JSONArray points=new JSONArray();
        for(int y=0;y<=rows;y++)for(int x=0;x<=columns;x++)points.put(new JSONArray().put(x/(double)columns).put(y/(double)rows));
        return new JSONObject(new JSONObject().put("columns",columns).put("rows",rows).put("points",points).put("keys",new JSONArray()).toString());
    }
    /** Additional key and stored point counts, including the base grid. */
    static int[] validate(JSONObject item,int frames)throws Exception {
        if(!item.has("paintMesh"))return new int[]{0,0};
        if(!"paint".equals(item.getString("kind"))||!(item.get("paintMesh") instanceof JSONObject))throw SketchDocument.bad("Paint mesh requires owned paint");
        JSONObject bounds=item.getJSONObject("bounds");if(bounds.getDouble("x")!=0||bounds.getDouble("y")!=0||bounds.getDouble("width")!=1||bounds.getDouble("height")!=1)throw SketchDocument.bad("Paint mesh requires full-canvas paint bounds");
        JSONObject mesh=item.getJSONObject("paintMesh");SketchDocument.fields(mesh,"columns,rows,points,keys");
        int columns=(int)SketchDocument.integer(mesh,"columns",1,MAX_AXIS),rows=(int)SketchDocument.integer(mesh,"rows",1,MAX_AXIS),count=(columns+1)*(rows+1);
        JSONArray base=mesh.getJSONArray("points");validatePoints(base,count,columns,rows);
        JSONArray keys=mesh.has("keys")?mesh.getJSONArray("keys"):new JSONArray();
        if(keys.length()>MAX_KEYS)throw SketchDocument.bad("Use at most 1200 paint mesh keys");
        int previous=-1;JSONObject left=null;
        for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);SketchDocument.fields(key,"frame,points,interpolation,curve");
            int frame=(int)SketchDocument.integer(key,"frame",0,frames-1);if(frame<=previous)throw SketchDocument.bad("Paint mesh keys require unique ascending frames");previous=frame;
            key.getString("interpolation");SketchEasing.validate(key);JSONArray points=key.getJSONArray("points");validatePoints(points,count,columns,rows);
            if(left!=null&&!"hold".equals(left.getString("interpolation")))validateInterval(left.getJSONArray("points"),points,columns,rows);
            left=key;
        }
        return new int[]{keys.length(),count*(keys.length()+1)};
    }
    private static void validatePoints(JSONArray points,int count,int columns,int rows)throws Exception {
        if(points.length()!=count)throw SketchDocument.bad("Paint mesh points must preserve its exact row-major topology");
        for(int i=0;i<count;i++){JSONArray point=points.getJSONArray(i);if(point.length()!=2)throw SketchDocument.bad("Paint mesh vertices require [x,y]");for(int c=0;c<2;c++)SketchDocument.scalar(point.get(c),-4,4,"Paint mesh coordinate");}
        triangles(points,null,columns,rows);
    }
    private static void validateInterval(JSONArray a,JSONArray b,int columns,int rows)throws Exception{triangles(a,b,columns,rows);}
    private static void triangles(JSONArray a,JSONArray b,int columns,int rows)throws Exception {
        for(int y=0;y<rows;y++)for(int x=0;x<columns;x++){
            int tl=y*(columns+1)+x,tr=tl+1,bl=tl+columns+1,br=bl+1;
            area(a,b,tl,tr,br);area(a,b,tl,br,bl);
        }
    }
    private static double cross(double ax,double ay,double bx,double by){return ax*by-ay*bx;}
    private static void area(JSONArray a,JSONArray b,int i,int j,int k)throws Exception {
        JSONArray p=a.getJSONArray(i),q=a.getJSONArray(j),r=a.getJSONArray(k);
        double ux=q.getDouble(0)-p.getDouble(0),uy=q.getDouble(1)-p.getDouble(1),vx=r.getDouble(0)-p.getDouble(0),vy=r.getDouble(1)-p.getDouble(1);
        double c=cross(ux,uy,vx,vy);if(c<=MIN_AREA)throw SketchDocument.bad("Paint mesh contains an inverted or degenerate triangle");
        if(b==null)return;
        JSONArray bp=b.getJSONArray(i),bq=b.getJSONArray(j),br=b.getJSONArray(k);
        double dux=bq.getDouble(0)-bp.getDouble(0)-ux,duy=bq.getDouble(1)-bp.getDouble(1)-uy,dvx=br.getDouble(0)-bp.getDouble(0)-vx,dvy=br.getDouble(1)-bp.getDouble(1)-vy;
        double quadratic=cross(dux,duy,dvx,dvy),linear=cross(dux,duy,vx,vy)+cross(ux,uy,dvx,dvy);
        if(quadratic>0){double t=-linear/(2*quadratic);if(t>0&&t<1&&(quadratic*t+linear)*t+c<=MIN_AREA)throw SketchDocument.bad("Paint mesh triangle collapses between keys");}
    }
    /** Base geometry before the first key; exact key poses and left-key timing thereafter. */
    static JSONArray pointsAt(JSONObject item,int frame)throws Exception {
        JSONObject mesh=item.getJSONObject("paintMesh");JSONArray base=mesh.getJSONArray("points"),keys=mesh.optJSONArray("keys");JSONObject left=null,right=null;
        if(keys!=null)for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);if(key.getInt("frame")<=frame)left=key;else{right=key;break;}}
        if(left==null)return new JSONArray(base.toString());JSONArray a=left.getJSONArray("points");
        if(right==null||"hold".equals(left.getString("interpolation")))return new JSONArray(a.toString());
        double t=SketchEasing.sample(left.getString("interpolation"),left.optJSONArray("curve"),(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        JSONArray b=right.getJSONArray("points"),result=new JSONArray();for(int i=0;i<a.length();i++){JSONArray p=a.getJSONArray(i),q=b.getJSONArray(i);result.put(new JSONArray().put(p.getDouble(0)+(q.getDouble(0)-p.getDouble(0))*t).put(p.getDouble(1)+(q.getDouble(1)-p.getDouble(1))*t));}return result;
    }

    static boolean regularAt(JSONObject object,int frame)throws Exception {
        JSONObject mesh=object.getJSONObject("paintMesh");int columns=mesh.getInt("columns"),rows=mesh.getInt("rows");JSONArray points=pointsAt(object,frame);for(int row=0;row<=rows;row++)for(int col=0;col<=columns;col++){JSONArray p=points.getJSONArray(row*(columns+1)+col);if(p.getDouble(0)!=col/(double)columns||p.getDouble(1)!=row/(double)rows)return false;}return true;
    }
    static float[] vertices(JSONObject object,int frame,int width,int height)throws Exception {
        JSONArray points=pointsAt(object,frame);float[] result=new float[points.length()*2];for(int i=0;i<points.length();i++){result[2*i]=(float)(points.getJSONArray(i).getDouble(0)*width);result[2*i+1]=(float)(points.getJSONArray(i).getDouble(1)*height);}return result;
    }
    /** Invert the same two triangles per native grid cell to unchanged source UVs. */
    static double[] sourceAt(JSONObject object,int frame,double x,double y)throws Exception {
        JSONObject mesh=object.getJSONObject("paintMesh");int columns=mesh.getInt("columns"),rows=mesh.getInt("rows");JSONArray p=pointsAt(object,frame);
        for(int row=0;row<rows;row++)for(int col=0;col<columns;col++){int tl=row*(columns+1)+col,tr=tl+1,bl=tl+columns+1,br=bl+1;for(int[] triangle:new int[][]{{tl,tr,br},{tl,br,bl}}){double[] b=bary(p,triangle,x,y);if(b==null)continue;double u=0,v=0;for(int i=0;i<3;i++){u+=b[i]*(triangle[i]%(columns+1))/(double)columns;v+=b[i]*(triangle[i]/(columns+1))/(double)rows;}return new double[]{u,v};}}return null;
    }
    private static double[] bary(JSONArray p,int[] t,double x,double y)throws Exception {
        JSONArray a=p.getJSONArray(t[0]),b=p.getJSONArray(t[1]),c=p.getJSONArray(t[2]);double ax=a.getDouble(0),ay=a.getDouble(1),bx=b.getDouble(0)-ax,by=b.getDouble(1)-ay,cx=c.getDouble(0)-ax,cy=c.getDouble(1)-ay,det=bx*cy-by*cx;if(det<=MIN_AREA)return null;double u=((x-ax)*cy-(y-ay)*cx)/det,v=(bx*(y-ay)-by*(x-ax))/det;return u>=-1e-7&&v>=-1e-7&&u+v<=1+1e-7?new double[]{1-u-v,u,v}:null;
    }
    static JSONObject sampled(JSONObject object,int frame)throws Exception {
        JSONObject result=SketchDocument.copy(object);if(result.has("paintMesh")){JSONObject mesh=result.getJSONObject("paintMesh");mesh.put("points",pointsAt(object,frame)).put("keys",new JSONArray());}return normalizeFeatureFields(result);
    }
    static void shiftKeys(JSONObject object,int frame,boolean insert)throws Exception {
        if(!object.has("paintMesh"))return;JSONArray keys=object.getJSONObject("paintMesh").optJSONArray("keys");if(keys==null)return;for(int i=keys.length()-1;i>=0;i--){JSONObject key=keys.getJSONObject(i);int at=key.getInt("frame");if(!insert&&at==frame)keys.remove(i);else if(at>=frame)key.put("frame",at+(insert?1:-1));}
    }

    /** Normalize only new mesh numbers; legacy request fingerprints remain byte-for-byte unchanged. */
    static JSONObject normalizeFeatureFields(JSONObject source)throws Exception {
        return containsMesh(source)?normalizeTree(source):source;
    }
    private static boolean containsMesh(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;if(object.has("paintMesh"))return true;java.util.Iterator<String> names=object.keys();while(names.hasNext())if(containsMesh(object.get(names.next())))return true;}
        else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)if(containsMesh(array.get(i)))return true;}return false;
    }
    private static JSONObject normalizeTree(JSONObject source)throws Exception {
        JSONObject result=new JSONObject();java.util.Iterator<String> fields=source.keys();while(fields.hasNext()){String name=fields.next();Object value=source.get(name);if("paintMesh".equals(name)&&value instanceof JSONObject)value=new JSONObject(value.toString());else if(value instanceof JSONObject)value=normalizeTree((JSONObject)value);else if(value instanceof JSONArray)value=normalizeArray((JSONArray)value);result.put(name,value);}return result;
    }
    private static JSONArray normalizeArray(JSONArray source)throws Exception {
        JSONArray result=new JSONArray();for(int i=0;i<source.length();i++){Object value=source.get(i);if(value instanceof JSONObject)value=normalizeTree((JSONObject)value);else if(value instanceof JSONArray)value=normalizeArray((JSONArray)value);result.put(value);}return result;
    }

}
