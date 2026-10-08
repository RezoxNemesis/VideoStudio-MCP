package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final int PICK_EXPORT_DESTINATION = 1203;
    private JSONObject pendingExportSettings;
    private String pendingExportProjectId;
    private static final int C_BG = Color.rgb(5, 8, 18);
    private static final int C_CARD = Color.rgb(13, 20, 37);
    private static final int C_CARD_2 = Color.rgb(17, 27, 48);
    private static final int C_TEXT = Color.rgb(246, 248, 255);
    private static final int C_MUTED = Color.rgb(157, 170, 196);
    private static final int C_CYAN = Color.rgb(34, 220, 255);
    private static final int C_BLUE = Color.rgb(58, 114, 255);
    private static final int C_PURPLE = Color.rgb(130, 70, 255);
    private static final int C_MAGENTA = Color.rgb(235, 68, 255);
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
    private TextView connectionPill;
    private ProjectStore.Project activeProject;
    private ProjectStore.Clip selectedClip;
    private LiveEditPlayer livePlayer;
    private EditorEngine editor;
    private StudioPreviewMonitor monitor;
    private StudioTimelineView timelineView;
    private long editorPlayhead;
    private Runnable playbackTick;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable activityRefresh;
    private Runnable serviceWatchdog;
    private boolean timelinePreviewRunning;
    private String currentScreen = "home";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(C_BG);
        window.setNavigationBarColor(C_BG);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateAutonomyDefaultOnce();
        store = new ProjectStore(this);
        jobs = new JobManager(this);
        renderEngine = new NativeRenderEngine(this);
        promptVideoEngine = new PromptVideoEngine(this);
        mediaAnalyzer = new NativeMediaAnalyzer(this);
        driveWorkspace = new DriveWorkspaceProvider(this);
        previewSnapshots = new PreviewSnapshotStore(this);
        proxyManager = new ProxyManager(this, store, jobs);
        livePlayer = new LiveEditPlayer(this);
        editor = new EditorEngine(store);
        monitor = new StudioPreviewMonitor(this, livePlayer);
        activeProject = store.active();
        protocol = new AppProtocol(this, this);
        syncProtocolState();

        setContentView(buildShell());
        showHome();
        handleMcpRebindIntent(getIntent());
        requestServiceSync();
        startServiceWatchdog();
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
        timelinePreviewRunning = false;
        if (activityRefresh != null) ui.removeCallbacks(activityRefresh);
        if (serviceWatchdog != null) ui.removeCallbacks(serviceWatchdog);
        if (playbackTick != null) ui.removeCallbacks(playbackTick);
        if (monitor != null) monitor.release();
        if (livePlayer != null) livePlayer.release();
        if (activeRenderHandle != null) activeRenderHandle.cancel();
        if (protocol != null) protocol.stop();
        if (jobs != null) jobs.shutdown();
        super.onDestroy();
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
        nav.addView(navButton("✦", "AI Tools", () -> showTools()), weight());
        nav.addView(navButton("⚙", "Control", () -> showControl()), weight());
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        return root;
    }

    private void setScreen(View view, String name) {
        if (activityRefresh != null) ui.removeCallbacks(activityRefresh);
        if (playbackTick != null) ui.removeCallbacks(playbackTick);
        currentScreen = name;
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showHome() {
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

    private void showEditor() {
        if (activeProject == null) createProject("Untitled Project");
        ProjectStore.Project latest = store.get(activeProject.id);
        if (latest != null) activeProject = latest;
        if (selectedClip != null) selectedClip = activeProject.clip(selectedClip.id);
        if (selectedClip == null && !activeProject.clips.isEmpty()) selectedClip = activeProject.clips.get(0);
        ScrollView scroll = baseScroll();
        LinearLayout box = column(); box.setPadding(dp(12), dp(8), dp(12), dp(20)); scroll.addView(box);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = title(activeProject.name, 20);
        heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(accent("Saved · r" + activeProject.revision, C_CYAN)); box.addView(heading);
        HorizontalScrollView toolbar = new HorizontalScrollView(this);
        LinearLayout top = new LinearLayout(this);
        addEditorButton(top,"Import",this::pickMedia);
        addEditorButton(top,"Undo",()->historyEditor(false));
        addEditorButton(top,"Redo",()->historyEditor(true));
        addEditorButton(top,"Media",this::showMediaBinDialog);
        addEditorButton(top,"Inspector",this::showInspectorDialog);
        addEditorButton(top,"Export",this::showExportPage);
        toolbar.addView(top);box.addView(toolbar,margins(-1,dp(48),dp(6),dp(6),0,0));
        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);viewer.setContentDescription("VideoStudio preview monitor");
        monitor.attach(viewer);
        int previewHeight=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?dp(210):dp(245);
        box.addView(viewer,margins(-1,previewHeight,0,0,0,0));
        TextView clock = accent("00:00:00:00 · Source · full/proxy",C_MUTED);
        box.addView(clock,margins(-1,-2,dp(6),dp(3),0,0));
        HorizontalScrollView transportScroll=new HorizontalScrollView(this);
        LinearLayout transport=new LinearLayout(this);
        addEditorButton(transport,"◀ Frame",()->stepEditorFrame(-1));
        addEditorButton(transport,"−5s",()->seekEditor(Math.max(0,editorPlayhead-5000)));
        addEditorButton(transport,"Play / Pause",()->{
            if(monitor.isPlaying()||monitor.isPreparingToPlay())monitor.pause();else previewTimeline();
        });
        addEditorButton(transport,"+5s",()->seekEditor(TimelineMath.add(editorPlayhead,5000)));
        addEditorButton(transport,"Frame ▶",()->stepEditorFrame(1));
        addEditorButton(transport,"Source",this::previewSelectedClip);
        addEditorButton(transport,"Program",()->monitor.showProgram(activeProject,editorPlayhead,false));
        addEditorButton(transport,"Fullscreen",()->showFullscreenPreview(viewer));
        transportScroll.addView(transport);box.addView(transportScroll,margins(-1,dp(46),0,dp(7),0,0));
        HorizontalScrollView timelineScroll=new HorizontalScrollView(this);
        timelineScroll.setHorizontalScrollBarEnabled(true);
        timelineView=new StudioTimelineView(this);timelineView.setProject(activeProject,selectedClip==null?"":selectedClip.id);
        timelineView.setPlayhead(editorPlayhead);
        timelineView.setListener(new StudioTimelineView.Listener(){
            @Override public void onSelect(String id){selectedClip=activeProject.clip(id);previewSelectedClip();}
            @Override public void onSeek(long ms){seekEditor(ms);}
            @Override public void onMove(String id,long ms,String track){try{
                JSONObject args=new JSONObject();args.put("clipId",id);args.put("startMs",ms);args.put("trackId",track);
                applyEditorOperation("move_clip",args);
            }catch(Exception error){editorError(error);}}
            @Override public void onTrim(String id,long in,long out,long start){try{
                JSONObject args=new JSONObject();args.put("clipId",id);args.put("inMs",in);args.put("outMs",out);args.put("startMs",start);
                applyEditorOperation("trim_clip",args);
            }catch(Exception error){editorError(error);}}
            @Override public void onTrackControl(String id,String property,boolean value){try{
                JSONObject args=new JSONObject();args.put("trackId",id);args.put(property,value);applyEditorOperation("set_track",args);
            }catch(Exception error){editorError(error);}}
        });
        timelineScroll.addView(timelineView);box.addView(timelineScroll,margins(-1,-2,0,dp(8),0,0));
        HorizontalScrollView editScroll=new HorizontalScrollView(this);
        LinearLayout editActions=new LinearLayout(this);
        addEditorButton(editActions,"+ Track",this::showAddTrackDialog);
        addEditorButton(editActions,"Split",this::splitClip);
        addEditorButton(editActions,"Trim",()->{if(requireSelection())trimDialog();});
        addEditorButton(editActions,"Duplicate",()->selectedEditorAction("duplicate_clip",false));
        addEditorButton(editActions,"Delete",()->selectedEditorAction("remove_clip",false));
        addEditorButton(editActions,"Ripple delete",()->selectedEditorAction("remove_clip",true));
        addEditorButton(editActions,"+ Marker",()->{try{
            JSONObject args=new JSONObject();args.put("timeMs",editorPlayhead);args.put("name","Marker");applyEditorOperation("add_marker",args);
        }catch(Exception error){editorError(error);}});
        addEditorButton(editActions,"Snapshot",()->{try{store.snapshot(activeProject.id,"Snapshot r"+activeProject.revision);Toast.makeText(this,"Snapshot saved",Toast.LENGTH_SHORT).show();}catch(Exception e){editorError(e);}});
        addEditorButton(editActions,"Restore",this::showSnapshotsDialog);
        addEditorButton(editActions,"Zoom +",()->timelineView.setPixelsPerSecond(125*getResources().getDisplayMetrics().density));
        addEditorButton(editActions,"Zoom −",()->timelineView.setPixelsPerSecond(30*getResources().getDisplayMetrics().density));
        editScroll.addView(editActions);box.addView(editScroll,margins(-1,dp(46),0,dp(6),0,0));
        HorizontalScrollView workspaceScroll=new HorizontalScrollView(this);
        LinearLayout workspaces=new LinearLayout(this);
        addEditorButton(workspaces,"Colour",this::showInspectorDialog);
        addEditorButton(workspaces,"Audio",this::showInspectorDialog);
        addEditorButton(workspaces,"Text",()->{if(requireSelection())textDialog();});
        addEditorButton(workspaces,"Motion",()->{if(requireSelection())applyTool("Motion");});
        addEditorButton(workspaces,"Blur",()->{if(requireSelection())applyTool("Blur");});
        addEditorButton(workspaces,"Animate images",this::animateImagesDialog);
        addEditorButton(workspaces,"Procedural video",this::promptVideoDialog);
        workspaceScroll.addView(workspaces);box.addView(workspaceScroll,margins(-1,dp(46),0,0,0,0));
        TextView studioActivity=body("Studio activity");
        JSONArray recent=ActivityLog.recentWork(this,1);JSONObject work=recent.optJSONObject(0);
        try{JSONObject live=new JSONObject(prefs.getString(ExecutionTruthPolicy.LIVE_JOB_PREF_KEY,"{}"));
            if(activeProject.id.equals(live.optString("projectId")) && !live.optString("detail").isEmpty())
                studioActivity.setText(("owner".equals(live.optString("origin"))?"Owner export":"ChatGPT activity")+" · "+live.optString("detail"));
            else if(work!=null)studioActivity.setText("Studio activity · "+work.optString("action")+" · "+work.optString("state"));
        }catch(Exception ignored){}
        studioActivity.setOnClickListener(v->showActivity());box.addView(studioActivity);
        PreviewSnapshotStore.Snapshot result=previewSnapshots.latest(activeProject.id);
        if(result!=null){Button playNew=compactButton("Play new result · "+result.qualityTier);
            playNew.setOnClickListener(v->{ProjectStore.Asset a=new ProjectStore.Asset();a.id=result.id;a.uri=result.uri;a.mime="video/mp4";a.name="Rendered result";monitor.showSource(activeProject,a,null,0,true);});box.addView(playNew);}
        setScreen(scroll,"editor");
        if(!monitor.isPlaying() && selectedClip!=null){
            if(monitor.isProgram())monitor.showProgram(activeProject,editorPlayhead,false);
            else monitor.showSource(activeProject,activeProject.asset(selectedClip.assetId),selectedClip,
                    TimelineMath.sourceAt(selectedClip.inMs,selectedClip.outMs,selectedClip.speed,Math.max(0,editorPlayhead-selectedClip.startMs)),false);
        }
        scheduleEditorRefresh(activeProject.id,activeProject.revision);
        playbackTick=new Runnable(){@Override public void run(){
            if(!"editor".equals(currentScreen))return;
            if(monitor.isPlaying()){
                long position=monitor.position();
                editorPlayhead=monitor.isProgram()?position:selectedClip==null?position:TimelineMath.add(selectedClip.startMs,TimelineMath.localAt(position,selectedClip.inMs,selectedClip.speed));
                timelineView.setPlayhead(editorPlayhead);
            }
            int fps=activeProject.settings.optInt("fps",30);long frame=TimelineMath.frameIndex(editorPlayhead,fps);
            long shown=monitor.shownRevision();String revision=shown<0?"preparing r"+activeProject.revision:"r"+shown+(shown==activeProject.revision?"":" · project r"+activeProject.revision);
            clock.setText(String.format(Locale.US,"%02d:%02d:%02d:%02d · %s · %s",editorPlayhead/3600000,editorPlayhead/60000%60,editorPlayhead/1000%60,frame%fps,monitor.isProgram()?"Program":"Source",revision));
            ui.postDelayed(this,120);
        }};ui.post(playbackTick);
    }

    private void addEditorButton(LinearLayout row,String label,Runnable action){
        Button button=compactButton(label);button.setContentDescription(label);button.setOnClickListener(v->action.run());row.addView(button);
    }
    private void stepEditorFrame(int direction){
        int fps=activeProject.settings.optInt("fps",30);
        long rounded=TimelineMath.frameIndex(TimelineMath.add(editorPlayhead,Math.max(1,500/fps)),fps);
        long next=direction<0?Math.max(0,rounded-1):TimelineMath.add(rounded,1);
        seekEditor(TimelineMath.frameTime(next,fps));
    }
    private boolean requireSelection(){
        if(selectedClip!=null)return true;Toast.makeText(this,"Select a timeline clip first",Toast.LENGTH_SHORT).show();return false;
    }
    private void applyEditorOperation(String operation,JSONObject args){
        try{
            String selectedId=selectedClip==null?"":selectedClip.id;
            activeProject=editor.execute(activeProject.id,activeProject.revision,"owner","",operation,args);
            selectedClip=activeProject.clip(selectedId);
            syncProtocolState();showEditor();
            if(!activeProject.clips.isEmpty())monitor.showProgram(activeProject,editorPlayhead,false);
        }catch(Exception error){editorError(error);}
    }
    private void selectedEditorAction(String operation,boolean ripple){
        if(!requireSelection())return;try{JSONObject args=new JSONObject();args.put("clipId",selectedClip.id);args.put("ripple",ripple);applyEditorOperation(operation,args);}catch(Exception error){editorError(error);}
    }
    private void editorError(Exception error){
        Toast.makeText(this,error.getMessage()==null?"Could not edit project":error.getMessage(),Toast.LENGTH_LONG).show();
        if(activeProject!=null){ProjectStore.Project fresh=store.get(activeProject.id);if(fresh!=null)activeProject=fresh;}
    }
    private void historyEditor(boolean redo){
        try{activeProject=redo?store.redo(activeProject.id,activeProject.revision):store.undo(activeProject.id,activeProject.revision);syncProtocolState();showEditor();}
        catch(Exception error){editorError(error);}
    }
    private void seekEditor(long timeMs){
        editorPlayhead=Math.max(0,Math.min(activeProject.outputDurationMs(),timeMs));
        if(timelineView!=null)timelineView.setPlayhead(editorPlayhead);
        monitor.showProgram(activeProject,editorPlayhead,false);
    }
    private void showFullscreenPreview(FrameLayout previous){
        FrameLayout full=new FrameLayout(this);monitor.attach(full);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(full).setPositiveButton("Back to editor",null).create();
        dialog.setOnDismissListener(d->monitor.attach(previous));dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    private void showAddTrackDialog(){
        String[] labels={"Video","Image / graphics","Audio music","Audio dialogue","Audio SFX","Voice-over"};
        String[] types={"video","image","audio_music","audio_dialogue","audio_sfx","voice_over"};
        new AlertDialog.Builder(this).setTitle("Add track").setItems(labels,(d,index)->{try{JSONObject a=new JSONObject();a.put("type",types[index]);a.put("name",labels[index]+" "+(activeProject.tracks.size()+1));applyEditorOperation("add_track",a);}catch(Exception error){editorError(error);}}).show();
    }
    private void showSnapshotsDialog(){
        JSONArray snapshots=store.snapshots(activeProject.id);
        if(snapshots.length()==0){Toast.makeText(this,"No snapshots yet",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[snapshots.length()];for(int i=0;i<names.length;i++)names[i]=snapshots.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this).setTitle("Restore project snapshot").setItems(names,(d,index)->{try{
            activeProject=store.restore(activeProject.id,activeProject.revision,snapshots.optJSONObject(index).optString("id"));syncProtocolState();showEditor();
        }catch(Exception error){editorError(error);}}).show();
    }
    private void showInspectorDialog(){
        if(!requireSelection())return;
        ScrollView scroll=baseScroll();LinearLayout form=column();form.setPadding(dp(14),dp(8),dp(14),dp(12));scroll.addView(form);
        form.addView(body("◆ adds a keyframe at the playhead. Values use normalized frame coordinates."));
        java.util.LinkedHashMap<String,EditText> fields=new java.util.LinkedHashMap<>();
        String[] properties={"x","y","scale","scaleX","scaleY","rotate","opacity","anchorX","anchorY","brightness","contrast","saturationAdjust","lightnessAdjust","volume","pan","blur","cropLeft","cropRight","cropTop","cropBottom"};
        android.widget.Spinner easing=new android.widget.Spinner(this);
        easing.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"linear","ease_in_out","ease_in","ease_out","hold"}));form.addView(easing);
        for(String property:properties){
            double fallback=property.startsWith("scale")||"opacity".equals(property)?1:0;
            double value=EditorEngine.valueAt(selectedClip,property,Math.max(0,editorPlayhead-selectedClip.startMs),fallback);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label=body(property);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            EditText input=numberInput((float)value);fields.put(property,input);row.addView(input,new LinearLayout.LayoutParams(dp(90),dp(45)));
            if(!property.startsWith("crop")&&!"blur".equals(property)){
                Button key=compactButton("◆");key.setOnClickListener(v->{try{
                    JSONObject a=new JSONObject();a.put("clipId",selectedClip.id);a.put("property",property);a.put("value",Double.parseDouble(input.getText().toString()));
                    a.put("timeMs",Math.max(0,Math.min(selectedClip.outputDurationMs(),editorPlayhead-selectedClip.startMs)));a.put("easing",easing.getSelectedItem().toString());
                    applyEditorOperation("set_keyframe",a);
                }catch(Exception error){editorError(error);}});row.addView(key);
            }
            form.addView(row);
        }
        new AlertDialog.Builder(this).setTitle("Clip inspector").setView(scroll).setPositiveButton("Apply",(d,w)->{try{
            JSONObject values=new JSONObject();for(java.util.Map.Entry<String,EditText> field:fields.entrySet())values.put(field.getKey(),Double.parseDouble(field.getValue().getText().toString()));
            JSONObject args=new JSONObject();args.put("clipId",selectedClip.id);args.put("values",values);applyEditorOperation("set_properties",args);
        }catch(Exception error){editorError(error);}}).setNegativeButton("Close",null).show();
    }
    private void showMediaBinDialog(){
        ScrollView scroll=baseScroll();LinearLayout items=column();items.setPadding(dp(14),dp(8),dp(14),dp(16));scroll.addView(items);
        for(ProjectStore.Asset asset:activeProject.assets){
            LinearLayout item=column();item.addView(title(asset.name,15));
            item.addView(body((asset.generated?"Generated":"Imported")+" · "+asset.mime+" · "+time(asset.durationMs)+" · "+(asset.seekable?"seekable":"provider stream")));
            LinearLayout actions=new LinearLayout(this);
            addEditorButton(actions,"Preview",()->{monitor.showSource(activeProject,asset,null,0,false);});
            addEditorButton(actions,"+ Timeline",()->showAddAssetDialog(asset));
            addEditorButton(actions,"Rename",()->{EditText name=new EditText(this);name.setText(asset.name);new AlertDialog.Builder(this).setTitle("Rename media").setView(name).setPositiveButton("Save",(d,w)->{try{JSONObject a=new JSONObject();a.put("assetId",asset.id);a.put("name",name.getText().toString());applyEditorOperation("rename_asset",a);}catch(Exception error){editorError(error);}}).setNegativeButton("Cancel",null).show();});
            item.addView(actions);items.addView(item,margins(-1,-2,0,dp(12),0,0));
        }
        if(activeProject.assets.isEmpty())items.addView(body("Import video, images or audio through the system picker."));
        new AlertDialog.Builder(this).setTitle("Media Bin").setView(scroll).setPositiveButton("Close",null).setNeutralButton("Import",(d,w)->pickMedia()).show();
    }
    private void showAddAssetDialog(ProjectStore.Asset asset){
        ArrayList<ProjectStore.Track> tracks=new ArrayList<>();
        for(ProjectStore.Track t:activeProject.tracks)if(!t.locked && (asset.mime.startsWith("audio/")?t.audioOnly():!t.audioOnly()))tracks.add(t);
        if(tracks.isEmpty()){Toast.makeText(this,"Add a compatible track first",Toast.LENGTH_LONG).show();return;}
        String[] names=new String[tracks.size()];for(int i=0;i<names.length;i++)names[i]=tracks.get(i).name;
        new AlertDialog.Builder(this).setTitle("Add to track").setItems(names,(d,index)->{try{JSONObject a=new JSONObject();a.put("assetId",asset.id);a.put("trackId",tracks.get(index).id);applyEditorOperation("add_clip",a);}catch(Exception error){editorError(error);}}).show();
    }

    private void showExportPage(){
        if(activeProject==null || activeProject.clips.isEmpty()){Toast.makeText(this,"Timeline is empty",Toast.LENGTH_SHORT).show();return;}
        ScrollView scroll=baseScroll();LinearLayout form=column();form.setPadding(dp(16),dp(12),dp(16),dp(24));scroll.addView(form);
        form.addView(title("Export video",24));form.addView(body(activeProject.name+" · revision "+activeProject.revision+" · "+time(activeProject.outputDurationMs())));
        EditText fileName=new EditText(this);fileName.setSingleLine();fileName.setText("VideoStudio_"+System.currentTimeMillis()+".mp4");fileName.setTextColor(C_TEXT);
        form.addView(body("Filename"));form.addView(fileName);
        android.widget.Spinner aspect=exportChoice(form,"Aspect ratio",new String[]{"16:9","9:16","1:1","4:5"});
        String aspectValue=activeProject.settings.optString("aspect","16:9");for(int i=0;i<4;i++)if(aspectValue.equals(aspect.getItemAtPosition(i)))aspect.setSelection(i);
        android.widget.Spinner quality=exportChoice(form,"Resolution",new String[]{"1080p","720p"});
        android.widget.Spinner fps=exportChoice(form,"Frames per second",new String[]{"30","24","25","50","60"});
        android.widget.Spinner bitrate=exportChoice(form,"Video bitrate",new String[]{"8 Mbps","4 Mbps","12 Mbps","20 Mbps"});
        android.widget.Spinner destination=exportChoice(form,"Destination",new String[]{"Movies / VideoStudio","App project storage","Choose a file through Android"});
        form.addView(body("H.264 video · AAC audio · SDR. The native encoder uses codec fallback. Source media stays unchanged."));
        Button start=neonButton("Start Export",C_CYAN);start.setTextColor(Color.BLACK);start.setContentDescription("Start Export");
        start.setOnClickListener(v->{try{
            JSONObject settings=new JSONObject();String name=fileName.getText().toString().trim();
            if(name.isEmpty())throw new IllegalArgumentException("Filename is required");if(!name.toLowerCase(Locale.US).endsWith(".mp4"))name+=".mp4";
            settings.put("fileName",name);settings.put("aspect",aspect.getSelectedItem().toString());settings.put("quality",quality.getSelectedItem().toString());
            settings.put("fps",Integer.parseInt(fps.getSelectedItem().toString()));
            int[] rates={8_000_000,4_000_000,12_000_000,20_000_000};settings.put("bitrate",rates[bitrate.getSelectedItemPosition()]);
            settings.put("destination",destination.getSelectedItemPosition()==1?"app":"movies");
            if(destination.getSelectedItemPosition()==2){
                pendingExportSettings=settings;pendingExportProjectId=activeProject.id;
                Intent pick=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4").putExtra(Intent.EXTRA_TITLE,name);
                startActivityForResult(pick,PICK_EXPORT_DESTINATION);
            }else startOwnerExport(activeProject,settings);
        }catch(Exception error){editorError(error);}});
        form.addView(start,margins(-1,dp(52),dp(12),0,0,0));
        ExportSessionStore.Session previous=new ExportSessionStore(this).latest(activeProject.id);
        if(previous!=null){Button last=compactButton("Last export · "+previous.state);last.setOnClickListener(v->showExportSession(previous.id));form.addView(last);}
        Button back=compactButton("Back to editor");back.setOnClickListener(v->showEditor());form.addView(back);setScreen(scroll,"export");
    }
    private android.widget.Spinner exportChoice(LinearLayout form,String label,String[] values){
        form.addView(body(label));android.widget.Spinner choice=new android.widget.Spinner(this);
        choice.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values));form.addView(choice,margins(-1,dp(48),0,dp(6),0,0));return choice;
    }
    private void startOwnerExport(ProjectStore.Project project,JSONObject settings){
        ExportSessionStore sessions=new ExportSessionStore(this);ExportSessionStore.Session session=sessions.create(project,settings);
        try{startForegroundService(new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_EXPORT).putExtra("sessionId",session.id));}
        catch(Exception error){sessions.fail(session.id,error.getMessage());}
        showExportSession(session.id);
    }
    private void showExportSession(String sessionId){
        ExportSessionStore sessions=new ExportSessionStore(this);ExportSessionStore.Session initial=sessions.get(sessionId);if(initial==null)return;
        ScrollView scroll=baseScroll();LinearLayout page=column();page.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(page);
        page.addView(title("Export session",25));page.addView(body(initial.name+" · revision "+initial.projectRevision));
        TextView stage=body("Preparing export");page.addView(stage);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);page.addView(progress,margins(-1,dp(12),dp(12),dp(12),0,0));
        TextView detail=body("");page.addView(detail);
        Button cancel=compactButton("Cancel Export");cancel.setOnClickListener(v->{sessions.cancel(sessionId);startForegroundService(new Intent(this,ControlService.class).setAction(ControlService.ACTION_CANCEL_MANUAL_EXPORT).putExtra("sessionId",sessionId));});page.addView(cancel);
        Button retry=compactButton("Retry Export");retry.setVisibility(View.GONE);retry.setOnClickListener(v->startOwnerExport(sessions.project(sessionId),sessions.get(sessionId).settings));page.addView(retry);
        Button play=compactButton("Play verified export");play.setVisibility(View.GONE);play.setOnClickListener(v->{
            ExportSessionStore.Session done=sessions.get(sessionId);showEditor();ProjectStore.Asset a=new ProjectStore.Asset();a.id=sessionId;a.uri=done.uri;a.mime="video/mp4";a.name=done.name;monitor.showSource(activeProject,a,null,0,true);
        });page.addView(play);
        Button editorButton=compactButton("Continue editing");editorButton.setOnClickListener(v->showEditor());page.addView(editorButton);
        setScreen(scroll,"export-session");
        activityRefresh=new Runnable(){@Override public void run(){
            if(!"export-session".equals(currentScreen))return;ExportSessionStore.Session current=sessions.get(sessionId);if(current==null)return;
            String state=current.state,description=current.detail;
            try{JSONArray live=new JSONArray(prefs.getString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,"[]"));
                for(int i=0;i<live.length();i++){JSONObject job=live.optJSONObject(i);if(job!=null && current.jobId.equals(job.optString("id")) && job.optString("state").startsWith("waiting_")){state=job.optString("state");description=job.optString("detail");}}
            }catch(Exception ignored){}
            stage.setText(state.replace('_',' ')+" · "+current.progress+"%");detail.setText(description);progress.setProgress(current.progress);
            cancel.setVisibility(current.terminal()?View.GONE:View.VISIBLE);retry.setVisibility("failed".equals(current.state)||"cancelled".equals(current.state)?View.VISIBLE:View.GONE);
            play.setVisibility(current.verified && "completed".equals(current.state)?View.VISIBLE:View.GONE);
            if(!current.terminal())ui.postDelayed(this,350);
        }};ui.post(activityRefresh);
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
            LivePlaybackState playing = livePlayer == null ? null : livePlayer.snapshotState();
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
                    livePlayer.setContextIds(latest.id, "");
                    livePlayer.play(Uri.parse(latest.uri), 0L);
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

    private void scheduleEditorRefresh(String projectId, long knownRevision) {
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
            if (latest != null && latest.revision != knownRevision) {
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

    private void showTools() {
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

        box.addView(title("Autonomous Control", 27));
        box.addView(body("Full Autonomous is the default. The MCP path is signalling/control only; projects, media and editing state live in the app. Gallery enumeration remains a hard technical boundary, not a permission toggle."));
        box.addView(section("Autonomy Mode"));
        box.addView(permissionCard("everything", "Full Autonomous  •  Recommended", "ChatGPT can use every VideoStudio-native operation: explicit file imports, project management, analysis, AI animation, editing, rendering, inspection, retries and cleanup without repeated permission prompts. Gallery listing/browsing remains technically blocked."));
        box.addView(permissionCard("one_file", "One File Lock", "Optional manual safety lock. Restricts ChatGPT to the currently authorised media file until you switch back to Full Autonomous."));

        box.addView(section("Workload Safety"));
        LinearLayout safety = card(false);
        safety.addView(title("Heavy-work governor", 17));
        JSONObject state = jobs.state();
        JSONObject mem = state.optJSONObject("memory");
        JSONObject thermal = state.optJSONObject("thermal");
        String detail = "Available memory: " + (mem == null ? "?" : mem.optLong("availableMb")) + " MB"
                + "\nThermal safe: " + (thermal == null || thermal.optBoolean("safeForHeavyWork", true) ? "yes" : "cooling required")
                + "\nParallel lanes: 2 light + 1 protected heavy lane";
        safety.addView(body(detail));
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
        box.addView(privateCard);

        setScreen(scroll, "control");
    }

    private View brandHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.rezoxnemesis.videostudio.R.drawable.logo_vs);
        row.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout copy = column();
        copy.setPadding(dp(12), 0, 0, 0);
        TextView brand = title("VideoStudio", 27);
        copy.addView(brand);
        copy.addView(body("Create • Edit • Enhance • With AI"));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView badge = accent("MCP v3", C_CYAN);
        row.addView(badge);
        return row;
    }

    private View projectRow(ProjectStore.Project project) {
        LinearLayout row = card(false);
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
                .setTitle("New VideoStudio project")
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
        JobManager.Job job = jobs.submit("Archive project • " + project.name, JobManager.Kind.LIGHT, JobManager.Origin.OWNER, state -> {
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
        JobManager.Job job = jobs.submit("Cloud offload • " + project.name, JobManager.Kind.HEAVY, JobManager.Origin.OWNER, state -> {
            File workspace = new CreativeWorkspace(this).projectRoot(project.id);
            state.checkpoint("Cloud offload", 2, "Archiving before local eviction");
            JSONObject archived = driveWorkspace.syncProject(project, workspace, (progress, detail) -> {
                int mapped = Math.max(2, Math.min(88, 2 + (int) (progress * .86)));
                state.checkpoint("Cloud offload", mapped, detail);
                ActivityLog.progress(this, state.id, "Cloud offload", detail, mapped, project.id);
            });
            if (!archived.optBoolean("ok", false)) throw new IllegalStateException("Cloud archive did not complete");
            JSONObject evicted = new CreativeWorkspace(this).evictCloudBackedProject(project.id);
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
        JobManager.Job job = jobs.submit("Restore project • " + project.name, JobManager.Kind.LIGHT, JobManager.Origin.OWNER, state -> {
            state.checkpoint("Cloud restore", 2, "Preparing project workspace");
            File workspace = new CreativeWorkspace(this).projectRoot(project.id);
            JSONObject result = driveWorkspace.restoreProjectWorkspace(project.id, workspace, (progress, detail) -> {
                state.checkpoint("Cloud restore", progress, detail);
                ActivityLog.progress(this, state.id, "Cloud restore", detail, progress, project.id);
            });
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
        if (requestCode == PICK_EXPORT_DESTINATION && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri destination=data.getData();
                int flags=data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(destination,flags); } catch(Exception ignored) {}
                if(pendingExportSettings==null || pendingExportProjectId==null)throw new IllegalStateException("Export settings expired; open Export again");
                pendingExportSettings.put("destination","document");pendingExportSettings.put("destinationUri",destination.toString());
                startOwnerExport(store.get(pendingExportProjectId),pendingExportSettings);
                pendingExportSettings=null;pendingExportProjectId=null;
            }catch(Exception error){editorError(error);}
        } else if (requestCode == PICK_CLOUD_WORKSPACE && resultCode == RESULT_OK && data != null && data.getData() != null) {
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
            if(activeProject==null)createProject("Imported media");
            String importProjectId=activeProject.id;ArrayList<String> ids=new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                ProjectStore.Asset asset=store.beginImport(importProjectId,uri,true);ids.add(asset.id);
                if ("one_file".equals(permissionMode()) && prefs.getString(KEY_FILE, "").isEmpty()) {
                    prefs.edit().putString(KEY_FILE, asset.id).apply();
                }
            }
            activeProject=store.get(importProjectId);
            startForegroundService(new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_IMPORT)
                    .putExtra("projectId",importProjectId).putStringArrayListExtra("assetIds",ids));
            syncProtocolState();
            requestServiceSync();
            showEditor();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void previewSelectedClip() {
        if (selectedClip == null || activeProject == null) return;
        timelinePreviewRunning = false;
        monitor.showSource(activeProject, activeProject.asset(selectedClip.assetId), selectedClip, selectedClip.inMs, false);
        editorPlayhead = selectedClip.startMs;
        if (timelineView != null) timelineView.setPlayhead(editorPlayhead);
    }

    private void previewTimeline() {
        if (activeProject == null || activeProject.clips.isEmpty()) return;
        timelinePreviewRunning = true;
        monitor.showProgram(activeProject, editorPlayhead, true);
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
        livePlayer.setContextIds("", clip.id);
        livePlayer.playClip(
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
        if (activeProject == null || selectedClip == null) {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            switch (tool) {
                case "Split":
                    splitClip();
                    return;
                case "Trim":
                    trimDialog();
                    return;
                case "Slow Motion":
                    selectedClip.speed = selectedClip.speed <= .55f ? .75f : selectedClip.speed <= .8f ? 1f : .5f;
                    break;
                case "Speed Ramp":
                    selectedClip.effects.put("speedRamp", "smooth");
                    break;
                case "Green Screen":
                    selectedClip.effects.put("chromaKey", true);
                    selectedClip.effects.put("chromaColor", "#00FF00");
                    selectedClip.effects.put("chromaTolerance", .18);
                    selectedClip.effects.put("spillSuppression", .35);
                    break;
                case "Transitions":
                    selectedClip.transition = CreatorCatalog.next(CreatorCatalog.TRANSITIONS, selectedClip.transition);
                    break;
                case "Motion":
                    selectedClip.effects.put("motionPreset", CreatorCatalog.next(CreatorCatalog.MOTIONS, selectedClip.effects.optString("motionPreset", "none")));
                    selectedClip.effects.put("ease", "easeInOut");
                    break;
                case "Effects":
                    selectedClip.effects.put("effectPreset", CreatorCatalog.next(CreatorCatalog.EFFECTS, selectedClip.effects.optString("effectPreset", "none")));
                    break;
                case "Colour":
                    String look = selectedClip.effects.optString("colorPreset", "none");
                    String[] looks = {"cinematic","teal_orange","warm_film","cool_night","noir","golden_hour","matte","high_contrast","soft_portrait"};
                    int li = java.util.Arrays.asList(looks).indexOf(look);
                    selectedClip.effects.put("colorPreset", looks[(li + 1 + looks.length) % looks.length]);
                    break;
                case "Text":
                    textDialog();
                    return;
                case "Fonts":
                    selectedClip.effects.put("fontFamily", CreatorCatalog.next(CreatorCatalog.FONTS, selectedClip.effects.optString("fontFamily", "sans-serif-medium")));
                    break;
                case "Volume":
                    selectedClip.volume = selectedClip.volume > .8f ? .6f : selectedClip.volume > .3f ? 0f : 1f;
                    break;
                case "Reframe":
                    selectedClip.effects.put("reframe", "9:16_subject_safe");
                    break;
                case "Mask":
                    selectedClip.effects.put("mask", "rounded_rect");
                    selectedClip.effects.put("maskFeather", .08);
                    break;
                case "Overlay":
                    selectedClip.effects.put("overlaySlot", "ready");
                    break;
                case "Motion Blur":
                    selectedClip.effects.put("motionBlur", .35);
                    break;
                case "Freeze":
                    selectedClip.effects.put("freezeAtMs", Math.max(selectedClip.inMs, livePlayer == null ? selectedClip.inMs : livePlayer.currentPositionMs()));
                    break;
                case "Duplicate": {
                    int index = activeProject.clips.indexOf(selectedClip);
                    ProjectStore.Clip copy = ProjectStore.Clip.fromJson(selectedClip.toJson());
                    copy.id = UUID.randomUUID().toString();
                    activeProject.clips.add(index + 1, copy);
                    selectedClip = copy;
                    break;
                }
                case "Reverse":
                    selectedClip.effects.put("reverse", !selectedClip.effects.optBoolean("reverse", false));
                    break;
                case "Shake":
                    selectedClip.effects.put("motionPreset", "impact_shake");
                    break;
                case "Blur":
                    selectedClip.effects.put("blur", selectedClip.effects.optDouble("blur", 0) > .1 ? 0 : 5.0);
                    break;
                case "Glow":
                    selectedClip.effects.put("effectPreset", "soft_glow");
                    selectedClip.effects.put("blur", 1.6);
                    break;
                case "Captions":
                    selectedClip.effects.put("captionStyle", "creator_pop");
                    selectedClip.effects.put("textAnimation", "caption_pop");
                    break;
                case "Audio Duck":
                    selectedClip.effects.put("audioDucking", true);
                    selectedClip.effects.put("duckLevel", .32);
                    break;
                case "Crop":
                    selectedClip.effects.put("crop", "center_cover");
                    break;
            }
            store.save(activeProject);
            syncProtocolState();
            Toast.makeText(this, tool + " applied", Toast.LENGTH_SHORT).show();
            showEditor();
        } catch (Exception error) {
            Toast.makeText(this, "Could not apply " + tool, Toast.LENGTH_SHORT).show();
        }
    }

    private void splitClip() {
        if(!requireSelection())return;
        try{JSONObject args=new JSONObject();args.put("clipId",selectedClip.id);args.put("atMs",editorPlayhead);applyEditorOperation("split_clip",args);}
        catch(Exception error){editorError(error);}
    }

    private void trimDialog() {
        LinearLayout wrap = column();
        wrap.setPadding(dp(22), 0, dp(22), 0);
        EditText start = numberInput(selectedClip.inMs / 1000f);
        EditText end = numberInput(selectedClip.outMs / 1000f);
        wrap.addView(body("Start seconds"));
        wrap.addView(start);
        wrap.addView(body("End seconds"));
        wrap.addView(end);
        new AlertDialog.Builder(this)
                .setTitle("Trim clip")
                .setView(wrap)
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        long a = (long) (Float.parseFloat(start.getText().toString()) * 1000);
                        long b = (long) (Float.parseFloat(end.getText().toString()) * 1000);
                        if (b <= a) throw new IllegalArgumentException();
                        JSONObject args=new JSONObject();args.put("clipId",selectedClip.id);args.put("inMs",a);args.put("outMs",b);
                        applyEditorOperation("trim_clip",args);
                    } catch (Exception error) {
                        Toast.makeText(this, "Invalid trim range", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void textDialog() {
        EditText input = new EditText(this);
        input.setText(selectedClip.title);
        input.setHint("Title text");
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_MUTED);
        new AlertDialog.Builder(this)
                .setTitle("Clip title")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    try{JSONObject args=new JSONObject();args.put("clipId",selectedClip.id);args.put("text",input.getText().toString().trim());applyEditorOperation("set_title",args);}
                    catch(Exception error){editorError(error);}
                })
                .setNegativeButton("Cancel", null)
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

        JobManager.Job job = jobs.submit("Prompt video • " + titleText, JobManager.Kind.HEAVY, JobManager.Origin.OWNER, state -> {
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
        if (project == null || project.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
        final ProjectStore.Project target = store.get(project.id);
        if (target == null) throw new IllegalArgumentException("Project not found");
        String safeName = (fileName == null || fileName.trim().isEmpty())
                ? "VideoStudio_" + System.currentTimeMillis() + ".mp4"
                : fileName.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (!safeName.toLowerCase(Locale.US).endsWith(".mp4")) safeName += ".mp4";
        final String finalName = safeName;

        JobManager.Job job = jobs.submit("Export • " + target.name, JobManager.Kind.HEAVY, JobManager.Origin.OWNER, state -> {
            state.checkpoint(2, "Preparing Media3 native export");
            runExportBlocking(target, aspect, quality, finalName, state);
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
        File dir = new File(getCacheDir(), "native_exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create export workspace");
        File temp = new File(dir, "tmp_" + System.currentTimeMillis() + ".mp4");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicReference<File> completed = new AtomicReference<>(null);

        ui.post(() -> {
            activeRenderHandle = renderEngine.export(project, temp, aspect, quality, new NativeRenderEngine.Listener() {
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
            });
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

            jobs.submit("Analyse • " + target.name, JobManager.Kind.LIGHT, JobManager.Origin.OWNER, state -> {
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
                case "preview_project":
                    if (!"editor".equals(currentScreen)) showEditor();
                    ui.postDelayed(this::previewTimeline, 250);
                    result.put("ok", true);
                    result.put("previewing", true);
                    break;
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
                            p.optString("fileName", "VideoStudio_" + System.currentTimeMillis() + ".mp4")
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

        JobManager.Job job = jobs.submit("Private import " + name, JobManager.Kind.LIGHT, JobManager.Origin.OWNER, state -> {
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
        JobManager.Job job = jobs.submit("Import " + name, JobManager.Kind.LIGHT, JobManager.Origin.OWNER, state -> {
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
        if (protocol != null && protocol.isControlPaused()) return "●  ChatGPT control paused • tap Control to resume";
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
        card.setBackground(rounded(hero ? Color.rgb(12, 26, 51) : C_CARD, hero ? Color.rgb(44, 115, 190) : Color.rgb(35, 48, 72), dp(18)));
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
        b.setTextColor(C_TEXT);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackground(rounded(C_CARD_2, Color.rgb(42, 58, 88), dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), dp(44));
        lp.setMargins(dp(6), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button neonButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(C_TEXT);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackground(rounded(color, C_CYAN, dp(15)));
        return b;
    }

    private View navButton(String icon, String label, Runnable action) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
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

