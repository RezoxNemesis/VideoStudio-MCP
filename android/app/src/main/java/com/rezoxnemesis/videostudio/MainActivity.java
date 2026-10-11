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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable activityRefresh;
    private Runnable serviceWatchdog;
    private boolean timelinePreviewRunning;
    private Runnable stillTimelineNext;
    private StudioThumbnailLoader studioThumbs;
    private StudioAudioWaveforms studioAudioWaveforms;
    private final StudioEditHistory editHistory=new StudioEditHistory();
    private ImageView studioStillPreview;
    private TextView studioCaptionPreview;
    private Runnable studioCaptionClock;
    private FrameLayout studioPreviewFrame;
    private TextView studioTimeLabel;
    private int studioToolCategory=0;
    private int studioToolsHubTab=0;
    private int studioTimelinePage=0;
    private int studioMediaPage=0;
    private long studioPlayheadMs=0;
    private boolean studioShowMedia=false;
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
        // UI/local proxy work must never rewrite the native MCP job journal.
        jobs = new JobManager(this,"videostudio_editor_local_jobs_v2");
        studioThumbs = new StudioThumbnailLoader(this);
        studioAudioWaveforms=new StudioAudioWaveforms(this);
        renderEngine = new NativeRenderEngine(this);
        promptVideoEngine = new PromptVideoEngine(this);
        mediaAnalyzer = new NativeMediaAnalyzer(this);
        driveWorkspace = new DriveWorkspaceProvider(this);
        previewSnapshots = new PreviewSnapshotStore(this);
        proxyManager = new ProxyManager(this, store, jobs);
        livePlayer = new LiveEditPlayer(this);
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
        if(stillTimelineNext!=null)ui.removeCallbacks(stillTimelineNext);
        if(studioCaptionClock!=null)ui.removeCallbacks(studioCaptionClock);
        if(studioThumbs!=null)studioThumbs.shutdown();
        if(studioAudioWaveforms!=null)studioAudioWaveforms.shutdown();
        if (activityRefresh != null) ui.removeCallbacks(activityRefresh);
        if (serviceWatchdog != null) ui.removeCallbacks(serviceWatchdog);
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
        currentScreen = name;
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
        hero.addView(title("Your Studio",26));
        hero.addView(body(activeProject==null?
                "Create, import, edit and play videos right here on your phone.":
                "Continue  ·  "+activeProject.name));
        Button resume=neonButton(activeProject==null?"Create First Project":
                "Open Editor  →",C_MAGENTA);
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
        }else nativeStrip.addView(body("Native rendering and manual editing stay separate."));
        box.addView(nativeStrip,margins(-1,-2,dp(4),0,0,0));

        LinearLayout projectsTop=new LinearLayout(this);
        projectsTop.setGravity(Gravity.CENTER_VERTICAL);
        projectsTop.addView(title("Projects",20),new LinearLayout.LayoutParams(0,-2,1));
        projectsTop.addView(studioButton("View All ›",this::showStudioProjectLibrary),
                new LinearLayout.LayoutParams(dp(107),dp(44)));
        box.addView(projectsTop,margins(-1,-2,dp(16),dp(6),0,0));
        List<ProjectStore.Project> projects=store.list();
        if(projects.isEmpty())box.addView(body("No projects yet. Create one and import your clips."));
        else for(int i=0;i<Math.min(8,projects.size());i++)
            box.addView(projectRow(projects.get(i)),margins(-1,-2,0,dp(6),0,0));

        LinearLayout creative=card(false);
        creative.addView(title("AI Animation & Advanced Tools",18));
        creative.addView(body("Create from a prompt, trace 2D character rigs, preview animated GLB models, or direct real combat motion."));
        LinearLayout links=new LinearLayout(this);
        links.addView(studioButton("AI Tools",this::showTools),
                new LinearLayout.LayoutParams(0,dp(46),1));
        links.addView(studioButton("Animation Director",this::animationDirectorDialog),
                new LinearLayout.LayoutParams(0,dp(46),1));
        creative.addView(links,margins(-1,dp(46),dp(7),0,0,0));
        box.addView(creative,margins(-1,-2,dp(16),0,0,0));
        setScreen(scroll,"home");
    }

    /**
     * Pairing belongs to the native Android identity. The web *front-end* is
     * optional; its underlying authenticated relay/Worker must remain online
     * for off-device commands. No new public token is ever generated here.
     */
    private void showStudioConnectionHub(){
        JSONObject state=NativeWorkMonitor.snapshot(this);
        boolean online=state.optBoolean("nativeServiceOnline",false);
        LinearLayout body=column();
        body.setPadding(dp(18),dp(3),dp(18),dp(5));
        body.addView(title(online?"● Native Agent connected":"◌ Native Agent reconnecting",19));
        body.addView(body(serviceConnectionText()),margins(-1,-2,dp(9),0,0,0));
        body.addView(body("VideoStudio keeps its device identity and connection credentials inside Android. "+
                "Removing the public website UI does not remove this installed app's saved projects. "+
                "However, off-device ChatGPT control still needs the authenticated MCP relay to stay online."),
                margins(-1,-2,dp(9),0,0,0));
        body.addView(body("Android can suspend background execution during Doze or after force-stop. "+
                "Pending commands resume when the native executor reconnects; no system can guarantee "+
                "uninterrupted work after Android has force-stopped the app."),
                margins(-1,-2,dp(9),0,0,0));
        Button connect=studioButton("Connect / Share with ChatGPT",this::sharePairing);
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
        if(studioCaptionClock!=null){
            ui.removeCallbacks(studioCaptionClock);studioCaptionClock=null;
        }
        if(activeProject==null){
            activeProject=store.active();
            if(activeProject==null)activeProject=store.create("Untitled Project");
        }
        ProjectStore.Project refreshed=store.get(activeProject.id);
        if(refreshed!=null&&refreshed.updatedAt!=activeProject.updatedAt){
            String selectedId=selectedClip==null?"":selectedClip.id;
            activeProject=refreshed;selectedClip=null;
            for(ProjectStore.Clip c:activeProject.clips){
                if(selectedId.equals(c.id)){selectedClip=c;break;}
            }
        }
        if(selectedClip==null && !activeProject.clips.isEmpty())
            selectedClip=activeProject.clips.get(0);
        editHistory.reset(activeProject.id);
        ScrollView scroll=baseScroll();
        LinearLayout box=column();
        box.setPadding(dp(12),dp(12),dp(12),dp(24));
        scroll.addView(box);

        LinearLayout projectTop=new LinearLayout(this);
        projectTop.setGravity(Gravity.CENTER_VERTICAL);
        Button projectButton=studioButton("▾  "+activeProject.name,()->showStudioProjectLibrary());
        projectButton.setTextSize(15);
        projectTop.addView(projectButton,new LinearLayout.LayoutParams(0,dp(49),1));
        Button export=studioButton("Export",this::queueStudioExport);
        export.setBackground(rounded(C_MAGENTA,C_PURPLE,dp(12)));
        projectTop.addView(export,margins(dp(86),dp(48),0,0,dp(6),0));
        box.addView(projectTop);
        box.addView(body("PROJECT  •  "+activeProject.clips.size()+" clips  •  "+
                activeProject.assets.size()+" assets  •  "+time(activeProject.outputDurationMs())),
                margins(-1,-2,dp(4),dp(8),dp(3),0));

        LinearLayout previewCard=card(false);
        LinearLayout caption=new LinearLayout(this);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        TextView previewLabel=title("Preview",16);
        caption.addView(previewLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView sourceLabel=accent(selectedClip==null?"NO CLIP":
                "SELECTED "+(activeProject.clips.indexOf(selectedClip)+1)+
                        "/"+activeProject.clips.size(),C_CYAN);
        caption.addView(sourceLabel);
        previewCard.addView(caption);
        studioPreviewFrame=new FrameLayout(this);
        studioPreviewFrame.setBackground(rounded(Color.BLACK,0,dp(12)));
        livePlayer.attach(studioPreviewFrame);
        studioStillPreview=new ImageView(this);
        studioStillPreview.setBackgroundColor(Color.BLACK);
        studioStillPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        studioPreviewFrame.addView(studioStillPreview,
                new FrameLayout.LayoutParams(-1,-1));
        studioCaptionPreview=new TextView(this);
        studioCaptionPreview.setGravity(Gravity.CENTER);
        studioCaptionPreview.setTextSize(17);
        studioCaptionPreview.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        studioCaptionPreview.setTextColor(Color.WHITE);
        studioCaptionPreview.setShadowLayer(dp(3),0,dp(2),Color.BLACK);
        studioCaptionPreview.setPadding(dp(8),dp(5),dp(8),dp(5));
        studioCaptionPreview.setVisibility(View.GONE);
        studioPreviewFrame.addView(studioCaptionPreview,
                new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        previewCard.addView(studioPreviewFrame,margins(-1,dp(254),dp(7),0,0,0));

        studioTimeLabel=body("0:00 / "+time(activeProject.outputDurationMs()));
        previewCard.addView(studioTimeLabel,margins(-1,-2,dp(7),0,0,0));
        SeekBar seek=new SeekBar(this);
        seek.setMax(1000);
        long projectLength=Math.max(1,activeProject.outputDurationMs());
        studioPlayheadMs=Math.max(0,Math.min(projectLength,studioPlayheadMs));
        if(selectedClip!=null){
            StudioTimelineClock.Position position=StudioTimelineClock.locate(
                    activeProject.clips,studioPlayheadMs);
            if(position.clipIndex<0||
                    !selectedClip.id.equals(activeProject.clips.get(position.clipIndex).id)){
                studioPlayheadMs=0;
                for(ProjectStore.Clip clip:activeProject.clips){
                    if(clip.id.equals(selectedClip.id))break;
                    studioPlayheadMs+=clip.outputDurationMs();
                }
            }
        }
        seek.setProgress((int)(1000*Math.min(projectLength,studioPlayheadMs)/(double)projectLength));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar b){stopStillSequenceOnly();}
            @Override public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                if(fromUser)studioTimeLabel.setText(
                        time(Math.round(projectLength*value/1000.0))+" / "+time(projectLength));
            }
            @Override public void onStopTrackingTouch(SeekBar b){
                long playhead=Math.round(projectLength*b.getProgress()/1000.0);
                scrubStudioTo(playhead);
            }
        });
        previewCard.addView(seek,margins(-1,dp(38),0,0,0,0));

        LinearLayout controls=new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(studioButton("▶ Play Edit",this::playStudioTimeline),
                new LinearLayout.LayoutParams(0,dp(46),1));
        controls.addView(studioButton("▶ Latest MP4",this::playStudioLatest),
                new LinearLayout.LayoutParams(0,dp(46),1));
        controls.addView(studioButton("▤ All Renders",this::showStudioRenderLibrary),
                new LinearLayout.LayoutParams(0,dp(46),1));
        previewCard.addView(controls,margins(-1,dp(48),dp(3),0,0,0));
        box.addView(previewCard,margins(-1,-2,dp(8),0,0,0));

        JSONObject nativeState=NativeWorkMonitor.snapshot(this);
        LinearLayout jobStrip=card(false);
        jobStrip.setPadding(dp(12),dp(9),dp(12),dp(9));
        jobStrip.setBackground(rounded(C_CARD_2,Color.rgb(37,53,82),dp(13)));
        LinearLayout jobTop=new LinearLayout(this);
        jobTop.setGravity(Gravity.CENTER_VERTICAL);
        boolean nativeOnline=nativeState.optBoolean("nativeServiceOnline",false);
        TextView status=title((nativeOnline?"● Native connected":"◌ Native reconnecting")
                +"  ·  "+nativeState.optInt("active",0)+" jobs",13);
        status.setTextColor(nativeOnline?C_CYAN:C_MUTED);
        jobTop.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button queue=studioButton("Jobs ›",this::showStudioJobDetails);
        jobTop.addView(queue,new LinearLayout.LayoutParams(dp(85),dp(39)));
        jobStrip.addView(jobTop);
        String jobState=nativeState.optString("jobState","");
        String detail=nativeState.optString("jobDetail","");
        if(!jobState.isEmpty()){
            jobStrip.addView(body(nativeState.optString("jobName","VideoStudio task")+
                    " · "+jobState.replace('_',' ')+
                    " · "+nativeState.optInt("jobProgress",0)+"%"));
        }
        if(nativeState.optBoolean("thermalSafetyHold",false)||
                "waiting_thermal".equals(jobState)){
            TextView message=accent(nativeState.optString("thermalLabel","Android thermal hold")+
                    " · OS throttling, not measured case temperature",0xffffb66c);
            jobStrip.addView(message);
        }else if(!detail.isEmpty()&&nativeState.optInt("active",0)>0)
            jobStrip.addView(body(detail));
        box.addView(jobStrip,margins(-1,-2,dp(9),0,0,0));

        LinearLayout timelineHeader=new LinearLayout(this);
        timelineHeader.setGravity(Gravity.CENTER_VERTICAL);
        timelineHeader.addView(title("Timeline",17),
                new LinearLayout.LayoutParams(0,-2,1));
        Button undo=studioButton("↶ Undo",()->restoreStudioHistory(false));
        undo.setEnabled(editHistory.canUndo());
        timelineHeader.addView(undo,new LinearLayout.LayoutParams(dp(76),dp(42)));
        Button redo=studioButton("↷ Redo",()->restoreStudioHistory(true));
        redo.setEnabled(editHistory.canRedo());
        timelineHeader.addView(redo,new LinearLayout.LayoutParams(dp(76),dp(42)));
        box.addView(timelineHeader,margins(-1,-2,dp(9),dp(2),0,0));

        HorizontalScrollView timeline=new HorizontalScrollView(this);
        timeline.setHorizontalScrollBarEnabled(false);
        LinearLayout clips=new LinearLayout(this);
        clips.setPadding(dp(2),dp(3),dp(9),dp(6));
        int pageSize=20;
        int pages=Math.max(1,(activeProject.clips.size()+pageSize-1)/pageSize);
        studioTimelinePage=Math.max(0,Math.min(studioTimelinePage,pages-1));
        int first=studioTimelinePage*pageSize;
        int end=Math.min(activeProject.clips.size(),first+pageSize);
        for(int i=first;i<end;i++){
            final int at=i;
            ProjectStore.Clip clip=activeProject.clips.get(i);
            ProjectStore.Asset asset=activeProject.asset(clip.assetId);
            boolean selected=selectedClip!=null&&selectedClip.id.equals(clip.id);
            LinearLayout chip=column();
            chip.setPadding(dp(5),dp(6),dp(5),dp(6));
            chip.setBackground(rounded(selected?0xff192a4b:C_CARD_2,
                    selected?C_CYAN:0xff32405d,dp(11)));
            TextView number=accent(String.valueOf(i+1)+" · "+time(clip.outputDurationMs()),
                    selected?C_CYAN:C_MUTED);
            chip.addView(number);
            ImageView thumb=new ImageView(this);
            thumb.setBackgroundColor(0xff0c1626);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if(asset!=null&&studioThumbs!=null)studioThumbs.bind(thumb,asset);
            chip.addView(thumb,margins(-1,dp(81),dp(4),0,0,0));
            TextView filename=body(asset==null?"Missing media":asset.name);
            filename.setMaxLines(1);filename.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(filename,margins(-1,-2,dp(4),0,0,0));
            chip.setOnClickListener(v->{
                stopStillSequenceOnly();
                long start=0;
                for(int j=0;j<at;j++)start+=activeProject.clips.get(j).outputDurationMs();
                studioPlayheadMs=start;
                selectedClip=activeProject.clips.get(at);
                showEditor();
                if(selectedClip!=null)previewSelectedStudioClip(true);
            });
            // Press-and-hold to reorder the actual timeline. No changes to
            // imported source media, generated renders or other project tracks.
            chip.setOnLongClickListener(v->{
                android.content.ClipData dragData=android.content.ClipData.newPlainText(
                        "videostudio.timeline.clip",clip.id);
                return v.startDragAndDrop(dragData,new View.DragShadowBuilder(chip),
                        clip.id,0);
            });
            chip.setOnDragListener((v,event)->{
                switch(event.getAction()){
                    case android.view.DragEvent.ACTION_DRAG_STARTED:
                        return event.getLocalState() instanceof String;
                    case android.view.DragEvent.ACTION_DROP:
                        Object origin=event.getLocalState();
                        if(!(origin instanceof String))return false;
                        moveStudioClipTo((String)origin,at);
                        return true;
                    default:return true;
                }
            });
            clips.addView(chip,margins(dp(115),dp(144),0,0,0,dp(7)));
        }
        if(activeProject.clips.isEmpty()){
            TextView empty=body("No clips yet. Import media or choose an existing generated project.");
            clips.addView(empty);
        }
        timeline.addView(clips);
        box.addView(timeline);
        if(pages>1){
            LinearLayout paging=new LinearLayout(this);
            paging.setGravity(Gravity.CENTER_VERTICAL);
            Button prev=studioButton("‹ Earlier",()->{
                studioTimelinePage=Math.max(0,studioTimelinePage-1);showEditor();
            });
            paging.addView(prev,new LinearLayout.LayoutParams(0,dp(40),1));
            TextView current=body("Page "+(studioTimelinePage+1)+" / "+pages);
            current.setGravity(Gravity.CENTER);
            paging.addView(current,new LinearLayout.LayoutParams(0,dp(40),1));
            Button next=studioButton("Later ›",()->{
                studioTimelinePage=Math.min(pages-1,studioTimelinePage+1);showEditor();
            });
            paging.addView(next,new LinearLayout.LayoutParams(0,dp(40),1));
            box.addView(paging);
        }

        renderStudioTrackLanes(box);

        LinearLayout mediaHead=new LinearLayout(this);
        mediaHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView mediaTitle=title("Media Bin · "+activeProject.assets.size(),16);
        mediaHead.addView(mediaTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button binButton=studioButton(studioShowMedia?"Hide media":"Show media",()->{
            studioShowMedia=!studioShowMedia;showEditor();
        });
        mediaHead.addView(binButton,new LinearLayout.LayoutParams(dp(114),dp(43)));
        Button add=studioButton("+ Import",this::pickMedia);
        mediaHead.addView(add,new LinearLayout.LayoutParams(dp(105),dp(43)));
        box.addView(mediaHead,margins(-1,-2,dp(8),dp(4),0,0));
        if(studioShowMedia)renderStudioMediaBin(box);

        box.addView(title("Tools",18),margins(-1,-2,dp(10),dp(3),0,0));
        HorizontalScrollView categories=new HorizontalScrollView(this);
        categories.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=new LinearLayout(this);
        for(int i=0;i<StudioToolCatalog.GROUPS.length;i++){
            final int choice=i;
            String group=StudioToolCatalog.GROUPS[i];
            Button tab=studioButton(group,()->{
                studioToolCategory=choice;showEditor();
            });
            tab.setBackground(rounded(studioToolCategory==i?0xff243b70:C_CARD_2,
                    studioToolCategory==i?C_CYAN:0xff273552,dp(12)));
            tabs.addView(tab,margins(dp(151),dp(45),0,0,0,dp(6)));
        }
        categories.addView(tabs);
        box.addView(categories);
        List<StudioToolCatalog.Tool> actions=StudioToolCatalog.group(studioToolCategory);
        for(int i=0;i<actions.size();i+=2){
            LinearLayout row=new LinearLayout(this);
            for(int j=i;j<Math.min(actions.size(),i+2);j++){
                StudioToolCatalog.Tool tool=actions.get(j);
                Button button=studioButton(tool.label+(tool.experimental?" *":""),
                        ()->performStudioTool(tool.label));
                row.addView(button,new LinearLayout.LayoutParams(0,dp(49),1));
            }
            if((actions.size()-i)==1)row.addView(new View(this),
                    new LinearLayout.LayoutParams(0,dp(49),1));
            box.addView(row,margins(-1,dp(49),dp(3),0,0,0));
        }
        Button find=studioButton("⌕ Find any tool",this::findStudioTool);
        box.addView(find,margins(-1,dp(46),dp(10),0,0,0));
        box.addView(body("* Advanced effects may require native MP4 export. "+
                "A source preview is not proof that an effect was rendered."),
                margins(-1,-2,dp(5),0,0,0));

        setScreen(scroll,"editor");
        previewSelectedStudioClip(false);
        updateStudioCaptionAt(studioPlayheadMs);
        scheduleEditorRefresh(activeProject.id,activeProject.updatedAt);
    }


    private Button studioButton(String label,Runnable action){
        Button button=new Button(this);
        button.setText(label);
        button.setTextColor(C_TEXT);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setPadding(dp(7),0,dp(7),0);
        button.setBackground(rounded(C_CARD_2,0xff344469,dp(11)));
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
        new AlertDialog.Builder(this).setTitle("My VideoStudio Projects")
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
                    livePlayer.setContextIds("final:"+System.currentTimeMillis(),"");
                    livePlayer.play(Uri.parse(uris.get(index)),0L);
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
            livePlayer.setContextIds("final:"+activeProject.latestExportAt,"");
            livePlayer.play(Uri.parse(activeProject.latestExportUri),0L);
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
        String[] quality={"720p · faster mobile export","1080p · higher detail"};
        new AlertDialog.Builder(this).setTitle("Export this timeline")
                .setMessage("VideoStudio's native Media3 renderer applies supported effects. "+
                        "The finished MP4 will appear in All Renders; the source project stays intact.")
                .setItems(quality,(d,index)->{
                    try{
                        Intent request=new Intent(this,ControlService.class)
                                .setAction(ControlService.ACTION_LOCAL_EXPORT)
                                .putExtra("projectId",activeProject.id)
                                .putExtra("aspect","9:16")
                                .putExtra("quality",index==0?"720p":"1080p")
                                .putExtra("fileName","VideoStudio_"+
                                        System.currentTimeMillis()+".mp4");
                        startForegroundService(request);
                        Toast.makeText(this,"Export queued. Open Jobs to track the actual result.",
                                Toast.LENGTH_LONG).show();
                        showStudioJobDetails();
                    }catch(Exception e){
                        Toast.makeText(this,"Could not queue native export: "+e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Cancel",null).show();
    }

    private void previewSelectedStudioClip(boolean startPlayback){
        if(selectedClip==null||activeProject==null||studioStillPreview==null){
            if(studioStillPreview!=null)studioStillPreview.setVisibility(View.VISIBLE);
            return;
        }
        ProjectStore.Asset asset=activeProject.asset(selectedClip.assetId);
        if(asset==null)return;
        if(asset.mime!=null&&asset.mime.startsWith("image/")){
            livePlayer.pause();
            studioStillPreview.setVisibility(View.VISIBLE);
            studioThumbs.bindPreview(studioStillPreview,asset);
        }else if(asset.mime!=null&&asset.mime.startsWith("video/")){
            studioStillPreview.setVisibility(View.GONE);
            Uri uri=Uri.parse(ProxyManager.previewUri(activeProject,asset));
            LivePlaybackState currently=livePlayer.snapshotState();
            if(!selectedClip.id.equals(currently.clipId)||
                    !uri.toString().equals(currently.mediaUri)){
                livePlayer.setContextIds("",selectedClip.id);
                livePlayer.play(uri,selectedClip.inMs,selectedClip.speed,startPlayback);
            }else if(startPlayback){
                livePlayer.playClip(uri,selectedClip.inMs,selectedClip.outMs,
                        selectedClip.speed,null);
            }
        }else{
            livePlayer.pause();
            studioStillPreview.setVisibility(View.VISIBLE);
            studioStillPreview.setImageDrawable(null);
        }
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
        showEditor();
        ProjectStore.Asset asset=activeProject.asset(selectedClip.assetId);
        if(asset!=null&&asset.mime!=null&&asset.mime.startsWith("video/")){
            livePlayer.seekTo(position.assetTimeMs);
        }
    }

    /** Honest timeline preview: original clips and stills, not fake interpolation. */
    private void playStudioTimeline(){
        if(activeProject==null||activeProject.clips.isEmpty()){
            Toast.makeText(this,"Timeline is empty",Toast.LENGTH_SHORT).show();return;
        }
        stopStillSequenceOnly();
        StudioTimelineClock.Position start=
                StudioTimelineClock.locate(activeProject.clips,studioPlayheadMs);
        timelinePreviewRunning=true;
        playStudioFrom(Math.max(0,start.clipIndex));
    }

    private void playStudioFrom(int index){
        if(!timelinePreviewRunning||activeProject==null||
                index>=activeProject.clips.size()){
            timelinePreviewRunning=false;
            return;
        }
        ProjectStore.Clip clip=activeProject.clips.get(index);
        selectedClip=clip;
        studioPlayheadMs=0;
        for(int i=0;i<index;i++)studioPlayheadMs+=activeProject.clips.get(i).outputDurationMs();
        if(studioTimeLabel!=null)studioTimeLabel.setText(
                time(studioPlayheadMs)+" / "+time(activeProject.outputDurationMs()));
        updateStudioCaptionAt(studioPlayheadMs);
        startStudioCaptionClock(clip,studioPlayheadMs);
        ProjectStore.Asset asset=activeProject.asset(clip.assetId);
        if(asset==null){playStudioFrom(index+1);return;}
        if(asset.mime!=null&&asset.mime.startsWith("video/")){
            if(studioStillPreview!=null)studioStillPreview.setVisibility(View.GONE);
            livePlayer.setContextIds("",clip.id);
            livePlayer.playClip(Uri.parse(ProxyManager.previewUri(activeProject,asset)),
                    clip.inMs,clip.outMs,clip.speed,()->{
                        if(timelinePreviewRunning)playStudioFrom(index+1);
                    });
        }else if(asset.mime!=null&&asset.mime.startsWith("image/")){
            livePlayer.pause();
            if(studioStillPreview!=null){
                studioStillPreview.setVisibility(View.VISIBLE);
                studioThumbs.bindPreview(studioStillPreview,asset);
            }
            stillTimelineNext=()->{
                stillTimelineNext=null;
                if(timelinePreviewRunning)playStudioFrom(index+1);
            };
            ui.postDelayed(stillTimelineNext,Math.max(50,clip.outputDurationMs()));
        }else playStudioFrom(index+1);
    }

    private void stopStillSequenceOnly(){
        timelinePreviewRunning=false;
        if(stillTimelineNext!=null){
            ui.removeCallbacks(stillTimelineNext);
            stillTimelineNext=null;
        }
        if(studioCaptionClock!=null){
            ui.removeCallbacks(studioCaptionClock);
            studioCaptionClock=null;
        }
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
                    livePlayer.setContextIds("asset:"+asset.id,"");
                    livePlayer.play(Uri.parse(ProxyManager.previewUri(activeProject,asset)),0L);
                }else if(asset.mime!=null&&asset.mime.startsWith("image/")){
                    livePlayer.pause();
                    studioStillPreview.setVisibility(View.VISIBLE);
                    studioThumbs.bindPreview(studioStillPreview,asset);
                }
            });
            tile.addView(use,margins(-1,dp(36),dp(3),0,0,0));
            if(asset.mime!=null&&(asset.mime.startsWith("video/")||
                    asset.mime.startsWith("image/"))){
                Button add=studioButton("+ Timeline",()->{
                    editHistory.checkpoint(activeProject);
                    if(store.appendAssetToTimeline(activeProject,asset.id)){
                        selectedClip=activeProject.clips.get(activeProject.clips.size()-1);
                        studioTimelinePage=(activeProject.clips.size()-1)/20;
                        studioPlayheadMs=activeProject.outputDurationMs()-selectedClip.outputDurationMs();
                        syncProtocolState();showEditor();
                    }
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

    private void performStudioTool(String name){
        switch(name){
            case "Add Music Track":chooseStudioAudio();return;
            case "Add Caption Track":editStudioTitle(null);return;
            case "Adjust":studioAdvancedAdjustDialog(false);return;
            case "Transform":studioAdvancedAdjustDialog(true);return;
            case "Slow Motion": studioSpeedDialog();return;
            case "Volume": studioVolumeDialog();return;
            case "Blur": studioBlurDialog();return;
            case "Colour": studioPresetDialog("Color grade","colorPreset",
                    new String[]{"none","cinematic","teal_orange","warm_film",
                            "cool_night","noir","golden_hour","matte",
                            "high_contrast","soft_portrait"});return;
            case "Transitions": studioPresetDialog("Clip transition","transition",
                    CreatorCatalog.TRANSITIONS.toArray(new String[0]));return;
            case "Motion": studioPresetDialog("Camera motion","motionPreset",
                    CreatorCatalog.MOTIONS.toArray(new String[0]));return;
            case "Delete Clip": deleteStudioClip(); return;
            case "Move Left": moveStudioClip(-1); return;
            case "Move Right": moveStudioClip(1); return;
            case "Animation Director": animationDirectorDialog();return;
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
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title("Audio & Titles",17),
                new LinearLayout.LayoutParams(0,-2,1));
        header.addView(studioButton("+ Music",this::chooseStudioAudio),
                new LinearLayout.LayoutParams(dp(93),dp(44)));
        header.addView(studioButton("+ Caption",()->editStudioTitle(null)),
                new LinearLayout.LayoutParams(dp(109),dp(44)));
        box.addView(header,margins(-1,-2,dp(10),dp(4),0,0));

        StudioTracksView bands=new StudioTracksView(this);
        bands.setTimeline(activeProject.outputDurationMs(),studioPlayheadMs,
                activeProject.studioLayers,this::scrubStudioTo,
                new StudioTracksView.ChangeListener(){
                    @Override public void onChange(String id,long start,long duration){
                        moveStudioLayerOnTimeline(id,start,duration);
                    }
                    @Override public void onSelect(String id){
                        for(StudioLayer layer:activeProject.studioLayers){
                            if(!layer.id.equals(id))continue;
                            if(StudioLayer.AUDIO.equals(layer.type))
                                editStudioAudio(layer,layer.assetId);
                            else editStudioTitle(layer);
                            return;
                        }
                    }
                });
        box.addView(bands,margins(-1,dp(139),dp(4),dp(6),0,0));
        if(studioAudioWaveforms!=null){
            for(StudioLayer audio:activeProject.studioLayers){
                if(!StudioLayer.AUDIO.equals(audio.type))continue;
                ProjectStore.Asset sample=activeProject.asset(audio.assetId);
                if(sample==null)continue;
                final String id=audio.id;
                final java.lang.ref.WeakReference<StudioTracksView> visible=
                        new java.lang.ref.WeakReference<>(bands);
                studioAudioWaveforms.request(sample,audio,(key,peaks)->{
                    StudioTracksView view=visible.get();
                    if(view!=null&&view.isAttachedToWindow())
                        view.setWaveform(id,peaks,(float)Math.min(1.0,30000.0/Math.max(1,audio.durationMs)));
                });
            }
        }

        ArrayList<StudioLayer> order=new ArrayList<>(activeProject.studioLayers);
        order.sort((a,b)->Long.compare(a.startMs,b.startMs));
        for(StudioLayer lane:order){
            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(C_CARD_2,0xff334661,dp(11)));
            row.setPadding(dp(9),dp(4),dp(9),dp(4));
            ProjectStore.Asset asset=StudioLayer.AUDIO.equals(lane.type)?
                    activeProject.asset(lane.assetId):null;
            String desc=StudioLayer.AUDIO.equals(lane.type)?
                    (asset==null?"Missing audio file":asset.name):lane.text;
            TextView info=body((StudioLayer.AUDIO.equals(lane.type)?"♫  ":"T  ")+desc+
                    "\n"+time(lane.startMs)+" – "+
                    time(lane.startMs+lane.durationMs)+
                    (StudioLayer.AUDIO.equals(lane.type)?
                    "  ·  "+Math.round(lane.gain*100)+"%":""));
            info.setMaxLines(2);info.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(studioButton("Edit",()->{
                if(StudioLayer.AUDIO.equals(lane.type))editStudioAudio(lane,lane.assetId);
                else editStudioTitle(lane);
            }),new LinearLayout.LayoutParams(dp(65),dp(38)));
            row.addView(studioButton("×",()->removeStudioLayer(lane)),
                    new LinearLayout.LayoutParams(dp(42),dp(38)));
            box.addView(row,margins(-1,-2,dp(3),dp(3),0,0));
        }
        box.addView(body("Audio mixes into MP4; titles are burned into the video "+
                "at their chosen times. Preview the exported result to hear "+
                "all tracks together."),margins(-1,-2,dp(2),0,0,0));
    }

    private void moveStudioLayerOnTimeline(String id,long start,long duration){
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
            changed.startMs=start;
            changed.durationMs=duration;
            changed.validate();
            validateStudioLayerTiming(changed);
            editHistory.checkpoint(activeProject);
            replaceStudioLayer(id,changed);
            studioPlayheadMs=start;
            store.save(activeProject);syncProtocolState();showEditor();
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
                        edited.startMs=Math.round(Double.parseDouble(start.getText().toString())*1000);
                        edited.durationMs=Math.round(Double.parseDouble(length.getText().toString())*1000);
                        edited.sourceInMs=Math.round(Double.parseDouble(source.getText().toString())*1000);
                        edited.gain=gain.getProgress()/100f;
                        edited.validate();
                        validateStudioLayerTiming(edited);
                        editHistory.checkpoint(activeProject);
                        if(existing==null)activeProject.studioLayers.add(edited);
                        else replaceStudioLayer(existing.id,edited);
                        store.save(activeProject);syncProtocolState();showEditor();
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
        StudioLayer original=existing==null?new StudioLayer():existing.copy();
        original.type=StudioLayer.TITLE;
        if(existing==null){
            original.startMs=Math.min(studioPlayheadMs,
                    Math.max(0,activeProject.outputDurationMs()-1000));
            original.durationMs=Math.min(2500,
                    Math.max(100,activeProject.outputDurationMs()-original.startMs));
        }
        LinearLayout wrap=column();
        wrap.setPadding(dp(20),dp(6),dp(20),0);
        EditText caption=new EditText(this);
        caption.setSingleLine(false);caption.setMaxLines(3);
        caption.setTextColor(C_TEXT);
        caption.setHint("Enter subtitle or cinematic title");
        caption.setText(original.text);
        wrap.addView(caption);
        wrap.addView(body("Start (seconds)"));
        EditText start=numberInput(original.startMs/1000f);
        wrap.addView(start);
        wrap.addView(body("Duration (seconds)"));
        EditText duration=numberInput(original.durationMs/1000f);
        wrap.addView(duration);
        String[] positions={"Top","Center","Bottom"};
        android.widget.Spinner position=new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_spinner_item,positions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        position.setAdapter(adapter);
        position.setSelection(original.position);
        wrap.addView(body("On-screen position"));
        wrap.addView(position,new LinearLayout.LayoutParams(-1,dp(49)));
        new AlertDialog.Builder(this).setTitle("T  Timed Caption")
                .setView(wrap)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        StudioLayer edited=original.copy();
                        edited.text=caption.getText().toString().trim();
                        edited.startMs=Math.round(Double.parseDouble(start.getText().toString())*1000);
                        edited.durationMs=Math.round(Double.parseDouble(duration.getText().toString())*1000);
                        edited.position=position.getSelectedItemPosition();
                        edited.validate();validateStudioLayerTiming(edited);
                        editHistory.checkpoint(activeProject);
                        if(existing==null)activeProject.studioLayers.add(edited);
                        else replaceStudioLayer(existing.id,edited);
                        store.save(activeProject);syncProtocolState();showEditor();
                    }catch(Exception error){
                        Toast.makeText(this,error.getMessage()==null?"Invalid caption":error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Cancel",null).show();
    }

    private void validateStudioLayerTiming(StudioLayer lane){
        if(lane.startMs+lane.durationMs>activeProject.outputDurationMs())
            throw new IllegalArgumentException("Layer must end inside the video timeline");
        if(activeProject.studioLayers.size()>=32&&
                activeProject.studioLayers.stream().noneMatch(x->x.id.equals(lane.id)))
            throw new IllegalArgumentException("Limit is 32 timed audio/caption layers");
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
        new AlertDialog.Builder(this).setTitle("Remove track?")
                .setMessage("Remove this audio/title layer from the edit? "+
                        "The original imported file is preserved.")
                .setPositiveButton("Remove",(d,w)->{
                    editHistory.checkpoint(activeProject);
                    activeProject.studioLayers.removeIf(x->x.id.equals(lane.id));
                    store.save(activeProject);syncProtocolState();showEditor();
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
        String[] labels=transform?StudioClipAdjustments.TRANSFORM_NAMES:
                StudioClipAdjustments.COLOR_NAMES;
        LinearLayout content=column();
        content.setPadding(dp(16),dp(9),dp(16),dp(9));
        final SeekBar[] controls=new SeekBar[keys.length];
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
                    editHistory.checkpoint(activeProject);
                    for(int i=0;i<keys.length;i++){
                        int low="scale".equals(keys[i])?50:0;
                        StudioClipAdjustments.apply(selectedClip.effects,keys[i],
                                controls[i].getProgress()+low);
                    }
                    store.save(activeProject);syncProtocolState();showEditor();
                })
                .setNeutralButton("Reset",(d,w)->{
                    editHistory.checkpoint(activeProject);
                    StudioClipAdjustments.reset(selectedClip.effects,transform);
                    store.save(activeProject);syncProtocolState();showEditor();
                })
                .setNegativeButton("Cancel",null).show();
    }

    private void updateStudioCaptionAt(long globalTimeMs){
        if(studioCaptionPreview==null||activeProject==null)return;
        List<StudioLayer> active=StudioTrackLayout.activeAt(
                activeProject.studioLayers,StudioLayer.TITLE,globalTimeMs);
        if(active.isEmpty()){
            studioCaptionPreview.setText("");
            studioCaptionPreview.setVisibility(View.GONE);
            return;
        }
        StringBuilder copy=new StringBuilder();
        for(int i=0;i<Math.min(3,active.size());i++){
            if(i>0)copy.append("\n");
            copy.append(active.get(i).text);
        }
        StudioLayer first=active.get(0);
        studioCaptionPreview.setText(copy.toString());
        studioCaptionPreview.setTextColor(first.color);
        studioCaptionPreview.setBackground(rounded(0x99050a12,0,dp(8)));
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

    private void startStudioCaptionClock(ProjectStore.Clip clip,long globalStartMs){
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
                    elapsed=(long)((livePlayer.currentPositionMs()-clip.inMs)/
                            Math.max(.1f,clip.speed));
                }else elapsed=android.os.SystemClock.elapsedRealtime()-systemStart;
                elapsed=Math.max(0,Math.min(clipLength,elapsed));
                long clock=globalStartMs+elapsed;
                updateStudioCaptionAt(clock);
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
        int speed=(int)Math.round(selectedClip.speed*100);
        studioSliderDialog("Playback Speed",25,300,Math.max(25,Math.min(300,speed)),
                "% speed",value->{
                    editHistory.checkpoint(activeProject);
                    selectedClip.speed=value/100f;
                    store.save(activeProject);syncProtocolState();showEditor();
                });
    }
    private void studioVolumeDialog(){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        int volume=(int)Math.round(selectedClip.volume*100);
        studioSliderDialog("Clip Volume",0,200,Math.max(0,Math.min(200,volume)),
                "% gain",value->{
                    editHistory.checkpoint(activeProject);
                    selectedClip.volume=value/100f;
                    store.save(activeProject);syncProtocolState();showEditor();
                });
    }
    private void studioBlurDialog(){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        int amount=(int)Math.round(selectedClip.effects.optDouble("blur",0)*10);
        studioSliderDialog("Gaussian Blur",0,200,Math.max(0,Math.min(200,amount)),
                "tenths",value->{
                    editHistory.checkpoint(activeProject);
                    try{selectedClip.effects.put("blur",value/10.0);}catch(Exception ignored){}
                    store.save(activeProject);syncProtocolState();showEditor();
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
                .setMessage("Edit this clip precisely. Preview the exported MP4 to verify renderer effects.")
                .setView(wrap)
                .setPositiveButton("Apply",(d,w)->onApply.accept(slider.getProgress()+min))
                .setNegativeButton("Cancel",null).show();
    }

    private void studioPresetDialog(String title,String key,String[] presets){
        if(selectedClip==null){Toast.makeText(this,"Select a clip first",
                Toast.LENGTH_SHORT).show();return;}
        String current="transition".equals(key)?selectedClip.transition:
                selectedClip.effects.optString(key,"none");
        int selected=java.util.Arrays.asList(presets).indexOf(current);
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(presets,Math.max(0,selected),(dialog,index)->{
                    editHistory.checkpoint(activeProject);
                    if("transition".equals(key))selectedClip.transition=presets[index];
                    else try{selectedClip.effects.put(key,presets[index]);}
                        catch(Exception ignored){}
                    store.save(activeProject);
                    syncProtocolState();
                    dialog.dismiss();
                    showEditor();
                })
                .setNegativeButton("Cancel",null).show();
    }

    private void deleteStudioClip(){
        if(activeProject==null||selectedClip==null)return;
        final String clipId=selectedClip.id;
        new AlertDialog.Builder(this).setTitle("Remove timeline clip?")
                .setMessage("This removes the selected clip from the edit, "+
                        "but keeps the original imported media safely in VideoStudio.")
                .setPositiveButton("Remove",(d,w)->{
                    editHistory.checkpoint(activeProject);
                    activeProject.clips.removeIf(c->clipId.equals(c.id));
                    selectedClip=activeProject.clips.isEmpty()?null:
                            activeProject.clips.get(Math.min(activeProject.clips.size()-1,
                                    Math.max(0,studioTimelinePage*20)));
                    studioPlayheadMs=0;
                    store.save(activeProject);syncProtocolState();showEditor();
                }).setNegativeButton("Cancel",null).show();
    }
    private void moveStudioClipTo(String clipId,int targetIndex){
        if(activeProject==null||clipId==null)return;
        ProjectStore.Project fresh=store.get(activeProject.id);
        if(fresh==null)return;
        if(fresh.updatedAt!=activeProject.updatedAt){
            activeProject=fresh;editHistory.reset(null);showEditor();
            Toast.makeText(this,"Project changed in background; retry the move",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int current=-1;
        for(int i=0;i<activeProject.clips.size();i++){
            if(clipId.equals(activeProject.clips.get(i).id)){current=i;break;}
        }
        if(current<0||targetIndex<0||targetIndex>=activeProject.clips.size()||
                current==targetIndex)return;
        editHistory.checkpoint(activeProject);
        if(!StudioTimelineEditOps.move(activeProject.clips,clipId,targetIndex))return;
        selectedClip=activeProject.clips.get(targetIndex);
        studioPlayheadMs=StudioTimelineEditOps.startOf(activeProject.clips,clipId);
        studioTimelinePage=targetIndex/20;
        store.save(activeProject);syncProtocolState();showEditor();
    }

    private void moveStudioClip(int distance){
        if(activeProject==null||selectedClip==null)return;
        int at=-1;
        for(int i=0;i<activeProject.clips.size();i++)
            if(selectedClip.id.equals(activeProject.clips.get(i).id)){at=i;break;}
        if(at<0||at+distance<0||at+distance>=activeProject.clips.size())return;
        editHistory.checkpoint(activeProject);
        java.util.Collections.swap(activeProject.clips,at,at+distance);
        studioTimelinePage=(at+distance)/20;
        studioPlayheadMs=0;
        for(int i=0;i<at+distance;i++)
            studioPlayheadMs+=activeProject.clips.get(i).outputDurationMs();
        store.save(activeProject);syncProtocolState();showEditor();
    }

    private void restoreStudioHistory(boolean redo){
        if(activeProject==null)return;
        ProjectStore.Project latest=store.get(activeProject.id);
        if(latest==null||latest.updatedAt!=activeProject.updatedAt){
            Toast.makeText(this,"Project changed in background. Reload before Undo.",
                    Toast.LENGTH_LONG).show();
            if(latest!=null)activeProject=latest;
            editHistory.reset(null);
            showEditor();return;
        }
        String previous=selectedClip==null?"":selectedClip.id;
        ProjectStore.Project restored=redo
                ?editHistory.redo(activeProject):editHistory.undo(activeProject);
        if(restored==null)return;
        activeProject=restored;
        selectedClip=null;
        for(ProjectStore.Clip c:restored.clips)
            if(previous.equals(c.id)){selectedClip=c;break;}
        if(selectedClip==null&&!restored.clips.isEmpty())
            selectedClip=restored.clips.get(0);
        store.save(activeProject);syncProtocolState();showEditor();
    }

    private void findStudioTool(){
        EditText input=new EditText(this);
        input.setHint("Search trim, speed, masks, audio, animation...");
        input.setSingleLine(true);
        input.setTextColor(C_TEXT);
        LinearLayout holder=column();
        holder.setPadding(dp(18),dp(5),dp(18),0);
        holder.addView(input);
        new AlertDialog.Builder(this).setTitle("Find VideoStudio Tool")
                .setView(holder).setPositiveButton("Search",(dialog,which)->{
                    List<StudioToolCatalog.Tool> results=StudioToolCatalog.find(
                            input.getText().toString());
                    String[] labels=new String[results.size()];
                    for(int i=0;i<results.size();i++){
                        StudioToolCatalog.Tool t=results.get(i);
                        labels[i]=t.label+"  ·  "+t.group;
                    }
                    new AlertDialog.Builder(this).setTitle(
                            results.isEmpty()?"No matching tools":"Select a tool")
                            .setItems(labels,(d,index)->performStudioTool(
                                    results.get(index).label))
                            .setPositiveButton("Close",null).show();
                }).setNegativeButton("Cancel",null).show();
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
            text.append("\nHeavy GPU/encoding work must cool before it resumes.");
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
        exportButton.setOnClickListener(v -> {
            if (activeProject == null || activeProject.clips.isEmpty()) {
                Toast.makeText(this, "Timeline is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent export = new Intent(this, ControlService.class)
                    .setAction(ControlService.ACTION_LOCAL_EXPORT)
                    .putExtra("projectId", activeProject.id)
                    .putExtra("aspect", "9:16")
                    .putExtra("quality", "1080p")
                    .putExtra("fileName", "VideoStudio_" + System.currentTimeMillis() + ".mp4");
            startForegroundService(export);
            Toast.makeText(this, "Export queued in Native Agent • watch Autonomous work", Toast.LENGTH_LONG).show();
        });
        top.addView(exportButton);
        box.addView(top, margins(-1, -2, 0, dp(10), 0, 0));
        Button directAnimation = neonButton("Animation Director • Make a Real Sequence", C_CYAN);
        directAnimation.setOnClickListener(v -> animationDirectorDialog());
        box.addView(directAnimation,margins(-1,dp(47),0,0,0,dp(12)));
        Button model3D=neonButton("Real 3D GLB Model • Skinned Motion Preview",C_CYAN);
        model3D.setOnClickListener(v -> open3DModelViewer());
        box.addView(model3D,margins(-1,dp(48),0,0,0,dp(10)));
        Button render3D=neonButton("Export Real 3D Sword Fight • GLB",C_MAGENTA);
        render3D.setOnClickListener(v -> choose3DCombatActors());
        box.addView(render3D,margins(-1,dp(48),0,0,0,dp(10)));
        Button rigEditor=neonButton("Character Rig Builder • Tap Bones + Trace Body",C_CYAN);
        rigEditor.setOnClickListener(v -> open2DRigEditor());
        box.addView(rigEditor,margins(-1,dp(48),0,0,0,dp(10)));
        Button benchmark=compactButton("True Combat Rig Test");
        benchmark.setOnClickListener(v -> combatRigBenchmarkDialog());
        box.addView(benchmark,margins(-1,dp(44),0,0,0,dp(10)));

        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackground(rounded(Color.BLACK, Color.rgb(37, 51, 83), dp(18)));
        viewer.setMinimumHeight(dp(280));
        livePlayer.attach(viewer);
        TextView hint = body(activeProject.assets.isEmpty()
                ? "No source media yet. Import a file, or ask ChatGPT to create a prompt video."
                : "Select a clip below");
        hint.setGravity(Gravity.CENTER);
        if (livePlayer.hasMedia()) hint.setVisibility(View.GONE);
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
        LivePlaybackState playbackState = livePlayer.snapshotState();
        if (latestPreview != null && !latestPreview.id.equals(playbackState.snapshotId)) {
            Button playNew = compactButton("Play new result");
            playNew.setOnClickListener(v -> {
                livePlayer.setContextIds(latestPreview.id, "");
                livePlayer.play(Uri.parse(latestPreview.uri), 0L);
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
                        livePlayer.setContextIds("", "");
                        livePlayer.play(Uri.parse(ProxyManager.previewUri(activeProject, asset)), 0L);
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

    /** Organized AI and advanced tools, not a wall of oversized cards. */
    private void showTools(){
        ScrollView scroll=baseScroll();
        LinearLayout box=column();
        box.setPadding(dp(14),dp(14),dp(14),dp(26));
        scroll.addView(box);
        box.addView(title("AI Magic & Pro Tools",25));
        box.addView(body("Choose what you're creating. Every button opens a real VideoStudio action; "+
                "experimental presets are clearly marked."),
                margins(-1,-2,dp(3),dp(7),0,0));
        LinearLayout prompt=card(true);
        prompt.addView(title("Prompt → Finished Video",18));
        prompt.addView(body("Describe the scenes, movement, pacing and style."));
        Button generate=neonButton("✦ Generate from Prompt",C_MAGENTA);
        generate.setOnClickListener(v->promptVideoDialog());
        prompt.addView(generate,margins(-1,dp(50),dp(8),0,0,0));
        box.addView(prompt);

        String[] tabs={"Create","Animate","Enhance","Text & Audio","Manage"};
        HorizontalScrollView nav=new HorizontalScrollView(this);
        nav.setHorizontalScrollBarEnabled(false);
        LinearLayout choices=new LinearLayout(this);
        for(int i=0;i<tabs.length;i++){
            final int n=i;
            Button button=studioButton(tabs[i],()->{
                studioToolsHubTab=n;showTools();
            });
            button.setBackground(rounded(studioToolsHubTab==i?0xff213e77:C_CARD_2,
                    studioToolsHubTab==i?C_CYAN:0xff303d5c,dp(12)));
            choices.addView(button,margins(dp(119),dp(46),0,0,0,dp(6)));
        }
        nav.addView(choices);
        box.addView(nav,margins(-1,dp(52),dp(10),0,0,0));

        ArrayList<String[]> sections=new ArrayList<>();
        switch(studioToolsHubTab){
            case 0:
                sections.add(new String[]{"Open Human Editor","Editor"});
                sections.add(new String[]{"New Project","New"});
                sections.add(new String[]{"Import Media","Import"});
                sections.add(new String[]{"Watch All Renders","Renders"});
                sections.add(new String[]{"AI Storyboard Director","Animation Director"});
                break;
            case 1:
                sections.add(new String[]{"Animate Imported Stills","Animate Stills"});
                sections.add(new String[]{"Animation Director","Animation Director"});
                sections.add(new String[]{"Rig Original Characters","Rig Builder"});
                sections.add(new String[]{"Render Rigged 2D","Render Rigged 2D"});
                sections.add(new String[]{"Preview 3D GLB","3D Model Viewer"});
                sections.add(new String[]{"Render 3D Combat","Render 3D Fight"});
                sections.add(new String[]{"Combat Motion Test","Combat Benchmark"});
                break;
            case 2:
                for(StudioToolCatalog.Tool t:StudioToolCatalog.group(2))
                    sections.add(new String[]{t.label+(t.experimental?" *":""),t.label});
                for(StudioToolCatalog.Tool t:StudioToolCatalog.group(1))
                    sections.add(new String[]{t.label+(t.experimental?" *":""),t.label});
                break;
            case 3:
                for(StudioToolCatalog.Tool t:StudioToolCatalog.group(3))
                    sections.add(new String[]{t.label+(t.experimental?" *":""),t.label});
                break;
            case 4:
                sections.add(new String[]{"Connect ChatGPT","Connect"});
                sections.add(new String[]{"Native Jobs & Thermal","Jobs"});
                sections.add(new String[]{"My Projects","Projects"});
                sections.add(new String[]{"Archive to Google Drive","Archive"});
                sections.add(new String[]{"All Generated MP4s","Renders"});
                break;
        }
        for(int i=0;i<sections.size();i+=2){
            LinearLayout row=new LinearLayout(this);
            for(int j=i;j<Math.min(i+2,sections.size());j++){
                String label=sections.get(j)[0];
                String action=sections.get(j)[1];
                Button button=studioButton(label,()->{
                    switch(action){
                        case "Editor":showEditor();return;
                        case "New":createProjectDialog();return;
                        case "Import":pickMedia();return;
                        case "Renders":showStudioRenderLibrary();return;
                        case "Animate Stills":animateImagesDialog();return;
                        case "Connect":showStudioConnectionHub();return;
                        case "Jobs":showStudioJobDetails();return;
                        case "Projects":showStudioProjectLibrary();return;
                        case "Archive":archiveActiveProjectToCloud();return;
                        default:performStudioTool(action);return;
                    }
                });
                row.addView(button,new LinearLayout.LayoutParams(0,dp(60),1));
            }
            if(sections.size()-i==1)row.addView(new View(this),
                    new LinearLayout.LayoutParams(0,dp(60),1));
            box.addView(row,margins(-1,dp(60),dp(6),0,0,0));
        }
        box.addView(studioButton("⌕ Search All Studio Tools",this::findStudioTool),
                margins(-1,dp(47),dp(12),0,0,0));
        box.addView(body("* Experimental effects need rendered-MP4 QA. "+
                "ChatGPT autonomous control is optional; the same editing tools work manually."),
                margins(-1,-2,dp(8),0,0,0));
        setScreen(scroll,"tools");
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

        box.addView(title("Autonomous Control", 27));
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
        JobManager.Job job = jobs.submit("Restore project • " + project.name, JobManager.Kind.LIGHT, state -> {
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
                livePlayer.setContextIds("final:" + activeProject.latestExportAt, "");
                livePlayer.play(Uri.parse(activeProject.latestExportUri), 0L);
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
            if(!"Split".equals(tool)&&!"Trim".equals(tool)&&!"Text".equals(tool))
                editHistory.checkpoint(activeProject);
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
            boolean experimental=false;
            for(StudioToolCatalog.Tool item:StudioToolCatalog.find(tool)){
                if(tool.equals(item.label)){experimental=item.experimental;break;}
            }
            Toast.makeText(this,experimental?
                            tool+" saved · verify in exported MP4":
                            tool+" applied to selected clip",
                    Toast.LENGTH_SHORT).show();
            showEditor();
        } catch (Exception error) {
            Toast.makeText(this, "Could not apply " + tool, Toast.LENGTH_SHORT).show();
        }
    }

    private void splitClip() {
        if(selectedClip==null||activeProject==null)return;
        StudioTimelineClock.Position where=
                StudioTimelineClock.locate(activeProject.clips,studioPlayheadMs);
        long at=selectedClip.inMs+(selectedClip.outMs-selectedClip.inMs)/2;
        if(where.clipIndex>=0&&where.clipIndex<activeProject.clips.size()&&
                activeProject.clips.get(where.clipIndex).id.equals(selectedClip.id)&&
                where.offsetInClipMs>150){
            at=where.assetTimeMs;
        }
        if (at <= selectedClip.inMs + 250 || at >= selectedClip.outMs - 250) {
            Toast.makeText(this, "Move playback inside the clip before splitting", Toast.LENGTH_SHORT).show();
            return;
        }
        editHistory.checkpoint(activeProject);
        int index = activeProject.clips.indexOf(selectedClip);
        ProjectStore.Clip second = ProjectStore.Clip.fromJson(selectedClip.toJson());
        second.id = UUID.randomUUID().toString();
        second.inMs = at;
        selectedClip.outMs = at;
        activeProject.clips.add(index + 1, second);
        store.save(activeProject);
        syncProtocolState();
        showEditor();
    }

    private void trimDialog() {
        if(activeProject==null||selectedClip==null)return;
        ProjectStore.Asset asset=activeProject.asset(selectedClip.assetId);
        final long maximum=StudioTrimMath.sourceLength(
                asset==null?0:asset.durationMs,selectedClip);
        LinearLayout wrap=column();
        wrap.setPadding(dp(20),dp(5),dp(20),dp(7));
        wrap.addView(body("Drag the trim handles, or type exact seconds."));
        wrap.addView(body("Start / In-point"));
        EditText start=numberInput(selectedClip.inMs/1000f);
        wrap.addView(start);
        SeekBar inSeek=new SeekBar(this);
        inSeek.setMax(1000);
        inSeek.setProgress(StudioTrimMath.toSeek(selectedClip.inMs,maximum));
        inSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int position,boolean user){
                if(user)start.setText(String.format(Locale.US,"%.2f",
                        StudioTrimMath.fromSeek(position,maximum)/1000.0));
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        wrap.addView(inSeek,margins(-1,dp(45),0,0,0,0));
        wrap.addView(body("End / Out-point"));
        EditText end=numberInput(selectedClip.outMs/1000f);
        wrap.addView(end);
        SeekBar outSeek=new SeekBar(this);
        outSeek.setMax(1000);
        outSeek.setProgress(StudioTrimMath.toSeek(selectedClip.outMs,maximum));
        outSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int position,boolean user){
                if(user)end.setText(String.format(Locale.US,"%.2f",
                        StudioTrimMath.fromSeek(position,maximum)/1000.0));
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        wrap.addView(outSeek,margins(-1,dp(45),0,0,0,0));
        Button playheadIn=studioButton("Use Playhead as In",()->{
            StudioTimelineClock.Position p=StudioTimelineClock.locate(
                    activeProject.clips,studioPlayheadMs);
            if(p.clipIndex>=0&&activeProject.clips.get(p.clipIndex).id.equals(selectedClip.id)){
                long seek=Math.max(0,Math.min(maximum,p.assetTimeMs));
                start.setText(String.format(Locale.US,"%.2f",seek/1000.0));
                inSeek.setProgress(StudioTrimMath.toSeek(seek,maximum));
            }
        });
        wrap.addView(playheadIn,margins(-1,dp(42),dp(6),0,0,0));
        new AlertDialog.Builder(this).setTitle("Trim selected clip")
                .setView(wrap)
                .setPositiveButton("Apply",(d,w)->{
                    try{
                        long a=Math.round(Double.parseDouble(start.getText().toString())*1000);
                        long b=Math.round(Double.parseDouble(end.getText().toString())*1000);
                        if(!StudioTrimMath.valid(a,b,maximum))
                            throw new IllegalArgumentException("Trim must fit source and be at least 0.1 seconds");
                        editHistory.checkpoint(activeProject);
                        selectedClip.inMs=a;
                        selectedClip.outMs=b;
                        store.save(activeProject);syncProtocolState();
                        showEditor();
                    }catch(Exception error){
                        Toast.makeText(this,error.getMessage()==null?
                                "Invalid trim range":error.getMessage(),Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Cancel",null).show();
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
                    editHistory.checkpoint(activeProject);
                    selectedClip.title = input.getText().toString().trim();
                    store.save(activeProject);
                    syncProtocolState();
                    showEditor();
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

