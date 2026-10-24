package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Set;

/** Exact flat transforms. x/y are fractions of the output frame, positive right/down. */
@UnstableApi
public final class StudioClipKeyframes implements MatrixTransformation {
    public static final int MAX_KEYS=64;
    public static final Set<String> EASINGS=Set.of("linear","ease_in","ease_out","ease_in_out","hold","cubic_bezier");
    private static final Set<String> FIELDS=Set.of("timeMs","x","y","scale","scaleX","scaleY","rotate","anchorX","anchorY","opacity","easing","bezier");
    public static final class Values {
        public final double x,y,scaleX,scaleY,rotate,anchorX,anchorY,opacity;
        Values(double x,double y,double sx,double sy,double rotate,double ax,double ay,double opacity){
            this.x=x;this.y=y;scaleX=sx;scaleY=sy;this.rotate=rotate;anchorX=ax;anchorY=ay;
            this.opacity=opacity;
        }
    }
    private static final class Key {
        final long time;final Values values;final String easing;final double[] bezier;
        Key(long time,Values values,String easing,double[] bezier){
            this.time=time;this.values=values;this.easing=easing;this.bezier=bezier;
        }
    }
    private final ArrayList<Key> keys;
    private final Values base;
    private final long clockOffsetMs;
    private final long presentationStartUs;
    private StudioClipKeyframes(ArrayList<Key> keys,Values base){this(keys,base,0);}
    private StudioClipKeyframes(ArrayList<Key> keys,Values base,long offset){this(keys,base,offset,0);}
    private StudioClipKeyframes(ArrayList<Key> keys,Values base,long offset,long presentationStartUs){
        if(presentationStartUs<0)throw new IllegalArgumentException("Presentation start must be nonnegative");
        this.keys=keys;this.base=base;clockOffsetMs=offset;this.presentationStartUs=presentationStartUs;
    }

    /** Render callback clock only. Authored/local sampling and split offsets stay unchanged. */
    public StudioClipKeyframes withPresentationStartUs(long startUs){
        return new StudioClipKeyframes(new ArrayList<>(keys),base,clockOffsetMs,startUs);
    }

