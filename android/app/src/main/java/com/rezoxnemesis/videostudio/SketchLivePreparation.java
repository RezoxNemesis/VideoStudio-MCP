package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Looper;
import org.json.JSONObject;

/** Worker builds detached data and vector caches; the UI adopts them once. */
final class SketchLivePreparation {
    static final class Prepared implements AutoCloseable {
        final JSONObject document;
        private SketchFrameRenderer renderer;
        Prepared(JSONObject document, SketchFrameRenderer renderer) {
            this.document = document;
            this.renderer = renderer;
        }
        SketchFrameRenderer takeRenderer() {
            if (renderer == null) throw new IllegalStateException("Live renderer already adopted");
            SketchFrameRenderer value = renderer;
            renderer = null;
            return value;
        }
        @Override public void close() {
            if (renderer != null) { renderer.close(); renderer = null; }
        }
    }

    static JSONObject prepare(JSONObject baseline, JSONObject operation) throws Exception {
        requireWorker();
        JSONObject next = SketchDocument.copy(baseline);
        SketchEditOps.apply(next, operation);
        return next;
    }

    static Prepared prepareDrawing(Context context, JSONObject baseline, JSONObject operation) throws Exception {
        JSONObject next = prepare(baseline, operation);
        SketchFrameRenderer renderer = new SketchFrameRenderer(context, next);
        try {
            renderer.prepareVectorGeometry();
            requireWorker();
            return new Prepared(next, renderer);
        } catch (Exception | OutOfMemoryError error) { renderer.close(); throw error; }
    }

    private static void requireWorker() throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Live geometry preparation requires a background worker");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Live preparation cancelled");
    }
}
