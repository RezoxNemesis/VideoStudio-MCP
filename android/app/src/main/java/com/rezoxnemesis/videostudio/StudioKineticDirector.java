package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Native, source-preserving kinetic editing compiler.
 *
 * Turns one explicit creative program into real Media3 TITLE motion layers,
 * reusing the project's original image/video clips and imported audio lanes.
 * Dry-run is read only. Apply always creates a separate editable project.
 * No Gallery access, paid inference, fake AI frames or automatic export.
 */
public final class StudioKineticDirector {
    private StudioKineticDirector() {}

    public static JSONObject capabilities() throws Exception {
        return new JSONObject()
                .put("schema", "videostudio.kinetic-director.v1")
                .put("minimumNativeVersion", "4.10.26")
                .put("planAction", "kinetic_benchmark_plan")
                .put("applyAction", "kinetic_benchmark_apply")
                .put("mcpNativeActionBridge", "autonomous_request")
                .put("templates", new JSONArray(Arrays.asList("editorial", "impact", "minimal", "glyph")))
                .put("maxAuthoredTitles", KineticBeatPlanner.MAX_TITLES)
                .put("maxProjectLayers", 32)
                .put("inputs", new JSONArray(Arrays.asList(
                        "projectId", "expectedUpdatedAt", "titles", "style",
                        "beatMarkersMs", "bpm", "tempoOffsetMs", "audioEnvelope", "shapeAccents", "glyphMode", "operationId")))
                .put("timingProvenance", new JSONArray(Arrays.asList(
                        "author_markers", "measured_audio_onset_candidates",
                        "manual_bpm_grid", "clip_boundaries")))
                .put("renderedBy", "existing native Media3 composition title/keyframe effects")
                .put("vectorAccentMinimumNativeVersion", "4.10.27")
                .put("supportsSourceFreeShapeAccents", true)
                .put("glyphAnimation", new JSONObject().put("modes",
                        new JSONArray(Arrays.asList("assemble","scatter","cascade","wave")))
                        .put("maxCodepointsPerTitle",StudioGlyphAnimation.MAX_GLYPHS)
                        .put("maxCompositedGlyphOverlays",StudioGlyphAnimation.MAX_COMPOSITION_GLYPHS)
                        .put("nativeRenderer","Media3 timed per-codepoint TextOverlay")
                        .put("requiresCandidateApk",true))
                .put("sourceUnchanged", true).put("createsIndependentEditableProject", true)
                .put("autoExport", false).put("galleryAccess", false)
                .put("actualBeatDetection", false)
                .put("limitations", "Measured audio onsets are not guaranteed musical beats. "
                        + "Whole-title 2D keys and optional bounded glyph transforms; no automatic lyric transcription, "
                        + "tracked rotoscoping, volumetric 3D typography, optical-flow blur or AE compatibility.");
    }

    private static final class Draft {
        final ProjectStore.Project project;
        final JSONObject receipt;
        Draft(ProjectStore.Project project, JSONObject receipt) {
            this.project = project;
            this.receipt = receipt;
        }
    }

    public static JSONObject plan(ProjectStore.Project source, JSONObject request) throws Exception {
        return draft(source, request).receipt;
    }

    public static JSONObject apply(ProjectStore store, JSONObject request) throws Exception {
        if (store == null) throw new IllegalArgumentException("Native project store is required");
        String operationId = request.optString("operationId", "");
        if (!operationId.matches("[A-Za-z0-9_-]{8,64}"))
            throw new IllegalArgumentException("Apply requires stable 8..64-character operationId");
        String sourceId = requiredId(request, "projectId");
        ProjectStore.Project source = store.get(sourceId);
        if (source == null) throw new IllegalArgumentException("Source project does not exist");
        Draft plan = draft(source, request);
        String stable = "kinetic-director/" + source.id + "/" + operationId;
        String outputId = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).toString();
        String signature = fingerprint(source.id + ":" + source.updatedAt + ":" + SketchDocument.canonical(plan.receipt));
        String witness = "videostudio.kinetic-director.v1:" + signature;
        ProjectStore.Project prior = store.get(outputId);
        if (prior != null) {
            if (!witness.equals(prior.sourcePrompt))
                throw new IllegalStateException("KINETIC_OPERATION_CONFLICT: operationId belongs to a different edit");
            return appliedReceipt(prior, source, plan, true);
        }

