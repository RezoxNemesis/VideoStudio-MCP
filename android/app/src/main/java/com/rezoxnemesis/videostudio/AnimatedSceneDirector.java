package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Converts portrait analysis into deterministic native motion choreography.
 *
 * This is intentionally not a random slideshow generator. It varies shot
 * grammar by analysed subject/face coverage and by timeline position so a
 * sequence gets establishing, medium, close and release beats.
 */
public final class AnimatedSceneDirector {
    private AnimatedSceneDirector() {}

    public static JSONObject attachPlan(ProjectStore.Clip clip,
                                        NativePortraitMotionAnalyzer.Result layers,
                                        int index,
                                        int total,
                                        String style,
                                        double intensity,
                                        long durationMs,
                                        String environment) throws Exception {
        if (clip.effects == null) clip.effects = new JSONObject();
        JSONObject analysis = layers.analysis;
        JSONObject spec = buildSpec(analysis, index, total, style, intensity, durationMs, environment);

        clip.inMs = 0;
        clip.outMs = Math.max(1800, durationMs);
        clip.speed = 1f;
        clip.programDurationMs = -1L;
        clip.transition = transitionFor(index, total, style);
        clip.effects.put("animationDurationMs", clip.outputDurationMs());
        clip.effects.put("animationOffsetMs", 0L);
        clip.effects.put("animatedScene", true);
        clip.effects.put("animationEngine", "videostudio-native-articulated-parallax-v2");
        clip.effects.put("animationAnalysis", analysis);
        clip.effects.put("animationSpec", spec);
        clip.effects.put("foregroundUri", layers.foregroundUri.toString());
        clip.effects.put("headUri", layers.headUri.toString());
        clip.effects.put("torsoUri", layers.torsoUri.toString());
        clip.effects.put("lowerUri", layers.lowerUri.toString());
        clip.effects.put("backgroundUri", layers.backgroundUri.toString());
        clip.effects.put("motionPreset", spec.optString("cameraPreset", "push_in"));
        // motionBlur remains a disclosed planning hint in animationSpec. There
        // is no optical-flow renderer, so never publish it as an active effect.
        clip.effects.remove("motionBlur");
        clip.effects.put("effectPreset", effectPreset(style));
        return spec;
    }

