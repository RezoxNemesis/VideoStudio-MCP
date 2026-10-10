package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;

import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;

import org.json.JSONObject;

/** Media3 adapter for the same output-clock motion evaluator used by the preview. */
@UnstableApi
public final class MotionMatrixEffect implements MatrixTransformation {
    private final MotionTimeline timeline;
    private final long timestampOffsetUs;
    private final float clockSpeed;

    public MotionMatrixEffect(MotionTimeline timeline) {
        this(timeline, 0, 1);
    }

    public MotionMatrixEffect(MotionTimeline timeline, long timestampOffsetUs, float clockSpeed) {
        this.timeline = timeline;
        this.timestampOffsetUs = timestampOffsetUs;
        this.clockSpeed = Math.max(.0001f, clockSpeed);
    }

    public MotionMatrixEffect(String preset, long durationUs, long transitionUs) {
        this(preset, durationUs, transitionUs, null, "flat");
    }

    public MotionMatrixEffect(String preset, long durationUs, long transitionUs,
                              JSONObject animationSpec, String layerRole) {
        JSONObject settings = new JSONObject();
        try {
            settings.put("motionPreset", preset == null ? "none" : preset);
            settings.put("transitionDurationMs", Math.max(0, transitionUs / 1000));
            if (animationSpec != null) settings.put("animationSpec", animationSpec);
        } catch (Exception error) { throw new IllegalArgumentException("Invalid motion", error); }
        String transition = animationSpec == null ? "none" : animationSpec.optString("transitionPreset", "none");
        timeline = new MotionTimeline(settings, durationUs, transition, layerRole);
        timestampOffsetUs = 0;
        clockSpeed = 1;
    }

    @Override public Size configure(int inputWidth, int inputHeight) {
        return new Size(inputWidth, inputHeight);
    }

    @Override public Matrix getMatrix(long presentationTimeUs) {
        MotionTimeline.Sample sample = timeline.sample(Math.max(0,
                (long) ((presentationTimeUs - timestampOffsetUs) / (double) clockSpeed)));
        Matrix matrix = new Matrix();
        matrix.setScale(sample.scaleX, sample.scaleY);
        matrix.postRotate(sample.rotation);
        matrix.postTranslate(sample.x, sample.y);
        return matrix;
    }
}
