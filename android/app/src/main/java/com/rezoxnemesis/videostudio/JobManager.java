package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

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
        public volatile String state = "queued";
        public volatile int progress = 0;
        public volatile String detail = "";
        Future<?> future;

        Job(String name, Kind kind) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.kind = kind;
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
            } catch (Exception ignored) {}
            return o;
        }
    }

    public interface Work {
        void run(Job job) throws Exception;
    }

    private final Context context;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Semaphore heavyLane = new Semaphore(1);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public JobManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public Job submit(String name, Kind kind, Work work) {
        Job job = new Job(name, kind);
        jobs.put(job.id, job);
        job.future = pool.submit(() -> {
            boolean locked = false;
            try {
                if (kind == Kind.HEAVY) {
                    job.state = "waiting";
                    heavyLane.acquire();
                    locked = true;
                    waitForSafeDevice(job);
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                job.state = "running";
                work.run(job);
                if (!"cancelled".equals(job.state) && !"failed".equals(job.state)) {
                    job.progress = 100;
                    job.state = "completed";
                }
            } catch (InterruptedException interrupted) {
                job.state = "cancelled";
                job.detail = "Cancelled";
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                job.state = "failed";
                job.detail = error.getMessage() == null ? "Job failed" : error.getMessage();
            } finally {
                if (locked) heavyLane.release();
            }
        });
        return job;
    }

    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) return false;
        job.state = "cancelled";
        if (job.future != null) job.future.cancel(true);
        return true;
    }

    public JSONObject state() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Job job : jobs.values()) arr.put(job.json());
        try {
            root.put("jobs", arr);
            root.put("memory", memoryState());
            root.put("thermal", thermalState());
        } catch (Exception ignored) {}
        return root;
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private void waitForSafeDevice(Job job) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.getMemoryInfo(info);
            boolean lowMemory = info.lowMemory || info.availMem < 420L * 1024L * 1024L;
            int thermal = thermalStatus();
            boolean hot = thermal >= PowerManager.THERMAL_STATUS_SEVERE;
            if (!lowMemory && !hot) return;
            job.detail = hot ? "Cooling device before heavy work" : "Waiting for memory pressure to drop";
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Device is under too much memory or thermal pressure for a heavy job");
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
}
