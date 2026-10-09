package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;

import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Per-frame GPU motion transform used by native exports.
 *
 * v3.1 keeps all legacy motion presets, and additionally accepts a structured
 * animationSpec containing cinematic keyframes and organic micro-motion.
 * Foreground/background layer roles use different depth multipliers to create
 * real 2.5D parallax from the same source portrait.
 */
@UnstableApi
public final class MotionMatrixEffect implements MatrixTransformation {
    private final String preset;
    private final long durationUs;
    private final long transitionUs;
    private final JSONObject animationSpec;
    private final String layerRole;
    private final long sequenceStartUs;

    public MotionMatrixEffect(String preset, long durationUs, long transitionUs) {
        this(preset, durationUs, transitionUs, null, "flat");
    }

    public MotionMatrixEffect(String preset,
                              long durationUs,
                              long transitionUs,
                              JSONObject animationSpec,
                              String layerRole) {
        this(preset,durationUs,transitionUs,animationSpec,layerRole,0);
    }
    public MotionMatrixEffect(String preset,long durationUs,long transitionUs,JSONObject animationSpec,String layerRole,long sequenceStartUs){
        this.sequenceStartUs=sequenceStartUs;
        this.preset = preset == null ? "none" : preset;
        this.durationUs = Math.max(1, durationUs);
        this.transitionUs = Math.max(0, Math.min(this.durationUs / 2, transitionUs));
        this.animationSpec = animationSpec;
        this.layerRole = layerRole == null ? "flat" : layerRole;
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
        return new Size(inputWidth, inputHeight);
    }

    @Override
    public Matrix getMatrix(long presentationTimeUs) {
        presentationTimeUs=Math.max(0,presentationTimeUs-sequenceStartUs);
        float p = clamp(presentationTimeUs / (float) durationUs);
        Motion motion = animationSpec != null
                ? keyframedMotion(p, presentationTimeUs)
                : presetMotion(p, presentationTimeUs);

        if (animationSpec != null) {
            applyLayerDepth(motion);
            applyOrganicMotion(motion, p);
        }

        applyTransitionEdge(motion, presentationTimeUs);

        Matrix matrix = new Matrix();
        matrix.setScale(motion.scale, motion.scale);
        if (Math.abs(motion.rotation) > .0001f) matrix.postRotate(motion.rotation);
        if (Math.abs(motion.x) > .0001f || Math.abs(motion.y) > .0001f) {
            matrix.postTranslate(motion.x, motion.y);
        }
        return matrix;
    }

    private Motion keyframedMotion(float p, long timeUs) {
        JSONArray frames = animationSpec.optJSONArray("keyframes");
        if (frames == null || frames.length() == 0) return presetMotion(p, timeUs);

        JSONObject left = frames.optJSONObject(0);
        JSONObject right = frames.optJSONObject(frames.length() - 1);
        if (left == null || right == null) return presetMotion(p, timeUs);

        for (int i = 0; i < frames.length() - 1; i++) {
            JSONObject a = frames.optJSONObject(i);
            JSONObject b = frames.optJSONObject(i + 1);
            if (a == null || b == null) continue;
            double at = clamp01(a.optDouble("t", i / (double) Math.max(1, frames.length() - 1)));
            double bt = clamp01(b.optDouble("t", (i + 1) / (double) Math.max(1, frames.length() - 1)));
            if (p >= at && p <= bt) {
                left = a;
                right = b;
                break;
            }
        }

        double aT = clamp01(left.optDouble("t", 0));
        double bT = clamp01(right.optDouble("t", 1));
        float local = (float) clamp01((p - aT) / Math.max(.0001, bT - aT));
        float eased = ease(local, animationSpec.optString("easing", "cinematic"));

        Motion m = new Motion();
        m.scale = lerp((float) left.optDouble("scale", 1), (float) right.optDouble("scale", 1), eased);
        m.x = lerp((float) left.optDouble("x", 0), (float) right.optDouble("x", 0), eased);
        m.y = lerp((float) left.optDouble("y", 0), (float) right.optDouble("y", 0), eased);
        m.rotation = lerp((float) left.optDouble("rotation", 0), (float) right.optDouble("rotation", 0), eased);
        return m;
    }

    private void applyLayerDepth(Motion m) {
        double parallax = clamp01(animationSpec.optDouble("parallaxStrength", .8));
        float depth;
        if ("head".equals(layerRole)) {
            depth = (float) animationSpec.optDouble("headDepth", 1.07);
        } else if ("torso".equals(layerRole)) {
            depth = (float) animationSpec.optDouble("torsoDepth", 1.0);
        } else if ("lower".equals(layerRole)) {
            depth = (float) animationSpec.optDouble("lowerDepth", .94);
        } else if ("foreground".equals(layerRole)) {
            depth = (float) animationSpec.optDouble("foregroundDepth", 1.0);
        } else if ("background".equals(layerRole)) {
            depth = (float) animationSpec.optDouble("backgroundDepth", .36);
        } else {
            depth = 1f;
        }
        depth = Math.max(.08f, Math.min(1.5f, depth));

        float translationDepth = (float) (.28 + .72 * parallax) * depth;
        m.x *= translationDepth;
        m.y *= translationDepth;

        float zoomDelta = m.scale - 1f;
        float zoomDepth = "background".equals(layerRole)
                ? Math.max(.34f, depth)
                : Math.max(.72f, depth);
        m.scale = 1f + zoomDelta * zoomDepth;
        m.rotation *= "background".equals(layerRole) ? .32f : Math.min(1.15f, depth);
    }

