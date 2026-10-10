package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Main-thread index rebuilt only when the monitor receives a project revision.
 * Valid lanes use one binary search; overlapping legacy lanes remain observable. */
final class MonitorProjectIndex {
    private final Map<String, ProjectStore.Asset> assets = new HashMap<>();
    private final Map<String, ProjectStore.Clip> clips = new HashMap<>();
    private final Map<String, ProjectStore.Track> tracks = new HashMap<>();
    private final List<Lane> lanes = new ArrayList<>();
    private final Map<String, Lane> laneById = new HashMap<>();
    private final List<ProjectStore.Clip> orphaned = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();

    private static final class Lane {
        final ProjectStore.Track track;
        final boolean enabled, audible;
        boolean overlaps;
        final List<ProjectStore.Clip> clips = new ArrayList<>();
        Lane(ProjectStore.Track track, boolean enabled) { this.track = track; this.enabled = enabled; audible = enabled && !track.muted; }
    }

    MonitorProjectIndex(ProjectStore.Project project) {
        boolean audioSolo = false, visualSolo = false;
        for (ProjectStore.Track track : project.tracks) if (track.visible && track.solo) {
            if (track.isAudio()) audioSolo = true; else visualSolo = true;
        }
        for (ProjectStore.Asset asset : project.assets) assets.put(asset.id, asset);
        for (ProjectStore.Track track : project.renderTracks()) {
            boolean enabled = track.visible && (!(track.isAudio() ? audioSolo : visualSolo) || track.solo);
            Lane lane = new Lane(track, enabled);
            tracks.put(track.id, track); lanes.add(lane); laneById.put(track.id, lane);
        }
        for (ProjectStore.Clip clip : project.clips) {
            clips.put(clip.id, clip);
            Lane lane = laneById.get(clip.trackId);
            if (lane == null) orphaned.add(clip); else lane.clips.add(clip);
        }
        for (Lane lane : lanes) {
            lane.clips.sort(Comparator.comparingLong(clip -> clip.startMs));
            long previousEnd = Long.MIN_VALUE;
            for (ProjectStore.Clip clip : lane.clips) {
                if (clip.startMs < previousEnd) lane.overlaps = true;
                previousEnd = Math.max(previousEnd, clip.endMs());
            }
            if (lane.overlaps && diagnostics.size() < 16)
                diagnostics.add("Track \"" + lane.track.name + "\" contains overlapping legacy clips. Separate them to restore normal editing and export.");
        }
    }

    ProjectStore.Asset asset(String id) { return assets.get(id); }
    ProjectStore.Clip clip(String id) { return clips.get(id); }
    ProjectStore.Track track(String id) { return tracks.get(id); }
    boolean audible(String trackId) { Lane lane = laneById.get(trackId); return lane == null || lane.audible; }
    List<String> diagnostics() { return new ArrayList<>(diagnostics); }

    List<ProjectStore.Clip> activeAt(long timeMs) {
        ArrayList<ProjectStore.Clip> active = new ArrayList<>(lanes.size());
        for (Lane lane : lanes) {
            if (!lane.enabled || lane.track.isAudio() && !lane.audible) continue;
            if (lane.overlaps) {
                // Loading older graphs does not prove the current transaction
                // invariants. Keep all active intervals visible for diagnosis;
                // the monitor's layer/decoder limits still bound bindings.
                for (ProjectStore.Clip clip : lane.clips) {
                    if (clip.startMs > timeMs) break;
                    if (timeMs < clip.endMs()) active.add(clip);
                }
                continue;
            }
            int low = 0, high = lane.clips.size();
            while (low < high) {
                int mid = low + (high - low) / 2;
                if (lane.clips.get(mid).startMs <= timeMs) low = mid + 1; else high = mid;
            }
            if (low > 0) {
                ProjectStore.Clip clip = lane.clips.get(low - 1);
                if (timeMs < clip.endMs()) active.add(clip);
            }
        }
        // Invalid legacy lanes remain visible for diagnosis rather than silently
        // deleting their media. Valid project transactions never create these.
        for (ProjectStore.Clip clip : orphaned) if (clip.startMs <= timeMs && timeMs < clip.endMs()) active.add(clip);
        active.sort(Comparator.comparingInt((ProjectStore.Clip clip) -> {
            ProjectStore.Track track = tracks.get(clip.trackId); return track == null ? 0 : track.order;
        }).thenComparing(clip -> clip.trackId == null ? "" : clip.trackId));
        return active;
    }
}
