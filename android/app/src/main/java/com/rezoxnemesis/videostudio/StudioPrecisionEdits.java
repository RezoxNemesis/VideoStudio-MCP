package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Set;

/** Shared phone/MCP operation facade. A whole batch validates before one guarded SQLite save. */
public final class StudioPrecisionEdits {
    private StudioPrecisionEdits(){}
    private static final Set<String> OP_FIELDS=Set.of("clipId","set","keyframes","keyframeEdits","clearKeyframes","resetColor","resetTransform");
    private static final Set<String> NUMBERS=Set.of("inMs","outMs","speed","volume","audioPan","audioFadeInMs","audioFadeOutMs","brightness","contrast","saturationAdjust","lightnessAdjust","blur","scale","scaleX","scaleY","rotate","x","y","anchorX","anchorY","opacity",
            "chromaTolerance","chromaFeather","maskLeft","maskTop","maskRight","maskBottom","maskFeather","glowStrength","glowRadiusPx","glowThreshold");
    private static final Set<String> TEXT=Set.of("motionPreset","colorPreset","effectPreset","title","chromaColor","maskShape");
    public static final Set<String> MOTIONS=Set.of("none","push_in","pull_out","pan_left","pan_right","pan_up","pan_down","drift","float","orbit","micro_shake","handheld","impact_shake","snap_zoom","zoom_punch","tilt","roll","hero_reveal","ken_burns");
    public static final Set<String> COLORS=Set.of("none","cinematic","teal_orange","warm_film","cool_night","noir","high_contrast","soft_portrait","matte","golden_hour","cyberpunk");
    public static final Set<String> EFFECTS=Set.of("none","gaussian_blur","dream");

