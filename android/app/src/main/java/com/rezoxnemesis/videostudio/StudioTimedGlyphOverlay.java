package com.rezoxnemesis.videostudio;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Pair;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;
import java.util.ArrayList;
import java.util.List;

/**
 * One native Media3 text texture per non-space codepoint, driven by one stored
 * TITLE layer. Position, opacity, angle and scale vary independently per frame.
 * Frame sampling uses the same clock in preview and Media3 final export.
 */
public final class StudioTimedGlyphOverlay extends TextOverlay {
    private final long startUs, endUs;
    private final SpannableString content;
    private final StudioGlyphAnimation.Spec glyphProgram;
    private final StudioLayerMotion titleMotion;
    private final StudioParentGroupTransform parent;
    private final String wholeText;
    private final int index;
    private final long durationMs;
    private final float anchorY;

    public static List<TextureOverlay> expand(StudioLayer title, StudioLayer group) {
        if (title == null || !StudioLayer.TITLE.equals(title.type) || title.glyphAnimation == null)
            throw new IllegalArgumentException("A glyph-animated title is required");
        title.validate();
        ArrayList<TextureOverlay> result = new ArrayList<>();
        for (int i = 0; i < StudioGlyphAnimation.glyphCount(title.text); i++) {
            String glyph = StudioGlyphAnimation.glyphAt(title.text, i);
            if (!Character.isWhitespace(glyph.codePointAt(0)))
                result.add(new StudioTimedGlyphOverlay(title, group, i, glyph));
        }
        return result;
    }

    private StudioTimedGlyphOverlay(StudioLayer layer, StudioLayer group, int index, String glyph) {
        layer.validate();
        this.index = index;
        wholeText = layer.text;
        durationMs = layer.durationMs;
        glyphProgram = StudioGlyphAnimation.compile(layer.glyphAnimation, layer.text, durationMs);
        titleMotion = StudioLayerMotion.fromLayer(layer);
        parent = group == null ? null : new StudioParentGroupTransform(group);
        startUs = layer.startMs * 1000L;
        endUs = (layer.startMs + layer.durationMs) * 1000L;
        anchorY = layer.position == 0 ? .72f : (layer.position == 2 ? -.71f : 0f);

        content = new SpannableString(glyph);
        content.setSpan(new ForegroundColorSpan(layer.color), 0, content.length(), 0);
        int style = "bold_italic".equals(layer.fontStyle) ? Typeface.BOLD_ITALIC :
                "italic".equals(layer.fontStyle) ? Typeface.ITALIC :
                "bold".equals(layer.fontStyle) ? Typeface.BOLD : Typeface.NORMAL;
        content.setSpan(new StyleSpan(style), 0, content.length(), 0);
        content.setSpan(new TypefaceSpan(layer.fontFamily), 0, content.length(), 0);
        content.setSpan(new AbsoluteSizeSpan(layer.fontSize, true), 0, content.length(), 0);
    }

    @Override public SpannableString getText(long presentationTimeUs) {
        return content;
    }

    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
        boolean active = StudioTimedTitleOverlay.activeAt(presentationTimeUs, startUs, endUs);
        long localUs = active ? presentationTimeUs - startUs : 0;
        long localMs = localUs / 1000L;
        StudioGlyphAnimation.Frame glyph = StudioGlyphAnimation.sample(glyphProgram, index, localMs);
        StudioClipKeyframes.Values title = titleMotion.atUs(localUs);
        float x = glyph.x + (float)(2 * title.x);
        float y = anchorY + glyph.y - (float)(2 * title.y);
        float sx = glyph.scale * (float)title.scaleX;
        float sy = glyph.scale * (float)title.scaleY;
        float rotation = glyph.rotation + (float)title.rotate;
        float alpha = active ? glyph.opacity * (float)title.opacity : 0f;
        OverlaySettings own = new GlyphSettings(x, y, sx, sy, rotation, alpha);
        return parent == null ? own : parent.apply(own, presentationTimeUs);
    }

    private static final class GlyphSettings implements OverlaySettings {
        private final Pair<Float,Float> background, scale;
        private final float rotation, alpha;
        GlyphSettings(float x, float y, float sx, float sy, float rotation, float alpha) {
            background = Pair.create(x, y);
            scale = Pair.create(sx, sy);
            this.rotation = rotation;
            this.alpha = Math.max(0f, Math.min(1f, alpha));
        }
        @Override public Pair<Float,Float> getBackgroundFrameAnchor() { return background; }
        @Override public Pair<Float,Float> getOverlayFrameAnchor() {
            return OverlaySettings.DEFAULT_OVERLAY_FRAME_ANCHOR;
        }
        @Override public Pair<Float,Float> getScale() { return scale; }
        @Override public float getRotationDegrees() { return rotation; }
        @Override public float getAlphaScale() { return alpha; }
    }
}
