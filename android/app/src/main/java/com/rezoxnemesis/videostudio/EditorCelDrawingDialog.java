package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native frame drawing with the same vector commands that create the saved PNG. */
@UnstableApi
public final class EditorCelDrawingDialog {
    private interface VectorRetention { void retain() throws Exception; }
    public interface SaveListener {
        /** Clear the retained draft only after the actual project transaction commits. */
        void onSave(JSONObject drawing, int frames, int fpsNumerator, int fpsDenominator,
                    Runnable discardCommittedDraft) throws Exception;
    }
    private static final AtomicBoolean OPEN = new AtomicBoolean();
    private static final Handler DRAFT_CALLBACKS = new Handler(Looper.getMainLooper());
    private static final Exception DRAFT_MEMORY_FAILURE = new IllegalStateException("Free memory and reopen the retained drawing before saving");
    private static final int MAX_DRAFT_BYTES = 140 * 1024;
    private static final String PREFS = "videostudio_animation_drawing_drafts";
    private static final ThreadPoolExecutor DRAFT_IO = new ThreadPoolExecutor(1, 1, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "VideoStudio-frame-drafts");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { DRAFT_IO.allowCoreThreadTimeOut(true); }

    /** At most eight latest snapshots; rapid strokes replace queued snapshots. */
    private static final Object DRAFT_LOCK = new Object();
    private static final LinkedHashMap<String, DraftWrite> PENDING_DRAFTS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, DraftWrite> PENDING_DRAFT_CLEARS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> MEMORY_DRAFTS = new LinkedHashMap<>();
    private static final ThreadPoolExecutor DRAFT_WRITER = new ThreadPoolExecutor(1, 1, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "VideoStudio-frame-retention");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static boolean draftDrainRunning;
    static { DRAFT_WRITER.allowCoreThreadTimeOut(true); }

    private static final class DraftWrite {
        final SharedPreferences preferences;
        final String key, encoded;
        Runnable committed;
        java.util.function.Consumer<Exception> failed;
        DraftWrite(SharedPreferences preferences, String key, String encoded, Runnable committed,
                   java.util.function.Consumer<Exception> failed) {
            this.preferences = preferences; this.key = key; this.encoded = encoded;
            this.committed = committed; this.failed = failed;
        }
    }

    private static void retainDraft(DraftWrite write) {
        synchronized (DRAFT_LOCK) {
            if (!MEMORY_DRAFTS.containsKey(write.key) && MEMORY_DRAFTS.size() >= 8)
                throw new IllegalStateException("Draft retention is full; save an existing retained frame first");
            MEMORY_DRAFTS.put(write.key, write.encoded);
            DraftWrite previous = PENDING_DRAFTS.get(write.key);
            if (previous != null && previous.committed != null && write.committed == null) {
                write.committed = previous.committed; write.failed = previous.failed;
            }
            PENDING_DRAFTS.put(write.key, write);
            startDraftDrain();
        }
    }

    /** Call only under DRAFT_LOCK. Writes and exact clears share one writer. */
    private static void startDraftDrain() {
        if (draftDrainRunning) return;
        draftDrainRunning = true;
        try { DRAFT_WRITER.execute(EditorCelDrawingDialog::drainDrafts); }
        catch (RuntimeException | OutOfMemoryError error) { draftDrainRunning = false; throw error; }
    }

    private static void clearCommittedDraft(SharedPreferences preferences, String key, String encoded) {
        synchronized (DRAFT_LOCK) {
            if (!encoded.equals(MEMORY_DRAFTS.get(key))) return;
            if (!PENDING_DRAFT_CLEARS.containsKey(key) && PENDING_DRAFT_CLEARS.size() >= 8) return;
            PENDING_DRAFT_CLEARS.put(key, new DraftWrite(preferences, key, encoded, null, null));
            startDraftDrain();
        }
    }

    private static void drainDrafts() {
        boolean interrupted = false;
        try { drainDraftWork(); }
        catch (RuntimeException | OutOfMemoryError error) { interrupted = true; /* Latest vector strings remain in memory. */ }
        finally {
            synchronized (DRAFT_LOCK) {
                draftDrainRunning = false;
                if (!interrupted && (!PENDING_DRAFTS.isEmpty() || !PENDING_DRAFT_CLEARS.isEmpty())) {
                    try { startDraftDrain(); } catch (RuntimeException | OutOfMemoryError ignored) { draftDrainRunning = false; }
                }
            }
        }
    }

    private static void drainDraftWork() {
        while (true) {
            DraftWrite write; boolean clearing;
            synchronized (DRAFT_LOCK) {
                if (PENDING_DRAFTS.isEmpty() && PENDING_DRAFT_CLEARS.isEmpty()) return;
                clearing = PENDING_DRAFTS.isEmpty();
                LinkedHashMap<String, DraftWrite> work = clearing ? PENDING_DRAFT_CLEARS : PENDING_DRAFTS;
                String key = work.keySet().iterator().next(); write = work.remove(key);
                if (clearing && (!write.encoded.equals(MEMORY_DRAFTS.get(key)) || PENDING_DRAFTS.containsKey(key))) continue;
            }
            try {
                if (clearing) {
                    if (write.encoded.equals(write.preferences.getString(write.key, ""))
                            && write.preferences.edit().remove(write.key).commit()) {
                        synchronized (DRAFT_LOCK) {
                            if (write.encoded.equals(MEMORY_DRAFTS.get(write.key)) && !PENDING_DRAFTS.containsKey(write.key))
                                MEMORY_DRAFTS.remove(write.key);
                        }
                    }
                    continue;
                }
                if (!write.preferences.contains(write.key) && write.preferences.getAll().size() >= 8)
                    throw new IllegalStateException("Draft storage is full; save an existing retained frame first");
                if (!write.preferences.edit().putString(write.key, write.encoded).commit())
                    throw new IllegalStateException("Drawing draft could not be durably retained");
                if (write.committed != null) DRAFT_CALLBACKS.post(write.committed);
            } catch (Exception | OutOfMemoryError error) {
                Exception failure = error instanceof Exception ? (Exception) error : DRAFT_MEMORY_FAILURE;
                if (write.failed != null) DRAFT_CALLBACKS.post(() -> write.failed.accept(failure));
                // The latest bounded vector snapshot remains in MEMORY_DRAFTS
                // for configuration recovery even when disk retention failed.
            }
        }
    }

    private EditorCelDrawingDialog() { }

