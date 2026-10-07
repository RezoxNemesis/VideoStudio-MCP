package com.rezoxnemesis.videostudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ControlService extends Service implements AppProtocol.Callback {
    public static final String ACTION_CANCEL_ALL = "com.rezoxnemesis.videostudio.CANCEL_ALL";
    public static final String ACTION_PAUSE = "com.rezoxnemesis.videostudio.PAUSE_CONTROL";
    public static final String ACTION_RESUME = "com.rezoxnemesis.videostudio.RESUME_CONTROL";
    public static final String ACTION_SYNC = "com.rezoxnemesis.videostudio.SYNC_STATE";
    private static final String CHANNEL = "videostudio_private_control";
    private static final int NOTIFICATION_ID = 6101;
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_MODE = "permission_mode";
    private static final String KEY_FILE = "allowed_asset_id";
    private static final String KEY_SERVICE_ONLINE = "control_service_online";
    private static final String KEY_SERVICE_DETAIL = "control_service_detail";

    private ProjectStore store;
    private JobManager jobs;
    private AppProtocol protocol;
    private NativeRenderEngine renderEngine;
    private NativeRenderEngine.Handle activeRender;
    private PromptVideoEngine promptVideoEngine;
    private NativeMediaAnalyzer mediaAnalyzer;
    private SharedPreferences prefs;
    private CommandJournal commandJournal;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        commandJournal = new CommandJournal(this);
        store = new ProjectStore(this);
        jobs = new JobManager(this);
        protocol = new AppProtocol(this, this);
        renderEngine = new NativeRenderEngine(this);
        promptVideoEngine = new PromptVideoEngine(this);
        mediaAnalyzer = new NativeMediaAnalyzer(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("VideoStudio MCP v3 starting"));
        syncProtocolState();
        protocol.start();
        markService(true, "VideoStudio MCP v3 Native Agent active");
        ActivityLog.add(this, "system", "VideoStudio v3 control online", "MCP v3 Native Agent background controller started", "success", null, null, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CANCEL_ALL.equals(action)) {
            int cancelled = cancelAllNativeWork();
            ActivityLog.add(this, "user", "Cancel all jobs", cancelled + " active job(s) cancelled", "success", null, null, null);
        } else if (ACTION_PAUSE.equals(action)) {
            protocol.setControlPaused(true);
            int cancelled = cancelAllNativeWork();
            ActivityLog.add(this, "user", "ChatGPT control paused", cancelled + " active job(s) cancelled", "info", null, null, null);
            updateNotification("ChatGPT control paused");
        } else if (ACTION_RESUME.equals(action)) {
            protocol.setControlPaused(false);
            ActivityLog.add(this, "user", "ChatGPT control resumed", "VideoStudio MCP v3 is accepting commands again", "success", null, null, null);
            syncProtocolState();
            protocol.registerNow();
            updateNotification("MCP v3 control ready");
        } else if (ACTION_SYNC.equals(action)) {
            syncProtocolState();
            protocol.registerNow();
        }
        syncProtocolState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ActivityLog.add(this, "system", "VideoStudio control stopped", "Background controller stopped", "info", null, null, null);
        markService(false, "Control service stopped");
        if (activeRender != null) activeRender.cancel();
        if (jobs != null) jobs.shutdown();
        if (protocol != null) protocol.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConnection(boolean connected, String detail) {
        markService(connected, detail);
        ActivityLog.add(this, "system", connected ? "MCP connected" : "MCP reconnecting", detail, connected ? "success" : "running", null, null, null);
        updateNotification(protocol != null && protocol.isControlPaused()
                ? "ChatGPT control paused"
                : (connected ? "MCP v3 control ready" : "Reconnecting securely"));
    }

    @Override
    public void onCommand(JSONObject command) {
        String action = command.optString("action");
        JSONObject p = command.optJSONObject("parameters");
        if (p == null) p = new JSONObject();

        String commandId = command.optString("id", "");
        String projectId = p.optString("projectId", "");

        JSONObject terminal = commandJournal.terminal(commandId);
        if (terminal != null) {
            JSONObject priorResult = terminal.optJSONObject("result");
            if (priorResult == null) priorResult = new JSONObject();
            String priorStatus = terminal.optString("status", "completed");
            ActivityLog.add(this, "system", "MCP v3 command replay prevented",
                    friendlyAction(action) + " • returning durable prior result",
                    "success", 100, commandId, projectId);
            protocol.complete(command, priorResult, priorStatus);
            return;
        }
        commandJournal.begin(command);
        ActivityLog.add(this, "chatgpt", friendlyAction(action), commandDetail(action, p), "running", 0, commandId, projectId);

        if (!isAllowed(action, p)) {
            JSONObject denied = new JSONObject();
            try {
                denied.put("ok", false);
                denied.put("error", "Blocked by VideoStudio permission/privacy boundary: " + action);
            } catch (Exception ignored) {}
            ActivityLog.add(this, "chatgpt", friendlyAction(action), denied.optString("error"), "denied", null, commandId, projectId);
            commandJournal.finish(command, denied, "denied");
            protocol.complete(command, denied, "denied");
            return;
        }

        try {
            switch (action) {
                case "ping":
                case "get_state":
                    complete(command, stateJson());
                    return;
                case "activity_note": {
                    String note = p.optString("message", "ChatGPT is working");
                    String noteStatus = p.optString("status", "info");
                    Integer noteProgress = p.has("progress") ? p.optInt("progress") : null;
                    ActivityLog.add(this, "chatgpt", p.optString("title", "ChatGPT progress"), note, noteStatus, noteProgress, commandId, p.optString("projectId", ""));
                    JSONObject result = ok();
                    result.put("logged", true);
                    complete(command, result);
                    return;
                }
                case "create_project": {
                    ProjectStore.Project project = store.create(p.optString("name", "ChatGPT Project"));
                    JSONObject result = ok();
                    result.put("projectId", project.id);
                    syncProtocolState();
                    complete(command, result);
                    return;
                }
                case "select_project": {
                    ProjectStore.Project project = store.get(p.optString("projectId"));
                    if (project == null) throw new IllegalArgumentException("Project not found");
                    store.setActive(project.id);
                    JSONObject result = ok();
                    result.put("projectId", project.id);
                    syncProtocolState();
                    complete(command, result);
                    return;
                }
                case "delete_project": {
                    String id = p.optString("projectId");
                    if (id.isEmpty()) throw new IllegalArgumentException("Project ID is required");
                    store.delete(id);
                    JSONObject result = ok();
                    result.put("deletedProjectId", id);
                    syncProtocolState();
                    complete(command, result);
                    return;
                }
                case "apply_edit_plan":
                    complete(command, applyEditPlan(p));
                    syncProtocolState();
                    return;
                case "apply_tool":
                    complete(command, applyTool(p));
                    syncProtocolState();
                    return;
                case "creator_preset":
                    complete(command, applyCreatorPreset(p));
                    syncProtocolState();
                    return;
                case "autonomous_edit":
                    complete(command, autonomousEdit(p));
                    syncProtocolState();
                    return;
                case "analyse_media":
                    queueAnalysis(command, p);
                    return;
                case "prompt_video":
                    complete(command, queuePromptVideo(p));
                    return;
                case "export_project":
                    complete(command, queueExport(p));
                    return;
                case "cancel_job": {
                    boolean cancelled = jobs.cancel(p.optString("jobId"));
                    if (cancelled && activeRender != null) activeRender.cancel();
                    JSONObject result = ok();
                    result.put("cancelled", cancelled);
                    complete(command, result);
                    return;
                }
                case "cancel_all_jobs":
                case "stop_all": {
                    int count = cancelAllNativeWork();
                    JSONObject result = ok();
                    result.put("cancelledJobs", count);
                    complete(command, result);
                    return;
                }
                case "import_url":
                    complete(command, queueUrlImport(p));
                    return;
                case "import_attachment":
                    complete(command, queueDirectAttachmentImport(p));
                    return;
                case "import_chat_file":
                    complete(command, queuePrivateImport(p));
                    return;
                case "preview_project": {
                    JSONObject result = ok();
                    result.put("previewRequiresForegroundUi", true);
                    result.put("note", "Editing remains available in background; open VideoStudio only for on-screen playback preview.");
                    complete(command, result);
                    return;
                }
                default: {
                    JSONObject result = new JSONObject();
                    result.put("ok", false);
                    result.put("error", "Native background controller does not implement action: " + action);
                    ActivityLog.add(this, "chatgpt", friendlyAction(action), result.optString("error"), "failed", null, commandId, projectId);
                    commandJournal.finish(command, result, "failed");
                    protocol.complete(command, result, "failed");
                }
            }
        } catch (Exception error) {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", false);
                result.put("error", error.getMessage() == null ? "Command failed" : error.getMessage());
            } catch (Exception ignored) {}
            ActivityLog.add(this, "chatgpt", friendlyAction(action), result.optString("error"), "failed", null, commandId, projectId);
            commandJournal.finish(command, result, "failed");
            protocol.complete(command, result, "failed");
        }
    }

    private JSONObject applyEditPlan(JSONObject p) throws Exception {
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
            clip.inMs = raw.has("inMs") ? raw.optLong("inMs") : (long) (raw.optDouble("start", 0) * 1000);
            clip.outMs = raw.has("outMs") ? raw.optLong("outMs") : (long) (raw.optDouble("end", asset.durationMs / 1000d) * 1000);
            clip.inMs = Math.max(0, clip.inMs);
            clip.outMs = Math.max(clip.inMs + 100, Math.min(asset.durationMs > 0 ? asset.durationMs : clip.outMs, clip.outMs));
            clip.speed = (float) Math.max(.25, Math.min(4, raw.optDouble("speed", 1)));
            clip.volume = (float) Math.max(0, Math.min(2, raw.optDouble("volume", 1)));
            clip.transition = raw.optString("transition", "none");
            clip.title = raw.optString("title", "");
            JSONObject effects = raw.optJSONObject("effects");
            clip.effects = effects == null ? new JSONObject() : effects;
            next.add(clip);
        }
        project.clips.clear();
        project.clips.addAll(next);
        store.save(project);
        JSONObject result = ok();
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
        String tool = p.optString("tool", "effect");
        JSONObject settings = p.optJSONObject("settings");
        if (settings == null) settings = new JSONObject();

        switch (tool) {
            case "trim":
                clip.inMs = Math.max(0, settings.optLong("inMs", clip.inMs));
                clip.outMs = Math.max(clip.inMs + 100, settings.optLong("outMs", clip.outMs));
                break;
            case "speed":
            case "slow_motion":
                clip.speed = (float) Math.max(.25, Math.min(4, settings.optDouble("speed", .5)));
                break;
            case "volume":
                clip.volume = (float) Math.max(0, Math.min(2, settings.optDouble("volume", 1)));
                break;
            case "transition":
                clip.transition = settings.optString("name", "fade");
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
            case "effect":
                clip.effects.put("effectPreset", settings.optString("preset", "cinematic"));
                copyNumeric(settings, clip.effects, "blur", "brightness", "contrast", "saturation", "lightness");
                break;
            case "color":
                clip.effects.put("colorPreset", settings.optString("preset", "cinematic"));
                if (settings.has("brightness")) clip.effects.put("brightness", settings.optDouble("brightness"));
                if (settings.has("contrast")) clip.effects.put("contrast", settings.optDouble("contrast"));
                if (settings.has("saturation")) clip.effects.put("saturationAdjust", settings.optDouble("saturation"));
                if (settings.has("lightness")) clip.effects.put("lightnessAdjust", settings.optDouble("lightness"));
                break;
            case "reframe":
                clip.effects.put("reframe", settings.optString("preset", "9:16_subject_safe"));
                break;
            case "mask":
                clip.effects.put("mask", settings.optString("shape", "rounded_rect"));
                clip.effects.put("maskFeather", settings.optDouble("feather", .08));
                break;
            case "font":
                clip.effects.put("fontFamily", settings.optString("family", "sans-serif-medium"));
                break;
            case "text_animation":
                clip.effects.put("textAnimation", settings.optString("preset", "fade_up"));
                break;
            case "title":
                clip.title = settings.optString("text", "");
                if (settings.has("font")) clip.effects.put("fontFamily", settings.optString("font"));
                if (settings.has("animation")) clip.effects.put("textAnimation", settings.optString("animation"));
                break;
            case "blur":
                clip.effects.put("blur", Math.max(0, Math.min(18, settings.optDouble("sigma", 4))));
                break;
            case "transform":
                if (settings.has("scale")) clip.effects.put("scale", settings.optDouble("scale", 1));
                if (settings.has("rotate")) clip.effects.put("rotate", settings.optDouble("rotate", 0));
                break;
            case "audio_duck":
                clip.effects.put("audioDucking", true);
                clip.effects.put("duckLevel", settings.optDouble("level", .32));
                break;
            case "keyframes":
                clip.effects.put("keyframes", settings.optJSONArray("keyframes") == null ? new JSONArray() : settings.optJSONArray("keyframes"));
                break;
            default:
                clip.effects.put(tool, settings);
        }
        store.save(project);
        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("clipIndex", index);
        result.put("tool", tool);
        return result;
    }

    private JSONObject applyCreatorPreset(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (project.clips.isEmpty()) throw new IllegalArgumentException("No clips");
        String preset = p.optString("preset", "cinematic");
        String motion = p.optString("motion", "");
        String transition = p.optString("transition", "");
        String font = p.optString("font", "");
        boolean all = p.optBoolean("allClips", true);
        int selected = Math.max(0, p.optInt("clipIndex", 0));
        int changed = 0;
        for (int i = 0; i < project.clips.size(); i++) {
            if (!all && i != selected) continue;
            ProjectStore.Clip clip = project.clips.get(i);
            clip.effects.put("effectPreset", preset);
            if (!motion.isEmpty()) clip.effects.put("motionPreset", motion);
            if (!transition.isEmpty()) clip.transition = transition;
            if (!font.isEmpty()) clip.effects.put("fontFamily", font);
            changed++;
        }
        store.save(project);
        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("changedClips", changed);
        result.put("preset", preset);
        return result;
    }

    private JSONObject autonomousEdit(JSONObject p) throws Exception {
        JSONObject result = ok();
        if (p.optJSONArray("clips") != null) result.put("plan", applyEditPlan(p));
        if (p.has("preset")) result.put("preset", applyCreatorPreset(p));
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (p.optBoolean("render", false)) {
            JSONObject exportArgs = new JSONObject();
            exportArgs.put("projectId", project.id);
            exportArgs.put("aspect", p.optString("aspect", "9:16"));
            exportArgs.put("quality", p.optString("quality", "1080p"));
            exportArgs.put("fileName", p.optString("fileName", "VideoStudio_AI_Edit_" + System.currentTimeMillis() + ".mp4"));
            result.put("export", queueExport(exportArgs));
        }
        return result;
    }

    private void queueAnalysis(JSONObject command, JSONObject p) {
        try {
            ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
            String requested = p.optString("assetId", "");
            ProjectStore.Asset asset = requested.isEmpty() ? null : project.asset(requested);
            if (asset == null) {
                for (ProjectStore.Asset candidate : project.assets) {
                    if (candidate.mime != null && candidate.mime.startsWith("video/")) { asset = candidate; break; }
                }
            }
            if (asset == null) throw new IllegalArgumentException("No video asset available for analysis");
            ProjectStore.Asset target = asset;
            int frames = p.optInt("frames", 12);
            long startMs = p.has("startMs") ? p.optLong("startMs") : (long) (p.optDouble("start", 0) * 1000);
            long endMs = p.has("endMs") ? p.optLong("endMs") : (long) (p.optDouble("end", 0) * 1000);

            jobs.submit("Analyse • " + target.name, JobManager.Kind.LIGHT, state -> {
                try {
                    checkpoint(state, "Analysing media", "Sampling frames locally", 8, project.id);
                    JSONObject result = mediaAnalyzer.analyse(target, frames, startMs, endMs);
                    checkpoint(state, "Analysing media", "Analysis complete", 100, project.id);
                    ActivityLog.add(this, "chatgpt", "Media analysis complete", target.name + " analysed on-device", "success", 100, command.optString("id", ""), project.id);
                    commandJournal.finish(command, result, "completed");
                    protocol.complete(command, result, "completed");
                } catch (Exception error) {
                    JSONObject failed = new JSONObject();
                    failed.put("ok", false);
                    failed.put("error", error.getMessage() == null ? "Analysis failed" : error.getMessage());
                    ActivityLog.add(this, "chatgpt", "Media analysis failed", failed.optString("error"), "failed", null, command.optString("id", ""), project.id);
                    commandJournal.finish(command, failed, "failed");
                    protocol.complete(command, failed, "failed");
                    throw error;
                }
            });
        } catch (Exception error) {
            JSONObject failed = new JSONObject();
            try {
                failed.put("ok", false);
                failed.put("error", error.getMessage());
            } catch (Exception ignored) {}
            commandJournal.finish(command, failed, "failed");
            protocol.complete(command, failed, "failed");
        }
    }

    private JSONObject queuePromptVideo(JSONObject p) throws Exception {
        String prompt = p.optString("prompt", "").trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("Prompt is required");
        String title = prompt.replaceAll("\\s+", " ").trim();
        if (title.length() > 36) title = title.substring(0, 36).trim() + "…";
        ProjectStore.Project project = store.create("AI • " + title);
        project.sourcePrompt = prompt;
        store.save(project);

        String aspect = p.optString("aspect", "9:16");
        String quality = p.optString("quality", "1080p");
        String fileName = p.optString("fileName", "VideoStudio_AI_" + System.currentTimeMillis() + ".mp4");

        JobManager.Job job = jobs.submit("Prompt video • " + title, JobManager.Kind.HEAVY, state -> {
            checkpoint(state, "Prompt video", "Designing local scene cards", 3, project.id);
            PromptVideoEngine.BuildResult built = promptVideoEngine.build(store, project, p);
            checkpoint(state, "Prompt video", "Scene plan ready • native rendering", 18, project.id);
            runExportBlocking(built.project, built.aspect, built.quality, fileName, state);
            checkpoint(state, "Prompt video", "Prompt video complete", 100, project.id);
        });

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("prompt", prompt);
        result.put("aspect", aspect);
        result.put("quality", quality);
        syncProtocolState();
        return result;
    }

    private JSONObject queueExport(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (project.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
        String aspect = p.optString("aspect", "9:16");
        String quality = p.optString("quality", "1080p");
        String fileName = sanitizeFileName(p.optString("fileName", "VideoStudio_" + System.currentTimeMillis() + ".mp4"));

        JobManager.Job job = jobs.submit("Export • " + project.name, JobManager.Kind.HEAVY, state -> {
            checkpoint(state, "Exporting video", "Preparing protected native export", 2, project.id);
            runExportBlocking(project, aspect, quality, fileName, state);
            checkpoint(state, "Exporting video", "Export complete", 100, project.id);
        });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("fileName", fileName);
        return result;
    }

    private void runExportBlocking(ProjectStore.Project project, String aspect, String quality, String fileName, JobManager.Job state) throws Exception {
        File dir = new File(getCacheDir(), "native_exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create export workspace");
        File temp = new File(dir, "tmp_" + System.currentTimeMillis() + ".mp4");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<File> completed = new AtomicReference<>();

        activeRender = renderEngine.export(project, temp, aspect, quality, new NativeRenderEngine.Listener() {
            @Override public void onProgress(int progress, String detail) {
                checkpoint(state, "Rendering video", detail, Math.max(20, Math.min(96, 20 + (int) (progress * .76))), project.id);
            }

            @Override public void onCompleted(File file, JSONObject result) {
                completed.set(file);
                latch.countDown();
            }

            @Override public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        if (activeRender == null) throw new IllegalStateException("Could not start native render");

        while (!latch.await(550, TimeUnit.MILLISECONDS)) {
            if (Thread.currentThread().isInterrupted()) {
                activeRender.cancel();
                throw new InterruptedException();
            }
        }
        if (error.get() != null) throw new IllegalStateException(error.get());
        File ready = completed.get();
        if (ready == null || !ready.exists() || ready.length() == 0) throw new IllegalStateException("Native export produced no file");

        checkpoint(state, "Exporting video", "Publishing to Movies/VideoStudio", 97, project.id);
        Uri publicUri = publishExport(ready, fileName);
        ProjectStore.Project fresh = store.get(project.id);
        if (fresh != null) {
            fresh.latestExportUri = publicUri.toString();
            fresh.latestExportName = fileName;
            fresh.latestExportAt = System.currentTimeMillis();
            store.save(fresh);
        }
        if (!ready.delete()) { /* cache cleanup best effort */ }
        activeRender = null;
        syncProtocolState();
    }

    private JSONObject queueDirectAttachmentImport(JSONObject p) throws Exception {
        String sourceUrl = p.optString("sourceUrl", "").trim();
        validateRemoteHttps(sourceUrl);
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT attachment");
        String mimeHint = p.optString("mime", "");
        long sizeHint = Math.max(0, p.optLong("size", 0));

        JobManager.Job job = jobs.submit("Direct attachment • " + name, JobManager.Kind.LIGHT, state -> {
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            File file = new File(dir, System.currentTimeMillis() + "_" + sanitizeFileName(name));

            HttpURLConnection connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", "VideoStudio-Android/3.0.0 MCPv3-DirectIngest");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Attachment source rejected: HTTP " + code);

            String mime = mimeHint.isEmpty() ? connection.getContentType() : mimeHint;
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            long expected = connection.getContentLengthLong();
            if (expected <= 0) expected = sizeHint;

            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[192 * 1024];
                long bytes = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, n);
                    bytes += n;
                    int progress = expected > 0
                            ? Math.min(97, (int) (97d * bytes / expected))
                            : Math.min(95, (int) (bytes / 1024 / 1024));
                    checkpoint(state, "Direct ChatGPT attachment import",
                            (bytes / 1024 / 1024) + " MB received directly by VideoStudio", progress, project.id);
                }
            } finally {
                connection.disconnect();
            }

            addImportedAsset(project, file, name, mime);
            checkpoint(state, "Direct ChatGPT attachment import",
                    "Attachment is now VideoStudio-owned media", 100, project.id);
            syncProtocolState();
        });

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("transport", "direct-app-ingest");
        return result;
    }

    private void validateRemoteHttps(String raw) throws Exception {
        URL parsed = new URL(raw);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IllegalArgumentException("VideoStudio v3 direct attachment ingest requires HTTPS");
        }
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.US);
        if (host.isEmpty()
                || "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || host.startsWith("10.")
                || host.startsWith("192.168.")
                || private172(host)) {
            throw new IllegalArgumentException("Private-network attachment sources are not permitted");
        }
    }

    private boolean private172(String host) {
        if (!host.startsWith("172.")) return false;
        String[] parts = host.split("\\.");
        if (parts.length < 2) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject queuePrivateImport(JSONObject p) throws Exception {
        String handoffId = p.optString("handoffId");
        if (handoffId.isEmpty()) throw new IllegalArgumentException("Missing private handoff ID");
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT import");
        String mimeHint = p.optString("mime", "");

        JobManager.Job job = jobs.submit("Private import • " + name, JobManager.Kind.LIGHT, state -> {
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            File file = new File(dir, System.currentTimeMillis() + "_" + sanitizeFileName(name));

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
                    int progress = expected > 0 ? Math.min(96, (int) (96d * bytes / expected)) : Math.min(94, (int) (bytes / 1024 / 1024));
                    checkpoint(state, "Importing ChatGPT file", (bytes / 1024 / 1024) + " MB securely streamed", progress, project.id);
                }
            } finally {
                connection.disconnect();
            }
            addImportedAsset(project, file, name, mime);
            checkpoint(state, "Importing ChatGPT file", "Imported privately into VideoStudio", 100, project.id);
            syncProtocolState();
        });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        return result;
    }

    private JSONObject queueUrlImport(JSONObject p) throws Exception {
        String url = p.optString("url");
        if (!url.startsWith("https://")) throw new IllegalArgumentException("Only HTTPS imports are allowed");
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT import");

        JobManager.Job job = jobs.submit("Import • " + name, JobManager.Kind.LIGHT, state -> {
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            File file = new File(dir, System.currentTimeMillis() + "_" + sanitizeFileName(name));
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("User-Agent", "VideoStudio-Android/3.0.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Import failed: HTTP " + code);
            String mime = connection.getContentType();
            if (mime == null) mime = "application/octet-stream";
            long expected = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                long bytes = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, n);
                    bytes += n;
                    int progress = expected > 0 ? Math.min(96, (int) (96d * bytes / expected)) : Math.min(94, (int) (bytes / 1024 / 1024));
                    checkpoint(state, "Importing media", (bytes / 1024 / 1024) + " MB received", progress, project.id);
                }
            } finally {
                connection.disconnect();
            }
            addImportedAsset(project, file, name, mime);
            checkpoint(state, "Importing media", "Import complete", 100, project.id);
            syncProtocolState();
        });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        return result;
    }

    private void addImportedAsset(ProjectStore.Project project, File file, String name, String mime) {
        ProjectStore.Asset asset = new ProjectStore.Asset();
        asset.id = UUID.randomUUID().toString();
        asset.uri = Uri.fromFile(file).toString();
        asset.name = name;
        asset.mime = mime;
        asset.durationMs = fileDuration(file);
        project.assets.add(asset);
        if (mime.startsWith("video/") || mime.startsWith("image/")) {
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = asset.id;
            clip.inMs = 0;
            clip.outMs = mime.startsWith("image/") ? 3000 : Math.max(1000, asset.durationMs);
            project.clips.add(clip);
        }
        store.save(project);
    }

    private Uri publishExport(File file, String displayName) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoStudio");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Could not create exported video");
        boolean success = false;
        try (InputStream in = new FileInputStream(file);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Could not open export destination");
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            success = true;
        } finally {
            if (!success) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    private ProjectStore.Project resolveProject(String id) {
        ProjectStore.Project project = id == null || id.isEmpty() ? store.active() : store.get(id);
        if (project == null) project = store.create("ChatGPT Project");
        store.setActive(project.id);
        return project;
    }

    private JSONObject stateJson() {
        JSONObject out = ok();
        try {
            out.put("deviceId", protocol.deviceId());
            out.put("appVersion", AppProtocol.APP_VERSION);
            out.put("protocolVersion", AppProtocol.PROTOCOL_VERSION);
            out.put("mcpEndpointVersion", "v3");
            out.put("nativeAgent", "videostudio-v3");
            out.put("directAttachmentIngest", true);
            out.put("localEngineOwnsProjects", true);
            out.put("nativeApp", true);
            out.put("backgroundControl", true);
            out.put("permissionMode", permissionMode());
            out.put("controlPaused", protocol.isControlPaused());
            out.put("galleryAccess", false);
            out.put("galleryBoundary", "MCP v3 cannot list, browse or enumerate Gallery media. Only user-picked files, VideoStudio-owned media and explicit ChatGPT attachments are usable.");
            out.put("projects", store.summaries().optJSONArray("projects"));
            ProjectStore.Project active = store.active();
            if (active != null) {
                out.put("activeProjectId", active.id);
                out.put("activeProjectName", active.name);
                out.put("clipCount", active.clips.size());
                out.put("assetCount", active.assets.size());
                out.put("durationMs", active.outputDurationMs());
                out.put("sourcePrompt", active.sourcePrompt);
                out.put("latestExportUri", active.latestExportUri);
                out.put("latestExportName", active.latestExportName);
                JSONArray assets = new JSONArray();
                for (ProjectStore.Asset a : active.assets) {
                    JSONObject ai = new JSONObject();
                    ai.put("id", a.id);
                    ai.put("name", a.name);
                    ai.put("mime", a.mime);
                    ai.put("durationMs", a.durationMs);
                    assets.put(ai);
                }
                out.put("activeAssets", assets);
            }
            out.put("workload", jobs.state());
            out.put("creatorCatalog", CreatorCatalog.describe());
            out.put("recentActivity", ActivityLog.recent(this, 30));
            out.put("commandJournal", commandJournal.recent(20));
        } catch (Exception ignored) {}
        return out;
    }

    private boolean isAllowed(String action, JSONObject parameters) {
        String lower = action == null ? "" : action.toLowerCase(Locale.US);
        if (lower.contains("gallery") || lower.contains("media_library") || lower.contains("photo_library")) return false;
        if ("ping".equals(action) || "get_state".equals(action) || "activity_note".equals(action) || "cancel_job".equals(action) || "cancel_all_jobs".equals(action) || "stop_all".equals(action)) return true;
        String mode = permissionMode();
        if ("everything".equals(mode)) return true;
        if ("all_tools".equals(mode)) {
            return !"import_url".equals(action)
                    && !"import_attachment".equals(action)
                    && !"import_chat_file".equals(action)
                    && !"delete_project".equals(action);
        }
        if ("one_file".equals(mode)) {
            String allowed = prefs.getString(KEY_FILE, "");
            if (allowed.isEmpty()) return false;
            if ("apply_tool".equals(action)) {
                ProjectStore.Project project = resolveProject(parameters.optString("projectId", ""));
                int index = parameters.optInt("clipIndex", -1);
                return index >= 0 && index < project.clips.size() && allowed.equals(project.clips.get(index).assetId);
            }
            return "analyse_media".equals(action) || "export_project".equals(action) || "preview_project".equals(action);
        }
        return false;
    }

    private int cancelAllNativeWork() {
        if (activeRender != null) {
            activeRender.cancel();
            activeRender = null;
        }
        return jobs.cancelAll();
    }

    private void complete(JSONObject command, JSONObject result) {
        boolean ok = result.optBoolean("ok", false);
        boolean queued = result.optBoolean("queued", false);
        String action = command.optString("action", "");
        JSONObject p = command.optJSONObject("parameters");
        String projectId = p == null ? "" : p.optString("projectId", result.optString("projectId", ""));
        String detail = queued
                ? ("Queued inside VideoStudio" + (result.optString("jobId", "").isEmpty() ? "" : " • job " + shortId(result.optString("jobId"))))
                : (ok ? completionDetail(action, result) : result.optString("error", "Command failed"));
        ActivityLog.add(this, "chatgpt", friendlyAction(action), detail, queued ? "queued" : (ok ? "success" : "failed"), queued ? 0 : (ok ? 100 : null), command.optString("id", ""), projectId);
        String terminalStatus = ok ? "completed" : "failed";
        commandJournal.finish(command, result, terminalStatus);
        protocol.complete(command, result, terminalStatus);
    }

    private void checkpoint(JobManager.Job state, String action, String detail, int progress, String projectId) {
        state.checkpoint(progress, detail);
        ActivityLog.progress(this, state.id, action, detail, progress, projectId);
    }

    private String friendlyAction(String action) {
        if (action == null) return "ChatGPT action";
        switch (action) {
            case "create_project": return "Creating project";
            case "select_project": return "Opening project";
            case "delete_project": return "Deleting project";
            case "apply_edit_plan": return "Building timeline";
            case "apply_tool": return "Applying edit tool";
            case "creator_preset": return "Applying creator style";
            case "autonomous_edit": return "Autonomous edit";
            case "analyse_media": return "Analysing media";
            case "prompt_video": return "Creating prompt video";
            case "export_project": return "Exporting project";
            case "import_attachment": return "Importing ChatGPT attachment directly";
            case "import_chat_file": return "Importing ChatGPT file";
            case "import_url": return "Importing media";
            case "preview_project": return "Opening preview";
            case "cancel_job": return "Cancelling job";
            case "cancel_all_jobs":
            case "stop_all": return "Stopping VideoStudio jobs";
            case "get_state": return "Reading VideoStudio state";
            case "activity_note": return "ChatGPT progress";
            default: return action.replace('_', ' ');
        }
    }

    private String commandDetail(String action, JSONObject p) {
        if (p == null) return "Received from ChatGPT";
        if ("apply_tool".equals(action)) return p.optString("tool", "edit") + " • clip " + (p.optInt("clipIndex", 0) + 1);
        if ("create_project".equals(action)) return p.optString("name", "New project");
        if ("import_attachment".equals(action) || "import_chat_file".equals(action) || "import_url".equals(action)) return p.optString("name", "Media");
        if ("prompt_video".equals(action)) {
            String prompt = p.optString("prompt", "");
            return prompt.length() > 90 ? prompt.substring(0, 90) + "…" : prompt;
        }
        return "Received from ChatGPT";
    }

    private String completionDetail(String action, JSONObject result) {
        if ("create_project".equals(action)) return "Project created inside VideoStudio";
        if ("apply_edit_plan".equals(action)) return result.optInt("clipCount", 0) + " timeline clip(s) applied";
        if ("apply_tool".equals(action)) return "Edit applied inside VideoStudio";
        if ("creator_preset".equals(action)) return result.optInt("changedClips", 0) + " clip(s) styled";
        if ("select_project".equals(action)) return "Project selected";
        if ("delete_project".equals(action)) return "Project deleted";
        return "Completed inside VideoStudio";
    }

    private String shortId(String id) {
        return id == null || id.length() <= 8 ? (id == null ? "" : id) : id.substring(0, 8);
    }

    private JSONObject ok() {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) {}
        return o;
    }

    private void copyNumeric(JSONObject from, JSONObject to, String... keys) throws Exception {
        for (String key : keys) if (from.has(key)) to.put(key, from.optDouble(key));
    }

    private long fileDuration(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private String sanitizeFileName(String value) {
        String safe = value == null ? "VideoStudio.mp4" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (safe.isEmpty()) safe = "VideoStudio.mp4";
        if (!safe.toLowerCase(Locale.US).endsWith(".mp4") && !safe.contains(".")) safe += ".mp4";
        return safe;
    }

    private String permissionMode() {
        return prefs.getString(KEY_MODE, "all_tools");
    }

    private void syncProtocolState() {
        protocol.setLocalState(permissionMode(), store.summaries());
    }

    private void markService(boolean online, String detail) {
        prefs.edit()
                .putBoolean(KEY_SERVICE_ONLINE, online)
                .putString(KEY_SERVICE_DETAIL, detail == null ? "" : detail)
                .putLong("control_service_heartbeat", System.currentTimeMillis())
                .apply();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "VideoStudio MCP v3", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps VideoStudio's user-controlled private MCP connection available in the background.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent pauseIntent = new Intent(this, ControlService.class).setAction(protocol != null && protocol.isControlPaused() ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent pausePending = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent cancelIntent = new Intent(this, ControlService.class).setAction(ACTION_CANCEL_ALL);
        PendingIntent cancelPending = PendingIntent.getService(this, 2, cancelIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("VideoStudio private control")
                .setContentText(message)
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(null, protocol != null && protocol.isControlPaused() ? "Resume" : "Pause", pausePending).build())
                .addAction(new Notification.Action.Builder(null, "Cancel jobs", cancelPending).build())
                .build();
    }

    private void updateNotification(String message) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(message));
    }
}
