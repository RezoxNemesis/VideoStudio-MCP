package com.rezoxnemesis.videostudio;
import org.json.*;
/** Reveal exactly the submitted pressure path by travelled distance. */
public final class SketchStrokeProgress {
    private SketchStrokeProgress(){}
    public static JSONArray prefix(JSONArray points,double fraction) throws Exception {
        JSONArray out=new JSONArray();if(fraction<=0||points.length()==0)return out;if(fraction>=1)return new JSONArray(points.toString());
        double total=0;for(int i=1;i<points.length();i++){JSONArray a=points.getJSONArray(i-1),b=points.getJSONArray(i);total+=Math.hypot(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1));}
        out.put(points.getJSONArray(0));double remaining=total*fraction;
        for(int i=1;i<points.length();i++){JSONArray a=points.getJSONArray(i-1),b=points.getJSONArray(i);double length=Math.hypot(b.getDouble(0)-a.getDouble(0),b.getDouble(1)-a.getDouble(1));if(remaining>=length){out.put(b);remaining-=length;}else{double t=length==0?1:remaining/length;out.put(new JSONArray().put(a.getDouble(0)+(b.getDouble(0)-a.getDouble(0))*t).put(a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*t).put(a.getDouble(2)+(b.getDouble(2)-a.getDouble(2))*t));break;}}
        return out;
    }
}
