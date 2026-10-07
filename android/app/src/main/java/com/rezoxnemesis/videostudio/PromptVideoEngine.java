package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public final class PromptVideoEngine {
    public static final class BuildResult {
        public final ProjectStore.Project project;
        public final String aspect;
        public final String quality;
        public final int sceneCount;

        BuildResult(ProjectStore.Project project, String aspect, String quality, int sceneCount) {
            this.project = project;
            this.aspect = aspect;
            this.quality = quality;
            this.sceneCount = sceneCount;
        }
    }

    private final Context context;

    public PromptVideoEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public BuildResult build(ProjectStore store, ProjectStore.Project project, JSONObject parameters) throws Exception {
        String prompt = parameters.optString("prompt", "").trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("Prompt is required");

        String aspect = safeAspect(parameters.optString("aspect", "9:16"));
        String quality = "720p".equals(parameters.optString("quality")) ? "720p" : "1080p";
        String style = parameters.optString("style", "cinematic");
        String font = parameters.optString("font", "sans-serif-medium");
        int totalSeconds = Math.max(4, Math.min(120, parameters.optInt("durationSeconds", 18)));
        JSONArray plan = parameters.optJSONArray("scenes");
        if (plan == null || plan.length() == 0) plan = fallbackPlan(prompt, totalSeconds);

        int maxScenes = Math.min(20, plan.length());
        File dir = new File(context.getFilesDir(), "prompt_video/" + project.id);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create prompt-video directory");

        ArrayList<ProjectStore.Asset> generatedAssets = new ArrayList<>();
        ArrayList<ProjectStore.Clip> generatedClips = new ArrayList<>();
        long defaultDuration = Math.max(1800, (totalSeconds * 1000L) / Math.max(1, maxScenes));

        for (int i = 0; i < maxScenes; i++) {
            JSONObject scene = plan.optJSONObject(i);
            if (scene == null) scene = new JSONObject();
            String title = scene.optString("title", shortTitle(scene.optString("text", prompt), i));
            String text = scene.optString("text", scene.optString("caption", prompt));
            String sceneStyle = scene.optString("style", style);
            String motion = scene.optString("motion", defaultMotion(i));
            String transition = scene.optString("transition", defaultTransition(i));
            String sceneFont = scene.optString("font", font);
            long durationMs = Math.max(900, Math.min(15000, scene.optLong("durationMs", defaultDuration)));

            int width = "16:9".equals(aspect) ? 1280 : ("1:1".equals(aspect) ? 1080 : ("4:5".equals(aspect) ? 864 : 720));
            int height = "16:9".equals(aspect) ? 720 : ("1:1".equals(aspect) ? 1080 : ("4:5".equals(aspect) ? 1080 : 1280));
            File png = new File(dir, String.format(Locale.US, "scene_%02d.png", i + 1));
            renderScene(png, width, height, title, text, sceneStyle, sceneFont, i, maxScenes);

            ProjectStore.Asset asset = new ProjectStore.Asset();
            asset.id = UUID.randomUUID().toString();
            asset.uri = Uri.fromFile(png).toString();
            asset.name = "AI Scene " + (i + 1);
            asset.mime = "image/png";
            asset.durationMs = durationMs;
            generatedAssets.add(asset);

            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = asset.id;
            clip.inMs = 0;
            clip.outMs = durationMs;
            clip.speed = 1f;
            clip.volume = 1f;
            clip.transition = transition;
            clip.title = title;
            clip.effects.put("motionPreset", motion);
            clip.effects.put("textAnimation", scene.optString("textAnimation", defaultTextAnimation(i)));
            clip.effects.put("fontFamily", sceneFont);
            clip.effects.put("effectPreset", scene.optString("effect", styleToEffect(sceneStyle)));
            clip.effects.put("generatedFromPrompt", true);
            clip.effects.put("promptSceneIndex", i);
            generatedClips.add(clip);
        }

        project.assets.addAll(generatedAssets);
        project.clips.clear();
        project.clips.addAll(generatedClips);
        store.save(project);
        return new BuildResult(project, aspect, quality, generatedClips.size());
    }

    private void renderScene(File file, int width, int height, String title, String text, String style, String font, int index, int count) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int[] colors = palette(style, index);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, width, height, colors[0], colors[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, bg);

        Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
        orb.setColor(colors[2]);
        orb.setAlpha(95);
        canvas.drawCircle(width * .83f, height * .18f, Math.min(width, height) * .27f, orb);
        orb.setAlpha(55);
        canvas.drawCircle(width * .16f, height * .78f, Math.min(width, height) * .34f, orb);

        Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        glass.setColor(Color.argb(95, 6, 9, 20));
        canvas.drawRoundRect(width * .065f, height * .50f, width * .935f, height * .86f, 34, 34, glass);

        Typeface titleFace = Typeface.create(resolveFont(font), Typeface.BOLD);
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTypeface(titleFace);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(Math.max(36, width * .065f));
        titlePaint.setLetterSpacing(.015f);

        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setTypeface(Typeface.create(resolveFont(font), Typeface.NORMAL));
        bodyPaint.setColor(Color.rgb(220, 230, 248));
        bodyPaint.setTextSize(Math.max(24, width * .035f));

        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setColor(Color.rgb(53, 230, 255));
        accent.setStrokeWidth(Math.max(5, width * .006f));
        canvas.drawLine(width * .07f, height * .46f, width * .31f, height * .46f, accent);

        float y = height * .23f;
        y = drawWrapped(canvas, titlePaint, title, width * .07f, y, width * .84f, titlePaint.getTextSize() * 1.12f, 3);
        drawWrapped(canvas, bodyPaint, text, width * .09f, Math.max(y + 34, height * .59f), width * .80f, bodyPaint.getTextSize() * 1.42f, 6);

        Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        chip.setColor(Color.argb(155, 16, 24, 45));
        canvas.drawRoundRect(width * .07f, height * .91f, width * .34f, height * .96f, 22, 22, chip);
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        small.setTextSize(Math.max(17, width * .022f));
        small.setColor(Color.rgb(180, 201, 236));
        canvas.drawText("AI SCENE  " + (index + 1) + "/" + count, width * .09f, height * .945f, small);

        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 94, out)) throw new IllegalStateException("Could not save generated scene");
        } finally {
            bitmap.recycle();
        }
    }

    private float drawWrapped(Canvas canvas, Paint paint, String value, float x, float y, float maxWidth, float lineHeight, int maxLines) {
        String[] words = (value == null ? "" : value.trim()).split("\\s+");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(test) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), x, y, paint);
                y += lineHeight;
                lines++;
                if (lines >= maxLines) return y;
                line.setLength(0);
                line.append(word);
            } else {
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
        }
        if (line.length() > 0 && lines < maxLines) {
            canvas.drawText(line.toString(), x, y, paint);
            y += lineHeight;
        }
        return y;
    }

    private JSONArray fallbackPlan(String prompt, int totalSeconds) {
        JSONArray arr = new JSONArray();
        String[] raw = prompt.split("(?<=[.!?])\\s+");
        int scenes = Math.max(3, Math.min(7, raw.length > 1 ? raw.length : 4));
        for (int i = 0; i < scenes; i++) {
            JSONObject s = new JSONObject();
            try {
                String part = raw.length > i ? raw[i] : prompt;
                s.put("title", i == 0 ? shortTitle(prompt, i) : shortTitle(part, i));
                s.put("text", part);
                s.put("durationMs", totalSeconds * 1000L / scenes);
                s.put("motion", defaultMotion(i));
                s.put("transition", defaultTransition(i));
                s.put("textAnimation", defaultTextAnimation(i));
            } catch (Exception ignored) {}
            arr.put(s);
        }
        return arr;
    }

    private String shortTitle(String text, int index) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.length() > 46) clean = clean.substring(0, 46).trim() + "…";
        if (clean.isEmpty()) clean = "Scene " + (index + 1);
        return clean;
    }

    private String defaultMotion(int i) {
        String[] values = {"push_in","pan_right","drift","pull_out","pan_left","hero_reveal"};
        return values[i % values.length];
    }

    private String defaultTransition(int i) {
        String[] values = {"fade","zoom_in","slide_left","flash","whip_right","dip_black"};
        return values[i % values.length];
    }

    private String defaultTextAnimation(int i) {
        String[] values = {"cinematic_title","fade_up","word_reveal","scale_in","tracking_in","mask_reveal"};
        return values[i % values.length];
    }

    private String styleToEffect(String style) {
        if (style == null) return "cinematic";
        String s = style.toLowerCase(Locale.US);
        if (s.contains("cyber") || s.contains("neon")) return "cyberpunk";
        if (s.contains("warm") || s.contains("film")) return "warm_film";
        if (s.contains("night") || s.contains("dark")) return "cool_night";
        if (s.contains("noir") || s.contains("mono")) return "noir";
        if (s.contains("gold")) return "golden_hour";
        return "cinematic";
    }

    private int[] palette(String style, int index) {
        String s = style == null ? "" : style.toLowerCase(Locale.US);
        if (s.contains("warm") || s.contains("gold")) return new int[]{Color.rgb(38, 14, 28), Color.rgb(142, 66, 32), Color.rgb(255, 184, 76)};
        if (s.contains("cyber") || s.contains("neon")) return new int[]{Color.rgb(4, 10, 30), Color.rgb(56, 14, 95), Color.rgb(30, 226, 255)};
        if (s.contains("clean") || s.contains("minimal")) return new int[]{Color.rgb(10, 17, 31), Color.rgb(31, 50, 75), Color.rgb(110, 172, 255)};
        if (s.contains("nature")) return new int[]{Color.rgb(6, 31, 31), Color.rgb(25, 75, 54), Color.rgb(70, 228, 172)};
        int phase = index % 3;
        if (phase == 1) return new int[]{Color.rgb(10, 8, 30), Color.rgb(45, 18, 82), Color.rgb(225, 64, 255)};
        if (phase == 2) return new int[]{Color.rgb(5, 18, 35), Color.rgb(16, 58, 91), Color.rgb(42, 216, 255)};
        return new int[]{Color.rgb(7, 10, 25), Color.rgb(32, 25, 85), Color.rgb(103, 89, 255)};
    }

    private String resolveFont(String font) {
        if (font == null) return "sans-serif-medium";
        switch (font) {
            case "serif":
            case "serif-monospace":
            case "monospace":
            case "cursive":
            case "sans-serif":
            case "sans-serif-medium":
            case "sans-serif-condensed":
            case "sans-serif-light":
            case "sans-serif-black":
                return font;
            case "editorial": return "serif";
            case "tech": return "monospace";
            case "elegant": return "serif";
            case "poster": return "sans-serif-black";
            case "casual": return "cursive";
            default: return "sans-serif-medium";
        }
    }

    private String safeAspect(String aspect) {
        if ("16:9".equals(aspect) || "1:1".equals(aspect) || "4:5".equals(aspect)) return aspect;
        return "9:16";
    }
}
