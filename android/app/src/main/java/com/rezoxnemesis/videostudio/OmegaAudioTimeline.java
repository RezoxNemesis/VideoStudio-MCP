package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OMEGA Phase 7: immutable, source-bound audio lane and editable viseme timing model.
 *
 * Pure engine: it neither opens media nor adds an audio track to a rendered video.
 * The native adapter must validate app-owned audio bytes/hash, decode them, persist
 * changes through SketchEditOps/ProjectStore, and inspect actual encoded MP4 tracks.
 * No Gallery access, hidden time stretching, or claim of audible playback.
 */
final class OmegaAudioTimeline {
    private OmegaAudioTimeline() {}

    static final int MAX_SAMPLES = 10_000_000; // interleaved PCM16 values
    static final int MAX_FRAMES = 1200;
    static final int MAX_CLIPS = 32;
    static final int MAX_CUES = 240;
    private static final long MICROS = 1_000_000L;
    private static final Set<String> SHAPES =
            Set.of("rest", "closed", "open", "wide", "round", "teeth", "tongue");

    enum Kind { DIALOGUE, MUSIC, AMBIENCE, SFX }
    enum CueOrigin { MANUAL_EDIT, MODEL_PREDICTED_UNALIGNED }

    static final class Envelope {
        final int frame, startSample, endSample;
        final double rms, peak;
        Envelope(int frame, int startSample, int endSample, double rms, double peak) {
            this.frame = frame;
            this.startSample = startSample;
            this.endSample = endSample;
            this.rms = rms;
            this.peak = peak;
        }
        boolean silent(double threshold) {
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
                throw new IllegalArgumentException("Silence threshold must be 0..1");
            return peak <= threshold;
        }
    }

    private static void clock(int sampleRate, int fps, int frameCount) {
        if (sampleRate < 8000 || sampleRate > 192000 ||
                frameCount < 1 || frameCount > MAX_FRAMES)
            throw new IllegalArgumentException("Invalid audio/frame clock");
        NativeFrameClock.requireFps(fps);
        NativeFrameClock.presentationTimeUs(frameCount, fps);
    }

    private static long ceilDiv(long numerator, long divisor) {
        return numerator / divisor + (numerator % divisor == 0 ? 0 : 1);
    }

    /** Exact sample-domain rational boundaries; NativeFrameClock owns physical timestamps. */
    private static int sampleBoundary(int frame, int sampleRate, int fps) {
        NativeFrameClock.presentationTimeUs(frame, fps);
        return (int) ((long) frame * sampleRate / fps);
    }

