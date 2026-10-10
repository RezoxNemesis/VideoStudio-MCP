package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;

/** Reusable timing editor with an actual X-solved curve, numeric controls and draggable handles. */
public final class EditorEasingControls extends LinearLayout {
    public interface NamedEvaluator { double at(double progress, String easingName); }
    private static final String[] NAMES = {"linear", "smooth", "ease_in", "ease_out", "ease_in_out", "cinematic", "hold", "cubic_bezier"};
    private final Spinner names;
    private final LinearLayout controls;
    private final EditText[] values = new EditText[4];
    private final TextView status;
    private final CurveView curve;
    private final NamedEvaluator namedEvaluator;

    public EditorEasingControls(Context context, String easing, JSONArray initialControls) {
        this(context, easing, initialControls, NAMES);
    }

    public EditorEasingControls(Context context, String easing, JSONArray initialControls, String[] choices) {
        this(context, easing, initialControls, choices, (progress, name) -> MotionTimeline.ease((float) progress, name));
    }

    public EditorEasingControls(Context context, String easing, JSONArray initialControls, String[] choices,
                                NamedEvaluator namedEvaluator) {
        super(context); setOrientation(VERTICAL);
        if (namedEvaluator == null) throw new IllegalArgumentException("An actual named timing evaluator is required");
        this.namedEvaluator = namedEvaluator;
        if (choices == null || choices.length == 0 || choices.length > 32)
            throw new IllegalArgumentException("Easing picker requires a bounded list of supported curves");
        for (String choice : choices) if (!MotionTimeline.supportsEasing(choice)) throw new IllegalArgumentException("Unsupported easing picker curve: " + choice);
        names = new Spinner(context);
        names.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, choices.clone()));
        String initial = java.util.Arrays.asList(choices).contains(easing) ? easing : canonical(easing);
        int selection = 0; for (int index = 0; index < choices.length; index++) if (choices[index].equals(initial)) selection = index;
        names.setSelection(selection); addView(names);
        curve = new CurveView(context); addView(curve, new LayoutParams(-1, dp(200)));
        controls = new LinearLayout(context); controls.setOrientation(HORIZONTAL);
        double[] fallback = {.25, .1, .25, 1};
        String[] labels = {"X1", "Y1", "X2", "Y2"};
        for (int index = 0; index < values.length; index++) {
            LinearLayout column = new LinearLayout(context); column.setOrientation(VERTICAL);
            TextView label = new TextView(context); label.setTextColor(Color.WHITE); label.setText(labels[index]); column.addView(label);
            EditText value = new EditText(context); value.setTextColor(Color.WHITE); value.setSingleLine();
            value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            double initialValue = initialControls != null && initialControls.length() == 4 ? initialControls.optDouble(index, fallback[index]) : fallback[index];
            value.setText(Double.toString(initialValue)); values[index] = value;
            column.addView(value); controls.addView(column, new LayoutParams(0, -2, 1));
        }
        addView(controls);
        status = new TextView(context); status.setTextSize(12); status.setTextColor(Color.rgb(160, 180, 210)); addView(status);
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable text) {}
        };
        for (EditText value : values) value.addTextChangedListener(watcher);
        names.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        }); refresh();
    }

    public String selectedName() { return names.getSelectedItem().toString(); }
    /** Named curves return null so callers remove obsolete controls from persisted keys. */
    public JSONArray bezier() {
        if (!"cubic_bezier".equals(selectedName())) return null;
        JSONArray array = new JSONArray();
        try {
            for (EditText value : values) array.put(Double.parseDouble(value.getText().toString().trim()));
        } catch (Exception failure) { throw new IllegalArgumentException("Enter four finite numeric Bézier controls"); }
        return CubicBezierEasing.fromJson(array).toJson();
    }

    private void refresh() {
        controls.setVisibility("cubic_bezier".equals(selectedName()) ? VISIBLE : GONE);
        try {
            bezier(); status.setText("cubic_bezier".equals(selectedName())
                    ? "Drag handles or enter X in 0–1 and Y in −4–4. The curve solves X for each progress value."
                    : "Timing progress is horizontal; interpolated value is vertical.");
            status.setTextColor(Color.rgb(160, 180, 210));
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage()); status.setTextColor(Color.rgb(255, 125, 135));
        }
        curve.invalidate();
    }

    private final class CurveView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        double yMin = -.25, yMax = 1.25;
        int dragging = -1;
        CurveView(Context context) { super(context); setContentDescription("Easing curve: drag custom timing handles"); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.drawColor(Color.rgb(14, 20, 30));
            CubicBezierEasing custom;
            JSONArray points;
            try { points = bezier(); custom = points == null ? null : CubicBezierEasing.fromJson(points); }
            catch (IllegalArgumentException invalid) { return; }
            if (dragging < 0) {
                yMin = -.25; yMax = 1.25;
                if (points != null) { yMin = Math.min(yMin, Math.min(points.optDouble(1), points.optDouble(3)) - .15); yMax = Math.max(yMax, Math.max(points.optDouble(1), points.optDouble(3)) + .15); }
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(60, 78, 103));
            canvas.drawRect(px(0), py(1), px(1), py(0), paint);
            paint.setColor(Color.rgb(41, 54, 75)); canvas.drawLine(px(0), py(0), px(1), py(1), paint);
            Path path = new Path();
            for (int index = 0; index <= 160; index++) {
                float input = index / 160f;
                double output = custom == null ? namedEvaluator.at(input, selectedName()) : custom.at(input);
                if (index == 0) path.moveTo(px(input), py(output)); else path.lineTo(px(input), py(output));
            }
            paint.setColor(Color.rgb(41, 225, 239)); paint.setStrokeWidth(dp(2)); canvas.drawPath(path, paint);
            if (points != null) for (int index = 0; index < 2; index++) {
                float x = px(points.optDouble(index * 2)), y = py(points.optDouble(index * 2 + 1));
                paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(181, 150, 252));
                canvas.drawLine(px(index), py(index), x, y, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, dp(7), paint); paint.setStyle(Paint.Style.STROKE);
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(170, 187, 209)); paint.setTextSize(dp(10));
            canvas.drawText("0", px(0), getHeight() - dp(8), paint); canvas.drawText("1 · progress", px(1) - dp(60), getHeight() - dp(8), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!"cubic_bezier".equals(selectedName())) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                JSONArray points; try { points = bezier(); } catch (IllegalArgumentException invalid) { return false; }
                double distance = dp(24); dragging = -1;
                for (int index = 0; index < 2; index++) {
                    double candidate = Math.hypot(event.getX() - px(points.optDouble(index * 2)), event.getY() - py(points.optDouble(index * 2 + 1)));
                    if (candidate < distance) { distance = candidate; dragging = index; }
                }
                if (dragging < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging >= 0) {
                double x = Math.max(0, Math.min(1, (event.getX() - dp(24)) / Math.max(1d, getWidth() - dp(48))));
                double y = Math.max(-4, Math.min(4, yMax - (event.getY() - dp(16)) / Math.max(1d, getHeight() - dp(48)) * (yMax - yMin)));
                values[dragging * 2].setText(Double.toString(x)); values[dragging * 2 + 1].setText(Double.toString(y)); return true;
            }
            if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && dragging >= 0) {
                dragging = -1; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); performClick(); return true;
            }
            return false;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        float px(double x) { return (float) (dp(24) + x * Math.max(1, getWidth() - dp(48))); }
        float py(double y) { return (float) (dp(16) + (yMax - y) / (yMax - yMin) * Math.max(1, getHeight() - dp(48))); }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String canonical(String name) {
        if ("easeIn".equals(name)) return "ease_in";
        if ("easeOut".equals(name)) return "ease_out";
        if ("easeInOut".equals(name)) return "ease_in_out";
        if ("step".equals(name)) return "hold";
        return name == null ? "linear" : name;
    }
}