    public static void show(Activity activity, String draftKey, String title, JSONObject initialDrawing,
                            Uri previousCel, Uri nextCel, int holdFrames, int fpsNumerator,
                            int fpsDenominator, SaveListener listener) {
        if (activity == null || activity.isFinishing() || listener == null) return;
        if (draftKey == null || draftKey.isEmpty() || draftKey.length() > 256)
            throw new IllegalArgumentException("A stable project drawing draft identity is required");
        if (!OPEN.compareAndSet(false, true)) {
            Toast.makeText(activity, "Close the current frame drawing first", Toast.LENGTH_SHORT).show(); return;
        }
        DrawingSession session = null;
        FrameView[] constructingView = new FrameView[1];
        try {
            session = new DrawingSession(activity, draftKey, title, initialDrawing, previousCel,
                    nextCel, holdFrames, fpsNumerator, fpsDenominator, listener, constructingView);
            session.open();
        } catch (Exception | OutOfMemoryError error) {
            if (session != null) session.close();
            else { if (constructingView[0] != null) constructingView[0].dispose(); OPEN.set(false); }
            Toast.makeText(activity, "Could not open frame drawing: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static final class DrawingSession {
        final Activity activity;
        final String draftKey;
        final SaveListener listener;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Dialog dialog;
        final FrameView view;
        final TextView status;
        final EditText frames, fps;
        final SharedPreferences drafts;
        final Runnable autosave;
        final java.util.ArrayList<AlertDialog> toolsDialogs = new java.util.ArrayList<>(4);
        boolean closed, modified, savedRequestQueued, finalRetentionPending, draftReady;
        int retainedFrames, retainedFpsNumerator, retainedFpsDenominator;

        DrawingSession(Activity activity, String key, String title, JSONObject drawing, Uri previous,
                       Uri next, int count, int numerator, int denominator, SaveListener listener,
                       FrameView[] constructingView) throws Exception {
            this.activity = activity; draftKey = key; this.listener = listener;
            retainedFrames = Math.max(1, count); retainedFpsNumerator = numerator; retainedFpsDenominator = denominator;
            drafts = activity.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            layout.setBackgroundColor(Color.rgb(18, 22, 30));
            TextView heading = label(activity, title == null || title.isEmpty() ? "Frame drawing" : title);
            heading.setTextSize(18); layout.addView(heading);
            status = label(activity, "Draw with one finger. Pinch and drag with two fingers to zoom.");
            layout.addView(status);
            view = new FrameView(activity, AnimationCelFactory.validateDrawing(drawing), () -> {
                modified = true; scheduleDraft(); status.setText("Unsaved frame");
            }, this::retainVectorEdit, message -> status.setText(message), constructingView);
            layout.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout tools = new LinearLayout(activity);
            tools.addView(button(activity, "Brush", () -> view.drawMode(false)));
            tools.addView(button(activity, "Eraser", () -> view.drawMode(true)));
            tools.addView(button(activity, "Color", this::chooseColor));
            tools.addView(button(activity, "Width", this::chooseWidth));
            tools.addView(button(activity, "Undo edit", view::undo));
            tools.addView(button(activity, "Fit", view::fit));
            HorizontalScrollView scroll = new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(tools); layout.addView(scroll);
            LinearLayout vectorTools = new LinearLayout(activity);
            vectorTools.addView(button(activity, "Select stroke", this::chooseStroke));
            vectorTools.addView(button(activity, "Stroke style", this::chooseStrokeStyle));
            vectorTools.addView(button(activity, "Point", this::choosePoint));
            vectorTools.addView(button(activity, "Move stroke", this::translateStroke));
            vectorTools.addView(button(activity, "Delete stroke", () -> {
                try { view.applySelected("delete_stroke", new JSONObject()); }
                catch (Exception | OutOfMemoryError error) { toast("Stroke was not deleted: " + error.getMessage()); }
            }));
            vectorTools.addView(button(activity, "Refresh preview", view::refreshPreview));
            HorizontalScrollView vectorScroll = new HorizontalScrollView(activity);
            vectorScroll.setHorizontalScrollBarEnabled(false); vectorScroll.addView(vectorTools); layout.addView(vectorScroll);
            LinearLayout onion = new LinearLayout(activity);
            CheckBox prior = checkbox(activity, "Previous", previous != null);
            CheckBox after = checkbox(activity, "Next", next != null);
            prior.setEnabled(previous != null); after.setEnabled(next != null);
            prior.setOnCheckedChangeListener((control, enabled) -> { view.showPrevious = enabled; view.invalidate(); });
            after.setOnCheckedChangeListener((control, enabled) -> { view.showNext = enabled; view.invalidate(); });
            onion.addView(label(activity, "Onion skin: ")); onion.addView(prior); onion.addView(after); layout.addView(onion);
            view.showPrevious = previous != null; view.showNext = next != null;
            LinearLayout settings = new LinearLayout(activity);
            settings.addView(label(activity, "Hold frames "));
            frames = numberInput(activity, Integer.toString(Math.max(1, count)), true);
            settings.addView(frames, new LinearLayout.LayoutParams(0, -2, 1));
            settings.addView(label(activity, " FPS "));
            fps = numberInput(activity, denominator == 1 ? Integer.toString(numerator) : numerator + "/" + denominator, true);
            fps.setHint("12 / 24 / 30 / 60");
            settings.addView(fps, new LinearLayout.LayoutParams(0, -2, 1)); layout.addView(settings);
            LinearLayout actions = new LinearLayout(activity);
            actions.addView(button(activity, "Keep draft and close", this::keepAndClose));
            actions.addView(button(activity, "Save frame", this::save)); layout.addView(actions);
            dialog.setContentView(layout);
            dialog.setOnDismissListener(ignored -> close());
            autosave = this::saveDraft;
            TextWatcher timingChanges = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    modified = true; scheduleDraft();
                }
                @Override public void afterTextChanged(Editable value) { }
            };
            frames.addTextChangedListener(timingChanges); fps.addTextChangedListener(timingChanges);
            view.setEnabled(false); frames.setEnabled(false); fps.setEnabled(false);
            status.setText("Opening retained drawing…");
            view.loadOnion(previous, true); view.loadOnion(next, false);
        }

        void open() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(-1, -1);
                window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View decor) { }
                    @Override public void onViewDetachedFromWindow(View decor) { close(); }
                });
            }
            loadDraft();
        }
        void scheduleDraft() { handler.removeCallbacks(autosave); handler.postDelayed(autosave, 400); }
        void finalRetention(boolean pending) {
            finalRetentionPending = pending; view.setEnabled(!pending && draftReady);
            frames.setEnabled(!pending && draftReady); fps.setEnabled(!pending && draftReady);
            if (pending) handler.removeCallbacks(autosave);
        }
        void retainVectorEdit() throws Exception {
            if (closed || !draftReady) throw new IllegalStateException("The retained drawing is not open for editing");
            // This retains the accepted vectors in the coalesced memory/write
            // queue before any replacement bitmap is allocated.
            submitDraft(draftRecord().toString());
        }
        boolean vectorEditingReady() {
            if (closed || !draftReady || finalRetentionPending) { toast("Wait for the retained drawing to be ready"); return false; }
            return true;
        }
        void showToolsDialog(AlertDialog.Builder builder) {
            if (closed) return;
            if (toolsDialogs.size() >= 8) throw new IllegalStateException("Close an existing drawing control first");
            AlertDialog child = builder.create(); toolsDialogs.add(child);
            child.setOnDismissListener(ignored -> toolsDialogs.remove(child));
            try { child.show(); }
            catch (RuntimeException | OutOfMemoryError error) { toolsDialogs.remove(child); child.dismiss(); throw error; }
        }
        void chooseStroke() {
            if (!vectorEditingReady()) return;
            try {
                view.finishStroke(); view.cancelPointDrag();
                JSONArray strokes = view.document.getJSONArray("strokes");
                if (strokes.length() == 0) { toast("Draw a stroke before selecting one"); return; }
                String[] names = new String[strokes.length()], ids = new String[strokes.length()]; int selected = -1;
                for (int index = 0; index < strokes.length(); index++) {
                    JSONObject stroke = strokes.getJSONObject(index); ids[index] = stroke.getString("id");
                    names[index] = (index + 1) + " · " + stroke.getString("type") + " · " + stroke.getJSONArray("points").length() + " points";
                    if (ids[index].equals(view.selectedStrokeId)) selected = index;
                }
                showToolsDialog(new AlertDialog.Builder(activity).setTitle("Select an existing stroke")
                        .setSingleChoiceItems(names, selected, (picker, index) -> { view.selectStroke(ids[index]); picker.dismiss(); })
                        .setNegativeButton("Cancel", null));
            } catch (Exception | OutOfMemoryError error) { toast("Could not select a stroke: " + error.getMessage()); }
        }
        JSONObject selectedStroke() throws Exception {
            if (!vectorEditingReady()) return null;
            JSONObject stroke = view.selectedStroke();
            if (stroke == null) toast("Select an existing stroke first");
            return stroke;
        }
        void chooseStrokeStyle() {
            try {
                JSONObject stroke = selectedStroke(); if (stroke == null) return;
                String strokeId = stroke.getString("id");
                LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
                String initialColor = stroke.getString("color"), initialWidth = Double.toString(stroke.getDouble("width") * 100);
                EditText colorInput = numberInput(activity, initialColor, false);
                EditText widthInput = decimalInput(activity, initialWidth);
                form.addView(label(activity, "Color #RRGGBB / #AARRGGBB")); form.addView(colorInput);
                form.addView(label(activity, "Width · percent of the shorter frame edge (0.1–20)")); form.addView(widthInput);
                showToolsDialog(new AlertDialog.Builder(activity).setTitle("Selected " + stroke.getString("type") + " stroke")
                        .setMessage("Eraser strokes keep their eraser behavior; their width remains editable.")
                        .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", (ignored, which) -> {
                            try {
                                String changedColor = colorInput.getText().toString().trim(), changedWidth = widthInput.getText().toString().trim();
                                JSONObject settings = new JSONObject();
                                if (!initialColor.equals(changedColor)) settings.put("color", changedColor);
                                if (!initialWidth.equals(changedWidth)) settings.put("width", Double.parseDouble(changedWidth) / 100);
                                // An unchanged width is omitted entirely: even
                                // percentage conversion must not quantize it.
                                if (settings.length() > 0) view.applyToStroke(strokeId, "set_stroke", settings);
                            }
                            catch (Exception | OutOfMemoryError error) { toast("Stroke style was not changed: " + error.getMessage()); }
                        }));
            } catch (Exception | OutOfMemoryError error) { toast("Could not edit the stroke: " + error.getMessage()); }
        }
        void choosePoint() {
            try {
                JSONObject stroke = selectedStroke(); if (stroke == null) return;
                String strokeId = stroke.getString("id"); int count = stroke.getJSONArray("points").length();
                EditText input = numberInput(activity, Integer.toString(Math.max(0, view.selectedPoint) + 1), true);
                showToolsDialog(new AlertDialog.Builder(activity).setTitle("Choose stroke point · 1–" + count).setView(input)
                        .setNegativeButton("Cancel", null).setPositiveButton("Choose", (ignored, which) -> {
                            try {
                                int index = Integer.parseInt(input.getText().toString().trim()) - 1;
                                if (!view.selectPoint(strokeId, index)) throw new IllegalArgumentException("Point is outside the selected stroke");
                                pointActions(strokeId, index);
                            } catch (Exception | OutOfMemoryError error) { toast("Could not choose the point: " + error.getMessage()); }
                        }));
            } catch (Exception | OutOfMemoryError error) { toast("Could not open the point: " + error.getMessage()); }
        }
        void pointActions(String strokeId, int pointIndex) {
            showToolsDialog(new AlertDialog.Builder(activity).setTitle("Point " + (pointIndex + 1))
                    .setItems(new String[]{"Edit position / pressure", "Insert before", "Insert after", "Remove point"}, (ignored, which) -> {
                        try {
                            if (!vectorEditingReady()) return;
                            if (which == 0) editPoint(strokeId, pointIndex);
                            else if (which == 1 || which == 2) insertPoint(strokeId, pointIndex, which == 2);
                            else view.applyToStroke(strokeId, "remove_point", new JSONObject().put("pointIndex", pointIndex));
                        } catch (Exception | OutOfMemoryError error) { toast("Point action was not applied: " + error.getMessage()); }
                    }).setNegativeButton("Cancel", null));
        }
        void insertPoint(String strokeId, int selectedIndex, boolean after) throws Exception {
            JSONObject stroke = view.stroke(strokeId); if (stroke == null) return;
            JSONArray points = stroke.getJSONArray("points"); JSONObject selected = points.getJSONObject(selectedIndex);
            int neighborIndex = selectedIndex + (after ? 1 : -1), insertionIndex = selectedIndex + (after ? 1 : 0);
            JSONObject neighbor = neighborIndex >= 0 && neighborIndex < points.length() ? points.getJSONObject(neighborIndex) : selected;
            LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
            EditText x = decimalInput(activity, Double.toString((selected.getDouble("x") + neighbor.getDouble("x")) / 2));
            EditText y = decimalInput(activity, Double.toString((selected.getDouble("y") + neighbor.getDouble("y")) / 2));
            EditText pressure = decimalInput(activity, Double.toString((selected.getDouble("pressure") + neighbor.getDouble("pressure")) / 2));
            form.addView(label(activity, "X · 0–1 across the frame")); form.addView(x);
            form.addView(label(activity, "Y · 0–1 down the frame")); form.addView(y);
            form.addView(label(activity, "Pressure · 0.1–1.5")); form.addView(pressure);
            showToolsDialog(new AlertDialog.Builder(activity).setTitle("Insert " + (after ? "after" : "before") + " point " + (selectedIndex + 1))
                    .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Insert", (ignored, which) -> {
                        try {
                            boolean applied = view.applyToStroke(strokeId, "insert_point", new JSONObject().put("pointIndex", insertionIndex)
                                    .put("x", Double.parseDouble(x.getText().toString().trim()))
                                    .put("y", Double.parseDouble(y.getText().toString().trim()))
                                    .put("pressure", Double.parseDouble(pressure.getText().toString().trim())));
                            if (applied) {
                                // Selection feedback failure cannot turn an
                                // already installed edit into a false failure.
                                try { view.selectPoint(strokeId, insertionIndex); }
                                catch (RuntimeException | OutOfMemoryError ignoredSelection) { }
                            }
                        } catch (Exception | OutOfMemoryError error) { toast("Point was not inserted: " + error.getMessage()); }
                    }));
        }
        void editPoint(String strokeId, int pointIndex) throws Exception {
            JSONObject stroke = view.stroke(strokeId); if (stroke == null) return;
            JSONObject point = stroke.getJSONArray("points").getJSONObject(pointIndex);
            LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
            EditText x = decimalInput(activity, Double.toString(point.getDouble("x")));
            EditText y = decimalInput(activity, Double.toString(point.getDouble("y")));
            EditText pressure = decimalInput(activity, Double.toString(point.getDouble("pressure")));
            form.addView(label(activity, "X · 0–1 across the frame")); form.addView(x);
            form.addView(label(activity, "Y · 0–1 down the frame")); form.addView(y);
            form.addView(label(activity, "Pressure · 0.1–1.5")); form.addView(pressure);
            showToolsDialog(new AlertDialog.Builder(activity).setTitle("Point " + (pointIndex + 1)).setView(form)
                    .setNegativeButton("Cancel", null).setPositiveButton("Apply", (ignored, which) -> {
                        try { view.applyToStroke(strokeId, "move_point", new JSONObject().put("pointIndex", pointIndex)
                                .put("x", Double.parseDouble(x.getText().toString().trim()))
                                .put("y", Double.parseDouble(y.getText().toString().trim()))
                                .put("pressure", Double.parseDouble(pressure.getText().toString().trim()))); }
                        catch (Exception | OutOfMemoryError error) { toast("Point was not changed: " + error.getMessage()); }
                    }));
        }
        void translateStroke() {
            try {
                JSONObject stroke = selectedStroke(); if (stroke == null) return;
                String strokeId = stroke.getString("id");
                LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
                EditText dx = decimalInput(activity, "0"), dy = decimalInput(activity, "0");
                form.addView(label(activity, "Horizontal offset · percent of frame width")); form.addView(dx);
                form.addView(label(activity, "Vertical offset · percent of frame height")); form.addView(dy);
                showToolsDialog(new AlertDialog.Builder(activity).setTitle("Move selected stroke").setMessage("Every point must remain inside the frame.")
                        .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Move", (ignored, which) -> {
                            try { view.applyToStroke(strokeId, "translate_stroke", new JSONObject()
                                    .put("dx", Double.parseDouble(dx.getText().toString().trim()) / 100)
                                    .put("dy", Double.parseDouble(dy.getText().toString().trim()) / 100)); }
                            catch (Exception | OutOfMemoryError error) { toast("Stroke was not moved: " + error.getMessage()); }
                        }));
            } catch (Exception | OutOfMemoryError error) { toast("Could not move the stroke: " + error.getMessage()); }
        }
        void chooseColor() {
            if (closed || finalRetentionPending || !draftReady) return;
            EditText input = numberInput(activity, String.format(java.util.Locale.ROOT, "#%08X", view.color), false);
            showToolsDialog(new AlertDialog.Builder(activity).setTitle("Brush color").setMessage("Use a color such as #FFFFFFFF or #FF3366FF.")
                    .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply", (ignored, which) -> {
                        try { if (!closed && !finalRetentionPending) { view.color = Color.parseColor(input.getText().toString().trim()); view.erasing = false; } }
                        catch (Exception error) { toast("Invalid brush color"); }
                    }));
        }
        void chooseWidth() {
            if (closed || finalRetentionPending || !draftReady) return;
            EditText input = numberInput(activity, String.format(java.util.Locale.ROOT, "%.1f", view.brushWidth * 100), false);
            showToolsDialog(new AlertDialog.Builder(activity).setTitle("Brush width").setMessage("Percent of the shorter frame edge, from 0.1 to 20.")
                    .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply", (ignored, which) -> {
                        try {
                            double value = Double.parseDouble(input.getText().toString().trim()) / 100;
                            if (!Double.isFinite(value) || value < .001 || value > .2) throw new IllegalArgumentException();
                            if (!closed && !finalRetentionPending) view.brushWidth = value;
                        } catch (Exception error) { toast("Brush width must be from 0.1 to 20 percent"); }
                    }));
        }
        int[] settings() {
            int count = Integer.parseInt(frames.getText().toString().trim());
            int numerator = Integer.parseInt(fps.getText().toString().trim());
            if (count < 1 || count > 10000000 || (numerator != 12 && numerator != 24 && numerator != 30 && numerator != 60))
                throw new IllegalArgumentException("Use 1–10000000 hold frames and 12, 24, 30 or 60 FPS");
            return new int[]{count, numerator, 1};
        }
        JSONObject draftRecord() throws Exception {
            try {
                int[] timing = settings(); retainedFrames = timing[0]; retainedFpsNumerator = timing[1]; retainedFpsDenominator = timing[2];
            } catch (RuntimeException incompleteTiming) { /* Preserve the drawing while its timing field is being edited. */ }
            return new JSONObject().put("drawing", view.drawing()).put("frames", retainedFrames)
                    .put("fpsNumerator", retainedFpsNumerator).put("fpsDenominator", retainedFpsDenominator)
                    .put("rawFrames", frames.getText().toString()).put("rawFps", fps.getText().toString())
                    .put("updatedAt", System.currentTimeMillis());
        }
        void save() {
            if (!draftReady) { toast("Wait for the retained drawing to open before saving"); return; }
            if (finalRetentionPending) return;
            try {
                view.cancelPointDrag(); view.finishStroke();
                JSONObject drawing = view.drawing(); int[] timing = settings();
                JSONObject record = draftRecord(); String encoded = record.toString();
                finalRetention(true);
                submitDraft(encoded, () -> {
                    finalRetention(false); if (closed) return;
                    try {
                        listener.onSave(drawing, timing[0], timing[1], timing[2], () -> {
                            try { clearCommittedDraft(drafts, draftKey, encoded); }
                            catch (Exception ignored) { /* Extra draft retention is safe. */ }
                        });
                        savedRequestQueued = true; dialog.dismiss();
                    } catch (Exception error) { toast("Frame was not queued: " + error.getMessage()); }
                }, error -> { finalRetention(false); if (!closed) toast("Frame draft was not retained: " + error.getMessage()); });
            } catch (Exception | OutOfMemoryError error) { finalRetention(false); toast("Frame was not saved: " + error.getMessage()); }
        }
        void keepAndClose() {
            if (!draftReady) { toast("Wait for the retained drawing to open before retaining it"); return; }
            if (finalRetentionPending) return;
            try {
                view.cancelPointDrag(); view.finishStroke(); finalRetention(true);
                submitDraft(draftRecord().toString(), () -> {
                    finalRetention(false);
                    if (!closed) { savedRequestQueued = true; dialog.dismiss(); }
                }, error -> { finalRetention(false); if (!closed) toast("Keep the drawing open: " + error.getMessage()); });
            } catch (Exception | OutOfMemoryError error) { finalRetention(false); toast("Drawing draft was not retained: " + error.getMessage()); }
        }
        void saveDraft() {
            if (closed || !draftReady || finalRetentionPending) return;
            try { submitDraft(draftRecord().toString()); }
            catch (Exception | OutOfMemoryError error) { if (modified) status.setText("Draft not saved: " + error.getMessage()); }
        }
        void submitDraft(String encoded) {
            submitDraft(encoded, null, error -> { if (!closed) status.setText("Draft not saved: " + error.getMessage()); });
        }
        void submitDraft(String encoded, Runnable committed, java.util.function.Consumer<Exception> failed) {
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES)
                throw new IllegalArgumentException("Drawing draft exceeds its bounded storage budget");
            retainDraft(new DraftWrite(drafts, draftKey, encoded, committed, failed));
        }
        void loadDraft() {
            try { DRAFT_IO.execute(() -> {
                try {
                    String encoded;
                    synchronized (DRAFT_LOCK) { encoded = MEMORY_DRAFTS.get(draftKey); }
                    if (encoded == null) encoded = drafts.getString(draftKey, "");
                    if (encoded.isEmpty()) {
                        synchronized (DRAFT_LOCK) {
                            if (!MEMORY_DRAFTS.containsKey(draftKey) && MEMORY_DRAFTS.size() >= 8)
                                throw new IllegalStateException("Save an existing retained drawing before opening a new draft");
                        }
                        if (!drafts.contains(draftKey) && drafts.getAll().size() >= 8)
                            throw new IllegalStateException("Save an existing retained drawing before opening a new draft");
                    }
                    if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES)
                        throw new IllegalStateException("Retained drawing exceeds its storage budget");
                    JSONObject record = encoded.isEmpty() ? null : new JSONObject(encoded);
                    JSONObject drawing = record == null ? null : AnimationCelFactory.validateDrawing(record.getJSONObject("drawing"));
                    handler.post(() -> {
                        if (closed) return;
                        try {
                            if (record != null) {
                                view.replace(drawing); frames.setText(record.optString("rawFrames", Integer.toString(record.optInt("frames", 1))));
                                int n = record.optInt("fpsNumerator", 24), d = record.optInt("fpsDenominator", 1);
                                retainedFrames = record.optInt("frames", 1); retainedFpsNumerator = n; retainedFpsDenominator = d;
                                fps.setText(record.optString("rawFps", d == 1 ? Integer.toString(n) : n + "/" + d));
                                modified = true;
                            }
                            draftReady = true; finalRetention(false);
                            status.setText(record == null ? "Draw with one finger. Pinch and drag with two fingers to zoom." : "Recovered an unsaved frame draft");
                        } catch (Exception | OutOfMemoryError error) { status.setText("Retained draft could not be opened; close and retry after freeing memory"); }
                    });
                } catch (Exception | OutOfMemoryError error) {
                    handler.post(() -> { if (!closed) status.setText("Retained draft could not be read; it was preserved"); });
                }
            }); } catch (Exception error) { status.setText("Draft reader is busy"); }
        }
        void close() {
            if (closed) return;
            // Android may dismiss the window on configuration changes. Retain
            // the complete vector draft before releasing bitmap allocations.
            try { if (modified && !savedRequestQueued) saveDraft(); }
            finally {
                closed = true;
                try {
                    while (!toolsDialogs.isEmpty()) {
                        AlertDialog child = toolsDialogs.remove(toolsDialogs.size() - 1);
                        try { child.dismiss(); } catch (RuntimeException | OutOfMemoryError ignored) { }
                    }
                    handler.removeCallbacks(autosave); view.dispose();
                }
                finally { OPEN.set(false); }
            }
        }
        void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    }

    private static final class FrameView extends View {
        static final int MAX_UNDO = 8, MAX_UNDO_CHARS = 512 * 1024;
        final Runnable changed;
        final VectorRetention retainBeforeRaster;
        final java.util.function.Consumer<String> information;
        final Handler main = new Handler(Looper.getMainLooper());
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path selectedPath = new Path();
        final ArrayDeque<String> undoHistory = new ArrayDeque<>(MAX_UNDO + 1);
        final ScaleGestureDetector pinch;
        JSONObject document, currentStroke;
        String strokeBefore, selectedStrokeId;
        Bitmap foreground, previous, next;
        Canvas foregroundCanvas;
        double brushWidth = .012;
        int color = Color.WHITE;
        boolean erasing, showPrevious, showNext, capacityNotice, strokeModified, editingVectors, draggingPoint, pointDragMoved, gestureSuppressed, previewDirty;
        volatile boolean closed;
        float zoom = 1, panX, panY, previousCenterX, previousCenterY;
        float dragPressX, dragPressY, dragExtentX, dragExtentY;
        int totalPoints, documentBytes, undoChars, selectedPoint = -1, dragPointer = -1;
        double dragX, dragY, dragStartX, dragStartY;
        FrameView(Context context, JSONObject drawing, Runnable changed, VectorRetention retainBeforeRaster,
                  java.util.function.Consumer<String> information, FrameView[] constructingView) throws Exception {
            super(context); constructingView[0] = this;
            this.changed = changed; this.retainBeforeRaster = retainBeforeRaster; this.information = information;
            setContentDescription("Animation frame drawing canvas; select a stroke to edit its points");
            setFocusable(true); replace(drawing);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float nextZoom = Math.max(1, Math.min(8, zoom * detector.getScaleFactor()));
                    float factor = nextZoom / zoom;
                    panX = detector.getFocusX() - getWidth() / 2f - (detector.getFocusX() - (getWidth() / 2f + panX)) * factor;
                    panY = detector.getFocusY() - getHeight() / 2f - (detector.getFocusY() - (getHeight() / 2f + panY)) * factor;
                    zoom = nextZoom; invalidate(); return true;
                }
            });
        }
        JSONObject drawing() throws Exception { return AnimationCelFactory.validateDrawing(document); }
        void replace(JSONObject drawing) throws Exception {
            JSONObject accepted = AnimationCelFactory.validateDrawing(drawing);
            int acceptedPoints = countPoints(accepted);
            int acceptedBytes = accepted.toString().getBytes(StandardCharsets.UTF_8).length;
            renderAccepted(accepted);
            document = accepted; totalPoints = acceptedPoints; documentBytes = acceptedBytes;
            currentStroke = null; strokeBefore = null; cancelPointDrag();
            undoHistory.clear(); undoChars = 0; selectedStrokeId = null; selectedPoint = -1; editingVectors = false;
            invalidate();
        }
        int countPoints(JSONObject accepted) throws Exception {
            int count = 0; JSONArray strokes = accepted.getJSONArray("strokes");
            for (int index = 0; index < strokes.length(); index++) count += strokes.getJSONObject(index).getJSONArray("points").length();
            return count;
        }
        void renderAccepted(JSONObject accepted) throws Exception {
            int width = accepted.getInt("width"), height = accepted.getInt("height");
            long available = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            if (width * (long) height * 4 + 3L * 1024 * 1024 > available - 32L * 1024 * 1024)
                throw new IllegalStateException("Close another heavy view before drawing this frame");
            JSONObject transparent = new JSONObject(accepted.toString()).put("background", "#00000000");
            Bitmap replacement = AnimationCelFactory.rasterizeBitmap(transparent);
            Canvas replacementCanvas;
            try { replacementCanvas = new Canvas(replacement); }
            catch (RuntimeException | OutOfMemoryError error) { replacement.recycle(); throw error; }
            Bitmap old = foreground; foreground = replacement; foregroundCanvas = replacementCanvas; previewDirty = false;
            if (old != null && old != replacement) old.recycle(); invalidate();
        }
        void pushUndo(String before) {
            while (!undoHistory.isEmpty() && (undoHistory.size() >= MAX_UNDO || undoChars + before.length() > MAX_UNDO_CHARS))
                undoChars -= undoHistory.removeFirst().length();
            undoHistory.addLast(before); undoChars += before.length();
        }
        /** Retain valid vectors first. A failed raster never rolls back the edit. */
        boolean commitVectors(JSONObject accepted, String before) throws Exception {
            String encoded = accepted.toString();
            if (before != null && before.equals(encoded)) return false;
            int acceptedPoints = countPoints(accepted), acceptedBytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (before != null) pushUndo(before);
            document = accepted; totalPoints = acceptedPoints; documentBytes = acceptedBytes;
            previewDirty = true; boolean retained = false;
            try {
                repairSelection(); changed.run(); retainBeforeRaster.retain(); retained = true;
                renderAccepted(accepted); selectionInformation();
            }
            catch (Exception | OutOfMemoryError error) {
                // Once document is installed, even a callback/notice failure
                // cannot escape as a false "not changed" result or lose undo.
                previewDirty = true;
                try { information.accept(retained ? "Edited vectors retained; use Refresh preview after freeing memory"
                        : "Edit remains in this drawing; draft retention needs a retry"); }
                catch (RuntimeException | OutOfMemoryError ignored) { }
                try { notice(retained ? "Edited vectors retained; preview refresh is unavailable"
                        : "Edit remains in this drawing; keep it open and retry draft retention"); }
                catch (RuntimeException | OutOfMemoryError ignored) { }
                try { invalidate(); } catch (RuntimeException | OutOfMemoryError ignored) { }
            }
            return true;
        }
        void refreshPreview() {
            if (closed || !isEnabled()) return;
            try { cancelPointDrag(); finishStroke(); retainBeforeRaster.retain(); renderAccepted(document); selectionInformation(); }
            catch (Exception | OutOfMemoryError error) { notice("Preview could not be refreshed; vectors remain available: " + error.getMessage()); }
        }
        void undo() {
            if (closed || !isEnabled()) return;
            try {
                cancelPointDrag(); finishStroke();
                if (undoHistory.isEmpty()) { notice("No earlier edit in this drawing session"); return; }
                String encoded = undoHistory.peekLast(); JSONObject candidate = AnimationCelFactory.validateDrawing(new JSONObject(encoded));
                // Keep the history entry until its valid vectors are installed;
                // failed detached allocation must not consume an undo step.
                commitVectors(candidate, null); undoHistory.removeLast(); undoChars -= encoded.length();
            } catch (Exception | OutOfMemoryError error) { notice("Could not undo edit: " + error.getMessage()); }
        }
        JSONObject stroke(String id) {
            if (document == null || id == null) return null;
            JSONArray strokes = document.optJSONArray("strokes");
            if (strokes == null) return null;
            for (int index = 0; index < strokes.length(); index++) {
                JSONObject stroke = strokes.optJSONObject(index);
                if (stroke != null && id.equals(stroke.optString("id"))) return stroke;
            }
            return null;
        }
        JSONObject selectedStroke() { return stroke(selectedStrokeId); }
        void repairSelection() {
            JSONObject stroke = selectedStroke();
            if (stroke == null) { selectedStrokeId = null; selectedPoint = -1; return; }
            selectedPoint = Math.max(0, Math.min(selectedPoint, stroke.optJSONArray("points").length() - 1));
        }
        void selectionInformation() {
            JSONObject stroke = selectedStroke();
            if (!editingVectors || stroke == null) { information.accept(editingVectors ? "Select a stroke to edit its points" : "Draw with one finger. Pinch with two fingers to zoom."); return; }
            information.accept(stroke.optString("type") + " stroke · point " + (selectedPoint + 1) + "/" + stroke.optJSONArray("points").length()
                    + " · drag a point; pinch/pan cancels a point drag");
        }
        void selectStroke(String id) {
            if (closed || !isEnabled()) return;
            cancelPointDrag(); if (stroke(id) == null) return;
            selectedStrokeId = id; selectedPoint = 0; editingVectors = true; selectionInformation(); invalidate();
        }
        boolean selectPoint(String id, int index) {
            if (closed || !isEnabled()) return false;
            JSONObject stroke = stroke(id);
            if (stroke == null || index < 0 || index >= stroke.optJSONArray("points").length()) return false;
            cancelPointDrag(); selectedStrokeId = id; selectedPoint = index; editingVectors = true; selectionInformation(); invalidate(); return true;
        }
        void drawMode(boolean erase) {
            if (closed || !isEnabled()) return;
            cancelPointDrag(); erasing = erase; editingVectors = false; selectedStrokeId = null; selectedPoint = -1;
            information.accept(erase ? "Eraser selected" : "Brush selected"); invalidate();
        }
        void applySelected(String operation, JSONObject settings) throws Exception {
            if (selectedStroke() == null) { notice("Select an existing stroke first"); return; }
            applyToStroke(selectedStrokeId, operation, settings);
        }
        boolean applyToStroke(String id, String operation, JSONObject settings) throws Exception {
            if (closed || !isEnabled()) return false;
            cancelPointDrag(); finishStroke();
            JSONObject action = new JSONObject(settings.toString()).put("op", operation).put("strokeId", id);
            JSONObject candidate = AnimationCelVectorEdits.apply(document, new JSONArray().put(action));
            return commitVectors(candidate, document.toString());
        }
        void cancelPointDrag() { draggingPoint = false; pointDragMoved = false; dragPointer = -1; invalidate(); }
        void fit() { cancelPointDrag(); zoom = 1; panX = panY = 0; invalidate(); }
        RectF viewport() {
            float ratio = foreground.getWidth() / (float) foreground.getHeight();
            float width = Math.min(getWidth(), getHeight() * ratio) * zoom, height = width / ratio;
            float x = getWidth() / 2f + panX, y = getHeight() / 2f + panY;
            return new RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (closed || foreground == null) return;
            RectF area = viewport(); canvas.save(); canvas.clipRect(area);
            paint.setAlpha(255);
            for (int y = 0; y < getHeight(); y += 24) for (int x = 0; x < getWidth(); x += 24) {
                paint.setColor(((x / 24 + y / 24) & 1) == 0 ? 0xff3b414d : 0xff292e38);
                canvas.drawRect(x, y, x + 24, y + 24, paint);
            }
            try { paint.setColor(Color.parseColor(document.optString("background", "#00000000"))); canvas.drawRect(area, paint); }
            catch (Exception ignored) { }
            paint.setColor(Color.WHITE); paint.setAlpha(56);
            if (showPrevious && previous != null) canvas.drawBitmap(previous, null, area, paint);
            if (showNext && next != null) canvas.drawBitmap(next, null, area, paint);
            paint.setAlpha(255); canvas.drawBitmap(foreground, null, area, paint);
            drawSelection(canvas, area); canvas.restore();
        }
        void drawSelection(Canvas canvas, RectF area) {
            if (!editingVectors) return;
            JSONObject stroke = selectedStroke(); if (stroke == null) return;
            JSONArray points = stroke.optJSONArray("points"); selectedPath.reset();
            for (int index = 0; index < points.length(); index++) {
                JSONObject point = points.optJSONObject(index);
                float x = area.left + area.width() * (float) (draggingPoint && index == selectedPoint ? dragX : point.optDouble("x"));
                float y = area.top + area.height() * (float) (draggingPoint && index == selectedPoint ? dragY : point.optDouble("y"));
                if (index == 0) selectedPath.moveTo(x, y); else selectedPath.lineTo(x, y);
            }
            overlay.setStyle(Paint.Style.STROKE); overlay.setStrokeWidth(dp(getContext(), 2)); overlay.setColor(0xffffd166);
            canvas.drawPath(selectedPath, overlay);
            // Bound the visible marker count while retaining exact selection of
            // all points through nearest-point picking or the numeric picker.
            int stride = Math.max(1, (points.length() + 127) / 128);
            for (int index = 0; index < points.length(); index++) {
                if (index != selectedPoint && index != points.length() - 1 && index % stride != 0) continue;
                JSONObject point = points.optJSONObject(index);
                float x = area.left + area.width() * (float) (draggingPoint && index == selectedPoint ? dragX : point.optDouble("x"));
                float y = area.top + area.height() * (float) (draggingPoint && index == selectedPoint ? dragY : point.optDouble("y"));
                overlay.setStyle(Paint.Style.FILL); overlay.setColor(index == selectedPoint ? (draggingPoint ? 0xff6ef0b1 : Color.WHITE) : 0xff62d2ff);
                canvas.drawCircle(x, y, dp(getContext(), index == selectedPoint ? 6 : 3), overlay);
                if (index == selectedPoint) { overlay.setStyle(Paint.Style.STROKE); overlay.setColor(Color.BLACK); canvas.drawCircle(x, y, dp(getContext(), 7), overlay); }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || foreground == null || !isEnabled()) return false;
            try {
                pinch.onTouchEvent(event);
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) gestureSuppressed = false;
                if (event.getPointerCount() > 1) {
                    gestureSuppressed = true; cancelPointDrag(); finishStroke();
                    float centerX = (event.getX(0) + event.getX(1)) / 2, centerY = (event.getY(0) + event.getY(1)) / 2;
                    if (action == MotionEvent.ACTION_MOVE && !pinch.isInProgress()) {
                        panX += centerX - previousCenterX; panY += centerY - previousCenterY; invalidate();
                    }
                    previousCenterX = centerX; previousCenterY = centerY; return true;
                }
                if (gestureSuppressed) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        gestureSuppressed = false; releaseTouch();
                    }
                    return true;
                }
                if (editingVectors) return vectorTouch(event);
                if (action == MotionEvent.ACTION_DOWN) {
                    if (!viewport().contains(event.getX(), event.getY())) return true;
                    // A preceding stroke may still need its normalization/undo
                    // install after an allocation failure. Never overwrite its
                    // pending pre-stroke snapshot with a new gesture.
                    finishStroke();
                    if (previewDirty) {
                        refreshPreview();
                        if (previewDirty) { notice("Refresh the retained preview before painting another stroke"); return true; }
                    }
                    if (document.getJSONArray("strokes").length() >= 512 || totalPoints >= 8192 || document.toString().length() > 120 * 1024) {
                        notice("This frame is full. Save it before adding another exposure."); return true;
                    }
                    strokeBefore = document.toString();
                    currentStroke = new JSONObject().put("id", UUID.randomUUID().toString()).put("type", erasing ? "erase" : "paint")
                            .put("color", String.format(java.util.Locale.ROOT, "#%08X", color)).put("width", brushWidth).put("points", new JSONArray());
                    document.getJSONArray("strokes").put(currentStroke); capacityNotice = false; strokeModified = false;
                    documentBytes = document.toString().getBytes(StandardCharsets.UTF_8).length;
                    append(event.getX(), event.getY(), event.getPressure());
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); return true;
                }
                if (action == MotionEvent.ACTION_MOVE && currentStroke != null) {
                    for (int index = 0; index < event.getHistorySize(); index++)
                        append(event.getHistoricalX(index), event.getHistoricalY(index), event.getHistoricalPressure(index));
                    append(event.getX(), event.getY(), event.getPressure()); return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { finishStroke(); releaseTouch(); performClick(); return true; }
            } catch (Exception | OutOfMemoryError error) {
                cancelPointDrag(); releaseTouch();
                try { finishStroke(); }
                catch (Exception | OutOfMemoryError ignored) { currentStroke = null; /* Keep the pending undo snapshot for retry. */ }
                notice("Stroke stopped: " + error.getMessage());
            }
            return true;
        }
        boolean vectorTouch(MotionEvent event) throws Exception {
            int action = event.getActionMasked(); RectF area = viewport();
            if (action == MotionEvent.ACTION_CANCEL) { cancelPointDrag(); releaseTouch(); selectionInformation(); return true; }
            if (action == MotionEvent.ACTION_DOWN) {
                cancelPointDrag(); JSONObject stroke = selectedStroke();
                if (stroke == null) { notice("Select an existing stroke first"); return true; }
                if (!area.contains(event.getX(), event.getY())) return true;
                JSONArray points = stroke.getJSONArray("points"); double distance = dp(getContext(), 22); int nearest = -1;
                // Hit-test every real point once on press. Motion only changes
                // two scalar preview coordinates; no drawing copies or raster.
                for (int index = 0; index < points.length(); index++) {
                    JSONObject point = points.getJSONObject(index);
                    double candidate = Math.hypot(area.left + area.width() * point.getDouble("x") - event.getX(),
                            area.top + area.height() * point.getDouble("y") - event.getY());
                    if (candidate < distance || (candidate == distance && index == selectedPoint)) { distance = candidate; nearest = index; }
                }
                if (nearest < 0) { information.accept("Tap a stroke point, or use Point to choose its exact number"); return true; }
                selectedPoint = nearest; JSONObject point = points.getJSONObject(nearest);
                dragX = dragStartX = point.getDouble("x"); dragY = dragStartY = point.getDouble("y");
                dragPressX = event.getX(); dragPressY = event.getY(); dragExtentX = area.width(); dragExtentY = area.height();
                draggingPoint = true; dragPointer = event.getPointerId(0);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                selectionInformation(); invalidate(); return true;
            }
            if (action == MotionEvent.ACTION_MOVE && draggingPoint) {
                int pointer = event.findPointerIndex(dragPointer);
                if (pointer < 0) { cancelPointDrag(); releaseTouch(); return true; }
                previewPointAt(event.getX(pointer), event.getY(pointer)); invalidate(); return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (draggingPoint) {
                    int pointer = event.findPointerIndex(dragPointer);
                    if (pointer >= 0) previewPointAt(event.getX(pointer), event.getY(pointer));
                    // A tap selects without quantizing or changing its point.
                    boolean moved = dragX != dragStartX || dragY != dragStartY;
                    String id = selectedStrokeId; int index = selectedPoint; double x = dragX, y = dragY;
                    cancelPointDrag();
                    if (moved) applyToStroke(id, "move_point", new JSONObject().put("pointIndex", index).put("x", x).put("y", y));
                    else selectionInformation();
                }
                releaseTouch(); performClick(); return true;
            }
            return true;
        }
        void previewPointAt(float screenX, float screenY) {
            double dx = screenX - dragPressX, dy = screenY - dragPressY;
            if (!pointDragMoved && Math.hypot(dx, dy) < dp(getContext(), 4)) return;
            pointDragMoved = true;
            // Preserve the initial finger-to-point offset and exact unchanged
            // axes, including high-precision positions authored through MCP.
            dragX = dx == 0 ? dragStartX : boundedCoordinate(dragStartX + dx / dragExtentX);
            dragY = dy == 0 ? dragStartY : boundedCoordinate(dragStartY + dy / dragExtentY);
        }
        double boundedCoordinate(double value) { return Math.round(Math.max(0, Math.min(1, value)) * 10000) / 10000.0; }
        void releaseTouch() { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            cancelPointDrag(); super.onSizeChanged(width, height, oldWidth, oldHeight);
        }
        @Override protected void onDetachedFromWindow() {
            cancelPointDrag(); releaseTouch(); super.onDetachedFromWindow();
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        void append(float screenX, float screenY, float pressure) throws Exception {
            if (currentStroke == null) return;
            if (totalPoints >= 8192) { if (!capacityNotice) { capacityNotice = true; notice("Frame point limit reached"); } return; }
            RectF area = viewport();
            double x = Math.round(Math.max(0, Math.min(1, (screenX - area.left) / area.width())) * 10000) / 10000.0;
            double y = Math.round(Math.max(0, Math.min(1, (screenY - area.top) / area.height())) * 10000) / 10000.0;
            JSONArray points = currentStroke.getJSONArray("points"); JSONObject previousPoint = points.length() == 0 ? null : points.getJSONObject(points.length() - 1);
            if (previousPoint != null && Math.hypot((x - previousPoint.getDouble("x")) * foreground.getWidth(),
                    (y - previousPoint.getDouble("y")) * foreground.getHeight()) < .75) return;
            JSONObject point = new JSONObject().put("x", x).put("y", y)
                    .put("pressure", Math.round(Math.max(.1, Math.min(1.5, pressure)) * 100) / 100.0);
            int pointBytes = point.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (documentBytes + pointBytes > 128 * 1024 - 256) {
                if (!capacityNotice) { capacityNotice = true; notice("Frame drawing storage limit reached. Save this exposure before continuing."); }
                return;
            }
            points.put(point); totalPoints++;
            documentBytes += pointBytes;
            // Retain a valid partial stroke even if its next raster operation
            // fails. Debounced snapshots do not terminate an ongoing stroke.
            if (!strokeModified) { strokeModified = true; changed.run(); }
            AnimationCelFactory.renderSegment(foregroundCanvas, currentStroke, previousPoint, point,
                    foreground.getWidth(), foreground.getHeight()); invalidate();
        }
        void finishStroke() throws Exception {
            if (currentStroke == null && strokeBefore == null) return;
            String before = strokeBefore;
            JSONObject accepted;
            try { accepted = AnimationCelFactory.validateDrawing(document); }
            catch (Exception invalidDrawing) {
                JSONArray strokes = document.getJSONArray("strokes"); strokes.remove(strokes.length() - 1);
                currentStroke = null; strokeBefore = null;
                totalPoints = 0;
                for (int index = 0; index < strokes.length(); index++) totalPoints += strokes.getJSONObject(index).getJSONArray("points").length();
                documentBytes = document.toString().getBytes(StandardCharsets.UTF_8).length;
                try { renderAccepted(document); } catch (Exception | OutOfMemoryError ignored) { previewDirty = true; }
                throw invalidDrawing;
            }
            commitVectors(accepted, before);
            // Pre-install validation/encoding failures keep this snapshot for
            // the next finish/undo/save attempt; successful install consumes it.
            currentStroke = null; strokeBefore = null;
        }
        void loadOnion(Uri uri, boolean prior) {
            if (uri == null) return;
            try {
                com.google.common.util.concurrent.ListenableFuture<Bitmap> decoding = new StreamingBitmapLoader(getContext().getApplicationContext(), 512).loadBitmap(uri);
                // A decoder may complete after dismissal. Its listener still
                // owns and recycles that result rather than cancelling it away.
                decoding.addListener(() -> {
                    try {
                        Bitmap bitmap = decoding.get();
                        if (closed) { bitmap.recycle(); return; }
                        boolean posted = main.post(() -> {
                            if (closed) { bitmap.recycle(); return; }
                            if (prior) previous = bitmap; else next = bitmap; invalidate();
                        });
                        if (!posted) bitmap.recycle();
                    } catch (Exception ignored) { main.post(() -> { if (!closed) notice("An adjacent source frame could not be loaded for onion skin"); }); }
                }, Runnable::run);
            } catch (Exception error) { notice("Onion skin reader is busy"); }
        }
        void dispose() {
            closed = true; currentStroke = null; strokeBefore = null; selectedStrokeId = null; cancelPointDrag();
            undoHistory.clear(); undoChars = 0; selectedPath.reset(); foregroundCanvas = null;
            if (foreground != null) foreground.recycle(); if (previous != null) previous.recycle(); if (next != null) next.recycle();
            foreground = previous = next = null;
        }
        void notice(String message) { Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show(); }
    }

    private static TextView label(Context context, String text) {
        TextView view = new TextView(context); view.setTextColor(Color.WHITE); view.setText(text);
        view.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4)); return view;
    }
    private static Button button(Context context, String text, Runnable action) {
        Button button = new Button(context); button.setText(text); button.setContentDescription(text);
        button.setOnClickListener(ignored -> action.run()); return button;
    }
    private static CheckBox checkbox(Context context, String text, boolean checked) {
        CheckBox checkbox = new CheckBox(context); checkbox.setText(text); checkbox.setTextColor(Color.WHITE); checkbox.setChecked(checked); return checkbox;
    }
    private static EditText numberInput(Context context, String text, boolean numeric) {
        EditText input = new EditText(context); input.setText(text); input.setTextColor(Color.WHITE); input.setSingleLine(true);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT); return input;
    }
    private static EditText decimalInput(Context context, String text) {
        EditText input = numberInput(context, text, false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
