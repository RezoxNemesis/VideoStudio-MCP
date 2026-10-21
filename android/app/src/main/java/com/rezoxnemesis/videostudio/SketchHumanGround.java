package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Native stage-floor placement and model-space ground planes shared by human authoring. */
final class SketchHumanGround {
    private static final double MAX_LEG=175.5,MAX_HIP_LOWERING=16;
    private SketchHumanGround(){}

    /** Returns placement controls only; never changes the document, human settings or clock. */
    static JSONObject placement(JSONObject doc,JSONObject settings,String location)throws Exception {
        if(doc==null||settings==null)throw SketchDocument.bad("Ground placement requires a document and human settings");
        int width=(int)SketchDocument.integer(doc,"width",256,2048),height=(int)SketchDocument.integer(doc,"height",256,2048);
        if((width&1)!=0||(height&1)!=0)throw SketchDocument.bad("Ground placement requires even canvas dimensions");
        NativeFrameClock.requireFps((int)SketchDocument.integer(doc,"fps",24,60));
        SketchDocument.integer(doc,"frameCount",1,SketchDocument.MAX_FRAMES);
        JSONObject s=SketchHumanFigure.settings(SketchDocument.copy(settings));
        double fit=Math.min(width/1280d,height/720d),offsetX=(width-1280*fit)/2,offsetY=(height-720*fit)/2;
        double refX=(s.getDouble("x")*width-offsetX)/(1280*fit);
        double floorY=(offsetY+SketchStoryStage.walkwayY(location,refX)*fit)/height;
        if(!Double.isFinite(floorY)||floorY<0||floorY>1)throw SketchDocument.bad("Native scene floor must remain within the canvas");
        double slope="bridge".equals(location)?-1d/12:0;
        double[] x=SketchHumanFigure.project(s,new double[]{1,0,0});
        double[] y=SketchHumanFigure.project(s,new double[]{0,1,0});
        double[] z=SketchHumanFigure.project(s,new double[]{0,0,1});
        if(!Double.isFinite(y[1])||Math.abs(y[1])<1e-9)throw SketchDocument.bad("Human view cannot resolve a ground plane");
        double sx=(slope*x[0]-x[1])/y[1],sz=(slope*z[0]-z[1])/y[1];
        return new JSONObject().put("floorY",floorY)
                .put("groundSlopeX",Math.abs(sx)<1e-12?0:sx)
                .put("groundSlope",Math.abs(sz)<1e-12?0:sz);
    }

    /** Adds the plane to authored heights, leaving lift/jump/swing offsets intact.
     * Call after motion strength. Only walk/run callers should request reach stabilization.
     * Zero slopes preserve the original pose, including its authored hip height.
     */
    static JSONObject applyPlane(JSONObject pose,double sx,double sz,boolean stabilizeLegReach)throws Exception {
        if(pose==null||!Double.isFinite(sx)||!Double.isFinite(sz))throw SketchDocument.bad("Ground plane requires a pose and finite slopes");
        JSONObject p=SketchHumanFigure.validatePose(SketchDocument.copy(pose));
        if(sx==0&&sz==0)return p;
        p.put("rootY",p.getDouble("rootY")+sx*p.getDouble("rootX")+sz*p.getDouble("rootZ"));
        for(String side:new String[]{"Left","Right"})p.put("foot"+side+"Y",p.getDouble("foot"+side+"Y")+sx*p.getDouble("foot"+side+"X")+sz*p.getDouble("foot"+side+"Z"));
        if(stabilizeLegReach){
            double hipX=p.getDouble("rootX")+p.getDouble("hipX"),hipZ=p.getDouble("rootZ")+p.getDouble("hipZ");
            double hipY=p.getDouble("rootY")+p.getDouble("hipY"),lowering=0;
            for(String side:new String[]{"Left","Right"}){
                // Each leg uses its own hip; cover both slender and broad pelvis widths.
                double sign=side.equals("Left")?-1:1,footX=p.getDouble("foot"+side+"X");
                double dx=Math.max(Math.abs(footX-(hipX+sign*18)),Math.abs(footX-(hipX+sign*23)));
                double dz=p.getDouble("foot"+side+"Z")-hipZ;
                double remaining=MAX_LEG*MAX_LEG-dx*dx-dz*dz;
                if(remaining<=0)throw SketchDocument.bad("Ground plane leg reach cannot be stabilized without stretching");
                double ankleY=p.getDouble("foot"+side+"Y")-14;
                lowering=Math.max(lowering,ankleY-hipY-Math.sqrt(remaining));
            }
            if(lowering>MAX_HIP_LOWERING)throw SketchDocument.bad("Ground plane needs more than 16 model pixels of hip lowering; adjust the pose or slope");
            p.put("hipY",p.getDouble("hipY")+lowering);
        }
        return SketchHumanFigure.validatePose(p);
    }
}
