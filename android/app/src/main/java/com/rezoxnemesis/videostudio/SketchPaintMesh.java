package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import android.graphics.*;

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
    /** Source rectangle is an integer UV interval, never a replacement/cropped source PNG. */
    static JSONObject focused(int columns,int rows,JSONArray rectangle,int width,int height)throws Exception {
        JSONObject mesh=regular(columns,rows).put("sourceRect",new JSONArray(rectangle.toString()));JSONArray points=mesh.getJSONArray("points");
        double l=rectangle.getDouble(0)/width,t=rectangle.getDouble(1)/height,r=rectangle.getDouble(2)/width,b=rectangle.getDouble(3)/height;
        for(int y=0;y<=rows;y++)for(int x=0;x<=columns;x++)points.put(y*(columns+1)+x,new JSONArray().put(l+(r-l)*x/columns).put(t+(b-t)*y/rows));return mesh;
    }
    static double[] sourceDomain(JSONObject object)throws Exception {
        JSONObject mesh=object.getJSONObject("paintMesh");if(!mesh.has("sourceRect"))return new double[]{0,0,1,1};JSONArray r=mesh.getJSONArray("sourceRect");JSONObject source=object.getJSONObject("paint");return new double[]{r.getDouble(0)/source.getInt("width"),r.getDouble(1)/source.getInt("height"),r.getDouble(2)/source.getInt("width"),r.getDouble(3)/source.getInt("height")};
    }
    /** Additional key and stored point counts, including the base grid. */
    static int[] validate(JSONObject item,int frames)throws Exception {
        if(!item.has("paintMesh"))return new int[]{0,0};
        if(!"paint".equals(item.getString("kind"))||!(item.get("paintMesh") instanceof JSONObject))throw SketchDocument.bad("Paint mesh requires owned paint");
        JSONObject bounds=item.getJSONObject("bounds");if(bounds.getDouble("x")!=0||bounds.getDouble("y")!=0||bounds.getDouble("width")!=1||bounds.getDouble("height")!=1)throw SketchDocument.bad("Paint mesh requires full-canvas paint bounds");
        JSONObject mesh=item.getJSONObject("paintMesh");SketchDocument.fields(mesh,"columns,rows,points,keys,sourceRect");
        if(mesh.has("sourceRect")){JSONArray rect=mesh.getJSONArray("sourceRect");JSONObject source=item.getJSONObject("paint");if(rect.length()!=4)throw SketchDocument.bad("Paint source rectangle requires four integer pixels");for(int i=0;i<4;i++){Object value=rect.get(i);if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()!=Math.rint(((Number)value).doubleValue())||((Number)value).doubleValue()<0||((Number)value).doubleValue()>2048)throw SketchDocument.bad("Paint source rectangle requires integer pixels");}if(rect.getInt(0)<0||rect.getInt(1)<0||rect.getInt(2)>source.getInt("width")||rect.getInt(3)>source.getInt("height")||rect.getInt(0)>=rect.getInt(2)||rect.getInt(1)>=rect.getInt(3))throw SketchDocument.bad("Paint source rectangle is outside the unchanged source");}
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
        JSONObject mesh=object.getJSONObject("paintMesh");int columns=mesh.getInt("columns"),rows=mesh.getInt("rows");JSONArray points=pointsAt(object,frame);double[] uv=sourceDomain(object);for(int row=0;row<=rows;row++)for(int col=0;col<=columns;col++){JSONArray p=points.getJSONArray(row*(columns+1)+col);double x=uv[0]+(uv[2]-uv[0])*col/columns,y=uv[1]+(uv[3]-uv[1])*row/rows;if(p.getDouble(0)!=x||p.getDouble(1)!=y)return false;}return true;
    }
    static float[] vertices(JSONObject object,int frame,int width,int height)throws Exception {
        JSONArray points=pointsAt(object,frame);float[] result=new float[points.length()*2];for(int i=0;i<points.length();i++){result[2*i]=(float)(points.getJSONArray(i).getDouble(0)*width);result[2*i+1]=(float)(points.getJSONArray(i).getDouble(1)*height);}return result;
    }
    /** Invert the same two triangles per native grid cell to unchanged source UVs. */
    static double[] sourceAt(JSONObject object,int frame,double x,double y)throws Exception {
        JSONObject mesh=object.getJSONObject("paintMesh");int columns=mesh.getInt("columns"),rows=mesh.getInt("rows");JSONArray p=pointsAt(object,frame);double[] domain=sourceDomain(object);
        for(int row=0;row<rows;row++)for(int col=0;col<columns;col++){int tl=row*(columns+1)+col,tr=tl+1,bl=tl+columns+1,br=bl+1;for(int[] triangle:new int[][]{{tl,tr,br},{tl,br,bl}}){double[] b=bary(p,triangle,x,y);if(b==null)continue;double u=0,v=0;for(int i=0;i<3;i++){u+=b[i]*(triangle[i]%(columns+1))/(double)columns;v+=b[i]*(triangle[i]/(columns+1))/(double)rows;}return new double[]{domain[0]+u*(domain[2]-domain[0]),domain[1]+v*(domain[3]-domain[1])};}}return null;
    }
    /** Exact uniform translation only. No tolerance hides an authored vertex edit. */
    static double[] translationAt(JSONObject object,int frame)throws Exception {
        JSONObject mesh=object.getJSONObject("paintMesh");if(!mesh.has("sourceRect"))return null;int columns=mesh.getInt("columns"),rows=mesh.getInt("rows");JSONArray points=pointsAt(object,frame);double[] domain=sourceDomain(object);double dx=points.getJSONArray(0).getDouble(0)-domain[0],dy=points.getJSONArray(0).getDouble(1)-domain[1];
        for(int y=0;y<=rows;y++)for(int x=0;x<=columns;x++){JSONArray p=points.getJSONArray(y*(columns+1)+x);if(p.getDouble(0)-(domain[0]+(domain[2]-domain[0])*x/columns)!=dx||p.getDouble(1)-(domain[1]+(domain[3]-domain[1])*y/rows)!=dy)return null;}return new double[]{dx,dy};
    }
    /** Crop the exact integer interval before resizing a disposable texture, never source assets. */
    static Bitmap focusedTexture(Bitmap full,JSONObject mesh,int edge)throws Exception {
        SketchPaintMeshPrepare.worker();SketchPaintMeshPrepare.cancelled();JSONArray rect=mesh.getJSONArray("sourceRect");Bitmap texture=Bitmap.createBitmap(full,rect.getInt(0),rect.getInt(1),rect.getInt(2)-rect.getInt(0),rect.getInt(3)-rect.getInt(1));if(texture==full)texture=full.copy(Bitmap.Config.ARGB_8888,false);
        try{if(edge>0&&Math.max(texture.getWidth(),texture.getHeight())>edge){double scale=edge/(double)Math.max(texture.getWidth(),texture.getHeight());Bitmap resized=Bitmap.createScaledBitmap(texture,Math.max(1,(int)Math.round(texture.getWidth()*scale)),Math.max(1,(int)Math.round(texture.getHeight()*scale)),true);if(resized!=texture)texture.recycle();texture=resized;}SketchPaintMeshPrepare.cancelled();return texture;}catch(Exception|Error failure){texture.recycle();throw failure;}
    }
    static void drawGrid(Canvas canvas,Bitmap texture,JSONObject mesh,float[] vertices,Paint paint)throws Exception {paint.setStyle(Paint.Style.FILL);canvas.drawBitmapMesh(texture,mesh.getInt("columns"),mesh.getInt("rows"),vertices,0,null,0,paint);}
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
