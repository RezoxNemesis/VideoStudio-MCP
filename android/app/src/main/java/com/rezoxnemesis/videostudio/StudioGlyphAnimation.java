package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.Set;

/**
 * Deterministic per-codepoint title motion on the native Media3 output clock.
 * No raster frame copies, pseudo-random jitter, source changes or Gallery access.
 *
 * A bounded number of independently transformed glyph overlays is deliberately
 * used instead of allocating a bitmap for every output frame.
 */
public final class StudioGlyphAnimation {
    public static final int MAX_GLYPHS = 18;
    public static final int MAX_COMPOSITION_GLYPHS = 48;
    private static final Set<String> FIELDS = Set.of(
            "mode", "staggerMs", "enterMs", "exitMs", "spacing", "spread", "waveAmplitude");
    private static final Set<String> MODES = Set.of("assemble", "scatter", "cascade", "wave");

    private StudioGlyphAnimation() {}

    public static JSONObject preset(String mode) throws Exception {
        return validate(new JSONObject().put("mode", mode), "MOTION", 2200);
    }

    public static int glyphCount(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    public static String glyphAt(String text, int index) {
        int count = glyphCount(text);
        if (index < 0 || index >= count) throw new IllegalArgumentException("Glyph index out of bounds");
        int at = text.offsetByCodePoints(0, index);
        int end = text.offsetByCodePoints(at, 1);
        return text.substring(at, end);
    }

    public static int visibleGlyphCount(String text) {
        int visible = 0;
        for (int i = 0; i < glyphCount(text); i++) {
            String glyph = glyphAt(text, i);
            if (!Character.isWhitespace(glyph.codePointAt(0))) visible++;
        }
        return visible;
    }

    public static JSONObject validate(JSONObject program, String text, long durationMs) {
        if (program == null) throw new IllegalArgumentException("Glyph animation program required");
        java.util.Iterator<String> fields = program.keys();
        while (fields.hasNext()) {
            if (!FIELDS.contains(fields.next())) throw new IllegalArgumentException("Unknown glyph animation field");
        }
        Object rawMode = program.opt("mode");
        if (rawMode != null && !(rawMode instanceof String))
            throw new IllegalArgumentException("Glyph mode must be a string");
        String mode = program.optString("mode", "assemble");
        if (!MODES.contains(mode)) throw new IllegalArgumentException("Unsupported glyph motion mode");
        int count = glyphCount(text);
        if (count < 1 || count > MAX_GLYPHS || visibleGlyphCount(text) == 0)
            throw new IllegalArgumentException("Animated title must contain 1..18 codepoints and visible text");
        long stagger = numberInt(program, "staggerMs", 45, 0, 160);
        long enter = numberInt(program, "enterMs", 360, 80, 1600);
        long exit = numberInt(program, "exitMs", 260, 80, 1600);
        double spacing = number(program, "spacing", Math.min(.15, 1.4 / Math.max(1, count - 1)), .035, .28);
        number(program, "spread", .31, 0, .75);
        number(program, "waveAmplitude", .07, 0, .3);
        if (spacing * (count - 1) > 1.85)
            throw new IllegalArgumentException("Glyph tracking would extend beyond the output frame");
        if (durationMs < stagger * (count - 1) + enter + exit + 80)
            throw new IllegalArgumentException("Title duration is too short for the requested stagger and fades");
        try { return new JSONObject(program.toString()); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException("Could not copy glyph program", error); }
    }

    private static long numberInt(JSONObject o, String key, long fallback, long lo, long hi) {
        return o.has(key) ? StudioClipKeyframes.integer(o, key, lo, hi) : fallback;
    }

    private static double number(JSONObject o, String key, double fallback, double lo, double hi) {
        return StudioClipKeyframes.number(o, key, fallback, lo, hi);
    }

    public static final class Frame {
        public final float x, y, scale, rotation, opacity;
        Frame(double x, double y, double scale, double rotation, double opacity) {
            this.x = (float)x; this.y = (float)y; this.scale = (float)scale;
            this.rotation = (float)rotation; this.opacity = (float)opacity;
        }
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double ease(double value) {
        double p = clamp(value);
        return p * p * (3 - 2 * p);
    }

    /** Exact, repeatable local-time sample. X/Y use Media3's centered [-1,1] frame space. */
    public static Frame sample(JSONObject program, String text, int index, long durationMs, long localMs) {
        validate(program, text, durationMs);
        int count = glyphCount(text);
        if (index < 0 || index >= count) throw new IllegalArgumentException("Invalid glyph index");
        String mode = program.optString("mode", "assemble");
        long stagger = numberInt(program, "staggerMs", 45, 0, 160);
        long enter = numberInt(program, "enterMs", 360, 80, 1600);
        long exit = numberInt(program, "exitMs", 260, 80, 1600);
        double spacing = number(program, "spacing", Math.min(.15, 1.4 / Math.max(1, count - 1)), .035, .28);
        double spread = number(program, "spread", .31, 0, .75);
        double wave = number(program, "waveAmplitude", .07, 0, .3);
        double in = ease((localMs - (double)index * stagger) / enter);
        double out = ease((durationMs - (double)localMs - (double)(count - 1 - index) * stagger) / exit);
        double alpha = in * out;
        double center = index - (count - 1) / 2.0;
        double x = center * spacing, y = 0, rotation = 0;
        double direction = center < 0 ? -1 : (center > 0 ? 1 : (index % 2 == 0 ? -1 : 1));
        switch (mode) {
            case "scatter": {
                double theta = index * 2.399963229728653;
                x += Math.cos(theta) * spread * (1 - in) + direction * spread * .8 * (1 - out);
                y += Math.sin(theta) * spread * (1 - in) + Math.sin(theta + 1) * spread * (1 - out);
                rotation = (index % 2 == 0 ? -28 : 28) * (1 - in) + direction * 33 * (1 - out);
                break;
            }
            case "cascade":
                y -= (1 - in) * (.22 + index * .007);
                y += (1 - out) * .14;
                rotation = direction * 11 * (1 - in);
                break;
            case "wave":
                y -= .14 * (1 - in);
                y += wave * Math.sin(localMs * .011 + index * .85) * in * out;
                y += (1 - out) * .12;
                break;
            case "assemble":
            default:
                x += direction * spread * (1 - in);
                y += (index % 2 == 0 ? -.17 : .17) * (1 - in);
                x += direction * spread * .42 * (1 - out);
                rotation = direction * (1 - in) * 14;
                break;
        }
        double scale = (.58 + .42 * in) * (1 + .16 * (1 - out));
        if (localMs < 0 || localMs >= durationMs) alpha = 0;
        return new Frame(x, y, scale, rotation, alpha);
    }
}
