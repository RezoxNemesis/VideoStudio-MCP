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
        int images=0;
        for(int i=0;i<layers.length();i++){
            JSONObject entry=layers.getJSONObject(i);
            StudioLayer lane=StudioLayer.fromJson(entry);
            if(lane.id==null||lane.id.length()>128||!usedIds.add(lane.id))
                throw new IllegalArgumentException("Duplicate/invalid lane ID");
            validate(project,lane);
            if(StudioLayer.IMAGE.equals(lane.type)&&++images>8)throw new IllegalArgumentException("Maximum 8 decoded image layers");
            result.add(lane);
        }
        return result;
    }

    public static void validate(ProjectStore.Project project,StudioLayer lane) {
        if(project==null||lane==null)throw new IllegalArgumentException("Missing lane");
        lane.validate();
        long output=project.outputDurationMs();
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
        if(StudioLayer.IMAGE.equals(lane.type)){
            ProjectStore.Asset asset=project.asset(lane.assetId);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")||asset.uri==null||asset.uri.isEmpty())
                throw new IllegalArgumentException("Image must be previously imported into this project");
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
