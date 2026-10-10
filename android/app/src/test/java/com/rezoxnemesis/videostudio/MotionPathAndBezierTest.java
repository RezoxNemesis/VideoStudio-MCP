package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Future source regressions. No tests have been executed in the implementation phase. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class MotionPathAndBezierTest {
    @Test public void timingSolvesXInsteadOfUsingProgressAsTheBezierParameter() {
        CubicBezierEasing curve = new CubicBezierEasing(.9, 0, .9, 1);
        // At parameter .25 the curve has X=.521875 and Y=.15625.
        assertEquals(.15625, curve.at(.521875), 1e-8);
        assertTrue(Math.abs(curve.at(.5) - .5) > .2);
        assertEquals(0, curve.at(-1), 0);
        assertEquals(1, curve.at(2), 0);
    }

    @Test public void timingHandlesFlatXDerivativeAndPreservesAllowedOvershoot() {
        CubicBezierEasing flat = new CubicBezierEasing(0, .3, 0, .7);
        double parameter = .01;
        double expected = 3 * .99 * .99 * parameter * .3 + 3 * .99 * parameter * parameter * .7 + parameter * parameter * parameter;
        assertEquals(expected, flat.at(.000001), 1e-5);
        assertTrue(new CubicBezierEasing(.3, -3, .7, 3).at(.1) < 0);
    }

    @Test public void spatialCubicUsesHandlesAndAnalyticOrientationWithEndpointHold() throws Exception {
        JSONObject path = path("replace", true);
        JSONObject first = path.getJSONArray("points").getJSONObject(0);
        JSONObject last = path.getJSONArray("points").getJSONObject(1);
        first.put("t", .25).put("outX", 0).put("outY", 1);
        last.put("t", .75).put("inX", 1).put("inY", 1);
        MotionPath2D compiled = MotionPath2D.fromJson(path);
        MotionPath2D.Sample middle = compiled.sample(.5);
        assertEquals(.5, middle.x, 1e-12); assertEquals(.75, middle.y, 1e-12);
        assertEquals(0, middle.rotationDeg, 1e-12);
        assertEquals(90, compiled.sample(0).rotationDeg, 1e-12);
        assertEquals(-90, compiled.sample(1).rotationDeg, 1e-12);
        assertEquals(0, compiled.sample(0).x, 0); assertEquals(1, compiled.sample(1).x, 0);
    }

    @Test public void actualClipSamplingSharesSignedAuthoredWindowAndAddReplaceModes() throws Exception {
        ProjectStore.Clip clip = clip();
        clip.effects.put("transform", new JSONObject().put("x", .2).put("y", .3).put("rotation", 20));
        clip.effects.put("animationDurationMs", 4000L).put("animationOffsetMs", -1000L);
        clip.effects.put("motionPath", path("add", false));
        assertEquals(.2, MotionTimeline.evaluate(clip, 0).x, .00001);
        assertEquals(.7, MotionTimeline.evaluate(clip, 3000).x, .00001);
        assertEquals(20, MotionTimeline.evaluate(clip, 3000).rotation, .00001);
        clip.effects.put("motionPath", path("replace", true).put("rotationOffsetDeg", 15));
        assertEquals(.5, MotionTimeline.evaluate(clip, 3000).x, .00001);
        assertEquals(0, MotionTimeline.evaluate(clip, 3000).y, .00001);
        assertEquals(15, MotionTimeline.evaluate(clip, 3000).rotation, .00001);
    }

    @Test public void transformAndAudioKeysEvaluateTheSameCompiledCustomTimingCurve() throws Exception {
        ProjectStore.Clip clip = clip();
        clip.effects.put("keyframes", new JSONArray()
                .put(new JSONObject().put("t", 0).put("x", 0).put("volume", 0).put("easing", "cubic_bezier")
                        .put("bezier", new JSONArray().put(.9).put(0).put(.9).put(1)))
                .put(new JSONObject().put("t", 1).put("x", 1).put("volume", 1)));
        double expected = new CubicBezierEasing(.9, 0, .9, 1).at(.5);
        assertEquals(expected, MotionTimeline.evaluate(clip, 2000).x, .00001);
        assertEquals(expected, AudioGainEnvelope.evaluate(clip, 2000), .00001);
    }

    @Test public void customGlobalCameraAndVisualDefaultsKeepSeparateControls() throws Exception {
        ProjectStore.Clip clip = clip();
        clip.effects.put("ease", "cubic_bezier").put("bezier", new JSONArray().put(.9).put(0).put(.9).put(1));
        clip.effects.put("animationSpec", new JSONObject().put("cameraPreset", "push_in")
                .put("easing", "cubic_bezier").put("bezier", new JSONArray().put(0).put(1).put(0).put(1)));
        double cameraProgress = new CubicBezierEasing(0, 1, 0, 1).at(.5);
        assertEquals(1 + .08 * cameraProgress, MotionTimeline.evaluate(clip, 2000).scaleX, .00001);
        clip.effects.put("motionPreset", "push_in");
        double visualProgress = new CubicBezierEasing(.9, 0, .9, 1).at(.5);
        assertEquals(1 + .08 * visualProgress, MotionTimeline.evaluate(clip, 2000).scaleX, .00001);
    }

    @Test public void duplicateTimesIdsUnpairedHandlesAndUnsafeCurvesReject() throws Exception {
        JSONObject duplicateTime = path("add", false); duplicateTime.getJSONArray("points").getJSONObject(1).put("t", 0);
        rejectPath(duplicateTime);
        JSONObject duplicateId = path("add", false); duplicateId.getJSONArray("points").getJSONObject(1).put("id", "first");
        rejectPath(duplicateId);
        JSONObject unpaired = path("add", false); unpaired.getJSONArray("points").getJSONObject(0).put("outX", .3);
        rejectPath(unpaired);
        JSONObject stringFlag = path("add", false).put("orientToPath", "true"); rejectPath(stringFlag);
        try { new CubicBezierEasing(-.1, 0, .5, 1); fail("Expected X bound rejection"); } catch (IllegalArgumentException expected) {}
        try { CubicBezierEasing.fromJson(new JSONArray().put(.1).put("1").put(.5).put(1)); fail("Expected numeric controls"); } catch (IllegalArgumentException expected) {}
    }

    private static ProjectStore.Clip clip() {
        ProjectStore.Clip clip = new ProjectStore.Clip(); clip.id = "clip"; clip.startMs = 0; clip.inMs = 0; clip.outMs = 4000; return clip;
    }
    private static JSONObject path(String mode, boolean orient) throws Exception {
        return new JSONObject().put("version", 1).put("mode", mode).put("orientToPath", orient)
                .put("points", new JSONArray().put(new JSONObject().put("id", "first").put("t", 0).put("x", 0).put("y", 0))
                        .put(new JSONObject().put("id", "last").put("t", 1).put("x", 1).put("y", 0)));
    }
    private static void rejectPath(JSONObject path) {
        try { MotionPath2D.fromJson(path); fail("Expected invalid path"); } catch (IllegalArgumentException expected) {}
    }
}
