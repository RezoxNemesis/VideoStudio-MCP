package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, shared, asynchronous thumbnails; never decodes a full-size still into the UI. */
final class EditorThumbnailCache {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final Map<String, ArrayList<Runnable>> callbacks = new HashMap<>();
    private boolean closed;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    EditorThumbnailCache(Context context) { this.context = context.getApplicationContext(); }

    Bitmap peek(ProjectStore.Asset asset) { return asset == null ? null : cache.get(asset.uri); }

    void bind(ProjectStore.Asset asset, ImageView target) {
        if (asset == null || target == null) return;
        final String key = asset.uri;
        target.setTag(key);
        Bitmap ready = cache.get(key);
        if (ready != null) { target.setImageBitmap(ready); return; }
        request(asset, () -> {
            if (key.equals(target.getTag())) {
                Bitmap bitmap = cache.get(key);
                if (bitmap != null) target.setImageBitmap(bitmap);
            }
        });
    }

    void request(ProjectStore.Asset asset, Runnable onReady) {
        if (closed || asset == null || asset.uri == null || asset.uri.isEmpty()) return;
        final String key = asset.uri;
        if (cache.get(key) != null) { if (onReady != null) onReady.run(); return; }
        synchronized (pending) {
            if (failed.contains(key)) return;
            if (onReady != null) callbacks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(onReady);
            if (pending.contains(key)) {
                return;
            }
            pending.add(key);
        }
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                Uri uri = Uri.parse(key);
                if (asset.mime != null && asset.mime.startsWith("image/")) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                        BitmapFactory.decodeStream(input, null, options);
                    }
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(input, null, options);
                    }
                } else if (asset.mime != null && asset.mime.startsWith("video/")) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(context, uri);
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
                        } else {
                            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (frame != null) {
                                float ratio = Math.min(1f, 320f / Math.max(frame.getWidth(), frame.getHeight()));
                                bitmap = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * ratio)), Math.max(1, Math.round(frame.getHeight() * ratio)), true);
                                if (bitmap != frame) frame.recycle();
                            }
                        }
                    } finally { retriever.release(); }
                }
            } catch (Exception ignored) { }
            final Bitmap result = bitmap;
            main.post(() -> {
                ArrayList<Runnable> waiting;
                synchronized (pending) {
                    pending.remove(key);
                    waiting = callbacks.remove(key);
                    if (result == null) failed.add(key);
                }
                if (closed) { if (result != null) result.recycle(); return; }
                if (result != null) cache.put(key, result);
                if (waiting != null) for (Runnable callback : waiting) callback.run();
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