    public static StudioClipKeyframes parse(JSONArray frames,long durationMs){
        return parse(frames,durationMs,new JSONObject());
    }
    public static StudioClipKeyframes fromEffects(JSONObject effects,long durationMs){
        if(effects==null)effects=new JSONObject();
        if(effects.has("zoom")&&!effects.has("scale")){
            try{effects=new JSONObject(effects.toString()).put("scale",effects.get("zoom"));}
            catch(Exception error){throw new IllegalArgumentException("Invalid legacy zoom",error);}
        }
        if(effects.has("keyframes")&&!(effects.opt("keyframes") instanceof JSONArray))
            throw new IllegalArgumentException("keyframes must be an array");
        long span=durationMs,offset=0;
        if(effects.has("studioKeyframeDurationMs")||effects.has("studioKeyframeOffsetMs")){
            span=integer(effects,"studioKeyframeDurationMs",1,86_400_000L);
            offset=integer(effects,"studioKeyframeOffsetMs",0,span);
            if(durationMs>span-offset+1)throw new IllegalArgumentException("Split keyframe clock exceeds its authored duration; replace or clear keys before extending this clip");
        }
        StudioClipKeyframes parsed=parse(readCompatibleKeys(effects.optJSONArray("keyframes"),span),span,effects);
        return new StudioClipKeyframes(parsed.keys,parsed.base,offset);
    }
    /** Detached canonical keys in their original authored clock; reading never rewrites a project. */
    public static JSONArray authoredKeys(JSONObject effects,long durationMs){
        fromEffects(effects,durationMs);
        if(effects==null)return new JSONArray();
        long span=effects.has("studioKeyframeDurationMs")?integer(effects,"studioKeyframeDurationMs",1,86_400_000L):durationMs;
        JSONArray keys=readCompatibleKeys(effects.optJSONArray("keyframes"),span);
        try{return keys==null?new JSONArray():new JSONArray(keys.toString());}
        catch(org.json.JSONException error){throw new IllegalArgumentException("Could not read authored keys",error);}
    }
    /** Legacy read adapter only. Canonical parse and new edit writes still require timeMs. */
    private static JSONArray readCompatibleKeys(JSONArray frames,long durationMs){
        if(frames==null||frames.length()==0)return frames;
        boolean normalized=true;
        for(int i=0;i<frames.length();i++){
            JSONObject key=frames.optJSONObject(i);
            if(key==null||!key.has("t")||key.has("timeMs")){normalized=false;break;}
        }
        if(!normalized)return frames;
        if(durationMs<=0||durationMs>86_400_000L)throw new IllegalArgumentException("Clip duration must be 1..86400000 ms");
        if(frames.length()>MAX_KEYS)throw new IllegalArgumentException("Maximum 64 transform keyframes");
        JSONArray mapped=new JSONArray();double previous=-1;long previousMs=-1;
        for(int i=0;i<frames.length();i++){
            JSONObject original=frames.optJSONObject(i);
            double t=number(original,"t",Double.NaN,0,1);
            if(t<=previous)throw new IllegalArgumentException("Legacy normalized t values must strictly increase in 0..1");
            previous=t;long time=Math.round(t*durationMs);
            if(time<=previousMs)throw new IllegalArgumentException("Legacy keyframes resolve to the same millisecond; increase duration or replace them with exact timeMs keys");
            previousMs=time;
            try{
                JSONObject key=new JSONObject(original.toString());key.remove("t");key.put("timeMs",time);
                if(key.has("rotation")){
                    if(key.has("rotate"))throw new IllegalArgumentException("Legacy keyframe has both rotation and rotate; keep one explicit rotation");
                    key.put("rotate",key.get("rotation"));key.remove("rotation");
                }
                mapped.put(key);
            }catch(org.json.JSONException error){throw new IllegalArgumentException("Could not read legacy keyframe",error);}
        }
        return mapped;
    }
    private static StudioClipKeyframes parse(JSONArray frames,long durationMs,JSONObject effects){
        if(durationMs<=0||durationMs>86_400_000L)throw new IllegalArgumentException("Clip duration must be 1..86400000 ms");
        Values base=values(effects,new Values(0,0,1,1,0,.5,.5,1));
        ArrayList<Key> keys=new ArrayList<>();
        if(frames==null)return new StudioClipKeyframes(keys,base);
        if(frames.length()>MAX_KEYS)throw new IllegalArgumentException("Maximum 64 transform keyframes");
        long previous=-1;
        for(int i=0;i<frames.length();i++){
            Object raw=frames.opt(i);if(!(raw instanceof JSONObject))throw new IllegalArgumentException("Each keyframe must be an object");
            JSONObject frame=(JSONObject)raw;
            java.util.Iterator<String> fields=frame.keys();while(fields.hasNext())if(!FIELDS.contains(fields.next()))
                throw new IllegalArgumentException("Unsupported keyframe field");
            long time=integer(frame,"timeMs",0,durationMs);
            if(time<=previous)throw new IllegalArgumentException("Keyframe timeMs values must strictly increase");
            previous=time;
            Object rawEase=frame.opt("easing");String easing=rawEase==null?"linear":rawEase instanceof String?(String)rawEase:"";
            if(!EASINGS.contains(easing))throw new IllegalArgumentException(
                    "Use linear, ease_in, ease_out, ease_in_out, hold or cubic_bezier");
            boolean custom="cubic_bezier".equals(easing);
            if(custom&&!(frame.opt("bezier") instanceof JSONArray))
                throw new IllegalArgumentException("cubic_bezier requires four control points");
            if(!custom&&frame.has("bezier"))
                throw new IllegalArgumentException("Bezier points require cubic_bezier easing");
            double[] handles=custom?StudioBezierEasing.validate(frame.getJSONArray("bezier")):null;
            keys.add(new Key(time,values(frame,base),easing,handles));
        }
        return new StudioClipKeyframes(keys,base);
    }
    private static Values values(JSONObject object,Values defaults){
        double uniform=number(object,"scale",Double.NaN,.1,8);
        double sx=number(object,"scaleX",Double.isNaN(uniform)?defaults.scaleX:uniform,.1,8);
        double sy=number(object,"scaleY",Double.isNaN(uniform)?defaults.scaleY:uniform,.1,8);
        return new Values(number(object,"x",defaults.x,-2,2),number(object,"y",defaults.y,-2,2),sx,sy,
                number(object,"rotate",defaults.rotate,-360,360),number(object,"anchorX",defaults.anchorX,0,1),
                number(object,"anchorY",defaults.anchorY,0,1),number(object,"opacity",defaults.opacity,0,1));
    }
    public static double number(JSONObject object,String name,double fallback,double low,double high){
        if(!object.has(name))return fallback;
        Object raw=object.opt(name);if(!(raw instanceof Number))throw new IllegalArgumentException(name+" must be a number");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value<low||value>high)throw new IllegalArgumentException(name+" must be finite in "+low+".."+high);
        return value;
    }
    public static long integer(JSONObject object,String name,long low,long high){
        double value=number(object,name,Double.NaN,low,high);
        if(!Double.isFinite(value)||value!=Math.rint(value))throw new IllegalArgumentException(name+" must be an integer");
        return (long)value;
    }
    public Values at(long timeMs){return sample((double)timeMs+clockOffsetMs);}
    public Values atUs(long timeUs){return sample(Math.max(0,timeUs)/1000.0+clockOffsetMs);}
    private Values sample(double timeMs){
        if(keys.isEmpty())return base;
        if(timeMs<=keys.get(0).time)return keys.get(0).values;
        for(int i=1;i<keys.size();i++){
            Key right=keys.get(i),left=keys.get(i-1);
            if(timeMs>=right.time)continue;
            double p=(timeMs-left.time)/(double)(right.time-left.time);
            switch(left.easing){
                case "hold":p=0;break;
                case "ease_in":p*=p;break;
                case "ease_out":p=1-(1-p)*(1-p);break;
                case "ease_in_out":p=p*p*(3-2*p);break;
                case "cubic_bezier":p=StudioBezierEasing.evaluate(p,left.bezier);break;
            }
            Values a=left.values,b=right.values;
            return new Values(mix(a.x,b.x,p),mix(a.y,b.y,p),mix(a.scaleX,b.scaleX,p),mix(a.scaleY,b.scaleY,p),
                    mix(a.rotate,b.rotate,p),mix(a.anchorX,b.anchorX,p),mix(a.anchorY,b.anchorY,p),mix(a.opacity,b.opacity,p));
        }
        return keys.get(keys.size()-1).values;
    }
    private static double mix(double a,double b,double p){return a+(b-a)*p;}
    @Override public Size configure(int width,int height){return new Size(width,height);}
    @Override public Matrix getMatrix(long presentationTimeUs){
        Values value=atUs(StudioEffectClock.localUs(presentationTimeUs,presentationStartUs));
        float ax=(float)(2*value.anchorX-1),ay=(float)(1-2*value.anchorY);
        Matrix matrix=new Matrix();matrix.postTranslate(-ax,-ay);
        matrix.postScale((float)value.scaleX,(float)value.scaleY);matrix.postRotate((float)value.rotate);
        matrix.postTranslate(ax+(float)(2*value.x),ay-(float)(2*value.y));return matrix;
    }
}
