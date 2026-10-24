package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, deterministic timing core for motion-graphics templates.
 *
 * This is deliberately not a BPM oracle or an ASR model. The audio route
 * reports amplitude-onset candidates, not proven musical beats; manual tempo
 * and human-authored markers remain distinct provenance types.
 */
public final class KineticBeatPlanner {
    public static final int MAX_EVENTS = 256;
    public static final int MAX_TITLES = 24;
    private KineticBeatPlanner() {}

    public static final class Cue {
        public final long startMs, durationMs;
        Cue(long startMs, long durationMs) {
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    public static List<Long> manualTempo(long durationMs, int bpm, long offsetMs) {
        if (durationMs < 500 || durationMs > 86_400_000L || bpm < 60 || bpm > 220
                || offsetMs < 0 || offsetMs >= durationMs)
            throw new IllegalArgumentException("Manual tempo requires 60..220 BPM and a valid nonnegative offset");
        double interval = 60_000.0 / bpm;
        ArrayList<Long> events = new ArrayList<>();
        for (int i = 0; i < MAX_EVENTS; i++) {
            long time = offsetMs + Math.round(i * interval);
            if (time >= durationMs) break;
            if (events.isEmpty() || time > events.get(events.size() - 1)) events.add(time);
        }
        return events;
    }

    public static List<Long> authoredMarkers(List<Long> markers, long durationMs) {
        if (markers == null || markers.isEmpty() || markers.size() > MAX_EVENTS || durationMs < 500)
            throw new IllegalArgumentException("1..256 authored event markers are required");
        ArrayList<Long> ordered = new ArrayList<>();
        long previous = -1;
        for (Long marker : markers) {
            if (marker == null || marker < 0 || marker >= durationMs || marker <= previous)
                throw new IllegalArgumentException("Authored event markers must be strictly increasing inside the film");
            ordered.add(marker);
            previous = marker;
        }
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Extracts transient energy-onset candidates from a measured normalized
     * audio envelope. The sample grid is owned by the caller, and gaps/partial
     * coverage must be rejected before this method is used.
     *
     * A local adaptive gate plus a 220ms refractory period avoids counting
     * every sustained note as a new event. Never label the output true beats.
     */
    public static List<Long> envelopeOnsets(double[] normalizedRms,
                                            long sourceMs, long windowMs) {
        if (normalizedRms == null || normalizedRms.length < 8 || normalizedRms.length > 720
                || windowMs < 100 || windowMs > 30_000 || sourceMs < 0)
            throw new IllegalArgumentException("Audio envelope must contain 8..720 measured buckets in <=30 seconds");
        final int n = normalizedRms.length;
        double[] smooth = new double[n], novelty = new double[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(normalizedRms[i]) || normalizedRms[i] < 0 || normalizedRms[i] > 1.001)
                throw new IllegalArgumentException("Envelope must be measured normalized RMS in [0,1]");
            double prev = normalizedRms[Math.max(0, i - 1)];
            double next = normalizedRms[Math.min(n - 1, i + 1)];
            smooth[i] = (prev + 2.0 * normalizedRms[i] + next) / 4.0;
        }
        double[] positive = new double[n];
        for (int i = 2; i < n - 1; i++) {
            double baseline = 0;
            int window = Math.min(6, i);
            for (int p = i - window; p < i; p++) baseline += smooth[p];
            baseline /= window;
            novelty[i] = Math.max(0, smooth[i] - baseline);
            positive[i] = novelty[i];
        }
        java.util.Arrays.sort(positive);
        double median = positive[n / 2];
        double[] deviations = new double[n];
        for (int i = 0; i < n; i++) deviations[i] = Math.abs(novelty[i] - median);
        java.util.Arrays.sort(deviations);
        double gate = Math.max(0.085, median + 1.8 * deviations[n / 2]);
        ArrayList<Long> events = new ArrayList<>();
        long last = -1000;
        for (int i = 2; i < n - 2 && events.size() < MAX_EVENTS; i++) {
            if (smooth[i] < 0.12 || novelty[i] < gate
                    || novelty[i] < novelty[i - 1] || novelty[i] <= novelty[i + 1]) continue;
            long at = sourceMs + Math.round(windowMs * (i + 0.5) / n);
            if (at - last < 220) continue;
            events.add(at);
            last = at;
        }
        return Collections.unmodifiableList(events);
    }

    /** Exact saved clip-boundary fallback; never describe as music-synced. */
    public static List<Long> clipBoundaries(List<Long> clipDurations, long totalDurationMs) {
        if (clipDurations == null || clipDurations.isEmpty() || totalDurationMs < 500)
            throw new IllegalArgumentException("A nonempty source timeline is required");
        ArrayList<Long> starts = new ArrayList<>();
        long cursor = 0;
        for (Long duration : clipDurations) {
            if (duration == null || duration <= 0) throw new IllegalArgumentException("Invalid clip duration");
            if (cursor >= totalDurationMs || starts.size() >= MAX_EVENTS) break;
            starts.add(cursor);
            cursor = Math.addExact(cursor, duration);
        }
        return Collections.unmodifiableList(starts);
    }

    /**
     * Selects spread-out, non-overlapping title cues using existing events.
     * If events are sparse, the returned cue count may be below the requested
     * number rather than creating false new musical event positions.
     */
    public static List<Cue> cues(List<Long> events, int requestedTitles, long filmMs) {
        if (events == null || events.isEmpty() || requestedTitles < 1
                || requestedTitles > MAX_TITLES || filmMs < 500)
            throw new IllegalArgumentException("Cannot schedule titles without real/manual timeline events");
        ArrayList<Long> candidates = new ArrayList<>();
        long prev = -1000;
        for (Long at : events) {
            if (at == null || at < 0 || at >= filmMs || at <= prev)
                throw new IllegalArgumentException("Timing events must be increasing and inside the film");
            prev = at;
            if (at >= filmMs - 390) continue;
            if (candidates.isEmpty() || at - candidates.get(candidates.size() - 1) >= 420)
                candidates.add(at);
        }
        int count = Math.min(requestedTitles, candidates.size());
        ArrayList<Long> chosen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : (int) Math.round(i * (candidates.size() - 1.0) / (count - 1.0));
            long at = candidates.get(index);
            if (chosen.isEmpty() || at - chosen.get(chosen.size() - 1) >= 420) chosen.add(at);
        }
        ArrayList<Cue> output = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            long at = chosen.get(i);
            long untilNext = i + 1 < chosen.size() ? chosen.get(i + 1) - at : filmMs - at;
            long duration = Math.min(2200, untilNext - (i + 1 < chosen.size() ? 40 : 0));
            if (duration >= 390) output.add(new Cue(at, duration));
        }
        return Collections.unmodifiableList(output);
    }
}
