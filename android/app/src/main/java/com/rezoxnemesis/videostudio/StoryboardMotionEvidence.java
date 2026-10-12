package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Bounded semantic diagnostics. Predictions never certify decoded cinematic output. */
public final class StoryboardMotionEvidence {
    public enum EvidenceStatus { MEASURED, UNAVAILABLE, NOT_APPLICABLE }
    public enum Verdict { PASS, FAIL, INCOMPLETE }
    public enum Origin { NONE, PREDICTED_NATIVE_EVALUATOR, DECODED_OUTPUT }
    public static final int MAX_SAMPLES = 1200, MIN_DENSE_SAMPLES = 16;
    private static final long MAX_TIME_US = 120_000_000L;
    private static final String[] IDS = {"T11", "T12", "T13", "T14", "T15", "T16", "T17", "T18", "T19"};

    private StoryboardMotionEvidence() { }

    public static final class Requirements {
        public final int width, height, fpsNumerator, fpsDenominator, outputFrameCount;
        public final long durationUs, contactWindowStartUs, contactWindowEndUs;
        public final boolean cinematicAction, expectsCollision, authoredContactWindow;
        public final double minimumNonAffinePixels;

        public Requirements(int width, int height, int numerator, int denominator, long durationUs, boolean cinematicAction) {
            this(width, height, numerator, denominator, durationUs, 0, cinematicAction, cinematicAction, -1, -1, 1);
        }
        public Requirements(int width, int height, int numerator, int denominator, long durationUs,
                            int outputFrameCount, boolean cinematicAction, boolean expectsCollision,
                            long contactStartUs, long contactEndUs, double minimumNonAffinePixels) {
            double fps = numerator / (double) denominator;
            if (width < 16 || height < 16 || width > 4096 || height > 4096 || numerator < 1 || numerator > 1_000_000
                    || denominator < 1 || denominator > 100_000 || !Double.isFinite(fps) || fps < 12 || fps > 60
                    || durationUs < 1 || durationUs > MAX_TIME_US || outputFrameCount < 0 || outputFrameCount > MAX_SAMPLES
                    || !Double.isFinite(minimumNonAffinePixels) || minimumNonAffinePixels <= 0 || minimumNonAffinePixels > 100)
                throw new IllegalArgumentException("Bounded frame geometry, rational FPS and measurement clock required");
            boolean authored = contactStartUs >= 0 || contactEndUs >= 0;
            if (authored && (contactStartUs < 0 || contactEndUs < contactStartUs || contactEndUs > durationUs))
                throw new IllegalArgumentException("Authored contact window is outside the shot clock");
            this.width = width; this.height = height; fpsNumerator = numerator; fpsDenominator = denominator;
            this.durationUs = durationUs; this.outputFrameCount = outputFrameCount; this.cinematicAction = cinematicAction;
            this.expectsCollision = expectsCollision; authoredContactWindow = authored;
            contactWindowStartUs = contactStartUs; contactWindowEndUs = contactEndUs;
            this.minimumNonAffinePixels = minimumNonAffinePixels;
        }
    }

