package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure project timing and editing rules shared by owner UI, MCP and recovery. */
public final class ProjectTimeline {
    private ProjectTimeline() {}

    public static boolean isAudioTrack(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.US);
        return t.equals("audio") || t.startsWith("audio_") || t.equals("music") || t.equals("dialogue")
                || t.equals("sfx") || t.equals("voiceover") || t.equals("voice_over");
    }

    public static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) throw new IllegalArgumentException("Timeline duration is too large");
        if (b < 0 && a < Long.MIN_VALUE - b) throw new IllegalArgumentException("Timeline position is too small");
        return a + b;
    }

    public static ProjectStore.Track defaultTrack(ProjectStore.Project project, String type) {
        String requested = type == null || type.trim().isEmpty() ? "video" : type.toLowerCase(Locale.US);
        for (ProjectStore.Track track : project.renderTracks()) if (requested.equals(track.type)) return track;
        ProjectStore.Track track = new ProjectStore.Track();
        String stableId = requested + "-main";
        track.id = project.track(stableId) == null ? stableId : UUID.randomUUID().toString();
        track.type = requested;
        track.name = isAudioTrack(requested) ? "Audio" : requested.equals("video") ? "Video" : title(requested);
        track.order = nextTrackOrder(project);
        project.tracks.add(track);
        return track;
    }

    /** Legacy clips acquire stable tracks and cumulative positions once on read. */
    public static void normalize(ProjectStore.Project project) {
        boolean legacySequential = project.tracks.isEmpty();
        for (ProjectStore.Clip clip : project.clips)
            if (clip.startMs != -1L || (clip.trackId != null && !clip.trackId.isEmpty())) legacySequential = false;
        long legacyCursor = 0L;
        for (ProjectStore.Track track : project.tracks) {
            if (track.id == null || track.id.isEmpty()) track.id = UUID.randomUUID().toString();
            if (track.type == null || track.type.trim().isEmpty()) track.type = "video";
            track.type = track.type.toLowerCase(Locale.US);
            if (track.name == null || track.name.trim().isEmpty()) track.name = title(track.type);
        }
        if (project.tracks.isEmpty()) defaultTrack(project, "video");
        Map<String, Long> ends = new HashMap<>();
        for (ProjectStore.Clip clip : project.clips) {
            if (clip.id == null || clip.id.isEmpty()) clip.id = UUID.randomUUID().toString();
            if (clip.linkGroupId == null) clip.linkGroupId = "";
            ProjectStore.Asset asset = project.asset(clip.assetId);
            // Early imports padded short video to one second. Retain the media,
            // but normalize that legacy source range to its real duration.
            if (legacySequential && asset != null && asset.mime != null && !asset.mime.startsWith("image/")
                    && asset.durationMs > clip.inMs && clip.outMs > asset.durationMs) clip.outMs = asset.durationMs;
            String type = asset != null && asset.mime != null && asset.mime.startsWith("audio/") ? "audio" : "video";
            if (clip.trackId == null || clip.trackId.isEmpty()) clip.trackId = defaultTrack(project, type).id;
            else if (project.track(clip.trackId) == null) {
                // Preserve a stored clip's track identity rather than silently moving its content.
                ProjectStore.Track track = new ProjectStore.Track();
                track.id = clip.trackId; track.type = type; track.name = title(type); track.order = nextTrackOrder(project);
                project.tracks.add(track);
            }
            if (clip.effects == null) clip.effects = new JSONObject();
            if (legacySequential) { clip.startMs = legacyCursor; legacyCursor = clip.endMs(); }
            if (clip.startMs >= 0L) ends.put(clip.trackId, Math.max(ends.getOrDefault(clip.trackId, 0L), clip.endMs()));
        }
        for (ProjectStore.Clip clip : project.clips) if (clip.startMs == -1L) {
            clip.startMs = ends.getOrDefault(clip.trackId, 0L);
            ends.put(clip.trackId, clip.endMs());
        }
    }

    /**
     * Old callers edit the flat clip list then save. Preserve their dense single
     * track behavior only when they did not explicitly change track or position.
     * The transaction API never invokes this compatibility reflow.
     */
    static void preserveLegacySequence(ProjectStore.Project before, ProjectStore.Project next) {
        if (before.clips.isEmpty()) return;
        String trackId = before.clips.get(0).trackId;
        long cursor = 0L;
        for (ProjectStore.Clip clip : before.clips) {
            if (!trackId.equals(clip.trackId) || clip.startMs != cursor) return;
            cursor = clip.endMs();
        }
        for (ProjectStore.Clip clip : next.clips) {
            ProjectStore.Clip old = before.clip(clip.id);
            if (old != null && (!trackId.equals(clip.trackId) || old.startMs != clip.startMs)) return;
            if (old == null && clip.trackId != null && !clip.trackId.isEmpty() && !trackId.equals(clip.trackId)) return;
            if (old == null && clip.startMs >= 0L) return;
        }
        cursor = 0L;
        for (ProjectStore.Clip clip : next.clips) {
            ProjectStore.Asset asset = next.asset(clip.assetId);
            if (asset != null && asset.mime != null && asset.mime.startsWith("audio/")) return;
        }
        for (ProjectStore.Clip clip : next.clips) {
            clip.trackId = trackId; clip.startMs = cursor; cursor = clip.endMs();
        }
    }

    public static void validate(ProjectStore.Project project) {
        ProjectMarkers.validate(project);
        if (project.animationFrameRate != 0 && !AnimationCelEdits.supportedFrameRate(project.animationFrameRate))
            throw new IllegalArgumentException("Animation output frame rate must be 12, 24, 30 or 60 fps");
        Set<String> assetIds = new HashSet<>();
        for (ProjectStore.Asset asset : project.assets) {
            if (asset.id == null || asset.id.isEmpty() || !assetIds.add(asset.id))
                throw new IllegalArgumentException("Assets require unique stable IDs");
        }
        Set<String> trackIds = new HashSet<>();
        for (ProjectStore.Track track : project.tracks) {
            if (track.id == null || track.id.isEmpty() || !trackIds.add(track.id))
                throw new IllegalArgumentException("Tracks require unique stable IDs");
            if (!validTrackType(track.type)) throw new IllegalArgumentException("Unsupported track type: " + track.type);
        }
        Set<String> clipIds = new HashSet<>();
        Map<String, List<ProjectStore.Clip>> linkGroups = new HashMap<>();
        for (ProjectStore.Clip clip : project.clips) {
            if (clip.id == null || clip.id.isEmpty() || !clipIds.add(clip.id))
                throw new IllegalArgumentException("Clips require unique stable IDs");
            ProjectStore.Asset asset = project.asset(clip.assetId);
            ProjectStore.Track track = project.track(clip.trackId);
            if (asset == null) throw new IllegalArgumentException("Clip references an unknown asset");
            if (track == null) throw new IllegalArgumentException("Clip references an unknown track");
            if (clip.startMs < 0L || clip.inMs < 0L || clip.outMs <= clip.inMs)
                throw new IllegalArgumentException("Clip source range and program timing must be positive");
            if (Float.isNaN(clip.speed) || Float.isInfinite(clip.speed) || clip.speed < .1f || clip.speed > 16f)
                throw new IllegalArgumentException("Speed must be between 0.1 and 16");
            if (Float.isNaN(clip.volume) || Float.isInfinite(clip.volume) || clip.volume < 0f || clip.volume > 2f)
                throw new IllegalArgumentException("Volume must be between 0 and 2");
            if (clip.outputDurationMs() <= 0L) throw new IllegalArgumentException("Clip duration is too short");
            if (clip.programDurationMs < -1L) throw new IllegalArgumentException("Program duration is invalid");
            if (clip.programDurationMs >= 0L) {
                long nominal = Math.round((clip.outMs - clip.inMs) / (double) clip.speed);
                long tolerance = Math.max(2L, (long) Math.ceil(2d / clip.speed));
                if (Math.abs(clip.programDurationMs - nominal) > tolerance)
                    throw new IllegalArgumentException("Program duration exceeds source timing precision");
            }
            String mime = asset.mime == null ? "" : asset.mime;
            if (!mime.startsWith("image/") && asset.durationMs > 0L && clip.outMs > asset.durationMs)
                throw new IllegalArgumentException("Trim exceeds the source duration");
            if (mime.startsWith("audio/") && !track.isAudio())
                throw new IllegalArgumentException("Audio media requires an audio track");
            if (clip.effects.has("audioDetached") && !(clip.effects.opt("audioDetached") instanceof Boolean))
                throw new IllegalArgumentException("audioDetached must be boolean");
            if (clip.effects.has("audioExtractionDetached") && !(clip.effects.opt("audioExtractionDetached") instanceof Boolean))
                throw new IllegalArgumentException("audioExtractionDetached must be boolean");
            if (clip.effects.optBoolean("audioExtractionDetached", false) && !clip.effects.optBoolean("audioDetached", false))
                throw new IllegalArgumentException("Extracted embedded audio must remain suppressed until explicitly restored");
            if (clip.linkGroupId != null && !clip.linkGroupId.isEmpty()) {
                if (clip.linkGroupId.length() > 128 || !clip.linkGroupId.equals(clip.linkGroupId.trim()))
                    throw new IllegalArgumentException("Clip link group ID is invalid");
                linkGroups.computeIfAbsent(clip.linkGroupId, key -> new ArrayList<>()).add(clip);
            }
            clip.endMs(); // Check overflow before persistence.
        }
        for (List<ProjectStore.Clip> group : linkGroups.values()) {
            if (group.size() < 2) throw new IllegalArgumentException("Linked clips require at least two members; unlink the remaining clip");
            ProjectStore.Clip first = group.get(0);
            for (ProjectStore.Clip peer : group) if (peer.startMs != first.startMs || peer.endMs() != first.endMs())
                throw new IllegalArgumentException("Linked clips must have identical program start and end; use shared timeline edits or unlink first");
        }
        for (ProjectStore.Track track : project.tracks) {
            long previousEnd = -1L;
            for (ProjectStore.Clip clip : project.clipsOnTrack(track.id)) {
                if (clip.startMs < previousEnd)
                    throw new IllegalArgumentException("Clips overlap on " + track.name + "; move one to another track");
                previousEnd = clip.endMs();
            }
        }
    }

    static void validateLocks(ProjectStore.Project before, ProjectStore.Project next) {
        for (ProjectStore.Track track : before.tracks) {
            ProjectStore.Track target = next.track(track.id);
            if (!track.locked || (target != null && !target.locked)) continue;
            if (target == null) throw new IllegalStateException("Track is locked: " + track.name);
            for (ProjectStore.Clip old : before.clipsOnTrack(track.id)) {
                ProjectStore.Clip current = next.clip(old.id);
                if (current == null || !old.toJson().toString().equals(current.toJson().toString()))
                    throw new IllegalStateException("Track is locked: " + track.name);
            }
            for (ProjectStore.Clip current : next.clipsOnTrack(track.id)) {
                ProjectStore.Clip old = before.clip(current.id);
                if (old == null || !track.id.equals(old.trackId)) throw new IllegalStateException("Track is locked: " + track.name);
            }
        }
    }

    /** Validate newly authored rigs without hiding older opaque effect payloads on read. */
    static void validateRigChanges(ProjectStore.Project before, ProjectStore.Project next) {
        for (ProjectStore.Clip clip : next.clips) {
            if (!clip.effects.has("rig2d")) continue;
            ProjectStore.Clip previous = before == null ? null : before.clip(clip.id);
            Object rig = clip.effects.opt("rig2d");
            Object prior = previous == null ? null : previous.effects.opt("rig2d");
            if (prior != null && prior.toString().equals(String.valueOf(rig))) continue;
            if (!(rig instanceof JSONObject)) throw new IllegalArgumentException("rig2d must be a rig settings object");
            AnimationRig2D.validate((JSONObject) rig);
        }
    }

    public static void apply(ProjectStore.Project project, String operation, JSONObject settings) throws Exception {
        normalize(project);
        String op = operation == null ? "" : operation.toLowerCase(Locale.US);
        if ((settings.has("startMs") && settings.getLong("startMs") < 0L)
                || (settings.has("positionMs") && settings.getLong("positionMs") < 0L))
            throw new IllegalArgumentException("Program start cannot be negative");
        if (op.equals("marker_add")) { ProjectMarkers.add(project, settings); return; }
        if (op.equals("marker_update")) { ProjectMarkers.update(project, settings); return; }
        if (op.equals("marker_delete")) { ProjectMarkers.remove(project, settings); return; }
        if (op.equals("editor_range_set")) { ProjectMarkers.setRange(project, settings); return; }
        if (op.equals("editor_range_clear")) {
            if (settings.length() != 0) throw new IllegalArgumentException("Clear program range does not accept settings");
            ProjectMarkers.clearRange(project);
            return;
        }
        if (op.startsWith("cel_exposure_") || op.equals("animation_frame_rate")) {
            AnimationCelEdits.apply(project, op, settings);
            return;
        }
        if (op.equals("add_track") || op.equals("create_track")) {
            addTrack(project, settings);
            return;
        }
        if (op.equals("track") || op.equals("track_flags")) {
            ProjectStore.Track track = requireTrack(project, settings.optString("trackId", ""));
            if (settings.has("locked")) track.locked = settings.getBoolean("locked");
            if (settings.has("muted")) track.muted = settings.getBoolean("muted");
            if (settings.has("solo")) track.solo = settings.getBoolean("solo");
            if (settings.has("visible")) track.visible = settings.getBoolean("visible");
            if (settings.has("name")) {
                String name = settings.getString("name").trim();
                if (name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("Track name must contain 1 to 120 characters");
                track.name = name;
            }
            if (settings.has("order") || settings.has("direction")) {
                List<ProjectStore.Track> ordered = project.renderTracks();
                int previous = ordered.indexOf(track);
                int destination;
                if (settings.has("order")) {
                    destination = settings.getInt("order");
                    if (destination < 0 || destination >= ordered.size()) throw new IllegalArgumentException("Track order is outside the track list");
                } else {
                    String direction = settings.getString("direction");
                    if (!direction.equals("up") && !direction.equals("down")) throw new IllegalArgumentException("Track direction must be up or down");
                    destination = Math.max(0, Math.min(ordered.size() - 1, previous + (direction.equals("up") ? -1 : 1)));
                }
                ordered.remove(track); ordered.add(destination, track);
                for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
            }
            return;
        }
        if (op.equals("remove_track") || op.equals("delete_track")) {
            ProjectStore.Track track = requireTrack(project, settings.optString("trackId", ""));
            if (track.locked) throw new IllegalStateException("Track is locked: " + track.name);
            if (!project.clipsOnTrack(track.id).isEmpty()) throw new IllegalStateException("Move or delete the track's clips first");
            if (project.tracks.size() <= 1) throw new IllegalStateException("Project needs at least one track");
            project.tracks.remove(track);
            List<ProjectStore.Track> ordered = project.renderTracks();
            for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
            return;
        }
        if (op.equals("append") || op.equals("insert_asset") || op.equals("append_asset")) {
            insertAsset(project, settings);
            return;
        }
        if (op.equals("link") || op.equals("link_clips")) {
            link(project, settings);
            return;
        }
        if (op.equals("roll") || op.equals("slip") || op.equals("slide")) {
            ProjectAdvancedEdits.apply(project, op, settings);
            return;
        }
        if (op.equals("relink_asset")) {
            String targetId = settings.optString("assetId", "");
            String replacementId = settings.optString("replacementAssetId", "");
            if (targetId.equals(replacementId)) throw new IllegalArgumentException("Select a different imported source as the replacement");
            ProjectStore.Asset replacement = project.asset(replacementId);
            if (replacement == null) throw new IllegalArgumentException("Import the replacement into this project before relinking");
            ProjectAssetRelinking.apply(project, targetId, replacement, settings.optBoolean("preserveName", true));
            return;
        }
        if (op.equals("replace_timeline")) {
            JSONArray raw = settings.optJSONArray("clips");
            if (raw == null) throw new IllegalArgumentException("clips are required");
            Map<String, ProjectStore.Clip> previous = new HashMap<>();
            for (ProjectStore.Clip old : project.clips) previous.put(old.id, old);
            project.clips.clear();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject entry = raw.optJSONObject(i);
                if (entry == null) throw new IllegalArgumentException("Clip must be an object");
                ProjectStore.Clip clip = ProjectStore.Clip.fromJson(entry);
                if (!entry.has("id") || entry.optString("id").isEmpty()) clip.id = UUID.randomUUID().toString();
                ProjectStore.Clip old = previous.get(clip.id);
                if (old != null && old.linkGroupId != null && !old.linkGroupId.isEmpty()
                        && !old.linkGroupId.equals(clip.linkGroupId))
                    throw new IllegalArgumentException("Replacement must preserve existing clip link groups; use link or unlink explicitly");
                if (old != null && old.effects.optBoolean("audioDetached", false)
                        && !clip.effects.optBoolean("audioDetached", false))
                    throw new IllegalArgumentException("Replacement must preserve detached embedded audio");
                if (old != null && old.effects.has("audioExtractionDetached")
                        && (!clip.effects.has("audioExtractionDetached")
                        || !old.effects.opt("audioExtractionDetached").equals(clip.effects.opt("audioExtractionDetached"))))
                    throw new IllegalArgumentException("Replacement must preserve embedded audio extraction ownership");
                project.clips.add(clip);
            }
            normalize(project);
            return;
        }
        ProjectStore.Clip clip = requireClip(project, settings);
        ProjectStore.Track track = requireTrack(project, clip.trackId);
        if (track.locked) throw new IllegalStateException("Track is locked: " + track.name);
        if (op.equals("extract_audio") || op.equals("detach_audio")) {
            extractAudio(project, clip, settings);
            return;
        }
        if (op.equals("unlink") || op.equals("unlink_clips")) {
            List<ProjectStore.Clip> group = linkedClips(project, clip);
            requireUnlocked(project, group);
            for (ProjectStore.Clip peer : group) peer.linkGroupId = "";
            return;
        }
        if (applyLinkedTiming(project, clip, op, settings)) return;
        switch (op) {
            case "split": {
                long at = settings.has("atMs") ? settings.getLong("atMs") : settings.getLong("positionMs");
                if (at <= clip.startMs || at >= clip.endMs()) throw new IllegalArgumentException("Split must be inside the clip");
                long originalDuration = clip.outputDurationMs();
                long firstDuration = at - clip.startMs;
                long sourceCut = clip.sourceTimeMs(at);
                if (sourceCut <= clip.inMs || sourceCut >= clip.outMs) throw new IllegalArgumentException("Split is too close to the source boundary");
                ProjectStore.Clip second = cloneClip(clip);
                second.id = UUID.randomUUID().toString();
                second.inMs = sourceCut;
                clip.outMs = sourceCut;
                clip.programDurationMs = firstDuration;
                second.programDurationMs = originalDuration - firstDuration;
                second.startMs = at;
                long animationDuration = clip.effects.optLong("animationDurationMs", originalDuration);
                long animationOffset = clip.effects.optLong("animationOffsetMs", 0L);
                ProjectLinkedEdits.animationWindow(clip, animationDuration, animationOffset);
                ProjectLinkedEdits.animationWindow(second, animationDuration, safeAdd(animationOffset, firstDuration));
                // Both windows retain the original transition, evaluated on its shared animation clock.
                second.transition = clip.transition;
                project.clips.add(project.clips.indexOf(clip) + 1, second);
                break;
            }
            case "trim": {
                long oldEnd = clip.endMs(), oldIn = clip.inMs, oldOut = clip.outMs;
                long oldDuration = clip.outputDurationMs();
                float oldRate = clip.effectiveSpeed();
                if (settings.has("inMs")) clip.inMs = settings.getLong("inMs");
                if (settings.has("outMs")) clip.outMs = settings.getLong("outMs");
                if (clip.inMs != oldIn || clip.outMs != oldOut) {
                    clip.programDurationMs = -1L;
                    long animationDuration = clip.effects.optLong("animationDurationMs", oldDuration);
                    long animationOffset = clip.effects.optLong("animationOffsetMs", 0L);
                    ProjectLinkedEdits.animationWindow(clip, animationDuration,
                            safeAdd(animationOffset, Math.round((clip.inMs - oldIn) / (double) oldRate)));
                }
                if (settings.has("startMs")) clip.startMs = settings.getLong("startMs");
                if (settings.optBoolean("ripple", false)) shiftFollowing(project, clip.trackId, oldEnd, clip.endMs() - oldEnd, clip.id);
                break;
            }
            case "move": {
                String destination = settings.optString("trackId", clip.trackId);
                ProjectStore.Track target = requireTrack(project, destination);
                if (target.locked) throw new IllegalStateException("Track is locked: " + target.name);
                clip.trackId = destination;
                if (settings.has("startMs")) clip.startMs = settings.getLong("startMs");
                else if (settings.has("positionMs")) clip.startMs = settings.getLong("positionMs");
                break;
            }
            case "duplicate": {
                ProjectStore.Clip duplicate = cloneClip(clip);
                duplicate.id = UUID.randomUUID().toString();
                duplicate.trackId = settings.optString("trackId", clip.trackId);
                ProjectStore.Track target = requireTrack(project, duplicate.trackId);
                if (target.locked) throw new IllegalStateException("Track is locked: " + target.name);
                duplicate.startMs = settings.has("startMs") ? settings.getLong("startMs") : clip.endMs();
                if (settings.optBoolean("ripple", !settings.has("startMs")))
                    shiftFollowing(project, duplicate.trackId, duplicate.startMs, duplicate.outputDurationMs(), clip.id);
                project.clips.add(project.clips.indexOf(clip) + 1, duplicate);
                break;
            }
            case "delete":
            case "remove":
            case "ripple_delete": {
                long end = clip.endMs(), duration = clip.outputDurationMs();
                project.clips.remove(clip);
                if (op.equals("ripple_delete") || settings.optBoolean("ripple", false))
                    shiftFollowing(project, clip.trackId, end, -duration, clip.id);
                break;
            }
            case "effects": {
                JSONObject effects = settings.optJSONObject("effects");
                if (effects == null) throw new IllegalArgumentException("effects are required");
                if (settings.optBoolean("replace", false)) {
                    JSONObject replacement = new JSONObject(effects.toString());
                    if (clip.effects.has("audioDetached") && !replacement.has("audioDetached"))
                        replacement.put("audioDetached", clip.effects.get("audioDetached"));
                    if (clip.effects.has("audioExtractionDetached") && !replacement.has("audioExtractionDetached"))
                        replacement.put("audioExtractionDetached", clip.effects.get("audioExtractionDetached"));
                    clip.effects = replacement;
                }
                else {
                    java.util.Iterator<String> keys = effects.keys();
                    while (keys.hasNext()) { String key = keys.next(); clip.effects.put(key, effects.get(key)); }
                }
                if (Boolean.FALSE.equals(clip.effects.opt("audioDetached"))) clip.effects.remove("audioExtractionDetached");
                break;
            }
            case "properties":
            case "clip":
            case "speed":
            case "volume":
            case "title": {
                long oldEnd = clip.endMs();
                long oldDuration = clip.outputDurationMs();
                if (settings.has("speed")) {
                    float rate = (float) settings.getDouble("speed");
                    if (!Float.isFinite(rate) || rate < .1f || rate > 16f) throw new IllegalArgumentException("Speed must be between 0.1 and 16");
                    if (rate != clip.speed) {
                        clip.speed = rate; clip.programDurationMs = -1L;
                        ProjectLinkedEdits.retimeAnimations(clip, oldDuration, clip.outputDurationMs());
                    }
                }
                if (settings.has("volume")) clip.volume = (float) settings.getDouble("volume");
                if (settings.has("title")) clip.title = settings.getString("title");
                if (settings.has("transition")) clip.transition = settings.getString("transition");
                if (settings.optBoolean("ripple", false)) shiftFollowing(project, clip.trackId, oldEnd, clip.endMs() - oldEnd, clip.id);
                break;
            }
            default: throw new IllegalArgumentException("Unsupported timeline edit: " + operation);
        }
    }

    public static ProjectStore.Clip appendAsset(ProjectStore.Project project, ProjectStore.Asset asset) throws Exception {
        if (asset == null) throw new IllegalArgumentException("Asset is required");
        JSONObject settings = new JSONObject(); settings.put("assetId", asset.id);
        return insertAsset(project, settings);
    }

    public static ProjectStore.Clip insertAsset(ProjectStore.Project project, JSONObject settings) throws Exception {
        if (settings.has("startMs") && settings.getLong("startMs") < 0L)
            throw new IllegalArgumentException("Program start cannot be negative");
        ProjectStore.Asset asset = project.asset(settings.optString("assetId", ""));
        if (asset == null) throw new IllegalArgumentException("Asset not found");
        String mime = asset.mime == null ? "" : asset.mime;
        if (!mime.startsWith("video/") && !mime.startsWith("image/") && !mime.startsWith("audio/"))
            throw new IllegalArgumentException("This asset cannot be inserted into the timeline");
        ProjectStore.Track track = settings.has("trackId") ? requireTrack(project, settings.getString("trackId"))
                : defaultTrack(project, mime.startsWith("audio/") ? "audio" : "video");
        if (track.locked) throw new IllegalStateException("Track is locked: " + track.name);
        ProjectStore.Clip clip = new ProjectStore.Clip();
        clip.id = UUID.randomUUID().toString(); clip.assetId = asset.id; clip.trackId = track.id;
        clip.startMs = settings.has("startMs") ? settings.getLong("startMs") : trackEnd(project, track.id);
        clip.inMs = settings.optLong("inMs", 0L);
        clip.outMs = settings.optLong("outMs", mime.startsWith("image/") ? 3000L : asset.durationMs > 0L ? asset.durationMs : 1000L);
        clip.speed = (float) settings.optDouble("speed", 1d); clip.volume = (float) settings.optDouble("volume", 1d);
        if (settings.optBoolean("ripple", false)) shiftFollowing(project, track.id, clip.startMs, clip.outputDurationMs(), clip.id);
        project.clips.add(clip);
        return clip;
    }

    private static void addTrack(ProjectStore.Project project, JSONObject settings) {
        String type = settings.optString("type", "video").toLowerCase(Locale.US);
        if (!validTrackType(type)) throw new IllegalArgumentException("Unsupported track type: " + type);
        ProjectStore.Track track = new ProjectStore.Track();
        track.id = UUID.randomUUID().toString(); track.type = type;
        track.name = settings.optString("name", title(type) + " " + (project.tracks.size() + 1));
        track.order = settings.optInt("order", nextTrackOrder(project));
        track.locked = settings.optBoolean("locked", false); track.muted = settings.optBoolean("muted", false);
        track.solo = settings.optBoolean("solo", false); track.visible = settings.optBoolean("visible", true);
        project.tracks.add(track);
    }

    private static ProjectStore.Clip requireClip(ProjectStore.Project project, JSONObject settings) {
        ProjectStore.Clip clip = settings.has("clipId") ? project.clip(settings.optString("clipId", "")) : null;
        if (clip == null && !settings.has("clipId")) {
            int index = settings.optInt("clipIndex", -1);
            if (index >= 0 && index < project.clips.size()) clip = project.clips.get(index);
        }
        if (clip == null) throw new IllegalArgumentException("Clip not found");
        return clip;
    }

    private static ProjectStore.Track requireTrack(ProjectStore.Project project, String id) {
        ProjectStore.Track track = project.track(id);
        if (track == null) throw new IllegalArgumentException("Track not found");
        return track;
    }

    private static ProjectStore.Clip cloneClip(ProjectStore.Clip clip) throws Exception {
        return ProjectStore.Clip.fromJson(new JSONObject(clip.toJson().toString()));
    }

    public static List<ProjectStore.Clip> linkedClips(ProjectStore.Project project, ProjectStore.Clip clip) {
        return ProjectLinkedEdits.members(project, clip);
    }

    private static void requireUnlocked(ProjectStore.Project project, List<ProjectStore.Clip> clips) {
        ProjectLinkedEdits.requireUnlocked(project, clips);
    }

    private static void link(ProjectStore.Project project, JSONObject settings) throws Exception {
        ProjectLinkedEdits.link(project, settings);
    }

    private static void extractAudio(ProjectStore.Project project, ProjectStore.Clip clip, JSONObject settings) throws Exception {
        ProjectLinkedEdits.extractAudio(project, clip, settings);
    }

    private static boolean applyLinkedTiming(ProjectStore.Project project, ProjectStore.Clip clip, String operation, JSONObject settings) throws Exception {
        return ProjectLinkedEdits.applyTiming(project, clip, operation, settings);
    }

    private static void shiftFollowing(ProjectStore.Project project, String trackId, long threshold, long delta, String except) {
        Set<String> tracks = new HashSet<>(), excluded = new HashSet<>();
        tracks.add(trackId); excluded.add(except);
        ProjectLinkedEdits.shiftFollowing(project, tracks, threshold, delta, excluded);
    }

    public static long trackEnd(ProjectStore.Project project, String trackId) {
        long end = 0L;
        for (ProjectStore.Clip clip : project.clips) if (trackId.equals(clip.trackId)) end = Math.max(end, clip.endMs());
        return end;
    }

    private static int nextTrackOrder(ProjectStore.Project project) {
        int order = 0;
        for (ProjectStore.Track track : project.tracks) order = Math.max(order, track.order + 1);
        return order;
    }

    private static boolean validTrackType(String type) {
        if (type == null) return false;
        switch (type) {
            case "video": case "image": case "graphics": case "overlay": case "adjustment":
            case "text": case "title": case "subtitle": case "animation": case "animation_2d":
            case "scene_3d": case "vfx": case "effects": case "control": case "automation":
            case "audio": case "audio_music": case "audio_dialogue": case "audio_sfx":
            case "music": case "dialogue": case "sfx": case "voiceover": case "voice_over": return true;
            default: return false;
        }
    }

    private static String title(String type) {
        if (type == null || type.isEmpty()) return "Track";
        String text = type.replace('_', ' ');
        return text.substring(0, 1).toUpperCase(Locale.US) + text.substring(1);
    }
}
