package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Native touch authoring. Every committed gesture is a revision-checked SketchEditOps batch. */
public final class SketchCanvasView extends View {
    public interface Listener {
        void onOperation(JSONObject operation);
        void onSelection(String objectId);
        void onMessage(String message);
    }
    private JSONObject document;
    private SketchFrameRenderer renderer;
    private Listener listener;
    private String tool = "stroke", layerId = "layer_1", selectedId, color = "#FF161C2B";
    private int frame;
    private float brushWidth = .008f;
    private boolean fill, onion = true;
    private JSONArray gesture = new JSONArray(), polygon = new JSONArray();
    private float downX, downY, viewScale = 1, left, top;
    private JSONObject dragBase, dragTransform;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SketchCanvasView(Context context) { super(context); setBackgroundColor(0xff1a2030); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    public void setListener(Listener listener) { this.listener = listener; }
    public void setDocument(JSONObject doc) throws Exception {
        SketchDocument.validate(doc); if (renderer != null) renderer.close();
        document = SketchDocument.copy(doc); renderer = new SketchFrameRenderer(getContext(), document);
        frame = Math.min(frame, doc.getInt("frameCount") - 1);
        try { SketchDocument.layer(document, layerId); } catch (Exception missing) { layerId = document.getJSONArray("layers").getJSONObject(0).getString("id"); }
        if (selectedId != null) try { SketchDocument.object(document, selectedId); } catch (Exception missing) { selectedId = null; }
        dragTransform = null; gesture = new JSONArray(); invalidate();
    }
    public void setFrame(int frame) { this.frame = frame; gesture = new JSONArray(); polygon = new JSONArray(); dragTransform = null; invalidate(); }
    public int getFrame() { return frame; }
    public String getLayerId() { return layerId; }
    public void setLayerId(String id) { layerId = id; selectedId = null; if (listener != null) listener.onSelection(null); invalidate(); }
    public String getSelectedId() { return selectedId; }
    public void setTool(String tool) { this.tool = tool; gesture = new JSONArray(); polygon = new JSONArray(); invalidate(); }
    public void setBrush(String color, float width, boolean fill) { this.color = color; this.brushWidth = width; this.fill = fill; invalidate(); }
    public void setOnion(boolean onion) { this.onion = onion; invalidate(); }
    public void dispose() { if (renderer != null) { renderer.close(); renderer = null; } }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (document == null || renderer == null) return;
        try {
            int w = document.getInt("width"), h = document.getInt("height");
            viewScale = Math.min(getWidth() / (float) w, getHeight() / (float) h);
            left = (getWidth() - w * viewScale) / 2; top = (getHeight() - h * viewScale) / 2;
            int save = canvas.save(); canvas.translate(left, top); canvas.scale(viewScale, viewScale); canvas.clipRect(0, 0, w, h);
            canvas.drawColor(SketchDocument.color(document.getString("background")));
            if (onion) {
                for (int offset : new int[]{-1, 1}) {
                    int ghost = frame + offset; if (ghost < 0 || ghost >= document.getInt("frameCount")) continue;
                    Paint alpha = new Paint(); alpha.setAlpha(48); int ghostSave = canvas.saveLayer(new RectF(0, 0, w, h), alpha);
                    renderer.draw(canvas, ghost, false); canvas.restoreToCount(ghostSave);
                }
            }
            renderer.draw(canvas, frame, false, selectedId, dragTransform);
            if (gesture.length() > 0 || polygon.length() > 0) drawGesture(canvas, w, h);
            if (selectedId != null) drawSelection(canvas, w, h);
            canvas.restoreToCount(save);
        } catch (Exception e) { if (listener != null) listener.onMessage(e.getMessage()); }
    }

