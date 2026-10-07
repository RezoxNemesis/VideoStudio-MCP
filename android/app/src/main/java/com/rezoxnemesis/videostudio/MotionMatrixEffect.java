package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;

import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

/**
 * Lightweight GPU matrix animation used by native exports.
 * Coordinates are normalized; the source dimensions are preserved.
 */
@UnstableApi
public final class MotionMatrixEffect implements MatrixTransformation {
    private final String preset;
    private final long durationUs;
    private final long transitionUs;
    private int width;
    private int height;

    public MotionMatrixEffect(String preset, long durationUs, long transitionUs) {
        this.preset = preset == null ? "none" : preset;
        this.durationUs = Math.max(1, durationUs);
        this.transitionUs = Math.max(0, Math.min(durationUs / 2, transitionUs));
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
        width = inputWidth;
        height = inputHeight;
        return new Size(inputWidth, inputHeight);
    }

    @Override
    public Matrix getMatrix(long presentationTimeUs) {
        float p = clamp(presentationTimeUs / (float) durationUs);
        float eased = p * p * (3f - 2f * p);
        Matrix m = new Matrix();

        float scale = 1f;
        float tx = 0f;
        float ty = 0f;
        float rotation = 0f;

        switch (preset) {
            case "push_in":
            case "ken_burns":
                scale = 1f + .08f * eased;
                break;
            case "pull_out":
                scale = 1.08f - .08f * eased;
                break;
            case "pan_left":
                scale = 1.06f;
                tx = .06f - .12f * eased;
                break;
            case "pan_right":
                scale = 1.06f;
                tx = -.06f + .12f * eased;
                break;
            case "pan_up":
                scale = 1.06f;
                ty = .06f - .12f * eased;
                break;
            case "pan_down":
                scale = 1.06f;
                ty = -.06f + .12f * eased;
                break;
            case "drift":
            case "float":
                scale = 1.035f;
                tx = (float) Math.sin(p * Math.PI * 2) * .025f;
                ty = (float) Math.cos(p * Math.PI * 2) * .018f;
                break;
            case "orbit":
                scale = 1.045f;
                tx = (float) Math.sin(p * Math.PI * 2) * .035f;
                ty = (float) Math.cos(p * Math.PI * 2) * .035f;
                rotation = (float) Math.sin(p * Math.PI * 2) * 1.2f;
                break;
            case "micro_shake":
            case "handheld":
                tx = (float) Math.sin(presentationTimeUs / 33000.0 * 2.1) * .006f;
                ty = (float) Math.sin(presentationTimeUs / 27000.0 * 1.7) * .004f;
                rotation = (float) Math.sin(presentationTimeUs / 45000.0) * .25f;
                scale = 1.015f;
                break;
            case "impact_shake":
                float decay = 1f - p;
                tx = (float) Math.sin(presentationTimeUs / 16000.0) * .022f * decay;
                ty = (float) Math.cos(presentationTimeUs / 19000.0) * .018f * decay;
                rotation = (float) Math.sin(presentationTimeUs / 23000.0) * 1.0f * decay;
                scale = 1.03f;
                break;
            case "snap_zoom":
            case "zoom_punch":
                float pulse = p < .22f ? p / .22f : Math.max(0, 1f - (p - .22f) / .35f);
                scale = 1f + .12f * pulse;
                break;
            case "tilt":
                rotation = -1.2f + 2.4f * eased;
                scale = 1.025f;
                break;
            case "roll":
                rotation = -2f + 4f * eased;
                scale = 1.04f;
                break;
            case "hero_reveal":
                scale = .96f + .07f * eased;
                ty = .06f * (1f - eased);
                break;
            default:
                break;
        }

        // Give non-overlap transitions a cinematic entrance/exit using the frame itself.
        if (transitionUs > 0) {
            float edge = 1f;
            if (presentationTimeUs < transitionUs) {
                edge = clamp(presentationTimeUs / (float) transitionUs);
            } else if (presentationTimeUs > durationUs - transitionUs) {
                edge = clamp((durationUs - presentationTimeUs) / (float) transitionUs);
            }
            float transitionEase = edge * edge * (3f - 2f * edge);
            if (preset.contains("slide_left")) tx += -.18f * (1f - transitionEase);
            if (preset.contains("slide_right")) tx += .18f * (1f - transitionEase);
            if (preset.contains("slide_up")) ty += .18f * (1f - transitionEase);
            if (preset.contains("slide_down")) ty += -.18f * (1f - transitionEase);
        }

        m.setScale(scale, scale);
        if (rotation != 0f) m.postRotate(rotation);
        if (tx != 0f || ty != 0f) m.postTranslate(tx, ty);
        return m;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
