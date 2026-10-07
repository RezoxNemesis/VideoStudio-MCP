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
    public enum Kind { LIGHT, HEAVY }

    public static final class Job {
        public final String id;
        public final String name;
        public final Kind kind;
        public final long createdAt;
        public volatile long updatedAt;
        public volatile String state = "queued";
        public volatile int progress = 0;
        public volatile String detail = "";
        public volatile String stage = "queued";
        public volatile boolean recoverable = true;
        public volatile int retryCount = 0;
        public volatile long lastCheckpointAt;
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
            } catch (Exception ignored) {}
            return o;
        }
    }

    public interface Work {
        void run(Job job) throws Exception;
    }

    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_JOBS = "job_recovery_snapshot";
    private final Context context;
    private final SharedPreferences prefs;
    private static final Semaphore PROCESS_HEAVY_LANE = new Semaphore(1, true);
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
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
        job.future = pool.submit(() -> {
            boolean locked = false;
            try {
                if (kind == Kind.HEAVY) {
                    setState(job, "waiting", "Waiting for safe render lane");
                    heavyLane.acquire();
                    locked = true;
                    waitForSafeDevice(job);
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                setState(job, "running", job.detail);
                work.run(job);
                if (!"cancelled".equals(job.state) && !"failed".equals(job.state)) {
                    job.progress = 100;
                    setState(job, "completed", job.detail.isEmpty() ? "Completed" : job.detail);
                }
            } catch (InterruptedException interrupted) {
                setState(job, "cancelled", "Cancelled");
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                setState(job, "failed", error.getMessage() == null ? "Job failed" : error.getMessage());
            } finally {
                if (locked) heavyLane.release();
                persist();
            }
        });
        return job;
    }

    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) return false;
        setState(job, "cancelled", "Cancelled");
        if (job.future != null) job.future.cancel(true);
        return true;
    }

    public int cancelAll() {
        int count = 0;
        for (Job job : jobs.values()) {
            if ("queued".equals(job.state) || "waiting".equals(job.state) || "waiting_thermal".equals(job.state)
                    || "waiting_memory".equals(job.state) || "running".equals(job.state) || "retrying".equals(job.state)) {
                if (cancel(job.id)) count++;
            }
        }
        return count;
    }

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
        persist();
    }

    private void setState(Job job, String state, String detail) {
        job.state = state;
        job.detail = detail == null ? "" : detail;
        job.updatedAt = System.currentTimeMillis();
        persist();
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

            String waitingState = hot ? "waiting_thermal" : "waiting_memory";
            String detail = hot
                    ? "Thermal governor paused heavy work; checkpoint preserved until the phone cools"
                    : "Memory governor paused heavy work; checkpoint preserved until memory pressure drops";
            setState(job, waitingState, detail);
            job.checkpoint(job.stage, job.progress, detail);
            Thread.sleep(hot ? 2500L : 1200L);
        }
    }

    public void awaitSafeCheckpoint(Job job, String stage) throws InterruptedException {
        if (job == null || job.kind != Kind.HEAVY) return;
        if (stage != null && !stage.trim().isEmpty()) job.stage = stage.trim();
        waitForSafeDevice(job);
        if (!"cancelled".equals(job.state)) setState(job, "running", job.detail);
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
                Kind kind = "heavy".equals(o.optString("kind")) ? Kind.HEAVY : Kind.LIGHT;
                Job job = new Job(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "Recovered job"), kind, o.optLong("createdAt", updated));
                job.owner = this;
                job.progress = o.optInt("progress", 0);
                job.updatedAt = updated;
                job.stage = o.optString("stage", "recovered");
                job.recoverable = o.optBoolean("recoverable", true);
                job.retryCount = o.optInt("retryCount", 0);
                job.lastCheckpointAt = o.optLong("lastCheckpointAt", updated);
                String state = o.optString("state", "interrupted");
                if ("queued".equals(state) || "waiting".equals(state) || "waiting_thermal".equals(state)
                        || "waiting_memory".equals(state) || "running".equals(state) || "retrying".equals(state)) {
                    job.state = "interrupted";
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
