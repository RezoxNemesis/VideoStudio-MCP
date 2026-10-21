package com.rezoxnemesis.videostudio;

import java.util.HashSet;
import java.util.UUID;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Complete timeline replacement through the same revision/validation boundary as touch edits. */
public final class StudioEditPlan {
    private static final long MAX_MS=86_400_000L;
    private static final Set<String> FIELDS=Set.of("id","clipId","assetId","inMs","outMs","start","end","speed","volume","transition","title","effects");
    private StudioEditPlan(){}

    public static JSONObject apply(ProjectStore store,JSONObject request)throws Exception {
        if(store==null||request==null||!(request.opt("projectId") instanceof String)
                ||request.getString("projectId").isEmpty())throw new IllegalArgumentException("A named project is required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        if(!(request.opt("clips") instanceof JSONArray))throw new IllegalArgumentException("clips must be an array");
        // Detach the caller's entire plan before the SQLite transaction.
        JSONArray clips=new JSONArray(request.getJSONArray("clips").toString());
        if(clips.length()<1||clips.length()>120)throw new IllegalArgumentException("Use 1..120 timeline clips");
        return store.guardedProject(request.getString("projectId"),expected,current->{
            ProjectStore.Project edited=ProjectStore.Project.fromJson(new JSONObject(current.toJson().toString()));
            edited.clips.clear();HashSet<String> ids=new HashSet<>();JSONArray accepted=new JSONArray();
            for(int i=0;i<clips.length();i++){
                if(!(clips.opt(i) instanceof JSONObject))throw new IllegalArgumentException("Each timeline clip must be an object");
                JSONObject raw=clips.getJSONObject(i);
                java.util.Iterator<String> fields=raw.keys();
                while(fields.hasNext()){
                    String field=fields.next();if(!FIELDS.contains(field))throw new IllegalArgumentException("Unsupported timeline clip field: "+field);
                }
                if(!(raw.opt("assetId") instanceof String))throw new IllegalArgumentException("An imported assetId is required");
                ProjectStore.Asset asset=edited.asset(raw.getString("assetId"));
                if(asset==null||asset.mime==null||(!asset.mime.startsWith("video/")&&!asset.mime.startsWith("image/")))
                    throw new IllegalArgumentException("Timeline media must be imported into this project");
                ProjectStore.Clip clip=new ProjectStore.Clip();
                clip.id=identifier(raw);if(!ids.add(clip.id))throw new IllegalArgumentException("Timeline clip IDs must be unique");
                clip.assetId=asset.id;
                clip.inMs=time(raw,"inMs","start",0);
                clip.outMs=time(raw,"outMs","end",asset.durationMs>0?asset.durationMs:asset.mime.startsWith("image/")?3000:0);
                clip.speed=(float)StudioClipKeyframes.number(raw,"speed",1,.25,4);
                clip.volume=(float)StudioClipKeyframes.number(raw,"volume",1,0,2);
                clip.transition=text(raw,"transition","none",80);clip.title=text(raw,"title","",160);
                if(raw.has("effects")){
                    if(!(raw.opt("effects") instanceof JSONObject))throw new IllegalArgumentException("effects must be an object");
                    clip.effects=new JSONObject(raw.getJSONObject("effects").toString());
                }
                StudioPrecisionEdits.validateClip(clip,asset);
                edited.clips.add(clip);accepted.put(clip.id);
            }
            // Retained music and captions must still fit the proposed timeline.
            StudioLayerContract.fromJson(edited,StudioLayerContract.toJson(edited.studioLayers));
            store.save(edited);
            return new JSONObject().put("ok",true).put("projectId",edited.id).put("updatedAt",edited.updatedAt)
                    .put("clipCount",edited.clips.size()).put("clipIds",accepted).put("durationMs",edited.outputDurationMs());
        });
    }

    private static String identifier(JSONObject raw)throws Exception {
        if(raw.has("id")&&raw.has("clipId")&&!raw.get("id").equals(raw.get("clipId")))
            throw new IllegalArgumentException("id and clipId must identify the same clip");
        String name=raw.has("clipId")?"clipId":"id";
        if(!raw.has(name))return UUID.randomUUID().toString();
        String id=text(raw,name,"",128);if(id.isEmpty())throw new IllegalArgumentException("A stable clip ID cannot be empty");return id;
    }
    private static long time(JSONObject raw,String millis,String seconds,long fallback)throws Exception {
        if(raw.has(millis))return StudioClipKeyframes.integer(raw,millis,0,MAX_MS);
        if(raw.has(seconds))return Math.round(StudioClipKeyframes.number(raw,seconds,0,0,MAX_MS/1000d)*1000);
        return fallback;
    }
    private static String text(JSONObject raw,String field,String fallback,int max)throws Exception {
        if(!raw.has(field))return fallback;
        if(!(raw.opt(field) instanceof String)||raw.getString(field).length()>max)
            throw new IllegalArgumentException(field+" must be a string of at most "+max+" characters");
        return raw.getString(field);
    }
}
