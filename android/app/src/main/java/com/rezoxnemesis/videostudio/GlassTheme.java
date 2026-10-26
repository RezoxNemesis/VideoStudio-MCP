package com.rezoxnemesis.videostudio;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * Shared glass palette for VideoStudio screens. Surfaces are translucent so the backdrop
 * gradient shows through, which gives the frosted-glass look without blur effects that
 * are costly on low-end devices.
 */
final class GlassTheme {
    static final int BACKDROP_TOP = Color.rgb(12, 20, 42);
    static final int BACKDROP_MID = Color.rgb(6, 9, 20);
    static final int BACKDROP_BOTTOM = Color.rgb(18, 9, 32);

    /** Card surface: translucent so the backdrop shows through. */
    static final int GLASS_FILL = Color.argb(150, 22, 32, 58);
    /** Raised surface used for chips, buttons and selected rows. */
    static final int GLASS_FILL_STRONG = Color.argb(190, 30, 44, 76);
    /** Hairline highlight around glass surfaces. */
    static final int GLASS_STROKE = Color.argb(70, 255, 255, 255);

    private GlassTheme() {}

    /** Full-screen backdrop: a soft vertical gradient from navy through to deep violet. */
    static GradientDrawable backdrop() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { BACKDROP_TOP, BACKDROP_MID, BACKDROP_BOTTOM });
    }
}
