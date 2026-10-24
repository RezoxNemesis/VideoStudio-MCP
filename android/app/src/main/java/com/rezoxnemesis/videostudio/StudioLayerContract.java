package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Common safety contract for user and ChatGPT-authored audio/title tracks.
 * Source media must already belong to VideoStudio's named project.
 * This is not a media-library or Gallery import API.
 */
public final class StudioLayerContract {
    private StudioLayerContract(){}

    public static List<StudioLayer> fromJson(ProjectStore.Project project,
                                             JSONArray layers) throws Exception {
        if(project==null||layers==null)
            throw new IllegalArgumentException("Project and lanes are required");
        if(layers.length()>32)throw new IllegalArgumentException("Maximum 32 layers");
        ArrayList<StudioLayer> result=new ArrayList<>();
        Set<String> usedIds=new HashSet<>();
        int images=0,videos=0,shapes=0,groups=0,cameras=0;
        java.util.HashMap<String,StudioLayer> byId=new java.util.HashMap<>();
        for(int i=0;i<layers.length();i++){
            JSONObject entry=layers.getJSONObject(i);
            StudioLayer lane=StudioLayer.fromJson(entry);
            if(lane.id==null||lane.id.length()>128||!usedIds.add(lane.id))
                throw new IllegalArgumentException("Duplicate/invalid lane ID");
            validate(project,lane);
            byId.put(lane.id,lane);
            if(StudioLayer.IMAGE.equals(lane.type)&&++images>8)throw new IllegalArgumentException("Maximum 8 decoded image layers");
            if(StudioLayer.VIDEO.equals(lane.type)&&++videos>2)throw new IllegalArgumentException("Maximum 2 decoded video layers");
            if(StudioLayer.SHAPE.equals(lane.type)&&++shapes>12)throw new IllegalArgumentException("Maximum 12 cached original vector shapes");
            if(StudioLayer.CAMERA.equals(lane.type)&&++cameras>1)
                throw new IllegalArgumentException("Only one global composition camera is supported");
            if(StudioLayer.GROUP.equals(lane.type)&&++groups>8)
                throw new IllegalArgumentException("Maximum 8 motion groups per composition");
            result.add(lane);
        }
        for(StudioLayer lane:result){
            if(lane.parentId==null||lane.parentId.isEmpty())continue;
            StudioLayer parent=byId.get(lane.parentId);
            if(parent==null||!StudioLayer.GROUP.equals(parent.type))
                throw new IllegalArgumentException("Parent link must reference a group in the same project");
            if(lane.startMs<parent.startMs||lane.durationMs>parent.durationMs-(lane.startMs-parent.startMs))
                throw new IllegalArgumentException("Motion group must cover every linked child\u0027s entire time span");
        }
        return result;
    }

    public static void validate(ProjectStore.Project project,StudioLayer lane) {
        if(project==null||lane==null)throw new IllegalArgumentException("Missing lane");
        lane.validate();
        long output=project.outputDurationMs();
        if(StudioLayer.CAMERA.equals(lane.type)){
            if(lane.startMs!=0)
                throw new IllegalArgumentException("Global camera must start at timeline zero");
            // Camera keyframes have their own authored duration; if clips are
            // lengthened or shortened later, the sample holds the nearest
            // authored end key rather than blocking an otherwise safe trim.
            return;
        }
        if(output<=0||lane.startMs>=output||
                lane.durationMs>output-lane.startMs)
            throw new IllegalArgumentException("Track extends outside the rendered video timeline");
        if(StudioLayer.AUDIO.equals(lane.type)){
            ProjectStore.Asset asset=project.asset(lane.assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("audio/"))
                throw new IllegalArgumentException("Audio must be previously imported into this project");
            if(asset.durationMs>0){
                if(lane.sourceInMs>asset.durationMs||
                        lane.durationMs>asset.durationMs-lane.sourceInMs)
                    throw new IllegalArgumentException("Audio trim exceeds imported source duration");
            }
        }
        if(StudioLayer.SHAPE.equals(lane.type)){
            if(!lane.assetId.isEmpty())throw new IllegalArgumentException("Native SHAPE cannot reference media sources");
        }
        if(StudioLayer.IMAGE.equals(lane.type)){
            ProjectStore.Asset asset=project.asset(lane.assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")||asset.uri==null||asset.uri.isEmpty())
                throw new IllegalArgumentException("Image must be previously imported into this project");
        }
        if(StudioLayer.VIDEO.equals(lane.type)){
            ProjectStore.Asset asset=project.asset(lane.assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("video/")||asset.uri==null||asset.uri.trim().isEmpty())
                throw new IllegalArgumentException("Video must be previously imported into this project");
            if(!asset.seekable)throw new IllegalArgumentException("Video layer source must have verified seekability");
            if(asset.durationMs<=0||asset.durationMs>Long.MAX_VALUE/1000L)
                throw new IllegalArgumentException("Video layer source requires a known positive native duration");
            if(lane.sourceInMs>asset.durationMs||lane.durationMs>asset.durationMs-lane.sourceInMs)
                throw new IllegalArgumentException("Video trim exceeds imported source duration");
        }
    }

    /** Atomic common human/MCP lane replacement; ACK receipts never include an unbounded project. */
    public static JSONObject apply(ProjectStore store,JSONObject request)throws Exception{
        if(store==null||request==null||!(request.opt("projectId") instanceof String))throw new IllegalArgumentException("Project and lane request are required");
        long expected=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        Object raw=request.has("studioLayers")?request.opt("studioLayers"):request.opt("layers");
        if(!(raw instanceof JSONArray))throw new IllegalArgumentException("studioLayers must be an array");
        final JSONArray requested=(JSONArray)raw;
        return store.guardedProject(request.getString("projectId"),expected,current->{
            List<StudioLayer> layers=fromJson(current,requested);
            ProjectStore.Project updated=ProjectStore.Project.fromJson(current.toJson());updated.studioLayers.clear();updated.studioLayers.addAll(layers);
            JSONArray ids=new JSONArray();for(StudioLayer layer:layers)ids.put(layer.id);
            store.save(updated);return new JSONObject().put("ok",true).put("projectId",updated.id).put("updatedAt",updated.updatedAt)
                    .put("layerCount",layers.size()).put("layerIds",ids);
        });
    }

    public static JSONArray toJson(List<StudioLayer> layers){
        JSONArray result=new JSONArray();
        if(layers!=null)for(StudioLayer lane:layers)result.put(lane.toJson());
        return result;
    }
}
