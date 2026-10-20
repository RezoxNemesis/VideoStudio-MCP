package com.rezoxnemesis.videostudio;

import java.util.Arrays;

/**
 * OMEGA Phase 5: deterministic, scene-space acting curves. No project IO, rendering,
 * camera transforms, inferred anatomy or mutable global state. All timings are
 * authored frame positions; NativeFrameClock owns FPS/time conversion.
 *
 * Integration boundary: an approved native adapter must map these poses and
 * contact targets to existing editable rig poses. This engine is not a renderer.
 */
final class OmegaMotionTimeline {
    static final int MAX_FRAMES = 1200, MAX_TRACK_KEYS = 256, MAX_CONTACTS = 16;
    private static final double EPS = 1e-9;

    private OmegaMotionTimeline() {}

    static final class Pose {
        final double x, y, angle;
        Pose(double x, double y, double angle) {
            if (!finite(x, y, angle) || Math.abs(x) > 8 || Math.abs(y) > 8
                    || Math.abs(angle) > 720)
                throw new IllegalArgumentException("Pose outside bounded scene space");
            this.x = x; this.y = y; this.angle = angle;
        }
    }

    /** Velocity is per authored frame. Angles are unwrapped degrees. */
    static final class Key {
        final int frame;
        final double x, y, angle, vx, vy, angularVelocity;
        final boolean lockedContact;
        Key(int frame, double x, double y, double angle, double vx, double vy,
            double angularVelocity, boolean lockedContact) {
            if (frame < 0 || frame >= MAX_FRAMES || !finite(x, y, angle, vx, vy, angularVelocity)
                    || Math.abs(vx) > 4 || Math.abs(vy) > 4
                    || Math.abs(angularVelocity) > 360)
                throw new IllegalArgumentException("Invalid motion key");
            new Pose(x, y, angle);
            this.frame = frame; this.x = x; this.y = y; this.angle = angle;
            this.vx = vx; this.vy = vy; this.angularVelocity = angularVelocity;
            this.lockedContact = lockedContact;
        }
        Pose pose() { return new Pose(x, y, angle); }
    }

    /**
     * Monotone Hermite for translation (never overshoots a floor/scene segment).
     * Contact pins x/y only; rotation may animate about the pinned pivot.
     */
    static Pose at(Key start, Key end, double frame) {
        if (start == null || end == null || end.frame <= start.frame || !Double.isFinite(frame))
            throw new IllegalArgumentException("Motion keys must have increasing frames");
        boolean pinned = start.lockedContact && end.lockedContact;
        if (pinned && Math.hypot(start.x - end.x, start.y - end.y) > EPS)
            throw new IllegalArgumentException("Locked contact endpoints cannot drift");
        if (frame <= start.frame) return start.pose();
        if (frame >= end.frame) return end.pose();
        double duration = end.frame - start.frame, t = (frame - start.frame) / duration;
        double x = pinned ? start.x : monotone(start.x, end.x, start.vx, end.vx, duration, t);
        double y = pinned ? start.y : monotone(start.y, end.y, start.vy, end.vy, duration, t);
        double angle = hermite(start.angle, end.angle, start.angularVelocity * duration,
                end.angularVelocity * duration, t);
        return new Pose(x, y, angle);
    }

