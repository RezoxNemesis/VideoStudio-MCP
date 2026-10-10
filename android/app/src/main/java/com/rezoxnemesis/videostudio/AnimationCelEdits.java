package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

/** Pure frame-sheet edits. Persist through ProjectStore.edit to enforce revisions, locks and undo. */
public final class AnimationCelEdits {
    private static final long MAX_FRAME = 10_000_000L;
    private static final long MAX_CLOCK_MS = Long.MAX_VALUE / 1000L;
    private AnimationCelEdits() {}

    public static boolean isCel(ProjectStore.Asset asset) {
        return asset != null && "animation_cel".equals(asset.role) && asset.generated && "image/png".equals(asset.mime)
                && asset.importMetadata != null && asset.importMetadata.optJSONObject("animationCel") != null;
    }

    public static ProjectStore.Asset requireCelAsset(ProjectStore.Project project, String id) {
        ProjectStore.Asset asset = project.asset(id);
        if (!isCel(asset)) throw new IllegalArgumentException("Select an editable animation cel source from this project");
        return asset;
    }

    public static JSONObject drawingForClip(ProjectStore.Project project, String clipId) throws Exception {
        ProjectStore.Clip clip = requireClip(project, clipId);
        return AnimationCelFactory.validateDrawing(requireCelAsset(project, clip.assetId).importMetadata
                .getJSONObject("animationCel").getJSONObject("document"));
    }

    public static JSONObject describe(ProjectStore.Project project, String clipId) throws Exception {
        ProjectStore.Clip clip = requireClip(project, clipId); ProjectStore.Asset asset = requireCelAsset(project, clip.assetId);
        JSONObject metadata = asset.importMetadata.getJSONObject("animationCel");
        JSONObject result = new JSONObject().put("clipId", clip.id).put("assetId", asset.id).put("celId", metadata.getString("celId"))
                .put("generationId", metadata.getString("generationId")).put("startMs", clip.startMs).put("endMs", clip.endMs())
                .put("frameAligned", matchesFrameGrid(asset, clip)).put("rasterWidth", asset.width).put("rasterHeight", asset.height)
                .put("outputFrameRate", project.animationFrameRate == 0 ? 30 : project.animationFrameRate)
                .put("timePrecision", "cumulative rational boundaries rounded to the native millisecond timeline");
        if (clip.effects.optJSONObject("celExposure") != null) result.put("exposure", new JSONObject(clip.effects.getJSONObject("celExposure").toString()));
        return result;
    }

    /** Registration accepts only a locally rendered immutable artifact, never a caller-supplied locator. */
    public static void register(ProjectStore.Project project, AnimationCelFactory.RenderedCel rendered, JSONObject settings, String clipId) throws Exception {
        if (!project.id.equals(rendered.projectId)) throw new IllegalArgumentException("Rendered cel belongs to another project");
        ProjectStore.Asset asset = rendered.registeredAsset();
        if (project.asset(asset.id) != null || project.clip(clipId) != null) throw new IllegalStateException("Cel request identities already exist");
        JSONObject options = new JSONObject(settings.toString()).put("assetId", asset.id);
        project.assets.add(asset);
        add(project, options, clipId);
    }

    /** Preserve exposure timing/effects; redraw changes its immutable image source only. */
    public static void replace(ProjectStore.Project project, String clipId, AnimationCelFactory.RenderedCel rendered) throws Exception {
        if (!project.id.equals(rendered.projectId)) throw new IllegalArgumentException("Rendered cel belongs to another project");
        ProjectStore.Clip clip = requireClip(project, clipId); ProjectStore.Asset old = requireCelAsset(project, clip.assetId);
        requireUnlocked(project, clip);
        ProjectStore.Asset asset = rendered.registeredAsset();
        JSONObject metadata = asset.importMetadata.getJSONObject("animationCel");
        if (!old.id.equals(metadata.getString("parentAssetId")) || !old.importMetadata.getJSONObject("animationCel").getString("celId").equals(rendered.celId))
            throw new IllegalStateException("Cel redraw source changed since raster publication");
        if (project.asset(asset.id) != null) throw new IllegalStateException("Cel generation already exists");
        project.assets.add(asset);
        clip.assetId = asset.id;
        JSONObject exposure = clip.effects.optJSONObject("celExposure");
        if (exposure != null) exposure.put("celId", rendered.celId).put("generationId", metadata.getString("generationId"));
    }