    public static JSONObject apply(Context context,ProjectStore store,JSONObject request)throws Exception{
        if(store==null||request==null)throw new IllegalArgumentException("Project store and precision request are required");
        if(!(request.opt("projectId") instanceof String)||request.getString("projectId").isEmpty())
            throw new IllegalArgumentException("projectId is required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        Object raw=request.opt("operations");if(!(raw instanceof JSONArray))throw new IllegalArgumentException("operations must be an array");
        JSONArray operations=(JSONArray)raw;
        if(operations.length()<1||operations.length()>64)throw new IllegalArgumentException("Use 1..64 clip operations");
        return store.guardedProject(request.getString("projectId"),expected,current->{
            ProjectStore.Project edited=ProjectStore.Project.fromJson(new JSONObject(current.toJson().toString()));
            ArrayList<String> changed=new ArrayList<>();
            for(int i=0;i<operations.length();i++){
                Object entry=operations.opt(i);if(!(entry instanceof JSONObject))throw new IllegalArgumentException("Each clip operation must be an object");
                JSONObject operation=(JSONObject)entry;
                java.util.Iterator<String> fields=operation.keys();while(fields.hasNext())if(!OP_FIELDS.contains(fields.next()))
                    throw new IllegalArgumentException("Unsupported precision operation field");
                Object id=operation.opt("clipId");if(!(id instanceof String)||((String)id).isEmpty()||((String)id).length()>128)
                    throw new IllegalArgumentException("A stable clipId of 1..128 characters is required");
                ProjectStore.Clip clip=null;for(ProjectStore.Clip candidate:edited.clips)if(id.equals(candidate.id)){clip=candidate;break;}
                if(clip==null)throw new IllegalArgumentException("Unknown clipId in this project");
                ProjectStore.Asset asset=edited.asset(clip.assetId);if(asset==null)throw new IllegalArgumentException("Clip asset is unavailable");
                applyOperation(clip,asset,operation);
                if(!changed.contains(id))changed.add((String)id);
            }
            // Existing title/audio tracks must still fit the changed timeline.
            StudioLayerContract.fromJson(edited,StudioLayerContract.toJson(edited.studioLayers));
            store.save(edited);
            return new JSONObject().put("ok",true).put("projectId",edited.id).put("updatedAt",edited.updatedAt)
                    .put("changedClipIds",new JSONArray(changed)).put("clipCount",edited.clips.size())
                    .put("outputDurationMs",edited.outputDurationMs());
        });
    }

    /** Single touch timeline operation under the same stable-ID/revision guard. */
    public static JSONObject applyTimeline(ProjectStore store,JSONObject request)throws Exception{
        if(store==null||request==null||!(request.opt("projectId") instanceof String)||!(request.opt("action") instanceof String))
            throw new IllegalArgumentException("Project and timeline action are required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        String action=request.getString("action");
        if(!Set.of("append","duplicate","delete","move","split").contains(action))throw new IllegalArgumentException("Unsupported timeline action");
        return store.guardedProject(request.getString("projectId"),expected,current->{
            ProjectStore.Project edited=ProjectStore.Project.fromJson(new JSONObject(current.toJson().toString()));
            String selected="";int index=-1;ProjectStore.Clip clip=null;
            if(!"append".equals(action)){
                Object id=request.opt("clipId");if(!(id instanceof String)||((String)id).isEmpty()||((String)id).length()>128)
                    throw new IllegalArgumentException("Stable clipId is required");
                for(int i=0;i<edited.clips.size();i++)if(id.equals(edited.clips.get(i).id)){index=i;clip=edited.clips.get(i);break;}
                if(clip==null)throw new IllegalArgumentException("Clip is no longer in this project");selected=clip.id;
            }
            if(Set.of("append","duplicate","split").contains(action)&&edited.clips.size()>=10000)
                throw new IllegalArgumentException("Timeline is limited to 10000 clips");
            switch(action){
                case "append":{
                    if(!(request.opt("assetId") instanceof String))throw new IllegalArgumentException("Owned assetId is required");
                    ProjectStore.Asset asset=edited.asset(request.getString("assetId"));
                    if(asset==null||asset.mime==null||(!asset.mime.startsWith("image/")&&!asset.mime.startsWith("video/")))
                        throw new IllegalArgumentException("Timeline media must be imported into this project");
                    ProjectStore.Clip added=new ProjectStore.Clip();added.id=java.util.UUID.randomUUID().toString();added.assetId=asset.id;
                    added.inMs=0;added.outMs=asset.mime.startsWith("image/")?3000:asset.durationMs>0?asset.durationMs:1000;
                    edited.clips.add(added);selected=added.id;break;
                }
                case "duplicate":{
                    ProjectStore.Clip copy=ProjectStore.Clip.fromJson(new JSONObject(clip.toJson().toString()));copy.id=java.util.UUID.randomUUID().toString();
                    edited.clips.add(index+1,copy);selected=copy.id;break;
                }
                case "delete":edited.clips.remove(index);selected=edited.clips.isEmpty()?"":edited.clips.get(Math.min(index,edited.clips.size()-1)).id;break;
                case "move":{
                    int target=(int)StudioClipKeyframes.integer(request,"targetIndex",0,edited.clips.size()-1);
                    StudioTimelineEditOps.move(edited.clips,clip.id,target);break;
                }
                case "split":{
                    long at=StudioClipKeyframes.integer(request,"sourceTimeMs",clip.inMs+100,clip.outMs-100);
                    long originalDuration=clip.outputDurationMs();
                    ProjectStore.Clip second=ProjectStore.Clip.fromJson(new JSONObject(clip.toJson().toString()));second.id=java.util.UUID.randomUUID().toString();
                    second.inMs=at;clip.outMs=at;
                    StudioClipAudioEnvelope.preserveSplit(clip.effects,second.effects,originalDuration,clip.outputDurationMs());
                    if(clip.effects.optJSONArray("keyframes")!=null&&clip.effects.optJSONArray("keyframes").length()>0){
                        long span=clip.effects.has("studioKeyframeDurationMs")?StudioClipKeyframes.integer(clip.effects,"studioKeyframeDurationMs",1,86_400_000):originalDuration;
                        long offset=clip.effects.has("studioKeyframeOffsetMs")?StudioClipKeyframes.integer(clip.effects,"studioKeyframeOffsetMs",0,span):0;
                        clip.effects.put("studioKeyframeDurationMs",span).put("studioKeyframeOffsetMs",offset);
                        second.effects.put("studioKeyframeDurationMs",span).put("studioKeyframeOffsetMs",offset+clip.outputDurationMs());
                    }
                    edited.clips.add(index+1,second);break;
                }
            }
            for(ProjectStore.Clip candidate:edited.clips){
                ProjectStore.Asset asset=edited.asset(candidate.assetId);if(asset==null)throw new IllegalArgumentException("Timeline asset is unavailable");
                applyOperation(candidate,asset,new JSONObject());
            }
            StudioLayerContract.fromJson(edited,StudioLayerContract.toJson(edited.studioLayers));store.save(edited);
            return new JSONObject().put("ok",true).put("projectId",edited.id).put("updatedAt",edited.updatedAt)
                    .put("selectedClipId",selected).put("clipCount",edited.clips.size()).put("outputDurationMs",edited.outputDurationMs());
        });
    }

    static void validateClip(ProjectStore.Clip clip,ProjectStore.Asset asset)throws Exception{
        applyOperation(clip,asset,new JSONObject());
    }

    private static void applyOperation(ProjectStore.Clip clip,ProjectStore.Asset asset,JSONObject operation)throws Exception{
        if(operation.has("keyframeEdits")&&(operation.has("keyframes")||operation.has("clearKeyframes")))
            throw new IllegalArgumentException("keyframeEdits cannot be combined with replacement or clearKeyframes");
        for(String name:new String[]{"clearKeyframes","resetColor","resetTransform"})
            if(operation.has(name)&&!(operation.opt(name) instanceof Boolean))throw new IllegalArgumentException(name+" must be boolean");
        if(operation.optBoolean("resetColor"))for(String name:StudioClipAdjustments.COLOR_KEYS)clip.effects.remove(name);
        if(operation.optBoolean("resetTransform"))for(String name:new String[]{"scale","zoom","scaleX","scaleY","rotate","x","y","anchorX","anchorY"})clip.effects.remove(name);
        if(operation.optBoolean("clearKeyframes")){
            clip.effects.remove("keyframes");clip.effects.remove("studioKeyframeDurationMs");clip.effects.remove("studioKeyframeOffsetMs");
        }
        if(operation.has("set")){
            Object raw=operation.opt("set");if(!(raw instanceof JSONObject))throw new IllegalArgumentException("set must be an object");
            JSONObject values=(JSONObject)raw;java.util.Iterator<String> keys=values.keys();
            while(keys.hasNext()){
                String key=keys.next();if(!NUMBERS.contains(key)&&!TEXT.contains(key)&&!"crop".equals(key)&&!"chromaKey".equals(key)&&!"audioMono".equals(key))throw new IllegalArgumentException("Unsupported clip setting: "+key);
                switch(key){
                    case "inMs":clip.inMs=StudioClipKeyframes.integer(values,key,0,86_400_000);break;
                    case "outMs":clip.outMs=StudioClipKeyframes.integer(values,key,1,86_400_000);break;
                    case "speed":clip.speed=(float)StudioClipKeyframes.number(values,key,1,.25,4);break;
                    case "volume":clip.volume=(float)StudioClipKeyframes.number(values,key,1,0,2);break;
                    case "audioPan":clip.effects.put(key,StudioClipKeyframes.number(values,key,0,-1,1));break;
                    case "audioMono":
                        if(!(values.opt(key) instanceof Boolean))throw new IllegalArgumentException("audioMono must be boolean");
                        clip.effects.put(key,values.getBoolean(key));break;
                    case "audioFadeInMs":case "audioFadeOutMs":clip.effects.put(key,StudioClipKeyframes.integer(values,key,0,86_400_000));break;
                    case "brightness":case "contrast":clip.effects.put(key,StudioClipKeyframes.number(values,key,0,-1,1));break;
                    case "saturationAdjust":case "lightnessAdjust":clip.effects.put(key,StudioClipKeyframes.number(values,key,0,-100,100));break;
                    case "blur":clip.effects.put(key,StudioClipKeyframes.number(values,key,0,0,18));break;
                    case "chromaKey":
                        if(!(values.opt(key) instanceof Boolean))throw new IllegalArgumentException("chromaKey must be boolean");
                        clip.effects.put(key,values.getBoolean(key));break;
                    case "chromaColor":case "maskShape":
                        if(!(values.opt(key) instanceof String))throw new IllegalArgumentException(key+" must be a string");
                        clip.effects.put(key,values.getString(key));break;
                    case "motionPreset":case "colorPreset":case "effectPreset":case "title":
                        if(!(values.opt(key) instanceof String))throw new IllegalArgumentException(key+" must be a string");
                        String value=values.getString(key);
                        if("title".equals(key)){if(value.length()>160)throw new IllegalArgumentException("Title exceeds 160 characters");clip.title=value;}
                        else{
                            Set<String> allowed="motionPreset".equals(key)?MOTIONS:"colorPreset".equals(key)?COLORS:EFFECTS;
                            if(!allowed.contains(value))throw new IllegalArgumentException("UNSUPPORTED_EFFECT: "+value+" has no implemented native renderer");
                            clip.effects.put(key,value);
                        }break;
                    default:clip.effects.put(key,values.get(key));
                }
            }
            // A uniform zoom control intentionally replaces prior independent-axis scaling.
            if(values.has("scale")){
                if(!values.has("scaleX"))clip.effects.remove("scaleX");
                if(!values.has("scaleY"))clip.effects.remove("scaleY");
            }
            if(values.has("audioFadeInMs")||values.has("audioFadeOutMs")){
                clip.effects.remove("studioAudioDurationMs");clip.effects.remove("studioAudioOffsetMs");
            }
        }
        boolean image=asset.mime!=null&&asset.mime.startsWith("image/");
        if(clip.inMs<0||clip.outMs<=clip.inMs||clip.outputDurationMs()<(image?1:100)||clip.outputDurationMs()>86_400_000L)
            throw new IllegalArgumentException("Clip must have a positive source range within 24h");
        if(!image&&asset.durationMs>0&&clip.outMs>asset.durationMs)throw new IllegalArgumentException("Clip trim exceeds its imported source");
        if(operation.has("keyframes")){
            if(!(operation.opt("keyframes") instanceof JSONArray))throw new IllegalArgumentException("keyframes must be an array");
            StudioClipKeyframes.parse(operation.getJSONArray("keyframes"),clip.outputDurationMs());
            clip.effects.remove("studioKeyframeDurationMs");clip.effects.remove("studioKeyframeOffsetMs");
            clip.effects.put("keyframes",new JSONArray(operation.getJSONArray("keyframes").toString()));
        }
        if(operation.has("keyframeEdits")){
            if(!(operation.opt("keyframeEdits") instanceof JSONArray))throw new IllegalArgumentException("keyframeEdits must be an array");
            clip.effects=StudioKeyframeEdits.apply(clip.effects,clip.outputDurationMs(),operation.getJSONArray("keyframeEdits"));
        }
        if(clip.effects.has("crop"))new StudioCropEffect(clip.effects.optJSONObject("crop"));
        StudioClipKeyframes.fromEffects(clip.effects,clip.outputDurationMs());
        StudioClipAudioEnvelope.fromEffects(clip.effects,clip.outputDurationMs(),clip.speed,clip.volume);
        new StudioPixelEffect(clip.effects,clip.outputDurationMs());
    }
}
