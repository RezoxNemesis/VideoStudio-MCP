package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AppProtocol.Callback {
    private static final int PICK_MEDIA = 1201;
    private static final int PICK_CLOUD_WORKSPACE = 1202;
    private static final int PICK_STORAGE_FOLDER = 1203;
    private static final int PICK_RELINK_SOURCE = 1204;
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
    private String currentScreen = "home";
    private EditorThumbnailCache editorThumbnails;
    private EditorWaveformCache editorWaveforms;
    private EditorTimelineView editorTimeline;
    private FrameLayout editorPreviewHost;
    private LinearLayout editorPanel;
    private TextView editorTimecode;
    private TextView editorPreviewBadge;
    private TextView editorStatus;
    private Button editorPlayButton;
    private boolean programMonitor = true;
    private long ownerPlayheadMs;
    private long sourcePlayheadMs;
    private String sourceAssetId = "";
    private String editorPanelTab = "Media";
    private String editorProjectId = "";
    private boolean previewGuides;
    private boolean previewCompare;
    private boolean previewTransformHandles;
    private PreviewTransformView previewTransformView;
    private EditorRig2DView editorRigView;
    private EditorAnimationDopeSheet animationDopeSheet;
    private final EditorAnimationDopeSheet.State animationDopeState = new EditorAnimationDopeSheet.State();
    private boolean animationDopeVisible;
    private String selectedRigBoneId = "";
    private boolean rigOverlayVisible = true;
    private boolean rigBindMode;
    private boolean rigMeshVisible;
    private String previewHandleClipId = "";
    private long previewHandleTime = -1;
    private Runnable inspectorRefresh;
    private boolean inspectorGesture;
    private String previewResultUri = "";
    private String previewResultId = "";
    private int mediaBinOffset;
    private String mediaBinSearch = "";
    private String insertTrackId = "";
    private boolean insertAtPlayhead;
    private String previewProxyTier = "auto";
    private boolean ownerTitlePending;
    private boolean ownerCelPending;
    private String previewNotice = "";
    private long previewBadgeRefreshAt;
    private float keyboardShuttleRate;
    private EditorAudioMeterView editorAudioMeters;
    private StorageProfiles storageProfiles;
    private int pendingStorageSlot = -1;
    private String pendingRelinkProjectId = "";
    private String pendingRelinkAssetId = "";
    private long pendingRelinkRevision = -1;
    private int storageViewGeneration;
    private final ExecutorService ownerMediaWorker = Executors.newSingleThreadExecutor();
    private String manualExportSessionId = "";
    private TextView exportStatusView;
    private ProgressBar exportProgressView;
    private boolean exportReceiverRegistered;
    private boolean ownerExportInFlight;
    private final BroadcastReceiver exportReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!manualExportSessionId.equals(intent.getStringExtra("sessionId"))) return;
            String status = intent.getStringExtra("status");
            String detail = intent.getStringExtra("detail");
            ownerExportDetail = (status == null ? "Export" : status.replace('_', ' ')) + " · " + (detail == null ? "" : detail);
            ownerExportInFlight = status != null && !isTerminalJobState(status) && !"success".equals(status);
            if (exportStatusView != null) exportStatusView.setText(ownerExportDetail);
            if (exportProgressView != null) { exportProgressView.setIndeterminate(false); exportProgressView.setProgress(intent.getIntExtra("progress", 0)); }
            if (!ownerExportInFlight) {
                activeProject = activeProject == null ? null : store.get(activeProject.id);
                if ("export".equals(currentScreen)) showExport();
            }
        }
    };

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
        driveWorkspace = new DriveWorkspaceProvider(this);
        storageProfiles = new StorageProfiles(this);
        previewSnapshots = new PreviewSnapshotStore(this);
        proxyManager = new ProxyManager(this, store, jobs);
        livePlayer = new LiveEditPlayer(this);
        editorThumbnails = new EditorThumbnailCache(this);
        editorWaveforms = new EditorWaveformCache(this);
        livePlayer.setListener(new LiveEditPlayer.Listener() {
            @Override public void onPosition(long positionMs, long durationMs, boolean playing) {
                if (previewCompare && selectedClip != null) ownerPlayheadMs = selectedClip.startMs + Math.round((positionMs - selectedClip.inMs) / Math.max(Float.MIN_NORMAL, selectedClip.effectiveSpeed()));
                else if (programMonitor) ownerPlayheadMs = positionMs; else sourcePlayheadMs = positionMs;
                if (editorTimecode != null) editorTimecode.setText(timecode(previewCompare ? ownerPlayheadMs : positionMs) + " / " + timecode(previewCompare && activeProject != null ? activeProject.outputDurationMs() : durationMs));
                if (editorPlayButton != null) editorPlayButton.setText(playing ? "Ⅱ" : "▶");
                if (editorTimeline != null && programMonitor) editorTimeline.setPlayhead(ownerPlayheadMs, playing);
                if(editorAudioMeters!=null&&"editor".equals(currentScreen))editorAudioMeters.setLevels(livePlayer.audioLevels());
                if ("editor".equals(currentScreen)) {
                    refreshPreviewTransformHandles();
                    refreshRigOverlay();
                    if(animationDopeSheet!=null)animationDopeSheet.setPlayhead(clipOutputPosition(),playing);
                    long now=android.os.SystemClock.uptimeMillis();
                    if(!playing||now-previewBadgeRefreshAt>=250){previewBadgeRefreshAt=now;refreshPreviewBadge();}
                }
            }
            @Override public void onError(String message) {
                previewNotice=message==null?"Preview reported an unavailable operation":message;
                if (editorStatus != null) editorStatus.setText("Preview notice · " + previewNotice);
            }
            @Override public void onReady() {
                syncPreviewNotices();
            }
        });
        activeProject = store.active();
        if (savedInstanceState != null) {
            pendingStorageSlot = savedInstanceState.getInt("pendingStorageSlot", -1);
            pendingRelinkProjectId = savedInstanceState.getString("pendingRelinkProject", "");
            pendingRelinkAssetId = savedInstanceState.getString("pendingRelinkAsset", "");
            pendingRelinkRevision = savedInstanceState.getLong("pendingRelinkRevision", -1);
            manualExportSessionId = savedInstanceState.getString("manualExportSession", "");
            ownerExportAspect = savedInstanceState.getString("exportAspect", "9:16");
            ownerExportQuality = savedInstanceState.getString("exportQuality", "1080p");
            ownerExportMode = savedInstanceState.getString("exportMode", "full");
            ownerExportRangeInMs = savedInstanceState.getLong("exportRangeIn", 0);
            ownerExportRangeOutMs = savedInstanceState.getLong("exportRangeOut", 0);
            ownerExportRangeRevision = savedInstanceState.getLong("exportRangeRevision", -1);
            ownerExportRangeProjectId = savedInstanceState.getString("exportRangeProject", "");
            livePlayer.setLoop(savedInstanceState.getBoolean("previewLoop", false));
            ProjectStore.Project restored = store.get(savedInstanceState.getString("editorProject", ""));
            if (restored != null) {
                activeProject = restored; editorProjectId = restored.id;
                selectedClip = restored.clip(savedInstanceState.getString("editorClip", ""));
                ownerPlayheadMs = savedInstanceState.getLong("editorPlayhead", 0);
                sourcePlayheadMs = savedInstanceState.getLong("sourcePlayhead", 0);
                sourceAssetId = savedInstanceState.getString("sourceAsset", "");
                programMonitor = savedInstanceState.getBoolean("programMonitor", true);
                editorPanelTab = savedInstanceState.getString("editorPanel", "Media");
                insertTrackId = savedInstanceState.getString("insertTrack", "");
                insertAtPlayhead = savedInstanceState.getBoolean("insertAtPlayhead", false);
                previewProxyTier = savedInstanceState.getString("previewProxyTier", "auto");
                selectedRigBoneId = savedInstanceState.getString("rigBone", "");
                rigOverlayVisible = savedInstanceState.getBoolean("rigOverlay", true);
                rigBindMode = savedInstanceState.getBoolean("rigBind", false);
                rigMeshVisible = savedInstanceState.getBoolean("rigMesh", false);
            }
        }
        protocol = new AppProtocol(this, this);
        syncProtocolState();

        setContentView(buildShell());
        if (savedInstanceState != null && "editor".equals(savedInstanceState.getString("screen"))) showEditor();
        else if (savedInstanceState != null && "export".equals(savedInstanceState.getString("screen"))) showExport();
        else if (savedInstanceState != null && "storage".equals(savedInstanceState.getString("screen"))) showStorageHub();
        else showHome();
        handleMcpRebindIntent(getIntent());
        requestServiceSync();
        startServiceWatchdog();
        IntentFilter exportFilter = new IntentFilter("com.rezoxnemesis.videostudio.LOCAL_EXPORT_STATUS");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(exportReceiver, exportFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(exportReceiver, exportFilter);
        exportReceiverRegistered = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleMcpRebindIntent(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", currentScreen);
        state.putString("editorProject", activeProject == null ? "" : activeProject.id);
        state.putString("editorClip", selectedClip == null ? "" : selectedClip.id);
        state.putLong("editorPlayhead", ownerPlayheadMs);
        state.putLong("sourcePlayhead", sourcePlayheadMs);
        state.putString("sourceAsset", sourceAssetId);
        state.putBoolean("programMonitor", programMonitor);
        state.putString("editorPanel", editorPanelTab);
        state.putString("insertTrack", insertTrackId);
        state.putBoolean("insertAtPlayhead", insertAtPlayhead);
        state.putString("previewProxyTier", previewProxyTier);
        state.putString("rigBone",selectedRigBoneId);
        state.putBoolean("rigOverlay",rigOverlayVisible);
        state.putBoolean("rigBind",rigBindMode);
        state.putBoolean("rigMesh",rigMeshVisible);
        state.putInt("pendingStorageSlot", pendingStorageSlot);
        state.putString("pendingRelinkProject", pendingRelinkProjectId);
        state.putString("pendingRelinkAsset", pendingRelinkAssetId);
        state.putLong("pendingRelinkRevision", pendingRelinkRevision);
        state.putString("manualExportSession", manualExportSessionId);
        state.putString("exportAspect", ownerExportAspect);
        state.putString("exportQuality", ownerExportQuality);
        state.putString("exportMode", ownerExportMode);
        state.putLong("exportRangeIn", ownerExportRangeInMs);
        state.putLong("exportRangeOut", ownerExportRangeOutMs);
        state.putLong("exportRangeRevision", ownerExportRangeRevision);
        state.putString("exportRangeProject", ownerExportRangeProjectId);
        state.putBoolean("previewLoop", livePlayer != null && livePlayer.isLooping());
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(!"editor".equals(currentScreen)||getCurrentFocus() instanceof EditText)return super.onKeyDown(keyCode,event);
        if(event.getRepeatCount()>0&&(keyCode==KeyEvent.KEYCODE_J||keyCode==KeyEvent.KEYCODE_K||keyCode==KeyEvent.KEYCODE_L))return true;
        switch(keyCode){
            case KeyEvent.KEYCODE_J:shuttleEditor(keyboardShuttleRate<0?Math.max(-8,keyboardShuttleRate*2):-1);return true;
            case KeyEvent.KEYCODE_K:shuttleEditor(0);return true;
            case KeyEvent.KEYCODE_L:shuttleEditor(keyboardShuttleRate>0&&livePlayer.isPlaying()?Math.min(8,keyboardShuttleRate*2):1);return true;
            case KeyEvent.KEYCODE_SPACE:toggleEditorPlayback();return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:stepEditorFrame(-33);return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:stepEditorFrame(33);return true;
            default:return super.onKeyDown(keyCode,event);
        }
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
        if (inspectorRefresh != null) ui.removeCallbacks(inspectorRefresh);
        if (livePlayer != null) livePlayer.release();
        if (editorThumbnails != null) editorThumbnails.close();
        if (editorWaveforms != null) editorWaveforms.close();
        ownerMediaWorker.shutdown();
        if (exportReceiverRegistered) unregisterReceiver(exportReceiver);
        if (protocol != null) protocol.stop();
        if (jobs != null) jobs.shutdown(true);
        super.onDestroy();
    }

    @Override protected void onPause() {
        if (livePlayer != null) livePlayer.pause();
        super.onPause();
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
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return root;
    }

    private void setScreen(View view, String name) {
        if (activityRefresh != null) ui.removeCallbacks(activityRefresh);
        if (!"editor".equals(name) && livePlayer != null) livePlayer.pause();
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
        if (activeProject == null) activeProject = store.create("Untitled Project");
        ProjectStore.Project latest = store.get(activeProject.id);
        if (latest != null) activeProject = latest;
        if (!activeProject.id.equals(editorProjectId)) {
            editorProjectId = activeProject.id;
            ownerPlayheadMs = 0;
            sourcePlayheadMs = 0;
            sourceAssetId = "";
            programMonitor = true;
            selectedClip = null;
            previewResultUri = ""; previewResultId = "";
            mediaBinOffset = 0; mediaBinSearch = "";
            insertTrackId = "";
        }
        String selectedId = selectedClip == null ? "" : selectedClip.id;
        selectedClip = activeProject.clip(selectedId);
        if (selectedClip == null && !activeProject.clips.isEmpty()) selectedClip = activeProject.clips.get(0);
        ownerPlayheadMs = Math.max(0, Math.min(ownerPlayheadMs, activeProject.outputDurationMs()));

        LinearLayout editor = column();
        editor.setPadding(dp(8), dp(5), dp(8), dp(5));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = title(activeProject.name, 17);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setOnClickListener(v -> renameProjectDialog());
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView saved = accent("Saved · r" + activeProject.revision, C_CYAN);
        saved.setTextSize(10);
        header.addView(saved);
        Button export = editorButton("Export ↗", this::showExport);
        export.setTextColor(C_CYAN);
        header.addView(export);
        editor.addView(header, new LinearLayout.LayoutParams(-1, dp(40)));

        HorizontalScrollView commands = horizontalStrip();
        LinearLayout commandRow = new LinearLayout(this);
        commandRow.addView(editorButton("＋ Import", this::pickMedia));
        JSONObject history = store.historyState(activeProject.id);
        Button undo = editorButton("↶ Undo", () -> ownerHistory(false));
        undo.setEnabled(history.optBoolean("canUndo")); undo.setAlpha(undo.isEnabled() ? 1f : .4f);
        commandRow.addView(undo);
        Button redo = editorButton("↷ Redo", () -> ownerHistory(true));
        redo.setEnabled(history.optBoolean("canRedo")); redo.setAlpha(redo.isEnabled() ? 1f : .4f);
        commandRow.addView(redo);
        commandRow.addView(editorButton("Snapshots", this::projectSnapshotsDialog));
        commandRow.addView(editorButton(programMonitor ? "● Program" : "Program", () -> setMonitorMode(true)));
        commandRow.addView(editorButton(programMonitor ? "Source" : "● Source", () -> setMonitorMode(false)));
        commandRow.addView(editorButton("⛶", this::fullscreenPreview));
        Button loopButton=editorButton(previewLoopLabel(),()->{});
        loopButton.setOnClickListener(v->{livePlayer.setLoop(!livePlayer.isLooping());loopButton.setText(previewLoopLabel());refreshPreviewBadge();});commandRow.addView(loopButton);
        commandRow.addView(editorButton("Shuttle",this::shuttleDialog));
        commandRow.addView(editorButton(previewGuides ? "Grid ●" : "Grid", () -> { previewGuides = !previewGuides; showEditor(); }));
        commandRow.addView(editorButton(previewCompare ? "Before ●" : "Before", () -> {
            if(!requireSelectedClip())return;
            previewCompare=!previewCompare;programMonitor=true;previewResultUri="";previewResultId="";livePlayer.pause();
            if(previewCompare)ownerPlayheadMs=Math.max(selectedClip.startMs,Math.min(ownerPlayheadMs,selectedClip.endMs()-1));
            showEditor();
        }));
        commandRow.addView(editorButton(previewTransformHandles ? "Transform ●" : "Transform", () -> { previewTransformHandles = !previewTransformHandles; livePlayer.pause(); showEditor(); }));
        commands.addView(commandRow);
        editor.addView(commands, new LinearLayout.LayoutParams(-1, dp(36)));

        previewTransformView = null;
        editorRigView = null;
        editorPreviewHost = new FrameLayout(this);
        editorPreviewHost.setBackgroundColor(Color.BLACK);
        livePlayer.attach(editorPreviewHost);
        if (previewGuides && programMonitor && !previewCompare && previewResultUri.isEmpty()) editorPreviewHost.addView(new PreviewGuideView(this, programmeAspect()), new FrameLayout.LayoutParams(-1, -1));
        editorPreviewBadge = accent("", C_MUTED);
        editorPreviewBadge.setTextSize(9);
        editorPreviewBadge.setPadding(dp(7), dp(3), dp(7), dp(3));
        editorPreviewBadge.setBackgroundColor(Color.argb(160, 0, 0, 0));
        FrameLayout.LayoutParams badgeLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        editorPreviewHost.addView(editorPreviewBadge, badgeLayout);
        refreshPreviewTransformHandles();
        if (activeProject.assets.isEmpty()) {
            LinearLayout empty = column(); empty.setGravity(Gravity.CENTER);
            empty.addView(title("Your preview appears here", 17));
            TextView hint = body("Import a video, photo, or audio file to begin."); hint.setGravity(Gravity.CENTER);
            empty.addView(hint);
            empty.addView(editorButton("＋ Import media", this::pickMedia));
            editorPreviewHost.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        }
        editor.addView(editorPreviewHost, new LinearLayout.LayoutParams(-1, 0, 4.2f));

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.addView(editorButton("|◀", () -> seekEditor(0)));
        transport.addView(editorButton("−5s", () -> seekEditor(editorPosition() - 5000)));
        transport.addView(editorButton("‹", () -> stepEditorFrame(-33)));
        editorPlayButton = editorButton(livePlayer.isPlaying() ? "Ⅱ" : "▶", this::toggleEditorPlayback);
        editorPlayButton.setTextColor(C_CYAN);
        transport.addView(editorPlayButton);
        transport.addView(editorButton("›", () -> stepEditorFrame(33)));
        transport.addView(editorButton("+5s", () -> seekEditor(editorPosition() + 5000)));
        editorTimecode = accent(timecode(editorPosition()) + " / " + timecode(activeProject.outputDurationMs()), C_TEXT);
        editorTimecode.setTextSize(10); editorTimecode.setGravity(Gravity.RIGHT);
        transport.addView(editorTimecode, new LinearLayout.LayoutParams(0, -2, 1));
        editor.addView(transport, new LinearLayout.LayoutParams(-1, dp(39)));

        editorTimeline = new EditorTimelineView(this, editorThumbnails, editorWaveforms);
        editorTimeline.setProject(activeProject, selectedClip == null ? "" : selectedClip.id);
        editorTimeline.setInsertionTrack(insertTrackId);
        editorTimeline.setPlayhead(ownerPlayheadMs, false);
        editorTimeline.setListener(new EditorTimelineView.Listener() {
            @Override public void onScrub(long positionMs) {
                livePlayer.pause();
                boolean sourceWasBound=!programMonitor||previewCompare||!previewResultUri.isEmpty();
                previewCompare=false;previewResultUri="";previewResultId="";programMonitor=true;
                ownerPlayheadMs=positionMs;
                if(sourceWasBound)bindEditorPreview(false);else livePlayer.seekProgram(positionMs);
                refreshPreviewTransformHandles();
                scheduleInspectorRefresh();
            }
            @Override public void onSelect(String clipId, long positionMs) {
                livePlayer.pause();
                selectedClip=activeProject.clip(clipId);ownerPlayheadMs=positionMs;
                previewResultUri="";previewResultId="";previewCompare=false;
                if(selectedClip!=null){sourceAssetId=selectedClip.assetId;if(!programMonitor)sourcePlayheadMs=selectedClip.inMs;}
                bindEditorPreview(false);
                updateEditorPanel();editorTimeline.setProject(activeProject,clipId);
                refreshPreviewTransformHandles();
            }
            @Override public void onMove(String clipId, String trackId, long startMs) {
                ProjectStore.Clip clip=activeProject.clip(clipId);ProjectStore.Track destination=activeProject.track(trackId);
                if(clip!=null&&destination!=null&&destination.isAudio()){
                    List<String> unavailable=NativeVideoEffects.unsupportedAudio(clip);
                    if(!unavailable.isEmpty()){Toast.makeText(MainActivity.this,"Remove visual effects before moving this clip to an audio-only track",Toast.LENGTH_LONG).show();return;}
                }
                ownerTimelineEdit("move", json("clipId", clipId, "trackId", trackId, "startMs", startMs), clipId);
            }
            @Override public void onTrim(String clipId, long inMs, long outMs, long startMs) {
                ownerTimelineEdit("trim", json("clipId", clipId, "inMs", inMs, "outMs", outMs, "startMs", startMs), clipId);
            }
            @Override public void onTrackAction(String trackId) { trackSettingsDialog(trackId); }
        });
        editor.addView(editorTimeline, new LinearLayout.LayoutParams(-1, 0, 3.2f));

        HorizontalScrollView edits = horizontalStrip();
        LinearLayout editRow = new LinearLayout(this);
        editRow.addView(editorButton("＋ Track", this::addTrackDialog));
        editRow.addView(editorButton("＋ Text", () -> titleEditorDialog(true)));
        editRow.addView(editorButton("✂ Split", this::splitClip));
        editRow.addView(editorButton("Trim", this::trimDialog));
        editRow.addView(editorButton("Roll", this::rollEditDialog));
        editRow.addView(editorButton("Slip", ()->advancedEditDialog("slip","end")));
        editRow.addView(editorButton("Slide", ()->advancedEditDialog("slide","end")));
        editRow.addView(editorButton("Extract audio", this::extractAudioDialog));
        editRow.addView(editorButton("Link / unlink", this::linkedClipsDialog));
        editRow.addView(editorButton("Duplicate", () -> { if (requireSelectedClip()) ownerTimelineEdit("duplicate", json("clipId", selectedClip.id), selectedClip.id); }));
        editRow.addView(editorButton("Delete", () -> { if (requireSelectedClip()) ownerTimelineEdit("delete", json("clipId", selectedClip.id, "ripple", false), ""); }));
        editRow.addView(editorButton("Ripple delete", () -> { if (requireSelectedClip()) ownerTimelineEdit("delete", json("clipId", selectedClip.id, "ripple", true), ""); }));
        editRow.addView(editorButton("−", () -> editorTimeline.zoom(.8f)));
        editRow.addView(editorButton("＋", () -> editorTimeline.zoom(1.25f)));
        editRow.addView(editorButton("Fit", () -> editorTimeline.fit()));
        editRow.addView(editorButton("＋ Marker",()->markerEditorDialog(null)));
        editRow.addView(editorButton("Markers",this::markerListDialog));
        editRow.addView(editorButton("In / Out",this::programRangeDialog));
        edits.addView(editRow);
        editor.addView(edits, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout tabs = new LinearLayout(this);
        for (String tab : new String[]{"Media", "Inspector", "Effects", "Audio", "Animation"}) {
            Button button = editorButton(tab, () -> { editorPanelTab = tab; showEditor(); });
            button.setTextColor(tab.equals(editorPanelTab) ? C_CYAN : C_MUTED);
            button.setBackground(rounded(tab.equals(editorPanelTab) ? C_CARD_2 : C_BG, 0, dp(5)));
            tabs.addView(button, new LinearLayout.LayoutParams(0, dp(34), 1));
        }
        editor.addView(tabs, new LinearLayout.LayoutParams(-1, dp(34)));
        ScrollView panelScroll = baseScroll();
        editorPanel = column();
        editorPanel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panelScroll.addView(editorPanel);
        editor.addView(panelScroll, new LinearLayout.LayoutParams(-1, 0, 2.6f));
        editorStatus = body(previewNotice.isEmpty()?"Ready · tap a clip or drag the playhead":"Preview notice · " + previewNotice);
        editorStatus.setTextSize(10); editorStatus.setSingleLine(true);
        editorStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        editorStatus.setOnClickListener(v -> {
            if(previewNotice.isEmpty()){editorWorkDialog();return;}
            new AlertDialog.Builder(this).setTitle("Last preview notice").setMessage(previewNotice).setPositiveButton("Dismiss",(d,w)->{previewNotice="";editorStatus.setText("Ready · tap a clip or drag the playhead");}).setNeutralButton("Background work",(d,w)->editorWorkDialog()).setNegativeButton("Close",null).show();
        });
        editor.addView(editorStatus, new LinearLayout.LayoutParams(-1, dp(19)));
        setScreen(editor, "editor");
        updateEditorPanel();
        bindEditorPreview(livePlayer.isPlaying());
        refreshRigOverlay();
        scheduleEditorRefresh(activeProject.id, activeProject.revision);
    }

    private Button editorButton(String label, Runnable action) {
        Button button = compactButton(label);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0); button.setTextSize(11);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(32));
        params.setMargins(dp(2), dp(2), dp(2), dp(2)); button.setLayoutParams(params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private HorizontalScrollView horizontalStrip() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(false);
        return scroll;
    }

    private long editorPosition() { return programMonitor ? ownerPlayheadMs : sourcePlayheadMs; }

    private float programmeAspect() {
        if ("16:9".equals(ownerExportAspect)) return 16f / 9f;
        if ("1:1".equals(ownerExportAspect)) return 1f;
        if ("4:5".equals(ownerExportAspect)) return 4f / 5f;
        return 9f / 16f;
    }

    private void scheduleInspectorRefresh() {
        if (inspectorRefresh != null) ui.removeCallbacks(inspectorRefresh);
        inspectorRefresh = () -> {
            if ("editor".equals(currentScreen) && !inspectorGesture && !"Media".equals(editorPanelTab)) updateEditorPanel();
        };
        ui.postDelayed(inspectorRefresh, 140);
    }

    private void bindEditorPreview(boolean play) {
        if (activeProject == null || livePlayer == null) return;
        keyboardShuttleRate=play?1:0;
        livePlayer.setProgramFormat(ownerExportAspect, ownerExportQuality);
        livePlayer.setPreviewProxyTier(previewProxyTier);
        if (!previewResultUri.isEmpty()) {
            livePlayer.setContextIds(previewResultId, "");
            livePlayer.play(Uri.parse(previewResultUri), sourcePlayheadMs, 1f, play);
        } else if (previewCompare && selectedClip != null) {
            ProjectStore.Asset asset = activeProject.asset(selectedClip.assetId);
            if (asset != null) livePlayer.previewAsset(asset, selectedClip.sourceTimeMs(ownerPlayheadMs), play);
        } else if (programMonitor) {
            livePlayer.previewProject(activeProject, ownerPlayheadMs, play);
        } else {
            ProjectStore.Asset asset = activeProject.asset(sourceAssetId);
            if (asset == null && selectedClip != null) asset = activeProject.asset(selectedClip.assetId);
            if (asset == null && !activeProject.assets.isEmpty()) asset = activeProject.assets.get(0);
            if (asset != null) { sourceAssetId = asset.id; livePlayer.previewAsset(asset, sourcePlayheadMs, play); }
        }
        refreshPreviewBadge();
        syncPreviewNotices();
    }

    private void syncPreviewNotices() {
        if(livePlayer==null)return;
        List<String> notices=livePlayer.currentWarnings();StringBuilder text=new StringBuilder();
        for(String notice:notices){if(text.length()>0)text.append('\n');text.append(notice);}
        if(activeProject!=null&&programMonitor&&!previewCompare){
            ProjectMarkers.Range range=ProjectMarkers.selection(activeProject);
            if(range.defined&&!range.reason.isEmpty()&&!"Range disabled".equals(range.reason)){if(text.length()>0)text.append('\n');text.append(range.reason).append(" · review Program In / Out");}
        }
        previewNotice=text.toString();
        if(editorStatus!=null)editorStatus.setText(previewNotice.isEmpty()?"Ready · tap a clip or drag the playhead":"Preview notice · " + previewNotice);
    }

    private void refreshPreviewBadge() {
        if (editorPreviewBadge != null && activeProject != null) {
            ProjectStore.Asset asset = activeProject.asset(sourceAssetId);
            boolean visualAtHead=false,audioAtHead=false;
            for(ProjectStore.Clip clip:activeProject.clips)if(ownerPlayheadMs>=clip.startMs&&ownerPlayheadMs<clip.endMs()){
                ProjectStore.Track track=activeProject.track(clip.trackId);ProjectStore.Asset source=activeProject.asset(clip.assetId);
                if(track!=null&&source!=null){
                    visualAtHead|=!track.isAudio()&&activeProject.isTrackEnabled(track)&&source.mime!=null&&!source.mime.startsWith("audio/");
                    if(activeProject.isTrackAudible(track)&&(source.hasAudio||source.mime!=null&&source.mime.startsWith("audio/"))&&!clip.effects.optBoolean("audioDetached",false)){
                        try{audioAtHead|=AudioGainEnvelope.evaluate(clip,ownerPlayheadMs-clip.startMs)>0;}catch(IllegalArgumentException ignored){}
                    }
                }
            }
            String segment=visualAtHead?"":audioAtHead?"audio only · ":activeProject.clips.isEmpty()?"empty timeline · ":"gap · ";
            String preferredTier=livePlayer.previewTier();
            ProjectMarkers.Range range=ProjectMarkers.selection(activeProject);
            String rangeBadge=range.enabled?" · range "+timecode(range.inMs)+"–"+timecode(range.outMs):"";
            editorPreviewBadge.setText(!previewResultUri.isEmpty() ? "Completed result · source monitor" : previewCompare ? "Before · source without clip effects" : programMonitor
                    ? "Program · " + segment + ("source".equals(preferredTier) ? "original source" : preferredTier + " proxy when ready") + rangeBadge + (livePlayer.isPlaying()&&keyboardShuttleRate!=1?" · "+keyboardShuttleRate+"×"+(keyboardShuttleRate<0?" silent":""):"") + " · r" + activeProject.revision
                    : "Source · " + (asset == null ? "Select media" : asset.name));
        }
    }

    private String previewLoopLabel() {
        if(!livePlayer.isLooping())return "Loop";
        return activeProject!=null&&programMonitor&&!previewCompare&&ProjectMarkers.selection(activeProject).enabled?"Loop range ●":"Loop ●";
    }

    private void setMonitorMode(boolean program) {
        timelinePreviewRunning = false;
        previewResultUri = ""; previewResultId = "";
        livePlayer.pause(); previewCompare = false; programMonitor = program;
        if (!program && selectedClip != null) { sourceAssetId = selectedClip.assetId; sourcePlayheadMs = selectedClip.inMs; }
        bindEditorPreview(false);
        showEditor();
    }

    private void seekEditor(long positionMs) {
        timelinePreviewRunning = false; livePlayer.pause();
        if (previewCompare) { previewCompare = false; bindEditorPreview(false); }
        if (programMonitor) {
            ownerPlayheadMs = Math.max(0, Math.min(positionMs, activeProject.outputDurationMs()));
            livePlayer.seekProgram(ownerPlayheadMs);
        } else {
            ProjectStore.Asset asset = activeProject.asset(sourceAssetId);
            sourcePlayheadMs = Math.max(0, asset != null && asset.durationMs > 0 ? Math.min(positionMs, asset.durationMs) : positionMs);
            livePlayer.seekTo(sourcePlayheadMs);
        }
        scheduleInspectorRefresh();
    }

    private void shuttleEditor(float rate) {
        timelinePreviewRunning=false;keyboardShuttleRate=rate;livePlayer.shuttle(rate);refreshPreviewBadge();
    }

    private void stepEditorFrame(long deltaMs) {
        livePlayer.pause();
        if(previewCompare){previewCompare=false;programMonitor=true;bindEditorPreview(false);}
        if(programMonitor&&previewResultUri.isEmpty()&&activeProject!=null){
            int rate=activeProject.animationFrameRate==0?30:activeProject.animationFrameRate;
            long frame=Math.max(0,(long)Math.floor(ownerPlayheadMs*rate/1000d));
            long next=Math.round(frame*1000d/rate);
            if(deltaMs>0){do{frame++;next=Math.round(frame*1000d/rate);}while(next<=ownerPlayheadMs);}
            else {while(frame>0&&next>=ownerPlayheadMs){frame--;next=Math.round(frame*1000d/rate);}}
            seekEditor(next);
        }else livePlayer.stepFrame(deltaMs);
        scheduleInspectorRefresh();refreshPreviewBadge();
    }

    private void toggleEditorPlayback() {
        timelinePreviewRunning=false;keyboardShuttleRate=livePlayer.isPlaying()?0:1;livePlayer.togglePlayback();
    }

    private void shuttleDialog() {
        String[] labels={"Reverse 8× · silent","Reverse 4× · silent","Reverse 2× · silent","Reverse 1× · silent","Pause · K","Forward 1× · L","Forward 2×","Forward 4×","Forward 8×"};
        float[] rates={-8,-4,-2,-1,0,1,2,4,8};
        new AlertDialog.Builder(this).setTitle("Preview shuttle · J / K / L").setItems(labels,(d,index)->shuttleEditor(rates[index])).setNegativeButton("Close",null).show();
    }

    private void refreshPreviewTransformHandles() {
        if(editorPreviewHost==null||livePlayer==null)return;
        if(previewTransformView!=null&&previewTransformView.isManipulating())return;
        boolean show=previewTransformHandles&&!"Animation".equals(editorPanelTab)&&programMonitor&&!previewCompare&&previewResultUri.isEmpty()&&selectedClip!=null&&ownerPlayheadMs>=selectedClip.startMs&&ownerPlayheadMs<selectedClip.endMs()&&!livePlayer.isPlaying();
        if(show&&previewTransformView!=null&&selectedClip.id.equals(previewHandleClipId)&&clipOutputPosition()==previewHandleTime)return;
        if(previewTransformView!=null){editorPreviewHost.removeView(previewTransformView);previewTransformView=null;}
        if(!show)return;
        ProjectStore.Track track=activeProject.track(selectedClip.trackId);
        if(track==null||track.locked||track.isAudio())return;
        final String clipId=selectedClip.id;final long keyTime=clipOutputPosition();
        previewHandleClipId=clipId;previewHandleTime=keyTime;
        previewTransformView=new PreviewTransformView(this,programmeAspect(),MotionTimeline.evaluate(selectedClip,keyTime),new PreviewTransformView.Listener(){
            @Override public void onPreview(float ratio,float x,float y,float rotation){livePlayer.pause();livePlayer.previewTransform(clipId,ratio,ratio,x,y,rotation);}
            @Override public void onCommit(float ratio,float x,float y,float rotation){livePlayer.previewTransform(clipId,1,1,0,0,0);ownerEdit("Transform clip",p->applyGestureTransform(p.clip(clipId),keyTime,ratio,x,y,rotation));}
            @Override public void onCancel(){livePlayer.previewTransform(clipId,1,1,0,0,0);}
        });
        editorPreviewHost.addView(previewTransformView,new FrameLayout.LayoutParams(-1,-1));
    }

    private void fullscreenPreview() {
        if (editorPreviewHost == null) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.BLACK);
        livePlayer.attach(frame);
        Button done = editorButton("Done ⛶", dialog::dismiss);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.RIGHT);
        params.setMargins(0, dp(12), dp(12), 0); frame.addView(done, params);
        dialog.setContentView(frame);
        dialog.setOnDismissListener(d -> { if (editorPreviewHost != null) livePlayer.attach(editorPreviewHost); });
        dialog.show();
    }

    private void updateEditorPanel() {
        if (editorPanel == null || activeProject == null) return;
        editorAudioMeters=null;
        animationDopeSheet=null;
        if (editorTimeline != null) editorTimeline.setInsertionTrack(insertTrackId);
        editorPanel.removeAllViews();
        if ("Media".equals(editorPanelTab)) buildMediaPanel();
        else if ("Effects".equals(editorPanelTab)) buildEffectsPanel();
        else if ("Audio".equals(editorPanelTab)) buildAudioPanel();
        else if ("Animation".equals(editorPanelTab)) buildAnimationPanel();
        else buildInspectorPanel();
    }

    private void buildAnimationPanel() {
        editorPanel.addView(editorButton("Draw / cel exposure",this::celDrawingDialog));
        editorPanel.addView(editorButton("Project output · "+(activeProject.animationFrameRate==0?30:activeProject.animationFrameRate)+" fps",this::projectFrameRateDialog));
        if(!requireSelectedClipSilently()){editorPanel.addView(body("Select a visual clip to edit its motion or create an image rig."));return;}
        if(isSelectedAudioTrack()){editorPanel.addView(body("Audio tracks use gain keys in the Audio panel. Select an image or video lane for visual animation."));return;}
        LinearLayout motionRow=new LinearLayout(this);
        motionRow.addView(editorButton("Motion path",this::motionPathDialog),new LinearLayout.LayoutParams(0,dp(36),1));
        motionRow.addView(editorButton("Transform keys",this::keyframeOverviewDialog),new LinearLayout.LayoutParams(0,dp(36),1));
        motionRow.addView(editorButton("Motion easing",()->defaultEasingDialog("visual")),new LinearLayout.LayoutParams(0,dp(36),1));editorPanel.addView(motionRow);
        if(selectedClip.effects.optJSONObject("animationSpec")!=null)editorPanel.addView(editorButton("Camera easing",()->defaultEasingDialog("camera")));
        ProjectStore.Asset asset=activeProject.asset(selectedClip.assetId);
        if(asset==null||asset.mime==null||!asset.mime.startsWith("image/")){editorPanel.addView(body("Articulated rigs use still-image artwork. Motion paths and transform keys work on this visual clip."));return;}
        if(selectedClip.effects.optBoolean("titleOnly",false)||selectedClip.effects.optJSONObject("proceduralScene")!=null){editorPanel.addView(body("This generated overlay draws after the source image. Use motion paths and transform keys here, or choose still-image artwork to build an articulated rig."));return;}
        try {
            if(selectedClip.effects.optJSONObject("rig2d")!=null){
                editorPanel.addView(editorButton("Weight brush · bind artwork",this::rigWeightBrushDialog));
                editorPanel.addView(editorButton(animationDopeVisible?"Dope sheet ● · close":"Dope sheet · bone / IK keys",()->{animationDopeVisible=!animationDopeVisible;updateEditorPanel();}));
                if(animationDopeVisible){
                    animationDopeSheet=new EditorAnimationDopeSheet(this,activeProject,selectedClip.id,selectedRigBoneId,animationDopeState,new EditorAnimationDopeSheet.Listener(){
                        @Override public boolean onCommit(String projectId,long revision,String operation,JSONObject settings){return ownerRigEdit(projectId,revision,operation,settings);}
                        @Override public void onSeek(long positionMs){seekAnimationKey(positionMs);}
                        @Override public long pauseAndGetOutputLocalMs(){livePlayer.pause();return clipOutputPosition();}
                        @Override public void onSelectBone(String boneId){selectedRigBoneId=boneId;if(editorRigView!=null)editorRigView.setSelectedBone(boneId);}
                    });
                    animationDopeSheet.setPlayhead(clipOutputPosition(),livePlayer.isPlaying());editorPanel.addView(animationDopeSheet);
                }
            }
            EditorRig2DInspector inspector=rigInspector();
            editorPanel.addView(inspector.build(rigOverlayVisible,rigBindMode,rigMeshVisible));
            String mappingNotice=rigOverlayMappingNotice(selectedClip);
            if(!mappingNotice.isEmpty())editorPanel.addView(body("Monitor joint handles unavailable: "+mappingNotice+". Numeric rig controls remain available."));
        }catch(Exception|OutOfMemoryError error){editorPanel.addView(body("Rig notice · "+(error.getMessage()==null?"Animation preview memory is unavailable":error.getMessage())));}
    }

    private EditorRig2DInspector rigInspector() throws Exception {
        return new EditorRig2DInspector(this,activeProject,selectedClip.id,clipOutputPosition(),selectedRigBoneId,new EditorRig2DInspector.Listener(){
            @Override public boolean onCommit(String projectId,long revision,String operation,JSONObject args){
                return ownerRigEdit(projectId,revision,operation,args);
            }
            @Override public void onSelectBone(String boneId){selectedRigBoneId=boneId;if(editorRigView!=null)editorRigView.setSelectedBone(boneId);updateEditorPanel();}
            @Override public void onOverlay(boolean visible,boolean bind,boolean mesh){rigOverlayVisible=visible;rigBindMode=bind;rigMeshVisible=mesh;refreshRigOverlay();}
            @Override public long currentOutputTimeMs(){livePlayer.pause();return clipOutputPosition();}
        });
    }

    private boolean ownerRigEdit(String projectId,long revision,String operation,JSONObject args) {
        livePlayer.pause();
        return ownerEdit("Rig · "+operation.replace('_',' '),projectId,revision,p->{
            if("move_keyframe".equals(operation)&&args.has("oldAtMs")){
                JSONObject key=args.getJSONObject("keyframe");
                AnimationRigEdits.apply(p,"move_keyframe",json("clipId",args.getString("clipId"),"boneId",key.getString("boneId"),"fromAtMs",args.getLong("oldAtMs"),"toAtMs",key.getLong("atMs")));
                AnimationRigEdits.apply(p,"set_keyframe",json("clipId",args.getString("clipId"),"keyframe",key));
            }else AnimationRigEdits.apply(p,operation,args);
        });
    }

    private void seekAnimationKey(long positionMs) {
        if(activeProject==null)return;livePlayer.pause();
        boolean rebind=!programMonitor||previewCompare||!previewResultUri.isEmpty();
        programMonitor=true;previewCompare=false;previewResultUri="";previewResultId="";
        ownerPlayheadMs=Math.max(0,Math.min(positionMs,activeProject.outputDurationMs()));
        if(rebind){showEditor();return;}
        livePlayer.seekProgram(ownerPlayheadMs);
        if(editorTimeline!=null)editorTimeline.setPlayhead(ownerPlayheadMs,false);
        if(animationDopeSheet!=null)animationDopeSheet.setPlayhead(clipOutputPosition(),false);
        refreshRigOverlay();refreshPreviewBadge();
    }

    private void rigWeightBrushDialog(){
        if(!requireSelectedClip()||isSelectedAudioTrack())return;livePlayer.pause();
        EditorRigWeightDialog.show(this,activeProject,selectedClip.id,selectedRigBoneId,new EditorRigWeightDialog.Listener(){
            @Override public ProjectStore.Project onCommit(String projectId,long revision,JSONObject stroke){return ownerRigEdit(projectId,revision,"paint_weights",stroke)?activeProject:null;}
            @Override public ProjectStore.Project onUndo(String projectId,long revision){
                String selected=selectedClip==null?"":selectedClip.id;
                try{if(activeProject==null||!projectId.equals(activeProject.id))throw new IllegalStateException("The active project changed; reopen weight painting");activeProject=store.undo(projectId,revision);selectedClip=activeProject.clip(selected);refreshCommittedOwnerEdit("Project undo",activeProject.revision);return activeProject;}
                catch(Exception error){ownerEditError(error);return null;}
            }
            @Override public void onSelectBone(String boneId){selectedRigBoneId=boneId;if(editorRigView!=null)editorRigView.setSelectedBone(boneId);}
        });
    }

    private String rigOverlayMappingNotice(ProjectStore.Clip clip) {
        JSONObject fx=clip.effects;
        ProjectStore.Asset asset=activeProject.asset(clip.assetId);
        if(asset==null||asset.width<=0||asset.height<=0)return "source image dimensions are not recorded";
        if(fx.optJSONObject("crop")!=null||fx.has("cropRect")||fx.has("reframe"))return "authored crop or reframe changes the source coordinate mapping";
        if(fx.has("proceduralScene")||fx.optBoolean("titleOnly",false))return "this generated layer does not use the image artwork mapping";
        JSONArray stack=fx.optJSONArray("stack");if(stack==null)stack=fx.optJSONArray("effectStack");
        if((fx.has("stack")||fx.has("effectStack"))&&(stack==null||stack.length()>0))return "stack transforms are not represented by these source handles";
        return "";
    }

    private void refreshRigOverlay() {
        if(editorPreviewHost==null||activeProject==null)return;
        if(editorRigView!=null&&editorRigView.isManipulating())return;
        ProjectStore.Track track=selectedClip==null?null:activeProject.track(selectedClip.trackId);
        JSONObject rig=selectedClip==null?null:selectedClip.effects.optJSONObject("rig2d");
        boolean show="Animation".equals(editorPanelTab)&&rigOverlayVisible&&programMonitor&&!previewCompare&&previewResultUri.isEmpty()
                &&selectedClip!=null&&ownerPlayheadMs>=selectedClip.startMs&&ownerPlayheadMs<selectedClip.endMs()
                &&track!=null&&!track.locked&&!track.isAudio()&&activeProject.isTrackEnabled(track)
                &&rig!=null&&rig.optBoolean("enabled",true)&&rigOverlayMappingNotice(selectedClip).isEmpty();
        if(!show){if(editorRigView!=null){editorPreviewHost.removeView(editorRigView);editorRigView=null;}return;}
        JSONArray bones=rig.optJSONArray("bones");boolean selectedExists=false;
        if(bones!=null)for(int index=0;index<bones.length();index++){JSONObject bone=bones.optJSONObject(index);selectedExists|=bone!=null&&selectedRigBoneId.equals(bone.optString("id"));}
        if(!selectedExists&&bones!=null&&bones.length()>0)selectedRigBoneId=bones.optJSONObject(0).optString("id");
        if(editorRigView==null||editorRigView.revision!=activeProject.revision||!editorRigView.clipId.equals(selectedClip.id)||!editorRigView.projectId.equals(activeProject.id)){
            if(editorRigView!=null)editorPreviewHost.removeView(editorRigView);
            final String projectId=activeProject.id,clipId=selectedClip.id;final long revision=activeProject.revision;
            try{editorRigView=new EditorRig2DView(this,activeProject,selectedClip,programmeAspect(),new EditorRig2DView.Listener(){
                @Override public void onBoneSelected(String boneId){selectedRigBoneId=boneId;updateEditorPanel();}
                @Override public void onVertexSelected(int vertex){try{rigInspector().vertexDialog(vertex);}catch(Exception error){ownerEditError(error);}}
                @Override public void onBindCommit(String boneId,boolean tail,float u,float v){
                    ownerRigEdit(projectId,revision,"set_bone",json("clipId",clipId,"bone",tail?json("id",boneId,"endX",u,"endY",v):json("id",boneId,"x",u,"y",v),"autoWeights",false,"preserveConnections",true));
                }
                @Override public void onIkCommit(String ikId,long authoredTimeMs,float u,float v){
                    ownerRigEdit(projectId,revision,"set_ik_keyframe",json("clipId",clipId,"ikId",ikId,"keyframe",json("atMs",authoredTimeMs,"targetX",u,"targetY",v,"ease","linear")));
                }
            });editorPreviewHost.addView(editorRigView,new FrameLayout.LayoutParams(-1,-1));}
            catch(Exception error){editorRigView=null;previewNotice="Rig overlay · "+error.getMessage();if(editorStatus!=null)editorStatus.setText(previewNotice);return;}
        }
        editorRigView.setSelectedBone(selectedRigBoneId);editorRigView.setModes(rigBindMode,rigMeshVisible);editorRigView.setTime(clipOutputPosition(),livePlayer.isPlaying());
    }

    private void motionPathDialog() {
        if(!requireSelectedClip()||isSelectedAudioTrack())return;livePlayer.pause();
        final String clipId=selectedClip.id;
        EditorMotionPathDialog.show(this,activeProject,clipId,clipOutputPosition(),(projectId,revision,path)->{
            boolean saved=ownerEdit(path==null?"Clear motion path":"Edit motion path",projectId,revision,p->{if(path==null)ProjectMotionPathEdits.clear(p,clipId);else ProjectMotionPathEdits.set(p,clipId,path);});
            if(!saved)throw new IllegalStateException("Motion path was not saved; review the project revision and reopen the editor");
        });
    }

    private void defaultEasingDialog(String scope) {
        if(!requireSelectedClip())return;livePlayer.pause();final String projectId=activeProject.id,clipId=selectedClip.id;final long revision=activeProject.revision;
        String key="audio".equals(scope)?"audioEasing":"ease",control="audio".equals(scope)?"audioBezier":"bezier";
        JSONObject source="camera".equals(scope)?selectedClip.effects.optJSONObject("animationSpec"):selectedClip.effects;if(source==null)source=new JSONObject();
        if("camera".equals(scope))key="easing";
        EditorEasingControls easing=new EditorEasingControls(this,source.optString(key,"linear"),source.optJSONArray(control));
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(12));box.addView(body("audio".equals(scope)?"Used by new volume keys and audio automation with no explicit per-key curve.":"camera".equals(scope)?"Used by authored camera motion. Explicit per-key curves stay as authored.":"Used by new transform keys and clip motion. Authored camera moves have their own Camera easing."));box.addView(easing);
        ScrollView scroll=baseScroll();scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(scope+" interpolation").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{JSONArray controls=easing.bezier();if(ownerEdit("Set "+scope+" easing",projectId,revision,p->ProjectMotionPathEdits.setEasing(p,clipId,scope,easing.selectedName(),controls)))dialog.dismiss();}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}));dialog.show();
    }

    private void projectFrameRateDialog() {
        if(activeProject==null)return;final String projectId=activeProject.id;final long revision=activeProject.revision;
        final boolean returnToExport="export".equals(currentScreen);
        String[] names={"12 fps","24 fps","30 fps","60 fps"};int[] rates={12,24,30,60};
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(12));box.addView(body("Changes the export frame clock. Existing cel exposure timing stays in milliseconds; this does not retime the artwork."));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Whole programme output cadence").setView(box).setNegativeButton("Cancel",null).create();
        for(int index=0;index<rates.length;index++){final int rate=rates[index];box.addView(editorButton(names[index],()->{if(ownerEdit("Change output frame rate",projectId,revision,p->AnimationCelEdits.apply(p,"animation_frame_rate",json("frameRate",rate)))){dialog.dismiss();if(returnToExport)showExport();}}));}
        dialog.show();
    }

    private void celDrawingDialog() {
        if(activeProject==null)return;if(ownerCelPending){Toast.makeText(this,"A drawn frame is being saved",Toast.LENGTH_SHORT).show();return;}livePlayer.pause();
        boolean editable=selectedClip!=null&&AnimationCelEdits.isCel(activeProject.asset(selectedClip.assetId));
        final String projectId=activeProject.id,openedClipId=selectedClip==null?"":selectedClip.id;final long revision=activeProject.revision;
        String[] actions=editable?new String[]{"Redraw selected cel","Draw next cel","Exposure sheet","Project frame rate"}:new String[]{"Draw new cel","Exposure sheet","Project frame rate"};
        new AlertDialog.Builder(this).setTitle("Drawing and cel exposures").setItems(actions,(d,index)->{
            if(activeProject==null||!projectId.equals(activeProject.id)||activeProject.revision!=revision){Toast.makeText(this,"The project changed; reopen drawing controls",Toast.LENGTH_LONG).show();return;}
            String action=actions[index];if(action.startsWith("Redraw"))openCelDrawing(openedClipId,null);
            else if(action.startsWith("Draw"))newCelPlacementDialog(editable?openedClipId:"");
            else if("Exposure sheet".equals(action))showCelExposureSheet();else projectFrameRateDialog();
        }).setNegativeButton("Close",null).show();
    }

    private void showCelExposureSheet() {
        if(activeProject==null)return;
        EditorCelExposureSheet.show(this,activeProject,new EditorCelExposureSheet.Listener(){
            @Override public boolean onCommit(String projectId,long revision,String operation,JSONObject settings){return ownerEdit(operation.replace('_',' '),projectId,revision,p->AnimationCelEdits.apply(p,operation,settings));}
            @Override public void onSeek(String clipId,long positionMs){ProjectStore.Clip clip=activeProject.clip(clipId);if(clip==null)return;selectedClip=clip;editorPanelTab="Animation";jumpToProgramMarker(positionMs);}
            @Override public void onRedraw(String clipId){openCelDrawing(clipId,null);}
            @Override public void onNew(String clipId){newCelPlacementDialog(clipId);}
        });
    }

    private void newCelPlacementDialog(String afterClipId) {
        if(activeProject==null)return;
        ProjectStore.Clip previous=activeProject.clip(afterClipId);JSONObject previousExposure=previous==null?null:previous.effects.optJSONObject("celExposure");
        int rate=activeProject.animationFrameRate==0?24:activeProject.animationFrameRate;long origin=previousExposure==null?0:previousExposure.optLong("originMs",0);
        long suggested=previousExposure==null?Math.max(0,Math.round((ownerPlayheadMs-origin)*rate/1000d)):previousExposure.optLong("startFrame")+previousExposure.optLong("frameCount");
        if(previousExposure!=null&&previousExposure.optInt("fpsNumerator",rate)!=rate){suggested=Math.max(0,(long)Math.ceil((previous.endMs()-origin)*rate/1000d));while(suggested<10000000&&AnimationCelEdits.frameBoundaryMs(suggested,rate,1)<previous.endMs()-origin)suggested++;}
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(12));
        ArrayList<String> trackIds=new ArrayList<>(),labels=new ArrayList<>();trackIds.add("");labels.add("Automatic visual track");
        for(ProjectStore.Track track:activeProject.tracks)if(!track.isAudio()&&!track.locked){trackIds.add(track.id);labels.add(track.name);}
        Spinner tracks=new Spinner(this);tracks.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        String target=previous!=null?previous.trackId:!insertTrackId.isEmpty()?insertTrackId:selectedClip==null?"":selectedClip.trackId;tracks.setSelection(Math.max(0,trackIds.indexOf(target)));box.addView(body("Visual lane"));box.addView(tracks);
        EditText frame=new EditText(this);frame.setSingleLine();frame.setInputType(InputType.TYPE_CLASS_NUMBER);frame.setText(Long.toString(suggested));box.addView(body("Starting frame · "+rate+" fps"));box.addView(frame);
        android.widget.CheckBox ripple=new android.widget.CheckBox(this);ripple.setText("Ripple later clips on this lane");ripple.setTextColor(C_TEXT);ripple.setChecked(true);box.addView(ripple);
        box.addView(body("A new immutable PNG cel will be placed on this lane. Its hold frame count is editable in the drawing window. Output cadence is "+rate+" fps."));
        final String projectId=activeProject.id;final long revision=activeProject.revision;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("New cel placement").setView(box).setPositiveButton("Draw",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{
            if(activeProject==null||!projectId.equals(activeProject.id)||revision!=activeProject.revision)throw new IllegalStateException("The project changed; reopen cel placement");
            long startFrame=Long.parseLong(frame.getText().toString());if(startFrame<0||startFrame>10000000)throw new IllegalArgumentException("Start frame must be 0 to 10000000");
            JSONObject options=json("startFrame",startFrame,"frameCount",1,"fpsNumerator",rate,"fpsDenominator",1,"originMs",origin,"ripple",ripple.isChecked());String track=trackIds.get(tracks.getSelectedItemPosition());if(!track.isEmpty())options.put("trackId",track);
            dialog.dismiss();openCelDrawing("",options);
        }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}));dialog.show();
    }

    private void openCelDrawing(String redrawClipId,JSONObject createOptions) {
        if(activeProject==null||ownerCelPending)return;livePlayer.pause();
        try {
            final String projectId=activeProject.id;final long revision=activeProject.revision;
            final boolean redraw=redrawClipId!=null&&!redrawClipId.isEmpty();final ProjectStore.Clip target=redraw?activeProject.clip(redrawClipId):null;
            if(redraw&&target==null)throw new IllegalArgumentException("Selected cel exposure no longer exists");
            if(redraw){ProjectStore.Track track=activeProject.track(target.trackId);if(track==null||track.locked)throw new IllegalStateException("Unlock the cel's track before redrawing");}
            JSONObject options=redraw?target.effects.optJSONObject("celExposure"):createOptions;if(options==null)throw new IllegalArgumentException("Choose cel placement first");
            final JSONObject admittedOptions=new JSONObject(options.toString());
            int rate=admittedOptions.optInt("fpsNumerator",activeProject.animationFrameRate==0?24:activeProject.animationFrameRate),frames=Math.toIntExact(admittedOptions.optLong("frameCount",1));
            String trackId=redraw?target.trackId:admittedOptions.optString("trackId","");
            long position=redraw?target.startMs:ProjectTimeline.safeAdd(admittedOptions.optLong("originMs",0),AnimationCelEdits.frameBoundaryMs(admittedOptions.optLong("startFrame",0),rate,1));
            JSONObject drawing;
            if(redraw)drawing=AnimationCelEdits.drawingForClip(activeProject,redrawClipId);
            else {float aspect=programmeAspect();int width=aspect>=1?1024:Math.max(16,Math.round(1024*aspect)),height=aspect>=1?Math.max(16,Math.round(1024/aspect)):1024;drawing=json("version",1,"width",width,"height",height,"background","#00000000","strokes",new JSONArray());}
            Uri before=null,after=null;long beforeTime=Long.MIN_VALUE,afterTime=Long.MAX_VALUE;
            for(ProjectStore.Clip cel:activeProject.clips){if(redraw&&cel.id.equals(redrawClipId)||!AnimationCelEdits.isCel(activeProject.asset(cel.assetId))||!trackId.isEmpty()&&!trackId.equals(cel.trackId))continue;
                if(cel.endMs()<=position&&cel.endMs()>beforeTime){beforeTime=cel.endMs();before=Uri.parse(activeProject.asset(cel.assetId).uri);}
                if(cel.startMs>=position&&cel.startMs<afterTime){afterTime=cel.startMs;after=Uri.parse(activeProject.asset(cel.assetId).uri);}}
            final String requestKey="owner-cel-"+UUID.randomUUID();final String stableTrack=trackId.isEmpty()?"auto":trackId;
            String draftKey=projectId+":"+(redraw?redrawClipId:stableTrack+":"+rate+":"+admittedOptions.optLong("originMs",0)+":"+admittedOptions.optLong("startFrame",0));
            EditorCelDrawingDialog.show(this,draftKey,redraw?"Redraw cel · r"+revision:"Draw cel · frame "+admittedOptions.optLong("startFrame",0)+" · r"+revision,drawing,before,after,frames,rate,1,(saved,hold,fpsNumerator,fpsDenominator,discardDraft)->{
                if(ownerCelPending)throw new IllegalStateException("A drawn frame is already being saved");
                if(fpsDenominator!=1||fpsNumerator!=rate)throw new IllegalArgumentException("This placement uses "+rate+" fps. Change project cadence and reopen placement to use another frame rate.");
                JSONObject document=new JSONObject(saved.toString());JSONObject exposure=redraw?json("frameCount",hold,"fpsNumerator",rate,"fpsDenominator",1,"ripple",true):new JSONObject(admittedOptions.toString()).put("frameCount",hold);
                ownerCelPending=true;if(editorStatus!=null)editorStatus.setText("Saving drawn cel…");
                try{ownerMediaWorker.execute(()->{
                    try{AnimationCelFactory factory=new AnimationCelFactory(getApplicationContext(),store);
                        AnimationCelFactory.Result result=redraw?factory.update(projectId,revision,redrawClipId,document,exposure,requestKey):factory.create(projectId,revision,document,exposure,requestKey);
                        discardDraft.run();ui.post(()->{ownerCelPending=false;if(isFinishing()||isDestroyed())return;if(activeProject!=null&&projectId.equals(activeProject.id)){activeProject=store.get(projectId);selectedClip=activeProject.clip(result.clipId);ownerPlayheadMs=selectedClip==null?position:selectedClip.startMs;programMonitor=true;previewCompare=false;previewResultUri="";previewResultId="";editorPanelTab="Animation";if(selectedClip!=null)sourceAssetId=selectedClip.assetId;syncProtocolState();requestServiceSync();showEditor();}else Toast.makeText(this,"Cel saved in the original project",Toast.LENGTH_SHORT).show();});
                    }catch(Exception | OutOfMemoryError error){ui.post(()->{ownerCelPending=false;if(isFinishing()||isDestroyed())return;Toast.makeText(this,"Cel not saved; drawing draft retained. "+error.getMessage(),Toast.LENGTH_LONG).show();if(editorStatus!=null)editorStatus.setText("Cel not saved · reopen retained draft");});}
                });}catch(Exception error){ownerCelPending=false;throw error;}
            });
        }catch(Exception error){ownerEditError(error);}
    }

    private void buildMediaPanel() {
        LinearLayout destination = new LinearLayout(this);
        ProjectStore.Track target = activeProject.track(insertTrackId);
        destination.addView(editorButton("Add to: " + (target == null ? "auto track" : target.name), this::chooseInsertTrackDialog));
        destination.addView(editorButton(insertAtPlayhead ? "At playhead" : "Append", () -> { insertAtPlayhead = !insertAtPlayhead; updateEditorPanel(); }));
        destination.addView(editorButton("＋ Text", () -> titleEditorDialog(true)));
        editorPanel.addView(destination);
        if (activeProject.assets.isEmpty()) {
            editorPanel.addView(body("Your imported and generated media appears here."));
            editorPanel.addView(editorButton("＋ Import media", this::pickMedia));
            return;
        }
        ArrayList<ProjectStore.Asset> visibleAssets = new ArrayList<>();
        for (ProjectStore.Asset asset : activeProject.assets) if (mediaBinSearch.isEmpty() || asset.name.toLowerCase(Locale.US).contains(mediaBinSearch.toLowerCase(Locale.US))) visibleAssets.add(asset);
        mediaBinOffset = Math.max(0, Math.min(mediaBinOffset, Math.max(0, visibleAssets.size() - 1)));
        LinearLayout mediaTools = new LinearLayout(this);
        mediaTools.addView(editorButton(mediaBinSearch.isEmpty() ? "Search media" : "Search: " + mediaBinSearch, this::searchMediaDialog));
        mediaTools.addView(editorButton("Program: " + ("source".equals(previewProxyTier) ? "original" : previewProxyTier), this::previewProxyDialog));
        ProjectStore.Asset proxySource = activeProject.asset(sourceAssetId);
        if(proxySource!=null && proxySource.mime!=null && proxySource.mime.startsWith("video/")) mediaTools.addView(editorButton("Build proxy", () -> ownerProxyDialog(proxySource)));
        if(proxySource!=null&&!proxySource.generated&&"source".equals(proxySource.role)){
            mediaTools.addView(editorButton("Source info",()->assetDetailsDialog(proxySource)));
            mediaTools.addView(editorButton("Relink source",()->pickRelinkSource(proxySource)));
        }
        if (visibleAssets.size() > 24) {
            mediaTools.addView(editorButton("‹", () -> { mediaBinOffset = Math.max(0, mediaBinOffset - 24); updateEditorPanel(); }));
            mediaTools.addView(editorButton("›", () -> { mediaBinOffset = Math.min(Math.max(0, visibleAssets.size()-24), mediaBinOffset + 24); updateEditorPanel(); }));
        }
        TextView count = body(visibleAssets.size() + " assets"); count.setTextSize(10); mediaTools.addView(count);
        HorizontalScrollView mediaToolStrip=horizontalStrip();mediaToolStrip.addView(mediaTools);editorPanel.addView(mediaToolStrip);
        HorizontalScrollView scroll = horizontalStrip();
        LinearLayout row = new LinearLayout(this);
        for (int assetIndex = mediaBinOffset; assetIndex < Math.min(visibleAssets.size(), mediaBinOffset + 24); assetIndex++) {
            ProjectStore.Asset asset = visibleAssets.get(assetIndex);
            LinearLayout tile = column(); tile.setPadding(dp(4), dp(4), dp(4), dp(4));
            tile.setBackground(rounded(asset.id.equals(sourceAssetId) ? C_CARD_2 : C_CARD, asset.id.equals(sourceAssetId) ? C_CYAN : 0, dp(6)));
            FrameLayout imageFrame = new FrameLayout(this);
            imageFrame.setBackgroundColor(Color.rgb(22, 30, 43));
            ImageView thumbnail = new ImageView(this); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setContentDescription(asset.name + " source preview");
            editorThumbnails.bind(asset, thumbnail);
            imageFrame.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
            TextView duration = accent("title_canvas".equals(asset.role) ? "T  Editable title" : asset.mime != null && asset.mime.startsWith("audio/") ? "♫  Audio" : time(asset.durationMs), C_TEXT);
            duration.setTextSize(9); duration.setPadding(dp(4), 0, dp(4), 0); duration.setBackgroundColor(Color.argb(150,0,0,0));
            imageFrame.addView(duration, new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT));
            tile.addView(imageFrame, new LinearLayout.LayoutParams(dp(108), dp(59)));
            TextView name = body(asset.name); name.setTextSize(10); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(name, new LinearLayout.LayoutParams(dp(108), -2));
            LinearLayout actions = new LinearLayout(this);
            actions.addView(editorButton("Source", () -> selectSourceAsset(asset)));
            actions.addView(editorButton("＋", () -> addAssetAtPlayhead(asset)));
            tile.addView(actions);
            tile.setOnClickListener(v -> selectSourceAsset(asset));
            tile.setOnLongClickListener(v -> { assetDetailsDialog(asset); return true; });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(116), -2); params.setMargins(0,0,dp(5),0);
            row.addView(tile, params);
        }
        scroll.addView(row); editorPanel.addView(scroll);
    }

    private void addAssetAtPlayhead(ProjectStore.Asset asset) {
        String type = asset.mime != null && asset.mime.startsWith("audio/") ? "audio" : "video";
        boolean titleCanvas = "title_canvas".equals(asset.role);
        ProjectStore.Track track = activeProject.track(insertTrackId);
        if (track == null && selectedClip != null && !titleCanvas) track = activeProject.track(selectedClip.trackId);
        boolean audioOnlyTarget=track!=null&&track.isAudio()&&asset.mime!=null&&asset.mime.startsWith("video/")&&asset.hasAudio&&!insertTrackId.isEmpty();
        if (track != null && (track.locked || (!audioOnlyTarget && track.isAudio() != "audio".equals(type)))) {
            if (!insertTrackId.isEmpty()) { Toast.makeText(this, "Choose an unlocked " + type + " track for this media", Toast.LENGTH_SHORT).show(); return; }
            track = null;
        }
        if (track == null || track.locked || (!audioOnlyTarget && "audio".equals(type) != track.isAudio())) {
            track = null;
            for (ProjectStore.Track candidate : activeProject.renderTracks()) if (!candidate.locked && ("audio".equals(type) == candidate.isAudio()) && (!titleCanvas || "text".equals(candidate.type) || "subtitle".equals(candidate.type))) { track = candidate; break; }
        }
        long atMs = ownerPlayheadMs;
        if (!insertAtPlayhead && track != null) for (ProjectStore.Clip clip : activeProject.clips) if (track.id.equals(clip.trackId)) atMs = Math.max(atMs, clip.endMs());
        JSONObject settings = json("assetId", asset.id, "startMs", atMs);
        if (track != null) try { settings.put("trackId", track.id); } catch (Exception ignored) { }
        final long insertStart=atMs;final String[] inserted={""};
        try {
            activeProject=store.edit(activeProject.id,activeProject.revision,"Insert media",p->{
                if(titleCanvas&&!settings.has("trackId")) {
                    ProjectStore.Track titles=null;for(ProjectStore.Track candidate:p.renderTracks())if(!candidate.locked&&("text".equals(candidate.type)||"subtitle".equals(candidate.type))){titles=candidate;break;}
                    if(titles==null){titles=new ProjectStore.Track();titles.id=UUID.randomUUID().toString();titles.name="Titles";titles.type="text";int order=0;for(ProjectStore.Track candidate:p.tracks)order=Math.max(order,candidate.order+1);titles.order=order;p.tracks.add(titles);}
                    settings.put("trackId",titles.id);
                }
                ProjectStore.Clip template=null;if(titleCanvas)for(ProjectStore.Clip candidate:p.clips)if(asset.id.equals(candidate.assetId)){template=candidate;break;}
                ProjectStore.Clip clip=ProjectTimeline.insertAsset(p,settings);inserted[0]=clip.id;
                if(titleCanvas){
                    JSONObject metadata=p.asset(asset.id).generationMetadata;
                    clip.title=template==null?metadata.optString("title","Title"):template.title;
                    JSONObject style=template==null?metadata.optJSONObject("titleStyle"):template.effects;
                    clip.effects=style==null?new JSONObject():new JSONObject(style.toString());clip.effects.put("titleOnly",true);
                    if(template!=null){clip.inMs=template.inMs;clip.outMs=template.outMs;clip.speed=template.speed;clip.programDurationMs=template.programDurationMs;}
                    else clip.outMs=metadata.optLong("defaultDurationMs",3000);
                }
            });
            selectedClip=activeProject.clip(inserted[0]);ownerPlayheadMs=insertStart;programMonitor=true;previewCompare=false;previewResultUri="";previewResultId="";
            sourceAssetId=asset.id;syncProtocolState();requestServiceSync();showEditor();
        }catch(Exception error){ownerEditError(error);}
    }

    private void buildInspectorPanel() {
        if (!requireSelectedClipSilently()) { editorPanel.addView(body("Select a timeline clip to change its properties.")); return; }
        ProjectStore.Asset asset = activeProject.asset(selectedClip.assetId);
        TextView label = title(asset == null ? "Clip inspector" : asset.name, 12); label.setSingleLine(true); editorPanel.addView(label);
        ProjectStore.Track track = activeProject.track(selectedClip.trackId);
        if(track!=null&&track.locked)editorPanel.addView(body("This track is locked. Unlock it from the track menu to edit."));
        LinearLayout timing = new LinearLayout(this);
        timing.addView(editorButton("Trim " + time(selectedClip.inMs) + "–" + time(selectedClip.outMs), this::trimDialog));
        timing.addView(editorButton("Move " + time(selectedClip.startMs), this::moveClipDialog));
        if(track==null||!track.isAudio())timing.addView(editorButton("Text", this::textDialog)); editorPanel.addView(timing);
        if(track!=null&&track.isAudio()){
            editorPanel.addView(body("Audio-only track · source timing, speed, gain and audio processing remain editable."));
            addPropertyRow("Speed ×","speed",selectedClip.speed,.1,16,false);addPropertyRow("Volume","volume",propertyValue("volume"),0,2,true);
            editorPanel.addView(editorButton("Gain keyframes",()->keyframePropertyDialog("volume")));editorPanel.addView(editorButton("Audio processing",()->{editorPanelTab="Audio";showEditor();}));return;
        }
        addPropertyRow("Position X", "translateX", propertyValue("translateX"), -2, 2, true);
        addPropertyRow("Position Y", "translateY", propertyValue("translateY"), -2, 2, true);
        addPropertyRow("Scale", "scale", propertyValue("scale"), .1, 4, true);
        addPropertyRow("Rotation °", "rotate", propertyValue("rotate"), -360, 360, true);
        addPropertyRow("Opacity", "opacity", propertyValue("opacity"), 0, 1, true);
        addPropertyRow("Speed ×", "speed", selectedClip.speed, .1, 16, false);
        addPropertyRow("Volume", "volume", propertyValue("volume"), 0, 2, true);
        LinearLayout reset = new LinearLayout(this);
        reset.addView(editorButton("Reset transform", () -> ownerEdit("Reset transform", p -> {
            JSONObject fx = p.clip(selectedClip.id).effects;
            for (String key : new String[]{"x","y","translateX","translateY","scale","scaleX","scaleY","zoom","rotation","rotate","opacity"}) {
                fx.remove(key);
                JSONObject transform = fx.optJSONObject("transform");
                if (transform != null) transform.remove(key);
            }
            JSONArray keys = fx.optJSONArray("keyframes");
            if (keys != null) for (int i = 0; i < keys.length(); i++) {
                JSONObject frame = keys.optJSONObject(i);
                if (frame != null) for (String key : new String[]{"x","y","translateX","translateY","scale","scaleX","scaleY","rotation","rotate","opacity"}) {
                    frame.remove(key);
                    JSONObject transform = frame.optJSONObject("transform");
                    if (transform != null) transform.remove(key);
                }
            }
        })));
        reset.addView(editorButton("Keyframes", this::keyframeOverviewDialog));
        editorPanel.addView(reset);
    }

    private void addPropertyRow(String label, String property, double value, double min, double max, boolean animate) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = body(label); name.setTextSize(11); row.addView(name, new LinearLayout.LayoutParams(dp(75), -2));
        SeekBar slider = new SeekBar(this); slider.setMax(1000);
        slider.setProgress((int)Math.round((Math.max(min,Math.min(max,value))-min)/(max-min)*1000));
        row.addView(slider, new LinearLayout.LayoutParams(0, dp(34), 1));
        Button number = editorButton(String.format(Locale.US, "%.2f", value), () -> numericPropertyDialog(label, property, value, min, max));
        number.setTextColor(C_TEXT); row.addView(number);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            double pending = value;
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) { pending = min + (max-min)*progress/1000d; number.setText(String.format(Locale.US,"%.2f",pending)); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { inspectorGesture = true; livePlayer.pause(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { inspectorGesture = false; commitProperty(property,pending); }
        });
        if (animate) {
            Button key = editorButton(hasPropertyKeyframes(property) ? "◆" : "◇", () -> addKeyframe(property, propertyValue(property)));
            key.setTextColor(C_CYAN);
            key.setOnLongClickListener(v -> { keyframePropertyDialog(property); return true; });
            row.addView(key);
        }
        editorPanel.addView(row);
    }

    private void numericPropertyDialog(String label, String property, double value, double min, double max) {
        EditText input = numberInput((float)value); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        new AlertDialog.Builder(this).setTitle(label).setMessage("Range " + min + " to " + max).setView(input)
                .setPositiveButton("Apply", (d,w) -> {
                    try { double next=Double.parseDouble(input.getText().toString()); if(!Double.isFinite(next)||next<min||next>max)throw new IllegalArgumentException();commitProperty(property,next); }
                    catch(Exception error){Toast.makeText(this,"Enter a value in the shown range",Toast.LENGTH_SHORT).show();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void commitProperty(String property, double value) {
        if (!requireSelectedClip()) return;
        final String clipId = selectedClip.id;
        if ("speed".equals(property)) {
            ownerTimelineEdit("properties", json("clipId", clipId, "speed", value, "ripple", true), clipId);
            return;
        }
        if (hasPropertyKeyframes(property)) {
            addKeyframe(property, value);
            return;
        }
        ownerEdit("Change " + property, p -> {
            ProjectStore.Clip clip = p.clip(clipId);
            if ("speed".equals(property)) clip.speed = (float)value;
            else if ("volume".equals(property)) clip.volume = (float)value;
            else setClipProperty(clip, property, value);
        });
    }

    private void buildEffectsPanel() {
        if (!requireSelectedClipSilently()) { editorPanel.addView(body("Select a clip to add an effect.")); return; }
        if(isSelectedAudioTrack()){
            editorPanel.addView(body("This track renders audio only. Use the Audio panel for gain, pan, EQ, compression and limiting."));
            editorPanel.addView(editorButton("Audio processing",()->{editorPanelTab="Audio";showEditor();}));
            editorPanel.addView(editorButton("Remove unavailable visual effects",this::removeAudioTrackVisuals));return;
        }
        TextView status = body("Effects on this clip: " + (selectedClip.effects.length() == 0 ? "none" : effectSummary(selectedClip.effects)));
        status.setTextSize(11); editorPanel.addView(status);
        addPresetStrip("Colour", "colorPreset", new String[]{"none","cinematic","teal_orange","warm_film","cool_night","noir","golden_hour","matte","high_contrast","soft_portrait"});
        addPresetStrip("Motion", "motionPreset", CreatorCatalog.MOTIONS.toArray(new String[0]));
        addPropertyRow("Brightness", "brightness", selectedClip.effects.optDouble("brightness",0), -1, 1, false);
        addPropertyRow("Contrast", "contrast", selectedClip.effects.optDouble("contrast",0), -1, 1, false);
        addPropertyRow("Saturation", "saturationAdjust", selectedClip.effects.optDouble("saturationAdjust",0), -100, 100, false);
        addPropertyRow("Blur", "blur", selectedClip.effects.optDouble("blur",0), 0, 18, false);
        if (selectedClip.effects.optBoolean("chromaKey")) addPropertyRow("Key tolerance", "chromaTolerance", selectedClip.effects.optDouble("chromaTolerance", .18), .001, 1, false);
        if (!"none".equals(selectedClip.effects.optString("mask", "none"))) addPropertyRow("Mask feather", "maskFeather", selectedClip.effects.optDouble("maskFeather", .08), .001, .5, false);
        editorPanel.addView(editorButton("Effect stack · add / reorder", this::effectStackDialog));
        HorizontalScrollView strip = horizontalStrip(); LinearLayout tools = new LinearLayout(this);
        for (String tool : new String[]{"Text","Fonts","Transitions","Green Screen","Mask","Reframe","Glow","Shake"}) tools.addView(editorButton(tool,()->applyTool(tool)));
        strip.addView(tools); editorPanel.addView(strip);
        editorPanel.addView(editorButton("Remove clip effects", () -> ownerEdit("Remove clip effects",p -> {
            ProjectStore.Clip clip=p.clip(selectedClip.id);JSONObject old=clip.effects,clean=new JSONObject();
            for(String key:new String[]{"audioDetached","audioExtractionDetached"})if(old.has(key))clean.put(key,old.get(key));
            clip.effects=clean;clip.transition="none";
        })));
    }

    private void addPresetStrip(String label, String property, String[] presets) {
        HorizontalScrollView scroll = horizontalStrip(); LinearLayout row = new LinearLayout(this);
        TextView name = body(label); name.setTextSize(11); name.setPadding(0,dp(8),dp(5),0); row.addView(name);
        for (String preset : presets) {
            Button button=editorButton(preset.replace('_',' '),()->ownerEdit("Set "+label,p -> p.clip(selectedClip.id).effects.put(property,preset)));
            boolean supported = "motionPreset".equals(property) ? MotionTimeline.supportsMotion(preset) : NativeVideoEffects.supportsPreset(preset);
            button.setEnabled(supported); button.setAlpha(supported ? 1f : .35f);
            if(preset.equals(selectedClip.effects.optString(property,"none")))button.setTextColor(C_CYAN);
            row.addView(button);
        }
        scroll.addView(row); editorPanel.addView(scroll);
    }

    private void buildAudioPanel() {
        editorPanel.addView(body("Decoded PCM peaks · separate clip channels"));
        editorAudioMeters=new EditorAudioMeterView(this);editorAudioMeters.setLevels(livePlayer.audioLevels());
        editorPanel.addView(editorAudioMeters,new LinearLayout.LayoutParams(-1,dp(72)));
        if (requireSelectedClipSilently()) {
            addPropertyRow("Volume", "volume", propertyValue("volume"), 0, 2, true);
            boolean audioDisabled=selectedClip.effects.optBoolean("audioDetached",false)||(selectedClip.volume==0&&!hasPropertyKeyframes("volume"));
            boolean extractedOriginal=selectedClip.effects.optBoolean("audioExtractionDetached",false);
            final String audioClipId=selectedClip.id;
            String audioAction=audioDisabled?(extractedOriginal?"Restore embedded audio":"Enable clip audio"):"Mute clip";
            editorPanel.addView(editorButton(audioAction,()->ownerEdit(audioAction,p->{
                ProjectStore.Clip clip=p.clip(audioClipId);clip.effects.put("audioDetached",!audioDisabled);
                if(audioDisabled)clip.effects.remove("audioExtractionDetached");
                if(audioDisabled&&clip.volume==0)clip.volume=1;
            })));
            if(extractedOriginal&&audioDisabled){
                for(ProjectStore.Clip peer:activeProject.clips){
                    ProjectStore.Track peerTrack=activeProject.track(peer.trackId);
                    if(!peer.id.equals(audioClipId)&&selectedClip.assetId.equals(peer.assetId)&&peerTrack!=null&&peerTrack.isAudio()){
                        editorPanel.addView(body("The separate audio clip stays in the project. Restoring embedded audio can mix both."));break;
                    }
                }
            }
            editorPanel.addView(editorButton("Gain fade keys", this::audioFadeDialog));
            editorPanel.addView(editorButton("Audio key easing",()->defaultEasingDialog("audio")));
            final String clipId = selectedClip.id;
            JSONObject audio = selectedClip.effects.optJSONObject("audio");
            boolean enabled = audio != null && audio.optBoolean("enabled", true);
            editorPanel.addView(editorButton(enabled ? "Audio processing ●" : "Enable audio processing", () -> ownerEdit("Toggle audio processing", p -> {
                JSONObject settings = p.clip(clipId).effects.optJSONObject("audio");
                if (settings == null) { settings = new JSONObject(); p.clip(clipId).effects.put("audio", settings); }
                settings.put("enabled", !enabled);
            })));
            HorizontalScrollView modules=horizontalStrip();LinearLayout moduleRow=new LinearLayout(this);
            for(String module:new String[]{"Gate","De-esser","Delay","Reverb"}){
                String key="Gate".equals(module)?"gateThresholdDb":"De-esser".equals(module)?"deEsserAmount":"Delay".equals(module)?"delayMix":"reverbMix";
                boolean moduleEnabled=enabled&&audio!=null&&("Gate".equals(module)?audio.optDouble(key,-90)>-90:audio.optDouble(key,0)>0);
                moduleRow.addView(editorButton(module+(moduleEnabled?" ●":" · off"),()->audioModuleDialog(module)));
            }
            modules.addView(moduleRow);editorPanel.addView(modules);
            addAudioDspRow("Pan L / R", "pan", 0, -1, 1);
            addAudioDspRow("Bass dB", "bassDb", 0, -18, 18);
            addAudioDspRow("Mid dB", "midDb", 0, -18, 18);
            addAudioDspRow("Treble dB", "trebleDb", 0, -18, 18);
            addAudioDspRow("Comp dB", "compressorThresholdDb", -18, -60, 0);
            addAudioDspRow("Comp ratio", "compressorRatio", 1, 1, 20);
            addAudioDspRow("Limit dB", "limiterCeilingDb", 0, -24, 0);
        }
        for (ProjectStore.Track track : activeProject.tracks) {
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label=body(track.name);label.setTextSize(11);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(editorButton(track.muted?"Muted":"Mute",()->ownerTimelineEdit("track",json("trackId",track.id,"muted",!track.muted),selectedClip==null?"":selectedClip.id)));
            row.addView(editorButton(track.solo?"Solo ●":"Solo",()->ownerTimelineEdit("track",json("trackId",track.id,"solo",!track.solo),selectedClip==null?"":selectedClip.id)));
            editorPanel.addView(row);
        }
        editorPanel.addView(editorButton("＋ Import music or voice",this::pickMedia));
    }

    private void addAudioDspRow(String label, String key, double fallback, double min, double max) {
        JSONObject audio = selectedClip.effects.optJSONObject("audio");
        double value = audio == null ? fallback : audio.optDouble(key, fallback);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = body(label); name.setTextSize(11); row.addView(name, new LinearLayout.LayoutParams(dp(75), -2));
        SeekBar slider = new SeekBar(this); slider.setMax(1000); slider.setProgress((int)Math.round((Math.max(min,Math.min(max,value))-min)/(max-min)*1000));
        row.addView(slider, new LinearLayout.LayoutParams(0,dp(34),1));
        Button number = editorButton(String.format(Locale.US,"%.2f",value), () -> {
            EditText input = numberInput((float)value);
            new AlertDialog.Builder(this).setTitle(label).setView(input).setPositiveButton("Apply",(d,w)->{
                try { double entered = Double.parseDouble(input.getText().toString()); if (!Double.isFinite(entered) || entered < min || entered > max) throw new IllegalArgumentException(); commitAudioDsp(key,entered); }
                catch (Exception error) { Toast.makeText(this,"Enter a value from " + min + " to " + max,Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("Cancel",null).show();
        }); row.addView(number);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            double pending = value;
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) { if(fromUser){pending=min+(max-min)*progress/1000d;number.setText(String.format(Locale.US,"%.2f",pending));} }
            @Override public void onStartTrackingTouch(SeekBar bar) { inspectorGesture=true;livePlayer.pause(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { inspectorGesture=false;commitAudioDsp(key,pending); }
        }); editorPanel.addView(row);
    }

    private void commitAudioDsp(String key, double value) {
        if (!requireSelectedClip()) return;
        final String clipId = selectedClip.id;
        ownerEdit("Change audio " + key,p->{ JSONObject settings=p.clip(clipId).effects.optJSONObject("audio"); if(settings==null){settings=new JSONObject();p.clip(clipId).effects.put("audio",settings);} settings.put("enabled",true);settings.put(key,value); });
    }

    private void audioModuleDialog(String module) {
        if(!requireSelectedClip())return;livePlayer.pause();final String clipId=selectedClip.id;
        JSONObject existing=selectedClip.effects.optJSONObject("audio");if(existing==null)existing=new JSONObject();
        final String[] keys,labels;final double[] minimum,maximum,defaults;final String description;
        switch(module){
            case "Gate":
                keys=new String[]{"gateThresholdDb","gateFloorDb","gateAttackMs","gateReleaseMs","gateHoldMs"};labels=new String[]{"Threshold · dB","Quiet level · dB","Attack · ms","Release · ms","Hold · ms"};
                minimum=new double[]{-90,-96,1,10,0};maximum=new double[]{0,0,100,1000,500};defaults=new double[]{-40,-80,5,100,40};
                description="Lowers sound below the threshold. Attack, hold and release control the change around quiet passages.";break;
            case "De-esser":
                keys=new String[]{"deEsserAmount","deEsserThresholdDb","deEsserFrequencyHz"};labels=new String[]{"Amount · 0 to 1","Threshold · dB","Sibilance frequency · Hz"};
                minimum=new double[]{0,-48,2000};maximum=new double[]{1,0,10000};defaults=new double[]{.3,-24,6000};
                description="Attenuates high-frequency sibilance when it crosses the threshold.";break;
            case "Delay":
                keys=new String[]{"delayMix","delayMs","delayFeedback"};labels=new String[]{"Wet mix · 0 to 1","Repeat delay · ms","Feedback · 0 to 0.85"};
                minimum=new double[]{0,1,0};maximum=new double[]{1,2000,.85};defaults=new double[]{.25,300,.3};
                description="Adds repeated echoes. Ping-pong alternates repeats between channels. The tail ends at this clip's boundary.";break;
            default:
                keys=new String[]{"reverbMix","reverbRoom","reverbDamping"};labels=new String[]{"Wet mix · 0 to 1","Room size · 0 to 1","High-frequency damping · 0 to 1"};
                minimum=new double[]{0,0,0};maximum=new double[]{1,1,1};defaults=new double[]{.2,.5,.5};
                description="Adds an algorithmic room tail. Size and damping shape the reflections; the tail ends at this clip's boundary.";
        }
        double inactive="Gate".equals(module)?-90:0;
        boolean active="Gate".equals(module)?existing.optDouble(keys[0],inactive)>inactive:existing.optDouble(keys[0],inactive)>0;
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));box.addView(body(description));
        android.widget.CheckBox enable=new android.widget.CheckBox(this);enable.setText("Enable "+module.toLowerCase(Locale.US));enable.setTextColor(C_TEXT);enable.setChecked(active);box.addView(enable);
        EditText[] inputs=new EditText[keys.length];
        for(int index=0;index<keys.length;index++){
            double current=existing.optDouble(keys[index],defaults[index]);if(index==0&&!active)current=defaults[index];
            box.addView(body(labels[index]+" · "+minimum[index]+" to "+maximum[index]));
            inputs[index]=numberInput((float)current);inputs[index].setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);box.addView(inputs[index]);
        }
        android.widget.CheckBox pingPong=new android.widget.CheckBox(this);pingPong.setText("Ping-pong repeats");pingPong.setTextColor(C_TEXT);pingPong.setChecked(existing.optBoolean("delayPingPong",false));
        if("Delay".equals(module))box.addView(pingPong);
        box.addView(body("Apply enables the clip's audio processing. Clip mute remains separate."));
        ScrollView scroll=baseScroll();scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(module+" · clip audio").setView(scroll).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                double[] values=new double[keys.length];for(int index=0;index<keys.length;index++){
                    values[index]=Double.parseDouble(inputs[index].getText().toString());
                    if(!Double.isFinite(values[index])||values[index]<minimum[index]||values[index]>maximum[index])throw new IllegalArgumentException(labels[index]+" must be between "+minimum[index]+" and "+maximum[index]);
                }
                if(!enable.isChecked())values[0]=inactive;
                ownerEdit("Change audio "+module,p->{
                    ProjectStore.Clip clip=p.clip(clipId);JSONObject audio=clip.effects.optJSONObject("audio");if(audio==null){audio=new JSONObject();clip.effects.put("audio",audio);}audio.put("enabled",true);
                    for(int index=0;index<keys.length;index++)audio.put(keys[index],values[index]);if("Delay".equals(module))audio.put("delayPingPong",pingPong.isChecked());
                });dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Enter values within the shown ranges":error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }

    private void audioFadeDialog() {
        if(!requireSelectedClip())return;
        final String clipId=selectedClip.id;final long duration=selectedClip.outputDurationMs();
        LinearLayout box=column();box.setPadding(dp(18),dp(5),dp(18),0);
        float suggestion=Math.min(.5f,duration/2000f);EditText fadeIn=numberInput(suggestion),fadeOut=numberInput(suggestion);
        box.addView(body("Fade in · seconds"));box.addView(fadeIn);box.addView(body("Fade out · seconds"));box.addView(fadeOut);
        box.addView(body("Creates editable volume keyframes in this clip window. Existing gain keys inside the fade regions are replaced."));
        new AlertDialog.Builder(this).setTitle("Create gain fades").setView(box).setPositiveButton("Apply",(d,w)->{
            try {
                double inSeconds=Double.parseDouble(fadeIn.getText().toString()),outSeconds=Double.parseDouble(fadeOut.getText().toString());
                if(!Double.isFinite(inSeconds)||!Double.isFinite(outSeconds)||inSeconds<0||outSeconds<0||(inSeconds+outSeconds)*1000>duration)throw new IllegalArgumentException();
                long in=Math.round(inSeconds*1000),out=Math.round(outSeconds*1000);
                if((double)in+out>duration||(in>0&&in<33)||(out>0&&out<33))throw new IllegalArgumentException();
                ownerEdit("Create gain fades",p->{
                    ProjectStore.Clip clip=p.clip(clipId);AudioGainEnvelope gain=new AudioGainEnvelope(clip);
                    double plateauIn=gain.automationAt(in*1000L),plateauOut=gain.automationAt((duration-out)*1000L);
                    migrateAudioKeyframes(clip);JSONArray keys=clip.effects.optJSONArray("keyframes");
                    if(keys!=null){canonicalizeKeyframeTimes(clip,keys);long offset=clip.effects.optLong("animationOffsetMs",0);
                        for(int i=keys.length()-1;i>=0;i--){JSONObject key=keys.optJSONObject(i);if(key==null)continue;long local=key.optLong("timeMs")-offset;if((in>0&&local>=0&&local<=in)||(out>0&&local>=duration-out&&local<=duration)){key.remove("volume");}}
                    }
                    if(in>0){writeKeyframe(clip,"volume",0,0,"linear");writeKeyframe(clip,"volume",plateauIn,in,"linear");}
                    if(out>0){writeKeyframe(clip,"volume",plateauOut,duration-out,"linear");writeKeyframe(clip,"volume",0,duration,"linear");}
                });
            }catch(Exception error){Toast.makeText(this,"Fade durations must fit inside the clip (minimum 0.033 seconds)",Toast.LENGTH_LONG).show();}
        }).setNegativeButton("Cancel",null).show();
    }

    private boolean requireSelectedClipSilently() { return activeProject != null && selectedClip != null && activeProject.clip(selectedClip.id) != null; }
    private boolean isSelectedAudioTrack() { ProjectStore.Track track=activeProject==null||selectedClip==null?null:activeProject.track(selectedClip.trackId);return track!=null&&track.isAudio(); }

    private JSONArray copyGainFrames(ProjectStore.Clip clip, JSONArray frames) throws Exception {
        JSONArray gains=new JSONArray();if(frames==null)return gains;canonicalizeKeyframeTimes(clip,frames);
        for(int i=0;i<frames.length();i++){JSONObject frame=frames.optJSONObject(i);if(frame==null||!frame.has("volume"))continue;JSONObject gain=new JSONObject();for(String key:new String[]{"timeMs","easing","bezier","volume"})if(frame.has(key))gain.put(key,frame.get(key));gains.put(gain);}return gains;
    }

    private void removeAudioTrackVisuals() {
        if(!requireSelectedClip())return;final String clipId=selectedClip.id;
        ownerEdit("Remove audio track visuals",p->{ProjectStore.Clip clip=p.clip(clipId);JSONObject old=clip.effects,clean=new JSONObject();
            if(old.optJSONObject("audio")!=null)clean.put("audio",new JSONObject(old.optJSONObject("audio").toString()));
            for(String key:new String[]{"audioDetached","audioExtractionDetached","ease","bezier","audioEasing","audioBezier"})if(old.has(key))clean.put(key,old.get(key));
            JSONArray gains=copyGainFrames(clip,old.optJSONArray("keyframes")),audioGains=copyGainFrames(clip,old.optJSONArray("audioKeyframes"));
            if(gains.length()>0)clean.put("keyframes",gains);if(audioGains.length()>0)clean.put("audioKeyframes",audioGains);
            for(String key:new String[]{"animationDurationMs","animationOffsetMs"})if(old.has(key))clean.put(key,old.get(key));clip.effects=clean;clip.title="";clip.transition="none";
        });
    }
    private boolean requireSelectedClip() {
        if(requireSelectedClipSilently())return true;
        Toast.makeText(this,"Select a timeline clip first",Toast.LENGTH_SHORT).show();return false;
    }

    private void ownerTimelineEdit(String operation, JSONObject settings, String selection) {
        if(activeProject==null)return;
        try {
            activeProject=store.timelineEdit(activeProject.id,activeProject.revision,operation,settings);
            selectedClip=activeProject.clip(selection);
            syncProtocolState();requestServiceSync();showEditor();
        } catch(Exception error){ownerEditError(error);}
    }

    private void ownerEdit(String label, ProjectStore.ProjectMutation mutation) {
        if(activeProject==null)return;
        ownerEdit(label,activeProject.id,activeProject.revision,mutation);
    }

    private boolean ownerEdit(String label,String projectId,long expectedRevision,ProjectStore.ProjectMutation mutation) {
        if(activeProject==null)return false;
        String selection=selectedClip==null?"":selectedClip.id;
        try {
            if(!projectId.equals(activeProject.id))throw new IllegalStateException("The active project changed; reopen this editor");
            activeProject=store.edit(projectId,expectedRevision,label,p->{
                java.util.HashMap<String,String> before=new java.util.HashMap<>();
                for(ProjectStore.Clip clip:p.clips)before.put(clip.id,clip.effects.toString()+"\n"+clip.title+"\n"+clip.transition);
                mutation.apply(p);
                for(ProjectStore.Clip clip:p.clips){String old=before.get(clip.id);if(old!=null&&!old.equals(clip.effects.toString()+"\n"+clip.title+"\n"+clip.transition)){
                    ProjectStore.Track track=p.track(clip.trackId);List<String> missing=track!=null&&track.isAudio()?NativeVideoEffects.unsupportedAudio(clip):NativeVideoEffects.unsupported(clip);if(!missing.isEmpty())throw new IllegalArgumentException("Remove unavailable clip effects before editing: "+String.join(", ",missing));
                }}
            });
        } catch(Exception error){ownerEditError(error);return false;}
        catch(OutOfMemoryError allocation){previewNotice="Edit could not be confirmed because memory is unavailable. Reopen the project before retrying.";if(editorStatus!=null)editorStatus.setText(previewNotice);Toast.makeText(this,previewNotice,Toast.LENGTH_LONG).show();return false;}
        selectedClip=activeProject.clip(selection);
        refreshCommittedOwnerEdit(label,activeProject.revision);
        return true;
    }

    private void refreshCommittedOwnerEdit(String label,long committedRevision){
        try{syncProtocolState();requestServiceSync();showEditor();}
        catch(Exception|OutOfMemoryError refreshError){previewNotice=label+" saved at revision "+committedRevision+". Reopen the editor to refresh its display.";if(editorStatus!=null)editorStatus.setText(previewNotice);Toast.makeText(this,previewNotice,Toast.LENGTH_LONG).show();}
    }

    private void ownerEditError(Exception error) {
        String message=error.getMessage();
        Toast.makeText(this,message==null?"Could not apply this edit":message,Toast.LENGTH_LONG).show();
        if(activeProject!=null){activeProject=store.get(activeProject.id);showEditor();}
    }

    private void ownerHistory(boolean redo) {
        if(activeProject==null)return;
        try {
            activeProject=redo?store.redo(activeProject.id,activeProject.revision):store.undo(activeProject.id,activeProject.revision);
            syncProtocolState();requestServiceSync();showEditor();
        } catch(Exception error){ownerEditError(error);}
    }

    private void addTrackDialog() {
        String[] names={"Video / image","Audio music","Audio dialogue","Audio SFX","Text / subtitle"};
        String[] types={"video","audio_music","audio_dialogue","audio_sfx","text"};
        new AlertDialog.Builder(this).setTitle("Add a track").setItems(names,(d,index)->{
            try {
                ArrayList<String> existing = new ArrayList<>();for(ProjectStore.Track track:activeProject.tracks)existing.add(track.id);
                String selection=selectedClip==null?"":selectedClip.id;
                activeProject=store.timelineEdit(activeProject.id,activeProject.revision,"add_track",json("type",types[index],"name",names[index]));
                for(ProjectStore.Track track:activeProject.tracks)if(!existing.contains(track.id))insertTrackId=track.id;
                selectedClip=activeProject.clip(selection);editorPanelTab="Media";syncProtocolState();requestServiceSync();showEditor();
            } catch(Exception error){ownerEditError(error);}
        }).setNegativeButton("Cancel",null).show();
    }

    private void chooseInsertTrackDialog() {
        ArrayList<String> ids=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();ids.add("");labels.add("Auto track · match media type");
        for(ProjectStore.Track track:activeProject.renderTracks())if(!track.locked){ids.add(track.id);labels.add(track.name+" · "+track.type);}
        new AlertDialog.Builder(this).setTitle("Media destination").setItems(labels.toArray(new String[0]),(d,index)->{insertTrackId=ids.get(index);updateEditorPanel();}).setNegativeButton("Close",null).show();
    }

    private void trackSettingsDialog(String trackId) {
        ProjectStore.Track track=activeProject.track(trackId);if(track==null)return;
        String[] choices={"Add media here","Rename track","Move earlier","Move later",track.locked?"Unlock track":"Lock track",track.muted?"Unmute track":"Mute track",track.solo?"Disable solo":"Solo track",track.visible?"Hide track":"Show track","Delete empty track"};
        new AlertDialog.Builder(this).setTitle(track.name).setItems(choices,(d,index)->{
            if(index==0){if(track.locked){Toast.makeText(this,"Unlock this track first",Toast.LENGTH_SHORT).show();return;}insertTrackId=trackId;editorPanelTab="Media";updateEditorPanel();return;}
            if(index==1){EditText name=new EditText(this);name.setText(track.name);name.setSingleLine();new AlertDialog.Builder(this).setTitle("Track name").setView(name).setPositiveButton("Save",(dialog,w)->ownerTimelineEdit("track",json("trackId",trackId,"name",name.getText().toString().trim()),selectedClip==null?"":selectedClip.id)).setNegativeButton("Cancel",null).show();return;}
            if(index==2||index==3){ownerTimelineEdit("track",json("trackId",trackId,"direction",index==2?"up":"down"),selectedClip==null?"":selectedClip.id);return;}
            if(index==8){ownerTimelineEdit("remove_track",json("trackId",trackId),selectedClip==null?"":selectedClip.id);if(activeProject.track(insertTrackId)==null)insertTrackId="";return;}
            String[] keys={"locked","muted","solo","visible"};boolean[] values={!track.locked,!track.muted,!track.solo,!track.visible};
            ownerTimelineEdit("track",json("trackId",trackId,keys[index-4],values[index-4]),selectedClip==null?"":selectedClip.id);
        }).setNegativeButton("Close",null).show();
    }

    private void moveClipDialog() {
        if(!requireSelectedClip())return;
        EditText start=numberInput(selectedClip.startMs/1000f);
        new AlertDialog.Builder(this).setTitle("Timeline start · seconds").setView(start).setPositiveButton("Move",(d,w)->{
            try{long value=Math.round(Double.parseDouble(start.getText().toString())*1000);ownerTimelineEdit("move",json("clipId",selectedClip.id,"trackId",selectedClip.trackId,"startMs",value),selectedClip.id);}
            catch(Exception error){Toast.makeText(this,"Enter a valid time",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("Cancel",null).show();
    }

    private void rollEditDialog() {
        if(!requireSelectedClip())return;
        new AlertDialog.Builder(this).setTitle("Roll a touching cut").setItems(new String[]{"Roll selected clip's start boundary","Roll selected clip's end boundary"},(d,index)->advancedEditDialog("roll",index==0?"start":"end")).setNegativeButton("Cancel",null).show();
    }

    private void advancedEditDialog(String operation,String edge) {
        if(!requireSelectedClip())return;livePlayer.pause();
        final ProjectStore.Project snapshot=ProjectStore.copy(activeProject);
        final String projectId=snapshot.id,clipId=selectedClip.id;final long revision=snapshot.revision,localPosition=clipOutputPosition();
        try{ProjectAdvancedEdits.describe(snapshot,operation,json("clipId",clipId,"edge",edge,"deltaMs",0L));}
        catch(Exception error){Toast.makeText(this,error.getMessage()==null?"This edit is unavailable at this boundary":error.getMessage(),Toast.LENGTH_LONG).show();return;}
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        String explanation="roll".equals(operation)?"Move the touching cut. One clip grows while the next shrinks; outer timing stays fixed.":"slip".equals(operation)?"Choose earlier or later footage while keeping this clip's timeline position and length. Linked sources follow at their own playback speeds.":"Move this clip between its touching neighbors. Its source range and length stay fixed; neighbor handles absorb the move.";
        box.addView(body(explanation));box.addView(body("Offset · seconds at program playback speed\nPositive = later · negative = earlier"));
        EditText offset=numberInput(.1f);offset.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);box.addView(offset);
        TextView feedback=body("");feedback.setTextSize(11);box.addView(feedback);
        ScrollView scroll=baseScroll();scroll.addView(box);
        String title=operation.substring(0,1).toUpperCase(Locale.US)+operation.substring(1)+(operation.equals("roll")?" · "+edge+" boundary":"");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->{
            Button apply=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable update=()->{
                try{
                    long delta=advancedOffsetMs(offset);JSONObject info=ProjectAdvancedEdits.describe(snapshot,operation,json("clipId",clipId,"edge",edge,"deltaMs",delta));
                    feedback.setText(advancedEditFeedback(info));feedback.setTextColor(C_TEXT);apply.setEnabled(delta!=0);
                }catch(Exception error){feedback.setText(error.getMessage()==null?"Enter a valid offset within available source footage":error.getMessage());feedback.setTextColor(Color.rgb(255,192,96));apply.setEnabled(false);}
            };
            offset.addTextChangedListener(new android.text.TextWatcher(){
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){update.run();}
                @Override public void afterTextChanged(android.text.Editable value){}
            });
            apply.setOnClickListener(button->{
                try{
                    long delta=advancedOffsetMs(offset);if(delta==0)return;
                    JSONObject settings=json("clipId",clipId,"edge",edge,"deltaMs",delta);
                    store.edit(projectId,revision,title,p->ProjectAdvancedEdits.apply(p,operation,settings));
                    dialog.dismiss();
                    if(activeProject!=null&&projectId.equals(activeProject.id)){
                        activeProject=store.get(projectId);if(activeProject==null)return;
                        selectedClip=activeProject.clip(clipId);programMonitor=true;previewCompare=false;previewResultUri="";previewResultId="";
                        if(selectedClip!=null){sourceAssetId=selectedClip.assetId;ownerPlayheadMs=ProjectTimeline.safeAdd(selectedClip.startMs,Math.min(localPosition,Math.max(0,selectedClip.outputDurationMs()-1)));}
                        syncProtocolState();requestServiceSync();showEditor();
                    }
                    Toast.makeText(this,title+" applied · Undo available",Toast.LENGTH_SHORT).show();
                }catch(Exception error){ownerEditError(error);dialog.dismiss();}
            });
            update.run();
        });dialog.show();
    }

    private long advancedOffsetMs(EditText input) {
        double seconds=Double.parseDouble(input.getText().toString().trim()),milliseconds=seconds*1000d;
        if(!Double.isFinite(milliseconds)||milliseconds<=Long.MIN_VALUE||milliseconds>=Long.MAX_VALUE)throw new IllegalArgumentException("Enter a finite offset in seconds");
        return Math.round(milliseconds);
    }

    private String advancedEditFeedback(JSONObject info) {
        StringBuilder text=new StringBuilder("Program duration stays "+editSeconds(info.optLong("durationMs")));
        if(info.has("boundaryAfterMs"))text.append("\nCut moves to ").append(editSeconds(info.optLong("boundaryAfterMs")));
        JSONArray changes=info.optJSONArray("changes");if(changes==null)return text.toString();
        for(int index=0;index<Math.min(6,changes.length());index++){
            JSONObject change=changes.optJSONObject(index);if(change==null)continue;
            text.append("\n\n").append(change.optString("trackName")).append(" · ").append(change.optString("name"));
            text.append("\nTimeline ").append(editSeconds(change.optLong("startMs"))).append("–").append(editSeconds(change.optLong("endMs")));
            text.append(" · source ").append(editSeconds(change.optLong("inMs"))).append("–").append(editSeconds(change.optLong("outMs")));
        }
        if(changes.length()>6)text.append("\n\n+").append(changes.length()-6).append(" linked or touching clips");
        return text.toString();
    }

    private String editSeconds(long milliseconds) { return String.format(Locale.US,"%.3fs",milliseconds/1000d); }

    private void markerListDialog() {
        if(activeProject==null)return;
        final String projectId=activeProject.id;final long revision=activeProject.revision;
        ArrayList<JSONObject> markers=new ArrayList<>();JSONArray list=ProjectMarkers.list(activeProject);
        for(int index=0;index<list.length();index++){JSONObject marker=list.optJSONObject(index);if(marker!=null)markers.add(marker);}
        markers.sort(java.util.Comparator.comparingLong(marker->marker.optLong("atMs")));
        if(markers.isEmpty()){new AlertDialog.Builder(this).setTitle("Program markers").setMessage("Add a named point or range at the playhead. Markers keep their absolute program times when clips move.").setPositiveButton("Add marker",(d,w)->markerEditorDialog(null)).setNegativeButton("Close",null).show();return;}
        String[] labels=new String[markers.size()];for(int index=0;index<labels.length;index++){JSONObject marker=markers.get(index);labels[index]=timecode(marker.optLong("atMs"))+" · "+marker.optString("name")+(marker.has("endMs")?" · range":"")+(marker.optBoolean("inProgram")?"":" · outside program");}
        new AlertDialog.Builder(this).setTitle("Program markers").setItems(labels,(d,index)->markerActionsDialog(markers.get(index),projectId,revision)).setPositiveButton("Add",(d,w)->markerEditorDialog(null)).setNegativeButton("Close",null).show();
    }

    private void markerActionsDialog(JSONObject marker,String projectId,long revision) {
        if(activeProject==null||!projectId.equals(activeProject.id)||revision!=activeProject.revision){Toast.makeText(this,"The project changed; reopen the marker list",Toast.LENGTH_LONG).show();return;}
        String markerId=marker.optString("id");ArrayList<String> choices=new ArrayList<>();
        if(marker.optBoolean("inProgram"))choices.add("Jump to marker");choices.add("Edit marker");choices.add("Remove marker");
        if(marker.optBoolean("inProgram")&&marker.has("endMs"))choices.add("Loop this marker range");
        new AlertDialog.Builder(this).setTitle(marker.optString("name")+" · "+editSeconds(marker.optLong("atMs"))).setItems(choices.toArray(new String[0]),(d,index)->{
            if(activeProject==null||!projectId.equals(activeProject.id)||revision!=activeProject.revision){Toast.makeText(this,"The project changed; reopen the marker list",Toast.LENGTH_LONG).show();return;}
            switch(choices.get(index)){
                case "Jump to marker":jumpToProgramMarker(marker.optLong("atMs"));break;
                case "Edit marker":markerEditorDialog(marker);break;
                case "Remove marker":ownerMetadataEdit("Remove marker",projectId,revision,p->ProjectMarkers.remove(p,markerId));break;
                case "Loop this marker range":
                    if(ownerMetadataEdit("Loop marker range",projectId,revision,p->ProjectMarkers.setRange(p,json("inMs",marker.optLong("atMs"),"outMs",marker.optLong("endMs"),"enabled",true)))){livePlayer.setLoop(true);jumpToProgramMarker(marker.optLong("atMs"));}break;
            }
        }).setNegativeButton("Close",null).show();
    }

    private void markerEditorDialog(JSONObject existing) {
        if(activeProject==null)return;livePlayer.pause();final String projectId=activeProject.id;final long revision=activeProject.revision;
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        EditText name=new EditText(this);name.setTextColor(C_TEXT);name.setSingleLine();name.setText(existing==null?"Marker "+(activeProject.markers.length()+1):existing.optString("name"));
        EditText at=numberInput(0);at.setText(String.format(Locale.US,"%.3f",(existing==null?Math.max(0,Math.min(ownerPlayheadMs,activeProject.outputDurationMs())):existing.optLong("atMs"))/1000d));
        EditText end=numberInput(0);end.setText(existing!=null&&existing.has("endMs")?String.format(Locale.US,"%.3f",existing.optLong("endMs")/1000d):"");end.setHint("Blank for a point marker");
        EditText note=new EditText(this);note.setTextColor(C_TEXT);note.setMinLines(2);note.setText(existing==null?"":existing.optString("note"));
        String[] colors={"#F6B654","#22DCFF","#FF5E78","#9280FA","#7FF4D2","#FFFFFF"};final String[] color={existing==null?ProjectMarkers.DEFAULT_COLOR:existing.optString("color",ProjectMarkers.DEFAULT_COLOR)};
        Button colorButton=editorButton("Color "+color[0],()->{});
        colorButton.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Marker color").setItems(colors,(d,index)->{color[0]=colors[index];colorButton.setText("Color "+color[0]);}).setNegativeButton("Cancel",null).show());
        box.addView(body("Name · up to 120 characters"));box.addView(name);box.addView(body("Program position · seconds"));box.addView(at);box.addView(body("Optional range end · seconds"));box.addView(end);box.addView(colorButton);box.addView(body("Note · up to 2048 characters"));box.addView(note);
        if(existing!=null&&!existing.optBoolean("inProgram",true))box.addView(body("This marker is outside the current program. You can rename it, remove it or move it to a valid time."));
        ScrollView scroll=baseScroll();scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Add program marker":"Edit program marker").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                if(activeProject==null||!projectId.equals(activeProject.id))throw new IllegalStateException("The active project changed; reopen this marker editor");
                if(revision!=activeProject.revision)throw new IllegalStateException("The project revision changed; reopen this marker editor");
                long position=advancedOffsetMs(at);if(position<0)throw new IllegalArgumentException("Program position cannot be negative");
                JSONObject settings=json("name",name.getText().toString().trim(),"color",color[0],"note",note.getText().toString());
                if(existing==null||position!=existing.optLong("atMs"))settings.put("atMs",position);
                String endText=end.getText().toString().trim();
                if(endText.isEmpty()){if(existing!=null&&existing.has("endMs"))settings.put("endMs",JSONObject.NULL);}
                else {long finish=advancedOffsetMs(end);if(existing==null||!existing.has("endMs")||finish!=existing.optLong("endMs"))settings.put("endMs",finish);}
                if(existing!=null)settings.put("markerId",existing.optString("id"));
                ProjectStore.Project candidate=ProjectStore.copy(activeProject);if(existing==null)ProjectMarkers.add(candidate,settings);else ProjectMarkers.update(candidate,settings);
                if(ownerMetadataEdit(existing==null?"Add marker":"Edit marker",projectId,revision,p->{if(existing==null)ProjectMarkers.add(p,settings);else ProjectMarkers.update(p,settings);}))dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Enter a valid marker name and program range":error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }

    private void programRangeDialog() {
        if(activeProject==null)return;livePlayer.pause();final String projectId=activeProject.id;final long revision=activeProject.revision;
        ProjectMarkers.Range range=ProjectMarkers.selection(activeProject);
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        box.addView(body("Save program In/Out for playback and Loop. You can still scrub the full timeline."));
        if(range.defined&&!range.reason.isEmpty()&&!"Range disabled".equals(range.reason))box.addView(body(range.reason+". Review the saved bounds and enable the range explicitly."));
        EditText in=numberInput(0),out=numberInput(0);in.setText(String.format(Locale.US,"%.3f",(range.defined?range.inMs:0)/1000d));out.setText(String.format(Locale.US,"%.3f",(range.defined?range.outMs:activeProject.outputDurationMs())/1000d));
        box.addView(body("Program In · seconds"));box.addView(in);box.addView(editorButton("In at playhead",()->in.setText(String.format(Locale.US,"%.3f",ownerPlayheadMs/1000d))));
        box.addView(body("Program Out · seconds"));box.addView(out);box.addView(editorButton("Out at playhead",()->out.setText(String.format(Locale.US,"%.3f",ownerPlayheadMs/1000d))));
        android.widget.CheckBox enabled=new android.widget.CheckBox(this);enabled.setText("Use this playback range");enabled.setTextColor(C_TEXT);enabled.setChecked(!range.defined||range.enabled);box.addView(enabled);
        ScrollView scroll=baseScroll();scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Program In / Out").setView(scroll).setPositiveButton("Save",null).setNeutralButton("Clear range",(d,w)->ownerMetadataEdit("Clear program range",projectId,revision,ProjectMarkers::clearRange)).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                if(activeProject==null||!projectId.equals(activeProject.id))throw new IllegalStateException("The active project changed; reopen the program range editor");
                if(revision!=activeProject.revision)throw new IllegalStateException("The project revision changed; reopen the program range editor");
                JSONObject settings=json("inMs",advancedOffsetMs(in),"outMs",advancedOffsetMs(out),"enabled",enabled.isChecked());
                ProjectMarkers.setRange(ProjectStore.copy(activeProject),settings);
                if(ownerMetadataEdit("Set program range",projectId,revision,p->ProjectMarkers.setRange(p,settings)))dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Choose 0 ≤ In < Out within the program":error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }

    private boolean ownerMetadataEdit(String label,ProjectStore.ProjectMutation mutation) {
        if(activeProject==null)return false;return ownerMetadataEdit(label,activeProject.id,activeProject.revision,mutation);
    }

    private boolean ownerMetadataEdit(String label,String projectId,long expectedRevision,ProjectStore.ProjectMutation mutation) {
        if(activeProject==null)return false;String selectedId=selectedClip==null?"":selectedClip.id;
        try{if(!projectId.equals(activeProject.id))throw new IllegalStateException("The active project changed; reopen this editor");activeProject=store.edit(projectId,expectedRevision,label,mutation);selectedClip=activeProject.clip(selectedId);syncProtocolState();requestServiceSync();showEditor();return true;}
        catch(Exception error){ownerEditError(error);return false;}
    }

    private void jumpToProgramMarker(long positionMs) {
        livePlayer.pause();programMonitor=true;previewCompare=false;previewResultUri="";previewResultId="";
        ownerPlayheadMs=Math.max(0,Math.min(positionMs,activeProject.outputDurationMs()));showEditor();
    }

    private void extractAudioDialog() {
        if(!requireSelectedClip())return;
        ProjectStore.Asset asset=activeProject.asset(selectedClip.assetId);
        if(isSelectedAudioTrack()||asset==null||asset.mime==null||!asset.mime.startsWith("video/")||!asset.hasAudio){Toast.makeText(this,"Select a video clip with an audio stream",Toast.LENGTH_LONG).show();return;}
        final String clipId=selectedClip.id;ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
        ids.add("");labels.add("New linked audio track");
        for(ProjectStore.Track track:activeProject.renderTracks())if(track.isAudio()&&!track.locked){ids.add(track.id);labels.add(track.name);}
        new AlertDialog.Builder(this).setTitle("Extract linked audio · destination").setItems(labels.toArray(new String[0]),(d,index)->{
            JSONObject settings=json("clipId",clipId);if(!ids.get(index).isEmpty())try{settings.put("trackId",ids.get(index));}catch(Exception ignored){}
            ownerTimelineEdit("extract_audio",settings,clipId);
        }).setNegativeButton("Cancel",null).show();
    }

    private void linkedClipsDialog() {
        if(!requireSelectedClip())return;final String clipId=selectedClip.id;
        ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
        for(ProjectStore.Clip clip:activeProject.clips)if(!clip.id.equals(clipId)&&clip.startMs==selectedClip.startMs&&clip.endMs()==selectedClip.endMs()){
            ProjectStore.Track track=activeProject.track(clip.trackId);if(track==null||track.locked)continue;
            if(!selectedClip.linkGroupId.isEmpty()&&selectedClip.linkGroupId.equals(clip.linkGroupId))continue;
            ProjectStore.Asset asset=activeProject.asset(clip.assetId);ids.add(clip.id);labels.add(track.name+" · "+(clip.title.isEmpty()?asset==null?"Clip":asset.name:clip.title));
        }
        int peers=0;if(!selectedClip.linkGroupId.isEmpty())for(ProjectStore.Clip clip:activeProject.clips)if(selectedClip.linkGroupId.equals(clip.linkGroupId))peers++;
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Linked clips"+(peers>0?" · "+peers+" peers":""));
        if(ids.isEmpty())dialog.setMessage("No other unlocked clip has the same program start and end. Align a counterpart first. Linked peers move, trim, split and delete together. Unlinking leaves embedded audio disabled; use Audio to enable it explicitly.");
        if(!ids.isEmpty())dialog.setItems(labels.toArray(new String[0]),(d,index)->ownerTimelineEdit("link",json("clipIds",new JSONArray().put(clipId).put(ids.get(index))),clipId));
        if(peers>0)dialog.setNeutralButton("Unlink group",(d,w)->ownerTimelineEdit("unlink",json("clipId",clipId),clipId));
        dialog.setNegativeButton("Close",null).show();
    }

    private void renameProjectDialog() {
        EditText name=new EditText(this);name.setText(activeProject.name);name.setSingleLine();
        new AlertDialog.Builder(this).setTitle("Project name").setView(name).setPositiveButton("Save",(d,w)->ownerEdit("Rename project",p->p.name=name.getText().toString().trim())).setNegativeButton("Cancel",null).show();
    }

    private void editorWorkDialog() {
        new AlertDialog.Builder(this).setTitle("Background work").setView(autonomousEditorCard()).setNegativeButton("Close",null).setNeutralButton("Activity",(d,w)->showActivity()).show();
    }

    private JSONObject json(Object... pairs) {
        JSONObject object=new JSONObject();
        try { for(int i=0;i+1<pairs.length;i+=2)object.put(String.valueOf(pairs[i]),pairs[i+1]); }
        catch(Exception ignored) { }
        return object;
    }

    private String timecode(long ms) {
        long value=Math.max(0,ms);
        return String.format(Locale.US,"%02d:%02d:%02d:%02d",value/3600000,(value/60000)%60,(value/1000)%60,(value%1000)*30/1000);
    }

    private JSONArray effectStack(ProjectStore.Clip clip) {
        JSONArray stack=clip.effects.optJSONArray("stack");return stack==null?clip.effects.optJSONArray("effectStack"):stack;
    }

    private void effectStackDialog() {
        if(!requireSelectedClip())return;
        JSONArray stack=effectStack(selectedClip);ArrayList<String> labels=new ArrayList<>();
        if(stack!=null)for(int i=0;i<stack.length();i++){
            JSONObject entry=stack.optJSONObject(i);if(entry==null){labels.add("Invalid stage");continue;}
            JSONObject settings=entry.optJSONObject("settings");if(settings==null)settings=entry;
            labels.add((i+1)+" · "+entry.optString("type","effect")+" "+settings.optString("preset","").replace('_',' ')+(entry.optBoolean("enabled",true)?"":" · disabled"));
        }
        new AlertDialog.Builder(this).setTitle("Effects · processed in this order").setItems(labels.toArray(new String[0]),(d,index)->editEffectStageDialog(index)).setPositiveButton("Add effect",(d,w)->addEffectStageDialog()).setNegativeButton("Close",null).show();
    }

    private void addEffectStageDialog() {
        if(!requireSelectedClip())return;
        final String clipId=selectedClip.id;
        ArrayList<String> supported=new ArrayList<>();for(String preset:CreatorCatalog.EFFECTS)if(!"none".equals(preset)&&NativeVideoEffects.supportsPreset(preset))supported.add(preset);
        new AlertDialog.Builder(this).setTitle("Add native effect").setItems(supported.toArray(new String[0]),(d,index)->ownerEdit("Add effect stage",p->{
            ProjectStore.Clip clip=p.clip(clipId);JSONArray stack=effectStack(clip);if(stack==null)stack=new JSONArray();
            if(stack.length()>=16)throw new IllegalArgumentException("Use up to 16 effect stages per clip");
            stack.put(json("id",UUID.randomUUID().toString(),"type","preset","enabled",true,"settings",json("preset",supported.get(index))));
            clip.effects.put(clip.effects.has("stack")?"stack":"effectStack",stack);
        })).setNegativeButton("Cancel",null).show();
    }

    private void editEffectStageDialog(int index) {
        if(!requireSelectedClip())return;
        final String clipId=selectedClip.id;JSONArray stack=effectStack(selectedClip);if(stack==null||index>=stack.length())return;
        JSONObject entry=stack.optJSONObject(index);boolean enabled=entry!=null&&entry.optBoolean("enabled",true);
        new AlertDialog.Builder(this).setTitle("Effect stage "+(index+1)).setItems(new String[]{enabled?"Disable":"Enable","Move earlier","Move later","Remove"},(d,action)->ownerEdit("Edit effect stack",p->{
            ProjectStore.Clip clip=p.clip(clipId);JSONArray current=effectStack(clip);if(current==null||index>=current.length())throw new IllegalArgumentException("Effect stage changed");
            if(action==0){current.getJSONObject(index).put("enabled",!enabled);return;}
            if(action==3){current.remove(index);return;}
            int target=action==1?index-1:index+1;if(target<0||target>=current.length())return;
            Object other=current.get(target);current.put(target,current.get(index));current.put(index,other);
        })).setNegativeButton("Close",null).show();
    }

    private void selectSourceAsset(ProjectStore.Asset asset) {
        programMonitor=false;sourceAssetId=asset.id;sourcePlayheadMs=0;previewCompare=false;previewResultUri="";previewResultId="";
        livePlayer.pause();showEditor();
    }

    private void showCompletedResult(String uri, String id) {
        livePlayer.pause();programMonitor=false;previewCompare=false;previewResultUri=uri;previewResultId=id;sourcePlayheadMs=0;
        showEditor();livePlayer.togglePlayback();
    }

    private void searchMediaDialog() {
        EditText input=new EditText(this);input.setSingleLine();input.setHint("Media name");input.setText(mediaBinSearch);
        new AlertDialog.Builder(this).setTitle("Search Media Bin").setView(input).setPositiveButton("Search",(d,w)->{mediaBinSearch=input.getText().toString().trim();mediaBinOffset=0;updateEditorPanel();}).setNeutralButton("Show all",(d,w)->{mediaBinSearch="";mediaBinOffset=0;updateEditorPanel();}).setNegativeButton("Cancel",null).show();
    }

    private void assetDetailsDialog(ProjectStore.Asset asset) {
        String size=asset.sizeBytes<0?"Not reported":String.format(Locale.US,"%.1f MB",asset.sizeBytes/1048576d);
        String dimensions=asset.width>0&&asset.height>0?asset.width+" × "+asset.height:"Not reported";
        LinearLayout details=column();details.setPadding(dp(18),dp(10),dp(18),dp(10));
        details.addView(body((asset.generated?"Generated media":"Imported media")+"\n"+asset.mime+"\nDuration: "+time(asset.durationMs)+"\nDimensions: "+dimensions+"\nSize: "+size+"\nAudio: "+(asset.hasAudio?"present":"not reported")+"\n"+(asset.seekable?"Seekable source":"Provider-controlled source")));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(asset.name).setView(details)
                .setPositiveButton("Source preview",(d,w)->selectSourceAsset(asset)).setNeutralButton("Add to timeline",(d,w)->addAssetAtPlayhead(asset)).setNegativeButton("Close",null).show();
        if(!asset.generated&&"source".equals(asset.role)){
            details.addView(editorButton("Relink original source…",()->{dialog.dismiss();pickRelinkSource(asset);}));
            details.addView(editorButton("Archive / restore original bytes…",()->{dialog.dismiss();sourceArchiveDialog(asset);}));
        }
    }

    private void sourceArchiveDialog(ProjectStore.Asset asset) {
        if(activeProject==null||asset==null||asset.generated||!"source".equals(asset.role))return;
        final String projectId=activeProject.id;
        EditorSourceArchiveDialog.show(this,store,storageProfiles,ownerMediaWorker,activeProject,asset,()->{
            if(isFinishing()||isDestroyed()||activeProject==null||!projectId.equals(activeProject.id))return;
            String selection=selectedClip==null?"":selectedClip.id;ProjectStore.Project latest=store.get(projectId);
            if(latest==null)return;activeProject=latest;selectedClip=latest.clip(selection);syncProtocolState();requestServiceSync();
            if("editor".equals(currentScreen))showEditor();
        },this::showStorageHub);
    }

    private void pickRelinkSource(ProjectStore.Asset asset) {
        if(activeProject==null||asset==null||asset.generated||!"source".equals(asset.role)){Toast.makeText(this,"Relinking is available for imported original sources",Toast.LENGTH_LONG).show();return;}
        pendingRelinkProjectId=activeProject.id;pendingRelinkAssetId=asset.id;pendingRelinkRevision=activeProject.revision;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        String kind=asset.mime==null?"":asset.mime.split("/",2)[0];
        intent.setType(java.util.Arrays.asList("video","audio","image").contains(kind)?kind+"/*":"*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false);
        startActivityForResult(intent,PICK_RELINK_SOURCE);
    }

    private void relinkOwnerSource(String projectId,String assetId,long expectedRevision,Uri replacementUri) {
        final String selectedId=selectedClip==null?"":selectedClip.id;
        if(editorStatus!=null)editorStatus.setText("Inspecting replacement source…");
        Toast.makeText(this,"Inspecting replacement source",Toast.LENGTH_SHORT).show();
        ownerMediaWorker.execute(()->{
            try{
                ProjectStore.Asset replacement=store.inspectUri(replacementUri);
                store.relinkAsset(projectId,expectedRevision,assetId,replacement);
                ActivityLog.add(this,"user","Source relinked","Stable asset and clip IDs preserved; replacement source checked against every clip range","success",100,null,projectId);
                ui.post(()->{
                    if(isFinishing()||isDestroyed())return;
                    if(activeProject!=null&&projectId.equals(activeProject.id)){
                        activeProject=store.get(projectId);if(activeProject==null)return;
                        String keepSelection=selectedClip==null?selectedId:selectedClip.id;selectedClip=activeProject.clip(keepSelection);
                        sourceAssetId=assetId;previewResultUri="";previewResultId="";
                        syncProtocolState();requestServiceSync();
                        if("editor".equals(currentScreen))showEditor();
                    }
                    Toast.makeText(this,"Original source relinked · Undo available",Toast.LENGTH_LONG).show();
                });
            }catch(Exception error){
                final String message=error.getMessage()==null?"Replacement must match the media type and cover every clip source range":error.getMessage();
                ui.post(()->{
                    if(isFinishing()||isDestroyed())return;
                    if(activeProject!=null&&projectId.equals(activeProject.id)){
                        activeProject=store.get(projectId);if(activeProject!=null){selectedClip=activeProject.clip(selectedClip==null?selectedId:selectedClip.id);if("editor".equals(currentScreen))showEditor();}
                    }
                    new AlertDialog.Builder(this).setTitle("Source was not relinked").setMessage(message).setPositiveButton("Close",null).show();
                });
            }
        });
    }

    private void previewProxyDialog() {
        String[] tiers={"auto","source","360p","540p","720p"};String[] labels={"Auto · cached tier adapts to playback and temperature","Original source","360p proxy if ready","540p proxy if ready","720p proxy if ready"};
        new AlertDialog.Builder(this).setTitle("Program preview media").setItems(labels,(d,index)->{previewProxyTier=tiers[index];bindEditorPreview(false);updateEditorPanel();}).setNegativeButton("Close",null).show();
    }

    private void ownerProxyDialog(ProjectStore.Asset asset) {
        String[] tiers={"360p","540p","720p"};final String projectId=activeProject.id;final String assetId=asset.id;
        new AlertDialog.Builder(this).setTitle("Build preview proxy · original kept").setItems(tiers,(d,index)->{
            if(editorStatus!=null)editorStatus.setText("Preparing "+tiers[index]+" proxy…");
            ownerMediaWorker.execute(()->{
                try {
                    ProjectStore.Project fresh=store.get(projectId);ProjectStore.Asset source=fresh==null?null:fresh.asset(assetId);
                    if(source==null)throw new IllegalStateException("Source media is no longer in this project");
                    JobManager.Job job=proxyManager.requestOwner(fresh,source,tiers[index]);
                    ui.post(()->{if(isFinishing()||isDestroyed())return;Toast.makeText(this,"Proxy job "+job.id+" queued",Toast.LENGTH_SHORT).show();if(editorStatus!=null)editorStatus.setText("Proxy queued · tap status to see background work");});
                }catch(Exception error){ui.post(()->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,error.getMessage()==null?"Could not build proxy":error.getMessage(),Toast.LENGTH_LONG).show();});}
            });
        }).setNegativeButton("Cancel",null).show();
    }

    private void projectSnapshotsDialog() {
        if(activeProject==null)return;
        new AlertDialog.Builder(this).setTitle("Project").setItems(new String[]{"Save named snapshot","Restore snapshot","Duplicate project","Rename project","Project metadata mirror"},(d,index)->{
            if(index==0)saveProjectSnapshotDialog();else if(index==1)restoreProjectSnapshotDialog();else if(index==2)duplicateProjectDialog();else if(index==3)renameProjectDialog();else projectMetadataDialog();
        }).setNegativeButton("Close",null).show();
    }

    private void projectMetadataDialog() {
        if(activeProject==null)return;final String projectId=activeProject.id;
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        box.addView(body("Share project structure and edits with the connected metadata service. Media bytes stay on the device. Remote edits apply here only when you choose Apply."));
        TextView detail=body("Reading native metadata state…");box.addView(detail);LinearLayout actions=column();box.addView(actions);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Project metadata mirror").setView(box).setNegativeButton("Close",null).create();dialog.show();
        final NativeMetadataMirror mirror=new NativeMetadataMirror(this,store,protocol);
        final JSONObject[] remote={null};final JSONObject[] local={new JSONObject()};
        Runnable render=()->{
            if(!dialog.isShowing()||isFinishing()||isDestroyed())return;
            String text=local[0].optBoolean("nativeProjectExists")?"Native revision: "+local[0].optLong("nativeRevision"):"Native project is unavailable";
            if(local[0].optBoolean("pendingAcknowledgement"))text+="\nNative edit saved; remote acknowledgement needs Retry.";
            else if(local[0].optBoolean("preparedApplyPending"))text+="\nA prepared edit needs explicit Apply.";
            if(local[0].optBoolean("stale"))text+="\nNative edits changed; inspect the pending conflict before retrying.";
            if(remote[0]!=null)text+="\nRemote revision: "+remote[0].optLong("mirrorRevision")+" · "+(remote[0].optBoolean("enabled")?"enabled":"disabled")+(remote[0].optBoolean("dirty")?" · pending edits":"");
            detail.setText(text);actions.removeAllViews();
            actions.addView(editorButton("Read remote state",()->{
                detail.setText("Reading remote metadata…");ownerMediaWorker.execute(()->{
                    try {JSONObject read=mirror.get(projectId),nativeState=mirror.status(projectId);ui.post(()->{if(!dialog.isShowing()||isFinishing()||isDestroyed())return;remote[0]=read;local[0]=nativeState;dialog.dismiss();showMetadataRemoteDialog(projectId,nativeState,read);});}
                    catch(Exception error){ui.post(()->{if(dialog.isShowing()&&!isFinishing()&&!isDestroyed())detail.setText(error.getMessage()==null?"Could not read remote metadata":error.getMessage());});}
                });
            }));
            if(local[0].optBoolean("requiresExplicitRetry"))actions.addView(editorButton("Retry acknowledgement",()->{dialog.dismiss();startMetadataOperation(projectId,"retry",false,-1,-1);}));
            actions.addView(editorButton("Activity",()->{dialog.dismiss();showActivity();}));
        };
        ownerMediaWorker.execute(()->{
            try{JSONObject nativeState=mirror.status(projectId);ui.post(()->{local[0]=nativeState;render.run();});}
            catch(Exception error){ui.post(()->{if(dialog.isShowing()&&!isFinishing()&&!isDestroyed())detail.setText(error.getMessage()==null?"Could not inspect native metadata":error.getMessage());});}
        });
    }

    private void showMetadataRemoteDialog(String projectId, JSONObject nativeState, JSONObject remote) {
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        box.addView(body("Native revision "+nativeState.optLong("nativeRevision")+" · remote revision "+remote.optLong("mirrorRevision")+"\nMetadata only; media stays on this device."));
        if(nativeState.optBoolean("pendingAcknowledgement"))box.addView(body("The native edit is saved. Retry only sends its acknowledgement."));
        else if(nativeState.optBoolean("stale"))box.addView(body("A native conflict remains. Refresh state and resolve it before applying remote edits."));
        final long mirrorRevision=remote.optLong("mirrorRevision",0),nativeRevision=nativeState.optLong("nativeRevision",0);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Project metadata").setView(box).setNegativeButton("Close",null).create();
        box.addView(editorButton("Sync native metadata",()->{
            new AlertDialog.Builder(this).setTitle("Enable metadata sync for this project?").setMessage("This sends the project graph and names to your connected metadata service. Native titles longer than 1000 characters cannot be mirrored. Changes remain revision checked and remote edits need explicit Apply.").setPositiveButton("Enable and sync",(d,w)->{dialog.dismiss();startMetadataOperation(projectId,"sync",true,mirrorRevision,-1);}).setNegativeButton("Cancel",null).show();
        }));
        if(remote.optBoolean("enabled")&&remote.optBoolean("dirty")&&!nativeState.optBoolean("stale"))box.addView(editorButton("Apply remote graph",()->new AlertDialog.Builder(this).setTitle("Apply remote project edits?").setMessage("Updates this native project at revision "+nativeRevision+". Undo remains available; media references stay owned by this project.").setPositiveButton("Apply",(d,w)->{dialog.dismiss();startMetadataOperation(projectId,"apply",false,mirrorRevision,nativeRevision);}).setNegativeButton("Cancel",null).show()));
        if(nativeState.optBoolean("requiresExplicitRetry"))box.addView(editorButton("Retry acknowledgement",()->{dialog.dismiss();startMetadataOperation(projectId,"retry",false,-1,-1);}));
        JSONObject pending=nativeState.optJSONObject("pendingMarker");
        if(remote.optBoolean("enabled")&&mirrorRevision>0&&nativeRevision>0&&pending!=null&&pending.optLong("expectedMirrorRevision")>0&&!pending.optString("fingerprint").isEmpty()&&!pending.optString("pendingId").isEmpty()){
            box.addView(editorButton("Keep current native edit",()->new AlertDialog.Builder(this).setTitle("Resolve with this native edit?").setMessage("Keeps native revision "+nativeRevision+" and replaces the remote project graph. A record of the conflict is preserved before the pending mirror operation is retired. Media stays on this device.").setPositiveButton("Keep native edit",(d,w)->{
                dialog.dismiss();startMetadataKeepNative(projectId,mirrorRevision,nativeRevision,pending);
            }).setNegativeButton("Cancel",null).show()));
        }
        if(mirrorRevision>0)box.addView(editorButton("Disable metadata mirror",()->new AlertDialog.Builder(this).setTitle("Disable this mirror?").setMessage("Revokes this project's remote metadata mirror. The native project and its owned media remain available.").setPositiveButton("Disable",(d,w)->{dialog.dismiss();startMetadataOperation(projectId,"revoke",false,mirrorRevision,-1);}).setNegativeButton("Cancel",null).show()));
        box.addView(editorButton("Refresh state",()->{dialog.dismiss();projectMetadataDialog();}));dialog.show();
    }

    private void startMetadataOperation(String projectId,String operation,boolean enabled,long mirrorRevision,long nativeRevision) {
        Intent intent=new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_METADATA_MIRROR).putExtra("projectId",projectId).putExtra("operation",operation).putExtra("enabled",enabled);
        if(mirrorRevision>=0)intent.putExtra("expectedMirrorRevision",mirrorRevision);if(nativeRevision>=0)intent.putExtra("expectedNativeRevision",nativeRevision);
        startForegroundService(intent);Toast.makeText(this,"Metadata operation queued · follow its result in Activity",Toast.LENGTH_LONG).show();
    }

    private void startMetadataKeepNative(String projectId,long mirrorRevision,long nativeRevision,JSONObject pending) {
        Intent intent=new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_METADATA_MIRROR)
                .putExtra("projectId",projectId).putExtra("operation","keep_native")
                .putExtra("expectedNativeRevision",nativeRevision).putExtra("expectedMirrorRevision",mirrorRevision)
                .putExtra("expectedPendingMirrorRevision",pending.optLong("expectedMirrorRevision"))
                .putExtra("expectedPendingFingerprint",pending.optString("fingerprint"))
                .putExtra("expectedPendingId",pending.optString("pendingId"));
        startForegroundService(intent);Toast.makeText(this,"Native conflict resolution queued · follow its result in Activity",Toast.LENGTH_LONG).show();
    }

    private void saveProjectSnapshotDialog() {
        EditText name=new EditText(this);name.setSingleLine();name.setHint("Snapshot name");name.setText("Edit "+new SimpleDateFormat("HH:mm",Locale.US).format(new Date()));
        final String projectId=activeProject.id;
        new AlertDialog.Builder(this).setTitle("Save snapshot").setView(name).setPositiveButton("Save",(d,w)->{
            try{store.saveSnapshot(projectId,activeProject.revision,name.getText().toString());Toast.makeText(this,"Snapshot saved",Toast.LENGTH_SHORT).show();}
            catch(Exception error){ownerEditError(error);}
        }).setNegativeButton("Cancel",null).show();
    }

    private void restoreProjectSnapshotDialog() {
        JSONArray snapshots=store.listSnapshots(activeProject.id);
        if(snapshots.length()==0){Toast.makeText(this,"No named snapshots yet",Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[snapshots.length()];
        for(int i=0;i<labels.length;i++){JSONObject snapshot=snapshots.optJSONObject(i);labels[i]=snapshot.optString("name")+" · r"+snapshot.optLong("revision")+" · "+formatActivityTime(snapshot.optLong("createdAt"));}
        final String projectId=activeProject.id;
        new AlertDialog.Builder(this).setTitle("Restore snapshot · undo remains available").setItems(labels,(d,index)->{
            try{activeProject=store.restoreSnapshot(projectId,activeProject.revision,snapshots.optJSONObject(index).optString("id"));syncProtocolState();requestServiceSync();showEditor();}
            catch(Exception error){ownerEditError(error);}
        }).setNegativeButton("Cancel",null).show();
    }

    private void duplicateProjectDialog() {
        EditText name=new EditText(this);name.setText(activeProject.name+" copy");name.setSingleLine();final String projectId=activeProject.id;
        new AlertDialog.Builder(this).setTitle("Duplicate project").setMessage("The new project references the same imported media and keeps its own edit history.").setView(name).setPositiveButton("Create copy",(d,w)->{
            try{activeProject=store.duplicateProject(projectId,name.getText().toString());store.setActive(activeProject.id);selectedClip=null;syncProtocolState();requestServiceSync();showEditor();}
            catch(Exception error){ownerEditError(error);}
        }).setNegativeButton("Cancel",null).show();
    }

    private JSONObject clipTransform(ProjectStore.Clip clip) {
        JSONObject transform=clip.effects.optJSONObject("transform");return transform==null?clip.effects:transform;
    }

    private double baseTransformProperty(ProjectStore.Clip clip,String property) {
        JSONObject transform=clipTransform(clip);String key=keyframeProperty(property);
        double fallback="scale".equals(property)||"opacity".equals(property)?1:0;
        if("scale".equals(property))return transform.optDouble("scaleX",transform.optDouble("scale",transform.optDouble("zoom",1)));
        return transform.optDouble(key,transform.optDouble(property,fallback));
    }

    private void setClipProperty(ProjectStore.Clip clip,String property,double value) throws Exception {
        if(java.util.Arrays.asList("translateX","translateY","scale","rotate","opacity").contains(property)){
            JSONObject target=clipTransform(clip);String key=keyframeProperty(property);
            target.put(key,value);if(!key.equals(property))target.remove(property);
            if("scale".equals(property)){target.remove("scaleX");target.remove("scaleY");target.remove("zoom");}
        }else clip.effects.put(property,value);
    }

    private boolean clipHasPropertyKeyframes(ProjectStore.Clip clip,String property) {
        JSONArray keys=clip.effects.optJSONArray("keyframes");if(keys==null)return false;String key=keyframeProperty(property);
        for(int i=0;i<keys.length();i++){JSONObject frame=keys.optJSONObject(i);if(frame!=null&&(keyframeValues(frame,property).has(key)||keyframeValues(frame,property).has(property)))return true;}return false;
    }

    private void applyGestureTransform(ProjectStore.Clip clip,long atMs,float ratio,float deltaX,float deltaY,float deltaRotation) throws Exception {
        MotionTimeline.Sample sample=MotionTimeline.evaluate(clip,atMs);
        if(clipHasPropertyKeyframes(clip,"translateX"))writeKeyframe(clip,"translateX",sample.x+deltaX,atMs,"easeInOut");else setClipProperty(clip,"translateX",baseTransformProperty(clip,"translateX")+deltaX);
        if(clipHasPropertyKeyframes(clip,"translateY"))writeKeyframe(clip,"translateY",sample.y+deltaY,atMs,"easeInOut");else setClipProperty(clip,"translateY",baseTransformProperty(clip,"translateY")+deltaY);
        if(clipHasPropertyKeyframes(clip,"rotate"))writeKeyframe(clip,"rotate",sample.rotation+deltaRotation,atMs,"easeInOut");else setClipProperty(clip,"rotate",baseTransformProperty(clip,"rotate")+deltaRotation);
        if(clipHasPropertyKeyframes(clip,"scale")||clipHasPropertyKeyframes(clip,"scaleX")||clipHasPropertyKeyframes(clip,"scaleY")){
            writeKeyframe(clip,"scaleX",sample.scaleX*ratio,atMs,"easeInOut");writeKeyframe(clip,"scaleY",sample.scaleY*ratio,atMs,"easeInOut");
        }else{
            JSONObject transform=clipTransform(clip);double scale=transform.optDouble("scale",transform.optDouble("zoom",1));
            transform.put("scaleX",transform.optDouble("scaleX",scale)*ratio);transform.put("scaleY",transform.optDouble("scaleY",scale)*ratio);
        }
    }

    private JSONObject keyframeValues(JSONObject frame, String property) {
        if ("volume".equals(property)) return frame;
        JSONObject transform = frame.optJSONObject("transform");
        return transform == null ? frame : transform;
    }

    private void migrateAudioKeyframes(ProjectStore.Clip clip) throws Exception {
        JSONArray audio=clip.effects.optJSONArray("audioKeyframes");if(audio==null)return;
        JSONArray keys=clip.effects.optJSONArray("keyframes");if(keys==null)keys=new JSONArray();
        canonicalizeKeyframeTimes(clip,keys);canonicalizeKeyframeTimes(clip,audio);
        for(int i=0;i<audio.length();i++) { JSONObject frame=audio.optJSONObject(i);if(frame!=null&&frame.has("volume"))keys.put(new JSONObject(frame.toString())); }
        clip.effects.put("keyframes",keys);clip.effects.remove("audioKeyframes");
    }

    private long authoredKeyTime(ProjectStore.Clip clip, JSONObject frame, int index, int count) {
        long duration=Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));
        double time=frame.has("timeMs")?frame.optDouble("timeMs",0):frame.has("timeUs")?frame.optDouble("timeUs",0)/1000d:frame.optDouble("t",index/(double)Math.max(1,count-1))*duration;
        if(!Double.isFinite(time))return 0;
        return Math.max(0,Math.min(duration,Math.round(time)));
    }

    private void canonicalizeKeyframeTimes(ProjectStore.Clip clip, JSONArray keys) throws Exception {
        for(int i=0;i<keys.length();i++){JSONObject frame=keys.optJSONObject(i);if(frame!=null){long time=authoredKeyTime(clip,frame,i,keys.length());frame.put("timeMs",time);frame.remove("timeUs");frame.remove("t");}}
    }

    private boolean validKeyframeValue(String property, double value) {
        if(!Double.isFinite(value))return false;
        if("volume".equals(property))return value>=0&&value<=2;
        if("opacity".equals(property))return value>=0&&value<=1;
        if(property.startsWith("scale"))return value>=.01&&value<=10;
        if("rotate".equals(property)||"rotation".equals(property))return value>=-3600&&value<=3600;
        return value>=-10&&value<=10;
    }

    private String keyframeProperty(String property) {
        if ("translateX".equals(property)) return "x";
        if ("translateY".equals(property)) return "y";
        if ("rotate".equals(property)) return "rotation";
        return property;
    }

    private long clipOutputPosition() {
        if (selectedClip == null) return 0;
        long relative = programMonitor ? ownerPlayheadMs - selectedClip.startMs
                : Math.round((sourcePlayheadMs - selectedClip.inMs) / Math.max(Float.MIN_NORMAL,selectedClip.effectiveSpeed()));
        return Math.max(0,Math.min(relative,selectedClip.outputDurationMs()));
    }

    private boolean hasPropertyKeyframes(String property) {
        if(selectedClip==null)return false;
        if ("volume".equals(property)) {
            JSONArray audio=selectedClip.effects.optJSONArray("audioKeyframes");
            if(audio!=null)for(int i=0;i<audio.length();i++){JSONObject frame=audio.optJSONObject(i);if(frame!=null&&frame.has("volume"))return true;}
        }
        JSONArray keys=selectedClip.effects.optJSONArray("keyframes");
        if(keys==null)return false;
        String key=keyframeProperty(property);
        for(int i=0;i<keys.length();i++){JSONObject frame=keys.optJSONObject(i);if(frame!=null&&(keyframeValues(frame, property).has(key)||keyframeValues(frame, property).has(property)||("scale".equals(property)&&(keyframeValues(frame, property).has("scaleX")||keyframeValues(frame, property).has("scaleY")))))return true;}
        return false;
    }

    private void writeKeyframe(ProjectStore.Clip clip, String property, double value, long atMs, String easing) throws Exception {
        writeKeyframe(clip,property,value,atMs,easing,null);
    }

    private void writeKeyframe(ProjectStore.Clip clip,String property,double value,long atMs,String easing,JSONArray controls) throws Exception {
        if(!validKeyframeValue(property,value))throw new IllegalArgumentException("Invalid " + property + " keyframe value");
        if(!MotionTimeline.supportsEasing(easing))throw new IllegalArgumentException("Unsupported keyframe easing");
        if("cubic_bezier".equals(easing))controls=CubicBezierEasing.fromJson(controls).toJson();
        else if(controls!=null)throw new IllegalArgumentException("Custom control points require cubic_bezier easing");
        atMs=ProjectTimeline.safeAdd(atMs,clip.effects.optLong("animationOffsetMs",0));
        long authoredDuration=Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));
        if(atMs<0||atMs>authoredDuration)throw new IllegalArgumentException("This extended trim holds the nearest animation state. Place the keyframe inside the authored animation window.");
        if ("volume".equals(property)) migrateAudioKeyframes(clip);
        JSONArray keys=clip.effects.optJSONArray("keyframes");
        if(keys==null)keys=new JSONArray();
        canonicalizeKeyframeTimes(clip,keys);
        JSONObject target=null;
        for(int i=0;i<keys.length();i++){
            JSONObject frame=keys.optJSONObject(i);
            if(frame!=null&&Math.abs(frame.optLong("timeMs",-1000)-atMs)<17)target=frame;
        }
        if(target==null){target=new JSONObject();keys.put(target);}
        target.put("timeMs",atMs);keyframeValues(target, property).put(keyframeProperty(property),value);target.put("easing",easing);
        if(controls==null)target.remove("bezier");else target.put("bezier",controls);
        if ("scale".equals(property)) { keyframeValues(target, property).remove("scaleX"); keyframeValues(target, property).remove("scaleY"); }
        clip.effects.put("keyframes",keys);
    }

    private void addKeyframe(String property, double value) {
        if(!requireSelectedClip())return;
        String clipId=selectedClip.id;long atMs=clipOutputPosition();
        String easing=selectedClip.effects.optString("volume".equals(property)?"audioEasing":"ease","easeInOut");
        JSONArray controls=selectedClip.effects.optJSONArray("volume".equals(property)?"audioBezier":"bezier");
        ownerEdit("Add "+property+" keyframe",p->writeKeyframe(p.clip(clipId),property,value,atMs,easing,controls));
    }

    private void keyframeOverviewDialog() {
        if(!requireSelectedClip())return;
        if(isSelectedAudioTrack()){keyframePropertyDialog("volume");return;}
        String[] properties={"translateX","translateY","scale","rotate","opacity","volume"};
        String[] labels={"Position X","Position Y","Scale","Rotation","Opacity","Volume"};
        for(int i=0;i<labels.length;i++)labels[i]+=(hasPropertyKeyframes(properties[i])?" · ◆":" · no keyframes");
        new AlertDialog.Builder(this).setTitle("Clip keyframes · "+timecode(clipOutputPosition())).setItems(labels,(d,i)->keyframePropertyDialog(properties[i])).setNegativeButton("Close",null).show();
    }

    private void keyframePropertyDialog(String property) {
        if(!requireSelectedClip())return;
        if ("volume".equals(property) && selectedClip.effects.has("audioKeyframes")) {
            final String clipId=selectedClip.id;
            ownerEdit("Use editable audio keyframes",p->migrateAudioKeyframes(p.clip(clipId)));
            if (selectedClip != null && !selectedClip.effects.has("audioKeyframes")) keyframePropertyDialog(property);
            return;
        }
        JSONArray keys=selectedClip.effects.optJSONArray("keyframes");
        ArrayList<Integer> indices=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        if(keys!=null)for(int i=0;i<keys.length();i++){
            JSONObject frame=keys.optJSONObject(i);
            long localTime = frame == null ? -1 : authoredKeyTime(selectedClip,frame,i,keys.length()) - selectedClip.effects.optLong("animationOffsetMs", 0);
            if(frame!=null&&localTime>=0&&localTime<=selectedClip.outputDurationMs()&&(keyframeValues(frame, property).has(keyframeProperty(property))||keyframeValues(frame, property).has(property)||("scale".equals(property)&&keyframeValues(frame, property).has("scaleX")))){
                indices.add(i);labels.add(timecode(localTime)+" · "+String.format(Locale.US,"%.2f",keyframeValues(frame, property).optDouble(keyframeProperty(property),keyframeValues(frame, property).optDouble("scale".equals(property)?"scaleX":property,0)))+" · "+frame.optString("easing","linear"));
            }
        }
        new AlertDialog.Builder(this).setTitle(property+" keyframes").setMessage(labels.isEmpty()?"Tap the diamond to save a value at the current playhead.":null)
                .setItems(labels.toArray(new String[0]),(d,i)->editKeyframeDialog(property,indices.get(i)))
                .setPositiveButton("Add at playhead",(d,w)->addKeyframe(property,propertyValue(property)))
                .setNegativeButton("Close",null).show();
    }

    private double propertyValue(String property) {
        if(selectedClip==null)return 0;
        if ("volume".equals(property)) {
            try{return new AudioGainEnvelope(selectedClip).automationAt(clipOutputPosition()*1000L);}
            catch(IllegalArgumentException error){return Float.isFinite(selectedClip.volume)?Math.max(0,Math.min(2,selectedClip.volume)):1;}
        }
        if (!hasPropertyKeyframes(property)) return baseTransformProperty(selectedClip, property);
        MotionTimeline.Sample sample=MotionTimeline.evaluate(selectedClip,clipOutputPosition());
        switch(property){
            case "translateX":return sample.x;
            case "translateY":return sample.y;
            case "scale":return sample.scaleX;
            case "rotate":return sample.rotation;
            case "opacity":return sample.opacity;
            default:return selectedClip.effects.optDouble(property,0);
        }
    }

    private void editKeyframeDialog(String property, int index) {
        JSONArray keys=selectedClip.effects.optJSONArray("keyframes");JSONObject frame=keys==null?null:keys.optJSONObject(index);if(frame==null)return;
        livePlayer.pause();final String clipId=selectedClip.id,projectId=activeProject.id;final long revision=activeProject.revision,clipDuration=selectedClip.outputDurationMs();
        LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);
        EditText timeInput=numberInput((authoredKeyTime(selectedClip,frame,index,keys.length())-selectedClip.effects.optLong("animationOffsetMs",0))/1000f);
        EditText valueInput=numberInput((float)keyframeValues(frame, property).optDouble(keyframeProperty(property),keyframeValues(frame, property).optDouble("scale".equals(property)?"scaleX":property,0)));
        valueInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditorEasingControls easing=new EditorEasingControls(this,frame.optString("easing",selectedClip.effects.optString("ease","linear")),frame.has("easing")?frame.optJSONArray("bezier"):selectedClip.effects.optJSONArray("bezier"));
        box.addView(body("Time in clip · seconds"));box.addView(timeInput);box.addView(body(property));box.addView(valueInput);box.addView(body("Interpolation to the next keyframe"));box.addView(easing);
        ScrollView scroll=baseScroll();scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit keyframe").setView(scroll).setPositiveButton("Save",null).setNeutralButton("Delete",(d,w)->ownerEdit("Delete keyframe",projectId,revision,p->{
            JSONArray existing=p.clip(clipId).effects.optJSONArray("keyframes");JSONObject target=existing.optJSONObject(index);
            keyframeValues(target, property).remove(keyframeProperty(property));keyframeValues(target, property).remove(property);
            if ("scale".equals(property)) { keyframeValues(target, property).remove("scaleX"); keyframeValues(target, property).remove("scaleY"); }
            boolean values=false;for(String key:new String[]{"x","y","scale","scaleX","scaleY","rotation","opacity","translateX","translateY","rotate","volume"})if(keyframeValues(target, property).has(key))values=true;
            if(!values && !target.has("volume") && !target.has("transform")) existing.remove(index);
        })).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                double time=Double.parseDouble(timeInput.getText().toString());if(!Double.isFinite(time))throw new IllegalArgumentException("Enter a finite clip time");long atMs=Math.round(time*1000);
                double value=Double.parseDouble(valueInput.getText().toString());
                if(atMs<0||atMs>clipDuration||!validKeyframeValue(property,value))throw new IllegalArgumentException("Enter a valid clip time and value");
                JSONArray controls=easing.bezier();
                if(ownerEdit("Edit keyframe",projectId,revision,p->{
                    ProjectStore.Clip clip=p.clip(clipId);long authoredTime=ProjectTimeline.safeAdd(atMs,clip.effects.optLong("animationOffsetMs",0));
                    if(authoredTime<0||authoredTime>Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs())))throw new IllegalArgumentException("Place this keyframe inside the authored animation window; extended trim holds the nearest state.");
                    canonicalizeKeyframeTimes(clip,clip.effects.optJSONArray("keyframes"));
                    JSONObject target=clip.effects.optJSONArray("keyframes").optJSONObject(index);
                    target.put("timeMs",authoredTime);keyframeValues(target, property).put(keyframeProperty(property),value);target.put("easing",easing.selectedName());
                    if(controls==null)target.remove("bezier");else target.put("bezier",controls);
                    if ("scale".equals(property)) { keyframeValues(target, property).remove("scaleX"); keyframeValues(target, property).remove("scaleY"); }
                }))dialog.dismiss();
            }catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Enter a valid clip time and value":error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }

    private String ownerExportAspect = "9:16";
    private String ownerExportQuality = "1080p";
    private String ownerExportMode = "full";
    private long ownerExportRangeInMs,ownerExportRangeOutMs,ownerExportRangeRevision=-1;
    private String ownerExportRangeProjectId="";
    private String ownerExportDetail = "Choose your export settings.";
    private long exportKnownRevision;

    private void showExport() {
        if(activeProject==null)return;
        ProjectStore.Project latest=store.get(activeProject.id);if(latest!=null)activeProject=latest;
        try {
            JSONObject session = new JSONObject(prefs.getString(manualExportSessionId.isEmpty() ? "manual_export_session" : "manual_export_session:" + manualExportSessionId, "{}"));
            if (activeProject.id.equals(session.optString("projectId"))) {
                manualExportSessionId = session.optString("sessionId", manualExportSessionId);
                String status = session.optString("status", "");
                if (!status.isEmpty()) {
                    ownerExportDetail = exportSessionDetail(session);
                    ownerExportInFlight = !isTerminalJobState(status) && !"success".equals(status);
                }
            } else if (!session.optString("projectId", "").isEmpty()) {
                manualExportSessionId = ""; ownerExportInFlight = false; ownerExportDetail = "Choose your export settings.";
            }
        } catch (Exception ignored) { }
        livePlayer.pause();
        ScrollView scroll=baseScroll();LinearLayout box=column();box.setPadding(dp(16),dp(16),dp(16),dp(20));scroll.addView(box);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(editorButton("‹ Editor",this::showEditor));
        TextView heading=title("Export",24);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        box.addView(top);
        box.addView(body(activeProject.name+" · "+time(activeProject.outputDurationMs())+" · "+activeProject.tracks.size()+" tracks"));
        box.addView(section("Export selection"));
        LinearLayout scope=new LinearLayout(this);
        Button full=editorButton("Whole program",()->{ownerExportMode="full";showExport();});full.setTextColor("full".equals(ownerExportMode)?C_CYAN:C_MUTED);scope.addView(full,new LinearLayout.LayoutParams(0,dp(40),1));
        ProjectMarkers.Range savedRange=ProjectMarkers.selection(activeProject);
        Button range=editorButton("Saved program In / Out",()->{
            ProjectMarkers.Range chosen=ProjectMarkers.selection(activeProject);
            if(!chosen.defined||!chosen.valid||!chosen.enabled){Toast.makeText(this,"Save and enable valid program In / Out before choosing a range export",Toast.LENGTH_LONG).show();return;}
            ownerExportMode="range";ownerExportRangeInMs=chosen.inMs;ownerExportRangeOutMs=chosen.outMs;ownerExportRangeRevision=activeProject.revision;ownerExportRangeProjectId=activeProject.id;showExport();
        });range.setTextColor("range".equals(ownerExportMode)?C_CYAN:C_MUTED);scope.addView(range,new LinearLayout.LayoutParams(0,dp(40),1));box.addView(scope);
        if("range".equals(ownerExportMode)){
            box.addView(body("Selected export: "+timecode(ownerExportRangeInMs)+" – "+timecode(ownerExportRangeOutMs)+" · "+time(Math.max(0,ownerExportRangeOutMs-ownerExportRangeInMs))+" · reviewed revision "+ownerExportRangeRevision));
            if(!validOwnerExportRange())box.addView(body("The program changed. Choose Saved program In / Out again to review its current bounds, or choose Whole program."));
            else if(ownerExportRangeInMs>0)box.addView(body("Audio dynamics and ambience start at the selected range In."));
        }else if(savedRange.defined&&savedRange.valid)box.addView(body("Saved In / Out: "+timecode(savedRange.inMs)+" – "+timecode(savedRange.outMs)+". Choose the range explicitly to export it."));
        box.addView(editorButton("Edit program In / Out",this::programRangeDialog));
        box.addView(section("Frame format"));
        LinearLayout aspect=new LinearLayout(this);
        for(String value:new String[]{"9:16","16:9","1:1","4:5"}){
            Button button=editorButton(value,()->{ownerExportAspect=value;showExport();});button.setTextColor(value.equals(ownerExportAspect)?C_CYAN:C_MUTED);aspect.addView(button,new LinearLayout.LayoutParams(0,dp(40),1));
        }
        box.addView(aspect);
        box.addView(section("Resolution"));
        LinearLayout quality=new LinearLayout(this);
        for(String value:new String[]{"720p","1080p"}){
            Button button=editorButton(value,()->{ownerExportQuality=value;showExport();});button.setTextColor(value.equals(ownerExportQuality)?C_CYAN:C_MUTED);quality.addView(button,new LinearLayout.LayoutParams(0,dp(40),1));
        }
        box.addView(quality);
        box.addView(editorButton("Output cadence · "+(activeProject.animationFrameRate==0?30:activeProject.animationFrameRate)+" fps",this::projectFrameRateDialog));
        box.addView(body("MP4 · H.264 video · AAC audio · saved to the project Media Bin and Android Movies"));
        Button start=neonButton(ownerExportInFlight ? "Export in progress" : "range".equals(ownerExportMode)?"Export selected range":"Export whole program",C_BLUE);start.setEnabled(!ownerExportInFlight&&(!"range".equals(ownerExportMode)||validOwnerExportRange()));start.setOnClickListener(v->startOwnerExport());box.addView(start,margins(-1,dp(52),dp(18),dp(12),0,0));
        LinearLayout status=card(false);exportStatusView=body(ownerExportDetail);status.addView(exportStatusView);
        exportProgressView=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);exportProgressView.setMax(100);exportProgressView.setIndeterminate(!manualExportSessionId.isEmpty());
        status.addView(exportProgressView,margins(-1,dp(10),dp(8),0,0,0));
        Button stop=editorButton("Cancel export",()->{
            if(manualExportSessionId.isEmpty())return;
            Intent cancel=new Intent(this,ControlService.class).setAction(ControlService.ACTION_CANCEL_LOCAL_EXPORT).putExtra("exportSessionId",manualExportSessionId);startForegroundService(cancel);
            ownerExportDetail="Cancelling export";exportStatusView.setText(ownerExportDetail);
        });status.addView(stop);stop.setEnabled(ownerExportInFlight);
        box.addView(status);
        if(activeProject.latestExportUri!=null&&!activeProject.latestExportUri.isEmpty()){
            box.addView(section("Latest completed video"));
            box.addView(body(activeProject.latestExportName+" · "+formatActivityTime(activeProject.latestExportAt)));
            LinearLayout result=new LinearLayout(this);
            result.addView(editorButton("▶ Play video",()->{
                showCompletedResult(activeProject.latestExportUri,"final:"+activeProject.latestExportAt);
                if(editorPreviewBadge!=null)editorPreviewBadge.setText("Final export · "+activeProject.latestExportName);
            }));
            result.addView(editorButton("Share",this::shareLatestExport));box.addView(result);
        }
        setScreen(scroll,"export");
        exportKnownRevision=activeProject.revision;
        scheduleExportRefresh();
    }

    private void startOwnerExport() {
        if(activeProject==null||activeProject.clips.isEmpty()){Toast.makeText(this,"Add media to the timeline first",Toast.LENGTH_SHORT).show();return;}
        if (ownerExportInFlight) return;
        if("range".equals(ownerExportMode)&&!validOwnerExportRange()){Toast.makeText(this,"Review the current program In / Out before exporting this range",Toast.LENGTH_LONG).show();return;}
        manualExportSessionId=UUID.randomUUID().toString();ownerExportDetail="Preparing owner export";ownerExportInFlight=true;
        Intent export=new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_EXPORT)
                .putExtra("projectId",activeProject.id).putExtra("aspect",ownerExportAspect).putExtra("quality",ownerExportQuality)
                .putExtra("fileName","VideoStudio_"+("range".equals(ownerExportMode)?"Range_":"")+System.currentTimeMillis()+".mp4").putExtra("exportSessionId",manualExportSessionId)
                .putExtra("exportMode",ownerExportMode).putExtra("expectedRevision",activeProject.revision);
        if("range".equals(ownerExportMode))export.putExtra("inMs",ownerExportRangeInMs).putExtra("outMs",ownerExportRangeOutMs);
        try{startForegroundService(export);showExport();}
        catch(Exception error){ownerExportInFlight=false;ownerExportDetail=error.getMessage()==null?"Could not start export":error.getMessage();showExport();}
    }

    private boolean validOwnerExportRange() {
        return activeProject!=null&&ownerExportRangeProjectId.equals(activeProject.id)&&ownerExportRangeRevision==activeProject.revision&&ownerExportRangeInMs>=0&&ownerExportRangeOutMs>ownerExportRangeInMs&&ownerExportRangeOutMs<=activeProject.outputDurationMs();
    }

    private String exportSessionDetail(JSONObject session) {
        String detail=session.optString("status",session.optString("state","preparing")).replace('_',' ')+" · "+session.optString("detail","");
        if("range".equals(session.optString("exportMode")))detail+="\nAccepted export range: "+timecode(session.optLong("inMs"))+" – "+timecode(session.optLong("outMs"))+" · r"+session.optLong("projectRevision");
        return detail;
    }

    private void scheduleExportRefresh() {
        activityRefresh=()->{
            if(!"export".equals(currentScreen)||activeProject==null)return;
            ProjectStore.Project latest=store.get(activeProject.id);
            if(latest!=null&&latest.revision!=exportKnownRevision){activeProject=latest;showExport();return;}
            JSONObject session;
            try{session=new JSONObject(prefs.getString("manual_export_session:"+manualExportSessionId,"{}"));}catch(Exception error){session=new JSONObject();}
            if(!manualExportSessionId.isEmpty() && manualExportSessionId.equals(session.optString("sessionId")) && activeProject.id.equals(session.optString("projectId"))){
                String state=session.optString("status",session.optString("state","preparing"));
                boolean wasInFlight=ownerExportInFlight;
                ownerExportInFlight = !isTerminalJobState(state) && !"success".equals(state);
                if (wasInFlight && !ownerExportInFlight) { showExport(); return; }
                ownerExportDetail=exportSessionDetail(session);
                if(exportStatusView!=null)exportStatusView.setText(ownerExportDetail);
                if(exportProgressView!=null){exportProgressView.setIndeterminate(!session.has("progress")&&!isTerminalJobState(state));exportProgressView.setProgress(session.optInt("progress",0));}
            }
            ui.postDelayed(activityRefresh,1000);
        };
        ui.postDelayed(activityRefresh,1000);
    }

    private void shareLatestExport() {
        if(activeProject==null||activeProject.latestExportUri==null||activeProject.latestExportUri.isEmpty())return;
        Uri uri=Uri.parse(activeProject.latestExportUri);
        Intent share=new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri("VideoStudio export",uri));
        try{startActivity(Intent.createChooser(share,"Share exported video"));}
        catch(Exception error){Toast.makeText(this,"Could not share this video",Toast.LENGTH_SHORT).show();}
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
                    showCompletedResult(latest.uri, latest.id);
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

        box.addView(title("Create and edit", 27));
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
                boolean available=tool.contains("Prompt Video")||tool.contains("Animate Stills")||tool.contains("Green")||tool.contains("Transition")||tool.contains("Motion")||tool.contains("Colour")||tool.contains("Fonts")||tool.contains("Blur");
                if(!available){tile.addView(accent("Additional engine module required",C_MUTED));tile.setAlpha(.5f);}
                tile.setEnabled(available);
                tile.setOnClickListener(v -> {
                    if (tool.contains("Prompt Video")) promptVideoDialog();
                    else if (tool.contains("Animate Stills")) animateImagesDialog();
                    else if (tool.contains("Green")) applyTool("Green Screen");
                    else if (tool.contains("Transition")) applyTool("Transitions");
                    else if (tool.contains("Motion")) applyTool("Motion");
                    else if (tool.contains("Colour")) applyTool("Colour");
                    else if (tool.contains("Fonts")) applyTool("Fonts");
                    else if (tool.contains("Blur")) applyTool("Blur");
                    else Toast.makeText(this, "This tool needs an additional executable engine module", Toast.LENGTH_SHORT).show();
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
        box.addView(editorButton("Storage Hub · five selected folders", this::showStorageHub));
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
                        jobs.cancelAutonomous();
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

    private void showStorageHub() {
        ScrollView scroll=baseScroll();LinearLayout box=column();box.setPadding(dp(16),dp(16),dp(16),dp(24));scroll.addView(box);
        box.addView(editorButton("‹ Control",this::showControl));box.addView(title("Storage Hub",25));
        box.addView(body("Up to five folders you select through Android. Each folder keeps its own role and access grant."));
        TextView loading=body("Checking selected folder access…");box.addView(loading,margins(-1,-2,dp(16),dp(12),0,0));
        LinearLayout slots=column();box.addView(slots);
        setScreen(scroll,"storage");final int generation=++storageViewGeneration;
        ownerMediaWorker.execute(()->{
            JSONArray profiles;
            try{profiles=storageProfiles.profiles();}catch(Exception error){profiles=new JSONArray();}
            final JSONArray available=profiles;
            ui.post(()->{
                if(isFinishing()||isDestroyed()||!"storage".equals(currentScreen)||generation!=storageViewGeneration)return;
                loading.setText("Cloud quota is not reported by Android's folder providers.");
                for(int i=0;i<5;i++){
                    final int slot=i;JSONObject profile=available.optJSONObject(i);if(profile==null)profile=new JSONObject();
                    final JSONObject selected=profile;boolean linked=profile.optBoolean("linked");boolean active=profile.optBoolean("active");
                    LinearLayout tile=card(false);tile.addView(title("Folder "+(slot+1)+(active?" · ACTIVE":""),16));
                    tile.addView(body(linked?profile.optString("label",profile.optString("displayName","Selected folder")):"No folder selected"));
                    if(linked){
                        tile.addView(accent(profile.optString("role","workspace").toUpperCase(Locale.US)+" · "+profile.optString("providerAuthority","document provider"),C_CYAN));
                        tile.addView(body("Access: "+profile.optString("availability",profile.optString("permissionState","unknown"))+"\nRead: "+(profile.optBoolean("persistedRead")?"granted":"not granted")+" · Write: "+(profile.optBoolean("persistedWrite")?"granted":"not granted")+"\nQuota: unknown"));
                    }
                    LinearLayout actions=new LinearLayout(this);
                    actions.addView(editorButton(linked?"Change":"Choose folder",()->pickStorageFolder(slot)));
                    if(linked){
                        actions.addView(editorButton(active?"Active":"Use workspace",()->{try{storageProfiles.select(slot);showStorageHub();}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}));
                        actions.addView(editorButton("Unlink",()->new AlertDialog.Builder(this).setTitle("Unlink this selected folder?").setMessage("This removes the folder profile. Existing files and imported media remain available through their retained grants.").setPositiveButton("Unlink",(d,w)->{storageProfiles.unlink(slot);showStorageHub();}).setNegativeButton("Cancel",null).show()));
                    }
                    tile.addView(actions);slots.addView(tile,margins(-1,-2,dp(8),dp(6),0,0));
                }
                LinearLayout projectActions=new LinearLayout(this);
                projectActions.addView(editorButton("Archive project",this::archiveActiveProjectToCloud));projectActions.addView(editorButton("Restore project",this::restoreActiveProjectFromCloud));
                slots.addView(projectActions);
            });
        });
    }

    private void pickStorageFolder(int slot) {
        pendingStorageSlot=slot;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent,PICK_STORAGE_FOLDER);
    }

    private void configureStorageFolderDialog(int slot,Uri tree) {
        if(slot<0||slot>=5){showStorageHub();return;}
        LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);
        EditText label=new EditText(this);label.setSingleLine();label.setText("Folder "+(slot+1));box.addView(body("Name"));box.addView(label);
        String[] roles={"workspace","media","models","exports","archive"};Spinner role=new Spinner(this);
        role.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,roles));box.addView(body("Role"));box.addView(role);
        new AlertDialog.Builder(this).setTitle("Selected storage folder").setView(box).setPositiveButton("Link folder",(d,w)->{
            try{storageProfiles.link(slot,tree,label.getText().toString(),String.valueOf(role.getSelectedItem()));showStorageHub();}
            catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();showStorageHub();}
        }).setNegativeButton("Cancel",(d,w)->showStorageHub()).show();
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
        final String selectedTree;
        try{selectedTree=driveWorkspace.captureTreeUri();}catch(Exception error){Toast.makeText(this,"Select a readable storage folder before archiving",Toast.LENGTH_LONG).show();return;}
        JobManager.Job job = jobs.submitOwnerPriority("Archive project • " + project.name, JobManager.Kind.LIGHT, state -> {
            state.checkpoint("Cloud archive", 2, "Preparing project workspace");
            CreativeWorkspace creative=new CreativeWorkspace(this,store);
            if(creative.cloudOffloadState(project.id).optBoolean("offloaded"))throw new IllegalStateException("Restore the offloaded workspace from its recorded archive before creating a new archive");
            File workspace = creative.projectRoot(project.id);
            JSONObject result = driveWorkspace.syncProject(project, workspace, (progress, detail) -> {
                state.checkpoint("Cloud archive", progress, detail);
                ActivityLog.progress(this, state.id, "Cloud archive", detail, progress, project.id);
            },selectedTree);
            if(!result.optBoolean("ok"))throw new IllegalStateException("Project archive did not complete and verify");
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
        final String selectedTree;
        try{selectedTree=driveWorkspace.captureTreeUri();}catch(Exception error){Toast.makeText(this,"Select a readable storage folder before offloading",Toast.LENGTH_LONG).show();return;}
        JobManager.Job job = jobs.submitOwnerPriority("Cloud offload • " + project.name, JobManager.Kind.HEAVY, state -> {
            CreativeWorkspace creative=new CreativeWorkspace(this,store);
            if(creative.cloudOffloadState(project.id).optBoolean("offloaded"))throw new IllegalStateException("This workspace is already offloaded. Restore its exact recorded archive before archiving or offloading it again");
            File workspace = creative.projectRoot(project.id);
            state.checkpoint("Cloud offload", 2, "Archiving before local eviction");
            JSONObject archived = driveWorkspace.syncProject(project, workspace, (progress, detail) -> {
                int mapped = Math.max(2, Math.min(88, 2 + (int) (progress * .86)));
                state.checkpoint("Cloud offload", mapped, detail);
                ActivityLog.progress(this, state.id, "Cloud offload", detail, mapped, project.id);
            },selectedTree);
            if (!archived.optBoolean("ok", false)) throw new IllegalStateException("Cloud archive did not complete");
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Offload was cancelled before local eviction");
            state.checkpoint("Cloud offload",90,"Archive verified; checking local file references");
            JSONObject evicted = creative.evictCloudBackedProject(project.id,archived);
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
        ProjectStore.Project project = store.get(activeProject.id);
        if (project == null) return;
        Uri selected=storageProfiles.activeTree();final String selectedTree=selected==null?"":selected.toString();
        JobManager.Job job = jobs.submitOwnerPriority("Restore project • " + project.name, JobManager.Kind.LIGHT, state -> {
            state.checkpoint("Cloud restore", 2, "Preparing project workspace");
            CreativeWorkspace creative=new CreativeWorkspace(this,store);File workspace=creative.projectRoot(project.id);
            JSONObject offload=creative.cloudOffloadState(project.id);boolean recovery=offload.optBoolean("offloaded");
            String restoreTree=recovery?offload.optString("archiveTreeUri"):selectedTree;
            String archiveDirectory=recovery?offload.optString("archiveDirectoryUri"):null;
            if(restoreTree.isEmpty()||recovery&&(archiveDirectory==null||archiveDirectory.isEmpty()))throw new IllegalStateException(recovery?"The offload recovery marker has no exact archive location; restore access to the recorded folder before retrying":"Select a storage folder before restoring this project workspace");
            JSONObject result = driveWorkspace.restoreProjectWorkspace(project.id, workspace,true, (progress, detail) -> {
                state.checkpoint("Cloud restore", progress, detail);
                ActivityLog.progress(this, state.id, "Cloud restore", detail, progress, project.id);
            },restoreTree,archiveDirectory);
            if(!result.optBoolean("ok"))throw new IllegalStateException("Project workspace restore did not complete and verify");
            if(recovery){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Restore was cancelled before clearing its recovery marker");state.checkpoint("Cloud restore",99,"Verified recorded archive restored; clearing recovery marker");creative.markCloudHydrated(project.id);}
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
        if(requestCode==PICK_RELINK_SOURCE){
            final String projectId=pendingRelinkProjectId,assetId=pendingRelinkAssetId;final long revision=pendingRelinkRevision;
            pendingRelinkProjectId="";pendingRelinkAssetId="";pendingRelinkRevision=-1;
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
                if(projectId.isEmpty()||assetId.isEmpty()||revision<0){Toast.makeText(this,"Relink target was unavailable; select the original source again",Toast.LENGTH_LONG).show();}
                else try{
                    int flags=data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    if(flags==0)throw new SecurityException("No read access was granted for this source");
                    getContentResolver().takePersistableUriPermission(data.getData(),flags);
                    relinkOwnerSource(projectId,assetId,revision,data.getData());
                }catch(Exception error){Toast.makeText(this,"Could not retain access to the replacement source",Toast.LENGTH_LONG).show();}
            }
        }else if (requestCode == PICK_STORAGE_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            int slot = pendingStorageSlot; pendingStorageSlot = -1;
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(tree, flags);
                configureStorageFolderDialog(slot, tree);
            } catch (Exception error) {
                Toast.makeText(this, "Could not retain access to this folder", Toast.LENGTH_LONG).show();
                showStorageHub();
            }
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
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
            }
            importOwnerMedia(uris);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void importOwnerMedia(List<Uri> uris) {
        if (uris.isEmpty()) return;
        if (activeProject == null) activeProject = store.create("Untitled Project");
        final String projectId = activeProject.id;
        final String selectionId = selectedClip == null ? "" : selectedClip.id;
        if (editorStatus != null) editorStatus.setText("Importing " + uris.size() + " file(s)…");
        Toast.makeText(this, "Importing media", Toast.LENGTH_SHORT).show();
        ownerMediaWorker.execute(() -> {
            int imported = 0;
            for (Uri uri : uris) {
                try {
                    ProjectStore.Project target = store.get(projectId);
                    if (target == null) throw new IllegalStateException("The import project was removed");
                    ProjectStore.Asset asset = store.importUri(target, uri);
                    imported++;
                    if (ProxyManager.shouldProxy(asset)) {
                        try { proxyManager.requestOwner(target, asset, "720p"); }
                        catch (Exception error) { ActivityLog.add(this,"system","Preview proxy unavailable",asset.name,"info",null,null,projectId); }
                    }
                    final String assetId = asset.id;
                    ui.post(() -> {
                        if (isFinishing() || isDestroyed() || activeProject == null || !projectId.equals(activeProject.id)) return;
                        activeProject = store.get(projectId);
                        if (activeProject == null) return;
                        String currentSelection = selectedClip == null ? selectionId : selectedClip.id;
                        selectedClip = activeProject.clip(currentSelection);
                        if (selectedClip == null) for (ProjectStore.Clip clip : activeProject.clips) if (assetId.equals(clip.assetId)) { selectedClip = clip; ownerPlayheadMs = clip.startMs; break; }
                        if (selectedClip != null) sourceAssetId = selectedClip.assetId;
                        if ("one_file".equals(permissionMode()) && prefs.getString(KEY_FILE, "").isEmpty()) prefs.edit().putString(KEY_FILE, assetId).apply();
                        syncProtocolState(); requestServiceSync();
                        if ("editor".equals(currentScreen) || "home".equals(currentScreen)) showEditor();
                    });
                } catch (Exception error) {
                    final String message = error.getMessage() == null ? "Could not import this media" : error.getMessage();
                    ui.post(() -> { if (!isFinishing()&&!isDestroyed()) Toast.makeText(this,message,Toast.LENGTH_LONG).show(); });
                }
            }
            final int count = imported;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (editorStatus != null) editorStatus.setText("Imported " + count + " of " + uris.size() + " files · autosaved");
            });
        });
    }

    private void previewSelectedClip() {
        if (!requireSelectedClip()) return;
        timelinePreviewRunning = false;
        programMonitor = true; previewCompare = false;
        ownerPlayheadMs = selectedClip.startMs;
        sourceAssetId = selectedClip.assetId;
        bindEditorPreview(false);
    }

    private void previewTimeline() {
        if (activeProject == null || activeProject.clips.isEmpty() || livePlayer == null) return;
        timelinePreviewRunning = false;
        programMonitor = true; previewCompare = false;
        ownerPlayheadMs = Math.max(0, Math.min(ownerPlayheadMs, activeProject.outputDurationMs()));
        bindEditorPreview(true);
    }

    private void applyTool(String tool) {
        if (!requireSelectedClip()) return;
        final String clipId=selectedClip.id;
        if(isSelectedAudioTrack()&&!java.util.Arrays.asList("Split","Trim","Duplicate","Slow Motion","Volume","Transitions").contains(tool)){Toast.makeText(this,"This track renders audio only. Visual effects belong on a video or title track.",Toast.LENGTH_LONG).show();return;}
        switch(tool){
            case "Split":splitClip();return;
            case "Trim":trimDialog();return;
            case "Text":textDialog();return;
            case "Duplicate":ownerTimelineEdit("duplicate",json("clipId",clipId),clipId);return;
            case "Slow Motion":commitProperty("speed",selectedClip.speed>.6f?.5f:1);return;
            case "Volume":editorPanelTab="Audio";showEditor();return;
            case "Transitions": {
                ArrayList<String> available=new ArrayList<>();
                for(String preset:CreatorCatalog.TRANSITIONS)if(MotionTimeline.supportsTransition(preset)&&(!isSelectedAudioTrack()||java.util.Arrays.asList("none","cut","fade","dip_black","dip_white").contains(preset)))available.add(preset);
                new AlertDialog.Builder(this).setTitle("Clip transition").setItems(available.toArray(new String[0]),(d,index)->ownerEdit("Change transition",p->p.clip(clipId).transition=available.get(index))).setNegativeButton("Cancel",null).show();return;
            }
            case "Motion":presetDialog("Motion","motionPreset",CreatorCatalog.MOTIONS);return;
            case "Effects":presetDialog("Effect","effectPreset",CreatorCatalog.EFFECTS);return;
            case "Colour":presetDialog("Colour","colorPreset",java.util.Arrays.asList("none","cinematic","teal_orange","warm_film","cool_night","noir","golden_hour","matte","high_contrast","soft_portrait"));return;
            case "Fonts":
                new AlertDialog.Builder(this).setTitle("Title font").setItems(CreatorCatalog.FONTS.toArray(new String[0]),(d,index)->ownerEdit("Change title font",p->p.clip(clipId).effects.put("fontFamily",CreatorCatalog.FONTS.get(index)))).setNegativeButton("Cancel",null).show();return;
            case "Green Screen":ownerEdit("Toggle green screen",p->{JSONObject fx=p.clip(clipId).effects;fx.put("chromaKey",!fx.optBoolean("chromaKey"));fx.put("chromaColor","#00FF00");fx.put("chromaTolerance",.18);fx.put("spillSuppression",.35);});return;
            case "Mask":
                String[] shapes={"none","rect","circle","ellipse","rounded_rect"};
                new AlertDialog.Builder(this).setTitle("Clip mask").setItems(shapes,(d,index)->ownerEdit("Change mask",p->{p.clip(clipId).effects.put("mask",shapes[index]);p.clip(clipId).effects.put("maskFeather",.08);})).setNegativeButton("Cancel",null).show();return;
            case "Reframe":case "Crop":cropDialog();return;
            case "Blur":commitProperty("blur",selectedClip.effects.optDouble("blur",0)>.1?0:5);return;
            case "Glow":ownerEdit("Add soft glow",p->{p.clip(clipId).effects.put("effectPreset","soft_glow");p.clip(clipId).effects.put("blur",1.6);});return;
            case "Shake":ownerEdit("Add impact shake",p->p.clip(clipId).effects.put("motionPreset","impact_shake"));return;
            default:Toast.makeText(this,tool+" needs an additional editor engine module",Toast.LENGTH_LONG).show();
        }
    }

    private void presetDialog(String label,String property,List<String> catalog) {
        ArrayList<String> supported=new ArrayList<>();
        for(String preset:catalog)if("motionPreset".equals(property)?MotionTimeline.supportsMotion(preset):NativeVideoEffects.supportsPreset(preset))supported.add(preset);
        final String clipId=selectedClip.id;
        new AlertDialog.Builder(this).setTitle(label).setItems(supported.toArray(new String[0]),(d,index)->ownerEdit("Set "+label,p->{
            JSONObject fx=p.clip(clipId).effects;
            fx.put(property,supported.get(index));
            if("colorPreset".equals(property))fx.remove("effectPreset");
        })).setNegativeButton("Cancel",null).show();
    }

    private void cropDialog() {
        if(!requireSelectedClip())return;
        final String clipId=selectedClip.id;
        JSONObject crop=selectedClip.effects.optJSONObject("crop");
        LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);
        String[] labels={"Left","Right","Bottom","Top"};String[] keys={"left","right","bottom","top"};double[] defaults={-1,1,-1,1};
        EditText[] inputs=new EditText[4];
        for(int i=0;i<4;i++){box.addView(body(labels[i]+" · normalized −1 to 1"));inputs[i]=numberInput((float)(crop==null?defaults[i]:crop.optDouble(keys[i],defaults[i])));inputs[i].setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);box.addView(inputs[i]);}
        new AlertDialog.Builder(this).setTitle("Crop").setView(box).setPositiveButton("Apply",(d,w)->{
            try{
                double[] values=new double[4];for(int i=0;i<4;i++){values[i]=Double.parseDouble(inputs[i].getText().toString());if(!Double.isFinite(values[i])||values[i]<-1||values[i]>1)throw new IllegalArgumentException();}
                if(values[0]>=values[1]||values[2]>=values[3])throw new IllegalArgumentException();
                ownerEdit("Crop clip",p->{JSONObject fx=p.clip(clipId).effects;fx.put("crop",json("left",values[0],"right",values[1],"bottom",values[2],"top",values[3]));fx.remove("reframe");});
            }catch(Exception error){Toast.makeText(this,"Crop needs a positive area within −1 to 1",Toast.LENGTH_SHORT).show();}
        }).setNeutralButton("Reset",(d,w)->ownerEdit("Reset crop",p->{p.clip(clipId).effects.remove("crop");p.clip(clipId).effects.remove("reframe");})).setNegativeButton("Cancel",null).show();
    }

    private void splitClip() {
        if (!requireSelectedClip()) return;
        long at = selectedClip.startMs + clipOutputPosition();
        if (at <= selectedClip.startMs + 33 || at >= selectedClip.endMs() - 33) {
            Toast.makeText(this, "Move the playhead inside this clip to split", Toast.LENGTH_SHORT).show();
            return;
        }
        ownerTimelineEdit("split", json("clipId", selectedClip.id, "atMs", at), selectedClip.id);
    }

    private void trimDialog() {
        if (!requireSelectedClip()) return;
        final String clipId = selectedClip.id;
        LinearLayout wrap = column(); wrap.setPadding(dp(22), 0, dp(22), 0);
        EditText start = numberInput(selectedClip.inMs / 1000f);
        EditText end = numberInput(selectedClip.outMs / 1000f);
        wrap.addView(body("Source in · seconds"));wrap.addView(start);
        wrap.addView(body("Source out · seconds"));wrap.addView(end);
        new AlertDialog.Builder(this).setTitle("Trim clip").setView(wrap).setPositiveButton("Apply", (d,w) -> {
            try {
                long in = Math.round(Double.parseDouble(start.getText().toString()) * 1000);
                long out = Math.round(Double.parseDouble(end.getText().toString()) * 1000);
                ownerTimelineEdit("trim",json("clipId",clipId,"inMs",in,"outMs",out,"ripple",true),clipId);
            } catch (Exception error) { Toast.makeText(this,"Enter a valid source range",Toast.LENGTH_SHORT).show(); }
        }).setNegativeButton("Cancel",null).show();
    }

    private void textDialog() {
        if (!requireSelectedClip()) return;
        if(isSelectedAudioTrack()){Toast.makeText(this,"Add a standalone title with ＋ Text; this track renders audio only.",Toast.LENGTH_LONG).show();return;}
        titleEditorDialog(false);
    }

    private Spinner titleSpinner(List<String> values, String selected) {
        Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));
        int index=values.indexOf(selected);if(index>=0)spinner.setSelection(index);return spinner;
    }

    private void titleEditorDialog(boolean create) {
        if(activeProject==null)activeProject=store.create("Untitled Project");
        if(!create&&!requireSelectedClip())return;
        if(create&&ownerTitlePending){Toast.makeText(this,"A title is being added",Toast.LENGTH_SHORT).show();return;}
        final String projectId=activeProject.id;final long revision=activeProject.revision;final long startMs=ownerPlayheadMs;
        final String clipId=create?"":selectedClip.id;
        JSONObject current=create?new JSONObject():selectedClip.effects;
        ScrollView scroll=baseScroll();LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));scroll.addView(box);
        EditText input=new EditText(this);input.setText(create?"":selectedClip.title);input.setHint("Title or subtitle text");input.setTextColor(C_TEXT);input.setHintTextColor(C_MUTED);input.setMinLines(2);input.setMaxLines(5);box.addView(input);
        Spinner font=titleSpinner(CreatorCatalog.FONTS,current.optString("fontFamily","sans-serif-medium"));
        ArrayList<String> animations=new ArrayList<>();for(String name:CreatorCatalog.TEXT_ANIMATIONS)if(NativeTextOverlay.supportsAnimation(name))animations.add(name);
        Spinner animation=titleSpinner(animations,current.optString("textAnimation","none"));
        EditText size=numberInput((float)current.optDouble("textSize",.06));
        EditText position=numberInput((float)current.optDouble("textY",create?.5:.8));
        EditText color=new EditText(this);color.setSingleLine();color.setText(current.optString("textColor","#FFFFFF"));
        EditText duration=numberInput(3);
        box.addView(body("Font"));box.addView(font);box.addView(body("Entrance animation"));box.addView(animation);
        box.addView(body("Text size · fraction of canvas width (0.02–0.15)"));box.addView(size);
        box.addView(body("Vertical position · top 0.05 to bottom 0.95"));box.addView(position);
        box.addView(body("Text color · #RRGGBB or #AARRGGBB"));box.addView(color);
        if(create){box.addView(body("Duration · seconds"));box.addView(duration);box.addView(body("Starts at "+timecode(startMs)+" on an available title track."));}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(create?"Add standalone title":"Edit clip title").setView(scroll).setPositiveButton(create?"Add title":"Apply",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String text=input.getText().toString().trim();if(text.length()>2000||(create&&text.isEmpty()))throw new IllegalArgumentException("Enter 1 to 2000 title characters");
                double textSize=Double.parseDouble(size.getText().toString());double textY=Double.parseDouble(position.getText().toString());
                if(!Double.isFinite(textSize)||textSize<.02||textSize>.15||!Double.isFinite(textY)||textY<.05||textY>.95)throw new IllegalArgumentException("Enter a valid text size and vertical position");
                String textColor=color.getText().toString().trim();Color.parseColor(textColor);
                JSONObject style=json("fontFamily",String.valueOf(font.getSelectedItem()),"textAnimation",String.valueOf(animation.getSelectedItem()),"textSize",textSize,"textY",textY,"textColor",textColor);
                if(create){
                    long durationMs=Math.round(Double.parseDouble(duration.getText().toString())*1000);if(durationMs<100||durationMs>3600000)throw new IllegalArgumentException("Title duration must be 0.1 to 3600 seconds");
                    ownerTitlePending=true;if(editorStatus!=null)editorStatus.setText("Adding editable title…");dialog.dismiss();
                    ownerMediaWorker.execute(()->{
                        try {
                            EditorTextFactory.Result result=EditorTextFactory.create(this,store,projectId,revision,startMs,durationMs,text,style);
                            ui.post(()->{ownerTitlePending=false;if(isFinishing()||isDestroyed())return;if(activeProject!=null&&projectId.equals(activeProject.id)){activeProject=store.get(projectId);selectedClip=activeProject.clip(result.clipId);ownerPlayheadMs=startMs;programMonitor=true;previewCompare=false;previewResultUri="";previewResultId="";editorPanelTab="Inspector";if(selectedClip!=null)sourceAssetId=selectedClip.assetId;syncProtocolState();requestServiceSync();showEditor();}else Toast.makeText(this,"Title added to the original project",Toast.LENGTH_SHORT).show();});
                        }catch(Exception error){ui.post(()->{ownerTitlePending=false;if(!isFinishing()&&!isDestroyed())ownerEditError(error);});}
                    });
                }else{
                    dialog.dismiss();ownerEdit("Change title",p->{ProjectStore.Clip clip=p.clip(clipId);clip.title=text;for(String key:new String[]{"fontFamily","textAnimation","textSize","textY","textColor"})clip.effects.put(key,style.get(key));});
                }
            }catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Enter valid title settings":error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
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
        // This Activity's protocol object supplies pairing/state only. The
        // foreground Native Agent owns command polling and durable execution.
        JSONObject result = json("ok", false,
                "error", "Commands execute through the foreground Native Agent service");
        protocol.complete(command, result, "failed");
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

