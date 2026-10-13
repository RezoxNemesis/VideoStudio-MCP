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
                    float fraction = Math.min(1, (SystemClock.uptimeMillis()-started)/(float)duration);
                    listener.preparedDrawing(current, target, fraction, "Live ChatGPT · drawing "+
                            (operationIndex+1)+"/"+operations.length()+" · r"+event.getLong("revision"));
                    if (fraction < 1) { main.postDelayed(this, 16); return; }
                    document = current.document;
                    current.close(); current = null; operationIndex++;
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
                listener.idle();
                if (!fetching) fetchEvents();
            } catch (Exception | OutOfMemoryError error) { fail(error); }
        }
    };

    private void prepareOperation(JSONObject operation) {
        JSONObject baseline = document; preparing = true;
        reader.execute(() -> {
            SketchLivePreparation.Prepared ready = null;
            try {
                ready = SketchLivePreparation.prepareDrawing(context, baseline, operation);
                String object = operation.has("object") ? operation.getJSONObject("object").optString("id",null)
                        : operation.optString("objectId",null);
                boolean stroke = "add_object".equals(operation.optString("type")) ||
                        "append_points".equals(operation.optString("type")) ||
                        "update_object".equals(operation.optString("type"));
                if (stroke && object != null) SketchDocument.object(ready.document,object);
                if (closed) { ready.close(); return; }
                SketchLivePreparation.Prepared prepared = ready;
                if (!main.post(() -> {
                    preparing = false;
                    if (closed) { prepared.close(); return; }
                    try {
                        current = prepared; target = object;
                        if (stroke && object != null) { started = SystemClock.uptimeMillis(); main.post(tick); }
                        else {
                            listener.preparedDrawing(current,null,1,"Live ChatGPT · "+operation.optString("type"));
                            document = current.document; current.close(); current = null;
                            operationIndex++; main.post(tick);
                        }
                    } catch (Exception | OutOfMemoryError error) { fail(error); }
                })) prepared.close();
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
        closed = true; main.removeCallbacksAndMessages(null); reader.shutdownNow();
        if (current != null) { current.close(); current = null; }
    }
}