    /** Immutable sparse track. Binary search and endpoint holds; no extrapolation. */
    static final class Track {
        private final Key[] keys;
        Track(Key... supplied) {
            if (supplied == null || supplied.length < 2 || supplied.length > MAX_TRACK_KEYS)
                throw new IllegalArgumentException("A track needs 2..256 keys");
            keys = supplied.clone();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == null || (i > 0 && keys[i].frame <= keys[i - 1].frame))
                    throw new IllegalArgumentException("Track keys must be unique and ordered");
                if (i > 0 && keys[i - 1].lockedContact && keys[i].lockedContact
                        && Math.hypot(keys[i - 1].x - keys[i].x,
                                      keys[i - 1].y - keys[i].y) > EPS)
                    throw new IllegalArgumentException("Locked contact endpoints cannot drift");
            }
        }
        Pose sample(double frame) {
            if (!Double.isFinite(frame)) throw new IllegalArgumentException("Nonfinite frame");
            if (frame <= keys[0].frame) return keys[0].pose();
            if (frame >= keys[keys.length - 1].frame) return keys[keys.length - 1].pose();
            int lo = 0, hi = keys.length - 1;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid].frame <= frame) lo = mid;
                else hi = mid;
            }
            return at(keys[lo], keys[hi], frame);
        }
        Key[] authoredKeys() { return keys.clone(); }
        Pose[] sampleFrames(int first, int last) {
            if (first < 0 || last < first || last >= MAX_FRAMES)
                throw new IllegalArgumentException("Frame interval exceeds document budget");
            Pose[] out = new Pose[last - first + 1];
            for (int i = 0; i < out.length; i++) out[i] = sample(first + i);
            return out;
        }
    }

    /**
     * Explicit authored beat timings and distances. "Arc" means a visible
     * center-of-mass/root lift during DRIVE, not a camera-only pixel change.
     */
    static final class ActingSpec {
        final int fps, startFrame, anticipationFrames, driveFrames, recoilFrames, recoveryFrames;
        final int windupEnd, impactFrame, recoilEnd, endFrame;
        final Pose from, to;
        final double windup, recoil, sink, arc, lagAngle, lagCycles, lagDamping;

        ActingSpec(int fps, int startFrame, int anticipationFrames, int driveFrames,
                   int recoilFrames, int recoveryFrames, Pose from, Pose to,
                   double windup, double recoil, double sink, double arc,
                   double lagAngle, double lagCycles, double lagDamping) {
            NativeFrameClock.requireFps(fps);
            long total = (long) anticipationFrames + driveFrames + recoilFrames + recoveryFrames;
            if (startFrame < 0 || anticipationFrames < 2 || driveFrames < 2
                    || recoilFrames < 2 || recoveryFrames < 2 || total > MAX_FRAMES
                    || (long) startFrame + total >= MAX_FRAMES
                    || from == null || to == null
                    || !finite(windup, recoil, sink, arc, lagAngle, lagCycles, lagDamping)
                    || windup < 0 || windup > .5 || recoil < 0 || recoil > .5
                    || sink < 0 || sink > .5 || arc < 0 || arc > .5
                    || Math.abs(lagAngle) > 90 || lagCycles < 0 || lagCycles > .5
                    || lagDamping < 0 || lagDamping > 2)
                throw new IllegalArgumentException("Invalid bounded acting specification");
            if (Math.hypot(from.x - to.x, from.y - to.y) < EPS
                    && Math.abs(from.angle - to.angle) < EPS)
                throw new IllegalArgumentException("An action needs authored subject motion");
            this.fps = fps; this.startFrame = startFrame;
            this.anticipationFrames = anticipationFrames; this.driveFrames = driveFrames;
            this.recoilFrames = recoilFrames; this.recoveryFrames = recoveryFrames;
            this.from = from; this.to = to;
            this.windup = windup; this.recoil = recoil; this.sink = sink; this.arc = arc;
            this.lagAngle = lagAngle; this.lagCycles = lagCycles; this.lagDamping = lagDamping;
            windupEnd = startFrame + anticipationFrames;
            impactFrame = windupEnd + driveFrames;
            recoilEnd = impactFrame + recoilFrames;
            endFrame = recoilEnd + recoveryFrames;
        }
    }

    enum Phase { REST, ANTICIPATION, DRIVE, RECOIL, RECOVERY }

    /**
     * Explicit pinned joint (e.g. foot or hand), mapped by the adapter to a
     * prepared-rig joint ID. These are IK targets, not silently baked deformation.
     */
    static final class Contact {
        final String jointId;
        final int beginFrame, endFrame, blendInFrames, blendOutFrames;
        final double anchorX, anchorY, restLocalX, restLocalY, maxCorrection;

        Contact(String jointId, int beginFrame, int endFrame, double anchorX, double anchorY,
                double restLocalX, double restLocalY, double maxCorrection) {
            this(jointId, beginFrame, endFrame, anchorX, anchorY, restLocalX, restLocalY,
                    maxCorrection, 0, 0);
        }

        /** Entry/exit are unlocked easing windows; [beginFrame,endFrame] stays perfectly pinned. */
        Contact(String jointId, int beginFrame, int endFrame, double anchorX, double anchorY,
                double restLocalX, double restLocalY, double maxCorrection,
                int blendInFrames, int blendOutFrames) {
            if (jointId == null || !jointId.matches("[A-Za-z0-9_./-]{1,64}")
                    || beginFrame < 0 || endFrame <= beginFrame || endFrame >= MAX_FRAMES
                    || blendInFrames < 0 || blendInFrames > 24
                    || blendOutFrames < 0 || blendOutFrames > 24
                    || beginFrame - blendInFrames < 0
                    || endFrame + blendOutFrames >= MAX_FRAMES
                    || !finite(anchorX, anchorY, restLocalX, restLocalY, maxCorrection)
                    || anchorX < 0 || anchorX > 1 || anchorY < 0 || anchorY > 1
                    || Math.abs(restLocalX) > 2 || Math.abs(restLocalY) > 2
                    || maxCorrection <= 0 || maxCorrection > 2)
                throw new IllegalArgumentException("Invalid world-space contact");
            this.jointId = jointId; this.beginFrame = beginFrame; this.endFrame = endFrame;
            this.blendInFrames = blendInFrames; this.blendOutFrames = blendOutFrames;
            this.anchorX = anchorX; this.anchorY = anchorY;
            this.restLocalX = restLocalX; this.restLocalY = restLocalY;
            this.maxCorrection = maxCorrection;
        }

        double weightAt(double frame) {
            if (frame < beginFrame - blendInFrames || frame > endFrame + blendOutFrames)
                return -1;
            if (frame < beginFrame)
                return smoothstep((frame - (beginFrame - blendInFrames)) / blendInFrames);
            if (frame <= endFrame) return 1;
            return 1 - smoothstep((frame - endFrame) / blendOutFrames);
        }

        ContactSample solve(Pose root, double weight, boolean planted) {
            double radians = Math.toRadians(root.angle);
            double c = Math.cos(radians), s = Math.sin(radians);
            // Inverse of the root's scene-space transform, for the IK end effector.
            double dx = anchorX - root.x, dy = anchorY - root.y;
            double pinLocalX = c * dx + s * dy, pinLocalY = -s * dx + c * dy;
            double fullCorrectionX = pinLocalX - restLocalX;
            double fullCorrectionY = pinLocalY - restLocalY;
            double needed = Math.hypot(fullCorrectionX, fullCorrectionY);
            if (!Double.isFinite(needed) || needed > maxCorrection + EPS)
                throw new IllegalArgumentException("Contact target unreachable for " + jointId);
            double correctionX = fullCorrectionX * weight, correctionY = fullCorrectionY * weight;
            double localX = restLocalX + correctionX, localY = restLocalY + correctionY;
            double worldX = root.x + c * localX - s * localY;
            double worldY = root.y + s * localX + c * localY;
            return new ContactSample(jointId, worldX, worldY, anchorX, anchorY,
                    localX, localY, correctionX, correctionY, weight, planted);
        }
    }

    static final class ContactSample {
        final String jointId;
        final double worldX, worldY, anchorX, anchorY;
        final double localTargetX, localTargetY, correctionX, correctionY, weight;
        final boolean planted;
        ContactSample(String jointId, double worldX, double worldY, double anchorX,
                      double anchorY, double localTargetX, double localTargetY,
                      double correctionX, double correctionY, double weight, boolean planted) {
            this.jointId = jointId; this.worldX = worldX; this.worldY = worldY;
            this.anchorX = anchorX; this.anchorY = anchorY;
            this.localTargetX = localTargetX; this.localTargetY = localTargetY;
            this.correctionX = correctionX; this.correctionY = correctionY;
            this.weight = weight; this.planted = planted;
        }
    }

    static final class Sample {
        final Pose root;
        final Phase phase;
        final double followThroughAngle;
        final ContactSample[] contacts;
        Sample(Pose root, Phase phase, double followThroughAngle, ContactSample[] contacts) {
            this.root = root; this.phase = phase; this.followThroughAngle = followThroughAngle;
            this.contacts = contacts.clone();
        }
        ContactSample[] contacts() { return contacts.clone(); }
    }

    /**
     * Native adapter can sample this clip into editable root/rotation poses.
     * The optional contacts return per-joint local IK targets with exact world pins.
     */
    static final class ActingClip {
        final ActingSpec spec;
        private final Track rootTrack;
        private final Contact[] contacts;

        ActingClip(ActingSpec spec, Contact... requested) {
            if (spec == null || requested == null || requested.length > MAX_CONTACTS)
                throw new IllegalArgumentException("Invalid clip or contact count");
            this.spec = spec;
            Pose a = spec.from, b = spec.to;
            Pose windup = new Pose(a.x - (b.x - a.x) * spec.windup,
                    a.y - (b.y - a.y) * spec.windup + spec.sink,
                    a.angle - (b.angle - a.angle) * spec.windup);
            Pose recoil = new Pose(b.x - (b.x - a.x) * spec.recoil,
                    b.y - (b.y - a.y) * spec.recoil + spec.sink * .35,
                    b.angle - (b.angle - a.angle) * spec.recoil);
            rootTrack = new Track(still(spec.startFrame, a), still(spec.windupEnd, windup),
                    still(spec.impactFrame, b), still(spec.recoilEnd, recoil),
                    still(spec.endFrame, b));
            contacts = requested.clone();
            for (int i = 0; i < contacts.length; i++) {
                Contact now = contacts[i];
                if (now == null || now.beginFrame - now.blendInFrames < spec.startFrame
                        || now.endFrame + now.blendOutFrames > spec.endFrame)
                    throw new IllegalArgumentException("Contact outside acting clip");
                for (int j = 0; j < i; j++) {
                    Contact prior = contacts[j];
                    if (now.jointId.equals(prior.jointId)
                            && now.beginFrame - now.blendInFrames
                                <= prior.endFrame + prior.blendOutFrames
                            && prior.beginFrame - prior.blendInFrames
                                <= now.endFrame + now.blendOutFrames)
                        throw new IllegalArgumentException("Overlapping contact on one joint");
                }
            }
        }

        Key[] authoredRootKeys() { return rootTrack.authoredKeys(); }

        Sample sample(double frame) {
            if (!Double.isFinite(frame)) throw new IllegalArgumentException("Nonfinite frame");
            Pose root = rootTrack.sample(frame);
            Phase phase = Phase.REST;
            if (frame >= spec.startFrame && frame < spec.windupEnd)
                phase = Phase.ANTICIPATION;
            else if (frame >= spec.windupEnd && frame < spec.impactFrame)
                phase = Phase.DRIVE;
            else if (frame >= spec.impactFrame && frame < spec.recoilEnd)
                phase = Phase.RECOIL;
            else if (frame >= spec.recoilEnd && frame < spec.endFrame)
                phase = Phase.RECOVERY;
            if (phase == Phase.DRIVE && spec.arc > 0) {
                double t = (frame - spec.windupEnd) / spec.driveFrames;
                double lift = Math.sin(Math.PI * t);
                root = new Pose(root.x, root.y - spec.arc * lift * lift, root.angle);
            }
            double follow = 0;
            if (frame >= spec.impactFrame && frame < spec.endFrame && spec.lagAngle != 0) {
                double age = frame - spec.impactFrame;
                follow = secondary(age, spec.lagAngle, spec.lagCycles, spec.lagDamping);
                if (frame >= spec.recoilEnd) {
                    double settle = (frame - spec.recoilEnd) / spec.recoveryFrames;
                    follow *= 1 - smoothstep(settle);
                }
            }
            ContactSample[] active = new ContactSample[contacts.length];
            int count = 0;
            for (Contact c : contacts) {
                double weight = c.weightAt(frame);
                if (weight >= 0)
                    active[count++] = c.solve(root, weight,
                            frame >= c.beginFrame && frame <= c.endFrame);
            }
            return new Sample(root, phase, follow, Arrays.copyOf(active, count));
        }

        /** PTS conversion delegates exclusively to NativeFrameClock. */
        Sample sampleAtPresentationUs(long timestampUs) {
            if (timestampUs < 0) throw new IllegalArgumentException("Negative presentation time");
            long finalTime = NativeFrameClock.presentationTimeUs(spec.endFrame, spec.fps);
            if (timestampUs >= finalTime) return sample(spec.endFrame);
            int lo = 0, hi = spec.endFrame + 1;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (NativeFrameClock.presentationTimeUs(mid, spec.fps) <= timestampUs) lo = mid;
                else hi = mid;
            }
            long before = NativeFrameClock.presentationTimeUs(lo, spec.fps);
            long after = NativeFrameClock.presentationTimeUs(lo + 1, spec.fps);
            return sample(lo + (timestampUs - before) / (double) (after - before));
        }
    }

    static double secondary(double elapsedFrames, double amplitude,
                            double cyclesPerFrame, double damping) {
        if (!finite(elapsedFrames, amplitude, cyclesPerFrame, damping)
                || elapsedFrames < 0 || elapsedFrames > MAX_FRAMES
                || Math.abs(amplitude) > 90 || cyclesPerFrame < 0 || cyclesPerFrame > 2
                || damping < 0 || damping > 2)
            throw new IllegalArgumentException("Secondary motion inputs invalid");
        return amplitude * Math.exp(-damping * elapsedFrames)
                * Math.sin(2 * Math.PI * cyclesPerFrame * elapsedFrames);
    }

    private static Key still(int frame, Pose p) {
        return new Key(frame, p.x, p.y, p.angle, 0, 0, 0, false);
    }

    private static double smoothstep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * t * (t * (6 * t - 15) + 10);
    }

    private static double monotone(double a, double b, double v0, double v1,
                                   double duration, double t) {
        double diff = b - a;
        if (Math.abs(diff) < EPS) return a;
        double slope = diff / duration;
        double left = Math.max(0, v0 / slope), right = Math.max(0, v1 / slope);
        double length = Math.hypot(left, right);
        if (length > 3) { left *= 3 / length; right *= 3 / length; }
        double value = hermite(a, b, left * diff, right * diff, t);
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), value));
    }

    private static double hermite(double a, double b, double m0, double m1, double t) {
        double t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * a + (t3 - 2 * t2 + t) * m0
                + (-2 * t3 + 3 * t2) * b + (t3 - t2) * m1;
    }

    private static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }
}
