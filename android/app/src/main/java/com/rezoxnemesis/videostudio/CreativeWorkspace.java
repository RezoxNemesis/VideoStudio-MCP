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

    public CreativeWorkspace(Context context) {
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
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId is required");
        }
        File project = projectRoot(projectId);
        long before = sizeOf(project);
        long removed = 0;

        String[] coldEligible = {
                "generated", "masks", "depth", "pose", "flow",
                "rigs", "meshes", "audio", "previews", "renders", "temp"
        };
        for (String name : coldEligible) {
            removed += deleteContents(new File(project, name));
        }

        JSONObject marker = new JSONObject();
        try {
            marker.put("projectId", projectId);
            marker.put("offloadedAt", System.currentTimeMillis());
            marker.put("removedBytes", removed);
            marker.put("cloudArchiveRequiredForRehydrate", true);
            marker.put("preservedScenes", true);
            marker.put("preservedCheckpoints", true);
            atomicWrite(new File(project, "cloud_offload.json"), marker.toString(2));
        } catch (Exception error) {
            throw new IllegalStateException("Could not commit cloud-offload marker", error);
        }

        JSONObject out = new JSONObject();
        try {
            out.put("projectId", projectId);
            out.put("beforeBytes", before);
            out.put("removedBytes", removed);
            out.put("afterBytes", sizeOf(project));
            out.put("preservedScenes", true);
            out.put("preservedCheckpoints", true);
            out.put("preservedSQLiteProjectState", true);
            out.put("preservedExports", true);
            out.put("requiresCloudRestoreForEvictedIntermediates", removed > 0);
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject cloudOffloadState(String projectId) {
        JSONObject out = new JSONObject();
        try {
            File marker = new File(projectRoot(projectId), "cloud_offload.json");
            out.put("offloaded", marker.isFile());
            out.put("markerBytes", marker.isFile() ? marker.length() : 0);
            out.put("projectId", projectId == null ? "" : projectId);
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Removes only regenerable workspace material. Source project assets,
     * SQLite project state and MediaStore exports are deliberately untouched.
     */
    public JSONObject cleanupRegenerable(String projectId) {
        long before = sizeOf(root);
        long removed = 0;
        if (projectId != null && !projectId.isEmpty()) {
            File project = projectRoot(projectId);
            removed += deleteContents(new File(project, "temp"));
            removed += deleteContents(new File(project, "previews"));
            removed += deleteContents(new File(project, "flow"));
        } else {
            removed += deleteContents(new File(root, "cache"));
            removed += deleteContents(new File(root, "jobs"));
        }

        JSONObject out = new JSONObject();
        try {
            out.put("removedBytes", removed);
            out.put("beforeBytes", before);
            out.put("afterBytes", sizeOf(root));
            out.put("preservedProjectState", true);
            out.put("preservedExports", true);
            out.put("preservedModelPacks", true);
        } catch (Exception ignored) {}
        return out;
    }

    private static void atomicWrite(File target, String content) throws Exception {
        ensureDirectory(target.getParentFile());
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace " + target.getName());
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Could not commit " + target.getName());
        }
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    private static long deleteContents(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        long removed = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            long bytes = sizeOf(child);
            if (deleteTree(child)) removed += bytes;
        }
        return removed;
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        return file.delete();
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
