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
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ControlService extends Service implements AppProtocol.Callback {
    public static final String ACTION_CANCEL_ALL = "com.rezoxnemesis.videostudio.CANCEL_ALL";
    public static final String ACTION_CANCEL_JOB = "com.rezoxnemesis.videostudio.CANCEL_ONE_NATIVE_JOB";
    public static final String ACTION_PAUSE = "com.rezoxnemesis.videostudio.PAUSE_CONTROL";
    public static final String ACTION_RESUME = "com.rezoxnemesis.videostudio.RESUME_CONTROL";
    public static final String ACTION_RECONNECT = "com.rezoxnemesis.videostudio.RECONNECT";
    public static final String ACTION_SYNC = "com.rezoxnemesis.videostudio.SYNC_STATE";
    public static final String ACTION_LOCAL_ANIMATE = "com.rezoxnemesis.videostudio.LOCAL_ANIMATE_IMAGES";
    public static final String ACTION_LOCAL_ANIMATION_STUDIO = "com.rezoxnemesis.videostudio.LOCAL_ANIMATION_STUDIO";
    public static final String ACTION_LOCAL_COMBAT_BENCHMARK = "com.rezoxnemesis.videostudio.LOCAL_COMBAT_BENCHMARK";
    public static final String ACTION_LOCAL_RIGGED_2D = "com.rezoxnemesis.videostudio.LOCAL_RIGGED_COMBAT_2D";
    public static final String ACTION_LOCAL_STORYBOARD_ARTWORK = "com.rezoxnemesis.videostudio.LOCAL_STORYBOARD_ARTWORK";
    public static final String ACTION_LOCAL_STORYBOARD_SERIES = "com.rezoxnemesis.videostudio.LOCAL_STORYBOARD_SERIES";
    public static final String ACTION_LOCAL_GLTF_COMBAT = "com.rezoxnemesis.videostudio.LOCAL_GLTF_COMBAT_EXPORT";
    public static final String ACTION_LOCAL_CINEMATIC_3D = "com.rezoxnemesis.videostudio.LOCAL_CINEMATIC_3D_RENDER";
    public static final String ACTION_LOCAL_SKETCH_EXPORT = "com.rezoxnemesis.videostudio.LOCAL_SKETCH_EXPORT";
    public static final String ACTION_LOCAL_PROMPT_VIDEO = "com.rezoxnemesis.videostudio.LOCAL_PROMPT_VIDEO";
    public static final String ACTION_LOCAL_EXPORT = "com.rezoxnemesis.videostudio.LOCAL_EXPORT_PROJECT";
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
    private volatile NativeRenderEngine.Handle activeRender;
    private volatile String activeRenderJobId="";
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
    private final ExecutorService commandCompletionWatchers = Executors.newFixedThreadPool(2);
    private final ExecutorService directorSetupExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService nativeSourceExecutor=Executors.newFixedThreadPool(2);
    private final ExecutorService cinematic3DExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService authoringExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService pipelineExecutor=Executors.newSingleThreadExecutor();
    private final Set<String> watchedCommands = ConcurrentHashMap.newKeySet();
    private volatile boolean serviceAlive;

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
        reattachInflightCommandWatchers();
        NativeAgentWatchdog.scheduleHealthy(this, "service_created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CANCEL_JOB.equals(action)) {
            String jobId=intent==null?"":intent.getStringExtra("jobId");
            boolean cancelled=jobId!=null&&!jobId.isEmpty()&&jobs.cancel(jobId);
            if(cancelled){
                cancelOwnedRender(jobId);
                recoveryPlans.cancelByJob(jobId);
                ActivityLog.add(this,"user","Cancelled selected render",
                        "Native job "+jobId.substring(0,Math.min(8,jobId.length()))+
                                " cancelled; other jobs were preserved",
                        "success",null,null,null);
            }else{
                ActivityLog.add(this,"user","Selected native job not cancelled",
                        "Job already finished or no longer exists","info",null,null,null);
            }
            syncProtocolState();
        } else if (ACTION_CANCEL_ALL.equals(action)) {
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
          } else if (ACTION_LOCAL_GLTF_COMBAT.equals(action)) {
            final Intent req=intent;
            directorSetupExecutor.submit(()->{
                try{
                    JSONObject args=new JSONObject();
                    args.put("sourceProjectId",req.getStringExtra("projectId"));
                    args.put("blackAssetId",req.getStringExtra("blackAssetId"));
                    args.put("whiteAssetId",req.getStringExtra("whiteAssetId"));
                    args.put("durationSeconds",req.getDoubleExtra("durationSeconds",3.2));
                    args.put("fps",req.getIntExtra("fps",24));
                    args.put("width",req.getIntExtra("width",320));
                    args.put("height",req.getIntExtra("height",576));
                    args.put("storyboardMotion",true);
                    args.put("quality","720p");
                    args.put("render",true);
                    args.put("fileName","VideoStudio_RealGLB_3D_"+
                            System.currentTimeMillis()+".mp4");
                    JSONObject work=queueNativeGltf3D(args);
                    ActivityLog.add(this,"user","Genuine 3D combat export queued",
                            "GPU animated GLB models • "+work.optInt("expectedFrames",0)+" frames",
                            "queued",0,null,work.optString("projectId",""));
                }catch(Exception error){
                    ActivityLog.add(this,"user","3D combat export failed",
                            error.getMessage()==null?"Cannot export GLB":error.getMessage(),
                            "failed",null,null,req.getStringExtra("projectId"));
                }
            });
        } else if (ACTION_LOCAL_CINEMATIC_3D.equals(action)) {
            final Intent req=intent;
            directorSetupExecutor.submit(()->{
                try{
                    JSONObject work=queueCinematic3D(new JSONObject(req.getStringExtra("requestJson")));
                    ActivityLog.add(this,"user","Cinematic 3D render queued",work.optInt("expectedFrames")+" articulated frames","queued",0,null,work.optString("projectId"));
                }catch(Exception error){ActivityLog.add(this,"user","Cinematic 3D render failed",error.getMessage()==null?"Cannot render saved program":error.getMessage(),"failed",null,null,req.getStringExtra("projectId"));}
            });
        } else if (ACTION_LOCAL_STORYBOARD_SERIES.equals(action)) {
            final Intent userRequest=intent;
            directorSetupExecutor.submit(()->{
                try {
                    JSONObject settings=new JSONObject()
                            .put("sourceProjectId",userRequest.hasExtra("sourceProjectId")?userRequest.getStringExtra("sourceProjectId"):userRequest.getStringExtra("projectId"))
                            .put("genre",userRequest.getStringExtra("genre"))
                            .put("direction",userRequest.getStringExtra("direction"))
                            .put("backend",userRequest.getStringExtra("backend"))
                            .put("batchIndex",userRequest.getIntExtra("batchIndex",0))
                            .put("framesPerPair",userRequest.getIntExtra("framesPerPair",8))
                            .put("maxFramesPerBatch",20)
                            .put("fps",userRequest.getIntExtra("fps",24))
                            .put("quality","720p")
                            .put("deduplicateSourceVariants",true)
                            .put("foley",userRequest.getBooleanExtra("foley",true))
                            .put("render",true);
                    if(userRequest.hasExtra("programId"))settings.put("programId",userRequest.getStringExtra("programId"));
                    if(userRequest.hasExtra("expectedProgramSha256"))settings.put("expectedProgramSha256",userRequest.getStringExtra("expectedProgramSha256"));
                    if(userRequest.hasExtra("motionIntent"))settings.put("motionIntent",userRequest.getStringExtra("motionIntent"));
                    if(userRequest.hasExtra("width")&&userRequest.hasExtra("height"))settings.put("width",userRequest.getIntExtra("width",432)).put("height",userRequest.getIntExtra("height",768));
                    boolean analysis=userRequest.getBooleanExtra("analysisOnly",false);
                    JSONObject work=analysis?queueStoryboardSeriesAudit(settings)
                            :queueStoryboardSeries(settings);
                    ActivityLog.add(this,"user",
                            analysis?"Storyboard analysis started":"Real source-artwork animation queued",
                            work.optString("jobId","")+" · "+
                                    (analysis?"Checking trackable drawings":
                                            "Native animated layers + scene motion + Foley"),
                            "queued",0,null,work.optString("outputProjectId",
                                    settings.optString("sourceProjectId","")));
                    syncProtocolState();
                }catch(Exception error){
                    ActivityLog.add(this,"user","Storyboard director needs attention",
                            error.getMessage()==null?"Unable to start":error.getMessage(),
                            "failed",null,null,userRequest.getStringExtra("projectId"));
                }
            });
        } else if (ACTION_LOCAL_STORYBOARD_ARTWORK.equals(action)) {
            final Intent request=intent;
            directorSetupExecutor.submit(()->{
                try {
                    JSONObject params=new JSONObject()
                            .put("sourceProjectId",request.getStringExtra("projectId"))
                            .put("blackFromRigId",request.getStringExtra("blackFromRigId"))
                            .put("whiteFromRigId",request.getStringExtra("whiteFromRigId"))
                            .put("blackToRigId",request.getStringExtra("blackToRigId"))
                            .put("whiteToRigId",request.getStringExtra("whiteToRigId"))
                            .put("beat",request.getStringExtra("beat"))
                            .put("durationSeconds",request.getDoubleExtra("durationSeconds",3.0))
                            .put("width",432).put("height",768)
                            .put("fps",24).put("render",true)
                            .put("quality","720p")
                            .put("fileName","VideoStudio_Actual_Artwork_Action_"+
                                    System.currentTimeMillis()+".mp4");
                    JSONObject queued=queueStoryboardArtwork(params);
                    ActivityLog.add(this,"user","Source storyboard animation queued",
                            "Actual masked fighter textures; " +
                              queued.optInt("expectedFrames",0)+" independent motion samples",
                            "queued",0,null,queued.optString("outputProjectId",""));
                    syncProtocolState();
                }catch(Exception error){
                    ActivityLog.add(this,"user","Source storyboard animation not started",
                            error.getMessage()==null?"Missing source masks or unsupported scene":
                                    error.getMessage(),
                            "failed",null,null,request.getStringExtra("projectId"));
                }
            });
        } else if (ACTION_LOCAL_RIGGED_2D.equals(action)) {
            final Intent request=intent;
            directorSetupExecutor.submit(()->{
                try{
                    JSONObject params=new JSONObject();
                    params.put("sourceProjectId",request.getStringExtra("projectId"));
                    params.put("blackRigId",request.getStringExtra("blackRigId"));
                    params.put("whiteRigId",request.getStringExtra("whiteRigId"));
                    params.put("backgroundMode",request.getStringExtra("backgroundMode")==null
                            ?"procedural":request.getStringExtra("backgroundMode"));
                    params.put("durationSeconds",request.getDoubleExtra("durationSeconds",4.6));
                    params.put("fps",request.getIntExtra("fps",24));
                    params.put("width",request.getIntExtra("width",432));
                    params.put("height",request.getIntExtra("height",768));
                    params.put("render",true);
                    params.put("storyboardMotion",true);
                    params.put("quality","720p");
                    params.put("fileName","VideoStudio_Source_Rigged_2D_"+
                            System.currentTimeMillis()+".mp4");
                    JSONObject queued=queueRigged2D(params);
                    ActivityLog.add(this,"user","Source-textured 2D animation queued",
                            "Articulated source rigs • "+queued.optInt("expectedFrames",0)+" frames",
                            "queued",0,null,queued.optString("projectId",""));
                    syncProtocolState();
                }catch(Exception error){
                    ActivityLog.add(this,"user","Source-textured rig failed",
                            error.getMessage()==null?"Rig animation unavailable":error.getMessage(),
                            "failed",null,null,request.getStringExtra("projectId"));
                }
            });
        } else if (ACTION_LOCAL_COMBAT_BENCHMARK.equals(action)) {
            final Intent request=intent;
            directorSetupExecutor.submit(() -> {
                try {
                    JSONObject args=new JSONObject();
                    args.put("sourceProjectId",
                            request.getStringExtra("sourceProjectId") == null
                                    ? "" : request.getStringExtra("sourceProjectId"));
                    args.put("backgroundMode",request.getStringExtra("backgroundMode") == null
                            ? "procedural" : request.getStringExtra("backgroundMode"));
                    args.put("durationSeconds",request.getDoubleExtra("durationSeconds",3.6));
                    args.put("fps",request.getIntExtra("fps",24));
                    args.put("width",request.getIntExtra("width",540));
                    args.put("height",request.getIntExtra("height",960));
                    args.put("render",true);
                    args.put("quality","720p");
                    args.put("fileName","VideoStudio_True_Combat_Rig_"+
                            System.currentTimeMillis()+".mp4");
                    JSONObject queued=queueCombatRig(args);
                    ActivityLog.add(this,"user","True combat rig benchmark queued",
                            "Native articulated movement • "+queued.optInt("expectedFrames",0)+" frames",
                            "queued",0,null,queued.optString("projectId",""));
                    syncProtocolState();
                }catch(Exception error){
                    ActivityLog.add(this,"user","True combat rig benchmark failed",
                            error.getMessage()==null?"Failed to prepare combat simulation":error.getMessage(),
                            "failed",null,null,request.getStringExtra("sourceProjectId"));
                }
            });
        } else if (ACTION_LOCAL_ANIMATION_STUDIO.equals(action)) {
            // Decode/thumbnail planning must not block Android's service
            // main thread. Export itself uses the guarded heavy-work lane.
            final Intent request = intent;
            directorSetupExecutor.submit(() -> {
                try {
                    JSONObject parameters = new JSONObject();
                    parameters.put("projectId", request.getStringExtra("projectId"));
                    parameters.put("method", request.getStringExtra("method") == null
                            ? "direct" : request.getStringExtra("method"));
                    parameters.put("fps", request.getIntExtra("fps", 30));
                    parameters.put("quality", "720p");
                    parameters.put("render", true);
                    parameters.put("fileName", "VideoStudio_AnimationDirector_"
                            + System.currentTimeMillis() + ".mp4");
                    String impactText = request.getStringExtra("impactFrames");
                    if(impactText!=null && !impactText.trim().isEmpty()) {
                        JSONArray markers = new JSONArray();
                        for(String token : impactText.split(",")) {
                            if(!token.trim().isEmpty())
                                markers.put(Integer.parseInt(token.trim())-1);
                        }
                        parameters.put("impactIndices", markers);
                    }
                    JSONObject queued = queueAnimateTimeline(parameters);
                    ActivityLog.add(this, "user", "Animation Director queued",
                            queued.optString("method", "direct") + " • "
                                    + queued.optInt("sourceFrames", 0) + " frames",
                            "queued", 0, null, queued.optString("projectId", ""));
                    syncProtocolState();
                } catch(Exception e) {
                    ActivityLog.add(this, "user", "Animation Director failed",
                            e.getMessage() == null ? "Animation setup failed" : e.getMessage(),
                            "failed", null, null, request.getStringExtra("projectId"));
                }
            });
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
        } else if (ACTION_LOCAL_PROMPT_VIDEO.equals(action)) {
            try {
                JSONObject p = new JSONObject();
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
        } else if (ACTION_LOCAL_SKETCH_EXPORT.equals(action)) {
            final String request=intent.getStringExtra("requestJson");
            authoringExecutor.execute(()->{
                try {
                    JSONObject queued=queueSketchRender(new JSONObject(request));
                    ActivityLog.add(this,"user","Sketch export queued","Native Canvas and Media3 job", "queued",0,null,queued.optString("projectId",""));
                } catch(Exception failure) {
                    ActivityLog.add(this,"user","Sketch export failed",failure.getMessage()==null?"Cannot prepare sketch export":failure.getMessage(),"failed",null,null,null);
                }
            });
        } else if (ACTION_LOCAL_EXPORT.equals(action)) {
            try {
                JSONObject p = new JSONObject();
                p.put("projectId", intent.getStringExtra("projectId"));
                p.put("aspect", intent.getStringExtra("aspect") == null ? "9:16" : intent.getStringExtra("aspect"));
                p.put("quality", intent.getStringExtra("quality") == null ? "1080p" : intent.getStringExtra("quality"));
                p.put("fileName", intent.getStringExtra("fileName") == null
                        ? "VideoStudio_" + System.currentTimeMillis() + ".mp4"
                        : intent.getStringExtra("fileName"));
                JSONObject queued = queueExport(p);
                ActivityLog.add(this, "user", "Export queued",
                        "Native job " + shortId(queued.optString("jobId")),
                        "queued", 0, null, queued.optString("projectId", ""));
            } catch (Exception error) {
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
        if (protocol == null || NativeAgentWatchdog.shouldRearm(protocol.isControlPaused())) {
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
        if (activeRender != null) activeRender.cancel();
        if (jobs != null) jobs.shutdown();
        if (protocol != null) protocol.stop();
        commandCompletionWatchers.shutdownNow();
        directorSetupExecutor.shutdownNow();
        nativeSourceExecutor.shutdownNow();
        cinematic3DExecutor.shutdownNow();
        authoringExecutor.shutdownNow();
        pipelineExecutor.shutdownNow();
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
        if(command == null)return;
        String incomingAction = NativeWorkbenchBridge.canonicalAction(command.optString("action", ""));
        if(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                && NativeCommandExecutionPolicy.requiresBackgroundThread(incomingAction)) {
            try {
                // The remote relay may deliver the same lease again. AppProtocol's
                // bounded per-ID dispatch guard suppresses duplicate main-thread posts.
                // The durable journal remains the authority for replay/exactly-once work.
                (incomingAction.startsWith("cinematic_3d_") ? cinematic3DExecutor
                        : incomingAction.startsWith("open_pipeline_") ? pipelineExecutor
                        : incomingAction.startsWith("sketch_")||"precision_edit".equals(incomingAction)||"apply_tool".equals(incomingAction)||"set_studio_layers".equals(incomingAction) ? authoringExecutor : nativeSourceExecutor)
                        .execute(() -> onCommand(command));
            } catch(java.util.concurrent.RejectedExecutionException rejected) {
                JSONObject failed = new JSONObject();
                try { failed.put("ok",false).put("error",
                        "Native inspection worker unavailable; reconnect and retry"); }
                catch(Exception ignored){}
                complete(command,failed);
            }
            return;
        }
        if(!incomingAction.equals(command.optString("action",""))){
            try{onCommand(NativeWorkbenchBridge.canonicalCommand(command));}
            catch(Exception invalid){JSONObject failed=new JSONObject();try{failed.put("ok",false).put("error",invalid.getMessage());}catch(Exception ignored){}complete(command,failed);}
            return;
        }
        String action = incomingAction;
        JSONObject p = command.optJSONObject("parameters");
        if (p == null) p = new JSONObject();
        // Host payloads cannot supply frozen proofs, recovery identities or
        // trusted command metadata. Local recovery enters queue methods directly.
        JSONArray incomingFields = p.names();
        if (incomingFields != null) for (int index = 0; index < incomingFields.length(); index++) {
            String field = incomingFields.optString(index);
            if (field.startsWith("_")) p.remove(field);
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
        if (mayQueueBackgroundWork(action) && !commandId.isEmpty()) {
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
                case "precision_edit": complete(command,StudioPrecisionEdits.apply(this,store,p));syncProtocolState();return;
                case "sketch_create": case "sketch_list": case "sketch_get": case "sketch_edit":
                case "sketch_undo": case "sketch_redo": case "sketch_import_reference":
                    complete(command,SketchEditOps.execute(this,action,p));return;
                case "sketch_preview":{
                    JSONObject preview=SketchEditOps.execute(this,action,p);
                    // Existing MCP v3 relays already deliver contactSheet as an
                    // image. Rename, rather than duplicate, bounded PNG bytes.
                    Object canvas=preview.remove("sketchPreviewImage");if(canvas!=null)preview.put("contactSheet",canvas);
                    complete(command,preview);return;
                }
                case "sketch_render": complete(command,queueSketchRender(p));return;
                case "open_pipeline_configure": case "open_pipeline_capabilities": case "open_pipeline_list":
                case "open_pipeline_status": case "open_pipeline_cancel":
                    complete(command,OpenPipelineOperations.execute(this,store,action,p,null));return;
                case "open_pipeline_export_request": case "open_pipeline_submit": case "open_pipeline_download_result":
                case "open_pipeline_import_result": case "open_pipeline_archive_artifact": case "open_pipeline_restore_artifact":
                    complete(command,queueOpenPipeline(action,p));return;
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
                case "set_studio_layers":
                    complete(command, setStudioLayers(p));
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
                case "analyse_storyboard_motion":
                    queueStoryboardMotionAnalysis(command, p);
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
                case "migrate_legacy_cloud_projects":
                    complete(command, queueLegacyProjectImport(p));
                    return;
                case "archive_model_pack_to_drive":
                    complete(command, queueDriveModelPackArchive(p));
                    return;
                case "restore_model_pack_from_drive":
                    complete(command, queueDriveModelPackRestore(p));
                    return;
                case "animate_combat_rig":
                    complete(command,queueCombatRig(p));
                    return;
                case "render_rigged_2d":
                    complete(command,queueRigged2D(p));
                    return;
                case "cinematic_3d_capabilities": complete(command,cinematic3DCapabilities()); return;
                case "cinematic_3d_install_builtin": complete(command,NativeCinematicBundleStore.installBuiltin(this,store,p)); return;
                case "cinematic_3d_import_bundle": complete(command,NativeCinematicBundleStore.install(this,store,p)); return;
                case "cinematic_3d_save_program": complete(command,Cinematic3DProgramStore.save(this,store,p)); return;
                case "cinematic_3d_get_program": {
                    if(p.has("editOnly")&&!(p.opt("editOnly") instanceof Boolean))throw new IllegalArgumentException("editOnly must be boolean");
                    boolean edit=p.optBoolean("editOnly",false);JSONObject identity=new JSONObject(p.toString());identity.remove("editOnly");
                    complete(command,edit?Cinematic3DProgramStore.readForEdit(this,store,identity):Cinematic3DProgramStore.get(this,store,identity));return;
                }
                case "cinematic_3d_list_programs": complete(command,Cinematic3DProgramStore.list(this,store,p)); return;
                case "cinematic_3d_plan": complete(command,Cinematic3DProgramStore.plan(this,store,p)); return;
                case "cinematic_3d_export_request": complete(command,Cinematic3DProgramStore.exportRequest(this,store,p)); return;
                case "cinematic_3d_render": complete(command,queueCinematic3D(p)); return;
                case "animate_storyboard_artwork":
                    complete(command,queueStoryboardArtwork(p));
                    return;
                case "storyboard_artwork_preflight":
                    complete(command,storyboardArtworkPreflight(p));
                    return;
                case "storyboard_series_plan":
                    complete(command,planStoryboardSeries(p));
                    return;
                case "storyboard_series_audit":
                    complete(command,queueStoryboardSeriesAudit(p));
                    return;
                case "animate_storyboard_series":
                    complete(command,queueStoryboardSeries(p));
                    return;
                case "save_storyboard_program":
                    complete(command,new StoryboardProgramStore(this,store).save(p.getString("projectId"),
                            p.getLong("sourceProjectUpdatedAt"),p.optString("expectedProgramSha256",""),p.getJSONObject("program")));
                    return;
                case "get_storyboard_program":
                    if(p.has("editOnly")&&!(p.opt("editOnly") instanceof Boolean))throw new IllegalArgumentException("editOnly must be boolean");
                    StoryboardProgramStore programStore=new StoryboardProgramStore(this,store);
                    complete(command,p.optBoolean("editOnly",false)?programStore.readForEdit(p.getString("projectId"),p.getString("programId"))
                            :programStore.load(p.getString("projectId"),p.getString("programId")));
                    return;
                case "save_source_rig_profile":
                    complete(command, saveSourceRigProfile(p));
                    syncProtocolState();
                    return;
                case "inspect_source_artwork":
                    complete(command, NativeSourceArtworkInspector.inspect(this, store, p));
                    return;
                case "preview_source_rig":
                    complete(command, NativeSourceRigPreview.render(this, store, p));
                    return;
                case "save_source_rig_profiles": {
                    final int requestedProfiles = p.getJSONArray("profiles").length();
                    final String rigProjectId = p.getString("projectId");
                    complete(command, NativeRigBatchAuthoring.save(this, store, p, (index, receipt) ->
                            ActivityLog.add(this, "chatgpt", "Saving original source-character rigs",
                                    "Published rig " + (index + 1) + " of " + requestedProfiles,
                                    "running", Math.min(99, (index + 1) * 100 / requestedProfiles),
                                    commandId, rigProjectId)));
                    syncProtocolState();
                    return;
                }
                case "source_rig_status":
                    complete(command, NativeRigBatchAuthoring.status(this, store, p));
                    return;
                case "list_source_rig_profiles":
                    complete(command, listSourceRigProfiles(p));
                    return;
                case "render_gltf_combat":
                    complete(command,queueNativeGltf3D(p));
                    return;
                case "animate_timeline":
                    complete(command, queueAnimateTimeline(p));
                    return;
                case "animate_pose_sequence":
                    complete(command, queuePoseSequence(p));
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
                    if (cancelled) cancelOwnedRender(jobId);
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
                case "append_frame_chunk":
                    complete(command, appendFrameChunk(p));
                    return;
                case "finish_frame_transfer":
                    complete(command, finishFrameTransfer(p));
                    syncProtocolState();
                    return;
                case "frame_transfer_status":
                    complete(command, new NativeChunkedImport(getFilesDir())
                            .status(p.optString("projectId", ""), p.optString("transferId", "")));
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
            JSONObject result = error instanceof StoryboardMotionAdmission.Rejected
                    ? ((StoryboardMotionAdmission.Rejected) error).toJson() : new JSONObject();
            try {
                result.put("ok", false);
                result.put("error", error.getMessage() == null ? "Command failed" : error.getMessage());
            } catch (Exception ignored) {}
            ActivityLog.add(this, "chatgpt", friendlyAction(action), result.optString("error"), "failed", null, commandId, projectId);
            commandJournal.finish(command, result, "failed");
            protocol.complete(command, result, "failed");
        }
    }

    /**
     * Edit precisely the SAME independent Music/Caption layers available in
     * VideoStudio's human Editor. An expected revision prevents overwriting
     * an in-progress manual edit with a stale ChatGPT action.
     */
    private JSONObject setStudioLayers(JSONObject parameters) throws Exception {
        return StudioLayerContract.apply(store,parameters);
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
        ProjectStore.Project project=resolveProject(p.optString("projectId",""));
        int index=p.optInt("clipIndex",0);
        if(p.has("clipId")){
            index=-1;String id=p.getString("clipId");
            for(int i=0;i<project.clips.size();i++)if(id.equals(project.clips.get(i).id)){index=i;break;}
        }
        if(index<0||index>=project.clips.size())throw new IllegalArgumentException("Clip not found in the named project");
        ProjectStore.Clip clip=project.clips.get(index);
        String tool=p.optString("tool","effect");JSONObject settings=p.optJSONObject("settings");
        if(settings==null)settings=new JSONObject();
        JSONObject set=new JSONObject(),operation=new JSONObject().put("clipId",clip.id);
        switch(tool){
            case "trim":set.put("inMs",settings.has("inMs")?settings.get("inMs"):clip.inMs).put("outMs",settings.has("outMs")?settings.get("outMs"):clip.outMs);break;
            case "speed":case "slow_motion":set.put("speed",settings.has("speed")?settings.get("speed"):settings.has("value")?settings.get("value"):.5);break;
            case "volume":set.put("volume",settings.has("volume")?settings.get("volume"):settings.has("gain")?settings.get("gain"):1);break;
            case "transform":
                for(String key:new String[]{"scale","scaleX","scaleY","rotate","x","y","anchorX","anchorY","opacity"})if(settings.has(key))set.put(key,settings.get(key));break;
            case "color":case "colour":
                if(settings.has("preset"))set.put("colorPreset",settings.get("preset"));
                for(String key:new String[]{"brightness","contrast","saturationAdjust","lightnessAdjust"})if(settings.has(key))set.put(key,settings.get(key));
                if(settings.has("saturation"))set.put("saturationAdjust",settings.get("saturation"));
                if(settings.has("lightness"))set.put("lightnessAdjust",settings.get("lightness"));break;
            case "effect":
                String preset=settings.optString("preset","none");
                set.put(StudioPrecisionEdits.COLORS.contains(preset)?"colorPreset":"effectPreset",preset);break;
            case "motion":set.put("motionPreset",settings.optString("preset","none"));break;
            case "blur":set.put("blur",settings.has("sigma")?settings.get("sigma"):4);break;
            case "title":set.put("title",settings.optString("text",""));break;
            case "keyframes":operation.put("keyframes",settings.getJSONArray("keyframes"));break;
            case "green_screen":
                set.put("chromaKey",settings.has("enabled")?settings.get("enabled"):true)
                    .put("chromaColor",settings.has("color")?settings.get("color"):settings.optString("keyColor","#00FF00"));
                if(settings.has("tolerance"))set.put("chromaTolerance",settings.get("tolerance"));
                if(settings.has("feather"))set.put("chromaFeather",settings.get("feather"));break;
            case "mask":
                set.put("maskShape",settings.optString("shape","ellipse"));
                for(String key:new String[]{"maskLeft","maskTop","maskRight","maskBottom","maskFeather"})if(settings.has(key))set.put(key,settings.get(key));
                if(settings.has("feather"))set.put("maskFeather",settings.get("feather"));break;
            case "glow":
                set.put("glowStrength",settings.has("strength")?settings.get("strength"):1);
                if(settings.has("radiusPx"))set.put("glowRadiusPx",settings.get("radiusPx"));
                if(settings.has("threshold"))set.put("glowThreshold",settings.get("threshold"));break;
            default:throw new IllegalArgumentException("UNSUPPORTED_EFFECT: "+tool+" has no implemented native rendering path");
        }
        if(set.length()>0)operation.put("set",set);
        long revision=p.has("expectedUpdatedAt")?StudioClipKeyframes.integer(p,"expectedUpdatedAt",0,9_007_199_254_740_991L):project.updatedAt;
        JSONObject result=StudioPrecisionEdits.apply(this,store,new JSONObject().put("projectId",project.id).put("expectedUpdatedAt",revision).put("operations",new JSONArray().put(operation)));
        return result.put("clipId",clip.id).put("clipIndex",index).put("tool",tool);
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
        result.put("projectId", project.id);
        if (p.optBoolean("render", false)) {
            JSONObject exportArgs = new JSONObject();
            exportArgs.put("projectId", project.id);
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

    /**
     * Read only explicitly imported project images. Heavy frame synthesis is
     * never performed here; we report where pixel interpolation is unsafe.
     */
    private void queueStoryboardMotionAnalysis(JSONObject command,JSONObject p) {
        try {
            ProjectStore.Project project=resolveProject(p.optString("projectId",""));
            JSONArray provided=p.optJSONArray("anchorAssetIds");
            JSONArray ordered=new JSONArray();
            if(provided!=null) {
                for(int i=0;i<provided.length();i++)ordered.put(provided.getString(i));
            }else{
                for(ProjectStore.Clip clip:project.clips){
                    ProjectStore.Asset asset=project.asset(clip.assetId);
                    if(asset!=null&&asset.mime!=null&&asset.mime.startsWith("image/"))
                        ordered.put(asset.id);
                }
            }
            if(ordered.length()<2||ordered.length()>40)
                throw new IllegalArgumentException("Storyboard needs 2-40 ordered imported images");
            final JSONArray sourceIds=ordered;
            jobs.submit("Storyboard motion preflight • "+project.name,
                    JobManager.Kind.LIGHT,state -> {
                try {
                    checkpoint(state,"Storyboard analysis",
                            "Checking scene changes and motion correspondence",10,project.id);
                    JSONObject result=StoryboardMotionPreflight.inspect(
                            this,project,sourceIds);
                    checkpoint(state,"Storyboard analysis",
                            "Rig and reconstruction risks classified",100,project.id);
                    ActivityLog.add(this,"chatgpt","Storyboard motion preflight",
                            result.optInt("needsArticulatedRig",0)+" pair(s) need character rigging; "
                            +result.optInt("needsSceneReconstruction",0)+" need scene reconstruction",
                            "success",100,command.optString("id",""),project.id);
                    commandJournal.finish(command,result,"completed");
                    protocol.complete(command,result,"completed");
                }catch(Exception error){
                    JSONObject failed=new JSONObject();
                    failed.put("ok",false);
                    failed.put("error",error.getMessage()==null
                            ?"Motion assessment failed":error.getMessage());
                    commandJournal.finish(command,failed,"failed");
                    protocol.complete(command,failed,"failed");
                    throw error;
                }
            });
        }catch(Exception error){
            JSONObject failed=new JSONObject();
            try {
                failed.put("ok",false);
                failed.put("error",error.getMessage());
            }catch(Exception ignored){}
            commandJournal.finish(command,failed,"failed");
            protocol.complete(command,failed,"failed");
        }
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
        return submitRecoverableGuarded(action, parameters, projectId,
                jobName, JobManager.Kind.HEAVY, work);
    }

    /** Disk/Drive project transfers are serialized but need no GPU. */
    private JobManager.Job submitRecoverableTransfer(String action,
                                                     JSONObject parameters,
                                                     String projectId,
                                                     String jobName,
                                                     JobManager.Work work) {
        return submitRecoverableGuarded(action, parameters, projectId,
                jobName, JobManager.Kind.TRANSFER, work);
    }

    private JobManager.Job submitRecoverableGuarded(String action,
                                                   JSONObject parameters,
                                                   String projectId,
                                                   String jobName,
                                                   JobManager.Kind kind,
                                                   JobManager.Work work) {
        String requestedPlan = parameters == null ? "" : parameters.optString("_recoveryPlanId", "");
        String planId = requestedPlan;
        if (planId.isEmpty() || recoveryPlans.get(planId) == null) {
            planId = recoveryPlans.begin(action, parameters, projectId);
        } else {
            recoveryPlans.markResuming(planId);
        }
        final String durablePlanId = planId;
        JobManager.Job job = jobs.submit(jobName, kind, state -> {
            recoveryPlans.attachJob(durablePlanId, state.id);
            try {
                work.run(state);
                recoveryPlans.completeByJob(state.id);
            } catch (InterruptedException interrupted) {
                if ("cancelled".equals(state.state)) recoveryPlans.cancelByJob(state.id);
                else recoveryPlans.failByJob(state.id,
                        "Interrupted after checkpoint; safe to resume", true);
                throw interrupted;
            } catch (Exception error) {
                recoveryPlans.failByJob(state.id,
                        error.getMessage() == null ? "Recoverable background job failure" : error.getMessage(),
                        true);
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

            if (!outputUri.isEmpty() && isReadableOutput(outputUri) && !"sketch_render".equals(action)) {
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
                    case "sketch_render":
                        parameters.remove("projectId");queued=queueSketchRender(parameters);break;
                    case "open_pipeline_export_request": case "open_pipeline_submit": case "open_pipeline_download_result":
                    case "open_pipeline_import_result": case "open_pipeline_archive_artifact": case "open_pipeline_restore_artifact":
                        if(!"open_pipeline_export_request".equals(action))parameters.remove("projectId");
                        queued=queueOpenPipeline(action,parameters);break;
                    case "animate_combat_rig":
                        queued = queueCombatRig(parameters);
                        break;
                    case "render_rigged_2d":
                        queued = queueRigged2D(parameters);
                        break;
                    case "storyboard_series_audit":
                        queued = queueStoryboardSeriesAudit(parameters);
                        break;
                    case "animate_storyboard_artwork":
                        queued = queueStoryboardArtwork(parameters);
                        break;
                    case "animate_storyboard_series":
                        queued = queueStoryboardSeries(parameters);
                        break;
                    case "cinematic_3d_render":
                        queued = queueCinematic3D(parameters);
                        break;
                    case "render_gltf_combat":
                        queued = queueNativeGltf3D(parameters);
                        break;
                    case "animate_timeline":
                        queued = queueAnimateTimeline(parameters);
                        break;
                    case "animate_pose_sequence":
                        queued = queuePoseSequence(parameters);
                        break;
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
                    case "migrate_legacy_cloud_projects":
                        queued = queueLegacyProjectImport(parameters);
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

    private JSONObject queueDriveProjectOffload(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        if (!driveWorkspace.isLinked()) {
            throw new IllegalStateException("No folder-scoped cloud workspace is linked. Link one from VideoStudio > Control.");
        }

        JSONObject durableParameters = new JSONObject(p.toString());
        durableParameters.put("projectId", project.id);

        JobManager.Job job = submitRecoverableTransfer(
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
                            (progress, detail) -> {
                                checkpoint(state, "Cloud offload", detail, Math.max(2, Math.min(86, (int) (2 + progress * .84))), project.id);
                                // File boundary: check memory and critical thermal
                                // before copying the next archived media item.
                                jobs.awaitSafeCheckpoint(state, state.stage);
                            }
                    );
                    if (!archived.optBoolean("ok", false)) {
                        throw new IllegalStateException("Cloud archive verification did not complete");
                    }
                    jobs.awaitSafeCheckpoint(state, "cloud_offload_commit");
                    checkpoint(state, "Cloud offload", "Archive verified • evicting only cloud-backed intermediates", 90, project.id);
                    JSONObject evicted;
                    // Restore takes the provider before the SQLite writer; use
                    // the same order so concurrent restore cannot deadlock offload.
                    synchronized(driveWorkspace){evicted=store.guardedProject(project.id,project.updatedAt,current->{
                        JSONObject evictionProof=driveWorkspace.verifyArchiveForEviction(current,workspace,archived);
                        if(!evictionProof.optBoolean("verified",false)||!evictionProof.optBoolean("evictionEligible",false))
                            throw new IllegalStateException("Cloud archive cannot verify this local generation; working files retained");
                        return creativeWorkspace.evictCloudBackedProject(current.id);
                    });}
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

        JobManager.Job job = submitRecoverableTransfer(
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
                            (progress, detail) -> {
                                checkpoint(state, "Cloud restore", detail, Math.max(2, Math.min(100, progress)), project.id);
                                // File boundary: check memory and critical thermal
                                // before copying the next archived media item.
                                jobs.awaitSafeCheckpoint(state, state.stage);
                            }
                    );
                    if(!restored.optBoolean("verified",false))throw new IllegalStateException("Cloud restore has no verified generation receipt");
                    creativeWorkspace.markCloudHydrated(project.id);
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

    /**
     * Recover exact, fully backed up legacy projects from the one folder
     * selected in Android's document-tree picker. The previous VideoStudio
     * installation and its local user data are never accessed or modified.
     */
    private JSONObject queueLegacyProjectImport(JSONObject p) throws Exception {
        if (!driveWorkspace.isLinked())
            throw new IllegalStateException("Link your old VideoStudio cloud folder first.");
        JSONObject durable=new JSONObject(p.toString());
        JobManager.Job job=submitRecoverableTransfer(
                "migrate_legacy_cloud_projects",durable,"",
                "Recover older VideoStudio projects",state->{
                    checkpoint(state,"Cloud migration","Discovering user-approved archives",2,"");
                    JSONObject report=new LegacyCloudRecovery(this,store).restoreAll(
                            (percent,detail)->{
                                checkpoint(state,"Cloud migration",detail,
                                        Math.max(2,Math.min(100,percent)),"");
                                jobs.awaitSafeCheckpoint(state,"migration_file_checkpoint");
                            });
                    state.setResult(report);
                    ActivityLog.add(this,"system","Legacy archive migration checked",
                            report.optInt("imported",0)+" project(s) restored, "+
                            report.optInt("skipped",0)+" still missing sources",
                            report.optInt("skipped",0)>0?"info":"success",100,null,null);
                    syncProtocolState();
                });
        JSONObject result=ok();
        result.put("queued",true);
        result.put("durableRecovery",true);
        result.put("jobId",job.id);
        result.put("archiveScoped",true);
        result.put("oldAppUnchanged",true);
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

        JobManager.Job job = submitRecoverableTransfer(
                "archive_model_pack_to_drive",
                durableParameters,
                "",
                "Archive model pack • " + packId,
                state -> {
                    checkpoint(state, "Cloud model archive", "Preparing installed model pack", 2, "");
                    JSONObject archived = driveWorkspace.syncModelPack(
                            packId,
                            pack,
                            (progress, detail) -> {
                                checkpoint(state, "Cloud model archive", detail, Math.max(2, Math.min(100, progress)), "");
                                // File boundary: check memory and critical thermal
                                // before copying the next archived media item.
                                jobs.awaitSafeCheckpoint(state, state.stage);
                            }
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

        JobManager.Job job = submitRecoverableTransfer(
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
                            (progress, detail) -> {
                                checkpoint(state, "Cloud archive", detail, Math.max(2, Math.min(100, progress)), project.id);
                                // File boundary: check memory and critical thermal
                                // before copying the next archived media item.
                                jobs.awaitSafeCheckpoint(state, state.stage);
                            }
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

    
    /**
     * New v3 command: animate_pose_sequence
     *
     * Only explicitly imported VideoStudio project assets are accepted.
     * The output is a separate native project containing real intermediate
     * frame samples (not independent Ken Burns motions on static images).
     */
    /**
     * One native action for EXISTING ordered image projects. No cloud frames,
     * no Gallery enumeration, no mandatory manual 40-step edit plan.
     *
     * method=direct (recommended): uses original drawn poses, quality-safe
     * impact pacing and Media3 native output. method=flow (experimental):
     * creates genuine motion-warped intermediate pixel frames via the pose
     * composer, with background/occlusion guards.
     */
    private JSONObject queueAnimateTimeline(JSONObject p) throws Exception {
        ProjectStore.Project source=resolveProject(p.optString("projectId", ""));
        String method=p.optString("method", "direct").toLowerCase(Locale.US);
        if(!"direct".equals(method) && !"flow".equals(method))
            throw new IllegalArgumentException("Animation method must be direct or flow");
        int fps=p.optInt("fps",30);
        JSONArray impactIndices=p.optJSONArray("impactIndices");
        NativeAnimationDirector.Plan plan=NativeAnimationDirector.plan(
                this, source, fps, impactIndices);
        plan.method=method;
        boolean render=p.optBoolean("render",true);
        String quality="1080p".equals(p.optString("quality","720p"))?"1080p":"720p";
        String fileName=sanitizeFileName(p.optString("fileName",
                "VideoStudio_AnimationDirector_"+System.currentTimeMillis()+".mp4"));
        JSONObject result;
        if("flow".equals(method)) {
            if(plan.sourceIds.size()>40)
                throw new IllegalArgumentException(
                        "Experimental flow supports up to 40 anchors; use direct animation for larger sequences");
            JSONObject args=new JSONObject();
            args.put("projectId",source.id);
            args.put("sourceProjectId",source.id);
            JSONArray ordered=new JSONArray();
            for(String id:plan.sourceIds) ordered.put(id);
            args.put("anchorAssetIds",ordered);
            args.put("framesByPair",NativeAnimationDirector.recommendedFlowSteps(plan));
            args.put("fps",fps);
            args.put("width",p.optInt("width",432));
            args.put("height",p.optInt("height",768));
            args.put("quality",quality);
            args.put("render",render);
            args.put("fileName",fileName);
            result=queuePoseSequence(args);
        } else {
            ProjectStore.Project output=
                    NativeAnimationDirector.createDirectProject(store,source,plan);
            if(render) {
                JSONObject args=new JSONObject();
                args.put("projectId",output.id);
                args.put("aspect","9:16");
                args.put("quality",quality);
                args.put("fileName",fileName);
                result=queueExport(args);
            } else {
                result=ok();
                result.put("queued",false);
                result.put("projectId",output.id);
            }
            result.put("durationMs",output.outputDurationMs());
        }
        result.put("method",method);
        result.put("sourceProjectId",source.id);
        result.put("sourceFrames",plan.sourceIds.size());
        result.put("render",render);
        result.put("animationPlan",plan.toJson());
        result.put("cameraMovement",false);
        result.put("neuralMotionModelInstalled",false);
        result.put("experimentalFlow","flow".equals(method));
        return result;
    }

    /**
     * True geometric animation benchmark: separate skeletons, analytical
     * IK, planted contact feet, continuous sword arcs and a fixed background.
     * This deliberately does NOT claim to reconstruct the uploaded artwork or
     * to run a neural video-generation model. The original project, if named,
     * is used only to identify the intended benchmark, not as a background.
     */
    private JSONObject queueCombatRig(JSONObject p) throws Exception {
        String sourceId=p.optString("sourceProjectId","");
        ProjectStore.Project source=sourceId.isEmpty()?null:resolveProject(sourceId);
        String backgroundMode=p.optString("backgroundMode","procedural");
        if(!"procedural".equals(backgroundMode) && !"source_median".equals(backgroundMode))
            throw new IllegalArgumentException("Background mode must be procedural or source_median");
        if("source_median".equals(backgroundMode) && source==null)
            throw new IllegalArgumentException("Estimated source background requires a selected image project");
        int fps=p.optInt("fps",24);
        int width=p.optInt("width",540),height=p.optInt("height",960);
        double duration=p.optDouble("durationSeconds",3.6);
        if(fps!=24 && fps!=30)throw new IllegalArgumentException("FPS must be 24 or 30");
        if(width<320||height<540||width>720||height>1280||width%2!=0||height%2!=0)
            throw new IllegalArgumentException("Choose an even portrait size up to 720x1280");
        if(!Double.isFinite(duration)||duration<2||duration>5)
            throw new IllegalArgumentException("Combat duration must be 2 to 5 seconds");
        String quality="1080p".equals(p.optString("quality","720p"))?"1080p":"720p";
        String fileName=sanitizeFileName(p.optString("fileName",
                "VideoStudio_True_Combat_"+System.currentTimeMillis()+".mp4"));
        boolean render=p.optBoolean("render",true);
        boolean storyboardMotion=p.optBoolean("storyboardMotion",false);
        String outputId=p.optString("outputProjectId","");
        ProjectStore.Project output;
        if(outputId.isEmpty()){
            output=store.create("True Combat Rig • "+
                    (source==null?"Movement Benchmark":source.name));
            output.sourcePrompt="combat-rig: true articulated stickman animation with fixed procedural world";
            store.save(output);
        } else {
            output=store.get(outputId);
            if(output==null||output.sourcePrompt==null||
                    !output.sourcePrompt.startsWith("combat-rig:"))
                throw new IllegalArgumentException("Invalid combat recovery project");
        }
        final ProjectStore.Project target=output;
        final ProjectStore.Project reference=source;
        final String selectedBackground=backgroundMode;
        final boolean useStoryboardMotion=storyboardMotion;
        final int outWidth=width,outHeight=height,frames=fps;
        final double seconds=duration;
        JSONObject durable=new JSONObject(p.toString());
        durable.put("projectId",target.id);
        durable.put("outputProjectId",target.id);
        durable.put("sourceProjectId",sourceId);
        durable.put("fps",fps);
        durable.put("backgroundMode",backgroundMode);
        durable.put("storyboardMotion",storyboardMotion);
        durable.put("width",width);
        durable.put("height",height);
        durable.put("durationSeconds",duration);
        durable.put("render",render);
        durable.put("fileName",fileName);
        durable.put("quality",quality);
        durable.put("aspect","9:16");
        JobManager.Job job=submitRecoverableHeavy("animate_combat_rig",
                durable,target.id,"True Combat Rig • "+target.name,state -> {
                    checkpoint(state,"Combat rig","Solving joints and sword timing in a locked world",3,target.id);
                    jobs.awaitSafeCheckpoint(state,"combat_rig_setup");
                    android.graphics.Bitmap plate=null;
                    try {
                        if("source_median".equals(selectedBackground)) {
                            checkpoint(state,"Scene stabilizer",
                                    "Reconstructing a fixed environment from imported frames",4,target.id);
                            jobs.awaitSafeCheckpoint(state,"clean_plate_prepare");
                            plate=NativeScenePlateBuilder.fromProject(
                                    this,reference,outWidth,outHeight);
                        }
                        final android.graphics.Bitmap fixedBackdrop=plate;
                        NativeCombatRigComposer.compose(this,store,target,outWidth,outHeight,
                                frames,seconds,fixedBackdrop,useStoryboardMotion,
                                (current,total,time,impact)->{
                                    if(current%6==0||current==total||current==1){
                                        int progress=5+(int)(57.0*current/Math.max(1,total));
                                        checkpoint(state,"Combat rig",
                                                current+"/"+total+" independent articulated frames",
                                                progress,target.id);
                                        jobs.awaitSafeCheckpoint(state,"combat_frame_"+current);
                                    }
                                });
                    } finally {
                        if(plate!=null&&!plate.isRecycled())plate.recycle();
                    }
                    JSONObject details=new JSONObject();
                    details.put("engine","articulated-two-bone-IK-v1");
                    details.put("sourceImageRestyling",false);
                    details.put("cameraLocked",true);
                    details.put("footPlanting",true);
                    details.put("swordContact",true);
                    details.put("impactRetiming",true);
                    details.put("storyboardMotion",useStoryboardMotion);
                    details.put("neuralVideoGenerator",false);
                    details.put("generatedFrames",target.clips.size());
                    details.put("backgroundMode",selectedBackground);
                    details.put("sourceProjectUnmodified",true);
                    state.setResult(details);
                    syncProtocolState();
                    checkpoint(state,"Combat rig","Native motion frames ready for Media3",64,target.id);
                    if(render){
                        jobs.awaitSafeCheckpoint(state,"combat_native_render");
                        runExportBlocking(target,"9:16",quality,fileName,state);
                        checkpoint(state,"Combat rig","Verified native MP4 exported",100,target.id);
                    }else{
                        checkpoint(state,"Combat rig","Editable articulated frames ready",100,target.id);
                    }
                });
        JSONObject result=ok();
        result.put("queued",true);
        result.put("durableRecovery",true);
        result.put("jobId",job.id);
        result.put("projectId",target.id);
        result.put("outputProjectId",target.id);
        result.put("sourceProjectId",sourceId);
        result.put("expectedFrames",(int)Math.round(duration*fps));
        result.put("render",render);
        result.put("engine","articulated-two-bone-IK-v1");
        result.put("storyboardMotion",storyboardMotion);
        result.put("background","source_median".equals(backgroundMode)
                ?"fixed-estimated-source-scene":"fixed-procedural-city");
        result.put("backgroundMode",backgroundMode);
        result.put("sourceImageRestyling",false);
        result.put("neuralVideoGenerator",false);
        return result;
    }


    /**
     * New VIDEO STUDIO NATIVE vertical slice: from two user-authored source-art
     * cutout rigs to an independently articulated 2D mesh video.
     *
     * This cannot guess masks, joints, backsides or 3D body meshes from raw
     * screenshots. The Android rig editor is the one-time authoring step.
     */

    /** Fully native GPU / glTFio headless 3D render, not a still-image morph. */
    /**
     * Shared, editable choreography timing. An authored beat map changes
     * action timing without uploading a single new image.
     */
    private static CombatActionPlan combatDirector(JSONObject p) throws Exception {
        JSONArray raw=p.optJSONArray("actionBeatTimes");
        if(raw==null)return new CombatActionPlan();
        if(raw.length()!=CombatActionPlan.BEAT_COUNT+1)
            throw new IllegalArgumentException("Need nine directed combat beat timestamps");
        double[] points=new double[raw.length()];
        for(int i=0;i<points.length;i++)points[i]=raw.getDouble(i);
        return new CombatActionPlan(points);
    }

    private JSONObject queueNativeGltf3D(JSONObject p) throws Exception {
        ProjectStore.Project source=resolveProject(p.optString(
                "sourceProjectId",p.optString("projectId","")));
        String blackId=p.optString("blackAssetId","");
        String whiteId=p.optString("whiteAssetId","");
        ProjectStore.Asset black=source.asset(blackId);
        ProjectStore.Asset white=source.asset(whiteId);
        if(black==null||white==null)
            throw new IllegalArgumentException("Select both VideoStudio GLB actors");
        if(blackId.equals(whiteId))
            throw new IllegalArgumentException("Choose two distinct 3D model assets");
        if(!"model/gltf-binary".equals(black.mime)||
                !"model/gltf-binary".equals(white.mime))
            throw new IllegalArgumentException("Both fighters must be pre-rigged GLB files");
        // Validate without touching unrelated device media.
        NativeGlbCombatComposer.glbFile(this,source,black);
        NativeGlbCombatComposer.glbFile(this,source,white);
        int fps=p.optInt("fps",24);
        int width=p.optInt("width",320),height=p.optInt("height",576);
        double seconds=p.optDouble("durationSeconds",3.2);
        if(fps!=24&&fps!=30||!Double.isFinite(seconds)||seconds<2||seconds>4
                ||width<256||width>540||height<448||height>960||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("Use a bounded 2-4-second 24/30 FPS portrait export");
        boolean storyboard=p.optBoolean("storyboardMotion",true);
        boolean render=p.optBoolean("render",true);
        String quality="1080p".equals(p.optString("quality","720p"))
                ?"1080p":"720p";
        String filename=sanitizeFileName(p.optString("fileName",
                "VideoStudio_GLB_3D_"+System.currentTimeMillis()+".mp4"));
        String outputId=p.optString("outputProjectId","");
        ProjectStore.Project output;
        if(outputId.isEmpty()){
            output=store.create("Real GLB 3D • "+source.name);
            output.sourcePrompt="gltf3d: independent skinned mesh animation";
            store.save(output);
        }else{
            output=store.get(outputId);
            if(output==null||!output.sourcePrompt.startsWith("gltf3d:"))
                throw new IllegalArgumentException("Invalid GPU export recovery project");
        }
        final ProjectStore.Project original=source,target=output;
        final ProjectStore.Asset b=black,w=white;
        final CombatActionPlan director=combatDirector(p);
        JSONObject durable=new JSONObject(p.toString());
        durable.put("projectId",output.id);
        durable.put("outputProjectId",output.id);
        durable.put("sourceProjectId",source.id);
        durable.put("blackAssetId",blackId);
        durable.put("whiteAssetId",whiteId);
        durable.put("fps",fps);
        durable.put("width",width);durable.put("height",height);
        durable.put("durationSeconds",seconds);
        durable.put("render",render);
        durable.put("storyboardMotion",storyboard);
        durable.put("aspect","9:16");
        durable.put("quality",quality);durable.put("fileName",filename);
        JobManager.Job job=submitRecoverableHeavy("render_gltf_combat",durable,
                output.id,"3D skinned fight • "+source.name,state->{
            checkpoint(state,"3D GLB combat","Preparing private GLB skeleton assets",
                    2,target.id);
            jobs.awaitSafeCheckpoint(state,"gpu_glb_prepare");
            NativeGlbCombatComposer.compose(this,store,original,target,b,w,
                    width,height,fps,seconds,storyboard,director,
                    (done,total,time,impact)->{
                        if(done==1||done%3==0||done==total){
                            int pct=4+(int)(60d*done/Math.max(1,total));
                            checkpoint(state,"3D GLB combat",
                                    done+"/"+total+" headless GPU skinned frames",
                                    pct,target.id);
                            jobs.awaitSafeCheckpoint(state,"gpu_glb_frame_"+done);
                        }
                    });
            JSONObject details=new JSONObject();
            details.put("engine","Filament gltfio headless + Media3 native");
            details.put("real3DGeometry",true);
            details.put("modelCharactersImported",2);
            details.put("preauthoredGltfAnimationsRequired",true);
            details.put("rootMotionDirectedByCombatSolver",true);
            details.put("actionDirector","shared-eight-beat-timeline-v1");
            details.put("semanticClipFallbackPossible",true);
            details.put("actualSoundtrackAttached",false);
            details.put("exactBladeMeshCollisionSolved",false);
            details.put("automaticImageTo3D",false);
            details.put("frames",target.clips.size());
            state.setResult(details);
            syncProtocolState();
            if(render){
                jobs.awaitSafeCheckpoint(state,"gpu_glb_media3");
                runExportBlocking(target,"9:16",quality,filename,state);
                checkpoint(state,"3D GLB combat","Verified genuine 3D MP4 exported",
                        100,target.id);
            }else{
                checkpoint(state,"3D GLB combat","Editable GPU frames ready",100,target.id);
            }
        });
        JSONObject result=ok();
        result.put("queued",true);result.put("durableRecovery",true);
        result.put("jobId",job.id);
        result.put("projectId",output.id);
        result.put("sourceProjectId",source.id);
        result.put("outputProjectId",output.id);
        result.put("expectedFrames",(int)Math.round(seconds*fps));
        result.put("engine","Filament gltfio GPU skinned 3D");
        result.put("render",render);
        result.put("requiresRigged3DAssets",true);
        return result;
    }

    /**
     * ChatGPT can now author genuine source-textured 2D actor rigs through the
     * private native command channel. The API accepts only bounded joint and
     * outline coordinates for an already-imported project image; no Gallery
     * lookup, filesystem path, external URI, or remote model is accepted.
     *
     * The project's revision is mandatory: two collaborators cannot silently
     * attach a rig to a stale or replaced source clip.
     */
    private JSONObject saveSourceRigProfile(JSONObject p) throws Exception {
        String projectId = p.optString("projectId", "");
        if (projectId.isEmpty()) throw new IllegalArgumentException("Project ID is required");
        ProjectStore.Project project = store.get(projectId);
        if (project == null) throw new IllegalArgumentException("Source project was not found");
        if (!p.has("sourceProjectUpdatedAt")
                || p.getLong("sourceProjectUpdatedAt") != project.updatedAt) {
            throw new IllegalArgumentException("Source project revision changed; refresh get_state before rig authoring");
        }
        String sourceAssetId = p.optString("sourceAssetId", "");
        ProjectStore.Asset sourceAsset = project.asset(sourceAssetId);
        if (sourceAsset == null || sourceAsset.mime == null
                || !sourceAsset.mime.startsWith("image/")
                || sourceAsset.uri == null || sourceAsset.uri.isEmpty()) {
            throw new IllegalArgumentException("Choose an existing imported VideoStudio image asset");
        }
        Rig2DProfile rig = NativeRigAuthoring.parse(project.id, sourceAsset.id, p);
        JSONObject binding = new Rig2DProjectStore(this, store).saveWithBinding(rig,
                p.getLong("sourceProjectUpdatedAt"),p.optString("expectedRigSha256",""));
        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("sourceAssetId", sourceAsset.id);
        result.put("sourceProjectUpdatedAt", project.updatedAt);
        result.put("rigId", rig.id);
        result.put("black", rig.black);
        result.put("jointCount", rig.joints.length);
        result.put("outlineCount", rig.outline.length);
        result.put("storedInPrivateProjectWorkspace", true);
        result.put("binding",binding);
        result.put("sha256",binding.getString("sha256"));
        result.put("galleryAccess", false);
        return result;
    }

    /** Read back only masks belonging to one explicitly selected native project. */
    private JSONObject listSourceRigProfiles(JSONObject p) throws Exception {
        String projectId = p.optString("projectId", "");
        if (projectId.isEmpty() || store.get(projectId) == null) {
            throw new IllegalArgumentException("Known project ID is required");
        }
        java.util.List<Rig2DProfile> all=new Rig2DProjectStore(this,store).list(projectId);
        int offset=p.has("offset")?StoryboardMotionAdmission.integer(p,"offset",0,100):0;
        int limit=p.has("limit")?StoryboardMotionAdmission.integer(p,"limit",1,8):8;
        JSONArray profiles = new JSONArray();
        int next=Math.min(all.size(),offset);
        while(next<all.size()&&profiles.length()<limit) {
            JSONObject row=all.get(next).toJson().put("sha256",Rig2DProjectStore.sha256(all.get(next)));
            if(profiles.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>192*1024)break;
            profiles.put(row);next++;
        }
        JSONObject result = ok();
        result.put("projectId", projectId);
        result.put("rigs", profiles);
        result.put("count", profiles.length());
        result.put("totalCount",all.size());result.put("offset",offset);
        result.put("nextOffset",next<all.size()?next:JSONObject.NULL);
        result.put("galleryAccess", false);
        return result;
    }

    /**
     * Cheap, owner-scoped inventory for the next animation shot.
     * Reports exact four-rig pairs; NEVER silently reroutes to optical-flow
     * or a generic 3D skeleton when the user's actual artwork is not marked.
     */
    private JSONObject storyboardArtworkPreflight(JSONObject input) throws Exception {
        JSONObject p=storyboardParameters(input,"",false);
        ProjectStore.Project source=existingStoryboardSource(p);
        JSONObject manifest=StoryboardSourceManifest.capture(this,source,p);
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,p,manifest);
        return ok().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("sourceManifest",manifest)
                .put("motionAdmission",StoryboardMotionAdmission.inspect(source,plan,p.optInt("batchIndex",0),
                        new Rig2DProjectStore(this,store).list(source.id),StoryboardMotionAdmission.intent(p),p).toJson())
                .put("cinematicAcceptance",false).put("neuralMotionProviderInstalled",false).put("galleryAccess",false);
    }

    private static float[] requiredStoryboardTip(JSONObject p,String name,
                                                  Rig2DProfile source) throws Exception {
        JSONArray values=p.optJSONArray(name);
        if(values==null && source!=null && source.swordTip!=null)
            return source.swordTip.clone();
        if(values==null||values.length()!=2)
            throw new IllegalArgumentException(
                    "Trace the exact source sword tip with a normalized [x,y] pair: "+name);
        float x=(float)values.getDouble(0),y=(float)values.getDouble(1);
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1)
            throw new IllegalArgumentException("Sword tip is outside its illustrated frame");
        return new float[]{x,y};
    }

    /**
     * Storyboard-keyframe-conditioned actual 2D illustration animator.
     * A deliberate alternative to generic 3D/2D skeleton fighters and full
     * image optical-flow morphs. The source pixels and project are immutable.
     *
     * Four traced Rig2DProfiles must describe black/white character texture
     * on the starting and ending images, including each source's sword tip.
     * The original art is raster-skinned; a clean source-world is reconstructed
     * from unobscured pixels and never replaced with a procedural city.
     */
    private static StoryboardBatchPlanner.Plan storyboardPlan(ProjectStore.Project source,JSONObject p,JSONObject manifest) throws Exception {
        StoryboardBatchPlanner.Plan plan=manifest==null?StoryboardBatchPlanner.plan(source,p):StoryboardBatchPlanner.plan(source,p,manifest);
        if(!p.has("durationSeconds"))return plan;
        Object raw=p.get("durationSeconds");double seconds=raw instanceof Number?((Number)raw).doubleValue():Double.NaN;
        int frames=Double.isFinite(seconds)?(int)Math.ceil(plan.fps*seconds):0;
        if(plan.assets.size()!=2||seconds<=0||frames<5||frames>300)throw new IllegalArgumentException("Explicit shot duration requires two anchors and 5..300 output frames");
        StoryboardBatchPlanner.Shot old=plan.batches.get(0).shots.get(0),shot=new StoryboardBatchPlanner.Shot(0,1,old.fromId,old.toId,
                old.action,frames-1,old.scene,old.sourceDescription,old.beat);
        StoryboardBatchPlanner.Batch batch=new StoryboardBatchPlanner.Batch(0,0,1,java.util.Collections.singletonList(shot),frames);
        return new StoryboardBatchPlanner.Plan(source.id,plan.fps,plan.backend,plan.scene,plan.assets,java.util.Collections.singletonList(batch),plan.deduplicatedCount,plan.sourceManifest);
    }

    private JSONObject planStoryboardSeries(JSONObject input) throws Exception {
        JSONObject p=storyboardParameters(input,"",false);
        ProjectStore.Project original=existingStoryboardSource(p);
        StoryboardBatchPlanner.Plan plan=storyboardPlan(original,p,null);
        return plan.toJson().put("motionAdmission",StoryboardMotionAdmission.inspect(original,plan,p.optInt("batchIndex",0),
                new Rig2DProjectStore(this,store).list(original.id),StoryboardMotionAdmission.intent(p),p).toJson())
                .put("minimumNativeAppVersion","3.7.2").put("cinematicAcceptance",false);
    }

    private JSONObject queueStoryboardSeriesAudit(JSONObject input) throws Exception {
        JSONObject p=storyboardParameters(input,"storyboard_series_audit",true);boolean recovering=p.has("_recoveryPlanId");
        ProjectStore.Project original=existingStoryboardSource(p);
        StoryboardBatchPlanner.Plan initial=storyboardPlan(original,p,null);int batch=p.optInt("batchIndex",0);
        if(!recovering)StoryboardMotionAdmission.inspect(original,initial,batch,new Rig2DProjectStore(this,store).list(original.id),
                StoryboardMotionAdmission.intent(p),p).requireAccepted();
        JSONObject manifest=recovering?p.getJSONObject("_storyboardSourceManifest"):StoryboardSourceManifest.capture(this,original,p);
        StoryboardBatchPlanner.Plan plan=storyboardPlan(original,p,manifest);int[] size=storyboardSize(p,manifest,plan);
        JSONObject proof=recovering?p.getJSONObject("_storyboardAdmission"):
                StoryboardMotionAdmission.freeze(this,store,original,plan,batch,size[0],size[1],p,manifest);
        final JSONObject accepted=new JSONObject(p.toString()).put("batchIndex",batch).put("width",size[0]).put("height",size[1])
                .put("_storyboardSourceManifest",manifest).put("_storyboardAdmission",proof);
        StoryboardMotionAdmission.verify(this,store,original,plan,accepted);
        JSONObject durable=new JSONObject(accepted.toString()).put("projectId",original.id).put("sourceProjectId",original.id);
        JobManager.Job job=submitRecoverableHeavy("storyboard_series_audit",durable,original.id,"Storyboard source/scene audit",state->{
                    checkpoint(state,"Storyboarding","Inspecting imported images",3,original.id);
                    jobs.awaitSafeCheckpoint(state,"storyboard_audit_prepare");
                    StoryboardMotionAdmission.verify(this,store,store.get(original.id),plan,accepted);
                    JSONObject report=StoryboardSequenceAuditor.inspect(
                            this,original,plan,batch,(n,total)->{
                                if(n==1||n%2==0||n==total){
                                    checkpoint(state,"Storyboard audit",
                                            n+"/"+total+" image pairs checked",
                                            5+(int)(93d*n/Math.max(1,total)),
                                            original.id);
                                    jobs.awaitSafeCheckpoint(state,
                                            "storyboard_audit_pair_"+n);
                                }
                            });
                    state.setResult(report);
                    checkpoint(state,"Storyboard audit","Scene risks classified",100,original.id);
                });
        return ok().put("queued",true).put("jobId",job.id)
                .put("sourceProjectId",original.id)
                .put("batchIndex",batch)
                .put("sourceProjectUnmodified",true)
                .put("neuralSynthesized",false);
    }

    private JSONObject cinematic3DCapabilities() throws Exception {
        JSONObject out=ok().put("minimumNativeAppVersion","3.8.0").put("appVersion",AppProtocol.APP_VERSION)
                .put("timelineSchema","videostudio.cinematic3d.timeline.v1").put("bundleSchema","videostudio.cinematic3d.bundle.v1")
                .put("fps",new JSONArray().put(24).put(30).put(60)).put("maxShots",80).put("maxOutputFrames",12000)
                .put("offlineActionLibrary",true).put("fullSceneGltfReplay",true).put("exactOutputFrameHitStops",true)
                .put("humanStudioActivity","Cinematic3DStudioActivity").put("cloudEngine","Blender/Rigify")
                .put("neuralProviderInstalled",false).put("originalImageReconstruction",false)
                .put("nativeExecutionVerified",false).put("cinematicAcceptance",false).put("bakedPoseChangesRequireRebuild",true);
        try(java.io.InputStream asset=getAssets().open("cinematic3d/demo.vscinematic")){out.put("builtinBundleAvailable",asset.read()!=-1);}
        catch(java.io.IOException missing){out.put("builtinBundleAvailable",false);}
        return out;
    }

    /** Complete admission precedes output creation, recovery work and GPU effects. */
    private JSONObject queueSketchRender(JSONObject input) throws Exception {
        final JSONObject request=publicOperationParameters(input);
        SketchDocument.fields(request,"sketchId,expectedRevision,operationId,aspect,quality");
        String sketchId=SketchDocument.id(request,"sketchId"),operationId=SketchDocument.id(request,"operationId");
        SketchDocument.integer(request,"expectedRevision",1,9_007_199_254_740_991L);
        if(!input.has("_recoveryPlanId"))
            SketchEditOps.execute(this,"sketch_get",new JSONObject().put("sketchId",sketchId).put("expectedRevision",request.get("expectedRevision")));
        final String outputId=UUID.nameUUIDFromBytes(("sketch-output/"+sketchId+"/"+operationId).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject durable=new JSONObject(request.toString());
        if(input.has("_recoveryPlanId"))durable.put("_recoveryPlanId",input.get("_recoveryPlanId"));
        if(input.has("_mcpCommandId"))durable.put("_mcpCommandId",input.get("_mcpCommandId"));
        JobManager.Job job=submitRecoverableHeavy("sketch_render",durable,outputId,"Sketch animation export",state->{
            jobs.awaitSafeCheckpoint(state,"sketch_prepare");
            SketchNativeExport.Prepared prepared=SketchNativeExport.prepare(this,request,(frame,count)->{
                if(frame%4==0||frame==count-1){
                    checkpoint(state,"Sketch frame capture",(frame+1)+"/"+count+" authored frames",2+(int)(65d*frame/Math.max(1,count)),outputId);
                    jobs.awaitSafeCheckpoint(state,"sketch_frame_"+frame);
                }
            });
            if(!outputId.equals(prepared.project.id))throw new IllegalStateException("Sketch output identity changed");
            JSONObject metadata;
            if(prepared.completed)metadata=new JSONObject(prepared.metadata.toString());
            else{
                jobs.awaitSafeCheckpoint(state,"sketch_encode");
                final CountDownLatch done=new CountDownLatch(1);
                final AtomicReference<JSONObject> encoder=new AtomicReference<>();
                final AtomicReference<String> failure=new AtomicReference<>();
                String aspect="original".equals(prepared.aspect)?"source:"+prepared.metadata.getInt("width")+"x"+prepared.metadata.getInt("height"):prepared.aspect;
                NativeRenderEngine.Handle handle=renderEngine.export(prepared.project,prepared.encodingFile,aspect,prepared.quality,new NativeRenderEngine.Listener(){
                    public void onProgress(int value,String detail){checkpoint(state,"Sketch encoding",detail,68+(int)(value*.25),outputId);}
                    public void onCompleted(File file,JSONObject result){encoder.set(result);done.countDown();}
                    public void onError(String message){failure.set(message);done.countDown();}
                });
                if(handle==null)throw new IllegalStateException("Cannot start sketch encoder");
                synchronized(this){activeRender=handle;activeRenderJobId=state.id;}
                long deadline=android.os.SystemClock.elapsedRealtime()+30*60*1000L;
                try{
                    while(!done.await(500,TimeUnit.MILLISECONDS)){
                        if(Thread.currentThread().isInterrupted()||"cancelled".equals(state.state)){handle.cancel();throw new InterruptedException();}
                        if(android.os.SystemClock.elapsedRealtime()>deadline){handle.cancel();throw new IllegalStateException("Sketch encoding exceeded its bounded deadline");}
                    }
                    if(Thread.currentThread().isInterrupted()||"cancelled".equals(state.state))throw new InterruptedException();
                    if(failure.get()!=null)throw new IllegalStateException(failure.get());
                    if(encoder.get()==null)throw new IllegalStateException("Sketch encoder produced no completion receipt");
                    metadata=SketchNativeExport.complete(this,prepared,encoder.get());
                }finally{
                    if(encoder.get()==null)handle.cancel();
                    synchronized(this){if(activeRender==handle){activeRender=null;activeRenderJobId="";}}
                }
            }
            jobs.awaitSafeCheckpoint(state,"sketch_publish");
            JSONObject committed=recoveryPlans.outputForJob(state.id);
            final String published=metadata.optString("publishedUri",committed==null?"":committed.optString("uri",""));
            final String outputName="VideoStudio_Sketch_"+operationId+".mp4";
            AtomicMediaPublisher.PublishResult publication=AtomicMediaPublisher.publish(prepared.file,new AtomicMediaPublisher.PublishTarget(){
                public String existingPublishedUri(){return isReadableOutput(published)?published:"";}
                public String publish(File source)throws Exception{return publishExport(source,outputName).toString();}
                public String displayName(){return outputName;}
            });
            recoveryPlans.markOutputForJob(state.id,publication.uri,outputName);
            metadata=SketchNativeExport.recordPublished(this,prepared,Uri.parse(publication.uri),outputName);
            state.setResult(metadata.put("ok",true).put("projectId",outputId).put("decodedFrameCountVerified",false).put("cinematicAcceptance",false));
            checkpoint(state,"Sketch export","Native MP4 is available in Movies/VideoStudio",100,outputId);syncProtocolState();
        });
        return ok().put("queued",true).put("jobId",job.id).put("projectId",outputId).put("outputProjectId",outputId)
                .put("sketchId",sketchId).put("durableRecovery",true).put("render",true);
    }

    private static JSONObject publicOperationParameters(JSONObject input)throws Exception {
        JSONObject result=new JSONObject(input.toString());JSONArray keys=result.names();
        if(keys!=null)for(int i=0;i<keys.length();i++)if(keys.getString(i).startsWith("_"))result.remove(keys.getString(i));
        return result;
    }

    private JSONObject queueOpenPipeline(String action,JSONObject input)throws Exception {
        final JSONObject request=publicOperationParameters(input);
        String requestId=OpenPipelineArtifacts.id(request.getString("requestId"));
        String projectId=request.optString("projectId","");
        if(projectId.isEmpty())projectId=new OpenPipelineStore(this).get(requestId).getString("projectId");
        final String sourceProjectId=projectId;
        JSONObject durable=new JSONObject(request.toString());
        if(input.has("_recoveryPlanId"))durable.put("_recoveryPlanId",input.get("_recoveryPlanId"));
        if(input.has("_mcpCommandId"))durable.put("_mcpCommandId",input.get("_mcpCommandId"));
        JobManager.Job job=submitRecoverableTransfer(action,durable,projectId,"Open Pipeline • "+action.replace("open_pipeline_","").replace('_',' '),state->{
            JSONObject result=OpenPipelineOperations.execute(this,store,action,request,(progress,detail)->{
                checkpoint(state,"Open Pipeline",detail,Math.min(99,Math.max(0,progress)),sourceProjectId);
                jobs.awaitSafeCheckpoint(state,"open_pipeline_transfer");
            });
            if(result.has("jobId")){result.put("remoteJobId",result.get("jobId"));result.remove("jobId");}
            state.setResult(result.put("ok",true).put("submissionIsGenerationCompletion",false));syncProtocolState();
        });
        return ok().put("queued",true).put("jobId",job.id).put("projectId",projectId).put("requestId",requestId)
                .put("durableRecovery",true).put("nativeRendered",false);
    }

    private JSONObject queueCinematic3D(JSONObject input) throws Exception {
        final boolean recovering=input.has("_recoveryPlanId");
        JSONObject request=new JSONObject(input.toString());
        JSONArray keys=request.names();if(keys!=null)for(int i=0;i<keys.length();i++)if(keys.getString(i).startsWith("_"))request.remove(keys.getString(i));
        if(recovering){request.put("projectId",input.getString("sourceProjectId"));request.remove("sourceProjectId");request.remove("outputProjectId");}
        Cinematic3DTimeline.fields(request,"projectId","programId","expectedProgramSha256","width","height","render","fileName");
        Cinematic3DTimeline.canonical(request);
        int width=request.has("width")?(int)Cinematic3DTimeline.integer(request,"width",256,1280):720;
        int height=request.has("height")?(int)Cinematic3DTimeline.integer(request,"height",256,1280):1280;
        NativeCinematicSceneComposer.requireDimensions(width,height);
        if(request.has("render")&&!(request.get("render") instanceof Boolean))throw new IllegalArgumentException("render must be boolean");
        if(request.has("fileName"))Cinematic3DTimeline.text(request,"fileName",180);
        final boolean encode=request.optBoolean("render",true);
        final String name=sanitizeFileName(request.optString("fileName","VideoStudio_Cinematic3D_"+System.currentTimeMillis()+".mp4"));
        JSONObject identity=new JSONObject().put("projectId",Cinematic3DTimeline.id(Cinematic3DTimeline.text(request,"projectId",96)))
                .put("programId",Cinematic3DTimeline.id(Cinematic3DTimeline.text(request,"programId",96)));
        if(request.has("expectedProgramSha256"))identity.put("expectedProgramSha256",request.get("expectedProgramSha256"));
        final JSONObject program=Cinematic3DProgramStore.get(this,store,identity);
        final JSONObject bundle=NativeCinematicBundleStore.get(this,store,program.getString("projectId"),program.getString("bundleId"));
        Cinematic3DProgramStore.requireUnchanged(this,store,program,bundle);
        final ProjectStore.Project source=store.get(program.getString("projectId"));
        if(source==null)throw new IllegalArgumentException("Source project unavailable");
        final JSONObject clock=Cinematic3DTimeline.compile(program.getJSONObject("timeline"));
        final int total=clock.getInt("totalFrames"),fps=clock.getInt("fps");
        final ProjectStore.Project output;
        final boolean reuseFrames;
        if(recovering){
            output=store.get(input.getString("outputProjectId"));
            if(output==null||output.id.equals(source.id)||(output.sourcePrompt==null||!output.sourcePrompt.startsWith("cinematic3d:"))
                    ||output.updatedAt!=input.getLong("_cinematicOutputUpdatedAt"))
                throw new IllegalStateException("OUTPUT_PROJECT_CHANGED: cinematic recovery preserves the previous output");
            reuseFrames=!output.clips.isEmpty();
            if(reuseFrames)requireCinematicFrames(output,program,total,fps);
            else NativeCinematicSceneComposer.requireNewOutput(source,output);
        }else{
            output=store.create("Cinematic 3D • "+source.name);output.sourcePrompt="cinematic3d:full-scene-v1";store.save(output);reuseFrames=false;
        }
        JSONObject durable=new JSONObject(request.toString()).put("projectId",output.id).put("sourceProjectId",source.id)
                .put("outputProjectId",output.id).put("expectedProgramSha256",program.getString("sha256"))
                .put("_cinematicOutputUpdatedAt",output.updatedAt).put("width",width).put("height",height).put("render",encode).put("fileName",name);
        if(recovering)durable.put("_recoveryPlanId",input.getString("_recoveryPlanId"));
        if(input.has("_mcpCommandId"))durable.put("_mcpCommandId",input.getString("_mcpCommandId"));
        JobManager.Job job=submitRecoverableHeavy("cinematic_3d_render",durable,output.id,"Cinematic 3D • "+source.name,state->{
            jobs.awaitSafeCheckpoint(state,"cinematic3d_prepare");
            Cinematic3DProgramStore.requireUnchanged(this,store,program,bundle);
            ProjectStore.Project generated=output;
            if(!reuseFrames){
                generated=NativeCinematicSceneComposer.compose(this,store,source,output,bundle,program,width,height,(done,count,seconds,impact)->{
                    checkpoint(state,"Cinematic 3D",done+"/"+count+" articulated frames",2+(int)(70d*done/Math.max(1,count)),output.id);
                    jobs.awaitSafeCheckpoint(state,"cinematic3d_frame_"+done);
                });
                recoveryPlans.checkpointCinematicOutputForJob(state.id,generated.id,generated.updatedAt);
            }
            requireCinematicFrames(generated,program,total,fps);
            JSONObject result=ok().put("sourceProjectId",source.id).put("outputProjectId",generated.id).put("programId",program.getString("programId"))
                    .put("programSha256",program.getString("sha256")).put("bundleSha256",bundle.getString("sha256"))
                    .put("frameCount",total).put("fps",fps).put("durationSeconds",total/(double)fps)
                    .put("execution","native_filament_full_scene").put("nativeFrameCaptureCompleted",true).put("renderRequested",encode)
                    .put("motionProvider",program.getJSONObject("timeline").getString("motionProvider"))
                    .put("originalImageReconstruction",false).put("cinematicAcceptance",false);
            state.setResult(result);syncProtocolState();
            if(encode){jobs.awaitSafeCheckpoint(state,"cinematic3d_media3");runExportBlocking(generated,"source:"+width+"x"+height,"720p",name,state);}
            checkpoint(state,"Cinematic 3D",encode?"Native MP4 published":"Articulated frames ready in the output project",100,generated.id);
        });
        return ok().put("queued",true).put("jobId",job.id).put("projectId",output.id).put("outputProjectId",output.id).put("sourceProjectId",source.id)
                .put("expectedFrames",total).put("fps",fps).put("render",encode).put("durableRecovery",true)
                .put("nativeExecutionVerified",false).put("cinematicAcceptance",false);
    }

    private static void requireCinematicFrames(ProjectStore.Project output,JSONObject program,int total,int fps)throws Exception {
        if(output.clips.size()!=total||output.assets.size()!=total)throw new IllegalStateException("Cinematic frame generation is incomplete");
        for(int i=0;i<total;i++){
            ProjectStore.Clip clip=output.clips.get(i);ProjectStore.Asset asset=output.asset(clip.assetId);
            JSONObject m=asset==null?null:asset.generationMetadata;
            if(m==null||!"generated_cinematic_3d".equals(asset.role)||!program.getString("sha256").equals(m.optString("programSha256"))
                    ||m.optInt("frameIndex",-1)!=i||m.optInt("fps",0)!=fps||m.optLong("presentationTimeUs",-1)!=NativeFrameClock.presentationTimeUs(i,fps))
                throw new IllegalStateException("Cinematic frame proof differs from the accepted program");
        }
    }

    private synchronized boolean cancelOwnedRender(String jobId) {
        if(jobId==null||!jobId.equals(activeRenderJobId)||activeRender==null)return false;
        activeRender.cancel();return true;
    }

    private JSONObject queueStoryboardSeries(JSONObject input) throws Exception {
        JSONObject p=storyboardParameters(input,"animate_storyboard_series",true);
        boolean recovering=p.has("_recoveryPlanId");
        ProjectStore.Project source=existingStoryboardSource(p);
        StoryboardBatchPlanner.Plan initial=storyboardPlan(source,p,null);
        int selected=p.optInt("batchIndex",0);
        if(!recovering)StoryboardMotionAdmission.inspect(source,initial,selected,new Rig2DProjectStore(this,store).list(source.id),
                StoryboardMotionAdmission.intent(p),p).requireAccepted();
        JSONObject manifest=recovering?p.getJSONObject("_storyboardSourceManifest"):StoryboardSourceManifest.capture(this,source,p);
        StoryboardBatchPlanner.Plan plan=storyboardPlan(source,p,manifest);
        int count=StoryboardSeriesOrchestrator.predictedFrames(plan,selected);
        int[] size=storyboardSize(p,manifest,plan);
        int width=size[0],height=size[1];
        p.put("batchIndex",selected).put("width",width).put("height",height).put("motionIntent",StoryboardMotionAdmission.intent(p));
        JSONObject proof=recovering?p.getJSONObject("_storyboardAdmission"):
                StoryboardMotionAdmission.freeze(this,store,source,plan,selected,width,height,p,manifest);
        p.put("_storyboardSourceManifest",manifest).put("_storyboardAdmission",proof);
        StoryboardMotionAdmission.verify(this,store,source,plan,p);
        boolean render=p.optBoolean("render",true);
        String quality="1080p".equalsIgnoreCase(p.optString("quality","720p"))
                ?"1080p":"720p";
        String fileName=sanitizeFileName(p.optString("fileName",
                "VideoStudio_Storyboard_Motion_"+System.currentTimeMillis()+".mp4"));
        String requested=p.optString("outputProjectId","");
        ProjectStore.Project output;
        if(!recovering){
            if(!requested.isEmpty())throw new IllegalArgumentException("Caller outputProjectId is not accepted; a fresh separate output is required");
            output=store.create("Storyboard Film • "+source.name);
            output.sourcePrompt="storyboard-multigenre-v1: original 2D moving art";
            store.save(output);
        }else{
            output=store.get(requested);
            if(output==null||output.id.equals(source.id)||
                    output.sourcePrompt==null||
                    !output.sourcePrompt.startsWith("storyboard-multigenre-v1"))
                throw new IllegalArgumentException("Storyboard output must be a separate existing project");
        }
        p.put("_storyboardOutputUpdatedAt",recovering?p.getLong("_storyboardOutputUpdatedAt"):output.updatedAt);
        if(output.updatedAt!=p.getLong("_storyboardOutputUpdatedAt"))throw new IllegalStateException("STORYBOARD_OUTPUT_CHANGED: previous target preserved");
        JSONObject durable=new JSONObject(p.toString())
                .put("projectId",output.id)
                .put("sourceProjectId",source.id)
                .put("outputProjectId",output.id)
                .put("batchIndex",selected)
                .put("render",render)
                .put("quality",quality)
                .put("fileName",fileName);
        final ProjectStore.Project in=source,out=output;
        final int expected=count,batchIndex=selected,w=width,h=height;
        final boolean export=render;
        final JSONObject parameters=new JSONObject(p.toString());
        JobManager.Job job=submitRecoverableHeavy("animate_storyboard_series",
                durable,out.id,"Source-image animation film • "+source.name,state->{
                    checkpoint(state,"Storyboard animation","Preparing original art layers",2,out.id);
                    jobs.awaitSafeCheckpoint(state,"multigenre_storyboard_prepare");
                    StoryboardMotionAdmission.verify(this,store,store.get(in.id),plan,parameters);
                    StoryboardSeriesOrchestrator.Result result=
                            StoryboardSeriesOrchestrator.run(this,store,in,out,
                                    plan,batchIndex,w,h,parameters,
                                    new StoryboardSeriesOrchestrator.Progress(){
                                        public void update(int produced,int total,int batch,int pair,String provider,float confidence) throws Exception {
                                            if(produced==1||produced%4==0||produced==total){
                                                int pct=4+(int)(64d*produced/Math.max(1,total));
                                                checkpoint(state,"Storyboard animation",produced+"/"+total+" motion frames · batch "+(batch+1)+" · "+provider,pct,out.id);
                                                jobs.awaitSafeCheckpoint(state,"storyboard_frame_"+produced);
                                            }
                                        }
                                        public void onSaved(ProjectStore.Project saved) throws Exception {
                                            recoveryPlans.checkpointStoryboardOutputForJob(state.id,saved.id,saved.updatedAt);
                                        }
                                    });
                    JSONObject data=result.toJson()
                            .put("sourceProjectId",in.id)
                            .put("outputProjectId",out.id)
                            .put("sourceClipCount",in.clips.size())
                            .put("renderRequested",export)
                            .put("durationMs",out.outputDurationMs());
                    state.setResult(data);
                    syncProtocolState();
                    if(export){
                        jobs.awaitSafeCheckpoint(state,"storyboard_media3_export");
                        checkpoint(state,"Native Media3","Encoding animated illustrated film",75,out.id);
                        runExportBlocking(out,"source:"+w+"x"+h,quality,fileName,state);
                        checkpoint(state,"Storyboard film","MP4 exported, original images untouched",100,out.id);
                    }else{
                        checkpoint(state,"Storyboard frames",
                                "Editable original-artwork animation generated",100,out.id);
                    }
                });
        return ok().put("queued",true).put("durableRecovery",true)
                .put("jobId",job.id)
                .put("sourceProjectId",source.id)
                .put("outputProjectId",output.id)
                .put("sourceFrameCount",plan.assets.size())
                .put("batchCount",selected<0?plan.batches.size():1)
                .put("expectedAnimatedFrames",count)
                .put("render",render)
                .put("backend",plan.backend.name())
                .put("sourceProjectUnchanged",true)
                .put("noGalleryBrowsing",true)
                .put("unseenPoseNeuralModelInstalled",false);
    }

    private JSONObject storyboardParameters(JSONObject input,String action,boolean recoveryAllowed) throws Exception {
        JSONObject p=new JSONObject(input.toString());
        String planId=p.optString("_recoveryPlanId","");
        if(!planId.isEmpty()) {
            JSONObject saved=recoveryAllowed&&recoveryPlans!=null?recoveryPlans.get(planId):null;
            if(saved==null||!action.equals(saved.optString("action"))||!(saved.opt("parameters") instanceof JSONObject))
                throw new IllegalArgumentException("Trusted storyboard recovery plan is missing or belongs to another action");
            p=new JSONObject(saved.getJSONObject("parameters").toString()).put("_recoveryPlanId",planId);
            if(!p.has("_storyboardSourceManifest")||!p.has("_storyboardAdmission")
                    ||!("storyboard_series_audit".equals(action)?saved.getString("projectId").equals(p.getString("sourceProjectId"))
                    :saved.getString("projectId").equals(p.getString("outputProjectId"))))
                throw new IllegalStateException("Legacy recovery lacks accepted source proof; no output is overwritten");
            return p;
        }
        JSONObject publicCopy=new JSONObject(p.toString());
        java.util.Iterator<String> names=publicCopy.keys();java.util.ArrayList<String> internals=new java.util.ArrayList<>();
        while(names.hasNext()){String name=names.next();if(name.startsWith("_"))internals.add(name);}
        for(String name:internals)publicCopy.remove(name);
        StoryboardMotionAdmission.requestBudget(publicCopy);
        for(String name:internals)if(!"_mcpCommandId".equals(name))p.remove(name);
        if(p.has("programId")) {
            ProjectStore.Project source=existingStoryboardSource(p);
            JSONObject program=new StoryboardProgramStore(this,store).load(source.id,p.getString("programId"));
            if(p.has("expectedProgramSha256")&&!p.getString("expectedProgramSha256").equals(program.getString("sha256")))
                throw new IllegalStateException("STORYBOARD_PROGRAM_CHANGED: saved program differs");
            if(program.getLong("sourceProjectUpdatedAt")!=source.updatedAt)throw new IllegalStateException("Saved program source revision changed");
            p.put("assetIds",program.getJSONArray("sourceAssetIds"));p.remove("anchorGroups");
            JSONArray bindings=new JSONArray();JSONObject map=program.getJSONObject("sourceRigBindings");
            java.util.Iterator<String> keys=map.keys();while(keys.hasNext()) {
                String assetId=keys.next();bindings.put(new JSONObject(map.getJSONObject(assetId).toString()).put("assetId",assetId));
            }
            p.put("sourceRigBindings",bindings);
            JSONArray contacts=new JSONArray();for(int i=0;i<program.getJSONArray("contactPlans").length();i++) {
                JSONObject row=new JSONObject(program.getJSONArray("contactPlans").getJSONObject(i).toString());row.remove("bindingProof");contacts.put(row);
            }
            p.put("contactPlans",contacts).put("choreography",program.getJSONArray("choreography"));
            p.put("expectedProgramSha256",program.getString("sha256"));
        }
        return p;
    }
    private static int[] storyboardSize(JSONObject p,JSONObject manifest,StoryboardBatchPlanner.Plan plan) throws Exception {
        if(p.has("width")||p.has("height")) {
            int w=p.getInt("width"),h=p.getInt("height");StoryboardMotionAdmission.dimensions(w,h);return new int[]{w,h};
        }
        int[] dims=StoryboardSourceManifest.orientedDimensions(manifest,plan.assets.get(0));
        int a=dims[0],b=dims[1];while(b!=0){int next=a%b;a=b;b=next;}
        int rw=dims[0]/a,rh=dims[1]/a;
        for(int scale=Math.min(1280/rw,1280/rh);scale>=1;scale--) {
            int w=rw*scale,h=rh*scale;
            if(w>=256&&h>=256&&(w&1)==0&&(h&1)==0&&(long)w*h<=720L*1280L)return new int[]{w,h};
        }
        throw new IllegalArgumentException("Source aspect cannot fit exact bounded even dimensions; explicit registration is required");
    }

    private ProjectStore.Project existingStoryboardSource(JSONObject parameters) {
        String requested=parameters.optString("sourceProjectId",parameters.optString("projectId",""));
        ProjectStore.Project project=requested.isEmpty()?store.active():store.get(requested);
        if(project==null)throw new IllegalArgumentException("An existing owned storyboard source project is required");
        return project;
    }

    private JSONObject queueStoryboardArtwork(JSONObject input) throws Exception {
        if(input.has("_recoveryPlanId"))throw new IllegalStateException("Legacy artwork recovery lacks accepted series proof; existing output retained");
        for(String unsupported:new String[]{"backgroundAssetId","blackTipFrom","whiteTipFrom","blackTipTo","whiteTipTo"})
            if(input.has(unsupported)&&!input.isNull(unsupported)&&!input.optString(unsupported,"").isEmpty())
                throw new IllegalArgumentException("Legacy artwork "+unsupported+" override is unsupported; render the accepted original source programme");
        if(input.has("programId"))return queueStoryboardSeries(input);
        String requestedBeat=input.optString("beat","");
        try{StoryboardChoreography.Beat.valueOf(requestedBeat);}catch(IllegalArgumentException unknown){
            throw StoryboardMotionAdmission.missingProgram(input.optString("sourceProjectId",input.optString("projectId","")));
        }
        ProjectStore.Project source=existingStoryboardSource(input);
        Rig2DProjectStore profiles=new Rig2DProjectStore(this,store);
        Rig2DProfile b0=profiles.load(source.id,input.getString("blackFromRigId")),
                w0=profiles.load(source.id,input.getString("whiteFromRigId")),
                b1=profiles.load(source.id,input.getString("blackToRigId")),
                w1=profiles.load(source.id,input.getString("whiteToRigId"));
        JSONObject p=new JSONObject(input.toString()).put("backend","SOURCE_RIG").put("batchIndex",0)
                .put("assetIds",new JSONArray().put(b0.sourceAssetId).put(b1.sourceAssetId))
                .put("sourceRigBindings",new JSONArray()
                        .put(new JSONObject().put("assetId",b0.sourceAssetId).put("blackRigId",b0.id).put("whiteRigId",w0.id))
                        .put(new JSONObject().put("assetId",b1.sourceAssetId).put("blackRigId",b1.id).put("whiteRigId",w1.id)));
        if(input.has("contactPlan"))p.put("contactPlans",new JSONArray().put(new JSONObject().put("shotIndex",0)
                .put("fromAssetId",b0.sourceAssetId).put("toAssetId",b1.sourceAssetId).put("contactPlan",input.getJSONObject("contactPlan"))));
        if(input.has("beat"))p.put("choreography",new JSONArray().put(new JSONObject().put("shotIndex",0)
                .put("fromAssetId",b0.sourceAssetId).put("toAssetId",b1.sourceAssetId).put("beat",input.getString("beat"))));
        return queueStoryboardSeries(p);
    }

    private JSONObject queueRigged2D(JSONObject p) throws Exception {
        String sourceId=p.optString("sourceProjectId",p.optString("projectId",""));
        ProjectStore.Project source=resolveProject(sourceId);
        Rig2DProjectStore rigs=new Rig2DProjectStore(this,store);
        String blackId=p.optString("blackRigId","");
        String whiteId=p.optString("whiteRigId","");
        Rig2DProfile blackProfile=rigs.load(source.id,blackId);
        Rig2DProfile whiteProfile=rigs.load(source.id,whiteId);
        if(!blackProfile.black||whiteProfile.black)
            throw new IllegalArgumentException("Pick distinct black and white source actor rigs");

        String backgroundMode=p.optString("backgroundMode","procedural");
        if(!"procedural".equals(backgroundMode)&&
                !"source_median".equals(backgroundMode)&&
                !"clean_asset".equals(backgroundMode))
            throw new IllegalArgumentException("Choose procedural, source_median or clean_asset");
        String backdropId=p.optString("backgroundAssetId","");
        if("clean_asset".equals(backgroundMode)){
            ProjectStore.Asset background=source.asset(backdropId);
            if(background==null||background.mime==null||
                    !background.mime.startsWith("image/")||
                    backdropId.equals(blackProfile.sourceAssetId)||
                    backdropId.equals(whiteProfile.sourceAssetId))
                throw new IllegalArgumentException("Use a separate user-selected clean background image");
        }
        boolean storyboard=p.optBoolean("storyboardMotion",true);
        int fps=p.optInt("fps",24),width=p.optInt("width",432),
                height=p.optInt("height",768);
        double duration=p.optDouble("durationSeconds",4.6);
        if(fps!=24&&fps!=30)
            throw new IllegalArgumentException("Source-rig animation supports 24 or 30 FPS");
        if(!Double.isFinite(duration)||duration<2||duration>6)
            throw new IllegalArgumentException("Source-rig animation must be 2-6 seconds");
        if(width<320||height<540||width>720||height>1280||
                (width&1)!=0||(height&1)!=0)
            throw new IllegalArgumentException("Source-rig preview must be even and bounded");

        boolean render=p.optBoolean("render",true);
        String quality="1080p".equals(p.optString("quality","720p"))
                ?"1080p":"720p";
        String fileName=sanitizeFileName(p.optString("fileName",
                "VideoStudio_Rigged2D_"+System.currentTimeMillis()+".mp4"));
        String outputId=p.optString("outputProjectId","");
        ProjectStore.Project target;
        if(outputId.isEmpty()){
            target=store.create("Rigged 2D • "+source.name);
            target.sourcePrompt="rigged2d: source-character mesh animation";
            store.save(target);
        }else{
            target=store.get(outputId);
            if(target==null||!target.sourcePrompt.startsWith("rigged2d:"))
                throw new IllegalArgumentException("Invalid native rig recovery destination");
        }
        final ProjectStore.Project output=target, original=source;
        final Rig2DProfile b=blackProfile,w=whiteProfile;
        final CombatActionPlan director=combatDirector(p);
        final String sceneMode=backgroundMode, sceneAssetId=backdropId;
        final int videoWidth=width,videoHeight=height,videoFps=fps;
        final double seconds=duration;
        JSONObject durable=new JSONObject(p.toString());
        durable.put("projectId",output.id);
        durable.put("sourceProjectId",source.id);
        durable.put("outputProjectId",output.id);
        durable.put("blackRigId",blackId);durable.put("whiteRigId",whiteId);
        durable.put("backgroundMode",sceneMode);durable.put("storyboardMotion",storyboard);
        durable.put("fps",fps);durable.put("width",width);durable.put("height",height);
        durable.put("durationSeconds",seconds);durable.put("render",render);
        durable.put("quality",quality);durable.put("aspect","9:16");
        durable.put("fileName",fileName);
        JobManager.Job job=submitRecoverableHeavy("render_rigged_2d",durable,
                output.id,"Source-rigged 2D • "+source.name,state -> {
            checkpoint(state,"2D source rig","Preparing character masks and joint weights",
                    3,output.id);
            jobs.awaitSafeCheckpoint(state,"rig2d_prepare");
            android.graphics.Bitmap clean=null;
            try{
                if("source_median".equals(sceneMode)){
                    checkpoint(state,"2D scene","Estimating a fixed clean scene from approved shots",
                            5,output.id);
                    clean=NativeScenePlateBuilder.fromProject(
                            this,original,videoWidth,videoHeight);
                }else if("clean_asset".equals(sceneMode)){
                    clean=NativeRiggedCombatComposer.cleanPlate(this,
                            original.asset(sceneAssetId),videoWidth,videoHeight);
                }
                NativeRiggedCombatComposer.compose(this,store,original,output,
                        b,w,videoWidth,videoHeight,videoFps,seconds,storyboard,
                        clean,director,(current,total,time,impact)->{
                            if(current==1||current%4==0||current==total){
                                int pct=6+(int)(58d*current/Math.max(1,total));
                                checkpoint(state,"2D source rig",
                                        current+"/"+total+" mesh-skinned character frames",
                                        pct,output.id);
                                jobs.awaitSafeCheckpoint(state,"rig2d_frame_"+current);
                            }
                        });
            }finally{if(clean!=null&&!clean.isRecycled())clean.recycle();}
            JSONObject details=new JSONObject();
            details.put("engine","builtin.mesh-skinned-2d-v1");
            details.put("sourceProjectId",original.id);
            details.put("sourceTexturesPreserved",true);
            details.put("riggedCharacterPixels",true);
            details.put("manuallyAuthoredCharacterMasks",true);
            details.put("genuine3DMesh",false);
            details.put("newUnseenAnatomySynthesis",false);
            details.put("storyboardMotion",storyboard);
            details.put("actionDirector","shared-eight-beat-timeline-v1");
            details.put("actualSoundtrackAttached",false);
            details.put("frames",output.clips.size());
            details.put("backgroundMode",sceneMode);
            state.setResult(details);
            syncProtocolState();
            if(render){
                jobs.awaitSafeCheckpoint(state,"rig2d_export");
                runExportBlocking(output,"9:16",quality,fileName,state);
                checkpoint(state,"2D source rig",
                        "Verified rigged-character MP4 exported",100,output.id);
            }else{
                checkpoint(state,"2D source rig",
                        "Editable skinned-pose samples ready",100,output.id);
            }
        });
        JSONObject result=ok();
        result.put("queued",true);result.put("durableRecovery",true);
        result.put("jobId",job.id);
        result.put("projectId",output.id);
        result.put("sourceProjectId",source.id);
        result.put("outputProjectId",output.id);
        result.put("expectedFrames",(int)Math.round(fps*seconds));
        result.put("render",render);result.put("backgroundMode",backgroundMode);
        result.put("engine","builtin.mesh-skinned-2d-v1");
        result.put("full3D",false);
        return result;
    }

    private JSONObject queuePoseSequence(JSONObject p) throws Exception {
        String sourceId = p.optString("sourceProjectId", p.optString("projectId", ""));
        ProjectStore.Project source = resolveProject(sourceId);
        JSONArray inputs = p.optJSONArray("anchorAssetIds");
        if (inputs == null || inputs.length() < 2 || inputs.length() > 40)
            throw new IllegalArgumentException("Pose sequence requires 2-40 ordered source image IDs");

        ArrayList<String> anchors = new ArrayList<>();
        for (int i=0; i<inputs.length(); i++) {
            String id = inputs.optString(i, "");
            ProjectStore.Asset asset = source.asset(id);
            if (asset == null || asset.mime == null || !asset.mime.startsWith("image/"))
                throw new IllegalArgumentException("Source frame " + i + " is not an imported image in this project");
            anchors.add(id);
        }
        int width = Math.max(256, Math.min(1280, p.optInt("width", NativePoseSequenceComposer.DEFAULT_WIDTH)));
        int height = Math.max(256, Math.min(1920, p.optInt("height", NativePoseSequenceComposer.DEFAULT_HEIGHT)));
        if ((width & 1) != 0 || (height & 1) != 0)
            throw new IllegalArgumentException("Pose dimensions must be even");
        int framesPerPair = Math.max(2, Math.min(16, p.optInt("framesPerPair", 6)));
        int fps = Math.max(12, Math.min(30, p.optInt("fps", 24)));
        int[] frameMap = new int[inputs.length() - 1];
        JSONArray customTiming = p.optJSONArray("framesByPair");
        if (customTiming != null && customTiming.length() != frameMap.length)
            throw new IllegalArgumentException("Motion timing map must match adjacent anchor pairs");
        int frameTotal = 1;
        for (int i=0; i<frameMap.length; i++) {
            frameMap[i] = customTiming == null ? framesPerPair : customTiming.optInt(i, 0);
            if (frameMap[i] < 2 || frameMap[i] > 16)
                throw new IllegalArgumentException("Each motion segment must contain 2-16 frames");
            frameTotal += frameMap[i];
        }
        if (frameTotal > 240)
            throw new IllegalArgumentException("Sequence exceeds 240 bounded pose frames");

        String outputId = p.optString("outputProjectId", "");
        ProjectStore.Project output;
        if (outputId.isEmpty()) {
            output = store.create("Pose Motion - " + source.name);
        } else {
            output = store.get(outputId);
            if (output == null || output.id.equals(source.id))
                throw new IllegalArgumentException("Recovery destination project unavailable");
        }
        final ProjectStore.Project outputProject = output;
        final ProjectStore.Project sourceProject = source;
        final ArrayList<String> orderedIds = anchors;
        final int targetWidth = width, targetHeight = height;
        final int[] segmentFrames = frameMap;
        final int targetFps = fps, expectedFrames = frameTotal;
        boolean render = p.optBoolean("render", true);
        String quality = "1080p".equals(p.optString("quality", "720p")) ? "1080p" : "720p";
        String fileName = sanitizeFileName(p.optString("fileName", "VideoStudio_Pose_Motion_" + System.currentTimeMillis() + ".mp4"));
        JSONObject durable = new JSONObject(p.toString());
        durable.put("sourceProjectId", source.id);
        durable.put("projectId", output.id);
        durable.put("outputProjectId", output.id);
        durable.put("fileName", fileName);
        durable.put("render", render);
        durable.put("aspect", "9:16");
        durable.put("quality", quality);

        JobManager.Job job = submitRecoverableHeavy("animate_pose_sequence",
                durable, output.id, "Pose Motion • " + source.name, state -> {
                    int expected = expectedFrames;
                    checkpoint(state, "Native pose synthesis", "Building time-coherent frame correspondences", 4, outputProject.id);
                    jobs.awaitSafeCheckpoint(state, "pose_frame_build");
                    final double[] lastConfidence = {0};
                    NativePoseSequenceComposer.compose(this, store, sourceProject,
                            outputProject, orderedIds, targetWidth, targetHeight,
                            segmentFrames, targetFps, (produced, total, confidence) -> {
                                lastConfidence[0] = confidence;
                                int pct = 5 + (int) Math.min(59, (produced * 59.0) / Math.max(1, total));
                                if (produced == 1 || produced % 4 == 0 || produced == total) {
                                    checkpoint(state, "Native pose synthesis",
                                            produced + "/" + total + " distinct pose frames stored", pct, outputProject.id);
                                    jobs.awaitSafeCheckpoint(state, "pose_frame_" + produced);
                                }
                            });
                    JSONObject details = new JSONObject();
                    details.put("engine", "bidirectional-block-flow");
                    details.put("generatedFrames", expected);
                    details.put("anchors", orderedIds.size());
                    details.put("width", targetWidth);
                    details.put("height", targetHeight);
                    details.put("fpsRequested", targetFps);
                    details.put("neuralPoseGeneration", false);
                    details.put("limitation", "Motion estimation cannot invent unseen anatomy; inspect for ghosting");
                    details.put("lastPairConfidence", lastConfidence[0]);
                    state.setResult(details);
                    syncProtocolState();
                    checkpoint(state, "Native pose synthesis", "Motion sequence available as its own project", 65, outputProject.id);
                    if (render) {
                        jobs.awaitSafeCheckpoint(state, "pose_export");
                        runExportBlocking(outputProject, "9:16", quality, fileName, state);
                        checkpoint(state, "Native pose synthesis", "Pose-sequence MP4 rendered", 100, outputProject.id);
                    } else checkpoint(state, "Native pose synthesis", "Editable pose frames prepared", 100, outputProject.id);
                });
        JSONObject result = ok();
        result.put("queued", true);
        result.put("durableRecovery", true);
        result.put("projectId", output.id);
        result.put("sourceProjectId", source.id);
        result.put("outputProjectId", output.id);
        result.put("jobId", job.id);
        result.put("render", render);
        result.put("expectedFrames", frameTotal);
        result.put("timingMode", customTiming == null ? "uniform" : "animator-directed");
        result.put("engine", "native-bidirectional-optical-flow-v1");
        return result;
    }

    /** Fixed-size private chunks avoid sending large base64 payloads through MCP. */
    private JSONObject appendFrameChunk(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        return new NativeChunkedImport(getFilesDir()).append(
                project.id, p.optString("transferId", ""),
                p.optString("name", ""), p.optString("mime", ""),
                p.optLong("totalBytes", 0), p.optString("sha256", ""),
                p.optLong("offset", -1), p.optString("base64", ""));
    }

    private JSONObject finishFrameTransfer(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
        File output = new File(getFilesDir(), "imports");
        NativeChunkedImport.Result frame = new NativeChunkedImport(getFilesDir())
                .finalizeImage(project.id, p.optString("transferId", ""),
                        p.optString("name", ""), p.optString("mime", ""),
                        p.optLong("totalBytes", 0), p.optString("sha256", ""), output);
        ProjectStore.Asset asset = addImportedAsset(project, frame.file, frame.name, frame.mime);
        JSONObject result = ok();
        result.put("projectId", project.id);
        result.put("assetId", asset.id);
        result.put("name", frame.name);
        result.put("mime", frame.mime);
        result.put("size", frame.bytes);
        result.put("width", frame.width);
        result.put("height", frame.height);
        result.put("transport", "private-checksummed-chunked-mcp");
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
        AtomicReference<JSONObject> encoderReceipt = new AtomicReference<>();

        final NativeRenderEngine.Handle ownedHandle = renderEngine.export(project, temp, aspect, quality, new NativeRenderEngine.Listener() {
            @Override public void onProgress(int progress, String detail) {
                checkpoint(state, "Rendering video", detail, Math.max(20, Math.min(96, 20 + (int) (progress * .76))), project.id);
            }

            @Override public void onCompleted(File file, JSONObject result) {
                completed.set(file);
                encoderReceipt.set(result);
                latch.countDown();
            }

            @Override public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        if (ownedHandle == null) throw new IllegalStateException("Could not start native render");
        synchronized(this){activeRender=ownedHandle;activeRenderJobId=state.id;}
        final long deadline=android.os.SystemClock.elapsedRealtime()+30*60*1000L;
        try {

        while (!latch.await(550, TimeUnit.MILLISECONDS)) {
            if (Thread.currentThread().isInterrupted()||"cancelled".equals(state.state)) {
                ownedHandle.cancel();
                throw new InterruptedException();
            }
            if(android.os.SystemClock.elapsedRealtime()>deadline){ownedHandle.cancel();throw new IllegalStateException("Native encoding exceeded its bounded 30 minute execution window");}
        }
        if("cancelled".equals(state.state)||Thread.currentThread().isInterrupted())throw new InterruptedException();
        if (error.get() != null) throw new IllegalStateException(error.get());
        File ready = completed.get();
        if (ready == null || !ready.exists() || ready.length() == 0) throw new IllegalStateException("Native export produced no file");

        JSONObject encoder=encoderReceipt.get();
        if(encoder!=null){JSONObject result=state.result==null?new JSONObject():new JSONObject(state.result.toString());
            result.put("nativeEncoderReceipt",new JSONObject(encoder.toString())).put("encodedClockVerified",false).put("decodedFrameCountVerified",false).put("cinematicAcceptance",false);state.setResult(result);}
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
        syncProtocolState();
        } finally {
            if(completed.get()==null)ownedHandle.cancel();
            synchronized(this){if(activeRender==ownedHandle){activeRender=null;activeRenderJobId="";}}
            if(temp.exists())temp.delete();
        }
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
     * Private inline small-media ingest used as a compatibility bridge when the normal
     * Worker handoff cannot deliver a ChatGPT attachment. The bytes travel inside the already
     * owner-authenticated MCP command and are written directly to app-private storage.
     * Nothing is published to Gallery or a public URL.
     */
    private JSONObject importInlineBase64(JSONObject p) throws Exception {
        ProjectStore.Project project = resolveProject(p.optString("projectId", ""));
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

        ProjectStore.Asset asset = addImportedAsset(project, file, name, mime);
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
            out.put("legacyInstallation",NativeApkUpdater.legacyInstallationStatus(this));
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
            out.put("chunkedFrameIngest", true);
            out.put("poseSequenceEngineReady", true);
            out.put("storyboardMotionPreflightReady", true);
            out.put("rigKinematicsMathReady", true);
            out.put("manualCharacterRigEditorReady", true);
            out.put("sourceArtworkInspectionReady", true);
            out.put("sourceRigPoseDiagnosticReady", true);
            out.put("sourceRigBatchAuthoringReady", true);
            out.put("sourceRigBatchProfileLimit", 8);
            out.put("sourceRigPreparationStatusReady", true);
            out.put("sourceRigWorkbenchMinimumVersion", "3.7.3");
            out.put("cinematic3d",cinematic3DCapabilities());
            out.put("workbenchRelayCompatibility",NativeWorkbenchBridge.capabilities());
            out.put("studioWorkbench",new JSONObject().put("minimumNativeAppVersion","3.9.0")
                    .put("sharedHumanAndMCP",true).put("sketchCanvasRenderer",true).put("sketchGeometryPaging",true)
                    .put("atomicClipEdits",true).put("clipKeyframes",true).put("chromaKey",true)
                    .put("featheredMasks",true).put("glow",true).put("timedImageOverlays",true)
                    .put("openSourceGenerationPipeline",true).put("commercialGenerationApis",false)
                    .put("driveVerifiedArchiveRequired",true).put("phoneGpuVerified",false));
            out.put("sourceRigAnatomicalInferenceAvailable", false);
            out.put("sourceTextured2DMeshSkinningReady", true);
            out.put("glbSkeletalPreviewReady", true);
            out.put("nativeHeadlessGltfExportReady", true);
            out.put("sharedCinematicActionDirectorReady", true);
            out.put("namedGltfActionClipRetimingReady", true);
            out.put("exact3DBladeMeshCollisionReady", false);
            out.put("encodedSoundtrackGeneratedAutomatically", false);
            out.put("sourceImageTo3DReconstructionInstalled", false);
            // Critical distinction: usable constraints are not the same as
            // character reconstruction or a textured 3D rigged renderer.
            out.put("sourceFaithfulRiggedRendererInstalled", false);
            out.put("full3DSkeletalRendererInstalled", true);
            out.put("animationDirectorReady", true);
            out.put("combatRigBenchmarkReady", true);
            out.put("combatRigBackend", "articulated-two-bone-IK-v1");
            out.put("combatRigBackgroundLocked", true);
            out.put("nativeSourcePlateEstimate", true);
            out.put("animationDirectorModes", "native-direct,experimental-flow");
            out.put("flowSceneCutProtection", true);
            out.put("nativeAnimationMaxAuthoredFrames", NativeAnimationDirector.MAX_SOURCE_FRAMES);
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
            out.put("legacyInstallation",NativeApkUpdater.legacyInstallationStatus(this));
            out.put("directAttachmentIngest", true);
            out.put("inlineAttachmentIngest", true);
            out.put("chunkedFrameIngest", true);
            out.put("poseSequenceEngine", "native-bidirectional-optical-flow-v1");
            out.put("storyboardArtworkEngine", "two-authored-poses-masked-texture-mesh-v1");
            out.put("storyboardMultigenreEngine", "source-multi-object-multi-shot-v1");
            out.put("storyboardMaxSourceFrames", 160);
            out.put("storyboardMaxFramesPerBatch", 20);
            out.put("storyboardAutoSegmentation", "source-difference-plus-bundled-person-ML");
            out.put("storyboardOriginalIllustrationStyle", true);
            out.put("storyboardOfflineFoleySynthesis", true);
            out.put("storyboardNeuralDiffusionInstalled", false);
            out.put("storyboardArtworkRequiresFourTracedActorRigs", true);
            out.put("neuralStoryboardModelAvailable", false);
            out.put("workbenchRelayCompatibility",NativeWorkbenchBridge.capabilities());
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
                out.put("activeProjectUpdatedAt", active.updatedAt);
                out.put("studioLayers",StudioLayerContract.toJson(active.studioLayers));
                out.put("studioLayerEditingSupported",true);
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
        String action = command.optString("action", "");
        JSONObject p = command.optJSONObject("parameters");
        String projectId = "animate_pose_sequence".equals(action)
                ? result.optString("projectId", p == null ? "" : p.optString("projectId", ""))
                : (p == null ? "" : p.optString("projectId", result.optString("projectId", "")));

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
            case "sketch_render":
            case "open_pipeline_export_request": case "open_pipeline_submit": case "open_pipeline_download_result":
            case "open_pipeline_import_result": case "open_pipeline_archive_artifact": case "open_pipeline_restore_artifact":
            case "prompt_video":
            case "animate_images":
            case "animate_storyboard_series":
            case "animate_storyboard_artwork":
            case "storyboard_series_audit":
            case "render_rigged_2d":
            case "render_gltf_combat":
            case "cinematic_3d_render":
            case "export_project":
            case "autonomous_edit":
            case "generate_image":
            case "generate_voice":
            case "run_creative_graph":
            case "install_model_pack":
            case "sync_project_to_drive":
            case "offload_project_to_drive":
            case "restore_project_from_drive":
            case "migrate_legacy_cloud_projects":
            case "archive_model_pack_to_drive":
            case "restore_model_pack_from_drive":
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

        boolean requirePlayableOutput = ExecutionTruthPolicy.requiresValidatedMediaOutput(action);
        if ("animate_images".equals(action)
                && queuedResult != null
                && !queuedResult.optBoolean("render", true)) {
            requirePlayableOutput = false;
        }
        if ("animate_combat_rig".equals(action)
                && queuedResult != null
                && !queuedResult.optBoolean("render", true)) {
            requirePlayableOutput = false;
        }
        if ("animate_pose_sequence".equals(action)
                && queuedResult != null
                && !queuedResult.optBoolean("render", true)) {
            requirePlayableOutput = false;
        }
        if (("render_rigged_2d".equals(action) || "render_gltf_combat".equals(action) || "cinematic_3d_render".equals(action))
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
            out.put("outputUri", outputUri);
            out.put("outputName", outputName == null ? "" : outputName);
            out.put("projectId", project.id);
            out.put("assetCount", project.assets.size());
            out.put("clipCount", project.clips.size());
            out.put("durationMs", project.outputDurationMs());
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

    private String friendlyAction(String action) {
        if (action == null) return "ChatGPT action";
        switch (action) {
            case "create_project": return "Creating project";
            case "select_project": return "Opening project";
            case "delete_project": return "Deleting project";
            case "apply_edit_plan": return "Building timeline";
            case "set_studio_layers": return "Editing project music and captions";
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
            case "migrate_legacy_cloud_projects": return "Recovering old project archives into VideoStudio Next";
            case "archive_model_pack_to_drive": return "Archiving model pack to cloud workspace";
            case "restore_model_pack_from_drive": return "Restoring model pack from cloud workspace";
            case "animate_pose_sequence": return "Synthesizing native pose in-betweens";
            case "append_frame_chunk": return "Importing private animation frame chunk";
            case "finish_frame_transfer": return "Verifying complete private frame";
            case "frame_transfer_status": return "Checking private frame transfer";
            case "animate_combat_rig": return "Generating articulated stickman sword combat";
            case "save_source_rig_profile": return "Saving original source-character rig";
            case "inspect_source_artwork": return "Inspecting original source artwork";
            case "preview_source_rig": return "Previewing a source-rig diagnostic pose";
            case "save_source_rig_profiles": return "Saving original source-character rig batch";
            case "source_rig_status": return "Reading ordered source-rig preparation";
            case "list_source_rig_profiles": return "Reading original source-character rigs";
            case "render_rigged_2d": return "Animating original masked fighters through native 2D mesh skinning";
            case "cinematic_3d_render": return "Rendering articulated cinematic 3D scenes";
            case "cinematic_3d_capabilities": return "Reading cinematic 3D capabilities";
            case "precision_edit": return "Applying exact clip edits";
            case "sketch_create": return "Creating a sketch animation";
            case "sketch_edit": case "sketch_undo": case "sketch_redo": return "Editing sketch layers and poses";
            case "sketch_get": case "sketch_list": return "Reading sketch geometry";
            case "sketch_preview": return "Rendering a sketch preview";
            case "sketch_import_reference": return "Importing an owned sketch reference";
            case "sketch_render": return "Exporting sketch animation";
            case "open_pipeline_configure": return "Configuring the private open-source runner";
            case "open_pipeline_capabilities": return "Reading actual runner readiness";
            case "open_pipeline_list": case "open_pipeline_status": return "Reading open-source generation jobs";
            case "open_pipeline_export_request": return "Preparing an immutable generation request";
            case "open_pipeline_submit": return "Submitting an open-source generation job";
            case "open_pipeline_cancel": return "Cancelling open-source generation";
            case "open_pipeline_download_result": case "open_pipeline_import_result": return "Importing a verified generation result";
            case "open_pipeline_archive_artifact": case "open_pipeline_restore_artifact": return "Transferring a verified cloud artifact";
            case "animate_storyboard_artwork": return "Animating original storyboard keyframes with source-textured character motion";
            case "storyboard_series_plan": return "Planning original illustrated multi-genre storyboard shots";
            case "storyboard_series_audit": return "Inspecting actual artwork movement and source masks";
            case "animate_storyboard_series": return "Producing original-artwork multi-shot video and synchronized sound";
            case "storyboard_artwork_preflight": return "Checking whether real imported storyboard actors are ready to animate";
            case "render_gltf_combat": return "Rendering two real glTF skinned 3D characters into Media3 video";
            case "animate_timeline": return "Directing cinematic image sequence";
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

