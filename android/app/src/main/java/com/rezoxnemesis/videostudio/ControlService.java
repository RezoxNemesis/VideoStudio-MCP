package com.rezoxnemesis.videostudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public final class ControlService extends Service implements AppProtocol.Callback {
    public static final String ACTION_CANCEL_ALL = "com.rezoxnemesis.videostudio.CANCEL_ALL";
    public static final String ACTION_PAUSE = "com.rezoxnemesis.videostudio.PAUSE_CONTROL";
    public static final String ACTION_RESUME = "com.rezoxnemesis.videostudio.RESUME_CONTROL";
    public static final String ACTION_RECONNECT = "com.rezoxnemesis.videostudio.RECONNECT";
    public static final String ACTION_SYNC = "com.rezoxnemesis.videostudio.SYNC_STATE";
    public static final String ACTION_LOCAL_ANIMATE = "com.rezoxnemesis.videostudio.LOCAL_ANIMATE_IMAGES";
    public static final String ACTION_LOCAL_PROMPT_VIDEO = "com.rezoxnemesis.videostudio.LOCAL_PROMPT_VIDEO";
    public static final String ACTION_LOCAL_EXPORT = "com.rezoxnemesis.videostudio.LOCAL_EXPORT_PROJECT";
    public static final String ACTION_LOCAL_EXPORT_STATUS = "com.rezoxnemesis.videostudio.LOCAL_EXPORT_STATUS";
    public static final String ACTION_CANCEL_LOCAL_EXPORT = "com.rezoxnemesis.videostudio.CANCEL_LOCAL_EXPORT";
    public static final String ACTION_LOCAL_METADATA_MIRROR = "com.rezoxnemesis.videostudio.LOCAL_METADATA_MIRROR";
    public static final String ACTION_LOCAL_SOURCE_MEDIA = "com.rezoxnemesis.videostudio.LOCAL_SOURCE_MEDIA";
    public static final String ACTION_LOCAL_SOURCE_MEDIA_STATUS = "com.rezoxnemesis.videostudio.LOCAL_SOURCE_MEDIA_STATUS";
    public static final String ACTION_CANCEL_LOCAL_SOURCE_MEDIA = "com.rezoxnemesis.videostudio.CANCEL_LOCAL_SOURCE_MEDIA";
    public static final String ACTION_FORGET_LOCAL_SOURCE_MEDIA = "com.rezoxnemesis.videostudio.FORGET_LOCAL_SOURCE_MEDIA";
    public static final String KEY_MANUAL_SOURCE_MEDIA_SESSION = "manual_source_media_session";
    public static final String KEY_MANUAL_EXPORT_SESSION = "manual_export_session";
    private static final String CHANNEL = "videostudio_private_control";
    private static final int NOTIFICATION_ID = 6101;
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_MODE = "permission_mode";
    private static final String KEY_FILE = "allowed_asset_id";
    private static final String KEY_SERVICE_ONLINE = "control_service_online";
    private static final String KEY_SERVICE_DETAIL = "control_service_detail";
    private static final String KEY_AUTONOMY_MIGRATED = "autonomy_everything_v32_migrated";
    // Private MCP JSON fallback for small ChatGPT attachments when the host cannot expose a usable temporary HTTPS file URL.
    // Kept deliberately bounded so normal video transfer stays on the streaming handoff path.
    private static final long MAX_INLINE_MEDIA_BYTES = 12L * 1024L * 1024L;
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
    private volatile String activeRenderJobId = "";
    private volatile boolean activeRenderOwnerInitiated;
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
    private NativeMetadataMirror metadataMirror;
    private SourceMediaVault sourceMediaVault;
    private SharedPreferences prefs;
    private CommandJournal commandJournal;
    private final ExecutorService commandCompletionWatchers = Executors.newFixedThreadPool(2);
    private final Set<String> watchedCommands = ConcurrentHashMap.newKeySet();
    private final Set<String> watchedManualExports = ConcurrentHashMap.newKeySet();
    private final Set<String> watchedSourceMedia = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService manualExportMonitor = Executors.newSingleThreadScheduledExecutor();
    // Serialize transfer, verification and registration together. Replayed light
    // jobs must never write an identical partial/final file concurrently.
    private static final ReentrantLock attachmentIngestionLock = new ReentrantLock(true);
    // Admission and orphan cleanup share a process lock across service instances.
    // Native cancellation can finish after a worker was interrupted; its pin
    // remains referenced until the actual terminal callback arrives.
    private static final ReentrantLock exportAdmissionLock = new ReentrantLock(true);
    private static final Set<String> runningExportPins = ConcurrentHashMap.newKeySet();
    private static final Set<String> renderingExportPins = ConcurrentHashMap.newKeySet();
    private static final Set<String> runningSourcePins = ConcurrentHashMap.newKeySet();
    private static final Set<String> runningSourceRequests = ConcurrentHashMap.newKeySet();
    private static final Set<String> runningDurableHeavyPlans = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, String> exportPinsByJob = new ConcurrentHashMap<>();
    private volatile boolean serviceAlive;
    private volatile boolean deferredPinnedRecovery;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
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
        creativeWorkspace = new CreativeWorkspace(this, store);
        motionScriptCompiler = new MotionScriptCompiler();
        recoveryPlans = new RecoveryPlanStore(this);
        capabilityRegistry = new CapabilityRegistry(this);
        modelPackManager = new ModelPackManager(this);
        computeProfile = new DeviceComputeProfile(this);
        creativeJobGraph = new CreativeJobGraph(capabilityRegistry, computeProfile);
        creativeNodeStore = new CreativeNodeStore(creativeWorkspace, store);
        renderCritic = new NativeRenderCritic(this);
        builtInCreativeRuntime = new CreativeBuiltInRuntime(portraitMotionAnalyzer, renderCritic, creativeNodeStore);
        driveWorkspace = new DriveWorkspaceProvider(this);
        metadataMirror = new NativeMetadataMirror(this, store, protocol);
        sourceMediaVault = new SourceMediaVault(this, store);
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
        reattachInflightCommandWatchers();
        manualExportMonitor.scheduleWithFixedDelay(this::refreshManualExportStatuses, 0, 1, TimeUnit.SECONDS);
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
            int cancelled = jobs.cancelAutonomous();
            if (activeRender != null && !activeRenderOwnerInitiated) activeRender.cancel();
            recoveryPlans.cancelAutonomous();
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
                p.put("_ownerInitiated", true);
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
        } else if (ACTION_LOCAL_PROMPT_VIDEO.equals(action)) {
            try {
                JSONObject p = new JSONObject();
                p.put("_ownerInitiated", true);
                p.put("prompt", intent.getStringExtra("prompt") == null ? "" : intent.getStringExtra("prompt"));
                p.put("durationSeconds", Math.max(4, Math.min(120, intent.getIntExtra("durationSeconds", 18))));
                p.put("aspect", intent.getStringExtra("aspect") == null ? "9:16" : intent.getStringExtra("aspect"));
                p.put("quality", intent.getStringExtra("quality") == null ? "1080p" : intent.getStringExtra("quality"));
                p.put("style", intent.getStringExtra("style") == null ? "cinematic" : intent.getStringExtra("style"));
                p.put("fileName", intent.getStringExtra("fileName") == null
                        ? "VideoStudio_AI_" + System.currentTimeMillis() + ".mp4"
                        : intent.getStringExtra("fileName"));
                JSONObject queued = queuePromptVideo(p);
                ActivityLog.add(this, "user", "Prompt video queued",
                        "Native job " + shortId(queued.optString("jobId")) + " • project " + shortId(queued.optString("projectId")),
                        "queued", 0, null, queued.optString("projectId", ""));
            } catch (Exception error) {
                ActivityLog.add(this, "user", "Prompt video failed",
                        error.getMessage() == null ? "Could not queue prompt video" : error.getMessage(),
                        "failed", null, null, null);
            }
        } else if (ACTION_FORGET_LOCAL_SOURCE_MEDIA.equals(action)) {
            String requestId = intent.getStringExtra("requestId");
            try {
                UUID.fromString(requestId);
                JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + requestId, "{}"));
                String operation = session.optString("operation", "");
                JobManager.Job forgetJob = jobs.submitOwnerPriority("Forget retained source transfer", JobManager.Kind.LIGHT, state -> {
                    exportAdmissionLock.lockInterruptibly();
                    try {
                        JSONObject plan = recoveryPlans.findByRequest("archive".equals(operation) ? "archive_source_media" : "restore_source_media", "_manualSourceSession", requestId);
                        JSONObject parameters = plan == null ? null : plan.optJSONObject("parameters");
                        if (parameters == null || !parameters.optBoolean("_ownerInitiated", false) || !"cancelled".equals(plan.optString("state")))
                            throw new IllegalArgumentException("Cancel the exact owner transfer before forgetting retained work");
                        if (!sourceWorkerStopped(plan)) throw new IllegalStateException("The previous source worker is still stopping");
                        JSONObject forgotten = sourceMediaVault.forgetRetainedRequest(parameters.getString("projectId"), parameters.getString("assetId"),
                                parameters.getString("storageTreeUri"), parameters.optString("generationId", ""), parameters.getString("requestId"), "archive".equals(operation));
                        if (!forgotten.optBoolean("forgotten", false)) throw new IllegalStateException("Local retained-source cleanup was not confirmed");
                        if (!recoveryPlans.forgetCancelledSourcePlan(plan.getString("id"), requestId))
                            throw new IllegalStateException("Local transfer was forgotten but its cancelled ledger remains; retry explicit Forget");
                        state.setResult(forgotten);
                        publishSourceMediaStatus(requestId, parameters.optString("projectId"), parameters.optString("assetId"), operation, state.id,
                                "forgotten", "Retained local transfer released; original media and remote archives preserved", 100, forgotten);
                        watchedSourceMedia.remove(requestId);
                    } catch (Exception error) {
                        publishSourceMediaStatus(requestId, session.optString("projectId"), session.optString("assetId"), operation, state.id,
                                "cancelled", error.getMessage(), session.optInt("progress", 0), session.optJSONObject("receipt"));
                        throw error;
                    } finally { exportAdmissionLock.unlock(); }
                });
                publishSourceMediaStatus(requestId, session.optString("projectId"), session.optString("assetId"), operation, forgetJob.id,
                        "forgetting", "Releasing only the exact local cancelled transfer", session.optInt("progress", 0), session.optJSONObject("receipt"));
            } catch (Exception error) {
                ActivityLog.add(this, "user", "Forget source transfer failed", error.getMessage() == null ? "No matching cancelled owner transfer" : error.getMessage(), "failed", null, null, null);
            }
        } else if (ACTION_CANCEL_LOCAL_SOURCE_MEDIA.equals(action)) {
            try {
                String requestId = intent.getStringExtra("requestId");
                UUID.fromString(requestId);
                JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + requestId, "{}"));
                String operation = session.optString("operation", ""), sourceAction = "archive".equals(operation) ? "archive_source_media" : "restore_source_media";
                JSONObject plan = recoveryPlans.findByRequest(sourceAction, "_manualSourceSession", requestId);
                JSONObject parameters = plan == null ? null : plan.optJSONObject("parameters");
                if (parameters == null || !parameters.optBoolean("_ownerInitiated", false) || !requestId.equals(parameters.optString("_manualSourceSession")))
                    throw new IllegalArgumentException("This owner source transfer no longer has a matching session");
                if (JobManager.isTerminal(plan.optString("state"))) return START_STICKY;
                String jobId = plan.optString("jobId", "");
                if (!jobId.isEmpty()) jobs.cancel(jobId);
                recoveryPlans.cancelPlan(plan.getString("id"));
                publishSourceMediaStatus(requestId, parameters.optString("projectId"), parameters.optString("assetId"), operation,
                        jobId, "cancelled", "Transfer stopped; verified journal retained for explicit owner resume", session.optInt("progress", 0), plan.optJSONObject("result"));
            } catch (Exception error) {
                ActivityLog.add(this, "user", "Source transfer cancellation failed", error.getMessage() == null ? "No matching owner transfer" : error.getMessage(), "failed", null, null, null);
            }
        } else if (ACTION_LOCAL_SOURCE_MEDIA.equals(action)) {
            try {
                JSONObject p = new JSONObject(); p.put("_ownerInitiated", true);
                p.put("projectId", intent.getStringExtra("projectId")); p.put("assetId", intent.getStringExtra("assetId"));
                if (intent.hasExtra("expectedRevision")) p.put("expectedRevision", intent.getLongExtra("expectedRevision", -1L));
                if (intent.hasExtra("storageTreeUri")) p.put("storageTreeUri", intent.getStringExtra("storageTreeUri"));
                if (intent.hasExtra("generationId")) p.put("generationId", intent.getStringExtra("generationId"));
                String requestId = intent.getStringExtra("requestId");
                if (requestId == null || requestId.isEmpty()) requestId = UUID.randomUUID().toString();
                UUID.fromString(requestId); p.put("_manualSourceSession", requestId);
                p.put("_resumeSourceRequested", intent.getBooleanExtra("resume", false));
                String operation = intent.getStringExtra("operation");
                String sourceAction = "archive".equals(operation) ? "archive_source_media"
                        : "restore".equals(operation) ? "restore_source_media" : "";
                if (sourceAction.isEmpty()) throw new IllegalArgumentException("Choose archive or restore for one source asset");
                JSONObject queued = queueSourceMedia(sourceAction, p);
                ActivityLog.add(this, "user", "Source media " + operation,
                        "Native job " + shortId(queued.optString("jobId", "")), "queued", 0, null, p.optString("projectId", ""));
            } catch (Exception error) {
                String requestId = intent.getStringExtra("requestId");
                JSONObject retained = new JSONObject();
                try { retained = new JSONObject(prefs.getString(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + requestId, "{}")); } catch (Exception ignored) {}
                boolean deferredResume = intent.getBooleanExtra("resume", false)
                        && ("cancelled".equals(retained.optString("status")) || "checkpointed".equals(retained.optString("status")));
                publishSourceMediaStatus(requestId, intent.getStringExtra("projectId"), intent.getStringExtra("assetId"),
                        intent.getStringExtra("operation"), deferredResume ? retained.optString("jobId", "") : "",
                        deferredResume ? retained.optString("status") : "failed", error.getMessage(), retained.optInt("progress", 0), retained.optJSONObject("receipt"));
                ActivityLog.add(this, "user", "Source media request failed", error.getMessage() == null ? "Could not admit source media work" : error.getMessage(),
                        "failed", null, null, intent.getStringExtra("projectId"));
            }
        } else if (ACTION_LOCAL_METADATA_MIRROR.equals(action)) {
            try {
                JSONObject p = new JSONObject();
                p.put("_ownerInitiated", true);
                p.put("projectId", intent.getStringExtra("projectId"));
                p.put("enabled", intent.getBooleanExtra("enabled", false));
                if (intent.hasExtra("expectedMirrorRevision")) p.put("expectedMirrorRevision", intent.getLongExtra("expectedMirrorRevision", -1));
                if (intent.hasExtra("expectedNativeRevision")) p.put("expectedNativeRevision", intent.getLongExtra("expectedNativeRevision", -1));
                if (intent.hasExtra("expectedPendingMirrorRevision")) p.put("expectedPendingMirrorRevision", intent.getLongExtra("expectedPendingMirrorRevision", -1));
                if (intent.hasExtra("expectedPendingFingerprint")) p.put("expectedPendingFingerprint", intent.getStringExtra("expectedPendingFingerprint"));
                if (intent.hasExtra("expectedPendingId")) p.put("expectedPendingId", intent.getStringExtra("expectedPendingId"));
                String operation = intent.getStringExtra("operation");
                JSONObject queued = queueMetadataMirror("metadata_mirror_" + (operation == null ? "status" : operation), p);
                ActivityLog.add(this, "user", "Project metadata mirror", "Metadata job " + shortId(queued.optString("jobId", "")),
                        "queued", 0, null, p.optString("projectId", ""));
            } catch (Exception error) {
                ActivityLog.add(this, "user", "Project metadata mirror", error.getMessage(),
                        "failed", null, null, intent.getStringExtra("projectId"));
            }
        } else if (ACTION_CANCEL_LOCAL_EXPORT.equals(action)) {
            try {
                String sessionId = intent.getStringExtra("exportSessionId");
                JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_EXPORT_SESSION + ":" + sessionId, "{}"));
                String jobId = session.optString("jobId", "");
                if (!jobId.isEmpty() && jobs.cancel(jobId)) {
                    if (jobId.equals(activeRenderJobId) && activeRender != null) activeRender.cancel();
                    recoveryPlans.cancelByJob(jobId);
                    publishManualExportStatus(sessionId, session.optString("projectId", ""), jobId,
                            "cancelled", "Export cancelled", session.optInt("progress", 0));
                }
            } catch (Exception ignored) {}
        } else if (ACTION_LOCAL_EXPORT.equals(action)) {
            try {
                JSONObject p = new JSONObject();
                p.put("_ownerInitiated", true);
                String session = intent.getStringExtra("exportSessionId");
                p.put("_manualExportSession", session == null || session.isEmpty() ? UUID.randomUUID().toString() : session);
                p.put("projectId", intent.getStringExtra("projectId"));
                p.put("aspect", intent.getStringExtra("aspect") == null ? "9:16" : intent.getStringExtra("aspect"));
                p.put("quality", intent.getStringExtra("quality") == null ? "1080p" : intent.getStringExtra("quality"));
                p.put("fileName", intent.getStringExtra("fileName") == null
                        ? "VideoStudio_" + System.currentTimeMillis() + ".mp4"
                        : intent.getStringExtra("fileName"));
                if (intent.hasExtra("expectedRevision")) p.put("expectedRevision", intent.getLongExtra("expectedRevision", -1L));
                if (intent.hasExtra("exportMode")) p.put("exportMode", intent.getStringExtra("exportMode"));
                if (intent.hasExtra("frameRate")) p.put("frameRate", intent.getIntExtra("frameRate", -1));
                if (intent.hasExtra("inMs")) p.put("inMs", intent.getLongExtra("inMs", -1L));
                if (intent.hasExtra("outMs")) p.put("outMs", intent.getLongExtra("outMs", -1L));
                JSONObject queued = queueExport(p);
                ActivityLog.add(this, "user", "Export queued",
                        "Native job " + shortId(queued.optString("jobId")),
                        "queued", 0, null, queued.optString("projectId", ""));
            } catch (Exception error) {
                publishManualExportStatus(intent.getStringExtra("exportSessionId"),
                        intent.getStringExtra("projectId"), "", "failed",
                        error.getMessage() == null ? "Could not queue export" : error.getMessage(), 0);
                ActivityLog.add(this, "user", "Export failed",
                        error.getMessage() == null ? "Could not queue export" : error.getMessage(),
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
        boolean pendingOwner = recoveryPlans != null && recoveryPlans.hasPendingOwnerWork();
        if (pendingOwner) {
            NativeAgentWatchdog.schedule(this, NativeAgentWatchdog.TASK_REMOVED_DELAY_MS, "owner_work_interrupted");
        } else if (protocol == null || NativeAgentWatchdog.shouldRearm(protocol.isControlPaused())) {
            NativeAgentWatchdog.scheduleTaskRemoved(this);
        }
        markService(false, "MCP control plane available • native execution re-arming");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        serviceAlive = false;
        ActivityLog.add(this, "transport", "VideoStudio control stopped", "Background controller stopped", "info", null, null, null);
        markService(false, "Control service stopped");
        boolean paused = protocol != null && protocol.isControlPaused();
        if (jobs != null) jobs.suspendForRestart();
        if (activeRender != null) activeRender.cancel();
        if (protocol != null) protocol.stop();
        commandCompletionWatchers.shutdownNow();
        manualExportMonitor.shutdownNow();
        boolean pendingOwner = recoveryPlans != null && recoveryPlans.hasPendingOwnerWork();
        if (NativeAgentWatchdog.shouldRearm(paused) || pendingOwner) {
            NativeAgentWatchdog.scheduleRetry(this, pendingOwner ? "owner_work_interrupted" : "service_destroyed");
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
        markService(connected, detail);
        if (connected != wasOnline) {
            ActivityLog.add(this, "transport", connected ? "MCP connected" : "MCP reconnecting",
                    detail + " • app " + AppProtocol.APP_VERSION,
                    connected ? "info" : "running", null, null, null);
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
        if (command == null || protocol.isControlPaused() || !protocol.admitCommand(command)) return;
        String action = command.optString("action");
        JSONObject p = command.optJSONObject("parameters");
        if (p == null) p = new JSONObject();
        // Owner priority is granted only by a local Android action, never by MCP input.
        JSONArray parameterNames = p.names();
        if (parameterNames != null) for (int i = 0; i < parameterNames.length(); i++) {
            String name = parameterNames.optString(i);
            if (name.startsWith("_")) p.remove(name);
        }

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

        JSONObject inflight = commandJournal.inflight(commandId);
        if (inflight != null) {
            watchDeferredCommand(command, inflight.optJSONObject("queuedResult"));
            String inflightJobId = inflight.optString("jobId", "");
            JSONObject inflightState = inflightJobId.isEmpty() ? null : jobs.get(inflightJobId);
            JSONObject inflightJob = inflightState == null ? null : inflightState.optJSONObject("job");
            if (inflightJob != null) {
                updateDeferredActivity(command, inflight, inflightJob);
            } else {
                ActivityLog.add(this, "chatgpt", friendlyAction(action),
                        "Reattaching to native job • " + shortId(inflightJobId),
                        "queued", inflight.optInt("progress", 0), commandId,
                        inflight.optString("projectId", projectId));
            }
            return;
        }

        commandJournal.begin(command);
        if (!commandId.isEmpty()) {
            try { p.put("_mcpCommandId", commandId); } catch (Exception ignored) {}
        }
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
                    complete(command, stateJson(p));
                    return;
                case "project_state":
                    complete(command, projectStatePage(p));
                    return;
                case "self_test":
                    complete(command, nativeSelfTest());
                    return;
                case "metadata_mirror_sync":
                case "metadata_mirror_apply":
                case "metadata_mirror_retry":
                case "metadata_mirror_status":
                case "metadata_mirror_revoke":
                case "metadata_mirror_keep_native":
                    complete(command, queueMetadataMirror(action, p));
                    return;
                case "create_title":
                    complete(command, queueCreateTitle(p));
                    return;
                case "cel_create": case "cel_update":
                    complete(command, queueAnimationCel(action, p));
                    return;
                case "cel_describe": {
                    ProjectStore.Project project = resolveExistingProject(p.optString("projectId", ""));
                    String clipId = p.getString("clipId");
                    complete(command, ok().put("projectId", project.id).put("revision", project.revision)
                            .put("cel", AnimationCelEdits.describe(project, clipId))
                            .put("drawing", AnimationCelEdits.drawingForClip(project, clipId)));
                    return;
                }
                case "cel_exposure_add": case "cel_exposure_hold": case "cel_exposure_extend":
                case "cel_exposure_duplicate": case "cel_exposure_delete": case "animation_frame_rate": {
                    ProjectStore.Project project = resolveExistingProject(p.optString("projectId", ""));
                    long expectedRevision = titleInteger(p, "expectedRevision", project.revision, 0L, 9007199254740991L);
                    JSONObject settings = nativeAuthoringSettings(p);
                    ProjectStore.Project edited = store.edit(project.id, expectedRevision, action,
                            current -> AnimationCelEdits.apply(current, action, settings));
                    complete(command, projectEditResult(edited));
                    return;
                }
                case "rig_describe": {
                    ProjectStore.Project project = resolveExistingProject(p.optString("projectId", ""));
                    String clipId = p.getString("clipId");
                    long atMs = titleInteger(p, "atMs", 0L, 0L, AnimationRig2D.MAX_TIME_MS);
                    if (p.has("includeDefinition") && !(p.opt("includeDefinition") instanceof Boolean))
                        throw new IllegalArgumentException("includeDefinition must be a boolean");
                    JSONObject rigState = AnimationRigEdits.describe(project, clipId, atMs);
                    JSONObject result = ok().put("projectId", project.id).put("revision", project.revision).put("clipId", clipId)
                            .put("rig", rigState).put("definitionIncluded", p.optBoolean("includeDefinition", true));
                    ProjectStore.Clip clip = project.clip(clipId);
                    if (clip != null && clip.effects.optJSONObject("rig2d") != null) {
                        ProjectStore.Asset asset = project.asset(clip.assetId);
                        result.put("authoredPose", AnimationRig2D.compileForClip(clip, AnimationRigEdits.sourceAspect(asset)).authoredPoseClip(atMs));
                        result.put("sampleTimeDomain", "output_local_ms");
                    }
                    if (p.optBoolean("includeDefinition", true) && clip != null && clip.effects.optJSONObject("rig2d") != null) {
                        // describe compiled the same definition against the actual asset geometry.
                        JSONObject definition = new JSONObject(clip.effects.getJSONObject("rig2d").toString());
                        if (definition.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AnimationRig2D.MAX_JSON_BYTES)
                            throw new IllegalArgumentException("Rig definition exceeds its 256 KiB bound");
                        result.put("definition", definition);
                    }
                    complete(command, result);
                    return;
                }
                case "rig_create": case "rig_apply": case "rig_clear":
                case "rig_set_enabled":
                case "rig_set_bone": case "rig_remove_bone": case "rig_auto_weights": case "rig_set_weights": case "rig_paint_weights":
                case "rig_set_keyframe": case "rig_remove_keyframe": case "rig_set_pose":
                case "rig_move_keyframe":
                case "rig_capture_pose": case "rig_apply_pose": case "rig_remove_pose":
                case "rig_set_ik": case "rig_remove_ik": case "rig_set_ik_keyframe": case "rig_remove_ik_keyframe":
                case "rig_move_ik_keyframe": {
                    ProjectStore.Project project = resolveExistingProject(p.optString("projectId", ""));
                    long expectedRevision = titleInteger(p, "expectedRevision", project.revision, 0L, 9007199254740991L);
                    JSONObject settings = nativeAuthoringSettings(p);
                    String operation = "rig_create".equals(action) ? "create_rig" : "rig_apply".equals(action) ? "apply_rig"
                            : "rig_clear".equals(action) ? "clear_rig" : action.substring(4);
                    final JSONObject[] weightReceipt = new JSONObject[1];
                    ProjectStore.Project edited = store.edit(project.id, expectedRevision, action, current -> {
                        ProjectStore.Project before = ProjectStore.copy(current);
                        if ("paint_weights".equals(operation)) weightReceipt[0] = AnimationRigEdits.paintWeights(current, settings);
                        else AnimationRigEdits.apply(current, operation, settings);
                        requireChangedAuthoredNativeEffects(before, current);
                    });
                    JSONObject result = projectEditResult(edited);
                    if (weightReceipt[0] != null) result.put("weightPaint", weightReceipt[0]);
                    complete(command, result); syncProtocolState(); return;
                }
                case "set_motion_path": case "clear_motion_path": {
                    ProjectStore.Project project = resolveExistingProject(p.optString("projectId", ""));
                    String clipId = p.getString("clipId");
                    long expectedRevision = titleInteger(p, "expectedRevision", project.revision, 0L, 9007199254740991L);
                    ProjectStore.Project edited = store.edit(project.id, expectedRevision, action, current -> {
                        ProjectStore.Project before = ProjectStore.copy(current);
                        if ("set_motion_path".equals(action)) ProjectMotionPathEdits.set(current, clipId, p.getJSONObject("path"));
                        else ProjectMotionPathEdits.clear(current, clipId);
                        requireChangedAuthoredNativeEffects(before, current);
                    });
                    complete(command, projectEditResult(edited)); syncProtocolState(); return;
                }
                case "set_animation_easing": {
                    ProjectStore.Project project = resolveExistingProject(p.optString("projectId", ""));
                    long expectedRevision = titleInteger(p, "expectedRevision", project.revision, 0L, 9007199254740991L);
                    ProjectStore.Project edited = store.edit(project.id, expectedRevision, action, current -> {
                        ProjectStore.Project before = ProjectStore.copy(current);
                        if (p.has("bezier") && !(p.opt("bezier") instanceof JSONArray)) throw new IllegalArgumentException("Bezier controls must be a four-number array");
                        ProjectMotionPathEdits.setEasing(current, p.getString("clipId"), p.getString("scope"), p.getString("easing"), p.optJSONArray("bezier"));
                        requireChangedAuthoredNativeEffects(before, current);
                    });
                    complete(command, projectEditResult(edited)); syncProtocolState(); return;
                }
                case "archive_source_media":
                case "restore_source_media":
                    complete(command, queueSourceMedia(action, p));
                    return;
                case "source_media_status": {
                    ProjectStore.Project project = store.get(p.optString("projectId", ""));
                    if (project == null) throw new IllegalArgumentException("Select one existing owned source project");
                    requireOriginalSource(project.asset(p.optString("assetId", "")));
                    JSONObject status = sourceMediaVault.localStatus(project.id, p.getString("assetId"));
                    status.put("ok", true).put("projectId", project.id).put("assetId", p.getString("assetId"));
                    complete(command, status);
                    return;
                }
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
                case "redeem_rebind": {
                    final String token = p.optString("token", "").trim();
                    if (token.length() < 30) throw new IllegalArgumentException("Valid MCP rebind token is required");
                    final JSONObject queuedCommand = command;
                    JobManager.Job rebindJob = jobs.submit(
                            "MCP identity rebind",
                            JobManager.Kind.LIGHT,
                            state -> {
                                state.checkpoint("mcp_rebind", 20, "Redeeming legacy stable endpoint");
                                JSONObject result = protocol.redeemRebindNow(token);
                                state.checkpoint("mcp_rebind", 90, "Refreshing canonical Native Agent registration");
                                syncProtocolState();
                                result.put("rebindCompleted", true);
                                result.put("stableMcpPath", McpConnectionCore.STABLE_MCP_PATH);
                                state.setResult(result);
                                ActivityLog.add(this, "system", "MCP identity alias repaired",
                                        "Legacy endpoint now targets this Native Agent",
                                        "success", 100, queuedCommand.optString("id", ""), null);
                                commandJournal.finish(queuedCommand, result, "completed");
                                protocol.complete(queuedCommand, result, "completed");
                            }
                    );
                    ActivityLog.add(this, "chatgpt", "MCP identity rebind queued",
                            "Repairing legacy stable endpoint • job " + shortId(rebindJob.id),
                            "queued", 0, commandId, null);
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
                case "duplicate_project": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    complete(command, projectEditResult(store.duplicateProject(project.id, p.optString("name", project.name + " copy"))));
                    syncProtocolState();
                    return;
                }
                case "save_snapshot": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    complete(command, store.saveSnapshot(project.id, p.optLong("expectedRevision", -1L), p.optString("name", "Checkpoint")));
                    return;
                }
                case "list_snapshots": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    JSONObject result = ok();
                    result.put("snapshots", store.listSnapshots(project.id));
                    complete(command, result);
                    return;
                }
                case "restore_snapshot": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    complete(command, projectEditResult(store.restoreSnapshot(project.id, p.optLong("expectedRevision", -1L), p.optString("snapshotId", ""))));
                    syncProtocolState();
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
                case "roll":
                case "slip":
                case "slide":
                case "timeline_edit": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    JSONObject settings = p.optJSONObject("settings");
                    String operation = "timeline_edit".equals(action) ? p.optString("operation", "") : action;
                    JSONObject editSettings = settings == null ? p : settings;
                    if (settings == null && ("roll".equals(action) || "slip".equals(action) || "slide".equals(action))) {
                        editSettings = new JSONObject();
                        for (String key : new String[]{"clipId", "deltaMs", "edge"}) if (p.has(key)) editSettings.put(key, p.opt(key));
                    }
                    final JSONObject acceptedSettings = editSettings;
                    ProjectStore.Project edited = store.edit(project.id, p.optLong("expectedRevision", -1L),
                            operation, current -> {
                                ProjectStore.Project before = ProjectStore.Project.fromJson(new JSONObject(current.toJson().toString()));
                                ProjectTimeline.apply(current, operation, acceptedSettings);
                                if ("effects".equals(operation) || "properties".equals(operation)
                                        && (acceptedSettings.has("effects") || acceptedSettings.has("title") || acceptedSettings.has("transition")))
                                    requireChangedAuthoredNativeEffects(before, current);
                            });
                    complete(command, projectEditResult(edited));
                    syncProtocolState();
                    return;
                }
                case "marker_add":
                case "marker_update":
                case "marker_delete":
                case "marker_list":
                case "editor_range_set":
                case "editor_range_clear":
                case "editor_range_status": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    JSONObject settings = p.optJSONObject("settings");
                    if (settings == null) {
                        settings = new JSONObject();
                        String[] keys = action.startsWith("marker_")
                                ? new String[]{"markerId", "atMs", "endMs", "name", "color", "note"}
                                : new String[]{"inMs", "outMs", "enabled"};
                        for (String key : keys) if (p.has(key)) settings.put(key, p.opt(key));
                    }
                    final JSONObject acceptedSettings = settings;
                    if (!"marker_list".equals(action) && !"editor_range_status".equals(action)) {
                        project = store.edit(project.id, p.optLong("expectedRevision", -1L), action, current -> {
                            switch (action) {
                                case "marker_add": ProjectMarkers.add(current, acceptedSettings); break;
                                case "marker_update": ProjectMarkers.update(current, acceptedSettings); break;
                                case "marker_delete": ProjectMarkers.remove(current, acceptedSettings); break;
                                case "editor_range_set": ProjectMarkers.setRange(current, acceptedSettings); break;
                                case "editor_range_clear":
                                    if (acceptedSettings.length() != 0) throw new IllegalArgumentException("Clearing the rehearsal range accepts no settings");
                                    ProjectMarkers.clearRange(current); break;
                                default: throw new IllegalArgumentException("Unknown marker or rehearsal-range operation");
                            }
                        });
                        syncProtocolState();
                    }
                    JSONObject result = projectEditResult(project);
                    result.put("markers", ProjectMarkers.list(project));
                    result.put("editorRange", ProjectMarkers.rangeJson(project));
                    result.put("rangeExportSupported", true).put("rangeExportAction", "export_range");
                    complete(command, result);
                    return;
                }
                case "undo":
                case "redo": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    ProjectStore.Project edited = "undo".equals(action)
                            ? store.undo(project.id, p.optLong("expectedRevision", -1L))
                            : store.redo(project.id, p.optLong("expectedRevision", -1L));
                    complete(command, projectEditResult(edited));
                    syncProtocolState();
                    return;
                }
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
                case "export_range":
                    p.put("exportMode", "range");
                    complete(command, queueExport(p));
                    return;
                case "export_project":
                    complete(command, queueExport(p));
                    return;
                case "cancel_job": {
                    String jobId = p.optString("jobId");
                    JSONObject lookup = jobs.get(jobId);
                    JSONObject target = lookup == null ? null : lookup.optJSONObject("job");
                    boolean ownerProtected = target != null && target.optBoolean("ownerInitiated", false);
                    boolean cancelled = !ownerProtected && jobs.cancel(jobId);
                    if (cancelled && jobId.equals(activeRenderJobId)
                            && !activeRenderOwnerInitiated && activeRender != null) activeRender.cancel();
                    if (cancelled) recoveryPlans.cancelByJob(jobId);
                    JSONObject result = ok();
                    result.put("cancelled", cancelled);
                    result.put("ownerJobProtected", ownerProtected);
                    complete(command, result);
                    return;
                }
                case "cancel_all_jobs":
                case "stop_all": {
                    int count = jobs.cancelAutonomous();
                    if (activeRender != null && !activeRenderOwnerInitiated) activeRender.cancel();
                    recoveryPlans.cancelAutonomous();
                    syncProtocolState();
                    JSONObject result = ok();
                    result.put("cancelledJobs", count);
                    complete(command, result);
                    return;
                }
                case "insert_asset_timeline": {
                    ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
                    String assetId = p.optString("assetId", "");
                    if (assetId.isEmpty()) throw new IllegalArgumentException("assetId is required");
                    ProjectStore.Project edited = store.timelineEdit(project.id, p.optLong("expectedRevision", -1L), "insert_asset", p);
                    JSONObject result = projectEditResult(edited);
                    result.put("assetId", assetId);
                    result.put("inserted", true);
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
        ProjectStore.Project target = resolveProject(p.optString("projectId", ""));
        JSONArray clips = p.optJSONArray("clips");
        if (clips == null || clips.length() == 0) throw new IllegalArgumentException("clips are required");
        ProjectStore.Project project = store.edit(target.id, p.optLong("expectedRevision", -1L),
                "Replace timeline", current -> replaceTimeline(current, clips));
        JSONObject result = projectEditResult(project);
        return result;
    }

    private void replaceTimeline(ProjectStore.Project project, JSONArray clips) throws Exception {
        if (clips.length() > 120) throw new IllegalArgumentException("Edit plan exceeds 120 clips");
        ArrayList<ProjectStore.Clip> next = new ArrayList<>();
        for (int i = 0; i < clips.length(); i++) {
            JSONObject raw = clips.getJSONObject(i);
            ProjectStore.Asset asset = project.asset(raw.optString("assetId"));
            if (asset == null) throw new IllegalArgumentException("Unknown asset on clip " + i);
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = raw.optString("id", UUID.randomUUID().toString());
            clip.assetId = asset.id;
            clip.trackId = raw.optString("trackId", project.defaultTrack(asset.mime.startsWith("audio/") ? "audio" : "video").id);
            clip.startMs = raw.has("startMs") ? raw.getLong("startMs") : -1L;
            clip.inMs = raw.has("inMs") ? raw.getLong("inMs") : (long) (raw.optDouble("start", 0) * 1000);
            long duration = asset.mime.startsWith("image/") ? 3000L : asset.durationMs;
            clip.outMs = raw.has("outMs") ? raw.getLong("outMs") : (long) (raw.optDouble("end", duration / 1000d) * 1000);
            clip.speed = (float) raw.optDouble("speed", 1);
            clip.volume = (float) raw.optDouble("volume", 1);
            clip.transition = raw.optString("transition", "none");
            clip.title = raw.optString("title", "");
            JSONObject effects = raw.optJSONObject("effects");
            clip.effects = effects == null ? new JSONObject() : new JSONObject(effects.toString());
            next.add(clip);
        }
        project.clips.clear();
        project.clips.addAll(next);
        requireSupportedNativeEffects(project);
    }

    private JSONObject projectEditResult(ProjectStore.Project project) throws Exception {
        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("revision", project.revision);
        result.put("clipCount", project.clips.size());
        result.put("durationMs", project.outputDurationMs());
        result.put("history", store.historyState(project.id));
        return result;
    }

    private JSONObject applyTool(JSONObject p) throws Exception {
        ProjectStore.Project target = resolveProject(p.optString("projectId", ""));
        final int index = p.optInt("clipIndex", 0);
        final String clipId = p.optString("clipId", "");
        String tool = p.optString("tool", "effect");
        JSONObject suppliedSettings = p.optJSONObject("settings");
        final JSONObject settings = suppliedSettings == null ? new JSONObject() : new JSONObject(suppliedSettings.toString());
        ProjectStore.Project project = store.edit(target.id, p.optLong("expectedRevision", -1L),
                "Apply " + tool, current -> {
        ProjectStore.Clip clip = clipId.isEmpty()
                ? (index < 0 || index >= current.clips.size() ? null : current.clips.get(index))
                : current.clip(clipId);
        if (clip == null) throw new IllegalArgumentException("Clip not found");
        ProjectStore.Track track = current.track(clip.trackId);
        if (track != null && track.locked) throw new IllegalArgumentException("Track is locked");
        switch (tool) {
            case "trim":
                JSONObject trim = new JSONObject(settings.toString());
                trim.put("clipId", clip.id);
                trim.put("ripple", settings.optBoolean("ripple", p.optBoolean("ripple", true)));
                ProjectTimeline.apply(current, "trim", trim);
                break;
            case "speed":
            case "slow_motion":
                JSONObject speed = new JSONObject();
                speed.put("clipId", clip.id);
                speed.put("speed", settings.optDouble("speed", "slow_motion".equals(tool) ? .5 : 1));
                speed.put("ripple", settings.optBoolean("ripple", p.optBoolean("ripple", true)));
                ProjectTimeline.apply(current, "properties", speed);
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
                copyNumeric(settings, clip.effects, "scaleX", "scaleY", "x", "y", "rotation", "opacity");
                break;
            case "audio_duck":
                clip.effects.put("audioDucking", true);
                clip.effects.put("duckLevel", settings.optDouble("level", .32));
                break;
            case "keyframes":
                clip.effects.put("keyframes", settings.optJSONArray("keyframes") == null ? new JSONArray() : settings.optJSONArray("keyframes"));
                break;
            case "effect_stack":
                if (settings.optJSONArray("stack") == null) throw new IllegalArgumentException("An effect stack array is required");
                clip.effects.put("stack", new JSONArray(settings.getJSONArray("stack").toString()));
                break;
            default:
                throw new IllegalArgumentException("Unsupported native edit tool: " + tool);
        }
        java.util.List<String> unsupported = track != null && track.isAudio()
                ? NativeVideoEffects.unsupportedAudio(clip) : NativeVideoEffects.unsupported(clip);
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("Native preview/export does not implement: " + String.join(", ", unsupported));
        });
        JSONObject result = projectEditResult(project);
        result.put("clipIndex", index);
        result.put("tool", tool);
        return result;
    }

    private JSONObject applyCreatorPreset(JSONObject p) throws Exception {
        ProjectStore.Project target = resolveProject(p.optString("projectId", ""));
        final int[] changed = {0};
        ProjectStore.Project project = store.edit(target.id, p.optLong("expectedRevision", -1L),
                "Creator preset", current -> changed[0] = applyPresetToProject(current, p));
        JSONObject result = projectEditResult(project);
        result.put("changedClips", changed[0]);
        result.put("preset", p.optString("preset", "cinematic"));
        return result;
    }

    private int applyPresetToProject(ProjectStore.Project project, JSONObject p) throws Exception {
        if (project.clips.isEmpty()) throw new IllegalArgumentException("No clips");
        String preset = p.optString("preset", "cinematic");
        String motion = p.optString("motion", "");
        String transition = p.optString("transition", "");
        String font = p.optString("font", "");
        boolean all = p.optBoolean("allClips", true);
        int selected = p.optInt("clipIndex", 0);
        String selectedId = p.optString("clipId", "");
        int changed = 0;
        for (int i = 0; i < project.clips.size(); i++) {
            ProjectStore.Clip clip = project.clips.get(i);
            if (!all && !(selectedId.isEmpty() ? i == selected : selectedId.equals(clip.id))) continue;
            ProjectStore.Track track = project.track(clip.trackId);
            if (track != null && track.locked) throw new IllegalArgumentException("Track is locked");
            clip.effects.put("effectPreset", preset);
            if (!motion.isEmpty()) clip.effects.put("motionPreset", motion);
            if (!transition.isEmpty()) clip.transition = transition;
            if (!font.isEmpty()) clip.effects.put("fontFamily", font);
            changed++;
        }
        if (changed == 0) throw new IllegalArgumentException("Clip not found");
        requireSupportedNativeEffects(project);
        return changed;
    }

    private void requireSupportedNativeEffects(ProjectStore.Project project) {
        for (ProjectStore.Clip clip : project.clips) {
            ProjectStore.Track track = project.track(clip.trackId);
            java.util.List<String> unsupported = track != null && track.isAudio()
                    ? NativeVideoEffects.unsupportedAudio(clip) : NativeVideoEffects.unsupported(clip);
            if (!unsupported.isEmpty()) throw new IllegalArgumentException("Clip " + clip.id
                    + " uses unimplemented native preview/export effects: " + String.join(", ", unsupported));
        }
    }

    private void requireChangedAuthoredNativeEffects(ProjectStore.Project before, ProjectStore.Project after) throws Exception {
        for (ProjectStore.Clip clip : after.clips) {
            ProjectStore.Clip old = before.clip(clip.id);
            if (old != null && java.util.Objects.equals(old.title, clip.title) && java.util.Objects.equals(old.transition, clip.transition)
                    && old.effects.toString().equals(clip.effects.toString())) continue;
            ProjectStore.Track track = after.track(clip.trackId);
            java.util.List<String> unsupported = track != null && track.isAudio()
                    ? NativeVideoEffects.unsupportedAudio(clip) : NativeVideoEffects.unsupported(clip);
            if (!unsupported.isEmpty()) throw new IllegalArgumentException("Clip " + clip.id
                    + " uses unimplemented native effects: " + String.join(", ", unsupported));
        }
    }

    private JSONObject nativeAuthoringSettings(JSONObject parameters) throws Exception {
        JSONObject settings = parameters.optJSONObject("settings") == null ? new JSONObject(parameters.toString())
                : new JSONObject(parameters.getJSONObject("settings").toString());
        settings.remove("projectId"); settings.remove("expectedRevision");
        java.util.List<String> internal = new java.util.ArrayList<>();
        java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) { String key = keys.next(); if (key.startsWith("_")) internal.add(key); }
        for (String key : internal) settings.remove(key);
        if (settings.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("Native animation authoring exceeds the 256 KiB request budget");
        return settings;
    }

    private JSONObject autonomousEdit(JSONObject p) throws Exception {
        ProjectStore.Project target = resolveProject(p.optString("projectId", ""));
        ProjectStore.Project project = target;
        if (p.optJSONArray("clips") != null || p.has("preset")) {
            project = store.edit(target.id, p.optLong("expectedRevision", -1L), "Autonomous edit", current -> {
                if (p.optJSONArray("clips") != null) replaceTimeline(current, p.getJSONArray("clips"));
                if (p.has("preset")) applyPresetToProject(current, p);
            });
        }
        JSONObject result = projectEditResult(project);
        if (p.optBoolean("render", false)) {
            JSONObject exportArgs = new JSONObject();
            exportArgs.put("projectId", project.id);
            exportArgs.put("expectedRevision", project.revision);
            exportArgs.put("aspect", p.optString("aspect", "9:16"));
            exportArgs.put("quality", p.optString("quality", "1080p"));
            exportArgs.put("fileName", p.optString("fileName", "VideoStudio_AI_Edit_" + System.currentTimeMillis() + ".mp4"));
            if (p.has("_mcpCommandId")) exportArgs.put("_mcpCommandId", p.optString("_mcpCommandId", ""));
            JSONObject export = queueExport(exportArgs);
            result.put("export", export);
            if (ExecutionTruthPolicy.isDeferredResult(export)) {
                result.put("queued", true);
                result.put("jobId", export.optString("jobId", ""));
                result.put("durableRecovery", export.optBoolean("durableRecovery", true));
                result.put("render", true);
            }
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
            exportParams.put("expectedRevision", project.revision);
            if (p.has("_mcpCommandId")) exportParams.put("_mcpCommandId", p.getString("_mcpCommandId"));
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
                        validateCachedPortraitGenerations(project.id);
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
                                node = creativeNodeStore.startNode(project.id, nodeId);
                                JSONObject skipped = new JSONObject();
                                skipped.put("ok", true);
                                skipped.put("skipped", true);
                                skipped.put("reason", "Critique disabled for this run");
                                skipped.put("evaluatedSourceIdentity", node.getString("sourceIdentity"));
                                skipped.put("evaluationId", node.getString("evaluationId"));
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
                            node = creativeNodeStore.startNode(project.id, nodeId);

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

                                nodeResult.put("evaluatedSourceIdentity", node.getString("sourceIdentity"));
                                nodeResult.put("evaluationId", node.getString("evaluationId"));
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
                                        true,
                                        node.optString("evaluationId", "")
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
            if (analysis == null) throw new IllegalStateException("Portrait rig has no immutable source proof; regenerate its bundle");
            ProjectStore.Project fresh = store.get(project.id);
            portraitMotionAnalyzer.assertSourceFresh(fresh == null ? null : fresh.asset(asset.id), analysis);
            portraitMotionAnalyzer.assertLayerFilesValid(analysis);
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
        final JSONObject publishedAnalysis = layered ? rig.getJSONObject("analysis") : null;
        store.edit(project.id, project.revision, "Creative compositor", current -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (publishedAnalysis != null) NativePortraitMotionAnalyzer.assertRegisteredSource(current.asset(asset.id), publishedAnalysis);
            ProjectStore.Clip target = current.clip(clip.id);
            if (target == null || !asset.id.equals(target.assetId)) throw new IllegalStateException("Creative compositor source clip changed");
            ProjectStore.Track targetTrack = current.track(target.trackId);
            if (targetTrack != null && targetTrack.locked) throw new IllegalStateException("Creative compositor track is locked");
            if (target.inMs != clip.inMs || target.outMs != clip.outMs || target.speed != clip.speed) target.programDurationMs = -1L;
            target.inMs = clip.inMs; target.outMs = clip.outMs; target.speed = clip.speed;
            target.effects = new JSONObject(clip.effects.toString());
            requireSupportedNativeEffects(current);
        });

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

    private void validateCachedPortraitGenerations(String projectId) throws Exception {
        ProjectStore.Project current = store.get(projectId);
        if (current == null) throw new IllegalStateException("Creative source project is unavailable");
        JSONArray nodes = creativeNodeStore.status(projectId).optJSONArray("nodes");
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            JSONObject node = nodes.optJSONObject(i), result = node == null ? null : node.optJSONObject("result");
            if (result == null || !"completed".equals(node.optString("state"))
                    || !(result.optBoolean("portraitBundle", false) || "portrait.rig".equals(node.optString("capability")))) continue;
            try {
                JSONObject analysis = result.optJSONObject("analysis");
                if (analysis == null) throw new IllegalStateException("Cached portrait generation has no source proof");
                portraitMotionAnalyzer.assertSourceFresh(current.asset(result.optString("assetId", "")), analysis);
                portraitMotionAnalyzer.assertLayerFilesValid(analysis);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception stale) {
                String sourceNode = result.optString("sourceBundleNode", node.optString("id", ""));
                creativeNodeStore.invalidate(projectId, sourceNode, true);
                ActivityLog.add(this, "system", "Portrait generation invalidated",
                        stale.getMessage() == null ? "Cached source or layers changed" : stale.getMessage(),
                        "info", null, null, projectId);
            }
        }
    }

    private JSONObject completedCreativeResult(String projectId,
                                               String capability,
                                               String assetId) {
        ProjectStore.Project currentProject = store.get(projectId);
        ProjectStore.Asset currentAsset = currentProject == null ? null : currentProject.asset(assetId);
        long relinkedAt = currentAsset == null || currentAsset.importMetadata == null ? 0L
                : currentAsset.importMetadata.optLong("relinkedAt", 0L);
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
            if (relinkedAt > 0L && node.optLong("completedAt", 0L) < relinkedAt) continue;
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
        if (!planId.isEmpty() && recoveryPlans.get(planId) == null)
            throw new IllegalStateException("The accepted durable work plan no longer exists; fresh admission is required");
        if (planId.isEmpty()) {
            planId = recoveryPlans.begin(action, parameters, projectId);
        } else {
            JSONObject accepted = recoveryPlans.get(planId);
            if (!action.equals(accepted.optString("action")) || !projectId.equals(accepted.optString("projectId")))
                throw new IllegalStateException("Durable work identity does not match this accepted request");
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
        recoveryPlans.attachQueuedJob(durablePlanId, job.id);
        return job;
    }

    private JobManager.Job submitRecoverableHeavy(String action,
                                                  JSONObject parameters,
                                                  String projectId,
                                                  String jobName,
                                                  JobManager.Work work) {
        String requestedPlan = parameters == null ? "" : parameters.optString("_recoveryPlanId", "");
        String planId = requestedPlan;
        if (!planId.isEmpty() && recoveryPlans.get(planId) == null)
            throw new IllegalStateException("The accepted durable work plan no longer exists; fresh admission is required");
        if (planId.isEmpty()) {
            planId = recoveryPlans.begin(action, parameters, projectId);
        } else {
            JSONObject accepted = recoveryPlans.get(planId);
            if (!action.equals(accepted.optString("action")) || !projectId.equals(accepted.optString("projectId")))
                throw new IllegalStateException("Durable work identity does not match this accepted request");
            recoveryPlans.markResuming(planId);
        }

        final String durablePlanId = planId;
        JobManager.Work guardedWork = state -> {
            runningDurableHeavyPlans.add(durablePlanId);
            activeRenderOwnerInitiated = parameters != null && parameters.optBoolean("_ownerInitiated", false);
            try {
                recoveryPlans.attachJob(durablePlanId, state.id);
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
            } finally {
                if (state.id.equals(activeRenderJobId)) {
                    activeRender = null;
                    activeRenderJobId = "";
                }
                activeRenderOwnerInitiated = false;
                runningDurableHeavyPlans.remove(durablePlanId);
                releaseTerminalExportPins();
            }
        };
        JobManager.Job job = parameters != null && parameters.optBoolean("_ownerInitiated", false)
                ? jobs.submitOwnerPriority(jobName, JobManager.Kind.HEAVY, guardedWork)
                : jobs.submit(jobName, JobManager.Kind.HEAVY, guardedWork);
        recoveryPlans.attachQueuedJob(durablePlanId, job.id);
        return job;
    }

    private void recoverDurablePlans() {
        if (recoveryPlans == null || protocol == null) return;
        JSONArray pending = recoveryPlans.pendingForAutoResume();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject plan = pending.optJSONObject(i);
            if (plan == null) continue;
            JSONObject originalParameters = plan.optJSONObject("parameters");
            if (protocol.isControlPaused() && (originalParameters == null || !originalParameters.optBoolean("_ownerInitiated", false))) continue;
            if (runningDurableHeavyPlans.contains(plan.optString("id", ""))) {
                deferredPinnedRecovery = true;
                continue;
            }
            if (originalParameters != null) {
                String pinId = originalParameters.optString("_exportPinId", "");
                String sourcePinId = originalParameters.optString("_sourcePinId", "");
                if (runningExportPins.contains(pinId) || renderingExportPins.contains(pinId)
                        || runningSourcePins.contains(sourcePinId) || runningSourceRequests.contains(originalParameters.optString("requestId", ""))) {
                    deferredPinnedRecovery = true;
                    continue;
                }
            }
            JSONObject runtimeLookup = jobs.get(plan.optString("jobId", ""));
            JSONObject runtimeJob = runtimeLookup == null ? null : runtimeLookup.optJSONObject("job");
            if (runtimeJob != null && !JobManager.isTerminal(runtimeJob.optString("state"))
                    && !JobManager.STATE_CHECKPOINTED.equals(runtimeJob.optString("state"))
                    && !runtimeJob.optBoolean("restartSuspended", false)) continue;
            String planId = plan.optString("id", "");
            String action = plan.optString("action", "");
            String projectId = plan.optString("projectId", "");
            String outputUri = plan.optString("outputUri", "");

            if (!outputUri.isEmpty() && isReadableOutput(outputUri)) {
                if ("run_creative_graph".equals(action)) {
                    // Published media survives restart, but a publication receipt
                    // cannot prove a node evaluated the current source snapshot.
                    // The normal graph recovery path reuses only valid cached
                    // evaluations or runs the invalidated nodes again.
                    ActivityLog.add(this, "system", "Recovered published CreativeIR media",
                            plan.optString("outputName", "Generated video") + " retained • graph recovery will check source evaluations",
                            "info", plan.optInt("progress", 0), null, projectId);
                } else if ("export_project".equals(action)) {
                    // Reattach a normal durable job so native/host watchers get
                    // the actual terminal receipt. queueExport reuses this
                    // publication without rendering its pinned graph again.
                    ActivityLog.add(this, "system", "Recovering pinned published export",
                            plan.optString("outputName", "Generated video") + " • repairing its Media Bin receipt",
                            "info", plan.optInt("progress", 0), null, projectId);
                } else {
                    ActivityLog.add(this, "system", "Recovering published native media",
                            plan.optString("outputName", "Generated video") + " • verifying its durable publication receipt",
                            "info", plan.optInt("progress", 0), null, projectId);
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
                JSONObject retainedPublication = plan.optJSONObject("pendingPublication");
                if (retainedPublication == null) retainedPublication = plan.optJSONObject("publication");
                boolean repairPublication = !"export_project".equals(action) && !"run_creative_graph".equals(action)
                        && (retainedPublication != null && !"allocated".equals(retainedPublication.optString("phase")) || !outputUri.isEmpty());
                if (repairPublication) {
                    queued = queueRecoveredPublication(plan, parameters);
                } else switch (action) {
                    case "animate_images":
                        queued = queueAnimatedImages(parameters);
                        break;
                    case "prompt_video":
                        queued = queuePromptVideo(parameters);
                        break;
                    case "generate_image":
                        queued = queueGenerateImage(parameters);
                        break;
                    case "create_title":
                        queued = queueCreateTitle(parameters);
                        break;
                    case "cel_create":
                    case "cel_update":
                        queued = queueAnimationCel(action, parameters);
                        break;
                    case "generate_voice":
                        queued = queueGenerateVoice(parameters);
                        break;
                    case "export_project":
                        queued = queueExport(parameters);
                        break;
                    case "archive_source_media":
                    case "restore_source_media":
                        queued = queueSourceMedia(action, parameters);
                        break;
                    case "import_chat_file":
                        queued = queuePrivateImport(parameters);
                        break;
                    case "import_attachment":
                        queued = queueDirectAttachmentImport(parameters);
                        break;
                    case "import_url":
                        queued = queueUrlImport(parameters);
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

                String deferredCommandId = parameters.optString("_mcpCommandId", "");
                if (!deferredCommandId.isEmpty() && ExecutionTruthPolicy.isDeferredResult(queued)) {
                    JSONObject inflight = commandJournal.inflight(deferredCommandId);
                    if (inflight != null) {
                        commandJournal.relinkJob(
                                deferredCommandId,
                                queued.optString("jobId", ""),
                                queued.optString("projectId", projectId),
                                queued
                        );
                        JSONObject originalCommand = inflight.optJSONObject("command");
                        if (originalCommand != null) watchDeferredCommand(originalCommand, queued);
                    }
                }
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

    private JSONObject queueRecoveredPublication(JSONObject previous, JSONObject parameters) throws Exception {
        JSONObject publication = previous.optJSONObject("pendingPublication");
        if (publication == null) publication = previous.optJSONObject("publication");
        if (publication == null) throw new IllegalStateException("Legacy native publication has no checksum receipt; its existing output is retained for explicit review");
        final JSONObject retained = new JSONObject(publication.toString());
        final JSONObject nativeEvidence = retained.optJSONObject("nativeRender") == null
                ? previous.optJSONObject("outputEvidence") : retained.optJSONObject("nativeRender");
        if (nativeEvidence == null) throw new IllegalStateException("Native publication is missing the actual renderer evidence");
        String projectId = previous.getString("projectId");
        JSONObject savedProof = retained.getJSONObject("requestProof");
        if (!previous.getString("id").equals(savedProof.getString("recoveryPlanId"))
                || !projectId.equals(savedProof.getString("projectId"))
                || nativeEvidence.getLong("projectRevision") != savedProof.getLong("projectRevision"))
            throw new IllegalStateException("Native publication proof differs from its durable request");
        final JSONObject proof = publicationProof(previous.getString("id"), projectId, nativeEvidence.getLong("projectRevision"),
                null, savedProof.getString("aspect"), savedProof.getString("quality"), savedProof.getString("displayName"),
                nativeEvidence.getInt("animationFrameRate"));
        ProjectStore.Project project = store.get(projectId);
        if (project == null) throw new IllegalStateException("Native output is retained but its project no longer exists");
        JobManager.Job job = submitRecoverableHeavy(previous.getString("action"), parameters, projectId, "Verify retained native publication", state -> {
            checkpoint(state, "Verifying retained native media", "Reading the exact saved MediaStore URI and checksum", 96, projectId);
            JSONObject actual = MediaStoreExportPublisher.resumePending(this, retained, proof, publicationRecorder(state.id, nativeEvidence));
            finishNativePublication(project, proof.getString("quality"), state, actual, nativeEvidence);
            recoveryPlans.markResultForJob(state.id, state.result);
            checkpoint(state, "Verifying retained native media", "Original native output receipt recovered", 100, projectId);
        });
        return ok().put("queued", true).put("jobId", job.id).put("projectId", projectId).put("durableRecovery", true)
                .put("recoveredPublication", true).put("render", true);
    }

    private JSONObject queueDriveProjectOffload(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        String storageTreeUri = pinStorageTree(p, true);

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("storageTreeUri", storageTreeUri);

        JobManager.Job job = submitRecoverableHeavy(
                "offload_project_to_drive",
                durableParameters,
                project.id,
                "Cloud offload • " + project.name,
                state -> {
                    File workspace = creativeWorkspace.projectRoot(project.id);
                    ensureProjectWorkspaceHydrated(project, state);
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
                            ),
                            storageTreeUri
                    );
                    if (!archived.optBoolean("ok", false)) {
                        throw new IllegalStateException("Cloud archive verification did not complete");
                    }
                    jobs.awaitSafeCheckpoint(state, "cloud_offload_commit");
                    checkpoint(state, "Cloud offload", "Archive verified • evicting only cloud-backed intermediates", 90, project.id);
                    JSONObject evicted = creativeWorkspace.evictCloudBackedProject(project.id, archived);
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
        String storageTreeUri = pinStorageTree(p, false);

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("storageTreeUri", storageTreeUri);

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
                            false,
                            (progress, detail) -> checkpoint(
                                    state,
                                    "Cloud restore",
                                    detail,
                                    Math.max(2, Math.min(100, progress)),
                                    project.id
                            ),
                            storageTreeUri
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
        String storageTreeUri = pinStorageTree(p, true);
        String packId = p.optString("id", "").trim();
        if (packId.isEmpty()) throw new IllegalArgumentException("Model pack id is required");
        File pack = modelPackManager.installedDirectory(packId);

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("id", packId);
        durableParameters.put("storageTreeUri", storageTreeUri);

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
                            ),
                            storageTreeUri
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
        String storageTreeUri = pinStorageTree(p, false);
        String packId = p.optString("id", "").trim();
        if (packId.isEmpty()) throw new IllegalArgumentException("Model pack id is required");

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("id", packId);
        durableParameters.put("storageTreeUri", storageTreeUri);

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
                            ),
                            storageTreeUri
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
        String storageTreeUri = pinStorageTree(p, true);

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);
        durableParameters.put("storageTreeUri", storageTreeUri);

        JobManager.Job job = submitRecoverableHeavy(
                "sync_project_to_drive",
                durableParameters,
                project.id,
                "Cloud archive • " + project.name,
                state -> {
                    ensureProjectWorkspaceHydrated(project, state);
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
                            ),
                            storageTreeUri
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

    private String pinStorageTree(JSONObject p, boolean requireWrite) throws Exception {
        String treeUri = p.optString("storageTreeUri", "").trim();
        if (treeUri.isEmpty()) treeUri = driveWorkspace.captureTreeUri();
        // The provider validates persisted owner capability; caller-supplied
        // metadata cannot grant access to an arbitrary document tree.
        driveWorkspace.validateArchiveTree(treeUri, requireWrite);
        return treeUri;
    }

    private JSONObject queueSourceMedia(String action, JSONObject request) throws Exception {
        boolean archive = "archive_source_media".equals(action);
        if (!archive && !"restore_source_media".equals(action)) throw new IllegalArgumentException("Unknown source-media operation");
        exportAdmissionLock.lockInterruptibly();
        try {
            JSONObject previous = request.optString("_recoveryPlanId", "").isEmpty() ? null
                    : recoveryPlans.get(request.getString("_recoveryPlanId"));
            if (!request.optString("_recoveryPlanId", "").isEmpty() && previous == null)
                throw new IllegalStateException("The retained source recovery plan is missing; its admission cannot be replaced");
            if (previous != null && !action.equals(previous.optString("action"))) throw new IllegalArgumentException("Recovery action does not match source request");
            if (previous == null && !request.optString("_mcpCommandId", "").isEmpty())
                previous = recoveryPlans.findByRequest(action, "_mcpCommandId", request.getString("_mcpCommandId"));
            if (previous == null && !request.optString("_manualSourceSession", "").isEmpty())
                previous = recoveryPlans.findByRequest(action, "_manualSourceSession", request.getString("_manualSourceSession"));
            if (previous != null && "cancelled".equals(previous.optString("state")) && request.optBoolean("_resumeSourceRequested", false)) {
                JSONObject saved = previous.getJSONObject("parameters");
                String sessionId = request.optString("_manualSourceSession", "");
                if (sessionId.isEmpty() || !request.optBoolean("_ownerInitiated", false)
                        || !sessionId.equals(saved.optString("_manualSourceSession", "")))
                    throw new IllegalArgumentException("Only the matching owner source session can resume cancelled work");
                if (runningDurableHeavyPlans.contains(previous.getString("id")) || runningSourceRequests.contains(saved.optString("requestId", ""))
                        || runningSourcePins.contains(saved.optString("_sourcePinId", "")))
                    throw new IllegalStateException("The previous transfer is still stopping; resume it after its worker has released the transfer lane");
                previous = recoveryPlans.reviveCancelledSourcePlan(previous.getString("id"), sessionId);
            }
            if (previous != null && JobManager.isTerminal(previous.optString("state"))) {
                JSONObject receipt = previous.optJSONObject("result");
                if (!"completed".equals(previous.optString("state")) || receipt == null
                        || !receipt.optBoolean(archive ? "committed" : "restored", false))
                    throw new IllegalStateException("This source request is terminal; submit a new explicit request");
                JSONObject reused = new JSONObject(receipt.toString());
                reused.put("queued", false).put("reused", true).put("jobId", previous.optString("jobId", ""));
                return reused;
            }
            JSONObject durable;
            ProjectStore.Project accepted;
            if (previous != null) {
                durable = new JSONObject(previous.getJSONObject("parameters").toString());
                durable.put("_recoveryPlanId", previous.getString("id"));
                JSONObject lookup = jobs.get(previous.optString("jobId", ""));
                JSONObject job = lookup == null ? null : lookup.optJSONObject("job");
                if (job != null && !JobManager.isTerminal(job.optString("state"))
                        && !JobManager.STATE_CHECKPOINTED.equals(job.optString("state")) && !job.optBoolean("restartSuspended", false))
                    return queuedSourceMediaResult(durable, previous.optString("jobId", ""));
                if (archive) {
                    accepted = store.readExportPin(durable.getString("_sourcePinId"), durable.getString("_sourcePinOwner"));
                    if (accepted == null) throw new IllegalStateException("The accepted original-source snapshot is missing; it cannot be replaced by the current editor");
                } else {
                    accepted = store.get(durable.getString("projectId"));
                    if (accepted == null) throw new IllegalStateException("Restore project no longer exists");
                }
            } else {
                String projectId = request.optString("projectId", "").trim(), assetId = request.optString("assetId", "").trim();
                if (projectId.isEmpty() || assetId.isEmpty()) throw new IllegalArgumentException("Select one explicit project and registered source asset");
                String requestId = !request.optString("_manualSourceSession", "").isEmpty()
                        ? request.getString("_manualSourceSession") : !request.optString("_mcpCommandId", "").isEmpty()
                        ? UUID.nameUUIDFromBytes(("source:" + action + ":" + request.getString("_mcpCommandId")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()
                        : UUID.randomUUID().toString();
                UUID.fromString(requestId);
                String pinId = archive ? SourceMediaVault.pinId(requestId) : "", owner = archive ? SourceMediaVault.pinOwner(requestId) : "";
                ProjectStore.Project orphan = archive ? store.readExportPin(pinId, owner) : null;
                accepted = orphan == null ? store.get(projectId) : orphan;
                if (accepted == null || !projectId.equals(accepted.id)) throw new IllegalArgumentException("Source project no longer exists or differs from its accepted request");
                ProjectStore.Asset asset = accepted.asset(assetId);
                requireOriginalSource(asset);
                long revision = titleInteger(request, "expectedRevision", accepted.revision, 0L, 9007199254740991L);
                if (revision != accepted.revision) throw new IllegalStateException("Source request revision conflicts with the accepted project");
                String tree = pinStorageTree(request, archive);
                durable = new JSONObject(request.toString()).put("projectId", projectId).put("assetId", assetId)
                        .put("expectedRevision", revision).put("requestId", requestId).put("storageTreeUri", tree);
                if (archive) {
                    if (orphan == null) store.captureSourceArchivePin(projectId, revision, assetId, pinId, owner);
                    accepted = store.readExportPin(pinId, owner);
                    if (accepted == null) throw new IllegalStateException("Accepted original-source snapshot could not be reopened");
                    durable.put("_sourcePinId", pinId).put("_sourcePinOwner", owner);
                } else {
                    String generationId = request.optString("generationId", "");
                    UUID.fromString(generationId);
                    durable.put("generationId", generationId);
                }
                try { durable.put("_recoveryPlanId", recoveryPlans.begin(action, durable, projectId)); }
                catch (Exception error) {
                    if (archive && orphan == null) store.releaseSourceArchivePin(pinId, owner);
                    throw error;
                }
            }
            final JSONObject parameters = durable;
            final ProjectStore.Project snapshot = accepted;
            final String projectId = parameters.getString("projectId"), assetId = parameters.getString("assetId");
            final String requestId = parameters.getString("requestId"), tree = parameters.getString("storageTreeUri");
            final String manualSession = parameters.optString("_manualSourceSession", ""), pinId = parameters.optString("_sourcePinId", "");
            if (archive && (!snapshot.id.equals(projectId) || snapshot.revision != parameters.getLong("expectedRevision")
                    || snapshot.asset(assetId) == null)) throw new IllegalStateException("Original-source snapshot identity does not match the durable request");
            driveWorkspace.validateArchiveTree(tree, archive);
            JobManager.Job job = submitRecoverableHeavy(action, parameters, projectId,
                    archive ? "Archive original source" : "Restore original source", state -> {
                runningSourceRequests.add(requestId);
                if (!pinId.isEmpty()) runningSourcePins.add(pinId);
                String operation = archive ? "archive" : "restore";
                publishSourceMediaStatus(manualSession, projectId, assetId, operation, state.id, "running", "Preparing verified source transfer", 1, null);
                try {
                    JSONObject receipt = recoveryPlans.resultForJob(state.id);
                    SourceMediaVault.Progress progress = (bytes, total, detail) -> {
                        jobs.awaitSafeCheckpoint(state, "source_media_chunk");
                        int percent = total > 0L ? Math.max(1, Math.min(94, (int) (bytes * 94.0 / total))) : 1;
                        checkpoint(state, friendlyAction(action), detail, percent, projectId);
                        publishSourceMediaStatus(manualSession, projectId, assetId, operation, state.id, "running", detail, percent, null);
                    };
                    if (archive) {
                        ProjectStore.Asset source = store.readSourceArchivePin(projectId, assetId, pinId, parameters.getString("_sourcePinOwner"));
                        if (source == null) throw new IllegalStateException("Accepted source-media pin is missing");
                        receipt = sourceMediaVault.archiveAsset(snapshot, source, tree, requestId, progress);
                        receipt.remove("sourceAssetSnapshot");
                        receipt.put("projectRevision", snapshot.revision).put("acceptedSourceSnapshot", true);
                    } else {
                        // A previous immutable restored copy may have been durably
                        // recorded before graph registration. Never recopy it just
                        // because this worker received a new runtime job identity.
                        JSONObject previousReceipt = receipt;
                        receipt = sourceMediaVault.restoreAsset(projectId, assetId, tree, parameters.getString("generationId"), requestId, progress);
                        if (previousReceipt != null && previousReceipt.optBoolean("registeredAsset", false)
                                && receipt.optString("uri").equals(previousReceipt.optString("uri"))
                                && receipt.optString("sourceSha256").equals(previousReceipt.optString("sourceSha256")))
                            for (String key : new String[]{"registeredAsset", "relinked", "nativeRevision", "replacementAssetId", "relinkConflict", "relinkBlockedReason"})
                                if (previousReceipt.has(key)) receipt.put(key, previousReceipt.opt(key));
                        receipt.remove("sourceAssetSnapshot");
                        recoveryPlans.markResultForJob(state.id, receipt);
                        if (!receipt.optBoolean("registeredAsset", false)) receipt = registerRestoredSource(parameters, receipt);
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    receipt.put("requestId", requestId).put("operation", operation).put("originalSourceDeleted", false);
                    state.setResult(receipt);
                    recoveryPlans.markResultForJob(state.id, receipt);
                    checkpoint(state, friendlyAction(action), archive ? "Verified original-source archive committed"
                            : receipt.optBoolean("relinked", false) ? "Verified original source restored and relinked"
                            : "Verified restored copy retained in Media Bin; relink requires owner review", 100, projectId);
                    publishSourceMediaStatus(manualSession, projectId, assetId, operation, state.id, "completed", state.detail, 100, receipt);
                    syncProtocolState();
                } catch (Exception error) {
                    boolean interrupted = error instanceof InterruptedException || state.restartSuspended;
                    String status = JobManager.STATE_CANCELLED.equals(state.state) ? "cancelled" : "checkpointed";
                    publishSourceMediaStatus(manualSession, projectId, assetId, operation, state.id, status,
                            interrupted && !"cancelled".equals(status) ? "Transfer interrupted; verified checkpoint retained for restart"
                                    : error.getMessage() == null ? "Source transfer interrupted" : error.getMessage(), state.progress, state.result);
                    throw error;
                } finally {
                    runningSourceRequests.remove(requestId);
                    if (!pinId.isEmpty()) runningSourcePins.remove(pinId);
                }
            });
            if (!manualSession.isEmpty()) {
                watchedSourceMedia.add(manualSession);
                publishSourceMediaStatus(manualSession, projectId, assetId, archive ? "archive" : "restore", job.id,
                        "queued", "Waiting for the safe native transfer lane", 0, null);
            }
            return queuedSourceMediaResult(parameters, job.id);
        } finally { exportAdmissionLock.unlock(); }
    }

    private static void requireOriginalSource(ProjectStore.Asset asset) {
        if (asset == null || asset.generated || !"source".equals(asset.role) || asset.uri == null || asset.uri.isEmpty())
            throw new IllegalArgumentException("Source archive/restore requires one registered original source asset");
    }

    private JSONObject queuedSourceMediaResult(JSONObject parameters, String jobId) throws Exception {
        return ok().put("queued", true).put("jobId", jobId).put("projectId", parameters.getString("projectId"))
                .put("assetId", parameters.getString("assetId")).put("requestId", parameters.getString("requestId"))
                .put("projectRevision", parameters.getLong("expectedRevision")).put("durableRecovery", true)
                .put("scope", "one-registered-original-source").put("originalSourceDeleted", false);
    }

    private JSONObject registerRestoredSource(JSONObject parameters, JSONObject restored) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String projectId = parameters.getString("projectId"), assetId = parameters.getString("assetId"), uri = restored.getString("uri");
        if (!projectId.equals(restored.getString("projectId")) || !assetId.equals(restored.getString("assetId"))
                || !parameters.getString("generationId").equals(restored.getString("generationId"))
                || !parameters.getString("storageTreeUri").equals(restored.getString("archiveTreeUri")))
            throw new IllegalStateException("Restored source receipt does not match its durable admission");
        ProjectStore.Project current = store.get(projectId);
        if (current == null) throw new IllegalStateException("Verified restored copy is retained, but its native project no longer exists");
        ProjectStore.Asset target = current.asset(assetId);
        if (target != null && uri.equals(target.uri)) return new JSONObject(restored.toString()).put("registeredAsset", true)
                .put("relinked", true).put("nativeRevision", current.revision).put("reused", true);
        ProjectStore.Asset inspected = store.inspectUri(Uri.parse(uri));
        requireOriginalSource(inspected);
        if (inspected.sizeBytes != restored.getLong("verifiedRestoredBytes")) throw new IllegalStateException("Restored source size differs from its verified receipt");
        if (inspected.importMetadata == null) inspected.importMetadata = new JSONObject();
        inspected.importMetadata.put("sourceArchive", new JSONObject(restored.toString()));
        String conflict;
        try {
            ProjectStore.Project relinked = store.edit(projectId, parameters.getLong("expectedRevision"), "Restore original source", latest -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                ProjectAssetRelinking.apply(latest, assetId, inspected, true);
            });
            return new JSONObject(restored.toString()).put("registeredAsset", true).put("relinked", true)
                    .put("nativeRevision", relinked.revision).put("replacementAssetId", assetId);
        } catch (Exception blocked) {
            if (Thread.currentThread().isInterrupted() || blocked instanceof InterruptedException) throw new InterruptedException();
            conflict = blocked.getMessage() == null ? "Native source relink was blocked" : blocked.getMessage();
        }
        // The copied bytes are real and verified even when the accepted editor
        // revision or portrait/track protection prevents replacement. Persist
        // them as an owned source, without manufacturing a timeline clip.
        inspected.id = UUID.nameUUIDFromBytes(("restored-source:" + uri).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        inspected.name = restored.optString("sourceName", inspected.name) + " (restored)";
        ProjectStore.Project registered = store.edit(projectId, -1L, "Register verified restored source", latest -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ProjectStore.Asset existing = latest.asset(inspected.id);
            if (existing == null) latest.assets.add(inspected);
            else if (!uri.equals(existing.uri)) throw new IllegalStateException("Restored source identity is already owned by different media");
        });
        return new JSONObject(restored.toString()).put("registeredAsset", true).put("relinked", false).put("relinkConflict", true)
                .put("relinkBlockedReason", conflict).put("replacementAssetId", inspected.id).put("nativeRevision", registered.revision);
    }

    private JSONObject queueAnimationCel(String action, JSONObject request) throws Exception {
        if (!"cel_create".equals(action) && !"cel_update".equals(action))
            throw new IllegalArgumentException("Unknown cel drawing operation");
        JSONObject durable = new JSONObject(request.toString());
        String recoveryId = durable.optString("_recoveryPlanId", "");
        if (!recoveryId.isEmpty()) {
            JSONObject accepted = recoveryPlans.get(recoveryId);
            if (accepted == null || !action.equals(accepted.optString("action")))
                throw new IllegalStateException("The accepted cel work plan is missing or belongs to another operation");
            durable = new JSONObject(accepted.getJSONObject("parameters").toString());
            durable.put("_recoveryPlanId", recoveryId);
        }
        ProjectStore.Project project = resolveExistingProject(durable.optString("projectId", ""));
        long expectedRevision = titleInteger(durable, "expectedRevision", project.revision, 0L, 9007199254740991L);
        JSONObject drawing = AnimationCelFactory.validateDrawing(durable.getJSONObject("drawing"));
        JSONObject exposure = durable.has("exposure") ? new JSONObject(durable.getJSONObject("exposure").toString())
                : "cel_create".equals(action) ? new JSONObject() : null;
        String clipId = "cel_update".equals(action) ? durable.getString("clipId") : "";
        String stableRequestKey = durable.optString("_mcpCommandId", "");
        if (stableRequestKey.isEmpty()) {
            stableRequestKey = "native-cel:" + UUID.randomUUID();
            durable.put("_mcpCommandId", stableRequestKey);
        }
        durable.put("projectId", project.id).put("expectedRevision", expectedRevision).put("drawing", drawing);
        if ("cel_create".equals(action)) durable.put("exposure", exposure);
        final JSONObject parameters = durable;
        final String requestKey = stableRequestKey;
        JobManager.Job job = submitRecoverableLight(action, parameters, project.id, "Create animation cel", state -> {
            if (protocol.isControlPaused() || !isAllowed(action, parameters))
                throw new IllegalStateException("Cel drawing is blocked by pause or the owner's permission scope");
            checkpoint(state, "Animation cel", "Rasterizing immutable owned paint strokes", 10, project.id);
            AnimationCelFactory factory = new AnimationCelFactory(this, store);
            AnimationCelFactory.Result created = "cel_create".equals(action)
                    ? factory.create(project.id, expectedRevision, drawing, exposure, requestKey)
                    : factory.update(project.id, expectedRevision, clipId, drawing, exposure, requestKey);
            ProjectStore.Clip committedClip = created.project.clip(created.clipId);
            ProjectStore.Asset committedAsset = created.project.asset(created.assetId);
            JSONObject cel = committedAsset == null ? null : committedAsset.importMetadata.optJSONObject("animationCel");
            if (committedClip == null || !created.assetId.equals(committedClip.assetId) || cel == null
                    || !requestKey.equals(cel.optString("requestKey")) || !isReadableOutput(committedAsset.uri))
                throw new IllegalStateException("Cel publication did not commit its actual immutable PNG and exposure");
            JSONObject completed = ok().put("projectId", created.project.id).put("revision", created.project.revision)
                    .put("assetId", created.assetId).put("clipId", created.clipId).put("reused", created.reused)
                    .put("renderer", "native_vector_strokes_to_png").put("pngSha256", cel.getString("pngSha256"))
                    .put("documentSha256", cel.getString("documentSha256"));
            recoveryPlans.markResultForJob(state.id, completed);
            state.setResult(completed);
            syncProtocolState();
            checkpoint(state, "Animation cel", created.reused ? "Committed cel recovered" : "Immutable cel and exposure committed", 100, project.id);
        });
        return ok().put("queued", true).put("jobId", job.id).put("projectId", project.id)
                .put("expectedRevision", expectedRevision).put("durableRecovery", true);
    }

    private JSONObject queueCreateTitle(JSONObject p) throws Exception {
        Object rawText = p.opt("text");
        if (!(rawText instanceof String)) throw new IllegalArgumentException("Title text is required");
        String text = ((String) rawText).trim();
        if (text.isEmpty() || text.length() > 2000) throw new IllegalArgumentException("Enter 1 to 2000 title characters");
        long startMs = titleInteger(p, "startMs", 0L, 0L, 9007199254740991L);
        long durationMs = titleInteger(p, "durationMs", 3000L, 100L, 3600000L);
        ProjectTimeline.safeAdd(startMs, durationMs);
        if (p.has("style") && !(p.opt("style") instanceof JSONObject))
            throw new IllegalArgumentException("Title style must be a settings object");
        JSONObject style = p.has("style") ? new JSONObject(p.getJSONObject("style").toString()) : new JSONObject();
        if (style.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw new IllegalArgumentException("Title style exceeds the 32 KB authoring limit");
        String requestedId = p.optString("projectId", "").trim();
        ProjectStore.Project project = requestedId.isEmpty() ? resolveProject("") : store.get(requestedId);
        if (project == null) throw new IllegalArgumentException("Title project no longer exists");
        long expectedRevision = titleInteger(p, "expectedRevision", project.revision, 0L, 9007199254740991L);
        JSONObject durable = new JSONObject(p.toString());
        durable.put("projectId", project.id);
        durable.put("expectedRevision", expectedRevision);
        durable.put("text", text);
        durable.put("startMs", startMs);
        durable.put("durationMs", durationMs);
        durable.put("style", style);
        // Only trusted local command/recovery metadata reaches this point. Persist
        // the key before queue admission so restart never allocates another title.
        String requestKey = durable.optString("_mcpCommandId", "");
        if (requestKey.isEmpty()) {
            requestKey = "native-title:" + UUID.randomUUID();
            durable.put("_mcpCommandId", requestKey);
        }
        final String stableRequestKey = requestKey;
        JobManager.Job job = submitRecoverableLight("create_title", durable, project.id, "Create editable title", state -> {
            if (protocol.isControlPaused() || !isAllowed("create_title", durable))
                throw new IllegalStateException("Title creation is blocked by pause or owner permission scope");
            checkpoint(state, "Title", "Creating owned canvas and revision-checked editable glyphs", 10, project.id);
            EditorTextFactory.Result created = EditorTextFactory.create(this, store, project.id,
                    expectedRevision, startMs, durationMs, text, style, stableRequestKey);
            ProjectStore.Clip clip = created.project.clip(created.clipId);
            ProjectStore.Asset asset = clip == null ? null : created.project.asset(clip.assetId);
            if (clip == null || asset == null || !isReadableOutput(asset.uri))
                throw new IllegalStateException("Title creation did not commit a readable owned canvas and clip");
            JSONObject completed = ok();
            completed.put("projectId", created.project.id);
            completed.put("revision", created.project.revision);
            completed.put("clipId", clip.id);
            completed.put("assetId", asset.id);
            completed.put("reused", created.reused);
            completed.put("editableGlyphRenderer", "native_text_overlay");
            state.setResult(completed);
            syncProtocolState();
            checkpoint(state, "Title", created.reused ? "Previously committed title recovered" : "Editable title committed", 100, project.id);
        });
        JSONObject queued = ok();
        queued.put("queued", true);
        queued.put("jobId", job.id);
        queued.put("projectId", project.id);
        queued.put("expectedRevision", expectedRevision);
        queued.put("durableRecovery", true);
        return queued;
    }

    private long titleInteger(JSONObject parameters, String key, long fallback, long minimum, long maximum) {
        if (!parameters.has(key)) return fallback;
        Object raw = parameters.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(key + " is outside the supported integer range");
        return ((Number) raw).longValue();
    }

    private JSONObject queueMetadataMirror(String action, JSONObject p) throws Exception {
        if (!"metadata_mirror_sync".equals(action) && !"metadata_mirror_apply".equals(action)
                && !"metadata_mirror_retry".equals(action) && !"metadata_mirror_status".equals(action)
                && !"metadata_mirror_revoke".equals(action) && !"metadata_mirror_keep_native".equals(action))
            throw new IllegalArgumentException("Unsupported metadata mirror operation");
        String projectId = p.optString("projectId", "").trim();
        if (projectId.isEmpty() || store.get(projectId) == null) throw new IllegalArgumentException("Existing projectId is required");
        if (!"metadata_mirror_status".equals(action)
                && (protocol.isControlPaused() || "one_file".equals(prefs.getString(KEY_MODE, "everything"))))
            throw new IllegalStateException("Metadata graph effects are blocked by pause or One File Lock");
        JobManager.Work work = state -> {
            checkpoint(state, "Project metadata mirror", "Reading revision-checked project metadata", 5, projectId);
            JSONObject result;
            if (!"metadata_mirror_status".equals(action)
                    && (protocol.isControlPaused() || "one_file".equals(prefs.getString(KEY_MODE, "everything"))))
                throw new IllegalStateException("Metadata graph effects are blocked by pause or One File Lock");
            switch (action) {
                case "metadata_mirror_sync": result = metadataMirror.sync(p); break;
                case "metadata_mirror_apply": result = metadataMirror.apply(p); break;
                case "metadata_mirror_retry": result = metadataMirror.retry(p); break;
                case "metadata_mirror_revoke": result = metadataMirror.revoke(p); break;
                case "metadata_mirror_keep_native": result = metadataMirror.keepNative(p); break;
                default: result = metadataMirror.status(projectId);
            }
            boolean provenPendingApply = "metadata_mirror_apply".equals(action)
                    && result.optBoolean("pendingAcknowledgement", false)
                    && result.optBoolean("nativeMatchesPending", false)
                    && (result.optBoolean("nativeApplied", false) || result.optBoolean("nativeUnchanged", false));
            if (!result.optBoolean("ok", false) && !provenPendingApply) {
                state.setResult(result);
                throw new IllegalStateException(result.optString("error", result.optString("reason", "Metadata mirror operation failed")));
            }
            state.setResult(result);
            checkpoint(state, "Project metadata mirror", result.optBoolean("pendingAcknowledgement", false)
                    ? "Native metadata retained • cloud acknowledgement requires explicit retry" : "Metadata operation complete", 100, projectId);
            syncProtocolState();
        };
        // These operations require deliberate retries; prepared native commits
        // are recovered from their own pending marker rather than auto-applied.
        JobManager.Job job = p.optBoolean("_ownerInitiated", false)
                ? jobs.submitOwnerPriority("Project metadata mirror", JobManager.Kind.LIGHT, work)
                : jobs.submit("Project metadata mirror", JobManager.Kind.LIGHT, work);
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", projectId);
        result.put("executorAvailable", false);
        result.put("mediaUploaded", false);
        result.put("automaticRetry", false);
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
        requireSupportedNativeEffects(project);

        ArrayList<ProjectStore.Clip> imageClips = new ArrayList<>();
        for (ProjectStore.Clip clip : project.clips) {
            ProjectStore.Asset asset = project.asset(clip.assetId);
            if (asset != null && asset.mime != null && asset.mime.startsWith("image/")) {
                if (clip.linkGroupId != null && !clip.linkGroupId.isEmpty())
                    throw new IllegalArgumentException("Unlink image clips through the native timeline before rebuilding their portrait duration");
                imageClips.add(clip);
            }
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
            java.util.Map<String, JSONObject> sourceProofs = new java.util.LinkedHashMap<>();
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
                ProjectStore.Project fresh = store.get(project.id);
                portraitMotionAnalyzer.assertSourceFresh(fresh == null ? null : fresh.asset(asset.id), layers.analysis);
                portraitMotionAnalyzer.assertLayerFilesValid(layers.analysis);
                sourceProofs.put(asset.id, layers.analysis);

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

            ProjectStore.Project latest = store.get(project.id);
            for (java.util.Map.Entry<String, JSONObject> proof : sourceProofs.entrySet()) {
                portraitMotionAnalyzer.assertSourceFresh(latest == null ? null : latest.asset(proof.getKey()), proof.getValue());
                portraitMotionAnalyzer.assertLayerFilesValid(proof.getValue());
            }
            ProjectStore.Project prepared = store.edit(project.id, project.revision, "Prepare immutable portrait animation", current -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                for (java.util.Map.Entry<String, JSONObject> proof : sourceProofs.entrySet())
                    NativePortraitMotionAnalyzer.assertRegisteredSource(current.asset(proof.getKey()), proof.getValue());
                for (ProjectStore.Clip original : current.clips) {
                    ProjectStore.Track track = current.track(original.trackId);
                    if (track != null && track.locked) {
                        ProjectStore.Clip planned = project.clip(original.id);
                        if (planned == null || !original.toJson().toString().equals(planned.toJson().toString()))
                            throw new IllegalStateException("Portrait animation track is locked");
                    }
                }
                current.clips.clear(); current.clips.addAll(project.clips);
                requireSupportedNativeEffects(current);
            });
            syncProtocolState();
            checkpoint(state, "AI motion director", "Layered motion timeline ready", 62, project.id);

            if (render) {
                checkpoint(state, "Rendering animated video", "Starting layered Media3 composition", 65, project.id);
                jobs.awaitSafeCheckpoint(state, "layered_render");
                runExportBlocking(prepared, aspect, quality, fileName, state);
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
        JobManager.Job job = submitRecoverableLight("generate_image", parameters, project.id, "Generate procedural image", state -> {
            String attemptId = UUID.randomUUID().toString();
            File dir = new File(creativeWorkspace.projectRoot(project.id), "generated/images/" + attemptId);
            if (!dir.mkdirs()) throw new IllegalStateException("Could not create immutable image generation workspace");
            File file = new File(dir, "scene_" + attemptId + ".png");
            checkpoint(state, "Image generation", "Rendering original local geometry", 10, project.id);
            PromptVideoEngine.renderProceduralImage(file, width, height, scene);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
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
        exportAdmissionLock.lockInterruptibly();
        try {
            JSONObject prior = null;
            String requestedPlanId = p.optString("_recoveryPlanId", "");
            if (!requestedPlanId.isEmpty()) {
                prior = recoveryPlans.get(requestedPlanId);
                if (prior == null || !"export_project".equals(prior.optString("action")))
                    throw new IllegalStateException("The original durable export plan is unavailable; submit a new export explicitly");
            } else if (!p.optString("_mcpCommandId", "").isEmpty()) {
                prior = recoveryPlans.findByRequest("export_project", "_mcpCommandId", p.getString("_mcpCommandId"));
            } else if (!p.optString("_manualExportSession", "").isEmpty()) {
                prior = recoveryPlans.findByRequest("export_project", "_manualExportSession", p.getString("_manualExportSession"));
            }

            JSONObject durableParameters;
            ProjectStore.Project project;
            if (prior != null) {
                durableParameters = prior.optJSONObject("parameters");
                if (durableParameters == null || durableParameters.optString("_exportPinId", "").isEmpty()
                        || durableParameters.optString("_exportPinOwner", "").isEmpty())
                    throw new IllegalStateException("This older export has no immutable graph snapshot; submit a new export explicitly");
                durableParameters = new JSONObject(durableParameters.toString());
                if (JobManager.isTerminal(prior.optString("state"))) return completedPinnedExport(prior);
                project = openPinnedExport(prior);
                String priorJobId = prior.optString("jobId", "");
                JSONObject lookup = jobs.get(priorJobId);
                JSONObject priorJob = lookup == null ? null : lookup.optJSONObject("job");
                if (priorJob != null && !JobManager.isTerminal(priorJob.optString("state"))
                        && !JobManager.STATE_CHECKPOINTED.equals(priorJob.optString("state"))
                        && !priorJob.optBoolean("restartSuspended", false))
                    return pinnedExportQueuedResult(project, durableParameters, priorJobId);
                durableParameters.put("_recoveryPlanId", prior.getString("id"));
            } else {
                String explicitProjectId = p.optString("projectId", "");
                String owner = !p.optString("_mcpCommandId", "").isEmpty()
                        ? "mcp:" + p.getString("_mcpCommandId")
                        : !p.optString("_manualExportSession", "").isEmpty()
                        ? "local:" + p.getString("_manualExportSession") : "admission:" + UUID.randomUUID();
                String pinId = UUID.nameUUIDFromBytes(("export:" + owner).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                ProjectStore.Project orphan = store.readExportPin(pinId, owner);
                if (orphan != null) {
                    // Process death can occur between the SQLite pin and the
                    // durable plan commit. A replay of the same trusted request
                    // recovers that exact graph rather than the changed editor.
                    if ((!explicitProjectId.isEmpty() && !explicitProjectId.equals(orphan.id))
                            || titleInteger(p, "expectedRevision", orphan.revision, 0L, 9007199254740991L) != orphan.revision)
                        throw new IllegalStateException("The export request already owns a different accepted snapshot");
                    project = orphan;
                } else {
                    if (!explicitProjectId.isEmpty() && store.get(explicitProjectId) == null)
                        throw new IllegalArgumentException("Export project no longer exists");
                    ProjectStore.Project accepted = resolveProject(explicitProjectId);
                    long expectedRevision = titleInteger(p, "expectedRevision", accepted.revision, 0L, 9007199254740991L);
                    project = store.captureExportPin(accepted.id, expectedRevision, pinId, owner);
                }
                durableParameters = new JSONObject(p.toString());
                durableParameters.put("projectId", project.id);
                durableParameters.put("expectedRevision", project.revision);
                durableParameters.put("_exportPinId", pinId);
                durableParameters.put("_exportPinOwner", owner);
                durableParameters.put("_exportPinnedRevision", project.revision);
                durableParameters.put("aspect", p.optString("aspect", "9:16"));
                durableParameters.put("quality", p.optString("quality", "1080p"));
                durableParameters.put("fileName", sanitizeFileName(p.optString("fileName", "VideoStudio_" + System.currentTimeMillis() + ".mp4")));
                try {
                    int acceptedFrameRate = NativeRenderEngine.frameRate(project);
                    if (titleInteger(p, "frameRate", acceptedFrameRate, 1L, 60L) != acceptedFrameRate)
                        throw new IllegalArgumentException("Export frameRate must match the accepted project cadence; change project cadence explicitly before export");
                    durableParameters.put("frameRate", acceptedFrameRate);
                    if (project.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
                    ProjectRangeExport.Prepared range = acceptedExportRange(project, durableParameters);
                    requireSupportedNativeEffects(range == null ? project : range.project());
                    String planId = recoveryPlans.begin("export_project", durableParameters, project.id);
                    durableParameters.put("_recoveryPlanId", planId);
                } catch (Exception admissionError) {
                    // No accepted durable plan owns this snapshot yet.
                    if (orphan == null) store.releaseExportPin(pinId, owner);
                    throw admissionError;
                }
            }
            if (project.clips.isEmpty()) throw new IllegalArgumentException("Pinned timeline is empty");
            if (titleInteger(durableParameters, "frameRate", NativeRenderEngine.frameRate(project), 1L, 60L) != NativeRenderEngine.frameRate(project))
                throw new IllegalStateException("Durable export cadence differs from its immutable accepted graph");
            final ProjectRangeExport.Prepared exportRange = acceptedExportRange(project, durableParameters);
            requireSupportedNativeEffects(exportRange == null ? project : exportRange.project());
            final String aspect = durableParameters.optString("aspect", "9:16");
            final String quality = durableParameters.optString("quality", "1080p");
            final String manualSession = durableParameters.optString("_manualExportSession", "");
            final String pinId = durableParameters.getString("_exportPinId");
            final String fileName = durableParameters.getString("fileName");
            final boolean ownerInitiated = durableParameters.optBoolean("_ownerInitiated", false);
            JobManager.Job job = submitRecoverableHeavy(
                "export_project",
                durableParameters,
                project.id,
                "Export • " + project.name,
                state -> {
            runningExportPins.add(pinId);
            exportPinsByJob.put(state.id, pinId);
            publishManualExportStatus(manualSession, project.id, state.id, "running", "Preparing native export", 2);
            try {
                JSONObject acceptedResult = ok();
                acceptedResult.put("exportSnapshotId", pinId);
                acceptedResult.put("projectRevision", project.revision);
                acceptedResult.put("sourceGraphPinned", true);
                state.setResult(acceptedResult);
                activeRenderOwnerInitiated = ownerInitiated;
                checkpoint(state, "Exporting video", "Preparing protected native export", 2, project.id);
                runExportBlocking(project, aspect, quality, fileName, state, exportRange);
                recoveryPlans.markResultForJob(state.id, state.result);
                checkpoint(state, "Exporting video", "Export complete", 100, project.id);
                publishManualExportStatus(manualSession, project.id, state.id, "completed", "Export complete", 100);
            } catch (Exception error) {
                boolean interrupted = error instanceof InterruptedException || state.restartSuspended;
                boolean explicitCancel = JobManager.STATE_CANCELLED.equals(state.state);
                publishManualExportStatus(manualSession, project.id, state.id,
                        interrupted ? (explicitCancel ? "cancelled" : "checkpointed") : "failed",
                        interrupted && !explicitCancel ? "Export interrupted • recoverable work retained for restart"
                                : error.getMessage() == null ? "Export interrupted" : error.getMessage(), state.progress);
                throw error;
            } finally {
                exportPinsByJob.remove(state.id);
                runningExportPins.remove(pinId);
            }
        });
        publishManualExportStatus(manualSession, project.id, job.id, "queued", "Waiting for the safe render lane", 0);
        if (!manualSession.isEmpty()) watchedManualExports.add(manualSession);
        return pinnedExportQueuedResult(project, durableParameters, job.id);
        } finally {
            exportAdmissionLock.unlock();
        }
    }

    private ProjectStore.Project openPinnedExport(JSONObject plan) throws Exception {
        JSONObject parameters = plan.getJSONObject("parameters");
        ProjectStore.Project project = store.readExportPin(parameters.getString("_exportPinId"), parameters.getString("_exportPinOwner"));
        if (project == null || !plan.getString("projectId").equals(project.id)
                || !parameters.getString("projectId").equals(project.id)
                || parameters.getLong("_exportPinnedRevision") != project.revision)
            throw new IllegalStateException("The accepted export snapshot is unavailable or does not match its durable plan");
        return project;
    }

    private JSONObject pinnedExportQueuedResult(ProjectStore.Project project, JSONObject parameters, String jobId) throws Exception {
        JSONObject result = ok(); result.put("queued", true); result.put("jobId", jobId);
        result.put("durableRecovery", true); result.put("projectId", project.id);
        result.put("fileName", parameters.getString("fileName")); result.put("projectRevision", project.revision);
        result.put("frameRate", NativeRenderEngine.frameRate(project));
        result.put("exportSnapshotId", parameters.getString("_exportPinId")); result.put("sourceGraphPinned", true);
        result.put("ownerInitiated", parameters.optBoolean("_ownerInitiated", false));
        ProjectRangeExport.Prepared range = acceptedExportRange(project, parameters);
        result.put("exportMode", range == null ? "full" : "range");
        if (range != null) result.put("exportRange", range.metadata());
        result.put("sessionId", parameters.optString("_manualExportSession", "")); return result;
    }

    private ProjectRangeExport.Prepared acceptedExportRange(ProjectStore.Project source, JSONObject parameters) throws Exception {
        String mode = parameters.optString("exportMode", "full");
        if (!"full".equals(mode) && !"range".equals(mode)) throw new IllegalArgumentException("Choose full or range export explicitly");
        if ("full".equals(mode)) {
            if (parameters.has("inMs") || parameters.has("outMs")) throw new IllegalArgumentException("Use export_range or exportMode=range with explicit In and Out");
            return null;
        }
        if (!parameters.has("inMs") || !parameters.has("outMs")) throw new IllegalArgumentException("Range export requires explicit accepted In and Out; editor selection is never inferred");
        long inMs = titleInteger(parameters, "inMs", -1L, 0L, 9007199254740991L);
        long outMs = titleInteger(parameters, "outMs", -1L, 1L, 9007199254740991L);
        return ProjectRangeExport.prepare(source, source.revision, inMs, outMs);
    }

    private JSONObject completedPinnedExport(JSONObject plan) throws Exception {
        if (!"completed".equals(plan.optString("state")) || !isReadableOutput(plan.optString("outputUri", "")))
            throw new IllegalStateException("This export request already ended; submit a new export explicitly");
        JSONObject parameters = plan.getJSONObject("parameters");
        JSONObject publication = plan.optJSONObject("publication");
        if (publication == null) throw new IllegalStateException("This legacy export has no durable publication checksum receipt; it cannot be verified by replay");
        JSONObject proof = publicationProof(plan.getString("id"), plan.getString("projectId"), parameters.getLong("_exportPinnedRevision"),
                parameters.getString("_exportPinId"), parameters.optString("aspect", "9:16"), parameters.optString("quality", "1080p"), parameters.getString("fileName"),
                parameters.getInt("frameRate"));
        addExportRangeProof(proof, parameters);
        MediaStoreExportPublisher.resumePending(this, publication, proof, new MediaStoreExportPublisher.ReceiptRecorder() {
            @Override public void persistAllocated(JSONObject receipt) { throw new IllegalStateException("A terminal export cannot allocate another row"); }
            @Override public void persistPending(JSONObject receipt) { throw new IllegalStateException("A terminal export cannot change visibility"); }
            @Override public void persistPublished(JSONObject receipt) {
                if (!plan.optString("outputUri").equals(receipt.optString("uri"))) throw new IllegalStateException("Terminal export URI changed");
            }
        });
        JSONObject result = plan.optJSONObject("result") == null ? ok() : new JSONObject(plan.getJSONObject("result").toString());
        result.put("queued", false); result.put("reused", true); result.put("projectId", plan.getString("projectId"));
        result.put("projectRevision", parameters.getLong("_exportPinnedRevision"));
        result.put("exportSnapshotId", parameters.getString("_exportPinId")); result.put("sourceGraphPinned", true);
        result.put("outputUri", plan.getString("outputUri")); result.put("outputName", plan.optString("outputName", ""));
        if (plan.optJSONObject("outputEvidence") != null) result.put("nativeRender", new JSONObject(plan.getJSONObject("outputEvidence").toString()));
        return result;
    }

    private void releaseTerminalExportPins() {
        if (store == null || recoveryPlans == null || !exportAdmissionLock.tryLock()) return;
        try {
            JSONArray plans = recoveryPlans.allPlans();
            java.util.Map<String, JSONObject> owners = new java.util.HashMap<>();
            for (int i = 0; i < plans.length(); i++) {
                JSONObject plan = plans.optJSONObject(i), parameters = plan == null ? null : plan.optJSONObject("parameters");
                if (parameters != null && "export_project".equals(plan.optString("action")))
                    owners.put(parameters.optString("_exportPinId", ""), plan);
                else if (parameters != null && "archive_source_media".equals(plan.optString("action")))
                    owners.put(parameters.optString("_sourcePinId", ""), plan);
            }
            java.util.Map<String, String> releasable = new java.util.HashMap<>();
            JSONArray pins = store.exportPins();
            for (int i = 0; i < pins.length(); i++) {
                JSONObject pin = pins.optJSONObject(i);
                if (pin == null) continue;
                String pinId = pin.optString("pinId", ""), ownerId = pin.optString("ownerId", "");
                if (pinId.isEmpty() || ownerId.isEmpty() || runningExportPins.contains(pinId) || renderingExportPins.contains(pinId)
                        || runningSourcePins.contains(pinId)) continue;
                JSONObject plan = owners.get(pinId);
                if (plan == null) {
                    // An absent ledger cannot prove an admission was terminal.
                    // Keep uncertain/orphan pins for exact trusted replay;
                    // neither age nor terminal history limits authorize release.
                    continue;
                }
                JSONObject parameters = plan.optJSONObject("parameters");
                boolean source = "archive_source_media".equals(plan.optString("action"));
                if (parameters == null || !ownerId.equals(parameters.optString(source ? "_sourcePinOwner" : "_exportPinOwner", ""))
                        || !JobManager.isTerminal(plan.optString("state"))) continue;
                if (!source && !"completed".equals(plan.optString("state"))) {
                    JSONObject pendingPublication = plan.optJSONObject("pendingPublication");
                    if (pendingPublication != null && !MediaStoreExportPublisher.discardOwnedPending(this, pendingPublication)) continue;
                }
                if ("completed".equals(plan.optString("state"))) {
                    if (source) {
                        JSONObject receipt = plan.optJSONObject("result");
                        if (receipt == null || !receipt.optBoolean("committed", false)
                                || !pinId.equals(receipt.optString("pinId", "")) || !ownerId.equals(receipt.optString("pinOwner", ""))
                                || !parameters.optString("projectId", "").equals(receipt.optString("projectId", ""))
                                || !parameters.optString("assetId", "").equals(receipt.optString("assetId", ""))
                                || !parameters.optString("storageTreeUri", "").equals(receipt.optString("archiveTreeUri", ""))
                                || receipt.optLong("projectRevision", -1L) != pin.optLong("revision", -2L)
                                || receipt.optLong("sourceBytes", 0L) <= 0L || !receipt.optString("sourceSha256", "").matches("[a-f0-9]{64}")) continue;
                        // The source vault commits its independent ownership
                        // catalog before returning this receipt; it retains the
                        // original URI after the active admission pin is released.
                        releasable.put(pinId, ownerId);
                        continue;
                    }
                    String outputUri = plan.optString("outputUri", "");
                    JSONObject evidence = plan.optJSONObject("outputEvidence");
                    if (outputUri.isEmpty() || evidence == null || !pinId.equals(evidence.optString("exportSnapshotId", ""))
                            || evidence.optLong("projectRevision", -1L) != pin.optLong("revision", -2L)) continue;
                    ProjectStore.Project live = store.get(pin.optString("projectId", ""));
                    boolean registered = live == null;
                    if (live != null) for (ProjectStore.Asset asset : live.assets) if (outputUri.equals(asset.uri) && asset.generated) { registered = true; break; }
                    if (!registered) continue;
                }
                // Cancelling an unfinished source upload stops automatic work;
                // its original admission and chunk journal remain available to
                // a later explicit owner resume, even after native relinking.
                if (source && parameters.optBoolean("_ownerInitiated", false) && !"completed".equals(plan.optString("state"))) continue;
                releasable.put(pinId, ownerId);
            }
            if (!releasable.isEmpty()) store.cleanupExportPins(releasable);
        } catch (Exception retained) {
            // Failed cleanup keeps source references; it must never erase the
            // accepted graph merely because metadata is temporarily unreadable.
        } finally {
            exportAdmissionLock.unlock();
        }
    }

    private void runExportBlocking(ProjectStore.Project project, String aspect, String quality, String fileName, JobManager.Job state) throws Exception {
        runExportBlocking(project, aspect, quality, fileName, state, null);
    }

    private void runExportBlocking(ProjectStore.Project project, String aspect, String quality, String fileName, JobManager.Job state,
                                   ProjectRangeExport.Prepared range) throws Exception {
        JSONObject plan = publicationPlanForJob(state.id);
        int acceptedFrameRate = NativeRenderEngine.frameRate(project);
        JSONObject proof = publicationProof(plan.getString("id"), project.id, project.revision, exportPinsByJob.get(state.id), aspect, quality, fileName, acceptedFrameRate);
        if (range != null) {
            proof.put("exportMode", "range").put("sourceInMs", range.inMs).put("sourceOutMs", range.outMs).put("durationMs", range.durationMs);
        }
        JSONObject priorPublication = plan.optJSONObject("pendingPublication");
        if (priorPublication == null) priorPublication = plan.optJSONObject("publication");
        if (priorPublication != null && !"allocated".equals(priorPublication.optString("phase"))) {
            JSONObject nativeEvidence = priorPublication.optJSONObject("nativeRender");
            if (nativeEvidence == null) nativeEvidence = plan.optJSONObject("outputEvidence");
            if (nativeEvidence == null || nativeEvidence.optLong("projectRevision", -1L) != project.revision
                    || nativeEvidence.optInt("animationFrameRate", -1) != acceptedFrameRate)
                throw new IllegalStateException("Publication receipt does not prove this accepted native graph");
            JSONObject publication = MediaStoreExportPublisher.resumePending(this, priorPublication, proof, publicationRecorder(state.id, nativeEvidence));
            finishNativePublication(project, quality, state, publication, nativeEvidence);
            return;
        }
        if (!plan.optString("outputUri", "").isEmpty() && priorPublication == null)
            throw new IllegalStateException("A legacy published output has no checksum receipt; retain it for explicit owner review instead of publishing a duplicate");
        ensureProjectWorkspaceHydrated(project, state);
        jobs.awaitSafeCheckpoint(state, "native_export_prepare");
        File dir = new File(getCacheDir(), "native_exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create export workspace");
        File temp = new File(dir, "tmp_" + System.currentTimeMillis() + ".mp4");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<File> completed = new AtomicReference<>();
        AtomicReference<JSONObject> renderEvidence = new AtomicReference<>();
        final String exportPinId = exportPinsByJob.get(state.id);
        if (exportPinId != null) renderingExportPins.add(exportPinId);

        activeRenderJobId = state.id;
        try {
            NativeRenderEngine.Listener listener = new NativeRenderEngine.Listener() {
            @Override public void onProgress(int progress, String detail) {
                checkpoint(state, "Rendering video", detail, Math.max(20, Math.min(96, 20 + (int) (progress * .76))), project.id);
            }

            @Override public void onCompleted(File file, JSONObject result) {
                completed.set(file);
                renderEvidence.set(result);
                if (exportPinId != null) renderingExportPins.remove(exportPinId);
                latch.countDown();
            }

            @Override public void onError(String message) {
                error.set(message);
                if (exportPinId != null) renderingExportPins.remove(exportPinId);
                latch.countDown();
            }
        };
            activeRender = range == null ? renderEngine.export(project, temp, aspect, quality, listener)
                    : renderEngine.export(range, temp, aspect, quality, listener);
        } catch (Exception preparationError) {
            if (exportPinId != null) renderingExportPins.remove(exportPinId);
            throw preparationError;
        }
        if (activeRender == null) {
            if (exportPinId != null) renderingExportPins.remove(exportPinId);
            throw new IllegalStateException(error.get() == null ? "Could not start native render" : error.get());
        }

        try {
            while (!latch.await(550, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            }
        } catch (InterruptedException interrupted) {
            // await itself can throw before the loop body. Enqueue native
            // cancellation before releasing the serialized heavy job lane.
            if (activeRender != null) activeRender.cancel();
            throw interrupted;
        }
        if (error.get() != null) throw new IllegalStateException(error.get());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        File ready = completed.get();
        if (ready == null || !ready.exists() || ready.length() == 0) throw new IllegalStateException("Native export produced no file");
        JSONObject evidence = renderEvidence.get();
        if (exportPinId != null && evidence == null) throw new IllegalStateException("Native export has no receipt for its accepted snapshot");
        if (evidence != null) {
            if (evidence.optInt("animationFrameRate", -1) != acceptedFrameRate
                    || evidence.optJSONObject("outputValidation") == null
                    || (evidence.getJSONObject("outputValidation").optBoolean("videoFrameDecoded", false)
                    && !evidence.getJSONObject("outputValidation").optBoolean("videoTimestampCadenceVerified", false)))
                throw new IllegalStateException("Native render receipt does not verify the accepted encoded animation cadence");
            if (evidence.has("projectRevision") && evidence.getLong("projectRevision") != project.revision)
                throw new IllegalStateException("Native render receipt does not match the accepted export revision");
            evidence.put("projectRevision", project.revision);
            if (exportPinId != null) { evidence.put("exportSnapshotId", exportPinId); evidence.put("sourceGraphPinned", true); }
            if (range != null) {
                JSONObject actualRange = evidence.optJSONObject("exportRange");
                if (actualRange == null || actualRange.optLong("sourceProjectRevision", -1L) != range.sourceRevision
                        || actualRange.optLong("sourceInMs", -1L) != range.inMs || actualRange.optLong("sourceOutMs", -1L) != range.outMs
                        || actualRange.optLong("durationMs", -1L) != range.durationMs)
                    throw new IllegalStateException("Native range receipt differs from the accepted immutable In/Out request");
            }
        }

        checkpoint(state, "Exporting video", "Publishing to Movies/VideoStudio", 97, project.id);
        JSONObject publication = MediaStoreExportPublisher.publish(this, ready, fileName, priorPublication, proof, publicationRecorder(state.id, evidence));
        finishNativePublication(project, quality, state, publication, evidence);
        if (!ready.delete()) { /* cache cleanup best effort */ }
        activeRender = null;
        activeRenderJobId = "";
        activeRenderOwnerInitiated = false;
        syncProtocolState();
    }

    private JSONObject publicationPlanForJob(String jobId) throws Exception {
        JSONArray plans = recoveryPlans.allPlans();
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.optJSONObject(i);
            if (plan != null && jobId.equals(plan.optString("jobId", ""))) return plan;
        }
        throw new IllegalStateException("Native publication has no matching durable request");
    }

    private JSONObject publicationProof(String planId, String projectId, long revision, String pinId,
                                         String aspect, String quality, String fileName, int frameRate) throws Exception {
        if (frameRate != 12 && frameRate != 24 && frameRate != 30 && frameRate != 60)
            throw new IllegalArgumentException("Native export cadence supports integer 12, 24, 30 or 60 fps");
        JSONObject proof = new JSONObject().put("recoveryPlanId", planId).put("projectId", projectId).put("projectRevision", revision)
                .put("aspect", aspect).put("quality", quality).put("displayName", fileName).put("container", "mp4").put("frameRate", frameRate);
        if (pinId != null && !pinId.isEmpty()) proof.put("exportSnapshotId", pinId);
        return proof;
    }

    private void addExportRangeProof(JSONObject proof, JSONObject parameters) throws Exception {
        if (!"range".equals(parameters.optString("exportMode", "full"))) return;
        long inMs = titleInteger(parameters, "inMs", -1L, 0L, 9007199254740991L);
        long outMs = titleInteger(parameters, "outMs", -1L, 1L, 9007199254740991L);
        if (outMs <= inMs) throw new IllegalArgumentException("Invalid durable export range");
        proof.put("exportMode", "range").put("sourceInMs", inMs).put("sourceOutMs", outMs).put("durationMs", outMs - inMs);
    }

    private MediaStoreExportPublisher.ReceiptRecorder publicationRecorder(String jobId, JSONObject nativeEvidence) {
        return new MediaStoreExportPublisher.ReceiptRecorder() {
            private JSONObject withEvidence(JSONObject receipt) throws Exception {
                JSONObject durable = new JSONObject(receipt.toString());
                if (nativeEvidence != null) durable.put("nativeRender", new JSONObject(nativeEvidence.toString()));
                return durable;
            }
            @Override public void persistAllocated(JSONObject receipt) throws Exception {
                recoveryPlans.markAllocatedPublicationForJob(jobId, withEvidence(receipt));
            }
            @Override public void persistPending(JSONObject receipt) throws Exception {
                recoveryPlans.markPendingPublicationForJob(jobId, withEvidence(receipt));
            }
            @Override public void persistPublished(JSONObject receipt) throws Exception {
                JSONObject actual = withEvidence(receipt);
                recoveryPlans.recordPublishedForJob(jobId, actual.getString("uri"), actual.getString("name"), nativeEvidence, actual);
            }
        };
    }

    private void finishNativePublication(ProjectStore.Project project, String quality, JobManager.Job state,
                                          JSONObject publication, JSONObject nativeEvidence) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONObject exported = state.result == null ? ok() : new JSONObject(state.result.toString());
        exported.put("projectId", project.id).put("projectRevision", nativeEvidence == null ? project.revision : nativeEvidence.optLong("projectRevision", project.revision))
                .put("outputUri", publication.getString("uri")).put("outputName", publication.getString("name"))
                .put("publication", new JSONObject(publication.toString())).put("recoveredPublishedOutput", publication.optBoolean("reused", false));
        if (nativeEvidence != null) exported.put("nativeRender", new JSONObject(nativeEvidence.toString()));
        if (nativeEvidence != null && nativeEvidence.optJSONObject("exportRange") != null)
            exported.put("exportRange", new JSONObject(nativeEvidence.getJSONObject("exportRange").toString()));
        state.setResult(exported);
        registerPublishedExport(project, Uri.parse(publication.getString("uri")), publication.getString("name"), quality, state.id, nativeEvidence, state);
        syncProtocolState();
    }

    private void registerPublishedExport(ProjectStore.Project project, Uri publicUri, String fileName,
                                         String quality, String jobId, JSONObject evidence, JobManager.Job state) throws Exception {
        ProjectStore.Project fresh = store.get(project.id);
        if (fresh != null) {
            boolean samePublication = publicUri.toString().equals(fresh.latestExportUri) && fresh.latestExportAt > 0L;
            fresh.latestExportUri = publicUri.toString();
            fresh.latestExportName = fileName;
            if (!samePublication) fresh.latestExportAt = System.currentTimeMillis();
            if (state != null) checkpoint(state, "Registering generated media", "Adding rendered MP4 to the project Media Bin", 98, project.id);
            ProjectStore.Asset generated = store.registerGeneratedAsset(
                    fresh,
                    publicUri,
                    fileName,
                    "final_render",
                    false
            );
            final String generatedId = generated.id;
            ProjectStore.Project registered = store.edit(project.id, -1L, "Record accepted export revision", current -> {
                ProjectStore.Asset asset = current.asset(generatedId);
                if (asset == null) throw new IllegalStateException("Published export asset disappeared during registration");
                asset.generationMetadata.put("projectRevision", evidence == null ? project.revision : evidence.optLong("projectRevision", project.revision));
                if (evidence != null && evidence.has("exportSnapshotId")) {
                    asset.generationMetadata.put("exportSnapshotId", evidence.getString("exportSnapshotId"));
                    asset.generationMetadata.put("sourceGraphPinned", true);
                }
                if (evidence != null && evidence.optJSONObject("exportRange") != null)
                    asset.generationMetadata.put("exportRange", new JSONObject(evidence.getJSONObject("exportRange").toString()));
            });
            if (state != null) {
                JSONObject receipt = state.result == null ? ok() : new JSONObject(state.result.toString());
                receipt.put("assetId", generatedId); receipt.put("registeredAsset", true);
                receipt.put("nativeProjectRevision", registered.revision); state.setResult(receipt);
            }
            previewSnapshots.publish(new PreviewSnapshotStore.Snapshot(
                    "final-" + jobId + "-" + fresh.latestExportAt,
                    fresh.id,
                    fresh.updatedAt,
                    fresh.latestExportAt,
                    publicUri.toString(),
                    fileName.toLowerCase(Locale.US).contains("1080") ? "1080p" : "final",
                    "final",
                    jobId,
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
                    jobId,
                    System.currentTimeMillis()
            ));
            ActivityLog.add(this, "system", "Generated video available",
                    generated.name + " • Media Bin • asset " + shortId(generated.id),
                    "success", 100, null, fresh.id);
        }
    }

    private void ensureProjectWorkspaceHydrated(ProjectStore.Project project,
                                               JobManager.Job state) throws Exception {
        if (project == null) return;
        JSONObject offloadState = creativeWorkspace.cloudOffloadState(project.id);
        boolean missingFiles = hasMissingCreativeLayerFiles(project);
        if (!missingFiles && !offloadState.optBoolean("offloaded", false)) return;
        if (!offloadState.optBoolean("offloaded", false)) {
            throw new IllegalStateException("Creative layer files are missing and no verified cloud-offload marker is present");
        }
        String archiveTreeUri = offloadState.optString("archiveTreeUri", "");
        String archiveDirectoryUri = offloadState.optString("archiveDirectoryUri", "");
        if (archiveTreeUri.isEmpty() || archiveDirectoryUri.isEmpty())
            throw new IllegalStateException("Evicted project has no pinned verified archive location; restore its original cloud archive before rendering");

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
                ),
                archiveTreeUri,
                archiveDirectoryUri
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
        return queueRemoteImport(p, "import_attachment");
    }

    private JSONObject queueRemoteImport(JSONObject p, String action) throws Exception {
        String sourceUrl = p.optString("sourceUrl", "").trim();
        URL attachmentUrl = new URL(sourceUrl);
        if (!"https".equalsIgnoreCase(attachmentUrl.getProtocol()) || attachmentUrl.getUserInfo() != null
                || (attachmentUrl.getPort() != -1 && attachmentUrl.getPort() != 443)) {
            throw new IllegalArgumentException("Media import requires HTTPS on port 443 without URL credentials");
        }
        ProjectStore.Project project = resolveImportProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT attachment");
        String mimeHint = p.optString("mime", "");
        long sizeHint = Math.max(0L, p.optLong("size", 0L));
        String sha256 = p.optString("sha256", "").trim().toLowerCase(Locale.US);
        String transferId = stableTransferId(project.id, sourceUrl, name);

        JSONObject durable = new JSONObject(p.toString());
        durable.put("projectId", project.id);
        JobManager.Job job = submitRecoverableLight(action, durable, project.id, "Media import • " + name, state -> {
            attachmentIngestionLock.lockInterruptibly();
            try {
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
                ProjectStore.Asset asset = addValidatedImportedAsset(project, file, name, mime,
                        sizeHint, sha256, new JSONObject());
                state.setResult(importedAssetResult(project.id, asset, transferId, transfer.resumed));
                checkpoint(state, "Direct ChatGPT attachment import",
                        "Attachment is now VideoStudio-owned media", 100, project.id);
                syncProtocolState();
            } finally {
                attachmentIngestionLock.unlock();
            }
        });

        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("transport", "resumable-direct-app-ingest");
        result.put("transferId", transferId);
        result.put("durableRecovery", true);
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
     * Private inline small-media ingest used as a compatibility bridge when the normal
     * Worker handoff cannot deliver a ChatGPT attachment. The bytes travel inside the already
     * owner-authenticated MCP command and are written directly to app-private storage.
     * Nothing is published to Gallery or a public URL.
     */
    private JSONObject importInlineBase64(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveImportProject(p.optString("projectId", ""));
        String name = sanitizeFileName(p.optString("name", "ChatGPT_media"));
        String mime = p.optString("mime", "image/png").trim().toLowerCase(Locale.US);
        boolean isImage = "image/png".equals(mime) || "image/jpeg".equals(mime) || "image/webp".equals(mime);
        boolean isMp4 = "video/mp4".equals(mime);
        if (!(isImage || isMp4)) {
            throw new IllegalArgumentException("Inline MCP ingest accepts PNG, JPEG, WebP or MP4 media only");
        }

        String encoded = p.optString("base64", "").trim();
        if (encoded.startsWith("data:")) {
            int comma = encoded.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Malformed data URL");
            encoded = encoded.substring(comma + 1);
        }
        if (encoded.isEmpty()) throw new IllegalArgumentException("Missing inline attachment bytes");
        if (encoded.length() > MAX_INLINE_BASE64_CHARS) {
            throw new IllegalArgumentException("Inline media exceeds VideoStudio's private MCP transfer limit");
        }

        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid base64 media payload");
        }
        if (bytes.length == 0 || bytes.length > MAX_INLINE_MEDIA_BYTES) {
            throw new IllegalArgumentException("Inline media exceeds VideoStudio's 12 MB decoded transfer limit");
        }

        String expectedSha = p.optString("sha256", "").trim().toLowerCase(Locale.US);
        if (!expectedSha.isEmpty()) {
            String actualSha = sha256Hex(bytes);
            if (!actualSha.equals(expectedSha)) throw new IllegalArgumentException("Inline media SHA-256 mismatch");
        }

        if (isMp4) {
            if (bytes.length < 12
                    || bytes[4] != 'f'
                    || bytes[5] != 't'
                    || bytes[6] != 'y'
                    || bytes[7] != 'p') {
                throw new IllegalArgumentException("Inline MP4 payload is missing an ISO media ftyp header");
            }
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

        int width = 0;
        int height = 0;
        if (isImage) {
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
            width = bounds.outWidth;
            height = bounds.outHeight;
        }

        ProjectStore.Asset asset;
        try {
            asset = addImportedAsset(project, file, name, mime);
        } catch (Exception error) {
            if (file.exists()) file.delete();
            throw error;
        }
        ActivityLog.add(this, "chatgpt",
                isMp4 ? "Private inline video imported" : "Private inline frame imported",
                isMp4 ? name + " • " + (bytes.length / 1024L) + " KB"
                        : name + " • " + width + "×" + height,
                "success", 100, null, project.id);

        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("assetId", asset.id);
        result.put("name", asset.name);
        result.put("mime", asset.mime);
        result.put("size", bytes.length);
        if (isImage) {
            result.put("width", width);
            result.put("height", height);
        } else {
            result.put("durationMs", asset.durationMs);
        }
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
        String handoffId = p.optString("handoffId", "").trim();
        if (handoffId.isEmpty()) throw new IllegalArgumentException("Missing private handoff ID");
        ProjectStore.Project project = resolveImportProject(p.optString("projectId", ""));
        String name = p.optString("name", "ChatGPT attachment");
        String mimeHint = p.optString("mime", "");
        long sizeHint = Math.max(0L, p.optLong("size", 0L));
        String expectedSha = p.optString("sha256", "").trim().toLowerCase(Locale.US);
        String transferId = stableTransferId(project.id, "private-handoff:" + handoffId, name);
        JSONObject durable = new JSONObject(p.toString());
        durable.put("projectId", project.id);

        JobManager.Job job = submitRecoverableLight("import_chat_file", durable, project.id,
                "Private import • " + name, state -> {
            attachmentIngestionLock.lockInterruptibly();
            try {
                File dir = new File(getFilesDir(), "imports");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
                File file = new File(dir, transferId + "_" + sanitizeFileName(name));
                ResumableTransferManager.Result transfer = file.isFile()
                        ? new ResumableTransferManager.Result(file, file.length(), sizeHint > 0 ? sizeHint : file.length(), true, mimeHint)
                        : transferManager.download(
                                new ResumableTransferManager.Request(transferId, "private-handoff:" + handoffId, file, sizeHint, expectedSha),
                                (source, offset, etag, lastModified) -> {
                                    HttpURLConnection connection = protocol.openPrivateHandoff(handoffId, offset, etag, lastModified);
                                    int code = connection.getResponseCode();
                                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                                        connection.disconnect();
                                        throw new IllegalStateException(code == 404
                                                ? "Attachment handoff expired or its private upload is unavailable. Share the attachment again to obtain a fresh host file reference."
                                                : "Private attachment transfer failed: HTTP " + code);
                                    }
                                    return connection;
                                },
                                (completed, expected) -> {
                                    int rawProgress = ResumableTransferManager.progressPercent(completed, expected);
                                    checkpoint(state, "Importing ChatGPT attachment",
                                            (completed / 1024L / 1024L) + " MB privately streamed",
                                            rawProgress < 0 ? 45 : Math.min(95, 4 + rawProgress * 91 / 100), project.id);
                                });
                JSONObject provenance = new JSONObject();
                provenance.put("source", "chatgpt-attachment");
                provenance.put("fileId", p.optString("fileId", ""));
                ProjectStore.Asset asset = addValidatedImportedAsset(project, file, name,
                        mimeHint.isEmpty() ? transfer.contentType : mimeHint,
                        sizeHint, expectedSha, provenance);
                JSONObject imported = importedAssetResult(project.id, asset, transferId, transfer.resumed);
                state.setResult(imported);
                checkpoint(state, "Importing ChatGPT attachment", "Media validated and added to the editor", 100, project.id);
                syncProtocolState();
            } finally {
                attachmentIngestionLock.unlock();
            }
        });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("transferId", transferId);
        result.put("durableRecovery", true);
        result.put("transport", "owner-authenticated-resumable-private-relay");
        return result;
    }

    private JSONObject queueUrlImport(JSONObject p) throws Exception {
        JSONObject remote = new JSONObject(p.toString());
        remote.put("sourceUrl", p.optString("url", ""));
        return queueRemoteImport(remote, "import_url");
    }

    private String stableTransferId(String projectId, String source, String name) {
        String seed = String.valueOf(projectId) + "\n" + String.valueOf(source) + "\n" + String.valueOf(name);
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private ProjectStore.Asset addImportedAsset(ProjectStore.Project project, File file,
                                                    String name, String mime) throws Exception {
        return addImportedAsset(project, file, name, mime, new JSONObject());
    }

    private ProjectStore.Asset addImportedAsset(ProjectStore.Project project, File file,
                                                    String name, String mime, JSONObject provenance) throws Exception {
        MediaImportInspector.Metadata metadata = MediaImportInspector.inspect(file, mime, name);
        return addImportedAsset(project, file, name, metadata, provenance);
    }

    private ProjectStore.Asset addValidatedImportedAsset(ProjectStore.Project project, File file,
                            String name, String mime, long expectedBytes, String expectedSha,
                            JSONObject provenance) throws Exception {
        MediaImportInspector.Metadata metadata;
        try {
            verifyCompletedImport(file, expectedBytes, expectedSha);
            metadata = MediaImportInspector.inspect(file, mime, name);
        } catch (Exception error) {
            if (!(error instanceof InterruptedException)
                    && !Thread.currentThread().isInterrupted()
                    && !store.referencesMediaUri(Uri.fromFile(file).toString())
                    && file.isFile() && !file.delete()) {
                error.addSuppressed(new IllegalStateException("Rejected unowned import could not be removed"));
            }
            throw error;
        }
        return addImportedAsset(project, file, name, metadata, provenance);
    }

    private ProjectStore.Asset addImportedAsset(ProjectStore.Project project, File file,
                         String name, MediaImportInspector.Metadata metadata, JSONObject provenance) throws Exception {
        String uri = Uri.fromFile(file).toString();
        String assetId = UUID.nameUUIDFromBytes((project.id + "\n" + uri)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ProjectStore.Project committed = store.edit(project.id, -1L, "Import media", current -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Attachment import was cancelled before registration");
            ProjectStore.Asset asset = null;
            for (ProjectStore.Asset existing : current.assets) if (uri.equals(existing.uri)) asset = existing;
            boolean newlyImported = asset == null;
            if (newlyImported) {
                asset = new ProjectStore.Asset();
                asset.id = assetId;
                asset.uri = uri;
                asset.name = name;
            }
            asset.mime = metadata.mime;
            asset.durationMs = metadata.durationMs;
            asset.width = metadata.width;
            asset.height = metadata.height;
            asset.rotation = metadata.rotation;
            asset.hasAudio = metadata.hasAudio;
            asset.sizeBytes = metadata.sizeBytes;
            asset.seekable = true;
            asset.persistedReadAccess = true;
            asset.providerAuthority = "";
            if (asset.importMetadata == null) asset.importMetadata = new JSONObject();
            java.util.Iterator<String> provenanceKeys = provenance.keys();
            while (provenanceKeys.hasNext()) {
                String key = provenanceKeys.next();
                asset.importMetadata.put(key, provenance.get(key));
            }
            asset.importMetadata.put("validated", true);
            if (metadata.mime.startsWith("image/")) asset.importMetadata.put("exifOrientation", metadata.exifOrientation);
            asset.importMetadata.put("storage", "app-private");
            if (newlyImported) current.assets.add(asset);
            if (newlyImported && !current.defaultTrack(metadata.mime.startsWith("audio/") ? "audio" : "video").locked) {
                ProjectStore.Clip inserted = ProjectTimeline.appendAsset(current, asset);
                inserted.id = UUID.nameUUIDFromBytes((assetId + "\ninitial-clip")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            }
        });
        for (ProjectStore.Asset asset : committed.assets) if (uri.equals(asset.uri)) return asset;
        throw new IllegalStateException("Imported asset was not committed to the project");
    }

    private void verifyCompletedImport(File file, long expectedBytes, String expectedSha) throws Exception {
        if (!file.isFile() || file.length() == 0L) throw new IllegalArgumentException("Attachment download is empty");
        if (expectedBytes > 0L && file.length() != expectedBytes) throw new IllegalArgumentException("Attachment byte size does not match the host file reference");
        if (expectedSha != null && !expectedSha.isEmpty()
                && !sha256File(file).equalsIgnoreCase(expectedSha)) {
            throw new IllegalArgumentException("Attachment SHA-256 mismatch");
        }
    }

    private String sha256File(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte part : digest.digest()) value.append(String.format(Locale.US, "%02x", part & 0xff));
        return value.toString();
    }

    private JSONObject importedAssetResult(String projectId, ProjectStore.Asset asset,
                                           String transferId, boolean resumed) throws Exception {
        JSONObject result = ok();
        result.put("projectId", projectId);
        result.put("assetId", asset.id);
        result.put("asset", asset.toJson());
        result.put("transferId", transferId);
        result.put("resumed", resumed);
        result.put("validatedMedia", true);
        ProjectStore.Project current = store.get(projectId);
        if (current != null) {
            result.put("revision", current.revision);
            result.put("clipCount", current.clips.size());
            boolean inserted = false;
            for (ProjectStore.Clip clip : current.clips) if (asset.id.equals(clip.assetId)) inserted = true;
            result.put("insertedIntoTimeline", inserted);
        }
        return result;
    }

    private ProjectStore.Project resolveProject(String id) {
        ProjectStore.Project project = id == null || id.isEmpty() ? store.active() : store.get(id);
        if (project == null) project = store.create("ChatGPT Project");
        store.setActive(project.id);
        return project;
    }

    private ProjectStore.Project resolveImportProject(String id) {
        String requested = id == null ? "" : id.trim();
        if (requested.isEmpty()) return resolveProject("");
        ProjectStore.Project project = store.get(requested);
        if (project == null) throw new IllegalArgumentException("Attachment target project no longer exists");
        store.setActive(project.id);
        return project;
    }

    private ProjectStore.Project resolveExistingProject(String id) {
        ProjectStore.Project project = id == null || id.isEmpty() ? store.active() : store.get(id);
        if (project == null) throw new IllegalArgumentException("Select an existing owned project");
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

    private JSONObject stateJson(JSONObject parameters) throws Exception {
        int projectOffset = (int) titleInteger(parameters, "projectOffset", 0, 0, Integer.MAX_VALUE);
        int projectLimit = (int) titleInteger(parameters, "projectLimit", 20, 1, 100);
        int assetOffset = (int) titleInteger(parameters, "assetOffset", 0, 0, Integer.MAX_VALUE);
        int assetLimit = (int) titleInteger(parameters, "assetLimit", 50, 1, 100);
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
            JSONArray projects = store.summaries().optJSONArray("projects");
            out.put("projects", stateArrayPage(projects, projectOffset, projectLimit));
            out.put("projectPage", statePageMetadata(projects == null ? 0 : projects.length(), projectOffset, projectLimit));
            out.put("projectStorage", store.storageBackend());
            ProjectStore.Project active = store.active();
            if (active != null) {
                out.put("activeProjectId", active.id);
                out.put("activeProjectName", active.name);
                out.put("clipCount", active.clips.size());
                out.put("assetCount", active.assets.size());
                out.put("durationMs", active.outputDurationMs());
                String prompt = active.sourcePrompt == null ? "" : active.sourcePrompt;
                out.put("sourcePrompt", prompt.length() <= 4000 ? prompt : prompt.substring(0, 4000));
                if (prompt.length() > 4000) out.put("sourcePromptTruncated", true);
                out.put("latestExportUri", active.latestExportUri);
                out.put("latestExportName", active.latestExportName);
                out.put("creativeWorkspace", creativeWorkspace.status(active.id));
                JSONArray assets = new JSONArray();
                int assetEnd = (int) Math.min((long) active.assets.size(), (long) assetOffset + assetLimit);
                for (int index = assetOffset; index < assetEnd; index++) {
                    ProjectStore.Asset a = active.assets.get(index);
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
                out.put("assetPage", statePageMetadata(active.assets.size(), assetOffset, assetLimit));
                out.put("activeProjectRevision", active.revision);
            }
            JSONObject workload = jobs.state();
            workload.put("jobs", stateSummaries(workload.optJSONArray("jobs")));
            out.put("workload", workload);
            out.put("recoveryPlans", stateSummaries(recoveryPlans.recent(12)));
            out.put("capabilityRegistry", capabilityRegistry.describe());
            out.put("computeProfile", computeProfile.snapshot());
            out.put("driveWorkspace", driveWorkspace.status());
            out.put("creatorCatalog", CreatorCatalog.describe());
            out.put("recentActivity", ActivityLog.recent(this, 30));
            out.put("commandJournal", stateSummaries(commandJournal.recent(20)));
            out.put("diagnosticSummaries", true);
            out.put("projectStateAction", "project_state");
        } catch (Exception error) {
            out.put("diagnosticsIncomplete", true);
            out.put("diagnosticError", error.getMessage() == null ? "Some local diagnostics were unavailable" : error.getMessage());
        }
        return out;
    }

    private static JSONArray stateArrayPage(JSONArray source, int offset, int limit) {
        JSONArray page = new JSONArray();
        if (source == null) return page;
        int end = (int) Math.min((long) source.length(), (long) offset + limit);
        for (int index = offset; index < end; index++) page.put(source.opt(index));
        return page;
    }

    private static JSONObject statePageMetadata(int total, int offset, int limit) throws Exception {
        long next = Math.min((long) total, (long) offset + limit);
        return new JSONObject().put("total", total).put("offset", offset).put("limit", limit)
                .put("hasMore", next < total).put("nextOffset", next < total ? next : JSONObject.NULL);
    }

    private static JSONArray stateSummaries(JSONArray source) throws Exception {
        JSONArray summaries = new JSONArray();
        if (source == null) return summaries;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            JSONObject summary = new JSONObject();
            for (String key : new String[]{"id", "seq", "action", "name", "kind", "state", "status", "projectId", "jobId", "stage", "progress", "detail", "error", "createdAt", "updatedAt", "completedAt", "ownerInitiated"}) {
                Object value = item.opt(key);
                if (value instanceof Number || value instanceof Boolean) summary.put(key, value);
                else if (value instanceof String) {
                    String text = (String) value;
                    summary.put(key, text.length() > 2048 ? text.substring(0, 2048) : text);
                    if (text.length() > 2048) summary.put("textTruncated", true);
                }
            }
            if (item.has("result")) summary.put("resultAvailable", true);
            summaries.put(summary);
        }
        return summaries;
    }

    private JSONObject projectStatePage(JSONObject parameters) throws Exception {
        ProjectStore.Project project = resolveExistingProject(parameters.optString("projectId", ""));
        if (parameters.has("expectedRevision") && titleInteger(parameters, "expectedRevision", project.revision, 0, 9007199254740991L) != project.revision)
            throw new IllegalArgumentException("Project revision changed; restart inspection at the current revision " + project.revision);
        String section = parameters.optString("section", "clips");
        int offset = (int) titleInteger(parameters, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = (int) titleInteger(parameters, "limit", 20, 1, 50);
        JSONObject header = new JSONObject().put("id", project.id).put("name", project.name).put("revision", project.revision)
                .put("durationMs", project.outputDurationMs()).put("updatedAt", project.updatedAt)
                .put("animationFrameRate", NativeRenderEngine.frameRate(project)).put("editorRange", ProjectMarkers.rangeJson(project));
        int total;
        switch (section) {
            case "assets": total = project.assets.size(); break;
            case "clips": total = project.clips.size(); break;
            case "tracks": total = project.tracks.size(); break;
            case "markers": total = project.markers.length(); break;
            default: throw new IllegalArgumentException("Project section must be assets, clips, tracks or markers");
        }
        JSONArray page = new JSONArray(); int next = Math.min(offset, total), bytes = 0;
        while (next < total && page.length() < limit) {
            JSONObject item = "assets".equals(section) ? project.assets.get(next).toJson()
                    : "clips".equals(section) ? project.clips.get(next).toJson()
                    : "tracks".equals(section) ? project.tracks.get(next).toJson() : new JSONObject(project.markers.getJSONObject(next).toString());
            int itemBytes = item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + itemBytes > 384 * 1024) {
                if (page.length() == 0) throw new IllegalArgumentException("This project entry exceeds the 384 KiB inspection page budget; use compact rig/cel diagnostics for its bounded authored definition");
                break;
            }
            page.put(item); bytes += itemBytes; next++;
        }
        return ok().put("projectId", project.id).put("revision", project.revision).put("project", header)
                .put("section", section).put("items", page).put("total", total).put("offset", offset)
                .put("hasMore", next < total).put("nextOffset", next < total ? next : JSONObject.NULL)
                .put("maximumPageBytes", 384 * 1024);
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
        String action = command.optString("action", "");
        JSONObject p = command.optJSONObject("parameters");
        String projectId = p == null ? "" : p.optString("projectId", result.optString("projectId", ""));

        if (ExecutionTruthPolicy.isDeferredResult(result)) {
            String jobId = result.optString("jobId", "");
            ActivityLog.add(this, "chatgpt", friendlyAction(action),
                    "Queued inside VideoStudio • job " + shortId(jobId),
                    "queued", 0, command.optString("id", ""), projectId);
            commandJournal.linkJob(command, jobId, projectId, result);
            ActivityLog.bindJob(this, command.optString("id", ""), jobId);
            watchDeferredCommand(command, result);
            return;
        }

        String detail = ok ? completionDetail(action, result) : result.optString("error", "Command failed");
        ActivityLog.add(this, "chatgpt", friendlyAction(action), detail,
                ok ? "success" : "failed", ok ? 100 : null,
                command.optString("id", ""), projectId);
        String terminalStatus = ok ? "completed" : "failed";
        commandJournal.finish(command, result, terminalStatus);
        protocol.complete(command, result, terminalStatus);
    }

    private boolean mayQueueBackgroundWork(String action) {
        if (action == null) return false;
        switch (action) {
            case "prompt_video":
            case "animate_images":
            case "export_project":
            case "export_range":
            case "archive_source_media":
            case "restore_source_media":
            case "autonomous_edit":
            case "generate_image":
            case "create_title":
            case "cel_create":
            case "cel_update":
            case "generate_voice":
            case "run_creative_graph":
            case "install_model_pack":
            case "sync_project_to_drive":
            case "offload_project_to_drive":
            case "restore_project_from_drive":
            case "archive_model_pack_to_drive":
            case "restore_model_pack_from_drive":
            case "import_chat_file":
            case "import_attachment":
            case "import_url":
            case "metadata_mirror_sync":
            case "metadata_mirror_apply":
            case "metadata_mirror_retry":
            case "metadata_mirror_status":
            case "metadata_mirror_revoke":
            case "metadata_mirror_keep_native":
                return true;
            default:
                return false;
        }
    }

    private void reattachInflightCommandWatchers() {
        if (commandJournal == null) return;
        JSONArray inflight = commandJournal.inflightEntries(160);
        for (int i = 0; i < inflight.length(); i++) {
            JSONObject entry = inflight.optJSONObject(i);
            if (entry == null) continue;
            JSONObject command = entry.optJSONObject("command");
            if (command == null) continue;
            watchDeferredCommand(command, entry.optJSONObject("queuedResult"));
        }
    }

    private void updateDeferredActivity(JSONObject command,
                                        JSONObject binding,
                                        JSONObject job) {
        if (command == null || job == null) return;
        String action = command.optString("action", "");
        JSONObject presentation = ExecutionTruthPolicy.presentDeferredJob(friendlyAction(action), job);
        String commandId = command.optString("id", "");
        String jobId = binding == null ? "" : binding.optString("jobId", "");
        String projectId = binding == null
                ? ""
                : binding.optString("projectId", "");
        if (!commandId.isEmpty() && !jobId.isEmpty()) {
            ActivityLog.bindJob(this, commandId, jobId);
        }
        ActivityLog.add(this, "chatgpt",
                presentation.optString("action", friendlyAction(action)),
                presentation.optString("detail", job.optString("detail", "")),
                presentation.optString("status", "running"),
                presentation.optInt("progress", job.optInt("progress", 0)),
                commandId,
                projectId);
    }

    private void watchDeferredCommand(JSONObject command, JSONObject initialQueuedResult) {
        if (command == null) return;
        String commandId = command.optString("id", "");
        if (commandId.isEmpty() || !watchedCommands.add(commandId)) return;

        JSONObject commandCopy;
        JSONObject queuedCopy;
        try { commandCopy = new JSONObject(command.toString()); }
        catch (Exception ignored) { commandCopy = command; }
        try {
            queuedCopy = initialQueuedResult == null
                    ? new JSONObject()
                    : new JSONObject(initialQueuedResult.toString());
        } catch (Exception ignored) {
            queuedCopy = initialQueuedResult == null ? new JSONObject() : initialQueuedResult;
        }

        final JSONObject durableCommand = commandCopy;
        final JSONObject durableQueued = queuedCopy;
        commandCompletionWatchers.execute(() -> {
            long missingSince = 0L;
            String lastActivityFingerprint = "";
            try {
                while (serviceAlive && !Thread.currentThread().isInterrupted()) {
                    JSONObject binding = commandJournal.inflight(commandId);
                    if (binding == null) return;

                    String jobId = binding.optString("jobId", durableQueued.optString("jobId", ""));
                    if (jobId.isEmpty()) {
                        Thread.sleep(350L);
                        continue;
                    }

                    JSONObject state = jobs.get(jobId);
                    if (!state.optBoolean("found", false)) {
                        if (missingSince == 0L) missingSince = System.currentTimeMillis();
                        if (System.currentTimeMillis() - missingSince > 180_000L) {
                            JSONObject failed = new JSONObject();
                            failed.put("ok", false);
                            failed.put("error", "Native background job disappeared before a terminal result");
                            finishDeferredCommand(durableCommand, failed, "failed",
                                    binding.optString("projectId", ""));
                            return;
                        }
                        Thread.sleep(500L);
                        continue;
                    }
                    missingSince = 0L;

                    JSONObject job = state.optJSONObject("job");
                    if (job == null) {
                        Thread.sleep(350L);
                        continue;
                    }
                    String jobState = job.optString("state", "");
                    if (durableCommand.optString("action", "").startsWith("metadata_mirror_")
                            && JobManager.STATE_CHECKPOINTED.equals(jobState)) {
                        JSONObject interrupted = metadataMirror.status(binding.optString("projectId", ""));
                        interrupted.put("ok", false);
                        interrupted.put("error", "Metadata operation interrupted by restart; inspect the pending marker and retry explicitly");
                        interrupted.put("jobId", jobId);
                        finishDeferredCommand(durableCommand, interrupted, "failed", binding.optString("projectId", ""));
                        return;
                    }
                    if (!JobManager.isTerminal(jobState)) {
                        String fingerprint = jobState
                                + "|" + job.optInt("progress", 0)
                                + "|" + job.optString("stage", "")
                                + "|" + job.optString("detail", "");
                        if (!fingerprint.equals(lastActivityFingerprint)) {
                            updateDeferredActivity(durableCommand, binding, job);
                            lastActivityFingerprint = fingerprint;
                        }
                        Thread.sleep(450L);
                        continue;
                    }

                    String projectId = binding.optString(
                            "projectId",
                            durableQueued.optString("projectId", "")
                    );
                    if ("completed".equals(jobState)) {
                        JSONObject finalResult = buildVerifiedDeferredResult(
                                durableCommand.optString("action", ""),
                                binding.optJSONObject("queuedResult"),
                                job,
                                jobId,
                                projectId
                        );
                        if (finalResult.optBoolean("ok", false)) {
                            finishDeferredCommand(durableCommand, finalResult, "completed", projectId);
                        } else {
                            finishDeferredCommand(durableCommand, finalResult, "failed", projectId);
                        }
                    } else {
                        JSONObject failed = new JSONObject();
                        failed.put("ok", false);
                        failed.put("jobId", jobId);
                        failed.put("jobState", jobState);
                        if (job.optJSONObject("result") != null)
                            failed.put("jobResult", new JSONObject(job.getJSONObject("result").toString()));
                        failed.put("error", job.optString("detail",
                                "Native job " + jobState));
                        finishDeferredCommand(durableCommand, failed, "failed", projectId);
                    }
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                try {
                    JSONObject failed = new JSONObject();
                    failed.put("ok", false);
                    failed.put("error", error.getMessage() == null
                            ? "Could not verify native job completion"
                            : error.getMessage());
                    finishDeferredCommand(durableCommand, failed, "failed",
                            durableQueued.optString("projectId", ""));
                } catch (Exception ignored) {}
            } finally {
                watchedCommands.remove(commandId);
            }
        });
    }

    private JSONObject buildVerifiedDeferredResult(String action,
                                                   JSONObject queuedResult,
                                                   JSONObject job,
                                                   String jobId,
                                                   String projectId) throws Exception {
        JSONObject out = queuedResult == null
                ? new JSONObject()
                : new JSONObject(queuedResult.toString());
        out.put("ok", true);
        out.put("queued", false);
        out.put("completed", true);
        out.put("jobId", jobId);
        out.put("jobState", "completed");
        out.put("job", new JSONObject(job.toString()));

        JSONObject jobResult = job.optJSONObject("result");
        if (jobResult != null) out.put("jobResult", new JSONObject(jobResult.toString()));
        if ("archive_source_media".equals(action) || "restore_source_media".equals(action)) {
            boolean archive = "archive_source_media".equals(action);
            if (jobResult == null || !jobResult.optBoolean(archive ? "committed" : "restored", false)
                    || !projectId.equals(jobResult.optString("projectId", ""))
                    || jobResult.optLong("sourceBytes", 0L) <= 0L
                    || !jobResult.optString("sourceSha256", "").matches("[a-f0-9]{64}")
                    || (!archive && (!jobResult.optBoolean("registeredAsset", false) || !isReadableOutput(jobResult.optString("uri", ""))))) {
                out.put("ok", false).put("error", "Source transfer completed without its verified durable receipt");
                return out;
            }
            out.put("receipt", new JSONObject(jobResult.toString()));
            out.put("committed", jobResult.optBoolean("committed", false));
            out.put("restored", jobResult.optBoolean("restored", false));
            out.put("relinked", jobResult.optBoolean("relinked", false));
            out.put("originalSourceDeleted", false);
            if (!archive && !jobResult.optBoolean("relinked", false))
                out.put("completionScope", "verified-restored-media-bin-source-relink-blocked");
        }
        if (action.startsWith("metadata_mirror_") && jobResult != null) {
            out.put("nativeApplied", jobResult.optBoolean("nativeApplied", false));
            out.put("nativeUnchanged", jobResult.optBoolean("nativeUnchanged", false));
            out.put("pendingAcknowledgement", jobResult.optBoolean("pendingAcknowledgement", false));
            out.put("reconciled", jobResult.optBoolean("reconciled", false));
            out.put("executorAvailable", false);
            out.put("mediaUploaded", false);
            if (jobResult.has("resolution")) out.put("resolution", jobResult.getString("resolution"));
            if (jobResult.has("pendingResolved")) out.put("pendingResolved", jobResult.getBoolean("pendingResolved"));
            if (jobResult.has("nativeKept")) out.put("nativeKept", jobResult.getBoolean("nativeKept"));
            if (jobResult.has("appliedRevision")) out.put("appliedRevision", jobResult.getLong("appliedRevision"));
            if (jobResult.optBoolean("pendingAcknowledgement", false))
                out.put("completionScope", "native-metadata-applied-cloud-acknowledgement-pending");
        }

        if ("import_chat_file".equals(action) || "import_attachment".equals(action) || "import_url".equals(action)) {
            ProjectStore.Project importedProject = store.get(projectId);
            String assetId = jobResult == null ? "" : jobResult.optString("assetId", "");
            ProjectStore.Asset asset = importedProject == null ? null : importedProject.asset(assetId);
            if (asset == null || !isReadableOutput(asset.uri)) {
                out.put("ok", false);
                out.put("error", "Attachment job completed without a readable asset committed to the project");
                return out;
            }
            out.put("assetId", asset.id);
            out.put("asset", asset.toJson());
            out.put("revision", importedProject.revision);
            out.put("validatedMedia", true);
        }

        if ("cel_create".equals(action) || "cel_update".equals(action)) {
            ProjectStore.Project celProject = store.get(projectId);
            String clipId = jobResult == null ? "" : jobResult.optString("clipId", "");
            String assetId = jobResult == null ? "" : jobResult.optString("assetId", "");
            ProjectStore.Clip clip = celProject == null ? null : celProject.clip(clipId);
            ProjectStore.Asset asset = celProject == null ? null : celProject.asset(assetId);
            JSONObject cel = asset == null ? null : asset.importMetadata.optJSONObject("animationCel");
            if (clip == null || !assetId.equals(clip.assetId) || cel == null || !isReadableOutput(asset.uri)
                    || !cel.optString("pngSha256").equals(jobResult.optString("pngSha256"))
                    || !cel.optString("documentSha256").equals(jobResult.optString("documentSha256"))) {
                return out.put("ok", false).put("error", "Cel job no longer has its committed immutable PNG and matching exposure");
            }
            out.put("clipId", clipId).put("assetId", assetId).put("revision", jobResult.getLong("revision"))
                    .put("currentRevision", celProject.revision).put("renderer", "native_vector_strokes_to_png")
                    .put("pngSha256", jobResult.getString("pngSha256")).put("documentSha256", jobResult.getString("documentSha256"))
                    .put("reused", jobResult.optBoolean("reused", false));
        }

        if ("create_title".equals(action)) {
            ProjectStore.Project titleProject = store.get(projectId);
            String clipId = jobResult == null ? "" : jobResult.optString("clipId", "");
            ProjectStore.Clip titleClip = titleProject == null ? null : titleProject.clip(clipId);
            ProjectStore.Asset titleAsset = titleClip == null ? null : titleProject.asset(titleClip.assetId);
            if (titleClip == null || titleAsset == null || !isReadableOutput(titleAsset.uri)) {
                out.put("ok", false);
                out.put("error", "Title job completed without its readable owned canvas and committed clip");
                return out;
            }
            out.put("clipId", titleClip.id);
            out.put("assetId", titleAsset.id);
            out.put("revision", titleProject.revision);
            out.put("editableGlyphRenderer", "native_text_overlay");
            out.put("reused", jobResult.optBoolean("reused", false));
        }

        boolean requirePlayableOutput = ExecutionTruthPolicy.requiresValidatedMediaOutput(action) || "export_range".equals(action);
        if ("animate_images".equals(action)
                && queuedResult != null
                && !queuedResult.optBoolean("render", true)) {
            requirePlayableOutput = false;
        }
        if ("autonomous_edit".equals(action)
                && queuedResult != null
                && !queuedResult.optBoolean("render", false)) {
            requirePlayableOutput = false;
        }

        if (requirePlayableOutput) {
            ProjectStore.Project project = projectId == null || projectId.isEmpty()
                    ? null : store.get(projectId);
            if (project == null) {
                out.put("ok", false);
                out.put("error", "Native job completed but its project is missing");
                return out;
            }

            JSONObject committed = recoveryPlans.outputForJob(jobId);
            String outputUri = committed == null ? "" : committed.optString("uri", "");
            String outputName = committed == null ? "" : committed.optString("name", "");
            if (outputUri.isEmpty()) {
                long jobCreatedAt = job.optLong("createdAt", 0L);
                if (project.latestExportAt >= jobCreatedAt) {
                    outputUri = project.latestExportUri;
                    outputName = project.latestExportName;
                }
            }
            if (outputUri == null || outputUri.isEmpty() || !isReadableOutput(outputUri)) {
                out.put("ok", false);
                out.put("error", "Native job reached completed state without a readable published video");
                out.put("verifiedPlayableOutput", false);
                return out;
            }

            out.put("verifiedPlayableOutput", true);
            if (jobResult != null && jobResult.optJSONObject("nativeRender") != null)
                out.put("nativeRender", new JSONObject(jobResult.getJSONObject("nativeRender").toString()));
            else if (committed != null && committed.optJSONObject("evidence") != null)
                out.put("nativeRender", new JSONObject(committed.getJSONObject("evidence").toString()));
            out.put("outputUri", outputUri);
            out.put("outputName", outputName == null ? "" : outputName);
            out.put("projectId", project.id);
            out.put("assetCount", project.assets.size());
            out.put("clipCount", project.clips.size());
            JSONObject range = jobResult == null ? null : jobResult.optJSONObject("exportRange");
            out.put("durationMs", range == null ? project.outputDurationMs() : range.getLong("durationMs"));
            if (range != null) out.put("exportRange", new JSONObject(range.toString()));
        }
        return out;
    }

    private void finishDeferredCommand(JSONObject command,
                                       JSONObject result,
                                       String status,
                                       String projectId) {
        String commandId = command.optString("id", "");
        boolean ok = "completed".equals(status) && result.optBoolean("ok", false);
        String action = command.optString("action", "");
        String detail = ok
                ? completionDetail(action, result)
                : result.optString("error", "Native background work failed");
        ActivityLog.add(this, "chatgpt", friendlyAction(action), detail,
                ok ? "success" : "failed", ok ? 100 : null,
                commandId, projectId);
        commandJournal.finish(command, result, status);
        protocol.complete(command, result, status);
    }

    private void checkpoint(JobManager.Job state, String action, String detail, int progress, String projectId) {
        state.checkpoint(action, progress, detail);
        try {
            JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_EXPORT_SESSION, "{}"));
            if (state.id.equals(session.optString("jobId", ""))) publishManualExportStatus(
                    session.optString("sessionId", ""), projectId, state.id, "running", detail, progress);
        } catch (Exception ignored) {}
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
            prefs.edit().putString(ExecutionTruthPolicy.LIVE_JOB_PREF_KEY, live.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void refreshManualExportStatuses() {
        if (!serviceAlive || jobs == null || prefs == null) return;
        if (deferredPinnedRecovery) {
            deferredPinnedRecovery = false;
            recoverDurablePlans();
        }
        releaseTerminalExportPins();
        refreshSourceMediaStatuses();
        JSONArray retainedPlans = recoveryPlans.allPlans();
        for (int i = 0; i < retainedPlans.length(); i++) {
            JSONObject plan = retainedPlans.optJSONObject(i), parameters = plan == null ? null : plan.optJSONObject("parameters");
            if (parameters == null || !"export_project".equals(plan.optString("action")) || !"cancelled".equals(plan.optString("state"))
                    || plan.optJSONObject("publication") == null || !plan.optJSONObject("publication").optBoolean("published", false)) continue;
            String sessionId = parameters.optString("_manualExportSession", "");
            if (sessionId.isEmpty()) continue;
            try {
                JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_EXPORT_SESSION + ":" + sessionId, "{}"));
                if (!plan.optString("outputUri").equals(session.optString("outputUri")))
                    publishManualExportStatus(sessionId, plan.optString("projectId"), plan.optString("jobId"), "cancelled",
                            "Cancellation arrived after publication; the verified visible output is retained", session.optInt("progress", 98));
            } catch (Exception ignored) {}
        }
        for (String sessionId : watchedManualExports) {
            try {
                JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_EXPORT_SESSION + ":" + sessionId, "{}"));
                String status = session.optString("status", "queued");
                if ("completed".equals(status) || "cancelled".equals(status) || "failed".equals(status)) {
                    watchedManualExports.remove(sessionId);
                    continue;
                }
                String jobId = session.optString("jobId", "");
                JSONObject lookup = jobs.get(jobId);
                JSONObject job = lookup == null ? null : lookup.optJSONObject("job");
                if (job == null) continue;
                String jobState = job.optString("state", "queued");
                if ("failed".equals(jobState) || "cancelled".equals(jobState)) {
                    if ("cancelled".equals(jobState)) recoveryPlans.cancelByJob(jobId);
                    publishManualExportStatus(sessionId, session.optString("projectId", ""), jobId,
                            jobState, job.optString("detail", "Export interrupted"), job.optInt("progress", 0));
                    watchedManualExports.remove(sessionId);
                } else if (!"completed".equals(jobState)) {
                    String detail = job.optString("detail", "Waiting for the safe render lane");
                    int progress = job.optInt("progress", 0);
                    if (!jobState.equals(status) || !detail.equals(session.optString("detail", ""))
                            || progress != session.optInt("progress", 0)) {
                        publishManualExportStatus(sessionId, session.optString("projectId", ""), jobId,
                                jobState, detail, progress);
                    }
                }
            } catch (Exception ignored) {
                // A transient read must not stop monitoring other owner sessions.
            }
        }
    }

    private void refreshSourceMediaStatuses() {
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":") || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject session = new JSONObject((String) entry.getValue());
                String requestId = session.optString("requestId", ""), status = session.optString("status", "");
                if (entry.getKey().equals(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + requestId)
                        && (!sourceStatusTerminal(status) || "cancelled".equals(status) && !session.optBoolean("forgetReady", false))) watchedSourceMedia.add(requestId);
            } catch (Exception ignored) {}
        }
        for (String requestId : watchedSourceMedia) {
            try {
                JSONObject session = new JSONObject(prefs.getString(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + requestId, "{}"));
                String savedStatus = session.optString("status", "");
                if ("cancelled".equals(savedStatus)) {
                    JSONObject cancelledPlan = recoveryPlans.findByRequest("archive".equals(session.optString("operation")) ? "archive_source_media" : "restore_source_media", "_manualSourceSession", requestId);
                    if (sourceWorkerStopped(cancelledPlan)) {
                        publishSourceMediaStatus(requestId, session.optString("projectId"), session.optString("assetId"),
                                session.optString("operation"), session.optString("jobId"), "cancelled", session.optString("detail"), session.optInt("progress"), session.optJSONObject("receipt"));
                        watchedSourceMedia.remove(requestId);
                    }
                    continue;
                }
                if (sourceStatusTerminal(savedStatus)) { watchedSourceMedia.remove(requestId); continue; }
                String jobId = session.optString("jobId", "");
                JSONObject lookup = jobs.get(jobId), job = lookup == null ? null : lookup.optJSONObject("job");
                if (job == null) continue;
                String status = job.optString("state", "queued");
                if ("failed".equals(status)) {
                    JSONObject durable = recoveryPlans.findByRequest("archive".equals(session.optString("operation")) ? "archive_source_media" : "restore_source_media", "_manualSourceSession", requestId);
                    if (durable != null && !JobManager.isTerminal(durable.optString("state"))) status = "checkpointed";
                }
                JSONObject receipt = job.optJSONObject("result");
                if ("completed".equals(status)) {
                    if (receipt == null) receipt = recoveryPlans.resultForJob(jobId);
                    if (receipt == null || !receipt.optBoolean("archive".equals(session.optString("operation")) ? "committed" : "restored", false)) continue;
                }
                if ("cancelled".equals(status)) recoveryPlans.cancelByJob(jobId);
                String detail = job.optString("detail", "Waiting for safe native transfer lane");
                int progress = job.optInt("progress", 0);
                if (!status.equals(session.optString("status")) || !detail.equals(session.optString("detail"))
                        || progress != session.optInt("progress", 0)
                        || "checkpointed".equals(status) && session.optBoolean("resumeReady", false) != sourceResumeReady(requestId, session.optString("operation")))
                    publishSourceMediaStatus(requestId, session.optString("projectId"), session.optString("assetId"),
                            session.optString("operation"), jobId, status, detail, progress, receipt);
            } catch (Exception ignored) {}
        }
    }

    private boolean sourceResumeReady(String requestId, String operation) {
        JSONObject plan = recoveryPlans.findByRequest("archive".equals(operation) ? "archive_source_media" : "restore_source_media", "_manualSourceSession", requestId);
        if (plan == null || "completed".equals(plan.optString("state")) || "failed".equals(plan.optString("state"))) return false;
        JSONObject parameters = plan.optJSONObject("parameters");
        if (parameters == null || !parameters.optBoolean("_ownerInitiated", false) || !sourceWorkerStopped(plan)) return false;
        if ("archive".equals(operation)) {
            try {
                return store.readSourceArchivePin(parameters.optString("projectId"), parameters.optString("assetId"),
                        parameters.optString("_sourcePinId"), parameters.optString("_sourcePinOwner")) != null;
            } catch (Exception retained) { return false; }
        }
        return true;
    }

    private boolean sourceWorkerStopped(JSONObject plan) {
        JSONObject parameters = plan == null ? null : plan.optJSONObject("parameters");
        if (parameters == null) return false;
        JSONObject lookup = jobs.get(plan.optString("jobId", "")), job = lookup == null ? null : lookup.optJSONObject("job");
        if (job != null && !JobManager.isTerminal(job.optString("state")) && !JobManager.STATE_CHECKPOINTED.equals(job.optString("state"))) return false;
        return !runningDurableHeavyPlans.contains(plan.optString("id")) && !runningSourceRequests.contains(parameters.optString("requestId"))
                && !runningSourcePins.contains(parameters.optString("_sourcePinId"));
    }

    private static boolean sourceStatusTerminal(String status) {
        return JobManager.isTerminal(status) || "forgotten".equals(status);
    }

    private synchronized void publishSourceMediaStatus(String requestId, String projectId, String assetId,
                                                       String operation, String jobId, String status, String detail,
                                                       int progress, JSONObject receipt) {
        if (requestId == null || requestId.isEmpty()) return;
        try {
            UUID.fromString(requestId);
            String key = KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + requestId;
            JSONObject previous = new JSONObject(prefs.getString(key, "{}"));
            boolean sameJob = jobId != null && jobId.equals(previous.optString("jobId", ""));
            if (sameJob && sourceStatusTerminal(previous.optString("status", "")) && !status.equals(previous.optString("status"))) return;
            if (sameJob && "queued".equals(status) && !"queued".equals(previous.optString("status", "queued"))) return;
            JSONObject session = new JSONObject().put("requestId", requestId).put("projectId", projectId == null ? "" : projectId)
                    .put("assetId", assetId == null ? "" : assetId).put("operation", operation == null ? "" : operation)
                    .put("jobId", jobId == null ? "" : jobId).put("status", status).put("detail", detail == null ? "" : detail)
                    .put("progress", Math.max(0, Math.min(100, progress))).put("updatedAt", System.currentTimeMillis());
            session.put("resumeReady", ("cancelled".equals(status) || "checkpointed".equals(status)) && sourceResumeReady(requestId, operation));
            JSONObject retainedPlan = recoveryPlans.findByRequest("archive".equals(operation) ? "archive_source_media" : "restore_source_media", "_manualSourceSession", requestId);
            session.put("forgetReady", "cancelled".equals(status) && sourceWorkerStopped(retainedPlan));
            JSONObject actual = receipt == null ? previous.optJSONObject("receipt") : receipt;
            if (actual != null) session.put("receipt", new JSONObject(actual.toString())).put("generationId", actual.optString("generationId", ""));
            android.content.SharedPreferences.Editor edit = prefs.edit().putString(KEY_MANUAL_SOURCE_MEDIA_SESSION, session.toString())
                    .putString(key, session.toString());
            if (projectId != null && !projectId.isEmpty() && assetId != null && !assetId.isEmpty())
                edit.putString(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + projectId + ":" + assetId, session.toString());
            // Retain active sessions and at most forty terminal owner receipts.
            // Durable transfer receipts remain in RecoveryPlanStore/the vault.
            java.util.List<JSONObject> terminal = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
                if (!(item.getValue() instanceof String) || !item.getKey().startsWith(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":") || item.getKey().equals(key)) continue;
                try {
                    JSONObject old = new JSONObject((String) item.getValue());
                    if (item.getKey().equals(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + old.optString("requestId"))
                            && sourceStatusTerminal(old.optString("status"))) terminal.add(old);
                } catch (Exception ignored) {}
            }
            terminal.sort(java.util.Comparator.comparingLong(item -> item.optLong("updatedAt", 0L)));
            int remove = Math.max(0, terminal.size() + (sourceStatusTerminal(status) ? 1 : 0) - 40);
            for (int i = 0; i < remove; i++) {
                JSONObject old = terminal.get(i);
                edit.remove(KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + old.getString("requestId"));
                String assetKey = KEY_MANUAL_SOURCE_MEDIA_SESSION + ":" + old.optString("projectId") + ":" + old.optString("assetId");
                JSONObject alias = new JSONObject(prefs.getString(assetKey, "{}"));
                if (old.getString("requestId").equals(alias.optString("requestId"))
                        && !(projectId != null && projectId.equals(old.optString("projectId")) && assetId != null && assetId.equals(old.optString("assetId")))) edit.remove(assetKey);
            }
            if (!edit.commit()) throw new IllegalStateException("Source status could not be persisted");
            Intent update = new Intent(ACTION_LOCAL_SOURCE_MEDIA_STATUS).setPackage(getPackageName());
            update.putExtra("requestId", requestId).putExtra("projectId", projectId).putExtra("assetId", assetId)
                    .putExtra("operation", operation).putExtra("jobId", jobId).putExtra("status", status)
                    .putExtra("detail", detail).putExtra("progress", progress).putExtra("session", session.toString());
            sendBroadcast(update);
        } catch (Exception ignored) {}
    }

    private synchronized void publishManualExportStatus(String sessionId, String projectId, String jobId,
                                          String status, String detail, int progress) {
        if (sessionId == null || sessionId.isEmpty()) return;
        try {
            JSONObject previous = new JSONObject(prefs.getString(KEY_MANUAL_EXPORT_SESSION + ":" + sessionId, "{}"));
            String previousStatus = previous.optString("status", "");
            if (("completed".equals(previousStatus) || "cancelled".equals(previousStatus) || "failed".equals(previousStatus))
                    && !previousStatus.equals(status) && jobId != null && jobId.equals(previous.optString("jobId", ""))) return;
            if (sessionId.equals(previous.optString("sessionId")) && "queued".equals(status)
                    && jobId != null && jobId.equals(previous.optString("jobId", ""))
                    && !"queued".equals(previous.optString("status", "queued"))) return;
            JSONObject session = new JSONObject();
            session.put("sessionId", sessionId);
            session.put("projectId", projectId == null ? "" : projectId);
            session.put("jobId", jobId == null ? "" : jobId);
            session.put("status", status);
            session.put("detail", detail == null ? "" : detail);
            session.put("progress", progress);
            session.put("updatedAt", System.currentTimeMillis());
            JSONObject exportPlan = recoveryPlans.findByRequest("export_project", "_manualExportSession", sessionId);
            JSONObject exportParameters = exportPlan == null ? null : exportPlan.optJSONObject("parameters");
            if (exportParameters != null) {
                session.put("frameRate", exportParameters.optInt("frameRate", 30));
                session.put("exportMode", exportParameters.optString("exportMode", "full"));
                if ("range".equals(exportParameters.optString("exportMode"))) {
                    session.put("inMs", exportParameters.getLong("inMs")).put("outMs", exportParameters.getLong("outMs"))
                            .put("projectRevision", exportParameters.getLong("_exportPinnedRevision"));
                }
            }
            JSONObject durableOutput = recoveryPlans.outputForJob(jobId);
            if (durableOutput != null && !durableOutput.optString("uri", "").isEmpty()) {
                session.put("outputUri", durableOutput.getString("uri")).put("outputName", durableOutput.optString("name", ""));
                if ("cancelled".equals(status)) session.put("outputPublishedAfterCancellation", true);
            } else if (previous.has("outputUri")) {
                session.put("outputUri", previous.getString("outputUri")).put("outputName", previous.optString("outputName", ""));
                if (previous.has("outputPublishedAfterCancellation")) session.put("outputPublishedAfterCancellation", previous.getBoolean("outputPublishedAfterCancellation"));
            }
            prefs.edit().putString(KEY_MANUAL_EXPORT_SESSION, session.toString())
                    .putString(KEY_MANUAL_EXPORT_SESSION + ":" + sessionId, session.toString()).apply();
            Intent update = new Intent(ACTION_LOCAL_EXPORT_STATUS).setPackage(getPackageName());
            update.putExtra("sessionId", sessionId).putExtra("projectId", projectId)
                    .putExtra("jobId", jobId).putExtra("status", status).putExtra("detail", detail)
                    .putExtra("progress", progress);
            sendBroadcast(update);
        } catch (Exception ignored) {}
    }

    private String friendlyAction(String action) {
        if (action == null) return "ChatGPT action";
        switch (action) {
            case "create_project": return "Creating project";
            case "create_title": return "Creating editable title";
            case "cel_create": return "Drawing animation cel";
            case "cel_update": return "Redrawing animation cel";
            case "metadata_mirror_sync": return "Syncing project metadata";
            case "metadata_mirror_apply": return "Applying mirrored metadata";
            case "metadata_mirror_retry": return "Acknowledging native metadata";
            case "metadata_mirror_status": return "Reading pending metadata acknowledgement";
            case "metadata_mirror_revoke": return "Disabling project metadata mirror";
            case "metadata_mirror_keep_native": return "Resolving metadata conflict by keeping native edits";
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
            case "export_range": return "Exporting accepted program range";
            case "archive_source_media": return "Archiving original source media";
            case "restore_source_media": return "Restoring original source media";
            case "source_media_status": return "Reading original-source archive catalog";
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

