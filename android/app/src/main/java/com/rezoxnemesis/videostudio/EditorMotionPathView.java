package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/** Spatial path editor. Anchor/handle gestures modify a draft until the dialog saves it. */
public final class EditorMotionPathView extends View {
    public interface Listener {
        void onSelected(int index);
        void onEdited(JSONObject draft, int selectedIndex);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private JSONObject draft;
    private MotionPath2D compiled;
    private Listener listener;
    private int selected;
    private int dragging = -1; // 0 anchor, 1 incoming handle, 2 outgoing handle
    private double viewport = 1.25;
    private double progress;
    private JSONObject gestureStart;

    public EditorMotionPathView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Motion path editor: select and drag anchors or tangent handles");
        setFocusable(true);
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public void setPath(JSONObject path) {
        compiled = MotionPath2D.fromJson(path); draft = compiled.toJson();
        selected = Math.max(0, Math.min(selected, points().length() - 1));
        invalidate();
    }
    public void setSelectedIndex(int index) {
        selected = Math.max(0, Math.min(index, points().length() - 1)); invalidate();
    }
    public void setProgress(double progress) { this.progress = Math.max(0, Math.min(1, progress)); invalidate(); }
    public void fitPath() {
        viewport = 1.25;
        JSONArray points = points();
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            for (String key : new String[]{"x", "y", "inX", "inY", "outX", "outY"})
                if (point.has(key)) viewport = Math.max(viewport, Math.abs(point.optDouble(key)) + .25);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(14, 20, 30));
        if (draft == null) return;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(38, 49, 66));
        for (int index = -4; index <= 4; index++) {
            double coordinate = index * viewport / 4;
            canvas.drawLine(px(coordinate), 0, px(coordinate), getHeight(), paint);
            canvas.drawLine(0, py(coordinate), getWidth(), py(coordinate), paint);
        }
        paint.setColor(Color.rgb(103, 123, 150));
        canvas.drawRect(px(-1), py(1), px(1), py(-1), paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(10));
        canvas.drawText("Program frame · X right / Y up", dp(8), getHeight() - dp(10), paint);
        JSONArray points = points();
        Path path = new Path();
        JSONObject first = points.optJSONObject(0);
        path.moveTo(px(first.optDouble("x")), py(first.optDouble("y")));
        for (int index = 1; index < points.length(); index++) {
            JSONObject left = points.optJSONObject(index - 1), right = points.optJSONObject(index);
            path.cubicTo(px(left.optDouble("outX", left.optDouble("x"))), py(left.optDouble("outY", left.optDouble("y"))),
                    px(right.optDouble("inX", right.optDouble("x"))), py(right.optDouble("inY", right.optDouble("y"))),
                    px(right.optDouble("x")), py(right.optDouble("y")));
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.rgb(43, 223, 240));
        canvas.drawPath(path, paint);
        JSONObject chosen = points.optJSONObject(selected);
        paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(190, 164, 255));
        float anchorX = px(chosen.optDouble("x")), anchorY = py(chosen.optDouble("y"));
        for (String prefix : new String[]{"in", "out"}) {
            float x = px(chosen.optDouble(prefix + "X", chosen.optDouble("x")));
            float y = py(chosen.optDouble(prefix + "Y", chosen.optDouble("y")));
            canvas.drawLine(anchorX, anchorY, x, y, paint);
            paint.setStyle(Paint.Style.STROKE); canvas.drawRect(x - dp(5), y - dp(5), x + dp(5), y + dp(5), paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(10)); canvas.drawText(prefix, x + dp(7), y - dp(6), paint);
        }
        for (int index = 0; index < points.length(); index++) {
            JSONObject point = points.optJSONObject(index);
            float x = px(point.optDouble("x")), y = py(point.optDouble("y"));
            paint.setStyle(Paint.Style.FILL); paint.setColor(index == selected ? Color.rgb(255, 193, 85) : Color.WHITE);
            canvas.drawCircle(x, y, dp(index == selected ? 7 : 5), paint);
            paint.setTextSize(dp(10)); canvas.drawText((index + 1) + " · " + Math.round(point.optDouble("t") * 100) + "%", x + dp(9), y + dp(12), paint);
        }
        MotionPath2D.Sample sample = compiled.sample(progress);
        float x = px(sample.x), y = py(sample.y);
        paint.setColor(Color.rgb(255, 93, 122)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        canvas.drawCircle(x, y, dp(10), paint);
        if (sample.hasOrientation) {
            double radians = Math.toRadians(sample.rotationDeg);
            canvas.drawLine(x, y, x + (float) Math.cos(radians) * dp(22), y - (float) Math.sin(radians) * dp(22), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (draft == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getX(), y = event.getY();
            JSONObject chosen = points().optJSONObject(selected);
            dragging = -1;
            // A coincident default handle is selected by its numeric controls first.
            for (int index = 1; index <= 2; index++) {
                String prefix = index == 1 ? "in" : "out";
                if (chosen.has(prefix + "X") && distance(x, y, px(chosen.optDouble(prefix + "X")), py(chosen.optDouble(prefix + "Y"))) < dp(18)
                        && distance(px(chosen.optDouble(prefix + "X")), py(chosen.optDouble(prefix + "Y")), px(chosen.optDouble("x")), py(chosen.optDouble("y"))) > dp(4)) dragging = index;
            }
            if (dragging < 0) {
                float nearest = dp(22); int match = -1;
                for (int index = 0; index < points().length(); index++) {
                    JSONObject point = points().optJSONObject(index);
                    float distance = distance(x, y, px(point.optDouble("x")), py(point.optDouble("y")));
                    if (distance < nearest) { nearest = distance; match = index; }
                }
                if (match < 0) return false;
                selected = match; dragging = 0;
                if (listener != null) listener.onSelected(selected);
            }
            gestureStart = copy(draft);
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate(); return true;
        }
        if (action == MotionEvent.ACTION_MOVE && dragging >= 0) {
            JSONObject point = points().optJSONObject(selected);
            double x = clamp((event.getX() - getWidth() / 2d) / scale());
            double y = clamp((getHeight() / 2d - event.getY()) / scale());
            try {
                if (dragging == 0) {
                    double deltaX = x - point.optDouble("x"), deltaY = y - point.optDouble("y");
                    for (String prefix : new String[]{"in", "out"}) if (point.has(prefix + "X")) {
                        point.put(prefix + "X", clamp(point.optDouble(prefix + "X") + deltaX));
                        point.put(prefix + "Y", clamp(point.optDouble(prefix + "Y") + deltaY));
                    }
                    point.put("x", x); point.put("y", y);
                } else {
                    String prefix = dragging == 1 ? "in" : "out";
                    point.put(prefix + "X", x); point.put(prefix + "Y", y);
                }
                compiled = MotionPath2D.fromJson(draft);
                if (listener != null) listener.onEdited(copy(draft), selected);
            } catch (Exception invalid) { draft = copy(gestureStart); compiled = MotionPath2D.fromJson(draft); }
            invalidate(); return true;
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && dragging >= 0) {
            if (action == MotionEvent.ACTION_CANCEL && gestureStart != null) {
                draft = copy(gestureStart); compiled = MotionPath2D.fromJson(draft);
                if (listener != null) listener.onEdited(copy(draft), selected);
            }
            dragging = -1; gestureStart = null;
            getParent().requestDisallowInterceptTouchEvent(false); invalidate(); performClick(); return true;
        }
        return false;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    private JSONArray points() { return draft == null ? new JSONArray() : draft.optJSONArray("points"); }
    private float px(double x) { return (float) (getWidth() / 2d + x * scale()); }
    private float py(double y) { return (float) (getHeight() / 2d - y * scale()); }
    private double scale() { return Math.max(1, Math.min(getWidth(), getHeight()) - dp(32)) / (2d * viewport); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static double clamp(double value) { return Math.max(-10, Math.min(10, value)); }
    private static float distance(float x, float y, float a, float b) { return (float) Math.hypot(x - a, y - b); }
    private static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (Exception failure) { throw new IllegalArgumentException("Path draft is unreadable", failure); }
    }
}
