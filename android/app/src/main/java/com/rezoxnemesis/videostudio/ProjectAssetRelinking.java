package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Validated source replacement without changing clip, asset or history identities. */
final class ProjectAssetRelinking {
    private ProjectAssetRelinking() {}

    static void apply(ProjectStore.Project project, String targetId, ProjectStore.Asset inspected, boolean preserveName) throws Exception {
        ProjectStore.Asset target = project.asset(targetId);
        if (target == null) throw new IllegalArgumentException("Source asset not found");
        if (!isSource(target) || !isSource(inspected)) throw new IllegalArgumentException("Only imported source media can be relinked; published and generated assets retain their records");
        String kind = mediaKind(target.mime), replacementKind = mediaKind(inspected.mime);
        if (kind.isEmpty() || !kind.equals(replacementKind)) throw new IllegalArgumentException("Replacement must have the same image, audio or video type as the source");
        if (inspected.uri == null || inspected.uri.isEmpty()) throw new IllegalArgumentException("Inspected replacement URI is required");
        if (!kind.equals("image") && inspected.durationMs <= 0L) throw new IllegalArgumentException("Replacement media needs an inspected positive duration");
        if (!kind.equals("audio") && (inspected.width <= 0 || inspected.height <= 0)) throw new IllegalArgumentException("Replacement image or video needs inspected dimensions");
        for (ProjectStore.Clip clip : project.clips) if (targetId.equals(clip.assetId)) {
            ProjectStore.Track track = project.track(clip.trackId);
            if (track == null) throw new IllegalArgumentException("Referring clip track is unavailable");
            if (track.locked) throw new IllegalStateException("Source is used on locked track: " + track.name);
            if (!kind.equals("image") && clip.outMs > inspected.durationMs) throw new IllegalArgumentException("Replacement is shorter than an existing clip's source range; trim the clip first");
            if (track.isAudio() && !inspected.hasAudio) throw new IllegalArgumentException("Replacement has no audio for an existing audio-track clip");
            if (hasDerivedLayers(clip.effects, 0)) throw new IllegalStateException("Remove this source's generated portrait layers before relinking, then rebuild them from the replacement");
        }
        ProjectStore.Asset replacement = ProjectStore.Asset.fromJson(new JSONObject(inspected.toJson().toString()));
        replacement.id = target.id; replacement.role = target.role; replacement.generated = false; replacement.createdAt = target.createdAt;
        if (preserveName && target.name != null && !target.name.trim().isEmpty()) replacement.name = target.name;
        replacement.generationMetadata = new JSONObject();
        replacement.importMetadata.put("relinkedAt", System.currentTimeMillis());
        project.assets.set(project.assets.indexOf(target), replacement);
        for (ProjectStore.Asset asset : project.assets) if ("preview_proxy".equals(asset.role) && asset.generationMetadata != null
                && targetId.equals(asset.generationMetadata.optString("originalAssetId", ""))) {
            JSONObject metadata = new JSONObject(asset.generationMetadata.toString());
            metadata.put("complete", false); metadata.put("staleReason", "Source media relinked");
            metadata.remove("sourceFingerprint"); metadata.remove("validation"); asset.generationMetadata = metadata;
        }
    }

    private static boolean hasDerivedLayers(JSONObject effects, int depth) {
        if (effects == null) return false;
        if (depth > 4) return true;
        if (effects.optBoolean("animatedScene", false)) return true;
        for (String key : new String[]{"foregroundUri", "headUri", "torsoUri", "lowerUri", "backgroundUri"})
            if (!effects.optString(key, "").isEmpty()) return true;
        JSONObject spec = effects.optJSONObject("animationSpec");
        return spec != null && hasDerivedLayers(spec, depth + 1);
    }
    private static boolean isSource(ProjectStore.Asset asset) { return asset != null && !asset.generated && "source".equals(asset.role); }
    private static String mediaKind(String mime) {
        if (mime == null) return "";
        for (String kind : new String[]{"image", "audio", "video"}) if (mime.startsWith(kind + "/")) return kind;
        return "";
    }
}
