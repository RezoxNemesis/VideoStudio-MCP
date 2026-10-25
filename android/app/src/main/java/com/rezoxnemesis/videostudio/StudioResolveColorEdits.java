package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolve-inspired batch color edit. The same guarded native operation is
 * invoked from touch controls or ChatGPT MCP; no new media ingestion.
 */
public final class StudioResolveColorEdits {
    private static final Set<String> FIELDS=Set.of(
            "projectId","expectedUpdatedAt","operation","clipIds","nodes","preset");
    private static final Set<String> PRESETS=Set.of(
            "balanced","cinematic","noir","golden_hour","cool_night");
    private StudioResolveColorEdits(){}

    public static JSONObject capabilities() throws Exception {
        return StudioResolveColorGraph.capabilities()
                .put("operations",new JSONArray().put("grade").put("reset"))
                .put("presets",new JSONArray()
                        .put("balanced").put("cinematic").put("noir")
                        .put("golden_hour").put("cool_night"))
                .put("maximumClipsPerOperation",64)
                .put("atomicBatch",true).put("revisionGuarded",true);
    }

    public static JSONObject plan(ProjectStore.Project source,JSONObject request)throws Exception {
        if(source==null||request==null)
            throw new IllegalArgumentException("Owned source project required");
        Result result=prepare(source,request);
        return receipt(result,source,true);
    }

    public static JSONObject apply(ProjectStore store,JSONObject request)throws Exception {
        if(store==null||request==null)
            throw new IllegalArgumentException("Native project store and color program required");
        String id=request.optString("projectId","");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        return store.guardedProject(id,expected,current->{
            Result program=prepare(current,request);
            ProjectStore.Project detached=ProjectStore.Project.fromJson(current.toJson());
            HashSet<String> selected=new HashSet<>(program.clipIds);
            int edited=0;
            for(ProjectStore.Clip clip:detached.clips) {
                if(!selected.contains(clip.id))continue;
                if(clip.effects==null)clip.effects=new JSONObject();
                if("reset".equals(program.operation))clip.effects.remove("resolveColorNodes");
                else clip.effects.put("resolveColorNodes",new JSONArray(program.nodes.toString()));
                StudioPrecisionEdits.validateClip(clip,detached.asset(clip.assetId));
                edited++;
            }
            if(edited!=selected.size())
                throw new IllegalArgumentException("One or more selected clips are no longer in the timeline");
            store.save(detached);
            return receipt(program,detached,false).put("updatedAt",detached.updatedAt);
        });
    }

    private static Result prepare(ProjectStore.Project project,JSONObject request)throws Exception {
        java.util.Iterator<String> iter=request.keys();
        while(iter.hasNext())if(!FIELDS.contains(iter.next()))
            throw new IllegalArgumentException("Unknown native Color page field");
        if(!project.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Project identity mismatch");
        long revision=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        if(project.updatedAt!=revision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String operation=request.optString("operation","");
        if(!"grade".equals(operation)&&!"reset".equals(operation))
            throw new IllegalArgumentException("Use grade or reset");
        JSONArray targets=request.optJSONArray("clipIds");
        if(targets==null||targets.length()<1||targets.length()>64)
            throw new IllegalArgumentException("Select 1..64 owned timeline clips");
        HashSet<String> available=new HashSet<>();
        for(ProjectStore.Clip clip:project.clips)available.add(clip.id);
        ArrayList<String> ids=new ArrayList<>();
        HashSet<String> dedup=new HashSet<>();
        for(int i=0;i<targets.length();i++){
            Object item=targets.opt(i);
            if(!(item instanceof String)||((String)item).isEmpty()
                    ||!available.contains(item)||!dedup.add((String)item))
                throw new IllegalArgumentException("Clip IDs must be distinct known timeline clips");
            ids.add((String)item);
        }
        JSONArray nodes=null;
        if("reset".equals(operation)){
            if(request.has("nodes")||request.has("preset"))
                throw new IllegalArgumentException("Reset has no new color nodes");
        }else{
            if(request.has("nodes")==request.has("preset"))
                throw new IllegalArgumentException("Provide either nodes or one preset, not both");
            if(request.has("preset")){
                Object raw=request.opt("preset");
                if(!(raw instanceof String)||!PRESETS.contains(raw))
                    throw new IllegalArgumentException("Unknown Color page preset");
                nodes=preset((String)raw);
            }else {
                Object raw=request.opt("nodes");
                if(!(raw instanceof JSONArray))
                    throw new IllegalArgumentException("Color nodes must be an ordered array");
                nodes=StudioResolveColorGraph.validate((JSONArray)raw);
            }
            StudioResolveColorGraph.validate(nodes);
        }
        return new Result(ids,nodes,operation);
    }

    private static JSONObject receipt(Result info,ProjectStore.Project project,boolean dryRun)
            throws Exception {
        return new JSONObject().put("ok",true)
                .put("schema","videostudio.resolve-color-batch.v1")
                .put("projectId",project.id).put("operation",info.operation)
                .put("clipIds",new JSONArray(info.clipIds))
                .put("affectedClipCount",info.clipIds.size())
                .put("nodeCount",info.nodes==null?0:info.nodes.length())
                .put("lutSize",StudioResolveColorGraph.LUT_SIZE)
                .put("dryRun",dryRun).put("saved",!dryRun)
                .put("previewVerified",false).put("exportVerified",false)
                .put("sourceMediaUnchanged",true).put("galleryAccess",false);
    }

    public static JSONArray preset(String name)throws Exception {
        if(!PRESETS.contains(name))throw new IllegalArgumentException("Unknown Color page preset");
        JSONObject primary=new JSONObject().put("type","primaries");
        switch(name){
            case "balanced":
                primary.put("contrast",1).put("saturation",1);break;
            case "cinematic":
                primary.put("contrast",1.17).put("saturation",.91).put("temperature",-.08);break;
            case "noir":
                primary.put("contrast",1.35).put("saturation",0);break;
            case "golden_hour":
                primary.put("contrast",1.11).put("saturation",1.08).put("temperature",.34);break;
            case "cool_night":
                primary.put("exposure",-.3).put("contrast",1.18).put("saturation",.83)
                        .put("temperature",-.35);break;
        }
        JSONArray result=new JSONArray().put(primary);
        if("cinematic".equals(name)||"cool_night".equals(name))
            result.put(new JSONObject().put("type","filmic").put("toe",.08)
                    .put("shoulder",.16));
        return StudioResolveColorGraph.validate(result);
    }

    private static final class Result {
        final ArrayList<String> clipIds;
        final JSONArray nodes;
        final String operation;
        Result(ArrayList<String> clipIds,JSONArray nodes,String operation){
            this.clipIds=clipIds;this.nodes=nodes;this.operation=operation;
        }
    }
}
