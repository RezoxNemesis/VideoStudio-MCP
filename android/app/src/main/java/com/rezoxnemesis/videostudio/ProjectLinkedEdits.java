package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Aligned clip groups and their atomic, source-aware timeline edits. */
final class ProjectLinkedEdits {
    private ProjectLinkedEdits() {}

    static List<ProjectStore.Clip> members(ProjectStore.Project project, ProjectStore.Clip selected) {
        ArrayList<ProjectStore.Clip> result = new ArrayList<>();
        if (selected.linkGroupId == null || selected.linkGroupId.isEmpty()) result.add(selected);
        else for (ProjectStore.Clip clip : project.clips) if (selected.linkGroupId.equals(clip.linkGroupId)) result.add(clip);
        return result;
    }

    static void requireUnlocked(ProjectStore.Project project, List<ProjectStore.Clip> clips) {
        for (ProjectStore.Clip clip : clips) {
            ProjectStore.Track track = project.track(clip.trackId);
            if (track == null) throw new IllegalArgumentException("Track not found");
            if (track.locked) throw new IllegalStateException("Track is locked: " + track.name);
        }
    }

    static void link(ProjectStore.Project project, JSONObject settings) throws Exception {
        JSONArray ids = settings.optJSONArray("clipIds");
        if (ids == null || ids.length() < 2) throw new IllegalArgumentException("Select at least two aligned clips to link");
        Set<String> requested = new HashSet<>(), included = new HashSet<>();
        ArrayList<ProjectStore.Clip> clips = new ArrayList<>();
        for (int index = 0; index < ids.length(); index++) {
            String id = ids.getString(index);
            ProjectStore.Clip clip = project.clip(id);
            if (clip == null) throw new IllegalArgumentException("Clip not found: " + id);
            requested.add(id);
            for (ProjectStore.Clip peer : members(project, clip)) if (included.add(peer.id)) clips.add(peer);
        }
        if (requested.size() < 2) throw new IllegalArgumentException("Select two different clips to link");
        requireUnlocked(project, clips);
        ProjectStore.Clip first = clips.get(0);
        for (ProjectStore.Clip clip : clips) if (clip.startMs != first.startMs || clip.endMs() != first.endMs())
            throw new IllegalArgumentException("Align the clips' program start and end before linking");
        String group = first.linkGroupId;
        boolean alreadyLinked = group != null && !group.isEmpty();
        for (ProjectStore.Clip clip : clips) if (!groupEquals(group, clip.linkGroupId)) alreadyLinked = false;
        if (alreadyLinked) return;
        group = UUID.randomUUID().toString();
        for (ProjectStore.Clip clip : clips) clip.linkGroupId = group;
    }

