package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/**
 * UI presentation duration for already accepted, revision-checked native Sketch
 * strokes. This never delays source admission, writes an extra stroke, changes
 * frame clocks or invents a hand trajectory. Only the existing real replay
 * prefix and accepted native pen cursor consume these timings.
 */
public final class SketchHumanPacing {
    public static final String PREFS="videostudio_sketch_live_view_preferences";
    public static final String KEY="drawingReplaySpeed";
    public static final String FAST="fast",READABLE="readable",CINEMATIC="cinematic";
    private static final Set<String> MODES=Set.of(FAST,READABLE,CINEMATIC);
    public static final int MAX_VISIBLE_MS=750;
    private SketchHumanPacing(){}

    public static JSONObject capabilities()throws Exception{
        return new JSONObject().put("minimumNativeVersion","4.10.33")
                .put("modes",new JSONArray().put(FAST).put(READABLE).put(CINEMATIC))
                .put("maximumAdaptiveVisibleMilliseconds",MAX_VISIBLE_MS)
                .put("enabledOnlyForAutoJournalledNativeDrawing",true)
                .put("userOwnedLiveSessionSpeedUnchanged",true)
                .put("acceptedInkOnly",true)
                .put("sourceEditExecutionNotSlowed",true)
                .put("doesNotInventStrokes",true)
                .put("liveMethod","SketchLivePlayer real native hand prefix")
                .put("galleryAccess",false);
    }

    public static String requireMode(String value){
        if(!MODES.contains(value))
            throw new IllegalArgumentException("Use fast, readable or cinematic drawing playback");
        return value;
    }

    public static int durationMs(JSONObject operation,int nativeMinimumMs,String mode){
        requireMode(mode);
        if(operation==null||nativeMinimumMs<80||nativeMinimumMs>5000)
            throw new IllegalArgumentException("Accepted sketch operation and valid base replay duration required");
        if(FAST.equals(mode))return nativeMinimumMs;
        String type=operation.optString("type","");
        int count=0;
        switch(type){
            case "add_object":{
                JSONObject object=operation.optJSONObject("object");
                if(object==null)break;
                String kind=object.optString("kind","");
                if(!Set.of("stroke","eraser","line","path","polygon").contains(kind))break;
                JSONArray points=object.optJSONArray("points");
                JSONArray commands=object.optJSONArray("commands");
                count=points==null?(commands==null?0:commands.length()*2):points.length();
                break;
            }
            case "append_points": case "precision_contour":{
                JSONArray points=operation.optJSONArray("points");
                count=points==null?0:points.length();break;
            }
            case "paint_stroke":{
                JSONArray points=operation.optJSONArray("points");
                if(points==null&&operation.optJSONObject("stroke")!=null){
                    JSONObject request=operation.optJSONObject("stroke").optJSONObject("request");
                    points=request==null?null:request.optJSONArray("points");
                }
                count=points==null?0:points.length();break;
            }
            default:
                // Moving a playhead, applying a keyframe, or changing a layer is
                // an instantaneous control action, not an invented pen stroke.
                return nativeMinimumMs;
        }
        if(count<=0)return nativeMinimumMs;
        int complexity=Math.min(128,Math.max(1,count));
        int readable=(int)Math.round(120+42*Math.sqrt(complexity));
        int requested=CINEMATIC.equals(mode)?(int)Math.round(readable*1.65):readable;
        return Math.max(nativeMinimumMs,Math.min(MAX_VISIBLE_MS,requested));
    }
}
