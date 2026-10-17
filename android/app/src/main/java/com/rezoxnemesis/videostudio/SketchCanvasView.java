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
        default void onSampleRequested(double x,double y,int frame) {}
        default PaintCommitter beginPaintGesture(String sketchId,long revision,int frame,String objectId){return this::onOperation;}
    }
    public interface PaintCommitter {
        void commit(JSONObject operation)throws Exception;
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
    public void setPresentation(String objectId,float fraction){if(objectId==null)hideAgentHand();liveObject=objectId;liveFraction=fraction;if(renderer!=null)renderer.setPresentation(objectId,fraction);invalidate();}
    private JSONObject brushStyle;
    private JSONObject paintBrush,paintClip,cloneSource;
    private boolean paintAlphaLock;
    private String paintTarget,paintStrokeId;
    private PaintCommitter paintCommitter;
    private JSONObject gesturePaintBrush,gesturePaintClip,gestureCloneSource;
    private boolean gestureAlphaLock;
    private int gestureFrame,gestureDabs;
    private long gestureVisits;
    private final java.util.LinkedHashMap<String,JSONObject> pendingPaintPreviews=new java.util.LinkedHashMap<>();
    private SketchPaintReplay.Frame paintPublication;
    private boolean agentHandEnabled=true;
    private SketchAgentHand.State agentHand=SketchAgentHand.State.hidden();
    private final Object touchPreviewLock=new Object();
    private static final class TouchPreviewRequest {
        final JSONObject document,operation,canvasCrop;
        final SketchPaintReplay.Viewport paintViewport;
        final int frame,epoch;final long sequence;
        TouchPreviewRequest(JSONObject doc,JSONObject operation,JSONObject canvasCrop,SketchPaintReplay.Viewport viewport,int frame,int epoch,long sequence){this.document=doc;this.operation=operation;this.canvasCrop=canvasCrop;paintViewport=viewport;this.frame=frame;this.epoch=epoch;this.sequence=sequence;}
    }
    private TouchPreviewRequest newestTouchPreview;
    private boolean touchPreviewWorkerRunning;
    private SketchPaintStore touchPaintCache;
    private String touchPaintOwner;
    private volatile int touchPreviewEpoch;
    private volatile long touchPreviewSequence;
    private RasterFrame touchComposite;
    private String touchCompositeStroke;
    private int touchCompositeFrame;
    private long touchCompositeRevision;
    private JSONObject touchCompositeDocument;
    private float zoom=1,panX,panY,lastPanX,lastPanY,stabilization=.2f;
    private boolean navigating,grid,symmetry,pointerDown;
    private JSONObject symmetryConfiguration;
    private int onionRange=1,onionAlpha=48;
    private final ScaleGestureDetector scaling;
    private static final long VIEWPORT_BYTES=12L*1024*1024;
    private static final class RasterFrame {final Bitmap bitmap;final JSONObject crop;RasterFrame(Bitmap b,JSONObject c){bitmap=b;crop=c;}}
    private final java.util.LinkedHashMap<Integer,RasterFrame> frameCache=new java.util.LinkedHashMap<>(8,.75f,true);
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService rasterWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private int rasterGeneration;
    private String rasterKey="";
    private boolean disposed,smallVector;
    private JSONObject previousBookOnion,nextBookOnion;
    private volatile int requestedRasterGeneration;
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
        // Store/session documents are immutable snapshots. Image/paint preparation stays on the worker.
        boolean same=document!=null&&document.getString("id").equals(doc.getString("id"));
        boolean retainGesture=same&&pointerDown&&isPaintTool()&&paintCommitter!=null&&paintTarget!=null&&frame==gestureFrame;
        if(retainGesture)try{JSONObject target=SketchDocument.object(doc,paintTarget);retainGesture="paint".equals(target.optString("kind"))&&SketchFrameRenderer.exposed(target,gestureFrame);}catch(Exception removed){retainGesture=false;}
        document=doc;invalidateTouchPreview(!same);detachPaintPublication();++rasterGeneration;requestedRasterGeneration=rasterGeneration;rasterKey="";
        if(renderer!=null){renderer.close();renderer=null;}
        smallVector=isSmallVector(doc);
        if(smallVector)renderer=new SketchFrameRenderer(getContext(),doc);
        if(!same){zoom=1;panX=panY=0;pendingPaintPreviews.clear();previousBookOnion=null;nextBookOnion=null;}
        if(!same)clearFrameCache();restoreDocumentCursor(doc,retainGesture);if(retainGesture)requestTouchPreview();
    }
    void setCommittedPaintDocument(JSONObject doc)throws Exception {
        document=doc;++rasterGeneration;requestedRasterGeneration=rasterGeneration;rasterKey="";smallVector=false;clearFrameCache();restoreDocumentCursor(doc);
    }
    private static boolean isSmallVector(JSONObject doc)throws Exception{
        if(doc.getInt("width")>1280||doc.getInt("height")>1280)return false;
        int count=0,geometryCount=0;JSONArray layers=doc.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");count+=objects.length();if(count>64)return false;
            for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);String kind=object.getString("kind");if("image".equals(kind)||"paint".equals(kind))return false;JSONArray points=object.optJSONArray("points"),commands=object.optJSONArray("commands");geometryCount+=(points==null?0:points.length())+(commands==null?0:commands.length());if(geometryCount>2048)return false;}}
        return true;
    }
    /** A validated worker snapshot transfers ownership without UI parsing/indexing. */
    void setPreparedDocument(SketchLivePreparation.Prepared prepared) throws Exception {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            throw new IllegalStateException("Canvas adoption requires the UI thread");
        boolean same=document!=null&&document.getString("id").equals(prepared.document.getString("id"));
        detachPaintPublication();++rasterGeneration;requestedRasterGeneration=rasterGeneration;rasterKey="";smallVector=false;
        invalidateTouchPreview(true);
        SketchFrameRenderer next=prepared.takeRenderer();
        if(renderer!=null)renderer.close();renderer=next;document=prepared.document;
        if(!same){zoom=1;panX=panY=0;}clearFrameCache();restoreDocumentCursor(document);
    }
    private void restoreDocumentCursor(JSONObject doc)throws Exception{restoreDocumentCursor(doc,false);}
    private void restoreDocumentCursor(JSONObject doc,boolean retainGesture) throws Exception {
        frame = Math.min(frame, doc.getInt("frameCount") - 1);
        try { SketchDocument.layer(document, layerId); } catch (Exception missing) { layerId = document.getJSONArray("layers").getJSONObject(0).getString("id"); }
        if (selectedId != null) try { SketchDocument.object(document, selectedId); } catch (Exception missing) { selectedId = null; }
        dragTransform = null;if(!retainGesture){gesture=new JSONArray();pointerDown=false;paintCommitter=null;paintTarget=null;}invalidate();
    }
    public void setFrame(int frame) { if(this.frame!=frame){invalidateTouchPreview(true);rasterKey="";++rasterGeneration;requestedRasterGeneration=rasterGeneration;}this.frame = frame; gesture = new JSONArray(); polygon = new JSONArray(); dragTransform = null; invalidate(); }
    public int getFrame() { return frame; }
    public String getLayerId() { return layerId; }
    public void setLayerId(String id) { layerId = id; selectedId = null; if (listener != null) listener.onSelection(null); invalidate(); }
    public String getSelectedId() { return selectedId; }
    public void setTool(String tool) { invalidateTouchPreview(true);this.tool = tool;paintCommitter=null;paintTarget=null; gesture = new JSONArray(); polygon = new JSONArray(); invalidate(); }
    public void setBrush(String color, float width, boolean fill) { this.color = color; this.brushWidth = width; this.fill = fill; invalidate(); }
    public void setOnion(boolean onion) { this.onion = onion;rasterKey="";invalidate(); }
    public void setBrushStyle(JSONObject brush,float smoothing){this.brushStyle=brush;this.stabilization=Math.max(0,Math.min(.9f,smoothing));invalidate();}
    public void setAgentHandEnabled(boolean enabled){agentHandEnabled=enabled;invalidate();}
    public void setAgentHand(SketchAgentHand.State state){agentHand=state==null?SketchAgentHand.State.hidden():state;invalidate();}
    public void hideAgentHand(){agentHand=SketchAgentHand.State.hidden();invalidate();}
    public void setBookOnionDocuments(String owner,JSONObject previous,JSONObject next)throws Exception{
        if(document==null||!owner.equals(document.getString("id")))return;
        previousBookOnion=previous;nextBookOnion=next;rasterKey="";invalidate();
    }
    public void setPaintBrush(JSONObject brush,boolean alphaLock,JSONObject clip,JSONObject source)throws Exception{
        paintBrush=SketchDocument.copy(brush);paintAlphaLock=alphaLock;paintClip=clip==null?null:SketchDocument.copy(clip);cloneSource=source==null?null:SketchDocument.copy(source);invalidate();
    }
    public void acknowledgePaint(JSONArray operations){if(operations==null)return;for(int i=0;i<operations.length();i++){JSONObject op=operations.optJSONObject(i);if(op!=null)pendingPaintPreviews.remove(op.optString("strokeId"));}invalidate();}
    public void setPaintPresentation(String objectId,SketchPaintReplay.Frame next){
        if(renderer!=null)renderer.setPaintPresentation(null,null);
        SketchPaintReplay.Frame old=paintPublication;paintPublication=next;
        if(renderer!=null&&next!=null)renderer.setPaintPresentation(objectId,next);
        if(old!=null&&old!=next)old.close();invalidate();
    }
    private void detachPaintPublication(){if(renderer!=null)renderer.setPaintPresentation(null,null);SketchPaintReplay.Frame old=paintPublication;paintPublication=null;if(old!=null)old.close();}
    public SketchPaintReplay.Viewport paintViewport(String objectId){return paintViewport(objectId,frame);}
    public SketchPaintReplay.Viewport paintViewport(String objectId,int frameIndex){
        try{JSONObject crop=visibleCrop();if(crop==null)return new SketchPaintReplay.Viewport(viewportEdge(),null);
            JSONObject object=SketchDocument.object(document,objectId),layer=SketchDocument.owner(document,objectId);int w=document.getInt("width"),h=document.getInt("height");
            Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,frameIndex),w,h),SketchFrameRenderer.matrix(SketchDocument.transformAt(object,frameIndex),w,h));Matrix inverse=new Matrix();if(!world.invert(inverse))return new SketchPaintReplay.Viewport(viewportEdge(),null);
            RectF local=new RectF((float)crop.getDouble("x")*w,(float)crop.getDouble("y")*h,(float)(crop.getDouble("x")+crop.getDouble("width"))*w,(float)(crop.getDouble("y")+crop.getDouble("height"))*h);inverse.mapRect(local);
            double x=Math.max(0,local.left/w),y=Math.max(0,local.top/h),right=Math.min(1,local.right/w),bottom=Math.min(1,local.bottom/h);if(right<=x||bottom<=y)return new SketchPaintReplay.Viewport(viewportEdge(),null);
            return new SketchPaintReplay.Viewport(viewportEdge(),new JSONObject().put("x",x).put("y",y).put("width",right-x).put("height",bottom-y));
        }catch(Exception invalid){return new SketchPaintReplay.Viewport(viewportEdge(),null);}
    }
    /** Compatibility entry point: enabling the old mirror setting starts with vertical midpoint symmetry. */
    public void setGuides(boolean grid,boolean symmetry){
        this.grid=grid;this.symmetry=symmetry;
        if(symmetry&&symmetryConfiguration==null)try{symmetryConfiguration=SketchSymmetry.configuration(new JSONObject().put("mode","vertical"));}catch(Exception invalid){throw new IllegalStateException(invalid);}
        invalidate();
    }
    public void setSymmetry(JSONObject configuration)throws Exception {
        if(pointerDown)throw SketchDocument.bad("Lift the pen before changing symmetry");
        symmetryConfiguration=configuration==null?null:SketchSymmetry.configuration(configuration);symmetry=configuration!=null;invalidate();
    }
    public void setOnionOptions(int range,int alpha){onionRange=Math.max(1,Math.min(3,range));onionAlpha=Math.max(0,Math.min(128,alpha));invalidate();}
    public void resetView(){zoom=1;panX=panY=0;invalidate();}
    public void focusSelection() throws Exception {
        if(document==null||selectedId==null)throw SketchDocument.bad("Select an object to focus");
        JSONObject object=SketchShapeKeys.sampled(SketchDocument.object(document,selectedId),frame),layer=SketchDocument.owner(document,selectedId);int w=document.getInt("width"),h=document.getInt("height");RectF bounds;
        if(object.has("bounds"))bounds=SketchFrameRenderer.bounds(object.getJSONObject("bounds"),w,h);
        else if(object.has("commands")){bounds=new RectF();SketchFrameRenderer.path(object.getJSONArray("commands"),w,h).computeBounds(bounds,true);}
        else{bounds=new RectF(Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE);JSONArray points=object.getJSONArray("points");for(int n=0;n<points.length();n++){JSONArray p=points.getJSONArray(n);float x=(float)p.getDouble(0)*w,y=(float)p.getDouble(1)*h;bounds.left=Math.min(bounds.left,x);bounds.right=Math.max(bounds.right,x);bounds.top=Math.min(bounds.top,y);bounds.bottom=Math.max(bounds.bottom,y);}}
        Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,frame),w,h),SketchFrameRenderer.matrix(SketchDocument.transformAt(object,frame),w,h));world.mapRect(bounds);
        float fit=Math.min(getWidth()/(float)w,getHeight()/(float)h);if(fit<=0)return;zoom=Math.max(1,Math.min(12,Math.min(getWidth()*.85f/Math.max(1,bounds.width()),getHeight()*.85f/Math.max(1,bounds.height()))/fit));panX=-(bounds.centerX()-w/2f)*fit*zoom;panY=-(bounds.centerY()-h/2f)*fit*zoom;invalidate();
    }
    public boolean isDrawing(){return pointerDown||nodePreview!=null||gesture.length()>0||polygon.length()>0||dragTransform!=null||navigating;}
    public float getZoom(){return zoom;}
    public void selectObject(String id){selectedId=id;invalidate();}
    private void clearFrameCache(){for(RasterFrame b:frameCache.values())b.bitmap.recycle();frameCache.clear();cacheBytes=0;}
    private int viewportEdge(){return Math.max(128,Math.min(1024,Math.max(getWidth(),getHeight())));}
    private JSONObject visibleCrop()throws Exception {
        if(document==null||viewScale<=0)return null;int w=document.getInt("width"),h=document.getInt("height");
        double x=Math.max(0,-left/viewScale/w),y=Math.max(0,-top/viewScale/h),right=Math.min(1,(getWidth()-left)/viewScale/w),bottom=Math.min(1,(getHeight()-top)/viewScale/h);
        if(right<=x||bottom<=y)return null;
        return new JSONObject().put("x",x).put("y",y).put("width",right-x).put("height",bottom-y);
    }
    private void requestRaster()throws Exception {
        if(disposed||document==null||liveObject!=null||getWidth()<1||getHeight()<1)return;
        JSONObject crop=visibleCrop();String key=document.getString("id")+"/"+document.getLong("revision")+"/"+frame+"/"+viewportEdge()+"/"+(crop==null?"full":crop.toString())+"/"+onion+"/"+onionRange+"/"+(previousBookOnion==null?"":previousBookOnion.optString("id")+":"+previousBookOnion.optLong("revision"))+"/"+(nextBookOnion==null?"":nextBookOnion.optString("id")+":"+nextBookOnion.optLong("revision"))+"/"+(dragTransform==null?"":dragTransform.toString())+"/"+(nodePreview==null?"":nodePreview.toString());
        if(key.equals(rasterKey))return;rasterKey=key;int generation=++rasterGeneration;requestedRasterGeneration=generation;
        JSONObject source=document,previewTransform=dragTransform,previewGeometry=nodePreview,previous=onion?previousBookOnion:null,next=onion?nextBookOnion:null;String previewId=selectedId;int selectedFrame=frame,edge=viewportEdge(),range=onion?onionRange:0;
        rasterWorker.execute(()->{
            java.util.LinkedHashMap<Integer,RasterFrame> prepared=new java.util.LinkedHashMap<>();SketchFrameRenderer ready=null;long bytes=0;
            try{
                if(disposed||generation!=requestedRasterGeneration)return;
                JSONObject renderDocument=SketchDocument.copy(source);
                if(previewId!=null&&previewTransform!=null)SketchDocument.object(renderDocument,previewId).put("transform",SketchDocument.copy(previewTransform)).put("keys",new JSONArray());
                if(previewGeometry!=null){JSONObject target=SketchDocument.object(renderDocument,previewGeometry.getString("id"));String geometryField=previewGeometry.has("commands")?"commands":"points";target.put(geometryField,previewGeometry.get(geometryField));target.remove("shapeKeys");}
                ready=new SketchFrameRenderer(getContext(),renderDocument);
                ready.prepareVectorGeometry();
                JSONObject[] neighbors={previous,next};
                for(int side=0;side<neighbors.length;side++){
                    JSONObject neighbor=neighbors[side];if(neighbor==null)continue;
                    if(disposed||generation!=requestedRasterGeneration)throw new InterruptedException("Viewport superseded");
                    try(SketchFrameRenderer ghost=new SketchFrameRenderer(getContext(),neighbor)){
                        Bitmap bitmap=ghost.render(0,false,Math.min(edge,512),crop);prepared.put(side==0?-1:-2,new RasterFrame(bitmap,crop));bytes+=bitmap.getAllocationByteCount();
                    }
                }
                for(int distance=range;distance>=0;distance--){int[] offsets=distance==0?new int[]{0}:new int[]{-distance,distance};for(int offset:offsets){
                    if(disposed||generation!=requestedRasterGeneration)throw new InterruptedException("Viewport superseded");int index=selectedFrame+offset;if(index<0||index>=source.getInt("frameCount"))continue;
                    Bitmap bitmap=ready.render(index,false,offset==0?edge:Math.min(edge,512),crop);
                    while(!prepared.isEmpty()&&bytes+bitmap.getAllocationByteCount()>VIEWPORT_BYTES){Integer oldest=prepared.keySet().iterator().next();Bitmap old=prepared.remove(oldest).bitmap;bytes-=old.getAllocationByteCount();old.recycle();}
                    prepared.put(index,new RasterFrame(bitmap,crop));bytes+=bitmap.getAllocationByteCount();
                }}
                // Only the worker uses this renderer: immutable bounded publications are the UI contract.
                ready.close();ready=null;long adoptedBytes=bytes;java.util.LinkedHashMap<Integer,RasterFrame> publication=prepared;
                ui.post(()->{if(disposed||generation!=requestedRasterGeneration){for(RasterFrame image:publication.values())image.bitmap.recycle();return;}clearFrameCache();frameCache.putAll(publication);cacheBytes=adoptedBytes;if(touchComposite!=null&&!pointerDown&&!pendingPaintPreviews.containsKey(touchCompositeStroke))clearTouchComposite();if(paintPublication!=null){detachPaintPublication();if(renderer!=null){renderer.close();renderer=null;}}invalidate();});prepared=null;
            }catch(InterruptedException cancelled){}
            catch(Exception|OutOfMemoryError failed){ui.post(()->{if(!disposed&&generation==requestedRasterGeneration&&listener!=null)listener.onMessage("Canvas preview: "+failed.getMessage());});}
            finally{if(ready!=null)ready.close();if(prepared!=null)for(RasterFrame image:prepared.values())image.bitmap.recycle();}
        });
    }
    private void drawRaster(Canvas canvas,RasterFrame image,int w,int h,Paint tint)throws Exception{
        if(image==null||image.bitmap.isRecycled())return;JSONObject c=image.crop;RectF target=c==null?new RectF(0,0,w,h):new RectF((float)c.getDouble("x")*w,(float)c.getDouble("y")*h,(float)(c.getDouble("x")+c.getDouble("width"))*w,(float)(c.getDouble("y")+c.getDouble("height"))*h);
        canvas.drawBitmap(image.bitmap,null,target,tint);
    }
    public void dispose() { if(disposed)return;disposed=true;hideAgentHand();invalidateTouchPreview(true);++rasterGeneration;requestedRasterGeneration=rasterGeneration;rasterWorker.execute(()->{if(touchPaintCache!=null){touchPaintCache.close();touchPaintCache=null;touchPaintOwner=null;}});rasterWorker.shutdown();detachPaintPublication();clearFrameCache();if(renderer!=null){renderer.close();renderer=null;} }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (document == null) return;
        try {
            int w = document.getInt("width"), h = document.getInt("height");
            viewScale = Math.min(getWidth() / (float) w, getHeight() / (float) h)*zoom;
            left = (getWidth() - w * viewScale) / 2+panX; top = (getHeight() - h * viewScale) / 2+panY;
            requestRaster();
            int save = canvas.save(); canvas.translate(left, top); canvas.scale(viewScale, viewScale); canvas.clipRect(0, 0, w, h);
            canvas.drawColor(SketchDocument.color(document.getString("background")));
            if(onion&&liveObject==null){
                Paint previousTint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);previousTint.setAlpha(onionAlpha);previousTint.setColorFilter(new PorterDuffColorFilter(0xffe27a83,PorterDuff.Mode.SRC_IN));drawRaster(canvas,frameCache.get(-1),w,h,previousTint);
                Paint nextTint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);nextTint.setAlpha(onionAlpha);nextTint.setColorFilter(new PorterDuffColorFilter(0xff4cbbdb,PorterDuff.Mode.SRC_IN));drawRaster(canvas,frameCache.get(-2),w,h,nextTint);
            }
            if(onion&&liveObject==null)for(int distance=onionRange;distance>=1;distance--)for(int offset:new int[]{-distance,distance}){
                int ghost=frame+offset;if(ghost<0||ghost>=document.getInt("frameCount"))continue;Paint tint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);tint.setAlpha(onionAlpha/distance);tint.setColorFilter(new PorterDuffColorFilter(offset<0?0xffe27a83:0xff4cbbdb,PorterDuff.Mode.SRC_IN));drawRaster(canvas,frameCache.get(ghost),w,h,tint);
            }
            boolean actualTouch=touchComposite!=null&&touchCompositeFrame==frame&&document.getString("id").equals(touchCompositeDocument.optString("id"));
            if(actualTouch)drawRaster(canvas,touchComposite,w,h,null);
            else if((liveObject!=null||paintPublication!=null)&&renderer!=null)renderer.draw(canvas,frame,false,selectedId,dragTransform);
            else if((smallVector&&(frameCache.get(frame)==null||dragTransform!=null||nodePreview!=null))&&renderer!=null)renderer.draw(canvas,frame,false,selectedId,dragTransform);
            else drawRaster(canvas,frameCache.get(frame),w,h,null);
            for(JSONObject preview:pendingPaintPreviews.values())if(!actualTouch||!preview.optString("strokeId").equals(touchCompositeStroke))drawPaintPreview(canvas,preview,w,h);
            if(grid||symmetry){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1/viewScale);paint.setColor(0x553fa4c9);if(grid){for(int i=1;i<10;i++){canvas.drawLine(w*i/10f,0,w*i/10f,h,paint);canvas.drawLine(0,h*i/10f,w,h*i/10f,paint);}}if(symmetry)drawSymmetryGuides(canvas,w,h);}
            if((gesture.length()>0||polygon.length()>0)&&(!actualTouch||!isPaintTool()||!java.util.Objects.equals(paintStrokeId,touchCompositeStroke)))drawGesture(canvas,w,h);
            if (selectedId != null && isEnabled() && liveObject == null) drawSelection(canvas, w, h);
            canvas.restoreToCount(save);
            if(agentHandEnabled&&agentHand.visible&&agentHand.x>=0&&agentHand.x<=1&&agentHand.y>=0&&agentHand.y<=1)
                SketchAgentHand.draw(canvas,agentHand,left+(float)agentHand.x*w*viewScale,top+(float)agentHand.y*h*viewScale,StudioUi.dp(getContext(),28));
        } catch (Exception e) { if (listener != null) listener.onMessage(e.getMessage()); }
    }

    private void drawGesture(Canvas canvas, int w, int h) throws Exception {
        if(isPaintTool()&&paintTarget!=null&&gesturePaintBrush!=null){JSONObject operation=new JSONObject().put("objectId",paintTarget).put("brush",gesturePaintBrush).put("points",gesture);drawPaintPreview(canvas,operation,w,h);return;}
        int save = canvas.save(); canvas.concat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), w, h));
        paint.setColor("eraser".equals(tool) ? 0xAAE77989 : SketchDocument.color(color)); paint.setStrokeWidth(brushWidth * Math.min(w, h));
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        if ("stroke".equals(tool) || "eraser".equals(tool)) {
            JSONArray copies;
            try{copies=symmetry?SketchSymmetry.copies(gesture,symmetryConfiguration,w,h):new JSONArray().put(gesture);}
            catch(IllegalArgumentException outside){
                // Invalid previews stay visibly provisional; lifting reports the rejected complete gesture.
                paint.setColor(0xAAE77989);copies=new JSONArray().put(gesture);
            }
            for(int i=0;i<copies.length();i++)SketchBrushes.draw(canvas,copies.getJSONArray(i),w,h,brushWidth*Math.min(w,h),paint,brushStyle);
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

    private void drawSymmetryGuides(Canvas canvas,int w,int h)throws Exception {
        if(symmetryConfiguration==null)return;
        int save=canvas.save();canvas.concat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document,layerId),frame),w,h));
        paint.setColor(0x998ed4ac);paint.setStyle(Paint.Style.STROKE);
        float cx=(float)symmetryConfiguration.getDouble("centerX")*w,cy=(float)symmetryConfiguration.getDouble("centerY")*h;
        String mode=symmetryConfiguration.getString("mode");
        if("vertical".equals(mode)||"both".equals(mode))canvas.drawLine(cx,0,cx,h,paint);
        if("horizontal".equals(mode)||"both".equals(mode))canvas.drawLine(0,cy,w,cy,paint);
        if("radial".equals(mode)){
            float radius=(float)Math.hypot(w,h);int count=symmetryConfiguration.getInt("segments");
            for(int i=0;i<count;i++){double angle=2*Math.PI*i/count;canvas.drawLine(cx,cy,cx+(float)Math.cos(angle)*radius,cy+(float)Math.sin(angle)*radius,paint);}
        }
        canvas.drawCircle(cx,cy,4/viewScale,paint);canvas.restoreToCount(save);
    }
    private void drawSelection(Canvas canvas, int w, int h) throws Exception {
        JSONObject object = SketchShapeKeys.sampled(SketchDocument.object(document, selectedId),frame), layer = SketchDocument.owner(document, selectedId);
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
        if(document==null)return false;
        if(!isEnabled()&&!(event.getPointerCount()>1||navigating||"pan".equals(tool)))return false;
        try {
            scaling.onTouchEvent(event);
            if(event.getPointerCount()>1){float cx=(event.getX(0)+event.getX(1))/2,cy=(event.getY(0)+event.getY(1))/2;if(!navigating||event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){navigating=true;invalidateTouchPreview(true);pointerDown=false;paintCommitter=null;paintTarget=null;gesture=new JSONArray();dragTransform=null;lastPanX=cx;lastPanY=cy;}else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!scaling.isInProgress()){panX+=cx-lastPanX;panY+=cy-lastPanY;}lastPanX=cx;lastPanY=cy;getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;}
            if(navigating){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){navigating=false;pointerDown=false;getParent().requestDisallowInterceptTouchEvent(false);}return true;}
            float x = (event.getX() - left) / viewScale / document.getInt("width"), y = (event.getY() - top) / viewScale / document.getInt("height");
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pointerDown=true;
                if (x < 0 || x > 1 || y < 0 || y > 1){pointerDown=false;return false;}
                getParent().requestDisallowInterceptTouchEvent(true); downX = x; downY = y;
                if("eyedropper".equals(tool)){pointerDown=false;if(listener!=null)listener.onSampleRequested(x,y,frame);getParent().requestDisallowInterceptTouchEvent(false);return true;}
                if("pan".equals(tool)){lastPanX=event.getX();lastPanY=event.getY();return true;}
                if("nodes".equals(tool)){if(selectedId==null)selectedId=SketchFrameRenderer.hitTest(document,frame,x,y,.035);if(selectedId!=null){layerId=SketchDocument.owner(document,selectedId).getString("id");SketchEditOps.requireUnlocked(SketchDocument.layer(document,layerId));chooseNode(x,y);}invalidate();return true;}
                if ("select".equals(tool)) {
                    selectedId = SketchFrameRenderer.hitTest(document, frame, x, y, .018);
                    dragBase = selectedId == null ? null : SketchDocument.transformAt(SketchDocument.object(document, selectedId), frame);
                    if (selectedId != null) layerId = SketchDocument.owner(document, selectedId).getString("id");
                    if (listener != null) listener.onSelection(selectedId); invalidate(); return true;
                }
                if(isPaintTool())beginPaintGesture();
                SketchEditOps.requireUnlocked(SketchDocument.layer(document, layerId)); gesture = new JSONArray(); addPoint(event.getX(), event.getY(), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getPressure():1);if(isPaintTool())requestTouchPreview();invalidate(); return true;
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
                    if ("stroke".equals(tool) || "eraser".equals(tool)||isPaintTool()) for (int i = 0; i < event.getHistorySize(); i++) addPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getHistoricalPressure(i):1);
                    addPoint(event.getX(), event.getY(), event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS?event.getPressure():1);
                }
                if(isPaintTool()&&gesture.length()>0)requestTouchPreview();invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                pointerDown=false;
                performClick(); getParent().requestDisallowInterceptTouchEvent(false);
                if("pan".equals(tool)||"eyedropper".equals(tool))return true;
                if("nodes".equals(tool)){
                    if(nodePreview!=null){
                        if(nodePreview.has("commands"))listener.onOperation(SketchShapeKeys.editOperation(SketchDocument.object(document,selectedId),frame,nodePreview.getJSONArray("commands")));
                        else listener.onOperation(new JSONObject().put("type","update_object").put("objectId",selectedId).put("patch",new JSONObject().put("points",nodePreview.getJSONArray("points"))));
                    }
                    nodePreview=null;if(renderer!=null)renderer.setGeometryPreview(null);invalidate();return true;
                }
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
                    else if(isPaintTool()){requestTouchPreview();commitPaintGesture();}else commitGesture();
                }
                gesture = new JSONArray(); invalidate(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { invalidateTouchPreview(true);pointerDown=false;gesture = new JSONArray(); dragTransform = null;nodePreview=null;if(renderer!=null)renderer.setGeometryPreview(null); getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true; }
        } catch (Exception e) {invalidateTouchPreview(true);pointerDown=false;paintCommitter=null;paintTarget=null; gesture = new JSONArray(); if (listener != null) listener.onMessage(e.getMessage()); }
        return true;
    }

    private Matrix objectInverse() throws Exception {int w=document.getInt("width"),h=document.getInt("height");Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document,layerId),frame),w,h),SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.object(document,selectedId),frame),w,h));Matrix inverse=new Matrix();if(!world.invert(inverse))throw SketchDocument.bad("Object transform cannot be inverted");return inverse;}
    private void chooseNode(float x,float y) throws Exception {JSONObject object=SketchShapeKeys.sampled(SketchDocument.object(document,selectedId),frame);nodeCommand=-1;nodePreview=null;if(!object.has("points")&&!object.has("commands"))return;float[] p={x*document.getInt("width"),y*document.getInt("height")};objectInverse().mapPoints(p);double nearest=.06;JSONArray geometry=object.has("commands")?object.getJSONArray("commands"):object.getJSONArray("points");for(int i=0;i<geometry.length();i++){JSONArray command=geometry.getJSONArray(i);int start=object.has("commands")?1:0,stop=object.has("commands")?command.length():2;for(int k=start;k<stop;k+=2){double distance=Math.hypot(command.getDouble(k)-p[0]/document.getDouble("width"),command.getDouble(k+1)-p[1]/document.getDouble("height"));if(distance<nearest){nearest=distance;nodeCommand=i;nodeOffset=k;}}}if(nodeCommand>=0)nodePreview=SketchDocument.copy(object);}
    private void moveNode(float x,float y) throws Exception {if(nodePreview==null)return;float[] p={x*document.getInt("width"),y*document.getInt("height")};objectInverse().mapPoints(p);JSONArray geometry=nodePreview.has("commands")?nodePreview.getJSONArray("commands"):nodePreview.getJSONArray("points");geometry.getJSONArray(nodeCommand).put(nodeOffset,Math.max(0,Math.min(1,p[0]/document.getDouble("width")))).put(nodeOffset+1,Math.max(0,Math.min(1,p[1]/document.getDouble("height"))));if(renderer!=null)renderer.setGeometryPreview(nodePreview);}
    private void addPoint(float screenX, float screenY, float pressure) throws Exception {
        if (gesture.length() >= 8192) throw SketchDocument.bad("Touch stroke reaches 8192 points; lift to continue");
        Matrix inverse = new Matrix();
        if(isPaintTool()&&paintTarget!=null){JSONObject object=SketchDocument.object(document,paintTarget);Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document,layerId),frame),document.getInt("width"),document.getInt("height")),SketchFrameRenderer.matrix(SketchDocument.transformAt(object,frame),document.getInt("width"),document.getInt("height")));if(!world.invert(inverse))throw SketchDocument.bad("Paint transform cannot be inverted");}
        else SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.layer(document, layerId), frame), document.getInt("width"), document.getInt("height")).invert(inverse);
        float[] p = {(screenX - left) / viewScale, (screenY - top) / viewScale}; inverse.mapPoints(p);
        double x = Math.max(0, Math.min(1, p[0] / document.getInt("width"))), y = Math.max(0, Math.min(1, p[1] / document.getInt("height")));
        if (gesture.length() > 0) { JSONArray last = gesture.getJSONArray(gesture.length() - 1); if (Math.hypot(x - last.getDouble(0), y - last.getDouble(1)) < .0002 && Math.abs(pressure - last.getDouble(2)) < .01) return; }
        if(gesture.length()>0&&("stroke".equals(tool)||"eraser".equals(tool))){double[] precise=SketchStrokePrecision.filter(gesture,x,y,stabilization,document.getInt("width"),document.getInt("height"));x=precise[0];y=precise[1];}
        if(isPaintTool())appendPaintPoint(x,y,Math.max(0,Math.min(1,pressure)));else gesture.put(new JSONArray().put(x).put(y).put(Math.max(0, Math.min(1, pressure))));
    }
    private boolean isPaintTool(){return "paint".equals(tool)||"paint_erase".equals(tool)||"clone".equals(tool);}
    private void beginPaintGesture()throws Exception{
        if(paintBrush==null)throw SketchDocument.bad("Choose Paint settings before painting");
        JSONObject target=null;
        if(selectedId!=null){JSONObject selected=SketchDocument.object(document,selectedId);if("paint".equals(selected.optString("kind"))&&SketchFrameRenderer.exposed(selected,frame))target=selected;}
        if(target==null){JSONArray objects=SketchDocument.layer(document,layerId).getJSONArray("objects");for(int i=objects.length()-1;i>=0;i--){JSONObject candidate=objects.getJSONObject(i);if("paint".equals(candidate.optString("kind"))&&SketchFrameRenderer.exposed(candidate,frame)){target=candidate;break;}}}
        if(target==null)throw SketchDocument.bad("Create or select a paint cel first: Brush / Text → Paint studio");
        paintTarget=target.getString("id");layerId=SketchDocument.owner(document,paintTarget).getString("id");selectedId=paintTarget;
        SketchEditOps.requireUnlocked(SketchDocument.layer(document,layerId));
        gesturePaintBrush=SketchDocument.copy(paintBrush).put("mode","paint_erase".equals(tool)?"erase":"clone".equals(tool)?"clone":"paint");
        gestureAlphaLock=paintAlphaLock;gesturePaintClip=paintClip==null?null:SketchDocument.copy(paintClip);gestureCloneSource=cloneSource==null?null:SketchDocument.copy(cloneSource);
        if("clone".equals(tool)&&gestureCloneSource==null)throw SketchDocument.bad("Choose an owned Clone source first");
        if("paint_erase".equals(tool)&&gestureAlphaLock)throw SketchDocument.bad("Turn off alpha lock to erase paint");
        gestureFrame=frame;gestureDabs=0;gestureVisits=0;paintStrokeId=UUID.randomUUID().toString();paintCommitter=listener==null?null:listener.beginPaintGesture(document.getString("id"),document.getLong("revision"),frame,paintTarget);
    }
    private long paintVisitBound(double a,double b)throws Exception{
        double size=gesturePaintBrush.getDouble("sizePx"),pressure=gesturePaintBrush.optBoolean("pressure",true)?Math.max(a,b):1,radius=size*pressure*.5;
        if(radius<=0)return 0;double horizontal=radius,vertical=radius;
        if("flat".equals(gesturePaintBrush.optString("tip","round"))){double radians=Math.toRadians(gesturePaintBrush.optDouble("angle",0)),cos=Math.abs(Math.cos(radians)),sin=Math.abs(Math.sin(radians));horizontal=radius*(cos+sin*.22);vertical=radius*(sin+cos*.22);}
        return (long)Math.ceil(horizontal*2+4)*(long)Math.ceil(vertical*2+4);
    }
    private int paintSegmentDabs(JSONArray last,double x,double y,double pressure)throws Exception{
        if(gesturePaintBrush.optBoolean("pressure",true)&&last.getDouble(2)==0&&pressure==0)return 0;
        double distance=Math.hypot((x-last.getDouble(0))*document.getInt("width"),(y-last.getDouble(1))*document.getInt("height"));
        if(distance==0&&last.getDouble(2)==pressure)return 0;
        double step=gesturePaintBrush.getDouble("sizePx")*gesturePaintBrush.optDouble("spacing",.2)*(gesturePaintBrush.optBoolean("pressure",true)?Math.max(.05,Math.min(last.getDouble(2),pressure)):1);
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,Math.ceil(distance/step)));
    }
    private void continuePaintChunk()throws Exception{
        JSONArray last=gesture.getJSONArray(gesture.length()-1);commitPaintGesture();gesture=new JSONArray().put(last);paintStrokeId=UUID.randomUUID().toString();gestureDabs=1;gestureVisits=paintVisitBound(last.getDouble(2),last.getDouble(2));
    }
    /** Constant-time segment estimates keep the UI free of native dab planning. */
    private void appendPaintPoint(double x,double y,double pressure)throws Exception{
        if(gesture.length()==0){gesture.put(new JSONArray().put(x).put(y).put(pressure));gestureDabs=1;gestureVisits=paintVisitBound(pressure,pressure);return;}
        int chunks=0;
        while(true){
            JSONArray last=gesture.getJSONArray(gesture.length()-1);int dabs=paintSegmentDabs(last,x,y,pressure);long perDab=paintVisitBound(last.getDouble(2),pressure),visits=perDab*dabs;
            if(gesture.length()<1024&&gestureDabs+dabs<=4000&&gestureVisits+visits<=16000000L){gesture.put(new JSONArray().put(x).put(y).put(pressure));gestureDabs+=dabs;gestureVisits+=visits;return;}
            if(++chunks>64)throw SketchDocument.bad("Your completed stroke segments are retained. Lift the pen and continue this very fine line.");
            long remaining=Math.min(4000-gestureDabs,perDab==0?4000:(16000000L-gestureVisits)/perDab);
            if(gesture.length()>=1024||remaining<2){continuePaintChunk();continue;}
            double fraction=Math.min(.9,remaining/(double)Math.max(1,dabs)*.9),cutX=last.getDouble(0)+(x-last.getDouble(0))*fraction,cutY=last.getDouble(1)+(y-last.getDouble(1))*fraction,cutPressure=last.getDouble(2)+(pressure-last.getDouble(2))*fraction;
            int cutDabs=paintSegmentDabs(last,cutX,cutY,cutPressure);gesture.put(new JSONArray().put(cutX).put(cutY).put(cutPressure));gestureDabs+=cutDabs;gestureVisits+=perDab*cutDabs;continuePaintChunk();
        }
    }
    private JSONObject paintOperation()throws Exception{
        JSONObject operation=new JSONObject().put("type","paint_stroke").put("objectId",paintTarget).put("strokeId",paintStrokeId).put("frameIndex",gestureFrame).put("points",new JSONArray(gesture.toString())).put("brush",SketchDocument.copy(gesturePaintBrush)).put("alphaLock",gestureAlphaLock);
        if(gesturePaintClip!=null)operation.put("clip",gesturePaintClip);if("clone".equals(gesturePaintBrush.optString("mode")))operation.put("cloneSource",gestureCloneSource);return operation;
    }
    private void commitPaintGesture()throws Exception{
        if(gesture.length()==0||paintCommitter==null)return;JSONObject operation=paintOperation();paintCommitter.commit(operation);pendingPaintPreviews.put(paintStrokeId,operation);
    }
    private void clearTouchComposite(){RasterFrame old=touchComposite;touchComposite=null;touchCompositeStroke=null;touchCompositeDocument=null;if(old!=null)old.bitmap.recycle();}
    private void invalidateTouchPreview(boolean detach){synchronized(touchPreviewLock){++touchPreviewEpoch;newestTouchPreview=null;}if(detach)clearTouchComposite();}
    private void requestTouchPreview()throws Exception{
        if(disposed||paintTarget==null||paintCommitter==null||gesture.length()==0||gesturePaintBrush==null)return;
        JSONObject operation=paintOperation();SketchPaintReplay.Viewport viewport=paintViewport(paintTarget,gestureFrame);JSONObject canvasCrop=visibleCrop();boolean start=false;
        synchronized(touchPreviewLock){newestTouchPreview=new TouchPreviewRequest(document,operation,canvasCrop,viewport,gestureFrame,touchPreviewEpoch,++touchPreviewSequence);if(!touchPreviewWorkerRunning){touchPreviewWorkerRunning=true;start=true;}}
        if(start)rasterWorker.execute(this::prepareTouchPreviews);
    }
    /** Only the newest uncommitted input waits; every admitted native save still retains its own intent. */
    private void prepareTouchPreviews(){
        SketchPaintStore store=touchPaintCache;String owner=touchPaintOwner;
        try{while(true){TouchPreviewRequest request;
            synchronized(touchPreviewLock){request=newestTouchPreview;newestTouchPreview=null;if(request==null||disposed)return;}
            Bitmap pixels=null,composite=null;SketchPaintReplay.Frame override=null;
            try{
                if(request.epoch!=touchPreviewEpoch)continue;
                String requestedOwner=request.document.getString("id");if(!requestedOwner.equals(owner)){if(store!=null)store.close();store=new SketchPaintStore(getContext(),requestedOwner);owner=requestedOwner;touchPaintCache=store;touchPaintOwner=owner;}
                pixels=store.renderGesture(request.document,request.operation,request.paintViewport.maxDimension,request.paintViewport.crop);
                override=SketchPaintReplay.Frame.owned(pixels,SketchPaintReplay.pixelCoveredCrop(request.paintViewport.crop,request.document.getInt("width"),request.document.getInt("height")));pixels=null;
                try(SketchFrameRenderer scene=new SketchFrameRenderer(getContext(),request.document)){
                    scene.setPaintPresentation(request.operation.getString("objectId"),override);
                    composite=scene.render(request.frame,false,request.paintViewport.maxDimension,request.canvasCrop);
                    scene.setPaintPresentation(null,null);
                }
                Bitmap publication=composite;composite=null;
                ui.post(()->{
                    if(disposed||request.epoch!=touchPreviewEpoch||request.sequence!=touchPreviewSequence||document==null||!request.document.optString("id").equals(document.optString("id"))||request.frame!=frame){publication.recycle();return;}
                    boolean currentGesture=pointerDown&&request.operation.optString("strokeId").equals(paintStrokeId),acceptedPending=pendingPaintPreviews.containsKey(request.operation.optString("strokeId"));
                    if(!currentGesture&&!acceptedPending){publication.recycle();return;}
                    clearTouchComposite();touchComposite=new RasterFrame(publication,request.canvasCrop);touchCompositeStroke=request.operation.optString("strokeId");touchCompositeFrame=request.frame;touchCompositeRevision=request.document.optLong("revision");touchCompositeDocument=request.document;invalidate();
                });
            }catch(InterruptedException cancelled){return;}
            catch(Exception|OutOfMemoryError failed){ui.post(()->{if(!disposed&&request.epoch==touchPreviewEpoch&&listener!=null)listener.onMessage("Brush preview: "+failed.getMessage());});}
            finally{if(pixels!=null)pixels.recycle();if(override!=null)override.close();if(composite!=null)composite.recycle();}
        }}finally{if(disposed&&store!=null){store.close();touchPaintCache=null;touchPaintOwner=null;}boolean restart=false;synchronized(touchPreviewLock){touchPreviewWorkerRunning=false;if(!disposed&&newestTouchPreview!=null){touchPreviewWorkerRunning=true;restart=true;}}if(restart)rasterWorker.execute(this::prepareTouchPreviews);}
    }
    /** A cheap transient gesture marker; durable pixels always come from the shared worker brush. */
    private void drawPaintPreview(Canvas canvas,JSONObject operation,int w,int h)throws Exception{
        JSONObject object=SketchDocument.object(document,operation.getString("objectId")),layer=SketchDocument.owner(document,object.getString("id"));
        int save=canvas.save();canvas.concat(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,frame),w,h));canvas.concat(SketchFrameRenderer.matrix(SketchDocument.transformAt(object,frame),w,h));
        JSONObject brush=operation.getJSONObject("brush");Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);marker.setColor("erase".equals(brush.getString("mode"))?0xAAE77989:SketchDocument.color(brush.getString("color")));marker.setAlpha((int)(Math.max(.15,brush.optDouble("opacity",1))*.6*Color.alpha(marker.getColor())));marker.setStyle(Paint.Style.STROKE);marker.setStrokeCap(Paint.Cap.ROUND);marker.setStrokeJoin(Paint.Join.ROUND);marker.setStrokeWidth((float)brush.getDouble("sizePx"));
        Path path=new Path();JSONArray points=operation.getJSONArray("points");for(int i=0;i<points.length();i++){JSONArray point=points.getJSONArray(i);float x=(float)point.getDouble(0)*w,y=(float)point.getDouble(1)*h;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}if(points.length()==1){JSONArray point=points.getJSONArray(0);marker.setStyle(Paint.Style.FILL);canvas.drawCircle((float)point.getDouble(0)*w,(float)point.getDouble(1)*h,marker.getStrokeWidth()/2,marker);}else canvas.drawPath(path,marker);canvas.restoreToCount(save);
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
        if(symmetry&&( "stroke".equals(tool)||"eraser".equals(tool))){
            JSONObject operation=new JSONObject().put("type","symmetry_stroke").put("layerId",layerId).put("object",object).put("symmetry",SketchDocument.copy(symmetryConfiguration));
            // Validate before invoking the editor listener; a rejected gesture must not become local intent.
            SketchSymmetry.expand(document,operation);listener.onOperation(operation);
        }
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
