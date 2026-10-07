package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MotionScript v0.2 compiler.
 *
 * MotionScript is a constrained creative language, not arbitrary code. It
 * compiles into serializable CreativeIR so scenes can survive app restarts,
 * be inspected by ChatGPT, and be executed by interchangeable native/local
 * providers.
 */
public final class MotionScriptCompiler {
    public static final String MOTION_SCRIPT_VERSION = "0.2";
    public static final String CREATIVE_IR_VERSION = "0.2";

    public static final class CompileResult {
        public final String name;
        public final JSONObject ir;

        CompileResult(String name, JSONObject ir) {
            this.name = name;
            this.ir = ir;
        }
    }

    public CompileResult compile(String source, ProjectStore.Project project) throws Exception {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("MotionScript source is required");
        }

        JSONObject ir = new JSONObject();
        ir.put("motionScriptVersion", MOTION_SCRIPT_VERSION);
        ir.put("creativeIrVersion", CREATIVE_IR_VERSION);
        ir.put("projectId", project == null ? "" : project.id);

        String name = "scene";
        int fps = 30;
        long durationMs = project == null ? 0 : project.outputDurationMs();
        int canvasWidth = 1080;
        int canvasHeight = 1920;
        String renderQuality = "1080p";
        String renderAspect = "9:16";

        JSONObject defaults = new JSONObject();
        defaults.put("motionPreset", "none");
        defaults.put("motionStrength", 0.35);
        defaults.put("effectPreset", "none");
        defaults.put("atmosphere", "ambient");
        defaults.put("atmosphereIntensity", 0.0);

        JSONObject render = new JSONObject();
        render.put("quality", renderQuality);
        render.put("aspect", renderAspect);
        render.put("interpolation", "adaptive");
        render.put("upscale", "auto");

        JSONObject critique = new JSONObject();
        critique.put("enabled", true);
        critique.put("mode", "continuity");

        JSONArray assets = new JSONArray();
        JSONArray subjects = new JSONArray();
        JSONArray lights = new JSONArray();
        JSONArray audio = new JSONArray();
        JSONArray generations = new JSONArray();
        JSONArray providerRequirements = new JSONArray();
        JSONArray shots = new JSONArray();
        JSONArray warnings = new JSONArray();
        JSONObject currentShot = null;

        String[] lines = source.replace("\r", "").split("\n");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String raw = stripComment(lines[lineNumber]).trim();
            if (raw.isEmpty()) continue;

            if (raw.equals("{")) continue;
            if (raw.startsWith("}")) {
                currentShot = null;
                continue;
            }

            boolean opensBlock = raw.endsWith("{");
            if (opensBlock) raw = raw.substring(0, raw.length() - 1).trim();

            List<String> tokens = tokenize(raw);
            if (tokens.isEmpty()) continue;
            String command = tokens.get(0).toLowerCase(Locale.US);

