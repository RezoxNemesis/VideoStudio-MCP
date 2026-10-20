package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure Java deterministic regression gate for Phase 5. Not a native film QA claim. */
public class OmegaMotionTimelineTest {
    private static final double TOL = 1e-10;

    private static OmegaMotionTimeline.Key key(int f, double x, double y, double angle,
                                                double vx, double vy, boolean locked) {
        return new OmegaMotionTimeline.Key(f, x, y, angle, vx, vy, 0, locked);
    }

    private static OmegaMotionTimeline.ActingSpec spec(int fps) {
        return new OmegaMotionTimeline.ActingSpec(fps, 10, 6, 10, 6, 10,
                new OmegaMotionTimeline.Pose(.30, .55, 0),
                new OmegaMotionTimeline.Pose(.70, .55, 35),
                .16, .12, .05, .07, 12, .09, .06);
    }

    private static void fails(String fragment, Runnable runnable) {
        try { runnable.run(); fail("Expected failure containing: " + fragment); }
        catch (IllegalArgumentException expected) {
            assertTrue("Unexpected validation: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains(fragment.toLowerCase()));
        }
    }

    @Test public void hermitePreservesEndpointsAndNeverOverrunsTranslation() {
        OmegaMotionTimeline.Key a = key(0, .1, .2, 0, 4, -4, false);
        OmegaMotionTimeline.Key b = key(20, .9, .7, 90, -4, 4, false);
        assertEquals(.1, OmegaMotionTimeline.at(a, b, -10).x, TOL);
        assertEquals(.9, OmegaMotionTimeline.at(a, b, 100).x, TOL);
        for (int i = 0; i <= 1000; i++) {
            OmegaMotionTimeline.Pose p = OmegaMotionTimeline.at(a, b, i * .02);
            assertTrue(p.x >= .1 - TOL && p.x <= .9 + TOL);
            assertTrue(p.y >= .2 - TOL && p.y <= .7 + TOL);
        }
        assertEquals(.5, OmegaMotionTimeline.at(
                key(0, .1, .2, 0, 0, 0, false),
                key(20, .9, .7, 0, 0, 0, false), 10).x, TOL);
    }

    @Test public void lockedContactPivotsInPlaceAndCannotSlide() {
        OmegaMotionTimeline.Key a = key(0, .4, .5, 0, 0, 0, true);
        OmegaMotionTimeline.Key b = key(24, .4, .5, 90, 0, 0, true);
        for (int f = 0; f <= 24; f++) {
            OmegaMotionTimeline.Pose p = OmegaMotionTimeline.at(a, b, f);
            assertEquals(.4, p.x, 0);
            assertEquals(.5, p.y, 0);
        }
        assertEquals(45, OmegaMotionTimeline.at(a, b, 12).angle, TOL);
        fails("contact", () -> OmegaMotionTimeline.at(a,
                key(24, .6, .5, 90, 0, 0, true), 12));
        fails("contact", () -> new OmegaMotionTimeline.Track(a,
                key(24, .6, .5, 90, 0, 0, true)));
    }

    @Test public void sparseTrackHasDeterministicOrderedAndClampedSampling() {
        OmegaMotionTimeline.Key[] authored = {
            key(0, 0, 0, 0, 0, 0, false),
            key(10, .5, .4, 10, 0, 0, false),
            key(20, 1, .5, 20, 0, 0, false)
        };
        OmegaMotionTimeline.Track track = new OmegaMotionTimeline.Track(authored);
        authored[1] = null;
        assertEquals(.5, track.sample(10).x, TOL);
        assertEquals(0, track.sample(-100).x, TOL);
        assertEquals(1, track.sample(100).x, TOL);
        OmegaMotionTimeline.Pose[] frames = track.sampleFrames(0, 20);
        assertEquals(21, frames.length);
        assertEquals(.5, frames[10].x, TOL);
        assertEquals(1, frames[20].x, TOL);
        assertEquals(3, track.authoredKeys().length);
        fails("ordered", () -> new OmegaMotionTimeline.Track(authored[0], authored[0]));
        fails("track needs", () -> new OmegaMotionTimeline.Track(authored[0]));
        fails("interval", () -> track.sampleFrames(-1, 5));
        fails("interval", () -> track.sampleFrames(0, 1200));
        fails("nonfinite", () -> track.sample(Double.NaN));
    }

    @Test public void authoredPhasesShowAnticipationArcImpactRecoilAndRecovery() {
        OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(30));
        OmegaMotionTimeline.ActingSpec s = clip.spec;
        assertEquals(16, s.windupEnd);
        assertEquals(26, s.impactFrame);
        assertEquals(32, s.recoilEnd);
        assertEquals(42, s.endFrame);
        assertEquals(OmegaMotionTimeline.Phase.REST, clip.sample(9).phase);
        assertEquals(OmegaMotionTimeline.Phase.ANTICIPATION, clip.sample(12).phase);
        assertEquals(OmegaMotionTimeline.Phase.DRIVE, clip.sample(21).phase);
        assertEquals(OmegaMotionTimeline.Phase.RECOIL, clip.sample(28).phase);
        assertEquals(OmegaMotionTimeline.Phase.RECOVERY, clip.sample(34).phase);
        assertEquals(OmegaMotionTimeline.Phase.REST, clip.sample(42).phase);
        assertEquals(.30, clip.sample(10).root.x, TOL);
        assertTrue("anticipatory counter-motion", clip.sample(16).root.x < .30);
        assertTrue("arc is in character root, not camera", clip.sample(21).root.y < .55);
        assertEquals(.70, clip.sample(26).root.x, TOL);
        assertTrue("body recoils away from contact", clip.sample(32).root.x < .70);
        assertEquals(.70, clip.sample(42).root.x, TOL);
        assertEquals(.55, clip.sample(42).root.y, TOL);
        assertEquals(35, clip.sample(42).root.angle, TOL);
        assertEquals(5, clip.authoredRootKeys().length);
        for (int f = 0; f <= 50; f++) {
            OmegaMotionTimeline.Sample p = clip.sample(f);
            assertTrue(Double.isFinite(p.root.x) && Double.isFinite(p.root.y));
            assertTrue(Double.isFinite(p.followThroughAngle));
        }
    }

