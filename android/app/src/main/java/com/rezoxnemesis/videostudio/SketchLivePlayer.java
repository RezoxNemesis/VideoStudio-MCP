package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** Replays every accepted operation, in order, through worker-prepared snapshots. */
public final class SketchLivePlayer implements AutoCloseable {
    public interface Listener {
        void drawing(JSONObject doc, String objectId, float fraction, String caption);
        default void preparedDrawing(SketchLivePreparation.Prepared prepared, String objectId,
                                     float fraction, String caption) {
            drawing(prepared.document, objectId, fraction, caption);
        }
        default SketchPaintReplay.Viewport paintViewport(String objectId){return new SketchPaintReplay.Viewport(1024,null);}
        default SketchPaintReplay.Viewport paintViewport(String objectId,int frameIndex){return paintViewport(objectId);}
        default void agentHand(SketchAgentHand.State state){}
        default void agentTool(String toolLabel,String caption){}
        /** Transfers ownership of the immutable frame to the receiving native canvas. */
        default void preparedPaintDrawing(SketchLivePreparation.Prepared prepared,SketchPaintReplay.Frame frame,
                                         String objectId,float fraction,String caption){
            frame.close();throw new IllegalStateException("Paint replay requires a native canvas presentation listener");
        }
        void committed(JSONObject doc);
        void idle();
        void finished(long sessionSequence);
        void failed(String message);
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final String id, sessionId;
    private final int duration;
    private JSONObject document, event;
    private JSONArray events = new JSONArray(), operations;
    private SketchLivePreparation.Prepared current;
    private String target;
    private long sequence, started;
    private int eventIndex, operationIndex;
    private volatile boolean closed;
    private boolean fetching, preparing;
    private boolean paintPreparing;
    private float paintFraction;
    private SketchAgentHand.State handState=SketchAgentHand.State.hidden();
    private final Object publicationLock=new Object();
    private final java.util.HashSet<AutoCloseable> publications=new java.util.HashSet<>();

    public SketchLivePlayer(Context context, JSONObject baseline, Listener listener) throws Exception {
        this.context = context.getApplicationContext();
        document = SketchDocument.copy(baseline.getJSONObject("document"));
        id = baseline.getString("sketchId");
        sessionId = baseline.getString("sessionId");
        sequence = baseline.getLong("afterSequence");
        duration = baseline.getInt("strokeDurationMs");
        this.listener = listener;
    }
    public void start() { listener.committed(document); main.post(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            try {
                if (current != null) {
                    if(current.isPaint()){
                        if(paintFraction<1){if(!paintPreparing)preparePaintPrefix();return;}
                        finishOperation();
                    }else{
                    float fraction = Math.min(1, (SystemClock.uptimeMillis()-started)/(float)duration);
                    listener.preparedDrawing(current, target, fraction, "Live ChatGPT · drawing "+
                            (operationIndex+1)+"/"+operations.length()+" · r"+event.getLong("revision"));
                    if(closed)return;showHand(current.handTrack.at(fraction));
                    if (fraction < 1) { main.postDelayed(this, 16); return; }
                    finishOperation();
                    }
                }
                if (operations != null && operationIndex < operations.length()) {
                    if (!preparing) prepareOperation(operations.getJSONObject(operationIndex));
                    return;
                }
                if (operations != null) {
                    document.put("revision", event.getLong("revision"));
                    sequence = event.getLong("sequence");
                    operations = null; listener.committed(document); eventIndex++;
                }
                if (eventIndex < events.length()) {
                    event = events.getJSONObject(eventIndex);
                    if (event.getLong("baseRevision") != document.getLong("revision"))
                        throw new IllegalStateException("LIVE_DRAWING_GAP: a non-stream edit changed the source; start a new live session to keep every step visible");
                    operations = event.getJSONArray("operations"); operationIndex = 0;
                    main.post(this); return;
                }
                hideHand();listener.idle();
                if (!fetching) fetchEvents();
            } catch (Exception | OutOfMemoryError error) { fail(error); }
        }
    };

    private void finishOperation(){document=current.committedDocument();current.close();current=null;operationIndex++;}
    private String caption()throws Exception{return "Live ChatGPT · drawing "+(operationIndex+1)+"/"+operations.length()+" · r"+event.getLong("revision");}
    private void showHand(SketchAgentHand.State state){
        if(closed)return;handState=state;listener.agentHand(state);if(!closed)listener.agentTool(state.toolLabel,state.caption);
    }
    private void pauseHand(String caption){if(handState.visible)showHand(handState.paused(caption));}
    private void hideHand(){if(handState.visible||!handState.toolLabel.isEmpty()||!handState.caption.isEmpty()){handState=SketchAgentHand.State.hidden();listener.agentHand(handState);listener.agentTool("","");}}

    private void preparePaintPrefix()throws Exception{
        SketchLivePreparation.Prepared prepared=current;SketchPaintReplay.Viewport viewport=listener.paintViewport(target,prepared.frameIndex);
        // A slow worker may extend presentation, but it still exposes multiple real prefixes.
        float elapsed=Math.min(1,(SystemClock.uptimeMillis()-started)/(float)duration);
        float fraction=Math.min(1,Math.min(paintFraction+.25f,Math.max(paintFraction+.01f,elapsed)));
        paintPreparing=true;String objectId=target;String label=caption();
        pauseHand("Preparing next brush step");
        reader.execute(()->{
            try{
                SketchPaintReplay.Frame frame=prepared.renderPaint(fraction,viewport);
                publish(frame,()->{
                    paintPreparing=false;if(closed||current!=prepared){frame.close();return;}
                    try{paintFraction=fraction;listener.preparedPaintDrawing(prepared,frame,objectId,fraction,label);if(!closed){showHand(prepared.handTrack.at(fraction));main.postDelayed(tick,fraction<1?16:0);}}
                    catch(Exception|OutOfMemoryError error){fail(error);}
                });
            }catch(Exception|OutOfMemoryError error){main.post(()->{paintPreparing=false;fail(error);});}
        });
    }

