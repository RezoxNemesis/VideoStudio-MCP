package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Real path anchors/handles editor. All gestures stay in a draft until one revision-checked save. */
public final class EditorMotionPathDialog {
    public interface Callback {
        void onCommit(String projectId, long expectedRevision, JSONObject pathOrNull) throws Exception;
    }

    private EditorMotionPathDialog() {}

    public static void show(Activity activity, ProjectStore.Project project, String clipId,
                            long outputLocalTimeMs, Callback callback) {
        if (project == null || callback == null) throw new IllegalArgumentException("Project and path callback are required");
        ProjectStore.Clip clip = project.clip(clipId);
        if (clip == null) throw new IllegalArgumentException("Clip not found");
        ProjectMotionPathEdits.validateVisual(project, clipId);
        new Editor(activity, project.id, project.revision, clip, outputLocalTimeMs, callback).show();
    }

    private static final class Editor {
        final Activity activity;
        final String projectId;
        final long revision;
        final Callback callback;
        final EditorMotionPathView canvas;
        final LinearLayout content;
        final Spinner mode;
        final CheckBox orient;
        final EditText rotation;
        final TextView selectedLabel;
        final LinkedHashMap<String, EditText> coordinates = new LinkedHashMap<>();
        JSONObject draft;
        int selected;

