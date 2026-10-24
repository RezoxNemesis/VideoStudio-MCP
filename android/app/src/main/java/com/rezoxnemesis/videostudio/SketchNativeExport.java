package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Background preparation of real raster frames for the existing native Media3 encoder. */
public final class SketchNativeExport {
    public interface Progress { void onProgress(int frameIndex, int frameCount) throws Exception; }
    public static final class Prepared {
        public final ProjectStore.Project project;
        public final File file;
        public final File encodingFile;
        public final String aspect, quality;
        public final JSONObject metadata;
        public final boolean completed;
        private final File generation;
        private final SketchExportFrameSource.Factory frameFactory;
        Prepared(ProjectStore.Project project, File file, String aspect, String quality, JSONObject metadata, boolean completed, File generation) {
            this(project,file,aspect,quality,metadata,completed,generation,null);
        }
        Prepared(ProjectStore.Project project, File file, String aspect, String quality, JSONObject metadata, boolean completed, File generation, SketchExportFrameSource.Factory frameFactory) {
            this.project = project; this.file = file; this.encodingFile = completed ? file : new File(generation,"sketch.encoding.mp4"); this.aspect = aspect; this.quality = quality; this.metadata = metadata; this.completed = completed; this.generation = generation;
            this.frameFactory=frameFactory;
        }
        SketchExportFrameSource openFrames() throws Exception {return frameFactory==null?SketchExportFrameSource.pngs(generation,metadata.getInt("frameCount")):frameFactory.open();}
        boolean streamsPages(){return frameFactory!=null;}
    }
    private SketchNativeExport() {}

