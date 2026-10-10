package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

/** Shared vector-stroke renderer and immutable PNG publication for owner-authored animation cels. */
public final class AnimationCelFactory {
    public static final int MAX_DRAWING_BYTES = 128 * 1024;
    public static final int MAX_POINTS = 8192;
    public static final int MAX_STROKES = 512;
    public static final int MAX_DIMENSION = 2048;
    public static final long MAX_BITMAP_BYTES = 16L * 1024L * 1024L;
    private static final Object PUBLICATION_LOCK = new Object();
    private final Context context;
    private final ProjectStore store;

    public AnimationCelFactory(Context context, ProjectStore store) {
        if (store == null) throw new IllegalArgumentException("Cel publication requires the project ownership ledger");
        this.context = context.getApplicationContext(); this.store = store;
    }

    public static final class Result {
        public final ProjectStore.Project project;
        public final String assetId;
        public final String clipId;
        public final boolean reused;
        private Result(ProjectStore.Project project, String assetId, String clipId, boolean reused) {
            this.project = project; this.assetId = assetId; this.clipId = clipId; this.reused = reused;
        }
    }

    public static final class RenderedCel {
        public final String projectId;
        public final String celId;
        public final ProjectStore.Asset asset;
        public final File file;
        private final File receipt;
        private final String assetJson;
        private final long fileLength;
        private final long fileModified;
        private RenderedCel(String projectId, String celId, ProjectStore.Asset asset, File file, File receipt) {
            this.projectId = projectId; this.celId = celId; this.asset = asset; this.file = file; this.receipt = receipt;
            assetJson = asset.toJson().toString();
            fileLength = file.length(); fileModified = file.lastModified();
        }
        ProjectStore.Asset registeredAsset() throws Exception { return ProjectStore.Asset.fromJson(new JSONObject(assetJson)); }
    }

    /** Called on a worker: rasterize/read back first, then commit at the exact accepted revision. */
    public Result create(String projectId, long expectedRevision, JSONObject drawing, JSONObject exposure, String stableRequestKey) throws Exception {
        identity(projectId); identity(stableRequestKey); exactRevision(expectedRevision);
        synchronized (PUBLICATION_LOCK) {
            String assetId = stableId(projectId, "create", stableRequestKey, "asset");
            String clipId = stableId(projectId, "create", stableRequestKey, "clip");
            ProjectStore.Project before = requireProject(projectId);
            Result reused = replay(before, assetId, clipId, stableRequestKey);
            if (reused != null) return reused;
            revision(before, expectedRevision);
            JSONObject options = exposure == null ? new JSONObject() : new JSONObject(exposure.toString());
            String celId = stableId(projectId, "create", stableRequestKey, "cel");
            RenderedCel rendered = renderCel(projectId, celId, "", drawing, "create:" + stableRequestKey);
            boolean committed = false;
            try {
                ProjectStore.Project project = store.edit(projectId, expectedRevision, "Draw animation cel", latest -> {
                    checkInterrupted();
                    AnimationCelEdits.register(latest, rendered, options, clipId);
                });
                committed = true;
                return new Result(project, rendered.asset.id, clipId, false);
            } finally { if (!committed) cleanupIfUnreferenced(rendered); }
        }
    }

    /** Redraw only this exposure. Other exposures and undo snapshots retain the earlier immutable PNG. */
    public Result update(String projectId, long expectedRevision, String clipId, JSONObject drawing, String stableRequestKey) throws Exception {
        return update(projectId, expectedRevision, clipId, drawing, null, stableRequestKey);
    }