        Editor(Activity activity, String projectId, long revision, ProjectStore.Clip clip,
               long localTimeMs, Callback callback) {
            this.activity = activity; this.projectId = projectId; this.revision = revision; this.callback = callback;
            JSONObject effects = clip.effects == null ? new JSONObject() : clip.effects;
            JSONObject existing = effects.optJSONObject("motionPath");
            draft = existing == null ? defaultPath() : MotionPath2D.fromJson(existing).toJson();
            content = column(); content.setPadding(dp(16), dp(8), dp(16), dp(12));
            content.addView(label("Drag anchors or their tangent handles. X = 2 moves one program width right; Y = 2 moves one program height up. Save to apply the path to preview and export."));
            canvas = new EditorMotionPathView(activity); canvas.setPath(draft); canvas.fitPath();
            long durationUs = AnimationClock.microseconds(Math.max(1, effects.optLong("animationDurationMs", clip.outputDurationMs())));
            double progress = AnimationClock.authoredTimeUs(AnimationClock.microseconds(Math.max(0, localTimeMs)),
                    AnimationClock.microseconds(effects.optLong("animationOffsetMs", 0)), durationUs) / (double) durationUs;
            canvas.setProgress(progress);
            content.addView(canvas, new LinearLayout.LayoutParams(-1, dp(260)));
            canvas.setListener(new EditorMotionPathView.Listener() {
                @Override public void onSelected(int index) { selected = index; refreshPoint(); }
                @Override public void onEdited(JSONObject path, int index) { draft = path; selected = index; refreshPoint(); }
            });
            content.addView(button("Fit path", () -> canvas.fitPath()));
            TextView clock = label("Authored clock: " + Math.round(progress * 100) + "%"); content.addView(clock);
            SeekBar scrub = new SeekBar(activity); scrub.setMax(1000); scrub.setProgress((int) Math.round(progress * 1000));
            scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { canvas.setProgress(value / 1000d); clock.setText("Authored clock: " + Math.round(value / 10d) + "%"); }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            }); content.addView(scrub);
            mode = new Spinner(activity); mode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"add", "replace"}));
            mode.setSelection("replace".equals(draft.optString("mode")) ? 1 : 0);
            content.addView(label("Mode · add offsets the clip transform; replace sets path position")); content.addView(mode);
            orient = new CheckBox(activity); orient.setText("Orient clip along the path tangent"); orient.setTextColor(Color.WHITE); orient.setChecked(draft.optBoolean("orientToPath")); content.addView(orient);
            rotation = number(draft.optDouble("rotationOffsetDeg", 0)); content.addView(label("Orientation rotation offset · degrees")); content.addView(rotation);
            selectedLabel = label(""); content.addView(selectedLabel);
            for (String key : new String[]{"t", "x", "y", "inX", "inY", "outX", "outY"}) {
                content.addView(label("t".equals(key) ? "Point time · normalized 0 to 1" : key + " · normalized program coordinate"));
                EditText input = number(0); coordinates.put(key, input); content.addView(input);
            }
            content.addView(button("Update selected point", () -> attempt(this::applyPoint)));
            LinearLayout actions = new LinearLayout(activity); actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(button("Previous", () -> select(selected - 1)), new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(button("Next", () -> select(selected + 1)), new LinearLayout.LayoutParams(0, -2, 1)); content.addView(actions);
            content.addView(button("Add point after selection", () -> attempt(this::addPoint)));
            content.addView(button("Remove selected point", () -> attempt(this::removePoint)));
            content.addView(button("Reset selected handles", () -> attempt(() -> {
                JSONObject point = points().getJSONObject(selected);
                point.remove("inX"); point.remove("inY"); point.remove("outX"); point.remove("outY"); refresh();
            })));
            refreshPoint();
        }

        void show() {
            ScrollView scroll = new ScrollView(activity); scroll.addView(content);
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Motion path")
                    .setView(scroll).setPositiveButton("Save", null).setNeutralButton("Clear path", null).setNegativeButton("Cancel", null).create();
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> attempt(() -> {
                    applyPoint(); applyGlobals();
                    callback.onCommit(projectId, revision, MotionPath2D.fromJson(draft).toJson()); dialog.dismiss();
                }));
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> attempt(() -> {
                    callback.onCommit(projectId, revision, null); dialog.dismiss();
                }));
            }); dialog.show();
        }

        void applyPoint() throws Exception {
            JSONObject candidate = new JSONObject(draft.toString());
            JSONObject point = candidate.getJSONArray("points").getJSONObject(selected);
            for (Map.Entry<String, EditText> input : coordinates.entrySet()) {
                String value = input.getValue().getText().toString().trim();
                if (value.isEmpty() && (input.getKey().startsWith("in") || input.getKey().startsWith("out"))) point.remove(input.getKey());
                else point.put(input.getKey(), finite(value));
            }
            draft = MotionPath2D.fromJson(candidate).toJson(); applyGlobals(); refresh();
        }

        void applyGlobals() throws Exception {
            JSONObject candidate = new JSONObject(draft.toString());
            candidate.put("mode", mode.getSelectedItem().toString()); candidate.put("orientToPath", orient.isChecked());
            candidate.put("rotationOffsetDeg", finite(rotation.getText().toString()));
            draft = MotionPath2D.fromJson(candidate).toJson();
        }

        void addPoint() throws Exception {
            applyPoint();
            JSONArray old = points();
            if (old.length() >= MotionPath2D.MAX_POINTS) throw new IllegalArgumentException("Path already has 128 points");
            int left = Math.min(selected, old.length() - 2);
            double at = (old.getJSONObject(left).getDouble("t") + old.getJSONObject(left + 1).getDouble("t")) * .5;
            if (at <= old.getJSONObject(left).getDouble("t") || at >= old.getJSONObject(left + 1).getDouble("t"))
                throw new IllegalArgumentException("These point times are too close; spread their times before adding a point");
            MotionPath2D.Sample sample = MotionPath2D.fromJson(draft).sample(at);
            JSONObject point = new JSONObject().put("id", UUID.randomUUID().toString()).put("t", at).put("x", sample.x).put("y", sample.y);
            JSONArray next = new JSONArray();
            for (int index = 0; index < old.length(); index++) { next.put(old.getJSONObject(index)); if (index == left) next.put(point); }
            draft.put("points", next); selected = left + 1; refresh();
        }

        void removePoint() throws Exception {
            if (points().length() <= 2) throw new IllegalArgumentException("A path needs at least two points");
            JSONArray next = new JSONArray();
            for (int index = 0; index < points().length(); index++) if (index != selected) next.put(points().getJSONObject(index));
            draft.put("points", next); selected = Math.min(selected, next.length() - 1); refresh();
        }

        void select(int index) { selected = Math.max(0, Math.min(index, points().length() - 1)); canvas.setSelectedIndex(selected); refreshPoint(); }
        void refresh() { canvas.setPath(draft); canvas.setSelectedIndex(selected); refreshPoint(); }
        void refreshPoint() {
            JSONObject point = points().optJSONObject(selected);
            selectedLabel.setText("Point " + (selected + 1) + " / " + points().length() + " · " + point.optString("id"));
            for (Map.Entry<String, EditText> input : coordinates.entrySet()) {
                String key = input.getKey();
                input.getValue().setText(Double.toString(point.optDouble(key,
                        key.endsWith("X") ? point.optDouble("x") : key.endsWith("Y") ? point.optDouble("y") : 0)));
            }
        }
        JSONArray points() { return draft.optJSONArray("points"); }
        LinearLayout column() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
        TextView label(String text) { TextView label = new TextView(activity); label.setTextColor(Color.WHITE); label.setTextSize(13); label.setPadding(0, dp(6), 0, dp(3)); label.setText(text); return label; }
        EditText number(double value) { EditText input = new EditText(activity); input.setTextColor(Color.WHITE); input.setSingleLine(); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); input.setText(Double.toString(value)); return input; }
        Button button(String title, Runnable click) { Button button = new Button(activity); button.setText(title); button.setOnClickListener(view -> click.run()); return button; }
        void attempt(Action action) { try { action.run(); } catch (Exception failure) { Toast.makeText(activity, failure.getMessage() == null ? "Enter a valid path" : failure.getMessage(), Toast.LENGTH_LONG).show(); } }
        int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    }

    private interface Action { void run() throws Exception; }
    private static double finite(String value) {
        double number;
        try { number = Double.parseDouble(value.trim()); }
        catch (Exception failure) { throw new IllegalArgumentException("Enter a numeric coordinate or time"); }
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Coordinates and times must be finite");
        return number;
    }
    private static JSONObject defaultPath() {
        try {
            return new JSONObject().put("version", 1).put("mode", "add").put("orientToPath", false)
                    .put("points", new JSONArray()
                            .put(new JSONObject().put("id", UUID.randomUUID().toString()).put("t", 0).put("x", -.4).put("y", 0))
                            .put(new JSONObject().put("id", UUID.randomUUID().toString()).put("t", 1).put("x", .4).put("y", 0)));
        } catch (Exception failure) { throw new IllegalStateException("Could not create the path draft", failure); }
    }
}
