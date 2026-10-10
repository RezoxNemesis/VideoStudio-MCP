package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.json.JSONArray;

/** Shared, undoable path mutations. The caller owns the ProjectStore transaction. */
public final class ProjectMotionPathEdits {
    private ProjectMotionPathEdits() {}

    public static void set(ProjectStore.Project project, String clipId, JSONObject path) {
        ProjectStore.Clip clip = visualClip(project, clipId);
        JSONObject canonical = MotionPath2D.fromJson(path).toJson();
        if (clip.effects == null) clip.effects = new JSONObject();
        try { clip.effects.put("motionPath", canonical); }
        catch (Exception failure) { throw new IllegalArgumentException("Could not save the clip motion path", failure); }
    }

    public static void clear(ProjectStore.Project project, String clipId) {
        ProjectStore.Clip clip = visualClip(project, clipId);
        if (clip.effects != null) clip.effects.remove("motionPath");
    }

    public static void validateVisual(ProjectStore.Project project, String clipId) { visualClip(project, clipId); }

    public static void setEasing(ProjectStore.Project project, String clipId, String scope,
                                  String easing, JSONArray controls) {
        if (!MotionTimeline.supportsEasing(easing)) throw new IllegalArgumentException("Unsupported easing: " + easing);
        CubicBezierEasing cubic = "cubic_bezier".equals(easing) ? CubicBezierEasing.fromJson(controls) : null;
        if (cubic == null && controls != null) throw new IllegalArgumentException("Bézier controls require cubic_bezier easing");
        ProjectStore.Clip clip;
        String easingKey, controlKey;
        JSONObject target;
        if ("audio".equals(scope)) {
            clip = requireClip(project, clipId);
            ProjectStore.Asset asset = project.asset(clip.assetId);
            if (asset == null || !asset.hasAudio) throw new IllegalArgumentException("Audio easing requires a clip with a real audio stream");
            if (clip.effects == null) clip.effects = new JSONObject();
            target = clip.effects; easingKey = "audioEasing"; controlKey = "audioBezier";
        } else if ("visual".equals(scope) || "camera".equals(scope)) {
            clip = visualClip(project, clipId);
            if (clip.effects == null) clip.effects = new JSONObject();
            target = clip.effects;
            easingKey = "ease"; controlKey = "bezier";
            if ("camera".equals(scope)) {
                target = clip.effects.optJSONObject("animationSpec");
                if (target == null) target = new JSONObject();
                easingKey = "easing";
            }
        } else throw new IllegalArgumentException("Easing scope must be visual, audio or camera");
        try {
            target.put(easingKey, easing);
            if (cubic == null) target.remove(controlKey); else target.put(controlKey, cubic.toJson());
            if ("camera".equals(scope)) clip.effects.put("animationSpec", target);
        } catch (Exception failure) { throw new IllegalArgumentException("Could not save animation easing", failure); }
    }

    private static ProjectStore.Clip visualClip(ProjectStore.Project project, String clipId) {
        ProjectStore.Clip clip = requireClip(project, clipId);
        ProjectStore.Track track = project.track(clip.trackId);
        ProjectStore.Asset asset = project.asset(clip.assetId);
        if (track == null || asset == null || track.isAudio() || asset.mime == null
                || !(asset.mime.startsWith("video/") || asset.mime.startsWith("image/")))
            throw new IllegalArgumentException("Motion paths require a visual image or video clip");
        return clip;
    }

    private static ProjectStore.Clip requireClip(ProjectStore.Project project, String clipId) {
        if (project == null) throw new IllegalArgumentException("Project is required");
        ProjectStore.Clip clip = project.clip(clipId);
        if (clip == null) throw new IllegalArgumentException("Clip not found");
        ProjectStore.Track track = project.track(clip.trackId);
        if (track == null) throw new IllegalArgumentException("Track not found");
        if (track.locked) throw new IllegalStateException("Track is locked: " + track.name);
        return clip;
    }
}
