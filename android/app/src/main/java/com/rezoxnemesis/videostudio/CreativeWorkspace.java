package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * App-private storage tier for compiler output, model staging, checkpoints and
 * regenerable creative artifacts. The project database remains the source of
 * truth; this workspace is a durable working set that can be trimmed safely.
 */
public final class CreativeWorkspace {
    private final File root;
    private final ProjectStore projects;

    public CreativeWorkspace(Context context) {
        this(context, null);
    }

    public CreativeWorkspace(Context context, ProjectStore projects) {
        this.projects = projects;
        root = new File(context.getFilesDir(), "creative_workspace");
        ensureDirectory(root);
        ensureDirectory(new File(root, "projects"));
        ensureDirectory(new File(root, "models"));
        ensureDirectory(new File(root, "cache"));
        ensureDirectory(new File(root, "jobs"));
    }

    public File projectRoot(String projectId) {
        String safe = safeId(projectId, "unassigned");
        File project = new File(new File(root, "projects"), safe);
        ensureDirectory(project);
        ensureDirectory(new File(project, "scenes"));
        ensureDirectory(new File(project, "generated"));
        ensureDirectory(new File(project, "masks"));
        ensureDirectory(new File(project, "depth"));
        ensureDirectory(new File(project, "pose"));
        ensureDirectory(new File(project, "flow"));
        ensureDirectory(new File(project, "rigs"));
        ensureDirectory(new File(project, "meshes"));
        ensureDirectory(new File(project, "audio"));
        ensureDirectory(new File(project, "checkpoints"));
        ensureDirectory(new File(project, "previews"));
        ensureDirectory(new File(project, "renders"));
        ensureDirectory(new File(project, "temp"));
        return project;
    }

    public JSONObject saveMotionScript(String projectId,
                                       String sceneName,
                                       String source,
                                       JSONObject creativeIr) throws Exception {
        File scenes = new File(projectRoot(projectId), "scenes");
        String base = safeId(sceneName, "scene");
        File sourceFile = new File(scenes, base + ".ms");
        File irFile = new File(scenes, base + ".creative-ir.json");
        atomicWrite(sourceFile, source == null ? "" : source);
        atomicWrite(irFile, creativeIr == null ? "{}" : creativeIr.toString(2));

        JSONObject out = new JSONObject();
        out.put("sourceFile", sourceFile.getAbsolutePath());
        out.put("irFile", irFile.getAbsolutePath());
        out.put("bytes", sourceFile.length() + irFile.length());
        return out;
    }

