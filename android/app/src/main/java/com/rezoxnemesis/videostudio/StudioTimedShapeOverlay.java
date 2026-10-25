package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Pair;

import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;

/**
 * Actual native Canvas geometry rendered into a bounded bitmap only once per
 * shader generation, then transformed for every output frame by Media3 GPU.
 *
 * No Gallery, external image source, generative model or unbounded per-frame
 * Bitmap allocations. Motion uses the same local clock and 64-key contract
 * as image/title/video layers.
 */
public final class StudioTimedShapeOverlay extends BitmapOverlay {
    public static final int RASTER_SIZE = 640;
    private final StudioLayer layer;
    private final StudioLayerMotion motion;
    private final StudioParentGroupTransform parent;
    private final StudioLayer matte;
    private Bitmap raster;
    private boolean released;
    private int frameWidth = 1, frameHeight = 1;

    public StudioTimedShapeOverlay(StudioLayer incoming){this(incoming,null,null);}
    public StudioTimedShapeOverlay(StudioLayer incoming,StudioLayer group){this(incoming,group,null);}
    public StudioTimedShapeOverlay(StudioLayer incoming,StudioLayer group,StudioLayer matteSource) {
        if (incoming == null || !StudioLayer.SHAPE.equals(incoming.type))
            throw new IllegalArgumentException("A native original shape lane is required");
        incoming.validate();
        layer = incoming.copy();
        motion = StudioLayerMotion.fromLayer(layer);
        parent=group==null?null:new StudioParentGroupTransform(group);
        if(matteSource!=null)StudioTrackMatteRasterizer.validatePair(layer,matteSource);
        matte=matteSource==null?null:matteSource.copy();
    }

    @Override public void configure(Size frame) {
        super.configure(frame);
        frameWidth = Math.max(1, frame.getWidth());
        frameHeight = Math.max(1, frame.getHeight());
    }

    public boolean activeAt(long timeUs) {
        return timeUs >= layer.startMs * 1000L &&
                timeUs < (layer.startMs + layer.durationMs) * 1000L;
    }

    @Override public Bitmap getBitmap(long timeUs) throws VideoFrameProcessingException {
        if (released)
            throw new VideoFrameProcessingException("Original shape overlay has been released");
        if (raster == null) {
            try {
                raster = drawOriginalShape(layer);
                if(layer.mask!=null)StudioMaskRasterizer.apply(raster,layer.mask);
                if(matte!=null)StudioTrackMatteRasterizer.apply(raster,layer,matte);
                if(layer.cornerPin!=null){
                    Bitmap earlier=raster;
                    Bitmap projected=StudioCornerPinRasterizer.apply(earlier,layer.cornerPin);
                    if(projected!=earlier){raster=projected;earlier.recycle();}
                }
            } catch (RuntimeException | OutOfMemoryError error) {
                throw new VideoFrameProcessingException("Could not construct native shape geometry", error);
            }
        }
        return raster;
    }

    public static Bitmap drawOriginalShape(StudioLayer layer) {
        if (layer == null || !StudioLayer.SHAPE.equals(layer.type))
            throw new IllegalArgumentException("Only native shapes can be rasterized");
        layer.validate();
        Bitmap bitmap = Bitmap.createBitmap(RASTER_SIZE, RASTER_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(layer.color);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(layer.strokeColor);
        stroke.setStrokeWidth(Math.max(1f, layer.strokeWidth * RASTER_SIZE));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStyle(Paint.Style.STROKE);

        float edge = Math.max(4f, stroke.getStrokeWidth() / 2f + 2f);
        float max = RASTER_SIZE - edge;
        RectF rect = new RectF(edge, edge, max, max);

        switch (layer.shapeKind) {
            case "rect":
                canvas.drawRect(rect, fill);
                if (layer.strokeWidth > 0) canvas.drawRect(rect, stroke);
                break;
            case "ellipse":
                canvas.drawOval(rect, fill);
                if (layer.strokeWidth > 0) canvas.drawOval(rect, stroke);
                break;
            case "ring":
                canvas.drawOval(rect, stroke);
                break;
            case "diamond": {
                Path diamond = new Path();
                float center = RASTER_SIZE / 2f;
                diamond.moveTo(center, edge);
                diamond.lineTo(max, center);
                diamond.lineTo(center, max);
                diamond.lineTo(edge, center);
                diamond.close();
                canvas.drawPath(diamond, fill);
                if (layer.strokeWidth > 0) canvas.drawPath(diamond, stroke);
                break;
            }
            case "line":
                canvas.drawLine(edge, max, max, edge, stroke);
                break;
            case "grid": {
                // A rendered perspective-style grid: converging source-drawn
                // geometry, not a claim of true 3D camera or 3D warp.
                float horizon = RASTER_SIZE * .32f;
                float center = RASTER_SIZE / 2f;
                stroke.setStrokeWidth(Math.max(1.5f, layer.strokeWidth * 110f));
                for (int i = -8; i <= 8; i++) {
                    float farX = center + i * 5f;
                    float nearX = center + i * 49f;
                    canvas.drawLine(farX, horizon, nearX, RASTER_SIZE - edge, stroke);
                }
                for (int row = 0; row <= 13; row++) {
                    float t = row / 13f;
                    float y = horizon + (RASTER_SIZE - edge - horizon) * t * t;
                    canvas.drawLine(edge, y, max, y, stroke);
                }
                break;
            }
            default:
                bitmap.recycle();
                throw new IllegalArgumentException("Unknown native shape");
        }
        return bitmap;
    }

    @Override public OverlaySettings getOverlaySettings(long timeUs) {
        boolean active = activeAt(timeUs);
        long localTimeUs = active ? timeUs - layer.startMs * 1000L : 0;
        StudioClipKeyframes.Values value = motion.atUs(localTimeUs);
        float x = (2f * layer.x - 1f) + (float) (2 * value.x);
        float y = (1f - 2f * layer.y) - (float) (2 * value.y);
        float sx = frameWidth * layer.width / RASTER_SIZE * (float) value.scaleX;
        float sy = frameHeight * layer.height / RASTER_SIZE * (float) value.scaleY;
        float angle = layer.rotation + (float) value.rotate;
        float alpha = active ? layer.opacity * (float) value.opacity : 0f;
        OverlaySettings own=new FrameSettings(x, y, sx, sy, angle, alpha);
        return parent==null?own:parent.apply(own,timeUs);
    }

    private static final class FrameSettings implements OverlaySettings {
        final Pair<Float,Float> background, scale;
        final float angle, alpha;
        FrameSettings(float x, float y, float sx, float sy, float angle, float alpha) {
            background = Pair.create(x, y);
            scale = Pair.create(sx, sy);
            this.angle = angle;
            this.alpha = alpha;
        }
        @Override public Pair<Float,Float> getBackgroundFrameAnchor() { return background; }
        @Override public Pair<Float,Float> getOverlayFrameAnchor() {
            return OverlaySettings.DEFAULT_OVERLAY_FRAME_ANCHOR;
        }
        @Override public Pair<Float,Float> getScale() { return scale; }
        @Override public float getRotationDegrees() { return angle; }
        @Override public float getAlphaScale() { return alpha; }
    }

    @Override public void release() throws VideoFrameProcessingException {
        try {
            super.release();
        } finally {
            released = true;
            if (raster != null && !raster.isRecycled()) raster.recycle();
            raster = null;
        }
    }
}