    static void extractAudio(ProjectStore.Project project, ProjectStore.Clip clip, JSONObject settings) throws Exception {
        ProjectStore.Asset asset = project.asset(clip.assetId);
        ProjectStore.Track sourceTrack = project.track(clip.trackId);
        if (asset == null || asset.mime == null || !asset.mime.startsWith("video/") || !asset.hasAudio
                || sourceTrack == null || sourceTrack.isAudio())
            throw new IllegalArgumentException("Select a video clip with an audio track to extract");
        if (clip.effects.optBoolean("audioExtractionDetached", false) && clip.effects.optBoolean("audioDetached", false))
            throw new IllegalStateException("This clip's audio was already extracted; restore embedded audio explicitly before extracting again");
        List<ProjectStore.Clip> group = members(project, clip);
        requireUnlocked(project, group);
        for (ProjectStore.Clip peer : group) {
            ProjectStore.Track peerTrack = project.track(peer.trackId);
            if (peer != clip && peerTrack != null && peerTrack.isAudio() && clip.assetId.equals(peer.assetId)
                    && clip.inMs == peer.inMs && clip.outMs == peer.outMs)
                throw new IllegalStateException("This clip already has linked extracted audio");
        }
        if (clip.effects.optBoolean("audioDetached", false)) for (ProjectStore.Clip peer : project.clips) {
            ProjectStore.Track peerTrack = project.track(peer.trackId);
            if (peer != clip && peerTrack != null && peerTrack.isAudio() && clip.assetId.equals(peer.assetId)
                    && clip.inMs == peer.inMs && clip.outMs == peer.outMs && clip.startMs == peer.startMs && clip.endMs() == peer.endMs())
                throw new IllegalStateException("Extracted audio already exists at this program position; link the existing clips instead");
        }
        ProjectStore.Track audioTrack;
        if (settings.has("trackId")) {
            audioTrack = project.track(settings.getString("trackId"));
            if (audioTrack == null || !audioTrack.isAudio()) throw new IllegalArgumentException("Audio extraction requires an audio track");
            if (audioTrack.locked) throw new IllegalStateException("Track is locked: " + audioTrack.name);
        } else {
            // A dedicated lane cannot overwrite existing sound at the same time.
            audioTrack = new ProjectStore.Track();
            audioTrack.id = UUID.randomUUID().toString(); audioTrack.type = "audio"; audioTrack.name = "Extracted audio";
            for (ProjectStore.Track track : project.tracks) audioTrack.order = Math.max(audioTrack.order, track.order + 1);
            project.tracks.add(audioTrack);
        }
        ProjectStore.Clip audio = cloneClip(clip);
        audio.id = UUID.randomUUID().toString(); audio.trackId = audioTrack.id; audio.title = "";
        audio.effects = audioEffects(clip.effects);
        if (clip.effects.optBoolean("audioDetached", false)) audio.effects.put("audioDetached", true);
        if (!isAudioTransition(audio.transition)) audio.transition = "none";
        String groupId = clip.linkGroupId == null || clip.linkGroupId.isEmpty() ? UUID.randomUUID().toString() : clip.linkGroupId;
        for (ProjectStore.Clip peer : group) peer.linkGroupId = groupId;
        audio.linkGroupId = groupId;
        clip.effects.put("audioDetached", true);
        clip.effects.put("audioExtractionDetached", true);
        project.clips.add(project.clips.indexOf(clip) + 1, audio);
    }

