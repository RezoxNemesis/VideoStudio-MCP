package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MotionScript v0.1 compiler.
 *
 * This deliberately compiles a constrained creative language instead of
 * executing arbitrary code. The output is a serializable CreativeIR document
 * that can survive app restarts and be executed by native VideoStudio engines.
 */
public final class MotionScriptCompiler {
    public static final String MOTION_SCRIPT_VERSION = "0.1";
    public static final String CREATIVE_IR_VERSION = "0.1";

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

        JSONArray assets = new JSONArray();
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
                    asset.put("alias", tokens.get(1));
                    asset.put("assetId", tokens.get(2));
                    if (project != null && project.asset(tokens.get(2)) == null) {
                        warnings.put("Line " + (lineNumber + 1) + ": assetId not currently present in project: " + tokens.get(2));
                    }
                    assets.put(asset);
                    break;
                }
                case "shot": {
                    require(tokens, 3, lineNumber, "shot <name> <start>..<end>");
                    currentShot = new JSONObject();
                    currentShot.put("name", tokens.get(1));
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
                    shots.put(currentShot);
                    break;
                }
                case "camera":
                case "motion": {
                    require(tokens, 2, lineNumber, command + " <preset> [strength]");
                    JSONObject target = currentShot == null ? defaults : currentShot;
                    target.put("motionPreset", safePreset(tokens.get(1)));
                    if (tokens.size() >= 3) target.put("motionStrength", boundedDouble(tokens.get(2), 0, 1, lineNumber, "motion strength"));
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
                case "render":
                    require(tokens, 2, lineNumber, "render <720p|1080p> [aspect]");
                    renderQuality = "720p".equalsIgnoreCase(tokens.get(1)) ? "720p" : "1080p";
                    if (tokens.size() >= 3) renderAspect = safeAspect(tokens.get(2));
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
        ir.put("shots", shots);

        JSONObject render = new JSONObject();
        render.put("quality", renderQuality);
        render.put("aspect", renderAspect);
        ir.put("render", render);
        ir.put("warnings", warnings);
        ir.put("safeRuntime", true);
        ir.put("arbitraryCodeExecution", false);

        return new CompileResult(name, ir);
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

    private static String safePreset(String raw) {
        String value = raw == null ? "none" : raw.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]+", "_");
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
