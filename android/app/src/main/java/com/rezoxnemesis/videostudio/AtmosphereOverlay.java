package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

/**
 * Deterministic procedural atmosphere drawn directly into Media3 frames.
 *
 * It intentionally stays subtle. The purpose is to give still-derived scenes
 * temporal cues such as water sparkle, mist drift, soft light breathing and
 * rain/wind particles without obscuring the subject or looking like a filter.
 */
@UnstableApi
public final class AtmosphereOverlay extends CanvasOverlay {
    private final String environment;
    private final float intensity;
    private final long durationUs;
    private final long sequenceStartUs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AtmosphereOverlay(String environment, double intensity, long durationUs) {
        this(environment,intensity,durationUs,0);
    }
    public AtmosphereOverlay(String environment,double intensity,long durationUs,long sequenceStartUs){
        super(true);
        this.sequenceStartUs=sequenceStartUs;
        this.environment = environment == null ? "ambient_drift" : environment.toLowerCase();
        this.intensity = (float) Math.max(.08, Math.min(1.0, intensity));
        this.durationUs = Math.max(1L, durationUs);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void onDraw(Canvas canvas, long presentationTimeUs) {
        presentationTimeUs=Math.max(0,presentationTimeUs-sequenceStartUs);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        float p = clamp(presentationTimeUs / (float) durationUs);
        if (environment.contains("water") || environment.contains("river")) {
            drawWaterGlints(canvas, w, h, p);
            drawMist(canvas, w, h, p, .32f);
        } else if (environment.contains("rain")) {
            drawRain(canvas, w, h, p);
            drawMist(canvas, w, h, p, .22f);
        } else if (environment.contains("mist") || environment.contains("fog")) {
            drawMist(canvas, w, h, p, .72f);
        } else if (environment.contains("wind") || environment.contains("forest")) {
            drawFloatingSpecks(canvas, w, h, p, .48f);
            drawMist(canvas, w, h, p, .20f);
        } else if (environment.contains("light") || environment.contains("sun")) {
            drawLightBreathe(canvas, w, h, p);
            drawFloatingSpecks(canvas, w, h, p, .26f);
        } else {
            drawFloatingSpecks(canvas, w, h, p, .28f);
            drawLightBreathe(canvas, w, h, p);
        }
    }

    private void drawWaterGlints(Canvas canvas, int w, int h, float p) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w * .0014f));
        int baseAlpha = Math.round(22 + 30 * intensity);
        for (int i = 0; i < 26; i++) {
            float seed = fract(i * .6180339f);
            float y = h * (.50f + .47f * fract(seed * 3.71f));
            float x = w * fract(seed * 7.13f + p * (.08f + .025f * (i % 4)));
            float wave = (float) Math.sin((p * 7.0 + i * .93) * Math.PI * 2);
            float len = w * (.012f + .026f * fract(seed * 5.23f));
            int alpha = Math.max(0, Math.round(baseAlpha * (.45f + .55f * Math.abs(wave))));
            paint.setColor(Color.argb(alpha, 222, 245, 255));
            canvas.drawLine(x - len * .5f, y, x + len * .5f, y + wave * h * .0015f, paint);
        }
    }

    private void drawRain(Canvas canvas, int w, int h, float p) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w * .0011f));
        int alpha = Math.round(18 + 30 * intensity);
        paint.setColor(Color.argb(alpha, 225, 238, 255));
        for (int i = 0; i < 34; i++) {
            float sx = fract(i * .7548777f);
            float speed = .70f + .35f * fract(i * .31337f);
            float x = w * fract(sx + p * .07f);
            float y = h * fract(i * .467f + p * speed);
            float len = h * (.016f + .014f * fract(i * .271f));
            canvas.drawLine(x, y, x - len * .17f, y + len, paint);
        }
    }

    private void drawMist(Canvas canvas, int w, int h, float p, float amount) {
        paint.setStyle(Paint.Style.FILL);
        int blobs = 7;
        for (int i = 0; i < blobs; i++) {
            float seed = fract(i * .4142135f + .11f);
            float drift = (float) Math.sin((p * (.32f + i * .025f) + seed) * Math.PI * 2);
            float x = w * (-.08f + 1.16f * fract(seed * 4.31f + p * (.05f + i * .004f)));
            float y = h * (.18f + .70f * fract(seed * 2.63f));
            float rw = w * (.18f + .16f * fract(seed * 6.17f));
            float rh = h * (.035f + .045f * fract(seed * 5.01f));
            int alpha = Math.round((5 + 12 * intensity) * amount * (.72f + .28f * drift));
            paint.setColor(Color.argb(Math.max(1, alpha), 235, 241, 247));
            canvas.drawOval(new RectF(x - rw, y - rh, x + rw, y + rh), paint);
        }
    }

    private void drawFloatingSpecks(Canvas canvas, int w, int h, float p, float amount) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 24; i++) {
            float seed = fract(i * .5698403f + .17f);
            float x = w * fract(seed * 6.37f + p * (.015f + (i % 5) * .004f));
            float y = h * fract(seed * 3.91f - p * (.025f + (i % 4) * .006f));
            float radius = Math.max(1.2f, w * (.0008f + .0013f * fract(seed * 9.11f)));
            float pulse = .5f + .5f * (float) Math.sin((p * 1.8f + seed) * Math.PI * 2);
            int alpha = Math.round((8 + 25 * pulse) * intensity * amount);
            paint.setColor(Color.argb(alpha, 244, 236, 213));
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    private void drawLightBreathe(Canvas canvas, int w, int h, float p) {
        paint.setStyle(Paint.Style.FILL);
        float pulse = .5f + .5f * (float) Math.sin((p * .85f + .18f) * Math.PI * 2);
        int alpha = Math.round((3 + 9 * pulse) * intensity);
        paint.setColor(Color.argb(alpha, 255, 232, 193));
        float x = w * (.18f + .04f * (float) Math.sin(p * Math.PI * 2));
        float y = h * .14f;
        float radius = Math.max(w, h) * (.18f + .025f * pulse);
        canvas.drawCircle(x, y, radius, paint);
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