    @Test public void rootArcIsSmoothAcrossEachAuthoredBeatBoundary() {
        OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(24));
        int[] knots = {clip.spec.startFrame, clip.spec.windupEnd, clip.spec.impactFrame,
                       clip.spec.recoilEnd, clip.spec.endFrame};
        for (int knot : knots) {
            OmegaMotionTimeline.Pose before = clip.sample(knot - .00001).root;
            OmegaMotionTimeline.Pose after = clip.sample(knot + .00001).root;
            assertEquals(before.x, after.x, .00001);
            assertEquals(before.y, after.y, .00001);
            assertEquals(before.angle, after.angle, .001);
        }
        assertTrue("the character root is animated, not just the scene camera",
                Math.abs(clip.sample(20).root.x - clip.sample(10).root.x) > .01);
    }

    @Test public void impactTriggeredSecondaryMotionSettlesToZero() {
        OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(60));
        assertEquals(0, clip.sample(25).followThroughAngle, 0);
        assertEquals(0, clip.sample(26).followThroughAngle, 0);
        assertTrue(Math.abs(clip.sample(28).followThroughAngle) > 1);
        assertEquals(0, clip.sample(42).followThroughAngle, 0);
        assertEquals(0, clip.sample(100).followThroughAngle, 0);
        double early = Math.abs(OmegaMotionTimeline.secondary(2, 12, .125, .08));
        double late = Math.abs(OmegaMotionTimeline.secondary(10, 12, .125, .08));
        assertTrue(early > late);
        fails("secondary", () -> OmegaMotionTimeline.secondary(-1, 1, .1, .1));
        fails("secondary", () -> OmegaMotionTimeline.secondary(2, Double.NaN, .1, .1));
    }

    @Test public void twoFeetStayPinnedInWorldSpaceWhileRootRotatesAndMoves() {
        OmegaMotionTimeline.Contact left = new OmegaMotionTimeline.Contact(
                "foot_left", 12, 38, .25, .80, -.05, .25, 1.0);
        OmegaMotionTimeline.Contact right = new OmegaMotionTimeline.Contact(
                "foot_right", 12, 38, .45, .80, .15, .25, 1.0);
        OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(30),
                left, right);
        assertEquals(0, clip.sample(11).contacts.length);
        assertEquals(0, clip.sample(39).contacts.length);
        for (int frame = 12; frame <= 38; frame++) {
            OmegaMotionTimeline.Sample s = clip.sample(frame);
            assertEquals(2, s.contacts.length);
            for (OmegaMotionTimeline.ContactSample c : s.contacts()) {
                double a = Math.toRadians(s.root.angle), cos = Math.cos(a), sin = Math.sin(a);
                double wx = s.root.x + c.localTargetX * cos - c.localTargetY * sin;
                double wy = s.root.y + c.localTargetX * sin + c.localTargetY * cos;
                assertEquals(c.worldX, wx, 1e-12);
                assertEquals(c.worldY, wy, 1e-12);
            }
        }
        OmegaMotionTimeline.ContactSample[] snapshot = clip.sample(21).contacts();
        snapshot[0] = null;
        assertEquals(2, clip.sample(21).contacts.length);
    }

    @Test public void unreachableOrOverlappingContactsFailClosed() {
        OmegaMotionTimeline.Contact unreachable = new OmegaMotionTimeline.Contact(
                "foot_left", 10, 42, .30, .80, 0, .25, .01);
        OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(30),
                unreachable);
        fails("unreachable", () -> clip.sample(26));
        OmegaMotionTimeline.Contact a = new OmegaMotionTimeline.Contact(
                "foot_left", 11, 25, .30, .80, 0, .25, 1.0);
        OmegaMotionTimeline.Contact b = new OmegaMotionTimeline.Contact(
                "foot_left", 25, 40, .30, .80, 0, .25, 1.0);
        fails("overlapping", () -> new OmegaMotionTimeline.ActingClip(spec(30), a, b));
        fails("outside", () -> new OmegaMotionTimeline.ActingClip(spec(30),
                new OmegaMotionTimeline.Contact("toe", 0, 42, .3, .8, 0, .25, 1.0)));
        fails("contact", () -> new OmegaMotionTimeline.Contact(
                "foot", 10, 12, Double.NaN, .7, 0, .1, .2));
    }

    @Test public void presentationTimesUseNativeFrameClockAtAllSupportedRates() {
        for (int fps : new int[]{24, 30, 60}) {
            OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(fps));
            for (int f = 0; f <= clip.spec.endFrame; f++) {
                long time = NativeFrameClock.presentationTimeUs(f, fps);
                assertEquals(clip.sample(f).root.x,
                        clip.sampleAtPresentationUs(time).root.x, 1e-9);
                assertEquals(clip.sample(f).root.y,
                        clip.sampleAtPresentationUs(time).root.y, 1e-9);
            }
            long a = NativeFrameClock.presentationTimeUs(20, fps);
            long b = NativeFrameClock.presentationTimeUs(21, fps);
            double half = clip.sampleAtPresentationUs((a + b) / 2).root.x;
            assertTrue(half >= Math.min(clip.sample(20).root.x, clip.sample(21).root.x) - TOL);
            assertTrue(half <= Math.max(clip.sample(20).root.x, clip.sample(21).root.x) + TOL);
            fails("negative", () -> clip.sampleAtPresentationUs(-1));
        }
    }

    @Test public void malformedTimelinesAndSpecsAreRejected() {
        fails("invalid", () -> key(1200, .5, .5, 0, 0, 0, false));
        fails("invalid", () -> key(1, Double.POSITIVE_INFINITY, .5, 0, 0, 0, false));
        fails("scene", () -> new OmegaMotionTimeline.Pose(9, 0, 0));
        fails("increasing", () -> OmegaMotionTimeline.at(
                key(10, 0, 0, 0, 0, 0, false),
                key(10, .2, 0, 0, 0, 0, false), 10));
        fails("Use 24", () -> new OmegaMotionTimeline.ActingSpec(25, 10, 6, 10, 6, 10,
                new OmegaMotionTimeline.Pose(.3, .5, 0),
                new OmegaMotionTimeline.Pose(.7, .5, 30),
                .1, .1, .05, .07, 12, .1, .05));
        fails("invalid", () -> new OmegaMotionTimeline.ActingSpec(30, 1180, 6, 10, 6, 10,
                new OmegaMotionTimeline.Pose(.3, .5, 0),
                new OmegaMotionTimeline.Pose(.7, .5, 30),
                .1, .1, .05, .07, 12, .1, .05));
        fails("invalid", () -> new OmegaMotionTimeline.ActingSpec(30, 10, 6, 10, 6, 10,
                new OmegaMotionTimeline.Pose(.3, .5, 0),
                new OmegaMotionTimeline.Pose(.7, .5, 30),
                .1, .1, .05, .07, 12, Double.NaN, .05));
        fails("subject motion", () -> new OmegaMotionTimeline.ActingSpec(30, 10, 6, 10, 6, 10,
                new OmegaMotionTimeline.Pose(.3, .5, 0),
                new OmegaMotionTimeline.Pose(.3, .5, 0),
                .1, .1, .05, .07, 12, .1, .05));
    }

    @Test public void allFiveNormalizedTemporalSamplesAreStableAndDistinct() {
        OmegaMotionTimeline.ActingClip clip = new OmegaMotionTimeline.ActingClip(spec(30));
        int[] frames = {10, 18, 26, 34, 42};
        double[] expectedX = {.30, 0, .70, 0, .70};
        for (int i = 0; i < frames.length; i++) {
            OmegaMotionTimeline.Sample s = clip.sample(frames[i]);
            assertTrue(Double.isFinite(s.root.x));
            if (i == 0 || i == 2 || i == 4) assertEquals(expectedX[i], s.root.x, TOL);
            assertEquals(s.root.x, clip.sample(frames[i]).root.x, 0);
        }
        assertTrue(clip.sample(18).root.x != clip.sample(34).root.x);
        assertFalse(clip.sample(18).phase == clip.sample(34).phase);
    }
}
