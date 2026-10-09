package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Iterator;

/** Translate the established v3 tool envelope into operations with real providers. */
final class LegacyEditorAdapter {
    private final EditorEngine editor;
    LegacyEditorAdapter(ProjectStore store){editor=new EditorEngine(store);}

    ProjectStore.Project applyTool(String projectId,JSONObject parameters)throws Exception{
        Object rawIndex=parameters.opt("clipIndex");
        int index=(int)integer(rawIndex==null?0:rawIndex,"clipIndex",0,Integer.MAX_VALUE);
        String tool=string(parameters,"tool","effect");
        if(tool.isEmpty()||tool.length()>80)throw new IllegalArgumentException("A bounded tool name is required");
        Object rawSettings=parameters.opt("settings");
        if(rawSettings!=null&&!(rawSettings instanceof JSONObject))throw new IllegalArgumentException("settings must be an object");
        JSONObject settings=rawSettings==null?new JSONObject():new JSONObject(rawSettings.toString());
        JSONObject request=new JSONObject().put("tool",tool).put("clipIndex",index).put("settings",settings);
        long revision=parameters.has("expectedRevision")?integer(parameters.get("expectedRevision"),"expectedRevision",1,9007199254740991L):-1;
        String commandId=string(parameters,"_mcpCommandId",string(parameters,"commandId",""));
        return editor.executeLegacyMappedBatch(projectId,revision,commandId,"apply_tool",request,p->map(p,index,tool,settings));
    }