    /** Optional hold settings are committed with the redraw, never as a second owner revision. */
    public Result update(String projectId, long expectedRevision, String clipId, JSONObject drawing,
                         JSONObject exposureSettings, String stableRequestKey) throws Exception {
        identity(projectId); identity(clipId); identity(stableRequestKey); exactRevision(expectedRevision);
        synchronized (PUBLICATION_LOCK) {
            ProjectStore.Project before = requireProject(projectId);
            String assetId = stableId(projectId, "update", stableRequestKey, "asset");
            Result reused = replay(before, assetId, clipId, stableRequestKey);
            if (reused != null) return reused;
            revision(before, expectedRevision);
            ProjectStore.Clip selected = before.clip(clipId);
            if (selected == null) throw new IllegalArgumentException("Cel exposure no longer exists");
            ProjectStore.Asset previous = AnimationCelEdits.requireCelAsset(before, selected.assetId);
            String celId = previous.importMetadata.getJSONObject("animationCel").getString("celId");
            RenderedCel rendered = renderCel(projectId, celId, previous.id, drawing, "update:" + stableRequestKey);
            JSONObject options = exposureSettings == null ? null : new JSONObject(exposureSettings.toString());
            boolean committed = false;
            try {
                ProjectStore.Project project = store.edit(projectId, expectedRevision, "Redraw animation cel", latest -> {
                    checkInterrupted(); AnimationCelEdits.replace(latest, clipId, rendered);
                    if (options != null) AnimationCelEdits.applyRedrawExposure(latest, clipId, options);
                });
                committed = true;
                return new Result(project, rendered.asset.id, clipId, false);
            } finally { if (!committed) cleanupIfUnreferenced(rendered); }
        }
    }