    public static void applyRedrawExposure(ProjectStore.Project project, String clipId, JSONObject settings) throws Exception {
        fields(settings, "frameCount", "fpsNumerator", "fpsDenominator", "ripple");
        ProjectStore.Clip clip = requireClip(project, clipId);
        requireCelAsset(project, clip.assetId); requireUnlocked(project, clip);
        JSONObject original = clip.effects.getJSONObject("celExposure");
        boolean ripple = bool(settings, "ripple", false);
        if (settings.has("fpsNumerator") || settings.has("fpsDenominator")) {
            JSONObject requested = new JSONObject().put("fpsNumerator", integer(settings, "fpsNumerator", fps(original), 1, 120000))
                    .put("fpsDenominator", integer(settings, "fpsDenominator", 1, 1, 10000));
            if (fps(requested) != fps(original)) throw new IllegalArgumentException("Redraw keeps the exposure frame grid; change project cadence explicitly or create a new exposure to use another fps");
        }
        if (settings.has("frameCount")) {
            long frames = integer(settings, "frameCount", -1, 1, MAX_FRAME);
            if (frames != integer(original, "frameCount", -1, 1, MAX_FRAME))
                apply(project, "cel_exposure_hold", new JSONObject().put("clipId", clipId).put("frameCount", frames).put("ripple", ripple));
        }
    }

