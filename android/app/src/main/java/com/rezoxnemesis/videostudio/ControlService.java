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
import java.net.InetAddress;
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
    public static final String ACTION_RECONNECT = "com.rezoxnemesis.videostudio.RECONNECT";
    public static final String ACTION_SYNC = "com.rezoxnemesis.videostudio.SYNC_STATE";
    public static final String ACTION_LOCAL_ANIMATE = "com.rezoxnemesis.videostudio.LOCAL_ANIMATE_IMAGES";
    private static final String CHANNEL = "videostudio_private_control";
    private static final int NOTIFICATION_ID = 6101;
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_MODE = "permission_mode";
    private static final String KEY_FILE = "allowed_asset_id";
    private static final String KEY_SERVICE_ONLINE = "control_service_online";
    private static final String KEY_SERVICE_DETAIL = "control_service_detail";
    private static final String KEY_AUTONOMY_MIGRATED = "autonomy_everything_v32_migrated";
    // Private MCP JSON fallback for ChatGPT attachments when the host cannot expose a temporary HTTPS file URL.
    // Kept deliberately small because this path is for still frames, not video payloads.
    private static final long MAX_INLINE_IMAGE_BYTES = 12L * 1024L * 1024L;
    private static final int MAX_INLINE_BASE64_CHARS = 17 * 1024 * 1024;
    private static final int MAX_REMOTE_REDIRECTS = 5;

    private ProjectStore store;
    private JobManager jobs;
    private PreviewSnapshotStore previewSnapshots;
    private TransferJournal transferJournal;
    private ResumableTransferManager transferManager;
    private AppProtocol protocol;
    private NativeRenderEngine renderEngine;
    private NativeRenderEngine.Handle activeRender;
    private PromptVideoEngine promptVideoEngine;
    private NativeMediaAnalyzer mediaAnalyzer;
    private NativePortraitMotionAnalyzer portraitMotionAnalyzer;
    private NativeSpeechEngine speechEngine;
    private CreativeWorkspace creativeWorkspace;
    private MotionScriptCompiler motionScriptCompiler;
    private RecoveryPlanStore recoveryPlans;
    private CapabilityRegistry capabilityRegistry;
    private ModelPackManager modelPackManager;
    private DeviceComputeProfile computeProfile;
    private CreativeJobGraph creativeJobGraph;
    private CreativeNodeStore creativeNodeStore;
    private NativeRenderCritic renderCritic;
    private CreativeBuiltInRuntime builtInCreativeRuntime;
    private DriveWorkspaceProvider driveWorkspace;
    private SharedPreferences prefs;
    private CommandJournal commandJournal;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateAutonomyDefaultOnce();
        commandJournal = new CommandJournal(this);
        store = new ProjectStore(this);
        jobs = new JobManager(this);
        previewSnapshots = new PreviewSnapshotStore(this);
        transferJournal = new TransferJournal(this);
        transferManager = new ResumableTransferManager(transferJournal);
        protocol = new AppProtocol(this, this);
        renderEngine = new NativeRenderEngine(this);
        promptVideoEngine = new PromptVideoEngine(this);
        mediaAnalyzer = new NativeMediaAnalyzer(this);
        portraitMotionAnalyzer = new NativePortraitMotionAnalyzer(this);
        speechEngine = new NativeSpeechEngine(this);
        creativeWorkspace = new CreativeWorkspace(this);
        motionScriptCompiler = new MotionScriptCompiler();
        recoveryPlans = new RecoveryPlanStore(this);
        capabilityRegistry = new CapabilityRegistry(this);
        modelPackManager = new ModelPackManager(this);
        computeProfile = new DeviceComputeProfile(this);
        creativeJobGraph = new CreativeJobGraph(capabilityRegistry, computeProfile);
        creativeNodeStore = new CreativeNodeStore(creativeWorkspace);
        renderCritic = new NativeRenderCritic(this);
        builtInCreativeRuntime = new CreativeBuiltInRuntime(portraitMotionAnalyzer, renderCritic, creativeNodeStore);
        driveWorkspace = new DriveWorkspaceProvider(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("VideoStudio stable MCP starting"));
        syncProtocolState();
        markService(false, "VideoStudio stable MCP Native Agent starting");
        protocol.start();
        ActivityLog.add(this, "system", "VideoStudio control starting",
                "Stable MCP compatibility endpoint • app " + AppProtocol.APP_VERSION
                        + " • generation " + protocol.appGeneration(),
                "success", null, null, null);
        recoverDurablePlans();
        NativeAgentWatchdog.scheduleHealthy(this, "service_created");
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
            NativeAgentWatchdog.cancel(this);
            updateNotification("ChatGPT control paused");
        } else if (ACTION_RESUME.equals(action)) {
            protocol.setControlPaused(false);
            ActivityLog.add(this, "user", "ChatGPT control resumed", "VideoStudio stable MCP connection is accepting commands again", "success", null, null, null);
            syncProtocolState();
            protocol.registerNow();
            NativeAgentWatchdog.scheduleHealthy(this, "control_resumed");
            updateNotification("Stable MCP control ready");
        } else if (ACTION_RECONNECT.equals(action)) {
            syncProtocolState();
            protocol.forceReconnect();
        } else if (ACTION_SYNC.equals(action)) {
            syncProtocolState();
            protocol.registerNow();
        } else if (ACTION_LOCAL_ANIMATE.equals(action)) {
            try {
                JSONObject p = new JSONObject();
                p.put("projectId", intent.getStringExtra("projectId"));
                p.put("style", intent.getStringExtra("style") == null ? "cinematic" : intent.getStringExtra("style"));
                p.put("environment", intent.getStringExtra("environment") == null ? "ambient" : intent.getStringExtra("environment"));
                p.put("intensity", intent.getDoubleExtra("intensity", .78));
                p.put("durationSecondsPerImage", intent.getDoubleExtra("durationSecondsPerImage", 4.2));
                p.put("reorderForStory", intent.getBooleanExtra("reorderForStory", true));
                p.put("render", intent.getBooleanExtra("render", true));
                p.put("aspect", intent.getStringExtra("aspect") == null ? "9:16" : intent.getStringExtra("aspect"));
                p.put("quality", intent.getStringExtra("quality") == null ? "1080p" : intent.getStringExtra("quality"));
                p.put("fileName", intent.getStringExtra("fileName") == null
                        ? "VideoStudio_Animated_" + System.currentTimeMillis() + ".mp4"
                        : intent.getStringExtra("fileName"));
                JSONObject queued = queueAnimatedImages(p);
                ActivityLog.add(this, "user", "AI image animation queued",
                        queued.optInt("imageCount", 0) + " image(s) • job " + shortId(queued.optString("jobId")),
                        "queued", 0, null, queued.optString("projectId", ""));
            } catch (Exception error) {
                ActivityLog.add(this, "user", "AI image animation failed",
                        error.getMessage() == null ? "Could not queue animation" : error.getMessage(),
                        "failed", null, null, intent.getStringExtra("projectId"));
            }
        }
        syncProtocolState();
        if (protocol != null && !protocol.isControlPaused()) {
            NativeAgentWatchdog.scheduleHealthy(this, "service_active");
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (protocol == null || NativeAgentWatchdog.shouldRearm(protocol.isControlPaused())) {
            NativeAgentWatchdog.scheduleTaskRemoved(this);
        }
        markService(false, "MCP control plane available • native execution re-arming");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        ActivityLog.add(this, "system", "VideoStudio control stopped", "Background controller stopped", "info", null, null, null);
        markService(false, "Control service stopped");
        boolean paused = protocol != null && protocol.isControlPaused();
        if (activeRender != null) activeRender.cancel();
        if (jobs != null) jobs.shutdown();
        if (protocol != null) protocol.stop();
        if (NativeAgentWatchdog.shouldRearm(paused)) {
            NativeAgentWatchdog.scheduleRetry(this, "service_destroyed");
        } else {
            NativeAgentWatchdog.cancel(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConnection(boolean connected, String detail) {
        boolean wasOnline = prefs.getBoolean(KEY_SERVICE_ONLINE, false);
        String previousDetail = prefs.getString(KEY_SERVICE_DETAIL, "");
        markService(connected, detail);
        if (connected != wasOnline || !String.valueOf(detail).equals(previousDetail)) {
            ActivityLog.add(this, "system", connected ? "MCP connected" : "MCP reconnecting",
                    detail + " • app " + AppProtocol.APP_VERSION,
                    connected ? "success" : "running", null, null, null);
        }
        if (protocol != null && !protocol.isControlPaused()) {
            if (connected) NativeAgentWatchdog.scheduleHealthy(this, "connection_healthy");
            else NativeAgentWatchdog.scheduleRetry(this, "connection_retry");
        }
        updateNotification(protocol != null && protocol.isControlPaused()
                ? "ChatGPT control paused"
                : (connected
                    ? "Stable MCP ready • " + AppProtocol.APP_VERSION + " • gen " + protocol.appGeneration()
                    : "MCP control plane online • native reconnecting • " + AppProtocol.APP_VERSION));
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
                case "self_test":
                    complete(command, nativeSelfTest());
                    return;
                case "connection_health":
                    complete(command, connectionHealth());
                    return;
                case "create_hybrid_binding": {
                    String webDeviceId = p.optString("webDeviceId", "").trim();
                    JSONObject result = protocol.createHybridBinding(webDeviceId);
                    JSONObject challenge = result.optJSONObject("challenge");
                    ActivityLog.add(this, "chatgpt", "Studio Web binding challenge",
                            challenge == null ? "Hybrid binding rejected" : "One-time challenge created for Studio Web",
                            challenge == null ? "failed" : "success", challenge == null ? null : 100,
                            commandId, null);
                    complete(command, result);
                    return;
                }
                case "reconnect_mcp": {
                    protocol.forceReconnect();
                    markService(false, "Forced stable MCP re-registration requested");
                    JSONObject result = connectionHealth();
                    result.put("reconnectRequested", true);
                    result.put("identityPreserved", true);
                    result.put("ownerCredentialPreserved", true);
                    complete(command, result);
                    return;
                }
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
                case "generate_image":
                    complete(command, queueGenerateImage(p));
                    return;
                case "generate_voice":
                    complete(command, queueGenerateVoice(p));
                    return;
                case "compile_scene":
                    complete(command, compileMotionScene(p));
                    return;
                case "run_motion_script":
                    complete(command, runMotionScript(p));
                    syncProtocolState();
                    return;
                case "plan_creative_graph":
                    complete(command, planCreativeGraph(p));
                    return;
                case "run_creative_graph":
                    complete(command, queueCreativeGraphRun(p));
                    return;
                case "creative_graph_status": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    JSONObject result = ok();
                    result.put("projectId", project.id);
                    result.put("state", creativeNodeStore.status(project.id));
                    result.put("ready", creativeNodeStore.readyNodes(project.id));
                    complete(command, result);
                    return;
                }
                case "invalidate_creative_node": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    String nodeId = p.optString("nodeId", "");
                    complete(command, creativeNodeStore.invalidate(
                            project.id,
                            nodeId,
                            p.optBoolean("downstream", true)
                    ));
                    return;
                }
                case "workspace_status":
                    complete(command, workspaceStatus(p));
                    return;
                case "cleanup_workspace":
                    complete(command, cleanupWorkspace(p));
                    return;
                case "creative_system_status": {
                    JSONObject result = ok();
                    result.put("connection", protocol.connectionStatus());
                    result.put("providers", capabilityRegistry.describe());
                    result.put("drive", driveWorkspace.status());
                    result.put("compute", computeProfile.snapshot());
                    result.put("localSceneRenderer", "2d-vectors-and-perspective-3d-triangle-meshes");
                    result.put("stillImageMotion", "articulated-2.5d-portrait");
                    result.put("neuralImageGeneration", false);
                    result.put("neuralVideoGeneration", false);
                    result.put("photorealisticHumanSynthesis", false);
                    result.put("missingComponent", "executable-local-generative-model-adapter-and-compatible-model-pack");
                    result.put("paidInferenceRequired", false);
                    result.put("phoneValidationRequired", true);
                    complete(command, result);
                    return;
                }
                case "capability_registry":
                    complete(command, capabilityRegistry.describe());
                    return;
                case "resolve_capability":
                    complete(command, capabilityRegistry.resolve(
                            p.optString("capability", ""),
                            p.optString("quality", "")
                    ));
                    return;
                case "model_pack_status":
                    complete(command, capabilityRegistry.modelPackStatus());
                    return;
                case "install_model_pack":
                    complete(command, queueModelPackInstall(p));
                    return;
                case "uninstall_model_pack":
                    complete(command, modelPackManager.uninstall(p.optString("id", "")));
                    return;
                case "compute_profile":
                    complete(command, computeProfile.snapshot());
                    return;
                case "plan_compute":
                    complete(command, computeProfile.plan(
                            Math.max(0, p.optLong("estimatedModelMb", 0)),
                            Math.max(1, p.optInt("width", 1080)),
                            Math.max(1, p.optInt("height", 1920)),
                            p.optString("quality", "balanced")
                    ));
                    return;
                case "drive_workspace_status":
                    complete(command, driveWorkspace.status());
                    return;
                case "drive_workspace_inventory":
                    complete(command, driveWorkspace.inventory());
                    return;
                case "sync_project_to_drive":
                    complete(command, queueDriveProjectSync(p));
                    return;
                case "offload_project_to_drive":
                    complete(command, queueDriveProjectOffload(p));
                    return;
                case "restore_project_from_drive":
                    complete(command, queueDriveProjectRestore(p));
                    return;
                case "archive_model_pack_to_drive":
                    complete(command, queueDriveModelPackArchive(p));
                    return;
                case "restore_model_pack_from_drive":
                    complete(command, queueDriveModelPackRestore(p));
                    return;
                case "animate_images":
                    complete(command, queueAnimatedImages(p));
                    return;
                case "job_status": {
                    JSONObject result = ok();
                    result.put("status", jobs.get(p.optString("jobId", "")));
                    complete(command, result);
                    return;
                }
                case "export_project":
                    complete(command, queueExport(p));
                    return;
                case "cancel_job": {
                    String jobId = p.optString("jobId");
                    boolean cancelled = jobs.cancel(jobId);
                    if (cancelled && activeRender != null) activeRender.cancel();
                    if (cancelled) recoveryPlans.cancelByJob(jobId);
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
                case "insert_asset_timeline": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    String assetId = p.optString("assetId", "");
                    if (assetId.isEmpty()) throw new IllegalArgumentException("assetId is required");
                    boolean inserted = store.appendAssetToTimeline(project, assetId);
                    JSONObject result = ok();
                    result.put("projectId", project.id);
                    result.put("assetId", assetId);
                    result.put("inserted", inserted);
                    result.put("clipCount", project.clips.size());
                    complete(command, result);
                    syncProtocolState();
                    return;
                }
                case "import_url":
                    complete(command, queueUrlImport(p));
                    return;
                case "import_attachment":
                    complete(command, queueDirectAttachmentImport(p));
                    return;
                case "import_inline_base64":
                    complete(command, importInlineBase64(p));
                    syncProtocolState();
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

    private JSONObject workspaceStatus(JSONObject p) {
        JSONObject result = ok();
        ProjectStore.Project project = store.active();
        String requested = p.optString("projectId", "");
        if (!requested.isEmpty()) {
            ProjectStore.Project candidate = store.get(requested);
            if (candidate != null) project = candidate;
        }
        String projectId = project == null ? "" : project.id;
        try {
            result.put("projectId", projectId);
            result.put("workspace", creativeWorkspace.status(projectId));
        } catch (Exception ignored) {}
        return result;
    }

    private JSONObject cleanupWorkspace(JSONObject p) {
        JSONObject result = ok();
        String projectId = p.optString("projectId", "");
        try {
            result.put("projectId", projectId);
            result.put("cleanup", creativeWorkspace.cleanupRegenerable(projectId));
        } catch (Exception ignored) {}
        return result;
    }

    private JSONObject compileMotionScene(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String source = p.optString("script", p.optString("source", ""));
        MotionScriptCompiler.CompileResult compiled = motionScriptCompiler.compile(source, project);
        resolveCreativeProviders(compiled.ir);
        JSONObject graph = creativeJobGraph.build(compiled.ir);
        compiled.ir.put("executionGraph", graph);
        compiled.ir.put("nodeState", creativeNodeStore.prepare(project.id, graph));
        JSONObject saved = creativeWorkspace.saveMotionScript(project.id, compiled.name, source, compiled.ir);

        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("name", compiled.name);
        result.put("motionScriptVersion", MotionScriptCompiler.MOTION_SCRIPT_VERSION);
        result.put("creativeIrVersion", MotionScriptCompiler.CREATIVE_IR_VERSION);
        result.put("ir", compiled.ir);
        result.put("workspace", saved);
        return result;
    }

    private JSONObject runMotionScript(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String source = p.optString("script", p.optString("source", ""));
        MotionScriptCompiler.CompileResult compiled = motionScriptCompiler.compile(source, project);
        JSONObject ir = compiled.ir;
        resolveCreativeProviders(ir);
        JSONObject executionGraph = creativeJobGraph.build(ir);
        ir.put("executionGraph", executionGraph);
        ir.put("nodeState", creativeNodeStore.prepare(project.id, executionGraph));
        if (p.optBoolean("strictProviders", false)) {
            JSONArray unresolved = ir.optJSONArray("unresolvedCapabilities");
            if (unresolved != null && unresolved.length() > 0) {
                throw new IllegalStateException("MotionScript requires unavailable capability: " + unresolved.optString(0));
            }
        }
        JSONObject defaults = ir.optJSONObject("defaults");
        if (defaults == null) defaults = new JSONObject();
        JSONArray shots = ir.optJSONArray("shots");
        if (shots == null) shots = new JSONArray();

        int changed = 0;
        for (int i = 0; i < project.clips.size(); i++) {
            ProjectStore.Clip clip = project.clips.get(i);
            JSONObject shot = shots.length() == 0 ? defaults : shots.optJSONObject(Math.min(i, shots.length() - 1));
            if (shot == null) shot = defaults;

            String motion = shot.optString("motionPreset", defaults.optString("motionPreset", "none"));
            String effect = shot.optString("effectPreset", defaults.optString("effectPreset", "none"));
            double strength = shot.optDouble("motionStrength", defaults.optDouble("motionStrength", .35));
            String atmosphere = shot.optString("atmosphere", defaults.optString("atmosphere", "ambient"));
            double atmosphereIntensity = shot.optDouble("atmosphereIntensity", defaults.optDouble("atmosphereIntensity", 0));

            clip.effects.put("motionPreset", motion);
            clip.effects.put("motionStrength", strength);
            if (!"none".equals(effect)) clip.effects.put("effectPreset", effect);

            JSONObject animationSpec = clip.effects.optJSONObject("animationSpec");
            if (animationSpec == null) animationSpec = new JSONObject();
            animationSpec.put("environmentMotion", atmosphere);
            animationSpec.put("atmosphereIntensity", atmosphereIntensity);
            animationSpec.put("motionScript", true);
            animationSpec.put("motionScriptVersion", MotionScriptCompiler.MOTION_SCRIPT_VERSION);
            clip.effects.put("animationSpec", animationSpec);

            if (shots.length() > 0 && shot.has("startMs") && shot.has("endMs")) {
                long requestedDuration = Math.max(900, shot.optLong("endMs") - shot.optLong("startMs"));
                ProjectStore.Asset asset = project.asset(clip.assetId);
                if (asset != null && asset.mime != null && asset.mime.startsWith("image/")) {
                    clip.inMs = 0;
                    clip.outMs = requestedDuration;
                } else if (asset != null && asset.durationMs > 0) {
                    clip.outMs = Math.min(asset.durationMs, Math.max(clip.inMs + 100, clip.inMs + requestedDuration));
                }
            }
            changed++;
        }

        store.save(project);
        JSONObject saved = creativeWorkspace.saveMotionScript(project.id, compiled.name, source, ir);
        ActivityLog.add(this, "chatgpt", "MotionScript compiled",
                compiled.name + " • " + changed + " clip(s) directed through CreativeIR",
                "success", 100, null, project.id);

        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("name", compiled.name);
        result.put("changedClips", changed);
        result.put("ir", ir);
        result.put("workspace", saved);

        if (p.optBoolean("render", false)) {
            JSONObject render = ir.optJSONObject("render");
            if (render == null) render = new JSONObject();
            JSONObject exportParams = new JSONObject();
            exportParams.put("projectId", project.id);
            exportParams.put("quality", p.optString("quality", render.optString("quality", "1080p")));
            exportParams.put("aspect", p.optString("aspect", render.optString("aspect", "9:16")));
            exportParams.put("fileName", sanitizeFileName(p.optString("fileName",
                    "VideoStudio_MotionScript_" + System.currentTimeMillis() + ".mp4")));
            result.put("export", queueExport(exportParams));
        }

        return result;
    }

    private void resolveCreativeProviders(JSONObject ir) {
        if (ir == null || capabilityRegistry == null) return;
        JSONArray requirements = ir.optJSONArray("providerRequirements");
        JSONArray resolutions = new JSONArray();
        JSONArray unresolved = new JSONArray();

        if (requirements != null) {
            for (int i = 0; i < requirements.length(); i++) {
                JSONObject requirement = requirements.optJSONObject(i);
                if (requirement == null) continue;
                String capability = requirement.optString("capability", "");
                String quality = requirement.optString("quality", "balanced");
                JSONObject resolution = capabilityRegistry.resolve(capability, quality);
                try {
                    resolution.put("target", requirement.optString("target", ""));
                    resolution.put("quality", quality);
                    resolutions.put(resolution);
                    if (!resolution.optBoolean("resolved", false)) unresolved.put(capability);
                } catch (Exception ignored) {}
            }
        }

        try {
            ir.put("providerResolution", resolutions);
            ir.put("unresolvedCapabilities", unresolved);
            ir.put("providerResolutionComplete", unresolved.length() == 0);
            ir.put("capabilityRegistryVersion", 1);
        } catch (Exception ignored) {}
    }

    private JSONObject planCreativeGraph(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        JSONObject ir = p.optJSONObject("ir");
        String source = p.optString("script", p.optString("source", ""));

        if (ir == null) {
            if (source.trim().isEmpty()) throw new IllegalArgumentException("script/source or ir is required");
            MotionScriptCompiler.CompileResult compiled = motionScriptCompiler.compile(source, project);
            ir = compiled.ir;
            resolveCreativeProviders(ir);
        } else {
            ir = new JSONObject(ir.toString());
            resolveCreativeProviders(ir);
        }

        JSONObject graph = creativeJobGraph.build(ir);
        ir.put("executionGraph", graph);
        JSONObject nodeState = creativeNodeStore.prepare(project.id, graph);

        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("creativeIrVersion", ir.optString("creativeIrVersion", MotionScriptCompiler.CREATIVE_IR_VERSION));
        result.put("graph", graph);
        result.put("nodeState", nodeState);
        result.put("readyNodes", creativeNodeStore.readyNodes(project.id));
        result.put("ready", graph.optBoolean("ready", false));
        result.put("unresolvedCapabilities", graph.optJSONArray("unresolvedCapabilities") == null
                ? new JSONArray()
                : graph.optJSONArray("unresolvedCapabilities"));
        result.put("computeProfile", computeProfile.snapshot());

        if (!source.trim().isEmpty()) {
            String name = ir.optString("name", "scene");
            result.put("workspace", creativeWorkspace.saveMotionScript(project.id, name, source, ir));
        }
        return result;
    }

    private JSONObject queueCreativeGraphRun(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        JSONObject existing = creativeNodeStore.status(project.id);
        JSONArray existingNodes = existing.optJSONArray("nodes");

        String source = p.optString("script", p.optString("source", "")).trim();
        if (existingNodes == null || existingNodes.length() == 0) {
            if (source.isEmpty()) {
                throw new IllegalStateException("No CreativeIR graph is prepared. Compile or plan MotionScript first.");
            }
            JSONObject planned = planCreativeGraph(p);
            if (!planned.optBoolean("ready", false) && p.optBoolean("strictProviders", false)) {
                JSONArray unresolved = planned.optJSONArray("unresolvedCapabilities");
                throw new IllegalStateException("Creative graph has unavailable capability: "
                        + (unresolved == null ? "unknown" : unresolved.optString(0, "unknown")));
            }
        }

        boolean recoveringGraph = !p.optString("_recoveryPlanId", "").isEmpty();
        int recoveredNodes = creativeNodeStore.recoverRetryable(project.id, recoveringGraph);
        boolean renderRequested = p.optBoolean("render", true);
        boolean critiqueRequested = p.optBoolean("critique", true);
        String aspect = p.optString("aspect", "9:16");
        String quality = p.optString("quality", "1080p");
        String fileName = sanitizeFileName(p.optString(
                "fileName",
                "VideoStudio_Creative_" + System.currentTimeMillis() + ".mp4"
        ));

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("render", renderRequested);
        durableParameters.put("critique", critiqueRequested);
        durableParameters.put("aspect", aspect);
        durableParameters.put("quality", quality);
        durableParameters.put("fileName", fileName);

        JobManager.Job job = submitRecoverableHeavy(
                "run_creative_graph",
                durableParameters,
                project.id,
                "Creative Runtime • " + project.name,
                state -> {
                    int guard = 0;
                    while (guard++ < 256) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        JSONObject readyState = creativeNodeStore.readyNodes(project.id);
                        JSONArray readyNodes = readyState.optJSONArray("nodes");
                        if (readyNodes == null || readyNodes.length() == 0) break;

                        boolean progressed = false;
                        for (int i = 0; i < readyNodes.length(); i++) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

                            JSONObject node = readyNodes.optJSONObject(i);
                            if (node == null) continue;
                            String nodeId = node.optString("id", "");
                            String capability = node.optString("capability", "");
                            if (nodeId.isEmpty() || capability.isEmpty()) continue;

                            if ("render.video".equals(capability) && !renderRequested) continue;
                            if ("render.critique".equals(capability) && !critiqueRequested) {
                                creativeNodeStore.startNode(project.id, nodeId);
                                JSONObject skipped = new JSONObject();
                                skipped.put("ok", true);
                                skipped.put("skipped", true);
                                skipped.put("reason", "Critique disabled for this run");
                                creativeNodeStore.complete(project.id, nodeId, skipped);
                                progressed = true;
                                continue;
                            }

                            int total = creativeNodeCount(project.id);
                            int completed = creativeCompletedCount(project.id);
                            int progress = 4 + (int) Math.min(88,
                                    88d * completed / Math.max(1, total));
                            checkpoint(state, "Creative Runtime",
                                    "Executing " + nodeId + " • " + capability,
                                    progress, project.id);
                            jobs.awaitSafeCheckpoint(state, "creative_" + nodeId.replaceAll("[^a-zA-Z0-9._-]+", "_"));
                            creativeNodeStore.startNode(project.id, nodeId);

                            try {
                                JSONObject nodeResult;
                                if (builtInCreativeRuntime.supports(capability)) {
                                    nodeResult = builtInCreativeRuntime.execute(
                                            store.get(project.id),
                                            node,
                                            latestGeneratedVideo(project.id)
                                    );
                                } else if ("render.compositor".equals(capability)) {
                                    nodeResult = executeCreativeCompositor(store.get(project.id), node);
                                } else if ("render.video".equals(capability)) {
                                    ProjectStore.Project latest = store.get(project.id);
                                    if (latest == null || latest.clips.isEmpty()) {
                                        throw new IllegalStateException("Creative compositor produced no timeline");
                                    }
                                    runExportBlocking(latest, aspect, quality, fileName, state);
                                    ProjectStore.Asset rendered = latestGeneratedVideo(project.id);
                                    if (rendered == null) {
                                        throw new IllegalStateException("Native creative render completed without a registered Media Bin asset");
                                    }
                                    nodeResult = new JSONObject();
                                    nodeResult.put("ok", true);
                                    nodeResult.put("assetId", rendered.id);
                                    nodeResult.put("uri", rendered.uri);
                                    nodeResult.put("name", rendered.name);
                                    nodeResult.put("role", rendered.role);
                                    nodeResult.put("durationMs", rendered.durationMs);
                                } else {
                                    throw new IllegalStateException(
                                            "Capability provider is registered but no safe local runtime adapter is available yet: "
                                                    + capability
                                    );
                                }

                                creativeNodeStore.complete(project.id, nodeId, nodeResult);
                                progressed = true;
                                checkpoint(state, "Creative Runtime",
                                        "Completed " + nodeId,
                                        5 + (int) Math.min(90,
                                                90d * creativeCompletedCount(project.id)
                                                        / Math.max(1, creativeNodeCount(project.id))),
                                        project.id);
                            } catch (Exception error) {
                                creativeNodeStore.fail(
                                        project.id,
                                        nodeId,
                                        error.getMessage() == null ? "Creative node failed" : error.getMessage(),
                                        true
                                );
                                throw error;
                            }
                        }
                        if (!progressed) break;
                    }

                    JSONObject finalState = creativeNodeStore.status(project.id);
                    JSONArray nodes = finalState.optJSONArray("nodes");
                    int completed = creativeCompletedCount(project.id);
                    int total = nodes == null ? 0 : nodes.length();
                    checkpoint(state, "Creative Runtime",
                            completed + "/" + total + " DAG nodes complete",
                            renderRequested ? 100 : Math.min(100, 10 + (int) (90d * completed / Math.max(1, total))),
                            project.id);
                    ActivityLog.add(this, "system", "Creative Runtime pass complete",
                            completed + "/" + total + " nodes • targeted cache preserved",
                            "success", 100, null, project.id);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("durableRecovery", true);
        result.put("render", renderRequested);
        result.put("critique", critiqueRequested);
        result.put("aspect", aspect);
        result.put("quality", quality);
        result.put("fileName", fileName);
        result.put("nodeState", creativeNodeStore.status(project.id));
        result.put("recoveredNodes", recoveredNodes);
        return result;
    }

    private JSONObject executeCreativeCompositor(ProjectStore.Project project, JSONObject node) throws Exception {
        if (project == null) throw new IllegalStateException("Project is unavailable");
        if (project.clips.isEmpty()) throw new IllegalStateException("Timeline is empty");

        JSONObject input = node.optJSONObject("input");
        if (input == null) input = new JSONObject();
        int shotIndex = Math.max(0, Math.min(
                project.clips.size() - 1,
                input.optInt("_shotIndex", 0)
        ));
        ProjectStore.Clip clip = project.clips.get(shotIndex);
        ProjectStore.Asset asset = project.asset(clip.assetId);
        if (asset == null) throw new IllegalStateException("Compositor clip asset is missing");

        String motion = input.optString("motionPreset", "none");
        String effect = input.optString("effectPreset", "none");
        double strength = Math.max(0, Math.min(1, input.optDouble("motionStrength", .35)));
        String atmosphere = input.optString("atmosphere", "ambient");
        double atmosphereIntensity = Math.max(0, Math.min(1, input.optDouble("atmosphereIntensity", .25)));
        long duration = input.has("startMs") && input.has("endMs")
                ? Math.max(900, input.optLong("endMs") - input.optLong("startMs"))
                : Math.max(900, clip.outputDurationMs());

        JSONObject rig = completedCreativeResult(project.id, "portrait.rig", asset.id);
        boolean layered = rig != null && asset.mime != null && asset.mime.startsWith("image/");

        if (layered) {
            JSONObject analysis = rig.optJSONObject("analysis");
            if (analysis == null) analysis = new JSONObject();
            JSONObject spec = AnimatedSceneDirector.buildSpec(
                    analysis,
                    shotIndex,
                    Math.max(1, project.clips.size()),
                    "cinematic",
                    Math.max(.15, strength),
                    duration,
                    atmosphere
            );
            if (!"none".equals(motion)) spec.put("cameraPreset", motion);
            spec.put("environmentMotion", atmosphere);
            spec.put("atmosphereIntensity", atmosphereIntensity);
            spec.put("motionScript", true);
            spec.put("motionScriptVersion", MotionScriptCompiler.MOTION_SCRIPT_VERSION);

            clip.inMs = 0;
            clip.outMs = duration;
            clip.speed = 1f;
            clip.effects.put("animatedScene", true);
            clip.effects.put("animationEngine", "creativeir-articulated-parallax-v1");
            clip.effects.put("animationAnalysis", analysis);
            clip.effects.put("animationSpec", spec);
            clip.effects.put("foregroundUri", rig.optString("foregroundUri", ""));
            clip.effects.put("headUri", rig.optString("headUri", ""));
            clip.effects.put("torsoUri", rig.optString("torsoUri", ""));
            clip.effects.put("lowerUri", rig.optString("lowerUri", ""));
            clip.effects.put("backgroundUri", rig.optString("backgroundUri", ""));
            clip.effects.put("motionPreset", spec.optString("cameraPreset", "push_in"));
            clip.effects.put("motionStrength", strength);
            clip.effects.put("motionBlur", spec.optDouble("motionBlur", .18));
        } else {
            clip.effects.put("motionPreset", motion);
            clip.effects.put("motionStrength", strength);
            JSONObject spec = clip.effects.optJSONObject("animationSpec");
            if (spec == null) spec = new JSONObject();
            spec.put("environmentMotion", atmosphere);
            spec.put("atmosphereIntensity", atmosphereIntensity);
            spec.put("motionScript", true);
            spec.put("motionScriptVersion", MotionScriptCompiler.MOTION_SCRIPT_VERSION);
            clip.effects.put("animationSpec", spec);
        }

        if (!"none".equals(effect)) clip.effects.put("effectPreset", effect);
        store.save(project);

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("clipId", clip.id);
        result.put("assetId", asset.id);
        result.put("shotIndex", shotIndex);
        result.put("layered", layered);
        result.put("motionPreset", clip.effects.optString("motionPreset", motion));
        result.put("durationMs", clip.outputDurationMs());
        result.put("engine", layered ? "creativeir-articulated-parallax-v1" : "creativeir-native-compositor-v1");
        return result;
    }

    private JSONObject completedCreativeResult(String projectId,
                                               String capability,
                                               String assetId) {
        JSONObject state = creativeNodeStore.status(projectId);
        JSONArray nodes = state.optJSONArray("nodes");
        if (nodes == null) return null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null
                    || !"completed".equals(node.optString("state"))
                    || !capability.equals(node.optString("capability"))) continue;
            JSONObject result = node.optJSONObject("result");
            if (result == null) continue;
            if (assetId == null || assetId.isEmpty() || assetId.equals(result.optString("assetId", ""))) {
                try { return new JSONObject(result.toString()); }
                catch (Exception ignored) { return result; }
            }
        }
        return null;
    }

    private ProjectStore.Asset latestGeneratedVideo(String projectId) {
        ProjectStore.Project project = store.get(projectId);
        if (project == null) return null;
        ProjectStore.Asset latest = null;
        for (ProjectStore.Asset asset : project.assets) {
            if (asset == null || asset.mime == null || !asset.mime.startsWith("video/")) continue;
            if (!asset.generated && !"final_render".equals(asset.role)) continue;
            if (latest == null || asset.createdAt >= latest.createdAt) latest = asset;
        }
        return latest;
    }

    private int creativeNodeCount(String projectId) {
        JSONArray nodes = creativeNodeStore.status(projectId).optJSONArray("nodes");
        return nodes == null ? 0 : nodes.length();
    }

    private int creativeCompletedCount(String projectId) {
        JSONArray nodes = creativeNodeStore.status(projectId).optJSONArray("nodes");
        if (nodes == null) return 0;
        int completed = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && "completed".equals(node.optString("state"))) completed++;
        }
        return completed;
    }

    private JobManager.Job submitRecoverableLight(String action,
                                                  JSONObject parameters,
                                                  String projectId,
                                                  String jobName,
                                                  JobManager.Work work) {
        String requestedPlan = parameters == null ? "" : parameters.optString("_recoveryPlanId", "");
        String planId = requestedPlan;
        if (planId.isEmpty() || recoveryPlans.get(planId) == null) {
            planId = recoveryPlans.begin(action, parameters, projectId);
        } else {
            recoveryPlans.markResuming(planId);
        }

        final String durablePlanId = planId;
        JobManager.Job job = jobs.submit(jobName, JobManager.Kind.LIGHT, state -> {
            recoveryPlans.attachJob(durablePlanId, state.id);
            try {
                work.run(state);
                recoveryPlans.completeByJob(state.id);
            } catch (InterruptedException interrupted) {
                if ("cancelled".equals(state.state)) recoveryPlans.cancelByJob(state.id);
                else recoveryPlans.failByJob(state.id, "Interrupted after checkpoint; safe to resume", true);
                throw interrupted;
            } catch (Exception error) {
                recoveryPlans.failByJob(
                        state.id,
                        error.getMessage() == null ? "Recoverable background job failure" : error.getMessage(),
                        true
                );
                throw error;
            }
        });
        return job;
    }

    private JobManager.Job submitRecoverableHeavy(String action,
                                                  JSONObject parameters,
                                                  String projectId,
                                                  String jobName,
                                                  JobManager.Work work) {
        String requestedPlan = parameters == null ? "" : parameters.optString("_recoveryPlanId", "");
        String planId = requestedPlan;
        if (planId.isEmpty() || recoveryPlans.get(planId) == null) {
            planId = recoveryPlans.begin(action, parameters, projectId);
        } else {
            recoveryPlans.markResuming(planId);
        }

        final String durablePlanId = planId;
        JobManager.Job job = jobs.submit(jobName, JobManager.Kind.HEAVY, state -> {
            recoveryPlans.attachJob(durablePlanId, state.id);
            try {
                work.run(state);
                recoveryPlans.completeByJob(state.id);
            } catch (InterruptedException interrupted) {
                if ("cancelled".equals(state.state)) recoveryPlans.cancelByJob(state.id);
                else recoveryPlans.failByJob(state.id, "Interrupted after checkpoint; safe to resume", true);
                throw interrupted;
            } catch (Exception error) {
                recoveryPlans.failByJob(
                        state.id,
                        error.getMessage() == null ? "Recoverable heavy job failure" : error.getMessage(),
                        true
                );
                throw error;
            }
        });
        return job;
    }

    private void recoverDurablePlans() {
        if (recoveryPlans == null || protocol == null || protocol.isControlPaused()) return;
        JSONArray pending = recoveryPlans.pendingForAutoResume();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject plan = pending.optJSONObject(i);
            if (plan == null) continue;
            String planId = plan.optString("id", "");
            String action = plan.optString("action", "");
            String projectId = plan.optString("projectId", "");
            String outputUri = plan.optString("outputUri", "");

            if (!outputUri.isEmpty() && isReadableOutput(outputUri)) {
                if ("run_creative_graph".equals(action)) {
                    try {
                        ProjectStore.Asset rendered = latestGeneratedVideo(projectId);
                        if (rendered != null) {
                            JSONObject renderResult = new JSONObject();
                            renderResult.put("ok", true);
                            renderResult.put("assetId", rendered.id);
                            renderResult.put("uri", rendered.uri);
                            renderResult.put("name", rendered.name);
                            renderResult.put("role", rendered.role);
                            renderResult.put("durationMs", rendered.durationMs);
                            creativeNodeStore.complete(projectId, "render.final", renderResult);
                            ActivityLog.add(this, "system", "Recovered CreativeIR render node",
                                    rendered.name + " was already published; resuming downstream nodes only",
                                    "success", 98, null, projectId);
                        }
                    } catch (Exception ignored) {
                        // If graph metadata was not committed, the normal retry path will safely rebuild it.
                    }
                } else {
                    recoveryPlans.completePlan(planId, "Recovered published output; no duplicate render required");
                    ActivityLog.add(this, "system", "Recovered completed render",
                            plan.optString("outputName", "Generated video") + " was already published before restart",
                            "success", 100, null, projectId);
                    continue;
                }
            }

            JSONObject parameters = plan.optJSONObject("parameters");
            if (parameters == null) parameters = new JSONObject();
            try {
                parameters = new JSONObject(parameters.toString());
                parameters.put("_recoveryPlanId", planId);
                if (!projectId.isEmpty()) parameters.put("projectId", projectId);
                recoveryPlans.markResuming(planId);

                JSONObject queued;
                switch (action) {
                    case "animate_images":
                        queued = queueAnimatedImages(parameters);
                        break;
                    case "prompt_video":
                        queued = queuePromptVideo(parameters);
                        break;
                    case "generate_image":
                        queued = queueGenerateImage(parameters);
                        break;
                    case "generate_voice":
                        queued = queueGenerateVoice(parameters);
                        break;
                    case "export_project":
                        queued = queueExport(parameters);
                        break;
                    case "install_model_pack":
                        queued = queueModelPackInstall(parameters);
                        break;
                    case "sync_project_to_drive":
                        queued = queueDriveProjectSync(parameters);
                        break;
                    case "offload_project_to_drive":
                        queued = queueDriveProjectOffload(parameters);
                        break;
                    case "restore_project_from_drive":
                        queued = queueDriveProjectRestore(parameters);
                        break;
                    case "archive_model_pack_to_drive":
                        queued = queueDriveModelPackArchive(parameters);
                        break;
                    case "restore_model_pack_from_drive":
                        queued = queueDriveModelPackRestore(parameters);
                        break;
                    case "run_creative_graph":
                        queued = queueCreativeGraphRun(parameters);
                        break;
                    default:
                        recoveryPlans.completePlan(planId, "No auto-resume handler required for action: " + action);
                        continue;
                }

                ActivityLog.add(this, "system", "Resuming interrupted work",
                        friendlyAction(action) + " • job " + shortId(queued.optString("jobId", "")),
                        "running", plan.optInt("progress", 0), null, projectId);
            } catch (Exception error) {
                ActivityLog.add(this, "system", "Recovery retry deferred",
                        friendlyAction(action) + " • " + (error.getMessage() == null ? "retry unavailable" : error.getMessage()),
                        "info", plan.optInt("progress", 0), null, projectId);
            }
        }
    }

    private boolean isReadableOutput(String rawUri) {
        if (rawUri == null || rawUri.isEmpty()) return false;
        try (InputStream in = getContentResolver().openInputStream(Uri.parse(rawUri))) {
            return in != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject queueDriveProjectOffload(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("No folder-scoped cloud workspace is linked. Link one from VideoStudio > Control.");
        }

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);

        JobManager.Job job = submitRecoverableHeavy(
                "offload_project_to_drive",
                durableParameters,
                project.id,
                "Cloud offload • " + project.name,
                state -> {
                    File workspace = creativeWorkspace.projectRoot(project.id);
                    checkpoint(state, "Cloud offload", "Verifying cloud archive before local eviction", 2, project.id);
                    JSONObject archived = driveWorkspace.syncProject(
                            project,
                            workspace,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud offload",
                                    detail,
                                    Math.max(2, Math.min(86, (int) (2 + progress * .84))),
                                    project.id
                            )
                    );
                    if (!archived.optBoolean("ok", false)) {
                        throw new IllegalStateException("Cloud archive verification did not complete");
                    }
                    jobs.awaitSafeCheckpoint(state, "cloud_offload_commit");
                    checkpoint(state, "Cloud offload", "Archive verified • evicting only cloud-backed intermediates", 90, project.id);
                    JSONObject evicted = creativeWorkspace.evictCloudBackedProject(project.id);
                    checkpoint(state, "Cloud offload",
                            "Freed " + evicted.optLong("removedBytes", 0) + " bytes of local creative working data",
                            100, project.id);
                    ActivityLog.add(this, "system", "Project workspace offloaded",
                            project.name + " • " + evicted.optLong("removedBytes", 0) + " bytes freed locally",
                            "success", 100, null, project.id);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("durableRecovery", true);
        result.put("archiveBeforeEvict", true);
        result.put("preservesProjectState", true);
        result.put("preservesFinalExports", true);
        result.put("galleryAccess", false);
        return result;
    }

    private JSONObject queueDriveProjectRestore(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("No folder-scoped cloud workspace is linked. Link one from VideoStudio > Control.");
        }

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);

        JobManager.Job job = submitRecoverableHeavy(
                "restore_project_from_drive",
                durableParameters,
                project.id,
                "Restore cloud workspace • " + project.name,
                state -> {
                    checkpoint(state, "Cloud restore", "Preparing protected project workspace", 2, project.id);
                    File workspace = creativeWorkspace.projectRoot(project.id);
                    JSONObject restored = driveWorkspace.restoreProjectWorkspace(
                            project.id,
                            workspace,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud restore",
                                    detail,
                                    Math.max(2, Math.min(100, progress)),
                                    project.id
                            )
                    );
                    checkpoint(state, "Cloud restore",
                            "Creative workspace restored • " + restored.optLong("bytesRestored", 0) + " bytes",
                            100, project.id);
                    ActivityLog.add(this, "system", "Cloud workspace restored",
                            project.name + " • " + restored.optInt("filesRestored", 0) + " file(s)",
                            "success", 100, null, project.id);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("durableRecovery", true);
        result.put("scope", "single-user-selected-document-tree");
        result.put("galleryAccess", false);
        return result;
    }

    private JSONObject queueDriveModelPackArchive(JSONObject p) throws Exception {
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("No folder-scoped cloud workspace is linked. Link one from VideoStudio > Control.");
        }
        String packId = p.optString("id", "").trim();
        if (packId.isEmpty()) throw new IllegalArgumentException("Model pack id is required");
        File pack = modelPackManager.installedDirectory(packId);

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("id", packId);

        JobManager.Job job = submitRecoverableHeavy(
                "archive_model_pack_to_drive",
                durableParameters,
                "",
                "Archive model pack • " + packId,
                state -> {
                    checkpoint(state, "Cloud model archive", "Preparing installed model pack", 2, "");
                    JSONObject archived = driveWorkspace.syncModelPack(
                            packId,
                            pack,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud model archive",
                                    detail,
                                    Math.max(2, Math.min(100, progress)),
                                    ""
                            )
                    );
                    checkpoint(state, "Cloud model archive",
                            "Model pack archived • " + archived.optLong("bytesWritten", 0) + " bytes",
                            100, "");
                    ActivityLog.add(this, "system", "Model pack archived",
                            packId + " • " + archived.optInt("filesWritten", 0) + " file(s)",
                            "success", 100, null, null);
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("id", packId);
        result.put("durableRecovery", true);
        result.put("scope", "single-user-selected-document-tree");
        return result;
    }

    private JSONObject queueDriveModelPackRestore(JSONObject p) throws Exception {
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("No folder-scoped cloud workspace is linked. Link one from VideoStudio > Control.");
        }
        String packId = p.optString("id", "").trim();
        if (packId.isEmpty()) throw new IllegalArgumentException("Model pack id is required");

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("id", packId);

        JobManager.Job job = submitRecoverableHeavy(
                "restore_model_pack_from_drive",
                durableParameters,
                "",
                "Restore model pack • " + packId,
                state -> {
                    File staging = modelPackManager.createCloudRestoreDirectory(packId);
                    checkpoint(state, "Cloud model restore", "Streaming model pack into protected staging", 2, "");
                    driveWorkspace.restoreModelPack(
                            packId,
                            staging,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud model restore",
                                    detail,
                                    Math.max(2, Math.min(64, (int) (2 + progress * .62))),
                                    ""
                            )
                    );
                    jobs.awaitSafeCheckpoint(state, "cloud_model_activation");
                    JSONObject installed = modelPackManager.activateRestoredDirectory(
                            staging,
                            "folder-scoped-cloud-workspace",
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud model activation",
                                    detail,
                                    Math.max(64, Math.min(100, progress)),
                                    ""
                            )
                    );
                    checkpoint(state, "Cloud model activation",
                            "Capabilities restored • " + installed.optString("id", packId),
                            100, "");
                    ActivityLog.add(this, "system", "Cloud model pack restored",
                            installed.optString("id", packId) + " • " + installed.optString("version", ""),
                            "success", 100, null, null);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("id", packId);
        result.put("durableRecovery", true);
        result.put("transactionalActivation", true);
        return result;
    }

    private JSONObject queueDriveProjectSync(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("No folder-scoped cloud workspace is linked. Link one from VideoStudio > Control.");
        }

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);

        JobManager.Job job = submitRecoverableHeavy(
                "sync_project_to_drive",
                durableParameters,
                project.id,
                "Cloud archive • " + project.name,
                state -> {
                    checkpoint(state, "Cloud archive", "Preparing project workspace", 2, project.id);
                    File workspace = creativeWorkspace.projectRoot(project.id);
                    JSONObject archived = driveWorkspace.syncProject(
                            project,
                            workspace,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud archive",
                                    detail,
                                    Math.max(2, Math.min(100, progress)),
                                    project.id
                            )
                    );
                    checkpoint(state, "Cloud archive",
                            "Project workspace archived • " + archived.optLong("bytesWritten", 0) + " bytes",
                            100, project.id);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("durableRecovery", true);
        result.put("scope", "single-user-selected-document-tree");
        result.put("broadDrivePermission", false);
        return result;
    }

    private JSONObject queueModelPackInstall(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String assetId = p.optString("assetId", "");
        if (assetId.isEmpty()) throw new IllegalArgumentException("assetId is required");
        ProjectStore.Asset source = project.asset(assetId);
        if (source == null) throw new IllegalArgumentException("Model-pack source asset not found");
        String expectedSha256 = p.optString("sha256", "");

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("assetId", assetId);

        JobManager.Job job = submitRecoverableHeavy(
                "install_model_pack",
                durableParameters,
                project.id,
                "Install model pack • " + source.name,
                state -> {
                    checkpoint(state, "Model pack", "Preparing transactional model-pack install", 1, project.id);
                    jobs.awaitSafeCheckpoint(state, "model_pack_install");
                    JSONObject installed = modelPackManager.install(
                            source,
                            expectedSha256,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Model pack",
                                    detail,
                                    Math.max(2, Math.min(99, progress)),
                                    project.id
                            )
                    );
                    checkpoint(state, "Model pack", "Capabilities activated • " + installed.optString("id"), 100, project.id);
                    ActivityLog.add(this, "system", "Model pack ready",
                            installed.optString("id") + " • " + installed.optString("version"),
                            "success", 100, null, project.id);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("assetId", assetId);
        result.put("durableRecovery", true);
        return result;
    }

    private JSONObject queueAnimatedImages(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (project.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");

        ArrayList<ProjectStore.Clip> imageClips = new ArrayList<>();
        for (ProjectStore.Clip clip : project.clips) {
            ProjectStore.Asset asset = project.asset(clip.assetId);
            if (asset != null && asset.mime != null && asset.mime.startsWith("image/")) imageClips.add(clip);
        }
        if (imageClips.isEmpty()) throw new IllegalArgumentException("No image clips are available to animate");

        String style = p.optString("style", "cinematic").toLowerCase(Locale.US);
        String environment = p.optString("environment", "ambient");
        double intensity = Math.max(.15, Math.min(1.0, p.optDouble("intensity", .78)));
        long baseDurationMs = (long) (1000d * Math.max(1.8, Math.min(8.0, p.optDouble("durationSecondsPerImage", 4.2))));
        boolean reorderForStory = p.optBoolean("reorderForStory", true);
        boolean render = p.optBoolean("render", true);
        String aspect = p.optString("aspect", "9:16");
        String quality = p.optString("quality", "1080p");
        String fileName = sanitizeFileName(p.optString("fileName", "VideoStudio_Animated_" + System.currentTimeMillis() + ".mp4"));

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("fileName", fileName);
        JobManager.Job job = submitRecoverableHeavy(
                "animate_images",
                durableParameters,
                project.id,
                "Animate images • " + project.name,
                state -> {
            int total = imageClips.size();
            for (int i = 0; i < total; i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                ProjectStore.Clip clip = imageClips.get(i);
                ProjectStore.Asset asset = project.asset(clip.assetId);
                if (asset == null) continue;

                int startProgress = 4 + (int) (48d * i / Math.max(1, total));
                checkpoint(state, "AI portrait animation",
                        "Analysing subject and building depth layers • " + (i + 1) + "/" + total,
                        startProgress, project.id);
                jobs.awaitSafeCheckpoint(state, "portrait_analysis_" + (i + 1));

                NativePortraitMotionAnalyzer.Result layers =
                        portraitMotionAnalyzer.analyseAndBuildLayers(asset, project.id);

                String shotType = layers.analysis.optString("shotType", "medium");
                long shotDuration = baseDurationMs;
                if ("wide".equals(shotType)) shotDuration += 350;
                if ("close".equals(shotType)) shotDuration -= 250;
                shotDuration += ((i % 3) - 1) * 120L;
                shotDuration = Math.max(1800, shotDuration);

                AnimatedSceneDirector.attachPlan(
                        clip,
                        layers,
                        i,
                        total,
                        style,
                        intensity,
                        shotDuration,
                        environment
                );

                checkpoint(state, "AI portrait animation",
                        "Motion plan ready • " + (i + 1) + "/" + total + " • " + shotType,
                        5 + (int) (50d * (i + 1) / Math.max(1, total)), project.id);
            }

            if (reorderForStory && imageClips.size() >= 4) {
                checkpoint(state, "AI motion director", "Rebuilding cinematic shot order", 58, project.id);
                reorderAnimatedStory(project);
            }

            store.save(project);
            syncProtocolState();
            checkpoint(state, "AI motion director", "Layered motion timeline ready", 62, project.id);

            if (render) {
                checkpoint(state, "Rendering animated video", "Starting layered Media3 composition", 65, project.id);
                jobs.awaitSafeCheckpoint(state, "layered_render");
                runExportBlocking(project, aspect, quality, fileName, state);
                checkpoint(state, "Rendering animated video", "Animated MP4 complete", 100, project.id);
            } else {
                checkpoint(state, "AI motion director", "Animation preparation complete", 100, project.id);
            }
        });

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("durableRecovery", true);
        result.put("projectId", project.id);
        result.put("imageCount", imageClips.size());
        result.put("style", style);
        result.put("environment", environment);
        result.put("intensity", intensity);
        result.put("durationSecondsPerImage", baseDurationMs / 1000d);
        result.put("reorderForStory", reorderForStory);
        result.put("render", render);
        result.put("aspect", aspect);
        result.put("quality", quality);
        result.put("engine", "VideoStudio v3.2 articulated portrait animation");
        return result;
    }

    private void reorderAnimatedStory(ProjectStore.Project project) {
        ArrayList<ProjectStore.Clip> wide = new ArrayList<>();
        ArrayList<ProjectStore.Clip> medium = new ArrayList<>();
        ArrayList<ProjectStore.Clip> close = new ArrayList<>();
        ArrayList<ProjectStore.Clip> other = new ArrayList<>();

        for (ProjectStore.Clip clip : project.clips) {
            JSONObject fx = clip.effects == null ? null : clip.effects;
            JSONObject analysis = fx == null ? null : fx.optJSONObject("animationAnalysis");
            if (analysis == null) {
                other.add(clip);
                continue;
            }
            String shot = analysis.optString("shotType", "medium");
            if ("wide".equals(shot)) wide.add(clip);
            else if ("close".equals(shot)) close.add(clip);
            else medium.add(clip);
        }

        ArrayList<ProjectStore.Clip> story = new ArrayList<>();
        int wi = 0, mi = 0, ci = 0;
        int target = wide.size() + medium.size() + close.size();
        while (story.size() < target) {
            int phase = story.size() % 5;
            ProjectStore.Clip next = null;
            if ((phase == 0 || phase == 4) && wi < wide.size()) next = wide.get(wi++);
            else if ((phase == 1 || phase == 3) && mi < medium.size()) next = medium.get(mi++);
            else if (phase == 2 && ci < close.size()) next = close.get(ci++);
            else if (mi < medium.size()) next = medium.get(mi++);
            else if (ci < close.size()) next = close.get(ci++);
            else if (wi < wide.size()) next = wide.get(wi++);
            if (next == null) break;
            story.add(next);
        }
        story.addAll(other);
        if (!story.isEmpty()) {
            project.clips.clear();
            project.clips.addAll(story);
        }
    }

    private JSONObject queueGenerateVoice(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String text = p.optString("text", "").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Narration text is required");

        String language = p.optString("language", "");
        String voice = p.optString("voice", "");
        float rate = (float) Math.max(.45, Math.min(2.0, p.optDouble("rate", 1.0)));
        float pitch = (float) Math.max(.55, Math.min(1.8, p.optDouble("pitch", 1.0)));
        boolean offlineOnly = p.optBoolean("offlineOnly", true);
        String fileName = p.optString("fileName", "VideoStudio_Voice_" + System.currentTimeMillis() + ".wav");

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("text", text);
        durableParameters.put("language", language);
        durableParameters.put("voice", voice);
        durableParameters.put("rate", rate);
        durableParameters.put("pitch", pitch);
        durableParameters.put("offlineOnly", offlineOnly);
        durableParameters.put("fileName", fileName);

        JobManager.Job job = submitRecoverableLight(
                "generate_voice",
                durableParameters,
                project.id,
                "Generate voice • " + project.name,
                state -> {
                    checkpoint(state, "Local narration", "Preparing Android speech synthesis", 2, project.id);
                    JSONObject generated = speechEngine.synthesize(
                            project,
                            text,
                            language,
                            voice,
                            rate,
                            pitch,
                            offlineOnly,
                            fileName,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Local narration",
                                    detail,
                                    Math.max(2, Math.min(96, progress)),
                                    project.id
                            )
                    );

                    Uri uri = Uri.parse(generated.optString("uri", ""));
                    ProjectStore.Project fresh = store.get(project.id);
                    if (fresh == null) throw new IllegalStateException("Project disappeared during narration generation");
                    ProjectStore.Asset asset = store.registerGeneratedAsset(
                            fresh,
                            uri,
                            generated.optString("fileName", fileName),
                            "generated_voice",
                            false
                    );
                    generated.put("assetId", asset.id);
                    generated.put("mime", asset.mime);
                    generated.put("durationMs", asset.durationMs);
                    state.setResult(generated);

                    checkpoint(state, "Local narration", "Narration available in project Media Bin", 100, project.id);
                    ActivityLog.add(this, "system", "Narration generated",
                            asset.name + " • Media Bin • asset " + shortId(asset.id),
                            "success", 100, null, project.id);
                    syncProtocolState();
                }
        );

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("durableRecovery", true);
        result.put("local", true);
        result.put("offlineOnly", offlineOnly);
        result.put("role", "generated_voice");
        return result;
    }

    private JSONObject queueGenerateImage(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        JSONObject graph = p.optJSONObject("sceneGraph");
        if (graph == null) graph = LocalSceneDirector.fromPrompt(p.optString("prompt", "abstract geometry"), 0);
        ProceduralScene.validate(graph);
        final JSONObject scene = graph;
        final JSONObject parameters = new JSONObject(p.toString());
        int width = Math.max(128, Math.min(1920, p.optInt("width", 720)));
        int height = Math.max(128, Math.min(1920, p.optInt("height", 1280)));
        parameters.put("projectId", project.id);
        if (!parameters.has("_generationId")) parameters.put("_generationId", java.util.UUID.randomUUID().toString());
        JobManager.Job job = submitRecoverableLight("generate_image", parameters, project.id, "Generate procedural image", state -> {
            File dir = new File(creativeWorkspace.projectRoot(project.id), "generated/images");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create image workspace");
            File file = new File(dir, "scene_" + parameters.getString("_generationId") + ".png");
            checkpoint(state, "Image generation", "Rendering original local geometry", 10, project.id);
            PromptVideoEngine.renderProceduralImage(file, width, height, scene);
            ProjectStore.Asset asset = store.registerGeneratedAsset(project, Uri.fromFile(file), file.getName(),
                    "generated_image", parameters.optBoolean("appendToTimeline", false));
            asset.generationMetadata.put("provider", "builtin.videostudio.procedural-scene");
            asset.generationMetadata.put("prompt", parameters.optString("prompt", ""));
            asset.generationMetadata.put("sceneGraph", scene);
            store.save(project);
            JSONObject generated = ok(); generated.put("assetId", asset.id); generated.put("uri", asset.uri);
            generated.put("provider", "builtin.videostudio.procedural-scene"); state.setResult(generated);
            syncProtocolState();
            checkpoint(state, "Image generation", "Generated image registered in Media Bin", 100, project.id);
        });
        JSONObject out = ok(); out.put("queued", true); out.put("jobId", job.id);
        out.put("projectId", project.id); out.put("provider", "builtin.videostudio.procedural-scene");
        out.put("photorealistic", false); return out;
    }

    private JSONObject queuePromptVideo(JSONObject p) throws Exception {
        String prompt = p.optString("prompt", "").trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("Prompt is required");
        String title = prompt.replaceAll("\\s+", " ").trim();
        if (title.length() > 36) title = title.substring(0, 36).trim() + "…";
        boolean recovering = !p.optString("_recoveryPlanId", "").isEmpty();
        ProjectStore.Project resolvedProject = recovering ? store.get(p.optString("projectId", "")) : null;
        if (resolvedProject == null) resolvedProject = store.create("AI • " + title);
        final ProjectStore.Project project = resolvedProject;
        project.sourcePrompt = prompt;
        store.save(project);

        String aspect = p.optString("aspect", "9:16");
        String quality = p.optString("quality", "1080p");
        String fileName = p.optString("fileName", "VideoStudio_AI_" + System.currentTimeMillis() + ".mp4");

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("fileName", fileName);
        JobManager.Job job = submitRecoverableHeavy(
                "prompt_video",
                durableParameters,
                project.id,
                "Prompt video • " + title,
                state -> {
            checkpoint(state, "Prompt video", "Designing local scene plan", 3, project.id);
            jobs.awaitSafeCheckpoint(state, "prompt_scene_build");
            PromptVideoEngine.BuildResult built = promptVideoEngine.build(store, project, p);
            checkpoint(state, "Prompt video", "Scene plan ready • native rendering", 18, project.id);
            jobs.awaitSafeCheckpoint(state, "prompt_render");
            runExportBlocking(built.project, built.aspect, built.quality, fileName, state);
            checkpoint(state, "Prompt video", "Prompt video complete", 100, project.id);
        });

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("durableRecovery", true);
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

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("fileName", fileName);
        JobManager.Job job = submitRecoverableHeavy(
                "export_project",
                durableParameters,
                project.id,
                "Export • " + project.name,
                state -> {
            checkpoint(state, "Exporting video", "Preparing protected native export", 2, project.id);
            runExportBlocking(project, aspect, quality, fileName, state);
            checkpoint(state, "Exporting video", "Export complete", 100, project.id);
        });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("durableRecovery", true);
        result.put("projectId", project.id);
        result.put("fileName", fileName);
        return result;
    }

    private void runExportBlocking(ProjectStore.Project project, String aspect, String quality, String fileName, JobManager.Job state) throws Exception {
        ensureProjectWorkspaceHydrated(project, state);
        jobs.awaitSafeCheckpoint(state, "native_export_prepare");
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
        JSONObject committed = recoveryPlans.outputForJob(state.id);
        final String reusableUri = committed != null
                && isReadableOutput(committed.optString("uri", ""))
                ? committed.optString("uri", "")
                : "";
        AtomicMediaPublisher.PublishResult publication = AtomicMediaPublisher.publish(
                ready,
                new AtomicMediaPublisher.PublishTarget() {
                    @Override public String existingPublishedUri() {
                        return reusableUri;
                    }

                    @Override public String publish(File source) throws Exception {
                        return publishExport(source, fileName).toString();
                    }

                    @Override public String displayName() {
                        return fileName;
                    }
                }
        );
        Uri publicUri = Uri.parse(publication.uri);
        if (!publication.reused) {
            recoveryPlans.markOutputForJob(state.id, publicUri.toString(), fileName);
        }
        ProjectStore.Project fresh = store.get(project.id);
        if (fresh != null) {
            fresh.latestExportUri = publicUri.toString();
            fresh.latestExportName = fileName;
            fresh.latestExportAt = System.currentTimeMillis();
            checkpoint(state, "Registering generated media", "Adding rendered MP4 to the project Media Bin", 98, project.id);
            ProjectStore.Asset generated = store.registerGeneratedAsset(
                    fresh,
                    publicUri,
                    fileName,
                    "final_render",
                    false
            );
            previewSnapshots.publish(new PreviewSnapshotStore.Snapshot(
                    "final-" + state.id + "-" + fresh.latestExportAt,
                    fresh.id,
                    fresh.updatedAt,
                    fresh.latestExportAt,
                    publicUri.toString(),
                    fileName.toLowerCase(Locale.US).contains("1080") ? "1080p" : "final",
                    "final",
                    state.id,
                    fresh.latestExportAt
            ));
            previewSnapshots.publish(new PreviewSnapshotStore.Snapshot(
                    UUID.randomUUID().toString(),
                    fresh.id,
                    fresh.updatedAt,
                    generated.createdAt,
                    publicUri.toString(),
                    quality,
                    "final",
                    state.id,
                    System.currentTimeMillis()
            ));
            ActivityLog.add(this, "system", "Generated video available",
                    generated.name + " • Media Bin • asset " + shortId(generated.id),
                    "success", 100, null, fresh.id);
        }
        if (!ready.delete()) { /* cache cleanup best effort */ }
        activeRender = null;
        syncProtocolState();
    }

    private void ensureProjectWorkspaceHydrated(ProjectStore.Project project,
                                               JobManager.Job state) throws Exception {
        if (project == null || !hasMissingCreativeLayerFiles(project)) return;
        JSONObject offloadState = creativeWorkspace.cloudOffloadState(project.id);
        if (!offloadState.optBoolean("offloaded", false)) {
            throw new IllegalStateException("Creative layer files are missing and no verified cloud-offload marker is present");
        }
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("Project intermediates are cloud-offloaded but the linked workspace is unavailable");
        }

        checkpoint(state, "Cloud rehydrate", "Restoring evicted creative layers before rendering", 12, project.id);
        File workspace = creativeWorkspace.projectRoot(project.id);
        JSONObject restored = driveWorkspace.restoreProjectWorkspace(
                project.id,
                workspace,
                true,
                (progress, detail) -> checkpoint(
                        state,
                        "Cloud rehydrate",
                        detail,
                        Math.max(12, Math.min(18, 12 + (int) (progress * .06))),
                        project.id
                )
        );
        if (!restored.optBoolean("ok", false) || hasMissingCreativeLayerFiles(project)) {
            throw new IllegalStateException("Cloud rehydration completed but required creative layer files are still missing");
        }
        creativeWorkspace.markCloudHydrated(project.id);
        checkpoint(state, "Cloud rehydrate", "Creative working set restored on demand", 19, project.id);
    }

    private boolean hasMissingCreativeLayerFiles(ProjectStore.Project project) {
        if (project == null) return false;
        String workspacePrefix = creativeWorkspace.projectRoot(project.id).getAbsolutePath() + File.separator;
        for (ProjectStore.Clip clip : project.clips) {
            ProjectStore.Asset asset = project.asset(clip.assetId);
            if (asset == null || !asset.generated) continue;
            Uri uri = Uri.parse(asset.uri);
            String path = uri.getPath();
            if ("file".equalsIgnoreCase(uri.getScheme()) && path != null && path.startsWith(workspacePrefix)
                    && !new File(path).isFile()) return true;
        }
        String[] keys = {"foregroundUri", "headUri", "torsoUri", "lowerUri", "backgroundUri"};
        for (ProjectStore.Clip clip : project.clips) {
            if (clip == null || clip.effects == null) continue;
            for (String key : keys) {
                String raw = clip.effects.optString(key, "");
                if (raw.isEmpty()) continue;
                try {
                    Uri uri = Uri.parse(raw);
                    if (!"file".equalsIgnoreCase(uri.getScheme())) continue;
                    String path = uri.getPath();
                    if (path != null && !path.isEmpty() && !new File(path).isFile()) return true;
                } catch (Exception ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private JSONObject queueDirectAttachmentImport(JSONObject p) throws Exception {
        String sourceUrl = p.optString("sourceUrl", "").trim();
        validateRemoteHttps(sourceUrl);
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT attachment");
        String mimeHint = p.optString("mime", "");
        long sizeHint = Math.max(0L, p.optLong("size", 0L));
        String sha256 = p.optString("sha256", "").trim().toLowerCase(Locale.US);
        String transferId = stableTransferId(project.id, sourceUrl, name);

        JobManager.Job job = jobs.submit("Direct attachment • " + name, JobManager.Kind.LIGHT, state -> {
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            File file = new File(dir, transferId + "_" + sanitizeFileName(name));

            ResumableTransferManager.Result transfer = file.isFile()
                    ? new ResumableTransferManager.Result(file, file.length(), sizeHint > 0 ? sizeHint : file.length(), true, mimeHint)
                    : transferManager.download(
                            new ResumableTransferManager.Request(
                                    transferId,
                                    sourceUrl,
                                    file,
                                    sizeHint,
                                    sha256
                            ),
                            (source, offset, etag, lastModified) ->
                                    openSafeRemote(source, 18000, 90000, offset, etag, lastModified),
                            (completed, expected) -> {
                                int rawProgress = ResumableTransferManager.progressPercent(completed, expected);
                                int progress = rawProgress < 0 ? 45 : Math.min(97, 4 + (rawProgress * 93 / 100));
                                checkpoint(state,
                                        "Direct ChatGPT attachment import",
                                        (completed / 1024L / 1024L) + " MB received directly by VideoStudio",
                                        progress,
                                        project.id);
                            }
                    );

            String mime = mimeHint.isEmpty() ? transfer.contentType : mimeHint;
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            addImportedAsset(project, file, name, mime);
            checkpoint(state, "Direct ChatGPT attachment import",
                    "Attachment is now VideoStudio-owned media", 100, project.id);
            syncProtocolState();
        });

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("transport", "resumable-direct-app-ingest");
        result.put("transferId", transferId);
        return result;
    }

    private void validateRemoteHttps(String raw) throws Exception {
        URL parsed = new URL(raw);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IllegalArgumentException("VideoStudio v3 remote ingest requires HTTPS");
        }
        if (parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Credential-bearing import URLs are not permitted");
        }
        int port = parsed.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalArgumentException("Non-standard HTTPS import ports are not permitted");
        }

        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.US);
        if (host.isEmpty()
                || "localhost".equals(host)
                || "0.0.0.0".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || host.endsWith(".localhost")
                || host.startsWith("10.")
                || host.startsWith("192.168.")
                || private172(host)) {
            throw new IllegalArgumentException("Private-network attachment sources are not permitted");
        }

        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) throw new IllegalArgumentException("Attachment host did not resolve");
        for (InetAddress address : resolved) {
            byte[] rawAddress = address.getAddress();
            boolean uniqueLocalV6 = rawAddress.length == 16 && ((rawAddress[0] & 0xfe) == 0xfc);
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || uniqueLocalV6) {
                throw new IllegalArgumentException("Attachment host resolves to a private/local address");
            }
        }
    }

    private HttpURLConnection openSafeRemote(String raw,
                                                  int connectTimeoutMs,
                                                  int readTimeoutMs) throws Exception {
        return openSafeRemote(raw, connectTimeoutMs, readTimeoutMs, 0L, "", "");
    }

    private HttpURLConnection openSafeRemote(String raw,
                                                  int connectTimeoutMs,
                                                  int readTimeoutMs,
                                                  long offset,
                                                  String etag,
                                                  String lastModified) throws Exception {
        String current = raw;
        for (int redirects = 0; redirects <= MAX_REMOTE_REDIRECTS; redirects++) {
            validateRemoteHttps(current);
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", "VideoStudio-Android/" + AppProtocol.APP_VERSION + " MCPv3-ResumableIngest");
            if (offset > 0L) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
                String validator = etag == null || etag.isEmpty() ? lastModified : etag;
                if (validator != null && !validator.isEmpty()) connection.setRequestProperty("If-Range", validator);
            }

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IllegalStateException("Attachment redirect had no destination");
                }
                current = new URL(url, location).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                throw new IllegalStateException("Attachment source rejected: HTTP " + code);
            }
            return connection;
        }
        throw new IllegalStateException("Too many attachment redirects");
    }

    private void copyRemoteToFile(HttpURLConnection connection,
                                  File file,
                                  long expected,
                                  JobManager.Job state,
                                  String projectId,
                                  String activity,
                                  String progressSuffix) throws Exception {
        long announced = expected > 0 ? expected : connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[256 * 1024];
            long bytes = 0L;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (n == 0) continue;
                bytes += n;
                out.write(buffer, 0, n);
                int rawProgress = announced > 0
                        ? ResumableTransferManager.progressPercent(bytes, announced)
                        : -1;
                int progress = rawProgress < 0 ? 50 : Math.min(97, 4 + (rawProgress * 93 / 100));
                checkpoint(state, activity,
                        (bytes / 1024L / 1024L) + " MB " + progressSuffix, progress, projectId);
            }
            out.flush();
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

    /**
     * Private inline still-image ingest used as a compatibility bridge when ChatGPT can read
     * an attachment but cannot expose an Android-downloadable HTTPS URL to the installed MCP
     * schema. The bytes travel inside the already owner-authenticated MCP command and are
     * written directly to app-private storage. Nothing is published to Gallery or a public URL.
     */
    private JSONObject importInlineBase64(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = sanitizeFileName(p.optString("name", "ChatGPT_frame.png"));
        String mime = p.optString("mime", "image/png").trim().toLowerCase(Locale.US);
        if (!("image/png".equals(mime) || "image/jpeg".equals(mime) || "image/webp".equals(mime))) {
            throw new IllegalArgumentException("Inline MCP ingest accepts PNG, JPEG or WebP still images only");
        }

        String encoded = p.optString("base64", "").trim();
        if (encoded.startsWith("data:")) {
            int comma = encoded.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Malformed data URL");
            encoded = encoded.substring(comma + 1);
        }
        if (encoded.isEmpty()) throw new IllegalArgumentException("Missing inline attachment bytes");
        if (encoded.length() > MAX_INLINE_BASE64_CHARS) {
            throw new IllegalArgumentException("Inline image exceeds VideoStudio's private MCP transfer limit");
        }

        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid base64 image payload");
        }
        if (bytes.length == 0 || bytes.length > MAX_INLINE_IMAGE_BYTES) {
            throw new IllegalArgumentException("Inline image exceeds VideoStudio's 12 MB decoded transfer limit");
        }

        String expectedSha = p.optString("sha256", "").trim().toLowerCase(Locale.US);
        if (!expectedSha.isEmpty()) {
            String actualSha = sha256Hex(bytes);
            if (!actualSha.equals(expectedSha)) throw new IllegalArgumentException("Inline image SHA-256 mismatch");
        }

        File dir = new File(getFilesDir(), "imports");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
        File file = new File(dir, System.currentTimeMillis() + "_" + name);
        boolean success = false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.flush();
            success = true;
        } finally {
            if (!success && file.exists()) file.delete();
        }

        // Decode bounds only, so malformed data is rejected without allocating the full bitmap.
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete();
            throw new IllegalArgumentException("Inline payload is not a readable image");
        }
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (pixels > 80_000_000L) {
            file.delete();
            throw new IllegalArgumentException("Inline image dimensions exceed VideoStudio's safe decode limit");
        }

        ProjectStore.Asset asset = addImportedAsset(project, file, name, mime);
        ActivityLog.add(this, "chatgpt", "Private inline frame imported",
                name + " • " + bounds.outWidth + "×" + bounds.outHeight,
                "success", 100, null, project.id);

        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("assetId", asset.id);
        result.put("name", asset.name);
        result.put("mime", asset.mime);
        result.put("size", bytes.length);
        result.put("width", bounds.outWidth);
        result.put("height", bounds.outHeight);
        result.put("transport", "owner-authenticated-inline-mcp");
        return result;
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
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
            try {
                copyRemoteToFile(connection, file, expected, state, project.id,
                        "Importing ChatGPT file", "securely streamed");
            } catch (Exception error) {
                if (file.exists()) file.delete();
                throw error;
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
        String url = p.optString("url", "").trim();
        validateRemoteHttps(url);
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT import");
        String sha256 = p.optString("sha256", "").trim().toLowerCase(Locale.US);
        long sizeHint = Math.max(0L, p.optLong("size", 0L));
        String transferId = stableTransferId(project.id, url, name);

        JobManager.Job job = jobs.submit("Import • " + name, JobManager.Kind.LIGHT, state -> {
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            File file = new File(dir, transferId + "_" + sanitizeFileName(name));

            ResumableTransferManager.Result transfer = file.isFile()
                    ? new ResumableTransferManager.Result(file, file.length(), sizeHint > 0 ? sizeHint : file.length(), true, "")
                    : transferManager.download(
                            new ResumableTransferManager.Request(transferId, url, file, sizeHint, sha256),
                            (source, offset, etag, lastModified) ->
                                    openSafeRemote(source, 15000, 60000, offset, etag, lastModified),
                            (completed, expected) -> {
                                int rawProgress = ResumableTransferManager.progressPercent(completed, expected);
                                int progress = rawProgress < 0 ? 45 : Math.min(97, 4 + (rawProgress * 93 / 100));
                                checkpoint(state, "Importing media",
                                        (completed / 1024L / 1024L) + " MB received",
                                        progress, project.id);
                            }
                    );

            String mime = transfer.contentType;
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            addImportedAsset(project, file, name, mime);
            checkpoint(state, "Importing media", "Import complete", 100, project.id);
            syncProtocolState();
        });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("transferId", transferId);
        result.put("transport", "resumable-url-ingest");
        return result;
    }

    private String stableTransferId(String projectId, String source, String name) {
        String seed = String.valueOf(projectId) + "\n" + String.valueOf(source) + "\n" + String.valueOf(name);
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private ProjectStore.Asset addImportedAsset(ProjectStore.Project project, File file, String name, String mime) {
        ProjectStore.Asset asset = new ProjectStore.Asset();
        asset.id = UUID.randomUUID().toString();
        asset.uri = Uri.fromFile(file).toString();
        asset.name = name;
        asset.mime = mime;
        asset.durationMs = fileDuration(file);
        asset.sizeBytes = file.length();
        asset.seekable = true;
        asset.persistedReadAccess = true;
        asset.providerAuthority = "";
        for (ProjectStore.Asset existing : project.assets) {
            if (asset.uri.equals(existing.uri)) return existing;
        }
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
        return asset;
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

    private JSONObject connectionHealth() {
        JSONObject out = ok();
        try {
            out.put("appVersion", AppProtocol.APP_VERSION);
            out.put("protocolVersion", protocol.protocolVersion());
            out.put("appGeneration", protocol.appGeneration());
            out.put("stableMcpEndpoint", true);
            out.put("stableMcpPath", McpConnectionCore.STABLE_MCP_PATH);
            out.put("connectionCore", protocol.connectionStatus());
            out.put("serviceOnline", prefs.getBoolean(KEY_SERVICE_ONLINE, false));
            out.put("serviceDetail", prefs.getString(KEY_SERVICE_DETAIL, ""));
            out.put("serviceHeartbeat", prefs.getLong("control_service_heartbeat", 0));
            out.put("serviceAppVersion", prefs.getString("control_service_app_version", ""));
            out.put("serviceAppGeneration", prefs.getLong("control_service_app_generation", 0));
            out.put("serviceConnectionCoreVersion", prefs.getInt("control_service_connection_core_version", 0));
            out.put("controlPaused", protocol.isControlPaused());
            out.put("deviceIdentityPersistent", true);
            out.put("ownerCredentialPersistent", true);
            out.put("galleryAccess", false);
        } catch (Exception ignored) {}
        return out;
    }

    private JSONObject nativeSelfTest() {
        JSONObject out = ok();
        File probe = null;
        try {
            File dir = new File(getFilesDir(), "v3_health");
            boolean dirReady = dir.exists() || dir.mkdirs();
            probe = new File(dir, "probe.tmp");
            if (dirReady) {
                try (FileOutputStream stream = new FileOutputStream(probe)) {
                    stream.write("videostudio-v3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    stream.flush();
                }
            }
            out.put("appVersion", AppProtocol.APP_VERSION);
            out.put("protocolVersion", protocol.protocolVersion());
            out.put("stableMcpEndpoint", true);
            out.put("appGeneration", protocol.appGeneration());
            out.put("connectionCore", protocol.connectionStatus());
            out.put("nativeAgent", "videostudio-v3");
            out.put("privateStorageWritable", dirReady && probe.exists() && probe.length() > 0);
            out.put("projectStoreReady", store.summaries() != null);
            out.put("projectStorage", store.storageBackend());
            out.put("jobEngineReady", jobs.state() != null);
            out.put("renderEngineReady", renderEngine != null);
            out.put("analysisEngineReady", mediaAnalyzer != null);
            out.put("promptVideoEngineReady", promptVideoEngine != null);
            out.put("portraitAnimationEngineReady", portraitMotionAnalyzer != null);
            out.put("localSpeechEngineReady", speechEngine != null);
            out.put("motionScriptCompilerReady", motionScriptCompiler != null);
            out.put("creativeWorkspaceReady", creativeWorkspace != null);
            out.put("capabilityRegistryReady", capabilityRegistry != null);
            out.put("modelPackManagerReady", modelPackManager != null);
            out.put("computePlannerReady", computeProfile != null);
            out.put("creativeJobGraphReady", creativeJobGraph != null);
            out.put("creativeNodeStoreReady", creativeNodeStore != null);
            out.put("builtInCreativeRuntimeReady", builtInCreativeRuntime != null);
            out.put("renderCriticReady", renderCritic != null);
            out.put("driveWorkspaceProviderReady", driveWorkspace != null);
            out.put("driveWorkspaceLinked", driveWorkspace != null && driveWorkspace.isLinked());
            out.put("bundledSubjectSegmentation", true);
            out.put("bundledFaceMesh", true);
            out.put("permissionMode", permissionMode());
            out.put("galleryAccess", false);
            out.put("directAttachmentIngest", true);
            out.put("inlineAttachmentIngest", true);
            out.put("controlPaused", protocol.isControlPaused());
            out.put("backgroundService", true);
            out.put("result", "VideoStudio v3 native core healthy");
        } catch (Exception error) {
            try {
                out.put("ok", false);
                out.put("error", error.getMessage() == null ? "Self-test failed" : error.getMessage());
            } catch (Exception ignored) {}
        } finally {
            if (probe != null && probe.exists()) {
                try { probe.delete(); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private JSONObject stateJson() {
        JSONObject out = ok();
        try {
            out.put("deviceId", protocol.deviceId());
            out.put("appVersion", AppProtocol.APP_VERSION);
            out.put("protocolVersion", protocol.protocolVersion());
            out.put("mcpEndpointVersion", "v3-stable");
            out.put("stableMcpEndpoint", true);
            out.put("stableMcpPath", McpConnectionCore.STABLE_MCP_PATH);
            out.put("appGeneration", protocol.appGeneration());
            out.put("connectionCore", protocol.connectionStatus());
            out.put("nativeAgent", "videostudio-v3");
            out.put("directAttachmentIngest", true);
            out.put("inlineAttachmentIngest", true);
            out.put("localEngineOwnsProjects", true);
            out.put("portraitAnimationEngine", "v3.2-articulated-parallax");
            out.put("onDevicePortraitAi", true);
            out.put("nativeApp", true);
            out.put("backgroundControl", true);
            out.put("permissionMode", permissionMode());
            out.put("controlPaused", protocol.isControlPaused());
            out.put("galleryAccess", false);
            out.put("galleryBoundary", "MCP v3 cannot list, browse or enumerate Gallery media. Only user-picked files, VideoStudio-owned media and explicit ChatGPT attachments are usable.");
            out.put("projects", store.summaries().optJSONArray("projects"));
            out.put("projectStorage", store.storageBackend());
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
                out.put("creativeWorkspace", creativeWorkspace.status(active.id));
                JSONArray assets = new JSONArray();
                for (ProjectStore.Asset a : active.assets) {
                    JSONObject ai = new JSONObject();
                    ai.put("id", a.id);
                    ai.put("name", a.name);
                    ai.put("mime", a.mime);
                    ai.put("durationMs", a.durationMs);
                    ai.put("role", a.role);
                    ai.put("generated", a.generated);
                    ai.put("createdAt", a.createdAt);
                    assets.put(ai);
                }
                out.put("activeAssets", assets);
            }
            out.put("workload", jobs.state());
            out.put("recoveryPlans", recoveryPlans.recent(12));
            out.put("capabilityRegistry", capabilityRegistry.describe());
            out.put("computeProfile", computeProfile.snapshot());
            out.put("driveWorkspace", driveWorkspace.status());
            out.put("creatorCatalog", CreatorCatalog.describe());
            out.put("recentActivity", ActivityLog.recent(this, 30));
            out.put("commandJournal", commandJournal.recent(20));
        } catch (Exception ignored) {}
        return out;
    }

    private boolean isAllowed(String action, JSONObject parameters) {
        String lower = action == null ? "" : action.toLowerCase(Locale.US);

        // This is an architectural privacy wall, not a user permission tier.
        // Even Full Autonomous cannot enumerate or browse the phone Gallery.
        if (lower.contains("gallery") || lower.contains("media_library") || lower.contains("photo_library")) return false;

        String mode = permissionMode();
        if ("one_file".equals(mode)) {
            String allowed = prefs.getString(KEY_FILE, "");
            if (allowed.isEmpty()) return false;
            if ("apply_tool".equals(action)) {
                ProjectStore.Project project = resolveProject(parameters.optString("projectId", ""));
                int index = parameters.optInt("clipIndex", -1);
                return index >= 0 && index < project.clips.size() && allowed.equals(project.clips.get(index).assetId);
            }
            return "ping".equals(action)
                    || "get_state".equals(action)
                    || "self_test".equals(action)
                    || "connection_health".equals(action)
                    || "reconnect_mcp".equals(action)
                    || "job_status".equals(action)
                    || "activity_note".equals(action)
                    || "analyse_media".equals(action)
                    || "preview_project".equals(action)
                    || "export_project".equals(action)
                    || "cancel_job".equals(action)
                    || "cancel_all_jobs".equals(action)
                    || "stop_all".equals(action);
        }

        // "all_tools" is accepted as a legacy alias, but from v3.2 onward it
        // means the same thing as Full Autonomous. No routine VideoStudio
        // operation is permission-gated in the autonomous mode.
        return true;
    }

    private int cancelAllNativeWork() {
        if (activeRender != null) {
            activeRender.cancel();
            activeRender = null;
        }
        int jobsCancelled = jobs.cancelAll();
        recoveryPlans.cancelActive();
        return jobsCancelled;
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
        state.checkpoint(action, progress, detail);
        recoveryPlans.checkpointForJob(state.id, action, progress, detail);
        ActivityLog.progress(this, state.id, action, detail, progress, projectId);
        try {
            JSONObject live = new JSONObject();
            live.put("jobId", state.id);
            live.put("projectId", projectId == null ? "" : projectId);
            live.put("action", action == null ? "" : action);
            live.put("detail", detail == null ? "" : detail);
            live.put("progress", Math.max(0, Math.min(100, progress)));
            live.put("state", state.state);
            live.put("updatedAt", System.currentTimeMillis());
            prefs.edit().putString("job_recovery_snapshot", live.toString()).apply();
        } catch (Exception ignored) {}
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
            case "generate_voice": return "Generating local narration";
            case "compile_scene": return "Compiling MotionScript";
            case "run_motion_script": return "Running MotionScript";
            case "plan_creative_graph": return "Planning CreativeIR execution graph";
            case "run_creative_graph": return "Executing CreativeIR graph";
            case "creative_graph_status": return "Reading CreativeIR node checkpoints";
            case "invalidate_creative_node": return "Invalidating CreativeIR node";
            case "workspace_status": return "Reading creative workspace";
            case "cleanup_workspace": return "Cleaning creative workspace";
            case "capability_registry": return "Reading capability providers";
            case "resolve_capability": return "Resolving creative capability";
            case "model_pack_status": return "Reading model packs";
            case "install_model_pack": return "Installing model pack";
            case "uninstall_model_pack": return "Removing model pack";
            case "compute_profile": return "Reading device compute profile";
            case "plan_compute": return "Planning local AI working set";
            case "drive_workspace_status": return "Reading cloud workspace";
            case "drive_workspace_inventory": return "Scanning linked cloud workspace";
            case "sync_project_to_drive": return "Archiving project to cloud workspace";
            case "offload_project_to_drive": return "Offloading project workspace to cloud";
            case "restore_project_from_drive": return "Restoring project creative workspace";
            case "archive_model_pack_to_drive": return "Archiving model pack to cloud workspace";
            case "restore_model_pack_from_drive": return "Restoring model pack from cloud workspace";
            case "animate_images": return "Animating still images";
            case "job_status": return "Reading native job status";
            case "export_project": return "Exporting project";
            case "insert_asset_timeline": return "Adding media to timeline";
            case "import_attachment": return "Importing ChatGPT attachment directly";
            case "import_chat_file": return "Importing ChatGPT file";
            case "import_url": return "Importing media";
            case "preview_project": return "Opening preview";
            case "cancel_job": return "Cancelling job";
            case "cancel_all_jobs":
            case "stop_all": return "Stopping VideoStudio jobs";
            case "get_state": return "Reading VideoStudio state";
            case "self_test": return "Running VideoStudio v3 self-test";
            case "connection_health": return "Checking stable MCP connection";
            case "reconnect_mcp": return "Rebinding stable MCP connection";
            case "activity_note": return "ChatGPT progress";
            default: return action.replace('_', ' ');
        }
    }

    private String commandDetail(String action, JSONObject p) {
        if (p == null) return "Received from ChatGPT";
        if ("apply_tool".equals(action)) return p.optString("tool", "edit") + " • clip " + (p.optInt("clipIndex", 0) + 1);
        if ("create_project".equals(action)) return p.optString("name", "New project");
        if ("import_attachment".equals(action) || "import_chat_file".equals(action) || "import_url".equals(action)) return p.optString("name", "Media");
        if ("animate_images".equals(action)) {
            return p.optString("style", "cinematic") + " • " + p.optString("environment", "ambient");
        }
        if ("prompt_video".equals(action)) {
            String prompt = p.optString("prompt", "");
            return prompt.length() > 90 ? prompt.substring(0, 90) + "…" : prompt;
        }
        if ("compile_scene".equals(action) || "run_motion_script".equals(action)) {
            String script = p.optString("script", p.optString("source", ""));
            return script.length() > 90 ? script.substring(0, 90) + "…" : script;
        }
        return "Received from ChatGPT";
    }

    private String completionDetail(String action, JSONObject result) {
        if ("create_project".equals(action)) return "Project created inside VideoStudio";
        if ("apply_edit_plan".equals(action)) return result.optInt("clipCount", 0) + " timeline clip(s) applied";
        if ("apply_tool".equals(action)) return "Edit applied inside VideoStudio";
        if ("creator_preset".equals(action)) return result.optInt("changedClips", 0) + " clip(s) styled";
        if ("generate_voice".equals(action)) return "Local narration generation queued";
        if ("compile_scene".equals(action)) return "MotionScript compiled to CreativeIR";
        if ("run_motion_script".equals(action)) return result.optInt("changedClips", 0) + " clip(s) directed by MotionScript";
        if ("plan_creative_graph".equals(action)) return result.optBoolean("ready", false) ? "Creative execution graph ready" : "Creative graph planned with unresolved providers";
        if ("run_creative_graph".equals(action)) return "CreativeIR execution queued";
        if ("creative_graph_status".equals(action)) return "Creative node checkpoints read";
        if ("invalidate_creative_node".equals(action)) return result.optInt("invalidated", 0) + " creative node(s) invalidated for targeted re-execution";
        if ("workspace_status".equals(action)) return "Creative workspace status read";
        if ("cleanup_workspace".equals(action)) return "Regenerable creative workspace cleaned";
        if ("capability_registry".equals(action)) return "Capability provider registry read";
        if ("resolve_capability".equals(action)) return result.optBoolean("resolved", false) ? "Creative capability resolved" : "No installed provider resolved";
        if ("model_pack_status".equals(action)) return "Optional model-pack status read";
        if ("install_model_pack".equals(action)) return "Transactional model-pack install queued";
        if ("uninstall_model_pack".equals(action)) return result.optBoolean("ok", false) ? "Model pack removed" : result.optString("error", "Model pack not removed");
        if ("compute_profile".equals(action)) return "Device compute profile measured";
        if ("plan_compute".equals(action)) return "Local AI working set planned";
        if ("drive_workspace_status".equals(action)) return result.optBoolean("linked", false) ? "Cloud workspace is linked" : "Cloud workspace is not linked";
        if ("drive_workspace_inventory".equals(action)) return "Folder-scoped cloud workspace scanned";
        if ("sync_project_to_drive".equals(action)) return "Project archive queued to folder-scoped cloud workspace";
        if ("offload_project_to_drive".equals(action)) return "Verified cloud archive and local workspace offload queued";
        if ("restore_project_from_drive".equals(action)) return "Project workspace restore queued from cloud";
        if ("archive_model_pack_to_drive".equals(action)) return "Model-pack archive queued to cloud";
        if ("restore_model_pack_from_drive".equals(action)) return "Model-pack restore queued from cloud";
        if ("connection_health".equals(action)) return "Stable MCP connection health read";
        if ("reconnect_mcp".equals(action)) return "Stable MCP re-registration requested without changing identity";
        if ("animate_images".equals(action)) return result.optInt("imageCount", 0) + " image clip(s) queued for native animation";
        if ("insert_asset_timeline".equals(action)) return result.optBoolean("inserted", false) ? "Media added to timeline" : "Media could not be added to timeline";
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

    private void migrateAutonomyDefaultOnce() {
        if (prefs.getBoolean(KEY_AUTONOMY_MIGRATED, false)) return;
        prefs.edit()
                .putString(KEY_MODE, "everything")
                .putBoolean(KEY_AUTONOMY_MIGRATED, true)
                .apply();
    }

    private String permissionMode() {
        String raw = prefs.getString(KEY_MODE, "everything");
        if ("one_file".equals(raw)) return "one_file";
        if (!"everything".equals(raw)) {
            prefs.edit().putString(KEY_MODE, "everything").apply();
        }
        return "everything";
    }

    private void syncProtocolState() {
        protocol.setLocalState(permissionMode(), store.summaries());
    }

    private void markService(boolean online, String detail) {
        prefs.edit()
                .putBoolean(KEY_SERVICE_ONLINE, online)
                .putString(KEY_SERVICE_DETAIL, detail == null ? "" : detail)
                .putString("control_service_app_version", AppProtocol.APP_VERSION)
                .putLong("control_service_app_generation", protocol == null ? 0 : protocol.appGeneration())
                .putInt("control_service_connection_core_version", McpConnectionCore.CORE_VERSION)
                .putLong("control_service_heartbeat", System.currentTimeMillis())
                .apply();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "VideoStudio MCP control", NotificationManager.IMPORTANCE_LOW);
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