    private void applyOrganicMotion(Motion m, float p) {
        double twoPi = Math.PI * 2.0;
        if ("head".equals(layerRole)) {
            double swayCycles = animationSpec.optDouble("swayCycles", .8);
            float hx = (float) animationSpec.optDouble("headSwayAmplitudeX", .0022);
            float hy = (float) animationSpec.optDouble("headSwayAmplitudeY", .0015);
            float nod = (float) animationSpec.optDouble("headNodDegrees", .18);
            m.x += hx * (float) Math.sin(twoPi * swayCycles * p + .35);
            m.y += hy * (float) Math.sin(twoPi * swayCycles * .71 * p + 1.0);
            m.rotation += nod * (float) Math.sin(twoPi * swayCycles * .62 * p + .55);
        } else if ("torso".equals(layerRole)) {
            double breathingCycles = animationSpec.optDouble("breathingCycles", 1.2);
            float breath = (float) Math.sin(twoPi * breathingCycles * p - Math.PI / 2.0);
            float scale = (float) animationSpec.optDouble("torsoBreathScale", .006);
            float shift = (float) animationSpec.optDouble("torsoBreathShiftY", .002);
            float swayX = (float) animationSpec.optDouble("swayAmplitudeX", 0);
            m.scale *= 1f + scale * (.5f + .5f * breath);
            m.y += shift * breath;
            m.x += swayX * .55f * (float) Math.sin(twoPi * .74 * p);
        } else if ("lower".equals(layerRole)) {
            float sway = (float) animationSpec.optDouble("lowerSwayAmplitudeX", .003);
            float degrees = (float) animationSpec.optDouble("lowerSwayDegrees", .14);
            m.x += sway * (float) Math.sin(twoPi * .58 * p + .8);
            m.rotation += degrees * (float) Math.sin(twoPi * .48 * p + .35);
        } else if ("foreground".equals(layerRole)) {
            float breathing = (float) animationSpec.optDouble("breathingAmplitude", 0);
            double breathingCycles = animationSpec.optDouble("breathingCycles", 1.2);
            if (breathing > 0) {
                float breath = (float) Math.sin(twoPi * breathingCycles * p - Math.PI / 2.0);
                m.scale *= 1f + breathing * (.5f + .5f * breath);
                m.y += breathing * .36f * breath;
            }

            float swayX = (float) animationSpec.optDouble("swayAmplitudeX", 0);
            float swayY = (float) animationSpec.optDouble("swayAmplitudeY", 0);
            double swayCycles = animationSpec.optDouble("swayCycles", .8);
            m.x += swayX * (float) Math.sin(twoPi * swayCycles * p);
            m.y += swayY * (float) Math.sin(twoPi * (swayCycles * .73) * p + .8);
            m.rotation += (float) animationSpec.optDouble("microRotation", 0)
                    * (float) Math.sin(twoPi * swayCycles * p + .4);
        } else if ("background".equals(layerRole)) {
            String environment = animationSpec.optString("environmentMotion", "ambient_drift");
            if ("flowing_water".equals(environment)) {
                m.x += .0028f * (float) Math.sin(twoPi * 1.35 * p);
                m.y += .0018f * (float) Math.sin(twoPi * 2.15 * p + .7);
            } else if ("wind_drift".equals(environment)) {
                m.x += .0035f * (float) Math.sin(twoPi * .58 * p);
                m.y += .0014f * (float) Math.cos(twoPi * .76 * p);
            } else if ("mist_float".equals(environment)) {
                m.x += .0022f * (float) Math.sin(twoPi * .35 * p);
                m.y += .0028f * (float) Math.cos(twoPi * .31 * p);
            } else {
                m.x += .0015f * (float) Math.sin(twoPi * .42 * p);
                m.y += .0010f * (float) Math.cos(twoPi * .37 * p);
            }
        }
    }