            switch (command) {
                case "video":
                    require(tokens, 2, lineNumber, "video <name>");
                    name = tokens.get(1);
                    break;

                case "canvas": {
                    require(tokens, 2, lineNumber, "canvas <width>x<height>");
                    String[] dims = tokens.get(1).toLowerCase(Locale.US).split("x");
                    if (dims.length != 2) throw syntax(lineNumber, "Canvas must be WIDTHxHEIGHT");
                    canvasWidth = boundedInt(dims[0], 64, 8192, lineNumber, "canvas width");
                    canvasHeight = boundedInt(dims[1], 64, 8192, lineNumber, "canvas height");
                    renderAspect = inferAspect(canvasWidth, canvasHeight);
                    render.put("aspect", renderAspect);
                    break;
                }

                case "fps":
                    require(tokens, 2, lineNumber, "fps <12..120>");
                    fps = boundedInt(tokens.get(1), 12, 120, lineNumber, "fps");
                    break;

                case "duration":
                    require(tokens, 2, lineNumber, "duration <seconds>");
                    durationMs = parseTimeMs(tokens.get(1), lineNumber);
                    break;

                case "asset": {
                    require(tokens, 3, lineNumber, "asset <alias> <assetId>");
                    JSONObject asset = new JSONObject();
                    asset.put("alias", safeAlias(tokens.get(1), lineNumber));
                    asset.put("assetId", tokens.get(2));
                    if (project != null && project.asset(tokens.get(2)) == null) {
                        warnings.put("Line " + (lineNumber + 1) + ": assetId not currently present in project: " + tokens.get(2));
                    }
                    assets.put(asset);
                    break;
                }

                case "subject": {
                    require(tokens, 3, lineNumber, "subject <alias> <assetId>");
                    String alias = safeAlias(tokens.get(1), lineNumber);
                    if (findByAlias(subjects, alias) != null) throw syntax(lineNumber, "Duplicate subject alias: " + alias);
                    JSONObject subject = new JSONObject();
                    subject.put("alias", alias);
                    subject.put("assetId", tokens.get(2));
                    subject.put("preserveIdentity", 0.96);
                    subject.put("rig", "articulated_2_5d");
                    subject.put("hairMotion", 0.18);
                    subject.put("clothMotion", 0.16);
                    subject.put("depth", "auto");
                    subject.put("pose", "auto");
                    if (project != null && project.asset(tokens.get(2)) == null) {
                        warnings.put("Line " + (lineNumber + 1) + ": subject assetId not currently present in project: " + tokens.get(2));
                    }
                    subjects.put(subject);
                    addRequirement(providerRequirements, "person.segmentation", "balanced", alias);
                    addRequirement(providerRequirements, "face.landmarks", "balanced", alias);
                    break;
                }

                case "preserve_identity": {
                    require(tokens, 3, lineNumber, "preserve_identity <subject> <0..1>");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    subject.put("preserveIdentity", boundedDouble(tokens.get(2), 0, 1, lineNumber, "identity strength"));
                    break;
                }

                case "rig": {
                    require(tokens, 3, lineNumber, "rig <subject> <type>");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    String rig = safePreset(tokens.get(2));
                    subject.put("rig", rig);
                    if (rig.contains("3d") || rig.contains("mesh")) addRequirement(providerRequirements, "mesh.skinning", "balanced", subject.optString("alias"));
                    else addRequirement(providerRequirements, "portrait.rig", "balanced", subject.optString("alias"));
                    break;
                }

                case "depth": {
                    require(tokens, 2, lineNumber, "depth <subject> [mode]");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    subject.put("depth", tokens.size() >= 3 ? safePreset(tokens.get(2)) : "auto");
                    addRequirement(providerRequirements, "monocular.depth", "balanced", subject.optString("alias"));
                    break;
                }

                case "pose": {
                    require(tokens, 2, lineNumber, "pose <subject> [mode]");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    subject.put("pose", tokens.size() >= 3 ? safePreset(tokens.get(2)) : "auto");
                    addRequirement(providerRequirements, "body.pose", "balanced", subject.optString("alias"));
                    break;
                }

                case "hands": {
                    require(tokens, 2, lineNumber, "hands <subject> [mode]");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    subject.put("hands", tokens.size() >= 3 ? safePreset(tokens.get(2)) : "auto");
                    addRequirement(providerRequirements, "hand.landmarks", "balanced", subject.optString("alias"));
                    break;
                }

                case "hair": {
                    require(tokens, 3, lineNumber, "hair <subject> <0..1>");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    subject.put("hairMotion", boundedDouble(tokens.get(2), 0, 1, lineNumber, "hair motion"));
                    break;
                }

                case "cloth": {
                    require(tokens, 3, lineNumber, "cloth <subject> <0..1>");
                    JSONObject subject = requireSubject(subjects, tokens.get(1), lineNumber);
                    subject.put("clothMotion", boundedDouble(tokens.get(2), 0, 1, lineNumber, "cloth motion"));
                    break;
                }

                case "shot": {
                    require(tokens, 3, lineNumber, "shot <name> <start>..<end>");
                    currentShot = new JSONObject();
                    currentShot.put("name", safeAlias(tokens.get(1), lineNumber));
                    String range = tokens.get(2);
                    String[] parts = range.split("\\.\\.");
                    if (parts.length != 2) throw syntax(lineNumber, "Shot range must be start..end");
                    long startMs = parseTimeMs(parts[0], lineNumber);
                    long endMs = parseTimeMs(parts[1], lineNumber);
                    if (endMs <= startMs) throw syntax(lineNumber, "Shot end must be after start");
                    currentShot.put("startMs", startMs);
                    currentShot.put("endMs", endMs);
                    currentShot.put("motionPreset", defaults.optString("motionPreset", "none"));
                    currentShot.put("motionStrength", defaults.optDouble("motionStrength", .35));
                    currentShot.put("effectPreset", defaults.optString("effectPreset", "none"));
                    currentShot.put("atmosphere", defaults.optString("atmosphere", "ambient"));
                    currentShot.put("atmosphereIntensity", defaults.optDouble("atmosphereIntensity", 0));
                    currentShot.put("camera", new JSONObject());
                    shots.put(currentShot);
                    break;
                }

                case "camera":
                case "motion": {
                    require(tokens, 2, lineNumber, command + " <preset> [strength]");
                    JSONObject target = currentShot == null ? defaults : currentShot;
                    target.put("motionPreset", safePreset(tokens.get(1)));
                    if (tokens.size() >= 3) target.put("motionStrength", boundedDouble(tokens.get(2), 0, 1, lineNumber, "motion strength"));
                    if (currentShot != null && "camera".equals(command)) {
                        JSONObject camera = currentShot.optJSONObject("camera");
                        if (camera == null) camera = new JSONObject();
                        camera.put("preset", safePreset(tokens.get(1)));
                        if (tokens.size() >= 3) camera.put("strength", boundedDouble(tokens.get(2), 0, 1, lineNumber, "camera strength"));
                        currentShot.put("camera", camera);
                    }
                    break;
                }

                case "lens": {
                    require(tokens, 2, lineNumber, "lens <mm>");
                    if (currentShot == null) throw syntax(lineNumber, "lens must appear inside a shot block");
                    JSONObject camera = currentShot.optJSONObject("camera");
                    if (camera == null) camera = new JSONObject();
                    camera.put("lensMm", boundedDouble(tokens.get(1).replace("mm", ""), 8, 400, lineNumber, "lens"));
                    currentShot.put("camera", camera);
                    break;
                }

                case "effect": {
                    require(tokens, 2, lineNumber, "effect <preset>");
                    JSONObject target = currentShot == null ? defaults : currentShot;
                    target.put("effectPreset", safePreset(tokens.get(1)));
                    break;
                }

                case "atmosphere": {
                    require(tokens, 2, lineNumber, "atmosphere <type> [intensity]");
                    JSONObject target = currentShot == null ? defaults : currentShot;
                    target.put("atmosphere", safePreset(tokens.get(1)));
                    if (tokens.size() >= 3) target.put("atmosphereIntensity", boundedDouble(tokens.get(2), 0, 1, lineNumber, "atmosphere intensity"));
                    break;
                }

                case "light": {
                    require(tokens, 4, lineNumber, "light <name> <type> <0..4>");
                    JSONObject light = new JSONObject();
                    light.put("name", safeAlias(tokens.get(1), lineNumber));
                    light.put("type", safePreset(tokens.get(2)));
                    light.put("intensity", boundedDouble(tokens.get(3), 0, 4, lineNumber, "light intensity"));
                    if (currentShot != null) light.put("shot", currentShot.optString("name"));
                    lights.put(light);
                    break;
                }

                case "narration": {
                    require(tokens, 2, lineNumber, "narration <text>");
                    JSONObject item = new JSONObject();
                    item.put("kind", "narration");
                    item.put("text", join(tokens, 1));
                    item.put("voice", "auto");
                    audio.put(item);
                    addRequirement(providerRequirements, "speech.tts", "balanced", "narration");
                    break;
                }

                case "voice": {
                    require(tokens, 3, lineNumber, "voice <target> <provider-or-voice-id>");
                    JSONObject item = new JSONObject();
                    item.put("kind", "voice");
                    item.put("target", safeAlias(tokens.get(1), lineNumber));
                    item.put("provider", safePreset(tokens.get(2)));
                    audio.put(item);
                    addRequirement(providerRequirements, "speech.tts", "balanced", tokens.get(1));
                    break;
                }

                case "generate": {
                    require(tokens, 4, lineNumber, "generate <alias> <capability> <prompt>");
                    JSONObject generation = new JSONObject();
                    generation.put("alias", safeAlias(tokens.get(1), lineNumber));
                    generation.put("capability", safeCapability(tokens.get(2), lineNumber));
                    generation.put("prompt", join(tokens, 3));
                    if (currentShot != null) generation.put("shot", currentShot.optString("name"));
                    generations.put(generation);
                    addRequirement(providerRequirements, generation.optString("capability"), "balanced", generation.optString("alias"));
                    break;
                }

                case "require": {
                    require(tokens, 2, lineNumber, "require <capability> [quality]");
                    String capability = safeCapability(tokens.get(1), lineNumber);
                    String quality = tokens.size() >= 3 ? safePreset(tokens.get(2)) : "balanced";
                    addRequirement(providerRequirements, capability, quality, "script");
                    break;
                }

                case "interpolate": {
                    require(tokens, 2, lineNumber, "interpolate <none|adaptive|provider>");
                    String mode = safePreset(tokens.get(1));
                    render.put("interpolation", mode);
                    if (!"none".equals(mode) && !"adaptive".equals(mode)) {
                        addRequirement(providerRequirements, "frame.interpolation", "final", "render");
                    }
                    break;
                }

                case "upscale": {
                    require(tokens, 2, lineNumber, "upscale <none|auto|provider>");
                    String mode = safePreset(tokens.get(1));
                    render.put("upscale", mode);
                    if (!"none".equals(mode) && !"auto".equals(mode)) {
                        addRequirement(providerRequirements, "super_resolution", "final", "render");
                    }
                    break;
                }

                case "critic": {
                    require(tokens, 2, lineNumber, "critic <off|continuity|strict>");
                    String mode = safePreset(tokens.get(1));
                    critique.put("enabled", !"off".equals(mode));
                    critique.put("mode", mode);
                    break;
                }

                case "render":
                    require(tokens, 2, lineNumber, "render <720p|1080p> [aspect]");
                    renderQuality = "720p".equalsIgnoreCase(tokens.get(1)) ? "720p" : "1080p";
                    render.put("quality", renderQuality);
                    if (tokens.size() >= 3) {
                        renderAspect = safeAspect(tokens.get(2));
                        render.put("aspect", renderAspect);
                    }
                    break;

                default:
                    throw syntax(lineNumber, "Unknown MotionScript command: " + command);
            }

