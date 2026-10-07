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
import android.media.PlaybackParams;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements AppProtocol.Callback {
    private static final int PICK_MEDIA = 1201;
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

    private ProjectStore store;
    private JobManager jobs;
    private AppProtocol protocol;
    private NativeRenderEngine renderEngine;
    private NativeRenderEngine.Handle activeRenderHandle;
    private PromptVideoEngine promptVideoEngine;
    private NativeMediaAnalyzer mediaAnalyzer;
    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView connectionPill;
    private ProjectStore.Project activeProject;
    private ProjectStore.Clip selectedClip;
    private VideoView preview;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable clipStopper;
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
        store = new ProjectStore(this);
        jobs = new JobManager(this);
        renderEngine = new NativeRenderEngine(this);
        promptVideoEngine = new PromptVideoEngine(this);
        mediaAnalyzer = new NativeMediaAnalyzer(this);
        activeProject = store.active();
        protocol = new AppProtocol(this, this);
        syncProtocolState();

        setContentView(buildShell());
        showHome();
        protocol.start();
    }

    @Override
    protected void onDestroy() {
        timelinePreviewRunning = false;
        if (clipStopper != null) ui.removeCallbacks(clipStopper);
        if (preview != null) preview.stopPlayback();
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
        nav.addView(navButton("+", "New", this::createProjectDialog), weight());
        nav.addView(navButton("✦", "AI Tools", () -> showTools()), weight());
        nav.addView(navButton("⚙", "Control", () -> showControl()), weight());
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        return root;
    }

    private void setScreen(View view, String name) {
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
        hero.addView(body("Native v1.1 Creator Engine • prompt-to-video • Media3 export • private autonomous App MCP"));
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
        connectionPill = body("Private App MCP • checking connection…");
        copy.addView(connectionPill);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = title("›", 34);
        row.addView(arrow);
        connect.addView(row);
        connect.setOnClickListener(v -> sharePairing());
        box.addView(connect, margins(-1, -2, 0, dp(16), 0, 0));

        box.addView(section("Quick Actions"));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(actionTile("+", "New Project", C_PURPLE, this::createProjectDialog), weightWithMargin());
        quick.addView(actionTile("▧", "Import Media", C_BLUE, this::pickMedia), weightWithMargin());
        quick.addView(actionTile("✦", "AI Edit", C_MAGENTA, () -> showTools()), weightWithMargin());
        quick.addView(actionTile("▶", "Prompt Video", C_CYAN, this::promptVideoDialog), weightWithMargin());
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
        foundation.addView(body("Media3 GPU export • crash-recovery checkpoints • thermal/RAM governor • private ChatGPT handoff • native frame analysis • no gallery browsing permission."));
        box.addView(foundation);

        setScreen(scroll, "home");
    }

    private void showEditor() {
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
            try { queueNativeExport(activeProject, "9:16", "1080p", "VideoStudio_" + System.currentTimeMillis() + ".mp4"); }
            catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
        });
        top.addView(exportButton);
        box.addView(top, margins(-1, -2, 0, dp(10), 0, 0));

        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackground(rounded(Color.BLACK, Color.rgb(37, 51, 83), dp(18)));
        preview = new VideoView(this);
        viewer.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        TextView hint = body(activeProject.assets.isEmpty() ? "Import media to begin" : "Select a clip below");
        hint.setGravity(Gravity.CENTER);
        viewer.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        box.addView(viewer);

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

        String[][] groups = {
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
        mcp.addView(body("Direct native control: analyse, import, edit, batch actions, prompt-video, export and inspect project state. Gallery enumeration is permanently excluded."));
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
        box.addView(body("Permissions are enforced locally on the phone as well as by the App MCP relay."));
        box.addView(section("Access Mode"));
        box.addView(permissionCard("one_file", "Allow one file", "ChatGPT can edit only the currently authorised media file."));
        box.addView(permissionCard("all_tools", "Allow all tools", "All editing and analysis tools on imported project media. No remote file import or project deletion."));
        box.addView(permissionCard("everything", "Allow everything except Gallery", "Full autonomous VideoStudio control: imports you explicitly share, projects, AI planning, editing, export and retries. Gallery listing/browsing stays blocked."));

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

        box.addView(section("Private Connection"));
        LinearLayout privateCard = card(false);
        privateCard.addView(title("Device-owned MCP endpoint", 17));
        privateCard.addView(body("The device credential is encrypted by Android Keystore, commands are leased and checkpointed, reconnect uses backoff, and the app can pause control instantly."));
        Button pause = neonButton(protocol.isControlPaused() ? "Resume ChatGPT Control" : "STOP CHATGPT CONTROL", protocol.isControlPaused() ? C_CYAN : Color.rgb(180, 38, 67));
        pause.setOnClickListener(v -> {
            boolean next = !protocol.isControlPaused();
            protocol.setControlPaused(next);
            if (next) {
                if (activeRenderHandle != null) activeRenderHandle.cancel();
                jobs.cancelAll();
            }
            showControl();
        });
        privateCard.addView(pause, margins(-1, dp(52), dp(10), dp(8), 0, 0));
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
        TextView badge = accent("NATIVE 1.1", C_CYAN);
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
            protocol.registerNow();
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
        protocol.registerNow();
        showEditor();
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
        if (requestCode == PICK_MEDIA && resultCode == RESULT_OK && data != null) {
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
                if (selectedClip == null && !activeProject.clips.isEmpty()) selectedClip = activeProject.clips.get(activeProject.clips.size() - 1);
                if ("one_file".equals(permissionMode()) && prefs.getString(KEY_FILE, "").isEmpty()) {
                    prefs.edit().putString(KEY_FILE, asset.id).apply();
                }
            }
            syncProtocolState();
            protocol.registerNow();
            showEditor();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void previewSelectedClip() {
        if (selectedClip == null || activeProject == null || preview == null) return;
        ProjectStore.Asset asset = activeProject.asset(selectedClip.assetId);
        if (asset == null || !asset.mime.startsWith("video/")) return;
        timelinePreviewRunning = false;
        playClip(selectedClip, null);
    }

    private void previewTimeline() {
        if (activeProject == null || activeProject.clips.isEmpty() || preview == null) return;
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
        ProjectStore.Asset a = activeProject.asset(clip.assetId);
        if (a == null || !a.mime.startsWith("video/")) {
            playTimelineIndex(index + 1);
            return;
        }
        playClip(clip, () -> playTimelineIndex(index + 1));
    }

    private void playClip(ProjectStore.Clip clip, Runnable after) {
        ProjectStore.Asset asset = activeProject.asset(clip.assetId);
        if (asset == null || preview == null) return;
        if (clipStopper != null) ui.removeCallbacks(clipStopper);
        preview.stopPlayback();
        preview.setVideoURI(Uri.parse(asset.uri));
        preview.setOnPreparedListener(mp -> {
            try {
                mp.setPlaybackParams(new PlaybackParams().setSpeed(Math.max(.5f, Math.min(2f, clip.speed))));
            } catch (Exception ignored) {}
            preview.seekTo((int) clip.inMs);
            preview.start();
            clipStopper = new Runnable() {
                @Override public void run() {
                    if (preview == null || !preview.isPlaying()) {
                        if (after != null && timelinePreviewRunning) after.run();
                        return;
                    }
                    if (preview.getCurrentPosition() >= clip.outMs) {
                        preview.pause();
                        if (after != null && timelinePreviewRunning) after.run();
                    } else {
                        ui.postDelayed(this, 80);
                    }
                }
            };
            ui.post(clipStopper);
        });
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
                    String[] transitions = {"fade", "slide", "zoom", "whip", "none"};
                    int at = 0;
                    for (int i = 0; i < transitions.length; i++) if (transitions[i].equals(selectedClip.transition)) at = i;
                    selectedClip.transition = transitions[(at + 1) % transitions.length];
                    break;
                case "Motion":
                    selectedClip.effects.put("motionPreset", "push_in");
                    selectedClip.effects.put("ease", "easeInOut");
                    break;
                case "Effects":
                    String current = selectedClip.effects.optString("effectPreset", "");
                    selectedClip.effects.put("effectPreset", "cinematic_glow".equals(current) ? "film_grain" : "cinematic_glow");
                    break;
                case "Colour":
                    selectedClip.effects.put("colorPreset", "cinematic");
                    selectedClip.effects.put("contrast", 1.06);
                    selectedClip.effects.put("saturation", 1.04);
                    break;
                case "Text":
                    textDialog();
                    return;
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
        long at = preview != null ? preview.getCurrentPosition() : selectedClip.inMs + (selectedClip.outMs - selectedClip.inMs) / 2;
        if (at <= selectedClip.inMs + 250 || at >= selectedClip.outMs - 250) {
            Toast.makeText(this, "Move playback inside the clip before splitting", Toast.LENGTH_SHORT).show();
            return;
        }
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
                        selectedClip.inMs = Math.max(0, a);
                        selectedClip.outMs = b;
                        store.save(activeProject);
                        syncProtocolState();
                        showEditor();
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
                    selectedClip.title = input.getText().toString().trim();
                    store.save(activeProject);
                    syncProtocolState();
                    showEditor();
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
            connectionPill.setText((connected ? "●  " : "○  ") + detail);
            connectionPill.setTextColor(connected ? Color.rgb(74, 255, 172) : C_MUTED);
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
                case "cancel_job":
                    result.put("ok", jobs.cancel(p.optString("jobId")));
                    break;
                case "import_url":
                    result = queueUrlImport(p.optString("url"), p.optString("name", "ChatGPT import"));
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
                    result.put("error", "Native v1 does not implement action: " + action);
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
                selectedClip.speed = (float) Math.max(.5, Math.min(2, settings.optDouble("speed", .5)));
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
                selectedClip.effects.put("effectPreset", settings.optString("preset", "cinematic_glow"));
                break;
            case "color":
                selectedClip.effects.put("colorPreset", settings.optString("preset", "cinematic"));
                break;
            case "reframe":
                selectedClip.effects.put("reframe", settings.optString("preset", "9:16_subject_safe"));
                break;
            case "mask":
                selectedClip.effects.put("mask", settings.optString("shape", "rounded_rect"));
                selectedClip.effects.put("maskFeather", settings.optDouble("feather", .08));
                break;
            case "title":
                selectedClip.title = settings.optString("text", "");
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
            c.setRequestProperty("User-Agent", "VideoStudio-Android/1.0");
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
        if ("ping".equals(action) || "get_state".equals(action)) return true;
        String mode = permissionMode();
        if ("everything".equals(mode)) return true;
        if ("all_tools".equals(mode)) {
            return !"import_url".equals(action) && !"import_chat_file".equals(action) && !"delete_project".equals(action);
        }
        if ("one_file".equals(mode)) {
            String allowed = prefs.getString(KEY_FILE, "");
            if (allowed.isEmpty()) return false;
            if ("apply_tool".equals(action)) {
                int index = parameters.optInt("clipIndex", -1);
                return activeProject != null && index >= 0 && index < activeProject.clips.size()
                        && allowed.equals(activeProject.clips.get(index).assetId);
            }
            if ("preview_project".equals(action)) return true;
            return false;
        }
        return false;
    }

    private JSONObject stateJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("deviceId", protocol.deviceId());
            out.put("appVersion", "1.0.1");
            out.put("nativeApp", true);
            out.put("permissionMode", permissionMode());
            out.put("projects", store.summaries().optJSONArray("projects"));
            if (activeProject != null) {
                out.put("activeProjectId", activeProject.id);
                out.put("activeProjectName", activeProject.name);
                out.put("clipCount", activeProject.clips.size());
                out.put("assetCount", activeProject.assets.size());
                out.put("durationMs", activeProject.outputDurationMs());
            }
            out.put("jobs", jobs.state().optJSONArray("jobs"));
            out.put("workload", jobs.state());
            JSONArray caps = new JSONArray();
            String[] values = {"native-ui","local-projects","media-picker","timeline","trim","split","slow-motion-preview","speed","green-screen-model","transitions-model","motion-model","effects-model","colour-model","masks-model","private-app-mcp","chat-attachment-handoff","url-import","bounded-multitasking","thermal-guard","memory-guard","job-cancel"};
            for (String v : values) caps.put(v);
            out.put("capabilities", caps);
        } catch (Exception ignored) {}
        return out;
    }

    private void syncProtocolState() {
        if (protocol != null) protocol.setLocalState(permissionMode(), store.summaries());
    }

    private String permissionMode() {
        return prefs.getString(KEY_MODE, "all_tools");
    }

    private void refreshCurrent() {
        activeProject = activeProject == null ? store.active() : store.get(activeProject.id);
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
