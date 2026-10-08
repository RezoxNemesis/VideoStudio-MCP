package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Preview-only proxy policy and generator.
 *
 * Proxies never replace source assets. NativeRenderEngine continues to resolve
 * the original ProjectStore.Asset URI for final export.
 */
@UnstableApi
public final class ProxyManager {
    public static final long HEAVY_VIDEO_THRESHOLD_BYTES = 512L * 1024L * 1024L;
    private final Context context;
    private final ProjectStore store;
    private final JobManager jobs;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ProxyManager(Context context, ProjectStore store, JobManager jobs) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.jobs = jobs;
    }

    private ProxyManager() {
        context = null;
        store = null;
        jobs = null;
    }

    public static boolean shouldProxy(ProjectStore.Asset source) {
        if (source == null) return false;
        if (source.mime == null || !source.mime.startsWith("video/")) return false;
        return source.sizeBytes >= HEAVY_VIDEO_THRESHOLD_BYTES;
    }

    public static String previewUri(ProjectStore.Project project, ProjectStore.Asset source) {
        if (source == null) return "";
        if (project == null || source.id == null || source.id.isEmpty()) return originalUri(source);

        ProjectStore.Asset best = null;
        for (ProjectStore.Asset candidate : project.assets) {
            if (candidate == null || candidate.generationMetadata == null) continue;
            if (!"preview_proxy".equals(candidate.role)) continue;
            if (!candidate.generated) continue;
            if (!source.id.equals(candidate.generationMetadata.optString("originalAssetId", ""))) continue;
            if (!candidate.generationMetadata.optBoolean("complete", false)) continue;
            String uri = candidate.uri == null ? "" : candidate.uri.trim();
            if (uri.isEmpty() || uri.endsWith(".partial")) continue;
            if (best == null || candidate.createdAt > best.createdAt) best = candidate;
        }
        return best == null ? originalUri(source) : best.uri;
    }

    public static String originalUri(ProjectStore.Asset source) {
        return source == null || source.uri == null ? "" : source.uri;
    }

    public static JSONObject proxyMetadata(String originalAssetId, String tier, boolean complete) {
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("originalAssetId", originalAssetId == null ? "" : originalAssetId);
            metadata.put("proxyTier", tier == null || tier.isEmpty() ? "720p" : tier);
            metadata.put("complete", complete);
            metadata.put("previewOnly", true);
        } catch (Exception ignored) {}
        return metadata;
    }

    public JobManager.Job request(ProjectStore.Project project,
                                  ProjectStore.Asset source,
                                  String tier) {
        if (context == null || store == null || jobs == null) {
            throw new IllegalStateException("ProxyManager runtime is not configured");
        }
        if (project == null || source == null) throw new IllegalArgumentException("Project and source are required");
        if (!shouldProxy(source)) throw new IllegalArgumentException("Source does not require a preview proxy");

        final String resolvedTier = "540p".equalsIgnoreCase(tier) ? "540p" : "720p";
        return jobs.submit("Preview proxy • " + source.name, JobManager.Kind.HEAVY, state -> {
            state.checkpoint("proxy_prepare", 2, "Preparing lightweight preview proxy");
            ProjectStore.Project latest = store.get(project.id);
            ProjectStore.Asset current = latest == null ? null : latest.asset(source.id);
            if (latest == null || current == null) throw new IllegalStateException("Proxy source is no longer available");

            String existing = previewUri(latest, current);
            if (!existing.equals(originalUri(current))) {
                state.checkpoint("proxy_reuse", 100, "Existing preview proxy reused");
                return;
            }

            File root = new File(context.getCacheDir(), "preview_proxies/" + project.id);
            if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Could not create proxy workspace");
            File finalFile = new File(root, safeName(current.id) + "_" + resolvedTier + ".mp4");
            File partial = new File(finalFile.getAbsolutePath() + ".partial");
            if (partial.exists() && !partial.delete()) throw new IllegalStateException("Could not reset incomplete proxy");

            if (finalFile.isFile() && finalFile.length() > 0L) {
                registerProxy(latest, current, finalFile, resolvedTier);
                state.checkpoint("proxy_ready", 100, "Preview proxy ready");
                return;
            }

            int height = "540p".equals(resolvedTier) ? 540 : 720;
            MediaItem media = MediaItem.fromUri(Uri.parse(current.uri));
            List<Effect> videoEffects = Collections.singletonList(Presentation.createForHeight(height));
            EditedMediaItem item = new EditedMediaItem.Builder(media)
                    .setEffects(new Effects(Collections.<AudioProcessor>emptyList(), videoEffects))
                    .setRemoveAudio(true)
                    .build();
            Composition composition = new Composition.Builder(
                    EditedMediaItemSequence.withVideoFrom(Collections.singletonList(item))
            ).build();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            Transformer transformer = new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition composition, ExportResult result) {
                            latch.countDown();
                        }

                        @Override public void onError(Composition composition,
                                                      ExportResult result,
                                                      ExportException exception) {
                            error.set(exception.getMessage() == null ? "Proxy export failed" : exception.getMessage());
                            latch.countDown();
                        }
                    })
                    .build();

            main.post(() -> {
                try { transformer.start(composition, partial.getAbsolutePath()); }
                catch (Exception startError) {
                    error.set(startError.getMessage() == null ? "Could not start proxy export" : startError.getMessage());
                    latch.countDown();
                }
            });

            while (!latch.await(500L, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) {
                    main.post(() -> {
                        try { transformer.cancel(); } catch (Exception ignored) {}
                    });
                    throw new InterruptedException();
                }
                int next = Math.min(92, state.progress + 3);
                state.checkpoint("proxy_encode", next, "Building " + resolvedTier + " preview proxy");
            }

            if (error.get() != null) {
                if (partial.exists()) partial.delete();
                throw new IllegalStateException(error.get());
            }
            if (!partial.isFile() || partial.length() <= 0L) {
                throw new IllegalStateException("Proxy renderer produced no playable file");
            }
            if (finalFile.exists() && !finalFile.delete()) {
                throw new IllegalStateException("Could not replace old proxy");
            }
            if (!partial.renameTo(finalFile)) throw new IllegalStateException("Could not publish preview proxy");

            ProjectStore.Project fresh = store.get(project.id);
            ProjectStore.Asset freshSource = fresh == null ? null : fresh.asset(source.id);
            if (fresh == null || freshSource == null) throw new IllegalStateException("Project changed before proxy publication");
            registerProxy(fresh, freshSource, finalFile, resolvedTier);
            state.checkpoint("proxy_ready", 100, "Preview proxy ready");
        });
    }

    private void registerProxy(ProjectStore.Project project,
                               ProjectStore.Asset source,
                               File file,
                               String tier) {
        Uri uri = Uri.fromFile(file);
        ProjectStore.Asset proxy = store.registerGeneratedAsset(
                project,
                uri,
                source.name + " • " + tier + " proxy",
                "preview_proxy",
                false
        );
        store.updateAssetMetadata(project.id,proxy.id,proxyMetadata(source.id,tier,true),file.length());
    }

    private static String safeName(String value) {
        String out = value == null ? "asset" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return out.isEmpty() ? "asset" : out;
    }
}
