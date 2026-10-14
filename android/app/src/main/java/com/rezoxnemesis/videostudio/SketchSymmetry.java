package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded authored vector symmetry. Coordinates and guides use the active layer's canvas space. */
public final class SketchSymmetry {
    public static final int MAX_SOURCE_ID=90,MAX_STROKE_POINTS=8192;
    private SketchSymmetry() {}

    /** Centers default to the canvas midpoint; only radial mode accepts and requires sectors. */
    public static JSONObject configuration(JSONObject source)throws Exception {
        SketchDocument.fields(source,"mode,segments,centerX,centerY");
        String mode=source.getString("mode");
        if(!java.util.Arrays.asList("vertical","horizontal","both","radial").contains(mode))
            throw SketchDocument.bad("Choose vertical, horizontal, both or radial symmetry");
        JSONObject result=new JSONObject().put("mode",mode)
                .put("centerX",source.has("centerX")?SketchDocument.number(source,"centerX",0,1):.5)
                .put("centerY",source.has("centerY")?SketchDocument.number(source,"centerY",0,1):.5);
        if("radial".equals(mode)){
            if(!source.has("segments"))throw SketchDocument.bad("Radial symmetry requires segments from 2 through 8");
            result.put("segments",SketchDocument.integer(source,"segments",2,8));
        }
        else if(source.has("segments"))throw SketchDocument.bad("Only radial symmetry accepts segments");
        return result;
    }

    /** Restrict this entry point to new authored strokes, never copied references or paint snapshots. */
    public static void requireSource(JSONObject source)throws Exception {
        SketchDocument.fields(source,"id,kind,points,frame,exposure,color,width,fill,brush");
        if(!"stroke".equals(source.getString("kind"))&&!"eraser".equals(source.getString("kind")))
            throw SketchDocument.bad("Symmetry requires an authored stroke or eraser");
        if(SketchDocument.id(source,"id").length()>MAX_SOURCE_ID)
            throw SketchDocument.bad("Symmetry source IDs must be at most 90 characters");
        requirePoints(source.getJSONArray("points"));
        if(source.has("brush"))SketchBrushes.validate(source.getJSONObject("brush"));
    }

    private static void requirePoints(JSONArray points)throws Exception {
        if(points.length()<1||points.length()>MAX_STROKE_POINTS)
            throw SketchDocument.bad("Symmetry strokes require 1..8192 points");
        for(int i=0;i<points.length();i++){
            JSONArray point=points.getJSONArray(i);
            if(point.length()!=3)throw SketchDocument.bad("Symmetry points require [x,y,pressure]");
            for(int n=0;n<3;n++)SketchDocument.scalar(point.get(n),0,1,"Symmetry point coordinate/pressure");
        }
    }

    /** Geometry only, shared with transient preview. Reject outside points instead of changing the stroke. */
    public static JSONArray copies(JSONArray source,JSONObject requested,int width,int height)throws Exception {
        requirePoints(source);JSONObject config=configuration(requested);
        if(width<=0||height<=0)throw SketchDocument.bad("Symmetry requires a positive canvas");
        String mode=config.getString("mode");double cx=config.getDouble("centerX"),cy=config.getDouble("centerY");
        int count="radial".equals(mode)?config.getInt("segments"):"both".equals(mode)?4:2;
        JSONArray result=new JSONArray();
        for(int copy=0;copy<count;copy++){
            JSONArray points=new JSONArray();
            double radians=2*Math.PI*copy/count,cos=cardinal(Math.cos(radians)),sin=cardinal(Math.sin(radians));
            for(int i=0;i<source.length();i++){
                JSONArray point=source.getJSONArray(i);double x=point.getDouble(0),y=point.getDouble(1),nextX=x,nextY=y;
                if(copy>0){
                    if("radial".equals(mode)){
                        double dx=(x-cx)*width,dy=(y-cy)*height;
                        nextX=cx+(dx*cos-dy*sin)/width;nextY=cy+(dx*sin+dy*cos)/height;
                    }else{
                        if("vertical".equals(mode)||"both".equals(mode)&&(copy==1||copy==3))nextX=2*cx-x;
                        if("horizontal".equals(mode)||"both".equals(mode)&&(copy==2||copy==3))nextY=2*cy-y;
                    }
                }
                if(!Double.isFinite(nextX)||!Double.isFinite(nextY)||nextX<0||nextX>1||nextY<0||nextY>1)
                    throw SketchDocument.bad("Symmetry would draw outside the canvas; move its center or draw nearer to it");
                points.put(new JSONArray().put(nextX).put(nextY).put(point.get(2)));
            }
            // A stroke on an axis/center must not receive duplicate ink or erase passes.
            boolean duplicate=false;for(int n=0;n<result.length();n++)if(samePoints(points,result.getJSONArray(n))){duplicate=true;break;}
            if(!duplicate)result.put(points);
        }
        return result;
    }

    private static double cardinal(double value){
        // Exact quarter turns avoid floating-point sine producing an outside point at a canvas edge.
        if(Math.abs(value)<1e-15)return 0;
        if(Math.abs(value-1)<1e-15)return 1;
        if(Math.abs(value+1)<1e-15)return -1;
        return value;
    }
    private static boolean samePoints(JSONArray a,JSONArray b)throws Exception {
        if(a.length()!=b.length())return false;
        for(int i=0;i<a.length();i++)for(int n=0;n<3;n++)
            if(Math.abs(a.getJSONArray(i).getDouble(n)-b.getJSONArray(i).getDouble(n))>1e-12)return false;
        return true;
    }

    /** Validate the complete prospective document before any object is appended to the supplied snapshot. */
    public static JSONArray expand(JSONObject document,JSONObject operation)throws Exception {
        SketchDocument.fields(operation,"type,layerId,object,symmetry");
        if(!"symmetry_stroke".equals(operation.getString("type")))throw SketchDocument.bad("Expected symmetry_stroke");
        String layerId=SketchDocument.id(operation,"layerId");JSONObject layer=SketchDocument.layer(document,layerId);
        SketchEditOps.requireUnlocked(layer);JSONObject source=operation.getJSONObject("object");requireSource(source);
        JSONArray copies=copies(source.getJSONArray("points"),operation.getJSONObject("symmetry"),document.getInt("width"),document.getInt("height"));
        JSONArray expanded=new JSONArray();JSONObject prospective=SketchDocument.copy(document);
        JSONArray objects=SketchDocument.layer(prospective,layerId).getJSONArray("objects");
        for(int i=0;i<copies.length();i++){
            JSONObject object=SketchEditOps.completeObject(source).put("id",source.getString("id")+(i==0?"":"_sym"+i)).put("points",copies.getJSONArray(i));
            objects.put(object);expanded.put(new JSONObject().put("type","add_object").put("layerId",layerId).put("object",object));
        }
        SketchDocument.validate(prospective);return expanded;
    }

    static JSONArray apply(JSONObject document,JSONObject operation)throws Exception {
        JSONArray expanded=expand(document,operation);JSONArray objects=SketchDocument.layer(document,operation.getString("layerId")).getJSONArray("objects");
        for(int i=0;i<expanded.length();i++)objects.put(expanded.getJSONObject(i).getJSONObject("object"));
        return expanded;
    }
}
