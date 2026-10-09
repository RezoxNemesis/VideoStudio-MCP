package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Durable high-level work specifications for restart-safe VideoStudio jobs.
 *
 * JobManager persists runtime checkpoints. This store persists the action and
 * parameters required to reconstruct selected long-running jobs after Android
 * kills or restarts the process.
 */
public final class RecoveryPlanStore {
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY = "durable_recovery_plans_v1";
    private static final int MAX = 24;
    private static final long MAX_AGE_MS = 48L * 60L * 60L * 1000L;
    private static final int MAX_AUTO_ATTEMPTS = 4;

    private final SharedPreferences prefs;

    public RecoveryPlanStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized String begin(String action, JSONObject parameters, String projectId) {
        String command=parameters==null?"":parameters.optString("_mcpCommandId","");
        if(!command.isEmpty()){
            JSONObject prior=findByCommand(action,command);
            if(prior!=null){
                if(!projectId.equals(prior.optString("projectId")))throw new IllegalArgumentException("Command ID belongs to another project");
                JSONObject saved=prior.optJSONObject("parameters");
                String previous=EditorEngine.fingerprint("recovery:"+action,saved==null?new JSONObject():saved,0);
                String requested=EditorEngine.fingerprint("recovery:"+action,scrubInternal(parameters),0);
                if(!previous.equals(requested))throw new IllegalArgumentException("Command ID conflicts with a different durable work plan");
                return prior.optString("id");
            }
        }
        String id = UUID.randomUUID().toString();
        JSONObject plan = new JSONObject();
        try {
            plan.put("id", id);
            plan.put("action", clean(action, "unknown"));
            plan.put("projectId", clean(projectId, ""));
            plan.put("origin", parameters!=null&&"owner".equals(parameters.optString("_origin"))?"owner":"autonomous");
            plan.put("parameters", parameters == null ? new JSONObject() : scrubInternal(parameters));
            plan.put("state", "queued");
            plan.put("stage", "queued");
            plan.put("progress", 0);
            plan.put("detail", "Durable work plan created");
            plan.put("attempts", 0);
            plan.put("jobId", "");
            plan.put("outputUri", "");
            plan.put("outputName", "");
            plan.put("createdAt", System.currentTimeMillis());
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
        return id;
    }

    public synchronized JSONObject findByCommand(String action,String commandId,String projectId){
        JSONObject plan=findByCommand(action,commandId);
        return plan!=null&&projectId.equals(plan.optString("projectId"))?plan:null;
    }

    public synchronized JSONObject findByCommand(String action,String commandId){
        if(commandId==null||commandId.isEmpty())return null;
        JSONArray entries=read();
        for(int i=0;i<entries.length();i++){
            JSONObject plan=entries.optJSONObject(i);if(plan==null)continue;
            JSONObject parameters=plan.optJSONObject("parameters");
            if(action.equals(plan.optString("action"))&&parameters!=null&&commandId.equals(parameters.optString("_mcpCommandId")))return get(plan.optString("id"));
        }
        return null;
    }

    public synchronized JSONObject get(String id) {
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan != null && id != null && id.equals(plan.optString("id"))) {
                try { return new JSONObject(plan.toString()); }
                catch (Exception ignored) { return plan; }
            }
        }
        return null;
    }

    public synchronized void attachJob(String planId, String jobId) {
        JSONObject plan = get(planId);
        if(plan==null)return;
        if(terminal(plan))throw new java.util.concurrent.CancellationException("Durable plan is terminal");
        try {
            plan.put("jobId", clean(jobId, ""));
            plan.put("state", "running");
            plan.put("attempts", plan.optInt("attempts", 0) + 1);
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void checkpointForJob(String jobId, String stage, int progress, String detail) {
        JSONObject plan = findByJobId(jobId);
        if(plan==null||terminal(plan))return;
        try {
            plan.put("state", "running");
            plan.put("stage", clean(stage, "working"));
            plan.put("progress", Math.max(0, Math.min(100, progress)));
            plan.put("detail", clean(detail, ""));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized JSONObject outputForJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return null;
        String uri = plan.optString("outputUri", "");
        if (uri.isEmpty()) return null;
        JSONObject out = new JSONObject();
        try {
            out.put("uri", uri);
            out.put("name", plan.optString("outputName", ""));
            out.put("stage", plan.optString("stage", ""));
            out.put("state", plan.optString("state", ""));
            JSONObject proof=plan.optJSONObject("outputVerification");
            if(proof!=null)out.put("verification",new JSONObject(proof.toString()));
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void markOutputForJob(String jobId, String uri, String name) {
        markOutputForJob(jobId,uri,name,null);
    }

    public synchronized void markOutputForJob(String jobId,String uri,String name,JSONObject verification){
        JSONObject plan = findByJobId(jobId);
        if(plan==null||terminal(plan))return;
        try {
            plan.put("outputUri", clean(uri, ""));
            plan.put("outputName", clean(name, ""));
            if(verification!=null)plan.put("outputVerification",new JSONObject(verification.toString()));
            else plan.remove("outputVerification");
            plan.put("stage", "output_published");
            plan.put("progress", Math.max(98, plan.optInt("progress", 0)));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    /** Forget a rejected binding, preserving the external file and any newer publication. */
    public synchronized boolean invalidateOutput(String planId,String rejectedUri,String detail){
        JSONObject plan=get(planId);
        if(plan==null||terminal(plan)||rejectedUri==null||!rejectedUri.equals(plan.optString("outputUri","")))return false;
        try{
            plan.put("outputUri","");plan.put("outputName","");plan.remove("outputVerification");
            plan.put("stage","output_invalidated");plan.put("detail",clean(detail,"Output verification failed; a new render is required"));
            plan.put("updatedAt",System.currentTimeMillis());
        }catch(Exception invalid){throw new IllegalArgumentException(invalid);}
        upsert(plan);return true;
    }

    public synchronized boolean invalidateOutputForJob(String jobId,String rejectedUri,String detail){
        JSONObject plan=findByJobId(jobId);
        return plan!=null&&invalidateOutput(plan.optString("id"),rejectedUri,detail);
    }

    public synchronized void completeByJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if(plan==null||terminal(plan))return;
        try {
            plan.put("state", "completed");
            plan.put("stage", "completed");
            plan.put("progress", 100);
            plan.put("detail", "Completed");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void completePlan(String planId, String detail) {
        JSONObject plan = get(planId);
        if(plan==null||terminal(plan))return;
        try {
            plan.put("state", "completed");
            plan.put("stage", "completed");
            plan.put("progress", 100);
            plan.put("detail", clean(detail, "Completed"));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void markResuming(String planId) {
        JSONObject plan = get(planId);
        if(plan==null)return;
        if(terminal(plan))throw new java.util.concurrent.CancellationException("Durable plan is terminal");
        try {
            plan.put("state", "retrying");
            plan.put("stage", "restart_recovery");
            plan.put("detail", "Resuming durable work after process restart");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void failByJob(String jobId, String error, boolean recoverable) {
        JSONObject plan = findByJobId(jobId);
        if(plan==null||terminal(plan))return;
        try {
            plan.put("state", recoverable ? "waiting_retry" : "failed");
            plan.put("detail", clean(error, recoverable ? "Recoverable failure" : "Failed"));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void cancelByJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if(plan==null||terminal(plan))return;
        try {
            plan.put("state", "cancelled");
            plan.put("detail", "Cancelled");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized int cancelActive() { return cancelActive(false); }

    public synchronized int cancelAutonomous() { return cancelActive(true); }

    private int cancelActive(boolean autonomousOnly) {
        JSONArray arr = read();
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan == null) continue;
            if(autonomousOnly&&"owner".equals(plan.optString("origin")))continue;
            String state = plan.optString("state");
            if(!terminal(plan)){
                try {
                    plan.put("state", "cancelled");
                    plan.put("detail", "Cancelled");
                    plan.put("updatedAt", System.currentTimeMillis());
                    count++;
                } catch (Exception ignored) {}
            }
        }
        write(arr);
        return count;
    }

    public synchronized JSONArray pendingForAutoResume() {
        JSONArray source = read();
        JSONArray out = new JSONArray();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (int i = 0; i < source.length() && out.length() < MAX; i++) {
            JSONObject plan = source.optJSONObject(i);
            if (plan == null) continue;
            if (plan.optLong("updatedAt", 0) < cutoff) continue;
            if (plan.optInt("attempts", 0) >= MAX_AUTO_ATTEMPTS) continue;
            String state = plan.optString("state");
            if(!terminal(plan)){
                try { out.put(new JSONObject(plan.toString())); }
                catch (Exception ignored) {}
            }
        }
        return out;
    }

    public synchronized JSONArray recent(int limit) {
        JSONArray source = read();
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < source.length() && out.length() < count; i++) {
            JSONObject plan = source.optJSONObject(i);
            if (plan != null) out.put(plan);
        }
        return out;
    }

    private JSONObject findByJobId(String jobId) {
        if (jobId == null || jobId.isEmpty()) return null;
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan != null && jobId.equals(plan.optString("jobId"))) {
                try { return new JSONObject(plan.toString()); }
                catch (Exception ignored) { return plan; }
            }
        }
        return null;
    }

    private void upsert(JSONObject plan) {
        if (plan == null) return;
        JSONArray old = read();
        JSONArray next = new JSONArray();
        next.put(plan);
        String id = plan.optString("id");
        int unfinished=terminal(plan)?0:1;
        for(int i=0;i<old.length();i++){
            JSONObject item=old.optJSONObject(i);
            if(item!=null&&!id.equals(item.optString("id"))&&!terminal(item))unfinished++;
        }
        if(unfinished>MAX)throw new IllegalStateException("Recovery queue is full; finish or cancel existing work first");
        // Preserve unfinished command bindings before rotating terminal history.
        for(int i=0;i<old.length();i++){
            JSONObject item=old.optJSONObject(i);
            if(item!=null&&!id.equals(item.optString("id"))&&!terminal(item))next.put(item);
        }
        for (int i = 0; i < old.length() && next.length() < MAX; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null || id.equals(item.optString("id"))||!terminal(item)) continue;
            next.put(item);
        }
        write(next);
    }

    private JSONArray read() {
        try { return new JSONArray(prefs.getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void write(JSONArray array) {
        if(!prefs.edit().putString(KEY,array==null?"[]":array.toString()).commit())throw new IllegalStateException("Could not persist durable recovery plan");
    }

    private static JSONObject scrubInternal(JSONObject input) {
        JSONObject out = new JSONObject();
        if (input == null) return out;
        JSONArray names = input.names();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            if(key.startsWith("_")){
                String value=input.optString(key,"");
                if("_generationId".equals(key)&&value.matches("[a-zA-Z0-9_-]{8,80}")){
                    try{out.put(key,value);}catch(Exception invalid){throw new IllegalArgumentException(invalid);}
                }else if("_mcpCommandId".equals(key)&&value.matches("[a-zA-Z0-9._:-]{1,200}")){
                    try{out.put(key,value);}catch(Exception invalid){throw new IllegalArgumentException(invalid);}
                }
                continue;
            }
            try { out.put(key, input.opt(key)); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean terminal(JSONObject plan){String state=plan.optString("state");return "completed".equals(state)||"cancelled".equals(state)||"failed".equals(state);}

    private static String clean(String value, String fallback) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty()) out = fallback;
        if (out.length() > 1000) out = out.substring(0, 1000);
        return out;
    }
}
