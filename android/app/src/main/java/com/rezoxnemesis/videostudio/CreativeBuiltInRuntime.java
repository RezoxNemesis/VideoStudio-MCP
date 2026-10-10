package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Executes the CreativeIR capabilities that VideoStudio can truthfully perform
 * with engines already bundled in the APK.
 *
 * Unsupported advanced capabilities are never faked here. They stay unresolved
 * until an installed provider/model pack can supply them.
 */
public final class CreativeBuiltInRuntime {
    private final NativePortraitMotionAnalyzer portraitAnalyzer;
    private final NativeRenderCritic renderCritic;
    private final CreativeNodeStore nodeStore;

    public CreativeBuiltInRuntime(NativePortraitMotionAnalyzer portraitAnalyzer,
                                  NativeRenderCritic renderCritic,
                                  CreativeNodeStore nodeStore) {
        this.portraitAnalyzer = portraitAnalyzer;
        this.renderCritic = renderCritic;
        this.nodeStore = nodeStore;
    }

    public boolean supports(String capability) {
        return "person.segmentation".equals(capability)
                || "face.landmarks".equals(capability)
                || "depth.estimate".equals(capability)
                || "portrait.rig".equals(capability)
                || "render.critique".equals(capability);
    }

    public JSONObject execute(ProjectStore.Project project,
                              JSONObject node,
                              ProjectStore.Asset latestRender) throws Exception {
        if (project == null) throw new IllegalArgumentException("Project is required");
        if (node == null) throw new IllegalArgumentException("Creative node is required");

        String capability = node.optString("capability", "");
        switch (capability) {
            case "person.segmentation":
                return executePortraitBundle(project, node);
            case "face.landmarks":
                return executeFace(project, node);
            case "depth.estimate":
                return executeLayeredDepth(project, node);
            case "portrait.rig":
                return executePortraitRig(project, node);
            case "render.critique":
                if (latestRender == null) throw new IllegalStateException("No rendered video is available for critique");
                return renderCritic.critique(latestRender, 14);
            default:
                throw new IllegalArgumentException("No built-in runtime for capability: " + capability);
        }
    }

    private JSONObject executePortraitBundle(ProjectStore.Project project, JSONObject node) throws Exception {
        JSONObject input = node.optJSONObject("input");
        String assetId = input == null ? "" : input.optString("assetId", "");
        ProjectStore.Asset asset = project.asset(assetId);
        if (asset == null) throw new IllegalArgumentException("Creative subject asset not found: " + assetId);

        NativePortraitMotionAnalyzer.Result layers = portraitAnalyzer.analyseAndBuildLayers(asset, project.id);
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("portraitBundle", true);
        out.put("assetId", asset.id);
        out.put("engine", "bundled-on-device-portrait-ai");
        out.put("foregroundUri", layers.foregroundUri.toString());
        out.put("headUri", layers.headUri.toString());
        out.put("torsoUri", layers.torsoUri.toString());
        out.put("lowerUri", layers.lowerUri.toString());
        out.put("backgroundUri", layers.backgroundUri.toString());
        out.put("width", layers.width);
        out.put("height", layers.height);
        out.put("analysis", new JSONObject(layers.analysis.toString()));
        out.put("depthMode", "subject-aware-layered-depth");
        return out;
    }

    private JSONObject executeFace(ProjectStore.Project project, JSONObject node) throws Exception {
        JSONObject bundle = findPortraitBundle(project, assetId(node));
        JSONObject analysis = bundle.optJSONObject("analysis");
        if (analysis == null) throw new IllegalStateException("Portrait analysis bundle is missing face data");

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("assetId", bundle.optString("assetId", ""));
        out.put("engine", "bundled-face-mesh-derived");
        out.put("faceDetected", analysis.optBoolean("faceDetected", false));
        out.put("faceCenterX", analysis.optDouble("faceCenterX", .5));
        out.put("faceCenterY", analysis.optDouble("faceCenterY", .36));
        out.put("faceCoverage", analysis.optDouble("faceCoverage", 0));
        out.put("sourceBundleNode", bundle.optString("_nodeId", ""));
        return out;
    }

