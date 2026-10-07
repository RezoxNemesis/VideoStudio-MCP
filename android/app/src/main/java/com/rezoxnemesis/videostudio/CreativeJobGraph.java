package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a durable execution DAG from CreativeIR.
 *
 * The graph is intentionally provider-agnostic: every node names a capability,
 * its dependencies, cache/checkpoint identity and a bounded resource plan.
 * Future local AI, 2D, 3D, audio and generative engines can therefore replace
 * one provider without changing MotionScript or the editor.
 */
public final class CreativeJobGraph {
    public static final int GRAPH_VERSION = 1;

    private final CapabilityRegistry registry;
    private final DeviceComputeProfile compute;

    public CreativeJobGraph(CapabilityRegistry registry, DeviceComputeProfile compute) {
        this.registry = registry;
        this.compute = compute;
    }

    public JSONObject build(JSONObject ir) {
        JSONObject graph = new JSONObject();
        JSONArray nodes = new JSONArray();
        JSONArray unresolved = new JSONArray();
        Set<String> unresolvedSet = new LinkedHashSet<>();

        JSONObject canvas = ir == null ? null : ir.optJSONObject("canvas");
        int width = canvas == null ? 1080 : Math.max(1, canvas.optInt("width", 1080));
        int height = canvas == null ? 1920 : Math.max(1, canvas.optInt("height", 1920));
        JSONObject render = ir == null ? null : ir.optJSONObject("render");
        String quality = render == null ? "balanced" : render.optString("quality", "balanced");

        ArrayList<String> globalAnalysisNodes = new ArrayList<>();
        JSONArray subjects = ir == null ? null : ir.optJSONArray("subjects");
        if (subjects != null) {
            for (int i = 0; i < subjects.length(); i++) {
                JSONObject subject = subjects.optJSONObject(i);
                if (subject == null) continue;
                String alias = safeId(subject.optString("alias", "subject_" + i));
                String assetId = subject.optString("assetId", "");

                String segmentation = addNode(nodes, unresolvedSet,
                        "subject." + alias + ".segmentation",
                        "analysis",
                        "person.segmentation",
                        "balanced",
                        new JSONArray(),
                        subjectInput(subject, assetId, "segmentation"),
                        width, height);
                globalAnalysisNodes.add(segmentation);

                String face = addNode(nodes, unresolvedSet,
                        "subject." + alias + ".face",
                        "analysis",
                        "face.landmarks",
                        "balanced",
                        arrayOf(segmentation),
                        subjectInput(subject, assetId, "face"),
                        width, height);
                globalAnalysisNodes.add(face);

                ArrayList<String> rigDeps = new ArrayList<>();
                rigDeps.add(segmentation);
                rigDeps.add(face);

                String depthMode = subject.optString("depth", "auto");
                if (!"none".equalsIgnoreCase(depthMode)) {
                    String depthCapability = ("monocular".equalsIgnoreCase(depthMode)
                            || "metric".equalsIgnoreCase(depthMode))
                            ? "monocular.depth" : "depth.estimate";
                    String depth = addNode(nodes, unresolvedSet,
                            "subject." + alias + ".depth",
                            "analysis",
                            depthCapability,
                            "balanced",
                            arrayOf(segmentation),
                            subjectInput(subject, assetId, "depth"),
                            width, height);
                    rigDeps.add(depth);
                    globalAnalysisNodes.add(depth);
                }

                String poseMode = subject.optString("pose", "auto");
                boolean poseAvailable = registry != null
                        && registry.resolve("body.pose", "balanced").optBoolean("resolved", false);
                if (!"none".equalsIgnoreCase(poseMode)
                        && (!"auto".equalsIgnoreCase(poseMode) || poseAvailable)) {
                    String pose = addNode(nodes, unresolvedSet,
                            "subject." + alias + ".pose",
                            "analysis",
                            "body.pose",
                            "balanced",
                            arrayOf(segmentation),
                            subjectInput(subject, assetId, "pose"),
                            width, height);
                    rigDeps.add(pose);
                    globalAnalysisNodes.add(pose);
                }

                String handsMode = subject.optString("hands", "none");
                if (!"none".equalsIgnoreCase(handsMode)) {
                    String hands = addNode(nodes, unresolvedSet,
                            "subject." + alias + ".hands",
                            "analysis",
                            "hand.landmarks",
                            "balanced",
                            arrayOf(segmentation),
                            subjectInput(subject, assetId, "hands"),
                            width, height);
                    rigDeps.add(hands);
                    globalAnalysisNodes.add(hands);
                }

                String rigCapability = subject.optString("rig", "articulated_2_5d").contains("3d")
                        ? "mesh.skinning" : "portrait.rig";
                String rig = addNode(nodes, unresolvedSet,
                        "subject." + alias + ".rig",
                        "rig",
                        rigCapability,
                        "balanced",
                        arrayOf(rigDeps),
                        subjectInput(subject, assetId, "rig"),
                        width, height);
                globalAnalysisNodes.add(rig);
            }
        }

        ArrayList<String> generationNodes = new ArrayList<>();
        JSONArray generations = ir == null ? null : ir.optJSONArray("generations");
        if (generations != null) {
            for (int i = 0; i < generations.length(); i++) {
                JSONObject generation = generations.optJSONObject(i);
                if (generation == null) continue;
                String alias = safeId(generation.optString("alias", "generation_" + i));
                String capability = generation.optString("capability", "image.generate");
                String node = addNode(nodes, unresolvedSet,
                        "generation." + alias,
                        "generation",
                        capability,
                        "balanced",
                        new JSONArray(),
                        generation,
                        width, height);
                generationNodes.add(node);
            }
        }

        ArrayList<String> audioNodes = new ArrayList<>();
        JSONArray audio = ir == null ? null : ir.optJSONArray("audio");
        if (audio != null) {
            for (int i = 0; i < audio.length(); i++) {
                JSONObject item = audio.optJSONObject(i);
                if (item == null) continue;
                String kind = safeId(item.optString("kind", "audio"));
                String node = addNode(nodes, unresolvedSet,
                        "audio." + kind + "." + i,
                        "audio",
                        "speech.tts",
                        "balanced",
                        new JSONArray(),
                        item,
                        width, height);
                audioNodes.add(node);
            }
        }

        ArrayList<String> shotNodes = new ArrayList<>();
        JSONArray shots = ir == null ? null : ir.optJSONArray("shots");
        if (shots != null) {
            for (int i = 0; i < shots.length(); i++) {
                JSONObject shot = shots.optJSONObject(i);
                if (shot == null) continue;
                JSONArray deps = new JSONArray();
                for (String dep : globalAnalysisNodes) deps.put(dep);
                for (String dep : generationNodes) deps.put(dep);
                String name = safeId(shot.optString("name", "shot_" + i));
                String node = addNode(nodes, unresolvedSet,
                        "shot." + name,
                        "compose",
                        "render.compositor",
                        qualityClass(quality),
                        deps,
                        shot,
                        width, height);
                shotNodes.add(node);
            }
        }

        if (shotNodes.isEmpty()) {
            JSONArray deps = new JSONArray();
            for (String dep : globalAnalysisNodes) deps.put(dep);
            for (String dep : generationNodes) deps.put(dep);
            shotNodes.add(addNode(nodes, unresolvedSet,
                    "shot.default",
                    "compose",
                    "render.compositor",
                    qualityClass(quality),
                    deps,
                    ir == null ? new JSONObject() : ir.optJSONObject("defaults"),
                    width, height));
        }

        JSONArray renderDeps = new JSONArray();
        for (String dep : shotNodes) renderDeps.put(dep);
        for (String dep : audioNodes) renderDeps.put(dep);

        String finalRender = addNode(nodes, unresolvedSet,
                "render.final",
                "render",
                "render.video",
                qualityClass(quality),
                renderDeps,
                render == null ? new JSONObject() : render,
                width, height);

        JSONObject critique = ir == null ? null : ir.optJSONObject("critique");
        if (critique != null && critique.optBoolean("enabled", true)) {
            addNode(nodes, unresolvedSet,
                    "critique.final",
                    "critique",
                    "render.critique",
                    "balanced",
                    arrayOf(finalRender),
                    critique,
                    width, height);
        }

        for (String missing : unresolvedSet) unresolved.put(missing);

        try {
            graph.put("graphVersion", GRAPH_VERSION);
            graph.put("nodeCount", nodes.length());
            graph.put("nodes", nodes);
            graph.put("unresolvedCapabilities", unresolved);
            graph.put("ready", unresolved.length() == 0);
            graph.put("durableCheckpoints", true);
            graph.put("cacheableNodes", true);
            graph.put("targetedReexecution", true);
        } catch (Exception ignored) {}
        return graph;
    }