    /** Legacy mono envelope API retained for callers; half-open bins, no extrapolation. */
    static List<Envelope> waveform(short[] pcm, int sampleRate, int fps, int frameCount) {
        if (pcm == null || pcm.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Invalid PCM sample buffer");
        clock(sampleRate, fps, frameCount);
        List<Envelope> result = new ArrayList<>(frameCount);
        for (int f = 0; f < frameCount; f++) {
            int first = Math.min(pcm.length, sampleBoundary(f, sampleRate, fps));
            int last = Math.min(pcm.length, sampleBoundary(f + 1, sampleRate, fps));
            double square = 0, peak = 0;
            for (int i = first; i < last; i++) {
                double amplitude = Math.abs(pcm[i] / 32768d);
                square += amplitude * amplitude;
                peak = Math.max(peak, amplitude);
            }
            result.add(new Envelope(f, first, last,
                    last == first ? 0 : Math.sqrt(square / (last - first)), peak));
        }
        return Collections.unmodifiableList(result);
    }

    /** Binary search avoids sampleIndex*fps long overflow for hostile timestamps. */
    static int frameForSample(long sampleIndex, int sampleRate, int fps, int frameCount) {
        clock(sampleRate, fps, frameCount);
        if (sampleIndex < 0) throw new IllegalArgumentException("Negative audio sample");
        int lo = 0, hi = frameCount;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sampleBoundary(mid, sampleRate, fps) <= sampleIndex) lo = mid;
            else hi = mid - 1;
        }
        return Math.min(frameCount - 1, lo);
    }

    static int frameForTimeUs(long timestampUs, int fps, int frameCount) {
        clock(8000, fps, frameCount);
        if (timestampUs < 0) throw new IllegalArgumentException("Negative time");
        int lo = 0, hi = frameCount;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (NativeFrameClock.presentationTimeUs(mid, fps) <= timestampUs) lo = mid;
            else hi = mid - 1;
        }
        return Math.min(frameCount - 1, lo);
    }

    static final class AudioClip {
        final String clipId, assetId, assetSha256;
        final Kind kind;
        final int sampleRate, channels, sourceStartSample, sourceEndSample;
        final long timelineStartUs;
        final double gain;
        final boolean muted;

        AudioClip(String clipId, String assetId, String assetSha256, Kind kind,
                  int sampleRate, int channels, int sourceStartSample, int sourceEndSample,
                  long timelineStartUs, double gain, boolean muted) {
            this.clipId = id(clipId);
            this.assetId = id(assetId);
            this.assetSha256 = hash(assetSha256);
            if (kind == null || sampleRate < 8000 || sampleRate > 192000 ||
                    channels < 1 || channels > 8 ||
                    sourceStartSample < 0 || sourceEndSample <= sourceStartSample ||
                    sourceEndSample > MAX_SAMPLES || timelineStartUs < 0 ||
                    !Double.isFinite(gain) || gain < 0 || gain > 2)
                throw new IllegalArgumentException("Invalid source-bound audio clip");
            this.kind = kind;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.sourceStartSample = sourceStartSample;
            this.sourceEndSample = sourceEndSample;
            this.timelineStartUs = timelineStartUs;
            this.gain = gain;
            this.muted = muted;
        }
        long durationUs() {
            return ceilDiv((long) (sourceEndSample - sourceStartSample) * MICROS, sampleRate);
        }
        long endUs() { return timelineStartUs + durationUs(); }
        boolean identical(AudioClip b) {
            return b != null && clipId.equals(b.clipId) && assetId.equals(b.assetId) &&
                    assetSha256.equals(b.assetSha256) && kind == b.kind &&
                    sampleRate == b.sampleRate && channels == b.channels &&
                    sourceStartSample == b.sourceStartSample &&
                    sourceEndSample == b.sourceEndSample && timelineStartUs == b.timelineStartUs &&
                    Double.compare(gain, b.gain) == 0 && muted == b.muted;
        }
    }

    static final class ProjectAudio {
        final String sketchId;
        final long projectRevision;
        final int fps, frameCount;
        final List<AudioClip> clips;

        ProjectAudio(String sketchId, long projectRevision, int fps, int frameCount,
                     List<AudioClip> clips) {
            this.sketchId = id(sketchId);
            if (projectRevision < 0 || clips == null || clips.size() > MAX_CLIPS)
                throw new IllegalArgumentException("Invalid pinned audio project");
            clock(8000, fps, frameCount);
            this.projectRevision = projectRevision;
            this.fps = fps;
            this.frameCount = frameCount;
            long durationUs = NativeFrameClock.presentationTimeUs(frameCount, fps);
            Set<String> ids = new HashSet<>();
            List<AudioClip> bounded = new ArrayList<>();
            for (AudioClip c : clips) {
                if (c == null || !ids.add(c.clipId) || c.endUs() > durationUs)
                    throw new IllegalArgumentException("Duplicate, null or out-of-range audio clip");
                bounded.add(c);
            }
            this.clips = Collections.unmodifiableList(bounded);
        }
        AudioClip clip(String clipId) {
            for (AudioClip clip : clips) if (clip.clipId.equals(clipId)) return clip;
            throw new IllegalArgumentException("Audio clip absent from source revision");
        }
    }

    /** Audio-lane lookup for native scrubbing, not a playback confirmation. */
    static List<AudioClip> activeAt(ProjectAudio project, long timestampUs) {
        if (project == null || timestampUs < 0 ||
                timestampUs >= NativeFrameClock.presentationTimeUs(project.frameCount, project.fps))
            throw new IllegalArgumentException("Scrub timestamp outside authored timeline");
        List<AudioClip> active = new ArrayList<>();
        for (AudioClip clip : project.clips)
            if (timestampUs >= clip.timelineStartUs && timestampUs < clip.endUs())
                active.add(clip);
        return Collections.unmodifiableList(active);
    }

    /**
     * Frame-aligned envelope of ONE app-owned, already-verified PCM16 asset.
     * Metadata must match the project's pinned source identity. The native
     * adapter must verify actual asset bytes/hash before passing decoded PCM;
     * matching a claimed SHA string here is not cryptographic proof.
     */
    static List<Envelope> waveformForClip(ProjectAudio project, String clipId,
                                          String decodedAssetId, String verifiedAssetSha256,
                                          int decodedRate, int decodedChannels, short[] pcm) {
        if (project == null) throw new IllegalArgumentException("Audio project required");
        AudioClip clip = project.clip(clipId);
        if (!clip.assetId.equals(decodedAssetId) ||
                !clip.assetSha256.equals(verifiedAssetSha256) ||
                clip.sampleRate != decodedRate || clip.channels != decodedChannels ||
                pcm == null || pcm.length > MAX_SAMPLES || pcm.length % clip.channels != 0 ||
                (long) clip.sourceEndSample * clip.channels > pcm.length)
            throw new IllegalArgumentException("Verified asset or PCM clock changed");
        List<Envelope> bins = new ArrayList<>(project.frameCount);
        for (int frame = 0; frame < project.frameCount; frame++) {
            long fromUs = NativeFrameClock.presentationTimeUs(frame, project.fps);
            long toUs = NativeFrameClock.presentationTimeUs(frame + 1, project.fps);
            if (toUs <= clip.timelineStartUs || fromUs >= clip.endUs()) {
                bins.add(new Envelope(frame, 0, 0, 0, 0));
                continue;
            }
            long fromRelative = Math.max(0, fromUs - clip.timelineStartUs);
            long toRelative = Math.max(0, toUs - clip.timelineStartUs);
            int first = clip.sourceStartSample + (int) Math.min(
                    clip.sourceEndSample - clip.sourceStartSample,
                    ceilDiv(fromRelative * clip.sampleRate, MICROS));
            int last = clip.sourceStartSample + (int) Math.min(
                    clip.sourceEndSample - clip.sourceStartSample,
                    ceilDiv(toRelative * clip.sampleRate, MICROS));
            double power = 0, peak = 0;
            if (!clip.muted && clip.gain != 0) {
                for (int sample = first; sample < last; sample++) {
                    int offset = sample * clip.channels;
                    for (int channel = 0; channel < clip.channels; channel++) {
                        double amp = Math.abs(pcm[offset + channel] / 32768d) * clip.gain;
                        power += amp * amp;
                        peak = Math.max(peak, amp);
                    }
                }
            }
            bins.add(new Envelope(frame, first, last,
                    first == last ? 0 : Math.sqrt(power / ((last - first) * clip.channels)),
                    peak));
        }
        return Collections.unmodifiableList(bins);
    }

    /** An explicitly supplied viseme; model estimates remain marked unaligned. */
    static final class Cue {
        final long startOffsetUs, endOffsetUs;
        final String shape;
        final double strength;
        final CueOrigin origin;
        Cue(long startOffsetUs, long endOffsetUs, String shape, double strength, CueOrigin origin) {
            if (startOffsetUs < 0 || endOffsetUs <= startOffsetUs ||
                    endOffsetUs > 600_000_000L || !SHAPES.contains(shape) ||
                    !Double.isFinite(strength) || strength < 0 || strength > 1 || origin == null)
                throw new IllegalArgumentException("Invalid authored viseme cue");
            this.startOffsetUs = startOffsetUs;
            this.endOffsetUs = endOffsetUs;
            this.shape = shape;
            this.strength = strength;
            this.origin = origin;
        }
    }

    static final class Viseme {
        final String shape;
        final double strength;
        final boolean multipleCuesInFrame, needsReview;
        private Viseme(String shape, double strength, boolean multipleCuesInFrame,
                       boolean needsReview) {
            this.shape = shape;
            this.strength = strength;
            this.multipleCuesInFrame = multipleCuesInFrame;
            this.needsReview = needsReview;
        }
    }

    /** No mutable shared cue list: edits either produce a new revision or fail. */
    static final class CueSheet {
        final ProjectAudio project;
        final AudioClip clip;
        final int editRevision;
        final List<Cue> cues;

        CueSheet(ProjectAudio project, String clipId, List<Cue> cues) {
            this(project, clipId, 0, cues);
        }
        private CueSheet(ProjectAudio project, String clipId, int editRevision, List<Cue> cues) {
            if (project == null || cues == null || cues.size() > MAX_CUES ||
                    editRevision < 0) throw new IllegalArgumentException("Invalid cue sheet");
            AudioClip pinned = project.clip(clipId);
            if (pinned.kind != Kind.DIALOGUE)
                throw new IllegalArgumentException("Visemes require a dialogue source");
            long previous = 0;
            List<Cue> ordered = new ArrayList<>();
            for (Cue cue : cues) {
                if (cue == null || cue.startOffsetUs < previous ||
                        cue.endOffsetUs > pinned.durationUs())
                    throw new IllegalArgumentException("Overlapping/out-of-range speech cues");
                previous = cue.endOffsetUs;
                ordered.add(cue);
            }
            this.project = project;
            this.clip = pinned;
            this.editRevision = editRevision;
            this.cues = Collections.unmodifiableList(ordered);
        }
        void requireCurrent(ProjectAudio current) {
            if (current == null || !current.sketchId.equals(project.sketchId) ||
                    current.projectRevision != project.projectRevision ||
                    current.fps != project.fps || current.frameCount != project.frameCount ||
                    !clip.identical(current.clip(clip.clipId)))
                throw new IllegalArgumentException("AUDIO_SOURCE_REVISION_CHANGED");
        }
        CueSheet correct(int expectedRevision, int index, Cue manualCorrection) {
            if (expectedRevision != editRevision || editRevision == Integer.MAX_VALUE)
                throw new IllegalArgumentException("AUDIO_CUE_REVISION_CONFLICT");
            if (index < 0 || index >= cues.size() || manualCorrection == null ||
                    manualCorrection.origin != CueOrigin.MANUAL_EDIT)
                throw new IllegalArgumentException("Only explicit manual cue correction accepted");
            List<Cue> updated = new ArrayList<>(cues);
            updated.set(index, manualCorrection);
            return new CueSheet(project, clip.clipId, editRevision + 1, updated);
        }
        Viseme atTimeUs(ProjectAudio current, long timeUs) {
            requireCurrent(current);
            if (timeUs < 0 || timeUs >= NativeFrameClock.presentationTimeUs(current.frameCount, current.fps))
                throw new IllegalArgumentException("Speech scrub outside authored timeline");
            return select(timeUs - clip.timelineStartUs, timeUs - clip.timelineStartUs + 1);
        }
        Viseme atFrame(ProjectAudio current, int frame) {
            requireCurrent(current);
            if (frame < 0 || frame >= current.frameCount)
                throw new IllegalArgumentException("Speech frame outside authored timeline");
            long from = NativeFrameClock.presentationTimeUs(frame, current.fps) - clip.timelineStartUs;
            long to = NativeFrameClock.presentationTimeUs(frame + 1, current.fps) - clip.timelineStartUs;
            return select(from, to);
        }
        private Viseme select(long from, long to) {
            if (to <= 0 || from >= clip.durationUs())
                return new Viseme("rest", 0, false, false);
            long midpoint = from + (to - from) / 2;
            int intersections = 0;
            Cue selected = null;
            long best = 0;
            for (Cue cue : cues) {
                long overlap = Math.max(0, Math.min(to, cue.endOffsetUs) -
                        Math.max(from, cue.startOffsetUs));
                if (overlap == 0) continue;
                intersections++;
                if (midpoint >= cue.startOffsetUs && midpoint < cue.endOffsetUs) {
                    selected = cue;
                    best = Long.MAX_VALUE;
                } else if (best != Long.MAX_VALUE && overlap > best) {
                    selected = cue;
                    best = overlap;
                }
            }
            if (selected == null) return new Viseme("rest", 0, false, false);
            boolean ambiguous = intersections > 1;
            return new Viseme(selected.shape, selected.strength, ambiguous,
                    ambiguous || selected.origin == CueOrigin.MODEL_PREDICTED_UNALIGNED);
        }
    }

    /** External decoder/FFprobe receipt. It never constitutes a listening review. */
    static final class ExportEvidence {
        final String mp4Sha256;
        final long bytes, durationUs, decodedAudioSamples;
        final int videoTracks, audioTracks, decodedVideoFrames;
        final double decodedPeak;
        ExportEvidence(String mp4Sha256, long bytes, long durationUs, int videoTracks,
                       int audioTracks, int decodedVideoFrames, long decodedAudioSamples,
                       double decodedPeak) {
            this.mp4Sha256 = hash(mp4Sha256);
            if (bytes <= 0 || durationUs < 0 || videoTracks < 0 || videoTracks > 8 ||
                    audioTracks < 0 || audioTracks > 8 || decodedVideoFrames < 0 ||
                    decodedAudioSamples < 0 || !Double.isFinite(decodedPeak) ||
                    decodedPeak < 0 || decodedPeak > 2)
                throw new IllegalArgumentException("Invalid decoded export receipt");
            this.bytes = bytes;
            this.durationUs = durationUs;
            this.videoTracks = videoTracks;
            this.audioTracks = audioTracks;
            this.decodedVideoFrames = decodedVideoFrames;
            this.decodedAudioSamples = decodedAudioSamples;
            this.decodedPeak = decodedPeak;
        }
    }

    static final class ExportAudit {
        final String status;
        final List<String> blockers;
        ExportAudit(List<String> blockers) {
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
            this.status = blockers.isEmpty() ?
                    "TRACK_EVIDENCE_PRESENT_LISTENING_AND_SYNC_REVIEW_REQUIRED" : "BLOCK";
        }
    }

    /**
     * Separate observed encoded-track evidence from a valid editing plan.
     * Even a successful probe cannot prove source identity, intelligibility,
     * audible listening, or that visible lips match spoken phonemes.
     */
    static ExportAudit auditExport(ProjectAudio project, ExportEvidence evidence) {
        if (project == null || evidence == null)
            throw new IllegalArgumentException("Export and project evidence required");
        List<String> errors = new ArrayList<>();
        if (project.clips.isEmpty()) errors.add("NO_AUDIO_LANES");
        if (evidence.videoTracks < 1 || evidence.decodedVideoFrames != project.frameCount)
            errors.add("VIDEO_FRAME_INVENTORY_MISMATCH");
        if (evidence.audioTracks < 1 || evidence.decodedAudioSamples == 0)
            errors.add("MISSING_DECODED_AUDIO");
        if (evidence.decodedPeak <= 0) errors.add("DECODED_AUDIO_IS_SILENT");
        long authored = NativeFrameClock.presentationTimeUs(project.frameCount, project.fps);
        if (Math.abs(evidence.durationUs - authored) > 200_000L)
            errors.add("EXPORT_DURATION_MISMATCH");
        return new ExportAudit(errors);
    }

    private static String id(String candidate) {
        if (candidate == null || !candidate.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Invalid app-owned identifier");
        return candidate;
    }
    private static String hash(String candidate) {
        if (candidate == null || !candidate.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Source SHA-256 required");
        return candidate;
    }
}
