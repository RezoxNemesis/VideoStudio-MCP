package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Looper;
import org.json.JSONObject;

/** Worker builds detached data and vector caches; the UI adopts them once. */
final class SketchLivePreparation {
    static final class Prepared implements AutoCloseable {
        final JSONObject document;
        final int frameIndex;
        final SketchAgentHand.Track handTrack;
        private final JSONObject committedDocument;
        private SketchFrameRenderer renderer;
        private SketchPaintReplay paintReplay;
        private SketchPaintReplay.Frame initialPaint;
        Prepared(JSONObject document, SketchFrameRenderer renderer) {
            this(document,document,renderer);
        }
        Prepared(JSONObject document,JSONObject committedDocument,SketchFrameRenderer renderer){
            this(document,committedDocument,renderer,0);
        }
        Prepared(JSONObject document,JSONObject committedDocument,SketchFrameRenderer renderer,int frameIndex){
            this(document,committedDocument,renderer,frameIndex,SketchAgentHand.Track.hidden());
        }
        Prepared(JSONObject document,JSONObject committedDocument,SketchFrameRenderer renderer,int frameIndex,SketchAgentHand.Track handTrack){
            this.document = document;
            this.frameIndex=frameIndex;
            this.handTrack=handTrack;
            this.committedDocument=committedDocument;
            this.renderer = renderer;
        }
        synchronized SketchFrameRenderer takeRenderer() {
            if (renderer == null) throw new IllegalStateException("Live renderer already adopted");
            SketchFrameRenderer value = renderer;
            renderer = null;
            return value;
        }
        boolean isPaint(){return paintReplay!=null;}
        JSONObject committedDocument(){return committedDocument;}
        synchronized SketchPaintReplay.Frame takeInitialPaint(){SketchPaintReplay.Frame value=initialPaint;initialPaint=null;return value;}
        SketchPaintReplay.Frame renderPaint(double fraction,SketchPaintReplay.Viewport viewport)throws Exception{
            SketchPaintReplay replay; synchronized(this){replay=paintReplay;}
            if(replay==null)throw new InterruptedException("Paint preparation closed");return replay.render(fraction,viewport);
        }
        @Override public synchronized void close() {
            if (renderer != null) { renderer.close(); renderer = null; }
            if(initialPaint!=null){initialPaint.close();initialPaint=null;}
            if(paintReplay!=null){paintReplay.close();paintReplay=null;}
        }
    }

    static JSONObject prepare(JSONObject baseline, JSONObject operation) throws Exception {
        requireWorker();
        JSONObject next = SketchDocument.copy(baseline);
        SketchEditOps.apply(next, operation);
        return next;
    }

    static Prepared prepareDrawing(Context context, JSONObject baseline, JSONObject operation) throws Exception {
        return prepareDrawing(context,baseline,operation,new SketchPaintReplay.Viewport(1024,null));
    }
    static Prepared prepareDrawing(Context context,JSONObject baseline,JSONObject operation,SketchPaintReplay.Viewport viewport)throws Exception{
        JSONObject next = prepare(baseline, operation);
        boolean paint=java.util.Arrays.asList("paint_stroke","paint_fill").contains(operation.optString("type"));
        JSONObject presented=paint?SketchDocument.copy(baseline):next;
        String objectId=operation.has("object")?operation.getJSONObject("object").optString("id",null):operation.optString("objectId",null);
        int frame=operationFrame(paint||"delete_object".equals(operation.optString("type"))?baseline:next,operation);
        SketchAgentHand.Track handTrack=SketchAgentHand.prepare(presented,operation,frame);
        SketchFrameRenderer renderer = new SketchFrameRenderer(context, presented);
        Prepared prepared=new Prepared(presented,next,renderer,frame,handTrack);
        try {
            renderer.prepareVectorGeometry();
            renderer.prepareRasterResources(viewport.maxDimension,frame);
            if("append_points".equals(operation.optString("type")))renderer.setAppendPresentation(objectId,SketchDocument.object(baseline,objectId).getJSONArray("points").length());
            if(java.util.Arrays.asList("paint_stroke","paint_fill").contains(operation.optString("type"))){
                JSONObject before=SketchDocument.object(baseline,operation.getString("objectId")).getJSONObject("paint");
                if(!before.getString("sha256").equals(operation.getJSONObject("beforePaint").getString("sha256")))throw new IllegalStateException("LIVE_DRAWING_GAP: paint baseline differs from accepted native stroke");
                prepared.paintReplay=new SketchPaintReplay(context,baseline.getString("id"),operation);
                prepared.initialPaint=prepared.paintReplay.render(0,viewport);
                renderer.setPaintPresentation(objectId,prepared.initialPaint);
            }
            requireWorker();
            return prepared;
        } catch (Exception | OutOfMemoryError error) { prepared.close(); throw error; }
    }

    /** Resolve the authored exposed frame from native journal pins on the reader worker. */
    static int operationFrame(JSONObject document,JSONObject operation)throws Exception{
        requireWorker();
        int frame=0,maximum=document.getInt("frameCount")-1;
        if(operation.has("objectId")){
            JSONObject object=SketchDocument.object(document,operation.getString("objectId"));
            frame=object.getInt("frame");
            if(java.util.Arrays.asList("paint_stroke","paint_fill").contains(operation.optString("type"))){
                JSONObject request=operation.getJSONObject("stroke").getJSONObject("request");
                if(request.has("frameIndex"))
                    frame=(int)SketchDocument.integer(request,"frameIndex",0,maximum);
                if(!SketchFrameRenderer.exposed(object,frame))
                    throw SketchDocument.bad("Live paint frame must fall inside its cel exposure");
            }
        }else if(operation.has("object")){
            frame=operation.getJSONObject("object").optInt("frame",0);
        }
        // Animation keyframes, individual cels and authored overlays can target
        // another frame than their underlying object's first exposure.
        if(operation.has("frame"))
            frame=(int)SketchDocument.integer(operation,"frame",0,maximum);
        if(frame<0||frame>maximum)
            throw SketchDocument.bad("Live drawing operation frame is outside the sketch");
        return frame;
    }

    private static void requireWorker() throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Live geometry preparation requires a background worker");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Live preparation cancelled");
    }
}
