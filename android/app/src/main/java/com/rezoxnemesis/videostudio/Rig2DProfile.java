package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/**
 * One user-authored, persistent, textured 2D actor. All coordinates refer to
 * the ORIGINAL imported source image, normalized 0..1. Masks describe the
 * illustrated actor without its sword; the weapon is attached to the rig.
 *
 * No automatic claim of identity extraction or image-to-3D conversion.
 */
public final class Rig2DProfile {
    public static final String[] JOINTS={
        "pelvis","chest","neck","head",
        "swordShoulder","swordElbow","swordHand",
        "freeShoulder","freeElbow","freeHand",
        "leadingHip","leadingKnee","leadingFoot",
        "trailingHip","trailingKnee","trailingFoot"
    };
    public static final int PELVIS=0,CHEST=1,NECK=2,HEAD=3;
    public static final int SWORD_SHOULDER=4,SWORD_ELBOW=5,SWORD_HAND=6;
    public static final int FREE_SHOULDER=7,FREE_ELBOW=8,FREE_HAND=9;
    public static final int LEADING_HIP=10,LEADING_KNEE=11,LEADING_FOOT=12;
    public static final int TRAILING_HIP=13,TRAILING_KNEE=14,TRAILING_FOOT=15;
    public static final int[][] BONES={
        {PELVIS,CHEST},{CHEST,NECK},{NECK,HEAD},
        {CHEST,SWORD_SHOULDER},{SWORD_SHOULDER,SWORD_ELBOW},
        {SWORD_ELBOW,SWORD_HAND},
        {CHEST,FREE_SHOULDER},{FREE_SHOULDER,FREE_ELBOW},
        {FREE_ELBOW,FREE_HAND},
        {PELVIS,LEADING_HIP},{LEADING_HIP,LEADING_KNEE},
        {LEADING_KNEE,LEADING_FOOT},
        {PELVIS,TRAILING_HIP},{TRAILING_HIP,TRAILING_KNEE},
        {TRAILING_KNEE,TRAILING_FOOT}
    };

    public final String id,projectId,sourceAssetId;
    public final boolean black,transparentSource;
    public final float[][] joints;
    public final float[][] outline;
    /** Optional exact source-frame sword endpoint, not a procedural weapon pose. */
    public final float[] swordTip;
    /** Original painted weapon attachment and mask; absent on readable legacy profiles. */
    public final float[] swordHilt;
    public final float[][] swordOutline;
    /** Exact encoded image bytes used for authoring; absent legacy rigs are not cinematic providers. */
    public final String sourceSha256;
    public final int version;

    public Rig2DProfile(String id,String projectId,String assetId,boolean black,
                        boolean transparentSource,float[][] joints,float[][] outline) {
        this(id,projectId,assetId,black,transparentSource,joints,outline,null);
    }

    public Rig2DProfile(String id,String projectId,String assetId,boolean black,
                        boolean transparentSource,float[][] joints,float[][] outline,
                        float[] swordTip) {
        this(id,projectId,assetId,black,transparentSource,joints,outline,swordTip,null,null);
    }

    public Rig2DProfile(String id,String projectId,String assetId,boolean black,
                        boolean transparentSource,float[][] joints,float[][] outline,
                        float[] swordTip,float[] swordHilt,float[][] swordOutline) {
        this(id,projectId,assetId,black,transparentSource,joints,outline,swordTip,swordHilt,swordOutline,null);
    }