    public static void apply(ProjectStore.Project project, String operation, JSONObject settings) throws Exception {
        if (settings == null) throw new IllegalArgumentException("Cel exposure settings are required");
        switch (operation) {
            case "animation_frame_rate": {
                fields(settings, "frameRate");
                int rate = (int) integer(settings, "frameRate", -1, 1, 60);
                if (!supportedFrameRate(rate)) throw new IllegalArgumentException("Choose 12, 24, 30 or 60 fps");
                project.animationFrameRate = rate; return;
            }
            case "cel_exposure_add": add(project, settings, UUID.randomUUID().toString()); return;
            case "cel_exposure_hold": case "cel_exposure_extend": {
                if ("cel_exposure_extend".equals(operation)) fields(settings, "clipId", "addFrames", "ripple");
                else fields(settings, "clipId", "frameCount", "ripple");
                ProjectStore.Clip clip = requireClip(project, settings.getString("clipId")); requireCelAsset(project, clip.assetId);
                requireUnlocked(project, clip); requireFrameAligned(clip);
                JSONObject exposure = clip.effects.getJSONObject("celExposure");
                long frames = "cel_exposure_extend".equals(operation) ? Math.addExact(integer(exposure, "frameCount", -1, 1, MAX_FRAME),
                        integer(settings, "addFrames", -1, 1, MAX_FRAME)) : integer(settings, "frameCount", -1, 1, MAX_FRAME);
                if (frames > MAX_FRAME) throw new IllegalArgumentException("Exposure frame count exceeds its bound");
                long start = integer(exposure, "startFrame", -1, 0, MAX_FRAME), end = Math.addExact(start, frames);
                int numerator = fps(exposure), denominator = 1;
                long duration = frameBoundaryMs(end, numerator, denominator) - frameBoundaryMs(start, numerator, denominator);
                JSONObject trim = new JSONObject().put("clipId", clip.id).put("inMs", clip.inMs).put("outMs", ProjectTimeline.safeAdd(clip.inMs, duration))
                        .put("ripple", bool(settings, "ripple", false));
                ProjectTimeline.apply(project, "trim", trim);
                clip.effects.getJSONObject("celExposure").put("frameCount", frames);
                return;
            }
            case "cel_exposure_duplicate": {
                fields(settings, "clipId", "startFrame", "trackId", "ripple");
                ProjectStore.Clip clip = requireClip(project, settings.getString("clipId")); requireCelAsset(project, clip.assetId);
                requireUnlocked(project, clip); requireFrameAligned(clip);
                JSONObject before = new JSONObject(clip.effects.getJSONObject("celExposure").toString());
                long startFrame = integer(settings, "startFrame", Math.addExact(before.getLong("startFrame"), before.getLong("frameCount")), 0, MAX_FRAME);
                int rate = fps(before); long origin = integer(before, "originMs", 0, 0, MAX_CLOCK_MS);
                long startMs = ProjectTimeline.safeAdd(origin, frameBoundaryMs(startFrame, rate, 1));
                HashSet<String> ids = new HashSet<>(); for (ProjectStore.Clip item : project.clips) ids.add(item.id);
                JSONObject duplicate = new JSONObject().put("clipId", clip.id).put("startMs", startMs).put("ripple", bool(settings, "ripple", false));
                if (settings.has("trackId")) duplicate.put("trackId", settings.getString("trackId"));
                ProjectTimeline.apply(project, "duplicate", duplicate);
                String destination = settings.optString("trackId", clip.trackId);
                for (ProjectStore.Clip copy : project.clips) if (!ids.contains(copy.id) && clip.assetId.equals(copy.assetId) && destination.equals(copy.trackId)) {
                    long frames = before.getLong("frameCount"), duration = frameBoundaryMs(Math.addExact(startFrame, frames), rate, 1) - frameBoundaryMs(startFrame, rate, 1);
                    long oldDuration = copy.outputDurationMs();
                    if (oldDuration != duration) {
                        JSONObject trim = new JSONObject().put("clipId", copy.id).put("inMs", copy.inMs).put("outMs", ProjectTimeline.safeAdd(copy.inMs, duration))
                                .put("ripple", bool(settings, "ripple", false));
                        ProjectTimeline.apply(project, "trim", trim);
                    }
                    before.put("startFrame", startFrame); copy.effects.put("celExposure", before); return;
                }
                throw new IllegalStateException("Duplicated cel exposure could not be identified");
            }
            case "cel_exposure_delete": {
                fields(settings, "clipId", "ripple");
                ProjectStore.Clip clip = requireClip(project, settings.getString("clipId")); requireCelAsset(project, clip.assetId);
                ProjectTimeline.apply(project, "delete", new JSONObject().put("clipId", clip.id).put("ripple", bool(settings, "ripple", false))); return;
            }
            default: throw new IllegalArgumentException("Unsupported cel exposure operation");
        }
    }

