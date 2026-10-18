package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Post-plane support for a fully kneeling preset; feet and fixed-bone IK stay native. */
final class SketchHumanKneelContact {
    private SketchHumanKneelContact(){}

    /** Returns a detached pose, changing only hipY. Call after the plane and overrides,
     * at full kneel strength only; an explicitly authored hip/root height takes priority.
     * The two pelvis widths share one solve; their tiny lateral difference is averaged.
     */
    static JSONObject apply(JSONObject pose,double sx,double sz)throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Human knee contact cancelled");
        if(pose==null||!Double.isFinite(sx)||!Double.isFinite(sz))throw SketchDocument.bad("Knee contact requires a pose and finite ground slopes");
        JSONObject result=SketchHumanFigure.validatePose(SketchDocument.copy(pose));
        if(sx==0&&sz==0)return result;
        double low=-140,high=-45,lo=error(result,low,sx,sz),hi=error(result,high,sx,sz);
        if(lo>0||hi<0)throw SketchDocument.bad("Knee contact cannot reach the ground within hipY -140..-45 without changing fixed bones or feet");
        for(int i=0;i<40;i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Human knee contact cancelled");
            double middle=(low+high)/2;
            if(error(result,middle,sx,sz)<0)low=middle;else high=middle;
        }
        return result.put("hipY",(low+high)/2);
    }

    private static double error(JSONObject pose,double hipY,double sx,double sz)throws Exception {
        JSONObject candidate=SketchDocument.copy(pose).put("hipY",hipY);double total=0;
        for(String build:new String[]{"slender","broad"}){
            SketchHumanFigure.Skeleton body=SketchHumanFigure.skeleton(new JSONObject().put("build",build),candidate);
            double[] knee=body.rightKnee;total+=knee[1]-sx*knee[0]-sz*knee[2]+9;
        }
        return total/2;
    }
}
