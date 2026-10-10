package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Immutable, deterministic clip-output-clock evaluation shared by preview and export.
 * Translations use normalized GL coordinates: x=2 moves one canvas width right,
 * y=2 moves one canvas height up. Rotation is in counter-clockwise degrees.
 * Sparse property keyframes interpolate independently, hold their end values,
 * and use the last value at a duplicate timestamp. No random state is used.
 */
public final class MotionTimeline {
    public static final class Sample {
        public final float scaleX, scaleY, x, y, rotation, opacity, colorMix;
        public final boolean dipWhite;

        Sample(float sx, float sy, float x, float y, float rotation, float opacity,
               float colorMix, boolean dipWhite) {
            this.scaleX = sx; this.scaleY = sy; this.x = x; this.y = y;
            this.rotation = rotation; this.opacity = opacity;
            this.colorMix = colorMix; this.dipWhite = dipWhite;
        }
    }

    private static final List<String> MOTIONS = Arrays.asList("none", "push_in", "pull_out",
            "pan_left", "pan_right", "pan_up", "pan_down", "drift", "float", "orbit",
            "handheld", "micro_shake", "impact_shake", "bounce", "elastic_pop",
            "parallax", "ken_burns", "snap_zoom", "zoom_punch", "tilt", "roll", "hero_reveal");
    private static final List<String> TRANSITIONS = Arrays.asList("none", "cut", "fade", "dip_black",
            "dip_white", "slide_left", "slide_right", "slide_up", "slide_down",
            "push_left", "push_right", "zoom_in", "zoom_out", "whip_left", "whip_right", "spin");
    private final String preset, transition, layerRole, easing;
    private final long durationUs, transitionUs;
    private final long animationOffsetUs;
    private final JSONObject spec;
    private final float strength, baseScaleX, baseScaleY, baseX, baseY, baseRotation, baseOpacity;
    private final Curve[] cameraCurves, userCurves;
    private final CubicBezierEasing presetCubic;
    private final MotionPath2D motionPath;

    public MotionTimeline(ProjectStore.Clip clip) {
        this(clip.effects, AnimationClock.microseconds(Math.max(1, clip.outputDurationMs())),
                clip.transition, "flat");
    }

    public MotionTimeline(JSONObject effects, long durationUs, String transition, String layerRole) {
        JSONObject fx = copy(effects);
        this.durationUs = AnimationClock.microseconds(Math.max(1, fx.optLong("animationDurationMs", durationUs / 1000L)));
        this.animationOffsetUs = AnimationClock.microseconds(fx.optLong("animationOffsetMs", 0));
        this.spec = fx.optJSONObject("animationSpec");
        this.preset = fx.optString("motionPreset", spec == null ? "none" : spec.optString("cameraPreset", "none"));
        this.transition = transition == null ? "none" : transition;
        this.layerRole = layerRole == null ? "flat" : layerRole;
        JSONObject defaultOwner = fx.has("motionPreset") || spec == null ? fx : spec;
        this.easing = defaultOwner == spec ? spec.optString("easing", "cinematic")
                : fx.optString("ease", spec == null ? "smooth" : spec.optString("easing", "cinematic"));
        JSONObject presetControlOwner = defaultOwner == fx && !fx.has("ease") && !fx.has("bezier") && spec != null ? spec : defaultOwner;
        this.presetCubic = CubicBezierEasing.compileDefault(presetControlOwner,
                presetControlOwner == spec ? "easing" : "ease", "bezier", easing);
        this.motionPath = fx.has("motionPath") ? MotionPath2D.fromJson(fx.optJSONObject("motionPath")) : null;
        this.transitionUs = Math.min(this.durationUs / 2,
                AnimationClock.microseconds(Math.max(0, fx.optLong("transitionDurationMs", 280))));
        this.strength = value(fx, "motionStrength", 1, 0, 3);
        JSONObject transform = fx.optJSONObject("transform");
        if (transform == null) transform = fx;
        float scale = value(transform, "scale", value(transform, "zoom", 1, .01f, 10), .01f, 10);
        this.baseScaleX = value(transform, "scaleX", scale, .01f, 10);
        this.baseScaleY = value(transform, "scaleY", scale, .01f, 10);
        this.baseX = value(transform, "x", value(transform, "translateX", 0, -10, 10), -10, 10);
        this.baseY = value(transform, "y", value(transform, "translateY", 0, -10, 10), -10, 10);
        this.baseRotation = value(transform, "rotation", value(transform, "rotate", 0, -3600, 3600), -3600, 3600);
        this.baseOpacity = value(transform, "opacity", value(fx, "opacity", 1, 0, 1), 0, 1);
        String cameraEasing = spec == null ? easing : spec.optString("easing", easing);
        CubicBezierEasing cameraCubic = spec == null || !spec.has("easing") && !spec.has("bezier") ? presetCubic
                : CubicBezierEasing.compileDefault(spec, "easing", "bezier", cameraEasing);
        this.cameraCurves = curves(spec == null ? null : spec.optJSONArray("keyframes"), cameraEasing, cameraCubic);
        String userEasing = fx.optString("ease", easing);
        CubicBezierEasing userCubic = fx.has("ease") || fx.has("bezier")
                ? CubicBezierEasing.compileDefault(fx, "ease", "bezier", userEasing) : presetCubic;
        this.userCurves = curves(fx.optJSONArray("keyframes"), userEasing, userCubic);
    }

