package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Small, lazy, bounded thumbnail cache. Original media is never read into a byte array. */
public final class MediaThumbnailCache {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24), new ThreadPoolExecutor.AbortPolicy());

    public MediaThumbnailCache(Context context) { this.context = context.getApplicationContext(); }

    public Bitmap get(ProjectStore.Asset asset, long sourceMs, Runnable ready) {
        if (asset == null || asset.uri == null) return null;
        String key = asset.id + ":" + asset.uri + ":" + sourceMs / 1000L;
        Bitmap hit = cache.get(key);
        if (hit != null) return hit;
        if (!pending.add(key)) return null;
        try {
            pool.execute(() -> {
                Bitmap result = null;
                try {
                    Uri uri = Uri.parse(asset.uri);
                    if (asset.mime != null && asset.mime.startsWith("image/")) {
                        result = StudioPreviewMonitor.decodeImage(context, uri, 320);
                    } else if (asset.mime != null && asset.mime.startsWith("video/")) {
                        MediaMetadataRetriever decoder = new MediaMetadataRetriever();
                        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                            if (fd != null) {
                                decoder.setDataSource(fd.getFileDescriptor());
                                result = decoder.getScaledFrameAtTime(Math.max(0L, sourceMs) * 1000L,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 136);
                            }
                        } finally { decoder.release(); }
                    }
                    if (result != null) cache.put(key, result);
                } catch (Exception ignored) { /* Source errors are shown by the preview monitor. */ }
                finally { pending.remove(key); main.post(ready); }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) { pending.remove(key); }
        return null;
    }

    public void close() { pool.shutdownNow(); cache.evictAll(); pending.clear(); }
}