    /** Returns false for independent clips or non-timing edits. */
    static boolean applyTiming(ProjectStore.Project project, ProjectStore.Clip selected, String operation, JSONObject settings) throws Exception {
        if (selected.linkGroupId == null || selected.linkGroupId.isEmpty()) return false;
        switch (operation) {
            case "split": case "trim": case "move": case "duplicate": case "delete": case "remove": case "ripple_delete": break;
            case "properties": case "clip": case "speed":
                if (!settings.has("speed")) return false;
                break;
            default: return false;
        }
        List<ProjectStore.Clip> group = members(project, selected);
        requireUnlocked(project, group);
        long oldStart = selected.startMs, oldEnd = selected.endMs(), oldDuration = selected.outputDurationMs();
        Set<String> originalIds = ids(group), tracks = tracks(group);
        switch (operation) {
            case "split": {
                long at = settings.has("atMs") ? settings.getLong("atMs") : settings.getLong("positionMs");
                if (at <= oldStart || at >= oldEnd) throw new IllegalArgumentException("Split must be inside the linked clips");
                long firstDuration = at - oldStart;
                String leftGroup = UUID.randomUUID().toString(), rightGroup = UUID.randomUUID().toString();
                ArrayList<ProjectStore.Clip> seconds = new ArrayList<>();
                for (ProjectStore.Clip clip : group) {
                    long cut = clip.sourceTimeMs(at);
                    if (cut <= clip.inMs || cut >= clip.outMs) throw new IllegalArgumentException("Split is too close to a linked source boundary");
                    ProjectStore.Clip second = cloneClip(clip);
                    second.id = UUID.randomUUID().toString(); second.inMs = cut; second.startMs = at;
                    clip.outMs = cut; clip.programDurationMs = firstDuration; second.programDurationMs = oldDuration - firstDuration;
                    long duration = clip.effects.optLong("animationDurationMs", oldDuration);
                    long offset = clip.effects.optLong("animationOffsetMs", 0L);
                    animationWindow(clip, duration, offset); animationWindow(second, duration, ProjectTimeline.safeAdd(offset, firstDuration));
                    clip.linkGroupId = leftGroup; second.linkGroupId = rightGroup; seconds.add(second);
                }
                for (int index = 0; index < group.size(); index++) project.clips.add(project.clips.indexOf(group.get(index)) + 1, seconds.get(index));
                return true;
            }
            case "trim": {
                long in = settings.has("inMs") ? settings.getLong("inMs") : selected.inMs;
                long out = settings.has("outMs") ? settings.getLong("outMs") : selected.outMs;
                if (in < 0L || out <= in) throw new IllegalArgumentException("Clip source range must be positive");
                long start = settings.has("startMs") ? settings.getLong("startMs") : oldStart;
                double headDelta = (in - selected.inMs) / (double) selected.effectiveSpeed();
                double tailDelta = (out - selected.outMs) / (double) selected.effectiveSpeed();
                boolean sourceChanged = in != selected.inMs || out != selected.outMs;
                long duration = sourceChanged ? Math.round((out - in) / (double) selected.speed) : oldDuration;
                if (duration <= 0L) throw new IllegalArgumentException("Linked trim duration is too short");
                for (ProjectStore.Clip clip : group) {
                    long priorIn = clip.inMs, priorOut = clip.outMs; float rate = clip.effectiveSpeed();
                    clip.inMs = clip == selected ? in : ProjectTimeline.safeAdd(priorIn, Math.round(headDelta * rate));
                    clip.outMs = clip == selected ? out : ProjectTimeline.safeAdd(priorOut, Math.round(tailDelta * rate));
                    clip.startMs = start; clip.programDurationMs = duration;
                    if (sourceChanged) animationWindow(clip, clip.effects.optLong("animationDurationMs", oldDuration),
                            ProjectTimeline.safeAdd(clip.effects.optLong("animationOffsetMs", 0L), Math.round(headDelta)));
                }
                if (settings.optBoolean("ripple", false)) shiftFollowing(project, tracks, oldEnd, selected.endMs() - oldEnd, originalIds);
                return true;
            }
            case "move": {
                long start = settings.has("startMs") ? settings.getLong("startMs") : settings.has("positionMs") ? settings.getLong("positionMs") : oldStart;
                String targetId = settings.optString("trackId", selected.trackId);
                ProjectStore.Track target = project.track(targetId);
                if (target == null) throw new IllegalArgumentException("Track not found");
                if (target.locked) throw new IllegalStateException("Track is locked: " + target.name);
                for (ProjectStore.Clip clip : group) clip.startMs = start;
                selected.trackId = targetId;
                return true;
            }
            case "duplicate": {
                long start = settings.has("startMs") ? settings.getLong("startMs") : oldEnd;
                String groupId = UUID.randomUUID().toString();
                ArrayList<ProjectStore.Clip> duplicates = new ArrayList<>();
                for (ProjectStore.Clip clip : group) {
                    ProjectStore.Clip duplicate = cloneClip(clip);
                    duplicate.id = UUID.randomUUID().toString(); duplicate.startMs = start; duplicate.linkGroupId = groupId;
                    if (clip == selected) duplicate.trackId = settings.optString("trackId", clip.trackId);
                    duplicates.add(duplicate);
                }
                requireUnlocked(project, duplicates);
                if (settings.optBoolean("ripple", !settings.has("startMs"))) shiftFollowing(project, tracks(duplicates), start, oldDuration, originalIds);
                for (int index = 0; index < group.size(); index++) project.clips.add(project.clips.indexOf(group.get(index)) + 1, duplicates.get(index));
                return true;
            }
            case "delete": case "remove": case "ripple_delete": {
                project.clips.removeAll(group);
                if (operation.equals("ripple_delete") || settings.optBoolean("ripple", false)) shiftFollowing(project, tracks, oldEnd, -oldDuration, originalIds);
                return true;
            }
            default: {
                float rate = (float) settings.getDouble("speed");
                if (!Float.isFinite(rate) || rate < .1f || rate > 16f) throw new IllegalArgumentException("Speed must be between 0.1 and 16");
                if (rate != selected.speed) {
                    long duration = Math.round((selected.outMs - selected.inMs) / (double) rate);
                    if (duration <= 0L) throw new IllegalArgumentException("Linked speed duration is too short");
                    for (ProjectStore.Clip clip : group) {
                        retimeAnimations(clip, oldDuration, duration);
                        clip.speed = clip == selected ? rate : (float) ((clip.outMs - clip.inMs) / (double) duration);
                        clip.programDurationMs = duration;
                    }
                }
                if (settings.has("volume")) selected.volume = (float) settings.getDouble("volume");
                if (settings.has("title")) selected.title = settings.getString("title");
                if (settings.has("transition")) selected.transition = settings.getString("transition");
                if (settings.optBoolean("ripple", false)) shiftFollowing(project, tracks, oldEnd, selected.endMs() - oldEnd, originalIds);
                return true;
            }
        }
    }

