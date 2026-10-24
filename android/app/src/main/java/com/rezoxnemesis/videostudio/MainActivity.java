package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements AppProtocol.Callback {
    private static final int PICK_MEDIA = 1201;
    private static final int PICK_CLOUD_WORKSPACE = 1202;
    private static final int PICK_UPDATE_APK = 1203;
    private static final int PICK_VIDEO_OVERLAY_FILE = 1204;
    private static final int C_BG = StudioUi.BG;
    private static final int C_CARD = StudioUi.SURFACE;
    private static final int C_CARD_2 = StudioUi.SURFACE_ALT;
    private static final int C_TEXT = StudioUi.TEXT;
    private static final int C_MUTED = StudioUi.MUTED;
    private static final int C_CYAN = StudioUi.ACCENT;
    private static final int C_BLUE = StudioUi.SURFACE_ALT;
    private static final int C_PURPLE = StudioUi.SURFACE_ALT;
    private static final int C_MAGENTA = StudioUi.ACCENT;
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_MODE = "permission_mode";
    private static final String KEY_FILE = "allowed_asset_id";
    private static final String KEY_AUTONOMY_MIGRATED = "autonomy_everything_v32_migrated";

    private ProjectStore store;
    private JobManager jobs;
    private AppProtocol protocol;
    private NativeRenderEngine renderEngine;
    private NativeRenderEngine.Handle activeRenderHandle;
    private PromptVideoEngine promptVideoEngine;
    private NativeMediaAnalyzer mediaAnalyzer;
    private DriveWorkspaceProvider driveWorkspace;
    private PreviewSnapshotStore previewSnapshots;
    private ProxyManager proxyManager;
    private SharedPreferences prefs;
    private FrameLayout content;
    private View appNavigation;
    private TextView connectionPill;
    private ProjectStore.Project activeProject;
    private ProjectStore.Clip selectedClip;
    private LiveEditPlayer livePlayer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable activityRefresh;
    private Runnable serviceWatchdog;
    private boolean timelinePreviewRunning;
    private Runnable stillTimelineNext;
    private StudioThumbnailLoader studioThumbs;
    private StudioAudioWaveforms studioAudioWaveforms;
    private StudioAudioPreviewEngine studioAudioPreview;
    private final StudioEditHistory editHistory=new StudioEditHistory();
    private ImageView studioStillPreview;
    private TextView studioCaptionPreview;
    private Runnable studioCaptionClock;
    private FrameLayout studioPreviewFrame;
    private StudioTouchTransformOverlay studioTouchTransform;
    private Button studioTouchExit;
    private TextView studioPreviewErrorView;
    private Button studioPreviewRetry;
    private String studioPreviewError="";
    private String studioPreviewErrorProjectId="";
    private long studioPreviewErrorRevision=-1;
    private TextView studioTimeLabel;
    private SeekBar studioSeek;
    private StudioTracksView studioTracks;
    private Button studioSplitButton;
    private int studioTrackZoom=1;
    private boolean studioTrackSnap=true;
    private int studioToolCategory=0;
    private AlertDialog studioToolDialog;
    private AlertDialog studioImageMotionDialog;
    private AutoCloseable studioVideoEditor;
    private StudioAudioEnvelopeDialog studioAudioEnvelopeDialog;
    private final java.util.concurrent.ConcurrentHashMap<String,AudioEnvelopeCompletion> audioEnvelopeCommands=new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService audioEnvelopeCompletions;
    private boolean audioEnvelopeCommandsClosed;
    private SelectedVideoImportDialog selectedVideoImport;
    private SelectedVideoPick selectedVideoPick;
    private static final class SelectedVideoPick {
        final String projectId,operationId;final long revision,generation;
        SelectedVideoPick(String projectId,long revision,long generation){this.projectId=projectId;this.revision=revision;this.generation=generation;operationId=UUID.randomUUID().toString();}
    }
    private long studioSurfaceGeneration;
    private StudioEditorWorkspace studioWorkspace;
    private String studioWorkspacePane="Timeline",studioInspectorProjectId="",studioSelectedLayerId;
    private String studioPreviewAspect="9:16";
    private Button studioPlayToggle;
    private View studioEditedTransport,studioSavedRenderTransport;
    private enum StudioPreviewTarget{EDITED,SAVED_RENDER,SOURCE_VIDEO,SOURCE_STILL}
    private StudioPreviewTarget studioPreviewTarget=StudioPreviewTarget.EDITED;
    private String studioPreviewTargetProjectId="",studioPreviewTargetAssetId="",studioPreviewTargetName="";
    private TextView studioPreviewTargetLabel;
    private String studioEditedProjectId="";
    private long studioEditedRevision=-1;
    private boolean studioPendingEditedSeek;
    private int studioTimelinePage=0;
    private int studioMediaPage=0;
    private long studioPlayheadMs=0;
    private boolean studioShowMedia=false;
    private String currentScreen = "home";
    private NativeEditorStartupGuard editorStartupGuard;
    private Runnable markLaunchHealthy;
    private boolean editorBootstrapReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NativeJavaCrashJournal.install(this);
        editorStartupGuard=new NativeEditorStartupGuard(this);
        NativeEditorStartupGuard.Admission boot=editorStartupGuard.begin(System.currentTimeMillis());
        if(boot.rescueMode){
            showSafeStartScreen(null);
            return;
        }
        Window window = getWindow();
        window.setStatusBarColor(C_BG);
        window.setNavigationBarColor(C_BG);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        try {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        studioPreviewAspect=StudioPreviewCanvas.coerce(prefs.getString("studio_preview_canvas_aspect","9:16"));
        migrateAutonomyDefaultOnce();
        editorStartupGuard.stage("loading_project_store");
        store = new ProjectStore(this);
        // UI/local proxy work must never rewrite the native MCP job journal.
        jobs = new JobManager(this,"videostudio_editor_local_jobs_v2");
        studioThumbs = new StudioThumbnailLoader(this);
        // Construct Media3 audio components only when the human opens the editor.
        // Instantiating ExoPlayer/AudioTrack during splash consumed scarce native
        // decoder memory while the agent might also be recovering a render.
        editorStartupGuard.stage("creating_media_factories");
        renderEngine = new NativeRenderEngine(this);
        promptVideoEngine = new PromptVideoEngine(this);
        mediaAnalyzer = new NativeMediaAnalyzer(this);
        driveWorkspace = new DriveWorkspaceProvider(this);
        previewSnapshots = new PreviewSnapshotStore(this);
        proxyManager = new ProxyManager(this, store, jobs);
        // ExoPlayer is heavyweight; lazy startup leaves media codecs untouched
        // until a preview or editor playback is explicitly requested.
        editorStartupGuard.stage("loading_active_project");
        activeProject = store.active();
        editorStartupGuard.stage("creating_native_protocol");
        protocol = new AppProtocol(this, this);
        // Update the local protocol snapshot without starting the foreground
        // service during our first activity frame. The explicit delayed launch
        // below starts it only after Home is constructed and touch responsive.
        protocol.setLocalState(permissionMode(),store.summaries());

        editorStartupGuard.stage("building_home");
        setContentView(buildShell());
        showHome();
        editorBootstrapReady=true;
        editorStartupGuard.stage("home_shown");
        handleMcpRebindIntent(getIntent());
        // The home screen becomes responsive before expensive native recovery.
        ui.postDelayed(this::requestServiceSync,750L);
        ui.postDelayed(this::startServiceWatchdog,1200L);
        markLaunchHealthy=()->{
            if(editorBootstrapReady&&editorStartupGuard!=null)
                editorStartupGuard.success();
        };
        ui.postDelayed(markLaunchHealthy,5000L);
        }catch(RuntimeException|LinkageError startupFailure){
            editorBootstrapReady=false;
            if(editorStartupGuard!=null)editorStartupGuard.failed(startupFailure);
            showSafeStartScreen(startupFailure);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleMcpRebindIntent(intent);
    }

    private void handleMcpRebindIntent(Intent intent) {
        if (intent == null || intent.getData() == null || protocol == null) return;
        Uri data = intent.getData();
        if (!"videostudio".equalsIgnoreCase(data.getScheme())
                || !"mcp-rebind".equalsIgnoreCase(data.getHost())) return;
        String token = data.getQueryParameter("token");
        if (token == null || token.trim().length() < 30) {
            Toast.makeText(this, "Invalid VideoStudio MCP rebind link", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
                .putBoolean("control_service_online", false)
                .putString("control_service_detail", "Rebinding stable MCP endpoint…")
                .putLong("control_service_heartbeat", System.currentTimeMillis())
                .apply();
        Toast.makeText(this, "Rebinding the existing ChatGPT connection…", Toast.LENGTH_SHORT).show();
        protocol.redeemRebind(token.trim());
        ui.postDelayed(this::requestServiceSync, 900);
    }

    @Override
    protected void onDestroy() {
        closeStudioAudioEnvelope();
        closeSelectedVideoImport();selectedVideoPick=null;
        closeStudioVideoEditor();
        stopStudioDirectTouch();
        if(studioImageMotionDialog!=null)studioImageMotionDialog.dismiss();
        timelinePreviewRunning = false;
        if(stillTimelineNext!=null)ui.removeCallbacks(stillTimelineNext);
        if(studioCaptionClock!=null)ui.removeCallbacks(studioCaptionClock);
        if(studioThumbs!=null)studioThumbs.shutdown();
        if(studioAudioWaveforms!=null)studioAudioWaveforms.shutdown();
        if(studioAudioPreview!=null)studioAudioPreview.release();
        if (activityRefresh != null) ui.removeCallbacks(activityRefresh);
        if (serviceWatchdog != null) ui.removeCallbacks(serviceWatchdog);
        if(markLaunchHealthy!=null)ui.removeCallbacks(markLaunchHealthy);
        if(editorBootstrapReady&&editorStartupGuard!=null)editorStartupGuard.success();
        if (livePlayer != null) livePlayer.release();
        if (activeRenderHandle != null) activeRenderHandle.cancel();
        closeAudioEnvelopeCommands();
        if (protocol != null) protocol.stop();
        if (jobs != null) jobs.shutdown();
        super.onDestroy();
    }

    /** Last-resort landing page for failures in project/editor preparation.
     * No database deletion, preference reset or Gallery access is attempted. */
    private void showSafeStartScreen(Throwable failure){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(45),dp(22),dp(20));
        root.setBackgroundColor(C_BG);
        TextView heading=new TextView(this);
        heading.setText("VideoStudio safe startup");
        heading.setTextSize(24);heading.setTextColor(C_TEXT);
        root.addView(heading);
        TextView detail=new TextView(this);
        detail.setText("The editing workspace could not initialize. Your saved projects and imported files have NOT been deleted. "+
                "Please keep the app installed and share the startup diagnostic.");
        detail.setTextSize(15);detail.setTextColor(C_MUTED);
        detail.setPadding(0,dp(14),0,dp(14));
        root.addView(detail);
        JSONObject checkpoint=editorStartupGuard==null?new JSONObject():editorStartupGuard.status();
        String summary="VideoStudio "+AppProtocol.APP_VERSION+" safe startup; "+
                "previousInterruptedStage="+checkpoint.optString("previousInterruptedStage","unknown")+
                "; lastAppFrame="+checkpoint.optString("lastAppFrame","unknown")+
                "; lastStage="+checkpoint.optString("lastStage","unknown")+
                "; lastJavaFailure="+checkpoint.optString("lastJavaFailure","unknown")+
                "; rapidFailures="+checkpoint.optInt("rapidFailures",0)+
                "; projectDataCleared=false; galleryBrowsed=false";
        detail.setText("VideoStudio stopped during startup. Your source projects and "+
                "imported files are preserved. No native media render was started "+
                "by this recovery screen.\n\nLast stage: "+
                checkpoint.optString("previousInterruptedStage",checkpoint.optString("lastStage","unknown"))+
                "\nJava error type: "+checkpoint.optString("lastJavaFailure","unknown")+
                "\nLast app code frame: "+checkpoint.optString("lastAppFrame","not_available")+
                "\nNative SIGKILL or OOM may not produce a Java trace.");
        String note=summary;
        Button copy=new Button(this);
        copy.setText("Copy safe diagnostic");
        copy.setOnClickListener(v->{
            android.content.ClipboardManager clipboard=
                    (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("VideoStudio startup",note));
            Toast.makeText(this,"Startup diagnostic copied",Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);
        Button retry=new Button(this);
        retry.setText("Retry safe launch");
        retry.setOnClickListener(v->{
            if(editorStartupGuard!=null)editorStartupGuard.allowNormalOnce();
            recreate();
        });
        root.addView(retry);
        setContentView(root);
    }

    private LiveEditPlayer player(){
        if(livePlayer==null)livePlayer=new LiveEditPlayer(this);
        return livePlayer;
    }

    private void ensureEditorMedia(){
        if(studioAudioWaveforms==null)studioAudioWaveforms=new StudioAudioWaveforms(this);
        if(studioAudioPreview==null)studioAudioPreview=new StudioAudioPreviewEngine(this);
    }

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(8), dp(8), dp(10));
        nav.setBackground(rounded(C_CARD, 0, dp(0)));
        nav.addView(navButton("⌂", "Home", () -> showHome()), weight());
        nav.addView(navButton("▣", "Editor", () -> showEditor()), weight());
        nav.addView(navButton("◎", "Activity", this::showActivity), weight());
        nav.addView(navButton("✦", "Tools", () -> showTools()), weight());
        nav.addView(navButton("⚙", "Agent", () -> showControl()), weight());
        nav.setMinimumHeight(dp(72));
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appNavigation=nav;
        return root;
    }

    private void setScreen(View view, String name) {
        if (activityRefresh != null) ui.removeCallbacks(activityRefresh);
        closeStudioAudioEnvelope();
        closeSelectedVideoImport();
        studioSurfaceGeneration++;
        currentScreen = name;
        StudioUi.prepareTouchLayout(view);
        if(appNavigation!=null)appNavigation.setVisibility("editor".equals(name)?View.GONE:View.VISIBLE);
        if("editor".equals(name))StudioEditorWorkspace.applyQuiet(view);
        getWindow().setStatusBarColor("editor".equals(name)?StudioEditorWorkspace.BG:C_BG);
        getWindow().setNavigationBarColor("editor".equals(name)?StudioEditorWorkspace.BG:C_BG);
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** A creator dashboard first, with autonomous control always accessible. */
    private void showHome() {
        if(activeProject==null)activeProject=store.active();
        ScrollView scroll=baseScroll();
        LinearLayout box=column();
        box.setPadding(dp(15),dp(17),dp(15),dp(28));
        scroll.addView(box);
        box.addView(brandHeader());

        LinearLayout hero=card(true);
        hero.addView(title("Make something",26));
        hero.addView(body(activeProject==null?
                "Create, import, edit and play videos right here on your phone.":
                "Continue  ·  "+activeProject.name));
        Button resume=neonButton(activeProject==null?"Create a project":
                "Continue editing",C_MAGENTA);
        resume.setOnClickListener(v->{
            if(activeProject==null)createProjectDialog();else showEditor();
        });
        hero.addView(resume,margins(-1,dp(54),dp(10),dp(4),0,0));
        LinearLayout actions=new LinearLayout(this);
        actions.addView(studioButton("+ New Project",this::createProjectDialog),
                new LinearLayout.LayoutParams(0,dp(45),1));
        actions.addView(studioButton("+ Import",this::pickMedia),
                new LinearLayout.LayoutParams(0,dp(45),1));
        actions.addView(studioButton("All Renders ▶",this::showStudioRenderLibrary),
                new LinearLayout.LayoutParams(0,dp(45),1));
        hero.addView(actions,margins(-1,dp(48),dp(6),0,0,0));
        box.addView(hero,margins(-1,-2,0,dp(9),0,0));

        JSONObject nativeWork=NativeWorkMonitor.snapshot(this);
        LinearLayout nativeStrip=card(false);
        nativeStrip.setPadding(dp(13),dp(12),dp(13),dp(12));
        LinearLayout connectionRow=new LinearLayout(this);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=title(nativeWork.optBoolean("nativeServiceOnline",false)?
                "●  ChatGPT native agent connected":"◌  Connect ChatGPT",15);
        label.setTextColor(nativeWork.optBoolean("nativeServiceOnline",false)?C_CYAN:C_TEXT);
        connectionRow.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        connectionRow.addView(studioButton("Manage ›",this::showStudioConnectionHub),
                new LinearLayout.LayoutParams(dp(100),dp(41)));
        nativeStrip.addView(connectionRow);
        String jobName=nativeWork.optString("jobName","");
        if(nativeWork.optInt("active",0)>0){
            nativeStrip.addView(body(nativeWork.optInt("active",0)+" active • "+
                    jobName+"  "+nativeWork.optInt("jobProgress",0)+"%"));
        }else nativeStrip.addView(body("Manage your connection and background work."));
        box.addView(nativeStrip,margins(-1,-2,dp(4),0,0,0));
        NativeStartupGuard nativeRecovery=new NativeStartupGuard(this);
        if(nativeRecovery.held()){
            LinearLayout recoveryCard=card(false);
            recoveryCard.addView(title("Interrupted render safely paused",17));
            recoveryCard.addView(body(
                    "A previous rendering task could not finish. VideoStudio has preserved "+
                    "the source, saved projects and native job checkpoints. "+
                    "Automatic heavy recovery is held to prevent another startup crash."));
            recoveryCard.addView(body(nativeRecovery.reason()));
            Button retry=studioButton("Retry saved render (optional)",()->{
                new AlertDialog.Builder(this)
                        .setTitle("Retry interrupted native render?")
                        .setMessage("Only retry after confirming the app opens and its "+
                                "projects load normally. The saved render might still "+
                                "exceed your device's memory or fail. No Gallery access is used.")
                        .setNegativeButton("Keep paused",null)
                        .setPositiveButton("Retry once",(d,w)->{
                            try{
                                startForegroundService(new Intent(this,ControlService.class)
                                        .setAction(ControlService.ACTION_RETRY_RECOVERY));
                                Toast.makeText(this,"Retry requested; track status in Activity",
                                        Toast.LENGTH_LONG).show();
                            }catch(Exception err){
                                Toast.makeText(this,"Retry not started: "+err.getClass().getSimpleName(),
                                        Toast.LENGTH_LONG).show();
                            }
                        }).show();
            });
            recoveryCard.addView(retry,margins(-1,dp(46),dp(8),0,0,0));
            box.addView(recoveryCard,margins(-1,-2,dp(9),0,0,0));
        }
        NativeStartupGuard launchGuard=new NativeStartupGuard(this);
        if(launchGuard.held()){
            LinearLayout safety=card(false);
            safety.addView(title("Safe startup • previous render paused",17));
            safety.addView(body("The native renderer may have interrupted the last session. "+
                    "VideoStudio has kept the project's source, imported assets and recovery record. "+
                    "Heavy jobs will not restart automatically while recovery is held."));
            safety.addView(body(launchGuard.reason()));
            Button retry=studioButton("Retry interrupted work (optional)",this::retryHeldRecovery);
            safety.addView(retry,margins(-1,dp(48),dp(10),0,0,0));
            box.addView(safety,margins(-1,-2,dp(10),0,0,0));
        }

        LinearLayout projectsTop=new LinearLayout(this);
        projectsTop.setGravity(Gravity.CENTER_VERTICAL);
        projectsTop.addView(title("Projects",20),new LinearLayout.LayoutParams(0,-2,1));
        projectsTop.addView(studioButton("View All ›",this::showStudioProjectLibrary),
                new LinearLayout.LayoutParams(dp(107),dp(44)));
        box.addView(projectsTop,margins(-1,-2,dp(16),dp(6),0,0));
        List<ProjectStore.Project> projects=store.listRecent(8);
        if(projects.isEmpty())box.addView(body("No projects yet. Create one and import your clips."));
        else for(int i=0;i<Math.min(8,projects.size());i++)
            box.addView(projectRow(projects.get(i)),margins(-1,-2,0,dp(6),0,0));

        LinearLayout creative=card(false);
        creative.addView(title("Explore the studio",18));
        creative.addView(body("Draw, animate characters, build 3D scenes and find every editing tool."));
        creative.addView(studioButton("Search tools",this::findStudioTool),margins(-1,-2,dp(10),dp(6),0,0));
        LinearLayout links=new LinearLayout(this);
        links.addView(studioButton("Browse tools",this::showTools),
                new LinearLayout.LayoutParams(0,dp(46),1));
        links.addView(studioButton("Animation Director",this::animationDirectorDialog),
                new LinearLayout.LayoutParams(0,dp(46),1));
        creative.addView(links,margins(-1,dp(46),dp(7),0,0,0));
        LinearLayout authoring=new LinearLayout(this);
        authoring.addView(studioButton("Sketch Studio",()->startActivity(new Intent(this,SketchStudioActivity.class))),
                new LinearLayout.LayoutParams(0,dp(46),1));
        authoring.addView(studioButton("Open Pipeline",this::openPipelineStudio),
                new LinearLayout.LayoutParams(0,dp(46),1));
        creative.addView(authoring,margins(-1,dp(46),dp(7),0,0,0));
        box.addView(creative,margins(-1,-2,dp(16),0,0,0));
        setScreen(scroll,"home");
    }

    private void retryHeldRecovery(){
        new AlertDialog.Builder(this).setTitle("Retry interrupted native work?")
                .setMessage("The previous media render may have caused the app to restart. "+
                        "Your projects remain safe while it is paused. "+
                        "Retry only after VideoStudio has remained open normally; another render may use significant RAM.")
                .setNegativeButton("Keep paused",null)
                .setPositiveButton("Retry once",(d,w)->{
                    try{
                        Intent request=new Intent(this,ControlService.class)
                                .setAction(ControlService.ACTION_RETRY_RECOVERY);
                        startForegroundService(request);
                        Toast.makeText(this,"Retry requested; source projects preserved",
                                Toast.LENGTH_LONG).show();
                    }catch(Exception failure){
                        Toast.makeText(this,"Retry unavailable: "+failure.getClass().getSimpleName(),
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    /** Both delivery routes use the existing device-owned Android identity. */
    private void showStudioConnectionHub(){
        JSONObject state=NativeWorkMonitor.snapshot(this);
        boolean online=state.optBoolean("nativeServiceOnline",false);
        LinearLayout body=column();
        body.setPadding(dp(18),dp(3),dp(18),dp(5));
        body.addView(title(online?"● Native Agent connected":"◌ Native Agent reconnecting",19));
        body.addView(body(serviceConnectionText()),margins(-1,-2,dp(9),0,0,0));
        body.addView(body("Share your app key to connect through encrypted GitHub delivery, or share the existing MCP connection. "+
                "The backup can deliver tasks while the MCP command store is unavailable. "+
                "Remote result delivery still uses the relay's separate metadata service."),
                margins(-1,-2,dp(9),0,0,0));
        body.addView(body("Android can suspend background execution during Doze or after force-stop. "+
                "Pending commands resume when the native executor reconnects; no system can guarantee "+
                "uninterrupted work after Android has force-stopped the app."),
                margins(-1,-2,dp(9),0,0,0));
        Button appKey=studioButton("Share app key",this::shareAppKey);
        body.addView(appKey,margins(-1,-2,dp(12),0,0,0));
        Button settings=studioButton("Agent settings",this::showControl);
        body.addView(settings,margins(-1,-2,dp(6),0,0,0));
        Button connect=studioButton("Share MCP connection",this::sharePairing);
        body.addView(connect,margins(-1,dp(49),dp(12),0,0,0));
        Button reconnect=studioButton("Repair native connection",()->{
            try{
                Intent intent=new Intent(this,ControlService.class)
                        .setAction(ControlService.ACTION_RECONNECT);
                startForegroundService(intent);
                requestServiceSync();
                Toast.makeText(this,"Refreshing the existing private MCP connection",
                        Toast.LENGTH_LONG).show();
            }catch(Exception e){
                Toast.makeText(this,"Reconnect failed: "+e.getMessage(),Toast.LENGTH_LONG).show();
            }
        });
        body.addView(reconnect,margins(-1,dp(49),dp(6),0,0,0));
        new AlertDialog.Builder(this).setTitle("VideoStudio ↔ ChatGPT")
                .setView(body).setPositiveButton("Done",null).show();
    }

    private void showLegacyHome() {
        ScrollView scroll = baseScroll();
        LinearLayout box = column();
        box.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(box);

        box.addView(brandHeader());

        LinearLayout hero = card(true);
        TextView heroTitle = title("Create Without Limits", 28);
        hero.addView(heroTitle);
        hero.addView(body("Native " + AppProtocol.APP_VERSION + " Creative Runtime • stable MCP compatibility core • on-device portrait AI • MotionScript/CreativeIR • local Media3 export"));
        Button promptVideo = neonButton("✦  Create Video from a Prompt", C_MAGENTA);
        promptVideo.setOnClickListener(v -> promptVideoDialog());
        hero.addView(promptVideo, margins(-1, dp(54), dp(14), dp(8), 0, 0));
        Button create = compactButton("+ New project");
        create.setOnClickListener(v -> createProjectDialog());
        hero.addView(create, margins(-1, dp(46), 0, dp(4), 0, 0));
        box.addView(hero, margins(-1, -2, 0, dp(22), 0, 0));

        LinearLayout connect = card(false);
        connect.setBackground(neonCard());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.rezoxnemesis.videostudio.R.drawable.logo_vs);
        row.addView(logo, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout copy = column();
        copy.setPadding(dp(12), 0, 0, 0);
        copy.addView(title("Connect ChatGPT", 19));
        connectionPill = body(serviceConnectionText());
        copy.addView(connectionPill);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = title("›", 34);
        row.addView(arrow);
        connect.addView(row);
        connect.setOnClickListener(v -> sharePairing());
        box.addView(connect, margins(-1, -2, 0, dp(16), 0, 0));

        LinearLayout live = card(false);
        LinearLayout liveTop = new LinearLayout(this);
        liveTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout liveCopy = column();
        liveCopy.addView(title("◎  Live ChatGPT Activity", 18));
        JSONArray latestActivity = ActivityLog.recentWork(this, 1);
        JSONObject latest = latestActivity.optJSONObject(0);
        liveCopy.addView(body(latest == null ? "No autonomous actions yet" : latest.optString("action") + " • " + latest.optString("status")));
        liveTop.addView(liveCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        liveTop.addView(title("›", 30));
        live.addView(liveTop);
        live.addView(accent("ONLY ACTIONS EXECUTED INSIDE VIDEOSTUDIO COUNT", C_CYAN));
        live.setOnClickListener(v -> showActivity());
        box.addView(live, margins(-1, -2, 0, dp(16), 0, 0));

        box.addView(section("Quick Actions"));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(actionTile("+", "New Project", C_PURPLE, this::createProjectDialog), weightWithMargin());
        quick.addView(actionTile("▧", "Import Media", C_BLUE, this::pickMedia), weightWithMargin());
        quick.addView(actionTile("✦", "AI Edit", C_MAGENTA, () -> showTools()), weightWithMargin());
        quick.addView(actionTile("◉", "Animate Stills", C_CYAN, this::animateImagesDialog), weightWithMargin());
        box.addView(quick);

        box.addView(section("Recent Projects"));
        List<ProjectStore.Project> projects = store.list();
        if (projects.isEmpty()) {
            TextView empty = body("No projects yet. Create one, then import videos from the Android file picker.");
            empty.setPadding(dp(8), dp(14), dp(8), dp(20));
            box.addView(empty);
        } else {
            for (ProjectStore.Project project : projects.subList(0, Math.min(6, projects.size()))) {
                box.addView(projectRow(project));
            }
        }

        box.addView(section("Foundation"));
        LinearLayout foundation = card(false);
        foundation.addView(title("Creator-grade native foundation", 17));
        foundation.addView(body("Media3 layered export • bundled person segmentation + face mesh • 2.5D parallax • keyframed motion • crash recovery • thermal/RAM governor • no Gallery browsing permission."));
        box.addView(foundation);

        setScreen(scroll, "home");
    }

    private void showActivity() {
        ScrollView scroll = baseScroll();
        LinearLayout box = column();
        box.setPadding(dp(16), dp(18), dp(16), dp(30));
        scroll.addView(box);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingCopy = column();
        headingCopy.addView(title("ChatGPT Activity", 27));
        headingCopy.addView(body("Live record of actions actually executed inside VideoStudio."));
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView online = accent(prefs.getBoolean("control_service_online", false) ? "● LIVE" : "○ CONNECTING", prefs.getBoolean("control_service_online", false) ? Color.rgb(74,255,172) : C_MUTED);
        heading.addView(online);
        box.addView(heading);

        LinearLayout rule = card(false);
        rule.setBackground(neonCard());
        rule.addView(title("VideoStudio-only execution", 17));
        rule.addView(body("An edit is counted as complete only when VideoStudio itself imports, analyses, changes, renders or exports it. External-editor substitutes are not counted."));
        box.addView(rule, margins(-1, -2, dp(14), dp(12), 0, 0));

        LinearLayout controls = new LinearLayout(this);
        Button refresh = compactButton("Refresh");
        refresh.setOnClickListener(v -> showActivity());
        controls.addView(refresh);
        Button clear = compactButton("Clear");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear activity history?")
                .setMessage("This clears the local activity display only. It does not change projects or media.")
                .setPositiveButton("Clear", (d,w) -> {
                    ActivityLog.clear(this);
                    showActivity();
                })
                .setNegativeButton("Cancel", null)
                .show());
        controls.addView(clear);
        box.addView(controls, margins(-1, -2, 0, dp(10), 0, 0));

        JSONArray entries = ActivityLog.recent(this, 120);
        if (entries.length() == 0) {
            LinearLayout empty = card(false);
            empty.addView(title("Waiting for activity", 17));
            empty.addView(body("When ChatGPT imports a file, analyses footage, applies an edit, renders, retries or fails, it will appear here."));
            box.addView(empty);
        } else {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item != null) box.addView(activityCard(item), margins(-1, -2, 0, dp(8), 0, 0));
            }
        }

        setScreen(scroll, "activity");
        activityRefresh = () -> {
            if ("activity".equals(currentScreen)) showActivity();
        };
        ui.postDelayed(activityRefresh, 1500);
    }

    private View activityCard(JSONObject item) {
        LinearLayout card = card(false);
        String status = item.optString("status", "info");
        int statusColor = C_MUTED;
        String icon = "•";
        if ("success".equals(status)) { statusColor = Color.rgb(74,255,172); icon = "✓"; }
        else if ("failed".equals(status) || "denied".equals(status)) { statusColor = Color.rgb(255,95,125); icon = "×"; }
        else if ("running".equals(status)) { statusColor = C_CYAN; icon = "●"; }
        else if ("queued".equals(status)) { statusColor = C_PURPLE; icon = "◷"; }

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView action = title(icon + "  " + item.optString("action", "Activity"), 16);
        top.addView(action, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = body(formatActivityTime(item.optLong("time", System.currentTimeMillis())));
        time.setGravity(Gravity.RIGHT);
        top.addView(time);
        card.addView(top);

        String detail = item.optString("detail", "");
        if (!detail.isEmpty()) card.addView(body(detail));

        LinearLayout meta = new LinearLayout(this);
        meta.setPadding(0, dp(8), 0, 0);
        TextView source = accent("chatgpt".equals(item.optString("source")) ? "CHATGPT" : item.optString("source", "SYSTEM").toUpperCase(Locale.US), statusColor);
        meta.addView(source, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (item.has("progress")) {
            TextView percent = accent(item.optInt("progress") + "%", statusColor);
            meta.addView(percent);
        }
        card.addView(meta);

        if (item.has("progress") && !"success".equals(status)) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(item.optInt("progress"));
            card.addView(progress, margins(-1, dp(8), dp(6), 0, 0, 0));
        }

        String commandId = item.optString("commandId", "");
        String projectId = item.optString("projectId", "");
        if (!commandId.isEmpty() || !projectId.isEmpty()) {
            String ids = (!projectId.isEmpty() ? "project " + shortUiId(projectId) : "")
                    + (!commandId.isEmpty() ? ((!projectId.isEmpty() ? "  •  " : "") + "command " + shortUiId(commandId)) : "");
            TextView idsView = body(ids);
            idsView.setTextSize(10);
            idsView.setPadding(0, dp(5), 0, 0);
            card.addView(idsView);
        }
        return card;
    }

    private String formatActivityTime(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    private String shortUiId(String id) {
        if (id == null) return "";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /**
     * First-class HUMAN editing workspace.
     * Essential controls are always visible; advanced tools are grouped in
     * searchable categories instead of an unstructured 24-button wall.
     */
    private void showEditor() {
        stopStudioDirectTouch();
        ensureEditorMedia();
        if(studioCaptionClock!=null){ui.removeCallbacks(studioCaptionClock);studioCaptionClock=null;}
        if(studioAudioPreview!=null)studioAudioPreview.stop();
        if(activeProject==null){activeProject=store.active();if(activeProject==null)activeProject=store.create("Untitled Project");}
        ProjectStore.Project refreshed=store.get(activeProject.id);
        if(refreshed!=null&&refreshed.updatedAt!=activeProject.updatedAt){
            String selectedId=selectedClip==null?"":selectedClip.id;activeProject=refreshed;selectedClip=null;
            for(ProjectStore.Clip clip:activeProject.clips)if(selectedId.equals(clip.id)){selectedClip=clip;break;}
        }
        if(!activeProject.id.equals(studioInspectorProjectId)){
            studioInspectorProjectId=activeProject.id;studioSelectedLayerId=null;studioWorkspacePane="Timeline";
        }
        if(!activeProject.id.equals(studioPreviewTargetProjectId))setStudioPreviewTarget(StudioPreviewTarget.EDITED,"","");
        if(studioPreviewTarget==StudioPreviewTarget.SOURCE_VIDEO||studioPreviewTarget==StudioPreviewTarget.SOURCE_STILL){
            ProjectStore.Asset target=activeProject.asset(studioPreviewTargetAssetId);
            if(target==null||target.mime==null||!target.mime.startsWith(studioPreviewTarget==StudioPreviewTarget.SOURCE_STILL?"image/":"video/"))
                setStudioPreviewTarget(StudioPreviewTarget.EDITED,"","");
        }
        if(selectedClip==null&&!activeProject.clips.isEmpty())selectedClip=activeProject.clips.get(0);
        selectedStudioLayer();editHistory.reset(activeProject.id);
        long length=Math.max(1,activeProject.outputDurationMs());studioPlayheadMs=Math.max(0,Math.min(length,studioPlayheadMs));
        if(selectedClip!=null){
            StudioTimelineClock.Position position=StudioTimelineClock.locate(activeProject.clips,studioPlayheadMs);
            if(position.clipIndex<0||!selectedClip.id.equals(activeProject.clips.get(position.clipIndex).id))
                studioPlayheadMs=StudioTimelineEditOps.startOf(activeProject.clips,selectedClip.id);
        }

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(studioEditorIconButton("back","Back to Home",this::showHome),new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button project=studioButton("▾  "+activeProject.name,this::showStudioProjectLibrary);
        project.setTextSize(18);project.setSingleLine(true);project.setMaxLines(1);
        project.setEllipsize(android.text.TextUtils.TruncateAt.END);project.setContentDescription("Open project "+activeProject.name);
        header.addView(project,new LinearLayout.LayoutParams(0,dp(48),1));
        Button orientation=studioButton("↔",this::chooseEditorOrientation);
        orientation.setTextSize(18);orientation.setContentDescription("Editor orientation: auto, portrait or landscape");
        header.addView(orientation,new LinearLayout.LayoutParams(dp(42),dp(48)));
        JSONObject nativeState=NativeWorkMonitor.snapshot(this);boolean connected=nativeState.optBoolean("nativeServiceOnline",false);
        TextView nativeStatus=accent(connected?"●":"○",connected?C_CYAN:C_MUTED);nativeStatus.setGravity(Gravity.CENTER);
        nativeStatus.setTag("editor-native-status");
        nativeStatus.setContentDescription((connected?"Native connected":"Native reconnecting")+". Open Activity");
        nativeStatus.setOnClickListener(view->showStudioJobDetails());header.addView(nativeStatus,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button export=studioButton("Export",this::queueStudioExport);StudioUi.styleButton(export,true);
        export.setTag("editor-export");export.setCompoundDrawablesWithIntrinsicBounds(null,null,StudioEditorWorkspace.icon(this,"export",false),null);
        export.setCompoundDrawablePadding(dp(4));header.addView(export,new LinearLayout.LayoutParams(dp(100),dp(48)));

        studioPreviewFrame=new FrameLayout(this);studioPreviewFrame.setBackgroundColor(StudioEditorWorkspace.BG);
        player().attach(studioPreviewFrame);setStudioPlayerControls(false);
        studioStillPreview=new ImageView(this);studioStillPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        studioStillPreview.setBackgroundColor(Color.BLACK);studioStillPreview.setVisibility(View.GONE);
        studioPreviewFrame.addView(studioStillPreview,new FrameLayout.LayoutParams(-1,-1));
        studioCaptionPreview=new TextView(this);studioCaptionPreview.setGravity(Gravity.CENTER);studioCaptionPreview.setTextSize(17);
        studioCaptionPreview.setTypeface(Typeface.DEFAULT,Typeface.BOLD);studioCaptionPreview.setTextColor(Color.WHITE);
        studioCaptionPreview.setShadowLayer(dp(3),0,dp(2),Color.BLACK);studioCaptionPreview.setPadding(dp(8),dp(5),dp(8),dp(5));
        studioCaptionPreview.setVisibility(View.GONE);studioPreviewFrame.addView(studioCaptionPreview,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        studioPreviewErrorView=body(studioPreviewError);studioPreviewErrorView.setTag("studio-preview-error");
        studioPreviewErrorView.setMaxLines(2);studioPreviewErrorView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        studioPreviewErrorView.setVisibility(studioPreviewError.isEmpty()?View.GONE:View.VISIBLE);
        studioPreviewErrorView.setOnClickListener(view->{if(!studioPreviewError.isEmpty())new AlertDialog.Builder(this).setTitle("Edited preview").setMessage(studioPreviewError).setPositiveButton("OK",null).show();});
        FrameLayout.LayoutParams errorSize=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);errorSize.bottomMargin=dp(50);studioPreviewFrame.addView(studioPreviewErrorView,errorSize);
        studioPreviewRetry=studioButton("Retry preview",this::playStudioTimeline);studioPreviewRetry.setTag("studio-preview-retry");
        studioPreviewRetry.setVisibility(studioPreviewError.isEmpty()?View.GONE:View.VISIBLE);
        studioPreviewFrame.addView(studioPreviewRetry,new FrameLayout.LayoutParams(-2,dp(48),Gravity.BOTTOM|Gravity.END));

        LinearLayout transport=column(),editedTransport=column();studioEditedTransport=editedTransport;
        LinearLayout play=new LinearLayout(this);play.setGravity(Gravity.CENTER_VERTICAL);
        studioTimeLabel=body(StudioEditCursor.timecode(studioPlayheadMs)+" / "+StudioEditCursor.timecode(activeProject.outputDurationMs()));
        studioTimeLabel.setGravity(Gravity.CENTER);studioTimeLabel.setTextSize(12);studioTimeLabel.setSingleLine(true);
        studioTimeLabel.setContentDescription("Edited timeline time. Tap for exact time");studioTimeLabel.setOnClickListener(view->studioSeekDialog());
        play.addView(studioTimeLabel,new LinearLayout.LayoutParams(0,dp(48),1));
        play.addView(studioEditorIconButton("previous","|‹ Cut",()->seekStudioBoundary(-1)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        studioPlayToggle=studioEditorIconButton("play","▶ Play Edit",this::toggleStudioEditedPlayback);studioPlayToggle.setTag("editor-play");
        studioPlayToggle.setCompoundDrawablesWithIntrinsicBounds(StudioEditorWorkspace.icon(this,"play",true),null,null,null);
        play.addView(studioPlayToggle,new LinearLayout.LayoutParams(dp(48),dp(48)));
        play.addView(studioEditorIconButton("next","Cut ›|",()->seekStudioBoundary(1)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        play.addView(studioEditorIconButton("audio","Audio controls",this::studioWorkspaceAudioMenu),new LinearLayout.LayoutParams(dp(48),dp(48)));
        editedTransport.addView(play,new LinearLayout.LayoutParams(-1,-2));
        studioSeek=new SeekBar(this);studioSeek.setContentDescription("Edited timeline seek");studioSeek.setMax(1_000_000);
        studioSeek.setProgress((int)Math.round(studioSeek.getMax()*studioPlayheadMs/(double)length));
        studioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){stopStillSequenceOnly();}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){if(user)studioTimeLabel.setText(StudioEditCursor.timecode(Math.round(activeProject.outputDurationMs()*value/(double)bar.getMax()))+" / "+StudioEditCursor.timecode(activeProject.outputDurationMs()));}
            @Override public void onStopTrackingTouch(SeekBar bar){scrubStudioTo(Math.round(activeProject.outputDurationMs()*bar.getProgress()/(double)bar.getMax()));}
        });editedTransport.addView(studioSeek,new LinearLayout.LayoutParams(-1,dp(48)));
        transport.addView(editedTransport,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout savedTransport=new LinearLayout(this);savedTransport.setGravity(Gravity.CENTER_VERTICAL);
        studioPreviewTargetLabel=body("");studioPreviewTargetLabel.setMaxLines(2);
        savedTransport.addView(studioPreviewTargetLabel,new LinearLayout.LayoutParams(0,-2,1));
        savedTransport.addView(studioButton("Return to edit",this::playStudioTimeline),new LinearLayout.LayoutParams(-2,-2));
        savedTransport.setVisibility(View.GONE);studioSavedRenderTransport=savedTransport;
        transport.addView(savedTransport,new LinearLayout.LayoutParams(-1,-2));
        View editingTools=StudioEditorWorkspace.editingTools(this,new String[]{"Trim","Adjust","Motion","Audio","More"},new Runnable[]{this::studioWorkspaceTrim,this::studioWorkspaceAdjust,this::studioWorkspaceMotion,this::studioWorkspaceAudioMenu,this::studioPreviewMenu});
        studioWorkspace=new StudioEditorWorkspace(this,header,studioPreviewFrame,transport,editingTools,studioWorkspacePane,
                this::buildStudioWorkspacePane,pane->studioWorkspacePane=pane);
        int savedOrientation=prefs.getInt("studio_orientation_choice",0);
        int wanted=savedOrientation==1?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                savedOrientation==2?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if(getRequestedOrientation()!=wanted)setRequestedOrientation(wanted);
        setScreen(studioWorkspace,"editor");
        if(isStudioExternalPreviewMode()&&!studioPendingEditedSeek)applyStudioPreviewTarget();else previewSelectedStudioClip(false);
        updateStudioCaptionAt(studioPlayheadMs);
        scheduleEditorRefresh(activeProject.id,activeProject.updatedAt);
    }

    /** Manual orientation choice overrides rotation-lock only while the editor is open. */
    private void chooseEditorOrientation(){
        String[] options={"Auto-rotate editor","Portrait editing","Landscape editing"};
        int configured=prefs.getInt("studio_orientation_choice",0);
        new AlertDialog.Builder(this).setTitle("Responsive editor orientation")
                .setSingleChoiceItems(options,Math.max(0,Math.min(options.length-1,configured)),
                        (dialog,which)->{
                            prefs.edit().putInt("studio_orientation_choice",which).apply();
                            setRequestedOrientation(which==1?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                                    which==2?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                            dialog.dismiss();
                        })
                .setNeutralButton("Preview canvas", (d,w)->chooseStudioPreviewCanvas())
                .setNegativeButton("Cancel",null).show();
    }

    /** Canvas aspect is independent of the physical screen rotation. */
    private void chooseStudioPreviewCanvas(){
        final String[] values=StudioPreviewCanvas.ASPECTS;
        final String[] labels={"9:16 · vertical film","16:9 · horizontal film",
                "1:1 · square","4:5 · portrait artwork"};
        int selected=0;
        for(int i=0;i<values.length;i++)
            if(values[i].equals(studioPreviewAspect))selected=i;
        new AlertDialog.Builder(this).setTitle("Preview canvas · 720p")
                .setSingleChoiceItems(labels,selected,(dialog,which)->{
                    dialog.dismiss();
                    String chosen=StudioPreviewCanvas.coerce(values[which]);
                    if(chosen.equals(studioPreviewAspect))return;
                    studioPreviewAspect=chosen;
                    prefs.edit().putString("studio_preview_canvas_aspect",chosen).apply();
                    stopStudioDirectTouch();
                    // The old player revision key also includes aspect, but an
                    // explicit rebuild is needed to avoid the quick reuse path.
                    studioEditedProjectId="";
                    studioEditedRevision=-1;
                    studioPendingEditedSeek=true;
                    if("editor".equals(currentScreen)&&activeProject!=null
                            &&!activeProject.clips.isEmpty())
                        beginStudioEditPreview(false);
                })
                .setNegativeButton("Cancel",null).show();
    }

    @Override public void onConfigurationChanged(Configuration next) {
        super.onConfigurationChanged(next);
        // The existing media3 player and native timeline survive a rotation.
        // StudioEditorWorkspace receives the same configuration and reflows in place.
        if("editor".equals(currentScreen)&&studioWorkspace!=null)studioWorkspace.requestLayout();
    }

    private void setStudioPlayerControls(boolean visible){
        if(studioPreviewFrame==null)return;
        for(int i=0;i<studioPreviewFrame.getChildCount();i++)if(studioPreviewFrame.getChildAt(i) instanceof androidx.media3.ui.PlayerView){
            androidx.media3.ui.PlayerView view=(androidx.media3.ui.PlayerView)studioPreviewFrame.getChildAt(i);
            view.setShutterBackgroundColor(StudioEditorWorkspace.BG);view.setBackgroundColor(StudioEditorWorkspace.BG);
            view.setUseController(visible);if(visible)view.showController();else view.hideController();
        }
    }

    private void studioPreviewMenu(){
        if(isStudioExternalPreviewMode()){
            ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
            labels.add("Return to edit");actions.add(this::playStudioTimeline);
            labels.add("▶ Latest MP4");actions.add(this::playStudioLatest);labels.add("▤ All Renders");actions.add(this::showStudioRenderLibrary);
            if(studioPreviewTarget!=StudioPreviewTarget.SOURCE_STILL){labels.add("Preview controls");actions.add(()->setStudioPlayerControls(true));}
            labels.add("Activity");actions.add(this::showStudioJobDetails);labels.add("⌕ Find any tool");actions.add(this::findStudioTool);
            labels.add("Home");actions.add(this::showHome);labels.add("Tools");actions.add(this::showTools);labels.add("Agent");actions.add(this::showControl);
            new AlertDialog.Builder(this).setTitle(studioPreviewTarget==StudioPreviewTarget.SAVED_RENDER?"Saved render playback":"Source preview")
                    .setItems(labels.toArray(new String[0]),(dialog,which)->actions.get(which).run()).show();return;
        }
        String[] labels={"Time…","|‹ Cut","Cut ›|","−0.1s","+0.1s","▶ Latest MP4","▤ All Renders","Preview controls","Activity","⌕ Find any tool","Home","Tools","Agent","Canvas ratio…"};
        new AlertDialog.Builder(this).setTitle("Preview and renders").setItems(labels,(dialog,which)->{
            switch(which){
                case 0:studioSeekDialog();break;case 1:seekStudioBoundary(-1);break;case 2:seekStudioBoundary(1);break;
                case 3:scrubStudioTo(studioPlayheadMs-100);break;case 4:scrubStudioTo(studioPlayheadMs+100);break;
                case 5:playStudioLatest();break;case 6:showStudioRenderLibrary();break;case 7:setStudioPlayerControls(true);break;
                case 8:showStudioJobDetails();break;case 9:findStudioTool();break;case 10:showHome();break;case 11:showTools();break;case 12:showControl();break;
                case 13:chooseStudioPreviewCanvas();break;
            }
        }).show();
    }

    private Button studioEditorIconButton(String icon,String description,Runnable action){
        Button button=studioButton("",action);button.setTag("editor-icon");button.setContentDescription(description);button.setTooltipText(description);
        button.setPadding(dp(12),dp(12),dp(12),dp(12));button.setCompoundDrawablesWithIntrinsicBounds(StudioEditorWorkspace.icon(this,icon,false),null,null,null);return button;
    }

    private void toggleStudioEditedPlayback(){
        if(isStudioExternalPreviewMode())return;
        boolean ready=player().isEditedPreview()&&player().snapshotState().playWhenReady&&activeProject!=null&&player().currentPositionMs()<activeProject.outputDurationMs()-1;
        if(ready){timelinePreviewRunning=false;player().pause();}else playStudioTimeline();
        updateStudioPlayToggle();
    }

    private void updateStudioPlayToggle(){
        if(studioPlayToggle==null)return;
        boolean playing=livePlayer!=null&&livePlayer.isEditedPreview()&&livePlayer.snapshotState().playWhenReady;
        String description=playing?"Pause edited timeline":"▶ Play Edit";
        if(!description.contentEquals(studioPlayToggle.getContentDescription())){
            studioPlayToggle.setContentDescription(description);
            studioPlayToggle.setCompoundDrawablesWithIntrinsicBounds(StudioEditorWorkspace.icon(this,playing?"pause":"play",true),null,null,null);
        }
    }

    private boolean isStudioExternalPreviewMode(){return activeProject!=null&&activeProject.id.equals(studioPreviewTargetProjectId)&&studioPreviewTarget!=StudioPreviewTarget.EDITED;}

    private void setStudioPreviewTarget(StudioPreviewTarget target,String assetId,String name){
        studioPreviewTarget=target;studioPreviewTargetProjectId=activeProject==null?"":activeProject.id;
        studioPreviewTargetAssetId=assetId==null?"":assetId;studioPreviewTargetName=name==null?"":name;
    }

    /** Presentation follows the actual selected native source, not merely Player.isEditedPreview. */
    private void applyStudioPreviewTarget(){
        boolean edited=!isStudioExternalPreviewMode();
        if(studioEditedTransport!=null)studioEditedTransport.setVisibility(edited?View.VISIBLE:View.GONE);
        if(studioSavedRenderTransport!=null)studioSavedRenderTransport.setVisibility(edited?View.GONE:View.VISIBLE);
        if(studioPreviewTargetLabel!=null)studioPreviewTargetLabel.setText((studioPreviewTarget==StudioPreviewTarget.SAVED_RENDER?"Saved render":"Source preview")
                +(studioPreviewTargetName.isEmpty()?"":" · "+studioPreviewTargetName));
        boolean still=!edited&&studioPreviewTarget==StudioPreviewTarget.SOURCE_STILL;
        if(!edited&&studioCaptionPreview!=null){studioCaptionPreview.setText("");studioCaptionPreview.setVisibility(View.GONE);}
        if(studioStillPreview!=null){
            studioStillPreview.setVisibility(still?View.VISIBLE:View.GONE);
            if(still){
                ProjectStore.Asset source=activeProject.asset(studioPreviewTargetAssetId);
                if(source!=null&&source.mime!=null&&source.mime.startsWith("image/")){
                    if(studioThumbs==null)studioThumbs=new StudioThumbnailLoader(this);
                    if(!("preview:"+source.id).equals(studioStillPreview.getTag()))studioThumbs.bindPreview(studioStillPreview,source);
                }
            }else studioStillPreview.setTag(null); // Reject a late source-still thumbnail after returning to edit.
        }
        setStudioPlayerControls(!edited&&!still);updateStudioPlayToggle();
    }

    private void studioWorkspaceTrim(){StudioLayer layer=selectedStudioLayer();if(layer==null)trimDialog();else if(StudioLayer.IMAGE.equals(layer.type))editStudioImage(layer,layer.assetId);else if(StudioLayer.VIDEO.equals(layer.type))editStudioVideo(layer,layer.assetId);else if(StudioLayer.SHAPE.equals(layer.type))editStudioShape(layer);else if(StudioLayer.GROUP.equals(layer.type))openStudioMotionGroup(layer);else if(StudioLayer.AUDIO.equals(layer.type))editStudioAudio(layer,layer.assetId);else editStudioTitle(layer);}
    private void studioWorkspaceAdjust(){StudioLayer layer=selectedStudioLayer();if(layer==null)studioAdvancedAdjustDialog(false);else studioWorkspaceTrim();}
    private void studioWorkspaceMotion(){StudioLayer layer=selectedStudioLayer();if(layer==null)studioKeyframeManager();else if(StudioLayer.IMAGE.equals(layer.type))openStudioImageMotion(layer);else if(StudioLayer.VIDEO.equals(layer.type))openStudioVideoMotion(layer);else if(StudioLayer.SHAPE.equals(layer.type))openStudioShapeMotion(layer);else if(StudioLayer.GROUP.equals(layer.type))openStudioMotionGroup(layer);else if(StudioLayer.TITLE.equals(layer.type))openStudioTitleMotion(layer);else Toast.makeText(this,"Motion keys apply to a base clip, image/video overlay or title",Toast.LENGTH_LONG).show();}
    private void studioWorkspaceAudioMenu(){new AlertDialog.Builder(this).setTitle("Audio").setItems(new String[]{"Clip Volume & Fades","+ Music","Story audio"},(dialog,which)->{if(which==0)studioVolumeDialog();else if(which==1)chooseStudioAudio();else if(activeProject!=null)SketchStoryAudioDialog.show(this,activeProject.id,result->Toast.makeText(this,"Story audio saved in a separate audio project",Toast.LENGTH_LONG).show());}).show();}

    private View buildStudioWorkspacePane(String pane){
        studioTracks=null;studioSplitButton=null;LinearLayout box=column();box.setPadding(0,dp(4),0,dp(4));
        if("Media".equals(pane)){
            box.addView(title("Media Bin · "+activeProject.assets.size()+" owned assets",16));
            LinearLayout imports=new LinearLayout(this);imports.addView(studioButton("+ Import",this::pickMedia),new LinearLayout.LayoutParams(0,dp(48),1));
            imports.addView(studioButton("Import from project",()->ProjectAssetImportDialog.show(this,activeProject.id)),new LinearLayout.LayoutParams(0,dp(48),1));box.addView(imports);
            LinearLayout visuals=new LinearLayout(this);
            visuals.addView(studioButton("+ Image Overlay",this::chooseStudioImage),new LinearLayout.LayoutParams(0,dp(48),1));
            Button video=studioButton("+ Video Overlay",this::chooseStudioVideo);video.setEnabled(studioVideoCount()<2);
            visuals.addView(video,new LinearLayout.LayoutParams(0,dp(48),1));box.addView(visuals);
            box.addView(body("Up to 2 silent video overlays. Source files stay owned by this project."));
            box.addView(studioButton("+ Animated Shape / Perspective Grid",this::chooseStudioShape));
            box.addView(studioButton("+ Parent Motion Group · move layers together",this::createStudioMotionGroup));
            studioWorkspaceActions(box,new String[]{"Choose video file","Retry saved video copy"},new Runnable[]{this::pickStudioVideoFile,this::retrySelectedVideoImport});
            studioWorkspaceActions(box,new String[]{"+ Music","+ Caption","Story audio"},new Runnable[]{this::chooseStudioAudio,()->editStudioTitle(null),()->{if(activeProject!=null)SketchStoryAudioDialog.show(this,activeProject.id,result->Toast.makeText(this,"Story audio saved in a separate audio project",Toast.LENGTH_LONG).show());}});
            box.addView(studioButton("Kinetic Director · editable motion graphics", () ->
                    StudioKineticDirectorDialog.show(this, store, activeProject, created -> {
                        store.setActive(created.id);
                        activeProject = created;
                        selectedClip = created.clips.isEmpty() ? null : created.clips.get(0);
                        studioPlayheadMs = 0;
                        studioSelectedLayerId = null;
                        showEditor();
                    })));
            renderStudioMediaBin(box);
        }else if("Inspector".equals(pane))buildStudioInspector(box);
        else{
            LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(title("Timeline",17),new LinearLayout.LayoutParams(0,dp(48),1));
            Button undo=studioButton("↶ Undo",()->restoreStudioHistory(false));undo.setEnabled(editHistory.canUndo());heading.addView(undo,new LinearLayout.LayoutParams(dp(76),dp(48)));
            Button redo=studioButton("↷ Redo",()->restoreStudioHistory(true));redo.setEnabled(editHistory.canRedo());heading.addView(redo,new LinearLayout.LayoutParams(dp(76),dp(48)));box.addView(heading);
            renderStudioTrackLanes(box);renderStudioCompactClips(box);
            LinearLayout navigation=new LinearLayout(this);navigation.setGravity(Gravity.CENTER_VERTICAL);
            Button less=studioButton("−",()->{studioTrackZoom=Math.max(1,studioTrackZoom/2);studioWorkspace.refreshPane();});less.setEnabled(studioTrackZoom>1);navigation.addView(less,new LinearLayout.LayoutParams(dp(48),dp(48)));
            TextView zoom=body(studioTrackZoom+"×");zoom.setGravity(Gravity.CENTER);navigation.addView(zoom,new LinearLayout.LayoutParams(dp(34),dp(48)));
            Button more=studioButton("+",()->{studioTrackZoom=Math.min(16,studioTrackZoom*2);studioWorkspace.refreshPane();});more.setEnabled(studioTrackZoom<16);navigation.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
            navigation.addView(studioButton(studioTrackSnap?"Snap: On":"Snap: Off",()->{studioTrackSnap=!studioTrackSnap;studioWorkspace.refreshPane();}),new LinearLayout.LayoutParams(0,dp(48),1));
            navigation.addView(studioButton("Targets",this::chooseStudioInspectorTarget),new LinearLayout.LayoutParams(dp(90),dp(48)));
            Button tools=studioButton("⋮",this::findStudioTool);tools.setContentDescription("Find any existing editing tool");navigation.addView(tools,new LinearLayout.LayoutParams(dp(48),dp(48)));box.addView(navigation);
            // Retain the existing authoring/automation entry; panes do not re-admit a failed preview.
            box.addView(studioButton("Show media",()->studioWorkspace.showPane("Media")));
        }
        ScrollView scroll=baseScroll();scroll.addView(box);return scroll;
    }

    /** Transform the selected owned layer directly on the real edited video surface. */
    private void openStudioDirectTouch(StudioLayer layer){
        if(activeProject==null||studioPreviewFrame==null||layer==null)return;
        if(isStudioExternalPreviewMode()){
            Toast.makeText(this,"Return to the edited preview before moving a layer",Toast.LENGTH_LONG).show();return;
        }
        stopStudioDirectTouch();
        final String owner=activeProject.id;
        final long expected=activeProject.updatedAt;
        final long generation=studioSurfaceGeneration;
        final StudioLayer source=layer.copy();
        try{
            studioTouchTransform=new StudioTouchTransformOverlay(this,source,
                    StudioPreviewCanvas.ratio(studioPreviewAspect),
                    new StudioTouchTransformOverlay.Listener(){
                @Override public void onCommit(StudioLayer candidate){
                    stopStudioDirectTouch();
                    if(activeProject==null||!owner.equals(activeProject.id)
                            ||activeProject.updatedAt!=expected
                            ||studioSurfaceGeneration!=generation){
                        Toast.makeText(MainActivity.this,"The editor changed; touch edit was not saved",
                                Toast.LENGTH_LONG).show();return;
                    }
                    try{saveStudioLayer(candidate,expected);}
                    catch(Exception failure){
                        Toast.makeText(MainActivity.this,"Touch edit not saved: "+failure.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
                @Override public void onCancel(){stopStudioDirectTouch();}
            });
            studioPreviewFrame.addView(studioTouchTransform,new FrameLayout.LayoutParams(-1,-1));
            studioTouchExit=studioButton("Done",this::stopStudioDirectTouch);
            studioTouchExit.setContentDescription("Close direct manipulation handles");
            FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(dp(82),dp(48),Gravity.TOP|Gravity.END);
            position.setMargins(dp(6),dp(30),dp(6),0);
            studioPreviewFrame.addView(studioTouchExit,position);
            Toast.makeText(this,"Drag the layer, or pinch with two fingers to resize and rotate. Release to save.",
                    Toast.LENGTH_LONG).show();
        }catch(Exception error){
            stopStudioDirectTouch();
            Toast.makeText(this,"Direct transform unavailable: "+error.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void stopStudioDirectTouch(){
        if(studioTouchTransform!=null){
            if(studioTouchTransform.getParent() instanceof ViewGroup)
                ((ViewGroup)studioTouchTransform.getParent()).removeView(studioTouchTransform);
            studioTouchTransform=null;
        }
        if(studioTouchExit!=null){
            if(studioTouchExit.getParent() instanceof ViewGroup)
                ((ViewGroup)studioTouchExit.getParent()).removeView(studioTouchExit);
            studioTouchExit=null;
        }
    }

    private void openStudioMaskEditor(StudioLayer target) {
        if(activeProject==null || target==null)return;
        if(!StudioLayer.IMAGE.equals(target.type)&&!StudioLayer.SHAPE.equals(target.type))return;
        final String owner=activeProject.id;
        final long revision=activeProject.updatedAt;
        String[] choices={"No mask","Ellipse","Rectangle","Rounded rectangle","Linear wipe"};
        new AlertDialog.Builder(this).setTitle("After Effects-style alpha mask")
                .setItems(choices,(dialog,which)->{
                    if(activeProject==null || !owner.equals(activeProject.id)
                            ||revision!=activeProject.updatedAt){
                        Toast.makeText(this,"Project changed; reopen mask editor",Toast.LENGTH_LONG).show();return;
                    }
                    if(which==0){
                        StudioLayer edit=target.copy();edit.mask=null;
                        saveStudioLayer(edit,revision);return;
                    }
                    final String kind=new String[]{"","ellipse","rectangle","round_rect","linear_wipe"}[which];
                    LinearLayout controls=column();controls.setPadding(dp(16),dp(10),dp(16),dp(10));
                    controls.addView(body("Feather (0–0.4, normalized to layer pixels)"));
                    EditText feather=numberInput(.06f);controls.addView(feather);
                    controls.addView(body("Wipe position 0–1 (used by linear wipe)"));
                    EditText position=numberInput(.5f);controls.addView(position);
                    android.widget.CheckBox inverted=new android.widget.CheckBox(this);
                    inverted.setText("Invert alpha mask");controls.addView(inverted);
                    if(target.mask!=null){
                        feather.setText(Double.toString(target.mask.optDouble("feather",.06)));
                        position.setText(Double.toString(target.mask.optDouble("position",.5)));
                        inverted.setChecked(target.mask.optBoolean("invert",false));
                    }
                    AlertDialog edit=new AlertDialog.Builder(this).setTitle("Mask · "+choices[which])
                            .setView(controls).setPositiveButton("Apply",null)
                            .setNegativeButton("Cancel",null).create();
                    edit.setOnShowListener(ignored->
                        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
                            try {
                                if(activeProject==null||!owner.equals(activeProject.id)
                                        ||revision!=activeProject.updatedAt)
                                    throw new IllegalStateException("Project changed. Reopen the mask inspector");
                                StudioLayer draft=target.copy();
                                draft.mask=StudioMaskRasterizer.validate(new JSONObject()
                                        .put("kind",kind)
                                        .put("feather",Double.parseDouble(feather.getText().toString()))
                                        .put("position",Double.parseDouble(position.getText().toString()))
                                        .put("invert",inverted.isChecked()));
                                if(saveStudioLayer(draft,revision))edit.dismiss();
                            }catch(Exception error){
                                Toast.makeText(this,"Mask not saved: "+error.getMessage(),Toast.LENGTH_LONG).show();
                            }
                        }));
                    edit.show();
                }).show();
    }

    /** Create a linked, keyframed group for selected visual layers in one edit. */
    private void createStudioMotionGroup(){
        if(activeProject==null||activeProject.clips.isEmpty()||activeProject.outputDurationMs()<100){
            onStudioNoTarget();return;
        }
        final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        ArrayList<StudioLayer> eligible=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(StudioLayer item:activeProject.studioLayers){
            if(!StudioLayer.TITLE.equals(item.type)&&!StudioLayer.IMAGE.equals(item.type)
                    &&!StudioLayer.SHAPE.equals(item.type))continue;
            if(item.parentId!=null&&!item.parentId.isEmpty())continue;
            eligible.add(item);
            labels.add((StudioLayer.TITLE.equals(item.type)?"Title":StudioLayer.IMAGE.equals(item.type)
                    ?"Image":"Shape")+" · "+
                    (StudioLayer.TITLE.equals(item.type)?item.text:StudioLayer.SHAPE.equals(item.type)
                            ?item.shapeKind:item.assetId));
        }
        if(eligible.isEmpty()){
            Toast.makeText(this,"Add an image, title or vector shape layer before grouping",
                    Toast.LENGTH_LONG).show();return;
        }
        final boolean[] checked=new boolean[eligible.size()];
        for(int i=0;i<eligible.size();i++)
            checked[i]=eligible.get(i).id.equals(studioSelectedLayerId);
        new AlertDialog.Builder(this).setTitle("Link visual layers · maximum 8")
          .setMultiChoiceItems(labels.toArray(new String[0]),checked,
                  (dialog,index,chosen)->checked[index]=chosen)
          .setPositiveButton("Choose group motion",(dialog,ignored)->{
              ArrayList<StudioLayer> chosen=new ArrayList<>();
              for(int i=0;i<eligible.size();i++)if(checked[i])chosen.add(eligible.get(i));
              if(chosen.isEmpty()||chosen.size()>8){
                  Toast.makeText(this,"Select 1..8 visual layers",Toast.LENGTH_LONG).show();return;
              }
              long first=Long.MAX_VALUE,last=Long.MIN_VALUE;
              for(StudioLayer item:chosen){
                  first=Math.min(first,item.startMs);
                  last=Math.max(last,item.startMs+item.durationMs);
              }
              final long span=last-first;
              final String[] choices={"Drift together","Pulse together",
                      "Rotate together","Slide together","Static linked group"};
              final String[] styles={"drift","pulse","spin","slide","hold"};
              new AlertDialog.Builder(this).setTitle("Animate linked overlays")
                .setItems(choices,(motionDialog,which)->{
                    if(activeProject==null||!owner.equals(activeProject.id)
                            ||revision!=activeProject.updatedAt){
                        Toast.makeText(this,"Project changed; reselect group layers",
                                Toast.LENGTH_LONG).show();return;
                    }
                    try{
                        JSONArray ids=new JSONArray();
                        for(StudioLayer item:chosen)ids.put(item.id);
                        JSONObject request=new JSONObject()
                                .put("projectId",owner).put("expectedUpdatedAt",revision)
                                .put("operation","create")
                                .put("operationId",UUID.randomUUID().toString())
                                .put("layerIds",ids)
                                .put("motionKeyframes",StudioShapePresets.motion(styles[which],span));
                        saveStudioMotionGroup(request,true);
                    }catch(Exception error){
                        Toast.makeText(this,"Group not created: "+error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
          })
          .setNegativeButton("Cancel",null).show();
    }

    private void openStudioMotionGroup(StudioLayer group){
        if(activeProject==null||group==null||!StudioLayer.GROUP.equals(group.type))return;
        final String owner=activeProject.id,groupId=group.id;
        final long revision=activeProject.updatedAt;
        String[] labels={"Edit precise keyframes and Bézier curves…",
                "Preserve existing motion","Drift together","Pulse together",
                "Rotate together","Slide together","Clear group motion"};
        String[] styles={"hold","drift","pulse","spin","slide","hold"};
        new AlertDialog.Builder(this).setTitle("Linked Motion · "+groupId.substring(0,Math.min(8,groupId.length())))
          .setItems(labels,(dialog,which)->{
              if(which==0){
                  try {
                      StudioGroupMotionDialog.show(this,group,
                              Math.max(0,Math.min(group.durationMs,studioPlayheadMs-group.startMs)),
                              keys->{
                                  if(activeProject==null||!owner.equals(activeProject.id)
                                          ||revision!=activeProject.updatedAt)return false;
                                  try{
                                      JSONObject req=new JSONObject().put("projectId",owner)
                                              .put("expectedUpdatedAt",revision).put("operation","animate")
                                              .put("groupId",groupId).put("motionKeyframes",keys);
                                      saveStudioMotionGroup(req,true);
                                      return true;
                                  }catch(Exception failure){
                                      Toast.makeText(this,"Group not saved: "+failure.getMessage(),
                                              Toast.LENGTH_LONG).show();return false;
                                  }
                              });
                  }catch(Exception failure){
                      Toast.makeText(this,"Exact keyframe editor unavailable: "+failure.getMessage(),
                              Toast.LENGTH_LONG).show();
                  }
                  return;
              }
              if(which==1)return;
              if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt){
                  Toast.makeText(this,"Project changed. Reopen group motion",Toast.LENGTH_LONG).show();return;
              }
              try{
                  JSONObject request=new JSONObject().put("projectId",owner)
                          .put("expectedUpdatedAt",revision).put("operation","animate")
                          .put("groupId",groupId)
                          .put("motionKeyframes",which==6?new JSONArray():
                                  StudioShapePresets.motion(styles[which-1],group.durationMs));
                  saveStudioMotionGroup(request,true);
              }catch(Exception error){
                  Toast.makeText(this,"Group motion not saved: "+error.getMessage(),Toast.LENGTH_LONG).show();
              }
          }).show();
    }

    private void saveStudioMotionGroup(JSONObject request,boolean inspectGroup)throws Exception{
        if(activeProject==null)throw new IllegalStateException("No selected project");
        ProjectStore.Project before=ProjectStore.Project.fromJson(activeProject.toJson());
        JSONObject receipt=StudioMotionGroupOps.apply(store,request);
        ProjectStore.Project saved=store.get(receipt.getString("projectId"));
        if(saved==null)throw new IllegalStateException("Saved group project unavailable");
        editHistory.checkpoint(before);
        activeProject=saved;
        selectedClip=saved.clips.isEmpty()?null:saved.clips.get(0);
        studioSelectedLayerId=inspectGroup?receipt.optString("groupId",""):null;
        syncProtocolState();
        showEditor();
        if(inspectGroup&&studioWorkspace!=null)studioWorkspace.showPane("Inspector");
    }

    private void chooseStudioGroupChildren(StudioLayer group){
        if(activeProject==null||group==null||!StudioLayer.GROUP.equals(group.type))return;
        final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        ArrayList<StudioLayer> children=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(StudioLayer item:activeProject.studioLayers){
            if(!StudioLayer.TITLE.equals(item.type)&&!StudioLayer.IMAGE.equals(item.type)
                    &&!StudioLayer.SHAPE.equals(item.type))continue;
            if(item.startMs<group.startMs||item.durationMs>group.durationMs-(item.startMs-group.startMs))continue;
            if(!item.parentId.isEmpty()&&!group.id.equals(item.parentId))continue;
            children.add(item);
            String kind=StudioLayer.TITLE.equals(item.type)?"Text":StudioLayer.IMAGE.equals(item.type)?"Image":"Shape";
            labels.add((group.id.equals(item.parentId)?"✓ ":"○ ")+kind+" · "+
                    (StudioLayer.SHAPE.equals(item.type)?item.shapeKind:
                        StudioLayer.TITLE.equals(item.type)?item.text:item.assetId));
        }
        if(children.isEmpty()){Toast.makeText(this,"Add a text, image or shape layer within this group first",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Link / unlink native overlays")
          .setItems(labels.toArray(new String[0]),(dialog,index)->{
              if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt){
                  Toast.makeText(this,"Project changed; choose group again",Toast.LENGTH_LONG).show();return;
              }
              ArrayList<StudioLayer> changed=new ArrayList<>();
              String target=children.get(index).id;
              for(StudioLayer layer:activeProject.studioLayers){
                  StudioLayer clone=layer.copy();
                  if(target.equals(clone.id))
                      clone.parentId=group.id.equals(clone.parentId)?"":group.id;
                  changed.add(clone);
              }
              applyStudioLayerList(changed,revision);
          }).setNegativeButton("Cancel",null).show();
    }

    private void removeStudioMotionGroup(StudioLayer group){
        if(activeProject==null||group==null)return;
        final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        new AlertDialog.Builder(this).setTitle("Unlink this motion group?")
          .setMessage("Linked layers and all imported files remain saved. Parent movement is removed, so their displayed positions may change. No clips are deleted.")
          .setPositiveButton("Unlink group",(dialog,which)->{
              if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt)return;
              try{
                  JSONObject request=new JSONObject()
                          .put("projectId",owner).put("expectedUpdatedAt",revision)
                          .put("operation","dissolve").put("groupId",group.id);
                  saveStudioMotionGroup(request,false);
              }catch(Exception error){
                  Toast.makeText(this,"Could not unlink group: "+error.getMessage(),Toast.LENGTH_LONG).show();
              }
          }).setNegativeButton("Cancel",null).show();
    }

    private void studioWorkspaceActions(LinearLayout box,String[] names,Runnable[] actions){
        for(int first=0;first<names.length;first+=2){LinearLayout row=new LinearLayout(this);
            for(int i=first;i<Math.min(names.length,first+2);i++)row.addView(studioButton(names[i],actions[i]),new LinearLayout.LayoutParams(0,dp(48),1));box.addView(row);}
    }

    private StudioLayer selectedStudioLayer(){
        if(activeProject!=null&&studioSelectedLayerId!=null)for(StudioLayer layer:activeProject.studioLayers)if(studioSelectedLayerId.equals(layer.id))return layer;
        studioSelectedLayerId=null;return null;
    }

    private void selectStudioLayer(String id){
        if(activeProject==null)return;boolean found=false;for(StudioLayer layer:activeProject.studioLayers)if(layer.id.equals(id)){found=true;break;}
        if(!found)return;studioInspectorProjectId=activeProject.id;studioSelectedLayerId=id;
        if(studioWorkspace!=null){if("Inspector".equals(studioWorkspacePane))studioWorkspace.refreshPane();else studioWorkspace.showPane("Inspector");}
    }

    private void chooseStudioInspectorTarget(){
        if(activeProject==null)return;final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
        if(selectedClip!=null){ids.add("");labels.add("Base clip · "+(activeProject.clips.indexOf(selectedClip)+1));}
        for(StudioLayer layer:activeProject.studioLayers){ids.add(layer.id);ProjectStore.Asset asset=activeProject.asset(layer.assetId);labels.add((StudioLayer.IMAGE.equals(layer.type)?"Image overlay":StudioLayer.VIDEO.equals(layer.type)?"Video overlay":StudioLayer.SHAPE.equals(layer.type)?"Animated shape":StudioLayer.GROUP.equals(layer.type)?"Motion group":StudioLayer.AUDIO.equals(layer.type)?"Audio":"Title")+" · "+(asset==null?(StudioLayer.SHAPE.equals(layer.type)?layer.shapeKind:StudioLayer.GROUP.equals(layer.type)?"Parent · "+layer.id.substring(0,Math.min(8,layer.id.length())):layer.text):asset.name)+(StudioLayer.VIDEO.equals(layer.type)?" · "+StudioEditCursor.timecode(layer.startMs):""));}
        if(ids.isEmpty()){onStudioNoTarget();return;}
        new AlertDialog.Builder(this).setTitle("Inspect target").setItems(labels.toArray(new String[0]),(dialog,index)->{
            if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt){Toast.makeText(this,"Project changed. Choose its current target again.",Toast.LENGTH_LONG).show();return;}
            studioSelectedLayerId=ids.get(index).isEmpty()?null:ids.get(index);if(studioWorkspace!=null){if("Inspector".equals(studioWorkspacePane))studioWorkspace.refreshPane();else studioWorkspace.showPane("Inspector");}
        }).show();
    }

    private void onStudioNoTarget(){Toast.makeText(this,"Import a timeline clip or overlay first",Toast.LENGTH_LONG).show();}

    private void buildStudioInspector(LinearLayout box){
        StudioLayer layer=selectedStudioLayer();
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        String kind=layer==null?"Base clip":StudioLayer.IMAGE.equals(layer.type)?"Image overlay":StudioLayer.VIDEO.equals(layer.type)?"Video overlay":StudioLayer.SHAPE.equals(layer.type)?"Animated shape":StudioLayer.GROUP.equals(layer.type)?"Motion group":StudioLayer.AUDIO.equals(layer.type)?"Audio":"Title";
        heading.addView(title(kind,17),new LinearLayout.LayoutParams(0,dp(48),1));heading.addView(studioButton("Choose target",this::chooseStudioInspectorTarget),new LinearLayout.LayoutParams(-2,dp(48)));box.addView(heading);
        if(layer!=null){
            ProjectStore.Asset asset=activeProject.asset(layer.assetId);TextView source=body((asset==null?(StudioLayer.SHAPE.equals(layer.type)?layer.shapeKind:StudioLayer.GROUP.equals(layer.type)?"Linked motion · "+layer.id.substring(0,Math.min(8,layer.id.length())):layer.text):asset.name)+" · "+StudioEditCursor.timecode(layer.startMs)+" – "+StudioEditCursor.timecode(layer.startMs+layer.durationMs));source.setMaxLines(2);box.addView(source);
            if(StudioLayer.GROUP.equals(layer.type)){
                box.addView(studioButton("Animate grouped layers",()->openStudioMotionGroup(layer)));
                box.addView(studioButton("Link or unlink overlay layers",()->chooseStudioGroupChildren(layer)));
                box.addView(studioButton("Delete group (unlink children)",()->removeStudioMotionGroup(layer)));
                box.addView(body("One-level motion parent. All linked text, image and vector layers receive its transform during preview and export."));
            }else if(StudioLayer.IMAGE.equals(layer.type)){
                box.addView(studioButton("Mask / feather",()->openStudioMaskEditor(layer)));
                box.addView(studioButton("Touch-transform directly on preview",()->openStudioDirectTouch(layer)));
                box.addView(studioButton("Animate overlay",()->openStudioImageMotion(layer)));
                box.addView(studioButton("Placement / timing",()->editStudioImage(layer,layer.assetId)));
                box.addView(studioButton("Delete image overlay",()->removeStudioLayer(layer)));
                box.addView(body("Image motion keeps the source pixels. Source Crop belongs to a base clip; this overlay has no source-crop control."));
            }else if(StudioLayer.VIDEO.equals(layer.type)){
                box.addView(studioButton("Touch-transform directly on preview",()->openStudioDirectTouch(layer)));
                box.addView(studioButton("Animate video",()->openStudioVideoMotion(layer)));
                box.addView(studioButton("Placement / timing",()->editStudioVideo(layer,layer.assetId)));
                box.addView(studioButton("Delete video overlay",()->removeStudioLayer(layer)));
                box.addView(body("Silent independent video source. Source in trims its own footage; it does not move the base timeline or add source audio."));
            }else if(StudioLayer.SHAPE.equals(layer.type)){
                box.addView(studioButton("Mask / feather",()->openStudioMaskEditor(layer)));
                box.addView(studioButton("Touch-transform directly on preview",()->openStudioDirectTouch(layer)));
                box.addView(studioButton("Animate vector shape",()->openStudioShapeMotion(layer)));
                box.addView(studioButton("Placement / timing",()->editStudioShape(layer)));
                box.addView(studioButton("Change geometry",()->chooseStudioShapeGeometry(layer)));
                box.addView(studioButton("Delete shape",()->removeStudioLayer(layer)));
                box.addView(body("Native vector geometry is cached once and transformed with true timed keyframes on the edited composition. No source image is synthesized."));
            }else if(StudioLayer.AUDIO.equals(layer.type)){
                box.addView(studioButton("Analyze envelope",()->openStudioAudioEnvelope(layer)));
                box.addView(studioButton("Edit audio",()->editStudioAudio(layer,layer.assetId)));
                box.addView(studioButton("Delete audio",()->removeStudioLayer(layer)));
            }
            else{box.addView(studioButton("Animate title",()->openStudioTitleMotion(layer)));box.addView(studioButton("Edit caption",()->editStudioTitle(layer)));box.addView(studioButton("Delete caption",()->removeStudioLayer(layer)));}
        }else if(selectedClip!=null){
            ProjectStore.Asset asset=activeProject.asset(selectedClip.assetId);box.addView(body((asset==null?"Missing media":asset.name)+" · "+StudioEditCursor.timecode(selectedClip.outputDurationMs())));
            String[] tools={"Crop","Keyframes","Trim","Slow Motion","Volume","Exact Transform","Colour","Adjust","Blur","Opacity","Green Screen","Mask","Glow","Effects","Motion","Shake","Duplicate","Delete Clip","Move Left","Move Right"};
            Runnable[] callbacks=new Runnable[tools.length];for(int i=0;i<tools.length;i++){String tool=tools[i];callbacks[i]=()->{if("Trim".equals(tool))trimDialog();else if("Duplicate".equals(tool))applyTool("Duplicate");else performStudioTool(tool);};}
            studioWorkspaceActions(box,tools,callbacks);
            studioSplitButton=studioButton("Split Here",this::splitClip);studioSplitButton.setEnabled(canSplitStudioAtCursor());box.addView(studioSplitButton);
            studioWorkspaceActions(box,new String[]{"Set In Here","Set Out Here"},new Runnable[]{()->trimStudioAtCursor(true),()->trimStudioAtCursor(false)});
        }else{box.addView(body("Select a timeline clip or image/audio/title layer. Media import and existing tools remain available."));}
        box.addView(studioButton("⌕ Find any tool",this::findStudioTool));
    }

    private void openStudioImageMotion(StudioLayer image){
        if(activeProject==null)return;final String owner=activeProject.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        try{if(!canApplyStudioImage(owner,revision,generation))return;showStudioImageMotion(image.copy(),owner,revision,generation,()->{});}
        catch(Exception error){Toast.makeText(this,"Cannot animate image overlay: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void openStudioTitleMotion(StudioLayer title){
        if(activeProject==null)return;final String owner=activeProject.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        try{
            if(!canApplyStudioTitleMotion(owner,revision,generation))return;
            if(studioImageMotionDialog!=null)studioImageMotionDialog.dismiss();
            AlertDialog motionDialog=StudioTitleMotionDialog.show(this,title.copy(),
                    Math.max(0,Math.min(title.durationMs,studioPlayheadMs-title.startMs)),candidate->{
                        if(!canApplyStudioTitleMotion(owner,revision,generation))return false;
                        return saveStudioLayer(candidate,revision);
                    },dismissed->{if(studioImageMotionDialog==dismissed)studioImageMotionDialog=null;});
            studioImageMotionDialog=motionDialog;
        }catch(Exception error){Toast.makeText(this,"Cannot animate title: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private static final String[] SHAPE_TYPES={"grid","ring","ellipse","rect","diamond","line"};
    private static final String[] SHAPE_MOTION={"drift","pulse","spin","slide","hold"};

    private void chooseStudioShape(){
        if(activeProject==null||activeProject.clips.isEmpty()||activeProject.outputDurationMs()<100){
            onStudioNoTarget();return;
        }
        final String owner=activeProject.id;
        final long revision=activeProject.updatedAt;
        new AlertDialog.Builder(this).setTitle("Add original vector motion").setItems(
                new String[]{"Perspective grid","Neon ring","Soft ellipse","Rectangle","Diamond","Diagonal line"},
                (dialog,index)->{
                    new AlertDialog.Builder(this).setTitle("Animation style")
                            .setItems(new String[]{"Ambient drift","Scale pulse","Full rotation","Slide across","Static hold"},
                                    (selection,which)->{
                                        if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt){
                                            Toast.makeText(this,"Project changed; reopen Add Shape",Toast.LENGTH_LONG).show();return;
                                        }
                                        try{
                                            long film=activeProject.outputDurationMs();
                                            long start=Math.min(studioPlayheadMs,Math.max(0,film-100));
                                            long duration=Math.min(3600,film-start);
                                            StudioLayer shape=StudioShapePresets.create(SHAPE_TYPES[index],
                                                    SHAPE_MOTION[which],start,duration);
                                            ArrayList<StudioLayer> updated=new ArrayList<>(activeProject.studioLayers);
                                            updated.add(shape);
                                            if(applyStudioLayerList(updated,revision)){
                                                studioSelectedLayerId=shape.id;
                                                if(studioWorkspace!=null)studioWorkspace.showPane("Inspector");
                                            }
                                        }catch(Exception failure){
                                            Toast.makeText(this,"Shape not added: "+failure.getMessage(),Toast.LENGTH_LONG).show();
                                        }
                                    }).show();
                }).show();
    }

    private void openStudioShapeMotion(StudioLayer shape){
        if(activeProject==null)return;
        final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        new AlertDialog.Builder(this).setTitle("Animate original "+shape.shapeKind)
                .setItems(new String[]{"Ambient drift","Scale pulse","Full rotation","Slide across","Static hold"},
                        (dialog,which)->{
                            if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt){
                                Toast.makeText(this,"Project changed; reopen Shape Motion",Toast.LENGTH_LONG).show();return;
                            }
                            try{
                                StudioLayer draft=shape.copy();
                                draft.motionKeyframes=StudioShapePresets.motion(SHAPE_MOTION[which],draft.durationMs);
                                saveStudioLayer(draft,revision);
                            }catch(Exception failure){
                                Toast.makeText(this,"Shape animation not saved: "+failure.getMessage(),Toast.LENGTH_LONG).show();
                            }
                        }).show();
    }

    private void chooseStudioShapeGeometry(StudioLayer shape){
        if(activeProject==null)return;
        final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        new AlertDialog.Builder(this).setTitle("Original shape geometry")
                .setItems(new String[]{"Perspective grid","Neon ring","Ellipse","Rectangle","Diamond","Line"},
                        (dialog,which)->{
                            if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt)return;
                            try{
                                StudioLayer draft=shape.copy();
                                draft.shapeKind=SHAPE_TYPES[which];
                                saveStudioLayer(draft,revision);
                            }catch(Exception failure){
                                Toast.makeText(this,"Shape not changed: "+failure.getMessage(),Toast.LENGTH_LONG).show();
                            }
                        }).show();
    }

    private void editStudioShape(StudioLayer shape){
        if(activeProject==null)return;
        final String owner=activeProject.id;final long revision=activeProject.updatedAt;
        LinearLayout content=column();content.setPadding(dp(16),dp(8),dp(16),dp(8));
        content.addView(body("Shape: "+shape.shapeKind+" · source-free geometry. Motion keys stay unchanged."));
        String[] names={"startMs","durationMs","x","y","width","height","rotation","opacity","strokeWidth"};
        double[] values={shape.startMs,shape.durationMs,shape.x,shape.y,shape.width,
                shape.height,shape.rotation,shape.opacity,shape.strokeWidth};
        EditText[] fields=new EditText[names.length];
        for(int i=0;i<names.length;i++){
            content.addView(body(names[i]));fields[i]=numberInput((float)values[i]);
            fields[i].setText(i<2?Long.toString((long)values[i]):Double.toString(values[i]));
            content.addView(fields[i]);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog edit=new AlertDialog.Builder(this).setTitle("Shape placement / timing")
                .setView(scroll).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        edit.setOnShowListener(ignored->edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            if(activeProject==null||!owner.equals(activeProject.id)||revision!=activeProject.updatedAt){
                Toast.makeText(this,"Project changed; reopen the Shape inspector",Toast.LENGTH_LONG).show();return;
            }
            try{
                JSONObject json=shape.toJson();
                for(int i=0;i<names.length;i++)json.put(names[i],
                        i<2?Long.parseLong(fields[i].getText().toString()):
                                Double.parseDouble(fields[i].getText().toString()));
                if(saveStudioLayer(StudioLayer.fromJson(json),revision))edit.dismiss();
            }catch(Exception failure){
                Toast.makeText(this,"Shape placement not saved: "+failure.getMessage(),Toast.LENGTH_LONG).show();
            }
        }));
        edit.show();
    }

    private int studioVideoCount(){int count=0;if(activeProject!=null)for(StudioLayer layer:activeProject.studioLayers)if(StudioLayer.VIDEO.equals(layer.type))count++;return count;}

    private boolean eligibleStudioVideo(ProjectStore.Asset asset){
        if(asset==null||asset.mime==null||!asset.mime.startsWith("video/")||!asset.seekable||asset.durationMs<100||asset.durationMs>Long.MAX_VALUE/1000L||asset.uri==null)return false;
        Uri uri=Uri.parse(asset.uri);return "file".equals(uri.getScheme())&&uri.getPath()!=null&&uri.getQuery()==null&&uri.getFragment()==null&&(uri.getAuthority()==null||uri.getAuthority().isEmpty());
    }

    private void chooseStudioVideo(){
        if(activeProject==null||activeProject.clips.isEmpty()||activeProject.outputDurationMs()<100){onStudioNoTarget();return;}
        if(studioVideoCount()>=2){Toast.makeText(this,"Up to 2 silent video overlays are supported",Toast.LENGTH_LONG).show();return;}
        final String owner=activeProject.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        ArrayList<ProjectStore.Asset> videos=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(ProjectStore.Asset asset:activeProject.assets)if(eligibleStudioVideo(asset)){videos.add(asset);labels.add(asset.name);}
        if(videos.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Add video overlay").setMessage("Choose one video in Files to make an independent owned copy without adding a base clip. Or copy an already owned video from another project. The selected document stays unchanged.")
                    .setPositiveButton("Import from project",(dialog,which)->ProjectAssetImportDialog.show(this,owner))
                    .setNeutralButton("Choose video file",(dialog,which)->{if(canApplyStudioVideo(owner,revision,generation))pickStudioVideoFile();}).setNegativeButton("Cancel",null).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Choose an imported overlay video").setItems(labels.toArray(new String[0]),(dialog,index)->{
            if(canApplyStudioVideo(owner,revision,generation))editStudioVideo(null,videos.get(index).id);
        }).setNegativeButton("Cancel",null).show();
    }

    private void editStudioVideo(StudioLayer existing,String assetId){
        if(activeProject==null)return;final String owner=activeProject.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        try{
            if(!canApplyStudioVideo(owner,revision,generation))return;
            ProjectStore.Asset asset=activeProject.asset(assetId);if(!eligibleStudioVideo(asset))throw new IllegalArgumentException("Choose an owned, seekable video with a known duration");
            if(existing==null&&studioVideoCount()>=2)throw new IllegalArgumentException("Up to 2 silent video overlays are supported");
            closeStudioVideoEditor();if(studioImageMotionDialog!=null)studioImageMotionDialog.dismiss();
            StudioVideoOverlayDialog editor=StudioVideoOverlayDialog.show(this,activeProject,asset,existing,studioPlayheadMs,
                    ()->canApplyStudioVideo(owner,revision,generation),candidate->saveStudioVideo(candidate,owner,revision,generation),
                    closed->{if(studioVideoEditor==closed)studioVideoEditor=null;if(studioImageMotionDialog==closed.dialog())studioImageMotionDialog=null;});
            studioVideoEditor=editor;studioImageMotionDialog=editor.dialog();
        }catch(Exception error){Toast.makeText(this,"Cannot edit video overlay: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void openStudioVideoMotion(StudioLayer layer){
        if(activeProject==null)return;final String owner=activeProject.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        try{
            if(!canApplyStudioVideo(owner,revision,generation))return;
            ProjectStore.Asset asset=activeProject.asset(layer.assetId);if(!eligibleStudioVideo(asset))throw new IllegalArgumentException("The video source is unavailable");
            closeStudioVideoEditor();if(studioImageMotionDialog!=null)studioImageMotionDialog.dismiss();
            StudioVideoMotionDialog editor=StudioVideoMotionDialog.show(this,activeProject,asset,layer,
                    Math.max(0,Math.min(layer.durationMs,studioPlayheadMs-layer.startMs)),
                    ()->canApplyStudioVideo(owner,revision,generation),candidate->saveStudioVideo(candidate,owner,revision,generation),
                    closed->{if(studioVideoEditor==closed)studioVideoEditor=null;if(studioImageMotionDialog==closed.dialog())studioImageMotionDialog=null;});
            studioVideoEditor=editor;studioImageMotionDialog=editor.dialog();
        }catch(Exception error){Toast.makeText(this,"Cannot animate video overlay: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private boolean saveStudioVideo(StudioLayer candidate,String owner,long revision,long generation){
        if(!canApplyStudioVideo(owner,revision,generation))return false;
        String oldTarget=studioSelectedLayerId,oldPane=studioWorkspacePane;
        studioSelectedLayerId=candidate.id;studioWorkspacePane="Inspector";
        if(saveStudioLayer(candidate,revision))return true;
        studioSelectedLayerId=oldTarget;studioWorkspacePane=oldPane;return false;
    }

    private boolean canApplyStudioVideo(String projectId,long revision,long generation){
        if(isFinishing()||isDestroyed()||activeProject==null||!projectId.equals(activeProject.id)||activeProject.updatedAt!=revision
                ||studioSurfaceGeneration!=generation||!"editor".equals(currentScreen)){
            Toast.makeText(this,"The editing view changed. Reopen this video overlay before applying its draft.",Toast.LENGTH_LONG).show();return false;
        }
        return true;
    }
    private void closeStudioVideoEditor(){AutoCloseable editor=studioVideoEditor;studioVideoEditor=null;if(editor!=null)try{editor.close();}catch(Exception ignored){}}

    private void openStudioAudioEnvelope(StudioLayer lane){
        if(activeProject==null||lane==null||!StudioLayer.AUDIO.equals(lane.type))return;
        final String owner=activeProject.id,selected=lane.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        try{
            ProjectStore.Asset asset=activeProject.asset(lane.assetId);
            closeStudioAudioEnvelope();
            studioAudioEnvelopeDialog=StudioAudioEnvelopeDialog.show(this,activeProject,asset,lane,
                    ()->!isFinishing()&&!isDestroyed()&&activeProject!=null&&owner.equals(activeProject.id)
                            &&activeProject.updatedAt==revision&&studioSurfaceGeneration==generation
                            &&"editor".equals(currentScreen)&&selected.equals(studioSelectedLayerId),
                    this::scrubStudioTo,closed->{if(studioAudioEnvelopeDialog==closed)studioAudioEnvelopeDialog=null;});
        }catch(Exception error){Toast.makeText(this,"Cannot analyze audio: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void closeStudioAudioEnvelope(){StudioAudioEnvelopeDialog dialog=studioAudioEnvelopeDialog;studioAudioEnvelopeDialog=null;if(dialog!=null)dialog.close();}
    private void pickStudioVideoFile(){
        if(activeProject==null||!"editor".equals(currentScreen))return;
        selectedVideoPick=new SelectedVideoPick(activeProject.id,activeProject.updatedAt,studioSurfaceGeneration);
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("video/*");picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(picker,PICK_VIDEO_OVERLAY_FILE);}catch(Exception error){selectedVideoPick=null;Toast.makeText(this,"Could not open video Files: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void selectedStudioVideoResult(int resultCode,Intent data){
        SelectedVideoPick selection=selectedVideoPick;selectedVideoPick=null;if(resultCode!=RESULT_OK)return;
        if(selection==null||!canApplyStudioVideo(selection.projectId,selection.revision,selection.generation))return;
        if(data==null||data.getData()==null||data.getClipData()!=null&&data.getClipData().getItemCount()!=1){Toast.makeText(this,"Choose one VIDEO document in Files",Toast.LENGTH_LONG).show();return;}
        try{
            Uri uri=data.getData();if(!"content".equals(uri.getScheme()))throw new IllegalArgumentException("Choose an explicit video document from Files");
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            closeSelectedVideoImport();selectedVideoImport=SelectedVideoImportDialog.picked(this,selection.projectId,selection.revision,uri,selection.operationId,
                    ()->canApplyStudioVideo(selection.projectId,selection.revision,selection.generation),this::selectedVideoImported,
                    closed->{if(selectedVideoImport==closed)selectedVideoImport=null;});
        }catch(Exception error){Toast.makeText(this,"Video import not saved: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void retrySelectedVideoImport(){
        if(activeProject==null||!"editor".equals(currentScreen))return;final String owner=activeProject.id;final long revision=activeProject.updatedAt,generation=studioSurfaceGeneration;
        try{closeSelectedVideoImport();selectedVideoImport=SelectedVideoImportDialog.retry(this,owner,()->canApplyStudioVideo(owner,revision,generation),this::selectedVideoImported,closed->{if(selectedVideoImport==closed)selectedVideoImport=null;});}
        catch(Exception error){Toast.makeText(this,"Video copy retry unavailable: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void selectedVideoImported(JSONObject result){
        if(activeProject==null||!activeProject.id.equals(result.optString("projectId")))return;
        studioWorkspacePane="Media";showEditor();String assetId=result.optString("assetId");
        if(studioVideoCount()<2&&activeProject.asset(assetId)!=null)editStudioVideo(null,assetId);
    }
    private void closeSelectedVideoImport(){SelectedVideoImportDialog dialog=selectedVideoImport;selectedVideoImport=null;if(dialog!=null)dialog.close();}

    private boolean canApplyStudioTitleMotion(String projectId,long revision,long surfaceGeneration){
        if(isFinishing()||isDestroyed()||activeProject==null||!projectId.equals(activeProject.id)
                ||activeProject.updatedAt!=revision||studioSurfaceGeneration!=surfaceGeneration
                ||!"editor".equals(currentScreen)){
            Toast.makeText(this,"The editing view changed. Reopen this title before applying its draft.",Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void renderStudioCompactClips(LinearLayout box){
        HorizontalScrollView scroll=new HorizontalScrollView(this);LinearLayout clips=new LinearLayout(this);scroll.setHorizontalScrollBarEnabled(false);
        int pages=Math.max(1,(activeProject.clips.size()+19)/20);studioTimelinePage=Math.max(0,Math.min(pages-1,studioTimelinePage));
        for(int i=studioTimelinePage*20;i<Math.min(activeProject.clips.size(),(studioTimelinePage+1)*20);i++){
            ProjectStore.Clip clip=activeProject.clips.get(i);ProjectStore.Asset asset=activeProject.asset(clip.assetId);
            LinearLayout chip=column();chip.setPadding(dp(6),dp(4),dp(6),dp(4));chip.setBackground(rounded(C_CARD_2,selectedClip!=null&&clip.id.equals(selectedClip.id)?C_CYAN:StudioUi.BORDER,dp(8)));
            TextView name=body((i+1)+" · "+(asset==null?"Missing media":asset.name));name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);chip.addView(name);chip.addView(body(StudioEditCursor.timecode(clip.outputDurationMs())));
            chip.setContentDescription("Select base clip "+(i+1)+" "+(asset==null?"Missing media":asset.name));
            chip.setOnClickListener(view->{stopStillSequenceOnly();selectedClip=clip;studioSelectedLayerId=null;studioPlayheadMs=StudioTimelineEditOps.startOf(activeProject.clips,clip.id);studioPendingEditedSeek=true;showEditor();previewSelectedStudioClip(true);});
            final int at=i;chip.setOnLongClickListener(view->view.startDragAndDrop(ClipData.newPlainText("videostudio.timeline.clip",clip.id),new View.DragShadowBuilder(view),clip.id,0));
            chip.setOnDragListener((view,event)->{if(event.getAction()==android.view.DragEvent.ACTION_DRAG_STARTED)return event.getLocalState() instanceof String;if(event.getAction()==android.view.DragEvent.ACTION_DROP&&event.getLocalState() instanceof String){moveStudioClipTo((String)event.getLocalState(),at);return true;}return false;});
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(136),dp(60));size.rightMargin=dp(4);clips.addView(chip,size);
        }
        if(activeProject.clips.isEmpty())clips.addView(body("No base clips. Open Media to import or append an owned image/video."));scroll.addView(clips);box.addView(scroll,new LinearLayout.LayoutParams(-1,dp(64)));
        if(pages>1)studioWorkspaceActions(box,new String[]{"‹ Earlier clips","Later clips ›"},new Runnable[]{()->{studioTimelinePage=Math.max(0,studioTimelinePage-1);studioWorkspace.refreshPane();},()->{studioTimelinePage=Math.min(pages-1,studioTimelinePage+1);studioWorkspace.refreshPane();}});
    }

    private Button studioButton(String label,Runnable action){
        Button button=new Button(this);
        button.setText(label);
        StudioUi.styleButton(button,false);
        button.setOnClickListener(v->action.run());
        return button;
    }

    private void showStudioProjectLibrary() {
        List<ProjectStore.Project> all=store.list();
        if(all.isEmpty()){createProjectDialog();return;}
        String[] labels=new String[all.size()];
        for(int i=0;i<all.size();i++){
            ProjectStore.Project p=all.get(i);
            labels[i]=(activeProject!=null&&p.id.equals(activeProject.id)?"✓ ":"")
                    +p.name+"  ·  "+p.clips.size()+" clips"
                    +(p.latestExportUri!=null&&!p.latestExportUri.isEmpty()?"  ·  MP4 ✓":"");
        }
        new AlertDialog.Builder(this).setTitle("My projects")
                .setItems(labels,(dialog,index)->{
                    ProjectStore.Project chosen=store.get(all.get(index).id);
                    if(chosen==null)return;
                    stopStillSequenceOnly();
                    activeProject=chosen;store.setActive(chosen.id);
                    selectedClip=chosen.clips.isEmpty()?null:chosen.clips.get(0);
                    studioTimelinePage=0;studioMediaPage=0;studioPlayheadMs=0;
                    editHistory.reset(chosen.id);
                    syncProtocolState();
                    showEditor();
                })
                .setPositiveButton("+ New", (d,w)->createProjectDialog())
                .setNegativeButton("Close",null).show();
    }

    /** Explicit user-visible list of real completed renders across ALL projects. */
    private void showStudioRenderLibrary() {
        ArrayList<String> descriptions=new ArrayList<>();
        ArrayList<String> uris=new ArrayList<>();
        ArrayList<String> projectIds=new ArrayList<>();
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(ProjectStore.Project p:store.list()){
            if(p.latestExportUri!=null&&!p.latestExportUri.isEmpty()&&
                    seen.add(p.latestExportUri)){
                uris.add(p.latestExportUri);
                descriptions.add("▶ "+(p.latestExportName.isEmpty()?"Latest export":p.latestExportName)
                        +"  •  "+p.name);
                projectIds.add(p.id);
            }
            for(ProjectStore.Asset asset:p.assets){
                if(!asset.generated||asset.uri==null||asset.uri.isEmpty()||
                        asset.mime==null||!asset.mime.startsWith("video/")||
                        !seen.add(asset.uri))continue;
                descriptions.add("▶ "+asset.name+"  •  "+p.name);
                uris.add(asset.uri);projectIds.add(p.id);
                if(uris.size()>=80)break;
            }
            if(uris.size()>=80)break;
        }
        if(uris.isEmpty()){
            new AlertDialog.Builder(this).setTitle("No rendered videos yet")
                    .setMessage("Your imported images and editing timeline are intact. "
                            +"Use Export or Animation Director to create the first MP4, "
                            +"then it will be listed here across projects.")
                    .setPositiveButton("OK",null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Finished Videos • "+uris.size())
                .setItems(descriptions.toArray(new String[0]),(dialog,index)->{
                    stopStillSequenceOnly();
                    ProjectStore.Project p=store.get(projectIds.get(index));
                    if(p!=null){
                        activeProject=p;store.setActive(p.id);
                        selectedClip=p.clips.isEmpty()?null:p.clips.get(0);
                        studioTimelinePage=0;studioPlayheadMs=0;
                        editHistory.reset(p.id);
                        syncProtocolState();
                        showEditor();
                    }
                    if(studioStillPreview!=null)
                        studioStillPreview.setVisibility(View.GONE);
                    player().setContextIds("final:"+System.currentTimeMillis(),"");
                    player().play(Uri.parse(uris.get(index)),0L);
                    setStudioPreviewTarget(StudioPreviewTarget.SAVED_RENDER,"",activeProject.name);applyStudioPreviewTarget();
                })
                .setNegativeButton("Close",null).show();
    }

    private void playStudioLatest(){
        stopStillSequenceOnly();
        if(activeProject==null||activeProject.latestExportUri==null||
                activeProject.latestExportUri.isEmpty()){
            showStudioRenderLibrary();
            return;
        }
        if(studioStillPreview!=null)studioStillPreview.setVisibility(View.GONE);
        try{
            player().setContextIds("final:"+activeProject.latestExportAt,"");
            player().setVolume(1f);
            player().play(Uri.parse(activeProject.latestExportUri),0L);
            setStudioPreviewTarget(StudioPreviewTarget.SAVED_RENDER,"",activeProject.name);applyStudioPreviewTarget();
        }catch(Exception error){
            Toast.makeText(this,"Latest MP4 could not open. Try All Renders.",
                    Toast.LENGTH_LONG).show();
            showStudioRenderLibrary();
        }
    }

    private void queueStudioExport(){
        if(activeProject==null||activeProject.clips.isEmpty()){
            Toast.makeText(this,"Add clips to the timeline before exporting",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final ProjectStore.Project project=activeProject;
        StudioExportDialog.showOptions(this,(aspect,quality,maxOutputSampleDelayMs)->{
                    if(project.clips.isEmpty()){
                        Toast.makeText(this,"Add clips to the timeline before exporting",Toast.LENGTH_LONG).show();
                        return;
                    }
                    try{
                        Intent request=new Intent(this,ControlService.class)
                                .setAction(ControlService.ACTION_LOCAL_EXPORT)
                                .putExtra("projectId",project.id)
                                .putExtra("aspect",aspect)
                                .putExtra("quality",quality)
                                .putExtra("fileName","VideoStudio_"+
                                        System.currentTimeMillis()+".mp4");
                        if(maxOutputSampleDelayMs!=null)request.putExtra(NativeExportOptions.FIELD,maxOutputSampleDelayMs.longValue());
                        startForegroundService(request);
                        Toast.makeText(this,"Export queued. Open Jobs to track the actual result.",
                                Toast.LENGTH_LONG).show();
                        showStudioJobDetails();
                    }catch(Exception e){
                        Toast.makeText(this,"Could not queue native export: "+e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                },studioPreviewAspect);
    }

    private void previewSelectedStudioClip(boolean startPlayback){
        if(!studioPreviewError.isEmpty()){
            if(activeProject!=null&&activeProject.id.equals(studioPreviewErrorProjectId)&&
                    activeProject.updatedAt==studioPreviewErrorRevision){
                // Rebuilding layout is not a Retry and does not prove decoding works.
                applyStudioPreviewTarget();
                if(!isStudioExternalPreviewMode()&&livePlayer!=null&&livePlayer.isEditedPreview())startStudioEditedClock();
                return;
            }
            showStudioPreviewError("");
        }
        if(selectedClip==null||activeProject==null||activeProject.clips.isEmpty())return;
        if(!startPlayback&&!studioPendingEditedSeek&&livePlayer!=null&&livePlayer.isEditedPreview()
                &&activeProject.id.equals(studioEditedProjectId)&&activeProject.updatedAt==studioEditedRevision){
            // Rebuilding native controls is not a seek, composition admission or playback-intent change.
            timelinePreviewRunning=livePlayer.snapshotState().playWhenReady;
            applyStudioPreviewTarget();startStudioEditedClock();return;
        }
        beginStudioEditPreview(startPlayback||timelinePreviewRunning);
    }

    private boolean beginStudioEditPreview(boolean play){
        final int stillVisibility=studioStillPreview==null?View.GONE:studioStillPreview.getVisibility();
        final int captionVisibility=studioCaptionPreview==null?View.GONE:studioCaptionPreview.getVisibility();
        final StudioPreviewTarget previousTarget=studioPreviewTarget;
        final String previousAsset=studioPreviewTargetAssetId,previousName=studioPreviewTargetName;
        try{
            player().playEdit(activeProject,renderEngine,studioPreviewAspect,"720p",studioPlayheadMs,play,
                    ()->{timelinePreviewRunning=false;studioPlayheadMs=activeProject.outputDurationMs();updateStudioPlayToggle();},
                    error->{
                        if(!isStudioExternalPreviewMode()){
                            if(studioStillPreview!=null)studioStillPreview.setVisibility(stillVisibility);
                            if(studioCaptionPreview!=null)studioCaptionPreview.setVisibility(captionVisibility);
                        }
                        timelinePreviewRunning=player().isEditedPreview()&&player().snapshotState().playWhenReady;
                        if(previousTarget!=StudioPreviewTarget.EDITED&&!isStudioExternalPreviewMode()){
                            setStudioPreviewTarget(previousTarget,previousAsset,previousName);applyStudioPreviewTarget();
                        }
                        updateStudioPlayToggle();
                        showStudioPreviewError(error);
                    });
            player().setContextIds("",selectedClip==null?"":selectedClip.id);
            if(studioStillPreview!=null)studioStillPreview.setVisibility(View.GONE);
            if(studioCaptionPreview!=null)studioCaptionPreview.setVisibility(View.GONE);
            if(stillTimelineNext!=null){ui.removeCallbacks(stillTimelineNext);stillTimelineNext=null;}
            if(studioAudioPreview!=null)studioAudioPreview.stop();
            showStudioPreviewError("");
            timelinePreviewRunning=play;
            studioEditedProjectId=activeProject.id;studioEditedRevision=activeProject.updatedAt;studioPendingEditedSeek=false;
            setStudioPreviewTarget(StudioPreviewTarget.EDITED,"","");applyStudioPreviewTarget();
            startStudioEditedClock();
            return true;
        }catch(Exception error){
            showStudioPreviewError("Edited preview unavailable: "+error.getMessage());
            return false;
        }
    }

    private void showStudioPreviewError(String error){
        studioPreviewError=error==null?"":error;
        studioPreviewErrorProjectId=studioPreviewError.isEmpty()||activeProject==null?"":activeProject.id;
        studioPreviewErrorRevision=studioPreviewError.isEmpty()||activeProject==null?-1:activeProject.updatedAt;
        if(studioPreviewErrorView!=null){
            studioPreviewErrorView.setText(studioPreviewError);
            studioPreviewErrorView.setVisibility(studioPreviewError.isEmpty()?View.GONE:View.VISIBLE);
        }
        if(studioPreviewRetry!=null)studioPreviewRetry.setVisibility(studioPreviewError.isEmpty()?View.GONE:View.VISIBLE);
    }

    private void startStudioEditedClock(){
        if(studioCaptionClock!=null)ui.removeCallbacks(studioCaptionClock);
        studioCaptionClock=new Runnable(){
            @Override public void run(){
                if(!"editor".equals(currentScreen)||activeProject==null||!player().isEditedPreview())return;
                long previous=studioPlayheadMs;
                studioPlayheadMs=Math.min(activeProject.outputDurationMs(),player().currentPositionMs());
                String time=StudioEditCursor.timecode(studioPlayheadMs)+" / "+StudioEditCursor.timecode(activeProject.outputDurationMs());
                if(studioTimeLabel!=null&&!time.contentEquals(studioTimeLabel.getText()))studioTimeLabel.setText(time);
                if(studioSeek!=null&&!studioSeek.isPressed()){
                    int progress=(int)Math.round(studioPlayheadMs*studioSeek.getMax()/(double)Math.max(1,activeProject.outputDurationMs()));
                    if(studioSeek.getProgress()!=progress)studioSeek.setProgress(progress);
                }
                if(studioTracks!=null&&previous!=studioPlayheadMs)studioTracks.setPlayhead(studioPlayheadMs);
                if(studioSplitButton!=null){boolean canSplit=canSplitStudioAtCursor();if(studioSplitButton.isEnabled()!=canSplit)studioSplitButton.setEnabled(canSplit);}
                updateStudioPlayToggle();
                ui.postDelayed(this,player().snapshotState().playWhenReady?100:250);
            }
        };
        ui.post(studioCaptionClock);
    }

    private void scrubStudioTo(long timestamp){
        if(activeProject==null||activeProject.clips.isEmpty())return;
        stopStillSequenceOnly();
        StudioTimelineClock.Position position=
                StudioTimelineClock.locate(activeProject.clips,timestamp);
        if(position.clipIndex<0)return;
        studioPlayheadMs=position.globalTimeMs;
        selectedClip=activeProject.clips.get(position.clipIndex);
        studioTimelinePage=position.clipIndex/20;
        studioPendingEditedSeek=true;
        showEditor();
    }

    private long[] studioClipBoundaries(){
        long[] cuts=new long[activeProject.clips.size()+1];long at=0;int i=0;
        for(ProjectStore.Clip clip:activeProject.clips){cuts[i++]=at;at+=clip.outputDurationMs();}
        cuts[i]=at;return cuts;
    }

    private void seekStudioBoundary(int direction){
        if(activeProject!=null)scrubStudioTo(StudioEditCursor.adjacentBoundary(studioClipBoundaries(),studioPlayheadMs,direction));
    }

    private void studioSeekDialog(){
        if(activeProject==null||activeProject.clips.isEmpty())return;
        EditText position=numberInput(0);position.setText(String.format(Locale.US,"%.3f",studioPlayheadMs/1000.0));
        position.setHint("Timeline position in seconds");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Go to exact timeline time (seconds)").setView(position)
                .setPositiveButton("Go",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{long requested=secondsToMs(position);if(requested>activeProject.outputDurationMs())throw new IllegalArgumentException("Time exceeds the edit duration");
                dialog.dismiss();scrubStudioTo(requested);
            }catch(Exception error){position.setError(error.getMessage());}
        }));dialog.show();
    }

    /** GPU edited composition, including stills, timed text and independently mixed audio. */
    private boolean playStudioTimeline(){
        if(activeProject==null||activeProject.clips.isEmpty()){
            showStudioPreviewError("Timeline is empty. Add clips, then Retry preview.");return false;
        }
        return beginStudioEditPreview(true);
    }

    private void playStudioFrom(int index){
        playStudioFrom(index,0);
    }
    private void playStudioFrom(int index,long offsetInClipMs){
        if(!timelinePreviewRunning||activeProject==null||
                index>=activeProject.clips.size()){
            timelinePreviewRunning=false;
            return;
        }
        ProjectStore.Clip clip=activeProject.clips.get(index);
        selectedClip=clip;
        studioPlayheadMs=0;
        for(int i=0;i<index;i++)studioPlayheadMs+=activeProject.clips.get(i).outputDurationMs();
        long actualOffset=Math.max(0,Math.min(
                Math.max(0,clip.outputDurationMs()-1),offsetInClipMs));
        studioPlayheadMs+=actualOffset;
        if(studioTimeLabel!=null)studioTimeLabel.setText(
                time(studioPlayheadMs)+" / "+time(activeProject.outputDurationMs()));
        updateStudioCaptionAt(studioPlayheadMs);
        startStudioCaptionClock(clip,studioPlayheadMs-actualOffset,actualOffset);
        ProjectStore.Asset asset=activeProject.asset(clip.assetId);
        if(asset==null){playStudioFrom(index+1);return;}
        if(asset.mime!=null&&asset.mime.startsWith("video/")){
            if(studioStillPreview!=null)studioStillPreview.setVisibility(View.GONE);
            player().setContextIds("",clip.id);
            player().setVolume(clip.volume);
            player().playClip(Uri.parse(ProxyManager.previewUri(activeProject,asset)),
                    Math.min(Math.max(clip.inMs,clip.outMs-1),
                            clip.inMs+Math.round(actualOffset*clip.speed)),
                    clip.outMs,clip.speed,()->{
                        if(timelinePreviewRunning)playStudioFrom(index+1);
                    });
        }else if(asset.mime!=null&&asset.mime.startsWith("image/")){
            player().pause();
            if(studioStillPreview!=null){
                studioStillPreview.setVisibility(View.VISIBLE);
                studioThumbs.bindPreview(studioStillPreview,asset);
            }
            stillTimelineNext=()->{
                stillTimelineNext=null;
                if(timelinePreviewRunning)playStudioFrom(index+1);
            };
            ui.postDelayed(stillTimelineNext,
                    Math.max(50,clip.outputDurationMs()-actualOffset));
        }else playStudioFrom(index+1);
    }

    private void stopStillSequenceOnly(){
        timelinePreviewRunning=false;
        if(livePlayer!=null&&player().isEditedPreview())player().pause();
        if(stillTimelineNext!=null){
            ui.removeCallbacks(stillTimelineNext);
            stillTimelineNext=null;
        }
        if(studioCaptionClock!=null){
            ui.removeCallbacks(studioCaptionClock);
            studioCaptionClock=null;
        }
        if(studioAudioPreview!=null)studioAudioPreview.stop();
    }

    private void renderStudioMediaBin(LinearLayout box){
        ArrayList<ProjectStore.Asset> assets=new ArrayList<>(activeProject.assets);
        assets.sort((a,b)->{
            if(a.generated!=b.generated)return a.generated?-1:1;
            return Long.compare(b.createdAt,a.createdAt);
        });
        int size=15;
        int pages=Math.max(1,(assets.size()+size-1)/size);
        studioMediaPage=Math.max(0,Math.min(studioMediaPage,pages-1));
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tiles=new LinearLayout(this);
        for(int i=studioMediaPage*size;i<Math.min(assets.size(),(studioMediaPage+1)*size);i++){
            ProjectStore.Asset asset=assets.get(i);
            LinearLayout tile=card(false);
            tile.setPadding(dp(7),dp(8),dp(7),dp(6));
            TextView tag=accent(asset.generated?"RENDER / GENERATED":"SOURCE",asset.generated?C_CYAN:C_MUTED);
            tile.addView(tag);
            ImageView thumbnail=new ImageView(this);
            if(studioThumbs!=null)studioThumbs.bind(thumbnail,asset);
            tile.addView(thumbnail,margins(-1,dp(78),dp(4),0,0,0));
            TextView name=body(asset.name);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(name,margins(-1,-2,dp(3),0,0,0));
            Button use=studioButton(asset.mime!=null&&asset.mime.startsWith("audio/")?
                    "+ Music Track":"Preview",()->{
                stopStillSequenceOnly();
                if(asset.mime!=null&&asset.mime.startsWith("audio/")){
                    editStudioAudio(null,asset.id);
                }else if(asset.mime!=null&&asset.mime.startsWith("video/")){
                    studioStillPreview.setVisibility(View.GONE);
                    player().setContextIds("asset:"+asset.id,"");
                    player().play(Uri.parse(ProxyManager.previewUri(activeProject,asset)),0L);
                    setStudioPreviewTarget(StudioPreviewTarget.SOURCE_VIDEO,asset.id,asset.name);applyStudioPreviewTarget();
                }else if(asset.mime!=null&&asset.mime.startsWith("image/")){
                    player().pause();
                    setStudioPreviewTarget(StudioPreviewTarget.SOURCE_STILL,asset.id,asset.name);applyStudioPreviewTarget();
                }
            });
            tile.addView(use,margins(-1,dp(36),dp(3),0,0,0));
            if(asset.mime!=null&&(asset.mime.startsWith("video/")||
                    asset.mime.startsWith("image/"))){
                Button add=studioButton("+ Timeline",()->{
                    try{applyStudioTimeline(new JSONObject().put("action","append").put("assetId",asset.id),activeProject.updatedAt);}
                    catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                });
                tile.addView(add,margins(-1,dp(36),dp(3),0,0,0));
            }
            tiles.addView(tile,margins(dp(162),-2,dp(3),dp(3),0,dp(7)));
        }
        if(assets.isEmpty())tiles.addView(body("Import media or ask ChatGPT to create a new project."));
        scroll.addView(tiles);
        box.addView(scroll);
        if(pages>1){
            Button more=studioButton("Media page "+(studioMediaPage+1)+"/"+pages+
                    "  ·  Next ›",()->{
                        studioMediaPage=(studioMediaPage+1)%pages;showEditor();
                    });
            box.addView(more,margins(-1,dp(42),dp(2),0,0,0));
        }
    }

    private void studioCropDialog(){
        if(activeProject==null||selectedClip==null){Toast.makeText(this,"Select a clip first",Toast.LENGTH_SHORT).show();return;}
        String clipId=selectedClip.id;long revision=activeProject.updatedAt;JSONObject old=selectedClip.effects.optJSONObject("crop");LinearLayout form=column();String[] names={"x","y","width","height"};EditText[] fields=new EditText[4];
        for(int n=0;n<4;n++){fields[n]=numberInput(0);fields[n].setHint("Source "+names[n]+" (0..1)");fields[n].setText(String.valueOf(old==null?(n<2?0:1):old.optDouble(names[n],n<2?0:1)));form.addView(fields[n]);}
        new AlertDialog.Builder(this).setTitle("Crop / reframe source").setView(form).setNegativeButton("Cancel",null).setNeutralButton("Reset",(d,w)->{try{applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",new JSONObject().put("crop",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))),revision);}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}).setPositiveButton("Apply",(d,w)->{try{JSONObject crop=new JSONObject();for(int n=0;n<4;n++)crop.put(names[n],Double.parseDouble(fields[n].getText().toString()));new StudioCropEffect(crop);applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",new JSONObject().put("crop",crop)),revision);}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}).show();
    }

    private void performStudioTool(String name){
        if(!StudioToolCatalog.isSupported(name)){
            new AlertDialog.Builder(this).setTitle(name+" is unavailable")
                    .setMessage("This tool has no complete native rendering implementation yet. Your project has not been changed.")
                    .setPositiveButton("OK",null).show();return;
        }
        switch(name){
            case "Add Music Track":chooseStudioAudio();return;
            case "Add Caption Track":editStudioTitle(null);return;
            case "Adjust":studioAdvancedAdjustDialog(false);return;
            case "Transform":studioAdvancedAdjustDialog(true);return;
            case "Exact Transform":studioNumericDialog(true,false);return;
            case "Keyframes":studioNumericDialog(true,true);return;
            case "Crop":case "Reframe":studioCropDialog();return;
            case "Opacity":studioPixelDialog("Opacity");return;
            case "Green Screen":studioPixelDialog("Green Screen");return;
            case "Mask":studioPixelDialog("Mask");return;
            case "Glow":studioPixelDialog("Glow");return;
            case "Overlay":chooseStudioImage();return;
            case "Sketch Studio":startActivity(new Intent(this,SketchStudioActivity.class));return;
            case "Open Pipeline":openPipelineStudio();return;
            case "Slow Motion": studioSpeedDialog();return;
            case "Volume": studioVolumeDialog();return;
            case "Blur": studioBlurDialog();return;
            case "Effects":studioPresetDialog("Native effect","effectPreset",new String[]{"none","gaussian_blur","dream"});return;
            case "Shake":
                try{applyStudioPrecision(new JSONObject().put("clipId",selectedClip.id)
                        .put("set",new JSONObject().put("motionPreset","impact_shake")),activeProject.updatedAt);}
                catch(Exception error){Toast.makeText(this,"Select a clip first",Toast.LENGTH_SHORT).show();}return;
            case "Colour": studioPresetDialog("Color grade","colorPreset",
                    new String[]{"none","cinematic","teal_orange","warm_film",
                            "cool_night","noir","golden_hour","matte",
                            "high_contrast","soft_portrait"});return;
            case "Transitions": studioPresetDialog("Clip transition","transition",
                    CreatorCatalog.TRANSITIONS.toArray(new String[0]));return;
            case "Motion": studioPresetDialog("Camera motion","motionPreset",
                    CreatorCatalog.MOTIONS.stream().filter(StudioPrecisionEdits.MOTIONS::contains).toArray(String[]::new));return;
            case "Delete Clip": deleteStudioClip(); return;
            case "Move Left": moveStudioClip(-1); return;
            case "Move Right": moveStudioClip(1); return;
            case "Animation Director": animationDirectorDialog();return;
            case "Storyboard Artwork": storyboardArtworkDialog();return;
            case "Storyboard Studio": storyboardStudioDialog();return;
            case "Rig Builder": open2DRigEditor();return;
            case "Render Rigged 2D": queueUserRiggedDuel();return;
            case "3D Model Viewer": open3DModelViewer();return;
            case "Render 3D Fight": choose3DCombatActors();return;
            case "Combat Benchmark": combatRigBenchmarkDialog();return;
            default: applyTool(name);return;
        }
    }

    /**
     * Music and caption lanes live independently of sequential video clips.
     * These are editable, source-backed tracks, not decorative timeline labels.
     */
    private void renderStudioTrackLanes(LinearLayout box){
        final boolean professional=prefs.getBoolean("studio_professional_multitrack",false);
        box.addView(studioButton(professional?"Timeline view: Multitrack · tap for compact":
                "Timeline view: Compact · tap for multitrack",()->{
            prefs.edit().putBoolean("studio_professional_multitrack",!professional).apply();
            if(studioWorkspace!=null)studioWorkspace.refreshPane();
        }));
        StudioTracksView bands=new StudioTracksView(this);studioTracks=bands;
        bands.setQuietEditorPalette(true);
        bands.setProfessionalRows(professional);
        bands.setOnPinchZoom(factor->{
            int before=studioTrackZoom;
            if(factor>1.18f)studioTrackZoom=Math.min(16,studioTrackZoom*2);
            else if(factor<.84f)studioTrackZoom=Math.max(1,studioTrackZoom/2);
            if(before!=studioTrackZoom&&studioWorkspace!=null)studioWorkspace.refreshPane();
        });
        bands.setTimeline(activeProject.outputDurationMs(),studioPlayheadMs,activeProject.studioLayers,this::scrubStudioTo,new StudioTracksView.ChangeListener(){
            @Override public void onChange(String id,long start,long duration){moveStudioLayerOnTimeline(id,start,duration);}
            @Override public void onTrimLeft(String id,long start,long duration){moveStudioLayerOnTimeline(id,start,duration,true);}
            @Override public void onSelect(String id){selectStudioLayer(id);}
        });
        bands.setSnapping(studioClipBoundaries(),studioTrackSnap);
        HorizontalScrollView scrolling=new HorizontalScrollView(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                if(View.MeasureSpec.getMode(widthSpec)!=View.MeasureSpec.UNSPECIFIED){
                    // Configuration reflow must use the new pane width in this layout,
                    // rather than clipping the old portrait width until a second pass.
                    int width=Math.max(dp(48),View.MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
                    bands.getLayoutParams().width=width*studioTrackZoom;
                }
                super.onMeasure(widthSpec,heightSpec);
            }
        };
        // Width follows this pane, including landscape's side-by-side workspace.
        int screen=getResources().getConfiguration().screenWidthDp;
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int initial=Math.max(dp(180),dp(screen>0?(landscape?(screen-36)*115/215:screen-24):280));
        // Professional mode gives each media/title/group lane its own hit row.
        // The parent pane's existing ScrollView owns vertical scrolling, while
        // this HorizontalScrollView owns the ruler and zoomed time axis.
        int height=bands.desiredHeightPx();
        scrolling.addView(bands,new FrameLayout.LayoutParams(initial*studioTrackZoom,height));
        box.addView(scrolling,new LinearLayout.LayoutParams(-1,height));
        scrolling.post(()->{int width=scrolling.getWidth();scrolling.scrollTo(Math.max(0,(int)(studioPlayheadMs/(double)Math.max(1,activeProject.outputDurationMs())*bands.getWidth())-width/2),0);});
        if(studioAudioWaveforms!=null)for(StudioLayer audio:activeProject.studioLayers){
            if(!StudioLayer.AUDIO.equals(audio.type))continue;ProjectStore.Asset source=activeProject.asset(audio.assetId);if(source==null)continue;
            java.lang.ref.WeakReference<StudioTracksView> target=new java.lang.ref.WeakReference<>(bands);String id=audio.id;
            studioAudioWaveforms.request(source,audio,(key,peaks)->{StudioTracksView visible=target.get();if(visible!=null&&visible.isAttachedToWindow())visible.setWaveform(id,peaks,(float)Math.min(1.0,30000.0/Math.max(1,audio.durationMs)));});
        }
    }

    private void moveStudioLayerOnTimeline(String id,long start,long duration){
        moveStudioLayerOnTimeline(id,start,duration,false);
    }

    private void moveStudioLayerOnTimeline(String id,long start,long duration,boolean trimLeft){
        if(activeProject==null||id==null)return;
        ProjectStore.Project latest=store.get(activeProject.id);
        if(latest==null)return;
        if(latest.updatedAt!=activeProject.updatedAt){
            activeProject=latest;editHistory.reset(null);showEditor();
            Toast.makeText(this,"A background edit changed the project. Try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        StudioLayer existing=null;
        for(StudioLayer layer:activeProject.studioLayers)
            if(layer.id.equals(id)){existing=layer;break;}
        if(existing==null||existing.startMs==start&&existing.durationMs==duration)return;
        try{
            StudioLayer changed=existing.copy();
            if(trimLeft&&(StudioLayer.AUDIO.equals(changed.type)||StudioLayer.VIDEO.equals(changed.type)))changed.sourceInMs+=start-existing.startMs;
            changed.startMs=start;
            changed.durationMs=duration;
            changed.validate();
            validateStudioLayerTiming(changed);
            studioPlayheadMs=start;
            saveStudioLayer(changed,activeProject.updatedAt);
        }catch(Exception error){
            Toast.makeText(this,"Could not move track: "+error.getMessage(),
                    Toast.LENGTH_LONG).show();
            showEditor();
        }
    }

    private void chooseStudioAudio(){
        if(activeProject==null||activeProject.clips.isEmpty()){
            Toast.makeText(this,"Create the video timeline before adding music",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<ProjectStore.Asset> audio=new ArrayList<>();
        for(ProjectStore.Asset a:activeProject.assets)
            if(a.mime!=null&&a.mime.startsWith("audio/"))audio.add(a);
        if(audio.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Add music or narration")
                .setMessage("Import an audio file through the Android Files picker. "+
                        "It will appear here as an independent timeline lane "+
                        "without changing the video or your original media.")
                .setPositiveButton("Import Audio",(d,w)->pickMedia())
                .setNegativeButton("Cancel",null).show();
            return;
        }
        String[] labels=new String[audio.size()];
        for(int i=0;i<audio.size();i++)
            labels[i]=audio.get(i).name;
        new AlertDialog.Builder(this).setTitle("Choose an imported audio file")
                .setItems(labels,(d,index)->editStudioAudio(null,audio.get(index).id))
                .setNegativeButton("Import another",(d,w)->pickMedia())
                .show();
    }

    private void editStudioAudio(StudioLayer existing,String assetId){
        ProjectStore.Asset asset=activeProject==null?null:activeProject.asset(assetId);
        if(asset==null||asset.mime==null||!asset.mime.startsWith("audio/")){
            Toast.makeText(this,"Audio asset is missing. Import it again.",
                    Toast.LENGTH_LONG).show();return;
        }
        final long revision=activeProject.updatedAt;
        StudioLayer original=existing==null?new StudioLayer():existing.copy();
        original.type=StudioLayer.AUDIO;
        original.assetId=asset.id;
        if(existing==null){
            original.startMs=Math.min(studioPlayheadMs,
                    Math.max(0,activeProject.outputDurationMs()-1000));
            long remaining=activeProject.outputDurationMs()-original.startMs;
            original.durationMs=Math.max(100,Math.min(remaining,
                    asset.durationMs>0?asset.durationMs:remaining));
        }
        LinearLayout wrap=column();
        wrap.setPadding(dp(20),dp(4),dp(20),0);
        wrap.addView(body("Start on timeline (seconds)"));
        EditText start=numberInput(original.startMs/1000f);
        wrap.addView(start);
        wrap.addView(body("Duration (seconds)"));
        EditText length=numberInput(original.durationMs/1000f);
        wrap.addView(length);
        wrap.addView(body("Source in-point (seconds)"));
        EditText source=numberInput(original.sourceInMs/1000f);
        wrap.addView(source);
        TextView gainText=body("Music volume "+Math.round(original.gain*100)+"%");
        wrap.addView(gainText);
        SeekBar gain=new SeekBar(this);
        gain.setMax(100);gain.setProgress(Math.round(original.gain*100));
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user){
                gainText.setText("Music volume "+progress+"%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        wrap.addView(gain);
        new AlertDialog.Builder(this).setTitle("♫ Audio Track · "+asset.name)
                .setView(wrap)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        StudioLayer edited=original.copy();
                        edited.startMs=secondsToMs(start);
                        edited.durationMs=secondsToMs(length);
                        edited.sourceInMs=secondsToMs(source);
                        edited.gain=gain.getProgress()/100f;
                        edited.validate();
                        validateStudioLayerTiming(edited);
                        saveStudioLayer(edited,revision);
                    }catch(Exception error){
                        Toast.makeText(this,error.getMessage()==null?"Invalid audio timing":error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Cancel",null).show();
    }

    private void editStudioTitle(StudioLayer existing){
        if(activeProject==null||activeProject.clips.isEmpty()){
            Toast.makeText(this,"Add video or image clips first",Toast.LENGTH_LONG).show();return;
        }
        if(activeProject.outputDurationMs()<100){
            Toast.makeText(this,"A caption needs at least 0.100 seconds on the timeline",Toast.LENGTH_LONG).show();return;
        }
        final String projectId=activeProject.id;final long revision=activeProject.updatedAt;
        StudioLayer original=existing==null?new StudioLayer():existing.copy();original.type=StudioLayer.TITLE;
        if(existing==null){
            original.startMs=Math.min(studioPlayheadMs,Math.max(0,activeProject.outputDurationMs()-1000));
            original.durationMs=Math.min(2500,Math.max(100,activeProject.outputDurationMs()-original.startMs));
        }
        StudioCaptionEditor.show(this,original,activeProject.outputDurationMs(),edited->{
            if(isFinishing()||isDestroyed()||activeProject==null||!projectId.equals(activeProject.id))return false;
            validateStudioLayerTiming(edited);return saveStudioLayer(edited,revision);
        });
    }

    private void validateStudioLayerTiming(StudioLayer lane){
        StudioLayerContract.validate(activeProject,lane);
        if(lane.startMs+lane.durationMs>activeProject.outputDurationMs())
            throw new IllegalArgumentException("Layer must end inside the video timeline");
        if(activeProject.studioLayers.size()>=32&&
                activeProject.studioLayers.stream().noneMatch(x->x.id.equals(lane.id)))
            throw new IllegalArgumentException("Limit is 32 timed layers");
        if(StudioLayer.AUDIO.equals(lane.type)){
            ProjectStore.Asset asset=activeProject.asset(lane.assetId);
            if(asset==null||!asset.mime.startsWith("audio/"))
                throw new IllegalArgumentException("Audio source is not imported");
            if(asset.durationMs>0&&lane.sourceInMs+lane.durationMs>asset.durationMs+20)
                throw new IllegalArgumentException("Audio is shorter than the selected trim");
        }
    }
    private void replaceStudioLayer(String id,StudioLayer replacement){
        for(int i=0;i<activeProject.studioLayers.size();i++)
            if(id.equals(activeProject.studioLayers.get(i).id)){
                activeProject.studioLayers.set(i,replacement);return;
            }
        throw new IllegalArgumentException("Timed layer no longer exists");
    }
    private void removeStudioLayer(StudioLayer lane){
        final long revision=activeProject.updatedAt;
        new AlertDialog.Builder(this).setTitle("Remove track?")
                .setMessage("Remove this timed layer from the edit? "+
                        "The original imported file is preserved.")
                .setPositiveButton("Remove",(d,w)->{
                    ArrayList<StudioLayer> retained=new ArrayList<>(activeProject.studioLayers);
                    retained.removeIf(x->x.id.equals(lane.id));applyStudioLayerList(retained,revision);
                })
                .setNegativeButton("Cancel",null).show();
    }

    private void studioAdvancedAdjustDialog(boolean transform){
        if(activeProject==null||selectedClip==null){
            Toast.makeText(this,"Choose a clip in the timeline",Toast.LENGTH_SHORT).show();
            return;
        }
        String[] keys=transform?StudioClipAdjustments.TRANSFORM_KEYS:
                StudioClipAdjustments.COLOR_KEYS;
        final long expectedRevision=activeProject.updatedAt;
        final String clipId=selectedClip.id;
        String[] labels=transform?StudioClipAdjustments.TRANSFORM_NAMES:
                StudioClipAdjustments.COLOR_NAMES;
        LinearLayout content=column();
        content.setPadding(dp(16),dp(9),dp(16),dp(9));
        final SeekBar[] controls=new SeekBar[keys.length];
        content.addView(studioButton("Exact numeric values",()->studioNumericDialog(transform,false)));
        if(transform){
            content.addView(studioButton("Transform keyframes",()->studioNumericDialog(true,true)));
            content.addView(studioButton("Clear transform keyframes",()->{
                try{applyStudioPrecision(new JSONObject().put("clipId",clipId).put("clearKeyframes",true),expectedRevision);}
                catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
            }));
        }
        for(int i=0;i<keys.length;i++){
            final int index=i;
            int current=StudioClipAdjustments.sliderFor(selectedClip.effects,keys[i]);
            TextView value=body(labels[i]+"  "+StudioClipAdjustments.labelFor(keys[i],current));
            content.addView(value,margins(-1,-2,dp(3),0,0,0));
            SeekBar seek=new SeekBar(this);
            int low="scale".equals(keys[i])?50:0;
            int high="scale".equals(keys[i])?250:
                    ("rotate".equals(keys[i])?90:200);
            seek.setMax(high-low);
            seek.setProgress(current-low);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onProgressChanged(SeekBar bar,int pos,boolean user){
                    value.setText(labels[index]+"  "+
                            StudioClipAdjustments.labelFor(keys[index],pos+low));
                }
                @Override public void onStartTrackingTouch(SeekBar bar){}
                @Override public void onStopTrackingTouch(SeekBar bar){}
            });
            controls[i]=seek;
            content.addView(seek,margins(-1,dp(43),0,0,0,0));
        }
        new AlertDialog.Builder(this)
                .setTitle(transform?"Position & Transform":"Adjust Color")
                .setView(content)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        JSONObject settings=new JSONObject();
                        for(int i=0;i<keys.length;i++){
                            int low="scale".equals(keys[i])?50:0;
                            StudioClipAdjustments.apply(settings,keys[i],controls[i].getProgress()+low);
                        }
                        applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",settings),expectedRevision);
                    }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                })
                .setNeutralButton("Reset",(d,w)->{
                    try{applyStudioPrecision(new JSONObject().put("clipId",clipId)
                            .put(transform?"resetTransform":"resetColor",true),expectedRevision);}
                    catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                })
                .setNegativeButton("Cancel",null).show();
    }

    private boolean applyStudioPrecision(JSONObject operation,long expectedRevision){
        if(activeProject==null)return false;
        try{
            ProjectStore.Project before=ProjectStore.Project.fromJson(activeProject.toJson());
            JSONObject request=new JSONObject().put("projectId",activeProject.id).put("expectedUpdatedAt",expectedRevision)
                    .put("operations",new JSONArray().put(operation));
            JSONObject result=StudioPrecisionEdits.apply(this,store,request);
            ProjectStore.Project saved=store.get(result.getString("projectId"));
            if(saved==null)throw new IllegalStateException("Saved project is unavailable");
            if(saved.updatedAt==result.getLong("updatedAt"))editHistory.checkpoint(before);
            else editHistory.reset(null);
            String clipId=operation.getString("clipId");
            activeProject=saved;selectedClip=null;
            for(ProjectStore.Clip clip:activeProject.clips)if(clipId.equals(clip.id)){selectedClip=clip;break;}
            syncProtocolState();showEditor();return true;
        }catch(Exception error){
            Toast.makeText(this,"Edit not applied: "+error.getMessage(),Toast.LENGTH_LONG).show();return false;
        }
    }

    private void studioNumericDialog(boolean transform,boolean keyframe){
        if(activeProject==null||selectedClip==null){Toast.makeText(this,"Select a clip first",Toast.LENGTH_SHORT).show();return;}
        if(keyframe){studioKeyframeManager();return;}
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        final long duration=selectedClip.outputDurationMs();
        try{
            JSONObject effects=new JSONObject(selectedClip.effects.toString());
            StudioClipKeyframes.Values values=StudioClipKeyframes.fromEffects(effects,duration).at(0);
            String[] keys=transform?new String[]{"x","y","scaleX","scaleY","rotate","anchorX","anchorY","opacity"}:StudioClipAdjustments.COLOR_KEYS;
            double[] initial=transform?new double[]{values.x,values.y,values.scaleX,values.scaleY,values.rotate,values.anchorX,values.anchorY,values.opacity}:
                    new double[]{effects.optDouble("brightness",0),effects.optDouble("contrast",0),effects.optDouble("saturationAdjust",0),effects.optDouble("lightnessAdjust",0)};
            LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));
            content.addView(body(transform?"X/Y: fraction of output frame, positive right/down. Scale: 0.1..8. Rotation: degrees. Anchor: 0..1.":
                    "Brightness/contrast: -1..1. Saturation/lightness: -100..100."));
            EditText[] fields=new EditText[keys.length];
            for(int i=0;i<keys.length;i++){content.addView(body(keys[i]));fields[i]=numberInput((float)initial[i]);content.addView(fields[i]);}
            ScrollView scroll=new ScrollView(this);scroll.addView(content);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(transform?"Exact transform":"Exact color")
                    .setView(scroll).setPositiveButton("Apply",null)
                    .setNeutralButton("Reset",null).setNegativeButton("Cancel",null).create();
            dialog.setOnShowListener(d->{
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    try{
                        JSONObject settings=new JSONObject();for(int i=0;i<keys.length;i++)settings.put(keys[i],Double.parseDouble(fields[i].getText().toString()));
                        JSONObject operation=new JSONObject().put("clipId",clipId).put("set",settings);
                        if(applyStudioPrecision(operation,revision))dialog.dismiss();
                    }catch(Exception error){Toast.makeText(this,"Invalid values: "+error.getMessage(),Toast.LENGTH_LONG).show();}
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{
                    if(applyStudioPrecision(new JSONObject().put("clipId",clipId).put(transform?"resetTransform":"resetColor",true),revision))dialog.dismiss();
                }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}});
            });dialog.show();
        }catch(Exception error){Toast.makeText(this,"Cannot edit transform: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void studioKeyframeManager(){
        if(activeProject==null||selectedClip==null)return;
        final String clipId=selectedClip.id;
        final long[] revision={activeProject.updatedAt};
        final long clipStart=StudioTimelineEditOps.startOf(activeProject.clips,clipId);
        final long duration=selectedClip.outputDurationMs();
        final long local=Math.max(0,Math.min(duration,studioPlayheadMs-clipStart));
        try{
            StudioKeyframeEditor.show(this,selectedClip.effects,duration,local,fragment->{
                try{
                    fragment.put("clipId",clipId);
                    if(!applyStudioPrecision(fragment,revision[0]))return false;
                    revision[0]=activeProject.updatedAt;return true;
                }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();return false;}
            },at->{studioPlayheadMs=clipStart+at;studioPendingEditedSeek=true;previewSelectedStudioClip(false);});
        }catch(Exception error){Toast.makeText(this,"Cannot open keyframes: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void openPipelineStudio(){
        Intent intent=new Intent(this,OpenPipelineActivity.class);
        if(activeProject!=null)intent.putExtra("projectId",activeProject.id);startActivity(intent);
    }

    private void studioPixelDialog(String tool){
        if(activeProject==null||selectedClip==null){Toast.makeText(this,"Select a clip first",Toast.LENGTH_SHORT).show();return;}
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        final JSONObject effects=selectedClip.effects;
        LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));
        String[] keys;double[] defaults;
        android.widget.CheckBox enabled=new android.widget.CheckBox(this);enabled.setTextColor(C_TEXT);
        EditText color=new EditText(this);color.setSingleLine(true);color.setText(effects.optString("chromaColor","#00FF00"));
        android.widget.Spinner shape=new android.widget.Spinner(this);
        String[] shapes={"none","rectangle","ellipse"};
        shape.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,shapes));
        if("Green Screen".equals(tool)){
            enabled.setText("Enable chroma key");enabled.setChecked(effects.optBoolean("chromaKey",true));content.addView(enabled);
            content.addView(body("Key color #RRGGBB"));content.addView(color);
            content.addView(body("Tolerance/feather: 0..1 in linear RGB. Removed pixels become transparent; MP4 has an opaque output background."));
            keys=new String[]{"chromaTolerance","chromaFeather"};defaults=new double[]{.12,.05};
        }else if("Mask".equals(tool)){
            shape.setSelection(Math.max(0,java.util.Arrays.asList(shapes).indexOf(effects.optString("maskShape","rectangle"))));
            content.addView(body("Mask shape"));content.addView(shape);
            content.addView(body("Bounds use output coordinates 0..1 from top-left. Feather: 0..0.5 of the output frame."));
            keys=new String[]{"maskLeft","maskTop","maskRight","maskBottom","maskFeather"};defaults=new double[]{0,0,1,1,.02};
        }else if("Glow".equals(tool)){
            content.addView(body("Thresholded nine-tap bloom. Strength: 0..2; radius: 0..24 output pixels; threshold: 0..1."));
            keys=new String[]{"glowStrength","glowRadiusPx","glowThreshold"};defaults=new double[]{.5,6,.7};
        }else{
            content.addView(body("Opacity: 0..1. Opacity keyframes override this static value; edit or clear them in Keyframes."));
            keys=new String[]{"opacity"};defaults=new double[]{1};
        }
        EditText[] fields=new EditText[keys.length];
        for(int i=0;i<keys.length;i++){content.addView(body(keys[i]));fields[i]=numberInput((float)effects.optDouble(keys[i],defaults[i]));content.addView(fields[i]);}
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tool).setView(scroll)
                .setPositiveButton("Apply",null).setNeutralButton("Opacity".equals(tool)?"Reset static":"Disable",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                JSONObject settings=new JSONObject();for(int i=0;i<keys.length;i++)settings.put(keys[i],Double.parseDouble(fields[i].getText().toString()));
                if("Green Screen".equals(tool))settings.put("chromaKey",enabled.isChecked()).put("chromaColor",color.getText().toString().trim());
                if("Mask".equals(tool))settings.put("maskShape",shape.getSelectedItem().toString());
                if(applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",settings),revision))dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,"Invalid pixel controls: "+error.getMessage(),Toast.LENGTH_LONG).show();}});
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{
                JSONObject settings=new JSONObject();
                if("Green Screen".equals(tool))settings.put("chromaKey",false);
                else if("Mask".equals(tool))settings.put("maskShape","none");
                else if("Glow".equals(tool))settings.put("glowStrength",0);
                else settings.put("opacity",1);
                if(applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",settings),revision))dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}});
        });dialog.show();
    }

    private void chooseStudioImage(){
        if(activeProject==null||activeProject.clips.isEmpty()){Toast.makeText(this,"Add timeline clips first",Toast.LENGTH_LONG).show();return;}
        ArrayList<ProjectStore.Asset> images=new ArrayList<>();
        for(ProjectStore.Asset asset:activeProject.assets)if(asset.mime!=null&&asset.mime.startsWith("image/"))images.add(asset);
        if(images.isEmpty()){Toast.makeText(this,"Import an image into this project first",Toast.LENGTH_LONG).show();return;}
        String[] labels=images.stream().map(a->a.name).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Choose an imported overlay image").setItems(labels,(d,index)->editStudioImage(null,images.get(index).id))
                .setNegativeButton("Cancel",null).show();
    }

    private void editStudioImage(StudioLayer existing,String assetId){
        if(activeProject==null)return;
        ProjectStore.Asset asset=activeProject.asset(assetId);
        if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")){Toast.makeText(this,"Imported image is unavailable",Toast.LENGTH_LONG).show();return;}
        final long revision=activeProject.updatedAt;
        final String projectId=activeProject.id;
        final long surfaceGeneration=studioSurfaceGeneration;
        StudioLayer original=existing==null?new StudioLayer():existing.copy();original.type=StudioLayer.IMAGE;original.assetId=assetId;
        if(existing==null){original.startMs=Math.min(studioPlayheadMs,Math.max(0,activeProject.outputDurationMs()-100));
            original.durationMs=Math.min(2500,activeProject.outputDurationMs()-original.startMs);}
        LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));
        content.addView(body("Center X/Y: output coordinates 0..1. Width/height: fraction of output frame (0.01..2). Rotation: degrees. Opacity: 0..1."));
        content.addView(body("Start on timeline (ms)"));EditText start=numberInput(0);start.setText(Long.toString(original.startMs));content.addView(start);
        content.addView(body("Duration (ms)"));EditText duration=numberInput(0);duration.setText(Long.toString(original.durationMs));content.addView(duration);
        String[] names={"x","y","width","height","rotation","opacity"};
        double[] values={original.x,original.y,original.width,original.height,original.rotation,original.opacity};
        EditText[] fields=new EditText[names.length];
        for(int i=0;i<names.length;i++){
            content.addView(body(names[i]));fields[i]=numberInput((float)values[i]);
            // Opening image motion must not quantize its existing static base.
            fields[i].setText(Float.toString((float)values[i]));fields[i].setContentDescription("Overlay "+names[i]);content.addView(fields[i]);
        }
        Button animate=studioButton("Animate overlay",()->{});content.addView(animate);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Image Overlay · "+asset.name).setView(scroll)
                .setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            if(!canApplyStudioImage(projectId,revision,surfaceGeneration))return;
            JSONObject record=original.toJson().put("startMs",Long.parseLong(start.getText().toString()))
                    .put("durationMs",Long.parseLong(duration.getText().toString()));
            for(int i=0;i<names.length;i++)record.put(names[i],Double.parseDouble(fields[i].getText().toString()));
            if(saveStudioLayer(StudioLayer.fromJson(record),revision))dialog.dismiss();
        }catch(Exception error){Toast.makeText(this,"Image overlay not applied: "+error.getMessage(),Toast.LENGTH_LONG).show();}}));dialog.show();
        animate.setOnClickListener(view->{
            try{
                if(!canApplyStudioImage(projectId,revision,surfaceGeneration))return;
                JSONObject record=original.toJson().put("startMs",Long.parseLong(start.getText().toString()))
                        .put("durationMs",Long.parseLong(duration.getText().toString()));
                for(int i=0;i<names.length;i++)record.put(names[i],Double.parseDouble(fields[i].getText().toString()));
                StudioLayer draft=StudioLayer.fromJson(record);
                showStudioImageMotion(draft,projectId,revision,surfaceGeneration,dialog::dismiss);
            }catch(Exception error){Toast.makeText(this,"Cannot animate image overlay: "+error.getMessage(),Toast.LENGTH_LONG).show();}
        });
    }

    private void showStudioImageMotion(StudioLayer draft,String projectId,long revision,long surfaceGeneration,Runnable afterSaved)throws Exception{
        if(studioImageMotionDialog!=null)studioImageMotionDialog.dismiss();
        AlertDialog motionDialog=StudioImageMotionDialog.show(this,draft,
                Math.max(0,Math.min(draft.durationMs,studioPlayheadMs-draft.startMs)),candidate->{
                    if(!canApplyStudioImage(projectId,revision,surfaceGeneration))return false;
                    if(!saveStudioLayer(candidate,revision))return false;
                    afterSaved.run();return true;
                });
        studioImageMotionDialog=motionDialog;
        motionDialog.setOnDismissListener(ignored->{if(studioImageMotionDialog==motionDialog)studioImageMotionDialog=null;});
    }

    private boolean canApplyStudioImage(String projectId,long revision,long surfaceGeneration){
        if(isFinishing()||isDestroyed()||activeProject==null||!projectId.equals(activeProject.id)
                ||activeProject.updatedAt!=revision||studioSurfaceGeneration!=surfaceGeneration
                ||!"editor".equals(currentScreen)){
            Toast.makeText(this,"The editing view changed. Reopen this image overlay before applying its draft.",Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private long secondsToMs(EditText field){
        double seconds=Double.parseDouble(field.getText().toString());
        if(!Double.isFinite(seconds)||seconds<0||seconds>86400)throw new IllegalArgumentException("Use a finite time in 0..86400 seconds");
        return Math.round(seconds*1000);
    }

    private boolean saveStudioLayer(StudioLayer replacement,long expectedRevision){
        ArrayList<StudioLayer> layers=new ArrayList<>(activeProject.studioLayers);boolean found=false;
        for(int i=0;i<layers.size();i++)if(layers.get(i).id.equals(replacement.id)){layers.set(i,replacement);found=true;break;}
        if(!found)layers.add(replacement);
        return applyStudioLayerList(layers,expectedRevision);
    }

    private boolean applyStudioLayerList(List<StudioLayer> layers,long expectedRevision){
        try{
            ProjectStore.Project before=ProjectStore.Project.fromJson(activeProject.toJson());
            JSONObject result=StudioLayerContract.apply(store,new JSONObject().put("projectId",activeProject.id)
                    .put("expectedUpdatedAt",expectedRevision).put("layers",StudioLayerContract.toJson(layers)));
            ProjectStore.Project saved=store.get(result.getString("projectId"));if(saved==null)throw new IllegalStateException("Saved project is unavailable");
            if(saved.updatedAt==result.getLong("updatedAt"))editHistory.checkpoint(before);else editHistory.reset(null);
            String selectedId=selectedClip==null?"":selectedClip.id;activeProject=saved;selectedClip=null;
            for(ProjectStore.Clip clip:saved.clips)if(selectedId.equals(clip.id)){selectedClip=clip;break;}
            syncProtocolState();showEditor();return true;
        }catch(Exception error){Toast.makeText(this,"Layer not applied: "+error.getMessage(),Toast.LENGTH_LONG).show();return false;}
    }

    private void updateStudioCaptionAt(long globalTimeMs){
        if(studioCaptionPreview==null||activeProject==null)return;
        if(isStudioExternalPreviewMode()||player().isEditedPreview()){
            studioCaptionPreview.setText("");studioCaptionPreview.setVisibility(View.GONE);return;
        }
        List<StudioLayer> active=StudioTrackLayout.activeAt(
                activeProject.studioLayers,StudioLayer.TITLE,globalTimeMs);
        if(active.isEmpty()){
            studioCaptionPreview.setText("");
            studioCaptionPreview.setVisibility(View.GONE);
            return;
        }
        android.text.SpannableStringBuilder copy=new android.text.SpannableStringBuilder();
        for(int i=0;i<Math.min(3,active.size());i++){
            if(i>0)copy.append("\n");
            copy.append(new StudioTimedTitleOverlay(active.get(i)).getText(globalTimeMs*1000L));
        }
        StudioLayer first=active.get(0);
        studioCaptionPreview.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.NORMAL);
        studioCaptionPreview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,first.fontSize);
        studioCaptionPreview.setText(copy);
        studioCaptionPreview.setTextColor(first.color);
        studioCaptionPreview.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        int vertical=first.position==0?Gravity.TOP:
                (first.position==2?Gravity.BOTTOM:Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams place=new FrameLayout.LayoutParams(
                -1,-2,Gravity.CENTER_HORIZONTAL|vertical);
        place.leftMargin=dp(18);place.rightMargin=dp(18);
        place.topMargin=dp(14);place.bottomMargin=dp(20);
        studioCaptionPreview.setLayoutParams(place);
        studioCaptionPreview.setVisibility(View.VISIBLE);
        studioCaptionPreview.bringToFront();
    }

    private void startStudioCaptionClock(ProjectStore.Clip clip,long globalStartMs,
                                         long initialOffsetMs){
        if(studioCaptionClock!=null)
            ui.removeCallbacks(studioCaptionClock);
        if(clip==null||activeProject==null)return;
        final String clipId=clip.id;
        final long clipLength=clip.outputDurationMs();
        final long systemStart=android.os.SystemClock.elapsedRealtime();
        studioCaptionClock=new Runnable(){
            @Override public void run(){
                if(!timelinePreviewRunning||activeProject==null||selectedClip==null||
                        !clipId.equals(selectedClip.id))return;
                ProjectStore.Asset source=activeProject.asset(clip.assetId);
                long elapsed;
                if(source!=null&&source.mime!=null&&source.mime.startsWith("video/")){
                    elapsed=(long)((player().currentPositionMs()-clip.inMs)/
                            Math.max(.1f,clip.speed));
                }else elapsed=initialOffsetMs+
                        android.os.SystemClock.elapsedRealtime()-systemStart;
                elapsed=Math.max(0,Math.min(clipLength,elapsed));
                long clock=globalStartMs+elapsed;
                updateStudioCaptionAt(clock);
                if(studioAudioPreview!=null)
                    studioAudioPreview.sync(activeProject,clock,true);
                if(studioTimeLabel!=null)
                    studioTimeLabel.setText(time(clock)+" / "+
                            time(activeProject.outputDurationMs()));
                ui.postDelayed(this,120L);
            }
        };
        ui.post(studioCaptionClock);
    }

    private void studioSpeedDialog(){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        int speed=(int)Math.round(selectedClip.speed*100);
        studioSliderDialog("Playback Speed",25,300,Math.max(25,Math.min(300,speed)),
                "% speed",value->{
                    try{applyStudioPrecision(new JSONObject().put("clipId",clipId)
                            .put("set",new JSONObject().put("speed",value/100.0)),revision);}
                    catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                });
    }
    private void studioVolumeDialog(){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        final String clipId=selectedClip.id,projectId=activeProject.id;final long revision=activeProject.updatedAt;
        final long duration=selectedClip.outputDurationMs();
        final long originalFadeInMs=selectedClip.effects.optLong("audioFadeInMs",0),originalFadeOutMs=selectedClip.effects.optLong("audioFadeOutMs",0);
        LinearLayout form=column();form.setPadding(dp(18),dp(5),dp(18),dp(8));
        TextView gain=body("Volume "+Math.round(selectedClip.volume*100)+"%");form.addView(gain);
        SeekBar volume=new SeekBar(this);volume.setMax(200);volume.setProgress(Math.round(selectedClip.volume*100));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean user){gain.setText("Volume "+value+"%");}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });form.addView(volume,margins(-1,dp(45),0,0,0,0));
        form.addView(body("Fade in (seconds, 0 disables)"));
        EditText fadeIn=numberInput(0);fadeIn.setText(String.format(Locale.US,"%.3f",originalFadeInMs/1000.0));form.addView(fadeIn);
        form.addView(body("Fade out (seconds, 0 disables)"));
        EditText fadeOut=numberInput(0);fadeOut.setText(String.format(Locale.US,"%.3f",originalFadeOutMs/1000.0));form.addView(fadeOut);
        TextView balanceText=body("Stereo balance");form.addView(balanceText);
        SeekBar balance=new SeekBar(this);balance.setMax(200);balance.setContentDescription("Stereo balance");
        balance.setProgress(100+(int)Math.round(selectedClip.effects.optDouble("audioPan",0)*100));
        balance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){
                int pan=value-100;balanceText.setText(pan==0?"Stereo balance: Center":"Stereo balance: "+Math.abs(pan)+"% "+(pan<0?"Left":"Right"));
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        int initialPan=balance.getProgress()-100;balanceText.setText(initialPan==0?"Stereo balance: Center":"Stereo balance: "+Math.abs(initialPan)+"% "+(initialPan<0?"Left":"Right"));
        form.addView(balance,margins(-1,dp(48),0,0,0,0));
        form.addView(studioButton("Center balance",()->balance.setProgress(100)));
        android.widget.CheckBox mono=new android.widget.CheckBox(this);mono.setText("Fold stereo L/R to mono");
        mono.setContentDescription("Fold stereo L/R to mono");mono.setTextColor(C_TEXT);mono.setChecked(selectedClip.effects.optBoolean("audioMono",false));form.addView(mono);
        form.addView(body("Balance and mono affect the left/right pair. Single-channel sources and additional surround channels keep their positions."));
        form.addView(body("Clip duration "+StudioEditCursor.timecode(duration)+". Fades use edited playback time and appear in preview and MP4 export."));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Clip Volume & Fades").setView(scroll)
                .setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(isFinishing()||isDestroyed()){dialog.dismiss();return;}
            if(activeProject==null||!projectId.equals(activeProject.id)){Toast.makeText(this,"The project changed. Open Volume again.",Toast.LENGTH_LONG).show();return;}
            try{
                long in=secondsToMs(fadeIn),out=secondsToMs(fadeOut);
                JSONObject settings=StudioVolumeEdits.settings(volume.getProgress(),duration,originalFadeInMs,originalFadeOutMs,in,out,balance.getProgress()-100,mono.isChecked());
                if(applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",settings),revision))dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }
    private void studioBlurDialog(){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        int amount=(int)Math.round(selectedClip.effects.optDouble("blur",0)*10);
        studioSliderDialog("Gaussian Blur",0,180,Math.max(0,Math.min(180,amount)),
                "tenths",value->{
                    try{applyStudioPrecision(new JSONObject().put("clipId",clipId)
                            .put("set",new JSONObject().put("blur",value/10.0)),revision);}
                    catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                });
    }

    private void studioSliderDialog(String title,int min,int max,int start,
                                    String units,java.util.function.IntConsumer onApply){
        LinearLayout wrap=column();
        wrap.setPadding(dp(19),dp(8),dp(19),dp(9));
        TextView amount=title(start+" "+units,19);
        wrap.addView(amount);
        SeekBar slider=new SeekBar(this);
        slider.setMax(max-min);
        slider.setProgress(start-min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int value,boolean user){
                amount.setText((value+min)+" "+units);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        wrap.addView(slider);
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage("Changes use the same native composition in edited preview and MP4 export.")
                .setView(wrap)
                .setPositiveButton("Apply",(d,w)->onApply.accept(slider.getProgress()+min))
                .setNegativeButton("Cancel",null).show();
    }

    private void studioPresetDialog(String title,String key,String[] presets){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        String current="transition".equals(key)?selectedClip.transition:
                selectedClip.effects.optString(key,"none");
        int selected=java.util.Arrays.asList(presets).indexOf(current);
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(presets,Math.max(0,selected),(dialog,index)->{
                    try{if(applyStudioPrecision(new JSONObject().put("clipId",clipId)
                            .put("set",new JSONObject().put(key,presets[index])),revision))dialog.dismiss();}
                    catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                })
                .setNegativeButton("Cancel",null).show();
    }

    private void deleteStudioClip(){
        if(activeProject==null||selectedClip==null)return;
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        new AlertDialog.Builder(this).setTitle("Remove timeline clip?")
                .setMessage("This removes the selected clip from the edit, but keeps its imported media in VideoStudio.")
                .setPositiveButton("Remove",(d,w)->{try{
                    applyStudioTimeline(new JSONObject().put("action","delete").put("clipId",clipId),revision);
                }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}})
                .setNegativeButton("Cancel",null).show();
    }
    private void moveStudioClipTo(String clipId,int targetIndex){
        if(activeProject==null||clipId==null||targetIndex<0||targetIndex>=activeProject.clips.size())return;
        try{applyStudioTimeline(new JSONObject().put("action","move").put("clipId",clipId).put("targetIndex",targetIndex),activeProject.updatedAt);}
        catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void moveStudioClip(int distance){
        if(activeProject==null||selectedClip==null)return;
        int at=activeProject.clips.indexOf(selectedClip);
        if(at<0||at+distance<0||at+distance>=activeProject.clips.size())return;
        moveStudioClipTo(selectedClip.id,at+distance);
    }

    private boolean applyStudioTimeline(JSONObject operation,long expectedRevision){
        if(activeProject==null)return false;
        try{
            ProjectStore.Project before=ProjectStore.Project.fromJson(activeProject.toJson());
            operation.put("projectId",activeProject.id).put("expectedUpdatedAt",expectedRevision);
            JSONObject result=StudioPrecisionEdits.applyTimeline(store,operation);
            ProjectStore.Project saved=store.get(result.getString("projectId"));if(saved==null)throw new IllegalStateException("Saved project is unavailable");
            if(saved.updatedAt==result.getLong("updatedAt"))editHistory.checkpoint(before);else editHistory.reset(null);
            activeProject=saved;selectedClip=null;String id=result.getString("selectedClipId");
            for(ProjectStore.Clip clip:saved.clips)if(id.equals(clip.id)){selectedClip=clip;break;}
            if("split".equals(operation.optString("action"))){
                StudioTimelineClock.Position position=StudioTimelineClock.locate(saved.clips,studioPlayheadMs);
                if(position.clipIndex>=0)selectedClip=saved.clips.get(position.clipIndex);
            }else studioPlayheadMs=selectedClip==null?0:StudioTimelineEditOps.startOf(saved.clips,selectedClip.id);
            studioTimelinePage=selectedClip==null?0:saved.clips.indexOf(selectedClip)/20;
            syncProtocolState();showEditor();return true;
        }catch(Exception error){Toast.makeText(this,"Timeline edit not applied: "+error.getMessage(),Toast.LENGTH_LONG).show();return false;}
    }

    private void restoreStudioHistory(boolean redo){
        if(activeProject==null)return;
        final String projectId=activeProject.id;final long revision=activeProject.updatedAt;
        final String previous=selectedClip==null?"":selectedClip.id;
        try{
            ProjectStore.Project restored=store.guardedProject(projectId,revision,current->{
                ProjectStore.Project next=redo?editHistory.redo(current):editHistory.undo(current);
                if(next!=null)store.save(next);return next;
            });
            if(restored==null)return;
            activeProject=restored;selectedClip=null;
            for(ProjectStore.Clip clip:restored.clips)if(previous.equals(clip.id)){selectedClip=clip;break;}
            if(selectedClip==null&&!restored.clips.isEmpty())selectedClip=restored.clips.get(0);
            syncProtocolState();showEditor();
        }catch(Exception error){
            Toast.makeText(this,"History not restored: "+error.getMessage(),Toast.LENGTH_LONG).show();
            ProjectStore.Project latest=store.get(projectId);if(latest!=null)activeProject=latest;
            editHistory.reset(null);showEditor();
        }
    }

    private void findStudioTool(){
        ScrollView scroll=baseScroll();LinearLayout holder=column();
        holder.setPadding(dp(16),dp(8),dp(16),dp(12));scroll.addView(holder);
        addStudioSearch(holder);
        studioToolDialog=new AlertDialog.Builder(this).setTitle("Find a tool").setView(scroll)
                .setPositiveButton("Close",null).create();
        studioToolDialog.setOnDismissListener(dialog->studioToolDialog=null);
        studioToolDialog.show();
    }

    private void chooseNativeJobToCancel(){
        JSONObject work=NativeWorkMonitor.snapshot(this);
        JSONArray all=work.optJSONArray("jobs");
        ArrayList<String> labels=new ArrayList<>();
        ArrayList<String> ids=new ArrayList<>();
        if(all!=null)for(int i=0;i<all.length();i++){
            JSONObject job=all.optJSONObject(i);
            if(job==null||JobManager.isTerminal(job.optString("state","")))continue;
            String id=job.optString("id","");
            if(id.isEmpty())continue;
            ids.add(id);
            labels.add(job.optString("name","Native job")+"  ·  "+
                    job.optString("state","").replace('_',' ')+"  "+
                    job.optInt("progress",0)+"%");
        }
        if(labels.isEmpty()){
            Toast.makeText(this,"No active native job needs cancellation",
                    Toast.LENGTH_SHORT).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Cancel one native job")
                .setItems(labels.toArray(new String[0]),(d,which)->{
                    String selected=ids.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("Stop only this job?")
                            .setMessage(labels.get(which)+"\nOther VideoStudio jobs and project files will be preserved.")
                            .setPositiveButton("Stop Job",(confirm,index)->{
                                try{
                                    Intent command=new Intent(this,ControlService.class)
                                            .setAction(ControlService.ACTION_CANCEL_JOB)
                                            .putExtra("jobId",selected);
                                    startForegroundService(command);
                                    Toast.makeText(this,"Stopping selected native job",
                                            Toast.LENGTH_SHORT).show();
                                }catch(Exception error){
                                    Toast.makeText(this,"Could not request cancellation",
                                            Toast.LENGTH_LONG).show();
                                }
                            }).setNegativeButton("Keep Running",null).show();
                }).setNegativeButton("Back",null).show();
    }

    private void showStudioJobDetails(){
        JSONObject work=NativeWorkMonitor.snapshot(this);
        StringBuilder text=new StringBuilder();
        text.append(work.optString("nativeServiceOnline","false").equals("true")?
                "Native Agent connected\n":"Native Agent reconnecting\n");
        text.append(work.optString("thermalLabel","Unknown thermal reading"));
        text.append("\nThermal source: Android system estimate, not touch temperature.");
        if(work.optBoolean("thermalSafetyHold",false))
            text.append("\nGPU/encoding is protected until Android cools; Drive transfer can continue at severe heat but pauses at critical.");
        text.append("\nActive: ").append(work.optInt("active",0));
        text.append("  ·  Waiting: ").append(work.optInt("waiting",0));
        text.append("\n\n");
        JSONArray tasks=work.optJSONArray("jobs");
        if(tasks!=null)for(int i=0;i<Math.min(tasks.length(),12);i++){
            JSONObject j=tasks.optJSONObject(i);
            if(j==null)continue;
            text.append(j.optString("name","Job")).append("\n  ")
                    .append(j.optString("state","unknown").replace('_',' '))
                    .append(" • ").append(j.optInt("progress",0)).append("%")
                    .append("\n  ").append(j.optString("detail","")).append("\n\n");
        }
        new AlertDialog.Builder(this).setTitle("Native VideoStudio Work")
                .setMessage(text.toString())
                .setPositiveButton("Refresh",(d,w)->showStudioJobDetails())
                .setNeutralButton("Stop One Job",(d,w)->chooseNativeJobToCancel())
                .setNegativeButton("Close",null).show();
    }

    private void showLegacyEditor() {
        if (activeProject == null) {
            createProject("Untitled Project");
        }
        ScrollView scroll = baseScroll();
        LinearLayout box = column();
        box.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(box);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = title(activeProject.name, 20);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button importButton = compactButton("Import");
        importButton.setOnClickListener(v -> pickMedia());
        top.addView(importButton);
        Button playButton = compactButton("Preview");
        playButton.setOnClickListener(v -> previewTimeline());
        top.addView(playButton);
        Button exportButton = compactButton("Export");
        exportButton.setOnClickListener(v -> queueStudioExport());
        top.addView(exportButton);
        box.addView(top, margins(-1, -2, 0, dp(10), 0, 0));
        Button storyboard=neonButton("Storyboard → Scoped Motion Drafts",C_MAGENTA);
        storyboard.setOnClickListener(v->storyboardStudioDialog());
        box.addView(storyboard,margins(-1,dp(51),0,0,0,dp(8)));
        Button artwork=neonButton("Fighter Storyboard → Saved Program + Contacts",C_MAGENTA);
        artwork.setOnClickListener(v->storyboardArtworkDialog());
        box.addView(artwork,margins(-1,dp(49),0,0,0,dp(8)));
        Button directAnimation = neonButton("Animation Director • Make a Real Sequence", C_CYAN);
        directAnimation.setOnClickListener(v -> animationDirectorDialog());
        box.addView(directAnimation,margins(-1,dp(47),0,0,0,dp(12)));
        Button model3D=neonButton("Real 3D GLB Model • Skinned Motion Preview",C_CYAN);
        model3D.setOnClickListener(v -> open3DModelViewer());
        box.addView(model3D,margins(-1,dp(48),0,0,0,dp(10)));
        Button render3D=neonButton("Export Real 3D Sword Fight • GLB",C_MAGENTA);
        render3D.setOnClickListener(v -> choose3DCombatActors());
        box.addView(render3D,margins(-1,dp(48),0,0,0,dp(10)));
        Button rigEditor=neonButton("Trace Original 2D Fighters + Sword",C_CYAN);
        rigEditor.setOnClickListener(v -> open2DRigEditor());
        box.addView(rigEditor,margins(-1,dp(48),0,0,0,dp(10)));
        Button benchmark=compactButton("True Combat Rig Test");
        benchmark.setOnClickListener(v -> combatRigBenchmarkDialog());
        box.addView(benchmark,margins(-1,dp(44),0,0,0,dp(10)));

        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackground(rounded(Color.BLACK, Color.rgb(37, 51, 83), dp(18)));
        viewer.setMinimumHeight(dp(280));
        player().attach(viewer);
        TextView hint = body(activeProject.assets.isEmpty()
                ? "No source media yet. Import a file, or ask ChatGPT to create a prompt video."
                : "Select a clip below");
        hint.setGravity(Gravity.CENTER);
        if (player().hasMedia()) hint.setVisibility(View.GONE);
        viewer.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        box.addView(viewer, margins(-1, dp(280), 0, 0, 0, 0));

        LinearLayout autonomousStrip = card(false);
        autonomousStrip.setBackground(neonCard());
        JSONArray recentActivity = ActivityLog.recentWork(this, 1);
        JSONObject latestActivity = recentActivity.optJSONObject(0);
        JSONObject recoverySnapshot;
        try {
            recoverySnapshot = new JSONObject(prefs.getString(ExecutionTruthPolicy.LIVE_JOB_PREF_KEY, "{}"));
        } catch (Exception ignored) {
            recoverySnapshot = new JSONObject();
        }
        boolean recoveryMatchesProject = activeProject != null
                && (recoverySnapshot.optString("projectId", "").isEmpty()
                || activeProject.id.equals(recoverySnapshot.optString("projectId", "")));
        String activityTitle = recoveryMatchesProject && !recoverySnapshot.optString("jobId", "").isEmpty()
                ? recoverySnapshot.optString("action", "Autonomous work")
                : latestActivity == null ? "Autonomous work" : latestActivity.optString("action", "Autonomous work");
        String activityDetail = recoveryMatchesProject && !recoverySnapshot.optString("jobId", "").isEmpty()
                ? recoverySnapshot.optString("detail", recoverySnapshot.optString("state", "VideoStudio is working."))
                : latestActivity == null
                ? "Ready for ChatGPT edits while playback remains interactive."
                : latestActivity.optString("detail", "VideoStudio is working.");
        autonomousStrip.addView(title("✦  Autonomous work", 15));
        autonomousStrip.addView(body(activityTitle + "\n" + activityDetail));
        int liveProgressValue = recoveryMatchesProject && recoverySnapshot.has("progress")
                ? recoverySnapshot.optInt("progress", 0)
                : latestActivity != null && latestActivity.has("progress")
                ? latestActivity.optInt("progress", 0)
                : -1;
        if (liveProgressValue >= 0) {
            ProgressBar liveProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            liveProgress.setMax(100);
            liveProgress.setProgress(Math.max(0, Math.min(100, liveProgressValue)));
            autonomousStrip.addView(liveProgress, margins(-1, dp(8), dp(6), 0, 0, 0));
        }
        LinearLayout liveActions = new LinearLayout(this);
        liveActions.setGravity(Gravity.CENTER_VERTICAL);
        PreviewSnapshotStore.Snapshot latestPreview = previewSnapshots.latest(activeProject.id);
        LivePlaybackState playbackState = player().snapshotState();
        if (latestPreview != null && !latestPreview.id.equals(playbackState.snapshotId)) {
            Button playNew = compactButton("Play new result");
            playNew.setOnClickListener(v -> {
                player().setContextIds(latestPreview.id, "");
                player().play(Uri.parse(latestPreview.uri), 0L);
                Toast.makeText(this, "Playing latest " + latestPreview.qualityTier + " result", Toast.LENGTH_SHORT).show();
            });
            liveActions.addView(playNew);
            TextView resultBadge = accent(
                    "  " + latestPreview.sourceType.toUpperCase(Locale.US) + " • " + latestPreview.qualityTier.toUpperCase(Locale.US),
                    C_CYAN
            );
            liveActions.addView(resultBadge);
        }
        Button stopJobs = compactButton("Stop jobs");
        stopJobs.setOnClickListener(v -> {
            try {
                jobs.cancelAll();
                if (activeRenderHandle != null) activeRenderHandle.cancel();
                Intent stop = new Intent(this, ControlService.class);
                stop.setAction(ControlService.ACTION_CANCEL_ALL);
                startForegroundService(stop);
                Toast.makeText(this, "Active VideoStudio jobs cancelled", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Could not stop every job", Toast.LENGTH_SHORT).show();
            }
        });
        liveActions.addView(stopJobs);
        autonomousStrip.addView(liveActions, margins(-1, -2, dp(8), 0, 0, 0));
        box.addView(autonomousStrip, margins(-1, -2, dp(10), dp(2), 0, 0));

        box.addView(section("Media Bin"));
        HorizontalScrollView mediaBin = new HorizontalScrollView(this);
        mediaBin.setHorizontalScrollBarEnabled(false);
        LinearLayout mediaItems = new LinearLayout(this);
        mediaItems.setPadding(0, dp(4), dp(12), dp(8));
        for (ProjectStore.Asset asset : activeProject.assets) {
            LinearLayout mediaCard = card(false);
            mediaCard.setMinimumWidth(dp(205));
            mediaCard.addView(title(asset.name, 13));
            String role = asset.role == null || asset.role.isEmpty() ? "source" : asset.role.replace('_', ' ');
            String meta = (asset.generated ? "GENERATED" : "SOURCE") + " • " + role.toUpperCase(Locale.US);
            mediaCard.addView(accent(meta, asset.generated ? C_CYAN : C_MUTED));
            mediaCard.addView(body((asset.mime == null ? "media" : asset.mime) + (asset.durationMs > 0 ? " • " + time(asset.durationMs) : "")));

            boolean onTimeline = assetOnTimeline(activeProject, asset.id);
            if (asset.mime != null && asset.mime.startsWith("video/")) {
                Button previewAsset = compactButton("Preview");
                previewAsset.setOnClickListener(v -> {
                    try {
                        hint.setVisibility(View.GONE);
                        player().setContextIds("", "");
                        player().play(Uri.parse(ProxyManager.previewUri(activeProject, asset)), 0L);
                    } catch (Exception error) {
                        Toast.makeText(this, "Could not preview media", Toast.LENGTH_SHORT).show();
                    }
                });
                mediaCard.addView(previewAsset, margins(-1, dp(42), dp(8), 0, 0, 0));
            }
            if (!onTimeline && asset.mime != null && (asset.mime.startsWith("video/") || asset.mime.startsWith("image/"))) {
                Button add = compactButton("+ Timeline");
                add.setOnClickListener(v -> {
                    ProjectStore.Project latest = store.get(activeProject.id);
                    if (latest != null && store.appendAssetToTimeline(latest, asset.id)) {
                        activeProject = store.get(latest.id);
                        selectedClip = activeProject.clips.isEmpty() ? null : activeProject.clips.get(activeProject.clips.size() - 1);
                        syncProtocolState();
                        showEditor();
                        Toast.makeText(this, "Added to timeline", Toast.LENGTH_SHORT).show();
                    }
                });
                mediaCard.addView(add, margins(-1, dp(42), dp(6), 0, 0, 0));
            }
            mediaItems.addView(mediaCard, margins(dp(205), -2, 0, dp(8), dp(8), 0));
        }
        if (activeProject.assets.isEmpty()) mediaItems.addView(body(
                "No media in this project yet. Import source media, or run Prompt Video to generate a new project."
        ));
        mediaBin.addView(mediaItems);
        box.addView(mediaBin);

        box.addView(section("Timeline"));
        HorizontalScrollView timeline = new HorizontalScrollView(this);
        timeline.setHorizontalScrollBarEnabled(false);
        LinearLayout clips = new LinearLayout(this);
        clips.setPadding(0, dp(4), dp(12), dp(8));
        for (int i = 0; i < activeProject.clips.size(); i++) {
            final int index = i;
            ProjectStore.Clip clip = activeProject.clips.get(i);
            ProjectStore.Asset asset = activeProject.asset(clip.assetId);
            LinearLayout chip = card(false);
            chip.setMinimumWidth(dp(170));
            boolean selected = selectedClip != null && selectedClip.id.equals(clip.id);
            chip.setBackground(rounded(selected ? Color.rgb(38, 48, 89) : C_CARD_2, selected ? C_CYAN : Color.rgb(35, 48, 72), dp(14)));
            chip.addView(title((index + 1) + "  " + (asset == null ? "Clip" : asset.name), 13));
            chip.addView(body(time(clip.outputDurationMs()) + " • " + trimFloat(clip.speed) + "× • " + clip.transition));
            if (clip.effects != null && clip.effects.length() > 0) {
                chip.addView(accent("✦ " + effectSummary(clip.effects), C_CYAN));
            }
            chip.setOnClickListener(v -> {
                selectedClip = clip;
                previewSelectedClip();
                showEditor();
            });
            clips.addView(chip, margins(dp(170), -2, 0, dp(8), dp(8), 0));
        }
        if (activeProject.clips.isEmpty()) {
            clips.addView(body("Timeline is empty."));
        }
        timeline.addView(clips);
        box.addView(timeline);

        box.addView(section("Creator Tools"));
        String[][] tools = {
                {"✂", "Split"}, {"↔", "Trim"}, {"½", "Slow Motion"}, {"⌁", "Speed Ramp"},
                {"◆", "Green Screen"}, {"⇄", "Transitions"}, {"↗", "Motion"}, {"✦", "Effects"},
                {"◉", "Colour"}, {"T", "Text"}, {"Aa", "Fonts"}, {"♫", "Volume"},
                {"▣", "Reframe"}, {"◐", "Mask"}, {"▥", "Overlay"}, {"≈", "Motion Blur"},
                {"❄", "Freeze"}, {"⧉", "Duplicate"}, {"↺", "Reverse"}, {"↯", "Shake"},
                {"◌", "Blur"}, {"☼", "Glow"}, {"CC", "Captions"}, {"⌁", "Audio Duck"}
        };
        LinearLayout toolGrid = column();
        for (int i = 0; i < tools.length; i += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 4 && i + j < tools.length; j++) {
                String icon = tools[i + j][0];
                String label = tools[i + j][1];
                row.addView(actionTile(icon, label, j % 2 == 0 ? C_BLUE : C_PURPLE, () -> applyTool(label)), weightWithMargin());
            }
            toolGrid.addView(row, margins(-1, -2, 0, dp(8), 0, 0));
        }
        box.addView(toolGrid);

        LinearLayout status = card(false);
        status.addView(title("Native creator pipeline", 16));
        status.addView(body("Cuts, speed, colour, blur, transforms, motion and prompt scenes now feed the Media3 native exporter. Advanced chroma, masks and creator transitions remain in the timeline model for progressive renderer coverage."));
        box.addView(status, margins(-1, -2, dp(6), 0, 0, 0));

        setScreen(scroll, "editor");
        scheduleEditorRefresh(activeProject == null ? "" : activeProject.id,
                activeProject == null ? 0 : activeProject.updatedAt);
    }

    private View autonomousEditorCard() {
        LinearLayout card = card(false);
        card.addView(title("Autonomous work", 16));

        JSONObject job = latestPersistedJob();
        if (job == null) {
            card.addView(body("Ready • playback and scrubbing stay available while ChatGPT edits in the background."));
        } else {
            String state = job.optString("state", "working");
            String stage = job.optString("stage", "");
            int progressValue = Math.max(0, Math.min(100, job.optInt("progress", 0)));
            String detail = job.optString("detail", "");
            card.addView(accent(state.replace('_', ' ').toUpperCase(Locale.US)
                    + (stage.isEmpty() ? "" : " • " + stage), C_CYAN));
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(progressValue);
            card.addView(progress, margins(-1, dp(10), dp(8), 0, 0, 0));
            card.addView(body(progressValue + "% • " + (detail.isEmpty() ? "Background work is active" : detail)));

            if (!isTerminalJobState(state)) {
                Button stop = compactButton("Stop autonomous work");
                stop.setOnClickListener(v -> {
                    Intent intent = new Intent(this, ControlService.class);
                    intent.setAction(ControlService.ACTION_CANCEL_ALL);
                    startForegroundService(intent);
                    Toast.makeText(this, "Stopping active VideoStudio jobs", Toast.LENGTH_SHORT).show();
                });
                card.addView(stop, margins(-1, dp(42), dp(8), 0, 0, 0));
            }
        }

        PreviewSnapshotStore.Snapshot latest = activeProject == null
                ? null
                : previewSnapshots.latest(activeProject.id);
        if (latest != null) {
            LivePlaybackState playing = livePlayer == null ? null : player().snapshotState();
            boolean alreadyPlaying = playing != null
                    && (latest.id.equals(playing.snapshotId) || latest.uri.equals(playing.mediaUri));
            card.addView(accent(
                    "PREVIEW • " + latest.sourceType.toUpperCase(Locale.US)
                            + (latest.qualityTier.isEmpty() ? "" : " • " + latest.qualityTier.toUpperCase(Locale.US)),
                    C_MUTED
            ));
            if (!alreadyPlaying) {
                Button playNew = neonButton("Play new result", C_CYAN);
                playNew.setTextColor(Color.BLACK);
                playNew.setOnClickListener(v -> {
                    player().setContextIds(latest.id, "");
                    player().play(Uri.parse(latest.uri), 0L);
                    showEditor();
                });
                card.addView(playNew, margins(-1, dp(46), dp(8), 0, 0, 0));
            }
        }
        return card;
    }

    private JSONObject latestPersistedJob() {
        try {
            JSONArray array = new JSONArray(prefs.getString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY, "[]"));
            JSONObject fallback = null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                if (fallback == null) fallback = item;
                if (!isTerminalJobState(item.optString("state", ""))) return item;
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isTerminalJobState(String state) {
        return "completed".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }

    private boolean assetOnTimeline(ProjectStore.Project project, String assetId) {
        if (project == null || assetId == null) return false;
        for (ProjectStore.Clip clip : project.clips) {
            if (assetId.equals(clip.assetId)) return true;
        }
        return false;
    }

    private void scheduleEditorRefresh(String projectId, long knownUpdatedAt) {
        if (projectId == null || projectId.isEmpty()) return;
        activityRefresh = () -> {
            if (!"editor".equals(currentScreen)) return;

            ProjectStore.Project storeActive = store.active();
            boolean currentEmpty = activeProject == null
                    || (activeProject.assets.isEmpty() && activeProject.clips.isEmpty());
            if (storeActive != null && ExecutionTruthPolicy.shouldAdoptStoreActive(
                    currentEmpty,
                    activeProject == null ? "" : activeProject.id,
                    storeActive.id)) {
                activeProject = storeActive;
                selectedClip = storeActive.clips.isEmpty() ? null : storeActive.clips.get(0);
                showEditor();
                return;
            }

            ProjectStore.Project latest = store.get(projectId);
            if (latest != null && latest.updatedAt != knownUpdatedAt) {
                String selectedId = selectedClip == null ? "" : selectedClip.id;
                activeProject = latest;
                selectedClip = null;
                for (ProjectStore.Clip clip : latest.clips) {
                    if (selectedId.equals(clip.id)) {
                        selectedClip = clip;
                        break;
                    }
                }
                if (selectedClip == null && !latest.clips.isEmpty()) selectedClip = latest.clips.get(0);
                showEditor();
                return;
            }
            ui.postDelayed(activityRefresh, 1500);
        };
        ui.postDelayed(activityRefresh, 1500);
    }

    private void choose3DCombatActors() {
        if(activeProject==null) {
            Toast.makeText(this,"Open an imported VideoStudio project first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final ArrayList<ProjectStore.Asset> models=new ArrayList<>();
        for(ProjectStore.Asset a:activeProject.assets){
            if("model/gltf-binary".equals(a.mime))models.add(a);
        }
        if(models.size()<2){
            new AlertDialog.Builder(this)
                    .setTitle("Two real 3D fighters required")
                    .setMessage("Import TWO rigged GLB models with their own animation clips "+
                            "using the VideoStudio 3D Model viewer. One screenshot cannot "+
                            "supply hidden 3D surfaces. No Gallery search is performed.")
                    .setPositiveButton("Import GLB",(d,w)->open3DModelViewer())
                    .setNegativeButton("Later",null).show();
            return;
        }
        final String projectId=activeProject.id;
        String[] names=new String[models.size()];
        for(int i=0;i<models.size();i++)names[i]=models.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Choose the BLACK 3D fighter")
                .setItems(names,(dialog,blackIndex)->{
                    final ProjectStore.Asset black=models.get(blackIndex);
                    final ArrayList<ProjectStore.Asset> remaining=new ArrayList<>(models);
                    remaining.remove(blackIndex);
                    String[] options=new String[remaining.size()];
                    for(int j=0;j<remaining.size();j++)options[j]=remaining.get(j).name;
                    new AlertDialog.Builder(this)
                            .setTitle("Choose the WHITE 3D fighter")
                            .setItems(options,(whiteDialog,whiteIndex)->{
                                ProjectStore.Asset white=remaining.get(whiteIndex);
                                new AlertDialog.Builder(this)
                                        .setTitle("Render actual 3D combat")
                                        .setMessage("VideoStudio will sample both GLB skeletal "+
                                                "animations into new 3D frames, position the "+
                                                "two independent models on one combat stage, "+
                                                "and render a native 24 FPS MP4. The models "+
                                                "must contain genuine animation clips. This "+
                                                "development mode does not simulate precise "+
                                                "blade-mesh collision or reconstruct 3D from a photo.")
                                        .setPositiveButton("Generate + Export",(confirm,which)->{
                                            Intent job=new Intent(this,ControlService.class)
                                                    .setAction(ControlService.ACTION_LOCAL_GLTF_COMBAT)
                                                    .putExtra("projectId",projectId)
                                                    .putExtra("blackAssetId",black.id)
                                                    .putExtra("whiteAssetId",white.id)
                                                    .putExtra("durationSeconds",3.2)
                                                    .putExtra("fps",24)
                                                    .putExtra("width",320)
                                                    .putExtra("height",576);
                                            startForegroundService(job);
                                            Toast.makeText(this,"3D render queued on the native engine",
                                                    Toast.LENGTH_LONG).show();
                                            showActivity();
                                        }).setNegativeButton("Cancel",null).show();
                            }).setNegativeButton("Cancel",null).show();
                }).setNegativeButton("Cancel",null).show();
    }

    private void open3DModelViewer(){
        if(activeProject==null){
            Toast.makeText(this,"Open a VideoStudio project first",Toast.LENGTH_SHORT).show();
            return;
        }
        Intent viewer=new Intent(this,FilamentGlbActivity.class);
        viewer.putExtra("projectId",activeProject.id);
        startActivity(viewer);
    }

    private void open2DRigEditor() {
        if(activeProject==null){
            Toast.makeText(this,"Open an imported image project first",Toast.LENGTH_LONG).show();
            return;
        }
        String imageId="";
        if(selectedClip!=null){
            ProjectStore.Asset selected=activeProject.asset(selectedClip.assetId);
            if(selected!=null&&selected.mime!=null&&selected.mime.startsWith("image/"))
                imageId=selected.id;
        }
        if(imageId.isEmpty()){
            for(ProjectStore.Asset a:activeProject.assets){
                if(a.mime!=null&&a.mime.startsWith("image/")){imageId=a.id;break;}
            }
        }
        if(imageId.isEmpty()){
            Toast.makeText(this,"Import an image using VideoStudio first",Toast.LENGTH_LONG).show();
            return;
        }
        Intent editor=new Intent(this,RigEditorActivity.class);
        editor.putExtra("projectId",activeProject.id);
        editor.putExtra("assetId",imageId);
        startActivity(editor);
    }

    /**
     * First source-accurate animation milestone. The owner marks BOTH
     * illustrated fighters (not visible 3D skeletons) in each of TWO
     * adjacent storyboard images using the touch-first Rig Builder.
     *
     * Only already-imported project assets are eligible. Missing outlines,
     * frame ordering and sword tips block the job rather than producing
     * another incorrect stickman or background zoom.
     */
    /**
     * Multi-genre creative storyboarding is an actual native rendering
     * workflow, not an inert design button or generic procedural fighter.
     * Project files are already VideoStudio-owned. The selected original
     * remains read-only while frames/MP4 go to an independent project.
     */
    private void storyboardStudioDialog(){
        if(activeProject==null||activeProject.clips.size()<2){
            Toast.makeText(this,"Open a VideoStudio project with two or more imported images",
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout panel=column();
        panel.setPadding(dp(18),dp(8),dp(18),dp(8));
        panel.addView(body("Choose the scope explicitly. Automatic/portrait options create source-deformation drafts; environmental motion moves the scene/camera. Cinematic fighters require an authored saved program, exact actor/sword profiles, contacts and choreography."));
        panel.addView(section("Story genre"));
        android.widget.Spinner genre=new android.widget.Spinner(this);
        String[] genres={"Auto / Mixed","Action","Comic / Manga","Portrait",
                "Product","Landscape","Explainer","Typography","Abstract"};
        String[] values={"mixed","action","comic","portrait","product",
                "landscape","explainer","typography","abstract"};
        android.widget.ArrayAdapter<String> genreAdapter=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,genres);
        genreAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        genre.setAdapter(genreAdapter);
        panel.addView(genre,new LinearLayout.LayoutParams(-1,dp(49)));
        panel.addView(section("Character / object extraction"));
        android.widget.Spinner backend=new android.widget.Spinner(this);
        String[] choices={"Source deformation draft · automatic layers",
                "Source deformation draft · human portraits",
                "Cinematic fighters · open saved program editor",
                "Environmental / camera motion · non-action"};
        String[] backends={"AUTO_LAYERS","PERSON_SEGMENTATION",
                "SOURCE_RIG","CAMERA_PARALLAX"};
        android.widget.ArrayAdapter<String> engines=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,choices);
        engines.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backend.setAdapter(engines);
        panel.addView(backend,new LinearLayout.LayoutParams(-1,dp(50)));
        panel.addView(body("Draft separation/deformation and camera motion do not certify articulated fighting or cinematic quality. The saved-program editor supplies the explicit action annotations."));
        panel.addView(section("Scene idea / optional direction"));
        EditText prompt=new EditText(this);
        prompt.setHint("e.g. chase, dramatic leap, wind, reveal");
        prompt.setSingleLine(false);
        prompt.setMinLines(1);prompt.setMaxLines(2);
        prompt.setTextColor(C_TEXT);prompt.setHintTextColor(C_MUTED);
        panel.addView(prompt,new LinearLayout.LayoutParams(-1,dp(65)));
        panel.addView(section("Action detail"));
        android.widget.Spinner samples=new android.widget.Spinner(this);
        String[] quality={"6 motion samples / pair (fast test)",
                "8 motion samples / pair (balanced)",
                "12 motion samples / pair (smoother)",
                "16 motion samples / pair (slower render)"};
        final int[] stepCounts={6,8,12,16};
        android.widget.ArrayAdapter<String> sampleList=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,quality);
        sampleList.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        samples.setAdapter(sampleList);
        samples.setSelection(1);
        panel.addView(samples,new LinearLayout.LayoutParams(-1,dp(50)));
        android.widget.CheckBox full=new android.widget.CheckBox(this);
        full.setText("Animate entire multi-batch story (otherwise first batch)");
        full.setTextColor(C_TEXT);
        panel.addView(full);
        android.widget.CheckBox audio=new android.widget.CheckBox(this);
        audio.setText("Add synchronized native action sounds (offline)");
        audio.setTextColor(C_TEXT);audio.setChecked(true);
        panel.addView(audio);
        panel.addView(body("Native output: portrait 9:16, 24 FPS, up to "+
                "20 contiguous original keyframes per batch. Original project "+
                "is never overwritten. Full sequence needs trackable scenes "+
                "or complete source object masks."));
        new AlertDialog.Builder(this)
                .setTitle("Storyboard motion scope")
                .setView(panel)
                .setNeutralButton("Analyze Source",(dlg,which)->
                        submitStoryboardStudio(genre,values,backend,backends,
                                prompt,samples,stepCounts,full,audio,true))
                .setPositiveButton("Render selected scope",(dlg,which)->
                        submitStoryboardStudio(genre,values,backend,backends,
                                prompt,samples,stepCounts,full,audio,false))
                .setNegativeButton("Cancel",null).show();
    }

    private void submitStoryboardStudio(android.widget.Spinner genre,
            String[] values,android.widget.Spinner backend,String[] modes,
            EditText direction,android.widget.Spinner samples,int[] steps,
            android.widget.CheckBox full,android.widget.CheckBox foley,
            boolean auditOnly){
        try{
            if(activeProject==null)throw new IllegalStateException("Original project closed");
            String selectedBackend=modes[backend.getSelectedItemPosition()];
            if("SOURCE_RIG".equals(selectedBackend)){storyboardArtworkDialog();return;}
            JSONObject options=new JSONObject()
                    .put("genre",values[genre.getSelectedItemPosition()])
                    .put("backend",selectedBackend)
                    .put("motionIntent",StoryboardOwnerRequests.intentForBackend(selectedBackend))
                    .put("direction",direction.getText().toString().trim())
                    .put("framesPerPair",steps[samples.getSelectedItemPosition()])
                    .put("fps",24)
                    .put("maxFramesPerBatch",20);
            StoryboardBatchPlanner.Plan plan=
                    StoryboardBatchPlanner.plan(activeProject,options);
            int batch=full.isChecked()?-1:0;
            int count=StoryboardSeriesOrchestrator.predictedFrames(plan,batch);
            Intent action=StoryboardOwnerRequests.series(this,activeProject.id,values[genre.getSelectedItemPosition()],selectedBackend,direction.getText().toString().trim(),steps[samples.getSelectedItemPosition()],batch,foley.isChecked(),auditOnly);
            startForegroundService(action);
            Toast.makeText(this,
                    (auditOnly?"Checking selected motion scope":StoryboardOwnerRequests.intentForBackend(selectedBackend).equals("environmental")?"Environmental motion requested":"Source-deformation draft requested")+
                    " · "+plan.assets.size()+" distinct frames · "+
                    count+" output motion frames",
                    Toast.LENGTH_LONG).show();
            showActivity();
        }catch(Exception issue){
            new AlertDialog.Builder(this)
                    .setTitle("Storyboard engine needs another shot plan")
                    .setMessage(issue.getMessage()==null?"Source frames unavailable":
                            issue.getMessage())
                    .setPositiveButton("OK",null).show();
        }
    }

    private void storyboardArtworkDialog() {
        if(activeProject==null){Toast.makeText(this,"Open a source storyboard project first",Toast.LENGTH_LONG).show();return;}
        ProjectStore.Asset first=null;int images=0;
        for(ProjectStore.Asset asset:activeProject.assets)if(asset.mime!=null&&asset.mime.startsWith("image/")){if(first==null)first=asset;images++;}
        if(images<2){new AlertDialog.Builder(this).setTitle("Prepare authentic source frames").setMessage("Import at least two original storyboard images. The saved-program editor chooses exact actor profiles, original sword masks, visible source landmarks, foot contacts and twelve-beat choreography. Hidden anatomy and cinematic acceptance are not inferred.").setPositiveButton("Open source annotation",(d,w)->open2DRigEditor()).setNegativeButton("Close",null).show();return;}
        StoryboardContactEditorDialog.show(this,store,new Rig2DProjectStore(this,store),activeProject.id,first.id);
    }

    private void queueUserRiggedDuel(){
        if(activeProject==null)return;
        Rig2DProjectStore rigs=new Rig2DProjectStore(this,store);
        Rig2DProfile black=null,white=null;
        for(Rig2DProfile rig:rigs.list(activeProject.id)){
            if(rig.black)black=rig;else white=rig;
        }
        if(black==null||white==null){
            new AlertDialog.Builder(this).setTitle("Rig your actual fighters first")
                .setMessage("VideoStudio needs one traced actor and sixteen joints for "+
                        "each black and white character before it can produce real "+
                        "source-textured skeletal movement. No more generic replacement figures.")
                .setPositiveButton("Open Rig Builder",(dialog,which)->open2DRigEditor())
                .setNegativeButton("Cancel",null).show();
            return;
        }
        Intent render=new Intent(this,ControlService.class)
                .setAction(ControlService.ACTION_LOCAL_RIGGED_2D)
                .putExtra("projectId",activeProject.id)
                .putExtra("blackRigId",black.id)
                .putExtra("whiteRigId",white.id)
                .putExtra("backgroundMode","procedural")
                .putExtra("durationSeconds",4.6)
                .putExtra("fps",24)
                .putExtra("width",432)
                .putExtra("height",768);
        startForegroundService(render);
        Toast.makeText(this,"Source-textured 2D animation queued in native VideoStudio",
                Toast.LENGTH_LONG).show();
        showActivity();
    }

    /** Search is shared by Home, Tools and the editor without hiding native studios. */
    private void showTools(){
        ScrollView scroll=baseScroll();
        LinearLayout box=column();box.setPadding(dp(16),dp(20),dp(16),dp(24));
        scroll.addView(box);
        box.addView(title("Studio tools",26));
        box.addView(body("Find a workspace or an editing action."),margins(-1,-2,dp(4),dp(16),0,0));
        addStudioSearch(box);
        setScreen(scroll,"tools");
    }

    private void addStudioSearch(LinearLayout box){
        EditText search=new EditText(this);
        search.setSingleLine(true);search.setTextColor(C_TEXT);search.setHintTextColor(C_MUTED);
        search.setHint("Search tools and studios");search.setContentDescription("Search tools and studios");
        search.setMinimumHeight(dp(48));
        box.addView(search,margins(-1,-2,0,dp(12),0,0));
        LinearLayout results=column();box.addView(results);
        results.addView(buildStudioDirectory(""));
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){
                results.removeAllViews();results.addView(buildStudioDirectory(text.toString()));
            }
            public void afterTextChanged(android.text.Editable text){}
        });
    }

    private LinearLayout buildStudioDirectory(String query){
        LinearLayout list=column();
        String q=query==null?"":query.trim().toLowerCase(java.util.Locale.ROOT);
        String[] names={"New Project","Import Media","Open Editor","All Renders","Generate from Prompt",
                "Cinematic 3D Studio","Storyboard Studio","Storyboard Artwork","Animate Imported Stills",
                "My Projects","Agent connection","Native jobs","Archive to Google Drive"};
        Runnable[] actions={this::createProjectDialog,this::pickMedia,this::showEditor,this::showStudioRenderLibrary,
                this::promptVideoDialog,this::openCinematicStudio,this::storyboardStudioDialog,this::storyboardArtworkDialog,
                this::animateImagesDialog,this::showStudioProjectLibrary,this::showStudioConnectionHub,
                this::showStudioJobDetails,this::archiveActiveProjectToCloud};
        for(int i=0;i<names.length;i++)if(names[i].toLowerCase(java.util.Locale.ROOT).contains(q)){
            Runnable action=actions[i];
            list.addView(studioButton(names[i],()->runStudioDirectoryAction(action)),margins(-1,-2,0,dp(6),0,0));
        }
        String group="";
        for(StudioToolCatalog.Tool tool:StudioToolCatalog.find(q)){
            if(!tool.group.equals(group)){group=tool.group;list.addView(section(group));}
            Button button=studioButton(tool.label+(tool.supported?"":" (unavailable)"),
                    ()->runStudioDirectoryAction(()->performStudioTool(tool.label)));
            button.setContentDescription(tool.label+". "+tool.description+(tool.supported?"":". Unavailable"));
            list.addView(button,margins(-1,-2,0,dp(6),0,0));
        }
        if(list.getChildCount()==0)list.addView(body("No matching tools. Try drawing, audio or motion."));
        return list;
    }

    private void runStudioDirectoryAction(Runnable action){
        if(studioToolDialog!=null)studioToolDialog.dismiss();
        action.run();
    }

    private void openCinematicStudio(){
        if(activeProject==null)activeProject=store.active();
        if(activeProject==null)activeProject=store.create("Untitled Project");
        startActivity(new Intent(this,Cinematic3DStudioActivity.class).putExtra("projectId",activeProject.id));
    }

    private void showLegacyTools() {
        ScrollView scroll = baseScroll();
        LinearLayout box = column();
        box.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(box);

        box.addView(title("AI Magic for Your Videos", 27));
        box.addView(body("A native tool surface designed so ChatGPT can use the same primitives you can."));
        LinearLayout tabs = new LinearLayout(this);
        tabs.addView(accentPill("All"));
        tabs.addView(accentPill("Edit"));
        tabs.addView(accentPill("Enhance"));
        tabs.addView(accentPill("Audio"));
        box.addView(tabs, margins(-1, -2, dp(14), dp(16), 0, 0));

        LinearLayout promptCard = card(true);
        promptCard.addView(title("Prompt → Finished Video", 22));
        promptCard.addView(body("Describe a video. ChatGPT can design the scenes, pacing, titles, motion and style, then VideoStudio builds and exports it locally."));
        Button promptButton = neonButton("Generate from Prompt", C_MAGENTA);
        promptButton.setOnClickListener(v -> promptVideoDialog());
        promptCard.addView(promptButton, margins(-1, dp(52), dp(12), 0, 0, 0));
        box.addView(promptCard);

        LinearLayout animateCard = card(true);
        animateCard.addView(title("Still Images → Living Scenes", 22));
        animateCard.addView(body("On-device portrait AI separates subject and background, anchors motion around the face, builds independent depth layers, directs cinematic keyframes, and renders a real MP4."));
        Button animateButton = neonButton("Animate Current Image Project", C_CYAN);
        animateButton.setTextColor(Color.BLACK);
        animateButton.setOnClickListener(v -> animateImagesDialog());
        animateCard.addView(animateButton, margins(-1, dp(52), dp(12), 0, 0, 0));
        box.addView(animateCard, margins(-1, -2, dp(10), 0, 0, 0));

        LinearLayout directorCard=card(true);
        directorCard.addView(title("Animation Director • Real Frame Motion",22));
        directorCard.addView(body("Turn your imported image sequence into an authentic frame-by-frame animation. Automatic action timing, controlled impact slow motion, original frame fidelity. Optional experimental optical flow — no Ken Burns or collage tricks."));
        Button directorButton=neonButton("Animate Image Timeline",C_CYAN);
        directorButton.setOnClickListener(v -> animationDirectorDialog());
        directorCard.addView(directorButton,margins(-1,dp(52),dp(12),0,0,0));
        box.addView(directorCard,margins(-1,-2,dp(10),0,0,0));

        LinearLayout cinematicCard=card(true);
        cinematicCard.addView(title("Cinematic 3D • Articulated Animation",22));
        cinematicCard.addView(body("Build a sequence from eleven animated actions, watch both fighters in the scene, and adjust shot timing, camera, impacts and effects. Import custom cinematic scenes for authored character poses."));
        Button cinematicButton=neonButton("Open Cinematic 3D Studio",C_CYAN);
        cinematicButton.setOnClickListener(v->{Intent studio=new Intent(this,Cinematic3DStudioActivity.class);studio.putExtra("projectId",activeProject.id);startActivity(studio);});
        cinematicCard.addView(cinematicButton,margins(-1,dp(52),dp(12),0,0,0));
        box.addView(cinematicCard,margins(-1,-2,dp(10),0,0,0));
        LinearLayout modelCard=card(true);
        modelCard.addView(title("Real 3D Animation • GLB Skeletal Models",22));
        modelCard.addView(body("Load a user-selected glTF 2.0 GLB model with actual mesh geometry, material texture, bones, authored motion clips, directional light, perspective and camera orbit. Purely local GPU rendering; unlike a flat image, the model must already include true 3D surfaces and animation."));
        Button modelButton=neonButton("Open Real 3D Animation Studio",C_CYAN);
        modelButton.setOnClickListener(v -> open3DModelViewer());
        modelCard.addView(modelButton,margins(-1,dp(52),dp(12),0,0,0));
        Button export3D=neonButton("Render 3D Fight → Native MP4",C_MAGENTA);
        export3D.setOnClickListener(v -> choose3DCombatActors());
        modelCard.addView(export3D,margins(-1,dp(52),dp(12),0,0,0));
        box.addView(modelCard,margins(-1,-2,dp(10),0,0,0));
        LinearLayout sourcedRigCard=card(true);
        sourcedRigCard.addView(title("Real 2D Character Rigging • Original Artwork",22));
        sourcedRigCard.addView(body("Draw a source character outline and mark joints ONCE, then animate that same textured character with native IK and attached swords. Mark black and white actors separately; these are re-usable project-private rigs, not image morphing. 3D hidden anatomy is not generated."));
        Button sourceRig=neonButton("Rig My Black & White Fighters",C_CYAN);
        sourceRig.setOnClickListener(v -> open2DRigEditor());
        sourcedRigCard.addView(sourceRig,margins(-1,dp(52),dp(12),0,0,0));
        Button createRigged=neonButton("Render Source-Rigged 2D Duel",C_MAGENTA);
        createRigged.setOnClickListener(v -> queueUserRiggedDuel());
        sourcedRigCard.addView(createRigged,margins(-1,dp(52),dp(12),0,0,0));
        box.addView(sourcedRigCard,margins(-1,-2,dp(10),0,0,0));

        LinearLayout physicsCard=card(true);
        physicsCard.addView(title("True Combat Rig • Movement Benchmark",22));
        physicsCard.addView(body("Independent 2D joint animation, IK arms and legs, planted feet, fixed destroyed-city background, collision/recoil, continuous sword movement and genuine slow-motion retiming. Generates a separate native MP4 — not a neural restyling of your existing pictures."));
        Button rigButton=neonButton("Run Real-Motion Sword Fight",C_CYAN);
        rigButton.setOnClickListener(v -> combatRigBenchmarkDialog());
        physicsCard.addView(rigButton,margins(-1,dp(52),dp(12),0,0,0));
        box.addView(physicsCard,margins(-1,-2,dp(10),0,0,0));

        String[][] groups = {
                {"◎ Animate Stills", "AI subject layers, face-aware parallax and organic micro-motion"},
                {"✦ Auto Cut", "Scene-aware pacing and highlight edits"},
                {"▣ Scene Detect", "Native sampled-frame scene analysis"},
                {"⌗ Smart Reframe", "Vertical, square and subject-safe framing"},
                {"T Text Animation", "21 title and caption motion styles"},
                {"⌁ Motion Effects", "Pan, push, drift, orbit, shake and zoom"},
                {"⇄ Transitions", "25 creator transition presets"},
                {"◉ Colour Enhance", "GPU brightness, contrast and HSL looks"},
                {"CC Subtitles", "Caption timing and animated style model"},
                {"♫ Audio Duck", "Dialogue-first gain automation model"},
                {"◆ Green Screen", "Chroma key, tolerance and spill controls"},
                {"≈ Blur / Glow", "Gaussian blur, glow and dream looks"},
                {"◐ Masks", "Shape, feather and animated mask model"},
                {"⚡ Beat Sync", "Cut and motion markers for music beats"},
                {"✂ Shorts Recut", "Rebuild long footage for vertical short-form"},
                {"▧ B-roll Plan", "AI-assisted insert and coverage planning"},
                {"◫ Multi Variant", "Create alternate hooks and pacing versions"},
                {"Aa Fonts", "System creator font families and styles"},
                {"▶ Prompt Video", "Generate a complete local video from a prompt"}
        };
        for (int i = 0; i < groups.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2 && i + j < groups.length; j++) {
                final String tool = groups[i + j][0];
                LinearLayout tile = card(false);
                tile.addView(title(tool, 16));
                tile.addView(body(groups[i + j][1]));
                tile.setOnClickListener(v -> {
                    if (tool.contains("Prompt Video")) promptVideoDialog();
                    else if (tool.contains("Animate Stills")) animateImagesDialog();
                    else if (tool.contains("Green")) applyTool("Green Screen");
                    else if (tool.contains("Transition")) applyTool("Transitions");
                    else if (tool.contains("Motion")) applyTool("Motion");
                    else if (tool.contains("Colour")) applyTool("Colour");
                    else if (tool.contains("Fonts")) applyTool("Fonts");
                    else if (tool.contains("Blur")) applyTool("Blur");
                    else Toast.makeText(this, tool + " is available to the autonomous editor", Toast.LENGTH_SHORT).show();
                });
                row.addView(tile, weightWithMargin());
            }
            box.addView(row, margins(-1, -2, 0, dp(8), 0, 0));
        }

        LinearLayout mcp = card(false);
        mcp.setBackground(neonCard());
        mcp.addView(title("Connected editing surface", 18));
        mcp.addView(body("MCP v3 controls the native engine directly: attachment ingest, portrait animation, analysis, edits, prompt-video, layered rendering, export and job inspection. Gallery enumeration is permanently excluded."));
        Button connect = neonButton("Connect ChatGPT", C_CYAN);
        connect.setTextColor(Color.BLACK);
        connect.setOnClickListener(v -> sharePairing());
        mcp.addView(connect, margins(-1, dp(50), dp(12), 0, 0, 0));
        box.addView(mcp, margins(-1, -2, dp(12), 0, 0, 0));

        setScreen(scroll, "tools");
    }

    private void showControl() {
        ScrollView scroll = baseScroll();
        LinearLayout box = column();
        box.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(box);

        box.addView(title("Agent & storage", 27));
        box.addView(studioButton("Connect / Repair ChatGPT ↗",this::showStudioConnectionHub),
                margins(-1,dp(49),dp(5),dp(8),0,0));
        box.addView(body("Full Autonomous is the default. The MCP path is signalling/control only; projects, media and editing state live in the app. Gallery enumeration remains a hard technical boundary, not a permission toggle."));
        box.addView(section("Autonomy Mode"));
        box.addView(permissionCard("everything", "Full Autonomous  •  Recommended", "ChatGPT can use every VideoStudio-native operation: explicit file imports, project management, analysis, AI animation, editing, rendering, inspection, retries and cleanup without repeated permission prompts. Gallery listing/browsing remains technically blocked."));
        box.addView(permissionCard("one_file", "One File Lock", "Optional manual safety lock. Restricts ChatGPT to the currently authorised media file until you switch back to Full Autonomous."));

        box.addView(section("Workload Safety"));
        LinearLayout safety = card(false);
        safety.addView(title("Heavy-work governor", 17));
        JSONObject state=jobs.state();
        JSONObject mem=state.optJSONObject("memory");
        JSONObject nativeWork=NativeWorkMonitor.snapshot(this);
        String detail="Available RAM: "+(mem==null?"?":mem.optLong("availableMb"))+" MB"
                +"\nReported by Android: "+nativeWork.optString("thermalLabel","unknown")
                +"\nNative MCP renders: "+nativeWork.optInt("active",0)+" active / "
                +nativeWork.optInt("waiting",0)+" waiting"
                +"\n1 protected heavy renderer + lightweight editing lanes"
                +"\nThe hardware, not the phone's outside temperature, determines safety.";
        safety.addView(body(detail));
        Button inspect=studioButton("Inspect native render queue",this::showStudioJobDetails);
        safety.addView(inspect,margins(-1,dp(43),dp(9),0,0,0));
        box.addView(safety);

        box.addView(section("Cloud Workspace"));
        LinearLayout cloud = card(false);
        JSONObject cloudState = driveWorkspace.status();
        boolean cloudLinked = cloudState.optBoolean("linked", false);
        cloud.addView(title(cloudLinked ? "Cloud workspace linked" : "Link a workspace folder", 17));
        cloud.addView(body(cloudLinked
                ? "Folder-scoped archive is active through Android's document provider. Broad Google Drive permission is not used. Provider: "
                    + cloudState.optString("providerAuthority", "document provider")
                : "Choose one folder from Android's system folder picker. If Google Drive is available there, VideoStudio receives persistent read/write access only to that selected folder, not your whole Drive."));
        Button cloudLink = compactButton(cloudLinked ? "Change Workspace Folder" : "Link Workspace Folder");
        cloudLink.setOnClickListener(v -> pickCloudWorkspace());
        cloud.addView(cloudLink, margins(-1, dp(44), dp(8), 0, 0, 0));
        if (cloudLinked) {
            Button archive = compactButton("Archive Active Project");
            archive.setOnClickListener(v -> archiveActiveProjectToCloud());
            cloud.addView(archive, margins(-1, dp(44), dp(6), 0, 0, 0));
            Button offload = compactButton("Archive + Free Local Workspace");
            offload.setOnClickListener(v -> offloadActiveProjectToCloud());
            cloud.addView(offload, margins(-1, dp(44), dp(6), 0, 0, 0));
            Button restore = compactButton("Restore Active Project Workspace");
            restore.setOnClickListener(v -> restoreActiveProjectFromCloud());
            cloud.addView(restore, margins(-1, dp(44), dp(6), 0, 0, 0));
            Button unlink = compactButton("Unlink Workspace");
            unlink.setOnClickListener(v -> {
                driveWorkspace.unlink();
                ActivityLog.add(this, "user", "Cloud workspace unlinked",
                        "The folder capability was removed from VideoStudio settings. Existing Drive files were left untouched.",
                        "success", 100, null, activeProject == null ? null : activeProject.id);
                showControl();
            });
            cloud.addView(unlink, margins(-1, dp(44), dp(6), 0, 0, 0));
        }
        box.addView(cloud);

        box.addView(section("Safe Updates & Legacy Migration"));
        LinearLayout updates=card(false);
        updates.addView(title("Video studio updates",17));
        JSONObject legacyInstall=NativeApkUpdater.legacyInstallationStatus(this);
        updates.addView(body(legacyInstall.optBoolean("installed",false)
                ? "Existing old VideoStudio detected (versionCode "
                  +legacyInstall.optLong("versionCode",0)
                  +"). Its installed signing certificate: "
                  +legacyInstall.optString("certificateSha256","unavailable")
                : "Old VideoStudio signing identity is not visible on this device."));
        updates.addView(body("Compatible signed APKs update this app in place and retain its projects and app key. "
                +"The earlier legacy package, if installed, remains separate. "
                +"Android verifies the signing certificate before installing an update."));
        updates.addView(studioButton("Install an update APK",this::chooseUpdateApk),
                margins(-1,dp(46),dp(7),0,0,0));
        updates.addView(studioButton("Recover projects from legacy cloud archive",
                this::confirmLegacyProjectRecovery),
                margins(-1,dp(46),dp(7),0,0,0));
        updates.addView(body("Choose the SAME folder your old app archived to: "
                +"VideoStudio-MCP-Temp-Frames. Imported source files and generated "
                +"frames are restored privately when the archive is complete; "
                +"missing files are reported without modifying the old app."));
        box.addView(updates);

        box.addView(section("Private Connection"));
        LinearLayout privateCard = card(false);
        privateCard.addView(title("Device-owned stable MCP endpoint", 17));
        privateCard.addView(body("This same private MCP endpoint survives compatible APK updates. The Keystore owner identity stays fixed while the Connection Core negotiates the current app generation, heartbeat and command profile. Stale Native Agent processes are fenced automatically. VideoStudio remains the source of truth."));
        Button pause = neonButton(protocol.isControlPaused() ? "Resume ChatGPT Control" : "STOP CHATGPT CONTROL", protocol.isControlPaused() ? C_CYAN : Color.rgb(180, 38, 67));
        pause.setOnClickListener(v -> {
            boolean next = !protocol.isControlPaused();
            protocol.setControlPaused(next);
            Intent control = new Intent(this, ControlService.class)
                    .setAction(next ? ControlService.ACTION_PAUSE : ControlService.ACTION_RESUME);
            startService(control);
            if (next) {
                if (activeRenderHandle != null) activeRenderHandle.cancel();
                jobs.cancelAll();
            }
            ui.postDelayed(this::showControl, 120);
        });
        privateCard.addView(pause, margins(-1, dp(52), dp(10), dp(8), 0, 0));
        Button repairConnection = neonButton("Repair Connection", C_CYAN);
        repairConnection.setOnClickListener(v -> {
            startService(new Intent(this, ControlService.class).setAction(ControlService.ACTION_RECONNECT));
            Toast.makeText(this, "Secure reconnection requested", Toast.LENGTH_SHORT).show();
        });
        privateCard.addView(repairConnection, margins(-1, dp(48), dp(8), 0, 0, 0));
        Button share = neonButton("Connect / Share with ChatGPT", C_PURPLE);
        share.setOnClickListener(v -> sharePairing());
        privateCard.addView(share, margins(-1, dp(52), dp(12), 0, 0, 0));
        privateCard.addView(studioButton("Share app key",this::shareAppKey),
                margins(-1,ViewGroup.LayoutParams.WRAP_CONTENT,dp(12),0,0,0));
        privateCard.addView(studioButton("Copy app key",this::copyAppKey),
                margins(-1,ViewGroup.LayoutParams.WRAP_CONTENT,dp(6),0,0,0));
        privateCard.addView(studioButton("Latest backup task result",this::showLatestBackupResult),
                margins(-1,ViewGroup.LayoutParams.WRAP_CONTENT,dp(6),0,0,0));
        privateCard.addView(body("Share one code with ChatGPT to use the encrypted backup delivery route. "
                +"ChatGPT also needs write access to this project's GitHub repository. "
                +"Your local control and media permissions apply to both connections."));
        SharedPreferences backup=getSharedPreferences("videostudio_agent_mailbox_v1",MODE_PRIVATE);
        long lastPoll=backup.getLong("lastPollAt",0),age=System.currentTimeMillis()-lastPoll;
        boolean backupFresh=lastPoll>0&&age>=0&&age<30000&&backup.getBoolean("connected",false);
        String backupStatus=protocol.isControlPaused()?"Paused":backupFresh?"Delivery reachable":"Waiting for a connection";
        privateCard.addView(title("GitHub backup · "+backupStatus,16));
        String receiptState=backup.getString("lastReceiptState","");
        if(!receiptState.isEmpty())privateCard.addView(body(receiptState));
        privateCard.addView(body("Task delivery can continue when the MCP command store fails. "
                +"Remote results use the separate relay metadata route; a full relay outage delays those results. "
                +"Execution results are retained privately on this phone."));
        box.addView(privateCard);

        setScreen(scroll, "control");
    }

    private View brandHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.rezoxnemesis.videostudio.R.drawable.logo_vs);
        row.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = column();
        copy.setPadding(dp(12), 0, 0, 0);
        TextView brand = title("Video studio", 25);
        copy.addView(brand);
        copy.addView(body("Your ideas, in motion"));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View projectRow(ProjectStore.Project project) {
        LinearLayout row = card(false);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = column();
        copy.addView(title(project.name, 16));
        copy.addView(body(project.clips.size() + " clips • " + time(project.outputDurationMs()) + " • " + project.assets.size() + " media"));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView open = title("›", 28);
        row.addView(open);
        row.setOnClickListener(v -> {
            activeProject = store.get(project.id);
            store.setActive(project.id);
            selectedClip = activeProject != null && !activeProject.clips.isEmpty() ? activeProject.clips.get(0) : null;
            showEditor();
        });
        return row;
    }

    private View permissionCard(String value, String label, String description) {
        String current = permissionMode();
        LinearLayout card = card(false);
        boolean selected = value.equals(current);
        card.setBackground(rounded(selected ? Color.rgb(24, 44, 68) : C_CARD, selected ? C_CYAN : Color.rgb(35, 48, 72), dp(16)));
        card.addView(title((selected ? "✓  " : "") + label, 17));
        card.addView(body(description));
        card.setOnClickListener(v -> {
            if ("one_file".equals(value) && (selectedClip == null || activeProject == null)) {
                Toast.makeText(this, "Select or import a file first", Toast.LENGTH_SHORT).show();
                pickMedia();
                return;
            }
            prefs.edit().putString(KEY_MODE, value).apply();
            if ("one_file".equals(value) && selectedClip != null) {
                prefs.edit().putString(KEY_FILE, selectedClip.assetId).apply();
            }
            syncProtocolState();
            requestServiceSync();
            showControl();
        });
        return card;
    }

    private void createProjectDialog() {
        final EditText input = new EditText(this);
        input.setHint("Project name");
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_MUTED);
        input.setSingleLine();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(22), dp(4), dp(22), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        new AlertDialog.Builder(this)
                .setTitle("New project")
                .setView(wrap)
                .setPositiveButton("Create", (d, w) -> createProject(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createProject(String name) {
        activeProject = store.create(name);
        selectedClip = null;
        syncProtocolState();
        requestServiceSync();
        showEditor();
    }

    /** Android Files selects one exact APK; never enumerate local installed apps. */
    private void chooseUpdateApk() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,PICK_UPDATE_APK);
    }

    private void inspectAndOfferUpdate(Uri selected) {
        Toast.makeText(this,"Checking APK certificate and version…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try {
                NativeApkUpdater.Inspection report=
                        NativeApkUpdater.stageAndVerify(getApplicationContext(),selected);
                runOnUiThread(()->{
                    AlertDialog.Builder result=new AlertDialog.Builder(this)
                            .setTitle(report.canUpdate()?"Compatible VideoStudio update":
                                    "Update blocked — original projects protected")
                            .setMessage(report.report())
                            .setNegativeButton("Close",null);
                    if(report.canUpdate())result.setPositiveButton(
                            "Open Android installer",(dialog,button)->{
                                try {
                                    NativeApkUpdater.openSystemInstaller(this,report);
                                }catch(Exception error){
                                    Toast.makeText(this,
                                            "Installer unavailable: "+error.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                    result.show();
                });
            }catch(Exception error){
                runOnUiThread(()->new AlertDialog.Builder(this)
                        .setTitle("APK verification was unsuccessful")
                        .setMessage(error.getMessage()==null?"Cannot inspect this APK":
                                error.getMessage())
                        .setPositiveButton("OK",null).show());
            }
        },"VideoStudio-APK-Compatibility").start();
    }

    private void confirmLegacyProjectRecovery() {
        if(!driveWorkspace.isLinked()){
            new AlertDialog.Builder(this)
                    .setTitle("Link your old Studio folder")
                    .setMessage("Select the VideoStudio-MCP-Temp-Frames folder "
                            +"used by your existing VideoStudio app. The old app "
                            +"and its projects are not altered.")
                    .setPositiveButton("Choose folder",(d,w)->pickCloudWorkspace())
                    .setNegativeButton("Cancel",null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Recover your archived projects?")
                .setMessage("Read project manifests and explicitly staged media "
                        +"from the linked VideoStudio folder. Only projects with "
                        +"all required source files are committed. Existing Next "
                        +"projects and your old app stay untouched.")
                .setPositiveButton("Start recovery",(d,w)->{
                    JobManager.Job job=jobs.submit("Recover legacy VideoStudio projects",
                            JobManager.Kind.TRANSFER,state->{
                                state.checkpoint("Cloud migration",1,
                                        "Checking legacy archive and source media");
                                JSONObject outcome=new LegacyCloudRecovery(this,store)
                                        .restoreAll((percent,detail)->
                                                state.checkpoint("Cloud migration",percent,detail));
                                state.setResult(outcome);
                                ActivityLog.add(this,"system","Legacy project recovery",
                                        outcome.optInt("imported",0)+" project(s) imported; "+
                                        outcome.optInt("skipped",0)+" require source media",
                                        outcome.optInt("skipped",0)>0?"info":"success",
                                        100,null,null);
                                runOnUiThread(()->{
                                    activeProject=store.active();
                                    syncProtocolState();
                                    requestServiceSync();
                                    new AlertDialog.Builder(this)
                                            .setTitle("Recovery report")
                                            .setMessage(outcome.toString())
                                            .setPositiveButton("OK",(dia,which)->showHome())
                                            .show();
                                });
                            });
                    Toast.makeText(this,"Project recovery queued • "+
                            job.id.substring(0,8),Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void pickCloudWorkspace() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_CLOUD_WORKSPACE);
    }

    private void archiveActiveProjectToCloud() {
        if (activeProject == null) {
            Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!driveWorkspace.isLinked()) {
            pickCloudWorkspace();
            return;
        }
        ProjectStore.Project project = store.get(activeProject.id);
        if (project == null) return;
        JobManager.Job job = jobs.submit("Archive project • " + project.name, JobManager.Kind.LIGHT, state -> {
            state.checkpoint("Cloud archive", 2, "Preparing project workspace");
            File workspace = new CreativeWorkspace(this).projectRoot(project.id);
            JSONObject result = driveWorkspace.syncProject(project, workspace, (progress, detail) -> {
                state.checkpoint("Cloud archive", progress, detail);
                ActivityLog.progress(this, state.id, "Cloud archive", detail, progress, project.id);
            });
            state.checkpoint("Cloud archive", 100, "Archive complete");
            ActivityLog.add(this, "system", "Project archived",
                    project.name + " • " + result.optLong("bytesWritten", 0) + " bytes",
                    "success", 100, null, project.id);
        });
        Toast.makeText(this, "Archive queued • " + job.id.substring(0, 8), Toast.LENGTH_SHORT).show();
    }

    private void offloadActiveProjectToCloud() {
        if (activeProject == null) {
            Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!driveWorkspace.isLinked()) {
            pickCloudWorkspace();
            return;
        }
        ProjectStore.Project project = store.get(activeProject.id);
        if (project == null) return;
        JobManager.Job job = jobs.submit("Cloud offload • " + project.name, JobManager.Kind.HEAVY, state -> {
            File workspace = new CreativeWorkspace(this).projectRoot(project.id);
            state.checkpoint("Cloud offload", 2, "Archiving before local eviction");
            JSONObject archived = driveWorkspace.syncProject(project, workspace, (progress, detail) -> {
                int mapped = Math.max(2, Math.min(88, 2 + (int) (progress * .86)));
                state.checkpoint("Cloud offload", mapped, detail);
                ActivityLog.progress(this, state.id, "Cloud offload", detail, mapped, project.id);
            });
            if (!archived.optBoolean("ok", false)) throw new IllegalStateException("Cloud archive did not complete");
            state.checkpoint("Cloud offload", 90, "Verifying exact archive before local eviction");
            JSONObject evicted;
            synchronized(driveWorkspace){
                evicted=store.guardedProject(project.id,project.updatedAt,current->{
                    JSONObject verification=driveWorkspace.verifyArchiveForEviction(current,workspace,archived);
                    if(!verification.optBoolean("verified",false)||!verification.optBoolean("evictionEligible",false))
                        throw new IllegalStateException("Cloud archive could not be verified for local eviction; local media retained");
                    return new CreativeWorkspace(this).evictCloudBackedProject(current.id);
                });
            }
            state.checkpoint("Cloud offload", 100, "Cloud archive verified and local intermediates evicted");
            ActivityLog.add(this, "system", "Project workspace offloaded",
                    project.name + " • " + evicted.optLong("removedBytes", 0) + " bytes freed locally",
                    "success", 100, null, project.id);
        });
        Toast.makeText(this, "Cloud offload queued • " + job.id.substring(0, 8), Toast.LENGTH_SHORT).show();
    }

    private void restoreActiveProjectFromCloud() {
        if (activeProject == null) {
            Toast.makeText(this, "Open a project first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!driveWorkspace.isLinked()) {
            pickCloudWorkspace();
            return;
        }
        ProjectStore.Project project = store.get(activeProject.id);
        if (project == null) return;
        JobManager.Job job = jobs.submit("Restore project • " + project.name, JobManager.Kind.LIGHT, state -> {
            state.checkpoint("Cloud restore", 2, "Preparing project workspace");
            File workspace = new CreativeWorkspace(this).projectRoot(project.id);
            JSONObject result = driveWorkspace.restoreProjectWorkspace(project.id, workspace, (progress, detail) -> {
                state.checkpoint("Cloud restore", progress, detail);
                ActivityLog.progress(this, state.id, "Cloud restore", detail, progress, project.id);
            });
            if(!result.optBoolean("verified",false))throw new IllegalStateException("Cloud restore was not verified");
            new CreativeWorkspace(this).markCloudHydrated(project.id);
            state.checkpoint("Cloud restore", 100, "Restore complete");
            ActivityLog.add(this, "system", "Project workspace restored",
                    project.name + " • " + result.optLong("bytesRestored", 0) + " bytes",
                    "success", 100, null, project.id);
        });
        Toast.makeText(this, "Restore queued • " + job.id.substring(0, 8), Toast.LENGTH_SHORT).show();
    }

    private void pickMedia() {
        if (activeProject == null) activeProject = store.create("Untitled Project");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/*", "audio/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_MEDIA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode==PICK_VIDEO_OVERLAY_FILE){selectedStudioVideoResult(resultCode,data);return;}
        if (requestCode == PICK_UPDATE_APK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                inspectAndOfferUpdate(data.getData());
            return;
        }
        if (requestCode == PICK_CLOUD_WORKSPACE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(tree, takeFlags);
                driveWorkspace.link(tree);
                ActivityLog.add(this, "user", "Cloud workspace linked",
                        "Folder-scoped archive enabled without broad Drive permission",
                        "success", 100, null, activeProject == null ? null : activeProject.id);
                Toast.makeText(this, "Workspace folder linked", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Could not persist folder access", Toast.LENGTH_LONG).show();
            }
            showControl();
        } else if (requestCode == PICK_MEDIA && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) uris.add(clipData.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                ProjectStore.Asset asset = store.importUri(activeProject, uri);
                if (ProxyManager.shouldProxy(asset)) {
                    try {
                        proxyManager.request(activeProject, asset, "720p");
                        ActivityLog.add(this, "system", "Heavy preview proxy queued",
                                asset.name + " • original retained for final render",
                                "queued", 0, null, activeProject.id);
                    } catch (Exception proxyError) {
                        ActivityLog.add(this, "system", "Heavy preview proxy unavailable",
                                proxyError.getMessage() == null ? asset.name : proxyError.getMessage(),
                                "info", null, null, activeProject.id);
                    }
                }
                if (selectedClip == null && !activeProject.clips.isEmpty()) selectedClip = activeProject.clips.get(activeProject.clips.size() - 1);
                if ("one_file".equals(permissionMode()) && prefs.getString(KEY_FILE, "").isEmpty()) {
                    prefs.edit().putString(KEY_FILE, asset.id).apply();
                }
            }
            syncProtocolState();
            requestServiceSync();
            showEditor();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void previewSelectedClip() {
        if (selectedClip == null || activeProject == null || livePlayer == null) return;
        ProjectStore.Asset asset = activeProject.asset(selectedClip.assetId);
        if (asset == null || asset.mime == null || !asset.mime.startsWith("video/")) return;
        timelinePreviewRunning = false;
        playClip(selectedClip, null);
    }

    private void previewTimeline() {
        if (activeProject == null || activeProject.clips.isEmpty() || livePlayer == null) return;
        boolean hasVideoClip = false;
        for (ProjectStore.Clip clip : activeProject.clips) {
            ProjectStore.Asset asset = activeProject.asset(clip.assetId);
            if (asset != null && asset.mime != null && asset.mime.startsWith("video/")) {
                hasVideoClip = true;
                break;
            }
        }
        if (!hasVideoClip) {
            if (activeProject.latestExportUri != null && !activeProject.latestExportUri.isEmpty()) {
                timelinePreviewRunning = false;
                player().setContextIds("final:" + activeProject.latestExportAt, "");
                player().play(Uri.parse(activeProject.latestExportUri), 0L);
            } else {
                Toast.makeText(this, "Render the animated image timeline first", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        timelinePreviewRunning = true;
        playTimelineIndex(0);
    }

    private void playTimelineIndex(int index) {
        if (!timelinePreviewRunning || activeProject == null || index >= activeProject.clips.size()) {
            timelinePreviewRunning = false;
            return;
        }
        ProjectStore.Clip clip = activeProject.clips.get(index);
        selectedClip = clip;
        ProjectStore.Asset asset = activeProject.asset(clip.assetId);
        if (asset == null || asset.mime == null || !asset.mime.startsWith("video/")) {
            playTimelineIndex(index + 1);
            return;
        }
        playClip(clip, () -> playTimelineIndex(index + 1));
    }

    private void playClip(ProjectStore.Clip clip, Runnable after) {
        if (activeProject == null || livePlayer == null || clip == null) return;
        ProjectStore.Asset asset = activeProject.asset(clip.assetId);
        if (asset == null) return;
        player().setContextIds("", clip.id);
        player().playClip(
                Uri.parse(ProxyManager.previewUri(activeProject, asset)),
                clip.inMs,
                clip.outMs,
                clip.speed,
                after == null ? null : () -> {
                    if (timelinePreviewRunning) after.run();
                }
        );
    }

    private void applyTool(String tool) {
        if(!StudioToolCatalog.isSupported(tool)){
            Toast.makeText(this,tool+" is unavailable: no implemented native effect. Project unchanged.",Toast.LENGTH_LONG).show();return;
        }
        if(java.util.Set.of("Green Screen","Mask","Glow","Overlay","Opacity","Crop","Reframe").contains(tool)){
            performStudioTool(tool);return;
        }
        if(activeProject==null||selectedClip==null){Toast.makeText(this,"Select a clip first",Toast.LENGTH_SHORT).show();return;}
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        try{
            JSONObject settings=new JSONObject();
            switch(tool){
                case "Split":splitClip();return;
                case "Trim":trimDialog();return;
                case "Text":textDialog();return;
                case "Duplicate":applyStudioTimeline(new JSONObject().put("action","duplicate").put("clipId",clipId),revision);return;
                case "Delete Clip":deleteStudioClip();return;
                case "Move Left":moveStudioClip(-1);return;
                case "Move Right":moveStudioClip(1);return;
                case "Slow Motion":settings.put("speed",selectedClip.speed<=.55f?.75:selectedClip.speed<=.8f?1:.5);break;
                case "Volume":settings.put("volume",selectedClip.volume>.8f?.6:selectedClip.volume>.3f?0:1);break;
                case "Blur":settings.put("blur",selectedClip.effects.optDouble("blur",0)>.1?0:5);break;
                case "Shake":settings.put("motionPreset","impact_shake");break;
                case "Effects":settings.put("effectPreset",CreatorCatalog.next(java.util.List.of("none","gaussian_blur","dream"),selectedClip.effects.optString("effectPreset","none")));break;
                case "Motion":settings.put("motionPreset",CreatorCatalog.next(CreatorCatalog.MOTIONS.stream()
                        .filter(StudioPrecisionEdits.MOTIONS::contains).collect(java.util.stream.Collectors.toList()),selectedClip.effects.optString("motionPreset","none")));break;
                case "Colour":settings.put("colorPreset",CreatorCatalog.next(java.util.List.of("none","cinematic","teal_orange","warm_film","cool_night","noir","golden_hour","matte","high_contrast","soft_portrait","cyberpunk"),selectedClip.effects.optString("colorPreset","none")));break;
                default:throw new IllegalArgumentException("This tool has no one-tap clip operation");
            }
            if(applyStudioPrecision(new JSONObject().put("clipId",clipId).put("set",settings),revision))
                Toast.makeText(this,tool+" applied to selected clip",Toast.LENGTH_SHORT).show();
        }catch(Exception error){Toast.makeText(this,"Edit not applied: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void splitClip() {
        if(selectedClip==null||activeProject==null)return;
        if(player().isEditedPreview()&&timelinePreviewRunning)studioPlayheadMs=player().currentPositionMs();
        if(!canSplitStudioAtCursor()){
            Toast.makeText(this,"Move the playhead inside the selected clip, leaving at least 0.1 seconds on each side",Toast.LENGTH_LONG).show();
            return;
        }
        long at=StudioEditCursor.sourceAt(selectedClip.inMs,selectedClip.outMs,selectedClip.speed,
                StudioTimelineEditOps.startOf(activeProject.clips,selectedClip.id),studioPlayheadMs);
        stopStillSequenceOnly();
        try{applyStudioTimeline(new JSONObject().put("action","split").put("clipId",selectedClip.id)
                .put("sourceTimeMs",at),activeProject.updatedAt);}
        catch(Exception error){Toast.makeText(this,"Split not applied: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private boolean canSplitStudioAtCursor(){
        return activeProject!=null&&selectedClip!=null&&StudioEditCursor.canSplit(selectedClip.inMs,selectedClip.outMs,
                selectedClip.speed,StudioTimelineEditOps.startOf(activeProject.clips,selectedClip.id),studioPlayheadMs);
    }

    private void trimStudioAtCursor(boolean trimIn){
        if(activeProject==null||selectedClip==null)return;
        long previous=studioPlayheadMs;
        try{
            if(player().isEditedPreview()&&timelinePreviewRunning)studioPlayheadMs=player().currentPositionMs();
            long start=StudioTimelineEditOps.startOf(activeProject.clips,selectedClip.id);
            long source=StudioEditCursor.sourceAt(selectedClip.inMs,selectedClip.outMs,selectedClip.speed,start,studioPlayheadMs);
            long minimum=Math.max(100,(long)Math.ceil(100*selectedClip.speed));
            if(trimIn&&selectedClip.outMs-source<minimum||!trimIn&&source-selectedClip.inMs<minimum)
                throw new IllegalArgumentException("Leave at least 0.1 seconds in the selected clip");
            JSONObject settings=new JSONObject().put(trimIn?"inMs":"outMs",source);
            String id=selectedClip.id;long revision=activeProject.updatedAt;
            stopStillSequenceOnly();studioPlayheadMs=trimIn?start:Math.max(start,studioPlayheadMs-1);
            if(!applyStudioPrecision(new JSONObject().put("clipId",id).put("set",settings),revision))studioPlayheadMs=previous;
        }catch(Exception error){studioPlayheadMs=previous;Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void trimDialog() {
        if(activeProject==null||selectedClip==null)return;
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        final ProjectStore.Clip original=selectedClip;
        ProjectStore.Asset asset=activeProject.asset(original.assetId);
        final long maximum=StudioTrimMath.sourceLength(asset==null?0:asset.durationMs,original);
        final boolean still=asset!=null&&asset.mime!=null&&asset.mime.startsWith("image/");
        // A still's imported duration is a default, not a finite source-video boundary.
        // Keep the seek handles useful over the current extent; exact numeric duration
        // uses the same existing 24h admission as native precision editing.
        final long allowedMaximum=still?86_400_000L:maximum;
        ScrollView scroll=new ScrollView(this);
        LinearLayout wrap=column();wrap.setPadding(dp(20),dp(5),dp(20),dp(7));scroll.addView(wrap);
        wrap.addView(body(still?"Set exact still duration in seconds (up to 24 hours). Seek handles cover its current extent. Preview and export use the saved duration.":"Set exact source seconds (millisecond precision). Preview and export use the saved trim."));
        wrap.addView(body("Start / In-point"));
        EditText start=numberInput(0);start.setText(String.format(Locale.US,"%.3f",original.inMs/1000.0));wrap.addView(start);
        SeekBar inSeek=new SeekBar(this);inSeek.setMax(1000);inSeek.setProgress(StudioTrimMath.toSeek(original.inMs,maximum));
        inSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int position,boolean user){if(user)start.setText(String.format(Locale.US,"%.3f",StudioTrimMath.fromSeek(position,maximum)/1000.0));}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });wrap.addView(inSeek,margins(-1,dp(45),0,0,0,0));
        wrap.addView(body("End / Out-point"));
        EditText end=numberInput(0);end.setText(String.format(Locale.US,"%.3f",original.outMs/1000.0));wrap.addView(end);
        SeekBar outSeek=new SeekBar(this);outSeek.setMax(1000);outSeek.setProgress(StudioTrimMath.toSeek(original.outMs,maximum));
        outSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int position,boolean user){if(user)end.setText(String.format(Locale.US,"%.3f",StudioTrimMath.fromSeek(position,maximum)/1000.0));}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });wrap.addView(outSeek,margins(-1,dp(45),0,0,0,0));
        LinearLayout handles=new LinearLayout(this);
        for(boolean isIn:new boolean[]{true,false})handles.addView(studioButton(isIn?"Playhead → In":"Playhead → Out",()->{
            try{
                long source=StudioEditCursor.sourceAt(original.inMs,original.outMs,original.speed,
                        StudioTimelineEditOps.startOf(activeProject.clips,clipId),studioPlayheadMs);
                (isIn?start:end).setText(String.format(Locale.US,"%.3f",source/1000.0));
                (isIn?inSeek:outSeek).setProgress(StudioTrimMath.toSeek(source,maximum));
            }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
        }),new LinearLayout.LayoutParams(0,dp(44),1));wrap.addView(handles);
        TextView errorLabel=body("");wrap.addView(errorLabel);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Trim selected clip").setView(scroll)
                .setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                long a=secondsToMs(start),b=secondsToMs(end);
                if(!StudioTrimMath.valid(a,b,allowedMaximum))throw new IllegalArgumentException(still?"Still duration must retain at least 0.1 seconds within 24 hours":"Trim must fit the source and retain at least 0.1 seconds");
                if(applyStudioPrecision(new JSONObject().put("clipId",clipId)
                        .put("set",new JSONObject().put("inMs",a).put("outMs",b)),revision))dialog.dismiss();
            }catch(Exception error){errorLabel.setText(error.getMessage()==null?"Invalid trim range":error.getMessage());}
        }));dialog.show();
    }

    private void textDialog() {
        final String clipId=selectedClip.id;final long revision=activeProject.updatedAt;
        EditText input = new EditText(this);
        input.setText(selectedClip.title);
        input.setHint("Title text");
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_MUTED);
        new AlertDialog.Builder(this)
                .setTitle("Clip title")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    try{applyStudioPrecision(new JSONObject().put("clipId",clipId)
                            .put("set",new JSONObject().put("title",input.getText().toString().trim())),revision);}
                    catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    /**
     * The Animator owns the complete workflow: no external ffmpeg, manual MCP
     * timeline assembly, camera-only fake animation or public media uploads.
     */

    /**
     * Focused benchmark for the new articulated engine. This is intentionally
     * distinct from image-frame animation; it generates real new motion under
     * a locked procedural camera while preserving every existing project.
     */
    private void combatRigBenchmarkDialog() {
        LinearLayout wrap=column();
        wrap.setPadding(dp(20),dp(8),dp(20),0);
        wrap.addView(body("The native engine creates a new two-warrior sword fight with independent IK body motion, planted feet, collision timing and a completely stable ruined-city backdrop. Your existing 40-frame project is preserved."));
        wrap.addView(body("Duration in seconds (2 to 5)"));
        EditText duration=numberInput(3.6f);
        wrap.addView(duration,new LinearLayout.LayoutParams(-1,dp(52)));
        wrap.addView(body("Render rate"));
        android.widget.Spinner fpsPicker=new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> fpsOptions=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,
                new String[]{"24 fps • cinematic", "30 fps • smoother"});
        fpsOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsPicker.setAdapter(fpsOptions);
        wrap.addView(fpsPicker,new LinearLayout.LayoutParams(-1,dp(56)));
        wrap.addView(body("Locked background"));
        android.widget.Spinner backgroundPicker=new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> backgrounds=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,
                new String[]{"Procedural static ruined city (reliable)",
                        "Estimate fixed scenery from imported image frames (experimental)"});
        backgrounds.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backgroundPicker.setAdapter(backgrounds);
        backgroundPicker.setEnabled(activeProject!=null && activeProject.clips.size()>=5);
        wrap.addView(backgroundPicker,new LinearLayout.LayoutParams(-1,dp(56)));
        new AlertDialog.Builder(this)
                .setTitle("True Combat Rig Benchmark")
                .setMessage("This tests procedural, physically connected sword combat. It does not yet reconstruct the characters from your imported images or run a neural video model.")
                .setView(wrap)
                .setPositiveButton("Generate + Render",(dialog,which)->{
                    try {
                        double seconds=Double.parseDouble(duration.getText().toString());
                        if(seconds<2||seconds>5)
                            throw new IllegalArgumentException("Duration must be between 2 and 5 seconds");
                        Intent request=new Intent(this,ControlService.class)
                                .setAction(ControlService.ACTION_LOCAL_COMBAT_BENCHMARK)
                                .putExtra("sourceProjectId",activeProject==null?"":activeProject.id)
                                .putExtra("backgroundMode",backgroundPicker.getSelectedItemPosition()==1
                                        ? "source_median" : "procedural")
                                .putExtra("durationSeconds",seconds)
                                .putExtra("fps",fpsPicker.getSelectedItemPosition()==0?24:30)
                                .putExtra("width",540)
                                .putExtra("height",960);
                        startForegroundService(request);
                        Toast.makeText(this,"Rig fight queued on Android • progress in Activity",Toast.LENGTH_LONG).show();
                        showActivity();
                    }catch(Exception error){
                        Toast.makeText(this,error.getMessage()==null?"Unable to start":error.getMessage(),Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void animationDirectorDialog() {
        if(activeProject==null || activeProject.clips.size()<2) {
            Toast.makeText(this,"Open a project with at least two image frames",Toast.LENGTH_LONG).show();
            return;
        }
        int frameCount=0;
        for(ProjectStore.Clip clip:activeProject.clips) {
            ProjectStore.Asset asset=activeProject.asset(clip.assetId);
            if(asset==null || asset.mime==null || !asset.mime.startsWith("image/")) {
                Toast.makeText(this,"Animation Director requires an image-only timeline",Toast.LENGTH_LONG).show();
                return;
            }
            frameCount++;
        }
        final int eligibleFrames = frameCount;
        LinearLayout wrap=column();
        wrap.setPadding(dp(20),dp(8),dp(20),0);
        wrap.addView(body("Use all "+frameCount+" frames in their existing order. A new animation project is created; your originals stay unchanged."));
        wrap.addView(body("Animation method"));
        android.widget.Spinner mode=new android.widget.Spinner(this);
        String[] choices={"Cinematic frame sequence (recommended)",
                "Experimental optical-flow in-betweens (may ghost fast movement)"};
        android.widget.ArrayAdapter<String> modeAdapter=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,choices);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(modeAdapter);
        wrap.addView(mode,new LinearLayout.LayoutParams(-1,dp(56)));
        wrap.addView(body("Playback frame rate (24 or 30)"));
        EditText frameRate=numberInput(30);
        wrap.addView(frameRate,new LinearLayout.LayoutParams(-1,dp(50)));
        wrap.addView(body("Impact frame numbers, e.g. 21,23,24 (optional; otherwise automatically detected)"));
        EditText impact=new EditText(this);
        impact.setHint("Auto detect impact");
        impact.setSingleLine(true);
        impact.setTextColor(C_TEXT);
        impact.setHintTextColor(C_MUTED);
        wrap.addView(impact,new LinearLayout.LayoutParams(-1,dp(52)));
        new AlertDialog.Builder(this)
                .setTitle("VideoStudio Animation Director")
                .setMessage("Genuine authored-frame playback is the default. Interpolation is experimental and cannot invent unseen body poses. No Gallery browsing.")
                .setView(wrap)
                .setPositiveButton("Animate + Render",(dialog,button)->{
                    try {
                        int fps=Integer.parseInt(frameRate.getText().toString().trim());
                        if(fps!=24 && fps!=30)
                            throw new IllegalArgumentException("Choose 24 or 30 fps");
                        if(mode.getSelectedItemPosition()==1 && eligibleFrames>40)
                            throw new IllegalArgumentException("Experimental flow supports at most 40 images");
                        Intent request=new Intent(this,ControlService.class)
                                .setAction(ControlService.ACTION_LOCAL_ANIMATION_STUDIO)
                                .putExtra("projectId",activeProject.id)
                                .putExtra("method",mode.getSelectedItemPosition()==1?"flow":"direct")
                                .putExtra("fps",fps)
                                .putExtra("impactFrames",impact.getText().toString().trim());
                        startForegroundService(request);
                        Toast.makeText(this,"Animation Director started • follow Activity",Toast.LENGTH_LONG).show();
                        showActivity();
                    } catch(Exception error) {
                        Toast.makeText(this,error.getMessage()==null?"Unable to start animation":error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void animateImagesDialog() {
        if (activeProject == null) {
            Toast.makeText(this, "Create or open an image project first", Toast.LENGTH_SHORT).show();
            return;
        }
        int imageCount = 0;
        for (ProjectStore.Clip clip : activeProject.clips) {
            ProjectStore.Asset asset = activeProject.asset(clip.assetId);
            if (asset != null && asset.mime != null && asset.mime.startsWith("image/")) imageCount++;
        }
        if (imageCount == 0) {
            Toast.makeText(this, "Import still images into this project first", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout wrap = column();
        wrap.setPadding(dp(20), dp(6), dp(20), 0);

        EditText style = new EditText(this);
        style.setHint("cinematic / dreamy / dramatic / epic / warm / romantic");
        style.setText("cinematic");
        style.setSingleLine();
        style.setTextColor(C_TEXT);
        style.setHintTextColor(C_MUTED);
        wrap.addView(body("Animation style"));
        wrap.addView(style, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        EditText environment = new EditText(this);
        environment.setHint("river, forest, rain, mist, ambient…");
        environment.setText("ambient");
        environment.setSingleLine();
        environment.setTextColor(C_TEXT);
        environment.setHintTextColor(C_MUTED);
        wrap.addView(body("Environment motion hint"));
        wrap.addView(environment, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        EditText duration = numberInput(4.2f);
        wrap.addView(body("Seconds per image"));
        wrap.addView(duration, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        final int count = imageCount;
        new AlertDialog.Builder(this)
                .setTitle("Animate " + count + " still image" + (count == 1 ? "" : "s"))
                .setMessage("VideoStudio will run bundled portrait AI on-device, build depth layers and render the animation through the protected native lane.")
                .setView(wrap)
                .setPositiveButton("Animate + Render", (d, w) -> {
                    try {
                        String styleValue = style.getText().toString().trim().toLowerCase(Locale.US);
                        if (styleValue.isEmpty()) styleValue = "cinematic";
                        String environmentValue = environment.getText().toString().trim();
                        double seconds = Math.max(1.8, Math.min(8.0, Double.parseDouble(duration.getText().toString())));

                        Intent animate = new Intent(this, ControlService.class)
                                .setAction(ControlService.ACTION_LOCAL_ANIMATE);
                        animate.putExtra("projectId", activeProject.id);
                        animate.putExtra("style", styleValue);
                        animate.putExtra("environment", environmentValue);
                        animate.putExtra("intensity", .82d);
                        animate.putExtra("durationSecondsPerImage", seconds);
                        animate.putExtra("reorderForStory", true);
                        animate.putExtra("render", true);
                        animate.putExtra("aspect", "9:16");
                        animate.putExtra("quality", "1080p");
                        animate.putExtra("fileName", "VideoStudio_Animated_" + System.currentTimeMillis() + ".mp4");
                        startForegroundService(animate);
                        Toast.makeText(this, "Native animation queued • watch Activity for live progress", Toast.LENGTH_LONG).show();
                        showActivity();
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage() == null ? "Could not queue animation" : error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptVideoDialog() {
        LinearLayout wrap = column();
        wrap.setPadding(dp(20), dp(6), dp(20), 0);

        EditText prompt = new EditText(this);
        prompt.setHint("Describe the video you want…");
        prompt.setTextColor(C_TEXT);
        prompt.setHintTextColor(C_MUTED);
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        wrap.addView(prompt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        EditText duration = numberInput(18);
        duration.setHint("Duration seconds");
        wrap.addView(body("Approx. duration in seconds"));
        wrap.addView(duration, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        new AlertDialog.Builder(this)
                .setTitle("Create Video from a Prompt")
                .setMessage("VideoStudio will build a scene plan locally. When ChatGPT controls this tool, I can supply a richer scene-by-scene plan automatically.")
                .setView(wrap)
                .setPositiveButton("Generate", (d, w) -> {
                    try {
                        JSONObject p = new JSONObject();
                        p.put("prompt", prompt.getText().toString().trim());
                        p.put("durationSeconds", Math.max(4, Math.min(120, (int) Float.parseFloat(duration.getText().toString()))));
                        p.put("aspect", "9:16");
                        p.put("quality", "1080p");
                        p.put("style", "cinematic");
                        p.put("font", "sans-serif-medium");
                        Intent generate = new Intent(this, ControlService.class)
                                .setAction(ControlService.ACTION_LOCAL_PROMPT_VIDEO)
                                .putExtra("prompt", p.optString("prompt", ""))
                                .putExtra("durationSeconds", p.optInt("durationSeconds", 18))
                                .putExtra("aspect", p.optString("aspect", "9:16"))
                                .putExtra("quality", p.optString("quality", "1080p"))
                                .putExtra("style", p.optString("style", "cinematic"))
                                .putExtra("fileName", "VideoStudio_AI_" + System.currentTimeMillis() + ".mp4");
                        startForegroundService(generate);
                        Toast.makeText(this, "Prompt video queued in Native Agent • watch Autonomous work", Toast.LENGTH_LONG).show();
                        showActivity();
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage() == null ? "Could not create prompt video" : error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private JSONObject queuePromptVideo(JSONObject parameters) throws Exception {
        String prompt = parameters.optString("prompt", "").trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("Write a video prompt first");

        String titleText = prompt.replaceAll("\\s+", " ").trim();
        if (titleText.length() > 36) titleText = titleText.substring(0, 36).trim() + "…";
        ProjectStore.Project project = store.create("AI • " + titleText);
        project.sourcePrompt = prompt;
        store.save(project);
        activeProject = project;
        selectedClip = null;
        syncProtocolState();

        String aspect = parameters.optString("aspect", "9:16");
        String quality = parameters.optString("quality", "1080p");
        String fileName = "VideoStudio_AI_" + System.currentTimeMillis() + ".mp4";

        JobManager.Job job = jobs.submit("Prompt video • " + titleText, JobManager.Kind.HEAVY, state -> {
            state.checkpoint(3, "Building original procedural scene geometry");
            PromptVideoEngine.BuildResult built = promptVideoEngine.build(store, project, parameters);
            state.checkpoint(18, "Scene plan ready • starting native render");
            runExportBlocking(built.project, built.aspect, built.quality, fileName, state);
            state.checkpoint(100, "Prompt video complete");
        });

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", project.id);
        result.put("prompt", prompt);
        result.put("aspect", aspect);
        result.put("quality", quality);
        return result;
    }

    private JSONObject queueNativeExport(ProjectStore.Project project, String aspect, String quality, String fileName) throws Exception {
        return queueNativeExport(project,aspect,quality,fileName,null);
    }

    private JSONObject queueNativeExport(ProjectStore.Project project, String aspect, String quality, String fileName,Long maxOutputSampleDelayMs) throws Exception {
        if (project == null || project.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
        final ProjectStore.Project target = store.get(project.id);
        if (target == null) throw new IllegalArgumentException("Project not found");
        String safeName = (fileName == null || fileName.trim().isEmpty())
                ? "VideoStudio_" + System.currentTimeMillis() + ".mp4"
                : fileName.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (!safeName.toLowerCase(Locale.US).endsWith(".mp4")) safeName += ".mp4";
        final String finalName = safeName;

        JobManager.Job job = jobs.submit("Export • " + target.name, JobManager.Kind.HEAVY, state -> {
            state.checkpoint(2, "Preparing Media3 native export");
            runExportBlocking(target, aspect, quality, finalName,maxOutputSampleDelayMs,state);
            state.checkpoint(100, "Export complete");
        });

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("projectId", target.id);
        result.put("fileName", finalName);
        return result;
    }

    private void runExportBlocking(ProjectStore.Project project, String aspect, String quality, String fileName, JobManager.Job state) throws Exception {
        runExportBlocking(project,aspect,quality,fileName,null,state);
    }

    private void runExportBlocking(ProjectStore.Project project, String aspect, String quality, String fileName,Long maxOutputSampleDelayMs,JobManager.Job state) throws Exception {
        File dir = new File(getCacheDir(), "native_exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create export workspace");
        File temp = new File(dir, "tmp_" + System.currentTimeMillis() + ".mp4");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicReference<File> completed = new AtomicReference<>(null);

        ui.post(() -> {
            NativeRenderEngine.Listener listener=new NativeRenderEngine.Listener() {
                @Override public void onProgress(int progress, String detail) {
                    state.checkpoint(Math.max(20, Math.min(96, 20 + (int) (progress * .76))), detail);
                }

                @Override public void onCompleted(File file, JSONObject result) {
                    completed.set(file);
                    latch.countDown();
                }

                @Override public void onError(String message) {
                    error.set(message);
                    latch.countDown();
                }
            };
            activeRenderHandle=maxOutputSampleDelayMs==null
                    ?renderEngine.export(project,temp,aspect,quality,listener)
                    :renderEngine.export(project,temp,aspect,quality,maxOutputSampleDelayMs,listener);
        });

        while (!latch.await(550, TimeUnit.MILLISECONDS)) {
            if (Thread.currentThread().isInterrupted()) {
                if (activeRenderHandle != null) activeRenderHandle.cancel();
                throw new InterruptedException();
            }
        }

        if (error.get() != null) throw new IllegalStateException(error.get());
        File ready = completed.get();
        if (ready == null || !ready.exists() || ready.length() == 0) throw new IllegalStateException("Native export produced no file");

        state.checkpoint(97, "Publishing to Movies/VideoStudio");
        Uri publicUri = publishExport(ready, fileName);
        ProjectStore.Project fresh = store.get(project.id);
        if (fresh != null) {
            fresh.latestExportUri = publicUri.toString();
            fresh.latestExportName = fileName;
            fresh.latestExportAt = System.currentTimeMillis();
            state.checkpoint(98, "Registering rendered MP4 in Media Bin");
            store.registerGeneratedAsset(fresh, publicUri, fileName, "final_render", false);
            previewSnapshots.publish(new PreviewSnapshotStore.Snapshot(
                    "local-final-" + fresh.latestExportAt,
                    fresh.id,
                    fresh.updatedAt,
                    fresh.latestExportAt,
                    publicUri.toString(),
                    "1080p",
                    "final",
                    state.id,
                    fresh.latestExportAt
            ));
        }
        syncProtocolState();
        ui.post(() -> {
            activeProject = store.get(project.id);
            refreshCurrent();
            Toast.makeText(this, "Exported to Movies/VideoStudio • " + fileName, Toast.LENGTH_LONG).show();
        });
        if (!ready.delete()) { /* cache cleanup is best-effort */ }
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
        try (InputStream in = new java.io.FileInputStream(file);
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

    private void queueAnalysisCommand(JSONObject command, JSONObject parameters) {
        if(parameters.has("mode")){queueAudioEnvelopeCommand(command,parameters);return;}
        try {
            if (activeProject == null || activeProject.assets.isEmpty()) throw new IllegalArgumentException("No media in the active project");
            String requested = parameters.optString("assetId", "");
            ProjectStore.Asset asset = requested.isEmpty() ? null : activeProject.asset(requested);
            if (asset == null) {
                for (ProjectStore.Asset candidate : activeProject.assets) {
                    if (candidate.mime != null && candidate.mime.startsWith("video/")) { asset = candidate; break; }
                }
            }
            if (asset == null) throw new IllegalArgumentException("No video asset available for analysis");
            ProjectStore.Asset target = asset;
            int frames = parameters.optInt("frames", 12);
            long startMs = parameters.has("startMs") ? parameters.optLong("startMs") : (long) (parameters.optDouble("start", 0) * 1000);
            long endMs = parameters.has("endMs") ? parameters.optLong("endMs") : (long) (parameters.optDouble("end", 0) * 1000);

            jobs.submit("Analyse • " + target.name, JobManager.Kind.LIGHT, state -> {
                try {
                    state.checkpoint(8, "Sampling frames locally");
                    JSONObject result = mediaAnalyzer.analyse(target, frames, startMs, endMs);
                    state.checkpoint(100, "Analysis complete");
                    protocol.complete(command, result, "completed");
                } catch (Exception error) {
                    JSONObject failed = new JSONObject();
                    failed.put("ok", false);
                    failed.put("error", error.getMessage() == null ? "Analysis failed" : error.getMessage());
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
            protocol.complete(command, failed, "failed");
        }
    }

    /** Explicit mode uses the same read-only worker as the ordinary Audio Inspector. */
    private void queueAudioEnvelopeCommand(JSONObject command,JSONObject parameters){
        final CountDownLatch admission=new CountDownLatch(1);
        final AtomicReference<AudioEnvelopeCompletion> completion=new AtomicReference<>();
        JobManager.Job submitted=null;
        try{
            // Public parameters contain no trusted transport metadata. Every key
            // reaches strict validation, including host-supplied underscore keys.
            final JSONObject request=NativeAudioEnvelope.validate(new JSONObject(parameters.toString()));
            final android.content.Context app=getApplicationContext();
            synchronized(audioEnvelopeCommands){
                if(audioEnvelopeCommandsClosed)throw new InterruptedException("The audio command executor closed before admission");
                submitted=jobs.submit("Audio envelope • "+request.getString("assetId"),JobManager.Kind.LIGHT,state->{
                    try{
                        admission.await();AudioEnvelopeCompletion bound=completion.get();
                        if(bound==null||bound.sent.get())throw new InterruptedException("Audio command was not admitted");
                        JSONObject result=NativeAudioEnvelope.analyse(app,request,(percent,detail)->state.checkpoint("Audio envelope",percent,detail));
                        state.setResult(result);bound.send(result,"completed");
                    }catch(Exception error){
                        JSONObject failed=audioEnvelopeFailure(error);state.setResult(failed);
                        AudioEnvelopeCompletion bound=completion.get();if(bound!=null)bound.send(failed,"failed");throw error;
                    }
                });
                AudioEnvelopeCompletion bound=new AudioEnvelopeCompletion(new JSONObject(command.toString()),protocol,submitted,audioEnvelopeCommands);
                completion.set(bound);audioEnvelopeCommands.put(submitted.id,bound);
                if(audioEnvelopeCompletions==null){
                    audioEnvelopeCompletions=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    audioEnvelopeCompletions.scheduleWithFixedDelay(()->{
                        for(AudioEnvelopeCompletion pending:audioEnvelopeCommands.values())pending.poll();
                    },0,100,TimeUnit.MILLISECONDS);
                }
            }
        }catch(Exception error){
            if(submitted!=null)jobs.cancel(submitted.id);
            AudioEnvelopeCompletion bound=completion.get();
            if(bound!=null)bound.send(audioEnvelopeFailure(error),"failed");else protocol.complete(command,audioEnvelopeFailure(error),"failed");
        }finally{admission.countDown();}
    }

    /** Complete queued cancellation even when Future.cancel prevents the work lambda from running. */
    private static final class AudioEnvelopeCompletion {
        final JSONObject command;final AppProtocol protocol;final JobManager.Job job;
        final java.util.concurrent.ConcurrentHashMap<String,AudioEnvelopeCompletion> bindings;
        final java.util.concurrent.atomic.AtomicBoolean sent=new java.util.concurrent.atomic.AtomicBoolean();
        AudioEnvelopeCompletion(JSONObject command,AppProtocol protocol,JobManager.Job job,
                java.util.concurrent.ConcurrentHashMap<String,AudioEnvelopeCompletion> bindings){
            this.command=command;this.protocol=protocol;this.job=job;this.bindings=bindings;
        }
        synchronized void send(JSONObject result,String status){
            if(!sent.compareAndSet(false,true))return;
            // Cancellation already published by JobManager cannot be turned into
            // success by a late decoder completion.
            if(JobManager.STATE_CANCELLED.equals(job.state)){result=audioEnvelopeFailure(new InterruptedException("Audio envelope cancelled; source retained"));status="failed";}
            try{protocol.complete(command,result,status);}finally{bindings.remove(job.id,this);}
        }
        synchronized void poll(){
            if(sent.get()||!JobManager.isTerminal(job.state))return;
            if(JobManager.STATE_CANCELLED.equals(job.state)){send(audioEnvelopeFailure(new InterruptedException("Audio envelope cancelled before completion; source retained")),"failed");return;}
            JSONObject result=job.result;
            if(result!=null)send(result,result.optBoolean("ok",false)?"completed":"failed");
            else send(audioEnvelopeFailure(new IllegalStateException("Audio analysis ended without a measured result: "+job.detail)),"failed");
        }
        synchronized void cancelAndComplete(JobManager jobs){
            // Wait for any already-started durable reply before cancelling its
            // worker, rather than interrupting result persistence during close.
            if(sent.get())return;if(jobs!=null)jobs.cancel(job.id);poll();
        }
    }

    private void closeAudioEnvelopeCommands(){
        synchronized(audioEnvelopeCommands){
            audioEnvelopeCommandsClosed=true;
            for(AudioEnvelopeCompletion pending:new ArrayList<>(audioEnvelopeCommands.values())){
                pending.cancelAndComplete(jobs);
            }
            if(audioEnvelopeCompletions!=null)audioEnvelopeCompletions.shutdown();
        }
    }

    private static JSONObject audioEnvelopeFailure(Throwable error){
        JSONObject result=new JSONObject();try{
            result=NativeAudioEnvelope.diagnostics(error);result.put("ok",false).put("mode","audio_envelope")
                    .put("code",NativeAudioEnvelope.code(error)).put("error",error.getMessage()==null?"Audio envelope failed":error.getMessage());
        }catch(Exception ignored){}return result;
    }

    private JSONObject applyCreatorPreset(JSONObject parameters) throws Exception {
        if (activeProject == null || activeProject.clips.isEmpty()) throw new IllegalArgumentException("No clips");
        String preset = parameters.optString("preset", "cinematic");
        String motion = parameters.optString("motion", "");
        String transition = parameters.optString("transition", "");
        String font = parameters.optString("font", "");
        boolean all = parameters.optBoolean("allClips", true);
        int selectedIndex = Math.max(0, parameters.optInt("clipIndex", 0));

        int changed = 0;
        for (int i = 0; i < activeProject.clips.size(); i++) {
            if (!all && i != selectedIndex) continue;
            ProjectStore.Clip c = activeProject.clips.get(i);
            c.effects.put("effectPreset", preset);
            if (!motion.isEmpty()) c.effects.put("motionPreset", motion);
            if (!transition.isEmpty()) c.transition = transition;
            if (!font.isEmpty()) c.effects.put("fontFamily", font);
            changed++;
        }
        store.save(activeProject);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("changedClips", changed);
        result.put("preset", preset);
        return result;
    }

    private void sharePairing() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, protocol.pairingMessage());
        if (getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt") != null) {
            send.setPackage("com.openai.chatgpt");
        }
        try {
            startActivity(send);
        } catch (Exception error) {
            send.setPackage(null);
            startActivity(Intent.createChooser(send, "Connect VideoStudio to ChatGPT"));
        }
    }

    private String availableAppKey() {
        String key=protocol==null?"":protocol.appKey();
        if(key.isEmpty())Toast.makeText(this,"App identity needs recovery before a key can be shared",Toast.LENGTH_LONG).show();
        return key;
    }

    private void shareAppKey() {
        String key=availableAppKey();if(key.isEmpty())return;
        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,key);
        startActivity(Intent.createChooser(send,"Share Video studio app key"));
    }

    private void copyAppKey() {
        String key=availableAppKey();if(key.isEmpty())return;
        ClipData clip=ClipData.newPlainText("Video studio app key",key);
        if(android.os.Build.VERSION.SDK_INT>=33){
            android.os.PersistableBundle sensitive=new android.os.PersistableBundle();
            sensitive.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true);
            clip.getDescription().setExtras(sensitive);
        }
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this,"App key copied",Toast.LENGTH_SHORT).show();
    }

    private void showLatestBackupResult() {
        String id=getSharedPreferences("videostudio_agent_mailbox_v1",MODE_PRIVATE).getString("lastCommandId","");
        if(id.isEmpty()){
            Toast.makeText(this,"No backup task has arrived yet",Toast.LENGTH_SHORT).show();return;
        }
        new Thread(()->{
            try{
                JSONObject envelope=NativeAgentMailbox.readSavedReceipt(getApplicationContext(),id);
                JSONObject receipt=envelope==null?null:NativeAgentMailboxCrypto.decodeReceipt(protocol.mailboxOwnerKey(),id,envelope);
                String message=receipt==null?"This task has not produced a saved result yet.":receipt.toString(2);
                if(message.length()>16000)message=message.substring(0,16000)+"\n… Full encrypted result retained on this phone.";
                final String detail=message;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    new AlertDialog.Builder(this).setTitle("Backup task result").setMessage(detail)
                            .setPositiveButton("Close",null).show();
                });
            }catch(Exception error){
                runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,"Saved result could not be authenticated",Toast.LENGTH_LONG).show();});
            }
        },"video-studio-result-reader").start();
    }

    @Override
    public void onConnection(boolean connected, String detail) {
        if (connectionPill != null) {
            connectionPill.setText(serviceConnectionText());
            connectionPill.setTextColor(prefs.getBoolean("control_service_online", false) ? Color.rgb(74, 255, 172) : C_MUTED);
        }
    }

    @Override
    public void onCommand(JSONObject command) {
        String action = command.optString("action");
        JSONObject p = command.optJSONObject("parameters");
        if (p == null) p = new JSONObject();

        if (!isAllowed(action, p)) {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", false);
                result.put("error", "Blocked by local VideoStudio permission mode: " + permissionMode());
            } catch (Exception ignored) {}
            protocol.complete(command, result, "denied");
            return;
        }

        try {
            JSONObject result = new JSONObject();
            switch (action) {
                case "ping":
                case "get_state":
                    result = stateJson();
                    result.put("ok", true);
                    break;
                case "create_project":
                    activeProject = store.create(p.optString("name", "ChatGPT Project"));
                    selectedClip = null;
                    result.put("ok", true);
                    result.put("projectId", activeProject.id);
                    refreshCurrent();
                    break;
                case "select_project": {
                    ProjectStore.Project target = store.get(p.optString("projectId"));
                    if (target == null) throw new IllegalArgumentException("Project not found");
                    activeProject = target;
                    store.setActive(target.id);
                    selectedClip = target.clips.isEmpty() ? null : target.clips.get(0);
                    result.put("ok", true);
                    result.put("projectId", target.id);
                    refreshCurrent();
                    break;
                }
                case "apply_edit_plan":
                    result = applyRemotePlan(p);
                    refreshCurrent();
                    break;
                case "apply_tool": {
                    int index = p.optInt("clipIndex", 0);
                    if (activeProject == null || index < 0 || index >= activeProject.clips.size()) throw new IllegalArgumentException("Clip not found");
                    selectedClip = activeProject.clips.get(index);
                    String tool = p.optString("tool");
                    applyRemoteTool(tool, p.optJSONObject("settings"));
                    result.put("ok", true);
                    result.put("clipIndex", index);
                    result.put("tool", tool);
                    refreshCurrent();
                    break;
                }
                case "preview_project": {
                    if (!"editor".equals(currentScreen)) showEditor();
                    boolean admitted=playStudioTimeline();
                    result.put("ok",admitted);
                    result.put("admitted",admitted);
                    result.put("playbackRequested",admitted);
                    result.put("previewMode","edited_composition");
                    result.put("previewing",admitted&&player().isPlaying());
                    if(!admitted)result.put("error",studioPreviewError);
                    break;
                }
                case "analyse_media":
                    queueAnalysisCommand(command, p);
                    return;
                case "prompt_video":
                    result = queuePromptVideo(p);
                    refreshCurrent();
                    break;
                case "export_project":
                    result = queueNativeExport(
                            activeProject,
                            p.optString("aspect", "9:16"),
                            p.optString("quality", "1080p"),
                            p.optString("fileName", "VideoStudio_" + System.currentTimeMillis() + ".mp4"),
                            NativeExportOptions.read(p)
                    );
                    break;
                case "creator_preset":
                    result = applyCreatorPreset(p);
                    refreshCurrent();
                    break;
                case "autonomous_edit": {
                    JSONObject planResult = p.optJSONArray("clips") == null ? new JSONObject().put("ok", true) : applyRemotePlan(p);
                    if (p.has("preset")) applyCreatorPreset(p);
                    result.put("ok", true);
                    result.put("plan", planResult);
                    if (p.optBoolean("render", false)) {
                        JSONObject export = queueNativeExport(
                                activeProject,
                                p.optString("aspect", "9:16"),
                                p.optString("quality", "1080p"),
                                p.optString("fileName", "VideoStudio_AI_Edit_" + System.currentTimeMillis() + ".mp4")
                        );
                        result.put("export", export);
                    }
                    refreshCurrent();
                    break;
                }
                case "cancel_job": {
                    boolean cancelled = jobs.cancel(p.optString("jobId"));
                    if (cancelled && activeRenderHandle != null) activeRenderHandle.cancel();
                    result.put("ok", cancelled);
                    break;
                }
                case "cancel_all_jobs":
                    if (activeRenderHandle != null) activeRenderHandle.cancel();
                    result.put("ok", true);
                    result.put("cancelled", jobs.cancelAll());
                    break;
                case "delete_project": {
                    String id = p.optString("projectId", activeProject == null ? "" : activeProject.id);
                    if (id.isEmpty()) throw new IllegalArgumentException("Project ID is required");
                    store.delete(id);
                    activeProject = store.active();
                    selectedClip = activeProject == null || activeProject.clips.isEmpty() ? null : activeProject.clips.get(0);
                    result.put("ok", true);
                    result.put("deletedProjectId", id);
                    refreshCurrent();
                    break;
                }
                case "import_url":
                    result = queueUrlImport(p.optString("url"), p.optString("name", "ChatGPT import"));
                    break;
                case "import_attachment":
                    result = queueUrlImport(p.optString("sourceUrl"), p.optString("name", "ChatGPT attachment"));
                    break;
                case "import_chat_file":
                    result = queuePrivateHandoffImport(
                            p.optString("handoffId"),
                            p.optString("name", "ChatGPT import"),
                            p.optString("mime", ""),
                            p.optString("projectId", "")
                    );
                    break;
                default:
                    result.put("ok", false);
                    result.put("error", "Native v3 does not implement action: " + action);
                    protocol.complete(command, result, "failed");
                    return;
            }
            syncProtocolState();
            protocol.complete(command, result, result.optBoolean("ok", false) ? "completed" : "failed");
        } catch (Exception error) {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", false);
                result.put("error", error.getMessage() == null ? "Command failed" : error.getMessage());
            } catch (Exception ignored) {}
            protocol.complete(command, result, "failed");
        }
    }

    private JSONObject applyRemotePlan(JSONObject p) throws Exception {
        if (activeProject == null) throw new IllegalArgumentException("No active project");
        JSONArray clips = p.optJSONArray("clips");
        if (clips == null || clips.length() == 0) throw new IllegalArgumentException("clips are required");
        ArrayList<ProjectStore.Clip> next = new ArrayList<>();
        for (int i = 0; i < clips.length() && i < 80; i++) {
            JSONObject raw = clips.optJSONObject(i);
            if (raw == null) continue;
            String assetId = raw.optString("assetId");
            ProjectStore.Asset asset = activeProject.asset(assetId);
            if (asset == null) throw new IllegalArgumentException("Unknown asset " + assetId);
            ProjectStore.Clip c = new ProjectStore.Clip();
            c.id = UUID.randomUUID().toString();
            c.assetId = assetId;
            c.inMs = raw.has("inMs") ? raw.optLong("inMs") : (long) (raw.optDouble("start", 0) * 1000);
            c.outMs = raw.has("outMs") ? raw.optLong("outMs") : (long) (raw.optDouble("end", asset.durationMs / 1000d) * 1000);
            c.inMs = Math.max(0, c.inMs);
            c.outMs = Math.max(c.inMs + 100, Math.min(asset.durationMs > 0 ? asset.durationMs : c.outMs, c.outMs));
            c.speed = (float) Math.max(.5, Math.min(2, raw.optDouble("speed", 1)));
            c.volume = (float) Math.max(0, Math.min(2, raw.optDouble("volume", 1)));
            c.transition = raw.optString("transition", "none");
            c.title = raw.optString("title", "");
            JSONObject effects = raw.optJSONObject("effects");
            c.effects = effects == null ? new JSONObject() : effects;
            next.add(c);
        }
        activeProject.clips.clear();
        activeProject.clips.addAll(next);
        selectedClip = next.isEmpty() ? null : next.get(0);
        store.save(activeProject);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("clipCount", next.size());
        result.put("durationMs", activeProject.outputDurationMs());
        return result;
    }

    private void applyRemoteTool(String tool, JSONObject settings) throws Exception {
        if (selectedClip == null) throw new IllegalArgumentException("No selected clip");
        if (settings == null) settings = new JSONObject();
        switch (tool) {
            case "speed":
            case "slow_motion":
                selectedClip.speed = (float) Math.max(.25, Math.min(4, settings.optDouble("speed", .5)));
                break;
            case "trim":
                selectedClip.inMs = settings.optLong("inMs", selectedClip.inMs);
                selectedClip.outMs = settings.optLong("outMs", selectedClip.outMs);
                break;
            case "green_screen":
                selectedClip.effects.put("chromaKey", true);
                selectedClip.effects.put("chromaColor", settings.optString("color", "#00FF00"));
                selectedClip.effects.put("chromaTolerance", settings.optDouble("tolerance", .18));
                selectedClip.effects.put("spillSuppression", settings.optDouble("spill", .35));
                break;
            case "transition":
                selectedClip.transition = settings.optString("name", "fade");
                break;
            case "motion":
                selectedClip.effects.put("motionPreset", settings.optString("preset", "push_in"));
                selectedClip.effects.put("ease", settings.optString("ease", "easeInOut"));
                break;
            case "effect":
                selectedClip.effects.put("effectPreset", settings.optString("preset", "cinematic"));
                if (settings.has("blur")) selectedClip.effects.put("blur", settings.optDouble("blur"));
                break;
            case "color":
                selectedClip.effects.put("colorPreset", settings.optString("preset", "cinematic"));
                if (settings.has("brightness")) selectedClip.effects.put("brightness", settings.optDouble("brightness"));
                if (settings.has("contrast")) selectedClip.effects.put("contrast", settings.optDouble("contrast"));
                if (settings.has("saturation")) selectedClip.effects.put("saturationAdjust", settings.optDouble("saturation"));
                if (settings.has("lightness")) selectedClip.effects.put("lightnessAdjust", settings.optDouble("lightness"));
                break;
            case "reframe":
                selectedClip.effects.put("reframe", settings.optString("preset", "9:16_subject_safe"));
                break;
            case "mask":
                selectedClip.effects.put("mask", settings.optString("shape", "rounded_rect"));
                selectedClip.effects.put("maskFeather", settings.optDouble("feather", .08));
                break;
            case "font":
                selectedClip.effects.put("fontFamily", settings.optString("family", "sans-serif-medium"));
                break;
            case "text_animation":
                selectedClip.effects.put("textAnimation", settings.optString("preset", "fade_up"));
                break;
            case "blur":
                selectedClip.effects.put("blur", Math.max(0, Math.min(18, settings.optDouble("sigma", 4))));
                break;
            case "transform":
                if (settings.has("scale")) selectedClip.effects.put("scale", settings.optDouble("scale", 1));
                if (settings.has("rotate")) selectedClip.effects.put("rotate", settings.optDouble("rotate", 0));
                break;
            case "audio_duck":
                selectedClip.effects.put("audioDucking", true);
                selectedClip.effects.put("duckLevel", settings.optDouble("level", .32));
                break;
            case "title":
                selectedClip.title = settings.optString("text", "");
                if (settings.has("font")) selectedClip.effects.put("fontFamily", settings.optString("font"));
                if (settings.has("animation")) selectedClip.effects.put("textAnimation", settings.optString("animation"));
                break;
            case "volume":
                selectedClip.volume = (float) Math.max(0, Math.min(2, settings.optDouble("volume", 1)));
                break;
            default:
                selectedClip.effects.put(tool, settings);
        }
        store.save(activeProject);
    }

    private JSONObject queuePrivateHandoffImport(String handoffId, String name, String mimeHint, String projectId) throws Exception {
        if (handoffId == null || handoffId.trim().isEmpty()) throw new IllegalArgumentException("Missing private handoff ID");
        ProjectStore.Project requested = projectId == null || projectId.isEmpty() ? null : store.get(projectId);
        if (requested == null) requested = activeProject;
        if (requested == null) requested = store.create("ChatGPT Imports");
        ProjectStore.Project project = requested;

        JobManager.Job job = jobs.submit("Private import " + name, JobManager.Kind.LIGHT, state -> {
            state.progress = 4;
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            String safe = name == null ? "chat_import.mp4" : name.replaceAll("[^a-zA-Z0-9._-]+", "_");
            if (safe.isEmpty()) safe = "chat_import_" + System.currentTimeMillis() + ".mp4";
            File file = new File(dir, System.currentTimeMillis() + "_" + safe);

            HttpURLConnection c = protocol.openPrivateHandoff(handoffId);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Private attachment transfer failed: HTTP " + code);
            String mime = mimeHint == null || mimeHint.isEmpty() ? c.getContentType() : mimeHint;
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            long expected = c.getContentLengthLong();

            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                long bytes = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buf, 0, n);
                    bytes += n;
                    if (expected > 0) state.progress = Math.min(94, 5 + (int) (88d * bytes / expected));
                    else state.progress = Math.min(90, 8 + (int) Math.min(82, bytes / (1024 * 1024)));
                    state.detail = (bytes / 1024 / 1024) + " MB securely streamed";
                }
            } finally {
                c.disconnect();
            }

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
            syncProtocolState();
            state.progress = 100;
            state.detail = "Imported into " + project.name;
            ui.post(() -> {
                activeProject = store.get(project.id);
                if (activeProject != null && !activeProject.clips.isEmpty()) {
                    selectedClip = activeProject.clips.get(activeProject.clips.size() - 1);
                }
                refreshCurrent();
                Toast.makeText(this, "ChatGPT file imported: " + name, Toast.LENGTH_SHORT).show();
            });
        });

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("queued", true);
        result.put("jobId", job.id);
        result.put("handoffId", handoffId);
        result.put("projectId", project.id);
        result.put("name", name);
        return result;
    }

    private JSONObject queueUrlImport(String url, String name) throws Exception {
        if (url == null || !url.startsWith("https://")) throw new IllegalArgumentException("Only HTTPS imports are allowed");
        if (activeProject == null) activeProject = store.create("ChatGPT Imports");
        ProjectStore.Project project = activeProject;
        JobManager.Job job = jobs.submit("Import " + name, JobManager.Kind.LIGHT, state -> {
            state.progress = 5;
            File dir = new File(getFilesDir(), "imports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create import directory");
            String safe = name.replaceAll("[^a-zA-Z0-9._-]+", "_");
            if (safe.isEmpty()) safe = "import_" + System.currentTimeMillis() + ".mp4";
            File file = new File(dir, System.currentTimeMillis() + "_" + safe);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "VideoStudio-Android/3.0.0");
            String mime = c.getContentType();
            if (mime == null) mime = "video/mp4";
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long bytes = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buf, 0, n);
                    bytes += n;
                    state.detail = (bytes / 1024 / 1024) + " MB received";
                    state.progress = Math.min(90, 10 + (int) Math.min(80, bytes / (1024 * 1024)));
                }
            }
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
            syncProtocolState();
            ui.post(this::refreshCurrent);
        });
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("queued", true);
        result.put("jobId", job.id);
        return result;
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

    private boolean isAllowed(String action, JSONObject parameters) {
        String lower = action == null ? "" : action.toLowerCase(Locale.US);
        // Hard boundary: no MCP mode may enumerate or browse the user's Gallery.
        if (lower.contains("gallery") || lower.contains("media_library") || lower.contains("photo_library")) return false;

        if ("one_file".equals(permissionMode())) {
            String allowed = prefs.getString(KEY_FILE, "");
            if (allowed.isEmpty()) return false;
            if ("apply_tool".equals(action)) {
                int index = parameters.optInt("clipIndex", -1);
                return activeProject != null && index >= 0 && index < activeProject.clips.size()
                        && allowed.equals(activeProject.clips.get(index).assetId);
            }
            return "ping".equals(action)
                    || "get_state".equals(action)
                    || "self_test".equals(action)
                    || "job_status".equals(action)
                    || "activity_note".equals(action)
                    || "preview_project".equals(action)
                    || "analyse_media".equals(action)
                    || "export_project".equals(action)
                    || "cancel_job".equals(action)
                    || "cancel_all_jobs".equals(action)
                    || "stop_all".equals(action);
        }
        return true;
    }

    private JSONObject stateJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("deviceId", protocol.deviceId());
            out.put("appVersion", AppProtocol.APP_VERSION);
            out.put("protocolVersion", AppProtocol.PROTOCOL_VERSION);
            out.put("mcpEndpointVersion", "v3");
            out.put("nativeAgent", "videostudio-v3");
            out.put("directAttachmentIngest", true);
            out.put("localEngineOwnsProjects", true);
            out.put("portraitAnimationEngine", "v3.2-articulated-parallax");
            out.put("onDevicePortraitAi", true);
            out.put("nativeApp", true);
            out.put("permissionMode", permissionMode());
            out.put("controlPaused", protocol.isControlPaused());
            out.put("galleryAccess", false);
            out.put("galleryBoundary", "MCP v3 cannot list, browse or enumerate Gallery media. Only Android-picker selections, VideoStudio-owned media and explicit ChatGPT attachments are usable.");
            out.put("projects", store.summaries().optJSONArray("projects"));
            out.put("projectStorage", store.storageBackend());
            if (activeProject != null) {
                out.put("activeProjectId", activeProject.id);
                out.put("activeProjectName", activeProject.name);
                out.put("clipCount", activeProject.clips.size());
                out.put("assetCount", activeProject.assets.size());
                out.put("durationMs", activeProject.outputDurationMs());
                out.put("latestExportUri", activeProject.latestExportUri);
                out.put("latestExportName", activeProject.latestExportName);
                out.put("sourcePrompt", activeProject.sourcePrompt);
                JSONArray assets = new JSONArray();
                for (ProjectStore.Asset a : activeProject.assets) {
                    JSONObject ai = new JSONObject();
                    ai.put("id", a.id);
                    ai.put("name", a.name);
                    ai.put("mime", a.mime);
                    ai.put("durationMs", a.durationMs);
                    assets.put(ai);
                }
                out.put("activeAssets", assets);
            }
            out.put("jobs", jobs.state().optJSONArray("jobs"));
            out.put("workload", jobs.state());
            out.put("creatorCatalog", CreatorCatalog.describe());
            out.put("studioEditing",StudioEditingCapabilities.capabilities());
            out.put("audioAnalysis",NativeAudioEnvelope.capabilities());
            out.put("recentActivity", ActivityLog.recent(this, 30));
            JSONArray caps = new JSONArray();
            String[] values = {
                    "native-ui","mcp-v3-native-agent","persistent-background-control","local-projects","private-app-mcp-v3","direct-chatgpt-attachment-ingest","chat-attachment-handoff-fallback","url-import",
                    "timeline","trim","split","speed","slow-motion","native-frame-analysis","scene-change-sampling",
                    "media3-native-export","layered-media3-animation","articulated-portrait-layers","head-hair-motion","torso-breathing","lower-drape-sway","on-device-person-segmentation","on-device-face-mesh","subject-aware-parallax","multi-keyframe-animation","prompt-to-video","gpu-brightness","gpu-contrast","gpu-hsl","gpu-blur",
                    "gpu-motion","scale","rotate","creator-transition-model","green-screen-model","masks-model",
                    "fonts","text-animation-model","audio-ducking-model","ai-edit-plans","autonomous-edit-and-export",
                    "bounded-multitasking","crash-recovery-checkpoints","durable-command-idempotency","thermal-guard","memory-guard","job-cancel"
            };
            for (String v : values) caps.put(v);
            out.put("capabilities", caps);
        } catch (Exception ignored) {}
        return out;
    }

    private void syncProtocolState() {
        if (protocol != null) protocol.setLocalState(permissionMode(), store.summaries());
        requestServiceSync();
    }

    private void requestServiceSync() {
        try {
            Intent intent = new Intent(this, ControlService.class).setAction(ControlService.ACTION_SYNC);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            prefs.edit()
                    .putLong("control_service_start_requested_at", System.currentTimeMillis())
                    .putString("control_service_requested_app_version", AppProtocol.APP_VERSION)
                    .apply();
        } catch (Exception error) {
            prefs.edit()
                    .putBoolean("control_service_online", false)
                    .putString("control_service_detail", "Native Agent start failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()))
                    .putLong("control_service_heartbeat", System.currentTimeMillis())
                    .apply();
            ActivityLog.add(this, "system", "Native Agent start failed",
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                    "failed", null, null, activeProject == null ? null : activeProject.id);
        }
    }

    private void startServiceWatchdog() {
        if (serviceWatchdog != null) ui.removeCallbacks(serviceWatchdog);
        serviceWatchdog = new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                long heartbeat = prefs.getLong("control_service_heartbeat", 0);
                boolean fresh = System.currentTimeMillis() - heartbeat < 35000;
                String reported = prefs.getString("control_service_app_version", "");
                long reportedGeneration = prefs.getLong("control_service_app_generation", 0);
                int reportedCore = prefs.getInt("control_service_connection_core_version", 0);
                boolean currentGeneration = reportedGeneration == protocol.appGeneration();
                boolean currentCore = reportedCore == McpConnectionCore.CORE_VERSION;
                if (!fresh || !AppProtocol.APP_VERSION.equals(reported) || !currentGeneration || !currentCore) {
                    requestServiceSync();
                }
                if (connectionPill != null) {
                    connectionPill.setText(serviceConnectionText());
                    boolean online = prefs.getBoolean("control_service_online", false)
                            && System.currentTimeMillis() - prefs.getLong("control_service_heartbeat", 0) < 65000
                            && AppProtocol.APP_VERSION.equals(prefs.getString("control_service_app_version", ""))
                            && prefs.getLong("control_service_app_generation", 0) == protocol.appGeneration()
                            && prefs.getInt("control_service_connection_core_version", 0) == McpConnectionCore.CORE_VERSION;
                    connectionPill.setTextColor(online ? Color.rgb(74, 255, 172) : C_MUTED);
                }
                ui.postDelayed(this, 10000);
            }
        };
        ui.post(serviceWatchdog);
    }

    private String serviceConnectionText() {
        long heartbeat = prefs.getLong("control_service_heartbeat", 0);
        boolean online = prefs.getBoolean("control_service_online", false);
        String detail = prefs.getString("control_service_detail", "");
        boolean fresh = System.currentTimeMillis() - heartbeat < 65000;
        String serviceVersion = prefs.getString("control_service_app_version", "");
        long serviceGeneration = prefs.getLong("control_service_app_generation", 0);
        int coreVersion = prefs.getInt("control_service_connection_core_version", 0);
        if (protocol != null && protocol.isControlPaused()) return "●  ChatGPT control paused • tap Agent to resume";
        if (online && fresh
                && AppProtocol.APP_VERSION.equals(serviceVersion)
                && serviceGeneration == protocol.appGeneration()
                && coreVersion == McpConnectionCore.CORE_VERSION) {
            return "●  " + (detail == null || detail.isEmpty() ? "VideoStudio stable MCP online" : detail)
                    + " • app " + serviceVersion + " • gen " + serviceGeneration;
        }
        if (!serviceVersion.isEmpty() && (!AppProtocol.APP_VERSION.equals(serviceVersion)
                || serviceGeneration != protocol.appGeneration()
                || coreVersion != McpConnectionCore.CORE_VERSION)) {
            return "◌  MCP control plane available • rebinding native executor • app " + AppProtocol.APP_VERSION
                    + " • gen " + protocol.appGeneration();
        }
        return "◌  MCP control plane available • native executor starting • app " + AppProtocol.APP_VERSION;
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestServiceSync();
        startServiceWatchdog();
        if (connectionPill != null) {
            connectionPill.setText(serviceConnectionText());
            connectionPill.setTextColor(prefs.getBoolean("control_service_online", false) ? Color.rgb(74, 255, 172) : C_MUTED);
        }
        if (activeProject != null) {
            ProjectStore.Project latest = store.get(activeProject.id);
            if (latest != null) activeProject = latest;
        }
        if ("editor".equals(currentScreen)) showEditor();
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

    private void refreshCurrent() {
        ProjectStore.Project storeActive = store.active();
        boolean currentEmpty = activeProject == null
                || (activeProject.assets.isEmpty() && activeProject.clips.isEmpty());
        if (storeActive != null && ExecutionTruthPolicy.shouldAdoptStoreActive(
                currentEmpty,
                activeProject == null ? "" : activeProject.id,
                storeActive.id)) {
            activeProject = storeActive;
            selectedClip = storeActive.clips.isEmpty() ? null : storeActive.clips.get(0);
        } else {
            activeProject = activeProject == null ? storeActive : store.get(activeProject.id);
        }
        if ("editor".equals(currentScreen)) showEditor();
        else if ("home".equals(currentScreen)) showHome();
        else if ("control".equals(currentScreen)) showControl();
        else if ("tools".equals(currentScreen)) showTools();
    }

    private String effectSummary(JSONObject effects) {
        ArrayList<String> names = new ArrayList<>();
        if (effects.optBoolean("chromaKey")) names.add("Green Screen");
        if (effects.has("motionPreset")) names.add("Motion");
        if (effects.has("effectPreset")) names.add(effects.optString("effectPreset"));
        if (effects.has("colorPreset")) names.add("Colour");
        if (effects.has("mask")) names.add("Mask");
        if (effects.has("speedRamp")) names.add("Speed Ramp");
        if (effects.has("reframe")) names.add("Reframe");
        if (effects.has("motionBlur")) names.add("Motion Blur");
        if (effects.has("blur")) names.add("Blur");
        if (effects.has("fontFamily")) names.add(effects.optString("fontFamily"));
        if (effects.has("textAnimation")) names.add("Text FX");
        return names.isEmpty() ? "Effect" : String.join(" • ", names);
    }

    private EditText numberInput(float value) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(Locale.US, "%.2f", value));
        input.setTextColor(C_TEXT);
        return input;
    }

    private ScrollView baseScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);
        return scroll;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout card(boolean hero) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(C_CARD, StudioUi.BORDER, dp(16)));
        return card;
    }

    private TextView title(String value, int sp) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(C_TEXT);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView body(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(C_MUTED);
        t.setTextSize(13);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private TextView section(String value) {
        TextView t = title(value, 18);
        t.setPadding(dp(2), dp(22), dp(2), dp(10));
        return t;
    }

    private TextView accent(String value, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView accentPill(String value) {
        TextView t = accent(value, C_TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        t.setBackground(rounded(C_PURPLE, C_BLUE, dp(20)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private Button compactButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        StudioUi.styleButton(b,false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button neonButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        StudioUi.styleButton(b,true);
        return b;
    }

    private View navButton(String icon, String label, Runnable action) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setMinimumHeight(dp(48));
        box.setPadding(0,dp(4),0,dp(4));
        box.setContentDescription(label);
        box.setFocusable(true);
        TextView i = title(icon, "+".equals(icon) ? 28 : 20);
        i.setTextColor("+".equals(icon) ? C_CYAN : C_TEXT);
        i.setGravity(Gravity.CENTER);
        TextView l = body(label);
        l.setTextSize(10);
        l.setGravity(Gravity.CENTER);
        box.addView(i);
        box.addView(l);
        box.setOnClickListener(v -> action.run());
        return box;
    }

    private View actionTile(String icon, String label, int color, Runnable action) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(14), dp(8), dp(12));
        box.setBackground(rounded(C_CARD_2, Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)), dp(15)));
        TextView i = title(icon, 24);
        i.setTextColor(color);
        i.setGravity(Gravity.CENTER);
        TextView l = title(label, 11);
        l.setGravity(Gravity.CENTER);
        box.addView(i);
        box.addView(l);
        box.setOnClickListener(v -> action.run());
        return box;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable neonCard() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(10, 79, 91), Color.rgb(32, 28, 88), Color.rgb(71, 20, 91)});
        d.setCornerRadius(dp(18));
        d.setStroke(dp(1), C_CYAN);
        return d;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    }

    private LinearLayout.LayoutParams weightWithMargin() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(92), 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int top, int bottom, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? ViewGroup.LayoutParams.MATCH_PARENT : w, h < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : h);
        p.setMargins(left, top, right, bottom);
        return p;
    }

    private String time(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private String trimFloat(float f) {
        if (Math.abs(f - Math.round(f)) < .01) return Integer.toString(Math.round(f));
        return String.format(Locale.US, "%.2f", f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

