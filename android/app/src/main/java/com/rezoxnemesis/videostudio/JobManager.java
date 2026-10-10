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
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class JobManager {
    public enum Kind { LIGHT, HEAVY }

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
        public boolean ownerInitiated;
        volatile boolean restartSuspended;
        volatile Future<?> future;
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
                o.put("ownerInitiated", ownerInitiated);
                o.put("restartSuspended", restartSuspended);
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
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private volatile boolean accepting = true;
    // One process-wide dispatcher protects codecs across Activity/service managers.
    // Waiting jobs do not occupy light workers or acquire the render gate in FIFO
    // order. The owner goes ahead of queued agent work, never interrupts an active
    // export, and still waits for the same thermal/memory safeguards.
    private static final AtomicLong HEAVY_ORDER = new AtomicLong();
    private static final ThreadPoolExecutor HEAVY_POOL = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>());
    private static final class HeavyTask extends FutureTask<Void> implements Comparable<HeavyTask> {
        final int priority;
        final long order = HEAVY_ORDER.getAndIncrement();
        HeavyTask(Runnable work, boolean owner) {
            super(work, null);
            priority = owner ? 0 : 1;
        }
        @Override public int compareTo(HeavyTask other) {
            int ranked = Integer.compare(priority, other.priority);
            return ranked != 0 ? ranked : Long.compare(order, other.order);
        }
    }
    private static final class YieldToOwner extends InterruptedException {}
    private final Semaphore heavyLane = PROCESS_HEAVY_LANE;
    private static final Map<String, Job> PROCESS_JOBS = new ConcurrentHashMap<>();
    private static final Object RECOVERY_LOCK = new Object();
    private static boolean processRecoveryLoaded;
    private final Map<String, Job> jobs = PROCESS_JOBS;

    public JobManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (RECOVERY_LOCK) {
            if (!processRecoveryLoaded) {
                restoreRecoveryState();
                processRecoveryLoaded = true;
            }
        }
    }

    public Job submit(String name, Kind kind, Work work) {
        return submit(name, kind, work, false);
    }

    public Job submitOwnerPriority(String name, Kind kind, Work work) {
        return submit(name, kind, work, true);
    }

    private synchronized Job submit(String name, Kind kind, Work work, boolean ownerPriority) {
        if (!accepting) throw new IllegalStateException("This job dispatcher has stopped; restart the controller before admitting work");
        if (work == null) throw new IllegalArgumentException("Job work is required");
        Job job = new Job(name, kind);
        job.ownerInitiated = ownerPriority;
        job.detail = ownerPriority && kind == Kind.HEAVY
                ? "Owner export admitted; takes the next safe render lane"
                : "Waiting to start";
        job.owner = this;
        jobs.put(job.id, job);
        persist();
        AtomicReference<Runnable> runner = new AtomicReference<>();
        Runnable run = () -> {
            boolean locked = false;
            try {
                if (STATE_CANCELLED.equals(job.state) || job.restartSuspended) return;
                setState(job, STATE_PREPARING,
                        kind == Kind.HEAVY ? "Waiting for safe render lane" : "Preparing");
                if (kind == Kind.HEAVY) {
                    heavyLane.acquire();
                    locked = true;
                    waitForSafeDevice(job, true);
                }
                if (Thread.currentThread().isInterrupted() || STATE_CANCELLED.equals(job.state) || job.restartSuspended) throw new InterruptedException();
                setState(job, STATE_RUNNING, job.detail);
                work.run(job);
                if (!isTerminal(job.state)) {
                    job.progress = 100;
                    setState(job, STATE_COMPLETED, job.detail.isEmpty() ? "Completed" : job.detail);
                }
            } catch (YieldToOwner yield) {
                setState(job, STATE_CHECKPOINTED, "Owner work takes the next safe render lane; autonomous work remains queued");
                enqueueHeavy(job, runner.get());
            } catch (InterruptedException interrupted) {
                if (job.restartSuspended) setState(job, STATE_CHECKPOINTED, "Controller stopped; durable work can resume after restart");
                else setState(job, STATE_CANCELLED, "Cancelled");
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (job.restartSuspended) setState(job, STATE_CHECKPOINTED, "Controller stopped; durable work can resume after restart");
                else setState(job, STATE_FAILED, error.getMessage() == null ? "Job failed" : error.getMessage());
            } finally {
                if (locked) heavyLane.release();
                persist();
            }
        };
        runner.set(run);
        if (kind == Kind.HEAVY) {
            enqueueHeavy(job, run);
        } else {
            try { job.future = pool.submit(run); }
            catch (java.util.concurrent.RejectedExecutionException stopped) {
                setState(job, STATE_FAILED, "Job dispatcher could not admit work");
                throw new IllegalStateException("Job dispatcher could not admit work", stopped);
            }
            if (STATE_CANCELLED.equals(job.state)) job.future.cancel(true);
        }
        return job;
    }

    private static void enqueueHeavy(Job job, Runnable work) {
        synchronized (job) {
            if (isTerminal(job.state) || job.restartSuspended) return;
            HeavyTask task = new HeavyTask(work, job.ownerInitiated);
            job.future = task;
            HEAVY_POOL.execute(task);
        }
    }

    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) return false;
        synchronized (job) {
            if (isTerminal(job.state)) return false;
            job.restartSuspended = false;
            setState(job, STATE_CANCELLED, "Cancelled");
            if (job.future != null) job.future.cancel(true);
        }
        return true;
    }

    public int cancelAll() {
        int count = 0;
        for (Job job : jobs.values()) {
            if (!isTerminal(job.state) && cancel(job.id)) count++;
        }
        return count;
    }

    /** Pausing ChatGPT must not cancel an export started by the owner. */
    public int cancelAutonomous() {
        int count = 0;
        for (Job job : jobs.values()) {
            if (!job.ownerInitiated && !isTerminal(job.state) && cancel(job.id)) count++;
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
        shutdown(false);
    }

    public synchronized void shutdown(boolean preserveOwnerJobs) {
        accepting = false;
        for (Job job : jobs.values()) {
            if (job.owner == this && !isTerminal(job.state) && !(preserveOwnerJobs && job.ownerInitiated)) cancel(job.id);
        }
        if (preserveOwnerJobs) pool.shutdown();
        else pool.shutdownNow();
        persist();
    }
    /** Lifecycle interruption is distinct from an owner cancellation. Recovery
     * plans retain the original request; queued/running futures release resources
     * without converting durable export intent into a cancelled terminal state. */
    public synchronized void suspendForRestart() {
        accepting = false;
        for (Job job : jobs.values()) synchronized (job) {
            if (job.owner != this || isTerminal(job.state)) continue;
            job.restartSuspended = true;
            job.recoverable = true;
            String state = STATE_QUEUED.equals(job.state) ? STATE_WAITING_NATIVE : STATE_CHECKPOINTED;
            setState(job, state, "Controller stopped; durable work can resume after restart");
            if (job.future != null) job.future.cancel(true);
        }
        pool.shutdownNow();
        persist();
    }

    private void setState(Job job, String state, String detail) {
        synchronized (job) {
            String current = canonicalState(job.state);
            String next = canonicalState(state);
            if (!canTransition(current, next)) return;
            job.state = next;
            job.detail = detail == null ? "" : detail;
            job.updatedAt = System.currentTimeMillis();
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

    private void waitForSafeDevice(Job job) throws InterruptedException { waitForSafeDevice(job, false); }

    private void waitForSafeDevice(Job job, boolean beforeWork) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (beforeWork && !job.ownerInitiated && HEAVY_POOL.getQueue().stream().anyMatch(task ->
                    task instanceof HeavyTask && ((HeavyTask) task).priority == 0 && !((HeavyTask) task).isCancelled()))
                throw new YieldToOwner();
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
        if (job == null || job.kind != Kind.HEAVY) return;
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
        synchronized (RECOVERY_LOCK) {
            java.util.List<Job> terminal = new java.util.ArrayList<>();
            for (Job job : jobs.values()) if (isTerminal(job.state)) terminal.add(job);
            terminal.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            for (int i = 80; i < terminal.size(); i++) jobs.remove(terminal.get(i).id, terminal.get(i));
            JSONArray arr = new JSONArray();
            jobs.values().stream().filter(j -> !isTerminal(j.state))
                    .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt)).forEach(j -> arr.put(j.json()));
            jobs.values().stream().filter(j -> isTerminal(j.state))
                    .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt)).limit(40).forEach(j -> arr.put(j.json()));
            prefs.edit().putString(KEY_JOBS, arr.toString()).apply();
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
                Kind kind = "heavy".equals(o.optString("kind")) ? Kind.HEAVY : Kind.LIGHT;
                Job job = new Job(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "Recovered job"), kind, o.optLong("createdAt", updated));
                job.owner = this;
                job.ownerInitiated = o.optBoolean("ownerInitiated", false);
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