    public static Prepared prepare(Context context, JSONObject args) throws Exception {
        return prepare(context, args, (frame, count) -> {});
    }
    public static Prepared prepare(Context context, JSONObject args, Progress progress) throws Exception {
        if(args!=null&&args.has("format"))return SketchStillExport.prepare(context,args,progress);
        SketchDocument.fields(args, "sketchId,expectedRevision,operationId,aspect,quality");
        String id = SketchDocument.id(args, "sketchId"), operationId = SketchDocument.id(args, "operationId");
        long revision = SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 1);
        String requestedAspect = args.optString("aspect", "original"), quality = args.optString("quality", "720p");
        if (!java.util.Arrays.asList("original","9:16","16:9","1:1").contains(requestedAspect)) throw SketchDocument.bad("Unsupported output aspect");
        if (!java.util.Arrays.asList("720p","1080p").contains(quality)) throw SketchDocument.bad("Unsupported output quality");
        String fingerprint = SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));
        File generation = new File(context.getFilesDir(), "sketch_studio/" + id + "/exports/" + operationId);
        if (!generation.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath() + "/sketch_studio/" + id + "/exports/" + operationId)) throw SketchDocument.bad("Owned export directory rejected");
        synchronized (SketchNativeExport.class) {
            try(SketchExportLock lock=SketchExportLock.acquire(context,generation)){
            File snapshotFile = new File(generation, "snapshot.json"); JSONObject snapshot;
            if (snapshotFile.isFile()) {
                snapshot = readJson(snapshotFile, SketchDocument.MAX_BYTES + 16 * 1024 * 1024 + 4096);
                if (!fingerprint.equals(snapshot.getString("requestSha256"))) throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT");
            } else {
                JSONObject document;
                try (SketchProjectStore store = new SketchProjectStore(context)) {
                    document = store.get(id); SketchProjectStore.requireRevision(document, revision); store.verifyReferences(document);
                }
                // Refuse disk pressure before rasterizing; PNG worst-case plus one frame buffer stays bounded.
                long frameBuffer = (long) document.getInt("width") * document.getInt("height") * 4;
                if (context.getFilesDir().getUsableSpace() < frameBuffer + 64L * 1024 * 1024) throw new IllegalStateException("SKETCH_EXPORT_STORAGE: insufficient free space");
                if (!generation.isDirectory() && !generation.mkdirs()) throw new IllegalStateException("Sketch output storage unavailable");
                JSONObject dependencies = SketchPaintArchive.freezeDependencies(context, document, new File(generation, "assets"));
                snapshot = new JSONObject().put("requestSha256", fingerprint).put("document", document)
                        .put("dependencyInventory", dependencies)
                        .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(document).getBytes(StandardCharsets.UTF_8)));
                atomicJson(snapshotFile, snapshot);
            }
            JSONObject doc = snapshot.getJSONObject("document"); SketchDocument.validate(doc);
            File ownedDependencyRoot = snapshot.has("dependencyInventory") ? new File(generation, "assets") : null;
            if (ownedDependencyRoot != null) SketchPaintArchive.verifyFrozen(context, doc, ownedDependencyRoot, snapshot.getJSONObject("dependencyInventory"));
            String aspect = "original".equals(requestedAspect) ? "source:" + doc.getInt("width") + "x" + doc.getInt("height") : requestedAspect;
            if (!id.equals(doc.getString("id")) || revision != doc.getLong("revision") ||
                    !snapshot.getString("documentSha256").equals(SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8))))
                throw new IllegalStateException("SKETCH_EXPORT_SNAPSHOT_CHANGED");
            File receipt = new File(generation, "completed.json"), output = new File(generation, "sketch.mp4");
            ProjectStore projects = new ProjectStore(context);
            String projectId = UUID.nameUUIDFromBytes(("sketch-output/" + id + "/" + operationId).getBytes(StandardCharsets.UTF_8)).toString();
            if (receipt.isFile()) {
                JSONObject metadata = readJson(receipt, 1024 * 1024); ProjectStore.Project project = projects.get(projectId);
                if (project == null || !output.isFile() || !metadata.getString("outputSha256").equals(SketchProjectStore.fileHash(output)))
                    throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_CHANGED");
                reconcileProject(context, projects, project, output, metadata);
                return new Prepared(project, output, aspect, quality, metadata, true, generation);
            }
            // A missing receipt means no committed encoding exists. Only this immutable
            // operation's private outputs may be replaced after interrupted encoding.
            for(File uncommitted:new File[]{output,new File(generation,"sketch.encoding.mp4")})
                if(uncommitted.exists()&&(!uncommitted.isFile()||!uncommitted.delete()))
                    throw new IllegalStateException("SKETCH_EXPORT_RECOVERY: cannot remove uncommitted owned encoding");
            ProjectStore.Project project = new ProjectStore.Project(); project.id = projectId;
            project.name = doc.getString("name") + " · Sketch export r" + revision;
            JSONArray frameEvidence = new JSONArray(); long actualBytes = 0;
            File manifest = new File(generation, "frames.json"); JSONObject previousManifest = manifest.isFile() ? readJson(manifest, 512 * 1024) : null;
            if (previousManifest != null && (!snapshot.getString("documentSha256").equals(previousManifest.getString("documentSha256")) ||
                    previousManifest.getInt("frameCount") != doc.getInt("frameCount") || previousManifest.getInt("fps") != doc.getInt("fps")))
                throw new IllegalStateException("SKETCH_EXPORT_MANIFEST_CHANGED");
            try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, doc, ownedDependencyRoot)) {
                for (int frame = 0; frame < doc.getInt("frameCount"); frame++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Sketch capture cancelled");
                    progress.onProgress(frame, doc.getInt("frameCount"));
                    long frameBuffer = (long) doc.getInt("width") * doc.getInt("height") * 4;
                    if (context.getFilesDir().getUsableSpace() < frameBuffer + 64L * 1024 * 1024) throw new IllegalStateException("SKETCH_EXPORT_STORAGE: insufficient free space");
                    String name = String.format(java.util.Locale.ROOT, "frame_%04d.png", frame); File png = new File(generation, name);
                    String hash;
                    if (previousManifest != null) {
                        JSONObject declared = previousManifest.getJSONArray("frames").getJSONObject(frame);
                        if (!name.equals(declared.getString("file")) || !png.isFile() || !declared.getString("sha256").equals(SketchProjectStore.fileHash(png)))
                            throw new IllegalStateException("SKETCH_EXPORT_FRAME_CHANGED");
                        hash = declared.getString("sha256");
                    } else {
                        Bitmap bitmap = renderer.render(frame); File temporary = new File(generation, name + ".part");
                        try {
                            try (BoundedOutput out = new BoundedOutput(temporary, Math.min(8L * 1024 * 1024, 512L * 1024 * 1024 - actualBytes))) {
                                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Sketch frame encoding failed"); out.getFD().sync();
                            }
                            if (png.exists() && !png.delete()) throw new IllegalStateException("Interrupted sketch frame cannot be replaced");
                            if (!temporary.renameTo(png)) throw new IllegalStateException("Sketch frame atomic publication failed");
                        } finally { bitmap.recycle(); temporary.delete(); }
                        hash = SketchProjectStore.fileHash(png);
                    }
                    actualBytes += png.length(); if (actualBytes > 512L * 1024 * 1024) throw new IllegalStateException("SKETCH_EXPORT_BUDGET");
                    int fps = doc.getInt("fps");
                    JSONObject timing = new JSONObject().put("frameClock", "rational-authored-v1").put("frameIndex", frame).put("fps", fps)
                            .put("presentationTimeUs", NativeFrameClock.presentationTimeUs(frame, fps)).put("durationUs", NativeFrameClock.frameDurationUs(frame, fps))
                            .put("sketchId", id).put("sketchRevision", revision).put("documentSha256", snapshot.getString("documentSha256"));
                    ProjectStore.Asset asset = new ProjectStore.Asset(); asset.id = "sketch_frame_" + frame; asset.uri = Uri.fromFile(png).toString();
                    asset.mime = "image/png"; asset.name = name; asset.role = "generated_sketch_frame"; asset.generated = true;
                    asset.sizeBytes = png.length(); asset.generationMetadata = timing; asset.seekable = true; asset.createdAt = 0; project.assets.add(asset);
                    ProjectStore.Clip clip = new ProjectStore.Clip(); clip.id = "sketch_clip_" + frame; clip.assetId = asset.id;
                    clip.inMs = 0; clip.outMs = NativeFrameClock.frameDurationMs(frame, fps);
                    clip.effects = SketchDocument.copy(timing).put("directorSequenceFrame", true).put("poseFps", fps); project.clips.add(clip);
                    frameEvidence.put(new JSONObject().put("file", name).put("sha256", hash).put("bytes", png.length()).put("frameIndex", frame)
                            .put("presentationTimeUs", NativeFrameClock.presentationTimeUs(frame, fps)));
                }
            }
            JSONObject metadata = new JSONObject().put("schema", "videostudio.sketch.export.v1").put("sketchId", id).put("sketchRevision", revision)
                    .put("operationId", operationId).put("outputProjectId", project.id).put("documentSha256", snapshot.getString("documentSha256"))
                    .put("renderer", "android-bitmap-canvas").put("frameClock", "rational-authored-v1").put("frameCount", doc.getInt("frameCount"))
                    .put("fps", doc.getInt("fps")).put("width", doc.getInt("width")).put("height", doc.getInt("height"))
                    .put("durationUs", NativeFrameClock.presentationTimeUs(doc.getInt("frameCount"), doc.getInt("fps")))
                    .put("rasterFramesCaptured", true).put("decodedFrameEvidenceAvailable", false).put("encoded", false).put("frames", frameEvidence);
            if (previousManifest == null) atomicJson(manifest, metadata);
            ProjectStore.Project existing = projects.get(project.id);
            if (existing == null) projects.save(project);
            else if (!SketchDocument.canonical(existing.toJson().getJSONArray("clips")).equals(SketchDocument.canonical(project.toJson().getJSONArray("clips"))) ||
                    !SketchDocument.canonical(existing.toJson().getJSONArray("assets")).equals(SketchDocument.canonical(project.toJson().getJSONArray("assets"))))
                throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_PROJECT_CHANGED");
            else project = existing;
            return new Prepared(project, output, aspect, quality, metadata, false, generation);
            }
        }
    }

    /** Call only after NativeRenderEngine completion. Captured clocks are not claimed as decoded frames. */
    public static JSONObject complete(Context context, Prepared prepared, JSONObject encoderResult) throws Exception {
        synchronized (SketchNativeExport.class) {
            try(SketchExportLock lock=SketchExportLock.acquire(context,prepared.generation)){
            if (prepared.completed) return SketchDocument.copy(prepared.metadata);
            if (!prepared.encodingFile.isFile() || prepared.encodingFile.length() < 1) throw new IllegalStateException("Sketch encoder output unavailable");
            try(java.io.RandomAccessFile encoded=new java.io.RandomAccessFile(prepared.encodingFile,"rw")){encoded.getFD().sync();}
            if(prepared.file.exists()||!prepared.encodingFile.renameTo(prepared.file))throw new IllegalStateException("Sketch encoding atomic publication failed");
            JSONObject metadata = SketchDocument.copy(prepared.metadata).put("encoded", true).put("encoderEvidence", encoderResult)
                    .put("outputUri", Uri.fromFile(prepared.file).toString()).put("outputBytes", prepared.file.length())
                    .put("outputSha256", SketchProjectStore.fileHash(prepared.file));
            atomicJson(new File(prepared.generation, "completed.json"), metadata);
            ProjectStore projects = new ProjectStore(context); ProjectStore.Project project = projects.get(prepared.project.id);
            if (project == null) throw new IllegalStateException("Sketch output project unavailable");
            reconcileProject(context, projects, project, prepared.file, metadata); return metadata;
            }
        }
    }
    /** Preserve the immutable private encoding and persist verified public publication separately. */
    public static JSONObject recordPublished(Context context, Prepared prepared, Uri published, String name) throws Exception {
        synchronized (SketchNativeExport.class) {
            try(SketchExportLock lock=SketchExportLock.acquire(context,prepared.generation)){
            File receipt = new File(prepared.generation, "completed.json"); JSONObject metadata = readJson(receipt, 1024 * 1024);
            if (published == null || !"content".equals(published.getScheme()) || !"media".equals(published.getAuthority()) || name == null || name.isEmpty() || name.length() > 160)
                throw SketchDocument.bad("Published sketch requires a MediaStore URI and bounded name");
            if (!prepared.file.isFile() || !metadata.getString("outputSha256").equals(SketchProjectStore.fileHash(prepared.file)) || !publishedMatches(context, published, metadata))
                throw new IllegalStateException("SKETCH_PUBLISHED_OUTPUT_CHANGED");
            if (metadata.has("publishedUri") && publishedMatches(context, Uri.parse(metadata.getString("publishedUri")), metadata)) {
                // A retried publication keeps the already verified generation identity.
            } else {
                metadata.put("publishedUri", published.toString()).put("publishedName", name).put("publicationBytesVerified", true);
                atomicJson(receipt, metadata);
            }
            ProjectStore projects = new ProjectStore(context); ProjectStore.Project project = projects.get(prepared.project.id);
            if (project == null) throw new IllegalStateException("Sketch output project unavailable");
            reconcileProject(context, projects, project, prepared.file, metadata); return metadata;
            }
        }
    }
    private static boolean publishedMatches(Context context, Uri uri, JSONObject metadata) {
        if (uri == null || !"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())) return false;
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); long bytes = 0, expected = metadata.getLong("outputBytes"); byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) { bytes += n; if (bytes > expected) return false; digest.update(buffer, 0, n); }
            if (bytes != expected) return false;
            StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return metadata.getString("outputSha256").equals(hash.toString());
        } catch (Exception unavailable) { return false; }
    }
    private static void reconcileProject(Context context, ProjectStore projects, ProjectStore.Project project, File output, JSONObject metadata) throws Exception {
        projects.guardedProject(project.id,project.updatedAt,current->{
            reconcileGuardedProject(context,projects,current,output,metadata);return null;
        });
    }
    private static void reconcileGuardedProject(Context context, ProjectStore projects, ProjectStore.Project project, File output, JSONObject metadata) throws Exception {
        String uri = Uri.fromFile(output).toString(); String assetId = "sketch_encoded_output";
        ProjectStore.Asset asset = project.asset(assetId);
        if (asset != null && (!uri.equals(asset.uri) || !metadata.getString("outputSha256").equals(asset.generationMetadata.optString("outputSha256"))))
            throw new IllegalStateException("SKETCH_EXPORT_OUTPUT_PROJECT_CHANGED");
        boolean changed = false;
        if (asset == null) {
            asset = new ProjectStore.Asset(); asset.id = assetId; asset.uri = uri; asset.name = output.getName(); asset.mime = "video/mp4";
            asset.generated = true; asset.role = "generated_sketch_video"; asset.sizeBytes = output.length(); asset.seekable = true;
            asset.durationMs = (metadata.getLong("durationUs") + 500) / 1000;
            asset.generationMetadata = new JSONObject().put("sketchId", metadata.getString("sketchId")).put("sketchRevision", metadata.getLong("sketchRevision"))
                    .put("documentSha256", metadata.getString("documentSha256")).put("outputSha256", metadata.getString("outputSha256"))
                    .put("frameCount", metadata.getInt("frameCount")).put("fps", metadata.getInt("fps")).put("encoderEvidence", metadata.getJSONObject("encoderEvidence"));
            project.assets.add(asset); changed = true;
        }
        String latest = uri, latestName = output.getName();
        if (metadata.has("publishedUri") && publishedMatches(context, Uri.parse(metadata.getString("publishedUri")), metadata)) { latest = metadata.getString("publishedUri"); latestName = metadata.getString("publishedName"); }
        if (!latest.equals(project.latestExportUri) || !latestName.equals(project.latestExportName)) { project.latestExportUri = latest; project.latestExportName = latestName; project.latestExportAt = asset.createdAt; changed = true; }
        if (changed) projects.save(project);
    }
    private static final class BoundedOutput extends FileOutputStream {
        private final long max; private long bytes;
        BoundedOutput(File file, long max) throws Exception { super(file); this.max = max; }
        private void reserve(int count) throws java.io.IOException { if (count < 0 || bytes + count > max) throw new java.io.IOException("SKETCH_EXPORT_BUDGET: PNG byte budget exceeded"); bytes += count; }
        @Override public void write(int b) throws java.io.IOException { reserve(1); super.write(b); }
        @Override public void write(byte[] b) throws java.io.IOException { write(b, 0, b.length); }
        @Override public void write(byte[] b, int offset, int length) throws java.io.IOException { reserve(length); super.write(b, offset, length); }
    }
    private static JSONObject readJson(File file, int max) throws Exception {
        if (file.length() > max) throw new IllegalStateException("Sketch snapshot/manifest exceeds budget");
        byte[] data = new byte[(int) file.length()]; try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0, n; while (offset < data.length && (n = in.read(data, offset, data.length - offset)) != -1) offset += n;
            if (offset != data.length) throw new IllegalStateException("Sketch snapshot truncated");
        } return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }
    private static void atomicJson(File target, JSONObject json) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(json.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!temporary.renameTo(target)) throw new IllegalStateException("Sketch metadata atomic publication failed");
        } finally { temporary.delete(); }
    }
}