    private Motion presetMotion(float p, long presentationTimeUs) {
        float eased = p * p * (3f - 2f * p);
        Motion m = new Motion();

        switch (preset) {
            case "push_in":
            case "ken_burns":
                m.scale = 1f + .08f * eased;
                break;
            case "pull_out":
                m.scale = 1.08f - .08f * eased;
                break;
            case "pan_left":
                m.scale = 1.06f;
                m.x = .06f - .12f * eased;
                break;
            case "pan_right":
                m.scale = 1.06f;
                m.x = -.06f + .12f * eased;
                break;
            case "pan_up":
                m.scale = 1.06f;
                m.y = .06f - .12f * eased;
                break;
            case "pan_down":
                m.scale = 1.06f;
                m.y = -.06f + .12f * eased;
                break;
            case "drift":
            case "float":
                m.scale = 1.035f;
                m.x = (float) Math.sin(p * Math.PI * 2) * .025f;
                m.y = (float) Math.cos(p * Math.PI * 2) * .018f;
                break;
            case "orbit":
                m.scale = 1.045f;
                m.x = (float) Math.sin(p * Math.PI * 2) * .035f;
                m.y = (float) Math.cos(p * Math.PI * 2) * .035f;
                m.rotation = (float) Math.sin(p * Math.PI * 2) * 1.2f;
                break;
            case "micro_shake":
            case "handheld":
                m.x = (float) Math.sin(presentationTimeUs / 33000.0 * 2.1) * .006f;
                m.y = (float) Math.sin(presentationTimeUs / 27000.0 * 1.7) * .004f;
                m.rotation = (float) Math.sin(presentationTimeUs / 45000.0) * .25f;
                m.scale = 1.015f;
                break;
            case "impact_shake":
                float decay = 1f - p;
                m.x = (float) Math.sin(presentationTimeUs / 16000.0) * .022f * decay;
                m.y = (float) Math.cos(presentationTimeUs / 19000.0) * .018f * decay;
                m.rotation = (float) Math.sin(presentationTimeUs / 23000.0) * 1.0f * decay;
                m.scale = 1.03f;
                break;
            case "snap_zoom":
            case "zoom_punch":
                float pulse = p < .22f ? p / .22f : Math.max(0, 1f - (p - .22f) / .35f);
                m.scale = 1f + .12f * pulse;
                break;
            case "tilt":
                m.rotation = -1.2f + 2.4f * eased;
                m.scale = 1.025f;
                break;
            case "roll":
                m.rotation = -2f + 4f * eased;
                m.scale = 1.04f;
                break;
            case "hero_reveal":
                m.scale = .96f + .07f * eased;
                m.y = .06f * (1f - eased);
                break;
            default:
                break;
        }
        return m;
    }

    private void applyTransitionEdge(Motion m, long presentationTimeUs) {
        if (transitionUs <= 0) return;
        float edge = 1f;
        boolean entrance = false;
        if (presentationTimeUs < transitionUs) {
            edge = clamp(presentationTimeUs / (float) transitionUs);
            entrance = true;
        } else if (presentationTimeUs > durationUs - transitionUs) {
            edge = clamp((durationUs - presentationTimeUs) / (float) transitionUs);
        }
        float e = edge * edge * (3f - 2f * edge);
        float remaining = 1f - e;
        String transition = animationSpec == null
                ? preset
                : animationSpec.optString("transitionPreset", preset);

        float direction = entrance ? 1f : -1f;
        if (transition.contains("slide_left") || transition.contains("push_left")) {
            m.x += -.15f * direction * remaining;
        } else if (transition.contains("slide_right") || transition.contains("push_right")) {
            m.x += .15f * direction * remaining;
        } else if (transition.contains("slide_up")) {
            m.y += .13f * direction * remaining;
        } else if (transition.contains("slide_down")) {
            m.y += -.13f * direction * remaining;
        } else if (transition.contains("whip_left")) {
            m.x += -.24f * direction * remaining;
            m.rotation += -1.1f * direction * remaining;
            m.scale *= 1f + .03f * remaining;
        } else if (transition.contains("whip_right")) {
            m.x += .24f * direction * remaining;
            m.rotation += 1.1f * direction * remaining;
            m.scale *= 1f + .03f * remaining;
        } else if (transition.contains("zoom_in")) {
            m.scale *= 1f + .08f * remaining;
        } else if (transition.contains("zoom_out")) {
            m.scale *= 1f - .05f * remaining;
        } else if (transition.contains("dip") || transition.contains("fade") || transition.contains("blur")) {
            // No alpha overlap is synthesized here. Use a restrained depth
            // pulse so the cut still has temporal momentum without faking a
            // cross-fade that the concatenated sequence cannot actually do.
            m.scale *= 1f + .018f * remaining;
            m.y += .006f * direction * remaining;
        }
    }

    private static float ease(float t, String easing) {
        t = clamp(t);
        if ("linear".equals(easing)) return t;
        if ("ease_out".equals(easing)) return 1f - (1f - t) * (1f - t) * (1f - t);
        if ("ease_in".equals(easing)) return t * t * t;
        if ("cinematic".equals(easing)) {
            // smootherstep gives zero velocity and acceleration at the edges.
            return t * t * t * (t * (t * 6f - 15f) + 10f);
        }
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class Motion {
        float scale = 1f;
        float x = 0f;
        float y = 0f;
        float rotation = 0f;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static double clamp01(double v) {
        return Math.max(0d, Math.min(1d, v));
    }
}
