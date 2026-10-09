package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Owner-controlled capabilities. This policy never affects local manual editing. */
public final class OwnerAccessPolicy {
    private final SharedPreferences prefs;
    private final ProjectStore store;
    public OwnerAccessPolicy(Context context,ProjectStore store){this.prefs=context.getApplicationContext().getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);this.store=store;}
    public String mode(){String m=prefs.getString("permission_mode","everything");return Arrays.asList("project","selected_assets","one_file").contains(m)?m:"everything";}
    public boolean restricted(){return !"everything".equals(mode());}
    public boolean assetLimited(){return "selected_assets".equals(mode())||"one_file".equals(mode());}
    public String projectId(){
        String id=prefs.getString("allowed_project_id","");if(!id.isEmpty())return id;
        if("one_file".equals(mode()))for(ProjectStore.Project p:store.list())if(p.asset(prefs.getString("allowed_asset_id",""))!=null)return p.id;
        return "";
    }
    public Set<String> assets(){Set<String> ids=new HashSet<>();
        if("one_file".equals(mode()))ids.add(prefs.getString("allowed_asset_id",""));
        else try{JSONArray a=new JSONArray(prefs.getString("allowed_asset_ids","[]"));for(int i=0;i<a.length();i++)ids.add(a.getString(i));}catch(Exception invalid){throw new IllegalStateException("Invalid owner asset scope",invalid);}ids.remove("");return ids;
    }
    public boolean projectAllowed(String id){return !restricted()||(!projectId().isEmpty()&&projectId().equals(id));}
    public boolean assetAllowed(String projectId,String assetId){return projectAllowed(projectId)&&(!assetLimited()||assets().contains(assetId));}
    public boolean allowsJob(JSONObject job){
        if(job==null||!projectAllowed(job.optString("projectId","")))return false;
        if(!assetLimited())return true;
        JSONArray inputs=job.optJSONArray("inputAssetIds");if(inputs==null||inputs.length()==0)return false;
        for(int i=0;i<inputs.length();i++)if(!assetAllowed(job.optString("projectId"),inputs.optString(i,"")))return false;
        return true;
    }
    public boolean allows(String action,JSONObject args){
        String lower=action==null?"":action.toLowerCase(java.util.Locale.US);
        if(lower.contains("gallery")||lower.contains("media_library")||lower.contains("photo_library"))return false;
        if(!restricted())return true;
        if(Arrays.asList("ping","get_state","self_test","connection_health","reconnect_mcp","job_status","activity_note","cancel_job","cancel_all_jobs","stop_all","editor_schema").contains(action))return true;
        String target=args.optString("projectId","");if(target.isEmpty())return false;
        if(!projectAllowed(target))return false;
        if("project".equals(mode()))return !Arrays.asList("create_project","delete_project").contains(action);
        ProjectStore.Project project=store.get(target);if(project==null)return false;
        if("project_query".equals(action))return !"snapshots".equals(args.optString("query","graph"));
        if(Arrays.asList("analyse_media","vault_create","vault_inspect","create_proxy").contains(action))return assetAllowed(target,args.optString("assetId"));
        if("editor_operation".equals(action))return allowsEdit(project,args.optString("operation"),args.optJSONObject("args"));
        if("editor_batch".equals(action)){
            JSONArray operations=args.optJSONArray("operations");if(operations==null||operations.length()==0)return false;
            for(int i=0;i<operations.length();i++){JSONObject op=operations.optJSONObject(i);if(op==null||!allowsEdit(project,op.optString("operation"),op.optJSONObject("args")))return false;}return true;
        }
        if("apply_tool".equals(action)){
            int index=args.optInt("clipIndex",-1);return index>=0&&index<project.clips.size()&&assetAllowed(target,project.clips.get(index).assetId)&&Arrays.asList("volume","title","transform","color","blur").contains(args.optString("tool"));
        }
        return false;
    }
    private boolean allowsEdit(ProjectStore.Project p,String operation,JSONObject args){
        if(args==null)return false;
        if(Arrays.asList("rename_asset","remove_asset","add_clip").contains(operation))return assetAllowed(p.id,args.optString("assetId"));
        if(!Arrays.asList("set_property","set_keyframe","remove_keyframe","set_title","slip_clip","split_clip","set_audio_effects","set_composite_effects","set_creator_style").contains(operation))return false;
        ProjectStore.Clip clip=p.clip(args.optString("clipId"));return clip!=null&&assetAllowed(p.id,clip.assetId);
    }
    public JSONObject redactProject(ProjectStore.Project project){
        if(project==null||!projectAllowed(project.id))return null;
        JSONObject result=project.toJson();if(!assetLimited())return result;
        try{
            JSONArray allowedAssets=new JSONArray(),allowedClips=new JSONArray(),allowedTracks=new JSONArray();Set<String> tracks=new HashSet<>();
            for(ProjectStore.Asset a:project.assets)if(assetAllowed(project.id,a.id))allowedAssets.put(a.toJson());
            for(ProjectStore.Clip c:project.clips)if(assetAllowed(project.id,c.assetId)){allowedClips.put(c.toJson());tracks.add(c.trackId);}
            for(ProjectStore.Track t:project.tracks)if(tracks.contains(t.id))allowedTracks.put(t.toJson());
            result.put("assets",allowedAssets).put("clips",allowedClips).put("tracks",allowedTracks).put("markers",new JSONArray());
            result.put("sourcePrompt","").put("latestExportUri","").put("latestExportName","").put("latestExportAt",0);
            return result;
        }catch(Exception invalid){throw new IllegalStateException(invalid);}
    }
    public JSONObject summaries(){
        JSONArray rows=new JSONArray();try{
            for(ProjectStore.Project p:store.list())if(projectAllowed(p.id)){
                ProjectStore.Project visible=ProjectStore.Project.fromJson(redactProject(p));
                JSONObject row=new JSONObject().put("id",visible.id).put("name",visible.name).put("revision",visible.revision).put("trackCount",visible.tracks.size()).put("clipCount",visible.clips.size()).put("assetCount",visible.assets.size()).put("durationMs",visible.outputDurationMs()).put("updatedAt",visible.updatedAt);rows.put(row);
            }
            return new JSONObject().put("projects",rows).put("storageBackend",store.storageBackend());
        }catch(Exception invalid){throw new IllegalStateException(invalid);}
    }
    public JSONObject metadata(){
        try{
            JSONArray clips=new JSONArray(),assets=new JSONArray();for(String id:assets())assets.put(id);
            ProjectStore.Project p=store.get(projectId());if(p!=null)for(ProjectStore.Clip c:p.clips)if(assetAllowed(p.id,c.assetId))clips.put(c.id);
            return new JSONObject().put("allowedProjectId",projectId()).put("allowedAssetIds",assets).put("allowedClipIds",clips).put("permissionScopeUpdatedAt",prefs.getLong("permission_scope_updated_at",0));
        }catch(Exception invalid){throw new IllegalStateException(invalid);}
    }
    public boolean resultPredatesScope(JSONObject command){
        if(!restricted())return false;long created=0;Object raw=command.opt("createdAt");
        if(raw instanceof Number)created=((Number)raw).longValue();else if(raw instanceof String)try{created=java.time.Instant.parse((String)raw).toEpochMilli();}catch(java.time.format.DateTimeParseException invalid){created=0;}
        return created<prefs.getLong("permission_scope_updated_at",0);
    }
}
