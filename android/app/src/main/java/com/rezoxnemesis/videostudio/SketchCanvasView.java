package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuff;
import android.view.ScaleGestureDetector;
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
        default void onColorPicked(String color) {}
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
    private JSONObject dragBase, dragTransform,nodePreview;private int nodeCommand=-1,nodeOffset;
    private String liveObject;private float liveFraction=1;
    public void setPresentation(String objectId,float fraction){liveObject=objectId;liveFraction=fraction;if(renderer!=null)renderer.setPresentation(objectId,fraction);invalidate();}
    private JSONObject brushStyle;
    private float zoom=1,panX,panY,lastPanX,lastPanY,stabilization=.2f;
    private boolean navigating,grid,symmetry,pointerDown;
    private int onionRange=1,onionAlpha=48;
    private final ScaleGestureDetector scaling;
    private final java.util.LinkedHashMap<Integer,Bitmap> frameCache=new java.util.LinkedHashMap<>(8,.75f,true);
    private long cacheBytes;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SketchCanvasView(Context context) {
        super(context);setBackgroundColor(0xff101725);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        scaling=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){float old=zoom;zoom=Math.max(1,Math.min(12,zoom*detector.getScaleFactor()));float ratio=zoom/old;panX=(panX+getWidth()/2f-detector.getFocusX())*ratio-getWidth()/2f+detector.getFocusX();panY=(panY+getHeight()/2f-detector.getFocusY())*ratio-getHeight()/2f+detector.getFocusY();invalidate();return true;}
        });
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public void setDocument(JSONObject doc) throws Exception {
        SketchDocument.validate(doc);boolean same=document!=null&&document.getString("id").equals(doc.getString("id"));
        document=SketchDocument.copy(doc);if(renderer==null||!same){if(renderer!=null)renderer.close();renderer=new SketchFrameRenderer(getContext(),document);zoom=1;panX=panY=0;}else renderer.updateDocument(getContext(),document);clearFrameCache();
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
    public void setBrushStyle(JSONObject brush,float smoothing){this.brushStyle=brush;this.stabilization=Math.max(0,Math.min(.9f,smoothing));invalidate();}
    public void setGuides(boolean grid,boolean symmetry){this.grid=grid;this.symmetry=symmetry;invalidate();}
    public void setOnionOptions(int range,int alpha){onionRange=Math.max(1,Math.min(3,range));onionAlpha=Math.max(0,Math.min(128,alpha));invalidate();}
    public void resetView(){zoom=1;panX=panY=0;invalidate();}
    public void focusSelection() throws Exception {
        if(document==null||selectedId==null)throw SketchDocument.bad("Select an object to focus");
        JSONObject object=SketchDocument.object(document,selectedId),layer=SketchDocument.owner(document,selectedId);int w=document.getInt("width"),h=document.getInt("height");RectF bounds;
        if(object.has("bounds"))bounds=SketchFrameRenderer.bounds(object.getJSONObject("bounds"),w,h);
        else if(object.has("commands")){bounds=new RectF();SketchFrameRenderer.path(object.getJSONArray("commands"),w,h).computeBounds(bounds,true);}
        else{bounds=new RectF(Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE);JSONArray points=object.getJSONArray("points");for(int n=0;n<points.length();n++){JSONArray p=points.getJSONArray(n);float x=(float)p.getDouble(0)*w,y=(float)p.getDouble(1)*h;bounds.left=Math.min(bounds.left,x);bounds.right=Math.max(bounds.right,x);bounds.top=Math.min(bounds.top,y);bounds.bottom=Math.max(bounds.bottom,y);}}
        Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,frame),w,h),SketchFrameRenderer.matrix(SketchDocument.transformAt(object,frame),w,h));world.mapRect(bounds);
        float fit=Math.min(getWidth()/(float)w,getHeight()/(float)h);if(fit<=0)return;zoom=Math.max(1,Math.min(12,Math.min(getWidth()*.85f/Math.max(1,bounds.width()),getHeight()*.85f/Math.max(1,bounds.height()))/fit));panX=-(bounds.centerX()-w/2f)*fit*zoom;panY=-(bounds.centerY()-h/2f)*fit*zoom;invalidate();
    }
    public boolean isDrawing(){return pointerDown||nodePreview!=null||gesture.length()>0||polygon.length()>0||dragTransform!=null||navigating;}
    public float getZoom(){return zoom;}
    public void selectObject(String id){selectedId=id;invalidate();}
    private void clearFrameCache(){for(Bitmap b:frameCache.values())b.recycle();frameCache.clear();cacheBytes=0;}
    private Bitmap frameImage(int index) throws Exception {
        Bitmap b=frameCache.get(index);if(b!=null)return b;b=renderer.render(index,false);
        while(!frameCache.isEmpty()&&cacheBytes+b.getAllocationByteCount()>12L*1024*1024){Integer first=frameCache.keySet().iterator().next();Bitmap old=frameCache.remove(first);cacheBytes-=old.getAllocationByteCount();old.recycle();}
        frameCache.put(index,b);cacheBytes+=b.getAllocationByteCount();return b;
    }
    public void dispose() { clearFrameCache();if (renderer != null) { renderer.close(); renderer = null; } }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (document == null || renderer == null) return;
        try {
            int w = document.getInt("width"), h = document.getInt("height");
            viewScale = Math.min(getWidth() / (float) w, getHeight() / (float) h)*zoom;
            left = (getWidth() - w * viewScale) / 2+panX; top = (getHeight() - h * viewScale) / 2+panY;
            int save = canvas.save(); canvas.translate(left, top); canvas.scale(viewScale, viewScale); canvas.clipRect(0, 0, w, h);
            canvas.drawColor(SketchDocument.color(document.getString("background")));
            if(onion&&liveObject==null)for(int distance=onionRange;distance>=1;distance--)for(int offset:new int[]{-distance,distance}){
                int ghost=frame+offset;if(ghost<0||ghost>=document.getInt("frameCount"))continue;Paint tint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);tint.setAlpha(onionAlpha/distance);tint.setColorFilter(new PorterDuffColorFilter(offset<0?0xffe27a83:0xff4cbbdb,PorterDuff.Mode.SRC_IN));canvas.drawBitmap(frameImage(ghost),0,0,tint);
            }
            if(dragTransform==null&&liveObject==null&&nodePreview==null)canvas.drawBitmap(frameImage(frame),0,0,null);else renderer.draw(canvas,frame,false,selectedId,dragTransform);
            if(grid||symmetry){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1/viewScale);paint.setColor(0x553fa4c9);if(grid){for(int i=1;i<10;i++){canvas.drawLine(w*i/10f,0,w*i/10f,h,paint);canvas.drawLine(0,h*i/10f,w,h*i/10f,paint);}}if(symmetry){paint.setColor(0x998ed4ac);canvas.drawLine(w/2f,0,w/2f,h,paint);}}
            if (gesture.length() > 0 || polygon.length() > 0) drawGesture(canvas, w, h);
            if (selectedId != null) drawSelection(canvas, w, h);
            canvas.restoreToCount(save);
        } catch (Exception e) { if (listener != null) listener.onMessage(e.getMessage()); }
    }

    private void drawGesture(Canvas canvas, int w, int h) throws Exception {
        int save = canvas.save(); canvas.concat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), w, h));
        paint.setColor("eraser".equals(tool) ? 0xAAE77989 : SketchDocument.color(color)); paint.setStrokeWidth(brushWidth * Math.min(w, h));
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        if ("stroke".equals(tool) || "eraser".equals(tool)) {
            SketchBrushes.draw(canvas,gesture,w,h,brushWidth*Math.min(w,h),paint,brushStyle);
            if(symmetry){int mirror=canvas.save();canvas.translate(w,0);canvas.scale(-1,1);SketchBrushes.draw(canvas,gesture,w,h,brushWidth*Math.min(w,h),paint,brushStyle);canvas.restoreToCount(mirror);}
        }
        else if (("polygon".equals(tool)||"curve".equals(tool))) {
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
            JSONArray pp = "path".equals(object.getString("kind"))?SketchFrameRenderer.pathPoints(object.getJSONArray("commands")):object.getJSONArray("points"); bounds = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            for (int i = 0; i < pp.length(); i++) { JSONArray p = pp.getJSONArray(i); float x = (float) p.getDouble(0) * w, y = (float) p.getDouble(1) * h; bounds.left = Math.min(bounds.left, x); bounds.right = Math.max(bounds.right, x); bounds.top = Math.min(bounds.top, y); bounds.bottom = Math.max(bounds.bottom, y); }
        }
        bounds.inset(-6 / viewScale, -6 / viewScale);
        Matrix matrix = new Matrix(); matrix.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer, frame), w, h),
                SketchFrameRenderer.matrix(dragTransform == null ? SketchDocument.transformAt(object, frame) : dragTransform, w, h)); matrix.mapRect(bounds);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(0xff2596ff); paint.setStrokeWidth(2 / viewScale); canvas.drawRect(bounds, paint);
        if("nodes".equals(tool)){JSONObject geometry=nodePreview==null?object:nodePreview;JSONArray commands=geometry.optJSONArray("commands");if(commands!=null){for(int i=0;i<commands.length();i++){JSONArray command=commands.getJSONArray(i);for(int k=1;k<command.length();k+=2){float[] p={(float)command.getDouble(k)*w,(float)command.getDouble(k+1)*h};matrix.mapPoints(p);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff69e7df);canvas.drawCircle(p[0],p[1],5/viewScale,paint);}}}}

    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()||document == null || renderer == null) return false;
        try {
            scaling.onTouchEvent(event);
            if(event.getPointerCount()>1){float cx=(event.getX(0)+event.getX(1))/2,cy=(event.getY(0)+event.getY(1))/2;if(!navigating||event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){navigating=true;gesture=new JSONArray();dragTransform=null;lastPanX=cx;lastPanY=cy;}else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!scaling.isInProgress()){panX+=cx-lastPanX;panY+=cy-lastPanY;}lastPanX=cx;lastPanY=cy;getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;}
            if(navigating){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){navigating=false;pointerDown=false;getParent().requestDisallowInterceptTouchEvent(false);}return true;}
            float x = (event.getX() - left) / viewScale / document.getInt("width"), y = (event.getY() - top) / viewScale / document.getInt("height");
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pointerDown=true;
                if (x < 0 || x > 1 || y < 0 || y > 1){pointerDown=false;return false;}
                getParent().requestDisallowInterceptTouchEvent(true); downX = x; downY = y;
                if("eyedropper".equals(tool)){Bitmap sample=frameImage(frame);int sampled=sample.getPixel(Math.max(0,Math.min(sample.getWidth()-1,(int)(x*sample.getWidth()))),Math.max(0,Math.min(sample.getHeight()-1,(int)(y*sample.getHeight()))));if((sampled>>>24)==0)sampled=SketchDocument.color(document.getString("background"));if(listener!=null)listener.onColorPicked(String.format(java.util.Locale.ROOT,"#%08X",sampled));getParent().requestDisallowInterceptTouchEvent(false);return true;}
                if("pan".equals(tool)){lastPanX=event.getX();lastPanY=event.getY();return true;}
                if("nodes".equals(tool)){if(selectedId==null)selectedId=SketchFrameRenderer.hitTest(document,frame,x,y,.035);if(selectedId!=null){layerId=SketchDocument.owner(document,selectedId).getString("id");SketchEditOps.requireUnlocked(SketchDocument.layer(document,layerId));chooseNode(x,y);}invalidate();return true;}
                if ("select".equals(tool)) {
                    selectedId = SketchFrameRenderer.hitTest(document, frame, x, y, .018);
                    dragBase = selectedId == null ? null : SketchDocument.transformAt(SketchDocument.object(document, selectedId), frame);
                    if (selectedId != null) layerId = SketchDocument.owner(document, selectedId).getString("id");
                    if (listener != null) listener.onSelection(selectedId); invalidate(); return true;
                }
                SketchEditOps.requireUnlocked(SketchDocument.layer(document, layerId)); gesture = new JSONArray(); addPoint(event.getX(), event.getY(), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getPressure():1); invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if("nodes".equals(tool)){moveNode(x,y);invalidate();return true;}
                if("pan".equals(tool)){panX+=event.getX()-lastPanX;panY+=event.getY()-lastPanY;lastPanX=event.getX();lastPanY=event.getY();invalidate();return true;}
                if ("select".equals(tool) && selectedId != null) {
                    Matrix inverse = new Matrix(); SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), document.getInt("width"), document.getInt("height")).invert(inverse);
                    float[] positions = {downX * document.getInt("width"), downY * document.getInt("height"), x * document.getInt("width"), y * document.getInt("height")}; inverse.mapPoints(positions);
                    dragTransform = SketchDocument.copy(dragBase).put("x", Math.max(-4, Math.min(4, dragBase.getDouble("x") + (positions[2] - positions[0]) / document.getInt("width"))))
                            .put("y", Math.max(-4, Math.min(4, dragBase.getDouble("y") + (positions[3] - positions[1]) / document.getInt("height"))));
                } else if (!"select".equals(tool) && gesture.length() > 0) {
                    if ("stroke".equals(tool) || "eraser".equals(tool)) for (int i = 0; i < event.getHistorySize(); i++) addPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getHistoricalPressure(i):1);
                    addPoint(event.getX(), event.getY(), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getPressure():1);
                }
                invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                pointerDown=false;
                performClick(); getParent().requestDisallowInterceptTouchEvent(false);
                if("pan".equals(tool)||"eyedropper".equals(tool))return true;
                if("nodes".equals(tool)){if(nodePreview!=null){JSONObject patch=new JSONObject();String field=nodePreview.has("commands")?"commands":"points";patch.put(field,nodePreview.get(field));listener.onOperation(new JSONObject().put("type","update_object").put("objectId",selectedId).put("patch",patch));}nodePreview=null;renderer.setGeometryPreview(null);invalidate();return true;}
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
                    addPoint(event.getX(), event.getY(), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getPressure():1);
                    if ("polygon".equals(tool)||"curve".equals(tool)) { polygon.put(gesture.getJSONArray(0)); gesture = new JSONArray();if("curve".equals(tool)&&polygon.length()==3)finishPolygon();else if(listener!=null)listener.onMessage("curve".equals(tool)?"Curve: tap start, control and end":"Polygon: tap vertices, then Finish path"); }
                    else commitGesture();
                }
                gesture = new JSONArray(); invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { pointerDown=false;gesture = new JSONArray(); dragTransform = null;nodePreview=null;renderer.setGeometryPreview(null); getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true; }
        } catch (Exception e) {pointerDown=false; gesture = new JSONArray(); if (listener != null) listener.onMessage(e.getMessage()); }
        return true;
    }

    private Matrix objectInverse() throws Exception {int w=document.getInt("width"),h=document.getInt("height");Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document,layerId),frame),w,h),SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.object(document,selectedId),frame),w,h));Matrix inverse=new Matrix();if(!world.invert(inverse))throw SketchDocument.bad("Object transform cannot be inverted");return inverse;}
    private void chooseNode(float x,float y) throws Exception {JSONObject object=SketchDocument.object(document,selectedId);nodeCommand=-1;nodePreview=null;if(!object.has("points")&&!object.has("commands"))return;float[] p={x*document.getInt("width"),y*document.getInt("height")};objectInverse().mapPoints(p);double nearest=.06;JSONArray geometry=object.has("commands")?object.getJSONArray("commands"):object.getJSONArray("points");for(int i=0;i<geometry.length();i++){JSONArray command=geometry.getJSONArray(i);int start=object.has("commands")?1:0,stop=object.has("commands")?command.length():2;for(int k=start;k<stop;k+=2){double distance=Math.hypot(command.getDouble(k)-p[0]/document.getDouble("width"),command.getDouble(k+1)-p[1]/document.getDouble("height"));if(distance<nearest){nearest=distance;nodeCommand=i;nodeOffset=k;}}}if(nodeCommand>=0)nodePreview=SketchDocument.copy(object);}
    private void moveNode(float x,float y) throws Exception {if(nodePreview==null)return;float[] p={x*document.getInt("width"),y*document.getInt("height")};objectInverse().mapPoints(p);JSONArray geometry=nodePreview.has("commands")?nodePreview.getJSONArray("commands"):nodePreview.getJSONArray("points");geometry.getJSONArray(nodeCommand).put(nodeOffset,Math.max(0,Math.min(1,p[0]/document.getDouble("width")))).put(nodeOffset+1,Math.max(0,Math.min(1,p[1]/document.getDouble("height"))));renderer.setGeometryPreview(nodePreview);}
    private void addPoint(float screenX, float screenY, float pressure) throws Exception {
        if (gesture.length() >= 8192) throw SketchDocument.bad("Touch stroke reaches 8192 points; lift to continue");
        Matrix inverse = new Matrix(); SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), document.getInt("width"), document.getInt("height")).invert(inverse);
        float[] p = {(screenX - left) / viewScale, (screenY - top) / viewScale}; inverse.mapPoints(p);
        double x = Math.max(0, Math.min(1, p[0] / document.getInt("width"))), y = Math.max(0, Math.min(1, p[1] / document.getInt("height")));
        if (gesture.length() > 0) { JSONArray last = gesture.getJSONArray(gesture.length() - 1); if (Math.hypot(x - last.getDouble(0), y - last.getDouble(1)) < .0002 && Math.abs(pressure - last.getDouble(2)) < .01) return; }
        if(gesture.length()>0&&("stroke".equals(tool)||"eraser".equals(tool))){JSONArray prior=gesture.getJSONArray(gesture.length()-1);x=prior.getDouble(0)*stabilization+x*(1-stabilization);y=prior.getDouble(1)*stabilization+y*(1-stabilization);}
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
        if(symmetry&&( "stroke".equals(tool)||"eraser".equals(tool))){JSONArray mirrored=new JSONArray();for(int i=0;i<gesture.length();i++){JSONArray p=gesture.getJSONArray(i);mirrored.put(new JSONArray().put(1-p.getDouble(0)).put(p.getDouble(1)).put(p.getDouble(2)));}JSONObject twin=SketchDocument.copy(object).put("id",UUID.randomUUID().toString()).put("points",mirrored);listener.onOperation(new JSONObject().put("type","mirror_gesture").put("layerId",layerId).put("object",object).put("mirror",twin));}
        else listener.onOperation(new JSONObject().put("type", "add_object").put("layerId", layerId).put("object", object));
    }
    public void finishPolygon() {
        try { if (polygon.length() < 3) throw SketchDocument.bad("A polygon needs at least three vertices");
            JSONObject shape;if("curve".equals(tool)){JSONArray a=polygon.getJSONArray(0),b=polygon.getJSONArray(1),c=polygon.getJSONArray(2);shape=baseObject("path").put("commands",new JSONArray().put(new JSONArray().put("M").put(a.getDouble(0)).put(a.getDouble(1))).put(new JSONArray().put("Q").put(b.getDouble(0)).put(b.getDouble(1)).put(c.getDouble(0)).put(c.getDouble(1))));}else shape=baseObject("polygon").put("points",polygon);
            listener.onOperation(new JSONObject().put("type", "add_object").put("layerId", layerId).put("object", shape));
            polygon = new JSONArray(); invalidate();
        } catch (Exception e) { if (listener != null) listener.onMessage(e.getMessage()); }
    }
    private JSONObject baseObject(String kind) throws Exception { JSONObject o=new JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind).put("frame", frame).put("exposure", 1).put("color", color).put("width", brushWidth).put("fill", fill);if(brushStyle!=null&&("stroke".equals(kind)||"eraser".equals(kind)))o.put("brush",brushStyle);return o; }
    @Override public boolean performClick() { super.performClick(); return true; }
}
