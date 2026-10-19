package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Connects a real Filament GLB pose capture to native Sketch Studio.
 *
 * The original GLB stays in the owner project. A bounded, hash-checked PNG is
 * retained as an invisible reference and traced into a NEW editable vector
 * layer at the selected frame. It is not a 3D mesh editor or neural 3D inference.
 */
final class Sketch3DCelBridge {
    static final int MAX_CAPTURE_EDGE = 768;
    private Sketch3DCelBridge() {}

    static int[] captureSize(int sourceWidth, int sourceHeight, int edge) {
        if (sourceWidth < 2 || sourceHeight < 2 || edge < 64 || edge > 1024)
            throw new IllegalArgumentException("Invalid 3D capture dimensions");
        double ratio = Math.min(1.0, edge / (double) Math.max(sourceWidth, sourceHeight));
        return new int[] {Math.max(1, (int)Math.round(sourceWidth * ratio)),
                Math.max(1, (int)Math.round(sourceHeight * ratio))};
    }

    static JSONObject traceOperation(String referenceId, String destinationLayer,
                                     int frame, String prefix, String mode) throws Exception {
        if (!"colour_regions".equals(mode) && !"edges".equals(mode))
            throw new IllegalArgumentException("Choose editable colour or ink tracing");
        JSONObject result = new JSONObject().put("type", "trace_reference")
                .put("objectId", referenceId).put("layerId", destinationLayer)
                .put("idPrefix", prefix).put("frame", frame).put("exposure", 1)
                .put("mode", mode);
        if ("colour_regions".equals(mode)) {
            // Mobile-safe quantised filled paths; no raster pixels in the final cel.
            result.put("maxDimension", 80).put("colourLevels", 5);
        } else {
            result.put("maxDimension", 160).put("threshold", 0.25)
                    .put("color", "#FF172333").put("width", 0.0015);
        }
        return result;
    }

