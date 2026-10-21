package com.rezoxnemesis.videostudio;

import org.json.*;
import java.nio.charset.StandardCharsets;

/** Strict bounded polygon geometry and truthful source-copy provenance. */
final class SketchSourceMask {
    private static final double EPS=1e-12;
    private SketchSourceMask() {}
    static void rings(JSONArray rings,int maximum)throws Exception {
        if(rings.length()<1||rings.length()>maximum)throw SketchDocument.bad("Use 1.."+maximum+" mask rings");
        for(int r=0;r<rings.length();r++){
            JSONArray ring=rings.getJSONArray(r);int n=ring.length();double area=0;
            if(n<3||n>64)throw SketchDocument.bad("Mask rings require 3..64 points");
            for(int i=0;i<n;i++){
                JSONArray p=ring.getJSONArray(i);if(p.length()!=2)throw SketchDocument.bad("Mask points require [x,y]");
                for(int k=0;k<2;k++)SketchDocument.scalar(p.get(k),0,1,"Mask coordinate");
                for(int j=0;j<i;j++)if(same(p,ring.getJSONArray(j)))throw SketchDocument.bad("Mask vertices must be distinct; closure is implicit");
            }
            for(int i=0;i<n;i++){
                JSONArray a=ring.getJSONArray(i),b=ring.getJSONArray((i+1)%n);area+=a.getDouble(0)*b.getDouble(1)-b.getDouble(0)*a.getDouble(1);
                JSONArray c=ring.getJSONArray((i+2)%n);
                // Adjacent collinear reversals overlap their own boundary.
                if(Math.abs(cross(a,b,c))<=EPS&&dot(a,b,c)<-EPS)throw SketchDocument.bad("Mask boundary doubles back");
                for(int j=i+1;j<n;j++)if(j!=i+1&&!(i==0&&j==n-1)&&intersects(a,b,ring.getJSONArray(j),ring.getJSONArray((j+1)%n)))throw SketchDocument.bad("Mask ring intersects itself");
            }
            if(Math.abs(area)<=EPS)throw SketchDocument.bad("Mask ring has no area");
            for(int s=0;s<r;s++){
                JSONArray other=rings.getJSONArray(s);
                for(int i=0;i<n;i++)for(int j=0;j<other.length();j++)if(intersects(ring.getJSONArray(i),ring.getJSONArray((i+1)%n),other.getJSONArray(j),other.getJSONArray((j+1)%other.length())))throw SketchDocument.bad("Mask rings must not touch or intersect");
            }
        }
    }
    private static boolean same(JSONArray a,JSONArray b)throws Exception{return Math.abs(a.getDouble(0)-b.getDouble(0))<=EPS&&Math.abs(a.getDouble(1)-b.getDouble(1))<=EPS;}
    private static double cross(JSONArray a,JSONArray b,JSONArray c)throws Exception{return(b.getDouble(0)-a.getDouble(0))*(c.getDouble(1)-a.getDouble(1))-(b.getDouble(1)-a.getDouble(1))*(c.getDouble(0)-a.getDouble(0));}
    private static double dot(JSONArray a,JSONArray b,JSONArray c)throws Exception{return(b.getDouble(0)-a.getDouble(0))*(c.getDouble(0)-b.getDouble(0))+(b.getDouble(1)-a.getDouble(1))*(c.getDouble(1)-b.getDouble(1));}
    private static boolean on(JSONArray a,JSONArray b,JSONArray p)throws Exception{return Math.abs(cross(a,b,p))<=EPS&&p.getDouble(0)>=Math.min(a.getDouble(0),b.getDouble(0))-EPS&&p.getDouble(0)<=Math.max(a.getDouble(0),b.getDouble(0))+EPS&&p.getDouble(1)>=Math.min(a.getDouble(1),b.getDouble(1))-EPS&&p.getDouble(1)<=Math.max(a.getDouble(1),b.getDouble(1))+EPS;}
    private static boolean intersects(JSONArray a,JSONArray b,JSONArray c,JSONArray d)throws Exception{double x=cross(a,b,c),y=cross(a,b,d),z=cross(c,d,a),w=cross(c,d,b);return ((x>EPS&&y<-EPS||x<-EPS&&y>EPS)&&(z>EPS&&w<-EPS||z<-EPS&&w>EPS))||on(a,b,c)||on(a,b,d)||on(c,d,a)||on(c,d,b);}
    /** The same native hard-mask geometry is used for extraction and touch selection. */
    static android.graphics.Path path(JSONArray rings,int width,int height)throws Exception{
        android.graphics.Path path=new android.graphics.Path();path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        for(int r=0;r<rings.length();r++){JSONArray ring=rings.getJSONArray(r);for(int i=0;i<ring.length();i++){
            JSONArray point=ring.getJSONArray(i);float x=(float)(point.getDouble(0)*width),y=(float)(point.getDouble(1)*height);
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }path.close();}return path;
    }
    static String hash(int width,int height,JSONArray rings,boolean complement)throws Exception{return hash(width,height,rings,complement,null);}
    private static String hash(int width,int height,JSONArray rings,boolean complement,Boolean overlap)throws Exception{JSONObject identity=new JSONObject().put("version",1).put("width",width).put("height",height).put("rings",new JSONArray(rings.toString())).put("complement",complement);if(overlap!=null)identity.put("allowPartOverlap",overlap);return SketchDocument.sha256(SketchDocument.canonical(identity).getBytes(StandardCharsets.UTF_8));}