    public static Sample evaluate(ProjectStore.Clip clip, long outputTimeMs) {
        return new MotionTimeline(clip).sample(AnimationClock.microseconds(Math.max(0, outputTimeMs)));
    }

    public static boolean supportsMotion(String name) { return name == null || MOTIONS.contains(name); }
    public static boolean supportsTransition(String name) { return name == null || TRANSITIONS.contains(name); }
    public static boolean supportsEasing(String name) { return Arrays.asList("linear","smooth","ease_in","ease_out","easeIn","easeOut","ease_in_out","easeInOut","cinematic","hold","step","cubic_bezier").contains(name); }

    public Sample sample(long outputTimeUs) {
        long timeUs = AnimationClock.authoredTimeUs(outputTimeUs, animationOffsetUs, durationUs);
        float p = timeUs / (float) durationUs;
        Motion m = presetMotion(p, timeUs);
        applyCurves(m, cameraCurves, p);
        if (spec != null) {
            applyLayerDepth(m);
            applyOrganicMotion(m, p);
        }
        m.scaleX = (1 + (m.scaleX - 1) * strength) * baseScaleX;
        m.scaleY = (1 + (m.scaleY - 1) * strength) * baseScaleY;
        m.x = m.x * strength + baseX;
        m.y = m.y * strength + baseY;
        m.rotation = m.rotation * strength + baseRotation;
        m.opacity *= baseOpacity;
        applyCurves(m, userCurves, p);
        if (motionPath != null) {
            MotionPath2D.Sample path = motionPath.sample(p);
            if ("replace".equals(motionPath.mode())) {
                m.x = (float) path.x; m.y = (float) path.y;
                if (path.hasOrientation) m.rotation = (float) path.rotationDeg;
            } else {
                m.x += path.x; m.y += path.y;
                if (path.hasOrientation) m.rotation += path.rotationDeg;
            }
        }
        float edge = edge(timeUs);
        float remaining = 1 - edge;
        float direction = timeUs < durationUs / 2 ? 1 : -1;
        switch (transition) {
            case "fade": m.opacity *= edge; break;
            case "slide_left": case "push_left": m.x -= 2 * direction * remaining; break;
            case "slide_right": case "push_right": m.x += 2 * direction * remaining; break;
            case "slide_up": m.y += 2 * direction * remaining; break;
            case "slide_down": m.y -= 2 * direction * remaining; break;
            case "whip_left": m.x -= 2 * direction * remaining; m.rotation -= 12 * direction * remaining; break;
            case "whip_right": m.x += 2 * direction * remaining; m.rotation += 12 * direction * remaining; break;
            case "zoom_in": m.scaleX *= 1 + remaining; m.scaleY *= 1 + remaining; break;
            case "zoom_out": m.scaleX *= Math.max(.01f, edge); m.scaleY *= Math.max(.01f, edge); break;
            case "spin": m.rotation += 180 * direction * remaining; break;
            default: break;
        }
        return new Sample(clamp(m.scaleX, .01f, 10), clamp(m.scaleY, .01f, 10),
                clamp(m.x, -10, 10), clamp(m.y, -10, 10), clamp(m.rotation, -3600, 3600),
                clamp(m.opacity, 0, 1), transition.startsWith("dip_") ? remaining : 0,
                "dip_white".equals(transition));
    }