    static JSONObject importCapturedPose(Context context, String sketchId, String projectId,
                                         String assetId, int frame, String requestedMode) throws Exception {
        if (context == null) throw new IllegalArgumentException("Sketch context is required");
        if (!"colour_regions".equals(requestedMode) && !"edges".equals(requestedMode))
            throw new IllegalArgumentException("Unknown 3D cel tracing mode");
        SketchDocument.id(new JSONObject().put("id", sketchId), "id");
        SketchDocument.id(new JSONObject().put("id", projectId), "id");
        SketchDocument.id(new JSONObject().put("id", assetId), "id");

        ProjectStore projectStore = new ProjectStore(context);
        ProjectStore.Project project = projectStore.get(projectId);
        if (project == null) throw new IllegalArgumentException("Source 3D project was removed");
        ProjectStore.Asset asset = project.asset(assetId);
        if (asset == null || !asset.generated || !"sketch_3d_pose".equals(asset.role)
                || !"image/png".equals(asset.mime))
            throw new IllegalArgumentException("Capture is not an owned VideoStudio 3D pose");
        String sourceGlb = asset.generationMetadata.optString("sourceGlbAssetId", "");
        ProjectStore.Asset model = project.asset(sourceGlb);
        if (model == null || !"model/gltf-binary".equals(model.mime))
            throw new IllegalArgumentException("The original GLB is no longer registered");
        Uri uri = Uri.parse(asset.uri);
        if (!"file".equals(uri.getScheme()) || uri.getPath() == null)
            throw new IllegalArgumentException("3D pose must remain app-private");
        File source = new File(uri.getPath());
        File directory = new File(context.getFilesDir(),
                "creative_workspace/projects/" + projectId + "/pose_cels").getCanonicalFile();
        if (!source.getCanonicalFile().getParentFile().equals(directory)
                || !source.isFile() || source.length() < 32 || source.length() > 8L*1024*1024)
            throw new IllegalArgumentException("3D pose capture is missing or outside its owner project");
        if (!SketchProjectStore.fileHash(source).equals(
                asset.generationMetadata.optString("captureSha256", "")))
            throw new IllegalStateException("3D pose pixels changed since capture");

        JSONObject initial;
        try (SketchProjectStore sketches = new SketchProjectStore(context)) {
            initial = sketches.get(sketchId);
        }
        if (frame < 0 || frame >= initial.getInt("frameCount"))
            throw new IllegalArgumentException("Target cel is outside its drawing");
        JSONArray layers = initial.getJSONArray("layers");
        if (layers.length() > SketchDocument.MAX_LAYERS - 2)
            throw new IllegalStateException("Sketch needs two spare layers for the 3D reference and editable cel");
        int images = 0;
        for (int n = 0; n < layers.length(); n++) {
            JSONArray objects = layers.getJSONObject(n).getJSONArray("objects");
            for (int j = 0; j < objects.length(); j++)
                if ("image".equals(objects.getJSONObject(j).optString("kind"))) images++;
        }
        if (images >= 16) throw new IllegalStateException("Sketch reference budget is full (16 images)");

        String unique = UUID.randomUUID().toString().replace("-", "");
        String guideLayer = "glb_guide_" + unique;
        String vectorLayer = "glb_cel_" + unique;
        String referenceId = "glb_ref_" + unique;
        String prefix = "glb_path_" + unique;
        JSONObject create = new JSONObject().put("sketchId", sketchId)
                .put("operationId", UUID.randomUUID().toString())
                .put("expectedRevision", initial.getLong("revision"))
                .put("operations", new JSONArray()
                        .put(new JSONObject().put("type", "add_layer")
                                .put("layer", new JSONObject().put("id", guideLayer)
                                        .put("name", "3D pose source - hidden")))
                        .put(new JSONObject().put("type", "add_layer")
                                .put("layer", new JSONObject().put("id", vectorLayer)
                                        .put("name", "3D pose - editable cel"))));
        JSONObject created = SketchEditOps.execute(context, "sketch_edit", create);
        // Importing the invisible image deliberately does not masquerade as
        // user-drawn line art. Its visible, editable replacement comes next.
        JSONObject imported = SketchEditOps.execute(context, "sketch_import_reference",
                new JSONObject().put("sketchId", sketchId)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("expectedRevision", created.getLong("revision"))
                        .put("projectId", projectId)
                        .put("sourceProjectUpdatedAt", project.updatedAt)
                        .put("assetId", assetId).put("layerId", guideLayer)
                        .put("objectId", referenceId).put("frame", frame)
                        .put("exposure", 1).put("guideVisible", false));

        String actualMode = requestedMode;
        String fallbackReason = "";
        JSONObject traced;
        try {
            traced = trace(context, sketchId, imported.getLong("revision"),
                    traceOperation(referenceId, vectorLayer, frame, prefix, actualMode));
        } catch (Exception colourFailure) {
            if (!"colour_regions".equals(actualMode)
                    || String.valueOf(colourFailure.getMessage()).contains("REVISION_CONFLICT"))
                throw colourFailure;
            actualMode = "edges";
            fallbackReason = "Colour trace could not fit the vector budget; used ink outlines";
            traced = trace(context, sketchId, imported.getLong("revision"),
                    traceOperation(referenceId, vectorLayer, frame, prefix, actualMode));
        }

        int pathCount;
        try (SketchProjectStore sketches = new SketchProjectStore(context)) {
            pathCount = SketchDocument.layer(sketches.get(sketchId), vectorLayer)
                    .getJSONArray("objects").length();
        }
        if (pathCount < 1) throw new IllegalStateException("3D pose created no editable vector paths");
        return new JSONObject().put("sketchId", sketchId).put("frameIndex", frame)
                .put("revision", traced.getLong("revision"))
                .put("sourceGlbAssetId", sourceGlb)
                .put("guideLayerId", guideLayer).put("editableLayerId", vectorLayer)
                .put("vectorObjects", pathCount).put("traceMode", actualMode)
                .put("notice", fallbackReason);
    }

    private static JSONObject trace(Context context, String sketchId, long revision,
                                    JSONObject operation) throws Exception {
        return SketchEditOps.execute(context, "sketch_edit",
                new JSONObject().put("sketchId", sketchId)
                        .put("operationId", UUID.randomUUID().toString())
                        .put("expectedRevision", revision)
                        .put("operations", new JSONArray().put(operation)));
    }
}
