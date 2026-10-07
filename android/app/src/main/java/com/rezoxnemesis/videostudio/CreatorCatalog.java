package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CreatorCatalog {
    public static final List<String> TRANSITIONS = Arrays.asList(
            "none","cut","fade","dip_black","dip_white","slide_left","slide_right","slide_up","slide_down",
            "push_left","push_right","zoom_in","zoom_out","whip_left","whip_right","spin","blur",
            "flash","glitch","rgb_split","light_leak","film_burn","luma_wipe","mask_wipe","camera_shutter"
    );

    public static final List<String> MOTIONS = Arrays.asList(
            "none","push_in","pull_out","pan_left","pan_right","pan_up","pan_down","drift","orbit",
            "handheld","micro_shake","impact_shake","bounce","elastic_pop","float","parallax","ken_burns",
            "snap_zoom","zoom_punch","rack_focus_sim","tilt","roll","hero_reveal"
    );

    public static final List<String> EFFECTS = Arrays.asList(
            "none","cinematic","film_grain","soft_glow","bloom","dream","vignette","sharpen","clarity",
            "motion_blur","radial_blur","gaussian_blur","chromatic_aberration","rgb_split","glitch",
            "scanlines","vhs","retro_cam","super8","film_burn","light_leak","halation","neon","cyberpunk",
            "noir","bleach_bypass","teal_orange","warm_film","cool_night","golden_hour","matte",
            "high_contrast","soft_portrait","crush_black","fade_black","duotone","posterize","pixelate",
            "fisheye","shake","strobe","flash","edge_glow"
    );

    public static final List<String> TEXT_ANIMATIONS = Arrays.asList(
            "none","fade","fade_up","fade_down","slide_left","slide_right","scale_in","pop","bounce",
            "typewriter","word_reveal","line_reveal","blur_in","tracking_in","tracking_out","glitch",
            "neon_flicker","kinetic","mask_reveal","cinematic_title","caption_pop"
    );

    public static final List<String> FONTS = Arrays.asList(
            "sans-serif","sans-serif-medium","sans-serif-condensed","sans-serif-light","sans-serif-black",
            "serif","serif-monospace","monospace","cursive","casual","elegant","poster","tech","editorial"
    );

    public static final List<String> AI_TOOLS = Arrays.asList(
            "auto_cut","scene_detect","silence_trim","highlight_extract","smart_reframe","caption_plan",
            "hook_builder","beat_sync","b_roll_plan","pace_rewrite","shorts_recut","story_recut",
            "colour_match","audio_ducking","title_writer","thumbnail_frame_pick","render_critique",
            "prompt_video","multi_variant_edit","platform_adapt","continuity_check"
    );

    private static final Map<String, JSONObject> EFFECT_PRESETS = new LinkedHashMap<>();

    static {
        preset("cinematic", 0.03, 0.10, 8, -3, 0.0, 0.12, 0.0);
        preset("teal_orange", 0.02, 0.12, 14, 2, 0.0, 0.10, 0.0);
        preset("warm_film", 0.04, 0.04, 7, 6, 0.0, 0.18, 0.0);
        preset("cool_night", -0.04, 0.13, -8, -8, 0.0, 0.16, 0.0);
        preset("noir", -0.02, 0.28, -100, 0, 0.0, 0.24, 0.0);
        preset("high_contrast", 0.01, 0.28, 4, 0, 0.0, 0.08, 0.0);
        preset("soft_portrait", 0.05, -0.04, -5, 5, 0.05, 0.06, 0.0);
        preset("matte", 0.05, -0.08, -8, 3, 0.0, 0.08, 0.0);
        preset("golden_hour", 0.07, 0.05, 12, 7, 0.0, 0.10, 0.0);
        preset("cyberpunk", 0.02, 0.20, 24, -2, 0.0, 0.08, 0.12);
    }

    private static void preset(String name, double brightness, double contrast, double saturation,
                               double lightness, double blur, double vignette, double rgbSplit) {
        JSONObject o = new JSONObject();
        try {
            o.put("brightness", brightness);
            o.put("contrast", contrast);
            o.put("saturationAdjust", saturation);
            o.put("lightnessAdjust", lightness);
            o.put("blur", blur);
            o.put("vignette", vignette);
            o.put("rgbSplit", rgbSplit);
        } catch (Exception ignored) {}
        EFFECT_PRESETS.put(name, o);
    }

    public static JSONObject effectPreset(String name) {
        JSONObject src = EFFECT_PRESETS.get(name);
        if (src == null) src = EFFECT_PRESETS.get("cinematic");
        try { return new JSONObject(src.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }

    public static JSONObject describe() {
        JSONObject o = new JSONObject();
        try {
            o.put("transitions", new JSONArray(TRANSITIONS));
            o.put("motions", new JSONArray(MOTIONS));
            o.put("effects", new JSONArray(EFFECTS));
            o.put("textAnimations", new JSONArray(TEXT_ANIMATIONS));
            o.put("fonts", new JSONArray(FONTS));
            o.put("aiTools", new JSONArray(AI_TOOLS));
        } catch (Exception ignored) {}
        return o;
    }

    public static String next(List<String> values, String current) {
        int i = values.indexOf(current);
        return values.get((i + 1 + values.size()) % values.size());
    }

    private CreatorCatalog() {}
}
