package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/**
 * Preview-only proxy selection policy.
 *
 * Proxies never replace source assets. NativeRenderEngine continues to resolve
 * the original ProjectStore.Asset URI for final export.
 */
public final class ProxyManager {
    public static final long HEAVY_VIDEO_THRESHOLD_BYTES = 512L * 1024L * 1024L;

    private ProxyManager() {}

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
}
