package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Runtime capability registry.
 *
 * VideoStudio features depend on capabilities rather than hard-coded model
 * names. Built-in Android engines and optional installed model packs expose the
 * same provider shape so stronger future providers can replace individual
 * stages without replacing the editor or MCP architecture.
 */
public final class CapabilityRegistry {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private final File installedModelRoot;
    private final boolean systemTtsAvailable;

    public CapabilityRegistry(Context context) {
        File workspace = new File(context.getApplicationContext().getFilesDir(), "creative_workspace");
        installedModelRoot = new File(new File(workspace, "models"), "installed");
        if (!installedModelRoot.exists()) installedModelRoot.mkdirs();
        boolean tts = false;
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            tts = !context.getPackageManager().queryIntentServices(intent, 0).isEmpty();
        } catch (Exception ignored) {}
        systemTtsAvailable = tts;
    }

    public JSONObject describe() {
        JSONObject root = new JSONObject();
        JSONArray providers = new JSONArray();

        providers.put(builtin(
                "builtin.mlkit.person-segmentation",
                "vision",
                "android-mlkit",
                new String[]{"person.segmentation"},
                220,
                "balanced"
        ));
        providers.put(builtin(
                "builtin.mlkit.face-mesh",
                "vision",
                "android-mlkit",
                new String[]{"face.landmarks"},
                180,
                "balanced"
        ));
        providers.put(builtin(
                "builtin.videostudio.articulated-parallax",
                "animation",
                "android-gpu-media3",
                new String[]{"portrait.rig","motion.2_5d","environment.procedural","depth.estimate"},
                420,
                "balanced"
        ));
        providers.put(builtin(
                "builtin.videostudio.motionscript",
                "compiler",
                "native-java",
                new String[]{"scene.compile","creative_ir"},
                32,
                "deterministic"
        ));
        providers.put(builtin(
                "builtin.videostudio.media3",
                "render",
                "android-media3",
                new String[]{"render.video","encode.h264","mux.aac"},
                256,
                "final"
        ));
        providers.put(builtin(
                "builtin.videostudio.compositor",
                "render",
                "android-media3-gpu",
                new String[]{"render.compositor"},
                320,
                "balanced"
        ));
        providers.put(builtin(
                "builtin.videostudio.critique",
                "quality",
                "native-analysis",
                new String[]{"render.critique"},
                96,
                "balanced"
        ));
        if (systemTtsAvailable) {
            providers.put(builtin(
                    "builtin.android.system-tts",
                    "audio",
                    "android-tts",
                    new String[]{"speech.tts","voice.narration"},
                    64,
                    "balanced"
            ));
        }

        providers.put(builtin("builtin.videostudio.procedural-scene", "generation", "android-canvas-media3",
                new String[]{"image.generate.procedural", "animation.2d.procedural", "render.3d.procedural"}, 64, "procedural"));
        int builtInCount = providers.length();
        JSONArray installed = installedProviders();
        for (int i = 0; i < installed.length(); i++) providers.put(installed.opt(i));

        try {
            root.put("registryVersion", 1);
            root.put("providerCount", providers.length());
            root.put("builtInProviderCount", builtInCount);
            root.put("systemTtsAvailable", systemTtsAvailable);
            root.put("installedModelProviderCount", installed.length());
            root.put("providers", providers);
            root.put("modelPackRoot", installedModelRoot.getAbsolutePath());
            root.put("selectionPolicy", "capability-first-hardware-aware");
            root.put("galleryAccess", false);
        } catch (Exception ignored) {}
        return root;
    }

    public JSONObject resolve(String capability, String qualityPreference) {
        String wanted = normalize(capability);
        String quality = normalize(qualityPreference);
        JSONObject catalog = describe();
        JSONArray providers = catalog.optJSONArray("providers");
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;

        if (providers != null) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject provider = providers.optJSONObject(i);
                if (provider == null || !supports(provider, wanted)) continue;
                if (!provider.optBoolean("enabled", true)) continue;
                if (!provider.optBoolean("runtimeAvailable", provider.optBoolean("builtIn", false))) continue;

                int score = provider.optBoolean("installed", false) ? 50 : 20;
                score += Math.max(-40, Math.min(80, provider.optInt("priority", provider.optBoolean("builtIn", false) ? 10 : 30)));
                if (!quality.isEmpty() && quality.equals(normalize(provider.optString("quality")))) score += 20;
                if (provider.optBoolean("builtIn", false)) score += 4;
                score -= Math.max(0, provider.optInt("estimatedRamMb", 0) / 256);
                if (score > bestScore) {
                    bestScore = score;
                    best = provider;
                }
            }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("capability", wanted);
            out.put("resolved", best != null);
            if (best != null) out.put("provider", best);
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject modelPackStatus() {
        JSONObject out = new JSONObject();
        JSONArray installed = installedProviders();
        long bytes = 0;
        File[] dirs = installedModelRoot.listFiles();
        if (dirs != null) for (File dir : dirs) bytes += sizeOf(dir);
        try {
            out.put("installedProviderCount", installed.length());
            out.put("installedBytes", bytes);
            out.put("providers", installed);
            out.put("root", installedModelRoot.getAbsolutePath());
            out.put("baseApkDependency", false);
            out.put("transactionalInstallerReady", true);
            out.put("installSource", "explicit-videostudio-asset");
            out.put("galleryAccess", false);
        } catch (Exception ignored) {}
        return out;
    }

    private JSONArray installedProviders() {
        JSONArray out = new JSONArray();
        File[] packs = installedModelRoot.listFiles();
        if (packs == null) return out;

        for (File pack : packs) {
            if (!pack.isDirectory()) continue;
            File manifest = new File(pack, "manifest.json");
            if (!manifest.isFile() || manifest.length() <= 0 || manifest.length() > MAX_MANIFEST_BYTES) continue;
            try {
                JSONObject parsed = new JSONObject(readSmallFile(manifest));
                JSONArray capabilities = parsed.optJSONArray("capabilities");
                String id = normalize(parsed.optString("id"));
                String version = parsed.optString("version", "").trim();
                if (id.isEmpty() || version.isEmpty() || capabilities == null || capabilities.length() == 0) continue;

                JSONObject provider = new JSONObject(parsed.toString());
                provider.put("builtIn", false);
                provider.put("installed", true);
                provider.put("enabled", parsed.optBoolean("enabled", true));
                provider.put("priority", Math.max(-40, Math.min(80, parsed.optInt("priority", 30))));
                provider.put("runtimeAvailable", installedRuntimeAvailable(parsed));
                provider.put("runtimeState", installedRuntimeAvailable(parsed)
                        ? "adapter-ready"
                        : "registered-awaiting-runtime-adapter");
                provider.put("packPath", pack.getAbsolutePath());
                provider.put("installedBytes", sizeOf(pack));
                if (!provider.has("backend")) provider.put("backend", "optional-model-runtime");
                if (!provider.has("quality")) provider.put("quality", "balanced");
                if (!provider.has("estimatedRamMb")) provider.put("estimatedRamMb", 1024);
                out.put(provider);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static JSONObject builtin(String id,
                                      String category,
                                      String backend,
                                      String[] capabilities,
                                      int ramMb,
                                      String quality) {
        JSONObject provider = new JSONObject();
        JSONArray caps = new JSONArray();
        for (String capability : capabilities) caps.put(capability);
        try {
            provider.put("id", id);
            provider.put("version", AppProtocol.APP_VERSION);
            provider.put("category", category);
            provider.put("backend", backend);
            provider.put("capabilities", caps);
            provider.put("estimatedRamMb", ramMb);
            provider.put("quality", quality);
            provider.put("builtIn", true);
            provider.put("installed", true);
            provider.put("enabled", true);
            provider.put("priority", 10);
            provider.put("runtimeAvailable", true);
            provider.put("runtimeState", "ready");
        } catch (Exception ignored) {}
        return provider;
    }

    private static boolean installedRuntimeAvailable(JSONObject manifest) {
        // Optional model packs are deliberately registered before they are
        // executable. A backend becomes runtimeAvailable only when the APK has
        // an audited adapter for that backend. This prevents a manifest from
        // claiming executable capability that VideoStudio cannot actually run.
        return false;
    }

    private static boolean supports(JSONObject provider, String capability) {
        JSONArray caps = provider.optJSONArray("capabilities");
        if (caps == null) return false;
        for (int i = 0; i < caps.length(); i++) {
            if (capability.equals(normalize(caps.optString(i)))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "_");
    }

    private static String readSmallFile(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != buffer.length) throw new IllegalStateException("Could not read complete model manifest");
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }
}

