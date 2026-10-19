package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
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

/**
 * Mobile-first Animate-style editing timeline for the *existing* native Sketch.
 * No raster copies, isolated renderer, playback emulator or separate projects.
 *
 * The host pins the exact source revision and executes every edit through its
 * ordinary undo/redo and native SketchEditorSession transaction.
 */
final class SketchAnimateTimelineDialog {
    interface Actions {
        void edit(JSONArray operations, int seekFrame);
        void seek(int frame, String layerId);
        void library();
        void xsheet();
    }
    private static final String[] EASINGS =
            {"hold", "linear", "smooth", "ease_in", "ease_out", "ease_in_out", "bezier"};
    private static final String[] EASING_LABELS =
            {"Hold / stop motion", "Linear", "Smooth", "Ease in", "Ease out", "Ease in & out", "Bezier S-curve"};

    private SketchAnimateTimelineDialog() {}

    static void show(Activity owner, JSONObject source, int frame, String layerId,
                     String objectId, Actions actions) {
        try {
            SketchDocument.validate(source);
            JSONObject layer = SketchDocument.layer(source, layerId);
            int count = source.getInt("frameCount");
            int position = Math.max(0, Math.min(count - 1, frame));
            String candidate = null;
            if (objectId != null) {
                try {
                    if (layerId.equals(SketchDocument.owner(source, objectId).getString("id")))
                        candidate = objectId;
                } catch (Exception ignored) { /* Previous canvas selection is no longer present. */ }
            }
            final String selected = candidate;
            LinearLayout layout = column(owner);
            ScrollView scroll = new ScrollView(owner);
            scroll.setFillViewport(false);
            scroll.addView(layout);

            TextView info = label(owner, "EDITABLE TIMELINE · " + count + " frames at "
                    + source.getInt("fps") + " FPS · " + layer.getString("name"), 13);
            layout.addView(info);
            TextView legend = label(owner,
                    "● new drawing   = held cel   − blank   ◆ motion key   ◇ shape key", 11);
            layout.addView(legend);
            Spinner subject = new Spinner(owner);
            String[] subjects = selected == null
                    ? new String[]{"Whole layer · all its visible artwork"}
                    : new String[]{"Whole layer · all its visible artwork", "Selected drawing · " + selected};
            subject.setContentDescription("Timeline keyframe target");
            subject.setAdapter(new ArrayAdapter<>(owner, android.R.layout.simple_spinner_dropdown_item, subjects));
            subject.setSelection(selected == null ? 0 : 1);
            layout.addView(subject);
            SeekBar playhead = new SeekBar(owner);
            playhead.setContentDescription("Timeline frame");
            playhead.setMax(count - 1);
            playhead.setProgress(position);
            playhead.setMinimumHeight(StudioUi.dp(owner, 48));
            layout.addView(playhead);
            TextView readout = label(owner, "", 12);
            layout.addView(readout);

            // Use the same source-object exposure projection that previews and
            // native MP4 exports use. This is a real, not simulated, cel sheet.
            int page = (position / 18) * 18;
            JSONObject sheet = SketchXsheetProjection.project(source, page, Math.min(18, count - page));
            JSONArray columns = sheet.getJSONArray("columns");
            JSONObject column = null;
            for (int i = 0; i < columns.length(); i++) {
                if (layerId.equals(columns.getJSONObject(i).getString("layerId"))) {
                    column = columns.getJSONObject(i); break;
                }
            }
            if (column == null) throw SketchDocument.bad("Selected timeline layer disappeared");
            JSONArray cells = column.getJSONArray("cells");
            TextView pageLabel = label(owner,
                    "CEL SHEET · frames " + (page + 1) + "–" + (page + cells.length())
                            + " (tap a cell to choose its frame)", 11);
            layout.addView(pageLabel);
            for (int row = 0; row < cells.length(); row += 6) {
                LinearLayout strip = row(owner, layout);
                for (int j = row; j < Math.min(row + 6, cells.length()); j++) {
                    JSONObject cell = cells.getJSONObject(j);
                    int seek = cell.getInt("frame");
                    String exposure = "blank".equals(cell.getString("state")) ? "−"
                            : "hold".equals(cell.getString("state")) ? "=" : "●";
                    String flag = cell.optBoolean("transformKey") ? "◆"
                            : cell.optBoolean("shapeKey") ? "◇" : "";
                    Button button = button(owner, String.valueOf(seek + 1) + " " + exposure + flag);
                    button.setContentDescription("Timeline frame " + (seek + 1) + ", "
                            + cell.getString("state") + (flag.isEmpty() ? "" : ", keyed"));
                    strip.addView(button, new LinearLayout.LayoutParams(0, StudioUi.dp(owner, 49), 1));
                    button.setOnClickListener(v -> playhead.setProgress(seek));
                }
            }

            Runnable refresh = () -> {
                int at = playhead.getProgress();
                String object = subject.getSelectedItemPosition() == 1 ? selected : null;
                try {
                    JSONObject sampled = SketchAnimateTimelineTools.inspect(source,
                            object == null ? layerId : null, object, at);
                    JSONObject t = sampled.getJSONObject("transform");
                    readout.setText("Frame " + (at + 1) + " · "
                            + (sampled.getBoolean("motionKey") ? "◆ keyed" : "unkeyed")
                            + (sampled.getBoolean("drawingExposed") ? "" : " · blank cel")
                            + " · x " + Math.round(t.getDouble("x") * source.getInt("width"))
                            + "px · y " + Math.round(t.getDouble("y") * source.getInt("height"))
                            + "px · rotation " + Math.round(t.getDouble("rotation")) + "°");
                } catch (Exception failure) {
                    readout.setText("Frame " + (at + 1) + " · " + failure.getMessage());
                }
            };
            playhead.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int progress, boolean byHuman) { refresh.run(); }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
            });
            subject.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p, View v, int index, long id) { refresh.run(); }
                public void onNothingSelected(AdapterView<?> p) {}
            });
            refresh.run();

            final AlertDialog dialog = new AlertDialog.Builder(owner).setTitle("Animate-style timeline")
                    .setView(scroll).setNegativeButton("Close", null).create();

            LinearLayout navigation = row(owner, layout);
            addAction(owner, navigation, "◀ 18", page > 0, () -> {
                dialog.dismiss();
                show(owner, source, Math.max(0, page - 18), layerId, selected, actions);
            });
            addAction(owner, navigation, "Go to frame", true, () -> {
                dialog.dismiss();
                actions.seek(playhead.getProgress(), layerId);
            });
            addAction(owner, navigation, "18 ▶", page + 18 < count, () -> {
                dialog.dismiss();
                show(owner, source, Math.min(count - 1, page + 18), layerId, selected, actions);
            });

            LinearLayout keyActions = row(owner, layout);
            addAction(owner, keyActions, "Insert key ◆", true, () -> {
                applyKey(owner, source, layerId, subject.getSelectedItemPosition() == 1 ? selected : null,
                        playhead.getProgress(), false, actions, dialog);
            });
            addAction(owner, keyActions, "Remove key", true, () -> {
                applyKey(owner, source, layerId, subject.getSelectedItemPosition() == 1 ? selected : null,
                        playhead.getProgress(), true, actions, dialog);
            });
            LinearLayout creative = row(owner, layout);
            addAction(owner, creative, "Motion tween", true, () -> {
                String object = subject.getSelectedItemPosition() == 1 ? selected : null;
                int start = playhead.getProgress();
                if (start >= count - 1) {
                    toast(owner, "Choose a frame before the end of this scene"); return;
                }
                dialog.dismiss();
                tweenDialog(owner, source, object == null ? layerId : null, object, start, actions);
            });
            addAction(owner, creative, "Extend cel hold", selected != null, () -> {
                dialog.dismiss();
                holdDialog(owner, source, selected, playhead.getProgress(), actions);
            });

            LinearLayout resources = row(owner, layout);
            addAction(owner, resources, "Symbol library", true, () -> {
                dialog.dismiss();
                actions.library();
            });
            addAction(owner, resources, "Full Xsheet", true, () -> {
                dialog.dismiss();
                actions.xsheet();
            });
            layout.addView(label(owner, "Undo / Redo stays in Sketch Studio. Motion is evaluated by "
                    + "the original vector renderer; no duplicated still-image slideshow.", 11));
            dialog.show();
        } catch (Exception failure) {
            toast(owner, "Timeline: " + failure.getMessage());
        }
    }

    private static void applyKey(Activity owner, JSONObject source, String layer, String object,
                                 int frame, boolean remove, Actions actions, AlertDialog dialog) {
        try {
            JSONObject edit = remove
                    ? SketchAnimateTimelineTools.removeKey(source, object == null ? layer : null, object, frame)
                    : SketchAnimateTimelineTools.insertKey(source, object == null ? layer : null,
                            object, frame, "smooth");
            JSONObject staged = SketchDocument.copy(source);
            SketchEditOps.apply(staged, edit); // Fail closed on document and motion budgets.
            actions.edit(new JSONArray().put(edit), frame);
            dialog.dismiss();
        } catch (Exception failure) { toast(owner, failure.getMessage()); }
    }

    private static void tweenDialog(Activity owner, JSONObject source, String layerId,
                                    String objectId, int first, Actions actions) {
        try {
            JSONObject original = SketchAnimateTimelineTools.inspect(source, layerId, objectId, first)
                    .getJSONObject("transform");
            int count = source.getInt("frameCount");
            LinearLayout form = column(owner);
            form.addView(label(owner, "From frame " + (first + 1)
                    + " · destination is an absolute transform, not a motion preset.", 12));
            EditText last = input(owner, "Destination frame (1–" + count + ")",
                    String.valueOf(Math.min(count, first + 13)), true);
            form.addView(last);
            String[] keys = {"x", "y", "scaleX", "scaleY", "rotation", "opacity"};
            String[] names = {"Target X translation (px)", "Target Y translation (px)",
                    "Target scale X", "Target scale Y", "Target rotation (°)", "Target opacity 0–1"};
            EditText[] values = new EditText[keys.length];
            for (int i = 0; i < keys.length; i++) {
                double originalValue = original.getDouble(keys[i]);
                if (i == 0) originalValue *= source.getInt("width");
                if (i == 1) originalValue *= source.getInt("height");
                values[i] = input(owner, names[i], String.valueOf(originalValue), true);
                form.addView(values[i]);
            }
            form.addView(label(owner, "Tween spacing", 12));
            Spinner easing = new Spinner(owner);
            easing.setContentDescription("Motion tween easing");
            easing.setAdapter(new ArrayAdapter<>(owner, android.R.layout.simple_spinner_dropdown_item,
                    EASING_LABELS));
            easing.setSelection(5); // Familiar natural ease-in/out default.
            form.addView(easing);
            CheckBox extend = new CheckBox(owner);
            extend.setText("Extend the selected drawing hold to the destination");
            extend.setChecked(false);
            extend.setTextColor(StudioUi.TEXT);
            extend.setVisibility(objectId == null ? View.GONE : View.VISIBLE);
            form.addView(extend);
            form.addView(label(owner, "Existing keys inside the span are preserved: overlapping"
                    + " tweens are refused. Use separate segments for complex acting.", 11));
            ScrollView scroll = new ScrollView(owner);
            scroll.addView(form);
            AlertDialog dialog = new AlertDialog.Builder(owner)
                    .setTitle("Classic motion tween")
                    .setView(scroll).setNegativeButton("Cancel", null)
                    .setPositiveButton("Insert tween", null).create();
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    int end = Integer.parseInt(last.getText().toString().trim()) - 1;
                    JSONObject destination = new JSONObject();
                    for (int i = 0; i < keys.length; i++) {
                        double value = Double.parseDouble(values[i].getText().toString().trim());
                        if (i == 0) value /= source.getInt("width");
                        if (i == 1) value /= source.getInt("height");
                        destination.put(keys[i], value);
                    }
                    JSONObject op = new JSONObject().put("type", "motion_tween")
                            .put(objectId == null ? "layerId" : "objectId",
                                    objectId == null ? layerId : objectId)
                            .put("startFrame", first).put("endFrame", end)
                            .put("destination", destination)
                            .put("interpolation", EASINGS[easing.getSelectedItemPosition()])
                            .put("extendExposure", extend.isChecked() && objectId != null);
                    JSONObject staged = SketchDocument.copy(source);
                    SketchEditOps.apply(staged, op);
                    actions.edit(new JSONArray().put(op), end);
                    dialog.dismiss();
                } catch (Exception failure) { toast(owner, failure.getMessage()); }
            });
        } catch (Exception failure) {
            toast(owner, "Tween: " + failure.getMessage());
        }
    }

    private static void holdDialog(Activity owner, JSONObject source, String objectId,
                                   int frame, Actions actions) {
        LinearLayout content = column(owner);
        EditText end = input(owner, "Hold until frame (1–" + source.optInt("frameCount") + ")",
                String.valueOf(Math.min(source.optInt("frameCount"), frame + 2)), true);
        content.addView(end);
        content.addView(label(owner, "Extends only this drawing's exposure, not its pose."
                + " It may overlap other artwork already on the layer.", 12));
        AlertDialog dialog = new AlertDialog.Builder(owner).setTitle("Extend cel exposure")
                .setView(content).setNegativeButton("Cancel", null)
                .setPositiveButton("Extend", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int last = Integer.parseInt(end.getText().toString().trim()) - 1;
                JSONObject operation = SketchAnimateTimelineTools.extendHold(source, objectId, last);
                JSONObject staged = SketchDocument.copy(source);
                SketchEditOps.apply(staged, operation);
                actions.edit(new JSONArray().put(operation), last);
                dialog.dismiss();
            } catch (Exception failure) { toast(owner, failure.getMessage()); }
        });
    }

    private static LinearLayout column(Activity owner) {
        LinearLayout view = new LinearLayout(owner);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(StudioUi.dp(owner, 10), StudioUi.dp(owner, 7),
                StudioUi.dp(owner, 10), StudioUi.dp(owner, 7));
        view.setBackgroundColor(StudioUi.SURFACE);
        return view;
    }
    private static LinearLayout row(Activity owner, LinearLayout parent) {
        LinearLayout view = new LinearLayout(owner);
        view.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(view);
        return view;
    }
    private static TextView label(Activity owner, String value, int size) {
        TextView view = new TextView(owner);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(StudioUi.MUTED);
        view.setPadding(StudioUi.dp(owner, 2), StudioUi.dp(owner, 5),
                StudioUi.dp(owner, 2), StudioUi.dp(owner, 5));
        return view;
    }
    private static Button button(Activity owner, String value) {
        Button item = new Button(owner);
        item.setText(value);
        item.setTextSize(11);
        StudioUi.styleButton(item, false);
        return item;
    }
    private static void addAction(Activity owner, LinearLayout row, String title,
                                  boolean enabled, Runnable action) {
        Button button = button(owner, title);
        button.setEnabled(enabled);
        row.addView(button, new LinearLayout.LayoutParams(0, StudioUi.dp(owner, 52), 1));
        button.setOnClickListener(v -> action.run());
    }
    private static EditText input(Activity owner, String hint, String initial,
                                  boolean numeric) {
        EditText input = new EditText(owner);
        input.setText(initial);
        input.setHint(hint);
        input.setTextColor(StudioUi.TEXT);
        input.setHintTextColor(StudioUi.MUTED);
        input.setTextSize(13);
        input.setSingleLine(true);
        if (numeric)
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setContentDescription(hint);
        return input;
    }
    private static void toast(Activity owner, String text) {
        Toast.makeText(owner, text, Toast.LENGTH_LONG).show();
    }
}
