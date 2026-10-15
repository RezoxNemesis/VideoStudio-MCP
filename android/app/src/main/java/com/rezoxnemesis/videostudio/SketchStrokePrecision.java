package com.rezoxnemesis.videostudio;

import org.json.JSONArray;

/**
 * Lightweight adaptive pencil stabilizer. Unlike cumulative exponential lag,
 * speed and corner detection preserve hard details and accurate long strokes.
 * Input points never leave the document's normalized coordinate system.
 */
public final class SketchStrokePrecision {
    private SketchStrokePrecision() {}
    public static double[] filter(JSONArray path,double rawX,double rawY,double strength,int width,int height)throws Exception {
        if(!Double.isFinite(rawX)||!Double.isFinite(rawY)||!Double.isFinite(strength))
            throw SketchDocument.bad("The pen location and stabilisation must be finite");
        if(width<=0||height<=0||strength<0||strength>.9)
            throw SketchDocument.bad("Invalid precision stroke configuration");
        if(path==null||path.length()==0||strength==0)return new double[]{rawX,rawY};
        JSONArray last=path.getJSONArray(path.length()-1);
        double oldX=last.getDouble(0),oldY=last.getDouble(1);
        double speed=Math.hypot((rawX-oldX)*width,(rawY-oldY)*height);
        // Slow jitter is damped; rapid intentional gestures catch up immediately.
        double adaptive=strength/(1+speed/8.0);
        if(path.length()>1){
            JSONArray before=path.getJSONArray(path.length()-2);
            double ax=(oldX-before.getDouble(0))*width,ay=(oldY-before.getDouble(1))*height;
            double bx=(rawX-oldX)*width,by=(rawY-oldY)*height;
            double al=Math.hypot(ax,ay),bl=Math.hypot(bx,by);
            if(al>1.5&&bl>1.5){
                double dot=(ax*bx+ay*by)/(al*bl);
                // A sharp corner is an intentional change of direction.
                if(dot<.34)adaptive=0;
            }
        }
        return new double[]{Math.max(0,Math.min(1,oldX*adaptive+rawX*(1-adaptive))),
                Math.max(0,Math.min(1,oldY*adaptive+rawY*(1-adaptive)))};
    }
}