    /** Track prepared ownership while a UI callback is queued, including cancellation. */
    private void publish(AutoCloseable resource,Runnable callback){
        synchronized(publicationLock){
            if(closed){release(resource);return;}publications.add(resource);
            if(!main.post(()->{
                synchronized(publicationLock){if(!publications.remove(resource))return;}
                if(closed){release(resource);return;}callback.run();
            })){publications.remove(resource);release(resource);}
        }
    }
    private static void release(AutoCloseable resource){try{resource.close();}catch(Exception ignored){}}

    private void prepareOperation(JSONObject operation) {
        String objectId=operation.has("object")?operation.optJSONObject("object").optString("id",null):operation.optString("objectId",null);
        JSONObject baseline = document; preparing = true;
        pauseHand("Preparing next drawing step");
        if("paint_stroke".equals(operation.optString("type"))){
            // Resolve keyed poses on the reader, then ask the UI only for its visible viewport.
            reader.execute(()->{
                try{
                    int frame=SketchLivePreparation.operationFrame(baseline,operation);
                    main.post(()->{
                        if(closed)return;
                        try{prepareOperationDrawing(baseline,operation,listener.paintViewport(objectId,frame));}
                        catch(Exception|OutOfMemoryError error){preparing=false;fail(error);}
                    });
                }catch(Exception|OutOfMemoryError error){main.post(()->{preparing=false;fail(error);});}
            });
        }else prepareOperationDrawing(baseline,operation,listener.paintViewport(objectId));
    }
    private void prepareOperationDrawing(JSONObject baseline,JSONObject operation,SketchPaintReplay.Viewport viewport){
        reader.execute(() -> {
            SketchLivePreparation.Prepared ready = null;
            try {
                ready = SketchLivePreparation.prepareDrawing(context, baseline, operation,viewport);
                String object = operation.has("object") ? operation.getJSONObject("object").optString("id",null)
                        : operation.optString("objectId",null);
                boolean stroke = "precision_contour".equals(operation.optString("type")) || "add_object".equals(operation.optString("type")) ||
                        "append_points".equals(operation.optString("type")) ||
                        "update_object".equals(operation.optString("type"));
                if (stroke && object != null) SketchDocument.object(ready.document,object);
                if (closed) { ready.close(); return; }
                SketchLivePreparation.Prepared prepared = ready;
                publish(prepared,() -> {
                    preparing = false;
                    if (closed) { prepared.close(); return; }
                    try {
                        current = prepared; target = object;
                        if(current.isPaint()){
                            paintFraction=0;paintPreparing=false;started=SystemClock.uptimeMillis();
                            SketchPaintReplay.Frame frame=current.takeInitialPaint();
                            listener.preparedPaintDrawing(current,frame,target,0,caption());
                            if(!closed){showHand(prepared.handTrack.at(0));main.postDelayed(tick,16);}
                        }else if (stroke && object != null) { started = SystemClock.uptimeMillis(); main.post(tick); }
                        else {
                            listener.preparedDrawing(current,null,1,"Live ChatGPT · "+operation.optString("type"));
                            if(closed)return;showHand(prepared.handTrack.at(1));
                            document = current.document; current.close(); current = null;
                            operationIndex++; main.post(tick);
                        }
                    } catch (Exception | OutOfMemoryError error) { fail(error); }
                });
            } catch (Exception | OutOfMemoryError error) {
                if (ready != null) ready.close();
                main.post(() -> { preparing = false; fail(error); });
            }
        });
    }

    private void fetchEvents() {
        fetching = true;
        reader.execute(() -> {
            try {
                JSONObject batch;
                try (SketchProjectStore store = new SketchProjectStore(context)) {
                    batch = store.liveEvents(id,sequence,4);
                    JSONObject stream = store.liveSession(id,sessionId);
                    if (stream.optBoolean("ended")) {
                        JSONArray filtered = new JSONArray(), all = batch.getJSONArray("events");
                        for (int i=0;i<all.length();i++)
                            if (all.getJSONObject(i).getLong("sequence") <= stream.getLong("endSequence"))
                                filtered.put(all.getJSONObject(i));
                        batch.put("events",filtered);
                    }
                    batch.put("session",stream);
                }
                main.post(() -> {
                    fetching = false;
                    if (closed) return;
                    try {
                        events = batch.getJSONArray("events"); eventIndex = 0;
                        JSONObject state = batch.getJSONObject("session");
                        if (state.optBoolean("ended") && sequence >= state.optLong("endSequence")) {
                            listener.finished(state.optLong("sessionSequence")); close(); return;
                        }
                        main.postDelayed(tick,events.length()==0?100:0);
                    } catch (Exception | OutOfMemoryError error) { fail(error); }
                });
            } catch (Exception | OutOfMemoryError error) {
                main.post(() -> { fetching = false; fail(error); });
            }
        });
    }
    private void fail(Throwable error) {
        if (closed) return;
        try { listener.failed(error.getMessage()); } finally { close(); }
    }
    @Override public void close() {
        synchronized(publicationLock){closed=true;main.removeCallbacksAndMessages(null);for(AutoCloseable resource:publications)release(resource);publications.clear();}
        reader.shutdownNow();
        if (current != null) { current.close(); current = null; }
        hideHand();
    }
}
