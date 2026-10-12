package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;

/** Real native vector/cel studio. Touch and numeric changes call the same facade as ChatGPT. */
public final class SketchStudioActivity extends Activity implements SketchCanvasView.Listener {
    private JSONObject document;
    private String sketchId;
    private SketchCanvasView canvas;
    private TextView status;
    private SeekBar frames;
    private Spinner layers;
    private final ArrayList<String> layerIds = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating, playing;
    private long playStarted;
    private int playFirst;
    private String brushColor = "#FF161C2B";
    private float brushWidth = .008f;
    private boolean brushFill;
    private Button playButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(); root.setPadding(dp(8), dp(8), dp(8), dp(8)); root.setBackgroundColor(0xfff2f4f8); setContentView(root);
        status = new TextView(this); status.setTextSize(16); root.addView(status);
        LinearLayout projects = row(root);
        button(projects, "New", this::newDocument); button(projects, "Open", this::openDocument);
        button(projects, "Undo", () -> history(false)); button(projects, "Redo", () -> history(true)); button(projects, "Export video", this::export);
        LinearLayout tools = row(root); Spinner tool = new Spinner(this);
        String[] names = {"Pen", "Eraser", "Line", "Rectangle", "Ellipse", "Polygon", "Select / move"};
        String[] values = {"stroke", "eraser", "line", "rect", "ellipse", "polygon", "select"};
        tool.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names)); tools.addView(tool);
        tool.setOnItemSelectedListener(new SimpleSelection() { @Override public void selected(int position) { canvas.setTool(values[position]); } });
        button(tools, "Brush", this::brushDialog); button(tools, "Finish polygon", () -> canvas.finishPolygon());
        canvas = new SketchCanvasView(this); canvas.setListener(this); root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        frames = new SeekBar(this); root.addView(frames); frames.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) { stopPlayback(); canvas.setFrame(progress); updateStatus(); } }
            public void onStartTrackingTouch(SeekBar bar) { stopPlayback(); }
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout timing = row(root);
        button(timing, "◀", () -> setFrame(canvas.getFrame() - 1)); playButton = button(timing, "Play", this::togglePlayback);
        button(timing, "▶", () -> setFrame(canvas.getFrame() + 1));
        CheckBox onion = new CheckBox(this); onion.setText("Onion skin"); onion.setChecked(true); onion.setOnCheckedChangeListener((v, checked) -> canvas.setOnion(checked)); timing.addView(onion);
        button(timing, "Timing / canvas", this::timingDialog);
        LinearLayout layerControls = row(root); layers = new Spinner(this); layerControls.addView(layers);
        layers.setOnItemSelectedListener(new SimpleSelection() { @Override public void selected(int position) {
            if (!updating && position < layerIds.size()) { canvas.setLayerId(layerIds.get(position)); updateStatus(); }
        } });
        button(layerControls, "+ Layer", this::addLayer); button(layerControls, "Layer settings", this::layerDialog);
        button(layerControls, "Up", () -> reorderLayer(1)); button(layerControls, "Down", () -> reorderLayer(-1));
        LinearLayout edits = row(root);
        button(edits, "Transform / key", this::transformDialog); button(edits, "Exposure", this::exposureDialog);
        button(edits, "Duplicate cel", this::duplicateCel); button(edits, "Delete object", this::deleteObject); button(edits, "Reference image", this::referenceDialog);
        try {
            sketchId = state == null ? getIntent().getStringExtra("sketchId") : state.getString("sketchId");
            if (sketchId == null) sketchId = getPreferences(MODE_PRIVATE).getString("lastSketchId", null);
            if (sketchId == null) {
                try (SketchProjectStore store = new SketchProjectStore(this)) { JSONArray list = store.list(); if (list.length() > 0) sketchId = list.getJSONObject(0).getString("sketchId"); }
            }
            if (sketchId == null) createDocument("Untitled sketch"); else reload();
            if (state != null) setFrame(state.getInt("frame", 0));
        } catch (Exception e) { onMessage(e.getMessage()); openDocument(); }
    }

    private void reload() throws Exception {
        try (SketchProjectStore store = new SketchProjectStore(this)) { document = store.get(sketchId); }
        canvas.setDocument(document); updating = true; layerIds.clear(); ArrayList<String> names = new ArrayList<>(); JSONArray items = document.getJSONArray("layers");
        for (int i = 0; i < items.length(); i++) { JSONObject layer = items.getJSONObject(i); layerIds.add(layer.getString("id")); names.add(layer.getString("name") + (layer.getBoolean("locked") ? " 🔒" : "") + (!layer.getBoolean("visible") ? " (hidden)" : "")); }
        layers.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        layers.setSelection(Math.max(0, layerIds.indexOf(canvas.getLayerId()))); frames.setMax(document.getInt("frameCount") - 1); frames.setProgress(canvas.getFrame()); updating = false;
        getPreferences(MODE_PRIVATE).edit().putString("lastSketchId", sketchId).apply(); updateStatus();
    }
    @Override public void onOperation(JSONObject operation) { apply(new JSONArray().put(operation)); }
    private void apply(JSONArray operations) {
        if (document == null) return; stopPlayback();
        try {
            SketchEditOps.execute(this, "sketch_edit", request().put("operations", operations)); reload();
        } catch (Exception e) { onMessage(e.getMessage()); try { reload(); } catch (Exception ignored) {} }
    }
    private JSONObject request() throws Exception { return new JSONObject().put("sketchId", sketchId).put("operationId", UUID.randomUUID().toString()).put("expectedRevision", document.getLong("revision")); }
    @Override public void onSelection(String id) { updateStatus(); }
    @Override public void onMessage(String message) { if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void updateStatus() {
        try { if (document != null) status.setText(document.getString("name") + " · r" + document.getLong("revision") + " · frame " + (canvas.getFrame() + 1) + "/" + document.getInt("frameCount") + " · " + document.getInt("fps") + " FPS" + (canvas.getSelectedId() == null ? "" : " · selected")); }
        catch (Exception ignored) {}
    }
    private void history(boolean redo) {
        stopPlayback(); try { SketchEditOps.execute(this, redo ? "sketch_redo" : "sketch_undo", request()); reload(); } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void createDocument(String name) throws Exception {
        sketchId = UUID.randomUUID().toString(); SketchEditOps.execute(this, "sketch_create", new JSONObject().put("sketchId", sketchId).put("operationId", UUID.randomUUID().toString()).put("name", name)); reload();
    }
    private void newDocument() {
        EditText name = input("Sketch name", "Untitled sketch", false); new AlertDialog.Builder(this).setTitle("New sketch").setView(name).setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> { try { createDocument(name.getText().toString().trim()); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
    }
    private void openDocument() {
        stopPlayback(); try (SketchProjectStore store = new SketchProjectStore(this)) {
            JSONArray sketches = store.list(); String[] names = new String[sketches.length()]; for (int i = 0; i < names.length; i++) names[i] = sketches.getJSONObject(i).getString("name");
            if (names.length == 0) { newDocument(); return; }
            new AlertDialog.Builder(this).setTitle("Open sketch").setItems(names, (d, index) -> { try { sketchId = sketches.getJSONObject(index).getString("sketchId"); reload(); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void export() {
        stopPlayback(); try {
            JSONObject args = request().put("aspect", "original").put("quality", "720p");
            Intent intent = new Intent(this, ControlService.class).setAction("com.rezoxnemesis.videostudio.LOCAL_SKETCH_EXPORT").putExtra("requestJson", args.toString());
            startForegroundService(intent); onMessage("Native sketch export started. Its output is saved in a separate project.");
        } catch (Exception e) { onMessage(e.getMessage()); }
    }

    private void brushDialog() {
        LinearLayout form = column(); EditText color = input("Color #AARRGGBB", brushColor, false), width = input("Stroke width in pixels", String.valueOf(brushWidth * Math.min(document.optInt("width"), document.optInt("height"))), true);
        CheckBox fill = new CheckBox(this); fill.setText("Fill shapes"); fill.setChecked(brushFill); form.addView(color); form.addView(width); form.addView(fill);
        new AlertDialog.Builder(this).setTitle("Brush / shape style").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d,w) -> {
            try { String next = color.getText().toString(); SketchDocument.color(next); float size = Float.parseFloat(width.getText().toString()) / Math.min(document.getInt("width"), document.getInt("height"));
                if (!Float.isFinite(size) || size < .0005 || size > .2) throw SketchDocument.bad("Stroke width outside .05%..20% of short canvas dimension");
                brushColor = next; brushWidth = size; brushFill = fill.isChecked(); canvas.setBrush(brushColor, brushWidth, brushFill);
                if (canvas.getSelectedId() != null) onOperation(new JSONObject().put("type", "update_object").put("objectId", canvas.getSelectedId()).put("patch", new JSONObject().put("color", next).put("width", size).put("fill", brushFill)));
            } catch (Exception e) { onMessage(e.getMessage()); }
        }).show();
    }
    private void timingDialog() {
        LinearLayout form = column(); EditText fps = input("FPS: 24, 30 or 60", String.valueOf(document.optInt("fps")), true), count = input("Frame count (1..1200)", String.valueOf(document.optInt("frameCount")), true);
        EditText width = input("Canvas width (even, 256..1280)", String.valueOf(document.optInt("width")), true), height = input("Canvas height (even, 256..1280)", String.valueOf(document.optInt("height")), true), background = input("Background #AARRGGBB", document.optString("background"), false);
        form.addView(fps); form.addView(count); form.addView(width); form.addView(height); form.addView(background);
        new AlertDialog.Builder(this).setTitle("Timing and canvas").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d,w) -> {
            try { apply(new JSONArray().put(new JSONObject().put("type", "set_timing").put("fps", Integer.parseInt(fps.getText().toString())).put("frameCount", Integer.parseInt(count.getText().toString())))
                    .put(new JSONObject().put("type", "set_canvas").put("width", Integer.parseInt(width.getText().toString())).put("height", Integer.parseInt(height.getText().toString())).put("background", background.getText().toString()))); }
            catch (Exception e) { onMessage(e.getMessage()); }
        }).show();
    }
    private void addLayer() { try { onOperation(new JSONObject().put("type", "add_layer").put("layer", new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Layer " + (layerIds.size() + 1)))); } catch (Exception e) { onMessage(e.getMessage()); } }
    private void layerDialog() {
        try {
            JSONObject layer = SketchDocument.layer(document, canvas.getLayerId()); LinearLayout form = column(); EditText name = input("Layer name", layer.getString("name"), false), opacity = input("Opacity 0..1", String.valueOf(layer.getDouble("opacity")), true);
            CheckBox visible = new CheckBox(this); visible.setText("Visible"); visible.setChecked(layer.getBoolean("visible")); CheckBox locked = new CheckBox(this); locked.setText("Locked"); locked.setChecked(layer.getBoolean("locked"));
            form.addView(name); form.addView(opacity); form.addView(visible); form.addView(locked);
            new AlertDialog.Builder(this).setTitle("Layer settings").setView(form).setNegativeButton("Cancel", null).setNeutralButton("Delete layer", (d,w) -> { try { onOperation(new JSONObject().put("type", "delete_layer").put("layerId", canvas.getLayerId())); } catch (Exception e) { onMessage(e.getMessage()); } })
                    .setPositiveButton("Apply", (d,w) -> { try { onOperation(new JSONObject().put("type", "update_layer").put("layerId", canvas.getLayerId()).put("patch", new JSONObject().put("name", name.getText().toString()).put("opacity", Double.parseDouble(opacity.getText().toString())).put("visible", visible.isChecked()).put("locked", locked.isChecked()))); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void reorderLayer(int direction) { try { int index = layerIds.indexOf(canvas.getLayerId()), target = Math.max(0, Math.min(layerIds.size() - 1, index + direction)); onOperation(new JSONObject().put("type", "reorder_layer").put("layerId", canvas.getLayerId()).put("index", target)); } catch (Exception e) { onMessage(e.getMessage()); } }

    private void transformDialog() {
        try {
            String objectId = canvas.getSelectedId(); JSONObject item = objectId == null ? SketchDocument.layer(document, canvas.getLayerId()) : SketchDocument.object(document, objectId);
            JSONObject current = SketchDocument.transformAt(item, canvas.getFrame()); LinearLayout form = column();
            String[] keys = {"x","y","scaleX","scaleY","rotation","opacity"}; String[] labels = {"X translation in pixels","Y translation in pixels","X scale","Y scale","Rotation in degrees","Opacity 0..1"}; EditText[] values = new EditText[keys.length];
            for (int i = 0; i < keys.length; i++) { double value = current.getDouble(keys[i]); if (i == 0) value *= document.getInt("width"); if (i == 1) value *= document.getInt("height"); values[i] = input(labels[i], String.valueOf(value), true); form.addView(values[i]); }
            CheckBox key = new CheckBox(this); key.setText("Set a transform key at current frame"); key.setChecked(item.getJSONArray("keys").length() > 0); form.addView(key);
            CheckBox hold = new CheckBox(this); hold.setText("Hold until next key"); form.addView(hold);
            new AlertDialog.Builder(this).setTitle(objectId == null ? "Layer transform" : "Object transform").setView(form).setNegativeButton("Cancel", null)
                    .setNeutralButton("Remove key", (d,w) -> { try { onOperation(targetOperation("set_keyframe", objectId).put("frame", canvas.getFrame()).put("remove", true)); } catch (Exception e) { onMessage(e.getMessage()); } })
                    .setPositiveButton("Apply", (d,w) -> { try { JSONObject transform = new JSONObject(); for (int i = 0; i < keys.length; i++) { double value = Double.parseDouble(values[i].getText().toString()); if (i == 0) value /= document.getInt("width"); if (i == 1) value /= document.getInt("height"); transform.put(keys[i], value); }
                        JSONObject operation = targetOperation(key.isChecked() ? "set_keyframe" : "set_transform", objectId).put("transform", transform);
                        if (key.isChecked()) operation.put("frame", canvas.getFrame()).put("interpolation", hold.isChecked() ? "hold" : "linear"); onOperation(operation);
                    } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private JSONObject targetOperation(String type, String objectId) throws Exception { return new JSONObject().put("type", type).put(objectId == null ? "layerId" : "objectId", objectId == null ? canvas.getLayerId() : objectId); }
    private void exposureDialog() {
        try {
            if (canvas.getSelectedId() == null) throw SketchDocument.bad("Select an object first"); JSONObject object = SketchDocument.object(document, canvas.getSelectedId()); LinearLayout form = column();
            EditText start = input("First frame (1-based)", String.valueOf(object.getInt("frame") + 1), true), exposure = input("Exposure in frames", String.valueOf(object.getInt("exposure")), true); form.addView(start); form.addView(exposure);
            new AlertDialog.Builder(this).setTitle("Cel exposure").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d,w) -> { try { onOperation(new JSONObject().put("type", "set_exposure").put("objectId", canvas.getSelectedId()).put("frame", Integer.parseInt(start.getText().toString()) - 1).put("exposure", Integer.parseInt(exposure.getText().toString()))); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void duplicateCel() {
        try {
            int sourceFrame = canvas.getFrame(), target = sourceFrame + 1; JSONArray operations = new JSONArray();
            if (target >= document.getInt("frameCount")) operations.put(new JSONObject().put("type", "set_timing").put("frameCount", target + 1));
            JSONArray objects = SketchDocument.layer(document, canvas.getLayerId()).getJSONArray("objects");
            for (int i = 0; i < objects.length(); i++) {
                JSONObject original = objects.getJSONObject(i); if (!SketchFrameRenderer.exposed(original, sourceFrame)) continue;
                if (operations.length() + 2 > 64) throw SketchDocument.bad("Duplicate cel supports up to 31 objects per atomic batch; use ChatGPT batches for larger cels");
                JSONObject copy = SketchDocument.copy(original).put("id", UUID.randomUUID().toString()).put("frame", target).put("exposure", 1)
                        .put("transform", SketchDocument.transformAt(original, sourceFrame)).put("keys", new JSONArray());
                operations.put(new JSONObject().put("type", "set_exposure").put("objectId", original.getString("id")).put("frame", original.getInt("frame")).put("exposure", sourceFrame - original.getInt("frame") + 1));
                operations.put(new JSONObject().put("type", "add_object").put("layerId", canvas.getLayerId()).put("object", copy));
            }
            if (operations.length() == 0) throw SketchDocument.bad("Current layer has no exposed cel objects"); apply(operations); setFrame(target);
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void deleteObject() { try { if (canvas.getSelectedId() == null) throw SketchDocument.bad("Select an object first"); onOperation(new JSONObject().put("type", "delete_object").put("objectId", canvas.getSelectedId())); } catch (Exception e) { onMessage(e.getMessage()); } }
    private void referenceDialog() {
        try {
            ProjectStore projects = new ProjectStore(this); ArrayList<ProjectStore.Project> owners = new ArrayList<>(); ArrayList<ProjectStore.Asset> assets = new ArrayList<>(); ArrayList<String> labels = new ArrayList<>();
            for (ProjectStore.Project project : projects.list()) for (ProjectStore.Asset asset : project.assets) if (asset.mime != null && asset.mime.startsWith("image/")) { owners.add(project); assets.add(asset); labels.add(project.name + " / " + asset.name); }
            if (assets.isEmpty()) throw SketchDocument.bad("Import an image into a VideoStudio project first");
            new AlertDialog.Builder(this).setTitle("Copy a project reference image").setItems(labels.toArray(new String[0]), (d,index) -> {
                try { ProjectStore.Project project = owners.get(index); JSONObject args = request().put("projectId", project.id).put("sourceProjectUpdatedAt", project.updatedAt).put("assetId", assets.get(index).id)
                        .put("layerId", canvas.getLayerId()).put("objectId", UUID.randomUUID().toString()).put("frame", canvas.getFrame()).put("exposure", document.getInt("frameCount") - canvas.getFrame());
                    SketchEditOps.execute(this, "sketch_import_reference", args); reload();
                } catch (Exception e) { onMessage(e.getMessage()); }
            }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }

    private void setFrame(int index) { stopPlayback(); if (document == null) return; int next = Math.max(0, Math.min(document.optInt("frameCount") - 1, index)); canvas.setFrame(next); frames.setProgress(next); updateStatus(); }
    private void togglePlayback() { if (playing) { stopPlayback(); return; } if (document == null) return; playing = true; playStarted = SystemClock.uptimeMillis(); playFirst = canvas.getFrame(); playButton.setText("Pause"); handler.post(playTick); }
    private final Runnable playTick = new Runnable() { @Override public void run() {
        if (!playing || document == null) return; int fps = document.optInt("fps"), count = document.optInt("frameCount"); long elapsed = SystemClock.uptimeMillis() - playStarted;
        long index = elapsed * fps / 1000; int frame = (int) ((playFirst + index) % count); canvas.setFrame(frame); frames.setProgress(frame); updateStatus();
        long nextAt = NativeFrameClock.presentationTimeMs(index + 1, fps); handler.postDelayed(this, Math.max(1, nextAt - elapsed));
    } };
    private void stopPlayback() { playing = false; handler.removeCallbacks(playTick); if (playButton != null) playButton.setText("Play"); }
    @Override protected void onPause() { stopPlayback(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (sketchId != null && canvas != null) try { reload(); } catch (Exception e) { onMessage(e.getMessage()); } }
    @Override protected void onDestroy() { stopPlayback(); if (canvas != null) canvas.dispose(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("sketchId", sketchId); state.putInt("frame", canvas.getFrame()); super.onSaveInstanceState(state); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row(LinearLayout root) { HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); scroll.addView(layout); root.addView(scroll); return layout; }
    private Button button(LinearLayout row, String title, Runnable action) { Button button = new Button(this); button.setText(title); button.setTextSize(12); button.setOnClickListener(v -> action.run()); row.addView(button); return button; }
    private EditText input(String hint, String value, boolean number) { EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setSingleLine(); if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); return input; }
    private int dp(int pixels) { return Math.round(pixels * getResources().getDisplayMetrics().density); }
    private abstract static class SimpleSelection implements android.widget.AdapterView.OnItemSelectedListener {
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { selected(position); }
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        public abstract void selected(int position);
    }
}