    private JSONArray map(ProjectStore.Project project,int index,String tool,JSONObject settings)throws Exception{
        if(index>=project.clips.size())throw new IllegalArgumentException("Clip not found");
        ProjectStore.Clip clip=project.clips.get(index);JSONArray operations=new JSONArray();
        switch(tool){
            case "trim":
                keys(settings,"inMs","outMs","ripple");
                add(operations,"trim_clip",args(clip).put("inMs",integer(settings.opt("inMs")==null?clip.inMs:settings.get("inMs"),"inMs",0,9007199254740991L))
                        .put("outMs",integer(settings.opt("outMs")==null?clip.outMs:settings.get("outMs"),"outMs",1,9007199254740991L)).put("ripple",bool(settings,"ripple",false)));break;
            case "speed": case "slow_motion":
                keys(settings,"speed","ripple");add(operations,"set_speed",args(clip).put("speed",number(settings,"speed",.5)).put("ripple",bool(settings,"ripple",true)));break;
            case "volume":
                keys(settings,"volume");property(operations,clip,"volume",number(settings,"volume",1));break;
            case "blur":
                keys(settings,"sigma");property(operations,clip,"blur",number(settings,"sigma",4));break;
            case "transform":
                keys(settings,"x","y","scale","scaleX","scaleY","rotate","opacity","anchorX","anchorY");
                if(settings.length()==0)throw new IllegalArgumentException("Choose a transform property");
                for(Iterator<String> it=settings.keys();it.hasNext();){String key=it.next();property(operations,clip,key,number(settings,key,0));}break;
            case "color": case "effect":
                keys(settings,"preset","brightness","contrast","saturation","lightness","blur");
                if(settings.has("preset")||settings.length()==0){
                    String preset=string(settings,"preset","cinematic");
                    if("effect".equals(tool))add(operations,"set_effect_preset",args(clip).put("preset",preset));
                    else style(operations,clip,"colorPreset",preset);
                }
                for(String key:new String[]{"brightness","contrast","saturation","lightness","blur"})if(settings.has(key)){
                    String property="saturation".equals(key)?"saturationAdjust":"lightness".equals(key)?"lightnessAdjust":key;
                    property(operations,clip,property,number(settings,key,0));
                }break;
            case "motion":
                keys(settings,"preset","ease");
                if(settings.has("ease")&&!"easeInOut".equals(string(settings,"ease","")))throw new IllegalArgumentException("Motion presets use their built-in easing; edit property keyframes for other curves");
                style(operations,clip,"motionPreset",string(settings,"preset","push_in"));break;
            case "font":
                keys(settings,"family");style(operations,clip,"fontFamily",string(settings,"family","sans-serif-medium"));break;
            case "text_animation":
                keys(settings,"preset");style(operations,clip,"textAnimation",string(settings,"preset","fade_up"));break;
            case "title":
                keys(settings,"text","font","animation");String text=string(settings,"text","");
                if(text.length()>5000)throw new IllegalArgumentException("Title exceeds 5000 characters");
                add(operations,"set_title",args(clip).put("text",text));
                if(settings.has("font"))style(operations,clip,"fontFamily",string(settings,"font",""));
                if(settings.has("animation"))style(operations,clip,"textAnimation",string(settings,"animation",""));break;
            case "green_screen": {
                keys(settings,"enabled","color","tolerance","softness","spill");JSONObject composite=composite(clip);
                composite.put("chromaKey",bool(settings,"enabled",true)).put("chromaColor",string(settings,"color","#00FF00"))
                        .put("chromaTolerance",number(settings,"tolerance",.18)).put("spillSuppression",number(settings,"spill",.35));
                if(settings.has("softness"))composite.put("chromaSoftness",number(settings,"softness",.08));
                add(operations,"set_composite_effects",args(clip).put("settings",composite));break;
            }
            case "mask": {
                keys(settings,"shape","feather","centerX","centerY","width","height","invert","cornerRadius");JSONObject composite=composite(clip);
                composite.put("mask",string(settings,"shape","rounded_rect")).put("maskFeather",number(settings,"feather",.08));
                String[] legacy={"centerX","centerY","width","height","cornerRadius"},nativeKeys={"maskCenterX","maskCenterY","maskWidth","maskHeight","maskCornerRadius"};
                for(int i=0;i<legacy.length;i++)if(settings.has(legacy[i]))composite.put(nativeKeys[i],number(settings,legacy[i],0));
                if(settings.has("invert"))composite.put("maskInvert",bool(settings,"invert",false));
                add(operations,"set_composite_effects",args(clip).put("settings",composite));break;
            }
            case "keyframes": {
                keys(settings,"keyframes");Object raw=settings.opt("keyframes");
                if(!(raw instanceof JSONArray))throw new IllegalArgumentException("keyframes must be an array");JSONArray frames=(JSONArray)raw;
                if(clip.keyframes.length()+frames.length()>100)throw new IllegalArgumentException("Use shared editor batches for more than 100 keyframe changes");
                for(int i=0;i<clip.keyframes.length();i++){
                    JSONObject frame=clip.keyframes.getJSONObject(i);add(operations,"remove_keyframe",args(clip).put("property",frame.getString("property")).put("timeMs",frame.getLong("timeMs")));
                }
                for(int i=0;i<frames.length();i++){
                    Object value=frames.get(i);if(!(value instanceof JSONObject))throw new IllegalArgumentException("Each keyframe must be an object");JSONObject frame=(JSONObject)value;
                    keys(frame,"property","timeMs","value","easing");
                    add(operations,"set_keyframe",args(clip).put("property",string(frame,"property","")).put("timeMs",integer(frame.opt("timeMs"),"timeMs",0,9007199254740991L))
                            .put("value",number(frame,"value",Double.NaN)).put("easing",string(frame,"easing","linear")));
                }
                if(operations.length()==0)throw new IllegalArgumentException("There are no keyframes to change");break;
            }
            default:throw new IllegalArgumentException("This legacy tool has no verified provider: "+tool+". Use the shared editor schema for available operations.");
        }
        return operations;
    }
    private static JSONObject composite(ProjectStore.Clip clip)throws Exception{
        JSONObject result=new JSONObject();for(String key:ClipCompositeSettings.KEYS)if(clip.effects.has(key))result.put(key,clip.effects.get(key));return result;
    }
    private static JSONObject args(ProjectStore.Clip clip)throws Exception{return new JSONObject().put("clipId",clip.id);}
    private static void add(JSONArray operations,String operation,JSONObject args)throws Exception{operations.put(new JSONObject().put("operation",operation).put("args",args));}
    private static void property(JSONArray operations,ProjectStore.Clip clip,String property,double value)throws Exception{add(operations,"set_property",args(clip).put("property",property).put("value",value));}
    private static void style(JSONArray operations,ProjectStore.Clip clip,String key,String value)throws Exception{add(operations,"set_creator_style",args(clip).put("settings",new JSONObject().put(key,value)));}
    private static void keys(JSONObject settings,String... permitted){
        for(Iterator<String> it=settings.keys();it.hasNext();){String key=it.next();if(!Arrays.asList(permitted).contains(key))throw new IllegalArgumentException("Unsupported tool setting: "+key);}
    }
    private static String string(JSONObject object,String key,String fallback){
        if(!object.has(key))return fallback;Object value=object.opt(key);if(!(value instanceof String))throw new IllegalArgumentException(key+" must be a string");return (String)value;
    }
    private static double number(JSONObject object,String key,double fallback){
        Object value=object.has(key)?object.opt(key):fallback;
        if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException(key+" must be a finite number");return ((Number)value).doubleValue();
    }
    private static long integer(Object value,String key,long min,long max){
        if(!(value instanceof Number))throw new IllegalArgumentException(key+" must be an integer");double number=((Number)value).doubleValue();long whole=((Number)value).longValue();
        if(!Double.isFinite(number)||number!=whole||whole<min||whole>max)throw new IllegalArgumentException(key+" is out of range");return whole;
    }
    private static boolean bool(JSONObject object,String key,boolean fallback){
        if(!object.has(key))return fallback;Object value=object.opt(key);if(!(value instanceof Boolean))throw new IllegalArgumentException(key+" must be a boolean");return (Boolean)value;
    }
}
