package com.rezoxnemesis.videostudio;

import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authored vector material shading; no texture substitution or hidden raster work. */
final class SketchGradients {
    private SketchGradients(){}
    static void validate(JSONObject g)throws Exception {
        if(!g.has("type")){
            SketchDocument.fields(g,"startColor,endColor,x0,y0,x1,y1");
            SketchDocument.color(g.getString("startColor"));SketchDocument.color(g.getString("endColor"));
            for(String k:new String[]{"x0","y0","x1","y1"})SketchDocument.number(g,k,0,1);
            if(g.getDouble("x0")==g.getDouble("x1")&&g.getDouble("y0")==g.getDouble("y1"))throw SketchDocument.bad("Gradient endpoints must differ");
            return;
        }
        SketchDocument.fields(g,"type,cx,cy,radius,colors,stops");
        if(!"radial".equals(g.getString("type")))throw SketchDocument.bad("Unsupported material shading type");
        SketchDocument.number(g,"cx",0,1);SketchDocument.number(g,"cy",0,1);SketchDocument.number(g,"radius",.0001,2);
        if(!(g.opt("colors") instanceof JSONArray))throw SketchDocument.bad("Radial shading requires color stops");
        JSONArray colors=g.getJSONArray("colors");if(colors.length()<2||colors.length()>16)throw SketchDocument.bad("Use 2..16 radial colors");
        for(int i=0;i<colors.length();i++)SketchDocument.color(colors.getString(i));
        if(g.has("stops")){
            if(!(g.opt("stops") instanceof JSONArray)||g.getJSONArray("stops").length()!=colors.length())throw SketchDocument.bad("Each color needs a matching stop");
            JSONArray stops=g.getJSONArray("stops");double previous=-1;
            for(int i=0;i<stops.length();i++){
                double v=SketchDocument.scalar(stops.get(i),0,1,"Gradient stop");
                if(v<=previous||i==0&&v!=0||i==stops.length()-1&&v!=1)throw SketchDocument.bad("Stops must increase strictly from 0 to 1");
                previous=v;
            }
        }
    }
    static Shader shader(JSONObject g,int width,int height)throws Exception {
        if(!g.has("type"))return new LinearGradient((float)g.getDouble("x0")*width,(float)g.getDouble("y0")*height,
                (float)g.getDouble("x1")*width,(float)g.getDouble("y1")*height,
                SketchDocument.color(g.getString("startColor")),SketchDocument.color(g.getString("endColor")),Shader.TileMode.CLAMP);
        JSONArray values=g.getJSONArray("colors");int[] colors=new int[values.length()];
        for(int i=0;i<colors.length;i++)colors[i]=SketchDocument.color(values.getString(i));
        float[] stops=null;if(g.has("stops")){
            stops=new float[colors.length];for(int i=0;i<stops.length;i++)stops[i]=(float)g.getJSONArray("stops").getDouble(i);
        }
        return new RadialGradient((float)g.getDouble("cx")*width,(float)g.getDouble("cy")*height,
                (float)g.getDouble("radius")*Math.min(width,height),colors,stops,Shader.TileMode.CLAMP);
    }
}
