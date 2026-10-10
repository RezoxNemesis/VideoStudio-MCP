package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Immutable timing curve. Input progress is its X coordinate, never the Bézier parameter. */
public final class CubicBezierEasing {
    private final double x1, y1, x2, y2;

    public CubicBezierEasing(double x1, double y1, double x2, double y2) {
        if (!finite(x1) || !finite(x2) || x1 < 0 || x1 > 1 || x2 < 0 || x2 > 1
                || !finite(y1) || !finite(y2) || y1 < -4 || y1 > 4 || y2 < -4 || y2 > 4)
            throw new IllegalArgumentException("Cubic Bézier easing needs X controls in [0,1] and Y controls in [-4,4]");
        this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
    }

    public static CubicBezierEasing fromJson(JSONArray controls) {
        if (controls == null || controls.length() != 4)
            throw new IllegalArgumentException("Cubic Bézier easing requires exactly four numeric controls");
        return new CubicBezierEasing(number(controls, 0), number(controls, 1), number(controls, 2), number(controls, 3));
    }

    public double at(double progress) {
        if (!finite(progress)) throw new IllegalArgumentException("Easing progress must be finite");
        if (progress <= 0) return 0;
        if (progress >= 1) return 1;
        double low = 0, high = 1, parameter = progress;
        for (int iteration = 0; iteration < 12; iteration++) {
            double error = coordinate(parameter, x1, x2) - progress;
            if (Math.abs(error) < 1e-10) return coordinate(parameter, y1, y2);
            if (error < 0) low = parameter; else high = parameter;
            double derivative = derivative(parameter, x1, x2);
            double candidate = derivative > 1e-10 ? parameter - error / derivative : Double.NaN;
            parameter = finite(candidate) && candidate > low && candidate < high ? candidate : (low + high) * .5;
        }
        for (int iteration = 0; iteration < 40; iteration++) {
            double value = coordinate(parameter, x1, x2);
            if (Math.abs(value - progress) < 1e-10) break;
            if (value < progress) low = parameter; else high = parameter;
            parameter = (low + high) * .5;
        }
        return coordinate(parameter, y1, y2);
    }

    public float at(float progress) { return (float) at((double) progress); }

    public JSONArray toJson() {
        try { return new JSONArray().put(x1).put(y1).put(x2).put(y2); }
        catch (Exception failure) { throw new IllegalStateException("Could not encode Bézier controls", failure); }
    }

    /** Explicit custom keys carry their own curve; implicit keys may inherit a compiled default. */
    public static CubicBezierEasing forKeyframe(JSONObject frame, String easingKey,
                                                String defaultName, CubicBezierEasing defaultCurve) {
        if (frame == null) throw new IllegalArgumentException("Keyframe must be an object");
        String name = frame.has(easingKey) ? text(frame, easingKey) : defaultName;
        if (!MotionTimeline.supportsEasing(name)) throw new IllegalArgumentException("Unsupported easing: " + name);
        if (!"cubic_bezier".equals(name)) {
            if (frame.has("bezier")) throw new IllegalArgumentException("Bézier controls require cubic_bezier easing");
            return null;
        }
        if (frame.has("bezier")) return fromJson(frame.optJSONArray("bezier"));
        if (!frame.has(easingKey) && defaultCurve != null) return defaultCurve;
        throw new IllegalArgumentException("A cubic_bezier keyframe requires its own four Bézier controls");
    }

    public static CubicBezierEasing compileDefault(JSONObject owner, String easingKey,
                                                    String bezierKey, String fallbackName) {
        if (owner == null) return null;
        String name = owner.has(easingKey) ? text(owner, easingKey) : fallbackName;
        if (!MotionTimeline.supportsEasing(name)) throw new IllegalArgumentException("Unsupported easing: " + name);
        if ("cubic_bezier".equals(name)) return fromJson(owner.optJSONArray(bezierKey));
        if (owner.has(bezierKey)) throw new IllegalArgumentException(bezierKey + " requires cubic_bezier easing");
        return null;
    }

    public static void validateKeyframe(JSONObject frame, String easingKey, List<String> errors) {
        try { forKeyframe(frame, easingKey, "linear", null); }
        catch (IllegalArgumentException failure) { errors.add(failure.getMessage()); }
    }

    public static void validateDefault(JSONObject owner, String easingKey, String bezierKey,
                                       String fallbackName, List<String> errors) {
        try { compileDefault(owner, easingKey, bezierKey, fallbackName); }
        catch (IllegalArgumentException failure) { errors.add(failure.getMessage()); }
    }

    private static double number(JSONArray array, int index) {
        Object value = array.opt(index);
        if (!(value instanceof Number) || !finite(((Number) value).doubleValue()))
            throw new IllegalArgumentException("Bézier controls must be finite numbers");
        return ((Number) value).doubleValue();
    }

    private static String text(JSONObject owner, String key) {
        Object value = owner.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be an easing name");
        return (String) value;
    }

    private static double coordinate(double t, double first, double second) {
        double inverse = 1 - t;
        return 3 * inverse * inverse * t * first + 3 * inverse * t * t * second + t * t * t;
    }

    private static double derivative(double t, double first, double second) {
        double inverse = 1 - t;
        return 3 * inverse * inverse * first + 6 * inverse * t * (second - first) + 3 * t * t * (1 - second);
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
