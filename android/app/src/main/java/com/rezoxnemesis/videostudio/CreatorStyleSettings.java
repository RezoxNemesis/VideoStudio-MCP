package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.List;

/** Presets with an executing preview/export provider, shared by owner and MCP. */
public final class CreatorStyleSettings {
    public static final List<String> COLOURS = Arrays.asList("none", "cinematic", "teal_orange", "warm_film", "cool_night", "noir", "high_contrast", "soft_portrait", "matte", "golden_hour", "cyberpunk");
    public static final List<String> EFFECTS;
    static {
        java.util.ArrayList<String> presets = new java.util.ArrayList<>(COLOURS);
        presets.addAll(Arrays.asList("gaussian_blur", "soft_glow", "dream"));
        EFFECTS = java.util.Collections.unmodifiableList(presets);
    }
    public static final List<String> MOTIONS = Arrays.asList("none", "push_in", "pull_out", "pan_left", "pan_right", "pan_up", "pan_down", "drift", "orbit", "handheld", "micro_shake", "impact_shake", "float", "ken_burns", "snap_zoom", "zoom_punch", "tilt", "roll", "hero_reveal");
    public static void validate(JSONObject settings) throws org.json.JSONException {
        if (settings.length() < 1) throw new IllegalArgumentException("Choose at least one creator style");
        java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = settings.get(key);
            List<String> choices;
            switch (key) {
                case "colorPreset": choices = COLOURS; break;
                case "motionPreset": choices = MOTIONS; break;
                case "fontFamily": choices = CreatorCatalog.FONTS; break;
                case "textAnimation": choices = CreatorCatalog.TEXT_ANIMATIONS; break;
                default: throw new IllegalArgumentException("Unknown creator style control: " + key);
            }
            if (!(raw instanceof String) || !choices.contains(raw)) throw new IllegalArgumentException("Unsupported " + key + " preset");
        }
    }
    private CreatorStyleSettings() {}
}
