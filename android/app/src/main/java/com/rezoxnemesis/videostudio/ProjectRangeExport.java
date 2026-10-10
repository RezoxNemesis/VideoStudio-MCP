package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Immutable admission snapshot for an explicitly requested program range. */
public final class ProjectRangeExport {
    private static final long MAX_CLOCK_MS = Long.MAX_VALUE / 1000L;
    private static final int MAX_GRAPH_BYTES = 16 * 1024 * 1024;

    private ProjectRangeExport() {}

    public static final class Prepared {
        public final String sourceProjectId;
        public final long sourceRevision;
        public final long inMs;
        public final long outMs;
        public final long durationMs;
        /** True when an explicit composition terminal gap must extend the cropped graph. */
        public final boolean requiresTerminalGap;
        public final boolean audioOnly;
        public final int clipCount;
        private final String graphJson;
        private final String warningsJson;

        private Prepared(ProjectStore.Project project, long inMs, long outMs, boolean audioOnly, JSONArray warnings) {
            sourceProjectId = project.id; sourceRevision = project.revision;
            this.inMs = inMs; this.outMs = outMs; durationMs = outMs - inMs;
            requiresTerminalGap = project.outputDurationMs() < durationMs;
            this.audioOnly = audioOnly; clipCount = project.clips.size();
            graphJson = project.toJson().toString(); warningsJson = warnings.toString();
            if (graphJson.getBytes(StandardCharsets.UTF_8).length > MAX_GRAPH_BYTES)
                throw new IllegalArgumentException("Range export graph exceeds the 16 MiB metadata budget");
        }

        /** Callers receive a new graph, so edits cannot mutate the accepted range. */
        public ProjectStore.Project project() {
            try { return ProjectStore.Project.fromJson(new JSONObject(graphJson)); }
            catch (Exception failure) { throw new IllegalStateException("Accepted range export graph is unreadable", failure); }
        }

        public JSONArray warnings() {
            try { return new JSONArray(warningsJson); }
            catch (Exception failure) { throw new IllegalStateException("Range export warnings are unreadable", failure); }
        }

        /** Locator-free receipt metadata; this is an admitted plan, not an encoded output. */
        public JSONObject metadata() {
            JSONObject metadata = new JSONObject();
            put(metadata, "mode", "program_range");
            put(metadata, "sourceProjectId", sourceProjectId);
            put(metadata, "sourceProjectRevision", sourceRevision);
            put(metadata, "sourceInMs", inMs); put(metadata, "sourceOutMs", outMs);
            put(metadata, "durationMs", durationMs); put(metadata, "renderOriginMs", 0L);
            put(metadata, "requiresTerminalGap", requiresTerminalGap);
            put(metadata, "audioOnly", audioOnly); put(metadata, "clipCount", clipCount);
            put(metadata, "sourceBoundaryPrecision", "existing clip source clock, rounded to integer milliseconds");
            put(metadata, "warnings", warnings());
            return metadata;
        }
    }