    public Rig2DProfile(String id,String projectId,String assetId,boolean black,
                        boolean transparentSource,float[][] joints,float[][] outline,
                        float[] swordTip,float[] swordHilt,float[][] swordOutline,String sourceSha256) {
        this.version=1;
        this.id=checkedId(id,"profile");
        this.projectId=checkedId(projectId,"project");
        this.sourceAssetId=checkedId(assetId,"source asset");
        this.black=black;
        this.transparentSource=transparentSource;
        if(sourceSha256!=null&&!sourceSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Source SHA-256 must be lowercase hexadecimal");
        this.sourceSha256=sourceSha256;
        this.joints=copyPoints(joints,JOINTS.length,JOINTS.length,"joints");
        int count=outline==null?0:outline.length;
        if(!transparentSource && count<3)
            throw new IllegalArgumentException("Mark the actor outline or use a transparent sprite");
        if(count>64)throw new IllegalArgumentException("Actor outline exceeds 64 points");
        this.outline=count==0?new float[0][2]:polygonPoints(outline,"outline");
        this.swordTip=optionalPoint(swordTip,"Sword tip");
        this.swordHilt=optionalPoint(swordHilt,"Sword hilt");
        this.swordOutline=swordOutline==null?new float[0][2]:polygonPoints(swordOutline,"sword outline");
        if(this.swordHilt!=null&&this.swordTip!=null
                &&this.swordHilt[0]==this.swordTip[0]&&this.swordHilt[1]==this.swordTip[1])
            throw new IllegalArgumentException("Sword hilt and tip must differ");
        if(this.swordOutline.length>0)validatePolygon(this.swordOutline,"Sword mask");
        // Reject zero-length limbs that would make skinning undefined.
        for(int[] bone:BONES) {
            float[] a=this.joints[bone[0]], b=this.joints[bone[1]];
            if(a[0]==b[0]&&a[1]==b[1])
                throw new IllegalArgumentException("Zero length joint segment: "+
                        JOINTS[bone[0]]+" to "+JOINTS[bone[1]]);
        }
        if(count>=3)validatePolygon(this.outline,"Actor mask");
    }

    public static Rig2DProfile create(String projectId,String sourceAssetId,
                                       boolean black,boolean transparent,
                                       float[][] joints,float[][] outline) {
        return new Rig2DProfile(UUID.randomUUID().toString(),projectId,
                sourceAssetId,black,transparent,joints,outline);
    }

    public static Rig2DProfile create(String projectId,String sourceAssetId,
                                       boolean black,boolean transparent,
                                       float[][] joints,float[][] outline,float[] swordTip) {
        return new Rig2DProfile(UUID.randomUUID().toString(),projectId,
                sourceAssetId,black,transparent,joints,outline,swordTip);
    }

    public static Rig2DProfile create(String projectId,String sourceAssetId,boolean black,
            boolean transparent,float[][] joints,float[][] outline,float[] swordTip,
            float[] swordHilt,float[][] swordOutline) {
        return new Rig2DProfile(UUID.randomUUID().toString(),projectId,sourceAssetId,black,
                transparent,joints,outline,swordTip,swordHilt,swordOutline);
    }

    private static float[] optionalPoint(float[] value,String label) {
        if(value==null)return null;
        if(value.length!=2||!Float.isFinite(value[0])||!Float.isFinite(value[1])
                ||value[0]<0||value[0]>1||value[1]<0||value[1]>1)
            throw new IllegalArgumentException(label+" must be a source-image point in 0..1");
        return value.clone();
    }

    private static float[][] copyPoints(float[][] value,int min,int max,String label) {
        if(value==null||value.length<min||value.length>max)
            throw new IllegalArgumentException("Invalid "+label+" point count");
        float[][] result=new float[value.length][2];
        for(int i=0;i<value.length;i++) {
            if(value[i]==null||value[i].length!=2)
                throw new IllegalArgumentException("Each "+label+" point requires x,y");
            for(int c=0;c<2;c++) {
                float v=value[i][c];
                if(!Float.isFinite(v)||v<0||v>1)
                    throw new IllegalArgumentException("Rig "+label+" coordinates must be 0..1");
                result[i][c]=v;
            }
        }
        return result;
    }

    private static double polygonArea(float[][] pts){
        double result=0;
        for(int i=0;i<pts.length;i++){
            float[] a=pts[i],b=pts[(i+1)%pts.length];
            result+=(double)a[0]*b[1]-(double)a[1]*b[0];
        }
        return result*.5;
    }
    private static float[][] polygonPoints(float[][] input,String label) {
        float[][] copied=copyPoints(input,3,64,label);
        java.util.ArrayList<float[]> result=new java.util.ArrayList<>();
        for(float[] point:copied)if(result.isEmpty()||point[0]!=result.get(result.size()-1)[0]
                ||point[1]!=result.get(result.size()-1)[1])result.add(point);
        if(result.size()>1&&result.get(0)[0]==result.get(result.size()-1)[0]
                &&result.get(0)[1]==result.get(result.size()-1)[1])result.remove(result.size()-1);
        if(result.size()<3)throw new IllegalArgumentException(label+" has fewer than three distinct corners");
        return result.toArray(new float[0][]);
    }

    private static void validatePolygon(float[][] points,String label) {
        if(polygonArea(points)==0)throw new IllegalArgumentException(label+" has no area");
        for(int i=0;i<points.length;i++) {
            float[] a=points[i],b=points[(i+1)%points.length];
            if(a[0]==b[0]&&a[1]==b[1])throw new IllegalArgumentException(label+" has a repeated vertex");
            float[] c=points[(i+2)%points.length];
            if(side(a,b,c)==0&&((double)b[0]-a[0])*((double)c[0]-b[0])
                    +((double)b[1]-a[1])*((double)c[1]-b[1])<0)
                throw new IllegalArgumentException(label+" doubles back along an adjacent edge");
            for(int j=i+1;j<points.length;j++) {
                if(j==i+1||(i==0&&j==points.length-1))continue;
                if(intersects(a,b,points[j],points[(j+1)%points.length]))
                    throw new IllegalArgumentException(label+" self-intersects");
            }
        }
    }
    private static double side(float[] a,float[] b,float[] c) {
        return ((double)b[0]-a[0])*((double)c[1]-a[1])-((double)b[1]-a[1])*((double)c[0]-a[0]);
    }
    private static boolean onSegment(float[] a,float[] b,float[] p) {
        return p[0]>=Math.min(a[0],b[0])&&p[0]<=Math.max(a[0],b[0])
                &&p[1]>=Math.min(a[1],b[1])&&p[1]<=Math.max(a[1],b[1]);
    }
    private static boolean intersects(float[] a,float[] b,float[] c,float[] d) {
        double ac=side(a,b,c),ad=side(a,b,d),ca=side(c,d,a),cb=side(c,d,b);
        if(ac==0&&onSegment(a,b,c)||ad==0&&onSegment(a,b,d)||ca==0&&onSegment(c,d,a)||cb==0&&onSegment(c,d,b))return true;
        return (ac>0&&ad<0||ac<0&&ad>0)&&(ca>0&&cb<0||ca<0&&cb>0);
    }

    private static String checkedId(String text,String label) {
        if(text==null||!text.matches("[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException("Invalid "+label+" ID");
        return text;
    }

    public JSONObject toJson() throws Exception {
        JSONObject j=new JSONObject();
        j.put("version",version);
        j.put("id",id);
        j.put("projectId",projectId);
        j.put("sourceAssetId",sourceAssetId);
        j.put("black",black);
        j.put("transparentSource",transparentSource);
        if(sourceSha256!=null)j.put("sourceSha256",sourceSha256);
        j.put("joints",toPairs(joints));
        j.put("outline",toPairs(outline));
        if(swordTip!=null)j.put("swordTip",
                new JSONArray().put(swordTip[0]).put(swordTip[1]));
        if(swordHilt!=null)j.put("swordHilt",floatPair(swordHilt));
        if(swordOutline.length>0) {
            JSONArray mask=new JSONArray();for(float[] point:swordOutline)mask.put(floatPair(point));
            j.put("swordOutline",mask);
        }
        j.put("swordAttachment","swordHand");
        j.put("renderer","skinned-2d-mesh-v1");
        return j;
    }

    private static JSONArray toPairs(float[][] points) throws Exception {
        JSONArray a=new JSONArray();
        for(float[] v:points)a.put(new JSONArray().put(v[0]).put(v[1]));
        return a;
    }
    private static JSONArray floatPair(float[] point) {
        // put(float) selects put(double), exposing widening noise; retain Float's round-trip representation.
        return new JSONArray().put((Object)Float.valueOf(point[0])).put((Object)Float.valueOf(point[1]));
    }

    public static Rig2DProfile fromJson(JSONObject j) throws Exception {
        if(j==null||!(j.opt("version") instanceof Number)||((Number)j.opt("version")).doubleValue()!=1)
            throw new IllegalArgumentException("Unsupported actor rig format");
        for(String key:new String[]{"id","projectId","sourceAssetId"})
            if(!(j.opt(key) instanceof String))throw new IllegalArgumentException("Rig "+key+" must be a string");
        if(!(j.opt("black") instanceof Boolean)||j.has("transparentSource")&&!(j.opt("transparentSource") instanceof Boolean))
            throw new IllegalArgumentException("Rig ownership and transparency must be boolean");
        if(j.has("outline")&&j.optJSONArray("outline")==null)throw new IllegalArgumentException("outline must be an array");
        if(j.has("sourceSha256")&&!(j.opt("sourceSha256") instanceof String))
            throw new IllegalArgumentException("sourceSha256 must be a SHA-256 string");
        return new Rig2DProfile(
                j.getString("id"),j.getString("projectId"),
                j.getString("sourceAssetId"),j.getBoolean("black"),
                j.optBoolean("transparentSource",false),
                fromPairs(j.getJSONArray("joints"),JOINTS.length,"joints"),
                fromPairs(j.optJSONArray("outline"),64,"outline"),
                optionalJsonPoint(j,"swordTip"),optionalJsonPoint(j,"swordHilt"),
                j.has("swordOutline")?fromPairs(requiredArray(j,"swordOutline"),64,"sword outline"):null,
                j.has("sourceSha256")?j.getString("sourceSha256"):null);
    }

    private static float[] optionalJsonPoint(JSONObject object,String name) throws Exception {
        if(!object.has(name))return null;
        JSONArray point=requiredArray(object,name);
        if(point.length()!=2)throw new IllegalArgumentException("Sword tip requires [x,y]");
        return new float[]{coordinate(point,0),coordinate(point,1)};
    }

    private static JSONArray requiredArray(JSONObject json,String name) {
        JSONArray value=json.optJSONArray(name);
        if(value==null)throw new IllegalArgumentException(name+" must be an array");
        return value;
    }
    private static float coordinate(JSONArray point,int index) throws Exception {
        Object value=point.get(index);
        if(!(value instanceof Number))throw new IllegalArgumentException("Source coordinates must be numbers");
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number<0||number>1)throw new IllegalArgumentException("Source coordinates must be finite in 0..1");
        return(float)number;
    }
    private static float[][] fromPairs(JSONArray src,int max,String label) throws Exception {
        if(src==null)return new float[0][2];
        if(src.length()>max)throw new IllegalArgumentException("Too many "+label+" points");
        float[][] points=new float[src.length()][2];
        for(int i=0;i<src.length();i++){
            JSONArray p=src.getJSONArray(i);
            if(p.length()!=2)throw new IllegalArgumentException("Expected x,y rig point");
            points[i][0]=coordinate(p,0);
            points[i][1]=coordinate(p,1);
        }
        return points;
    }
}