    /** Pure schema validation returns a normalized, detached document. No hidden source paths are accepted. */
    public static JSONObject validateDrawing(JSONObject input) {
        try {
            if (input == null) throw new IllegalArgumentException("Drawing document is required");
            requireByteBudget(input, MAX_DRAWING_BYTES);
            fields(input, "version", "width", "height", "background", "strokes");
            if (integer(input, "version", 1, 1, 1) != 1) throw new IllegalArgumentException("Unsupported drawing version");
            int width = integer(input, "width", 512, 16, MAX_DIMENSION), height = integer(input, "height", 512, 16, MAX_DIMENSION);
            if ((long) width * height * 4L > MAX_BITMAP_BYTES) throw new IllegalArgumentException("Cel raster exceeds 16 MiB");
            String background = color(input, "background", "#00000000");
            Object supplied = input.opt("strokes");
            if (supplied != null && !(supplied instanceof JSONArray)) throw new IllegalArgumentException("strokes must be an array");
            JSONArray strokes = supplied == null ? new JSONArray() : (JSONArray) supplied;
            if (strokes.length() > MAX_STROKES) throw new IllegalArgumentException("Drawing exceeds 512 strokes");
            JSONArray normalized = new JSONArray(); int points = 0; HashSet<String> ids = new HashSet<>();
            for (int index = 0; index < strokes.length(); index++) {
                JSONObject stroke = strokes.optJSONObject(index);
                if (stroke == null) throw new IllegalArgumentException("Drawing stroke must be an object");
                JSONObject checked = validateStroke(stroke);
                if (!ids.add(checked.getString("id"))) throw new IllegalArgumentException("Stroke IDs must be unique");
                points += checked.getJSONArray("points").length();
                if (points > MAX_POINTS) throw new IllegalArgumentException("Drawing exceeds 8192 points");
                normalized.put(checked);
            }
            JSONObject result = new JSONObject().put("version", 1).put("width", width).put("height", height)
                    .put("background", background).put("strokes", normalized);
            requireByteBudget(result, MAX_DRAWING_BYTES);
            return result;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid cel drawing: " + error.getMessage(), error); }
    }

    /** Draws in document pixel coordinates. The caller may translate/scale its canvas first. */
    public static void render(Canvas canvas, JSONObject drawing) {
        JSONObject checked = validateDrawing(drawing);
        int width = checked.optInt("width"), height = checked.optInt("height");
        Paint background = new Paint(); background.setColor(Color.parseColor(checked.optString("background")));
        canvas.drawRect(0, 0, width, height, background);
        int foreground = canvas.saveLayer(0, 0, width, height, null);
        try {
            JSONArray strokes = checked.optJSONArray("strokes");
            for (int index = 0; index < strokes.length(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Cel drawing cancelled");
                paintStroke(canvas, strokes.optJSONObject(index), width, height);
            }
        } finally { canvas.restoreToCount(foreground); }
    }

    /** Incremental foreground renderer; erase clears foreground, not the separately drawn background. */
    public static void renderStroke(Canvas foreground, JSONObject stroke, int width, int height) {
        if (width < 16 || height < 16 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height * 4L > MAX_BITMAP_BYTES)
            throw new IllegalArgumentException("Cel foreground size is outside raster bounds");
        paintStroke(foreground, validateStroke(stroke), width, height);
    }

    /** One exact incremental step; from=null paints the initial dot only. No stroke-array copy. */
    public static void renderSegment(Canvas foreground, JSONObject stroke, JSONObject from, JSONObject to, int width, int height) {
        if (width < 16 || height < 16 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height * 4L > MAX_BITMAP_BYTES)
            throw new IllegalArgumentException("Cel foreground size is outside raster bounds");
        try {
            String type = stroke.getString("type");
            if (!"paint".equals(type) && !"erase".equals(type)) throw new IllegalArgumentException("Stroke type must be paint or erase");
            double brush = number(stroke, "width", .008, .001, .2);
            Paint paint = strokePaint(type, color(stroke, "color", "#FF000000"));
            JSONObject beginning = from == null ? null : validatePoint(from), ending = validatePoint(to);
            paintSegment(foreground, paint, beginning, ending, (float) brush * Math.min(width, height), width, height);
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid drawing segment", error); }
    }

    public static Bitmap rasterizeBitmap(JSONObject drawing) {
        JSONObject checked = validateDrawing(drawing);
        int width = checked.optInt("width"), height = checked.optInt("height");
        Runtime runtime = Runtime.getRuntime(); long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        if (available < (long) width * height * 8L + 8L * 1024L * 1024L)
            throw new IllegalStateException("There is not enough available memory for this cel and its foreground layer");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try { render(new Canvas(bitmap), checked); return bitmap; }
        catch (RuntimeException | Error failure) { bitmap.recycle(); throw failure; }
    }

    private static JSONObject validateStroke(JSONObject input) {
        try {
            fields(input, "id", "type", "color", "width", "points");
            String id = input.getString("id"); identity(id);
            String type = input.getString("type");
            if (!"paint".equals(type) && !"erase".equals(type)) throw new IllegalArgumentException("Stroke type must be paint or erase");
            double width = number(input, "width", .008d, .001d, .2d);
            String color = color(input, "color", "#FF000000");
            JSONArray raw = input.optJSONArray("points");
            if (raw == null || raw.length() < 1 || raw.length() > MAX_POINTS) throw new IllegalArgumentException("Stroke needs 1 to 8192 points");
            JSONArray points = new JSONArray();
            for (int index = 0; index < raw.length(); index++) {
                JSONObject point = raw.optJSONObject(index);
                if (point == null) throw new IllegalArgumentException("Stroke points must be objects");
                points.put(validatePoint(point));
            }
            return new JSONObject().put("id", id).put("type", type).put("color", color).put("width", width).put("points", points);
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid drawing stroke: " + error.getMessage(), error); }
    }

    private static void paintStroke(Canvas canvas, JSONObject stroke, int width, int height) {
        Paint paint = strokePaint(stroke.optString("type"), stroke.optString("color"));
        JSONArray points = stroke.optJSONArray("points"); float brush = (float) (stroke.optDouble("width") * Math.min(width, height));
        paintSegment(canvas, paint, null, points.optJSONObject(0), brush, width, height);
        for (int index = 1; index < points.length(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Cel drawing cancelled");
            paintSegment(canvas, paint, points.optJSONObject(index - 1), points.optJSONObject(index), brush, width, height);
        }
    }
    private static Paint strokePaint(String type, String color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.parseColor(color));
        if ("erase".equals(type)) paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        return paint;
    }
    private static JSONObject validatePoint(JSONObject point) throws Exception {
        if (point == null) throw new IllegalArgumentException("Stroke point is required");
        fields(point, "x", "y", "pressure");
        return new JSONObject().put("x", number(point, "x", Double.NaN, 0, 1)).put("y", number(point, "y", Double.NaN, 0, 1))
                .put("pressure", number(point, "pressure", 1, .1, 1.5));
    }
    private static void paintSegment(Canvas canvas, Paint paint, JSONObject from, JSONObject to, float brush, int width, int height) {
        float x = (float) to.optDouble("x") * width, y = (float) to.optDouble("y") * height, pressure = (float) to.optDouble("pressure", 1);
        if (from != null) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(brush * ((float) from.optDouble("pressure", 1) + pressure) / 2f);
            canvas.drawLine((float) from.optDouble("x") * width, (float) from.optDouble("y") * height, x, y, paint);
        }
        paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, brush * pressure / 2f, paint);
    }

    /** Low-level owned artifact publication for callers using the same pure commit helper. */
    public RenderedCel renderCel(String projectId, String celId, String parentAssetId, JSONObject drawing, String stableRequestKey) throws Exception {
        synchronized (PUBLICATION_LOCK) { return renderCelLocked(projectId, celId, parentAssetId, drawing, stableRequestKey); }
    }

    private RenderedCel renderCelLocked(String projectId, String celId, String parentAssetId, JSONObject drawing, String stableRequestKey) throws Exception {
        identity(projectId); identity(celId);
        if (!UUID.fromString(celId).toString().equals(celId)) throw new IllegalArgumentException("Cel generation requires a canonical UUID");
        if (parentAssetId != null && !parentAssetId.isEmpty()) identity(parentAssetId);
        if (stableRequestKey == null) throw new IllegalArgumentException("A stable cel request identity is required");
        String action = stableRequestKey.startsWith("update:") ? "update" : "create";
        String request = stableRequestKey.startsWith("update:") || stableRequestKey.startsWith("create:")
                ? stableRequestKey.substring(stableRequestKey.indexOf(':') + 1) : stableRequestKey;
        identity(request); requireProject(projectId); checkInterrupted();
        JSONObject checked = validateDrawing(drawing);
        String assetId = stableId(projectId, action, request, "asset"), generationId = stableId(projectId, action, request, "generation");
        File project = new CreativeWorkspace(context).projectRoot(projectId).getCanonicalFile();
        File allowed = context.getFilesDir().getCanonicalFile();
        if (!project.getPath().startsWith(allowed.getPath() + File.separator)) throw new IllegalStateException("Cel workspace escaped app-owned storage");
        File directory = new File(new File(new File(new File(project, "generated/animation_cels"), uuid(projectId)), celId), generationId);
        ownedDirectory(directory, project);
        File target = new File(directory, "cel.png"), receipt = new File(directory, "CEL.json"), staging = new File(directory, "cel.png.partial");
        String documentHash = hash(checked.toString().getBytes(StandardCharsets.UTF_8));
        if (receipt.exists() && !regular(receipt, project)) throw new IllegalStateException("Cel receipt is not an owned regular file");
        if (receipt.isFile()) {
            JSONObject prior = readReceipt(receipt);
            if (!projectId.equals(prior.getString("projectId")) || !celId.equals(prior.getString("celId"))
                    || !stableRequestKey.equals(prior.getString("requestKey")) || !documentHash.equals(prior.getString("documentSha256"))
                    || !assetId.equals(prior.getJSONObject("asset").getString("id")))
                throw new IllegalStateException("This immutable cel generation belongs to another drawing request");
            if (!regular(target, project)) throw new IllegalStateException("Cel output is not an owned regular file");
            verifyPng(target, checked, prior.getString("pngSha256"));
            ProjectStore.Asset recorded = ProjectStore.Asset.fromJson(prior.getJSONObject("asset"));
            JSONObject cel = recorded.importMetadata.getJSONObject("animationCel");
            if (!Uri.fromFile(target).toString().equals(recorded.uri) || !"animation_cel".equals(recorded.role) || !recorded.generated
                    || !"image/png".equals(recorded.mime) || recorded.width != checked.getInt("width") || recorded.height != checked.getInt("height")
                    || recorded.sizeBytes != target.length() || recorded.hasAudio || recorded.rotation != 0
                    || !documentHash.equals(cel.getString("documentSha256")) || !celId.equals(cel.getString("celId"))
                    || !generationId.equals(cel.getString("generationId")) || !request.equals(cel.getString("requestKey"))
                    || !prior.getString("pngSha256").equals(cel.getString("pngSha256"))
                    || !Uri.fromFile(receipt).toString().equals(cel.getString("receiptUri"))
                    || !(parentAssetId == null ? "" : parentAssetId).equals(cel.getString("parentAssetId"))
                    || !documentHash.equals(hash(validateDrawing(cel.getJSONObject("document")).toString().getBytes(StandardCharsets.UTF_8))))
                throw new IllegalStateException("Cel receipt source metadata differs from its immutable artifact");
            return new RenderedCel(projectId, celId, recorded, target, receipt);
        }
        if (target.exists() && !regular(target, project)) throw new IllegalStateException("Cel output path is not an owned regular file");
        if (staging.exists() && (!regular(staging, project) || !store.deleteMediaIfUnreferenced(staging)))
            throw new IllegalStateException("Interrupted cel staging file is still owned or cannot be cleared");
        if (directory.getUsableSpace() < MAX_BITMAP_BYTES + 32L * 1024L * 1024L) throw new IllegalStateException("Cel publication storage reserve is unavailable");
        Bitmap bitmap = rasterizeBitmap(checked);
        try {
            if (!staging.createNewFile()) throw new IllegalStateException("Cel staging file could not be reserved");
            try (FileOutputStream output = new FileOutputStream(staging)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("Cel PNG could not be encoded");
                output.flush(); output.getFD().sync();
            }
            checkInterrupted(); String pngHash = hashFile(staging); verifyPng(staging, checked, pngHash);
            if (target.exists()) {
                if (!pngHash.equals(hashFile(target))) throw new IllegalStateException("Unreceipted immutable cel differs from this request; preserve it for recovery");
                if (!store.deleteMediaIfUnreferenced(staging)) throw new IllegalStateException("Verified duplicate cel staging file could not be released");
            } else if (!staging.renameTo(target)) throw new IllegalStateException("Verified cel PNG could not be atomically published");
            ProjectStore.Asset asset = new ProjectStore.Asset(); asset.id = assetId; asset.uri = Uri.fromFile(target).toString();
            asset.name = "Animation cel"; asset.mime = "image/png"; asset.width = checked.getInt("width"); asset.height = checked.getInt("height");
            asset.hasAudio = false; asset.sizeBytes = target.length(); asset.seekable = true; asset.role = "animation_cel"; asset.generated = true;
            JSONObject metadata = new JSONObject().put("version", 1).put("celId", celId).put("generationId", generationId)
                    .put("requestKey", request).put("document", checked).put("pngSha256", pngHash).put("documentSha256", documentHash)
                    .put("parentAssetId", parentAssetId == null ? "" : parentAssetId).put("receiptUri", Uri.fromFile(receipt).toString());
            asset.importMetadata.put("animationCel", metadata); asset.generationMetadata.put("kind", "owner_drawn_animation_cel");
            JSONObject record = new JSONObject().put("projectId", projectId).put("celId", celId).put("requestKey", stableRequestKey)
                    .put("documentSha256", documentHash).put("pngSha256", pngHash).put("asset", asset.toJson());
            writeReceipt(receipt, record, project);
            return new RenderedCel(projectId, celId, asset, target, receipt);
        } finally { bitmap.recycle(); if (staging.exists()) store.deleteMediaIfUnreferenced(staging); }
    }

    /** Only a Factory-produced capability can enter cleanup, and SQLite protects every history/pin reference. */
    public boolean cleanupIfUnreferenced(RenderedCel cel) {
        synchronized (PUBLICATION_LOCK) {
            boolean removed = store.deleteMediaIfUnreferenced(cel.file, cel.fileLength, cel.fileModified);
            if (removed) store.deleteMediaIfUnreferenced(cel.receipt);
            return removed;
        }
    }

    private Result replay(ProjectStore.Project project, String assetId, String clipId, String request) throws Exception {
        ProjectStore.Asset asset = project.asset(assetId); ProjectStore.Clip clip = project.clip(clipId);
        if (asset == null) return null;
        if (clip == null || !assetId.equals(clip.assetId) || !AnimationCelEdits.isCel(asset)
                || !request.equals(asset.importMetadata.getJSONObject("animationCel").getString("requestKey")))
            throw new IllegalStateException("This cel request was already applied; its owner-edited exposure cannot be recreated");
        Uri uri = Uri.parse(asset.uri);
        if (!"file".equals(uri.getScheme()) || uri.getPath() == null) throw new IllegalStateException("Cel source is no longer an owned PNG");
        File file = new File(uri.getPath());
        File root = new CreativeWorkspace(context).projectRoot(project.id).getCanonicalFile();
        if (!regular(file, root)) throw new IllegalStateException("Cel source is no longer an owned regular PNG");
        verifyPng(file, asset.importMetadata.getJSONObject("animationCel").getJSONObject("document"),
                asset.importMetadata.getJSONObject("animationCel").getString("pngSha256"));
        return new Result(project, assetId, clipId, true);
    }
    private ProjectStore.Project requireProject(String id) { ProjectStore.Project project = store.get(id); if (project == null) throw new IllegalArgumentException("Cel project no longer exists"); return project; }
    private static void revision(ProjectStore.Project project, long expected) { if (project.revision != expected) throw new ProjectStore.RevisionConflictException(expected, project.revision); }
    private static void exactRevision(long revision) { if (revision < 0) throw new IllegalArgumentException("Cel drawing requires an exact accepted project revision"); }
    static String stableId(String project, String action, String request, String role) { return uuid("animation-cel:" + project + ":" + action + ":" + request + ":" + role); }
    private static String uuid(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static void identity(String value) { if (value == null || value.isEmpty() || value.length() > 256 || !value.equals(value.trim())) throw new IllegalArgumentException("A stable cel/request identity is required"); for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("Cel/request identity contains whitespace"); }
    private static void fields(JSONObject value, String... allowed) { java.util.HashSet<String> names = new java.util.HashSet<>(java.util.Arrays.asList(allowed)); Iterator<String> iterator = value.keys(); while (iterator.hasNext()) if (!names.contains(iterator.next())) throw new IllegalArgumentException("Unknown drawing field"); }
    private static int integer(JSONObject json, String key, int fallback, int min, int max) { double value = number(json, key, fallback, min, max); if (value != Math.rint(value)) throw new IllegalArgumentException(key + " must be an integer"); return (int) value; }
    private static double number(JSONObject json, String key, double fallback, double min, double max) { Object value = json.opt(key); if (value != null && !(value instanceof Number)) throw new IllegalArgumentException(key + " must be numeric"); double result = value == null ? fallback : ((Number) value).doubleValue(); if (!Double.isFinite(result) || result < min || result > max) throw new IllegalArgumentException(key + " is outside drawing bounds"); return result; }
    private static String color(JSONObject json, String key, String fallback) throws Exception { Object raw = json.has(key) ? json.get(key) : fallback; if (!(raw instanceof String) || !((String) raw).matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new IllegalArgumentException(key + " must be #RRGGBB or #AARRGGBB"); String value = ((String) raw).toUpperCase(java.util.Locale.ROOT); Color.parseColor(value); return value; }
    private static void requireByteBudget(JSONObject json, int max) { String value = json.toString(); if (value.length() > max || value.getBytes(StandardCharsets.UTF_8).length > max) throw new IllegalArgumentException("Drawing exceeds its 128 KiB vector budget"); }
    private static void ownedDirectory(File directory, File project) throws Exception { if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile()) || !directory.getAbsolutePath().startsWith(project.getPath() + File.separator)) throw new IllegalStateException("Cel generation path is aliased or outside its project"); if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cel storage could not be created"); }
    private static boolean regular(File file, File project) throws Exception { return file.isFile() && file.getCanonicalFile().equals(file.getAbsoluteFile()) && file.getPath().startsWith(project.getPath() + File.separator); }
    private static void verifyPng(File file, JSONObject drawing, String expectedHash) throws Exception { if (!file.isFile() || !file.getCanonicalFile().equals(file.getAbsoluteFile()) || file.length() <= 0 || file.length() > MAX_BITMAP_BYTES + 1024L * 1024L || !expectedHash.equals(hashFile(file))) throw new IllegalStateException("Immutable cel PNG checksum is unavailable or changed"); BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getAbsolutePath(), bounds); if (bounds.outWidth != drawing.getInt("width") || bounds.outHeight != drawing.getInt("height") || !"image/png".equals(bounds.outMimeType)) throw new IllegalStateException("Cel PNG dimensions do not match the editable drawing"); }
    private static String hash(byte[] data) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static String hashFile(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[256 * 1024]; try (FileInputStream input = new FileInputStream(file)) { int count; while ((count = input.read(buffer)) != -1) { checkInterrupted(); if (count > 0) digest.update(buffer, 0, count); } } return hex(digest.digest()); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(bytes.length * 2); for (byte item : bytes) value.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16)); return value.toString(); }
    private static JSONObject readReceipt(File file) throws Exception { if (file.length() <= 0 || file.length() > 256 * 1024) throw new IllegalStateException("Cel receipt exceeds its metadata bound"); byte[] bytes = new byte[(int) file.length()]; try (FileInputStream input = new FileInputStream(file)) { int offset = 0; while (offset < bytes.length) { int count = input.read(bytes, offset, bytes.length - offset); if (count <= 0) throw new IllegalStateException("Cel receipt is truncated"); offset += count; } if (input.read() != -1) throw new IllegalStateException("Cel receipt changed while reading"); } return ProjectMediaReferenceIndex.decodeOwnershipMetadata(bytes); }
    private void writeReceipt(File file, JSONObject json, File project) throws Exception {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 256 * 1024) throw new IllegalArgumentException("Cel receipt metadata exceeds its bound");
        File staging = new File(file.getParentFile(), "CEL.json.partial");
        if (staging.exists() && (!regular(staging, project) || !store.deleteMediaIfUnreferenced(staging)))
            throw new IllegalStateException("Cel receipt staging is owned or cannot be cleared");
        try {
            if (!staging.createNewFile()) throw new IllegalStateException("Cel receipt staging could not be reserved");
            try (FileOutputStream output = new FileOutputStream(staging)) { output.write(bytes); output.flush(); output.getFD().sync(); }
            checkInterrupted();
            if (file.exists() || !staging.renameTo(file)) throw new IllegalStateException("Cel receipt could not be atomically published");
        } finally { if (staging.exists() && regular(staging, project)) store.deleteMediaIfUnreferenced(staging); }
    }
    private static void checkInterrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cel drawing publication cancelled"); }
}