    private void drawGesture(Canvas canvas, int w, int h) throws Exception {
        int save = canvas.save(); canvas.concat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), w, h));
        paint.setColor("eraser".equals(tool) ? 0xAAE77989 : SketchDocument.color(color)); paint.setStrokeWidth(brushWidth * Math.min(w, h));
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        if ("stroke".equals(tool) || "eraser".equals(tool)) SketchFrameRenderer.drawPressureStroke(canvas, gesture, w, h, brushWidth * Math.min(w, h), paint);
        else if ("polygon".equals(tool)) {
            Path path = new Path(); for (int i = 0; i < polygon.length(); i++) { JSONArray p = polygon.getJSONArray(i); float x = (float) p.getDouble(0) * w, y = (float) p.getDouble(1) * h; if (i == 0) path.moveTo(x, y); else path.lineTo(x, y); }
            paint.setStyle(Paint.Style.STROKE); canvas.drawPath(path, paint);
        } else if (gesture.length() > 1) {
            JSONArray a = gesture.getJSONArray(0), b = gesture.getJSONArray(gesture.length() - 1);
            float ax = (float) a.getDouble(0) * w, ay = (float) a.getDouble(1) * h, bx = (float) b.getDouble(0) * w, by = (float) b.getDouble(1) * h;
            RectF bounds = new RectF(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
            if ("line".equals(tool)) { paint.setStyle(Paint.Style.STROKE); canvas.drawLine(ax, ay, bx, by, paint); }
            else if ("ellipse".equals(tool)) canvas.drawOval(bounds, paint); else canvas.drawRect(bounds, paint);
        }
        canvas.restoreToCount(save);
    }
    private void drawSelection(Canvas canvas, int w, int h) throws Exception {
        JSONObject object = SketchDocument.object(document, selectedId), layer = SketchDocument.owner(document, selectedId);
        if (!SketchFrameRenderer.exposed(object, frame)) return;
        RectF bounds;
        if (object.has("bounds")) bounds = SketchFrameRenderer.bounds(object.getJSONObject("bounds"), w, h);
        else {
            JSONArray pp = object.getJSONArray("points"); bounds = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            for (int i = 0; i < pp.length(); i++) { JSONArray p = pp.getJSONArray(i); float x = (float) p.getDouble(0) * w, y = (float) p.getDouble(1) * h; bounds.left = Math.min(bounds.left, x); bounds.right = Math.max(bounds.right, x); bounds.top = Math.min(bounds.top, y); bounds.bottom = Math.max(bounds.bottom, y); }
        }
        bounds.inset(-6 / viewScale, -6 / viewScale);
        Matrix matrix = new Matrix(); matrix.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer, frame), w, h),
                SketchFrameRenderer.matrix(dragTransform == null ? SketchDocument.transformAt(object, frame) : dragTransform, w, h)); matrix.mapRect(bounds);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(0xff2596ff); paint.setStrokeWidth(2 / viewScale); canvas.drawRect(bounds, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (document == null || renderer == null) return false;
        try {
            float x = (event.getX() - left) / viewScale / document.getInt("width"), y = (event.getY() - top) / viewScale / document.getInt("height");
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (x < 0 || x > 1 || y < 0 || y > 1) return false;
                getParent().requestDisallowInterceptTouchEvent(true); downX = x; downY = y;
                if ("select".equals(tool)) {
                    selectedId = SketchFrameRenderer.hitTest(document, frame, x, y, .018);
                    dragBase = selectedId == null ? null : SketchDocument.transformAt(SketchDocument.object(document, selectedId), frame);
                    if (selectedId != null) layerId = SketchDocument.owner(document, selectedId).getString("id");
                    if (listener != null) listener.onSelection(selectedId); invalidate(); return true;
                }
                SketchEditOps.requireUnlocked(SketchDocument.layer(document, layerId)); gesture = new JSONArray(); addPoint(event.getX(), event.getY(), event.getPressure()); invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if ("select".equals(tool) && selectedId != null) {
                    Matrix inverse = new Matrix(); SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), document.getInt("width"), document.getInt("height")).invert(inverse);
                    float[] positions = {downX * document.getInt("width"), downY * document.getInt("height"), x * document.getInt("width"), y * document.getInt("height")}; inverse.mapPoints(positions);
                    dragTransform = SketchDocument.copy(dragBase).put("x", Math.max(-4, Math.min(4, dragBase.getDouble("x") + (positions[2] - positions[0]) / document.getInt("width"))))
                            .put("y", Math.max(-4, Math.min(4, dragBase.getDouble("y") + (positions[3] - positions[1]) / document.getInt("height"))));
                } else if (!"select".equals(tool) && gesture.length() > 0) {
                    if ("stroke".equals(tool) || "eraser".equals(tool)) for (int i = 0; i < event.getHistorySize(); i++) addPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i));
                    addPoint(event.getX(), event.getY(), event.getPressure());
                }
                invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                performClick(); getParent().requestDisallowInterceptTouchEvent(false);
                if ("select".equals(tool)) {
                    if (selectedId != null && dragTransform != null && Math.hypot(x - downX, y - downY) > .002) {
                        JSONObject object = SketchDocument.object(document, selectedId);
                        JSONObject operation = new JSONObject().put("type", object.getJSONArray("keys").length() > 0 ? "set_keyframe" : "set_transform")
                                .put("objectId", selectedId).put("transform", dragTransform);
                        if (object.getJSONArray("keys").length() > 0) operation.put("frame", frame).put("interpolation", "linear");
                        listener.onOperation(operation);
                    }
                    dragTransform = null;
                } else {
                    addPoint(event.getX(), event.getY(), event.getPressure());
                    if ("polygon".equals(tool)) { polygon.put(gesture.getJSONArray(0)); gesture = new JSONArray(); if (listener != null) listener.onMessage("Polygon: tap vertices, then Finish polygon"); }
                    else commitGesture();
                }
                gesture = new JSONArray(); invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { gesture = new JSONArray(); dragTransform = null; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true; }
        } catch (Exception e) { gesture = new JSONArray(); if (listener != null) listener.onMessage(e.getMessage()); }
        return true;
    }

    private void addPoint(float screenX, float screenY, float pressure) throws Exception {
        if (gesture.length() >= 8192) throw SketchDocument.bad("Touch stroke reaches 8192 points; lift to continue");
        Matrix inverse = new Matrix(); SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), document.getInt("width"), document.getInt("height")).invert(inverse);
        float[] p = {(screenX - left) / viewScale, (screenY - top) / viewScale}; inverse.mapPoints(p);
        double x = Math.max(0, Math.min(1, p[0] / document.getInt("width"))), y = Math.max(0, Math.min(1, p[1] / document.getInt("height")));
        if (gesture.length() > 0) { JSONArray last = gesture.getJSONArray(gesture.length() - 1); if (Math.hypot(x - last.getDouble(0), y - last.getDouble(1)) < .0002 && Math.abs(pressure - last.getDouble(2)) < .01) return; }
        gesture.put(new JSONArray().put(x).put(y).put(Math.max(0, Math.min(1, pressure))));
    }
    private void commitGesture() throws Exception {
        if (gesture.length() == 0) return; JSONObject object = baseObject(tool);
        if ("stroke".equals(tool) || "eraser".equals(tool)) object.put("points", gesture);
        else {
            JSONArray a = gesture.getJSONArray(0), b = gesture.getJSONArray(gesture.length() - 1);
            if ("line".equals(tool)) object.put("points", new JSONArray().put(a).put(b));
            else {
                double x = Math.min(a.getDouble(0), b.getDouble(0)), y = Math.min(a.getDouble(1), b.getDouble(1)), width = Math.abs(a.getDouble(0) - b.getDouble(0)), height = Math.abs(a.getDouble(1) - b.getDouble(1));
                if (width < .0001 || height < .0001) return;
                object.put("bounds", new JSONObject().put("x", x).put("y", y).put("width", width).put("height", height));
            }
        }
        listener.onOperation(new JSONObject().put("type", "add_object").put("layerId", layerId).put("object", object));
    }
    public void finishPolygon() {
        try { if (polygon.length() < 3) throw SketchDocument.bad("A polygon needs at least three vertices");
            listener.onOperation(new JSONObject().put("type", "add_object").put("layerId", layerId).put("object", baseObject("polygon").put("points", polygon)));
            polygon = new JSONArray(); invalidate();
        } catch (Exception e) { if (listener != null) listener.onMessage(e.getMessage()); }
    }
    private JSONObject baseObject(String kind) throws Exception { return new JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind).put("frame", frame).put("exposure", 1).put("color", color).put("width", brushWidth).put("fill", fill); }
    @Override public boolean performClick() { super.performClick(); return true; }
}