            if (!opensBlock && "shot".equals(command)) currentShot = null;
        }

        JSONObject canvas = new JSONObject();
        canvas.put("width", canvasWidth);
        canvas.put("height", canvasHeight);
        canvas.put("fps", fps);

        ir.put("name", name);
        ir.put("canvas", canvas);
        ir.put("durationMs", Math.max(0, durationMs));
        ir.put("defaults", defaults);
        ir.put("assets", assets);
        ir.put("subjects", subjects);
        ir.put("shots", shots);
        ir.put("lights", lights);
        ir.put("audio", audio);
        ir.put("generations", generations);
        ir.put("providerRequirements", providerRequirements);
        ir.put("render", render);
        ir.put("critique", critique);
        ir.put("warnings", warnings);
        ir.put("safeRuntime", true);
        ir.put("arbitraryCodeExecution", false);

        JSONObject semantics = new JSONObject();
        semantics.put("identityFirst", true);
        semantics.put("editableSceneGraph", true);
        semantics.put("targetedRegeneration", true);
        semantics.put("providerInterchangeable", true);
        ir.put("semantics", semantics);

        return new CompileResult(name, ir);
    }

    private static JSONObject requireSubject(JSONArray subjects, String aliasRaw, int line) {
        String alias = safeAlias(aliasRaw, line);
        JSONObject subject = findByAlias(subjects, alias);
        if (subject == null) throw syntax(line, "Unknown subject: " + alias);
        return subject;
    }

    private static JSONObject findByAlias(JSONArray array, String alias) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && alias.equals(item.optString("alias"))) return item;
        }
        return null;
    }

    private static void addRequirement(JSONArray requirements,
                                       String capability,
                                       String quality,
                                       String target) {
        String safeCapability = capability == null ? "" : capability.trim().toLowerCase(Locale.US);
        if (safeCapability.isEmpty()) return;
        for (int i = 0; i < requirements.length(); i++) {
            JSONObject existing = requirements.optJSONObject(i);
            if (existing != null
                    && safeCapability.equals(existing.optString("capability"))
                    && String.valueOf(target).equals(existing.optString("target"))) {
                return;
            }
        }
        JSONObject requirement = new JSONObject();
        try {
            requirement.put("capability", safeCapability);
            requirement.put("quality", quality == null || quality.isEmpty() ? "balanced" : safePreset(quality));
            requirement.put("target", target == null ? "" : target);
        } catch (Exception ignored) {}
        requirements.put(requirement);
    }

    private static String stripComment(String value) {
        int hash = value.indexOf('#');
        int slashes = value.indexOf("//");
        int cut = -1;
        if (hash >= 0) cut = hash;
        if (slashes >= 0 && (cut < 0 || slashes < cut)) cut = slashes;
        return cut < 0 ? value : value.substring(0, cut);
    }

    private static List<String> tokenize(String line) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    out.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }
        if (token.length() > 0) out.add(token.toString());
        return out;
    }

    private static String join(List<String> tokens, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < tokens.size(); i++) {
            if (out.length() > 0) out.append(' ');
            out.append(tokens.get(i));
        }
        return out.toString();
    }

    private static void require(List<String> tokens, int size, int line, String usage) {
        if (tokens.size() < size) throw syntax(line, "Expected " + usage);
    }

    private static IllegalArgumentException syntax(int line, String message) {
        return new IllegalArgumentException("MotionScript line " + (line + 1) + ": " + message);
    }

    private static int boundedInt(String raw, int min, int max, int line, String label) {
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw syntax(line, label + " must be " + min + ".." + max);
            return value;
        } catch (NumberFormatException error) {
            throw syntax(line, label + " is not a number");
        }
    }

    private static double boundedDouble(String raw, double min, double max, int line, String label) {
        try {
            double value = Double.parseDouble(raw);
            if (value < min || value > max) throw syntax(line, label + " must be " + min + ".." + max);
            return value;
        } catch (NumberFormatException error) {
            throw syntax(line, label + " is not a number");
        }
    }

    private static long parseTimeMs(String raw, int line) {
        String value = raw.trim().toLowerCase(Locale.US);
        try {
            if (value.endsWith("ms")) return Math.max(0, Math.round(Double.parseDouble(value.substring(0, value.length() - 2))));
            if (value.endsWith("s")) return Math.max(0, Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1000d));
            return Math.max(0, Math.round(Double.parseDouble(value) * 1000d));
        } catch (NumberFormatException error) {
            throw syntax(line, "Invalid time: " + raw);
        }
    }

    private static String safeAlias(String raw, int line) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw syntax(line, "Invalid alias: " + value);
        }
        return value;
    }

    private static String safeCapability(String raw, int line) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (!value.matches("[a-z][a-z0-9._-]{1,95}")) {
            throw syntax(line, "Invalid capability: " + value);
        }
        return value;
    }

    private static String safePreset(String raw) {
        String value = raw == null ? "none" : raw.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9_.-]+", "_");
        return value.isEmpty() ? "none" : value;
    }

    private static String safeAspect(String raw) {
        if ("16:9".equals(raw) || "1:1".equals(raw) || "4:5".equals(raw)) return raw;
        return "9:16";
    }

    private static String inferAspect(int width, int height) {
        double ratio = height == 0 ? 1 : width / (double) height;
        if (Math.abs(ratio - 16d / 9d) < .08) return "16:9";
        if (Math.abs(ratio - 1d) < .08) return "1:1";
        if (Math.abs(ratio - 4d / 5d) < .08) return "4:5";
        return "9:16";
    }
}
