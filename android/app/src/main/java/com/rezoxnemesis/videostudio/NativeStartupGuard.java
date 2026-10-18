package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/**
 * Crash-loop circuit breaker for *restarted* native media work.
 *
 * It never removes projects, old jobs, source assets, MCP credentials or Android
 * Keystore material. First startup after an in-place upgrade that interrupted a
 * potentially OOM-heavy render keeps the old work recoverable but HELD until the
 * user explicitly requests a retry. Repeated unclean process starts similarly
 * hold recovery instead of repeatedly launching the same decoder/renderer.
 */
final class NativeStartupGuard {
    private static final String PREFS="videostudio_startup_guard_v457";
    static final long CRASH_WINDOW_MS=3L*60*1000;
    static final long RETRY_WINDOW_MS=20L*60*1000;
    private static final int MAX_ATTEMPT_RECORDS=16;
    private final SharedPreferences state;

    static final class Start {
        final boolean upgraded,held;
        final int rapidUncleanStarts;
        Start(boolean upgraded,boolean held,int starts){
            this.upgraded=upgraded;this.held=held;this.rapidUncleanStarts=starts;
        }
    }
    NativeStartupGuard(Context context){
        state=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    synchronized Start start(long now,String installedVersion,String previousServiceVersion){
        if(now<=0)throw new IllegalArgumentException("A valid monotonic wall-clock time is required");
        String prior=previousServiceVersion==null?"":previousServiceVersion;
        boolean upgrade=!prior.isEmpty()&&!prior.equals(installedVersion);
        long earlier=state.getLong("lastServiceStartAt",0);
        boolean unclean=state.getBoolean("serviceStartupOpen",false);
        int count=unclean&&earlier>0&&now>=earlier&&now-earlier<=CRASH_WINDOW_MS
                ?Math.min(20,state.getInt("rapidUncleanStarts",0)+1):0;
        boolean held=state.getBoolean("recoveryHeld",false)||count>=2;
        String reason=count>=2?"Repeated unclean native service restarts; heavy recovery suspended":
                state.getString("holdReason","");
        state.edit().putBoolean("serviceStartupOpen",true)
                .putLong("lastServiceStartAt",now)
                .putInt("rapidUncleanStarts",count)
                .putBoolean("recoveryHeld",held)
                .putString("holdReason",reason)
                .putString("currentVersion",installedVersion==null?"":installedVersion)
                .apply();
        return new Start(upgrade,held,count);
    }

    synchronized void holdInterruptedUpgrade(JSONArray plans,boolean upgraded){
        if(!upgraded||plans==null)return;
        for(int i=0;i<plans.length();i++){
            JSONObject row=plans.optJSONObject(i);
            if(row!=null&&heavy(row.optString("action",""))){
                hold("Interrupted heavy render retained after update; retry manually when the app is stable");
                return;
            }
        }
    }

    synchronized boolean mayAutoResume(String planId,String action,long now){
        if(held()||now<=0||planId==null||planId.isEmpty())return false;
        if(!heavy(action))return true;
        JSONObject previous=readAttempts();
        long last=previous.optLong(planId,0);
        if(last>0&&now>=last&&now-last<RETRY_WINDOW_MS){
            hold("Repeated recovery of the same render stopped; the saved project is safe");
            return false;
        }
        JSONObject next=new JSONObject();
        try{
            next.put(planId,now);
            int preserved=0;
            JSONArray names=previous.names();
            if(names!=null)for(int i=0;i<names.length()&&preserved<MAX_ATTEMPT_RECORDS-1;i++){
                String key=names.getString(i);
                if(key.equals(planId))continue;
                long value=previous.optLong(key,0);
                if(value>0&&now>=value&&now-value<RETRY_WINDOW_MS){
                    next.put(key,value);preserved++;
                }
            }
        }catch(Exception invalid){throw new IllegalStateException("Cannot persist render recovery admission",invalid);}
        state.edit().putString("recentPlanAttempts",next.toString()).apply();
        return true;
    }

    synchronized void hold(String reason){
        state.edit().putBoolean("recoveryHeld",true)
                .putString("holdReason",reason==null?"Native render recovery held":reason).apply();
    }
    synchronized boolean held(){return state.getBoolean("recoveryHeld",false);}
    synchronized String reason(){return state.getString("holdReason","");}
    synchronized void userRetry(){
        state.edit().putBoolean("recoveryHeld",false)
                .putString("holdReason","")
                .putString("recentPlanAttempts","{}")
                .putInt("rapidUncleanStarts",0)
                .apply();
    }
    synchronized void cleanStop(){
        state.edit().putBoolean("serviceStartupOpen",false)
                .putInt("rapidUncleanStarts",0).apply();
    }
    synchronized JSONObject status(){
        JSONObject result=new JSONObject();
        try{
            result.put("recoveryHeld",held());
            result.put("reason",reason());
            result.put("rapidUncleanStarts",state.getInt("rapidUncleanStarts",0));
            result.put("projectsPreserved",true);
            result.put("retryRequiresOwnerAction",true);
            result.put("sourceAssetsCleared",false);
        }catch(Exception ignored){}
        return result;
    }
    private JSONObject readAttempts(){
        try{return new JSONObject(state.getString("recentPlanAttempts","{}"));}
        catch(Exception invalid){return new JSONObject();}
    }

    static boolean heavy(String action){
        if(action==null)return false;
        switch(action){
            case "animate_images": case "animate_pose_sequence":
            case "animate_storyboard_series": case "animate_storyboard_artwork":
            case "render_rigged_2d": case "animate_combat_rig":
            case "render_gltf_combat": case "cinematic_3d_render":
            case "run_creative_graph": case "prompt_video":
            case "export_project": case "sketch_render": case "sketch_book_render":
            case "earth_projection_preview":
            case "generate_image": case "install_model_pack":
                return true;
            default:return false;
        }
    }
}