    private float edge(long timeUs) {
        if (transitionUs == 0) return 1;
        return ease(clamp(Math.min(timeUs, durationUs - timeUs) / (float) transitionUs, 0, 1), "smooth");
    }

    private static void applyCurves(Motion m, Curve[] curves, float p) {
        m.scaleX = curves[0].at(p, m.scaleX); m.scaleY = curves[1].at(p, m.scaleY);
        m.x = curves[2].at(p, m.x); m.y = curves[3].at(p, m.y);
        m.rotation = curves[4].at(p, m.rotation); m.opacity = curves[5].at(p, m.opacity);
    }

    private Curve[] curves(JSONArray frames, String defaultEasing, CubicBezierEasing defaultCubic) {
        Curve[] result = new Curve[6];
        String[] names = {"scaleX", "scaleY", "x", "y", "rotation", "opacity"};
        for (int prop = 0; prop < result.length; prop++) {
            ArrayList<Point> points = new ArrayList<>();
            if (frames != null) for (int i = 0; i < frames.length(); i++) {
                JSONObject frame = frames.optJSONObject(i);
                if (frame == null) continue;
                JSONObject values = frame.optJSONObject("transform");
                if (values == null) values = frame;
                String key = names[prop];
                if (!values.has(key) && prop < 2 && values.has("scale")) key = "scale";
                if (!values.has(key) && prop == 4 && values.has("rotate")) key = "rotate";
                if (!values.has(key) && prop == 2 && values.has("translateX")) key = "translateX";
                if (!values.has(key) && prop == 3 && values.has("translateY")) key = "translateY";
                if (!values.has(key)) continue;
                double raw = values.optDouble(key, Double.NaN);
                double t = frame.has("timeMs") ? frame.optDouble("timeMs", 0) * 1000 / durationUs
                        : frame.has("timeUs") ? frame.optDouble("timeUs", 0) / durationUs
                        : frame.optDouble("t", i / (double) Math.max(1, frames.length() - 1));
                if (!Double.isFinite(raw) || !Double.isFinite(t)) continue;
                points.add(new Point(clamp((float) t, 0, 1), (float) raw,
                        frame.optString("easing", defaultEasing),
                        CubicBezierEasing.forKeyframe(frame, "easing", defaultEasing, defaultCubic), i));
            }
            points.sort(Comparator.comparingDouble((Point point) -> point.t).thenComparingInt(point -> point.index));
            ArrayList<Point> unique = new ArrayList<>();
            for (Point point : points) {
                if (!unique.isEmpty() && unique.get(unique.size() - 1).t == point.t) unique.set(unique.size() - 1, point);
                else unique.add(point);
            }
            result[prop] = new Curve(unique);
        }
        return result;
    }

