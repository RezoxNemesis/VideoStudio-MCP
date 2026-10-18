package com.rezoxnemesis.videostudio;
import org.json.*;

/** Authored parameter presets for the native construction tool, never measured reference motion. */
public final class SketchSolidPrograms {
    private SketchSolidPrograms(){}
    public static JSONObject turn(int frames)throws Exception {
        JSONObject program=new JSONObject().put("size",new JSONArray("[140,140,140]")).put("camera",new JSONObject("{\"focal\":900,\"distance\":1000,\"height\":300,\"horizon\":0.2}")).put("ground",true).put("strokePx",3).put("ink","#FF142637");
        JSONArray keys=new JSONArray().put(key(0,0,0,0,0,0,0,1));if(frames>1)keys.put(key(frames-1,0,0,0,0,360,0,1));return program.put("keys",keys);
    }
    public static JSONObject bounce(int frames)throws Exception {
        if(frames<48||frames>1200)throw SketchDocument.bad("The bounce study uses 48..1200 frames");
        JSONObject p=turn(frames);JSONArray keys=new JSONArray();
        double[][] poses={
            {0,-260,0,0,0,25,0,1},{12,-260,0,0,0,25,0,.72},{18,-260,0,0,-10,25,-12,1.12},
            {42,-80,280,700,180,205,35,1},{68,160,0,0,360,385,0,.67},{72,160,0,0,360,385,0,1.08},{78,160,0,0,360,385,0,1.12},
            {100,280,160,260,450,475,-12,1},{120,340,0,0,540,565,0,.72},{124,340,0,0,540,565,0,1.05},{130,340,0,0,540,565,0,1.08},
            {146,210,85,100,585,610,15,1},{162,80,0,0,630,655,0,.82},{166,80,0,0,630,655,0,1.04},{180,80,0,0,630,665,0,1},{210,80,0,0,630,655,0,1},{239,80,0,0,630,655,0,1}};
        int previous=-1;for(int index=0;index<poses.length;index++){double[] pose=poses[index];int frame=Math.max(previous+1,Math.min(frames-(poses.length-index),(int)Math.round(pose[0]*(frames-1)/239)));previous=frame;JSONObject k=key(frame,pose[1],pose[2],pose[3],pose[4],pose[5],pose[6],pose[7]);int f=(int)pose[0];k.put("interpolation",f>=162?"smooth":"linear").put("depthInterpolation","smooth");if(f==18||f==78||f==130)k.put("elevationInterpolation","ease_out");if(f==42||f==100||f==146)k.put("elevationInterpolation","ease_in");keys.put(k);}
        return p.put("keys",keys);
    }
    private static JSONObject key(int frame,double x,double elevation,double depth,double pitch,double yaw,double roll,double sy)throws Exception{return new JSONObject().put("frame",frame).put("x",x).put("elevation",elevation).put("depth",depth).put("pitch",pitch).put("yaw",yaw).put("roll",roll).put("scaleY",sy).put("scaleX",1/Math.sqrt(sy)).put("scaleZ",1/Math.sqrt(sy));}
}