    private JSONObject executeLayeredDepth(ProjectStore.Project project, JSONObject node) throws Exception {
        JSONObject bundle = findPortraitBundle(project, assetId(node));
        JSONObject analysis = bundle.optJSONObject("analysis");
        if (analysis == null) analysis = new JSONObject();

        double coverage = analysis.optDouble("subjectCoverage", .35);
        double subjectDepth = clamp(.54 + Math.min(.24, coverage * .32), .52, .82);
        double backgroundDepth = clamp(.22 - Math.min(.08, coverage * .08), .10, .25);

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("assetId", bundle.optString("assetId", ""));
        out.put("engine", "subject-aware-layered-depth-v1");
        out.put("metricDepth", false);
        out.put("depthMap", false);
        out.put("subjectDepth", subjectDepth);
        out.put("backgroundDepth", backgroundDepth);
        out.put("foregroundUri", bundle.optString("foregroundUri", ""));
        out.put("backgroundUri", bundle.optString("backgroundUri", ""));
        if (bundle.optJSONObject("analysis") != null) {
            out.put("analysis", new JSONObject(bundle.optJSONObject("analysis").toString()));
        }
        out.put("sourceBundleNode", bundle.optString("_nodeId", ""));
        return out;
    }

    private JSONObject executePortraitRig(ProjectStore.Project project, JSONObject node) throws Exception {
        JSONObject bundle = findPortraitBundle(project, assetId(node));
        JSONObject input = node.optJSONObject("input");
        JSONObject subject = input == null ? null : input.optJSONObject("subject");
        if (subject == null) subject = new JSONObject();

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("assetId", bundle.optString("assetId", ""));
        out.put("engine", "articulated-portrait-rig-v1");
        out.put("rigType", subject.optString("rig", "articulated_2_5d"));
        out.put("preserveIdentity", subject.optDouble("preserveIdentity", .96));
        out.put("hairMotion", subject.optDouble("hairMotion", .18));
        out.put("clothMotion", subject.optDouble("clothMotion", .16));
        out.put("headUri", bundle.optString("headUri", ""));
        out.put("torsoUri", bundle.optString("torsoUri", ""));
        out.put("lowerUri", bundle.optString("lowerUri", ""));
        out.put("foregroundUri", bundle.optString("foregroundUri", ""));
        out.put("backgroundUri", bundle.optString("backgroundUri", ""));
        if (bundle.optJSONObject("analysis") != null)
            out.put("analysis", new JSONObject(bundle.getJSONObject("analysis").toString()));
        out.put("sourceBundleNode", bundle.optString("_nodeId", ""));
        return out;
    }

    private JSONObject findPortraitBundle(ProjectStore.Project project, String assetId) throws Exception {
        JSONObject state = nodeStore.status(project.id);
        JSONArray nodes = state.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject candidate = nodes.optJSONObject(i);
                if (candidate == null || !"completed".equals(candidate.optString("state"))) continue;
                JSONObject result = candidate.optJSONObject("result");
                if (result == null || !result.optBoolean("portraitBundle", false)) continue;
                if (!assetId.isEmpty() && !assetId.equals(result.optString("assetId", ""))) continue;
                ProjectStore.Asset source = project.asset(result.optString("assetId", ""));
                if (source == null) continue;
                JSONObject analysis = result.optJSONObject("analysis");
                // Provider documents can change behind a stable URI. A stored
                // node identity is insufficient without checking captured pixels
                // and immutable layer files before this dependency is reused.
                portraitAnalyzer.assertSourceFresh(source, analysis);
                portraitAnalyzer.assertLayerFilesValid(analysis);
                JSONObject copy = new JSONObject(result.toString());
                copy.put("_nodeId", candidate.optString("id", ""));
                return copy;
            }
        }
        throw new IllegalStateException("Portrait analysis dependency has not completed for asset " + assetId);
    }

    private static String assetId(JSONObject node) {
        JSONObject input = node.optJSONObject("input");
        return input == null ? "" : input.optString("assetId", "");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
