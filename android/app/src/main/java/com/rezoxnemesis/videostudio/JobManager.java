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
        Future<?> future;
        private JobManager owner;

        Job(String name, Kind kind) {
            this(UUID.randomUUID().toString(), name, kind, System.currentTimeMillis());
        }

        Job(String id, String name, Kind kind, long createdAt) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.lastCheckpointAt = createdAt;
        }

        public void checkpoint(int progress, String detail) {
            checkpoint(this.stage, progress, detail);
        }

        public void checkpoint(String stage, int progress, String detail) {
            this.stage = stage == null || stage.trim().isEmpty() ? this.stage : stage.trim();
            this.progress = Math.max(0, Math.min(100, progress));
            this.detail = detail == null ? "" : detail;
            this.updatedAt = System.currentTimeMillis();
            this.lastCheckpointAt = this.updatedAt;
            if (owner != null) owner.persist();
        }

        public void setResult(JSONObject value) {
            if (value == null) {
                this.result = null;
            } else {
                try { this.result = new JSONObject(value.toString()); }
                catch (Exception ignored) { this.result = value; }
            }
            this.updatedAt = System.currentTimeMillis();
            if (owner != null) owner.persist();
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("kind", kind.name().toLowerCase());
                o.put("state", state);
                o.put("progress", progress);
                o.put("detail", detail);
                o.put("stage", stage);
                o.put("recoverable", recoverable);
                o.put("retryCount", retryCount);
                o.put("lastCheckpointAt", lastCheckpointAt);
                o.put("createdAt", createdAt);
                o.put("updatedAt", updatedAt);
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
        Job job = new Job(name, kind);
        job.owner = this;
        jobs.put(job.id, job);
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
            } catch (InterruptedException interrupted) {
                setState(job, STATE_CANCELLED, "Cancelled");
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                setState(job, STATE_FAILED, error.getMessage() == null ? "Job failed" : error.getMessage());
            } finally {
                if (locked) lane.release();
                persist();
            }
        });
        return job;
    }

    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null || isTerminal(job.state)) return false;
        setState(job, STATE_CANCELLED, "Cancelled");
        if (job.future != null) job.future.cancel(true);
        return true;
    }

    public int cancelAll() {
        int count = 0;
        for (Job job : jobs.values()) {
            if (!isTerminal(job.state) && cancel(job.id)) count++;
        }
        return count;
    }

    public int cancelAutonomous() {
        int count = 0;
        for (Job job : jobs.values()) if (job.kind != Kind.MANUAL_RENDER && !isTerminal(job.state) && cancel(job.id)) count++;
        return count;
    }

    public boolean isManual(String id) { Job job=jobs.get(id);return job!=null && job.kind==Kind.MANUAL_RENDER; }

    public JSONObject get(String id) {
        JSONObject out = new JSONObject();
        try {
            Job job = id == null ? null : jobs.get(id);
            if (job == null) {
                out.put("found", false);
                out.put("jobId", id == null ? "" : id);
            } else {
                out.put("found", true);
                out.put("job", job.json());
            }
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject state() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        Map<String, Job> sorted = new LinkedHashMap<>();
        jobs.values().stream()
                .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt))
                .limit(30)
                .forEach(j -> sorted.put(j.id, j));
        for (Job job : sorted.values()) arr.put(job.json());
        try {
            root.put("jobs", arr);
            root.put("memory", memoryState());
            root.put("thermal", thermalState());
            root.put("heavyLaneBusy", heavyLane.availablePermits() == 0);
            root.put("parallelLightCapacity", 2);
        } catch (Exception ignored) {}
        return root;
    }

    public void shutdown() {
        cancelAll();
        pool.shutdownNow();
        manualPool.shutdownNow();
        persist();
    }

    private void setState(Job job, String state, String detail) {
        String current = canonicalState(job.state);
        String next = canonicalState(state);
        if (!canTransition(current, next)) return;
        job.state = next;
        job.detail = detail == null ? "" : detail;
        job.updatedAt = System.currentTimeMillis();
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

    private synchronized void persist() {
        JSONArray arr = new JSONArray();
        jobs.values().stream()
                .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt))
                .limit(40)
                .forEach(j -> arr.put(j.json()));
        prefs.edit().putString(KEY_JOBS, arr.toString()).apply();
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
                Kind kind = "manual_render".equals(o.optString("kind")) ? Kind.MANUAL_RENDER
                        : "heavy".equals(o.optString("kind")) ? Kind.HEAVY : Kind.LIGHT;
                Job job = new Job(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "Recovered job"), kind, o.optLong("createdAt", updated));
                job.owner = this;
                job.progress = o.optInt("progress", 0);
                job.updatedAt = updated;
                job.stage = o.optString("stage", "recovered");
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
