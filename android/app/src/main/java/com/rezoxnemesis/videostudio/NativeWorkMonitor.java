package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only status bridge for the Human Editor. The MCP native service owns
 * its own persistent work journal. Opening/rebuilding an editor screen may
 * never restore, cancel or rewrite native render job records.
 */
public final class NativeWorkMonitor {
    private static final String PREFS="videostudio_native_v1";
    private NativeWorkMonitor(){}

    public static JSONObject snapshot(Context context) {
        if(context==null)throw new IllegalArgumentException("Context required");
        SharedPreferences prefs=context.getApplicationContext()
                .getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String raw=prefs.getString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,"[]");
        JSONArray saved;
        try{saved=new JSONArray(raw);}catch(Exception ignored){saved=new JSONArray();}
        JSONObject summary=summarize(saved);
        long last=prefs.getLong("control_service_heartbeat",0);
        boolean fresh=last>0&&Math.abs(System.currentTimeMillis()-last)<65000;
        boolean identityCurrent=AppProtocol.APP_VERSION.equals(
                prefs.getString("control_service_app_version",""))
                && prefs.getInt("control_service_connection_core_version",0)
                        ==McpConnectionCore.CORE_VERSION;
        boolean online=fresh&&identityCurrent&&
                prefs.getBoolean("control_service_online",false);
        int thermal=PowerManager.THERMAL_STATUS_NONE;
        try{
            PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if(pm!=null)thermal=pm.getCurrentThermalStatus();
        }catch(Exception ignored){}
        try{
            summary.put("nativeServiceOnline",online);
            summary.put("nativeVersionCurrent",identityCurrent);
            summary.put("heartbeatAgeSeconds",last>0?
                    Math.max(0,(System.currentTimeMillis()-last)/1000): -1);
            summary.put("thermalStatus",thermal);
            summary.put("thermalLabel",thermalLabel(thermal));
            summary.put("thermalSafetyHold",thermal>=PowerManager.THERMAL_STATUS_SEVERE);
            summary.put("thermalSource","Android PowerManager, not external case temperature");
            summary.put("connectionDetail",prefs.getString("control_service_detail",""));
        }catch(Exception ignored){}
        return summary;
    }

    public static JSONObject summarize(JSONArray saved) {
        JSONArray work=saved==null?new JSONArray():saved;
        int active=0,waiting=0,completed=0;
        JSONObject top=null;
        for(int i=0;i<work.length();i++){
            JSONObject job=work.optJSONObject(i);
            if(job==null)continue;
            String state=job.optString("state","");
            if(JobManager.isTerminal(state)){completed++;continue;}
            active++;
            if(state.startsWith("waiting")||"checkpointed".equals(state)||
                    "preparing".equals(state))waiting++;
            if(top==null||job.optLong("updatedAt",0)>top.optLong("updatedAt",0))
                top=job;
        }
        if(top==null)for(int i=0;i<work.length();i++){
            JSONObject j=work.optJSONObject(i);
            if(j!=null&&(top==null||
                    j.optLong("updatedAt",0)>top.optLong("updatedAt",0)))top=j;
        }
        JSONObject result=new JSONObject();
        try{
            result.put("jobs",work);
            result.put("active",active);
            result.put("waiting",waiting);
            result.put("completed",completed);
            if(top!=null){
                result.put("jobId",top.optString("id",""));
                result.put("jobName",top.optString("name",""));
                result.put("jobStage",top.optString("stage",""));
                result.put("jobState",top.optString("state",""));
                result.put("jobProgress",top.optInt("progress",0));
                result.put("jobDetail",top.optString("detail",""));
                result.put("lastUpdatedAt",top.optLong("updatedAt",0));
            }
        }catch(Exception ignored){}
        return result;
    }

    public static String thermalLabel(int status){
        switch(status){
            case PowerManager.THERMAL_STATUS_NONE: return "No system thermal restriction";
            case PowerManager.THERMAL_STATUS_LIGHT: return "Android thermal level 1 · light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "Android thermal level 2 · moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "Android thermal level 3 · severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "Android thermal level 4 · critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "Android thermal level 5 · emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "Android thermal level 6 · shutdown";
            default: return "Android thermal status " + status;
        }
    }
}
