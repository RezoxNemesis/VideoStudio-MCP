package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
        parameters=new JSONObject(parameters.toString());
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
        String generationId=UUID.randomUUID().toString();
        File parent = new File(new CreativeWorkspace(context).projectRoot(project.id), "generated/procedural_video");
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("Could not create prompt-video directory");
        File dir=new File(parent,generationId);
        if(!dir.mkdir())throw new IllegalStateException("Could not create an immutable prompt-video generation");

        ArrayList<ProjectStore.Asset> generatedAssets = new ArrayList<>();
        ArrayList<ProjectStore.Clip> generatedClips = new ArrayList<>();
        long defaultDuration = Math.max(1800, (totalSeconds * 1000L) / Math.max(1, maxScenes));

        boolean filesCompleted=false;
        try {
        for (int i = 0; i < maxScenes; i++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Prompt-video generation cancelled");
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
            JSONObject graph = scene.optJSONObject("sceneGraph");
            if (graph == null) graph = parameters.optJSONObject("sceneGraph");
            if (graph == null) graph = LocalSceneDirector.fromPrompt(scene.optString("prompt", text), i);
            ProceduralScene.validate(graph);
            renderProceduralImage(png, width, height, graph);

            ProjectStore.Asset asset = new ProjectStore.Asset();
            asset.id = UUID.randomUUID().toString();
            asset.uri = Uri.fromFile(png).toString();
            asset.name = "Procedural Scene " + (i + 1);
            asset.mime = "image/png";
            asset.width = width;
            asset.height = height;
            asset.sizeBytes = png.length();
            asset.seekable = true;
            asset.hasAudio = false;
            asset.generated = true;
            asset.role = "generated_image";
            asset.generationMetadata.put("provider", "builtin.videostudio.procedural-scene");
            asset.generationMetadata.put("prompt", prompt);
            asset.generationMetadata.put("sceneGraph", graph);
            asset.generationMetadata.put("generationId",generationId);
            asset.generationMetadata.put("workspaceRelativePath","generated/procedural_video/"+generationId+"/"+png.getName());
            asset.durationMs = durationMs;
            ProjectStore.Asset existing = null;
            for (ProjectStore.Asset candidate : project.assets) if (asset.uri.equals(candidate.uri)) { existing = candidate; break; }
            if (existing != null) {
                existing.generationMetadata = asset.generationMetadata;
                existing.generated = true; existing.role = "generated_image"; asset = existing;
            } else generatedAssets.add(asset);

            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = asset.id;
            clip.inMs = 0;
            clip.outMs = durationMs;
            clip.speed = 1f;
            clip.volume = 1f;
            clip.transition = transition;
            clip.title = scene.optBoolean("showTitle", false) ? title : "";
            clip.effects.put("motionPreset", "none");
            clip.effects.put("proceduralScene", graph);
            clip.effects.put("textAnimation", scene.optString("textAnimation", defaultTextAnimation(i)));
            clip.effects.put("fontFamily", sceneFont);
            clip.effects.put("effectPreset", scene.optString("effect", styleToEffect(sceneStyle)));
            clip.effects.put("generatedFromPrompt", true);
            clip.effects.put("promptSceneIndex", i);
            java.util.List<String> unsupported=NativeVideoEffects.unsupported(clip);
            if(!unsupported.isEmpty())throw new IllegalArgumentException("Unsupported procedural scene effects: "+String.join(", ",unsupported));
            generatedClips.add(clip);
        }
        filesCompleted=true;
        project.assets.addAll(generatedAssets);
        project.clips.clear();
        project.clips.addAll(generatedClips);
        store.save(project);
        return new BuildResult(project, aspect, quality, generatedClips.size());
        }finally{
            // Completed generations can have reached a project commit or recovery
            // record; only this attempt's incomplete files are disposable.
            if(!filesCompleted){for(int i=0;i<maxScenes;i++)new File(dir,String.format(Locale.US,"scene_%02d.png",i+1)).delete();dir.delete();}
        }
    }

    public static void renderProceduralImage(File file, int width, int height, JSONObject graph) throws Exception {
        ProceduralScene scene = new ProceduralScene(graph);
        Bitmap bitmap=null;boolean created=false,completed=false;
        try {
            if(!file.createNewFile())throw new IllegalStateException("Generated image already exists; use a new immutable generation instead of overwriting it");
            created=true;bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            scene.draw(new Canvas(bitmap), 0, 1);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Could not save generated image");
                out.flush();out.getFD().sync();
            }
            completed=true;
        } finally { if(bitmap!=null)bitmap.recycle();if(created&&!completed)file.delete(); }
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
        String[] values = {"fade","zoom_in","slide_left","dip_white","whip_right","dip_black"};
        return values[i % values.length];
    }

    private String defaultTextAnimation(int i) {
        String[] values = {"cinematic_title","fade_up","word_reveal","scale_in","slide_left","line_reveal"};
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

    private String safeAspect(String aspect) {
        if ("16:9".equals(aspect) || "1:1".equals(aspect) || "4:5".equals(aspect)) return aspect;
        return "9:16";
    }
}