    private String addNode(JSONArray nodes,
                           Set<String> unresolved,
                           String id,
                           String kind,
                           String capability,
                           String quality,
                           JSONArray dependencies,
                           JSONObject input,
                           int width,
                           int height) {
        JSONObject resolution = registry == null
                ? new JSONObject()
                : registry.resolve(capability, quality);
        JSONObject provider = resolution.optJSONObject("provider");
        long estimatedModelMb = provider == null ? 0 : Math.max(0, provider.optLong("estimatedRamMb", 0));
        JSONObject resourcePlan = compute == null
                ? new JSONObject()
                : compute.plan(estimatedModelMb, width, height, qualityClass(quality));

        JSONObject node = new JSONObject();
        try {
            node.put("id", id);
            node.put("kind", kind);
            node.put("capability", capability);
            node.put("quality", quality);
            node.put("dependencies", dependencies == null ? new JSONArray() : dependencies);
            node.put("input", input == null ? new JSONObject() : new JSONObject(input.toString()));
            node.put("providerResolved", resolution.optBoolean("resolved", false));
            if (provider != null) node.put("provider", provider);
            node.put("resourcePlan", resourcePlan);
            node.put("checkpointKey", "creative:" + id);
            node.put("cacheKey", cacheKey(node));
            node.put("state", "planned");
        } catch (Exception ignored) {}
        nodes.put(node);
        if (!resolution.optBoolean("resolved", false)) unresolved.add(capability);
        return id;
    }