    /**
     * Crops an accepted source revision without editing its persisted project.
     * The renderer must use Prepared.durationMs for actual sequence gaps and
     * output verification. Project.outputDurationMs alone can omit a terminal gap.
     */
    public static Prepared prepare(ProjectStore.Project source, long expectedRevision, long inMs, long outMs) {
        if (source == null) throw new IllegalArgumentException("Range export requires a project");
        if (expectedRevision < 0L) throw new IllegalArgumentException("Range export requires an accepted project revision");
        ProjectStore.Project original = ProjectStore.copy(source);
        if (original.revision != expectedRevision)
            throw new ProjectStore.RevisionConflictException(expectedRevision, original.revision);
        if (original.id == null || original.id.isEmpty()) throw new IllegalArgumentException("Range export requires a stable project ID");
        ProjectTimeline.validate(original);
        long programDuration = original.outputDurationMs();
        if (inMs < 0L || outMs <= inMs || outMs > programDuration || outMs > MAX_CLOCK_MS)
            throw new IllegalArgumentException("Export range must satisfy 0 <= In < Out <= program duration within the native clock");

        ProjectStore.Project cropped = ProjectStore.copy(original);
        cropped.clips.clear();
        // Owner annotations and playback selection remain on the original graph.
        cropped.markers = new JSONArray(); cropped.editorRange = new JSONObject();
        cropped.latestExportUri = ""; cropped.latestExportName = ""; cropped.latestExportAt = 0L;
        JSONArray warnings = new JSONArray();
        for (ProjectStore.Clip before : original.clips) {
            long cutStart = Math.max(inMs, before.startMs), cutEnd = Math.min(outMs, before.endMs());
            if (cutEnd <= cutStart) continue;
            long sourceIn = cutStart == before.startMs ? before.inMs : before.sourceTimeMs(cutStart);
            long sourceOut = cutEnd == before.endMs() ? before.outMs : before.sourceTimeMs(cutEnd);
            if (sourceIn < before.inMs || sourceOut > before.outMs || sourceOut <= sourceIn)
                throw new IllegalArgumentException("Range is too close to the source timing boundary of clip " + before.id
                        + "; integer millisecond source handles cannot represent this cut");
            if (sourceOut > MAX_CLOCK_MS) throw new IllegalArgumentException("Source time exceeds the native clock for clip " + before.id);
            ProjectStore.Clip clip = copyClip(before);
            long originalSpan = before.outputDurationMs();
            long headDelta = cutStart - before.startMs;
            clip.startMs = cutStart - inMs;
            clip.inMs = sourceIn; clip.outMs = sourceOut;
            // Like a razor split, keep the exact output span after source-ms rounding.
            clip.programDurationMs = cutEnd - cutStart;
            long authoredDuration = before.effects.optLong("animationDurationMs", originalSpan);
            long authoredOffset = before.effects.optLong("animationOffsetMs", 0L);
            long nextOffset = ProjectTimeline.safeAdd(authoredOffset, headDelta);
            if (authoredDuration <= 0L || authoredDuration > MAX_CLOCK_MS
                    || authoredOffset < -MAX_CLOCK_MS || authoredOffset > MAX_CLOCK_MS
                    || nextOffset < -MAX_CLOCK_MS || nextOffset > MAX_CLOCK_MS)
                throw new IllegalArgumentException("Authored animation clock exceeds the native range for clip " + before.id);
            try { ProjectLinkedEdits.animationWindow(clip, authoredDuration, nextOffset); }
            catch (Exception failure) { throw new IllegalArgumentException("Could not preserve the authored clock for clip " + before.id, failure); }
            cropped.clips.add(clip);
            ProjectStore.Track track = cropped.track(clip.trackId);
            ProjectStore.Asset asset = cropped.asset(clip.assetId);
            JSONObject audio = clip.effects.optJSONObject("audio");
            if (headDelta > 0L && track != null && cropped.isTrackAudible(track) && asset != null && asset.hasAudio
                    && !clip.effects.optBoolean("audioDetached", false) && audio != null && audio.optBoolean("enabled", true)
                    && warnings.length() < 32)
                warnings.put(clip.id + ": audio filter, dynamics and ambience state starts at the selected range boundary; no earlier PCM pre-roll is rendered");
        }
        ProjectTimeline.validate(cropped);
        long requestedDuration = outMs - inMs;
        for (ProjectStore.Clip clip : cropped.clips) if (clip.startMs < 0L || clip.endMs() > requestedDuration)
            throw new IllegalArgumentException("Cropped clip escapes the accepted export range");
        if (cropped.clips.isEmpty()) throw new IllegalArgumentException("Selected range contains no media clips; an all-gap render is unavailable");
        boolean visual = false, audio = false;
        for (ProjectStore.Track track : cropped.renderTracks()) {
            if (!cropped.isTrackEnabled(track)) continue;
            List<ProjectStore.Clip> clips = cropped.clipsOnTrack(track.id);
            for (ProjectStore.Clip clip : clips) {
                ProjectStore.Asset asset = cropped.asset(clip.assetId);
                if (asset == null) throw new IllegalArgumentException("Selected clip has no source asset");
                if (!track.isAudio()) visual = true;
                if (cropped.isTrackAudible(track) && asset.hasAudio && !clip.effects.optBoolean("audioDetached", false)) audio = true;
            }
        }
        if (!visual && !audio)
            throw new IllegalArgumentException("Selected range contains no enabled picture or audible source stream; an all-gap render is unavailable");
        return new Prepared(cropped, inMs, outMs, !visual, warnings);
    }

    private static ProjectStore.Clip copyClip(ProjectStore.Clip clip) {
        try { return ProjectStore.Clip.fromJson(new JSONObject(clip.toJson().toString())); }
        catch (Exception failure) { throw new IllegalArgumentException("Could not copy clip for range export", failure); }
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); }
        catch (Exception failure) { throw new IllegalStateException("Could not encode range export metadata", failure); }
    }
}
