package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.Arrays;

/** Optional local finger flexions. Legacy fingers retain their original arithmetic path. */
final class SketchFingerJointAngles {
    static final String[] FINGERS={"index","middle","ring","little"};
    private static final double[] FACTORS={60,92,48},MAXIMUM={110,120,90};
    private static final int OFFSET=9;
    static final String CAPABILITY_JSON="{\"minimumNativeVersion\":\"4.10.16\",\"field\":\"poses[].fingerJointAngles\",\"fingers\":[\"index\",\"middle\",\"ring\",\"little\"],\"jointOrder\":[\"MCP\",\"PIP\",\"DIP\"],\"degreeLimits\":[[0,110],[0,120],[0,90]],\"interpolation\":\"resolved_endpoint_angles\",\"automaticContact\":false}";
    private SketchFingerJointAngles(){}
    static JSONObject capabilities()throws Exception{return new JSONObject(CAPABILITY_JSON);}
    static void validate(JSONObject pose)throws Exception {
        if(!pose.has("fingerJointAngles"))return;
        Object value=pose.get("fingerJointAngles");
        if(!(value instanceof JSONObject))throw SketchDocument.bad("Finger joint angles require a strict finger map");
        JSONObject map=(JSONObject)value;SketchDocument.fields(map,"index,middle,ring,little");
        if(map.length()<1||map.length()>4)throw SketchDocument.bad("Finger joint angles require 1..4 fingers");
        for(String finger:FINGERS)if(map.has(finger)){
            Object tuple=map.get(finger);if(!(tuple instanceof JSONArray)||((JSONArray)tuple).length()!=3)throw SketchDocument.bad("Finger joint angles require [MCP,PIP,DIP] degrees");
            for(int j=0;j<3;j++)SketchDocument.scalar(((JSONArray)tuple).get(j),0,MAXIMUM[j],finger+" joint degrees");
        }
    }
    private static int explicitMask(JSONArray poses)throws Exception {
        int mask=0;for(int i=0;i<poses.length();i++){JSONObject map=poses.getJSONObject(i).optJSONObject("fingerJointAngles");if(map!=null)for(int d=0;d<4;d++)if(map.has(FINGERS[d]))mask|=1<<d;}return mask;
    }
    private static double endpoint(JSONObject key,int d,int j,double[] neutral)throws Exception {
        JSONObject map=key.optJSONObject("fingerJointAngles");return map!=null&&map.has(FINGERS[d])?map.getJSONArray(FINGERS[d]).getDouble(j):FACTORS[j]*key.optDouble(FINGERS[d]+"Curl",neutral[d]);
    }
    /** Computed legacy-equivalent explicit input can retain the old scalar arithmetic too. */
    private static boolean legacyEquivalent(JSONArray poses,int d,double[] neutral)throws Exception {
        for(int i=0;i<poses.length();i++){JSONObject key=poses.getJSONObject(i);double curl=key.optDouble(FINGERS[d]+"Curl",neutral[d]);for(int j=0;j<3;j++)if(Double.doubleToLongBits(endpoint(key,d,j,neutral))!=Double.doubleToLongBits(FACTORS[j]*curl))return false;}return true;
    }
    static double[] resolve(JSONArray keys,JSONObject left,JSONObject right,double t,double[] legacy,double[] neutral)throws Exception {
        int mask=explicitMask(keys);if(mask==0)return legacy;
        double[] out=Arrays.copyOf(legacy,21);Arrays.fill(out,OFFSET,out.length,Double.NaN);
        for(int d=0;d<4;d++)if((mask&(1<<d))!=0&&!legacyEquivalent(keys,d,neutral))for(int j=0;j<3;j++){
            double a=endpoint(left,d,j,neutral),b=endpoint(right,d,j,neutral);out[OFFSET+d*3+j]=a+(b-a)*t;
        }
        // NaN is only an internal inactive-slot marker. It never enters geometry or JSON.
        return out;
    }
    static double increment(double[] pose,int finger,int joint){
        int slot=OFFSET+finger*3+joint;
        return pose.length>slot&&!Double.isNaN(pose[slot])?Math.toRadians(pose[slot]):Math.toRadians(FACTORS[joint]*pose[finger]);
    }
    static void sample(JSONArray source,double[] resolved,JSONObject frozen)throws Exception {
        int mask=explicitMask(source);if(mask==0)return;JSONObject map=new JSONObject();
        for(int d=0;d<4;d++)if((mask&(1<<d))!=0){JSONArray tuple=new JSONArray();for(int j=0;j<3;j++){double angle=resolved[OFFSET+d*3+j];tuple.put(Double.isNaN(angle)?FACTORS[j]*resolved[d]:angle);}map.put(FINGERS[d],tuple);}
        frozen.put("fingerJointAngles",map);
    }
}