    private static void add(ProjectStore.Project project, JSONObject settings, String clipId) throws Exception {
        fields(settings, "assetId", "trackId", "startFrame", "frameCount", "fpsNumerator", "fpsDenominator", "originMs", "ripple", "name");
        ProjectStore.Asset asset = requireCelAsset(project, settings.getString("assetId"));
        int rate = fps(settings);
        if (project.animationFrameRate == 0) project.animationFrameRate = rate;
        else if (project.animationFrameRate != rate) throw new IllegalArgumentException("Cel fps differs from the chosen project output cadence; choose the project frame rate explicitly first");
        long startFrame = integer(settings, "startFrame", 0, 0, MAX_FRAME), frameCount = integer(settings, "frameCount", 1, 1, MAX_FRAME);
        long endFrame = Math.addExact(startFrame, frameCount), origin = integer(settings, "originMs", 0, 0, MAX_CLOCK_MS);
        long start = ProjectTimeline.safeAdd(origin, frameBoundaryMs(startFrame, rate, 1));
        long end = ProjectTimeline.safeAdd(origin, frameBoundaryMs(endFrame, rate, 1));
        if (end > MAX_CLOCK_MS) throw new IllegalArgumentException("Exposure exceeds native media timing");
        ProjectStore.Track track = settings.has("trackId") ? project.track(settings.getString("trackId")) : ProjectTimeline.defaultTrack(project, "video");
        if (track == null || track.isAudio()) throw new IllegalArgumentException("Animation cels require a visual track");
        if (track.locked) throw new IllegalStateException("Track is locked: " + track.name);
        ProjectStore.Clip clip = new ProjectStore.Clip(); clip.id = clipId; clip.assetId = asset.id; clip.trackId = track.id;
        clip.startMs = start; clip.inMs = 0; clip.outMs = end - start; clip.programDurationMs = end - start;
        JSONObject cel = asset.importMetadata.getJSONObject("animationCel");
        clip.effects.put("crop", "fit");
        clip.effects.put("celExposure", new JSONObject().put("version", 1).put("celId", cel.getString("celId"))
                .put("generationId", cel.getString("generationId")).put("fpsNumerator", rate).put("fpsDenominator", 1)
                .put("originMs", origin).put("startFrame", startFrame).put("frameCount", frameCount));
        if (bool(settings, "ripple", false)) ProjectLinkedEdits.shiftFollowing(project, java.util.Collections.singleton(track.id), start, end - start, java.util.Collections.emptySet());
        if (settings.has("name")) {
            String name = settings.getString("name").trim(); if (name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("Cel name needs 1 to 120 characters");
            asset.name = name;
        }
        project.clips.add(clip);
    }

    public static boolean matchesFrameGrid(ProjectStore.Clip clip) {
        try {
            JSONObject exposure = clip.effects.getJSONObject("celExposure");
            if (integer(exposure, "version", -1, 1, 1) != 1) return false;
            if (!(exposure.opt("celId") instanceof String) || exposure.getString("celId").isEmpty()
                    || !(exposure.opt("generationId") instanceof String) || exposure.getString("generationId").isEmpty()) return false;
            int rate = fps(exposure); long start = integer(exposure, "startFrame", -1, 0, MAX_FRAME), frames = integer(exposure, "frameCount", -1, 1, MAX_FRAME);
            long origin = integer(exposure, "originMs", 0, 0, MAX_CLOCK_MS);
            long beginning = ProjectTimeline.safeAdd(origin, frameBoundaryMs(start, rate, 1)), end = ProjectTimeline.safeAdd(origin, frameBoundaryMs(Math.addExact(start, frames), rate, 1));
            return clip.speed == 1f && clip.inMs == 0 && clip.startMs == beginning && clip.endMs() == end && clip.outMs == end - beginning;
        } catch (Exception invalid) { return false; }
    }

    /** Precise authored timing additionally requires provenance from the actual cel asset. */
    public static boolean matchesFrameGrid(ProjectStore.Asset asset, ProjectStore.Clip clip) {
        if (!isCel(asset) || !matchesFrameGrid(clip)) return false;
        try {
            JSONObject source = asset.importMetadata.getJSONObject("animationCel"), exposure = clip.effects.getJSONObject("celExposure");
            return integer(source, "version", -1, 1, 1) == 1
                    && source.getString("celId").equals(exposure.getString("celId"))
                    && source.getString("generationId").equals(exposure.getString("generationId"));
        } catch (Exception invalid) { return false; }
    }

    /** Exact integer-rational arithmetic; cumulative boundaries avoid summed per-frame rounding. */
    public static long frameBoundaryMs(long frame, int numerator, int denominator) { return boundary(frame, numerator, denominator, 1000L); }
    public static long frameBoundaryUs(long frame, int numerator, int denominator) { return boundary(frame, numerator, denominator, 1_000_000L); }
    public static long exposureStartUs(ProjectStore.Clip clip) throws Exception {
        requireFrameAligned(clip); JSONObject exposure = clip.effects.getJSONObject("celExposure");
        return Math.addExact(Math.multiplyExact(integer(exposure, "originMs", 0, 0, MAX_CLOCK_MS), 1000L),
                frameBoundaryUs(exposure.getLong("startFrame"), fps(exposure), 1));
    }
    public static long exposureEndUs(ProjectStore.Clip clip) throws Exception {
        requireFrameAligned(clip); JSONObject exposure = clip.effects.getJSONObject("celExposure");
        return Math.addExact(Math.multiplyExact(integer(exposure, "originMs", 0, 0, MAX_CLOCK_MS), 1000L),
                frameBoundaryUs(Math.addExact(exposure.getLong("startFrame"), exposure.getLong("frameCount")), fps(exposure), 1));
    }
    public static int exposureFrameRate(ProjectStore.Clip clip) throws Exception { requireFrameAligned(clip); return fps(clip.effects.getJSONObject("celExposure")); }
    private static long boundary(long frame, int numerator, int denominator, long scale) {
        if (frame < 0 || frame > MAX_FRAME * 2L || numerator <= 0 || denominator <= 0) throw new IllegalArgumentException("Frame boundary is outside supported bounds");
        BigInteger n = BigInteger.valueOf(numerator);
        BigInteger rounded = BigInteger.valueOf(frame).multiply(BigInteger.valueOf(scale)).multiply(BigInteger.valueOf(denominator))
                .add(n.divide(BigInteger.valueOf(2))).divide(n);
        if (rounded.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw new IllegalArgumentException("Frame boundary exceeds native timing");
        return rounded.longValue();
    }
    private static int fps(JSONObject settings) throws Exception {
        long numerator = integer(settings, "fpsNumerator", 24, 1, 120000), denominator = integer(settings, "fpsDenominator", 1, 1, 10000);
        if (numerator % denominator != 0L || !supportedFrameRate((int) (numerator / denominator)))
            throw new IllegalArgumentException("Choose integer 12, 24, 30 or 60 fps; fractional encoding cadence is unavailable");
        return (int) (numerator / denominator);
    }
    public static boolean supportedFrameRate(int rate) { return rate == 12 || rate == 24 || rate == 30 || rate == 60; }
    private static void requireFrameAligned(ProjectStore.Clip clip) { if (!matchesFrameGrid(clip)) throw new IllegalStateException("This exposure was edited outside its frame grid; add a new frame-aligned exposure or use shared timeline timing controls"); }
    private static ProjectStore.Clip requireClip(ProjectStore.Project project, String id) { ProjectStore.Clip clip = project.clip(id); if (clip == null) throw new IllegalArgumentException("Cel exposure no longer exists"); return clip; }
    private static void requireUnlocked(ProjectStore.Project project, ProjectStore.Clip clip) { ProjectLinkedEdits.requireUnlocked(project, ProjectLinkedEdits.members(project, clip)); }
    private static void fields(JSONObject object, String... allowed) { HashSet<String> names = new HashSet<>(java.util.Arrays.asList(allowed)); Iterator<String> iterator = object.keys(); while (iterator.hasNext()) if (!names.contains(iterator.next())) throw new IllegalArgumentException("Unknown cel exposure setting"); }
    private static boolean bool(JSONObject object, String key, boolean fallback) throws Exception { if (!object.has(key)) return fallback; Object value = object.get(key); if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean"); return (Boolean) value; }
    private static long integer(JSONObject object, String key, long fallback, long min, long max) throws Exception {
        Object value = object.opt(key);
        if (value == null) { if (fallback < min || fallback > max) throw new IllegalArgumentException(key + " is required"); return fallback; }
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        try {
            long exact = new java.math.BigDecimal(value.toString()).longValueExact();
            if (exact < min || exact > max) throw new IllegalArgumentException(key + " is outside frame bounds");
            return exact;
        } catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException(key + " must be an exact bounded integer", invalid); }
    }
}
