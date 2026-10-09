package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public final class JobManager {
    public enum Kind { LIGHT, HEAVY, MANUAL_RENDER }
    public enum Origin { OWNER, AUTONOMOUS }

    public static final String STATE_QUEUED = "queued";
    public static final String STATE_PREPARING = "preparing";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_CHECKPOINTED = "checkpointed";
    public static final String STATE_WAITING_NETWORK = "waiting_network";
    public static final String STATE_WAITING_STORAGE = "waiting_storage";
    public static final String STATE_WAITING_MEMORY = "waiting_memory";
    public static final String STATE_WAITING_THERMAL = "waiting_thermal";
    public static final String STATE_WAITING_NATIVE = "waiting_native";
    public static final String STATE_COMPLETED = "completed";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_CANCELLED = "cancelled";

    public static final class Job {
        public final String id;
        public final String name;
        public final Kind kind;
        public final Origin origin;
        public final long createdAt;
        public volatile long updatedAt;
        public volatile String state = STATE_QUEUED;
        public volatile int progress = 0;
        public volatile String detail = "";
        public volatile String stage = "queued";
        public volatile boolean recoverable = true;
        public volatile int retryCount = 0;
        public volatile long lastCheckpointAt;
        public volatile JSONObject result;
        public volatile long journalRevision=1;
        public volatile String projectId="";
        private volatile java.util.Set<String> inputAssetIds=java.util.Collections.emptySet();
        private volatile Runnable authorizationGuard=()->{};
        volatile Future<?> future;
        private JobManager owner;

        Job(String name, Kind kind) {
            this(UUID.randomUUID().toString(), name, kind, kind==Kind.MANUAL_RENDER?Origin.OWNER:Origin.AUTONOMOUS, System.currentTimeMillis());
        }

        Job(String id, String name, Kind kind, Origin origin, long createdAt) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.origin = origin;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.lastCheckpointAt = createdAt;
        }

        public void checkpoint(int progress, String detail) {
            checkpoint(this.stage, progress, detail);
        }

        public void checkpoint(String stage, int progress, String detail) {
            synchronized(this){
            checkActive();
            this.stage = stage == null || stage.trim().isEmpty() ? this.stage : stage.trim();
            this.progress = Math.max(0, Math.min(100, progress));
            this.detail = detail == null ? "" : detail;
            this.updatedAt = System.currentTimeMillis();
            this.lastCheckpointAt = this.updatedAt;
            journalRevision++;
            }
            if (owner != null) owner.persist();
        }

        public void bindProject(String id){ synchronized(this){projectId=id==null?"":id;journalRevision++;}if(owner!=null)owner.persist(); }

        public void bindInputs(String id,java.util.Collection<String> assets){
            synchronized(this){projectId=id==null?"":id;java.util.Set<String> copy=new java.util.TreeSet<>();
                if(assets!=null)for(String asset:assets)if(asset!=null&&!asset.isEmpty())copy.add(asset);
                inputAssetIds=java.util.Collections.unmodifiableSet(copy);journalRevision++;}
            if(owner!=null)owner.persist();
        }
        public void setAuthorizationGuard(Runnable guard){authorizationGuard=guard==null?()->{}:guard;}
        public synchronized void checkActive(){
            if(Thread.currentThread().isInterrupted()||isTerminal(state))throw new java.util.concurrent.CancellationException("Job is no longer active");
            authorizationGuard.run();
        }
        /** Cancellation and publication share this lock: the winning operation determines the outcome. */
        public synchronized void commit(Work publication)throws Exception{checkActive();publication.run(this);}
        public void completeDurably(){if(owner==null)throw new IllegalStateException("Job has no durable journal");owner.completeDurably(this,null);}

        public void setResult(JSONObject value) {
            synchronized(this){
            if (value == null) {
                this.result = null;
            } else {
                try { this.result = new JSONObject(value.toString()); }
                catch (Exception ignored) { this.result = value; }
            }
            this.updatedAt = System.currentTimeMillis();
            journalRevision++;
            }
            if (owner != null) owner.persist();
        }

        synchronized JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("kind", kind.name().toLowerCase(java.util.Locale.US));
                o.put("origin", origin.name().toLowerCase(java.util.Locale.US));
                o.put("state", state);
                o.put("progress", progress);
                o.put("detail", detail);
                o.put("stage", stage);
                o.put("recoverable", recoverable);
                o.put("retryCount", retryCount);
                o.put("lastCheckpointAt", lastCheckpointAt);
                o.put("createdAt", createdAt);
                o.put("updatedAt", updatedAt);
                o.put("journalRevision",journalRevision);
                o.put("projectId",projectId);
                o.put("inputAssetIds",new JSONArray(inputAssetIds));
                if (result != null) o.put("result", result);
            } catch (Exception ignored) {}
            return o;
        }
    }

    public interface Work {
        void run(Job job) throws Exception;
    }

    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_JOBS = ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY;
    private static final Object JOURNAL_LOCK=new Object();
    private static final Map<String,Job> PROCESS_RUNNING=new ConcurrentHashMap<>();
    private final Context context;
    private final SharedPreferences prefs;
    private static final Semaphore PROCESS_HEAVY_LANE = new Semaphore(1, true);
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ExecutorService manualPool = Executors.newSingleThreadExecutor();
    private static final Semaphore PROCESS_MANUAL_RENDER_LANE = new Semaphore(1, true);
    private final Semaphore heavyLane = PROCESS_HEAVY_LANE;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public JobManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restoreRecoveryState();
    }

    public Job submit(String name, Kind kind, Work work) {
        return submit(name,kind,kind==Kind.MANUAL_RENDER?Origin.OWNER:Origin.AUTONOMOUS,work);
    }

    public Job submit(String name,Kind kind,Origin origin,Work work) {
        if(origin==null)throw new IllegalArgumentException("Job origin is required");
        Job job = new Job(UUID.randomUUID().toString(),name,kind,origin,System.currentTimeMillis());
        job.owner = this;
        jobs.put(job.id, job);
        PROCESS_RUNNING.put(job.id,job);
        persist();
        ExecutorService executor = kind == Kind.MANUAL_RENDER ? manualPool : pool;
        Semaphore lane = kind == Kind.MANUAL_RENDER ? PROCESS_MANUAL_RENDER_LANE : heavyLane;
        job.future = executor.submit(() -> {
            boolean locked = false;
            try {
                setState(job, STATE_PREPARING,
                        kind == Kind.HEAVY ? "Waiting for safe render lane" : "Preparing");
                if (kind == Kind.HEAVY || kind == Kind.MANUAL_RENDER) {
                    lane.acquire();
                    locked = true;
                    waitForSafeDevice(job);
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                setState(job, STATE_RUNNING, job.detail);
                work.run(job);
                if (!isTerminal(job.state)) {
                    job.progress = 100;
                    setState(job, STATE_COMPLETED, job.detail.isEmpty() ? "Completed" : job.detail);
                }
            } catch (java.util.concurrent.CancellationException cancelled) {
                setState(job, STATE_CANCELLED, "Cancelled");
            } catch (InterruptedException interrupted) {
                setState(job, STATE_CANCELLED, "Cancelled");
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                setState(job, STATE_FAILED, error.getMessage() == null ? "Job failed" : error.getMessage());
            } finally {
                if (locked) lane.release();
                persist();
                PROCESS_RUNNING.remove(job.id,job);
            }
        });
        return job;
    }

    public boolean cancel(String id) {
        Job job = PROCESS_RUNNING.get(id);if(job!=null&&job.owner!=this)return job.owner.cancel(id);
        if(job==null)job=jobs.get(id);
        if (job == null || isTerminal(job.state)) return false;
        setState(job, STATE_CANCELLED, "Cancelled");
        if (job.future != null) job.future.cancel(true);
        PROCESS_RUNNING.remove(id,job);
        return true;
    }

    public int cancelAll() {
        int count = 0;
        Map<String,Job> all=new LinkedHashMap<>(jobs);all.putAll(PROCESS_RUNNING);
        for (Job job : all.values()) {
            if (!isTerminal(job.state) && cancel(job.id)) count++;
        }
        return count;
    }

    public int cancelAutonomous() {
        int count = 0;
        Map<String,Job> all=new LinkedHashMap<>(jobs);all.putAll(PROCESS_RUNNING);
        for (Job job : all.values()) if (job.origin == Origin.AUTONOMOUS && !isTerminal(job.state) && cancel(job.id)) count++;
        return count;
    }

    public boolean isManual(String id) { Job job=PROCESS_RUNNING.get(id);if(job==null)job=jobs.get(id);return job!=null && job.kind==Kind.MANUAL_RENDER; }

    public boolean isOwner(String id) { return get(id).optJSONObject("job")!=null && "owner".equals(get(id).optJSONObject("job").optString("origin")); }

    /** Used only after a recovered output has been independently verified. Never revives cancellation. */
    public boolean completeRecovered(String id,JSONObject verifiedResult){
        if(verifiedResult==null||!verifiedResult.optBoolean("ok"))throw new IllegalArgumentException("Verified recovered result is required");
        Job job=PROCESS_RUNNING.get(id);if(job!=null&&job.owner!=this)return job.owner.completeRecovered(id,verifiedResult);
        if(job==null)job=jobs.get(id);if(job==null)return false;
        completeDurably(job,verifiedResult);return true;
    }
    public boolean commitRecovered(String id,Work publication)throws Exception{
        Job job=PROCESS_RUNNING.get(id);if(job!=null&&job.owner!=this)return job.owner.commitRecovered(id,publication);
        if(job==null)job=jobs.get(id);if(job==null)return false;job.commit(publication);return true;
    }
    private void completeDurably(Job job,JSONObject recovered){
        // Snapshot other jobs BEFORE taking this job's monitor; persistence must not acquire other monitors.
        java.util.ArrayList<JSONObject> snapshots=new java.util.ArrayList<>();
        for(Job other:jobs.values())if(other!=job)snapshots.add(other.json());
        synchronized(job){
            if(STATE_COMPLETED.equals(job.state)){snapshots.add(job.json());persistSnapshots(snapshots);return;}
            job.checkActive();long time=System.currentTimeMillis(),revision=job.journalRevision+1;
            JSONObject result=recovered==null?job.result:recovered;
            JSONObject completed=job.json();
            try{completed.put("state",STATE_COMPLETED).put("progress",100).put("updatedAt",time).put("journalRevision",revision);
                if(result!=null)completed.put("result",new JSONObject(result.toString()));}
            catch(org.json.JSONException invalid){throw new IllegalStateException(invalid);}
            snapshots.add(completed);persistSnapshots(snapshots);
            // Expose terminal completion only after the synchronous journal commit succeeds.
            job.state=STATE_COMPLETED;job.progress=100;job.updatedAt=time;job.journalRevision=revision;job.result=result;
        }
    }

    private Map<String,JSONObject> journalView(){
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        try{
            JSONArray saved=new JSONArray(prefs.getString(KEY_JOBS,"[]"));
            for(int i=0;i<saved.length();i++){JSONObject row=saved.getJSONObject(i);rows.put(row.getString("id"),row);}
            for(Job job:jobs.values()){
                JSONObject local=job.json(),previous=rows.get(job.id);
                if(previous==null||local.optLong("journalRevision")>previous.optLong("journalRevision"))rows.put(job.id,local);
            }
            for(Job job:PROCESS_RUNNING.values())rows.put(job.id,job.json());
        }catch(org.json.JSONException invalid){throw new IllegalStateException("Invalid job journal",invalid);}
        return rows;
    }

    public JSONObject get(String id) {
        JSONObject out = new JSONObject();
        try {
            JSONObject row=id==null?null:journalView().get(id);
            out.put("found",row!=null);
            if(row==null)out.put("jobId",id==null?"":id);else out.put("job",row);
        } catch (org.json.JSONException invalid) {throw new IllegalStateException(invalid);}
        return out;
    }

    public JSONObject state() {
        JSONObject root=new JSONObject();JSONArray arr=new JSONArray();
        journalView().values().stream().sorted((a,b)->Long.compare(b.optLong("updatedAt"),a.optLong("updatedAt"))).limit(30).forEach(arr::put);
        try{
            root.put("jobs",arr);root.put("memory",memoryState());root.put("thermal",thermalState());
            root.put("heavyLaneBusy",heavyLane.availablePermits()==0);
            root.put("manualRenderLaneBusy",PROCESS_MANUAL_RENDER_LANE.availablePermits()==0);
            root.put("parallelLightCapacity",2);
        }catch(org.json.JSONException invalid){throw new IllegalStateException(invalid);}
        return root;
    }

    public void shutdown() {
        // Closing an Activity does not revoke Service work or restored checkpoints.
        for(Job job:jobs.values())if(job.owner==this&&job.future!=null&&!isTerminal(job.state))cancel(job.id);
        pool.shutdownNow();
        manualPool.shutdownNow();
        persist();
    }

    private void setState(Job job, String state, String detail) {
        synchronized(job){
        String current = canonicalState(job.state);
        String next = canonicalState(state);
        if (!canTransition(current, next)) return;
        job.state = next;
        job.detail = detail == null ? "" : detail;
        job.updatedAt = System.currentTimeMillis();
        job.journalRevision++;
        }
        persist();
    }

    public static boolean isTerminal(String state) {
        String value = canonicalState(state);
        return STATE_COMPLETED.equals(value)
                || STATE_FAILED.equals(value)
                || STATE_CANCELLED.equals(value);
    }

    public static boolean canTransition(String from, String to) {
        String current = canonicalState(from);
        String next = canonicalState(to);
        if (current.equals(next)) return true;
        if (isTerminal(current)) return false;
        if (STATE_CANCELLED.equals(next) || STATE_FAILED.equals(next)) return true;
        switch (current) {
            case STATE_QUEUED:
                return STATE_PREPARING.equals(next)
                        || STATE_RUNNING.equals(next)
                        || isWaiting(next);
            case STATE_PREPARING:
                return STATE_RUNNING.equals(next)
                        || STATE_CHECKPOINTED.equals(next)
                        || isWaiting(next);
            case STATE_RUNNING:
                return STATE_CHECKPOINTED.equals(next)
                        || STATE_COMPLETED.equals(next)
                        || isWaiting(next);
            case STATE_CHECKPOINTED:
                return STATE_PREPARING.equals(next)
                        || STATE_RUNNING.equals(next)
                        || STATE_COMPLETED.equals(next)
                        || isWaiting(next);
            default:
                if (isWaiting(current)) {
                    return STATE_PREPARING.equals(next)
                            || STATE_RUNNING.equals(next)
                            || STATE_CHECKPOINTED.equals(next);
                }
                return false;
        }
    }

    private static boolean isWaiting(String state) {
        return STATE_WAITING_NETWORK.equals(state)
                || STATE_WAITING_STORAGE.equals(state)
                || STATE_WAITING_MEMORY.equals(state)
                || STATE_WAITING_THERMAL.equals(state)
                || STATE_WAITING_NATIVE.equals(state);
    }

    private static String canonicalState(String state) {
        String value = state == null ? "" : state.trim().toLowerCase();
        if (value.isEmpty()) return STATE_QUEUED;
        if ("waiting".equals(value)) return STATE_PREPARING;
        if ("retrying".equals(value) || "interrupted".equals(value)) return STATE_CHECKPOINTED;
        return value;
    }

    private void waitForSafeDevice(Job job) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.getMemoryInfo(info);
            boolean lowMemory = info.lowMemory || info.availMem < 520L * 1024L * 1024L;
            int thermal = thermalStatus();
            boolean hot = thermal >= PowerManager.THERMAL_STATUS_SEVERE;
            if (!lowMemory && !hot) return;

            String waitingState = hot ? STATE_WAITING_THERMAL : STATE_WAITING_MEMORY;
            String detail = hot
                    ? "Thermal governor paused heavy work; checkpoint preserved until the phone cools"
                    : "Memory governor paused heavy work; checkpoint preserved until memory pressure drops";
            setState(job, waitingState, detail);
            job.checkpoint(job.stage, job.progress, detail);
            Thread.sleep(hot ? 2500L : 1200L);
        }
    }

    public void awaitSafeCheckpoint(Job job, String stage) throws InterruptedException {
        if (job == null || (job.kind != Kind.HEAVY && job.kind != Kind.MANUAL_RENDER)) return;
        if (stage != null && !stage.trim().isEmpty()) job.stage = stage.trim();
        waitForSafeDevice(job);
        if (!STATE_CANCELLED.equals(job.state)) setState(job, STATE_RUNNING, job.detail);
    }

    private JSONObject memoryState() {
        JSONObject o = new JSONObject();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(info);
            o.put("availableMb", info.availMem / 1024 / 1024);
            o.put("totalMb", info.totalMem / 1024 / 1024);
            o.put("lowMemory", info.lowMemory);
            o.put("safeForHeavyWork", !info.lowMemory && info.availMem >= 520L * 1024L * 1024L);
        } catch (Exception ignored) {}
        return o;
    }

    private JSONObject thermalState() {
        JSONObject o = new JSONObject();
        try {
            int value = thermalStatus();
            o.put("status", value);
            o.put("safeForHeavyWork", value < PowerManager.THERMAL_STATUS_SEVERE);
        } catch (Exception ignored) {}
        return o;
    }

    private int thermalStatus() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm == null ? PowerManager.THERMAL_STATUS_NONE : pm.getCurrentThermalStatus();
        } catch (Exception ignored) {
            return PowerManager.THERMAL_STATUS_NONE;
        }
    }

    private void persist() {
        java.util.ArrayList<JSONObject> updates=new java.util.ArrayList<>();
        for(Job job:jobs.values())updates.add(job.json());
        persistSnapshots(updates);
    }
    private void persistSnapshots(java.util.List<JSONObject> updates){
        synchronized(JOURNAL_LOCK){
            try{
                Map<String,JSONObject> merged=new LinkedHashMap<>();JSONArray previous=new JSONArray(prefs.getString(KEY_JOBS,"[]"));
                long cutoff=System.currentTimeMillis()-48L*60L*60L*1000L;
                for(int i=0;i<previous.length();i++){JSONObject row=previous.optJSONObject(i);if(row!=null&&row.optLong("updatedAt")>=cutoff)merged.put(row.optString("id"),row);}
                for(JSONObject row:updates){JSONObject old=merged.get(row.optString("id"));
                    if(old==null||row.optLong("journalRevision",1)>old.optLong("journalRevision",1))merged.put(row.optString("id"),row);
                }
                java.util.ArrayList<JSONObject> rows=new java.util.ArrayList<>(merged.values());rows.sort((a,b)->Long.compare(b.optLong("updatedAt"),a.optLong("updatedAt")));
                JSONArray out=new JSONArray();int terminal=0;
                for(JSONObject row:rows)if(!isTerminal(row.optString("state"))||terminal++<40)out.put(row);
                if(!prefs.edit().putString(KEY_JOBS,out.toString()).commit())throw new IllegalStateException("Could not persist job checkpoint");
            }catch(org.json.JSONException invalid){throw new IllegalStateException("Invalid job journal",invalid);}
        }
    }

    private void restoreRecoveryState() {
        String raw = prefs.getString(KEY_JOBS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            long cutoff = System.currentTimeMillis() - 48L * 60L * 60L * 1000L;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long updated = o.optLong("updatedAt", 0);
                if (updated < cutoff) continue;
                if(PROCESS_RUNNING.containsKey(o.optString("id")))continue;
                Kind kind = "manual_render".equals(o.optString("kind")) ? Kind.MANUAL_RENDER
                        : "heavy".equals(o.optString("kind")) ? Kind.HEAVY : Kind.LIGHT;
                Job job = new Job(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "Recovered job"), kind, "owner".equals(o.optString("origin"))||kind==Kind.MANUAL_RENDER?Origin.OWNER:Origin.AUTONOMOUS, o.optLong("createdAt", updated));
                job.owner = this;
                job.progress = o.optInt("progress", 0);
                job.updatedAt = updated;
                job.journalRevision=o.optLong("journalRevision",1);
                job.stage = o.optString("stage", "recovered");
                job.projectId=o.optString("projectId","");
                JSONArray inputs=o.optJSONArray("inputAssetIds");java.util.Set<String> ids=new java.util.TreeSet<>();
                if(inputs!=null)for(int j=0;j<inputs.length();j++){String id=inputs.optString(j,"");if(!id.isEmpty())ids.add(id);}
                job.inputAssetIds=java.util.Collections.unmodifiableSet(ids);
                job.recoverable = o.optBoolean("recoverable", true);
                job.retryCount = o.optInt("retryCount", 0);
                job.lastCheckpointAt = o.optLong("lastCheckpointAt", updated);
                JSONObject savedResult = o.optJSONObject("result");
                if (savedResult != null) {
                    try { job.result = new JSONObject(savedResult.toString()); }
                    catch (Exception ignored) { job.result = savedResult; }
                }
                String state = canonicalState(o.optString("state", STATE_CHECKPOINTED));
                if (!isTerminal(state)) {
                    job.state = STATE_CHECKPOINTED;
                    job.recoverable = true;
                    job.detail = "App restarted after checkpoint '" + job.stage + "'. Recoverable work is preserved for retry.";
                    job.journalRevision++;
                } else {
                    job.state = state;
                    job.detail = o.optString("detail", "");
                }
                jobs.put(job.id, job);
            }
        } catch (Exception ignored) {}
        persist();
    }
}