    /** Ripple expands linked peers and shifts each member exactly once. */
    static void shiftFollowing(ProjectStore.Project project, Set<String> tracks, long threshold, long delta, Set<String> except) {
        if (delta == 0L) return;
        ArrayList<ProjectStore.Clip> affected = new ArrayList<>(); Set<String> included = new HashSet<>();
        for (ProjectStore.Clip clip : project.clips) if (tracks.contains(clip.trackId) && clip.startMs >= threshold && !except.contains(clip.id)) {
            for (ProjectStore.Clip peer : members(project, clip)) if (!except.contains(peer.id) && included.add(peer.id)) affected.add(peer);
        }
        requireUnlocked(project, affected);
        for (ProjectStore.Clip clip : affected) {
            clip.startMs = ProjectTimeline.safeAdd(clip.startMs, delta);
            if (clip.startMs < 0L) throw new IllegalArgumentException("Ripple would move a clip before the program start");
        }
    }

    private static JSONObject audioEffects(JSONObject original) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"audio", "ease", "bezier", "audioEasing", "audioBezier", "transitionDurationMs", "animationDurationMs", "animationOffsetMs"})
            if (original.has(key)) result.put(key, original.get(key));
        for (String key : new String[]{"keyframes", "audioKeyframes"}) {
            JSONArray frames = original.optJSONArray(key); if (frames == null) continue;
            JSONArray audioFrames = new JSONArray();
            for (int index = 0; index < frames.length(); index++) {
                JSONObject frame = frames.optJSONObject(index); if (frame == null || !frame.has("volume")) continue;
                JSONObject audioFrame = new JSONObject();
                for (String property : new String[]{"volume", "t", "timeMs", "timeUs", "easing", "bezier"})
                    if (frame.has(property)) audioFrame.put(property, frame.get(property));
                // Missing time uses the original mixed track's index, not the filtered index.
                if (!frame.has("t") && !frame.has("timeMs") && !frame.has("timeUs"))
                    audioFrame.put("t", index / (double) Math.max(1, frames.length() - 1));
                audioFrames.put(audioFrame);
            }
            if (audioFrames.length() > 0) result.put(key, audioFrames);
        }
        return new JSONObject(result.toString());
    }

    static void retimeAnimations(ProjectStore.Clip clip, long oldDuration, long newDuration) throws Exception {
        if (oldDuration <= 0L || newDuration <= 0L) throw new IllegalArgumentException("Animation retime requires positive output durations");
        double ratio = newDuration / (double) oldDuration;
        JSONObject effects = clip.effects;
        effects.put("animationDurationMs", Math.max(1L, scaledClock(effects.optLong("animationDurationMs", oldDuration), ratio)));
        effects.put("animationOffsetMs", scaledClock(effects.optLong("animationOffsetMs", 0L), ratio));
        if (effects.has("transitionDurationMs") || !"none".equals(clip.transition) && !"cut".equals(clip.transition))
            effects.put("transitionDurationMs", Math.max(0L, scaledClock(effects.optLong("transitionDurationMs", 280L), ratio)));
        retimeFrames(effects.optJSONArray("keyframes"), ratio); retimeFrames(effects.optJSONArray("audioKeyframes"), ratio);
        if (effects.has("rig2d"))
            effects.put("rig2d", AnimationRig2D.retime(effects.getJSONObject("rig2d"), ratio));
        JSONObject spec = effects.optJSONObject("animationSpec");
        if (spec != null) {
            if (spec.has("durationMs")) spec.put("durationMs", Math.max(1L, scaledClock(spec.getLong("durationMs"), ratio)));
            retimeFrames(spec.optJSONArray("keyframes"), ratio);
        }
        Set<JSONObject> retimedStages = new HashSet<>();
        for (String stackKey : new String[]{"stack", "effectStack"}) {
            JSONArray stack = effects.optJSONArray(stackKey); if (stack == null) continue;
            for (int index = 0; index < stack.length(); index++) {
                JSONObject entry = stack.optJSONObject(index); if (entry == null) continue;
                JSONObject stage = entry.optJSONObject("settings"); if (stage == null) stage = entry;
                if (!retimedStages.add(stage)) continue;
                // Disabled stages still follow the clip's authored clock when re-enabled.
                for (String key : new String[]{"animationDurationMs", "animationOffsetMs", "transitionDurationMs"})
                    if (stage.has(key)) stage.put(key, key.equals("animationDurationMs")
                            ? Math.max(1L, scaledClock(stage.getLong(key), ratio)) : scaledClock(stage.getLong(key), ratio));
                retimeFrames(stage.optJSONArray("keyframes"), ratio); retimeFrames(stage.optJSONArray("audioKeyframes"), ratio);
            }
        }
    }

    private static long scaledClock(long value, double ratio) {
        double scaled = value * ratio;
        if (!Double.isFinite(scaled) || scaled < -Long.MAX_VALUE / 1000d || scaled > Long.MAX_VALUE / 1000d)
            throw new IllegalArgumentException("Retimed animation clock exceeds supported media timing");
        return Math.round(scaled);
    }

    private static void retimeFrames(JSONArray frames, double ratio) throws Exception {
        if (frames == null) return;
        for (int index = 0; index < frames.length(); index++) {
            JSONObject frame = frames.optJSONObject(index); if (frame == null) continue;
            for (String key : new String[]{"timeMs", "timeUs"}) if (frame.has(key)) {
                double time = frame.getDouble(key) * ratio;
                if (!Double.isFinite(time) || time < 0d || time > Long.MAX_VALUE / (key.equals("timeMs") ? 1000d : 1d))
                    throw new IllegalArgumentException("Retimed keyframe exceeds supported media timing");
                frame.put(key, time);
            }
        }
    }

    private static boolean isAudioTransition(String transition) {
        return "none".equals(transition) || "cut".equals(transition) || "fade".equals(transition)
                || "dip_black".equals(transition) || "dip_white".equals(transition);
    }
    static void animationWindow(ProjectStore.Clip clip, long duration, long offset) throws Exception {
        long previousOffset = clip.effects.optLong("animationOffsetMs", 0L);
        long delta = ProjectTimeline.safeAdd(offset, -previousOffset);
        Set<JSONObject> shiftedStages = new HashSet<>();
        for (String stackKey : new String[]{"stack", "effectStack"}) {
            JSONArray stack = clip.effects.optJSONArray(stackKey); if (stack == null) continue;
            for (int index = 0; index < stack.length(); index++) {
                JSONObject entry = stack.optJSONObject(index); if (entry == null) continue;
                JSONObject stage = entry.optJSONObject("settings"); if (stage == null) stage = entry;
                if (shiftedStages.add(stage) && stage.has("animationOffsetMs"))
                    stage.put("animationOffsetMs", ProjectTimeline.safeAdd(stage.getLong("animationOffsetMs"), delta));
            }
        }
        clip.effects.put("animationDurationMs", duration); clip.effects.put("animationOffsetMs", offset);
    }
    private static boolean groupEquals(String left, String right) { return left == null ? right == null || right.isEmpty() : left.equals(right); }
    private static ProjectStore.Clip cloneClip(ProjectStore.Clip clip) throws Exception { return ProjectStore.Clip.fromJson(new JSONObject(clip.toJson().toString())); }
    private static Set<String> ids(List<ProjectStore.Clip> clips) { Set<String> result = new HashSet<>(); for (ProjectStore.Clip clip : clips) result.add(clip.id); return result; }
    private static Set<String> tracks(List<ProjectStore.Clip> clips) { Set<String> result = new HashSet<>(); for (ProjectStore.Clip clip : clips) result.add(clip.trackId); return result; }
}
