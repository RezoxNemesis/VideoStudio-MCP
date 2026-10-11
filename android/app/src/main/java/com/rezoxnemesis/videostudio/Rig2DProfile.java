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
    public final int version;

    public Rig2DProfile(String id,String projectId,String assetId,boolean black,
                        boolean transparentSource,float[][] joints,float[][] outline) {
        this.version=1;
        this.id=checkedId(id,"profile");
        this.projectId=checkedId(projectId,"project");
        this.sourceAssetId=checkedId(assetId,"source asset");
        this.black=black;
        this.transparentSource=transparentSource;
        this.joints=copyPoints(joints,JOINTS.length,JOINTS.length,"joints");
        int count=outline==null?0:outline.length;
        if(!transparentSource && count<3)
            throw new IllegalArgumentException("Mark the actor outline or use a transparent sprite");
        if(count>64)throw new IllegalArgumentException("Actor outline exceeds 64 points");
        this.outline=count==0?new float[0][2]:copyPoints(outline,3,64,"outline");
        // Reject zero-length limbs that would make skinning undefined.
        for(int[] bone:BONES) {
            float[] a=this.joints[bone[0]], b=this.joints[bone[1]];
            if(Math.hypot(a[0]-b[0],a[1]-b[1])<.002)
                throw new IllegalArgumentException("Zero length joint segment: "+
                        JOINTS[bone[0]]+" to "+JOINTS[bone[1]]);
        }
        if(count>=3 && Math.abs(polygonArea(this.outline))<.0001)
            throw new IllegalArgumentException("Actor mask outline has no area");
    }

    public static Rig2DProfile create(String projectId,String sourceAssetId,
                                       boolean black,boolean transparent,
                                       float[][] joints,float[][] outline) {
        return new Rig2DProfile(UUID.randomUUID().toString(),projectId,
                sourceAssetId,black,transparent,joints,outline);
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
            result+=a[0]*b[1]-a[1]*b[0];
        }
        return result*.5;
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
        j.put("joints",toPairs(joints));
        j.put("outline",toPairs(outline));
        j.put("swordAttachment","swordHand");
        j.put("renderer","skinned-2d-mesh-v1");
        return j;
    }

    private static JSONArray toPairs(float[][] points) throws Exception {
        JSONArray a=new JSONArray();
        for(float[] v:points)a.put(new JSONArray().put(v[0]).put(v[1]));
        return a;
    }

    public static Rig2DProfile fromJson(JSONObject j) throws Exception {
        if(j==null||j.optInt("version",0)!=1)
            throw new IllegalArgumentException("Unsupported actor rig format");
        return new Rig2DProfile(
                j.getString("id"),j.getString("projectId"),
                j.getString("sourceAssetId"),j.getBoolean("black"),
                j.optBoolean("transparentSource",false),
                fromPairs(j.getJSONArray("joints")),
                fromPairs(j.optJSONArray("outline")));
    }

    private static float[][] fromPairs(JSONArray src) throws Exception {
        if(src==null)return new float[0][2];
        float[][] points=new float[src.length()][2];
        for(int i=0;i<src.length();i++){
            JSONArray p=src.getJSONArray(i);
            if(p.length()!=2)throw new IllegalArgumentException("Expected x,y rig point");
            points[i][0]=(float)p.getDouble(0);
            points[i][1]=(float)p.getDouble(1);
        }
        return points;
    }
}