    /** Coordinates are aspect-correct, camera-registered pixels, not normalized UV. */
    public static final class ActorPose {
        private final double[][] joints;
        private final double[] hilt, tip;
        public final double actorHeightPixels;
        public final boolean visible, independentWeaponAnchors;
        public ActorPose(double[][] joints, double actorHeightPixels, double[] hilt, double[] tip,
                         boolean visible, boolean independentWeaponAnchors) {
            if (joints == null || joints.length != 16 || !Double.isFinite(actorHeightPixels) || actorHeightPixels <= 0 || actorHeightPixels > 8192)
                throw new IllegalArgumentException("Sixteen traced joints and observed actor height required");
            this.joints = new double[16][2];
            for (int index = 0; index < 16; index++) this.joints[index] = point(joints[index]);
            this.hilt = hilt == null ? null : point(hilt); this.tip = tip == null ? null : point(tip);
            this.actorHeightPixels = actorHeightPixels; this.visible = visible; this.independentWeaponAnchors = independentWeaponAnchors;
        }
        public double[][] joints() {
            double[][] copy = new double[16][2]; for (int index = 0; index < 16; index++) copy[index] = joints[index].clone(); return copy;
        }
    }
    public static final class Sample {
        public final int frameIndex;
        public final long timestampUs;
        public final double phase;
        public final ActorPose black, white;
        public final String frameSha256;
        /** Index and PTS are shot-relative; actual output PTS must be preserved by the adapter. */
        public Sample(int frameIndex, long timestampUs, double phase, ActorPose black, ActorPose white, String frameSha256) {
            if (frameIndex < 0 || frameIndex > 1_000_000 || timestampUs < 0 || timestampUs > MAX_TIME_US || !Double.isFinite(phase) || phase < 0 || phase > 1)
                throw new IllegalArgumentException("A real frame index, timestamp and normalized sample phase are required");
            this.frameIndex = frameIndex; this.timestampUs = timestampUs; this.phase = phase; this.black = black; this.white = white;
            this.frameSha256 = hash(frameSha256, false);
        }
    }
    public static final class HoldInterval {
        public final boolean black;
        public final long startUs, endUs;
        public HoldInterval(boolean black, long startUs, long endUs) {
            interval(startUs, endUs); this.black = black; this.startUs = startUs; this.endUs = endUs;
        }
    }
    public static final class StanceInterval {
        public final boolean black;
        public final int footJoint;
        public final long startUs, endUs;
        private final double[] groundAnchor;
        public StanceInterval(boolean black, int footJoint, long startUs, long endUs, double[] groundAnchor) {
            interval(startUs, endUs);
            if (footJoint != Rig2DProfile.LEADING_FOOT && footJoint != Rig2DProfile.TRAILING_FOOT)
                throw new IllegalArgumentException("Stance needs a labelled leading or trailing source foot");
            this.black = black; this.footJoint = footJoint; this.startUs = startUs; this.endUs = endUs; this.groundAnchor = point(groundAnchor);
        }
    }
    public static final class ContactEvent {
        public final String eventId, frameSha256;
        public final long timestampUs, reactionOnsetUs;
        public final double bladeSeparationPixels;
        public final boolean bladeSegmentWitnessed;
        public final Origin origin;
        public ContactEvent(String eventId, long timestampUs, long reactionOnsetUs, double bladeSeparationPixels,
                            boolean bladeSegmentWitnessed, Origin origin, String frameSha256) {
            if (timestampUs < 0 || timestampUs > MAX_TIME_US || reactionOnsetUs < -1 || reactionOnsetUs > MAX_TIME_US
                    || !Double.isFinite(bladeSeparationPixels) || bladeSeparationPixels < 0 || bladeSeparationPixels > 8192 || origin == null || origin == Origin.NONE)
                throw new IllegalArgumentException("Captured blade event and explicit measurement origin required");
            this.eventId = label(eventId); this.timestampUs = timestampUs; this.reactionOnsetUs = reactionOnsetUs;
            this.bladeSeparationPixels = bladeSeparationPixels; this.bladeSegmentWitnessed = bladeSegmentWitnessed; this.origin = origin;
            this.frameSha256 = hash(frameSha256, origin == Origin.DECODED_OUTPUT);
        }
    }
    public static final class AudioPeak {
        public final String eventId, audioSha256;
        public final long sampleIndex, trackStartUs;
        public final int sampleRate;
        public final Origin origin;
        public AudioPeak(String eventId, long sampleIndex, int sampleRate, long trackStartUs, Origin origin, String audioSha256) {
            if (sampleIndex < 0 || sampleIndex > 24_000_000 || sampleRate < 8000 || sampleRate > 192000
                    || trackStartUs < -MAX_TIME_US || trackStartUs > MAX_TIME_US || origin == null || origin == Origin.NONE)
                throw new IllegalArgumentException("Captured event-specific PCM peak and clock required");
            this.eventId = label(eventId); this.sampleIndex = sampleIndex; this.sampleRate = sampleRate; this.trackStartUs = trackStartUs;
            this.origin = origin; this.audioSha256 = hash(audioSha256, origin == Origin.DECODED_OUTPUT);
        }
    }
    public static final class Trace {
        public final Origin origin;
        public final String provider, sourceBindingSha256;
        public final boolean cameraRegistered, cameraDisabled;
        public final List<Sample> samples;
        public final List<HoldInterval> holds;
        public final List<StanceInterval> stances;
        public final List<ContactEvent> contacts;
        public final List<AudioPeak> audioPeaks;
        public Trace(Origin origin, String provider, String sourceBindingSha256, boolean cameraRegistered, boolean cameraDisabled,
                     List<Sample> samples, List<HoldInterval> holds, List<StanceInterval> stances,
                     List<ContactEvent> contacts, List<AudioPeak> audioPeaks) {
            if (origin == null) throw new IllegalArgumentException("Trace origin is required");
            this.origin = origin; this.provider = label(provider); this.sourceBindingSha256 = hash(sourceBindingSha256, origin == Origin.DECODED_OUTPUT);
            this.cameraRegistered = cameraRegistered; this.cameraDisabled = cameraDisabled;
            this.samples = bounded(samples, MAX_SAMPLES); this.holds = bounded(holds, 256); this.stances = bounded(stances, 256);
            this.contacts = bounded(contacts, 64); this.audioPeaks = bounded(audioPeaks, 160);
            if (origin == Origin.NONE && !this.samples.isEmpty()) throw new IllegalArgumentException("Numeric traces must identify their origin");
            long previousTime = -1; int previousFrame = -1; double previousPhase = -1;
            for (Sample sample : this.samples) {
                if (sample.timestampUs <= previousTime || sample.frameIndex <= previousFrame || sample.phase < previousPhase)
                    throw new IllegalArgumentException("Sample frames and timestamps must be distinct and ordered");
                if (origin == Origin.DECODED_OUTPUT) hash(sample.frameSha256, true);
                previousTime = sample.timestampUs; previousFrame = sample.frameIndex; previousPhase = sample.phase;
            }
            HashSet<String> ids = new HashSet<>(); for (ContactEvent event : this.contacts) if (!ids.add(event.eventId)) throw new IllegalArgumentException("Contact event IDs must be distinct");
            ids.clear(); for (AudioPeak peak : this.audioPeaks) if (!ids.add(peak.eventId)) throw new IllegalArgumentException("One unambiguous observed peak per event required");
        }
    }
    public static final class Metric {
        public final String id, unit, reason;
        public final EvidenceStatus evidenceStatus;
        public final Origin origin;
        public final Verdict verdict;
        public final Double value;
        public final int sampleCount;
        public final Boolean thresholdSatisfied;
        private Metric(String id, String unit, String reason, EvidenceStatus status, Origin origin, Double value, int count, Boolean pass) {
            if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Evidence cannot contain nonfinite numbers");
            this.id = id; this.unit = unit; this.reason = reason; evidenceStatus = status; this.origin = origin; this.value = value;
            sampleCount = count; thresholdSatisfied = pass;
            verdict = pass == null ? Verdict.INCOMPLETE : !pass ? Verdict.FAIL : origin == Origin.DECODED_OUTPUT ? Verdict.PASS : Verdict.INCOMPLETE;
        }
        public JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("evidenceStatus", evidenceStatus.name()).put("origin", origin.name())
                    .put("verdict", verdict.name()).put("value", value == null ? JSONObject.NULL : value).put("unit", unit)
                    .put("sampleCount", sampleCount).put("thresholdSatisfied", thresholdSatisfied == null ? JSONObject.NULL : thresholdSatisfied).put("reason", reason);
        }
    }
    public static final class Ledger {
        public final String provider;
        public final Origin origin;
        public final Verdict technicalVerdict;
        public final List<Metric> metrics;
        public final int sampleCount;
        public final boolean densePhaseCoverage;
        private Ledger(String provider, Origin origin, List<Metric> metrics, int count, boolean coverage) {
            this.provider = provider; this.origin = origin; this.metrics = Collections.unmodifiableList(new ArrayList<>(metrics)); sampleCount = count; densePhaseCoverage = coverage;
            Verdict overall = Verdict.PASS;
            for (Metric metric : metrics) {
                if (metric.evidenceStatus == EvidenceStatus.NOT_APPLICABLE) continue;
                if (metric.verdict == Verdict.FAIL) overall = Verdict.FAIL;
                else if (metric.verdict == Verdict.INCOMPLETE && overall != Verdict.FAIL) overall = Verdict.INCOMPLETE;
            }
            if (metrics.stream().allMatch(metric -> metric.evidenceStatus == EvidenceStatus.NOT_APPLICABLE)) overall = Verdict.INCOMPLETE;
            technicalVerdict = overall;
        }
        public Metric metric(String id) { for (Metric metric : metrics) if (metric.id.equals(id)) return metric; throw new IllegalArgumentException("Unknown semantic gate"); }
        public JSONObject toJson() throws Exception {
            JSONArray items = new JSONArray(); for (Metric metric : metrics) items.put(metric.toJson());
            return new JSONObject().put("schemaVersion", 1).put("provider", provider).put("origin", origin.name())
                    .put("technicalVerdict", technicalVerdict.name()).put("cinematicAcceptance", false)
                    .put("decodedOutputVerified", false).put("denseSamplesRequired", MIN_DENSE_SAMPLES).put("sampleCount", sampleCount)
                    .put("fivePhaseCoverage", densePhaseCoverage).put("metrics", items)
                    .put("visualReview", "UNAVAILABLE").put("limitation", "Numeric/predicted diagnostics do not establish reviewed native cinematic acceptance");
        }
    }

    public static Ledger unavailable(boolean cinematicAction, String provider, String reason) {
        if (reason == null || reason.isEmpty() || reason.length() > 4096) throw new IllegalArgumentException("Bounded missing-evidence reason required");
        List<Metric> metrics = new ArrayList<>();
        for (String id : IDS) metrics.add(absent(id, cinematicAction ? reason : "Explicit non-action scope; cinematic motion is not evaluated", !cinematicAction));
        return new Ledger(label(provider), Origin.NONE, metrics, 0, false);
    }

    public static Ledger inspect(Requirements requirements, Trace trace) {
        return inspect(requirements, trace, null);
    }
    public static Ledger inspect(Requirements req, Trace trace, Trace cameraDisabledPeer) {
        if (req == null) throw new IllegalArgumentException("Measurement requirements are required");
        if (trace == null) return unavailable(req.cinematicAction, "unavailable", "No observed native motion trace");
        if (!req.cinematicAction) return unavailable(false, trace.provider, "Explicit non-action scope");
        for (Sample sample : trace.samples) if (sample.timestampUs > req.durationUs) throw new IllegalArgumentException("Sample is outside the output clock");
        for (HoldInterval hold : trace.holds) if (hold.endUs > req.durationUs) throw new IllegalArgumentException("Hold is outside the output clock");
        for (StanceInterval stance : trace.stances) if (stance.endUs > req.durationUs) throw new IllegalArgumentException("Stance is outside the output clock");
        for (ContactEvent event : trace.contacts) if (event.timestampUs > req.durationUs || event.reactionOnsetUs > req.durationUs) throw new IllegalArgumentException("Contact is outside the output clock");
        boolean coverage = denseCoverage(trace.samples);
        List<Metric> metrics = new ArrayList<>();
        metrics.add(articulation(req, trace, coverage));
        metrics.add(footDrift(trace, coverage));
        metrics.add(hiltError(trace, coverage));
        metrics.add(contact(req, trace));
        metrics.add(pacing(req, trace, coverage));
        metrics.add(absent("T16", "Decoded identity, source-texture ownership and planned occlusion analysis are unavailable", false));
        metrics.add(camera(req, trace, cameraDisabledPeer, coverage));
        metrics.add(absent("T18", "Decoded effect onset, collision location and moving-region blur evidence are unavailable", false));
        metrics.add(audio(req, trace));
        return new Ledger(trace.provider, trace.origin, metrics, trace.samples.size(), coverage);
    }

    private static Metric articulation(Requirements req, Trace trace, boolean coverage) {
        if (!coverage || !trace.cameraRegistered) return absent("T11", "Need sixteen distinct frames, five anchor phases and camera-registered actor landmarks", false);
        double minimum = 1; int windows = 0;
        try {
            for (boolean black : new boolean[]{true, false}) {
                int active = 0, changing = 0;
                for (int index = 1; index < trace.samples.size(); index++) {
                    Sample a = trace.samples.get(index - 1), b = trace.samples.get(index);
                    if (held(trace, black, a.timestampUs, b.timestampUs)) continue;
                    ActorPose from = actor(a, black), to = actor(b, black);
                    if (from == null || to == null || !from.visible || !to.visible) return absent("T11", "Required actor joints or occlusion labels are unavailable", false);
                    double residual = StoryboardMotionQualityGate.nonAffineResidualPixels(from.joints, to.joints);
                    active++; if (residual >= req.minimumNonAffinePixels) changing++;
                }
                if (active == 0) continue;
                minimum = Math.min(minimum, changing / (double) active); windows += active;
            }
        } catch (IllegalArgumentException unstable) { return absent("T11", "Landmarks cannot support a stable common-affine residual measurement", false); }
        if (windows == 0) return absent("T11", "All sampled actor windows have explicit authored holds", true);
        return measured("T11", "fraction_active_windows", minimum, windows, minimum >= .75, trace.origin,
                "Minimum per-actor fraction after common-affine removal; threshold 0.75. Predictions are not decoded motion proof");
    }
    private static Metric footDrift(Trace trace, boolean coverage) {
        if (!coverage || !trace.cameraRegistered || trace.stances.isEmpty()) return absent("T12", "Ground-labelled stance intervals and registered foot traces are unavailable", false);
        double maximum = 0; int count = 0;
        for (StanceInterval stance : trace.stances) {
            if (stance.endUs - stance.startUs < 500_000) return absent("T12", "A half-second stance interval has not been observed", false);
            long first = Long.MAX_VALUE, last = -1;
            for (Sample sample : trace.samples) {
                if (sample.timestampUs < stance.startUs || sample.timestampUs > stance.endUs) continue;
                ActorPose pose = actor(sample, stance.black); if (pose == null || !pose.visible) return absent("T12", "Stance foot observation is unavailable", false);
                maximum = Math.max(maximum, distance(pose.joints[stance.footJoint], stance.groundAnchor) / pose.actorHeightPixels);
                first = Math.min(first, sample.timestampUs); last = Math.max(last, sample.timestampUs); count++;
            }
            if (first != stance.startUs || last != stance.endUs) return absent("T12", "Stance endpoints were not actually sampled", false);
        }
        return measured("T12", "actor_height_fraction", maximum, count, maximum < .015, trace.origin, "Maximum labelled stance-anchor drift over observed half-second intervals; strict threshold 0.015");
    }
    private static Metric hiltError(Trace trace, boolean coverage) {
        if (!coverage || !trace.cameraRegistered) return absent("T13", "Dense registered weapon/hand observations are unavailable", false);
        double maximum = 0; int count = 0;
        for (Sample sample : trace.samples) for (boolean black : new boolean[]{true, false}) {
            ActorPose pose = actor(sample, black);
            if (pose == null || !pose.visible || !pose.independentWeaponAnchors || pose.hilt == null || pose.tip == null)
                return absent("T13", "Independent original-weapon hilt/tip observations are unavailable; hilt=wrist by construction is not proof", false);
            maximum = Math.max(maximum, distance(pose.hilt, pose.joints[Rig2DProfile.SWORD_HAND]) / pose.actorHeightPixels); count++;
        }
        return measured("T13", "actor_height_fraction", maximum, count, maximum <= .02, trace.origin,
                "Hilt attachment subcheck only; original blade contour/ownership and foreshortening still require decoded visual review");
    }
    private static Metric contact(Requirements req, Trace trace) {
        if (!req.expectsCollision) return absent("T14", "No collision is authored for this shot", true);
        if (!req.authoredContactWindow || trace.contacts.isEmpty()) return absent("T14", "Source-authored contact window and captured blade collision are unavailable", false);
        Origin origin = trace.contacts.get(0).origin;
        if (trace.contacts.size() != 1) return measured("T14", "contact_events", (double) trace.contacts.size(), trace.contacts.size(), false, origin, "Exactly one intended blade event is required");
        ContactEvent event = trace.contacts.get(0);
        if (!event.bladeSegmentWitnessed || event.reactionOnsetUs < 0) return absent("T14", "Blade-segment witness or post-contact body reaction is unavailable; bounding boxes/tip envelopes are not collision proof", false);
        boolean correct = event.timestampUs >= req.contactWindowStartUs && event.timestampUs <= req.contactWindowEndUs
                && event.reactionOnsetUs >= event.timestampUs && event.bladeSeparationPixels <= 1;
        return measured("T14", "contact_events", 1d, 1, correct, origin,
                "Captured blade event must lie in its authored window, separate segments by at most one raster pixel and precede reaction; pass-through still needs dense decoded inspection");
    }
    private static Metric pacing(Requirements req, Trace trace, boolean coverage) {
        if (!coverage || !trace.cameraRegistered || req.outputFrameCount == 0 || trace.samples.size() != req.outputFrameCount)
            return absent("T15", "Four-consecutive-frame hold detection requires full output-frame coverage, not sixteen sparse samples", false);
        for (int index = 0; index < trace.samples.size(); index++) if (trace.samples.get(index).frameIndex != index)
            return absent("T15", "Consecutive decoded frame indices are unavailable", false);
        int maximum = 1;
        try {
            for (boolean black : new boolean[]{true, false}) {
                int run = 1;
                for (int index = 1; index < trace.samples.size(); index++) {
                    Sample a = trace.samples.get(index - 1), b = trace.samples.get(index);
                    ActorPose from = actor(a, black), to = actor(b, black);
                    if (from == null || to == null || !from.visible || !to.visible) return absent("T15", "Continuous visible actor pose evidence is unavailable", false);
                    if (held(trace, black, a.timestampUs, b.timestampUs)) run = 1;
                    else run = StoryboardMotionQualityGate.nonAffineResidualPixels(from.joints, to.joints) < req.minimumNonAffinePixels ? run + 1 : 1;
                    maximum = Math.max(maximum, run);
                }
            }
        } catch (IllegalArgumentException unstable) { return absent("T15", "Stable pose comparisons are unavailable", false); }
        return measured("T15", "consecutive_static_frames", (double) maximum, trace.samples.size(), maximum < 4, trace.origin,
                "Static-run subcheck with explicit holds excluded; spike/acceleration and acting assessment remain separate");
    }
    private static Metric camera(Requirements req, Trace trace, Trace peer, boolean coverage) {
        if (peer == null || trace.cameraDisabled || !peer.cameraDisabled || trace.sourceBindingSha256 == null
                || !trace.sourceBindingSha256.equals(peer.sourceBindingSha256)) return absent("T17", "Matching source-bound camera-on and camera-disabled output traces are unavailable", false);
        if (!comparableCameraClock(req, trace, peer))
            return absent("T17", "Camera-on/off observations do not share the same in-range frame indices, PTS, phases and authored hold exclusions", false);
        Metric disabled = articulation(req, peer, denseCoverage(peer.samples));
        Metric enabled = articulation(req, trace, coverage);
        if (disabled.value == null || enabled.value == null) return absent("T17", "Camera comparison is missing genuine articulated observations", false);
        double minimum = Math.min(disabled.value, enabled.value);
        return measured("T17", "non_affine_active_window_fraction", minimum, trace.samples.size() + peer.samples.size(), minimum >= .75,
                combine(trace.origin, peer.origin), "Both matching outputs contain independent limb action; camera alone is insufficient");
    }
    private static boolean comparableCameraClock(Requirements req, Trace enabled, Trace disabled) {
        if (enabled.samples.size() != disabled.samples.size()) return false;
        for (HoldInterval hold : disabled.holds) if (hold.endUs > req.durationUs) return false;
        for (int index = 0; index < enabled.samples.size(); index++) {
            Sample a = enabled.samples.get(index), b = disabled.samples.get(index);
            if (b.timestampUs > req.durationUs || a.frameIndex != b.frameIndex || a.timestampUs != b.timestampUs
                    || Math.abs(a.phase - b.phase) > 1e-9) return false;
            if (index == 0) continue;
            long start = enabled.samples.get(index - 1).timestampUs, end = a.timestampUs;
            for (boolean black : new boolean[]{true, false})
                if (held(enabled, black, start, end) != held(disabled, black, start, end)) return false;
        }
        return true;
    }
    private static Metric audio(Requirements req, Trace trace) {
        if (!req.expectsCollision) return absent("T19", "No collision is authored; footstep/slash/ambience synchronization evidence is still unavailable", false);
        if (trace.contacts.size() != 1 || !trace.contacts.get(0).bladeSegmentWitnessed || trace.contacts.get(0).bladeSeparationPixels > 1)
            return absent("T19", "Observed blade contact within one raster pixel is unavailable; no zero audio offset is fabricated", false);
        ContactEvent contact = trace.contacts.get(0); AudioPeak matching = null;
        for (AudioPeak peak : trace.audioPeaks) if (peak.eventId.equals(contact.eventId)) matching = peak;
        if (matching == null) return absent("T19", "Event-specific decoded PCM peak is unavailable; ambience nonzero samples are not a clash", false);
        BigInteger rate = BigInteger.valueOf(matching.sampleRate);
        BigInteger peakTime = BigInteger.valueOf(matching.sampleIndex).multiply(BigInteger.valueOf(1_000_000))
                .add(BigInteger.valueOf(matching.trackStartUs).multiply(rate));
        if (peakTime.signum() < 0 || peakTime.compareTo(BigInteger.valueOf(req.durationUs).multiply(rate)) >= 0)
            return absent("T19", "The event-specific PCM peak is outside the actual output clock", false);
        BigInteger difference = peakTime.subtract(BigInteger.valueOf(contact.timestampUs).multiply(rate));
        BigInteger numerator = difference.multiply(BigInteger.valueOf(req.fpsNumerator));
        BigInteger denominator = BigInteger.valueOf(1_000_000).multiply(rate).multiply(BigInteger.valueOf(req.fpsDenominator));
        boolean within = numerator.abs().compareTo(denominator.multiply(BigInteger.valueOf(2))) <= 0;
        double offset = numerator.doubleValue() / denominator.doubleValue();
        return measured("T19", "video_frames_signed", offset, 1, within, combine(contact.origin, matching.origin),
                "Event peak minus blade-contact PTS using exact rational FPS; inclusive +/-2 frames. Synthesized stems do not verify exported AAC");
    }

    private static boolean denseCoverage(List<Sample> samples) {
        if (samples.size() < MIN_DENSE_SAMPLES) return false;
        int span = Math.max(1, samples.get(samples.size() - 1).frameIndex - samples.get(0).frameIndex);
        double tolerance = .51 / span;
        for (double phase : new double[]{0, .25, .5, .75, 1}) {
            boolean found = false; for (Sample sample : samples) if (Math.abs(sample.phase - phase) <= tolerance) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }
    private static ActorPose actor(Sample sample, boolean black) { return black ? sample.black : sample.white; }
    private static boolean held(Trace trace, boolean black, long start, long end) {
        for (HoldInterval hold : trace.holds) if (hold.black == black && hold.startUs <= start && hold.endUs >= end) return true; return false;
    }
    private static Metric absent(String id, String reason, boolean notApplicable) {
        return new Metric(id, "unavailable", reason, notApplicable ? EvidenceStatus.NOT_APPLICABLE : EvidenceStatus.UNAVAILABLE, Origin.NONE, null, 0, null);
    }
    private static Metric measured(String id, String unit, double value, int count, boolean pass, Origin origin, String reason) {
        return new Metric(id, unit, reason, EvidenceStatus.MEASURED, origin, value, count, pass);
    }
    private static Origin combine(Origin a, Origin b) { return a == Origin.DECODED_OUTPUT && b == Origin.DECODED_OUTPUT ? Origin.DECODED_OUTPUT : Origin.PREDICTED_NATIVE_EVALUATOR; }
    private static double distance(double[] a, double[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }
    private static double[] point(double[] point) {
        if (point == null || point.length != 2 || !Double.isFinite(point[0]) || !Double.isFinite(point[1]) || Math.abs(point[0]) > 32768 || Math.abs(point[1]) > 32768)
            throw new IllegalArgumentException("Bounded finite registered 2D point required"); return point.clone();
    }
    private static void interval(long start, long end) { if (start < 0 || end <= start || end > MAX_TIME_US) throw new IllegalArgumentException("Bounded explicit program interval required"); }
    private static String hash(String value, boolean required) {
        if (value == null && !required) return null;
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Decoded evidence requires an actual artifact SHA-256 binding"); return value;
    }
    private static String label(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) throw new IllegalArgumentException("Bounded evidence label required");
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) throw new IllegalArgumentException("Evidence labels cannot contain control characters"); return value;
    }
    private static <T> List<T> bounded(List<T> values, int maximum) {
        if (values == null) return Collections.emptyList();
        if (values.size() > maximum || values.contains(null)) throw new IllegalArgumentException("Evidence collection exceeds its bounds or contains missing entries");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
