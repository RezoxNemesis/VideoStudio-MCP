package com.rezoxnemesis.videostudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.UUID;

public final class ControlService extends Service implements AppProtocol.Callback {
    private static final String CHANNEL = "videostudio_control";
    private static final int NOTIFICATION_ID = 5101;

    private ProjectStore store;
    private JobManager jobs;
    private AppProtocol protocol;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new ProjectStore(this);
        jobs = new JobManager(this);
        protocol = new AppProtocol(this, this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("ChatGPT control connected"));
        syncState();
        protocol.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        syncState();
        protocol.registerNow();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (protocol != null) protocol.stop();
        if (jobs != null) jobs.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConnection(boolean connected, String detail) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(connected ? "ChatGPT control ready" : "Reconnecting securely"));
    }

    @Override
    public void onCommand(JSONObject command) {
        String action = command.optString("action");
        JSONObject p = command.optJSONObject("parameters");
        if (p == null) p = new JSONObject();
        try {
            JSONObject result;
            switch (action) {
                case "ping":
                case "get_state":
                    result = state();
                    result.put("ok", true);
                    complete(command, result);
                    return;
                case "create_project": {
                    ProjectStore.Project project = store.create(p.optString("name", "ChatGPT Project"));
                    result = new JSONObject();
                    result.put("ok", true);
                    result.put("projectId", project.id);
                    syncState();
                    complete(command, result);
                    return;
                }
                case "select_project": {
                    ProjectStore.Project project = store.get(p.optString("projectId"));
                    if (project == null) throw new IllegalArgumentException("Project not found");
                    store.setActive(project.id);
                    result = new JSONObject();
                    result.put("ok", true);
                    result.put("projectId", project.id);
                    syncState();
                    complete(command, result);
                    return;
                }
                case "delete_project": {
                    store.delete(p.optString("projectId"));
                    result = new JSONObject();
                    result.put("ok", true);
                    syncState();
                    complete(command, result);
                    return;
                }
                case "apply_edit_plan":
                    complete(command, applyPlan(p));
                    syncState();
                    return;
                case "apply_tool":
                    complete(command, applyTool(p));
                    syncState();
                    return;
                case "import_chat_file":
                    complete(command, queuePrivateImport(p));
                    return;
                case "render_prompt_video":
                    complete(command, queuePromptRender(p));
                    return;
                case "cancel_job": {
                    result = new JSONObject();
                    result.put("ok", jobs.cancel(p.optString("jobId")));
                    complete(command, result);
                    return;
                }
                case "stop_all": {
                    int count = jobs.cancelAll();
                    result = new JSONObject();
                    result.put("ok", true);
                    result.put("cancelledJobs", count);
                    complete(command, result);
                    return;
                }
                case "preview_project":
                    result = new JSONObject();
                    result.put("ok", false);
                    result.put("error", "Preview requires the VideoStudio screen to be open");
                    protocol.complete(command, result, "failed");
                    return;
                default:
                    result = new JSONObject();
                    result.put("ok", false);
                    result.put("error", "Native service does not implement action: " + action);
                    protocol.complete(command, result, "failed");
            }
        } catch (Exception error) {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", false);
                result.put("error", error.getMessage() == null ? "Command failed" : error.getMessage());
            } catch (Exception ignored) {}
            protocol.complete(command, result, "failed");
        }
    }

    private JSONObject applyPlan(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        JSONArray clips = p.optJSONArray("clips");
        if (clips == null || clips.length() == 0) throw new IllegalArgumentException("clips are required");
        ArrayList<ProjectStore.Clip> next = new ArrayList<>();
        for (int i = 0; i < clips.length() && i < 120; i++) {
            JSONObject raw = clips.optJSONObject(i);
            if (raw == null) continue;
            ProjectStore.Asset asset = project.asset(raw.optString("assetId"));
            if (asset == null) throw new IllegalArgumentException("Unknown asset on clip " + i);
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = asset.id;
            clip.inMs = raw.has("inMs") ? raw.optLong("inMs") : (long)(raw.optDouble("start", 0) * 1000);
            clip.outMs = raw.has("outMs") ? raw.optLong("outMs") : (long)(raw.optDouble("end", asset.durationMs / 1000d) * 1000);
            clip.inMs = Math.max(0, clip.inMs);
            clip.outMs = Math.max(clip.inMs + 100, Math.min(asset.durationMs > 0 ? asset.durationMs : clip.outMs, clip.outMs));
            clip.speed = (float)Math.max(.25, Math.min(4, raw.optDouble("speed", 1)));
            clip.volume = (float)Math.max(0, Math.min(2, raw.optDouble("volume", 1)));
            clip.transition = raw.optString("transition", "none");
            clip.title = raw.optString("title", "");
            JSONObject effects = raw.optJSONObject("effects");
            clip.effects = effects == null ? new JSONObject() : effects;
            next.add(clip);
        }
        project.clips.clear();
        project.clips.addAll(next);
        store.save(project);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("projectId", project.id);
        result.put("clipCount", next.size());
        result.put("durationMs", project.outputDurationMs());
        return result;
    }

    private JSONObject applyTool(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        int index = p.optInt("clipIndex", 0);
        if (index < 0 || index >= project.clips.size()) throw new IllegalArgumentException("Clip not found");
        ProjectStore.Clip clip = project.clips.get(index);
        JSONObject settings = p.optJSONObject("settings");
        if (settings == null) settings = new JSONObject();
        String tool = p.optString("tool", "effect");

        switch (tool) {
            case "trim":
                clip.inMs = settings.optLong("inMs", clip.inMs);
                clip.outMs = settings.optLong("outMs", clip.outMs);
                break;
            case "speed":
            case "slow_motion":
                clip.speed = (float)Math.max(.25, Math.min(4, settings.optDouble("speed", .5)));
                break;
            case "volume":
                clip.volume = (float)Math.max(0, Math.min(2, settings.optDouble("volume", 1)));
                break;
            case "title":
                clip.title = settings.optString("text", "");
                clip.effects.put("font", settings.optString("font", "sans"));
                clip.effects.put("textAnimation", settings.optString("animation", "fade_up"));
                break;
            case "transition":
                clip.transition = settings.optString("name", "fade");
                clip.effects.put("transitionDurationMs", settings.optInt("durationMs", 350));
                break;
            case "green_screen":
                clip.effects.put("chromaKey", true);
                clip.effects.put("chromaColor", settings.optString("color", "#00FF00"));
                clip.effects.put("chromaTolerance", settings.optDouble("tolerance", .18));
                clip.effects.put("spillSuppression", settings.optDouble("spill", .35));
                break;
            case "motion":
                clip.effects.put("motionPreset", settings.optString("preset", "push_in"));
                clip.effects.put("ease", settings.optString("ease", "easeInOut"));
                break;
            case "keyframes":
                clip.effects.put("keyframes", settings.optJSONArray("keyframes") == null ? new JSONArray() : settings.optJSONArray("keyframes"));
                break;
            case "mask":
                clip.effects.put("mask", settings.optString("shape", "rounded_rect"));
                clip.effects.put("maskFeather", settings.optDouble("feather", .08));
                break;
            case "reframe":
                clip.effects.put("reframe", settings.optString("preset", "9:16_subject_safe"));
                break;
            default:
                clip.effects.put(tool, settings);
                break;
        }
        store.save(project);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("projectId", project.id);
        result.put("clipIndex", index);
        result.put("tool", tool);
        return result;
    }

    private JSONObject queuePrivateImport(JSONObject p) throws Exception {
        String handoffId = p.optString("handoffId");
        if (handoffId.isEmpty()) throw new IllegalArgumentException("Missing private handoff");
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT import");
        String mimeHint = p.optString("mime", "");

        JobManager.Job job = jobs.submit("Private import " + name, JobManager.Kind.LIGHT, state -> {
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            String safe = name.replaceAll("[^a-zA-Z0-9._-]+", "_");
            File file = new File(dir, System.currentTimeMillis() + "_" + (safe.isEmpty() ? "chat_import.bin" : safe));

            HttpURLConnection connection = protocol.openPrivateHandoff(handoffId);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Private attachment transfer failed: HTTP " + code);
            String mime = mimeHint.isEmpty() ? connection.getContentType() : mimeHint;
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            long expected = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                long bytes = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, n);
                    bytes += n;
                    state.progress = expected > 0 ? Math.min(96, (int)(96d * bytes / expected)) : Math.min(94, (int)(bytes / 1024 / 1024));
                    state.detail = (bytes / 1024 / 1024) + " MB securely streamed";
                }
            } finally {
                connection.disconnect();
            }
            store.importGeneratedFile(project, file, name, mime, true);
            state.progress = 100;
            state.detail = "Imported privately";
            syncState();
        });
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        return result;
    }

    private JSONObject queuePromptRender(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        JSONArray scenes = p.optJSONArray("scenes");
        if (scenes == null || scenes.length() == 0) throw new IllegalArgumentException("Prompt-video scenes are required");
        int width = p.optInt("width", 720);
        int height = p.optInt("height", 1280);
        int fps = p.optInt("fps", 24);
        String name = p.optString("name", "AI Prompt Video");

        JobManager.Job job = jobs.submit("Render " + name, JobManager.Kind.HEAVY, state -> {
            File dir = new File(getFilesDir(), "renders");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create render directory");
            File file = new File(dir, System.currentTimeMillis() + "_prompt_video.mp4");
            PromptVideoRenderer renderer = new PromptVideoRenderer();
            renderer.render(this, project, scenes, file, width, height, fps, (progress, detail) -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                state.progress = progress;
                state.detail = detail;
            });
            ProjectStore.Asset asset = store.importGeneratedFile(project, file, name + ".mp4", "video/mp4", true);
            state.detail = "Rendered " + asset.name;
            syncState();
        });

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        return result;
    }

    private ProjectStore.Project resolveProject(String id) {
        ProjectStore.Project project = id == null || id.isEmpty() ? store.active() : store.get(id);
        if (project == null) project = store.create("ChatGPT Project");
        store.setActive(project.id);
        return project;
    }

    private JSONObject state() {
        JSONObject out = new JSONObject();
        try {
            out.put("appVersion", "1.1.0");
            out.put("nativeApp", true);
            out.put("galleryAccess", false);
            out.put("projects", store.summaries().optJSONArray("projects"));
            ProjectStore.Project active = store.active();
            if (active != null) {
                out.put("activeProjectId", active.id);
                out.put("activeProject", store.fullState(active.id));
            }
            out.put("workload", jobs.state());
        } catch (Exception ignored) {}
        return out;
    }

    private void complete(JSONObject command, JSONObject result) {
        protocol.complete(command, result, result.optBoolean("ok", false) ? "completed" : "failed");
    }

    private void syncState() {
        protocol.setLocalState("everything", store.summaries());
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "VideoStudio control", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the private ChatGPT control connection available.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("VideoStudio")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }
}
