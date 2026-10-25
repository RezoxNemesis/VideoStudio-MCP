package com.rezoxnemesis.videostudio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Strict owner-authored offline temporal video request. No URL, paid provider,
 * account, token, remote endpoint or procedural fallback field is accepted.
 */
public final class NativeOfflineVideoPlan {
    static final String SCHEMA="videostudio.offline-video.plan.v1";
    static final String PACK_ID="native_temporal_video_onnx_v1";
    static final int WIDTH=128,HEIGHT=128,FRAMES_PER_SHOT=9,MAX_SHOTS=6,FPS=9;
    static final Set<String> REQUEST=Set.of("sourceProjectId","sourceUpdatedAt",
            "operationId","mode","sourceAssetIds","prompt","seed");
    private NativeOfflineVideoPlan(){}

    public static JSONObject validate(ProjectStore.Project project,JSONObject request)throws Exception{
        if(project==null||request==null)throw new IllegalArgumentException("Existing owned project required");
        java.util.Iterator<String> keys=request.keys();
        while(keys.hasNext())if(!REQUEST.contains(keys.next()))
            throw new IllegalArgumentException("Unsupported local video generation field");
        String sourceId=string(request,"sourceProjectId");
        if(!sourceId.equals(project.id))throw new IllegalArgumentException("Source project identity mismatch");
        long revision=exact(request,"sourceUpdatedAt",0,9_007_199_254_740_991L);
        if(revision!=project.updatedAt)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String operationId=string(request,"operationId");
        if(!operationId.matches("[A-Za-z0-9_-]{8,64}"))
            throw new IllegalArgumentException("Operation ID must contain 8..64 safe characters");
        String mode=string(request,"mode");
        if(!"image_to_video".equals(mode)&&!"text_to_video".equals(mode))
            throw new IllegalArgumentException("Only genuine offline image-to-video or text-to-video");
        String prompt=string(request,"prompt").trim();
        if(prompt.isEmpty()||prompt.length()>1200)
            throw new IllegalArgumentException("Motion prompt must contain 1..1200 characters");
        if(prompt.contains("<|startoftext|>")||prompt.contains("<|endoftext|>"))
            throw new IllegalArgumentException("Prompt cannot inject tokenizer control markers");
        long seed=exact(request,"seed",0,1_000_000_000L);
        JSONArray assetIds=request.optJSONArray("sourceAssetIds");
        if(assetIds==null)throw new IllegalArgumentException("sourceAssetIds must be an explicit array");
        int count=assetIds.length();
        if("image_to_video".equals(mode)?count<1||count>MAX_SHOTS:count!=0)
            throw new IllegalArgumentException("Image mode needs 1..6 owned stills; text mode uses no stills");
        Set<String> seen=new HashSet<>();
        JSONArray anchors=new JSONArray();
        for(int i=0;i<count;i++){
            Object raw=assetIds.opt(i);
            if(!(raw instanceof String))throw new IllegalArgumentException("Source asset ID must be a string");
            String id=(String)raw;
            if(id.isEmpty()||!seen.add(id))throw new IllegalArgumentException("Unique owned source asset IDs required");
            ProjectStore.Asset asset=project.asset(id);
            if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")
                    ||asset.uri==null||asset.uri.isEmpty())
                throw new IllegalArgumentException("Missing owned image reference "+id);
            anchors.put(id);
        }
        if("text_to_video".equals(mode))count=1;
        JSONObject normalized=new JSONObject()
                .put("schema",SCHEMA).put("sourceProjectId",sourceId)
                .put("sourceUpdatedAt",revision).put("operationId",operationId)
                .put("mode",mode).put("prompt",prompt).put("seed",seed)
                .put("sourceAssetIds",anchors)
                .put("fps",FPS).put("width",WIDTH).put("height",HEIGHT)
                .put("framesPerShot",FRAMES_PER_SHOT)
                .put("shotCount",count).put("totalFrames",count*FRAMES_PER_SHOT)
                .put("neuralGenerationRequired",true).put("proceduralFallback",false)
                .put("paidApiAllowed",false).put("networkInferenceAllowed",false);
        normalized.put("planSha256",hex(MessageDigest.getInstance("SHA-256")
                .digest(SketchDocument.canonical(normalized).getBytes(StandardCharsets.UTF_8))));
        return normalized;
    }
    private static String string(JSONObject obj,String key)throws Exception{
        if(!(obj.opt(key) instanceof String))throw new IllegalArgumentException(key+" must be a string");
        return obj.getString(key);
    }
    private static long exact(JSONObject obj,String key,long min,long max)throws Exception{
        if(!(obj.opt(key) instanceof Number))
            throw new IllegalArgumentException("Required integer "+key);
        double val=((Number)obj.opt(key)).doubleValue();
        long output=((Number)obj.opt(key)).longValue();
        if(!Double.isFinite(val)||val!=output||output<min||output>max)
            throw new IllegalArgumentException("Invalid integer "+key);
        return output;
    }
    private static String hex(byte[] b){
        StringBuilder s=new StringBuilder(b.length*2);
        for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
        return s.toString();
    }
}