    static String hashField(JSONObject o,String key)throws Exception{Object v=o.get(key);if(!(v instanceof String)||!((String)v).matches("[a-f0-9]{64}"))throw SketchDocument.bad("Invalid "+key);return(String)v;}
    static JSONObject metadata(JSONObject args,String maskId,JSONArray rings,boolean complement,int w,int h)throws Exception{Boolean overlap=args.has("allowPartOverlap")?args.getBoolean("allowPartOverlap"):null;JSONObject metadata=new JSONObject().put("version",1).put("method","source_assisted").put("sourceReferenceSha256",args.getString("expectedReferenceSha256")).put("sourceSha256",args.getString("expectedSourceSha256")).put("operationId",args.getString("operationId")).put("maskId",maskId).put("complement",complement).put("rings",new JSONArray(rings.toString())).put("maskSha256",hash(w,h,rings,complement,overlap));if(overlap!=null)metadata.put("allowPartOverlap",overlap);return metadata;}
    static void validate(JSONObject ref)throws Exception{
        JSONObject m=ref.getJSONObject("sourceMask");SketchDocument.fields(m,"version,method,sourceReferenceSha256,sourceSha256,operationId,maskId,complement,rings,maskSha256,allowPartOverlap");
        SketchDocument.integer(m,"version",1,1);if(!"source_assisted".equals(m.getString("method")))throw SketchDocument.bad("Source cutouts must retain source_assisted provenance");
        hashField(m,"sourceReferenceSha256");String source=hashField(m,"sourceSha256");SketchDocument.id(m,"operationId");SketchDocument.id(m,"maskId");SketchDocument.bool(m,"complement");
        if(m.has("allowPartOverlap"))SketchDocument.bool(m,"allowPartOverlap");
        JSONArray rr=m.getJSONArray("rings");boolean complement=m.getBoolean("complement");
        // A complement aggregates independent part rings, which may share edges.
        if(complement){if(rr.length()<1||rr.length()>64)throw SketchDocument.bad("Complement rings exceed their budget");for(int i=0;i<rr.length();i++)rings(new JSONArray().put(rr.getJSONArray(i)),1);}else rings(rr,8);
        if(!source.equals(ref.optString("sourceSha256")))throw SketchDocument.bad("Cutout source hash changed");
        if(!hash(ref.getInt("width"),ref.getInt("height"),rr,complement,m.has("allowPartOverlap")?m.getBoolean("allowPartOverlap"):null).equals(hashField(m,"maskSha256")))throw SketchDocument.bad("Source mask geometry hash changed");
    }
}
