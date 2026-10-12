package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;

/** Explicit phone request scopes; an extraction backend never implies cinematic approval. */
public final class StoryboardOwnerRequests {
    private StoryboardOwnerRequests(){}
    public static String intentForBackend(String backend){
        if("CAMERA_PARALLAX".equals(backend))return "environmental";
        if("AUTO_LAYERS".equals(backend)||"PERSON_SEGMENTATION".equals(backend))return "source_deformation";
        if("SOURCE_RIG".equals(backend))throw new IllegalArgumentException("Cinematic source rigs require an authored saved program with contacts and choreography");
        throw new IllegalArgumentException("Unsupported owner storyboard backend");
    }
    public static Intent series(Context context,String projectId,String genre,String backend,String direction,int framesPerPair,int batchIndex,boolean foley,boolean auditOnly){
        if(context==null||projectId==null||projectId.isEmpty()||framesPerPair<4||framesPerPair>24||batchIndex< -1)throw new IllegalArgumentException("Valid owner source project and storyboard settings required");
        return new Intent(context,ControlService.class).setAction(ControlService.ACTION_LOCAL_STORYBOARD_SERIES)
                .putExtra("projectId",projectId).putExtra("sourceProjectId",projectId).putExtra("genre",genre).putExtra("backend",backend)
                .putExtra("motionIntent",intentForBackend(backend)).putExtra("direction",direction).putExtra("framesPerPair",framesPerPair)
                .putExtra("fps",24).putExtra("batchIndex",batchIndex).putExtra("foley",foley).putExtra("analysisOnly",auditOnly);
    }
}
