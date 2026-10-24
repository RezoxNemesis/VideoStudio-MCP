package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Small, genuinely native interface for the same kinetic editing program
 * ChatGPT can invoke over MCP. No editing outside VideoStudio, no Gallery.
 */
public final class StudioKineticDirectorDialog {
    private StudioKineticDirectorDialog() {}

    public static void show(Activity activity, ProjectStore store,
                            ProjectStore.Project source,
                            Consumer<ProjectStore.Project> onCreated) {
        if (activity == null || store == null || source == null || onCreated == null)
            throw new IllegalArgumentException("Editor and project are required");
        final long expectedRevision = source.updatedAt;
        final String projectId = source.id;
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad, pad, pad);

        TextView explanation = new TextView(activity);
        explanation.setText("Create an independent, editable kinetic-typography cut from your current "
                + "image/video timeline and existing music. The original remains unchanged.");
        fields.addView(explanation, new LinearLayout.LayoutParams(-1, -2));

        EditText titles = new EditText(activity);
        titles.setHint("One meaningful title or phrase per line");
        titles.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titles.setMinLines(4);
        titles.setMaxLines(8);
        fields.addView(titles, new LinearLayout.LayoutParams(-1, -2));

        Spinner style = new Spinner(activity);
        ArrayAdapter<String> modes = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,
                new String[]{"Editorial", "Impact", "Minimal"});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        style.setAdapter(modes);
        fields.addView(style, new LinearLayout.LayoutParams(-1, -2));

        android.widget.CheckBox shapes=new android.widget.CheckBox(activity);
        shapes.setText("Animated vector accents / perspective grid (up to 4)");
        shapes.setChecked(true);
        fields.addView(shapes,new LinearLayout.LayoutParams(-1,-2));

        EditText tempo = new EditText(activity);
        tempo.setHint("Optional music BPM (60–220)");
        tempo.setInputType(InputType.TYPE_CLASS_NUMBER);
        fields.addView(tempo, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(activity);
        note.setText("Without BPM, title entrances use your existing clip cuts; "
                + "they are not falsely claimed to match the song's beats. "
                + "No rendering starts automatically.");
        fields.addView(note, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(fields, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle("Kinetic Director · After Effects-inspired")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create editable copy", (dialog, which) -> {
                    try {
                        JSONArray phrases = new JSONArray();
                        for (String line : titles.getText().toString().split("\\r?\\n")) {
                            String phrase = line.trim();
                            if (!phrase.isEmpty()) phrases.put(phrase);
                        }
                        JSONObject input = new JSONObject()
                                .put("projectId", projectId)
                                .put("expectedUpdatedAt", expectedRevision)
                                .put("style", style.getSelectedItem().toString().toLowerCase(java.util.Locale.ROOT))
                                .put("shapeAccents",shapes.isChecked())
                                .put("titles", phrases)
                                .put("operationId", UUID.randomUUID().toString());
                        String bpm = tempo.getText().toString().trim();
                        if (!bpm.isEmpty()) input.put("bpm", Integer.parseInt(bpm));
                        StudioKineticDirector.plan(store.get(projectId), input);
                        JSONObject outcome = StudioKineticDirector.apply(store, input);
                        ProjectStore.Project created = store.get(outcome.getString("projectId"));
                        if (created == null) throw new IllegalStateException("Saved kinetic project not found");
                        onCreated.accept(created);
                    } catch (Exception failure) {
                        new AlertDialog.Builder(activity).setTitle("Kinetic edit not created")
                                .setMessage(failure.getMessage() == null
                                        ? "Check titles, tempo and source revision"
                                        : failure.getMessage())
                                .setPositiveButton("OK", null).show();
                    }
                }).show();
    }
}
