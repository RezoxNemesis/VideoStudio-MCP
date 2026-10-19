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
        for(int i=0;i<layers.length();i++){
            JSONObject entry=layers.getJSONObject(i);
            StudioLayer lane=StudioLayer.fromJson(entry);
            if(lane.id==null||lane.id.length()>128||!usedIds.add(lane.id))
                throw new IllegalArgumentException("Duplicate/invalid lane ID");
            validate(project,lane);
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
    }

    public static JSONArray toJson(List<StudioLayer> layers){
        JSONArray result=new JSONArray();
        if(layers!=null)for(StudioLayer lane:layers)result.put(lane.toJson());
        return result;
    }
}
