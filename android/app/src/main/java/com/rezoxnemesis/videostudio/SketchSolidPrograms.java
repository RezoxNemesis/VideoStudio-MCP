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
    public static JSONObject study(String shape,int frames)throws Exception {
        if(!SketchSolidMesh.SHAPES.contains(shape))throw SketchDocument.bad("Unsupported study shape");if(frames<48||frames>1200)throw SketchDocument.bad("Studies need 48..1200 frames");
        if("pyramid".equals(shape)){
            JSONObject p=turn(frames).put("holdLast",true).put("mergeHolds",true).put("shape",shape).put("size",new JSONArray("[180,220,180]")).put("groundLock",true).put("rockingPivot",true).put("softShadow",true).put("lighting",true);JSONArray keys=new JSONArray();int count=32,end=(int)Math.round((frames-1)*.84);
            for(int n=0;n<count;n++){double t=n/(double)(count-1),fall=1-t;keys.put(key((int)Math.round(t*end),0,0,0,0,25,25*Math.cos(5*Math.PI*t)*fall*fall,1));}return p.put("keys",keys);
        }
        if("cone".equals(shape)){
            JSONObject p=turn(frames).put("holdLast",true).put("mergeHolds",true).put("shape",shape).put("size",new JSONArray("[180,220,180]")).put("groundLock",true).put("softShadow",true).put("lighting",true).put("colors",new JSONArray("[\"#FFF19445\",\"#FFFFE9B5\",\"#FF39B9AD\",\"#FF405C9C\",\"#FFFFD87A\",\"#FF7452A3\"]"));JSONArray keys=new JSONArray();int count=Math.min(32,frames);
            for(int n=0;n<count;n++){double t=n/(double)(count-1),fall=1-t;keys.put(key((int)Math.round(t*(frames-1)),0,0,0,12*Math.sin(6*Math.PI*t)*fall,900*(2*t-t*t),6*Math.cos(6*Math.PI*t)*fall,1));}return p.put("keys",keys);
        }
        if("cylinder".equals(shape)){
            JSONObject p=turn(frames).put("holdLast",true).put("mergeHolds",true).put("shape",shape).put("size",new JSONArray("[140,220,140]")).put("rollingRadius",70).put("groundLock",true).put("softShadow",true).put("lighting",true);JSONArray keys=new JSONArray();double[][] poses={{0,-300},{130,300},{179,240}};for(double[] v:poses)keys.put(key((int)Math.round(v[0]*(frames-1)/179),v[1],0,0,90,0,0,1).put("interpolation","smooth"));return p.put("keys",keys);
        }
        if("sphere".equals(shape)){
            JSONObject p=turn(frames).put("holdLast",true).put("mergeHolds",true).put("groundLock",true).put("softShadow",true).put("lighting",true).put("shape",shape).put("size",new JSONArray("[160,160,160]")).put("rollingRadius",80).put("scaleSpace","world").put("colors",new JSONArray("[\"#FFF2A94A\",\"#FFF7D488\",\"#FF39B9AD\",\"#FF74C7C0\",\"#FF405C9C\",\"#FF92A9D5\"]"));
            JSONArray keys=new JSONArray();double[][] poses={{0,-320},{70,200},{110,320},{179,-120}};for(double[] v:poses)keys.put(key((int)Math.round(v[0]*(frames-1)/179),v[1],0,0,0,20,0,1).put("interpolation","smooth"));return p.put("keys",keys);
        }
        JSONObject p=turn(frames).put("holdLast",true).put("mergeHolds",true).put("softShadow",true).put("lighting",true).put("preserveVolume",true).put("scaleSpace","world");
        double[][] poses={{0,-300,0,0,0,25,0,1,0},{8,-300,0,0,0,25,-4,.78,0},{14,-300,0,0,0,25,-5,1.08,240},{56,70,0,160,360,385,0,.65,0},{60,70,0,160,360,385,0,1.06,0},{66,70,0,160,360,385,0,1.1,130},{98,270,0,40,540,565,0,.72,0},{102,270,0,40,540,565,0,1.05,0},{108,270,0,40,540,565,0,1.06,60},{132,360,0,0,630,655,0,.8,0},{138,360,0,0,630,655,0,1.04,0},{146,360,0,0,630,662,3,1,0},{158,360,0,0,630,653,-1,1,0},{170,360,0,0,630,655,0,1,0},{179,360,0,0,630,655,0,1,0}};
        JSONArray keys=new JSONArray();int previous=-1;for(int n=0;n<poses.length;n++){double[] v=poses[n];int f=Math.max(previous+1,Math.min(frames-(poses.length-n),(int)Math.round(v[0]*(frames-1)/179)));previous=f;JSONObject k=key(f,v[1],v[2],v[3],v[4],v[5],v[6],v[7]).put("flightHeight",v[8]).put("scaleInterpolation","smooth").put("depthInterpolation","smooth").put("interpolation",v[8]>0?"linear":"smooth");keys.put(k);}return p.put("keys",keys);
    }
    private static JSONObject key(int frame,double x,double elevation,double depth,double pitch,double yaw,double roll,double sy)throws Exception{return new JSONObject().put("frame",frame).put("x",x).put("elevation",elevation).put("depth",depth).put("pitch",pitch).put("yaw",yaw).put("roll",roll).put("scaleY",sy).put("scaleX",1/Math.sqrt(sy)).put("scaleZ",1/Math.sqrt(sy));}
}