    private Motion presetMotion(float p, long timeUs) {
        float e = presetCubic == null ? ease(p, easing) : presetCubic.at(p);
        Motion m = new Motion();
        switch (preset) {
            case "push_in": case "ken_burns": m.scaleX = m.scaleY = 1 + .08f * e; break;
            case "pull_out": m.scaleX = m.scaleY = 1.08f - .08f * e; break;
            case "pan_left": m.scaleX = m.scaleY = 1.06f; m.x = .06f - .12f * e; break;
            case "pan_right": m.scaleX = m.scaleY = 1.06f; m.x = -.06f + .12f * e; break;
            case "pan_up": m.scaleX = m.scaleY = 1.06f; m.y = -.06f + .12f * e; break;
            case "pan_down": m.scaleX = m.scaleY = 1.06f; m.y = .06f - .12f * e; break;
            case "drift": case "float": case "parallax":
                m.scaleX = m.scaleY = 1.035f; m.x = sin(p * Math.PI * 2) * .025f; m.y = cos(p * Math.PI * 2) * .018f; break;
            case "orbit": m.scaleX = m.scaleY = 1.045f; m.x = sin(p * Math.PI * 2) * .035f;
                m.y = cos(p * Math.PI * 2) * .035f; m.rotation = sin(p * Math.PI * 2) * 1.2f; break;
            case "micro_shake": case "handheld":
                m.scaleX = m.scaleY = 1.015f; m.x = sin(timeUs / 33000.0 * 2.1) * .006f;
                m.y = sin(timeUs / 27000.0 * 1.7) * .004f; m.rotation = sin(timeUs / 45000.0) * .25f; break;
            case "impact_shake": m.scaleX = m.scaleY = 1.03f;
                m.x = sin(timeUs / 16000.0) * .022f * (1 - p); m.y = cos(timeUs / 19000.0) * .018f * (1 - p);
                m.rotation = sin(timeUs / 23000.0) * (1 - p); break;
            case "snap_zoom": case "zoom_punch":
                float pulse = p < .22f ? p / .22f : Math.max(0, 1 - (p - .22f) / .35f);
                m.scaleX = m.scaleY = 1 + .12f * pulse; break;
            case "bounce": m.y = Math.abs(sin(p * Math.PI * 3)) * .10f * (1 - p); break;
            case "elastic_pop":
                float pop = p == 0 ? 0 : p == 1 ? 1 : (float) (Math.pow(2, -10 * p) * Math.sin((p - .075) * Math.PI * 2 / .3) + 1);
                m.scaleX = m.scaleY = .75f + .25f * pop; break;
            case "tilt": m.rotation = -1.2f + 2.4f * e; m.scaleX = m.scaleY = 1.025f; break;
            case "roll": m.rotation = -2 + 4 * e; m.scaleX = m.scaleY = 1.04f; break;
            case "hero_reveal": m.scaleX = m.scaleY = .96f + .07f * e; m.y = -.06f * (1 - e); break;
            default: break;
        }
        return m;
    }

    private void applyLayerDepth(Motion m) {
        float parallax = value(spec, "parallaxStrength", .8f, 0, 1);
        float depth = 1;
        switch (layerRole) {
            case "head": depth = value(spec, "headDepth", 1.07f, .08f, 1.5f); break;
            case "torso": depth = value(spec, "torsoDepth", 1, .08f, 1.5f); break;
            case "lower": depth = value(spec, "lowerDepth", .94f, .08f, 1.5f); break;
            case "foreground": depth = value(spec, "foregroundDepth", 1, .08f, 1.5f); break;
            case "background": depth = value(spec, "backgroundDepth", .36f, .08f, 1.5f); break;
            default: break;
        }
        m.x *= (.28f + .72f * parallax) * depth; m.y *= (.28f + .72f * parallax) * depth;
        float zoomDepth = "background".equals(layerRole) ? Math.max(.34f, depth) : Math.max(.72f, depth);
        m.scaleX = 1 + (m.scaleX - 1) * zoomDepth; m.scaleY = 1 + (m.scaleY - 1) * zoomDepth;
        m.rotation *= "background".equals(layerRole) ? .32f : Math.min(1.15f, depth);
    }

