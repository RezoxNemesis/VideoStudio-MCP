package com.rezoxnemesis.videostudio;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded time curve for editable drawings, prepared rigs, and cameras. */
public final class SketchEasing {
    public static final Set<String> MODES=Set.of("linear","hold","smooth","ease_in","ease_out","ease_in_out","bezier");
    private SketchEasing(){}

    static void validate(JSONObject key)throws Exception {
        Object raw=key.opt("interpolation");
        String mode=raw==null?"linear":raw instanceof String?(String)raw:"";
        if(!MODES.contains(mode))throw SketchDocument.bad("Unsupported animation timing curve");
        if("bezier".equals(mode)){
            if(!(key.opt("curve") instanceof JSONArray)||key.getJSONArray("curve").length()!=4)
                throw SketchDocument.bad("Bezier timing requires four control values");
            JSONArray curve=key.getJSONArray("curve");
            for(int i=0;i<4;i++)SketchDocument.scalar(curve.get(i),0,1,"Bezier control");
        }else if(key.has("curve"))throw SketchDocument.bad("Explicit curve controls require Bezier timing");
    }

    static double sample(String mode,JSONArray curve,double time)throws Exception {
        if(!Double.isFinite(time))throw SketchDocument.bad("Animation time must be finite");
        double t=Math.max(0,Math.min(1,time));
        switch(mode){
            case "linear":return t;
            case "hold":return 0;
            case "smooth":return t*t*(3-2*t);
            case "ease_in":return t*t;
            case "ease_out":return t*(2-t);
            case "ease_in_out":return t<.5?2*t*t:1-2*(1-t)*(1-t);
            case "bezier":
                if(t==0||t==1)return t;
                // Invert X(t) first. Bisection also handles flat derivatives safely.
                double lo=0,hi=1,x1=curve.getDouble(0),x2=curve.getDouble(2);
                for(int i=0;i<40;i++){
                    double mid=(lo+hi)/2;if(cubic(mid,x1,x2)<t)lo=mid;else hi=mid;
                }
                return cubic((lo+hi)/2,curve.getDouble(1),curve.getDouble(3));
            default:throw SketchDocument.bad("Unsupported animation timing curve");
        }
    }
    private static double cubic(double t,double first,double second){
        double s=1-t;return 3*s*s*t*first+3*s*t*t*second+t*t*t;
    }
}