    public JSONObject status(String projectId) {
        JSONObject out = new JSONObject();
        try {
            File project = projectId == null || projectId.isEmpty() ? null : projectRoot(projectId);
            StatFs stat = new StatFs(root.getAbsolutePath());
            out.put("root", root.getAbsolutePath());
            out.put("workspaceBytes", sizeOf(root));
            out.put("projectBytes", project == null ? 0 : sizeOf(project));
            out.put("modelsBytes", sizeOf(new File(root, "models")));
            out.put("cacheBytes", sizeOf(new File(root, "cache")));
            out.put("jobsBytes", sizeOf(new File(root, "jobs")));
            out.put("freeBytes", stat.getAvailableBytes());
            out.put("totalBytes", stat.getTotalBytes());
            out.put("storageTier", "device-app-private-hot-workspace");
            out.put("cloudTierArchitecture", "folder-scoped-storage-access-framework");
            out.put("cloudTierSupportsProjectArchiveRestore", true);
            out.put("cloudTierSupportsModelPackColdStorage", true);
            out.put("cloudTierUsesBroadDriveOAuth", false);
            out.put("galleryAccess", false);
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Evicts bulky project working data only after the caller has verified a
     * cloud archive. MotionScript source and durable DAG checkpoints stay hot
     * locally so ChatGPT can still inspect and plan the project without
     * downloading the full working set.
     */
    public JSONObject evictCloudBackedProject(String projectId) {
        throw new IllegalArgumentException("A verified archive location is required before offload");
    }

    public JSONObject evictCloudBackedProject(String projectId, JSONObject archive) throws Exception {
        requireReferenceStore();
        if (projectId == null || projectId.trim().isEmpty()) throw new IllegalArgumentException("projectId is required");
        if (archive == null || !archive.optBoolean("ok") || !projectId.equals(archive.optString("projectId"))
                || archive.optString("archiveTreeUri").isEmpty() || archive.optString("archiveDirectoryUri").isEmpty()) {
            throw new IllegalArgumentException("A complete verified archive with storage provenance is required");
        }
        JSONObject archivedFiles = archive.optJSONObject("archivedFiles");
        if (archivedFiles == null) throw new IllegalArgumentException("Verified archive file index is required before offload");
        File project = projectRoot(projectId);
        File markerFile = new File(project, "cloud_offload.json");
        if (markerFile.exists())
            throw new IllegalStateException("Restore the previously offloaded workspace before replacing its archive recovery location");
        long before = sizeOf(project);
        JSONObject marker = new JSONObject();
        marker.put("projectId", projectId);
        marker.put("offloadedAt", System.currentTimeMillis());
        marker.put("archiveTreeUri", archive.getString("archiveTreeUri"));
        marker.put("archiveDirectoryUri", archive.getString("archiveDirectoryUri"));
        marker.put("archiveRevision", archive.optLong("revision", 0L));
        marker.put("cloudArchiveRequiredForRehydrate", true);
        marker.put("preservedScenes", true);
        marker.put("preservedCheckpoints", true);
        marker.put("preservedOriginalAssets", true);
        // Commit recovery provenance BEFORE removing the first intermediate.
        atomicWrite(markerFile, marker.toString(2));
        long removed = 0L;
        // Generated assets, audio and published renders can be project sources.
        // Only checksummed intermediate files represented by this archive go cold.
        for (String name : new String[]{"masks", "depth", "pose", "flow", "rigs", "meshes", "previews", "temp"}) {
            removed = StorageBudget.saturatingAdd(removed, deleteVerifiedContents(project, new File(project, name), archivedFiles));
        }
        marker.put("removedBytes", removed);
        atomicWrite(markerFile, marker.toString(2));
        if (removed == 0L && !markerFile.delete()) throw new IllegalStateException("Could not clear an unused offload marker");
        JSONObject out = new JSONObject();
        out.put("projectId", projectId);
        out.put("beforeBytes", before);
        out.put("removedBytes", removed);
        out.put("afterBytes", sizeOf(project));
        out.put("preservedScenes", true);
        out.put("preservedCheckpoints", true);
        out.put("preservedSQLiteProjectState", true);
        out.put("preservedExports", true);
        out.put("preservedOriginalAssets", true);
        out.put("requiresCloudRestoreForEvictedIntermediates", removed > 0);
        return out;
    }

    private long deleteVerifiedContents(File project, File directory, JSONObject archived) throws Exception {
        return deleteVerifiedContents(project, directory, archived, new java.util.HashSet<>(), 0);
    }
    private long deleteVerifiedContents(File project, File directory, JSONObject archived,
                                        java.util.Set<String> visited, int depth) throws Exception {
        if (depth > 64 || visited.size() >= 16384 || !visited.add(directory.getCanonicalPath())) return 0L;
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long removed = 0L;
        String rootPath = project.getCanonicalPath() + File.separator;
        for (File file : children) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Offload cancelled");
            String path = file.getCanonicalPath();
            if (!path.startsWith(rootPath)) throw new IllegalArgumentException("Workspace path escapes project");
            if (!path.equals(new File(directory.getCanonicalFile(), file.getName()).getAbsolutePath())) continue;
            if (file.isDirectory()) {
                removed = StorageBudget.saturatingAdd(removed, deleteVerifiedContents(project, file, archived, visited, depth + 1));
                File[] remaining = file.listFiles();
                if (remaining != null && remaining.length == 0) file.delete();
            } else {
                if (projects.referencesMediaUri(android.net.Uri.fromFile(file).toString())) continue;
                JSONObject expected = archived.optJSONObject(path.substring(rootPath.length()).replace(File.separatorChar, '/'));
                if (expected == null || expected.optLong("size", -1L) != file.length()) continue;
                long originalSize = file.length();
                long originalModified = file.lastModified();
                java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[256 * 1024];
                try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Offload cancelled");
                        if (count > 0) hash.update(buffer, 0, count);
                    }
                }
                StringBuilder checksum = new StringBuilder(64);
                for (byte value : hash.digest()) checksum.append(String.format(Locale.ROOT, "%02x", value & 255));
                if (!expected.optString("sha256").contentEquals(checksum)) continue;
                if (file.length() != originalSize || file.lastModified() != originalModified) continue;
                long bytes = file.length();
                // SQLite serialization covers the last reference scan and delete;
                // an admitted export cannot race between those two operations.
                if (projects.deleteMediaIfUnreferenced(file, originalSize, originalModified))
                    removed = StorageBudget.saturatingAdd(removed, bytes);
            }
        }
        return removed;
    }

    public void markCloudHydrated(String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) return;
        File marker = new File(projectRoot(projectId), "cloud_offload.json");
        if (marker.exists() && !marker.delete()) throw new IllegalStateException("Restored workspace marker could not be cleared");
    }

    public JSONObject cloudOffloadState(String projectId) {
        JSONObject out = new JSONObject();
        try {
            File marker = new File(projectRoot(projectId), "cloud_offload.json");
            if (marker.isFile()) {
                if (marker.length() > 64 * 1024L) throw new IllegalStateException("Invalid offload marker size");
                out = new JSONObject(new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            }
            out.put("offloaded", marker.isFile());
            out.put("markerBytes", marker.isFile() ? marker.length() : 0);
            out.put("projectId", projectId == null ? "" : projectId);
        } catch (Exception error) {
            try { out.put("offloaded", true); out.put("error", "Offload marker requires recovery"); } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Removes only regenerable workspace material. Source project assets,
     * SQLite project state and MediaStore exports are deliberately untouched.
     */
    public JSONObject cleanupRegenerable(String projectId) {
        requireReferenceStore();
        long before = sizeOf(root);
        long removed = 0;
        if (projectId != null && !projectId.isEmpty()) {
            File project = projectRoot(projectId);
            removed = StorageBudget.saturatingAdd(removed, deleteContents(new File(project, "temp")));
            removed = StorageBudget.saturatingAdd(removed, deleteContents(new File(project, "previews")));
            removed = StorageBudget.saturatingAdd(removed, deleteContents(new File(project, "flow")));
        } else {
            removed = StorageBudget.saturatingAdd(removed, deleteContents(new File(root, "cache")));
            removed = StorageBudget.saturatingAdd(removed, deleteContents(new File(root, "jobs")));
        }

        JSONObject out = new JSONObject();
        try {
            out.put("removedBytes", removed);
            out.put("beforeBytes", before);
            out.put("afterBytes", sizeOf(root));
            out.put("preservedProjectState", true);
            out.put("preservedExports", true);
            out.put("preservedModelPacks", true);
            out.put("preservedReferencedMedia", true);
        } catch (Exception ignored) {}
        return out;
    }

    private static void atomicWrite(File target, String content) throws Exception {
        ensureDirectory(target.getParentFile());
        File temp = new File(target.getParentFile(), target.getName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        StorageVault.commit(temp, target);
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    private void requireReferenceStore() {
        if (projects == null) throw new IllegalStateException("Workspace cleanup requires the project reference ledger");
    }

    private long deleteContents(File dir) {
        return deleteContents(dir, new java.util.HashSet<>(), 0);
    }
    private long deleteContents(File dir, java.util.Set<String> visited, int depth) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        try {
            if (depth > 64 || visited.size() >= 16384 || !visited.add(dir.getCanonicalPath())) return 0;
        } catch (java.io.IOException inaccessible) { return 0; }
        long removed = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                if (!child.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) continue;
                if (!child.getCanonicalPath().equals(new File(dir.getCanonicalFile(), child.getName()).getAbsolutePath())) continue;
            } catch (java.io.IOException inaccessible) { continue; }
            if (child.isDirectory()) {
                removed = StorageBudget.saturatingAdd(removed, deleteContents(child, visited, depth + 1));
                File[] remaining = child.listFiles();
                if (remaining != null && remaining.length == 0) child.delete();
            } else {
                long bytes = child.length();
                if (projects.deleteMediaIfUnreferenced(child)) removed = StorageBudget.saturatingAdd(removed, bytes);
            }
        }
        return removed;
    }

    private static void ensureDirectory(File dir) {
        if (dir == null || dir.exists()) return;
        if (!dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Could not create workspace directory: " + dir);
        }
    }

    private static String safeId(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) raw = fallback;
        String safe = raw.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (safe.length() > 96) safe = safe.substring(0, 96);
        return safe.toLowerCase(Locale.US);
    }
}