    public static JSONObject buildSpec(JSONObject analysis,
                                       int index,
                                       int total,
                                       String style,
                                       double intensity,
                                       long durationMs,
                                       String environment) throws Exception {
        double power = clamp(intensity, 0.15, 1.0);
        String shot = analysis.optString("shotType", "medium");
        double faceX = analysis.optDouble("faceCenterX", analysis.optDouble("subjectCenterX", 0.5));
        double faceY = analysis.optDouble("faceCenterY", Math.max(0.2, analysis.optDouble("subjectCenterY", 0.5) - 0.15));
        double subjectX = analysis.optDouble("subjectCenterX", 0.5);
        double subjectY = analysis.optDouble("subjectCenterY", 0.55);

        int beat = Math.floorMod(index, 6);
        double direction = faceX < 0.48 ? 1.0 : -1.0;
        double faceCorrectionX = clamp((0.5 - faceX) * 0.16, -0.055, 0.055);
        double faceCorrectionY = clamp((0.43 - faceY) * 0.10, -0.035, 0.035);

        double startScale;
        double endScale;
        if ("wide".equals(shot)) {
            startScale = 1.025;
            endScale = 1.105;
        } else if ("close".equals(shot)) {
            startScale = 1.055;
            endScale = 1.095;
        } else {
            startScale = 1.035;
            endScale = 1.115;
        }

        String cameraPreset;
        switch (beat) {
            case 0: cameraPreset = "hero_reveal"; break;
            case 1: cameraPreset = "push_in"; break;
            case 2: cameraPreset = direction > 0 ? "pan_right" : "pan_left"; break;
            case 3: cameraPreset = "pull_out"; break;
            case 4: cameraPreset = "drift"; break;
            default: cameraPreset = "push_in"; break;
        }

        if ("dreamy".equals(style)) {
            startScale += .01;
            endScale -= .005;
        } else if ("dramatic".equals(style) || "epic".equals(style)) {
            endScale += .025 * power;
        }

        double travel = (0.020 + 0.040 * power) * direction;
        double vertical = (beat % 2 == 0 ? -1 : 1) * (0.006 + 0.012 * power);
        if ("close".equals(shot)) {
            travel *= 0.58;
            vertical *= 0.55;
        }

        JSONArray keyframes = new JSONArray();
        keyframes.put(kf(0.0, startScale, faceCorrectionX - travel * .45, faceCorrectionY - vertical * .35, rotationFor(beat, -1, power)));
        keyframes.put(kf(0.30, lerp(startScale, endScale, .36), faceCorrectionX - travel * .12, faceCorrectionY + vertical * .16, rotationFor(beat, 0, power)));
        keyframes.put(kf(0.72, lerp(startScale, endScale, .76), faceCorrectionX + travel * .27, faceCorrectionY - vertical * .12, rotationFor(beat, 1, power)));
        keyframes.put(kf(1.0, endScale, faceCorrectionX + travel * .52, faceCorrectionY + vertical * .26, rotationFor(beat, 2, power)));

        JSONObject spec = new JSONObject();
        spec.put("version", 1);
        spec.put("style", safeStyle(style));
        spec.put("environment", environment == null ? "" : environment);
        spec.put("shotType", shot);
        spec.put("cameraPreset", cameraPreset);
        spec.put("transitionPreset", transitionFor(index, total, style));
        spec.put("durationMs", durationMs);
        spec.put("easing", "cinematic");
        spec.put("keyframes", keyframes);
        spec.put("subjectAnchorX", subjectX);
        spec.put("subjectAnchorY", subjectY);
        spec.put("faceAnchorX", faceX);
        spec.put("faceAnchorY", faceY);

        // Independent layer movement creates the actual 2.5D effect.
        spec.put("foregroundDepth", 1.0);
        spec.put("headDepth", 1.07);
        spec.put("torsoDepth", 1.0);
        spec.put("lowerDepth", 0.94);
        spec.put("backgroundDepth", 0.36);
        spec.put("parallaxStrength", 0.65 + 0.35 * power);

        // Organic micro-motion remains subtle enough not to bend anatomy.
        spec.put("breathingAmplitude", ("close".equals(shot) ? 0.0045 : 0.0065) * power);
        spec.put("breathingCycles", "close".equals(shot) ? 1.15 : 1.45);
        spec.put("swayAmplitudeX", (0.0025 + 0.0030 * power) * direction);
        spec.put("swayAmplitudeY", 0.0015 + 0.0020 * power);
        spec.put("swayCycles", 0.72 + (index % 3) * .08);
        spec.put("microRotation", ("dramatic".equals(style) ? .34 : .18) * power);
        spec.put("headSwayAmplitudeX", (0.0018 + 0.0024 * power) * direction);
        spec.put("headSwayAmplitudeY", 0.0012 + 0.0015 * power);
        spec.put("headNodDegrees", ("close".equals(shot) ? .16 : .24) * power);
        spec.put("torsoBreathScale", (0.0045 + 0.0035 * power));
        spec.put("torsoBreathShiftY", (0.0015 + 0.0017 * power));
        spec.put("lowerSwayAmplitudeX", (0.0025 + 0.0040 * power) * -direction);
        spec.put("lowerSwayDegrees", 0.18 * power);
        spec.put("motionBlur", 0.12 + 0.20 * power);
        spec.put("focusPulse", "dreamy".equals(style) ? 0.20 : 0.08);
        spec.put("environmentMotion", environmentMotion(environment));
        spec.put("atmosphereIntensity", 0.22 + 0.46 * power);
        return spec;
    }

    private static JSONObject kf(double t, double scale, double x, double y, double rotation) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", t);
        o.put("scale", scale);
        o.put("x", x);
        o.put("y", y);
        o.put("rotation", rotation);
        return o;
    }

    private static double rotationFor(int beat, int stage, double power) {
        if (beat == 4) return Math.sin((stage + 2) * 1.3) * .34 * power;
        if (beat == 2) return stage * .12 * power;
        return stage * .055 * power;
    }

    private static String transitionFor(int index, int total, String style) {
        if (index == 0) return "fade";
        if (index == total - 1) return "dip_black";
        if ("dramatic".equals(style) || "epic".equals(style)) {
            return index % 4 == 0 ? "whip_right" : (index % 3 == 0 ? "zoom_in" : "fade");
        }
        if ("dreamy".equals(style)) return index % 3 == 0 ? "dip_white" : "fade";
        return index % 4 == 0 ? "push_left" : "fade";
    }

    private static String effectPreset(String style) {
        if ("dreamy".equals(style)) return "soft_glow";
        if ("dramatic".equals(style)) return "high_contrast";
        if ("epic".equals(style)) return "teal_orange";
        if ("warm".equals(style) || "romantic".equals(style)) return "warm_film";
        return "cinematic";
    }

    private static String environmentMotion(String environment) {
        String e = environment == null ? "" : environment.toLowerCase();
        if (e.contains("water") || e.contains("river") || e.contains("rain")) return "flowing_water";
        if (e.contains("forest") || e.contains("wind")) return "wind_drift";
        if (e.contains("mist") || e.contains("fog")) return "mist_float";
        if (e.contains("light") || e.contains("sun")) return "light_breathe";
        return "ambient_drift";
    }

    private static String safeStyle(String value) {
        if (value == null || value.isEmpty()) return "cinematic";
        return value;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