        ProjectStore.Project next = plan.project;
        next.id = outputId;
        next.name = boundedName(source.name + " — Kinetic " + request.optString("style", "editorial"));
        next.sourcePrompt = witness;
        next.latestExportUri = "";
        next.latestExportName = "";
        next.latestExportAt = 0;
        // Recheck the source immediately before publishing. The original
        // and every selected asset remain read-only; a concurrent edit aborts.
        ProjectStore.Project current = store.get(sourceId);
        if (current == null || current.updatedAt != source.updatedAt)
            throw new IllegalStateException("KINETIC_SOURCE_CHANGED");
        store.save(next);
        return appliedReceipt(next, source, plan, false);
    }

    private static JSONObject appliedReceipt(ProjectStore.Project saved,
                                             ProjectStore.Project source, Draft draft,
                                             boolean reused) throws Exception {
        return new JSONObject().put("ok", true)
                .put("schema", "videostudio.kinetic-director.apply.v1")
                .put("projectId", saved.id)
                .put("sourceProjectId", source.id)
                .put("sourceUpdatedAt", source.updatedAt)
                .put("outputUpdatedAt", saved.updatedAt)
                .put("assetCount", saved.assets.size()).put("clipCount", saved.clips.size())
                .put("layerCount", saved.studioLayers.size())
                .put("cues", draft.receipt.getJSONArray("cues"))
                .put("timingProvenance", draft.receipt.getString("timingProvenance"))
                .put("reused", reused).put("sourceUnchanged", true)
                .put("rendered", false).put("previewVerified", false)
                .put("needsOnDeviceVisualQA", true).put("galleryAccess", false);
    }

    private static Draft draft(ProjectStore.Project source, JSONObject request) throws Exception {
        if (source == null || request == null) throw new IllegalArgumentException("Source project and request required");
        String projectId = requiredId(request, "projectId");
        if (!projectId.equals(source.id)) throw new IllegalArgumentException("Project identity mismatch");
        long revision = request.getLong("expectedUpdatedAt");
        if (revision != source.updatedAt) throw new IllegalStateException("KINETIC_SOURCE_CHANGED");
        if (source.clips.isEmpty() || source.outputDurationMs() < 500)
            throw new IllegalArgumentException("Source requires a timeline at least 500ms long");
        long outputMs = source.outputDurationMs();
        if (outputMs > 86_400_000L) throw new IllegalArgumentException("Only timelines up to 24h are supported");
        String style = request.optString("style", "editorial");
        if (!"editorial".equals(style) && !"impact".equals(style)
                && !"minimal".equals(style) && !"glyph".equals(style))
            throw new IllegalArgumentException("Unknown kinetic template");
        JSONArray raw = request.getJSONArray("titles");
        if (raw.length() == 0 || raw.length() > KineticBeatPlanner.MAX_TITLES)
            throw new IllegalArgumentException("Provide 1..24 human/AI-authored title strings");
        ArrayList<String> titles = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            if (!(raw.opt(i) instanceof String))
                throw new IllegalArgumentException("Each title must be a string");
            String title = raw.getString(i).trim();
            if (title.isEmpty() || title.length() > 100)
                throw new IllegalArgumentException("Each title must contain 1..100 characters");
            titles.add(title);
        }
        ProjectStore.Project output = ProjectStore.Project.fromJson(source.toJson());
        int available = 32 - output.studioLayers.size();
        if (available < 1) throw new IllegalArgumentException("No native title-layer capacity remains");
        if (titles.size() > available)
            throw new IllegalArgumentException("Too many titles for the remaining 32-layer budget: " + available);
        List<Long> timelineEvents;
        String origin;
        if (request.has("beatMarkersMs")) {
            JSONArray arr = request.getJSONArray("beatMarkersMs");
            ArrayList<Long> markers = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) markers.add(arr.getLong(i));
            timelineEvents = KineticBeatPlanner.authoredMarkers(markers, outputMs);
            origin = "author_markers";
        } else if (request.has("audioEnvelope")) {
            JSONObject envelope = request.getJSONObject("audioEnvelope");
            if (!"videostudio.audio.envelope.v1".equals(envelope.optString("schema"))
                    || !envelope.optBoolean("sourceUnchanged", false)
                    || envelope.optJSONObject("coverage") == null
                    || !envelope.getJSONObject("coverage").optBoolean("complete", false)
                    || !projectId.equals(envelope.optString("projectId", ""))
                    || revision != envelope.optLong("sourceProjectUpdatedAt", -1))
                throw new IllegalArgumentException("Verified complete audio envelope for current project revision required");
            String assetId = envelope.getString("assetId");
            ProjectStore.Asset music = source.asset(assetId);
            if (music == null || music.mime == null || !music.mime.startsWith("audio/"))
                throw new IllegalArgumentException("Envelope must reference registered project audio");
            StudioLayer audio = null;
            for (StudioLayer layer : source.studioLayers)
                if (StudioLayer.AUDIO.equals(layer.type) && assetId.equals(layer.assetId)) {
                    audio = layer; break;
                }
            if (audio == null) throw new IllegalArgumentException("Envelope audio is not on the source timeline");
            JSONArray buckets = envelope.getJSONArray("buckets");
            double[] rms = new double[buckets.length()];
            for (int i = 0; i < rms.length; i++) {
                JSONObject b = buckets.getJSONObject(i);
                if (b.isNull("normalizedRms") || b.getInt("index") != i ||
                        !b.getJSONObject("coverage").optBoolean("complete", false))
                    throw new IllegalArgumentException("Partial or out-of-order audio envelope buckets");
                rms[i] = b.getDouble("normalizedRms");
            }
            List<Long> onsets = KineticBeatPlanner.envelopeOnsets(rms,
                    envelope.getLong("sourceMs"), envelope.getLong("durationMs"));
            ArrayList<Long> aligned = new ArrayList<>();
            long previous = -1;
            for (long onset : onsets) {
                long at = onset - audio.sourceInMs + audio.startMs;
                if (at >= 0 && at < outputMs - 190 && at > previous) {
                    aligned.add(at); previous = at;
                }
            }
            if (aligned.isEmpty())
                throw new IllegalArgumentException("No measured onset candidates; use authored markers or BPM");
            timelineEvents = aligned;
            origin = "measured_audio_onset_candidates";
        } else if (request.has("bpm")) {
            int bpm = request.getInt("bpm");
            long offset = request.optLong("tempoOffsetMs", 0);
            timelineEvents = KineticBeatPlanner.manualTempo(outputMs, bpm, offset);
            origin = "manual_bpm_grid";
        } else {
            ArrayList<Long> durations = new ArrayList<>();
            for (ProjectStore.Clip clip : source.clips) durations.add(clip.outputDurationMs());
            timelineEvents = KineticBeatPlanner.clipBoundaries(durations, outputMs);
            origin = "clip_boundaries";
        }

        List<KineticBeatPlanner.Cue> cues = KineticBeatPlanner.cues(
                timelineEvents, titles.size(), outputMs);
        if (cues.isEmpty()) throw new IllegalArgumentException("No events long enough to animate a title");

        boolean includeAccents=request.optBoolean("shapeAccents",false);
        int shapesOnSource=0;
        for(StudioLayer lane:source.studioLayers)
            if(StudioLayer.SHAPE.equals(lane.type))shapesOnSource++;
        int remainingSlots=32-output.studioLayers.size()-cues.size();
        int desired=includeAccents?Math.min(4,cues.size()):0;
        int accentCount=Math.max(0,Math.min(desired,Math.min(remainingSlots,12-shapesOnSource)));
        java.util.HashSet<Integer> accentedIndices=new java.util.HashSet<>();
        for(int i=0;i<accentCount;i++) {
            int index=accentCount==1?0:
                    (int)Math.round(i*(cues.size()-1.0)/(accentCount-1));
            accentedIndices.add(index);
        }

        JSONArray cueReceipts = new JSONArray();
        for (int i = 0; i < cues.size(); i++) {
            KineticBeatPlanner.Cue cue = cues.get(i);
            StudioLayer accent=null;
            if(accentedIndices.contains(i)){
                String kind="impact".equals(style)?"diamond":
                        "minimal".equals(style)?"line":"grid";
                String motionStyle="impact".equals(style)?"spin":
                        "minimal".equals(style)?"slide":"drift";
                accent=StudioShapePresets.create(kind,motionStyle,cue.startMs,cue.durationMs);
                accent.id=UUID.nameUUIDFromBytes((source.id+"/kinetic-shape/"+i+
                        "/"+cue.startMs).getBytes(StandardCharsets.UTF_8)).toString();
                output.studioLayers.add(accent);
            }
            StudioLayer title = new StudioLayer();
            title.id = UUID.nameUUIDFromBytes((source.id + "/kinetic/" + i
                    + "/" + cue.startMs).getBytes(StandardCharsets.UTF_8)).toString();
            title.type = StudioLayer.TITLE;
            title.text = titles.get(i);
            title.startMs = cue.startMs;
            title.durationMs = cue.durationMs;
            title.fontFamily = "minimal".equals(style) ? "sans-serif" : "sans-serif-condensed";
            title.fontSize = "impact".equals(style) ? 72 : "minimal".equals(style) ? 40 : "glyph".equals(style) ? 58 : 56;
            title.fontStyle = "bold";
            title.textAlign = "center";
            title.position = i % 3 == 0 ? 1 : (i % 3 == 1 ? 0 : 2);
            title.color = "impact".equals(style) ? 0xffffe99a : 0xffffffff;
            title.backgroundColor = 0;
            if("glyph".equals(style)){
                JSONObject glyph=new JSONObject().put("mode",request.optString("glyphMode","scatter"));
                title.glyphAnimation=StudioGlyphAnimation.validate(glyph,title.text,cue.durationMs);
                // The per-letter entrance/exit is the authored motion, not a second global fade.
                title.motionKeyframes=new JSONArray();
            }else title.motionKeyframes=titleKeys(style, i, cue.durationMs);
            title.validate();
            output.studioLayers.add(title);
            JSONObject cueReceipt=new JSONObject().put("text", title.text)
                    .put("startMs", title.startMs).put("durationMs", title.durationMs)
                    .put("layerId", title.id).put("keyframeCount", title.motionKeyframes.length());
            if(title.glyphAnimation!=null)cueReceipt
                    .put("glyphAnimationMode",title.glyphAnimation.optString("mode","assemble"))
                    .put("independentGlyphs",StudioGlyphAnimation.visibleGlyphCount(title.text));
            if(accent!=null)cueReceipt.put("nativeShapeLayerId",accent.id)
                    .put("nativeShapeKind",accent.shapeKind);
            cueReceipts.put(cueReceipt);
        }
        // Uses the same authoritative admission that normal editor lanes use.
        StudioLayerContract.fromJson(output, StudioLayerContract.toJson(output.studioLayers));
        JSONObject receipt = new JSONObject().put("ok", true)
                .put("schema", "videostudio.kinetic-director.plan.v1")
                .put("sourceProjectId", source.id).put("sourceUpdatedAt", source.updatedAt)
                .put("style", style).put("timingProvenance", origin)
                .put("nativePerGlyphAnimation", "glyph".equals(style))
                .put("musicalBeatCertainty", "none")
                .put("titleCount", cues.size()).put("requestedTitles", titles.size())
                .put("shapeAccentsRequested", includeAccents).put("shapeAccentsCreated", accentCount)
                .put("durationMs", outputMs).put("assetCount", output.assets.size())
                .put("clipCount", output.clips.size()).put("layerCount", output.studioLayers.size())
                .put("originalProjectModified", false).put("rendered", false)
                .put("previewVerified", false).put("cues", cueReceipts);
        return new Draft(output, receipt);
    }

    private static JSONArray titleKeys(String style, int index, long ms) throws Exception {
        long enter = Math.max(60, Math.min(ms / 4, 210));
        long exit = Math.max(90, Math.min(ms / 4, 260));
        long settled = Math.max(enter + 1, ms - exit);
        double direction = (index % 2 == 0 ? -1 : 1);
        JSONArray keys = new JSONArray();
        if ("impact".equals(style)) {
            keys.put(key(0, 0.0, 0.0, .58, direction * -6, 0, "ease_out"));
            keys.put(key(enter, 0.0, 0.0, 1.15, 2 * direction, 1, "ease_out"));
            keys.put(key(Math.min(ms, enter + Math.max(50, enter / 2)), 0, 0, 1.0, 0, 1, "ease_in_out"));
        } else if ("editorial".equals(style)) {
            keys.put(key(0, direction * .28, .07, 1.07, 0, 0, "cubic_bezier")
                    .put("bezier",StudioBezierEasing.fromText(StudioBezierEasing.PRESET_FILM)));
            keys.put(key(enter, 0, 0, 1, 0, 1, "ease_in_out"));
        } else {
            keys.put(key(0, 0, .035, 1.01, 0, 0, "ease_in_out"));
            keys.put(key(enter, 0, 0, 1, 0, 1, "ease_in_out"));
        }
        keys.put(key(settled, 0, 0, 1, 0, 1, "linear"));
        keys.put(key(ms, "editorial".equals(style) ? -direction * .12 : 0,
                "minimal".equals(style) ? -.02 : 0, 1.02, 0, 0, "ease_in"));
        return keys;
    }

    private static JSONObject key(long at, double x, double y, double scale,
                                  double rotation, double alpha, String easing) throws Exception {
        return new JSONObject().put("timeMs", at).put("x", x).put("y", y)
                .put("scale", scale).put("rotate", rotation)
                .put("opacity", alpha).put("easing", easing);
    }

    private static String requiredId(JSONObject request, String key) throws Exception {
        Object raw = request.get(key);
        if (!(raw instanceof String) || ((String) raw).length() < 8
                || ((String) raw).length() > 128)
            throw new IllegalArgumentException(key + " must be an existing stable project ID");
        return (String) raw;
    }

    private static String fingerprint(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte part : digest) result.append(String.format(Locale.ROOT, "%02x", part & 255));
        return result.toString();
    }

    private static String boundedName(String value) {
        return value.length() > 110 ? value.substring(0, 110) : value;
    }
}
