package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Exact cubic Bezier temporal easing for native animation keyframes.
 * X is monotonic because both temporal handles are constrained to [0,1].
 * Evaluates a strictly bounded per-frame curve without allocation.
 */
public final class StudioBezierEasing {
    public static final int HANDLE_COUNT=4;
    public static final String PRESET_FILM="0.22,1.0,0.36,1.0";
    private StudioBezierEasing(){}

    public static double[] validate(JSONArray handles) {
        if(handles==null||handles.length()!=HANDLE_COUNT)
            throw new IllegalArgumentException("Bezier needs exactly four numeric handles x1,y1,x2,y2");
        double[] h=new double[HANDLE_COUNT];
        for(int i=0;i<HANDLE_COUNT;i++){
            Object value=handles.opt(i);
            if(!(value instanceof Number))
                throw new IllegalArgumentException("Bezier control coordinates must be numbers");
            h[i]=((Number)value).doubleValue();
            if(!Double.isFinite(h[i]))
                throw new IllegalArgumentException("Bezier cannot contain NaN or infinity");
            if((i==0||i==2)&&(h[i]<0||h[i]>1))
                throw new IllegalArgumentException("Bezier time handle X must be in [0,1]");
            if((i==1||i==3)&&(h[i]<-.5||h[i]>1.5))
                throw new IllegalArgumentException("Bezier velocity handle Y must be in [-0.5,1.5]");
        }
        return h;
    }

    public static JSONArray fromText(String commaSeparated) {
        if(commaSeparated==null)throw new IllegalArgumentException("Bezier text required");
        String[] tokens=commaSeparated.trim().split(",");
        if(tokens.length!=4)throw new IllegalArgumentException("Use x1,y1,x2,y2");
        JSONArray values=new JSONArray();
        try{
            for(String token:tokens){
                double value=Double.parseDouble(token.trim());
                if(!Double.isFinite(value))throw new NumberFormatException("Nonfinite");
                values.put(value);
            }
        }catch(NumberFormatException error){
            throw new IllegalArgumentException("Bezier handles must be finite decimal numbers",error);
        }
        validate(values);
        return values;
    }

    public static double evaluate(double progress,double[] handles){
        if(handles==null||handles.length!=4)throw new IllegalArgumentException("Four Bezier handles required");
        if(!Double.isFinite(progress))throw new IllegalArgumentException("Finite progress required");
        if(progress<=0)return 0;
        if(progress>=1)return 1;
        // Bisection is deterministic on Android CPU and is less vulnerable
        // to unstable tangents than naïve Newton steps near control end-points.
        double lo=0,hi=1,t=.5;
        for(int i=0;i<20;i++){
            t=(lo+hi)*.5;
            double x=cubic(t,handles[0],handles[2]);
            if(x<progress)lo=t;else hi=t;
        }
        return Math.max(0,Math.min(1,cubic(t,handles[1],handles[3])));
    }

    private static double cubic(double t,double a,double b){
        double one=1-t;
        return 3*one*one*t*a+3*one*t*t*b+t*t*t;
    }
}