    private void applyOrganicMotion(Motion m, float p) {
        double twoPi = Math.PI * 2;
        double swayCycles = number(spec, "swayCycles", .8);
        if ("head".equals(layerRole)) {
            m.x += number(spec, "headSwayAmplitudeX", .0022) * sin(twoPi * swayCycles * p + .35);
            m.y += number(spec, "headSwayAmplitudeY", .0015) * sin(twoPi * swayCycles * .71 * p + 1);
            m.rotation += number(spec, "headNodDegrees", .18) * sin(twoPi * swayCycles * .62 * p + .55);
        } else if ("torso".equals(layerRole)) {
            float breath = sin(twoPi * number(spec, "breathingCycles", 1.2) * p - Math.PI / 2);
            float scale = 1 + (float) number(spec, "torsoBreathScale", .006) * (.5f + .5f * breath);
            m.scaleX *= scale; m.scaleY *= scale;
            m.y += number(spec, "torsoBreathShiftY", .002) * breath;
            m.x += number(spec, "swayAmplitudeX", 0) * .55f * sin(twoPi * .74 * p);
        } else if ("lower".equals(layerRole)) {
            m.x += number(spec, "lowerSwayAmplitudeX", .003) * sin(twoPi * .58 * p + .8);
            m.rotation += number(spec, "lowerSwayDegrees", .14) * sin(twoPi * .48 * p + .35);
        } else if ("foreground".equals(layerRole)) {
            float breath = sin(twoPi * number(spec, "breathingCycles", 1.2) * p - Math.PI / 2);
            float scale = 1 + (float) number(spec, "breathingAmplitude", 0) * (.5f + .5f * breath);
            m.scaleX *= scale; m.scaleY *= scale;
            m.y += number(spec, "breathingAmplitude", 0) * .36f * breath;
            m.x += number(spec, "swayAmplitudeX", 0) * sin(twoPi * swayCycles * p);
            m.y += number(spec, "swayAmplitudeY", 0) * sin(twoPi * swayCycles * .73 * p + .8);
            m.rotation += number(spec, "microRotation", 0) * sin(twoPi * swayCycles * p + .4);
        } else if ("background".equals(layerRole)) {
            String environment = spec.optString("environmentMotion", "ambient_drift");
            if ("flowing_water".equals(environment)) {
                m.x += .0028f * sin(twoPi * 1.35 * p); m.y += .0018f * sin(twoPi * 2.15 * p + .7);
            } else if ("wind_drift".equals(environment)) {
                m.x += .0035f * sin(twoPi * .58 * p); m.y += .0014f * cos(twoPi * .76 * p);
            } else if ("mist_float".equals(environment)) {
                m.x += .0022f * sin(twoPi * .35 * p); m.y += .0028f * cos(twoPi * .31 * p);
            } else {
                m.x += .0015f * sin(twoPi * .42 * p); m.y += .0010f * cos(twoPi * .37 * p);
            }
        }
    }

    public static float ease(float t, String easing) {
        t = clamp(t, 0, 1);
        if ("cubic_bezier".equals(easing)) throw new IllegalArgumentException("Cubic Bézier easing requires compiled control points");
        if ("hold".equals(easing) || "step".equals(easing)) return t < 1 ? 0 : 1;
        if ("linear".equals(easing)) return t;
        if ("ease_out".equals(easing) || "easeOut".equals(easing)) return 1 - (1 - t) * (1 - t) * (1 - t);
        if ("ease_in".equals(easing) || "easeIn".equals(easing)) return t * t * t;
        if ("cinematic".equals(easing)) return t * t * t * (t * (t * 6 - 15) + 10);
        return t * t * (3 - 2 * t);
    }

    private static final class Point {
        final float t, value; final String easing; final CubicBezierEasing cubic; final int index;
        Point(float t, float value, String easing, CubicBezierEasing cubic, int index) {
            this.t=t; this.value=value; this.easing=easing; this.cubic=cubic; this.index=index;
        }
    }
    private static final class Curve {
        final List<Point> points;
        Curve(List<Point> points) { this.points = points; }
        float at(float t, float fallback) {
            if (points.isEmpty()) return fallback;
            if (t <= points.get(0).t) return points.get(0).value;
            for (int i = 1; i < points.size(); i++) {
                Point b = points.get(i), a = points.get(i - 1);
                if (t <= b.t) {
                    float progress = (t - a.t) / (b.t - a.t);
                    return a.value + (b.value - a.value) * (a.cubic == null ? ease(progress, a.easing) : a.cubic.at(progress));
                }
            }
            return points.get(points.size() - 1).value;
        }
    }
    private static final class Motion { float scaleX=1,scaleY=1,x,y,rotation,opacity=1; }
    private static JSONObject copy(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid motion settings", error); }
    }
    private static double number(JSONObject object, String key, double fallback) {
        double value = object.optDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }
    private static float value(JSONObject object, String key, float fallback, float min, float max) {
        return clamp((float) number(object, key, fallback), min, max);
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float sin(double value) { return (float) Math.sin(value); }
    private static float cos(double value) { return (float) Math.cos(value); }
}
