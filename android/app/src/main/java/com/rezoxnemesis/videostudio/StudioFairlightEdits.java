package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** One native transaction for editing multiple owned Fairlight-style audio lanes. */
public final class StudioFairlightEdits {
    private static final Set<String> FIELDS=Set.of(
            "projectId","expectedUpdatedAt","operation","layerIds","preset","fx");
    private static final Set<String> PRESETS=Set.of(
            "dialogue","music","broadcast","ambient","neutral");
    private StudioFairlightEdits(){}

    public static JSONObject capabilities()throws Exception{
        return StudioFairlightSettings.capabilities()
                .put("presets",new JSONArray().put("dialogue").put("music")
                        .put("broadcast").put("ambient").put("neutral"))
                .put("atomicOwnedAudioLanes",true).put("maxLanesPerEdit",16)
                .put("revisionGuarded",true);
    }

    public static JSONObject plan(ProjectStore.Project p,JSONObject req)throws Exception{
        Result r=prepare(p,req);
        return receipt(r,p,true);
    }

    public static JSONObject apply(ProjectStore store,JSONObject req)throws Exception{
        if(store==null||req==null)throw new IllegalArgumentException("Audio source and project required");
        String id=req.optString("projectId","");
        long expected=StudioClipKeyframes.integer(req,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        return store.guardedProject(id,expected,current->{
            Result r=prepare(current,req);
            ProjectStore.Project updated=ProjectStore.Project.fromJson(current.toJson());
            HashSet<String> wanted=new HashSet<>(r.ids);
            int found=0;
            for(StudioLayer track:updated.studioLayers) {
                if(!wanted.contains(track.id))continue;
                track.fairlightFx="reset".equals(r.operation)?null:
                        new JSONObject(r.fx.toString());
                track.validate();
                found++;
            }
            if(found!=wanted.size())throw new IllegalArgumentException("Audio track changed while editing");
            StudioLayerContract.fromJson(updated,StudioLayerContract.toJson(updated.studioLayers));
            store.save(updated);
            return receipt(r,updated,false).put("updatedAt",updated.updatedAt);
        });
    }

    private static Result prepare(ProjectStore.Project p,JSONObject request)throws Exception{
        if(p==null||request==null)throw new IllegalArgumentException("Project and audio program required");
        java.util.Iterator<String> fields=request.keys();
        while(fields.hasNext())if(!FIELDS.contains(fields.next()))
            throw new IllegalArgumentException("Unsupported Fairlight MCP field");
        if(!p.id.equals(request.optString("projectId","")))
            throw new IllegalArgumentException("Project identity mismatch");
        long revision=StudioClipKeyframes.integer(request,"expectedUpdatedAt",0,9_007_199_254_740_991L);
        if(p.updatedAt!=revision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED");
        String operation=request.optString("operation","");
        if(!"apply".equals(operation)&&!"reset".equals(operation))
            throw new IllegalArgumentException("Use apply or reset");
        JSONArray chosen=request.optJSONArray("layerIds");
        if(chosen==null||chosen.length()<1||chosen.length()>16)
            throw new IllegalArgumentException("Choose 1..16 native audio lanes");
        HashSet<String> audio=new HashSet<>();
        for(StudioLayer track:p.studioLayers)
            if(StudioLayer.AUDIO.equals(track.type))audio.add(track.id);
        ArrayList<String> ids=new ArrayList<>();
        for(int i=0;i<chosen.length();i++){
            Object raw=chosen.opt(i);
            if(!(raw instanceof String)||!audio.contains(raw)||ids.contains(raw))
                throw new IllegalArgumentException("Each selected ID must be a distinct owned AUDIO track");
            ids.add((String)raw);
        }
        JSONObject fx=null;
        if("reset".equals(operation)){
            if(request.has("fx")||request.has("preset"))
                throw new IllegalArgumentException("Reset takes no audio effects");
        }else{
            if(request.has("fx")==request.has("preset"))
                throw new IllegalArgumentException("Choose exact fx or one preset");
            if(request.has("preset")){
                Object raw=request.opt("preset");
                if(!(raw instanceof String)||!PRESETS.contains(raw))
                    throw new IllegalArgumentException("Unknown Fairlight preset");
                fx=preset((String)raw);
            }else{
                if(!(request.opt("fx") instanceof JSONObject))
                    throw new IllegalArgumentException("Fairlight audio effects require an object");
                fx=StudioFairlightSettings.validate((JSONObject)request.opt("fx"));
            }
        }
        return new Result(ids,fx,operation);
    }

    public static JSONObject preset(String style)throws Exception{
        if(!PRESETS.contains(style))throw new IllegalArgumentException("Unknown Fairlight style");
        JSONObject spec=new JSONObject();
        switch(style){
            case "dialogue":
                spec.put("highpassHz",90).put("eqLowDb",-2)
                        .put("eqMidDb",1.8).put("eqHighDb",1)
                        .put("compressorThresholdDb",-23).put("compressorRatio",2.5)
                        .put("attackMs",15).put("releaseMs",140)
                        .put("makeupDb",3).put("limiterCeilingDb",-1.5);break;
            case "music":
                spec.put("eqLowDb",1).put("eqHighDb",.5)
                        .put("compressorThresholdDb",-16).put("compressorRatio",1.45)
                        .put("attackMs",30).put("releaseMs",300)
                        .put("limiterCeilingDb",-1);break;
            case "broadcast":
                spec.put("highpassHz",70).put("compressorThresholdDb",-20)
                        .put("compressorRatio",3).put("makeupDb",2.5)
                        .put("limiterCeilingDb",-2).put("releaseMs",180);break;
            case "ambient":
                spec.put("highpassHz",45).put("eqHighDb",-1).put("outputDb",-3)
                        .put("limiterCeilingDb",-1);break;
            case "neutral":
                spec.put("bypass",true);break;
        }
        return StudioFairlightSettings.validate(spec);
    }

    private static JSONObject receipt(Result r,ProjectStore.Project p,boolean preview)throws Exception{
        return new JSONObject().put("ok",true)
                .put("schema","videostudio.fairlight-batch.v1")
                .put("projectId",p.id).put("operation",r.operation)
                .put("layerIds",new JSONArray(r.ids)).put("affectedLaneCount",r.ids.size())
                .put("sourceMediaUnchanged",true)
                .put("dryRun",preview).put("saved",!preview)
                .put("previewVerified",false).put("exportVerified",false)
                .put("lufsMeasured",false).put("galleryAccess",false);
    }
    private static final class Result{
        final ArrayList<String> ids;
        final JSONObject fx;final String operation;
        Result(ArrayList<String> ids,JSONObject fx,String op){
            this.ids=ids;this.fx=fx;this.operation=op;
        }
    }
}