    private static JSONObject subjectInput(JSONObject subject, String assetId, String phase) {
        JSONObject input = new JSONObject();
        try {
            input.put("subject", subject == null ? new JSONObject() : new JSONObject(subject.toString()));
            input.put("assetId", assetId == null ? "" : assetId);
            input.put("phase", phase);
        } catch (Exception ignored) {}
        return input;
    }

    private static JSONArray arrayOf(String value) {
        JSONArray out = new JSONArray();
        if (value != null && !value.isEmpty()) out.put(value);
        return out;
    }

    private static JSONArray arrayOf(Iterable<String> values) {
        JSONArray out = new JSONArray();
        if (values != null) for (String value : values) if (value != null && !value.isEmpty()) out.put(value);
        return out;
    }

    private static String qualityClass(String value) {
        String q = value == null ? "balanced" : value.toLowerCase(Locale.US);
        if (q.contains("720") || q.contains("draft") || q.contains("fast")) return "draft";
        if (q.contains("1080") || q.contains("final") || q.contains("high")) return "final";
        return "balanced";
    }

    private static String safeId(String raw) {
        String value = raw == null ? "node" : raw.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9._-]+", "_");
        if (value.isEmpty()) value = "node";
        if (value.length() > 96) value = value.substring(0, 96);
        return value;
    }

    private static String cacheKey(JSONObject node) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            JSONObject stable = new JSONObject();
            stable.put("id", node.optString("id"));
            stable.put("kind", node.optString("kind"));
            stable.put("capability", node.optString("capability"));
            stable.put("quality", node.optString("quality"));
            stable.put("dependencies", node.optJSONArray("dependencies"));
            stable.put("input", node.optJSONObject("input"));
            byte[] hash = digest.digest(stable.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return "uncached-" + safeId(node.optString("id"));
        }
    }
}
